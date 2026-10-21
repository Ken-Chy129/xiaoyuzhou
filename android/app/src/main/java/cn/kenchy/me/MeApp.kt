package cn.kenchy.me

import android.app.Application
import cn.kenchy.me.core.Api
import cn.kenchy.me.core.Prefs

class MeApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var api: Api
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        api = Api(prefs)
    }

    companion object {
        lateinit var instance: MeApp
            private set
    }
}
