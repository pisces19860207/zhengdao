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
// 3. **只修数据**：四类里本批只做三类（①索引 ②账本 ③缓存/临时）；类④配置漂移
//    用户 2026-11-03 裁定"先不做"，故 [RepairKind] 里**没有** CONFIG 这一项——
//    不占位、不留半截枚举（留个空位会让人以为"只是没实现"）。
// 4. **判据要说得出证据**：每个 [RepairPlan] 都带 `evidence`（依据哪一次体检），
//    没有出处的计划不许构造——与 `HealthCheck` 的 L14 同一条口径。
package com.example.zhengdao.baopu.inspect

/**
 * 可自修的数据类目（**闭集**；`wire` 是账行的稳定键，改字面量＝老账对不上）。
 *
 * 类④（配置漂移／判据表）**不在这个枚举里**：用户 2026-11-03 裁定"先不做"。
 */
internal enum class RepairKind(val wire: String, val cn: String) {
    INDEX("index", "索引重建"),
    LEDGER("ledger", "账本补损坏行"),
    TEMP("temp", "缓存残留清理"),
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
