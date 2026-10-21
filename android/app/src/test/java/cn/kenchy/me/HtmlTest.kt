package cn.kenchy.me

import cn.kenchy.me.news.Block
import cn.kenchy.me.news.parseHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTest {
    @Test fun paragraphsBoldLinks() {
        val b = parseHtml("<p><strong>重点</strong>正文 <a href=\"https://x.com/a\">链接</a>&amp;更多</p><p>第二段</p>")
        assertEquals(2, b.size)
        val p = b[0] as Block.Para
        assertEquals("重点", p.spans[0].text)
        assertTrue(p.spans[0].bold)
        assertEquals("https://x.com/a", p.spans.first { it.text == "链接" }.link)
        assertEquals("重点正文 链接&更多", p.spans.joinToString("") { it.text })
    }

    @Test fun figureWithCaption() {
        val b = parseHtml("<figure><img src=\"https://aihot.news/api/img-proxy?u=x&amp;mode=full\" width=\"1920\" height=\"1080\"><figcaption>图注 | 图片：A</figcaption></figure><p>后文</p>")
        val img = b[0] as Block.Image
        assertEquals("https://aihot.news/api/img-proxy?u=x&mode=full", img.src)
        assertEquals("图注 | 图片：A", img.caption)
        assertEquals(1920f / 1080f, img.ratio!!, 0.001f)
        assertTrue(b[1] is Block.Para)
    }

    @Test fun headingsAndLists() {
        val b = parseHtml("<h2>标题</h2><ul><li>一</li><li>二</li></ul><ol><li>甲</li><li>乙</li></ol><p>尾</p>")
        assertTrue(b[0] is Block.Heading)
        assertEquals(null, (b[1] as Block.Bullet).ordered)
        assertEquals(2, (b[4] as Block.Bullet).ordered)
        assertTrue(b[5] is Block.Para)
    }

    @Test fun bareTextAndImageOutsideFigure() {
        val b = parseHtml("开头文字<br>换行<img src=\"https://a/b.png\">结尾")
        assertEquals(3, b.size)
        assertEquals("开头文字\n换行", (b[0] as Block.Para).spans.joinToString("") { it.text })
        assertTrue(b[1] is Block.Image)
    }

    @Test fun preKeepsLayout() {
        val b = parseHtml("<p>目录如下</p><pre><code>root/\n├── a.json   # x -&gt; y\n└── README.md\n</code></pre><h2>快速开始</h2><pre>git clone x\n<span>cd</span> y</pre>")
        val code = b.filterIsInstance<Block.Code>()
        assertEquals(2, code.size)
        assertEquals("root/\n├── a.json   # x -> y\n└── README.md", code[0].text)
        assertEquals("git clone x\ncd y", code[1].text)
        assertTrue(b[2] is Block.Heading)
    }

    @Test fun tableWithHeaderBrAndLinks() {
        val b = parseHtml("<table>\n<thead>\n<tr>\n<th>模型</th>\n<th>输入<br><span>美元</span></th>\n</tr>\n</thead><tbody><tr><td><a href=\"https://a.com\">MiniMax</a></td><td><strong>\$0.15</strong></td></tr><tr><td>只有一格</td></tr></tbody></table><p>后文</p>")
        val t = b[0] as Block.Table
        assertTrue(t.header)
        assertEquals(3, t.rows.size)
        assertEquals(listOf(2, 2, 2), t.rows.map { it.size })
        assertEquals("输入\n美元", t.rows[0][1].joinToString("") { it.text })
        assertEquals("https://a.com", t.rows[1][0][0].link)
        assertTrue(t.rows[1][1][0].bold)
        assertTrue(b[1] is Block.Para)
    }

    @Test fun realArticles() {
        for (f in listOf("/root/.me-logs/art-uvgayscfmc9ujjak94ymsnxnd.html", "/root/.me-logs/art-wrnv8dd428iozlj5xax99zbmj.html")) {
            val file = java.io.File(f)
            if (!file.exists()) continue
            val b = parseHtml(file.readText())
            println("== $f " + b.groupingBy { it::class.simpleName }.eachCount())
            b.filterIsInstance<Block.Code>().take(1).forEach { println(it.text) }
            b.filterIsInstance<Block.Table>().forEach { t -> t.rows.forEach { r -> println(r.joinToString(" | ") { c -> c.joinToString("") { it.text.replace("\n", "⏎") } }) } }
        }
    }
}

