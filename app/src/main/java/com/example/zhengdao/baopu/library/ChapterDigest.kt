// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴线刀 2 追加包（章节级抽取流水线 · 摘要与落库）。
// 两条硬纪律：
// 1. **不跑模型、零网络**——摘要是确定性抽取式（标题 + 章字数 + 首句截断）。
//    追加包明令「不额外多跑一轮模型」；抽取式摘要永远可用、永远同样输入同样输出，
//    索引因此可重复生成（同章同 sha256 幂等）；
// 2. 单次最多 MAX_CHAPTERS_PER_RUN = 20 章，超了停下并把剩余列出来——
//    不静默截断（与 Guard.kt / list_dir 同款原则）。
package com.example.zhengdao.baopu.library

import com.example.zhengdao.baopu.BaopuSql

/** 章节摘要行（chapter_index 表的一行的内存形状）。 */
internal data class ChapterDigestEntry(
    val book: String,
    val chapterNo: Int,
    val title: String,
    val sha256: String,
    val digest: String,
    val chars: Int,
    val createdAt: Long,
)

/**
 * 一轮抽取的结果。
 *
 * @param entries 本轮真正要落库的行（≤ [ChapterDigest.MAX_CHAPTERS_PER_RUN] 条）
 * @param remaining 触顶没处理的章数（UI 原话级提示「还剩 N 章，可再跑一轮」）
 * @param total 书的总章数（UI 显示「共 M 章」）
 * @param skipped 精读模式下**故意**没选的章数（不算 remaining——那是用户的选择）
 */
internal data class DigestRun(
    val entries: List<ChapterDigestEntry>,
    val remaining: Int,
    val total: Int,
    val skipped: Int,
)

/**
 * 逐章摘要 + 落库语句（刀 2 追加包）。
 *
 * 幂等判据（追加包判据②「同章重复跑不产生重复行」）落在 SQL 上：
 * `UNIQUE(book, chapter_no, sha256)` + `INSERT OR IGNORE` —— 同一本书的同一章、
 * 章文本没变 ⇒ 重复跑不产生新行；章文本变了 ⇒ 新行，取最新按 `MAX(id)`
 * （见 [Sql.latestChapters]）。
 *
 * ⚠️ 本仓无 Robolectric ⇒ SQLiteOpenHelper 在 JVM 里实例化不了，**行为层
 * （真跑 SQL 验幂等）没法在本机测**；这里把 SQL 文本层钉死（单测断言
 * OR IGNORE / UNIQUE 三元组 / 确定性），与刀 0b 的 DB 测试同款口径。
 */
internal object ChapterDigest {

    /** 单次最多处理章数（追加包原值）。 */
    const val MAX_CHAPTERS_PER_RUN = 20

    /** 「只要索引」模式下的摘要占位（行仍在，标明没跑摘要）。 */
    const val INDEX_ONLY_DIGEST = "（仅索引：未生成摘要）"

    /** 空章摘要占位。 */
    private const val EMPTY_CHAPTER_DIGEST = "（空章）"

    /**
     * 一轮抽取。
     *
     * @param bookName   书名（索引的分组键；一般用文件名去扩展名）
     * @param chapters   [ChapterSplitter.split] 的输出
     * @param lines      同一份文本按行切开（与切章时同一份，保证行号对得上）
     * @param now        落库时间戳（注入以便单测确定性）
     * @param withDigest true = 逐章生成摘要；false = 只要索引（digest 占位）
     * @param selected   null = 全部章；非 null = 只处理这些章号（精读某几章）
     */
    fun build(
        bookName: String,
        chapters: List<Chapter>,
        lines: List<String>,
        now: Long,
        withDigest: Boolean = true,
        selected: Set<Int>? = null,
    ): DigestRun {
        if (chapters.isEmpty()) return DigestRun(emptyList(), 0, 0, 0)
        val candidates = if (selected == null) chapters else chapters.filter { it.index in selected }
        val skipped = chapters.size - candidates.size
        val taken = candidates.take(MAX_CHAPTERS_PER_RUN)
        val remaining = candidates.size - taken.size
        val entries = taken.map { ch ->
            val text = ch.textOf(lines)
            ChapterDigestEntry(
                book = bookName,
                chapterNo = ch.index,
                title = ch.title,
                sha256 = FaceIndex.sha256Bytes(text.toByteArray(Charsets.UTF_8)),
                digest = if (withDigest) digestOf(ch.title, text) else INDEX_ONLY_DIGEST,
                chars = text.length,
                createdAt = now,
            )
        }
        return DigestRun(entries, remaining, chapters.size, skipped)
    }

    /**
     * 单章摘要（**确定性**：同输入永远同字节——索引要能重复生成）。
     *
     * 形状：`标题｜N 字｜开头：首句（≤80 字）…`。为什么只要首句：
     * 抽取式摘要的价值是「这章在不在、讲什么开头」，不是替代阅读；
     * 真概括留给用户或后续带模型的刀，本刀不越界。
     *
     * 两条细节（2026-10-17 单测逼出来的）：
     * 1. **首句跳过标题行本身**——否则「开头」永远是标题的复读
     *    （`第一章 甲｜9 字｜开头：第一章 甲` 等于什么都没说）；
     * 2. 除掉标题行外**全是空白** ⇒ `（空章）`（ marker-only 的章也是空章，
     *    不能让「开头」拿标题行凑数）。
     */
    fun digestOf(title: String, chapterText: String): String {
        val nonBlank = chapterText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        val body = nonBlank.filter { it != title }.toList()
        if (body.isEmpty()) return EMPTY_CHAPTER_DIGEST
        val head = body.first()
        val shown = if (head.length <= 80) head else head.take(79) + "…"
        return "$title｜${chapterText.length} 字｜开头：$shown"
    }

    /**
     * 落库语句层（纯 SQL 文本 + 绑定参数；壳层在 [ChapterIndexStore]）。
     *
     * `INSERT OR IGNORE` 的 IGNORE 分支正是幂等判据：三元组撞上 ⇒ 什么都不做。
     */
    internal object Sql {

        fun insertOrIgnore(e: ChapterDigestEntry): BaopuSql.Stmt = BaopuSql.Stmt(
            "INSERT OR IGNORE INTO chapter_index " +
                "(book, chapter_no, title, sha256, digest, chars, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                e.book,
                e.chapterNo.toString(),
                e.title,
                e.sha256,
                e.digest,
                e.chars.toString(),
                e.createdAt.toString(),
            ),
        )

        /**
         * 每章取最新一行（`MAX(id)`）——章文本改过的书不会被旧摘要挡住。
         * 子查询先按章分组取最大 id，再连回原表拿整行。
         */
        fun latestChapters(book: String): BaopuSql.Stmt = BaopuSql.Stmt(
            "SELECT c.chapter_no, c.title, c.sha256, c.digest, c.chars, c.created_at " +
                "FROM chapter_index c JOIN (" +
                "  SELECT chapter_no, MAX(id) AS mid FROM chapter_index WHERE book = ? " +
                "  GROUP BY chapter_no" +
                ") m ON c.chapter_no = m.chapter_no AND c.id = m.mid " +
                "WHERE c.book = ? ORDER BY c.chapter_no",
            arrayOf(book, book),
        )

        /** 某书已入索引的章数（去重后；UI 显示「已索引 N 章」）。 */
        fun indexedChapterCount(book: String): BaopuSql.Stmt = BaopuSql.Stmt(
            "SELECT COUNT(DISTINCT chapter_no) FROM chapter_index WHERE book = ?",
            arrayOf(book),
        )
    }
}

/**
 * chapter_index 的薄壳（DB 句柄注入；Context-free 内核在 [ChapterDigest.Sql]）。
 *
 * 与 [LibraryStore] 同款房型：SQL 文本层可 JVM 直测，壳层只负责把 Stmt
 * 喂给 SQLiteDatabase（本仓无 Robolectric，壳层行为归真机切片）。
 */
internal class ChapterIndexStore(private val db: com.example.zhengdao.baopu.BaopuDb) {

    /** 落一轮（逐条 INSERT OR IGNORE；返回实际插入行数由调用方按需再查）。 */
    fun insertAll(entries: List<ChapterDigestEntry>) {
        if (entries.isEmpty()) return
        db.write { d ->
            entries.forEach { e ->
                val stmt = ChapterDigest.Sql.insertOrIgnore(e)
                d.execSQL(stmt.sql, stmt.args)
            }
        }.get()
    }

    /** 某书每章最新一行（UI「已索引」列表）。 */
    fun latest(book: String): List<ChapterDigestEntry> = db.read { d ->
        val stmt = ChapterDigest.Sql.latestChapters(book)
        d.rawQuery(stmt.sql, stmt.args).use { c ->
            val out = mutableListOf<ChapterDigestEntry>()
            while (c.moveToNext()) {
                out += ChapterDigestEntry(
                    book = book,
                    chapterNo = c.getInt(0),
                    title = c.getString(1),
                    sha256 = c.getString(2),
                    digest = c.getString(3),
                    chars = c.getInt(4),
                    createdAt = c.getLong(5),
                )
            }
            out
        }
    }

    /**
     * 某书已入索引的章数。
     *
     * ⚠️ 待删（第二十七批清单，2026-10-18）：`git grep -w indexedCount` 只命中本处定义。
     * 同族的 [insertAll] 活着（`ui/baopu/BaopuExtractSheet.kt:113` 抽完就落库），
     * 但"读回来"这条路（本函数与 [latest]）没有任何调用者——抽取页显示结果用的是
     * 内存里刚生成的 `entries`，不查库。
     * 删除需一并处理 [Sql.indexedChapterCount]（只被本函数引用，删了它就是死 SQL）。
     * **若将来要做"这本书抽过 N 章"的提示**，应改用它而不是再写一条 SQL——本条留作提醒。
     */
    fun indexedCount(book: String): Int = db.read { d ->
        val stmt = ChapterDigest.Sql.indexedChapterCount(book)
        d.rawQuery(stmt.sql, stmt.args).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
