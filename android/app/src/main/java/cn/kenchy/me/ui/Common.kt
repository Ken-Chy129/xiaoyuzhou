package cn.kenchy.me.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val CardShape = RoundedCornerShape(16.dp)

@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = T.c
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(c.surface)
            .border(1.dp, c.line, CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = T.c.ink3) {
    Text(text, modifier, color = color, fontSize = 12.sp, letterSpacing = 0.3.sp)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = T.c.ink, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 金额：隐私模式下模糊 */
@Composable
fun MoneyText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 15,
    weight: FontWeight = FontWeight.Medium,
    color: Color = T.c.ink,
) {
    Text(
        text,
        modifier,
        style = androidx.compose.material3.LocalTextStyle.current.merge(Num.copy(fontSize = size.sp, fontWeight = weight, color = color)),
        maxLines = 1,
    )
}


@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun Pill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
        color = fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = T.c.accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    // 点了重试至少转 0.6 秒：失败得很快时也让人看得出「确实重试过了」
    var busy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var tries by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(tries) {
        if (tries > 0) {
            kotlinx.coroutines.delay(600)
            busy = false
        }
    }
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(cn.kenchy.me.core.friendlyError(message), color = T.c.ink2, style = MaterialTheme.typography.bodyMedium)
        if (tries > 0) Text("已重试 $tries 次", color = T.c.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
            if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), color = T.c.accent, strokeWidth = 2.dp)
            else TextButton(onClick = { busy = true; tries++; onRetry() }) { Text("重试", color = T.c.accent) }
        }
    }
}

/** 细横条 */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, track: Color = T.c.line2, height: Dp = 6.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "bar")
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(track)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
    }
}

/** 两列 Key-Value 小表 */
@Composable
fun KV(label: String, value: String, modifier: Modifier = Modifier, money: Boolean = true, valueColor: Color = T.c.ink) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = T.c.ink2, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (money) MoneyText(value, size = 14, color = valueColor) else Text(value, style = androidx.compose.material3.LocalTextStyle.current.merge(Num.copy(fontSize = 14.sp, color = valueColor)))
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = T.c
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(c.line2).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val bg by androidx.compose.animation.animateColorAsState(if (on) c.surface else c.surface.copy(alpha = 0f), androidx.compose.animation.core.tween(200), label = "seg")
            Text(
                o,
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                fontSize = 13.sp,
                color = if (on) c.ink else c.ink2,
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.padding(top = h))

@Composable
fun HGap(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun Faded(on: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.alpha(if (on) 0.5f else 1f)) { content() }
}

val Mono = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
