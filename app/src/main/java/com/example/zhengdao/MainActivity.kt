// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.AppState
import com.example.zhengdao.ui.HomeScreen
import com.example.zhengdao.ui.PluginsScreen
import com.example.zhengdao.ui.SettingsScreen
import kotlinx.coroutines.launch
import com.example.zhengdao.ui.WelcomeScreen

/** App 自更新检查端点（GitHub Releases 最新发布）。 */
private const val APP_RELEASES_API = "https://api.github.com/repos/pisces19860207/zhengdao/releases/latest"

/**
 * 应用入口：Compose Navigation 承载 欢迎页 → 首页（Agent/终端 双 Tab）。
 * 终端本体是独立 Activity（TerminalActivity，零改动复用），从首页 Tab 启动。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 边到边渲染（v1.1 第三阶段「输入框与手机体验」）：
        // 必须与 ComposerBar 的 Modifier.imePadding() 配套，键盘弹出时输入框才不被顶掉
        // ——非 edge-to-edge 时系统自行 resize window，Compose 读不到 IME inset，imePadding 形同虚设。
        // 系统栏遮挡由各页面自行补偿：home 走 Scaffold（已处理）；settings 加 statusBarsPadding；
        // welcome 内容居中且有内边距，天然安全。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // ⚠️ RunLog 此前**只在 TerminalActivity 里 init**：WebView 时代用户必然进终端页，
        //    所以日志一直有；Compose 原生 UI 流程**根本不进终端** → appContext == null
        //    → RunLog.log() 开头的 `appContext ?: return` 把所有日志**静默丢掉**。
        //    后果：跨多轮分支构建 RunLog 零输出，SSE 断连原因完全无法定位。
        //    「失败可见」的前提是日志能落盘，故必须在 App 入口无条件初始化。
        RunLog.init(applicationContext)

        // 系统版本兜底门槛（用户第四批）：minSdk=35 已拦住安装，这里双保险
        // 应对旁加载极端场景；不可取消，确定即退出，不崩溃。
        if (android.os.Build.VERSION.SDK_INT < 36) {
            android.app.AlertDialog.Builder(this)
                .setTitle("系统版本过低")
                .setMessage("证道需要安卓 16 或更高版本（当前安卓 ${android.os.Build.VERSION.RELEASE}）。")
                .setCancelable(false)
                .setPositiveButton("确定") { _, _ -> finish() }
                .show()
            return
        }

        // App 自更新（用户第四批）：启动后台查 releases，网络失败静默忽略，不打断用户
        checkAppUpdateInBackground()

        // 存储运行时权限（第 0 步修复，2026-10-06）：此前全 App 没有任何请求代码，
        // WRITE 靠用户手点设置授予、READ 因 manifest maxSdkVersion=32 帽子从未可授，
        // Android 16 上共享存储读写全被 FUSE 拒——hermes"只能写不能读"的根因。
        // 冷启动缺哪个补哪个；拒绝不打断使用（bind 会静默跳过，终端照常）。
        runCatching {
            val needed = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ).filter {
                androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                androidx.core.app.ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
            }
        }

        // 存储主路径引导（E-005 修订 / P1，2026-10-06）：MANAGE_EXTERNAL_STORAGE 升为
        // 正式主路径。首启且未授权时主动引导用户到「所有文件访问」设置页（带包名），
        // 失败回退通用设置页；非阻塞，可稍后，设置页仍保留入口。
        runCatching {
            val sp = getSharedPreferences("zhengdao-ui", MODE_PRIVATE)
            if (!sp.getBoolean("manage_prompted", false) && !android.os.Environment.isExternalStorageManager()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("开启完整存储访问")
                    .setMessage("证道需要访问手机存储，以便 Agent 在你的文件中查找与产出。将打开系统设置，请开启「所有文件访问」；也可稍后在设置中开启。")
                    .setCancelable(false)
                    .setPositiveButton("去开启") { _, _ ->
                        try {
                            startActivity(
                                Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                    .setData(android.net.Uri.parse("package:$packageName"))
                            )
                        } catch (_: Exception) {
                            try {
                                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            } catch (_: Exception) { /* 个别 ROM 无此入口，忽略 */ }
                        }
                    }
                    .setNegativeButton("稍后") { _, _ -> }
                    .show()
                sp.edit().putBoolean("manage_prompted", true).apply()
            }
        }

        // 通知栏「回到终端」：跳过欢迎页直达终端（M2）
        val openTerminal = intent?.getBooleanExtra("open_terminal", false) == true

        // 记住上次页面（用户反馈：上滑切走再回来不该回首页）。
        // 欢迎页只在首次安装展示；之后冷启动直达上次位置。
        val prefs = getSharedPreferences("zhengdao-ui", MODE_PRIVATE)
        val lastRoute = prefs.getString("last_route", null)

        // 环境安装状态：进 UI 前先同步读一次，避免首帧按默认 false 闪一下「环境未安装」。
        // （v1.2 修复：原先各页面直接在组合里调 rootfsInstalled()，装完环境回主页不刷新。）
        com.example.zhengdao.ui.RootfsState.refresh(this)

        setContent {
            com.example.zhengdao.ui.theme.ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ZhengdaoApp(startInTerminal = openTerminal, lastRoute = lastRoute)
                }
            }
        }
    }

    /** 后台检查 GitHub 最新发布，比当前 versionName 新则弹非阻断提示（去下载 = 打开浏览器）。 */
    private fun checkAppUpdateInBackground() {
        Thread {
            try {
                val c = java.net.URL(APP_RELEASES_API).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val body = c.inputStream.bufferedReader().readText()
                val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                    ?: return@Thread
                if (!isNewerVersion(tag, BuildConfig.VERSION_NAME)) return@Thread
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    android.app.AlertDialog.Builder(this)
                        .setTitle("发现新版本")
                        .setMessage("最新版 ${tag.removePrefix("v")}（当前 ${BuildConfig.VERSION_NAME}），可到 GitHub Releases 下载。")
                        .setPositiveButton("去下载") { _, _ ->
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/pisces19860207/zhengdao/releases")
                                )
                            )
                        }
                        .setNegativeButton("忽略", null)
                        .show()
                }
            } catch (_: Throwable) {
                // 静默：更新检查失败绝不打扰用户
            }
        }.start()
    }

    /** 语义化版本比较：remote 严格大于 local 才算有更新（v 前缀与缺失段容错）。 */
    private fun isNewerVersion(remote: String, local: String): Boolean {
        val r = remote.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 内存看护（用户指定）：退后台时释放空闲连接等非必要资源，降低被系统清理的概率。 */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.example.zhengdao.rootfs.RootfsDownloader.releaseIdleResources()
        }
    }
}

/**
 * 进终端的唯一 Intent 构造器（2026-10-07：任务栈曾叠到 10 个终端页）。
 *
 * 为什么必须统一在这里加 flag：
 *   默认 standard 下每次 startActivity 都新建实例。五个入口（丹房安装/启动、洞天 Tab、
 *   设置页清理缓存、通知栏回到终端、欢迎页装环境）各自 `Intent(ctx, TerminalActivity)`，
 *   来回几次就叠出一串——而 SessionManager.onViewUpdate 是**全局单例回调**，只指向最后
 *   建的实例，先前的全部变成死画面（pty 照常输出但永不重绘），且每个新实例还会重复
 *   attach、重复注入 autocmd。
 *
 * CLEAR_TOP | SINGLE_TOP：在**同一 task 内**把既有终端提到前台并走 onNewIntent，
 * 清理其上方 activity，保留下方的 MainActivity ⇒ 红点关闭仍回主页，返回栈语义不变。
 * （为何不用 manifest 的 singleTask：实测它会把终端塞进独立 task，清掉 MainActivity，
 *   关闭后不再回主页——参见 AndroidManifest.xml 里 TerminalActivity 的注释。）
 */
private fun terminalIntent(context: android.content.Context, autocmd: String?, agentId: String?): Intent =
    Intent(context, TerminalActivity::class.java).apply {
        putExtra("autocmd", autocmd)
        putExtra("agent_id", agentId)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

@Composable
fun ZhengdaoApp(startInTerminal: Boolean = false, lastRoute: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val nav = rememberNavController()
    // 可观察状态：环境装/没装会变（终端里装完、设置里卸载），必须跟着变。
    val installed by com.example.zhengdao.ui.RootfsState.installed
    val routePrefs = context.getSharedPreferences("zhengdao-ui", android.content.Context.MODE_PRIVATE)

    // 回到前台就重读一次：装在终端里完成（RootfsInstaller 写完成标记）后返回主页，
    // 欢迎页与主页的环境状态必须立刻跟上，不能等杀进程重进。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                com.example.zhengdao.ui.RootfsState.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 路由决策优先级：通知栏直达终端 > 上次页面（欢迎页只在首次安装出现）
    // ⚠️ 恢复页不能当 startDestination——起始页底下没有返回栈，popBackStack
    // 无处可退（用户实测：冷启动直落设置页后返回键和右滑全失效）。改为永远
    // 落 home，恢复页叠加其上。
    val startRoute = when {
        startInTerminal -> "home"
        lastRoute != null -> "home"
        else -> "welcome"
    }

    // 通知栏直达终端：落到主页后立即拉起终端（会话由 SessionManager attach 恢复）
    if (startInTerminal) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            context.startActivity(terminalIntent(context, null, null))
        }
    }

    // 恢复上次页面：叠在 home 之上（返回键退回首页，不再有死返回）
    androidx.compose.runtime.LaunchedEffect(lastRoute) {
        if (!startInTerminal && lastRoute == "settings") {
            nav.navigate("settings") { launchSingleTop = true }
        }
    }

    // 记录当前页面（供冷启动恢复；terminal 是独立 Activity，见下）
    androidx.compose.runtime.LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            routePrefs.edit().putString("last_route", entry.destination.route).apply()
        }
    }

    // 跳终端（带可选自动命令）：洞天入口与设置页「在终端中清理缓存」共用同一实现，
    // 两处必须同一行为——此前 settings 路由没传参，onOpenTerminal 落到默认 no-op，
    // 「在终端中清理缓存」点了零反应（连 Toast 都没有）。
    //
    // ⚠️ 失败必须可见（项目原则）＋ 可诊断：这条 lambda 是全 App 唯一的进终端入口，
    //    它一旦 silently 失败，表现就是"点了没反应"，用户无从判断是没点到还是坏了。
    //    故：入口处打点（autocmd/agentId 的长度而非全文——全文可能是几 KB 的安装命令），
    //    startActivity 包 try/catch，任何异常都要 Toast 出原因并落 RunLog。
    val openTerminal: (String?, String?) -> Unit = { autocmd, agentId ->
        val tag = "OpenTerminal"
        android.util.Log.d(
            tag, "进终端: autocmd=${autocmd?.length ?: 0}B agentId=$agentId ctx=$context"
        )
        try {
            context.startActivity(terminalIntent(context, autocmd, agentId))
            android.util.Log.d(tag, "startActivity 已发出")
        } catch (t: Throwable) {
            // 异常不可见 = 用户眼里就是"点了没反应"，必须当面说清
            val reason = t.message ?: t.toString()
            android.util.Log.e(tag, "进终端失败", t)
            runCatching {
                com.example.zhengdao.rootfs.RunLog.log("[错误] 进终端失败: $reason")
            }
            // 2026-10-08：异常原文 → 人话。原文已落 RunLog，用户看的用 [HumanizeError]
            // 转成「没有权限 / 网络超时 / 文件找不到」之类可读短语
            android.widget.Toast.makeText(
                context, "无法打开终端：${com.example.zhengdao.util.HumanizeError.title(t)}", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    NavHost(navController = nav, startDestination = startRoute) {
        composable("welcome") {
            WelcomeScreen(
                environmentInstalled = installed,
                onStart = { envInstalled ->
                    if (envInstalled) {
                        nav.navigate("home") { launchSingleTop = true }
                    } else {
                        // 未安装：直达终端（安装对话框会在会话就绪后自动弹出）
                        context.startActivity(terminalIntent(context, null, null))
                    }
                },
                // 跳过环境安装直进主界面：**太极不依赖 Debian**（宿主 bionic serve，
                // 从不进 PRoot），新用户可以先体验主打功能，想要终端再回来装。
                onSkip = { nav.navigate("home") { launchSingleTop = true } },
            )
        }
        composable("home") {
            HomeTabs(
                onOpenTerminal = openTerminal,
                onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } },
            )
        }
        composable("settings") {
            // 设置页（二级页）：顶栏＝「‹ 返回 + 居中标题」，内容区不再重复写一遍标题
            //（旧版外层"设置"与页内大字"设置"上下叠着，是最扎眼的重复）。
            // 层级约定：根页（丹房）用大标题，二级页用导航栏标题——与 iOS 的分层一致。
            // edge-to-edge 下必须自行避让系统栏，否则返回栏会被状态栏压住、末项被导航栏遮挡。
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    TextButton(
                        onClick = { nav.popBackStack() },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BackChevron(tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text("返回", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                SettingsScreen(
                    onOpenTerminal = openTerminal,
                    onOpenPlugins = { nav.navigate("plugins") { launchSingleTop = true } },
                )
            }
        }
        composable("plugins") {
            // 三级页：顶栏与设置页同款（返回 + 居中标题），内容由 PluginsScreen 负责
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    TextButton(
                        onClick = { nav.popBackStack() },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BackChevron(tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text("返回", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        text = "插件",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                PluginsScreen()
            }
        }
    }
}

/** 首页骨架：Agent Tab（卡片列表）/ 终端 Tab（进入全屏终端）。 */
@Composable
fun HomeTabs(
    onOpenTerminal: (autocmd: String?, agentId: String?) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(2) }

    // 切 Tab 保留滚动位置（K2）——把两个 tab 的 LazyListState 提到 HomeTabs 顶层。
    //
    // 之前 listState 在各 tab 内部用 rememberLazyListState() 创建：切走 tab 时整个 tab
    // 的 Composable 退出 Composition，state 跟着 destroy；切回时拿到的是"出厂态"。
    // 用户实感：丹房翻到第 8 个 Agent，切太极再切回，又被甩到顶。
    //
    // 提到外层后 state 跟 HomeTabs 同寿命；rememberLazyListState() 内部用 saver 走
    // rememberSaveable，顺带覆盖了"配置变更（旋转、深浅色）"也要保留位置。
    //
    // K1 双击滚顶：把这两个 state 通过 onTabDoubleTap lambda 注入底栏，双击触发
    // animateScrollToItem(0)。注意：仅对**当前 tab 之外**的 tab 触发 —— 当前 tab 双击
    // 等同于再次选自己，不动列表。
    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val taijiListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // K1（双击 Tab 滚顶）：在 HomeTabs 顶层记"上一次点同一个 Tab 的时间"。
    // 300ms 内再次点同一 Tab → 视为双击 → animateScrollToItem(0)。
    // 终端 Tab 没 listState（点了直接跳 Activity），双击分支对它 no-op。
    //
    // 为什么不在 Composable 内做手势检测（detectTapGestures onDoubleTap）：
    //   NavigationBarItem 已自带 onClick 包了 clickable，叠加 pointerInput 会与 clickable
    //   抢事件，得自己处理 tap/doubleTap 互斥。这里走"时间窗口判定"逻辑等价（300ms
    //   是 Material Design 文档里 Tap-Double 区分的推荐值），代码少一半。
    val lastTabClickAt = androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val now = { android.os.SystemClock.uptimeMillis() }

    fun animateToTop(target: Int) {
        when (target) {
            0 -> scope.launch { taijiListState.animateScrollToItem(0) }
            2 -> scope.launch { homeListState.animateScrollToItem(0) }
            // 1 = 终端：点了就跳 TerminalActivity，不存在"滚顶"概念，no-op
        }
    }

    fun onTabClicked(target: Int, onTerminalClicked: () -> Unit) {
        if (tab == target) {
            val t = now()
            if (t - lastTabClickAt.longValue < 300L) {
                animateToTop(target)
                lastTabClickAt.longValue = 0L
                return
            }
        }
        lastTabClickAt.longValue = now()
        when (target) {
            0 -> { tab = 0 }
            1 -> { tab = 1; onTerminalClicked() }
            2 -> { tab = 2 }
        }
    }

    Scaffold(
        bottomBar = {
            // 底栏走 iOS 语言：与页面同为白底、靠一条 0.5dp 发丝线分隔。
            // 刻意不用 M3 默认的 tonalElevation 阴影——浅色主题下那会压出一条灰带，
            // 与全局"白卡片 + 发丝描边"的语言冲突（同一处理见丹房卡片）。
            Column {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    // 选中＝systemBlue 图标/文字 + 淡蓝胶囊；未选中＝次要文字色
                    val tabColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NavigationBarItem(
                        selected = tab == 0,
                        // 太极 = Tab 内嵌 TerminalView，直跑宿主 bionic opencode TUI
                        //（不经过 PRoot；XDG 独立 = /data/data/证道/files/taiji/）
                        // K1：双击 = 滚顶（见 HomeTabs 顶部的 lastTabClickAt 注释）。
                        onClick = { onTabClicked(0) {} },
                        icon = { TaijiIcon(tab == 0) },
                        label = { Text("太极") },
                        colors = tabColors,
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        // 点击直接进全屏终端（用户定：简单明了，不要占位页多一跳）
                        //
                        // ⚠️ `tab = 1` 不能省（2026-10-07 用户报「红点返回回的不是主界面，
                        //    是太极 tab」）：人在太极页（tab=0）点洞天进终端时若不改选中态，
                        //    终端一整页盖在上面时看不出问题，等红点关掉终端就露馅了——
                        //    底部高亮还停在太极，用户以为"返回到了 opencode"。
                        //    洞天是进终端的那个 tab，用过终端就该停在洞天。
                        // K1：终端 tab 双击不滚顶（点了就跳 Activity，列表根本不存在）。
                        onClick = { onTabClicked(1) { onOpenTerminal(null, null) } },
                        icon = { CaveIcon(tab == 1) },
                        // 2026-10-08：文案由「洞天」改为「终端」。
                        // 理由：这个 Tab 的功能是打开终端，而全项目 30+ 处文案都写「终端」，
                        // 只有底栏与空态写「洞天」——用户在首次启动那一屏就会同时看到两个词
                        // （WelcomeScreen 原句："运行环境用于「洞天」终端与 Agent"），
                        // 却找不到叫「洞天」的地方。「终端」是用户已有的通用认知，无需学。
                        // 注意：**代码与图标设计里仍称「洞天」**（CaveIcon 是月洞门，
                        // 见 :624 的设计说明），此处只改用户可见文案。
                        label = { Text("终端") },
                        colors = tabColors,
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        // K1：双击 = 滚到丹房 Agent 列表顶。
                        onClick = { onTabClicked(2) {} },
                        icon = { DingIcon(tab == 2) },
                        label = { Text("丹房") },
                        colors = tabColors,
                    )
                }
            }
        },
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Scaffold **不消费** window insets —— 见 material3 Scaffold.kt 的 KDoc：
                //   "The lambda receives a PaddingValues that should be applied to the content root
                //    via Modifier.padding and Modifier.consumeWindowInsets"。
                // 只 padding 不 consume 的后果：内部任何 statusBarsPadding() / navigationBarsPadding()
                // 都会把状态栏 / 导航条再垫一次（顶层内容整体下移、底部多出一条空档）。
                // 太极 Tab 的固定顶栏与输入框正是这种情况，故在此声明「系统栏内边距已由本层应用」。
                // ⚠️ IME inset 不在 contentWindowInsets 内，imePadding() 不受影响，键盘避让照常。
                .consumeWindowInsets(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (tab) {
                // 太极：Compose 原生 UI（直连 opencode serve 的 HTTP + SSE）。
                // ⚠️ 旧 WebView + LocalProxy 回退路径已删（v1.1.1 阶段 3）——
                //    原生 UI 已过真机验收（v1.1 四阶段 + v1.1.1 阶段 0），退路失去存在意义；
                //    真坏了就修，不藏一条会腐烂的备用路。
                0 -> com.example.zhengdao.ui.taiji.TaijiScreen(listState = taijiListState)
                // 丹房：Agent 管理（OpenCode 已内置为太极，不在丹房展示）
                2 -> HomeScreen(
                    onOpenTerminal = onOpenTerminal,
                    onOpenSettings = onOpenSettings,
                    listState = homeListState,
                )
                // 洞天：终端本身是独立的整屏页面（不在 Tab 里内嵌），所以这里只是"回程落点"。
                // ⚠️ 以前这里是 `else -> {}`（全白）——红点关掉终端后落在洞天会看到一片空白，
                //    用户完全无从判断发生了什么。给一个诚实的空态：说清终端在哪、给一个再进去的按钮。
                1 -> TerminalTabEmptyState(onOpenTerminal = { onOpenTerminal(null, null) })
                else -> {}
            }
        }
    }
}

/**
 * 洞天 Tab 的空态 —— 只是"从终端回来的落点"。
 *
 * 为什么需要：终端是**独立的整屏 Activity**（不在 Tab 里内嵌，见 TerminalActivity），
 * 所以洞天这个 Tab 本身没有内容。红点 / 左边缘右滑关掉终端后落在这里，
 * 以前是一片全白（`else -> {}`），用户看到空白只会以为 App 坏了。
 * 空态里把"终端是个独立页面"说明白，并留一个再进去的入口。
 */
@Composable
private fun TerminalTabEmptyState(onOpenTerminal: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            // 与底栏 Tab 文案保持一致：同一个 Tab，同一个名字（见 :470 的改名说明）
            text = "终端",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "终端是一个独立页面",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "关掉它之后会回到这里。会话还在后台跑着，再进去就是刚才那一屏。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenTerminal, shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
            Text("进入终端")
        }
    }
}

// ── 底部 Tab 自绘图标：道家体系三件套（Compose Path，零第三方素材）──
//
// 为什么重绘（2026-10-07，真机截图驱动）：
//   旧太极图标在真机上是一片"叶子/旗子"，不是阴阳鱼。根因在几何：
//   两段 arcTo 画完 S 之后用 close() 收尾，close() 是**从底点直线拉回顶点**，
//   填出的是一个由 S 与直径围成的月牙——正确做法是沿**外圆左半**绕回顶点。
//   顺手把三个图标收敛成一套统一的设计语言（下面这些常量就是"一套"的契约）。
//
// 统一设计语言（三个必须像同一个人画的）：
//   · 画布 26dp，光学框 20dp（四边各留 ~3dp 呼吸，Tab 图标不做满框）
//   · 描边 1.9f + 圆头 + 圆角连接（StrokeCap.Round / StrokeJoin.Round）
//   · 圆形母题贯穿：太极＝整圆、洞天＝半圆月洞门、丹房＝圆腹鼎
//   · 只在"点睛"处用实心（太极双鱼眼、炉中之丹），其余一律描边——26dp 下不糊

/** 图标画布边长（dp）。 */
private const val TAB_ICON_DP = 26

/** 统一描边宽度（px）。26dp @560dpi ≈ 91px，1.9f 约等于 2dp，不粗不细。 */
private const val TAB_STROKE = 1.9f

/** 统一取色：选中＝主色（systemBlue），未选中＝次要文字色。 */
@Composable
private fun tabIconColor(selected: Boolean) =
    if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant

/** 统一描边样式（圆头圆角）——三个图标共用，保证线感一致。 */
private fun tabStroke() = Stroke(
    width = TAB_STROKE,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

/**
 * 太极：外圆 + 阴阳鱼 S 分界 + 双鱼眼。
 *
 * S 分界 = 上半圆向右鼓（半径 r/2，圆心在 (cx, cy-r/2)）+ 下半圆向左鼓
 * （半径 r/2，圆心在 (cx, cy+r/2)）首尾相接；两只鱼眼就落在两个半圆的圆心上，
 * 这是太极图的标准作图法。
 */
@Composable
fun TaijiIcon(selected: Boolean) {
    val color = tabIconColor(selected)
    Canvas(modifier = Modifier.size(TAB_ICON_DP.dp)) {
        val stroke = tabStroke()
        val r = (size.minDimension - TAB_STROKE) / 2f
        val cx = size.width / 2f
        val cy = size.height / 2f

        // 外圆
        drawCircle(color = color, radius = r, center = Offset(cx, cy), style = stroke)

        // 阴阳鱼 S 分界（⚠️ 不要 close()：那会直线拉回形成月牙，即旧版的畸形根因）
        val s = Path().apply {
            moveTo(cx, cy - r)
            arcTo(Rect(cx - r / 2f, cy - r, cx + r / 2f, cy), -90f, 180f, false)   // 上半圆，向右鼓
            arcTo(Rect(cx - r / 2f, cy, cx + r / 2f, cy + r), -90f, -180f, false)  // 下半圆，向左鼓
        }
        drawPath(s, color, style = stroke)

        // 双鱼眼（实心点，落在两个半圆圆心）
        drawCircle(color = color, radius = r * 0.15f, center = Offset(cx, cy - r / 2f))
        drawCircle(color = color, radius = r * 0.15f, center = Offset(cx, cy + r / 2f))
    }
}

/**
 * 洞天：月洞门（外拱 + 内门洞 + 地平线）——道家「洞天福地」的门阙意象。
 *
 * 加一条地平线是有意为之：只有拱就会读成"山洞/隧道"，有了门前地面才成"门"，
 * 也与另外两个图标一样在底部有一条稳定的横向基线。
 */
@Composable
fun CaveIcon(selected: Boolean) {
    val color = tabIconColor(selected)
    Canvas(modifier = Modifier.size(TAB_ICON_DP.dp)) {
        val stroke = tabStroke()
        val w = size.width
        val h = size.height
        val baseY = h * 0.86f

        // 地平线（门前的台阶 / 地面）
        drawLine(color, Offset(w * 0.10f, baseY), Offset(w * 0.90f, baseY), stroke.width, StrokeCap.Round)

        // 门框（外拱）：直壁 → 半圆顶 → 直壁
        val outer = Path().apply {
            moveTo(w * 0.22f, baseY)
            lineTo(w * 0.22f, h * 0.44f)
            arcTo(Rect(w * 0.22f, h * 0.16f, w * 0.78f, h * 0.72f), 180f, 180f, false)
            lineTo(w * 0.78f, baseY)
        }
        drawPath(outer, color, style = stroke)

        // 门洞（内拱）：与外拱同心同法，只缩放
        val inner = Path().apply {
            moveTo(w * 0.40f, baseY)
            lineTo(w * 0.40f, h * 0.50f)
            arcTo(Rect(w * 0.40f, h * 0.40f, w * 0.60f, h * 0.60f), 180f, 180f, false)
            lineTo(w * 0.60f, baseY)
        }
        drawPath(inner, color, style = stroke)
    }
}

/**
 * 丹房：圆腹鼎（口沿 + 双耳 + 圆腹 + 双足）+ 炉中之丹。
 *
 * 旧版腹部是梯形、耳是外撇的斜线，整体读成"提篮"。改为圆腹（两段三次贝塞尔）
 * + 竖直双耳 + 外撇双足，腹心点一颗实心"丹"——落在名字上（丹房＝炼丹之所）。
 */
@Composable
fun DingIcon(selected: Boolean) {
    val color = tabIconColor(selected)
    Canvas(modifier = Modifier.size(TAB_ICON_DP.dp)) {
        val stroke = tabStroke()
        val w = size.width
        val h = size.height
        val rimY = h * 0.30f

        // 口沿
        drawLine(color, Offset(w * 0.07f, rimY), Offset(w * 0.93f, rimY), stroke.width, StrokeCap.Round)
        // 双耳（竖直短柱——比外撇斜线更像鼎耳）
        drawLine(color, Offset(w * 0.22f, rimY), Offset(w * 0.22f, h * 0.14f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(w * 0.78f, rimY), Offset(w * 0.78f, h * 0.14f), stroke.width, StrokeCap.Round)

        // 圆腹
        val belly = Path().apply {
            moveTo(w * 0.07f, rimY)
            cubicTo(w * 0.09f, h * 0.60f, w * 0.26f, h * 0.76f, w * 0.50f, h * 0.76f)
            cubicTo(w * 0.74f, h * 0.76f, w * 0.91f, h * 0.60f, w * 0.93f, rimY)
        }
        drawPath(belly, color, style = stroke)

        // 双足
        drawLine(color, Offset(w * 0.32f, h * 0.72f), Offset(w * 0.29f, h * 0.90f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(w * 0.68f, h * 0.72f), Offset(w * 0.71f, h * 0.90f), stroke.width, StrokeCap.Round)

        // 炉中之丹（点睛）
        drawCircle(color = color, radius = w * 0.055f, center = Offset(w * 0.50f, h * 0.50f))
    }
}

/**
 * 返回箭头（‹）：设置页顶栏用。与底部 Tab 图标同一套笔（圆头圆角 + 1.9f 描边）。
 * 取代原先的"←"文本箭头——字符箭头在 16sp 下是一根细长斜线，与自绘图标不同路。
 */
@Composable
fun BackChevron(tint: Color, side: Dp = 13.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.66f, h * 0.12f)
            lineTo(w * 0.28f, h * 0.50f)
            lineTo(w * 0.66f, h * 0.88f)
        }
        drawPath(
            path,
            tint,
            style = Stroke(width = TAB_STROKE * 0.95f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * 设置入口图标（滑杆，sliders/tune）：三条横轨 + 各一个圆点。
 *
 * 为什么不是齿轮：17dp 的尺寸下，齿轮只能画成"细圆 + 一圈放射线"，
 * 真机截图里无论齿长齿短都读成太阳／花（试过两版参数都一样），
 * 而"设置"两个字就在旁边，不需要一个会被误读的图形去抢戏。
 * 滑杆是通用的设置符号，横向线条也与底部三个道家图标同一套笔法。
 */
@Composable
fun SettingsIcon(tint: Color, side: Dp = 17.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val rail = Stroke(width = TAB_STROKE * 0.85f, cap = StrokeCap.Round)
        // 三条轨道 + 圆点错位分布，一眼能看出是"可调的"
        val rails = listOf(
            h * 0.24f to w * 0.68f,
            h * 0.50f to w * 0.34f,
            h * 0.76f to w * 0.58f,
        )
        rails.forEach { (y, knobX) ->
            drawLine(
                color = tint,
                start = Offset(w * 0.12f, y),
                end = Offset(w * 0.88f, y),
                strokeWidth = rail.width,
                cap = StrokeCap.Round,
            )
            drawCircle(color = tint, radius = w * 0.09f, center = Offset(knobX, y))
        }
    }
}
