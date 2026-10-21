package cn.kenchy.me.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.kenchy.me.MeApp
import cn.kenchy.me.core.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 一张可以查看的图：本地已有的位图、data URL，或需要设备凭证的服务器地址 */
sealed interface ViewImage {
    data class Local(val bitmap: Bitmap) : ViewImage
    data class DataUrl(val url: String) : ViewImage
    data class Remote(val path: String) : ViewImage // 形如 /api/codex/…，带设备凭证取
}

/** 带凭证取图（Codex 会话里的图片），和正文图片共用一份内存缓存 */
object AuthImages {
    private val cache = object : android.util.LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** 下载放在 App 级别：列表刷新 / 滚出屏幕不会把下到一半的图取消掉；同一张图同时只下一次 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = HashMap<String, Deferred<Bitmap?>>()
    private val imgHttp by lazy {
        MeApp.instance.api.images
    }

    fun cached(key: String): Bitmap? = cache.get(key)

    suspend fun load(img: ViewImage, maxSide: Int = 2048): Bitmap? = when (img) {
        is ViewImage.Local -> img.bitmap
        is ViewImage.DataUrl -> cache.get(img.url.hashCode().toString()) ?: withContext(Dispatchers.Default) {
            val b64 = img.url.substringAfter("base64,", "")
            runCatching { decode(Base64.decode(b64, Base64.DEFAULT), maxSide) }.getOrNull()
        }?.also { cache.put(img.url.hashCode().toString(), it) }
        is ViewImage.Remote -> cache.get(img.path) ?: remote(img.path, maxSide).await()
    }

    private fun remote(path: String, maxSide: Int): Deferred<Bitmap?> = synchronized(inflight) {
        inflight[path]?.takeIf { it.isActive } ?: scope.async {
            try {
                var result: Bitmap? = null
                // 网络抖动时自动再试两次
                for (attempt in 0 until 3) {
                    val bytes = runCatching { fetch(path) }.getOrNull()
                    result = bytes?.let { decode(it, maxSide) }
                    if (result != null) break
                    kotlinx.coroutines.delay(800L * (attempt + 1))
                }
                result?.also { cache.put(path, it) }
            } finally {
                synchronized(inflight) { inflight.remove(path) }
            }
        }.also { inflight[path] = it }
    }

    private suspend fun fetch(path: String): ByteArray? {
        val app = MeApp.instance
        val req = Request.Builder().url(app.api.base + path).apply {
            app.prefs.token?.let { header("Authorization", "Bearer $it") }
        }.build()
        return imgHttp.newCall(req).await().use { if (it.isSuccessful) it.body?.bytes() else null }
    }

    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** 缩略图：点了打开大图 */
@Composable
fun Thumb(img: ViewImage, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = T.c
    val key = when (img) { is ViewImage.Remote -> img.path; is ViewImage.DataUrl -> img.url.hashCode().toString(); else -> null }
    var bmp by remember(img) { mutableStateOf((img as? ViewImage.Local)?.bitmap ?: key?.let { AuthImages.cached(it) }) }
    var failed by remember(img) { mutableStateOf(false) }
    var attempt by remember(img) { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(img, attempt) {
        if (bmp == null) { failed = false; AuthImages.load(img).let { if (it == null) failed = true else bmp = it } }
    }
    Box(
        modifier.size(size).clip(RoundedCornerShape(10.dp)).background(c.line2)
            .clickable { if (failed) attempt++ else onClick() },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it.asImageBitmap(), "图片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: if (failed) Text("加载失败\n点击重试", color = c.ink3, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            else CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
    }
}

/** 全屏看图：左右滑切换，双指缩放，双击放大 / 还原，单击或下滑关闭 */
@Composable
fun ImageViewer(images: List<ViewImage>, start: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                ZoomableImage(images[page], onClose)
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, "关闭", tint = Color.White) }
            }
            if (images.size > 1) {
                Row(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(images.size) { i ->
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == pager.currentPage) 0.9f else 0.3f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(img: ViewImage, onClose: () -> Unit) {
    var bmp by remember(img) { mutableStateOf((img as? ViewImage.Local)?.bitmap ?: (img as? ViewImage.Remote)?.let { AuthImages.cached(it.path) }) }
    var failed by remember(img) { mutableStateOf(false) }
    LaunchedEffect(img) { if (bmp == null) AuthImages.load(img).let { if (it == null) failed = true else bmp = it } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxSize()
            .pointerInput(img) {
                detectTapGestures(
                    onTap = { onClose() },
                    onDoubleTap = { p ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                        else { scale = 2.5f; offset = (Offset(size.width / 2f, size.height / 2f) - p) * 1.5f }
                    },
                )
            }
            .pointerInput(img) {
                detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                    val s = (scale * zoom).coerceIn(1f, 6f)
                    if (s > 1f || scale > 1f) {
                        scale = s
                        offset = if (s == 1f) Offset.Zero else offset + pan
                    } else {
                        // 没放大时竖着拖：下拉关闭
                        scope.launch { drag.snapTo(drag.value + pan.y) }
                    }
                }
            }
            .pointerInput(img) {
                // 手指抬起时决定关闭还是弹回
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.changes.all { !it.pressed } && drag.value != 0f) {
                            if (kotlin.math.abs(drag.value) > size.height / 6f) onClose()
                            else scope.launch { drag.animateTo(0f) }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        when {
            b != null -> Image(
                b.asImageBitmap(), "图片",
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y + drag.value
                    alpha = 1f - (kotlin.math.abs(drag.value) / (size.height * 0.8f)).coerceIn(0f, 0.6f)
                },
                contentScale = ContentScale.Fit,
            )
            failed -> Text("图片加载失败", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            else -> CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
        }
    }
}
