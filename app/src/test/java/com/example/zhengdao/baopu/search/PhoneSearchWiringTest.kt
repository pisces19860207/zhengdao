// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1①④ ＋ §2）：**装配层的接线门禁**——判据层（`PhoneScanRules`／
// `PhoneText`／`PhoneRank`／`PhoneJump`）与薄壳（`PhoneSearch`／`PhoneIndex`）之间那几根线接对没有。
//
// ## 为什么这一层只能用源码文本判
// `PhoneSearch.scan()` 要真的 `Context`、真的 `listFiles()`、真的 `SQLiteDatabase`，
// 而本仓没有 Robolectric（`android.jar` 是 stub）⇒ JVM 里**跑不起来**。所以「片刷盘的单位」
// 「取消检查的粒度」「重建索引时那条快路关没关」这些只能在源码文本上判。
// 判据本身（切多少片、什么时候停、什么算变过）全在纯函数那一层，由 `PhoneScanTest` 表驱动打——
// **两片合起来才是派工单 §2 那句「JVM 表驱动测……不许只写真机验」的完整交付**。
//
// ## 为什么每条都锚在函数体上而不是整文件
// 文件级 `contains` 会「在哪儿出现都行」：把 `cancelled()` 从片循环挪进文件循环、
// 把 `putShard` 从片里挪到根尾，整文件仍然 `contains` 得着，而行为已经反了。
// 所以下面几乎所有断言都先 [body] 取出那一个函数的体，再在体内判顺序与个数。
package com.example.zhengdao.baopu.search

import com.example.zhengdao.baopu.library.LibraryPaths
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSearchWiringTest {

    // ── 1. 分片刷盘与取消的粒度（§1①「每片 2000、边扫边入库、片间可取消」） ──────────

    @Test
    fun `结构_刷盘单位是片_不是根也不是整趟`() {
        val scan = body(SHELL, "scan")
        val walk = body(SHELL, "walkRoot")
        val page = body(SHELL, "indexPage")
        // 落库发生在片里（indexPage），且由遍历段的片循环逐片调用
        assertTrue("indexPage 必须自己 putShard（一片一次事务）", page.contains("index.putShard(rootId, rows, now)"))
        assertTrue("walkRoot 的片循环必须调 indexPage", walk.contains("indexPage(index, rootFile, rootId, relDir, entries.slice(page), terms, reuse, c)"))
        // 扫描主干（scan）一张都不许写：根尾统一入库就是派工单 §2 点名的「全扫完才写」
        assertFalse("scan 本体不许直接 putShard：刷盘单位是片，攒到根尾就是「全扫完才写」", scan.contains("putShard"))
        // 顺序：先落库，再判停（反过来就是「算够了才写」——停下的那一片永远进不了库）
        assertTrue(
            "片循环里必须先把这片入库、再判停因（否则停下的那一片一行都没落）",
            walk.indexOf("indexPage(index,") < walk.indexOf("stopReason("),
        )
    }

    @Test
    fun `结构_取消只在片间_文件级循环里一次都不查`() {
        val walk = body(SHELL, "walkRoot")
        val page = body(SHELL, "indexPage")
        val sweep = body(SHELL, "sweepLedger")
        assertEquals("遍历段一片查一次取消", 1, Regex("cancelled\\(\\)").findAll(walk).count())
        assertEquals("核对段一片查一次取消", 1, Regex("cancelled\\(\\)").findAll(sweep).count())
        assertFalse("片内（逐文件）不许查取消：2000 个文件查 2000 次等于把闸门做成装饰", page.contains("cancelled"))
        // 取消永远是第一个停因（纯函数那一半由 `PhoneScanTest.表驱动_停因优先级` 打）
        assertTrue("片间检查必须把 cancelled 传给 stopReason", walk.contains("cancelled())"))
    }

    @Test
    fun `结构_片失败不算命中也不算扫过`() {
        val page = body(SHELL, "indexPage")
        val at = page.indexOf("if (!index.putShard(rootId, rows, now))")
        assertTrue("indexPage 必须接住 putShard 的 false", at >= 0)
        val branch = page.substring(at, at + 160)
        assertTrue("失败片必须记进 failedShards", branch.contains("c.failedShards++"))
        assertTrue("失败片一个命中都不许算（算了就是「够一屏」提前停，屏幕上却是空的）", branch.contains("return 0"))
        // 空片与「这一片全没被认领」都不算失败（否则计数会假，界面上「几片」没意义）
        assertTrue("这一片全被排除 ⇒ 一行不写、也不算失败", page.contains("if (rows.isEmpty()) return 0"))
    }

    @Test
    fun `结构_取片必须按闭区间_每片不丢末位`() {
        // 派工单 §2「静默截断」在本批唯一可能悄悄发生的地方就是这一行：
        // `shardPages` 给的是 `IntRange`（last **含端点**），写成 `subList(first, last)`
        // 每片悄悄少一个文件（单文件目录整个不入库）。上一轮实测就是这么坏的。
        val walk = body(SHELL, "walkRoot")
        assertTrue("必须用闭区间的 entries.slice(page)", walk.contains("entries.slice(page)"))
        assertFalse("不许退回 subList(page.first, page.last)（每片丢末位）", walk.contains("subList(page.first, page.last)"))
        // 行为半边（纯函数层）：切片覆盖全量、尾巴是 1 条不是 0 条
        val items = (0..PhoneScanRules.CHUNK_FILES).toList()
        assertEquals("2001 条按闭区间切两片 = 全覆盖", items.size, PhoneScanRules.shardPages(items.size).flatMap { items.slice(it) }.size)
    }

    // ── 2. 增量（§1④）：重建索引不看账、两段顺序、水位只说真话 ────────────────────

    @Test
    fun `结构_重建索引不看账`() {
        // 「重建索引」必须关掉「账上 size+mtime 对上就不重读」那条快路——
        // 不然切词口径改了（本批就改了）也扫不出变化，用户点了个空转。
        val scan = body(SHELL, "scan")
        val page = body(SHELL, "indexPage")
        assertTrue("遍历段的 reuse 必须正好是 !forceFull", scan.contains("plan.full, !forceFull, terms, topK"))
        assertTrue("片内只有 reuse 为真才查账", page.contains("if (reuse) index.docStat(rootId, rel) else null"))
        assertFalse("indexPage 不许自己看 forceFull（口径由上游一层层传下来，判据不散）", page.contains("forceFull"))
        // 行为半边：forceFull 的根计划不核对账（整根重扫），非 forceFull 且扫全过的才走核对段
        val row = ScopeRow(1L, "/sdcard", ScopeKind.PHONE, 0L, null)
        val mark = MarkRow(lastScanAt = 5L, lastFullAt = 5L, filesSeen = 10, filesSkipped = 0, bytesRead = 0L)
        val forced = PhoneScanRules.planRoot(row, mark, forceFull = true)
        assertFalse("重建索引不该再走核对段（整根重扫已经覆盖它）", forced.verifyDocs)
        assertTrue("重建索引必须整根扫", forced.full)
    }

    @Test
    fun `结构_核对段先于遍历段_停下就不再走`() {
        val scan = body(SHELL, "scan")
        val sweepIdx = scan.indexOf("if (plan.verifyDocs) stop = sweepLedger")
        val walkIdx = scan.indexOf("if (stop == StopReason.DONE) stop = walkRoot")
        assertTrue("先按账核对（删没了的、重索引改了的），再遍历（补新增的）", sweepIdx in 0 until walkIdx)
        assertTrue("核对段没扫完 ⇒ 遍历段根本不走（两段共用一个时间预算）", walkIdx >= 0)
        // 核对段的三条出口都在（gone 删行 / reindex 重读 / 没变的不计数）
        val sweep = body(SHELL, "sweepLedger")
        assertTrue("没了的按批删（tok 顺级联）", sweep.contains("index.deleteDocs(ids)"))
        assertTrue("变了的重读（批内一批 2000）", sweep.contains("readDoc(f, ref.relPath"))
        assertFalse("核对段不数 reused（同一文件算两遍就是假账）", sweep.contains("c.reused"))
    }

    @Test
    fun `结构_水位每根一次且completed只由DONE来`() {
        val scan = body(SHELL, "scan")
        assertEquals("每根只落一次水位", 1, Regex("index\\.putMark\\(").findAll(scan).count())
        assertTrue("「扫全了」只等于停因是 DONE", scan.contains("val completed = stop == StopReason.DONE"))
        assertTrue("completed 必须原样交给 rollUpMark（不许在薄壳里另算）", scan.contains("completed = completed"))
        assertTrue("中途停下就不扫下一个根（时间预算是整趟的，不是每根的）", scan.contains("if (!completed) break"))
        // 行为半边：没扫完时 lastFullAt 沿用旧值（谎报扫全＝永久漏索引）
        val w = PhoneScanRules.rollUpMark(1L, MarkRow(100L, 100L, 9, 0, 0L), 5, 0, 0L, now = 200L, completed = false)
        assertEquals("没扫完只刷 lastScanAt", 200L, w.lastScanAt)
        assertEquals("没扫完不许刷 lastFullAt", 100L, w.lastFullAt)
    }

    // ── 3. 数据来源与复用面（§1②③：未授权扫不到、不跟随软链、不用 scanTree） ────────

    @Test
    fun `结构_有效根只有一个来源`() {
        val scan = body(SHELL, "scan")
        assertTrue("待扫的根只来自 activeRoots()（revoked_at IS NULL 的那几行）", scan.contains("val active = index.activeRoots()"))
        assertTrue("根路径只从账上那一条来", scan.contains("val rootFile = File(root.absRoot)"))
        // 撤销过的根在这儿物理上扫不到：整个薄壳不自己找目录
        val src = code(SHELL)
        listOf("Environment.", "getExternalStorageDirectory", "MediaStore", "ContentResolver", "externalStorage").forEach {
            assertFalse("薄壳自己去找目录「$it」：读侧能扫哪儿只由账本说了算", src.contains(it))
        }
        // 撤销后的残留由 S2 那条链负责（本批不重复实现一遍删除）
        assertFalse("薄壳不许自己撤销根（那是设置页的活）", src.contains("revoke("))
        assertFalse("薄壳不许自己删残留", src.contains("revokeAbsent"))
    }

    @Test
    fun `结构_不接scanTree_只用一层列举`() {
        val src = code(SHELL)
        assertFalse("薄壳不许用 scanTree（它到上限 take 掉尾巴＝静默截断）", src.contains("scanTree"))
        assertTrue("遍历只用 listTopLevelFiles（不递归、无上限、按名排序 ⇒ 切片是精确的）",
            src.contains("LibraryPaths.listTopLevelFiles(dir)"))
        val walk = body(src, "walkRoot")
        assertTrue("子目录进队时才判软链与排除清单", walk.contains("if (child.isDirectory && PhoneScanRules.enqueueDir(child)) queue.addLast(child)"))
        // 判据半边（软链与排除清单同源）在 `PhoneScanTest.结构_软链判据与LibraryPaths同源`；
        // 这里补一条：薄壳不另写一份软链判据（判据只有一份才谈得上「同源」）。
        assertFalse("薄壳不许自己碰 isSymbolicLink", src.contains("isSymbolicLink"))
    }

    @Test
    fun `结构_准入只有一扇门_排除清单不在这里重写`() {
        val src = code(SHELL)
        // S2 钉着的那一扇：`SearchAdmission` —— 本批只有这一处调用点集合
        assertTrue("片内入库前必须过 planDocRows", src.contains("SearchAdmission.planDocRows(rootId, rootFile, probes, now)"))
        assertTrue("重索引那一条也过同一扇门（call）", src.contains("SearchAdmission.call(rootFile, f) == Call.IN_SCOPE"))
        assertTrue("相对路径只问 relativePath（不许自己拼字符串）", src.contains("SearchAdmission.relativePath(rootFile, dir)"))
        // 排除清单的字面量只许活在 SearchAdmission 里（写进薄壳就是第二份清单，将来必漂）。
        // 注意 `rootfs/` 带斜杠：本文件那句 `import …rootfs.RunLog` 是包名，不是清单条目。
        listOf("Android/data", "Android/obb", "LOST.DIR", ".baopu-spill", "rootfs/").forEach {
            assertFalse("薄壳里出现排除清单字面量「$it」：清单只有一个地方说话", src.contains(it))
        }
    }

    @Test
    fun `结构_读内容只有一处`() {
        val src = code(SHELL)
        assertEquals("全仓只有 readHead/readAll 两处真的读字节", 2, Regex("inputStream\\(\\)").findAll(src).count())
        assertEquals("读一篇的算式只有一个函数", 1, Regex("private fun readDoc\\(").findAll(src).count())
        assertTrue("片内走 readDoc", body(src, "indexPage").contains("readDoc(candidate, rel"))
        assertTrue("核对段也走 readDoc（两处各写一遍就是将来漂的开始）", body(src, "sweepLedger").contains("readDoc(f, ref.relPath"))
        assertFalse("readDoc 本体不许自己开流（只在 readHead/readAll 里）", body(src, "readDoc").contains("inputStream"))
    }

    @Test
    fun `结构_内存四闸都是常量_壳里没有裸数字`() {
        // 派工单 §3 死卡「峰值内存必须是可复现的实测数字」——前提就是常驻量与根的大小无关，
        // 而它成立靠的是这四个数：一批 ≤2000 行、一片 ≤2000 条、一篇 ≤2 MiB、一篇 ≤4096 词。
        val src = code(SHELL)
        assertTrue("核对段一批用 CHUNK_FILES", body(src, "sweepLedger").contains("index.eachDoc(rootId, PhoneScanRules.CHUNK_FILES)"))
        assertEquals("一片的上限与核对段的一批同一个数", PhoneScanRules.CHUNK_FILES, LibraryPaths.MAX_SCAN)
        assertEquals("前 8KB 嗅探", 8192, PhoneText.HEAD_BYTES)
        assertEquals("一篇 2 MiB", 2L * 1024L * 1024L, PhoneText.MAX_FILE_BYTES)
        assertEquals("一篇 4096 个词", 4096, PhoneRank.MAX_DOC_TOKENS)
        assertEquals("stat 之后长胖的硬闸", 8L * 1024L * 1024L, PhoneSearch.READ_HARD_CAP)
        listOf("2000", "4096", "8192", "2 * 1024").forEach {
            assertFalse("薄壳里出现裸数字「$it」（四闸只能由常量说话，散在壳里就改不动）", src.contains(it))
        }
    }

    // ── 4. 不常驻、不自动（派工单 §2 点名的负例「常驻扫描」） ───────────────────────

    @Test
    fun `结构_没有常驻扫描`() {
        val src = code(SHELL)
        listOf("Thread(", "Handler(", "WorkManager", "JobScheduler", "Timer(", "ScheduledExecutor",
            "while (true)", "GlobalScope", "delay(", "suspend ", "CoroutineScope(",
        ).forEach {
            assertFalse("读侧内核出现「$it」：扫描是用户点一次跑一趟，不是后台常驻", src.contains(it))
        }
        // 时间预算是「一次手动扫描」的预算（90 秒），到点就停；不是「每隔多久扫一次」
        assertTrue("预算时间来自纯函数", Regex("SCAN_TIMEOUT_MS").findAll(src).count() == 0)
        assertEquals("预算只有一个数（片间检查用的就是它）", 90_000L, PhoneScanRules.SCAN_TIMEOUT_MS)
    }

    @Test
    fun `结构_UI的扫描只由按钮触发`() {
        // 「进页面就扫一遍」＝ 常驻扫描的另一种写法（用户没点头，盘已经在转）。
        listOf("Thread(", "Handler(", "WorkManager", "JobScheduler", "Timer(", "while (true)", "GlobalScope").forEach {
            assertFalse("搜索卡片出现「$it」：扫描只能由用户点按钮起、点停止停", UI.contains(it))
        }
        UI.lines().forEach { line ->
            if (line.contains("startScan(") && !line.contains("fun startScan(")) {
                assertTrue("有一句 startScan 不在 onClick 里（组合时／LaunchedEffect 里自动扫描是本批红线）：「${line.trim()}」",
                    line.contains("onClick"))
            }
        }
        assertTrue("取消按钮置的是同一个闸门", UI.contains("cancelFlag.set(true)"))
        assertTrue("扫描时读的闸门是 AtomicBoolean（跨线程读 Compose State 不安全⇒「点了没反应」）",
            UI.contains("AtomicBoolean(false)"))
        // 盘 IO 与库 IO 全部落在 IO 线程：每个 `scope.launch` 块内必须有 `Dispatchers.IO`，
        // 每个门面调用点必须落在「withContext 块 ＋ 只在那些块里被调的私有 helper」的闭包内。
        Regex("scope\\.launch\\s*\\{").findAll(UI).forEach { m ->
            val body = UI.substring(m.range.first, matchBracket(UI, UI.indexOf('{', m.range.last), '{', '}'))
            assertTrue("有一个 scope.launch 块不带 Dispatchers.IO（主线程读盘／读库）：「${body.take(90).trim()}」",
                body.contains("Dispatchers.IO"))
        }
        assertTrue("卡片里确实有 launch 块（没有的话上面那半条就是空跑）", Regex("scope\\.launch\\s*\\{").findAll(UI).count() >= 3)
        listOf(
            "PhoneSearch.snapshot(", "PhoneSearch.scan(", "PhoneSearch.query(", "PhoneSearch.syncNow(",
            "PhoneSearch.dbExists(", "BaopuRuntime.db(",
        ).forEach { call ->
            val at = allIndices(UI, call)
            assertTrue("$call 在卡片里一个调用点都没有（改了名就得回来解释这条门禁）", at.isNotEmpty())
            val ranges = ioRanges(UI)
            at.forEach { p ->
                assertTrue("「$call」的调用点不在 IO 线程的范围里（房规：所有盘 IO 与库 IO 都在 Dispatchers.IO）",
                    ranges.any { p in it })
            }
        }
    }

    @Test
    fun `结构_UI只经门面_不自己开search库`() {
        val card = code(UI)
        listOf("SearchDb(", "PhoneIndex(", "SearchSql.", "writableDatabase", "SearchScope.sync(").forEach {
            assertFalse("卡片里出现「$it」：读侧的库只许经 PhoneSearch 门面（越过去就绕过了失败口径）", card.contains(it))
        }
        // 台账查询是**主库**（`artifact`），本批唯一一处直读，判据在 PhoneJump 里
        assertTrue("回跳的台账语句只有一条，且来自 PhoneJump", card.contains("PhoneJump.SELECT_ARTIFACT_SESSION"))
        assertEquals("主库台账查询在卡片里只有一处", 1, Regex("PhoneJump\\.SELECT_ARTIFACT_SESSION").findAll(card).count())
    }

    // ── 5. 三处「同一个数／同一个口径」的对账（本批复用的那几把尺子） ────────────────

    @Test
    fun `同源_切词与打分不在壳里重算`() {
        val src = code(SHELL)
        assertFalse("薄壳不许自己 substring 切词", src.contains("substring(i, i + 2)"))
        assertFalse("薄壳不许自己加权", src.contains("TITLE_WEIGHT"))
        assertTrue("片内切词只调 PhoneRank.tokenCounts", src.contains("PhoneRank.tokenCounts(title, body.text)"))
        // 命中计数用同一批词（terms 一路只用来数命中，不进日志、不进库）
        assertTrue("命中判定用词表而不是原文", body(src, "indexPage").contains("r.counts.keys.any { it in terms }"))
        assertEquals("顶栏默认条数与 SQL 那个数同一条", SearchSql.SEARCH_LIMIT, PhoneSearch.DEFAULT_TOP_K)
    }

    @Test
    fun `同源_认领口径与资料库同一把`() {
        val src = code(SHELL)
        // 认领（哪扩展名算什么类）只问 LibraryPaths.classify，不在这儿维护第二份表
        assertTrue("片内条目用 listTopLevelFiles 的 kind（classify 的结果已经带在条目上）", src.contains("e.kind.name"))
        assertTrue("重索引时现查 classify（同一把尺子）", src.contains("LibraryPaths.classify(f.name).first.name"))
        assertFalse("薄壳不许出现扩展名字面量表", src.contains("listOf(\"md\", \"txt\")"))
        // 行为半边：TEXT_EXTS 与 classify 的对应关系由 `PhoneTextTest.表驱动_认领口径与LibraryPaths同源` 打
    }

    // ── helper（房型同 `SearchAdmissionTest`：源码文本比对先剥注释） ────────────────

    private companion object {
        /** 装配层：扫描／入库的薄壳（本批新写的两个文件）——**一律先剥注释再判**。 */
        val SHELL: String = code(read("src/main/java/com/example/zhengdao/baopu/search/PhoneSearch.kt"))

        /** 搜索卡片（用户唯一能看到、也唯一能起点扫描的地方）。 */
        val UI: String = code(read("src/main/java/com/example/zhengdao/ui/baopu/BaopuPhoneSearchCard.kt"))

        private fun read(rel: String): String = repoFile(rel).readText()

        private fun repoFile(rel: String): File {
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                listOf(File(dir, "app/$rel"), File(dir, rel)).firstOrNull { it.exists() }?.let { return it }
                dir = dir.parentFile
            }
            throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
        }

        /** 剥掉块注释与整行 `//`：本文件那些说明里写满了「不许 scanTree」这种话。 */
        private fun code(text: String): String =
            text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("(?m)^[ \\t]*//.*$"), "")

        /**
         * 取 `fun <name>(…)` 的函数体（参数表与大括号都按配平取）。
         *
         * 不用正则：`scan` 的参数表里有 `cancelled: () -> Boolean = { false }`，
         * `[^)]*` 与 `[^\n{]*` 都会在那儿截断——截断后取到的「体」是半句默认值，
         * 于是所有体内断言一起失效（静默的红）。
         */
        private fun body(src: String, fn: String): String {
            val at = src.indexOf("fun $fn(")
            assertTrue("找不到 `fun $fn(`（薄壳改了形状 ⇒ 这条门禁的锚点漂了，得回来重写）", at >= 0)
            val paramsClose = matchBracket(src, src.indexOf('(', at), '(', ')')
            val braceOpen = src.indexOf('{', paramsClose)
            assertTrue("`$fn` 没有函数体（或是表达式函数体？那这条门禁得换个判法）", braceOpen > paramsClose)
            return src.substring(braceOpen + 1, matchBracket(src, braceOpen, '{', '}'))
        }

        /** 从 `open` 处的那个开括号配平到对应闭括号，返回闭括号的下标。 */
        private fun matchBracket(src: String, open: Int, l: Char, r: Char): Int {
            var depth = 0
            for (i in open until src.length) {
                when (src[i]) {
                    l -> depth++
                    r -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            throw AssertionError("从 $open 起「$l」没配平（尾部：${src.takeLast(160)}）")
        }

        /** 子串的全部出现位置（调用点审计只看第一次＝只审了一条）。 */
        private fun allIndices(text: String, needle: String): List<Int> {
            val out = mutableListOf<Int>()
            var i = 0
            while (true) {
                val at = text.indexOf(needle, i)
                if (at < 0) return out
                out += at
                i = at + 1
            }
        }

        /**
         * 卡片里「一定跑在 IO 线程上」的代码范围：每个 `withContext(Dispatchers.IO){…}` 块，
         * 再加上**只在这些块里被调用**的私有 helper 的整个函数体（传递闭包：`searchOutcomeOf`
         * 包在 withContext 里 ⇒ 它调的 `searchRowOf`、它开的 `BaopuRuntime.db` 都算在 IO 线程上）。
         *
         * 为什么要闭包而不是一行一行看：房规是「所有盘 IO 与库 IO 都在 Dispatchers.IO」，
         * 而卡片的房型是把它们收进两三个私有 helper——按行判要么把 helper 判红，
         * 要么放过一个真的在主线程上读盘的新调用点。
         */
        private fun ioRanges(card: String): List<IntRange> {
            val out = mutableListOf<IntRange>()
            var i = 0
            while (true) {
                val at = card.indexOf("withContext(Dispatchers.IO)", i)
                if (at < 0) break
                out += at..matchBracket(card, card.indexOf('{', at), '{', '}')
                i = at + 1
            }
            var changed = true
            while (changed) {
                changed = false
                listOf("syncLedgerLine", "searchOutcomeOf", "searchRowOf", "copyIntoDeliver").forEach { fn ->
                    val decl = card.indexOf("private fun $fn(")
                    if (decl < 0) return@forEach
                    val end = matchBracket(card, card.indexOf('{', card.indexOf(')', decl)), '{', '}')
                    val range = decl..end
                    if (out.any { range.first >= it.first && range.last <= it.last }) return@forEach
                    val calls = allIndices(card, "$fn(").filter { it !in range }
                    if (calls.isNotEmpty() && calls.all { c -> out.any { c in it } }) {
                        out += range
                        changed = true
                    }
                }
            }
            return out.toList()
        }
    }
}
