// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §1⑥ 的 ⑥①⑥②⑥③ 三条缓办的正面交代）：**读侧账本的入口门禁**。
//
// ## ⑥① 的裁定：不加自动同步，改成把「谁在什么时候能改账本」钉成可核对的话
// S2 交付时装配层（`ZhengdaoApp`／`MainActivity`／`BaopuRuntime`／`HomeScreen`）没有自动同步，
// 派工单问「要不要加」。裁定是**不加**，理由不是省事：那张 `scope` 表是读侧唯一的授权真相，
// 加了自动同步就等于让「什么时候多出一次授予」由运行时决定，而不是由用户的一次动作决定。
// 不加的代价是「开关早就开着但一次没扫过」的设备会扫到 0 个根 ⇒ 补偿是**扫描前先拨一次账本**
// （`BaopuPhoneSearchCard` 的 `syncLedgerLine`，用户点「扫描本机／重建索引」那一刻）。
//
// 于是「谁在什么时候能改读侧账本」变成两句能在源码里数出来的话，本文件就把这两句数出来：
// - 写账本的语句只有一个入口函数（[SearchScope.sync]），它的调用点恰好一个文件
//   （第二百二十九批起：设置页那个入口随开关一起删了）；
// - 旧设置口（`SearchScopeSettings`）零调用——开关删了，判定不许再读它。
// 结构门禁那一条（`SearchReadNeverGrantsWriteTest`）管的是「读不许变成写」；
// **这一条管的是「读侧账本自己什么时候被动」**——两回事，都要钉。
//
// ## ⑥② MEDIA 留给 S4
// 本批产出的一条都不许是媒体档：入账白名单只有 HOME/PHONE，`MEDIA` 只活在 DDL 的 CHECK 里
// （形状照单，不是「已经支持」）。这里用全仓文本对账，防止有人顺手往白名单里加一档。
//
// ## ⑥③ 那条日志要能被真机看懂
// 验收要求（S2 那条原话）：「真机第一次拨开关时，把日志那行『账本新增 N 行/撤销 M 个根』截图给我」。
// 一句给真机看的话必须先在 JVM 里能被断言 ⇒ 文案收在纯函数 [SearchScopeRules.syncLine]，
// 本文件把它的六种形状逐条打表（包括「首次建库」那一截——它只在库之前不存在时才出现）。
package com.example.zhengdao.baopu.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSearchEntryGateTest {

    // ── ⑥① 调用点集合：账本什么时候被动 ────────────────────────────────────────

    @Test
    fun `⑥①_写账本的调用点恰好一个手动入口`() {
        // 第二百二十九批：设置页那个入口随开关一起删了——扫描卡「扫描本机／重建索引」前
        // 的对账（syncLedgerLine）是唯一的手动入口。
        val hits = sourcesContaining("SearchScope.sync(")
        assertEquals(
            "读侧账本只有一个写入口，调用点必须数得出来：多了就是长出第二个入口（自动同步就是这么混进来的）",
            mapOf(
                "PhoneSearch.kt" to 1, // `syncNow`：门面对 UI 的那一层，转调而已
            ),
            hits,
        )
    }

    @Test
    fun `负例_开关没了_旧设置口零调用`() {
        // 第二百二十九批负例「删了 UI 仍读开关」：`set(` 早已删除，`get(`（兼容读口）
        // 也必须**零调用**——判定路径只认系统授权，老 prefs 的值不再产生任何效果。
        assertEquals("写口必须已删干净", emptyMap<String, Int>(), sourcesContaining("SearchScopeSettings.set("))
        assertEquals(
            "兼容读口不许有任何调用方（有人想靠老 prefs 里的 false 把范围缩回去，这里当场红）",
            emptyMap<String, Int>(),
            sourcesContaining("SearchScopeSettings.get("),
        )
    }

    @Test
    fun `⑥①_门面调用点只在搜索卡`() {
        // 扫描／检索／现状读数：只有搜索卡会点（229 起设置页不再有任何读侧调用）
        mapOf(
            "PhoneSearch.scan(" to setOf("BaopuPhoneSearchCard.kt"),
            "PhoneSearch.query(" to setOf("BaopuPhoneSearchCard.kt"),
            "PhoneSearch.snapshot(" to setOf("BaopuPhoneSearchCard.kt"),
            "PhoneSearch.syncNow(" to setOf("BaopuPhoneSearchCard.kt"),
            "PhoneSearch.dbExists(" to setOf("BaopuPhoneSearchCard.kt"),
        ).forEach { (call, where) ->
            assertEquals("「$call」的调用点集合漂了：读侧的扫描与检索只能由用户点按钮起", where, sourcesContaining(call).keys)
        }
    }

    @Test
    fun `⑥①_装配层一个字都不引读侧`() {
        // 这四片是「App 一起来就跑」的那几处；它们不认识读侧 ⇒ 启动路径上不可能有自动同步。
        listOf(
            "src/main/java/com/example/zhengdao/ZhengdaoApp.kt",
            "src/main/java/com/example/zhengdao/MainActivity.kt",
            "src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt",
            "src/main/java/com/example/zhengdao/ui/HomeScreen.kt",
        ).forEach { rel ->
            val text = code(repoFile(rel).readText())
            listOf("PhoneSearch", "SearchScope", "SearchDb", "baopu.search", "baopu/search").forEach { sym ->
                assertFalse("$rel 出现「$sym」：装配层一接读侧，「谁改的账本」就再也不是两句能数出来的话了", text.contains(sym))
            }
        }
        // 反证这四片没被我路径写错而变成空跑
        listOf("ZhengdaoApp.kt", "MainActivity.kt", "BaopuRuntime.kt", "HomeScreen.kt").forEach {
            assertTrue("找不到 $srcRoot/$it（改了名就要回来更新这份名单）", sources().any { f -> f.name == it })
        }
    }

    @Test
    fun `⑥①_读侧的可见面只有搜索卡那一片UI`() {
        // 闭世界对账：全仓（只算 src/main）**认识**门面与账本的文件，除 search 包自己之外只许搜索卡一片
        // （设置页那片仍**实例化**搜索卡——它只是不再碰账本，所以仍在许可名单里，但只许这一处）。
        // 方向要说清：`allowed` 是「许可名单」，不是「与命中集相等」——包里有些文件（PhoneRank 那种）
        // 本来就不提门面，硬比相等会把它们判成违规。
        val pkg = sourcesInSearchPackage()
        assertTrue("search 包一个文件都没数到（路径漂了 ⇒ 下面那条闭世界就成了真空）", pkg.contains("PhoneSearch.kt"))
        val allowed = pkg.toSet() + setOf("BaopuPhoneSearchCard.kt", "BaopuSettingsScreen.kt")
        val outsiders = sources().filter { code(it.readText()).let { t -> t.contains("PhoneSearch") || t.contains("SearchScope") } }
            .map { it.name }.toSet() - allowed
        assertEquals(
            "读侧的引用面长出了 search 包与搜索卡之外的文件：入口集合是 ⑥① 的裁定，加一处就要回来重新数一遍",
            emptySet<String>(),
            outsiders,
        )
    }

    // ── ⑥② MEDIA 留给 S4：本批一条都不产出 ──────────────────────────────────────

    @Test
    fun `⑥②_本批入账的档只有HOME与PHONE`() {
        assertEquals("白名单就是这两档", setOf(ScopeKind.HOME, ScopeKind.PHONE), SearchScopeRules.KINDS_THIS_BATCH)
        assertFalse("MEDIA 进了白名单：乙档要的是 `READ_MEDIA_*` 权限与 S4 的分流，本批一条都不该产出",
            ScopeKind.MEDIA in SearchScopeRules.KINDS_THIS_BATCH)
        // 全仓文本对账：`MEDIA` 这个字面量只许出现在「形状照单」的那几处（enum 定义 + DDL 的 CHECK）
        val where = sources().mapNotNull { f ->
            val t = code(f.readText())
            val n = Regex("\\bMEDIA\\b").findAll(t).count()
            if (n > 0) f.name to n else null
        }.toMap()
        assertEquals(
            "MEDIA 只在 enum 与建表 CHECK 里存在（第二个消费者出现＝有人在没拿权限的情况下记媒体档）",
            mapOf("SearchScope.kt" to 2, "SearchSql.kt" to 1),
            where,
        )
    }

    @Test
    fun `⑥②_scope的CHECK留三档是给S4的形状不是本批的能力`() {
        val ddl = SearchSql.DDL.first { it.contains("CREATE TABLE IF NOT EXISTS scope(") }
        assertTrue("CHECK 必须已经容得下 MEDIA（否则 S4 落地要改 DDL ⇒ 又要升一次库）", ddl.contains("CHECK (kind IN ('HOME', 'PHONE', 'MEDIA'))"))
        // 反向：本批代码写进 scope.kind 的取值只有两档（由 [SearchScopeRules.KINDS_THIS_BATCH] 把关）
        val sync = code(repoFile("src/main/java/com/example/zhengdao/baopu/search/SearchScope.kt").readText())
        assertTrue("入账处必须拿白名单过滤（不是「枚举里有就能记」）", sync.contains("KINDS_THIS_BATCH"))
    }

    // ── ⑥③ 那条日志要能被真机看懂 ──────────────────────────────────────────────

    @Test
    fun `⑥③_账本同步那行的六种形状逐条对账`() {
        // 「账本新增 N 行/撤销 M 个根」这句是要截图的 ⇒ 它的每个数字都必须在 JVM 里钉得住。
        // 第二百二十九批：开关没了，这句话从「拨开关」改判成「账本同步」（扫描前的那次对账）。
        val line = SearchScopeRules.syncLine(
            access = PhoneAccess.GRANTED, recorded = 7, revokedRoots = 2, orphanRoots = 0, dbFirstBuild = false,
        )
        assertEquals(
            "授权已给：这句话要说清动了几个根，并且明说「只读」与「写不放大」",
            "搜索范围：手机全盘 = 已纳入（只读；账本新增 7 行、撤销 2 个根；写文件不跟着放大：只限当前工作区与 Download/证道）",
            line,
        )
        listOf(
            Triple(
                "新增 0 行也要说出来（授权没给时一个根没入账，是最需要看得懂的一次）",
                SearchScopeRules.syncLine(PhoneAccess.NO_PERMISSION, 0, 0, 0, false),
                "搜索范围：手机全盘 = 未纳入（只读；账本新增 0 行、撤销 0 个根；系统还没给「所有文件访问」，所以手机档一行都没入账；写文件不跟着放大：只限当前工作区与 Download/证道）",
            ),
            Triple(
                "首次建库要单独说一句（⑥③ 的本体：这一句只在库之前不存在时出现）",
                SearchScopeRules.syncLine(PhoneAccess.GRANTED, 3, 0, 0, true),
                "搜索范围：手机全盘 = 已纳入（只读；账本新增 3 行、撤销 0 个根；这是首次建库：search.db 这个文件是这一刻才被建出来的；写文件不跟着放大：只限当前工作区与 Download/证道）",
            ),
            Triple(
                "撤销的根还留着索引行 ⇒ 必须带 ⚠（这是隐私事故，不许埋在计数里）",
                SearchScopeRules.syncLine(PhoneAccess.GRANTED, 0, 2, 1, false),
                "搜索范围：手机全盘 = 已纳入（只读；账本新增 0 行、撤销 2 个根；⚠ 撤销的根里还有 1 个留着索引行（要立刻重建索引）；写文件不跟着放大：只限当前工作区与 Download/证道）",
            ),
            // 反面：首次建库那句不许在「库早就在了」的时候冒出来（那会把老用户吓成「刚建的索引不可信」）
            Triple(
                "不是首次建库就不提 search.db",
                SearchScopeRules.syncLine(PhoneAccess.GRANTED, 1, 0, 0, false),
                "搜索范围：手机全盘 = 已纳入（只读；账本新增 1 行、撤销 0 个根；写文件不跟着放大：只限当前工作区与 Download/证道）",
            ),
        ).forEach { (title, actual, want) -> assertEquals(title, want, actual) }
    }

    @Test
    fun `⑥③_这句话只在真动了账本的时候说`() {
        // 唯一调用点（扫描卡）：先问「库之前在不在」，再 sync，再组这句话。
        val askFirst = "val firstBuild = !PhoneSearch.dbExists(ctx)"
        val rel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuPhoneSearchCard.kt"
        val t = code(repoFile(rel).readText())
        assertTrue("$rel 没有先问「库之前在不在」再 sync：那一步晚了，「首次建库」永远是假话", t.contains(askFirst))
        assertTrue("$rel 没走 syncLine：文案散在 UI 里就没法在 JVM 里断言", t.contains("SearchScopeRules.syncLine("))
        // 扫描那一趟：动了账本才占界面一行（没动就不刷）；日志那边一条不落地说（验收要看的就是它）
        assertTrue("扫描前的账本句要先写进 RunLog 再决定给不给界面看", t.contains("RunLog.log(line)"))
        assertTrue("没动账本就不占界面那一行", t.contains("if (rep.isNoop) null else line"))
        // 扫之前库在不在 ⇒ 扫描那行日志要能自证「本次是首次建库」
        val shell = code(repoFile("src/main/java/com/example/zhengdao/baopu/search/PhoneSearch.kt").readText())
        assertTrue("扫描日志必须带「首次建库」那一截", shell.contains("\"（首次建库：扫之前 search.db 这个文件不存在）\""))
        assertTrue("库文件是不是在，问的是那个文件本身（不是「能不能打开」）",
            shell.contains("SearchDb.dbPathUnder(ctx.filesDir).isFile"))
    }

    // ── helper（房型同 `ChannelSwitchTruthGateTest`：全仓扫源码用 walkTopDown） ────

    private val srcRoot: String = "src/main/java"

    /** `needle` 在哪些生产文件里出现、各几次（注释不算：文案里引用判据名是常事）。 */
    private fun sourcesContaining(needle: String): Map<String, Int> =
        sources().mapNotNull { f ->
            val n = code(f.readText()).split(needle).size - 1
            if (n > 0) f.name to n else null
        }.toMap()

    /** search 包（读侧内核）里的文件名——闭世界对账时它们是「自己人」。 */
    private fun sourcesInSearchPackage(): List<String> =
        repoFile("src/main/java/com/example/zhengdao/baopu/search").listFiles()?.filter { it.extension == "kt" }?.map { it.name } ?: emptyList()

    private fun sources(): List<File> =
        repoFile(srcRoot).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun repoFile(rel: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf(File(dir, "app/$rel"), File(dir, rel)).firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
    }
}
