// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：492 工单 B 段①「在既有 SearchDb 上加一层『给模型读的摘要』：标题/前 N 行/结构块名/
// 文件:行」。本文件只有**纯函数与常量**（无 Android、无 IO、无 SQL——SQL 在 [SearchSql]）：
// 一篇文件的正文文本怎么折成「给模型读的摘要」，判据全部住在这里，JVM 直测；
// [PhoneSearch.readDoc]（切词的同一处）调用它，[PhoneIndex.putShard] 把结果落库。
//
// ## 为什么摘要跟着正文走（不另立第二遍读盘）
// 工单 B③「增量更新沿用既有 Scan 的 mtime；**不许新写扫描器**」——摘要的原料就是
// `readDoc` 已经读到手的 `body.text`（同一次读、同一个 mtime 账），扫描器没有第二套；
// 文件变了 ⇒ 既有 Scan 的 size+mtime 判它重索引 ⇒ 重索引时摘要跟着重写（同一个事务）；
// 文件没了 ⇒ `doc_summary.doc_id` 挂着 `ON DELETE CASCADE`，跟 doc 行一起走。
package com.example.zhengdao.baopu.search

/**
 * 「给模型读的摘要」的折叠判据（**纯函数**：text 进，两段字符串出）。
 *
 * 摘要只存两段文本（库形状见 [SearchSql.TABLE_DOC_SUMMARY]）：
 * - `firstLines`：前 [FIRST_LINES] 行（每行截到 [MAX_LINE_CHARS]；尾部空行丢掉）；
 * - `blocks`：结构块名——Markdown ATX 标题（`#`..`######`）带 1 起的行号，
 *   每条 `行号\t块名`，至多 [MAX_BLOCKS] 条；`文件:行` 那半在**渲染层**
 *   （[IndexBrowse]）拼，库里只存行号与块名本体。
 *
 * ## 规模上限（工单「规模上限写死，别把『读得到』做成全盘扫描」）
 * [MAX_ROWS] 是 `doc_summary` 的**行数硬顶**（写死、无开关）：摘要只给 HOME 档根
 * （即多根工作区清单里的根）建，全盘档（PHONE）一篇都不建；顶满后新文件不再建摘要，
 * 由 [IndexBrowse] 的状态行标「部分索引 N/M」——超限**必须可见**，不许静默假装全量。
 */
internal object DocSummaryRules {

    /** 摘要带的正文行数（「前 N 行」的 N；12 行 ≈ 一屏眼_footer，再多该用 read_file 分段读）。 */
    const val FIRST_LINES = 12

    /** 单行在摘要里的截断宽度（一个 2000 字的无换行段落不该把摘要撑爆）。 */
    const val MAX_LINE_CHARS = 160

    /** 结构块条数上限（一篇文件 32 个标题足够「看懂结构」，再多就该分段读了）。 */
    const val MAX_BLOCKS = 32

    /** `doc_summary` 行数硬顶（工单「规模上限写死」；与 [SearchSql] 的 DDL 无关，纯判据）。 */
    const val MAX_ROWS = 2000

    /** `blocks` 里行号与块名的分隔（块名里的制表符编码时替换掉，解码才无歧义）。 */
    private const val BLOCK_SEP = "\t"

    /** 折叠一篇正文（**纯函数**）：标题不在这里存——`doc.title` 已有，摘要层不抄第二份。 */
    fun of(text: String): SummaryText {
        val lines = text.lines()
        val head = lines.take(FIRST_LINES)
            .map { it.trim().take(MAX_LINE_CHARS) }
            .dropLastWhile { it.isEmpty() }
            .joinToString("\n")
        val blocks = lines.asSequence()
            .mapIndexed { i, line -> (i + 1) to line }
            .filter { (_, line) -> isHeading(line) }
            .take(MAX_BLOCKS)
            .joinToString("\n") { (no, line) ->
                "$no$BLOCK_SEP${line.trim().replace(BLOCK_SEP, " ").take(MAX_LINE_CHARS)}"
            }
        return SummaryText(firstLines = head, blocks = blocks)
    }

    /** ATX 标题判定（**纯函数**）：1..6 个 `#` 后跟空格（或行尾）——`#######` 与 `#标签` 不是。 */
    fun isHeading(line: String): Boolean {
        val t = line.trimStart()
        if (!t.startsWith("#")) return false
        val hashes = t.takeWhile { it == '#' }
        if (hashes.length !in 1..6) return false
        val rest = t.drop(hashes.length)
        return rest.isEmpty() || rest.startsWith(" ")
    }

    /** `first_lines` 列的解码（渲染层用；空串＝没有行，不是一行空行）。 */
    fun firstLineList(firstLines: String): List<String> =
        if (firstLines.isEmpty()) emptyList() else firstLines.split("\n")

    /** `blocks` 列的解码（渲染层用；`行号\t块名` → (行号, 块名)。坏行丢弃，不猜）。 */
    fun blockList(blocks: String): List<Pair<Int, String>> = blocks.split("\n")
        .filter { it.isNotEmpty() }
        .mapNotNull { row ->
            val at = row.indexOf(BLOCK_SEP)
            val no = if (at <= 0) null else row.substring(0, at).toIntOrNull()
            if (no == null || at + 1 > row.lastIndex) null else no to row.substring(at + 1)
        }

    /** 一篇的摘要（两段编码文本；形状照 `doc_summary` 的两列）。 */
    data class SummaryText(val firstLines: String, val blocks: String)
}
