// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的公开资料：Compose UI Test 官方文档、UiAutomation.takeScreenshot 官方文档。
//
// 第二百零三批（批量问卡）的真机自检：把真卡（BaopuQuestionGroupCard）渲染出来，
// 用真点击走一遍「必答挡提交 → 红字点名 → 数字非数字就地拒 → 按默认 → 填完提交」，
// 并把两张截图落到 app 外置私有目录供交付报告取证（adb pull）。
//
// 跑法（真机，同 ImeRegressionSelfCheckTest 的纪律——别跑 connectedDebugAndroidTest，
// AGP 跑完会卸载 App）：
// ```
// adb install -r app/build/outputs/apk/debug/*.apk
// adb install -r app/build/outputs/apk/androidTest/debug/*-androidTest.apk
// adb shell am instrument -w -e class com.example.zhengdao.ui.AskGroupCardSelfCheckTest \
//   com.example.zhengdao.test/androidx.test.runner.AndroidJUnitRunner
// adb pull /sdcard/Android/data/com.example.zhengdao/files/zd-203-*.png
// ```
package com.example.zhengdao.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.zhengdao.baopu.agent.AskQuestion
import com.example.zhengdao.baopu.agent.AskQuestionKind
import com.example.zhengdao.baopu.agent.AskUserOption
import com.example.zhengdao.baopu.agent.AskUserRequest
import com.example.zhengdao.ui.baopu.BaopuQuestionGroupCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AskGroupCardSelfCheckTest {

    @get:Rule
    val compose = createComposeRule()

    /** 正例的形状：1 张卡、3 项（SINGLE 必答 / NUMBER 带默认 / BOOL 必答）。 */
    private val request = AskUserRequest(
        question = "",
        options = emptyList(),
        questions = listOf(
            AskQuestion(
                id = "q1",
                title = "删哪些？",
                kind = AskQuestionKind.SINGLE,
                options = listOf(
                    AskUserOption("keep", "都留着"),
                    AskUserOption("drop", "删掉旧的两份"),
                ),
                required = true,
            ),
            AskQuestion(id = "q2", title = "留几层？", kind = AskQuestionKind.NUMBER, default = "3"),
            AskQuestion(
                id = "q3",
                title = "要不要报告？",
                kind = AskQuestionKind.BOOL,
                required = true,
            ),
        ),
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
    fun threeQuestionsOneCard_validateInPlace_submitOnce() {
        val submitted = AtomicReference<Map<String, String>?>(null)
        compose.setContent {
            MaterialTheme {
                BaopuQuestionGroupCard(request = request, onSubmit = { submitted.set(it) })
            }
        }

        // 初始态：必答没填 ⇒ 提交置灰、红字点名（负例 3 的卡面证据）
        compose.onNodeWithText("提交").assertIsNotEnabled()
        compose.onNodeWithText("必答未填", substring = true).assertExists()
        compose.waitForIdle()
        saveShot("zd-203-group-card-initial.png")

        // 数字项填非数字 ⇒ 仍被挡（就地校验红字里会点名「留几层？要填数字」），换数字后才能继续
        compose.onNode(hasSetTextAction()).performTextInput("两三层")
        compose.waitForIdle()
        compose.onNodeWithText("提交").assertIsNotEnabled()
        compose.onNodeWithText("要填数字（现在是：两三层）", substring = true).assertExists()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("3")

        // 「按默认」在（q2 有默认值）；「都不改」不在（有必答项）
        compose.onNodeWithText("按默认").assertExists()
        compose.onAllNodesWithText("都不改").assertCountEquals(0)

        // 点选项与是否片
        compose.onNodeWithText("删掉旧的两份").performClick()
        compose.onNodeWithText("是").performClick()
        compose.waitForIdle()

        // 一次提交：3 个答案齐全、id 对应（正例判据）
        compose.onNodeWithText("提交").assertIsEnabled()
        saveShot("zd-203-group-card-filled.png")
        compose.onNodeWithText("提交").performClick()
        compose.waitForIdle()
        val got = submitted.get()
        assertNotNull("提交回调没触发", got)
        assertEquals(
            mapOf("q1" to "drop", "q2" to "3", "q3" to "true"),
            got,
        )
    }
}
