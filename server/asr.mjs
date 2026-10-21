// 语音识别：App 按住说话时把 16k PCM 实时传上来，交给常驻的 Python 识别进程（SenseVoice）
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { createInterface } from "node:readline";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer } from "ws";

const WORKER = join(dirname(fileURLToPath(import.meta.url)), "asr", "worker.py");

let proc = null;
let ready = null;
const listeners = new Map();

function worker() {
  if (proc) return ready;
  proc = spawn("python3", [WORKER], { stdio: ["pipe", "pipe", "inherit"] });
  ready = new Promise((ok) => {
    createInterface({ input: proc.stdout }).on("line", (line) => {
      let m;
      try { m = JSON.parse(line); } catch { return; }
      if (m.ready) return ok();
      listeners.get(m.s)?.(m);
    });
  });
  proc.on("exit", (code) => {
    console.error("[asr] worker exited", code);
    proc = null;
    for (const [, fn] of listeners) fn({ text: "", final: true, error: "识别服务重启了，再说一次" });
    listeners.clear();
  });
  return ready;
}

const send = (m) => proc?.stdin.write(JSON.stringify(m) + "\n");

/** 开一次识别：返回 push(pcm Buffer) / end() / cancel() */
export function session(onText) {
  const s = randomUUID();
  listeners.set(s, (m) => {
    if (m.final) listeners.delete(s);
    onText(m);
  });
  const queue = [];
  let started = false;
  worker().then(() => {
    send({ s, op: "start" });
    started = true;
    for (const q of queue.splice(0)) send(q);
  });
  const put = (m) => (started ? send(m) : queue.push(m));
  return {
    push: (buf) => buf.length && put({ s, op: "audio", pcm: buf.toString("base64") }),
    end: () => put({ s, op: "end" }),
    cancel: () => { put({ s, op: "cancel" }); listeners.delete(s); },
  };
}

/** 整段识别（实时连接中途断了时，App 松手后把整段录音补传上来） */
export function recognize(pcm) {
  return new Promise((ok) => {
    const ss = session((m) => m.final && ok(m.text || ""));
    for (let i = 0; i < pcm.length; i += 32000) ss.push(pcm.subarray(i, i + 32000));
    ss.end();
  });
}

/** /api/asr/live：二进制帧 = PCM；文本帧 "end" / "cancel" */
const wss = new WebSocketServer({ noServer: true, maxPayload: 1 << 20 });
export function asrUpgrade(req, socket, head, deviceOk) {
  if (new URL(req.url, "http://x").pathname !== "/api/asr/live") return false;
  if (!deviceOk(req)) {
    socket.end("HTTP/1.1 401 Unauthorized\r\n\r\n");
    return true;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    const ss = session((m) => {
      if (ws.readyState !== 1) return;
      ws.send(JSON.stringify({ text: m.text, final: m.final, error: m.error }));
      if (m.final) ws.close();
    });
    let ended = false;
    ws.on("message", (data, binary) => {
      if (binary) return ss.push(Buffer.from(data));
      const cmd = String(data);
      if (cmd === "end" && !ended) { ended = true; ss.end(); }
      if (cmd === "cancel") { ended = true; ss.cancel(); ws.close(); }
    });
    ws.on("close", () => !ended && ss.cancel());
  });
  return true;
}

/** 服务启动时先把模型加载好，第一次说话不用等 */
export const warm = () => worker();
