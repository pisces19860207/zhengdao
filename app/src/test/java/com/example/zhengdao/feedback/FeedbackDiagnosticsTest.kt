// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第四百五十二批判据①③：诊断块白名单负例、截断与外链预填。
 *
 * 隐私那条**不是恒真断言**：先证明每个假串确实被塞进了上下文（否则"不出现"毫无意义），
 * 再逐字段钉它在诊断块里一个字都不出现。
 */
class FeedbackDiagnosticsTest {

    /** 造一条"含假密钥与假聊天正文"的上下文，字段取真实生产里会出现的那些名字。 */
    private val fakeSecrets: Map<String, String> = linkedMapOf(
        "api_key" to "sk-FAKEKEY-452-NEVER-LEAK-0001",
        "token" to "FAKE_TOKEN_452_DO_NOT_SHOW",
        "chat_body" to "这是第四百五十二批假聊天正文不许出现",
        "file_content" to "假文件内容452ABCD不许出现",
        "authorization" to "Authorization: Bearer FAKE_BEARER_452_TOKEN",
        "raw_error" to "run_log.err 原文 452 含模型输出",
        "file_path" to "/sdcard/私有文件452原文.txt",
        "message_payload" to "假消息载荷452不许出现",
    )

    private fun poisonedContext(): Map<String, Any?> {
        val context = LinkedHashMap<String, Any?>()
        context[FeedbackDiagnostics.KEY_VERSION] = "2.2.2"
        context[FeedbackDiagnostics.KEY_MODEL] = "Magic5 Pro"
        context[FeedbackDiagnostics.KEY_SYSTEM] = "Android 16 (API 36)"
        context[FeedbackDiagnostics.KEY_LEDGER] = listOf(
            LedgerDigest(1_000L, "SKILL_PROMOTE", "SKILL", true, false),
        )
        context[FeedbackDiagnostics.KEY_FAILURE] = listOf(FailureDigest(2_000L, "NETWORK"))
        fakeSecrets.forEach { (key, value) -> context[key] = value }
        return context
    }

    @Test
    fun `隐私负例_含假密钥与假正文的上下文_诊断块逐字段一个字都不出现`() {
        val context = poisonedContext()
        val rawDump = context.values.joinToString("\n") { it.toString() }
        val diagnostic = FeedbackDiagnostics.build(FeedbackDiagnostics.project(context))
        fakeSecrets.forEach { (key, secret) ->
            assertTrue("负例自证：$key 的假串必须先真实存在于上下文里", rawDump.contains(secret))
            assertFalse("字段 $key 泄漏进了诊断块", diagnostic.contains(secret))
        }
    }

    @Test
    fun `白名单正例_版本机型系统账摘要失败分类确实在块里`() {
        val diagnostic = FeedbackDiagnostics.build(FeedbackDiagnostics.project(poisonedContext()))
        assertTrue(diagnostic.contains("2.2.2"))
        assertTrue(diagnostic.contains("Magic5 Pro"))
        assertTrue(diagnostic.contains("Android 16 (API 36)"))
        assertTrue(diagnostic.contains("SKILL_PROMOTE"))
        assertTrue(diagnostic.contains("NETWORK"))
    }

    @Test
    fun `白名单是五个键_敏感字段名一个都不在`() {
        assertEquals(
            setOf("version", "model", "system", "ledger", "failure"),
            FeedbackDiagnostics.ALLOWED_KEYS,
        )
        fakeSecrets.keys.forEach { key ->
            assertFalse("$key 不该进白名单", FeedbackDiagnostics.ALLOWED_KEYS.contains(key))
        }
    }

    @Test
    fun `截断_超长文本不超过2000字且注明已截断`() {
        val long = "账摘要行".repeat(800)
        val capped = FeedbackDiagnostics.cap(long)
        assertTrue("原文必须真的超上限", long.length > FeedbackDiagnostics.MAX_CHARS)
        assertTrue(capped.length <= FeedbackDiagnostics.MAX_CHARS)
        assertEquals(FeedbackDiagnostics.MAX_CHARS, capped.length)
        assertTrue(capped.contains("已截断"))
    }

    @Test
    fun `截断_短文本原样返回且不注记`() {
        val short = "版本: 2.2.2"
        assertEquals(short, FeedbackDiagnostics.cap(short))
        assertFalse(FeedbackDiagnostics.cap(short).contains("已截断"))
    }

    @Test
    fun `构建出口_普通诊断块也走cap且头部字段保得住`() {
        val diagnostic = FeedbackDiagnostics.build(FeedbackDiagnostics.project(poisonedContext()))
        assertTrue(diagnostic.startsWith("证道诊断块"))
        assertTrue(diagnostic.contains("版本: 2.2.2"))
        assertTrue(diagnostic.length <= FeedbackDiagnostics.MAX_CHARS)
    }

    @Test
    fun `外链_两个入口预填的是同一块诊断`() {
        val block = FeedbackDiagnostics.build(FeedbackDiagnostics.project(poisonedContext()))
        val issue = FeedbackLinks.issueUrl("报问题", block)
        val mail = FeedbackLinks.mailtoUrl("证道反馈", block)
        assertTrue(issue.startsWith(FeedbackLinks.ISSUES_NEW))
        assertTrue(issue.contains("template=${FeedbackLinks.BUG_TEMPLATE}"))
        assertTrue(issue.contains("title=${FeedbackLinks.encode("报问题")}"))
        assertTrue(mail.startsWith("mailto:${FeedbackLinks.CONTACT}"))
        assertTrue(issue.contains(FeedbackLinks.encode(block)))
        assertTrue(mail.contains(FeedbackLinks.encode(block)))
        fakeSecrets.values.forEach { secret ->
            assertFalse("GitHub 预填里出现 $secret", issue.contains(secret))
            assertFalse("邮件预填里出现 $secret", mail.contains(secret))
        }
    }

    @Test
    fun `百分号编码_中文空格与保留字符`() {
        assertEquals("a%20b", FeedbackLinks.encode("a b"))
        assertEquals("%E4%B8%AD", FeedbackLinks.encode("中"))
        assertEquals("a-b_c.d~e", FeedbackLinks.encode("a-b_c.d~e"))
        assertNotEquals("编码不能是恒等映射", "a b", FeedbackLinks.encode("a b"))
    }
}
