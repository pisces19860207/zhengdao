// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.consider

import com.example.zhengdao.baopu.MemoryKind
import com.example.zhengdao.baopu.MemoryRefine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 试点接线：`MemoryRefine.refine` ⇒ `ConsiderLog`（第八百一十一批）。
 *
 * 这是本单唯一的产生点接入（会话结束的记忆提炼：`BaopuChatController.refineMemories`
 * → `MemoryRefine.refine`）。四类钉子：
 *
 * - **判据①**：候选全被否决时，池里仍看得到全部候选（旧口径只有一个 dropped 总数）；
 * - **判据②**：四字段齐、原因码是枚举；
 * - **判据④**：只加留痕——选优结果（候选内容/顺序/dropped/自省）与接线前逐字一致；
 * - **判据⑤**：留痕口在生产代码里（不是只在测试里被调）。
 */
class ConsiderWiringTest {

    private fun facts(
        userTexts: List<String>,
        toolsUsed: List<String> = emptyList(),
        steps: Int = 2,
        endState: String = "Done",
        now: Long = 1_000L,
    ) = MemoryRefine.SessionFacts(
        sessionId = 7L,
        userTexts = userTexts,
        toolsUsed = toolsUsed,
        steps = steps,
        endState = endState,
        now = now,
    )

    private fun pool() = ConsiderLog.shared.query(generatorId = MemoryRefine.CONSIDER_GENERATOR)

    @Before
    fun clearBefore() = ConsiderLog.shared.reset()

    @After
    fun clearAfter() = ConsiderLog.shared.reset()

    // ── 判据①：全被否决也要在池里 ─────────────────────────────────────────

    @Test
    fun `判据一_候选全被否决时池里仍看得到全部候选`() {
        // 夹具：两条太短（BELOW_BAR）＋ 一条超长过不了校验（INVALID）＋ 同一条再来一次（DUPLICATE）。
        // 结果 kept 为空——旧口径下这件事只剩一个 dropped 数字，看不见是哪几条。
        val tooLong = "以后" + "啊".repeat(600)
        val r = MemoryRefine.refine(facts(listOf("以后。", "不对。", "$tooLong。", "$tooLong。")))

        assertTrue("夹具要求「候选全被否决」", r.candidates.isEmpty())
        val got = pool()
        assertEquals("被否决的候选必须一条不落地进池", 4, got.size)
        assertTrue("全否决时池里不该有 ACCEPTED", got.none { it.verdict == ConsiderVerdict.ACCEPTED })
        assertEquals(
            listOf(
                ConsiderVerdict.BELOW_BAR,
                ConsiderVerdict.BELOW_BAR,
                ConsiderVerdict.INVALID,
                ConsiderVerdict.DUPLICATE,
            ),
            got.map { it.verdict },
        )
        // 旧口径的落差：dropped 只说「丢了 1 条」，实际这一批 4 条候选都没进下游
        assertEquals(1, r.dropped)
        assertEquals(4, got.size)
        assertTrue("时刻取提炼时刻（同一把尺）", got.all { it.atMs == 1_000L })
    }

    @Test
    fun `判据一_入选与超限并存时超限的也在池里`() {
        val r = MemoryRefine.refine(
            facts(listOf("以后用中文回答。以后用英文回答。以后用日文回答。以后用法文回答。以后用德文回答。")),
        )
        assertEquals(MemoryRefine.MAX_CANDIDATES, r.candidates.size)
        val got = pool()
        assertEquals(5, got.size)
        assertEquals(3, got.count { it.verdict == ConsiderVerdict.ACCEPTED })
        assertEquals(2, got.count { it.verdict == ConsiderVerdict.OVER_LIMIT })
        // ACCEPTED 的摘要必须能在真候选里找到对应（不是错记）
        val keptDigests = r.candidates.map { ConsiderLog.digestOf(it.body) }.toSet()
        val acceptedDigests = got.filter { it.verdict == ConsiderVerdict.ACCEPTED }.map { it.inputDigest }.toSet()
        assertEquals(keptDigests, acceptedDigests)
    }

    @Test
    fun `判据二_四字段齐且摘要不超上限`() {
        val tooLong = "以后" + "啊".repeat(600)
        MemoryRefine.refine(facts(listOf("以后用中文。$tooLong。")))
        val got = pool()
        assertTrue(got.isNotEmpty())
        got.forEach { rec ->
            assertTrue("产生器 id 不能空", rec.generatorId.isNotBlank())
            assertTrue("输入摘要不能空", rec.inputDigest.isNotBlank())
            assertTrue(
                "摘要超上限：${rec.inputDigest.length}",
                rec.inputDigest.length <= ConsiderLog.DIGEST_LIMIT + 1,
            )
            assertTrue("原因码必须落在枚举名闭集里", rec.verdict.name in ConsiderVerdict.entries.map { it.name })
        }
    }

    // ── 判据④：只加留痕，选优一字未动 ──────────────────────────────────────

    @Test
    fun `判据四_选优指纹与接线前逐字一致`() {
        // 这三个期望值是接线前（ConsiderLog 未接）跑出来的同一组读数：接线只加旁路，不许动它。
        val r = MemoryRefine.refine(facts(listOf("以后请叫我老师。不对，别这样写。"), now = 1_000L))

        assertEquals(2, r.candidates.size)
        assertEquals(MemoryKind.PREFERENCE, r.candidates[0].kind)
        assertEquals("以后请叫我老师", r.candidates[0].body)
        assertEquals(MemoryKind.LESSON, r.candidates[1].kind)
        assertEquals("不对，别这样写", r.candidates[1].body)
        assertEquals(0, r.dropped)
        assertEquals("这次有 1 处做法被纠正，已记进候选等你过目。", r.introspection.text)
        assertEquals("introspection", r.introspection.kind)
        // 候选 id 也不许漂：它是 now + 句序 + slug 的确定性产物
        assertEquals("1000-以后请叫我老师", r.candidates[0].id)
    }

    @Test
    fun `判据四_同输入跑两次_候选与池都逐字节一致`() {
        val input = listOf("以后用中文。不对，别这样写。")
        val a = MemoryRefine.refine(facts(input))
        val poolA = pool()
        ConsiderLog.shared.reset()
        val b = MemoryRefine.refine(facts(input))
        val poolB = pool()

        assertEquals(a, b)
        assertEquals(poolA, poolB)
    }

    // ── 判据⑤：生产链路里的调用点（不是只在测试里调）───────────────────────

    @Test
    fun `判据五_留痕口在生产代码里且提炼入口仍被UI调用`() {
        val memoryRefine = source("src/main/java/com/example/zhengdao/baopu/MemoryRefine.kt")
        assertTrue("留痕写入口不在生产代码里", memoryRefine.contains("ConsiderLog.shared.recordAll("))
        assertTrue("复算入口不在生产代码里", memoryRefine.contains("fun accountAll("))
        assertTrue("提炼入口签名被改（本单不许改公开面）", memoryRefine.contains("fun refine(facts: SessionFacts): RefineResult"))

        val controller = source("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
        assertTrue("生产调用点丢了：UI 不再调 MemoryRefine.refine", controller.contains("MemoryRefine.refine(facts)"))

        // 反向闸：ConsiderLog 若只有「定义 + 测试」在碰，就是死件——生产侧必须至少有一处引用
        val refs = countProductionRefs("ConsiderLog")
        assertTrue("ConsiderLog 生产侧引用不足（$refs 处）", refs >= 2)
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private fun source(rel: String): String {
        val direct = File(rel)
        val fallback = File("../app/$rel")
        val file = if (direct.isFile) direct else fallback
        assertTrue("读不到生产源码：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** 数生产侧（src/main/java）提到该名字的文件数（含声明文件自身）。 */
    private fun countProductionRefs(name: String): Int {
        val direct = File("src/main/java")
        val root = if (direct.isDirectory) direct else File("../app/src/main/java")
        assertTrue("读不到生产源码目录：${root.absolutePath}", root.isDirectory)
        var n = 0
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            if (f.readText().contains(name)) n++
        }
        return n
    }
}
