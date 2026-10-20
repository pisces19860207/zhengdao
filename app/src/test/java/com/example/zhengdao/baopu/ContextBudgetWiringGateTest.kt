// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第九十一批 §4 的**门禁**（负例在这条测试里落地）：
// - 默认路径必须是「不执行降级」；谁把它改成「直接截断」⇒ 这里红；
// - 注入预算只许不装，不许删除；谁在预算文件里加删除/改 RunEngine 注入 ⇒ 这里红；
// - 压缩低保留率只许记日志，不许 return / 不配拖垮对话；
// - 视觉零变化：除控制器外没有任何 UI 文件引用预算预览。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContextBudgetWiringGateTest {

    private val baopuMain = "src/main/java/com/example/zhengdao/baopu"
    private val uiMain = "src/main/java/com/example/zhengdao/ui/baopu"

    private fun code(path: String): String {
        val raw = File(path).readText()
        return raw.lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
    }

    private fun bodyOf(path: String, signature: String): String {
        val src = code(path)
        val i = src.indexOf(signature)
        assertTrue("找不到 $signature（$path）", i >= 0)
        val rest = src.substring(i + signature.length)
        val next = rest.indexOf("\n    fun ")
        return if (next >= 0) rest.substring(0, next) else rest
    }

    // ── 负例 1：默认直接截断 ⇒ 红 ───────────────────────────────────────

    @Test
    fun `默认路径_不执行任何降级动作`() {
        val running = DegradationPolicy.LADDER
        assertEquals(
            "阶梯顺序必须是 压缩→截断→拒绝",
            listOf(BudgetStep.COMPRESS, BudgetStep.TRUNCATE, BudgetStep.REJECT),
            running,
        )
        assertFalse("默认必须关", DegradationPolicy.ENGAGED_BY_DEFAULT)

        val budget = ModelBudget("t", "", 100_000, 20_000, 5_000, 10_000, "测试")
        val plan = DegradationPolicy.plan(TokenBudget.usage(90_000, budget))
        assertEquals(
            "溢出时先压缩、后截断；把 TRUNCATE 提到前面这条就红",
            listOf(BudgetStep.COMPRESS, BudgetStep.TRUNCATE),
            plan.steps,
        )
        assertTrue("默认执行口必须返回空表", DegradationPolicy.execute(plan).isEmpty())
    }

    @Test
    fun `生产源码没有执行降级或直接截断`() {
        val root = File("src/main/java/com/example/zhengdao")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "ContextBudget.kt" }
            .filter { f ->
                val s = f.readText()
                s.contains("DegradationPolicy.execute") || s.contains("BudgetStep.TRUNCATE")
            }
            .map { it.relativeTo(root).path }
            .toList()
        assertTrue(
            "本批只度量：除 ContextBudget.kt 外不许有降级执行/截断调用，实际命中 $offenders",
            offenders.isEmpty(),
        )
        val runEngine = File("$baopuMain/agent/RunEngine.kt").readText()
        assertFalse("RunEngine 不许被本批改动（87 批边界）", runEngine.contains("DegradationPolicy"))
    }

    // ── 负例 2：注入超预算直接删除 ⇒ 红 ─────────────────────────────────

    @Test
    fun `注入预算源码没有删除能力`() {
        val src = File("$baopuMain/MemoryInjectionBudget.kt").readText()
        for (banned in listOf("delete", "remove", "reject(", "writeText", "deleteRecursively")) {
            assertFalse("预算策略不许有删除/写盘能力：命中 $banned", src.contains(banned))
        }
        assertTrue("必须明说只不装、不删除", src.contains("不删除"))
    }

    @Test
    fun `注入超预算只进omitted_不丢条目`() {
        val now = 1_800_000_000_000L
        val all = (1..4).map { i ->
            MemoryEntry(
                id = "m$i", scope = MemoryScope.GLOBAL, kind = MemoryKind.FACT,
                title = "标题$i", body = "正文$i", sourceSession = "s",
                status = MemoryStatus.CONFIRMED, createdAt = now, updatedAt = now,
            )
        }
        val plan = MemoryInjectionBudget.plan(
            all, now,
            config = MemoryInjectionBudget.Config(maxItems = 1, maxChars = 100_000),
        )
        assertEquals("注入 + 未注入 = 原集合，一条不少", all.map { it.id }.toSet(), (plan.injected + plan.omitted).map { it.id }.toSet())
        assertTrue(plan.truncated)
    }

    // ── 压缩度量：只记录，不阻塞 ─────────────────────────────────────────

    @Test
    fun `压缩低保留率只告警_不return不Failed`() {
        val src = File("$baopuMain/agent/CompressionRunner.kt").readText()
        val start = src.indexOf("if (metrics.lowRetention) {")
        assertTrue("必须保留低保留率告警块", start >= 0)
        val end = src.indexOf("} else {", start)
        assertTrue("告警块结构变了，请重新核对", end > start)
        val block = src.substring(start, end)
        assertFalse("低保留率不许 return（那会跳过摘要替身）", Regex("\\breturn\\b").containsMatchIn(block))
        assertFalse("低保留率不许转 Failed（那会阻塞对话）", block.contains("Outcome.Failed"))
        assertTrue("必须写明只记录不拦对话", block.contains("不拦对话"))
    }

    @Test
    fun `压缩度量失败也不影响落库`() {
        val src = code("$baopuMain/agent/CompressionRunner.kt")
        assertTrue("度量要兜异常", src.contains("runCatching {"))
        val measureAt = src.indexOf("CompressionQuality.measure(")
        val insertAt = src.indexOf("store.insert(sessionId, record, sourceTokens, summaryTokens)")
        assertTrue("度量必须在落库前算好（但不改变落库参数）", measureAt in 0 until insertAt)
        assertTrue("落库参数保持原样", insertAt > 0)
    }

    // ── 控制器接线：主线程零新增 IO；视觉零变化 ─────────────────────────

    @Test
    fun `控制器_记忆一次读在IO段_注入预览只读`() {
        val body = bodyOf("$uiMain/BaopuChatController.kt", "fun refresh(force: Boolean = false) {")
        val ioAt = body.indexOf("withContext(Dispatchers.IO)")
        assertTrue("refresh 必须在 IO 段读库", ioAt >= 0)
        val loadAt = body.indexOf("val memories = store.loadAll()")
        assertTrue("记忆读取必须在 IO 段内", loadAt > ioAt)
        assertTrue("注入预览必须在 IO 段内算", body.indexOf("MemoryInjectionBudget.plan(") > ioAt)
        assertFalse(
            "状态写回不许丢进 IO（真机实测过时灵时不灵）",
            body.contains("withContext(Dispatchers.IO) {\n            memoryInjectionPreview ="),
        )
    }

    @Test
    fun `视觉零变化_除控制器外没有UI引用预算预览`() {
        val offenders = File(uiMain).listFiles()
            ?.filter { it.isFile && it.extension == "kt" && it.name != "BaopuChatController.kt" }
            ?.filter { f ->
                val s = f.readText()
                s.contains("memoryInjectionPreview") || s.contains("MemoryInjectionBudget")
            }
            ?.map { it.name }
            .orEmpty()
        assertTrue("UI 不许渲染/引用预算预览（§B 90 冻结），实际命中 $offenders", offenders.isEmpty())
    }

    // ── 阈值不许被本批改掉 ───────────────────────────────────────────────

    @Test
    fun `压缩阈值仍是70pct与900k`() {
        val kernel = File("$baopuMain/kernel/BaopuKernel.kt").readText()
        assertTrue(kernel.contains("const val COMPRESS_THRESHOLD = 0.70"))
        assertTrue(kernel.contains("const val COMPRESS_ABS_MAX_TOKENS = 900_000"))
    }
}
