// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 599 段D 判据（2026-11-12）：四件齐全夹具认/导/校全过；模板引用不存在字段 ⇒ 报错；
// 开关缺失或 false ⇒ 导出不带皮肤并提示；不联网。
// 夹具全部**运行时合成**（结构仿实测、内容是占位词），全内存零临时目录；真件内容零进仓。
package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FengyueVarsTest {

    // ── 夹具：结构仿实测面板（示例值实例骨架），内容全占位 ─────────────────

    private fun panelSkeleton(): JSONObject = JSONObject()
        .put("时间", "第一天·清晨")
        .put("地点", "示例城")
        .put(
            "玩家",
            JSONObject()
                .put("姓名", "示例甲")
                .put("境界", "练气")
                .put("数值", JSONObject().put("气血", 80).put("真元", 60))
                .put(
                    "当前效果",
                    JSONArray().put(
                        JSONObject().put("type", "buff").put("名称", "示例效果").put("数值", 5).put("说明", "示例说明"),
                    ),
                )
                .put("特质", JSONArray().put("示例特质"))
                .put("已习功法", JSONArray())
                .put("随身之物", JSONArray()),
        )
        .put("道侣", JSONArray())
        .put("在场NPC", JSONArray().put(JSONObject().put("姓名", "示例乙").put("态度", "中立")))
        .put("日志", JSONObject().put("最新事件", "示例事件").put("下一步提示", "示例提示"))

    /** 实测 Handlebars 子集全覆盖：if/unless/each/with/else、.length、this.x、(eq …)、(gt … 字面量)。 */
    private fun subsetTemplate(): String = """
        {{时间}} · {{地点}}
        {{#if 玩家.当前效果.length}}
          {{#each 玩家.当前效果}}
            {{this.名称}} {{this.数值}} · {{this.说明}}
            {{#if (eq this.type "debuff")}}坏{{else}}好{{/if}}
          {{/each}}
        {{else}}无{{/if}}
        {{#each 玩家.特质}}{{this}}、{{/each}}
        {{#unless 玩家.已习功法.length}}无{{/unless}}
        {{#if 道侣.length}}
          {{#each 道侣}}{{this.姓名}} {{this.羁绊值}}{{#if this.心结}}结{{/if}}{{#each this.已知性癖}}{{this}}{{/each}}{{/each}}
        {{else}}无{{/if}}
        {{#each 在场NPC}}{{this.姓名}}（{{this.态度}}）{{/each}}
        {{日志.最新事件}} {{日志.下一步提示}}
        {{#with 玩家}}{{姓名}} · {{境界}}{{/with}}
        {{#if (gt 玩家.数值.气血 50)}}气血足{{/if}}
    """.trimIndent()

    private fun fiveFieldState(): JSONObject = JSONObject()
        .put("境界", "练气")
        .put("好感", 30)
        .put("伤势", "无")
        .put("持有物", JSONArray().put("示例剑").put("示例丹"))
        .put("日期", "第一天")

    private fun varsError(block: () -> Unit): FengyueVars.VarsError {
        val err = runCatching(block).exceptionOrNull()!!
        assertTrue("应是 VarsError，实为 ${err.javaClass.simpleName}: ${err.message}", err is FengyueVars.VarsError)
        return err as FengyueVars.VarsError
    }

    // ── 判据①：认 ────────────────────────────────────────────────────────

    @Test
    fun `detect_reports_panel_when_four_pieces_present`() {
        val card = JSONObject().put(
            "model_config",
            JSONObject()
                .put("ai_variable_json", "{}")
                .put("ai_variable_template", "<div></div>")
                .put("ai_variable_prompt", "示例指令")
                .put("extend", JSONObject().put("ai_variable_enabled", true)),
        )
        val d = FengyueVars.detect(card)
        assertTrue(d.usesPanel)
        assertTrue(d.hasJson && d.hasTemplate && d.hasPrompt)
        assertEquals(true, d.enabled)
        assertTrue(d.message().contains("这张卡用了变量面板"))
        assertTrue(d.message().contains("ai_variable_json"))
    }

    @Test
    fun `detect_no_panel_and_missing_switch`() {
        assertFalse(FengyueVars.detect(JSONObject()).usesPanel)
        assertFalse(FengyueVars.detect(JSONObject().put("model_config", JSONObject().put("别的", 1))).usesPanel)
        val noSwitch = JSONObject().put(
            "model_config",
            JSONObject().put("ai_variable_json", "{}").put("ai_variable_template", "t").put("ai_variable_prompt", "p"),
        )
        val d = FengyueVars.detect(noSwitch)
        assertTrue(d.usesPanel)
        assertEquals(null, d.enabled)
        assertTrue(d.message().contains("未写"))
        val off = JSONObject().put(
            "model_config",
            JSONObject().put("ai_variable_json", "{}").put("extend", JSONObject().put("ai_variable_enabled", false)),
        )
        assertTrue(FengyueVars.detect(off).message().contains("关"))
    }

    // ── 判据②：导（母档状态字段 → 骨架草稿） ──────────────────────────────

    @Test
    fun `draft_generates_skeleton_template_prompt_from_state_fields`() {
        val d = FengyueVars.draft(fiveFieldState())
        val skeleton = JSONObject(d.skeletonJson)
        assertEquals("练气", skeleton.getString("境界"))
        assertEquals(30, skeleton.getInt("好感"))
        assertEquals("无", skeleton.getString("伤势"))
        assertEquals(2, skeleton.getJSONArray("持有物").length())
        assertEquals("第一天", skeleton.getString("日期"))
        for (key in listOf("境界", "好感", "伤势", "日期")) {
            assertTrue("皮肤缺字段 $key", d.template.contains("{{$key}}"))
        }
        assertTrue("数组字段给 each 草稿", d.template.contains("{{#each 持有物}}"))
        assertTrue(d.template.contains("<style>"))
        assertTrue(d.template.contains(".fy-vars"))
        assertFalse("shadow DOM 里 :root 失效，默认皮肤不许用", d.template.contains(":root"))
        assertTrue(d.prompt.isNotBlank())
        FengyueVars.validate(d.skeletonJson, d.template)
    }

    @Test
    fun `draft_template_row_order_follows_skeleton_text_order`() {
        val d = FengyueVars.draft(fiveFieldState())
        val keys = CardJson.topLevelKeys(d.skeletonJson, 100)
        assertEquals(5, keys.size)
        val positions = keys.map { d.template.indexOf("{{$it}}").takeIf { p -> p >= 0 } ?: d.template.indexOf("{{#each $it}}") }
        assertTrue("模板行序必须与骨架文本键序一致（JVM/真机同一结果）", positions == positions.sorted())
        assertTrue(positions.all { it >= 0 })
    }

    // ── 判据③：校（引用不存在字段 ⇒ 报错） ────────────────────────────────

    @Test
    fun `validate_unknown_field_reports_with_field_name`() {
        val err = varsError { FengyueVars.validate("""{"甲":1}""", "{{乙}}") }
        assertTrue(err.message!!.contains("乙"))
        val nested = varsError { FengyueVars.validate("""{"玩家":{"数值":{}}}""", "{{玩家.数值.气血}}") }
        assertTrue(nested.message!!.contains("玩家.数值.气血"))
    }

    @Test
    fun `validate_real_world_subset_passes`() {
        FengyueVars.validate(panelSkeleton().toString(), subsetTemplate())
    }

    @Test
    fun `validate_empty_array_this_refs_are_skipped_not_guessed`() {
        FengyueVars.validate("""{"道侣":[]}""", "{{#each 道侣}}{{this.姓名}} {{this.羁绊值}}{{/each}}")
    }

    @Test
    fun `validate_sample_element_missing_key_reports`() {
        val err = varsError {
            FengyueVars.validate("""{"效果":[{"名称":"示例"}]}""", "{{#each 效果}}{{this.不存在}}{{/each}}")
        }
        assertTrue(err.message!!.contains("this.不存在"))
    }

    @Test
    fun `validate_with_rebases_bare_paths_each_does_not`() {
        FengyueVars.validate("""{"玩家":{"姓名":"甲"}}""", "{{#with 玩家}}{{姓名}}{{/with}}")
        varsError { FengyueVars.validate("""{"玩家":{"姓名":"甲"}}""", "{{#with 玩家}}{{不存在}}{{/with}}") }
        // with 弹出后裸路径回根：根上没有「姓名」⇒ 报错
        varsError { FengyueVars.validate("""{"玩家":{"姓名":"甲"}}""", "{{#with 玩家}}{{姓名}}{{/with}}{{姓名}}") }
        // each 不改裸路径作用域：根键在 each 内仍可查；元素键裸写 ⇒ 报错
        FengyueVars.validate("""{"列表":[{"x":1}],"根键":1}""", "{{#each 列表}}{{根键}}{{/each}}")
        varsError { FengyueVars.validate("""{"列表":[{"x":1}],"根键":1}""", "{{#each 列表}}{{x}}{{/each}}") }
    }

    @Test
    fun `validate_literals_and_framework_builtins_are_not_fields`() {
        FengyueVars.validate(
            """{"数值":1,"列表":[]}""",
            "{{#if (gt 数值 50)}}足{{/if}}{{#each 列表}}{{@index}}{{/each}}{{#if true}}恒{{/if}}",
        )
    }

    @Test
    fun `validate_skeleton_must_be_json_object`() {
        for (bad in listOf("[1,2]", "坏文本", "", "\"字符串\"")) {
            val err = varsError { FengyueVars.validate(bad, "{{甲}}") }
            assertTrue(err.message!!.contains("对象"))
        }
    }

    // ── 判据④：开关默认 false（没皮肤就不开） ─────────────────────────────

    @Test
    fun `export_switch_missing_or_false_omits_skin_with_notice`() {
        for (switch in listOf(null, false)) {
            val ex = FengyueVars.export("""{"甲":1}""", "<div>{{甲}}</div>", "示例指令", switch)
            val mc = ex.modelConfig
            assertTrue(mc.has("ai_variable_json"))
            assertTrue(mc.has("ai_variable_prompt"))
            assertFalse("不开就不写皮肤", mc.has("ai_variable_template"))
            assertFalse(mc.getJSONObject("extend").getBoolean("ai_variable_enabled"))
            assertTrue("要给人话提示", ex.notices.any { it.contains("要开面板请给皮肤") })
        }
    }

    @Test
    fun `export_true_with_skin_writes_all_four_pieces`() {
        val ex = FengyueVars.export("""{"甲":1}""", "<div>{{甲}}</div>", "示例指令", true)
        val mc = ex.modelConfig
        assertTrue(mc.has("ai_variable_json") && mc.has("ai_variable_template") && mc.has("ai_variable_prompt"))
        assertTrue(mc.getJSONObject("extend").getBoolean("ai_variable_enabled"))
        assertTrue(ex.notices.isEmpty())
    }

    @Test
    fun `export_true_without_skin_downgrades_to_false_with_notice`() {
        for (skin in listOf(null, "   ")) {
            val ex = FengyueVars.export("""{"甲":1}""", skin, "示例指令", true)
            assertFalse(ex.modelConfig.getJSONObject("extend").getBoolean("ai_variable_enabled"))
            assertFalse(ex.modelConfig.has("ai_variable_template"))
            assertTrue(ex.notices.any { it.contains("要开面板请给皮肤") })
        }
    }

    @Test
    fun `export_validates_refs_even_when_switch_off`() {
        val err = varsError { FengyueVars.export("""{"甲":1}""", "{{不存在}}", "p", false) }
        assertTrue(err.message!!.contains("不存在"))
    }

    @Test
    fun `export_rejects_non_object_skeleton_regardless_of_switch`() {
        varsError { FengyueVars.export("[1,2]", null, "p", null) }
    }

    // ── 全链：导 → 导出 → 认 ─────────────────────────────────────────────

    @Test
    fun `draft_export_detect_full_chain`() {
        val d = FengyueVars.draft(fiveFieldState())
        val ex = FengyueVars.export(d.skeletonJson, d.template, d.prompt, true)
        assertEquals("骨架原文进 ai_variable_json，不二次改写", d.skeletonJson, ex.modelConfig.getString("ai_variable_json"))
        val card = JSONObject().put("model_config", ex.modelConfig)
        val det = FengyueVars.detect(card)
        assertTrue(det.usesPanel)
        assertTrue(det.hasJson && det.hasTemplate && det.hasPrompt)
        assertEquals(true, det.enabled)
        assertTrue(det.message().contains("这张卡用了变量面板"))
    }
}