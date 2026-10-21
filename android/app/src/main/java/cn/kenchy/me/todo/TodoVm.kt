package cn.kenchy.me.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.kenchy.me.MeApp
import cn.kenchy.me.core.Api
import cn.kenchy.me.core.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@Serializable
data class Todo(
    val id: String,
    val kind: String = "todo", // todo | note
    val text: String = "",
    val pinned: Boolean = false,
    val due: String? = null,
    val repeat: Repeat? = null,
    val link: String? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val doneCount: Int = 0,
)

@Serializable
data class TodoList(val items: List<Todo> = emptyList())

/** 后台收到的待办更新：存本地缓存、重排提醒，并通知正在显示的界面 */
object TodoSync {
    val pushed = kotlinx.coroutines.flow.MutableSharedFlow<List<Todo>>(replay = 0, extraBufferCapacity = 2)

    fun update(ctx: android.content.Context, list: List<Todo>) {
        runCatching {
            ctx.getSharedPreferences("todos", android.content.Context.MODE_PRIVATE).edit()
                .putString("cache", json.encodeToString(TodoList.serializer(), TodoList(list))).apply()
            Reminders.sync(ctx, list)
        }
        pushed.tryEmit(list)
    }
}

val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
fun nowLocal(): LocalDateTime = LocalDateTime.now(shanghai)
fun todayLocal(): LocalDate = LocalDate.now(shanghai)

/** 今天页的分组（只有待办，备忘在「我 → 备忘」） */
data class Groups(
    val overdue: List<Todo>, // 之前没做完的
    val today: List<Todo>, // 今天的（含重点）
    val someday: List<Todo>, // 没日期的待办，随时可做
    val doneToday: List<Todo>, // 今天完成的
    val upcoming: Int, // 以后还有几件，用于提示
)

fun Todo.dueDate(): LocalDate? = due?.let { LocalDate.parse(it.substring(0, 10)) }
fun Todo.overdue(now: LocalDateTime): Boolean {
    val d = due ?: return false
    if (kind != "todo" || done) return false
    return if (d.length > 10) LocalDateTime.parse(d).isBefore(now) else LocalDate.parse(d).isBefore(now.toLocalDate())
}

fun group(items: List<Todo>, now: LocalDateTime): Groups {
    val today = now.toLocalDate()
    val byDue = compareBy<Todo>({ !it.pinned }, { it.due ?: "9999" }, { it.createdAt })
    val todos = items.filter { it.kind == "todo" }
    val open = todos.filter { !it.done }
    val startOfToday = today.atStartOfDay(shanghai).toInstant().toEpochMilli()
    return Groups(
        overdue = open.filter { it.dueDate()?.isBefore(today) == true }.sortedWith(byDue),
        today = open.filter { it.dueDate() == today }.sortedWith(byDue),
        someday = open.filter { it.due == null }.sortedWith(compareBy<Todo>({ !it.pinned }, { -it.createdAt })),
        doneToday = todos.filter { it.done && (it.doneAt ?: 0) >= startOfToday }.sortedByDescending { it.doneAt ?: 0 },
        upcoming = open.count { it.dueDate()?.isAfter(today) == true },
    )
}

/** 某一天要做的：当天到期的（未完成 + 完成了的）；重复事项按规则推算出落在这天的也算 */
fun onDay(items: List<Todo>, day: LocalDate): List<Todo> =
    items.filter { it.kind == "todo" && (it.dueDate() == day || (!it.done && it.repeat != null && occursOn(it, day))) }
        .distinctBy { it.id }
        .sortedWith(compareBy<Todo>({ it.done }, { !it.pinned }, { it.due?.substringAfter('T', "") ?: "" }))

/** 这一天有没有待办（日历上的小点）：未完成的数 / 是否都完成了 */
fun dayMarks(items: List<Todo>, month: java.time.YearMonth): Map<LocalDate, Pair<Int, Int>> {
    val out = HashMap<LocalDate, Pair<Int, Int>>()
    for (d in 1..month.lengthOfMonth()) {
        val day = month.atDay(d)
        val list = onDay(items, day)
        if (list.isNotEmpty()) out[day] = list.count { !it.done } to list.size
    }
    return out
}

/** 重复事项在 day 这天会不会发生：从下一次到期时间往后推（只往后看，过去的次数已经在服务器上记过了） */
fun occursOn(t: Todo, day: LocalDate): Boolean {
    val r = t.repeat ?: return false
    val first = t.dueDate() ?: return false
    if (day.isBefore(first)) return false
    if (day == first) return true
    val every = r.every.coerceAtLeast(1).toLong()
    return when (r.unit) {
        "day" -> java.time.temporal.ChronoUnit.DAYS.between(first, day) % every == 0L
        "week" -> java.time.temporal.ChronoUnit.DAYS.between(first, day) % (7 * every) == 0L
        "month" -> {
            val months = java.time.temporal.ChronoUnit.MONTHS.between(first.withDayOfMonth(1), day.withDayOfMonth(1))
            val anchor = r.day ?: first.dayOfMonth
            months % every == 0L && day.dayOfMonth == minOf(anchor, day.lengthOfMonth())
        }
        "year" -> {
            val years = (day.year - first.year).toLong()
            val mo = r.month ?: first.monthValue
            val anchor = r.day ?: first.dayOfMonth
            years % every == 0L && day.monthValue == mo && day.dayOfMonth == minOf(anchor, day.lengthOfMonth())
        }
        else -> false
    }
}

class TodoVm(private val api: Api, autoload: Boolean = true) : ViewModel() {
    constructor() : this(MeApp.instance.api)

    private val sp by lazy { MeApp.instance.getSharedPreferences("todos", android.content.Context.MODE_PRIVATE) }

    val items = MutableStateFlow<List<Todo>?>(null)
    val error = MutableStateFlow<String?>(null)
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 4)

    init {
        if (autoload) {
            // 先用本地缓存秒开，再拉最新
            sp.getString("cache", null)?.let { raw ->
                runCatching { json.decodeFromString(TodoList.serializer(), raw) }.onSuccess { items.value = it.items }
            }
            refresh()
        }
        // 电脑上改了待办，后台连接收到后推过来
        viewModelScope.launch { TodoSync.pushed.collect { items.value = it; error.value = null } }
    }

    private fun set(list: List<Todo>) {
        items.value = list
        error.value = null
        runCatching {
            sp.edit().putString("cache", json.encodeToString(TodoList.serializer(), TodoList(list))).apply()
            Reminders.sync(MeApp.instance, list)
        }
    }

    fun refresh() = viewModelScope.launch {
        runCatching { api.get("/api/todos", TodoList.serializer()) }
            .onSuccess { set(it.items) }
            .onFailure { error.value = it.message }
    }

    private fun replace(t: Todo) = set((items.value ?: emptyList()).map { if (it.id == t.id) t else it })

    private fun call(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { toast.tryEmit(it.message ?: "操作失败") }
    }

    /** 新建：表单填好的结构直接写入，成功后回调（用于关表单 / 连续添加清空） */
    fun create(t: Todo, done: () -> Unit = {}) = call {
        val body = buildJsonObject {
            put("kind", JsonPrimitive(t.kind))
            put("text", JsonPrimitive(t.text.trim()))
            put("pinned", JsonPrimitive(t.pinned))
            put("due", t.due?.takeIf { t.kind == "todo" }?.let(::JsonPrimitive) ?: JsonNull)
            put("repeat", t.repeat?.takeIf { t.kind == "todo" && t.due != null }?.let { json.encodeToJsonElement(it) } ?: JsonNull)
            if (t.kind == "todo" && Regex("记.{0,4}(储蓄|存款|账)").containsMatchIn(t.text)) put("link", JsonPrimitive("savings/new"))
        }
        set((items.value ?: emptyList()) + api.send("POST", "/api/todos", body.toString(), Todo.serializer()))
        done()
    }

    fun patch(id: String, body: JsonObject) = call {
        replace(api.send("PATCH", "/api/todos/$id", body.toString(), Todo.serializer()))
    }

    fun save(t: Todo) = patch(
        t.id,
        buildJsonObject {
            put("kind", JsonPrimitive(t.kind))
            put("text", JsonPrimitive(t.text))
            put("pinned", JsonPrimitive(t.pinned))
            put("due", t.due?.let(::JsonPrimitive) ?: JsonNull)
            put("repeat", t.repeat?.let { json.encodeToJsonElement(it) } ?: JsonNull)
        },
    )

    fun togglePin(t: Todo) = patch(t.id, buildJsonObject { put("pinned", JsonPrimitive(!t.pinned)) })

    fun toggleDone(t: Todo) = call {
        // 先在本地打勾，界面不等网络
        if (t.repeat == null) replace(t.copy(done = !t.done, doneAt = if (t.done) null else System.currentTimeMillis()))
        val r = api.send("POST", "/api/todos/${t.id}/${if (t.done) "undone" else "done"}", "{}", Todo.serializer())
        replace(r)
        if (t.repeat != null && !t.done) toast.tryEmit("下一次：${describeDue(r.due!!, todayLocal())}")
    }

    fun delete(t: Todo) = call {
        api.raw("DELETE", "/api/todos/${t.id}")
        set((items.value ?: emptyList()).filter { it.id != t.id })
    }

    /** 撤销删除：按原内容重新建一条 */
    fun restore(t: Todo) = call {
        val body = buildJsonObject {
            put("kind", JsonPrimitive(t.kind))
            put("text", JsonPrimitive(t.text))
            put("pinned", JsonPrimitive(t.pinned))
            put("due", t.due?.let(::JsonPrimitive) ?: JsonNull)
            put("repeat", t.repeat?.let { json.encodeToJsonElement(it) } ?: JsonNull)
            put("link", t.link?.let(::JsonPrimitive) ?: JsonNull)
        }
        set((items.value ?: emptyList()) + api.send("POST", "/api/todos", body.toString(), Todo.serializer()))
    }
}
