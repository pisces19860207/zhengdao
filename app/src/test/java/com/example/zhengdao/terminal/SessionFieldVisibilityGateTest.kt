// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第六十批 · P2-11：[SessionManager.startedAtMs] 必须 `@Volatile`。
 *
 * ## 为什么这一个是真问题
 *
 * 写点全在会话线程/主线程（`start()` 登记、`killInternal()` 与 onSessionFinished 清零），
 * 但读它的是 **[SessionService] 自己的 30 秒巡检**（`elapsedText()`，服务线程）。
 * 没有 `@Volatile` 时，服务线程可能长期读到 0 —— 通知里「已运行 N 分钟」直接整段消失
 * （不是短一截，而是空串），且这种漏读只在真机上偶发。
 *
 * ## 判据
 *
 * 1. 声明必须紧跟 `@Volatile`，且只有一处声明；
 * 2. 同款字段（`session` / `usesTmux` / `isFallback` / `currentAgentId`）的注解不许被顺手删掉
 *    —— 它们是同一类跨线程读写的既有防线，删一个就是回退；
 * 3. **写点数量不变**：本单只允许加注解，不许改写入语义（三处赋值：登记、两处清零）。
 */
class SessionFieldVisibilityGateTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    private fun code(rel: String): String = raw(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val sessionSrc = "src/main/java/com/example/zhengdao/terminal/SessionManager.kt"

    @Test
    fun `P2-11_startedAtMs 必须带 Volatile 且只声明一处`() {
        val src = code(sessionSrc)
        assertEquals("startedAtMs 只应有一处声明", 1, Regex("var startedAtMs\\b").findAll(src).count())
        assertTrue(
            "startedAtMs 必须紧跟 @Volatile（30 秒巡检在服务线程读，没有注解会长期读到 0）",
            Regex("@Volatile\\s*\\n\\s*var startedAtMs\\b").containsMatchIn(src),
        )
    }

    @Test
    fun `P2-11_同款跨线程字段的注解不许被删`() {
        val src = code(sessionSrc)
        listOf("currentAgentId", "session", "usesTmux", "isFallback").forEach { field ->
            assertTrue(
                "$field 的 @Volatile 被删了（同类跨线程字段，删一个就是回退）",
                Regex("@Volatile\\s*\\n\\s*var $field\\b").containsMatchIn(src),
            )
        }
    }

    @Test
    fun `P2-11_写点不变_仍是三处登记与清零`() {
        val src = code(sessionSrc)
        assertEquals(
            "startedAtMs 的赋值点数量变了（本单只允许加 @Volatile，不许改写入语义）",
            3,
            Regex("startedAtMs = ").findAll(src).count(),
        )
    }
}
