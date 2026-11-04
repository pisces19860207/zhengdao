// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十七批 ①的行为判据（派工单判据①「造真实场景 ⇒ 目标能力被调用」＋ 2026-11-04
// 批示「decidedBy=CENTER_AUTO：门照旧拒、日志照记，账上不许写人裁」）：
// 自动代行的处置申请真的进了 DispositionGate.decide，被判定①的源级钉如实拒掉——
// 门拒什么记什么，本文件不加工。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.law.DispositionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DispositionWiringTest {

    private fun capture(): MutableList<String> = mutableListOf()

    @Test
    fun `判据一_自动代行被源级钉拒_日志照记_账不落人裁`() {
        val log = capture()
        DispositionWiring.onMatrixReject(
            toolName = "delete_path",
            reasonCode = "raw_zone",
            detail = "路径在原件区内",
            log = { log += it },
        )
        assertTrue("自动申请没进门（日志里没有门拒那一行）：$log", log.size == 1)
        assertTrue(log[0].contains("门拒（not_center）"))
        // 批示的原话级判据：decidedBy 不是 user（center_auto 被点名），且明说不落处置账。
        assertTrue(log[0].contains("center_auto"))
        assertTrue(log[0].contains("不是「user」"))
        assertTrue(log[0].contains("自动代行不落处置账"))
        assertTrue("申请对象没带上：${log[0]}", log[0].contains("申请对象 delete_path"))
        // 门给的拒因里不许把这笔自动申请记成"user 裁的"。
        assertFalse(log[0].contains("decidedBy=「user」"))
    }

    @Test
    fun `判据二_源级钉最前置_御批对象也是not_center_边界格轮不到`() {
        // 处置对象指向御批账也先撞判定①（检查链顺序：源 → 边界）——这不是漏，
        // 是门自己的顺序：接线口是自动代行，永远到不了圣旨边界那一格；要真测边界，
        // 得由 user 亲自发起。本接线口如实记 not_center，不冒充边界判定。
        val log = capture()
        DispositionWiring.onMatrixReject(
            toolName = RoyalDecisionLedger.FILE_NAME,
            reasonCode = "raw_zone",
            detail = "路径在原件区内",
            log = { log += it },
        )
        assertTrue(log.single().contains("门拒（not_center）"))
        assertFalse("自动申请不该冒充边界判定：${log.single()}", log.single().contains("edict_immutable"))
    }

    @Test
    fun `判据三_常量口径_center_auto不是user`() {
        // 批示的机制级判据：自动标记与"人裁"标记必须是两个值——相等＝自动记成人裁＝批示被破。
        assertFalse(DispositionWiring.DECIDER_CENTER_AUTO == DispositionGate.DECIDER_USER)
        assertEquals("center_auto", DispositionWiring.DECIDER_CENTER_AUTO)
    }
}