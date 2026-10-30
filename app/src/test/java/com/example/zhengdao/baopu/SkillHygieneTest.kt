// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 289 · 技能库卫生的验收测试（判据见 `SkillHygiene`；用户口径原话钉在用例名上）：
//   正例：造 5 个近似技能 ⇒ 全被识别并给合并建议；归档后仍可恢复；阈值触发提醒；
//   负例：静默覆盖同名技能／归档变删除／只看数量不看是否被选中。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SkillHygieneTest {

    private val now = 1_760_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    // ── ① 重名／近似 ──────────────────────────────────────────────────────

    private fun line(name: String, scenario: String) =
        SkillRules.IndexLine(MemoryScope.PROJECT, name, scenario)

    @Test
    fun `H1_同名必中_SAME_NAME`() {
        val dup = SkillHygiene.findDuplicate(
            "整理书稿", "遇到拆章转码就按它做",
            listOf(line("整理书稿", "旧场景"), line("别的技能", "别的场景")),
        )
        assertNotNull(dup)
        assertEquals(SkillHygiene.DuplicateKind.SAME_NAME, dup!!.kind)
        assertEquals("整理书稿", dup.existingName)
    }

    @Test
    fun `H2_名字互含_V2后缀也算近似`() {
        val dup = SkillHygiene.findDuplicate(
            "整理书稿V2", "场景随便",
            listOf(line("整理书稿", "旧场景")),
        )
        assertNotNull("改个 V2 后缀不许绕过查重", dup)
        assertEquals(SkillHygiene.DuplicateKind.SIMILAR_NAME, dup!!.kind)
    }

    @Test
    fun `H3_长名_bigram相似也算近似`() {
        val dup = SkillHygiene.findDuplicate(
            "网络平台表单填写", "场景随便",
            listOf(line("网络平台表单填写指南", "旧场景")),
        )
        assertNotNull(dup)
        assertEquals(SkillHygiene.DuplicateKind.SIMILAR_NAME, dup!!.kind)
    }

    @Test
    fun `H4_不同名但场景几乎一样_也算近似`() {
        val scenario = "遇到拆章转编码导出书稿类任务先按步骤拆解再逐段处理最后合卷检查"
        val dup = SkillHygiene.findDuplicate(
            "拆章转码", scenario,
            listOf(line("书稿整理", scenario)), // 场景逐字相同 ⇒ 相似度 1.0
        )
        assertNotNull(dup)
        assertEquals(SkillHygiene.DuplicateKind.SIMILAR_SCENARIO, dup!!.kind)
    }

    /** 用户判据原文：**造 5 个近似技能 ⇒ 全被识别并给合并建议**。 */
    @Test
    fun `H5_五个近似技能提案_全部命中并给出升级新建二选一`() {
        val library = listOf(
            line("整理书稿", "遇到拆章转编码就按步骤做"),
            line("平台表单填写", "网页表单逐字段填写并保存"),
            line("导出备份", "把工作区打成压缩包留档"),
            line("检查链接", "逐个检查文档里的外链是否可达"),
            line("批量改名", "按规则给一组文件统一改名"),
        )
        val proposals = listOf(
            // 1 同名
            "整理书稿" to "换个说法的拆章流程",
            // 2 名字互含（V2 后缀）
            "整理书稿V2" to "拆章流程升级版",
            // 3 场景几乎一样（不同名）
            "拆章转码" to "遇到拆章转编码就按步骤做",
            // 4 名字 bigram 相似
            "平台表单填写指南" to "网页表单逐字段填写并勾选必填项",
            // 5 名字互含（二）
            "批量改名二" to "统一改名的另一套规则",
        )
        val hits = proposals.mapNotNull { (name, scenario) ->
            SkillHygiene.findDuplicate(name, scenario, library)
        }
        assertEquals("5 个近似提案必须全部被识别", 5, hits.size)
        hits.forEach { dup ->
            val g = SkillHygiene.duplicateGuidance(dup, SkillHygiene.alternativeName(dup.existingName, setOf(dup.existingName)))
            assertTrue("每条建议都要给二选一：$g", g.contains("升级") && g.contains("新建"))
            assertTrue("建议里要带一个能用的名字：$g", g.contains("建议「"))
        }
    }

    @Test
    fun `H6_没有近似就不报_空索引不报`() {
        assertNull(SkillHygiene.findDuplicate("全新技能", "完全不同的场景", listOf(line("别的", "别的场景"))))
        assertNull(SkillHygiene.findDuplicate("全新技能", "场景", emptyList()))
    }

    @Test
    fun `H7_新建建议名_躲开被占的_撞满截断也有解`() {
        assertEquals("整理书稿-2", SkillHygiene.alternativeName("整理书稿", setOf("整理书稿")))
        assertEquals(
            "整理书稿-3",
            SkillHygiene.alternativeName("整理书稿", setOf("整理书稿", "整理书稿-2")),
        )
        val long = "很长的技能名字共三十二个字顶到名字上限的位置哦".let { it + it.take(32 - it.length) }
        assertEquals(32, long.length)
        val alt = SkillHygiene.alternativeName(long, setOf(long))
        assertNotNull("顶格名字也要能给出新建名", alt)
        assertTrue(SkillStore.sanitizeName(alt!!) == alt)
        assertTrue(alt.length <= SkillStore.NAME_MAX_CHARS)
    }

    // ── ② 长期不用 ⇒ 归档建议（不删） ─────────────────────────────────────

    @Test
    fun `H8_久未用建议归档_老失败建议重验_证据不够不判`() {
        val stats = listOf(
            // 15 天没用：建议归档，理由要能看懂
            SkillHygiene.UsageStat("闲置技能", lastUsedAtMs = now - (SkillRefine.SkillExpiry.STALE_DAYS + 1) * day),
            // 刚用但 5 连败：建议重验，不是归档
            SkillHygiene.UsageStat("老失败", lastUsedAtMs = now - day, recentOks = List(5) { false }),
            // 又久又败：归档
            SkillHygiene.UsageStat(
                "又久又败",
                lastUsedAtMs = now - (SkillRefine.SkillExpiry.STALE_DAYS + 1) * day,
                recentOks = List(5) { false },
            ),
            // 刚用且成过：不提
            SkillHygiene.UsageStat("健康技能", lastUsedAtMs = now - day, recentOks = List(5) { true }),
            // 记录上线后没用过：没有证据，不冤枉
            SkillHygiene.UsageStat("没记录", lastUsedAtMs = null),
        )
        val out = SkillHygiene.hygieneSuggestions(stats, nowMs = now)
        val byName = out.associateBy { it.name }
        assertEquals(SkillHygiene.SuggestAction.ARCHIVE, byName.getValue("闲置技能").action)
        assertTrue(byName.getValue("闲置技能").reason.contains("14 天"))
        assertEquals(SkillHygiene.SuggestAction.RECHECK, byName.getValue("老失败").action)
        assertEquals(SkillHygiene.SuggestAction.ARCHIVE, byName.getValue("又久又败").action)
        assertFalse("健康的与没证据的都不许被点名", out.any { it.name == "健康技能" || it.name == "没记录" })
    }

    /** 负例「归档变删除」的文件层钉子：归档＝改名保留，原名不在了但原件随时能改回来。 */
    @Test
    fun `H15_归档是改名不是删除_原件可恢复`() {
        val root = File.createTempFile("zd289-arch", "").let { it.delete(); it.mkdirs(); it }
        try {
            val f = File(File(root, "技能/闲置技能"), "SKILL.md")
            f.parentFile!!.mkdirs()
            val original = "闲置技能的正文"
            f.writeText(original, Charsets.UTF_8)
            val r = SkillStore.archive(f, stamp = "stamp-1")
            assertTrue(r is SkillWriteResult.Written)
            assertFalse("本名不在了（不再被索引/展示）", f.isFile)
            val gone = File(File(root, "技能/闲置技能"), "SKILL.md.deleted-stamp-1")
            assertTrue("改名件必须留在原地", gone.isFile)
            assertEquals("原件一个字节不动", original, gone.readText(Charsets.UTF_8))
            // 恢复＝改回来（UI 的「恢复」动作就走这一步）
            assertTrue(gone.renameTo(f))
            assertEquals(original, f.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    // ── ③ 总量阈值 ────────────────────────────────────────────────────────

    @Test
    fun `H9_到阈值提醒_没到不提醒`() {
        assertNull("199 条不该吵", SkillHygiene.totalReminder(199))
        val msg = SkillHygiene.totalReminder(200)
        assertNotNull("200 条要提醒整理", msg)
        assertTrue(msg!!.contains("200"))
        assertTrue("提醒要给出路（归档可恢复），不能光报警", msg.contains("归档"))
        assertNotNull(SkillHygiene.totalReminder(300))
    }

    // ── ④ 命中率 ──────────────────────────────────────────────────────────

    @Test
    fun `H10_检索数按精确尾段匹配_名字带下划线不吃亏`() {
        val paths = listOf(
            "/data/技能/批量_改名/SKILL.md",
            "/data/技能/批量_改名/SKILL.md", // 去重由 DISTINCT 做，这里数原始行
            "/mnt/global/技能/批量_改名/SKILL.md",
            "/data/技能/别的技能/SKILL.md",
            "/data/技能/批量_改名/SKILL.md.deleted-x", // 退役件不算检索
            "/data/别的目录/批量_改名/SKILL.md", // 不在技能目录下不算
        )
        assertEquals(3, SkillHygiene.retrievedCountOf("批量_改名", paths))
        assertEquals(1, SkillHygiene.retrievedCountOf("别的技能", paths))
        assertEquals(0, SkillHygiene.retrievedCountOf("不存在", paths))
    }

    @Test
    fun `H10b_三个数全是0不占行_有数才显示`() {
        assertEquals("", SkillHygiene.HitRate(0, 0, 0).text())
        assertEquals("检索 3 · 用 2 · 成 1", SkillHygiene.HitRate(3, 2, 1).text())
    }

    // ── 接线：plan 的探查（给不给既有索引，行为分两路） ─────────────────────

    @Test
    fun `H11_plan_喂了既有索引就报重名_没喂就查不出（老调用方零影响）`() {
        val root = File.createTempFile("zd289-plan", "").let { it.delete(); it.mkdirs(); it }
        try {
            val p = SkillProposal(SkillScope.PROJECT, "整理书稿", "正文", "理由", draft = true)
            val withLines = SkillStore.plan(
                p, root, root, "s",
                existingLines = listOf(line("整理书稿", "旧场景")),
            )
            assertNotNull(withLines.duplicate)
            assertEquals(SkillHygiene.DuplicateKind.SAME_NAME, withLines.duplicate!!.kind)
            val without = SkillStore.plan(p, root, root, "s")
            assertNull("不喂索引 ⇒ 维持旧行为（查不出），既有调用方一行不改", without.duplicate)
        } finally {
            root.deleteRecursively()
        }
    }

    // ── 免批通道负例（静默覆盖／近似堆叠都不许AUTO） ─────────────────────────

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        projectRoot = File.createTempFile("zd289-proj", "").let { it.delete(); it.mkdirs(); it }
        globalRoot = File.createTempFile("zd289-glob", "").let { it.delete(); it.mkdirs(); it }
    }

    private fun files() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private fun skillDir() = SkillRules.projectSkillDir(projectRoot)

    private fun tip(
        name: String = "小技巧",
        body: String = "先把必填字段填完，再点保存。看到「成功」两个字才算成。",
    ) = SkillProposal(scope = SkillScope.PROJECT, name = name, body = body, reason = "学到的小技巧", draft = true)

    private fun auto(p: SkillProposal): SkillAutoOutcome = SkillAutoWrite.tryAutoWrite(
        proposal = p,
        projectRoot = projectRoot,
        globalRoot = globalRoot,
        personaFiles = files(),
        stamp = "20261030-180000",
    )

    private fun seedIndex(layer: PersonaLayer, line: SkillRules.IndexLine) {
        val pf = files()
        val base = pf.read(layer).orEmpty().trim()
        val proposed =
            if (base.isEmpty()) SkillRules.renderIndexLine(line) + "\n" else base + "\n" + SkillRules.renderIndexLine(line) + "\n"
        val pr = PersonaRules.propose(layer, base, proposed, "289 夹具：灌索引")
        assertNotNull("夹具要能写人设 $layer", pr)
        assertTrue("夹具写盘要成", pf.applyProposal(pr!!))
    }

    @Test
    fun `H12_近似重复是新增否决_命中即确认_不命中不误伤`() {
        val nc = SkillWritePolicy.decide(
            tip(),
            SkillWriteContext(nearDuplicateOf = "整理书稿"),
        )
        assertTrue(nc is SkillWriteDecision.NeedsConfirm)
        assertEquals(SkillWriteVeto.NEAR_DUPLICATE, (nc as SkillWriteDecision.NeedsConfirm).veto)
        val clean = SkillWritePolicy.decide(tip(), SkillWriteContext())
        assertEquals(SkillWriteDecision.Auto, clean)
    }

    /** 负例「静默覆盖同名技能」：AUTO 通道撞同名 ⇒ 必须确认，原件一字不动，话里带二选一。 */
    @Test
    fun `H13_同名已有_AUTO不许静默覆盖_理由带二选一`() {
        val existing = SkillProposal(
            SkillScope.PROJECT, "已有技能", "旧正文，别动它", "用户的底座", draft = false,
        )
        assertTrue(SkillStore.apply(SkillStore.plan(existing, projectRoot, globalRoot, "s1"), files(), "")
            is SkillWriteResult.Written)
        val file = File(skillDir(), "已有技能/${SkillRules.SKILL_FILE}")
        val before = file.readText(Charsets.UTF_8)
        val out = auto(tip(name = "已有技能", body = "新正文，想覆盖"))
        val nc = out as? SkillAutoOutcome.NeedsConfirm
        assertNotNull("同名必须 NEEDS_CONFIRM：$out", nc)
        assertEquals("原件一个字节都不许动", before, file.readText(Charsets.UTF_8))
        val reason = nc!!.decision.reason
        assertTrue("否决理由要给「升级／新建」二选一：$reason", reason.contains("升级") && reason.contains("新建"))
        assertTrue("要给出建议名：$reason", reason.contains("已有技能-2"))
    }

    /** 负例「近似技能悄悄并排堆」：不同名但高度近似 ⇒ AUTO 拒，盘上不许长出新目录。 */
    @Test
    fun `H14_近似技能_AUTO拒绝并排堆_盘上无新目录`() {
        seedIndex(PersonaLayer.C_PROJECT, line("整理书稿", "遇到拆章转编码就按步骤做"))
        val out = auto(tip(name = "整理书稿V2", body = "拆章流程的升级版：先拆章，再转码，最后合卷。"))
        val nc = out as? SkillAutoOutcome.NeedsConfirm
        assertNotNull("近似必须 NEEDS_CONFIRM：$out", nc)
        assertEquals(SkillWriteVeto.NEAR_DUPLICATE, nc!!.decision.veto)
        assertFalse(File(skillDir(), "整理书稿V2").exists())
        assertTrue(nc.decision.reason.contains("升级") && nc.decision.reason.contains("新建"))
    }
}
