// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.Provider

/**
 * 资源机（第六十四批）——把「换模型 / 换通道」做成一个**扁平五态**的显式状态机。
 *
 * 设计依据：派工单 `docs/派工单-2026-10-20-第六十四批-跨模型切换状态机.md`。
 *
 * ## 为什么是手写迁移表，不上状态机框架
 * 派工单红线明令不引 statechart 框架/新依赖。五个状态、六个事件，一张表就装得下；
 * 框架带来的代码生成与调试复杂度在这里只增不减。迁移表写成显式 `when`，
 * 全部路径 JVM 直测（[ChannelSwitchFsmTest]）。
 *
 * ## 单一真相纪律（派工单 §4）
 * 本文件**不碰任何存储**（无 SharedPreferences、无数据库、无 context）：
 * 「当前通道」的唯一真相是 `ProviderPrefs` 现值，本机只持有**请求负荷**
 * （[ChannelSwitchSnapshot.requestedTarget]——用户请求切到哪，不是"现在在哪"）。
 * 切换的落盘/重建经调用方注入的效应（[ChannelSwitchCoordinator.applySwitch]）完成。
 * 源码门禁见 `ChannelSwitchTruthGateTest`。
 *
 * ## 两条派生迁移（派工单未写全，按"立得起来"补的，理由写在交付报告）
 * - **SWITCHED 是瞬态**：`SWITCHING + SwitchSucceeded ⇒ SWITCHED`（留可观察 lastResult），
 *   之后收到任何事件先自动回 CURRENT（lastResult 保留）——派工单原文"切换完成
 *   （随即回 CURRENT，但留一个可观察的 lastResult）"。
 * - **FALLBACK 是稳态变体**：切换失败并回退旧通道后，用户仍在原通道上 ⇒ 与 CURRENT
 *   同规则收 `SwitchRequested`（可重试），不收其余事件。若按"未定义迁移"一律拒，
 *   一次失败后这台机器就再也接不到请求——那不是立得起来的机器。
 */
enum class ChannelSwitchState {
    /** 稳定，正在用某通道。 */
    CURRENT,

    /** 已收到用户切换请求，等安全点（不打断当前步）。 */
    PENDING,

    /** 正在落切换（读偏好/写偏好/重建 provider 的效应窗口）。 */
    SWITCHING,

    /** 切换完成（瞬态，随即回 CURRENT；lastResult 留痕）。 */
    SWITCHED,

    /** 切换失败并回退旧通道（带原因，留可观察结果；可重试）。 */
    FALLBACK,
}

/** lastResult——切换结果的可观察留痕（成功 Ok；失败带原因）。 */
sealed interface SwitchResult {
    data object Ok : SwitchResult
    data class Failed(val reason: String) : SwitchResult
}

/**
 * 事件（派工单 §1 原表）。
 *
 * [SecondRequestIgnored] 不在其中：派工单把它列在事件段，但它的语义是
 * "PENDING/SWITCHING 收到第二次 SwitchRequested"的**结果命名**——
 * 同一个意图开两个入口（事件 vs 结果）只会让迁移表多一条无人派发的路径。
 * 该结果由 [SwitchOutcome.Ignored] 承载，话术在Coordinator 层给。
 */
sealed interface SwitchEvent {
    /** 用户请求切到 [target]（target 是请求负荷，不是当前真相）。 */
    data class Requested(val target: Provider) : SwitchEvent

    /** 到达安全点（派工单 §2：两步之间 / 出确认卡之前 / 本轮 Settled 之后）。 */
    data object ReachedSafePoint : SwitchEvent

    /** 效应窗口开启标记（只在 SWITCHING 合法；他态一律拒）。 */
    data object SwitchStarted : SwitchEvent

    /** 效应成功。 */
    data object SwitchSucceeded : SwitchEvent

    /** 效应失败（带原因；回旧通道由效应层负责，本机只记账）。 */
    data class SwitchFailed(val reason: String) : SwitchEvent
}

/**
 * 一次 [ChannelSwitchFsm.dispatch] 的结果。
 *
 * - [Accepted]：迁移合法（[changed] = 状态是否真的变了）；
 * - [Ignored]：单飞——第二个请求被忽略（状态不变，带话术）；
 * - [Rejected]：未定义迁移（状态不变，带原因；调用方记日志）。
 */
sealed interface SwitchOutcome {
    data class Accepted(val snapshot: ChannelSwitchSnapshot, val changed: Boolean) : SwitchOutcome
    data class Ignored(val message: String, val snapshot: ChannelSwitchSnapshot) : SwitchOutcome
    data class Rejected(val reason: String) : SwitchOutcome
}

/** 资源机可观察快照（状态 + 请求负荷 + lastResult）。 */
data class ChannelSwitchSnapshot(
    val state: ChannelSwitchState = ChannelSwitchState.CURRENT,
    /** 待执行的切换请求负荷；切换完成/失败即清空。**不是**"当前通道"真相。 */
    val requestedTarget: Provider? = null,
    val lastResult: SwitchResult? = null,
)

/**
 * 手写迁移表（纯判据，零 Android 依赖，JVM 直测）。
 *
 * 线程约定：`dispatch` 可能被两条线程调用（UI 线程发请求、`zd-baopu-run` 线程
 * 在安全点派发）⇒ 方法级 `synchronized`；不新起线程（`BareThreadGuardTest` 口径）。
 */
class ChannelSwitchFsm(initialState: ChannelSwitchState = ChannelSwitchState.CURRENT) {

    private var snap = ChannelSwitchSnapshot(state = initialState)

    fun snapshot(): ChannelSwitchSnapshot = snap

    @Synchronized
    fun dispatch(event: SwitchEvent): SwitchOutcome {
        val before = snap
        // 派生迁移 D1：SWITCHED 是瞬态——任何后续事件先自动回 CURRENT（lastResult 保留），
        // 再按 CURRENT 的规则处理这次事件。没有事件来时它停在 SWITCHED（UI 可观察
        // "刚刚切换完成"），下一个事件自然把它带回 CURRENT。
        if (snap.state == ChannelSwitchState.SWITCHED) {
            snap = snap.copy(state = ChannelSwitchState.CURRENT)
        }
        return when (snap.state) {
            // ── 稳态（CURRENT）与回退态（FALLBACK，派生 D2）──────────────────
            ChannelSwitchState.CURRENT, ChannelSwitchState.FALLBACK -> when (event) {
                is SwitchEvent.Requested -> accept(before, 
                    snap.copy(
                        state = ChannelSwitchState.PENDING,
                        requestedTarget = event.target,
                    ),
                )

                is SwitchEvent.ReachedSafePoint -> accept(before, snap) // 派工单明条：不在 PENDING ⇒ 不变

                is SwitchEvent.SwitchStarted ->
                    reject("${label()}态收到 SwitchStarted：效应窗口没开（未定义迁移）")

                is SwitchEvent.SwitchSucceeded ->
                    reject("${label()}态收到 SwitchSucceeded：没有正在进行的切换（未定义迁移）")

                is SwitchEvent.SwitchFailed ->
                    reject("${label()}态收到 SwitchFailed：没有正在进行的切换（未定义迁移）")
            }

            // ── 排队中（等安全点；不打断当前步）──────────────────────────────
            ChannelSwitchState.PENDING -> when (event) {
                is SwitchEvent.Requested -> ignored(
                    "已收到切换请求（${snap.requestedTarget?.label ?: "目标"}），" +
                        "将在下一个安全点生效；第二个请求已忽略",
                )

                is SwitchEvent.ReachedSafePoint -> accept(before, 
                    snap.copy(state = ChannelSwitchState.SWITCHING),
                )

                is SwitchEvent.SwitchStarted ->
                    reject("PENDING 态收到 SwitchStarted：还没到安全点（未定义迁移）")

                is SwitchEvent.SwitchSucceeded ->
                    reject("PENDING 态收到 SwitchSucceeded：还没开始切（未定义迁移）")

                is SwitchEvent.SwitchFailed ->
                    reject("PENDING 态收到 SwitchFailed：还没开始切（未定义迁移）")
            }

            // ── 切换中（效应窗口；单飞）────────────────────────────────────
            ChannelSwitchState.SWITCHING -> when (event) {
                is SwitchEvent.Requested -> ignored(
                    "正在切换到 ${snap.requestedTarget?.label ?: "目标"}，" +
                        "已忽略第二个请求（一次只切一个）",
                )

                // 派生 D3：SwitchStarted 只在 SWITCHING 接受，无迁移（纯标记）。
                is SwitchEvent.SwitchStarted -> accept(before, snap)

                is SwitchEvent.SwitchSucceeded -> accept(before, 
                    snap.copy(
                        state = ChannelSwitchState.SWITCHED,
                        requestedTarget = null,
                        lastResult = SwitchResult.Ok,
                    ),
                )

                is SwitchEvent.SwitchFailed -> accept(before, 
                    snap.copy(
                        state = ChannelSwitchState.FALLBACK,
                        requestedTarget = null,
                        lastResult = SwitchResult.Failed(event.reason),
                    ),
                )

                is SwitchEvent.ReachedSafePoint -> accept(before, snap) // 派工单明条：不在 PENDING ⇒ 不变
            }

            // 理论不可达（SWITCHED 在函数头部已自动回 CURRENT）；兜底不静默。
            ChannelSwitchState.SWITCHED -> reject("内部错误：SWITCHED 未被自动回带")
        }
    }

    /** changed 对**派发前**快照比较（SWITCHED 自动回带也算一次变化）。 */
    private fun accept(before: ChannelSwitchSnapshot, next: ChannelSwitchSnapshot): SwitchOutcome =
        SwitchOutcome.Accepted(
            next,
            changed = next.state != before.state || next.lastResult != before.lastResult,
        ).also { snap = next }

    private fun ignored(message: String): SwitchOutcome =
        SwitchOutcome.Ignored(message, snap)

    private fun reject(reason: String): SwitchOutcome = SwitchOutcome.Rejected(reason)

    private fun label(): String = snap.state.name
}

/**
 * 能力差异（派工单 §3）——换目标通道时算一份，**只出数据不硬兼容**。
 *
 * v1 不自动降级、不自动裁历史；唯一需要用户明确一句话的是
 * [requiresUserDecision]（本轮有图要发、目标通道不能看）。
 */
data class CapabilityDiff(
    val fromId: String,
    val toId: String,
    val toLabel: String,
    /** 当前能看、目标不能看（唯一需要用户拍板的差异）。 */
    val visionLost: Boolean,
    /** 当前不能看、目标能看（好消息，不拦）。 */
    val visionGained: Boolean,
    val contextWindowFrom: Int?,
    val contextWindowTo: Int?,
    val effortFrom: String?,
    val effortTo: String?,
) {
    val requiresUserDecision: Boolean get() = visionLost

    /** 人读的一行（日志/UI 通吃；不含 Key）。 */
    fun render(): String = buildString {
        append("能力差异 ${fromId}→${toId}：")
        append(if (visionLost) "目标不能看图；" else if (visionGained) "目标可以看图；" else "看图能力不变；")
        if (contextWindowFrom != contextWindowTo) {
            append("上下文窗口 ${contextWindowFrom ?: "?"}→${contextWindowTo ?: "?"}；")
        }
        if (effortFrom != effortTo) append("思考档位 ${effortFrom ?: "默认"}→${effortTo ?: "默认"}；")
    }
}

/** 能力差异纯判据（无副作用）。 */
fun capabilityDiff(from: Provider, to: Provider): CapabilityDiff = CapabilityDiff(
    fromId = from.id,
    toId = to.id,
    toLabel = to.label,
    visionLost = from.vision && !to.vision,
    visionGained = !from.vision && to.vision,
    contextWindowFrom = from.contextWindow,
    contextWindowTo = to.contextWindow,
    effortFrom = from.reasoningEffort,
    effortTo = to.reasoningEffort,
)

/** 安全点（派工单 §2：只认这三处，引擎不许自己猜）。 */
enum class SafePoint {
    /** 两步之间（`stepCount` 增长前）。 */
    StepBoundary,

    /** 出确认卡之前（不能卡在工具半途）。 */
    BeforeConfirmCard,

    /** 本轮 Settled 之后（收尾后立刻切）。 */
    AfterSettled,
}

/** 切换效应的结果（注入给协调器；成功带重建后的新 provider）。 */
sealed interface SwitchApplyOutcome {
    data class Applied(val provider: Provider) : SwitchApplyOutcome
    data class Failed(val reason: String) : SwitchApplyOutcome
}

/** 安全点处理的结果（引擎据此更新自己的 provider 字段与提示）。 */
data class SafePointOutcome(
    val switched: Boolean,
    val provider: Provider? = null,
    val message: String? = null,
)

/**
 * 正交协作层（派工单 §2）——任务机与资源机之间的唯一桥梁。
 *
 * 职责只有三件：
 * 1. 收 [requestSwitch]（任何时候可调；CURRENT/FALLBACK 受理，PENDING/SWITCHING 单飞忽略）；
 * 2. 在三个安全点调 [atSafePoint]（只有 PENDING 才真正落切换，其余一律不动）；
 * 3. 落切换时跑注入的效应（写偏好/重建 provider），按结果派发 Succeeded/Failed。
 *
 * 它**不持有**当前 provider（那是引擎的字段与 ProviderPrefs 的真相）：
 * [requestSwitch] 的 `current` 参数只用于算能力差异，出了函数就丢。
 */
class ChannelSwitchCoordinator(
    private val fsm: ChannelSwitchFsm = ChannelSwitchFsm(),
    private val applySwitch: (Provider) -> SwitchApplyOutcome,
    private val hasPendingImages: () -> Boolean = { false },
    private val log: (String) -> Unit = {},
) {

    fun snapshot(): ChannelSwitchSnapshot = fsm.snapshot()

    fun state(): ChannelSwitchState = fsm.snapshot().state

    /**
     * UI 入口：请求切换。返回给用户看的一句话。
     *
     * @param allowDropImages 用户在"去图再切 / 留在原通道"里显式选了前者才为 true
     *   （派工单 §3 三选一的落地：默认拒切，不静默剥图）。
     */
    fun requestSwitch(current: Provider, target: Provider, allowDropImages: Boolean = false): String {
        val diff = capabilityDiff(current, target)
        if (diff.requiresUserDecision && hasPendingImages() && !allowDropImages) {
            return "本轮要发的图，${target.label} 不支持看图：" +
                "留在原通道，或选「去掉图再切」（只发文字，历史消息不动）"
        }
        return when (val o = fsm.dispatch(SwitchEvent.Requested(target))) {
            is SwitchOutcome.Ignored -> o.message
            is SwitchOutcome.Accepted ->
                "已记下：切到 ${target.label}，到下一个安全点生效（不打断当前步）"

            is SwitchOutcome.Rejected -> "切换请求被拒：${o.reason}"
        }
    }

    /**
     * 引擎在三个安全点调用。只有 PENDING 才落切换；SWITCHED 瞬态在此被带回家。
     */
    fun atSafePoint(point: SafePoint): SafePointOutcome {
        if (fsm.snapshot().state == ChannelSwitchState.SWITCHED) {
            fsm.dispatch(SwitchEvent.ReachedSafePoint) // 自动回 CURRENT（lastResult 保留）
            return SafePointOutcome(switched = false)
        }
        if (fsm.snapshot().state != ChannelSwitchState.PENDING) {
            return SafePointOutcome(switched = false) // 无待切换 ⇒ 任何安全点都不动
        }
        val target = fsm.snapshot().requestedTarget
            ?: return SafePointOutcome(switched = false)
        fsm.dispatch(SwitchEvent.ReachedSafePoint) // PENDING ⇒ SWITCHING
        fsm.dispatch(SwitchEvent.SwitchStarted)
        return when (val o = applySwitch(target)) {
            is SwitchApplyOutcome.Applied -> {
                fsm.dispatch(SwitchEvent.SwitchSucceeded)
                log("资源机：切换完成 ⇒ ${o.provider.id}（安全点 ${point.name}）")
                SafePointOutcome(switched = true, provider = o.provider)
            }

            is SwitchApplyOutcome.Failed -> {
                fsm.dispatch(SwitchEvent.SwitchFailed(o.reason))
                log("资源机：切换失败，留在原通道（${o.reason}；安全点 ${point.name}）")
                SafePointOutcome(switched = false, message = "切换失败：${o.reason}（已留在原通道）")
            }
        }
    }
}
