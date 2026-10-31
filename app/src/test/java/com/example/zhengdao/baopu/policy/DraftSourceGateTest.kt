// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十八批 · 判据⑤：**草稿层不许用模型**（源级门禁钉），外加判决路径"零锁零 IO"。
//
// 治理机制细则把「两级裁决」的第一级写死了：草稿层**只能规则/查表**，"律是尺子，模型是嘴"。
// 这条不是靠自觉——本文件把它扫成源码：
//   ① 草稿层三件（`VerdictDraft`/`VerdictExperienceSnapshot`/`VerdictExperienceRuntime`）里
//      出现任何模型/生成式符号即红（符号名全部取仓库里的真件，不是空词）；
//   ② 调解器里"查表那一段"（`verdictWithDraft` 函数体，剥掉注释后）同样零模型、
//      并且**零 IO、零锁**——判决路径不许为了查表去读盘或排队；
//   ③ 草稿层的 import 面是闭集：只认经验表/学习判据/契约三样，多一个就是新权力；
//   ④ 草稿只能是"不弹"：出现 `OpinionDecision.POP` 即红——学到的是"什么时候别问"，
//      不是"什么时候该问"（负例「撞法条仍按草稿放行」守的是同一条边界）。
//
// 负例②「草稿层偷偷调模型」就在这儿变红：往 `VerdictDraft.kt` 里塞一句 `LlmClient(...)`
// 或者给 `verdictWithDraft` 加一次读盘，本文件立刻点名是哪一格。
package com.example.zhengdao.baopu.policy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftSourceGateTest {

    private fun mainRoot(): File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    private fun read(rel: String): String {
        val f = File(mainRoot(), "java/com/example/zhengdao/baopu/$rel")
        assertTrue("找不到 $f", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    private fun code(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")

    /** 草稿层的三件（判决读的那一条链）。 */
    private val draftLayer = listOf(
        "policy/VerdictDraft.kt",
        "policy/VerdictExperienceSnapshot.kt",
        "policy/VerdictExperienceRuntime.kt",
    )

    /** 模型侧的**真符号**（逐个在仓库里存在）——草稿层一次都不许碰。 */
    private val modelTokens = listOf(
        "LlmClient", "ModelRouter", "ModelParams", "Provider", "AnthropicWire", "GeminiWire",
        "OpenAiWire", "Compressor", "ContextAssembler", "RunEngine", "ToolRunner",
        "SubAgent", "Llm", "prompt", "chat(", "completion",
    )

    // ── ① 草稿层零模型 ────────────────────────────────────────────────────

    @Test
    fun `草稿层三件零模型符号`() {
        draftLayer.forEach { rel ->
            val body = code(read(rel))
            modelTokens.forEach { token ->
                assertFalse("$rel 的草稿层里出现了模型符号 $token（律是尺子，模型是嘴）", body.contains(token))
            }
        }
    }

    // ── ② 判决查表那一段：零模型／零 IO／零锁 ─────────────────────────────

    @Test
    fun `判决路径查表那一段_不加锁不读盘不调模型`() {
        val text = read("agent/OpinionWiring.kt")
        val body = code(text).substringAfter("private fun verdictWithDraft(").substringBefore("\n    }")
        assertTrue("前提：调解器里那段确实在查表", body.contains("VerdictDraft.decide("))
        modelTokens.forEach { token -> assertFalse("查表那一段出现模型符号 $token", body.contains(token)) }
        listOf("synchronized", "Thread(", "sleep(", "readAll(", "File(", "readLines", "appendText", "Lock").forEach {
            assertFalse("判决路径不许有 $it（读表只读内存里那一版）", body.contains(it))
        }
    }

    @Test
    fun `读表口只有一条volatile读_没有锁也没有等待`() {
        val line = code(read("policy/VerdictExperienceRuntime.kt"))
            .lines()
            .first { it.trim().startsWith("fun current(") }
        assertTrue("读口必须就是「一次引用读」：$line", line.trim().endsWith("= snapshot"))
        listOf("synchronized", "runCatching", "readAll(", "build(", "Thread.sleep").forEach {
            assertFalse("读表口里不许有 $it：$line", line.contains(it))
        }
    }

    // ── ③ import 面闭集 ───────────────────────────────────────────────────

    @Test
    fun `草稿层的import面是闭集`() {
        val allowed = setOf(
            "com.example.zhengdao.baopu.agent.ExperienceKind",
            "com.example.zhengdao.baopu.agent.ExperienceRow",
            "com.example.zhengdao.baopu.agent.LearnPolicy",
            "com.example.zhengdao.baopu.agent.OpinionDecision",
            "com.example.zhengdao.baopu.inspect.LawRef",
            "com.example.zhengdao.baopu.inspect.Opinion",
            "com.example.zhengdao.baopu.inspect.Severity",
            "java.util.concurrent.atomic.AtomicInteger",
            "java.util.concurrent.atomic.AtomicLong",
        )
        draftLayer.forEach { rel ->
            val imports = Regex("""(?m)^import\s+([^\s]+)""").findAll(code(read(rel))).map { it.groupValues[1] }.toList()
            assertTrue("$rel 的 import 越界：${imports.filterNot { it in allowed }}", imports.all { it in allowed })
        }
    }

    // ── ④ 草稿只会说"不弹" ────────────────────────────────────────────────

    @Test
    fun `草稿层只可能得出不弹_不可能得出该弹`() {
        val body = code(read("policy/VerdictDraft.kt"))
        assertTrue("草稿的结论是 SKIP", body.contains("OpinionDecision.SKIP"))
        assertFalse("草稿不许说 POP（学习无权决定'什么时候该问'）", body.contains("OpinionDecision.POP"))
        assertFalse("草稿不许说 DEFER（延后队列由完整流程管）", body.contains("OpinionDecision.DEFER"))
        // 验证层只有一条判据：法典点名的必问集（283 那一处），不许自己另编
        assertTrue(body.contains("LearnPolicy.mustAsk("))
    }

    // ── ⑤ 单一消费口：草稿只被调解器查一次 ────────────────────────────────

    @Test
    fun `草稿层的生产消费口只有一处`() {
        val root = mainRoot()
        val callers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText(Charsets.UTF_8).contains("VerdictDraft.decide(") }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .toList()
        assertEquals(
            "两级裁决只许长在调解器那道口上（多一处＝多一条判决路径）",
            listOf("java/com/example/zhengdao/baopu/agent/OpinionWiring.kt"),
            callers,
        )
    }
}
