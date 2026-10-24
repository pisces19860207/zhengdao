// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu
import java.io.File

/**
 * 第一百七十九批 · 长文/微头条输出卡的**纯判据**（触发／标题／文件名／纯文本化）。
 *
 * 图纸：`_zd-private/docs/派工单-2026-10-24-第一百七十九批-长文输出卡.md` §1。
 * 全部 JVM 可测；卡片与落库只做装配。
 *
 * 红线对照：
 * - 「普通短回复不强制变卡」⇒ 触发条件全部落在 [isLongform]；
 * - 「保存平铺、不加 文本/ 第三层」⇒ [artifactFileName] 只给文件名，目录由仓库定死为 `产物/`；
 * - 「不覆盖」⇒ [nextFileName] 同名加 -2/-3。
 */
internal object BaopuLongformPolicy {

    /** 用户明确点名（说这些词就算要长文，多短都给卡）。 */
    private val USER_KEYWORDS = Regex("微头条|文章|文案|长文")

    /** 标题行（markdown 一级/二级标题）。 */
    private val HEADING = Regex("(?m)^#{1,2}\\s+(.+)$")

    /** 列表项（- 或 1. ）。 */
    private val LIST_ITEM = Regex("(?m)^(?:-|\\d+[.、])\\s+")

    /** 字数阈值：超过就值得一张卡（普通一两段聊天到不了）。 */
    const val LENGTH_THRESHOLD = 600

    /** 段落数阈值。 */
    const val PARAGRAPH_THRESHOLD = 3

    /** 文件名里 slug 的最大长度（字符）。 */
    const val SLUG_MAX = 20

    // ── 触发 ──────────────────────────────────────────────────────────────

    /**
     * 这条回复要不要渲染成输出卡。
     *
     * 四条独立通道，命中任一即卡（图纸 §1）：
     * 1. **用户点名**：这轮用户输入里有 微头条/文章/文案/长文；
     * 2. **篇幅**：非空白字符 ≥ [LENGTH_THRESHOLD]；
     * 3. **段落数** ≥ [PARAGRAPH_THRESHOLD]（多段是"结构化输出"最朴素的信号）；
     * 4. **markdown 结构**：有标题行，或列表项 ≥ 3。
     */
    fun isLongform(text: String, userText: String = ""): Boolean {
        if (text.isBlank()) return false
        if (USER_KEYWORDS.containsMatchIn(userText)) return true
        val chars = wordCount(text)
        if (chars >= LENGTH_THRESHOLD) return true
        val paras = text.split(Regex("\\n\\s*\\n")).count { it.isNotBlank() }
        if (paras >= PARAGRAPH_THRESHOLD) return true
        if (HEADING.containsMatchIn(text)) return true
        return LIST_ITEM.findAll(text).count() >= 3
    }

    /** 字数＝非空白字符数（中文场景比"词数"直觉）。 */
    fun wordCount(text: String): Int = text.count { !it.isWhitespace() }

    // ── 标题 ──────────────────────────────────────────────────────────────

    /** 卡头标题：优先 markdown 标题行；否则取第一个非空行；截 24 字。 */
    fun cardTitle(text: String): String {
        val heading = HEADING.find(text)?.groupValues?.get(1)?.trim()
        val first = heading
            ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: return "无标题"
        val clean = first.removePrefix("#").trim()
        return if (clean.length > 24) clean.take(24) + "…" else clean.ifEmpty { "无标题" }
    }

    // ── 文件名 ────────────────────────────────────────────────────────────

    /** 文件名 slug：中文/字母/数字保留，空格变 -，Windows 非法字符与换行剔除，压成一段。 */
    fun slug(title: String): String {
        val cleaned = title
            .replace(Regex("[/\\\\:*?\"<>|\\r\\n\\t]"), "")
            .replace(' ', '-')
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .take(SLUG_MAX)
            .trim('-', '.')
        return cleaned.ifEmpty { "无标题" }
    }

    /**
     * 产物文件名：`微头条-<yyyyMMdd-HHmm>-<slug>.<ext>`（平铺在 `产物/`，**不加第三层**）。
     *
     * @param y/M/D/h/min 本地时间字段（调用方给，方便 JVM 测试固定时钟）
     */
    fun artifactFileName(y: Int, m: Int, d: Int, h: Int, min: Int, title: String, ext: String): String =
        "微头条-%04d%02d%02d-%02d%02d-%s.%s".format(y, m, d, h, min, slug(title), ext)

    /**
     * 同名不覆盖（与图片产物同一条规则）：`base` 被占用就 `stem-2.ext`、`stem-3.ext`……
     * 后缀加在**扩展名前**且用下划线（`a_2.md`——派工单原文「重名加 _2」；单测抓过 a.md-2 的错）。
     * @param existing 目录里现有文件名集合
     */
    fun nextFileName(existing: Set<String>, base: String): String {
        if (base !in existing) return base
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val ext = if (dot > 0) base.substring(dot) else ""
        var n = 2
        while ("${stem}_$n$ext" in existing) n++
        return "${stem}_$n$ext"
    }

    // ── 纯文本视图 ────────────────────────────────────────────────────────

    /**
     * Markdown → 纯文本（「纯文本」视图用）。
     * 这是**展示变换**，不是 markdown 解析器（解析渲染归 mikepenz 渲染器）：
     * 只剥标记符号，正文文字一个字不动。
     */
    fun toPlainText(md: String): String {
        var s = md
        s = HEADING.replace(s) { m -> m.groupValues[1] }
        s = Regex("\\*\\*([^*]+)\\*\\*").replace(s, "$1")
        s = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)").replace(s, "$1")
        s = Regex("`([^`]+)`").replace(s, "$1")
        s = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(s, "$1")
        s = LIST_ITEM.replace(s, "")
        s = Regex("\\n{3,}").replace(s, "\n\n")
        return s.trim()
    }

    // ── 179 §0.2/§0.3（20:4x 拍板后的落点与台账） ────────────────────────

    /** 落点：知识库根下的产物目录（平铺，**不加第三层**；外置根只是换 [ROOT_MARKER] 指向）。 */
    const val PRODUCT_DIR = "产物"

    /** 外置根的可选覆盖文件：一行绝对路径（SAF 选库后写入；v1 先用文件缝，后换 SAF）。 */
    const val ROOT_MARKER = "外置根.txt"

    /** 外置根的台账约定名（存在就复用，**绝不新建第二份**）。 */
    const val EXTERNAL_LEDGER = "已发台账_勿删.md"

    /**
     * 产物文件名：`YYYYMMDD_<赛道>_<序号>_<标题slug>.<ext>`（派工单 §0.2）。
     * 序号＝该赛道当日已有文件数 + 1；重名不覆盖，由调用方走 [nextFileName] 加 `_2`。
     */
    fun trackFileName(y: Int, m: Int, d: Int, track: String, seq: Int, title: String, ext: String): String =
        "%04d%02d%02d_%s_%d_%s.%s".format(y, m, d, slug(track), seq.coerceAtLeast(1), slug(title), ext)

    /** 序号＝同赛道当日已有文件数 + 1（`YYYYMMDD_<赛道>_<N>_` 前缀计数）。 */
    fun nextSeq(existingNames: Set<String>, y: Int, m: Int, d: Int, track: String): Int {
        val prefix = "%04d%02d%02d_%s_".format(y, m, d, slug(track))
        val max = existingNames.filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).substringBefore('_').toIntOrNull() }
            .maxOrNull() ?: 0
        return max + 1
    }

    /**
     * 台账文件选择：外置根下有 [EXTERNAL_LEDGER] ⇒ **复用它**；
     * 否则默认 `<根>/产物/台账.md`（不存在由调用方按「总览＋逐条 ☐/✔」建）。
     */
    fun pickLedger(root: File, productDir: File): File {
        val external = File(root, EXTERNAL_LEDGER)
        if (external.isFile) return external
        val inProduct = File(productDir, EXTERNAL_LEDGER)
        if (inProduct.isFile) return inProduct
        return File(productDir, "台账.md")
    }

    /** 台账新建时的头（总览段）；append-only：只在**建文件**时写这一段。 */
    fun ledgerHeader(): List<String> = listOf(
        "# 产物台账",
        "",
        "## 总览",
        "",
        "只追加不改旧行；打勾＝把 ☐ 改 ✔ 并补日期。",
        "",
    )

    /** 一个批次段（追加到台账末尾）：日期标题 + 逐条 ☐（含字数）。 */
    fun ledgerSection(y: Int, m: Int, d: Int, h: Int, min: Int, files: List<Pair<String, Int>>): List<String> =
        listOf("## %04d-%02d-%02d 批次（%02d:%02d 落盘）".format(y, m, d, h, min), "") +
            files.map { (name, chars) -> "- ☐ $name（$chars 字）" }

    /**
     * append 合并：老内容**逐字保留**，段接在末尾（空行分隔）。台账红线（§0.3 硬判据）。
     */
    fun mergeAppend(oldText: String, section: List<String>): List<String> {
        val old = oldText.split('\n').map { it.trimEnd('\r') }
        val trimmed = old.dropLastWhile { it.isBlank() }
        return trimmed + "" + section
    }

    /**
     * 硬判据：新内容 = 老内容逐字未变 + 只多末尾几行。返回 true = 通过。
     * 负例：整表重写 / 行序变 / 丢行 ⇒ false。
     */
    fun verifyAppend(oldText: String, newText: String): Boolean {
        val old = oldText.split('\n').map { it.trimEnd('\r') }.dropLastWhile { it.isBlank() }
        val new = newText.split('\n').map { it.trimEnd('\r') }
        if (new.size < old.size) return false
        return old.indices.all { new[it] == old[it] }
    }

}
