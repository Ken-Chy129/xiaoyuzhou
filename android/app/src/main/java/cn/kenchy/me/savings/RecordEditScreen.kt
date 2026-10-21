package cn.kenchy.me.savings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.core.Entry
import cn.kenchy.me.core.money
import cn.kenchy.me.core.monthLabel
import cn.kenchy.me.core.pct
import cn.kenchy.me.ui.Card
import cn.kenchy.me.ui.Gap
import cn.kenchy.me.ui.KV
import cn.kenchy.me.ui.Label
import cn.kenchy.me.ui.T
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private data class Field(val key: String, val label: String, val hint: String = "")

private val incomeFields = listOf(
    Field("salary", "工资收入", "税前，含奖金"),
    Field("bonus", "其中奖金"),
    Field("other", "附加收入", "补贴、退税等"),
)
private val deductFields = listOf(
    Field("insurance", "五险一金", "个人缴纳部分"),
    Field("tax", "个税"),
    Field("family", "家庭补贴"),
    Field("fund", "公积金缴存", "个人 + 公司双边"),
)
private val saveFields = listOf(
    Field("fixed", "定存"),
    Field("personal", "个人存储"),
    Field("fundWithdrawal", "其中来自公积金提取", "这部分不算收入存下"),
)

private fun Entry.value(k: String) = when (k) {
    "salary" -> salary; "bonus" -> bonus; "other" -> other; "insurance" -> insurance; "tax" -> tax
    "family" -> family; "fund" -> fund; "fixed" -> fixed; "personal" -> personal; "fundWithdrawal" -> fundWithdrawal
    else -> 0.0
}

private fun fmt(v: Double) = if (v == 0.0) "" else if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v)

@Composable
fun RecordEditScreen(vm: SavingsVm, month: String?, back: () -> Unit) {
    val data by vm.data.collectAsState()
    val records = data?.records.orEmpty().sortedBy { it.month }
    val existing = month?.let { m -> records.find { it.month == m } }
    // 新增时默认下一个月，并预填上个月的固定项（五险一金、公积金、家庭补贴），方便快速记
    val template = existing ?: records.lastOrNull()
    val defaultMonth = existing?.month ?: records.lastOrNull()?.month?.let { addMonths(it, 1) }
        ?: LocalDate.now(ZoneId.of("Asia/Shanghai")).toString().substring(0, 7)

    var monthText by remember { mutableStateOf(defaultMonth) }
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            val carry = setOf("insurance", "fund", "family")
            (incomeFields + deductFields + saveFields).forEach { f ->
                put(f.key, if (existing != null || f.key in carry) template?.let { fmt(it.value(f.key)) }.orEmpty() else "")
            }
        }
    }
    var special by remember { mutableStateOf(existing?.special ?: false) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun num(k: String) = values[k]?.toDoubleOrNull() ?: 0.0
    val entry = Entry(
        id = existing?.id.orEmpty(),
        month = monthText,
        salary = num("salary"), bonus = num("bonus"), insurance = num("insurance"), tax = num("tax"), other = num("other"),
        family = num("family"), fund = num("fund"), fixed = num("fixed"), personal = num("personal"),
        fundWithdrawal = num("fundWithdrawal"), special = special, note = note,
    )
    val preview = derive(listOf(entry), data?.expenses.orEmpty()).first()

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = T.c.ink) }
            Text(if (existing != null) monthLabel(existing.month) else "记一个月", color = T.c.ink, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "删除", tint = T.c.ink3) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp, 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Input("月份", monthText, { monthText = it }, hint = "YYYY-MM", number = false)
            Group("收入", incomeFields, values)
            Group("扣除与支出", deductFields, values)
            Group("存款", saveFields, values)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("特殊月", color = T.c.ink, fontSize = 15.sp)
                    Text("离职、入职等，不进月均", color = T.c.ink3, fontSize = 12.sp)
                }
                Switch(special, { special = it }, colors = SwitchDefaults.colors(checkedTrackColor = T.c.accent))
            }
            Input("备注", note, { note = it }, number = false)
            Card {
                Label("预览")
                KV("到手", money(preview.takeHome))
                KV("存下", money(preview.saved))
                KV("储蓄率", pct(preview.rate), money = false)
            }
            error?.let { Text(it, color = T.c.danger, fontSize = 13.sp) }
        }
        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    val err = vm.save(entry)
                    busy = false
                    if (err == null) back() else error = err
                }
            },
            enabled = !busy && Regex("""^\d{4}-\d{2}$""").matches(monthText),
            colors = ButtonDefaults.buttonColors(containerColor = T.c.accent),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        ) { Text(if (busy) "保存中…" else "保存", modifier = Modifier.padding(vertical = 6.dp)) }
    }

    if (confirmDelete && existing != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除 ${monthLabel(existing.month)}？") },
        text = { Text("服务器会自动保留备份，但 App 里没有撤销。") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch { vm.delete(existing.id)?.let { error = it } ?: back() }
            }) { Text("删除", color = T.c.danger) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = T.c.ink2) } },
    )
}

@Composable
private fun Group(title: String, fields: List<Field>, values: MutableMap<String, String>) {
    Text(title, color = T.c.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    fields.forEach { f -> Input(f.label, values[f.key].orEmpty(), { values[f.key] = it }, hint = f.hint) }
}

@Composable
private fun Input(label: String, value: String, onChange: (String) -> Unit, hint: String = "", number: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(if (number) v.filter { it.isDigit() || it == '.' } else v) },
        label = { Text(label) },
        supportingText = if (hint.isNotBlank()) ({ Text(hint, fontSize = 11.sp) }) else null,
        singleLine = true,
        textStyle = cn.kenchy.me.ui.Num.copy(fontSize = 16.sp, color = T.c.ink),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = T.c.accent, unfocusedBorderColor = T.c.line, focusedLabelColor = T.c.accent),
    )
}
