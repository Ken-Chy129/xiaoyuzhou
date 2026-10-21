package cn.kenchy.me.codex

import android.content.Context
import cn.kenchy.me.MeApp

/** 输入框草稿：按会话存在本机，最多留 30 条 */
object Drafts {
    private val sp by lazy { MeApp.instance.getSharedPreferences("drafts", Context.MODE_PRIVATE) }

    fun get(key: String): String = sp.getString(key, "").orEmpty()

    fun put(key: String, text: String) {
        val e = sp.edit()
        if (text.isBlank()) e.remove(key) else e.putString(key, text)
        e.apply()
        if (sp.all.size > 30) sp.edit().remove(sp.all.keys.first { it != key }).apply()
    }
}
