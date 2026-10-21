package cn.kenchy.me.todo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.ui.Card
import cn.kenchy.me.ui.Gap
import cn.kenchy.me.ui.T
import java.time.LocalDate
import java.time.YearMonth

private val WEEK = listOf("一", "二", "三", "四", "五", "六", "日")

/** 日历：当月格子（有待办的日子下面有点），左右切月，点一天在下面看那天的待办 */
@Composable
fun TodoCalendarScreen(vm: TodoVm, back: () -> Unit, openLink: (String) -> Unit) {
    val c = T.c
    val all by vm.items.collectAsState()
    val today = todayLocal()
    var monthStr by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var dayStr by rememberSaveable { mutableStateOf(today.toString()) }
    val month = YearMonth.parse(monthStr)
    val day = LocalDate.parse(dayStr)
    var editing by remember { mutableStateOf<Todo?>(null) }
    var forward by remember { mutableStateOf(true) }

    fun go(m: YearMonth) {
        forward = m.isAfter(month)
        monthStr = m.toString()
        // 切到今天所在的月就选今天，其他月选 1 号
        dayStr = (if (m == YearMonth.from(today)) today else m.atDay(1)).toString()
    }

    val items = all.orEmpty()
    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(18.dp, 0.dp, 18.dp, 104.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back, modifier = Modifier.padding(start = 0.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.ink) }
                    Text("${month.year}年${month.monthValue}月", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (month != YearMonth.from(today) || day != today) {
                        Text(
                            "今天", color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(c.accentSoft)
                                .clickable { forward = YearMonth.from(today).isAfter(month); monthStr = YearMonth.from(today).toString(); dayStr = today.toString() }
                                .padding(12.dp, 5.dp),
                        )
                    }
                    IconButton(onClick = { go(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上个月", tint = c.ink2) }
                    IconButton(onClick = { go(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下个月", tint = c.ink2) }
                }
            }
            item {
                Card(padding = PaddingValues(10.dp, 12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        WEEK.forEachIndexed { i, w ->
                            Text(
                                w, color = if (i >= 5) c.ink3 else c.ink2, fontSize = 12.sp,
                                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                    Gap(6.dp)
                    AnimatedContent(
                        month,
                        transitionSpec = {
                            val dir = if (forward) 1 else -1
                            (slideInHorizontally(tween(260)) { it / 4 * dir } + fadeIn(tween(220)))
                                .togetherWith(slideOutHorizontally(tween(220)) { -it / 4 * dir } + fadeOut(tween(160)))
                        },
                        label = "month",
                        modifier = Modifier.pointerInput(month) {
                            // 左右滑切月
                            var dx = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dx = 0f },
                                onDragEnd = { if (dx < -80) go(month.plusMonths(1)) else if (dx > 80) go(month.minusMonths(1)) },
                            ) { _, d -> dx += d }
                        },
                    ) { m ->
                        MonthGrid(m, day, today, dayMarks(items, m)) { dayStr = it.toString() }
                    }
                }
            }
            item {
                val wd = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day.dayOfWeek.value - 1]
                val label = when (day) {
                    today -> "今天"
                    today.plusDays(1) -> "明天"
                    today.minusDays(1) -> "昨天"
                    else -> ""
                }
                Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${day.monthValue}月${day.dayOfMonth}日 $wd", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (label.isNotEmpty()) Text("  $label", color = c.ink3, fontSize = 13.sp)
                }
            }
            val list = onDay(items, day)
            if (list.isEmpty()) item {
                Text(
                    if (day.isBefore(today)) "这天没有记录" else "这天还没有安排",
                    color = c.ink3, fontSize = 14.sp, modifier = Modifier.padding(vertical = 18.dp, horizontal = 4.dp),
                )
            } else item {
                Card(padding = PaddingValues(0.dp)) {
                    list.forEachIndexed { i, t ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 52.dp).size(height = 1.dp, width = 0.dp).background(c.line2))
                        // 重复事项在以后某天的「影子」：只能看，不在那天打勾（打勾会把下一次往后推）
                        val shadow = t.dueDate() != day
                        TodoRow(
                            t,
                            onToggle = { if (!shadow) vm.toggleDone(t) },
                            onOpen = { if (t.link != null && !t.done && !shadow) openLink(t.link) else editing = t },
                            onLong = { editing = t },
                            onPin = { vm.togglePin(t) },
                        )
                    }
                }
            }
        }
        androidx.compose.material3.FloatingActionButton(
            onClick = { editing = Todo(id = "", due = day.toString()) },
            containerColor = c.accent, contentColor = c.bg,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
        ) { Icon(Icons.Outlined.Add, "在这天新建") }
    }

    editing?.let { t -> TodoEditor(t, vm, onClose = { editing = null }, onDelete = { editing = null; vm.delete(t) }) }
}

@Composable
private fun MonthGrid(month: YearMonth, selected: LocalDate, today: LocalDate, marks: Map<LocalDate, Pair<Int, Int>>, pick: (LocalDate) -> Unit) {
    val c = T.c
    val lead = month.atDay(1).dayOfWeek.value - 1 // 周一开头
    val cells = lead + month.lengthOfMonth()
    val rows = (cells + 6) / 7
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val n = r * 7 + col - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..month.lengthOfMonth()) {
                            val d = month.atDay(n)
                            val isSel = d == selected
                            val isToday = d == today
                            val mark = marks[d]
                            Column(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                                    .background(
                                        when {
                                            isSel -> c.accent
                                            isToday -> c.accentSoft
                                            else -> Color.Transparent
                                        },
                                    )
                                    .clickable { pick(d) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    "$n",
                                    color = when {
                                        isSel -> c.bg
                                        isToday -> c.accent
                                        d.isBefore(today) -> c.ink3
                                        else -> c.ink
                                    },
                                    fontSize = 15.sp,
                                    fontWeight = if (isSel || isToday) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                // 小点：还有没做完的是实心，做完了的是淡色
                                Box(
                                    Modifier.padding(top = 3.dp).size(5.dp).clip(CircleShape).background(
                                        when {
                                            mark == null -> Color.Transparent
                                            isSel -> c.bg.copy(alpha = if (mark.first > 0) 1f else 0.5f)
                                            mark.first > 0 && d.isBefore(today) -> c.down
                                            mark.first > 0 -> c.accent
                                            else -> c.line
                                        },
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
