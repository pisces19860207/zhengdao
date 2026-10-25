// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十八批（派工单 §2 的「查询词进日志」「搜索权限删不了文件」两条负例，UI 半边）：
// **搜索卡片的隐私与写面门禁**。
//
// ## 为什么再来一份（S2 不是已经钉过隐私线了吗）
// `SearchReadNeverGrantsWriteTest` 扫的是 `src/main/java/…/baopu/search/` 那一片——判据层与薄壳。
// 而用户那几个字（`input`）**诞生在 UI**，从 UI 出发有四条能走歪的路：
// 进日志、进 `SharedPreferences`、进异常文本、或者干脆由卡片自己拼一条 SQL。
// 这四条 S2 那份门禁一条都看不见（文件不在它的扫描范围里）。本文件补的就是这一段。
// 反过来，S2 那条**按行**筛 `RunLog.log(` 的门禁看不见跨行日志的续行，而 `terms` 恰恰只在下发到
// 门面这一趟里以变量的形式活着——所以 [负例_查询词不进日志_壳半边] 按括号配平取整条参数表，
// 再叠一层「日志只许插哪几样读数」的白名单。**两半边合起来才是 §2 那一条负例的完整交付**。
//
// ## 卡片唯一的写面
// 「复制进证道」（§1⑤）：把命中的那篇文件逐字节拷进 `投递/`。它是本批读侧链条上**唯一**会落盘的
// 动作，由用户点一下才做，写的是证道自己的目录。所以这份门禁不是「卡片一个字都不许写」，
// 而是「写只许发生在 `copyIntoDeliver` 这一个函数里，其他任何函数一律零写面」——
// 这样「读侧能不能改/删用户文件」这句验收话，答案是一个函数名，不是一次运行时观察。
package com.example.zhengdao.baopu.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSearchPrivacyGateTest {

    // ── 1. 查询词：只到切词为止，不进日志、不进盘、不进异常 ────────────────────────

    @Test
    fun `负例_查询词不进日志`() {
        val lines = CARD.lines().filter { it.contains("RunLog.log(") }
        assertTrue("造场景：卡片确实有一行日志（没有的话上面那半条就是空跑）", lines.isNotEmpty())
        lines.forEach { line ->
            listOf("input", "text", "query", "keyword", "terms", "phrase", "pattern").forEach { w ->
                assertFalse("日志行插了查询词一类的东西「$w」：「${line.trim()}」", line.containsWord(w))
            }
        }
        // 那一行日志装的是什么：账本动了几个根（⑥③ 要截图的那句），与用户搜的字无关
        assertEquals("卡片只有一处写日志", 1, lines.size)
        assertTrue("唯一那行日志写的是 syncLine（账本读数），不是内容", lines[0].contains("RunLog.log(line)"))
    }

    @Test
    fun `负例_查询词不落盘`() {
        val src = code(CARD)
        listOf(
            "putString(", "getSharedPreferences(", ".edit()", "writeText(", "writeBytes(",
            "FileOutputStream", "Files.write", "MediaStore", "ContentResolver", "insert(",
        ).forEach {
            assertFalse("卡片出现「$it」：查询词一落盘，「不保存搜索记录」这句承诺就成了假话", src.contains(it))
        }
        // 输入框那几个字的全部去处（数出来就五类：声明／绑定／判空／切词／进门面）——多一条就是有人把它往别处带
        val uses = src.lines().filter { Regex("\\binput\\b").containsMatchIn(it) }
        assertTrue("input 的使用点比预想的多：${uses.map { it.trim() }}", uses.size <= 9)
        uses.forEach { l ->
            val t = l.trim()
            assertTrue(
                "input 出现在了不该出现的地方：「$t」（允许：state 声明／判空／切词／传给门面／传给 OutlinedTextField）",
                t.startsWith("var input by remember") || t.contains("input.isBlank()") || t.contains("input.isNotBlank()") ||
                    t.contains("PhoneRank.termsOf(input)") || t.contains("searchOutcomeOf(ctx, input)") ||
                    t == "value = input," || t.startsWith("onValueChange = { input = it }"),
            )
        }
    }

    @Test
    fun `负例_异常文本不进日志`() {
        // Android 的 `SQLiteException` 会把绑定参数抄进 message；卡片这边绑的是内容切出来的 token，
        // 一旦把异常打出去，日志里就是用户搜的那些字（与 `PhoneIndex` 同一条纪律，两处都要钉）。
        listOf("\$e", "\$it", "\$err", "throwable", "localizedMessage").forEach { leak ->
            CARD.lines().filter { it.contains("RunLog.log(") }.forEach { line ->
                assertFalse("日志行插了异常读数「$leak」：「${line.trim()}」", line.contains(leak))
            }
        }
        // runCatching 的默认值必须是「没结果」，不许是「猜一个」
        assertTrue("台账查询失败 ⇒ 当没这一行（退回路径③）", CARD.contains(".getOrDefault(0L)"))
        // 读侧内核里**绑内容词的那两个文件**同样不许出现异常读数：`selectSearch` 与 `putShard` 绑的是
        // 从文件内容切出来的 token，`SQLiteException.message` 会把它们抄进 message ⇒ 一个裸的异常插值
        // 就把用户查过／索引里的字写进 `run_log`。判据按**括号配平**取整段参数表，不按行。
        // 包里其余文件（`SearchDb`/`SearchScope`）只绑路径与计数，那几条异常插值是 197 先写的文案，
        // 本批不替它们改（也不许别人把它们当成「内核可以打异常」的先例）。
        listOf("PhoneIndex.kt", "PhoneSearch.kt").forEach { want ->
            val raw = searchSources().first { it.first == want }.second
            logArguments(code(raw)).forEach { arg ->
                interpolations(arg).forEach { expr ->
                    assertFalse(
                        "$want 的一行日志插了异常读数「$expr」：绑内容词的那两个文件只许写计数、档位名与根路径",
                        expr in setOf("e", "it", "err", "th", "t") || expr.startsWith("e.") ||
                            expr.startsWith("it.") || expr.contains("message"),
                    )
                }
            }
        }
    }

    @Test
    fun `负例_查询词不进日志_壳半边`() {
        // 派工单 §2 那条负例的正主是**门面**：`scan(terms = …)` 是查询词在这一批里唯一一次以变量存在，
        // 它离日志只差一个 `${}`。S2 那份 `SearchReadNeverGrantsWriteTest` 已经钉过「参数名与日志行不许
        // 带 query/keyword」，但它是**按行**筛 `RunLog.log(` 的——一行装不下的那条长日志（本文件
        // PhoneSearch.kt:235 起，六个字段跨四行）只有首行被看过，续行漏检。
        // 所以这里按**括号配平**取整条参数表：查红的是「这一次日志调用真正写了什么」，不是「哪一行含有 RunLog」。
        val words = listOf("terms", "tokens", "token", "text", "title", "body", "keyword", "query", "phrase", "pattern", "content")
        var seen = 0
        searchSources().forEach { (name, raw) ->
            logArguments(code(raw)).forEach { arg ->
                seen++
                words.forEach { w ->
                    assertFalse(
                        "$name 的一行日志把内容变量「$w」抄了进去：「${arg.trim().replace(Regex("\\s+"), " ").take(120)}」" +
                            "（查询词只许到切词为止，日志只许有计数、档位名与根路径）",
                        Regex("\\b$w\\b").containsMatchIn(arg),
                    )
                }
            }
        }
        // 反证不是空跑：门面那几行对账日志确实被扫到了（本批「扫了多少／为什么停」就靠它们）
        assertTrue("造场景：读侧内核至少有三行日志（扫到的是一条都没）", seen >= 3)
        // 词表是**下限**：光靠禁词挡不住「换个变量名再抄一遍」。所以第二层是**白名单**——
        // 日志里的每一个插值必须从这几样读数里取（计数／档位名／根路径），别人一律不算。
        val prefixes = listOf("report.", "root.kind", "root.rootId", "mw.", "d.kind", "d.absRoot", "v.absRoot")
        val bare = setOf("rootId", "n", "e", "it", "docsGone", "marked")
        searchSources().forEach { (name, raw) ->
            logArguments(code(raw)).forEach { arg ->
                interpolations(arg).forEach { expr ->
                    assertTrue(
                        "$name 的一行日志插了白名单外的读数「$expr」：日志只许写计数、档位名与根路径" +
                            "（新读数要进来先想清楚它是不是内容——是内容就不该进日志）",
                        prefixes.any { expr.startsWith(it) } || expr.trimEnd() in bare || expr.trimEnd().removeSuffix("!!") in bare,
                    )
                }
            }
        }
    }

    // ── 2. 写面：只有 copyIntoDeliver 一个函数 ────────────────────────────────────

    @Test
    fun `负例_搜索权限删不了文件`() {
        val forbidden = listOf(
            "deleteRecursively", ".delete(", ".renameTo", ".createNewFile", ".createTempFile",
            "writeText(", "writeBytes(", "FileOutputStream", "RandomAccessFile",
            "Files.copy", "Files.move", "Files.delete",
            "Runtime.getRuntime", "ProcessBuilder", "java.net.", "HttpURL", "okhttp",
            "ACTION_MANAGE_APP_ALL_FILES", "startActivity", "FileProvider", "Intent",
        )
        // 一律在**剥掉注释**的同一份文本上算偏移：注释里那些「FileProvider 只放相机缓存」的说明
        // 会把匹配点带到一个不属于任何函数的位置，而函数范围是按同一份文本量出来的。
        val src = code(CARD)
        val range = fnRange(src, "private fun copyIntoDeliver(")
        forbidden.forEach { token ->
            Regex(Regex.escape(token)).findAll(src).forEach { m ->
                assertTrue(
                    "「$token」出现在 copyIntoDeliver 之外（卡片唯一的写面是「复制进证道」那一个函数；" +
                        "「打开/分享/删除」一条都不许从这里长出来）",
                    m.range.first in range,
                )
            }
        }
        // 反向钉：这一处确实存在（不是「全仓没写操作」蒙过去的）
        assertTrue("copyIntoDeliver 没走投递目录（LibraryPaths.deliverDir）", src.contains("LibraryPaths.deliverDir(ctx)"))
        assertTrue("写盘前必须过越界判据（destinationUnder 那把尺子，与资料库导入同一个）",
            src.contains("LibraryPaths.destinationUnder(deliver, name)"))
        assertTrue("先写 tmp 再 rename（不许留半个文件）", src.contains("tmp.renameTo(target)"))
        assertTrue("同名不覆盖", src.contains("if (target.exists()) return"))
    }

    @Test
    fun `结构_卡片不越门面自己开读侧的库`() {
        val src = code(CARD)
        listOf("SearchDb(", "PhoneIndex(", "SearchSql.", "writableDatabase", "rawQuery(SearchSql").forEach {
            assertFalse("卡片里出现「$it」：读侧的库只许经 PhoneSearch 门面", src.contains(it))
        }
        // 全卡片唯一一处直读是主库 `artifact` 台账（路径① 的读数），语句来自 PhoneJump
        val queries = Regex("rawQuery\\(").findAll(src).toList()
        assertEquals("卡片里只该有一条 rawQuery（查主库台账）", 1, queries.size)
        assertTrue("那一条用的是 PhoneJump 的常量，不自己拼语句", src.contains("rawQuery(PhoneJump.SELECT_ARTIFACT_SESSION"))
        // 而且它绑的两个参都是「位置」不是「内容」：工作区根 ＋ 库里的 rel_path
        val bound = Regex("rawQuery\\(PhoneJump\\.SELECT_ARTIFACT_SESSION, arrayOf\\(([^)]*)\\)").find(src)
        assertTrue("台账查询的绑参读不出来（改了形状要回来解释这条门禁）", bound != null)
        assertEquals("绑参恰好两个，且都不是查询词", listOf("artifactWorkspace", "hit.relPath"), bound!!.groupValues[1].split(",").map { it.trim() })
    }

    @Test
    fun `结构_动作表在UI侧不自己长`() {
        // §1⑤「外部只读范围只给预览／复制进证道」在 UI 侧的落点：按钮由判据给的表渲染，
        // 卡片不许自己再加一个动作（加「用别的 App 打开」是安全面改动，见 PhoneJump.kt 文件头）。
        val src = code(CARD)
        listOf("Intent", "startActivity", "FileProvider", "share(").forEach {
            assertFalse("卡片出现「$it」：读侧不产生「交给别的 App」这条能力", src.contains(it))
        }
        assertTrue("按钮必须由判据给的那张表渲染（不是 UI 自己列一份）", src.contains("row.actions.forEach { a ->"))
        // 每个动作在 UI 里恰好一处（`when` 的一支）：多一处＝有人在 UI 里另判一遍，
        // 少一处＝那张表里有个动作没人做（用户点了没反应）。
        JumpAction.values().forEach { a ->
            assertEquals("UI 里「${a.name}」的处理应当只有一处", 1, Regex("JumpAction\\.${a.name}\\b").findAll(src).count())
        }
        val branches = Regex("JumpAction\\.(\\w+) ->").findAll(src).map { it.groupValues[1] }.toSet()
        assertEquals("渲染的动作集合必须与枚举全集相等", JumpAction.values().map { it.name }.toSet(), branches)
        assertEquals("不许有枚举之外的动作回调名（那等于偷偷加一条能力）", 0, Regex("onOwnership|onOpenWith|onShare|onDelete").findAll(src).count())
        assertTrue("回会话这条走的是宿主传入的回调（卡片不自己找 Activity）", src.contains("onOpenSession(row.sessionId)"))
    }

    @Test
    fun `结构_一次扫描的取消是用户点出来的`() {
        // §1①「片间可取消」的 UI 半边：停止按钮置闸门，扫描器一片一查——不杀线程。
        val src = code(CARD)
        assertTrue("取消按钮要置 AtomicBoolean", src.contains("cancelFlag.set(true)"))
        assertTrue("扫描时把闸门读给内核", src.contains("cancelled = { cancelFlag.get() }"))
        assertTrue("重建索引是另一颗按钮（forceFull = true）", src.contains("startScan(forceFull = true)"))
        assertTrue("普通扫描带着用户等的词（可达即停）", src.contains("PhoneRank.termsOf(input).tokens"))
        // 「重建索引」那一次不带词：它的目标是建全量索引，不是找东西（带词会在够一屏时提前停）
        assertTrue("重建时词表必须是空", src.contains("val terms = if (forceFull || input.isBlank()) emptyList()"))
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private val name = "BaopuPhoneSearchCard.kt"

    private val CARD: String = repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuPhoneSearchCard.kt").readText()

    /** 一个顶层 `private fun` 的字符范围（从签名到函数体最后一个 `}`）。 */
    private fun fnRange(src: String, decl: String): IntRange {
        val at = src.indexOf(decl)
        assertTrue("$name 里找不到 $decl（卡片改了形状 ⇒ 这份写面名单得回来重数）", at >= 0)
        var depth = 0
        var started = false
        for (i in at until src.length) {
            when (src[i]) {
                '{' -> { depth++; started = true }
                '}' -> {
                    depth--
                    if (started && depth == 0) return at..i
                }
            }
        }
        throw AssertionError("$decl 的函数体没配平")
    }

    /** 子串是否作为词出现（`insert(` 这种带括号的交给调用方，这里只处理词表）。 */
    private fun String.containsWord(word: String): Boolean = this.lowercase().contains(word)

    /** 读侧内核（search 包）的全部生产源码：文件名 ⇔ 原文。 */
    private fun searchSources(): List<Pair<String, String>> =
        repoFile("src/main/java/com/example/zhengdao/baopu/search").listFiles()
            ?.filter { it.extension == "kt" }?.map { it.name to it.readText() }?.sortedBy { it.first }
            ?: throw AssertionError("search 包目录没数到文件（路径漂了 ⇒ 这份门禁就是真空）")

    /**
     * 每一条 `RunLog.log(…)` 的**整段参数表**（括号配平，跨行也取全）。
     *
     * 不按行取：门面那条扫描对账日志跨四行，按行筛只有首行被看见——
     * 泄漏写在续行上就检不出来（这是本批比 S2 多钉的那一层）。
     */
    private fun logArguments(src: String): List<String> {
        val needle = "RunLog.log("
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = src.indexOf(needle, from)
            if (at < 0) return out
            val open = at + needle.length - 1
            var depth = 0
            var i = open
            var end = -1
            while (i < src.length) {
                when (src[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) { end = i; break }
                    }
                }
                i++
            }
            assertTrue("有一处 RunLog.log( 的括号没配平：${src.substring(at, minOf(src.length, at + 80))}", end > open)
            out += src.substring(open + 1, end)
            from = end
        }
    }

    /** 一段文本里的字符串插值（`${…}` 与 `$word` 两种写法都算；返回去掉 `$`／`{}` 后的表达式）。 */
    private fun interpolations(text: String): List<String> =
        Regex("\\$\\{([^}]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)").findAll(text)
            .map { (it.groupValues[1].ifBlank { it.groupValues[2] }).trim() }.toList()

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
