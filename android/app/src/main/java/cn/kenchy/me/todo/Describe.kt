package cn.kenchy.me.todo

import java.time.LocalDate

/** 重复规则，和服务端一致 */
@kotlinx.serialization.Serializable
data class Repeat(val unit: String, val every: Int = 1, val day: Int? = null, val month: Int? = null)

/** 到期时间：10月25日 周六 15:00（跨年带年份） */
fun describeDue(due: String, today: LocalDate): String {
    val date = LocalDate.parse(due.substring(0, 10))
    val time = if (due.length > 10) " " + due.substring(11) else ""
    val wd = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]
    // 一律写具体日期：10月20日 周二 14:00
    val d = if (date.year == today.year) "${date.monthValue}月${date.dayOfMonth}日" else "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    return "$d $wd$time"
}

fun describeRepeat(r: Repeat): String {
    val n = if (r.every > 1) "${r.every}" else ""
    return when (r.unit) {
        "day" -> if (n.isEmpty()) "每天" else "每${n}天"
        "week" -> if (n.isEmpty()) "每周" else "每${n}周"
        "month" -> (if (n.isEmpty()) "每月" else "每${n}个月") + (r.day?.let { " ${it}号" } ?: "")
        "year" -> "每年" + (if (r.month != null && r.day != null) " ${r.month}月${r.day}日" else "")
        else -> ""
    }
}
