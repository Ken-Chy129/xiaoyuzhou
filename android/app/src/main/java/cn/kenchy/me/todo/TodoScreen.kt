package cn.kenchy.me.todo

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Add
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.ui.Card
import cn.kenchy.me.ui.ErrorBox
import cn.kenchy.me.ui.Gap
import cn.kenchy.me.ui.HGap
import cn.kenchy.me.ui.Loading
import cn.kenchy.me.ui.T
import cn.kenchy.me.ui.VoiceButton
import cn.kenchy.me.ui.appendVoice
import kotlinx.coroutines.launch

/** 待办页：只看今天（加上之前没做完的、没定日期的）。右上角日历看其他日子 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TodoScreen(vm: TodoVm, openLink: (String) -> Unit, openCalendar: () -> Unit) {
    val all by vm.items.collectAsState()
    val error by vm.error.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Todo?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val c = T.c

    LaunchedEffect(Unit) { vm.toast.collect { snack.showSnackbar(it) } }

    fun deleteWithUndo(t: Todo) {
        vm.delete(t)
        scope.launch {
            if (snack.showSnackbar("已删除「${t.text.take(12)}」", actionLabel = "撤销") == SnackbarResult.ActionPerformed) vm.restore(t)
        }
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; vm.refresh().invokeOnCompletion { refreshing = false } },
            modifier = Modifier.fillMaxSize(),
        ) {
            val list = all
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 104.dp),
            ) {
                item { Header(list, openCalendar) }
                item(key = "setup") {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val hasTimed = list?.any { it.kind == "todo" && !it.done && it.due != null } == true
                    if (hasTimed && !ReminderPerms.allOk(ctx)) {
                        Card(Modifier.padding(top = 8.dp), padding = PaddingValues(16.dp, 12.dp)) {
                            ReminderSetup(compact = true, onChanged = { list?.let { Reminders.sync(ctx, it) } })
                        }
                    }
                }
                if (list == null) {
                    item { if (error != null) ErrorBox(error!!, { vm.refresh() }) else Box(Modifier.padding(48.dp)) { Loading() } }
                    return@LazyColumn
                }
                val g = group(list, nowLocal())
                val row: @Composable (Todo) -> Unit = { t ->
                    TodoRow(
                        t,
                        onToggle = { vm.toggleDone(t) },
                        onOpen = { if (t.link != null && !t.done) openLink(t.link) else editing = t },
                        onLong = { editing = t },
                        onPin = { vm.togglePin(t) },
                    )
                }
                todoSection("之前没做完", g.overdue, c.down, row)
                todoSection("今天", g.today, c.accent, row)
                todoSection("随时", g.someday, null, row)
                if (g.overdue.isEmpty() && g.today.isEmpty() && g.someday.isEmpty()) {
                    item { Empty(g.upcoming, openCalendar) }
                }
                todoSection("今天完成了", g.doneToday, c.ink3, row)
                if (g.upcoming > 0 && (g.overdue.isNotEmpty() || g.today.isNotEmpty() || g.someday.isNotEmpty())) item(key = "upcoming") {
                    Text(
                        "以后还有 ${g.upcoming} 件 · 在日历里看 ›",
                        color = c.ink3, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 20.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = openCalendar).padding(6.dp),
                    )
                }
            }
        }
        androidx.compose.material3.FloatingActionButton(
            onClick = { editing = Todo(id = "", due = todayLocal().toString()) },
            containerColor = c.accent, contentColor = c.bg,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Outlined.Add, "新建待办") }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp))
    }

    editing?.let { t ->
        TodoEditor(t, vm, onClose = { editing = null }, onDelete = { editing = null; deleteWithUndo(t) })
    }
}

/** 新建和编辑待办共用的入口 */
@Composable
internal fun TodoEditor(t: Todo, vm: TodoVm, onClose: () -> Unit, onDelete: () -> Unit) {
    val isNew = t.id.isEmpty()
    TodoForm(
        t,
        isNew = isNew,
        onDismiss = onClose,
        onSave = { v, _ ->
            if (isNew) vm.create(v) { onClose() }
            else { vm.save(v); onClose() }
        },
        onDelete = onDelete,
    )
}

internal fun androidx.compose.foundation.lazy.LazyListScope.todoSection(
    title: String, items: List<Todo>, accent: Color?, row: @Composable (Todo) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "h/$title") { SectionHead(title, items.size, accent) }
    item(key = "c/$title") {
        Card(padding = PaddingValues(0.dp), modifier = Modifier.animateContentSize()) {
            items.forEachIndexed { i, t ->
                if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 52.dp).size(height = 1.dp, width = 0.dp).background(T.c.line2))
                row(t)
            }
        }
    }
}

@Composable
private fun Header(list: List<Todo>?, openCalendar: () -> Unit) {
    val c = T.c
    val now = nowLocal()
    val today = now.toLocalDate()
    val g = list?.let { group(it, now) }
    val left = g?.let { it.overdue.size + it.today.size } ?: 0
    val wd = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[today.dayOfWeek.value - 1]
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("今天", style = MaterialTheme.typography.headlineMedium, color = c.ink)
            Gap(4.dp)
            Text(
                "${today.monthValue}月${today.dayOfMonth}日 $wd · " + when {
                    g == null -> ""
                    g.overdue.isNotEmpty() -> "还有 $left 件，${g.overdue.size} 件是之前的"
                    left > 0 -> "还有 $left 件"
                    g.doneToday.isNotEmpty() -> "今天的都做完了"
                    else -> "今天没有安排"
                },
                color = if (g?.overdue?.isNotEmpty() == true) c.down else c.ink2, fontSize = 14.sp,
            )
        }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line2, RoundedCornerShape(14.dp))
                .clickable(onClick = openCalendar),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.CalendarMonth, "日历", tint = c.ink2, modifier = Modifier.size(22.dp)) }
    }
}

@Composable
internal fun SectionHead(label: String, count: Int, accent: Color?) {
    val c = T.c
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).clip(RoundedCornerShape(2.dp)).background(accent ?: c.ink3))
        HGap(8.dp)
        Text(label, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        HGap(6.dp)
        Text("$count", color = c.ink3, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TodoRow(t: Todo, onToggle: () -> Unit, onOpen: () -> Unit, onLong: () -> Unit, onPin: () -> Unit) {
    val c = T.c
    val now = nowLocal()
    val late = t.overdue(now)
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLong).padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t.kind == "todo") {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onToggle), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(if (t.done) c.accent else Color.Transparent)
                        .border(1.5.dp, if (t.done) c.accent else if (late) c.down else c.ink3, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (t.done) Icon(Icons.Outlined.Check, "已完成", tint = c.bg, modifier = Modifier.size(14.dp)) }
            }
        } else {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.StickyNote2, null, tint = c.ink3, modifier = Modifier.size(18.dp))
            }
        }
        HGap(4.dp)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                t.text,
                color = if (t.done) c.ink3 else c.ink,
                fontSize = 15.sp, lineHeight = 22.sp,
                textDecoration = if (t.done) TextDecoration.LineThrough else null,
                maxLines = if (t.kind == "note") 4 else 3, overflow = TextOverflow.Ellipsis,
            )
            val meta = buildList {
                t.due?.let { add(describeDue(it, now.toLocalDate()) + if (late) " · 已过" else "") }
                t.repeat?.let { add(describeRepeat(it)) }
                if (t.link == "savings/new" && !t.done) add("点一下去记账")
            }
            if (meta.isNotEmpty()) {
                Gap(3.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.repeat != null) {
                        Icon(Icons.Outlined.Repeat, null, tint = c.ink3, modifier = Modifier.size(12.dp))
                        HGap(3.dp)
                    }
                    Text(meta.joinToString(" · "), color = if (late) c.down else c.ink3, fontSize = 12.sp)
                }
            }
        }
        if (!t.done) {
            IconButton(onClick = onPin) {
                if (t.pinned) Icon(Icons.Filled.Star, "取消重点", tint = c.salary, modifier = Modifier.size(20.dp))
                else Icon(Icons.Outlined.StarBorder, "标为重点", tint = c.line, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun Empty(upcoming: Int, openCalendar: () -> Unit) {
    val c = T.c
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("今天没有要做的", color = c.ink2, fontSize = 15.sp)
        Gap(10.dp)
        if (upcoming > 0) Text(
            "以后还有 $upcoming 件 · 在日历里看 ›", color = c.accent, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = openCalendar).padding(6.dp),
        ) else Text("点右下角的 ＋ 新建", color = c.ink3, fontSize = 13.sp)
    }
}

/* ---------- 编辑 ---------- */

private val REPEATS = listOf<Pair<String, ((java.time.LocalDate) -> Repeat?)>>(
    "不重复" to { _ -> null },
    "每天" to { _ -> Repeat("day") },
    "每周" to { _ -> Repeat("week") },
    "每月" to { d -> Repeat("month", day = d.dayOfMonth) },
    "每年" to { d -> Repeat("year", day = d.dayOfMonth, month = d.monthValue) },
)

/** 新建和编辑共用的表单 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TodoForm(t: Todo, isNew: Boolean, onDismiss: () -> Unit, onSave: (Todo, Boolean) -> Unit, onDelete: () -> Unit) {
    val c = T.c
    var text by remember { mutableStateOf(t.text) }
    var kind by remember { mutableStateOf(t.kind) }
    var pinned by remember { mutableStateOf(t.pinned) }
    var date by remember { mutableStateOf(t.due?.substring(0, 10)) }
    var time by remember { mutableStateOf(t.due?.takeIf { it.length > 10 }?.substring(11)) }
    var repeatIdx by remember {
        mutableStateOf(when (t.repeat?.unit) { "day" -> 1; "week" -> 2; "month" -> 3; "year" -> 4; else -> 0 })
    }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val today = todayLocal()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (isNew) LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); runCatching { focus.requestFocus() } }

    fun build(): Todo {
        val d = if (kind == "todo") date else null
        val due = d?.let { it + (time?.let { tm -> "T$tm" } ?: "") }
        val rep = if (kind == "todo" && d != null) REPEATS[repeatIdx].second(java.time.LocalDate.parse(d)) else null
        return t.copy(text = text.trim(), kind = kind, pinned = pinned, due = due, repeat = rep)
    }
    fun submit() {
        if (text.isBlank()) return
        onSave(build(), false)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(20.dp, 0.dp, 20.dp, 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 待办和备忘分在两处，从哪里进来就是哪种，不在表单里切换
                Text((if (isNew) "新建" else "编辑") + if (kind == "note") "备忘" else "待办", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Gap(14.dp)
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text(if (kind == "todo") "要做什么" else "记点什么", color = c.ink3) },
                minLines = if (kind == "note") 3 else 1, maxLines = 8,
                trailingIcon = { VoiceButton(onText = { text = appendVoice(text, it) }) },
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = if (kind == "todo") androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line),
            )
            if (kind == "todo") {
                FormRow(Icons.Outlined.Event, "日期") {
                    Chip("今天", date == today.toString()) { date = today.toString() }
                    Chip("明天", date == today.plusDays(1).toString()) { date = today.plusDays(1).toString() }
                    val other = date != null && date != today.toString() && date != today.plusDays(1).toString()
                    Chip(if (other) describeDue(date!!, today) else "选日期", other) { pickDate = true }
                    if (date != null) Chip("不设", false) { date = null; time = null; repeatIdx = 0 }
                }
                if (date != null) {
                    FormRow(Icons.Outlined.Schedule, "时间") {
                        Chip("全天", time == null) { time = null }
                        listOf("09:00", "14:00", "20:00").forEach { tm -> Chip(tm, time == tm) { time = tm } }
                        val custom = time != null && time !in listOf("09:00", "14:00", "20:00")
                        Chip(if (custom) time!! else "自定义", custom) { pickTime = true }
                    }
                    FormRow(Icons.Outlined.Repeat, "重复") {
                        REPEATS.forEachIndexed { i, (label, _) -> Chip(label, repeatIdx == i) { repeatIdx = i } }
                    }
                }
            }
            FormRow(if (pinned) Icons.Filled.Star else Icons.Outlined.StarBorder, "重点", tint = if (pinned) c.salary else null) {
                Chip(if (pinned) "已标为重点" else "标为重点", pinned) { pinned = !pinned }
            }
            if (!isNew && t.doneCount > 0) {
                Gap(10.dp)
                Text("已经按时完成过 ${t.doneCount} 次", color = c.ink3, fontSize = 12.sp)
            }
            Gap(20.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) {
                    TextButton(onClick = onDelete) { Text("删除", color = c.danger) }
                }
                Box(Modifier.weight(1f))
                Button(
                    onClick = ::submit,
                    enabled = text.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent),
                ) { Text(if (isNew) "添加" else "保存", modifier = Modifier.padding(horizontal = 14.dp)) }
            }
        }
    }

    if (pickDate) DatePick(date ?: today.toString(), { date = it; pickDate = false }, { pickDate = false })
    if (pickTime) TimePick(time ?: "09:00", { time = it; pickTime = false }, { time = null; pickTime = false })
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FormRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color? = null, content: @Composable () -> Unit) {
    val c = T.c
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Top) {
        Row(Modifier.padding(top = 6.dp).size(width = 64.dp, height = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint ?: c.ink3, modifier = Modifier.size(16.dp))
            HGap(6.dp)
            Text(label, color = c.ink3, fontSize = 13.sp)
        }
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = T.c
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(if (on) c.accent else c.line2).clickable(onClick = onClick).padding(14.dp, 6.dp),
        color = if (on) c.bg else c.ink2,
        fontSize = 13.sp,
    )
}

@Composable
private fun DatePick(initial: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ms = java.time.LocalDate.parse(initial).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = ms)
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let {
                    onPick(java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString())
                } ?: onDismiss()
            }) { Text("确定", color = T.c.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = T.c.ink2) } },
    ) { androidx.compose.material3.DatePicker(state, showModeToggle = false) }
}

@Composable
private fun TimePick(initial: String, onPick: (String) -> Unit, onAllDay: () -> Unit) {
    val (h, m) = initial.split(":").map { it.toInt() }
    val state = androidx.compose.material3.rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onPick(initial) },
        confirmButton = {
            TextButton(onClick = { onPick("%02d:%02d".format(state.hour, state.minute)) }) { Text("确定", color = T.c.accent) }
        },
        dismissButton = { TextButton(onClick = onAllDay) { Text("设为全天", color = T.c.ink2) } },
        text = { androidx.compose.material3.TimePicker(state) },
    )
}
