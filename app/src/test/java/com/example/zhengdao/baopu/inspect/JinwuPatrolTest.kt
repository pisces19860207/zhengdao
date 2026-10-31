// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十三批 · 金吾卫采样的行为测试（判据①：四触发各一测＋无触发不采样；
// 判据⑤的源级面在 JinwuSamplingGateTest）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.verify.GapKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JinwuPatrolTest {

    @Before
    fun setUp() {
        JinwuPatrol.resetForTest()
    }

    // ── T1：参数与 schema 不符 ────────────────────────────────────────────────

    @Test
    fun `判据①T1_schema不符即采样`() {
        val gaps = JinwuPatrol.observe(step(resultCode = StepResultCode.SCHEMA_INVALID))
        assertEquals(1, gaps.size)
        assertEquals(GapKind.SCHEMA_VIOLATION, gaps[0].kind)
        assertEquals("action:call-1", gaps[0].target)
        assertTrue(gaps[0].evidence.any { it.contains("code=schema_invalid") })
    }

    @Test
    fun `T1_正常结果码不触发`() {
        assertTrue(JinwuPatrol.observe(step(resultCode = StepResultCode.DONE)).isEmpty())
        JinwuPatrol.resetForTest()
        assertTrue(JinwuPatrol.observe(step(resultCode = StepResultCode.NEEDS_CONFIRM)).isEmpty())
    }

    // ── T2：同一动作重复失败 ≥2 ──────────────────────────────────────────────

    @Test
    fun `判据①T2_同一动作重复失败两次即采样`() {
        assertTrue(JinwuPatrol.observe(step(callId = "call-1", resultCode = StepResultCode.FAILED)).isEmpty())
        val gaps = JinwuPatrol.observe(step(callId = "call-2", resultCode = StepResultCode.FAILED))
        assertEquals(1, gaps.size)
        assertEquals(GapKind.TOOL_MISUSE, gaps[0].kind)
        assertEquals("同一动作合并进同一 target（attempts 才累得起来）", "action:write_file:$DIGEST", gaps[0].target)
        assertTrue(gaps[0].evidence.any { it.contains("重复失败第 2 次") })
    }

    @Test
    fun `T2_不同参数不算同一动作_同动作第二次失败再触发`() {
        assertTrue("该动作第 1 次失败不采样", JinwuPatrol.observe(step(callId = "call-1", resultCode = StepResultCode.FAILED)).isEmpty())
        assertTrue("换动作不触发", JinwuPatrol.observe(step(callId = "call-2", argsDigest = "其他参数摘要", resultCode = StepResultCode.FAILED)).isEmpty())
        val gaps = JinwuPatrol.observe(step(callId = "call-3", resultCode = StepResultCode.FAILED))
        assertEquals("同一动作第 2 次失败触发", 1, gaps.size)
        assertTrue(gaps[0].evidence.any { it.contains("重复失败第 2 次") })
    }

    // ── T3：计划项跳号 ───────────────────────────────────────────────────────

    @Test
    fun `判据①T3_计划项跳号即采样_中间项逐个出`() {
        assertTrue(JinwuPatrol.observe(step(callId = "call-1", planStep = 1)).isEmpty())
        val gaps = JinwuPatrol.observe(step(callId = "call-2", planStep = 4))
        assertEquals("跳过的 2、3 各一条", 2, gaps.size)
        assertEquals(listOf("plan:2", "plan:3"), gaps.map { it.target })
        gaps.forEach { assertEquals(GapKind.GOAL_DRIFT, it.kind) }
        assertTrue(gaps[0].evidence.any { it.contains("plan:1→plan:4") })
    }

    @Test
    fun `T3_顺序推进不触发_首个计划项不做基准比较`() {
        assertTrue(JinwuPatrol.observe(step(callId = "call-1", planStep = 2)).isEmpty())
        assertTrue(JinwuPatrol.observe(step(callId = "call-2", planStep = 3)).isEmpty())
        assertTrue(JinwuPatrol.observe(step(callId = "call-3", planStep = 2)).isEmpty())
    }

    // ── T4：工具与任务类型明显不匹配 ──────────────────────────────────────────

    @Test
    fun `判据①T4_只读任务里动写工具即采样`() {
        val gaps = JinwuPatrol.observe(step(writeTool = true, taskReadOnly = true))
        assertEquals(1, gaps.size)
        assertEquals(GapKind.TOOL_MISUSE, gaps[0].kind)
        assertTrue(gaps[0].evidence.any { it.contains("只读任务里动了写工具") })
    }

    @Test
    fun `T4_可写任务动写工具_只读任务动读工具_都不触发`() {
        assertTrue(JinwuPatrol.observe(step(writeTool = true, taskReadOnly = false)).isEmpty())
        assertTrue(JinwuPatrol.observe(step(writeTool = false, taskReadOnly = true)).isEmpty())
    }

    // ── 负例①的正面：无触发不采样（全量扫描即红） ────────────────────────────

    @Test
    fun `无触发不采样_十步干净一步不采`() {
        repeat(10) { n ->
            assertTrue(
                "第 ${n + 1} 步不该采样",
                JinwuPatrol.observe(step(callId = "call-$n", resultCode = StepResultCode.DONE, writeTool = false)).isEmpty(),
            )
        }
    }

    @Test
    fun `采样窗口只带最近N步_不带全量`() {
        repeat(12) { n -> JinwuPatrol.observe(step(callId = "call-$n")) }
        val gaps = JinwuPatrol.observe(step(callId = "call-x", resultCode = StepResultCode.SCHEMA_INVALID))
        assertEquals(1, gaps.size)
        val sampleLines = gaps[0].evidence.filter { it.startsWith("step") }
        assertEquals("窗口就是 ${JinwuPatrol.SAMPLE_WINDOW} 步", JinwuPatrol.SAMPLE_WINDOW, sampleLines.size)
    }

    // ── 参数摘要（唯一口径） ──────────────────────────────────────────────────

    @Test
    fun `argsDigestOf_同一参数同一摘要_键序无关`() {
        val a = JSONObject("""{"path":"资料/x.md","mode":"append"}""")
        val b = JSONObject("""{"mode":"append","path":"资料/x.md"}""")
        assertEquals(JinwuPatrol.argsDigestOf(a), JinwuPatrol.argsDigestOf(b))
        assertTrue("摘要带键名清单", JinwuPatrol.argsDigestOf(a).startsWith("mode+path:"))
    }

    @Test
    fun `argsDigestOf_不同参数不同摘要`() {
        val a = JSONObject("""{"path":"资料/x.md"}""")
        val b = JSONObject("""{"path":"资料/y.md"}""")
        assertTrue(JinwuPatrol.argsDigestOf(a) != JinwuPatrol.argsDigestOf(b))
    }

    // ── 内件 ─────────────────────────────────────────────────────────────────

    private val DIGEST = "a1b2c3d4e5f60718"

    /** 默认干净步（DONE）：只测单个触发器时互不干扰；测失败的显式传 FAILED。 */
    private fun step(
        callId: String = "call-1",
        resultCode: StepResultCode = StepResultCode.DONE,
        planStep: Int? = null,
        writeTool: Boolean = true,
        taskReadOnly: Boolean = false,
        argsDigest: String = DIGEST,
    ): ToolStep = ToolStep(
        atMs = 1_000L,
        callId = callId,
        tool = "write_file",
        argsDigest = argsDigest,
        resultCode = resultCode,
        planStep = planStep,
        writeTool = writeTool,
        taskReadOnly = taskReadOnly,
    )
}
