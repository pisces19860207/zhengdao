// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十五批 · 「建议」页签第三类行的进程内持有者。
package com.example.zhengdao.baopu.audit

/**
 * 「最近一轮对账」的结果（进程级，重启即忘；持久化留痕在 `对账结果.jsonl`，职责分开）。
 *
 * 为什么不是直接读账本：账本只记**有缺口的轮次**，0 缺口那一轮不落账；页签若读账本，
 * 上一轮的旧缺口会一直顶着，判据③「0 缺口 ⇒ 0 行」就不成立。所以页签只认本进程
 * 最近一轮的产出：publish(emptyList()) 就是把这一类行清空。
 *
 * 本件名字不含 Wiring／Install／Boot，文件也不叫 `*Wiring.kt` ⇒ 不进接线清单候选；
 * 声明为 internal ⇒ 不进入口覆盖的公开面。它只是个读数槽，不是装配件。
 */
internal object ClaimEvidenceRowStore {
    @Volatile
    private var latest: List<AuditRow> = emptyList()

    /** 一轮跑完才调；窗口外不跑 ⇒ 不调，上一轮结果保持原样（判据⑤）。 */
    fun publish(rows: List<AuditRow>) {
        latest = rows.toList()
    }

    /** 页签读这一份（只读）。 */
    fun latest(): List<AuditRow> = latest

    /** 测试专用：谁污染谁还原。 */
    fun resetForTest() {
        latest = emptyList()
    }
}
