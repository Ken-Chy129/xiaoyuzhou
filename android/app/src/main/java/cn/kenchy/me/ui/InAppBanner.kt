package cn.kenchy.me.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.alerts.Alert
import cn.kenchy.me.alerts.AlertPrefs
import cn.kenchy.me.alerts.InApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 站内提醒：App 在前台时代替系统通知。
 * - 正在看的那个会话、或者就在 Codex 列表上：不打扰（列表 / 页面本身会更新，角标和未读点照常）。
 * - 其他页面：顶部落下一张小卡片，4 秒后自己收起；往上一划立即收起；点一下直接进去。
 * - 等你批准：不自动收起，卡片上直接批准 / 拒绝；在别处处理掉了就自动收起。
 * - 短时间里连着完成好几个：合成一张「还有 N 个」，不排队一张张弹。
 */
@Composable
fun InAppBanner(route: String?, open: (Alert) -> Unit, approve: (Alert, Boolean) -> Unit) {
    var current by remember { mutableStateOf<Alert?>(null) }
    var more by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<Alert?>(null) } // 退场动画期间还要画的内容
    val routeNow by rememberUpdatedState(route)
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(Unit) {
        InApp.alerts.collect { a ->
            val cur = current
            if (a.kind == "resolved") {
                if (cur?.kind == "waiting" && cur.machine == a.machine && cur.approvalKey == a.approvalKey) current = null
                return@collect
            }
            val r = routeNow
            val inThatSession = AlertPrefs.watching == "${a.machine}/${a.sessionId}"
            val quiet = when (a.kind) {
                "todo" -> r == "todo"
                "waiting" -> inThatSession
                else -> inThatSession || r == "codex"
            }
            if (quiet) return@collect
            when {
                cur == null -> { current = a; more = 0 }
                a.kind == "waiting" -> { current = a; more = 0 } // 要你动手的优先
                cur.kind == "waiting" -> Unit // 正在等你批准：别被普通完成顶掉，角标会记着
                else -> { current = a; more += 1 }
            }
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    // 普通提醒 4 秒后收起；再来一条就重新计时
    LaunchedEffect(current, more) {
        val c = current ?: return@LaunchedEffect
        shown = c
        if (c.kind != "waiting") {
            delay(4_000)
            current = null
        }
    }
    // 自己点进了那个会话（或待办页）：卡片没用了，收起
    LaunchedEffect(route) {
        delay(300) // 等会话页登记 watching
        val c = current ?: return@LaunchedEffect
        if (AlertPrefs.watching == "${c.machine}/${c.sessionId}" || (c.kind == "todo" && route == "todo")) current = null
    }

    AnimatedVisibility(
        visible = current != null,
        enter = slideInVertically(spring(dampingRatio = 0.78f, stiffness = 420f)) { -it } + fadeIn(tween(160)),
        exit = slideOutVertically(tween(220)) { -it } + fadeOut(tween(180)),
    ) {
        val a = shown ?: return@AnimatedVisibility
        BannerCard(a, more, onOpen = { current = null; open(a) }, onDismiss = { current = null }, onApprove = { ok -> current = null; approve(a, ok) })
    }
}

@Composable
private fun BannerCard(a: Alert, more: Int, onOpen: () -> Unit, onDismiss: () -> Unit, onApprove: (Boolean) -> Unit) {
    val c = T.c
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val (icon, tint) = when (a.kind) {
        "waiting" -> Icons.Outlined.PanTool to c.warn
        "failed" -> Icons.Outlined.ErrorOutline to c.danger
        "interrupted" -> Icons.Outlined.StopCircle to c.ink3
        "todo" -> Icons.Outlined.Alarm to c.accent
        else -> Icons.Outlined.Check to c.accent
    }
    val title = when (a.kind) {
        "waiting" -> "等你批准"
        "failed" -> "出错了"
        "interrupted" -> "已中断"
        "todo" -> "到点了"
        else -> "已完成"
    }
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(10.dp, 6.dp)
            .offset { IntOffset(0, drag.value.roundToInt()) }
            .alpha(1f - (-drag.value / 300f).coerceIn(0f, 0.6f))
            .draggable(
                rememberDraggableState { d -> scope.launch { drag.snapTo((drag.value + d).coerceAtMost(24f)) } },
                Orientation.Vertical,
                onDragStopped = { v ->
                    if (drag.value < -40f || v < -800f) onDismiss()
                    else drag.animateTo(0f, spring(dampingRatio = 0.7f))
                },
            ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.line, shape)
                .clickable(onClick = onOpen)
                .padding(12.dp, 11.dp, 14.dp, 11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.padding(start = 11.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (a.kind != "todo" && a.machine.isNotBlank()) Text(" · ${a.machine}", color = c.ink3, fontSize = 12.sp)
                        if (more > 0) Text("  还有 $more 个", color = c.ink3, fontSize = 12.sp)
                    }
                    Text(
                        a.name.ifBlank { "Codex 任务" }, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (a.text.isNotBlank()) {
                        Text(a.text, color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (a.kind == "waiting" && a.approvalKey != null) {
                Row(Modifier.fillMaxWidth().padding(start = 45.dp, top = 10.dp)) {
                    BannerButton("拒绝", c.line2, c.ink2, Modifier.weight(1f)) { onApprove(false) }
                    HGap(8.dp)
                    BannerButton("批准", c.accent, c.bg, Modifier.weight(1f)) { onApprove(true) }
                }
            }
        }
    }
}

@Composable
private fun BannerButton(text: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}
