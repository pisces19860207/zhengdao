// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十六批：draw_chart 的判据层（bar|line，数据不猜、限额明确、SVG 静态）。
// 负例重点：长度对不上、负数、非数字、种类不认识、超限 —— 全部可读 Err。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawChartTest {

    private fun series(name: String, vararg values: Int): JSONObject = JSONObject()
        .put("name", name)
        .put("values", JSONArray(values.toList()))

    private fun data(cats: List<String>, vararg series: JSONObject): JSONObject =
        JSONObject()
            .put("categories", JSONArray(cats))
            .put("series", JSONArray(series.toList()))

    private fun ok(kind: String, data: JSONObject, title: String? = null): DrawChart.Rendered {
        val r = DrawChart.render(kind, data, title)
        assertTrue("应当成功，实际：$r", r is DrawResult.Ok)
        return (r as DrawResult.Ok).value
    }

    private fun err(kind: String, data: JSONObject, title: String? = null): String {
        val r = DrawChart.render(kind, data, title)
        assertTrue("应当失败，实际：$r", r is DrawResult.Err)
        return (r as DrawResult.Err).message
    }

    @Test
    fun `柱状图_类别与数值对应_产出静态SVG`() {
        val r = ok("bar", data(listOf("一月", "二月", "三月"), series("销量", 3, 5, 8)), title = "季度销量")
        assertEquals("bar", r.kind)
        // 三个数据柱（图例另有一个 12×12 的小方块，不能算进去）
        assertEquals(3, r.scene.shapes.count { it is DrawShape.Rect && it.w > 20 })
        val svg = r.scene.toSvg()
        assertTrue(svg.contains("季度销量"))
        assertTrue(svg.contains("一月") && svg.contains("三月"))
        assertTrue("数值要标在柱上", svg.contains("8"))
        val clean = MarkupSanitizer.sanitizeSvg(svg)
        assertTrue("自动生成的 SVG 必须零残留：${clean.problems}", clean.ok)
        assertEquals(0, clean.removed)
    }

    @Test
    fun `折线图_用折线而不是柱子`() {
        val r = ok("line", data(listOf("A", "B", "C"), series("温度", 1, 4, 2)))
        assertEquals("line", r.kind)
        assertTrue(r.scene.shapes.any { it is DrawShape.Poly })
        assertTrue(r.scene.toSvg().contains("<polyline"))
    }

    @Test
    fun `labels是categories的别名`() {
        val d = JSONObject()
            .put("labels", JSONArray(listOf("甲", "乙")))
            .put("series", JSONArray(listOf(series("s", 1, 2))))
        val r = ok("bar", d)
        assertTrue(r.scene.toSvg().contains("甲"))
    }

    @Test
    fun `顶层values_按单系列接住`() {
        val d = JSONObject()
            .put("categories", JSONArray(listOf("甲", "乙")))
            .put("name", "单系列")
            .put("values", JSONArray(listOf(1, 2)))
        val r = ok("bar", d)
        assertTrue(r.scene.toSvg().contains("单系列"))
    }

    @Test
    fun `series给对象而不是数组_也接住`() {
        val d = JSONObject()
            .put("categories", JSONArray(listOf("甲", "乙")))
            .put("series", series("单个", 1, 2))
        val r = ok("line", d)
        assertEquals(1, r.scene.shapes.count { it is DrawShape.Poly && !it.closed })
    }

    @Test
    fun `values长度与categories对不上_报错`() {
        val m = err("bar", data(listOf("甲", "乙"), series("s", 1)))
        assertTrue(m.contains("一一对应"))
    }

    @Test
    fun `负数值_报错而不是偷偷画成0`() {
        val m = err("bar", data(listOf("甲"), series("s", -1)))
        assertTrue(m.contains("负数"))
    }

    @Test
    fun `非数字_报错`() {
        val d = JSONObject()
            .put("categories", JSONArray(listOf("甲")))
            .put("series", JSONArray(listOf(JSONObject().put("name", "s").put("values", JSONArray().put("多")))))
        val m = err("bar", d)
        assertTrue(m.contains("不是数字"))
    }

    @Test
    fun `不认识kind_只说bar与line`() {
        val m = err("pie", data(listOf("甲"), series("s", 1)))
        assertTrue(m.contains("bar"))
        assertTrue(m.contains("line"))
        assertFalse(m.contains("flowchart"))
    }

    @Test
    fun `缺categories或series_可读错误`() {
        val noCats = JSONObject().put("series", JSONArray(listOf(series("s", 1))))
        assertTrue(err("bar", noCats).contains("categories"))
        val noSeries = JSONObject().put("categories", JSONArray(listOf("甲")))
        assertTrue(err("bar", noSeries).contains("series"))
    }

    @Test
    fun `类别超上限_报错`() {
        val cats = (1..DrawLimits.MAX_CATEGORIES + 1).map { "c$it" }
        val values = IntArray(cats.size) { 1 }
        val m = err("bar", data(cats, series("s", *values)))
        assertTrue(m.contains("类别太多"))
    }

    @Test
    fun `系列超上限_报错`() {
        val list = (1..DrawLimits.MAX_SERIES + 1).map { series("s$it", 1) }.toTypedArray()
        val m = err("bar", data(listOf("甲"), *list))
        assertTrue(m.contains("系列太多"))
    }

    @Test
    fun `全零数据不崩_轴上限取1`() {
        val r = ok("bar", data(listOf("甲", "乙"), series("s", 0, 0)))
        assertTrue(r.scene.toSvg().contains("<rect"))
    }

    @Test
    fun `标题与类别里的标记当文字_并计数`() {
        val r = ok(
            "bar",
            data(listOf("<b>甲</b>", "乙"), series("<script>x()</script>系列", 1, 2)),
            title = "<img src=x onerror=evil()>销量",
        )
        val svg = r.scene.toSvg()
        assertFalse(svg.contains("<script"))
        assertFalse(svg.contains("onerror"))
        assertFalse(svg.contains("<img"))
        assertTrue(svg.contains("销量"))
        assertTrue(r.notes.isNotEmpty())
    }
}
