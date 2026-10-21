package cn.kenchy.me.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.kenchy.me.BuildConfig
import cn.kenchy.me.MeApp
import cn.kenchy.me.core.VersionInfo
import cn.kenchy.me.core.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** 自更新：服务器上的 /api/version 有新版本时，下载 APK 交给系统安装器（只给自己装，不走应用商店） */
class UpdateVm : ViewModel() {
    private val app by lazy { MeApp.instance }
    val available = MutableStateFlow<VersionInfo?>(null)
    val progress = MutableStateFlow<String?>(null)
    /** 版本行右侧的状态：null = 已是最新；"检查中…" / "检查失败" */
    val checking = MutableStateFlow<String?>(null)
    private var lastCheck = 0L

    fun check(force: Boolean = false): kotlinx.coroutines.Job? {
        if (!force && System.currentTimeMillis() - lastCheck < 10 * 60_000) return null
        lastCheck = System.currentTimeMillis()
        return viewModelScope.launch {
            if (force) checking.value = "检查中…"
            runCatching { app.api.get("/api/version", VersionInfo.serializer()) }
                .onSuccess {
                    available.value = it.takeIf { v -> v.versionCode > BuildConfig.VERSION_CODE && v.url.isNotBlank() }
                    checking.value = null
                }
                .onFailure { if (force) checking.value = "检查失败，点击重试" }
        }
    }

    fun install() {
        val v = available.value ?: return
        if (progress.value != null) return
        viewModelScope.launch {
            progress.value = "下载中…"
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                    val file = File(dir, "me-${v.versionCode}.apk")
                    val url = if (v.url.startsWith("http")) v.url else app.api.base + v.url
                    // 之前已经完整下好（校验通过）就直接装
                    if (v.sha256.isNotBlank() && file.exists() && sha256(file).equals(v.sha256, true)) return@withContext file
                    val part = File(dir, "me-${v.versionCode}.apk.part")
                    // 清掉旧版本留下的包
                    dir.listFiles()?.filter { !it.name.startsWith("me-${v.versionCode}.") }?.forEach { it.delete() }
                    // 下载安装包单独开连接、只走 HTTP/1.1：复用 App 里那条 HTTP/2 长连接时，
                    // 网络切换后容易被对端重置（stream was reset: PROTOCOL_ERROR）
                    val client = app.api.http.newBuilder()
                        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                        .connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    var last: Throwable? = null
                    for (attempt in 1..5) {
                        val ok = runCatching {
                            val have = if (part.exists()) part.length() else 0L
                            val rq = Request.Builder().url(url).header("Accept-Encoding", "identity")
                            if (have > 0) rq.header("Range", "bytes=$have-")
                            val res = client.newCall(rq.build()).await()
                            res.use { r ->
                                if (r.code == 416) { part.delete(); error("重新下载") }
                                if (!r.isSuccessful) error("下载失败（${r.code}）")
                                val body = r.body ?: error("下载失败")
                                // 206 = 服务器接受了续传；200 = 从头来
                                val resumed = r.code == 206
                                val start = if (resumed) have else 0L
                                val total = body.contentLength().let { if (it > 0) it + start else -1L }
                                java.io.FileOutputStream(part, resumed).use { out ->
                                    val input = body.byteStream()
                                    val buf = ByteArray(64 * 1024)
                                    var done = start
                                    var shown = -1
                                    var lastAt = 0L
                                    var lastBytes = done
                                    var speed = ""
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        done += n
                                        val now = System.currentTimeMillis()
                                        if (now - lastAt >= 1000) {
                                            if (lastAt > 0) speed = " · " + rate((done - lastBytes) * 1000 / (now - lastAt))
                                            lastAt = now
                                            lastBytes = done
                                        }
                                        if (total > 0) {
                                            val p = (done * 100 / total).toInt()
                                            if (p != shown) { shown = p; progress.value = "下载中 $p%$speed" }
                                        }
                                    }
                                    if (total > 0 && done != total) error("下载不完整")
                                }
                            }
                        }
                        if (ok.isSuccess) { last = null; break }
                        last = ok.exceptionOrNull()
                        if (attempt < 5) {
                            progress.value = "网络中断，接着下（$attempt/4）…"
                            kotlinx.coroutines.delay(1000L * attempt)
                        }
                    }
                    last?.let { throw it }
                    if (v.sha256.isNotBlank() && !sha256(part).equals(v.sha256, true)) {
                        part.delete()
                        error("安装包校验失败，再点一次重新下载")
                    }
                    file.delete()
                    part.renameTo(file)
                    file
                }
            }
            result.onSuccess { file ->
                progress.value = null
                val pm = app.packageManager
                if (!pm.canRequestPackageInstalls()) {
                    app.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    progress.value = "允许安装后再点一次更新"
                    return@launch
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                app.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { progress.value = it.message ?: "更新失败" }
        }
    }

    fun clearMessage() {
        if (progress.value != "下载中…") progress.value = null
    }
}

private fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { s ->
        val buf = ByteArray(64 * 1024)
        while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun rate(bps: Long) = when {
    bps >= 1024 * 1024 -> "%.1f MB/s".format(bps / 1048576.0)
    else -> "${bps / 1024} KB/s"
}
