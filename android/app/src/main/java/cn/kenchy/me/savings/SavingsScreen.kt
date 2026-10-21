package cn.kenchy.me.savings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.core.money
import cn.kenchy.me.core.monthLabel
import cn.kenchy.me.core.monthShort
import cn.kenchy.me.core.pct
import cn.kenchy.me.ui.Bar
import cn.kenchy.me.ui.Card
import cn.kenchy.me.ui.ErrorBox
import cn.kenchy.me.ui.Gap
import cn.kenchy.me.ui.HGap
import cn.kenchy.me.ui.KV
import cn.kenchy.me.ui.Label
import cn.kenchy.me.ui.Loading
import cn.kenchy.me.ui.MoneyText
import cn.kenchy.me.ui.Pill
import cn.kenchy.me.ui.Segmented
import cn.kenchy.me.ui.T

private enum class Metric(val label: String) { Saved("存下"), Salary("工资收入"), TakeHome("到手"), Other("附加收入") }

@Composable
fun SavingsScreen(
    vm: SavingsVm,
    edit: (String?) -> Unit,
    back: (() -> Unit)? = null,
) {
    val data by vm.data.collectAsState()
    val all by vm.rows.collectAsState()
    val error by vm.error.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var fiscal by rememberSaveable { mutableStateOf(true) }
    var periodKey by rememberSaveable { mutableStateOf("all") } // 默认看全部
    var metric by rememberSaveable { mutableStateOf(Metric.Saved) }
    var pickedTrend by remember { mutableStateOf<Int?>(null) }
    var pickedBar by remember { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (data == null) {
            if (error != null) ErrorBox(error!!, { vm.refresh() }) else Loading()
            return@Box
        }
        val settings = data!!.settings
        val ps = periods(all, fiscal)
        val rows = if (periodKey == "all") all else all.filter { periodOf(it.month, fiscal).key == periodKey }
        val sum = summarize(rows)
        val prev = if (periodKey == "all") null else samePeriodLastYear(all, rows)?.let(::summarize)
        val balance = all.lastOrNull()?.balance ?: 0.0
        val projection = projectGoal(all, settings.goal)

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refreshing = true; vm.refresh().invokeOnCompletion { refreshing = false } },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (back != null) {
                            IconButton(onClick = back, modifier = Modifier.padding(end = 4.dp)) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = T.c.ink)
                            }
                        }
                        Text("储蓄", style = MaterialTheme.typography.headlineMedium, color = T.c.ink, modifier = Modifier.weight(1f))
                    }
                }
                // 当前存款：最关注的数字
                item {
                    Card {
                        val pick = pickedTrend?.let { all.getOrNull(it) }
                        val shown = pick ?: all.lastOrNull()
                        Label(if (pick != null) "${monthLabel(pick.month)} 累计" else "当前存款")
                        MoneyText(money(pick?.balance ?: balance), size = 32, weight = FontWeight.SemiBold)
                        // 拆分：定存 / 个人 / 公积金，三等分两行（标签在上、金额在下），一行挤不下也不会截断
                        if (shown != null) {
                            Gap(10.dp)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Part("定存", shown.balanceFixed, T.c.accent, Modifier.weight(1f))
                                Part("个人", shown.balancePersonal, T.c.salary, Modifier.weight(1f))
                                Part("公积金", shown.fundBalance, T.c.fund, Modifier.weight(1f))
                            }
                            Gap(10.dp)
                        }
                        if (pick != null) {
                            Row {
                                Text("当月存下 ", color = T.c.ink3, fontSize = 12.sp)
                                MoneyText(money(pick.saved, sign = true), size = 12, color = T.c.up)
                            }
                        } else if (settings.goal > 0) {
                            Text("目标 ${money(settings.goal)} · 已完成 ${pct(balance / settings.goal, 0)}", color = T.c.ink3, fontSize = 12.sp)
                        }
                        Gap(12.dp)
                        AreaChart(all.map { it.balance }, pickedTrend, { pickedTrend = it })
                        Gap(12.dp)
                        Bar((balance / settings.goal).toFloat(), T.c.accent)
                        Gap(8.dp)
                        projection?.let { p ->
                            Text(
                                when {
                                    balance >= settings.goal -> "已经达成目标"
                                    p.month == null -> "近 12 个月没有净增长，暂时推算不出达成时间"
                                    else -> "按近 12 个月平均每月 ${money(p.monthly)} 的速度，预计 ${monthLabel(p.month)} 达成"
                                },
                                color = T.c.ink2, fontSize = 13.sp, lineHeight = 19.sp,
                            )
                        }
                    }
                }
                // 时间范围
                item {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("统计范围", color = T.c.ink3, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Segmented(listOf("年度", "自然年"), if (fiscal) 0 else 1, { fiscal = it == 0; periodKey = "all"; pickedBar = null })
                        }
                        Gap(8.dp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("全部", periodKey == "all") { periodKey = "all"; pickedBar = null }
                            ps.reversed().forEach { p -> Chip(p.label, periodKey == p.key) { periodKey = p.key; pickedBar = null } }
                        }
                    }
                }
                // 关键指标
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Kpi("工资收入", money(sum.salary), prev?.let { delta(sum.salary, it.salary) }, Modifier.weight(1f))
                            Kpi("附加收入", money(sum.other), "${sum.otherMonths} 个月有", Modifier.weight(1f), plainSub = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Kpi("到手", money(sum.takeHome), prev?.let { delta(sum.takeHome, it.takeHome) }, Modifier.weight(1f))
                            Kpi(
                                "储蓄率", pct(sum.rate), prev?.rate?.let { pr -> sum.rate?.let { "%+.1f 个百分点".format((it - pr) * 100) } },
                                Modifier.weight(1f), isMoney = false,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Kpi("存下", money(sum.saved), prev?.let { delta(sum.saved, it.saved) }, Modifier.weight(1f))
                            Kpi("月均存下", money(sum.avgSaved), "不含特殊月", Modifier.weight(1f), plainSub = true)
                        }
                    }
                }
                // 按月
                item {
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("每月", style = MaterialTheme.typography.titleMedium, color = T.c.ink, modifier = Modifier.weight(1f))
                        }
                        Gap(8.dp)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Metric.entries.forEach { m -> Chip(m.label, metric == m) { metric = m } }
                        }
                        Gap(12.dp)
                        val values = rows.map { metricOf(it, metric) }
                        val pick = pickedBar?.let { rows.getOrNull(it) }
                        if (pick != null) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(monthLabel(pick.month), color = T.c.ink2, fontSize = 13.sp)
                                HGap(8.dp)
                                MoneyText(money(metricOf(pick, metric)), size = 18, weight = FontWeight.SemiBold)
                                if (pick.e.special) { HGap(8.dp); Pill("特殊月", T.c.warn, T.c.warnSoft) }
                            }
                        } else {
                            val normal = rows.filter { !it.e.special }
                            val avg = if (normal.isEmpty()) 0.0 else normal.sumOf { metricOf(it, metric) } / normal.size
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("月均", color = T.c.ink2, fontSize = 13.sp)
                                HGap(8.dp)
                                MoneyText(money(avg), size = 18, weight = FontWeight.SemiBold)
                                HGap(8.dp)
                                Text("点柱子看单月", color = T.c.ink3, fontSize = 12.sp)
                            }
                        }
                        Gap(10.dp)
                        val normal = rows.filter { !it.e.special }
                        MonthBars(
                            values = values,
                            labels = rows.map { monthShort(it.month) },
                            special = rows.map { it.e.special },
                            selected = pickedBar,
                            onSelect = { pickedBar = it },
                            average = if (normal.isEmpty()) null else normal.sumOf { metricOf(it, metric) } / normal.size,
                            color = when (metric) {
                                Metric.Saved -> T.c.accent
                                Metric.Salary -> T.c.salary
                                Metric.TakeHome -> T.c.fund
                                Metric.Other -> T.c.cost
                            },
                        )
                        pick?.let {
                            Gap(8.dp)
                            Text("查看 / 编辑这个月 ›", color = T.c.accent, fontSize = 13.sp, modifier = Modifier.clickable { edit(it.month) }.padding(vertical = 4.dp))
                        }
                    }
                }
                // 钱去哪了：收入拆分
                item {
                    Card {
                        Text("收入去向", style = MaterialTheme.typography.titleMedium, color = T.c.ink)
                        Gap(4.dp)
                        Text("工资收入 + 附加收入 = ${money(sum.salary + sum.other)}", color = T.c.ink3, fontSize = 12.sp)
                        Gap(10.dp)
                        val gross = (sum.salary + sum.other).coerceAtLeast(1.0)
                        val spent = sum.disposable - sum.saved
                        Flow(
                            listOf(
                                Triple("存下", sum.saved, T.c.accent),
                                Triple("五险一金", sum.insurance, T.c.fund),
                                Triple("个税", sum.tax, T.c.salary),
                                Triple("家庭补贴", sum.family, T.c.cost),
                                Triple("其余花销", spent, T.c.line),
                            ),
                            gross,
                        )
                    }
                }
                // 年度对比
                if (periodKey == "all" && ps.size > 1) item {
                    Card {
                        Text(if (fiscal) "各年度" else "各自然年", style = MaterialTheme.typography.titleMedium, color = T.c.ink)
                        Gap(8.dp)
                        val maxSaved = ps.maxOf { p -> summarize(all.filter { periodOf(it.month, fiscal).key == p.key }).saved }.coerceAtLeast(1.0)
                        ps.reversed().forEach { p ->
                            val s = summarize(all.filter { periodOf(it.month, fiscal).key == p.key })
                            Column(Modifier.fillMaxWidth().clickable { periodKey = p.key }.padding(vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.label, color = T.c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    HGap(6.dp)
                                    Text("${s.months} 个月", color = T.c.ink3, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    MoneyText(money(s.saved), size = 14)
                                    HGap(8.dp)
                                    Text(pct(s.rate, 0), style = cn.kenchy.me.ui.Num.copy(color = T.c.ink2, fontSize = 13.sp))
                                }
                                Gap(6.dp)
                                Bar((s.saved / maxSaved).toFloat(), T.c.accent, height = 5.dp)
                            }
                        }
                    }
                }
                // 月度明细
                item { Text("明细", style = MaterialTheme.typography.titleMedium, color = T.c.ink, modifier = Modifier.padding(top = 4.dp)) }
                items(rows.reversed(), key = { it.e.id }) { r -> MonthRow(r) { edit(r.month) } }
            }
        }
        FloatingActionButton(
            onClick = { edit(null) },
            containerColor = T.c.accent,
            contentColor = T.c.bg,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Outlined.Add, "记一个月") }
    }
}

private fun metricOf(r: Row, m: Metric) = when (m) {
    Metric.Saved -> r.saved
    Metric.Salary -> r.e.salary
    Metric.TakeHome -> r.takeHome
    Metric.Other -> r.e.other
}

private fun delta(now: Double, before: Double): String? =
    if (before <= 0) null else "%+.0f%% 同比".format((now - before) / before * 100)

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(if (on) T.c.accent else T.c.line2).clickable(onClick = onClick).padding(12.dp, 5.dp),
        color = if (on) T.c.bg else T.c.ink2,
        fontSize = 13.sp,
    )
}

@Composable
private fun Part(label: String, value: Double, color: Color, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
            HGap(5.dp)
            Text(label, color = T.c.ink3, fontSize = 12.sp, maxLines = 1)
        }
        Gap(2.dp)
        MoneyText(money(value), size = 15, color = T.c.ink, weight = FontWeight.Medium)
    }
}

@Composable
private fun Kpi(label: String, value: String, sub: String?, modifier: Modifier, isMoney: Boolean = true, plainSub: Boolean = false) {
    Card(modifier, padding = PaddingValues(14.dp)) {
        Label(label)
        Gap(2.dp)
        if (isMoney) MoneyText(value, size = 19, weight = FontWeight.SemiBold)
        else Text(value, style = cn.kenchy.me.ui.Num.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = T.c.ink))
        Gap(2.dp)
        val color = when {
            plainSub || sub == null -> T.c.ink3
            sub.startsWith("+") -> T.c.up
            sub.startsWith("-") -> T.c.down
            else -> T.c.ink3
        }
        Text(sub ?: " ", color = color, fontSize = 12.sp, maxLines = 1)
    }
}

/** 一条横向分段条 + 图例 */
@Composable
private fun Flow(parts: List<Triple<String, Double, Color>>, total: Double) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))) {
        parts.forEach { (_, v, col) ->
            val w = (v / total).toFloat()
            if (w > 0.003f) Box(Modifier.weight(w).height(14.dp).background(col))
        }
    }
    Gap(10.dp)
    parts.forEach { (name, v, col) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            cn.kenchy.me.ui.Dot(col)
            HGap(8.dp)
            Text(name, color = T.c.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
            MoneyText(money(v), size = 13)
            HGap(10.dp)
            Text(pct(v / total, 0), style = cn.kenchy.me.ui.Num.copy(color = T.c.ink3, fontSize = 12.sp), modifier = Modifier.padding(start = 2.dp))
        }
    }
}

@Composable
private fun MonthRow(r: Row, onClick: () -> Unit) {
    Card(onClick = onClick, padding = PaddingValues(14.dp, 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(monthLabel(r.month), color = T.c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    if (r.e.special) { HGap(6.dp); Pill("特殊月", T.c.warn, T.c.warnSoft) }
                    if (r.e.bonus > 0) { HGap(6.dp); Pill("奖金", T.c.salary, T.c.line2) }
                }
                Gap(2.dp)
                Row {
                    Text("工资 ", color = T.c.ink3, fontSize = 12.sp)
                    MoneyText(money(r.e.salary), size = 12, color = T.c.ink3, weight = FontWeight.Normal)
                    Text("  到手 ", color = T.c.ink3, fontSize = 12.sp)
                    MoneyText(money(r.takeHome), size = 12, color = T.c.ink3, weight = FontWeight.Normal)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(money(r.saved, sign = true), size = 15, color = if (r.saved >= 0) T.c.up else T.c.down)
                Text(pct(r.rate, 0), style = cn.kenchy.me.ui.Num.copy(color = T.c.ink3, fontSize = 12.sp))
            }
        }
    }
}
