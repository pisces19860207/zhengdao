// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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

        // 通知栏「回到终端」：跳过欢迎页直达终端（M2）
        val openTerminal = intent?.getBooleanExtra("open_terminal", false) == true

        setContent {
            com.example.zhengdao.ui.theme.ZhengdaoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ZhengdaoApp(startInTerminal = openTerminal)
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
fun ZhengdaoApp(startInTerminal: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val nav = rememberNavController()
    val installed = AppState.rootfsInstalled(context)

    // 通知栏直达终端：落到主页后立即拉起终端（会话由 SessionManager attach 恢复）
    if (startInTerminal) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            context.startActivity(Intent(context, TerminalActivity::class.java))
        }
    }

    NavHost(navController = nav, startDestination = if (startInTerminal) "home" else "welcome") {
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
                onOpenTerminal = { autocmd, agentId ->
                    context.startActivity(
                        Intent(context, TerminalActivity::class.java).apply {
                            putExtra("autocmd", autocmd)
                            putExtra("agent_id", agentId)
                        }
                    )
                },
                onOpenSettings = { nav.navigate("settings") { launchSingleTop = true } },
            )
        }
        composable("settings") {
            // 设置页（含返回）：复用 Material3 顶栏由页面内实现，此处提供返回按钮容器
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { nav.popBackStack() }) { Text("← 返回") }
                    Text("设置", style = MaterialTheme.typography.titleMedium)
                }
                SettingsScreen()
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
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("AI") },
                    label = { Text("Agent") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text(">_") },
                    label = { Text("终端") },
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
                0 -> HomeScreen(onOpenTerminal = onOpenTerminal, onOpenSettings = onOpenSettings)
                else -> TerminalTabPlaceholder(onOpenTerminal = onOpenTerminal)
            }
        }
    }
}

/** 终端 Tab 首屏：一个明确的进入按钮（单会话制，终端是全屏独立页面）。 */
@Composable
private fun TerminalTabPlaceholder(onOpenTerminal: (autocmd: String?, agentId: String?) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Linux 终端", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "单会话制：进入即回到上次的 Debian 会话",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { onOpenTerminal(null, null) }) {
            Text("进入终端")
        }
    }
}
