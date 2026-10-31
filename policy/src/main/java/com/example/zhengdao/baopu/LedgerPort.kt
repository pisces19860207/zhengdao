// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十三批 ③（2026-10-31，来自第二百九十七批体检）· **账本接口化的一刀**。
//
// 事实是：宰相（`MediatorWiring`）判出违规后要往 208 落一行，而 208 的实现
// （`DecisionLedger`）带着 `java.io.File` ＋ `org.json` ＋ `MessageDigest`——把实现搬进
// `:policy` 就等于把 IO 壳塞进白名单匣；把它留在 `:app` 让宰相去 import，
// 依赖方向当场倒转（`:policy` → `:app`），抽模块的全部意义没了。
//
// 所以这一刀切在**端口**上：`:policy` 只认 [LedgerPort]，实现仍住在 :app，由 :app 的装配层
// （`DecisionLedger.install`）把自己登记进 [LedgerPorts]。宰相拿到的还是同一本账、同一次落盘、
// 同一句失败原因——**行为一个字没动**，动的只有"宰相通过什么名字找到账本"。
//
// 三条边界（本批不许越，负例见派工单）：
//   · **只抽这一个账本**。`RoyalDecisionLedger`／`SelfRulingLedger`／产物台账／进化档案
//     一律不动：一次抽五个接口＝给五本没人要的账各加一层装配，正是"顺手铺开"。
//   · 端口只给**写**。读侧（分组卡、抽检、回滚）留在 :app——宰相不读账，给它读口就是给它新权力。
//   · [LedgerPort.write] 返回布尔，不返回 `DecisionRecord`：那个类型带 `toJson()`，
//     搬过来就是把 org.json 引进匣子。
package com.example.zhengdao.baopu

/**
 * 一本决策账的**写入口**（宰相唯一认得的账本名字）。
 *
 * 为什么把 `lastError` 放进接口：调用方唯一要做的收尾就是"没落进去的时候 log 一句"，
 * 而那句原因只有实现方知道（没装配落点／缺 reason／盘写失败）。少了它，端口返回值就成了
 * 一个无法解释的 false，调用方只好去猜——猜出来的文案就是把判据搬进字符串。
 */
interface LedgerPort {

    /** 最近一次写失败的一句话；`null`＝没失败过。真源在实现方。 */
    val lastError: String?

    /**
     * 落一行。
     *
     * @return `true`＝落了；`false`＝没落，原因见 [lastError]（**不抛**：留痕不是闸门，
     *   账本卡住不许把用户已经看到的动作回滚——208 批的口径，本端口原样继承）
     */
    fun write(draft: DecisionDraft): Boolean
}

/**
 * [LedgerPort] 的装配位。
 *
 * 为什么还要一个全局持有者、而不是让宰相构造时收一个端口参数：接线层本来就是一个进程内单例
 * （258 W1「档位存进程内单例」），而账本的落点在 208 里也是进程级全局（`DecisionLedger.install`）。
 * 加一个参数就等于给宰相新增第二个装配点——两处装配一处忘就是"账静默不落"，
 * 那比多一个持有者糟得多。所以登记动作**长在既有 `install()` 里**：装账本＝装端口，一次一件事。
 */
object LedgerPorts {

    /** 当前接线的账本端口；`null`＝账本没装配（早期启动/单测未装），写口一律走"没落进去"分支。 */
    @Volatile
    var ledger: LedgerPort? = null
}
