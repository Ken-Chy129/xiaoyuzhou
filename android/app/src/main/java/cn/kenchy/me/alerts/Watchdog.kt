package cn.kenchy.me.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import cn.kenchy.me.todo.Reminders

/**
 * 兜底：每 15 分钟把常驻连接拉起来一次（已在运行时什么也不做）。
 * 用精确闹钟是因为安卓 12 起只有它（和开机广播）允许从后台启动前台服务。
 */
object Watchdog {
    private const val EVERY = 15 * 60_000L

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = SystemClock.elapsedRealtime() + EVERY
        val pi = pending(ctx)
        if (Reminders.canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    }

    fun cancel(ctx: Context) = ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 7001, Intent(ctx, WatchdogReceiver::class.java).setAction("cn.kenchy.me.WATCHDOG"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 打开 App、开机、闹钟到点都走这里 */
    fun kick(ctx: Context) {
        if (!AlertPrefs.enabled(ctx)) return
        AlertService.start(ctx)
        schedule(ctx)
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Watchdog.kick(ctx)
}
