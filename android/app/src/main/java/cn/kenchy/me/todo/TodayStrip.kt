package cn.kenchy.me.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.ui.HGap
import cn.kenchy.me.ui.T

/** 资讯页顶部：重点 + 今天到期的待办，最多 3 条，没有就不显示 */
@Composable
fun TodayStrip(items: List<Todo>, open: () -> Unit) {
    val c = T.c
    val now = nowLocal()
    val g = group(items, now)
    // 之前没做完的 + 今天的；重点排前面
    val show = (g.overdue + g.today).sortedBy { !it.pinned }.distinctBy { it.id }
    if (show.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(14.dp)).background(c.warnSoft).clickable(onClick = open).padding(14.dp, 10.dp),
    ) {
        show.take(3).forEach { t ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t.pinned) Icon(Icons.Filled.Star, null, tint = c.salary, modifier = Modifier.size(14.dp))
                else Icon(Icons.Outlined.RadioButtonUnchecked, null, tint = if (t.overdue(now)) c.down else c.warn, modifier = Modifier.size(14.dp))
                HGap(8.dp)
                Text(t.text, color = c.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                t.due?.let {
                    HGap(8.dp)
                    Text(describeDue(it, now.toLocalDate()), color = if (t.overdue(now)) c.down else c.ink3, fontSize = 12.sp)
                }
            }
        }
        if (show.size > 3) Text("还有 ${show.size - 3} 条 ›", color = c.warn, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
    }
}
