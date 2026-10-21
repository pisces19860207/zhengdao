// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第一百一十九批 §2「测试」——
// - 每个工具至少：合法参数通过、缺必填拒绝、类型错拒绝、未知字段拒绝；
// - schema 单一来源逐字节比对（同 94 批，但这次覆盖**全部** 31 个工具、**两个**协议）；
// - 负例：摘掉 required ⇒ 缺必填被放行；造第二套 schema ⇒ 单一来源会红；
// - 也不许把「合法参数照常执行」改坏（golden：本轮动过的六个工具逐值比对）。
//
// 与 94 批 [ToolSchemaContractTest] 的分工（两处都留着，别合并）：
// - 94 批钉的是**四个核心文件工具**的「不执行 + 原文回灌」全链路（走 GuardPipeline，
//   真执行、真落盘、taps 计数）；它证明「非法参数一次都不执行」；
// - 本文件钉的是**全部工具的 schema 本身**：每个工具都说得出「哪个参数、错在哪」，
//   且发出去的那一份与校验用的那一份是同一个对象。执行闸只有一条链
//   （GuardPipeline 先 [ToolRegistry.validateCall]），所以「不执行」不在本文件重复验。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PersonaFiles
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolSchemaAllTest {

    private val roots = mutableListOf<File>()

    /** 全局可变接线点（ask_user / update_plan 的 sink）用完必须还原，别污染同 JVM 的别的用例。 */
    @After
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
        AskUserTool.sink = null
        PlanTool.sink = null
    }

    // ── ① 全量：31 个工具一条不漏，S1–S12 全过 ─────────────────────────────

    @Test
    fun `全部工具_schema_契约齐全_一个不漏`() {
        val reg = registryOf(newRoot())
        // 名册三方相等：分组表（唯一一张）＝ 注册表实到 ＝ 本文件的样本表。
        // 新增工具忘了同步样本表 ⇒ 这里先红，不必等某条用例莫名少跑一个工具。
        assertEquals(
            "注册表里的工具与 ToolGroupTable 不一致（新工具要先登记分组）",
            ToolGroupTable.GROUP_OF.keys,
            reg.names().toSet(),
        )
        assertEquals(
            "本文件的样本表与真实注册表不一致：新增/删除工具要同步 REQUIRED_ONLY",
            reg.names().toSet(),
            REQUIRED_ONLY.keys,
        )
        val bad = reg.schemaIssues()
        assertTrue(
            "这些工具的 schema 不合规（规则代号见 ToolSchemaRules）：\n" +
                bad.entries.joinToString("\n") { (name, list) -> "  $name → $list" },
            bad.isEmpty(),
        )
    }

    @Test
    fun `白名单不许过期_也不许漏登记`() {
        val reg = registryOf(newRoot())
        val names = reg.names().toSet()
        assertTrue(
            "REQUIRED_EMPTY_OK 里有根本没注册的工具（过期条目）：${ToolSchemaRules.REQUIRED_EMPTY_OK.keys - names}",
            names.containsAll(ToolSchemaRules.REQUIRED_EMPTY_OK.keys),
        )
        assertTrue(
            "ADDITIONAL_OPEN_OK 里有根本没注册的工具（过期条目）：${ToolSchemaRules.ADDITIONAL_OPEN_OK.keys - names}",
            names.containsAll(ToolSchemaRules.ADDITIONAL_OPEN_OK.keys),
        )
        // 反向：真正空 required 的工具必须都在白名单里（漏登记由 audit 的 S5 报，
        // 这里再从注册表侧核一遍，两处都红才算真漏）
        val emptyRequired = reg.all().filter { it.inputSchema.optJSONArray("required")?.length() == 0 }
            .map { it.name }.toSet()
        assertEquals(
            "空 required 的实到集合与白名单不一致",
            emptyRequired,
            ToolSchemaRules.REQUIRED_EMPTY_OK.keys.filter { it in names }.toSet(),
        )
    }

    // ── ② 每个工具 × 四类用例 ─────────────────────────────────────────────

    @Test
    fun `合法参数_全部工具都放行_含全部可选字段`() {
        val reg = registryOf(newRoot())
        for (name in reg.names()) {
            val schema = reg.all().first { it.name == name }.inputSchema
            // (a) 只填必填
            assertNull(
                "$name 的最小合法参数被拒了：${reg.validateCall(name, REQUIRED_ONLY.getValue(name))}",
                reg.validateCall(name, REQUIRED_ONLY.getValue(name)),
            )
            // (b) 把 schema 里**列出来的每个可选字段**都填上（模型全填时不许被自己的 schema 拒）
            val full = fullArgs(name, schema)
            assertNull(
                "$name 把 properties 全填上反而被拒了（schema 承认的参数必须能收）：${reg.validateCall(name, full)}",
                reg.validateCall(name, full),
            )
        }
    }

    @Test
    fun `缺必填_每个工具都指名道姓`() {
        val reg = registryOf(newRoot())
        var checked = 0
        for (spec in reg.all()) {
            val required = spec.inputSchema.optJSONArray("required") ?: continue
            if (required.length() == 0) continue
            val key = required.optString(0)
            val args = copyOf(REQUIRED_ONLY.getValue(spec.name)).also { it.remove(key) }
            val err = reg.validateCall(spec.name, args)
            assertNotNull("${spec.name} 少了必填「$key」却放行了", err)
            assertTrue("${spec.name} 的错误原文没点名「$key」：$err", err!!.contains(key))
            checked++
        }
        // 31 个工具里 24 个有必填（其余 7 个是白名单里的全可选）
        assertTrue("这条用例一个工具都没验到（判据空了）", checked >= 20)
    }

    @Test
    fun `类型错_每个工具都指出期望与实到`() {
        val reg = registryOf(newRoot())
        for (spec in reg.all()) {
            val props = spec.inputSchema.optJSONObject("properties") ?: continue
            val key = props.names()?.optString(0) ?: continue
            val def = props.optJSONObject(key) ?: continue
            val args = fullArgs(spec.name, spec.inputSchema).put(key, wrongValue(def.optString("type")))
            val err = reg.validateCall(spec.name, args)
            assertNotNull("${spec.name}.$key 类型错（${def.optString("type")}）却放行了", err)
            assertTrue("${spec.name} 的错误原文没点名「$key」：$err", err!!.contains(key))
        }
    }

    @Test
    fun `未知字段_除白名单外一律拒绝并列出可用参数`() {
        val reg = registryOf(newRoot())
        var checked = 0
        for (spec in reg.all()) {
            if (spec.name in ToolSchemaRules.ADDITIONAL_OPEN_OK) continue
            val args = copyOf(REQUIRED_ONLY.getValue(spec.name)).put("zd_probe_unknown", 1)
            val err = reg.validateCall(spec.name, args)
            assertNotNull("${spec.name} 收到未知字段却放行了（幻觉参数会被静默接受）", err)
            assertTrue("${spec.name} 的错误原文没点名未知字段：$err", err!!.contains("zd_probe_unknown"))
            assertTrue("${spec.name} 的错误原文没列可用参数：$err", err!!.contains("可用参数"))
            checked++
        }
        assertTrue("未知字段这条一个工具都没验到", checked >= 25)
    }

    // ── ③ 单一来源：两个协议、全部工具、逐字节 ────────────────────────────

    @Test
    fun `两个协议发出去的schema_与注册表校验用的是同一份`() {
        val reg = registryOf(newRoot())
        val openAi = HashMap<String, String>()
        val sent = reg.toolsJsonOpenAi(SESSION)
        for (i in 0 until sent.length()) {
            val fn = sent.getJSONObject(i).getJSONObject("function")
            openAi[fn.getString("name")] = fn.getJSONObject("parameters").toString()
        }
        val anthropic = HashMap<String, String>()
        val sentA = reg.toolsJsonAnthropic(SESSION)
        for (i in 0 until sentA.length()) {
            val obj = sentA.getJSONObject(i)
            anthropic[obj.getString("name")] = obj.getJSONObject("input_schema").toString()
        }
        assertEquals("tools 数组没发全（有工具看不见）", reg.names().toSet(), openAi.keys)
        assertEquals("anthropic 的 tools 数组没发全", reg.names().toSet(), anthropic.keys)
        for (spec in reg.all()) {
            val local = spec.inputSchema.toString()
            assertEquals("${spec.name}（openai）发出去的不是校验用的那一份 ⇒ 模型按 A 写、校验按 B 判", local, openAi[spec.name])
            assertEquals("${spec.name}（anthropic）发出去的不是校验用的那一份", local, anthropic[spec.name])
        }
    }

    // ── ④ golden：本轮动过 schema 的工具，合法参数照旧执行 ────────────────

    @Test
    fun `golden_update_plan_合法参数原样进落点`() {
        val root = newRoot()
        val reg = registryOf(root)
        var seen: JSONObject? = null
        PlanTool.sink = object : PlanSink {
            override fun submit(args: JSONObject): ToolOutcome {
                seen = JSONObject(args.toString())
                return ToolOutcome.Ok("计划已更新：1 条")
            }
        }
        val args = JSONObject()
            .put("items", JSONArray().put(JSONObject().put("text", "写第十节").put("status", "doing")))
        val out = reg.find("update_plan")!!.executor(args)
        assertEquals("合法参数必须照旧执行成功", "计划已更新：1 条", (out as ToolOutcome.Ok).text)
        assertEquals(
            "参数被改过（schema 的改动不许动合法参数的行为）",
            args.toString(),
            seen!!.toString(),
        )
    }

    @Test
    fun `golden_ask_user_合法参数原样变成一张卡`() {
        val root = newRoot()
        val reg = registryOf(root)
        var card: AskUserRequest? = null
        AskUserTool.sink = { req ->
            card = req
            req.options.first()
        }
        val args = JSONObject()
            .put("question", "这两条要删吗？")
            .put(
                "options",
                JSONArray()
                    .put(JSONObject().put("id", "keep").put("label", "都留着"))
                    .put(JSONObject().put("id", "big").put("label", "删大的").put("recommended", true)),
            )
        val out = reg.find("ask_user")!!.executor(args)
        assertTrue("合法参数必须照旧弹卡并回灌答案：$out", out is ToolOutcome.Ok)
        assertEquals("问题要原样传下去", "这两条要删吗？", card!!.question)
        assertEquals("推荐项必须排第一（用户 2026-10-19 口径）", "big", card!!.options.first().id)
        assertEquals("用户点了哪个就回哪个", "用户选了：删大的", (out as ToolOutcome.Ok).text)
    }

    @Test
    fun `golden_capabilities_不带参数就念名册`() {
        val root = newRoot()
        val reg = registryOf(root)
        val out = reg.find("capabilities")!!.executor(JSONObject())
        val text = (out as ToolOutcome.Ok).text
        assertTrue("名册必须列出当前可见工具：$text", text.contains("当前可见工具"))
        assertTrue("名册必须列组：$text", text.contains("core"))
    }

    @Test
    fun `golden_library_list_合法参数照旧列清单`() {
        val root = newRoot()
        File(root, "资料库/投递/武侠").mkdirs()
        File(root, "资料库/投递/武侠/书.txt").writeText("内容", Charsets.UTF_8)
        val reg = registryOf(root)
        val out = reg.find("library_list")!!.executor(JSONObject().put("dir", "deliver"))
        val text = (out as ToolOutcome.Ok).text
        assertTrue("清单里要有那个文件：$text", text.contains("书.txt"))
    }

    @Test
    fun `golden_write_skill_合法参数照旧写盘`() {
        val root = newRoot()
        val reg = registryOf(root)
        val out = reg.find("write_skill")!!.executor(
            JSONObject().put("name", "试样").put("scope", "project").put("body", "# 试样\n"),
        )
        assertTrue("合法参数必须照旧写成功：$out", out is ToolOutcome.Ok)
        assertTrue("SKILL.md 没落盘", File(root, "技能/试样/SKILL.md").exists())
    }

    // ── ⑤ 负例：证明上面那批断言不是空转 ───────────────────────────────────

    @Test
    fun `负例_摘掉required_缺必填就会被放行`() {
        val reg = registryOf(newRoot(), stripRequired = true)
        val err = reg.validateCall("write_file", JSONObject().put("path", "x.md"))
        assertNull(
            "摘掉 required 之后缺 content 仍被拒 ⇒ 拦住它的不是 required 本身，" +
                "本文件「缺必填_指名道姓」那条是假绿",
            err,
        )
    }

    @Test
    fun `负例_放开additionalProperties_未知字段就会被放行`() {
        val reg = registryOf(newRoot(), allowAdditional = true)
        val err = reg.validateCall("read_file", JSONObject().put("path", "a.md").put("zd_probe_unknown", 1))
        assertNull("放开 additionalProperties 之后未知字段仍被拒 ⇒ 两条断言的靶心不是同一个开关", err)
    }

    @Test
    fun `负例_造第二套schema_单一来源比对会红`() {
        val root = newRoot()
        val reg = registryOf(root)
        val shipped = reg.find("read_file")!!.inputSchema
        // 注册表里换一份（等于「发出去的」与「工厂里的」变成两套）
        val second = JSONObject(shipped.toString()).put("zd_second_source", true)
        reg.register(reg.find("read_file")!!.let { it.copy(inputSchema = second) })
        val fromFactory = BaopuToolCatalog.ENTRIES.first { it.name == "read_file" }.factory { root }.inputSchema
        assertNotEquals(
            "注册表被换成第二套 schema 之后仍然相等 ⇒ 单一来源比对根本没在比两个来源",
            fromFactory.toString(),
            reg.find("read_file")!!.inputSchema.toString(),
        )
    }

    @Test
    fun `负例_缺schema的工具_体检账里必须留下名字`() {
        val reg = registryOf(newRoot())
        reg.register(
            ToolSpec(
                name = "zd_no_schema",
                description = "故意不合规：没有 properties/required/additionalProperties",
                inputSchema = jsonObjectOf("type" to "object"),
            ) { ToolOutcome.Ok("x") },
        )
        val issues = reg.schemaIssues()["zd_no_schema"]
        assertNotNull("缺 schema 的工具没被记账 ⇒ 门禁是空转", issues)
        assertTrue("记账里要点出 required：$issues", issues!!.any { it.contains(ToolSchemaRules.S4_NO_REQUIRED) })
        assertTrue("记账里要点出 additionalProperties：$issues", issues.any { it.contains(ToolSchemaRules.S7_ADDITIONAL_OPEN) })
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    /**
     * 真注册表（不是手搓假件）：[BaopuToolCatalog] 27 条 + 运行时那 4 条，一个不少。
     *
     * 与 94 批的 `registryOf` 做两处同样的**测试专用**改动：解锁全部组（否则 `find` 回的是
     * 「组未解锁」拒绝桩）；CONFIRM 降级为 FREE（确认卡是线上交互，JVM 里没人点）。
     */
    private fun registryOf(
        root: File,
        stripRequired: Boolean = false,
        allowAdditional: Boolean = false,
    ): ToolRegistry {
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        // 装配层那四条（第一百一十九批起换成 JVM 够得着的工厂，见 CompatChannelTools）
        reg.register(SkillTools.spec(workspaceRoot = { root }, personaFiles = { PersonaFiles(File(root, "global"), root) }))
        reg.register(AppendPersonaTool.spec(personaFiles = { PersonaFiles(File(root, "global"), root) }))
        reg.register(CompatChannelTools.readSpec(readFileEnabled = true))
        reg.register(CompatChannelTools.bashSpec())
        reg.currentSessionId = SESSION
        reg.unlockStore().unlock(SESSION, ToolGroup.values().toList())
        // 契约测试只看 schema，不看确认流程 ⇒ 统一降成 FREE，省掉「没人点卡」的噪声
        for (name in reg.names().toList()) {
            val spec = reg.find(name) ?: continue
            val schema = if (stripRequired || allowAdditional) mutate(spec.inputSchema, stripRequired, allowAdditional) else spec.inputSchema
            reg.register(spec.copy(inputSchema = schema, permission = ToolPermission.FREE))
        }
        return reg
    }

    private fun mutate(schema: JSONObject, stripRequired: Boolean, allowAdditional: Boolean): JSONObject {
        val out = JSONObject()
        val names = schema.names() ?: return out
        for (i in 0 until names.length()) {
            val key = names.optString(i)
            if (stripRequired && key == "required") continue
            if (allowAdditional && key == "additionalProperties") continue
            out.put(key, schema.get(key))
        }
        if (allowAdditional) out.put("additionalProperties", true)
        return out
    }

    /** 按 schema 自己列的 properties 造一份「全填」的合法参数（值按 type/enum/minimum 造）。 */
    private fun fullArgs(name: String, schema: JSONObject): JSONObject {
        val props = schema.optJSONObject("properties") ?: return JSONObject()
        val out = JSONObject()
        val keys = props.names() ?: return out
        for (i in 0 until keys.length()) {
            val key = keys.optString(i)
            val def = props.optJSONObject(key) ?: continue
            out.put(key, sampleValue(name, key, def))
        }
        return out
    }

    private fun sampleValue(name: String, key: String, def: JSONObject): Any {
        ARRAY_OF_OBJECTS["$name.$key"]?.let { return it() }
        return when (def.optString("type")) {
            "string" -> def.optJSONArray("enum")?.let { en -> en.optString(0) } ?: "示例"
            "integer" -> {
                val min = if (def.has("minimum")) def.optDouble("minimum") else 1.0
                val max = if (def.has("maximum")) def.optDouble("maximum") else 1_000.0
                min.coerceAtMost(max).toLong().coerceAtLeast(0L).toInt()
            }
            "number" -> 1.0
            "boolean" -> true
            "array" -> def.optJSONObject("items")?.optJSONArray("enum")?.let { en ->
                JSONArray().put(en.optString(0))
            } ?: JSONArray()
            "object" -> JSONObject()
            else -> "示例"
        }
    }

    /** 与声明类型不符的一个值（类型错用例用）。 */
    private fun wrongValue(type: String): Any = when (type) {
        "string" -> 12345
        "integer", "number" -> "不是数字"
        "boolean" -> "true"
        "array" -> "不是数组"
        "object" -> "不是对象"
        else -> 12345
    }

    private fun copyOf(src: JSONObject): JSONObject = JSONObject(src.toString())

    private fun newRoot(): File =
        kotlin.io.path.createTempDirectory("zd-schema119-").toFile().also { roots += it }

    private companion object {
        /** 评测会话号（与真会话分格，不碰线上状态）。 */
        const val SESSION = 11_919L

        /**
         * 每个工具的**最小合法参数**（只填必填）。
         *
         * 这张表是「工具名册」的第二份显式清单：新增工具必须在这里加一行，否则
         * [全部工具_schema_契约齐全_一个不漏] 会先红（比「少跑一个工具」更容易发现）。
         * 值本身不求真（契约只看类型/必填/枚举），求的是**每个工具都被点到**。
         */
        val REQUIRED_ONLY: Map<String, JSONObject> = mapOf(
            "read_file" to JSONObject().put("path", "a.md"),
            "list_dir" to JSONObject().put("path", "资料库"),
            "search_in_files" to JSONObject().put("path", "资料库").put("query", "词"),
            "find_files" to JSONObject().put("pattern", "斗罗"),
            "book_outline" to JSONObject().put("path", "书.txt"),
            "file_info" to JSONObject().put("path", "a.md"),
            "write_file" to JSONObject().put("path", "o.md").put("content", "甲乙\n丙"),
            "edit_file" to JSONObject().put("path", "a.md").put("op", "append").put("content", "新行"),
            "move_path" to JSONObject().put("from", "a.md").put("to", "b.md"),
            "convert_encoding" to JSONObject().put("path", "a.md"),
            "browser_open" to JSONObject().put("url", "https://m.bing.com"),
            "browser_snapshot" to JSONObject(),
            "browser_click" to JSONObject().put("target", "#a"),
            "browser_type" to JSONObject().put("target", "#a").put("text", "字"),
            "browser_scroll" to JSONObject().put("direction", "down"),
            "browser_screenshot" to JSONObject(),
            "update_plan" to JSONObject().put(
                "items",
                JSONArray().put(JSONObject().put("text", "写第十节").put("status", "doing")),
            ),
            "library_list" to JSONObject(),
            "library_organize" to JSONObject().put("action", "index"),
            "delete_path" to JSONObject(),
            "image_ops" to JSONObject().put("action", "palette").put("path", "a.png"),
            "ask_user" to JSONObject().put("question", "选哪个？").put(
                "options",
                JSONArray()
                    .put(JSONObject().put("label", "留着"))
                    .put(JSONObject().put("label", "删掉")),
            ),
            "card_inspect" to JSONObject().put("path", "c.json"),
            "card_export" to JSONObject().put("path", "c.json").put("out", "c.png"),
            "card_build" to JSONObject(),
            "capabilities" to JSONObject(),
            "preview_local_file" to JSONObject().put("path", "产物/首页.html"),
            // 第一百二十七批：多 Agent 协作（只给最小合法形状：一条带 goal 的子任务）
            "run_subtasks" to JSONObject().put(
                "subtasks",
                JSONArray().put(JSONObject().put("goal", "读 a.md 写摘要")),
            ),
            "write_skill" to JSONObject().put("name", "试样").put("body", "# 试样\n"),
            "append_persona" to JSONObject().put("text", "先列计划再动手"),
            "read" to JSONObject().put("path", "a.md"),
            "bash" to JSONObject(),
        )

        /** 数组成员的参数（按「工具名.参数名」索引）：自动造不出有意义的元素，手写。 */
        val ARRAY_OF_OBJECTS: Map<String, () -> JSONArray> = mapOf(
            "ask_user.options" to {
                JSONArray()
                    .put(JSONObject().put("id", "keep").put("label", "留着"))
                    .put(JSONObject().put("id", "drop").put("label", "删掉").put("recommended", true))
            },
            "update_plan.items" to {
                JSONArray().put(JSONObject().put("text", "写第十节").put("status", "doing"))
            },
        )
    }
}
