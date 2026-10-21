package cn.kenchy.me.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** 已选好、准备发给 Codex 的图片：缩略图用来显示，dataUrl 用来发送 */
class PickedImage(val uri: Uri?, val thumb: Bitmap, val dataUrl: String)

/** 草稿里的图片：按会话存成文件（data URL 文本），切走、App 被回收后回来还在，发送成功后删掉 */
object DraftImages {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(1) // 串行写，后一次保存总是覆盖前一次
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + io)

    private fun dir(key: String): java.io.File {
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return java.io.File(cn.kenchy.me.MeApp.instance.filesDir, "draft-images/$name")
    }

    fun save(key: String, dataUrls: List<String>) {
        scope.launch {
            val d = dir(key)
            d.listFiles()?.forEach { it.delete() }
            if (dataUrls.isEmpty()) { d.delete(); return@launch }
            d.mkdirs()
            dataUrls.forEachIndexed { i, url -> java.io.File(d, "$i.txt").writeText(url) }
        }
    }

    suspend fun load(key: String): List<PickedImage> = withContext(io) {
        val files = dir(key).listFiles()?.filter { it.name.endsWith(".txt") }?.sortedBy { it.name.substringBefore('.').toIntOrNull() ?: 0 }
        files.orEmpty().mapNotNull { f ->
            runCatching {
                val url = f.readText()
                val bytes = Base64.decode(url.substringAfter("base64,"), Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 160) sample *= 2
                val thumb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })!!
                PickedImage(null, thumb, url)
            }.getOrNull()
        }
    }
}

private const val MAX_SIDE = 1600

/** 读图并压到最长边 1600、JPEG 85，转成 data URL（一张一般 200~500KB） */
suspend fun loadImage(ctx: Context, uri: Uri): PickedImage = withContext(Dispatchers.IO) {
    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
    val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
        d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
    val data = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    val ts = 160f / maxOf(bmp.width, bmp.height)
    val thumb = Bitmap.createScaledBitmap(bmp, (bmp.width * ts).toInt().coerceAtLeast(1), (bmp.height * ts).toInt().coerceAtLeast(1), true)
    PickedImage(uri, thumb, data)
}

@Suppress("unused")
private fun decodeFallback(ctx: Context, uri: Uri): Bitmap? =
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
