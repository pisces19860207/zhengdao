// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十批 · 种子技能版本迁移判据（派工单 §判据 ①–⑥）：
//   ① 装旧版 v1 ⇒ 升到 v2 ⇒ 正文变七步        → U1
//   ② 用户改过的同名 ⇒ 不覆盖、出现提示        → U2
//   ③ 用户自建同名 ⇒ 不覆盖、**不提示**        → U3 + U10（正文恰等于 v1 基线的漏测补例）
//   ④ 升级后旧版可回滚（归档在、留痕）        → U4 + U12（回滚后不许再升回去）
//   ⑤ hash 判定可信（改一个字节即判改过）      → U2 + U5（文字/换行两类单字节变异）
//   ⑥ detektWall ≤337（门禁跑）＋报告构建计划
// 护栏：已是 v2 幂等 / 转正状态不丢 / 资产读不到不出声 / 删过不升级 /
//       内置基线与 207 批资产指纹对账 / 契约源码未动 / 双侧异常隔离。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SeedSkillUpgradeTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        projectRoot = File.createTempFile("zd490-proj", "").let { it.delete(); it.mkdirs(); it }
        globalRoot = File.createTempFile("zd490-glob", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
        globalRoot.deleteRecursively()
    }

    private fun pf() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    /** v1 六步＝清单里的内置基线（207 批首装进手机的原文）。 */
    private fun v1Body(): String = SeedSkillCatalog.V1_WEITOUTIAO

    /** v2 七步＝APK 里真资产（不内置副本，避免测试与资产漂移）。 */
    private fun v2Body(): String =
        repoFile("src/main/assets/${SeedSkills.assetPathOf(NAME)}").readText(Charsets.UTF_8)

    private fun seedFile(): File =
        File(File(SkillRules.globalSkillDir(globalRoot), NAME), SkillRules.SKILL_FILE)

    /** 用 v1 正文跑一次 207 批首装（另外两条给干净假正文，凑齐三条）。 */
    private fun installV1(): InstallReport {
        val bodies = mapOf(
            NAME to v1Body(),
            "T9取数" to "# 查今天的车\n1. 先看收件箱。",
            "产物清理" to "# 清一下发完的\n1. 先列清单。",
        )
        return SeedSkills.install(
            readAsset = { path ->
                bodies[path.removePrefix("${SeedSkills.ASSET_DIR}/")
                    .removeSuffix("/${SkillRules.SKILL_FILE}")]
            },
            globalRoot = globalRoot,
            projectRoot = projectRoot,
            personaFiles = pf(),
            backupStamp = "20261106-000000",
        )
    }

    private fun upgrade(asset: String? = v2Body()): UpgradeReport = SeedSkillUpgrade.upgrade(
        readAsset = { path -> if (path == SeedSkills.assetPathOf(NAME)) asset else null },
        globalRoot = globalRoot,
        projectRoot = projectRoot,
        personaFiles = pf(),
        backupStamp = "20261106-010000",
        nowMs = 1_700_000_000_000L,
    )

    private fun auditText(): String {
        val f = SkillAudit.fileIn(SkillRules.globalSkillDir(globalRoot))
        return if (f.isFile) f.readText(Charsets.UTF_8) else ""
    }

    // ── 判据①：旧版 v1 未改过 ⇒ 自动升到 v2 ─────────────────────────────────

    @Test
    fun `U1_装旧版v1_自动升到v2_正文变七步且状态不丢`() {
        val installed = installV1()
        assertTrue("v1 要先装上：${installed.results}", NAME in installed.installed)
        assertTrue(seedFile().readText().contains("# 写完这批微头条，按台账查重再动笔"))

        val r = upgrade()
        val up = r.upgraded.single()
        assertEquals(1, up.from)
        assertEquals(2, up.to)
        val body = seedFile().readText()
        assertTrue("正文要变成七步版：$body", body.contains("7. 收尾报一句"))
        assertTrue(body.contains("先联网搜近期热点"))
        assertFalse("旧标题不许留着", body.contains("# 写完这批微头条，按台账查重再动笔"))
        assertEquals("草稿状态不许被升级洗掉", "draft", SkillStore.statusOf(body))
        assertEquals(0, SkillStore.usesOf(body))

        val index = SkillRules.parseIndexLines(pf().read(PersonaLayer.B_GLOBAL), MemoryScope.GLOBAL)
        assertEquals("升级是替换索引行，不是再追加一行", 3, index.size)
        assertTrue("草稿标不许丢", index.first { it.name == NAME }.scenario.contains("草稿·未验证"))
    }

    // ── 判据②＋⑤：改过的种子不覆盖、出提示；改一个字节就算改过 ──────────────

    @Test
    fun `U2_改过一个字节_判已改过_不覆盖且出提示`() {
        installV1()
        val f = seedFile()
        val changed = f.readText().replace("按台账查重再动笔", "按台账查重再动笔X")
        f.writeText(changed, Charsets.UTF_8)

        val r = upgrade()
        assertTrue("改过的一个字节都不许动", r.upgraded.isEmpty())
        val m = r.outcomes.filterIsInstance<UpgradeOutcome.ModifiedByUser>().single()
        assertEquals(NAME, m.name)
        assertEquals("内置有新版 v1→v2，看差异吗", m.hint)
        assertEquals(listOf("内置有新版 v1→v2，看差异吗"), r.hints)
        assertEquals("用户那份一字不动", changed, f.readText())
    }

    // ── 判据③：用户自建同名 ⇒ 不覆盖、不提示 ────────────────────────────────

    @Test
    fun `U3_用户自建同名_不覆盖且不提示`() {
        val dir = File(SkillRules.globalSkillDir(globalRoot), NAME)
        dir.mkdirs()
        val own = File(dir, SkillRules.SKILL_FILE)
        own.writeText("<!-- status=draft uses=0 -->\n我自己写的流程", Charsets.UTF_8)

        val r = upgrade()
        assertTrue(r.upgraded.isEmpty())
        assertTrue("自建同名不许出任何提示：${r.hints}", r.hints.isEmpty())
        assertTrue(r.outcomes.filterIsInstance<UpgradeOutcome.UserOwned>().isNotEmpty())
        assertTrue("自建那份一字不动", own.readText().contains("我自己写的流程"))
    }

    /** 独立评审抓出的阻断：早先只靠 hash 命中就认种子，照抄内置正文的自建技能会被覆盖。 */
    @Test
    fun `U10_自建同名且正文恰等于v1基线_仍不覆盖不提示`() {
        val dir = File(SkillRules.globalSkillDir(globalRoot), NAME)
        dir.mkdirs()
        val own = File(dir, SkillRules.SKILL_FILE)
        val body = SkillStore.draftHeader() + "\n" + v1Body()
        own.writeText(body, Charsets.UTF_8) // 没有任何 seed 审计行 / 旁挂标记

        val r = upgrade()
        assertTrue("hash 命中基线也不许放行：${r.outcomes}", r.upgraded.isEmpty())
        assertTrue("自建同名一个提示都不许出：${r.hints}", r.hints.isEmpty())
        assertTrue(r.outcomes.filterIsInstance<UpgradeOutcome.UserOwned>().isNotEmpty())
        assertEquals("用户那份一个字节没动", body, own.readText())
        assertFalse("也不许往用户自建目录写种子标记", File(dir, SeedSkillUpgrade.MARKER_FILE).exists())
    }

    // ── 判据④：归档在、可回滚、留一笔 v1→v2 来源=种子 ────────────────────────

    @Test
    fun `U4_升级后旧版归档在_可回滚_审计记v1到v2来源种子`() {
        installV1()
        val up = upgrade().upgraded.single()
        assertTrue("归档戳要带种子识别段：${up.archivedAs}", up.archivedAs.contains(SeedSkillUpgrade.ARCHIVE_TAG))
        val archive = File(seedFile().parentFile, up.archivedAs)
        assertTrue("旧版归档件要在：$archive", archive.isFile)
        assertTrue(archive.readText().contains("# 写完这批微头条，按台账查重再动笔"))
        assertFalse("归档绝不能用 deleted- 前缀（会被认成用户删过）", archive.name.contains(".deleted-"))

        val text = auditText()
        assertTrue(text.contains("\"action\":\"seed_upgrade\""))
        assertTrue("留痕要写清版本与来源：$text", text.contains("v1→v2，来源=种子"))
        assertTrue(text.contains("\"source\":\"seed\""))
        assertTrue("旁挂版本标记要落盘", File(seedFile().parentFile, SeedSkillUpgrade.MARKER_FILE).isFile)

        assertTrue("回滚要真的走通", SeedSkillUpgrade.rollback(globalRoot, NAME, "20261106-020000"))
        assertTrue("回滚后正文回到 v1", seedFile().readText().contains("# 写完这批微头条，按台账查重再动笔"))
    }

    @Test
    fun `U12_回滚后下次机会窗口不再自动升回v2`() {
        installV1()
        assertEquals(1, upgrade().upgraded.size)
        assertTrue(SeedSkillUpgrade.rollback(globalRoot, NAME, "20261106-020000"))
        val rolledBack = seedFile().readText()

        val again = upgrade()
        assertTrue("已回滚的不许再升级：${again.outcomes}", again.upgraded.isEmpty())
        assertTrue(again.outcomes.all { it is UpgradeOutcome.Left })
        assertTrue("也不许再出提示", again.hints.isEmpty())
        assertEquals("盘上保持 v1", rolledBack, seedFile().readText())
    }

    // ── 判据⑤：hash 判定器本身的单字节变异（基于真实盘上文件，不自己比自己）──

    @Test
    fun `U5_文字与换行单字节变异都掉档_机器头不算改过`() {
        val v2 = v2Body()
        installV1()
        assertEquals("盘上 v1（带机器头）判 BASELINE", BodyVerdict.BASELINE,
            SeedSkillUpgrade.classify(seedFile().readText(), v1Body(), v2))
        upgrade()
        assertEquals("升级后的盘上正文判 LATEST", BodyVerdict.LATEST,
            SeedSkillUpgrade.classify(seedFile().readText(), v1Body(), v2))

        val poisoned = seedFile().readText().replace("收尾报一句", "收尾报一句X")
        assertEquals("文字改一个字节必须判 OTHER", BodyVerdict.OTHER,
            SeedSkillUpgrade.classify(poisoned, v1Body(), v2))
        // 行尾也算字节：只把 LF 换成 CRLF 必须判 OTHER，不许被 lines() 归一化抹平。
        val crlf = (SkillStore.draftHeader() + "\n" + v1Body()).replace("\n", "\r\n")
        assertEquals("换行符变异必须判 OTHER", BodyVerdict.OTHER,
            SeedSkillUpgrade.classify(crlf, v1Body(), v2))
        // 头由 SkillStore 按使用/转正重算；把它算进 hash 会让「用过一次」的种子永远升不了级。
        val promoted = SkillStore.draftHeader("active", 3) + "\n" + v1Body()
        assertEquals(BodyVerdict.BASELINE, SeedSkillUpgrade.classify(promoted, v1Body(), v2))
    }

    /** 基线常量与 `git show ee1f16e37^` 的 v1 资产逐字节对账（指纹 + 长度 + 结构）。 */
    @Test
    fun `U11_内置v1基线指纹与207批资产对账`() {
        assertEquals(
            "与 ee1f16e37^ 的 v1 资产 raw sha256 一致",
            "ce515f3566fe62a471db1417c8b12dae1a65692d988f4c093e021846eb8df12d",
            SeedSkillCatalog.bodySha(v1Body()),
        )
        assertEquals("705 字节", 705, v1Body().toByteArray(Charsets.UTF_8).size)
        assertFalse("纯 LF，不含 CR", v1Body().contains("\r"))
        assertTrue(v1Body().contains("6. 收尾报一句"))
        assertFalse("v1 是六步，不该有第七步", v1Body().contains("7. 收尾报一句"))
    }

    // ── 护栏 ────────────────────────────────────────────────────────────────

    @Test
    fun `U6_已是v2不动_没换过内容的两条种子永不迁移`() {
        assertEquals("清单名字集合必须与 NAMES 一致", SeedSkills.NAMES.toSet(), SeedSkillCatalog.ENTRIES.map { it.name }.toSet())
        assertEquals("只有微头条流程换过内容", listOf(NAME), SeedSkillCatalog.upgradable().map { it.name })
        installV1()
        upgrade()
        val after = seedFile().readText()
        val again = upgrade()
        assertTrue("再跑一次不升级", again.upgraded.isEmpty())
        assertTrue(again.outcomes.all { it is UpgradeOutcome.AlreadyCurrent })
        assertEquals("盘上一个字节没动", after, seedFile().readText())
    }

    @Test
    fun `U7_已转正的种子升级后仍是正式且uses不丢`() {
        installV1()
        seedFile().writeText(SkillStore.promoteBody(SkillStore.draftHeader("draft", 3) + "\n" + v1Body()), Charsets.UTF_8)
        val r = upgrade()
        assertEquals(1, r.upgraded.size)
        val body = seedFile().readText()
        assertEquals("active", SkillStore.statusOf(body))
        assertEquals(3, SkillStore.usesOf(body))
        assertTrue(body.contains("7. 收尾报一句"))
        val line = SkillRules.parseIndexLines(pf().read(PersonaLayer.B_GLOBAL), MemoryScope.GLOBAL)
            .first { it.name == NAME }
        assertFalse("正式技能的索引不该挂草稿标：${line.scenario}", line.scenario.contains("未验证"))
    }

    @Test
    fun `U8_资产读不到_只留跳过原因不抛异常不覆盖`() {
        installV1()
        val before = seedFile().readText()
        val r = upgrade(asset = null)
        assertTrue(r.outcomes.all { it is UpgradeOutcome.Left })
        assertTrue((r.outcomes.single() as UpgradeOutcome.Left).why.contains("读不到"))
        assertEquals("升级失败不许动旧版", before, seedFile().readText())
    }

    @Test
    fun `U9_用户删过的种子_升级通道也跳过`() {
        installV1()
        assertTrue(SeedSkills.markDeclined(globalRoot, NAME))
        val r = upgrade()
        assertTrue(r.upgraded.isEmpty())
        assertTrue(r.outcomes.all { it is UpgradeOutcome.Left })
    }

    // ── 源码级门禁：旧契约真 return、调用点各一处、双侧异常隔离 ──────────────

    @Test
    fun `G1_不覆盖契约原样在_启动窗口先升级后首装且双侧隔离`() {
        val seedSrc = repoFile("src/main/java/com/example/zhengdao/baopu/SeedSkills.kt").readText()
        assertTrue("同名不覆盖那道闸必须还在", seedSrc.contains("同名已在 ⇒ 绝不覆盖"))
        val gate = seedSrc.indexOf("if (target.isFile)")
        assertTrue(gate > 0)
        assertTrue("同名分支必须真的 return Skipped（不是留个空 if）",
            seedSrc.indexOf("return SeedResult.Skipped(", gate) > gate)

        val ctrl = repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText()
        assertEquals("升级调用点恰好一处", 1, Regex("SeedSkillUpgrade\\.upgrade\\(").findAll(ctrl).count())
        assertEquals("首装调用点恰好一处", 1, Regex("SeedSkills\\.install\\(").findAll(ctrl).count())
        assertTrue("升级必须排在首装前面", ctrl.indexOf("SeedSkillUpgrade.upgrade(") < ctrl.indexOf("SeedSkills.install("))
        assertTrue("提示要接进便签", ctrl.contains("R.string.seed_skill_new_version"))
        assertTrue("升级异常要被吞掉", ctrl.contains("种子技能升级窗口异常（不影响启动）"))
        assertTrue("首装异常也要被吞掉", ctrl.contains("种子技能首装窗口异常（不影响启动）"))
        assertTrue("两侧都要 getOrElse", Regex("getOrElse").findAll(ctrl).count() >= 2)
    }

    private companion object {
        const val NAME = "微头条流程"
    }
}
