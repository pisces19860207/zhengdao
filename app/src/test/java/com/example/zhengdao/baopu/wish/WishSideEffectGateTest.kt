// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十批 · 「草稿不进生产」的**另两条腿**（派工单 §六 共同②、§七③）。
// §六 共同② 说的是三类：技能不进库、**卡不进产物目录**、**内容不进台账**；
// 技能那一条已由 `WishDraftIsolationTest` 在真盘上钉住（status=draft＋未验证标）。
// 卡／内容这两条不能只靠嘴说「本批没接线」——这里各钉一层：
//   ① 磁盘级：四类产物各跑一遍流水线，工作区里**只许**多出流水账那一个文件；
//   ② 源级：许愿包里除既有技能写口外**没有任何生产写口**（防后来人顺手加一个）。
// 复用 `GateIo`（法典门禁同款只读探针），不另写一套扫源设施。
package com.example.zhengdao.baopu.wish

import com.example.zhengdao.baopu.constitution.GateIo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WishSideEffectGateTest {

    private lateinit var workspaceRoot: File

    @Before
    fun setUp() {
        workspaceRoot = File.createTempFile("zd-w510-se", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        workspaceRoot.deleteRecursively()
    }

    /** 一套「什么都不写真东西」的端口：只有落账是真的（writeDraft 记次数但不落盘）。 */
    private fun inertPorts(): WishPorts = WishPorts(
        generateBody = { "安全正文：先填必填再点保存" },
        writeDraft = { _, _ -> true },
        runRehearsal = { RehearsalReading(ok = true, realUntouched = true, note = "只读真文件，写落副本") },
        collectCheck = { SelfCheckVerdict.Pass("机械判定通过") },
        doPromote = { true },
        doRollback = { _, _ -> true },
    )

    @Test
    fun 四类产物跑完工作区里只有流水账那一个文件() {
        WishProduct.entries.forEach { product ->
            setUp()
            val p = WishPipeline(workspaceRoot, inertPorts()) { 1_000L }
            val out = p.submit("给 $product 许愿", product)
            assertTrue("$product 要出规格", out is WishOutcome.AwaitingConfirmation)
            val wishId = (out as WishOutcome.AwaitingConfirmation).wishId
            p.confirm(wishId, out.spec.candidates.map { it.id })

            val files = workspaceRoot.walkTopDown().filter { it.isFile }.toList()
            val rel = files.map { it.relativeTo(workspaceRoot).invariantSeparatorsPath }.sorted()
            val only = "${WishLedger.DIR_NAME}/${WishLedger.FILE_NAME}"
            assertEquals("$product 跑完只许多出一个账文件：$rel", listOf(only), rel)
            val ledger = File(workspaceRoot, only)
            assertTrue("账要真的落了（非空）", ledger.length() > 0)
            assertTrue("每一步都有一行账", ledger.readLines().size >= 3)
        }
    }

    @Test
    fun 源级_许愿包除技能写口外没有任何生产写口() {
        val relRoot = "src/main/java/com/example/zhengdao/baopu/wish"
        val sources = GateIo.kotlinSources(relRoot)
        // 防空转：扫不到文件／行数太少＝这条什么都没判（零读数不是绿）。
        assertTrue("扫到整个许愿包（8 个文件）", sources.size >= 8)
        assertTrue("扫到的行数够全（≥ 600 行）", sources.sumOf { it.second.lines().size } >= 600)

        val code = GateIo.stripComments(sources.joinToString("\n") { it.second })
        // 三条「不进生产」的写口一个都不许出现：卡片产物、台账、小说正文。
        listOf(
            "CardTools" to "卡片写口",
            "CardWriter" to "卡片写口",
            "card_build" to "卡片写口",
            "card_export" to "卡片导出",
            "DecisionLedger" to "台账写口",
            "NovelProjectStore" to "小说正文写口",
            "novel_continue" to "小说续写口",
        ).forEach { (needle, label) ->
            assertTrue("$label（$needle）不许出现在许愿包里", !code.contains(needle))
        }
        // 反钉：技能那条写口**必须**在（不在就是整包被删空，上面三条会假绿）。
        assertTrue("技能草稿写口要在（复用既有免批口）", code.contains("SkillAutoWrite.tryAutoWrite"))
        assertTrue("技能撤销写口要在（复用既有一键撤销）", code.contains("SkillAutoWrite.undoAutoWrite"))
    }
}
