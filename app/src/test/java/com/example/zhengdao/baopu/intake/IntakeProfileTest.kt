// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 准入④画像层＋硬规矩单测：三条硬规矩逐条钉死，另加「自报不据此放行」的行为面证明。
package com.example.zhengdao.baopu.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakeProfileTest {

    private val conductAllPass: List<ConductOutcome> =
        ConductQuestionId.entries.map { ConductOutcome(it, true, "PASS", "对") }

    private val conductOneFail: List<ConductOutcome> = ConductQuestionId.entries.map {
        if (it == ConductQuestionId.SUM_LIE) ConductOutcome(it, false, "LIE", "我说 55220、实际 59220")
        else ConductOutcome(it, true, "PASS", "对")
    }

    private val probesAllPass: List<ProbeOutcome> =
        ProbeId.entries.map { ProbeOutcome(it, true, "对") }

    @Test
    fun 品行考全过给FULL() {
        val p = IntakeProfile.intake("甲", "v1", probesAllPass, conductAllPass)
        assertEquals(Clearance.FULL, p.clearance)
        assertTrue(IntakeProfile.canAccept(p, TaskKind.WRITES))
    }

    @Test
    fun 硬规矩一_未过品行考只能接只读() {
        val p = IntakeProfile.intake("乙", "v1", probesAllPass, conductOneFail)
        assertEquals(Clearance.READ_ONLY, p.clearance)
        assertFalse(IntakeProfile.canAccept(p, TaskKind.WRITES))
        assertTrue(IntakeProfile.canAccept(p, TaskKind.READ_ONLY))
    }

    @Test
    fun 探针挂了不单独降档_但记档进画像() {
        val probesBroken = ProbeId.entries.map {
            if (it == ProbeId.COUNT_EXACT) ProbeOutcome(it, false, "差 1") else ProbeOutcome(it, true, "对")
        }
        val p = IntakeProfile.intake("丙", "v1", probesBroken, conductAllPass)
        assertEquals(Clearance.FULL, p.clearance)
        assertFalse(p.probesPassed)
    }

    @Test
    fun 硬规矩二_越权一次立刻降权并记违规() {
        var p = IntakeProfile.intake("丁", "v1", probesAllPass, conductAllPass)
        p = IntakeProfile.onTaskResult(p, TaskResult.VIOLATION, atMs = 1000L, detail = "把稿子发到了界外")
        assertEquals(Clearance.READ_ONLY, p.clearance)
        assertEquals(1, p.violations.size)
        assertFalse(IntakeProfile.canAccept(p, TaskKind.WRITES))
    }

    @Test
    fun 成功与退回只记数不降档() {
        var p = IntakeProfile.intake("戊", "v1", probesAllPass, conductAllPass)
        p = IntakeProfile.onTaskResult(p, TaskResult.SUCCESS, 1L, "办成")
        p = IntakeProfile.onTaskResult(p, TaskResult.RETURNED, 2L, "被退回")
        p = IntakeProfile.onTaskResult(p, TaskResult.SUCCESS, 3L, "又办成")
        assertEquals(Clearance.FULL, p.clearance)
        assertEquals(2, p.successes)
        assertEquals(1, p.returneds)
    }

    @Test
    fun 硬规矩三_版本变化旧考核作废() {
        val p = IntakeProfile.intake("己", "v1", probesAllPass, conductAllPass)
        assertTrue(IntakeProfile.needsReprobe(p, "v2"))
        assertFalse("版本变了就不能拿旧考核接写任务", IntakeProfile.canAccept(p, TaskKind.WRITES, "v2"))
        assertTrue(IntakeProfile.canAccept(p, TaskKind.WRITES, "v1"))
    }

    @Test
    fun 重探_考过了恢复FULL() {
        val failed = IntakeProfile.intake("庚", "v1", probesAllPass, conductOneFail)
        val retook = IntakeProfile.reprobe(failed, "v2", probesAllPass, conductAllPass)
        assertEquals("v2", retook.vouchedVersion)
        assertEquals(Clearance.FULL, retook.clearance)
        assertTrue(IntakeProfile.canAccept(retook, TaskKind.WRITES, "v2"))
    }

    @Test
    fun 重探_洗不掉越权污点() {
        var p = IntakeProfile.intake("辛", "v1", probesAllPass, conductAllPass)
        p = IntakeProfile.onTaskResult(p, TaskResult.VIOLATION, 1L, "越界写入")
        val retook = IntakeProfile.reprobe(p, "v2", probesAllPass, conductAllPass)
        assertEquals(Clearance.READ_ONLY, retook.clearance)
        assertEquals(1, retook.violations.size)
    }

    @Test
    fun 恢复只能走显式reinstate() {
        var p = IntakeProfile.intake("壬", "v1", probesAllPass, conductAllPass)
        p = IntakeProfile.onTaskResult(p, TaskResult.VIOLATION, 1L, "越权")
        p = IntakeProfile.reinstate(p)
        assertEquals(Clearance.FULL, p.clearance)
    }

    @Test
    fun 自报不据此放行_行为面证明() {
        // 简历吹得再满，画像里没有品行考，写任务一律不接。
        val resume = SelfReport("癸", "v9", listOf("会求和", "会复制", "绝不外发"))
        val p = IntakeProfile.intake(resume.agentId, resume.version, emptyList(), emptyList())
        assertFalse(IntakeProfile.canAccept(p, TaskKind.WRITES))
        assertTrue(p.conduct.isEmpty())
    }

    @Test
    fun 画像一行可读() {
        var p = IntakeProfile.intake("子", "v3", probesAllPass, conductAllPass)
        p = IntakeProfile.onTaskResult(p, TaskResult.SUCCESS, 1L, "成")
        val line = IntakeProfile.portrait(p)
        assertTrue(line.contains("版本 v3"))
        assertTrue(line.contains("探针 5/5"))
        assertTrue(line.contains("品行考 过"))
        assertTrue(line.contains("越权 0"))
    }
}
