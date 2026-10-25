// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第一百九十二批 §1–§3（2026-10-24 用户拍板：「小技巧这类只写项目技能、纯流程、
// 可撤销的，不用每次问；其余照旧问」）。
//
// 结构：
//   - P1 判据表：**一个用例表覆盖五条 × 六个否决的组合**（派工单 §6 硬要求：不许散装 if）；
//   - P2–P4 端到端：真临时目录 ＋ 真 `PersonaFiles`，AUTO 落盘、审计免批行、名单识别；
//   - N1–N8 八条负例，一条一个测试（N6「免批直接转正」、N7「撤销后索引残留/审计丢失」是必红红线）；
//   - 源码级铁律：免批壳自己不写正文文件、只许碰 C 层（N8）。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SkillWritePolicyTest {

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        projectRoot = File.createTempFile("zd-swp-proj", "").let { it.delete(); it.mkdirs(); it }
        globalRoot = File.createTempFile("zd-swp-glob", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
        globalRoot.deleteRecursively()
    }

    private fun files() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private fun skillDir() = SkillRules.projectSkillDir(projectRoot)

    /** 一条「够格的小技巧」（五条全满足的基线提案；各负例只改一个维度）。 */
    private fun tip(
        name: String = "小技巧",
        body: String = "先把必填字段填完，再点保存。看到「成功」两个字才算成。",
        scope: SkillScope = SkillScope.PROJECT,
        draft: Boolean = true,
    ) = SkillProposal(scope = scope, name = name, body = body, reason = "学到的小技巧", draft = draft)

    private fun auto(
        p: SkillProposal,
        stamp: String = "20261025-090000",
        source: SkillAutoSource? = null,
        nowMs: Long = 1_000L,
    ): SkillAutoOutcome = SkillAutoWrite.tryAutoWrite(
        proposal = p,
        projectRoot = projectRoot,
        globalRoot = globalRoot,
        personaFiles = files(),
        stamp = stamp,
        source = source,
        nowMs = nowMs,
    )

    /** 往一层人设灌一行索引（夹具；走合法写口，不手写路径；多次调用是累加不是覆盖）。 */
    private fun seedIndex(layer: PersonaLayer, line: SkillRules.IndexLine) {
        val base = files().read(layer).orEmpty().trim()
        val proposed = if (base.isEmpty()) SkillRules.renderIndexLine(line) + "\n" else base + "\n" + SkillRules.renderIndexLine(line) + "\n"
        val p = PersonaRules.propose(layer, base, proposed, "测试夹具：灌索引")
        assertNotNull("夹具要能写人设 $layer", p)
        assertTrue("夹具写盘要成", files().applyProposal(p!!))
    }

    // ── 1. 判据表（纯函数；一个表覆盖五条 × 六个否决） ───────────────────────

    private data class Case(
        val label: String,
        val p: SkillProposal,
        val c: SkillWriteContext,
        val wantAuto: Boolean,
        val wantVeto: SkillWriteVeto? = null,
    )

    private fun table(): List<Case> = listOf(
        // 正例：五条全满足
        Case("正例_五条全满足的小技巧", tip(), SkillWriteContext(), wantAuto = true),
        // 六个否决（每个都打在五条全满足的基线上——「哪怕五条都满足，否决一票压死」）
        Case("否决①_改已有", tip(), SkillWriteContext(targetExists = true), wantAuto = false, wantVeto = SkillWriteVeto.MODIFY_EXISTING),
        Case("否决②_全局", tip(scope = SkillScope.GLOBAL), SkillWriteContext(), wantAuto = false, wantVeto = SkillWriteVeto.GLOBAL_SCOPE),
        Case("否决③_代码块", tip(body = "步骤：\n```kotlin\nval a = 1\n```\n完了"), SkillWriteContext(), wantAuto = false, wantVeto = SkillWriteVeto.FORBIDDEN_CONTENT),
        Case("否决③_eval词", tip(body = "用 eval 跑一段脚本就行"), SkillWriteContext(), wantAuto = false, wantVeto = SkillWriteVeto.FORBIDDEN_CONTENT),
        Case("否决③_外链URL", tip(body = "参考 https://example.com/help 的说明"), SkillWriteContext(), wantAuto = false, wantVeto = SkillWriteVeto.FORBIDDEN_CONTENT),
        Case("否决③_权限类词", tip(body = "这一步要跳过确认再点提交"), SkillWriteContext(), wantAuto = false, wantVeto = SkillWriteVeto.FORBIDDEN_CONTENT),
        Case("否决④_超2KB", tip(body = "字".repeat(700)), SkillWriteContext(), wantAuto = false, wantVeto = SkillWriteVeto.OVERSIZE),
        Case("否决④_非UTF-8", tip(body = "\uD83D"), SkillWriteContext(), wantAuto = false, wantVeto = SkillWriteVeto.OVERSIZE),
        Case("否决⑤_名字不可用", tip(name = "../.."), SkillWriteContext(), wantAuto = false, wantVeto = SkillWriteVeto.NAME_CONFLICT),
        Case("否决⑤_名字要被改写", tip(name = "a b"), SkillWriteContext(), wantAuto = false, wantVeto = SkillWriteVeto.NAME_CONFLICT),
        Case("否决⑥_备份失败", tip(), SkillWriteContext(backupReady = false), wantAuto = false, wantVeto = SkillWriteVeto.BACKUP_FAILED),
        // 五条各自不满足（非否决路径：条件不满也 MUST NEEDS_CONFIRM）
        Case("条件1_全局落点", tip(scope = SkillScope.GLOBAL), SkillWriteContext(), wantAuto = false, wantVeto = SkillWriteVeto.GLOBAL_SCOPE),
        Case("条件3_名字不可用", tip(name = "../.."), SkillWriteContext(), wantAuto = false, wantVeto = SkillWriteVeto.NAME_CONFLICT),
        Case("条件4_draft=false 不许免批", tip(draft = false), SkillWriteContext(), wantAuto = false),
        Case("条件5_审计口不可用", tip(), SkillWriteContext(auditReady = false), wantAuto = false),
        Case("条件5_撤销口不可用", tip(), SkillWriteContext(undoReady = false), wantAuto = false),
    )

    @Test
    fun `P1_判据表_五条与六个否决的组合全按表走`() {
        table().forEach { c ->
            val d = SkillWritePolicy.decide(c.p, c.c)
            if (c.wantAuto) {
                assertTrue("[${c.label}] 应该 AUTO，实际：$d", d is SkillWriteDecision.Auto)
            } else {
                val nc = d as? SkillWriteDecision.NeedsConfirm
                assertNotNull("[${c.label}] 必须 NEEDS_CONFIRM，实际：$d", nc)
                assertEquals("[${c.label}] 否决行不对", c.wantVeto, nc!!.veto)
                assertTrue("[${c.label}] 得有一句能看的原因", nc.reason.isNotBlank())
            }
        }
    }

    @Test
    fun `P2_forbiddenFindings_每类命中都报得出来_干净正文为空`() {
        assertEquals(emptyList<String>(), SkillWritePolicy.forbiddenFindings(tip().body))
        // 代码块栅栏只认行首，单独成行给
        val fence = SkillWritePolicy.forbiddenFindings("步骤：\n```\n写到这里")
        assertTrue("代码块必须命中：$fence", fence.first().contains("代码块"))
        listOf(
            "eval" to "eval/exec",
            "https://x.y" to "外链 URL",
            "权限" to "禁用词",
            "确认" to "禁用词",
            "守卫" to "禁用词",
            "白名单" to "禁用词",
            "工具启用" to "禁用词",
            "跳过确认" to "禁用词",
        ).forEach { (needle, expect) ->
            val hits = SkillWritePolicy.forbiddenFindings("正文里有 $needle 一处")
            assertTrue("[$needle] 必须命中，实际：$hits", hits.isNotEmpty())
            assertTrue("[$needle] 命中描述要带「$expect」：$hits", hits.first().contains(expect))
        }
        // 词边界：evaluate / execute 不该被 \beval\b / \bexec\b 误伤
        assertTrue(SkillWritePolicy.forbiddenFindings("评估一下 evaluate 这个词").isEmpty())
    }

    @Test
    fun `P3_两KB口径按UTF8字节算_不是字符数`() {
        // 2100 个 ASCII 字符只有 2100 字节 ⇒ 超；700 个汉字 2100 字节 ⇒ 同样超
        assertTrue(SkillWritePolicy.utf8Bytes("a".repeat(2049))!! > SkillWritePolicy.BODY_MAX_BYTES)
        assertTrue(SkillWritePolicy.utf8Bytes("字".repeat(700))!! > SkillWritePolicy.BODY_MAX_BYTES)
        // 边界内放行：2000 字节
        assertTrue(SkillWritePolicy.utf8Bytes("a".repeat(2000))!! <= SkillWritePolicy.BODY_MAX_BYTES)
        // 未配对代理项：严格编码失败 ⇒ null（判非 UTF-8）
        assertNull(SkillWritePolicy.utf8Bytes("\uD83D"))
    }

    // ── 2. 端到端：AUTO 落盘 ＋ 留痕 ────────────────────────────────────────

    @Test
    fun `P4_正例_免批落草稿_索引带未验证_审计带免批标与来源`() {
        val out = auto(
            tip(),
            source = SkillAutoSource(sessionId = 42L, candidateId = "cand-1"),
            nowMs = 1_000L,
        )
        val w = out as? SkillAutoOutcome.Written
        assertNotNull("五条全满足必须写成功：$out", w)
        val body = w!!.file.readText(Charsets.UTF_8)
        // 条件4：免批恒为草稿——正文头必是 draft
        assertEquals("免批落地必须是 draft", "draft", SkillStore.statusOf(body))
        assertTrue("正文一个字不能丢", body.contains("先把必填字段填完"))
        // 条件4：草稿进索引并标「未验证」
        val idx = files().read(PersonaLayer.C_PROJECT).orEmpty()
        assertTrue("索引要有这条", idx.contains("小技巧"))
        assertTrue("索引行要标「草稿·未验证」", idx.contains("草稿·未验证"))
        // 条件5：审计行有免批标＋来源会话＋来源候选 id
        val audit = File(skillDir(), SkillAudit.FILE_NAME)
        assertTrue("审计文件要在", audit.isFile)
        val autoLine = audit.readText().lines().last { it.contains("\"auto\"") }
        assertTrue(autoLine.contains("\"auto\":true"))
        assertTrue("要记来源会话：$autoLine", autoLine.contains("\"sessionId\":42"))
        assertTrue("要记来源候选：$autoLine", autoLine.contains("\"candidateId\":\"cand-1\""))
        // 技能页名单认得出它是免批
        assertTrue(SkillAutoWrite.isAutoWritten(skillDir(), "小技巧"))
        assertEquals(setOf("小技巧"), SkillAutoWrite.autoWrittenNames(skillDir()))
    }

    @Test
    fun `P5_索引满时AUTO也写不进_原因如实带回`() {
        repeat(SkillRules.INDEX_CAP) { seedIndex(PersonaLayer.C_PROJECT, SkillRules.IndexLine(MemoryScope.PROJECT, "占位$it", "占位场景")) }
        val out = auto(tip())
        val r = out as? SkillAutoOutcome.Rejected
        assertNotNull("索引满必须拒：$out", r)
        assertTrue(r!!.reason.contains("索引已满"))
        assertFalse("正文不许落地", File(skillDir(), "小技巧/${SkillRules.SKILL_FILE}").isFile)
    }

    // ── 3. 八条负例（N6 / N7 必红） ────────────────────────────────────────

    @Test
    fun `N1_负例1_写全局技能_免批变确认_盘上无痕迹`() {
        val out = auto(tip(name = "全局底座", scope = SkillScope.GLOBAL))
        val nc = out as? SkillAutoOutcome.NeedsConfirm
        assertNotNull("全局必须 NEEDS_CONFIRM：$out", nc)
        assertEquals(SkillWriteVeto.GLOBAL_SCOPE, nc!!.decision.veto)
        assertFalse("全局目录不许建", File(globalRoot, SkillRules.SKILL_DIR).exists())
        assertFalse("项目技能目录也不许建", skillDir().exists())
    }

    @Test
    fun `N2_负例2_改已有技能_必须走确认_原件一字不动`() {
        // 先用确认制原口写一个既有技能（正文不带草稿头＝正式技能，模拟用户自己的底座）
        val existing = tip(name = "已有技能", body = "旧正文，别动它", draft = false)
        val first = SkillStore.apply(SkillStore.plan(existing, projectRoot, globalRoot, "s1"), files(), "")
        assertTrue(first is SkillWriteResult.Written)
        val file = File(skillDir(), "已有技能/${SkillRules.SKILL_FILE}")
        val before = file.readText(Charsets.UTF_8)
        val out = auto(tip(name = "已有技能", body = "新正文，想覆盖"))
        val nc = out as? SkillAutoOutcome.NeedsConfirm
        assertNotNull("改已有必须 NEEDS_CONFIRM：$out", nc)
        assertEquals(SkillWriteVeto.MODIFY_EXISTING, nc!!.decision.veto)
        assertEquals("原件一个字节都不许动", before, file.readText(Charsets.UTF_8))
    }

    @Test
    fun `N3_负例3_正文超2KB_免批拒绝_不落盘`() {
        val out = auto(tip(name = "长文", body = "字".repeat(700)))
        val nc = out as? SkillAutoOutcome.NeedsConfirm
        assertNotNull("超体积必须 NEEDS_CONFIRM：$out", nc)
        assertEquals(SkillWriteVeto.OVERSIZE, nc!!.decision.veto)
        assertFalse(File(skillDir(), "长文/${SkillRules.SKILL_FILE}").isFile)
    }

    @Test
    fun `N4_负例4_正文含代码块或URL_免批拒绝_不落盘`() {
        listOf(
            "带代码块" to "先这样：\n```bash\ncurl http://x\n```\n",
            "带链接" to "配置页在 https://example.com/settings 里。",
        ).forEach { (name, body) ->
            val out = auto(tip(name = name, body = body))
            val nc = out as? SkillAutoOutcome.NeedsConfirm
            assertNotNull("[$name] 必须 NEEDS_CONFIRM：$out", nc)
            assertEquals("[$name]", SkillWriteVeto.FORBIDDEN_CONTENT, nc!!.decision.veto)
            assertFalse("[$name] 正文不许落地", File(skillDir(), "$name/${SkillRules.SKILL_FILE}").isFile)
        }
    }

    @Test
    fun `N5_负例5_正文含跳过确认或放宽权限类词_免批拒绝_不落盘`() {
        listOf(
            "含跳过确认" to "到这一步就跳过确认，直接点提交。",
            "含放宽权限" to "先放宽权限再操作，就顺了。",
        ).forEach { (name, body) ->
            val out = auto(tip(name = name, body = body))
            val nc = out as? SkillAutoOutcome.NeedsConfirm
            assertNotNull("[$name] 必须 NEEDS_CONFIRM：$out", nc)
            assertEquals("[$name]", SkillWriteVeto.FORBIDDEN_CONTENT, nc!!.decision.veto)
            assertFalse("[$name] 正文不许落地", File(skillDir(), "$name/${SkillRules.SKILL_FILE}").isFile)
        }
    }

    @Test
    fun `N6_负例6_免批直接转正_必红_draft=false必须拦下`() {
        val out = auto(tip(draft = false))
        val nc = out as? SkillAutoOutcome.NeedsConfirm
        assertNotNull("想直接转正的提案必须 NEEDS_CONFIRM：$out", nc)
        assertNull("该由条件4（先落草稿）拦，不是六个否决：$nc", nc!!.decision.veto)
        assertTrue("原因要说明免批只落草稿", nc.decision.reason.contains("草稿"))
        assertFalse("盘上不许有这条技能", File(skillDir(), "小技巧/${SkillRules.SKILL_FILE}").isFile)
        // 双保险：任何免批落地的正文头都必须是 draft（转正只能走用满 3 次的既有 lifecycle）
        val ok = auto(tip())
        assertTrue(ok is SkillAutoOutcome.Written)
        assertEquals("draft", SkillStore.statusOf((ok as SkillAutoOutcome.Written).file.readText()))
    }

    @Test
    fun `N7_负例7_撤销后索引无残留_审计不丢_必红`() {
        // ① 先垫一行别人的索引（撤销后行数要回到「垫完＋这条」之前的口径）
        seedIndex(PersonaLayer.C_PROJECT, SkillRules.IndexLine(MemoryScope.PROJECT, "占位技能", "占位场景"))
        val countBefore = SkillRules.parseIndexLines(files().read(PersonaLayer.C_PROJECT), MemoryScope.PROJECT).size
        // ② 免批写入
        assertTrue(auto(tip(name = "会撤的"), nowMs = 1_000L) is SkillAutoOutcome.Written)
        val countAfterAuto = SkillRules.parseIndexLines(files().read(PersonaLayer.C_PROJECT), MemoryScope.PROJECT).size
        assertEquals("免批写入后索引多一行", countBefore + 1, countAfterAuto)
        val auditBefore = File(skillDir(), SkillAudit.FILE_NAME).readText(Charsets.UTF_8)
        val bodyBefore = File(skillDir(), "会撤的/${SkillRules.SKILL_FILE}").readText(Charsets.UTF_8)
        // ③ 一键撤销
        val r = SkillAutoWrite.undoAutoWrite("会撤的", projectRoot, files(), "20261025-100000", nowMs = 2_000L)
        assertTrue("撤销要成：$r", r is SkillWriteResult.Written)
        // ④ 正文没了，但归档在原地（不真删、内容原样）
        assertFalse("原 SKILL.md 不在", File(skillDir(), "会撤的/${SkillRules.SKILL_FILE}").isFile)
        val gone = File(skillDir(), "会撤的/SKILL.md.deleted-20261025-100000")
        assertTrue("归档件要在", gone.isFile)
        assertEquals("归档内容原样", bodyBefore, gone.readText(Charsets.UTF_8))
        // ⑤ 索引行数回原值、名字无残留（模型下一轮看不到）
        val idxAfter = files().read(PersonaLayer.C_PROJECT).orEmpty()
        assertEquals("索引行数必须是原值", countBefore, SkillRules.parseIndexLines(idxAfter, MemoryScope.PROJECT).size)
        assertFalse("索引里不许残留", idxAfter.contains("会撤的"))
        // ⑥ 审计一行不少：auto 行还在，undo 行追加在后（撤了也要能查到「谁撤的」）
        val auditAfter = File(skillDir(), SkillAudit.FILE_NAME).readText(Charsets.UTF_8)
        assertTrue("审计必须是追加式（旧行全在）", auditAfter.startsWith(auditBefore))
        assertTrue("auto 行还在", auditAfter.contains("\"action\":\"auto\""))
        assertTrue("undo 行在", auditAfter.contains("\"action\":\"undo\""))
        // ⑦ 撤完之后名单里没有它（技能页不再显示免批标，也不再有撤销按钮）
        assertFalse(SkillAutoWrite.isAutoWritten(skillDir(), "会撤的"))
        assertTrue(SkillAutoWrite.autoWrittenNames(skillDir()).isEmpty())
    }

    @Test
    fun `N8_负例8_AUTO路径碰不到A层与B层`() {
        // B 层（全局索引层）先垫内容：任何免批写入都不许动它
        seedIndex(PersonaLayer.B_GLOBAL, SkillRules.IndexLine(MemoryScope.GLOBAL, "全局占位", "全局占位场景"))
        val bBefore = files().read(PersonaLayer.B_GLOBAL)
        // 全局提案被否决（同 N1），B 层一字不动
        val out = auto(tip(name = "全局底座", scope = SkillScope.GLOBAL))
        assertTrue(out is SkillAutoOutcome.NeedsConfirm)
        assertEquals(bBefore, files().read(PersonaLayer.B_GLOBAL))
        // 项目提案正常 AUTO：动的只有 C 层与技能目录；A 层结构性无文件（propose 直接拒）
        assertTrue(auto(tip()) is SkillAutoOutcome.Written)
        assertEquals("B 层必须一字不动", bBefore, files().read(PersonaLayer.B_GLOBAL))
        // 源码级：免批壳里只许出现 C 层写口，A/B 层连提都不许提
        val src = File("src/main/java/com/example/zhengdao/baopu/SkillWritePolicy.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/baopu/SkillWritePolicy.kt") }
            .readText()
        assertTrue("免批壳要走 C 层", src.contains("PersonaLayer.C_PROJECT"))
        assertFalse("免批壳不许提 A 层：", src.contains("A_KERNEL"))
        assertFalse("免批壳不许提 B 层", src.contains("B_GLOBAL"))
        assertFalse("免批壳不许自己写文件", src.contains("writeText("))
    }

    // ── 4. 撤销的边界 ──────────────────────────────────────────────────────

    @Test
    fun `N9_非免批技能没有一键撤销`() {
        val existing = tip(name = "手写的", body = "用户自己写的", draft = false)
        assertTrue(SkillStore.apply(SkillStore.plan(existing, projectRoot, globalRoot, "s1"), files(), "") is SkillWriteResult.Written)
        val r = SkillAutoWrite.undoAutoWrite("手写的", projectRoot, files(), "20261025-110000")
        assertTrue("必须拒：$r", r is SkillWriteResult.Rejected)
        assertTrue("正文还在", File(skillDir(), "手写的/${SkillRules.SKILL_FILE}").isFile)
        assertTrue("索引行也还在", files().read(PersonaLayer.C_PROJECT).orEmpty().contains("手写的"))
    }

    @Test
    fun `N10_被确认制改过的免批技能不能再一键撤销`() {
        assertTrue(auto(tip(name = "会被改的")) is SkillAutoOutcome.Written)
        // 用户（确认制）改一次正文 ⇒ 审计多一行 update ⇒ 名单掉出
        val file = File(skillDir(), "会被改的/${SkillRules.SKILL_FILE}")
        val edited = SkillProposal(SkillScope.PROJECT, "会被改的", SkillStore.contentWithoutHeader(file.readText()) + "（改过）", "用户手改", draft = false)
        val planned = SkillStore.plan(edited, projectRoot, globalRoot, "s2")
        assertTrue(SkillStore.apply(planned, files(), files().read(PersonaLayer.C_PROJECT).orEmpty()) is SkillWriteResult.Written)
        assertFalse("改过就不算免批件了", SkillAutoWrite.isAutoWritten(skillDir(), "会被改的"))
        assertTrue(SkillAutoWrite.undoAutoWrite("会被改的", projectRoot, files(), "s3") is SkillWriteResult.Rejected)
        // 名单也认不出它（技能页不给「免批」标）
        assertTrue(SkillAutoWrite.autoWrittenNames(skillDir()).isEmpty())
    }

    @Test
    fun `P6_撤销的技能名可以重新免批写入`() {
        assertTrue(auto(tip(name = "repeatable")) is SkillAutoOutcome.Written)
        assertTrue(SkillAutoWrite.undoAutoWrite("repeatable", projectRoot, files(), "u1") is SkillWriteResult.Written)
        // 撤完同名再来一条：targetExists=false（原件已改名），AUTO 应该照常放行
        val again = auto(tip(name = "repeatable", body = "重新记的一条"))
        assertTrue("撤过的名字要能重新免批写入：$again", again is SkillAutoOutcome.Written)
        assertTrue(SkillAutoWrite.isAutoWritten(skillDir(), "repeatable"))
    }
}
