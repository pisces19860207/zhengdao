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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import com.example.zhengdao.ui.WelcomeScreen

/**
 * 应用入口：Compose Navigation 承载 欢迎页 → 首页（Agent/终端 双 Tab）。
 * 终端本体是独立 Activity（TerminalActivity，零改动复用），从首页 Tab 启动。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ZhengdaoApp()
                }
            }
        }
    }
}

@Composable
fun ZhengdaoApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val nav = rememberNavController()
    val installed = AppState.rootfsInstalled(context)
    NavHost(navController = nav, startDestination = "welcome") {
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
                onOpenTerminal = { autocmd ->
                    context.startActivity(
                        Intent(context, TerminalActivity::class.java).apply {
                            putExtra("autocmd", autocmd)
                        }
                    )
                },
            )
        }
    }
}

/** 首页骨架：Agent Tab（卡片列表）/ 终端 Tab（进入全屏终端）。 */
@Composable
fun HomeTabs(
    onOpenTerminal: (autocmd: String?) -> Unit,
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
                0 -> HomeScreen(onOpenTerminal = onOpenTerminal)
                else -> TerminalTabPlaceholder(onOpenTerminal = onOpenTerminal)
            }
        }
    }
}

/** 终端 Tab 首屏：一个明确的进入按钮（单会话制，终端是全屏独立页面）。 */
@Composable
private fun TerminalTabPlaceholder(onOpenTerminal: (autocmd: String?) -> Unit) {
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
        Button(onClick = { onOpenTerminal(null) }) {
            Text("进入终端")
        }
    }
}
