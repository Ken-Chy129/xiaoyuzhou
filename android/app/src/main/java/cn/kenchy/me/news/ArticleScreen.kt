package cn.kenchy.me.news

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.core.NewsArticle
import cn.kenchy.me.core.NewsItem
import cn.kenchy.me.ui.ErrorBox
import cn.kenchy.me.ui.Gap
import cn.kenchy.me.ui.HGap
import cn.kenchy.me.ui.NetImage
import cn.kenchy.me.ui.Palette
import cn.kenchy.me.ui.T
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val zone = ZoneId.of("Asia/Shanghai")

private fun fullTime(iso: String?): String = runCatching {
    val t = Instant.parse(iso).atZone(zone)
    val now = java.time.LocalDate.now(zone)
    val pattern = if (t.year == now.year) "M月d日 HH:mm" else "yyyy年M月d日"
    DateTimeFormatter.ofPattern(pattern).format(t)
}.getOrDefault("")

fun openInBrowser(ctx: android.content.Context, url: String, c: Palette) {
    if (url.isBlank()) return
    val colors = CustomTabColorSchemeParams.Builder().setToolbarColor(c.bg.toArgb()).build()
    CustomTabsIntent.Builder().setDefaultColorSchemeParams(colors).setShowTitle(true).build().launchUrl(ctx, Uri.parse(url))
}

private fun spansToText(spans: List<Span>, c: Palette): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.SemiBold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) c.line2 else androidx.compose.ui.graphics.Color.Unspecified,
            color = if (s.link != null) c.accent else androidx.compose.ui.graphics.Color.Unspecified,
            textDecoration = if (s.link != null) TextDecoration.Underline else null,
        )
        if (s.link != null) {
            pushStringAnnotation("url", s.link)
            withStyle(style) { append(s.text) }
            pop()
        } else if (!s.code) {
            // 正文里直接写出来的网址（没包 <a>）也能点
            var last = 0
            for (m in BARE_URL.findAll(s.text)) {
                withStyle(style) { append(s.text.substring(last, m.range.first)) }
                val url = m.value.trimEnd('.', ',', ':', ';', '!', '?')
                pushStringAnnotation("url", url)
                withStyle(style.copy(color = c.accent, textDecoration = TextDecoration.Underline)) { append(url) }
                pop()
                withStyle(style) { append(m.value.substring(url.length)) }
                last = m.range.last + 1
            }
            withStyle(style) { append(s.text.substring(last)) }
        } else withStyle(style) { append(s.text) }
    }
}

private val BARE_URL = Regex("""https?://[^\s<>()\[\]，。；、）」"]+""")

@Composable
private fun RichText(spans: List<Span>, style: TextStyle, onLink: (String) -> Unit) {
    val c = T.c
    val text = remember(spans, c) { spansToText(spans, c) }
    if (text.getStringAnnotations("url", 0, text.length).isEmpty()) {
        Text(text, style = style)
    } else {
        @Suppress("DEPRECATION")
        ClickableText(text, style = style) { off ->
            text.getStringAnnotations("url", off, off).firstOrNull()?.let { onLink(it.item) }
        }
    }
}

/**
 * 资讯详情：在 App 内直接读正文。
 * 先用列表里已有的标题/摘要立即铺出来，正文到了再补上。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArticleScreen(vm: NewsVm, id: String, back: () -> Unit) {
    val c = T.c
    val ctx = LocalContext.current
    val preview: NewsItem? = remember(id) { vm.preview(id) }
    var article by remember(id) { mutableStateOf(vm.cachedArticle(id)) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(id, attempt) {
        if (article == null) {
            error = null
            vm.article(id).onSuccess { article = it }.onFailure { error = it.message ?: "加载失败" }
        }
    }
    val a = article
    val blocks = remember(a?.html) { a?.html?.takeIf { it.isNotBlank() }?.let(::parseHtml).orEmpty() }
    val title = a?.title ?: preview?.title.orEmpty()
    val url = a?.url?.takeIf { it.isNotBlank() } ?: preview?.url?.ifBlank { preview.aihotUrl }.orEmpty()
    val list = rememberLazyListState()
    val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val progress by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val total = info.totalItemsCount
            if (total <= 1) 0f else {
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                (last + 1f) / total
            }
        }
    }
    fun share() {
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, "$title\n$url")
        }
        ctx.startActivity(android.content.Intent.createChooser(send, "分享"))
    }

    val body = TextStyle(color = c.ink, fontSize = 17.sp, lineHeight = 30.sp, letterSpacing = 0.2.sp)

    Column(Modifier.fillMaxSize().background(c.surface).statusBarsPadding()) {
        // 顶栏：滚下去后显示标题
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.ink) }
            Box(Modifier.weight(1f)) {
                val alpha by androidx.compose.animation.core.animateFloatAsState(if (scrolled) 1f else 0f, label = "title")
                Box(Modifier.alpha(alpha)) {
                    Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            if (url.isNotBlank()) {
                IconButton(onClick = ::share) { Icon(Icons.Outlined.Share, "分享", tint = c.ink2) }
            }
        }
        LinearProgressIndicator(
            progress = { if (a == null && error == null) 0f else progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = c.accent, trackColor = c.line2, drawStopIndicator = {},
        )

        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp, 18.dp, 22.dp, 48.dp)) {
            item {
                val tags = a?.tags.orEmpty()
                if (tags.isNotEmpty() || a?.translated == true) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (a?.translated == true) Chip("译文", c.accent, c.accentSoft)
                        tags.forEach { Chip(it, c.ink2, c.line2) }
                    }
                    Gap(14.dp)
                }
                Text(title, color = c.ink, fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
                a?.originalTitle?.let {
                    Gap(8.dp)
                    Text(it, color = c.ink3, fontSize = 13.sp, lineHeight = 19.sp)
                }
                Gap(14.dp)
                val source = cleanSource(a?.source ?: preview?.source.orEmpty())
                val meta = listOfNotNull(
                    source.takeIf { it.isNotBlank() },
                    a?.author?.takeIf { it.isNotBlank() && it != source },
                    fullTime(a?.publishedAt ?: preview?.publishedAt ?: preview?.latestAt).takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 3.dp, height = 14.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
                    HGap(8.dp)
                    Text(meta, color = c.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    val n = a?.sourceCount ?: preview?.sourceCount ?: 0
                    if (n > 1) Text("$n 个来源", color = c.ink3, fontSize = 12.sp)
                }
                val summary = a?.summary ?: preview?.summary.orEmpty()
                if (summary.isNotBlank()) {
                    Gap(20.dp)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.bg).padding(16.dp, 14.dp)) {
                        Text("摘要", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                        Gap(6.dp)
                        Text(summary, color = c.ink2, fontSize = 15.sp, lineHeight = 25.sp)
                    }
                }
                Gap(24.dp)
            }

            when {
                a == null && error != null -> item { ErrorBox(error!!, { attempt++ }) }
                a == null -> item { Skeleton() }
                else -> {
                    itemsIndexed(blocks) { _, b ->
                        when (b) {
                            is Block.Para -> Box(Modifier.padding(bottom = 18.dp)) { RichText(b.spans, body) { openInBrowser(ctx, it, c) } }
                            is Block.Heading -> Box(Modifier.padding(top = 10.dp, bottom = 12.dp)) {
                                RichText(b.spans, TextStyle(color = c.ink, fontSize = if (b.level == 2) 20.sp else 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)) { openInBrowser(ctx, it, c) }
                            }
                            is Block.Bullet -> Row(Modifier.padding(bottom = 10.dp, start = 4.dp)) {
                                Text(b.ordered?.let { "$it." } ?: "•", style = body.copy(color = c.accent, fontWeight = FontWeight.SemiBold), modifier = Modifier.width(22.dp))
                                Box(Modifier.weight(1f)) { RichText(b.spans, body) { openInBrowser(ctx, it, c) } }
                            }
                            is Block.Quote -> Row(Modifier.padding(bottom = 18.dp)) {
                                Box(Modifier.width(3.dp).height(24.dp).background(c.line))
                                HGap(12.dp)
                                Box(Modifier.weight(1f)) { RichText(b.spans, body.copy(color = c.ink2)) { openInBrowser(ctx, it, c) } }
                            }
                            is Block.Code -> CodeBlock(b.text)
                            is Block.Table -> TableBlock(b) { openInBrowser(ctx, it, c) }
                            Block.Rule -> Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(c.line2))
                            is Block.Image -> Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 22.dp)) {
                                NetImage(b.src, b.ratio, Modifier.clip(RoundedCornerShape(10.dp)))
                                if (b.caption.isNotBlank()) {
                                    Gap(8.dp)
                                    Text(b.caption, color = c.ink3, fontSize = 12.sp, lineHeight = 18.sp)
                                }
                            }
                        }
                    }
                    if (blocks.isEmpty()) item {
                        Text("这条没有可在 App 内阅读的正文，摘要已经在上面了。", color = c.ink3, fontSize = 14.sp, lineHeight = 22.sp)
                        Gap(12.dp)
                    }
                    if (!a.complete && blocks.isNotEmpty()) item {
                        Text("正文可能只是节选，完整内容见原文。", color = c.ink3, fontSize = 13.sp)
                        Gap(12.dp)
                    }
                }
            }

            item {
                Gap(12.dp)
                if (url.isNotBlank()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp))
                            .clickable { openInBrowser(ctx, url, c) }.padding(16.dp, 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("看原文", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text(Uri.parse(url).host.orEmpty().removePrefix("www."), color = c.ink3, fontSize = 12.sp)
                        }
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = c.ink3, modifier = Modifier.size(18.dp))
                    }
                }
                Gap(16.dp)
                HorizontalDivider(color = c.line2)
                Gap(10.dp)
                Text(a?.attribution ?: "内容来自 AIHOT（aihot.news），版权归原作者", color = c.ink3, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Chip(text: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
    Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(10.dp, 3.dp))
}

@Composable
private fun Skeleton() {
    val c = T.c
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(1f, 0.95f, 0.98f, 0.6f, 0f, 1f, 0.9f, 0.75f).forEach { w ->
            if (w == 0f) Gap(8.dp)
            else Box(Modifier.fillMaxWidth(w).height(14.dp).clip(RoundedCornerShape(4.dp)).background(c.line2))
        }
    }
}

/** 代码块：等宽字体、保留换行；长行横向滑动，不折行（目录树、命令行才能对齐） */
@Composable
private fun CodeBlock(text: String) {
    val c = T.c
    Box(
        Modifier.fillMaxWidth().padding(bottom = 18.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(12.dp)),
    ) {
        Text(
            text,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 19.sp, color = c.ink2),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp, 12.dp),
        )
    }
}

/**
 * 表格：列宽按内容估算（短列窄、长列宽，单列最宽 220dp），超出屏幕时整表横向滑动。
 * 表头加粗带底色，行与行之间细分隔线。
 */
@Composable
private fun TableBlock(t: Block.Table, onLink: (String) -> Unit) {
    val c = T.c
    val cols = t.rows.maxOf { it.size }
    val widths = remember(t) {
        (0 until cols).map { j ->
            val longest = t.rows.maxOf { r -> r.getOrNull(j).orEmpty().joinToString("") { it.text }.lines().maxOfOrNull(::visualLen) ?: 0 }
            (longest * 7.5f + 24f).coerceIn(64f, 220f).dp
        }
    }
    val cellStyle = TextStyle(color = c.ink, fontSize = 13.5.sp, lineHeight = 20.sp)
    Box(
        Modifier.fillMaxWidth().padding(bottom = 20.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, c.line, RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            t.rows.forEachIndexed { i, r ->
                val head = t.header && i == 0
                Row(
                    Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                        .background(if (head) c.line2 else androidx.compose.ui.graphics.Color.Transparent),
                ) {
                    r.forEachIndexed { j, cell ->
                        Box(Modifier.width(widths[j]).fillMaxHeight().padding(12.dp, 9.dp)) {
                            RichText(cell, if (head) cellStyle.copy(fontWeight = FontWeight.SemiBold, color = c.ink2, fontSize = 12.5.sp) else cellStyle, onLink)
                        }
                    }
                }
                if (i < t.rows.size - 1) Box(Modifier.width(widths.fold(0.dp) { a, w -> a + w }).height(1.dp).background(c.line2))
            }
        }
    }
}

/** 估算显示宽度：中日韩字符算 2 */
private fun visualLen(s: String) = s.sumOf { ch -> if (ch.code > 0x2E80) 2 else 1 as Int }
