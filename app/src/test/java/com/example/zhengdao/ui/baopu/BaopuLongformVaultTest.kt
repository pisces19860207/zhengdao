// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开接口：java.io.File / JUnit 4（TemporaryFolder）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.MemoryLayout
import java.io.File
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 第一百七十五批 · S1 **组合门禁**（用户 2026-10-24 原话：「这是关键，不是顺手」）。
 *
 * ## 这里钉的是哪条真缺陷
 * 179（长文落盘）与 157（四目录收口）**各自的测试都绿**，唯独没人把两件事串起来跑，
 * 于是漏过一条：179 的 `vaultRoot` 多写了 `资料库/` 一层 ⇒ 每存一篇长文就 `mkdirs()` 把
 * 157 已经搬空删掉的旧布局**原地复活** ⇒ 非空 ⇒ 「搬空就删」永远不成立。
 * 本文件就是那条缺失的组合用例（派工单 S1 第 2 条两条断言都在这里）：
 * 1. `save()` 之后跑 [MemoryLayout.migrate] ⇒ `<工作区>/资料库/` **必须不存在**；
 * 2. 台账对账 ⇒ 路径恰为 `<工作区>/产物/台账.md`。
 *
 * ## 为什么不直接调 `save()`
 * 它要 Android `Context`，本仓是纯 JVM 测试栈（无 Robolectric）。所以落点判据抽成
 * [BaopuLongformStore.vaultRootUnder]／[BaopuLongformStore.productDirOf] 两个纯函数，
 * 这里用**同一对函数**复现 save() 的三步（建目录→写正文→记台账），
 * 再用 `红线_*` 那条钉住 `save()` 确实走这一对函数——防以后另起炉灶绕过本门禁。
 */
class BaopuLongformVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ws(): File = tmp.newFolder("工作区")

    /** App 私有根：一个测试可能跑两遍 migrate ⇒ 只建一次（TemporaryFolder 同名二建会抛 IOException）。 */
    private val privateRoot: File by lazy { tmp.newFolder("files") }
    private fun filesDir(): File = privateRoot
    private fun slash(f: File): String = f.path.replace('\\', '/')

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    /** save() 的等价复现（同一对纯函数）：建 `产物/` → 写正文 → 记台账。@return 正文文件 */
    private fun saveLikeJvm(workspace: File, markdown: String, title: String): File {
        val root = BaopuLongformStore.vaultRootUnder(workspace)
        val dir = BaopuLongformStore.productDirOf(root)
        assertTrue("建目录必须成功", dir.isDirectory || dir.mkdirs())
        val now = Calendar.getInstance()
        val y = now.get(Calendar.YEAR)
        val m = now.get(Calendar.MONTH) + 1
        val d = now.get(Calendar.DAY_OF_MONTH)
        val name = BaopuLongformPolicy.trackFileName(
            y, m, d, BaopuLongformStore.DEFAULT_TRACK, 1, title, "md",
        )
        File(dir, name).writeText(markdown, Charsets.UTF_8)
        val ledger = BaopuLongformPolicy.pickLedger(root, dir)
        val section = BaopuLongformPolicy.ledgerSection(
            y, m, d, 0, 0, listOf(name to BaopuLongformPolicy.wordCount(markdown)),
        )
        ledger.writeText(
            BaopuLongformPolicy.mergeAppend(BaopuLongformPolicy.ledgerHeader().joinToString("\n"), section)
                .joinToString("\n"),
            Charsets.UTF_8,
        )
        return File(dir, name)
    }

    // ── 门禁 1：save → migrate ⇒ 资料库/ 不许存在 ─────────────────────────────

    @Test
    fun `组合门禁_存长文之后跑迁移_工作区不许有资料库`() {
        val w = ws()
        val saved = saveLikeJvm(w, "# 测试长文\n\n正文", "测试长文")

        val first = MemoryLayout.migrate(w, filesDir())

        // 先判这条：它就是派工单 S1 第 2 条的那句「save() 之后跑 migrate ⇒ 资料库/ 必须不存在」
        assertFalse(
            "save→migrate 之后 资料库/ 必须不存在（旧缺陷就是它被 mkdirs 复活）：${first.summary()}",
            File(w, "资料库").exists(),
        )
        assertEquals("新正文必须平铺在 <工作区>/产物/ 一层", slash(MemoryLayout.productsDir(w)), slash(saved.parentFile!!))
        val second = MemoryLayout.migrate(w, filesDir())
        assertTrue("第二遍必须全零：${second.summary()}", second.isEmpty)
    }

    @Test
    fun `组合门禁_旧存量工作区_新正文一件也不落进资料库`() {
        val w = ws()
        File(w, "资料库/产物").mkdirs()
        File(w, "资料库/产物/20261020_微头条_1_旧文.md").writeText("# 旧文", Charsets.UTF_8)

        val saved = saveLikeJvm(w, "# 新文\n\n正文", "新文")
        val r = MemoryLayout.migrate(w, filesDir())

        assertEquals(slash(MemoryLayout.productsDir(w)), slash(saved.parentFile!!))
        assertEquals("存量走四步复制过来：${r.summary()}", 1, r.copiedLegacyProducts)
        assertEquals("# 旧文", File(MemoryLayout.productsDir(w), "20261020_微头条_1_旧文.md").readText())
        assertTrue("原件一个不许删（删归用户）", File(w, "资料库/产物/20261020_微头条_1_旧文.md").isFile)
        assertEquals(
            "新落点不许在 资料库/产物 里再造任何文件（只剩待删的那一份）",
            listOf("20261020_微头条_1_旧文.md"),
            File(w, "资料库/产物").list()?.toList()?.sorted(),
        )
    }

    // ── 门禁 2：台账对账 ─────────────────────────────────────────────────────

    @Test
    fun `对账_台账路径恰是产物下的台账md`() {
        val w = ws()
        val root = BaopuLongformStore.vaultRootUnder(w)
        val ledger = BaopuLongformPolicy.pickLedger(root, BaopuLongformStore.productDirOf(root))

        assertEquals(slash(File(MemoryLayout.productsDir(w), "台账.md")), slash(ledger))
        assertFalse("台账路径里不许夹 资料库 那一层", slash(ledger).contains("/资料库/"))

        saveLikeJvm(w, "# 甲\n\n正文", "甲")
        assertTrue("真写过一次，对账才算数", File(MemoryLayout.productsDir(w), "台账.md").isFile)
    }

    @Test
    fun `外置根标记读自产物目录_换根也不许带回资料库`() {
        val w = ws()
        val ext = tmp.newFolder("外置库")
        val marker = File(BaopuLongformStore.productDirOf(w), BaopuLongformPolicy.ROOT_MARKER)
        marker.parentFile?.mkdirs()
        marker.writeText(ext.path, Charsets.UTF_8)

        val root = BaopuLongformStore.vaultRootUnder(w)

        assertEquals(slash(ext), slash(root))
        assertEquals(
            slash(File(ext, "产物/台账.md")),
            slash(BaopuLongformPolicy.pickLedger(root, BaopuLongformStore.productDirOf(root))),
        )
        assertFalse("换外置根也不许把 资料库/ 带回来", File(w, "资料库").exists())
    }

    // ── 接线红线：save() 必须走这一对纯函数（否则上面的门禁会空转） ────────────

    @Test
    fun `红线_save与台账共用同一对派生函数_不许另起炉灶`() {
        val store = src("src/main/java/com/example/zhengdao/ui/baopu/BaopuLongformStore.kt")
        assertTrue(
            "save() 的目录必须来自 productDirOf(vaultRoot(context))",
            store.contains("val dir = productDirOf(vaultRoot(context))"),
        )
        assertTrue(
            "台账必须与正文同一个根、同一个 产物/",
            store.contains("appendLedger(root = vaultRoot(context), productDir = dir"),
        )
        assertFalse("落点不许再拼 资料库 字面量", store.contains("\"资料库\""))
        assertFalse("也不许引用旧布局常量", store.contains("LEGACY_LIBRARY_DIR"))
    }
}
