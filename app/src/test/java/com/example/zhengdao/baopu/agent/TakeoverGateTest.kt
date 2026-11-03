// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-02-第三百六十六批-分片与接管 §三 判据 ①②③（④是 detekt 预算闸，跑在门禁脚本里）。
// 房型照 `ProcessObjectsTest`（374）：判定层零 Android、零 IO、零时钟，每一条负例都能在 JVM 里红。
// 三条判据各打什么：
//   判据① 三类检测各有测试——无提交无日志超阈⇒STALLED、自报余量不足⇒EXHAUSTED、片缺却报完成⇒INCOMPLETE，
//         且「且」字口径（只哑一样不判）、挂起与 374 同进度两条触发、谎报>耗尽>停滞的判序各有例；
//   判据② 接管五步有测试——五步顺序逐字钉死＋**冻结不动分支：前后 sha 不变**（假分支带写口，
//         五步跑完写口没被碰过、sha 一次没变、句柄被读两次＝真的一前一后）；
//   判据③ 无痕接管有独立账行——三样全缺 ⇒ 账行之外多一行「无痕接管」，两行不得混用；
//   判据④ detekt 预算闸不在这里（门禁脚本）。
// 扫源码前先证明尺子自己会咬人（合成违规行必须被抓出来），免得「全绿」只是没扫到。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoverGateTest {

    // ── 造数 ────────────────────────────────────────────────────────────────

    private val t0 = 1_730_000_000_000L
    private val small = 2 * 60 * 60 * 1000L // 小单 2h（派工单 §一① 的档）
    private val large = 6 * 60 * 60 * 1000L // 大单 6h

    private fun trace(
        index: Int,
        commit: Boolean = true,
        report: Boolean = true,
        readings: Boolean = true,
    ): ShardTrace = ShardTrace(index, commit, report, readings)

    private fun trail(
        lastCommitAtMs: Long = t0,
        lastLogAtMs: Long = t0,
        suspensions: Int = 0,
    ): ActivityTrail = ActivityTrail(lastCommitAtMs, lastLogAtMs, suspensions)

    private fun note(done: Int, total: Int = 3, orderId: String = "366") =
        com.example.zhengdao.baopu.plan.ProgressNote(orderId, done, total, etaMinutes = 20, blockers = emptyList())

    private fun scene(
        note: com.example.zhengdao.baopu.plan.ProgressNote = note(done = 1),
        threshold: Long = small,
        activity: ActivityTrail = trail(),
        exhausted: Boolean = false,
        previous: com.example.zhengdao.baopu.plan.ProgressNote? = null,
        traces: List<ShardTrace> = listOf(trace(1)),
        orderId: String = "366",
    ): SceneSnapshot = SceneSnapshot(orderId, threshold, activity, exhausted, previous, note, traces)

    private fun tscene(
        verdict: SignalVerdict,
        traces: List<ShardTrace> = listOf(trace(1), trace(2)),
        total: Int = 3,
        origin: String = "qoder",
        successor: String = "dsh",
        orderId: String = "366",
    ): TakeoverScene = TakeoverScene(orderId, verdict, origin, successor, total, traces)

    /** 内存假分支：带着一个**写口**（模拟 force-push），门禁五步要是碰了它就现形。 */
    private class FakeBranch(initialSha: String) {
        var sha: String = initialSha
        var forcePushed: Boolean = false
        var reads: Int = 0

        fun forcePush(newSha: String) {
            forcePushed = true
            sha = newSha
        }

        val frozenView: FrozenBranch = FrozenBranch {
            reads++
            sha
        }
    }

    private fun takeover(scene: TakeoverScene, branch: FakeBranch): TakeoverRecord = TakeoverGate.run(scene, branch.frozenView)

    /** 与 ProcessObjectsTest 同法：剥掉注释再扫（注释里提 force-push 是引据，不是写口）。 */
    private fun strippedCode(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")

    private fun gateCode(): String = strippedCode(File(SRC_UNDER_GATE).readText(Charsets.UTF_8))

    // ── 判据①：STALLED ──────────────────────────────────────────────────────

    @Test
    fun `判据1_无新提交且无日志超阈_STALLED`() {
        val over = TakeoverGate.classify(scene(activity = trail(t0, t0)), nowMs = t0 + small + 1)
        assertEquals(TakeoverSignal.STALLED, over!!.signal)
        assertTrue("触发原因要点名超阈：${over.trigger}", over.trigger.contains("超阈"))
        assertTrue("小单阈是 120 分钟：${over.trigger}", over.trigger.contains("120 分钟"))
    }

    @Test
    fun `判据1_未超阈不判_差一毫秒也算在场`() {
        assertNull(TakeoverGate.classify(scene(activity = trail(t0, t0)), nowMs = t0 + small))
    }

    @Test
    fun `判据1_大单用大单的阈_2小时不判`() {
        assertNull(
            "大单 6h 阈：哑了 2h 还不该接管",
            TakeoverGate.classify(scene(threshold = large, activity = trail(t0, t0)), nowMs = t0 + small + 1),
        )
    }

    @Test
    fun `判据1_只哑一样不判_提交新鲜日志陈旧仍有人在场`() {
        val now = t0 + small + 60_000L
        val verdict = TakeoverGate.classify(
            scene(activity = trail(lastCommitAtMs = now - 60_000L, lastLogAtMs = t0)),
            nowMs = now,
        )
        assertNull("§一① 写的是「无新提交**且**无日志」——两样都哑才算：$verdict", verdict)
    }

    @Test
    fun `判据1_只哑另一样也不判_日志新鲜提交陈旧仍有人在场`() {
        val now = t0 + small + 60_000L
        val verdict = TakeoverGate.classify(
            scene(activity = trail(lastCommitAtMs = t0, lastLogAtMs = now - 60_000L)),
            nowMs = now,
        )
        assertNull("提交陈旧但日志新鲜＝人还在场写日志：$verdict", verdict)
    }

    @Test
    fun `判据1_连续2次挂起无进展_STALLED`() {
        val verdict = TakeoverGate.classify(scene(activity = trail(suspensions = 2)), nowMs = t0)
        assertEquals(TakeoverSignal.STALLED, verdict!!.signal)
        assertTrue("触发原因要点名挂起：${verdict.trigger}", verdict.trigger.contains("挂起"))
    }

    @Test
    fun `判据1_挂起不足2次不判`() {
        assertNull(TakeoverGate.classify(scene(activity = trail(suspensions = 1)), nowMs = t0))
    }

    @Test
    fun `判据1_连续两次读数同进度_STALLED_374的消费口`() {
        val verdict = TakeoverGate.classify(
            scene(note = note(done = 2), previous = note(done = 2), traces = listOf(trace(1), trace(2))),
            nowMs = t0,
        )
        assertEquals(TakeoverSignal.STALLED, verdict!!.signal)
        assertTrue("触发原因要点名同进度：${verdict.trigger}", verdict.trigger.contains("同进度"))
    }

    @Test
    fun `判据1_片数在动不判停滞`() {
        assertNull(
            TakeoverGate.classify(
                scene(note = note(done = 2), previous = note(done = 1), traces = listOf(trace(1), trace(2))),
                nowMs = t0,
            ),
        )
        assertNull("首份读数没有上一份可比", TakeoverGate.classify(scene(previous = null), nowMs = t0))
    }

    // ── 判据①：EXHAUSTED ────────────────────────────────────────────────────

    @Test
    fun `判据1_自报余量不足_EXHAUSTED`() {
        val verdict = TakeoverGate.classify(scene(exhausted = true, traces = listOf(trace(1), trace(2))), nowMs = t0)
        assertEquals(TakeoverSignal.EXHAUSTED, verdict!!.signal)
        assertTrue("触发原因要点名余量不足：${verdict.trigger}", verdict.trigger.contains("余量不足"))
        assertTrue("额度耗尽是资源事实不是错误（§二 不追责）：${verdict.trigger}", verdict.trigger.contains("不是错误"))
    }

    // ── 判据①：INCOMPLETE ───────────────────────────────────────────────────

    @Test
    fun `判据1_报完成但片不齐_INCOMPLETE`() {
        // 自报 3/3，影子核验只认 2 片（第 3 片只有提交没有读数——三样不齐不算完成）。
        val verdict = TakeoverGate.classify(
            scene(note = note(done = 3), traces = listOf(trace(1), trace(2), trace(3, readings = false))),
            nowMs = t0,
        )
        assertEquals(TakeoverSignal.INCOMPLETE, verdict!!.signal)
        assertTrue("触发原因要点名谎报：${verdict.trigger}", verdict.trigger.contains("谎报"))
    }

    @Test
    fun `判据1_中途虚报也算谎报_自报片数对不上影子`() {
        val verdict = TakeoverGate.classify(
            scene(note = note(done = 2, total = 5), traces = listOf(trace(1))),
            nowMs = t0,
        )
        assertEquals(TakeoverSignal.INCOMPLETE, verdict!!.signal)
    }

    @Test
    fun `判据1_自报片数影子认账_不算谎报`() {
        assertNull(
            TakeoverGate.classify(scene(note = note(done = 2), traces = listOf(trace(1), trace(2))), nowMs = t0),
        )
    }

    // ── 判据①：判序与闭集 ───────────────────────────────────────────────────

    @Test
    fun `判据1_谎报优先于耗尽_自报矛盾不能洗成额度问题`() {
        val verdict = TakeoverGate.classify(
            scene(
                note = note(done = 3),
                exhausted = true,
                traces = listOf(trace(1), trace(2)),
            ),
            nowMs = t0,
        )
        assertEquals("谎报改变性质（§二：额度耗尽不是错误，谎报才是）：$verdict", TakeoverSignal.INCOMPLETE, verdict!!.signal)
    }

    @Test
    fun `判据1_耗尽优先于停滞_亲口说的比外部观察硬`() {
        val verdict = TakeoverGate.classify(
            scene(exhausted = true, activity = trail(t0, t0)),
            nowMs = t0 + small + 1,
        )
        assertEquals(TakeoverSignal.EXHAUSTED, verdict!!.signal)
    }

    @Test
    fun `域_三信号闭集_没有第四类`() {
        assertEquals(listOf("STALLED", "EXHAUSTED", "INCOMPLETE"), TakeoverSignal.entries.map { it.name })
    }

    @Test
    fun `判据1_三类都不中_正常在干`() {
        assertNull(TakeoverGate.classify(scene(note = note(done = 2), traces = listOf(trace(1), trace(2))), nowMs = t0))
    }

    // ── 判据②：接管五步 ─────────────────────────────────────────────────────

    @Test
    fun `判据2_五步顺序逐字固定`() {
        assertEquals(
            listOf("①冻结现场", "②读留痕", "③影子先验", "④接剩余片", "⑤记账"),
            TakeoverGate.STEPS,
        )
        assertEquals("回执里的 steps 就是这五步", TakeoverGate.STEPS, recordOf().steps)
    }

    @Test
    fun `判据2_冻结不动分支_前后sha不变且写口没被碰`() {
        val branch = FakeBranch("abc123ef")
        val record = takeover(tscene(stalled()), branch)
        assertTrue("冻结凭证：前后 sha 不变", record.freeze.unchanged)
        assertEquals("sha 一次没动", "abc123ef", branch.sha)
        assertFalse("写口（force-push）没被碰过", branch.forcePushed)
        assertTrue("句柄要被读两次（入口＋出口各一次）＝真的一前一后", branch.reads >= 2)
        assertEquals("前读＝后读＝分支头", branch.sha, record.freeze.shaBefore)
        assertEquals(branch.sha, record.freeze.shaAfter)
    }

    @Test
    fun `判据2_账行五样齐全`() {
        val line = recordOf().ledgerLine
        assertTrue("原承接方：$line", line.contains("原承接方 qoder"))
        assertTrue("接管方：$line", line.contains("接管方 dsh"))
        assertTrue("优先换会话：$line", line.contains("优先换会话"))
        assertTrue("从哪片接：$line", line.contains("从第 3 片接"))
        assertTrue("已验片数：$line", line.contains("已验 2/3 片"))
        assertTrue("损失估算：$line", line.contains("损失 1 片"))
        assertTrue("原因原样进账行：$line", line.contains("原因："))
    }

    @Test
    fun `判据2_剩余片与接续点`() {
        val record = recordOf()
        assertEquals(listOf(1, 2), record.verifiedShards)
        assertEquals(listOf(3), record.remainingShards)
        assertEquals(3, record.resumeFromShard)
    }

    @Test
    fun `判据2_接管不追责_EXHAUSTED账行不带谎报字样`() {
        val branch = FakeBranch("s1")
        val record = takeover(
            tscene(SignalVerdict(TakeoverSignal.EXHAUSTED, "承接方自报余量不足——额度耗尽是资源事实，不是错误")),
            branch,
        )
        assertFalse("不追责：$record", record.ledgerLine.contains("谎报"))
    }

    @Test
    fun `判据2_谎报的账行补按谎报处理`() {
        val branch = FakeBranch("s1")
        val record = takeover(tscene(SignalVerdict(TakeoverSignal.INCOMPLETE, "自报完成 3 片，影子核验只认 2 片")), branch)
        assertTrue(record.ledgerLine.contains("谎报（按谎报处理）"))
    }

    // ── 判据②：接管前拒因 ───────────────────────────────────────────────────

    @Test
    fun `判据2_人名齐全有剩余片_放行`() {
        assertNull(TakeoverGate.takeoverRejection(tscene(stalled())))
    }

    @Test
    fun `判据2_负例_原承接方或接管方或单号缺失_拒`() {
        assertNotNull(TakeoverGate.takeoverRejection(tscene(stalled(), origin = " ")))
        assertNotNull(TakeoverGate.takeoverRejection(tscene(stalled(), successor = "")))
        assertNotNull(TakeoverGate.takeoverRejection(tscene(stalled(), orderId = "  ")))
    }

    @Test
    fun `判据2_负例_无片与全验完_拒`() {
        assertNotNull(TakeoverGate.takeoverRejection(tscene(stalled(), total = 0)))
        val all = listOf(trace(1), trace(2), trace(3))
        val reason = TakeoverGate.takeoverRejection(tscene(stalled(), traces = all, total = 3))
        assertNotNull("全验完的单没有剩余片", reason)
        assertTrue("拒因要点名无需接管：$reason", reason!!.contains("无需接管"))
    }

    // ── 判据③：无痕接管单独记 ───────────────────────────────────────────────

    @Test
    fun `判据3_三样全缺_无痕接管独立账行`() {
        val branch = FakeBranch("s1")
        val record = takeover(
            tscene(stalled(), traces = listOf(trace(1, readings = false), trace(2, report = false))),
            branch,
        )
        assertNotNull("零留痕必须单独记一笔", record.tracelessLine)
        assertTrue("要点名无痕接管：${record.tracelessLine}", record.tracelessLine!!.contains("无痕接管"))
        assertTrue("要点名这是流程问题不是个人问题：${record.tracelessLine}", record.tracelessLine!!.contains("流程问题"))
        assertTrue("无痕只能从第 1 片重做", record.resumeFromShard == 1)
        assertTrue("无痕接管的账行已验 0 片", record.ledgerLine.contains("已验 0/3 片"))
        assertFalse("独立账行不得与接管账行混用", record.tracelessLine == record.ledgerLine)
    }

    @Test
    fun `判据3_有留痕_不出无痕行`() {
        assertNull(recordOf().tracelessLine)
    }

    // ── 源级：类型约束与租约自守 ─────────────────────────────────────────────

    @Test
    fun `源级_冻结句柄只有读一口_push与reset无处安放`() {
        val body = File(SRC_UNDER_GATE).readText(Charsets.UTF_8)
            .substringAfter("fun interface FrozenBranch {")
            .substringBefore("}")
        val fns = Regex("""fun (\w+)""").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals("句柄上只许有 headSha——多一个写口，§二 的「不许 force-push/reset」就成了空话", listOf("headSha"), fns)
    }

    @Test
    fun `源级_判定层零IO零进程`() {
        val code = gateCode()
        listOf("ProcessBuilder", "Runtime.getRuntime", "java.io.File", "java.nio").forEach { banned ->
            assertFalse("判定层不许碰 IO／进程（$banned）：那是接线层的事", code.contains(banned))
        }
    }

    @Test
    fun `源级_不接线不顺手重构_不碰执行面`() {
        val code = gateCode()
        listOf("RunEngine", "GuardPipeline", "MainActivity", "PlanController").forEach { name ->
            assertFalse("366 只判状态不动手（§一①），代码里不该出现 $name", code.contains(name))
        }
    }

    @Test
    fun `源级_尺子先量自己_合成违规必须被抓`() {
        val sneaky = strippedCode("fun interface FrozenBranch {\n /* fun forcePush(x: String) */ \n fun headSha(): String\n}")
        val fns = Regex("""fun (\w+)""").findAll(sneaky.substringAfter("{").substringBefore("}"))
            .map { it.groupValues[1] }.toList()
        assertEquals("剥注释后不许留下注释里的写口", listOf("headSha"), fns)
    }

    // ── 造数辅助 ─────────────────────────────────────────────────────────────

    /** 典型停滞结论：超阈（造数用，接管的输入不关心是哪条触发）。 */
    private fun stalled(): SignalVerdict = SignalVerdict(TakeoverSignal.STALLED, "无新提交且无日志超阈（阈 120 分钟）")

    /** 标准接管：3 片里影子认 2 片，从第 3 片接。 */
    private fun recordOf(): TakeoverRecord = takeover(tscene(stalled()), FakeBranch("head-1"))

    private companion object {
        const val SRC_UNDER_GATE = "src/main/java/com/example/zhengdao/baopu/agent/TakeoverGate.kt"
    }
}
