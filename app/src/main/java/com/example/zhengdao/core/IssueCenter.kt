// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.core

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.example.zhengdao.rootfs.RunLog

/**
 * 「最近问题」中枢（#4 全 App 错误提示走查，2026-10-09）。
 *
 * ## 为什么要有它
 *
 * 走查发现同一个毛病反复出现：**失败只写 `RunLog`，界面上什么都没有**。
 * 用户看到的往往是"点了没反应"或"功能不对"，而日志躺在
 * `/sdcard/Download/证道/logs/zhengdao-log.txt` 里——出了问题没人会去看，
 * 也不该要求用户去看。
 *
 * 这个类的定位是**唯一的那一个出口**：任何后台/静默失败都往这里报一条，
 * 主页把它渲染成「最近问题（N）」卡片，每条尽量带一个**能点的去路**
 * （重试 / 修复 / 去设置 / 去授权 / 反馈）。日志照旧写——它仍是无 UI 场景的
 * 唯一痕迹，两者不是替代关系。
 *
 * ## 设计约束（都是被真机问题逼出来的）
 *
 * - **按 id 去重**：同一个失败在重试/轮询里会被反复触发（例如 serve 每次拉起都超时）。
 *   同 id 报到就**替换**那一条并把时间刷新到最新，列表不会长成一堆重复项。
 * - **上限 20 条**：这是"最近问题"不是历史归档；更早的痕迹在 RunLog 里。
 * - **不持有 Context**：可在任意线程、任意层（oc / terminal / ui）调用，
 *   也就不会有 Activity 泄漏。写 [State] 由 Compose 自动驱动重组。
 * - **纯数据 + 纯函数**：动作只是字符串标识，**不在这里执行**——谁来执行是 UI 层的事
 *   （主页 / 设置页各自解释），这样这里可以单测，也不会把 UI 依赖灌进 oc/terminal。
 */
object IssueCenter {

    /**
     * 一条问题。
     *
     * @param id     稳定标识：同 id 视为同一问题的再次发生（替换，不追加）。
     * @param title  一句话说清"哪儿坏了"（人话，别带异常类名）。
     * @param detail 现状与后果，可含关键数值（路径、秒数、错误的 SHA 等）。
     * @param actionLabel 去路按钮文案；null = 只报告，没有可点的动作。
     * @param actionId    去路标识（[ACTION_REPAIR_ENV] 等），由 UI 层解释。
     */
    data class Issue(
        val id: String,
        val title: String,
        val detail: String,
        val actionLabel: String? = null,
        val actionId: String? = null,
        val at: Long = 0L,
    )

    // ── 去路标识（UI 层解释；不在此处执行任何动作）──

    /** 设置页「修复环境」：重解压系统层（rootfs / proot 损坏的兜底）。 */
    const val ACTION_REPAIR_ENV = "repair-env"

    /** 重新拉起太极 serve（进程还在但没就绪 / 上次启动超时）。 */
    const val ACTION_RETRY_SERVE = "retry-serve"

    /** 杀掉再拉起太极 serve（密码没解析到 ⇒ 后续请求全程 401 时用）。 */
    const val ACTION_RESTART_SERVE = "restart-serve"

    /** 打开设置页（通用去路：修复、检查更新、看占用都在这儿）。 */
    const val ACTION_OPEN_SETTINGS = "open-settings"

    /** 直接跳到系统「所有文件访问」授权页。 */
    const val ACTION_GRANT_STORAGE = "grant-storage"

    /** 打开运行日志（终端里 tail 那份文本）。 */
    const val ACTION_VIEW_LOGS = "view-logs"

    /** 去 GitHub 提 issue（用户看不懂/修不了的失败，给一条反馈通道）。 */
    const val ACTION_FEEDBACK = "feedback"

    /** 上限：超出丢最旧的。 */
    const val LIMIT = 20

    private val _issues = mutableStateOf<List<Issue>>(emptyList())

    /** 供 Composable 以 `by` 读取（最新在前）；只读，避免调用方误写。 */
    val issues: State<List<Issue>> get() = _issues

    /**
     * 上报一条问题。**任何线程可调**（含 IO 线程里的失败回调）。
     *
     * 同 id 覆盖：保留新的一条、丢掉旧的（列表里同 id 永远只有一条，最新在前）。
     */
    fun report(issue: Issue) {
        val stamped = issue.copy(at = System.currentTimeMillis())
        synchronized(this) {
            val rest = _issues.value.filterNot { it.id == issue.id }
            _issues.value = (listOf(stamped) + rest).take(LIMIT)
        }
        // 日志是"无人看界面"场景下的唯一痕迹，这里必须同时留一份（含 id，便于 grep 定位）。
        RunLog.log("问题[${issue.id}]: ${issue.title} — ${issue.detail}")
    }

    /** [report] 的便捷重载（最常见形态：id + 标题 + 说明 + 一个去路）。 */
    fun report(
        id: String,
        title: String,
        detail: String,
        actionLabel: String? = null,
        actionId: String? = null,
    ) = report(Issue(id, title, detail, actionLabel, actionId))

    /**
     * 该问题已消失（重试成功、用户已处理、体检转绿）⇒ 撤下。
     *
     * 与 [report] 的"替换"语义互补：report 说"现在坏了"，resolve 说"现在好了"。
     */
    fun resolve(id: String) {
        synchronized(this) {
            val rest = _issues.value.filterNot { it.id == id }
            if (rest.size != _issues.value.size) _issues.value = rest
        }
    }

    /** 用户点「清空」：只清列表，日志不动。 */
    fun clear() {
        synchronized(this) { _issues.value = emptyList() }
    }

    /** 当前快照（单测与日志用；Compose 请用 [issues]）。 */
    fun snapshot(): List<Issue> = _issues.value
}
