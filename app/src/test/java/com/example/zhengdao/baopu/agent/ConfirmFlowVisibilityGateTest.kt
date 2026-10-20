// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第六十批 · P2-10：[ConfirmFlow] 的四个跨线程字段必须 `@Volatile`。
 *
 * ## 谁写谁读
 *
 * | 字段 | 写 | 读 |
 * |---|---|---|
 * | `pending` | 引擎线程（enter 置、结束清）、UI（liveCallId/陈旧点击判别） | 两边 |
 * | `deferred` | 引擎线程置/清、UI `resumeWithApproval`/`abandon` 完成它 | 两边 |
 * | `note` | UI 写（用户填的拒绝理由） | 引擎线程（Rejected 文案） |
 * | `abandoned` | UI 写（点停止） | 引擎线程（区分「拒绝」与「作废」） |
 *
 * 缺 `@Volatile` 时的故障形态不是崩溃，而是「点了批准，引擎还在等」/「点停止被当成拒绝」——
 * 两个线程各看各的缓存，且在 x86 模拟器上几乎不复现（只有真机 ARM 的弱内存序稳定出）。
 * 单测触发不了内存序，所以判据只能是源码：**声明必须紧跟在注解后面**，而且要恰好一处声明
 * （防止「加了一个 @Volatile 的新字段，老的没删」这种半吊子改法）。
 *
 * 状态机语义没动 —— 行为级回归由既有 `ConfirmFlowTest` 兜（本类只钉可见性）。
 */
class ConfirmFlowVisibilityGateTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText().replace("\r\n", "\n")

    private fun code(rel: String): String = raw(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val flowSrc = "src/main/java/com/example/zhengdao/baopu/agent/ConfirmFlow.kt"

    @Test
    fun `P2-10_四个跨线程字段都必须带 Volatile`() {
        val src = code(flowSrc)
        listOf("pending", "deferred", "note", "abandoned").forEach { field ->
            val decls = Regex("private var $field\\b").findAll(src).count()
            val volatileDecls = Regex("@Volatile\\s*\\n\\s*private var $field\\b").findAll(src).count()
            assertEquals("$field 只应有一处声明（多了就是有人复制粘贴改了一半）", 1, decls)
            assertEquals(
                "$field 的声明必须紧跟 @Volatile（引擎线程写、UI 主线程读）",
                1,
                volatileDecls,
            )
        }
    }

    @Test
    fun `P2-10_不许用局部变量或方法绕开字段可见性`() {
        // 防「看起来加了注解，实际读的是一个每帧新建的类型」这类假修法：
        // 两个线程之间传递的只能是上面四个字段（enter/resume/abandon 的读写点都落在它们上）。
        val src = code(flowSrc)
        listOf(
            "fun liveCallId(): String? = pending?.call?.id",
            "deferred?.complete(approved)",
            "deferred?.complete(false)",
        ).forEach { needle ->
            assertTrue("接线点变了：找不到 `$needle`（改了就要重新确认跨线程路径）", src.contains(needle))
        }
    }
}
