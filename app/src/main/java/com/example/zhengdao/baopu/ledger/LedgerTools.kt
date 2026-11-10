// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十四批 · 项目账本的**三个工具**（模型手里的那三个口）。
//
// 用户口径（2026-11-09 派工单原文）：「模型 `ledger_add/status/list`；
//   **右相 PASS 才可「已收」**（UNVERIFIABLE 只能「待验收」）；左相判提醒；
//   用户只点一次「确认归档」（归档＝默认视图隐藏，账不删）」。
//
// 「归档」为什么不在这里（与 541 的 `todo_confirm` 同一手法：**结构上够不着**）：
//   1. `ledger_archive` **根本没有登记成工具**——不在 `BaopuToolCatalog.ENTRIES`、
//      不在 `ToolGroupTable.GROUP_OF`，注册表 `find("ledger_archive")` 回 null，
//      模型真去调只会拿到"没有这个工具"（门禁 `LedgerCenterTest` 拿真注册表查，不看注释）；
//   2. 就算将来有人手滑把它登记进来，落盘口 `LedgerStore.archive` 还会按角色再拒一次。
//      两道闸各管一段：①管"模型看不见"，②管"就算看得见也归档不了"。
//
// 三个工具都归 FILES 组、都进 `ToolGroup.UNLOCK_FREE_SILENT`（免解锁、不进 capabilities 名册）：
// 与 541 `todo_*` / 542 `board_*` 同一条口径——「这是模型的手账，永远在手边」。
// 入口净增 0：本文件与 Store 都不碰导航、不新增 Activity、不动任何 Screen。
package com.example.zhengdao.baopu.ledger

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.ToolTimeout
import com.example.zhengdao.baopu.agent.TurnSourceWiring
import com.example.zhengdao.baopu.agent.jsonArrayOf
import com.example.zhengdao.baopu.agent.jsonObjectOf
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// ── ledger_add ──────────────────────────────────────────────────────────────

/**
 * 立一条（R1：只写工作区 `.zd-ledger.jsonl`，不动用户文件、不联网）。
 *
 * `sourceMsgId` **可选**（574）：模型不传 ⇒ 执行体用本轮用户消息的 messageId 自动补
 * （[TurnSourceWiring]，引擎落库的真值，不是模型嘴上的号）；
 * 定时/后台跑没有这句话 ⇒ 落 null——**不写「缺口-XXX」这类占位号**。
 */
internal object LedgerAddTool {

    const val NAME = "ledger_add"

    const val ARG_TITLE = "title"
    const val ARG_SOURCE = "sourceMsgId"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "在项目账上给自己记一件事（你自己的工作账，显示在「账本」那一块）。" +
            "title 只有**一句话**、最多 ${LedgerRules.MAX_TITLE_CHARS} 字（超了拒收并让你按长度重写）。" +
            "sourceMsgId 可选：这件事从哪来（用户那句话的编号／缺口编号／提案 id）——" +
            "不传就自动记本轮那句话的编号（定时/后台跑没有这句话，就空着）。" +
            "会记成「在飞」；做完调 ledger_status 标「已收」送右相。" +
            "同标题同来源且还活着的条目不会重复立，回你既有的那条 id。归档不由你做。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_TITLE to jsonObjectOf(
                    "type" to "string",
                    "description" to "一句话标题（≤${LedgerRules.MAX_TITLE_CHARS} 字，单行）：写这件事是什么。",
                ),
                ARG_SOURCE to jsonObjectOf(
                    "type" to "string",
                    "description" to "可选：来源凭证号（用户那句话的编号／缺口编号／提案 id）；" +
                        "不传自动记本轮消息号，没有就空着。",
                ),
            ),
            "required" to jsonArrayOf(ARG_TITLE),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE, // 手账：每记一笔弹一张卡会把"记账"变成打扰（风险档仍是 R1）
        riskTier = "R1",
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot(), System.currentTimeMillis()) }

    fun run(args: JSONObject, workspaceRoot: File, nowMs: Long): ToolOutcome {
        val write = LedgerStore.add(
            workspaceRoot,
            args.optString(ARG_TITLE),
            TurnSourceWiring.resolveFrom(args, ARG_SOURCE),
            nowMs,
        )
        return when (write) {
            is LedgerWrite.Refused -> ToolOutcome.Failed(write.reason)
            is LedgerWrite.Ok -> {
                val counts = LedgerStore.counts(workspaceRoot)
                ToolOutcome.Ok(
                    JSONObject()
                        .put("id", write.item.id)
                        .put("title", write.item.title)
                        .put("chars", LedgerRules.charCount(write.item.title))
                        .put("status", write.item.status.wire)
                        .put("statusCn", write.item.status.cn)
                        .put("sourceMsgId", write.item.sourceMsgId)
                        .put("created", write.created)
                        .put("header", LedgerRules.headerLine(counts))
                        .toString(),
                )
            }
        }
    }
}

// ── ledger_status ───────────────────────────────────────────────────────────

/**
 * 改状态（R1：写账两行——`status/freeze` 谁改、`done` 谁验）。
 *
 * 四个目标状态直接写；`accepted`（已收）**不是**直接写：这一调用里就把右相托单装好送判
 * （[LedgerVerify.judge]），PASS 才落「已收」，FAIL／UNVERIFIABLE 落「待验收」并把
 * "缺什么"回灌给模型。判据②就钉在这一段：没有第二个入口能写「已收」。
 */
internal object LedgerStatusTool {

    const val NAME = "ledger_status"

    const val ARG_ID = "id"
    const val ARG_STATUS = "status"

    /** 拿中文写状态的也认（`在飞`／`flying` 两种写法都见过）。 */
    private const val STATE_HINT =
        "flying 在飞／pending 待验收／frozen 冻结／withdrawn 撤／accepted 已收"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "改一条账的状态。可写 $STATE_HINT。" +
            "**「已收」不是自己标就完事**：这一下会把这条送到右相验收（右相看账上真实的落盘记录，" +
            "不听你怎么说）——判 PASS 才进「已收」；判 FAIL／UNVERIFIABLE 就停在「待验收」，" +
            "并把缺什么回给你（补齐后可再标一次）。已收与撤是终点，改不回去；要重做就另立一条。" +
            "「冻结」是暂停（解冻就回在飞）。归档不由你做。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_ID to jsonObjectOf(
                    "type" to "string",
                    "description" to "账上那条的 id（形如 L3；先用 ledger_list 拿，不要自己编）。",
                ),
                ARG_STATUS to jsonObjectOf(
                    "type" to "string",
                    "description" to "目标状态：$STATE_HINT（中文或英文都收；别的值会被拒并列出可选值）。",
                ),
            ),
            "required" to jsonArrayOf(ARG_ID, ARG_STATUS),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE, // 手账：每改一次弹一张卡等于让模型不敢收口（风险档仍是 R1）
        riskTier = "R1",
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot(), System.currentTimeMillis()) }

    fun run(args: JSONObject, workspaceRoot: File, nowMs: Long): ToolOutcome {
        val id = args.optString(ARG_ID).trim()
        if (id.isEmpty()) {
            return ToolOutcome.Failed("少了 id：先用 ledger_list 看有哪些条（id 形如 L3）")
        }
        val rawStatus = args.optString(ARG_STATUS)
        val target = LedgerState.ofAny(rawStatus) ?: return ToolOutcome.Failed(
            "不认得这个状态「$rawStatus」——只能写：$STATE_HINT。",
        )
        return if (target == LedgerState.ACCEPTED) {
            complete(workspaceRoot, id, nowMs)
        } else {
            when (val write = LedgerStore.moveTo(workspaceRoot, id, target, nowMs)) {
                is LedgerWrite.Refused -> ToolOutcome.Failed(write.reason)
                is LedgerWrite.Ok -> ToolOutcome.Ok(resultJson(write.item, "").toString())
            }
        }
    }

    /** 「已收」那一路：先过状态闸，再送右相，最后按**结论**落状态。 */
    private fun complete(workspaceRoot: File, id: String, nowMs: Long): ToolOutcome {
        val item = LedgerStore.items(workspaceRoot).firstOrNull { it.id == id }
            ?: return ToolOutcome.Failed("没有这条（id 不对）：先用 ledger_list 看一眼，别自己编 id")
        LedgerRules.transitionRefusal(item, LedgerState.ACCEPTED)?.let { return ToolOutcome.Failed(it) }
        val report = LedgerVerify.judge(
            wsRoot = workspaceRoot,
            item = item,
            fileOps = FileOpLog.recent(LedgerVerify.FILE_OPS_WINDOW),
            decisions = decisions(),
        )
        val reason = LedgerVerify.brief(report)
        // 落账失败也不能把"已判 PASS"说成没判：这一行写不进 ⇒ 状态不动（fail-closed 的方向）
        val write = LedgerStore.recordDone(workspaceRoot, id, report.verdict.name, reason, nowMs)
        return when (write) {
            is LedgerWrite.Refused -> ToolOutcome.Failed(write.reason)
            is LedgerWrite.Ok -> {
                RunLog.log("ledger_status: $id 右相 ${report.verdict.name}")
                ToolOutcome.Ok(resultJson(write.item, reason).toString())
            }
        }
    }

    /** 回执（模型要能一眼看出"现在到哪一步、下一步该谁动"）。 */
    private fun resultJson(after: LedgerItem, reason: String): JSONObject = JSONObject()
        .put("id", after.id)
        .put("title", LedgerRules.rowText(after))
        .put("status", after.status.wire)
        .put("statusCn", after.status.cn)
        .put("line", LedgerRules.statusLine(after))
        .put("verdict", after.verdict)
        .put("reason", reason)
        .put("accepted", after.status == LedgerState.ACCEPTED)
        .put("next", nextHint(after))

    /** 下一步该谁动（这一段是模型最容易搞错的地方，写成一句话）。 */
    private fun nextHint(after: LedgerItem): String = when (after.status) {
        LedgerState.ACCEPTED -> "已收：这件事到此为止（归档那一下由用户点，你点不了）"
        LedgerState.PENDING -> "还在「待验收」：右相没判 PASS，按上面的 reason 补齐后可以再标一次"
        LedgerState.FROZEN -> "已冻结：解冻就能回「在飞」（冻着的东西不进待验收）"
        LedgerState.WITHDRAWN -> "已撤：账留着当证据，要重做另立一条"
        LedgerState.FLYING -> "在飞：做完调 ledger_status 标 accepted 送右相"
    }

    /** 决策账快照（读不到 ⇒ 空集，不猜；与 `ChancellorWiring.decisionSnapshot` 同一口径）。 */
    private fun decisions(): List<DecisionRecord> =
        DecisionLedger.installedRoot()?.let { root -> runCatching { DecisionLedger.read(root) }.getOrNull() }
            ?: emptyList()
}

// ── ledger_list ─────────────────────────────────────────────────────────────

/** 看账（R0 只读：只读这一本账；归档的默认不露，`includeArchived` 才一起给）。 */
internal object LedgerListTool {

    const val NAME = "ledger_list"

    const val ARG_INCLUDE_ARCHIVED = "includeArchived"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "看你自己的项目账（「账本」那一块的同一本）：每条只有一句话标题与状态" +
            "（flying 在飞／pending 待验收／accepted 已收／frozen 冻结／withdrawn 撤），" +
            "外加右相最近一次结论。顶上的 header 就是记录态那一行。" +
            "归档的条目默认不露面（账里仍在）：要连它们一起看就传 includeArchived=true。" +
            "归档不由你做——那是用户点的最后一下。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_INCLUDE_ARCHIVED to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "可选：true＝连已归档的一起给（默认 false＝只看还在账面上的）。",
                ),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        riskTier = "R0", // 只读：读账、不写一字节
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(workspaceRoot(), args.optBoolean(ARG_INCLUDE_ARCHIVED, false)) }

    fun run(workspaceRoot: File, includeArchived: Boolean = false): ToolOutcome {
        val all = LedgerStore.items(workspaceRoot)
        val counts = LedgerRules.counts(all)
        val shown = if (includeArchived) all else LedgerRules.live(all)
        val arr = JSONArray()
        shown.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", LedgerRules.rowText(item))
                    .put("status", item.status.wire)
                    .put("statusCn", item.status.cn)
                    .put("line", LedgerRules.statusLine(item))
                    .put("verdict", item.verdict)
                    .put("reason", item.reason)
                    .put("sourceMsgId", item.sourceMsgId)
                    .put("archived", item.archivedAtMs > 0L),
            )
        }
        return ToolOutcome.Ok(
            JSONObject()
                .put("v", LedgerRules.VERSION)
                .put("header", LedgerRules.headerLine(counts))
                .put("flying", counts.flying)
                .put("pending", counts.pending)
                .put("accepted", counts.accepted)
                .put("frozen", counts.frozen)
                .put("withdrawn", counts.withdrawn)
                .put("archived", counts.archived)
                .put("total", shown.size)
                .put("items", arr)
                .toString(),
        )
    }
}
