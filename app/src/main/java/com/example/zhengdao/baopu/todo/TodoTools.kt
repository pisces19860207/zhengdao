// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十一批 · 待办中心的**三个工具**（模型手里的那三个口）。
//
// 用户口径（2026-11-09 派工单原文）：
//   `todo_add(text, sourceId)` · `todo_list()` · `todo_mark_done(id)`（模型可调）
//   · `todo_confirm(id)` **只由 UI 调**，模型不可自删。
//
// 「模型不可自删」是怎么做到的（不是靠模型自觉，是**结构上够不着**）：
//   1. `todo_confirm` **根本没有登记成工具**——它不在 `BaopuToolCatalog.ENTRIES`、
//      不在 `ToolGroupTable.GROUP_OF`，注册表 `find("todo_confirm")` 回 null，
//      模型真去调只会拿到"没有这个工具"（门禁 `TodoCenterTest` 用注册表真查，不看注释）；
//   2. 就算有人将来手滑把它登记进来，落盘口 [TodoStore.confirm] 还会按
//      [TodoRules.confirmRefusal] 再拒一次：非 [TodoActor.USER] 一律拒（判据⑤的负例）。
//      两道闸各管一段：①管"模型看不见"，②管"就算看得见也删不掉"。
//
// 三个工具都归 FILES 组、都进 `ToolGroup.UNLOCK_FREE_SILENT`（免解锁、不进 capabilities 名册）：
// 与 542 的 `board_*` / `governance_state` 同一条口径——「这三件是模型的手账，永远在手边，
// 没有『要不要开』可谈」。入口净增 0：本文件与 [TodoStore] 都不碰任何导航、不新增 Activity。
package com.example.zhengdao.baopu.todo

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.ToolTimeout
import com.example.zhengdao.baopu.agent.jsonArrayOf
import com.example.zhengdao.baopu.agent.jsonObjectOf
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// ── todo_add ────────────────────────────────────────────────────────────────

/**
 * 立一条待办（R1：只写工作区 `产物/.todo.jsonl`，不动用户文件、不联网）。
 *
 * 参数 `text` 必填、`sourceId` 可省（省了就记"未注明"）：必填参数只要一个，
 * 模型漏传 sourceId 不该整条拒掉（那是把"记一笔"变成"先背参数"）。
 */
internal object TodoAddTool {

    const val NAME = "todo_add"

    const val ARG_TEXT = "text"
    const val ARG_SOURCE = "sourceId"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "给自己立一条待办（你自己的手账，显示在自治理中心的「待办」那一块）。" +
            "text 只有**一句话**、最多 ${TodoRules.MAX_SUMMARY_CHARS} 字：写「要做什么」就够，" +
            "正文/来源不要抄进来（超了会被**拒收**并让你按长度重写）。" +
            "sourceId 可选：这条从哪来的（缺口编号/提案 id/用户那句话的编号）。" +
            "同摘要且还没删的条目不会重复立，回你既有的那条 id。" +
            "做完之后调 todo_mark_done(id)：那条会由右相判 PASS 才进「已完成」，" +
            "最后一下删除是**用户**点的，你删不了。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_TEXT to jsonObjectOf(
                    "type" to "string",
                    "description" to "一句话摘要（≤${TodoRules.MAX_SUMMARY_CHARS} 字，单行）：写要做什么，不写正文和来源。",
                ),
                ARG_SOURCE to jsonObjectOf(
                    "type" to "string",
                    "description" to "可选：这条从哪来（缺口编号/提案 id/用户原话编号），省了记「未注明」。",
                ),
            ),
            "required" to jsonArrayOf(ARG_TEXT),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE, // 手账：每记一笔弹一张卡就把"记待办"变成打扰（风险档仍是 R1）
        riskTier = "R1",
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot(), System.currentTimeMillis()) }

    fun run(args: JSONObject, workspaceRoot: File, nowMs: Long): ToolOutcome {
        val write = TodoStore.add(workspaceRoot, args.optString(ARG_TEXT), args.optString(ARG_SOURCE), nowMs)
        return when (write) {
            is TodoWrite.Refused -> ToolOutcome.Failed(write.reason)
            is TodoWrite.Ok -> {
                val counts = TodoStore.counts(workspaceRoot)
                ToolOutcome.Ok(
                    JSONObject()
                        .put("id", write.item.id)
                        .put("summary", write.item.text)
                        .put("chars", TodoRules.charCount(write.item.text))
                        .put("state", write.item.state.wire)
                        .put("created", write.created)
                        .put("header", TodoRules.headerLine(counts))
                        .toString(),
                )
            }
        }
    }
}

// ── todo_list ───────────────────────────────────────────────────────────────

/** 看待办（R0 只读：只读这一本手账）。 */
internal object TodoListTool {

    const val NAME = "todo_list"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "看你自己立的待办（自治理中心那块「待办」的同一本账）：每条只有一句话摘要与状态" +
            "（todo 待做／done 已做待右相／complete 已完成待用户确认），外加右相最近一次结论。" +
            "顶上的 header 就是收起态那一行「待办 N · 在做 M」。删除**不由你**做。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        riskTier = "R0", // 只读：读手账、不写一字节
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { _ -> run(workspaceRoot()) }

    fun run(workspaceRoot: File): ToolOutcome {
        val items = TodoRules.live(TodoStore.items(workspaceRoot))
        val counts = TodoRules.counts(TodoStore.items(workspaceRoot))
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("summary", TodoRules.rowText(item))
                    .put("state", item.state.wire)
                    .put("stateCn", item.state.cn)
                    .put("status", TodoRules.statusLine(item))
                    .put("verdict", item.verdict)
                    .put("verdictReason", item.verdictReason),
            )
        }
        return ToolOutcome.Ok(
            JSONObject()
                .put("v", TodoRules.VERSION)
                .put("header", TodoRules.headerLine(counts))
                .put("todo", counts.todo)
                .put("doing", counts.doing)
                .put("complete", counts.complete)
                .put("total", items.size)
                .put("items", arr)
                .toString(),
        )
    }
}

// ── todo_mark_done ──────────────────────────────────────────────────────────

/**
 * 标「已做」（R1：写手账两行——`done` 谁做、`verify` 谁验）。
 *
 * 关键是**标了不等于完成**：这一调用里就把右相的托单装好送判（[TodoVerify.judge]），
 * 三态结论原样落账并回灌给模型。PASS 才进「已完成」；FAIL／UNVERIFIABLE 停在「已做」，
 * 并把"缺什么"写在回执里（模型可以补做之后**再标一次**——已做状态允许重复标，见 [TodoRules.doneRefusal]）。
 */
internal object TodoMarkDoneTool {

    const val NAME = "todo_mark_done"

    const val ARG_ID = "id"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "把一条待办标成「已做」。**标了不等于完成**：这一下会把这条送到右相验收" +
            "（右相看的是账本上真实的落盘记录，不看你说的话）。右相判 PASS 才进「已完成」；" +
            "判 FAIL／UNVERIFIABLE 就停在「已做」并把缺什么回给你——补完之后可以再标一次。" +
            "（完工后要留着交付物在工作区里：什么都没落盘时右相判不了，那不算做完。）" +
            "删除不在这里：最后一下由用户点确认。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_ID to jsonObjectOf(
                    "type" to "string",
                    "description" to "待办 id（形如 t3；先用 todo_list 拿，不要自己编）。",
                ),
            ),
            "required" to jsonArrayOf(ARG_ID),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE, // 手账：每标一次弹一张卡等于让模型不敢收口（风险档仍是 R1）
        riskTier = "R1",
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot(), System.currentTimeMillis()) }

    fun run(args: JSONObject, workspaceRoot: File, nowMs: Long): ToolOutcome {
        val id = args.optString(ARG_ID).trim()
        if (id.isEmpty()) return ToolOutcome.Failed("少了 id：先用 todo_list 看有哪些条（id 形如 t3）")
        val item = TodoStore.items(workspaceRoot).firstOrNull { it.id == id }
            ?: return ToolOutcome.Failed("没有这条待办：$id（先用 todo_list 看一眼，别自己编 id）")
        TodoRules.doneRefusal(item)?.let { return ToolOutcome.Failed(it) }
        if (!TodoStore.append(workspaceRoot, TodoStore.row(TodoOp.DONE, id, TodoActor.MODEL, nowMs))) {
            return ToolOutcome.Failed("待办账写不进去（盘上原有内容没动）——如实说「没记上」，别当成标成了。")
        }
        val reason = verifyAndRecord(workspaceRoot, item, id, nowMs)
        val after = TodoStore.items(workspaceRoot).firstOrNull { it.id == id }
            ?: return ToolOutcome.Failed("账本写完却读不回 $id（不该发生）——如实说，别猜状态。")
        RunLog.log("todo_mark_done: $id 右相 ${after.verdict}")
        return ToolOutcome.Ok(resultJson(after, reason).toString())
    }

    /** 送右相判、把结论落成 `verify` 行（谁验），返回那一行的人话理由。 */
    private fun verifyAndRecord(workspaceRoot: File, item: TodoItem, id: String, nowMs: Long): String {
        val report = TodoVerify.judge(
            wsRoot = workspaceRoot,
            item = item,
            fileOps = FileOpLog.recent(TodoVerify.FILE_OPS_WINDOW),
            decisions = decisions(),
        )
        val reason = TodoVerify.brief(report)
        // 落账失败也不能把"已判 PASS"说成没判：这一行写不进 ⇒ 状态停在已做（fail-closed 的方向）
        TodoStore.append(
            workspaceRoot,
            TodoStore.row(
                op = TodoOp.VERIFY,
                id = id,
                who = TodoActor.RIGHT_MINISTER,
                atMs = nowMs,
                verdict = report.verdict.name,
                reason = reason,
            ),
        )
        return reason
    }

    /** 回执（模型要能一眼看出"现在到哪一步、下一步该谁动"）。 */
    private fun resultJson(after: TodoItem, reason: String): JSONObject = JSONObject()
        .put("id", after.id)
        .put("summary", TodoRules.rowText(after))
        .put("state", after.state.wire)
        .put("stateCn", after.state.cn)
        .put("verdict", after.verdict)
        .put("reason", reason)
        .put("complete", after.state == TodoState.COMPLETE)
        .put(
            "next",
            if (after.state == TodoState.COMPLETE) {
                "已完成：等用户点确认才会从看板移除（你删不了）"
            } else {
                "还在「已做」：右相没判 PASS，按上面的 reason 补齐后可以再标一次"
            },
        )

    /** 决策账快照（读不到 ⇒ 空集，不猜；与 `ChancellorWiring.decisionSnapshot` 同一口径）。 */
    private fun decisions(): List<DecisionRecord> =
        DecisionLedger.installedRoot()?.let { root -> runCatching { DecisionLedger.read(root) }.getOrNull() }
            ?: emptyList()
}
