// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 2026-10-19 用户口径：「好像也没有提意见哪些推荐删，哪些不推荐删，就那个意见卡？」⇒「加，
// 位置在输入栏的上方弹出」。
//
// 2026-10-25 用户口径（第二百零三批）：「假如那些卡有好几个问题，是不是都可以在卡内回答啊？
// 不用每一个问题分别弹一次吧？」「每个问题弹一次的话真的会打扰到」⇒ `ask_user` 从
// **一个问题／一次** 扩成 **一组问题、一张卡、一次提交**：N 个疑问一次答完、一条工具结果
// 回 `{"answers": {"id": value}}`，不再一题一轮。
//
// `ask_user` 是抱朴**自己主动弹卡**的唯一通道：给出问题 + 2–4 个选项，用户点一个，
// 答案作为工具结果回灌，循环继续跑（不像确认卡那样只给「允许/拒绝」）。
//
// 纪律（旧三条全保留，第四条新增）：
// 1. **选项上限 4、下限 2**：一个选项叫命令，五个往上用户不看了（SINGLE/MULTI 的问题项同规）；
// 2. **推荐项排第一**（`recommended=true`，旧单问形状；问题组项不设推荐）；
// 3. **没人回答就如实说**（超时/取消 ⇒ 工具结果写清楚），不许假装用户选了；
// 4. **一卡最多 [MAX_PER_CARD] 项**，超了引擎侧自动拆卡接着问（[AskGroupRules.split]）；
//    一次请求最多 [MAX_QUESTIONS] 项，再多就该分轮说。
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

/**
 * 问题组里一问的**输入控件类型**（第二百零三批）。
 *
 * 只决定卡上画什么控件、答案长什么样，与卡片的「事由分类」[AskKind] 是两码事：
 * 御批卡（组授权/人设提案）的 kind 还是 [AskKind]，问题项的 kind 在这里。
 */
internal enum class AskQuestionKind(val key: String) {
    /** 单选（2–4 个选项里挑一个；答案＝选项 id）。 */
    SINGLE("SINGLE"),

    /** 多选（2–4 个选项里挑任意个；答案＝选项 id 逗号连接，没挑＝空串）。 */
    MULTI("MULTI"),

    /** 自由文本（答案＝用户输入原样）。 */
    TEXT("TEXT"),

    /** 数字（答案＝用户输入；非数字在卡上就地拒绝，提交按钮置灰）。 */
    NUMBER("NUMBER"),

    /** 是否（答案＝"true"/"false"）。 */
    BOOL("BOOL"),
    ;

    companion object {
        /** 键 → 档位（认不出返回 null，由解析层报人话错误，不静默猜测）。 */
        fun fromKey(key: String): AskQuestionKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * 问题组里的一问（第二百零三批）。
 *
 * [id] 是答案回灌的锚：工具结果里 `answers` 用它做键，**全批不得重复**——
 * 重复 id 就是「答案串位」的源头，解析层直接拒（负例 1 的第一道闸）。
 * [required] 必答：没填 ⇒ 提交按钮置灰并指出是哪项；[default] 有值 ⇒ 卡上给「按默认」，
 * 全部可免答时再给「都不改」原样回默认。
 */
internal data class AskQuestion(
    val id: String,
    val title: String,
    val kind: AskQuestionKind = AskQuestionKind.SINGLE,
    val options: List<AskUserOption> = emptyList(),
    val required: Boolean = false,
    val default: String = "",
)

/**
 * 一次选项请求（UI 拿它渲染输入栏上方那张卡）。
 *
 * 第一百零四批（UI P0）补三个**带默认值的展示字段**——语义一个字没动，只是让
 * 壳层知道「这是哪一种事、关于谁、附文是什么」，好把它渲染进决策卡家族
 * （`ui/baopu/DecisionCard.kt`，设计稿 v1.4 §2）：
 * - [kind]：`ask_user` 工具自己的提问（默认）；97 的组授权、96 的人设提案
 *   分别由 [com.example.zhengdao.baopu.agent.GroupApprovalCard] /
 *   [com.example.zhengdao.baopu.agent.PersonaApprovalCard] 在构造时盖上；
 * - [subject]：授权组 key（`browser`…）或人设层（`B`/`C`）——槽位判据要稳定 id；
 * - [detailText]：附文（人设提案的条款全文；授权卡不需要，留空）。
 *
 * 第二百零三批再加两个**带默认值的尾部字段**，老调用一个字都不用改：
 * - [questions]：问题组（空 ＝ 旧单问题形状，内部包成 1 项，见 [allQuestions]）；
 * - [note]：拆卡续问提示（引擎只放在第一张卡上：「另有 N 项，答完这张接着来」）。
 *
 * 为什么不另立一个「带类型的请求」类：引擎/工具侧只认这一个形状，多加一类
 * 就要在 sink 上分叉；默认值保证既有调用方与测试原样编译。
 */
internal data class AskUserRequest(
    val question: String,
    val options: List<AskUserOption>,
    val kind: AskKind = AskKind.QUESTION,
    val subject: String = "",
    val detailText: String = "",
    /** 问题组（第二百零三批）。空 ＝ 旧单问题形状；派工单原话「旧形状内部包成 1 项」。 */
    val questions: List<AskQuestion> = emptyList(),
    /** 拆卡续问提示（只有第一张卡非空；[AskGroupRules.moreNote] 造）。 */
    val note: String = "",
) {
    /**
     * 这张卡上的问题，**旧形状也在这里露面**：没给 [questions] 时把
     * （[question], [options]）包成 1 项必答题——旧调用零改动，但下游
     * （拆卡/账本 key/界面）只认问题组这一种形状。
     */
    val allQuestions: List<AskQuestion>
        get() = questions.ifEmpty {
            listOf(
                AskQuestion(
                    id = "q1",
                    title = question,
                    kind = if (options.isEmpty()) AskQuestionKind.TEXT else AskQuestionKind.SINGLE,
                    options = options,
                    required = true,
                ),
            )
        }
}

/**
 * 这张选项卡是哪一种事（第一百零四批；只影响渲染与槽位归类，不影响执行）。
 */
internal enum class AskKind {
    /** `ask_user` 工具：抱朴自己拿不准时问用户（既有形状，走原选项卡）。 */
    QUESTION,

    /** 工具组授权（97：本次会话/一直/取消）——决策卡·授权变体。 */
    GROUP_AUTHORIZATION,

    /** B/C 人设提案（96：拒绝/本工作区/全局）——决策卡·提案变体。 */
    PERSONA_PROPOSAL,
}

/** `questions` 解析产物：要么全对，要么一句人话（哪个下标、错在哪）。 */
internal sealed interface AskQuestionsParse {
    data class Ok(val questions: List<AskQuestion>) : AskQuestionsParse

    data class Bad(val reason: String) : AskQuestionsParse
}

/** `ask_user`：让抱朴把「推荐删/建议留」这类意见做成一张卡让用户点。 */
internal object AskUserTool {

    /** 一屏最多几个选项。 */
    const val MAX_OPTIONS = 4

    /** 最少几个选项（一个选项等于命令，不该走卡片）。 */
    const val MIN_OPTIONS = 2

    /** 一张卡最多几问（第二百零三批；超了引擎侧拆卡，见 [AskGroupRules.split]）。 */
    const val MAX_PER_CARD = 5

    /** 一次请求最多几问（拆卡也要有边：20 问拆下来是整 4 张；再多请分轮问）。 */
    const val MAX_QUESTIONS = 20

    /**
     * 谁来弹卡、谁来收答案（引擎在 [com.example.zhengdao.baopu.BaopuRuntime] 里接上）。
     * null = 当前没有界面（返回人话，不静默等）。
     */
    @Volatile
    var sink: ((AskUserRequest) -> AskUserOption?)? = null

    /**
     * 问题组的弹卡口（第二百零三批）：阻塞到**整组**答完，一次回全批答案；
     * 超时/取消回 null（与 [sink] 同一条「如实说没人答」的纪律）。
     * null = 当前没有界面（返回人话，不静默等）。
     */
    @Volatile
    var groupSink: ((AskUserRequest) -> Map<String, String>?)? = null

    fun spec() = ToolSpec(
        name = "ask_user",
        description = "拿不准该删哪些、该按哪条走时，弹一张卡问用户（卡片出现在输入栏上方）。" +
            "一个疑问：给 question + 2–4 个 options，推荐的那个标 recommended 排第一。" +
            "几个疑问一起：给 questions（一卡最多 ${MAX_PER_CARD} 项，超了自动拆卡接着问，" +
            "一次最多 $MAX_QUESTIONS 项），用户一次答完，你收到一条 {\"answers\": {\"问题id\": 答案}}，" +
            "别再一题一题弹。用户点完/答完你会收到他的选择，然后继续干——不用自己猜，也不要在正文里重复问一遍。",
        permission = ToolPermission.FREE,
        // 第九十二批：不改盘，但它要**阻塞等人**（最长 15 分钟）⇒ 绝不许进并行段
        readOnly = true,
        inputSchema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "question": {"type": "string", "description": "一句话问清要选什么（≤60 字）；与 questions 二选一"},
                "options": {
                  "type": "array",
                  "minItems": 2,
                  "maxItems": 4,
                  "description": "2–4 个选项（一个选项等于命令，五个往上用户不看了）；推荐的那个标 recommended 会排第一",
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
                },
                "questions": {
                  "type": "array",
                  "description": "问题组：N 个疑问合一张卡一次答完（与 question/options 二选一，别混用）。每项一个 id 当答案的键，重复 id 会被拒",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id": {"type": "string", "description": "答案的键（缺省按序号 q1/q2…补）；全批不得重复"},
                      "title": {"type": "string", "description": "这个问题问什么（一行，≤60 字）"},
                      "kind": {"type": "string", "enum": ["SINGLE", "MULTI", "TEXT", "NUMBER", "BOOL"], "description": "控件类型：SINGLE 单选 / MULTI 多选（都给 2–4 个 options，答案=选项 id，MULTI 逗号连接）/ TEXT 文本 / NUMBER 数字 / BOOL 是否（true/false）。缺省：给了 options 按 SINGLE，没给按 TEXT"},
                      "options": {
                        "type": "array",
                        "minItems": 2,
                        "maxItems": 4,
                        "description": "SINGLE/MULTI 的 2–4 个选项（TEXT/NUMBER/BOOL 不用给）",
                        "items": {
                          "type": "object",
                          "properties": {
                            "id": {"type": "string", "description": "选项 id（缺省 o1/o2…补）"},
                            "label": {"type": "string", "description": "选项上的字（≤20 字）"}
                          },
                          "required": ["label"]
                        }
                      },
                      "required": {"type": "boolean", "description": "必答就 true：没填提交按钮置灰并点名"},
                      "default": {"type": "string", "description": "默认值：SINGLE 填选项 id；MULTI 填逗号连接的选项 id；NUMBER 填数字；BOOL 填 true/false；TEXT 填文字。有默认值的项可「按默认」跳过"}
                    },
                    "required": ["title"]
                  }
                }
              },
              "required": [],
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
        executor = { args ->
            val questionsRaw = args.optJSONArray("questions")
            if (questionsRaw != null) {
                executeGroup(questionsRaw, args.has("question") || args.has("options"))
            } else {
                executeLegacy(args)
            }
        },
        // 第四十八批 P0-1：这条工具等的是**用户**（引擎侧上限 RunRules.CONFIRM_TIMEOUT_MS
        // ＝ 15 分钟），不是文件 I/O——用 ToolTimeout.FILE_MS（5 秒）会让用户还没点就
        // 被判「执行超时」，且单线程工具池被占死到 15 分钟、后续工具逐个排队超时。
        // CONFIRM_MS = 15 分钟 + 5 秒：比引擎多留 5 秒把「没人回答」如实回灌。
        timeoutMs = ToolTimeout.CONFIRM_MS,
    )

    /** 旧单问题形状：一个 question + 2–4 个 options，回「用户选了：X」。原样保留。 */
    private fun executeLegacy(args: JSONObject): ToolOutcome {
        val question = args.optString("question").trim()
        if (question.isEmpty()) return ToolOutcome.Failed("question 不能为空")
        val options = normalizeOptions(args.optJSONArray("options"))
            ?: return ToolOutcome.Failed(
                "options 要给 ${MIN_OPTIONS}–$MAX_OPTIONS 个，每个至少带 label",
            )
        val sink = sink
            ?: return ToolOutcome.Failed("现在没有界面能弹选项卡（后台运行时不支持），请直接在正文里问")
        val picked = sink(AskUserRequest(question, options))
            ?: return ToolOutcome.Failed("用户没有选（超时或取消了）：先按最保守的方案做，或停下来再问")
        val detail = picked.detail.trim()
        return ToolOutcome.Ok(
            "用户选了：${picked.label}" + if (detail.isEmpty()) "" else "（$detail）",
        )
    }

    /** 问题组形状（第二百零三批）：N 项一次答完，回 `{"answers": {"id": value}}` 一条。 */
    private fun executeGroup(raw: JSONArray, mixed: Boolean): ToolOutcome {
        if (mixed) {
            return ToolOutcome.Failed(
                "questions 与 question/options 是两套形状只能给一套：问一组用 questions；问一个用 question+options",
            )
        }
        val questions = when (val parsed = parseQuestions(raw)) {
            is AskQuestionsParse.Bad -> return ToolOutcome.Failed(parsed.reason)
            is AskQuestionsParse.Ok -> parsed.questions
        }
        if (questions.size > MAX_QUESTIONS) {
            return ToolOutcome.Failed(
                "一次最多问 $MAX_QUESTIONS 个问题（现在 ${questions.size} 个）：太长的清单请分轮问，或先在正文里列出让用户自己挑",
            )
        }
        val groupSink = groupSink
            ?: return ToolOutcome.Failed("现在没有界面能弹提问卡（后台运行时不支持），请直接在正文里问")
        val answers = groupSink(AskUserRequest(question = "", options = emptyList(), questions = questions))
            ?: return ToolOutcome.Failed("用户没有答（超时或取消了）：先按默认方案做，或停下来再问")
        return ToolOutcome.Ok(JSONObject().put("answers", JSONObject(answers)).toString())
    }

    /**
     * 问题组解析（**纯函数**，JVM 直测）：
     * - title 必填、kind 认不出就报人话（带可用清单）；
     * - SINGLE/MULTI 要 2–[MAX_OPTIONS] 个 options（TEXT/NUMBER/BOOL 给了也忽略）；
     * - id 缺省按序号补（`q1`…）；**全批重复 id 直接拒**——答案按 id 回，重复必串位；
     * - default 要能对上：SINGLE/MULTI 的值必须是本项 options 的 id，NUMBER 必须是数字，
     *   BOOL 必须是 true/false（对不上的默认值会在卡上摆一个永远填不出来的答案）。
     */
    fun parseQuestions(raw: JSONArray): AskQuestionsParse {
        if (raw.length() == 0) {
            return AskQuestionsParse.Bad("questions 不能是空数组：要问就至少给 1 项，或改用 question+options 问一个")
        }
        val out = mutableListOf<AskQuestion>()
        for (i in 0 until raw.length()) {
            val obj = raw.optJSONObject(i)
                ?: return AskQuestionsParse.Bad("questions[$i] 不是对象：每项要给 {id, title, kind, …}")
            val title = obj.optString("title").trim()
            if (title.isEmpty()) {
                return AskQuestionsParse.Bad("questions[$i].title 不能为空：每个问题都要有一行问什么")
            }
            val kindKey = obj.optString("kind").trim()
            val optionsRaw = obj.optJSONArray("options")
            val kind = if (kindKey.isEmpty()) {
                // 缺省按形状定：给了 options 就是单选，没给就是文本（不许猜出第四种东西）
                if (optionsRaw != null && optionsRaw.length() > 0) {
                    AskQuestionKind.SINGLE
                } else {
                    AskQuestionKind.TEXT
                }
            } else {
                AskQuestionKind.fromKey(kindKey) ?: return AskQuestionsParse.Bad(
                    "questions[$i].kind 不认识：「$kindKey」（可用：SINGLE / MULTI / TEXT / NUMBER / BOOL）",
                )
            }
            val options = if (kind == AskQuestionKind.SINGLE || kind == AskQuestionKind.MULTI) {
                normalizeGroupOptions(optionsRaw)
                    ?: return AskQuestionsParse.Bad(
                        "questions[$i] 是 $kind，要给 $MIN_OPTIONS–$MAX_OPTIONS 个 options（每个至少带 label）",
                    )
            } else {
                emptyList()
            }
            val id = obj.optString("id").trim().ifEmpty { "q${i + 1}" }
            val default = obj.optString("default").trim()
            default.takeIf { it.isNotEmpty() }?.let { d ->
                val problem = defaultProblem(i, kind, d, options)
                if (problem != null) return AskQuestionsParse.Bad(problem)
            }
            out += AskQuestion(
                id = id,
                title = title,
                kind = kind,
                options = options,
                required = obj.optBoolean("required", false),
                default = default,
            )
        }
        val dupIds = out.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dupIds.isNotEmpty()) {
            return AskQuestionsParse.Bad(
                "questions 的 id 重复：${dupIds.joinToString("、")}——答案按 id 回，重复会串位，请改成分清每个问题的 id",
            )
        }
        return AskQuestionsParse.Ok(out)
    }

    /** default 的对账（返回 null = 合法；否则给一句人话）。 */
    private fun defaultProblem(
        index: Int,
        kind: AskQuestionKind,
        default: String,
        options: List<AskUserOption>,
    ): String? = when (kind) {
        AskQuestionKind.SINGLE, AskQuestionKind.MULTI -> {
            val known = options.map { it.id }.toSet()
            val unknown = default.split(",", "、").map { it.trim() }.filter { it.isNotEmpty() }
                .filter { it !in known }
            if (unknown.isEmpty()) {
                null
            } else {
                "questions[$index].default 里的 ${unknown.joinToString("、")} 不在本项 options 的 id 里（现有：${known.joinToString("、")}）"
            }
        }

        AskQuestionKind.NUMBER ->
            if (default.toDoubleOrNull() == null) "questions[$index].default 要是数字（现在是：$default）" else null

        AskQuestionKind.BOOL ->
            if (default != "true" && default != "false") "questions[$index].default 要是 true 或 false（现在是：$default）" else null

        AskQuestionKind.TEXT -> null
    }

    /**
     * 问题项的选项归一（与 [normalizeOptions] 同纪律：2–4 个、label 必填、id 缺省补序号），
     * 但**不设推荐**——问题组是「问事实」，不是「荐方案」；选项 id 重复也拒（串位同源）。
     */
    fun normalizeGroupOptions(raw: JSONArray?): List<AskUserOption>? {
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
                recommended = false,
            )
        }
        if (out.size < MIN_OPTIONS || out.size > MAX_OPTIONS) return null
        val ids = out.map { it.id }
        if (ids.toSet().size != ids.size) return null
        return out
    }

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
