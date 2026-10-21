package cn.kenchy.me.core

import cn.kenchy.me.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

class ApiError(val status: Int, message: String) : IOException(message)

class Api(private val prefs: Prefs, val base: String = BuildConfig.SERVER) {
    /**
     * 只用 HTTP/1.1：HTTP/2 下所有请求共用一条连接，手机切网 / 锁屏后这条连接半死不活，
     * 之后每个请求都会报「stream was reset: PROTOCOL_ERROR」直到 App 重启。HTTP/1.1 一请求一连接，坏一条不影响别的。
     */
    val http: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 12 })
        .build()

    /** 图片单独一条通道、最多同时下 2 张：大图不会占满连接，把会话内容这类小请求挤在后面排队 */
    val images: OkHttpClient by lazy {
        http.newBuilder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 2 })
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun request(path: String): Request.Builder {
        val b = Request.Builder().url(base + path)
        prefs.token?.let { b.header("Authorization", "Bearer $it") }
        return b
    }

    suspend fun raw(method: String, path: String, body: String? = null): String = withContext(Dispatchers.IO) {
        val req = request(path).method(method, body?.toRequestBody(jsonType)).build()
        val res = try {
            http.newCall(req).await()
        } catch (e: IOException) {
            // 连接坏了：清掉连接池里的旧连接，GET 自动重试一次（POST 不重试，避免重复发消息）
            if (e is ApiError) throw e
            http.connectionPool.evictAll()
            if (method != "GET") throw e
            http.newCall(req).await()
        }
        res.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
                    ?.get("error")?.jsonPrimitive?.content ?: "请求失败（${it.code}）"
                if (it.code == 401 && path != "/api/pair") onUnauthorized?.invoke()
                throw ApiError(it.code, msg)
            }
            text
        }
    }

    suspend fun <T> get(path: String, s: KSerializer<T>): T = json.decodeFromString(s, raw("GET", path))
    suspend fun <T> send(method: String, path: String, body: String, s: KSerializer<T>): T =
        json.decodeFromString(s, raw(method, path, body))

    /** 设备凭证失效时（被吊销）回到配对页 */
    var onUnauthorized: (() -> Unit)? = null

    fun live(listener: WebSocketListener): WebSocket =
        http.newWebSocket(request("/api/codex/live").build(), listener)

    /** 语音识别：实时连接（边说边出字） */
    private val asrClient by lazy { http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(10, TimeUnit.SECONDS).build() }
    fun asrLive(listener: WebSocketListener): WebSocket = asrClient.newWebSocket(request("/api/asr/live").build(), listener)

    /** 语音识别：整段补传（实时连接中途断了时用），pcm = 16k 单声道 int16 */
    suspend fun asr(pcm: ByteArray): String = withContext(Dispatchers.IO) {
        val req = request("/api/asr").post(pcm.toRequestBody("application/octet-stream".toMediaType())).build()
        http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build().newCall(req).await().use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw ApiError(it.code, "识别失败（${it.code}）")
            (json.parseToJsonElement(text) as JsonObject)["text"]?.jsonPrimitive?.content.orEmpty()
        }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(IOException(friendly(e), e))
        }

        override fun onResponse(call: Call, response: Response) = cont.resume(response)
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

private fun friendly(e: IOException): String = when (e) {
    is java.net.UnknownHostException -> "网络不可用"
    is java.net.SocketTimeoutException -> "连接超时"
    else -> friendlyError(e.message ?: "网络错误")
}

/** 把底层网络库的英文报错换成人话 */
fun friendlyError(msg: String): String = when {
    msg.contains("stream was reset", true) || msg.contains("PROTOCOL_ERROR") -> "连接中断了，点重试"
    msg.contains("connection reset", true) || msg.contains("unexpected end of stream", true) -> "连接中断了，点重试"
    msg.contains("failed to connect", true) -> "连不上服务器"
    else -> msg
}
