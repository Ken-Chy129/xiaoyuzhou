package cn.kenchy.me.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.withLink
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.ui.Mono
import cn.kenchy.me.ui.Palette
import cn.kenchy.me.ui.T

/**
 * Codex 的回复是 Markdown。手机上只做够用的渲染：标题、列表、代码块、表格、行内代码、加粗、
 * 链接（[文字](url) 和裸露的 http 地址都能点）、图片（![说明](路径)，本机文件经服务器从那台机器读）。
 */
@Composable
fun Markdownish(src: String, machine: String? = null) {
    val c = T.c
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks(src).forEach { b ->
            when (b) {
                is Block.Code -> Text(
                    b.text,
                    style = Mono.copy(color = c.ink2),
                    softWrap = false,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.line2)
                        .horizontalScroll(rememberScrollState()).padding(10.dp),
                )
                is Block.Heading -> Text(inline(b.text, c), color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp))
                is Block.Bullet -> Row(Modifier.padding(start = (b.indent * 12).dp)) {
                    Text(b.marker, color = c.ink3, fontSize = 15.sp, modifier = Modifier.padding(end = 6.dp))
                    Text(inline(b.text, c), color = c.ink, fontSize = 15.sp, lineHeight = 23.sp)
                }
                is Block.Table -> TableView(b)
                is Block.Image -> MdImage(b, machine)
                is Block.Para -> Text(inline(b.text, c), color = c.ink, fontSize = 15.sp, lineHeight = 23.sp)
            }
        }
    }
}

/** 图片：http(s) / data: 直接取；以 / 开头的是 Codex 那台机器上的文件 */
@Composable
private fun MdImage(b: Block.Image, machine: String?) {
    val c = T.c
    val img = remember(b.src, machine) {
        when {
            b.src.startsWith("data:image/") -> cn.kenchy.me.ui.ViewImage.DataUrl(b.src)
            b.src.startsWith("/") && machine != null ->
                cn.kenchy.me.ui.ViewImage.Remote("/api/codex/machines/${cn.kenchy.me.ui.enc(machine)}/image?path=" + java.net.URLEncoder.encode(b.src, "UTF-8"))
            else -> null
        }
    }
    if (img == null) {
        // 取不到的（比如外网图片）：至少给个能点的链接
        Text(inline("[${b.alt.ifBlank { "图片" }}](${b.src})", c), color = c.ink, fontSize = 15.sp)
        return
    }
    var viewing by remember { mutableStateOf(false) }
    if (viewing) cn.kenchy.me.ui.ImageViewer(listOf(img), 0) { viewing = false }
    Column {
        cn.kenchy.me.ui.Thumb(img, 180.dp, onClick = { viewing = true })
        if (b.alt.isNotBlank()) Text(b.alt, color = c.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

private sealed interface Block {
    data class Code(val text: String) : Block
    data class Heading(val text: String) : Block
    data class Bullet(val marker: String, val text: String, val indent: Int) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data class Image(val alt: String, val src: String) : Block
    data class Para(val text: String) : Block
}

private fun blocks(src: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Para(para.toString().trim())
        para.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) code.appendLine(lines[i++])
                out += Block.Code(code.toString().trimEnd())
            }
            t.startsWith("#") -> { flush(); out += Block.Heading(t.trimStart('#').trim()) }
            IMAGE_LINE.matches(t) -> {
                flush()
                val m = IMAGE_LINE.find(t)!!
                out += Block.Image(m.groupValues[1], m.groupValues[2].trim().removeSurrounding("<", ">"))
            }
            Regex("""^\s*([-*•]|\d+[.)])\s+""").containsMatchIn(line) -> {
                flush()
                val m = Regex("""^(\s*)([-*•]|\d+[.)])\s+(.*)$""").find(line)!!
                val marker = m.groupValues[2].let { if (it in listOf("-", "*", "•")) "•" else it }
                out += Block.Bullet(marker, m.groupValues[3], m.groupValues[1].length / 2)
            }
            t.startsWith("|") -> {
                flush()
                val rows = mutableListOf<List<String>>()
                var sawSep = false
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val r = lines[i].trim()
                    if (Regex("""^\|[\s:|-]+\|?$""").matches(r)) sawSep = true
                    else rows += splitRow(r)
                    i++
                }
                if (rows.isNotEmpty()) {
                    val width = rows.maxOf { it.size }
                    val norm = rows.map { it + List(width - it.size) { "" } }
                    out += if (sawSep) Block.Table(norm.first(), norm.drop(1)) else Block.Table(emptyList(), norm)
                }
                continue
            }
            t.isEmpty() -> flush()
            else -> para.append(if (para.isEmpty()) t else "\n$t")
        }
        i++
    }
    flush()
    return out
}

/** 按 | 切单元格，`code` 里和 \| 转义的竖线不切 */
private fun splitRow(line: String): List<String> {
    val s = line.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var inCode = false
    var k = 0
    while (k < s.length) {
        val ch = s[k]
        when {
            ch == '\\' && k + 1 < s.length && s[k + 1] == '|' -> { cur.append('|'); k++ }
            ch == '`' -> { inCode = !inCode; cur.append(ch) }
            ch == '|' && !inCode -> { cells += cur.toString().trim(); cur.clear() }
            else -> cur.append(ch)
        }
        k++
    }
    cells += cur.toString().trim()
    return cells
}

/**
 * 表格：表头加粗带底色、单元格自动换行；列多时整体横向滑动。
 * 列宽按内容长短分配（每列 88~220dp），短列不浪费、长列不挤成一条。
 */
@Composable
private fun TableView(t: Block.Table) {
    val c = T.c
    val cols = maxOf(t.header.size, t.rows.firstOrNull()?.size ?: 0)
    if (cols == 0) return
    val all = listOf(t.header) + t.rows
    val widths = (0 until cols).map { col ->
        // 中文按 1、英文按 0.55 估算显示宽度
        val len = all.maxOf { r -> r.getOrNull(col).orEmpty().sumOf { ch -> if (ch.code > 0x2E80) 1.0 else 0.55 } }
        (len * 15 + 24).coerceIn(88.0, 220.0).dp
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, c.line, shape).horizontalScroll(rememberScrollState()),
    ) {
        if (t.header.isNotEmpty()) {
            Row(Modifier.background(c.line2).height(IntrinsicSize.Min)) {
                t.header.forEachIndexed { i, h ->
                    Cell(h, widths[i], header = true, last = i == cols - 1)
                }
            }
        }
        t.rows.forEachIndexed { ri, r ->
            if (ri > 0 || t.header.isNotEmpty()) Box(Modifier.width(widths.fold(0.dp) { a, b -> a + b }).height(1.dp).background(c.line))
            Row(Modifier.height(IntrinsicSize.Min)) {
                r.forEachIndexed { i, cell -> Cell(cell, widths[i], header = false, last = i == cols - 1) }
            }
        }
    }
}

@Composable
private fun Cell(text: String, width: androidx.compose.ui.unit.Dp, header: Boolean, last: Boolean) {
    val c = T.c
    Row(Modifier.width(width).fillMaxHeight()) {
        Text(
            inline(text, c),
            color = if (header) c.ink else c.ink2,
            fontSize = 13.sp, lineHeight = 19.sp,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f).padding(10.dp, 8.dp),
        )
        if (!last) Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
    }
}

private val IMAGE_LINE = Regex("""^!\[([^\]]*)]\(([^)\s]+(?:\s+"[^"]*")?)\)$""")

// **bold**；`code`；![图](src)（行内的退化成链接）；[text](url)；裸 http(s) 地址
private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|!?\[([^\]]+)]\(([^)\s]+)\)|(https?://[^\s<>()\[\]，。；、）」]+)""")


private fun inline(s: String, c: Palette): AnnotatedString = buildAnnotatedString {
    val linkStyle = androidx.compose.ui.text.TextLinkStyles(
        style = SpanStyle(color = c.accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        pressedStyle = SpanStyle(color = c.accent, background = c.accentSoft),
    )
    var last = 0
    for (m in INLINE.findAll(s)) {
        append(s.substring(last, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.groupValues[1]) }
            m.groups[2] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.line2, fontSize = 13.sp)) { append(m.groupValues[2]) }
            m.groups[3] != null -> {
                val url = m.groupValues[4]
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    withLink(androidx.compose.ui.text.LinkAnnotation.Url(url, linkStyle)) { append(m.groupValues[3]) }
                } else withStyle(SpanStyle(color = c.accent)) { append(m.groupValues[3]) } // 本地路径：点了也打不开，只做强调
            }
            else -> {
                val url = m.groupValues[5].trimEnd('.', ',', ':', ';', '!', '?', '"', '\'')
                withLink(androidx.compose.ui.text.LinkAnnotation.Url(url, linkStyle)) { append(url) }
                append(m.groupValues[5].substring(url.length))
            }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}
