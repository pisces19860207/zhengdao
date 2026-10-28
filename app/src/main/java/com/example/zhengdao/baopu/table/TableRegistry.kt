// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · 表登记（**先知道有哪些表，再谈问答**）。
//
// 派工单 §2.1 的四条：
// 1. 发现：工作区 / `Download/证道` / 资料库目录下的 `*.xlsx` `*.csv` `*.tsv`，**不扫 PDF**；
// 2. 登记：`抱朴记忆/表格/registry.json`——路径、mtime、size、sheet 列表、识别到的表头行、
//    行数、主键候选；
// 3. 增量：**mtime/size 变了才重解析**（与 182 同口径，不全量重扫）；
// 4. 识别失败要说清：标 `header_unknown`，问一句「第几行是表头」，不猜。
//
// ## 目录口径（与 157/179 收口后的现行法一致）
// 派工单写的三个目录，在 157「目录收口」与 179「拔掉 资料库/ 那一层」之后是**同一棵树**：
// 工作区默认就是 `Download/证道`，旧 `资料库/` 的内容已被迁进工作区。所以本文件的扫描根是
// 「工作区根 + 默认工作区（不同时才算第二个根）」，**递归**扫一遍就覆盖了派工单那三处；
// 这里**不引用** `LEGACY_LIBRARY_DIR`（内核注释把它列为新代码禁字——引一次就等于永远清不掉那层壳）。
//
// ## 为什么 registry 落在工作区而不是 App 私有
// 它描述的是**工作区里的文件**：工作区一换，旧登记就全错。跟着工作区走 ⇒ 换回来还是对的，
// 而且用户能自己翻、自己删（删了下次重建，不丢东西）。
package com.example.zhengdao.baopu.table

import com.example.zhengdao.baopu.agent.SheetCsv
import com.example.zhengdao.baopu.agent.SheetGrid
import com.example.zhengdao.baopu.agent.SheetLimits
import com.example.zhengdao.baopu.agent.SheetReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一个扫描根。[label] 非空时进 [TableEntry.rel] 前缀（多根时才需要区分）。 */
data class TableRoot(val dir: File, val label: String = "")

/** 一张 sheet 的登记（一个 xlsx 里可能有好几张表，各记各的）。 */
data class SheetEntry(
    val name: String,
    /** 0 起；`-1` ＝ header_unknown。 */
    val headerRow: Int,
    val rows: Int,
    val columns: List<EntryColumn>,
    val keys: List<String>,
    val warnings: List<String>,
    /** 认不出表头时的那句话（非 null ⇒ 这张 sheet 要问用户）。 */
    val ask: String? = null,
    /** 243③：用户指定的表头行（1 起）；存盘后不再猜也不再问。 */
    val userHeaderRow: Int? = null,
    /** 243③：记住表头时这张表的 mtime（内容变了要提醒）。 */
    val userHeaderMtime: Long? = null,
)

/** 登记里的列（**只存列名与类型，不存任何单元格取值**——姓名与手机号不许落盘）。 */
data class EntryColumn(val name: String, val type: String, val key: Boolean)

/** 一个文件一条登记。 */
data class TableEntry(
    /** 相对扫描根的正斜杠路径（出处念它）。 */
    val rel: String,
    val root: String,
    val mtime: Long,
    val size: Long,
    val sheets: List<SheetEntry>,
    val scannedAt: Long,
    /**
     * 252：这张表是 `.xls` 转出来的吗？非 null ⇒ 它**不是**登记簿的「原生」集合成员——
     * 它先进过缓存目录、被转成 CSV 才进得来（派工单 §1①：`.xls` 与 `.xlsx` 不同级）。
     */
    val xls: XlsNotice? = null,
) {
    /** 默认问哪张 sheet：第一张认得出表头的；都认不出就第一张（让它去问用户）。 */
    val defaultSheet: SheetEntry? get() = sheets.firstOrNull { it.headerRow >= 0 } ?: sheets.firstOrNull()

    val known: Boolean get() = sheets.any { it.headerRow >= 0 }

    fun sheetNamed(name: String?): SheetEntry? =
        if (name.isNullOrBlank()) defaultSheet else sheets.firstOrNull { it.name == name }
}

/** 一次刷新的读数（负例 7「每次全量重扫」就靠 [parsed] 钉住）。 */
data class RefreshReport(
    val scanned: Int,
    /** 真解析了几个文件（＝0 表示全是复用）。 */
    val parsed: Int,
    val reused: Int,
    val removed: Int,
    /** 跳过的 PDF 个数（不扫，但要如实报——「不扫」不等于「没有」）。 */
    val pdfSkipped: Int,
)

/**
 * 表登记簿。
 *
 * 线程口径：只有一个调用方线程（工具在工具线程里跑），内部 [synchronized] 兜住面板与工具
 * 同时进来的那一下——登记与内存缓存是同一份状态，撕不开。
 */
/** 243②：原件区的末级目录名（资料库/原始、library/原始）。 */
private const val RAW_SUB = "原始"

class TableRegistry(
    private val home: File,
    roots: List<TableRoot>,
    private val maxRows: Int = SheetLimits.MAX_ROWS,
) {

    private val roots: List<TableRoot> = roots
        .filter { it.dir.isDirectory }
        .distinctBy { it.dir.absolutePath }
        .let { list ->
            // 只有第一个根不要前缀（它就是工作区根）；其余加目录名当前缀
            if (list.size <= 1) list else list.mapIndexed { i, r ->
                if (i == 0 || r.label.isNotEmpty()) r else r.copy(label = r.dir.name)
            }
        }

    private var entries: Map<String, TableEntry> = emptyMap()

    /** 本次进程真解析过的文件数（测试拿它证明「没有全量重扫」）。 */
    var parseCount: Int = 0
        private set

    /** 内存里的模型（按 `rel#sheet` 缓存；刷新时按文件失效）。 */
    private val cache = HashMap<String, TableModel>()

    val registryFile: File get() = File(home, FILE_NAME)

    /** 252：`.xls` 转出来的 CSV 落在哪（**固定** `<工作区>/抱朴记忆/表格/缓存/`；R1 约束 a）。 */
    val cacheDir: File get() = File(home, XlsCompat.DIR_CACHE)

    /** 252：有没有 `.xls` 正在转（面板/日志要的「正在转换」可视状态；转换本身不在这里调度）。 */
    val convertingXls: Boolean get() = XlsProgress.active

    /** 252：上一次刷新里没能转成的一张 `.xls`（rel → 一句给用户的话；**不是异常，是话**）。 */
    val xlsFailures: Map<String, String> get() = synchronized(this) { LinkedHashMap(xlsFail) }

    private val xlsFail = LinkedHashMap<String, String>()

    fun entries(): List<TableEntry> = synchronized(this) { entries.values.toList() }

    fun entryOf(rel: String): TableEntry? = synchronized(this) { entries[rel] }

    fun isEmpty(): Boolean = synchronized(this) { entries.isEmpty() }

    /**
     * 增量刷新。
     *
     * **只对 mtime/size 变了的文件重新解析**——这是派工单 §2.1③ 的硬要求，
     * 也是负例 7「每次问答都全量重扫表」的靶子：`refresh()` 返回的 [RefreshReport.parsed]
     * 在没有文件变化时必须是 0。
     */
    fun refresh(now: Long = System.currentTimeMillis()): RefreshReport = synchronized(this) {
        if (entries.isEmpty()) loadFromDisk()
        xlsFail.clear() // 252：失败账只对**本次刷新**有效（转成功了下次就不该再提）
        val found = discover()
        // 252 · R1 约束 c：**扫描时顺手清缓存**（源被删 / 源变了 ⇒ 旧 CSV 立刻清掉）。
        // 惰性，不注册任何后台任务；只在至少有一个根还能列的时候做——
        // 整盘拔掉（U 盘/同步目录暂时不可见）时 discover 必然返回空，那时清缓存等于把好的缓存全删了。
        if (roots.any { it.dir.isDirectory }) {
            XlsCompat.sweep(cacheDir, found.map { it.file }.filter { XlsCompat.isXls(it.name) })
        }
        val next = LinkedHashMap<String, TableEntry>(found.size)
        var parsed = 0
        var reused = 0
        for (hit in found) {
            val old = entries[hit.rel]
            if (old != null && old.mtime == hit.mtime && old.size == hit.size) {
                next[hit.rel] = old
                reused++
                continue
            }
            // 先失效这个文件的旧模型，再解析（顺序反了会把刚建好的缓存又删掉——
            // 那会让 modelOf 每次都重解析，正是负例 7「每次问答都全量重扫」的另一种形态）
            cache.keys.removeAll { it.startsWith("${hit.rel}#") }
            val entry = parseFile(hit, now)
            // 243③：重解析不能把用户指定的表头行丢了（负例 6）
            val merged = entry?.let { e ->
                old?.let { o ->
                    e.copy(
                        sheets = e.sheets.map { sh ->
                            val prev = o.sheets.firstOrNull { it.name == sh.name }
                            if (prev != null) {
                                sh.copy(userHeaderRow = prev.userHeaderRow, userHeaderMtime = prev.userHeaderMtime)
                            } else sh
                        },
                    )
                } ?: e
            }
            if (merged != null) next[hit.rel] = merged
            parsed++
        }
        val removed = entries.keys.count { it !in next.keys }
        val changed = parsed > 0 || removed > 0 || next.size != entries.size
        entries = next
        if (changed || !registryFile.isFile) persist()
        RefreshReport(
            scanned = found.size,
            parsed = parsed,
            reused = reused,
            removed = removed,
            pdfSkipped = pdfSkipped,
        )
    }

    /** 上一次 [discover] 跳过几个 PDF（「不扫」也要能说出跳了几个）。 */
    var pdfSkipped: Int = 0
        private set

    /**
     * 取一张表的模型。
     *
     * @param rel 登记里的相对路径
     * @param sheet sheet 名（null ＝ 默认那张）
     * @param headerRowOverride 用户指定（1 起）；给了就覆盖登记里的表头行
     * @return null ＝ 没登记过这张表（调用方要说「没找到这张表」，不许当成空表）
     */
    fun modelOf(rel: String, sheet: String? = null, headerRowOverride: Int? = null): TableModel? =
        synchronized(this) {
            val entry = entries[rel] ?: return null
            val target = entry.sheetNamed(sheet) ?: return null
            // 243③：用户没现场指定时，用登记里记住的表头行
            val effective = headerRowOverride ?: target.userHeaderRow
            val key = "$rel#${target.name}#${effective ?: -1}"
            cache[key]?.let { return it }
            val file = fileOf(entry) ?: return null
            val model = readModel(entry, file, target.name, effective) ?: return null
            cache[key] = model
            model
        }

    /** 全部模型（选表用；只读缓存里已有的，不额外解析——没缓存就现读一次）。 */
    fun models(): List<TableModel> = synchronized(this) {
        entries.values.mapNotNull { e ->
            val s = e.defaultSheet ?: return@mapNotNull null
            modelOf(e.rel, s.name)
        }
    }

    // ── 发现 ────────────────────────────────────────────────────────────────

    private data class Found(
        val rel: String,
        val rootLabel: String,
        val file: File,
        val mtime: Long,
        val size: Long,
    )

    /** 递归列出所有可读表；**PDF 只计数、不登记**。 */
    private fun discover(): List<Found> {
        val out = ArrayList<Found>()
        var pdfs = 0
        roots.forEach { root ->
            walk(root.dir, root.dir, root.label, 0) { f ->
                val name = f.name.lowercase()
                when {
                    name.endsWith(".pdf") -> pdfs++
                    isTableFile(name) -> out += Found(
                        rel = relOf(root.label, root.dir, f),
                        rootLabel = root.label,
                        file = f,
                        mtime = f.lastModified(),
                        size = f.length(),
                    )
                }
            }
        }
        pdfSkipped = pdfs
        return out.sortedBy { it.rel }
    }

    private fun relOf(label: String, rootDir: File, file: File): String {
        val rel = file.absolutePath.removePrefix(rootDir.absolutePath).trimStart('/', '\\')
            .replace('\\', '/')
        return if (label.isEmpty()) rel else "$label/$rel"
    }

    /** 243②：原件区判定——末级目录 `原始`，上一级 `资料库` 或 `library`。 */
    private fun isRawZone(dir: File): Boolean {
        if (dir.name != RAW_SUB) return false
        val parent = dir.parentFile?.name ?: return false
        return parent == "资料库" || parent == "library"
    }


    private fun walk(dir: File, root: File, label: String, depth: Int, sink: (File) -> Unit) {
        if (depth > MAX_DEPTH) return
        val listed = dir.listFiles() ?: return
        for (f in listed.sortedBy { it.name }) {
            if (f.isDirectory) {
                if (f.name in SKIP_DIRS) continue
                // 252 · R1 约束 a：**缓存目录按路径身份硬跳过**，不许被当成表格扫进来。
                // 今天它恰好在 SKIP_DIRS 里的 `抱朴记忆` 下面，所以本来也扫不到——但「恰好扫不到」
                // 不是规格：谁哪天把根指到 `抱朴记忆/表格/`，缓存 CSV 就会变成第二份「表」被问答命中。
                // 这一行才是规格，测试按「根故意指到表格目录」钉它。
                if (f.absolutePath == cacheDir.absolutePath) continue
                if (isRawZone(f)) continue // 243②：原件区不扫
                walk(f, root, label, depth + 1, sink)
            } else if (f.isFile) {
                sink(f)
            }
        }
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    private fun parseFile(hit: Found, now: Long): TableEntry? {
        parseCount++
        val sheets = readSheets(hit.file, hit.rel) ?: return TableEntry(
            rel = hit.rel,
            root = hit.rootLabel,
            mtime = hit.mtime,
            size = hit.size,
            sheets = listOf(
                SheetEntry(
                    name = hit.file.name,
                    headerRow = -1,
                    rows = 0,
                    columns = emptyList(),
                    keys = emptyList(),
                    warnings = emptyList(),
                    // 252：`.xls` 转不动时说**那一种**失败的话（「加密了」「超过 50MB」「截断」），
                    // 不拿「读不动（加密/损坏/不是表格）」这种什么都说了等于什么都没说的句子糊过去。
                    ask = TableHeaderAsk.sentence(
                        xlsFail[hit.rel] ?: "这个文件读不动（加密/损坏/不是表格）。",
                    ),
                ),
            ),
            scannedAt = now,
        )
        val models = sheets.map { (name, grid) ->
            val model = TableModelBuilder.build(hit.rel, name, grid)
            cache["${hit.rel}#$name#-1"] = model
            model
        }
        return TableEntry(
            rel = hit.rel,
            root = hit.rootLabel,
            mtime = hit.mtime,
            size = hit.size,
            sheets = models.map { it.toSheetEntry() },
            scannedAt = now,
            xls = if (XlsCompat.isXls(hit.file.name)) xlsNoticeOf(hit.rel) else null,
        )
    }

    /**
     * 读一个文件的所有 sheet。
     *
     * `.xlsx` 先读一次拿 sheet 名单（第一张顺手读了），其余按名字逐个读；
     * `.csv` / `.tsv` 各只有一张「sheet」，名字就是文件名。
     *
     * 252：`.xls` 走 [readXls]（**先转成 CSV 再进既有通道**），它在 `if` 链的最前面——
     * 这样下面那几行 xlsx/csv 分支**一个字都不用改**（红线：不动既有通道）。
     */
    private fun readSheets(file: File, rel: String): List<Pair<String, SheetGrid>>? {
        if (XlsCompat.isXls(file.name)) return readXls(file, rel)
        val name = file.name.lowercase()
        if (name.endsWith(".tsv")) {
            val grid = runCatching {
                SheetCsv.read(file, range = null, hasHeader = false, maxRows = maxRows, delimiter = '\t')
            }.getOrNull() ?: return null
            return listOf(file.name to grid)
        }
        if (!name.endsWith(".xlsx") && !name.endsWith(".csv")) return null
        val first = SheetReader.read(file, sheet = null, range = null, hasHeader = false, maxRows = maxRows)
            .getOrNull() ?: return null
        val out = ArrayList<Pair<String, SheetGrid>>()
        val names = first.sheets.ifEmpty { listOf(file.name) }
        out += (names.firstOrNull() ?: file.name) to first
        names.drop(1).forEach { n ->
            val g = SheetReader.read(file, sheet = n, range = null, hasHeader = false, maxRows = maxRows)
            g.getOrNull()?.let { out += n to it }
        }
        return out
    }

    /** 252：本次刷新里每张 `.xls` 的转换说明（按 rel 索引；`readXls` 填，`parseFile` 读）。 */
    private val xlsNotices = LinkedHashMap<String, XlsNotice>()

    private fun xlsNoticeOf(rel: String): XlsNotice? = xlsNotices[rel]

    /**
     * 252：`.xls` **不硬读** —— 先用 [XlsCompat] 转成 UTF-8 CSV，再交给既有的 CSV 通道。
     *
     * 三件事在这里定死：
     * - 失败不许静默：每一种失败都翻译成一句能动手的话，落进 [xlsFail] 由 [parseFile] 说出去；
     * - 转换的「行列数 / 余下几张表」记进 [xlsNotices]，回答里那句「已自动转成 CSV」念它；
     * - 之后读的是**缓存里的 CSV**，与用户自己放的 CSV 走的是同一条 [SheetCsv]（不是第二套口径）。
     */
    private fun readXls(file: File, rel: String): List<Pair<String, SheetGrid>>? {
        val done = XlsCompat.convert(file, cacheDir).getOrElse { e ->
            xlsNotices.remove(rel)
            xlsFail[rel] = (e as? XlsFailure)?.action
                ?: "读不动（${e.message?.take(60) ?: e.javaClass.simpleName}）——请重新导出一份完整的 .xls"
            return null
        }
        xlsFail.remove(rel)
        xlsNotices[rel] = XlsNotice(
            source = rel,
            rows = done.rows,
            columns = done.columns,
            extraSheets = (done.sheets.size - 1).coerceAtLeast(0),
        )
        val grid = runCatching {
            SheetCsv.read(done.csv, range = null, hasHeader = false, maxRows = maxRows)
        }.getOrNull()
        if (grid == null) {
            xlsNotices.remove(rel)
            xlsFail[rel] = "转成 CSV 之后还是读不动——请重新导出一份完整的 .xls"
            return null
        }
        return listOf(done.sheet to grid)
    }

    private fun readModel(entry: TableEntry, file: File, sheet: String, override: Int?): TableModel? {
        parseCount++
        // ⚠️ 写成 `if / else if / else { … } ?: return null` 是**错的**——Kotlin 会把 `?:`
        // 吸进 `else if` 那一段的表达式里（else 分支是 statement ⇒ 能吞下 elvis），
        // 结果 `grid` 仍是 `SheetGrid?`，`build` 当场报类型不符。故这里老老实实分两步。
        val maybe: SheetGrid? = if (XlsCompat.isXls(file.name)) {
            readXls(file, entry.rel)?.firstOrNull()?.second
        } else if (file.name.lowercase().endsWith(".tsv")) {
            runCatching {
                SheetCsv.read(file, range = null, hasHeader = false, maxRows = maxRows, delimiter = '\t')
            }.getOrNull()
        } else {
            SheetReader.read(file, sheet = sheet, range = null, hasHeader = false, maxRows = maxRows).getOrNull()
        }
        val grid = maybe ?: return null
        return TableModelBuilder.build(entry.rel, sheet, grid, override)
    }

    private fun fileOf(entry: TableEntry): File? {
        val bare = entry.rel.removePrefix(if (entry.root.isEmpty()) "" else "${entry.root}/")
        roots.firstOrNull { it.label == entry.root }?.let { r -> File(r.dir, bare).takeIf { it.isFile }?.let { return it } }
        return roots.asSequence().map { File(it.dir, bare) }.firstOrNull { it.isFile }
    }

    // ── 落盘 ────────────────────────────────────────────────────────────────

    private fun loadFromDisk() {
        val f = registryFile
        if (!f.isFile) return
        val json = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        if (json.optInt("version", 0) != VERSION) return
        val arr = json.optJSONArray("tables") ?: return
        val loaded = LinkedHashMap<String, TableEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rel = o.optString("rel")
            if (rel.isEmpty()) continue
            loaded[rel] = TableEntry(
                rel = rel,
                root = o.optString("root"),
                mtime = o.optLong("mtime"),
                size = o.optLong("size"),
                sheets = sheetsOf(o.optJSONArray("sheets")),
                scannedAt = o.optLong("scannedAt"),
                xls = xlsOf(o.optJSONObject("xls")),
            )
        }
        entries = loaded
    }


    /**
     * 243③：记住用户指定的表头行（1 起；null＝清掉指定）。
     *
     * 只改登记里的一个字段并落盘，**不重读表**；表内容变了
     * 也不丢用户的指定（mtime/size 变只影响缓存重解析）。
     *
     * @return true＝真的写了（登记里有这张表/sheet）
     */
    fun rememberHeaderRow(rel: String, sheet: String?, row: Int?): Boolean = synchronized(this) {
        val entry = entries[rel] ?: return false
        val target = entry.sheetNamed(sheet) ?: return false
        val updatedSheets = entry.sheets.map { s ->
            if (s.name == target.name) {
                s.copy(userHeaderRow = row, userHeaderMtime = if (row == null) null else entry.mtime)
            } else s
        }
        entries = entries + (rel to entry.copy(sheets = updatedSheets))
        cache.clear()
        persist()
        true
    }

    private fun persist() {
        val tables = JSONArray()
        entries.values.forEach { e ->
            tables.put(
                JSONObject()
                    .put("rel", e.rel)
                    .put("root", e.root)
                    .put("mtime", e.mtime)
                    .put("size", e.size)
                    .put("scannedAt", e.scannedAt)
                    .put("sheets", sheetsJson(e.sheets))
                    // 252：`.xls` 才有的那一段（xlsx/csv/tsv 一律是 NULL，**登记簿不因此变胖**）
                    .put("xls", xlsJson(e.xls)),
            )
        }
        val body = JSONObject().put("version", VERSION).put("tables", tables).toString(2)
        runCatching {
            home.mkdirs()
            registryFile.writeText(body, Charsets.UTF_8)
        }
    }

    /** 252：`.xls` 说明落盘（没有就写 NULL；只有四个标量，不存任何单元格取值）。 */
    private fun xlsJson(x: XlsNotice?): Any = if (x == null) {
        JSONObject.NULL
    } else {
        JSONObject()
            .put("source", x.source)
            .put("rows", x.rows)
            .put("columns", x.columns)
            .put("extraSheets", x.extraSheets)
    }

    private fun xlsOf(o: JSONObject?): XlsNotice? {
        if (o == null) return null
        val src = o.optString("source")
        if (src.isEmpty()) return null
        return XlsNotice(
            source = src,
            rows = o.optInt("rows"),
            columns = o.optInt("columns"),
            extraSheets = o.optInt("extraSheets"),
        )
    }

    private fun sheetsJson(sheets: List<SheetEntry>): JSONArray = JSONArray().also { arr ->
        sheets.forEach { s ->
            arr.put(
                JSONObject()
                    .put("name", s.name)
                    .put("headerRow", s.headerRow)
                    .put("rows", s.rows)
                    .put("keys", JSONArray(s.keys))
                    .put("warnings", JSONArray(s.warnings))
                    .put("ask", s.ask ?: JSONObject.NULL)
                    .put("userHeaderRow", s.userHeaderRow ?: JSONObject.NULL)
                    .put("userHeaderMtime", s.userHeaderMtime ?: JSONObject.NULL)
                    .put(
                        "columns",
                        JSONArray().also { cols ->
                            s.columns.forEach {
                                cols.put(JSONObject().put("name", it.name).put("type", it.type).put("key", it.key))
                            }
                        },
                    ),
            )
        }
    }

    private fun sheetsOf(arr: JSONArray?): List<SheetEntry> {
        if (arr == null) return emptyList()
        val out = ArrayList<SheetEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cols = ArrayList<EntryColumn>()
            o.optJSONArray("columns")?.let { ca ->
                for (j in 0 until ca.length()) {
                    val c = ca.optJSONObject(j) ?: continue
                    cols += EntryColumn(c.optString("name"), c.optString("type"), c.optBoolean("key"))
                }
            }
            out += SheetEntry(
                name = o.optString("name"),
                headerRow = o.optInt("headerRow", -1),
                rows = o.optInt("rows", 0),
                columns = cols,
                keys = strings(o.optJSONArray("keys")),
                warnings = strings(o.optJSONArray("warnings")),
                ask = if (o.isNull("ask")) null else o.optString("ask"),
                userHeaderRow = if (o.isNull("userHeaderRow")) null else o.optInt("userHeaderRow"),
                userHeaderMtime = if (o.isNull("userHeaderMtime")) null else o.optLong("userHeaderMtime"),
            )
        }
        return out
    }

    private fun strings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "registry.json"

        /** 登记簿所在目录名（`<工作区>/抱朴记忆/表格/`）。 */
        const val DIR_MEMORY = "抱朴记忆"
        const val DIR_TABLES = "表格"

        /** 最多往下走几层（防病态目录树）。 */
        const val MAX_DEPTH = 4

        /** 不进的目录：App 自己的备份/回收站/记忆，以及构建产物。 */
        val SKIP_DIRS: Set<String> = setOf("备份", ".回收站", DIR_MEMORY, ".git", "build", "node_modules", ".gradle")

        /** 登记簿的默认落点（`<工作区>/抱朴记忆/表格/registry.json`）。 */
        fun homeUnder(workspace: File): File = File(File(workspace, DIR_MEMORY), DIR_TABLES)

        /**
         * 认这四种后缀；**`.pdf` 不认**（派工单红线：不扫 PDF）。
         *
         * 252：`.xls`（BIFF）也进候选——不扫它就等于 F9 导出的表在抱朴里**根本不存在**。
         * 但它在登记簿里**不与 `.xlsx` 同级**：[TableEntry.xls] 非 null 就是它的记号，
         * 意思是「这张表是先转成 CSV 才进来的」（派工单 §1①）。
         */
        fun isTableFile(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".xlsx") || n.endsWith(".csv") || n.endsWith(".tsv") ||
                n.endsWith(".xls")
        }

        /** 是不是 PDF（唯一一处判据：工具拒绝与登记跳过都问它）。 */
        fun isPdf(name: String): Boolean = name.lowercase().endsWith(".pdf")
    }
}

/** 模型 → 登记（只留元数据；**单元格取值一个字都不进 registry**）。 */
internal fun TableModel.toSheetEntry(): SheetEntry = SheetEntry(
    name = sheet,
    headerRow = headerRow,
    rows = rows.size,
    columns = columns.map { EntryColumn(it.name, it.type.name, it.keyCandidate) },
    keys = keys,
    warnings = warnings,
    ask = headerAsk,
)
