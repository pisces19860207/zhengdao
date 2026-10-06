// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.oc

/**
 * 太极 Tab 的屏幕级状态与连接状态。
 *
 * 单独成文件（而非放在 [OcRepository] 内）的理由：**UI 层不应依赖 Repository 的
 * 内部结构**。把状态类型提到包级，UI 只认这些类型，Repository 的重构不会波及 UI。
 */

/** 屏幕级状态机。失败态带 [retryable]，供 UI 决定是否显示「重试」。 */
sealed interface TaijiPhase {
    /** 未打开会话 */
    data object Idle : TaijiPhase

    /** 正在首次加载（全量拉取 + 建立 SSE） */
    data object Loading : TaijiPhase

    data object Ready : TaijiPhase

    /**
     * 启动/连接失败。
     *
     * 项目原则「失败必须可见」：任何失败都要在此呈现并给出重试入口，
     * **不允许静默失败或假装成功**。
     */
    data class Failed(val message: String, val retryable: Boolean = true) : TaijiPhase
}

/**
 * SSE 连接状态。
 *
 * 注意 [Reconnecting] 与 [Connected] 的区分——UI 必须在非 [Connected] 时明确
 * 告知用户"正在重连"（否则用户会以为界面卡死）。
 */
enum class ConnectionState {
    Idle,
    Connecting,
    Connected,

    /** 已断开，正在按退避策略重连。[OcRepository] 持有重试次数。 */
    Reconnecting,
}

/**
 * 太极 Tab 的 UI 状态 —— 唯一真相源。
 *
 * Compose state 即真相；本类不含持久化。进程被系统杀掉后重新
 * [OcRepository.open] 即可从 serve 恢复（会话本就由宿主进程的 serve 持有）。
 */
data class TaijiState(
    val phase: TaijiPhase = TaijiPhase.Idle,
    val connection: ConnectionState = ConnectionState.Idle,
    val sessionId: String? = null,

    val messages: List<OcMessage> = emptyList(),

    /**
     * partId → 内容。
     *
     * 单独存一份而不在 [messages] 里就地更新，是为了流式增量能 O(1) 定位当前文本
     * ——否则每收到一个 delta 都要线性查找part，消息多了会明显卡顿。
     */
    val parts: Map<String, OcPart> = emptyMap(),

    /** 待处理的权限请求。非 null 时 UI 必须弹底部抽屉（否则 Agent 卡死）。 */
    val pendingPermission: OcPermission? = null,

    val todos: List<OcTodo> = emptyList(),
    val isStreaming: Boolean = false,
    val input: String = "",

    /** 最近一次错误，用于顶部横幅。null 表示无。 */
    val lastError: String? = null,

    /** 当前重连次数（第 N 次），供 UI 显示"第 N 次重试"。 */
    val reconnectAttempt: Int = 0,

    // ── 模型池（v1.0 任务一）──

    /** 可选模型目录（GET /api/model；进会话/打开选择器时拉取，可能为空——models.dev 不可达时目录为空）。 */
    val models: List<OcModel> = emptyList(),

    /** 当前生效模型显示名：session.step.started 事件的 model 字段（服务端实际在用的），
     *  或本地 override 刚设置时的值；null = 服务端默认。 */
    val currentModel: String? = null,

    /** 本地模型覆盖（providerID+id）。仅影响当前会话；持久化在调用方（TaijiScreen 的 prefs）。 */
    val modelOverride: OcModel? = null,

    /** 是否已完成过一次全量加载。false 时显示 loading 而非空列表。 */
    val loadedOnce: Boolean = false,
)