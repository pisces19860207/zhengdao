// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十六批 · 判据③的测试：许愿转正那一刻，台账要落**两处**——
// 业务账 `产物/台账.md`（179 行格式，append-only，老内容逐字保留）＋
// 机器账 `许愿/_流水线.jsonl` 的 PROMOTED 一行。跑的是真流水线（真端口），不是假件。
package com.example.zhengdao.baopu.wish

import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.agent.WishIOWiring
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class WishDeliveryLedgerTest {

    private lateinit var workspace: File
    private lateinit var cacheRoot: File

    @Before
    fun setUp() {
        workspace = tempDir("zd-w516-dl-ws")
        cacheRoot = tempDir("zd-w516-dl-cache")
        // 业务台账先有一行旧稿（append-only 的对照物）
        File(workspace, "产物").mkdirs()
        File(workspace, "产物/台账.md").writeText(
            "# 产物台账\n\n## 总览\n\n只追加不改旧行；打勾＝把 ☐ 改 ✔ 并补日期。\n\n" +
                "## 2026-11-06 批次（10:00 落盘）\n\n- ☐ 20261106_微头条_1_旧稿.md（300 字）\n",
            Charsets.UTF_8,
        )
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
        cacheRoot.deleteRecursively()
    }

    private fun tempDir(tag: String) = File.createTempFile(tag, "").let { it.delete(); it.mkdirs(); it }

    private fun ports() = WishIOWiring.ports(
        workspace = workspace,
        persona = PersonaFiles(projectRoot = tempDir("zd-w516-dl-proj"), globalRoot = tempDir("zd-w516-dl-glob")),
        cacheRoot = cacheRoot,
        readSkillAsset = { name ->
            val rel = "src/main/assets/baopu/skills/$name/SKILL.md"
            val f = File(rel).let { if (it.isFile) it else File("app/$rel") }
            if (f.isFile) f.readText(Charsets.UTF_8) else null
        },
        stamp = { "20261107-090000" },
    )

    @Test
    fun 判据3_转正时业务台账与机器账各落一行() {
        val pipeline = WishPipeline(workspace, ports()) { 1_000_000L }
        val wish = "我想要一个能把想法变成微头条的流程"
        val outcome = pipeline.submit(wish, WishProduct.CONTENT)
        val wishId = (outcome as WishOutcome.AwaitingConfirmation).wishId

        val gray = pipeline.confirm(wishId, listOf("T1", "T2", "T3"))
        assertTrue("自检过要进灰度：$gray", gray is WishOutcome.InGray)
        assertEquals("首次进灰度 uses=1", 1, (gray as WishOutcome.InGray).uses)

        assertTrue("第二次使用仍在灰度", pipeline.recordUse(wishId) is WishOutcome.InGray)
        val promoted = pipeline.recordUse(wishId)
        assertTrue("第三次使用要转正：$promoted", promoted is WishOutcome.Promoted)

        // ① 机器账一行：PROMOTED
        val machine = WishLedger.readAll(workspace).filter { it.wishId == wishId }
        assertTrue("机器账要有这条许愿的全程", machine.isNotEmpty())
        assertTrue(
            "机器账要有 PROMOTED 一行",
            machine.any { it.stage == WishStage.PROMOTED },
        )

        // ② 业务账一行：179 行格式，追加在老内容之后
        val ledgerText = File(workspace, "产物/台账.md").readText()
        assertTrue(
            "业务台账要有许愿稿那行",
            ledgerText.contains(Regex("- ☐ \\d{8}_许愿_1_.+\\.md（\\d+ 字）")),
        )
        val oldTail = ledgerText.indexOf("20261106_微头条_1_旧稿.md")
        val newTail = ledgerText.indexOf("_许愿_1_")
        assertTrue("老行还在", oldTail >= 0)
        assertTrue("新行追加在老行之后（append-only）", newTail > oldTail)

        // 稿子本体进了 产物/（转正＝离开草稿目录）
        val delivered = File(workspace, "产物").listFiles { f -> f.name.contains("_许愿_") }
        assertTrue("产物里要有许愿稿", delivered!!.isNotEmpty())
        // 草稿还在许愿目录（归档不真删）
        assertTrue(File(WishIOWiring.draftDir(workspace, wishId), "草稿卡.md").isFile)
    }

    @Test
    fun 负例_没转正时业务台账不动() {
        val pipeline = WishPipeline(workspace, ports()) { 1_000_000L }
        val outcome = pipeline.submit("我想要一个能把想法变成微头条的流程", WishProduct.CONTENT)
        val wishId = (outcome as WishOutcome.AwaitingConfirmation).wishId
        pipeline.confirm(wishId, listOf("T1", "T2", "T3"))

        val ledgerText = File(workspace, "产物/台账.md").readText()
        assertFalse("灰度期不许进业务台账", ledgerText.contains("_许愿_1_"))
        assertFalse("产物目录不许有许愿稿", File(workspace, "产物").listFiles { f -> f.name.contains("_许愿_") }!!.isNotEmpty())
    }
}
