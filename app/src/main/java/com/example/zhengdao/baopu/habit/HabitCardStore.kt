// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 固化建议卡的**进程内持有者**（676 `SubconsciousRowStore` 的房型）。
//
// 页签只认本进程最近一轮的建议：上一轮的结果保持原样（延后/跳过的轮不 publish）。
// 持久留痕在自己的 `习惯账.jsonl`（suggest/adopt 行），职责分开——这里不是账，是展示槽。
package com.example.zhengdao.baopu.habit

/** 固化建议卡的展示槽（进程级；重启即忘，账上有底）。 */
internal object HabitCardStore {
    @Volatile
    private var latest: List<HabitSuggestion> = emptyList()

    /** 一轮真出卡才调；跳过的轮不调 ⇒ 上一轮保持原样。 */
    fun publish(suggestions: List<HabitSuggestion>) {
        latest = suggestions.toList()
    }

    /** 页签读这一份（只读）。 */
    fun latest(): List<HabitSuggestion> = latest

    /** 按 id 找一张（采纳/收尾的分派口用；不存在 ⇒ null）。 */
    fun byId(id: String): HabitSuggestion? = latest.firstOrNull { it.id == id }

    /** 人看过（卡上点了收尾）⇒ 撤下；账行与账本一行不动。 */
    fun dismiss(id: String) {
        latest = latest.filterNot { it.id == id }
    }

    /** 采纳后把卡原位换新（wishId 已带上，正文换成"等确认"）；找不到就不换。 */
    fun replace(updated: HabitSuggestion) {
        latest = latest.map { if (it.id == updated.id) updated else it }
    }

    /** 测试专用：谁污染谁还原。 */
    fun resetForTest() {
        latest = emptyList()
    }
}
