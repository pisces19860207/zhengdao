// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零八批 · 御医可修数据的**判据层**：只回答"这一类该不该修、凭什么"，
// 一个字节都不动（本文件零 IO、零时钟、零 Android、零随机——房型照 `HealthCheck`）。
//
// 四条纪律（派工单 408 的五条铁律落到这里的部分）：
// 1. **一轮只修一类**：类目是闭集 [RepairKind]，一次请求只许出现一个；
//    出现第二个就整单拒（[batchGuard]），**一个字节都不动**。
// 2. **不许修判定类**：修不了就 [RepairVerdict.NEED_HUMAN]，不许硬修、不许替它造数。
//    "没有判据出处"本身就是需人处理——例如清理前缀，本层**不替它定**（前缀由调用方传）。
// 3. **只修数据**：四类各一条路（①索引/清单 ②账本 ③缓存/临时 ④配置漂移）；
//    判定类（谁该弹卡、三态怎么落）**不在类目里**，永远修不了——铁律②。
// 4. **判据要说得出证据**：每个 [RepairPlan] 都带 `evidence`（依据哪一次体检），
//    没有出处的计划不许构造——与 `HealthCheck` 的 L14 同一条口径。
//
// 第四百四十七批修订（2026-11-05 用户批准「这个有用，做」）：**只加路，不改房**。
// - 类④ 配置漂移进闭集（408 那次"先不做"的裁定被这张新单子推翻，见 [RepairKind.CONFIG]）；
// - 类① 从"只认索引读不动"扩到**索引↔清单（快照）这一对**——判据仍是"硬不一致"，
//   没有扩到"篇数对不对得上"（408 拒过的假阳一个都没捡回来）；
// - 配置那一路的**唯一真源是判据表** [ConfigCriteria]：表里没有的键一律只报不修，
//   不许取"默认值"（猜错比不改更坏）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionLedger

/**
 * 可自修的数据类目（**闭集**；`wire` 是账行的稳定键，改字面量＝老账对不上）。
 *
 * 类④（配置漂移）2026-11-03 裁定"先不做"，2026-11-05 用户批准本单（447 §三）做了进来。
 * 除此之外**不再加类别**（派工单边界：类④之外不加新类别）。
 */
internal enum class RepairKind(val wire: String, val cn: String) {
    INDEX("index", "索引重建"),
    LEDGER("ledger", "账本补损坏行"),
    TEMP("temp", "缓存残留清理"),
    CONFIG("config", "配置改回判据表"),
    ;

    companion object {
        fun ofWire(wire: String): RepairKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一条计划的三态（闭集；"无需修"与"修不了"必须分开，否则修不了会被写成没事）。 */
internal enum class RepairVerdict(val wire: String, val cn: String) {

    /** 有证据、有既有接口、可回滚 ⇒ 可以动手。 */
    FIXABLE("fixable", "可自修"),

    /** 账实相符或本来就正常 ⇒ 不动（不是"修好了"）。 */
    NOTHING("nothing", "无需修"),

    /** 修不了／判不了／没有判据出处 ⇒ 只报，不硬修。 */
    NEED_HUMAN("need_human", "需人处理"),
}

/**
 * 一条修复计划（判据层的唯一产物；执行层照它动手，不许自己加戏）。
 *
 * @param reason 给人看的一句话（"读坏了：解不开"／"有 2 行读不懂且没人记过账"）
 * @param targets 要动的东西（相对工作区的路径；空表 ＝ 不动手）
 * @param evidence 依据哪一次体检（`diagnose:<时刻>:<类目>`）——没有出处不许构造
 */
internal data class RepairPlan(
    val kind: RepairKind,
    val verdict: RepairVerdict,
    val reason: String,
    val targets: List<String> = emptyList(),
    val evidence: String,
) {
    val fixable: Boolean get() = verdict == RepairVerdict.FIXABLE
}

/**
 * `临时/` 里的一个候选（**只有名字与龄期**，不带 [java.io.File]——判据层不碰盘）。
 *
 * @param relPath 相对工作区根、以 `/` 分隔（`临时/zd-旧.bin`）
 * @param lastModifiedMs 盘上 mtime（由取数层读好递进来）
 */
internal data class TempItem(val relPath: String, val lastModifiedMs: Long)

/**
 * 索引的三态读数（把 `KnowledgeIndexStore.FileRead` 的三态翻成本层口径；
 * 判据层只认这三种，"没有"与"坏了"不许混）。
 */
internal sealed interface IndexReading {

    /** 从没建过索引（不是坏了，也不是该修）。 */
    object Missing : IndexReading

    /** 文件在但读不出／解不开；[digest8] 可对账、不可逆、不含正文。 */
    data class Corrupt(val why: String, val bytes: Int, val digest8: String) : IndexReading

    /** 读得动（[docs] 篇）。 */
    data class Ok(val docs: Int) : IndexReading
}

/** 判据层：全是纯函数，输入是盘上事实，输出是计划。 */
internal object DataRepair {

    /** 缓存/临时的龄期门槛（派工单：**超 7 天**；正好 7 天不算超）。 */
    const val TEMP_MAX_AGE_DAYS = 7

    const val DAY_MS = 86_400_000L

    // ── ① 索引 ──────────────────────────────────────────────────────────────

    /**
     * 索引该不该重建。
     *
     * 判据（派工单 §1）：**索引/清单不一致 ⇒ 重建索引**。
     * - [IndexReading.Corrupt] ⇒ 可修：索引读不动就是最硬的一种不一致，且索引是
     *   "可整棵删除后重建"的缓存（`KnowledgeLayout` 文件头第 2 条）；
     * - [IndexReading.Missing] ⇒ 无需修：**从没建过不是坏了**（第一次用也会是 Missing，
     *   把它当缺陷就是"把没人派活记成它坏了"）；
     * - [IndexReading.Ok] ⇒ 无需修：读得动就交给人看，本层不去比"清单与盘上篇数"——
     *   `filesIndexed` 与盘上文件数天然不等（过大/读不出/不认领的会被跳过），
     *   硬比必假阳，假阳就会触发不必要的重建（那是数据churn，不是修复）。
     */
    fun indexPlan(reading: IndexReading, target: String, evidence: String): RepairPlan = when (reading) {
        is IndexReading.Corrupt -> RepairPlan(
            kind = RepairKind.INDEX,
            verdict = RepairVerdict.FIXABLE,
            reason = "索引读坏了：${reading.why}（bytes=${reading.bytes}，sha=${reading.digest8}）",
            targets = listOf(target),
            evidence = evidence,
        )
        is IndexReading.Missing -> RepairPlan(
            kind = RepairKind.INDEX,
            verdict = RepairVerdict.NOTHING,
            reason = "没有索引（从没建过，不是坏了）",
            evidence = evidence,
        )
        is IndexReading.Ok -> RepairPlan(
            kind = RepairKind.INDEX,
            verdict = RepairVerdict.NOTHING,
            reason = "索引读得动（${reading.docs} 篇）",
            evidence = evidence,
        )
    }

    /**
     * 索引 ↔ 清单（快照）**这一对**该不该重建（447 §一①：索引/清单不一致 ⇒ 重建索引）。
     *
     * 收的还是"硬不一致"这一种，一条都不往外扩：
     * - `KnowledgeIndexStore.save()` 是**先写索引、索引写成才写快照**（源码里就这一处写口），
     *   所以"只剩一条"必然是半截落盘／手工删过一条 ⇒ 这一对天然成对，缺一条就是不一致；
     * - 清单读坏了同样是不一致：它是下一轮增量 diff 的基准，基准读不出⇒下一轮只会拿
     *   "没有上一版"当基准，索引里的条目与盘上的对不上却没人记账；
     * - **依旧不比篇数**（408 §二那条拒绝原封不动搬过来）：`filesIndexed` 与盘上文件数
     *   天然不等（过大/读不出/不认领的会被跳过），硬比必假阳，假阳就触发无谓重建。
     *
     * 索引自己读坏了那一档**整条交给 [indexPlan]**——措辞、targets、sha 线索都是 408 钉过的，
     * 不在这里重写第二遍（同一件事两处各写一份必漂移）。
     */
    fun indexPairPlan(
        index: IndexReading,
        manifest: IndexReading,
        indexTarget: String,
        manifestTarget: String,
        evidence: String,
    ): RepairPlan {
        if (index is IndexReading.Corrupt) return indexPlan(index, indexTarget, evidence)
        val pair = listOf(indexTarget, manifestTarget)
        fun fix(why: String): RepairPlan =
            RepairPlan(
                kind = RepairKind.INDEX,
                verdict = RepairVerdict.FIXABLE,
                reason = why,
                targets = pair,
                evidence = evidence,
            )
        if (manifest is IndexReading.Corrupt) {
            return fix("清单读坏了：${manifest.why}（bytes=${manifest.bytes}，sha=${manifest.digest8}）")
        }
        val indexMissing = index is IndexReading.Missing
        val manifestMissing = manifest is IndexReading.Missing
        if (indexMissing != manifestMissing) {
            val left = if (indexMissing) manifestTarget else indexTarget
            val gone = if (indexMissing) indexTarget else manifestTarget
            return fix("成对文件只剩一条（$left 在、$gone 不在）")
        }
        if (indexMissing) {
            return RepairPlan(
                kind = RepairKind.INDEX,
                verdict = RepairVerdict.NOTHING,
                reason = "索引与清单都还没建过（从没建过，不是坏了）",
                evidence = evidence,
            )
        }
        val docs = (index as IndexReading.Ok).docs
        return RepairPlan(
            kind = RepairKind.INDEX,
            verdict = RepairVerdict.NOTHING,
            reason = "索引与清单都在、都读得动（索引 $docs 篇）",
            evidence = evidence,
        )
    }

    // ── ② 账本 ──────────────────────────────────────────────────────────────

    /**
     * 账本该不该补一条"损坏记录"行。
     *
     * 判据（派工单 §2）：**账本损坏/缺行 ⇒ 只补一条"损坏记录"行（不删旧行、不改旧行）**。
     * 所以"修"的对象**不是坏行本身**——坏行一个字节都不许动（删了就不可回滚），
     * 修的是"账上有损坏而没人知道"这件事。
     *
     * @param badLines 读不懂的行数（`parse` 回 null 的行；取数层数好递进来）
     * @param totalLines 总行数（`badLines > totalLines` 是不可能的，用来给账行留对账底）
     * @param alreadyRecorded 这本账的损坏**已经记过账**（再记一条就是重复，重复也是噪音）
     */
    fun ledgerPlan(
        ledger: String,
        badLines: Int,
        totalLines: Int,
        alreadyRecorded: Boolean,
        evidence: String,
    ): RepairPlan = when {
        badLines <= 0 -> RepairPlan(
            kind = RepairKind.LEDGER,
            verdict = RepairVerdict.NOTHING,
            reason = "账本 $ledger 没有读不懂的行（$totalLines 行全认得出）",
            evidence = evidence,
        )
        alreadyRecorded -> RepairPlan(
            kind = RepairKind.LEDGER,
            verdict = RepairVerdict.NOTHING,
            reason = "账本 $ledger 的 $badLines 行损坏已记过账（原行保留，不重复记）",
            evidence = evidence,
        )
        else -> RepairPlan(
            kind = RepairKind.LEDGER,
            verdict = RepairVerdict.FIXABLE,
            reason = "账本 $ledger 有 $badLines 行读不懂（共 $totalLines 行），还没人记过账",
            targets = listOf(ledger),
            evidence = evidence,
        )
    }

    // ── ③ 缓存/临时 ─────────────────────────────────────────────────────────

    /**
     * `临时/` 里哪些该清。
     *
     * 判据（派工单 §3）：**只清"带明确前缀且超 7 天"的**——两个条件**合取**，
     * 缺一个都不许动：
     * - 没前缀 ⇒ 可能是用户自己的东西（`临时/` 是约定给抱朴的，但用户也会放）；
     * - 没超龄 ⇒ 可能是正在用的草稿。
     *
     * **前缀由调用方传**（[prefixes]）：仓里没有"哪些前缀算抱朴的"这张判据表，
     * 本层**不替它定**（铁律④）。空集 ⇒ [RepairVerdict.NEED_HUMAN]，
     * 不是"没有候选"——"判不了"与"不用修"必须分开。
     */
    fun tempPlan(
        items: List<TempItem>,
        prefixes: Set<String>,
        nowMs: Long,
        evidence: String,
    ): RepairPlan {
        if (prefixes.isEmpty()) {
            return RepairPlan(
                kind = RepairKind.TEMP,
                verdict = RepairVerdict.NEED_HUMAN,
                reason = "没有前缀判据（哪些前缀算抱朴的），不猜：临时/ 里可能是用户自己的东西",
                evidence = evidence,
            )
        }
        val stale = items.filter { isStale(it, prefixes, nowMs) }
        if (stale.isEmpty()) {
            return RepairPlan(
                kind = RepairKind.TEMP,
                verdict = RepairVerdict.NOTHING,
                reason = "临时/ 里没有「带前缀且超 $TEMP_MAX_AGE_DAYS 天」的残留（共 ${items.size} 个文件）",
                evidence = evidence,
            )
        }
        return RepairPlan(
            kind = RepairKind.TEMP,
            verdict = RepairVerdict.FIXABLE,
            reason = "临时/ 有 ${stale.size} 个「带前缀且超 $TEMP_MAX_AGE_DAYS 天」的残留（共 ${items.size} 个文件）",
            targets = stale.map { it.relPath }.sorted(),
            evidence = evidence,
        )
    }

    /**
     * 单个候选是否该清：**带前缀 ∧ 超龄**（两个条件都要）。
     *
     * `lastModifiedMs <= 0` ⇒ **不算超龄**：`File.lastModified()` 读不出时回 0，
     * 若把它当"1970 年的老文件"，就会把刚写下的草稿清掉——这是"测不出来"冒充"超龄"，
     * 与取数层"绝不拿 0 顶替"同一条纪律。
     */
    fun isStale(item: TempItem, prefixes: Set<String>, nowMs: Long): Boolean =
        item.lastModifiedMs > 0 &&
            hasPrefix(item.relPath, prefixes) &&
            nowMs - item.lastModifiedMs > TEMP_MAX_AGE_DAYS * DAY_MS

    /** 前缀只看**文件名**（目录名带前缀不算——`临时/zd-稿/说明.txt` 的文件名没有前缀）。 */
    fun hasPrefix(relPath: String, prefixes: Set<String>): Boolean {
        val name = relPath.substringAfterLast('/')
        return prefixes.any { it.isNotEmpty() && name.startsWith(it) }
    }

    // ── ③b 419：工作区残留清点（白名单已定）────────────────────────────────

    /**
     * 419 清残留的白名单（**2026-11-04 用户已定**——判据出处落在这里，不再是"等一张表"）：
     *
     * - **目录**：`rehearsal/`、`shadow/`、`cache/`、`baopu-tmp/`——路径上任一层目录叫
     *   这个名字，它底下的文件就算抱朴自己的残留；
     * - **后缀**：`.tmp`、`.part`、`.crdownload`——半截活（中断的写／下载），落在哪儿都算。
     *
     * 两把尺是**或**关系（命中任意一把就算白名单内）；清不清还要另过"超 7 天"那把尺
     * （[isSweepStale]），与 408 前缀那一路的合取口径互不混用。
     */
    data class SweepWhitelist(val dirs: Set<String>, val suffixes: Set<String>) {
        companion object {
            val USER_2026_11_04: SweepWhitelist = SweepWhitelist(
                dirs = setOf("rehearsal", "shadow", "cache", "baopu-tmp"),
                suffixes = setOf(".tmp", ".part", ".crdownload"),
            )
        }
    }

    /**
     * 白名单两把尺：**在白名单目录里**（父链上任一层）∨ **文件名带白名单后缀**。
     *
     * 后缀照字面小写匹配（用户写的就是小写；`.TMP` 不算——往宽里配就是替人放宽判据）。
     * `shadow/` 不吃 `.shadow`：名字差一个点就是差一个点，影子的沙盒残留走"需人处理"
     * 报出来让人定，判据不替用户拐弯。
     */
    fun isSweepWhitelisted(relPath: String, whitelist: SweepWhitelist): Boolean {
        val segs = relPath.split('/')
        if (segs.dropLast(1).any { it in whitelist.dirs }) return true
        val name = segs.last()
        return whitelist.suffixes.any { it.isNotEmpty() && name.endsWith(it) }
    }

    /**
     * 208 主账挡板（[DecisionLedger.FILE_NAME]）：白名单再怎么匹配，主账与它的
     * 变体（`决策记录.jsonl.tmp` 这类半截写）一个都不许动——判据层先挡一道，
     * 执行层 [com.example.zhengdao.baopu.repair.DataDoctor] 的搬移口再挡一道。
     */
    fun isMainLedgerPath(relPath: String): Boolean {
        val name = relPath.substringAfterLast('/')
        return name == DecisionLedger.FILE_NAME || name.startsWith("${DecisionLedger.FILE_NAME}.")
    }

    /**
     * 清点口径的单件超龄：白名单 ∧ mtime 读得出 ∧ 超 [TEMP_MAX_AGE_DAYS] 天。
     *
     * `lastModifiedMs <= 0` 不算超龄（同 [isStale] 的判据：测不出来不许冒充超龄）；
     * 正好 7 天不算超（多一毫秒才算）。
     */
    fun isSweepStale(item: TempItem, whitelist: SweepWhitelist, nowMs: Long): Boolean =
        item.lastModifiedMs > 0 &&
            isSweepWhitelisted(item.relPath, whitelist) &&
            nowMs - item.lastModifiedMs > TEMP_MAX_AGE_DAYS * DAY_MS

    /**
     * 工作区残留该怎么清（判据逐条对 2026-11-04 用户口令）：
     *
     * - **白名单内 ∧ 超 7 天 ⇒ 清**（搬回收站，可恢复）——主账变体除外（[isMainLedgerPath]）；
     * - **白名单外 ⇒ 不动手**，报"需人处理"并**列出看到的东西**（只列顶层入口的名字，
     *   不把用户家底逐个抄进账）；
     * - **空集（什么都没看到）⇒ 永不清理**——连账都不留（408 的老规矩：没动手不写账）；
     * - 白名单内都还新鲜（≤7 天）⇒ 无需修，不是"清过了"。
     *
     * @param items 白名单内的文件（取数层扫好递进来，判据层不碰盘）
     * @param outside 白名单外的**顶层入口**名字（看到但没动手的东西，要列给人对账）
     */
    fun tempSweepPlan(
        items: List<TempItem>,
        outside: List<String>,
        whitelist: SweepWhitelist,
        nowMs: Long,
        evidence: String,
    ): RepairPlan {
        if (items.isEmpty() && outside.isEmpty()) {
            return RepairPlan(
                kind = RepairKind.TEMP,
                verdict = RepairVerdict.NOTHING,
                reason = "空集：工作区里什么都没看到——空集永不清理",
                evidence = evidence,
            )
        }
        val stale = items.filter { isSweepStale(it, whitelist, nowMs) }
        val vetoed = stale.filter { isMainLedgerPath(it.relPath) }
        val clean = stale.filterNot { isMainLedgerPath(it.relPath) }.map { it.relPath }.sorted()
        val outsiders = outside.filter { it.isNotEmpty() }.distinct().sorted()
        val outsideNote =
            if (outsiders.isEmpty()) "" else "；白名单外 ${outsiders.size} 项没动（需人处理）：${sweepNames(outsiders)}"
        val vetoNote = if (vetoed.isEmpty()) "" else "；主账挡下 ${vetoed.size} 个（不许删主账）：${sweepNames(vetoed.map { it.relPath }.sorted())}"
        return when {
            clean.isNotEmpty() -> RepairPlan(
                kind = RepairKind.TEMP,
                verdict = RepairVerdict.FIXABLE,
                reason = "白名单内超 $TEMP_MAX_AGE_DAYS 天 ${clean.size} 个（搬回收站可恢复）$outsideNote$vetoNote",
                targets = clean,
                evidence = evidence,
            )
            outsiders.isNotEmpty() || vetoed.isNotEmpty() -> RepairPlan(
                kind = RepairKind.TEMP,
                verdict = RepairVerdict.NEED_HUMAN,
                reason = "白名单内没有超 $TEMP_MAX_AGE_DAYS 天的残留（共 ${items.size} 个文件）$outsideNote$vetoNote",
                evidence = evidence,
            )
            else -> RepairPlan(
                kind = RepairKind.TEMP,
                verdict = RepairVerdict.NOTHING,
                reason = "白名单内 ${items.size} 个都还在 $TEMP_MAX_AGE_DAYS 天内，没动",
                evidence = evidence,
            )
        }
    }

    /** 列名字的小口（账行与日志都要能读：超过 [SWEEP_LIST_CAP] 个就收口成计数）。 */
    internal fun sweepNames(paths: List<String>): String =
        if (paths.size <= SWEEP_LIST_CAP) {
            paths.joinToString("、")
        } else {
            paths.take(SWEEP_LIST_CAP).joinToString("、") + "…等 ${paths.size} 项"
        }

    /** 列名单的收口（全列会把日志撑成流水账；10 个够人对账，余下的给计数）。 */
    const val SWEEP_LIST_CAP = 10

    // ── ④ 447：配置漂移（判据表＝唯一真源）────────────────────────────────

    /**
     * 配置判据表的一行。
     *
     * @param key 配置键的**稳定字面量**（与 owning 模块同一个串；改它＝老账对不上）
     * @param expected 判据表认定该生效的值（字符串形态，布尔写 `"true"`/`"false"`）
     * @param basis **依据判据表哪一行**——用户口令／派工单编号＋日期，账行与日志都念它；
     *              没有出处的行不许进表（铁律④在这一路的落点：值不是我们猜的默认值，
     *              是人拍过的判据）。
     */
    data class ConfigRow(val key: String, val expected: String, val basis: String)

    /**
     * 一个配置项的读数（[value] 是**现在生效**的值，不是"用户拨过没拨过"）。
     *
     * 键没落盘但读侧有缺省 ⇒ owning 模块照样给出一个值，这里照收（"没拨过"不等于"没读到"）。
     */
    data class ConfigItem(val key: String, val value: String)

    /**
     * 配置判据表（**唯一真源**；派工单 447 §三：改回判据表的值，不许取"默认值"）。
     *
     * 装什么：**用户拍过、且当前没有用户写口**的那几项。判据是"代价不对称"——
     * 一项若能被用户在设置页拨动，御医把它改回去就是盖掉她的选择（那是最坏的一种"修"）；
     * 反过来，一项只有判据、没有写口，那么盘上出现别的值只可能是漂移，改回判据值不会碰人。
     *
     * 表里现在只有一行，是照着这条判据挑的：
     * - `nightwatch_auto`＝开（432 拍板 A，2026-11-04 用户「开」；2026-11-05 记下的开关口径
     *   「缺省＝开」「写侧：本批不做（UI 批 U8）」⇒ 今天没有任何用户写口）。
     *
     * **U8 上线时必须把这一行撤掉或改判**（御医不许把用户按下去的「关」改回「开」）——
     * 这条写在 447 交付报告的移交项里，也写在这里，下一个改代码的人一眼能看到。
     */
    object ConfigCriteria {

        /** 暗卫自动档开关（键名与 [com.example.zhengdao.baopu.inspect.NocturnalAuto.SWITCH_KEY] 同串）。 */
        const val NIGHTWATCH_AUTO: String = "nightwatch_auto"

        /** 表本体（闭集；加一行＝用户立法，不走进化通道，也不许"顺手加一项"）。 */
        val ROWS: List<ConfigRow> = listOf(
            ConfigRow(
                key = NIGHTWATCH_AUTO,
                expected = "true",
                basis = "432 派工单拍板 A（2026-11-04 用户「开」）＋开关口径「缺省＝开」（2026-11-05 记）",
            ),
        )

        /** 按键找行（表外 ⇒ null，一路的"需人处理"就靠它判）。 */
        fun rowOf(key: String): ConfigRow? = ROWS.firstOrNull { it.key == key }
    }

    /**
     * 配置漂移该怎么修（派工单 447 §三）。
     *
     * 三种输入、三种结论：
     * - [readings] 里能对上表且**值不等** ⇒ [RepairVerdict.FIXABLE]，reason 逐条写清
     *   「哪项 原值=… → 判据表值=…（依据：…）」——先记后修要吃的就是这句话；
     * - [outside]（看到、但判据表里没有的键）∨ 表里某项**没读出来**（未取样）⇒
     *   [RepairVerdict.NEED_HUMAN]：只把看到的值列出来，一个键都不动。
     *   **绝不替它猜默认值**——猜错比不改更坏，且猜出来的值会伪装成"人定过的判据"；
     * - 表里的项全对位、表外也没有东西 ⇒ [RepairVerdict.NOTHING]（不是"修好了"）。
     *
     * @param rows 判据表（默认唯一真源 [ConfigCriteria.ROWS]；测试可递一张假表，
     *             房型照 [tempPlan] 的 `prefixes`——判据层不替调用方记表）
     */
    fun configPlan(
        readings: List<ConfigItem>,
        outside: List<ConfigItem>,
        rows: List<ConfigRow> = ConfigCriteria.ROWS,
        evidence: String,
    ): RepairPlan {
        val byKey = readings.associateBy { it.key }
        val inRows = rows.map { it.key }.toSet()
        val drift = ArrayList<Pair<String, String>>()
        val unreadable = ArrayList<String>()
        for (row in rows.sortedBy { it.key }) {
            val got = byKey[row.key]
            when {
                got == null -> unreadable += row.key
                got.value == row.expected -> Unit
                else -> drift += row.key to
                    "${row.key} 原值=${got.value} → 判据表值=${row.expected}（依据：${row.basis}）"
            }
        }
        val outsiders = outside.filter { it.key.isNotBlank() && it.key !in inRows }
            .map { "${it.key}=${it.value}" }
            .distinct()
            .sorted()
        val outsideNote =
            if (outsiders.isEmpty()) "" else "；判据表外 ${outsiders.size} 项没动（表里没有这一行）：${sweepNames(outsiders)}"
        val unreadNote =
            if (unreadable.isEmpty()) "" else "；表里 ${unreadable.size} 项这趟没读出来（未取样，不猜）：${sweepNames(unreadable.sorted())}"
        return when {
            drift.isNotEmpty() -> RepairPlan(
                kind = RepairKind.CONFIG,
                verdict = RepairVerdict.FIXABLE,
                reason = "配置漂移 ${drift.size} 项：${drift.joinToString("；") { it.second }}$outsideNote$unreadNote",
                targets = drift.map { it.first }.sorted(),
                evidence = evidence,
            )
            outsiders.isNotEmpty() || unreadable.isNotEmpty() -> RepairPlan(
                kind = RepairKind.CONFIG,
                verdict = RepairVerdict.NEED_HUMAN,
                reason = "判据表 ${rows.size} 项这趟没一个该改$outsideNote$unreadNote",
                evidence = evidence,
            )
            else -> RepairPlan(
                kind = RepairKind.CONFIG,
                verdict = RepairVerdict.NOTHING,
                reason = if (rows.isEmpty()) {
                    "空集：判据表没一项可核，也没看到任何配置项"
                } else {
                    "判据表 ${rows.size} 项全对位（值与依据行一致），没动"
                },
                evidence = evidence,
            )
        }
    }

    // ── 铁律⑤：一轮只修一类 ─────────────────────────────────────────────────

    /**
     * 一轮只修一类的闸（返回 null ＝ 放行；非 null ＝ 整单拒，一个字都不许动）。
     *
     * 为什么要有：`repair(root, listOf(INDEX, LEDGER))` 这种写法在类型上是合法的，
     * 一旦放过去，"修了哪一类、依据哪条、回滚哪一步"就说不清了。
     */
    fun batchGuard(kinds: List<RepairKind>): String? = when {
        kinds.isEmpty() -> "这一轮没指定要修哪一类（空单不是「全都不修」）"
        kinds.size > 1 -> "一轮只修一类（收到 ${kinds.size} 类：${kinds.joinToString("、") { it.cn }}）"
        else -> null
    }
}
