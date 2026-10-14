// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.oc

/**
 * `OcRepository.open()` 的判据：**这次调用还要不要重新建连**。
 *
 * ## 为什么要有它（2026-10-11 用户报「每次重进太极都会重新连接」）
 *
 * `HomeTabs` 切 Tab 用的是 `when (tab)`（`MainActivity.kt:654`）——切走的 Tab **直接退出
 * Composition**。原先 `OcRepository` 是在 `TaijiScreen` 里 `remember { }` 建的、轮询与 SSE
 * 又跑在 `rememberCoroutineScope()` 上，于是"切走 Tab"等于把连接整套拆掉，切回来自然重新
 * 握手 + 重新全量拉取。
 *
 * 现在 repository 与它的作用域都挂在 [OcRuntime]（进程级）上，切 Tab 不再拆连接；但这样一来
 * **每次重进页面都会调一次 `open()`**，必须能判断"连接还活着、这次什么都不用做"，否则照样重连。
 *
 * ## 判据（按顺序，先命中先返回）
 *
 * 1. **失败态** → 重开（`FailedPane` 的「重试」就是靠这条）
 * 2. **当前还没有会话 id** → 开
 * 3. **轮询或 SSE 任一没在跑** → 重开（真断线，或进程重建后 Job 已死）
 * 4. **明确要求切到别的会话** → 开
 * 5. 其余（同会话 + 两条 Job 都活）→ **不重开**，直接复用现有连接
 *
 * 判据抽成纯函数是为了能在 JVM 上把 5 种组合都钉住——Job 的存活与否没法在真机上反复复现
 * （同一套教训见 `TaijiSessionGrouping` / `MessageCopy` / `AgentInstallPrep`）。
 *
 * @param requested 调用方传入的会话 id（`null` = 「不指定，沿用当前/最近那个」）
 * @param current repository 此刻持有的会话 id
 * @param pollAlive REST 轮询任务是否还在跑（**主数据源**，见 ERRATA E-008）
 * @param sseAlive SSE 信号通道是否还在跑
 * @param isFailed 当前是否停在 [TaijiPhase.Failed]
 */
internal fun shouldReopenConnection(
    requested: String?,
    current: String?,
    pollAlive: Boolean,
    sseAlive: Boolean,
    isFailed: Boolean,
): Boolean {
    if (isFailed) return true
    if (current.isNullOrEmpty()) return true
    if (!pollAlive || !sseAlive) return true
    return requested != null && requested != current
}

/**
 * 切会话（`switchSession` / `startNewSession`）时写进 state 的**预置状态**：
 * 只清掉上一场的残留，**绝不改 [TaijiState.sessionId]**。
 *
 * ## 为什么专门抽成函数（2026-10-13 真机，ERRATA E-123）
 *
 * [shouldReopenConnection] 的第 4 条判据是 `requested != current`。`switchSession` 原先把
 * `sessionId = 目标 id` 一起写进这次预置（`f61dd51`，v1.1 时代落笔，那时 `OcRepository.open`
 * 还没有复用判据），于是 `open()` 里 `requested == current` 恒成立 ⇒ 直接走
 * 「open 复用现有连接」分支 `return`：既不 `loadAll()`，也不重建轮询 / SSE
 * ⇒ **切到历史会话后页面停在空列表、轮询还挂在旧会话上**；杀掉 App 重进
 * （repository 重建、`current` 为 null）才恢复。
 *
 * 用户原话（2026-10-13）：「我刚刚在太极页切换会话的时候就有个出错，然后关闭APP重进了
 * 才没问题的 —— 那种切换回旧聊天会话的时候」。
 *
 * 目标 id 由 `OcRepository.open` 自己在 Loading 那次 update 里写进 state —— 那里是唯一写点。
 */
internal fun TaijiState.afterSessionSwitchStarted(): TaijiState = copy(
    messages = emptyList(),
    parts = emptyMap(),
    loadedOnce = false,
    input = "",
    isStreaming = false,
    pendingPermission = null,
    phase = TaijiPhase.Loading,
)

/**
 * 第⑭刀 #2 / P2-15②：REST 轮询**连续失败**多少次后放弃自动重连。
 *
 * ## 为什么是 8
 *
 * 退避阶梯 1s/2s/4s/8s/15s（封顶 15s）：第 8 次失败时累计等待约 1+2+4+8+15×4 ≈ 75s，
 * 再等下去用户只会以为界面卡死。放弃后置 `TaijiPhase.Failed(retryable = true)`，
 * 恢复路径复用既有机制 —— FailedPane「重试」→ `open()`（[shouldReopenConnection] 第 1 条）。
 *
 * 熔断是「**连续**失败」语义：一次成功即清零（由调用方 [com.example.zhengdao.oc.OcRepository]
 * 的 pollOnce 成功路径保证）。纯函数，表驱动单测见 OcConnectionPolicyTest。
 */
internal const val RECONNECT_GIVE_UP = 8

internal fun shouldGiveUpReconnecting(consecutiveFailures: Int): Boolean =
    consecutiveFailures >= RECONNECT_GIVE_UP
