// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十四批 · 判据/负例（派工单 §2 的八条负例逐条有靶子）。
//
// 为什么全在 JVM 里跑：`form_fill` 的执行体只认 [FormDom] 端口（201 的接口），
// 假 DOM 就能把「填哪个格、填没填进去、有没有点提交」全部验真——
// 不靠真机、不靠 WebView，也不靠人去点按钮。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.FormFillTool
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolSchemaRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FormFillToolTest {

    // ── 正例 ────────────────────────────────────────────────────────────────

    @Test
    fun `填三个普通字段_全成功_证据三条_submitted为false`() {
        val dom = page()
        val out = run(dom, mapOf("标题" to "A", "正文" to "B", "一句话简介" to "C"))
        val json = JSONObject((out as ToolOutcome.Ok).text)

        assertEquals("三个字段都要填上", 3, json.getJSONArray("filled").length())
        assertFalse("工具永不提交", json.getBoolean("submitted"))
        assertTrue("页面上有按钮就要交给人点", json.getBoolean("needsHumanClick"))
        assertEquals("按钮清单要含那个保存按钮", "保存", json.getJSONArray("buttons").getJSONObject(0).getString("text"))
        assertTrue("保存必须标成高危（人点之前要看一眼）", json.getJSONArray("buttons").getJSONObject(0).getBoolean("dangerous"))

        // 证据：每字段一条，且带「前值 → 后值」
        for (i in 0 until 3) {
            val e = json.getJSONArray("filled").getJSONObject(i).getString("evidence")
            assertTrue("证据行要看得出前后值：$e", e.contains("→"))
            assertTrue("证据行要带定位器：$e", e.contains("T") && e.contains("="))
        }
        assertEquals("页面上一共只该被填三次", 3, dom.fillCalls.size)
        assertEquals("表单端口没有点击通道，点次数只能是 0", 0, dom.clickCount)
        assertTrue("提交类控件一格都不许碰", dom.poisonFilled.isEmpty())
    }

    @Test
    fun `三档查找_标签档_name档_placeholder档各自都能命中`() {
        val dom = page()
        // 「正文」走标签档、「subtitle」走 name 档（它的标签是「副标」）、「一句话简介」走 placeholder 档
        val out = run(dom, mapOf("正文" to "按标签", "subtitle" to "按name", "一句话简介" to "按placeholder"))
        val json = JSONObject((out as ToolOutcome.Ok).text)
        assertEquals("三格都要填上：$json", 3, json.getJSONArray("filled").length())
        val byField = (0 until 3).associate {
            val o = json.getJSONArray("filled").getJSONObject(it)
            o.getString("label") to o.getString("tier")
        }
        assertEquals("label", byField["正文"])
        assertEquals("name", byField["副标"])
        assertEquals("placeholder", byField["tagline"])
    }

    @Test
    fun `找不到字段时回执里带上这页能填什么`() {
        val out = run(page(), mapOf("不存在的字段" to "x"))
        val json = JSONObject((out as ToolOutcome.Ok).text)
        val reason = json.getJSONArray("skipped").getJSONObject(0).getString("reason")
        assertTrue("要说清找不到：$reason", reason.contains("找不到"))
        assertTrue("还要列出能填的字段（让模型自己改）：$reason", reason.contains("标题"))
    }

    @Test
    fun `读不到页面时一个字段都不填`() {
        val dom = page()
        val blind = object : FormDom {
            override fun scan(): List<FormCandidate>? = null
            override fun probe(locators: List<TeachLocator>): FormReply = FormReply.Failed("没有标签页")
            override fun fill(locators: List<TeachLocator>, control: FormControl, value: String, optionValue: String?): FormReply =
                FormReply.Failed("没有标签页")
            override fun read(locators: List<TeachLocator>, control: FormControl): FormReply = FormReply.Failed("没有标签页")
        }
        val json = JSONObject((run(blind, mapOf("标题" to "A")) as ToolOutcome.Ok).text)
        assertEquals(0, json.getJSONArray("filled").length())
        assertTrue("读不到页面就必须如实说（负例：不许假装读到）", json.isNull("unreadable").not())
        assertEquals(0, dom.fillCalls.size)
    }

    // ── 负例 1：工具自己点了提交按钮 ────────────────────────────────────────

    @Test
    fun `负例1_传submit为true直接被拒_页面零动作`() {
        val dom = page()
        val out = FormFillTool.execute(
            JSONObject().put("fields", JSONObject().put("标题", "A")).put("submit", true),
            dom,
        )
        assertTrue("必须是被拒（不是悄悄忽略）", out is ToolOutcome.Rejected)
        assertEquals(FormFillTool.SUBMIT_REFUSAL, (out as ToolOutcome.Rejected).reason)
        assertEquals("一个字段都没动", 0, dom.fillCalls.size)
        assertEquals("更没有点", 0, dom.clickCount)
    }

    @Test
    fun `负例1_源码里没有点击与提交的调用`() {
        // 只看**代码行**：注释里解释「本文件没有 .click(」不算违规（否则门禁会逼人删注释）
        val src = source("src/main/java/com/example/zhengdao/baopu/agent/tools/FormFillTool.kt")
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        for (bad in listOf(".click(", "requestSubmit", "form.submit", "dispatchEvent(new MouseEvent", "click()")) {
            assertFalse("工具层不许出现「点一下」：$bad", src.contains(bad))
        }
        // 端口（201 的 FormDom）本身也没有 click 通道——这是结构保证，不是自觉
        val port = source("src/main/java/com/example/zhengdao/baopu/TeachForm.kt")
        assertFalse("FormDom 端口不许长出 click：$port", port.substringBefore("class BrowserFormDom").contains("fun click"))
    }

    // ── 负例 2：密码/验证码被填进页面 ──────────────────────────────────────

    @Test
    fun `负例2_密码框拒填_值一个字节都没进去`() {
        val dom = page()
        val json = JSONObject((run(dom, mapOf("密码" to "hunter2")) as ToolOutcome.Ok).text)
        assertEquals(0, json.getJSONArray("filled").length())
        val reason = json.getJSONArray("skipped").getJSONObject(0).getString("reason")
        assertTrue("要说清为什么拒：$reason", reason.contains("拒填"))
        assertEquals("密码框必须还是空的", "", dom.elements.first { it.nameAttr == "pwd" }.value)
        assertTrue("也不许出现任何填动作落到密码框", dom.fillCalls.none { it.contains("pwd") })
    }

    @Test
    fun `负例2_autocomplete为one_time_code的框也拒填`() {
        val dom = page()
        // 请求名「短码」不含任何凭证字样 ⇒ 只能靠 autocomplete 判据拦住
        val json = JSONObject((run(dom, mapOf("短码" to "123456")) as ToolOutcome.Ok).text)
        assertEquals(0, json.getJSONArray("filled").length())
        assertTrue(dom.fillCalls.isEmpty())
        assertTrue(
            "理由要指向凭证：${json.getJSONArray("skipped").getJSONObject(0).getString("reason")}",
            json.getJSONArray("skipped").getJSONObject(0).getString("reason").contains("拒填"),
        )
        assertEquals("", dom.elements.first { it.autocomplete == "one-time-code" }.value)
    }

    @Test
    fun `负例2_名字像验证码的一律拒填_不看页面`() {
        val dom = page()
        val json = JSONObject((run(dom, mapOf("验证码" to "123456", "password" to "x")) as ToolOutcome.Ok).text)
        assertEquals(0, json.getJSONArray("filled").length())
        assertEquals(2, json.getJSONArray("skipped").length())
        assertTrue(dom.fillCalls.isEmpty())
    }

    // ── 负例 3：敏感值进了日志/回执 ────────────────────────────────────────

    @Test
    fun `负例3_手机号与密钥量级的值进回执前就打码`() {
        val dom = page()
        val phone = "13800138000"
        val secret = "sk-live-0123456789abcdef0123456789abcdef"
        val json = JSONObject((run(dom, mapOf("联系方式" to phone, "接口密钥" to secret)) as ToolOutcome.Ok).text)
        val text = json.toString()
        assertFalse("完整手机号不许出现在回执里", text.contains(phone))
        assertFalse("完整密钥不许出现在回执里", text.contains(secret))
        assertTrue("但要留下可核对的痕迹（打码形态）", text.contains("*"))
        // 值仍然填进了页面（打码只打证据，不改实际填写）
        assertEquals(phone, dom.elements.first { it.nameAttr == "contact" }.value)
    }

    // ── 负例 4：歧义时猜一个填 ────────────────────────────────────────────

    @Test
    fun `负例4_一个标签命中两个输入框_报歧义_两个都不填`() {
        val dom = FakeFormDom(
            mutableListOf(
                FakeFormEl(tag = "input", nameAttr = "name1", label = "名字", type = "text"),
                FakeFormEl(tag = "input", nameAttr = "name2", label = "名字", type = "text"),
            ),
        )
        val json = JSONObject((run(dom, mapOf("名字" to "张三")) as ToolOutcome.Ok).text)
        assertEquals("歧义要单独报", 1, json.getJSONArray("ambiguous").length())
        assertEquals(0, json.getJSONArray("filled").length())
        val amb = json.getJSONArray("ambiguous").getJSONObject(0)
        assertEquals("名字", amb.getString("field"))
        assertEquals("命中几个也要说清", 2, amb.getJSONArray("candidates").length())
        assertEquals("宁可一个都不填，也不猜第一个", 0, dom.fillCalls.size)
        assertTrue(dom.elements.all { it.value.isEmpty() })
    }

    // ── 负例 5：批量失败后没清单 / 无法撤回 ────────────────────────────────

    @Test
    fun `负例5_中途失败即停_已填项有清单且能清回原值`() {
        val dom = FakeFormDom(
            mutableListOf(
                FakeFormEl(tag = "input", nameAttr = "title", label = "标题", type = "text", value = "旧标题"),
                // 这格填了也不存（页面当装饰静默丢弃）⇒ 回读不一致 ⇒ 停
                FakeFormEl(tag = "input", nameAttr = "summary", label = "简介", type = "text", ignoreFill = true),
                FakeFormEl(tag = "input", nameAttr = "note", label = "备注", type = "text"),
            ),
        )
        val json = JSONObject(
            (run(dom, mapOf("标题" to "新标题", "简介" to "X", "备注" to "Y")) as ToolOutcome.Ok).text,
        )
        assertEquals("失败前填成的那一格要有清单", 1, json.getJSONArray("filled").length())
        assertEquals("清单里要带动手前的原值", "旧标题", json.getJSONArray("filled").getJSONObject(0).getString("before"))
        assertFalse("停下要说原因", json.isNull("stopped"))
        assertEquals("已填项要撤回", "标题", json.getJSONArray("rolledBack").getString(0))
        assertEquals("页面真的回到原值", "旧标题", dom.elements.first { it.nameAttr == "title" }.value)
        assertEquals("后面的字段一格没动", "", dom.elements.first { it.nameAttr == "note" }.value)
        // 每一格都有交代：填失败的那格 ＋ 没来得及动的那格
        assertEquals(2, json.getJSONArray("skipped").length())
    }

    @Test
    fun `负例5_原值本来为空_撤回就是清空`() {
        val dom = FakeFormDom(
            mutableListOf(
                FakeFormEl(tag = "input", nameAttr = "title", label = "标题", type = "text"),
                FakeFormEl(tag = "input", nameAttr = "summary", label = "简介", type = "text", ignoreFill = true),
            ),
        )
        val json = JSONObject((run(dom, mapOf("标题" to "新标题", "简介" to "X")) as ToolOutcome.Ok).text)
        assertEquals("标题", json.getJSONArray("rolledBack").getString(0))
        assertEquals("原值是空 ⇒ 撤回就是清空", "", dom.elements.first { it.nameAttr == "title" }.value)
    }

    @Test
    fun `负例5_关掉撤回时不写回_但清单照样给`() {
        val dom = FakeFormDom(
            mutableListOf(
                FakeFormEl(tag = "input", nameAttr = "title", label = "标题", type = "text"),
                FakeFormEl(tag = "input", nameAttr = "summary", label = "简介", type = "text", ignoreFill = true),
            ),
        )
        val out = FormFillTool.execute(
            JSONObject()
                .put("fields", JSONObject().put("标题", "新标题").put("简介", "X"))
                .put("rollbackOnFailure", false),
            dom,
        )
        val json = JSONObject((out as ToolOutcome.Ok).text)
        assertEquals(0, json.getJSONArray("rolledBack").length())
        assertEquals("清单不能因为不撤回就不给", 1, json.getJSONArray("filled").length())
        assertEquals("新标题", dom.elements.first { it.nameAttr == "title" }.value)
    }

    // ── 负例 6：未注册 / 未进 R 表 ────────────────────────────────────────

    @Test
    fun `负例6_工具已注册且进风险表_R2起_语义升R3_密码键硬拒`() {
        assertTrue(
            "注册表里没有 form_fill：${BaopuToolCatalog.ENTRIES.map { it.name }}",
            BaopuToolCatalog.ENTRIES.any { it.name == FormFillTool.NAME },
        )
        assertEquals(RiskClass.R2, RiskMatrix.baseOf(FormFillTool.NAME))
        assertFalse("它必须已注册分组（否则 checkCoverage 加载即抛）", ToolGroupTable.GROUP_OF[FormFillTool.NAME] == null)

        val plain = verdict(mapOf("fields" to JSONObject().put("标题", "A")))
        assertEquals(RiskClass.R2, plain.risk)
        assertEquals(RiskDecision.PROCEED, plain.decision)

        // 名称含「登录/支付/提交/发布」语义 ⇒ R3 且必问（不可自动）
        for (word in listOf("登录", "支付", "提交", "发布")) {
            val v = verdict(mapOf("fields" to JSONObject().put("${word}按钮", "x")))
            assertEquals("「$word」必须升 R3", RiskClass.R3, v.risk)
            assertEquals("「$word」必须必问", RiskDecision.MUST_ASK, v.decision)
            assertFalse(v.hardDeny)
        }

        // 字段名像密码/验证码 ⇒ 硬拒（不是「问一下」）
        for (word in listOf("密码", "验证码", "password", "otp")) {
            val v = verdict(mapOf("fields" to JSONObject().put(word, "x")))
            assertTrue("「$word」必须硬拒", v.hardDeny)
            assertEquals(RiskDecision.REJECT, v.decision)
        }
    }

    @Test
    fun `负例6_schema契约齐全_描述不超上限`() {
        val spec = FormFillTool.spec(dom = { pageDom() })
        assertEquals(FormFillTool.NAME, spec.name)
        assertEquals("object", spec.inputSchema.optString("type"))
        assertFalse(spec.inputSchema.optBoolean("additionalProperties", true))
        val props = spec.inputSchema.getJSONObject("properties")
        for (k in listOf("fields", "batch", "submit", "rollbackOnFailure")) {
            assertTrue("schema 少了 $k", props.has(k))
        }
        // 第二百零一批 ①：fields 与 batch 二选一。「至少给一个」这条 JSON Schema 面上写不出来
        // （SchemaCheck 是平的、不递归），改由 execute() 手工判 ⇒ required 不许再写死 fields，
        // 否则批量分支（只带 batch）会在 schema 校验那关就被误拒。
        // 但「整段没有」过不了 94/119 批的 S4 门禁（ToolSchemaGateTest 会红）：口径是
        // **空数组 ＋ 在 ToolSchemaRules.REQUIRED_EMPTY_OK 登记原因**（与 203 的 ask_user 同形制）。
        assertTrue("required 段必须在（S4：缺段＝漏写必填的伪装）", spec.inputSchema.has("required"))
        assertEquals("required 放开：批量分支不填 fields", 0, spec.inputSchema.getJSONArray("required").length())
        assertTrue(
            "空 required 要在签字表里登记原因，否则 S5 红",
            ToolSchemaRules.REQUIRED_EMPTY_OK.containsKey(FormFillTool.NAME),
        )
        assertTrue("batch 的说明要把四个动作写全", listOf("start", "next", "advance", "status")
            .all { props.getJSONObject("batch").optString("description").contains(it) })
        assertTrue("描述不能超上限", spec.description.length <= 500)
        assertFalse("它改页面，不许标成只读", spec.readOnly)
    }

    // ── 负例 7：description 暗示可以提交 ──────────────────────────────────

    @Test
    fun `负例7_描述里只填不提交_没有任何代点暗示`() {
        val d = FormFillTool.DESCRIPTION
        assertTrue("必须明说只填：$d", d.contains("只填"))
        assertTrue("必须明说不提交：$d", d.contains("不提交"))
        assertTrue("必须明说按钮由用户点：$d", d.contains("自己点") || d.contains("人点"))
        for (bad in listOf("自动提交", "会提交", "代为提交", "帮你提交", "点击提交", "自动保存", "点击保存")) {
            assertFalse("描述不许暗示能提交：$bad", d.contains(bad))
        }
        // schema 那一段也要说清 submit 传 true 会被拒
        val submitDesc = FormFillTool.spec(dom = { pageDom() })
            .inputSchema.getJSONObject("properties").getJSONObject("submit").optString("description")
        assertTrue("submit 参数说明必须写明永不提交：$submitDesc", submitDesc.contains("永不提交"))
    }

    // ── 负例 8：另起一套表单实现 ──────────────────────────────────────────

    @Test
    fun `负例8_填表动作全部复用201的判据与执行层_没有第二套`() {
        val src = source("src/main/java/com/example/zhengdao/baopu/agent/tools/FormFillTool.kt")
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue("必须用 201 的填表器", src.contains("FormFiller("))
        assertTrue("必须用 201 的分类/预检", src.contains("TeachFormRules."))
        assertTrue("必须用 201 的端口", src.contains("FormDom"))
        assertTrue("拒填判据必须复用 196 的名单", src.contains("TeachSafety.refuseTypeReason"))
        // 不许自己写 JS、不许自己写 DOM 操作（那是第二套实现的样子）
        for (bad in listOf("document.querySelector", "document.querySelectorAll", "innerHTML", "el.value =", "getElementsByName")) {
            assertFalse("工具层不许自己操作 DOM：$bad", src.contains(bad))
        }
    }


    // ── 脚手架 ──────────────────────────────────────────────────────────────

    private fun pageDom(): FormDom = page()

    private fun page(): FakeFormDom = FakeFormDom(
        mutableListOf(
            FakeFormEl(tag = "input", nameAttr = "title", label = "标题", type = "text"),
            FakeFormEl(tag = "textarea", nameAttr = "content", label = "正文"),
            FakeFormEl(tag = "input", nameAttr = "subtitle", label = "副标", type = "text"),
            FakeFormEl(tag = "input", nameAttr = "tagline", placeholder = "一句话简介", type = "text"),
            FakeFormEl(tag = "input", nameAttr = "contact", label = "联系方式", type = "text"),
            FakeFormEl(tag = "input", nameAttr = "apikey", label = "接口密钥", type = "text"),
            // 凭证类：两格都必须拒（一个靠 type，一个靠 autocomplete）
            FakeFormEl(tag = "input", nameAttr = "pwd", label = "密码", type = "password"),
            FakeFormEl(tag = "input", nameAttr = "vcode", label = "短码", type = "text", autocomplete = "one-time-code"),
            FakeFormEl(tag = "button", label = "保存"),
        ),
    )

    private fun run(dom: FormDom, fields: Map<String, String>): ToolOutcome {
        val obj = JSONObject()
        fields.forEach { (k, v) -> obj.put(k, v) }
        return FormFillTool.execute(JSONObject().put("fields", obj), dom)
    }

    private fun verdict(args: Map<String, Any?>): RiskVerdict {
        val json = JSONObject()
        args.forEach { (k, v) -> json.put(k, v) }
        return RiskMatrix.classify(com.example.zhengdao.baopu.agent.ToolCall("t", FormFillTool.NAME, json))
    }

    /** 源级门禁用：`src/main/java/...` 相对模块根（Gradle test 的工作目录是 app/）。 */
    private fun source(rel: String): String =
        File(rel).let { if (it.exists()) it else File("app/$rel") }.readText()
}
