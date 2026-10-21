package cn.kenchy.me.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import cn.kenchy.me.MainActivity
import cn.kenchy.me.MeApp
import cn.kenchy.me.R
import cn.kenchy.me.core.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

@Serializable
data class Alert(
    val seq: Long,
    val at: Long = 0,
    val kind: String,
    val machine: String = "",
    val sessionId: String = "",
    val name: String = "",
    val text: String = "",
    val approvalKey: String? = null,
)

/**
 * 常驻连接（ADR 0007）：国内安卓没有可用的系统推送，小米推送又不对个人开放，
 * 所以由一个前台服务和服务器保持一条 WebSocket，收到 Codex 的完成 / 出错 / 等你批准就弹通知。
 * 断线后按 2s、4s…最长 60s 重连，重连时带上最后收到的序号，服务器补发期间漏掉的。
 */
class AlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var retry: Job? = null
    private var backoff = 2_000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AlertNotify.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        ServiceCompat.startForeground(this, AlertNotify.ONGOING_ID, AlertNotify.ongoing(this, false), type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AlertPrefs.enabled(this) || MeApp.instance.prefs.token == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ws == null) connect()
        return START_STICKY
    }

    private fun connect() {
        retry?.cancel()
        val app = MeApp.instance
        val token = app.prefs.token ?: return stopSelf()
        val req = Request.Builder()
            .url(app.api.base + "/api/alerts/live?after=" + AlertPrefs.lastSeq(this))
            .header("Authorization", "Bearer $token")
            .build()
        // 单独的客户端：心跳 4 分钟一次就够保住连接，尽量少唤醒手机
        val client = app.api.http.newBuilder().pingInterval(4, TimeUnit.MINUTES).readTimeout(0, TimeUnit.MILLISECONDS).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                backoff = 2_000L
                AlertNotify.updateOngoing(this@AlertService, true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (msg["type"]?.jsonPrimitive?.content) {
                    // 第一次连上：从服务器当前序号开始，不把历史当新提醒
                    "hello" -> if (AlertPrefs.lastSeq(this@AlertService) == 0L) {
                        msg["last"]?.jsonPrimitive?.content?.toLongOrNull()?.let { AlertPrefs.setLastSeq(this@AlertService, it) }
                    }
                    "alert" -> {
                        val a = runCatching { json.decodeFromJsonElement(Alert.serializer(), msg["alert"]!!) }.getOrNull() ?: return
                        if (a.seq <= AlertPrefs.lastSeq(this@AlertService)) return
                        AlertPrefs.setLastSeq(this@AlertService, a.seq)
                        AlertNotify.show(this@AlertService, a)
                    }
                    // 电脑上改了待办：后台拉最新的重排到点提醒，打开着的待办页也跟着刷新
                    "todos" -> scope.launch {
                        runCatching { cn.kenchy.me.MeApp.instance.api.get("/api/todos", cn.kenchy.me.todo.TodoList.serializer()) }
                            .onSuccess { cn.kenchy.me.todo.TodoSync.update(this@AlertService, it.items) }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, code)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket, response?.code ?: 0)
        })
    }

    private fun dropped(socket: WebSocket, code: Int) {
        if (ws !== socket) return
        ws = null
        AlertNotify.updateOngoing(this, false)
        if (code == 401) return stopSelf() // 设备被吊销
        retry = scope.launch {
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
            connect()
        }
    }

    override fun onDestroy() {
        retry?.cancel()
        ws?.close(1000, null)
        ws = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** 打开 App、开机、定时兜底都调这个；已在运行时只是确认一下连接 */
        fun start(ctx: Context) {
            if (!AlertPrefs.enabled(ctx) || MeApp.instance.prefs.token == null) return
            if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            runCatching { ctx.startForegroundService(Intent(ctx, AlertService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AlertService::class.java))
        }
    }
}

object AlertPrefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = sp(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("enabled", v).apply()
    fun lastSeq(ctx: Context) = sp(ctx).getLong("seq", 0L)
    fun setLastSeq(ctx: Context, v: Long) = sp(ctx).edit().putLong("seq", v).apply()

    /** 正在看的会话：这时不弹通知（页面本身会刷新） */
    @Volatile var watching: String? = null
}

/** 站内提醒：App 在前台时不弹系统通知，交给界面里的小卡片 */
object InApp {
    val alerts = kotlinx.coroutines.flow.MutableSharedFlow<Alert>(extraBufferCapacity = 8)
}

object AlertNotify {
    const val CH_CODEX = "codex_task" // 旧的 "codex" 是默认级别、不弹横幅，渠道级别建好后改不了，只能换新 id
    const val CH_APPROVAL = "codex_approval"
    const val CH_ONGOING = "connection"
    const val ONGOING_ID = 1

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("codex")
        nm.createNotificationChannel(NotificationChannel(CH_CODEX, "Codex 任务", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "任务完成、中断或出错（顶部横幅弹出）"
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_APPROVAL, "Codex 等你批准", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Codex 要执行操作，等你批准或拒绝"
            enableVibration(true)
        })
        // 常驻提示：最低级别，折叠在通知栏底部、状态栏不出图标
        nm.createNotificationChannel(NotificationChannel(CH_ONGOING, "后台连接", NotificationManager.IMPORTANCE_MIN).apply {
            description = "保持和服务器的连接，用来实时收提醒。可以在这里把它隐藏"
            setShowBadge(false)
        })
    }

    private fun openApp(ctx: Context, target: String, req: Int): PendingIntent = PendingIntent.getActivity(
        ctx, req,
        Intent(ctx, MainActivity::class.java).putExtra("open", target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun ongoing(ctx: Context, connected: Boolean): Notification =
        NotificationCompat.Builder(ctx, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (connected) "提醒已开启" else "正在连接…")
            .setContentText("Codex 完成或等你批准时会通知你")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setContentIntent(openApp(ctx, "settings/alerts", 1))
            .build()

    fun updateOngoing(ctx: Context, connected: Boolean) {
        runCatching { NotificationManagerCompat.from(ctx).notify(ONGOING_ID, ongoing(ctx, connected)) }
    }

    private fun idOf(a: Alert) = ("${a.machine}/${a.sessionId}").hashCode().let { if (it == ONGOING_ID) 2 else it }

    /** 打开过的会话：收起它的通知（桌面图标上的数字跟着减少） */
    fun cancelFor(ctx: Context, machine: String, sessionId: String) {
        val id = ("$machine/$sessionId").hashCode().let { if (it == ONGOING_ID) 2 else it }
        runCatching { NotificationManagerCompat.from(ctx).cancel(id) }
    }

    fun show(ctx: Context, a: Alert) {
        ensureChannels(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        val id = idOf(a)
        val foreground = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        // 批准请求被处理了（不管在哪处理的）：收起那条通知
        if (a.kind == "resolved") {
            nm.cancel(id)
            InApp.alerts.tryEmit(a)
            return
        }
        // 人在 App 里：不进通知栏，由站内卡片 + 角标 + 未读点提示
        if (foreground) {
            InApp.alerts.tryEmit(a)
            return
        }
        val title = when (a.kind) {
            "waiting" -> "等你批准 · ${a.name}"
            "failed" -> "出错了 · ${a.name}"
            "interrupted" -> "已中断 · ${a.name}"
            else -> "已完成 · ${a.name}"
        }
        val body = a.text.ifBlank { if (a.kind == "waiting") "Codex 在等你处理" else "点开查看" }
        val b = NotificationCompat.Builder(ctx, if (a.kind == "waiting") CH_APPROVAL else CH_CODEX)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(a.machine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setWhen(if (a.at > 0) a.at else System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setNumber(1) // 小米桌面角标按「还没看的通知条数」计：一个会话一条，打开会话后收起
            .setGroup("codex")
            .setCategory(if (a.kind == "waiting") NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openApp(ctx, "session/${a.machine}/${a.sessionId}", id))
        if (a.kind == "waiting" && a.approvalKey != null) {
            b.addAction(0, "拒绝", action(ctx, a, false, id))
            b.addAction(0, "批准", action(ctx, a, true, id))
        }
        runCatching { nm.notify(id, b.build()) }
    }

    private fun action(ctx: Context, a: Alert, accept: Boolean, id: Int): PendingIntent {
        val i = Intent(ctx, ApproveReceiver::class.java)
            .setAction("cn.kenchy.me.APPROVE.${a.machine}.${a.approvalKey}.$accept")
            .putExtra("machine", a.machine).putExtra("key", a.approvalKey).putExtra("accept", accept)
            .putExtra("id", id).putExtra("name", a.name)
        return PendingIntent.getBroadcast(ctx, (id * 31) + if (accept) 1 else 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun result(ctx: Context, id: Int, name: String, text: String) {
        val n = NotificationCompat.Builder(ctx, CH_CODEX)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(name)
            .setContentText(text)
            .setTimeoutAfter(6_000)
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}

/** 通知上的「批准 / 拒绝」：不打开 App，直接调接口 */
class ApproveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val machine = intent.getStringExtra("machine") ?: return
        val key = intent.getStringExtra("key") ?: return
        val accept = intent.getBooleanExtra("accept", false)
        val id = intent.getIntExtra("id", 0)
        val name = intent.getStringExtra("name").orEmpty()
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val body = buildJsonObject { put("decision", if (accept) "accept" else "decline") }.toString()
            val r = runCatching { MeApp.instance.api.raw("POST", "/api/codex/approvals/$machine/$key", body) }
            AlertNotify.result(
                ctx, id, name,
                r.fold({ if (accept) "已批准，继续运行" else "已拒绝" }, { it.message ?: "操作失败" }),
            )
            done.finish()
        }
    }
}
