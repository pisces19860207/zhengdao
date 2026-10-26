// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu.cards

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二百一十二批 · 模板层判据（派工单 §2.5）。
 *
 * 钉住四件事：
 * ① 随包的三份模板**真的能解析**（不是「照着 JSON 写了一遍断言」——读的是 assets 里的原文）；
 * ② 缺值落「未填」且不可拨（负例⑤④）；值原样，不补 0；
 * ③ 解析是严格的（重复 key／未知动作／缺 labelKey／空组 各红）；
 * ④ 「≤ 屏 60%」是一条能算的判据（[ResultCardBudget]），不是愿望。
 */
class ResultCardTemplateTest {

    private fun asset(rel: String): String {
        val f = File("src/main/assets/$rel").let { if (it.exists()) it else File("app/src/main/assets/$rel") }
        assertTrue("找不到 assets/$rel：${f.path}", f.isFile)
        return f.readText()
    }

    private fun parseAsset(id: String): ResultCardTemplate {
        val load = ResultCardTemplates.parse("assets/baopu/cards/$id.json", asset("baopu/cards/$id.json"))
        assertTrue("$id 解析失败：${(load as? TemplateLoad.Bad)?.reason}", load is TemplateLoad.Ok)
        return (load as TemplateLoad.Ok).template
    }

    /** 假文案表：键回显，便于断言「label 来自 labelKey 而不是 JSON 里的字面量」。 */
    private val lookup: (String) -> String = { k -> "L:$k" }

    @Test
    fun `三份随包模板都能解析_且 id 与文件名一致`() {
        listOf("logistics.waybill", "study.knowledge", "write.topic").forEach { id ->
            val t = parseAsset(id)
            assertEquals(id, t.templateId)
            assertEquals(ResultCardTemplate.FORMAT_VERSION, t.version)
            assertTrue("$id 至少一组", t.groups.isNotEmpty())
            t.groups.forEach { g -> assertTrue("$id/组 ${g.key} 没有行", g.rows.isNotEmpty()) }
        }
    }

    @Test
    fun `物流模板_组顺序与客户两行的隐私标记`() {
        val t = parseAsset("logistics.waybill")
        // 小结在最前：她问「北京装没装」时，答案就是那几个数
        assertEquals(listOf("summary", "doc", "customer", "goods", "route", "vehicle"), t.groups.map { it.key })
        val customer = t.groups.first { it.key == "customer" }
        assertEquals(listOf("customerName", "customerPhone"), customer.rows.map { it.key })
        assertTrue("客户两行都落在 sensitiveInLog（显示原样、日志不记）", customer.rows.all { it.sensitiveInLog })
        assertTrue("客户电话可拨（标记）；真能不能拨看有没有 dial", customer.rows.last().dialable)
        // 三个只读动作，一个字都不许有提交类
        assertEquals(listOf("copyAll", "detail", "followUp"), t.actions.map { it.id })
    }

    @Test
    fun `缺值落未填且不可拨_值原样不补零`() {
        val t = parseAsset("logistics.waybill")
        val card = ResultCardBinder.bind(
            template = t,
            values = mapOf(
                "waybill" to "1234567",
                "pieces" to "0", // 表里真写了 0 ⇒ 原样给 0，不许当成「没填」
                "driverPhone" to "", // 空串 ⇒ 未填
            ),
            lookup = lookup,
            dials = mapOf("driverPhone" to "13800138000"), // 号码算出来了，但值是空的
            itemCount = 1,
        )
        val rows = card.rows.associateBy { it.key }
        assertEquals("L:baopu_card_unfilled", rows.getValue("goods").value)
        assertFalse(rows.getValue("goods").filled)
        assertEquals("0", rows.getValue("pieces").value)
        assertTrue(rows.getValue("pieces").filled)
        // 未填的行即便 producer 给了 dial 也不可拨（负例④：点下去只能拨空号）
        assertNull(rows.getValue("driverPhone").dial)
        assertFalse(rows.getValue("driverPhone").dialable)
        assertEquals("1234567", rows.getValue("waybill").value)
    }

    @Test
    fun `有值的号码才带 dial_且 label 来自 labelKey`() {
        val t = parseAsset("logistics.waybill")
        val card = ResultCardBinder.bind(
            template = t,
            values = mapOf("driverPhone" to "138 0013 8000", "driver" to "王师傅"),
            lookup = lookup,
            dials = mapOf("driverPhone" to "13800138000"),
        )
        val row = card.rows.first { it.key == "driverPhone" }
        assertEquals("13800138000", row.dial)
        assertEquals("138 0013 8000", row.value) // 显示原样，拨号串另存
        assertEquals("L:baopu_card_logistics_driver_phone", row.label)
        assertEquals("L:baopu_card_logistics_title", card.title)
    }

    @Test
    fun `解析严格_五类坏模板各红`() {
        val cases = mapOf(
            "重复 key" to """{"templateId":"x","version":1,"titleKey":"t","groups":[
                {"key":"g","rows":[{"key":"a","labelKey":"l"},{"key":"a","labelKey":"l2"}]}]}""",
            "未知动作 id" to """{"templateId":"x","version":1,"titleKey":"t","groups":[
                {"key":"g","rows":[{"key":"a","labelKey":"l"}]}],
                "actions":[{"id":"submit","labelKey":"l"}]}""",
            "缺 labelKey" to """{"templateId":"x","version":1,"titleKey":"t","groups":[
                {"key":"g","rows":[{"key":"a","label":"字面量"}]}]}""",
            "空 groups" to """{"templateId":"x","version":1,"titleKey":"t","groups":[]}""",
            "不认识的版本" to """{"templateId":"x","version":2,"titleKey":"t","groups":[
                {"key":"g","rows":[{"key":"a","labelKey":"l"}]}]}""",
        )
        cases.forEach { (name, json) ->
            val load = ResultCardTemplates.parse("assets/baopu/cards/x.json", json)
            assertTrue("「$name」应当红，却过了", load is TemplateLoad.Bad)
        }
        // 合法的最小模板要能过（负例的另一半：不能宽到不许过、也不能严到过不去）
        val ok = ResultCardTemplates.parse(
            "assets/baopu/cards/x.json",
            """{"templateId":"x","version":1,"titleKey":"t","groups":[{"key":"g","rows":[{"key":"a","labelKey":"l"}]}]}""",
        )
        assertTrue(ok is TemplateLoad.Ok)
    }

    @Test
    fun `预算_屏六成与折行`() {
        // 800dp 的消息区 ⇒ 480dp 上限；扣掉卡头动作条 152 ⇒ 12 行（26dp 一行）
        assertEquals(480, ResultCardBudget.maxHeightDp(800))
        assertEquals(12, ResultCardBudget.maxRows(800))
        // 矮屏不许把行数压成 0 或负数（再矮也要看得见一条）
        assertTrue(ResultCardBudget.maxRows(200) >= ResultCardBudget.MIN_ROWS)

        val t = parseAsset("logistics.waybill")
        val card = ResultCardBinder.bind(
            template = t,
            values = mapOf("waybill" to "1", "goods" to "钢材"),
            lookup = lookup,
        )
        val total = card.rows.size
        assertTrue("这张模板的行数应当多于 3 行（否则这条判据是空转）", total > 3)
        val folded = ResultCardBudget.fold(card, 3)
        assertEquals(3, folded.card.rows.size)
        assertEquals(total - 3, folded.hiddenRows)
        // 保留的是**前面**几行（先看头一票），不是随便抽三行
        assertEquals(card.rows.take(3).map { it.key }, folded.card.rows.map { it.key })
        // 不超预算时一行都不折
        assertEquals(0, ResultCardBudget.fold(card, total).hiddenRows)
    }

    @Test
    fun `同一件事的判定_按模板与逐行值`() {
        val t = parseAsset("write.topic")
        val a = ResultCardBinder.bind(t, mapOf("topic" to "城中村"), lookup)
        val b = ResultCardBinder.bind(t, mapOf("topic" to "城中村"), lookup)
        val c = ResultCardBinder.bind(t, mapOf("topic" to "学区房"), lookup)
        assertTrue(sameResult(a, b))
        assertFalse(sameResult(a, c))
    }
}
