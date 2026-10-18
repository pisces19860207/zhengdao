// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 朗读（系统 TTS）接线守卫 —— 2026-10-18 补上 `接线审计` §5 P4 里「账上写着、代码零命中」的那一条。
 *
 * 五组判据（源码文本级；先剥注释再断言）：
 * ① 用**系统** TTS（`android.speech.tts.TextToSpeech`），不引第三方库、不走网络；
 * ② 跟着页面生命走：离开抱朴页必须 `release()`（不许后台接着念）；
 * ③ 失败不静默：引擎不可用/缺语言包都要说出来（Toast ＋ RunLog）；
 * ④ 只读**助手**气泡正文（用户自己说的话不用读），且朗读按钮在 SelectionContainer 之外；
 * ⑤ 中英文都有文案。
 */
class BaopuSpeakWiringTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun speaker() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSpeaker.kt")

    private fun screen() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt")

    private fun list() = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuMessageList.kt")

    @Test
    fun `用系统TTS_不引库_不走网络`() {
        val src = speaker()
        assertTrue("必须用系统 TextToSpeech", src.contains("import android.speech.tts.TextToSpeech"))
        assertFalse("朗读不许走网络（不联网是这条能力的前提）", src.contains("http://") || src.contains("https://"))
        assertFalse("不许引第三方 TTS 库", src.contains("com.google.android.tts"))
    }

    @Test
    fun `离开页面必须释放引擎`() {
        val src = screen()
        assertTrue("必须 onDispose { speaker.release() }，否则后台接着念", src.contains("speaker.release()"))
        assertTrue("speaker 的生命跟着页面（remember 创建）", src.contains("remember { BaopuSpeaker(context) }"))
    }

    @Test
    fun `失败不许静默`() {
        val src = speaker()
        assertTrue("引擎不可用要弹 Toast", src.contains("Toast.makeText"))
        assertTrue("同时记 RunLog（可追溯）", src.contains("RunLog.log("))
        assertTrue("缺语言包要走同一套失败路径", src.contains("baopu_speak_no_language"))
        assertTrue("初始化失败要走同一套失败路径", src.contains("baopu_speak_failed"))
    }

    @Test
    fun `只读助手气泡_按钮在选区容器之外`() {
        val src = list()
        assertTrue("朗读只给助手气泡", src.contains("if (!isUser && onSpeak != null)"))
        assertTrue("按钮文案走 strings", src.contains("R.string.baopu_speak"))
        // 朗读按钮必须排在 SelectionContainer 之后：长按选字与朗读各管各的
        val sel = src.indexOf("SelectionContainer {")
        val btn = src.indexOf("R.string.baopu_speak")
        assertTrue("朗读按钮要写在 SelectionContainer 之后", sel in 0 until btn)
    }

    @Test
    fun `中英文都有朗读文案`() {
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        listOf("baopu_speak", "baopu_speak_failed", "baopu_speak_no_language").forEach { key ->
            assertTrue("中文缺 $key", zh.contains("name=\"$key\""))
            assertTrue("英文缺 $key", en.contains("name=\"$key\""))
        }
    }
}
