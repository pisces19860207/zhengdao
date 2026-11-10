// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百三十六批 A · 判据与坑账**运行时包**（派工单 436「追加二」的第 ⑦ 件）。
//
// 用户在 436-A 裁定里把这一跳钉成三条，本文件逐条对上：
//   ① **只注入摘要**：判据与坑账各取最相关的 N 条 ＋ 版本号 ＋ 条数 ＋ 全文路径；
//      全文不进 prompt（坑账全文是盘上那本 `判决经验账.jsonl`，模型要细节自己读）。
//   ② **哈希去重**：注入内容由一个版本键（包版本｜判据表序｜坑账条数｜最后更新｜账龄）
//      决定；键没变 ⇒ 文本逐字节不变，**不随轮次增长**。这正是 258 批 `PROMPT_CONTRACT`
//      那条「恒常追加，别随状态出现/消失」的同一口径——随轮次漂移等于自毁前缀缓存。
//   ③ **与记忆分层不混池**：这里注入的是「**规矩**」（判据＝硬闸，坑账＝踩过的坑），
//      不是「偏好」也不是「事实」——那两类归第四百三十八批，本文件一个字段都不读它们。
//
// 落点（用户裁定原文）：注入 **system prompt**，也就是 `RunEngine.kt` 里那条
// `LlmMessage(ROLE_SYSTEM, systemFinal)`；不塞每轮 user 上下文、不塞工具描述。
// 拼法走**尾部追加**：`systemFinal = … + PROMPT_CONTRACT + [本包的块]`——
// 前面的字节一个不动，只往尾巴加（`ContextAssembler.render` 的同一条追加判据）。
//
// 本批判据④「零拦截」在本文件怎么成立：它**只产文本**——不判动作、不改三态、
// 不 import `GuardPipeline`，也不读任何档位/阈值。有没有这条注入，判定结果一模一样。
// 「判据/坑账是硬规矩不是建议」这句话的**执行**仍在 310/253 的闸里，这里只是让模型知道它存在。
//
// 第五百六十八批 A（2026-11-10）改一处并如实记账：注入口多了**灰度门**——上一段"不读任何
// 档位"从 568-A 起不再成立，发不发这段文本由登记表 `PROMPT_TAIL` 的档位决定（缺省灰度
// ⇒ 行为不变），版本变更落一条观察账。其余四项裁定（只注摘要／哈希去重／不混分层／
// 不判动作不改三态）逐字未动；`systemSuffix` 的 KDoc 里写了完整的账。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ExperienceKind
import com.example.zhengdao.baopu.agent.ExperienceRow
import com.example.zhengdao.baopu.agent.JudgmentExperienceLedger
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.evolution.lowrisk.EvoCandidate
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskEvo
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskVerdict
import com.example.zhengdao.baopu.policy.EpochRuntime
import com.example.zhengdao.baopu.policy.ImmutableCriteria
import com.example.zhengdao.core.TimeFormats
import java.io.File

/** 运行时包里的一条判据（渲染成一行）。 */
internal data class PackCriterion(val cn: String, val lawRef: String)

/** 运行时包里的一条坑（渲染成一行）。 */
internal data class PackPit(val atMs: Long, val label: String, val why: String)

/**
 * 运行时包的输入：纯数据，没有文件、没有时钟。
 *
 * `lastUpdatedAtMs == null` ＝ 这本坑账**还没落过一行**（不是"落得很久"，两种说法在
 * [CriteriaPitPack.render] 里分开写——把"空账"说成"旧账"是假话，用户最反感这个）。
 */
internal data class PackInput(
    val criteriaVersion: String,
    val criteria: List<PackCriterion>,
    val pitTotal: Int,
    val pits: List<PackPit>,
    val lastUpdatedAtMs: Long?,
    val fullPath: String?,
)

/** 判据与坑账运行时包（摘要渲染 ＋ 版本键 ＋ 装配壳）。 */
internal object CriteriaPitPack {

    /** 包自身的形状版本（改渲染形状／改取数口径才 +1）。 */
    const val VERSION = 1

    /**
     * 注入块总长上限（字符）。判据原话是「总长度有上限（比如 ≤800 token），超了按
     * 最近/最相关截断而不是全塞」——中文按一字一 token 的保守口径折成 800 字符。
     * 超了丢的是**坑账尾部**（已按最相关排过序），判据三条与版本行永远留全。
     */
    const val MAX_CHARS = 800

    /** 坑账进摘要的条数（其余的看全文那本账）。 */
    const val PIT_TOP = 5

    /** 坑账过期天数（AGENTS「稳定层与易变层」定的是默认 90 天，这里同口径）。 */
    const val STALE_DAYS = 90L

    private const val DAY_MS = 86_400_000L

    /**
     * 坑的等级（进摘要的先后）：撞过法条的**违规**最贵，其次判错**归因**、被压错要回补的
     * **重复问**；其余（信号／问答／压下）不算坑，只垫底按时间排。
     */
    private val PIT_RANK: Map<ExperienceKind, Int> = mapOf(
        ExperienceKind.VIOLATION to 0,
        ExperienceKind.ATTRIBUTION to 1,
        ExperienceKind.REASK to 2,
    )

    private const val NOT_A_PIT = 3

    // ── 纯判据部分（零时钟：时刻由调用方给）──────────────────────────────

    /** 账龄（天）；空账返回 -1（与"很久没更新"区分开）。 */
    fun ageDays(input: PackInput, nowMs: Long): Long {
        val last = input.lastUpdatedAtMs ?: return -1L
        return Math.floorDiv(nowMs - last, DAY_MS)
    }

    /** 坑账是否过期（空账不算过期——那是"还没学到东西"，另一句话）。 */
    fun stale(input: PackInput, nowMs: Long): Boolean = ageDays(input, nowMs) > STALE_DAYS

    /**
     * 版本键（判据②「同一版本只注入一次；版本变了才替换」里那个"版本"的定义）。
     *
     * 键里**只放会改变内容的东西**：包形状版本、判据表序、坑账条数、最后更新时刻、账龄
     * （账龄按天 ⇒ 同一天内两轮一定同键）。请求时刻的毫秒抖动不进键，文本因此不逐轮漂。
     */
    fun versionKey(input: PackInput, nowMs: Long): String = buildString {
        append('v').append(VERSION)
        append('|').append(input.criteriaVersion)
        append('|').append(input.pitTotal)
        append('|').append(input.lastUpdatedAtMs ?: -1L)
        append('|').append(ageDays(input, nowMs))
    }

    /**
     * 渲染注入块（纯函数：同一 [input] ＋ 同一天 ⇒ 逐字节相同；长度 **≤ [MAX_CHARS] 由构造保证**）。
     *
     * 返回的串**不带**前后分隔符——分隔由调用方（[systemSuffix]）给，
     * 因为拼进 `systemFinal` 时它是尾巴上的一段，前后各要一个空行。
     */
    fun render(input: PackInput, nowMs: Long): String {
        val head = renderHead(input, nowMs)
        val criteria = input.criteria.mapIndexed { i, c -> "${i + 1}. ${c.cn}（${c.lawRef}）" }
        val tail = renderTail(input, nowMs)
        val pitLines = input.pits.take(PIT_TOP).map {
            "坑 · ${TimeFormats.dayStamp(it.atMs)} ${it.label} ⇒ ${it.why}"
        }
        // 上限不许靠"预算估个大概"——估少了就超（实测一版就是这么溢出到 818 字符）。
        // 整段还超就接着从**最不相关**那一端（尾部）丢，丢到只剩空账也如实写"见全文"。
        var kept = pitLines
        while (kept.isNotEmpty() && assemble(head, criteria, kept, tail, input.pitTotal).length > MAX_CHARS) {
            kept = kept.dropLast(1)
        }
        return assemble(head, criteria, kept, tail, input.pitTotal)
    }

    /** 拼最终那段文本（判据三条与版本行永远留全；坑行没留满时如实报还剩多少没列）。 */
    private fun assemble(
        head: String,
        criteria: List<String>,
        pits: List<String>,
        tail: String,
        pitTotal: Int,
    ): String = buildString {
        append(head)
        criteria.forEach { append(it).append('\n') }
        if (pits.isEmpty()) {
            append(if (pitTotal == 0) "坑账（还没有一行学到的东西落账，0 条）\n" else "坑账：见全文\n")
        } else {
            pits.forEach { append(it).append('\n') }
            val omitted = pitTotal - pits.size
            if (omitted > 0) append("（另有 $omitted 条没列，读全文那本账）\n")
        }
        append(tail)
    }

    /** 版本行：包号＋判据条数与表序＋坑账条数与最后更新日期（判据①要能在 system 里看到这些）。 */
    private fun renderHead(input: PackInput, nowMs: Long): String {
        val last = input.lastUpdatedAtMs?.let { TimeFormats.dayStamp(it) } ?: "还没有"
        val mark = if (stale(input, nowMs)) "（**旧**）" else ""
        return "## 规矩与坑账（运行时包 v$VERSION）\n" +
            "判据 ${input.criteria.size} 条（表序：${input.criteriaVersion}）· " +
            "坑账 ${input.pitTotal} 条 · 最后更新 $last$mark\n" +
            "这些是硬规矩与既成事实，不是建议：判据不许自行放宽，坑账里记过的不许再踩第二次。\n"
    }

    /** 过期提示（判据③的那句话）＋ 全文路径。 */
    private fun renderTail(input: PackInput, nowMs: Long): String = buildString {
        if (stale(input, nowMs)) {
            append("⚠️ 你的坑账是旧的：最后更新距今 ${ageDays(input, nowMs)} 天，超过 $STALE_DAYS 天阈值")
            append("⇒ 先复核再用，别照抄旧结论。\n")
        }
        val path = input.fullPath
        append("全文：").append(path ?: "还没装配落点（本机一行账都还没写过）").append('\n')
    }

    // ── 装配壳（读盘 ＋ 去重缓存；只有这里碰时钟与文件）──────────────────

    @Volatile
    private var lastKey = ""

    @Volatile
    private var lastText = ""

    /** 从真源拼一份输入：判据＝310 的不可进化清单＋当前进化表序；坑账＝298 那本账。 */
    fun snapshot(): PackInput {
        val live = EpochRuntime.currentSnapshot()
        val version = if (live == null) {
            "写死三条，还没有进化表"
        } else {
            "E${live.epoch.ordinal}·表序 ${live.seq}"
        }
        val rows = runCatching { JudgmentExperienceLedger.readAll() }.getOrDefault(emptyList())
        return PackInput(
            criteriaVersion = version,
            criteria = ImmutableCriteria.ALL.map { PackCriterion(it.cn, it.lawRef) },
            pitTotal = rows.size,
            pits = orderPits(rows),
            lastUpdatedAtMs = rows.maxOfOrNull { it.atMs },
            fullPath = DecisionLedger.installedRoot()?.let {
                File(it, JudgmentExperienceLedger.FILE_NAME).absolutePath
            },
        )
    }

    /** 取数口径：先分等级（撞法条的违规 > 归因 > 重复问 > 其余），同级里新的在前，取前 [PIT_TOP] 条。 */
    fun orderPits(rows: List<ExperienceRow>): List<PackPit> = rows.sortedWith(
        compareBy<ExperienceRow> { PIT_RANK[it.kind] ?: NOT_A_PIT }.thenByDescending { it.atMs },
    ).take(PIT_TOP).map { PackPit(it.atMs, "${it.kind.wire} ${it.cardClass}", clip(it.reason, 60)) }

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    /**
     * 拼在 `systemFinal` 尾巴上的那一段（`RunEngine` 是本方法唯一的生产调用点）。
     *
     * 返回串自带前置空行；版本键没变就复用上一次渲染的字节（判据②「不重复增长」）。
     *
     * 第五百六十八批 A：这一格现在归**提示词尾部域**（473 登记表 `PROMPT_TAIL`，缺省灰度）——
     * 先问档再发文本：档位关闭 ⇒ 尾段不发（返回空串，候选保留在账上）；红线的拒由共用门出
     * （尾部候选的固定标 `system:tail` 恒净 ⇒ 生产上不会误拒）；版本键变更且放行 ⇒
     * 落一条灰度观察账（一段新内容进了灰度注入；版本没变不重复记——同判据②的口径）。
     * 「零拦截」的本义没破：仍不判动作、不改三态、不 import GuardPipeline；
     * 变的只是"这段文本本身也是一项被灰度管的自动化"（436 的四项裁定在尾段发不发这一格上
     * 多了一个主人，其余逐字不变）。
     */
    @Synchronized
    fun systemSuffix(nowMs: Long = System.currentTimeMillis()): String {
        val verdict = LowRiskEvo.gate(CanaryCapability.PROMPT_TAIL, TAIL_CANDIDATE)
        if (verdict is LowRiskVerdict.Refuse) {
            LowRiskEvo.recordRefusal(CanaryCapability.PROMPT_TAIL, verdict.redLine, verdict.note, TAIL_CANDIDATE, nowMs)
            return ""
        }
        if (verdict is LowRiskVerdict.Hold) return ""
        val input = snapshot()
        val key = versionKey(input, nowMs)
        if (key != lastKey) {
            lastKey = key
            lastText = render(input, nowMs)
            LowRiskEvo.observeRun(CanaryCapability.PROMPT_TAIL, ok = true, atMs = nowMs)
        }
        return "\n\n" + lastText
    }

    /** 568-A：尾部候选的固定标（尾段没有独立目标键；红线扫 target＋body，恒净 ⇒ 不误伤）。 */
    private val TAIL_CANDIDATE = EvoCandidate(
        domain = CanaryCapability.PROMPT_TAIL.wire,
        target = "system:tail",
    )

    /** 测试专用：清掉去重缓存（谁用谁还，与 224 批「装配落点谁装谁还」同一条规矩）。 */
    @Synchronized
    fun resetForTest() {
        lastKey = ""
        lastText = ""
    }
}
