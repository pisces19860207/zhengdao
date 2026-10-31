// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十八批 · 判决经验表：**当前生效的那一份**（进程级单一真相，原子换指针）。
//
// 房型照 122 批的 `PolicyRuntime`（那张是"发版政策"，这张是"学到的经验"——两回事，
// 但"读侧一次 volatile 读、写侧单把锁、坏数据 fail closed"这三条是同一套）：
//
//   - **读**：[current] 只有一条 `@Volatile` 引用读——不加锁、不等待、不与刷新争资源；
//     判决路径（`OpinionWiring.submit`）每条意见读一次，读到的永远是**完整的一版**表；
//   - **写**：只有 [refresh] 一处。它在 `synchronized` 里把整张新表**先建完**，再做
//     **一次**引用写；`seq` 单调递增 ⇒ 读侧能证自己没看见"回退"或"半截"；
//   - **fail closed**：折表抛（自洽性对不上、阈值越界）⇒ **保留上一版**并返回一行审计；
//     账为空 ⇒ 换一张空表（不是"保留旧表"——表被删就是本批判据②要的"退回原样"）。
//
// 什么时候刷新：只在**会挪阈值的行**落账之后（用户信号 / 重问 / 违规），
// 不在观察行（ASK）之后——每判一条就重建一张表等于把 283 的全本扫描换个地方做。
//
// 零 Android / 零 IO / 零时钟：`rows` 与 `now` 都由调用方给（装配层读盘、写侧带表）。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.agent.ExperienceRow

/** 换表的结果状态（审计与测试读 [VerdictExperienceRuntime.state]）。 */
internal enum class SnapshotStatus(val cn: String) {
    /** 还没刷过（进程刚起来）。 */
    INIT("未装表"),

    /** 换表成功（含"账是空的 ⇒ 换一张空表"）。 */
    LOADED("已换表"),

    /** 折表被拒（自洽性对不上）：**上一版继续生效**。 */
    REJECTED("拒绝换表"),

    /** 显式清空（测试/判据②「删掉表」的落点）。 */
    CLEARED("已清表"),
}

/** 换表的账面状态：状态＋当前版本号＋为什么（**不暴露可写句柄**）。 */
internal data class SnapshotState(
    val status: SnapshotStatus,
    val seq: Long,
    val rowCount: Int,
    val reason: String,
)

internal object VerdictExperienceRuntime {

    /** 当前生效的那张表。**判决路径只读它**：一次 volatile 读，无锁、无等待。 */
    @Volatile
    private var snapshot: VerdictExperienceSnapshot = VerdictExperienceSnapshot.EMPTY

    private var state: SnapshotState = SnapshotState(SnapshotStatus.INIT, 0L, 0, "启动还没折过表")

    /** 当前表（永远非空；没刷过就是 [VerdictExperienceSnapshot.EMPTY]）。 */
    fun current(): VerdictExperienceSnapshot = snapshot

    /** 换表状态（审计/测试用）。 */
    fun state(): SnapshotState = synchronized(this) { state }

    /**
     * 折一张新表并**一次性**换上（唯一写入口）。
     *
     * @param rows 经验账全本（调用方读盘给的；本函数不读盘）
     * @param now 建表时刻（窗口按它算；本函数不读时钟）
     * @param source 这一把行的来路（审计用：`startup` / `signal` / `test`…）
     * @return 一行 `[经验表] …` 审计文本（每次都返回，调用方负责落日志）
     */
    fun refresh(rows: List<ExperienceRow>, now: Long, source: String = "ledger"): String = synchronized(this) {
        val next = runCatching {
            VerdictExperienceSnapshot.build(rows = rows, now = now, seq = snapshot.seq + 1L)
        }
        val built = next.getOrNull()
        if (built == null) {
            // 关键：**不动 snapshot**。半截/自洽性对不上的表换不上去，上一版继续生效。
            val cause = next.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "未知"
            state = SnapshotState(SnapshotStatus.REJECTED, snapshot.seq, snapshot.rowCount, cause)
            return auditLine(state, source)
        }
        snapshot = built // 单次引用写：读侧要么看到整张旧表，要么看到整张新表
        state = SnapshotState(SnapshotStatus.LOADED, built.seq, built.rowCount, "")
        auditLine(state, source)
    }

    /** 清表（判据②「删掉表 ⇒ 行为退回原样」的进程内落点；测试也用这一口）。 */
    fun clear(reason: String = "显式清表") = synchronized(this) {
        snapshot = VerdictExperienceSnapshot.EMPTY
        state = SnapshotState(SnapshotStatus.CLEARED, 0L, 0, reason)
        auditLine(state, reason)
    }

    /** 测试/进程内重置（生产不调用）。 */
    fun reset() = synchronized(this) {
        snapshot = VerdictExperienceSnapshot.EMPTY
        state = SnapshotState(SnapshotStatus.INIT, 0L, 0, "启动还没折过表")
    }

    /** 审计那一行（抽成纯函数：格式也是判据，埋在 RunLog 里就抓不到）。 */
    internal fun auditLine(state: SnapshotState, source: String): String = when (state.status) {
        SnapshotStatus.REJECTED -> "[经验表] 拒绝换表（来源=$source）：${state.reason}——保留 v${state.seq}"
        SnapshotStatus.CLEARED -> "[经验表] 已清表（来源=$source）：回到无表状态，判决走完整流程"
        else -> "[经验表] 已换表 v${state.seq}（来源=$source）：账面 ${state.rowCount} 行，" +
            "抑制 ${snapshot.suppressedClasses(snapshot.builtAtMs).size} 类，阈值 ${snapshot.threshold}"
    }
}
