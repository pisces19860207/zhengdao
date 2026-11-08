// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十七批判据⑤：批/驳/搁置各落一行 208，且 kind 是**既有**的
// `advisor_opinion`（零新 kind/零新字段）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.inspect.OpinionConflict
import com.example.zhengdao.baopu.inspect.OpinionConflictSide
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConflictRoyalWiringTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zd-conflict-208").toFile()
        DecisionLedger.install(root)
        RoyalDecisionLedger.resetForTest()
    }

    @After
    fun tearDown() {
        DecisionLedger.uninstallForTest()
        RoyalDecisionLedger.resetForTest()
        root.deleteRecursively()
    }

    private val conflict = OpinionConflict(
        eventKey = "ev1:event:1",
        a = OpinionConflictSide("漏账", Severity.CRITICAL, Source.PATROL),
        b = OpinionConflictSide("账实不符", Severity.MAJOR, Source.AUDIT),
        at = 1_700_000_000_000L,
        evidence = listOf("event:1", "log:1"),
    )

    @Test
    fun `判据5_批驳搁置各落一行208且kind不变`() {
        val now = 1_700_000_000_000L
        RoyalVerdict.entries.forEachIndexed { i, verdict ->
            ConflictRoyalWiring.record(conflict, verdict, now + i)
        }

        val rows = DecisionLedger.read(root)
        assertEquals("批/驳/搁置各一行", 3, rows.size)
        assertTrue(
            "kind 必须复用既有 advisor_opinion：${rows.map { it.kind.wire }}",
            rows.all { it.kind == DecisionKind.ADVISOR_OPINION },
        )
        assertEquals("kind 表里没有新增冲突类", DecisionKind.ADVISOR_OPINION, DecisionKind.ofWire("advisor_opinion"))
        assertNull(DecisionKind.ofWire("conflict_decision"))
        assertEquals("御批账也各一行（压制重复上卡）", 3, RoyalDecisionLedger.replay().size)
    }

    @Test
    fun `208 行形状未变_字段集合与既有键一致`() {
        val row = ConflictRoyalWiring.record(conflict, RoyalVerdict.APPROVE, 1_700_000_000_000L)!!
        val keys = row.toJson().keys().asSequence().toSet()
        assertEquals(
            setOf(
                "v", "id", "kind", "group_if_known", "actor", "reason", "evidence", "before", "after",
                "at", "session", "reversible", "rollback_ref", "rollback_of", "executor",
            ),
            keys,
        )
    }
}
