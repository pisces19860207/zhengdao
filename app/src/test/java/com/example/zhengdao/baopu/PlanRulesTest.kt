// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十七批 §3 判据 J1（参数校验：空拒绝/超 8 截断/超 60 字截断）、
// J2（全量覆盖）、J3（勾选只增不减）、J4（payload 往返逐字一致——库侧回读见
// BaopuDbPlanTest）。判据全压 PlanRules 这个纯函数，JVM 直测。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanRulesTest {

    private fun args(vararg pairs: Pair<String, Any?>): JSONObject =
        JSONObject().also { j -> pairs.forEach { (k, v) -> j.put(k, v) } }

    private fun itemsJson(vararg items: Pair<String, String>): JSONArray = JSONArray().also { arr ->
        items.forEach { (text, status) ->
            arr.put(JSONObject().put("text", text).put("status", status))
        }
    }

    private fun parseOk(vararg items: Pair<String, String>): PlanRules.Parse.Ok {
        val r = PlanRules.parseItems(args("items" to itemsJson(*items)))
        assertTrue("本该解析成功：$r", r is PlanRules.Parse.Ok)
        return r as PlanRules.Parse.Ok
    }

    private fun parseErr(vararg items: Pair<String, Any?>): String {
        val r = PlanRules.parseItems(args(*items))
        assertTrue("本该拒绝：$r", r is PlanRules.Parse.Error)
        return (r as PlanRules.Parse.Error).message
    }

    private fun longText(n: Int): String = "甲".repeat(n)

    // ── J1：参数校验 ───────────────────────────────────────────────────

    @Test
    fun `items为空_拒绝`() {
        val msg = parseErr("items" to JSONArray())
        assertTrue("错误原文要说是空的：$msg", msg.contains("空"))
    }

    @Test
    fun `缺items参数_拒绝`() {
        val msg = parseErr()
        assertTrue("错误原文要点名 items：$msg", msg.contains("items"))
    }

    @Test
    fun `items不是数组_拒绝`() {
        val msg = parseErr("items" to "读文件,写文件")
        assertTrue("错误原文要说是数组：$msg", msg.contains("数组"))
    }

    @Test
    fun `条目不是对象_拒绝`() {
        val bad = JSONArray().put("读文件")
        val msg = parseErr("items" to bad)
        assertTrue("错误原文要说是第几条：$msg", msg.contains("第 1 条"))
    }

    @Test
    fun `缺text_拒绝`() {
        val bad = JSONArray().put(JSONObject().put("status", "todo"))
        val msg = parseErr("items" to bad)
        assertTrue("错误原文要点名 text：$msg", msg.contains("text"))
    }

    @Test
    fun `status不在枚举_拒绝并列出允许值`() {
        val msg = parseErr("items" to itemsJson("读文件" to "finish"))
        assertTrue("错误原文要点名 status：$msg", msg.contains("status"))
        assertTrue("错误原文要列出允许范围：$msg", msg.contains("todo") && msg.contains("done"))
    }

    @Test
    fun `text空_丢弃该条_其余保留`() {
        val r = parseOk("  " to "todo", "读文件" to "todo")
        assertEquals(1, r.items.size)
        assertEquals("读文件", r.items[0].text)
        assertTrue("丢条要有 notice：${r.notices}", r.notices.any { it.contains("空") })
    }

    @Test
    fun `全部text空_拒绝`() {
        val msg = parseErr(
            "items" to itemsJson(" " to "todo", "\t" to "doing"),
        )
        assertTrue("错误原文要说明原因：$msg", msg.contains("空"))
    }

    @Test
    fun `恰好8条_不截断`() {
        val r = parseOk(
            "一" to "todo", "二" to "todo", "三" to "todo", "四" to "todo",
            "五" to "todo", "六" to "todo", "七" to "todo", "八" to "todo",
        )
        assertEquals(8, r.items.size)
        assertTrue("不该有截断 notice：${r.notices}", r.notices.isEmpty())
    }

    @Test
    fun `超8条_截断到8并记notice`() {
        val nine = (1..9).map { "条目$it" to "todo" }.toTypedArray()
        val r = parseOk(*nine)
        assertEquals(8, r.items.size)
        assertEquals("条目8", r.items[7].text)
        assertTrue("截断要有 notice：${r.notices}", r.notices.any { it.contains("8") && it.contains("9") })
    }

    @Test
    fun `text超60字_截断到60并记notice`() {
        val r = parseOk(longText(70) to "todo")
        assertEquals(60, r.items[0].text.length)
        assertTrue("截断要有 notice：${r.notices}", r.notices.any { it.contains("60") })
    }

    // ── J2：全量覆盖 ───────────────────────────────────────────────────

    @Test
    fun `全量覆盖_先5条再3条_结果是3条不是8条合并`() {
        val first = parseOk("一" to "todo", "二" to "todo", "三" to "todo", "四" to "todo", "五" to "todo")
        val second = parseOk("一" to "done", "二" to "doing", "三" to "todo")
        val applied = PlanRules.applyOverwrite(first.items, second.items)
        assertEquals("全量覆盖：3 条就是 3 条", 3, applied.items.size)
        assertEquals(listOf("一", "二", "三"), applied.items.map { it.text })
    }

    @Test
    fun `全量覆盖_旧有条目不收编`() {
        val first = parseOk("老一" to "done", "老二" to "doing")
        val second = parseOk("新一" to "todo")
        val applied = PlanRules.applyOverwrite(first.items, second.items)
        assertEquals(1, applied.items.size)
        assertEquals("新一", applied.items[0].text)
        assertEquals(PlanRules.STATUS_TODO, applied.items[0].status)
    }

    @Test
    fun `空表上首轮_原样落地`() {
        val r = parseOk("读文件" to "doing", "写文件" to "todo")
        val applied = PlanRules.applyOverwrite(emptyList(), r.items)
        assertEquals(r.items, applied.items)
        assertTrue("没有 sticky 命中：${applied.stickyNotes}", applied.stickyNotes.isEmpty())
    }

    // ── J3：勾选只增不减 ───────────────────────────────────────────────

    @Test
    fun `done回退doing_保持done并告知`() {
        val first = parseOk("读文件" to "done")
        val second = parseOk("读文件" to "doing")
        val applied = PlanRules.applyOverwrite(first.items, second.items)
        assertEquals(PlanRules.STATUS_DONE, applied.items[0].status)
        assertTrue("sticky 要写进 notice：${applied.stickyNotes}", applied.stickyNotes.any { it.contains("读文件") })
    }

    @Test
    fun `done回退todo_同样保持done`() {
        val first = parseOk("读文件" to "done")
        val second = parseOk("读文件" to "todo")
        val applied = PlanRules.applyOverwrite(first.items, second.items)
        assertEquals(PlanRules.STATUS_DONE, applied.items[0].status)
    }

    @Test
    fun `todo进阶done_放行`() {
        val first = parseOk("读文件" to "todo")
        val second = parseOk("读文件" to "done")
        val applied = PlanRules.applyOverwrite(first.items, second.items)
        assertEquals(PlanRules.STATUS_DONE, applied.items[0].status)
        assertTrue(applied.stickyNotes.isEmpty())
    }

    @Test
    fun `未打勾条目_状态可自由改`() {
        val first = parseOk("读文件" to "todo")
        val second = parseOk("读文件" to "doing")
        val applied = PlanRules.applyOverwrite(first.items, second.items)
        assertEquals(PlanRules.STATUS_DOING, applied.items[0].status)
    }

    @Test
    fun `sticky按text匹配_改写text即新条目`() {
        val first = parseOk("读文件" to "done")
        val second = parseOk("读取文件" to "doing")
        val applied = PlanRules.applyOverwrite(first.items, second.items)
        assertEquals("改写字即新条目，不吃旧勾", PlanRules.STATUS_DOING, applied.items[0].status)
    }

    // ── J4：payload 往返逐字一致 ────────────────────────────────────────

    @Test
    fun `toJson_fromJson_往返逐字一致`() {
        val items = parseOk("读文件" to "done", longText(60) to "doing", "收尾" to "todo").items
        val payload = PlanRules.toJson(items)
        val back = PlanRules.fromJson(payload)
        assertEquals("条目逐条一致", items, back)
        assertEquals("再序列化逐字一致（库里的 payload 形状稳定）", payload, PlanRules.toJson(back))
    }

    @Test
    fun `fromJson_坏JSON_不抛返回空`() {
        assertTrue(PlanRules.fromJson("not json at all").isEmpty())
        assertTrue(PlanRules.fromJson("").isEmpty())
    }

    @Test
    fun `fromJson_跳过坏条目`() {
        val payload = """{"v":1,"items":[{"text":"好的","status":"done"},""" + """"坏条目",{"text":"没状态","status":"finish"}]}"""
        val back = PlanRules.fromJson(payload)
        assertEquals(1, back.size)
        assertEquals("好的", back[0].text)
    }

    // ── 文案（C 段 UI 与回灌共用） ─────────────────────────────────────

    @Test
    fun `summaryLine_格式`() {
        val items = parseOk("一" to "done", "二" to "done", "三" to "todo").items
        assertEquals("计划 2/3 完成", PlanRules.summaryLine(items))
    }

    @Test
    fun `renderAck_带notice与行动指引`() {
        val items = parseOk("一" to "done").items
        val ack = PlanRules.renderAck(items, listOf("共 9 条，已截断到前 8 条"))
        assertTrue("ack 要带进度：$ack", ack.contains("计划 1/1 完成"))
        assertTrue("ack 要带 notice：$ack", ack.contains("截断"))
        assertTrue("ack 要提醒整表重发：$ack", ack.contains("重发"))
    }

    @Test
    fun `renderAck_无notice时不吭声`() {
        val items = parseOk("一" to "todo").items
        val ack = PlanRules.renderAck(items, emptyList())
        assertFalse("没 notice 就不该出现「注意」", ack.contains("注意"))
    }

    @Test
    fun `doneCount_与summaryLine同源`() {
        val items = parseOk("一" to "done", "二" to "todo", "三" to "done", "四" to "todo").items
        assertEquals(2, PlanRules.doneCount(items))
    }

    @Test
    fun `状态常量集合_就是三个`() {
        // PlanTool 的 schema enum 取自这里；两处漂移 = 模型发的合法值在校验侧变非法
        // （跨文件的同源断言在 PlanToolTest，这里只钉集合本身）
        assertEquals(listOf("todo", "doing", "done"), PlanRules.STATUSES)
    }

    @Test
    fun `J1_状态别名归一_in_progress不算错`() {
        // 2026-10-19 真机：模型把「正在做」写成 in_progress ⇒ 整张计划被拒，用户看到「第一步就被挡住」
        assertEquals(PlanRules.STATUS_DOING, PlanRules.normalizeStatus("in_progress"))
        assertEquals(PlanRules.STATUS_DOING, PlanRules.normalizeStatus("In-Progress"))
        assertEquals(PlanRules.STATUS_TODO, PlanRules.normalizeStatus("pending"))
        assertEquals(PlanRules.STATUS_DONE, PlanRules.normalizeStatus("completed"))
        assertNull("认不出的就该拒，不许瞎猜", PlanRules.normalizeStatus("瞎写"))
        val ok = parseOk("读目录" to "in_progress", "写文件" to "todo")
        assertEquals(PlanRules.STATUS_DOING, ok.items[0].status)
    }

}
