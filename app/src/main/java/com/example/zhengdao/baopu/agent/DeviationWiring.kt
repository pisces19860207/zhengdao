// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十六批 · 偏差五类的**接线口**（谁往账里写；账本本体见 [DeviationLedger]，363）。
//
// 来历（派工单-2026-11-02-第三百八十六批）：363 交付了账本＋读数口，但**没有任何地方往里
// 写**——账本空转。本文件把三个「判据已存在」的触发点（超步数／超时／挂起超时）接上：
// 判定处只加一行记账调用，判定逻辑一个字不动（判据②：接线前后执行路径不变）。
//
// 为什么需要一个 object：账行必须带出处（363 硬规矩 3：runId 必填），而三个判定点里只有
// 超步数住在引擎里（roundRunId 是引擎私有字段）；工具超时（ToolRunner/ToolBatchRunner）
// 与挂起超时（ConfirmFlow）都拿不到它。所以引擎在新起一轮时把本轮出处**存**在这里
// （[installRun]，每轮一次，@Volatile 保证工具线程读得到），判定组件落账时来取——
// 出处还是引擎的真实状态，只是走了一个双方都看得见的信箱。
//
// 四条纪律（363 的四条硬规矩在这里同样生效）：
//   1. **静默**：没挂账（[DeviationLedger.installedRoot] 为 null）或没有出处 ⇒ 一个字节
//      不写、不抛、不打日志——记账永远不连累主流程（判据②的另一半）；
//   2. **只转写**：本文件不产事实、不改笔迹——量出来的数原样进 [DeviationEntry] 的工厂，
//      落盘交给 [DeviationLedger.write]（它自己的失败兜底在 363）；
//   3. **不判决**：只写五类事实，判决词从形状上进不来（363 的工厂键值闭集）；
//   4. **缺判据不接线**：打扰超额／绕过确认两类，生产代码里没有「弹卡计数与授权次数
//      比较」「免打扰授权下执行不可逆动作」的判定处——按派工单 §三**报缺判据不造阈值**
//      （阈值归用户定），本文件因此只开三个记账口，不开五个。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.inspect.DeviationEntry
import com.example.zhengdao.baopu.inspect.DeviationLedger

internal object DeviationWiring {

    // ── 出处信箱（引擎每轮写一次；判定组件落账时读） ──────────────────────────

    @Volatile private var runId: String = ""

    @Volatile private var taskId: String = ""

    /** 新起一轮：存本轮出处（引擎 `newRun` 处调；每轮覆盖——出处永远跟着当前轮）。 */
    fun installRun(newRunId: String, newTaskId: String = "") {
        runId = newRunId
        taskId = newTaskId
    }

    /** 收工：出处摘空（与引擎 `roundRunId` 同生命周期——收工后的超时该记给谁？谁都不知道）。 */
    fun clearRun() {
        runId = ""
        taskId = ""
    }

    fun currentRunId(): String = runId

    fun currentTaskId(): String = taskId

    /** 测试摘信箱（进程级全局不清就是随执行顺序变红——`TaskGrantBoard.resetForTest` 同款教训）。 */
    fun resetForTest() {
        runId = ""
        taskId = ""
    }

    // ── 三个记账口（判定处各一行调用；时刻在这里取，数由调用方真量） ────────────
    //
    // 出处先查后造：363 的工厂构造期拦空 runId（硬规矩 3），没出处就构造 ⇒ 抛——
    // 静默纪律（硬规矩 1）要求记账永远不连累主流程，所以先退再造，不许靠接住异常兜。

    /** 超步数：步数刹车处（`RunRules.isStepExhausted` 命中的那一步）。 */
    fun recordOverSteps(steps: Int, limit: Int, atStep: Int) {
        val rid = currentRunId()
        if (rid.isBlank()) return
        record(DeviationEntry.overSteps(rid, currentTaskId(), nowMs(), steps, limit, atStep))
    }

    /** 超时：工具执行超时收口处（实到毫秒由调用方掐表量得；没卡到具体工具留空串）。 */
    fun recordTimeout(elapsedMs: Long, limitMs: Long, tool: String) {
        val rid = currentRunId()
        if (rid.isBlank()) return
        record(DeviationEntry.timeout(rid, currentTaskId(), nowMs(), elapsedMs, limitMs, tool))
    }

    /**
     * 挂起超时：确认卡等到线处（挂起时刻是 `enter` 落 pending 那一刻的真值）。
     *
     * @param callId 挂起的那次调用的 opId（第四百一十五批加的出处：卡级映射键——
     *        处置账时钟收卡行的 `card` 列就是同一个 id，偏差→处置两行按键 join；
     *        调用方给不出就留空串，账行照落，只是那一格空着——宁空勿猜）
     */
    fun recordSuspendTimeout(suspendedAtMs: Long, question: String, stillPending: Boolean, callId: String = "") {
        val rid = currentRunId()
        if (rid.isBlank()) return
        record(
            DeviationEntry.suspendTimeout(
                rid, currentTaskId(), nowMs(), suspendedAtMs, question, stillPending, callId,
            ),
        )
    }

    // ── 落账收口（静默：没挂账或没出处 ⇒ 一个字节不写） ────────────────────────

    private fun record(entry: DeviationEntry) {
        if (entry.runId.isBlank()) return
        val root = DeviationLedger.installedRoot() ?: return
        DeviationLedger.write(root, entry)
    }

    private fun nowMs(): Long = System.currentTimeMillis()
}
