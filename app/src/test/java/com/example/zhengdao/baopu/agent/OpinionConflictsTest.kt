// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十七批判据③：`.conflicts.jsonl` 每次合并追加一行；无冲突时不创建文件。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.OpinionMerger
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpinionConflictsTest {

    private lateinit var products: File

    @Before
    fun setUp() {
        OpinionWiring.resetForTest()
        products = Files.createTempDirectory("zd-conflicts").toFile()
        OpinionWiring.installConflictProducts { products }
    }

    @After
    fun tearDown() {
        products.deleteRecursively()
        OpinionWiring.resetForTest()
    }

    private fun opinion(source: Source, severity: Severity, gist: String, evidence: String) =
        Opinion(source, severity, gist, ActionCode.REVIEW, LawRef.L5, listOf(evidence))

    private fun conflictPair(): List<Opinion> = listOf(
        opinion(Source.PATROL, Severity.CRITICAL, "漏账", "event:1"),
        opinion(Source.AUDIT, Severity.MAJOR, "账实不符", "event:1"),
    )

    @Test
    fun `判据3_每次合并追加一行且能读回`() {
        val result = OpinionMerger.merge(conflictPair()) { group -> group.first() }
        assertEquals(1, OpinionWiring.recordConflicts(result.conflicts, now = 1_700_000_000_000L))

        val file = OpinionConflicts.fileIn(products)
        assertTrue("有冲突才建文件", file.isFile)
        val parsed = OpinionConflicts.readAll(products).single()
        assertEquals("ev1:event:1", parsed.eventKey)
        assertEquals(setOf("漏账", "账实不符"), setOf(parsed.a.gist, parsed.b.gist))
        assertEquals(1_700_000_000_000L, parsed.at)
    }

    @Test
    fun `负例_无冲突时不创建文件`() {
        assertEquals(0, OpinionWiring.recordConflicts(emptyList()))
        assertFalse(OpinionConflicts.fileIn(products).exists())
    }

    @Test
    fun `两条分歧落两行`() {
        val pair = conflictPair()
        val first = OpinionMerger.conflictsOf(pair).single()
        val second = first.copy(
            eventKey = "ev1:event:2",
            a = first.a.copy(gist = "越界"),
            b = first.b.copy(gist = "未分类"),
        )

        assertEquals(2, OpinionWiring.recordConflicts(listOf(first, second), now = 42L))
        assertEquals(2, OpinionConflicts.fileIn(products).readLines(Charsets.UTF_8).size)
    }
}
