package cn.kenchy.me.core

import kotlinx.serialization.Serializable

/* ---------- 配对 / 版本 ---------- */

@Serializable
data class PairResult(val token: String = "", val error: String? = null)

@Serializable
data class VersionInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val url: String = "",
    val notes: String = "",
    val sha256: String = "",
)

/* ---------- 首页 ---------- */

@Serializable
data class HomeData(
    val savings: HomeSavings = HomeSavings(),
    val codex: CodexList = CodexList(),
    val daily: Daily? = null,
)

@Serializable
data class HomeSavings(
    val records: Int = 0,
    val latest: String? = null,
    val balance: Double = 0.0,
    val latestSaved: Double = 0.0,
    val goal: Double = 1_000_000.0,
    val currentMonth: String = "",
    val currentRecorded: Boolean = false,
)

/* ---------- 储蓄 ---------- */

@Serializable
data class Entry(
    val id: String = "",
    val month: String,
    val salary: Double = 0.0,
    val bonus: Double = 0.0,
    val insurance: Double = 0.0,
    val tax: Double = 0.0,
    val other: Double = 0.0,
    val family: Double = 0.0,
    val fund: Double = 0.0,
    val fixed: Double = 0.0,
    val personal: Double = 0.0,
    val fundWithdrawal: Double = 0.0,
    val special: Boolean = false,
    val note: String = "",
)

@Serializable
data class Expense(
    val id: String = "",
    val name: String,
    val category: String = "居住",
    val amount: Double,
    val from: String,
    val to: String? = null,
    val note: String = "",
)

@Serializable
data class FundBalance(val amount: Double, val asOf: String)

@Serializable
data class Settings(
    val goal: Double = 1_000_000.0,
    val fundBalance: FundBalance? = null,
    val otherAssets: Double = 0.0,
)

@Serializable
data class Ledger(
    val records: List<Entry> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val settings: Settings = Settings(),
)

/* ---------- 资讯 ---------- */

@Serializable
data class NewsItem(
    val id: String = "",
    val title: String = "",
    val summary: String = "",
    val source: String = "",
    val url: String = "",
    val aihotUrl: String = "",
    val publishedAt: String? = null,
    val rank: Int? = null,
    val sourceCount: Int = 0,
    val sources: List<String> = emptyList(),
    val latestAt: String? = null,
)

@Serializable
data class Lead(val id: String? = null, val title: String = "", val summary: String = "", val url: String? = null)

@Serializable
data class NewsArticle(
    val id: String = "",
    val title: String = "",
    val originalTitle: String? = null,
    val summary: String = "",
    val source: String = "",
    val author: String? = null,
    val publishedAt: String? = null,
    val tags: List<String> = emptyList(),
    val url: String = "",
    val translated: Boolean = false,
    val complete: Boolean = false,
    val sourceCount: Int = 1,
    val html: String = "",
    val attribution: String = "",
)

@Serializable
data class Section(val label: String = "", val items: List<NewsItem> = emptyList())

@Serializable
data class Daily(
    val date: String = "",
    val generatedAt: String? = null,
    val lead: Lead? = null,
    val sections: List<Section> = emptyList(),
    val flashes: List<NewsItem> = emptyList(),
    val url: String? = null,
    val attribution: String = "",
)

@Serializable
data class Hot(val items: List<NewsItem> = emptyList(), val attribution: String = "")

/* ---------- Codex ---------- */

@Serializable
data class Approval(
    val key: String,
    val kind: String = "command",
    val command: String? = null,
    val cwd: String? = null,
    val reason: String? = null,
    val at: Long = 0,
)

@Serializable
data class Machine(val name: String, val online: Boolean = false, val lastSeenAt: Long? = null)

@Serializable
data class MachineError(val machine: String? = null, val error: String = "")

@Serializable
data class SessionSummary(
    val machine: String,
    val id: String,
    val name: String = "",
    val cwd: String = "",
    val state: String = "done",
    val loaded: Boolean = false,
    val updatedAt: Long = 0,
    val lastMessage: String = "",
    val model: String? = null,
    val originator: String? = null,
    val approvals: List<Approval> = emptyList(),
)

@Serializable
data class CodexList(
    val sessions: List<SessionSummary> = emptyList(),
    val older: Int = 0,
    val machines: List<Machine> = emptyList(),
    val errors: List<MachineError> = emptyList(),
)

@Serializable
data class Step(
    val kind: String,
    val command: String? = null,
    val name: String? = null,
    val status: String? = null,
    val exitCode: Int? = null,
    val output: String? = null,
)

@Serializable
data class TurnItem(
    val kind: String,
    val text: String? = null,
    val steps: List<Step> = emptyList(),
    val count: Int = 0,
    val files: List<String> = emptyList(),
    val status: String? = null,
    val id: String? = null,
    val images: List<ImageRef> = emptyList(),
)

@Serializable
data class ImageRef(val index: Int, val local: Boolean = false)

@Serializable
data class Turn(
    val id: String,
    val status: String = "",
    val startedAt: Long? = null,
    val durationMs: Long? = null,
    val error: String? = null,
    val items: List<TurnItem> = emptyList(),
)

@Serializable
data class SessionDetail(
    val machine: String,
    val id: String,
    val name: String = "",
    val cwd: String = "",
    val state: String = "done",
    val updatedAt: Long = 0,
    val model: String? = null,
    val approvals: List<Approval> = emptyList(),
    val turns: List<Turn> = emptyList(),
    val moreTurns: Boolean = false,
    val before: String? = null,
)

@Serializable
data class OlderTurns(val turns: List<Turn> = emptyList(), val before: String? = null)

@Serializable
data class Dirs(val dirs: List<String> = emptyList())

@Serializable
data class Started(val id: String = "")

@Serializable
data class MessageResult(val mode: String = "")
