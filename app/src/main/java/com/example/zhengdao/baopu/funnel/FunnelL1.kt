// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十七批 · 能力路由漏斗的 **L1 确定性层**（纯判据，零模型、零 IO、零时钟）。
//
// 工程口径（派工单 §一，三个词必须先分清）：这不是「意图标签分类」——开放域列不完标签；
// 这是**能力路由**：决定「这次装哪些工具、走哪条流水线、要不要检索」。路由必须由代码决定：
// 同输入同输出、可单测。L2（轻量模型）与 L3（主循环）都不在本文件——本层只答一个问题：
// 「这句话有没有**确定性**的入口？」答不出来就返回未命中，**不硬猜**（派工单 §三④）。
//
// 四条规则，按贵的往后排（判序固定＝确定性）：
//   R1 技能脚本命中（643 一系的回放脚本）：话里点名了某条脚本（同流程），且话里若带网址，
//      网址主机必须与脚本目标站一致（同站）——两条都满足才判「确定性执行」；
//   R2 关键词/显式命令：闭集词表（查物流/做卡/通读/写剧本……），词到组一一对应，表外不算；
//   R3 引用锚点：带 @文件、带图片附件、带 URL ⇒ 路由到对应组（锚点是实打实出现在输入里的东西）；
//   R4 会话状态：上一轮落在某组流水线上 ⇒ 本轮**建议**同流水线——reason 里明写「只作建议」，
//      最终决策权在 L3 主循环（本层连装配都不碰，见 FunnelRead 与判据⑤门禁）。
//
// 输出三字段（判据③）：route / reason / evidence。route 只许是**闭集**：
// 组名（ToolGroup 的 key）∪ 确定性执行 ∪ 未命中；不在闭集里的值一律折算成未命中——
// 「把组外名字当成有效路由」是派工单点名的负例。
package com.example.zhengdao.baopu.funnel

/** 一条可命中的脚本（IO 壳读好后传进来；判据层自己不碰盘）。 */
data class FunnelScript(
    val name: String,
    val purpose: String,
    /** 目标站主机名（622 的 targetSite；v1 老脚本没有时由壳层从 startUrl 抠 host）。空＝核不了站。 */
    val targetSite: String,
)

/** 一轮用户输入的判据视图（文本＋附件事实＋脚本表＋上一轮去向——全是调用方读好的值）。 */
data class FunnelTurnInput(
    val text: String,
    val hasImage: Boolean,
    val scripts: List<FunnelScript>,
    val previousRoute: String?,
)

/** L1 判定输出（判据③钉死三字段：route / reason / evidence）。 */
data class FunnelDecision(
    /** 闭集：组名 key ∪ [FunnelL1Rules.ROUTE_DETERMINISTIC] ∪ [FunnelL1Rules.ROUTE_MISS]。 */
    val route: String,
    /** 一句话为什么（含规则号）；未命中也要说清「四条都没中」。 */
    val reason: String,
    /** 命中依据的原始事实（命中词/脚本名/锚点/上一轮），一条不少；未命中＝空表。 */
    val evidence: List<String>,
)

/** L1 判据本体（纯函数：同输入必同输出——判据④的确定性就落在「没有 IO、没有时钟」上）。 */
internal object FunnelL1Rules {

    /** 确定性执行：命中脚本 ⇒ 走回放链，理论上零次模型调用（判据①）。 */
    const val ROUTE_DETERMINISTIC = "确定性执行"

    /** 未命中：四条规则都没中——答案就是「没答案」，交给 L3，不硬猜（判据②）。 */
    const val ROUTE_MISS = "未命中"

    /** 会话状态建议的 reason 尾注（判据⑤的另一面：建议不是命令）。 */
    const val ADVISORY_NOTE = "只作建议，不强制"

    /**
     * R2 闭集词表（词 → 组 key；表序即判序，同输入同输出）。
     * 只收「有明显入口」的说法（派工单点名四例＋组表里入口确凿的几例）；
     * 词必须整串出现在话里才算——不做分词、不做近义，那是模型活不是本层活。
     */
    val KEYWORD_GROUPS: List<Pair<String, String>> = listOf(
        "查物流" to "logistics",
        "做卡" to "card",
        "通读" to "library",
        "写剧本" to "library",
        "写小说" to "library",
        "画图" to "image",
        "联网搜" to "websearch",
        "读表" to "data",
        "定时任务" to "schedule",
    )

    /** URL 锚点：http(s) 开头的连续串（碰到空白或中文标点即断）。 */
    private val URL_REGEX = Regex("https?://[^\\s<>\"'，。；、）]+")

    /** @文件锚点：@ 后跟「带路径分隔或带扩展名」的连续 token（`@产物/a.md`、`@a.md`）。 */
    private val FILE_REF_REGEX = Regex("@[^\\s@]+(?:/[^\\s@]+|\\.[A-Za-z0-9]{1,8})")

    /** 闭集全集：组名 key ∪ 两个哨兵。route 落在这里面才算合法（判据③）。 */
    fun legalRoutes(validGroupKeys: Set<String>): Set<String> =
        validGroupKeys + ROUTE_DETERMINISTIC + ROUTE_MISS

    /**
     * 判一轮（判据①②③④的本体；[validGroupKeys] 由壳层从组表传入，判据层不 import 组表）。
     *
     * 判序固定：R1 脚本 → R2 关键词 → R3 锚点 → R4 会话状态 → 未命中。
     * 任何一步产出的 route 先过闭集校验（[legalRoutes]），不在闭集 ⇒ 折算成未命中——
     * 「把组外名字当成有效路由」当场红（派工单负例③）。
     */
    fun judge(input: FunnelTurnInput, validGroupKeys: Set<String>): FunnelDecision {
        val urls = URL_REGEX.findAll(input.text).map { it.value }.toList()
        val fileRefs = FILE_REF_REGEX.findAll(input.text).map { it.value }.toList()
        val hit = judgeRaw(input, urls, fileRefs)
        val legal = legalRoutes(validGroupKeys)
        return if (hit.route in legal) hit else miss("闭集外组名折算：${hit.route}")
    }

    /**
     * 四条规则的原始判定。**闭集校验只在 [judge] 收尾做**（单一闸口）：
     * 这里产出的组名原样交出去，闸口拆了负例必须红——判据③是承重的，不是摆设。
     */
    private fun judgeRaw(
        input: FunnelTurnInput,
        urls: List<String>,
        fileRefs: List<String>,
    ): FunnelDecision {
        // R1 技能脚本：同名（话里点名）＋同站（话里带网址时必须对得上目标站）。
        scriptHit(input, urls)?.let { return it }

        // R2 关键词/显式命令：闭集词表按表序第一命中（词整串出现才算，不做分词近义）。
        for ((word, groupKey) in KEYWORD_GROUPS) {
            if (input.text.contains(word)) {
                return FunnelDecision(
                    route = groupKey,
                    reason = "R2 关键词命中：「$word」→ 组 $groupKey",
                    evidence = listOf("R2词=$word", "R2组=$groupKey"),
                )
            }
        }

        // R3 引用锚点：@文件 → files；图片附件 → edit（image_ops 所在组）；URL → browser。
        if (fileRefs.isNotEmpty()) {
            return FunnelDecision(
                route = "files",
                reason = "R3 锚点命中：带 @文件引用 → 组 files",
                evidence = listOf("R3锚=${fileRefs.first()}"),
            )
        }
        if (input.hasImage) {
            return FunnelDecision(
                route = "edit",
                reason = "R3 锚点命中：带图片附件 → 组 edit",
                evidence = listOf("R3锚=图片附件"),
            )
        }
        if (urls.isNotEmpty()) {
            return FunnelDecision(
                route = "browser",
                reason = "R3 锚点命中：带网址 → 组 browser",
                evidence = listOf("R3锚=${urls.first()}"),
            )
        }

        // R4 会话状态：上一轮落在某条流水线上 ⇒ 本轮原样建议回去（只作建议）。
        // 两个哨兵不进建议（确定性执行不是组；未命中没有可延续的流水线）。
        val prev = input.previousRoute
        if (prev != null && prev != ROUTE_MISS && prev != ROUTE_DETERMINISTIC) {
            return FunnelDecision(
                route = prev,
                reason = "R4 会话状态：上一轮在 $prev——$ADVISORY_NOTE",
                evidence = listOf("R4上一轮=$prev"),
            )
        }

        // 四条都没中——未命中就是未命中（判据②：这里硬猜出一个组就是红）。
        return miss("R1-R4 四条规则都没命中——交给 L3，不硬猜")
    }

    /** R1 细节：话里点名脚本＝同流程；话里带网址时逐条核站（脚本没记站点的老账不核＝不命中，判不了就不猜）。 */
    private fun scriptHit(input: FunnelTurnInput, urls: List<String>): FunnelDecision? {
        for (script in input.scripts) {
            if (script.name.isBlank() || !input.text.contains(script.name)) continue
            if (urls.isEmpty()) {
                return FunnelDecision(
                    route = ROUTE_DETERMINISTIC,
                    reason = "R1 脚本命中：话里点名「${script.name}」（没带网址，不核站）",
                    evidence = listOf("R1脚本=${script.name}", "R1同站=话里没网址不核"),
                )
            }
            val siteOk = script.targetSite.isNotBlank() &&
                urls.any { h -> hostOf(h)?.let { host -> sameSite(host, script.targetSite) } == true }
            if (siteOk) {
                return FunnelDecision(
                    route = ROUTE_DETERMINISTIC,
                    reason = "R1 脚本命中：话里点名「${script.name}」且网址同站（${script.targetSite}）",
                    evidence = listOf("R1脚本=${script.name}", "R1同站=${script.targetSite}"),
                )
            }
        }
        return null
    }

    /** 未命中的标准形状（reason 带因由；evidence 空表——没命中就没有依据）。 */
    private fun miss(why: String): FunnelDecision =
        FunnelDecision(route = ROUTE_MISS, reason = "未命中：$why", evidence = emptyList())

    /** 主机名（含端口）；抠不出来返回 null（不猜）。 */
    fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
        if (start >= url.length) return null
        val slash = url.indexOf('/', start)
        val host = if (slash >= 0) url.substring(start, slash) else url.substring(start)
        return host.ifBlank { null }
    }

    /** 同站判：完全相等，或一边是另一边的子域（www.a.com ↔ a.com 算同站；evil-a.com 不算）。 */
    fun sameSite(host: String, targetSite: String): Boolean {
        val h = host.removePrefix("www.")
        val t = targetSite.removePrefix("www.")
        return h == t || h.endsWith(".$t") || t.endsWith(".$h")
    }

    /**
     * 建议装载工具数（读数第三字段；**只算不装**——装配改动留给后续单）。
     *
     * 口径：现装配的默认可见面（组默认可见 ∪ 工具级默认可见 ∪ 免解锁名单）为底，
     * 命中组就再并上该组成员；确定性执行＝整轮不进模型循环 ⇒ 0；未命中＝照旧底数。
     */
    fun suggestedToolCount(
        route: String,
        groupTools: Map<String, Set<String>>,
        defaultVisibleGroups: Set<String>,
        defaultVisibleTools: Set<String>,
        unlockFree: Set<String>,
    ): Int {
        if (route == ROUTE_DETERMINISTIC) return 0
        val baseline = groupTools.filterKeys { it in defaultVisibleGroups }.values.flatten().toSet() +
            defaultVisibleTools + unlockFree
        return if (route == ROUTE_MISS) baseline.size else (baseline + groupTools[route].orEmpty()).size
    }

    /** 恒出现的注入段底数：persona 1＋硬事实 1＋历史 1＋本轮输入 1（ContextAssembler 的必现四段；
     *  记忆/日记/压缩是条件段，与路由无关，不计入本读数）。 */
    const val BASE_SEGMENTS = 4

    /**
     * 预计注入段数（读数第四字段，投影口径）：
     * 确定性执行＝不进模型 ⇒ 0 段；命中组＝底数＋1（接装配时该多一段「组工具说明」）；未命中＝底数照旧。
     */
    fun expectedSegmentCount(route: String): Int = when (route) {
        ROUTE_DETERMINISTIC -> 0
        ROUTE_MISS -> BASE_SEGMENTS
        else -> BASE_SEGMENTS + 1
    }

    /** 读数「命中哪条 L1」取 evidence 首条的规则号；未命中＝固定字样。 */
    fun hitLabel(decision: FunnelDecision): String {
        if (decision.route == ROUTE_MISS) return ROUTE_MISS
        val first = decision.evidence.firstOrNull() ?: return ROUTE_MISS
        return first.substringBefore("=")
    }
}
