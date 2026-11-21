// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十五批 · 失败→计划步号三态归因的**判据断言**（派工单 755 判据逐条落测）。
// 纯 JVM：StepRef 零 Android 依赖（org.json 都不碰），直测即可。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.StepRef.Ambiguous
import com.example.zhengdao.baopu.StepRef.Attributed
import com.example.zhengdao.baopu.StepRef.Code
import com.example.zhengdao.baopu.StepRef.Unattributed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepRefTest {

    // ── 判据② 三态闭集与线格式（wire 逐字；第九种形态产不出来） ──

    @Test
    fun `判据1_三态线格式逐字_attributed_ambiguous_unattributed`() {
        assertEquals("attributed:3", Attributed(3).wire)
        assertEquals("ambiguous:2,4", Ambiguous(listOf(2, 4)).wire)
        assertEquals("unattributed:no-plan", Unattributed(Code.NO_PLAN).wire)
        assertEquals("unattributed:step-not-recorded", Unattributed(Code.STEP_NOT_RECORDED).wire)
        assertEquals("unattributed:tool-failed-early", Unattributed(Code.TOOL_FAILED_EARLY).wire)
    }

    @Test
    fun `判据1_原因码闭集恰好三个_线名逐字对派工单`() {
        assertEquals(
            listOf("no-plan", "step-not-recorded", "tool-failed-early"),
            Code.entries.map(Code::wire),
        )
        assertNull(Code.of("no_plan")) // 下划线变体不认（线名逐字）
        assertNull(Code.of("No-Plan")) // 大小写敏感（wire 是线格式，753 Loop.of 同口径）
        assertNull(Code.of(""))
        assertNull(Code.of("no-failure")) // 自造第四码 ⇒ null（第九个产不出来）
    }

    @Test
    fun `判据1_parse回读三态逐字还原_of与wire互逆`() {
        listOf<StepRef>(
            Attributed(3),
            Ambiguous(listOf(2, 4, 9)),
            Unattributed(Code.NO_PLAN),
            Unattributed(Code.STEP_NOT_RECORDED),
            Unattributed(Code.TOOL_FAILED_EARLY),
        ).forEach { ref -> assertEquals(ref, StepRef.of(ref.wire)) }
    }

    @Test
    fun `判据1_parse拒判_坏形状一律null`() {
        // attributed：步号 <1、非数字、空段
        assertNull(StepRef.of("attributed:0"))
        assertNull(StepRef.of("attributed:-2"))
        assertNull(StepRef.of("attributed:abc"))
        assertNull(StepRef.of("attributed:"))
        // ambiguous：候选 <2、不升序、重复、越界、非数字
        assertNull(StepRef.of("ambiguous:3"))
        assertNull(StepRef.of("ambiguous:"))
        assertNull(StepRef.of("ambiguous:5,3"))
        assertNull(StepRef.of("ambiguous:3,3"))
        assertNull(StepRef.of("ambiguous:0,2"))
        assertNull(StepRef.of("ambiguous:3,x"))
        // 头部不在闭集 / 缺冒号 / 空串
        assertNull(StepRef.of("step:3"))
        assertNull(StepRef.of("Attributed:3"))
        assertNull(StepRef.of("attributed:3x"))
        assertNull(StepRef.of(""))
    }

    @Test
    fun `判据2_ambiguous少于两候选构造不出来_判据的机器牙`() {
        try {
            Ambiguous(listOf(3))
            throw AssertionError("候选 1 个不该构造成功")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("至少两个候选"))
        }
        try {
            Attributed(0)
            throw AssertionError("步号 0 不该构造成功")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("从 1 起"))
        }
    }

    // ── 判据① from：当场归因的五种结局（含三个原因码各一条可达路） ──

    private fun item(text: String, status: String) = PlanRules.PlanItem(text, status)

    @Test
    fun `判据3_恰一条doing归attributed_步号是条目序号加一`() {
        val plan = listOf(
            item("建索引", PlanRules.STATUS_DONE),
            item("登记伏笔", PlanRules.STATUS_DOING),
            item("续写", PlanRules.STATUS_TODO),
        )
        assertEquals(Attributed(2), StepRef.from(plan, storeHasPlan = true))
    }

    @Test
    fun `判据3_两条doing归ambiguous_候选升序含全部doing`() {
        val plan = listOf(
            item("a", PlanRules.STATUS_DOING),
            item("b", PlanRules.STATUS_DONE),
            item("c", PlanRules.STATUS_DOING),
            item("d", PlanRules.STATUS_TODO),
        )
        assertEquals(Ambiguous(listOf(1, 3)), StepRef.from(plan, storeHasPlan = true))
    }

    @Test
    fun `判据3_计划在但没标doing归step_not_recorded_不猜第一条没做完的`() {
        val plan = listOf(
            item("a", PlanRules.STATUS_DONE),
            item("b", PlanRules.STATUS_TODO),
        )
        assertEquals(Unattributed(Code.STEP_NOT_RECORDED), StepRef.from(plan, storeHasPlan = true))
    }

    @Test
    fun `判据3_空计划有计划史归tool_failed_early_没史归no_plan`() {
        assertEquals(
            Unattributed(Code.TOOL_FAILED_EARLY),
            StepRef.from(emptyList(), storeHasPlan = true),
        )
        assertEquals(Unattributed(Code.NO_PLAN), StepRef.from(emptyList(), storeHasPlan = false))
    }

    @Test
    fun `判据3_全done的计划没doing也是step_not_recorded_不拿完成步冒充失败步`() {
        val plan = listOf(item("a", PlanRules.STATUS_DONE), item("b", PlanRules.STATUS_DONE))
        assertEquals(Unattributed(Code.STEP_NOT_RECORDED), StepRef.from(plan, storeHasPlan = true))
    }

    // ── 判据④ 同输入两次一致（纯函数零状态；wire 逐字节可复算） ──

    @Test
    fun `判据4_同输入两次读数逐字一致`() {
        val plan = listOf(
            item("x", PlanRules.STATUS_DOING),
            item("y", PlanRules.STATUS_DOING),
        )
        val a = StepRef.from(plan, storeHasPlan = false)
        val b = StepRef.from(plan, storeHasPlan = false)
        assertEquals(a, b)
        assertEquals(a.wire, b.wire)
        assertEquals("ambiguous:1,2", a.wire)
        // 同一引用再 parse 一遍仍是同一值（回读不改历史，只产新对象）
        assertEquals(a, StepRef.of(a.wire))
    }

    // ── 判据⑤ 755③ 与 753 JSONL 对齐：字段名 stepRef、值是 attributed 的那个数 ──

    @Test
    fun `判据5_attributed的步号就是JSONL_stepRef该带的值_歧义与未归因不给数`() {
        // attributed ⇒ 计划步号本身（loop=plan reading 的 stepRef 键就落它；null 的口径
        // 在 LoopReadingsTest 与 validate 白名单里钉）。
        val attributed = StepRef.from(
            listOf(item("a", PlanRules.STATUS_DONE), item("b", PlanRules.STATUS_DOING)),
            storeHasPlan = true,
        )
        assertEquals(2, (attributed as Attributed).step)
        // ambiguous/unattributed 没有单一落点 ⇒ JSONL 的 stepRef 只能 null（判不了就不猜）
        assertTrue(StepRef.of("ambiguous:1,2") is Ambiguous)
        assertTrue(StepRef.of("unattributed:no-plan") is Unattributed)
    }
}
