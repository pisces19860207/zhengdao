// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：OpenCode 官方 serve 模式的会话与消息数据结构（由其 OpenAPI
// 描述生成 / schema v5），字段命名以**实际打包版本**为准。
package com.example.zhengdao.oc

/**
 * OpenCode 会话与消息的数据模型。
 *
 * ## ⚠️ 字段名的确定方法（不要照抄官方主站文档）
 *
 * 太极 Tab 跑的是 **社区 bionic 版**（`Hope2333/opencode-termux`，v2.0.x），
 * 由 binary surgery 移植而来（Bun 官方不支持 Android 交叉编译）。API 层理论上
 * 与上游一致，但**官方文档横跨 v1/v2/v3 三代，字段名会变**（如 v2 命名空间下
 * 事件叫 `EventSessionCreated`，v1 是 `session.created`）。
 *
 * **动作**：以实际打包版本（[OcManager.VERSION]）对应的 `openapi.json` 逐字段核对，
 * 并逐个探测端点存在性。参见 docs/milestones/证道-bionic版查证补充.md。
 *
 * 当前字段名取自社区版README 与上游 v2 API 的**交集**，属"待实测确认"状态。
 * 若实测不符，改这里即可（数据层与 UI 层已解耦）。
 */

// ── 会话 ──────────────────────────────────────────────────────────────

data class OcSession(
    val id: String,
    val title: String? = null,
    val timeCreated: Long? = null,
    val timeUpdated: Long? = null,
)

/**
 * 会话摘要 —— **历史列表专用**（v1.1 第一阶段）。
 *
 * 在 [OcSession] 元数据之外附带 [messageCount]，供列表项显示"N 条消息"。
 * 之所以不把它并进 [OcSession]：条数要额外发一次 `GET /session/{id}/message` 才拿得到，
 * 与纯元数据的拉取成本不同；分开才能「先出列表、条数异步补」。
 *
 * [messageCount] 为 `null` 表示**条数未取到**（该会话消息端点失败），UI 显示为省略而非 0
 * ——0 与"不知道"必须区分，否则用户会以为空会话。
 */
data class OcSessionSummary(
    val id: String,
    val title: String? = null,
    /** 最近更新时间（毫秒时间戳），用于分组与显示；null 表示服务端未给。 */
    val updatedAt: Long? = null,
    val messageCount: Int? = null,
)

// ── 消息 ──────────────────────────────────────────────────────────────

/**
 * 一条消息。[parts] 承载多段内容（文本 / 推理 / 工具调用 / 文件…）。
 *
 * 实际 API 的 Message 是 `{ info, parts }` 二元结构；本模型把它拍平成
 * "消息 + 它自己的 part 列表"，因为 SSE 增量是**按 partId 定位**的（见 SseClient）。
 */
data class OcMessage(
    val id: String,
    val role: Role,
    val parts: List<OcPart> = emptyList(),
    val timeCreated: Long? = null,
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

// ── Part（消息的一段内容）─────────────────────────────────────────────

/**
 * ⚠️ part 类型在社区版上取到[PartKind.UNKNOWN] 是**预期行为**，不是 bug。
 * 上游持续新增 part 类型，未知类型必须保留原文而不是丢弃（详见设计文档 §3.2）。
 */
sealed interface OcPart {
    val id: String

    data class Text(
        override val id: String,
        val text: String = "",
        /** true = 仍在流式输出中 */
        val streaming: Boolean = false,
    ) : OcPart

    data class Reasoning(
        override val id: String,
        val text: String = "",
    ) : OcPart

    data class Tool(
        override val id: String,
        val toolName: String = "",
        val state: ToolState = ToolState.Unknown,
        val input: String? = null,      // 摘要文本（可能是 JSON）
        val output: String? = null,
    ) : OcPart

    data class File(
        override val id: String,
        val filename: String = "",
        val mime: String? = null,
    ) : OcPart

    data class Unknown(
        override val id: String,
        val type: String = "",
        val raw: String = "",
    ) : OcPart
}

enum class PartKind { TEXT, REASONING, TOOL, FILE, UNKNOWN }

/** 工具调用的三态 —— 与设计文档 §5.2 的 ToolCallCard 视觉对应。 */
enum class ToolState { Running, Success, Error, Unknown }

// ── 待办（Agent 自跟踪的任务清单）────────────────────────────────────

data class OcTodo(
    val id: String,
    val content: String,
    val status: TodoStatus = TodoStatus.Pending,
) {
    enum class TodoStatus { Pending, Running, Completed, Cancelled }
}

// ── 权限请求（Agent 要改文件/执行命令时等待用户批准）─────────────────

/**
 * 权限请求。
 *
 * ⚠️ 字段以实测 openapi 的 `Permission.Request` 为准：
 * `{ id(^per), sessionID(^ses), action, resources:[], save:[], metadata, source, message }`
 * —— **没有** 旧版的 `permissionID` / `title` / `description`。
 *
 * 不显示 [detail]/[resources] 用户就只能盲批，故**必须展示**。
 */
data class OcPermission(
    /** requestID（回执端点 `/permission/{requestID}/reply` 用） */
    val permissionId: String,
    val sessionId: String,
    /** 工具名（action），如 "bash" / "edit" / "write" */
    val title: String = "",
    /** 具体目标：文件路径或命令。**必须显示，否则用户只能盲批。** */
    val detail: String? = null,
    val type: String? = null,
    /** resources 原文列表（UI 可逐条展示） */
    val resources: List<String> = emptyList(),
)

// ── 服务端健康与版本 ──────────────────────────────────────────────────

data class OcHealth(
    val version: String = "",
)

/** HTTP API 的错误体（OpenCode 的 global error handler 返回 `{name, data}`）。 */
data class OcApiError(
    val name: String = "",
    val data: String? = null,
)