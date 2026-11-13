// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十批 · 连环失败提案的**前置归因**（判据①②③）。
//
// 病灶：同一轮里多个工具失败后，收尾按「工具名」数次数，到线就提一条「约束模型」
// 的工具规则。可失败未必是模型的错：路径不存在、没权限、工具组没解锁、参数解析
// 失败，都是环境给的，约束模型既冤枉也修不好。派工单要的是「先分清谁的错」。
//
// 本文件把这一步收成**一张表 ＋ 一个纯函数**：错误线索进，三态归因出。
//   · 环境类（路径不存在／权限／可见性／参数解析）⇒ 只记账，不提约束；
//   · 行为类（选错工具／漏参数等参数错／重试无效）⇒ 才允许提约束；
//   · 拿不到线索 ⇒ 未归因（fail-closed，不硬猜，同样不提约束）。
//
// 边界（与第八十六批同款）：零 IO、零时钟、零 Android、零模型裁决；依据只放
// **结构化线索标签**，不复制错误原文、不放用户正文，免得提案卡变成第二个正文出口。
// 根因键稳定且不含次数/工具名 —— 同轮限一次、跨轮幂等都靠它。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolErrCodes

/** 这次失败该记在谁头上（闭集三格；「既环境又行为」的第四格不存在）。 */
internal enum class FailureOwner(val label: String) {
    ENVIRONMENT("环境类"),
    BEHAVIOR("行为类"),
    UNATTRIBUTED("未归因"),
}

/**
 * 一条失败的归因结论。
 *
 * @param reason 可读依据（只含线索标签，不含错误原文、不含次数）
 * @param rootCause 稳定根因键；同轮限一次、跨轮幂等全靠它
 */
internal data class FailureAttribution(
    val owner: FailureOwner,
    val reason: String,
    val rootCause: String,
)

/** 一条判类规则：稳定根因码 ＋ 中文类名 ＋ 命中词表。 */
private data class ClueRule(
    val owner: FailureOwner,
    val code: String,
    val cn: String,
    val tokens: List<String>,
)

internal object ToolFailureAttribution {

    /** 账行没留错误线索时的固定依据。 */
    const val NO_CLUE = "账行没有可归因的错误线索"

    /** 未归因的固定根因键（空线索与无线索共用，输出确定）。 */
    const val NO_CLUE_ROOT = "U_NO_CLUE"

    /**
     * 判类表：**环境类整体排在前**。
     *
     * 为什么这个顺序：同一段错误原文可能同时带着两种味道（例如「路径不存在，
     * 请改用别的工具」），宁可少提一张约束卡，也不要把环境的错记到模型头上。
     * 词表只收项目里真实出现过的错误文案说法，不收宽泛单字，免得「参数」两个字
     * 把「参数解析失败」（环境）和「漏参数」（行为）混成一类。
     *
     * 参数类错误（缺必填／多给／类型／取值）**共用一个根因码**：它们都是
     * 「模型这次参数没给对」同一件事，按根因限一次时不该被拆成四张卡。
     */
    private val RULES: List<ClueRule> = listOf(
        ClueRule(
            FailureOwner.ENVIRONMENT, "E_PATH_ABSENT", "路径不存在",
            listOf("路径不存在", "文件不存在", "目录不存在", "源不存在", "目标不存在", "里有一层不存在"),
        ),
        ClueRule(
            FailureOwner.ENVIRONMENT, "E_PERMISSION", "权限",
            listOf("权限", "未授权", "不允许", "路径越界", "原件区只读", "已被用户关闭", "越界"),
        ),
        ClueRule(
            FailureOwner.ENVIRONMENT, "E_VISIBILITY", "可见性",
            listOf("未解锁", "不可见", "默认不可见", "组未解锁", "当前不可用", "暂不支持"),
        ),
        ClueRule(
            FailureOwner.ENVIRONMENT, "E_PARSE", "参数解析",
            listOf("参数解析失败", "不是合法 JSON", "JSON 解析", "tool_call 解析"),
        ),
        ClueRule(
            FailureOwner.BEHAVIOR, "B_WRONG_TOOL", "选错工具",
            listOf("未知工具", "选错工具"),
        ),
        ClueRule(
            FailureOwner.BEHAVIOR, "B_BAD_ARGS", "模型参数错",
            listOf(
                "缺少必填参数", "漏参数", "未知参数", "类型不对",
                "不能小于", "不能大于", "取值不在允许范围", "请改用",
            ),
        ),
        ClueRule(
            FailureOwner.BEHAVIOR, "B_RETRY_USELESS", "重试无效",
            listOf("重试无效", "原样重试", "重复失败", "连续失败"),
        ),
    )

    /**
     * 归因（纯函数：同样的线索永远得同样的结论）。
     *
     * 空线索／都不命中 ⇒ 未归因；调用方据此**不提约束**，不把「猜不出」当成行为类。
     */
    fun classify(err: String): FailureAttribution {
        val text = err.trim()
        if (text.isEmpty()) return unattributed(NO_CLUE)
        // 第六百二十批收口（用户裁定⑥）：**码是主判据**——码由引擎按规则生成，比自然语言稳；
        // 文本命中降为兜底（码认不出时才看中文词）。表只有一份：[ToolErrCodes.ALL]。
        ToolErrCodes.parse(text)?.let { code ->
            return FailureAttribution(
                owner = code.owner,
                reason = "失败码「${code.code}」（${code.owner.label}）" +
                    if (code.note.isEmpty()) "" else "｜${code.note}",
                rootCause = "${code.owner.name}|${code.code.uppercase()}",
            )
        }
        for (rule in RULES) {
            val hit = rule.tokens.firstOrNull { text.contains(it) }
            if (hit != null) {
                return FailureAttribution(
                    owner = rule.owner,
                    reason = "命中「${rule.cn}」线索：$hit",
                    rootCause = "${rule.owner.name}|${rule.code}",
                )
            }
        }
        return unattributed("错误原文没有可归因的线索")
    }

    /** 只有行为类才允许提「约束模型」的规则（唯一放行口）。 */
    fun mayProposeRule(a: FailureAttribution): Boolean = a.owner == FailureOwner.BEHAVIOR

    private fun unattributed(reason: String): FailureAttribution =
        FailureAttribution(FailureOwner.UNATTRIBUTED, reason, NO_CLUE_ROOT)
}
