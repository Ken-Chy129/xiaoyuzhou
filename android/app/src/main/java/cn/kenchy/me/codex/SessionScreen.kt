package cn.kenchy.me.codex

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.core.Step
import cn.kenchy.me.core.Turn
import cn.kenchy.me.core.TurnItem
import cn.kenchy.me.ui.ErrorBox
import cn.kenchy.me.ui.Gap
import cn.kenchy.me.ui.HGap
import cn.kenchy.me.ui.Loading
import cn.kenchy.me.ui.Mono
import cn.kenchy.me.ui.Pill
import cn.kenchy.me.ui.T
import kotlinx.coroutines.launch

@Composable
fun SessionScreen(vm: CodexVm, machine: String, id: String, back: () -> Unit) {
    DisposableEffect(machine, id) {
        vm.open(machine, id)
        cn.kenchy.me.alerts.AlertPrefs.watching = "$machine/$id"
        onDispose {
            vm.close(machine, id)
            if (cn.kenchy.me.alerts.AlertPrefs.watching == "$machine/$id") cn.kenchy.me.alerts.AlertPrefs.watching = null
        }
    }
    Toasts(vm)
    val detail by vm.detail.collectAsState()
    val error by vm.detailError.collectAsState()
    val d = detail?.takeIf { it.machine == machine && it.id == id }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 草稿按会话存在本机：切到别的页面、App 被系统回收后回来都还在，发送成功才清空
    val draftKey = "$machine/$id"
    var text by remember(draftKey) { mutableStateOf(Drafts.get(draftKey)) }
    var sending by remember { mutableStateOf(false) }
    val attachments = cn.kenchy.me.ui.rememberAttachments(draftKey)
    val older by vm.older.collectAsState()
    val olderLoading by vm.olderLoading.collectAsState()

    // 新内容到来时滚到底部：第一次直接定位（不播动画）；先显示的是缓存时，最新内容一到再直接定位一次；
    // 之后只在本来就停在底部附近时才跟随
    val fresh by vm.fresh.collectAsState()
    val total = d?.turns?.sumOf { it.items.size + 1 } ?: 0
    var placed by remember(draftKey) { mutableStateOf(false) }
    var placedFresh by remember(draftKey) { mutableStateOf(false) }
    LaunchedEffect(total, fresh) {
        if (total == 0) return@LaunchedEffect
        val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        if (!placed || (fresh && !placedFresh)) {
            // 等这一帧把新条目排好再定位，否则 totalItemsCount 还是旧的
            kotlinx.coroutines.yield()
            androidx.compose.runtime.withFrameNanos { }
            // 最后一条可能很长：定位到它之后再滚到它的底部
            listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0), Int.MAX_VALUE / 2)
            placed = true
            if (fresh) placedFresh = true
        } else {
            val seen = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (last - seen <= 3) listState.animateScrollToItem(last)
        }
    }
    // 滚到顶：加载更早的几轮
    val atTop by remember { derivedStateOf { placed && listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(atTop, d?.before) { if (atTop && vm.hasOlder()) vm.loadOlder(machine, id) }
    // 键盘弹起 / 收起：列表跟着键盘同步上移，看到的最后几行不被盖住（逐帧跟随动画）
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeInsets = androidx.compose.foundation.layout.WindowInsets.Companion.ime
    LaunchedEffect(listState) {
        var prev = imeInsets.getBottom(density)
        androidx.compose.runtime.snapshotFlow { imeInsets.getBottom(density) }.collect { now ->
            val delta = now - prev
            prev = now
            if (delta > 0) listState.scrollBy(delta.toFloat())
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        // 顶栏
        Row(Modifier.fillMaxWidth().padding(4.dp, 4.dp, 12.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = T.c.ink) }
            Column(Modifier.weight(1f)) {
                Text(d?.name ?: "会话", color = T.c.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateDot(d?.state ?: "done")
                    HGap(6.dp)
                    Text(
                        listOfNotNull(d?.state?.let(::stateLabel), machine, d?.cwd?.let(::shortPath)).joinToString(" · "),
                        color = T.c.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (d?.state == "running" || d?.state == "waiting") {
                IconButton(onClick = { vm.stop(machine, id) }) { Icon(Icons.Outlined.Stop, "停止", tint = T.c.danger) }
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                d == null && error != null -> ErrorBox(error!!, { vm.refreshDetail(machine, id, userRetry = true) })
                d == null -> Loading()
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "older") {
                        Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                            when {
                                olderLoading -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = T.c.accent, strokeWidth = 2.dp)
                                vm.hasOlder() -> Text("上滑加载更早的内容", color = T.c.ink3, fontSize = 12.sp, modifier = Modifier.clickable { vm.loadOlder(machine, id) })
                                else -> Text("已经到最开始了", color = T.c.ink3, fontSize = 12.sp)
                            }
                        }
                    }
                    val shownIds = d.turns.map { it.id }.toSet()
                    (older.filter { it.id !in shownIds } + d.turns).forEach { turn ->
                        turn.items.forEachIndexed { i, item -> item(key = "${turn.id}/$i") { TurnItemView(item, machine, id) } }
                        item(key = "${turn.id}/end") { TurnFooter(turn) }
                    }
                    d.approvals.forEach { a ->
                        item(key = "approval/${a.key}") { ApprovalBox(a) { ok -> vm.approve(machine, a.key, ok, id) } }
                    }
                }
            }
        }
        // 输入框：空闲时开新一轮，运行中则插话
        cn.kenchy.me.ui.Composer(
            text = text,
            onText = { text = it; Drafts.put(draftKey, it) },
            placeholder = if (d?.state == "running") "插话，下一步生效…" else "继续推进…",
            attachments = attachments,
            sending = sending,
            onSend = {
                if (!sending) {
                    sending = true
                    scope.launch {
                        if (vm.send(machine, id, text.trim(), attachments.dataUrls)) {
                            text = ""
                            Drafts.put(draftKey, "")
                            attachments.clear()
                        }
                        sending = false
                    }
                }
            },
            modifier = Modifier.background(T.c.surface).navigationBarsPadding().padding(12.dp, 8.dp, 12.dp, 10.dp),
        )
    }
}

@Composable
private fun TurnItemView(item: TurnItem, machine: String, sessionId: String) {
    val c = T.c
    when (item.kind) {
        "user" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(horizontalAlignment = Alignment.End) {
                // 图片单独一行显示在气泡上方，点开看大图
                val refs = item.images
                if (refs.isNotEmpty() && item.id != null) {
                    val imgs = remember(item.id, refs) {
                        refs.map {
                            cn.kenchy.me.ui.ViewImage.Remote(
                                "/api/codex/sessions/${cn.kenchy.me.ui.enc(machine)}/${cn.kenchy.me.ui.enc(sessionId)}/images/${cn.kenchy.me.ui.enc(item.id)}/${it.index}",
                            )
                        }
                    }
                    var viewing by remember { mutableStateOf<Int?>(null) }
                    viewing?.let { cn.kenchy.me.ui.ImageViewer(imgs, it) { viewing = null } }
                    Row(
                        Modifier.padding(bottom = 6.dp).widthIn(max = 320.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        imgs.forEachIndexed { i, img -> cn.kenchy.me.ui.Thumb(img, if (imgs.size == 1) 160.dp else 96.dp, onClick = { viewing = i }) }
                    }
                }
                if (!item.text.isNullOrBlank() || (item.count > 0 && refs.isEmpty())) SelectionContainer {
                Column(
                    Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(c.accentSoft).padding(12.dp, 10.dp),
                ) {
                    if (item.count > 0 && refs.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Image, null, tint = c.accent, modifier = Modifier.size(15.dp))
                            HGap(4.dp)
                            Text("${item.count} 张图片", color = c.accent, fontSize = 13.sp)
                        }
                        if (!item.text.isNullOrBlank()) Gap(4.dp)
                    }
                    if (!item.text.isNullOrBlank()) Text(item.text, color = c.ink, fontSize = 15.sp, lineHeight = 22.sp)
                }
            }
            }
        }
        "agent" -> SelectionContainer { Markdownish(item.text.orEmpty(), machine) }
        "plan" -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.line2).padding(12.dp)) {
            Pill("计划", c.accent, c.surface)
            Gap(6.dp)
            Text(item.text.orEmpty(), color = c.ink2, fontSize = 14.sp, lineHeight = 21.sp)
        }
        "steps" -> StepsView(item.steps)
        "files" -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp)) {
            Icon(Icons.Outlined.Description, null, tint = c.ink3, modifier = Modifier.size(16.dp))
            HGap(6.dp)
            Text(
                "改了 ${item.count} 个文件" + (item.files.firstOrNull()?.let { " · ${it.substringAfterLast('/')}" + if (item.count > 1) " 等" else "" } ?: ""),
                color = c.ink3, fontSize = 13.sp,
            )
        }
    }
}

/** 一组连续的命令，默认折叠成一行 */
@Composable
private fun StepsView(steps: List<Step>) {
    val c = T.c
    var open by remember { mutableStateOf(false) }
    val failed = steps.count { it.status == "failed" || (it.exitCode ?: 0) != 0 }
    val running = steps.any { it.status == "inProgress" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.line2)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(12.dp, 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Terminal, null, tint = c.ink3, modifier = Modifier.size(16.dp))
            HGap(8.dp)
            Text(
                buildString {
                    append(if (running) "正在执行" else "执行了")
                    append(" ${steps.size} 步")
                    if (failed > 0) append(" · $failed 步失败")
                },
                color = if (failed > 0) c.down else c.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = c.ink3, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(12.dp, 0.dp, 12.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                steps.forEach { s -> StepRow(s) }
            }
        }
    }
}

@Composable
private fun StepRow(s: Step) {
    val c = T.c
    var open by remember { mutableStateOf(false) }
    val bad = s.status == "failed" || (s.exitCode ?: 0) != 0
    Column {
        Row(Modifier.clickable(enabled = !s.output.isNullOrBlank()) { open = !open }, verticalAlignment = Alignment.Top) {
            Icon(
                when {
                    s.status == "declined" -> Icons.Outlined.Close
                    bad -> Icons.Outlined.ErrorOutline
                    else -> Icons.Outlined.CheckCircle
                },
                null,
                tint = if (bad) c.down else c.ink3,
                modifier = Modifier.size(14.dp).padding(top = 2.dp),
            )
            HGap(6.dp)
            Text(prettyCommand(s.command ?: s.name ?: ""), style = Mono.copy(color = c.ink2), maxLines = if (open) 8 else 2, overflow = TextOverflow.Ellipsis)
        }
        if (open && !s.output.isNullOrBlank()) {
            Gap(4.dp)
            Text(
                s.output, style = Mono.copy(color = c.ink3, fontSize = 11.sp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.surface).padding(8.dp),
            )
        }
    }
}

/** 去掉 /bin/bash -lc "..." 的外壳 */
private fun prettyCommand(cmd: String): String {
    val m = Regex("""^/bin/(?:ba|z)?sh -l?c ['"](.*)['"]$""", RegexOption.DOT_MATCHES_ALL).find(cmd.trim())
    return m?.groupValues?.get(1) ?: cmd
}

@Composable
private fun TurnFooter(turn: Turn) {
    val c = T.c
    val text = when (turn.status) {
        "inProgress" -> null
        "interrupted" -> "已停止"
        "failed" -> "出错：${turn.error ?: "未知错误"}"
        else -> turn.durationMs?.let { "用时 ${fmtDuration(it)}" }
    } ?: return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
        Text(text, color = if (turn.status == "failed") c.down else c.ink3, fontSize = 11.sp)
    }
}

private fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s} 秒"
        s < 3600 -> "${s / 60} 分 ${s % 60} 秒"
        else -> "${s / 3600} 小时 ${(s % 3600) / 60} 分"
    }
}
