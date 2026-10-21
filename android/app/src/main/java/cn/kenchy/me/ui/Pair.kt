package cn.kenchy.me.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import cn.kenchy.me.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.MeApp
import cn.kenchy.me.core.PairResult
import cn.kenchy.me.core.json
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 首次打开：输入服务器上生成的 6 位配对码，换一个长期设备凭证（ADR 0004） */
@Composable
fun PairScreen(onPaired: () -> Unit) {
    val app = MeApp.instance
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        busy = true
        error = null
        scope.launch {
            runCatching {
                val body = buildJsonObject {
                    put("code", code)
                    put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
                }.toString()
                app.api.send("POST", "/api/pair", body, PairResult.serializer())
            }.onSuccess {
                app.prefs.token = it.token
                onPaired()
            }.onFailure { error = it.message }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.mipmap.ic_launcher), null,
            modifier = Modifier.size(72.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)),
        )
        Gap(16.dp)
        Text("小宇宙", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = T.c.ink)
        Gap(12.dp)
        Text("连接到你的服务器", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = T.c.ink)
        Gap(8.dp)
        Text(
            "在服务器上运行 node server/cli.mjs pair，把显示的 6 位配对码填在这里。配对一次即可，之后打开 App 不用再登录。",
            color = T.c.ink2,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
        Gap(28.dp)
        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter(Char::isDigit).take(6); error = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("000000", style = Num.copy(fontSize = 24.sp, letterSpacing = 8.sp), color = T.c.ink3) },
            textStyle = Num.copy(fontSize = 24.sp, letterSpacing = 8.sp, textAlign = TextAlign.Start, color = T.c.ink),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = error != null,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = T.c.accent, unfocusedBorderColor = T.c.line),
        )
        error?.let {
            Gap(8.dp)
            Text(it, color = T.c.danger, fontSize = 13.sp)
        }
        Gap(20.dp)
        Button(
            onClick = ::submit,
            enabled = code.length == 6 && !busy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = T.c.accent),
        ) { Text(if (busy) "配对中…" else "配对", modifier = Modifier.padding(vertical = 6.dp)) }
    }
}
