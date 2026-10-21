package cn.kenchy.me.savings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.ui.T

/** 累计存款走势：面积 + 线，可拖动查看某个月 */
@Composable
fun AreaChart(
    values: List<Double>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    goal: Double? = null,
) {
    val c = T.c
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values.size) { grow.animateTo(1f, tween(700)) }
    if (values.size < 2) return
    val max = maxOf(values.max(), 1.0)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(values.size) {
                fun idx(x: Float) = ((x / size.width) * (values.size - 1)).let { Math.round(it) }.coerceIn(0, values.size - 1)
                detectDragGestures(
                    onDragStart = { onSelect(idx(it.x)) },
                    onDragEnd = { },
                    onDrag = { ch, _ -> onSelect(idx(ch.position.x)) },
                )
            }
            .pointerInput(values.size) {
                detectTapGestures { o ->
                    val i = Math.round((o.x / size.width) * (values.size - 1)).coerceIn(0, values.size - 1)
                    onSelect(if (selected == i) null else i)
                }
            },
    ) {
        val w = size.width
        val h = size.height - 4.dp.toPx()
        val pts = values.mapIndexed { i, v -> Offset(w * i / (values.size - 1), h - (v / max * h * grow.value).toFloat() + 2.dp.toPx()) }
        val line = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val p0 = pts[i - 1]
                val p1 = pts[i]
                val mx = (p0.x + p1.x) / 2
                cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
            }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(pts.last().x, size.height)
            lineTo(pts.first().x, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.22f), c.accent.copy(alpha = 0.02f))))
        drawPath(line, c.accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        selected?.let { i ->
            val p = pts[i.coerceIn(0, pts.lastIndex)]
            drawLine(c.ink3, Offset(p.x, 0f), Offset(p.x, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawCircle(c.surface, 5.dp.toPx(), p)
            drawCircle(c.accent, 3.5.dp.toPx(), p)
        } ?: drawCircle(c.accent, 3.5.dp.toPx(), pts.last())
    }
}

/** 每月存下的柱状图；特殊月用浅色；点一下看那个月 */
@Composable
fun MonthBars(
    values: List<Double>,
    labels: List<String>,
    special: List<Boolean>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    average: Double? = null,
    color: Color = T.c.accent,
) {
    val c = T.c
    val measurer = rememberTextMeasurer()
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values.size) { grow.snapTo(0f); grow.animateTo(1f, tween(600)) }
    if (values.isEmpty()) return
    val max = maxOf(values.maxOf { maxOf(it, 0.0) }, 1.0)
    val labelStyle = TextStyle(fontSize = 9.sp, color = c.ink3)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .pointerInput(values.size, selected) {
                detectTapGestures { o ->
                    val i = (o.x / (size.width / values.size)).toInt().coerceIn(0, values.size - 1)
                    onSelect(if (selected == i) null else i)
                }
            },
    ) {
        val labelH = 14.dp.toPx()
        val h = size.height - labelH
        val slot = size.width / values.size
        val bw = (slot * 0.62f).coerceAtMost(22.dp.toPx())
        values.forEachIndexed { i, v ->
            val bh = (maxOf(v, 0.0) / max * h * grow.value).toFloat()
            val x = slot * i + (slot - bw) / 2
            val on = selected == null || selected == i
            val col = when {
                special[i] -> c.cost
                else -> color
            }.copy(alpha = if (on) 1f else 0.3f)
            drawRoundRect(col, Offset(x, h - bh), Size(bw, bh), CornerRadius(3.dp.toPx()))
            // 标签：月份少时全显示，多时每隔几个显示
            val step = when {
                values.size <= 12 -> 1
                values.size <= 24 -> 3
                else -> 6
            }
            if (i % step == 0 || i == values.lastIndex) {
                val t = measurer.measure(labels[i], labelStyle)
                val tx = (slot * i + slot / 2 - t.size.width / 2).coerceIn(0f, size.width - t.size.width)
                drawText(t, topLeft = Offset(tx, h + 2.dp.toPx()))
            }
        }
        average?.let { a ->
            if (a > 0) {
                val y = h - (a / max * h).toFloat()
                drawLine(c.ink3, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            }
        }
    }
}

/** 首页卡片里的迷你走势线 */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, color: Color = T.c.accent) {
    if (values.size < 2) return
    val max = values.max()
    val min = values.min()
    Canvas(modifier) {
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        val p = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height - ((v - min) / span * size.height).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
