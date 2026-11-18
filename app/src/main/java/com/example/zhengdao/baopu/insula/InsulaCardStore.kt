// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 岛叶自调卡的**进程内持有者**（676 `SubconsciousRowStore` 的房型）。
// 调节不是静默的：每真调一次浮一张卡，卡上有「一键回默认」——撤销面长在人点得到的地方。
package com.example.zhengdao.baopu.insula

/** 一张自调卡（展示槽；账行在调节账里，这里只放人要看的摘要）。 */
internal data class InsulaTuningCard(
    val id: String,
    val moves: List<TuningMove>,
    val basis: String,
    val atMs: Long,
) {
    /** 卡稳定 id（一轮一张；前缀在装配层加）。 */
    companion object {
        fun idOf(atMs: Long): String = "insula:$atMs"
    }
}

/** 岛叶自调卡的展示槽（进程级；重启即忘，账上有底）。 */
internal object InsulaCardStore {
    @Volatile
    private var latest: List<InsulaTuningCard> = emptyList()

    /** 一轮真调过才调；空表/跳过的轮不调 ⇒ 上一轮保持原样。 */
    fun publish(cards: List<InsulaTuningCard>) {
        latest = cards.toList()
    }

    /** 页签读这一份（只读）。 */
    fun latest(): List<InsulaTuningCard> = latest

    /** 人看过（卡上点了收尾）⇒ 撤下；账行一行不动。 */
    fun dismiss(id: String) {
        latest = latest.filterNot { it.id == id }
    }

    /** 测试专用：谁污染谁还原。 */
    fun resetForTest() {
        latest = emptyList()
    }
}
