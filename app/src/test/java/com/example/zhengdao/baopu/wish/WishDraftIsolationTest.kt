// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十批 · 判据②的**真落盘**证明。
// 流水线单测（WishPipelineTest）用的是假端口，只能证明状态机不提前调转正；
// 本件接真 `SkillAutoWrite`／真 `PersonaFiles`／真临时目录，证明盘上事实：
//   · 草稿落地后 `status=draft`（＝没进生产），索引行带「草稿·未验证」标；
//   · 未用满 3 次时转正口拒不放行；用满后转正 ⇒ 标记消失、状态转 active；
//   · 回滚走归档改名，正文一个字节不删。
// 另钉一条接线缺陷：许愿原话含空格／点号时，名字必须先 sanitizeName 再进 proposal，
// 否则 `SkillWritePolicy` 的 NAME_CONFLICT 否决必命中 ⇒ 草稿一条也落不下去。
package com.example.zhengdao.baopu.wish

import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WishDraftIsolationTest {

    private lateinit var projectRoot: File
    private lateinit var globalRoot: File
    private lateinit var workspaceRoot: File

    @Before
    fun setUp() {
        projectRoot = dir("zd-w510-proj")
        globalRoot = dir("zd-w510-glob")
        workspaceRoot = dir("zd-w510-ws")
    }

    @After
    fun tearDown() {
        projectRoot.deleteRecursively()
        globalRoot.deleteRecursively()
        workspaceRoot.deleteRecursively()
    }

    private fun dir(tag: String) = File.createTempFile(tag, "").let { it.delete(); it.mkdirs(); it }

    private fun files() = PersonaFiles(projectRoot = projectRoot, globalRoot = globalRoot)

    private fun ctx(text: String) = WishContext("w-1", text, WishProduct.SKILL)

    /** 一条够格免批的正文：无代码块／外链／禁用词，≤2KB。 */
    private fun cleanBody() = "先把必填字段填完，再点保存。看到「成功」两个字才算成。"

    private fun draftPort() = WishWiring.skillWriteDraft(projectRoot, globalRoot, files(), "20261107-090000")

    private fun skillFile(name: String) = File(File(SkillRules.projectSkillDir(projectRoot), name), SkillRules.SKILL_FILE)

    // ── 判据②：草稿不进生产（盘上事实） ────────────────────────────────────

    @Test
    fun 判据2_草稿落地后状态是draft不是active() {
        val name = requireNotNull(WishWiring.skillName(WISH))
        assertTrue("免批草稿要能落盘", draftPort()(ctx(WISH), cleanBody()))

        val file = skillFile(name)
        assertTrue("正文文件要存在", file.isFile)
        val body = file.readText()
        assertEquals("草稿态＝未进生产", "draft", SkillStore.statusOf(body))
        assertEquals("初始 uses=0", 0, SkillStore.usesOf(body))
    }

    @Test
    fun 判据2_草稿进索引但带未验证标() {
        // 第一百七十四批口径：草稿**也**进索引（不带标看不见＝白烧 token），
        // 所以「不进生产」判的是 status 与标记，不是「索引里查无此条」。
        val name = requireNotNull(WishWiring.skillName(WISH))
        assertTrue(draftPort()(ctx(WISH), cleanBody()))

        val index = files().read(PersonaLayer.C_PROJECT).orEmpty()
        assertTrue("索引行有这条", index.contains(name))
        assertTrue("索引行带草稿·未验证标", index.contains("草稿·未验证"))
    }

    @Test
    fun 判据2_未用满三次转正口不放行() {
        val name = requireNotNull(WishWiring.skillName(WISH))
        assertTrue(draftPort()(ctx(WISH), cleanBody()))
        val promote = WishWiring.skillPromote(projectRoot, files(), "20261107-090100")

        assertFalse("uses=0 不能转正", promote(ctx(WISH)))
        assertEquals("仍是草稿", "draft", SkillStore.statusOf(skillFile(name).readText()))
    }

    @Test
    fun 判据2_用满三次后转正口放行并留备份() {
        val name = requireNotNull(WishWiring.skillName(WISH))
        assertTrue(draftPort()(ctx(WISH), cleanBody()))
        val file = skillFile(name)
        // 造「用满 3 次但仍未转正」这一格：不能用 afterUse 连跑三次——它第 3 次同一份正文里
        // 就把头改成 active（184 批口径：数次数与改状态不拆两笔），那之后 canPromote 恒假，
        // 转正口反而拦死。手动转正那格只能用 draftHeader 直接摆出来。
        val pending = SkillStore.draftHeader(status = "draft", uses = SkillStore.PROMOTE_USES) + "\n" + cleanBody()
        file.writeText(pending, Charsets.UTF_8)
        assertEquals("转正前仍是草稿（未进生产）", "draft", SkillStore.statusOf(file.readText()))

        val promoted = WishWiring.skillPromote(projectRoot, files(), "20261107-090200")(ctx(WISH))
        assertTrue("用满后可转正", promoted)
        val after = file.readText()
        assertEquals("转正后进生产", "active", SkillStore.statusOf(after))
        assertEquals("uses 原样保留", SkillStore.PROMOTE_USES, SkillStore.usesOf(after))
        assertTrue("转正留了备份", file.parentFile!!.listFiles { f -> f.name.contains(SkillStore.BACKUP_MARK) }!!.isNotEmpty())
    }

    // ── 接线缺陷钉子：名字未净化 ⇒ 免批必被 NAME_CONFLICT 否决 ──────────────

    @Test
    fun 名字含空格与点号也能落草稿() {
        // 原话带空格与「.」；sanitizeName 会滤掉它们。
        // 若 proposal.name 直接塞原话，NAME_CONFLICT 否决命中 ⇒ NeedsConfirm ⇒ 写不进去。
        assertTrue(WISH.contains(" ") && WISH.contains("."))
        val name = requireNotNull(WishWiring.skillName(WISH))
        assertNotEquals("净化名应已滤掉空格与点号", WISH.trim(), name)
        assertTrue("净化后的名字要能进免批口", draftPort()(ctx(WISH), cleanBody()))
        assertTrue("正文真的落了盘", skillFile(name).isFile)
    }

    @Test
    fun 三条接线用同一个名字口径() {
        val name = requireNotNull(WishWiring.skillName(WISH))
        assertTrue(draftPort()(ctx(WISH), cleanBody()))
        // 转正／回滚找的是同一个目录名：写进去的找得到，才不会写成一条、撤掉另一条。
        assertTrue(skillFile(name).isFile)
        assertTrue("回滚走撤销口要成", WishWiring.skillRollback(projectRoot, files(), "20261107-090300")(ctx(WISH), "试跑不过"))

        val raw = files().read(PersonaLayer.C_PROJECT).orEmpty()
        val lines = SkillRules.parseIndexLines(raw, MemoryScope.PROJECT)
        assertFalse("撤销后索引不再有该行", lines.any { it.name == name })
    }

    @Test
    fun 回滚是归档改名不是真删正文() {
        val name = requireNotNull(WishWiring.skillName(WISH))
        assertTrue(draftPort()(ctx(WISH), cleanBody()))
        val skillDir = File(SkillRules.projectSkillDir(projectRoot), name)
        val original = File(skillDir, SkillRules.SKILL_FILE)
        val before = original.readText()
        val stamp = "20261107-090400"

        assertTrue(WishWiring.skillRollback(projectRoot, files(), stamp)(ctx(WISH), "命中红线"))
        assertFalse("原名正文已让位", original.isFile)
        // SkillStore.archive 的口径：同名文件加 `.deleted-<时间戳>` 留在**技能自己目录内**，
        // 内容一字不动；真要彻底删由用户在文件管理器里做。
        val archived = File(skillDir, "${SkillRules.SKILL_FILE}.deleted-$stamp")
        assertTrue("归档件要还在原地", archived.isFile)
        assertEquals("归档件内容与原正文一字不差", before, archived.readText())
    }

    @Test
    fun 红线拦住的正文一条也不落盘() {
        // 判据⑦的盘上证明：红线在 writeDraft **之前**拦，所以真免批口压根没被调用过，
        // 技能目录不该被建出来——不是"写进去之后再警告"。
        val name = requireNotNull(WishWiring.skillName(WISH))
        val ports = WishPorts(
            generateBody = { "步骤：删原件，不留备份" },
            writeDraft = WishWiring.skillWriteDraft(projectRoot, globalRoot, files(), "20261107-090500"),
            runRehearsal = { RehearsalReading(true, true, "不该跑到这里") },
            collectCheck = { SelfCheckVerdict.Pass("不该跑到这里") },
            doPromote = { true },
            doRollback = { _, _ -> true },
        )
        val p = WishPipeline(workspaceRoot, ports) { 1_000_000L }
        val out = p.submit(WISH, WishProduct.SKILL)
        val wishId = (out as WishOutcome.AwaitingConfirmation).wishId

        assertTrue("含危险的草稿 ⇒ 拒生成", p.confirm(wishId, listOf("S1", "S2", "S3")) is WishOutcome.Rejected)
        assertFalse("技能目录压根没被建出来", SkillRules.projectSkillDir(projectRoot).isDirectory)
        assertFalse("索引里也没有这条", files().read(PersonaLayer.C_PROJECT).orEmpty().contains(name))
    }

    private companion object {
        const val WISH = "把工作区里的 .md 合并成一本"
    }
}
