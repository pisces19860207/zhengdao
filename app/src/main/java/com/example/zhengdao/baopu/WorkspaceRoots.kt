// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：492 工单 A 段①②「根清单 [{path, mode: RW|RO, label}]（默认 Download/证道；最多 5）；
// 相对路径按根序解析；同名歧义 ⇒ 报错列候选（不许猜）」＋「RO 写拒」。
//
// ## 三件东西分得很清（别混）
// 1. **主根**（清单第 0 条）：就是 [BaopuWorkspace.hostDir]——「当前工作区」的唯一真源，
//    恒 RW、恒在清单里。A④「单根行为与现状逐字一致（0 回归）」由这条结构保证：
//    没配附加根时清单只有主根一条，[WorkspaceRootRules.resolve] 的每条出口都折回
//    `File(主根, raw)`（与旧 `resolve(root, raw)` 同一算式），行为逐字节不变。
// 2. **附加根**（最多 4 条，清单总条数 ≤ [WorkspaceRootRules.MAX_ROOTS]）：
//    用户配置的额外可及根（RW 或 RO），落 `<filesDir>/baopu/workspace_roots.json`。
//    不进 SharedPreferences：这文件要能**当场改当场生效**（真机验收用 run-as 写入后
//    不重启即生效），而且它是配置不是用户数据——坏了就跳过那条并记日志，不猜。
// 3. **判据与壳**：[WorkspaceRootRules] 纯函数（无 Android、无 IO，JVM 直测）；
//    [WorkspaceRoots] 只管读文件与缓存。
//
// ## 与写侧的关系（不新增权限模型）
// RO 不是新沙箱：写侧判据仍是 `FilePolicy` 那一张拒绝清单（系统/凭据目录）。
// RO 是**用户对某个根的吩咐**——「那个目录只许看」——落在 [WorkspaceRootRules.writeDenial]，
// 由管线 ② 段在写类工具的写目标上执行（GuardPipeline）。读永远放行：RO 挡手不挡眼。
package com.example.zhengdao.baopu

import android.content.Context
import com.example.zhengdao.baopu.search.SearchDb
import com.example.zhengdao.core.PathGuard
import com.example.zhengdao.core.RunLog
import java.io.File

/** 根的模式：RW 可读写（现状默认）；RO 只读——读放行、写拒。 */
enum class RootMode(val tag: String) {
    RW("RW"),
    RO("RO"),
    ;

    companion object {
        /** 读不懂的写法 ⇒ null（配置坏条目跳过，不猜成 RW——发授权的事不许有默认值）。 */
        fun fromTag(tag: String): RootMode? = entries.firstOrNull { it.tag == tag }
    }
}

/** 清单里的一条根。 */
data class WsRoot(val dir: File, val mode: RootMode, val label: String)

/**
 * 多根工作区的判据（**纯函数**：无 Android、无 IO；`exists()` 是唯一例外——
 * 「同名条目在哪个根里真的存在」本来就是盘上的事实，测试用临时目录喂）。
 */
internal object WorkspaceRootRules {

    /** 清单总条数上限（工单「最多 5」写死；主根占第 0 条 ⇒ 附加根最多 4 条）。 */
    const val MAX_ROOTS = 5

    /** 主根的显示名（不是用户起的，是它「当前工作区」的身份）。 */
    const val LABEL_PRIMARY = "工作区"

    /** 把主根与附加根拨成清单：主根恒在第 0 位；重复根、超限的丢弃（先到先得，稳定序）。 */
    fun manifest(primary: File, extras: List<WsRoot>, max: Int = MAX_ROOTS): List<WsRoot> {
        require(max >= 1) { "max 至少要容得下主根，实传 $max" }
        val out = ArrayList<WsRoot>(max.coerceAtMost(MAX_ROOTS))
        out += WsRoot(primary, RootMode.RW, LABEL_PRIMARY)
        extras.forEach { e ->
            if (out.size >= max) return@forEach
            if (out.none { sameRoot(it.dir, e.dir) }) out += e
        }
        return out
    }

    /** 两个目录是不是同一个物理根（同一把归一：[FilePolicy.rootKey]，与读侧账本同源）。 */
    fun sameRoot(a: File, b: File): Boolean = FilePolicy.rootKey(a) == FilePolicy.rootKey(b)

    // ── A②：相对路径按根序解析 ──────────────────────────────────────────────

    /** 解析的一条出口。 */
    sealed interface Resolution {
        /** 绝对路径 ⇒ 原样（287：全盘可读写，多根清单不改它的语义）。 */
        data class Absolute(val file: File) : Resolution

        /** 相对路径恰好在一个根里存在 ⇒ 就是它。 */
        data class Single(val file: File, val root: WsRoot) : Resolution

        /** 哪个根里都不存在 ⇒ 回主根（写新文件、以及「不存在」错误文案的基准都在主根）。 */
        data class Fallback(val file: File, val primary: WsRoot) : Resolution

        /** 两个以上根里同名同位都存在 ⇒ 歧义：**报错列候选，不许猜**（工单 A②原文）。 */
        data class Ambiguous(val raw: String, val candidates: List<File>) : Resolution
    }

    /**
     * 解析（**纯函数**）。绝对性按 POSIX 判（`/` 开头）——目标是 Android；Windows 测试宿主
     * 里 `File("/x").isAbsolute` 是 false 的那条语义差异，与 [FilePolicy]「系统区按 POSIX 判」
     * 同一条口径（[isAbsoluteRaw] 可注入，测试喂 Windows 语义）。
     *
     * 相对路径的三层（从精确到祖先）：
     * 1. 精确路径在一个根里存在 ⇒ 那个根；在多个根里 ⇒ 歧义（工单 A②：列候选，不许猜）；
     * 2. 精确路径哪儿都没有 ⇒ 沿**最深存在的祖先**定根——写新文件要落进它该落的那个目录，
     *    而不是静默折回主根（否则「往 RO 子目录里写新文件」会变成「在主根另建一份」）；
     *    祖先在多个根里同样歧义（落哪个根必须说清）；
     * 3. 连祖先都没有（顶层新建）⇒ 主根——这就是旧单根算式，A④「0 回归」由这条结构保证。
     *
     * 613：入口先过 [UserPath.normalize]——`file://` 前缀与百分号编码在这里就剥掉，
     * 于是「`file://` 也算相对路径」这条老毛病（会被拼成 `<根>/file:/…`）在本段根治；
     * 归一后的绝对形态仍走 [Resolution.Absolute]，**不再拼根**。
     */
    fun resolve(
        roots: List<WsRoot>,
        raw: String,
        isAbsoluteRaw: (String) -> Boolean = ::defaultIsAbsolute,
    ): Resolution {
        val path = UserPath.normalize(raw)
        require(path.isNotEmpty()) { "空串不是路径（管线层把空 path 当参数错，不进这里）" }
        if (isAbsoluteRaw(path)) return Resolution.Absolute(File(path))
        fun hits(rel: String): List<Pair<WsRoot, File>> = roots.mapNotNull { r ->
            val f = File(r.dir, rel)
            if (f.exists()) r to f else null
        }
        val exact = hits(path)
        if (exact.size == 1) return Resolution.Single(exact[0].second, exact[0].first)
        if (exact.size > 1) return Resolution.Ambiguous(path, exact.map { it.second })
        var dir = path.substringBeforeLast('/', "")
        while (dir.isNotEmpty()) {
            val h = hits(dir)
            // 解析结果永远是「根 + 完整相对 path」——祖先只用来**选根**，
            // 不许拿祖先的 File 再拼一遍 path（那会拼出 根/资料库/整理/资料库/整理/… 的嵌套重路）。
            if (h.size == 1) return Resolution.Single(File(h[0].first.dir, path), h[0].first)
            if (h.size > 1) return Resolution.Ambiguous(path, h.map { File(it.first.dir, path) })
            dir = dir.substringBeforeLast('/', "")
        }
        return Resolution.Fallback(File(roots.first().dir, path), roots.first())
    }

    /** 绝对性判据（平台口径见 [resolve]）。 */
    private fun defaultIsAbsolute(raw: String): Boolean =
        File(raw).isAbsolute || raw.startsWith("/")

    /** 歧义那条给模型的原文（候选带序号与根 label——「列候选」要列得能照着用）。 */
    fun ambiguityMessage(resolution: Resolution.Ambiguous, roots: List<WsRoot>): String {
        val listed = resolution.candidates.joinToString("\n") { c ->
            val root = roots.firstOrNull { PathGuard.isInside(it.dir, c) }
            "- $c（${root?.label ?: "?"}，${root?.mode?.tag ?: "?"}）"
        }
        return "相对路径「${resolution.raw}」在多个根里都有同名条目，抱朴不猜：" +
            "请改成下面之一的绝对路径，或写明根内完整路径。\n$listed"
    }

    // ── RO 写拒 ─────────────────────────────────────────────────────────────

    /**
     * 写目标落进 RO 根 ⇒ 给模型的拒绝原文；null = 放行。
     *
     * 嵌套根的口径：RO 根里面再配一个 RW 根，RW 更具体、说了算（用户的清单是用户排的，
     * 更深的那条是更晚、更明确的吩咐）；其余任何 RO 根内的目标一律拒。
     */
    fun writeDenial(roots: List<WsRoot>, target: File): String? {
        val hit = roots.firstOrNull { it.mode == RootMode.RO && PathGuard.isInside(it.dir, target) }
            ?: return null
        val deeperRw = roots.any { r ->
            r.mode == RootMode.RW &&
                PathGuard.isInside(hit.dir, r.dir) &&
                PathGuard.isInside(r.dir, target)
        }
        if (deeperRw) return null
        return "「${hit.label}」是只读根（RO）：抱朴只读不写——新建/改写/删除都被拒。" +
            "产物请写进工作区（${roots.first().dir}），或由用户把这个根改成 RW。"
    }
}

/**
 * 附加根的存取壳（Context 到这一层为止）。清单文件：`<filesDir>/baopu/workspace_roots.json`。
 *
 * 没有写入口（工单边界「不做 UI」）：这文件由用户/验收用 run-as（或将来的设置页）放进去，
 * 本壳只读。读不懂的条目**跳过并记日志**（fail closed——清单只会变小不会变瞎），不抛、不猜。
 */
internal object WorkspaceRoots {

    /** 清单文件名（落 [SearchDb] 同一个 baopu 目录——App 私有，模型写不着）。 */
    const val FILE_NAME = "workspace_roots.json"

    /** 单条 JSON 的形状：{"path":"/storage/…","mode":"RO","label":"资料库"}（mode/label 可省）。 */
    private const val KEY_PATH = "path"
    private const val KEY_MODE = "mode"
    private const val KEY_LABEL = "label"

    private const val MAX_LABEL_CHARS = 20

    /** (路径, mtime, 长度) 三元组命中才用缓存——文件被 run-as 改过的下一笔调用立即生效。 */
    @Volatile
    private var cache: Pair<Triple<String, Long, Long>, List<WsRoot>>? = null

    /** 附加根（不含主根；主根由调用方 [WorkspaceRootRules.manifest] 拼）。文件不在 ⇒ 空表。 */
    fun extras(ctx: Context): List<WsRoot> {
        val f = file(ctx)
        if (!f.isFile) return emptyList()
        val key = Triple(f.absolutePath, runCatching { f.lastModified() }.getOrDefault(0L), f.length())
        cache?.let { (k, v) -> if (k == key) return v }
        val parsed = parse(f.absolutePath, runCatching { f.readText() }.getOrDefault(""))
        cache = key to parsed
        return parsed
    }

    /** 清单文件落点（测试与验收用）。 */
    fun file(ctx: Context): File = File(File(ctx.filesDir, SearchDb.DIR_BAOPU), FILE_NAME)

    /**
     * 解析清单文本（**纯函数**：路径文本进，附加根出——JVM 直测）。
     * `source` 只进日志不进判据；坏条目跳过，一条没剩 ⇒ 空表（= 单根现状）。
     */
    fun parse(source: String, text: String): List<WsRoot> {
        val arr = runCatching { org.json.JSONArray(text) }.getOrElse {
            RunLog.log("工作区根清单没读成（$source 不是 JSON 数组）：按没有附加根处理")
            return emptyList()
        }
        val out = mutableListOf<WsRoot>()
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrElse {
                RunLog.log("工作区根清单第 ${i + 1} 条不是对象：跳过")
                continue
            }
            val path = o.optString(KEY_PATH).trim()
            val mode = RootMode.fromTag(o.optString(KEY_MODE, RootMode.RW.tag).trim())
            val problem = validExtra(path, mode)
            if (problem != null) {
                RunLog.log("工作区根清单第 ${i + 1} 条没接受（$problem）：跳过")
                continue
            }
            val label = o.optString(KEY_LABEL).trim()
                .ifBlank { path.trimEnd('/').substringAfterLast('/') }
                .take(MAX_LABEL_CHARS)
            out += WsRoot(File(path), mode!!, label)
        }
        return out
    }

    /**
     * 一条附加根的合法性（**纯函数**，[parse] 与测试共用这一把）：
     * POSIX 绝对路径、不带 `..`、不是系统/凭据目录（[FilePolicy.denyReasonOf] 同一把判据）。
     */
    fun validExtra(path: String, mode: RootMode?): String? = when {
        path.isEmpty() -> "path 为空"
        !path.startsWith("/") -> "path 必须是绝对路径（/ 开头）"
        path.split('/').any { it == ".." } -> "path 不许带 .."
        mode == null -> "mode 只认 RW/RO"
        FilePolicy.denyReasonOf(path, com.example.zhengdao.BuildConfig.APPLICATION_ID) != null ->
            "path 落在系统/凭据目录（FilePolicy 拒绝清单）"
        else -> null
    }
}
