// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import com.example.zhengdao.rootfs.PathGuard
import com.example.zhengdao.util.TimeFormats
import java.io.File

/**
 * 第一百七十八批（§0.9 口径更正）· 自产稿件「清单勾选批量删」的**纯判据**。
 *
 * 图纸：`_zd-private/docs/派工单-2026-10-24-第一百七十八批-自产文件删除.md` §0.9＋用户 20:4x 原话
 * 「列出来那几条，然后用户自己勾选，勾选了它就自己去干」。
 *
 * 这一层只做四件事，全部可在 JVM 里用真临时目录直测（零 Android / 零 Compose）：
 * 1. **只列「产物目录里、台账有记录」的文件**——台账是「谁记的账」，目录是「东西还在不在」；
 *    两边的交集才是可删清单。用户自己扔进目录的文件不在这本账上 ⇒ 一个都不列。
 * 2. **勾选 → 一句确认 → 执行**——[batchGate] 是那道闸：`confirmed=false` 时**一条名字都不给**，
 *    界面拿到 [BatchGate.NeedsConfirm] 只能去问，不能动盘。
 * 3. **台账标 deleted 但行不清**——[markDeletedText] 只在原行**下面**插一行 🗑 删除标记；
 *    原来那一行逐字不动（[verifyMarked] 钉着：旧行顺序与内容一个字都不许变）。
 *    为什么必须保留原行：190 的查重指纹（`TopicSuggest.publishedFromLedger`）就认这一行，
 *    行一没，删过的题材会被当成没写过、又推一遍（用户点名的负例）。
 * 4. **审计一行**——[markDeletedInLedger] 每次批量删只调用一次，写一行删除标记；
 *    运行日志那一行由界面层写（`RunLog.log`）。
 *
 * 红线：
 * - 只碰 `<产物目录>/<台账行里那个文件名>`，文件名带路径分隔符 / 隐藏名 / 越界 ⇒ 一律不列；
 * - 本文件**没有** delete / deleteRecursively：文件只能走界面层的「移进回收站」（同卷 rename）；
 * - `资料库/原始/`（旧形态）与私有原件区不在任何路径拼法里（产物目录名由调用方给，本文件不拼）。
 */
internal object ArtifactDeleteRules {

    /** 台账稿件行：`- ☐/✔ <文件名>（N 字）`（与 179 落盘格式逐字一致）。 */
    private val ROW = Regex("""^\s*[-*]\s*([☐✔])\s+(.+?)（\s*(\d+)\s*字\s*）\s*$""")

    /** 删除标记行（插在原行下面）：`  - 🗑 已删 <文件名>（yyyy-MM-dd HH:mm · 移入回收站）`。 */
    private val MARK = Regex("""^\s*[-*]\s*🗑\s*已删\s+(.+?)（\d{4}-\d{2}-\d{2} \d{2}:\d{2} · 移入回收站）\s*$""")

    /** 拍板版文件名 `YYYYMMDD_赛道_序号_slug.ext`（slug 段里可能还有 `-` 连接的多词）。 */
    private val NAME_V2 = Regex("""^(\d{8})_(.+?)_(\d+)_(.+?)\.[A-Za-z0-9]+$""")

    /** 179 v1 文件名 `微头条-YYYYMMDD-HHmm-slug.ext`。 */
    private val NAME_V1 = Regex("""^(.+?)-(\d{8})-\d{4}-(.+?)\.[A-Za-z0-9]+$""")

    /** 台账一条稿件（只带事实，不带句子；中文怎么写归 strings）。 */
    data class LedgerItem(
        /** 台账里那一列文件名（不含目录）。 */
        val name: String,
        /** 从文件名解析的标题 slug；解析不出来就退回去掉扩展名的原名。 */
        val title: String,
        /** `yyyy-MM-dd`；解析不出来给空串（界面显示占位）。 */
        val date: String,
        /** 赛道（文件名第二段）；解析不出来给空串。 */
        val track: String,
        /** 台账记的字数。 */
        val chars: Int,
        /** `✔`＝已发；`☐`＝待发。 */
        val published: Boolean,
    )

    /** 清单一行 = 台账条目 ＋ 行号（标删除时锚定）＋ 盘上字节数。 */
    data class PickerRow(
        val item: LedgerItem,
        val lineIndex: Int,
        val bytes: Long,
    )

    /** 一句确认那道闸的下场：**不许静默跳过确认**。 */
    sealed interface BatchGate {
        /** 还没确认 ⇒ 一条名字都不给（界面只能去问）。 */
        data object NeedsConfirm : BatchGate

        /** 已确认 ⇒ [names] 是「选中 ∩ 当前列表」----列表外的名字在这里被剔掉。 */
        data class Ready(val names: List<String>) : BatchGate
    }

    /** 台账改写结果（[marked] 这次新插的、[already] 早就标过的、[missing] 台账里找不到的）。 */
    data class MarkResult(
        val text: String,
        val marked: List<String>,
        val already: List<String>,
        val missing: List<String>,
    )

    /** 落盘结果：[ok]=false 时 [why] 必须能给人看（英文短标签，界面翻成人话）。 */
    data class LedgerWriteResult(
        val ok: Boolean,
        val marked: List<String>,
        val already: List<String>,
        val why: String? = null,
    )

    // ── 台账解析 ──────────────────────────────────────────────────────────

    /** 台账全文 → 带行号的稿件条目（**只读**；按出现顺序，同名只认第一条）。 */
    fun parseRows(ledgerText: String?): List<Pair<Int, LedgerItem>> {
        if (ledgerText.isNullOrBlank()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Pair<Int, LedgerItem>>()
        ledgerText.split('\n').forEachIndexed { index, raw ->
            val m = ROW.matchEntire(raw.trimEnd('\r')) ?: return@forEachIndexed
            val name = m.groupValues[2].trim()
            if (!safeName(name) || !seen.add(name)) return@forEachIndexed
            val parts = nameParts(name)
            out += index to LedgerItem(
                name = name,
                published = m.groupValues[1] == "✔",
                chars = m.groupValues[3].toIntOrNull() ?: 0,
                title = parts.title,
                date = parts.date,
                track = parts.track,
            )
        }
        return out
    }

    /** 台账里已经带 🗑 删除标记的文件名（这些不再进清单）。 */
    fun deletedNames(ledgerText: String?): Set<String> {
        if (ledgerText.isNullOrBlank()) return emptySet()
        return ledgerText.lineSequence().mapNotNull { raw ->
            MARK.matchEntire(raw.trimEnd('\r'))?.groupValues?.get(1)?.trim()
        }.toSet()
    }

    /**
     * 可删清单：**台账有这一条 ＋ 产物目录里有这个文件 ＋ 没被标过删除**。
     *
     * @param filesInDir 产物目录里**普通文件**的名字 → 字节数（软链/子目录已由调用方筛掉）
     * @param includePublishedOnly 只看已发（界面的「已发」筛）
     */
    fun pickerRows(
        ledgerText: String?,
        filesInDir: Map<String, Long>,
        includePublishedOnly: Boolean = false,
    ): List<PickerRow> {
        val deleted = deletedNames(ledgerText)
        return parseRows(ledgerText).mapNotNull { (index, item) ->
            if (item.name in deleted) return@mapNotNull null
            if (item.name !in filesInDir) return@mapNotNull null
            if (includePublishedOnly && !item.published) return@mapNotNull null
            PickerRow(item, index, filesInDir[item.name] ?: 0L)
        }
    }

    // ── 一句确认的闸 ──────────────────────────────────────────────────────

    /**
     * 批量删除的闸（判据 N2「删到列表外」就死在这）。
     *
     * `selected` 里可能残留上一次列表的名字（筛了「已发」、又或者别人刚删过一条）⇒
     * 这里用 `visible` 把它剔掉；**未确认时连剔完的名单都不给**。
     */
    fun batchGate(
        selected: Collection<String>,
        visible: Collection<String>,
        confirmed: Boolean,
    ): BatchGate {
        val allowed = visible.toSet()
        val names = selected.filter { it in allowed }.distinct()
        return if (confirmed) BatchGate.Ready(names) else BatchGate.NeedsConfirm
    }

    // ── 台账标 deleted（行不清）────────────────────────────────────────────

    /**
     * 在每条被删稿件**原行下面**插一行删除标记；原行与其余所有行逐字不动。
     *
     * 幂等：已经标过的名字进 [MarkResult.already]，不会插第二行。
     * 台账里找不到的名字进 [MarkResult.missing]（不假装标了）。
     */
    fun markDeletedText(oldText: String, names: Collection<String>, stamp: String): MarkResult {
        val nl = if (oldText.contains("\r\n")) "\r\n" else "\n"
        val lines = oldText.split('\n').map { it.trimEnd('\r') }.toMutableList()
        val deleted = deletedNames(oldText)
        val wants = names.distinct()
        val already = wants.filter { it in deleted }
        val targets = wants.filter { it !in deleted }

        val rowIndex = LinkedHashMap<String, Int>()
        lines.forEachIndexed { index, line ->
            val m = ROW.matchEntire(line) ?: return@forEachIndexed
            val name = m.groupValues[2].trim()
            if (!rowIndex.containsKey(name)) rowIndex[name] = index
        }

        val marked = ArrayList<String>()
        val missing = ArrayList<String>()
        val insertions = ArrayList<Pair<Int, String>>()
        targets.forEach { name ->
            val index = rowIndex[name]
            if (index == null) {
                missing += name
            } else {
                marked += name
                insertions += index to markerOf(name, stamp)
            }
        }
        // 从下往上插，先插的不影响上面那几行的行号
        insertions.sortedByDescending { it.first }.forEach { (index, marker) ->
            lines.add(index + 1, marker)
        }
        return MarkResult(
            text = lines.joinToString(nl),
            marked = marked,
            already = already,
            missing = missing,
        )
    }

    /**
     * 硬判据：新台账 = 老台账**所有行逐字且同序保留** ＋ 只在下面多了这些标记行。
     * 负例（整表重写 / 删了原行 / 行序变 / 多写了别的行）⇒ false。
     *
     * 老台账里**已有的**删除标记（前几批删过的）不参与「新增」计数：先把它从新标记里减掉，
     * 剩下的必须恰好是这一次的 [expectedMarkers]——否则第二次批量删会被自己前一批的标记判红。
     */
    fun verifyMarked(oldText: String, newText: String, expectedMarkers: Collection<String>): Boolean {
        val oldLines = oldText.split('\n').map { it.trimEnd('\r') }
        val newLines = newText.split('\n').map { it.trimEnd('\r') }
        val oldMarkers = oldLines.filter { MARK.matchEntire(it) != null }
        val newMarkers = newLines.filter { MARK.matchEntire(it) != null }
        // ① 非标记行必须逐字同序（老行一行都不能少、顺序不能换、也不许多写别的行）
        if (oldLines.filterNot { MARK.matchEntire(it) != null } != newLines.filterNot { MARK.matchEntire(it) != null }) {
            return false
        }
        // ② 标记行：老标记一个不少，新增的恰好是这一次要写的那几行
        val remaining = oldMarkers.toMutableList()
        newMarkers.forEach { remaining.remove(it) }
        if (remaining.isNotEmpty()) return false
        if (expectedMarkers.size != newMarkers.size - oldMarkers.size) return false
        return expectedMarkers.all { it in newMarkers }
    }

    /**
     * 把删除标记写回台账（**多写者协议**与 179 同款：重读 → 临时文件＋rename＋.bak → 写后校验 →
     * 期间被别人改过就重读再试一次）。
     *
     * @return 失败时 [LedgerWriteResult.why] 是短标签（`no_ledger` / `concurrent` / `io`），
     *         界面负责翻成人话；**失败不回滚已经移进回收站的文件**（文件是用户要删的，
     *         台账没记上由界面如实说一句，下次还能看见这一条）。
     */
    fun markDeletedInLedger(ledger: File, names: Collection<String>, nowMs: Long): LedgerWriteResult {
        val wants = names.distinct()
        if (wants.isEmpty()) return LedgerWriteResult(true, emptyList(), emptyList())
        if (!ledger.isFile) return LedgerWriteResult(false, emptyList(), emptyList(), "no_ledger")
        val stamp = TimeFormats.minuteStamp(nowMs)
        var lastWhy = "io"
        repeat(2) {
            // ① 写前重读最新（不许用缓存）
            val oldText = runCatching { ledger.readText() }.getOrElse { return LedgerWriteResult(false, emptyList(), emptyList(), "io") }
            val result = markDeletedText(oldText, wants, stamp)
            if (result.marked.isEmpty()) {
                // 全都早已标过 / 台账里没有这几行 ⇒ 不用重写文件
                return LedgerWriteResult(true, result.marked, result.already)
            }
            val expected = result.marked.map { markerOf(it, stamp) }
            if (!verifyMarked(oldText, result.text, expected)) {
                return LedgerWriteResult(false, emptyList(), result.already, "io")
            }
            // ② 临时文件 + rename 原子替换 + .bak
            val dir = ledger.absoluteFile.parentFile
            val tmp = File(dir, ledger.name + ".tmp")
            val bak = File(dir, ledger.name + ".bak")
            val written = runCatching {
                tmp.writeText(result.text, Charsets.UTF_8)
                ledger.copyTo(bak, overwrite = true)
                if (!tmp.renameTo(ledger)) error("rename")
                true
            }.getOrDefault(false)
            if (!written) {
                runCatching { tmp.delete() }
                return LedgerWriteResult(false, emptyList(), result.already, "io")
            }
            // ③ 写后校验：落盘的必须就是这一版（期间别人改过 ⇒ 重来一次）
            val after = runCatching { ledger.readText() }.getOrNull()
            if (after == result.text) return LedgerWriteResult(true, result.marked, result.already)
            lastWhy = "concurrent"
        }
        return LedgerWriteResult(false, emptyList(), emptyList(), lastWhy)
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /** 删除标记行的唯一形状（解析与写入共用一处，防两套写法漂移）。 */
    internal fun markerOf(name: String, stamp: String): String = "  - 🗑 已删 $name（$stamp · 移入回收站）"

    /** 文件名里不许有路径成分：本区永远只在产物目录这一层动手。 */
    fun safeName(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n == "." || n == ".." || n.startsWith(".")) return false
        if (n.contains('/') || n.contains('\\')) return false
        return true
    }

    /** 产物目录里这一条该落的绝对路径（越界 / 名字不合法 ⇒ null，调用方不许猜）。 */
    fun fileIn(productDir: File, name: String): File? {
        if (!safeName(name)) return null
        val f = File(productDir, name)
        return if (PathGuard.isInside(productDir, f)) f else null
    }

    /** 文件名三段：标题 / 日期 / 赛道（两种命名都认，认不出就退回原名）。 */
    internal data class NameParts(val title: String, val date: String, val track: String)

    internal fun nameParts(name: String): NameParts {
        val stem = name.substringBeforeLast('.')
        NAME_V2.matchEntire(name)?.let { m ->
            val ymd = m.groupValues[1]
            return NameParts(
                title = stem.split('_').drop(3).joinToString("-").ifBlank { stem },
                date = "${ymd.substring(0, 4)}-${ymd.substring(4, 6)}-${ymd.substring(6, 8)}",
                track = m.groupValues[2],
            )
        }
        NAME_V1.matchEntire(name)?.let { m ->
            val ymd = m.groupValues[2]
            return NameParts(
                title = m.groupValues[3],
                date = "${ymd.substring(0, 4)}-${ymd.substring(4, 6)}-${ymd.substring(6, 8)}",
                track = m.groupValues[1],
            )
        }
        return NameParts(title = stem.ifBlank { name }, date = "", track = "")
    }
}
