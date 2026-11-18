// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十八批 · 海马重放的**行槽／账／状态**三件（判据一行不落在这里）。
//
// 三条纪律（照 676 SubconsciousWiring／677 InspirationLedger 的房型）：
// 1. **自身产出只写自己的三样**：进程级行槽（建议页签）＋ 本账
//    `<工作区>/产物/海马重放.jsonl`（起居注**同形** ACTION 行，635 房型）＋
//    一行状态文件（天闸的 last-run 桶）。记忆层（MemoryStore/项目记忆/日记）一个写口
//    都没有——判据③「不自动合并、不改记忆正文」由源码门禁钉死（本包零
//    `.update(`/`.delete(`/`MemoryEntry(` 构造）。
// 2. **账只增不删**（504/635 口径）：撤销也是一行 ACTION，不擦历史——
//    「无残留」指的是**建议区与状态**（判据④的原话），不是审计账。
// 3. **状态可回滚**：撤销 ＝ 把 last-run 桶换回重放前的那个值（prev 格），
//    桶读回「没跑过」就是重放前的语义——天闸重新放行，等同回到重放前。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.safety.DiaryCodec
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import java.io.File
import org.json.JSONObject

/**
 * 「建议」页签重放类行的进程级持有者（房型同 635 ClaimEvidenceRowStore／676/677 行槽）。
 *
 * [publishForRound] 一轮一 publish（顺带记下重放前的行快照——撤销时原样放回）；
 * [undoPublish] 只在撤销那一条路上被调（恢复快照），它不是新的浮出入口。
 */
internal object ReplayRowStore {

    @Volatile
    private var latest: List<ReplayRow> = emptyList()

    /** 重放前的行快照（撤销恢复用；进程内有效——进程重启后行槽本来就空了，见交付报告）。 */
    @Volatile
    private var preRoundRows: List<ReplayRow> = emptyList()

    /** 一轮算完才调；页签读这一份（只读）。快照 = publish 之前的行。 */
    fun publishForRound(rows: List<ReplayRow>) {
        synchronized(this) {
            preRoundRows = latest
            latest = rows.toList()
        }
    }

    fun latest(): List<ReplayRow> = latest

    /** 撤销：行槽放回重放前的快照（判据④「建议区回到重放前」）。 */
    fun undoPublish(): List<ReplayRow> = synchronized(this) {
        latest = preRoundRows
        preRoundRows = emptyList()
        latest
    }

    /** 用户逐条收起（卡上「知道了」；不影响撤销快照——撤销是整轮的事）。 */
    fun dismiss(id: String): List<ReplayRow> = synchronized(this) {
        latest = latest.filterNot { it.id == id }
        latest
    }

    /** 测试专用：谁污染谁还原。 */
    fun resetForTest() {
        synchronized(this) {
            latest = emptyList()
            preRoundRows = emptyList()
        }
    }
}

/** 重放账（自身产出；与被读的 L2 记忆、起居注互不改写）。 */
internal object ReplayLedger {

    /** 认领自己行的 why 前缀（不误吃起居注/别的账的 ACTION 行）。 */
    const val WHY = "678 海马重放"

    const val FILE_NAME = "海马重放.jsonl"

    /** 落点：`<工作区>/产物/海马重放.jsonl`（与 625 回响／534 灵感闪回同区，用户可见）。 */
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

    /** 浮出一行（一轮浮 N 条 ⇒ N 行——「行数与该次产物一致」靠这个一一对应）。 */
    fun floatedEvent(atMs: Long, rowId: String, l0: String, title: String) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$WHY·浮出·$rowId",
        what = title,
        result = "L0 $l0",
    )

    /** 撤销一行（判据④的账面留痕；账只增不删）。 */
    fun undoneEvent(atMs: Long, roundId: String, rows: Int) = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$WHY·撤销·$roundId",
        what = "一键撤销本轮重放（$rows 行回快照、状态回重放前）",
        result = "记忆层零改动（只写建议区，判据③）",
    )
}

/**
 * 重放的状态文件（`<工作区>/产物/海马重放-状态.json`，一行 JSON）。
 *
 * 两格：`lastRunDayKey`（天闸的 last-run 桶）＋ `prev`（重放前的桶——撤销时换回去）。
 * 「没跑过」的桶读数是 -1（676 同款哨兵）；撤销后桶＝重放前的值，语义即「回到重放前」。
 * 文件本身可覆盖（不是账、不是 ACTION 行，不掺进行数对账——676 同款裁定）。
 */
internal object ReplayDayGate {

    const val FILE_NAME = "海马重放-状态.json"

    /** 「没跑过」的桶（读不到/负值都算）。 */
    const val NEVER = -1L

    fun fileOf(workspace: File): File = File(File(workspace, BaopuKernel.Data.DIR_PRODUCTS), FILE_NAME)

    /** 读 last-run 桶（坏文件/不存在 ⇒ [NEVER]＝从没跑过——重跑一轮比丢一轮便宜）。 */
    fun lastRunDayKey(workspace: File): Long = runCatching {
        val f = fileOf(workspace)
        if (!f.isFile) return NEVER
        JSONObject(f.readText(Charsets.UTF_8)).optLong("lastRunDayKey", NEVER)
    }.getOrDefault(NEVER)

    /** 一轮真跑后写（唯一写口；prev 记下重放前的桶供撤销）。失败只记日志不抛。 */
    fun writeAfterRound(workspace: File, dayKey: Long, prev: Long, nowMs: Long, roundId: String) {
        runCatching {
            val f = fileOf(workspace)
            f.parentFile?.mkdirs()
            f.writeText(
                JSONObject()
                    .put("v", 1)
                    .put("lastRunDayKey", dayKey)
                    .put("prev", prev)
                    .put("at", nowMs)
                    .put("round", roundId)
                    .toString(),
                Charsets.UTF_8,
            )
        }
    }

    /**
     * 撤销：桶换回重放前的值（判据④「状态回到重放前」）。prev 记 [NEVER]（防连环撤销
     * 把桶越换越旧——撤销只针对最近一轮）。文件写不进返回 false（要说，不静默）。
     */
    fun restoreToPrev(workspace: File, nowMs: Long, roundId: String): Boolean {
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
                    .put("at", nowMs)
                    .put("round", roundId)
                    .toString(),
                Charsets.UTF_8,
            )
            true
        }.getOrDefault(false)
    }
}
