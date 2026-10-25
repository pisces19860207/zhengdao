// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1①②④ ＋ §2 负例）：**分片扫描与增量的算式层直测**。
// 派工单 §2 原话「分词/打分/指纹的 JVM 表驱动测……这是选 B 方案的全部理由，不许只写真机验」——
// 这一条管的是它的前半批：切片、进队、停因、增量、水位。这些全在 `PhoneScanRules` 的纯函数里，
// 一个 Android 符号都不接 ⇒ 真机只是把同一张表跑一遍，不是唯一的证据。
//
// ## 覆盖清单（逐条对派工单）
// 1. §1①「每片 2000」→ [表驱动_切片]：`shardPages` 覆盖 `[0,total)` **不漏不重**，每片 ≤2000。
//    负例「全扫完才写／尾巴丢了」红在这里：尾巴那一片必须存在（`scanTree` 的 `take(MAX_SCAN)` 是反面教材）。
// 2. §1②「不跟随软链、不进 `Android/`、不进 `.` 开头、点名排除 `rootfs/` 与 `.baopu-spill/`」
//    → [表驱动_目录进队]：判据是 `SearchAdmission.exclusion` 那一把（排除清单只有一处说话）。
// 3. §1①「片间可取消」＋ §1③「可达即停」→ [表驱动_停因优先级]：取消永远最优先，其次命中够，再次超时。
// 4. §1④「size+mtime 变则重索引、没了删 doc＋tok」→ [表驱动_增量三条出口]：三条出口分全，
//    `reindex` 必须带 `docId`（不带就撤不下旧行）。
// 5. §1④「重启按 watermark 补扫」→ [表驱动_根计划] ＋ [负例_水位不谎报扫全]：
//    中途停只刷 `last_scan_at`，`last_full_at` 谎报一次＝永久漏索引。
// 6. §1③「显示已扫 N/约 M」→ [表驱动_约M]：没真扫全过 ⇒ null（UI 只显示 N，不拿本片冒充满总数）。
//
// ## 为什么这里不建真目录
// 本文件全部判据吃的都是 String/Long/`File` 的**名字与读数**，没有一次实读盘（读盘在
// `PhoneSearch` 那层薄壳里，由真机冷扫数字覆盖）。排除清单比的是路径**组件**，
// `File("/storage/…").absoluteFile` 在 Windows 上会多个盘符，不影响任何一条判据（同 `SearchAdmissionTest`）。
package com.example.zhengdao.baopu.search

import com.example.zhengdao.baopu.library.LibraryPaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneScanTest {

    private val phone = "/storage/emulated/0"

    private fun f(path: String) = File(path)

    // ── 1. 切片：每片 2000，不漏不重（§1①／§2「全扫完才写」） ───────────────────

    @Test
    fun `表驱动_切片覆盖全量不漏不重`() {
        val cases = listOf(0, 1, 7, 1999, 2000, 2001, 4500, 20_000)
        cases.forEach { total ->
            val pages = PhoneScanRules.shardPages(total)
            val covered = pages.flatMap { it.toList() } // IntRange 的 last **含端点**
            assertEquals("total=$total 必须每个下标都被切到一次（漏一个＝那个文件永不进索引）", total, covered.size)
            assertEquals("total=$total 不许重复切（重复只是多写一次同一行，但计数会假）", total, covered.toSet().size)
            if (total > 0) {
                assertEquals("total=$total 的起点必须是 0", 0, covered.first())
                assertEquals("total=$total 的终点必须是最后一个下标", total - 1, covered.last())
            }
            pages.forEach { p ->
                assertTrue("total=$total 出现空片：$p", p.last >= p.first)
                assertTrue("total=$total 某片超过上限：$p", p.count() <= PhoneScanRules.CHUNK_FILES)
            }
        }
    }

    @Test
    fun `负例_片是闭区间_薄壳取片不许丢末位`() {
        // `shardPages` 返回 `IntRange`（last 含端点），而 `List.subList(from, to)` 的 to **不含**端点。
        // 两者一混，薄壳每片就悄悄少一个文件：2001 条丢第 2001 条，单文件目录（`0 until 1`）
        // 整个目录不入库。这条不是理论问题——本仓真写过 `subList(page.first, page.last)`，
        // 是本文件上面那条「不漏不重」用例把它逼出来的（报告 §3 记这条）。
        val items = (0..2000).toList() // 2001 条
        val pages = PhoneScanRules.shardPages(items.size)
        assertEquals("按闭区间取片 ⇒ 全覆盖", items.size, pages.flatMap { items.slice(it) }.size)
        assertEquals(
            "反证：写成 subList(first, last) 会少 2 条（每片末位各一条，尾巴那片整个空）",
            items.size - 2, pages.flatMap { items.subList(it.first, it.last) }.size,
        )
        // 判据层不许长出第二套「怎么取片」：薄壳只能用 slice(page)。比**剥掉注释的代码**——
        // 薄壳那段注释正是在解释「别写 subList(first,last)」，拿原文比对会被自己的注释判红。
        val shell = code(sourceOf("PhoneSearch.kt"))
        assertTrue("薄壳必须按闭区间取片（entries.slice(page)）", shell.contains("entries.slice(page)"))
        assertFalse("薄壳不许退回 subList(first, last)（每片丢末位）", shell.contains("subList(page.first, page.last)"))
    }

    @Test
    fun `负例_尾巴不丢_切片与take的分别`() {
        // 派工单 §1① 的「每片 2000」如果做成 `list.take(2000)`，第 2001 个文件永远进不了索引，
        // 而且**没人知道**——这正是 `LibraryPaths.scanTree`（:189 `collected.take(MAX_SCAN)`）的形态。
        val total = PhoneScanRules.CHUNK_FILES + 1
        val pages = PhoneScanRules.shardPages(total)
        assertEquals("2001 条要切两片", 2, pages.size)
        assertEquals("第二片就是那条尾巴：1 条，不是 0 条", 1, pages[1].count())
        assertEquals(
            "对照：`take(2000)` 会把尾巴整条丢掉（本批不用它，就是因为这一句）",
            PhoneScanRules.CHUNK_FILES,
            (0 until total).toList().take(PhoneScanRules.CHUNK_FILES).size,
        )
        // 一片一个事务＝「边扫边入库」的形状：片数必须大于 1，否则「边扫边写」无从谈起（薄壳按片提交）。
        assertTrue("一片就要有一个 putShard：片数即事务数", pages.size >= 2)
    }

    @Test
    fun `表驱动_一片就是派工单点名的2000`() {
        // 与 `LibraryPaths.MAX_SCAN` 同一个数：一个目录的顶层一次看完，不另造第二个「片大小」。
        assertEquals(2000, PhoneScanRules.CHUNK_FILES)
        assertEquals("片大小与资料库扫描上限同源", LibraryPaths.MAX_SCAN, PhoneScanRules.CHUNK_FILES)
    }

    // ── 2. 片内路径 → 根内路径（`doc.rel_path` 的写法） ─────────────────────────

    @Test
    fun `表驱动_片内路径换成根内路径`() {
        val cases = listOf(
           ("" to "a.md") to "a.md",
            ("Documents" to "a.md") to "Documents/a.md",
            ("Download/证道/资料库" to "笔记.md") to "Download/证道/资料库/笔记.md",
            // 多写的斜杠必须吃掉：库里 `/x/a.md` 与 `x/a.md` 是两个 rel_path，增量会把它当两个文件
            ("/Documents/" to "/a.md") to "Documents/a.md",
        )
        cases.forEach { (input, want) ->
            assertEquals("relToRoot(${input.first}, ${input.second})", want, PhoneScanRules.relToRoot(input.first, input.second))
        }
        // 空片内名＝目录不是文件：让它拼出「Documents/」这种串，库里就多一条永远 stat 不到的行
        assertThrows(IllegalArgumentException::class.java) { PhoneScanRules.relToRoot("Documents", "") }
        assertThrows(IllegalArgumentException::class.java) { PhoneScanRules.shardPages(-1) }
    }

    // ── 3. 目录进队：排除清单连队列都不许进（§1②／§2「索引 Android/data」「索引 rootfs/」） ──

    @Test
    fun `表驱动_哪些目录不许进队`() {
        val rejected = listOf(
            "Android" to "android-any",
            "Android/data" to "android-data",
            "Android/obb" to "android-obb",
            "Android/data/com.foo.files/files" to "android-data",
            ".baopu-spill" to "dot",
            ".ssh" to "dot",
            "Download/.hidden" to "dot",
            "rootfs" to "terminal",
            "logs" to "terminal",
            "cache" to "terminal",
            "agents" to "terminal",
            "截屏" to "terminal",
            "Pictures/Screenshots" to "terminal",
            "backup" to "backup",
            "backup/2026-10-24" to "backup",
            "Download/keys.pem.key" to "secret",
            "Download/id_rsa" to "secret",
        )
        rejected.forEach { (rel, tag) ->
            val dir = f("$phone/$rel")
            assertFalse("「$rel」必须被挡在队列外（排除清单是下限，不是选项）", PhoneScanRules.acceptDir(dir))
            assertEquals("「$rel」挡住的原因应当是 $tag", tag, SearchAdmission.exclusion(dir)?.tag)
            assertFalse("进队判据也要挡「$rel」", PhoneScanRules.enqueueDir(dir))
        }
    }

    @Test
    fun `表驱动_正常目录要进队_挡错了就是少搜`() {
        val accepted = listOf(
            "", // 手机存储根自己
            "Documents",
            "Download",
            "Download/证道",
            "Download/证道/资料库",
            "DCIM/Camera",
            "Music/专辑/第一集",
            "Note",
        )
        accepted.forEach { rel ->
            val dir = if (rel.isEmpty()) f(phone) else f("$phone/$rel")
            assertTrue("「$rel」不该被挡（失效方向只许是少搜，不许是错挡）", PhoneScanRules.acceptDir(dir))
            assertTrue("「$rel」要能进队", PhoneScanRules.enqueueDir(dir))
        }
        // 根自己也要过门：授予的根若本身在排除清单里，整根一行都不扫
        assertTrue(PhoneScanRules.rootAccepted(f(phone)))
        assertFalse("根就是 Android/data ⇒ 整根不扫", PhoneScanRules.rootAccepted(f("$phone/Android/data")))
        assertFalse("根就是 rootfs ⇒ 整根不扫（真机实测 349 MB 那个）", PhoneScanRules.rootAccepted(f("$phone/rootfs")))
    }

    @Test
    fun `结构_软链判据与LibraryPaths同源`() {
        // `LibraryPaths.isSymlink` 是 private（改可见性要动那个文件，本批不碰），所以本地实现一份。
        // 同源的代价由门禁付：两边的实现字面量必须同为「Files.isSymbolicLink ＋ 出错当不是软链」。
        val mine = sourceOf("PhoneScan.kt")
        val theirs = repoFile("src/main/java/com/example/zhengdao/baopu/library/LibraryPaths.kt").readText()
        listOf(mine to "PhoneScan.kt", theirs to "LibraryPaths.kt").forEach { (text, name) ->
            assertTrue("$name 的软链判据不再是 Files.isSymbolicLink", text.contains("Files.isSymbolicLink("))
            assertTrue("$name 的软链判据读不到就当真不是软链（宁可不判，不许抛出去打断整根扫描）",
                text.contains(".getOrDefault(false)"))
        }
    }

    // ── 4. 片间停因：取消 → 命中够 → 超时（§1①／§1③） ──────────────────────────

    @Test
    fun `表驱动_停因优先级`() {
        val timeout = PhoneScanRules.SCAN_TIMEOUT_MS
        // 取消永远最优先：用户按了停止，就算命中够爆、时间也超了，报的也是「你停的」
        assertEquals(StopReason.CANCELLED, PhoneScanRules.stopReason(hits = 999, topK = 1, elapsedMs = timeout, cancelled = true))
        assertEquals(StopReason.HIT_BUDGET, PhoneScanRules.stopReason(hits = 20, topK = 1, elapsedMs = 0, cancelled = false))
        assertEquals(StopReason.TIMEOUT, PhoneScanRules.stopReason(hits = 0, topK = 1, elapsedMs = timeout, cancelled = false))
        assertEquals(StopReason.DONE, PhoneScanRules.stopReason(hits = 19, topK = 1, elapsedMs = timeout - 1, cancelled = false))
        // 不带词（重建索引那一次）⇒ 永不因命中停：否则「重建」会变成「扫到一屏就完」，索引永远是半份
        (0..(PhoneScanRules.HIT_RATIO * 5)).forEach { hits ->
            assertEquals("topK=0 时命中数不参与停：hits=$hits", StopReason.DONE, PhoneScanRules.stopReason(hits, 0, 0, false))
        }
        // 预算＝topK × HIT_RATIO（派工单「可达即停」的那个「够」字）：一屏 40 条，扫到 800 个命中才停
        assertEquals(StopReason.DONE, PhoneScanRules.stopReason(40 * PhoneScanRules.HIT_RATIO - 1, 40, 0, false))
        assertEquals(StopReason.HIT_BUDGET, PhoneScanRules.stopReason(40 * PhoneScanRules.HIT_RATIO, 40, 0, false))
    }

    // ── 5. 根计划与水位（§1④「重启按 watermark 补扫」） ──────────────────────────

    private fun row(kind: ScopeKind = ScopeKind.PHONE, revokedAt: Long? = null) = ScopeRow(
        rootId = 7L,
        absRoot = phone,
        kind = kind,
        grantedAt = 1000L,
        revokedAt = revokedAt,
    )

    private fun mark(lastScanAt: Long, lastFullAt: Long, seen: Int = 0) =
        MarkRow(lastScanAt, lastFullAt, seen, 0, 0L)

    @Test
    fun `表驱动_根计划三种形态`() {
        // ① 从没全扫过 ⇒ 整根全量，不核对账（账上是空的，核对个什么）
        val cold = PhoneScanRules.planRoot(row(), mark = null, forceFull = false)
        assertTrue("冷扫必须整根", cold.full)
        assertFalse("冷扫不核对账本", cold.verifyDocs)
        // ② 扫全过 ⇒ 只补可疑目录 ＋ 核对账上的行（增量两半各管一件事）
        val inc = PhoneScanRules.planRoot(row(), mark(9000L, 9000L, 500), forceFull = false)
        assertFalse("增量不整根重扫", inc.full)
        assertTrue("增量必须核对账（不然删掉的文件永远留着旧行）", inc.verifyDocs)
        // ③ 用户点「重建索引」⇒ 整根，并且**不核对**（核对是增量的那一半，重建时用不上）
        val rebuild = PhoneScanRules.planRoot(row(), mark(9000L, 9000L, 500), forceFull = true)
        assertTrue("重建必须整根", rebuild.full)
        assertFalse("重建不核对", rebuild.verifyDocs)
        // 只扫过一次但没扫全（中途停）⇒ 仍然按全量补：水位不敢说「扫全了」，计划就不敢说
        val partial = PhoneScanRules.planRoot(row(), mark(9000L, 0L, 120), forceFull = false)
        assertTrue("上次没扫全 ⇒ 这次还是全量", partial.full)
    }

    @Test
    fun `负例_已撤销的根一行都不扫`() {
        val p = PhoneScanRules.planRoot(row(revokedAt = 2000L), mark(9000L, 9000L, 500), forceFull = true)
        assertFalse("撤过的根不许整根扫", p.full)
        assertFalse("撤过的根也不许核对账", p.verifyDocs)
        // forceFull 也不能翻案：用户点重建不等于把撤掉的根再授权一次
        val again = PhoneScanRules.planRoot(row(revokedAt = 2000L), mark = null, forceFull = true)
        assertEquals(RootPlan(7L, phone, full = false, verifyDocs = false), again)
    }

    @Test
    fun `表驱动_可疑目录只抓新增与删除`() {
        // 没水位 ⇒ 一律可疑（宁可多扫，不许漏扫）
        assertTrue(PhoneScanRules.suspectDir(1L, mark = null))
        assertTrue(PhoneScanRules.suspectDir(9999L, mark(0L, 0L)))
        assertTrue("lastScanAt=0 等于没水位", PhoneScanRules.suspectDir(1L, mark(0L, 5L)))
        // 目录 mtime 在上次扫描之后动过 ⇒ 里面有新增/删除（POSIX：改内容不碰目录 mtime）
        assertTrue(PhoneScanRules.suspectDir(2000L, mark(1000L, 1000L)))
        // 没动过 ⇒ 可以跳过这一层（改内容那一半由 planIncrement 逐行 stat 兜住）
        assertFalse(PhoneScanRules.suspectDir(1000L, mark(1000L, 1000L)))
        assertFalse(PhoneScanRules.suspectDir(500L, mark(1000L, 1000L)))
    }

    @Test
    fun `表驱动_增量三条出口分全`() {
        val rows = listOf(
            DocLedgerRow(1L, "a.md", 100L, 1000L),
            DocLedgerRow(2L, "b.md", 200L, 1000L),
            DocLedgerRow(3L, "c.md", 300L, 1000L),
            DocLedgerRow(4L, "d.md", 400L, 1000L),
        )
        val disk = mapOf(
            "a.md" to SizeMtime(100L, 1000L), // 两个数都对上 ⇒ 不动
            "b.md" to SizeMtime(201L, 1000L), // 大小变了 ⇒ 重索引
            "c.md" to SizeMtime(300L, 1001L), // 时间变了 ⇒ 重索引
            // d.md 盘上没了 ⇒ 删行
        )
        val inc = PhoneScanRules.planIncrement(rows, disk)
        assertEquals("改内容：2 行", listOf(DocRowRef(2L, "b.md"), DocRowRef(3L, "c.md")), inc.reindex)
        assertEquals("没了：1 行", listOf(DocRowRef(4L, "d.md")), inc.gone)
        assertEquals("没变：1 行", 1, inc.unchanged)
        assertEquals("三条出口必须分全（少了哪条都是漏）", rows.size, inc.reindex.size + inc.gone.size + inc.unchanged)
        // `reindex` 带 docId 的理由：重扫后如果这篇变成二进制/空文件，必须能把旧行撤下来，
        // 否则旧内容还能被搜到——那是一份看不见的假账。
        assertTrue("reindex 必须带 docId（只带路径就撤不了旧行）", inc.reindex.all { it.docId > 0L })
    }

    @Test
    fun `表驱动_增量把mtime精度也钉住`() {
        // mtime 差 1 毫秒也算变：文件改了就重索引，判据不做「差不多就算了」的模糊比较
        val rows = listOf(DocLedgerRow(1L, "a.md", 10L, 1_700_000_000_000L))
        assertEquals(
            1,
            PhoneScanRules.planIncrement(rows, mapOf("a.md" to SizeMtime(10L, 1_700_000_000_001L))).reindex.size,
        )
        // 空账（从没扫过的根）⇒ 三个出口全空，不炸
        val none = PhoneScanRules.planIncrement(emptyList(), mapOf("x.md" to SizeMtime(1L, 1L)))
        assertEquals(IncrementPlan(emptyList(), emptyList(), 0), none)
    }

    @Test
    fun `负例_水位不谎报扫全`() {
        val old = mark(5000L, 5000L, seen = 300)
        // 中途停：只刷 last_scan_at，last_full_at 保持上一次那个数 ⇒ 下次仍按全量补
        val partial = PhoneScanRules.rollUpMark(7L, old, 120, 3, 4096L, now = 9000L, completed = false)
        assertEquals(9000L, partial.lastScanAt)
        assertEquals("没扫全就不许把 last_full_at 拨到此刻", 5000L, partial.lastFullAt)
        assertEquals(120, partial.filesSeen)
        // 扫全了：两个时间戳一起走
        val full = PhoneScanRules.rollUpMark(7L, old, 500, 8, 8192L, now = 9000L, completed = true)
        assertEquals("扫全了 ⇒ last_full_at 就是这一刻", 9000L, full.lastFullAt)
        assertEquals(500, full.filesSeen)
        // 第一次扫（没有旧行）且没扫全 ⇒ last_full_at 留 0，读起来是「从没扫全过」而不是「1970 年扫过」
        val first = PhoneScanRules.rollUpMark(7L, old = null, 10, 0, 100L, now = 9000L, completed = false)
        assertEquals(0L, first.lastFullAt)
        // 负数是调用方算错了（跨根累加减错了），必须炸，不许写进库里当「跳过 -1 个」
        assertThrows(IllegalArgumentException::class.java) {
            PhoneScanRules.rollUpMark(7L, old, -1, 0, 0L, now = 1L, completed = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneScanRules.rollUpMark(7L, old, 0, 0, -1L, now = 1L, completed = true)
        }
    }

    @Test
    fun `表驱动_约M只在真扫全过之后才有`() {
        assertNull("从没扫过 ⇒ 没有约 M", PhoneScanRules.approxTotal(null))
        assertNull("中途停的那次不许冒充实数", PhoneScanRules.approxTotal(mark(9000L, 0L, 120)))
        assertEquals(500, PhoneScanRules.approxTotal(mark(9000L, 9000L, 500)))
        // 超过上限就不报了「约」：「约 200000」和「很多」对用户的意义一样，前者的精度是假的
        assertEquals(PhoneScanRules.MAX_APPROX, PhoneScanRules.approxTotal(mark(9L, 9L, PhoneScanRules.MAX_APPROX + 50_000)))
        // 扫全过但一个文件都没有（真空目录）⇒ null：显示「已扫 0／约 0」会让人以为扫过了
        assertNull("0 个文件的根没有「约 M」可说", PhoneScanRules.approxTotal(mark(9000L, 9000L, 0)))
    }

    // ── helper（源码文本门禁用） ────────────────────────────────────────────────

    private fun sourceOf(name: String): String = repoFile("src/main/java/com/example/zhengdao/baopu/search/$name").readText()

    /** 剥掉块注释与整行 `//`（行尾注释不剥）——源码文本门禁比的是代码，不是解释（房型同 `SearchAdmissionTest`）。 */
    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 从工作目录逐级向上找仓库里的相对路径（同 `SearchAdmissionTest`／`SearchReadNeverGrantsWriteTest` 的房型）。 */
    private fun repoFile(rel: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf(File(dir, "app/$rel"), File(dir, rel)).firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
    }
}
