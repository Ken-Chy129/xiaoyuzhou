package cn.kenchy.me.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import kotlinx.coroutines.launch

/** 输入框里待发送的图片，交给调用方持有，发送成功后清空；给了 draftKey 就跟着草稿存在本机 */
class Attachments(private val draftKey: String? = null) {
    val items = mutableStateListOf<PickedImage>()
    var loading by androidx.compose.runtime.mutableIntStateOf(0)
    val dataUrls: List<String> get() = items.map { it.dataUrl }
    fun add(img: PickedImage) { items.add(img); save() }
    fun remove(img: PickedImage) { items.remove(img); save() }
    fun clear() { items.clear(); save() }
    private fun save() { draftKey?.let { DraftImages.save(it, dataUrls) } }

    /** 把上次没发出去的图片读回来 */
    suspend fun restore() {
        val key = draftKey ?: return
        if (items.isNotEmpty()) return
        val saved = DraftImages.load(key)
        if (saved.isNotEmpty() && items.isEmpty()) items.addAll(saved)
    }
}

@Composable
fun rememberAttachments(draftKey: String? = null): Attachments {
    val a = remember(draftKey) { Attachments(draftKey) }
    androidx.compose.runtime.LaunchedEffect(a) { a.restore() }
    return a
}

/**
 * 统一的输入条：[图片] [文字……] [语音 / 发送]
 * - 空的时候右边是麦克风；有内容时变成发送
 * - 语音时整条变成「正在听」：波形 + 实时文字，点停止结束
 */
@Composable
fun Composer(
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    attachments: Attachments? = null,
    sending: Boolean = false,
    maxLines: Int = 6,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = T.c
    val ctx = LocalContext.current
    val voice = rememberVoice(hold = true)
    var cancelling by remember { androidx.compose.runtime.mutableStateOf(false) }
    val onVoiceText: (String) -> Unit = { onText(appendVoice(text, it)) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        val a = attachments ?: return@rememberLauncherForActivityResult
        val room = 6 - a.items.size
        if (uris.size > room) Toast.makeText(ctx, "一次最多 6 张", Toast.LENGTH_SHORT).show()
        uris.take(room).forEach { uri ->
            a.loading++
            scope.launch {
                runCatching { loadImage(ctx, uri) }
                    .onSuccess { a.add(it) }
                    .onFailure { Toast.makeText(ctx, "图片读取失败", Toast.LENGTH_SHORT).show() }
                a.loading--
            }
        }
    }
    val hasContent = text.isNotBlank() || (attachments?.items?.isNotEmpty() == true)
    val canSend = hasContent && !sending && (attachments?.loading ?: 0) == 0
    var viewing by remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    viewing?.let { start ->
        val list = attachments?.items.orEmpty()
        if (list.isNotEmpty()) ImageViewer(list.map { ViewImage.DataUrl(it.dataUrl) }, start) { viewing = null }
        else viewing = null
    }

    Column(modifier.fillMaxWidth()) {
        // 待发送的图片
        if (attachments != null && (attachments.items.isNotEmpty() || attachments.loading > 0)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.items.forEachIndexed { index, img ->
                    Box {
                        Image(
                            img.thumb.asImageBitmap(), null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp))
                                .clickable { viewing = index },
                        )
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.65f))
                                .clickable { attachments.remove(img) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Close, "移除", tint = c.bg, modifier = Modifier.size(13.dp)) }
                    }
                }
                repeat(attachments.loading) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(c.line2), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        val border by animateColorAsState(
            when { voice.listening && cancelling -> c.danger; voice.listening -> c.accent; else -> c.line }, label = "border",
        )
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(c.bg)
                .border(1.dp, border, RoundedCornerShape(24.dp))
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            leading?.let {
                Box(Modifier.height(40.dp).padding(start = 4.dp), contentAlignment = Alignment.Center) { it() }
            }
            if (attachments != null && !voice.listening) {
                RoundIcon(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Outlined.AddPhotoAlternate, "添加图片", tint = c.ink2, modifier = Modifier.size(22.dp))
                }
            } else if (leading == null) {
                Box(Modifier.width(12.dp))
            }

            Box(Modifier.weight(1f).heightIn(min = 40.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.CenterStart) {
                if (voice.listening || voice.finishing) {
                    ListeningView(text, voice.live, voice.level, cancelling, voice.finishing)
                } else {
                    BasicTextField(
                        value = text,
                        onValueChange = onText,
                        textStyle = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, color = c.ink),
                        cursorBrush = SolidColor(c.accent),
                        maxLines = maxLines,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        decorationBox = { inner ->
                            if (text.isEmpty()) Text(placeholder, color = c.ink3, fontSize = 16.sp, maxLines = 1)
                            inner()
                        },
                    )
                }
            }

            // 麦克风始终是同一个节点：按住期间界面切到「正在听」也不会打断手势
            Row(verticalAlignment = Alignment.CenterVertically) {
                HoldMic(voice, onVoiceText, { cancelling = it }, big = !hasContent || voice.listening, active = voice.listening, cancelling = cancelling)
                if (hasContent && !voice.listening) {
                    RoundIcon(onClick = { if (canSend) onSend() }, bg = if (canSend) c.accent else c.line2) {
                        if (sending) CircularProgressIndicator(color = c.bg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = if (canSend) c.bg else c.ink3, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
    }
}

/**
 * 按住说话：按下开始录，松开结束并把文字放进输入框；按住时往上滑超过一段距离 → 松开取消。
 * 轻点一下只提示「按住说话」。
 */
@Composable
private fun HoldMic(
    voice: VoiceState,
    onText: (String) -> Unit,
    onCancelling: (Boolean) -> Unit,
    big: Boolean,
    active: Boolean,
    cancelling: Boolean,
) {
    val c = T.c
    val ctx = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val cancelPx = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    val latestText by androidx.compose.runtime.rememberUpdatedState(onText)
    val scale by animateFloatAsState(if (active) 1.12f else 1f, tween(160), label = "micScale")
    val bg by animateColorAsState(
        when { active && cancelling -> c.danger; active -> c.accent; else -> androidx.compose.ui.graphics.Color.Transparent }, label = "micBg",
    )
    Box(
        Modifier.size(if (big) 40.dp else 34.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(bg)
            .pointerInput(voice) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val startedAt = System.currentTimeMillis()
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    voice.start { latestText(it) }
                    var cancel = false
                    onCancelling(false)
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val up = down.position.y - ch.position.y > cancelPx
                        if (up != cancel) {
                            cancel = up
                            onCancelling(up)
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        }
                        ch.consume()
                    }
                    onCancelling(false)
                    when {
                        cancel -> voice.cancel()
                        System.currentTimeMillis() - startedAt < 350 -> {
                            voice.cancel()
                            Toast.makeText(ctx, "按住说话，松开结束", Toast.LENGTH_SHORT).show()
                        }
                        else -> voice.stop()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Mic, "按住说话",
            tint = if (active) c.bg else if (big) c.ink2 else c.ink3,
            modifier = Modifier.size(if (big) 22.dp else 19.dp),
        )
    }
}

@Composable
private fun RoundIcon(onClick: () -> Unit, bg: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Transparent, content: @Composable () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 正在听：已有文字（淡）+ 实时识别（深）+ 波形 */
@Composable
private fun ListeningView(existing: String, partial: String, level: Float, cancelling: Boolean, finishing: Boolean = false) {
    val c = T.c
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wave(level)
            Text(
                when { finishing -> "  识别中…"; cancelling -> "  松开取消"; else -> "  松开结束 · 上滑取消" },
                color = if (cancelling && !finishing) c.danger else c.accent, fontSize = 12.sp,
            )
        }
        val shown = partial.ifEmpty { if (existing.isBlank() && !finishing) "请说话…" else "" }
        if (existing.isNotBlank() || shown.isNotEmpty()) {
            // 说长了只看最后几行：实时文字一直往下长，开头的自动滚走
            val scroll = rememberScrollState()
            androidx.compose.runtime.LaunchedEffect(shown) { scroll.scrollTo(scroll.maxValue) }
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    if (existing.isNotBlank()) {
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = c.ink3))
                        append(existing.trimEnd())
                        if (partial.isNotEmpty()) append(" ")
                        pop()
                    }
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = if (partial.isEmpty()) c.ink3 else c.ink))
                    append(shown)
                    pop()
                },
                fontSize = 16.sp, lineHeight = 23.sp,
                modifier = Modifier.padding(top = 4.dp).heightIn(max = 140.dp).verticalScroll(scroll),
            )
        }
    }
}

@Composable
private fun Wave(level: Float) {
    val c = T.c
    val t = rememberInfiniteTransition(label = "wave")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "phase")
    val lv by animateFloatAsState(level, tween(120), label = "lv")
    Row(Modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(0.5f, 0.8f, 1f, 0.7f, 0.45f).forEachIndexed { i, w ->
            val idle = 0.25f + 0.2f * (if (i % 2 == 0) phase else 1 - phase)
            val h = (idle + lv * w).coerceIn(0.2f, 1f)
            Box(Modifier.width(3.dp).fillMaxHeight(h).clip(RoundedCornerShape(2.dp)).background(c.accent))
        }
    }
}
