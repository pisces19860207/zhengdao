// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十五批（2026-11-09）· 治理中心「待办」那一块的**判据层**（纯函数，零 Android、零 IO、零 R）。
//
// 三件事在这里定死，屏幕层只许照着画：
//   1. **行里只有摘要 + 状态**（判据③）：[TodoRowView] 只有三个字段——id、摘要、状态。
//      想让界面多画点东西（正文、来源、右相理由）就得先改这个数据类，改不动就没有第二套口径；
//   2. **「确认」只出现在「已完成」上**（判据④）：`canConfirm` 唯一判据是 `state == COMPLETE`，
//      而「已完成」在数据层只有一个入口——右相判 PASS（541 的 `TodoRules.fold`）。
//      所以「右相没 PASS 却出现已完成」在这条链上没有生产者，本层不需要、也不许再造一个判断；
//   3. **一次只展开一块**（判据②）：[toggle] 是唯一的展开状态机——点同一块＝收回，
//      点另一块＝换它展开（原来那块自动收回）。屏幕只是把它的返回值存进一个 `String?`。
//
// 为什么判据要单开一层：Compose 树在 JVM 直测不了（同 322/539 的取舍），而"三字段""谁能点确认"
// "一次只展开一块"这三条都能在纯函数上逐条断言。
//
// ⚠️ 本层**不统计**：没有"今天新增几条"、没有完成率。收起态那两个数由 541 的
// `TodoRules.headerLine` 给，本层只转发（[header] 一行都不重写——重写就是第二套口径）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.todo.TodoCounts
import com.example.zhengdao.baopu.todo.TodoItem
import com.example.zhengdao.baopu.todo.TodoRules
import com.example.zhengdao.baopu.todo.TodoState

/**
 * 看板上的一行：**只有**摘要与状态（判据③）。
 *
 * 没有来源、没有正文、没有右相理由——不是因为"没显示"，是因为**这个类型里没有这些字段**：
 * 界面拿不到，也就画不出来。
 */
internal data class TodoRowView(
    /** 账本 id（点「确认」时回传给落盘口；界面上不显示）。 */
    val id: String,
    /** 一句话摘要（≤40 字由写入侧保证，541 的 `TodoRules.refusal`）。 */
    val summary: String,
    /** 状态签：待做 / 已做 / 已完成（文案在资源里，本层只给枚举）。 */
    val state: TodoState,
) {
    /** 「确认」只出现在「已完成」上（判据④；唯一判据，不许加第二条路）。 */
    val canConfirm: Boolean get() = state == TodoState.COMPLETE
}

/** 待办块的判据（纯函数）。 */
internal object TodoBoardRules {

    /** 这一块在"展开槽位"里的键（[toggle] 用；以后第二块可折叠时再加一个常量）。 */
    const val BLOCK_TODO: String = "todo"

    /**
     * 一次只展开一块。
     *
     * @param current 现在展开的是哪一块（null ＝全收起）
     * @param clicked 刚点的那一块
     * @return 点同一块 ⇒ null（收回）；点另一块 ⇒ 换成它（原来那块自动收回，不会两块同开）
     */
    fun toggle(current: String?, clicked: String): String? = if (current == clicked) null else clicked

    /** 收起态那一行（转发 541 的算法，**不重写**）。 */
    fun header(counts: TodoCounts): String = TodoRules.headerLine(counts)

    /** 看板行（已确认删除的不出现——`TodoRules.live` 的口径）。 */
    fun rows(items: List<TodoItem>): List<TodoRowView> = TodoRules.live(items).map { item ->
        TodoRowView(
            id = item.id,
            summary = TodoRules.rowText(item),
            state = item.state,
        )
    }
}
