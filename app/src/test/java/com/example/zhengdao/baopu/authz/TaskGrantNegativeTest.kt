// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：派工单 §9 的**八条负例**。每一条都只钉一个问题，
// 而且每一条都做成了"改一行就会红"的形状（交付报告的变异演练按它逐条跑）。
//
// 这个类叫 Negative 不是"否定测试"，是"这八件事一件都不许成立"。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.DecisionAppend
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskCode
import com.example.zhengdao.baopu.RiskDecision
import com.example.zhengdao.baopu.RiskReason
import com.example.zhengdao.baopu.RiskVerdict
import com.example.zhengdao.baopu.inspect.ShadowVerdict
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskGrantNegativeTest {

    @Before
    fun setUp() {
        TaskGrantRuntime.resetForTest()
    }

    @After
    fun tearDown() {
        TaskGrantRuntime.resetForTest()
    }

    private fun tempRoot(): File = Files.createTempDirectory("baopu249-neg").toFile()

    // ── 负例① 追认偷偷加回来 ────────────────────────────────────────────────
    @Test
    fun 负例1_追认不许偷偷加回来() {
        // 结构层：运行时上不许长出一个"事后补批"的口子
        val names = TaskGrantRuntime::class.java.declaredMethods.map { it.name.lowercase() }
        listOf("ratify", "retro", "provisional", "lateapprove", "approveafter", "recordafter", "catchup")
            .forEach { bad ->
                assertFalse("TaskGrantRuntime 长出了事后追认入口：$bad（实际方法：$names）", names.any { it.contains(bad) })
            }
        // 文案层：本批的生产源码里不许出现"追认/悬挂 24 小时"这套词
        authzSources().forEach { f ->
            val src = f.readText()
            listOf("追认", "ratif", "provisional", "86400", "24h", "24 * 60 * 60").forEach { word ->
                assertFalse("${f.name} 里出现了「$word」——追认那套不许回来", src.contains(word, ignoreCase = true))
            }
        }
    }

    // ── 负例② 整个工作区当对象 ───────────────────────────────────────────────
    @Test
    fun 负例2_整个工作区不许当对象() {
        val state = AuthzFixtures.state()
        listOf("*", "**", "/", "工作区", "整个工作区").forEach { bad ->
            val d = TaskGrantGate.evaluate(AuthzFixtures.request(objectId = bad), state)
            assertFalse("「$bad」不许放行", d.allow)
            assertEquals("「$bad」应停在对象这一步", TaskGrantGate.CODE_WHOLE_WORKSPACE, d.code)
            assertEquals(GateOutcome.ASK_SINGLE, d.outcome)
        }
        // 快照层也进不来（不是只靠门禁兜）
        val g = AuthzFixtures.grant(objects = listOf(AuthzFixtures.obj("整个工作区")))
        assertTrue(TaskGrantRules.validate(g).contains(GrantIssue.WHOLE_WORKSPACE))
        assertTrue(TaskGrantRuntime.install(g, AuthzFixtures.EPOCH, AuthzFixtures.NOW) is InstallResult.Refused)
    }

    // ── 负例③ 共享目录也走授权 ───────────────────────────────────────────────
    @Test
    fun 负例3_共享目录的文件不走任务授权() {
        val g = AuthzFixtures.grant(
            objects = listOf(AuthzFixtures.obj("a", location = ObjectLocation.SHARED_FILE)),
        )
        // ① 快照装不进来
        val install = TaskGrantRuntime.install(g, AuthzFixtures.EPOCH, AuthzFixtures.NOW)
        assertTrue("共享目录的快照必须装不进来", install is InstallResult.Refused)
        assertEquals(
            GrantEndReason.BOUNDARY_VIOLATION,
            (install as InstallResult.Refused).reason,
        )
        assertTrue(TaskGrantRules.validate(g).contains(GrantIssue.SHARED_FILE))
        // ② 就算有人硬造一个内存状态，六步第一步也拦
        val forged = GrantState(g, AuthzFixtures.EPOCH)
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(), forged)
        assertFalse(d.allow)
        assertEquals(TaskGrantGate.CODE_SHARED_FILE, d.code)
        assertEquals(GrantStep.OBJECTS, d.stoppedAt)
    }

    // ── 负例④ 重启恢复 ───────────────────────────────────────────────────────
    @Test
    fun 负例4_重启后老授权不许恢复() {
        val old = AuthzFixtures.grant(epoch = "old-process-epoch")
        // ① 老快照装不进新进程
        val install = TaskGrantRuntime.install(old, "new-process-epoch", AuthzFixtures.NOW)
        assertTrue(install is InstallResult.Refused)
        assertEquals(GrantEndReason.PROCESS_RESTARTED, (install as InstallResult.Refused).reason)
        assertTrue(TaskGrantRuntime.current() == null)
        // ② 运行时没有"读回来"的入口（不是"记得别读"，是根本没写）
        val names = TaskGrantRuntime::class.java.declaredMethods.map { it.name.lowercase() }
        listOf("restore", "load", "save", "persist", "readfrom", "writeTo").forEach { bad ->
            assertFalse("TaskGrantRuntime 长出了持久化入口：$bad", names.any { it.contains(bad.lowercase()) })
        }
        // ③ 门禁按进程代号判，不看"上次批过"
        val forged = GrantState(old, "old-process-epoch")
        val d = TaskGrantGate.evaluate(
            AuthzFixtures.request(epoch = "new-process-epoch"),
            forged,
        )
        assertEquals(TaskGrantGate.CODE_PROCESS_CHANGED, d.code)
        assertEquals(GateOutcome.ASK_SINGLE, d.outcome)
        assertFalse(d.allow)
    }

    // ── 负例⑤ 越界重问整单 ───────────────────────────────────────────────────
    @Test
    fun 负例5_越界只问增量不许重问整单() {
        val g = AuthzFixtures.grant(
            objects = listOf(
                AuthzFixtures.obj("a"),
                AuthzFixtures.obj("b"),
                AuthzFixtures.obj("c"),
            ),
        )
        val state = AuthzFixtures.state(g)
        val d = TaskGrantGate.evaluate(AuthzFixtures.request(objectId = "new-obj"), state)
        assertEquals(GateOutcome.ASK_DELTA, d.outcome)
        val delta = requireNotNull(d.delta) { "越界必须给出一问" }
        assertEquals("只问这次要的那一个", listOf("new-obj"), delta.deltaObjects)
        assertEquals(1, delta.deltaObjects.size)
        listOf("a", "b", "c").forEach { authorized ->
            assertFalse("已授权的 $authorized 不许出现在增量里", delta.deltaObjects.contains(authorized))
        }
        assertTrue("动作本来就在授权内 ⇒ 这一问不提动作", delta.deltaActions.isEmpty())
        assertEquals("new-obj", delta.askedObject)
        assertEquals("delta:new-obj|", delta.key)
    }

    // ── 负例⑥ UNKNOWN 放行 ───────────────────────────────────────────────────
    @Test
    fun 负例6_影子跑不出结论不许放行() {
        val state = AuthzFixtures.state()
        val unknown = TaskGrantGate.evaluate(
            AuthzFixtures.request(shadow = ShadowSignal.Rehearsed(ShadowVerdict.UNVERIFIABLE)),
            state,
        )
        assertEquals(GateOutcome.STOPPED, unknown.outcome)
        assertEquals(TaskGrantGate.CODE_NO_DATA, unknown.code)
        assertFalse(unknown.allow)

        // 没跑（连结论都没有）同样是 NO_DATA，而不是"默认没事"
        assertEquals(
            TaskGrantGate.CODE_NO_DATA,
            TaskGrantGate.evaluate(AuthzFixtures.request(shadow = ShadowSignal.None), state).code,
        )
        // 另外两种三态也各停各的
        assertEquals(
            TaskGrantGate.CODE_SHADOW_MISMATCH,
            TaskGrantGate.evaluate(
                AuthzFixtures.request(shadow = ShadowSignal.Rehearsed(ShadowVerdict.MISMATCH)),
                state,
            ).code,
        )
        assertEquals(
            TaskGrantGate.CODE_SHADOW_MISWIRED,
            TaskGrantGate.evaluate(
                AuthzFixtures.request(shadow = ShadowSignal.Rehearsed(ShadowVerdict.MISWIRED)),
                state,
            ).code,
        )
        // 只有 CONSISTENT 过
        assertTrue(TaskGrantGate.shadowAllows(ShadowVerdict.CONSISTENT))
        listOf(ShadowVerdict.MISMATCH, ShadowVerdict.MISWIRED, ShadowVerdict.UNVERIFIABLE)
            .forEach { assertFalse("${it.wire} 不许放行", TaskGrantGate.shadowAllows(it)) }
    }

    // ── 负例⑦ 授权覆盖 protected/hardDeny ────────────────────────────────────
    @Test
    fun 负例7_授权不许覆盖protected与硬拒() {
        val state = AuthzFixtures.state()
        // 受保护对象：快照里明明有这个对象，也不许沿用它
        val protectedHit = TaskGrantGate.evaluate(
            AuthzFixtures.request(boundary = BoundaryFacts(protectedHit = true)),
            state,
        )
        assertFalse(protectedHit.allow)
        assertEquals(GateOutcome.ASK_SINGLE, protectedHit.outcome)
        assertEquals(TaskGrantGate.CODE_PROTECTED, protectedHit.code)

        // 硬拒：连问都不问
        val deny = TaskGrantGate.evaluate(
            AuthzFixtures.request(boundary = BoundaryFacts(hardDeny = true)),
            state,
        )
        assertFalse(deny.allow)
        assertEquals(GateOutcome.REJECT, deny.outcome)
        assertEquals(TaskGrantGate.CODE_HARD_DENY, deny.code)

        // R3：授权最高只到 R2
        val r3 = TaskGrantGate.evaluate(AuthzFixtures.request(risk = RiskClass.R3), state)
        assertFalse(r3.allow)
        assertEquals(TaskGrantGate.CODE_RISK_TOO_HIGH, r3.code)

        // 219 的判定翻成第 4 步的事实（唯一翻译口）
        val raw = RiskVerdict(
            risk = RiskClass.R2,
            decision = RiskDecision.REJECT,
            reasons = listOf(RiskReason(RiskCode.PATH_ORIGINAL_RAW, "原件区")),
        )
        assertEquals(BoundaryFacts(protectedHit = true, hardDeny = true), boundaryFactsOf(raw))
        // 硬拒不等于「必问」：拒就是拒，问了也不执行
        assertFalse(mustAskIndependentlyOf(raw))
        assertTrue(
            mustAskIndependentlyOf(
                RiskVerdict(risk = RiskClass.R2, decision = RiskDecision.MUST_ASK),
            ),
        )
        assertTrue(
            "R3 永远逐次人批",
            mustAskIndependentlyOf(RiskVerdict(risk = RiskClass.R3, decision = RiskDecision.PROCEED)),
        )
    }

    // ── 负例⑧ 一个动作两条账 ─────────────────────────────────────────────────
    @Test
    fun 负例8_一个动作只许一条账() {
        val root = tempRoot()
        try {
            val request = AuthzFixtures.request()
            assertEquals(DecisionAppend.APPENDED, TaskGrantLedger.recordApplied(root, request, 1_000L))
            assertEquals("重跑不许再落一条", DecisionAppend.DUPLICATE, TaskGrantLedger.recordApplied(root, request, 1_001L))
            val rows = DecisionLedger.fileIn(root).readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            assertEquals("账本里应正好一行", 1, rows.size)
            assertTrue(rows.single().contains("\"evidence\":\"op-1:applied\""))
            assertEquals(listOf("op-1"), TaskGrantLedger.appliedOps(root))
        } finally {
            root.deleteRecursively()
        }
        // 状态层也拦一次（同一 opId 的第二回 commit 不算数）
        val state = AuthzFixtures.state()
        assertTrue(state.commit("op-1", "a", 10L, ObjectState.Absent))
        assertFalse(state.commit("op-1", "a", 10L, ObjectState.Absent))
        assertEquals(1, state.actionsUsed)
    }

    /** 本批 authz 包的全部生产源（负例①的文案扫描范围）。 */
    private fun authzSources(): List<File> {
        val dir = File("src/main/java/com/example/zhengdao/baopu/authz")
        require(dir.isDirectory) { "authz 目录没找到：${dir.absolutePath}" }
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }!!.sortedBy { it.name }
    }
}
