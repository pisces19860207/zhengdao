// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十四批 · 项目账本的**落盘口**（全仓唯一碰 `<工作区>/.zd-ledger.jsonl` 的地方）。
//
// 落点就是工作区根、点号开头（用户点名的路径）。点号不是审美：
// `ReflectionRunner.isCountedProduct` 把点号开头的文件排除在「产物 N 件」之外
// （与 512 游标、541 `产物/.todo.jsonl` 同一口径）。账不是产物——读数里多一件就是账不实。
//
// 三条边界：
//   1. **append-only**：只追加，不改历史行、不删行。归档＝追加一条 `archive`，
//      默认视图由 [LedgerRules.fold] 的 replay 决定"露不露面"。「账不删」不是靠某人记得备份，
//      而是**结构上删不掉**（判据⑤）。
//   2. **半行不致命**：追加是单次 write，最坏末尾坏一行 JSON；读侧跳过坏行不崩（[rows]），
//      不会把整本账写成读不出来。
//   3. **不认识 Android**：只吃 `java.io.File` + `org.json`，JVM 单测直接打（同 `TodoStore` 房型）。
//
// 「已收」为什么只走 [recordDone]：它把右相那一次结论（PASS/UNVERIFIABLE/FAIL）与目标状态
// 一起落进 `done` 行。想让一条变「已收」，**没有别的入口**——这是判据②的结构保证。
package com.example.zhengdao.baopu.ledger

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/** 一次写入的结果（成功给条目；失败给**人话**，调用方原样回灌给模型/界面）。 */
internal sealed class LedgerWrite {
    data class Ok(val item: LedgerItem, val created: Boolean = false) : LedgerWrite()

    data class Refused(val reason: String) : LedgerWrite()
}

/** 项目账本的 IO 壳（判据全在 [LedgerRules]，验收在 [LedgerVerify]）。 */
internal object LedgerStore {

    /** 稳定键：点号开头 ⇒ 不计入「产物 N 件」。改这个名字等于换一本账。 */
    const val FILE_NAME: String = ".zd-ledger.jsonl"

    /** 落点：`<工作区>/.zd-ledger.jsonl`（工作区根，不是 `产物/`——它是账，不是产物）。 */
    fun fileOf(wsRoot: File): File = File(wsRoot, FILE_NAME)

    /**
     * 读全本账（不存在 ⇒ 空表：一本空账是合法状态，不是错误）。
     *
     * 坏行**跳过不崩**：追加写被打断时最后一行可能是半截 JSON，那一行不算数，
     * 前面的行照读——整本账不该因为一行坏字节变成"读不出"。
     */
    fun rows(wsRoot: File): List<LedgerRow> {
        val f = fileOf(wsRoot)
        if (!f.isFile) return emptyList()
        val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrElse {
            RunLog.log("项目账读不出：${it.javaClass.simpleName}")
            return emptyList()
        }
        return lines.mapNotNull { parse(it) }
    }

    /** 折成账上条目（含已归档的；默认视图用 [LedgerRules.live]）。 */
    fun items(wsRoot: File): List<LedgerItem> = LedgerRules.fold(rows(wsRoot))

    /** 记录态那一行要的几个数。 */
    fun counts(wsRoot: File): LedgerCounts = LedgerRules.counts(items(wsRoot))

    /**
     * 追加一行（唯一写口）。
     *
     * @return false ＝没写成（盘上原有内容一个字节没动），调用方必须如实说"没记上"
     */
    fun append(wsRoot: File, row: LedgerRow): Boolean = runCatching {
        val f = fileOf(wsRoot)
        val dir = f.parentFile ?: return@runCatching false
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        // 那个位置上要是躺着一件不是普通文件的东西（同名目录之类），一律不动它、如实失败
        if (f.exists() && !f.isFile) return@runCatching false
        f.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
        true
    }.getOrElse {
        RunLog.log("项目账写失败：${it.javaClass.simpleName}")
        false
    }

    /** 造一行（字段口径只此一处；`reason` 在这里就压成一行）。 */
    fun row(
        kind: LedgerKind,
        id: String,
        atMs: Long,
        title: String = "",
        status: String = "",
        sourceMsgId: String = "",
        verdict: String = "",
        reason: String = "",
    ): LedgerRow = LedgerRow(
        kind = kind,
        id = id,
        atMs = atMs,
        title = title,
        status = status,
        sourceMsgId = sourceMsgId,
        verdict = verdict,
        reason = LedgerRules.oneLine(reason),
    )

    /**
     * 立一条（模型调 `ledger_add`）。
     *
     * 同标题 + 同来源且**还活着** ⇒ 不重复立，回既有那条（模型每轮记一遍同一件事
     * 不会把账刷成一堆重复行）。归档的、撤的算旧账：重做就该是一条新账（`nextId` 只增不复用）。
     */
    fun add(wsRoot: File, rawTitle: String, rawSource: String, nowMs: Long): LedgerWrite {
        LedgerRules.addRefusal(rawTitle, rawSource)?.let { return LedgerWrite.Refused(it) }
        val title = LedgerRules.normalize(rawTitle)
        val src = LedgerRules.normalize(rawSource)
        val alive = LedgerRules.live(items(wsRoot)).filter { it.status != LedgerState.WITHDRAWN }
        alive.firstOrNull { it.title == title && it.sourceMsgId == src }?.let {
            return LedgerWrite.Ok(it, created = false)
        }
        val rows = rows(wsRoot)
        val id = LedgerRules.nextId(rows)
        val row = row(
            kind = LedgerKind.ADD,
            id = id,
            atMs = nowMs,
            title = title,
            status = LedgerState.FLYING.wire,
            sourceMsgId = src,
        )
        if (!append(wsRoot, row)) {
            return LedgerWrite.Refused("项目账写不进去（盘上原有内容没动）——如实说「没记上」，别当成记上了。")
        }
        val item = items(wsRoot).firstOrNull { it.id == id }
            ?: return LedgerWrite.Refused("账本写完却读不回 $id（不该发生）——如实说，别猜状态。")
        RunLog.log("ledger_add: $id 立起来（${LedgerRules.charCount(title)} 字）")
        return LedgerWrite.Ok(item, created = true)
    }

    /**
     * 改状态（**不含「已收」**；已收只走 [recordDone]，见文件头）。
     *
     * 冻结用 `kind=freeze`（动作分开记），其余用 `kind=status`。
     */
    fun moveTo(wsRoot: File, id: String, target: LedgerState, nowMs: Long): LedgerWrite {
        if (target == LedgerState.ACCEPTED) {
            return LedgerWrite.Refused("「已收」不能直接写——它只能由右相判过 PASS 的那一次产生（见 recordDone）。")
        }
        val item = items(wsRoot).firstOrNull { it.id == id }
        LedgerRules.transitionRefusal(item, target)?.let { return LedgerWrite.Refused(it) }
        val kind = if (target == LedgerState.FROZEN) LedgerKind.FREEZE else LedgerKind.STATUS
        val row = row(kind = kind, id = id, atMs = nowMs, status = target.wire)
        if (!append(wsRoot, row)) {
            return LedgerWrite.Refused("项目账写不进去（盘上原有内容没动）——如实说「没记上」，状态没变。")
        }
        RunLog.log("ledger_status: $id → ${target.wire}")
        val after = items(wsRoot).firstOrNull { it.id == id }
            ?: return LedgerWrite.Refused("账本写完却读不回 $id（不该发生）——如实说，别猜状态。")
        return LedgerWrite.Ok(after)
    }

    /**
     * 落**右相那一次验收**（`kind=done`）：PASS ⇒ 「已收」；判不了/判 FAIL ⇒ 「待验收」。
     *
     * 判据②：这里没有"按 verdict 之外的依据写已收"的分支——状态是**结论算出来的**，
     * 不是调用者传进来的。
     */
    fun recordDone(wsRoot: File, id: String, verdict: String, reason: String, nowMs: Long): LedgerWrite {
        val item = items(wsRoot).firstOrNull { it.id == id }
            ?: return LedgerWrite.Refused("没有这条（id 不对）：先用 ledger_list 看一眼。")
        val target = if (verdict == LedgerRules.VERDICT_PASS) LedgerState.ACCEPTED else LedgerState.PENDING
        LedgerRules.transitionRefusal(item, target)?.let { return LedgerWrite.Refused(it) }
        val row = row(
            kind = LedgerKind.DONE,
            id = id,
            atMs = nowMs,
            status = target.wire,
            verdict = verdict,
            reason = reason,
        )
        if (!append(wsRoot, row)) {
            return LedgerWrite.Refused("项目账写不进去（盘上原有内容没动）——如实说「没记上」，状态没变。")
        }
        RunLog.log("ledger_done: $id 右相 $verdict → ${target.wire}")
        val after = items(wsRoot).firstOrNull { it.id == id }
            ?: return LedgerWrite.Refused("账本写完却读不回 $id（不该发生）——如实说，别猜状态。")
        return LedgerWrite.Ok(after)
    }

    /**
     * **用户点的那一下**（只由界面调用；模型永远走不到这里）。
     *
     * 归档＝默认视图隐藏，**不删任何行**（判据⑤）。两条硬边界：
     * ① 只有用户能归档；② 还在飞/待验收的不许归档（那是在掩盖"在做啥"）。
     */
    fun archive(wsRoot: File, id: String, actor: LedgerActor, nowMs: Long): LedgerWrite {
        if (actor != LedgerActor.USER) {
            return LedgerWrite.Refused("拒：账只能由**用户**点确认归档——模型和右相都归档不了（544 的硬边界）。")
        }
        val item = items(wsRoot).firstOrNull { it.id == id }
            ?: return LedgerWrite.Refused("没有这条（id 不对，或者从来没立过）。")
        if (item.archivedAtMs > 0L) return LedgerWrite.Refused("这条已经归档过了（默认视图里早就没有它）。")
        if (item.status == LedgerState.FLYING || item.status == LedgerState.PENDING) {
            return LedgerWrite.Refused(
                "「${item.status.cn}」的东西不能归档——还在做/等验收，归档就等于从「在做啥」里抹掉它。" +
                    "先收或先撤，再归档。",
            )
        }
        val row = row(
            kind = LedgerKind.ARCHIVE,
            id = id,
            atMs = nowMs,
            status = item.status.wire,
        )
        if (!append(wsRoot, row)) {
            return LedgerWrite.Refused("项目账写不进去——默认视图里它还在（没归档成）。")
        }
        RunLog.log("ledger_archive: $id 用户确认归档（账本保留该条全部历史行）")
        return LedgerWrite.Ok(item.copy(archivedAtMs = nowMs))
    }

    /** 解析一行（坏行 ⇒ null；见 [rows] 的口径）。 */
    private fun parse(line: String): LedgerRow? {
        val raw = line.trim()
        if (raw.isEmpty()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val kind = LedgerKind.ofWire(o.optString("kind")) ?: return null
        val id = o.optString("id")
        if (id.isEmpty()) return null
        return LedgerRow(
            kind = kind,
            id = id,
            atMs = o.optLong("at", 0L),
            title = o.optString("title"),
            status = o.optString("status"),
            sourceMsgId = o.optString("sourceMsgId"),
            verdict = o.optString("verdict"),
            reason = o.optString("reason"),
        )
    }
}

/** 一行 → JSON（键名固定；少写一个键就是历史读不回来，不走"默认值兜"）。 */
internal fun LedgerRow.toJson(): JSONObject {
    val o = JSONObject()
        .put("v", LedgerRules.VERSION)
        .put("kind", kind.wire)
        .put("id", id)
        .put("at", atMs)
    if (title.isNotEmpty()) o.put("title", title)
    if (status.isNotEmpty()) o.put("status", status)
    if (sourceMsgId.isNotEmpty()) o.put("sourceMsgId", sourceMsgId)
    if (verdict.isNotEmpty()) o.put("verdict", verdict)
    if (reason.isNotEmpty()) o.put("reason", reason)
    return o
}
