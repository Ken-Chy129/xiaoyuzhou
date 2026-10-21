package cn.kenchy.me.todo

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.mutableStateOf
import cn.kenchy.me.alerts.AlertPrefs
import cn.kenchy.me.alerts.AlertService
import cn.kenchy.me.alerts.Watchdog
import cn.kenchy.me.ui.Label
import cn.kenchy.me.ui.T
import cn.kenchy.me.ui.Card
import cn.kenchy.me.ui.Gap

/** 提醒要能准点弹出，需要三样：通知权限、精确闹钟、小米的自启动 */
object ReminderPerms {
    fun notifications(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun exact(ctx: Context): Boolean = Reminders.canExact(ctx)

    fun autostartDone(ctx: Context): Boolean = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE).getBoolean("autostart", false)
    fun markAutostart(ctx: Context) = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit().putBoolean("autostart", true).apply()

    fun allOk(ctx: Context) = notifications(ctx) && exact(ctx) && autostartDone(ctx)

    /** 小米「自启动管理」，打不开就退回应用详情页 */
    fun openAutostart(ctx: Context) {
        val miui = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { ctx.startActivity(miui) }.isSuccess
        if (!ok) openAppDetails(ctx)
        markAutostart(ctx)
    }

    fun openAppDetails(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openExact(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { openAppDetails(ctx) }
        }
    }

    fun openNotificationSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** 直接打开某个通知类别的设置页（小米的「悬浮通知」开关在这里） */
    fun openChannel(ctx: Context, channel: String) {
        cn.kenchy.me.alerts.AlertNotify.ensureChannels(ctx)
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openNotificationSettings(ctx) }
    }

    /** 省电策略是否「无限制」（系统层面的电池优化白名单，小米的「无限制」会同步到这里） */
    fun unrestricted(ctx: Context): Boolean =
        ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    @android.annotation.SuppressLint("BatteryLife")
    fun openBattery(ctx: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(direct) }.isFailure) openAppDetails(ctx)
    }
}

/**
 * 提醒设置清单。compact = true 时只在有没开的项时显示（待办页顶部用）。
 */
@Composable
fun ReminderSetup(compact: Boolean, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val c = T.c
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onChanged()
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        if (!granted) ReminderPerms.openNotificationSettings(ctx)
    }
    @Suppress("UNUSED_EXPRESSION") tick
    val notif = ReminderPerms.notifications(ctx)
    val exact = ReminderPerms.exact(ctx)
    val auto = ReminderPerms.autostartDone(ctx)
    if (compact && notif && exact && auto) return

    Column(Modifier.fillMaxWidth()) {
        if (compact) {
            Text("开启后，待办到点会弹提醒", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("小米需要这三项都打开，App 不在前台时才能准时提醒。", color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
        }
        Item("通知", if (compact) null else "到点在通知栏弹出提醒", notif, "去开启") {
            if (Build.VERSION.SDK_INT >= 33 && !notif) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else ReminderPerms.openNotificationSettings(ctx)
        }
        Item("准点闹钟", if (compact) null else "不开的话系统会把提醒攒着晚一点再发", exact, "去开启") { ReminderPerms.openExact(ctx) }
        Item("自启动（小米）", if (compact) null else "手机重启或 App 被清掉后，能把提醒重新排上", auto, "去设置") { ReminderPerms.openAutostart(ctx); tick++ }
        if (!compact) {
            // 悬浮通知是小米在每个通知类别里的开关，系统接口查不到状态，只能带过去让用户打开
            Item("悬浮通知（小米）", "任务完成时在屏幕顶部弹出横幅。打开「Codex 任务」「Codex 等你批准」里的「悬浮通知」", null, "去设置") {
                ReminderPerms.openChannel(ctx, cn.kenchy.me.alerts.AlertNotify.CH_CODEX)
            }
            Item("省电策略：无限制", "不设的话，锁屏一会儿后台连接就会被冻结，Codex 的提醒会晚到或收不到", ReminderPerms.unrestricted(ctx), "去设置") {
                ReminderPerms.openBattery(ctx)
            }
        }
    }
}

/** 提醒是否都设好了（给「我」页那一行用）：待办三项 + Codex 提醒开着时还要省电无限制 */
@Composable
fun rememberReminderOk(): Boolean {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    @Suppress("UNUSED_EXPRESSION") tick
    return ReminderPerms.allOk(ctx) && (!AlertPrefs.enabled(ctx) || ReminderPerms.unrestricted(ctx))
}

@Composable
fun ReminderSettingsScreen(back: () -> Unit) {
    val c = T.c
    val ctx = LocalContext.current
    var codexOn by remember { mutableStateOf(AlertPrefs.enabled(ctx)) }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.ink) }
            Text("通知与提醒", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.padding(18.dp, 8.dp)) {
            Label("提醒什么")
            Gap(8.dp)
            Card(padding = PaddingValues(16.dp, 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Codex 任务", color = c.ink, fontSize = 14.sp)
                        Text(
                            "完成、出错时通知；等你批准时直接在通知上批准或拒绝",
                            color = c.ink3, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Switch(
                        checked = codexOn,
                        onCheckedChange = {
                            codexOn = it
                            AlertPrefs.setEnabled(ctx, it)
                            if (it) Watchdog.kick(ctx) else { Watchdog.cancel(ctx); AlertService.stop(ctx) }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.bg),
                    )
                }
                HorizontalDivider(color = c.line2)
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("待办", color = c.ink, fontSize = 14.sp)
                        Text("设了时间的待办到点提醒", color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                    Text("跟随待办", color = c.ink3, fontSize = 13.sp)
                }
            }
            Gap(20.dp)
            Label("手机设置")
            Gap(8.dp)
            Text(
                "小米对后台管得严，下面几项都打开，App 不在前台时提醒才能准时到。",
                color = c.ink2, fontSize = 13.sp, lineHeight = 20.sp,
            )
            Gap(10.dp)
            Card(padding = PaddingValues(16.dp, 4.dp)) { ReminderSetup(compact = false) }
            if (codexOn) {
                Gap(20.dp)
                Text(
                    "为了实时收到 Codex 的提醒，App 会在后台和服务器保持一条连接，通知栏底部会有一条折叠的「提醒已开启」。" +
                        "觉得碍眼的话，长按它 → 关闭「后台连接」这一类通知即可，连接不受影响。",
                    color = c.ink3, fontSize = 12.sp, lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun Item(label: String, desc: String?, ok: Boolean?, action: String, onClick: () -> Unit) {
    val c = T.c
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = if (desc != null) 12.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = c.ink, fontSize = 14.sp)
            if (desc != null) Text(desc, color = c.ink3, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
        }
        val done = ok == true
        Text(if (done) "已开启" else action, color = if (done) c.ink3 else c.accent, fontSize = 13.sp, fontWeight = if (done) FontWeight.Normal else FontWeight.Medium)
    }
}
