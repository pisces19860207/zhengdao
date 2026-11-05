// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百三十一批 · 影子按需②「复核排到下一轮」的队列账（`影子复核.jsonl`）。
//
// 431 工单②：按需对表验不了 ⇒ **复核排到下一轮（不在收尾重做）**。这本账就是那张排程单：
// 首验验不了 ⇒ 追加一行 pending；消化只发生在**开轮点**（RunEngine 换轮处调
// [ShadowOnDemand.drainDue]，settle/收尾闸/Settled 家族一个钩子都没有——"不在收尾重做"
// 是工单原话，钉在调用点的唯一性上）；复核仍验不了 ⇒ 落 gaveup 收口，不许把队列欠成死账。
//
// 房型照 [ShadowLedger]：JSONL、只追加、`@Synchronized`、写失败返回 false 由调用方 log。
// 同 opId 多行取**最新**（追加式账本不回头改行；终态行盖掉 pending 行）。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.json.JSONObject

/** 复核队列的一行（`影子复核.jsonl`）。 */
internal data class ShadowRecheckRow(
    val id: String,
    val atMs: Long,
    val opId: String,
    val tool: String,
    /** 重读真盘用的根（入队时存下——下一轮复核不许再依赖调用方记得传）。 */
    val workspaceRoot: String,
    /** 已试次数（首验=1；到 [ShadowOnDemand.MAX_ATTEMPTS] 顶 ⇒ 收口）。 */
    val attempts: Int,
    /** pending=等下一轮；concluded=已出结论；gaveup=两试仍验不了，收口。 */
    val state: String,
    val reason: String,
)

/** 影子按需·复核队列（431②）。 */
internal object ShadowRecheck {

    /** 文件名（与影子执行/影子基线同一棵 `抱朴记忆/`——影子的账只进影子自己的文件）。 */
    const val FILE_NAME = "影子复核.jsonl"

    /** 行 id 前缀（按 opId 一把）。 */
    const val ID_PREFIX = "rc:"

    const val STATE_PENDING = "pending"
    const val STATE_CONCLUDED = "concluded"
    const val STATE_GAVEUP = "gaveup"

    fun rowIdOf(opId: String): String = "$ID_PREFIX$opId"

    /** 落点文件。 */
    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    /** 排进队列（已有 pending 的同 opId 不重复排——一次动作一张单）。 */
    fun enqueue(
        root: File,
        opId: String,
        tool: String,
        workspaceRoot: String,
        attempts: Int,
        reason: String,
        nowMs: Long,
    ) {
        if (pendingRows(root).any { it.opId == opId }) return
        append(
            root,
            ShadowRecheckRow(
                id = rowIdOf(opId),
                atMs = nowMs,
                opId = opId,
                tool = tool,
                workspaceRoot = workspaceRoot,
                attempts = attempts,
                state = STATE_PENDING,
                reason = reason,
            ),
        )
    }

    /** 盖一行终态（concluded/gaveup；pending 原样留底，读侧按"最新"取终态）。 */
    fun conclude(root: File, pending: ShadowRecheckRow, state: String, reason: String, nowMs: Long) {
        append(root, pending.copy(atMs = nowMs, state = state, reason = reason))
    }

    /** 某 opId 的**最新**一行（没有 ⇒ null）。
     *
     * ⚠️ 2026-11-05 修 Linux 真红：取"最新"不能用 `maxByOrNull { atMs }`——
     * 台账是**追加式**的，同一毫秒里可能连写「入队 pending」与「收口 concluded」两行
     * （CI 上 mkdir/delete/plant 都在同一毫秒内完成），`maxByOrNull` 在并列时返回**第一个**，
     * 于是"最新一行"取到了入队那行 ⇒ pendingRows 永远不为空（Windows 上因 I/O 慢些、毫秒不同而绿）。
     * 正确口径：**文件里最后出现的那一行才是当前态**（append-only 的语义）。
     */
    fun rowOf(root: File, opId: String): ShadowRecheckRow? = runCatching {
        fileIn(root).takeIf { it.isFile }
            ?.readLines(Charsets.UTF_8)?.mapNotNull { parseLine(it) }
            ?.lastOrNull { it.opId == opId }
    }.getOrDefault(null)

    /** 仍 pending 的那些（同 opId 取**最后一行**再过滤终态；按入队顺序消化）。 */
    fun pendingRows(root: File): List<ShadowRecheckRow> = runCatching {
        val f = fileIn(root)
        if (!f.isFile) return@runCatching emptyList()
        f.readLines(Charsets.UTF_8).mapNotNull { parseLine(it) }
            .groupBy { it.opId }.mapValues { rows -> rows.value.last() }
            .values.filter { it.state == STATE_PENDING }
            .sortedBy { it.atMs }
    }.getOrDefault(emptyList())

    private fun parseLine(raw: String): ShadowRecheckRow? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val op = o.optString("op")
        if (op.isEmpty()) return null
        return ShadowRecheckRow(
            id = o.optString("id", rowIdOf(op)),
            atMs = o.optLong("at", 0L),
            opId = op,
            tool = o.optString("tool"),
            workspaceRoot = o.optString("root"),
            attempts = o.optInt("attempts", 0),
            state = o.optString("state", STATE_PENDING),
            reason = o.optString("reason"),
        )
    }

    /** 追加一行（失败返回 false——排程单丢一行调用方 log，不影响判定）。 */
    @Synchronized
    private fun append(root: File, row: ShadowRecheckRow): Boolean = runCatching {
        val f = fileIn(root)
        f.parentFile?.mkdirs()
        f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    private fun ShadowRecheckRow.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("at", atMs)
        .put("op", opId)
        .put("tool", tool)
        .put("root", workspaceRoot)
        .put("attempts", attempts)
        .put("state", state)
        .put("reason", reason)
}
