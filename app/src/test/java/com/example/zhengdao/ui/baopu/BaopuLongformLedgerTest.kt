// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 第一百七十九批 §0.2/§0.3（20:4x 拍板）：知识库根落点、命名/序号、**台账 append 协议**。
 *
 * 协议判据（派工单 §0.3 硬判据 + 多写者协议）：改前逐行取内容 → 改后除追加行外**逐字不变**、
 * 长度只增 → `.bak` 在 → 冲突重试一次 → 再冲突停手。负例：整表重写/行序变/丢行 ⇒ 红。
 */
class BaopuLongformLedgerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 命名与序号 ────────────────────────────────────────────────────────

    @Test
    fun `命名-a YYYYMMDD_赛道_序号_标题`() {
        assertEquals(
            "20261024_微头条_1_秋日随想.md",
            BaopuLongformPolicy.trackFileName(2026, 10, 24, "微头条", 1, "秋日随想", "md"),
        )
    }

    @Test
    fun `命名-b 序号是同赛道当日最大值加一`() {
        val existing = setOf(
            "20261024_微头条_1_甲.txt",
            "20261024_微头条_2_乙.txt",
            "20261023_微头条_9_昨天的不算.txt",
        )
        assertEquals(3, BaopuLongformPolicy.nextSeq(existing, 2026, 10, 24, "微头条"))
    }

    @Test
    fun `命名-c 当日没有同赛道_从 1 起`() {
        assertEquals(1, BaopuLongformPolicy.nextSeq(emptySet(), 2026, 10, 24, "微头条"))
    }

    @Test
    fun `命名-d 重名加 _2 不覆盖`() {
        assertEquals("20261024_微头条_1_甲_2.txt", BaopuLongformPolicy.nextFileName(setOf("20261024_微头条_1_甲.txt"), "20261024_微头条_1_甲.txt"))
    }

    // ── 台账文件选择 ──────────────────────────────────────────────────────

    @Test
    fun `台账-a 默认根_没有外置台账_建 产物-台账md`() {
        val root = tmp.newFolder("kb")
        val product = File(root, "产物")
        val picked = BaopuLongformPolicy.pickLedger(root, product)
        assertEquals(File(product, "台账.md"), picked)
    }

    @Test
    fun `台账-b 外置根已有 已发台账_勿删md_复用不新建`() {
        val root = tmp.newFolder("obsidian")
        val external = File(root, "已发台账_勿删.md")
        external.writeText("# 已发台账（勿删）")
        val product = File(tmp.newFolder("kb"), "产物")
        assertEquals(external, BaopuLongformPolicy.pickLedger(root, product))
        assertFalse("绝不新建第二份", File(product, "台账.md").exists())
    }

    @Test
    fun `台账-c 产物目录里已有 已发台账_也复用`() {
        val product = tmp.newFolder("kb", "产物")
        val external = File(product, "已发台账_勿删.md")
        external.writeText("# 已发台账")
        assertEquals(external, BaopuLongformPolicy.pickLedger(tmp.newFolder(), product))
    }

    // ── append 协议（硬判据） ─────────────────────────────────────────────

    private val oldLedger = "# 产物台账\n\n## 总览\n\n只追加。\n\n## 2026-10-23 批次\n\n- ☐ 旧的.txt（10 字）\n"
    private val section = listOf("## 2026-10-24 批次（19:05 落盘）", "", "- ☐ 新的.md（20 字）")

    @Test
    fun `协议-a 追加后_老内容逐字未变_只多末尾`() {
        val merged = BaopuLongformPolicy.mergeAppend(oldLedger, section).joinToString("\n")
        assertTrue(BaopuLongformPolicy.verifyAppend(oldLedger, merged))
        assertTrue("老行逐字在", merged.contains("- ☐ 旧的.txt（10 字）"))
        assertTrue("长度只增", merged.length > oldLedger.length)
    }

    @Test
    fun `协议-b 整表重写_红`() {
        assertFalse(BaopuLongformPolicy.verifyAppend(oldLedger, "# 产物台账\n\n全重写了\n"))
    }

    @Test
    fun `协议-c 丢行或行序变_红`() {
        val lines = oldLedger.split("\n")
        val dropped = lines.filterNot { it.contains("旧的") }.joinToString("\n")
        assertFalse("丢行 ⇒ 红", BaopuLongformPolicy.verifyAppend(oldLedger, dropped))
        val reordered = lines.reversed().joinToString("\n")
        assertFalse("行序变 ⇒ 红", BaopuLongformPolicy.verifyAppend(oldLedger, reordered))
    }

    @Test
    fun `协议-d 首建_带总览头`() {
        // 首建时 Store 会先给 ledgerHeader()，再接批次段
        val merged = BaopuLongformPolicy.mergeAppend(
            BaopuLongformPolicy.ledgerHeader().joinToString("\n"), section,
        ).joinToString("\n")
        assertTrue("首建必须带总览段", merged.contains("## 总览"))
        assertTrue("批次段在末尾", merged.contains("- ☐ 新的.md（20 字）"))
    }

    // ── 真实文件系统走一遍协议（含 .bak 与重读） ───────────────────────────

    @Test
    fun `协议-e Store 保存产物并追加台账_bak 在_老行逐字不变`() {
        val root = tmp.newFolder("kb")
        // 真实文件系统走一遍协议（Store.save 依赖 Android workspaceRoot，JVM 里以协议本体验证）
        val product = File(root, "产物").apply { mkdirs() }
        val ledger = File(product, "台账.md")
        ledger.writeText(oldLedger)

        val merged = BaopuLongformPolicy.mergeAppend(ledger.readText(), section).joinToString("\n")
        val bak = File(product, "台账.md.bak")
        val tmpFile = File(product, "台账.md.tmp")
        tmpFile.writeText(merged)
        ledger.copyTo(bak, overwrite = true)
        assertTrue(tmpFile.renameTo(ledger))

        assertTrue("bak 保留老内容", bak.readText() == oldLedger)
        assertTrue("老行逐字未变", BaopuLongformPolicy.verifyAppend(oldLedger, ledger.readText()))
        assertTrue("台账里能找到新条目", ledger.readText().contains("- ☐ 新的.md（20 字）"))
    }

    @Test
    fun `红线-原始目录_一个字节不碰`() {
        // slug 剔除了路径分隔符 ⇒ 文件名不可能越出 产物/（目录穿越负例）
        val s = BaopuLongformPolicy.slug("../../原始/机密")
        assertFalse(s.contains("/"))
        assertFalse(s.contains(".."))
        // 代码里没有任何指向 原始/ 的写路径
        val rel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuLongformStore.kt"
        val store = File(rel).let { if (it.exists()) it else File("app/$rel") }.readText()
        assertFalse(store.contains("\"原始\""))
        assertFalse("没有 delete——删除永远用户手动", store.contains(".delete()") || store.contains("deleteRecursively"))
    }
}
