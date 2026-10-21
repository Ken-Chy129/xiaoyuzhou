"""语音识别常驻进程：SenseVoice（本地中文模型）+ Silero VAD 按停顿切句。

协议（一行一个 JSON）：
  stdin : {"s": 会话, "op": "start"} / {"s", "op": "audio", "pcm": base64(16k 单声道 int16)} / {"s", "op": "end"} / {"s", "op": "cancel"}
  stdout: {"s", "text": 当前全文, "final": bool}
"""
import base64
import json
import os
import sys
import time

import numpy as np
import sherpa_onnx

DIR = os.environ.get("ME_ASR_DIR", "/var/lib/me/asr")
SR = 16000
WINDOW = 512

rec = sherpa_onnx.OfflineRecognizer.from_sense_voice(
    model=f"{DIR}/model.int8.onnx", tokens=f"{DIR}/tokens.txt", num_threads=3, use_itn=True, language="zh",
)


def vad_new():
    cfg = sherpa_onnx.VadModelConfig()
    cfg.silero_vad.model = f"{DIR}/silero_vad.onnx"
    cfg.silero_vad.threshold = 0.4
    cfg.silero_vad.min_silence_duration = 0.6
    cfg.silero_vad.min_speech_duration = 0.2
    cfg.silero_vad.max_speech_duration = 8  # 一口气说很久也会切开，单句短，实时识别才跟得上
    cfg.sample_rate = SR
    return sherpa_onnx.VoiceActivityDetector(cfg, buffer_size_in_seconds=120)


def decode(samples):
    if len(samples) < SR * 0.25:
        return ""
    s = rec.create_stream()
    s.accept_waveform(SR, samples)
    rec.decode_stream(s)
    return s.result.text.strip()


def join(parts):
    out = ""
    for p in parts:
        if not p:
            continue
        if out and out[-1] not in "，。！？,.!?、；;：:":
            out += "，"
        out += p
    return out


class Session:
    def __init__(self):
        self.vad = vad_new()
        self.audio = np.zeros(0, dtype=np.float32)  # 全部录音
        self.fed = 0  # 已经喂给 VAD 的样本数
        self.done_to = 0  # 已经定稿的句子结束位置
        self.parts = []
        self.next_partial = 0.0
        self.last_sent = None

    def pump(self):
        while self.fed + WINDOW <= len(self.audio):
            self.vad.accept_waveform(self.audio[self.fed:self.fed + WINDOW])
            self.fed += WINDOW
        self.collect()

    def collect(self):
        while not self.vad.empty():
            seg = self.vad.front
            # 往前多带 0.2 秒，避免切掉第一个字
            start = max(self.done_to, seg.start - int(0.2 * SR))
            end = seg.start + len(seg.samples)
            self.parts.append(decode(self.audio[start:end]))
            self.done_to = end
            self.vad.pop()

    def text(self, partial=True):
        tail = ""
        if partial and self.vad.is_speech_detected():
            t = time.time()
            tail = decode(self.audio[max(self.done_to, self.fed - SR * 8):self.fed])
            # 实时结果别把进程占满：下一次至少隔「本次耗时 × 3」
            self.next_partial = time.time() + max(0.6, (time.time() - t) * 3)
        return join(self.parts + [tail])

    def finish(self):
        # 剩下的不足一个窗口也喂进去，再强制收尾
        if self.fed < len(self.audio):
            rest = self.audio[self.fed:]
            self.vad.accept_waveform(np.pad(rest, (0, WINDOW - len(rest) % WINDOW)))
            self.fed = len(self.audio)
        self.vad.flush()
        self.collect()
        text = join(self.parts)
        # VAD 一段都没切出来（说得很轻）：整段直接识别兜底
        if not text:
            text = decode(self.audio)
        return text


sessions = {}


def emit(sid, text, final):
    sys.stdout.write(json.dumps({"s": sid, "text": text, "final": final}, ensure_ascii=False) + "\n")
    sys.stdout.flush()


sys.stdout.write(json.dumps({"ready": True}) + "\n")
sys.stdout.flush()

for line in sys.stdin:
    try:
        m = json.loads(line)
    except ValueError:
        continue
    sid, op = m.get("s"), m.get("op")
    try:
        if op == "start":
            sessions[sid] = Session()
        elif op == "audio":
            ss = sessions.get(sid)
            if not ss:
                continue
            pcm = np.frombuffer(base64.b64decode(m["pcm"]), dtype=np.int16).astype(np.float32) / 32768
            ss.audio = np.concatenate([ss.audio, pcm])
            before = len(ss.parts)
            ss.pump()
            now = time.time()
            # 句子定稿了马上推；说话中隔一会推一次实时结果
            settled = len(ss.parts) != before
            if settled or now >= ss.next_partial:
                t = ss.text(partial=not settled or now >= ss.next_partial)
                if t != ss.last_sent:
                    ss.last_sent = t
                    emit(sid, t, False)
        elif op == "end":
            ss = sessions.pop(sid, None)
            emit(sid, ss.finish() if ss else "", True)
        elif op == "cancel":
            sessions.pop(sid, None)
    except Exception as e:  # 一个会话出错不影响别的
        sys.stderr.write(f"[asr] {sid} {op}: {e}\n")
        sessions.pop(sid, None)
        emit(sid, "", True)
