package cn.kenchy.me.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * App 内语音输入：手机录音（16k PCM）实时传到自己的服务器，服务器上的中文识别模型边听边出字。
 * 不依赖手机自带的语音服务（小米的那个会按停顿切句、超时、只给最后一句）。
 *
 * 整段录音同时留在手机内存里：实时连接中途断了，松手后把整段补传上去识别，说的话不会丢。
 */
@Stable
class VoiceState internal constructor(private val ctx: Context, private val hold: Boolean = false) {
    var listening by mutableStateOf(false)
        private set
    /** 识别中：已松手，等最后结果 */
    var finishing by mutableStateOf(false)
        private set
    private var partial by mutableStateOf("")
    /** 0..1，说话音量，用来画波形 */
    var level by mutableFloatStateOf(0f)
        private set

    private var onFinal: ((String) -> Unit)? = null
    internal var askPermission: (() -> Unit)? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    private var record: android.media.AudioRecord? = null
    private var reader: Thread? = null
    @Volatile private var recording = false
    private var ws: okhttp3.WebSocket? = null
    @Volatile private var wsOk = true
    private var all = java.io.ByteArrayOutputStream()
    private var session = 0
    private var delivered = false

    /** 界面上显示的「正在识别」 */
    val live: String get() = partial

    fun start(onText: (String) -> Unit) {
        if (listening || finishing) return
        onFinal = onText
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            askPermission?.invoke()
            return
        }
        val sr = 16000
        val min = android.media.AudioRecord.getMinBufferSize(sr, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            @Suppress("MissingPermission")
            android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
                android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sr * 2),
            ).takeIf { it.state == android.media.AudioRecord.STATE_INITIALIZED }
        }.getOrNull()
        if (rec == null) {
            Toast.makeText(ctx, "麦克风不可用", Toast.LENGTH_SHORT).show()
            return
        }
        val id = ++session
        delivered = false
        partial = ""
        all = java.io.ByteArrayOutputStream()
        wsOk = true
        listening = true
        record = rec
        ws = cn.kenchy.me.MeApp.instance.api.asrLive(object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject }.getOrNull() ?: return
                val t = (o["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val fin = (o["final"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                main.post {
                    if (id != session) return@post
                    if (fin) deliver(id, t) else partial = t
                }
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                wsOk = false
                // 已经松手在等结果：直接整段补传
                main.post { if (id == session && finishing) fallbackUpload(id) }
            }
            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                main.post { if (id == session && finishing && !delivered) fallbackUpload(id) }
            }
        })
        rec.startRecording()
        recording = true
        val socket = ws
        reader = Thread {
            val buf = ByteArray(3200) // 100ms
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val chunk = buf.copyOf(n)
                synchronized(this) { all.write(chunk) }
                if (wsOk) socket?.send(okio.ByteString.of(*chunk))
                var sum = 0.0
                for (i in 0 until n / 2) {
                    val s = ((chunk[2 * i + 1].toInt() shl 8) or (chunk[2 * i].toInt() and 0xff)).toShort().toDouble()
                    sum += s * s
                }
                val rms = kotlin.math.sqrt(sum / maxOf(1, n / 2))
                val lv = ((20 * kotlin.math.log10(rms.coerceAtLeast(1.0)) - 35) / 35).toFloat().coerceIn(0f, 1f)
                main.post { if (id == session) level = lv }
            }
        }.apply { name = "voice"; start() }
    }

    private fun stopRecording() {
        recording = false
        runCatching { reader?.join(300) }
        reader = null
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        level = 0f
    }

    /** 松手：停止录音，等最后结果 */
    fun stop() {
        if (!listening) return
        stopRecording()
        listening = false
        val id = session
        val tooShort = synchronized(this) { all.size() } < 16000 // 不到半秒
        if (tooShort) { cancel(); return }
        finishing = true
        if (wsOk && ws?.send("end") == true) {
            // 兜底：8 秒还没出结果，就整段补传
            main.postDelayed({ if (id == session && finishing && !delivered) fallbackUpload(id) }, 8000)
        } else fallbackUpload(id)
    }

    private var uploading = -1
    private fun fallbackUpload(id: Int) {
        if (delivered || uploading == id) return
        uploading = id
        runCatching { ws?.cancel() }
        val pcm = synchronized(this) { all.toByteArray() }
        scope.launch {
            val r = runCatching { cn.kenchy.me.MeApp.instance.api.asr(pcm) }
            if (id != session) return@launch
            r.onSuccess { deliver(id, it) }
                .onFailure {
                    Toast.makeText(ctx, "识别失败：网络不通，录音已丢弃", Toast.LENGTH_SHORT).show()
                    deliver(id, "")
                }
        }
    }

    private fun deliver(id: Int, text: String) {
        if (delivered || id != session) return
        delivered = true
        finishing = false
        partial = ""
        runCatching { ws?.close(1000, null) }
        ws = null
        val t = text.trim()
        if (t.isNotEmpty()) onFinal?.invoke(t)
        else Toast.makeText(ctx, "没听清，再说一次", Toast.LENGTH_SHORT).show()
    }

    /** 上滑取消：说的都不要了 */
    fun cancel() {
        session++
        stopRecording()
        runCatching { ws?.send("cancel") }
        runCatching { ws?.close(1000, null) }
        ws = null
        listening = false
        finishing = false
        partial = ""
    }

    internal fun resumeAfterPermission() {
        // 按住说话时，授权弹窗出现后手指早已松开，不自动开始，等下一次按住
        if (hold) Toast.makeText(ctx, "可以了，按住麦克风说话", Toast.LENGTH_SHORT).show()
        else onFinal?.let { start(it) }
    }

    internal fun deliver(text: String) {
        onFinal?.invoke(text)
    }

    internal fun destroy() {
        cancel()
        main.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}

@Composable
fun rememberVoice(hold: Boolean = false): VoiceState {
    val ctx = LocalContext.current
    val state = remember { VoiceState(ctx.applicationContext, hold) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) state.resumeAfterPermission()
        else Toast.makeText(ctx, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
    }
    state.askPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(state) { onDispose { state.destroy() } }
    return state
}

/** 把识别结果接到已有文字后面 */
fun appendVoice(old: String, add: String): String =
    if (old.isBlank()) add else old.trimEnd() + (if (old.trimEnd().last().isLetterOrDigit()) "，" else "") + add

/** 编辑弹窗里用的小麦克风（不需要整条输入栏的地方） */
@Composable
fun VoiceButton(onText: (String) -> Unit, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val v = rememberVoice()
    val c = T.c
    androidx.compose.material3.IconButton(
        onClick = { if (v.listening) v.stop() else v.start(onText) },
        modifier = modifier,
    ) {
        androidx.compose.material3.Icon(
            if (v.listening) androidx.compose.material.icons.Icons.Outlined.Stop else androidx.compose.material.icons.Icons.Outlined.Mic,
            if (v.listening) "说完了" else "语音输入",
            tint = if (v.listening) c.accent else c.ink3,
        )
    }
}
