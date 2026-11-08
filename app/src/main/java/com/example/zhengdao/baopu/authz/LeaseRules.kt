// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十七批 · 租约（Lease）的**判定层**：类目、范围、拒授、报价。
//
// 一句话：她要的不是"再问一次要不要"，是"这一类我认了，别一个一个问"。
// 租约＝一次批准 ＋ 四条边（同类／范围／时间窗／次数），四条边任一条破了就回到弹卡。
//
// 三条纪律钉在这个文件的形状上：
//   1. **判定纯函数**：本文件零 IO、零时钟、不跨轮（时间与事实全由调用方给）；
//      落账是判完之后另一步的事（账是留痕不是闸门，208/499 同一口径）。
//   2. **不建第三张分类表**：类目从 `ToolGroup.key` × 动作种类导出——两个既有单一真相源，
//      新工具入场自动归类；另立一张枚举表就是第三份归一化，迟早与矩阵/分组表漂移。
//   3. **拒授清单包住 499 的三条硬线**：硬线（必问/未分类/R3）＋ 根外 ＋ 不可逆，
//      一条都不许被租约越过。布尔式从 [AskHardLines] 借，**不在这里复制一个 ||**。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.FileOpRules
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.agent.AskHardLines
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.escalate.CapabilityKind
import java.io.File
import org.json.JSONObject

/**
 * 一张租约的四条边（**天花板写在常量里，任何路径都不许超过**）。
 *
 * 为什么有天花板：没有上限的"次数/时长"就是变相永久租约，而派工单的红线是
 * 「不许自动生成永久租约」。默认值由人定、建议由 [LeaseAdvice] 出、改由人改——
 * 这条路里没有"系统自己把数调大"的分支。
 */
internal class LeaseLimits(val cap: Int = DEFAULT_CAP, val ttlMs: Long = DEFAULT_TTL_MS) {

    init {
        require(cap in 1..MAX_CAP) { "cap 超出天花板：$cap" }
        require(ttlMs in 1..MAX_TTL_MS) { "ttlMs 超出天花板：$ttlMs" }
    }

    companion object {
        /** 默认覆盖几次（467 定点 D9：10 次）。 */
        const val DEFAULT_CAP = 10

        /** 默认时间窗（467 定点 D9：4 小时）。 */
        const val DEFAULT_TTL_MS = 4L * 60L * 60L * 1000L

        /** 次数天花板（467 定点 D9：15 次）。 */
        const val MAX_CAP = 15

        /** 时间窗天花板（467 定点 D9：12 小时）。 */
        const val MAX_TTL_MS = 12L * 60L * 60L * 1000L

        /** 内存里最多几张（淘汰顺序见 [LeaseStore]）。 */
        const val MAX_LIVE = 32

        /** 单次最多扣几次（定点 D9：多目标仍按目标数算，但**一次最多扣 2**）。 */
        const val MAX_COST_PER_USE = 2

        /** 满几次越权降一档（定点 D10）。 */
        const val VIOLATION_STEP = 3

        val DEFAULT = LeaseLimits()
    }
}

/**
 * 租约的**范围**（一次授予时算好，之后只读）。
 *
 * 三种形态：目录子树（文件类）、站点（带 URL 的网页类）、不限定（无路径无 URL 的类目，
 * 此时"范围"这一维退化成类目本身——[Kind.WORKSPACE] 的 within 恒 true）。
 */
data class LeaseScope(val kind: Kind, val value: String) {

    enum class Kind(val wire: String) {
        DIR("dir"),
        HOST("host"),
        WORKSPACE("workspace"),
    }

    /** 账上那一格（`dir:/sdcard/...`）。 */
    fun wire(): String = "${kind.wire}:$value"

    /**
     * 这次动作整体落在范围内吗（**全部**目标都要在内，不许部分覆盖）。
     *
     * 目录比较用**已经规范化过的字符串**：范围在授予时规范化一次，这里只对目标各规范化
     * 一次再逐段比——不逐个 `canonicalFile` 两次，也不自己写一份 startsWith 语义。
     */
    fun within(targets: List<File>, args: JSONObject): Boolean = when (kind) {
        Kind.WORKSPACE -> true
        Kind.HOST -> LeasePaths.hostOf(args)?.equals(value, ignoreCase = true) == true
        Kind.DIR -> targets.isNotEmpty() && targets.all { LeasePaths.isUnder(File(value), it) }
    }

    companion object {
        /**
         * 从事实里算范围（判定层唯一口径）。
         *
         * 优先目录（文件类）：取全部目标的**共同父目录**——不是"整个工作区"，
         * 也不是"每个目标各一张"；共同父目录就是这次动作真正动到的那一块。
         * 其次站点（带 URL 的网页类）。都不成立 ⇒ 不限定（类目已经够窄）。
         */
        fun of(targets: List<File>, args: JSONObject, root: File?): LeaseScope {
            LeasePaths.commonDir(targets)?.let { return LeaseScope(Kind.DIR, it.path) }
            LeasePaths.hostOf(args)?.let { return LeaseScope(Kind.HOST, it) }
            return LeaseScope(Kind.WORKSPACE, root?.absolutePath.orEmpty())
        }
    }
}

/** 路径与站点的三件小工具（**唯一实现**，范围与命中都走它）。 */
internal object LeasePaths {

    /** 规范化：能 canonical 就 canonical（解符号链接/相对段），失败退回绝对路径。 */
    fun norm(f: File): File = runCatching { f.canonicalFile }.getOrElse { f.absoluteFile }

    /** [f] 是否在 [dir] 子树内（含 dir 本身）。两边都先规范化。 */
    fun isUnder(dir: File, f: File): Boolean {
        val d = norm(dir).path.trimEnd(File.separatorChar)
        val p = norm(f).path
        return p == d || p.startsWith(d + File.separatorChar)
    }

    /** 全部目标的共同父目录（空表 ⇒ null；跨根 ⇒ 一路退到共同祖先，退到头就 null）。 */
    fun commonDir(targets: List<File>): File? {
        if (targets.isEmpty()) return null
        var dir = norm(targets.first().absoluteFile.parentFile ?: return null)
        for (t in targets) {
            var guard = 0
            while (!isUnder(dir, t) && guard < MAX_WALK) {
                dir = norm(dir.parentFile ?: return null)
                guard += 1
            }
        }
        return dir
    }

    /** 从参数里取站点（只认这几个键；取不到 ⇒ null）。 */
    fun hostOf(args: JSONObject): String? {
        for (k in URL_KEYS) {
            val raw = args.optString(k)
            if (raw.isBlank()) continue
            val host = runCatching { java.net.URI(raw.trim()).host }.getOrNull()
            if (!host.isNullOrBlank()) return host
        }
        return null
    }

    private const val MAX_WALK = 64
    private val URL_KEYS = listOf("url", "site", "link", "href", "page")
}

/**
 * 判一张卡能不能给租约的**事实**（全部由调用方给：判定零 IO、可注入时间）。
 *
 * 前六格与 499 的 [com.example.zhengdao.baopu.agent.AskTierFacts] 同源——同一个采集点
 * （`GuardPipeline.askTierFacts`）出两份，两处不会漂。
 */
internal data class LeaseFacts(
    /** 类目（[LeaseRules.classOf] 的结果）。 */
    val cls: String,
    /** 工具名（拒授清单按名点几件制度面工具）。 */
    val tool: String,
    /** 矩阵判出的档（null＝表外）。 */
    val risk: RiskClass?,
    val unclassified: Boolean,
    /** 矩阵点名的必问。 */
    val mustAsk: Boolean,
    /** 目标全部在主根内（499 硬线之一）。 */
    val inRoot: Boolean,
    /** 可回滚（499 硬线之二：回收站/备份/纯新建）。 */
    val reversible: Boolean,
    /** 工具组（`ToolGroup.key`）。 */
    val groupKey: String,
    /** 能力类（`CapabilityKind.ofTool`）。 */
    val capability: CapabilityKind,
    /** 任一写目标落在原件区。 */
    val targetsInRawZone: Boolean,
    val targets: List<File>,
    val args: JSONObject,
    /** 工作区根（范围兜底用；null＝纯单测里没有根）。 */
    val root: File?,
)

/**
 * 卡上那一句的**报价**（与 [LeaseGrant] 分开：报价不是授权）。
 *
 * 为什么是 public：它随 `ConfirmRequest` 走到卡面（那个 data class 是 public），
 * 卡面要读 cap/ttlMs 渲染「≤N 次/≤T 小时」。除它俩之外，本包一律 internal。
 */
data class LeaseOffer(
    val cls: String,
    val scope: LeaseScope,
    val cap: Int,
    val ttlMs: Long,
)

/**
 * 租约的**判定层**（纯函数；顺序即法条）。
 *
 * 拒授九类（派工单判据＋蓝图定点）：499 三条硬线 → R3 → 外发/账号/联网 → 改制度
 * → 原件区 → 未分类 → 表外/MCP → 放大类 → 资料库（② 段对它是整段早退的）。
 */
internal object LeaseRules {

    /** 制度面（改技能/人设/定时/外部工具配置）：一律不租——按**组**拒，另有按名点几件。 */
    private val DENY_GROUPS = setOf(
        ToolGroup.SKILL.key,
        ToolGroup.SCHEDULE.key,
        ToolGroup.MCP.key,
        ToolGroup.LIBRARY.key,
    )

    /** 按名补拒的四件：它们能改制度，但**不在**上面那几个组里（光按组拒会漏）。 */
    private val DENY_TOOLS = setOf(
        "write_skill",
        "append_persona",
        "teach_skill_save",
        "import_data",
    )

    /** 放大类：目标表只有 1–2 个，实际搬整棵目录/解整包 ⇒ 按目标数计次压不住，整类不租。 */
    private val DENY_AMPLIFY = setOf(
        "bulk_copy",
        "bulk_move",
        "organize",
        "archive_extract",
        "import_data",
        "library_organize",
    )

    /** 记账口径被当读、实际是写的几件（`FileOpRules.kindOf` 是记账口径，权限口径要覆盖它）。 */
    private val READ_LIKE = setOf(FileOpKind.READ, FileOpKind.LIST, FileOpKind.SEARCH)

    /**
     * 类目（唯一口径）：**工具组 × 动作种类**。
     *
     * 有文件语义 ⇒ `组/种类`（`files/delete`、`edit/write`）；
     * 无文件语义 ⇒ `组/能力类`（`browser/browser`、`websearch/network`）。
     *
     * ⚠️ 一处结构覆盖：`convert_encoding` 在记账口径里是 READ，但它会写盘（矩阵 R2、
     * `writeTargets` 当写目标）——`!readOnly && 种类∈读类` 一律改判 `write`，
     * 否则一个写工具会落进 `files/read` 类目，租约就成了"读的租约放行写"。
     */
    fun classOf(spec: ToolSpec): String {
        val kind = FileOpRules.kindOf(spec.name)
        val effective = if (kind != null && !spec.readOnly && kind in READ_LIKE) FileOpKind.WRITE else kind
        if (effective != null) return "${spec.group.key}/${effective.tag}"
        return "${spec.group.key}/${CapabilityKind.ofTool(spec.group.key, spec.readOnly).wire}"
    }

    /**
     * 拒授原因（null＝可授）。**顺序即法条**：命中最前一条即返回。
     *
     * 为什么硬线排第一：租约只免"弹卡"，**不许成为 499 的第四个档位**——
     * 硬线（必问/未分类/R3）＋ 根外 ＋ 不可逆这三条任何档位都不许跳，租约更不许。
     */
    fun denyReason(f: LeaseFacts): String? = when {
        AskHardLines.hardAsk(f.mustAsk, f.unclassified, f.risk) ->
            "硬线：必问/未分类/外发花钱账号，任何档位都问，租约不碰"
        !f.inRoot -> "目标在工作区根外：租约只在主根内生效"
        !f.reversible -> "不可逆（无回收站/无备份）：租约不碰"
        f.risk == null -> "表外工具：没有本地判定，租约不接管"
        f.capability == CapabilityKind.NETWORK -> "联网取数：外发边界，租约不碰"
        f.groupKey in DENY_GROUPS -> "制度面类目（技能/定时/外部工具/资料库）：租约不碰"
        f.tool in DENY_TOOLS -> "这一件能改制度：租约不碰"
        f.tool in DENY_AMPLIFY -> "整目录/整包类：一次动作动的量不确定，租约不碰"
        f.targetsInRawZone -> "原件区：租约不碰"
        f.targets.isEmpty() -> "取不到写目标：范围定不下来，租约不碰"
        else -> null
    }

    /** 这次要扣几次：目标数（至少 1），**单次最多 2**（定点 D9）。 */
    fun costOf(targets: List<File>): Int =
        minOf(LeaseLimits.MAX_COST_PER_USE, maxOf(1, targets.size))

    /** 报价（不可授 ⇒ null；卡面那一行也随之为 null）。 */
    fun offerOf(f: LeaseFacts, limits: LeaseLimits = LeaseLimits.DEFAULT): LeaseOffer? {
        if (denyReason(f) != null) return null
        return LeaseOffer(
            cls = f.cls,
            scope = LeaseScope.of(f.targets, f.args, f.root),
            cap = limits.cap,
            ttlMs = limits.ttlMs,
        )
    }
}
