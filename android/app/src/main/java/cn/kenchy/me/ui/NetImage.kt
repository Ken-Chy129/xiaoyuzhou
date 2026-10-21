package cn.kenchy.me.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.MeApp
import cn.kenchy.me.core.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 正文图片：内存缓存 + 按屏宽降采样，够用就不引入图片库。
 * 先直连图片地址（快）；直连不通时改由自己的服务器中转（/api/news/img）。
 * 下载在 App 级别进行：滚出屏幕不会把下到一半的图取消掉，同一张图同时只下一次。
 */
object Images {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = HashMap<String, Deferred<Bitmap?>>()
    private val direct by lazy {
        MeApp.instance.api.images.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    }
    private val relay by lazy { MeApp.instance.api.images }

    fun cached(url: String): Bitmap? = cache.get(url)

    suspend fun load(url: String, maxWidth: Int = 1280): Bitmap? {
        cache.get(url)?.let { return it }
        val job = synchronized(inflight) {
            inflight[url]?.takeIf { it.isActive } ?: scope.async {
                try {
                    fetchAndDecode(url, maxWidth)?.also { cache.put(url, it) }
                } finally {
                    synchronized(inflight) { inflight.remove(url) }
                }
            }.also { inflight[url] = it }
        }
        return job.await()
    }

    private suspend fun fetchAndDecode(url: String, maxWidth: Int): Bitmap? {
        // 直连一次 → 服务器中转（失败再试一次）
        val attempts = listOf<suspend () -> ByteArray?>(
            { get(direct, Request.Builder().url(url).header("User-Agent", "me-android").build()) },
            { relayFetch(url) },
            { kotlinx.coroutines.delay(1000); relayFetch(url) },
        )
        for (a in attempts) {
            val bytes = runCatching { a() }.getOrNull() ?: continue
            decode(bytes, maxWidth)?.let { return it }
        }
        return null
    }

    private suspend fun relayFetch(url: String): ByteArray? {
        val app = MeApp.instance
        val u = app.api.base.trimEnd('/') + "/api/news/img?u=" + java.net.URLEncoder.encode(url, "UTF-8")
        val req = Request.Builder().url(u).apply {
            app.prefs.token?.let { header("Authorization", "Bearer $it") }
        }.build()
        return get(relay, req)
    }

    private suspend fun get(client: okhttp3.OkHttpClient, req: Request): ByteArray? =
        client.newCall(req).await().use { res ->
            if (res.isSuccessful && res.header("Content-Type").orEmpty().startsWith("image/")) res.body?.bytes() else null
        }

    private suspend fun decode(bytes: ByteArray, maxWidth: Int): Bitmap? = withContext(Dispatchers.Default) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return@withContext null
        var sample = 1
        // 宽按屏幕，长图的高度也限制一下，免得一张图吃掉几十 MB 内存
        while (bounds.outWidth / (sample * 2) >= maxWidth || bounds.outHeight / sample > 4096) sample *= 2
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
    }
}

@Composable
fun NetImage(url: String, ratio: Float?, modifier: Modifier = Modifier) {
    val c = T.c
    var bmp by remember(url) { mutableStateOf(Images.cached(url)) }
    var failed by remember(url) { mutableStateOf(false) }
    var attempt by remember(url) { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(url, attempt) {
        if (bmp == null) {
            failed = false
            val b = Images.load(url)
            if (b == null) failed = true else bmp = b
        }
    }
    val shown by animateFloatAsState(if (bmp != null) 1f else 0f, tween(220), label = "img")
    val r = bmp?.let { it.width.toFloat() / it.height } ?: ratio ?: (16f / 9f)
    Box(
        modifier.fillMaxWidth().aspectRatio(r.coerceIn(0.5f, 3f)).background(c.line2)
            .then(if (failed) Modifier.clickable { attempt++ } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(shown), contentScale = ContentScale.Fit)
        }
        when {
            failed -> Text("图片加载失败，点击重试", color = c.ink3, fontSize = 12.sp)
            bmp == null -> androidx.compose.material3.CircularProgressIndicator(
                Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp,
            )
        }
    }
}
