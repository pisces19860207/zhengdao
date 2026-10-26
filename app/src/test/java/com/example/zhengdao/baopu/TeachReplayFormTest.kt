// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零一批 ② · 示教回放遇表单步：接线正确性（全 JVM，不碰 WebView）。
//
// 派工单那句「不许另写一套」在这里是可测的：回放的 TYPE/SELECT 两支交出去以后，动手的
// 只有 201 的 `FormFiller`——两条假 DOM 各记各的动作日志，`FakeDom.typed` 必须全空、
// `FakeFormDom.fillCalls` 必须有值，这就是「走的是哪条路」的证据（不是读注释读出来的）。
//
// 一段必须说清的边界（否则测试会盖住一个真问题）：
//   生产里 `BrowserTeachDom` 与 `BrowserFormDom` 打的是**同一个 WebView** ⇒ 填进去的值
//   真会留在页面上，196 那条 expect「关键文本出现」判的是同一份像素；
//   JVM 里它们是两个各存各值的内存对象 ⇒ 页面侧的「文本出现」由夹具里的**实时预览位**承担
//   （真制卡页确实有预览），**填对没填对一律由 `FakeFormDom` 的回读判定**。
//   两路联动（填完页面文本真的出现）由真机验收覆盖，见交付报告 §7。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TeachReplayFormTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val url = FormFixtures.START_URL
    private val persona = "十六岁，住在河边。\n怕水，但会撑船。\n嘴上不认。"
    private val wb1Content = "一条大河\n两座石桥"

    /** 一次回放的现场（两条假 DOM ＋ 结局）。 */
    private class Scene(
        val page: FakeDom,
        val form: FakeFormDom,
        val dom: FormFillingTeachDom,
        val report: TeachRunReport,
    )

    // ── 夹具：录一遍「风月制卡」的五格表单（走真学习器，不手写技能）────────────

    private fun info(
        tag: String,
        role: String,
        label: String,
        id: String? = null,
        inputName: String? = null,
        placeholder: String? = null,
        inputType: String? = null,
        options: List<TeachOption> = emptyList(),
    ) = TeachElementInfo(
        tag = tag,
        role = role,
        name = label,
        ariaLabel = label,
        elemId = id,
        inputName = inputName,
        placeholder = placeholder,
        inputType = inputType,
        options = options,
    )

    private fun events(charName: String = "小明", styleValue: String = "古风"): List<TeachRawEvent> = listOf(
        TeachRawEvent(
            TeachAction.TYPE, url,
            info("INPUT", "textbox", "角色名", id = "f-char-name", inputName = "char_name",
                placeholder = "起个名字", inputType = "text"),
            charName, url, url, 0, 0,
        ),
        TeachRawEvent(
            TeachAction.TYPE, url,
            info("TEXTAREA", "textbox", "人设", id = "f-persona", inputName = "persona"),
            persona, url, url, 0, 0,
        ),
        TeachRawEvent(
            TeachAction.SELECT, url,
            info("SELECT", "combobox", "风格", id = "f-style", inputName = "style",
                options = listOf(TeachOption("1", "古风"), TeachOption("2", "江湖"))),
            styleValue, url, url, 0, 0,
        ),
        TeachRawEvent(
            TeachAction.TYPE, url,
            info("INPUT", "textbox", "第 1 条标题", inputName = "worldbook[0][title]", inputType = "text"),
            "河湾", url, url, 0, 0,
        ),
        TeachRawEvent(
            TeachAction.TYPE, url,
            info("TEXTAREA", "textbox", "第 1 条内容", inputName = "worldbook[0][content]"),
            wb1Content, url, url, 0, 0,
        ),
    )

    private fun skill(
        stepCount: Int = Int.MAX_VALUE,
        charName: String = "小明",
        styleValue: String = "古风",
    ): TeachSkill = TeachLearner.buildDraft(
        events(charName, styleValue).take(if (stepCount == Int.MAX_VALUE) 5 else stepCount),
        "制卡", "录一遍制卡表单",
        // 起点页事实：这是一张表单、没有表头 ⇒ 指纹锚只收页面上真有的两个按钮
        TeachPageFacts(url, emptyList(), listOf("创建", "保存草稿")),
        nowMs = 1_700_000_000_000L,
    ).skill

    /** 同一张页面在 196 那侧的样子：属性与 [FormFixtures] 一一对齐，两边定位链才有交集。 */
    private fun pageElements(charName: String, styleValue: String): List<FakeEl> = listOf(
        FakeEl(tag = "INPUT", id = "f-char-name", nameAttr = "char_name", ariaLabel = "角色名",
            inputType = "text", placeholder = "起个名字"),
        FakeEl(tag = "TEXTAREA", id = "f-persona", nameAttr = "persona", ariaLabel = "人设"),
        FakeEl(tag = "SELECT", id = "f-style", nameAttr = "style", ariaLabel = "风格"),
        FakeEl(tag = "INPUT", nameAttr = "worldbook[0][title]", ariaLabel = "第 1 条标题", inputType = "text"),
        FakeEl(tag = "TEXTAREA", nameAttr = "worldbook[0][content]", ariaLabel = "第 1 条内容"),
        // 页脚的诱饵按钮（负例 1）：这一路一次都不许点着
        FakeEl(tag = "BUTTON", text = "创建", id = "btn-create", testid = "create-card"),
        FakeEl(tag = "BUTTON", text = "保存草稿", id = "btn-save"),
        // 实时预览位（真制卡页都有）：196 的 expect「文本出现」读的是这一份，见文件头
        FakeEl(tag = "P", text = charName),
        FakeEl(tag = "P", text = "河湾"),
        FakeEl(tag = "P", text = styleValue),
        FakeEl(tag = "DIV", text = persona),
        FakeEl(tag = "DIV", text = wb1Content),
    )

    private fun replay(
        form: FakeFormDom = FormFixtures.dom(),
        scanBlind: Boolean = false,
        stepCount: Int = Int.MAX_VALUE,
        charName: String = "小明",
        styleValue: String = "古风",
    ): Scene {
        val page = FakeDom(mapOf(url to FakePage(url, pageElements(charName, styleValue))), url)
        val port: FormDom = if (scanBlind) ScanBlindFormDom(form) else form
        val dom = FormFillingTeachDom(page, port)
        var now = 0L
        val report = TeachExecutor(
            dom = dom,
            evidence = FileTeachEvidenceSink(tmp.newFolder()),
            clock = { now },
            sleeper = { now += TeachExecutor.EXPECT_POLL_MS },
        ).run(skill(stepCount, charName, styleValue))
        return Scene(page, form, dom, report)
    }

    /** 把假页面上某个控件换成另一种长相（负例 3「富文本被压平」要重建元素：store 是构造期的）。 */
    private fun formWithPersonaFlattened(): FakeFormDom {
        val els = FormFixtures.elements().map { el ->
            if (el.nameAttr == "persona") {
                FakeFormEl(tag = "TEXTAREA", nameAttr = "persona", label = "人设", id = "f-persona",
                    store = { v -> v.lines().joinToString(" ") })
            } else {
                el
            }
        }.toMutableList()
        els += FormFixtures.decoys()
        return FakeFormDom(els)
    }

    /** 页面上「风格」这一格换成了输入框（下拉没了）：技能里的下拉步不许硬塞。 */
    private fun formWithStyleTurnedIntoInput(): FakeFormDom {
        val els = FormFixtures.elements().map { el ->
            if (el.nameAttr == "style") {
                FakeFormEl(tag = "INPUT", nameAttr = "style", label = "风格", id = "f-style", type = "text")
            } else {
                el
            }
        }.toMutableList()
        els += FormFixtures.decoys()
        return FakeFormDom(els)
    }

    // ── 正例：录一遍 ⇒ 五格全走 201 的填表器、回读一致 ────────────────────────

    @Test
    fun `正例_五步表单回放全走201填表器_回读一致且一个按钮都没点`() {
        val s = replay()
        assertTrue("回放应通过：第 ${s.report.failedStep} 步 ${s.report.stopReason}", s.report.ok)
        assertEquals(5, s.report.stepsDone)

        val outcomes = s.dom.formOutcomes
        assertEquals("五格都该留下 201 的回读结局", 5, outcomes.size)
        assertTrue("回读没过：" + outcomes.filter { !it.ok }.map { it.reason }, outcomes.all { it.ok })

        // 动手的是 201 的执行层，196 那条老 type 通道一次都没走
        assertEquals(5, s.form.fillCalls.size)
        assertTrue("回放不该再走 196 的 type：${s.page.typed}", s.page.typed.isEmpty())
        // 负例 1 的行为面：一个按钮都没点着、提交类控件一次都没被填
        assertTrue(s.page.clicks.isEmpty())
        assertTrue(s.form.poisonFilled.isEmpty())
        // 全绿 ⇒ 没触发过 228-C 的滚入视口重试
        assertTrue(s.report.retried.isEmpty())

        // 富文本保住三行（负例 3 的正半面）、下拉按选项表落成页面的 value
        assertEquals(3, s.form.elements.first { it.nameAttr == "persona" }.value.lines().size)
        assertEquals("1", s.form.elements.first { it.nameAttr == "style" }.value)
        assertEquals("河湾", s.form.elements.first { it.nameAttr == "worldbook[0][title]" }.value)
    }

    @Test
    fun `正例_结局带页面标签与控件类别_重复块带段号`() {
        val s = replay()
        val byKey = s.dom.formOutcomes.associateBy { it.key }
        // 认回模板的那几格带的是页面的标签（不是定位器原文），卡面才给人看得懂
        assertEquals("角色名", byKey.getValue("char_name").label)
        assertEquals(FormControl.RICH_TEXT, byKey.getValue("persona").control)
        assertEquals(FormControl.SELECT, byKey.getValue("style").control)
        // 重复块的第 1 段：键带段号（与 201 的模板口径同一份）
        assertEquals("worldbook1_title", byKey.getValue("worldbook1_title").key)
        assertEquals("worldbook1_content", byKey.getValue("worldbook1_content").key)
    }

    @Test
    fun `正例_换名字再跑一次仍对`() {
        val s = replay(charName = "小红")
        assertTrue("换值回放应通过：第 ${s.report.failedStep} 步 ${s.report.stopReason}", s.report.ok)
        assertEquals("小红", s.dom.formOutcomes.first { it.key == "char_name" }.expected)
        assertEquals("小红", s.form.elements.first { it.nameAttr == "char_name" }.value)
    }

    @Test
    fun `正例_下拉录的是页面value_回放照原值落`() {
        // 录制端 `change` 推的是 `el.value`（这里＝"1"），不是选项文案 ⇒ 这一支走的是真机上的形状
        val s = replay(styleValue = "1")
        assertTrue("回放应通过：第 ${s.report.failedStep} 步 ${s.report.stopReason}", s.report.ok)
        val style = s.dom.formOutcomes.first { it.key == "style" }
        assertEquals("1", style.expected)
        assertTrue(style.reason ?: "", style.ok)
        assertEquals("1", s.form.elements.first { it.nameAttr == "style" }.value)
    }

    // ── 负例 1：回放把「创建」点着了 ──────────────────────────────────────────

    @Test
    fun `负例1_页面上它变成了创建按钮_探针就拒且没有点击通道`() {
        val form = FormFixtures.dom()
        form.probeOverride = { el ->
            if (el.nameAttr == "char_name") {
                FormProbe(found = true, hitCount = 1, tag = "BUTTON", elemId = "btn-create", text = "创建")
            } else {
                null
            }
        }
        val s = replay(form)
        assertFalse("目标已长成提交按钮，这一步不许算成功", s.report.ok)
        val failed = s.dom.formOutcomes.first { !it.ok }
        assertTrue("原因得说清是提交类：${failed.reason}", failed.reason?.contains("提交") == true)
        // 结构面：表单端口压根没有点击通道（[FormDom] 四个方法里没 click），行为面：一次没点
        assertTrue(s.page.clicks.isEmpty())
        assertTrue(s.form.poisonFilled.isEmpty())
    }

    @Test
    fun `负例1_源码面_回放表单那一条没有点击`() {
        val src = File("src/main/java/com/example/zhengdao/baopu/TeachReplay.kt")
            .readText(Charsets.UTF_8).replace("\r\n", "\n")
        val decorator = src.substringAfter("internal class FormFillingTeachDom")
            .substringBefore("\n/**\n * 回放跑完")
        for (bad in listOf(".click(", "keyPress", "dispatchEvent", "document.querySelector")) {
            assertFalse("表单回放这一段不该出现「$bad」（点提交的念头在源码层就该写不出来）", decorator.contains(bad))
        }
        assertTrue("动手只许走 201 的填表器", decorator.contains("FormFiller("))
        assertTrue("判据只许走 201 的分类/预检", decorator.contains("TeachFormRules."))
        // 且不许自己再写一份填的动作（探针→填→回读那一套全在 FormFiller 里）
        assertFalse("回放这一段不许直接调 dom.fill", decorator.contains(".fill("))
    }

    // ── 负例 2：填进密码/验证码框 ─────────────────────────────────────────────

    @Test
    fun `负例2_探针说它是密码框_动手之前就拒`() {
        val form = FormFixtures.dom()
        form.probeOverride = { el ->
            if (el.nameAttr == "char_name") {
                FormProbe(found = true, hitCount = 1, tag = "INPUT", inputType = "password",
                    inputName = "char_name", elemId = "f-char-name", text = "角色名")
            } else {
                null
            }
        }
        val s = replay(form)
        assertFalse(s.report.ok)
        val failed = s.dom.formOutcomes.first { !it.ok }
        assertTrue("得报凭证类拒填：${failed.reason}", failed.reason?.contains("密码") == true)
        // 真没动手：这一格不在填过的名单里（探针就拦下了，不是填完才发现）
        assertTrue(s.form.fillCalls.isEmpty())
    }

    // ── 负例 3：富文本被塞成纯文本 ────────────────────────────────────────────

    @Test
    fun `负例3_页面把人设压成一行_该步判失败并带上行数`() {
        val s = replay(formWithPersonaFlattened())
        assertFalse("换行丢了就不许算填对", s.report.ok)
        val failed = s.dom.formOutcomes.first { !it.ok }
        assertEquals("persona", failed.key)
        assertTrue("得报富文本降级：${failed.reason}", failed.reason?.contains("富文本") == true)
    }

    // ── 负例 5：回读不一致却报「填好」 ────────────────────────────────────────

    @Test
    fun `负例5_回读读到别的值_这一步判失败并停在玄览`() {
        val form = FormFixtures.dom()
        form.readOverride = { el, _ -> if (el.nameAttr == "char_name") FormReadback("小红") else null }
        val s = replay(form)
        assertFalse(s.report.ok)
        assertEquals(1, s.report.failedStep)
        assertEquals(0, s.report.stepsDone)
        val failed = s.dom.formOutcomes.first { !it.ok }
        assertEquals("角色名", failed.label)
        assertEquals("回读不一致", failed.reason)
        assertEquals("小红", failed.actual)
        // 停在失败那一步：228-C 只把**这一格**再走一遍，后面的格子一个都没填（半填的表最难收拾）
        assertEquals(listOf("INPUT/char_name"), s.form.fillCalls.distinct())
    }

    @Test
    fun `负例5_动作报成功但值没落_也算没填对`() {
        val els = FormFixtures.elements().map { el ->
            if (el.nameAttr == "char_name") {
                FakeFormEl(tag = "INPUT", nameAttr = "char_name", label = "角色名", id = "f-char-name",
                    placeholder = "起个名字", type = "text", ignoreFill = true)
            } else {
                el
            }
        }.toMutableList()
        els += FormFixtures.decoys()
        val s = replay(FakeFormDom(els))
        assertFalse("填了也不存 ⇒ 不许报填好", s.report.ok)
        assertTrue(s.dom.formOutcomes.first { !it.ok }.reason?.contains("回读") == true)
    }

    // ── 形状变了不许猜（回放特有的第三种失败）────────────────────────────────

    @Test
    fun `形状变了_下拉那一格找不到了_停下不猜`() {
        val s = replay(formWithStyleTurnedIntoInput())
        assertFalse(s.report.ok)
        val failed = s.dom.formOutcomes.first { !it.ok }
        // 下拉整个没了 ⇒ 连「这一格是什么」都拿不到：标签只能退回定位器原文（不编一个好看的名字）
        assertEquals("combobox|风格", failed.label)
        assertTrue("得说清是找不到这一格：${failed.reason}", failed.reason?.contains("找不到这一格") == true)
        // 控件类别卡面上不印，但 FieldOutcome 要一项：拿不到页面事实时按这一步录的动作给名目
        assertEquals(FormControl.SELECT, failed.control)
        // 没往别处硬塞：下拉那一步一个字节都没动
        assertEquals("form 侧填过的格子：${s.form.fillCalls}", 2, s.form.fillCalls.size)
        assertFalse("不许填到 style 那一格", s.form.fillCalls.any { it.contains("style") })
        // 执行器那一侧同样收到原因串（这一步判失败、停在玄览、不猜）
        assertTrue("停机原因要带这一句：${s.report.stopReason}",
            s.report.stopReason?.contains("找不到这一格") == true)
        // 「不许藏」：这一格以红行进卡，卡不能看着像一张填完了的卡
        val card = requireNotNull(
            buildReplayPublishCard("制卡", s.dom.formOutcomes, s.report.evidenceDir, url, null, emptyMap()),
        )
        assertFalse(card.ok)
        assertEquals(1, card.badRows.size)
        assertEquals("combobox|风格", card.badRows.single().label)
    }

    @Test
    fun `形状变了_探针说这一格不是下拉_SELECT那步拒填`() {
        val form = FormFixtures.dom()
        // 扫描面认不回（自愈后换了定位器那一档）⇒ 走探针事实；探针说这一格是输入框
        form.probeOverride = { el ->
            if (el.nameAttr == "style") {
                FormProbe(found = true, hitCount = 1, tag = "INPUT", inputType = "text",
                    inputName = "style", elemId = "f-style", text = "风格")
            } else {
                null
            }
        }
        val s = replay(form, scanBlind = true, stepCount = 3)
        assertFalse(s.report.ok)
        val failed = s.dom.formOutcomes.first { !it.ok }
        assertTrue("得点明形状变了要重教：${failed.reason}", failed.reason?.contains("形状变了") == true)
        assertEquals("风格", failed.label)
        assertFalse("下拉那一步没动手", s.form.fillCalls.any { it.contains("style") })
    }

    @Test
    fun `认不回模板_退到探针事实_单行仍然填得进去`() {
        val s = replay(scanBlind = true, stepCount = 1)
        assertTrue("兜底那一条也该把格子填对：${s.report.stopReason}", s.report.ok)
        val only = s.dom.formOutcomes.single()
        assertTrue(only.ok)
        assertEquals("角色名", only.label) // 标签取的是探针带回来的页面事实
        assertEquals(listOf("INPUT/char_name"), s.form.fillCalls)
    }

    // ── 待发布卡（②的出口：填完给人看，最后一下人点）──────────────────────────

    @Test
    fun `卡_五格全过_带截图与回读证据`() {
        val s = replay()
        val card = buildReplayPublishCard(
            skillName = "制卡",
            outcomes = s.dom.formOutcomes,
            evidenceDir = s.report.evidenceDir,
            openUrl = s.dom.lastUrl.ifBlank { url },
            shotPath = "证道/截屏/核对-1.png",
            redactValues = emptyMap(),
        )
        val c = requireNotNull(card)
        assertTrue(c.ok)
        assertEquals(5, c.rows.size)
        assertTrue(c.rows.all { it.ok })
        assertEquals("证道/截屏/核对-1.png", c.shotPath)
        assertEquals(url, c.openUrl)
        // 全文五格都在，且没一处说「已经提交/发布」——最后一下是人点的
        for (v in listOf("小明", "古风", "河湾")) assertTrue(c.copyText.contains(v))
        val disk = File(s.report.evidenceDir!!, "待发布卡.txt").readText(Charsets.UTF_8)
        assertTrue(disk.contains("✓ 角色名 = 小明"))
        assertFalse(disk.contains("✗"))
        assertFalse("卡不许替用户宣布已提交：\n$disk", disk.contains("已提交") || disk.contains("已发布"))
    }

    @Test
    fun `卡_没截到图要明说_不许留空让人以为有图`() {
        val s = replay()
        val card = requireNotNull(
            buildReplayPublishCard("制卡", s.dom.formOutcomes, s.report.evidenceDir, url, null, emptyMap()),
        )
        assertNull(card.shotPath)
        assertTrue("得有一句「没取到截图」：${card.notes}", card.notes.any { it.contains("截图") })
    }

    @Test
    fun `卡_红行进卡并写进落盘文本`() {
        val form = FormFixtures.dom()
        form.readOverride = { el, _ -> if (el.nameAttr == "char_name") FormReadback("小红") else null }
        val s = replay(form)
        val card = requireNotNull(
            buildReplayPublishCard("制卡", s.dom.formOutcomes, s.report.evidenceDir, url, null, emptyMap()),
        )
        assertFalse(card.ok)
        assertEquals(1, card.badRows.size)
        val disk = File(s.report.evidenceDir!!, "待发布卡.txt").readText(Charsets.UTF_8)
        assertTrue(disk.contains("✗ 角色名 = 小明 —— 回读不一致"))
        assertTrue("有红行就得在注意事项里喊一声：${card.notes}", card.notes.any { it.contains("回读不一致") })
    }

    @Test
    fun `卡_落盘文本过脱敏_参数值不进盘`() {
        val s = replay()
        buildReplayPublishCard(
            skillName = "制卡",
            outcomes = s.dom.formOutcomes,
            evidenceDir = s.report.evidenceDir,
            openUrl = url,
            shotPath = null,
            redactValues = mapOf("char_name" to "小明"),
        )
        val disk = File(s.report.evidenceDir!!, "待发布卡.txt").readText(Charsets.UTF_8)
        assertFalse("参数值不许进盘：$disk", disk.contains("小明"))
        assertTrue(disk.contains("\${char_name}"))
        // 界面拿的是对象本身（用户要复制的就是那份原文），脱敏只发生在落盘那一份
        val card = requireNotNull(
            buildReplayPublishCard("制卡", s.dom.formOutcomes, null, url, null, mapOf("char_name" to "小明")),
        )
        assertTrue(card.copyText.contains("小明"))
    }

    @Test
    fun `卡_纯点选翻页的回放不编空卡`() {
        assertNull(
            buildReplayPublishCard("翻页", emptyList(), tmp.newFolder().absolutePath, url, null, emptyMap()),
        )
    }
}

/**
 * 「扫描面看不见这张页」的假端口：[FormFillingTeachDom.pickField] 认不回模板时才退到探针事实，
 * 这一档在页面扫得到的时候永远走不到，只能把扫描面蒙住才测得到（真机上对应重复块被扫描排除、
 * 或自愈后换了定位器）。填/探/读仍打同一份假页面。
 */
private class ScanBlindFormDom(delegate: FakeFormDom) : FormDom by delegate {
    override fun scan(): List<FormCandidate>? = null
}
