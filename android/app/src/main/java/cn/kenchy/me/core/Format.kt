package cn.kenchy.me.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

private fun group(n: Long): String = "%,d".format(n)

/** ¥525,000 */
fun money(v: Double, sign: Boolean = false): String {
    val s = group(abs(v).roundToLong())
    val p = when {
        v < 0 -> "−"
        sign && v > 0 -> "+"
        else -> ""
    }
    return "$p¥$s"
}

/** 52.5万 / 8,400 —— 图表和紧凑位置用 */
fun moneyShort(v: Double): String {
    val a = abs(v)
    val p = if (v < 0) "−" else ""
    return when {
        a >= 10_000 -> p + "%.1f万".format(a / 10_000).replace(".0万", "万")
        else -> p + group(a.roundToLong())
    }
}

fun pct(v: Double?, digits: Int = 1): String = if (v == null) "—" else "%.${digits}f%%".format(v * 100)

fun monthLabel(m: String) = "${m.substring(0, 4)}年${m.substring(5, 7).toInt()}月"
fun monthShort(m: String) = "${m.substring(2, 4)}.${m.substring(5, 7)}"

private val zone = ZoneId.of("Asia/Shanghai")

/** 「刚刚 / 5 分钟前 / 3 小时前 / 10月18日」 */
fun ago(epochMs: Long): String {
    val d = (System.currentTimeMillis() - epochMs) / 1000
    return when {
        d < 60 -> "刚刚"
        d < 3600 -> "${d / 60} 分钟前"
        d < 86400 -> "${d / 3600} 小时前"
        d < 7 * 86400 -> "${d / 86400} 天前"
        else -> DateTimeFormatter.ofPattern("M月d日").format(Instant.ofEpochMilli(epochMs).atZone(zone))
    }
}

fun isoAgo(iso: String?): String =
    runCatching { ago(Instant.parse(iso).toEpochMilli()) }.getOrDefault("")

fun hhmm(epochMs: Long): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(epochMs).atZone(zone))
