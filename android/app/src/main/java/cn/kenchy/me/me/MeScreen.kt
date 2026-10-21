package cn.kenchy.me.me

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.interaction.collectIsPressedAsState

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.BuildConfig
import cn.kenchy.me.codex.CodexVm
import cn.kenchy.me.core.ago
import cn.kenchy.me.core.money
import cn.kenchy.me.core.monthLabel
import cn.kenchy.me.core.pct
import cn.kenchy.me.savings.SavingsVm
import cn.kenchy.me.todo.rememberReminderOk
import cn.kenchy.me.ui.Bar
import cn.kenchy.me.ui.Card
import cn.kenchy.me.ui.Dot
import cn.kenchy.me.ui.Gap
import cn.kenchy.me.ui.HGap
import cn.kenchy.me.ui.Label
import cn.kenchy.me.ui.MoneyText
import cn.kenchy.me.ui.T
import cn.kenchy.me.ui.UpdateVm

@Composable
fun MeScreen(
    savings: SavingsVm,
    todoVm: cn.kenchy.me.todo.TodoVm,
    updater: UpdateVm,
    todos: List<cn.kenchy.me.todo.Todo>?,
    openSavings: () -> Unit,
    openNotes: () -> Unit,
    openReminders: () -> Unit,
) {
    val c = T.c
    val all by savings.rows.collectAsState()
    val ledger by savings.data.collectAsState()
    val update by updater.available.collectAsState()
    val updateMsg by updater.progress.collectAsState()
    val checking by updater.checking.collectAsState()
    var refreshing by remember { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            todoVm.refresh()
            val jobs = listOfNotNull(savings.refresh(), updater.check(force = true))
            var left = jobs.size
            jobs.forEach { it.invokeOnCompletion { if (--left == 0) refreshing = false } }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("我", style = MaterialTheme.typography.headlineMedium, color = c.ink, modifier = Modifier.weight(1f))
                }
            }

            // 功能入口：点进去才看内容。资产要先验证指纹，首页不露金额
            item {
                val notes = todos.orEmpty().filter { it.kind == "note" }
                val thisMonth = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Shanghai"))
                val lastMonth = thisMonth.minusMonths(1).toString()
                val missing = ledger != null && all.none { it.month == lastMonth }
                Card(padding = PaddingValues(0.dp, 6.dp)) {
                    Entry(
                        Icons.Outlined.AccountBalanceWallet, c.accent, "资产",
                        if (missing) "${monthLabel(lastMonth)} 还没记" else "存款、收入与储蓄率",
                        warn = missing, locked = true, onClick = openSavings,
                    )
                    RowDivider()
                    Entry(
                        Icons.Outlined.StickyNote2, c.salary, "备忘",
                        when {
                            notes.isEmpty() -> "随手记点东西"
                            else -> notes.maxBy { it.updatedAt }.text.lineSequence().first().take(24)
                        },
                        count = notes.size.takeIf { it > 0 }, onClick = openNotes,
                    )
                }
            }

            // 设置
            item {
                val remindOk = rememberReminderOk()
                Card(padding = PaddingValues(0.dp, 6.dp)) {
                    Row(
                        Modifier.pressRow(onClick = openReminders),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("通知与提醒", color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (!remindOk) {
                            Dot(c.warn, 6.dp)
                            HGap(6.dp)
                        }
                        Text(if (remindOk) "已就绪" else "未设置完", color = if (remindOk) c.ink3 else c.warn, fontSize = 13.sp)
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.ink3, modifier = Modifier.size(18.dp))
                    }
                    RowDivider()
                    Row(
                        Modifier.pressRow(onClick = { if (update != null) updater.install() else updater.check(force = true) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("版本", color = c.ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (update != null) {
                            // 有新版：做成一个小胶囊按钮，下载中显示进度
                            Text(
                                updateMsg ?: "更新到 ${update!!.versionName}",
                                color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(c.accentSoft).padding(12.dp, 5.dp),
                            )
                        } else {
                            Text("${BuildConfig.VERSION_NAME} · ${checking ?: "已是最新"}", color = c.ink3, fontSize = 13.sp)
                        }
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Text("小宇宙 · 只给自己用", color = c.line, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Entry(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    sub: String,
    warn: Boolean = false,
    locked: Boolean = false,
    count: Int? = null,
    onClick: () -> Unit,
) {
    val c = T.c
    Row(Modifier.pressRow(onClick = onClick, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        HGap(14.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (locked) { HGap(6.dp); Icon(Icons.Outlined.Lock, "需要验证", tint = c.ink3, modifier = Modifier.size(13.dp)) }
            }
            Text(sub, color = if (warn) c.warn else c.ink3, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (warn) { Dot(c.warn, 6.dp); HGap(6.dp) }
        count?.let { Text("$it", color = c.ink3, fontSize = 13.sp) }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = c.ink3, modifier = Modifier.size(18.dp))
    }
}

/**
 * 卡片里的一行：按下时整行铺到卡片两边（左右留同样的边距），在卡片内缩进 6dp 的圆角块里柔和变色，
 * 不再是贴着文字边的直角色块。
 */
@Composable
private fun Modifier.pressRow(onClick: () -> Unit, vertical: androidx.compose.ui.unit.Dp = 14.dp): Modifier {
    val c = T.c
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val bg by androidx.compose.animation.animateColorAsState(
        if (pressed) c.line2 else androidx.compose.ui.graphics.Color.Transparent,
        androidx.compose.animation.core.tween(if (pressed) 60 else 220), label = "press",
    )
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.985f else 1f, androidx.compose.animation.core.tween(120), label = "pressScale")
    return this
        .fillMaxWidth()
        .padding(horizontal = 6.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(14.dp))
        .background(bg)
        .clickable(interactionSource = src, indication = null, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = vertical)
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = T.c.line2)
