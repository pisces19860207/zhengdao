// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零七批 · 种子技能包的判据（派工单 §1/§2）：
// 正例＝干净安装三条全落全局目录、全带「草稿·未验证」、审计带 source=seed；
// 六条负例各红：①直接转正落地 ②删了又装回 ③正文含禁用内容 ④覆盖用户改过的同名技能
// ⑤塞进工作区技能目录 ⑥装失败静默。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SeedSkillsTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File

    @Before
    fun setUp() {
        projectRoot = File.createTempFile("zd207-proj", "").let { it.delete(); it.mkdirs(); it }
        globalRoot = File.createTempFile("zd207-glob", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
        globalRoot.deleteRecursively()
    }

    private fun pf() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    /** 三条假正文（盘上行为用；真资产的内容自检见 S7，读的是 APK 将要打包的那三份文件）。 */
    private fun fakeBodies(): Map<String, String> = mapOf(
        "微头条流程" to "# 写完这批微头条\n1. 先查台账再写。",
        "T9取数" to "# 查今天的车\n1. 先看收件箱。",
        "产物清理" to "# 清一下发完的\n1. 先列清单。",
    )

    private fun installOnce(bodies: Map<String, String> = fakeBodies()): InstallReport =
        SeedSkills.install(
            readAsset = { path -> bodies[path.removePrefix("${SeedSkills.ASSET_DIR}/")
                .removeSuffix("/${SkillRules.SKILL_FILE}")] },
            globalRoot = globalRoot,
            projectRoot = projectRoot,
            personaFiles = pf(),
            backupStamp = "20261025-120000",
        )

    private fun seedFile(name: String): File =
        File(File(SkillRules.globalSkillDir(globalRoot), name), SkillRules.SKILL_FILE)

    private fun auditText(): String {
        val f = SkillAudit.fileIn(SkillRules.globalSkillDir(globalRoot))
        return if (f.isFile) f.readText(Charsets.UTF_8) else ""
    }

    // ── 正例：干净安装 ────────────────────────────────────────────────────

    @Test
    fun `S1_干净安装_三条落全局目录_全草稿_索引带未验证标_审计带seed`() {
        val report = installOnce()
        assertEquals("三条全装上", listOf("微头条流程", "T9取数", "产物清理"), report.installed)

        // ① 正文：draft 头＋在**全局**技能目录（负例⑤的反面：工作区技能目录一个文件都没有）
        SeedSkills.NAMES.forEach { name ->
            val f = seedFile(name)
            assertTrue("正文要落在全局 技能/$name/SKILL.md", f.isFile)
            val body = f.readText(Charsets.UTF_8)
            assertEquals("首装即草稿（负例①的反面：不许直接转正）", "draft", SkillStore.statusOf(body))
            assertEquals("次数从 0 起", 0, SkillStore.usesOf(body))
        }
        assertFalse("不许在工作区技能目录里出现任何种子（负例⑤）", SkillRules.projectSkillDir(projectRoot).exists())

        // ② 索引：B 层三行、名字对、场景带「草稿·未验证」
        val index = SkillRules.parseIndexLines(pf().read(PersonaLayer.B_GLOBAL), MemoryScope.GLOBAL)
        assertEquals(3, index.size)
        SeedSkills.NAMES.forEach { name ->
            val line = index.firstOrNull { it.name == name }
            assertNotNull("索引要有 $name", line)
            assertTrue("索引行要带草稿标：$line", line!!.scenario.contains("草稿·未验证"))
        }

        // ③ 审计：apply 的 write 行之外，还有一行 action=seed 且 source=seed
        val seedLines = auditText().lines().filter { it.contains("\"action\":\"seed\"") }
        assertEquals("三条各一行 seed 审计", 3, seedLines.size)
        assertTrue("审计行要带 source=seed", seedLines.all { it.contains("\"source\":\"seed\"") })
    }

    @Test
    fun `S2_幂等_再跑一次一条不装_索引不重复`() {
        installOnce()
        val again = installOnce()
        assertTrue("第二次全跳过", again.installed.isEmpty())
        val index = SkillRules.parseIndexLines(pf().read(PersonaLayer.B_GLOBAL), MemoryScope.GLOBAL)
        assertEquals("索引仍然只有三行（不重复追加）", 3, index.size)
    }

    // ── 负例②：删了又装回 ────────────────────────────────────────────────

    @Test
    fun `S3_删过不再装回_归档痕迹在或硬删后名单都拦`() {
        installOnce()
        // 用户删掉一条（设置页口径：归档改名 + 索引去行）
        val gone = SkillStore.archive(seedFile("微头条流程"), "20261025-130000")
        assertTrue(gone is SkillWriteResult.Written)
        val bIndex = pf().read(PersonaLayer.B_GLOBAL).orEmpty()
        pf().applyProposal(
            PersonaProposal(
                layer = PersonaLayer.B_GLOBAL,
                current = bIndex,
                proposed = (SkillRules.removeIndexLine(bIndex, "微头条流程") as SkillRules.EditResult.Ok).newText,
                reason = "删技能：微头条流程",
            ),
        )

        // 第一次重装扫描：认出 .deleted-* 痕迹 ⇒ 记入拒绝名单、跳过
        val r1 = installOnce()
        assertTrue(
            "删过的不装：${r1.results}",
            r1.results.any { it is SeedResult.Skipped && it.name == "微头条流程" },
        )
        assertFalse("正文不许回来", seedFile("微头条流程").isFile)
        assertTrue("拒绝名单要记上", "微头条流程" in SeedSkills.readDeclined(globalRoot))
        // 另两条不受影响：还在盘上（第二次扫描对它们是「已装过」跳过，不是重装）
        assertTrue("T9取数不受影响", seedFile("T9取数").isFile)
        assertTrue("产物清理不受影响", seedFile("产物清理").isFile)
        assertTrue("第二次扫描不新装任何条", r1.installed.isEmpty())

        // 用户再把归档件彻底删掉（痕迹清零）⇒ 名单继续拦（删了不再装回的红线靠名单，不靠痕迹）
        File(SkillRules.globalSkillDir(globalRoot), "微头条流程")
            .listFiles { f -> f.name.startsWith("${SkillRules.SKILL_FILE}.deleted-") }
            ?.forEach { it.delete() }
        val r2 = installOnce()
        assertFalse("硬删之后也不许装回来", seedFile("微头条流程").isFile)
        assertTrue(r2.results.none { it is SeedResult.Installed && it.name == "微头条流程" })
    }

    // ── 负例④：覆盖用户改过的同名技能 ────────────────────────────────────

    @Test
    fun `S4_用户已有同名技能_跳过且一字不动`() {
        val custom = File(File(SkillRules.globalSkillDir(globalRoot), "T9取数"), SkillRules.SKILL_FILE)
        custom.parentFile?.mkdirs()
        custom.writeText("<!-- status=draft uses=0 -->\n我自己改过的 T9 流程", Charsets.UTF_8)

        val report = installOnce()
        assertEquals("只装另外两条", listOf("微头条流程", "产物清理"), report.installed)
        assertEquals("用户那份一个字没动", "我自己改过的 T9 流程", custom.readText(Charsets.UTF_8).lines()[1])
        val skip = report.results.filterIsInstance<SeedResult.Skipped>().single()
        assertEquals("T9取数", skip.name)
        assertTrue("跳过原因要说明是同名不覆盖：${skip.why}", skip.why.contains("不覆盖"))
        val index = SkillRules.parseIndexLines(pf().read(PersonaLayer.B_GLOBAL), MemoryScope.GLOBAL)
        assertTrue("被占用的那条不许再写索引行", index.none { it.name == "T9取数" && it.scenario.contains("查今天的车") })
    }

    // ── 负例⑥：装失败静默 ────────────────────────────────────────────────

    @Test
    fun `S5_资产读不到_失败要带人话原因_不许静默`() {
        val report = installOnce(fakeBodies() - "产物清理")
        assertEquals("两条装上", 2, report.installed.size)
        val failed = report.failed.single()
        assertEquals("产物清理", failed.name)
        assertTrue("失败原因要能直接读：${failed.why}", failed.why.contains("读不到"))
    }

    @Test
    fun `S6_内容闸_权限词与超体积的种子直接拒装`() {
        val bad = fakeBodies() + ("产物清理" to "# 清理\n这个流程需要跳过确认。")
        val r1 = SeedSkills.install(
            readAsset = { path -> bad[path.substringAfterLast("${SeedSkills.ASSET_DIR}/").removeSuffix("/${SkillRules.SKILL_FILE}")] },
            globalRoot = globalRoot, projectRoot = projectRoot, personaFiles = pf(), backupStamp = "s",
        )
        assertTrue(r1.failed.single().why.contains("不干净"))

        val big = fakeBodies() + ("产物清理" to ("# 太长\n" + "很长的流程。".repeat(300)))
        val r2 = SeedSkills.install(
            readAsset = { path -> big[path.substringAfterLast("${SeedSkills.ASSET_DIR}/").removeSuffix("/${SkillRules.SKILL_FILE}")] },
            globalRoot = globalRoot, projectRoot = projectRoot, personaFiles = pf(), backupStamp = "s",
        )
        assertTrue(r2.failed.single().why.contains("超"))
    }

    // ── 判据：三条真资产过 192 的 decide()（内容维度）且 ≤2KB ─────────────

    @Test
    fun `S7_真资产自检_三条都能被decide判AUTO且不超2KB`() {
        SeedSkills.NAMES.forEach { name ->
            val f = repoFile("src/main/assets/${SeedSkills.assetPathOf(name)}")
            assertTrue("资产文件要在：$f", f.isFile)
            val body = f.readText(Charsets.UTF_8)
            assertTrue("$name 正文 ≤2KB（现在 ${SkillWritePolicy.utf8Bytes(body)} 字节）",
                SkillWritePolicy.utf8Bytes(body)!! <= SkillWritePolicy.BODY_MAX_BYTES)
            assertEquals("目录名必须是安全名原样", name, SkillStore.sanitizeName(name))
            // decide() 的 AUTO 语义按 192 是「项目技能」；种子落全局，所以这里把 scope 折成
            // PROJECT 只验**内容维度**（纯流程/无禁用词/体积/名字/先草稿），与负例③同一张表。
            val d = SkillWritePolicy.decide(
                SkillProposal(scope = SkillScope.PROJECT, name = name, body = body, reason = "自检", draft = true),
                SkillWriteContext(targetExists = false),
            )
            assertTrue("$name 要能判 AUTO（不含代码块/外链/权限词）：$d", d is SkillWriteDecision.Auto)
        }
        // 判别器负例：往任一正文里塞权限词 ⇒ 必须变 NEEDS_CONFIRM（防这条判据空转）
        val poisoned = SkillProposal(
            scope = SkillScope.PROJECT, name = "微头条流程",
            body = repoFile("src/main/assets/${SeedSkills.assetPathOf("微头条流程")}").readText(Charsets.UTF_8) + "\n需要跳过确认。",
            reason = "自检", draft = true,
        )
        assertTrue(
            SkillWritePolicy.decide(poisoned, SkillWriteContext(targetExists = false)) is SkillWriteDecision.NeedsConfirm,
        )
    }

    // ── 边界：索引满 ⇒ 拒装但出声 ─────────────────────────────────────────

    @Test
    fun `S8_索引已满_全部拒装且失败原因可读`() {
        val current = StringBuilder()
        repeat(20) { i ->
            val edit = SkillRules.addIndexLine(
                current.toString(),
                SkillRules.IndexLine(MemoryScope.GLOBAL, "占位$i", "占位场景$i"),
                otherLayerCount = 0,
            ) as SkillRules.EditResult.Ok
            current.clear().append(edit.newText)
        }
        pf().applyProposal(
            PersonaProposal(
                layer = PersonaLayer.B_GLOBAL, current = "", proposed = current.toString(),
                reason = "测试：灌满 B 层索引",
            ),
        )
        val report = installOnce()
        assertTrue("满了就一条都不装", report.installed.isEmpty())
        assertTrue("每条失败都要有原因：${report.failed}", report.failed.size == 3 && report.failed.all { it.why.isNotBlank() })
    }

    // ── 源码门禁：失败留痕与硬删挂名单 ────────────────────────────────────

    @Test
    fun `G1_install每条结果都落RunLog_硬删种子必挂拒绝名单`() {
        val seedSrc = repoFile("src/main/java/com/example/zhengdao/baopu/SeedSkills.kt").readText()
        listOf("种子技能安装：", "种子技能跳过：", "种子技能装失败：").forEach {
            assertTrue("装成/跳过/失败三类都要进 RunLog（负例⑥）：缺 $it", seedSrc.contains(it))
        }
        val cardSrc = repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuSkillCard.kt").readText()
        assertTrue(
            "硬删归档件必须挂拒绝名单（删了不再装回）",
            cardSrc.contains("SeedSkills.markDeclined"),
        )
    }
}
