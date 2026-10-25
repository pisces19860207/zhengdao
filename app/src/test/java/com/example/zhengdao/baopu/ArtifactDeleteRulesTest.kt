// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 第一百七十八批 §0.9 · 「清单勾选批量删」纯判据（J1–J7）。
 *
 * 这一层直接用真临时目录断言，不靠界面状态：
 * - J1 只列「产物目录里、台账有记录」——用户自己的文件不列；
 * - J2 台账行原样保留、只在下面多一行 🗑 删除标记；
 * - J3 标过删除的从清单里消失，但 190 的查重指纹还认原行（删过的不重复推荐）；
 * - J4 一句确认的闸：没确认一条名字都不给，列表外的名字被剔掉；
 * - J5 台账里找不到 / 名字带路径成分 ⇒ 不列、不动；
 * - J6 台账写失败要说话（`no_ledger`），不假装记上了；
 * - J7 幂等：同一行标两次只有一行标记。
 */
class ArtifactDeleteRulesTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("zd178", "").let { it.delete(); it.mkdirs(); it }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(rel: String, text: String): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    private val ledger = """
        # 产物台账

        ## 2026-10-23 批次（10:00 落盘）
        - ☐ 20261023_微头条_1_楼下便利店老板娘.md（612 字）
        - ✔ 20261024_家庭_2_中年失业第三个月.txt（655 字）
    """.trimIndent()

    private val a = "20261023_微头条_1_楼下便利店老板娘.md"
    private val b = "20261024_家庭_2_中年失业第三个月.txt"

    // ── J1/J5：只列交集 ──────────────────────────────────────────────────

    @Test
    fun `J1_台账行解析出标题日期赛道字数与勾选状态`() {
        val rows = ArtifactDeleteRules.parseRows(ledger)
        assertEquals(2, rows.size)
        val first = rows[0].second
        assertEquals(a, first.name)
        assertEquals("楼下便利店老板娘", first.title)
        assertEquals("2026-10-23", first.date)
        assertEquals("微头条", first.track)
        assertEquals(612, first.chars)
        assertFalse(first.published)
        val second = rows[1].second
        assertEquals(b, second.name)
        assertEquals("家庭", second.track)
        assertTrue("✔ 是已发", second.published)
    }

    @Test
    fun `J1_v1命名也认_日期赛道从文件名来`() {
        val v1 = "- ✔ 微头条-20261022-0830-中年失业第三个月.md（655 字）"
        val item = ArtifactDeleteRules.parseRows(v1).single().second
        assertEquals("2026-10-22", item.date)
        assertEquals("微头条", item.track)
        assertEquals("中年失业第三个月", item.title)
    }

    @Test
    fun `J1_只列产物目录里且台账有记录的文件_用户自己的文件不进清单`() {
        val files = mapOf(a to 10L, b to 20L, "用户自己放的照片.jpg" to 5L)
        val rows = ArtifactDeleteRules.pickerRows(ledger, files)
        assertEquals("两条都在盘上且都有账", 2, rows.size)
        assertTrue("用户文件不在台账上 ⇒ 不列", rows.none { it.item.name == "用户自己放的照片.jpg" })
    }

    @Test
    fun `J1_台账有记录但盘上没有_不进清单`() {
        val files = mapOf(a to 10L, "用户自己放的照片.jpg" to 5L)
        val rows = ArtifactDeleteRules.pickerRows(ledger, files)
        assertEquals(1, rows.size)
        assertEquals(a, rows.single().item.name)
    }

    @Test
    fun `J1_按已发筛只留勾过的那条`() {
        val files = mapOf(a to 10L, b to 20L)
        val rows = ArtifactDeleteRules.pickerRows(ledger, files, includePublishedOnly = true)
        assertEquals(1, rows.size)
        assertEquals(b, rows.single().item.name)
    }

    @Test
    fun `J5_路径成分与隐藏名一律不列_构造不出产物目录外的路径`() {
        listOf("../原始/机密.md", "原始/机密.md", "\\\\server\\x.md", ".hidden.md", "..", ".").forEach {
            assertFalse("必须拒绝：$it", ArtifactDeleteRules.safeName(it))
            assertNull("构造不出路径：$it", ArtifactDeleteRules.fileIn(root, it))
        }
        assertTrue(ArtifactDeleteRules.safeName(a))
        val productDir = File(root, "产物").also { it.mkdirs() }
        assertEquals(
            File(productDir, a).absolutePath,
            ArtifactDeleteRules.fileIn(productDir, a)!!.absolutePath,
        )
    }

    // ── J2/J3/J7：台账标 deleted 但行不清 ────────────────────────────────

    @Test
    fun `J2_只在原行下面插一行标记_原行逐字不动`() {
        val result = ArtifactDeleteRules.markDeletedText(ledger, listOf(a), "2026-10-25 12:03")
        val expectedMarker = ArtifactDeleteRules.markerOf(a, "2026-10-25 12:03")
        assertEquals(listOf(a), result.marked)
        assertTrue("老行原样在", result.text.contains("- ☐ $a（612 字）"))
        assertTrue("原行下面有删除标记", result.text.contains("$a（612 字）\n  - 🗑 已删"))
        assertTrue(
            "硬判据：所有老行逐字同序保留，只多这一行标记",
            ArtifactDeleteRules.verifyMarked(ledger, result.text, listOf(expectedMarker)),
        )
    }

    @Test
    fun `J2_CRLF台账保持原换行_不混行尾`() {
        val crlf = ledger.replace("\n", "\r\n") + "\r\n"
        val result = ArtifactDeleteRules.markDeletedText(crlf, listOf(b), "2026-10-25 12:03")
        assertTrue("行尾全是 CRLF", result.text.contains("\r\n"))
        assertFalse("不许出现裸 LF", Regex("(?<!\r)\n").containsMatchIn(result.text))
        assertTrue(ArtifactDeleteRules.verifyMarked(crlf, result.text, listOf(ArtifactDeleteRules.markerOf(b, "2026-10-25 12:03"))))
    }

    @Test
    fun `J3_标过删除的不再进清单_但查重指纹仍认原行`() {
        val files = mapOf(a to 10L, b to 20L)
        val marked = ArtifactDeleteRules.markDeletedText(ledger, listOf(a), "2026-10-25 12:03").text
        assertTrue("已标删除 ⇒ 清单里没有这条", ArtifactDeleteRules.pickerRows(marked, files).none { it.item.name == a })
        assertEquals(1, ArtifactDeleteRules.pickerRows(marked, files).size)
        // 负例「删了仍重复推荐同题材」：190 的指纹必须还认原行（否则删过的题材会被当没写过）
        val refs = TopicSuggest.publishedFromLedger(marked)
        assertTrue(
            "查重指纹还认那条（${refs.map { it.slug }}）",
            refs.any { it.slug == "楼下便利店老板娘" },
        )
    }

    @Test
    fun `J7_同一行标两次只有一行标记`() {
        val once = ArtifactDeleteRules.markDeletedText(ledger, listOf(a), "2026-10-25 12:03")
        val twice = ArtifactDeleteRules.markDeletedText(once.text, listOf(a), "2026-10-25 12:04")
        assertEquals("第二次不再插", emptyList<String>(), twice.marked)
        assertEquals("认得出早标过", listOf(a), twice.already)
        assertEquals("文本一字不变", once.text, twice.text)
        assertEquals(1, Regex("🗑").findAll(twice.text).count())
    }

    @Test
    fun `J2负例_删掉原行或换了行序都必须判红`() {
        val result = ArtifactDeleteRules.markDeletedText(ledger, listOf(a), "2026-10-25 12:03")
        val marker = ArtifactDeleteRules.markerOf(a, "2026-10-25 12:03")
        val droppedRow = result.text.lines().filterNot { it.startsWith("- ☐ $a") }.joinToString("\n")
        assertFalse("删了原行 ⇒ 红", ArtifactDeleteRules.verifyMarked(ledger, droppedRow, listOf(marker)))
        val reordered = ledger.lines().reversed().joinToString("\n")
        assertFalse("行序变 ⇒ 红", ArtifactDeleteRules.verifyMarked(ledger, reordered, emptyList()))
        assertFalse("多写一行非标记 ⇒ 红", ArtifactDeleteRules.verifyMarked(ledger, ledger + "\n抄一句\n", emptyList()))
    }

    // ── J4：一句确认的闸 ──────────────────────────────────────────────────

    @Test
    fun `J4_没确认一条名字都不给_列表外的名字被剔掉`() {
        val selected = setOf(a, "列表外.md")
        val gate = ArtifactDeleteRules.batchGate(selected, setOf(a, b), confirmed = false)
        assertTrue(gate is ArtifactDeleteRules.BatchGate.NeedsConfirm)
        val ready = ArtifactDeleteRules.batchGate(selected, setOf(a, b), confirmed = true)
        assertEquals(listOf(a), (ready as ArtifactDeleteRules.BatchGate.Ready).names)
    }

    // ── J6：落盘协议 ──────────────────────────────────────────────────────

    @Test
    fun `J6_写回台账_留bak_原行还在_标记生效`() {
        val ledgerFile = write("产物/台账.md", ledger)
        val result = ArtifactDeleteRules.markDeletedInLedger(ledgerFile, listOf(a), 1_760_000_000_000L)
        assertTrue("写成了：${result.why}", result.ok)
        assertEquals(listOf(a), result.marked)
        val after = ledgerFile.readText()
        assertTrue("原行还在", after.contains("- ☐ $a（612 字）"))
        assertTrue("删除标记写进了台账", after.contains("🗑 已删 $a（"))
        assertEquals("备份是改前那一份", ledger, File(ledgerFile.parentFile, "台账.md.bak").readText())
        assertTrue("标记行可解析", ArtifactDeleteRules.deletedNames(after).contains(a))
    }

    @Test
    fun `J6_台账不存在要说失败_不假装记上了`() {
        val result = ArtifactDeleteRules.markDeletedInLedger(File(root, "产物/台账.md"), listOf(a), 1L)
        assertFalse(result.ok)
        assertEquals("no_ledger", result.why)
    }

    @Test
    fun `J6_第二批删除不被前一批标记判红`() {
        val ledgerFile = write("产物/台账.md", ledger)
        assertTrue("第一批", ArtifactDeleteRules.markDeletedInLedger(ledgerFile, listOf(a), 1_760_000_000_000L).ok)
        val second = ArtifactDeleteRules.markDeletedInLedger(ledgerFile, listOf(b), 1_760_000_060_000L)
        assertTrue("第二批也要写成：${second.why}", second.ok)
        assertEquals(listOf(b), second.marked)
        val after = ledgerFile.readText()
        assertEquals("两条标记都在", setOf(a, b), ArtifactDeleteRules.deletedNames(after))
    }

    @Test
    fun `J6_台账里找不到这一行_只报missing_不写文件`() {
        val ledgerFile = write("产物/台账.md", ledger)
        val before = ledgerFile.readText()
        val marked = ArtifactDeleteRules.markDeletedText(ledger, listOf("没这条.md"), "2026-10-25 12:03")
        assertEquals(listOf("没这条.md"), marked.missing)
        val result = ArtifactDeleteRules.markDeletedInLedger(ledgerFile, listOf("没这条.md"), 1L)
        assertTrue("没这条不算失败，但不能写", result.ok)
        assertEquals("什么都没标", emptyList<String>(), result.marked)
        assertEquals("文件一字不动", before, ledgerFile.readText())
    }
}
