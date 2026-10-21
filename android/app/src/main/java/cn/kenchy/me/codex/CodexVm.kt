package cn.kenchy.me.codex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.kenchy.me.MeApp
import cn.kenchy.me.core.Api
import cn.kenchy.me.core.CodexList
import cn.kenchy.me.core.Dirs
import cn.kenchy.me.core.HomeData
import cn.kenchy.me.core.MessageResult
import cn.kenchy.me.core.SessionDetail
import cn.kenchy.me.core.Started
import cn.kenchy.me.core.json
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Codex 模块 + 首页数据。App 在前台时保持一条到服务器的 WebSocket，
 * 收到「某会话变了」就刷新列表和正在看的会话。
 */
class CodexVm(private val api: Api, private val prefs: cn.kenchy.me.core.Prefs) : ViewModel() {
    constructor() : this(MeApp.instance.api, MeApp.instance.prefs)

    val home = MutableStateFlow(prefs.cachedHome?.let { runCatching { json.decodeFromString(HomeData.serializer(), it) }.getOrNull() })
    val homeError = MutableStateFlow<String?>(null)
    val list = MutableStateFlow<CodexList?>(null)
    val listError = MutableStateFlow<String?>(null)
    val detail = MutableStateFlow<SessionDetail?>(null)
    val detailError = MutableStateFlow<String?>(null)
    val live = MutableStateFlow(false)
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private var watching: Pair<String, String>? = null
    private var ws: WebSocket? = null
    private var foreground = false
    private var reconnect: Job? = null
    private var refreshJob: Job? = null

    fun resume() {
        foreground = true
        refreshHome()
        refreshList()
        watching?.let { (m, id) -> refreshDetail(m, id) }
        connect()
    }

    fun pause() {
        foreground = false
        reconnect?.cancel()
        ws?.close(1000, null)
        ws = null
        live.value = false
    }

    private fun connect() {
        if (ws != null || !foreground) return
        ws = api.live(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                live.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (msg["type"]?.jsonPrimitive?.content) {
                    "changed" -> {
                        val m = msg["machine"]?.jsonPrimitive?.content
                        val id = msg["sessionId"]?.jsonPrimitive?.content
                        scheduleRefresh(m, id)
                    }
                    "machines" -> scheduleRefresh(null, null)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
        })
    }

    private fun dropped(socket: WebSocket) {
        if (ws !== socket) return
        ws = null
        live.value = false
        if (!foreground) return
        reconnect?.cancel()
        reconnect = viewModelScope.launch {
            delay(3000)
            connect()
        }
    }

    /** 流式输出时变更很密，合并成最多每秒刷新一次 */
    private fun scheduleRefresh(machine: String?, id: String?) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            delay(800)
            refreshList()
            val w = watching
            if (w != null && (machine == null || (w.first == machine && w.second == id))) refreshDetail(w.first, w.second)
        }
    }

    fun refreshHome() = viewModelScope.launch {
        runCatching { api.raw("GET", "/api/home") }
            .onSuccess {
                prefs.cachedHome = it
                home.value = json.decodeFromString(HomeData.serializer(), it)
                homeError.value = null
            }
            .onFailure { homeError.value = it.message }
    }

    fun refreshList() = viewModelScope.launch {
        runCatching { api.get("/api/codex/sessions", CodexList.serializer()) }
            .onSuccess {
                list.value = it
                listError.value = null
                home.value = home.value?.copy(codex = it)
            }
            .onFailure { listError.value = it.message }
    }

    fun open(machine: String, id: String) {
        if (watching != machine to id) {
            // 有缓存先显示缓存（秒开），后台再刷新
            detail.value = detailCache[machine to id]
            older.value = emptyList()
            olderCursor = null
            olderDone = false
            detailError.value = null // 不让上一个会话的错误挡住这个会话
            fresh.value = false
        }
        watching = machine to id
        refreshDetail(machine, id)
    }

    fun close(machine: String, id: String) {
        // 停留期间的新进展也算看过了
        (detail.value?.takeIf { it.machine == machine && it.id == id }?.updatedAt
            ?: list.value?.sessions?.firstOrNull { it.machine == machine && it.id == id }?.updatedAt)
            ?.let { Seen.markSeen(machine, id, it) }
        if (watching == machine to id) watching = null
    }

    /** 这次打开后，服务器上的最新内容是否已经到了（之前显示的可能只是缓存） */
    val fresh = MutableStateFlow(false)

    val detailLoading = MutableStateFlow(false)

    /** 往上翻出来的更早几轮；刷新最新内容时保留，不会被冲掉 */
    val older = MutableStateFlow<List<cn.kenchy.me.core.Turn>>(emptyList())
    val olderLoading = MutableStateFlow(false)
    private var olderCursor: String? = null
    private var olderDone = false
    private val detailCache = object : LinkedHashMap<Pair<String, String>, SessionDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, SessionDetail>?) = size > 12
    }

    fun hasOlder(): Boolean {
        val d = detail.value ?: return false
        return if (older.value.isEmpty() && olderCursor == null && !olderDone) d.before != null else olderCursor != null
    }

    fun loadOlder(machine: String, id: String) {
        if (olderLoading.value || watching != machine to id) return
        val cursor = olderCursor ?: if (older.value.isEmpty() && !olderDone) detail.value?.before else null
        cursor ?: return
        olderLoading.value = true
        viewModelScope.launch {
            runCatching {
                api.get("/api/codex/sessions/$machine/$id/older?before=" + java.net.URLEncoder.encode(cursor, "UTF-8"), cn.kenchy.me.core.OlderTurns.serializer())
            }.onSuccess {
                if (watching == machine to id) {
                    val have = (older.value + detail.value?.turns.orEmpty()).map { t -> t.id }.toSet()
                    older.value = it.turns.filter { t -> t.id !in have } + older.value
                    olderCursor = it.before
                    olderDone = it.before == null
                }
            }.onFailure { toast.tryEmit(it.message ?: "加载失败") }
            olderLoading.value = false
        }
    }

    /** userRetry：用户点「重试」时先清掉错误，界面回到加载中 */
    fun refreshDetail(machine: String, id: String, userRetry: Boolean = false) = viewModelScope.launch {
        if (userRetry) detailError.value = null
        detailLoading.value = true
        runCatching { api.get("/api/codex/sessions/${machine}/${id}", SessionDetail.serializer()) }
            .onSuccess {
                detailCache[machine to id] = it
                if (watching == machine to id) {
                    detail.value = it
                    detailError.value = null
                    fresh.value = true
                    Seen.markSeen(machine, id, it.updatedAt)
                }
            }
            .onFailure { if (watching == machine to id) detailError.value = it.message }
        detailLoading.value = false
    }

    suspend fun send(machine: String, id: String, text: String, images: List<String> = emptyList()): Boolean = runCatching {
        val body = buildJsonObject {
            put("text", text)
            put("images", kotlinx.serialization.json.JsonArray(images.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString()
        val r = api.send("POST", "/api/codex/sessions/$machine/$id/message", body, MessageResult.serializer())
        toast.tryEmit(if (r.mode == "steer") "已插话，会在下一步生效" else "已发送")
        refreshDetail(machine, id)
        refreshList()
    }.onFailure { toast.tryEmit(it.message ?: "发送失败") }.isSuccess

    fun stop(machine: String, id: String) = viewModelScope.launch {
        runCatching { api.raw("POST", "/api/codex/sessions/$machine/$id/stop", "{}") }
            .onSuccess { toast.tryEmit("已停止"); refreshDetail(machine, id); refreshList() }
            .onFailure { toast.tryEmit(it.message ?: "停止失败") }
    }

    fun approve(machine: String, key: String, accept: Boolean, sessionId: String? = null) = viewModelScope.launch {
        val body = buildJsonObject { put("decision", if (accept) "accept" else "decline") }.toString()
        runCatching { api.raw("POST", "/api/codex/approvals/$machine/$key", body) }
            .onSuccess { toast.tryEmit(if (accept) "已批准" else "已拒绝") }
            .onFailure { toast.tryEmit(it.message ?: "操作失败") }
        refreshList()
        sessionId?.let { refreshDetail(machine, it) }
    }

    suspend fun dirs(machine: String): List<String> =
        runCatching { api.get("/api/codex/machines/$machine/dirs", Dirs.serializer()).dirs }.getOrDefault(emptyList())

    suspend fun start(machine: String, cwd: String, text: String, images: List<String> = emptyList()): String? = runCatching {
        val body = buildJsonObject {
            put("cwd", cwd)
            put("text", text)
            put("images", kotlinx.serialization.json.JsonArray(images.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }.toString()
        api.send("POST", "/api/codex/sessions/$machine", body, Started.serializer()).id
    }.onFailure { toast.tryEmit(it.message ?: "创建失败") }.getOrNull().also { refreshList() }
}
