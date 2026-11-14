// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 C · 域②**五流状态语义不可改**的源码断言＋行为断言（判据「状态语义有源码断言钉住」）。
//
// 549 的状态机一字一句不许动——本测试两面钉：
//   · 源码面：peer/A2aClient.kt 的 A2aTaskState 九态词表/终态集/要人集、peer/EnvoyTrack.kt 的
//     EnvoyLaw.legal 五条法条与 observe 落痕，逐字钉住（改一个词就红）；
//   · 行为面：直接调 549 的纯函数（本仓 JVM 可测），断言终态粘住/要人两态/五流合法链/非法迁移拒。
//   · 单一真相：RouteSemantics 只引用 549 闭集，不自带第二份状态词表（字面量零出现）。
//
// 负例（判据「改了状态含义 ⇒ 红」）：把真文件里的状态词/终态判据/法条改掉再跑同一把断言，必须红。
package com.example.zhengdao.baopu.evolution.highrisk

import com.example.zhengdao.GateBodies
import com.example.zhengdao.baopu.peer.A2aTaskState
import com.example.zhengdao.baopu.peer.EnvoyLaw
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighRiskRouteSemanticsGateTest {

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }.readText()
            .replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun a2aClient(): String =
        code("src/main/java/com/example/zhengdao/baopu/peer/A2aClient.kt")

    private fun envoyTrack(): String =
        code("src/main/java/com/example/zhengdao/baopu/peer/EnvoyTrack.kt")

    private fun routeSemantics(): String =
        code("src/main/java/com/example/zhengdao/baopu/evolution/highrisk/RouteSemantics.kt")

    // ── 源码面：九态词表逐字钉 ──────────────────────────────────────────────

    @Test
    fun `S1_九态词表与线值逐字钉住`() {
        val src = a2aClient()
        val expected = listOf(
            "SUBMITTED(\"submitted\")", "WORKING(\"working\")", "INPUT_REQUIRED(\"input-required\")",
            "AUTH_REQUIRED(\"auth-required\")", "COMPLETED(\"completed\")", "FAILED(\"failed\")",
            "CANCELED(\"canceled\")", "REJECTED(\"rejected\")", "UNKNOWN(\"unknown\")",
        )
        expected.forEach { assertTrue("状态词被改了：$it", src.contains(it)) }
        assertEquals("状态闭集仍是九态（改集合＝改状态语义）", 9, A2aTaskState.entries.size)
    }

    @Test
    fun `S1负例_改一个状态线值_断言必须红`() {
        val src = a2aClient()
        val mutated = src.replace("COMPLETED(\"completed\")", "COMPLETED(\"done\")")
        assertFalse("把 completed 改成 done 后必须红（状态语义不可改）", mutated.contains("COMPLETED(\"completed\")"))
    }

    @Test
    fun `S2_终态集与要人集逐字钉住`() {
        val src = a2aClient()
        assertTrue("终态判据被改了", src.contains("this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED"))
        assertTrue("要人判据被改了", src.contains("this == INPUT_REQUIRED || this == AUTH_REQUIRED"))
    }

    @Test
    fun `S2负例_把终态集缩水_断言必须红`() {
        val src = a2aClient()
        val mutated = src.replace(
            "this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED",
            "this == COMPLETED || this == FAILED",
        )
        assertFalse("终态集被缩水后必须红（canceled/rejected 不再粘住＝翻案缝）", mutated.contains("this == CANCELED || this == REJECTED"))
    }

    // ── 源码面：迁移法条逐字钉 ──────────────────────────────────────────────

    @Test
    fun `S3_迁移法条五条逐字钉住`() {
        val body = GateBodies.functionBody(envoyTrack(), "fun legal(")
        assertTrue("EnvoyLaw.legal 不在了？", body != null)
        val b = body!!
        assertTrue("第一条（null＝第一步）被改", b.contains("from == null -> true"))
        assertTrue("终态粘住被改", b.contains("from.terminal -> from == to"))
        assertTrue("UNKNOWN 兜底被改", b.contains("from == A2aTaskState.UNKNOWN -> true"))
        assertTrue("退回 submitted 限制被改", b.contains("to == A2aTaskState.SUBMITTED -> from == A2aTaskState.SUBMITTED"))
    }

    @Test
    fun `S3负例_把终态粘住改成放行_断言必须红`() {
        val src = envoyTrack()
        val mutated = src.replace("from.terminal -> from == to", "from.terminal -> true")
        val body = GateBodies.functionBody(mutated, "fun legal(")
        assertTrue(body != null)
        assertFalse("终态翻案被放行后必须红（状态语义不可改）", body!!.contains("from.terminal -> from == to"))
    }

    @Test
    fun `S4_observe落痕并返回法条结论`() {
        val body = GateBodies.functionBody(envoyTrack(), "fun observe(")
        assertTrue("EnvoyTrack.observe 不在了？", body != null)
        assertTrue("observe 不落痕（账是事实记录）", body!!.contains("steps += EnvoyStep("))
        assertTrue("observe 不返回法条结论（非法迁移认不出）", body.contains("return EnvoyLaw.legal(from, to)"))
    }

    // ── 行为面：直接调 549 纯函数断言语义 ───────────────────────────────────

    @Test
    fun `S5_终态四格_要人两态_行为面钉住`() {
        assertEquals(
            setOf("completed", "failed", "canceled", "rejected"),
            A2aTaskState.entries.filter { it.terminal }.map { it.wire }.toSet(),
        )
        assertEquals(
            setOf("input-required", "auth-required"),
            A2aTaskState.entries.filter { it.needsHuman }.map { it.wire }.toSet(),
        )
    }

    @Test
    fun `S6_五流合法链与非法迁移_行为面钉住`() {
        // 五流合法链（549 判据①的链）。
        assertTrue("∅→submitted", EnvoyLaw.legal(null, A2aTaskState.SUBMITTED))
        assertTrue("submitted→working", EnvoyLaw.legal(A2aTaskState.SUBMITTED, A2aTaskState.WORKING))
        assertTrue("working→input-required", EnvoyLaw.legal(A2aTaskState.WORKING, A2aTaskState.INPUT_REQUIRED))
        assertTrue("input-required→working（回话续任务）", EnvoyLaw.legal(A2aTaskState.INPUT_REQUIRED, A2aTaskState.WORKING))
        assertTrue("working→completed", EnvoyLaw.legal(A2aTaskState.WORKING, A2aTaskState.COMPLETED))
        assertTrue("canceled→canceled（同态幂等）", EnvoyLaw.legal(A2aTaskState.CANCELED, A2aTaskState.CANCELED))
        // 非法：终态翻案、working 退回收条。
        assertFalse("completed→working＝终态翻案，非法", EnvoyLaw.legal(A2aTaskState.COMPLETED, A2aTaskState.WORKING))
        assertFalse("working→submitted＝乱跳，非法", EnvoyLaw.legal(A2aTaskState.WORKING, A2aTaskState.SUBMITTED))
    }

    // ── 单一真相：RouteSemantics 只引用 549，不自带第二份状态词表 ──────────────

    @Test
    fun `S7_路由守卫只引用549闭集_无第二份状态字面量`() {
        val src = routeSemantics()
        assertTrue("状态词表要引用 549 的 A2aTaskState", src.contains("A2aTaskState.entries"))
        assertTrue("迁移法条要委托 549 的 EnvoyLaw.legal", src.contains("EnvoyLaw.legal(from, to)"))
        // 路由守卫里不许出现状态线值字面量（第二份真相＝漂移入口）。
        listOf("submitted", "working", "input-required", "auth-required", "completed", "failed", "canceled", "rejected").forEach {
            assertFalse("路由守卫自带状态字面量「$it」＝第二份真相", src.contains("\"$it\""))
        }
    }

    @Test
    fun `S8_冲突时以状态机为准_路由守卫委托不另判`() {
        // 路由假设了一条终态翻案 ⇒ 状态机赢（flowLegal 就是 EnvoyLaw.legal，非法即 false）。
        assertFalse(RouteSemantics.flowLegal(A2aTaskState.COMPLETED, A2aTaskState.WORKING))
        assertTrue(RouteSemantics.flowLegal(A2aTaskState.WORKING, A2aTaskState.COMPLETED))
        assertEquals("路由守卫的终态集＝549 的终态集（同一份真相）", RouteSemantics.TERMINAL_WORDS, A2aTaskState.entries.filter { it.terminal }.map { it.wire }.toSet())
        assertEquals("路由守卫的要人集＝549 的要人集", RouteSemantics.NEEDS_HUMAN_WORDS, A2aTaskState.entries.filter { it.needsHuman }.map { it.wire }.toSet())
    }
}