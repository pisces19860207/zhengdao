// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

// 第一百八十八批 §1.5：sanitizeSchema 白名单由 fixture 驱动（2026-10-24）。
//
// 病灶：Gemini 的 schema 白名单是**手写 + 真机试出来的**——`GeminiWire.sanitizeSchema`
// 里那几段 2026-10-23 真机注释就是考古记录。口头考古的问题：改白名单的人看不到
// 「这个词当初为什么留/为什么扔」，扔错了服务端 400、留错了模型收不到参数表，
// 而且都只在真机上现形。本文件把每条考古结论钉成测试名，白名单由一张表驱动。
//
// 覆盖（与 GeminiWire 注释逐条对应）：
//   ① `parameters`（OpenAPI 风格）模型不认 ⇒ 只发 `parametersJsonSchema`；
//      两种都发会被服务端 400 ⇒ 只发一个；
//   ② 老 `parameters` 要的是 Gemini Schema 枚举 type（STRING/OBJECT…）；
//   ③ `parametersJsonSchema` 要的是标准 JSON Schema ⇒ type 必须小写
//      （发过大写，服务端不报错但模型当作没拿到参数表）；
//   ④ 其余关键词（`$schema`/`additionalProperties`…）Gemini 不吃 ⇒ 400 ⇒ 剥掉；
//   ⑤ 真机症状名（list_dir 回 items、capabilities 回 items）留成回归名。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.wire.GeminiWire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiSchemaWhitelistTest {

    // ── 夹具 ─────────────────────────────────────────────────────────────

    /** OpenAI 形状的一个工具（parameters 即 ToolSpec.inputSchema 的原样）。 */
    private fun oneTool(parameters: JSONObject): org.json.JSONArray = org.json.JSONArray().put(
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "read_file")
                    .put("description", "读文件")
                    .put("parameters", parameters),
            ),
    )

    private fun schemaOf(parameters: JSONObject): JSONObject =
        GeminiWire.toolDecls(oneTool(parameters)).getJSONObject(0).getJSONObject("parametersJsonSchema")

    // ── ① 真机一：parameters 模型不认，只发 parametersJsonSchema ─────────

    @Test
    fun `真机一_parameters是OpenAPI风格模型不认_只发parametersJsonSchema`() {
        val params = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("path", JSONObject().put("type", "string")))
        val decl = GeminiWire.toolDecls(oneTool(params)).getJSONObject(0)
        assertTrue("必须发 parametersJsonSchema", decl.has("parametersJsonSchema"))
        assertFalse("不许再发 parameters（两种都发会被服务端 400）", decl.has("parameters"))
        assertEquals("object", decl.getJSONObject("parametersJsonSchema").getString("type"))
    }

    @Test
    fun `真机一的代价_两种都发服务端400_所以代码里只有一个put`() {
        // 源码级：toolDecls 里只许出现一次 schema put（parametersJsonSchema），
        // 谁要是「两种都带上由服务端选」就会在这里红。
        val f = java.io.File("app/src/main/java/com/example/zhengdao/baopu/wire/GeminiWire.kt")
        val code = (if (f.isFile) f else java.io.File("src/main/java/com/example/zhengdao/baopu/wire/GeminiWire.kt"))
            .readText()
        assertTrue("必须发 parametersJsonSchema", code.contains("d.put(\"parametersJsonSchema\", clean)"))
        assertFalse("不许出现 parameters 的 put（两者同发 ⇒ 服务端 400）", code.contains("d.put(\"parameters\""))
    }

    // ── ② 真机二：老 parameters 要枚举 type；JsonSchema 要小写 ───────────

    @Test
    fun `真机二_老parameters要的是枚举type_新JsonSchema要的是小写`() {
        // 同一条 schema 走两种字段时的历史分野（本批只发 JsonSchema，但这条判据
        // 钉住「type 大小写是字段形状问题，不是随手选的」）：
        // 老 parameters（OpenAPI 风格）⇒ Gemini Schema 枚举 STRING/OBJECT；
        // parametersJsonSchema（标准 JSON Schema）⇒ object/string。
        val params = JSONObject().put("type", "OBJECT")
        assertEquals("JsonSchema 路线必须小写", "object", schemaOf(params).getString("type"))
        val nested = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("count", JSONObject().put("type", "INTEGER")))
        assertEquals(
            "嵌套属性同样小写",
            "integer",
            schemaOf(nested).getJSONObject("properties").getJSONObject("count").getString("type"),
        )
    }

    @Test
    fun `真机二连_JsonSchema发大写type服务端不报错但模型当没拿到_必须小写`() {
        // 2026-10-23 真机二连：发 OBJECT/STRING 时服务端**不报错**（静默！），
        // 模型当作没拿到参数表。所以这条判据不能靠「不抛异常」兜底，必须钉输出。
        val params = JSONObject()
            .put("type", "OBJECT")
            .put("properties", JSONObject().put("path", JSONObject().put("type", "STRING")))
        val out = schemaOf(params)
        assertEquals("object", out.getString("type"))
        assertEquals("string", out.getJSONObject("properties").getJSONObject("path").getString("type"))
        // 输出里不许残留任何大写枚举名
        assertFalse(out.toString().contains("OBJECT"))
        assertFalse(out.toString().contains("STRING"))
    }

    @Test
    fun `真机二连之症状_list_dir回items与capabilities回items_钉住不复发`() {
        // 真机表现（日志 20:05 复现）：模型自己猜 keys——list_dir 回 {"items":[…]}、
        // capabilities 回 items。症状的根因是参数表没送到/形状不对；这里用这两个
        // 工具的真实 schema 形状做回归：capabilities 的 enable 是字符串数组。
        val params = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject().put(
                    "enable",
                    JSONObject()
                        .put("type", "array")
                        .put("items", JSONObject().put("type", "string"))
                        .put("description", "要启用的工具组 key"),
                ),
            )
            .put("required", org.json.JSONArray().put("enable"))
        val out = schemaOf(params)
        val enable = out.getJSONObject("properties").getJSONObject("enable")
        assertEquals("array", enable.getString("type"))
        assertEquals("string", enable.getJSONObject("items").getString("type"))
        assertEquals("要启用的工具组 key", enable.getString("description"))
        assertEquals(1, out.getJSONArray("required").length())
    }

    // ── ④ 白名单 fixture 驱动：每个关键词一条判据 ──────────────────────

    @Test
    fun `白名单保留表_九类关键词原样过_枚举数组都留`() {
        val params = JSONObject()
            .put("type", "string")
            .put("format", "date-time")
            .put("description", "路径")
            .put("nullable", true)
            .put("enum", org.json.JSONArray().put("a").put("b"))
            .put("minItems", 1)
            .put("maxItems", 9)
            .put("title", "标题")
        val out = GeminiWire.sanitizeSchema(params)
        for (k in listOf(
            "type", "format", "description", "nullable", "enum",
            "minItems", "maxItems", "title",
        )) {
            assertTrue("白名单关键词 $k 必须保留", out.has(k))
        }
        assertEquals("date-time", out.getString("format"))
        assertEquals(2, out.getJSONArray("enum").length())
        assertEquals(9, out.getInt("maxItems"))
        // required 由外层 schema 带（这里单独钉）
        val withRequired = JSONObject().put("type", "object")
            .put("required", org.json.JSONArray().put("path"))
        assertTrue("required 必须保留", GeminiWire.sanitizeSchema(withRequired).has("required"))
    }

    @Test
    fun `白名单剥掉表_其余关键词Gemini不吃会400_一个不留`() {
        // 每家（这里是 Gemini）会丢的关键词表：丢了会怎样 = 服务端 400。
        // （`\$schema` 的 $ 必须转义——不然 Kotlin 把它当字符串模板）
        val dropped = listOf(
            "\$schema", "additionalProperties", "examples", "default",
            "readOnly", "writeOnly", "deprecated", "pattern",
        )
        for (k in dropped) {
            val params = JSONObject().put("type", "string").put(k, "x")
            val out = GeminiWire.sanitizeSchema(params)
            assertTrue("$k 必须被剥掉（Gemini 不吃，发了 400）", out.has("type"))
            assertFalse("$k 不该出现在输出里", out.has(k))
        }
    }

    @Test
    fun `递归_properties与items都过白名单_嵌套一样剥`() {
        val params = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put(
                        "tags",
                        JSONObject()
                            .put("type", "ARRAY")
                            .put("items", JSONObject()
                                .put("type", "string")
                                .put("pattern", "^x")),
                    ),
            )
        val out = GeminiWire.sanitizeSchema(params)
        assertFalse("外层 additionalProperties 剥掉", out.has("additionalProperties"))
        val tags = out.getJSONObject("properties").getJSONObject("tags")
        assertEquals("array", tags.getString("type"))
        val items = tags.getJSONObject("items")
        assertEquals("string", items.getString("type"))
        assertFalse("嵌套里的 pattern 也剥掉", items.has("pattern"))
    }

    // ── 边界（fixture 驱动：坏了不抛、也不悄悄放行）─────────────────────

    @Test
    fun `边界_properties不是对象时整个丢弃不抛`() {
        val params = JSONObject().put("type", "object").put("properties", "不是对象")
        val out = GeminiWire.sanitizeSchema(params)
        assertFalse("非对象 properties 整个丢（不抛）", out.has("properties"))
        assertTrue("type 照旧", out.has("type"))
    }

    @Test
    fun `边界_items不是对象时丢弃_type缺失则不造空type键`() {
        val badItems = JSONObject().put("type", "array").put("items", "不是对象")
        assertFalse(GeminiWire.sanitizeSchema(badItems).has("items"))
        // type 缺失 ⇒ 循环根本见不到这个键 ⇒ 不造 "type":"" 空键
        val noType = JSONObject().put("description", "只有描述")
        assertFalse("不许造空 type 键", GeminiWire.sanitizeSchema(noType).has("type"))
        assertEquals("只有描述", GeminiWire.sanitizeSchema(noType).getString("description"))
    }

    @Test
    fun `边界_空schema过一遍还是空_不会半截`() {
        assertEquals(0, GeminiWire.sanitizeSchema(JSONObject()).length())
    }

    // ── 负例：白名单被改坏 ⇒ 红 ────────────────────────────────────────

    @Test
    fun `负例_把additionalProperties加回白名单_本用例就红`() {
        // 负例演练：白名单表 keep 里多一个 additionalProperties，上面的剥掉表
        // 立刻抓到。这里直接钉住 keep 表的现状（九项＋两个递归键），改动要显式。
        val f = java.io.File("app/src/main/java/com/example/zhengdao/baopu/wire/GeminiWire.kt")
        val code = (if (f.isFile) f else java.io.File("src/main/java/com/example/zhengdao/baopu/wire/GeminiWire.kt"))
            .readText()
        val keepBlock = code.substringAfter("val keep = setOf(").substringBefore(")")
        for (k in listOf(
            "\"type\"", "\"format\"", "\"description\"", "\"nullable\"", "\"enum\"",
            "\"required\"", "\"minItems\"", "\"maxItems\"", "\"title\"",
        )) {
            assertTrue("keep 表里少了 $k（加/减白名单必须改测试，不许悄悄进行）", keepBlock.contains(k))
        }
        assertFalse("keep 表里不许出现 additionalProperties", keepBlock.contains("additionalProperties"))
        assertFalse("keep 表里不许出现 \$schema", keepBlock.contains("\"\$schema\""))
    }
}
