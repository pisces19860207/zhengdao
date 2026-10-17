// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.library

import com.example.zhengdao.baopu.AskUserCardRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * LibraryImport 单测（刀 2 A 段；判据 §5.4／§5.5）。
 *
 * 钉死四件事（派工单 A 段原话级）：
 * 1. **只扫投递**——整理/、工作区根一律不进；
 * 2. **一次 ask_user**——四档选项，复制档 `isolated=false`；
 * 3. **不删任何文件**——「搬」= 复制进私有 ＋ 只列待删除清单；
 * 4. **不认领格式只列清单**——`_待外部转换.json` 有它、私有目录没有它。
 */
class LibraryImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var deliver: File
    private lateinit var done: File
    private lateinit var raw: File
    private lateinit var imp: LibraryImport

    private fun setup() {
        val lib = tmp.newFolder("资料库")
        deliver = File(lib, "投递").apply { mkdirs() }
        done = File(lib, "整理").apply { mkdirs() }
        raw = File(lib, "私有原始").apply { mkdirs() }
        imp = LibraryImport(deliver, done, raw)
    }

    private fun deliverFile(name: String, text: String = "内容"): File {
        val f = File(deliver, name)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    // ── 扫描与提问卡 ──────────────────────────────────────────────────────

    @Test
    fun `扫描_只认投递里的件`() {
        setup()
        deliverFile("a.txt")
        File(done, "别处.txt").writeText("x") // 整理里的不算料
        File(tmp.root, "散落.md").writeText("x") // 工作区根的更不算

        val scan = imp.scan()

        assertEquals(listOf("a.txt"), scan.files.map { it.relPath })
    }

    @Test
    fun `提问卡_四档且推荐第一`() {
        setup()
        deliverFile("a.txt")
        val card = imp.card(imp.scan())

        assertTrue(AskUserCardRules.isValid(card))
        assertEquals(4, card.options.size)
        assertTrue(card.options.first().recommended)
        assertEquals("move", card.options.first().id)
        assertTrue(card.question.contains("1"))
    }

    @Test
    fun `自由输入_关键词判档且窄的先判`() {
        setup()
        assertEquals(MoveChoice.MOVE_DOCS_ONLY, imp.parseChoice("只搬文档，图片不要"))
        assertEquals(MoveChoice.COPY, imp.parseChoice("复制一份"))
        assertEquals(MoveChoice.SKIP, imp.parseChoice("先不搬"))
        assertEquals(MoveChoice.MOVE, imp.parseChoice("搬走吧"))
        assertNull(imp.parseChoice("今天天气不错"))
        assertNull(imp.parseChoice(""))
    }

    // ── 执行：搬 ──────────────────────────────────────────────────────────

    @Test
    fun `搬_复制进私有_原件不动_列出待删除清单`() {
        setup()
        deliverFile("a.txt", "甲")
        deliverFile("sub/b.md", "乙")

        val out = imp.execute(MoveChoice.MOVE, now = 1000L)

        assertEquals(2, out.copied.size)
        // 原件还在投递里（不许自动删）
        assertTrue(File(deliver, "a.txt").isFile)
        assertTrue(File(deliver, "sub/b.md").isFile)
        // 私有里有了
        assertTrue(File(raw, "a.txt").isFile)
        assertTrue(File(raw, "sub/b.md").isFile)
        // 待删除清单 = 投递里已搬的那两份
        assertEquals(listOf("a.txt", "sub/b.md"), out.pendingDelete.sorted())
        val listText = out.pendingDeleteFile.readText()
        assertTrue(listText.contains("\"a.txt\""))
        // 搬 = 真隔离
        assertTrue(out.copied.all { it.isolated })
    }

    @Test
    fun `复制_隔离作废`() {
        setup()
        deliverFile("a.txt")
        val out = imp.execute(MoveChoice.COPY, now = 1000L)
        assertFalse(out.copied.single().isolated)
        // 复制档不列待删除清单（共享区那份是留底，不是垃圾）
        assertTrue(out.pendingDelete.isEmpty())
    }

    @Test
    fun `只搬文档_其他文本件仍留投递`() {
        setup()
        deliverFile("a.txt")
        deliverFile("b.json")
        val out = imp.execute(MoveChoice.MOVE_DOCS_ONLY, now = 1000L)
        assertEquals(listOf("a.txt"), out.copied.map { it.relPath })
        assertEquals(listOf("b.json"), out.leftBehind.map { it.relPath })
        assertTrue(File(raw, "a.txt").isFile)
        assertFalse(File(raw, "b.json").exists())
    }

    @Test
    fun `先不搬_什么都不动`() {
        setup()
        deliverFile("a.txt")
        val out = imp.execute(MoveChoice.SKIP, now = 1000L)
        assertTrue(out.copied.isEmpty())
        assertTrue(File(deliver, "a.txt").isFile)
        assertFalse(File(raw, "a.txt").exists())
    }

    @Test
    fun `不认领格式_只列清单不进私有`() {
        setup()
        deliverFile("a.pdf")
        deliverFile("b.png")
        val out = imp.execute(MoveChoice.MOVE, now = 1000L)

        assertTrue(out.copied.isEmpty())
        assertEquals(listOf("a.pdf", "b.png"), out.leftBehind.map { it.relPath }.sorted())
        val listText = out.externalListFile.readText()
        assertTrue(listText.contains("\"a.pdf\""))
        assertTrue(listText.contains("外部转换"))
        assertFalse(File(raw, "a.pdf").exists())
    }

    @Test
    fun `同内容已在私有_不重复复制`() {
        setup()
        deliverFile("a.txt", "甲")
        imp.execute(MoveChoice.MOVE, now = 1000L)
        val out2 = imp.execute(MoveChoice.MOVE, now = 2000L)

        assertTrue(out2.copied.single().alreadyThere)
        assertEquals("甲", File(raw, "a.txt").readText())
        // 待删除清单与旧清单合并（只增不减）：两批都指向同一份
        assertTrue(out2.pendingDeleteFile.readText().contains("\"a.txt\""))
    }

    @Test
    fun `撞名不同内容_加sha后缀让路不覆盖`() {
        setup()
        deliverFile("a.txt", "甲")
        imp.execute(MoveChoice.MOVE, now = 1000L)
        // 第二次投递一个同名不同内容的
        deliverFile("a.txt", "乙乙乙")
        val out = imp.execute(MoveChoice.MOVE, now = 2000L)

        val names = raw.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("a.701fa272.txt", "a.txt"), names)
        assertEquals("甲", File(raw, "a.txt").readText())
        assertNotNull(out.copied.firstOrNull { it.relPath == "a.txt" && !it.alreadyThere })
    }

    @Test
    fun `待删除清单_三批合并不丢中间那批`() {
        // 回归（2026-10-17 实测）：旧清单解析曾把整个文档当成一条 ⇒ 第三批会把第二批冲掉
        setup()
        deliverFile("a.txt")
        imp.execute(MoveChoice.MOVE, now = 1000L)
        deliverFile("b.txt")
        imp.execute(MoveChoice.MOVE, now = 2000L)
        deliverFile("c.txt")
        val out = imp.execute(MoveChoice.MOVE, now = 3000L)

        val text = out.pendingDeleteFile.readText()
        listOf("a.txt", "b.txt", "c.txt").forEach {
            assertTrue("待删除清单丢了 $it：\n$text", text.contains("\"$it\""))
        }
        assertEquals(3, text.lines().count { it.contains("\"rel_path\"") })
    }

    @Test
    fun `待外部转换清单_三批合并不丢中间那批`() {
        // 同上：外部清单也曾有「整文档当一条」的吞条目 bug
        setup()
        deliverFile("a.pdf")
        imp.execute(MoveChoice.MOVE, now = 1000L)
        deliverFile("b.png")
        imp.execute(MoveChoice.MOVE, now = 2000L)
        imp.appendExternalList(listOf(LibraryImport.ExternalEntry("c.zip", "压缩包（.zip），需外部转换")))

        val text = File(done, LibraryImport.FILE_EXTERNAL).readText()
        listOf("a.pdf", "b.png", "c.zip").forEach {
            assertTrue("外部清单丢了 $it：\n$text", text.contains("\"$it\""))
        }
    }

    @Test
    fun `待删除清单_与旧清单合并不清空`() {
        setup()
        deliverFile("a.txt")
        imp.execute(MoveChoice.MOVE, now = 1000L)
        deliverFile("b.txt")
        val out = imp.execute(MoveChoice.MOVE, now = 2000L)

        val text = out.pendingDeleteFile.readText()
        assertTrue("旧条目不许被抹掉", text.contains("\"a.txt\""))
        assertTrue(text.contains("\"b.txt\""))
    }

    @Test
    fun `待外部转换清单_可合并追加`() {
        setup()
        deliverFile("a.pdf")
        deliverFile("b.png")
        imp.execute(MoveChoice.MOVE, now = 1000L)
        imp.appendExternalList(listOf(LibraryImport.ExternalEntry("a.pdf", "不是 UTF-8 编码")))

        val text = File(done, LibraryImport.FILE_EXTERNAL).readText()
        // 两条都还在（b.png 是第一次执行写的）；a.pdf 同 rel_path 只一条，理由被后写的覆盖
        assertEquals(2, text.lines().count { it.contains("\"rel_path\"") })
        assertTrue(text.contains("不是 UTF-8 编码"))
        assertTrue(text.contains("b.png"))
    }}
