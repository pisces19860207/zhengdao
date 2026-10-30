// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十九批：六步放行（§5「对象→动作→上限→protected/hardDeny→影子三态→执行落 208」）。
//
// 三条纪律钉在这个文件的形状上：
// 1. **顺序就是代码顺序**：六步一步不许跳、一步不许并；每步都留一条 [StepReading]，
//    交付报告里的「六步真实读数」就是把这些读数原样打出来（不另写一套说辞）；
// 2. **越界只问增量**：对象/动作不在授权里 ⇒ [GateOutcome.ASK_DELTA]，问的**只有这次要的那一个**，
//    绝不把整单再列一遍（§6）；
// 3. **影子三态**：只有 `CONSISTENT` 放行；`UNVERIFIABLE`／没跑 ⇒ 停在 NO_DATA，
//    不拖成「无限弹卡」（§7）。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskCode
import com.example.zhengdao.baopu.RiskVerdict
import com.example.zhengdao.baopu.inspect.ShadowAdapter
import com.example.zhengdao.baopu.inspect.ShadowInput
import com.example.zhengdao.baopu.inspect.ShadowVerdict

/** 六步（`wire` 是交付报告与账本的稳定键；改字面量＝老读数读不出步骤）。 */
internal enum class GrantStep(val wire: String, val cn: String) {
    OBJECTS("objects", "对象"),
    ACTIONS("actions", "动作"),
    LIMITS("limits", "上限"),
    BOUNDARY("boundary", "protected/hardDeny"),
    SHADOW("shadow", "影子三态"),
    RECORD("record", "执行落 208"),
    ;

    companion object {
        fun ofWire(wire: String): GrantStep? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一步的读数（`passed=null` ＝这一步**验不了**，不算通过）。 */
internal data class StepReading(val key: GrantStep, val passed: Boolean?, val detail: String) {

    /** 交付报告/GUI 的「N 步」写法（不给人话之外的解码）。 */
    fun line(): String = "${key.cn}=" + when (passed) {
        true -> "过"
        false -> "不过"
        null -> "未验"
    } + "（$detail）"
}

/** 放行结论（**闭集**；四种「没放行」的处置各不相同）。 */
internal enum class GateOutcome(val wire: String, val cn: String) {
    /** 授权内放行（六步全过）。 */
    ALLOW("allow", "授权内放行"),

    /** 越界 ⇒ **只问这次增量**。 */
    ASK_DELTA("ask_delta", "只问增量"),

    /** 走单次人批（没有授权/授权覆盖不到这类事）。 */
    ASK_SINGLE("ask_single", "改走单次人批"),

    /** 硬拒（受保护对象/硬拒风险）：连问都不问。 */
    REJECT("reject", "拒绝"),

    /** 停（读数拿不到、影子不成立、限额用尽）——不弹卡，直接报 NO_DATA。 */
    STOPPED("stop", "停下"),
    ;

    companion object {
        fun ofWire(wire: String): GateOutcome? = entries.firstOrNull { it.wire == wire }
    }
}

/** 影子的读数（**每个动作都要有**；`None` ＝ 没跑）。 */
internal sealed interface ShadowSignal {

    /** 这个动作一次影子预演都没跑 ⇒ 不许放行（NO_DATA）。 */
    data object None : ShadowSignal

    /**
     * 跑了，结论是 [verdict]（四态；只有 CONSISTENT 放行）。
     *
     * [reason]（第二百九十六批）：`UNVERIFIABLE` 的**原因码**（`UNSUPPORTED`／`NO_SANDBOX`／
     * `STRUCTURE`／`NO_TARGET`）。为什么要跟着结论走：四态只说"跑不出结论"，原因才说得出
     * "下一批接谁才能开自动档"——原因掉了，六步第⑤步的读数就永远只是一句"影子跑不出结论"。
     */
    data class Rehearsed(val verdict: ShadowVerdict, val reason: String = "") : ShadowSignal
}

/**
 * protected / hardDeny 这两件事的事实（由调用方从 219 的 [RiskVerdict] 翻过来）。
 *
 * 为什么不直接收 [RiskVerdict]：六步里第 4 步只关心「碰没碰受保护对象」「是不是硬拒」，
 * 把整份判定塞进来会让这一层跟着矩阵的字段漂；[boundaryFactsOf] 是唯一的翻译口。
 */
internal data class BoundaryFacts(
    /** 原件区／密码框（219 的 `raw_zone`／`sensitive_field`）。 */
    val protectedHit: Boolean = false,
    /** 矩阵判的硬拒（私有 URL、白名单外、提交类按钮…）。 */
    val hardDeny: Boolean = false,
)

/** [RiskVerdict] ⇒ 第 4 步要的两个事实（唯一翻译口，别处不许自己判一次）。 */
internal fun boundaryFactsOf(verdict: RiskVerdict): BoundaryFacts = BoundaryFacts(
    protectedHit = verdict.reasonCodes.any {
        it == RiskCode.PATH_ORIGINAL_RAW || it == RiskCode.PASSWORD_FIELD
    },
    hardDeny = verdict.hardDeny,
)

/** 矩阵说「这次必须单独问」吗（R3／未分类／表点名必问都算）。 */
internal fun mustAskIndependentlyOf(verdict: RiskVerdict): Boolean =
    verdict.mustAsk || verdict.risk == RiskClass.R3

/** 一次动作请求（六步的全部输入；本层不读时钟、不查盘）。 */
internal data class ActionRequest(
    /** 动作的稳定 id（**一个动作一个**；账本与影子都按它去重）。 */
    val opId: String,
    /** 引擎侧的请求 id（同一个 opId 被重跑时不变；只用于对账）。 */
    val engineRequestId: String = "",
    val sessionId: Long,
    val taskId: String,
    /** 对象 id（也接受句柄写法）。 */
    val objectId: String,
    val action: GrantActionType,
    /** 本次要落盘的字节数（超单文件/总写入上限就停）。 */
    val candidateBytes: Long,
    val risk: RiskClass,
    /** 矩阵说必须单独问（R3/未分类/必问）⇒ 授权不许覆盖。 */
    val mustAskIndependently: Boolean = false,
    val boundary: BoundaryFacts = BoundaryFacts(),
    val shadow: ShadowSignal = ShadowSignal.None,
    /**
     * 第二百九十六批 §3：第⑤步**去问适配器**时要带的那份输入（谁、改哪个对象、写多少字节）。
     *
     * 为什么做成可注入的字段而不是让第⑤步自己去构造：本层零 IO 是 249 钉死的纪律
     * （[TaskGrantSourceGateTest] 的「授权层不带IO」），副本／快照／diff 全都归沙盒。
     * 没给 ⇒ 第⑤步照旧 `None`（停在 NO_DATA），一个字节都不碰盘——249/276 那批纯判定测试
     * 因此逐字不变，真跑的只有交了输入的那一条路径。
     */
    val shadowInput: ShadowInput? = null,
    /** 动作开始前的对象状态（改正文必须与快照里的那一版对得上）。 */
    val before: ObjectState = ObjectState.Absent,
    val nowMonoMs: Long = 0L,
    val processEpoch: String = "",
)

/** 越界时问的那**一问**（§6：只列增量，绝不整单重问）。 */
internal data class DeltaQuestion(
    /** 增量对象（只有这次要的那一个）。 */
    val deltaObjects: List<String>,
    /** 增量动作（授权里已经有了 ⇒ 空表：这次问的是对象）。 */
    val deltaActions: List<GrantActionType>,
    /** 触发这一问的对象。 */
    val askedObject: String,
) {
    /** 去重键：同一增量不重复弹卡（合并计数由确认卡那一层做）。 */
    val key: String
        get() = "delta:" + deltaObjects.sorted().joinToString(",") + "|" +
            deltaActions.sortedBy { it.wire }.joinToString(",") { it.wire }
}

/** 六步走完的结论（[steps] 原样给交付报告/勘验；[delta] 只在 ASK_DELTA 时非空）。 */
internal data class GateDecision(
    val outcome: GateOutcome,
    /** 结论码（稳定键；一份动作只有一条，便于抽检按码统计）。 */
    val code: String,
    val steps: List<StepReading> = emptyList(),
    val delta: DeltaQuestion? = null,
    val opId: String = "",
    /** 208 的幂等键（`<opId>:applied`；一动作两账的判据就是它）。 */
    val ledgerKey: String = "",
    /** 执行侧落地后要重新比对的「执行前状态」。 */
    val expectedBefore: ObjectState = ObjectState.Absent,
) {
    val allow: Boolean get() = outcome == GateOutcome.ALLOW

    /** 过了几步（只数 `passed==true`）。 */
    val passedSteps: Int get() = steps.count { it.passed == true }

    /** 停在哪一步（全过 ⇒ null）。 */
    val stoppedAt: GrantStep? get() = steps.lastOrNull()?.takeIf { it.passed != true }?.key
}

/** 六步放行（纯函数：给定请求与内存状态，回答「这一步怎么走」）。 */
internal object TaskGrantGate {

    const val CODE_ALLOW = "allow"
    const val CODE_NO_GRANT = "no_grant"
    const val CODE_PROCESS_CHANGED = "process_changed"
    const val CODE_FOREIGN_TASK = "foreign_task"
    const val CODE_EXPIRED = "expired"
    const val CODE_WHOLE_WORKSPACE = "whole_workspace"
    const val CODE_OBJECT_OUT = "object_out"
    const val CODE_SHARED_FILE = "shared_file"
    const val CODE_STATE_CHANGED = "state_changed"
    const val CODE_ACTION_BANNED = "action_banned"
    const val CODE_ACTION_OUT = "action_out"
    const val CODE_ACTION_LIMIT = "action_limit"
    const val CODE_FILE_TOO_BIG = "file_too_big"
    const val CODE_TOTAL_TOO_BIG = "total_too_big"
    const val CODE_HARD_DENY = "hard_deny"
    const val CODE_PROTECTED = "protected"
    const val CODE_RISK_TOO_HIGH = "risk_too_high"
    const val CODE_NO_DATA = "no_data"
    const val CODE_SHADOW_MISMATCH = "shadow_mismatch"
    const val CODE_SHADOW_MISWIRED = "shadow_miswired"
    const val CODE_NO_OP_ID = "no_op_id"
    const val CODE_DUPLICATE_OP = "duplicate_op"

    /** 208 的幂等键（**唯一**的拼法：账本、勘验、门禁三处都从这里取）。 */
    fun ledgerKeyOf(opId: String): String = "$opId:applied"

    /**
     * 影子三态**只有一种**放行。
     *
     * 单独抽成一个函数是为了让「UNKNOWN 也放行」这种改法只改一行、并且立刻有一条负例在响
     * （§7 负例⑥）。
     */
    internal fun shadowAllows(verdict: ShadowVerdict): Boolean = verdict == ShadowVerdict.CONSISTENT

    /**
     * 第⑤步的**取数口**（第二百九十六批 §3：判据⑤「第⑤步有真实调用点」打的就是这一行）。
     *
     * 这一句问出去之后发生的事：沙盒在 `<工作区>/.shadow/<opId>/` 里把这次动作复刻一遍，
     * 判据层（[ShadowAdapter] → `ShadowRehearsal.compare`）交回四态，本函数只翻成 [ShadowSignal]。
     * · 构造层没交输入 ⇒ [ShadowSignal.None]（连问都没问，与 249 交付时同一态）；
     * · 工具没接适配器／沙盒没装／副本建不起 ⇒ `UNVERIFIABLE` ＋原因码，第⑤步停在 NO_DATA，
     *   **一张卡都不省**——没有"仍然执行"那颗按钮，本层唯一认的免卡凭据是 CONSISTENT。
     *
     * 为什么判定层可以"问"而不能"跑"：[ShadowAdapter] 自己一字节盘都不写（落账走它自己的
     * 账本、建副本走注入的沙盒口），碰盘的那份实现住在装配层；「授权层不带IO」那条门禁
     * 扫的是本文件的原文（注释也扫），所以这里连一个 IO 字样都不出现。
     */
    internal fun shadowSignalFor(input: ShadowInput?, log: (String) -> Unit = { }): ShadowSignal {
        if (input == null) return ShadowSignal.None
        val run = ShadowAdapter.rehearse(input, log = log)
        return ShadowSignal.Rehearsed(run.outcome.verdict, run.reason)
    }

    fun evaluate(request: ActionRequest, state: GrantState?): GateDecision {
        val steps = mutableListOf<StepReading>()
        fun decide(outcome: GateOutcome, code: String, delta: DeltaQuestion? = null): GateDecision =
            GateDecision(
                outcome = outcome,
                code = code,
                steps = steps.toList(),
                delta = delta,
                opId = request.opId,
                ledgerKey = ledgerKeyOf(request.opId),
                expectedBefore = request.before,
            )

        // ① 对象：先识破「整个工作区」，再核对清单、进程、任务、有效期、落点、版本
        if (TaskGrantRules.isWholeWorkspaceObject(request.objectId)) {
            steps += StepReading(
                GrantStep.OBJECTS,
                false,
                "对象是「${request.objectId}」＝整个工作区，不是可点的文件清单",
            )
            return decide(GateOutcome.ASK_SINGLE, CODE_WHOLE_WORKSPACE)
        }
        val live = state?.takeIf { it.alive }
        val grant = live?.grant
        if (live == null || grant == null) {
            steps += StepReading(
                GrantStep.OBJECTS,
                false,
                "没有生效的任务级授权（${state?.endReason?.wire ?: "none"}）",
            )
            return decide(GateOutcome.ASK_SINGLE, CODE_NO_GRANT)
        }
        // 进程重启 ⇒ 老快照作废（§2 第四要素；判定用的是**代号**，不是时长）。
        if (request.processEpoch != grant.processEpoch || live.epoch != request.processEpoch) {
            steps += StepReading(
                GrantStep.OBJECTS,
                false,
                "进程代号对不上（快照 ${grant.processEpoch.take(8)}／当前 ${request.processEpoch.take(8)}）",
            )
            return decide(GateOutcome.ASK_SINGLE, CODE_PROCESS_CHANGED)
        }
        if (request.taskId != grant.taskId) {
            steps += StepReading(GrantStep.OBJECTS, false, "这次是任务 ${request.taskId}，授权是 ${grant.taskId}")
            return decide(GateOutcome.ASK_SINGLE, CODE_FOREIGN_TASK)
        }
        if (request.nowMonoMs >= grant.expiresMonoMs) {
            steps += StepReading(GrantStep.OBJECTS, false, "授权已到期（${grant.expiresMonoMs} ≤ ${request.nowMonoMs}）")
            return decide(GateOutcome.ASK_SINGLE, CODE_EXPIRED)
        }
        val obj = grant.objectOf(request.objectId)
        if (obj == null) {
            steps += StepReading(GrantStep.OBJECTS, false, "对象 ${request.objectId} 不在授权清单里")
            return decide(GateOutcome.ASK_DELTA, CODE_OBJECT_OUT, deltaFor(request, grant))
        }
        // 共享目录**不进** v1 支持域（§3）：授权只对受控任务仓里的对象有效。
        if (obj.location != ObjectLocation.CONTROLLED_TASK_STORE) {
            steps += StepReading(
                GrantStep.OBJECTS,
                false,
                "对象 ${obj.objectId} 在${obj.location.cn}，v1 不支持",
            )
            return decide(GateOutcome.ASK_SINGLE, CODE_SHARED_FILE)
        }
        if (request.before != obj.state) {
            steps += StepReading(
                GrantStep.OBJECTS,
                false,
                "对象 ${obj.objectId} 的现状与快照对不上（快照 ${obj.state.wire}／现在 ${request.before.wire}）",
            )
            return decide(GateOutcome.ASK_SINGLE, CODE_STATE_CHANGED)
        }
        steps += StepReading(
            GrantStep.OBJECTS,
            true,
            "对象 ${obj.handle} 在清单内（${obj.location.cn}，${obj.state.wire}）",
        )

        // ② 动作：先看这类动作允不允许授权，再看这份授权里有没有
        if (!request.action.grantable) {
            steps += StepReading(
                GrantStep.ACTIONS,
                false,
                "${request.action.cn} 是 §2 点名的排除项，永远不可授权",
            )
            return decide(GateOutcome.ASK_SINGLE, CODE_ACTION_BANNED)
        }
        if (!grant.allowsAction(request.action)) {
            steps += StepReading(GrantStep.ACTIONS, false, "这份授权里没有 ${request.action.cn}")
            return decide(GateOutcome.ASK_DELTA, CODE_ACTION_OUT, deltaFor(request, grant))
        }
        steps += StepReading(GrantStep.ACTIONS, true, "${request.action.cn} 在授权内")

        // ③ 上限（§2 程度：超了就不算数，不再问、直接停）
        val budget = grant.budget.clamped()
        if (request.candidateBytes < 0L) {
            steps += StepReading(GrantStep.LIMITS, false, "本次字节数为负（${request.candidateBytes}）")
            return decide(GateOutcome.ASK_SINGLE, CODE_FILE_TOO_BIG)
        }
        if (request.candidateBytes > budget.maxBytesPerFile) {
            steps += StepReading(
                GrantStep.LIMITS,
                false,
                "本次 ${request.candidateBytes}B 超过单文件上限 ${budget.maxBytesPerFile}B",
            )
            return decide(GateOutcome.STOPPED, CODE_FILE_TOO_BIG)
        }
        if (live.actionsUsed + 1 > budget.maxActions) {
            steps += StepReading(
                GrantStep.LIMITS,
                false,
                "动作 ${live.actionsUsed + 1} 次超过上限 ${budget.maxActions}",
            )
            return decide(GateOutcome.STOPPED, CODE_ACTION_LIMIT)
        }
        if (live.bytesWritten + request.candidateBytes > budget.maxBytesTotal) {
            steps += StepReading(
                GrantStep.LIMITS,
                false,
                "累计写入 ${live.bytesWritten + request.candidateBytes}B 超过上限 ${budget.maxBytesTotal}B",
            )
            return decide(GateOutcome.STOPPED, CODE_TOTAL_TOO_BIG)
        }
        if (grant.objectCount > budget.maxObjects) {
            steps += StepReading(
                GrantStep.LIMITS,
                false,
                "清单 ${grant.objectCount} 个对象超过上限 ${budget.maxObjects}",
            )
            return decide(GateOutcome.STOPPED, CODE_ACTION_LIMIT)
        }
        steps += StepReading(
            GrantStep.LIMITS,
            true,
            "动作 ${live.actionsUsed + 1}/${budget.maxActions}，本次 ${request.candidateBytes}B，" +
                "累计 ${live.bytesWritten + request.candidateBytes}B/${budget.maxBytesTotal}B",
        )

        // ④ protected / hardDeny：授权在这两件事上**一格都不覆盖**
        if (request.boundary.hardDeny) {
            steps += StepReading(GrantStep.BOUNDARY, false, "219 判了硬拒（原件区/密码框/私网 URL 之类）")
            return decide(GateOutcome.REJECT, CODE_HARD_DENY)
        }
        if (request.boundary.protectedHit) {
            steps += StepReading(GrantStep.BOUNDARY, false, "碰到受保护对象（原件区/密码框）")
            return decide(GateOutcome.ASK_SINGLE, CODE_PROTECTED)
        }
        if (request.mustAskIndependently || request.risk == RiskClass.R3 ||
            request.risk.ordinal > grant.maxRisk
        ) {
            steps += StepReading(
                GrantStep.BOUNDARY,
                false,
                "风险 ${request.risk} 必须逐次人批（授权最高覆盖 R${grant.maxRisk}）",
            )
            return decide(GateOutcome.ASK_SINGLE, CODE_RISK_TOO_HIGH)
        }
        steps += StepReading(
            GrantStep.BOUNDARY,
            true,
            "无硬拒、未碰受保护对象、风险 ${request.risk} ≤ R${grant.maxRisk}",
        )

        // ⑤ 影子三态（每个动作都查；UNKNOWN 停下报 NO_DATA，不弹卡）
        // 第二百九十六批 §3：这一格**第一次真去问适配器**——249 交付时它是"全绿但零调用"，
        // 276 P1 接上六步之后它仍然恒 `None`（管线那一格不传影子）。现在只要构造层交了
        // [ActionRequest.shadowInput]，第⑤步就自己去跑一次预演拿结论；调用方已经交回结论的
        // （276 的 N6 注入那一格）不重跑第二次——影子是有代价的，同一次动作只许跑一遍。
        val signal = request.shadow.takeIf { it is ShadowSignal.Rehearsed }
            ?: shadowSignalFor(request.shadowInput)
        val verdict = (signal as? ShadowSignal.Rehearsed)?.verdict
        if (verdict == null) {
            steps += StepReading(GrantStep.SHADOW, null, "这个动作没跑影子预演")
            return decide(GateOutcome.STOPPED, CODE_NO_DATA)
        }
        if (!shadowAllows(verdict)) {
            val reason = (signal as ShadowSignal.Rehearsed).reason
            steps += StepReading(
                GrantStep.SHADOW,
                // 「跑不出结论」不是「对不上」：MISMATCH/MISWIRED 是实测交回了差，UNVERIFIABLE
                // 是压根没有可比的东西（工具没接／沙盒没装／副本建不起）。记成"未验"才与
                // 239 那条「验不了不许冒充绿、也不许冒充红」同口径，结论码仍是 NO_DATA。
                passed = if (verdict == ShadowVerdict.UNVERIFIABLE) null else false,
                detail = "影子结论是 ${verdict.cn}（${verdict.wire}" +
                    if (reason.isBlank()) "）" else "：reason=$reason）",
            )
            return decide(
                GateOutcome.STOPPED,
                when (verdict) {
                    ShadowVerdict.MISMATCH -> CODE_SHADOW_MISMATCH
                    ShadowVerdict.MISWIRED -> CODE_SHADOW_MISWIRED
                    else -> CODE_NO_DATA
                },
            )
        }
        steps += StepReading(GrantStep.SHADOW, true, "影子 ${verdict.wire}（${verdict.cn}）")

        // ⑥ 执行落 208：一个动作一条账，键稳定，落过的不再落
        if (request.opId.isBlank()) {
            steps += StepReading(GrantStep.RECORD, false, "这个动作没有稳定的 opId")
            return decide(GateOutcome.ASK_SINGLE, CODE_NO_OP_ID)
        }
        if (live.alreadyApplied(request.opId)) {
            steps += StepReading(GrantStep.RECORD, false, "opId=${request.opId} 已经落过账（一个动作只许一条）")
            return decide(GateOutcome.STOPPED, CODE_DUPLICATE_OP)
        }
        steps += StepReading(
            GrantStep.RECORD,
            true,
            "opId=${request.opId}，账键=${ledgerKeyOf(request.opId)}，尚未落过",
        )
        return decide(GateOutcome.ALLOW, CODE_ALLOW)
    }

    /** 组装「只问增量」的那一问。 */
    private fun deltaFor(request: ActionRequest, grant: TaskGrant?): DeltaQuestion = DeltaQuestion(
        deltaObjects = deltaObjectsFor(request, grant),
        deltaActions = deltaActionsFor(request, grant),
        askedObject = request.objectId,
    )

    /**
     * 增量对象：**只有这次请求的那一个**。
     *
     * 负例⑤（越界重问整单）的判据就是这一行——它一旦开始拼 `grant.objectIds`，
     * 「只问增量」立刻退化成「重问整单」。
     */
    internal fun deltaObjectsFor(request: ActionRequest, grant: TaskGrant?): List<String> =
        listOf(request.objectId)

    /** 增量动作：授权里已经有这个动作 ⇒ 空（这次问的是对象，不是动作）。 */
    internal fun deltaActionsFor(request: ActionRequest, grant: TaskGrant?): List<GrantActionType> =
        if (grant?.allowsAction(request.action) == true) emptyList() else listOf(request.action)
}
