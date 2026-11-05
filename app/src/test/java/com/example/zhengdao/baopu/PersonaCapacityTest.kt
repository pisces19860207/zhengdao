// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十四批 ①（人设容量）的判据测试。
// 这一批的**红线是"不丢"**：上限放开只是让观众席变大，分段才是把整场演完 ——
// 所以这里断言的不是"切得好看"，而是"非空白字符逐字相等"和"切不开时明说拒收"。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.AppendPersonaTool
import com.example.zhengdao.baopu.agent.ToolOutcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PersonaCapacityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        PersonaCapacity.reset()
    }

    // ── 旋钮 ──

    @Test
    fun `默认上限是四千_不是八百`() {
        assertEquals(4_000, PersonaCapacity.DEFAULT_MAX_SECTION_CHARS)
        assertEquals(4_000, PersonaCapacity.maxSectionChars())
        // 464 的验收判据之一：单条上限只有**一个出口**——补丁规则必须转读同一个数
        assertEquals(PersonaCapacity.maxSectionChars(), PersonaPatchRules.MAX_SECTION_CHARS)
    }

    @Test
    fun `配置只收区间内_且判据读的是生效值`() {
        assertEquals("低于下端收到 200", 200, PersonaCapacity.configure(5))
        assertEquals("高于上端收到八千", 8_000, PersonaCapacity.configure(999_999))
        assertEquals("八百仍可配（老口径没被删掉，只是不再当默认）", 800, PersonaCapacity.configure(800))
        assertEquals(800, PersonaCapacity.maxSectionChars())
        assertEquals("判据读的也是生效值", 800, PersonaPatchRules.MAX_SECTION_CHARS)
        PersonaCapacity.reset()
        assertEquals("复位回默认", 4_000, PersonaCapacity.maxSectionChars())
    }

    @Test
    fun `整条补丁上限跟着单条走`() {
        // 464 前总长恒 2000：单条放开到 4000 而总长还留 2000 ⇒ 判据① 写在第二段就被砍。
        PersonaCapacity.configure(800)
        assertEquals(800 * PersonaPatchRules.MAX_SECTIONS + 32 * PersonaPatchRules.MAX_SECTIONS,
            PersonaCapacity.maxPatchChars())
        PersonaCapacity.configure(4_000)
        assertEquals(4_000 * PersonaPatchRules.MAX_SECTIONS + 32 * PersonaPatchRules.MAX_SECTIONS,
            PersonaCapacity.maxPatchChars())
        assertTrue("总长判据必须装得下三千字一条：${PersonaCapacity.maxPatchChars()}",
            PersonaCapacity.maxPatchChars() > 3_000)
    }

    // ── 判据①：三千字一次写下 ──

    @Test
    fun `三千字条款默认一次收下`() {
        val clause = clause(3_000)
        assertEquals("默认上限四千，三千字不该被拒", null, PersonaPatchRules.validate(patch(clause, "ws")))
        val segs = PersonaCapacity.splitWithinLimit(clause)!!
        assertEquals("装得下就是一段（不许为了好看硬切）", 1, segs.size)
        assertEquals("写成 1 段，共 3000 字", PersonaCapacity.receipt(segs))
    }

    @Test
    fun `判据一_三千字经唯一写口一次落盘_逐字不丢`() {
        val files = personaFiles()
        val ws = PersonaPatchApply.workspaceIdOf(files)
        val clause = clause(3_000)
        val r = PersonaPatchApply.apply(
            patch(clause, ws),
            PersonaConsent.WORKSPACE,
            ws,
            files,
            source = "工具",
        )
        assertTrue("三千字必须一次写下：$r", r is PersonaApplyResult.Applied)
        val body = files.projectFile().readText(Charsets.UTF_8)
        assertEquals("落盘就是那一条条款，一个字都不许丢", "## 规矩\n$clause", body)
        assertEquals(3_000, nonSpace(body).length - nonSpace("## 规矩").length)
    }

    // ── 分段：超长不丢 ──

    @Test
    fun `配成八百时长条款分段且非空白字符逐字不丢`() {
        val big = longClause()
        PersonaCapacity.configure(800)
        val segs = PersonaCapacity.splitWithinLimit(big)!!
        assertTrue("小标题块在八百上限下至少四段：${segs.size}", segs.size >= 4)
        segs.forEach { assertTrue("每段都不许超上限（${it.length}）", it.length <= 800) }
        assertEquals("切完拼回去，非空白字符逐字相等", nonSpace(big), nonSpace(segs.joinToString("\n\n")))
        assertTrue("回执要报段数与字数", PersonaCapacity.receipt(segs).matches(Regex("写成 \\d+ 段，共 \\d+ 字")))
    }

    @Test
    fun `切不开就按字符硬切_不许跳字`() {
        val runOn = "甲".repeat(2_500)
        val segs = PersonaCapacity.split(runOn, 800)
        assertEquals("一整串没有边界的东西也要分段装下", 4, segs.size)
        assertEquals("硬切也不许少一个字", 2_500, nonSpace(segs.joinToString("")).length)
    }

    @Test
    fun `小标题与空行优先成为切点`() {
        val text = "## 一\n" + "甲".repeat(300) + "\n\n## 二\n" + "乙".repeat(300) + "\n\n## 三\n" + "丙".repeat(300)
        val segs = PersonaCapacity.split(text, 400)
        assertEquals("按小标题切：一段一节", 3, segs.size)
        assertTrue("段段对齐标题：${segs.map { it.take(4) }}", segs.all { it.startsWith("##") })
        assertEquals(nonSpace(text), nonSpace(segs.joinToString("\n\n")))
    }

    @Test
    fun `段数封顶之外明说拒收_不静默截断`() {
        val huge = "段落内容。\n\n".repeat(PersonaCapacity.MAX_SEGMENTS * 40)
        PersonaCapacity.configure(200)
        assertNull(
            "超过 ${PersonaCapacity.MAX_SEGMENTS} 段 ⇒ null（调用方必须明说拒收）",
            PersonaCapacity.splitWithinLimit(huge),
        )
        assertEquals(
            "纯切分仍然一个字不丢（封顶拒的是整条，不是悄悄截一半）",
            nonSpace(huge),
            nonSpace(PersonaCapacity.split(huge, 200).joinToString("\n\n")),
        )
    }

    // ── 端到端：唯一写口的分段与回执 ──

    @Test
    fun `唯一写口分段落盘_一次写完不留半批`() {
        val files = personaFiles()
        val ws = PersonaPatchApply.workspaceIdOf(files)
        // 先写一条短的，让落点已存在 ⇒ 分段这次必须留下可回滚的备份（37 批那条不变式）
        val seed = PersonaPatchApply.apply(
            patch("先有一条：说话别绕。", ws),
            PersonaConsent.WORKSPACE,
            ws,
            files,
            source = "工具",
        )
        assertTrue("播种写入应成功：$seed", seed is PersonaApplyResult.Applied)
        val before = files.projectFile().readText(Charsets.UTF_8)

        PersonaCapacity.configure(800)
        val seg = PersonaPatchApply.applySegmented(
            patch(longClause(), ws),
            PersonaConsent.WORKSPACE,
            ws,
            files,
            source = "工具",
        )
        assertTrue("分段写入应成功：${seg.outcome}", seg.outcome is PersonaApplyResult.Applied)
        assertTrue("回执有段数：${seg.receipt()}", seg.segments.size >= 4)
        var expected = before
        for (s in seg.segments) {
            expected = PersonaPatchRules.render(expected, listOf(PersonaSection(PersonaSectionKind.RULES, s)))
        }
        assertEquals(
            "盘上内容必须逐字等于「原正文 + 按段依次追加」，段段都在",
            expected,
            files.projectFile().readText(Charsets.UTF_8),
        )
        assertEquals(nonSpace(longClause()), nonSpace(seg.segments.joinToString("\n\n")))
        assertTrue("写前要备份（可回滚）", PersonaBackup.backupsOf(files.projectFile()).isNotEmpty())
    }

    @Test
    fun `拒收时文件一个字节不动_话说清楚不假装写进去`() {
        val files = personaFiles()
        val ws = PersonaPatchApply.workspaceIdOf(files)
        val seed = PersonaPatchApply.apply(
            patch("先有一条：说话别绕。", ws),
            PersonaConsent.WORKSPACE,
            ws,
            files,
            source = "工具",
        )
        assertTrue("播种写入应成功：$seed", seed is PersonaApplyResult.Applied)
        val before = files.projectFile().readText(Charsets.UTF_8)

        PersonaCapacity.configure(200)
        // 每段最多 200 字 ⇒ 总量过 24×200 就**必然**撞段数封顶（不靠手调例子；下面先钉这条前提）
        val over = "块。\n\n".repeat(PersonaCapacity.MAX_SEGMENTS * 60)
        assertNull("这条得真的超段数上限，否则这例是空的", PersonaCapacity.splitWithinLimit(over))
        val seg = PersonaPatchApply.applySegmented(
            patch(over, ws),
            PersonaConsent.WORKSPACE,
            ws,
            files,
            source = "工具",
        )
        assertTrue("超段数上限必须 Rejected：${seg.outcome}", seg.outcome is PersonaApplyResult.Rejected)
        val why = (seg.outcome as PersonaApplyResult.Rejected).reason
        assertTrue("拒绝原因要明说没写入：$why", why.contains("没有写入"))
        assertEquals("拒收不该动文件半个字", before, files.projectFile().readText(Charsets.UTF_8))
        assertTrue("拒收不给回执段数（不许说「写成 0 段」这种糊弄话）", seg.segments.isEmpty())
    }

    @Test
    fun `工具回执把段数与字数说给用户`() {
        val files = personaFiles()
        PersonaCapacity.configure(800)
        val spec = AppendPersonaTool.spec(
            ask = { req -> req.options.first { it.id == "workspace" } },
            personaFiles = { files },
        )
        val out = spec.executor(org.json.JSONObject().put("text", longClause()).put("reason", "用户说过"))
        assertTrue("工具应成功：$out", out is ToolOutcome.Ok)
        val msg = (out as ToolOutcome.Ok).text
        assertTrue("回执要说清写成几段：$msg", msg.contains("写成") && msg.contains("段"))
        assertTrue("回执要说清共几字：$msg", msg.contains("共") && msg.contains("字"))
    }

    /** 判据⑥：截断提示沿用既有常量，本批不许另造一把。 */
    @Test
    fun `截断提示沿用 truncation_mark`() {
        val over = PersonaRules.assemble("内核", null, "正".repeat(9_000), tokensOf = { 8_001 })
        val seg = over.last { it.layer == PersonaLayer.C_PROJECT }
        assertTrue("超限层仍标截断", seg.truncated)
        assertTrue("用的还是那个常量", seg.text.endsWith(PersonaRules.TRUNCATION_MARK))
        assertEquals("截断可见这句话本身就是判据", "\n…（人设超长，已截断；完整内容在文件里）", PersonaRules.TRUNCATION_MARK)
    }

    // ── helpers ──

    /** 不带出厂兜底：盘上内容必须逐字等于渲染结果，判据① 的「不丢」才测得干净。 */
    private fun personaFiles(): PersonaFiles =
        PersonaFiles(tmp.newFolder("global"), tmp.newFolder("project"))

    private fun patch(text: String, workspaceId: String?) = PersonaPatch(
        scope = PersonaPatchScope.C_PROJECT,
        workspaceId = workspaceId,
        sections = listOf(PersonaSection(PersonaSectionKind.RULES, text)),
        reason = "容量测试",
    )

    /** 两千八百字上下的长条款：12 个小节、每节约二百三十字（真实长文的样子，不是一串同字）。 */
    private fun longClause(): String = buildString {
        for (i in 1..12) {
            append("## 第${i}节\n")
            append("这是第${i}节的规矩，".repeat(25)).append("\n\n")
        }
    }.trim()

    private fun clause(n: Int): String {
        val unit = "这条规矩要说清楚：先给结论，再给理由，不堆术语。"
        val sb = StringBuilder()
        while (sb.length < n) sb.append(unit)
        return sb.substring(0, n)
    }

    private fun nonSpace(s: String): String = s.filter { !it.isWhitespace() }
}
