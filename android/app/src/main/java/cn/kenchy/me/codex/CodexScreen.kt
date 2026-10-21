package cn.kenchy.me.codex

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.core.SessionSummary
import cn.kenchy.me.core.ago
import cn.kenchy.me.ui.Card
import cn.kenchy.me.ui.Dot
import cn.kenchy.me.ui.ErrorBox
import cn.kenchy.me.ui.Gap
import cn.kenchy.me.ui.HGap
import cn.kenchy.me.ui.Loading
import cn.kenchy.me.ui.Pill
import cn.kenchy.me.ui.T

@Composable
fun StateDot(state: String) {
    val c = T.c
    when (state) {
        "running" -> {
            val t = rememberInfiniteTransition(label = "pulse")
            val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
            Box(Modifier.alpha(a)) { Dot(c.accent) }
        }
        "waiting" -> Dot(c.warn)
        "error" -> Dot(c.danger)
        else -> Dot(c.line)
    }
}

/** 未读：实心的大一点的点，外面一圈淡色光晕；出错的未读用红色 */
@Composable
private fun UnreadDot(state: String) {
    val c = T.c
    val col = if (state == "error") c.danger else c.accent
    Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(col.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(col))
    }
}

fun stateLabel(state: String) = when (state) {
    "waiting" -> "等你处理"
    "running" -> "运行中"
    "error" -> "出错"
    else -> "已结束"
}

fun shortPath(p: String): String {
    val parts = p.trimEnd('/').split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 2) p else "…/" + parts.takeLast(2).joinToString("/")
}

@Composable
fun CodexScreen(vm: CodexVm, openSession: (String, String) -> Unit, newSession: () -> Unit) {
    val list by vm.list.collectAsState()
    val error by vm.listError.collectAsState()
    val live by vm.live.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var machine by remember { mutableStateOf<String?>(null) }
    val seenVersion by Seen.version.collectAsState()
    Toasts(vm)

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; vm.refreshList().invokeOnCompletion { refreshing = false } },
            modifier = Modifier.fillMaxSize(),
        ) {
            val data = list
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Codex", style = MaterialTheme.typography.headlineMedium, color = T.c.ink, modifier = Modifier.weight(1f))
                        Dot(if (live) T.c.accent else T.c.ink3, 6.dp)
                        HGap(6.dp)
                        Text(if (live) "实时" else "未连接", color = T.c.ink3, fontSize = 12.sp)
                    }
                }
                if (data == null) {
                    item { if (error != null) ErrorBox(error!!, { vm.refreshList() }) else Box(Modifier.padding(48.dp)) { Loading() } }
                    return@LazyColumn
                }
                // 机器筛选
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Chip("全部", machine == null) { machine = null }
                        data.machines.forEach { m ->
                            Chip(m.name, machine == m.name, dim = !m.online) { machine = if (machine == m.name) null else m.name }
                        }
                    }
                }
                data.errors.forEach { e ->
                    item { Text("${e.machine ?: ""} ${e.error}", color = T.c.down, fontSize = 12.sp) }
                }
                val shown = data.sessions.filter { machine == null || it.machine == machine }.distinctBy { "${it.machine}/${it.id}" }
                @Suppress("UNUSED_EXPRESSION") seenVersion
                val unread = shown.filter { it.state != "waiting" && Seen.unread(it) }
                val groups = listOf(
                    "等你处理" to shown.filter { it.state == "waiting" },
                    "运行中" to shown.filter { it.state == "running" },
                    "未读" to unread,
                    "最近 7 天" to shown.filter { (it.state == "done" || it.state == "error") && it !in unread },
                )
                groups.forEach { (title, items) ->
                    if (items.isEmpty()) return@forEach
                    item(key = "h/$title") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp).animateItem(), verticalAlignment = Alignment.CenterVertically) {
                            Text("$title · ${items.size}", color = if (title == "未读") T.c.accent else T.c.ink3, fontSize = 12.sp,
                                fontWeight = if (title == "未读") FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                            if (title == "未读") Text(
                                "全部已读", color = T.c.ink3, fontSize = 12.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { Seen.markAllSeen(items) }.padding(8.dp, 2.dp),
                            )
                        }
                    }
                    items(items, key = { "${it.machine}/${it.id}" }) { s ->
                        Box(Modifier.animateItem()) { SessionRow(s, vm, unread = s in unread) { openSession(s.machine, s.id) } }
                    }
                }
                if (shown.isEmpty()) item {
                    Text(
                        if (data.machines.none { it.online }) "没有在线的机器。机器上的 agentdeck 连上后，这里会出现它的会话。" else "最近 7 天没有会话",
                        color = T.c.ink3, fontSize = 14.sp, modifier = Modifier.padding(24.dp),
                    )
                }
                if (data.older > 0) item { Text("更早的 ${data.older} 个会话已收起", color = T.c.ink3, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }
            }
        }
        FloatingActionButton(
            onClick = newSession,
            containerColor = T.c.accent,
            contentColor = T.c.bg,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Outlined.Add, "新会话") }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    val c = T.c
    Text(
        text,
        Modifier
            .alpha(if (dim && !on) 0.5f else 1f)
            .clip(RoundedCornerShape(50))
            .background(if (on) c.accent else c.line2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        color = if (on) c.bg else c.ink2,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun SessionRow(s: SessionSummary, vm: CodexVm, unread: Boolean = false, onClick: () -> Unit) {
    Card(onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (unread) UnreadDot(s.state) else StateDot(s.state)
            HGap(10.dp)
            Text(s.name, color = T.c.ink, fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            HGap(8.dp)
            Text(ago(s.updatedAt * 1000), color = if (unread) T.c.accent else T.c.ink3, fontSize = 12.sp,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal)
        }
        Gap(4.dp)
        Row(Modifier.padding(start = 18.dp)) {
            Text("${s.machine} · ${shortPath(s.cwd)}", color = T.c.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.lastMessage.isNotBlank()) {
            Gap(6.dp)
            Text(s.lastMessage, color = if (unread) T.c.ink else T.c.ink2, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 18.dp))
        }
        s.approvals.firstOrNull()?.let { a ->
            Gap(8.dp)
            ApprovalBox(a, onDecide = { ok -> vm.approve(s.machine, a.key, ok) })
        }
    }
}

@Composable
fun ApprovalBox(a: cn.kenchy.me.core.Approval, onDecide: (Boolean) -> Unit) {
    val c = T.c
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.warnSoft).padding(12.dp)) {
            Pill(
                when (a.kind) {
                    "fileChange" -> "要改文件"
                    "permissions" -> "要更多权限"
                    else -> "要运行命令"
                },
                c.warn, c.surface,
            )
            a.reason?.let { Gap(6.dp); Text(it, color = c.ink, fontSize = 13.sp, lineHeight = 19.sp) }
            a.command?.let { Gap(6.dp); Text(it, style = cn.kenchy.me.ui.Mono.copy(color = c.ink2), maxLines = 4, overflow = TextOverflow.Ellipsis) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onDecide(false) }) { Text("拒绝", color = c.ink2) }
                TextButton(onClick = { onDecide(true) }) { Text("批准", color = c.accent, fontWeight = FontWeight.SemiBold) }
            }
    }
}

@Composable
fun Toasts(vm: CodexVm) {
    val ctx = LocalContext.current
    LaunchedEffect(vm) {
        vm.toast.collect { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show() }
    }
}
