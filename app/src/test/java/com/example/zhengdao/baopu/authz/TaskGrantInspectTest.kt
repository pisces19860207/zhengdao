// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：勘验看**授权证据**（§7 末句）。
// 这里刻意不提供"数卡"的入口：入口都没有，就没法拿它当判据。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionAppend
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.inspect.ShadowVerdict
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskGrantInspectTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        TaskGrantRuntime.resetForTest()
        root = Files.createTempDirectory("baopu249-inspect").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        TaskGrantRuntime.resetForTest()
        DecisionLedger.uninstallForTest()
    }

    private fun allowDecision(state: GrantState): GateDecision =
        TaskGrantGate.evaluate(AuthzFixtures.request(), state)

    private fun appendRaw(evidence: String, atMs: Long, reason: String = "手写一条"): Unit {
        DecisionLedger.recordTo(
            root,
            DecisionDraft(
                kind = DecisionKind.AUTO_ACT,
                actor = DecisionActor.MODEL,
                reason = reason,
                evidence = evidence,
                before = "a:absent",
                after = "a:present",
                atMs = atMs,
            ),
        )
    }

    @Test
    fun 证据链完整才叫任务级授权() {
        val state = AuthzFixtures.state()
        val decision = allowDecision(state)
        assertTrue(TaskGrantLedger.recordApplied(root, AuthzFixtures.request(), 1_000L) == DecisionAppend.APPENDED)
        val records = DecisionLedger.read(root)

        val ins = TaskGrantInspect.inspect(
            decision = decision,
            state = state,
            records = records,
            nowMonoMs = AuthzFixtures.NOW + 1_000L,
            processEpoch = AuthzFixtures.EPOCH,
        )
        assertTrue("证据链应当完整：${ins.problem}", ins.ok)
        assertEquals(AuthEvidence.TASK_GRANT, ins.evidence)
        assertEquals("grant-249", ins.evidenceId)
        assertEquals(records.single().id, ins.ledgerId)
        assertEquals("六步各一条事实", GrantStep.entries.size, ins.facts.size)
        assertEquals("objects=true", ins.facts.first())
        assertEquals("record=true", ins.facts.last())
    }

    @Test
    fun 没落账就不算有证据() {
        val state = AuthzFixtures.state()
        val ins = TaskGrantInspect.inspect(
            allowDecision(state),
            state,
            emptyList(),
            AuthzFixtures.NOW + 1_000L,
            AuthzFixtures.EPOCH,
        )
        assertFalse(ins.ok)
        assertEquals(AuthEvidence.NO_DATA, ins.evidence)
        assertTrue(ins.problem.contains("没有这条动作的账"))
    }

    @Test
    fun 一动作两账就说不清() {
        val state = AuthzFixtures.state()
        appendRaw("op-1:applied", 1_000L, "第一条")
        appendRaw("op-1:applied", 1_001L, "第二条")
        assertEquals(2, DecisionLedger.read(root).size)
        val ins = TaskGrantInspect.inspect(
            allowDecision(state),
            state,
            DecisionLedger.read(root),
            AuthzFixtures.NOW + 1_000L,
            AuthzFixtures.EPOCH,
        )
        assertFalse(ins.ok)
        assertEquals(AuthEvidence.NO_DATA, ins.evidence)
        assertTrue(ins.problem.contains("落了 2 条账"))
    }

    @Test
    fun 换了进程老授权不是证据() {
        val state = AuthzFixtures.state()
        appendRaw("op-1:applied", 1_000L)
        val ins = TaskGrantInspect.inspect(
            allowDecision(state),
            state,
            DecisionLedger.read(root),
            AuthzFixtures.NOW + 1_000L,
            "another-epoch",
        )
        assertFalse(ins.ok)
        assertTrue(ins.problem.contains("进程换了"))
    }

    @Test
    fun 到期之后不是证据() {
        val state = AuthzFixtures.state()
        appendRaw("op-1:applied", 1_000L)
        val ins = TaskGrantInspect.inspect(
            allowDecision(state),
            state,
            DecisionLedger.read(root),
            AuthzFixtures.NOW + 60 * AuthzFixtures.MIN,
            AuthzFixtures.EPOCH,
        )
        assertFalse(ins.ok)
        assertTrue(ins.problem.contains("已到期"))
    }

    @Test
    fun 没放行的动作没有授权证据() {
        val state = AuthzFixtures.state()
        val stopped = TaskGrantGate.evaluate(
            AuthzFixtures.request(shadow = ShadowSignal.Rehearsed(ShadowVerdict.UNVERIFIABLE)),
            state,
        )
        val ins = TaskGrantInspect.inspect(
            stopped,
            state,
            emptyList(),
            AuthzFixtures.NOW + 1_000L,
            AuthzFixtures.EPOCH,
        )
        assertFalse(ins.ok)
        assertEquals(AuthEvidence.NO_DATA, ins.evidence)
        assertTrue(ins.problem.contains("不是放行的动作"))
    }

    @Test
    fun 六步少一步就说不清() {
        val state = AuthzFixtures.state()
        appendRaw("op-1:applied", 1_000L)
        val full = allowDecision(state)
        val truncated = full.copy(steps = full.steps.dropLast(1))
        val ins = TaskGrantInspect.inspect(
            truncated,
            state,
            DecisionLedger.read(root),
            AuthzFixtures.NOW + 1_000L,
            AuthzFixtures.EPOCH,
        )
        assertFalse(ins.ok)
        assertTrue(ins.problem.contains("六步没走完"))
    }
}
