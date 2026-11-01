// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十一批 · 左相的**打扰闸收窄**：让左相敢判，但每笔可查、可推翻。
//
// 出处：第三百三十一批派工单（基线 912e0ad6）§①「把必须问压到三类」。
//
// ## 判据（输入→输出，逐条可执行）
// 一件事进来，只问三个问题，**命中就题请，不命中就自决**：
// 1. [AskClass.IRREVERSIBLE]：**不可逆且不可回退**（[SelfRequest.irreversible] 为真
//    **且** [SelfRequest.rollback] 是 [Rollback.None]）。两个条件是**合取**——
//    可逆的、或虽不可逆但有回退手段的（回收站、备份、undo），都不在这一类。
// 2. [AskClass.BEYOND_GRANT]：**超出授权**（[Authorization] 不是 [Authorization.IN_SCOPE]）。
// 3. [AskClass.REAL_FAILURE]：**真失败要人决策**（[FailureSignal.isRealFailure]：
//    要人拍板，且重试不通、没有替代路径）。可重试的、能换条路的，都不算。
//
// **其余一律可自决**——延后、跳不可用能力、可回退的破坏性动作，以及三类都不命中的。
// 这一条是反向写的：[SelfRule] 里**没有「允许自决」的白名单**，只有「必须问」的黑名单。
// 加一类必须问要动 [AskClass]（枚举闭集，恰好三个），而放宽自决不用改任何代码——
// 派工单点名的负例「把可回退的删除也算必须问」因此从结构上落不下来。
//
// ## 与法典 L9 的关系（一处必须说清的越界）
// [com.example.zhengdao.baopu.law.DiscretionLadder]（L9）的 ② 把「含删除」列为四要素之一，
// 缺它即降 ③ 题请；本批的判据恰好相反：**可回退的删除属于自决**。
// 本批租约只到 policy/SelfRule.kt 与 policy/SelfDecideLog.kt，**没有动 L9 一个字节**
// （那一格归 332 之后的 L9 对齐单收口）。在两份口径合并之前，以本层为准的那部分只有
// 「要不要打扰人」这一问；L9 判的「最多做到哪一档」不动。这一点写在这里，免得下一个人
// 以为两处一致。
package com.example.zhengdao.baopu.policy

/**
 * 必须问的三类（**闭集，恰好三个**——[SelfRule.CLASS_COUNT] 由单测钉住）。
 *
 * 加第四个值的代价：`SelfRuleTest.必须问恰好三类` 立刻红，逼着改派工单而不是改代码。
 */
internal enum class AskClass(val wire: String, val cn: String) {
    /** 不可逆**且**没有回退手段（合取；任一条件不成立就不在这一类）。 */
    IRREVERSIBLE("irreversible", "不可逆且不可回退"),

    /** 超出已授权范围（范围外、没授权，都算）。 */
    BEYOND_GRANT("beyond_grant", "超出授权"),

    /** 真失败：要人拍板，且重试与替代路径都不通。 */
    REAL_FAILURE("real_failure", "真失败要人决策"),
}

/**
 * 自决的依据类别（**只用于归因，不参与判定**）。
 *
 * 与 [AskClass] 的关键差别：这里加一个值不会让任何东西变成「必须问」——
 * 它只是给自决单的「依据」一栏一个人读的归类。
 */
internal enum class SelfBasis(val wire: String, val cn: String) {
    /** 可以延后，先攒着。 */
    DEFER("defer", "延后"),

    /** 能力不可用（工具缺、权限缺、离线），跳过它，不打扰人。 */
    SKIP_UNAVAILABLE("skip_unavailable", "跳不可用能力"),

    /** 破坏性动作，但有回退手段（回收站／备份／undo）。 */
    REVERSIBLE_DESTRUCTIVE("reversible_destructive", "可回退的破坏性动作"),

    /** 三类必须问一条都不命中——这是绝大多数动作的归宿，不是异常。 */
    UNCLASSIFIED("unclassified", "三类都不命中"),
}

/** 授权状态（三态；只有 [IN_SCOPE] 不算超授权）。 */
internal enum class Authorization(val wire: String, val cn: String) {
    /** 在已授权范围内。 */
    IN_SCOPE("in_scope", "授权内"),

    /** 在范围外（授权存在，但这件事不在里面）。 */
    OUT_OF_SCOPE("out_of_scope", "超出授权范围"),

    /** 压根没拿到授权。 */
    NOT_GRANTED("not_granted", "没拿到授权"),
    ;

    /** 超没超授权（[BEYOND_GRANT][AskClass.BEYOND_GRANT] 的唯一判据）。 */
    val isBeyond: Boolean get() = this != IN_SCOPE
}

/**
 * 回退手段（两态，**不是**「动作可不可逆」）。
 *
 * [Ready] 必须写清怎么退——写不出「怎么退」的，就是 [None]。
 * 这一条挡的是「嘴上说可回退、真出事没处下手」那种自决。
 */
internal sealed class Rollback {

    /** 有回退手段，且写清怎么退（回收站恢复／备份名／undo 命令）。 */
    data class Ready(val how: String) : Rollback() {
        init {
            require(how.isNotBlank()) { "可回退必须写清回退方式：写不出怎么退，就等于不可回退" }
        }
    }

    /** 没有回退手段。 */
    object None : Rollback()
}

/**
 * 一次失败的信号（**只说事实，不下结论**）。
 *
 * 「真失败」的判据只有 [isRealFailure] 一处：要人拍板，**且**重试不通、没有替代路径。
 * 可重试的失败（网络抖、锁竞争）与有替代路径的失败（换工具、跳过该能力）都不打扰人。
 */
internal data class FailureSignal(
    val retryable: Boolean,
    val hasWorkaround: Boolean,
    val needsHuman: Boolean,
) {
    /** 真失败要人决策（[AskClass.REAL_FAILURE] 的唯一判据）。 */
    val isRealFailure: Boolean get() = needsHuman && !retryable && !hasWorkaround
}

/**
 * 一件待判的事（事实全部由调用方给；本层**不自己分类**，免得出现两套口径）。
 *
 * [authorization] 故意**不给默认值**：默认成「授权内」等于把放行写成沉默的默认，
 * 而那正是本批要收的口。其余布尔值默认「不触发那一类」，语义与字段名一致。
 */
internal data class SelfRequest(
    /** 动作标识：自决单与推翻都要能点名到它。 */
    val actionId: String,
    /** 动作原文（人读的一句）。 */
    val action: String,
    /** 授权状态（必填，无默认）。 */
    val authorization: Authorization,
    /** 做了就回不去（删除／外发／扣款）。注意：与「有没有回退手段」是两件事。 */
    val irreversible: Boolean = false,
    /** 回退手段（默认没有）。 */
    val rollback: Rollback = Rollback.None,
    /** 失败信号（null ＝ 没失败）。 */
    val failure: FailureSignal? = null,
    /** 能不能延后（能延后就不必现在打扰人）。 */
    val deferrable: Boolean = false,
    /** 这个能力现在可用不可用（不可用就跳过，不打扰人）。 */
    val capabilityAvailable: Boolean = true,
    /** 是不是破坏性动作（删／覆盖／改状态）；只影响自决的归因，不影响判定。 */
    val destructive: Boolean = false,
)

/**
 * 一次判定的结论。
 *
 * 只有两种结局：**必须问**（[askClass] 非空、[basis] 为空）或**自决**（反过来）。
 * 「判不了」不是第三种结局——三类都不命中就是自决，这是本批要的「敢判」。
 */
internal data class SelfJudgement(
    val request: SelfRequest,
    /** 必须问的类别；null ＝ 自决。 */
    val askClass: AskClass?,
    /** 自决的依据类别；null ＝ 必须问。 */
    val basis: SelfBasis?,
    /** 一句人读的判词（进自决单的「依据」一栏，也进日志）。 */
    val why: String,
) {
    /** 必须打扰人。 */
    val mustAsk: Boolean get() = askClass != null

    /** 可以自己判（每笔仍要落自决单，见 [SelfDecideLog.record]）。 */
    val mayDecide: Boolean get() = askClass == null
}

/** 左相打扰闸的唯一判据实现（纯函数，零 IO、零时钟、JVM 直测）。 */
internal object SelfRule {

    /** 必须问的类数（派工单：三类；改这个数要先改派工单）。 */
    const val CLASS_COUNT = 3

    /** 三类必须问（顺序即判定顺序：先不可逆，再授权，最后失败）。 */
    fun classes(): List<AskClass> = AskClass.entries

    /**
     * 判一件事要不要打扰人。
     *
     * @throws IllegalArgumentException [SelfRequest.actionId] 或 [SelfRequest.action] 为空
     *   （点不出名、说不出是什么动作的判定，落不进可查的自决单）
     */
    fun judge(request: SelfRequest): SelfJudgement {
        require(request.actionId.isNotBlank()) { "自决判定必须点名是哪个动作：actionId 不许为空" }
        require(request.action.isNotBlank()) { "自决单要有动作原文：action 不许为空" }
        val hit = askClass(request)
        return if (hit == null) {
            SelfJudgement(request, null, basisOf(request), selfWhy(request))
        } else {
            SelfJudgement(request, hit, null, askWhy(hit, request))
        }
    }

    /**
     * 命中哪一类必须问；都不命中回 null（＝自决）。
     *
     * 顺序写死（不可逆→授权→失败），**首命中即返回**：一件又超授权又不可逆的事，
     * 记在不可逆那一类下——因为它是更硬的那条理由。
     */
    fun askClass(request: SelfRequest): AskClass? = when {
        request.irreversible && request.rollback is Rollback.None -> AskClass.IRREVERSIBLE
        request.authorization.isBeyond -> AskClass.BEYOND_GRANT
        request.failure?.isRealFailure == true -> AskClass.REAL_FAILURE
        else -> null
    }

    /**
     * 自决的归因（顺序：跳不可用能力 → 延后 → 可回退的破坏性 → 兜底）。
     *
     * 「可回退的破坏性」要求**真有回退手段**：破坏性动作若既不可逆又没手段，早在
     * [askClass] 就命中第一类了，走不到这里；剩下「破坏性、自身可逆、但没写怎么退」的，
     * 归「三类都不命中」——不拿「可回退」三个字骗人。
     */
    private fun basisOf(request: SelfRequest): SelfBasis = when {
        !request.capabilityAvailable -> SelfBasis.SKIP_UNAVAILABLE
        request.deferrable -> SelfBasis.DEFER
        request.destructive && request.rollback is Rollback.Ready -> SelfBasis.REVERSIBLE_DESTRUCTIVE
        else -> SelfBasis.UNCLASSIFIED
    }

    /** 必须问的判词（要能把触发它的那两个事实说出来，否则推翻时无从下手）。 */
    private fun askWhy(hit: AskClass, request: SelfRequest): String = when (hit) {
        AskClass.IRREVERSIBLE ->
            "${hit.cn}：${request.action}（不可逆＝${request.irreversible}，回退手段＝${rollbackText(request.rollback)}）"
        AskClass.BEYOND_GRANT ->
            "${hit.cn}：${request.authorization.cn}——${request.action}"
        AskClass.REAL_FAILURE ->
            "${hit.cn}：${request.action}（不可重试、无替代路径，只有人能定）"
    }

    /** 自决的判词（同样要说清凭什么）。 */
    private fun selfWhy(request: SelfRequest): String {
        val basis = basisOf(request)
        val tail = when (basis) {
            SelfBasis.SKIP_UNAVAILABLE -> "能力不可用，跳过它，不打扰人"
            SelfBasis.DEFER -> "可以延后，先攒着，不打扰人"
            SelfBasis.REVERSIBLE_DESTRUCTIVE ->
                "破坏性但可回退（${rollbackText(request.rollback)}），做完留痕即可"
            SelfBasis.UNCLASSIFIED -> "三类必须问一条都不命中"
        }
        return "自决（${basis.cn}）：$tail"
    }

    /** 回退手段的人读文本。 */
    private fun rollbackText(rollback: Rollback): String = when (rollback) {
        is Rollback.Ready -> rollback.how
        Rollback.None -> "无"
    }
}
