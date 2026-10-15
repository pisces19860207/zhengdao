// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
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
                BridgePolicy.Command.STATUS -> "fun status()"
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
        assertTrue(
            "execJS 的返回值必须过 SensitiveRedactor —— 否则 agent 用它读原始隐私值绕过脱敏",
            commands.contains("SensitiveRedactor().redactText(result)"),
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
        assertTrue(
            "截图必须落共享目录（agent 唯一可达路径），不是 App 私有目录",
            commands.contains("File(Store.root(context), \"screenshots\")"),
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
}
