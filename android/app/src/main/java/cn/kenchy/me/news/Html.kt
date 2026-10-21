package cn.kenchy.me.news

/**
 * 把 AIHOT 正文里那一小组标签（服务端已收敛过）拆成原生可排版的块。
 * 不依赖 WebView：段落、小标题、列表、引用、图片（带图注）。
 */
sealed interface Block {
    data class Para(val spans: List<Span>) : Block
    data class Heading(val level: Int, val spans: List<Span>) : Block
    data class Bullet(val spans: List<Span>, val ordered: Int? = null) : Block
    data class Quote(val spans: List<Span>) : Block
    data class Image(val src: String, val caption: String, val ratio: Float?) : Block
    /** 代码块：原样保留换行和缩进 */
    data class Code(val text: String) : Block
    /** 表格：第一行是否表头；每格是一组行内片段 */
    data class Table(val rows: List<List<List<Span>>>, val header: Boolean) : Block
    data object Rule : Block
}

/** 行内文本片段 */
data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val link: String? = null)

private val TOKEN = Regex("<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>|([^<]+)")
private val ATTR = Regex("([a-zA-Z-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

fun decodeEntities(s: String): String {
    if ('&' !in s) return s
    return ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> when (e) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                "nbsp" -> " "; "mdash" -> "—"; "ndash" -> "–"; "hellip" -> "…"
                "lsquo" -> "‘"; "rsquo" -> "’"; "ldquo" -> "“"; "rdquo" -> "”"
                else -> m.value
            }
        }
    }
}

private fun attrs(raw: String): Map<String, String> =
    ATTR.findAll(raw).associate { it.groupValues[1].lowercase() to decodeEntities(it.groupValues[3].ifEmpty { it.groupValues[4] }) }

private val INLINE = setOf("strong", "b", "em", "i", "code", "a")
private val SPACES = Regex("[ \\t\\r\\n]+")

fun parseHtml(html: String): List<Block> {
    val out = mutableListOf<Block>()
    var spans = mutableListOf<Span>()
    var kind = "p"
    var ordered: Int? = null
    var listCounter = 0
    val listStack = ArrayDeque<Boolean>() // true = ol
    var bold = 0
    var italic = 0
    var code = 0
    var link: String? = null
    var inFigure = false
    var caption: StringBuilder? = null
    var pendingImage: Block.Image? = null
    // 代码块：<pre> 里的文字原样收集
    var pre: StringBuilder? = null
    // 表格：行 → 格 → 片段
    var table: MutableList<MutableList<List<Span>>>? = null
    var row: MutableList<List<Span>>? = null
    var cell: MutableList<Span>? = null
    var tableHeader = false

    fun flush() {
        val merged = mutableListOf<Span>()
        for (s in spans) {
            val last = merged.lastOrNull()
            if (last != null && last.copy(text = "") == s.copy(text = "")) merged[merged.size - 1] = last.copy(text = last.text + s.text)
            else merged += s
        }
        if (merged.isNotEmpty()) {
            merged[0] = merged[0].copy(text = merged[0].text.trimStart())
            merged[merged.size - 1] = merged.last().copy(text = merged.last().text.trimEnd())
        }
        val clean = merged.filter { it.text.isNotEmpty() }
        if (clean.any { it.text.isNotBlank() }) {
            out += when (kind) {
                "h2" -> Block.Heading(2, clean)
                "h3" -> Block.Heading(3, clean)
                "li" -> Block.Bullet(clean, ordered)
                "blockquote" -> Block.Quote(clean)
                else -> Block.Para(clean)
            }
        }
        spans = mutableListOf()
    }

    fun flushImage() {
        pendingImage?.let { img -> out += img.copy(caption = caption?.toString()?.trim().orEmpty()) }
        pendingImage = null
        caption = null
    }

    for (m in TOKEN.findAll(html)) {
        val text = m.groups[4]?.value
        if (text != null) {
            if (pre != null) { pre!!.append(decodeEntities(text)); continue }
            val t = decodeEntities(text).replace(SPACES, " ")
            when {
                cell != null -> cell!! += Span(t, bold > 0, italic > 0, code > 0, link)
                table != null -> {} // 表格结构之间的空白
                caption != null -> caption!!.append(t)
                inFigure -> {}
                else -> spans += Span(t, bold > 0, italic > 0, code > 0, link)
            }
            continue
        }
        val closing = m.groupValues[1] == "/"
        val tag = m.groupValues[2].lowercase()
        val a = m.groupValues[3]
        // 代码块里只认 </pre>，里面的 <span> 高亮之类一律忽略
        if (pre != null) {
            if (tag == "pre" && closing) {
                val code = pre.toString().trimEnd('\n', ' ').trimStart('\n')
                if (code.isNotBlank()) out += Block.Code(code)
                pre = null
            } else if (tag == "br") pre!!.append('\n')
            continue
        }
        // 表格
        when (tag) {
            "table" -> {
                if (!closing) { flush(); table = mutableListOf(); tableHeader = false }
                else {
                    cell?.let { c -> row?.add(trimSpans(c)) }; cell = null
                    row?.takeIf { it.isNotEmpty() }?.let { table?.add(it) }; row = null
                    table?.takeIf { it.isNotEmpty() }?.let { t ->
                        val w = t.maxOf { it.size }
                        out += Block.Table(t.map { r -> r + List(w - r.size) { emptyList() } }, tableHeader)
                    }
                    table = null
                }
                continue
            }
            "tr" -> if (table != null) {
                cell?.let { c -> row?.add(trimSpans(c)) }; cell = null
                row?.takeIf { it.isNotEmpty() }?.let { table?.add(it) }
                row = if (closing) null else mutableListOf()
                continue
            }
            "td", "th" -> if (table != null) {
                cell?.let { c -> (row ?: mutableListOf<List<Span>>().also { row = it }).add(trimSpans(c)) }
                cell = if (closing) null else mutableListOf()
                if (tag == "th" && !closing && table!!.isEmpty()) tableHeader = true
                continue
            }
            "thead", "tbody", "tfoot", "caption", "colgroup", "col" -> if (table != null) continue
        }
        if (cell != null && tag == "br") { cell!! += Span("\n", bold > 0, italic > 0, code > 0, link); continue }
        if (table != null && cell == null) continue
        // 表格格子里只保留行内样式（加粗、链接、代码），段落/列表/图片标签忽略
        if (cell != null && tag !in INLINE) continue
        when (tag) {
            "pre" -> if (!closing) { flush(); pre = StringBuilder() }
            "hr" -> { flush(); out += Block.Rule }
            "p", "div", "section", "article" -> {
                flush()
                if (kind != "li" && kind != "blockquote") kind = "p"
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                flush()
                kind = if (closing) "p" else if (tag == "h1" || tag == "h2") "h2" else "h3"
            }
            "ul", "ol" -> {
                flush()
                if (!closing) { listStack.addLast(tag == "ol"); listCounter = 0 } else listStack.removeLastOrNull()
                kind = if (listStack.isEmpty()) "p" else "li"
            }
            "li" -> {
                flush()
                if (!closing) {
                    kind = "li"
                    ordered = if (listStack.lastOrNull() == true) ++listCounter else null
                }
            }
            "blockquote" -> { flush(); kind = if (closing) "p" else "blockquote" }
            "br" -> if (caption == null) spans += Span("\n", bold > 0, italic > 0, code > 0, link)
            "strong", "b" -> bold = (bold + if (closing) -1 else 1).coerceAtLeast(0)
            "em", "i" -> italic = (italic + if (closing) -1 else 1).coerceAtLeast(0)
            "code" -> code = (code + if (closing) -1 else 1).coerceAtLeast(0)
            "a" -> link = if (closing) null else attrs(a)["href"]?.takeIf { it.startsWith("http") }
            "figure" -> {
                flush()
                if (closing) { flushImage(); inFigure = false } else inFigure = true
            }
            "figcaption" -> if (!closing && pendingImage != null) caption = StringBuilder()
            "img" -> {
                val at = attrs(a)
                val src = at["src"].orEmpty()
                if (src.startsWith("http")) {
                    flushImage()
                    if (!inFigure) flush()
                    val w = at["width"]?.toFloatOrNull()
                    val h = at["height"]?.toFloatOrNull()
                    pendingImage = Block.Image(src, "", if (w != null && h != null && w > 0 && h > 0) w / h else null)
                    if (!inFigure) flushImage()
                }
            }
        }
    }
    flush()
    flushImage()
    return out
}

private fun trimSpans(list: List<Span>): List<Span> {
    val merged = list.filter { it.text.isNotEmpty() }.toMutableList()
    if (merged.isEmpty()) return merged
    merged[0] = merged[0].copy(text = merged[0].text.trimStart())
    merged[merged.size - 1] = merged.last().copy(text = merged.last().text.trimEnd())
    return merged.filter { it.text.isNotEmpty() }
}
