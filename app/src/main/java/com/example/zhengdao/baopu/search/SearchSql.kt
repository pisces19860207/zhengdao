// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-24-第一百九十七批-读侧授权阶梯 §1①②（读侧授予账本落**新库**
// `filesDir/baopu/search.db`，四张普通表 `scope / doc / tok / watermark`，形状照设计 §6.2）；
// 设计 §6.1 方案 B ＋ §12.2 真机探针（系统 SQLite **没编 FTS5**，3.44.5 也搜不到 fts5 符号）
// ⇒ 倒排必须是**普通表**，不可能是虚拟表。
//
// 为什么不并进 `BaopuSql`（三条都是实测过的理由，不是偏好）：
// 1. `BaopuDb.kt` 由 172-L4 在飞占着，派工单「不许碰」点名 BaopuDb 版本号——本批一行不动它；
// 2. 房规先例：`WorkspaceGrantSql`（WorkspaceGrant.kt:133）把 SQL 常量放在业务文件里，
//    理由是裸线程对账单把 `zd-baopu-db` 的创建点钉死在 `BaopuDb.kt` 行号上，往里加行会把
//    全仓几十条对账单一起平移；本库是**另一个 db 文件**，连那个线程都不该有；
// 3. 更要紧的是判据：搜索表混进 `BaopuSql` 会长出「升 BaopuDb 版本才会建搜索表」这种错话——
//    `search.db` 缺了就重建（§6.4「索引永远可整包丢弃重建」），跟 App 主库的版本演进无关。
//
// 为什么全是纯文本、不落在 `SQLiteDatabase` 上：本仓没有 Robolectric，`android.jar` 是 stub
// ⇒ JVM 里 SQLite 根本跑不起来。所以「表长什么样、每条语句绑哪些参数、撤销先删谁」
// 必须由字符串测试钉死（房型同 `BaopuDbTest`／`DiaryDbGateTest`），薄壳 [SearchDb] 只管执行。
package com.example.zhengdao.baopu.search

/**
 * `search.db` 的建表语句与全部 SQL 常量（**只有字符串**，没有 Android 依赖，JVM 直测）。
 *
 * ## 四张表（派工单 §1② 点名的形状）
 * - `scope`：读侧授予账本——哪个绝对根、属于哪一档（HOME/PHONE）、什么时候批的、有没有撤。
 * - `doc`：一篇文件一行（路径/大小/时间/标题/头哈希/状态），S3 的扫描器写它。
 * - `tok`：倒排——`token → doc`，token 来自 `MemoryRules.tokensOf`（与记忆检索同一个算式）。
 * - `watermark`：每个根一行的水位与对账（扫过多少、跳过多少、读了多少字节）。
 *
 * ## 两处自作主张（报告 §3 逐条说明，都有理由）
 * 1. `doc` 用 `doc_id INTEGER PRIMARY KEY AUTOINCREMENT` ＋ `UNIQUE(root_id, rel_path)`，
 *    而不是字面的 `PRIMARY KEY(root_id, rel_path)`：设计 §6.2 自己写的下一张表是
 *    `tok(token, doc_id, cnt)`——复合主键没有单列可引，`doc_id` 必须是 rowid 别名。
 *    选 `AUTOINCREMENT`：rowid 会被复用（`BaopuDb.kt` v1 的 `session` 表也是这个理由），
 *    一个旧文件的 tok 行挂到新文件身上＝搜索结果**看着对其实错**，这种错不响。
 * 2. `tok` 不另建 `INDEX(tok.token)`：`PRIMARY KEY(token, doc_id)` 自带的那棵索引
 *    最左列就是 token，查询走它；再建一棵是**同一份数据的第二份拷贝**，只多付写入。
 *    `SearchSqlTest` 用「tok 上的索引恰好一棵、最左列是 token」把这条钉住。
 */
internal object SearchSql {

    const val TABLE_SCOPE = "scope"
    const val TABLE_DOC = "doc"
    const val TABLE_TOK = "tok"
    const val TABLE_WATERMARK = "watermark"

    /** 本批允许出现的表名全集（隐私门禁：任何写语句只能碰这四张表）。 */
    val TABLES: Set<String> = setOf(TABLE_SCOPE, TABLE_DOC, TABLE_TOK, TABLE_WATERMARK)

    /** 外键每个连接都要开（PRAGMA 不持久化）——`scope → doc → tok` 的级联删除靠它。 */
    const val PRAGMA_FK_ON = "PRAGMA foreign_keys = ON"

    /** `ON CONFLICT … DO UPDATE` 需要 SQLite 3.24+；真机 PGT-AN10 实测系统 SQLite 3.44.5（设计 §12.2）。 */
    val DDL: List<String> = listOf(
        // ── 1. scope：读侧授予账本 ──────────────────────────────────────────
        // abs_root 上是 UNIQUE：「同一个物理根只能有一条授予」是库级约束，不靠调用方自觉。
        // kind 的 CHECK 照设计 §6.2 的三档字面量；本批（S2）只写 HOME 与 PHONE——
        // MEDIA 是乙档（S4 之后），它进 CHECK 是形状照单，不是「已经支持」。
        // 门禁 `SearchScopeTest.负例_没授权或没开关_绝不落手机档` 盯着「凭空记一条没拿到的授予」。
        """
        CREATE TABLE IF NOT EXISTS scope(
          root_id INTEGER PRIMARY KEY AUTOINCREMENT,
          abs_root TEXT NOT NULL UNIQUE,
          kind TEXT NOT NULL CHECK (kind IN ('HOME', 'PHONE', 'MEDIA')),
          granted_at INTEGER NOT NULL,
          revoked_at INTEGER
        )
        """.trimIndent(),
        // 账本的用途是「现在哪些根还有效」，每次扫描都查它 ⇒ 不给 revoked_at 建索引就是全表扫。
        "CREATE INDEX IF NOT EXISTS idx_scope_active ON scope (revoked_at)",
        // ── 2. doc：一篇文件一行 ────────────────────────────────────────────
        // status 的字面量照设计 §6.2（OK/TOO_BIG/ENCRYPTED/JUNK/PRUNED）＋ §6.3 补的 UNCLAIMED。
        // 「截断与不认领必须可见」（§6.2 原话）⇒ 不认领的**建行**、被排除的**不建行**，
        // 分界线在 SearchAdmission：排除清单是「进不了扫描器」，不是「进来了但标成垃圾」。
        """
        CREATE TABLE IF NOT EXISTS doc(
          doc_id INTEGER PRIMARY KEY AUTOINCREMENT,
          root_id INTEGER NOT NULL REFERENCES scope(root_id) ON DELETE CASCADE,
          rel_path TEXT NOT NULL,
          kind TEXT NOT NULL,
          size INTEGER NOT NULL,
          mtime INTEGER NOT NULL,
          title TEXT NOT NULL DEFAULT '',
          sha256_head TEXT NOT NULL DEFAULT '',
          indexed_at INTEGER NOT NULL,
          status TEXT NOT NULL CHECK (status IN ('OK', 'TOO_BIG', 'ENCRYPTED', 'JUNK', 'PRUNED', 'UNCLAIMED')),
          UNIQUE(root_id, rel_path)
        )
        """.trimIndent(),
        // 增量（§6.4）与撤销都要按根翻一遍 ⇒ root_id 是最左列的那棵索引服务的查询。
        "CREATE INDEX IF NOT EXISTS idx_doc_root_rel ON doc (root_id, rel_path)",
        // ── 3. tok：倒排（普通表） ──────────────────────────────────────────
        // PRIMARY KEY(token, doc_id)：一个词在同一篇里只有一行计数，重复入库是刷新不是追加。
        """
        CREATE TABLE IF NOT EXISTS tok(
          token TEXT NOT NULL,
          doc_id INTEGER NOT NULL REFERENCES doc(doc_id) ON DELETE CASCADE,
          cnt INTEGER NOT NULL,
          PRIMARY KEY(token, doc_id)
        )
        """.trimIndent(),
        // ── 4. watermark：每根一行的水位 ────────────────────────────────────
        // root_id 直接当主键（一个根只有水位这一行）；数值全部 NOT NULL DEFAULT 0，
        // 读侧不用处理 null——「扫过多少」是给人看的话，缺列等于说「一次都没扫」，不该由 null 表达。
        """
        CREATE TABLE IF NOT EXISTS watermark(
          root_id INTEGER PRIMARY KEY REFERENCES scope(root_id) ON DELETE CASCADE,
          last_scan_at INTEGER NOT NULL DEFAULT 0,
          last_full_at INTEGER NOT NULL DEFAULT 0,
          files_seen INTEGER NOT NULL DEFAULT 0,
          files_skipped INTEGER NOT NULL DEFAULT 0,
          bytes_read INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
    )

    // ── scope：授予账本 ──────────────────────────────────────────────────────

    /**
     * 记一条授予（幂等）。冲突走 `DO UPDATE` 而不是 `INSERT OR REPLACE`：后者在 UNIQUE 上冲突时
     * 是「删旧行＋插新行」，删旧行会顺着 `ON DELETE CASCADE` 把这一根的 `doc`/`tok` 全清掉——
     * 「重新授权一次，索引白建了」这种事不该由一句 UPSERT 顺手做掉。
     */
    const val UPSERT_SCOPE =
        "INSERT INTO scope(abs_root, kind, granted_at, revoked_at) VALUES(?, ?, ?, NULL) " +
            "ON CONFLICT(abs_root) DO UPDATE SET kind = excluded.kind, " +
            "granted_at = excluded.granted_at, revoked_at = NULL"

    /** 账本清单（**含已撤销的**：撤销是历史，历史要能翻给人看；界面自己按 `revoked_at` 过滤）。 */
    const val SELECT_ALL_ROOTS =
        "SELECT root_id, abs_root, kind, granted_at, revoked_at FROM scope ORDER BY root_id"

    /** 现在还有效的根（撤销过的不回来）。`ORDER BY kind, root_id`：清单与单测都要确定序。 */
    const val SELECT_ACTIVE_ROOTS =
        "SELECT root_id, abs_root, kind FROM scope WHERE revoked_at IS NULL ORDER BY kind, root_id"

    const val SELECT_IS_ACTIVE =
        "SELECT 1 FROM scope WHERE abs_root = ? AND revoked_at IS NULL LIMIT 1"

    /** 撤销要按 `root_id` 删 doc（外键那一列是 id 不是路径），所以先把 abs_root 翻成 id。 */
    const val SELECT_ROOT_ID = "SELECT root_id FROM scope WHERE abs_root = ?"

    /** 撤销＝打时间戳（账本留着给人复盘），索引另删（§6.2 的 `revoked_at` 是历史不是屏蔽位）。 */
    const val MARK_SCOPE_REVOKED =
        "UPDATE scope SET revoked_at = ? WHERE root_id = ? AND revoked_at IS NULL"

    /**
     * 撤销后的**残留探针**：有没有「已撤销的根还留着 doc 行」。读出来非空就是隐私事故
     * （用户以为撤了，倒排里还存着他那批文件的词）——[SearchScopeStore.orphanDocRoots] 用它。
     * 第一列给 `abs_root` 而不是 `root_id`：这个读数要进日志给人看，id 他读不懂。
     */
    const val SELECT_ORPHAN_DOCS =
        "SELECT s.abs_root, d.rel_path FROM doc d JOIN scope s ON s.root_id = d.root_id " +
            "WHERE s.revoked_at IS NOT NULL ORDER BY s.abs_root, d.rel_path"

    /** 先删 doc（tok 顺级联走）再打撤销标记；两条语句的顺序由 [SearchScopeStore.revoke] 保证。 */
    const val DELETE_DOCS_OF_ROOT = "DELETE FROM doc WHERE root_id = ?"

    const val DELETE_WATERMARK_OF_ROOT = "DELETE FROM watermark WHERE root_id = ?"

    // ── doc：一篇一行 ────────────────────────────────────────────────────────

    const val UPSERT_DOC =
        "INSERT INTO doc(root_id, rel_path, kind, size, mtime, title, sha256_head, indexed_at, status) " +
            "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(root_id, rel_path) DO UPDATE SET kind = excluded.kind, size = excluded.size, " +
            "mtime = excluded.mtime, title = excluded.title, sha256_head = excluded.sha256_head, " +
            "indexed_at = excluded.indexed_at, status = excluded.status"

    const val SELECT_DOC_ID = "SELECT doc_id FROM doc WHERE root_id = ? AND rel_path = ?"

    /** 增量（§6.4）：按根翻出 (rel_path, size, mtime, status)，与磁盘对不上的由调用方决定重扫/删行。 */
    const val SELECT_DOCS_OF_ROOT =
        "SELECT doc_id, rel_path, size, mtime, status FROM doc WHERE root_id = ? ORDER BY rel_path"

    /** 文件没了 ⇒ 删行，tok 顺 `ON DELETE CASCADE` 一起走（不写第二条 DELETE，避免「删了一半」）。 */
    const val DELETE_DOC_BY_ID = "DELETE FROM doc WHERE doc_id = ?"

    // ── tok：倒排 ────────────────────────────────────────────────────────────

    const val UPSERT_TOK = "INSERT OR REPLACE INTO tok(token, doc_id, cnt) VALUES(?, ?, ?)"

    /**
     * 检索（只读）。**词永远走绑定参数**：本文件生成的是 `IN (?, ?, ?)` 这样的占位符，
     * 词本身一个字都不进 SQL 文本（既是注入防线，也是「查询词不落在任何持久化字符串里」那条
     * 隐私线的实现方式——派工单 §2 点名「把查询词写进 `err`」是要红的负例）。
     *
     * 打分只做「命中词频求和」：设计 §9 的「标题 ×3／正文 ×1」是 S3 检索件的活（它复用
     * `KbRetriever` 的算式），本批不在这儿预先长出一个没人调的权重参数。
     *
     * `ORDER BY score DESC, rel_path`：同分必按路径定序，不靠 rowid 的偶然顺序——
     * 单测要能断言第 1 名是谁。
     */
    fun selectSearch(tokenCount: Int, limit: Int = SEARCH_LIMIT): String {
        require(tokenCount in 1..MAX_TOKENS) { "tokenCount 要在 1..$MAX_TOKENS，实传 $tokenCount" }
        require(limit in 1..MAX_LIMIT) { "limit 要在 1..$MAX_LIMIT，实传 $limit" }
        return "SELECT d.root_id, d.rel_path, SUM(t.cnt) AS score " +
            "FROM tok t JOIN doc d ON d.doc_id = t.doc_id " +
            "WHERE t.token IN (" + placeholders(tokenCount) + ") " +
            "AND d.status = 'OK' " +
            "GROUP BY d.root_id, d.rel_path " +
            "ORDER BY score DESC, d.rel_path LIMIT " + limit
    }

    /** 占位符串（纯函数，可单测）：只允许 `?`、逗号、空格——出现别的字符就是拼参数进 SQL。 */
    fun placeholders(count: Int): String {
        require(count in 1..MAX_TOKENS) { "count 要在 1..$MAX_TOKENS，实传 $count" }
        return List(count) { "?" }.joinToString(", ")
    }

    // ── watermark：每根一行 ──────────────────────────────────────────────────

    const val UPSERT_WATERMARK =
        "INSERT INTO watermark(root_id, last_scan_at, last_full_at, files_seen, files_skipped, bytes_read) " +
            "VALUES(?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(root_id) DO UPDATE SET last_scan_at = excluded.last_scan_at, " +
            "last_full_at = excluded.last_full_at, files_seen = excluded.files_seen, " +
            "files_skipped = excluded.files_skipped, bytes_read = excluded.bytes_read"

    const val SELECT_WATERMARK =
        "SELECT last_scan_at, last_full_at, files_seen, files_skipped, bytes_read " +
            "FROM watermark WHERE root_id = ?"

    // ── 常量 ────────────────────────────────────────────────────────────────

    /** 一次查询最多绑多少个词（`tokensOfQuery` 切出的词数上限，防 SQL 变量数撞 SQLite 参数顶）。 */
    const val MAX_TOKENS = 16

    const val MAX_LIMIT = 200

    const val SEARCH_LIMIT = 40
}
