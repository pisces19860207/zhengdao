// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.zhengdao.oc.TaijiPrefs
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.AppState
import com.example.zhengdao.ui.HomeScreen
import com.example.zhengdao.ui.SettingsScreen
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

@Composable
fun ZhengdaoApp(startInTerminal: Boolean = false, lastRoute: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val nav = rememberNavController()
    val installed = AppState.rootfsInstalled(context)
    val routePrefs = context.getSharedPreferences("zhengdao-ui", android.content.Context.MODE_PRIVATE)

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
            context.startActivity(Intent(context, TerminalActivity::class.java))
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
    val openTerminal: (String?, String?) -> Unit = { autocmd, agentId ->
        context.startActivity(
            Intent(context, TerminalActivity::class.java).apply {
                putExtra("autocmd", autocmd)
                putExtra("agent_id", agentId)
            }
        )
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
                        context.startActivity(Intent(context, TerminalActivity::class.java))
                    }
                },
            )
        }
        composable("home") {
            HomeTabs(
                onOpenTerminal = openTerminal,
                onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } },
            )
        }
        composable("settings") {
            // 设置页（含返回）：复用 Material3 顶栏由页面内实现，此处提供返回按钮容器。
            // edge-to-edge 下必须自行避让系统栏，否则返回栏会被状态栏压住、末项被导航栏遮挡。
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { nav.popBackStack() }) { Text("← 返回") }
                    Text("设置", style = MaterialTheme.typography.titleMedium)
                }
                SettingsScreen(onOpenTerminal = openTerminal)
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

    // 太极 Tab 走哪套 UI：默认 Compose 原生 UI（直连 serve），出问题可一键回退 WebView 版。
    // 这里用**监听**而不是只读取一次——本页常驻不重建，设置页切换后要即时生效，不必重启 App。
    val ctx = LocalContext.current
    val taijiPrefs = remember { TaijiPrefs.prefs(ctx) }
    var useNativeUi by remember { mutableStateOf(TaijiPrefs.useNativeUi(ctx)) }
    DisposableEffect(taijiPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TaijiPrefs.key()) useNativeUi = TaijiPrefs.useNativeUi(ctx)
        }
        taijiPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { taijiPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    // 太极 = Tab 内嵌 TerminalView，直跑宿主 bionic opencode TUI
                    //（不经过 PRoot；XDG 独立 = /data/data/证道/files/taiji/）
                    onClick = { tab = 0 },
                    icon = { TaijiIcon(tab == 0) },
                    label = { Text("太极") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    // 点击直接进全屏终端（用户定：简单明了，不要占位页多一跳）
                    onClick = { onOpenTerminal(null, null) },
                    icon = { CaveIcon(tab == 1) },
                    label = { Text("洞天") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { DingIcon(tab == 2) },
                    label = { Text("丹房") },
                )
            }
        },
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (tab) {
                // 太极：默认 Compose 原生 UI（直连 opencode serve 的 HTTP + SSE）；
                // 开关关掉则回退旧 WebView + LocalProxy 版（阶段 0 鉴权未过时的退路）。
                0 ->
                    if (useNativeUi) com.example.zhengdao.ui.taiji.TaijiScreen()
                    else com.example.zhengdao.ui.TaijiScreen()
                // 丹房：Agent 管理（OpenCode 已内置为太极，不在丹房展示）
                2 -> HomeScreen(onOpenTerminal = onOpenTerminal, onOpenSettings = onOpenSettings)
                // 洞天：点击即进终端（onClick 已处理），停留时显示空态
                else -> {}
            }
        }
    }
}

// ── 底部 Tab 自绘图标（Compose Path，零第三方素材，用户定稿）──

/** 太极：外圆 + S 分割 + 双鱼眼。 */
@Composable
fun TaijiIcon(selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.Canvas(modifier = Modifier.size(26.dp)) {
        val r = size.minDimension / 2f
        val cx = size.width / 2f
        val cy = r
        drawCircle(color = color, radius = r - 1f, center = androidx.compose.ui.geometry.Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, cy - (r - 1f))
            arcTo(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset(cx, cy - (r - 1f) / 2), androidx.compose.ui.geometry.Size(r - 1f, r - 1f)), -90f, 180f, false)
            arcTo(androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset(cx - (r - 1f), cy + (r - 1f) / 2), androidx.compose.ui.geometry.Size(r - 1f, r - 1f)), -90f, 180f, false)
            close()
        }
        drawPath(path, color)
        drawCircle(color = color, radius = (r - 1f) / 8f, center = androidx.compose.ui.geometry.Offset(cx, cy - (r - 1f) / 2))
        drawCircle(color = color, radius = (r - 1f) / 8f, center = androidx.compose.ui.geometry.Offset(cx, cy + (r - 1f) / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f))
    }
}

/** 洞天：拱门（外拱 + 内门洞）。 */
@Composable
fun CaveIcon(selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.Canvas(modifier = Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
        val outer = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.18f, h * 0.88f)
            lineTo(w * 0.18f, h * 0.45f)
            cubicTo(w * 0.18f, h * 0.12f, w * 0.82f, h * 0.12f, w * 0.82f, h * 0.45f)
            lineTo(w * 0.82f, h * 0.88f)
        }
        drawPath(outer, color, style = stroke)
        val inner = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.36f, h * 0.88f)
            lineTo(w * 0.36f, h * 0.55f)
            cubicTo(w * 0.36f, h * 0.34f, w * 0.64f, h * 0.34f, w * 0.64f, h * 0.55f)
            lineTo(w * 0.64f, h * 0.88f)
        }
        drawPath(inner, color, style = stroke)
    }
}

/** 丹房：鼎（口沿双耳 + 腹 + 双足）。 */
@Composable
fun DingIcon(selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.foundation.Canvas(modifier = Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.32f), androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.32f), stroke.width)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.32f), androidx.compose.ui.geometry.Offset(w * 0.24f, h * 0.16f), stroke.width)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.32f), androidx.compose.ui.geometry.Offset(w * 0.76f, h * 0.16f), stroke.width)
        val belly = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.2f, h * 0.32f)
            lineTo(w * 0.28f, h * 0.72f)
            lineTo(w * 0.72f, h * 0.72f)
            lineTo(w * 0.8f, h * 0.32f)
        }
        drawPath(belly, color, style = stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.35f, h * 0.72f), androidx.compose.ui.geometry.Offset(w * 0.32f, h * 0.88f), stroke.width)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.65f, h * 0.72f), androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.88f), stroke.width)
    }
}
