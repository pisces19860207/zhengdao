// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui.baopu

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf

/**
 * 抱朴页「跨 Tab / 跨转屏 / 跨终端往返」存活的 UI 状态（§B 顺序 2，P2-2 + P2-3，2026-10-14）。
 *
 * ## 为什么需要它（两个缺陷是同一根）
 * - **P2-3**：`BaopuScreen` 的会话 id 原先用 `rememberSaveable` —— 那只跨**配置变更**（转屏）。
 *   切到底栏别的 Tab 时 BaopuScreen 离开 Composition，id 丢了 ⇒ 回来 `repo.open(scope, null)`
 *   ⇒ **新建一个会话**，历史消息全丢。
 * - **P2-2**：输入框的文本在 `OcRepository`（进程级单例）里，本不该随切 Tab 丢；但只要
 *   P2-3 把会话换掉，输入框就跟着被清空 ⇒ **草稿丢失是次生现象**。
 *
 * 所以本 holder 同时存两样，口径与 `ui/browser/BrowserHolder`（§B 32）一致：
 * **实例长活 + UI 状态留在 holder**，而不是靠 `remember` / `rememberSaveable`。
 *
 * ## 覆盖范围（派工单要求）
 * ① 应用内 Tab 切换 ② 转屏 ③ 从终端页回到抱朴页 —— 三者都只是"离开/重建 Composition"，
 * 只要状态在进程级的 holder 里就都保得住。
 *
 * ⚠️ 已知边界（照实说明）：`OcRepository.setInput(text: String)` 只收文本、不收光标，
 * 而输入框 value 由 repo 驱动 ⇒ **文本草稿能按会话恢复，光标位置恢复不了**。
 * 要让光标也回来，得把输入框改成吃 `TextFieldValue`（牵动 BaopuComponents 的接口），
 * 建议单独一刀，不混在这里。
 */
@Stable
object BaopuStateHolder {

    private val session: MutableState<String?> = mutableStateOf(null)

    /** 当前会话 id（null = 还没有/已清空）。 */
    val sessionId: String? get() = session.value

    /** 会话 id 的可观察状态（Compose 直接读）。 */
    val sessionState: MutableState<String?> get() = session

    /** 记下当前会话 id。 */
    fun setSession(id: String?) {
        session.value = id
    }

    // ── P2-2：按会话各留一份输入草稿 ────────────────────────────────────────

    private val drafts = mutableStateMapOf<String, String>()

    /** 取某个会话的草稿（没有则 null）。 */
    fun draft(id: String?): String? = id?.let { drafts[id] }

    /** 存下某个会话的草稿（空文本不存，避免堆积无意义条目）。 */
    fun saveDraft(id: String?, text: String) {
        if (id == null) return
        if (text.isEmpty()) drafts.remove(id) else drafts[id] = text
    }

    /** 当前有几个会话存了草稿（观察用 / 单测断言）。 */
    fun draftCount(): Int = drafts.size

    /** 仅单测用：清空（object 单例会跨用例串味）。 */
    internal fun resetForTest() {
        session.value = null
        drafts.clear()
    }
}
