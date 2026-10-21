package cn.kenchy.me.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cn.kenchy.me.MeApp
import cn.kenchy.me.codex.CodexScreen
import cn.kenchy.me.codex.CodexVm
import cn.kenchy.me.codex.NewSessionScreen
import cn.kenchy.me.codex.SessionScreen
import cn.kenchy.me.news.NewsScreen
import cn.kenchy.me.news.ArticleScreen
import cn.kenchy.me.todo.ReminderSettingsScreen
import cn.kenchy.me.news.NewsVm
import cn.kenchy.me.savings.RecordEditScreen
import cn.kenchy.me.savings.SavingsScreen
import cn.kenchy.me.savings.SavingsVm
import cn.kenchy.me.me.MeScreen
import cn.kenchy.me.todo.TodoScreen
import cn.kenchy.me.todo.TodoVm
import cn.kenchy.me.todo.TodayStrip
import cn.kenchy.me.todo.dueDate
import cn.kenchy.me.todo.nowLocal
import java.net.URLDecoder
import java.net.URLEncoder

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("news", "资讯", Icons.Outlined.Newspaper),
    Tab("codex", "Codex", Icons.Outlined.Terminal),
    Tab("todo", "待办", Icons.Outlined.TaskAlt),
    Tab("me", "我", Icons.Outlined.Person),
)

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Root(openTarget: String? = null, consumed: () -> Unit = {}) {
    val app = MeApp.instance
    var paired by remember { mutableStateOf(app.prefs.token != null) }
    LaunchedEffect(Unit) {
        app.api.onUnauthorized = {
            app.prefs.token = null
            paired = false
        }
    }
    if (!paired) {
        PairScreen(onPaired = { paired = true })
        return
    }

    val codex: CodexVm = viewModel()
    val savings: SavingsVm = viewModel()
    val news: NewsVm = viewModel()
    val updater: UpdateVm = viewModel()
    val todo: TodoVm = viewModel()

    // 前台时保持实时连接，回到前台时刷新（没有系统推送，见对齐结论）
    LifecycleResumeEffect(Unit) {
        codex.resume()
        updater.check()
        todo.refresh()
        cn.kenchy.me.alerts.Watchdog.kick(app)
        onPauseOrDispose { codex.pause() }
    }

    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }
    val list by codex.list.collectAsState()
    val seenVersion by cn.kenchy.me.codex.Seen.version.collectAsState()
    // Codex 角标：等你处理的 + 未读的
    val waiting = list?.sessions?.distinctBy { "${it.machine}/${it.id}" }?.let { all ->
        @Suppress("UNUSED_EXPRESSION") seenVersion
        all.count { it.state == "waiting" || cn.kenchy.me.codex.Seen.unread(it) }
    } ?: 0
    val todos by todo.items.collectAsState()
    val todoDue = todos?.let { all -> val now = nowLocal(); all.count { it.kind == "todo" && !it.done && it.dueDate()?.let { d -> !d.isAfter(now.toLocalDate()) } == true } } ?: 0
    val update by updater.available.collectAsState()
    val updateMsg by updater.progress.collectAsState()

    fun go(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    // 从通知点进来：待办 / 某个 Codex 会话 / 提醒设置
    LaunchedEffect(openTarget) {
        val t = openTarget ?: return@LaunchedEffect
        when {
            t == "todo" -> go("todo")
            t == "settings/alerts" -> nav.navigate("settings/reminders") { launchSingleTop = true }
            t.startsWith("session/") -> {
                val parts = t.removePrefix("session/").split("/", limit = 2)
                if (parts.size == 2) {
                    go("codex")
                    nav.navigate("session/${enc(parts[0])}/${enc(parts[1])}") { launchSingleTop = true }
                }
            }
        }
        consumed()
    }

    run {
        Scaffold(
            containerColor = T.c.bg,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                androidx.compose.animation.AnimatedVisibility(
                    showBar,
                    enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(260, easing = ease)) { it } + androidx.compose.animation.fadeIn(),
                    // 进二级页时底栏立刻让位：带退场动画的话，那 0.2 秒里页面底部会多留一截底栏高度，输入框先停在上面再掉下来
                    exit = androidx.compose.animation.ExitTransition.None,
                ) { Column {
                update?.let { v ->
                    // 浮在底栏上方的圆角提示条，不再是一整条贴边色块
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp, 0.dp, 12.dp, 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(T.c.accentSoft)
                            .border(1.dp, T.c.accent.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
                            .clickable { updater.install() }
                            .padding(14.dp, 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.SystemUpdate, null, tint = T.c.accent, modifier = Modifier.size(20.dp))
                        Text(
                            "新版本 ${v.versionName}", color = T.c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 10.dp).weight(1f),
                        )
                        Text(updateMsg ?: "更新", color = T.c.accent, fontSize = 13.sp, maxLines = 1)
                    }
                }
                NavigationBar(containerColor = T.c.surface, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { go(t.route) },
                            icon = {
                                val n = when (t.route) { "codex" -> waiting; "todo" -> todoDue; else -> 0 }
                                if (n > 0) {
                                    BadgedBox(badge = { Badge(containerColor = if (t.route == "todo") T.c.accent else T.c.warn) { Text("$n") } }) { Icon(t.icon, null) }
                                } else Icon(t.icon, null)
                            },
                            label = { Text(t.label, fontSize = 12.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = T.c.accent,
                                selectedTextColor = T.c.accent,
                                indicatorColor = T.c.accentSoft,
                                unselectedIconColor = T.c.ink3,
                                unselectedTextColor = T.c.ink3,
                            ),
                        )
                    }
                }
                } }
            },
        ) { pad ->
            // consumeWindowInsets：底栏已经占掉的高度不再算进键盘留白，否则键盘上面会多空一截底栏的高度
            Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
                NavHost(
                    nav, startDestination = "news",
                    enterTransition = { navEnter(initialState.destination.route, targetState.destination.route) },
                    exitTransition = { navExit(initialState.destination.route, targetState.destination.route) },
                    popEnterTransition = { navPopEnter(initialState.destination.route, targetState.destination.route) },
                    popExitTransition = { navPopExit(initialState.destination.route, targetState.destination.route) },
                ) {
                    screen("codex") {
                        CodexScreen(
                            vm = codex,
                            openSession = { m, id -> nav.navigate("session/${enc(m)}/${enc(id)}") },
                            newSession = { nav.navigate("codex/new") },
                        )
                    }
                    screen("codex/new") {
                        NewSessionScreen(codex, back = { nav.popBackStack() }, opened = { m, id ->
                            nav.popBackStack()
                            nav.navigate("session/${enc(m)}/${enc(id)}")
                        })
                    }
                    screen("session/{m}/{id}") { e ->
                        SessionScreen(
                            vm = codex,
                            machine = dec(e.arguments?.getString("m").orEmpty()),
                            id = dec(e.arguments?.getString("id").orEmpty()),
                            back = { nav.popBackStack() },
                        )
                    }
                    screen("todo") {
                        TodoScreen(
                            todo,
                            openLink = { link -> if (link == "savings/new") nav.navigate("savings/edit/new") },
                            openCalendar = { nav.navigate("todo/calendar") },
                        )
                    }
                    screen("todo/calendar") {
                        cn.kenchy.me.todo.TodoCalendarScreen(
                            todo, back = { nav.popBackStack() },
                            openLink = { link -> if (link == "savings/new") nav.navigate("savings/edit/new") },
                        )
                    }
                    screen("notes") {
                        cn.kenchy.me.todo.NotesScreen(todo, back = { nav.popBackStack() })
                    }
                    screen("me") {
                        MeScreen(
                            savings = savings, todoVm = todo, updater = updater,
                            todos = todos,
                            openSavings = { nav.navigate("savings") },
                            openNotes = { nav.navigate("notes") },
                            openReminders = { nav.navigate("settings/reminders") },
                        )
                    }
                    screen("savings") {
                        Locked("资产", back = { nav.popBackStack() }) {
                            SavingsScreen(
                                savings,
                                back = { nav.popBackStack() },
                                edit = { month -> nav.navigate("savings/edit/${month ?: "new"}") },
                            )
                        }
                    }
                    screen("savings/edit/{month}") { e ->
                        val m = e.arguments?.getString("month")
                        Locked("资产", back = { nav.popBackStack() }) {
                            RecordEditScreen(savings, month = m.takeIf { it != "new" }, back = { nav.popBackStack() })
                        }
                    }
                    screen("news/item/{id}") { e ->
                        ArticleScreen(news, id = e.arguments?.getString("id").orEmpty(), back = { nav.popBackStack() })
                    }
                    screen("settings/reminders") {
                        ReminderSettingsScreen(back = { nav.popBackStack() })
                    }
                    screen("news") {
                        NewsScreen(news, open = { id -> nav.navigate("news/item/$id") }, top = {
                            TodayStrip(todos.orEmpty(), open = { go("todo") })
                        })
                    }
                }
                // 站内提醒：前台时代替系统通知，浮在所有页面上方
                InAppBanner(
                    route = route,
                    open = { a ->
                        if (a.kind == "todo") go("todo")
                        else {
                            go("codex")
                            nav.navigate("session/${enc(a.machine)}/${enc(a.sessionId)}") { launchSingleTop = true }
                        }
                    },
                    approve = { a, ok -> a.approvalKey?.let { codex.approve(a.machine, it, ok, a.sessionId) } },
                )
            }
        }
    }
}

/* ---------- 转场 ---------- */

private val tabOrder = listOf("news", "codex", "todo", "me")
private fun isTab(r: String?) = r in tabOrder
private val ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f) // Material 强调减速
private const val DUR = 340

private fun slideIn(fromRight: Boolean) =
    androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(DUR, easing = ease)) { w -> if (fromRight) w / 4 else -w / 4 } +
        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(DUR / 2, delayMillis = 40))

private fun slideOut(toLeft: Boolean) =
    androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(DUR, easing = ease)) { w -> if (toLeft) -w / 8 else w / 4 } +
        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(DUR / 2))

/** Tab 之间：轻微缩放的淡入淡出（不横移，避免和底栏的点击方向打架）；进二级页：从右滑入，返回反向 */
private fun tabFadeIn() =
    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) +
        androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(280, easing = ease), initialScale = 0.985f)

private fun tabFadeOut() = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))

fun navEnter(from: String?, to: String?) = if (isTab(from) && isTab(to)) tabFadeIn() else slideIn(fromRight = true)
fun navExit(from: String?, to: String?) = if (isTab(from) && isTab(to)) tabFadeOut() else slideOut(toLeft = true)
fun navPopEnter(from: String?, to: String?) = if (isTab(from) && isTab(to)) tabFadeIn() else slideIn(fromRight = false)
fun navPopExit(from: String?, to: String?) = if (isTab(from) && isTab(to)) tabFadeOut() else slideOut(toLeft = false)

/** 每个页面铺一层不透明底色，滑动转场时前后两页不会互相透出来 */
private fun NavGraphBuilder.screen(route: String, content: @Composable (NavBackStackEntry) -> Unit) =
    composable(route) { e -> Box(Modifier.fillMaxSize().background(T.c.bg)) { content(e) } }
