// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十九批（2026-11-11）· 进化判定样本的**口径**（纯判据：零 IO、零时钟、零 Android）。
//
// 派工单原话：「进化判定样本量不够（几条沟只有个位数）⇒ 扩样本，让灰度/转正有统计意义」。
// 568-A 挂的账在这里兑现：四个低风险域接进既有链之后，观察账是落了，但**几条沟只有个位数**
// ——个位数上算出来的失败率不是读数，是噪声。本文件只定一件事：**多少次算够**。
//
// 三条线（写死在这里，不读配置、不读模型自述、不从别处抄）：
//   · **转正**：≥20 次独立事件 **且** ≥7 天窗口（两个都要：20 次挤在同一天＝一次性事件，
//     7 天里只跑 3 次＝没跑过，两种都不构成统计意义）；
//   · **灰度**：≥8 次（灰度是"能跑，但每一步都记账"，8 次够看清有没有坏消息）；
//   · **不足**：<8 次 ⇒ 只能判「样本不足」，**fail-closed**——不许拿"看起来还行"顶上去。
//
// 为什么 20/7/8 三个数不跟 473（`CanaryLadder.MIN_RUNS`＝10、`WINDOW_DAYS`＝14）合并：
// 那是**指标窗口**（近 14 天跑成什么样），这是**样本门槛**（够不够格谈档位）。两件事各算各的，
// 合并任何一边都是改 495/517 的聚合口径——派工单 ⑤ 明令不许。本文件因此**一个既有常量都不引用**，
// 三个数在下面写成自己的常量；`EvoSampleGateTest` 里那两条「判据⑤」用例反向钉住 473/495/517
// 的三个字面量与"照数不重"的计数口径没被顺手改动。
//
// ── 拍板补丁（2026-11-11 晚，⑪⑫⑬）：**一条样本得先有"锚"** ────────────────────
// 「独立事件」这句话要落地，得先回答"凭什么说这是**同一件事**"。所以每个域声明一种**锚类型**
// （[EvoAnchorKind]，闭集，[EvoAnchorRegistry] 是唯一真相；568-B 起按派工逐批扩格）：
//   · 技能＝候选提案、提示词尾部＝尾段版本、记忆组织＝收编批次、调度降频＝抽检轮次；
//   · **同一域内同一锚值只算一次**（⑪：暗卫连开五次若都在同一轮，就是同一件事的五次回声 ⇒ 计 1）；
//   · **没有锚的域不计**，且在读数里标出来（⑫）——不给"没定义什么叫一次"的域发样本数；
//   · 池里那一行的 anchorKind 必须与注册表一致，对不上的行**不算数**（⑬）。
//
// 去重（派工单 ③ 的两条 ＋ 上面那条锚，都在 [fold] 里，全是读侧纯折叠 ⇒ 重放幂等）：
//   · **同会话同一事件只算一次**：键＝(会话, 事件)；
//   · **同域同一锚值只算一次**：键＝(锚类型, 锚值)——⑪ 落在这里；
//   · **同一用户连续同类动作合并计数**（防刷）：同会话相邻两条、动作类相同、间隔 ≤[BURST_GAP_MS]
//     ⇒ 只算一次。人连点十下不是一个独立事件；但隔一天的同类动作是两次独立选择，照数。
//
// 边界：不碰 `Canary.kt`／`EvolutionDepth.kt`／`ConsequenceWiring.kt` 的聚合；本文件不落盘
// （落盘在 `EvoSamplePool`），不记账（记账在 `EvoSampleWiring.promote`）。
package com.example.zhengdao.baopu.evolution.samples

import com.example.zhengdao.baopu.consequence.CanaryCapability

/**
 * 锚类型（**闭集**）：这一域的"一次独立事件"按什么认身份。
 *
 * 线值落盘就是稳定键（[wire]），改字面量＝老行读不出来（同 `ConsequenceEvidence` 的纪律）。
 */
internal enum class EvoAnchorKind(val wire: String, val cn: String) {

    /** 技能：一次候选提案（锚值＝提案 id）。 */
    SKILL_PROPOSAL("skill_proposal", "技能候选提案"),

    /** 提示词尾部：一次尾段版本（锚值＝版本号）。 */
    TAIL_VERSION("tail_version", "提示词尾段版本"),

    /** 记忆组织：一次收编批次（锚值＝批次 id）。 */
    IMPORT_BATCH("import_batch", "记忆收编批次"),

    /** 调度降频／暗卫：一趟抽检轮次（锚值＝轮次；同一轮里连开几次是同一件事）。 */
    PATROL_ROUND("patrol_round", "抽检轮次"),

    /**
     * 568-B 对话域（判定阈值／相处规则候选）：一次对话轮次（589 拍板①：锚＝`turn:` 标——
     * 同一轮里的多次事件是同一件事，只计 1；没有轮次可取时兜底 `at:`，不硬造轮次号）。
     */
    CONV_TURN("conv_turn", "对话轮次"),

    /**
     * 568-B 后台域（目标模板候选：收尾家族的闭合事件）：一小时的桶（589 拍板②：
     * 锚＝`bucket:<1 小时桶>`——一小时里连收几次＝同一次条件成熟，只计 1；不是 runId）。
     */
    HOURLY_BUCKET("hourly_bucket", "后台一小时桶"),

    /**
     * 568-C 域①工具白名单：一次白名单变更事件（锚值＝变更前后的稳定串对——同一对 before→after
     * 的多次回声只计 1；没有身份可取时兜底 `at:`，不硬造事件号）。
     */
    WHITELIST_EVENT("whitelist_event", "白名单变更事件"),

    /**
     * 568-C 域②路由：一次路由变更事件（锚值＝变更前后的稳定串对——同一对 before→after
     * 的多次回声只计 1；没有身份可取时兜底 `at:`，不硬造事件号）。
     */
    ROUTE_EVENT("route_event", "路由变更事件"),
    ;

    companion object {
        /** 线值 ⇒ 锚类型；认不出 ⇒ null（自造锚不渲染，也不静默当任何一种）。 */
        fun ofWire(wire: String): EvoAnchorKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 锚注册表（唯一真相）：哪个域声明了哪种锚。
 *
 * **不在表里＝无锚**（⑫）。无锚域的样本不计、读数里标注，而不是给它编一个"其实也差不多"的锚——
 * 判据里那句"没定义什么叫一次，就别报次数"是这张表存在的全部理由。
 *
 * 四域取自 568-A 的 [CanaryCapability] 四格（技能／提示词尾部／记忆组织／调度降频）；
 * 其余能力（编排、线上对拍、人设自整理、御医、事件触发、斥候、许愿流水线）今天**没有锚**，
 * 它们的样本会落进"不计"那一栏——要放开得先在这里补一行并说明"凭什么算一次"。
 */
internal object EvoAnchorRegistry {

    /** 已声明锚的域（568-A 四域起，568-B 三域、568-C 两域逐批扩格；不在表里＝无锚）。 */
    val DECLARED: Map<CanaryCapability, EvoAnchorKind> = mapOf(
        CanaryCapability.REFLECTION_AUTO_EDIT to EvoAnchorKind.SKILL_PROPOSAL,
        CanaryCapability.PROMPT_TAIL to EvoAnchorKind.TAIL_VERSION,
        CanaryCapability.MEMORY_IMPORT to EvoAnchorKind.IMPORT_BATCH,
        CanaryCapability.NOCTURNAL_PATROL to EvoAnchorKind.PATROL_ROUND,
        // 568-B 域①判定阈值：对话域（未批准/同意都发生在会话轮内）⇒ 锚＝轮次（589 拍板①）。
        CanaryCapability.JUDGMENT_THRESHOLD to EvoAnchorKind.CONV_TURN,
        // 568-B 域②相处规则候选：纠正发生在用户轮内（左相判档处）⇒ 锚＝轮次（同一轮的
        // 多次纠正回声只计 1；589 拍板①对话域 turn）。
        CanaryCapability.COMPANION_RULE to EvoAnchorKind.CONV_TURN,
        // 568-B 域③目标模板候选：收尾是后台家族的闭合事件（不在用户轮内）⇒ 锚＝1 小时桶
        // （589 拍板②：一小时连收几次＝同一次条件成熟，只计 1）。
        CanaryCapability.GOAL_TEMPLATE to EvoAnchorKind.HOURLY_BUCKET,
        // 568-C 域①工具白名单：一次变更事件（组授权/撤销发生在会话内）⇒ 锚＝变更事件本身。
        CanaryCapability.TOOL_WHITELIST to EvoAnchorKind.WHITELIST_EVENT,
        // 568-C 域②路由：一次变更事件（新 peer 准入/路由偏好调整）⇒ 锚＝变更事件本身。
        CanaryCapability.ENVOY_ROUTE to EvoAnchorKind.ROUTE_EVENT,
    )

    /** 这一域声明了哪种锚（null＝无锚：不计、标注）。 */
    fun of(capabilityWire: String): EvoAnchorKind? =
        CanaryCapability.ofWire(capabilityWire)?.let { DECLARED[it] }

    /**
     * 算锚值：调用方给了就用调用方的（它知道"这一次"的身份），没给就按类型兜。
     *
     * 兜底不是凑数——[EvoAnchorKind.PATROL_ROUND] 的"一轮"就是**当天**（暗卫每天最多一趟），
     * 所以同一天连开五次落到同一个 `round:<epochDay>` 上，⑪ 那句"只计 1 次"由此成立；
     * 其余各格的兜底是 `at:<毫秒>`，意思是"没有身份就拿时刻当身份"——它**不合并**任何东西，
     * 只是让行落得下去、读得回来，且不会假装比实际更独立。
     */
    fun anchorOf(kind: EvoAnchorKind, atMs: Long, supplied: String): String = when {
        supplied.isNotBlank() -> supplied
        kind == EvoAnchorKind.PATROL_ROUND -> "round:${atMs / EvoSampleRules.DAY_MS}"
        kind == EvoAnchorKind.HOURLY_BUCKET -> "bucket:${atMs / EvoSampleRules.HOUR_MS}"
        else -> "at:$atMs"
    }
}

/**
 * 样本门槛的裁决（**闭集三态**，没有第四种"再看看"）。
 *
 * 线值一旦落账/落读数就是稳定键（[wire]），改字面量＝老读数读不回来。
 */
internal enum class EvoSampleVerdict(val wire: String, val cn: String) {

    /** 够转正：≥20 次独立事件且 ≥7 天窗口。 */
    PROMOTE_READY("promote_ready", "够转正"),

    /** 只够灰度：≥8 次但还够不上转正的两条（次数或天数差一条）。 */
    CANARY_ONLY("canary_only", "只能灰度"),

    /** 样本不足：<8 次——这一档只许报"样本不足"，不许往前推。 */
    INSUFFICIENT("insufficient", "样本不足"),
    ;

    companion object {
        /** 线值 ⇒ 三态；认不出 ⇒ null（自造态不渲染，也不静默当"不足"）。 */
        fun ofWire(wire: String): EvoSampleVerdict? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一次**独立**样本事件（池里的一行）。
 *
 * @param domain 域＝473 登记表里的能力线值（[CanaryCapability.wire]）：不在册的域建不出事件。
 * @param anchorKind 锚类型（⑬：必须等于注册表给这一域声明的那一种，写错就建不出来）。
 * @param anchor 锚值（这一条证据的身份；同域同锚只算一次）。
 * @param sessionId 会话号（与 `DecisionDraft.sessionId` 同一把尺）；**-1（无会话）不许进池**。
 * @param eventId 事件号：同一会话内唯一（一次被灰度的执行就是一个事件）。
 * @param kind 动作类（防刷合并按它比）。
 * @param atMs 观察时刻，由调用方给（本包零时钟）。
 */
internal data class EvoSampleEvent(
    val domain: String,
    val anchorKind: String,
    val anchor: String,
    val sessionId: Long,
    val eventId: String,
    val kind: String,
    val atMs: Long,
) {
    init {
        require(CanaryCapability.ofWire(domain) != null) { "没登记在册的域「$domain」：样本池按登记表分文件，不许自造域" }
        require(EvoAnchorKind.ofWire(anchorKind) != null) { "认不出的锚类型「$anchorKind」：锚是闭集，不许自造" }
        require(EvoAnchorRegistry.of(domain)?.wire == anchorKind) {
            "域的锚类型与注册表不一致（$domain 声明的是 ${EvoAnchorRegistry.of(domain)?.wire ?: "无锚"}，" +
                "这条写的是 $anchorKind）：⑬ 池内 anchorKind 必须与注册表一致"
        }
        require(anchor.isNotBlank()) { "样本事件缺锚值：没有锚就没法说清「这是不是同一件事」" }
        require(sessionId >= 0L) { "样本事件缺会话（sessionId=$sessionId）：没有会话就算不了「同会话同一事件只算一次」" }
        require(eventId.isNotBlank()) { "样本事件缺事件号：没有事件号，去重和重放都没有依据" }
        require(kind.isNotBlank()) { "样本事件缺动作类：防刷合并按动作类比，空类会把不相干的动作并成一条" }
        require(atMs >= 0L) { "样本时刻由调用方给（本包零时钟），且不许为负" }
    }
}

/**
 * 去重之后的清点（数字全在这里，读侧不许再数一遍）。
 *
 * @param kept 去重＋锚合并＋防刷之后的**独立事件**条数（判据只看它）
 * @param droppedDuplicate 同会话同一事件被吞掉的条数（重放/重复读的痕迹）
 * @param droppedAnchor 同域同一锚值被吞掉的条数（⑪：同一件事的多次回声）
 * @param droppedBurst 连续同类动作被合并的条数（防刷吞掉的）
 */
internal data class EvoSampleTally(
    val kept: Int,
    val droppedDuplicate: Int,
    val droppedAnchor: Int,
    val droppedBurst: Int,
    val firstMs: Long,
    val lastMs: Long,
) {
    init {
        require(kept >= 0 && droppedDuplicate >= 0 && droppedAnchor >= 0 && droppedBurst >= 0) { "清点数不许为负" }
    }

    /** 窗口天数＝首末事件跨度（整天下取）。0 条 ⇒ 0 天。 */
    val days: Int get() = if (kept <= 1) 0 else ((lastMs - firstMs) / EvoSampleRules.DAY_MS).toInt()
}

/**
 * 一个域的样本读数（`evo_samples` 那一行的内容）。
 *
 * @param anchored 这一域有没有声明锚（⑫：无锚域**不计**，读数里标出来）
 */
internal data class EvoSampleReading(
    val domain: String,
    val anchorKind: String,
    val anchored: Boolean,
    val events: Int,
    val days: Int,
    val droppedDuplicate: Int,
    val droppedAnchor: Int,
    val droppedBurst: Int,
    val verdict: EvoSampleVerdict,
) {
    /** 够不够转正（判据③的"强行转正"就撞这一句；无锚域永远不够）。 */
    fun promotable(): Boolean = anchored && verdict == EvoSampleVerdict.PROMOTE_READY

    /**
     * 读数的一行（稳定键，别随手改格式）。
     *
     * 有锚：`evo_samples|<域>|<锚类型>|<次数>|<天数>|<结论>`
     * 无锚：`evo_samples|<域>|无锚|不计`（⑫：不发数字，只标出来）
     */
    fun line(): String = if (!anchored) {
        "$READING_KEY|$domain|无锚|不计"
    } else {
        "$READING_KEY|$domain|$anchorKind|$events 次|$days 天|${verdict.cn}"
    }

    /** 人读的一句（报告与日志用；与 [line] 同源，不另算一遍）。 */
    fun describe(): String = if (!anchored) {
        "$domain：无锚（没声明「一次独立事件」按什么认身份）⇒ 不计"
    } else {
        "$domain（锚 $anchorKind）：$events 次独立事件／$days 天窗口 ⇒ ${verdict.cn}" +
            (if (droppedDuplicate + droppedAnchor + droppedBurst == 0) "" else
                "（去重吞 $droppedDuplicate／同锚合并 $droppedAnchor／防刷合并 $droppedBurst）")
    }

    companion object {
        /** 读数键（派工单 ④ 点名的 `evo_samples`）。 */
        const val READING_KEY: String = "evo_samples"

        /** 一个域一条都没有时的读数（不是错误，是"还没跑过"）；无锚域照样标"无锚"。 */
        fun empty(domain: String): EvoSampleReading {
            val kind = EvoAnchorRegistry.of(domain)
            return EvoSampleReading(
                domain = domain,
                anchorKind = kind?.wire ?: "",
                anchored = kind != null,
                events = 0,
                days = 0,
                droppedDuplicate = 0,
                droppedAnchor = 0,
                droppedBurst = 0,
                verdict = EvoSampleVerdict.INSUFFICIENT,
            )
        }
    }
}

/** 样本口径本体（纯函数；IO 在 `EvoSamplePool`，装配在 `EvoSampleWiring`）。 */
internal object EvoSampleRules {

    /** 转正要的独立事件条数（派工单 ② 写死）。 */
    const val PROMOTE_EVENTS: Int = 20

    /** 转正要的窗口天数（派工单 ② 写死）。 */
    const val PROMOTE_DAYS: Int = 7

    /** 灰度要的独立事件条数（派工单 ② 写死）。 */
    const val CANARY_EVENTS: Int = 8

    /** 「连续同类动作」的连续性上限：间隔不超过它才算一次连点（超过就是两次独立选择）。 */
    const val BURST_GAP_MS: Long = 10L * 60_000L

    /** 一天（读数与窗口共用一把尺）。 */
    const val DAY_MS: Long = 86_400_000L

    /** 一小时（后台域 1h 桶锚的尺；589 拍板②）。 */
    const val HOUR_MS: Long = 3_600_000L

    /** 行格式版本（池文件的一行；改它＝老行读不出来）。 */
    const val SCHEMA: String = "zd-evo/1"

    /** 池目录名：`<工作区>/.zd-evo/`（点号开头 ⇒ 不计入「产物 N 件」，与 `.zd-ledger.jsonl` 同口径）。 */
    const val DIR_NAME: String = ".zd-evo"

    /**
     * 三层去重：同会话同一事件 → 同域同一锚值 → 同一用户连续同类动作。
     *
     * 排序先按时刻、再按 (会话, 事件, 锚) —— 同一毫秒的两条也要有确定次序，
     * 否则"重放两遍结果一样"就成了碰运气（判据④的一半在这句里）。
     */
    fun fold(events: List<EvoSampleEvent>): EvoSampleTally {
        if (events.isEmpty()) return EvoSampleTally(0, 0, 0, 0, 0L, 0L)
        val sorted = events.sortedWith(compareBy({ it.atMs }, { it.sessionId }, { it.eventId }, { it.anchor }))
        val seenEvent = HashSet<String>()
        val seenAnchor = HashSet<String>()
        val kept = ArrayList<EvoSampleEvent>(sorted.size)
        var duplicate = 0
        var sameAnchor = 0
        var burst = 0
        sorted.forEach { e ->
            if (!seenEvent.add(e.sessionId.toString() + "#" + e.eventId)) {
                duplicate++
                return@forEach
            }
            if (!seenAnchor.add(e.anchorKind + "#" + e.anchor)) {
                sameAnchor++
                return@forEach
            }
            if (isBurstOf(kept.lastOrNull(), e)) {
                burst++
                return@forEach
            }
            kept += e
        }
        return EvoSampleTally(
            kept = kept.size,
            droppedDuplicate = duplicate,
            droppedAnchor = sameAnchor,
            droppedBurst = burst,
            firstMs = kept.first().atMs,
            lastMs = kept.last().atMs,
        )
    }

    /** 是不是同一个人的一次连点（同会话＋同类＋间隔在 [BURST_GAP_MS] 内）。 */
    private fun isBurstOf(previous: EvoSampleEvent?, current: EvoSampleEvent): Boolean =
        previous != null &&
            previous.sessionId == current.sessionId &&
            previous.kind == current.kind &&
            current.atMs - previous.atMs <= BURST_GAP_MS

    /** 清点 ⇒ 三态（三条线在这里落地，别处不许再判一遍）。 */
    fun verdictOf(tally: EvoSampleTally): EvoSampleVerdict = when {
        tally.kept >= PROMOTE_EVENTS && tally.days >= PROMOTE_DAYS -> EvoSampleVerdict.PROMOTE_READY
        tally.kept >= CANARY_EVENTS -> EvoSampleVerdict.CANARY_ONLY
        else -> EvoSampleVerdict.INSUFFICIENT
    }

    /**
     * 一批事件 ⇒ 一个域的读数（线上唯一入口；调用方拿到的就是 `evo_samples` 那一行）。
     *
     * 无锚域（⑫）**不计**：一条都不数，`anchored=false`，读数里写"无锚|不计"。
     * 这里不是"数了不显示"，是根本不进 [fold]——显示层做减法容易被下一批改回去。
     */
    fun reading(domain: String, events: List<EvoSampleEvent>): EvoSampleReading {
        EvoAnchorRegistry.of(domain) ?: return EvoSampleReading.empty(domain)
        val tally = fold(events)
        return EvoSampleReading(
            domain = domain,
            anchorKind = EvoAnchorRegistry.of(domain)!!.wire,
            anchored = true,
            events = tally.kept,
            days = tally.days,
            droppedDuplicate = tally.droppedDuplicate,
            droppedAnchor = tally.droppedAnchor,
            droppedBurst = tally.droppedBurst,
            verdict = verdictOf(tally),
        )
    }
}
