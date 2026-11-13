// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的公开资料：Compose UI Test 官方文档、UiAutomation.takeScreenshot 官方文档。
//
// 第六百二十九单（装配失败页）的真机自检：把**真页面**渲染出来，用真点击走一遍
// 「三个按钮都在 → 重试回调 → 导出诊断回调并露出路径 → 安全模式」，
// 并把截图落到 app 外置私有目录供交付报告取证（adb pull）。
//
// 跑法（真机，照 AskGroupCardSelfCheckTest 的纪律——**别用 connectedDebugAndroidTest**，
// AGP 跑完会卸载 App，外置私有目录里的截图会跟着没）：
// ```
// adb install -r app/build/outputs/apk/debug/*.apk
// adb install -r app/build/outputs/apk/androidTest/debug/*-androidTest.apk
// adb shell am instrument -w -e class com.example.zhengdao.ui.StartupFailureScreenSelfCheckTest \
//   com.example.zhengdao.test/androidx.test.runner.AndroidJUnitRunner
// adb pull /sdcard/Android/data/com.example.zhengdao/files/zd-629-*.png
// ```
package com.example.zhengdao.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.startup.StartupFailure
import com.example.zhengdao.ui.startup.SafeModeScreen
import com.example.zhengdao.ui.startup.StartupFailureScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StartupFailureScreenSelfCheckTest {

    @get:Rule
    val compose = createComposeRule()

    private val failure = StartupFailure(
        type = "IllegalStateException",
        typeFull = "java.lang.IllegalStateException",
        message = "policy 表装不上：assets/policy/rules.json 读不出来",
        stack = listOf("java.lang.IllegalStateException: policy 表装不上"),
        atMillis = 1_700_000_000_000L,
    )

    private fun saveShot(name: String) {
        val img = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = InstrumentationRegistry.getInstrumentation()
            .targetContext.getExternalFilesDir(null) ?: return
        File(dir, name).outputStream().use { out ->
            img.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    @Test
    fun failurePage_showsTitleAndThreeButtons_clickEach() {
        var retries = 0
        var exports = 0
        var safeModes = 0
        var dumpPath by mutableStateOf<String?>(null)

        compose.setContent {
            MaterialTheme {
                StartupFailureScreen(
                    failure = failure,
                    dumpPath = dumpPath,
                    onRetry = { retries++ },
                    onExport = {
                        exports++
                        dumpPath = "产物/启动失败-20261113-140501.txt"
                        true
                    },
                    onSafeMode = { safeModes++ },
                )
            }
        }

        // 判据①：标题是「抱朴没能启动：<异常类型>」，不是崩溃、也不是主界面
        compose.onNodeWithText("抱朴没能启动：IllegalStateException").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.onNodeWithText("导出诊断").assertIsDisplayed()
        compose.onNodeWithText("以安全模式继续").assertIsDisplayed()
        // 主界面的东西一个都不许在（分叉在组合最外层，抱朴页压根没被组合）
        compose.onAllNodesWithText("抱朴").assertCountEquals(0)
        compose.waitForIdle()
        saveShot("zd-629-fail-page.png")

        compose.onNodeWithText("导出诊断").performClick()
        compose.waitForIdle()
        assertEquals(1, exports)
        // 判据②：导出后当场把路径露出来（用户要能看见"文件在哪"）
        compose.onNodeWithText("产物/启动失败-20261113-140501.txt", substring = true).assertExists()
        saveShot("zd-629-fail-page-exported.png")

        compose.onNodeWithText("重试").performClick()
        compose.waitForIdle()
        assertEquals(1, retries)

        compose.onNodeWithText("以安全模式继续").performClick()
        compose.waitForIdle()
        assertEquals(1, safeModes)
    }

    @Test
    fun safeModeShell_limitedAndExplicit() {
        var logsOpened = 0
        var exits = 0

        compose.setContent {
            MaterialTheme {
                SafeModeScreen(
                    failure = failure,
                    dumpPath = null,
                    onOpenLogs = { logsOpened++ },
                    onExport = { true },
                    onExitSafeMode = { exits++ },
                )
            }
        }

        // 判据③：明示受限 + 只有设置与日志两个去处（没有抱朴）
        compose.onNodeWithText("安全模式 · 功能受限").assertIsDisplayed()
        compose.onNodeWithText("抱朴没有启动。现在只有「设置」和「日志」可用。").assertIsDisplayed()
        compose.onNodeWithText("上次失败：IllegalStateException").assertExists()
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onNodeWithText("日志").assertIsDisplayed()
        compose.onNodeWithText("退出安全模式并重新装配").assertIsDisplayed()
        // 抱朴页的入口（发送/会话）一个都不该出现
        compose.onAllNodesWithText("发送").assertCountEquals(0)
        compose.waitForIdle()
        saveShot("zd-629-safe-mode.png")

        compose.onNodeWithText("日志").performClick()
        compose.waitForIdle()
        assertEquals(1, logsOpened)

        compose.onNodeWithText("退出安全模式并重新装配").performClick()
        compose.waitForIdle()
        assertEquals(1, exits)
    }
}
