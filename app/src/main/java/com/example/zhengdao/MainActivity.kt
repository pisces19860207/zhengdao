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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.keepalive.CoroutineCrashHandler
import com.example.zhengdao.ui.AppState
import com.example.zhengdao.ui.HomeScreen
import com.example.zhengdao.ui.PermissionSetup
import com.example.zhengdao.ui.PermissionSetupScreen
import com.example.zhengdao.ui.SettingsScreen
import kotlinx.coroutines.launch
import com.example.zhengdao.ui.WelcomeScreen

/** App 自更新检查端点（GitHub Releases 最新发布）。 */
private const val APP_RELEASES_API = "https://api.github.com/repos/pisces19860207/zhengdao/releases/latest"

/**
 * 冷启动可恢复的 Compose 路由白名单（B-补-15 复核，2026-10-16）。
 *
 * NavHost 只有四个目的地：`welcome` / `home` / `permission_setup` / `settings`。
 *  - `welcome`：首次安装的入口，由 `startRoute` 决定，谈不上「恢复」；
 *  - `home`：默认落点，本身就是兜底；
 *  - `permission_setup`：权限门槛，由 `guidePermissions` 那个效果按需重新叠加（见 `onCreate` 里的注释）；
 *  - `settings`：用户会主动离开、又期望回来还在原处的二级页 ⇒ **唯一值得恢复的**。
 *
 * ⚠️ 故意写成「白名单 + 兜底判空」，而不是 `nav.navigate(lastRoute)`：prefs 里可能残留
 * **老版本**的路由字符串 —— 例：插件页 2026-10-08 曾搬进抱朴抽屉（该抽屉的插件面板
 * 复用 `ui/PluginsScreen.kt`；抽屉本身 2026-10-16 随旧抱朴线清场删除），`plugins` 路由随之删除，但老装机的
 * `last_route` 里仍可能留着 "plugins"。直接 navigate 会抛
 * `IllegalArgumentException` 崩在冷启动，白名单把这类死值一律当「没有可恢复的页面」。
 */
private val RESTORABLE_ROUTES = setOf("settings")

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

        // 系统版本兜底门槛（用户第四批）：minSdk=36 已拦住安装，这里双保险
        // 应对旁加载极端场景；不可取消，确定即退出，不崩溃。
        if (android.os.Build.VERSION.SDK_INT < 36) {
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.main_android_too_old_title))
                .setMessage(getString(R.string.main_android_too_old_msg, android.os.Build.VERSION.RELEASE))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.common_confirm)) { _, _ -> finish() }
                .show()
            return
        }

        // 签名自检（2026-10-09，ERRATA E-065）：被重新打包、重新签名的包在这里被认出来，
        // 只**提醒**一次，不禁用任何功能 —— GPL 允许别人改代码自己签名，
        // 但用户有权知道手里这个包是不是作者发的。常驻显示在设置页「关于」。
        warnIfUnofficialSignature()

        // App 自更新：调用点**下移**到权限判定之后（§B 顺序 15 的「与权限框互斥」，见下面那段）。

        // 首启权限引导（2026-10-11，用户反馈「新安装的时候申请各种权限的那个页面……单个卡片
        // 有点晃眼也不符合使用习惯」）：首次安装且缺必需权限时，下面那些**逐个弹的系统对话框
        // 全部让路**，改由一页「权限」（ui/PermissionSetupScreen.kt）把几项权限列在一屏里、
        // 逐项点开授权。判定必须在这里算出来（而不是只放在 Compose 里）：对话框的触发点就在
        // 下面几行，晚一步判断就会先弹系统对话框、把权限页顶到后面——那仍旧是"一次一个"。
        val permissionSetupPending = PermissionSetup.shouldGuide(this)

        if (!permissionSetupPending) {
            // B-补-19 立账（2026-10-16，用户拍板「立账，并做了」）：**一次冷启动最多一个系统框**。
            //
            // 原先存储(100)与通知(101)是两段互不相干的 runCatching：已引导过的冷启动（例如在
            // 权限页点了「稍后再说」）会把两个系统框连着排出去；而自更新 AlertDialog 是同一
            // 次冷启动里第 3 个可能出现的框 —— 更新检查是网络后台线程，返回通常晚于系统框弹出，
            // 结果是「系统权限框刚点掉、更新框又贴上来」。用「本次冷启动」的局部标记收敛：
            //   ① 已经排过一个系统框，就不再排第二个（没排的那项下个冷启动照常补 —— 一次性标记
            //      保证"问过就不再问"，见 PermissionSetup.shouldAutoRequest 的 KDoc）；
            //   ② 本次排了系统框，就不发起自更新检查（与「更新框不与权限框抢同一屏」同一口径）。
            // 权限页那段互斥（2026-10-11）在下面照旧：pending 时两个请求都不排。
            var permDialogThisLaunch = false

            // 存储运行时权限（第 0 步修复，2026-10-06）：此前全 App 没有任何请求代码，
            // WRITE 靠用户手点设置授予、READ 因 manifest maxSdkVersion=32 帽子从未可授，
            // Android 16 上共享存储读写全被 FUSE 拒——hermes"只能写不能读"的根因。
            // 冷启动缺哪个补哪个；拒绝不打断使用（bind 会静默跳过，终端照常）。
            // 首次安装由权限页接管；这段保留给"已引导过却依然缺权限"的冷启动补漏
            // （否则用户一旦点过「稍后再说」，后来想补权限就只剩设置页一条路）。
            runCatching {
                // 第㉕刀（2026-10-15，targetSdk 28 → 36）：READ/WRITE_EXTERNAL_STORAGE 在
                // API 30+ 已 inert —— 请求只会被系统**静默拒绝**（不弹任何框），问了也白问，
                // 共享存储的唯一通道是「所有文件访问」（见 ProotLauncher.storageGranted 与
                // 权限页那两行）。故 30+ 干脆不问，免得每次冷启动都白跑一轮 requestPermissions。
                if (android.os.Build.VERSION.SDK_INT < 30) {
                    val needed = arrayOf(
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ).filter {
                        androidx.core.content.ContextCompat.checkSelfPermission(this, it) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    // §B 顺序 15（2026-10-14）：**每项最多自动问一次** —— 原代码只判"缺不缺"，
                    // 用户拒绝过（或系统永远不给）就会**每次冷启动再弹一次**。改用一次性标记；
                    // 之后补权限走权限页 / 设置页。
                    if (PermissionSetup.shouldAutoRequest(PermissionSetup.storageAsked(this), needed.isNotEmpty())) {
                        PermissionSetup.markStorageAsked(this) // 先写标记再请求：请求中途被杀也不会重问
                        androidx.core.app.ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
                        permDialogThisLaunch = true // B-补-19：本次冷启动的系统框名额已用掉
                    }
                }
            }

            // 通知权限（2026-10-09，#3 验收时发现）：安卓 13 起「装完清理 >500MB」的静默提示
            // 必须拿到 POST_NOTIFICATIONS，否则系统直接把通知丢掉（真机 `dumpsys package` 里
            // `POST_NOTIFICATIONS: granted=false`、`AppSettings importance=NONE`、通知列表为空，
            // 而代码这边"发了通知"看起来一切正常）。冷启动缺就补一次；拒绝只是没有通知，
            // 不影响任何功能（清理照跑、日志照写）。
            // B-补-19：同一次冷启动不排第二个系统框 —— 存储是必需项，先排它；通知只是可选
            // （拒绝仅影响装完清理的提示），让到下次冷启动，不跟存储框抢同一屏。
            if (!permDialogThisLaunch) runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    // 字面量收敛到 PermissionSetup.NOTIFICATIONS_PERMISSION（那边的 KDoc 本来
                    // 就写着「全文件只此一处」，原先这里是硬编码的重复一份）
                    val perm = PermissionSetup.NOTIFICATIONS_PERMISSION
                    val missing = androidx.core.content.ContextCompat.checkSelfPermission(this, perm) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    // §B 顺序 15：同样"每项最多自动问一次"（理由见上面存储那段）。
                    if (PermissionSetup.shouldAutoRequest(PermissionSetup.notifyAsked(this), missing)) {
                        PermissionSetup.markNotifyAsked(this)
                        androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(perm), 101)
                        permDialogThisLaunch = true // B-补-19：同上（此处也守住"只排一个"）
                    }
                }
            }

            // App 自更新（用户第四批）：启动后台查 releases，网络失败静默忽略，不打断用户。
            //
            // §B 顺序 15（2026-10-14）：**与权限引导互斥** —— 权限是门槛，先过门槛再谈更新。
            // 原先是无条件发起，更新 AlertDialog 会叠在权限页（甚至系统权限框）之上；
            // 原代码只在 UI 线程里守 isFinishing/isDestroyed，管不了「同时有两个框」这件事。
            // 跳过一次不影响功能：下个冷启动照常查（本就是"启动后台查一次、失败静默"）。
            //
            // B-补-19（2026-10-16）加一层：**本次冷启动排过系统权限框时也不查**。上面那条只
            // 保证"不叠在权限页上"，管不了"不叠在系统权限框上" —— 系统框是系统的独立窗口，
            // 应用内的 AlertDialog 拦不住它，只能自己不发起。系统框每项权限一辈子只排一次，
            // 因此最多跳过一个冷启动（标记一次性，见 PermissionSetup）。
            if (!permDialogThisLaunch) checkAppUpdateInBackground()
        }

        // 存储主路径引导（E-005 修订 / P1，2026-10-06）：MANAGE_EXTERNAL_STORAGE 升为正式主路径。
        // **2026-10-11 起这里那个「开启完整存储访问」AlertDialog 撤掉**，由权限页的
        // 「所有文件访问」行替代（同一件事、同一个系统页，只是不再单独弹窗抢在前面）；
        // 设置页入口照旧保留。连带效果：prefs 键 `manage_prompted` 全仓只剩这条注释，不再写也不再读（老装机残留，无代码读取）。
        // ⚠️ 第㉕刀（2026-10-15，targetSdk 28 → 36）：旧注释说「该开关只对 target≥30 的 App
        // 存在、本 App 恒为 false」—— 抬到 36 后该前提消失，开关现在**可达且就是共享存储的
        // 唯一通道**（READ/WRITE 运行时权限在 30+ 已 inert）。权限页的「共享存储」必需项已
        // 经 ProotLauncher.storageGranted 双通道判定路由到它，不再有"永远完不成"的页面。

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
                    ZhengdaoApp(
                        startInTerminal = openTerminal,
                        lastRoute = lastRoute,
                        guidePermissions = permissionSetupPending,
                    )
                }
            }
        }
    }

    /**
     * 非官方签名时弹一次提示（2026-10-09，ERRATA E-065）。
     *
     * 只在 [com.example.zhengdao.util.SigningCheck.Result.UNOFFICIAL] 时出现 ——
     * 官方包、以及读不到签名的情况都**安静通过**（宁可漏报，不误报：
     * 把官方包说成"被改过"会让用户白白怀疑自己下的东西）。
     */
    private fun warnIfUnofficialSignature() {
        val result = runCatching {
            com.example.zhengdao.util.SigningCheck.check(this)
        }.getOrNull() ?: return
        if (result != com.example.zhengdao.util.SigningCheck.Result.UNOFFICIAL) return
        runCatching {
            android.widget.Toast.makeText(
                this,
                getString(R.string.main_unofficial_build),
                android.widget.Toast.LENGTH_LONG,
            ).show()
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
                        .setTitle(getString(R.string.main_update_title))
                        .setMessage(
                            getString(
                                R.string.main_update_msg,
                                tag.removePrefix("v"),
                                BuildConfig.VERSION_NAME,
                            )
                        )
                        .setPositiveButton(getString(R.string.main_update_go)) { _, _ ->
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://github.com/pisces19860207/zhengdao/releases")
                                )
                            )
                        }
                        .setNegativeButton(getString(R.string.main_update_ignore), null)
                        .show()
                }
            } catch (_: Throwable) {
                // 静默：更新检查失败绝不打扰用户
            }
        }.apply { isDaemon = true; name = "zd-update-check" }.start()
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
        // D-4（第⑱刀）：内存压力下回收玄览 WebView（仅 COMPLETE 档且玄览离线时才真回收）。
        com.example.zhengdao.ui.browser.BrowserHolder.onTrimMemory(level)
    }
}

/**
 * 进终端的唯一 Intent 构造器（2026-10-07：任务栈曾叠到 10 个终端页）。
 *
 * 为什么必须统一在这里加 flag：
 *   默认 standard 下每次 startActivity 都新建实例。五个入口（怡然安装/启动、逍遥 Tab、
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
fun ZhengdaoApp(
    startInTerminal: Boolean = false,
    lastRoute: String? = null,
    // 首启是否需要先走一遍「权限」页（判定在 MainActivity.onCreate，见那里的说明）。
    // 默认 false：不传的调用方（预览/测试）行为与改动前完全一致。
    guidePermissions: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val nav = rememberNavController()
    // 可观察状态：环境装/没装会变（终端里装完、设置里卸载），必须跟着变。
    val installed by com.example.zhengdao.ui.RootfsState.installed
    val routePrefs = context.getSharedPreferences("zhengdao-ui", android.content.Context.MODE_PRIVATE)

    // 回到前台就重读一次：装在终端里完成（RootfsInstaller 写完成标记）后返回主页，
    // 欢迎页与主页的环境状态必须立刻跟上，不能等杀进程重进。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
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

    // 首启「权限」页（2026-10-11）：叠在起始页之上，**绝不当 startDestination**——
    // 同上面 startRoute 处的教训：起始页底下没有返回栈，popBackStack 无处可退（真机实测返回键与
    // 右滑全失效）。起始页是 welcome（首次安装）还是 home（老装机补漏）都成立。
    //
    // 顺序有意为之：本效果写在下面的"恢复上次页面"之前，于是恢复页会看到 cur 不是起始页
    // 而跳过这一次恢复——权限是门槛，先过门槛再谈回到哪一页（下次冷启动照常恢复）。
    androidx.compose.runtime.LaunchedEffect(guidePermissions) {
        if (guidePermissions) {
            nav.navigate(PermissionSetup.ROUTE) { launchSingleTop = true }
        }
    }

    // 恢复上次页面：叠在 home 之上（返回键退回首页，不再有死返回）
    //
    // 2026-10-08 加固：真机上出现过"从设置页返回后整页纯白"（当次 2/2，随后 9 次导航 0 复现），
    // 纯白时 uiautomator 一个节点都读不到、logcat 也没有异常，日志里没有任何路由痕迹 ⇒ 无法归因。
    // 这里做两件事：① 只在确实站在起始页时才叠加恢复页，避免在任何中间态下再造一次
    // "栈里只剩恢复页"的局面（那正是 2026-10-06 c7e59dc7 修过的死返回）；② 记一行路由面包屑，
    // 万一再复现，日志里至少能看到最后到达/来自哪个页面、栈是不是退空了。
    androidx.compose.runtime.LaunchedEffect(lastRoute) {
        val cur = nav.currentBackStackEntry?.destination?.route
        // B-补-15（2026-10-16）：白名单化 —— 只有确实还在 NavHost 里的路由才恢复，
        // 老版本残留的死值（如已删除的 "plugins"）按「无可恢复页面」处理，见 RESTORABLE_ROUTES。
        val restore = lastRoute?.takeIf { it in RESTORABLE_ROUTES }
        if (!startInTerminal && restore != null && (cur == "home" || cur == "welcome")) {
            nav.navigate(restore) { launchSingleTop = true }
        }
    }

    // 记录当前页面（供冷启动恢复；terminal 是独立 Activity，见下）
    androidx.compose.runtime.LaunchedEffect(nav) {
        nav.currentBackStackEntryFlow.collect { entry ->
            routePrefs.edit().putString("last_route", entry.destination.route).apply()
            // 路由面包屑（白屏排查用，见上）：一次导航一行，正常使用一天也就几行。
            runCatching {
                com.example.zhengdao.rootfs.RunLog.log(
                    "路由 → ${entry.destination.route}" +
                        "（来自 ${nav.previousBackStackEntry?.destination?.route ?: "无"}）"
                )
            }
        }
    }

    // 跳终端（带可选自动命令）：逍遥入口与设置页「在终端中清理缓存」共用同一实现，
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
                context, "无法打开终端：${com.example.zhengdao.util.HumanizeError.title(t, context.resources)}", android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── 全图转场（B-补-26，2026-10-15）────────────────────────────────────
    // Compose Navigation 默认是**瞬间切换**：点「设置」整页凭空换成设置页、返回又凭空换回来。
    // 本项目是 iOS 浅色主题、层级约定（根页大标题 / 二级页导航栏标题）也是照 iOS 的分层抄的，
    // 分层却没有过渡动画＝"页面是贴上去的"。这里给全图统一补上 iOS push/pop 那套转场：
    //   推入（enter/exit）  ：新页从右滑入 25% ＋ 淡入，旧页向左退 25% ＋ 淡出
    //   返回（popEnter/popExit）：整套反向
    // 25% 而非 100%：100% 是全屏平移，长页面（设置页 2000+ 行）滑起来像拉帘；25% 是
    // iOS `UINavigationController` 的实际取值量级，配 220ms 手感最接近"推入"而非"滑动"。
    // 时长取 220ms 的依据：iOS push 约 300ms，但 Compose 的 AnimatedContent 起停各有一帧
    // 开销，220ms 实测既不拖沓也不会让人看不清过渡。
    //
    // ⚠️ 不覆盖每个 composable 自己的转场：这里设的是**全图默认值**，个别页若要特殊转场
    // （如欢迎页首次安装想用纯淡入）可在 composable(...) 里单独覆盖，不必回来改这里。
    //
    // ⚠️ 本刀只做 **NavHost 内**的 Compose 转场。派工单原文同时提了 `overridePendingTransition`
    // （Activity 级转场，如 TerminalActivity 整屏推入），那要动两个 Activity 的启动处，
    // 而 TerminalActivity 属在跑区域（第⑯刀占着错误文案区）⇒ 本刀不碰，留给独立一刀。
    // 注意 slide 系的 animationSpec 是 IntOffset 型、fade 系是 Float 型 —— 两个不能共用
    // 一个 spec（Kotlin 泛型不匹配，编译期就报错），故分别建。
    val navSlideSpec = tween<IntOffset>(durationMillis = 220, easing = FastOutSlowInEasing)
    val navFadeSpec = tween<Float>(durationMillis = 220, easing = FastOutSlowInEasing)
    NavHost(
        navController = nav,
        startDestination = startRoute,
        enterTransition = {
            slideInHorizontally(initialOffsetX = { it / 4 }, animationSpec = navSlideSpec) +
                fadeIn(animationSpec = navFadeSpec)
        },
        exitTransition = {
            slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = navSlideSpec) +
                fadeOut(animationSpec = navFadeSpec)
        },
        popEnterTransition = {
            slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = navSlideSpec) +
                fadeIn(animationSpec = navFadeSpec)
        },
        popExitTransition = {
            slideOutHorizontally(targetOffsetX = { it / 4 }, animationSpec = navSlideSpec) +
                fadeOut(animationSpec = navFadeSpec)
        },
    ) {
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
                // 跳过环境安装直进主界面：**抱朴不依赖 Debian**（宿主 bionic serve，
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
        composable(PermissionSetup.ROUTE) {
            // 首启「权限」页（2026-10-11）：一屏列全几项权限，逐项点开授权，
            // 底部主按钮「完成，进入证道」／「稍后再说」。系统栏由该页自行避让
            //（不套设置页那种"返回 + 居中标题"顶栏：这是首启的过渡页，出口只有底部那一个按钮，
            //  少一个"返回"歧义入口——按返回＝不写已引导标记，见该页 KDoc）。
            PermissionSetupScreen(
                onDone = {
                    // 摘掉本页再进主界面：只 navigate 不 popUpTo 会把权限页留在家页下方，
                    // 用户一按返回又弹回来（正是"反复弹"）。
                    nav.navigate("home") {
                        popUpTo(PermissionSetup.ROUTE) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable("settings") {
            // 设置页（二级页）：顶栏＝「‹ 返回 + 居中标题」，内容区不再重复写一遍标题
            //（旧版外层"设置"与页内大字"设置"上下叠着，是最扎眼的重复）。
            // 层级约定：根页（怡然）用大标题，二级页用导航栏标题——与 iOS 的分层一致。
            // edge-to-edge 下必须自行避让系统栏，否则返回栏会被状态栏压住、末项被导航栏遮挡。
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                // B-补-8（2026-10-16）：顶栏不再是写死高度，理由见 SettingsCommon.SettingsTopBar。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    TextButton(
                        // popBackStack 返回 false = 栈里已经没有可退的页面（历史死返回）。
                        // 此时若什么都不做，NavHost 就没有内容可画 → 整页纯白
                        //（2026-10-08 真机现象：设置页返回后整窗纯白、零可读节点）。
                        // 兜底：显式回 home 并清栈重建，保证任何时候都有页面在显示。
                        onClick = {
                            if (!nav.popBackStack()) {
                                runCatching {
                                    com.example.zhengdao.rootfs.RunLog.log(
                                        "路由：设置页返回时栈已空，兜底回 home（白屏防御）"
                                    )
                                }
                                nav.navigate("home") {
                                    popUpTo(nav.graph.id) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BackChevron(tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.common_back), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(
                        text = stringResource(R.string.common_settings),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                SettingsScreen(
                    onOpenTerminal = openTerminal,
                )
            }
        }
    }
}

/**
 * 底栏四个 Tab 的索引 —— **唯一真相源**（§B 顺序 33，2026-10-14）。
 *
 * 顺序：抱朴 / 逍遥 / 玄览 / 怡然。用户要求「玄览排到逍遥之后」⇒ 与怡然交换了 2/3。
 *
 * 为什么要抽常量：这次交换暴露了隐患 —— 全文件原有 **20 处硬编码索引**
 * （`when (tab)` 分支、底栏 selected/onClick/图标、`animateToTop`、ON_RESUME 切 Tab、
 * 抱朴页的 onExit —— 清场后一度是占位页 BaopuRebuilding，刀 1 起又是真页面 BaopuScreen），
 * 漏改任何一处都会**静默错位**。最隐蔽的一处是初始值
 * `mutableIntStateOf(2)`：2 原本是怡然、交换后变成玄览，不跟着换常量的话，
 * 启动页会无声无息地从"怡然"漂到"玄览"。改顺序时只动这里。
 */
internal object TabIndex {
    /** 抱朴（真实）：Compose 原生对话（内嵌 OpenCode）。 */
    const val BAOPU = 0

    /** 逍遥（自由）：终端（独立整屏 Activity；本 Tab 本身只是回程落点）。 */
    const val TERMINAL = 1

    /** 玄览（期望）：浏览器（阶段 1 新增）。 */
    const val BROWSER = 2

    /** 怡然（幸福）：Agent 管理。 */
    const val AGENTS = 3
}

/** 首页骨架：Agent Tab（卡片列表）/ 终端 Tab（进入全屏终端）。 */
@Composable
fun HomeTabs(
    onOpenTerminal: (autocmd: String?, agentId: String?) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    // P2-1（§B 顺序 2，2026-10-14）：必须是 **rememberSaveable**，不能是 remember。
    // `remember` 不跨**配置变更**（旋转屏幕 / 切深浅色 / 改字体大小）保留 ⇒ 转一次屏，
    // tab 就被打回初始值——人正停在逍遥（终端）或玄览，一转屏被甩回怡然。
    // rememberSaveable 把值写进 Bundle，配置变更后原样恢复（同文件下面两个
    // listState 走 rememberSaveable 也是这个道理，见 :559 那段注释）。
    // 初始值语义不变：首次进 App 落怡然（与原字面 2 一致）。
    var tab by androidx.compose.runtime.saveable.rememberSaveable {
        mutableIntStateOf(TabIndex.AGENTS)
    }

    // 切 Tab 保留滚动位置（K2）——把两个 tab 的 LazyListState 提到 HomeTabs 顶层。
    //
    // 之前 listState 在各 tab 内部用 rememberLazyListState() 创建：切走 tab 时整个 tab
    // 的 Composable 退出 Composition，state 跟着 destroy；切回时拿到的是"出厂态"。
    // 用户实感：怡然翻到第 8 个 Agent，切抱朴再切回，又被甩到顶。
    //
    // 提到外层后 state 跟 HomeTabs 同寿命；rememberLazyListState() 内部用 saver 走
    // rememberSaveable，顺带覆盖了"配置变更（旋转、深浅色）"也要保留位置。
    //
    // K1 双击滚顶：把这两个 state 通过 onTabDoubleTap lambda 注入底栏，双击触发
    // animateScrollToItem(0)。判定用的是"300ms 内再点同一个 Tab"（见
    // [lastTabClickAt] 与下面 NavigationBarItem 的 onClick），所以**只有点当前 Tab
    // 才会滚顶**；点别的 Tab 只是切 Tab，不动列表。
    val homeListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val baopuListState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 玄览：外部打开请求的切 Tab 消费（§4.6，2026-10-14 第 ② 刀接线）。
    // TerminalActivity 点链接时置请求 + finish() 回主页；这里 ON_RESUME 时切到玄览，
    // pendingUrl 由 BrowserHolder 持有、BrowserScreen 组合时消费（不经过此层状态）。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    // 玄览多标签（第⑨刀 B）：回前台恢复活跃标签（onResume + 渲染优先级升回 IMPORTANT）
                    com.example.zhengdao.ui.browser.BrowserTabManager.resumeActive()
                    if (com.example.zhengdao.ui.browser.BrowserHolder.consumeTabRequest()
                    ) {
                        tab = TabIndex.BROWSER
                    }
                }
                // 退后台（切别的 App、或跳终端独立 Activity）：全部标签 onPause + 降 WAIVED
                // （§4.2「后台标签降优先级」的进程级落实；回前台由上面 ON_RESUME 恢复）。
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    com.example.zhengdao.ui.browser.BrowserTabManager.pauseAll()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // K2 的另外两个伴随量（baopuFollow / baopuPositionedSession）2026-10-16 随旧抱朴页
    // 一并退役：旧 UI 壳已删，刀 1 的新页面自己管「是否跟随最新」与「定位过哪个会话」。
    val scope = androidx.compose.runtime.rememberCoroutineScope { CoroutineCrashHandler }

    // K1（双击 Tab 滚顶）：在 HomeTabs 顶层记"上一次点同一个 Tab 的时间"。
    // 300ms 内再次点同一 Tab → 视为双击 → animateScrollToItem(0)。
    // 终端 Tab 没 listState（点了直接跳 Activity），双击分支对它 no-op。
    //
    // 为什么不在 Composable 内做手势检测（detectTapGestures onDoubleTap）：
    //   NavigationBarItem 已自带 onClick 包了 clickable，叠加 pointerInput 会与 clickable
    //   抢事件，得自己处理 tap/doubleTap 互斥。这里走"时间窗口判定"逻辑等价（300ms
    //   是 Material Design 文档里 Tap-Double 区分的推荐值），代码少一半。
    // 2026-10-10 改：iOS Tab 标签 = 10sp Medium（iOS HIG 文档值）。
    // 不走 MaterialTheme.typography.labelSmall：PluginsScreen.kt:133 把它当 13sp
    // 副标题用，全局改会破坏既有视觉。本次只在 HomeTabs 局部用。
    val tabLabelStyle = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.12.sp,
    )

    val lastTabClickAt = androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val now = { android.os.SystemClock.uptimeMillis() }

    fun animateToTop(target: Int) {
        when (target) {
            TabIndex.BAOPU -> scope.launch { baopuListState.animateScrollToItem(0) }
            TabIndex.AGENTS -> scope.launch { homeListState.animateScrollToItem(0) }
            // 逍遥（终端）：点了就跳 TerminalActivity，不存在"滚顶"概念，no-op；
            // 玄览：页面区不是列表，同样 no-op。
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
            TabIndex.BAOPU -> { tab = TabIndex.BAOPU }
            TabIndex.TERMINAL -> { tab = TabIndex.TERMINAL; onTerminalClicked() }
            TabIndex.BROWSER -> { tab = TabIndex.BROWSER }
            TabIndex.AGENTS -> { tab = TabIndex.AGENTS }
        }
    }

    Scaffold(
        bottomBar = {
            // 底栏走 iOS 语言：与页面同为白底、靠一条 0.5dp 发丝线分隔。
            // 刻意不用 M3 默认的 tonalElevation 阴影——浅色主题下那会压出一条灰带，
            // 与全局"白卡片 + 发丝描边"的语言冲突（同一处理见怡然卡片）。
            Column {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                NavigationBar(
                    modifier = Modifier.height(56.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    // 选中＝systemBlue 图标/文字 + 淡蓝胶囊；未选中＝次要文字色
                    val tabColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NavigationBarItem(
                        selected = tab == TabIndex.BAOPU,
                        // 抱朴 = Tab 内嵌 TerminalView，直跑宿主 bionic opencode TUI
                        //（不经过 PRoot；XDG 独立 = /data/data/证道/files/taiji/）
                        // K1：双击 = 滚顶（见 HomeTabs 顶部的 lastTabClickAt 注释）。
                        onClick = { onTabClicked(TabIndex.BAOPU) {} },
                        icon = { BaoPuIcon(tab == TabIndex.BAOPU) },
                        label = { Text(stringResource(R.string.nav_taiji), style = tabLabelStyle) },
                        colors = tabColors,
                    )
                    NavigationBarItem(
                        selected = tab == TabIndex.TERMINAL,
                        // 点击直接进全屏终端（用户定：简单明了，不要占位页多一跳）
                        //
                        // ⚠️ `tab = TabIndex.TERMINAL` 不能省（2026-10-07 用户报「红点返回回的不是主界面，
                        //    是抱朴 tab」）：人在抱朴页（tab=0）点逍遥进终端时若不改选中态，
                        //    终端一整页盖在上面时看不出问题，等红点关掉终端就露馅了——
                        //    底部高亮还停在抱朴，用户以为“返回到了 opencode”。
                        //    逍遥是进终端的那个 tab，用过终端就该停在逍遥。
                        // K1：终端 tab 双击不滚顶（点了就跳 Activity，列表根本不存在）。
                        onClick = { onTabClicked(TabIndex.TERMINAL) { onOpenTerminal(null, null) } },
                        icon = { XiaoYaoIcon(tab == TabIndex.TERMINAL) },
                        // 改名链（留痕）：2026-10-08「洞天」→「终端」（与全站功能文案统一）；
                        // 2026-10-14 派工单「终端」→「逍遥」（四 Tab 祝福命名定稿：
                        // 抱朴/逍遥/怡然/玄览 = 真实/自由/幸福/期望）。Tab 名走资源
                        // （nav_terminal），底栏/空态/WelcomeScreen 同源，不会再分裂。
                        // 注意：2026-10-16 起**代码与图标名已与 Tab 名同一口径**（XiaoYaoIcon ⇒ XiaoYaoIcon、
                        // 图形换成流云「逍遥」），本段只讲文案沿革；再没有"代码里仍称逍遥"这回事。
                        label = { Text(stringResource(R.string.nav_terminal), style = tabLabelStyle) },
                        colors = tabColors,
                    )
                    NavigationBarItem(
                        selected = tab == TabIndex.BROWSER,
                        // 玄览（期望）：浏览器。**排在逍遥之后**（§B 顺序 33，2026-10-14
                        // 用户要求：抱朴 / 逍遥 / 玄览 / 怡然）。
                        // 页面区不是列表，双击滚顶 no-op（animateToTop 的 when 不含它）。
                        onClick = { onTabClicked(TabIndex.BROWSER) {} },
                        icon = { XuanLanIcon(tab == TabIndex.BROWSER) },
                        label = { Text(stringResource(R.string.nav_browser), style = tabLabelStyle) },
                        colors = tabColors,
                    )
                    NavigationBarItem(
                        selected = tab == TabIndex.AGENTS,
                        // K1：双击 = 滚到怡然 Agent 列表顶。
                        onClick = { onTabClicked(TabIndex.AGENTS) {} },
                        icon = { YiRanIcon(tab == TabIndex.AGENTS) },
                        label = { Text(stringResource(R.string.nav_agents), style = tabLabelStyle) },
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
                // 抱朴 Tab 的固定顶栏与输入框正是这种情况，故在此声明「系统栏内边距已由本层应用」。
                // ⚠️ IME inset 不在 contentWindowInsets 内，imePadding() 不受影响，键盘避让照常。
                .consumeWindowInsets(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            // 分支沿用原物理顺序（语义见 [TabIndex]）：抱朴 → 怡然 → 逍遥 → 玄览。
            when (tab) {
                // 抱朴：刀 1 起是真页面（自研 agent：状态机 + 守卫管线 + 两个读工具）。
                // 旧线（opencode serve）与占位页的历史见 docs/ERRATA.md E-140 与
                // ui/baopu/BaopuRebuilding.kt 的 KDoc；onExit 仍落**怡然**（另一个有真实内容的 Tab）。
                TabIndex.BAOPU -> com.example.zhengdao.ui.baopu.BaopuScreen(
                    onExit = { tab = TabIndex.AGENTS },
                )
                // 怡然（幸福）：Agent 管理（OpenCode 已内置为抱朴，不在怡然展示）
                TabIndex.AGENTS -> HomeScreen(
                    onOpenTerminal = onOpenTerminal,
                    onOpenSettings = onOpenSettings,
                    listState = homeListState,
                )
                // 逍遥：终端本身是独立的整屏页面（不在 Tab 里内嵌），所以这里只是"回程落点"。
                // ⚠️ 以前这里是 `else -> {}`（全白）——红点关掉终端后落在逍遥会看到一片空白，
                //    用户完全无从判断发生了什么。给一个诚实的空态：说清终端在哪、给一个再进去的按钮。
                TabIndex.TERMINAL -> TerminalTabEmptyState(onOpenTerminal = { onOpenTerminal(null, null) })
                // 玄览：浏览器（用户模式 MVP，阶段 1）。WebView 实例由 BrowserHolder
                // 跨 Tab 保活；外部打开入口（终端链接点击等）走 BrowserHolder.pendingUrl
                // + 上面 ON_RESUME 的切 Tab 消费（§4.6，第 ② 刀接线）。
                TabIndex.BROWSER -> com.example.zhengdao.ui.browser.BrowserScreen()
                else -> {}
            }
        }
    }
}

/**
 * 逍遥 Tab 的空态 —— 只是"从终端回来的落点"。
 *
 * 为什么需要：终端是**独立的整屏 Activity**（不在 Tab 里内嵌，见 TerminalActivity），
 * 所以逍遥这个 Tab 本身没有内容。红点 / 左边缘右滑关掉终端后落在这里，
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
            // 与底栏 Tab 文案保持一致：同一个 Tab，同一个名字（走 nav_terminal 资源，同源不再分裂）
            text = stringResource(R.string.nav_terminal),
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

// ── 底部 Tab 自绘图标：四枚一套（Compose Path，零第三方素材）──
//
// 为什么整组重绘（2026-10-16，与四 Tab 改名同批）：
//   旧四枚是给旧 Tab 名画的（抱朴 / 逍遥 / 怡然 / 玄览），Tab 名已定稿为祝福名
//   （抱朴 / 逍遥 / 玄览 / 怡然），图形跟着改名，一套旧稿整组退役：
//   · 旧抱朴在真机上是一片"叶子/旗子"——根因是 S 分界用 close() 直线拉回形成月牙；
//   · 旧月洞门（逍遥）、圆腹鼎（怡然）随旧 Tab 名退出；
//   · 旧杏眼在 26dp 下读成"鱼"（上下弧太尖），换成明潭（环 + 偏心瞳）。
//   旧稿全文留痕在 `证道设计报告/`（三件：AI 提示词 / 新设计.kt / 新设计稿.html）。
//
// 四枚的剪影分工（一眼分得开，才有资格占底栏）：竖（芽）/ 横（流云）/ 圆（明潭）/ 舟（扁舟）。
// 全套设计语言（四枚必须像同一个人画的）：
//   · 画布 26dp，光学框 20dp（四边各留 ~3dp 呼吸，Tab 图标不做满框）
//   · 描边 1.9f + 圆头 + 圆角连接（StrokeCap.Round / StrokeJoin.Round），四枚共用 tabStroke()
//   · 每枚 ≤ 3 笔（旧版 4~7 笔，26dp 里挤成一团）
//   · 只在"点睛"处用实心（芽的叶、明潭的瞳），其余一律描边——26dp 下不糊
/** 图标画布边长（dp）。 */
private const val TAB_ICON_DP = 26

/** 统一描边宽度（px）。26dp @560dpi ≈ 91px，1.9f 约等于 2dp，不粗不细。 */
private const val TAB_STROKE = 1.9f

/** 统一取色：选中＝主色（systemBlue），未选中＝次要文字色。 */
@Composable
private fun tabIconColor(selected: Boolean) =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

/**
 * 2026-10-11 加：Tab 图标的选中态弹动（iOS SF Symbols 变体切换的观感）。
 *
 * 选中＝弹到 1.0，未选中＝收在 0.86 —— 切换时 spring 过渡，
 * 缩略比里插一段 overshoot（≈8%），像"啪地弹到位"。
 *
 * ⚠️ 为什么改 scale 而不是改 Canvas 内的几何：
 *   26dp 图标里任何几何改动都要重算 Path；scale 是**整块缩放**，
 *   四个图标的绘制代码一行不动，也不破坏 TAB_ICON_DP 的光学框契约
 *   （呼吸留白等比缩放，视觉上仍是同一套设计语言）。
 *
 * ⚠️ 为什么不做「页面内容 Crossfade 转场」：
 *   淡出需要新旧两页在过渡期**同时存在**，而 tab=0 的抱朴页（清场前是 `BaopuScreen`）
 *   会在 LaunchedEffect 里开一条 SSE 长连接 —— 重叠 160ms 就是**同时两条连接**，
 *   且滚动/会话定位状态在过渡中途会被读两次。图标动效没有这个副作用。
 */
@Composable
private fun tabIconScale(selected: Boolean): Float {
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (selected) 1f else 0.86f,
        animationSpec = androidx.compose.animation.core.spring<Float>(
            dampingRatio = 0.55f,
            stiffness = 420f,
        ),
        label = "tab-icon-scale",
    )
    return scale
}

/** 统一描边样式（圆头圆角）——四个图标共用，保证线感一致。 */
private fun tabStroke() = Stroke(
    width = TAB_STROKE,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

/**
 * 一片叶子（实心梭形）：叶柄在 [ax, ay]，叶尖在 [tx, ty]，[b] 是鼓度（法向偏移，符号决定往哪边鼓）。
 *
 * 两段三次贝塞尔首尾相接：去程往 +法向鼓 b，回程往 −法向鼓 b，闭合后是一条对称梭形。
 * 控制点取在 0.33 / 0.67 —— 根部钝、尖端利，比正圆更像叶子（26dp 下这一口气很关键）。
 * 法向 = 叶轴转 90°，所以同一组参数翻个符号就是镜像叶。
 */
private fun leafPath(w: Float, h: Float, ax: Float, ay: Float, tx: Float, ty: Float, b: Float): Path {
    val dx = tx - ax
    val dy = ty - ay
    val len = kotlin.math.sqrt(dx * dx + dy * dy)
    val nx = -dy / len
    val ny = dx / len
    val off = b * w
    return Path().apply {
        moveTo(w * ax, h * ay)
        cubicTo(
            w * (ax + dx * 0.33f) + nx * off, h * (ay + dy * 0.33f) + ny * off,
            w * (ax + dx * 0.67f) + nx * off, h * (ay + dy * 0.67f) + ny * off,
            w * tx, h * ty,
        )
        cubicTo(
            w * (ax + dx * 0.67f) - nx * off, h * (ay + dy * 0.67f) - ny * off,
            w * (ax + dx * 0.33f) - nx * off, h * (ay + dy * 0.33f) - ny * off,
            w * ax, h * ay,
        )
        close()
    }
}

/**
 * 抱朴：一株破土的芽（茎 = 生长，两片实心叶 = 未雕之朴）。
 *
 * 2026-10-16 定稿（用户逐枚拍板，S1「错开」）：四枚图标里唯一的**竖向**剪影 ——
 * 与逍遥的横流云、玄览的圆、怡然的舟互不撞车。叶走**实心**（26dp 下描边叶
 * 会读成蜻蜓），茎仍用统一描边 tabStroke()，与其余三枚是同一支笔。
 */
@Composable
fun BaoPuIcon(selected: Boolean) {
    val color = tabIconColor(selected)
    val scale = tabIconScale(selected)
    Canvas(modifier = Modifier.size(TAB_ICON_DP.dp).graphicsLayer { this.scaleX = scale; scaleY = scale }) {
        val stroke = tabStroke()
        val w = size.width
        val h = size.height

        // 叶先画（茎随后压上去，接缝干净）
        drawPath(leafPath(w, h, 0.50f, 0.62f, 0.18f, 0.32f, 0.095f), color)
        drawPath(leafPath(w, h, 0.50f, 0.46f, 0.82f, 0.16f, -0.095f), color)

        // 茎：一道微弯的有机曲线（先左倾再回正，避免读成"旗杆"）
        val stem = Path().apply {
            moveTo(w * 0.50f, h * 0.95f)
            cubicTo(w * 0.44f, h * 0.74f, w * 0.56f, h * 0.56f, w * 0.50f, h * 0.30f)
        }
        drawPath(stem, color, style = stroke)
    }
}

/**
 * 逍遥：一笔流云（主流云 + 上方一短横远云）。
 *
 * 《逍遥游》"乘云气，御飞龙"。长身微弧一笔，右端向上卷成云头；
 * 全套图标里唯一的**横向流动**形，与抱朴的"竖静"相对。2 笔。
 * 云头 = 圆心 (0.78, 0.48)、r 0.13 的圆弧，从下端起逆时针 300°，
 * 开口朝左下 —— 缺口的角度是这朵云好不好认的关键，别随手改。
 */
@Composable
fun XiaoYaoIcon(selected: Boolean) {
    val color = tabIconColor(selected)
    val scale = tabIconScale(selected)
    Canvas(modifier = Modifier.size(TAB_ICON_DP.dp).graphicsLayer { this.scaleX = scale; scaleY = scale }) {
        val stroke = tabStroke()
        val w = size.width
        val h = size.height

        val cloud = Path().apply {
            moveTo(w * 0.12f, h * 0.64f)
            cubicTo(w * 0.32f, h * 0.59f, w * 0.56f, h * 0.67f, w * 0.78f, h * 0.61f)
            arcTo(
                Rect(w * 0.65f, h * 0.35f, w * 0.91f, h * 0.61f),
                startAngleDegrees = 90f,
                sweepAngleDegrees = -300f,
                forceMoveTo = false,
            )
        }
        drawPath(cloud, color, style = stroke)

        // 远云（一短横，拉开层次）
        drawLine(color, Offset(w * 0.26f, h * 0.34f), Offset(w * 0.54f, h * 0.34f), stroke.width, StrokeCap.Round)
    }
}

/**
 * 怡然：一叶扁舟（船体一笔 + 桅杆 + 三角帆）。
 *
 * 《赤壁赋》"驾一叶之扁舟，举匏樽以相属"——怡然自得之形。
 * 船底一笔（左舷起、底部鼓、右舷收、回起点闭合＝甲板一条直线），
 * 桅杆穿过帆压到船体，帆是三角（竖直左缘与桅杆同线）。3 笔，
 * 四枚里唯一的"载具"剪影。用户 2026-10-16 定稿（Y2 一叶扁舟）。
 */
@Composable
fun YiRanIcon(selected: Boolean) {
    val color = tabIconColor(selected)
    val scale = tabIconScale(selected)
    Canvas(modifier = Modifier.size(TAB_ICON_DP.dp).graphicsLayer { this.scaleX = scale; scaleY = scale }) {
        val stroke = tabStroke()
        val w = size.width
        val h = size.height

        // 船体（闭合 = 甲板）
        val hull = Path().apply {
            moveTo(w * 0.08f, h * 0.64f)
            cubicTo(w * 0.26f, h * 0.90f, w * 0.74f, h * 0.90f, w * 0.92f, h * 0.64f)
            close()
        }
        drawPath(hull, color, style = stroke)

        // 桅杆（从帆顶压进船体）
        drawLine(color, Offset(w * 0.33f, h * 0.12f), Offset(w * 0.33f, h * 0.78f), stroke.width, StrokeCap.Round)

        // 帆（三角）
        val sail = Path().apply {
            moveTo(w * 0.33f, h * 0.14f)
            lineTo(w * 0.76f, h * 0.58f)
            lineTo(w * 0.33f, h * 0.58f)
            close()
        }
        drawPath(sail, color, style = stroke)
    }
}

/**
 * 玄览：明潭（描边环 + 偏心实心瞳）。
 *
 * "涤除玄览"——玄览即深观。旧的杏眼在 26dp 下读成"鱼"（上下弧太尖）；
 * 改为偏心瞳落在环里：偏心给出"注视的方向"，环给出"潭"的静。2 笔。
 * 用户 2026-10-16 在 6 个候选里拍板本方案（团镜/提梁镜/竖瞳均因小尺寸
 * 误读被否，见设计报告）。函数名未变，是"换体不换名"。
 */
@Composable
fun XuanLanIcon(selected: Boolean) {
    val color = tabIconColor(selected)
    val scale = tabIconScale(selected)
    Canvas(modifier = Modifier.size(TAB_ICON_DP.dp).graphicsLayer { this.scaleX = scale; scaleY = scale }) {
        val w = size.width
        val h = size.height

        // 环（潭）
        drawCircle(color = color, radius = w * 0.335f, center = Offset(w * 0.50f, h * 0.50f), style = tabStroke())
        // 瞳（偏心 = 有神）
        drawCircle(color = color, radius = w * 0.115f, center = Offset(w * 0.44f, h * 0.44f))
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
 * 右箭头（›）：设置行行尾用。与 [BackChevron] 同一套笔（圆头圆角 + 1.9f 描边）。
 * 2026-10-12 取代设置行里的 "›" 文本字符——字符箭头各机型字体渲染不一
 * （胖瘦高低没个准），与全套自绘图标不同路（走查报告 §五）。
 */
@Composable
fun ChevronRight(tint: Color, side: Dp = 13.dp) {
    Canvas(modifier = Modifier.size(side)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.34f, h * 0.12f)
            lineTo(w * 0.72f, h * 0.50f)
            lineTo(w * 0.34f, h * 0.88f)
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
