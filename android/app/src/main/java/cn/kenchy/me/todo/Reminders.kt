package cn.kenchy.me.todo

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.kenchy.me.MainActivity
import cn.kenchy.me.R
import cn.kenchy.me.core.json
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 到点提醒：全部在手机本地完成，不依赖服务器推送。
 * 每次拿到待办列表就把「未来的提醒」整体重排一遍；开机后从本地缓存再排一次。
 * 全天事项在当天 09:00 提醒。
 */
object Reminders {
    const val CHANNEL = "todo"
    private const val PREFS = "reminders"
    private val zone = ZoneId.of("Asia/Shanghai")
    private val allDay = LocalTime.of(9, 0)

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "待办提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "待办到点时提醒"
                    enableVibration(true)
                },
            )
        }
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun whenOf(due: String): LocalDateTime =
        if (due.length > 10) LocalDateTime.parse(due) else java.time.LocalDate.parse(due).atTime(allDay)

    /** 按最新列表重排：取消不再需要的，排上新的 */
    fun sync(ctx: Context, items: List<Todo>) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().putString("items", json.encodeToString(ListSerializer(Todo.serializer()), items)).apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now(zone)
        val wanted = items.filter { it.kind == "todo" && !it.done && it.due != null }
            .map { it to whenOf(it.due!!) }
            .filter { it.second.isAfter(now) }
        val old = sp.getStringSet("ids", emptySet())!!
        for (id in old - wanted.map { it.first.id }.toSet()) am.cancel(pending(ctx, id, "", null))
        for ((t, at) in wanted) {
            val ms = at.atZone(zone).toInstant().toEpochMilli()
            val pi = pending(ctx, t.id, t.text, t.due)
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
        sp.edit().putStringSet("ids", wanted.map { it.first.id }.toSet()).apply()
    }

    /** 开机后用本地缓存重排 */
    fun restore(ctx: Context) {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items", null) ?: return
        runCatching { json.decodeFromString(ListSerializer(Todo.serializer()), raw) }.onSuccess { sync(ctx, it) }
    }

    private fun pending(ctx: Context, id: String, text: String, due: String?): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .setAction("cn.kenchy.me.REMIND.$id")
            .putExtra("id", id).putExtra("text", text).putExtra("due", due)
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(ctx: Context, id: String, text: String, due: String?) {
        // 人在 App 里：用站内卡片，不进通知栏
        val foreground = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        if (foreground) {
            val sub = due?.let { if (it.length > 10) "到点了 · ${it.substring(11)}" else "今天要做" } ?: ""
            cn.kenchy.me.alerts.InApp.alerts.tryEmit(cn.kenchy.me.alerts.Alert(seq = 0, kind = "todo", sessionId = id, name = text, text = sub))
            return
        }
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, id.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra("open", "todo").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(text)
            .setContentText(due?.let { if (it.length > 10) "到点了 · ${it.substring(11)}" else "今天要做" } ?: "提醒")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id.hashCode(), n) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        Reminders.show(ctx, id, intent.getStringExtra("text").orEmpty(), intent.getStringExtra("due"))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.restore(ctx)
        cn.kenchy.me.alerts.Watchdog.kick(ctx)
    }
}
