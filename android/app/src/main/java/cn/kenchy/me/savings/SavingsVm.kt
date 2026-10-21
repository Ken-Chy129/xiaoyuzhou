package cn.kenchy.me.savings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.kenchy.me.MeApp
import cn.kenchy.me.core.Api
import cn.kenchy.me.core.Entry
import cn.kenchy.me.core.Ledger
import cn.kenchy.me.core.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SavingsVm(private val api: Api, autoload: Boolean = true) : ViewModel() {
    constructor() : this(MeApp.instance.api)

    val data = MutableStateFlow<Ledger?>(null)
    val rows = MutableStateFlow<List<Row>>(emptyList())
    val error = MutableStateFlow<String?>(null)

    init {
        if (autoload) refresh()
    }

    fun set(l: Ledger) {
        data.value = l
        rows.value = derive(l.records, l.expenses, l.settings)
        error.value = null
    }

    fun refresh() = viewModelScope.launch {
        runCatching { api.get("/api/savings/data", Ledger.serializer()) }
            .onSuccess(::set)
            .onFailure { error.value = it.message }
    }

    /** 新增或修改一个月；返回错误信息，成功返回 null */
    suspend fun save(entry: Entry): String? = runCatching {
        val body = json.encodeToString(Entry.serializer(), entry)
        val l = if (entry.id.isBlank()) api.send("POST", "/api/savings/records", body, Ledger.serializer())
        else api.send("PATCH", "/api/savings/records/${entry.id}", body, Ledger.serializer())
        set(l)
    }.exceptionOrNull()?.message

    suspend fun delete(id: String): String? = runCatching {
        set(json.decodeFromString(Ledger.serializer(), api.raw("DELETE", "/api/savings/records/$id")))
    }.exceptionOrNull()?.message
}
