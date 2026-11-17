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
//
// 第六百九十六批（用户 2026-11-17）：真失败被判「未归因」——切句后 `err` 只剩
// 「不存在」「已拒绝」「超时」这类**裸尾巴**，而词表里全是带前缀的完整短语
// （「路径不存在」「文件不存在」…），一个都命中不了。本批两层补齐：
//   · A 词表：`E_PATH_ABSENT` 等补**裸尾巴别名**；别名只在「没有任何短语命中」时才采纳。
//   · B 匹配：**归一化 ＋ 最长匹配** —— 同一段文本命中多条取最长那条（同长按表序，
//     环境类在前），避免「参数」类短词把「参数里有一层不存在」误并成行为类。
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

/**
 * 一条判类规则：稳定根因码 ＋ 中文类名 ＋ 命中词表。
 *
 * @param tokens **短语**词表（优先）
 * @param aliases **裸尾巴**别名（第六百九十六批）：只有一条短语都没命中时才采纳。
 *   为什么分两栏：裸「不存在」能救「切句只剩尾巴」的真失败，但它比任何完整短语都短，
 *   若与短语同权，会把「参数里有一层不存在」这种带限定词的原句拉成最短命中。
 */
private data class ClueRule(
    val owner: FailureOwner,
    val code: String,
    val cn: String,
    val tokens: List<String>,
    val aliases: List<String> = emptyList(),
)

/** 一次命中：哪条规则、命中的是哪个词（最长匹配要比长度）。 */
private class ClueHit(val rule: ClueRule, val token: String)

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
     *
     * 第六百九十六批补的三处（都用真跑出来的生产文案，不是猜的）：
     * · `E_PATH_ABSENT` 补「不存在或不是文件」「范围不是目录」（NovelReadthroughTool /
     *   BulkPathTools 的原文，切句后就是这两截）＋ 别名裸「不存在」；
     * · `E_PERMISSION` 补别名「已拒绝」「被拒绝」「拒绝」（「被拒：」前缀被写侧剥掉后
     *   常常只剩这两三个字）；
     * · `E_VISIBILITY` 补别名「不可用」（「当前不可用」的裸尾巴）；
     * · 网络/超时那一族（`TIMEOUT`）**不新造码**：码表 `ToolErrCodes` 里已有 `timeout`，
     *   这里只是给它补中文词，根因键仍是 `ENVIRONMENT|TIMEOUT`（与码链路同一个键），
     *   免得同一次超时在词表路径与码路径下变成两个根因、把「同轮限一次」拆开。
     */
    private val RULES: List<ClueRule> = listOf(
        ClueRule(
            FailureOwner.ENVIRONMENT, "E_PATH_ABSENT", "路径不存在",
            listOf(
                "路径不存在", "文件不存在", "目录不存在", "源不存在", "目标不存在",
                "里有一层不存在", "不存在或不是文件", "范围不是目录",
            ),
            aliases = listOf("不存在"),
        ),
        ClueRule(
            FailureOwner.ENVIRONMENT, "E_PERMISSION", "权限",
            listOf("权限", "未授权", "不允许", "路径越界", "原件区只读", "已被用户关闭", "越界"),
            aliases = listOf("已拒绝", "被拒绝", "拒绝"),
        ),
        ClueRule(
            FailureOwner.ENVIRONMENT, "E_VISIBILITY", "可见性",
            listOf("未解锁", "不可见", "默认不可见", "组未解锁", "当前不可用", "暂不支持"),
            aliases = listOf("不可用"),
        ),
        ClueRule(
            FailureOwner.ENVIRONMENT, "E_PARSE", "参数解析",
            listOf("参数解析失败", "不是合法 JSON", "JSON 解析", "tool_call 解析"),
        ),
        ClueRule(
            FailureOwner.ENVIRONMENT, "TIMEOUT", "超时",
            listOf(
                "超时", "等太久没回音", "连接半路断了", "拒绝连接",
                "域名解析不了", "证书信不过", "尚未连接", "连接已断",
            ),
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
     * 顺序（第六百九十六批定型，一条比一条弱，先命中先用）：
     * 1. **码**：`timeout: …` 这种由引擎按规则生成的码，比自然语言稳（620 用户裁定⑥）；
     * 2. **最长短语**：同一段文本命中多条 ⇒ 取最长那条（等长按表序，环境类在前）；
     * 3. **裸尾巴别名**：一条短语都没命中，才退到「不存在」「已拒绝」这类别名；
     * 4. 都没有 ⇒ 未归因（不硬猜）；调用方据此**不提约束**，不把「猜不出」当成行为类。
     */
    fun classify(err: String): FailureAttribution {
        val text = normalize(err)
        if (text.isEmpty()) return unattributed(NO_CLUE)
        ToolErrCodes.parse(text)?.let { code -> return fromCode(code) }
        val hit = longest(text, phrases = true) ?: longest(text, phrases = false)
        if (hit != null) return fromHit(hit)
        return unattributed("错误原文没有可归因的线索")
    }

    /**
     * 这段话能不能**单独**当失败依据（写侧切句用，第六百九十六批）。
     *
     * 为什么放在 620 这一侧：判断「这截文字里有没有可归因线索」用的是同一张词表，
     * 若写侧自己再写一份词表，两处迟早漂（620 收口时已经吃过一次这个亏）。
     * 与 [classify] 共用 [normalize]／[longest]，不构成第二份真相。
     */
    fun carriesClue(raw: String): Boolean {
        val text = normalize(raw)
        if (text.isEmpty()) return false
        if (ToolErrCodes.parse(text) != null) return true
        return longest(text, phrases = true) != null || longest(text, phrases = false) != null
    }

    /** 只有行为类才允许提「约束模型」的规则（唯一放行口）。 */
    fun mayProposeRule(a: FailureAttribution): Boolean = a.owner == FailureOwner.BEHAVIOR

    // ── 匹配内核（归一化 ＋ 最长匹配）──────────────────────────────────────

    /**
     * 归一化：去首尾空白 ＋ 连续空白压成一个空格。
     *
     * 只说空白，不动中文标点：词表里的词不含标点，动了标点只会让「不存在。」这类
     * 尾巴反而认不出；而空白是工具文案里唯一会无意义漂的东西（缩进、换行、双空格）。
     */
    private fun normalize(raw: String): String = raw.trim().replace(WHITESPACE, " ")

    /**
     * 最长匹配：[phrases] 为真时看短语词表，为假时看别名。
     *
     * 等长不替换 ⇒ 先出现的赢 ⇒ 表序即优先级（环境类整体在前）。
     */
    private fun longest(text: String, phrases: Boolean): ClueHit? {
        var best: ClueHit? = null
        for (rule in RULES) {
            for (token in if (phrases) rule.tokens else rule.aliases) {
                if (token.isEmpty() || !text.contains(token)) continue
                val cur = best
                if (cur == null || token.length > cur.token.length) best = ClueHit(rule, token)
            }
        }
        return best
    }

    private fun fromCode(code: com.example.zhengdao.baopu.agent.ToolErrCode): FailureAttribution =
        FailureAttribution(
            owner = code.owner,
            reason = "失败码「${code.code}」（${code.owner.label}）" +
                if (code.note.isEmpty()) "" else "｜${code.note}",
            rootCause = "${code.owner.name}|${code.code.uppercase()}",
        )

    private fun fromHit(hit: ClueHit): FailureAttribution {
        val isAlias = hit.token !in hit.rule.tokens
        return FailureAttribution(
            owner = hit.rule.owner,
            reason = "命中「${hit.rule.cn}」线索：${hit.token}" + if (isAlias) "（裸尾巴别名）" else "",
            rootCause = "${hit.rule.owner.name}|${hit.rule.code}",
        )
    }

    private val WHITESPACE = Regex("\\s+")

    private fun unattributed(reason: String): FailureAttribution =
        FailureAttribution(FailureOwner.UNATTRIBUTED, reason, NO_CLUE_ROOT)
}
