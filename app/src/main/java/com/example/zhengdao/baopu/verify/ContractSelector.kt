// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第三百三十二批 · 契约选择器：**先认类型，再谈判定**（纯函数，零模型、零 IO）。
//
// 为什么选择器要单独一个文件：332 最容易出的错不是「判据写错」，是**拿错尺子**——
// 拿复制的证据（路径集合＋哈希＋账行，六种证据里最齐的一套）去给一份文档判合格，
// 结果「证据齐全」四个字把人骗过去。所以选契约这一步必须是**显式的一步**，
// 而且它只有一个出口规则：
//
//   声明的任务类型 ≠ 证据的族  ⇒  **UNVERIFIABLE（type_mismatch）**，判据一条都不跑。
//
// 为什么不是 FAIL：FAIL 的意思是「判了，不过」；类型不对时我们**根本没判**，
// 说 FAIL 等于替一单没验过的活盖了个「不合格」的章，同样是撒谎（派工单 332 硬规矩①：
// 类型不匹配 ⇒ UNVERIFIABLE，不许硬套）。报告里 `checks` 刻意留空——空表就是
// 「一条都没判」的证据，谁也拿不出「我已经过了三条」这种说法。
//
// 三种入口各管一件事：
//   · [evaluate] 有证据 ⇒ 按证据的族对类型，对得上就交给 [Contracts] 逐条判；
//   · [absent]   证据是 null ⇒ **FAIL(missing_evidence)**，不是 UNVERIFIABLE——
//                契约列了判据、我们手上什么都没有，这是「缺证据」（硬规矩②），不是「类型不对」；
//   · [mismatch] 类型对不上 ⇒ UNVERIFIABLE。
//
// 与 342 的分工：342 的 `TaskContract` 只承载声明（含「必须交哪几类证据」），
// 本文件不读它、也不改它；两边靠**词表**对齐（四类任务／六类证据），不靠 import。
package com.example.zhengdao.baopu.verify

internal object ContractSelector {

    /** 四类任务的判据表（给验收单/UI 摊开用；真源在 [Contracts.checksOf]）。 */
    fun checksOf(type: TaskType): List<CheckId> = Contracts.checksOf(type)

    fun types(): List<TaskType> = TaskType.entries

    /**
     * 按声明的任务类型验收。
     *
     * @param declared 派工单上写的任务类型（不是从证据里猜出来的）
     * @param evidence 交上来的证据；null＝一条都没交
     */
    fun evaluate(declared: TaskType, evidence: Evidence?): ContractReport {
        if (evidence == null) return absent(declared)
        if (evidence.taskType != declared) return mismatch(declared, evidence.taskType)
        return Contracts.evaluate(evidence)
    }

    /** 一条证据都没交：判据全列出来、全标缺证据（不猜、不替它补）。 */
    fun absent(declared: TaskType): ContractReport = ContractReport(
        taskType = declared,
        status = Status.FAIL,
        reason = Reason.MISSING_EVIDENCE,
        checks = Contracts.checksOf(declared).map {
            CheckOutcome(it, ok = false, missing = true, detail = "一条证据都没交")
        },
        note = "契约列了 ${Contracts.checksOf(declared).size} 条判据，但一条证据都没拿到——缺证据算不过，不算待定",
    )

    /** 类型不匹配：判不了（UNVERIFIABLE），判据一条不跑。 */
    fun mismatch(declared: TaskType, actual: TaskType): ContractReport = ContractReport(
        taskType = declared,
        status = Status.UNVERIFIABLE,
        reason = Reason.TYPE_MISMATCH,
        checks = emptyList(),
        note = "声明的是「${declared.cn}」，交上来的证据是「${actual.cn}」的——类型不匹配，不许硬套；" +
            "要么补对类型的证据，要么改声明，二选一",
    )
}
