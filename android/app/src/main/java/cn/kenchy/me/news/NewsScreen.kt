package cn.kenchy.me.news

import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.kenchy.me.MeApp
import cn.kenchy.me.core.Daily
import cn.kenchy.me.core.Hot
import cn.kenchy.me.core.NewsItem
import cn.kenchy.me.core.NewsArticle
import cn.kenchy.me.core.isoAgo
import cn.kenchy.me.ui.Card
import cn.kenchy.me.ui.ErrorBox
import cn.kenchy.me.ui.Gap
import cn.kenchy.me.ui.HGap
import cn.kenchy.me.ui.Loading
import cn.kenchy.me.ui.Num
import cn.kenchy.me.ui.Segmented
import cn.kenchy.me.ui.T
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NewsVm(private val api: cn.kenchy.me.core.Api, autoload: Boolean = true) : ViewModel() {
    constructor() : this(MeApp.instance.api)

    val daily = MutableStateFlow<Daily?>(null)
    val hot = MutableStateFlow<Hot?>(null)
    val error = MutableStateFlow<String?>(null)

    /** 正在切去的日期（还没加载完时日期栏先显示它） */
    val pending = MutableStateFlow<String?>(null)
    /** 切换方向：1 往后一天，-1 往前一天，用于转场方向 */
    var direction = 0
        private set
    private val days = java.util.concurrent.ConcurrentHashMap<String, Daily>()
    private val articles = java.util.concurrent.ConcurrentHashMap<String, NewsArticle>()

    // 放在所有字段之后：init 里就会开始加载，用到的字段必须已经初始化
    init {
        if (autoload) {
            load(null)
            loadHot()
        }
    }

    fun load(date: String?) = viewModelScope.launch {
        val cur = daily.value?.date
        direction = if (date != null && cur != null) date.compareTo(cur).coerceIn(-1, 1) else 0
        // 看过的日子直接出，后台再刷新
        date?.let { days[it] }?.let { daily.value = it }
        pending.value = date
        runCatching { api.get("/api/news/daily" + (date?.let { "?date=$it" } ?: ""), Daily.serializer()) }
            .onSuccess {
                if (pending.value == date) {
                    daily.value = it
                    error.value = null
                    pending.value = null
                }
                days[it.date] = it
                prefetch(it.date)
            }
            .onFailure { if (pending.value == date) { error.value = it.message; pending.value = null } }
    }

    /** 预取前一天，往回翻时基本是秒开 */
    private fun prefetch(date: String) = viewModelScope.launch {
        val prev = runCatching { java.time.LocalDate.parse(date).minusDays(1).toString() }.getOrNull() ?: return@launch
        if (days.containsKey(prev)) return@launch
        runCatching { api.get("/api/news/daily?date=$prev", Daily.serializer()) }.onSuccess { days[prev] = it }
    }

    fun loadHot() = viewModelScope.launch {
        runCatching { api.get("/api/news/hot", Hot.serializer()) }.onSuccess { hot.value = it }
    }

    fun cachedArticle(id: String): NewsArticle? = articles[id]

    suspend fun article(id: String): Result<NewsArticle> =
        runCatching { api.get("/api/news/items/$id", NewsArticle.serializer()) }.onSuccess { articles[id] = it }

    /** 列表里已有的信息，详情页先拿来铺首屏 */
    fun preview(id: String): NewsItem? {
        val d = daily.value
        d?.lead?.takeIf { it.id == id }?.let { return NewsItem(id = id, title = it.title, summary = it.summary, url = it.url.orEmpty()) }
        return d?.sections?.flatMap { it.items }?.firstOrNull { it.id == id }
            ?: d?.flashes?.firstOrNull { it.id == id }
            ?: hot.value?.items?.firstOrNull { it.id == id }
    }
}

private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 「X：Arena (@arena)」→「Arena」，「The Decoder：AI News（RSS）」→「The Decoder」 */
fun cleanSource(s: String): String {
    var t = s.trim()
    if (t.startsWith("X：")) t = t.removePrefix("X：").replace(Regex("\\s*\\(@[^)]*\\)"), "")
    else t = t.substringBefore("：").substringBefore("·")
    return t.replace(Regex("（[^）]*）"), "").trim()
}

private fun hhmm(iso: String?): String =
    runCatching { DateTimeFormatter.ofPattern("HH:mm").format(Instant.parse(iso).atZone(zone)) }.getOrDefault("")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(vm: NewsVm, open: (String) -> Unit, top: @Composable () -> Unit = {}) {
    val daily by vm.daily.collectAsState()
    val hot by vm.hot.collectAsState()
    val error by vm.error.collectAsState()
    val pending by vm.pending.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val c = T.c
    fun read(n: NewsItem) {
        if (n.id.isNotBlank()) open(n.id) else openInBrowser(ctx, n.url.ifBlank { n.aihotUrl }, c)
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            (if (tab == 0) vm.load(daily?.date) else vm.loadHot()).invokeOnCompletion { refreshing = false }
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 32.dp),
        ) {
            item {
                Header(
                    tab = tab,
                    onTab = { tab = it },
                    date = pending ?: daily?.date,
                    onDate = { vm.load(it) },
                )
                // 加载中：日期栏下一条细进度条，旧内容淡下去但保留，不闪白
                val loading = tab == 0 && pending != null && daily != null && pending != daily?.date
                androidx.compose.animation.AnimatedVisibility(loading, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                    androidx.compose.material3.LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp).height(2.dp).clip(RoundedCornerShape(1.dp)),
                        color = c.accent, trackColor = c.line2,
                    )
                }
            }
            item { top() }
            if (tab == 0) {
                val d0 = daily
                if (d0 == null) {
                    item { if (error != null) ErrorBox(error!!, { vm.load(null) }) else DailySkeleton() }
                } else item(key = "daily") {
                    val dim by androidx.compose.animation.core.animateFloatAsState(
                        if (pending != null && pending != d0.date) 0.35f else 1f, androidx.compose.animation.core.tween(200), label = "dim",
                    )
                    androidx.compose.animation.AnimatedContent(
                        d0,
                        contentKey = { it.date },
                        transitionSpec = {
                            val dir = vm.direction
                            val ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
                            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260, delayMillis = 60)) +
                                androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(320, easing = ease)) { w -> dir * w / 6 })
                                .togetherWith(
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
                                        androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(320, easing = ease)) { w -> -dir * w / 8 },
                                )
                                .using(androidx.compose.animation.SizeTransform(clip = false))
                        },
                        label = "daily",
                        modifier = Modifier.graphicsLayer { alpha = dim },
                    ) { d -> Column {
                    d.lead?.let { lead ->
                        Lead(lead.title, lead.summary) { lead.id?.let(open) ?: openInBrowser(ctx, lead.url ?: d.url.orEmpty(), c) }
                    }
                    d.sections.forEach { s ->
                        SectionHead(s.label, s.items.size)
                        Card(padding = PaddingValues(0.dp)) {
                            s.items.forEachIndexed { i, n ->
                                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = c.line2)
                                NewsRow(n) { read(n) }
                            }
                        }
                    }
                    if (d.flashes.isNotEmpty()) {
                        SectionHead("快讯", d.flashes.size)
                        Card(padding = PaddingValues(0.dp, 6.dp)) {
                            d.flashes.forEach { f -> FlashRow(f) { read(f) } }
                        }
                    }
                    if (d.sections.isEmpty() && d.flashes.isEmpty() && d.lead == null) {
                        run { Text("这一天还没有日报", color = c.ink3, fontSize = 14.sp, modifier = Modifier.padding(vertical = 40.dp).fillMaxWidth(), textAlign = TextAlign.Center) }
                    }
                    Footer(d.attribution)
                    } }
                }
            } else {
                val h = hot
                if (h == null) item { Box(Modifier.padding(48.dp)) { Loading() } }
                else {
                    item {
                        Text("过去 24 小时被最多来源报道的事", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    item {
                        Card(padding = PaddingValues(0.dp)) {
                            h.items.forEachIndexed { i, n ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), color = c.line2)
                                HotRow(i + 1, n) { read(n) }
                            }
                        }
                    }
                    item { Footer(h.attribution) }
                }
            }
        }
    }
}

/** 首次进入还没有数据时的占位骨架，带呼吸感 */
@Composable
private fun DailySkeleton() {
    val c = T.c
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.45f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Column(Modifier.fillMaxWidth().graphicsLayer { alpha = a }) {
        Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp)).background(c.line2))
        repeat(2) {
            Gap(24.dp)
            Box(Modifier.size(width = 80.dp, height = 16.dp).clip(RoundedCornerShape(4.dp)).background(c.line2))
            Gap(12.dp)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(0.9f, 1f, 0.7f, 0f, 0.85f, 1f, 0.6f).forEach { w ->
                    if (w == 0f) Gap(6.dp) else Box(Modifier.fillMaxWidth(w).height(13.dp).clip(RoundedCornerShape(4.dp)).background(c.line2))
                }
            }
        }
    }
}

@Composable
private fun Header(tab: Int, onTab: (Int) -> Unit, date: String?, onDate: (String) -> Unit) {
    val c = T.c
    val today = LocalDate.now(zone)
    val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("资讯", style = MaterialTheme.typography.headlineMedium, color = c.ink, modifier = Modifier.weight(1f))
            Segmented(listOf("日报", "热点"), tab, onTab)
        }
        if (tab == 0) {
            Gap(6.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val label = when (d) {
                    today -> "今天"
                    today.minusDays(1) -> "昨天"
                    else -> ""
                }
                Text(
                    "${d.monthValue}月${d.dayOfMonth}日 ${weekdays[d.dayOfWeek.value - 1]}" + (if (label.isNotEmpty()) " · $label" else ""),
                    color = c.ink2, fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
                DateArrow("‹", true) { onDate(d.minusDays(1).toString()) }
                HGap(4.dp)
                DateArrow("›", d.isBefore(today)) { onDate(d.plusDays(1).toString()) }
            }
        }
    }
}

@Composable
private fun DateArrow(text: String, enabled: Boolean, onClick: () -> Unit) {
    val c = T.c
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(50)).background(c.line2).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) c.ink else c.line, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Lead(title: String, summary: String, onClick: () -> Unit) {
    val c = T.c
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.accent).clickable(onClick = onClick).padding(20.dp),
    ) {
        Text("今日导读", color = c.bg.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp)
        Gap(8.dp)
        Text(title, color = c.bg, fontSize = 20.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold)
        if (summary.isNotBlank()) {
            Gap(10.dp)
            Text(summary, color = c.bg.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 23.sp)
        }
    }
}

@Composable
private fun SectionHead(label: String, count: Int) {
    val c = T.c
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
        HGap(8.dp)
        Text(label, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        HGap(6.dp)
        Text("$count", color = c.ink3, fontSize = 13.sp)
    }
}

@Composable
private fun NewsRow(n: NewsItem, onClick: () -> Unit) {
    val c = T.c
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 14.dp)) {
        Text(n.title, color = c.ink, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
        if (n.summary.isNotBlank()) {
            Gap(6.dp)
            Text(n.summary, color = c.ink2, fontSize = 14.sp, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        val meta = listOf(cleanSource(n.source), isoAgo(n.publishedAt)).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) {
            Gap(8.dp)
            Text(meta, color = c.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FlashRow(n: NewsItem, onClick: () -> Unit) {
    val c = T.c
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 10.dp)) {
        Text(hhmm(n.publishedAt), style = Num.copy(fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.Medium), modifier = Modifier.width(48.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(n.title, color = c.ink, fontSize = 15.sp, lineHeight = 23.sp)
            val src = cleanSource(n.source)
            if (src.isNotBlank()) Text(src, color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun HotRow(rank: Int, n: NewsItem, onClick: () -> Unit) {
    val c = T.c
    val top = rank <= 3
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 14.dp)) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(if (top) c.salary else c.line2),
            contentAlignment = Alignment.Center,
        ) {
            Text("$rank", style = Num.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (top) c.surface else c.ink3))
        }
        HGap(14.dp)
        Column(Modifier.weight(1f)) {
            Text(n.title, color = c.ink, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = if (top) FontWeight.SemiBold else FontWeight.Medium)
            Gap(6.dp)
            val names = n.sources.take(2).map(::cleanSource).filter { it.isNotBlank() }.joinToString("、")
            Text(
                listOf(
                    if (n.sourceCount > 0) "${n.sourceCount} 个来源" else "",
                    names,
                    isoAgo(n.latestAt ?: n.publishedAt),
                ).filter { it.isNotBlank() }.joinToString(" · "),
                color = c.ink3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Footer(text: String) {
    Text(text, color = T.c.ink3, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
}
