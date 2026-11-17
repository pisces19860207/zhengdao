// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十四批 · 「三连失败」真机/模拟器探针（**只进 debug 源集**）。
//
// 判据要的三件事都得在真设备上落地：
//   ① 开注入 ⇒ `run_log` 出现 3 条 `ok=0` 且 `err` 非空；
//   ② 归因判「行为类」⇒ 收工出 **1 张**约束卡（`提案候选.json` 里一条 TOOL_RULE）；
//   ③ 关注入 ⇒ 行为与现在完全一致（本探针**不负责**验这条，它是"没有配置文件时缝为空"
//      的结构事实；回归由既有 1118 条单测兜）。
//
// ## 为什么必须走真引擎
// 派工单原话：「必须走真实失败路径（写 run_log.err、进归因层），不是假日志」。
// 所以本探针**不自己写 run_log、不自己拼失败文本**：
//   · 一轮会话由 `engine.send(...)` 起（与用户敲字、定时任务同一个入口）；
//   · 失败由注入器在 `GuardPipeline` 第 ④ 段冒充（`ToolOutcome.Failed`）；
//   · `run_log` 那几行是引擎的 `persistToolExchange` 写的，`err` 是 `RunLoopRules.runLogErr` 写的；
//   · 约束卡是生产收尾路径（`BaopuChatController` 的 settle 钩子 → `ProposalRules`）出的。
// 探针只做两件事：**驱动**（发几轮消息）与**读数**（查库/查候选档）。
//
// ## 用法（脚本 `tools/fault-inject-3x.sh` 会自动做）
//   1. 先让聊天页活着（生产收尾钩子挂在它身上）：`am start` 主界面；
//   2. 写注入配置：`run-as <包> sh -c 'mkdir -p files/baopu && cat > files/baopu/fault-inject.txt'`；
//   3. 拉起本页：`am start -n <包>/com.example.zhengdao.debug.FaultInjectProbeActivity`；
//   4. 读数：logcat tag=`FaultInjectProbe`，结果文件 `files/fault-probe/result.txt`。
//
// 不挂 LAUNCHER（桌面只许一个图标）、不解析任何外部 intent 参数（只认两个可选的
// `--es tool` / `--ei rounds`，都由本机 adb 给）。
package com.example.zhengdao.debug

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.FailureOwner
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.baopu.ProposalCandidateStore
import com.example.zhengdao.baopu.ProposalKind
import com.example.zhengdao.baopu.ToolFailureAttribution
import com.example.zhengdao.baopu.agent.SendAcceptance
import java.io.File

/**
 * 三连失败探针：发 [rounds] 轮「读同一个文件」的请求，每轮至少撞一次注入的失败，
 * 最后把 `run_log` 的失败行、归因结论、约束卡读数一起打在屏上/logcat/结果文件里。
 */
class FaultInjectProbeActivity : Activity() {

    private val rows = mutableListOf<String>()
    private var view: TextView? = null
    private var outDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isDebugBuild()) {
            record("GATE", "debug 构建检查", "FAIL", "FLAG_DEBUGGABLE=false，拒绝运行")
            renderAndFinish(rows.joinToString("\n"), 0L)
            return
        }
        val tool = intent.getStringExtra("tool")?.takeIf { it.isNotBlank() } ?: DEFAULT_TOOL
        val rounds = intent.getIntExtra("rounds", DEFAULT_ROUNDS).coerceIn(1, MAX_ROUNDS)
        // 结果文件落**内部** filesDir：`adb shell run-as <包> cat files/fault-probe/result.txt`
        // 一条命令取得到（外部私有目录在 API 30+ 上 shell 读不了，脚本不该为取证要 root）。
        outDir = File(filesDir, PROBE_DIR).apply { mkdirs() }
        record("SET", "参数", "INFO", "tool=$tool rounds=$rounds 包=$packageName")
        render("故障注入·三连失败探针（684）\n跑动中…（每轮要等一次模型往返，约 1–2 分钟）")
        // 网络往返 + 工具梯子：放后台线程，别在主线程上等（ANR 会把证据一起带走）。
        Thread({ runScenario(tool, rounds) }, "zd-fault-probe").apply { isDaemon = true }.start()
    }

    // ── 场景 ────────────────────────────────────────────────────────────────

    private fun runScenario(tool: String, rounds: Int) {
        val started = System.currentTimeMillis()
        val report = runCatching { probe(tool, rounds) }
            .fold(
                onSuccess = { it },
                onFailure = {
                    record("ALL", "探针自身抛异常", "FAIL", "${it.javaClass.simpleName}: ${it.message}")
                    "FAIL 探针异常：${it.javaClass.simpleName} ${it.message}"
                },
            )
        record("SUM", "总结", if (report.startsWith("PASS")) "PASS" else "FAIL", report)
        writeResult(started, report)
        // 快照在**本线程**取好再回主线程渲染：`rows` 只有本线程写，主线程读要拿一份副本，
        // 不然就是跨线程读一个可变 List（真机上偶发 ConcurrentModificationException 的那种）。
        val snapshot = rows.joinToString("\n")
        Handler(mainLooper).post { renderAndFinish(snapshot, started) }
    }

    private fun probe(tool: String, rounds: Int): String {
        val rules = FaultInjection.describe(this)
        if (rules.isEmpty()) {
            return "FAIL 注入没开：${FaultInjection.configFile(this).absolutePath} 不存在或是空的" +
                "（脚本 tools/fault-inject-3x.sh 会写它）"
        }
        rules.forEachIndexed { i, line -> record("SET", "规则 ${i + 1}", "INFO", line) }

        val engine = runCatching { BaopuRuntime.engine(this) }.getOrElse {
            return "FAIL 引擎起不来：${it.javaClass.simpleName}: ${it.message}"
        }
        val db = BaopuRuntime.db(this)
        // **必须用聊天页当前那个会话**：约束卡是生产收尾钩子按 `BaopuChatController.sessionId`
        // 读 `run_log` 生成的（`generateProposalCandidatesOnSettle`），探针另开新会话时它读的
        // 仍是原来那个 ⇒ 卡片永远出不来。聊天页开机会取 `latestSessionId`，所以脚本先
        // force-stop 再起主界面，这里读同一个口径就能对上。
        val latest = BaopuRuntime.latestSessionId(db)
        val sid = latest ?: engine.createSession("故障注入·三连失败", System.currentTimeMillis())
        if (sid <= 0L) return "FAIL 建会话失败"
        record(
            "SET",
            "会话",
            if (latest != null) "INFO" else "WARN",
            "sessionId=$sid（${if (latest != null) "沿用聊天页当前会话" else "没有历史会话，新建的——约束卡这一格会读不到" }）",
        )

        // 目标文件放**工作区的 临时/**：模型看到的是它熟悉的那棵树里的真文件，不会因为
        // `/data/user/0/...` 这种路径"看着可疑"而拒绝调用工具（那样三连失败就复现不出来）。
        // 内容只有一行，跑完由脚本/用户自己删（`临时/` 本来就是可丢的草稿区）。
        val scratch = File(BaopuRuntime.workspaceRoot(this), "临时").apply { mkdirs() }
        val target = File(scratch, "故障注入-验收.txt").apply {
            writeText("684 故障注入探针的目标文件：注入命中时工具根本不会真的执行。\n")
        }
        record("SET", "目标文件", "INFO", target.absolutePath)

        // 只看**本次探针之后**的账：这个会话里可能躺着以前的行/卡，拿旧账充数等于自欺。
        val since = System.currentTimeMillis()
        val startId = maxRunLogId(sid)

        for (r in 1..rounds) {
            val fromId = maxRunLogId(sid)
            val verdict = engine.send(sid, promptOf(r, rounds, tool, target.absolutePath))
            record("RUN", "第 $r 轮发消息", verdict.name, "fromRunLogId=$fromId")
            if (verdict != SendAcceptance.STARTED && verdict != SendAcceptance.QUEUED) {
                return "FAIL 第 $r 轮没被引擎收下（$verdict）——多半是另一个会话正在跑"
            }
            if (!awaitSettled(sid, fromId)) {
                record("RUN", "第 $r 轮收尾", "FAIL", "等了 ${SETTLE_TIMEOUT_MS / 1000}s 没等到 Done/Aborted")
            }
        }

        // 读数一：run_log 的失败行（引擎写的，不是探针写的；只数本次探针之后的）
        val failures = readFailures(sid).filter { it.id > startId }
        failures.forEach { f ->
            record("LOG", "run_log#${f.id}", "INFO", "step=${f.step} tool=${f.tool} ok=${f.ok} err=${f.err}")
        }
        val real = failures.filter { it.tool == tool && !it.ok && it.err.isNotBlank() }
        record(
            "LOG",
            "失败行计数（$tool）",
            if (real.size >= REQUIRED_FAILURES) "PASS" else "FAIL",
            "ok=0 且 err 非空：${real.size} 条（要 ≥ $REQUIRED_FAILURES）",
        )

        // 读数二：归因（生产同一个分类器）
        val owners = real.map { ToolFailureAttribution.classify(it.err) }
        owners.forEachIndexed { i, a ->
            record("ATTR", "第 ${i + 1} 条", a.owner.name, "${a.owner.label}｜${a.reason}｜root=${a.rootCause}")
        }
        val behavior = owners.count { it.owner == FailureOwner.BEHAVIOR }
        record(
            "ATTR",
            "行为类计数",
            if (behavior == real.size && behavior > 0) "PASS" else "FAIL",
            "行为类 $behavior / 共 ${real.size}",
        )

        // 读数三：约束卡（生产收尾路径写的候选档；最多等一会儿——它跟 Settled 事件异步）
        val card = awaitToolRuleCard(tool, since)
        // 同 hash 的卡还在 PENDING 时，生产侧**故意**不再写第二条（跨轮幂等，ProposalRules
        // 的 pendingIds 闸）。这不算失败：卡就是那一张，重复跑不会刷出第二张——如实说明。
        val pending = toolRuleCards(tool)
        val mark = when {
            card != null -> "PASS"
            pending.isNotEmpty() -> "PASS"
            else -> "FAIL"
        }
        record(
            "CARD",
            "约束卡",
            mark,
            when {
                card != null -> "新写入 1 张：id=${card.id} target=${card.target} payload=${card.payload.take(80)}"
                pending.isNotEmpty() -> "幂等：同 hash 的卡已在 PENDING（共 ${pending.size} 张，未再叠加）" +
                    "｜id=${pending.first().id}"
                else -> "候选档里没有 target=$tool 的 TOOL_RULE（聊天页要活着，收尾钩子才跑）"
            },
        )
        if (pending.size > 1) {
            record("CARD", "同工具卡片数", "FAIL", "同一工具堆了 ${pending.size} 张约束卡（同轮限一次破了）")
        }

        val fails = rows.count { it.contains("|FAIL|") }
        return if (fails == 0) {
            "PASS 三连失败可复现：${real.size} 条 ok=0（err 非空）、归因全为行为类、约束卡 1 张"
        } else {
            "FAIL 有 $fails 行没过（看上面 FAIL 那几行）"
        }
    }

    /**
     * 一轮的提示词。
     *
     * 为什么每轮都要「只做这一件事、失败也如实说」：注入让工具必失败，模型看到错误原文后
     * 可能换路/重试——那是**它的自由**（也是失败路径的一部分）；探针只要保证每轮至少
     * 撞一次指定工具，不去干涉模型怎么收场。
     */
    private fun promptOf(round: Int, total: Int, tool: String, path: String): String =
        "故障注入验收（第 $round/$total 轮）：请只调用一次 $tool 工具，path=$path。" +
            "这一轮不要用别的工具替代它；如果它失败了，把失败原因照实说一句就收尾，不要连续重试。"

    // ── 读数实现 ────────────────────────────────────────────────────────────

    private data class FailureRow(val id: Long, val step: Int, val tool: String, val ok: Boolean, val err: String)

    private fun maxRunLogId(sid: Long): Long = BaopuRuntime.db(this).read { d ->
        d.rawQuery("SELECT COALESCE(MAX(id), 0) FROM run_log WHERE session_id = ?", arrayOf(sid.toString()))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    /** 等这一轮收尾（`state` 是 Done/Aborted 且 `tool` 为空的那一行由 `settle` 写）。 */
    private fun awaitSettled(sid: Long, fromId: Long): Boolean {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val done = BaopuRuntime.db(this).read { d ->
                d.rawQuery(
                    "SELECT COUNT(*) FROM run_log WHERE session_id = ? AND id > ? AND tool = '' " +
                        "AND state IN ('Done', 'Aborted')",
                    arrayOf(sid.toString(), fromId.toString()),
                ).use { c -> if (c.moveToFirst()) c.getInt(0) > 0 else false }
            }
            if (done) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    private fun readFailures(sid: Long): List<FailureRow> = BaopuRuntime.db(this).read { d ->
        d.rawQuery(
            "SELECT id, step, tool, ok, err FROM run_log WHERE session_id = ? AND tool != '' ORDER BY id",
            arrayOf(sid.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(FailureRow(c.getLong(0), c.getInt(1), c.getString(2).orEmpty(), c.getInt(3) != 0, c.getString(4).orEmpty()))
                }
            }
        }
    }

    /** 候选档里这个工具现有的 TOOL_RULE 卡（PENDING）。 */
    private fun toolRuleCards(tool: String): List<com.example.zhengdao.baopu.ProposalCandidate> =
        ProposalCandidateStore.under(MemoryLayout.globalMemoryDir(filesDir))
            .listPending()
            .filter { it.kind == ProposalKind.TOOL_RULE && it.target == tool }

    /** 等生产收尾路径把候选写进档（它挂在聊天页的 Settled 事件上，所以慢几拍）。 */
    private fun awaitToolRuleCard(
        tool: String,
        since: Long,
    ): com.example.zhengdao.baopu.ProposalCandidate? {
        val store = ProposalCandidateStore.under(MemoryLayout.globalMemoryDir(filesDir))
        val deadline = System.currentTimeMillis() + CARD_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val hit = store.listPending().firstOrNull {
                it.kind == ProposalKind.TOOL_RULE && it.target == tool && it.createdAt >= since
            }
            if (hit != null) return hit
            Thread.sleep(POLL_MS)
        }
        return null
    }

    // ── 读数出口（logcat／屏／结果文件三处同源） ─────────────────────────────

    private fun record(phase: String, what: String, mark: String, detail: String) {
        val line = "ROW|$phase|$what|$mark|$detail"
        rows += line
        Log.i(TAG, line)
    }

    private fun render(text: String) {
        val tv = view ?: TextView(this).also {
            it.setPadding(24, 24, 24, 24)
            it.typeface = android.graphics.Typeface.MONOSPACE
            view = it
        }
        tv.text = text
        if (tv.parent == null) setContentView(ScrollView(this).apply { addView(tv) })
    }

    private fun renderAndFinish(snapshot: String, started: Long) {
        render(snapshot)
        // 不能立刻 finish：读数要拍成截图（和 436-B 探针同一条理由）。
        Handler(mainLooper).postDelayed({ finish() }, HOLD_MS)
        Log.i(TAG, "ROW|OUT|开始时刻|INFO|$started")
    }

    private fun writeResult(started: Long, report: String) {
        val dir = outDir ?: return
        val body = buildString {
            appendLine("684 故障注入·三连失败 · 真机探针")
            appendLine("开始时刻=$started｜设备=${android.os.Build.MODEL} ${android.os.Build.VERSION.RELEASE}")
            appendLine("包=$packageName")
            appendLine()
            rows.forEach(::appendLine)
            appendLine()
            appendLine("结论：$report")
        }
        runCatching { File(dir, "result.txt").writeText(body) }
            .onFailure { Log.i(TAG, "ROW|OUT|结果文件|FAIL|${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun isDebugBuild(): Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    companion object {
        private const val TAG = "FaultInjectProbe"
        private const val PROBE_DIR = "fault-probe"
        private const val DEFAULT_TOOL = "read_file"
        private const val DEFAULT_ROUNDS = 3
        private const val MAX_ROUNDS = 6
        private const val REQUIRED_FAILURES = 3
        private const val SETTLE_TIMEOUT_MS = 180_000L
        private const val CARD_TIMEOUT_MS = 30_000L
        private const val POLL_MS = 500L
        private const val HOLD_MS = 90_000L
    }
}
