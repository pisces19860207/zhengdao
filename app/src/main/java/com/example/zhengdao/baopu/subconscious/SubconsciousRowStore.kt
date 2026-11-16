// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十六批 · 回响信号的**进程内持有者**（635 `ClaimEvidenceRowStore` 的房型）。
package com.example.zhengdao.baopu.subconscious

/**
 * 最近一轮真跑出的回响信号（进程级，重启即忘；持久留痕在自己的 `回响.jsonl`，职责分开）。
 *
 * 页签只认本进程最近一轮：延后/天闸跳过的那轮不 publish ⇒ 上一轮结果保持原样
 * （判据⑤的「什么都不做，连上一轮结果都不动」同款口径）。
 *
 * 本件不是装配件（无 install/record 入口）⇒ 不进接线清单候选；internal ⇒ 不进入口覆盖。
 */
internal object SubconsciousRowStore {
    @Volatile
    private var latest: List<SubconsciousSignal> = emptyList()

    /** 一轮真跑完才调；延后/跳过的轮不调。 */
    fun publish(signals: List<SubconsciousSignal>) {
        latest = signals.toList()
    }

    /** 页签读这一份（只读）。 */
    fun latest(): List<SubconsciousSignal> = latest

    /** 人看过（卡上点了收尾）⇒ 这一枚从页签撤下；账行与账本一行不动。 */
    fun dismiss(id: String) {
        latest = latest.filterNot { it.id == id }
    }

    /** 测试专用：谁污染谁还原。 */
    fun resetForTest() {
        latest = emptyList()
    }
}
