// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.oc

import org.json.JSONObject

/**
 * 第㉙刀（§B 顺序 5 / issue #9）：新接入的 4 类 SSE 事件里，需要从 payload 取字段的那两种
 * 的**纯函数**提取器。不依赖 Android —— JVM 单测直接跑（`SseEventParserTest`）。
 *
 * ## 为什么写成"多路径依次尝试"
 *
 * 服务端字段位置**不稳定**：同一语义在不同事件/版本里挂在 `properties` 下（`parsePartRemoved`
 * 读的就是 `properties.partID`）、也可能挂在 `data` 或 `data.properties` 下
 * （`parseStepModel` 读的是 `data.model`）。这正是 E-054 的教训：**路径猜错时必须仍有可读
 * 输出**，不能因为"字段没猜到"就把事件悄悄丢掉。所以这里：
 * - 依次摸多个作用域与多个键名；
 * - 全都取不到 ⇒ 返回 null，**由调用方落一条「收到了但没认出字段」的日志**（事件依然不丢，
 *   原文仍在 RunLog 里）；
 * - 任何异常（坏 JSON 等）都被 `runCatching` 吃掉 ⇒ 只返回 null，绝不抛（不许带崩 SSE 循环）。
 *
 * ## 刻意不做的事
 *
 * 不把 root 的 `id` 当作实体 id —— 那是**事件自身**的 id（真机抓包：`{"id":"evt_…","type":…,
 * "data":{…}}`），拿它当消息 id 会把「删除第 N 条」删成一条都不删。
 */
internal object SseEventPayload {

    /** 按顺序摸的作用域：`properties` / `data` / `data.properties`（不含 root，见类注释）。 */
    private fun innerScopes(root: JSONObject): List<JSONObject> = listOfNotNull(
        root.optJSONObject("properties"),
        root.optJSONObject("data"),
        root.optJSONObject("data")?.optJSONObject("properties"),
    )

    /**
     * `session.model.selected` → 当前模型标识，口径与 [OcRepository] 里 session.step.started
     * 的 `parseStepModel` 一致（对象形态拼成 `providerID/modelID`），好让顶栏模型胶囊不因
     * 来源不同而显示两种写法。
     */
    fun modelOf(raw: String): String? = runCatching {
        val root = JSONObject(raw)
        // root 也要摸：`model` 不是事件信封的字段，无害；modelID 在 root 属于宽松兜底。
        for (scope in listOf(root) + innerScopes(root)) {
            when (val m = scope.opt("model")) {
                is String -> if (m.isNotEmpty()) return@runCatching m
                is JSONObject -> {
                    val pid = m.optString("providerID")
                    val mid = m.optString("modelID").ifEmpty { m.optString("id") }
                    val joined = listOf(pid, mid).filter { it.isNotEmpty() }.joinToString("/")
                    if (joined.isNotEmpty()) return@runCatching joined
                }
                else -> Unit
            }
            for (key in listOf("modelID", "modelId")) {
                scope.optString(key).takeIf { it.isNotEmpty() }?.let { return@runCatching it }
            }
        }
        null
    }.getOrNull()

    /** `message.removed` → 被删消息的 id（依次摸 `message.id` / `messageID` / `messageId` / `id`）。 */
    fun messageIdOf(raw: String): String? = runCatching {
        val root = JSONObject(raw)
        for (scope in innerScopes(root)) {
            scope.optJSONObject("message")?.optString("id")
                ?.takeIf { it.isNotEmpty() }?.let { return@runCatching it }
            for (key in listOf("messageID", "messageId", "id")) {
                scope.optString(key).takeIf { it.isNotEmpty() }?.let { return@runCatching it }
            }
        }
        null
    }.getOrNull()
}
