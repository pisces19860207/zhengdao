// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十七批 · 能力边界声明的**唯一数据源表**（派工单 §二①②③）。
//
// 病灶（505 交付 Q4 的原话）：能力边界声明无唯一数据源 ⇒ 各写各的必然漂移——
// 505 把声明写死在 [CapabilityBoundaries] 的代码里（writableRoots／maxDurationMs 两维干脆空着），
// 判定层读它、执行层（②段红线）读 `FilePolicy`／`WorkspaceRootRules`、体检单谁都不读。
// 本文件把这件事倒过来：**一张表（数据）说了算**，越界判定、执行闸、体检展示都从它读；
// 改表即改行为，不改代码。
//
// 三条硬规矩（判据③④⑤的牙）：
//   ① **缺行 ⇒ 最严**：表里没有这一类能力的行 ⇒ 不可写、时限最短、不许外发，并记一笔账
//     （「没声明」不是「不确定」——505 把没声明读成 UNCERTAIN 是判定层的语义，保留在
//     [BoundaryRules] 里；**执行闸**认的是本表，没声明就是不许，两句分属两层，见文件尾）；
//   ② **改表即改行为**：表是盘上一份数据（`边界声明.json`），装一次、校一遍，此后判定全走它；
//   ③ **schema 校验**：格式不对 ⇒ **整表拒载**（宁可全按最严，也不拿半份声明放行），
//     拒载本身记一笔账——报错不许只有 log 一条，账上要查得到"哪一刻表坏了"。
//
// 与 505 那层的关系（一句白话）：
//   [BoundaryRules] 判「要不要升级」——**不许把不确定当越界**（UNCERTAIN 没有升级口，475 红线）；
//   本表管「这一笔动作准不准做」——**没声明就不许做**（做之前拦下，不是做完升级）。
//   升级三要素（[Escalation]）一字不动：本表只提供边界来源（派工单 §四）。
//
// 可写根写的是**根类**（`workspace`／`private`），不是盘上路径：工作区根是用户改的、
// filesDir 是装机才知的，声明表里存类、装配时展开成真路径——表不随工作区漂移，
// 「改表即改行为」测的是表本身。类清单收窄在 [ROOT_CLASSES]（闭集）。
package com.example.zhengdao.baopu.escalate

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.TeachSiteTrust
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 一行声明（一类能力一行；派工单 §二①点名的四样＋能力类主键）。 */
internal data class BoundaryRow(
    val kind: CapabilityKind,
    /** 可写根的**根类**清单（空 ⇒ 这一类不可写——声明为空与缺行同罪，判据③的字面）。 */
    val writableRoots: List<String> = emptyList(),
    /** 能力级时长上限（毫秒；0＝这一维没声明 ⇒ 判定层说不准，执行闸不拿它判）。 */
    val maxDurationMs: Long = 0L,
    /** 允许站点（host 清单；空 ⇒ 不许外发，判据③）。 */
    val allowedSites: List<String> = emptyList(),
    /** 允许外发（false ⇒ 这一类不许把数据送出本机）。 */
    val allowOutbound: Boolean = false,
)

/** 一次执行闸的裁定（拒绝必带原因；原因要能落账）。 */
internal data class BoundaryRuling(val allowed: Boolean, val reason: String, val base: String)

/**
 * 能力边界声明表（唯一数据源）。
 *
 * 谁读它（派工单 §二②点名的三处，本批全接上）：
 *   · **执行闸**：[checkWrite]——`GuardPipeline` ②段写红线调用，写声明之外 ⇒ 拒＋记账；
 *   · **越界判定**：[boundaryOf]——`TraceBlackbox` 交 475 前把边界对象换成表出的这一份；
 *   · **体检展示**：[sheetLine]——体检单「边界声明」一行。
 */
internal object BoundaryTable {

    const val FILE_NAME: String = "边界声明.json"

    /** 行格式 schema（稳定键：拒载判定按它认表）。 */
    const val SCHEMA: String = "zd-boundary/1"

    /** 执行记账（缺行/置空被拒的那一笔笔，都落这本）；账文件与 475 升级账同棵目录。 */
    const val ENFORCEMENT_FILE: String = "边界执行.jsonl"

    /** 可写根类闭集（展开表由装配层给；类认不出 ⇒ 那一类展开成空 ⇒ 不可写，保守方向）。 */
    const val ROOT_WORKSPACE: String = "workspace"
    const val ROOT_PRIVATE: String = "private"
    val ROOT_CLASSES: Set<String> = setOf(ROOT_WORKSPACE, ROOT_PRIVATE)

    /** 体检行最多点名几类（超出说「另有 N 类」，不把单子撑爆——同 475 升级行的口径）。 */
    private const val SHEET_KIND_MAX = 4

    @Volatile
    private var rows: Map<CapabilityKind, BoundaryRow> = emptyMap()

    @Volatile
    private var installed: Boolean = false

    /** 根类 ⇒ 本机真路径（装配层注入；没注入 ⇒ 类展开成空 ⇒ 不可写，保守方向）。 */
    @Volatile
    private var rootsProvider: () -> Map<String, List<File>> = { emptyMap() }

    /**
     * 装配（一次启动装一次；重复装以最后一次为准——测试里换表重装就是判据③的测法）。
     *
     * 文件不存在 ⇒ 播默认声明并落盘（默认值只登记**生产里已有的事实**，见 [defaultRows]）；
     * 文件存在但 schema/形状不对 ⇒ **拒载**（整表按空＝处处最严）＋报错＋记一笔账。
     */
    fun install(dir: File, roots: () -> Map<String, List<File>>) {
        rootsProvider = roots
        val f = File(dir, FILE_NAME)
        if (!f.isFile) {
            rows = defaultRows().associateBy { it.kind }
            runCatching {
                f.parentFile?.mkdirs()
                f.writeText(toJson(rows.values.toList()).toString(), Charsets.UTF_8)
            }.onFailure { RunLog.log("边界声明表首次落盘失败：${it.javaClass.simpleName}（判定按内存表走）") }
        } else {
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
            val loaded = fromJson(text)
            if (loaded == null) {
                rows = emptyMap()
                RunLog.log("边界声明表拒载：schema/形状不合格（$f）——全部按最严处理并记账")
                record("table_rejected", "-", "-", "声明表拒载：schema/形状不合格", "invalid_table", 0L)
            } else {
                rows = loaded.associateBy { it.kind }
            }
        }
        installed = true
        RunLog.log("边界声明表装配：${rows.size} 类已声明（缺行按最严）")
    }

    /** 直测收尾（生产不调）：摘表＝执行闸回到「机制没装配」的原样。 */
    fun resetForTest() {
        rows = emptyMap()
        installed = false
        rootsProvider = { emptyMap() }
    }

    fun isInstalled(): Boolean = installed

    /** 这一类的声明行（缺行 ⇒ null ⇒ 执行闸按最严、判定层按"没声明"）。 */
    fun rowOf(kind: CapabilityKind): BoundaryRow? = rows[kind]

    /**
     * 执行闸：这一笔**写**准不准落盘（`GuardPipeline` ②段调用）。
     *
     * 顺序即法条：机制没装配 ⇒ 不拦（本批的闸跟着装配走，与 ShadowAdapter 同款）；
     * 缺行 ⇒ 拒（最严）；声明为空 ⇒ 拒；目标在任何声明的根类里 ⇒ 放行；其余 ⇒ 拒。
     * 拒的那一笔**记一笔账**（[record]，判据④的"并记账"）。
     */
    fun checkWrite(kind: CapabilityKind, target: File, nowMs: Long): BoundaryRuling {
        if (!installed) return BoundaryRuling(true, "", "not_installed")
        val row = rows[kind]
        if (row == null) {
            val why = "「${kind.cn}」没有边界声明行——按最严：不可写（缺行最严）"
            record("write_denied", kind.wire, target.absolutePath, why, "missing_row", nowMs)
            return BoundaryRuling(false, why, "missing_row")
        }
        if (row.writableRoots.isEmpty()) {
            val why = "「${kind.cn}」的可写根声明为空——按最严：不可写（声明为空同缺行）"
            record("write_denied", kind.wire, target.absolutePath, why, "empty_roots", nowMs)
            return BoundaryRuling(false, why, "empty_roots")
        }
        val roots = expandedRoots(row.writableRoots)
        val inside = roots.any { root -> PathInside.inside(root, target) }
        if (inside) return BoundaryRuling(true, "", "inside_roots")
        val why = "写目标落在「${kind.cn}」声明的可写根之外（声明根：${row.writableRoots.joinToString("、")}）"
        record("write_denied", kind.wire, target.absolutePath, why, "outside_roots", nowMs)
        return BoundaryRuling(false, why, "outside_roots")
    }

    /**
     * 越界判定吃的边界对象（`TraceBlackbox` 交 475 前用；**唯一来源换成本表**）。
     *
     * 缺行 ⇒ null：判定层对 null 的语义是"除硬事实外说不准"（505 的红线原样保留）——
     * 升级只认**已声明的边界被可证超出**或②段硬拦这两条来路；执行侧的"不许做"
     * 由 [checkWrite] 在动作发生前拦，两层各司其职。
     */
    fun boundaryOf(kind: CapabilityKind): CapabilityBoundary? {
        val row = rows[kind] ?: return null
        return CapabilityBoundary(
            kind = kind,
            tools = toolsOf(kind),
            writableRoots = expandedRoots(row.writableRoots).map { it.absolutePath },
            budgetCalls = BaopuKernel.Budget.MAX_STEPS,
            maxDurationMs = row.maxDurationMs,
            allowedSites = row.allowedSites,
        )
    }

    /** 体检单那一行（展示读表，本层不另记一份声明）。 */
    fun sheetLine(): String {
        if (!installed) return "边界声明：机制没装配（执行闸不拦）"
        if (rows.isEmpty()) return "边界声明：0 类已声明——全部按最严（不可写／不许外发）"
        val named = rows.values.take(SHEET_KIND_MAX).joinToString("、") { it.kind.cn }
        val rest = rows.size - SHEET_KIND_MAX
        val outbound = rows.values.count { it.allowOutbound }
        return "边界声明：${rows.size} 类已声明（$named${if (rest > 0) "，另有 $rest 类" else ""}）" +
            "· 允许外发 $outbound 类 · 缺行按最严"
    }

    /** 执行记账回放（读侧：判据测试与人工抽检查这一本）。 */
    fun enforcementReplay(dir: File): List<String> {
        val f = File(dir, ENFORCEMENT_FILE)
        if (!f.isFile) return emptyList()
        return runCatching { f.readLines(Charsets.UTF_8).filter { it.isNotBlank() } }.getOrDefault(emptyList())
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun expandedRoots(classes: List<String>): List<File> {
        val table = rootsProvider()
        return classes.filter { it in ROOT_CLASSES }
            .flatMap { table[it] ?: emptyList() }
    }

    private fun record(event: String, kindWire: String, target: String, why: String, base: String, nowMs: Long) {
        val line = JSONObject()
            .put("v", 1)
            .put("at", nowMs)
            .put("k", event)
            .put("kind", kindWire)
            .put("target", target)
            .put("base", base)
            .put("why", why)
            .toString()
        val root = DecisionLedger.installedRoot()
        if (root == null) {
            RunLog.log("边界执行：$why（账根没挂，只留日志）")
            return
        }
        val ok = runCatching {
            val f = File(root, ENFORCEMENT_FILE)
            if (!f.parentFile.isDirectory) f.parentFile?.mkdirs()
            f.appendText("$line\n", Charsets.UTF_8)
        }.isSuccess
        if (!ok) RunLog.log("边界执行账没落上（$event）：$why")
        RunLog.log("边界闸：$why")
    }

    private fun toolsOf(kind: CapabilityKind): Set<String> = when (kind) {
        CapabilityKind.OTHER -> emptySet()
        CapabilityKind.BROWSER -> groupMembers(CapabilityKind.BROWSER_GROUP)
        CapabilityKind.NETWORK -> groupMembers(CapabilityKind.WEBSEARCH_GROUP)
        CapabilityKind.MODEL -> groupMembers("agent")
        else -> CapabilityKind.FILE_GROUPS.flatMap { groupMembers(it) }.toSet()
    }

    private fun groupMembers(groupKey: String): Set<String> =
        ToolGroupTable.GROUP_OF.filterValues { it.key == groupKey }.keys

    /** 默认声明：只登记**生产里已有的事实**，一个数都不新造。 */
    private fun defaultRows(): List<BoundaryRow> = listOf(
        // 写盘/读盘：可写根＝工作区（RW 根）＋应用私有树（`filesDir` 全域，记忆/技能都在里面）。
        // 站点/外发：文件能力不联网，不放外发。
        BoundaryRow(CapabilityKind.FILE_WRITE, writableRoots = listOf(ROOT_WORKSPACE, ROOT_PRIVATE)),
        BoundaryRow(CapabilityKind.FILE_READ, writableRoots = listOf(ROOT_WORKSPACE, ROOT_PRIVATE)),
        // 网页/联网取数：站点由回放信任站点那把尺管（TeachSiteTrust），表里先空着＝不许外发；
        // 这两类的写路径不存在（无 path 参数），空可写根不拦它们的本行。
        BoundaryRow(CapabilityKind.BROWSER, allowedSites = emptyList()),
        BoundaryRow(CapabilityKind.NETWORK, allowedSites = emptyList()),
        // 模型调用：无写路径；外发本就是它的本行（调用模型服务），明说允许。
        BoundaryRow(CapabilityKind.MODEL, allowOutbound = true),
    )

    // ── 序列化（schema 校验拒收半份表）────────────────────────────────────

    fun toJson(rows: List<BoundaryRow>): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put(
            "rows",
            JSONArray().apply {
                rows.forEach { r ->
                    put(
                        JSONObject().apply {
                            put("kind", r.kind.wire)
                            put("writableRoots", JSONArray(r.writableRoots))
                            put("maxDurationMs", r.maxDurationMs)
                            put("allowedSites", JSONArray(r.allowedSites))
                            put("allowOutbound", r.allowOutbound)
                        },
                    )
                }
            },
        )
    }

    /**
     * 读表：**任何一格不合格 ⇒ 整表作废**（返回 null，装配层按"全部最严"处理并报错）。
     * 不跳过坏行、不补默认值——半份声明比没有更危险：它看起来像声明过。
     */
    fun fromJson(text: String): List<BoundaryRow>? = runCatching {
        val o = JSONObject(text)
        if (o.optString("schema") != SCHEMA) return@runCatching null
        val arr = o.optJSONArray("rows") ?: return@runCatching null
        val out = mutableListOf<BoundaryRow>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: return@runCatching null
            val kind = CapabilityKind.ofWire(r.optString("kind")) ?: return@runCatching null
            val roots = stringListOf(r.optJSONArray("writableRoots")) ?: return@runCatching null
            val sites = stringListOf(r.optJSONArray("allowedSites")) ?: return@runCatching null
            val maxMs = r.optLong("maxDurationMs", -1L)
            if (maxMs < 0L) return@runCatching null
            if (!r.has("allowOutbound")) return@runCatching null
            out += BoundaryRow(
                kind = kind,
                writableRoots = roots.map { it.trim() }.filter { it.isNotEmpty() },
                maxDurationMs = maxMs,
                allowedSites = sites,
                allowOutbound = r.optBoolean("allowOutbound"),
            )
        }
        out
    }.getOrNull()

    private fun stringListOf(arr: JSONArray?): List<String>? {
        arr ?: return null
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) out += arr.optString(i)
        return out
    }
}

/** 路径 containment（组件级；同 [BoundaryRules.isInsideRoot] 的口径，文件对象版）。 */
internal object PathInside {
    fun inside(root: File, target: File): Boolean {
        val r = normalize(root.absolutePath)
        val t = normalize(target.absolutePath)
        if (r.isEmpty() || t.isEmpty()) return false
        if (t == r) return true
        return t.startsWith("$r/")
    }

    private fun normalize(raw: String): String = raw.replace('\\', '/').trimEnd('/').let {
        if (it.startsWith("//")) it.trimStart('/') else it
    }
}

/**
 * 站点口径的借条（本批没新造尺）：
 * 允许站点非空 ⇒ 照 [TeachSiteTrust.covers] 判；为空 ⇒ 不许外发（判据③）。
 * 判定层 [com.example.zhengdao.baopu.escalate.BoundaryRules.siteDim] 的"没声明＝说不准"
 * 保留原样——这一层是执行闸，语义就是最严。
 */
internal object SiteGate {
    fun allows(host: String?, row: BoundaryRow?): BoundaryRuling {
        if (host == null) return BoundaryRuling(true, "", "no_host")
        if (row == null) {
            return BoundaryRuling(false, "「$host」无处声明——按最严：不许外发（缺行）", "missing_row")
        }
        if (row.allowedSites.isEmpty()) {
            return BoundaryRuling(false, "「${row.kind.cn}」未声明允许站点——按最严：不许外发", "empty_sites")
        }
        return if (TeachSiteTrust.covers(host, row.allowedSites)) {
            BoundaryRuling(true, "", "site_ok")
        } else {
            BoundaryRuling(false, "「$host」不在「${row.kind.cn}」允许站点之内", "outside_sites")
        }
    }
}
