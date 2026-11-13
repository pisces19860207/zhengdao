// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十七批 · 结论-证据对账机检（金吾卫＝取证与比对那一半）。
//
// 三段：① 从交付报告/会话结论里抽「动作＋对象」；② 按动作类型找证据，找不到记缺口；
//       ③ 缺口清单交右相判三态、左相判要不要现在打扰——本件只上报，不下结论。
//
// 三条纪律（房型照 239 的 AuditGapCheck）：
// 1. 只读：本文件一个写出口都没有，不碰盘、不跑进程、不落账；
//    证据一律从 [ClaimEvidenceEnv] 注入（生产由装配层给，测试用内存夹具）。
// 2. 只认明确动词＋明确对象：含糊句不计入，宁可漏报也不误报（判据②）。
// 3. 缺口不是失败：本件只产「对得上/对不上/证据过期」＋要求什么证据；
//    合格与否归右相，要不要打扰归左相（派工单 §三）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.intake.IntakeProbes

/** 一条声称的动作（闭集；只认明确完成式，含糊动词根本没有对应取值）。 */
internal enum class ClaimAction(val wire: String) {
    DONE("done"),
    FILE_ADDED("file_added"),
    FILE_REMOVED("file_removed"),
    MERGED("merged"),
    TESTS_GREEN("tests_green"),
    DEVICE_VERIFIED("device_verified"),
}

/** 从报告里抽出的一条声称：[index] 是第几条（1 起），判据①靠它指「第几件」。 */
internal data class Claim(
    val index: Int,
    val action: ClaimAction,
    val target: String,
    val raw: String,
)

/** 每类声称要求什么证据（缺口里的「要求什么」这一格）。 */
internal enum class EvidenceNeed(val cn: String) {
    LEDGER_OR_COMMIT("账行（起居注/审计账）或 commit 短号"),
    GIT_SHOW_PATH("git show 命中＋路径"),
    MERGE_BASE("git merge-base --is-ancestor 命中 main"),
    TEST_XML("测试 XML（套件/例/失败数＋mtime）"),
    DEVICE_DIR("证据目录文件（截图/RunLog）"),
}

/** 测试 XML 读数（mtime 是本轮新鲜度的唯一判据）。 */
internal data class TestXmlReading(
    val path: String,
    val suites: Int,
    val cases: Int,
    val failures: Int,
    val mtimeMs: Long,
)

/**
 * 金吾卫的取证口：**全是读**，没有一格能写。
 *
 * 为什么不在这里跑 git：跑命令属于装配层，且「运行前后 git 状态一致」要是机检自己的
 * 性质而不是靠调用方自觉（判据⑤）。本件只读别人递进来的结果，谁也改不了盘。
 */
internal interface ClaimEvidenceEnv {
    /** 本轮起点：晚于它的 XML mtime 才算本轮证据（判据③）。 */
    val roundStartMs: Long

    /** 此刻钟点（0–23）：左相判「是不是深夜」用，本层不读时钟。 */
    val hourOfDay: Int

    /** 账行（起居注/审计账）原文列表。 */
    fun ledgerLines(): List<String>

    /** 本轮 commit 短号列表。 */
    fun commitShortIds(): List<String>

    /** git show 能点开的相对路径集合。 */
    fun gitShowPaths(): Set<String>

    /** 该分支是否已是 main 的祖先（对应 merge-base 的退出码）。 */
    fun mergedIntoMain(branch: String): Boolean

    /** 本轮测试 XML；没有 ⇒ null（不许拿上一轮的充数）。 */
    fun testXml(): TestXmlReading?

    /** 真机证据目录里的文件名（截图/RunLog）。 */
    fun deviceEvidenceFiles(): List<String>
}

internal enum class GapReason(val wire: String) { MISSING("missing"), STALE("stale") }

/** 一条缺口：第几件、给了什么、要求什么证据。 */
internal data class ClaimGap(
    val claimIndex: Int,
    val claim: Claim,
    val reason: GapReason,
    val given: String,
    val required: String,
) {
    /** 交右相的那一行（只描述缺口，不含任何合格结论）。 */
    fun render(): String {
        val head = "第 $claimIndex 件「${claim.raw}」"
        return when (reason) {
            GapReason.MISSING -> "$head 缺证据：$given；要求 $required"
            GapReason.STALE -> "$head 证据过期：$given；要求 $required"
        }
    }
}

/** 左相的打扰建议（机检只给建议，不替左相拍板）。 */
internal enum class LeftAdvice(val wire: String, val cn: String) {
    SUGGESTION_TAB("suggestion_tab", "建议页签行"),
    LEDGER_ONLY("ledger_only", "只记账"),
}

/** 一轮对账的产出。 */
internal data class AuditOutcome(
    val claims: List<Claim>,
    val gaps: List<ClaimGap>,
    /** 交右相的缺口清单（右相拿它判三态；本件一格结论都不写）。 */
    val chancellorInput: List<String>,
    val leftAdvice: LeftAdvice,
) {
    /** 只标「交给谁」，不写结论。 */
    val handoffTo: List<String> get() = listOf(HANDOFF_RIGHT, HANDOFF_LEFT)

    companion object {
        const val HANDOFF_RIGHT = "右相判三态"
        const val HANDOFF_LEFT = "左相判打扰"
    }
}

internal object ClaimEvidenceAudit {

    /**
     * 明确动词→动作（闭集）。
     *
     * [IntakeProbes.doneMarkers] 是仓里现成的「只认完成式」词表（345 准入探针口径），
     * 这里复用它当 DONE 的词源：不为对账另立一套「什么算做完了」。
     * 表按动词长度降序排，免得「已加文件」被「已加」截胡。
     */
    private val VERB_RULES: List<Pair<String, ClaimAction>> = (
        (IntakeProbes.doneMarkers + listOf("已修", "已修复", "修复了", "已实现", "已落地"))
            .map { it to ClaimAction.DONE } +
            listOf(
                "已加文件" to ClaimAction.FILE_ADDED,
                "新增文件" to ClaimAction.FILE_ADDED,
                "已新增" to ClaimAction.FILE_ADDED,
                "已加" to ClaimAction.FILE_ADDED,
                "新增" to ClaimAction.FILE_ADDED,
                "已删文件" to ClaimAction.FILE_REMOVED,
                "已删除" to ClaimAction.FILE_REMOVED,
                "已删" to ClaimAction.FILE_REMOVED,
                "移除" to ClaimAction.FILE_REMOVED,
                "已合并" to ClaimAction.MERGED,
                "已合入" to ClaimAction.MERGED,
                "合并进" to ClaimAction.MERGED,
                "测试全绿" to ClaimAction.TESTS_GREEN,
                "全量测试通过" to ClaimAction.TESTS_GREEN,
                "测试 0 失败" to ClaimAction.TESTS_GREEN,
                "真机验过" to ClaimAction.DEVICE_VERIFIED,
                "真机验证" to ClaimAction.DEVICE_VERIFIED,
                "真机取证" to ClaimAction.DEVICE_VERIFIED,
            )
        ).sortedByDescending { it.first.length }

    /** 含糊对象：以这些词开头的「对象」不算明确对象（判据②的负例护栏）。 */
    private val VAGUE_WORDS = listOf(
        "一下", "一些", "相关", "若干", "部分", "整体", "略微", "稍微",
        "适当", "某些", "有点", "大概", "可能", "也许", "等等",
    )

    private val NUM_PREFIX = Regex("^\\d+[.、)]\\s*")
    private val BACKTICK = Regex("`([^`]+)`")
    private val OBJECT_END = charArrayOf('|', '｜', '，', ',', '。', '；', ';', '、')

    /** ① 抽声称：逐行，只认明确动词＋明确对象；含糊句整行丢弃。 */
    fun extractClaims(reportText: String): List<Claim> {
        val out = mutableListOf<Claim>()
        reportText.replace("\r\n", "\n").lines().forEach { raw ->
            val line = stripLine(raw)
            val rule = ruleOf(line) ?: return@forEach
            val target = objectOf(line, rule.first).ifEmpty { implicitObjectOf(rule) }
            if (!isExplicit(target)) return@forEach
            out += Claim(out.size + 1, rule.second, target, raw.trim())
        }
        return out
    }

    /** ②＋③ 跑一轮：抽声称 → 逐条找证据 → 出缺口 → 上报左右相。 */
    fun audit(
        reportText: String,
        env: ClaimEvidenceEnv,
        leftAllowsDisturb: Boolean = true,
    ): AuditOutcome {
        val claims = extractClaims(reportText)
        val gaps = claims.mapNotNull { gapOf(it, env) }
        return AuditOutcome(
            claims = claims,
            gaps = gaps,
            chancellorInput = gaps.map(ClaimGap::render),
            leftAdvice = adviceOf(gaps.size, env.hourOfDay, leftAllowsDisturb),
        )
    }

    /**
     * ③ 左相那一半：缺口 ≥1 且非深夜 ⇒ 建议页签行；否则只记账。
     *
     * [leftAllowsDisturb] 是左相的既成结论，机检不翻案（派工单判据⑥夹具）。
     * 深夜窗口复用 [PatrolScheduler.inNightWindow]（239/§10③ 的唯一钟点判据），
     * 本件不另写一套「几点算夜里」。
     */
    fun adviceOf(
        gapCount: Int,
        hourOfDay: Int,
        leftAllowsDisturb: Boolean = true,
    ): LeftAdvice = when {
        !leftAllowsDisturb -> LeftAdvice.LEDGER_ONLY
        gapCount < 1 -> LeftAdvice.LEDGER_ONLY
        PatrolScheduler.inNightWindow(hourOfDay) -> LeftAdvice.LEDGER_ONLY
        else -> LeftAdvice.SUGGESTION_TAB
    }

    // ── 抽声称的三个小判据 ─────────────────────────────────────────────────

    private fun stripLine(raw: String): String {
        val s = raw.trim()
            .removePrefix("- [x] ").removePrefix("- [ ] ")
            .removePrefix("- ").removePrefix("* ").removePrefix("| ")
            .trim().trim('|').trim()
        return NUM_PREFIX.replace(s, "")
    }

    /** 行里最先出现的那个动词；同一位置并列时取更长的（「已加文件」优于「已加」）。 */
    private fun ruleOf(line: String): Pair<String, ClaimAction>? = VERB_RULES
        .mapNotNull { r ->
            val at = line.indexOf(r.first)
            if (at < 0) null else Triple(at, r.first.length, r)
        }
        .minWithOrNull(
            compareBy<Triple<Int, Int, Pair<String, ClaimAction>>> { it.first }
                .thenByDescending { it.second },
        )?.third

    /** 动词后到句末标点之间就是对象；引导词（了/冒号/竖线）先剥掉。 */
    private fun objectOf(line: String, verb: String): String {
        val after = line.substringAfter(verb, "").trimStart(' ', '：', ':', '了', '|', '｜', '-', '—')
        val cut = after.indexOfAny(OBJECT_END)
        return (if (cut >= 0) after.substring(0, cut) else after).trim()
    }

    /**
     * 「测试全绿」这类动作自带对象（「测试」就在动词里），没后缀也算一条明确声称；
     * 其余动作没写对象就是含糊句，整行丢弃（派工单①「明确动词＋明确对象」）。
     */
    private fun implicitObjectOf(rule: Pair<String, ClaimAction>): String =
        if (rule.second == ClaimAction.TESTS_GREEN) rule.first else ""

    private fun isExplicit(target: String): Boolean {
        if (target.length < 2) return false
        if (target in VAGUE_WORDS) return false
        if (VAGUE_WORDS.any { target.startsWith(it) }) return false
        return target.any { it.isLetterOrDigit() }
    }

    // ── 找证据：按动作类型分派，每个分支只做一次比对 ──────────────────────

    private fun gapOf(c: Claim, env: ClaimEvidenceEnv): ClaimGap? = when (c.action) {
        ClaimAction.DONE -> doneGap(c, env)
        ClaimAction.FILE_ADDED, ClaimAction.FILE_REMOVED -> fileGap(c, env)
        ClaimAction.MERGED -> mergeGap(c, env)
        ClaimAction.TESTS_GREEN -> testGap(c, env)
        ClaimAction.DEVICE_VERIFIED -> deviceGap(c, env)
    }

    private fun doneGap(c: Claim, env: ClaimEvidenceEnv): ClaimGap? {
        val lines = env.ledgerLines() + env.commitShortIds()
        if (matchesAny(lines, c.target)) return null
        return gap(c, GapReason.MISSING, "账上/commit 共 ${lines.size} 条，没有「${c.target}」", EvidenceNeed.LEDGER_OR_COMMIT)
    }

    private fun fileGap(c: Claim, env: ClaimEvidenceEnv): ClaimGap? {
        val paths = env.gitShowPaths().toList()
        if (matchesAny(paths, c.target)) return null
        return gap(c, GapReason.MISSING, "git show 列出 ${paths.size} 个路径，没有「${c.target}」", EvidenceNeed.GIT_SHOW_PATH)
    }

    private fun mergeGap(c: Claim, env: ClaimEvidenceEnv): ClaimGap? {
        if (env.mergedIntoMain(c.target)) return null
        return gap(c, GapReason.MISSING, "分支「${c.target}」不是 main 的祖先", EvidenceNeed.MERGE_BASE)
    }

    private fun testGap(c: Claim, env: ClaimEvidenceEnv): ClaimGap? {
        val x = env.testXml()
            ?: return gap(c, GapReason.MISSING, "没找到本轮测试 XML", EvidenceNeed.TEST_XML)
        if (x.failures > 0) {
            return gap(c, GapReason.MISSING, "${x.path} 里失败 ${x.failures} 例", EvidenceNeed.TEST_XML)
        }
        if (x.mtimeMs < env.roundStartMs) {
            return gap(
                c, GapReason.STALE,
                "${x.path} mtime=${x.mtimeMs} 早于本轮起点 ${env.roundStartMs}",
                EvidenceNeed.TEST_XML,
            )
        }
        return null
    }

    private fun deviceGap(c: Claim, env: ClaimEvidenceEnv): ClaimGap? {
        val files = env.deviceEvidenceFiles()
        if (matchesAny(files, c.target)) return null
        return gap(c, GapReason.MISSING, "证据目录 ${files.size} 个文件里没有「${c.target}」", EvidenceNeed.DEVICE_DIR)
    }

    private fun gap(c: Claim, reason: GapReason, given: String, need: EvidenceNeed) =
        ClaimGap(c.index, c, reason, given, need.cn)

    /** 对象里若用反引号点名文件，按反引号内的正文匹配；否则整段对象比。 */
    private fun matchesAny(lines: List<String>, target: String): Boolean {
        val key = (BACKTICK.find(target)?.groupValues?.get(1) ?: target).trim()
        return key.length >= 2 && lines.any { it.contains(key, ignoreCase = true) }
    }
}
