// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 判据：派工单-2026-10-24-第一百八十二批 §0.5/§1/§3（知识库三目录、原件只读、
// 迁移先备份→复制→校验、旧目录不删、外部库只索引不搬）。
package com.example.zhengdao.baopu.knowledge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KnowledgeLayoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ws(): File = tmp.newFolder("ws")

    private fun write(dir: File, rel: String, text: String): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        return f
    }

    @Test
    fun `三目录都在工作区下面的知识库根`() {
        val w = ws()
        assertEquals(File(w, "知识库/原始"), KnowledgeLayout.rawDir(w))
        assertEquals(File(w, "知识库/整理"), KnowledgeLayout.organizedDir(w))
        assertEquals(File(w, "知识库/索引"), KnowledgeLayout.indexDir(w))
        assertTrue(KnowledgeLayout.ensureDirs(w))
        assertTrue(KnowledgeLayout.rawDir(w).isDirectory)
        assertTrue(KnowledgeLayout.organizedDir(w).isDirectory)
        assertTrue(KnowledgeLayout.indexDir(w).isDirectory)
    }

    @Test
    fun `原始只读_写目标判据拒原件区`() {
        val w = ws()
        KnowledgeLayout.ensureDirs(w)
        assertTrue(KnowledgeLayout.isInsideRaw(w, File(KnowledgeLayout.rawDir(w), "a.txt")))
        assertFalse(KnowledgeLayout.isWritableTarget(w, File(KnowledgeLayout.rawDir(w), "a.txt")))
        assertTrue(KnowledgeLayout.isWritableTarget(w, File(KnowledgeLayout.organizedDir(w), "doc/a.md")))
        assertTrue(KnowledgeLayout.isWritableTarget(w, File(KnowledgeLayout.indexDir(w), "x.json")))
        assertFalse(KnowledgeLayout.isWritableTarget(w, File(w, "产物/x.md")))
    }

    @Test
    fun `外部根_默认投递与私有原始_再并外部库清单`() {
        val w = ws()
        val files = tmp.newFolder("files")
        val deliver = LibraryPathsDir.deliver(w)
        deliver.mkdirs()
        val rawPrivate = LibraryPathsDir.rawPrivate(files)
        rawPrivate.mkdirs()
        val outside = tmp.newFolder("outside")
        write(File(w, "知识库"), "外部库.txt", "# 注释\n${outside.absolutePath}\n相对/路径\n")

        val roots = KnowledgeLayout.externalRoots(w, files)
        assertTrue(roots.any { it.name == "投递" && it.dir == deliver })
        assertTrue(roots.any { it.name == "私有原始" && it.dir == rawPrivate })
        assertTrue(roots.any { it.dir == outside })
        // 相对路径那行必须被跳过；知识库自身不许当外部根
        assertEquals(3, roots.size)
        assertTrue(roots.none { it.dir == KnowledgeLayout.knowledgeDir(w) })
    }

    @Test
    fun `迁移_只复制不覆盖不删源_二遍幂等`() {
        val w = ws()
        val legacy = write(w, "资料库/原始/书.txt", "旧内容")
        val report = KnowledgeLayout.migrateLegacy(w)
        assertEquals(1, report.copied)
        val target = File(KnowledgeLayout.rawDir(w), "书.txt")
        assertTrue(target.isFile)
        assertEquals("旧内容", target.readText())
        assertTrue("备份必须先落地", File(KnowledgeLayout.migrationBackupDir(w), "书.txt").isFile)
        assertTrue("旧原件一个字节都不许删", legacy.isFile)
        assertTrue(KnowledgeLayout.migrationPendingFile(w).readText().contains("书.txt"))

        val second = KnowledgeLayout.migrateLegacy(w)
        assertEquals(0, second.copied)
        assertEquals("目标已在 ⇒ 幂等跳过", 1, second.skipped)
    }

    @Test
    fun `迁移_目标已存在且内容不同_不覆盖`() {
        val w = ws()
        KnowledgeLayout.ensureDirs(w)
        write(KnowledgeLayout.rawDir(w), "书.txt", "新内容")
        write(w, "资料库/原始/书.txt", "旧内容")
        val r = KnowledgeLayout.migrateLegacy(w)
        assertEquals(0, r.copied)
        assertEquals(1, r.skipped)
        assertEquals("新内容", File(KnowledgeLayout.rawDir(w), "书.txt").readText())
    }

    @Test
    fun `索引目录自己不进索引`() {
        assertTrue(KnowledgeLayout.excludedFromIndex("索引"))
        assertTrue(KnowledgeLayout.excludedFromIndex("索引/知识库索引.json"))
        assertFalse(KnowledgeLayout.excludedFromIndex("原始/索引.txt"))
    }

    /** 只为测试构造默认路径（生产代码不经过这里）。 */
    private object LibraryPathsDir {
        fun deliver(w: File) = com.example.zhengdao.baopu.library.LibraryPaths.deliverDirUnder(w)
        fun rawPrivate(files: File) = com.example.zhengdao.baopu.library.LibraryPaths.rawDirUnder(files)
    }
}
