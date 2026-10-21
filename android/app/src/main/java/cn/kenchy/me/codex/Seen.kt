package cn.kenchy.me.codex

import android.content.Context
import cn.kenchy.me.MeApp
import cn.kenchy.me.core.SessionSummary
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 会话已读记录（只存在本机）：记每个会话「看过时的更新时间」。
 * 会话在你看过之后又有了新进展，并且已经停下来（完成 / 出错 / 等你处理），就算未读。
 * 正在跑的会话一直在变，不算未读，等它跑完才提示。
 * 第一次装这个版本时，之前的会话都当作已读，免得一下冒出一堆。
 */
object Seen {
    private val sp by lazy { MeApp.instance.getSharedPreferences("seen", Context.MODE_PRIVATE) }
    private val baseline: Long by lazy {
        sp.getLong("_baseline", 0).takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000).also { sp.edit().putLong("_baseline", it).apply() }
    }
    /** 每次已读状态变了就 +1，界面据此重算 */
    val version = MutableStateFlow(0)

    private fun key(machine: String, id: String) = "$machine/$id"

    fun seenAt(machine: String, id: String): Long = maxOf(sp.getLong(key(machine, id), 0), baseline)

    fun unread(s: SessionSummary): Boolean =
        s.state != "running" && s.updatedAt > seenAt(s.machine, s.id)

    fun markSeen(machine: String, id: String, updatedAt: Long) {
        val at = maxOf(updatedAt, System.currentTimeMillis() / 1000)
        if (sp.getLong(key(machine, id), 0) >= at) return
        val e = sp.edit().putLong(key(machine, id), at)
        // 只留最近 300 条
        if (sp.all.size > 320) sp.all.entries.filter { it.key != "_baseline" }.sortedBy { (it.value as? Long) ?: 0 }.take(40).forEach { e.remove(it.key) }
        e.apply()
        version.value++
        cn.kenchy.me.alerts.AlertNotify.cancelFor(MeApp.instance, machine, id)
    }

    fun markAllSeen(list: List<SessionSummary>) {
        val e = sp.edit()
        list.forEach { e.putLong(key(it.machine, it.id), maxOf(it.updatedAt, System.currentTimeMillis() / 1000)) }
        e.apply()
        version.value++
        list.forEach { cn.kenchy.me.alerts.AlertNotify.cancelFor(MeApp.instance, it.machine, it.id) }
    }
}
