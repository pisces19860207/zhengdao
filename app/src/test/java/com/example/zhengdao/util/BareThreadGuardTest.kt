package com.example.zhengdao.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.text.Charsets

/**
 * P4-2（issue #17）：裸 Thread 纪律守卫。
 *
 * 审计原文：「裸 Thread 35 处」。两次实测（证据等级②，2026-10-14，基线 88c9a2a）：
 * - 首轮（账本记载）：报「真实创建点 7 处，其余是 Thread.currentThread() 误报」——**漏数**。
 *   漏因：只 grep 了 `Thread(` 一种形态；`Thread {`（Kotlin 尾随 lambda，无括号）整个
 *   没数，而名字走属性赋值（`name = "…"`）的两处连 `Thread(` grep 都看不见。
 * - 本轮（写门禁时逐点复核）：**28 处** Kotlin 创建点，全部有名字、全部 daemon。
 *   其中 18 处（`Thread { … }.start()` 房型，散在 9 个文件）原本**既无名字也无
 *   daemon**，本刀按既有房型统一补上：`}.apply { isDaemon = true; name = "zd-…" }.start()`
 *   （与 ZhengdaoApp.kt 保活线程同款写法；每处只改收尾一行，不碰线程体）。
 *   台账点名的「第 7 处」= TerminalActivity.kt:596 的 zd-session-start，一并在内。
 *
 * 纪律（本守卫钉这条）：**每条裸 Thread 必须有名字 + 必须 daemon**。
 * - 无名线程在 ANR 堆栈里是一团 "Thread-12"，查不出是谁；
 * - 非 daemon 线程挂着会让进程退不干净——保活巡检那种 while(true) 长循环反而更必须
 *   daemon：它本来就要活到进程死，但不能拦着进程死。
 *
 * 立场（判断，证据等级③，写进交付）：这 28 处**不强行改成协程**。它们分两类——
 * 一次性 detached worker（安装/检查/修复/刷新/粘贴/起会话）与进程级长循环
 * （保活巡检/归档）。改用协程要先给每条找一条寿命正确的 scope，收益（少一个
 * Thread）配不上风险（scope 生命周期 / 取消语义）。
 *
 * 范围：只扫 `com/example/zhengdao` 自己的 Kotlin。vendored termux 的
 * `com/termux/terminal/TerminalSession.java` 有 3 条上游原装的**命名但非 daemon**
 * 线程（:139/:156/:172）——按本批红线不碰 termux，留待单独评估。Kotlin 标准库
 * 小写 `thread { }`（默认非 daemon）同样纳入纪律；当前全仓零使用（证据②）。
 *
 * 对账单冻结 2026-10-14 的 28 个创建点（`Thread` 关键字行号）。将来文件拆分/移动
 * 会让它失败——那是提醒回来更新对账单，不是 bug。
 */
class BareThreadGuardTest {

    /** 一条裸 Thread 创建点（line = `Thread` 关键字所在行）。 */
    private data class Site(val file: String, val line: Int, val named: Boolean, val daemon: Boolean)

    // ── 守卫本体 ──

    @Test
    fun `门禁_每条裸 Thread 都有名字且是 daemon`() {
        val sites = scanAppSources()
        assertTrue(
            "范围内一条裸 Thread 都没扫到——守卫自己失效了（工作目录/路径变了？）",
            sites.isNotEmpty(),
        )
        val bad = sites.filterNot { it.named && it.daemon }
        assertTrue(
            "以下裸 Thread 不合纪律（必须有名字 + 必须 daemon）：\n" +
                bad.joinToString("\n") {
                    "  ${it.file}:${it.line}  名字=${if (it.named) "有" else "缺"}  daemon=${if (it.daemon) "有" else "缺"}"
                },
            bad.isEmpty(),
        )
    }

    @Test
    fun `门禁_创建点数量收敛在红线以内`() {
        val total = scanAppSources().size
        // 2026-10-14 实测 28 处；红线 32——允许零星新增，但谁把数量拉回审计原文
        // （35 处）那种状态，必须先在账本上说清楚。
        assertTrue("裸 Thread 创建点 $total 处，超过红线 32——先收敛再谈新增", total in 1..32)
    }

    @Test
    fun `对账单_2026-10-14_实测 28 处创建点`() {
        val got = scanAppSources()
            .map { "${it.file.substringAfterLast('\\').substringAfterLast('/')}:${it.line}" }
            .sorted()
        val expected = listOf(
            "AgentInstaller.kt:54",                 // zd-agent-install
            "AgentManifest.kt:136",                 // zd-manifest-refresh
            "HomeScreen.kt:177",                    // zd-taiji-retry
            "HomeScreen.kt:560",                    // zd-health-fix
            "KeepaliveWatcher.kt:187",              // zhengdao-keepalive-patrol
            "KnowledgeBase.kt:153",                 // zd-knowledge-base-rebuild
            "KnowledgeBase.kt:176",                 // zd-knowledge-base-index
            "KnowledgeBase.kt:200",                 // zd-knowledge-base-auto
            "KnowledgeBaseHints.kt:133",            // zd-kb-hints
            "KnowledgeBaseSummarizerRunner.kt:110", // zd-kb-summarize
            "MainActivity.kt:228",                  // zd-update-check
            "NetSelfCheck.kt:94",                   // net-self-check-index
            "SettingsDataActivity.kt:588",          // zd-net-check
            "SettingsEnvActivity.kt:207",           // zd-hermes-repair
            "SettingsEnvActivity.kt:261",           // zd-env-check
            "SettingsEnvActivity.kt:564",           // zd-env-repair
            "SettingsEnvActivity.kt:659",           // zd-env-update
            "SettingsStorageActivity.kt:600",       // zd-cache-reload
            "SettingsStorageActivity.kt:663",       // zd-cache-clean
            "SettingsStorageActivity.kt:715",       // zd-rollback
            "TerminalActivity.kt:79",               // zd-paste
            "TerminalActivity.kt:596",              // zd-session-start（本刀补 daemon）
            "TerminalActivity.kt:811",              // zd-net-probe
            "TerminalActivity.kt:1550",             // zd-install-saf
            "TerminalActivity.kt:1595",             // zd-install-cache
            "TerminalActivity.kt:1681",             // zd-install-download
            "ZhengdaoApp.kt:47",                    // zhengdao-keepalive-archive
            "ZhengdaoApp.kt:97",                    // zd-auto-clean
        )
        assertEquals(
            "裸 Thread 创建点与 2026-10-14 对账单不符——新增/移动了线程？" +
                "按纪律补 有名+daemon 后回来更新对账单（它不是 bug，是提醒）",
            expected.sorted(),
            got,
        )
    }

    // ── 扫描器 ──
    //
    // 为什么不能只用正则：首版门禁稿（本刀前任留的未完成件）用
    // `Thread\(\s*(\{|r\b|…)` + `[^)]*,\s*"…"` 两条正则，实测四处失抓/误抓：
    // ① `Thread {` 尾随 lambda 形态（无括号）整个漏掉——18 处非合规创建点全是
    //    这个房型，首轮"7 处"的漏数就是这么来的；
    // ② 名字走属性赋值（`name = "…"`）漏判（ZhengdaoApp / KeepaliveWatcher 两处）；
    // ③ 跨行 lambda 体内的括号让 `[^)]*` 提前截断，有名也判成没名；
    // ④ 注释里的字面量（`isDaemon = true` / `Thread(`）会虚增计数——台账坑：
    //    门禁被自己的注释带偏（TerminalActivity.kt 那条 P4-2 注释里就写着
    //    isDaemon=true）。因此改为：先去注释（保留字符串），再逐字符扫描
    //    （跳过字符串/字符字面量/字符串模板），括号配对用深度计数。

    /** 名字/daemon 的搜索窗口：创建处之后 400 字符（`.apply { … }`、`t.isDaemon = true` 都落在这里）。 */
    private val regionWindow = 400

    /** 扫 App 自己的 Kotlin 源码（vendored termux 是 Java，天然不在范围内）。 */
    private fun scanAppSources(): List<Site> {
        val root = File("src/main/java/com/example/zhengdao")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { collectSites(it.readText(Charsets.UTF_8), it.path) }
            .toList()
    }

    /**
     * 扫出文件中所有裸 Thread 创建点。
     * 算创建点：`Thread(…)` / `Thread {…}`，以及标准库小写 `thread {…}`（默认非 daemon，同纪律）。
     * 不算：`Thread.currentThread()` / `Thread.sleep(…)`（点是成员访问）、
     * `startSessionOffMainThread()`（词边界）、`HandlerThread(…)`（另一个类）、
     * 注释与字符串里的一切（先去注释 + 扫描跳过字符串）。
     */
    private fun collectSites(raw: String, path: String): List<Site> {
        val code = stripComments(raw)
        val sites = mutableListOf<Site>()
        var i = 0
        var line = 1
        val n = code.length
        while (i < n) {
            val c = code[i]
            if (c == '\n') { line++; i++; continue }
            if (c == '"' || c == '\'') {
                val end = if (c == '"' && code.startsWith("\"\"\"", i)) skipRawString(code, i) else skipString(code, i, c)
                line += code.substring(i, end).count { it == '\n' }
                i = end
                continue
            }
            val kwLen = when {
                c == 'T' && code.startsWith("Thread", i) -> 6
                c == 't' && code.startsWith("thread", i) -> 6
                else -> 0
            }
            val prevOk = i == 0 ||
                (!code[i - 1].isLetterOrDigit() && code[i - 1] != '_' && code[i - 1] != '.')
            if (kwLen > 0 && prevOk) {
                var j = i + kwLen
                while (j < n && (code[j] == ' ' || code[j] == '\t')) j++
                if (j < n && (code[j] == '(' || code[j] == '{')) {
                    sites += inspectSite(code, j, line, path)
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return sites
    }

    /** 判定一条创建点的名字与 daemon（delimIdx 指向 `(` 或 `{`）。 */
    private fun inspectSite(code: String, delimIdx: Int, line: Int, path: String): Site {
        val open = code[delimIdx]
        val close = if (open == '(') ')' else '}'
        val closeIdx = scanBalanced(code, delimIdx, open, close)
        // 名字：构造函数里的字符串参数（Thread(r, "name")），或创建处之后窗口内的 name = "…" 属性赋值
        val args = if (closeIdx > 0) code.substring(delimIdx + 1, closeIdx) else ""
        val regionStart = if (closeIdx > 0) closeIdx + 1 else delimIdx + 1
        val region = code.substring(regionStart, minOf(regionStart + regionWindow, code.length))
        val named = (open == '(' && args.contains('"')) ||
            Regex("""\bname\s*=\s*["]""").containsMatchIn(region)
        // daemon：同一窗口内的 isDaemon = true（注释已被 stripComments 去掉，注释里的字面量不算）
        val daemon = Regex("""isDaemon\s*=\s*true""").containsMatchIn(region)
        return Site(path, line, named, daemon)
    }

    /** 从 code[start]（= open）深度配对到 close；跳过字符串/字符字面量。未闭合返回 -1。 */
    private fun scanBalanced(code: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = start
        while (i < code.length) {
            val c = code[i]
            if (c == '"' || c == '\'') {
                i = if (c == '"' && code.startsWith("\"\"\"", i)) skipRawString(code, i) else skipString(code, i, c)
                continue
            }
            if (c == open) depth++
            else if (c == close) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    // ── 去注释（Kotlin 块注释可嵌套；字符串/字符字面量原样保留；换行保留以维持行号） ──

    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') { out.append(' '); i++ }
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    var depth = 1
                    out.append(' ').append(' ')
                    i += 2
                    while (i < n && depth > 0) {
                        if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                            depth++; out.append(' ').append(' '); i += 2
                        } else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                            depth--; out.append(' ').append(' '); i += 2
                        } else {
                            out.append(if (text[i] == '\n') '\n' else ' '); i++
                        }
                    }
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val end = skipRawString(text, i)
                    out.append(text, i, end)
                    i = end
                }
                c == '"' -> {
                    val end = skipString(text, i, '"')
                    out.append(text, i, end)
                    i = end
                }
                c == '\'' -> {
                    val end = skipString(text, i, '\'')
                    out.append(text, i, end)
                    i = end
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** 普通字符串/字符字面量：从开引号到未转义的闭引号；`${…}` 模板里可能再嵌字符串，递归跳过。 */
    private fun skipString(text: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < text.length) {
            when (val c = text[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> return i // 未闭合就到行尾（异常代码）：交还主循环，行号不错
                '$' if i + 1 < text.length && text[i + 1] == '{' -> i = skipTemplate(text, i + 2)
                else -> i++
            }
        }
        return text.length
    }

    /** 字符串模板 `${` 之后：深度配对到 `}` 之后；里面可能有字符串/字符字面量。 */
    private fun skipTemplate(text: String, start: Int): Int {
        var depth = 1
        var i = start
        while (i < text.length) {
            when (val c = text[i]) {
                '{' -> { depth++; i++ }
                '}' -> { depth--; i++; if (depth == 0) return i }
                '"' -> i = if (text.startsWith("\"\"\"", i)) skipRawString(text, i) else skipString(text, i, '"')
                '\'' -> i = skipString(text, i, '\'')
                '\n' -> return i
                else -> i++
            }
        }
        return text.length
    }

    /** 原始字符串 `"""…"""`：到下一个三引号为止。 */
    private fun skipRawString(text: String, start: Int): Int {
        var i = start + 3
        while (i < text.length) {
            if (text.startsWith("\"\"\"", i)) return i + 3
            i++
        }
        return text.length
    }

    // ── 自检：扫描器自身靠得住 ──
    // （台账坑：文本门禁容易被自己注释里的字面量带偏——所以下面两例专门拿
    //  注释/字符串/模板/成员访问/大小写/另一个类名喂它。）

    @Test
    fun `自检_注释与字符串里的 Thread 不算创建点`() {
        val tricky = """
            // Thread({ foo() }, "line-comment") .apply { isDaemon = true }
            /* Thread({ bar() }, "block-comment") isDaemon = true
               Thread({ baz() }, "nested /* still comment */ tail") */
            val url = "https://example.com/Thread(not-a-thread)"
            val label = "say ${'$'}{if (ok) "Thread(" else "no"}"
            val a = Thread({ realWork() }, "real-name").apply { isDaemon = true }
            val b = Thread { work2() }.apply { isDaemon = true; name = "prop-name" }
            a.start(); b.start()
        """.trimIndent()
        val sites = collectSites(tricky, "synthetic.kt")
        assertEquals(2, sites.size)
        assertTrue("两条都应有名且 daemon：$sites", sites.all { it.named && it.daemon })
    }

    @Test
    fun `自检_违规形态与误报形态都能正确分辨`() {
        val mixed = """
            val t1 = Thread({ work() })
            t1.start()
            val t2 = Thread({ work() }, "has-name-no-daemon")
            t2.start()
            Thread.currentThread()
            startSessionOffMainThread()
            Thread.sleep(100)
            HandlerThread("factory")
            val t3 = thread { legacy() }
            t3.start()
        """.trimIndent()
        val sites = collectSites(mixed, "synthetic2.kt")
        // 只有 t1/t2/t3 是创建点；成员访问/函数名尾巴/另一个类都不算
        assertEquals(3, sites.size)
        assertTrue("三条违规都应被抓出来：$sites", sites.none { it.named && it.daemon })
    }
}
