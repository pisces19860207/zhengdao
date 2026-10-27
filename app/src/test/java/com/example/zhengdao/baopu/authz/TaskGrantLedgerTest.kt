// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：六步第 6 步的账（`<opId>:applied` 幂等追加）。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionAppend
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskGrantLedgerTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("baopu249-ledger").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        DecisionLedger.uninstallForTest()
    }

    private fun rows(): List<String> =
        DecisionLedger.fileIn(root).takeIf { it.isFile }
            ?.readLines(Charsets.UTF_8)
            ?.filter { it.isNotBlank() }
            .orEmpty()

    @Test
    fun 幂等键与门禁同源() {
        assertEquals("op-9:applied", TaskGrantLedger.keyOf("op-9"))
        assertEquals(TaskGrantGate.ledgerKeyOf("op-9"), TaskGrantLedger.keyOf("op-9"))
    }

    @Test
    fun 两个动作各落一条() {
        val request = AuthzFixtures.request()
        assertEquals(DecisionAppend.APPENDED, TaskGrantLedger.recordApplied(root, request, 1_000L))
        assertEquals(
            DecisionAppend.APPENDED,
            TaskGrantLedger.recordApplied(root, AuthzFixtures.request(opId = "op-2"), 1_001L),
        )
        assertEquals(2, rows().size)
        assertEquals(listOf("op-1", "op-2"), TaskGrantLedger.appliedOps(root).sorted())
    }

    @Test
    fun 落下的账读得回来且不可回滚() {
        TaskGrantLedger.recordApplied(root, AuthzFixtures.request(), 1_000L)
        val rec = DecisionLedger.read(root).single()
        assertEquals(DecisionKind.AUTO_ACT, rec.kind)
        assertEquals("op-1:applied", rec.evidence)
        assertFalse("通用写盘没有统一回滚引用 ⇒ 不许自称可回滚", rec.reversible)
        assertEquals(7L, rec.sessionId)
    }

    @Test
    fun 坏行在场时一个字节都不追加() {
        val target = DecisionLedger.fileIn(root)
        target.parentFile?.mkdirs()
        target.writeText("{这不是一条合法的账\n", Charsets.UTF_8)
        val before = target.readText()
        assertEquals(
            DecisionAppend.FAILED,
            TaskGrantLedger.recordApplied(root, AuthzFixtures.request(), 1_000L),
        )
        assertEquals("坏行在场不许往后追加", before, target.readText())
    }

    @Test
    fun 证据键与幂等键不一致就拒写() {
        val draft = DecisionDraft(
            kind = DecisionKind.AUTO_ACT,
            actor = DecisionActor.MODEL,
            reason = "对不上",
            evidence = "another-key",
            before = "a",
            after = "b",
            atMs = 1_000L,
        )
        assertEquals(DecisionAppend.FAILED, DecisionLedger.recordOnce(root, "op-1:applied", draft))
        assertTrue("被拒的账一个字节都不许落", rows().isEmpty())
    }

    @Test
    fun 已回滚标记不会把授权账算成可回滚() {
        TaskGrantLedger.recordApplied(root, AuthzFixtures.request(), 1_000L)
        val rec = DecisionLedger.read(root).single()
        assertFalse(rec.rolledBack)
        assertFalse(rec.reversible)
    }
}
