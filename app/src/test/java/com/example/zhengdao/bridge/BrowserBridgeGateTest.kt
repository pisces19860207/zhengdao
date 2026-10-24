// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.bridge

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器桥门禁（阶段 3 重建，2026-10-15；设计文档 §4.5）。
 *
 * ## 为什么这一整类东西必须靠源码门禁
 *
 * 桥的**可测部分**（白名单、URL/selector 校验、Basic 解析）已在 [BridgePolicyTest] 里跑过；
 * 剩下的全是"要 Context / 要 WebView / 要真设备"的层面：HTTP 监听、WebView 操作、
 * token 存取、设置页 UI。JVM 单测碰不到它们 —— 但**这些恰恰是最不能退化的地方**：
 * 把 `127.0.0.1` 写成 `0.0.0.0`、把默认关改成默认开、给 execJS 结果不过脱敏、
 * 用裸 `Thread(` 起 acceptor……每一处都不会让任何现有测试变红，只会把一条
 * "能操作浏览器"的通道悄悄打开给不该给的对面。
 *
 * 所以这一份门禁钉的是**结构事实**，判据一律钉实质（调用形状、参数形态、绑谁），
 * 不钉局部变量名与行号（ERRATA E-133）。**凡断言"某字样不出现"，先剥注释再断言** ——
 * 第⑲刀与 E-133 都栽在"注释里写了同名字面量"上。
 */
class BrowserBridgeGateTest {

    private fun file(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    /** 剥注释：注释里的同名字面量不算数（防"自己拦自己"与"注释里写一句就算过"）。 */
    private fun strip(s: String): String = s
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    private val bridge by lazy { strip(file("src/main/java/com/example/zhengdao/bridge/BrowserAgentBridge.kt")) }
    private val commands by lazy { strip(file("src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt")) }
    private val token by lazy { strip(file("src/main/java/com/example/zhengdao/bridge/BridgeToken.kt")) }
    private val policy by lazy { strip(file("src/main/java/com/example/zhengdao/bridge/BridgePolicy.kt")) }
    private val manifest by lazy { file("src/main/AndroidManifest.xml") }
    private val bridgePage by lazy { strip(file("src/main/java/com/example/zhengdao/ui/SettingsBridgeActivity.kt")) }
    private val screen by lazy { strip(file("src/main/java/com/example/zhengdao/ui/SettingsScreen.kt")) }

    // ── ① 只绑本机（最要紧的一条） ─────────────────────────────────────────

    @Test
    fun `只绑 127 0 0 1_绝不许出现 0 0 0 0`() {
        assertTrue(
            "bind 必须用 BridgePolicy.BIND_HOST（唯一真相源）而不是字面量 —— 字面量会被" +
                "后来者『顺手』改成 0.0.0.0 而没有任何测试变红",
            bridge.contains("InetSocketAddress(BridgePolicy.BIND_HOST, BridgePolicy.PORT)"),
        )
        assertFalse(
            "出现了 0.0.0.0 —— 那等于对着全网开放一条能操作浏览器的通道",
            bridge.contains("0.0.0.0"),
        )
        assertEquals("127.0.0.1", BridgePolicy.BIND_HOST)
    }

    // ── ② 白名单 11 条，分发层一条不少 ────────────────────────────────────

    @Test
    fun `分发层覆盖全部 11 条命令`() {
        // 数 dispatch 的 when 分支：每条命令都要有一个 `BrowserPolicy.Command.X ->`
        val branchCount = Regex("BridgePolicy\\.Command\\.[A-Z_]+\\s*->").findAll(bridge).count()
        assertEquals("分发层必须一条命令一个分支（11 条）", 11, branchCount)
        // 每条命令都要有对应的执行函数（BrowserCommands 里出现同名 fun）
        BridgePolicy.ALL.forEach { cmd ->
            val fn = when (cmd) {
                // ㊽-C（§B 78，2026-10-16）：status 加了末位默认参数 tabId，判据跟着放宽到「函数名 + 左括号」
                // （与其余十条一致）—— 只认 `fun status()` 会让「给 status 加可选路由参数」这种合法改动误红。
                BridgePolicy.Command.STATUS -> "fun status("
                BridgePolicy.Command.SNAPSHOT -> "fun snapshot("
                BridgePolicy.Command.EXEC_JS -> "fun execJs("
                BridgePolicy.Command.CLICK -> "fun click("
                BridgePolicy.Command.TYPE -> "fun type("
                BridgePolicy.Command.SCROLL -> "fun scroll("
                BridgePolicy.Command.NAVIGATE -> "fun navigate("
                BridgePolicy.Command.NEW_TAB -> "fun newTab("
                BridgePolicy.Command.ACTIVE_TAB -> "fun activeTab("
                BridgePolicy.Command.CLOSE_TAB -> "fun closeTab("
                BridgePolicy.Command.SCREENSHOT -> "fun screenshot("
            }
            assertTrue("缺 $fn —— ${cmd.wireName} 会走到 else 静默失败", commands.contains(fn))
        }
    }

    // ── ③ 默认关 ──────────────────────────────────────────────────────────

    @Test
    fun `默认关_只有设置页开关能启动_且启动点带用户显式动作`() {
        // 全仓对 start( 的调用只允许出现在设置页（用户拨开关）—— App 启动/服务里不许自启
        val srcMain = File("src/main").walk().filter { it.extension == "kt" }.toList()
        val starters = srcMain.filter { f ->
            val t = strip(f.readText())
            t.contains("BrowserAgentBridge.start(")
        }
        assertEquals(
            "只有设置页可以启动桥（默认关 = 用户显式开）。当前调用点：" +
                starters.joinToString { it.path },
            1,
            starters.size,
        )
        assertTrue(
            "启动点必须在 SettingsBridgeActivity 里",
            starters.single().path.endsWith("SettingsBridgeActivity.kt"),
        )
        // 开关的 onCheckedChange 里才调 start（而不是 onCreate/LaunchedEffect 自启）
        assertTrue(
            "start 必须挂在用户拨开关的动作里",
            bridgePage.contains("BrowserAgentBridge.start(ctx)"),
        )
        // 判据要精确到**块内**：页面里有 LaunchedEffect（toast 自动消失用它）不等于自启桥。
        // 做法：取 LaunchedEffect(...) { … } 的块（花括号配平），块内不许出现 start。
        val effectBlocks = Regex("LaunchedEffect\\s*\\([^)]*\\)\\s*\\{").findAll(bridgePage).map { it.range.last }
        effectBlocks.forEach { openBrace ->
            var depth = 1
            var i = openBrace + 1
            while (i < bridgePage.length && depth > 0) {
                when (bridgePage[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                i++
            }
            val block = bridgePage.substring(openBrace, i)
            assertFalse(
                "LaunchedEffect 块里出现了 BrowserAgentBridge.start —— 那是页面自己把桥拉起来了，" +
                    "不是用户拨开关（默认关的纪律就破了）",
                block.contains("BrowserAgentBridge.start"),
            )
        }
        // Manifest 里不该有 boot receiver 之类把它拉起来
        assertFalse("Manifest 里不该有 BOOT_COMPLETED 接收器把桥拉起来", manifest.contains("BOOT_COMPLETED"))
    }

    // ── ④ token 的存储与流转 ──────────────────────────────────────────────

    @Test
    fun `token 只进私有 prefs_不落文件不联网_且用 SecureRandom`() {
        assertTrue("必须用 SecureRandom（用户设的口令有可猜性）", token.contains("SecureRandom"))
        assertTrue(
            "必须走 SharedPreferences（App 私有）",
            token.contains("getSharedPreferences"),
        )
        assertFalse("不许写文件（token 不该出现在任何文件里）", token.contains("FileOutputStream"))
        assertFalse("不许走网络（token 不该出设备）", token.contains("okhttp") || token.contains("HttpURL"))
        assertFalse(
            "token 文件里不该有 RunLog 调用（日志会被导出给作者，token 不许进日志）",
            token.contains("RunLog.log"),
        )
    }

    // ── ⑤ 脱敏不可绕（红线⑦） ─────────────────────────────────────────────

    @Test
    fun `离开桥的数据一律过脱敏_execJS 与 snapshot 都拦`() {
        // 第㊵刀：判据从字面串升级为语义——execJs 函数体内必须同时过 redactText
        // （号码/PII 值）与 redactPairs（cookie/localStorage 键值对，P3 接线），
        // 少任何一个都算"agent 能读原始隐私值绕过脱敏"。
        val execJsBody = commands.substringAfter("fun execJs(").substringBefore("\n    fun ")
        assertTrue(
            "execJS 的返回值必须过 SensitiveRedactor.redactText —— 否则 agent 用它读原始隐私值绕过脱敏",
            execJsBody.contains("redactText("),
        )
        assertTrue(
            "execJS 的返回值必须过 SensitiveRedactor.redactPairs —— document.cookie 的键值对否则明文（第㊵刀 P3）",
            execJsBody.contains("redactPairs("),
        )
        assertTrue(
            "snapshot 的 DOM 必须过 redactDom",
            commands.contains("SensitiveRedactor().redactDom(PageSnapshot.scrubSecrets(html))"),
        )
        // 结果一律限长（防一次拉爆内存）
        assertTrue("结果必须限长", commands.contains("BridgePolicy.MAX_RESULT_CHARS"))
    }

    // ── ⑥ 不许碰 App 私有数据（硬红线⑤） ─────────────────────────────────

    @Test
    fun `命令层不许碰 App 私有数据与其他 App`() {
        assertFalse("不许 openFileInput/openFileOutput（App 私有文件）", commands.contains("openFileInput") || commands.contains("openFileOutput"))
        assertFalse("不许直接拿 Context 的 filesDir 写东西", commands.contains("context.filesDir"))
        assertFalse("不许起别的 App", commands.contains("startActivity"))
        // 唯一允许的目录写入是截图落共享目录（Store.root = Downloads/证道，已 bind 给 guest）
        // 第一百七十七批：目录名从 `screenshots` 改成 `截屏`（用户口径），落点仍必须是
        // 共享目录、仍不许是 App 私有目录 —— 判据跟着改名走，不是放宽。
        assertTrue(
            "截图必须落共享目录（agent 唯一可达路径），不是 App 私有目录",
            commands.contains("File(Store.root(context), SHOT_DIR)"),
        )
        assertTrue(
            "截图目录名必须是 截屏（用户 2026-10-24 口径）",
            commands.contains("internal const val SHOT_DIR = \"截屏\""),
        )
    }

    // ── ⑦ 线程纪律（不许裸 Thread） ───────────────────────────────────────

    @Test
    fun `连接线程走线程池_起名且 daemon`() {
        assertTrue("acceptor 必须走 Executors（项目并发纪律）", bridge.contains("Executors.newFixedThreadPool"))
        assertTrue("线程必须起名（出问题看 stack trace 才认得）", bridge.contains("\"zd-bridge-http\""))
        assertTrue("线程必须 daemon（进程要能直接退出）", bridge.contains("isDaemon = true"))
        // 口径与 BareThreadGuardTest 一致：`Executors.newSingleThreadExecutor { r -> Thread(r, "zd-…").apply { isDaemon = true } }`
        // 是项目**认可**的形态（KeepaliveWatcher.kt:66 同款），要求是"有名字 + daemon"而不是"不许出现 Thread("。
        // 本刀的 zd-bridge-http 已登记进 BareThreadGuardTest 对账单（BrowserAgentBridge.kt:75）。
        val m = Regex("Thread\\(\\s*r\\s*,\\s*\\\"([^\\\"]+)\\\"\\)\\s*\\.apply\\s*\\{\\s*isDaemon\\s*=\\s*true\\s*\\}").find(bridge)
        assertNotNull("线程构造必须是 Thread(r, \"名字\").apply { isDaemon = true }（项目认可形态）", m)
        assertTrue("线程名要带 zd- 前缀（对账与排查用）", m!!.groupValues[1].startsWith("zd-"))
    }

    // ── ⑧ 中英文案对称 ────────────────────────────────────────────────────

    @Test
    fun `bridge 相关文案中英成对`() {
        fun keys(rel: String): Set<String> =
            Regex("<string name=\"([^\"]+)\"").findAll(file(rel)).map { it.groupValues[1] }.toSet()
        val zh = keys("src/main/res/values/strings.xml")
        val en = keys("src/main/res/values-en/strings.xml")
        assertEquals("中英键数必须相等（红线③）", zh.size, en.size)
        val bridgeKeys = zh.filter { it.startsWith("bridge_") || it == "settings_bridge" || it == "settings_value_bridge" }
        assertTrue("一条 bridge 文案都没有？", bridgeKeys.isNotEmpty())
        bridgeKeys.forEach { k ->
            assertTrue("en 缺 $k", en.contains(k))
        }
    }

    // ── ⑨ 可运行期：body 参数解析（极简 JSON，纯函数） ────────────────────

    @Test
    fun `param 能取字符串与数字_取不到给 null`() {
        assertEquals("https://ai-xan.xyz/zh", BrowserAgentBridge.param("{\"url\":\"https://ai-xan.xyz/zh\"}", "url"))
        assertEquals("input[name=q]", BrowserAgentBridge.param("{\"selector\":\"input[name=q]\"}", "selector"))
        // 转义要还原
        assertEquals("a\"b\\c", BrowserAgentBridge.param("{\"code\":\"a\\\"b\\\\c\"}", "code"))
        assertEquals("line1\nline2", BrowserAgentBridge.param("{\"text\":\"line1\\nline2\"}", "text"))
        // 数字与布尔按字符串取用
        assertEquals("3", BrowserAgentBridge.param("{\"tabId\":3}", "tabId"))
        assertEquals("true", BrowserAgentBridge.param("{\"fullPage\":true}", "fullPage"))
        // 取不到
        assertNull(BrowserAgentBridge.param("{\"url\":\"x\"}", "selector"))
        assertNull(BrowserAgentBridge.param("", "url"))
        assertNull(BrowserAgentBridge.param("not json at all", "url"))
        // 空格容忍
        assertEquals("v", BrowserAgentBridge.param("{ \"url\" : \"v\" }", "url"))
    }

    @Test
    fun `param 不会被别的键名误命中`() {
        // "dataUrl" 里含 "url" —— 必须精确匹配键名（否则 agent 传错字段也能取到值）
        assertNull(BrowserAgentBridge.param("{\"dataUrl\":\"https://x\"}", "url"))
        assertNotNull(BrowserAgentBridge.param("{\"url\":\"https://x\",\"dataUrl\":\"https://y\"}", "url"))
    }

    // ── ⑩ 主线程亲和（第㉓刀） ─────────────────────────────────────────────

    /**
     * 判别器：在（已剥注释的）BrowserCommands 源码里，找出「绕过 [onMain] 直调
     * UI 线程限定 API」的调用点，返回违例描述（空 = 合规）。
     *
     * 做法：先收集所有 `onMain { … }` 块（花括号配平），再逐个找四条控制命令对
     * BrowserTabManager / WebViewEngine 的直调字样，落在块外即违例。
     * 纯函数（吃源码字符串）⇒ 正例（真实源码必须零违例）与负例（合成/变异的
     * 坏形状必须被识别）用同一个判别器自证，见下面两个测试。
     */
    private fun directUiCalls(src: String): List<String> {
        val violations = mutableListOf<String>()
        val onMainRanges = mutableListOf<IntRange>()
        Regex("\\bonMain\\b").findAll(src).forEach { m ->
            val open = src.indexOf('{', m.range.last)
            if (open == -1) return@forEach
            var depth = 0
            var i = open
            while (i < src.length) {
                when (src[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            onMainRanges.add(open..i)
                            break
                        }
                    }
                }
                i++
            }
        }
        val needles = mapOf(
            "BrowserTabManager.newTab(" to "newTab（WebView 创建必须主线程，否则必抛 Looper 崩溃）",
            "BrowserTabManager.selectTab(" to "selectTab（Compose snapshot 状态写入限主线程）",
            "BrowserTabManager.closeTab(" to "closeTab（WebView.destroy 限主线程）",
            ".engine.loadUrl(" to "navigate→loadUrl（WebView 操作限主线程）",
        )
        needles.forEach { (needle, why) ->
            var from = 0
            while (true) {
                val at = src.indexOf(needle, from)
                if (at == -1) break
                from = at + needle.length
                if (onMainRanges.none { it.contains(at) }) {
                    violations.add("$why —— $needle 出现在 onMain 块之外 @offset $at")
                }
            }
        }
        return violations
    }

    @Test
    fun `门禁_四条控制命令必须经主线程 onMain`() {
        val violations = directUiCalls(commands)
        assertTrue(
            "navigate/newTab/activeTab/closeTab 四条控制命令必须经 onMain 落主线程" +
                "（BrowserTabManager 全部公开方法限定 UI 线程，BrowserTabManager KDoc 原文）；" +
                "以下调用点绕过了 onMain（HTTP 工作线程直调）——第㉓刀修的正是这个：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `门禁_主线程亲和负例自证_剥掉 onMain 包装必全数报警`() {
        // 负例一（合成）：往真实源码追加一个"改回直调"的形状，判别器必须抓到。
        val synthetic = commands + "\nfun fakeRegression(){ BrowserTabManager.selectTab(3) }"
        assertTrue(
            "负例自证失败：直调 selectTab 的形状判别器没抓到 —— 门禁自己失效了",
            directUiCalls(synthetic).isNotEmpty(),
        )
        // 负例二（变异）：把真实源码里所有 onMain 包装剥掉（模拟回归"顺手改回直调"），
        // 四条命令的直调必须全部被识别。
        val mutated = commands.replace("onMain {", "listOf<Unit>().let {")
        val violations = directUiCalls(mutated)
        assertTrue(
            "负例自证失败：剥掉 onMain 包装后判别器只报了 ${violations.size} 条（应 ≥ 4 条）——" +
                "门禁对四条命令的覆盖有缺口\n${violations.joinToString("\n")}",
            violations.size >= 4,
        )
    }
}
