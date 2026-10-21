package cn.kenchy.me

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import cn.kenchy.me.ui.MeTheme
import cn.kenchy.me.ui.Root

class MainActivity : FragmentActivity() {
    private val target = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        target.value = intent?.getStringExtra("open")
        setContent { MeTheme { Root(openTarget = target.value, consumed = { target.value = null }) } }
    }

    /** 切到后台：资产页重新上锁（旋转屏幕之类的配置变化不算） */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) cn.kenchy.me.ui.Lock.relock()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("open")?.let { target.value = it }
    }
}
