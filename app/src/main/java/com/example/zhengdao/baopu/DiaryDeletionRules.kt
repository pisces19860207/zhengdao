// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第一百四十七批（checkpoint③ 删除）＋《抱朴情景记忆设计方案》§5
// 真删的五条硬边界（2026-10-22 用户两次拍板）：
//   ① 只删「记的那段」——日记/月度摘要/索引/归档，产物与用户文件一个字节不碰；
//   ② 必须明确确认——确认卡把「删日记，不删文件」说到字面上，歧义指令先问；
//   ③ 不进回收站——日记不是产物，真删即真删（用户要的就是「删了就没了」）；
//   ④ 归档 ≠ 删除——归档件只是不回灌，仍占盘可查；
//   ⑤ 删前产物可核对——确认卡列出该批条目引用的全部产物路径，看得见再按确认。
// 判据 18：真删后全量 grep 日记/摘要/索引/归档四处零命中，否则「删了就忘」是假的。
//
// 本文件只有纯函数（无 IO、无 Context、无 Compose）——JVM 直测；执行在 DiaryStore。
package com.example.zhengdao.baopu

/** 删除范围：按「哪几天」整日删，或按「哪几条」单条删（两者都给全量条目，由判据挑）。 */
internal enum class DiaryDeletionScope { DAY, ENTRY }

/**
 * 一次真删的**计划**（确认卡的素材；执行前给用户看，执行后拿它验收）。
 *
 * @param scope 按日还是按条
 * @param targetDates 涉及的日期（按日删时＝选中日；按条删时＝条目所在日的并集）
 * @param targetIds 要删的条目 id（按日删时＝这些日的全部非摘要条目）
 * @param artifactRefs **会被波及展示的产物引用**（只给看、不删除——边界①⑤）
 * @param affectedDigestMonths 需要重算/删除的月度摘要月份（删完后若摘要空则连摘要一起消失）
 */
internal data class DiaryDeletionPlan(
    val scope: DiaryDeletionScope,
    val targetDates: List<String>,
    val targetIds: List<String>,
    val artifactRefs: List<String>,
    val affectedDigestMonths: List<String>,
) {
    val isEmpty: Boolean get() = targetIds.isEmpty()
}

/** 真删执行后的报告（对账用：四处应该各有多少）。 */
internal data class DiaryDeletionReport(
    val deletedEntries: Int,
    val deletedFiles: Int,
    val rebuiltDigests: Int,
    val removedArchiveFiles: Int,
    val deletedIndexRows: Int,
)

internal object DiaryDeletionRules {

    /** 确认卡的固定话头（边界②：把「删日记，不删文件」说到字面上）。 */
    const val CONFIRM_WORDS = "删日记，不删文件"

    /**
     * 生成删除计划。
     *
     * 按日删：该日全部非摘要条目（摘要归 maintain 管，不在这里逐条删——
     * 但该日被摘要承载过的，摘要月份进 [DiaryDeletionPlan.affectedDigestMonths]，
     * 执行后由 store 重算，线索断不断由重算结果说话）。
     * 按条删：只删指定 id；月份同样按条目 date 归集。
     */
    fun plan(
        scope: DiaryDeletionScope,
        entries: List<DiaryEntry>,
        dates: List<String> = emptyList(),
        ids: List<String> = emptyList(),
    ): DiaryDeletionPlan {
        val nonDigest = entries.filterNot { DiaryRecall.isDigest(it.id) }
        val targets = when (scope) {
            DiaryDeletionScope.DAY -> nonDigest.filter { it.date in dates }
            DiaryDeletionScope.ENTRY -> nonDigest.filter { it.id in ids }
        }
        val targetDates = targets.map { it.date }.distinct().sorted()
        return DiaryDeletionPlan(
            scope = scope,
            targetDates = targetDates,
            targetIds = targets.map { it.id }.distinct().sorted(),
            // 产物引用只去重保序，**用于展示核对**；绝不参与删除（边界①）
            artifactRefs = targets.flatMap { it.artifacts }.distinct(),
            affectedDigestMonths = targetDates.map { it.take(7) }.distinct().sorted(),
        )
    }

    /**
     * 确认卡正文（边界②③⑤）：一处拼、处处一致——UI 与测试用同一份文本，
     * 不允许「卡上一套、日志里另一套」。产物引用逐行列在「以下文件不会动」之下。
     */
    fun confirmBody(plan: DiaryDeletionPlan): String {
        if (plan.isEmpty) return "没有可删的日记条目。"
        val what = when (plan.scope) {
            DiaryDeletionScope.DAY -> "${plan.targetDates.size} 天的日记"
            DiaryDeletionScope.ENTRY -> "${plan.targetIds.size} 条日记"
        }
        val sb = StringBuilder()
        // 边界②：话头说在正文里（确认键上还有一遍，UI 与纯函数两处一致）
        sb.append(DiaryDeletionRules.CONFIRM_WORDS).append("。")
        sb.append("\n将永久删除").append(what)
            .append("（含月度摘要与归档中的同名记录、索引行）。")
            .append("\n删除后不可恢复，也不进回收站。")
        if (plan.artifactRefs.isNotEmpty()) {
            sb.append("\n\n这些产物文件不会被删除：")
            plan.artifactRefs.forEach { sb.append("\n- ").append(it) }
        }
        return sb.toString()
    }

    /**
     * 删后验收（判据 18）：四处文本里**一个字符都不许**再提到被删的 id。
     *
     * @param sources 删后重新读出的全部相关文本（日记文件＋摘要文件＋归档文件＋索引行序列化）
     * @return true = 零命中（真删干净）；false = 有残影（附命中明细，调用方记运行日志）
     */
    fun verifyGone(sources: List<String>, deletedIds: List<String>): Boolean =
        deletedIds.none { id -> sources.any { it.contains(id) } }
}
