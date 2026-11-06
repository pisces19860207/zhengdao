// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零五批 · 475 可靠升级的**边界声明与越界识别**（派工单 §二 ①②）。
//
// 病灶：能力不知道自己到哪为止，于是两件事混在一起——
//   · 真越界（写到声明之外的地方）却硬撑，撑到把盘改坏；
//   · 只是**说不准**（这一维没声明、事实没采到）也被当成越界，于是一有含糊就停手问人。
// 用户原话的分界就在这一条：「不许把『不确定』当越界」。
//
// 本文件把这句话做成**形状**而不是一句口号：判定是三态闭集
// （[BoundaryVerdict.IN_SCOPE]／[BoundaryVerdict.OUT_OF_SCOPE]／[BoundaryVerdict.UNCERTAIN]），
// 越界只有两条来路——① 管线第②段自己拦下的那个硬事实（`scopeEscapeDenied`，
//   与 346-B 的 `OVER_SCOPE` 事实同一个出处，本层不重判），
//   ② 某一条**已声明**的边界被可证地超出（站点不在清单内／预算用超／时长用超）。
// 剩下所有含糊都只能落在 UNCERTAIN，而 UNCERTAIN 在 [EscalationTrigger] 里没有升级口
// （判据②的可执行形式：拿不到事实就别说越界，说了测试要红）。
//
// 为什么不复用 [com.example.zhengdao.baopu.authz.TaskGrantGate.BoundaryFacts]：
// 那一份是 276 批的**任务级授权**判定输入（从 RiskVerdict 推出来，判的是"这一笔要不要问人"），
// 本层判的是"这类能力到哪为止"——两者对象不同、周期不同（一笔 vs 一类），
// 硬要塞成同一个形状就会把风险判档拖进来（派工单 §四红线：**不改风险判档**）。
//
// 纯函数层：不碰 Android、不碰盘、不摸时钟（时刻由调用方传）。
// 声明表里能取真的都取真（时长＝工具自己的 `ToolSpec.timeoutMs`、笔数＝内核预算、
// 站点口径＝`TeachSiteTrust.covers` 那把现成的尺），**不造第二口径**。
package com.example.zhengdao.baopu.escalate

import com.example.zhengdao.baopu.TeachSiteTrust
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.baopu.kernel.BaopuKernel

/**
 * 能力类（闭集）。
 *
 * 只有这六类有声明口；映射不上的工具走 [CapabilityKind.OTHER]，
 * 而 OTHER **没有预置边界**⇒ 判出来必然是 [BoundaryVerdict.UNCERTAIN]（不猜边界）。
 */
internal enum class CapabilityKind(val wire: String, val cn: String) {
    FILE_WRITE("file_write", "写盘"),
    FILE_READ("file_read", "读盘"),
    BROWSER("browser", "网页操作"),
    NETWORK("network", "联网取数"),
    MODEL("model", "模型调用"),
    OTHER("other", "未归类能力"),
    ;

    companion object {
        fun ofWire(wire: String): CapabilityKind? = entries.firstOrNull { it.wire == wire }

        /** 文件类能力组（分组表 `ToolGroupTable.GROUP_OF` 的 key；读写由工具自己的 readOnly 分）。 */
        val FILE_GROUPS: Set<String> = setOf(
            "core", "edit", "files", "library", "preview", "data", "skill", "image", "draw", "card",
        )

        /** 分组表里的网页组与联网组。 */
        const val BROWSER_GROUP = "browser"
        const val WEBSEARCH_GROUP = "websearch"

        /**
         * 工具 → 能力类（唯一口径）。
         *
         * 用的全是既有事实：工具**分组**（分组表由注册表的 `checkCoverage` 钉死齐全，
         * 没分组的工具起不来）＋工具**自己的只读分类**（`ToolSpec.readOnly`）。本层不查风险矩阵、
         * 不另立名单——「哪一组算哪一类能力」是一次登记，不是每次现判。都对不上 ⇒ [OTHER]，
         * 而 OTHER 没有声明边界，判出来必然是 [BoundaryVerdict.UNCERTAIN]：**不猜边界**。
         */
        fun ofTool(groupKey: String?, readOnly: Boolean): CapabilityKind = when (groupKey) {
            BROWSER_GROUP -> BROWSER
            WEBSEARCH_GROUP -> NETWORK
            in FILE_GROUPS -> if (readOnly) FILE_READ else FILE_WRITE
            else -> OTHER
        }
    }
}

/** 边界判定三态（闭集；[UNCERTAIN] 不是越界，派工单 §二② 的原话落在这里）。 */
internal enum class BoundaryVerdict(val wire: String, val cn: String) {
    IN_SCOPE("in", "在边界内"),
    OUT_OF_SCOPE("out", "越界"),
    UNCERTAIN("uncertain", "说不准"),
    ;

    companion object {
        fun ofWire(wire: String): BoundaryVerdict? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一类能力的边界声明：可用工具／可写根／预算／时长／允许站点（派工单 §二① 点名的五样）。
 *
 * **空集与 null 是两种不同的话**，不许混：
 * - `writableRoots`／`allowedSites` 为空 ⇒ 这一维**没声明** ⇒ 该维只能说不准；
 *   （"没声明"不等于"什么都不许"，更不等于"已经越界"——那是 287 批把「空名单」读成
 *   「全禁」之后剩下的老毛病，这里由维度的三态各自承载。）
 * - `budgetCalls`／`maxDurationMs` ≤0 ⇒ 同上，这一维不判。
 */
internal data class CapabilityBoundary(
    val kind: CapabilityKind,
    val tools: Set<String> = emptySet(),
    val writableRoots: List<String> = emptyList(),
    val budgetCalls: Int = 0,
    val maxDurationMs: Long = 0L,
    val allowedSites: List<String> = emptyList(),
) {
    /** 这一维有没有声明（声明了才判得动；没声明＝说不准，不是越界）。 */
    val declaresSites: Boolean get() = allowedSites.isNotEmpty()
    val declaresRoots: Boolean get() = writableRoots.isNotEmpty()
    val declaresBudget: Boolean get() = budgetCalls > 0
    val declaresDuration: Boolean get() = maxDurationMs > 0L
}

/**
 * 判定要的**事实**（由装配层采好传进来；缺哪一维那一维就不判）。
 *
 * @param scopeEscapeDenied 管线第②段拦下了这一笔（硬事实，出处＝`AnomalyFeed` 的 OVER_SCOPE 同一条）。
 *   `null` ＝ 调用方没这条事实（不是"没拦"，是"不知道"）⇒ 这一维说不准。
 * @param deniedButNotScope 被拦下了，但**不是**因为越界（参数为空／同名歧义）。
 *   这一格存在就是为了把"拦下了"与"越界"分开——把前者当后者就是派工单禁的那件事。
 * @param elapsedMs 这一笔动作已用的墙钟毫秒；负数＝没量到（不拿 0 冒充"确实只用了一瞬"）。
 */
internal data class BoundaryFacts(
    val tool: String,
    val writeTarget: String? = null,
    val host: String? = null,
    val callsUsed: Int = 0,
    val elapsedMs: Long = -1L,
    val scopeEscapeDenied: Boolean? = null,
    val deniedButNotScope: Boolean = false,
)

/** 逐维判定（纯函数，判据②/③的牙齿都在这几行）。 */
internal object BoundaryRules {

    /**
     * 一次动作落在这类能力边界的哪一格。
     *
     * 合成规则（写死、可测）：**任一维判出越界 ⇒ 越界**；没有越界但有任一维说不准 ⇒ 说不准；
     * 全都明说在界内才算在界内。顺序不能反过来——反过来就变成"有一维不知道 ⇒ 全体不知道"，
     * 真越界会被含糊淹掉。
     *
     * `boundary == null`（这类能力压根没声明）不等于"这一笔没事"：**第②段拦下的硬事实照判**
     * ——判据③要的「越界 ⇒ 发升级」不能因为声明表少一格就哑掉；其余情况才回落说不准。
     */
    fun verdictOf(boundary: CapabilityBoundary?, facts: BoundaryFacts): BoundaryVerdict {
        val denied = deniedDim(facts)
        if (boundary == null) {
            return if (denied == BoundaryVerdict.OUT_OF_SCOPE) denied else BoundaryVerdict.UNCERTAIN
        }
        val dims = mutableListOf(denied)
        dims += toolDim(boundary, facts.tool)
        dims += rootDim(boundary, facts)
        dims += siteDim(boundary, facts)
        dims += budgetDim(boundary, facts)
        dims += durationDim(boundary, facts)
        return when {
            dims.any { it == BoundaryVerdict.OUT_OF_SCOPE } -> BoundaryVerdict.OUT_OF_SCOPE
            dims.any { it == BoundaryVerdict.UNCERTAIN } -> BoundaryVerdict.UNCERTAIN
            else -> BoundaryVerdict.IN_SCOPE
        }
    }

    /** 可用工具：不在名单里＝这类能力不管这个工具（映射问题），不是越界。 */
    private fun toolDim(boundary: CapabilityBoundary, tool: String): BoundaryVerdict = when {
        boundary.tools.isEmpty() -> BoundaryVerdict.UNCERTAIN
        tool in boundary.tools -> BoundaryVerdict.IN_SCOPE
        else -> BoundaryVerdict.UNCERTAIN
    }

    /**
     * 管线拦下的那笔：越界硬事实从这里进，别含糊。
     *
     * - `true` ⇒ **越界**（第②段红线拦的，动作没发生）；
     * - `false` ⇒ 在界内这一维没有话要说 ⇒ 不判（交给别的维度）；
     * - `null` ⇒ 采集方没这条事实 ⇒ 说不准；
     * - 被拦但明确不是越界（参数为空／歧义）⇒ 这一维**明说在界内**：
     *   「拦下了」是事实，「越界」是说法，本层只收事实。
     */
    private fun deniedDim(facts: BoundaryFacts): BoundaryVerdict = when {
        facts.deniedButNotScope -> BoundaryVerdict.IN_SCOPE
        facts.scopeEscapeDenied == true -> BoundaryVerdict.OUT_OF_SCOPE
        facts.scopeEscapeDenied == false -> BoundaryVerdict.IN_SCOPE
        else -> BoundaryVerdict.UNCERTAIN
    }

    /** 可写根：只有「声明了根名单」且「写目标落在名单外」才叫越界。 */
    private fun rootDim(boundary: CapabilityBoundary, facts: BoundaryFacts): BoundaryVerdict {
        if (facts.writeTarget == null) return BoundaryVerdict.IN_SCOPE
        if (!boundary.declaresRoots) return BoundaryVerdict.UNCERTAIN
        val inside = boundary.writableRoots.any { root -> isInsideRoot(root, facts.writeTarget) }
        return if (inside) BoundaryVerdict.IN_SCOPE else BoundaryVerdict.OUT_OF_SCOPE
    }

    /** 允许站点：口径借 [TeachSiteTrust.covers]（同一把尺，不在此处重写主域/子域规则）。 */
    private fun siteDim(boundary: CapabilityBoundary, facts: BoundaryFacts): BoundaryVerdict {
        val host = facts.host ?: return BoundaryVerdict.IN_SCOPE
        if (!boundary.declaresSites) return BoundaryVerdict.UNCERTAIN
        return if (TeachSiteTrust.covers(host, boundary.allowedSites)) {
            BoundaryVerdict.IN_SCOPE
        } else {
            BoundaryVerdict.OUT_OF_SCOPE
        }
    }

    private fun budgetDim(boundary: CapabilityBoundary, facts: BoundaryFacts): BoundaryVerdict = when {
        !boundary.declaresBudget -> BoundaryVerdict.UNCERTAIN
        facts.callsUsed > boundary.budgetCalls -> BoundaryVerdict.OUT_OF_SCOPE
        else -> BoundaryVerdict.IN_SCOPE
    }

    /** 时长：没量到（负数）就说不准——0 不是"只用了一瞬"，是"这里没读数"。 */
    private fun durationDim(boundary: CapabilityBoundary, facts: BoundaryFacts): BoundaryVerdict = when {
        !boundary.declaresDuration || facts.elapsedMs < 0L -> BoundaryVerdict.UNCERTAIN
        facts.elapsedMs > boundary.maxDurationMs -> BoundaryVerdict.OUT_OF_SCOPE
        else -> BoundaryVerdict.IN_SCOPE
    }

    /**
     * 路径是否落在某个根内（组件级比较，不用字符串前缀——`/sdcardfoo` 骗得过前缀，
     * 骗不过组件）。空根串不参与比较（当作"这个根没声明"）。
     */
    fun isInsideRoot(root: String, target: String): Boolean {
        val r = normalizePath(root)
        val t = normalizePath(target)
        if (r.isEmpty() || t.isEmpty()) return false
        if (t == r) return true
        return t.startsWith("$r/")
    }

    private fun normalizePath(raw: String): String = raw.replace('\\', '/').trimEnd('/').let {
        if (it.startsWith("//")) it.trimStart('/') else it
    }
}

/**
 * 每类能力的**声明表**（派工单 §二①：可用工具／可写根／预算／时长／允许站点）。
 *
 * 这一张表只登记**已经在生产里存在的数**，一个都不新造：
 * - `tools`＝分组表 [ToolGroupTable.GROUP_OF] 的组员（读写共用同一份组员名单：这一维只答
 *   「这类管不管这个工具」，读写切分由 [CapabilityKind.ofTool] 在归类时已经做过）；
 * - `budgetCalls`＝内核本轮步数预算 [BaopuKernel.Budget.MAX_STEPS]（一类能力在一轮里
 *   最多用得掉整轮的预算——这是既有硬卡的**下界继承**，不是新阈值）；
 * - `writableRoots`／`maxDurationMs`＝**今天没有声明口**：可写根只活在 `FilePolicy` 的红线判定里
 *   （287 之后工作区不是权限边界，全盘可读写，没有一份"这类能力只许写这几根"的表），
 *   时长只活在每个工具自己的 `ToolSpec.timeoutMs` 里（没有能力级时长）。
 *   **空着就是空着**：那两维因此恒判 [BoundaryVerdict.UNCERTAIN]，不拿"没声明"冒充"已越界"，
 *   也不拿单笔超时冒充能力级预算。真正的越界硬事实走 `scopeEscapeDenied` 那一路进来。
 * - `allowedSites` 由调用方给（名单在 `回放信任站点.json`，读盘不在纯函数层做）。
 */
internal object CapabilityBoundaries {

    /**
     * 这一类能力的声明（没归类的 [CapabilityKind.OTHER] ⇒ null：连"管哪些工具"都说不清）。
     *
     * @param allowedSites 允许站点（调用方现读的名单；空＝这一维没声明）
     */
    fun of(kind: CapabilityKind, allowedSites: List<String> = emptyList()): CapabilityBoundary? = when (kind) {
        CapabilityKind.OTHER -> null
        CapabilityKind.BROWSER, CapabilityKind.NETWORK -> CapabilityBoundary(
            kind = kind,
            tools = toolsIn(webGroupOf(kind)),
            budgetCalls = BaopuKernel.Budget.MAX_STEPS,
            allowedSites = allowedSites,
        )
        CapabilityKind.MODEL -> CapabilityBoundary(
            kind = kind,
            tools = toolsIn("agent"),
            budgetCalls = BaopuKernel.Budget.MAX_STEPS,
        )
        else -> CapabilityBoundary(
            kind = kind,
            tools = CapabilityKind.FILE_GROUPS.flatMap { toolsIn(it) }.toSet(),
            budgetCalls = BaopuKernel.Budget.MAX_STEPS,
        )
    }

    /** 这类能力有没有声明（没声明＝判不出来，只能说不准）。 */
    fun declares(kind: CapabilityKind): Boolean = of(kind) != null

    private fun toolsIn(groupKey: String): Set<String> =
        ToolGroupTable.GROUP_OF.filterValues { it.key == groupKey }.keys

    /** 网页两能力各自的分组名（操作网页＝browser 组，联网取数＝websearch 组；表里只这两组）。 */
    private fun webGroupOf(kind: CapabilityKind): String =
        if (kind == CapabilityKind.BROWSER) CapabilityKind.BROWSER_GROUP else CapabilityKind.WEBSEARCH_GROUP
}
