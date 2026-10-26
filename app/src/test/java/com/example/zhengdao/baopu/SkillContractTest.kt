// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十三批 A 段判据 · 契约的形状（派工单 §1 三条规则 ＋ §4 负例②「未知 kind 被接受」、⑦「解析带副作用」）。
//
// 这一层全是纯函数，所以**没有一条用例需要临时目录、需要时钟、需要 Android**——
// 「需要」本身就是负例⑦的靶子：C17 拿源码门禁钉住这个文件不引任何 IO/时钟。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SkillContractTest {

    private val title = "# 装车核对"

    private val contractLines = listOf(
        "<!-- contract appliesWhen url_domain=example.com -->",
        "<!-- contract appliesWhen page_has=文案「装车单」 -->",
        "<!-- contract success element_appears=文案「装车状态」 -->",
        "<!-- contract success text_nonempty=结果区 -->",
        "<!-- contract protected 不点提交/保存/支付类按钮 -->",
        "<!-- contract protected 不改原件区文件 -->",
    )

    private val prose = listOf("", "## 步骤", "1. 打开装车单列表", "2. 核对装车状态")

    /** 「标题 + 契约行 + 人话正文」形状的 SKILL.md（[extra] 是插在标题后的行）。 */
    private fun body(vararg extra: String): String =
        (listOf(title) + extra.toList() + prose).joinToString("\n")

    private val completeBody: String = body(*contractLines.toTypedArray())

    private fun parse(body: String): SkillContract.Parsed = SkillContract.parse(body)

    private fun contractOf(body: String): SkillContract.Contract =
        (SkillContract.parse(body) as SkillContract.Parsed.Contracted).contract

    // ── §1 规则 1＋2：三段都读得到，闭集之外的一个字都不收 ──────────────────

    @Test
    fun `C1_齐契约_三段一条不落地读到`() {
        val parsed = parse(completeBody)
        assertTrue("应当读成契约，实际：${SkillContract.describe(parsed)}", parsed is SkillContract.Parsed.Contracted)
        val c = (parsed as SkillContract.Parsed.Contracted).contract
        assertEquals(2, c.appliesWhen.size)
        assertEquals(SkillContract.ConditionKind.URL_DOMAIN, c.appliesWhen[0].kind)
        assertEquals("example.com", c.appliesWhen[0].value)
        assertEquals(SkillContract.ConditionKind.PAGE_HAS, c.appliesWhen[1].kind)
        assertEquals("文案「装车单」", c.appliesWhen[1].value)
        assertEquals(2, c.success.size)
        assertEquals(SkillContract.SuccessKind.ELEMENT_APPEARS, c.success[0].kind)
        assertEquals(SkillContract.SuccessKind.TEXT_NONEMPTY, c.success[1].kind)
        assertEquals("结果区", c.success[1].value)
        assertEquals(listOf("不点提交/保存/支付类按钮", "不改原件区文件"), c.protectedClauses)
        assertTrue("三段都有才算齐", parsed.complete)
        assertTrue(parsed.missingSections.isEmpty())
        assertTrue(SkillContract.describe(parsed).startsWith("契约齐"))
    }

    @Test
    fun `C2_一条契约都没有_Absent并明写contract_missing`() {
        val parsed = parse(body())
        assertEquals(SkillContract.Parsed.Absent, parsed)
        assertTrue(SkillContract.describe(parsed).contains("contract_missing"))
        // 草稿机器头不是契约行（否则每条草稿都"自带契约"，负例①就没路走红了）
        assertEquals(SkillContract.Parsed.Absent, parse("${SkillStore.draftHeader()}\n$title"))
    }

    @Test
    fun `C2b_技能页的短标签四种状态都有字`() {
        assertEquals("缺", SkillContract.shortLabel(parse(body())))
        assertEquals("齐", SkillContract.shortLabel(parse(completeBody)))
        assertEquals(
            "缺 protected",
            SkillContract.shortLabel(parse(body(
                "<!-- contract appliesWhen url_domain=example.com -->",
                "<!-- contract success element_appears=文案「状态」 -->",
            ))),
        )
        assertEquals("读不成", SkillContract.shortLabel(parse(body("<!-- contract appliesWhen browser=chrome -->"))))
    }

    @Test
    fun `C2c_validate把能不能用压成一张问题清单`() {
        // 派工单 §1 规则 3 点名的口：parse 管读法，validate 管验收
        assertTrue(SkillContract.validate(completeBody).isEmpty())
        assertEquals(listOf("一条契约都没有（contract_missing）"), SkillContract.validate(body()))
        val missing = SkillContract.validate(body(
            "<!-- contract appliesWhen url_domain=example.com -->",
            "<!-- contract success element_appears=文案「状态」 -->",
        ))
        assertEquals(missing.toString(), listOf("缺 protected 段"), missing)
        val broken = SkillContract.validate(body("<!-- contract appliesWhen browser=chrome -->"))
        assertEquals(1, broken.size)
        assertTrue(broken.single(), broken.single().contains("未知的适用条件类型"))
        // 验收与适配评估不各说各话：契约齐 ⇔ 这条不落进「契约未知」那一档（读数另算）
        listOf(completeBody, body(), body("<!-- contract appliesWhen browser=chrome -->")).forEach { b ->
            val r = fitOf(b)
            assertEquals(
                "validate 与适配评估对不上：${SkillContract.validate(b)} / $r",
                SkillContract.validate(b).isEmpty(),
                r.fit != SkillFit.UNKNOWN_CONTRACT,
            )
        }
    }

    private fun fitOf(text: String): SkillFitResult =
        SkillApplicability.assess("装车核对", text, 0, SkillContext(url = "https://example.com"))

    @Test
    fun `C3_未知适用类型_拒收且不兜默认值`() {
        val parsed = parse(body("<!-- contract appliesWhen url_host=example.com -->"))
        assertTrue("未知类型必须拒收：${SkillContract.describe(parsed)}", parsed is SkillContract.Parsed.Invalid)
        val err = (parsed as SkillContract.Parsed.Invalid).errors.single()
        assertTrue(err, err.contains("未知的适用条件类型"))
        // 合法清单要一起说给人看（只说"不合法"等于让人猜）
        listOf("url_domain", "page_has", "workspace_has").forEach { assertTrue(err, err.contains(it)) }
    }

    @Test
    fun `C4_未知成功判据类型_拒收`() {
        val parsed = parse(body("<!-- contract success model_says=装车完成了 -->"))
        assertTrue(parsed is SkillContract.Parsed.Invalid)
        val err = (parsed as SkillContract.Parsed.Invalid).errors.single()
        assertTrue(err, err.contains("未知的成功判据类型"))
        assertTrue(err, err.contains("element_appears") && err.contains("text_nonempty"))
    }

    @Test
    fun `C5_未知段名_拒收`() {
        val parsed = parse(body("<!-- contract appliesIf url_domain=example.com -->"))
        assertTrue(parsed is SkillContract.Parsed.Invalid)
        val err = (parsed as SkillContract.Parsed.Invalid).errors.single()
        assertTrue(err, err.contains("不认得契约段"))
        listOf("appliesWhen", "success", "protected").forEach { assertTrue(err, err.contains(it)) }
    }

    @Test
    fun `C6_少等号_值为空_空契约行_都算读不成`() {
        val cases = listOf(
            "<!-- contract appliesWhen url_domain -->" to "少了「类型=值」",
            "<!-- contract appliesWhen url_domain= -->" to "值是空的",
            "<!-- contract success element_appears= -->" to "值是空的",
            "<!-- contract protected -->" to "受保护约束要写成人话一句",
            "<!-- contract -->" to "契约行是空的",
        )
        for ((line, want) in cases) {
            val parsed = parse(body(line))
            assertTrue("$line ⇒ ${SkillContract.describe(parsed)}", parsed is SkillContract.Parsed.Invalid)
            val err = (parsed as SkillContract.Parsed.Invalid).errors.joinToString("；")
            assertTrue("$line ⇒ $err", err.contains(want))
        }
    }

    @Test
    fun `C7_几条错误一次列全_不在第一处就返回`() {
        val parsed = parse(
            body(
                "<!-- contract appliesWhen browser_says=chrome -->",
                "<!-- contract success text_nonempty= -->",
                "<!-- contract wrongSection 一句话 -->",
            ),
        )
        assertTrue(parsed is SkillContract.Parsed.Invalid)
        val errs = (parsed as SkillContract.Parsed.Invalid).errors
        assertEquals("三处错要一次说完：$errs", 3, errs.size)
        // 每条都指到第几行（标题占第 1 行，契约行从第 2 行起）
        listOf("第 2 行", "第 3 行", "第 4 行").forEach { line ->
            assertTrue(errs.joinToString("；"), errs.any { it.contains(line) })
        }
    }

    // ── §1「success 必须可观察」：结论词写进值里也拒收（§4 负例④的解析侧）──────

    @Test
    fun `C8_成功判据写成结论词_一律拒收`() {
        for (v in listOf("完成", "成功了", "成功", "好了", "搞定", "结束", "执行完毕", "done", "OK", "success")) {
            val parsed = parse(body("<!-- contract success element_appears=$v -->"))
            assertTrue("「$v」不是可观察判据，必须拒收：${SkillContract.describe(parsed)}",
                parsed is SkillContract.Parsed.Invalid)
            val err = (parsed as SkillContract.Parsed.Invalid).errors.single()
            assertTrue(err, err.contains("不是可观察判据"))
        }
    }

    @Test
    fun `C9_带引号的真文案不误伤`() {
        // 页面上真有四字「审核通过」⇒ "通过"这类结论词表不能把它一起掐掉
        val c = contractOf(body("<!-- contract success element_appears=文案「审核通过」 -->"))
        assertEquals(SkillContract.SuccessKind.ELEMENT_APPEARS, c.success.single().kind)
    }

    @Test
    fun `C10_url_domain的值必须像域名_其余两种允许空格与中文`() {
        for (bad in listOf("example", ".example.com", "example.", "http://example.com", "exa mple.com")) {
            val parsed = parse(body("<!-- contract appliesWhen url_domain=$bad -->"))
            assertTrue("$bad 不该当域名收下：${SkillContract.describe(parsed)}", parsed is SkillContract.Parsed.Invalid)
        }
        // 大小写归一（域名本来就大小写不敏感）
        assertEquals("example.com", contractOf(body("<!-- contract appliesWhen url_domain=Example.COM -->")).appliesWhen.single().value)
        val c = contractOf(body("<!-- contract appliesWhen page_has=配 货 单.xlsx -->"))
        assertEquals("配 货 单.xlsx", c.appliesWhen.single().value)
    }

    @Test
    fun `C11_只缺一段_读得成但不齐`() {
        val parsed = parse(
            body(
                "<!-- contract appliesWhen url_domain=example.com -->",
                "<!-- contract protected 不点提交按钮 -->",
            ),
        )
        assertTrue(parsed is SkillContract.Parsed.Contracted)
        val c = parsed as SkillContract.Parsed.Contracted
        assertFalse("缺 success 就不是齐", c.complete)
        assertEquals(listOf(SkillContract.SECTION_SUCCESS), c.missingSections)
        assertTrue(SkillContract.describe(c).contains("契约不全"))
    }

    @Test
    fun `C12_重复的契约行只算一条`() {
        val c = contractOf(body(*contractLines.toTypedArray(), contractLines[0], contractLines[4]))
        assertEquals(2, c.appliesWhen.size)
        assertEquals(2, c.protectedClauses.size)
    }

    @Test
    fun `C13_值里的注释收尾串不许把行拆坏`() {
        val line = "<!-- contract protected 不改原件 --> 区文件 -->"
        val c = contractOf(body(line))
        val clause = c.protectedClauses.single()
        assertFalse(clause, clause.contains("-->"))
        assertTrue(clause, clause.contains("不改原件") && clause.contains("区文件"))
    }

    // ── 渲染／剥离／回写（C 段门槛拿它比字节）──────────────────────────────

    @Test
    fun `C14_render与parse互逆_渲染顺序固定`() {
        val c = contractOf(completeBody)
        val rendered = SkillContract.render(c)
        assertEquals(6, rendered.size)
        assertTrue(rendered.first().contains(SkillContract.SECTION_APPLIES))
        assertTrue(rendered[2].contains(SkillContract.SECTION_SUCCESS))
        assertTrue(rendered.last().contains(SkillContract.SECTION_PROTECTED))
        assertEquals(c, contractOf(body(*rendered.toTypedArray())))
        assertEquals(rendered, SkillContract.render(c))
    }

    @Test
    fun `C15_strip只去契约行_人话正文一个字不动`() {
        val stripped = SkillContract.strip(completeBody)
        assertFalse(stripped.contains(SkillContract.MARK))
        assertEquals(listOf(title) + prose, stripped.lines())
        // 草稿机器头留在原位（strip 之后仍交给 SkillStore 的头/正文那套管法）
        val draft = SkillStore.draftHeader() + "\n" + completeBody
        assertEquals(listOf(SkillStore.draftHeader(), title) + prose, SkillContract.strip(draft).lines())
    }

    @Test
    fun `C16_契约落在标题之后_索引场景与卡片编辑都不吞它`() {
        val c = contractOf(completeBody)
        val fresh = SkillContract.withContract(listOf(title, "1. 打开装车单").joinToString("\n"), c)
        // ① 索引「场景」取第一条非注释行 ⇒ 仍是标题（贴 YAML 头会把它写成 "appliesWhen:"）
        assertEquals("装车核对", SkillStore.purposeOf(fresh))
        // ② 卡片上可编辑的正文剥的是**开头连续的注释行** ⇒ 契约跟在标题后，不会被剥掉
        assertEquals(6, contractLineCount(SkillStore.contentWithoutHeader(fresh)))
        // ③ 草稿头加在最前面也不影响：回写时头重算，契约段仍留在标题之后
        val withHeader = SkillStore.draftHeader() + "\n" + fresh
        assertEquals("装车核对", SkillStore.purposeOf(withHeader))
        assertEquals(6, contractLineCount(SkillStore.contentWithoutHeader(withHeader)))
        // ④ 幂等：已带契约的正文再回写一次，条数与内容都不变
        val again = SkillContract.withContract(fresh, c)
        assertEquals(6, contractLineCount(again))
        assertEquals(c, contractOf(again))
    }

    private fun contractLineCount(text: String): Int =
        text.lines().count { it.trim().startsWith("<!-- ${SkillContract.MARK}") }

    @Test
    fun `C16b_正文是空的也能补契约`() {
        val c = contractOf(completeBody)
        val fresh = SkillContract.withContract("", c)
        assertEquals(SkillContract.render(c), fresh.lines())
        assertEquals(c, contractOf(fresh))
        // 全是注释的正文（只有草稿头）⇒ 头留在原位，契约追加其后
        assertEquals(listOf(SkillStore.draftHeader()) + SkillContract.render(c),
            SkillContract.withContract(SkillStore.draftHeader(), c).lines())
    }

    // ── §4 负例⑦：解析带副作用——这条路在本文件字面上不存在 ──────────────────

    @Test
    fun `C17_契约层零IO零时钟零Android_且同一输入同一输出`() {
        val src = File("src/main/java/com/example/zhengdao/baopu/SkillContract.kt")
            .let { if (it.isFile) it else File("app/src/main/java/com/example/zhengdao/baopu/SkillContract.kt") }
            .readText()
        assertFalse("纯函数层不许任何 import（连 java.util 都不必）",
            Regex("""^import\s""", RegexOption.MULTILINE).containsMatchIn(src))
        for (banned in listOf(
            "System.currentTimeMillis", "SystemClock", "Date(", "Instant", "LocalDate",
            "Random", "UUID", "File(", "readText", "writeText", "RunLog", "android.",
        )) {
            val hits = src.lines().filter {
                it.contains(banned) && !it.trim().startsWith("//") && !it.trim().startsWith("*") && !it.trim().startsWith("/*")
            }
            assertTrue("契约层出现副作用原语：$banned ⇒ ${hits.firstOrNull()}", hits.isEmpty())
        }
        // 同输入两次读必须一字不差（真随机／可变状态在这里没有藏身处）
        assertEquals(parse(completeBody), parse(completeBody))
        assertEquals(parse(body()), parse(body()))
        assertEquals(SkillContract.strip(completeBody), SkillContract.strip(completeBody))
    }
}
