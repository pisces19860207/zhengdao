// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百批 §1/§4 参数化单测：
// - 候选按来源列（输入/下拉/URL query 默认勾；页面固定文本列出来但**不许勾**）；
// - 参数化必须把字面量从**执行面**拔干净（负例 1：解析 JSON 断言，步骤里一个字都不许剩）；
// - 定位文本不许参数化（负例 2 的静态面：替换掉定位就找不到元素）。
//
// 一个口径要说清：`params[].options`（下拉词表）里**会**出现「北京/天津」——那不是残留，
// 是反问「可选：北京/天津」与「说天津 ⇒ 填天津的 value」的唯一依据；判据只认**步骤**，
// 步骤（value/expect/url/startUrl）里零出现。本文件两条断言把这两层分别钉住。
package com.example.zhengdao.baopu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachParamsTest {

    // ── 夹具：查货页（方向＝下拉、车牌＝输入、日期＝输入，结果页带 ?direction=）────────
    // 事件与技能在 TeachParamFixtures（与执行期单测共用一份，免得两份夹具悄悄漂开）

    private val PH_DIR = "\${dir}"
    private val PH_PLATE = "\${plate}"
    private val PH_DAY = "\${day}"

    private fun skill(): TeachSkill = TeachParamFixtures.skill()

    private fun pick(c: TeachParamCandidate, name: String = c.name, default: String? = null) =
        TeachParamFixtures.pick(c, name, default)

    private fun cand(sk: TeachSkill, example: String): TeachParamCandidate =
        TeachParamFixtures.candidate(sk, example)

    // ── 候选识别 ──────────────────────────────────────────────────────────────

    @Test
    fun `候选_输入与下拉默认勾_页面文本列出但不许勾`() {
        val cands = TeachParams.collect(skill())
        val byExample = cands.associateBy { it.example }

        val dir = byExample["北京"]
        assertNotNull("下拉的选中项要成为候选：${cands.map { it.describe() }}", dir)
        assertEquals(TeachParamSource.SELECT, dir!!.source)
        assertEquals(TeachParamShape.ENUM, dir.shape)
        assertEquals("下拉项要带选项表（反问的词表）", 3, dir.options.size)
        assertTrue("下拉默认勾", dir.defaultSelected)
        assertTrue("下拉能勾", dir.selectable)

        val plate = byExample["冀A12345"]
        assertNotNull(plate)
        assertEquals(TeachParamSource.INPUT, plate!!.source)
        assertEquals(TeachParamShape.CODE, plate.shape)
        assertTrue(plate.defaultSelected)

        val day = byExample["2026-10-24"]
        assertNotNull(day)
        assertEquals(TeachParamShape.DATE, day!!.shape)

        // 页面固定文本：列出来、默认不勾、且不许勾（结构锚）
        val text = byExample["查询"]
        assertNotNull("按钮名也该列出来（告诉用户「这个不能参数化」）", text)
        assertEquals(TeachParamSource.PAGE_TEXT, text!!.source)
        assertFalse(text.defaultSelected)
        assertFalse("结构锚不许勾", text.selectable)
        assertTrue(text.blockReason!!.contains("定位文本"))

        // 同一个字面量只出一个参数（?direction=北京 与下拉里的北京 合成一条）
        assertEquals(1, cands.count { it.example == "北京" })
        val dirSlots = byExample["北京"]!!.slots
        assertTrue("输入落点要并进同一条：" + dirSlots.map { it.describe() }, dirSlots.any { it.field == TeachSlotField.VALUE })
        assertTrue("URL 落点要并进同一条", dirSlots.any { it.field == TeachSlotField.URL })
    }

    @Test
    fun `候选_可勾的都有落点且名字合法_形状与路径段口径对`() {
        TeachParams.collect(skill()).filter { it.selectable }.forEach { c ->
            assertTrue("${c.name} 没落点", c.slots.isNotEmpty())
            assertTrue("${c.name} 名字不合法", TeachParam.NAME_RE.matches(c.name))
        }
        assertEquals(TeachParamShape.NUMBER, TeachParams.shapeOf("12", emptyList()))
        assertEquals(TeachParamShape.CODE, TeachParams.shapeOf("WB12345678", emptyList()))
        assertEquals(TeachParamShape.DATE, TeachParams.shapeOf("2026-10-24", emptyList()))
        assertEquals(TeachParamShape.TEXT, TeachParams.shapeOf("天津", emptyList()))
        assertEquals(TeachParamShape.ENUM, TeachParams.shapeOf("天津", listOf(TeachOption("天津", "天津"))))
        assertTrue(TeachParams.looksLikeData("2026-10-24"))
        assertTrue(TeachParams.looksLikeData("1024"))
        assertTrue(TeachParams.looksLikeData("WB123"))
        assertFalse("静态段不算数据", TeachParams.looksLikeData("list"))
        assertFalse("静态段不算数据", TeachParams.looksLikeData("api"))
    }

    // ── 参数化：字面量必须拔干净 ──────────────────────────────────────────────

    @Test
    fun `参数化_三个值拔干净_步骤里零残留_占位与expect都换`() {
        val sk = skill()
        val r = TeachParams.parameterize(sk, listOf("北京", "冀A12345", "2026-10-24").map { pick(cand(sk, it)) })
        assertTrue("参数化应成功：${r.errors}", r.ok)
        val out = r.skill!!

        // ① 解析 JSON 断言：执行面里一个字都不许剩（负例 1 的靶心）
        val left = TeachParams.residualLiterals(out, listOf("北京", "冀A12345", "2026-10-24"))
        assertTrue("不该有残留：$left", left.isEmpty())

        val json = JSONObject(out.toJsonText())
        val steps = json.getJSONArray("steps")
        // 只看**执行面**（value/url/expect）：这是真会发出去、真去等的东西
        val execText = buildString {
            for (i in 0 until steps.length()) {
                val s = steps.getJSONObject(i)
                append(s.optString("value")).append('\u0001')
                append(s.optString("url")).append('\u0001')
                val ea = s.optJSONArray("expect")!!
                for (j in 0 until ea.length()) {
                    append(ea.getJSONObject(j).optString("value")).append('\u0001')
                }
            }
        }
        assertFalse("执行面不许出现「北京」：$execText", execText.contains("北京"))
        assertFalse("执行面不许出现车牌", execText.contains("冀A12345"))
        assertFalse("执行面不许出现日期", execText.contains("2026-10-24"))
        // 词表已经搬到参数上：步骤里不再驮一份（否则同一个字会在 JSON 里出现两处）
        assertNull("步骤里不该再有 options（词表归参数）", steps.getJSONObject(0).optJSONArray("options"))

        // ② 占位符真的在位
        assertEquals(PH_DIR, steps.getJSONObject(0).getString("value"))
        assertEquals(PH_PLATE, steps.getJSONObject(1).getString("value"))
        assertEquals(PH_DAY, steps.getJSONObject(2).getString("value"))
        // ③ expect 也跟着换（否则回放时去等「北京」出现）
        assertEquals(PH_DIR, steps.getJSONObject(0).getJSONArray("expect").getJSONObject(0).getString("value"))
        // ④ URL 的 query 值换成占位并按原键留住
        assertTrue(
            "URL 也要拔：" + steps.getJSONObject(4).getString("url"),
            steps.getJSONObject(4).getString("url").contains("direction=$PH_DIR"),
        )
        // ⑤ 参数表只存声明：没有 example 键（样例值不落盘）
        assertEquals(3, out.params.size)
        val allowed = setOf("name", "label", "source", "shape", "slots", "default", "options")
        val pj = json.getJSONArray("params")
        for (i in 0 until pj.length()) {
            assertTrue("参数表多出没声明的键：${pj.getJSONObject(i)}", pj.getJSONObject(i).keys().asSequence().all { it in allowed })
        }
        // ⑥ 严格解析能读回来（形态合法）
        val back = TeachSkill.fromJson(out.toJsonText(), strict = true)
        assertNotNull(back)
        assertEquals(setOf("dir", "plate", "day"), TeachParams.placeholders(back!!))
    }

    @Test
    fun `参数化_默认值只写进参数表_不写进步骤`() {
        val sk = skill()
        val r = TeachParams.parameterize(sk, listOf(pick(cand(sk, "北京"), default = "唐山")))
        assertTrue(r.ok)
        val out = r.skill!!
        assertEquals("唐山", out.params.single().default)
        assertTrue("默认值不是残留（它在 params 里）", TeachParams.residualLiterals(out, listOf("唐山")).isEmpty())
        assertTrue(TeachParams.residualLiterals(out, listOf("北京")).isEmpty())
        // 步骤里不许出现默认值（它只在 params 里当声明）
        val steps = JSONObject(out.toJsonText()).getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            assertFalse(s.optString("value").contains("唐山"))
            assertFalse(s.optString("url").contains("唐山"))
        }
    }

    @Test
    fun `负例2_把定位文本当参数_拒绝且说清原因`() {
        val sk = skill()
        val textC = cand(sk, "查询")
        assertFalse("结构锚本来就不许勾", textC.selectable)
        val r = TeachParams.parameterize(sk, listOf(pick(textC)))
        assertFalse("必须拒绝：${r.errors}", r.ok)
        assertTrue(
            "要说清「那一步没有这个值/没落点」：${r.errors}",
            r.errors.any { it.contains("要替换") || it.contains("没拔干净") || it.contains("落点") },
        )
    }

    @Test
    fun `参数化_名字不合法或重复或空勾_一律拒绝`() {
        val sk = skill()
        val dirC = cand(sk, "北京")
        val plateC = cand(sk, "冀A12345")
        assertFalse("大写开头要拒", TeachParams.parameterize(sk, listOf(pick(dirC, name = "Direction"))).ok)
        assertFalse("数字开头要拒", TeachParams.parameterize(sk, listOf(pick(dirC, name = "1dir"))).ok)
        assertFalse("中文名要拒", TeachParams.parameterize(sk, listOf(pick(dirC, name = "方向"))).ok)
        val dup = TeachParams.parameterize(sk, listOf(pick(dirC, name = "p"), pick(plateC, name = "p")))
        assertFalse("同名要拒：${dup.errors}", dup.ok)
        assertTrue(dup.errors.any { it.contains("重复") })
        assertFalse("空勾要拒", TeachParams.parameterize(sk, emptyList()).ok)
    }

    @Test
    fun `参数化_全部勾上_拔完还能被196原样读回`() {
        val sk = skill()
        val r = TeachParams.parameterize(sk, TeachParams.collect(sk).filter { it.selectable }.map { pick(it) })
        assertTrue("参数化应成功：${r.errors}", r.ok)
        val out = r.skill!!
        val back = TeachSkill.fromJson(out.toJsonText(), strict = true)!!
        assertEquals(out.toJsonText(), back.toJsonText())
        assertEquals(TeachParams.placeholders(back), back.params.map { it.name }.toSet())
    }

    @Test
    fun `导出_参数化后的技能JSON样例_给交付报告引用`() {
        val out = TeachParamFixtures.parameterized()
        val dir = java.io.File("build/teach-sample").apply { mkdirs() }
        java.io.File(dir, "回放-查货-参数化.json").writeText(out.toJsonText(), Charsets.UTF_8)
        // 样例本身要能过严格解析，且步骤里零残留（报告引用的就是它，不是手写的）
        assertNotNull(TeachSkill.fromJson(out.toJsonText(), strict = true))
        assertTrue(TeachParams.residualLiterals(out, listOf("北京", "冀A12345", "2026-10-24")).isEmpty())
    }

    @Test
    fun `负例1_门禁本身有效_手写一份没拔干净JSON必须被抓到`() {
        val sk = skill()
        val out = TeachParams.parameterize(sk, listOf(pick(cand(sk, "北京")))).skill!!
        // 人为把字面量塞回步骤：门禁必须报出来（否则这条判据就是恒真的假闸）
        val bad = out.toJsonText().replace("\"value\":\"$PH_DIR\"", "\"value\":\"北京\"")
        val badSkill = TeachSkill.fromJson(bad, strict = true)
        assertNotNull(badSkill)
        val hits = TeachParams.residualLiterals(badSkill!!, listOf("北京"))
        assertTrue("门禁必须抓到残留：$hits", hits.isNotEmpty())
        assertTrue("要说清在哪：$hits", hits.first().contains("第 1 步 value"))
        // 对照组：正常那份是干净的（证明门禁不是「永远报错」）
        assertTrue(TeachParams.residualLiterals(out, listOf("北京")).isEmpty())
    }
}
