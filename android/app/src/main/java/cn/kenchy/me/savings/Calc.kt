package cn.kenchy.me.savings

import cn.kenchy.me.core.Entry
import cn.kenchy.me.core.Expense
import cn.kenchy.me.core.Settings

// 与网站 src/calc.ts 同一套口径
data class Row(
    val e: Entry,
    val takeHome: Double, // 到手 = 工资 − 五险一金 − 个税
    val disposable: Double, // 可支配 = 到手 + 附加收入 − 家庭补贴
    val fixedCost: Double,
    val deposits: Double, // 当月存入 = 定存 + 个人存储
    val saved: Double, // 收入存下 = 存入 − 公积金提取
    val rate: Double?, // 储蓄率 = 存下 ÷ 可支配
    val balanceFixed: Double, // 截至当月累计定存
    val balancePersonal: Double, // 截至当月累计个人存储
    val fundBalance: Double, // 公积金余额 = 累计缴存 − 提取（可用实际余额校准）
) {
    val month get() = e.month
    /** 当前存款 = 定存 + 个人存储 + 公积金 */
    val balance get() = balanceFixed + balancePersonal + fundBalance
}

fun monthIndex(m: String) = m.substring(0, 4).toInt() * 12 + m.substring(5, 7).toInt() - 1
fun monthFromIndex(i: Int) = "%d-%02d".format(i / 12, i % 12 + 1)
fun addMonths(m: String, n: Int) = monthFromIndex(monthIndex(m) + n)

fun fixedCostFor(month: String, expenses: List<Expense>) =
    expenses.filter { it.from <= month && (it.to == null || month <= it.to) }.sumOf { it.amount }

fun derive(entries: List<Entry>, expenses: List<Expense>, settings: Settings? = null): List<Row> {
    var fixed = 0.0
    var personal = 0.0
    var fund = 0.0
    val anchor = settings?.fundBalance
    return entries.sortedBy { it.month }.map { e ->
        val takeHome = e.salary - e.insurance - e.tax
        val disposable = takeHome + e.other - e.family
        val deposits = e.fixed + e.personal
        val saved = deposits - e.fundWithdrawal
        fixed += e.fixed
        personal += e.personal
        fund += e.fund - e.fundWithdrawal
        if (anchor != null && anchor.asOf == e.month) fund = anchor.amount
        Row(
            e = e,
            takeHome = takeHome,
            disposable = disposable,
            fixedCost = fixedCostFor(e.month, expenses),
            deposits = deposits,
            saved = saved,
            rate = if (disposable > 0) saved / disposable else null,
            balanceFixed = fixed,
            balancePersonal = personal,
            fundBalance = maxOf(0.0, fund),
        )
    }
}

/** 年度：7 月 1 日到次年 6 月 30 日（与网站一致），key 是起始年份 */
data class Period(val key: String, val label: String, val range: String)

fun periodOf(month: String, fiscal: Boolean): Period {
    val y = month.substring(0, 4).toInt()
    val m = month.substring(5, 7).toInt()
    if (!fiscal) return Period("$y", "${y}年", "$y.1–$y.12")
    val start = if (m >= 7) y else y - 1
    return Period("$start", "${start % 100}/${(start + 1) % 100}年度", "$start.7–${start + 1}.6")
}

fun periods(rows: List<Row>, fiscal: Boolean) =
    rows.map { periodOf(it.month, fiscal) }.distinctBy { it.key }.sortedBy { it.key }

data class Summary(
    val months: Int,
    val salary: Double,
    val bonus: Double,
    val other: Double,
    val takeHome: Double,
    val disposable: Double,
    val saved: Double,
    val deposits: Double,
    val tax: Double,
    val insurance: Double,
    val family: Double,
    val fund: Double,
    val fixedCost: Double,
    val rate: Double?,
    val avgSaved: Double,
    val avgSalary: Double,
    val avgTakeHome: Double,
    val otherMonths: Int,
)

fun summarize(rows: List<Row>): Summary {
    val normal = rows.filter { !it.e.special }
    fun avg(f: (Row) -> Double) = if (normal.isEmpty()) 0.0 else normal.sumOf(f) / normal.size
    val disposable = rows.sumOf { it.disposable }
    val saved = rows.sumOf { it.saved }
    return Summary(
        months = rows.size,
        salary = rows.sumOf { it.e.salary },
        bonus = rows.sumOf { it.e.bonus },
        other = rows.sumOf { it.e.other },
        takeHome = rows.sumOf { it.takeHome },
        disposable = disposable,
        saved = saved,
        deposits = rows.sumOf { it.deposits },
        tax = rows.sumOf { it.e.tax },
        insurance = rows.sumOf { it.e.insurance },
        family = rows.sumOf { it.e.family },
        fund = rows.sumOf { it.e.fund },
        fixedCost = rows.sumOf { it.fixedCost },
        rate = if (disposable > 0) saved / disposable else null,
        avgSaved = avg { it.saved },
        avgSalary = avg { it.e.salary },
        avgTakeHome = avg { it.takeHome },
        otherMonths = rows.count { it.e.other > 0 },
    )
}

/** 去年同期：同样的月份往前推 12 个月，月份要齐全才比较 */
fun samePeriodLastYear(all: List<Row>, selected: List<Row>): List<Row>? {
    val wanted = selected.map { addMonths(it.month, -12) }.toSet()
    val rows = all.filter { it.month in wanted }
    return if (rows.isNotEmpty() && rows.size == selected.size) rows else null
}

/** 按近 12 个月的平均月存下推算何时达到目标 */
data class Projection(val monthly: Double, val month: String?)

fun projectGoal(rows: List<Row>, goal: Double): Projection? {
    val last = rows.lastOrNull() ?: return null
    val back = rows[maxOf(0, rows.size - 13)]
    val span = monthIndex(last.month) - monthIndex(back.month)
    val monthly = if (span > 0) (last.balance - back.balance) / span else 0.0
    if (last.balance >= goal) return Projection(monthly, last.month)
    if (monthly <= 0) return Projection(monthly, null)
    val n = kotlin.math.ceil((goal - last.balance) / monthly).toInt()
    return Projection(monthly, addMonths(last.month, n))
}
