// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百一十七批 · 验收接管补缝的行为测试：改约基线「何时被消费」。
//
// 为什么有这个文件：这段语义原先只写在 `RunEngine.amendGate` 里靠引擎私有字段判断，
// 单测够不着 ⇒ 只能拿源级 grep 钉形状（误红/误绿的绊线，不是证明）。抽成
// [AmendRiskBaseline.step] 之后，「改约后先来一次 update_plan、再来那次动作」这条序列
// 可以直接喂工具名钉住——判据由字符串匹配升级成行为断言。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.agent.PlanTool
import com.example.zhengdao.baopu.agent.ToolCall
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AmendRiskBaselineTest {

    private fun call(name: String) = ToolCall(id = "call-$name", name = name, arguments = JSONObject())

    private val del = call("delete_path")
    private val plan = call(PlanTool.NAME)

    @Test
    fun `改约后的 update_plan 不消费基线_基线原样留着`() {
        val out = AmendRiskBaseline.step(plan, stored = del)
        assertNull("update_plan 不是带新参数的动作，不许拿走基线", out.taken)
        assertSame("基线必须原样留在字段里等真正的动作", del, out.nextBaseline)
        assertNull("update_plan 不是「最后一次动作」", out.lastAction)
    }

    @Test
    fun `改约后先来一次 update_plan_再来动作_那一次动作才拿得到基线`() {
        // 序列：改约落地把 del 挂成基线 → 模型先发 update_plan → 再发真动作
        val first = AmendRiskBaseline.step(plan, stored = del)
        assertNull(first.taken)
        val second = AmendRiskBaseline.step(call("delete_path"), stored = first.nextBaseline)
        assertSame("真正的动作必须拿到基线去重算（否则旧批准罩住新要求）", del, second.taken)
        assertNull("消费掉之后基线清空", second.nextBaseline)
        assertEquals("delete_path", second.lastAction?.name)
    }

    @Test
    fun `没有基线时动作也照样只刷新最后一次动作`() {
        val out = AmendRiskBaseline.step(del, stored = null)
        assertNull("没基线就没有可比对的一对参数", out.taken)
        assertNull(out.nextBaseline)
        assertEquals("delete_path", out.lastAction?.name)
    }

    @Test
    fun `连续两次动作_第一次消费基线_第二次不再重复消费`() {
        val first = AmendRiskBaseline.step(del, stored = del)
        assertSame(del, first.taken)
        val second = AmendRiskBaseline.step(call("write_file"), stored = first.nextBaseline)
        assertNull("基线是一次性的：同一份改约只重算一次", second.taken)
        assertEquals("write_file", second.lastAction?.name)
    }

    @Test
    fun `连续两次改约_基线不被 update_plan 吃掉_仍等最后一次动作`() {
        var stored: ToolCall? = del
        repeat(2) {
            val p = AmendRiskBaseline.step(plan, stored)
            assertNull(p.taken)
            stored = p.nextBaseline
        }
        assertSame("两次 update_plan 之后基线还在", del, stored)
        val act = AmendRiskBaseline.step(call("move_path"), stored)
        assertSame(del, act.taken)
    }
}
