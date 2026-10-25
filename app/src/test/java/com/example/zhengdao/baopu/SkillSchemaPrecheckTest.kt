// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百八十五批 · 技能 schema 预检的用例与负例（派工单 §3 逐条）：
// 合法三家各 1 通过；Gemini type 大写 ⇒ 拒；错字段 ⇒ 拒；缺 required ⇒ 拒；
// 各 provider 不串规则（OpenAI 示例按 Anthropic 校验 ⇒ 拒）；
// 负例「预检失败仍写盘」「错误被吞」在这里全红；
// 另有一条源码门禁：Gemini 白名单与发送侧 `GeminiWire.sanitizeSchema` 的 keep 集逐字同步。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.SkillSchemaPrecheck.Family
import com.example.zhengdao.ui.baopu.SkillCardBuild
import com.example.zhengdao.ui.baopu.SkillCardDraft
import com.example.zhengdao.ui.baopu.SkillCardRules
import com.example.zhengdao.ui.baopu.SkillCardWriter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.io.File

class SkillSchemaPrecheckTest {

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        projectRoot = File.createTempFile("zd-schema185-proj", "").let { it.delete(); it.mkdirs(); it }
        globalRoot = File.createTempFile("zd-schema185-glob", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
        globalRoot.deleteRecursively()
    }

    private fun pf() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private fun proposal(body: String, name: String = "平台填卡示例") =
        SkillProposal(scope = SkillScope.PROJECT, name = name, body = body, reason = "记一条", draft = true)

    /** 把 JSON 装进一个像样的技能正文（标题＋围栏块＋步骤句）。 */
    private fun skillBody(json: String): String =
        "# 平台填卡示例\n\n先按下面的工具形状调用：\n\n```json\n$json\n```\n\n步骤：先看字段清单再填。"

    // ── 三家合法示例（派工单 §3：各 1 通过） ───────────────────────────────

    @Test
    fun `P1_Gemini合法示例_当前API形状通过`() {
        val body = skillBody(
            """
            {"name":"fill_form","description":"按字段清单填表",
             "parametersJsonSchema":{"type":"object","properties":{
                 "fields":{"type":"array","items":{"type":"string"}},
                 "draft":{"type":"boolean","description":"先落草稿"}},
               "required":["fields"]}}
            """.trimIndent(),
        )
        val r = SkillSchemaPrecheck.precheck(body)
        assertEquals("验了 1 条声明", 1, r.checked)
        assertEquals("合法示例不许有 finding：${r.findings}", emptyList<String>(), r.findings)
        assertNull("写前预检要放行", SkillSchemaPrecheck.blockedReason(body))
    }

    @Test
    fun `P2_OpenAI合法示例_非strict通过`() {
        val body = skillBody(
            """
            {"type":"function","function":{"name":"get_weather","description":"查天气",
             "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}
            """.trimIndent(),
        )
        val r = SkillSchemaPrecheck.precheck(body)
        assertEquals(1, r.checked)
        assertEquals("合法示例不许有 finding：${r.findings}", emptyList<String>(), r.findings)
    }

    @Test
    fun `P3_OpenAI合法示例_strict全绿通过`() {
        val body = skillBody(
            """
            {"type":"function","function":{"name":"get_weather","strict":true,
             "parameters":{"type":"object","properties":{"city":{"type":"string"}},
               "required":["city"],"additionalProperties":false}}}
            """.trimIndent(),
        )
        val r = SkillSchemaPrecheck.precheck(body)
        assertEquals("strict 全绿也要放行：${r.findings}", emptyList<String>(), r.findings)
    }

    @Test
    fun `P4_Anthropic合法示例通过`() {
        val body = skillBody(
            """
            {"name":"get_weather","description":"查天气",
             "input_schema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}
            """.trimIndent(),
        )
        val r = SkillSchemaPrecheck.precheck(body)
        assertEquals(1, r.checked)
        assertEquals("合法示例不许有 finding：${r.findings}", emptyList<String>(), r.findings)
    }

    @Test
    fun `P5_普通JSON配置不误伤_不当工具示例验`() {
        // 平台机制技能里常见的世界书配置：不是工具声明，不许 flagged。
        val body = skillBody(
            """
            {"world_book":[{"value":"设定","enable":true,"sort":1}]}
            """.trimIndent(),
        )
        val r = SkillSchemaPrecheck.precheck(body)
        assertEquals("不算工具声明", 0, r.checked)
        assertEquals("不许有 finding：${r.findings}", emptyList<String>(), r.findings)
        assertNull(SkillSchemaPrecheck.blockedReason(body))
    }

    @Test
    fun `P6_没有围栏块的正文零打扰`() {
        val r = SkillSchemaPrecheck.precheck("# 平台填卡\n先看字段清单，再按必填顺序填。")
        assertEquals(0, r.checked)
        assertEquals(emptyList<String>(), r.findings)
    }

    @Test
    fun `P7_请求体包装层也认_tools数组与functionDeclarations`() {
        val body = skillBody(
            """
            {"tools":[{"type":"function","function":{"name":"get_weather","description":"查天气",
              "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}]}
            """.trimIndent(),
        )
        val r = SkillSchemaPrecheck.precheck(body)
        assertEquals("tools 数组里的声明要被验到", 1, r.checked)
        assertEquals(emptyList<String>(), r.findings)

        val geminiBody = skillBody(
            """
            {"functionDeclarations":[{"name":"fill_form","description":"填表",
              "parametersJsonSchema":{"type":"object","properties":{"fields":{"type":"string"}},"required":["fields"]}}]}
            """.trimIndent(),
        )
        val r2 = SkillSchemaPrecheck.precheck(geminiBody)
        assertEquals(1, r2.checked)
        assertEquals(emptyList<String>(), r2.findings)
    }

    // ── 负例（派工单 §3：type 大写／错字段／缺 required／串家） ─────────────

    @Test
    fun `N1_Gemini_type大写_拒_根层与嵌层都抓`() {
        val rootUpper = skillBody(
            """{"name":"fill_form","parametersJsonSchema":{"type":"OBJECT","properties":{"fields":{"type":"string"}},"required":["fields"]}}""",
        )
        val r = SkillSchemaPrecheck.precheck(rootUpper)
        assertEquals(1, r.checked)
        assertTrue("type 大写要拒：${r.findings}", r.findings.any { it.contains("小写") && it.contains("OBJECT") })

        val nestedUpper = skillBody(
            """{"name":"fill_form","parametersJsonSchema":{"type":"object","properties":{"fields":{"type":"STRING"}},"required":["fields"]}}""",
        )
        assertTrue(
            "嵌层的 type 大写也要拒：${SkillSchemaPrecheck.precheck(nestedUpper).findings}",
            SkillSchemaPrecheck.precheck(nestedUpper).findings.any { it.contains("STRING") },
        )
        assertNotNull("拒因要进 blockedReason（拒写的口）", SkillSchemaPrecheck.blockedReason(rootUpper))
    }

    @Test
    fun `N2_Gemini_白名单外关键词_拒`() {
        val body = skillBody(
            """
            {"name":"fill_form","parametersJsonSchema":{"type":"object",
              "properties":{"fields":{"type":"string"}},"required":["fields"],
              "additionalProperties":false}}
            """.trimIndent(),
        )
        val r = SkillSchemaPrecheck.precheck(body)
        assertTrue("additionalProperties 不在 Gemini 白名单：${r.findings}", r.findings.any { it.contains("additionalProperties") })
    }

    @Test
    fun `N3_Gemini_parameters与JsonSchema同挂_按当前API拒`() {
        val body = skillBody(
            """
            {"name":"fill_form","parameters":{"type":"object"},
             "parametersJsonSchema":{"type":"object","properties":{"fields":{"type":"string"}},"required":["fields"]}}
            """.trimIndent(),
        )
        val r = SkillSchemaPrecheck.precheck(body)
        assertTrue("两字段同挂要点名：${r.findings}", r.findings.any { it.contains("只留一个") })
    }

    @Test
    fun `N4_required引用不存在的字段_拒`() {
        val body = skillBody(
            """
            {"name":"get_weather","input_schema":{"type":"object",
              "properties":{"city":{"type":"string"}},"required":["location"]}}
            """.trimIndent(),
        )
        val r = SkillSchemaPrecheck.precheck(body)
        assertTrue("required 悬空要拒：${r.findings}", r.findings.any { it.contains("location") && it.contains("没有对应字段") })
    }

    @Test
    fun `N5_OpenAI_strict缺additionalProperties_false_拒`() {
        val missing = skillBody(
            """
            {"type":"function","function":{"name":"get_weather","strict":true,
             "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}
            """.trimIndent(),
        )
        assertTrue(
            "strict 缺 additionalProperties:false 要拒：${SkillSchemaPrecheck.precheck(missing).findings}",
            SkillSchemaPrecheck.precheck(missing).findings.any { it.contains("additionalProperties") },
        )
        val explicitTrue = skillBody(
            """
            {"type":"function","function":{"name":"get_weather","strict":true,
             "parameters":{"type":"object","properties":{"city":{"type":"string"}},
               "required":["city"],"additionalProperties":true}}}
            """.trimIndent(),
        )
        assertTrue(
            "strict 里 additionalProperties:true 也要拒",
            SkillSchemaPrecheck.precheck(explicitTrue).findings.any { it.contains("additionalProperties") },
        )
    }

    @Test
    fun `N6_OpenAI_strict_required没列全_拒`() {
        val body = skillBody(
            """
            {"type":"function","function":{"name":"get_weather","strict":true,
             "parameters":{"type":"object","properties":{"city":{"type":"string"},"unit":{"type":"string"}},
               "required":["city"],"additionalProperties":false}}}
            """.trimIndent(),
        )
        val r = SkillSchemaPrecheck.precheck(body)
        assertTrue("strict 要求 required 列全：${r.findings}", r.findings.any { it.contains("列全") && it.contains("unit") })
    }

    @Test
    fun `N7_Anthropic_input_schema顶层不是object或缺_拒`() {
        val notObject = skillBody(
            """{"name":"get_weather","input_schema":{"type":"string","properties":{}}}""",
        )
        val r = SkillSchemaPrecheck.precheck(notObject)
        assertTrue(
            "input_schema 顶层要 object：${r.findings}",
            r.findings.any { it.contains("object") },
        )
        val missing = JSONObject("""{"name":"get_weather","description":"查天气"}""")
        val r2 = SkillSchemaPrecheck.checkDeclaration(missing, Family.ANTHROPIC, "t")
        assertTrue(
            "缺 input_schema 要拒：$r2",
            r2.any { it.contains("input_schema") },
        )
    }

    @Test
    fun `N8_各provider不串规则_显式串家校验必拒`() {
        val openai = JSONObject(
            """
            {"type":"function","function":{"name":"get_weather",
             "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}
            """.trimIndent(),
        )
        val anthropic = JSONObject(
            """
            {"name":"get_weather","input_schema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}
            """.trimIndent(),
        )
        val gemini = JSONObject(
            """
            {"name":"fill_form","parametersJsonSchema":{"type":"object","properties":{"fields":{"type":"string"}},"required":["fields"]}}
            """.trimIndent(),
        )
        assertTrue(
            "OpenAI 示例按 Anthropic 校验 ⇒ 拒（没有 input_schema）",
            SkillSchemaPrecheck.checkDeclaration(openai, Family.ANTHROPIC, "t").any { it.contains("input_schema") },
        )
        assertTrue(
            "Anthropic 示例按 Gemini 校验 ⇒ 拒（没有 parametersJsonSchema）",
            SkillSchemaPrecheck.checkDeclaration(anthropic, Family.GEMINI, "t").any { it.contains("parametersJsonSchema") },
        )
        assertTrue(
            "Gemini 示例按 OpenAI 校验 ⇒ 拒（没有 function）",
            SkillSchemaPrecheck.checkDeclaration(gemini, Family.OPENAI, "t").any { it.contains("function") },
        )
    }

    @Test
    fun `N9_工具示例JSON写坏了_报解析失败不放过`() {
        // 少右括号（org.json 容忍尾逗号，拿它当坏例测不出「解析失败」）。
        val body = "# 技能\n\n```json\n{\"name\":\"get_weather\", \"input_schema\": {\"type\": \"object\"}\n```\n"
        val r = SkillSchemaPrecheck.precheck(body)
        assertTrue("坏 JSON 要有声音：${r.findings}", r.findings.any { it.contains("解析失败") })
        assertNotNull(SkillSchemaPrecheck.blockedReason(body))
    }

    @Test
    fun `N10_外壳多余字段按provider拒`() {
        val anthropicExtra = skillBody(
            """
            {"name":"get_weather","input_schema":{"type":"object","properties":{}},
             "parametersJsonSchema":{"type":"object"}}
            """.trimIndent(),
        )
        assertTrue(
            "Anthropic 外壳出现 parametersJsonSchema 要拒：${SkillSchemaPrecheck.precheck(anthropicExtra).findings}",
            SkillSchemaPrecheck.precheck(anthropicExtra).findings.any { it.contains("多余字段") },
        )
        val openaiExtra = skillBody(
            """
            {"type":"function","function":{"name":"get_weather","input_schema":{"type":"object"}}}
            """.trimIndent(),
        )
        assertTrue(
            "OpenAI function 里出现 input_schema 要拒",
            SkillSchemaPrecheck.precheck(openaiExtra).findings.any { it.contains("input_schema") },
        )
    }

    @Test
    fun `N11_name非法_拒`() {
        val chineseName = skillBody(
            """{"name":"查天气","input_schema":{"type":"object","properties":{}}}""",
        )
        assertTrue(
            "中文 name 不合三家口径：${SkillSchemaPrecheck.precheck(chineseName).findings}",
            SkillSchemaPrecheck.precheck(chineseName).findings.any { it.contains("name") },
        )
        val geminiDashFirst = skillBody(
            """{"name":"-fill","parametersJsonSchema":{"type":"object","properties":{}}}""",
        )
        assertTrue(
            "Gemini name 要字母/下划线开头：${SkillSchemaPrecheck.precheck(geminiDashFirst).findings}",
            SkillSchemaPrecheck.precheck(geminiDashFirst).findings.any { it.contains("Gemini 口径") },
        )
    }

    // ── 端到端：预检失败不许写盘（派工单负例「预检失败仍写盘」「错误被吞」） ──

    private fun geminiBadBody(): String = skillBody(
        """{"name":"fill_form","parametersJsonSchema":{"type":"OBJECT","properties":{"fields":{"type":"string"}},"required":["fields"]}}""",
    )

    @Test
    fun `E1_预检失败_plan挡下_apply拒写_盘上不留半条`() {
        val pf = pf()
        val before = pf.read(PersonaLayer.C_PROJECT).orEmpty()
        val plan = SkillStore.plan(proposal(geminiBadBody()), projectRoot, globalRoot, "s")
        assertNotNull("plan 的 blocked 链尾要接住：${plan.blocked}", plan.blocked)
        assertTrue("拒因要说清是 schema 预检：${plan.blocked}", plan.blocked!!.contains("schema 预检"))
        val r = SkillStore.apply(plan, pf, before)
        assertTrue("要拒：$r", r is SkillWriteResult.Rejected)
        assertFalse("不许留下正文", plan.file.exists())
        assertFalse("不许建技能目录", File(projectRoot, SkillRules.SKILL_DIR).exists())
        assertEquals("索引一个字不动", before, pf.read(PersonaLayer.C_PROJECT).orEmpty())
    }

    @Test
    fun `E2_合法示例照常写_不误伤`() {
        val pf = pf()
        val good = skillBody(
            """
            {"name":"fill_form","parametersJsonSchema":{"type":"object","properties":{
              "fields":{"type":"array","items":{"type":"string"}}},"required":["fields"]}}
            """.trimIndent(),
        )
        val plan = SkillStore.plan(proposal(good), projectRoot, globalRoot, "s")
        assertNull("合法要放行：${plan.blocked}", plan.blocked)
        val r = SkillStore.apply(plan, pf, pf.read(PersonaLayer.C_PROJECT).orEmpty())
        assertTrue("照常写成：$r", r is SkillWriteResult.Written)
        assertTrue("正文在盘上", plan.file.isFile)
        assertTrue("索引也写上", (r as SkillWriteResult.Written).indexUpdated)
    }

    @Test
    fun `E3_技能卡_失败显示在blocked_禁用记住它`() {
        val built = SkillCardRules.build(
            SkillCardDraft(name = "平台填卡示例", body = geminiBadBody(), reason = "用户说记住"),
            projectRoot,
            globalRoot,
            "s",
        )
        val view = (built as SkillCardBuild.Ready).view
        assertNotNull("卡上要显示失败原因：${view.blocked}", view.blocked)
        assertTrue("失败原因要含 schema 字样：${view.blocked}", view.blocked!!.contains("schema 预检"))
        assertFalse("不能点记住它", view.canRemember)
        assertNull("失败时不显示「通过」行", view.schemaCheckedCount)
        val saved = SkillCardWriter.save(
            SkillCardDraft(name = "平台填卡示例", body = geminiBadBody(), reason = "用户说记住"),
            projectRoot, globalRoot, pf(), "s",
        )
        assertTrue("保存口同样拒：$saved", saved is SkillWriteResult.Rejected)
        assertFalse("盘上不许留", File(projectRoot, SkillRules.SKILL_DIR).exists())
    }

    @Test
    fun `E4_技能卡_通过显示示例数`() {
        val good = skillBody(
            """
            {"name":"fill_form","parametersJsonSchema":{"type":"object","properties":{"fields":{"type":"string"}},"required":["fields"]}}
            """.trimIndent(),
        )
        val built = SkillCardRules.build(
            SkillCardDraft(name = "平台填卡示例", body = good, reason = "用户说记住"),
            projectRoot,
            globalRoot,
            "s",
        )
        val view = (built as SkillCardBuild.Ready).view
        assertNull("没有阻断", view.blocked)
        assertEquals("卡上要报通过数量", 1, view.schemaCheckedCount)
    }

    // ── 源码门禁：Gemini 白名单与发送侧 keep 集逐字同步 ─────────────────────

    private fun geminiWireSrc(): String = File("src/main/java/com/example/zhengdao/baopu/wire/GeminiWire.kt")
        .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/baopu/wire/GeminiWire.kt") }
        .readText()

    @Test
    fun `G1_Gemini白名单与发送侧sanitizeSchema同步`() {
        val m = Regex("val keep = setOf\\((.*?)\\)", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(geminiWireSrc())
        assertNotNull("GeminiWire 里要还能找到 keep 集（搬家/改名要先改这条门禁）", m)
        val wireKeep = Regex("\"(\\w+)\"").findAll(m!!.groupValues[1]).map { it.groupValues[1] }.toSet()
        val precheckKeep = SkillSchemaPrecheck.GEMINI_SCHEMA_KEYWORDS - setOf("properties", "items")
        assertEquals("两份白名单必须逐字一致（漂了就是第二真相）", wireKeep, precheckKeep)
    }
}
