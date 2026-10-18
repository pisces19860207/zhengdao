// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-19 用户口径：「好像也没有提意见哪些推荐删，哪些不推荐删，就那个意见卡？」⇒「加，
// 位置在输入栏的上方弹出」。
//
// `ask_user` 是抱朴**自己主动弹卡**的唯一通道：给出问题 + 2–4 个选项，用户点一个，
// 答案作为工具结果回灌，循环继续跑（不像确认卡那样只给「允许/拒绝」）。
//
// 三条纪律：
// 1. **选项上限 4、下限 2**：一个选项叫命令，五个往上用户不看了；
// 2. **推荐项排第一**（`recommended=true`），并且每项都要能带一句理由（`detail`）；
// 3. **没人回答就如实说**（超时/取消 ⇒ 工具结果写清楚），不许假装用户选了。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject

/** 选项卡上的一个选项。 */
internal data class AskUserOption(
    val id: String,
    val label: String,
    val detail: String = "",
    val recommended: Boolean = false,
)

/** 一次选项请求（UI 拿它渲染输入栏上方那张卡）。 */
internal data class AskUserRequest(val question: String, val options: List<AskUserOption>)

/** `ask_user`：让抱朴把「推荐删/建议留」这类意见做成一张卡让用户点。 */
internal object AskUserTool {

    /** 一屏最多几个选项。 */
    const val MAX_OPTIONS = 4

    /** 最少几个选项（一个选项等于命令，不该走卡片）。 */
    const val MIN_OPTIONS = 2

    /**
     * 谁来弹卡、谁来收答案（引擎在 [com.example.zhengdao.baopu.BaopuRuntime] 里接上）。
     * null = 当前没有界面（返回人话，不静默等）。
     */
    @Volatile
    var sink: ((AskUserRequest) -> AskUserOption?)? = null

    fun spec() = ToolSpec(
        name = "ask_user",
        description = "拿不准该删哪些、该按哪条走时，弹一张选项卡问用户（卡片出现在输入栏上方）。" +
            "给 2–4 个选项，把**你推荐的那个放第一并标 recommended**，每项可带一句理由。" +
            "用户点完你会收到他的选择，然后继续干——不用再自己猜，也不要在正文里重复问一遍。",
        permission = ToolPermission.FREE,
        inputSchema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "question": {"type": "string", "description": "一句话问清要选什么（≤60 字）"},
                "options": {
                  "type": "array",
                  "minItems": 2,
                  "maxItems": 4,
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {"type": "string", "description": "短标识（字母数字，如 keep / delete-big）"},
                      "label": {"type": "string", "description": "按钮上的字（≤20 字）"},
                      "detail": {"type": "string", "description": "一句理由/后果（可选）"},
                      "recommended": {"type": "boolean", "description": "推荐就 true（会排第一并高亮）"}
                    },
                    "required": ["label"]
                  }
                }
              },
              "required": ["question", "options"]
            }
            """.trimIndent(),
        ),
        executor = { args ->
            val question = args.optString("question").trim()
            if (question.isEmpty()) return@ToolSpec ToolOutcome.Failed("question 不能为空")
            val options = normalizeOptions(args.optJSONArray("options"))
                ?: return@ToolSpec ToolOutcome.Failed(
                    "options 要给 ${MIN_OPTIONS}–$MAX_OPTIONS 个，每个至少带 label",
                )
            val sink = sink
                ?: return@ToolSpec ToolOutcome.Failed("现在没有界面能弹选项卡（后台运行时不支持），请直接在正文里问")
            val picked = sink(AskUserRequest(question, options))
                ?: return@ToolSpec ToolOutcome.Failed("用户没有选（超时或取消了）：先按最保守的方案做，或停下来再问")
            val detail = picked.detail.trim()
            ToolOutcome.Ok(
                "用户选了：${picked.label}" + if (detail.isEmpty()) "" else "（$detail）",
            )
        },
        timeoutMs = ToolTimeout.FILE_MS,
    )

    /**
     * 选项归一（**纯函数**，JVM 直测）：
     * - 少于 [MIN_OPTIONS] / 多于 [MAX_OPTIONS] ⇒ 截断或判非法；
     * - 没有 id 的用序号补（`o1`…）；
     * - **推荐项排第一**（用户 2026-10-19：意见要先说明推荐哪个）。
     */
    fun normalizeOptions(raw: JSONArray?): List<AskUserOption>? {
        if (raw == null) return null
        val out = mutableListOf<AskUserOption>()
        for (i in 0 until raw.length()) {
            val obj = raw.optJSONObject(i) ?: continue
            val label = obj.optString("label").trim()
            if (label.isEmpty()) continue
            out += AskUserOption(
                id = obj.optString("id").trim().ifEmpty { "o${i + 1}" },
                label = label.take(20),
                detail = obj.optString("detail").trim(),
                recommended = obj.optBoolean("recommended", false),
            )
        }
        if (out.size < MIN_OPTIONS) return null
        val trimmed = out.take(MAX_OPTIONS)
        // 稳定排序：推荐的在前，其余保持原顺序
        return trimmed.sortedBy { if (it.recommended) 0 else 1 }
    }
}
