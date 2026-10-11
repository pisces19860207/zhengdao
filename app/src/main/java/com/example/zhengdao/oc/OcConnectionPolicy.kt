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
