// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十七批（L2 轮）· 能力路由漏斗 **L2 判据层**（纯函数：零 IO、零时钟、零模型引用）。
//
// L2 只干一件事（派工单 §二＋本轮工单）：**在候选工具组里选一组**——不抽参数、不判意图标签、
// 不写计划。候选闭集就五个：内容／查询／卡片／教学／系统。选不出来就 undecided，**不许猜**。
//
// 四条硬规矩落在本文件的哪一处：
//   · 输入 ≤200 字（与 624 E 提问同口径）⇒ [promptOf] 硬截，不喂整页快照/正文——prompt 里
//     只有用户那句话＋候选清单，别的一律不进；
//   · 输出必须带「我不确定」出口 ⇒ [L2Result] 的 undecided 是**合法终值**，三种成因分开写
//     （拿不到 JSON／格式非法／置信不足或候选不唯一），账上能看出为什么不确定；
//   · margin 机制 ⇒ 第一与第二候选分差 < [MARGIN_MIN]（5%）一律 undecided——「差 0.01 也选」
//     是本轮工单点名的负例；
//   · 输出防御四步固定：剥围栏 → 取 JSON → 类型检查 → 闭集过滤（[judge] 逐段对应）。
//     返回的组名不在候选闭集里 ⇒ 视作 undecided——判据④，闸口拆了负例必须红。
//
// 模型调用不在这里（在 FunnelL2Read 的壳与出口缝）；本层只吃「模型的原始文本」，
// 夹具直测不需要真模型（同 610 读通的 ExtractionPort 房型：判据纯、出口可换假件）。
package com.example.zhengdao.baopu.funnel

import org.json.JSONObject

/** L2 一次判定结果：group ∈ [FunnelL2Judge.CANDIDATES] ∪ {[FunnelL2Judge.UNDECIDED]}。 */
data class L2Result(
    val group: String,
    /** 为什么（含分数与差距的人话）；undecided 时就是「不确定原因」。 */
    val reason: String,
) {
    val undecided: Boolean get() = group == FunnelL2Judge.UNDECIDED
}

/** L2 判据本体（纯函数：同输入必同输出）。 */
internal object FunnelL2Judge {

    /** 候选闭集（本轮工单点名五组；表序即稳定序）。 */
    val CANDIDATES: List<String> = listOf("内容", "查询", "卡片", "教学", "系统")

    /** 合法出口：选不出来就是这个，不是「硬选一个」。 */
    const val UNDECIDED = "undecided"

    /** 输入硬上限（与 624 E 提问同口径：≤200 字；整页快照/正文根本不进 prompt）。 */
    const val MAX_INPUT_CHARS = 200

    /** margin 阈值（本轮工单口径「如 5%」）：第一第二候选分差低于它就 undecided。 */
    const val MARGIN_MIN = 0.05

    /** 置信地板（口径自定并钉死：五候选均分＝0.2，0.4 要求明显偏向第一）。 */
    const val CONF_FLOOR = 0.40

    /** 候选组的一句话（进 prompt 用；只有用途没有参数面——L2 不抽参数）。 */
    private val CANDIDATE_LINES: List<String> = listOf(
        "内容：写与改的产物（文章/小说/剧本/图文）",
        "查询：查数据查事实（物流/表/联网/账本）",
        "卡片：角色卡与卡片格式（做卡/体检/导出）",
        "教学：教我怎么做某件事（步骤演示/回放）",
        "系统：App 自身与设置（权限/配置/治理）",
    )

    /**
     * 组 prompt（纯函数）：用户那句话**截到 200 字**＋候选清单＋输出契约。
     * 契约只收 scores 映射（每组 0..1 一个分），逼模型把「第二候选」也交出来供 margin 核。
     */
    fun promptOf(text: String): String = buildString {
        append("给这句话选一个工具组，只选一组，不抽参数、不判意图、不写计划。\n")
        append("候选（闭集，组名必须逐字用）：")
        append(CANDIDATE_LINES.joinToString("；"))
        append("\n只输出 JSON：{\"scores\":{")
        append(CANDIDATES.joinToString(",") { "\"$it\":0.0" })
        append("}}，每组 0..1 一个分，合计不必为 1。\n")
        append("选不准就在任何组都不给 0.4 以上的分（系统会按不确定处理，不许硬选）。\n")
        append("这句话（最多 200 字）：")
        append(cappedText(text))
    }

    /** 输入截断（判据①的落点：超 200 字就截，不喂整页快照/正文）。 */
    fun cappedText(text: String): String = text.take(MAX_INPUT_CHARS)

    /**
     * 判一次原始返回（防御四步逐字对应本轮工单④）：
     * 剥围栏 → 取 JSON → 类型检查 → 闭集过滤 → floor/margin → 结果。
     * 每一步失败都走 undecided，**没有「差不多就选」的分支**。
     */
    fun judge(raw: String): L2Result {
        val jsonText = extractJsonObject(stripFences(raw))
            ?: return undecidedOf("防御链·取JSON：剥完围栏拿不到 JSON 对象")
        val scores = typedScores(jsonText)
            ?: return undecidedOf("防御链·类型检查：scores 缺失/非对象/值非 0..1 数字")
        val inSet = scores.filterKeys { it in CANDIDATES } // 防御链·闭集过滤：组外名字直接丢
        if (inSet.isEmpty()) {
            return undecidedOf("闭集过滤后无候选（返回的组名都不在候选里）")
        }
        val sorted = inSet.entries.sortedWith(
            compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key },
        )
        val top = sorted.first()
        if (top.value < CONF_FLOOR) {
            return undecidedOf("置信不足：第一候选 ${top.key}=${fmt(top.value)} < 地板 ${fmt(CONF_FLOOR)}")
        }
        val runnerUp = sorted.getOrNull(1)
        if (runnerUp != null && top.value - runnerUp.value < MARGIN_MIN) {
            return undecidedOf(
                "候选不唯一：${top.key}=${fmt(top.value)} 与 ${runnerUp.key}=${fmt(runnerUp.value)} " +
                    "差距 ${fmt(top.value - runnerUp.value)} < margin ${fmt(MARGIN_MIN)}",
            )
        }
        val tail = if (runnerUp == null) "（无第二候选）" else "，第二 ${runnerUp.key}=${fmt(runnerUp.value)}"
        return L2Result(top.key, "L2 选组：${top.key}=${fmt(top.value)}$tail")
    }

    /** 剥围栏：去掉以 ``` 开头的行（语言标签行一并去；行内代码不动）。 */
    fun stripFences(raw: String): String =
        raw.lineSequence().filterNot { it.trimStart().startsWith("```") }.joinToString("\n")

    /** 取第一个配平的 JSON 对象（字符串里的花括号不算数）；配不平 ⇒ null。 */
    fun extractJsonObject(s: String): String? {
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            when {
                esc -> esc = false
                inStr && c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /**
     * 类型检查（严格面）：必须有 scores 对象、值全是 0..1 的 Number；
     * 有任何一个非法值就整份作废（宁要 undecided，不要半份可信）。返回 null ⇒ 非法。
     */
    fun typedScores(jsonText: String): Map<String, Double>? {
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val scores = obj.optJSONObject("scores") ?: return null
        val out = LinkedHashMap<String, Double>()
        val keys = scores.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = scores.opt(key)
            val num = value as? Number ?: return null
            val d = num.toDouble()
            if (d.isNaN() || d < 0.0 || d > 1.0) return null
            out[key] = d
        }
        return out
    }

    private fun undecidedOf(why: String): L2Result = L2Result(UNDECIDED, "undecided：$why")

    private fun fmt(v: Double): String = (v * 100).toInt().toString() + "%"
}
