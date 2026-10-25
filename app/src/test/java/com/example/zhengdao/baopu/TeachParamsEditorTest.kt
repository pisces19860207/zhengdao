// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百批 §3/§4 编辑器单测：只做四件事（改参数名/改默认值/删参数回退字面量/调换步骤顺序），
// 做不了的（拖拽/条件/循环）用**结构**堵死——schema 里没有这些键，严格解析一律拒收（负例 6）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.TeachParams.Editor
import com.example.zhengdao.baopu.TeachParams.TeachEditOp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachParamsEditorTest {

    private fun base(): TeachSkill = TeachParamFixtures.parameterized()

    private fun ok(r: TeachParams.TeachEditResult): TeachSkill {
        assertTrue("编辑该成功：${r.error}", r.ok)
        return r.skill!!
    }

    // ── ① 改参数名 ────────────────────────────────────────────────────────────

    @Test
    fun `改参数名_占位符一起改_严格解析仍通过`() {
        val out = ok(Editor.apply(base(), TeachEditOp.RenameParam("dir", "direction")))
        assertTrue(out.params.any { it.name == "direction" })
        assertFalse(out.params.any { it.name == "dir" })
        assertEquals(setOf("direction", "plate", "day"), TeachParams.placeholders(out))
        assertEquals("\${direction}", out.steps.first().value)
        assertNotNull(TeachSkill.fromJson(out.toJsonText(), strict = true))
    }

    @Test
    fun `改参数名_名字不合法或重名_拒绝`() {
        assertFalse(Editor.apply(base(), TeachEditOp.RenameParam("dir", "Direction")).ok)
        assertFalse(Editor.apply(base(), TeachEditOp.RenameParam("dir", "1x")).ok)
        assertFalse(Editor.apply(base(), TeachEditOp.RenameParam("dir", "plate")).ok)
        assertFalse(Editor.apply(base(), TeachEditOp.RenameParam("nope", "x")).ok)
    }

    // ── ② 改默认值 ────────────────────────────────────────────────────────────

    @Test
    fun `改默认值_只动参数表_一步都不动`() {
        val sk = base()
        val out = ok(Editor.apply(sk, TeachEditOp.SetDefault("dir", "唐山")))
        assertEquals("唐山", out.params.first { it.name == "dir" }.default)
        assertEquals("步骤必须原样：" , sk.steps.map { it.canonical() }, out.steps.map { it.canonical() })
        // 清掉默认值
        val cleared = ok(Editor.apply(out, TeachEditOp.SetDefault("dir", "  ")))
        assertNull(cleared.params.first { it.name == "dir" }.default)
        assertFalse(Editor.apply(sk, TeachEditOp.SetDefault("nope", "x")).ok)
    }

    // ── ③ 删参数（回退成字面量）──────────────────────────────────────────────

    @Test
    fun `删参数_用默认值回退成字面量_参数与占位一起消失`() {
        val sk = ok(Editor.apply(base(), TeachEditOp.SetDefault("dir", "唐山")))
        val out = ok(Editor.apply(sk, TeachEditOp.DeleteParam("dir")))
        assertFalse("参数要删掉", out.params.any { it.name == "dir" })
        assertFalse("占位要没了", TeachParams.placeholders(out).contains("dir"))
        assertEquals("第 1 步回到字面量", "唐山", out.steps.first().value)
        assertNotNull(TeachSkill.fromJson(out.toJsonText(), strict = true))
    }

    @Test
    fun `删参数_没默认值就不许删_说清为什么`() {
        val r = Editor.apply(base(), TeachEditOp.DeleteParam("plate"))
        assertFalse("没默认值不能删：${r.error}", r.ok)
        assertTrue(r.error!!.contains("默认值"))
    }

    // ── ④ 调换步骤顺序 ────────────────────────────────────────────────────────

    @Test
    fun `调换步骤顺序_步号重排_参数落点跟着走`() {
        val sk = base() // 1=选方向 2=车牌 3=日期 4=查询 5=翻页
        val out = ok(Editor.apply(sk, TeachEditOp.MoveStep(from = 3, to = 1)))
        assertEquals(5, out.steps.size)
        assertEquals("步号必须是 1..5 连续", listOf(1, 2, 3, 4, 5), out.steps.map { it.index })
        assertEquals("原来的第 3 步（日期）现在排第一", "\${day}", out.steps.first().value)
        assertEquals("日期参数的落点要跟着改成第 1 步", 1, out.params.first { it.name == "day" }.slots.first().step)
        assertNotNull(TeachSkill.fromJson(out.toJsonText(), strict = true))
    }

    @Test
    fun `调换步骤顺序_越界拒绝`() {
        assertFalse(Editor.apply(base(), TeachEditOp.MoveStep(0, 2)).ok)
        assertFalse(Editor.apply(base(), TeachEditOp.MoveStep(2, 99)).ok)
        // 原地不动：不算错，原样返回
        assertTrue(Editor.apply(base(), TeachEditOp.MoveStep(2, 2)).ok)
    }

    // ── 负例 6：编辑器做不出条件分支/循环（结构面）─────────────────────────────

    @Test
    fun `负例6_技能JSON里出现循环或条件_严格解析一律拒收`() {
        val text = base().toJsonText()
        for (bad in listOf("loop", "repeat", "if", "condition", "branch", "children", "foreach")) {
            val o = JSONObject(text)
            o.put(bad, JSONObject().put("times", 2))
            val withBad = o.toString()
            assertNull("顶层出现「$bad」必须拒收", TeachSkill.fromJson(withBad, strict = true))
            // 对照：非严格解析照旧能读（证明是「闸」在拦，不是解析坏了）
            assertNotNull("非严格解析不该受影响（否则这条判据是假闸）", TeachSkill.fromJson(withBad))
        }
        // 步骤级也一样
        val o2 = JSONObject(text)
        o2.getJSONArray("steps").getJSONObject(0).put("children", JSONArray())
        assertNull(TeachSkill.fromJson(o2.toString(), strict = true))
        // 参数级也一样
        val o3 = JSONObject(text)
        o3.getJSONArray("params").getJSONObject(0).put("onMissing", "skip")
        assertNull(TeachSkill.fromJson(o3.toString(), strict = true))
    }

    @Test
    fun `负例6_编辑器可表达的操作恰好四种_没有拖拽分支循环`() {
        val ops = TeachParams.TeachEditOp::class.java.declaredClasses.mapNotNull { it.simpleName }.toSet()
        assertEquals(
            "能力边界就是这四种；多出来一种说明有人偷偷加了编排表达能力",
            setOf("RenameParam", "SetDefault", "DeleteParam", "MoveStep"),
            ops,
        )
    }

    @Test
    fun `四种操作来回做一遍_技能始终可被196严格读回`() {
        var sk = base()
        sk = ok(Editor.apply(sk, TeachEditOp.RenameParam("day", "biz_date")))
        sk = ok(Editor.apply(sk, TeachEditOp.SetDefault("biz_date", "2026-10-24")))
        sk = ok(Editor.apply(sk, TeachEditOp.MoveStep(1, 2)))
        sk = ok(Editor.apply(sk, TeachEditOp.DeleteParam("biz_date")))
        assertNotNull(TeachSkill.fromJson(sk.toJsonText(), strict = true))
        assertFalse(sk.params.any { it.name == "biz_date" })
        // 视图（编辑器「步骤＋参数」那一块）也拿得出来东西
        assertEquals(5, Editor.stepsView(sk).size)
        assertTrue(Editor.stepsView(sk).any { it.contains("\${dir}") })
        assertTrue(Editor.paramsView(sk).any { it.contains("dir") })
    }
}
