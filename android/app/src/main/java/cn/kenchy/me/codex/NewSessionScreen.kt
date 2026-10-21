package cn.kenchy.me.codex

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.ui.Gap
import cn.kenchy.me.ui.Label
import cn.kenchy.me.ui.T
import kotlinx.coroutines.launch

/** 在某台机器的最近目录里开一个新会话 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NewSessionScreen(vm: CodexVm, back: () -> Unit, opened: (String, String) -> Unit) {
    val list by vm.list.collectAsState()
    val machines = list?.machines.orEmpty().filter { it.online }
    var machine by remember { mutableStateOf(machines.firstOrNull()?.name) }
    var dirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var cwd by remember { mutableStateOf("") }
    var text by remember { mutableStateOf(Drafts.get("new")) }
    var busy by remember { mutableStateOf(false) }
    val attachments = cn.kenchy.me.ui.rememberAttachments("new")
    val scope = rememberCoroutineScope()
    Toasts(vm)

    LaunchedEffect(machine) {
        dirs = emptyList()
        machine?.let { m ->
            dirs = vm.dirs(m)
            if (cwd.isBlank()) cwd = dirs.firstOrNull().orEmpty()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = T.c.ink) }
            Text("新会话", color = T.c.ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Label("机器")
            Gap(8.dp)
            if (machines.isEmpty()) Text("没有在线的机器", color = T.c.ink3, fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                machines.forEach { m -> Option(m.name, machine == m.name) { machine = m.name; cwd = "" } }
            }
            Gap(20.dp)
            Label("目录")
            Gap(8.dp)
            dirs.forEach { d ->
                Text(
                    d,
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (cwd == d) T.c.accentSoft else T.c.surface)
                        .clickable { cwd = d }
                        .padding(12.dp, 10.dp),
                    color = if (cwd == d) T.c.accent else T.c.ink2,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Gap(4.dp)
            }
            Gap(4.dp)
            OutlinedTextField(
                value = cwd,
                onValueChange = { cwd = it },
                label = { Text("或者填一个路径") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = T.c.accent, unfocusedBorderColor = T.c.line),
            )
            Gap(16.dp)
            Text("在下面写要做什么，可以带截图。会话会出现在这台机器的 Codex 里，回到电脑上也能接着做。", color = T.c.ink3, fontSize = 12.sp, lineHeight = 18.sp)
        }
        val ready = machine != null && cwd.isNotBlank()
        cn.kenchy.me.ui.Composer(
            text = text,
            onText = { text = it; Drafts.put("new", it) },
            placeholder = if (ready) "要做什么…" else "先选机器和目录",
            attachments = attachments,
            sending = busy,
            onSend = {
                val m = machine
                if (m != null && cwd.isNotBlank() && !busy) {
                    busy = true
                    scope.launch {
                        vm.start(m, cwd.trim(), text.trim(), attachments.dataUrls)?.let { Drafts.put("new", ""); attachments.clear(); opened(m, it) }
                        busy = false
                    }
                }
            },
            modifier = Modifier.background(T.c.surface).navigationBarsPadding().padding(12.dp, 8.dp, 12.dp, 10.dp),
        )
    }
}

@Composable
private fun Option(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(50)).background(if (on) T.c.accent else T.c.line2).clickable(onClick = onClick).padding(14.dp, 7.dp),
        color = if (on) T.c.bg else T.c.ink2,
        fontSize = 14.sp,
    )
}
