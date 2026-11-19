// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十二批 · 记忆修剪的**账本／回滚档／状态**三件（判据一行不落在这里）。
//
// 三条纪律（照 678 ReplayStores 的房型）：
// 1. **自身产出只写自己的三样**：本账 `<工作区>/产物/记忆修剪.jsonl`（起居注**同形**
//    ACTION 行，635 房型）＋ 回滚档（App 私有 `filesDir/抱朴记忆/修剪回滚/`——**不进
//    工作区**，与 157 的写前备份同隐私口径）＋ 一行状态文件
//    `<工作区>/产物/记忆修剪-状态.json`。记忆条目文件**只在装配层**经
//    [com.example.zhengdao.baopu.MemoryStore.update]（或撤销的**原像写回**）动——
//    本文件没有 EntryCodec、没有记忆条目构造。
// 2. **账只增不删**（504/635 口径）：撤销也是一行 ACTION，不擦历史。
// 3. **回滚档是写前原像**（判据④的地基）：应用任何状态变更**之前**，先把要动的每条
//    记忆文件**原文**存进回滚档；撤销＝逐条把原像写回去（字节级还原），索引重建后
//    与状态一起回到修剪前。原像没写上 ⇒ 整轮中止、一条不动（半份计划比没有更坏）。
package com.example.zhengdao.baopu.memoryprune

import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.safety.DiaryCodec
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 修剪账（自身产出；被读的记忆条目与起居注互不改写）。 */
internal object PruneLedger {

    /** 认领自己行的 why 前缀（不误吃起居注/别的账的 ACTION 行）。 */
    const val WHY = "752 记忆修剪"

    const val FILE_NAME = "记忆修剪.jsonl"

    /** 落点：`<工作区>/产物/记忆修剪.jsonl`（与 678 重放／676 回响同区，用户可见）。 */
    fun fileOf(workspace: File): File = File(File(workspace, BaopuKernel.Data.DIR_PRODUCTS), FILE_NAME)

    /** 追加一行（失败返回 false 不抛——账没落上要说，不许静默）。 */
    fun append(file: File, event: DiaryEvent): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.appendText(DiaryCodec.encode(event) + "\n")
        true
    }.getOrDefault(false)

    /** 读回本账（坏行跳过；只认自己的 why 前缀）。 */
    fun read(file: File): List<DiaryEvent> = runCatching {
        if (!file.isFile) emptyList()
        else file.readLines().mapNotNull(DiaryCodec::parse).filter { it.why.startsWith(WHY) }
    }.getOrDefault(emptyList())

    /**
     * 本轮总账行（判据⑥：「合并 N / 退役 M / 跳过 K」——N 是组数，吸收条数一并带上；
     * 跳过明细原样进 result，读账的人不用猜 K 是什么构成的）。
     */
    fun summaryEvent(atMs: Long, roundId: String, note: String, skipDetail: String) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$WHY·本轮·$roundId",
        what = note,
        result = skipDetail.ifBlank { "无跳过" },
    )

    /** 合并一行（判据①的账面：全部指针逐条列在 result——顺 id 都能回原文）。 */
    fun mergedEvent(atMs: Long, survivorId: String, absorbedIds: List<String>, allIds: List<String>) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$WHY·合并·$survivorId",
        what = "合并 ${absorbedIds.size + 1} 条为 1 条（主体 $survivorId；被吸收 ${absorbedIds.joinToString(",")}）",
        result = "指针全集：${allIds.joinToString(",")}（merged_from 全保留，原件只标退役未删）",
    )

    /** 退役一行（判据②的账面：标 retired、未物理删）。 */
    fun retiredEvent(atMs: Long, id: String) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$WHY·退役·$id",
        what = "退役一条（标 retired：默认召回不再带它，include-retired 仍可检索）",
        result = "id=$id；文件原地未动、未物理删",
    )

    /** 应用失败一行（update 落盘没成——如实记账，不静默）。 */
    fun failedEvent(atMs: Long, id: String, what: String) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$WHY·失败·$id",
        what = what,
        result = "该条未动（文件原样）",
    )

    /** 整轮中止一行（回滚档没写上/预读原像失败——本轮一条未动，如实记账）。 */
    fun abortEvent(atMs: Long, roundId: String, why: String) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$WHY·中止·$roundId",
        what = "本轮修剪中止，一条未动",
        result = why,
    )

    /** 撤销一行（判据④的账面留痕；账只增不删）。 */
    fun undoneEvent(atMs: Long, roundId: String, restored: Int, failed: Int) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$WHY·撤销·$roundId",
        what = "一键撤销本轮修剪（原像逐条写回，索引随文件重建）",
        result = "恢复 $restored 条${if (failed == 0) "" else "；失败 $failed 条（文件不在，未虚构）"}",
    )
}

/**
 * 回滚档（判据④的地基；**写前原像**）。
 *
 * 一轮一档：`<rollbackRoot>/<roundId>.json`——数组，每项
 * `{id, path, before}`：id 条目在**动它之前**的文件全文。撤销＝逐条把 `before`
 * 写回（字节级还原）；`path` 只作核对（真正落点由撤销时按 id 重新定位——条目
 * 在两棵记忆树里挪过位置也能找回）。App 私有、不进工作区、模型只读不到。
 */
internal object PruneRollback {

    /** 回滚档根（`filesDir/抱朴记忆/修剪回滚/`；挂在全局记忆根旁边，与备份同侧）。 */
    fun dirOf(globalMemoryRoot: File): File = File(globalMemoryRoot, "修剪回滚")

    /** 一轮的回滚档文件。 */
    fun fileOf(root: File, roundId: String): File = File(root, "$roundId.json")

    /** 一条原像（[before] 是**动它之前**的文件全文，字节级）。 */
    data class Item(val id: String, val path: String, val before: String)

    /**
     * 写前原像落档（**必须在任何状态变更之前调**；写失败返回 false——调用方整轮
     * 中止，一条都不许动：半份回滚档比没有更坏，会被误当成能回滚的）。
     */
    fun write(file: File, items: List<Item>): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        items.forEach { i ->
            arr.put(JSONObject().put("id", i.id).put("path", i.path).put("before", i.before))
        }
        file.writeText(JSONObject().put("v", 1).put("items", arr).toString(), Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 读回原像（坏档/不存在 ⇒ null——撤销路径必须如实说「回不了」，不许装成功）。 */
    fun read(file: File): List<Item>? = runCatching {
        if (!file.isFile) return null
        val obj = JSONObject(file.readText(Charsets.UTF_8))
        val arr = obj.optJSONArray("items") ?: return null
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val before = o.optString("before")
            if (id.isBlank() || before.isEmpty()) return@mapNotNull null
            Item(id = id, path = o.optString("path"), before = before)
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/**
 * 修剪的状态文件（`<工作区>/产物/记忆修剪-状态.json`，一行 JSON）。
 *
 * 四格：`lastRunDayKey`（天闸的 last-run 桶）＋ `prev`（修剪前的桶——撤销时换回去）
 * ＋ `lastRoundId`/`rollback`（最近一轮的 id 与回滚档名——「一键撤销」按它找原像）
 * ＋ `lastMerged`/`lastRetired`/`lastSkipped`（判据⑥读数——界面读数行直接用）。
 * 文件本身可覆盖（不是账、不是 ACTION 行，不掺进行数对账——678 同款裁定）。
 */
internal object PruneDayGate {

    const val FILE_NAME = "记忆修剪-状态.json"

    /** 「没跑过」的桶（读不到/负值都算）。 */
    const val NEVER = -1L

    /** 界面读数行用的状态快照（[lastState] 的返回；null ＝ 从没跑过）。 */
    data class State(
        val lastRunDayKey: Long,
        val prevDayKey: Long,
        val roundId: String,
        val rollbackName: String,
        val merged: Int,
        val retired: Int,
        val skipped: Int,
        val note: String,
        val skipDetail: String,
        val atMs: Long,
    ) {
        /** 有没有可撤销的一轮（有 roundId 才有得撤）。 */
        val undoable: Boolean get() = roundId.isNotBlank()
    }

    fun fileOf(workspace: File): File = File(File(workspace, BaopuKernel.Data.DIR_PRODUCTS), FILE_NAME)

    /** 读 last-run 桶（坏文件/不存在 ⇒ [NEVER]＝从没跑过——重跑一轮比丢一轮便宜）。 */
    fun lastRunDayKey(workspace: File): Long = runCatching {
        val f = fileOf(workspace)
        if (!f.isFile) return NEVER
        JSONObject(f.readText(Charsets.UTF_8)).optLong("lastRunDayKey", NEVER)
    }.getOrDefault(NEVER)

    /** 读整份状态（坏文件/不存在 ⇒ null）。 */
    fun lastState(workspace: File): State? = runCatching {
        val f = fileOf(workspace)
        if (!f.isFile) return null
        val o = JSONObject(f.readText(Charsets.UTF_8))
        State(
            lastRunDayKey = o.optLong("lastRunDayKey", NEVER),
            prevDayKey = o.optLong("prev", NEVER),
            roundId = o.optString("lastRoundId"),
            rollbackName = o.optString("rollback"),
            merged = o.optInt("lastMerged", 0),
            retired = o.optInt("lastRetired", 0),
            skipped = o.optInt("lastSkipped", 0),
            note = o.optString("lastNote"),
            skipDetail = o.optString("lastSkipDetail"),
            atMs = o.optLong("at", 0L),
        )
    }.getOrNull()

    /** 一轮真跑后写（唯一写口；prev 记下修剪前的桶供撤销；counts 记**实际应用**的读数）。 */
    fun writeAfterRound(
        workspace: File,
        dayKey: Long,
        prev: Long,
        roundId: String,
        rollbackName: String,
        merged: Int,
        retired: Int,
        skipped: Int,
        note: String,
        skipDetail: String,
        nowMs: Long,
    ) {
        runCatching {
            val f = fileOf(workspace)
            f.parentFile?.mkdirs()
            f.writeText(
                JSONObject()
                    .put("v", 1)
                    .put("lastRunDayKey", dayKey)
                    .put("prev", prev)
                    .put("lastRoundId", roundId)
                    .put("rollback", rollbackName)
                    .put("lastMerged", merged)
                    .put("lastRetired", retired)
                    .put("lastSkipped", skipped)
                    .put("lastNote", note)
                    .put("lastSkipDetail", skipDetail)
                    .put("at", nowMs)
                    .toString(),
                Charsets.UTF_8,
            )
        }
    }

    /**
     * 撤销：桶换回修剪前的值、本轮标记清空（判据④「状态回到修剪前」）。prev 记 [NEVER]
     * （防连环撤销把桶越换越旧——撤销只针对最近一轮）。文件写不进返回 false（要说）。
     */
    fun restoreToPrev(workspace: File, nowMs: Long): Boolean {
        val prev = runCatching {
            JSONObject(fileOf(workspace).readText(Charsets.UTF_8)).optLong("prev", NEVER)
        }.getOrDefault(NEVER)
        return runCatching {
            val f = fileOf(workspace)
            f.parentFile?.mkdirs()
            f.writeText(
                JSONObject()
                    .put("v", 1)
                    .put("lastRunDayKey", prev)
                    .put("prev", NEVER)
                    .put("lastRoundId", "")
                    .put("rollback", "")
                    .put("lastMerged", 0)
                    .put("lastRetired", 0)
                    .put("lastSkipped", 0)
                    .put("lastNote", "")
                    .put("lastSkipDetail", "")
                    .put("at", nowMs)
                    .toString(),
                Charsets.UTF_8,
            )
            true
        }.getOrDefault(false)
    }
}

/**
 * 界面读数投影（判据⑥读数送达 ＋ 判据④「一键」的入口旗）：
 * 从状态文件抽出界面要的三样——总账一句、跳过明细、有没有可撤销的一轮。
 * 纯函数（[State] 进、投影出），JVM 直测。
 */
internal data class PruneReadout(
    val roundId: String,
    val note: String,
    val skipDetail: String,
    val atMs: Long,
)

/** [PruneDayGate.State] ⇒ 读数投影；没有可展示的一轮（没跑过/已撤销）⇒ null。 */
internal fun pruneReadoutOf(state: PruneDayGate.State?): PruneReadout? {
    if (state == null || !state.undoable) return null
    return PruneReadout(
        roundId = state.roundId,
        note = state.note.ifBlank { "合并 ${state.merged} / 退役 ${state.retired} / 跳过 ${state.skipped}" },
        skipDetail = state.skipDetail,
        atMs = state.atMs,
    )
}
