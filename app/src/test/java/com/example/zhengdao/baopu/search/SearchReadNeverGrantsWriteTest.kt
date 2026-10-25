// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十七批（派工单 §1⑥ 硬不变式 ＋ §2 负例）：**授予读 ≠ 授予写**。
//
// 派工单原话：「有只读授予时，写判据 workspaceBoundaryCheck 的输出必须与没授予时逐字相同」。
// 这一句拆成三层，缺一条这个门禁就是空的：
//
// 1. **行为层**：读侧能给的三样（意愿＝开关、能力＝甲档、账本行＝scope 有效行）全部拉满，
//    写判据在三档目标（工作区内／工作区外·该弹卡／工作区外·原文拒绝）上的输出**逐字**不变。
// 2. **对照层**：把读侧账本接到写判据的 `granted` 口上（那次接线错误本尊），输出**立刻**不一致
//    ——这一条证明第 1 层不是「两侧同时漂」的空跑。
// 3. **结构层**：写判据的入参里没有读侧的位置；`WorkspaceAccessPolicy` 只有两个读数（都是写侧的）；
//    两个方向都不许 import 对方（读侧只被允许引写侧那几个符号：`WorkspaceGrantRules` 用来把根归一成
//    与写侧同一个字符串，S3 另外那几条全是「同一把尺子的第二个消费者」，逐条钉在哪个文件里；
//    读侧一片都不写盘、不联网、不自己抬权限；查询词只进绑定参数，不进日志也不进 SQL 文本）。
//
// 第一百九十八批（S3）改了两处措辞，判据本身没松：
// - 「读侧不接查询词」→「接了不许留」：S3 有了 `PhoneSearch.query(ctx, text)`，所以钉的改成
//   原文只到切词为止、日志与 SQL 文本一个字都不带（列那半边仍由 `SearchSqlTest` 钉）。
// - 包根 import 白名单从 2 条长成 6 条，并且**每条钉死所在文件**——白名单可以加长，
//   但不能长成「六个文件随便引写侧邻居」。
//
// 说清楚各层的边界（免得读数被当成比实际更强的结论）：行为层在 JVM 里能拉的读侧状态只有
// 纯算式与内存账本——生产那两样（`Environment.isExternalStorageManager()`、prefs 键）都要 Context，
// android.jar 的桩件里恒 false。所以「拉满」的强度是**算式层拉满**，真正的兜底是第 3 层：
// 读侧状态压根没有一个参数、全局读数或 import 能流进写判据。
//
// 房型照 156 批 `WorkspaceAccessTest`：`WorkspaceAccessPolicy` 是进程级读数点 ⇒ 每条用例自己
// `finally` 还原，不污染别的测试；`WorkspaceGrants` 用完 `resetForTest()`。
package com.example.zhengdao.baopu.search

import com.example.zhengdao.baopu.AccessVerdict
import com.example.zhengdao.baopu.WorkspaceAccessPolicy
import com.example.zhengdao.baopu.WorkspaceGrantRules
import com.example.zhengdao.baopu.WorkspaceGrants
import com.example.zhengdao.baopu.workspaceAccessVerdict
import com.example.zhengdao.baopu.workspaceBoundaryCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchReadNeverGrantsWriteTest {

    private val homePath = "/storage/emulated/0/Download/证道"
    private val phonePath = "/storage/emulated/0"

    /** 一个写判据的用例：工作区根、目标、以及**写侧**那个开关（与读侧开关是两件事）。 */
    private data class WriteCase(val label: String, val root: File, val target: File, val writeSwitchOn: Boolean)

    private val cases: List<WriteCase> = listOf(
        WriteCase("工作区内", File(homePath), File("$homePath/Documents/x.md"), false),
        WriteCase("工作区内·写开关也开", File(homePath), File("$homePath/Documents/x.md"), true),
        WriteCase("工作区外·写开关关（原文拒绝）", File(homePath), File("$phonePath/Documents/y.md"), false),
        WriteCase("工作区外·写开关开·本会话没批（该弹卡）", File(homePath), File("$phonePath/Documents/y.md"), true),
        WriteCase("工作区外·别家App私有目录", File(homePath), File("$phonePath/Android/data/com.x/files/z.md"), true),
    )

    private fun rootKeyOf(f: File): String = WorkspaceGrantRules.rootKey(f)

    /**
     * 写判据的一排输出（`null` = 放行）。两个写侧读数按生产的接法喂：开关现读 ＋ 本会话授权账本。
     *
     * `extraGranted` 是**违例通道**，只给对照组用（把读侧账本喂进去），正常调用一律留空。
     * 会话身份钉成 NO_SESSION：`WorkspaceGrants` 是进程级的，别的测试留下的会话号会让
     * `granted` 凭空为真，那样两侧的「逐字相同」就成了两个错互相抵消。
     */
    private fun writeOutputs(extraGranted: Set<String> = emptySet()): List<String?> {
        WorkspaceGrants.enterSession(WorkspaceGrantRules.NO_SESSION)
        return try {
            cases.map { c ->
                val oldOn = WorkspaceAccessPolicy.settingOn
                val oldGranted = WorkspaceAccessPolicy.granted
                WorkspaceAccessPolicy.settingOn = { c.writeSwitchOn }
                WorkspaceAccessPolicy.granted = { root ->
                    WorkspaceGrants.granted(root) || rootKeyOf(root) in extraGranted
                }
                try {
                    workspaceBoundaryCheck(
                        c.root,
                        c.target,
                        rootKeyOf(c.target),
                        "拒绝：${rootKeyOf(c.target)} 在工作区（${rootKeyOf(c.root)}）外，不许改。",
                    )
                } finally {
                    WorkspaceAccessPolicy.settingOn = oldOn
                    WorkspaceAccessPolicy.granted = oldGranted
                }
            }
        } finally {
            WorkspaceGrants.resetForTest()
        }
    }

    /** 读侧「授予拉满」在 JVM 里能给的最好状态：意愿开 ＋ 甲档能力 ＋ 账本里两行都有效。 */
    private fun fullReadGrant(): InMemorySearchScopeStore {
        val store = InMemorySearchScopeStore()
        val plan = SearchScopeRules.plan(PhoneAccess.GRANTED, rootKeyOf(File(homePath)), rootKeyOf(File(phonePath)))
        assertEquals("造场景：读侧两行都得入账", 2, store.record(plan, 1000L))
        assertEquals(PhoneAccess.GRANTED, SearchScopeRules.phoneAccessOf(true, SearchScopeRules.tierOf(allFilesAccess = true)))
        assertEquals(
            "造场景：账上两行都必须是有效授予（revokedAt=null）",
            setOf(rootKeyOf(File(homePath)), rootKeyOf(File(phonePath))),
            store.all().filter { it.revokedAt == null }.map { it.absRoot }.toSet(),
        )
        return store
    }

    // ── 第 1＋2 层：逐字相同，且对照组必须能不一样 ─────────────────────────────

    @Test
    fun `硬不变式_读侧授予拉满_写判据输出逐字相同`() {
        val bare = writeOutputs()
        // 读侧三样拉满（意愿＋甲档能力＋两行有效账本）之后，再取一次写判据的输出。
        // 两次之间**只**动了读侧状态——写侧的两个读数还是同一套接法。
        val read = fullReadGrant()
        val afterReadGrant = writeOutputs()
        assertEquals(
            "有只读授予时，写判据 workspaceBoundaryCheck 的输出必须与没授予时逐字相同（派工单 §1⑥）",
            bare,
            afterReadGrant,
        )
        // 三档形状本身也得钉死：否则「两侧一样」可能只是两侧都放行的那种一样。
        assertNull("工作区内必须放行", bare[0])
        assertNull("工作区内·写开关开着也一样放行", bare[1])
        assertNotNull("工作区外·写开关关必须给原文拒绝", bare[2])
        val deniedCase = cases[2]
        assertEquals(
            "原文拒绝用的是调用方传进来的那句 deniedReason，一个字都不许多",
            "拒绝：${rootKeyOf(deniedCase.target)} 在工作区（${rootKeyOf(deniedCase.root)}）外，不许改。",
            bare[2],
        )
        assertTrue("工作区外·没批过必须弹卡（ASK_USER 文案）", bare[3]?.contains("需用户批准") == true)
        assertTrue("别家 App 的私有目录同样先弹卡，不许静默放行", bare[4]?.contains("需用户批准") == true)
        // 读侧那一侧的自查：账本确实拉满了（上面 fullReadGrant 里 assert 过），它没反过来动写侧。
        assertEquals(2, read.all().count { it.revokedAt == null })
    }

    @Test
    fun `对照组_把读侧账本当写侧批准_输出立刻不一致`() {
        // 这条不是生产形态，是**门禁灵敏度**的证据：§1⑥ 那条红线要是被接错一次，
        // 上面那条逐字比对必须当场变红。接错的样子就是把 scope 的有效行喂给 `granted` 口。
        val honest = writeOutputs()
        val read = fullReadGrant()
        // 读侧账本里有一行 HOME＝工作区根本身（丁档那条）。拿它当写侧批准，正好让
        // 「工作区外·该弹卡」那一档变成放行——这就是负例点名的「只读授予让写判据少弹一张卡」。
        val miswired = writeOutputs(read.all().filter { it.revokedAt == null }.map { it.absRoot }.toSet())
        assertNotNull("诚实接法下这一档该弹卡", honest[3])
        assertNull("对照组没接错：把读侧账本喂给写侧 granted 口，这张卡就真的少弹了", miswired[3])
        assertNotEquals("对照组与诚实接法输出不同 ⇒ 上面那条逐字比对不是空跑", honest, miswired)
    }

    @Test
    fun `负例_读侧账本不是写侧的批准_同一个rootKey也不行`() {
        // 两个账本用同一个归一字符串（读侧 rootKey 就是引的写侧那个），省事的做法是「键一样就算批过」。
        // 这条钉死：读侧建行，写侧账本一个字节都不动；反向写侧批一次，读侧账本也不涨行。
        val read = fullReadGrant()
        val root = File(homePath)
        val key = rootKeyOf(root)
        assertTrue("造场景：读侧账本里确有这个根的有效行", read.all().any { it.absRoot == key && it.revokedAt == null })
        WorkspaceGrants.enterSession(7777L)
        try {
            assertFalse("写侧账本必须仍回答「没批过」", WorkspaceGrants.granted(root))
            val before = read.all().size
            WorkspaceGrants.grant(root) // 用户在确认卡上批准一次写操作
            assertTrue("写侧批过之后写侧认账", WorkspaceGrants.granted(root))
            assertEquals("写侧的批准不许在读侧账本里长出第 3 行", before, read.all().size)
            assertEquals(setOf(key, rootKeyOf(File(phonePath))), read.all().map { it.absRoot }.toSet())
            assertEquals(
                "读侧那一行还是它自己：HOME 档、有效、时间戳没被写侧动过",
                listOf(ScopeKind.HOME),
                read.all().filter { it.absRoot == key }.map { it.kind },
            )
        } finally {
            WorkspaceGrants.resetForTest() // 换回内存账本＋清会话身份＋清缓存
        }
    }

    // ── 第 3 层：结构上就没有流进去的口子 ─────────────────────────────────────

    @Test
    fun `结构_写判据的入参里没有读侧的位置`() {
        // 判据能看见的东西只有它的参数与那两个全局读数。参数在 **JVM 字节码**上钉（本仓没有
        // kotlin-reflect，KFunction 那条路跑不起来；字节码这条反而绕不开默认值与重载）。
        // 有人想加第五个入参（比如 searchScopeGranted: Boolean），这条当场红——加的人必须解释。
        assertEquals(
            "workspaceAccessVerdict 在 JVM 上只该是一个四参方法（File,File,boolean,boolean）",
            listOf(listOf("java.io.File", "java.io.File", "boolean", "boolean")),
            jvmSignatures("workspaceAccessVerdict"),
        )
        assertEquals(
            "workspaceBoundaryCheck 同上（File,File,String,String）",
            listOf(listOf("java.io.File", "java.io.File", "java.lang.String", "java.lang.String")),
            jvmSignatures("workspaceBoundaryCheck"),
        )
        // 参数之外，判据的三档结论也不许长出第四个值（「因为读过所以能写」那种结论没地方放）。
        assertEquals(setOf("INSIDE", "ASK_USER", "DENIED"), AccessVerdict.values().map { it.name }.toSet())
        // 三档形状现跑一遍（读侧授予在这张表里没有输入位）：userGranted 恒 false = 没批过写。
        assertEquals(AccessVerdict.INSIDE, workspaceAccessVerdict(File(homePath), File("$homePath/x.md"), userGranted = false, settingOn = false))
        assertEquals(AccessVerdict.ASK_USER, workspaceAccessVerdict(File(homePath), File("$phonePath/x.md"), userGranted = false, settingOn = true))
        assertEquals(AccessVerdict.DENIED, workspaceAccessVerdict(File(homePath), File("$phonePath/x.md"), userGranted = false, settingOn = false))
    }

    /** `WorkspaceAccess.kt` 编译出来的文件类里，某个顶层方法的全部 JVM 签名（internal 会被改名，按前缀收）。 */
    private fun jvmSignatures(fn: String): List<List<String>> {
        val cls = Class.forName("com.example.zhengdao.baopu.WorkspaceAccessKt")
        return cls.declaredMethods
            .filter { (it.name == fn || it.name.startsWith("$fn\$$")) && !it.name.contains("\$default") }
            .map { m -> m.parameterTypes.map { it.name } }
    }

    @Test
    fun `结构_写侧只有两个全局读数_第三个就是后门`() {
        val readings = WorkspaceAccessPolicy::class.java.declaredFields
            .filter { it.type.name.startsWith("kotlin.jvm.functions") }
            .associate { it.name to it.type.simpleName }
        assertEquals(
            "WorkspaceAccessPolicy 只许有「写侧开关」（Function0）＋「写侧本会话授权」（Function1）两个读数；" +
                "冒出第三个就是给读侧开了通往写判据的后门",
            mapOf("settingOn" to "Function0", "granted" to "Function1"),
            readings,
        )
    }

    @Test
    fun `结构_写侧一片都不引读侧`() {
        val forbidden = listOf(
            "SearchScope", "SearchAdmission", "SearchDb", "SearchSql", "SearchScopeSettings",
            "baopu.search", "baopu/search", "search.db", "PhoneAccess", "ReadTier", "ScopeKind",
        )
        // 写侧判据本体（派工单点名不许碰的三个）＋ 装配层（读侧状态唯一可能被接进去的地方）。
        val writeSide = listOf(
            "src/main/java/com/example/zhengdao/baopu/WorkspaceAccess.kt",
            "src/main/java/com/example/zhengdao/baopu/WorkspaceGrant.kt",
            "src/main/java/com/example/zhengdao/baopu/Guard.kt",
            "src/main/java/com/example/zhengdao/rootfs/PathGuard.kt",
            "src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt",
        )
        writeSide.forEach { rel ->
            val text = code(repoFile(rel).readText())
            forbidden.forEach { f ->
                assertFalse("$rel 出现「$f」：写判据一旦开始问「这个根能不能读」，读授权就变成写授权了", text.contains(f))
            }
        }
    }

    @Test
    fun `结构_读侧只被允许引这些baopu包根符号`() {
        val allowed = setOf(
            "com.example.zhengdao.baopu.BaopuWorkspace", // 工作区根在哪（丁档要知道根是不是私有目录）
            "com.example.zhengdao.baopu.WorkspaceGrantRules", // 只用它的 rootKey：两边同一个字符串
            // ↓ 第一百九十八批（S3）加的四条，全是「同一把尺子第二个消费者」，没有一条是判据：
            "com.example.zhengdao.baopu.MemoryRules", // tokensOf：切词不许另造一把
            "com.example.zhengdao.baopu.hasNulByte", // 前 8KB 有 NUL：与产物白名单同一个判据
            "com.example.zhengdao.baopu.agent.TextDecode", // 编码嗅探／解码：与 file_info 同一套
            "com.example.zhengdao.baopu.agent.FileInfoTool", // guessEncoding 的判序（BOM 先于 NUL）
            "com.example.zhengdao.baopu.library.LibraryPaths", // classify/TEXT_EXTS/2 MiB：认领口径的真源
        )
        // 每条新许可钉死在**哪个文件**里出现（照下面 mkdirs 那两条例外的房型）：
        // 「复用一把尺子」变成「六个文件都能随便引」就是这份白名单将来漂的样子。
        val onlyIn = mapOf(
            "com.example.zhengdao.baopu.MemoryRules" to "PhoneRank.kt",
            "com.example.zhengdao.baopu.hasNulByte" to "PhoneText.kt",
            "com.example.zhengdao.baopu.agent.TextDecode" to "PhoneText.kt",
            "com.example.zhengdao.baopu.agent.FileInfoTool" to "PhoneText.kt",
            "com.example.zhengdao.baopu.library.LibraryPaths" to "PhoneSearch.kt",
        )
        searchSources().forEach { (name, text) ->
            val fromBaopuRoot = text.lines()
                .map { it.trim() }
                .filter { it.startsWith("import com.example.zhengdao.baopu.") }
                .map { it.removePrefix("import ").trim() }
                .toSet()
            assertTrue(
                "$name 引了 baopu 包根的「$fromBaopuRoot」：读侧不许引写侧判据" +
                    "（WorkspaceAccessPolicy／AccessVerdict／workspaceBoundaryCheck／WorkspaceGrants 都不在允许清单里）",
                fromBaopuRoot.all { it in allowed },
            )
            onlyIn.forEach { (sym, owner) ->
                if (sym in fromBaopuRoot) {
                    assertEquals("$name 引了「$sym」：这条复用只许写在 $owner 里", owner, name)
                }
            }
            listOf("WorkspaceAccessPolicy", "AccessVerdict", "workspaceBoundaryCheck", "workspaceAccessVerdict", "WorkspaceGrants.")
                .forEach { sym ->
                    assertFalse("$name 出现写侧判据符号「$sym」", code(text).contains(sym))
                }
        }
        // `terminal.DocxTextExtractor` 不在这张表里（不在 `baopu.` 包根下，上面的 filter 抓不到它），
        // 但它同样是「第二个消费者」⇒ 单独钉一处：只许 PhoneText.kt 引。
        searchSources().forEach { (name, text) ->
            if (text.contains("import com.example.zhengdao.terminal.DocxTextExtractor")) {
                assertEquals("$name 引了 docx 抽取器：只许 PhoneText.kt 一处", "PhoneText.kt", name)
            }
        }
    }

    // ── §2 负例：搜索权限删不了文件 ／ 查询词进不了库与日志 ────────────────────

    @Test
    fun `负例_搜索权限删不了文件_读侧一片都不写盘不联网不抬权限`() {
        val forbidden = listOf(
            "deleteRecursively", ".delete(", ".renameTo", ".createNewFile", ".createTempFile",
            "writeText(", "writeBytes(", "FileOutputStream", "FileWriter", "RandomAccessFile",
            "Files.write", "Files.newBufferedWriter", "Files.copy", "Files.move", "Files.delete",
            "MediaStore", "ContentResolver", "ContentUris",
            "Runtime.getRuntime", "getRuntime().exec", "ProcessBuilder",
            "java.net.", "HttpURL", "okhttp", "Socket(",
            "ACTION_MANAGE_APP_ALL_FILES", "startActivity", "startActivityForResult",
        )
        searchSources().forEach { (name, text) ->
            val c = code(text)
            forbidden.forEach { f ->
                assertFalse(
                    "$name 出现「$f」：读侧只放开「能读到什么」。授予读就能改/删/上传文件，" +
                        "或者读侧自己抬权限（申请入口主页已有：HomeScreen.kt:179），都是本批红线",
                    c.contains(f),
                )
            }
            // 两处「看着像写盘」的例外，逐处钉死位置，别让它们在别的文件里也合法：
            if (c.contains("mkdirs")) assertEquals("$name 里有 mkdirs：只许 SearchDb.kt 为自己的库目录建父目录", "SearchDb.kt", name)
            if (c.contains("writableDatabase")) assertEquals("$name 里开写库：读侧的索引缓存只许 SearchDb.kt 一处", "SearchDb.kt", name)
        }
        // 读侧唯一的写面就是那张可整包丢弃的索引缓存；主库与用户文件一概不碰。
        assertFalse(SearchSql.DDL.any { it.contains("BaopuDb") })
        assertEquals("search.db", SearchDb.DB_NAME)
    }

    @Test
    fun `负例_查询词只进绑定参数_不进日志也不进SQL文本`() {
        // 第一百九十七批时读侧「压根不接查询词」，所以那门禁的是参数名；第一百九十八批（S3）
        // 接了——唯一的口是 `PhoneSearch.query(ctx, text, topK)`。所以这条从「不许接」改判成
        // **「接了不许留」**：用户那几个字只许活在两处——函数入参和 SQL 的绑定参数，
        // 三处持久面（SQL 文本／RunLog／库里的列）一处都不能出现它。
        // 派工单 §2 点名的负例「把查询词写进 err／写进日志」在这一条里逐层钉。
        val queryWords = listOf("query", "keyword", "keywords", "phrase", "pattern", "regex")
        searchSources().forEach { (name, text) ->
            val c = code(text)
            signatures(c).forEach { (fn, params) ->
                queryWords.forEach { w ->
                    assertFalse(
                        "$name 的 `fun $fn($params)` 接了查询词一类的东西「$w」：" +
                            "S3 的口径是入参叫 text（原文）或 terms/tokens（切好的词），" +
                            "名字里带 query/keyword 就说明有人打算把原文一路带下去",
                        params.containsWord(w),
                    )
                }
            }
            c.lines().filter { it.contains("RunLog.log(") }.forEach { line ->
                queryWords.forEach { w ->
                    assertFalse("日志行插了查询词一类的东西：「$line」", line.containsWord(w))
                }
            }
        }
        // 建索引的语句只接「词的个数」，不接词本身（`selectSearch(tokenCount, limit)`）。
        assertTrue(
            "selectSearch 的参数必须是 tokenCount（个数），不是词串",
            signatures(code(sourceOf("SearchSql.kt"))).any { (fn, params) ->
                fn == "selectSearch" && params.contains("tokenCount") && !params.containsWord("query")
            },
        )
        // 占位符生成器只许产出 `?`、逗号、空格——多一个字符就是有人在拼词进 SQL 文本。
        (1..SearchSql.MAX_TOKENS).forEach { n ->
            val ph = SearchSql.placeholders(n)
            assertEquals("placeholders($n) 只该是问号与分隔符", true, ph.all { it == '?' || it == ',' || it == ' ' })
            assertEquals("placeholders($n) 的问号个数", n, ph.count { it == '?' })
        }
        // 唯一接原文的口：`PhoneSearch.query` 的第一手动作必须是切词（`PhoneRank.termsOf`），
        // 而且 `text` 这个变量在函数体里只许出现一次——第二次出现就是原文在下游漂。
        val phoneSearch = code(sourceOf("PhoneSearch.kt"))
        val queryFn = Regex("(?m)\\bfun query\\(([^)]*)\\)[^{]*\\{([\\s\\S]*?)\\n    }").find(phoneSearch)
        assertNotNull("PhoneSearch 里必须还是那个 `fun query(...)`（改了形状就得回来解释这条门禁）", queryFn)
        val body = queryFn!!.groupValues[2]
        assertTrue("query 的第一手动作必须是 PhoneRank.termsOf（原文只到切词为止）", body.contains("PhoneRank.termsOf(text)"))
        assertEquals(
            "`text`（用户那几个字）在 query 里只许被用一次：多一次就是有人把它往日志或库里带",
            1,
            Regex("\\btext\\b").findAll(body).count(),
        )
        assertFalse("query 一个字都不进日志", body.contains("RunLog.log("))
        // 切好的词只进绑定数组：`PhoneIndex.search` 的参数是 tokens，语句里只有占位符。
        val idx = code(sourceOf("PhoneIndex.kt"))
        assertTrue("PhoneIndex.search 接的是 tokens（切好的词），不是原文", signatures(idx).any { (fn, p) -> fn == "search" && p.contains("tokens") })
        // ⚠️ Android 的 `SQLiteException` 文本会**抄绑定参数**：这个文件是唯一往库里绑 token 的地方，
        // 所以它的任何一行日志都不许插异常变量（`$e`／`$it`／`$err`）——抄出来的就是用户搜的那些字。
        idx.lines().filter { it.contains("RunLog.log(") }.forEach { line ->
            listOf("\$e", "\$it", "\$err", "throwable", "message", "localizedMessage").forEach { leak ->
                assertFalse("PhoneIndex 的日志行插了异常读数「$leak」（异常文本会抄绑定参数）：「$line」", line.contains(leak))
            }
        }
        assertTrue("造场景：PhoneIndex 确实有日志行（没有的话上面那半条就是空跑）", idx.lines().any { it.contains("RunLog.log(") })
    }

    @Test
    fun `结构_读侧开关的键名只有一处_与写侧那个开关不串`() {
        // 读侧开关（search_phone_on）与写侧开关（允许访问工作区外）是两个键：
        // 串了就是一个开关管两件事——拨「读」就把「写」也放开了。
        val settings = code(sourceOf("SearchScopeSettings.kt"))
        assertTrue("写口的键名必须是 search_phone_on", settings.contains("const val KEY_PHONE_ON = \"search_phone_on\""))
        assertTrue("缺省必须是 false（没点头就不读）", settings.contains("getBoolean(KEY_PHONE_ON, false)"))
        searchSources().forEach { (name, text) ->
            if (name != "SearchScopeSettings.kt") {
                assertFalse("$name 自己声明了一个 prefs 键：开关只能有一个地方被读", code(text).contains("KEY_"))
            }
        }
        // 写侧那个开关（`baopu_allow_outside`，默认**开**）的键名不许出现在读侧：
        // 两边各管各的——读侧默认关，写侧默认开，串了之后一个开关同时改两条边界，缺省值都反了。
        val wiring = code(repoFile("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt").readText())
        assertTrue("写侧开关的键名应当还是 baopu_allow_outside（本条是对账锚，它漂了上面那条就没意义）", wiring.contains("KEY_ALLOW_OUTSIDE = \"baopu_allow_outside\""))
        searchSources().forEach { (name, text) ->
            listOf("baopu_allow_outside", "KEY_ALLOW_OUTSIDE").forEach { k ->
                assertFalse("$name 引用了写侧开关的键「$k」：一个开关不许同时管读和写", code(text).contains(k))
            }
        }
    }

    // ── helper ──────────────────────────────────────────────────────────────

    /** 函数签名（名字 → 参数表文本）。带默认值的嵌套括号按原样留下，够本门禁做词表比对。 */
    private fun signatures(codeText: String): List<Pair<String, String>> =
        Regex("(?m)\\b(?:internal |private |public |override |protected )*fun (\\w+)\\s*\\(([^)]*)\\)")
            .findAll(codeText)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    /**
     * 参数表/日志行里是否出现了查询词一类的词。
     *
     * 故意用**子串**而不是完整标识符：`searchQuery: String` 也必须被抓（按词边界就漏了）。
     * 代价是 `inquiry` 这种名字会误伤——误伤的方向是「逼人来解释」，不是放行，可以接受。
     */
    private fun String.containsWord(word: String): Boolean = this.lowercase().contains(word)

    private fun sourceOf(name: String): String = repoFile("src/main/java/com/example/zhengdao/baopu/search/$name").readText()

    /** 从工作目录逐级向上找仓库里的相对路径（连 Gradle 把 `app/` 当工作目录的情形也兜住）。 */
    private fun repoFile(rel: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf(File(dir, "app/$rel"), File(dir, rel)).firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
    }

    private fun searchSources(): List<Pair<String, String>> {
        val dir = repoFile("src/main/java/com/example/zhengdao/baopu/search")
        val files = dir.listFiles { fd: File -> fd.isFile && fd.extension == "kt" }?.toList() ?: emptyList()
        assertTrue("baopu/search 里没有源文件＝门禁失效", files.isNotEmpty())
        return files.map { it.name to it.readText() }
    }

    /** 剥注释（行注释＋块注释）：源码与本文件都在大量引用那些「不许出现」的符号名，不剥就自己判红。 */
    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")
}
