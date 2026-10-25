// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零一批 · 判据层单测（纯函数：分类/扫描/预检/回读/去重/参数化）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachFormRulesTest {

    private fun cand(
        tag: String,
        type: String? = null,
        name: String? = null,
        label: String? = null,
        ce: Boolean = false,
        disabled: Boolean = false,
        readOnly: Boolean = false,
        visible: Boolean = true,
        options: List<TeachOption> = emptyList(),
    ) = FormCandidate(
        tag = tag, inputType = type, inputName = name, labelText = label,
        contentEditable = ce, disabled = disabled, readOnly = readOnly, visible = visible,
        options = options,
    )

    // ── 分类：五类控件各就各位（派工单 §1 支持面）───────────────────────────

    @Test
    fun `门禁_五类控件分类`() {
        fun kind(c: FormCandidate): FormControl? =
            (TeachFormRules.classify(c) as? TeachFormRules.Classified.Accepted)?.control

        assertEquals(FormControl.SINGLE_LINE, kind(cand("INPUT", "text", "a")))
        assertEquals(FormControl.SINGLE_LINE, kind(cand("INPUT", null, "a")))
        assertEquals(FormControl.SINGLE_LINE, kind(cand("INPUT", "search", "a")))
        assertEquals(FormControl.RICH_TEXT, kind(cand("TEXTAREA", null, "a")))
        assertEquals(FormControl.RICH_TEXT, kind(cand("DIV", null, "a", ce = true)))
        assertEquals(FormControl.SELECT, kind(cand("SELECT", null, "a")))
        assertEquals(FormControl.RADIO, kind(cand("INPUT", "radio", "a")))
    }

    @Test
    fun `门禁_看不见与只读禁用一律不填`() {
        listOf(
            cand("INPUT", "text", "a", visible = false),
            cand("INPUT", "text", "a", disabled = true),
            cand("INPUT", "text", "a", readOnly = true),
        ).forEach { c ->
            assertTrue("应排除：$c", TeachFormRules.classify(c) is TeachFormRules.Classified.Excluded)
        }
    }

    @Test
    fun `门禁_不在v1支持范围的类型明确排除`() {
        listOf("hidden", "file", "checkbox", "date", "color", "range").forEach { t ->
            val verdict = TeachFormRules.classify(cand("INPUT", t, "a"))
            assertTrue("$t 应排除", verdict is TeachFormRules.Classified.Excluded)
            assertTrue(
                "排除原因要说清是什么类型：${(verdict as TeachFormRules.Classified.Excluded).reason}",
                verdict.reason.contains(t),
            )
        }
    }

    // ── 负例 1：提交/创建/保存/发布类控件 ───────────────────────────────────

    @Test
    fun `负例1_提交创建保存发布类控件一律不进字段表`() {
        val scan = TeachFormRules.buildFields(FormFixtures.candidates(FormFixtures.decoys()))
        assertEquals("诱饵按钮一个都不许进字段表", 0, scan.total)
        assertEquals(3, scan.excluded.size)
        // 按钮、submit、保存草稿：三条原因都要说「不点」或「不填」
        scan.excluded.forEach { e ->
            assertTrue("排除原因不明确：${e.reason}", e.reason.contains("不点") || e.reason.contains("不填"))
        }
        assertTrue(scan.excluded.any { it.label.contains("创建") })
        assertTrue(scan.excluded.any { it.label.contains("发布") })
        assertTrue(scan.excluded.any { it.label.contains("保存") })
    }

    @Test
    fun `负例1_字段名像提交类也不填`() {
        // 名字里带 create/save 的输入框：一样拒（误拒方向安全）
        listOf("create_card", "save_draft").forEach { n ->
            val v = TeachFormRules.classify(cand("INPUT", "text", n))
            assertTrue("$n 应排除，实际 $v", v is TeachFormRules.Classified.Excluded)
        }
        // postal_code 不许被 post 误伤（英文面用词边界）
        val ok = TeachFormRules.classify(cand("INPUT", "text", "postal_code"))
        assertTrue("postal_code 不该被当成发布按钮：$ok", ok is TeachFormRules.Classified.Accepted)
    }

    // ── 负例 2：密码/验证码 ────────────────────────────────────────────────

    @Test
    fun `负例2_密码与验证码不进字段表`() {
        listOf(
            cand("INPUT", "password", "pwd"),
            cand("INPUT", "text", "captcha"),
            cand("INPUT", "text", "login_pwd"),
        ).forEach { c ->
            val v = TeachFormRules.classify(c)
            assertTrue("凭证类应排除：$c", v is TeachFormRules.Classified.Excluded)
            val reason = (v as TeachFormRules.Classified.Excluded).reason
            assertTrue("原因要点明凭证：$reason", reason.contains("拒填"))
        }
    }

    // ── 扫描：重复块与单选组 ───────────────────────────────────────────────

    @Test
    fun `扫描_重复块按 name 分段_键形如 worldbook1_title`() {
        val scan = TeachFormRules.buildFields(FormFixtures.candidates(FormFixtures.elements()))
        assertEquals("固定 2 段", 2, scan.blocks.size)
        assertEquals(listOf(1, 2), scan.blocks.map { it.index })
        val keys = scan.blocks.flatMap { b -> b.fields.map { it.key } }
        assertEquals(listOf("worldbook1_title", "worldbook1_content", "worldbook2_title", "worldbook2_content"), keys)
        assertTrue(scan.fields.none { it.key.startsWith("worldbook") })
    }

    @Test
    fun `扫描_同名单选只留一个字段且带选项表`() {
        val scan = TeachFormRules.buildFields(FormFixtures.candidates(FormFixtures.elements()))
        val radios = scan.fields.filter { it.control == FormControl.RADIO }
        assertEquals(1, radios.size)
        assertEquals("gender", radios.first().key)
        assertEquals(listOf("m", "f"), radios.first().options.map { it.value })
    }

    @Test
    fun `扫描_字段键唯一_重名加下划线序号`() {
        val scan = TeachFormRules.buildFields(
            listOf(
                cand("INPUT", "text", null, "同一个名字"),
                cand("INPUT", "text", null, "同一个名字"),
            ),
        )
        assertEquals(2, scan.fields.size)
        assertTrue("键必须唯一：${scan.fields.map { it.key }}", scan.fields.map { it.key }.toSet().size == 2)
    }

    @Test
    fun `扫描_参数表按形状推导`() {
        val scan = TeachFormRules.buildFields(FormFixtures.candidates(FormFixtures.elements()))
        val params = TeachFormRules.deriveParams(scan).associateBy { it.name }
        assertEquals(TeachParamShape.ENUM, params.getValue("style").shape)
        assertEquals(TeachParamShape.TEXT, params.getValue("char_name").shape)
        assertEquals(8, params.size)
    }

    // ── 负例 4 前半：预检先说清缺哪一段 ────────────────────────────────────

    @Test
    fun `负例4_预检_缺第二段要指名道姓`() {
        val t = FormFixtures.template()
        val p = TeachFormRules.precheck(t, FormFixtures.cardFirstSegmentOnly())
        assertTrue(p is TeachFormRules.Precheck.Rejected)
        val reasons = (p as TeachFormRules.Precheck.Rejected).reasons.joinToString("；")
        assertTrue("要说第 2 段：$reasons", reasons.contains("第 2 段"))
        assertTrue("要点名缺哪个字段：$reasons", reasons.contains("第 2 条标题") || reasons.contains("标题"))
    }

    @Test
    fun `预检_多余字段要拒_防拿错卡`() {
        val t = FormFixtures.template()
        val card = FormFixtures.card().let { it.copy(values = it.values + ("unknown_key" to "x")) }
        val p = TeachFormRules.precheck(t, card)
        assertTrue(p is TeachFormRules.Precheck.Rejected)
        assertTrue((p as TeachFormRules.Precheck.Rejected).reasons.joinToString().contains("unknown_key"))
    }

    @Test
    fun `预检_下拉值不在选项表要拒`() {
        val t = FormFixtures.template()
        val card = FormFixtures.card().let { it.copy(values = it.values + ("style" to "赛博")) }
        val p = TeachFormRules.precheck(t, card)
        assertTrue(p is TeachFormRules.Precheck.Rejected)
        assertTrue((p as TeachFormRules.Precheck.Rejected).reasons.joinToString().contains("风格"))
    }

    @Test
    fun `预检_参数声明了形状就按形状校验`() {
        val t = FormFixtures.template()
        // 声明成日期 ⇒ 「小明」过不了
        val dated = t.copy(params = t.params + FormParamDecl("char_name", "角色名", TeachParamShape.DATE))
        val p = TeachFormRules.precheck(dated, FormFixtures.card())
        assertTrue("声明了日期形状就要校验", p is TeachFormRules.Precheck.Rejected)
        assertTrue(
            "原因要说清形状不对：${(p as TeachFormRules.Precheck.Rejected).reasons}",
            p.reasons.joinToString().contains("形状不对"),
        )
        // 声明成自由文本 ⇒ 照过
        val text = t.copy(params = t.params + FormParamDecl("char_name", "角色名", TeachParamShape.TEXT))
        assertTrue(TeachFormRules.precheck(text, FormFixtures.card()) is TeachFormRules.Precheck.Ok)
    }

    @Test
    fun `预检_完整卡一次过且指令数等于字段数`() {
        val t = FormFixtures.template()
        val p = TeachFormRules.precheck(t, FormFixtures.card())
        assertTrue(p is TeachFormRules.Precheck.Ok)
        assertEquals(8, (p as TeachFormRules.Precheck.Ok).ops.size)
    }

    // ── 回读校验（负例 3 / 5 的判据）───────────────────────────────────────

    private fun richField() = FormField(
        key = "persona", label = "人设", control = FormControl.RICH_TEXT,
        locators = listOf(TeachLocator(TeachLocatorKind.INPUT_NAME, "persona")),
    )

    @Test
    fun `回读_富文本多行一致才通过`() {
        val f = richField()
        val ok = TeachFormRules.check(f, "一\n二\n三", null, FormReadback("一\n二\n三"))
        assertTrue(ok.ok)
        assertNull(ok.reason)
    }

    @Test
    fun `负例3_富文本被塞成纯文本要红`() {
        val f = richField()
        // 字一个不少，换行变成空格 ⇒ 典型「纯文本塞进富文本编辑器」
        val bad = TeachFormRules.check(f, "一\n二\n三", null, FormReadback("一 二 三"))
        assertFalse(bad.ok)
        assertTrue("原因要点明富文本降级：${bad.reason}", bad.reason.orEmpty().contains("富文本被塞成纯文本"))
    }

    @Test
    fun `负例3_富文本少行也算结构丢了`() {
        val f = richField()
        // 文字对得上但空行被吞（行数变少）
        val bad = TeachFormRules.check(f, "一\n\n二", null, FormReadback("一\n二"))
        assertFalse(bad.ok)
    }

    @Test
    fun `回读_单行不一致要红`() {
        val f = FormField(
            key = "char_name", label = "角色名", control = FormControl.SINGLE_LINE,
            locators = listOf(TeachLocator(TeachLocatorKind.INPUT_NAME, "char_name")),
        )
        assertTrue(TeachFormRules.check(f, "小明", null, FormReadback("小明")).ok)
        assertTrue("首尾空白不算不一致", TeachFormRules.check(f, "小明", null, FormReadback(" 小明 ")).ok)
        assertFalse(TeachFormRules.check(f, "小明", null, FormReadback("小红")).ok)
    }

    @Test
    fun `回读_下拉与单选看选中项`() {
        val sel = FormField(
            key = "style", label = "风格", control = FormControl.SELECT,
            locators = listOf(TeachLocator(TeachLocatorKind.INPUT_NAME, "style")),
            options = listOf(TeachOption("1", "古风"), TeachOption("2", "江湖")),
        )
        assertTrue(TeachFormRules.check(sel, "古风", "1", FormReadback("古风", "1")).ok)
        assertFalse(TeachFormRules.check(sel, "古风", "1", FormReadback("江湖", "2")).ok)

        val radio = FormField(
            key = "gender", label = "性别", control = FormControl.RADIO,
            locators = listOf(TeachLocator(TeachLocatorKind.INPUT_NAME, "gender")),
        )
        assertTrue(TeachFormRules.check(radio, "女", "f", FormReadback("女", "f", checked = true)).ok)
        assertFalse(TeachFormRules.check(radio, "女", "f", FormReadback("女", "f", checked = false)).ok)
        assertFalse(TeachFormRules.check(radio, "女", "f", FormReadback("男", "m", checked = true)).ok)
    }

    @Test
    fun `负例5_填成功不算数_只认回读`() {
        // 判据层的落点：check 只看 read，不看任何「填成功」信号
        val f = richField()
        val bad = TeachFormRules.check(f, "完整三行\n第二行\n第三行", null, FormReadback("完整三行"))
        assertFalse(bad.ok)
        assertNotNull(bad.reason)
    }

    // ── 负例 6：重名不覆盖 ────────────────────────────────────────────────

    @Test
    fun `负例6_重名依次加后缀`() {
        assertEquals("小明", TeachFormRules.uniqueName("小明", emptySet()))
        assertEquals("小明-2", TeachFormRules.uniqueName("小明", setOf("小明")))
        assertEquals("小明-3", TeachFormRules.uniqueName("小明", setOf("小明", "小明-2")))
        assertEquals("未命名", TeachFormRules.uniqueName("   ", emptySet()))
    }

    // ── 复制全文 ─────────────────────────────────────────────────────────

    @Test
    fun `复制全文_按字段序带段标题`() {
        val t = FormFixtures.template()
        val text = TeachFormRules.copyText(t, FormFixtures.card())
        val i1 = text.indexOf("角色名：小明")
        val i2 = text.indexOf("人设：")
        val i3 = text.indexOf("〔第 1 段")
        val i4 = text.indexOf("〔第 2 段")
        assertTrue("四个位置都要在：$text", i1 >= 0 && i2 >= 0 && i3 >= 0 && i4 >= 0)
        assertTrue("顺序：主体在前、段落在后", i1 < i2 && i2 < i3 && i3 < i4)
        assertTrue("多行值不许压行", text.contains("十六岁，住在河边。\n怕水，但会撑船。"))
    }

    // ── 模板序列化 ───────────────────────────────────────────────────────

    @Test
    fun `模板_往返序列化字节一致`() {
        val t = FormFixtures.template()
        val once = t.canonical()
        val back = FormTemplate.fromJson(once, strict = true)
        assertNotNull("严格解析要能读回：$once", back)
        assertEquals(once, back!!.canonical())
    }

    @Test
    fun `模板_严格解析拒未知键_表里没有循环与分支`() {
        val t = FormFixtures.template()
        val polluted = t.canonical().trimEnd('}') + ",\"loop\":2}"
        assertNull("多一个键就整份不收", FormTemplate.fromJson(polluted, strict = true))
        assertNotNull("宽松解析照读（老文件/新文件共存）", FormTemplate.fromJson(polluted, strict = false))
    }

    @Test
    fun `模板_重复块段数固定_不认动态条数`() {
        val t = FormFixtures.template()
        assertEquals("段数就是录下来那几段", 2, t.blocks.size)
        val fourSegments = FormCard(
            name = "四段",
            values = FormFixtures.card().values +
                mapOf("worldbook3_title" to "多出来", "worldbook4_title" to "再多"),
        )
        val p = TeachFormRules.precheck(t, fourSegments)
        assertTrue("多出来的段要拒（v1 不支持动态增删）", p is TeachFormRules.Precheck.Rejected)
    }

    // ── 参数化输入：JSON / CSV ─────────────────────────────────────────────

    @Test
    fun `批量_JSON_世界书数组按段展平`() {
        val t = FormFixtures.template()
        val json = """
            [{
              "card": "小明",
              "char_name": "小明",
              "persona": "一行\n两行",
              "style": "古风",
              "gender": "女",
              "worldbook": [
                {"title": "河湾", "content": "一条大河"},
                {"title": "老周", "content": "摆渡人"}
              ]
            }]
        """.trimIndent()
        val cards = TeachFormRules.parseCardsJson(json, t)
        assertEquals(1, cards.size)
        assertEquals("小明", cards[0].name)
        assertEquals("河湾", cards[0].values["worldbook1_title"])
        assertEquals("摆渡人", cards[0].values["worldbook2_content"])
        // 展平后应能过预检
        assertTrue(TeachFormRules.precheck(t, cards[0]) is TeachFormRules.Precheck.Ok)
    }

    @Test
    fun `批量_CSV_首行表头每行一张卡`() {
        val t = FormFixtures.template()
        val csv = "card,char_name,persona,style,gender,worldbook1_title,worldbook1_content," +
            "worldbook2_title,worldbook2_content\n" +
            "小红,小红,\"甲\\n乙\",江湖,女,河湾,一条大河,老周,摆渡人\n"
        val cards = TeachFormRules.parseCardsCsv(csv, t)
        assertEquals(1, cards.size)
        assertEquals("小红", cards[0].name)
        assertEquals("江湖", cards[0].values["style"])
        assertEquals("甲\n乙", cards[0].values["persona"])
    }

    @Test
    fun `批量_形状校验_日期与数字`() {
        assertTrue(TeachFormRules.shapeOk(TeachParamShape.DATE, "2026-10-25", emptyList()))
        assertFalse(TeachFormRules.shapeOk(TeachParamShape.DATE, "10/25", emptyList()))
        assertTrue(TeachFormRules.shapeOk(TeachParamShape.NUMBER, "12", emptyList()))
        assertFalse(TeachFormRules.shapeOk(TeachParamShape.NUMBER, "十二", emptyList()))
    }
}
