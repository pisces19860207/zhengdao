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
// 3. **一轮只修一类**：本文件每个入口各只对应一个 [RepairKind]（447 加路后入口变多，
//    仍然是一个入口一类、一桩 intent→result），没有"传一组类目全修一遍"的口（铁律⑤）。
//
// 一处刻意的分工（419 修订）：**残留清点**（`sweepResidue`/`repairResidue`）已由 419 接进
// 体检收尾（`HealthRun.run` 之后一行，[afterHealthRun]）。
//
// 第四百四十七批（2026-11-05 用户批准）：408 欠着的**索引／账本**两路，加上**配置漂移**
// 新一路，一并接进同一个入口（[afterHealthRun] 一行），本文件仍不新写任何索引逻辑：
// 1. 索引路读的是 `KnowledgeIndexStore.loadRead` ＋ `loadSnapshotRead` 两条**既有**读口，
//    修的动作还是 408 那一句 `build(...)` ＋ `save(...)`；
// 2. 账本路一字未改（只补了一条"旧行字节不变＋新增一行"的断言）；
// 3. 配置路只吃 [ConfigAccess] 这个口（生产实现＝本包 [MaintPauseConfig]，它对读口的
//    转发与写口都只引用属主模块的常量），本文件里没有 SP 文件名、没有键名字面量、
//    没有"默认值"——真源在判据表 `ConfigCriteria`。
package com.example.zhengdao.baopu.repair

import com.example.zhengdao.baopu.TempScratch
import com.example.zhengdao.baopu.inspect.DataRepair
import com.example.zhengdao.baopu.inspect.DataRepair.ConfigCriteria
import com.example.zhengdao.baopu.inspect.DataRepair.ConfigItem
import com.example.zhengdao.baopu.inspect.DataRepair.SweepWhitelist
import com.example.zhengdao.baopu.inspect.HealthLedger
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

/**
 * 配置项的**唯一出入口气**（447 §三；本文件与 [DataDoctor] 都不碰 SharedPreferences）。
 *
 * 谁实现它：这个配置域的**适配器**（生产＝本包 [MaintPauseConfig]，测试＝假表）。
 * 为什么适配器在 `repair/` 而不在 `inspect/`：写 SP 是写盘，而 `InspectReadOnlyGateTest`
 * 把勘验层整层钉成"只查不改"（负例①那句"唯一允许落盘的是它自己那几本 JSONL 的追加"）。
 * 适配器仍然**不自己认键**：读走 [com.example.zhengdao.baopu.maintenance.MaintPause] 那个读口,
 * 文件名、键名与缺省值都住在属主模块——御医再抄一份就是同一个配置两套读法（那正是"漂移"的出生地）。
 *
 * 三态口径（与判据层一致，不许拿 null 冒充"没这个键"以外的任何意思）：
 * - [read] 回 null ＝ **这趟读不出**（未取样）⇒ 判据层落成"需人处理"，绝不猜；
 * - [read] 回字符串 ＝ 这一项**现在生效**的值（没落盘但有缺省也算"生效值"，不算读不出）；
 * - [observed] ＝ 这个配置域里看到的全部项（表外的也要给，判据层才有东西可"只报不修"）。
 */
internal interface ConfigAccess {

    fun read(key: String): String?

    fun observed(): List<ConfigItem>

    /** 写回一个值；**只有判据表里的键会走到这里**（表外的在判据层就被拦成需人处理）。 */
    fun write(key: String, value: String): Boolean
}

internal object DataDoctor {

    /** 回收站目录名（与 `TempScratch.reap` 同一个落点；那边是字面量，这里跟着）。 */
    const val TRASH_DIR = "回收站"

    /** 一本账最多扫多少行（够判"有没有坏行"就停，不做全库导出）。 */
    const val LEDGER_SCAN_LIMIT = 20_000

    /** `intent` 落不上时的固定说法（用例按它断言"不许动手"）。 */
    const val INTENT_NOT_WRITTEN = "intent 落不上账 ⇒ 不许动手（铁律②：先记后修）"

    // ── 体检发现（只读） ─────────────────────────────────────────────────────

    /**
     * ① 索引↔清单：读**两条既有读口** → 成对计划（447 §一①）。
     *
     * 本文件不新写任何索引逻辑：`loadRead`／`loadSnapshotRead` 是 259-08 交付的三态读数，
     * `save` 是唯一的成对写口（先索引、索引写成才写快照——"只剩一条"就是这么来的），
     * 这里的动作仍然是 408 那一句 `build(...) ＋ save(...)`。
     */
    fun diagnoseIndex(workspace: File, nowMs: Long): RepairPlan = DataRepair.indexPairPlan(
        index = readingOf(KnowledgeIndexStore.loadRead(workspace)) { it.docs.size },
        manifest = readingOf(KnowledgeIndexStore.loadSnapshotRead(workspace)) { it.entries.size },
        indexTarget = relativePath(workspace, KnowledgeLayout.indexFile(workspace)),
        manifestTarget = relativePath(workspace, KnowledgeLayout.snapshotFile(workspace)),
        evidence = evidence(RepairKind.INDEX, nowMs),
    )

    /** `FileRead` 的三态翻成 [IndexReading]（判据层不认实现，只认「没有／坏了／读得动」）。 */
    private fun <T> readingOf(read: KnowledgeIndexStore.FileRead<T>, docs: (T) -> Int): IndexReading =
        when (read) {
            is KnowledgeIndexStore.FileRead.Missing -> IndexReading.Missing
            is KnowledgeIndexStore.FileRead.Corrupt -> IndexReading.Corrupt(read.why, read.bytes, read.digest8)
            is KnowledgeIndexStore.FileRead.Value -> IndexReading.Ok(docs(read.value))
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

    /**
     * ④ 配置：按判据表逐行取值 → 计划（447 §三；真源只有 [ConfigCriteria] 这一张表）。
     *
     * 三样东西分开喂，判据层才分得清"对位／漂移／没读出来"：
     * - 表里每一行的**生效值**（[ConfigAccess.read] 回 null ⇒ 这行按未取样，落成需人处理）；
     * - 这个配置域里**表外**的项（[ConfigAccess.observed] 去掉表里的键）⇒ 只报不修；
     * - 表本体可注入（测试递假表），房型照 [diagnoseTemp] 的 `prefixes`——本层不替谁记表。
     */
    fun diagnoseConfig(
        config: ConfigAccess,
        nowMs: Long,
        rows: List<DataRepair.ConfigRow> = ConfigCriteria.ROWS,
    ): RepairPlan {
        val readings = rows.mapNotNull { row -> config.read(row.key)?.let { ConfigItem(row.key, it) } }
        val inRows = rows.map { it.key }.toSet()
        val outside = config.observed().filter { it.key !in inRows }
        return DataRepair.configPlan(readings, outside, rows, evidence(RepairKind.CONFIG, nowMs))
    }

    // ── 419：工作区残留清点（体检收尾后的自修）─────────────────────────────

    /** 一趟清点看见的东西：白名单内的文件（带 mtime）＋ 白名单外的**顶层入口**名字。 */
    data class SweepFinding(val files: List<TempItem>, val outside: List<String>)

    /**
     * 扫工作区（**只读**）：白名单内的文件逐个列出；白名单外的只记顶层入口。
     *
     * 两处刻意的不扫：
     * - [TRASH_DIR] 整棵不进——回收站是终点不是对象，搬 trash 里的东西等于把
     *   "等人发落"的账搬乱；清不清回收站是用户的动作，不是御医的；
     * - 白名单外的目录不逐个列深处的文件——顶层入口一行就够人对账，
     *   把用户家底逐个抄进账是另一种打扰。
     */
    fun sweepResidue(
        workspace: File,
        whitelist: SweepWhitelist = SweepWhitelist.USER_2026_11_04,
    ): SweepFinding {
        if (!workspace.isDirectory) return SweepFinding(emptyList(), emptyList())
        val files = ArrayList<TempItem>()
        val outside = sortedSetOf<String>()
        workspace.walkTopDown()
            .onEnter { it == workspace || it.name != TRASH_DIR }
            .filter { it.isFile }
            .forEach { f ->
                val rel = relativePath(workspace, f)
                if (DataRepair.isSweepWhitelisted(rel, whitelist)) {
                    files += TempItem(relPath = rel, lastModifiedMs = f.lastModified())
                } else {
                    outside += rel.substringBefore('/')
                }
            }
        return SweepFinding(files, outside.toList())
    }

    /** 体检发现（只读）：工作区残留 → 计划（白名单 2026-11-04 用户已定，判据在 [DataRepair]）。 */
    fun diagnoseResidue(
        workspace: File,
        nowMs: Long,
        whitelist: SweepWhitelist = SweepWhitelist.USER_2026_11_04,
    ): RepairPlan {
        val found = sweepResidue(workspace, whitelist)
        return DataRepair.tempSweepPlan(
            found.files,
            found.outside,
            whitelist,
            nowMs,
            evidence(RepairKind.TEMP, nowMs),
        )
    }

    /**
     * 修（先记后修）：把计划内那几个搬进 `回收站/`（同 [repairTemp] 的房型；可恢复）。
     *
     * 主账那一道在判据层就挡死了（[DataRepair.isMainLedgerPath]），这里的目标表
     * 里不可能出现主账；回收站整棵不在扫描范围，也不会被再搬一遍。
     */
    fun repairResidue(
        workspace: File,
        nowMs: Long,
        ledgerRoot: File = workspace,
        whitelist: SweepWhitelist = SweepWhitelist.USER_2026_11_04,
    ): RepairOutcome {
        val plan = diagnoseResidue(workspace, nowMs, whitelist)
        val blocked = blockReason(ledgerRoot, plan)
        if (blocked != null) return RepairOutcome(plan, false, blocked)
        if (!writeIntent(ledgerRoot, plan, nowMs)) return RepairOutcome(plan, false, INTENT_NOT_WRITTEN)

        val moved = moveToTrash(workspace, plan.targets, nowMs)
        val note = "搬进回收站 $moved/${plan.targets.size} 个（可恢复，没真删）"
        return finish(ledgerRoot, plan, nowMs, note, moved == plan.targets.size)
    }

    /**
     * 体检收尾后的那一行（419 开的口，447 把四路接满）：残留 → 索引 → 账本 → 配置，
     * 每路各一趟 [RepairKind]、各两条账（intent→result），压成一行 note 进 RunLog。
     *
     * 三条口径：
     * 1. **一路失败不连累别的路**：每路自带 `runCatching`，抛了只说"这一路没跑成"，
     *    体检已经落账，不许把整趟体检报成失败；
     * 2. **少给输入就说少给**：`filesDir`/`config` 没递 ⇒ 那一路显式写"没跑"，
     *    不静默跳过（静默跳过会被读成"这路跑了、没事"）；
     * 3. **不动手的不留账**：无需修／需人处理都在 [blockReason] 就返回，账上一行都不写
     *    （408 的老规矩：留了就像修过）。
     */
    fun afterHealthRun(
        workspace: File,
        ledgerRoot: File,
        nowMs: Long,
        filesDir: File? = null,
        config: ConfigAccess? = null,
    ): String {
        val notes = ArrayList<String>()
        notes += route("残留") { repairResidue(workspace, nowMs, ledgerRoot) }
        notes += if (filesDir == null) {
            "索引：没给 filesDir ⇒ 这路没跑（重建要用它，不是跳过）"
        } else {
            route("索引") { repairIndex(workspace, filesDir, nowMs, ledgerRoot) }
        }
        notes += route("账本") { repairLedger(ledgerRoot, HealthLedger.FILE_NAME, nowMs) }
        notes += if (config == null) {
            "配置：没给 ConfigAccess ⇒ 这路没跑（没读口就没判据）"
        } else {
            route("配置") { repairConfig(config, ledgerRoot, nowMs) }
        }
        return notes.joinToString("｜")
    }

    /** 一路的跑法（名字进 note，异常也只认领到这一路）。 */
    private fun route(label: String, block: () -> RepairOutcome): String = runCatching {
        val out = block()
        if (out.done) "$label：${out.note}｜判据：${out.plan.reason}" else "$label：${out.note}"
    }.getOrElse { "$label：没跑成——${it.javaClass.simpleName}: ${it.message}" }

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

    /**
     * ④ 配置：把计划内那几项**改回判据表的值**（447 §三；先记后修）。
     *
     * `intent` 行吃的就是判据层那句「哪项 原值=… → 判据表值=…（依据：…）」——
     * 动手前账上已经写清了"改哪项、从几改到几、依据哪一行"，撤回时照着这行就能改回去。
     *
     * 两道不猜：`plan.targets` 里出现表外键（不该发生，判据层就拦了）就**跳过不动**；
     * [ConfigAccess.write] 回 false ＝ 没改成，落成 `failed` 留痕，不冒充"修好了"。
     */
    fun repairConfig(
        config: ConfigAccess,
        ledgerRoot: File,
        nowMs: Long,
        rows: List<DataRepair.ConfigRow> = ConfigCriteria.ROWS,
    ): RepairOutcome {
        val plan = diagnoseConfig(config, nowMs, rows)
        val blocked = blockReason(ledgerRoot, plan)
        if (blocked != null) return RepairOutcome(plan, false, blocked)
        if (!writeIntent(ledgerRoot, plan, nowMs)) return RepairOutcome(plan, false, INTENT_NOT_WRITTEN)

        val expected = rows.associate { it.key to it.expected }
        var written = 0
        for (key in plan.targets) {
            val to = expected[key] ?: continue
            if (config.write(key, to)) written++
        }
        val note = "改回判据表 $written/${plan.targets.size} 项（只动表里有的键）"
        return finish(ledgerRoot, plan, nowMs, note, written == plan.targets.size)
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
