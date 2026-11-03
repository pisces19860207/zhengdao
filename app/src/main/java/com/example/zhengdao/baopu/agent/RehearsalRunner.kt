// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百五十八批 · 推演盘（军师）的**编排层**：启动闸 → 串行道 → 建副本 → 逐步跑 → 出报告。
//
// ## 为什么跑两趟（先全部归置、再全部复刻）
// 影子的形状是「一次动作预演一次」，所以它可以逐步 diff；推演的形状是**一份计划整趟预演**，
// 「会改 N 个文件」要的是整趟的影响面。若逐步 diff，第 2 步看到的第 1 步原件会算成新增。
// 所以这里把复制（`stage`）与执行（`replay`）分开：
// ① 预置趟把每个写侧声明对象归置进副本（顺带算出配额与>8MB 的分界）；
// ② 取一次副本树快照；
// ③ 主趟按步序在副本里真跑；
// ④ 再取一次快照，diff 出的就是「这趟计划会改哪些对象」——摘要比对，等长覆盖也算改了。
//
// ## 三件只在这里发生的事
// · **只读步真跑真盘**（派单【做什么】"只读操作真跑"）：`read_file`/`list_dir` 直连工作区，不复制；
// · **写侧步只在副本里跑**：executor 的 `root` 一律给 `copyRoot`，路径给相对写法——
//   两个条件缺一个就等于"推演写了真文件"（判据①）；
// · **风险档取生产表**：`RiskMatrix.classify` ＋ 实测的 `existingPaths`，不另立第二张表。
//
// ## 一条不能复用 RiskGate 的理由
// [com.example.zhengdao.baopu.RiskGate.judge] 会把这次调用**记进"最近调用"窗口**（防超频用的）。
// 推演若走它，预演本身就把同一动作数了第二次 ⇒ 真跑那一趟的频度档被推演抬高。
// 所以这里只调矩阵本体 `RiskMatrix.classify`，判定照样来自生产表，但不往生产的时间窗里写。
//
// ## 军师不写任何账
// 派单【隔离】表里军师那一行的「账本」格子写的是**不写任何账**（报告只进报告区）：
// 本文件没有 `DecisionLedger`／`*Jsonl` 任何一口的调用，报告交回调用方展示。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskCtx
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.inspect.NOT_APPLICABLE_TOOLS
import com.example.zhengdao.baopu.inspect.SHADOW_TOOLS
import java.io.File

// 七个工具对象（`ReadFileTool` 等）在 `agent/tools/` 目录、`com.example.zhengdao.baopu.agent` 包里，
// 与本文件同包 ⇒ 不 import（`agent/ShadowSandbox.kt` 调用它们也是这个写法）。

/**
 * 推演的跑法（无 Android 依赖：现场读数由调用方取，本层只吃 [EnvReading]/availableBytes）。
 *
 * 这样切是为了派单 §四判据⑥——"报告数字全部来自实测"这句话只有在判据层不碰 Context
 * 时才能走 JVM 直测；探针（电量／热状态／可用空间）在 `RehearsalWorker` 那一层。
 */
internal object RehearsalRunner {

    /** 只读侧覆盖到的工具（照原样真跑真盘）。 */
    private val READ_TOOLS = listOf("read_file", "list_dir")

    /** 明细里每一步的说明留这么多字（与 `FileShadowSandbox` 的回执口径一致，不塞整段栈）。 */
    private const val NOTE_CHARS = 120

    /**
     * 跑一趟推演，交回一份报告。**任何出口都走到清理**（判据②⑤）。
     *
     * @param availableBytes 副本可落的那个卷的可用字节（装配层用 `StatFs` 取；取不到交 0 ⇒ 直接降级）
     * @param stop 取消检查（主趟逐步之间问一次；派单§二④"用户随时能取消，取消后立刻清副本"）
     */
    fun run(
        plan: RehearsalPlan,
        cacheRoot: File,
        env: EnvReading,
        availableBytes: Long,
        stop: () -> Boolean = { false },
    ): RehearsalReport {
        val started = System.nanoTime()
        val block = RehearsalRules.blockOf(env)
        if (block != StartBlock.OK) {
            return notStarted(plan, block, RehearsalRules.blockNote(env, block), started)
        }
        // 串行道（派单【隔离】④）：同任务的影子在跑 ⇒ 这一次不叠上去。
        if (!RehearsalLane.tryBegin(plan.taskKey, RehearsalLaneOwner.REHEARSAL)) {
            return notStarted(plan, StartBlock.BUSY, RehearsalLane.waitNote(plan.taskKey), started)
        }
        try {
            return rehearse(plan, cacheRoot, availableBytes, stop, started)
        } finally {
            RehearsalLane.end(plan.taskKey, RehearsalLaneOwner.REHEARSAL)
        }
    }

    // ── 一趟的骨架 ──────────────────────────────────────────────────────────

    private fun rehearse(
        plan: RehearsalPlan,
        cacheRoot: File,
        availableBytes: Long,
        stop: () -> Boolean,
        started: Long,
    ): RehearsalReport {
        val quota = RehearsalRules.quotaBytes(availableBytes)
        val sandbox = RehearsalSandbox(cacheRoot, quota)
        val walk = Walk(sandbox, plan.workspaceRoot)
        // 真盘改前的格子（mtime＋size）：判据①要的正是这两格，跑完再取一次必须逐条相等。
        val declared = plan.steps.flatMap { it.targets }
            .map { it.trim().replace('\\', '/') }.filter { it.isNotEmpty() }
        val realBefore = realNodesOf(plan.workspaceRoot, declared)
        val why = if (quota <= 0L) {
            "可用空间 $availableBytes B ⇒ 配额 ${quota} B，建副本没有落点"
        } else {
            sandbox.open(plan.runId)
        }
        var clean = RehearsalSandbox.Cleanup(0L, 0, gone = false)
        try {
            if (why == null) {
                val pre = stageAll(plan, walk)
                if (pre != null) degrade(walk, plan, pre) else playAll(plan, walk, stop)
            } else {
                degrade(walk, plan, why)
            }
            // 影响面必须在**删副本之前**称：`cleanup()` 之后树是空的，diff 会把"复制进来的"
            // 全说成"消失了"，「会改 N 个文件」当场变成少报（判据⑥）。
            walk.impact = impactOf(plan, walk)
        } finally {
            // 派单 §四③要的是「结束／失败两种路径都被清」：抛出去那条不走 return，
            // 清理不放进 finally 就把副本留在机上等下一次 sweepStale 收尸。
            clean = sandbox.cleanup()
        }
        return finish(plan, walk, clean, realUntouchedOf(plan.workspaceRoot, realBefore), started)
    }

    /** 一趟的可变态（只有这几格会变，其余从计划里读）。 */
    private class Walk(val sandbox: RehearsalSandbox, val workspace: File) {
        val done = mutableListOf<StepResult>()
        var mode = RehearsalMode.FILE
        var degradeNote = ""
        var cancelled = false
        var peakBytes = 0L

        /** 副本树预置完成时的快照，主趟跑完再 diff 一次 ⇒ 实测影响面。 */
        var baseline: Map<String, RehearsalSandbox.Node> = emptyMap()

        /** 影响面（在删副本**之前**称好的，见 [impactOf]）。 */
        var impact: List<String> = emptyList()

        /** 超阈值只做元数据的对象（归一写法 → 读数），主趟据此**不复刻**这些步。 */
        val metaOnly = LinkedHashMap<String, MetaReading>()
    }

    // ── 预置趟：把写侧声明对象归置进副本 ────────────────────────────────────

    /**
     * 逐步归置。**配额满**是第一件要说的事：整趟降级为纯计划层（派单判据④），
     * 已复制的那半棵照样由 [rehearse] 末尾的 `cleanup()` 清掉，不留半截副本当现场。
     *
     * @return null＝全部归置完（可以开主趟）；非 null＝降级的原因
     */
    private fun stageAll(plan: RehearsalPlan, walk: Walk): String? {
        plan.steps.forEach { step ->
            if (step.call.name !in SHADOW_TOOLS) return@forEach
            step.targets.forEach { rel ->
                val one = walk.sandbox.stage(plan.workspaceRoot, rel)
                if (one.stage == RehearsalSandbox.Stage.QUOTA_FULL) return one.note
                if (one.stage == RehearsalSandbox.Stage.METADATA) {
                    one.meta?.let { walk.metaOnly[RehearsalRules.relKey(it.path)] = it }
                }
            }
        }
        walk.baseline = walk.sandbox.snapshot()
        walk.peakBytes = walk.sandbox.treeBytes()
        return null
    }

    // ── 主趟：按步序跑 ──────────────────────────────────────────────────────

    private fun playAll(plan: RehearsalPlan, walk: Walk, stop: () -> Boolean) {
        var i = 0
        while (i < plan.steps.size) {
            if (stop()) {
                walk.cancelled = true
                markRest(plan, walk, i, "已取消：中途收到取消，这一步与之后的都没跑")
                return
            }
            walk.done += walkStep(plan.steps[i], walk)
            walk.peakBytes = maxOf(walk.peakBytes, walk.sandbox.treeBytes())
            i++
        }
    }

    /** 一步怎么跑：只读直连真盘，写侧复刻进副本，其余按"不在覆盖"如实标注。 */
    private fun walkStep(step: RehearsalStep, walk: Walk): StepResult {
        val started = System.nanoTime()
        val name = step.call.name
        val risk = riskOf(step, walk)
        val refusal = step.targets.firstNotNullOfOrNull { walk.sandbox.refuse(it) }
        if (refusal != null) {
            return result(step, risk, StepKind.UNSUPPORTED, false, refusal, started)
        }
        return when {
            name in READ_TOOLS -> runRead(step, walk, risk, started)
            name in SHADOW_TOOLS -> replay(step, walk, risk, started)
            name in NOT_APPLICABLE_TOOLS -> result(
                step, risk, StepKind.UNSUPPORTED, true,
                "$name 无文件副作用 ⇒ 推演不预演它（派单【明确不做】）", started,
            )
            else -> result(
                step, risk, StepKind.UNSUPPORTED, false,
                "工具「$name」不在推演覆盖（只读跑 ${READ_TOOLS.joinToString("／")}，" +
                    "写侧跑 ${SHADOW_TOOLS.joinToString("／")}）", started,
            )
        }
    }

    /** 只读步：真盘真跑（读不占配额，也不需要副本）。 */
    private fun runRead(step: RehearsalStep, walk: Walk, risk: RiskClass?, started: Long): StepResult {
        val workspace = walk.workspace
        val rel = step.targets.firstOrNull()?.trim()?.trim('/') ?: ""
        val args = step.call.arguments
        val outcome = if (step.call.name == "read_file") {
            ReadFileTool.read(
                File(workspace, rel),
                args.optInt(ReadFileTool.ARG_OFFSET, ReadFileTool.DEFAULT_OFFSET),
                args.optInt(ReadFileTool.ARG_LIMIT, ReadFileTool.DEFAULT_LIMIT),
            )
        } else {
            ListDirTool.list(
                if (rel.isEmpty()) workspace else File(workspace, rel),
                args.optInt(ListDirTool.ARG_DEPTH, ListDirTool.DEFAULT_DEPTH),
                args.optInt(ListDirTool.ARG_LIMIT, ListDirTool.DEFAULT_LIMIT),
            )
        }
        val text = textOf(outcome)
        return result(step, risk, StepKind.READ_REAL, outcome is ToolOutcome.Ok, text, started)
    }

    /**
     * 写侧步：在**副本根**里复刻这次动作。
     *
     * 超阈值（预置趟判成 METADATA）的那一步**不复刻**——副本里根本没有原件，
     * 复刻只会在副本里凭空造出一个几十字节的小文件，把「会改」的数字污染成假。
     */
    private fun replay(step: RehearsalStep, walk: Walk, risk: RiskClass?, started: Long): StepResult {
        val root = walk.sandbox.copyRoot()
            ?: return result(step, risk, StepKind.NOT_RUN, false, "副本根没开", started)
        val metaHit = step.targets.map { RehearsalRules.relKey(it) }.firstOrNull { walk.metaOnly.containsKey(it) }
        if (metaHit != null) {
            val meta = walk.metaOnly[metaHit]
            return result(
                step, risk, StepKind.METADATA, meta?.writable == true,
                meta?.brief() ?: "$metaHit 超阈值：只做元数据预演，未复刻", started,
            )
        }
        val outcome = replayCall(step.call, root, step.targets)
        return result(step, risk, StepKind.WRITE_COPY, outcome is ToolOutcome.Ok, textOf(outcome), started)
    }

    /** 用**相对路径**把动作喂回真工具，root 换成副本根（与 `FileShadowSandbox.runTrial` 同一条红线）。 */
    private fun replayCall(call: ToolCall, root: File, targets: List<String>): ToolOutcome {
        val args = call.arguments
        val first = targets.getOrElse(0) { "" }
        return when (call.name) {
            "write_file" -> WriteFileTool.write(
                root, first, args.optString(WriteFileTool.ARG_CONTENT),
                args.optBoolean(WriteFileTool.ARG_OVERWRITE, false),
            )
            "edit_file" -> EditFileTool.edit(
                root, first, args.optString(EditFileTool.ARG_OP),
                if (args.has(EditFileTool.ARG_FROM_LINE)) args.optInt(EditFileTool.ARG_FROM_LINE) else null,
                if (args.has(EditFileTool.ARG_TO_LINE)) args.optInt(EditFileTool.ARG_TO_LINE) else null,
                args.optString(EditFileTool.ARG_CONTENT),
            )
            "move_path" -> MovePathTool.move(
                root, first, targets.getOrElse(1) { args.optString(MovePathTool.ARG_TO) },
            )
            "delete_path" -> DeletePathTool.run(
                path = first, permanent = args.optBoolean(DeletePathTool.ARG_PERMANENT, false), root = root,
            )
            "convert_encoding" -> ConvertEncodingTool.convert(root, File(root, first))
            else -> ToolOutcome.Failed("工具「${call.name}」不在推演覆盖里")
        }
    }

    /** 风险档：生产矩阵 ＋ 实测的"目标在不在盘上"，不喂推演自己的估计。 */
    private fun riskOf(step: RehearsalStep, walk: Walk): RiskClass? {
        val workspace = walk.workspace
        val existing = step.targets.map { File(workspace, it.trim().replace('\\', '/')) }
            .filter { it.exists() }
            .map { it.absolutePath }
            .toSet()
        return RiskMatrix.classify(
            step.call,
            RiskCtx(workspaceRoots = listOf(workspace.absolutePath), existingPaths = existing),
        ).risk
    }

    // ── 收口 ────────────────────────────────────────────────────────────────

    /** 降级为纯计划层：已经跑过的格子留着，剩下的如实标"没跑"，清单改口为"计划声明"。 */
    private fun degrade(walk: Walk, plan: RehearsalPlan, note: String) {
        walk.mode = RehearsalMode.PLAN_ONLY
        walk.degradeNote = note
        walk.metaOnly.clear()
        walk.baseline = emptyMap()
        val seen = walk.done.map { it.n }.toSet()
        plan.steps.filter { it.n !in seen }.forEach { walk.done += notRun(it, note) }
    }

    private fun markRest(plan: RehearsalPlan, walk: Walk, from: Int, note: String) {
        plan.steps.drop(from).forEach { walk.done += notRun(it, note) }
    }

    private fun notRun(step: RehearsalStep, note: String): StepResult = StepResult(
        step.n, step.call.name, StepKind.NOT_RUN, null, false, note,
        RunTraceRules.UNKNOWN_DURATION_MS, step.targets.toList(),
    )

    private fun result(
        step: RehearsalStep,
        risk: RiskClass?,
        kind: StepKind,
        ok: Boolean,
        note: String,
        started: Long,
    ): StepResult = StepResult(
        n = step.n,
        tool = step.call.name,
        kind = kind,
        risk = risk,
        ok = ok,
        note = note.take(NOTE_CHARS),
        ms = elapsedMs(started),
        paths = step.targets.toList(),
    )

    private fun finish(
        plan: RehearsalPlan,
        walk: Walk,
        clean: RehearsalSandbox.Cleanup,
        realUntouched: Boolean,
        started: Long,
    ): RehearsalReport = RehearsalReport(
        runId = plan.runId,
        mode = walk.mode,
        block = StartBlock.OK,
        blockNote = "",
        steps = walk.done.toList(),
        measuredPaths = walk.impact,
        cancelled = walk.cancelled,
        degradeNote = walk.degradeNote,
        realUntouched = realUntouched,
        copyBytesPeak = walk.peakBytes,
        cleanedBytes = clean.bytesFreed,
        elapsedMs = elapsedMs(started),
    )

    /**
     * 影响面（「会改 N 个文件」的那份清单）。
     *
     * 文件层那一趟＝**副本树跑前跑后的 diff**（按摘要比，等长覆盖也算改了）＋超阈值只做
     * 元数据的那几条（副本里没有它，但真实跑会改它 ⇒ 漏掉就是少报）；
     * 降级那一趟没有副本可 diff，只能退回计划声明，并在报告里把来路写在脸上（[RehearsalReport.lines]）。
     */
    private fun impactOf(plan: RehearsalPlan, walk: Walk): List<String> =
        if (walk.mode == RehearsalMode.FILE) {
            walk.sandbox.diffPaths(walk.baseline) + walk.metaOnly.keys
        } else {
            plan.steps.filter { it.call.name in SHADOW_TOOLS }.flatMap { it.targets }
        }

    /** 没启动的那一趟：一步都没跑，但耗时与"没建副本"仍是实测事实。 */
    private fun notStarted(plan: RehearsalPlan, block: StartBlock, note: String, started: Long): RehearsalReport =
        RehearsalReport(
            runId = plan.runId,
            mode = RehearsalMode.FILE,
            block = block,
            blockNote = note,
            steps = plan.steps.map { notRun(it, "推演未启动：${block.cn}") },
            measuredPaths = emptyList(),
            cancelled = false,
            degradeNote = "",
            // 没跑过任何一步，真盘自然一格没动；这一格在拦截分支里不进报告正文。
            realUntouched = true,
            copyBytesPeak = 0L,
            cleanedBytes = 0L,
            elapsedMs = elapsedMs(started),
        )

    // ── 真盘那一侧的读数（只取mtime/size，不写不改）─────────────────────────

    /** 真盘一个对象的读数（派单判据①点名的就这两格：mtime 与 size）。 */
    private data class RealNode(val bytes: Long, val mtime: Long)

    /**
     * 改前读数表：归一写法 → 「原样写法＋读数」。
     *
     * 值里带上原样写法，是因为跑完还要按**同一个写法**再取一次：`relKey` 把大小写折掉了，
     * 在区分大小写的盘上拿折过的写法回查会查成"不存在"，把没改过的报成改过（假警）。
     */
    private fun realNodesOf(workspace: File, rels: List<String>): Map<String, Pair<String, RealNode>> {
        val out = LinkedHashMap<String, Pair<String, RealNode>>()
        rels.forEach { rel ->
            out[RehearsalRules.relKey(rel)] = rel to probe(workspace, rel)
        }
        return out
    }

    /** 取一格读数；对象不在盘上时两格都用 -1（别让"不存在"冒充"零字节的原件"）。 */
    private fun probe(workspace: File, rel: String): RealNode {
        val f = File(workspace, rel)
        return if (f.isFile) RealNode(f.length(), f.lastModified()) else RealNode(-1L, -1L)
    }

    /** 跑完之后逐条再取一次：有一格变了就是**推演写了真文件**，报告直说"核对不过"。 */
    private fun realUntouchedOf(workspace: File, before: Map<String, Pair<String, RealNode>>): Boolean =
        before.all { (_, pair) -> probe(workspace, pair.first) == pair.second }

    private fun textOf(outcome: ToolOutcome): String = when (outcome) {
        is ToolOutcome.Ok -> outcome.text
        is ToolOutcome.Failed -> outcome.error
        is ToolOutcome.Rejected -> outcome.reason
    }

    /** 没测到就交 -1（照 [RunTraceRules.UNKNOWN_DURATION_MS] 的口径），不写 0 假装知道。 */
    private fun elapsedMs(startedNanos: Long): Long {
        val ms = (System.nanoTime() - startedNanos) / 1_000_000L
        return if (ms < 0L) RunTraceRules.UNKNOWN_DURATION_MS else ms
    }
}
