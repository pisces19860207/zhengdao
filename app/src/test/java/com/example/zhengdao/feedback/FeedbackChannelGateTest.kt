// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第四百五十二批判据④与「只加一块 UI」的源码门禁。
 *
 * 两条判别器都带负例自证：先喂一段故意违规的源码确认判别器**咬得住**，
 * 再对真实源码断言干净，避免"文件里没有某个字面量"那种恒真绿灯。
 */
class FeedbackChannelGateTest {

    private fun projectFile(rel: String): File {
        val cwd = File(".").canonicalFile
        return listOf(
            File(cwd, rel),
            File(cwd.parentFile, rel),
            File(cwd, "app/$rel"),
        ).firstOrNull { it.isFile } ?: error("找不到 $rel（cwd=${cwd.absolutePath}）")
    }

    private fun text(rel: String): String = projectFile(rel).readText()

    /** 只删块注释与整行注释；字符串里的 https:// 必须保留，不能拿 substringBefore 砍。 */
    private fun stripComments(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    private val feedbackSources: Map<String, String> by lazy {
        listOf(
            "src/main/java/com/example/zhengdao/feedback/FeedbackDiagnostics.kt",
            "src/main/java/com/example/zhengdao/feedback/FeedbackLinks.kt",
            "src/main/java/com/example/zhengdao/feedback/FeedbackSnapshot.kt",
            "src/main/java/com/example/zhengdao/ui/FeedbackSection.kt",
        ).associateWith { stripComments(text(it)) }
    }

    private val networkCalls = listOf(
        "HttpURLConnection", "openConnection(", "java.net.URL", "URLConnection",
        "okhttp", "Retrofit", "HttpPost", "socket(", "Socket(",
    )

    private fun networkHits(src: String): List<String> = networkCalls.filter { src.contains(it) }

    private val autoHooks = listOf(
        "LaunchedEffect", "DisposableEffect", "WorkManager", "enqueue(",
        "AlarmManager", "CoroutineScope(", "Thread {", "viewModelScope",
    )

    private fun autoHits(src: String): List<String> = autoHooks.filter { src.contains(it) }

    @Test
    fun `不联网_反馈四个源码零网络调用`() {
        feedbackSources.forEach { (name, src) ->
            assertTrue("$name 出现网络调用：${networkHits(src)}", networkHits(src).isEmpty())
        }
    }

    @Test
    fun `负例_网络调用判别器咬得住`() {
        val bad = """val c = java.net.URL("https://x").openConnection() as HttpURLConnection"""
        assertTrue(networkHits(bad).isNotEmpty())
        assertTrue("干净源码不该被误判", networkHits("val url = \"https://github.com/x\"").isEmpty())
    }

    @Test
    fun `不自动上传_反馈块零自动执行钩子`() {
        feedbackSources.forEach { (name, src) ->
            assertTrue("$name 出现自动执行钩子：${autoHits(src)}", autoHits(src).isEmpty())
        }
    }

    @Test
    fun `负例_自动钩子判别器咬得住`() {
        val bad = "LaunchedEffect(Unit) { WorkManager.getInstance(ctx).enqueue(request) }"
        assertTrue(autoHits(bad).isNotEmpty())
        assertTrue("干净源码不该被误判", autoHits("onClick = { ctx.startActivity(intent) }").isEmpty())
    }

    @Test
    fun `发送只在点击回调里_三个动作各一次`() {
        val section = feedbackSources.getValue(
            "src/main/java/com/example/zhengdao/ui/FeedbackSection.kt",
        )
        assertEquals(1, Regex("copyPlainText\\(").findAll(section).count())
        assertEquals(1, Regex("ACTION_VIEW").findAll(section).count())
        assertEquals(1, Regex("ACTION_SENDTO").findAll(section).count())
    }

    @Test
    fun `设置页只挂一行_原有分组数量不变`() {
        val settings = stripComments(text("src/main/java/com/example/zhengdao/ui/SettingsScreen.kt"))
        assertEquals(1, Regex("FeedbackSection\\(\\)").findAll(settings).count())
        assertEquals(5, Regex("SectionCard\\(stringResource").findAll(settings).count())
        assertFalse("反馈文案不该散进设置页", settings.contains("settings_feedback_"))
    }

    @Test
    fun `中英反馈文案键与占位符成对`() {
        val zh = text("src/main/res/values/strings_feedback.xml")
        val en = text("src/main/res/values-en/strings_feedback.xml")
        assertEquals(stringKeys(zh), stringKeys(en))
        assertEquals(13, stringKeys(zh).size)
        assertTrue(stringKeys(zh).all { it.startsWith("settings_feedback_") })
        assertFalse("中文文案不该有占位符", zh.contains("%1\$"))
    }

    private fun stringKeys(xml: String): Set<String> =
        Regex("<string name=\"([A-Za-z0-9_]+)\"").findAll(xml)
            .map { it.groupValues[1] }.toSet()

    @Test
    fun `GitHub 两个表单字段齐全且写明不要贴密钥`() {
        val bug = text(".github/ISSUE_TEMPLATE/bug.yml")
        val idea = text(".github/ISSUE_TEMPLATE/idea.yml")
        listOf("device", "system", "version", "steps", "expected", "actual", "logs", "screenshots")
            .forEach { id -> assertTrue("bug.yml 缺字段 $id", bug.contains("id: $id")) }
        listOf("want", "workaround", "why")
            .forEach { id -> assertTrue("idea.yml 缺字段 $id", idea.contains("id: $id")) }
        assertTrue(bug.contains("不要贴密钥"))
        assertTrue(idea.contains("请勿贴入密钥"))
        assertTrue(bug.contains("body:"))
        assertTrue(idea.contains("body:"))
    }
}
