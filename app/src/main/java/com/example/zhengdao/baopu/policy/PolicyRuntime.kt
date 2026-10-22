// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十二批 · 政策表 v1：**当前生效的那一份**（进程级单一真相）。
//
// 读：A 的四个执行点（工具组默认可见 / 组闸阈值 / 进化风险与 eval 阈值）都走
// [current]；写：只有装配层在启动时调一次 [install]——没有设置页写口、没有网络写口、
// 没有工具写口。进程内换表只发生在启动安装那一次；之后是只读的。
//
// fail closed 的落点写死在这里：
// - 表缺失 ⇒ 回到 [Policy.DEFAULT]（与落盘表同值，但绝不比它宽）；
// - 表非法 ⇒ **整张拒绝**，保留当前这一份（启动时就是默认）；
// - 两种情况都返回一行审计文本，由调用方落 RunLog。
package com.example.zhengdao.baopu.policy

/** 当前政策与它的来路（状态不可变，整体替换）。 */
internal data class PolicyState(
    val status: Status,
    val policy: Policy,
    val source: String?,
    val reason: String?,
) {
    enum class Status {
        /** 还没安装过，按代码默认跑。 */
        DEFAULT,

        /** 表通过校验并生效。 */
        LOADED,

        /** 表不在，已回默认。 */
        MISSING,

        /** 表在但不合格，整张拒绝；[policy] 是拒绝后仍在用的那一份。 */
        INVALID,
    }
}

internal object PolicyRuntime {

    @Volatile
    private var policy: Policy = Policy.DEFAULT

    @Volatile
    private var state: PolicyState = PolicyState(
        status = PolicyState.Status.DEFAULT,
        policy = Policy.DEFAULT,
        source = null,
        reason = null,
    )

    /** 当前生效政策（永远非空；没装过就是内置默认）。 */
    fun current(): Policy = policy

    /** 当前状态（审计/测试用；不暴露可写句柄）。 */
    fun state(): PolicyState = state

    /**
     * 安装一份读表结果。**唯一写入口**。
     *
     * @return 一行 `[policy] …` 审计文本（每次都返回，装配层负责落盘）
     */
    fun install(load: PolicyTable.Load): String = synchronized(this) {
        when (load) {
            is PolicyTable.Load.Loaded -> {
                policy = load.policy
                state = PolicyState(
                    status = PolicyState.Status.LOADED,
                    policy = load.policy,
                    source = load.source,
                    reason = null,
                )
                PolicyAudit.loaded(load.policy, load.source)
            }

            is PolicyTable.Load.Missing -> {
                policy = Policy.DEFAULT
                state = PolicyState(
                    status = PolicyState.Status.MISSING,
                    policy = Policy.DEFAULT,
                    source = load.source,
                    reason = null,
                )
                PolicyAudit.missing(load.source)
            }

            is PolicyTable.Load.Invalid -> {
                // 关键：不动 policy。启动时它是默认；将来若支持重读，它保留上一份好表。
                state = PolicyState(
                    status = PolicyState.Status.INVALID,
                    policy = policy,
                    source = load.source,
                    reason = load.reason,
                )
                PolicyAudit.invalid(load.reason, load.source)
            }
        }
    }

    /** 测试/进程内重置用；生产不调用。 */
    fun reset() = synchronized(this) {
        policy = Policy.DEFAULT
        state = PolicyState(
            status = PolicyState.Status.DEFAULT,
            policy = Policy.DEFAULT,
            source = null,
            reason = null,
        )
    }
}
