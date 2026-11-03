// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零八批 · 御医可修数据的**执行层**：把判据层的计划落成动作。
//
// 为什么在 `repair/` 而不在 `inspect/`：体检层被 `InspectReadOnlyGateTest` 从源码层钉死
// "只读"（全层禁 `writeText`／`renameTo`／`mkdirs`／`execSQL`）。修复天生要写盘，
// 放进那个包就是让门禁红。分层是**门禁逼出来的正确形状**，不是躲：
// **判据在 `inspect/DataRepair.kt`（零 IO、可 JVM 直测），执行与账在这里（可写）**。
//
// 三条纪律：
// 1. **只调既有接口**：索引走 `KnowledgeIndexStore`（三态读数／`build`／`save`），
//    临时走 `TempScratch.leftover` 的列目录口 ＋ 同款「搬进 `回收站/<stamp>-<名>`」房型；
//    本文件**不新写**任何解析器、不碰 `execSQL`、不改既有源码一行（铁律①）。
// 2. **先记后修**：动手前先把 `intent` 落进 [RepairLedger]；**落不上就不动手**（铁律②）。
//    修完（成功或失败）都落 `result`——失败也要留痕，否则"没修"与"修了没成"分不清。
// 3. **一轮只修一类**：本文件三个入口各只对应一个 [RepairKind]，
//    没有"传一组类目全修一遍"的口（铁律⑤）。
//
// 一处刻意的不做：**本批不接线**。接线要改既有文件（体检编排或界面），与铁律①
// "不改源码"冲突；接法是下一单的一行（`HealthRun.run` 之后调 `DataDoctor.repairXxx`）。
package com.example.zhengdao.baopu.repair

import com.example.zhengdao.baopu.TempScratch
import com.example.zhengdao.baopu.inspect.DataRepair
import com.example.zhengdao.baopu.inspect.IndexReading
import com.example.zhengdao.baopu.inspect.RepairKind
import com.example.zhengdao.baopu.inspect.RepairPlan
import com.example.zhengdao.baopu.inspect.RepairVerdict
import com.example.zhengdao.baopu.inspect.TempItem
import com.example.zhengdao.baopu.knowledge.KnowledgeIndexStore
import com.example.zhengdao.baopu.knowledge.KnowledgeLayout
import com.example.zhengdao.core.TimeFormats
import java.io.File
import org.json.JSONObject

/** 一次修复的结果（[plan] 是判据层给的，[note] 是人话，[done] 是真动过手且成了）。 */
internal data class RepairOutcome(
    val plan: RepairPlan,
    val done: Boolean,
    val note: String,
)

internal object DataDoctor {

    /** 回收站目录名（与 `TempScratch.reap` 同一个落点；那边是字面量，这里跟着）。 */
    const val TRASH_DIR = "回收站"

    /** 一本账最多扫多少行（够判"有没有坏行"就停，不做全库导出）。 */
    const val LEDGER_SCAN_LIMIT = 20_000

    /** `intent` 落不上时的固定说法（用例按它断言"不许动手"）。 */
    const val INTENT_NOT_WRITTEN = "intent 落不上账 ⇒ 不许动手（铁律②：先记后修）"

    // ── 体检发现（只读） ─────────────────────────────────────────────────────

    /** ① 索引：读三态 → 计划（`FileRead` 的三态翻成 [IndexReading]，判据层不认实现）。 */
    fun diagnoseIndex(workspace: File, nowMs: Long): RepairPlan {
        val target = relativePath(workspace, KnowledgeLayout.indexFile(workspace))
        val read = KnowledgeIndexStore.loadRead(workspace)
        val reading = when (read) {
            is KnowledgeIndexStore.FileRead.Missing -> IndexReading.Missing
            is KnowledgeIndexStore.FileRead.Corrupt -> IndexReading.Corrupt(read.why, read.bytes, read.digest8)
            else -> IndexReading.Ok(read.valueOrNull()?.docs?.size ?: 0)
        }
        return DataRepair.indexPlan(reading, target, evidence(RepairKind.INDEX, nowMs))
    }

    /** ② 账本：数坏行 → 计划（读不动 ⇒ 需人处理，**不许拿 0 顶替**）。 */
    fun diagnoseLedger(root: File, ledgerName: String, nowMs: Long): RepairPlan {
        val ev = evidence(RepairKind.LEDGER, nowMs)
        val scan = scanLedger(File(root, ledgerName))
            ?: return RepairPlan(
                kind = RepairKind.LEDGER,
                verdict = RepairVerdict.NEED_HUMAN,
                reason = "账本 $ledgerName 读不动（未取样），不猜它坏没坏",
                evidence = ev,
            )
        val recorded = RepairLedger.recordedTargets(root, RepairKind.LEDGER.wire).contains(ledgerName)
        return DataRepair.ledgerPlan(ledgerName, scan.first, scan.second, recorded, ev)
    }

    /** ③ 临时：列 `临时/` → 计划（前缀由调用方给，判据层不替它定）。 */
    fun diagnoseTemp(workspace: File, prefixes: Set<String>, nowMs: Long): RepairPlan {
        val items = TempScratch.leftover(workspace).map {
            TempItem(relPath = relativePath(workspace, it), lastModifiedMs = it.lastModified())
        }
        return DataRepair.tempPlan(items, prefixes, nowMs, evidence(RepairKind.TEMP, nowMs))
    }

    // ── 修（先记后修） ───────────────────────────────────────────────────────

    /**
     * ① 重建索引：`build(rebuild)` ＋ `save`（索引是缓存，删了能重建 ⇒ 天然可回滚）。
     *
     * @param ledgerRoot 修复账落在哪（生产传 `DecisionLedger.installedRoot()`；本批测试传临时目录）
     */
    fun repairIndex(workspace: File, filesDir: File, nowMs: Long, ledgerRoot: File = workspace): RepairOutcome {
        val plan = diagnoseIndex(workspace, nowMs)
        val blocked = blockReason(ledgerRoot, plan)
        if (blocked != null) return RepairOutcome(plan, false, blocked)
        if (!writeIntent(ledgerRoot, plan, nowMs)) return RepairOutcome(plan, false, INTENT_NOT_WRITTEN)

        val result = runCatching {
            val built = KnowledgeIndexStore.build(
                workspace = workspace,
                filesDir = filesDir,
                previousIndex = null,
                previousSnapshot = null,
                now = nowMs,
            )
            if (built.cancelled) return@runCatching false to "建索引被取消（没保存）"
            val index = built.index ?: return@runCatching false to "建不出索引：${built.report}"
            val saved = KnowledgeIndexStore.save(workspace, index, built.snapshot)
            saved to (if (saved) "索引已重建并落盘" else "索引重建了但落盘失败")
        }.getOrElse { false to "重建抛异常：${it.javaClass.simpleName}" }
        return finish(ledgerRoot, plan, nowMs, result.second, result.first)
    }

    /**
     * ② 账本：**只补一条"损坏记录"行**（落在修复账里，指向那本账）。
     *
     * 为什么不往那本账里写：写进去就是一行它自己的解析器认不出的行 ⇒ 下次体检
     * 把这条"记录"又算成损坏，越修越多（自毒）。所以损坏记录落在**修复账**上，
     * 坏行一个字节都不动（铁律③：删行不可回滚）。
     */
    fun repairLedger(root: File, ledgerName: String, nowMs: Long): RepairOutcome {
        val plan = diagnoseLedger(root, ledgerName, nowMs)
        val blocked = blockReason(root, plan)
        if (blocked != null) return RepairOutcome(plan, false, blocked)
        if (!writeIntent(root, plan, nowMs)) return RepairOutcome(plan, false, INTENT_NOT_WRITTEN)

        val scan = scanLedger(File(root, ledgerName))
        val note = if (scan == null) {
            "扫描失败，损坏行数没拿到"
        } else {
            "已记损坏行 ${scan.first}（共 ${scan.second} 行，原行一字未动）"
        }
        return finish(root, plan, nowMs, note, scan != null)
    }

    /**
     * ③ 临时：只把计划内那几个搬进 `回收站/`（同 `TempScratch.reap` 的命名；可恢复）。
     *
     * 计划外的**一个都不碰**——不递归清目录、不删空目录（清空目录是 `reap` 的活，
     * 本批只做"带前缀且超 7 天"这一档）。
     */
    fun repairTemp(workspace: File, prefixes: Set<String>, nowMs: Long, ledgerRoot: File = workspace): RepairOutcome {
        val plan = diagnoseTemp(workspace, prefixes, nowMs)
        val blocked = blockReason(ledgerRoot, plan)
        if (blocked != null) return RepairOutcome(plan, false, blocked)
        if (!writeIntent(ledgerRoot, plan, nowMs)) return RepairOutcome(plan, false, INTENT_NOT_WRITTEN)

        val moved = moveToTrash(workspace, plan.targets, nowMs)
        val note = "搬进回收站 $moved/${plan.targets.size} 个（可恢复，没真删）"
        return finish(ledgerRoot, plan, nowMs, note, moved == plan.targets.size)
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 体检依据（"凭什么修"）：带上类目与时刻，账行里能反查到是哪一趟体检说的。 */
    private fun evidence(kind: RepairKind, nowMs: Long): String = "diagnose:$nowMs:${kind.wire}"

    /** 不许动手的三种情形（无需修／需人处理／上次修到一半）；null ＝ 放行。 */
    private fun blockReason(ledgerRoot: File, plan: RepairPlan): String? {
        if (plan.verdict == RepairVerdict.NOTHING) return plan.reason
        if (plan.verdict == RepairVerdict.NEED_HUMAN) return "需人处理：${plan.reason}"
        val pending = RepairLedger.unfinishedIntent(ledgerRoot)
        if (pending != null) {
            return "上次修复只落了 intent（${pending.id}）没落 result，先查那一趟做到哪（需人处理）"
        }
        return null
    }

    private fun writeIntent(root: File, plan: RepairPlan, nowMs: Long): Boolean = RepairLedger.write(
        root,
        RepairEntry(
            id = entryId(plan, nowMs),
            atMs = nowMs,
            kind = plan.kind.wire,
            phase = RepairLedger.PHASE_INTENT,
            target = plan.targets.joinToString("、"),
            reason = plan.reason,
            evidence = plan.evidence,
        ),
    )

    private fun finish(root: File, plan: RepairPlan, nowMs: Long, note: String, ok: Boolean): RepairOutcome {
        val written = RepairLedger.write(
            root,
            RepairEntry(
                id = entryId(plan, nowMs),
                atMs = nowMs,
                kind = plan.kind.wire,
                phase = RepairLedger.PHASE_RESULT,
                target = plan.targets.joinToString("、"),
                reason = plan.reason,
                evidence = plan.evidence,
                outcome = if (ok) RepairLedger.OUTCOME_DONE else RepairLedger.OUTCOME_FAILED,
            ),
        )
        return RepairOutcome(plan, ok, if (written) note else "$note（结果没落上账）")
    }

    private fun entryId(plan: RepairPlan, nowMs: Long): String = "repair:${plan.kind.wire}:$nowMs"

    /**
     * 数一本 JSONL 账的（坏行, 总行）——坏行判据只有一条：**这一行解不出 JSON**。
     *
     * 三态：文件不在 ⇒ (0,0)（真的一行没有）；读不动 ⇒ null（未取样）；否则计数。
     * 空白行不算坏行（`appendText` 的尾换行会造成一行空串）。
     */
    private fun scanLedger(f: File): Pair<Int, Int>? {
        if (!f.isFile) return 0 to 0
        return runCatching {
            var bad = 0
            var total = 0
            f.bufferedReader(Charsets.UTF_8).use { r ->
                while (total < LEDGER_SCAN_LIMIT) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    total++
                    if (runCatching { JSONObject(line) }.isFailure) bad++
                }
            }
            bad to total
        }.getOrNull()
    }

    /** 把计划内的文件搬进 `回收站/<stamp>-<名>`（重名追加 `-2`、`-3`…；只搬文件）。 */
    private fun moveToTrash(workspace: File, targets: List<String>, nowMs: Long): Int {
        val trash = File(workspace, TRASH_DIR)
        if (targets.isEmpty()) return 0
        if (!trash.isDirectory && !trash.mkdirs()) return 0
        val stamp = TimeFormats.fileStamp(nowMs)
        var moved = 0
        for (rel in targets) {
            val src = File(workspace, rel)
            if (!src.isFile) continue
            var target = File(trash, "$stamp-${src.name}")
            var n = 2
            while (target.exists()) {
                target = File(trash, "$stamp-$n-${src.name}")
                n++
            }
            if (src.renameTo(target)) moved++
        }
        return moved
    }

    /** 相对工作区、以 `/` 分隔（账行与计划里的目标一律这个形状，跨平台才对得上）。 */
    private fun relativePath(workspace: File, file: File): String =
        runCatching { file.relativeTo(workspace).path.replace('\\', '/') }
            .getOrElse { file.name }
}
