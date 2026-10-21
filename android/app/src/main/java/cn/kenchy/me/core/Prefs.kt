package cn.kenchy.me.core

import android.content.Context

/** 设备凭证等少量本地状态。凭证只在本机，卸载即失效（ADR 0004） */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("me", Context.MODE_PRIVATE)

    var token: String?
        get() = sp.getString("token", null)
        set(v) = sp.edit().putString("token", v).apply()


    var cachedHome: String?
        get() = sp.getString("home", null)
        set(v) = sp.edit().putString("home", v).apply()
}
