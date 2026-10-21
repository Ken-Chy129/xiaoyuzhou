package cn.kenchy.me.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * 资产这类敏感页的门：指纹（或锁屏密码）通过才显示内容。
 * 通过后 3 分钟内再进不用重新验证；App 切到后台就重新上锁。
 */
object Lock {
    private const val GRACE_MS = 3 * 60_000L
    /** 上次验证通过的时间；界面订阅它，切后台被清零时正在显示的资产页也会立刻盖上 */
    val unlockedAt = kotlinx.coroutines.flow.MutableStateFlow(0L)

    val unlocked: Boolean get() = System.currentTimeMillis() - unlockedAt.value < GRACE_MS
    fun relock() { unlockedAt.value = 0 }
    internal fun markUnlocked() { unlockedAt.value = System.currentTimeMillis() }

    private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** 手机上有没有可用的验证方式（录了指纹或设了锁屏密码） */
    fun available(ctx: Context) = BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(ctx: Context, title: String, onOk: () -> Unit, onFail: (String?) -> Unit) {
        val act = ctx.findActivity() ?: return onFail("无法弹出验证")
        val p = BiometricPrompt(act, ContextCompat.getMainExecutor(ctx), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { markUnlocked(); onOk() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onFail(if (cancelled) null else errString.toString())
            }
        })
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("查看$title")
                .setAllowedAuthenticators(AUTH)
                .setConfirmationRequired(false)
                .build(),
        )
    }

    private fun Context.findActivity(): FragmentActivity? {
        var c: Context? = this
        while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
        return null
    }
}

/** 包住敏感内容：没验证时显示锁屏页并自动弹指纹；通过后指纹变成对勾，再淡入内容 */
@Composable
fun Locked(title: String, back: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val at by Lock.unlockedAt.collectAsState()
    val noLock = remember { !Lock.available(ctx) } // 手机没设任何锁屏：没法验证，直接放行
    var msg by remember { mutableStateOf<String?>(null) }
    var ok by remember { mutableStateOf(false) }
    var shake by remember { mutableStateOf(0) }
    // 进门时已过宽限期就先上锁；看的过程中不会因为过了 3 分钟被打断，只有切后台才重新上锁
    val wasUnlocked = remember { if (!Lock.unlocked) Lock.relock(); Lock.unlocked }
    var showContent by remember { mutableStateOf(wasUnlocked || noLock) }
    // 切后台被重新上锁：盖回锁屏
    LaunchedEffect(at) { if (at == 0L && !noLock) { showContent = false; ok = false } }
    fun ask() {
        msg = null
        Lock.prompt(ctx, title, onOk = { ok = true }, onFail = { e -> msg = e; if (e != null) shake++ })
    }
    LaunchedEffect(ok) {
        if (ok) { kotlinx.coroutines.delay(420); showContent = true }
    }

    androidx.compose.animation.AnimatedContent(
        targetState = showContent,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260, delayMillis = 60)) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(260, delayMillis = 60), initialScale = 0.98f))
                .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)))
        },
        label = "lock",
    ) { open ->
        if (open) { content(); return@AnimatedContent }
        LaunchedEffect(Unit) { if (!ok) { kotlinx.coroutines.delay(320); ask() } } // 等转场动画走完再弹
        LockFace(title, msg, ok, shake, back, ::ask)
    }
}

@Composable
private fun LockFace(title: String, msg: String?, ok: Boolean, shake: Int, back: () -> Unit, ask: () -> Unit) {
    val c = T.c
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "halo")
    val breathe by inf.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1800, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "breathe",
    )
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(shake) {
        if (shake == 0) return@LaunchedEffect
        for (x in listOf(10f, -8f, 6f, -4f, 0f)) offset.animateTo(x, androidx.compose.animation.core.tween(55))
    }
    val tint by androidx.compose.animation.animateColorAsState(
        when { ok -> c.accent; msg != null -> c.danger; else -> c.accent }, label = "tint",
    )
    val iconScale by androidx.compose.animation.core.animateFloatAsState(
        if (ok) 1.08f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 380f), label = "pop",
    )

    Box(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.bg, c.accentSoft.copy(alpha = 0.55f), c.bg)),
        ).statusBarsPadding(),
    ) {
        IconButton(onClick = back, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.ink) }

        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 32.dp).padding(bottom = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Lock, null, tint = c.ink3, modifier = Modifier.size(16.dp))
            Gap(10.dp)
            Text(title, color = c.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Gap(8.dp)
            androidx.compose.animation.AnimatedContent(
                targetState = when { ok -> "已验证"; msg != null -> msg; else -> "仅你本人可见" },
                transitionSpec = { androidx.compose.animation.fadeIn().togetherWith(androidx.compose.animation.fadeOut()) },
                label = "msg",
            ) { m ->
                Text(m, color = if (msg != null && !ok) c.danger else c.ink3, fontSize = 13.sp)
            }
            Gap(56.dp)

            // 指纹：外圈呼吸光晕，点它重新验证
            Box(
                Modifier.size(168.dp).graphicsLayer { translationX = offset.value * density },
                contentAlignment = Alignment.Center,
            ) {
                if (!ok) {
                    Box(
                        Modifier.size(168.dp).graphicsLayer {
                            val s = 0.82f + 0.18f * breathe
                            scaleX = s; scaleY = s; alpha = 0.55f * (1f - breathe) + 0.15f
                        }.clip(CircleShape).background(tint.copy(alpha = 0.16f)),
                    )
                }
                Box(
                    Modifier.size(96.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale }
                        .clip(CircleShape)
                        .background(if (ok) c.accent else c.surface)
                        .border(1.dp, if (ok) c.accent else c.line, CircleShape)
                        .clickable(enabled = !ok, onClick = ask),
                    contentAlignment = Alignment.Center,
                ) {
                    // Crossfade 默认左上对齐：两种图标尺寸不同时对勾会偏，内容一律铺满再居中
                    androidx.compose.animation.Crossfade(ok, Modifier.fillMaxSize(), label = "icon") { done ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (done) CheckMark(c.bg) else Icon(Icons.Outlined.Fingerprint, "验证指纹", tint = tint, modifier = Modifier.size(48.dp))
                        }
                    }
                }
            }
            Gap(28.dp)
            Text(
                if (ok) " " else "轻触指纹解锁",
                color = c.ink2, fontSize = 13.sp,
                modifier = Modifier.alpha(if (ok) 0f else 1f),
            )
        }

        Text(
            "也可以用锁屏密码 · 3 分钟内免验证",
            color = c.ink3, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 28.dp),
        )
    }
}

/** 对勾：几何居中（图标字体里的对勾本身偏左下），描线动画画出来 */
@Composable
private fun CheckMark(color: androidx.compose.ui.graphics.Color) {
    val p = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, androidx.compose.animation.core.tween(320, delayMillis = 60, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    androidx.compose.foundation.Canvas(Modifier.size(40.dp)) {
        val w = size.width
        // 三个点围出的外框正好以画布中心对称：x 0.2→0.8，y 0.3→0.72（视觉重心再上提一点）
        val a = androidx.compose.ui.geometry.Offset(w * 0.22f, w * 0.52f)
        val b = androidx.compose.ui.geometry.Offset(w * 0.42f, w * 0.70f)
        val e = androidx.compose.ui.geometry.Offset(w * 0.78f, w * 0.32f)
        val l1 = (b - a).getDistance()
        val l2 = (e - b).getDistance()
        val d = p.value * (l1 + l2)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(a.x, a.y)
            if (d <= l1) lineTo(a.x + (b.x - a.x) * d / l1, a.y + (b.y - a.y) * d / l1)
            else {
                lineTo(b.x, b.y)
                val t = (d - l1) / l2
                lineTo(b.x + (e.x - b.x) * t, b.y + (e.y - b.y) * t)
            }
        }
        drawPath(
            path, color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w * 0.09f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}
