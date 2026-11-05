// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的公开资料：Compose UI Test 官方文档、UiAutomation.takeScreenshot 官方文档。
//
// 第四百六十六批（2026-11-05）**真机自检**：把真的确认卡（BaopuConfirmCard）渲染出来，
// 喂一张「批量删 10 个文件」的卡，截图落 app 外置私有目录供交付报告取证（adb pull）。
//
// 为什么这条必须在设备上跑：判据①要的是「**只弹一张卡**、卡上一眼看清 10 项」——
// 卡面是 Compose 真渲染出来的东西，字体回退、换行、折叠都要真机才看得见；
// JVM 侧只能钉「一次批量调用只产出一张卡」（`BatchOpsTest.B12`）与摘要文本。
//
// 跑法（真机，同 `AskGroupCardSelfCheckTest` 的纪律——**别跑** connectedDebugAndroidTest，
// AGP 跑完会卸载 App）：
// ```
// adb install -r app/build/outputs/apk/debug/app-debug.apk
// adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
// adb shell am instrument -w -e class com.example.zhengdao.ui.BatchConfirmCardSelfCheckTest \
//   com.example.zhengdao.test/androidx.test.runner.AndroidJUnitRunner
// adb pull /sdcard/Android/data/com.example.zhengdao/files/zd-466-batch-10.png
// ```
package com.example.zhengdao.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.zhengdao.R
import com.example.zhengdao.baopu.agent.ConfirmRequest
import com.example.zhengdao.baopu.agent.PathBatch
import com.example.zhengdao.baopu.agent.RunRules
import com.example.zhengdao.ui.baopu.BaopuConfirmCard
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchConfirmCardSelfCheckTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** 证据：整屏截图落到 app 外置私有目录（adb pull 拿得到，不进用户相册）。 */
    private fun saveShot(name: String) {
        val img = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = ctx.getExternalFilesDir(null) ?: return
        File(dir, name).outputStream().use { out ->
            img.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /** 造一批真文件（各 120 KB ⇒ 10 个共 1.2 MB，卡面上「共 X MB」有真数）。 */
    private fun files(count: Int): Pair<File, JSONArray> {
        val root = File(ctx.getExternalFilesDir(null), "zd466-ws").apply { mkdirs() }
        val arr = JSONArray()
        for (i in 1..count) {
            val f = File(root, "附件/截图-$i.png")
            f.parentFile?.mkdirs()
            f.writeBytes(ByteArray(120 * 1024))
            arr.put(f.absolutePath)
        }
        return root to arr
    }

    @Test
    fun 批量删十项_一张卡列全部_点批准只回调一次() {
        val (root, paths) = files(10)
        // 卡面参数就是 ② 段改写之后的形状（绝对路径），与生产同源
        val args = JSONObject().put(PathBatch.ARG_PATHS, paths)
        val summary = RunRules.confirmSummary("delete_path", args, root)
        val approved = AtomicInteger(0)

        compose.setContent {
            MaterialTheme {
                BaopuConfirmCard(
                    request = ConfirmRequest(
                        callId = "zd466-10",
                        tool = "delete_path",
                        summary = summary,
                        argsJson = args.toString(),
                    ),
                    onApprove = { approved.incrementAndGet() },
                    onReject = {},
                    projectRoot = root,
                )
            }
        }
        compose.waitForIdle()

        // 判据②：卡上三格——几个 / 可恢复 / 每项一行
        compose.onNodeWithText("10 个文件", substring = true).assertExists()
        compose.onNodeWithText("可恢复", substring = true).assertExists()
        compose.onNodeWithText("截图-1.png", substring = true).assertExists()
        assertEquals("卡面摘要必须真的是 10 项那一条：$summary", 10, summary.lines().count { it.startsWith("· ") })
        saveShot("zd-466-batch-10.png")

        compose.onNodeWithText(ctx.getString(R.string.baopu_confirm_approve)).performClick()
        compose.waitForIdle()
        assertEquals("点一次批准只能回调一次", 1, approved.get())

        // 自洁：测试造的文件自己删（不走 delete_path，那条要卡）
        root.deleteRecursively()
    }

    @Test
    fun 超过二十项_卡面折叠成共N项_不把按钮挤出屏幕() {
        val (root, paths) = files(25)
        val args = JSONObject().put(PathBatch.ARG_PATHS, paths)
        val summary = RunRules.confirmSummary("delete_path", args, root)

        compose.setContent {
            MaterialTheme {
                BaopuConfirmCard(
                    request = ConfirmRequest(
                        callId = "zd466-25",
                        tool = "delete_path",
                        summary = summary,
                        argsJson = args.toString(),
                    ),
                    onApprove = {},
                    onReject = {},
                    projectRoot = root,
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("25 个文件", substring = true).assertExists()
        compose.onNodeWithText("共 25 项", substring = true).assertExists()
        assertTrue("超过 20 项不许逐条列：$summary", summary.lines().count { it.startsWith("· ") } == 1)
        saveShot("zd-466-batch-25.png")

        root.deleteRecursively()
    }
}
