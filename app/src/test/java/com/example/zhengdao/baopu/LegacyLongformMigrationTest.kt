// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开接口：java.io.File / JUnit 4（TemporaryFolder）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.library.FaceIndex
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 第一百七十五批 · S1 第 3 条：**旧层 `资料库/产物` 存量的四步迁移**（设计 §7，用户「不许直接删」）。
 *
 * 四步＝**备份 → 复制 → 校验 → 待删清单**；全程只读原件，只写自己那三处
 * （`产物/`、`产物/_迁移存档/旧资料库产物/`、`产物/从旧资料库搬来_待删.txt`）。
 * 这里钉的是「不丢、不覆盖、不删、对得上账」：
 * - 内容逐字过来（sha256＋字节数双对）；
 * - 同名**不覆盖**，连备份与清单都不写；
 * - 原件**一个都没删**（所以 `资料库/` 会留着，直到用户按清单手动删完——设计里写明的代价）；
 * - 幂等：待删清单就是「已搬」判据 ⇒ 第二遍全零，且不建第二个带日期的存档目录。
 *
 * 纯 [File] 进出、不碰 Context ⇒ JVM 直测（与 157 的 [MemoryLayoutTest] 同一套路）。
 */
class LegacyLongformMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ws(): File = tmp.newFolder("工作区")

    /** App 私有根：一个测试可能跑两遍 migrate ⇒ 只建一次（TemporaryFolder 同名二建会抛 IOException）。 */
    private val privateRoot: File by lazy { tmp.newFolder("files") }
    private fun filesDir(): File = privateRoot

    /** 往旧层 `资料库/产物/` 里放一份存量。 */
    private fun legacy(w: File, rel: String, text: String): File {
        val out = File(File(w, "资料库/产物"), rel)
        out.parentFile?.mkdirs()
        out.writeText(text, Charsets.UTF_8)
        return out
    }

    /** 一层目录名（排序后比；幂等与「别乱建目录」判据用）。 */
    private fun children(dir: File): List<String> = dir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    /** 整棵树的**文件名**（不含目录名），查串味用。 */
    private fun fileNames(dir: File): List<String> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.flatMap { if (it.isDirectory) fileNames(it) else listOf(it.name) } ?: emptyList()
    }

    // ── 四步本体 ────────────────────────────────────────────────────────────

    @Test
    fun `四步_复制进产物并备份_相对路径保留_原件一个没删`() {
        val w = ws()
        val srcA = legacy(w, "20261020_微头条_1_旧文.md", "# 旧文\n\n正文")
        val srcB = legacy(w, "子目录/另一篇.md", "另一篇")

        val r = MemoryLayout.migrate(w, filesDir())

        assertEquals("两件存量都走完四步：${r.summary()}", 2, r.copiedLegacyProducts)
        val dstA = File(MemoryLayout.productsDir(w), "20261020_微头条_1_旧文.md")
        val dstB = File(MemoryLayout.productsDir(w), "子目录/另一篇.md")
        assertEquals("# 旧文\n\n正文", dstA.readText())
        assertEquals("相对路径要原样保留", "另一篇", dstB.readText())
        // ③ 校验：摘要＋字节数都对得上才算搬成
        assertEquals(FaceIndex.sha256(srcA), FaceIndex.sha256(dstA))
        assertEquals(FaceIndex.sha256(srcB), FaceIndex.sha256(dstB))
        assertEquals(srcA.length(), dstA.length())
        // ① 备份在固定名的存档目录里
        assertTrue(File(MemoryLayout.legacyProductArchiveDir(w), "20261020_微头条_1_旧文.md").isFile)
        assertTrue(File(MemoryLayout.legacyProductArchiveDir(w), "子目录/另一篇.md").isFile)
        // 不删：两处原件都还在
        assertTrue("原件一个不许删（删归用户）", srcA.isFile && srcB.isFile)
        assertTrue("有存量 ⇒ 资料库/ 留着（「不许直接删」的代价）", File(w, "资料库").isDirectory)
        // ④ 待删清单逐条可对账
        val lines = MemoryLayout.legacyPendingDeleteList(w).readText().lines().filter { it.startsWith("- ") }
        assertEquals(2, lines.size)
        assertTrue("每行都带摘要", lines.all { it.contains("| sha256=") })
        assertTrue(
            "第一行必须逐字是「- 相对路径 | 字节 | sha256=摘要」",
            lines.first().startsWith("- 20261020_微头条_1_旧文.md | ${srcA.length()} | sha256=${FaceIndex.sha256(srcA)}"),
        )
    }

    @Test
    fun `四步_幂等_二遍全零_不建第二个存档目录`() {
        val w = ws()
        legacy(w, "a.md", "甲")
        legacy(w, "子目录/b.md", "乙")

        val first = MemoryLayout.migrate(w, filesDir())
        assertTrue("第一遍要有存量搬动：${first.summary()}", !first.isEmpty)
        val second = MemoryLayout.migrate(w, filesDir())

        assertTrue("第二遍必须全零（待删清单就是「已搬」判据）：${second.summary()}", second.isEmpty)
        assertEquals(
            "清单不许出现重复行", 2,
            MemoryLayout.legacyPendingDeleteList(w).readText().lines().count { it.startsWith("- ") },
        )
        assertEquals(
            "存档目录固定名，第二遍不许建出带日期的第二份", listOf("旧资料库产物"),
            children(File(MemoryLayout.productsDir(w), BaopuKernel.Data.DIR_MIGRATED_ARCHIVE)),
        )
        assertEquals("原件还在", "甲", File(w, "资料库/产物/a.md").readText())
    }

    @Test
    fun `四步_同名不覆盖_撞名的一件都不写`() {
        val w = ws()
        val src = legacy(w, "撞车.md", "旧内容")
        val dst = File(MemoryLayout.productsDir(w), "撞车.md")
        dst.parentFile?.mkdirs()
        dst.writeText("新内容", Charsets.UTF_8)

        val r = MemoryLayout.migrate(w, filesDir())

        assertEquals(0, r.copiedLegacyProducts)
        assertEquals("新内容", dst.readText())
        assertEquals("旧内容", src.readText())
        assertTrue("要记账给用户看：${r.skipped}", r.skipped.any { it.contains("撞车.md") && it.contains("不覆盖") })
        assertFalse("撞名的连备份都不写", File(MemoryLayout.legacyProductArchiveDir(w), "撞车.md").isFile)
        assertFalse("没走完四步就不许进待删清单", MemoryLayout.legacyPendingDeleteList(w).isFile)
    }

    @Test
    fun `四步_旧台账与新台账同名_绝不覆盖已发记录`() {
        val w = ws()
        legacy(w, "台账.md", "# 老台账\n- ☐ 20261020 那篇")
        val newLedger = File(MemoryLayout.productsDir(w), "台账.md")
        newLedger.parentFile?.mkdirs()
        newLedger.writeText("# 新台账\n- ✔ 今天的", Charsets.UTF_8)

        val r = MemoryLayout.migrate(w, filesDir())

        assertEquals(0, r.copiedLegacyProducts)
        assertEquals("# 新台账\n- ✔ 今天的", newLedger.readText())
        assertEquals("# 老台账\n- ☐ 20261020 那篇", File(w, "资料库/产物/台账.md").readText())
        assertEquals("台账冲突要单独报给用户", 1, r.skipped.count { it.contains("台账.md") })
    }

    // ── 红线：只写产物，别的一个目录都不许碰 ─────────────────────────────────

    @Test
    fun `红线_存量四步只写产物_原始与记忆与投递一个字节不碰`() {
        val w = ws()
        val fd = filesDir()
        legacy(w, "正文.md", "正文")
        val raw = File(w, "资料库/原始/原件.txt")
        raw.parentFile?.mkdirs()
        raw.writeText("原文", Charsets.UTF_8)

        val r = MemoryLayout.migrate(w, fd)

        assertEquals(1, r.copiedLegacyProducts)
        assertFalse("产物/ 里不许混进原件", fileNames(MemoryLayout.productsDir(w)).contains("原件.txt"))
        assertFalse("投递/ 不许被这次迁移建出来", MemoryLayout.deliverDir(w).exists())
        assertEquals("记忆/ 只有 ensureDirs 那一层", listOf("日记"), children(MemoryLayout.memoryDir(w)))
        // 原件走的是 157 那条老路（进 App 私有原件区），与本次四步无关
        assertEquals("原文", File(MemoryLayout.rawPrivateDir(fd), "原件.txt").readText())
    }
}
