package cn.kenchy.me.todo

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.kenchy.me.core.ago
import cn.kenchy.me.ui.Card
import cn.kenchy.me.ui.Gap
import cn.kenchy.me.ui.Loading
import cn.kenchy.me.ui.T
import kotlinx.coroutines.launch

/** 备忘：随手记的东西，没有完成状态。重点的排前面，其余按最近修改 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(vm: TodoVm, back: () -> Unit) {
    val c = T.c
    val all by vm.items.collectAsState()
    var editing by remember { mutableStateOf<Todo?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.toast.collect { snack.showSnackbar(it) } }

    val notes = all?.filter { it.kind == "note" }?.sortedWith(compareBy<Todo>({ !it.pinned }, { -it.updatedAt }))
    Box(Modifier.fillMaxSize().background(c.bg)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(18.dp, 0.dp, 18.dp, 104.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = c.ink) }
                    Text("备忘", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (notes != null) Text("${notes.size} 条", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                }
            }
            when {
                notes == null -> item { Box(Modifier.padding(48.dp)) { Loading() } }
                notes.isEmpty() -> item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有备忘", color = c.ink2, fontSize = 15.sp)
                        Gap(8.dp)
                        Text("账号、地址、想法……随手记在这里", color = c.ink3, fontSize = 13.sp)
                    }
                }
                else -> items(notes, key = { it.id }) { n ->
                    Card(padding = PaddingValues(0.dp), modifier = Modifier.animateItem()) {
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(onClick = { editing = n }, onLongClick = { editing = n })
                                .padding(start = 16.dp, top = 14.dp, bottom = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(n.text, color = c.ink, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                                Gap(6.dp)
                                Text("${ago(n.updatedAt)}修改", color = c.ink3, fontSize = 12.sp)
                            }
                            IconButton(onClick = { vm.togglePin(n) }, modifier = Modifier.size(40.dp)) {
                                if (n.pinned) Icon(Icons.Filled.Star, "取消重点", tint = c.salary, modifier = Modifier.size(20.dp))
                                else Icon(Icons.Outlined.StarBorder, "标为重点", tint = c.line, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
        androidx.compose.material3.FloatingActionButton(
            onClick = { editing = Todo(id = "", kind = "note") },
            containerColor = c.accent, contentColor = c.bg,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
        ) { Icon(Icons.Outlined.Add, "新建备忘") }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
    }

    editing?.let { t ->
        TodoEditor(t, vm, onClose = { editing = null }, onDelete = {
            editing = null
            vm.delete(t)
            scope.launch { if (snack.showSnackbar("已删除", actionLabel = "撤销") == SnackbarResult.ActionPerformed) vm.restore(t) }
        })
    }
}
