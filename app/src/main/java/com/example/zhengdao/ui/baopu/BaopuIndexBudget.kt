// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十二批（§B 80 结构切片 · 纯逻辑抽取）：把技能索引「上限判据」收成一处纯函数。
//
// 为什么抽它：同一条 `总行数 + 新增行数 > INDEX_CAP` 在控制器里出现了两次
// （封卷提炼、确认提案），一处用候选条数、一处用两段文本的行数差——两份算术迟早漂。
// 抽出来只有一处算术，且 K2「超上限就拒绝、不截断」的语义可以 JVM 直测。**行为零变化**。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.SkillRules

internal object BaopuIndexBudget {

    /**
     * 加上 [adding] 行之后会不会超上限。
     *
     * 判据是 `>` 不是 `>=`：正好顶到 `INDEX_CAP` 行是合法的（搬移前就是 `total + adding > CAP`）。
     * [adding] 允许为负（提案把旧行换成新行、行数没涨）——负数只会更安全，不用特判。
     */
    fun wouldExceed(total: Int, adding: Int, cap: Int = SkillRules.INDEX_CAP): Boolean =
        total + adding > cap

    /**
     * 一份提案净增几行：`proposed` 比 `current` 多出的索引行数（可为负）。
     *
     * 两份文本都按同一个 [scope] 解析——跨层比较没有意义（B 层与 C 层的索引格式不同）。
     */
    fun addedIndexLines(proposed: String, current: String, scope: MemoryScope): Int =
        SkillRules.parseIndexLines(proposed, scope).size -
            SkillRules.parseIndexLines(current, scope).size
}
