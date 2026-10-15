// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.bridge

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥的策略与 token 单测（阶段 3 重建，2026-10-15；设计文档 §4.5）。
 *
 * ## 为什么这两个类值得单测、而别的桥类不（暂时）
 *
 * [BridgePolicy] 与 [BridgeToken] 的判定逻辑是**纯函数**（零 android.* 依赖）：
 * 白名单命中、URL scheme、selector 注入逃逸、Basic 解析、恒定时间比较 ——
 * 全是"输入字符串 → 输出判定"，能在 JVM 上表驱动跑。
 * 而 HTTP 服务端 / WebView 操作那两层要 Context、要真设备，只能靠源码门禁 + 真机
 * （见 `BrowserBridgeGateTest`），这里不硬凑。
 *
 * ## 这里钉的是"安全判据"，不是"能跑"
 *
 * 一条给外部 agent 开的高权限通道，**漏拦**的代价远大于**漏放**：
 * 漏放只是某个命令用不了（报错明确）；漏拦是 agent 拿到了设计说不该给的能力
 * （而且不会有任何测试变红）。所以下面每条都朝着"换种写法还能不能绕过"去写
 * （ERRATA E-133 的教训：判据钉实质，不钉字面）。
 */
class BridgePolicyTest {

    // ── 白名单 ─────────────────────────────────────────────────────────────

    @Test
    fun `白名单恰好 11 条_与设计文档命令清单对齐`() {
        assertEquals("设计文档 §4.5 命令清单是 11 条；数量变了说明有人加了第 12 条", 11, BridgePolicy.ALL.size)
        assertEquals(
            "wireName 必须与设计文档逐字一致（agent 侧照文档写，大小写敏感）",
            listOf(
                "snapshot", "execJS", "click", "type", "scroll",
                "navigate", "newTab", "activeTab", "closeTab", "status", "screenshot",
            ),
            BridgePolicy.ALL.map { it.wireName },
        )
    }

    @Test
    fun `协议面名字能找回命令_且大小写敏感`() {
        assertEquals(BridgePolicy.Command.EXEC_JS, BridgePolicy.Command.fromWire("execJS"))
        // 大小写不敏感会让 "EXECJS"/"execjs" 也进得来 —— 协议面就该逐字对齐
        assertNull(BridgePolicy.Command.fromWire("execjs"))
        assertNull(BridgePolicy.Command.fromWire("EXECJS"))
        assertNull(BridgePolicy.Command.fromWire(""))
        assertNull(BridgePolicy.Command.fromWire("unknown-cmd"))
    }

    @Test
    fun `白名单外的命令一律拒_且拒绝信息里列出可用命令`() {
        val v = BridgePolicy.checkCommand("deleteEverything")
        assertTrue("白名单外必须拒", v is BridgePolicy.Verdict.Deny)
        val reason = (v as BridgePolicy.Verdict.Deny).reason
        assertTrue("拒绝原因该告诉 agent 能用什么（否则它只能瞎猜）", reason.contains("snapshot"))
        assertTrue("拒绝原因该点名它发的那条", reason.contains("deleteEverything"))
    }

    @Test
    fun `空命令名与 null 都拒`() {
        assertTrue(BridgePolicy.checkCommand(null) is BridgePolicy.Verdict.Deny)
        assertTrue(BridgePolicy.checkCommand("") is BridgePolicy.Verdict.Deny)
        assertTrue(BridgePolicy.checkCommand("   ") is BridgePolicy.Verdict.Deny)
    }

    // ── 硬红线 ─────────────────────────────────────────────────────────────

    @Test
    fun `硬红线 7 类都在_且白名单命令一条都不撞红线`() {
        // 设计文档 §4.5「硬红线区」7 条 → 至少要有代表性动作
        val mustHave = listOf(
            "change-security-config", // ① WebView 安全配置
            "change-navigation-policy", // ② 导航拦截规则
            "change-allowlist", // ③ 本桥 allowlist
            "change-port", // ③ 本桥端口
            "change-token", // ③ 本桥 token
            "rebind-javascript-interface", // ④ JS 接口重绑
            "read-app-private-data", // ⑤ App 私有数据
            "write-app-private-data", // ⑤
            "touch-other-app", // ⑤ 其他 App
            "change-browser-ui", // ⑥ 浏览器 UI
            "change-engine", // ⑥ 引擎/代码
            "disable-redaction", // ⑦ 脱敏开关
        )
        mustHave.forEach { assertTrue("硬红线少了 $it", BridgePolicy.isRedLine(it)) }
        // 反过来：白名单里的命令一条都不许撞红线（否则 checkCommand 会把合法命令拒掉）
        BridgePolicy.ALL.forEach { cmd ->
            assertFalse("白名单命令 ${cmd.wireName} 撞了红线", BridgePolicy.isRedLine(cmd.wireName))
        }
        // 普通字样不是红线
        assertFalse(BridgePolicy.isRedLine("navigate"))
        assertFalse(BridgePolicy.isRedLine(""))
    }

    @Test
    fun `checkCommand 对撞红线的命令也拒_双保险`() {
        // 白名单与红线是两套集合，但 checkCommand 要同时过两道（防将来有人把红线项加进 Command）
        val v = BridgePolicy.checkCommand("disable-redaction")
        assertTrue("撞红线必须拒", v is BridgePolicy.Verdict.Deny)
    }

    // ── navigate 的 scheme 校验 ────────────────────────────────────────────

    @Test
    fun `navigate 只放行 http 与 https`() {
        listOf(
            "https://ai-xan.xyz/zh",
            "http://127.0.0.1:8080",
            "HTTPS://EXAMPLE.COM", // 大小写不该影响判定（lowercase 后判）
            "  https://example.com/a?b=1#c  ", // 首尾空白容忍
        ).forEach {
            assertTrue("该放行：$it", BridgePolicy.checkNavigateUrl(it) is BridgePolicy.Verdict.Allow)
        }
        listOf(
            "file:///etc/passwd",
            "intent://scan/#Intent;scheme=zxing;end",
            "tel:+8613800138000",
            "content://com.android.contacts/data",
            "data:text/html,<script>alert(1)</script>",
            "javascript:alert(1)",
            "about:blank",
            "", "   ", null,
        ).forEach {
            assertTrue("该拒绝：$it", BridgePolicy.checkNavigateUrl(it) is BridgePolicy.Verdict.Deny)
        }
    }

    // ── selector 校验（防 JS 注入逃逸） ────────────────────────────────────

    @Test
    fun `selector 不许含引号与反斜杠_否则能闭合 JS 字符串执行任意代码`() {
        // 逃逸样本：selector 最终被拼进一段 JS 源码，能**闭合字符串**的字符才是危险源
        listOf(
            "x'}alert(1)//",
            "a\\';alert(1);//",
            "\" onmouseover=\"alert(1)",
            "a\\\\",
        ).forEach {
            assertTrue("必须拒绝可逃逸的 selector：$it", BridgePolicy.checkSelector(it) is BridgePolicy.Verdict.Deny)
        }
        // ⚠️ 反例（钉住判据的边界，防后人把选择器校验收紧到没法用）：
        // `input[onload=alert(1)]` 这种 CSS **属性选择器**不逃逸 —— 它不含引号/反斜杠，
        // 整串被包在 querySelector("…") 的引号里，只会得到一个"找不到元素"的选择器。
        // 危险的是"闭合字符串"，不是"长得像 XSS"。把这类也拒掉会让合法页面选择器误伤。
        assertTrue(
            "CSS 属性选择器里的 alert(1) 不构成逃逸，该放行",
            BridgePolicy.checkSelector("input[onload=alert(1)]") is BridgePolicy.Verdict.Allow,
        )
    }

    @Test
    fun `正常 CSS 选择器放行`() {
        listOf(
            "input[name=q]",
            "#login-form button[type=submit]",
            "div.card > a.btn.primary",
            "textarea",
            "*[aria-label=搜索]",
        ).forEach {
            assertTrue("该放行：$it", BridgePolicy.checkSelector(it) is BridgePolicy.Verdict.Allow)
        }
        assertTrue(BridgePolicy.checkSelector(null) is BridgePolicy.Verdict.Deny)
        assertTrue(BridgePolicy.checkSelector("") is BridgePolicy.Verdict.Deny)
        assertTrue(
            "超长 selector 必须拒（防拖垮 JS 注入）",
            BridgePolicy.checkSelector("a".repeat(BridgePolicy.MAX_SELECTOR_CHARS + 1)) is BridgePolicy.Verdict.Deny,
        )
    }

    // ── snapshot mode ──────────────────────────────────────────────────────

    @Test
    fun `snapshot 三种 mode 都放行_大小写与空白容忍`() {
        listOf("text", "dom", "full", "TEXT", " dom ", "", null).forEach {
            assertTrue("该放行：$it", BridgePolicy.checkSnapshotMode(it) is BridgePolicy.Verdict.Allow)
        }
        assertTrue(BridgePolicy.checkSnapshotMode("cookies") is BridgePolicy.Verdict.Deny)
        assertTrue(BridgePolicy.checkSnapshotMode("dom,text") is BridgePolicy.Verdict.Deny)
    }

    // ── 路径解析 ───────────────────────────────────────────────────────────

    @Test
    fun `路径解析只认 bridge v1 前缀`() {
        assertEquals("navigate", BridgePolicy.commandFromPath("/bridge/v1/navigate"))
        assertEquals("snapshot", BridgePolicy.commandFromPath("/bridge/v1/snapshot?mode=text"))
        assertEquals("click", BridgePolicy.commandFromPath("/bridge/v1/click/extra"))
        assertNull(BridgePolicy.commandFromPath("/bridge/v2/navigate")) // 版本前缀不对
        assertNull(BridgePolicy.commandFromPath("/bridge/v1/")) // 空命令名
        assertNull(BridgePolicy.commandFromPath("/other/v1/navigate"))
        assertNull(BridgePolicy.commandFromPath(null))
        assertNull(BridgePolicy.commandFromPath(""))
    }

    // ── 端点常量 ───────────────────────────────────────────────────────────

    @Test
    fun `只绑本机_端口避开 OpenCode 与 AI App Bridge`() {
        assertEquals("必须只绑 127.0.0.1（绑 0.0.0.0 等于对全网开放）", "127.0.0.1", BridgePolicy.BIND_HOST)
        assertEquals("端口须避开 OpenCode 的 14000", 14080, BridgePolicy.PORT)
        assertTrue("也不该撞 AI App Bridge 的 18080", BridgePolicy.PORT != 18080)
    }
}

/** [BridgeToken] 的纯函数内核单测（存取层要 Context，不在这里测）。 */
class BridgeTokenTest {

    private fun seededRandom(): SecureRandom {
        // 固定种子 ⇒ token 可复现（测"生成逻辑"而不是"随机性"）
        return SecureRandom.getInstance("SHA1PRNG").apply { setSeed("zhengdao-bridge-token-test".toByteArray()) }
    }

    @Test
    fun `token 是 64 位小写 hex_且同种子可复现`() {
        val a = BridgeToken.generateTokenHex(seededRandom())
        val b = BridgeToken.generateTokenHex(seededRandom())
        assertEquals("同种子必须可复现（否则测试无法断言）", a, b)
        assertEquals("32 字节 = 64 hex 位", 64, a.length)
        assertTrue("必须全是 hex 小写", a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `不同种子给出不同 token`() {
        val r1 = SecureRandom.getInstance("SHA1PRNG").apply { setSeed("seed-a".toByteArray()) }
        val r2 = SecureRandom.getInstance("SHA1PRNG").apply { setSeed("seed-b".toByteArray()) }
        assertTrue(BridgeToken.generateTokenHex(r1) != BridgeToken.generateTokenHex(r2))
    }

    @Test
    fun `Basic 头解析_容忍大小写与空白_不容忍残缺`() {
        val token = "abc123"
        // ⚠️ 这里只能用 java.util.Base64 —— **不许**用 android.util.Base64：
        // JVM 单测里 android.jar 是 stub，`android.util.Base64.encodeToString` 会抛
        // `java.lang.RuntimeException: Stub!`（ERRATA E-130 同款坑，本文件首版真踩了）。
        // 这也正是 BridgeToken 把 parseBasic 做成"只吃 String"的纯函数的原因。
        val jvmB64 = java.util.Base64.getEncoder().encodeToString("browser:$token".toByteArray())
        assertEquals("browser" to token, BridgeToken.parseBasic("Basic $jvmB64"))
        assertEquals("basic 小写也认", "browser" to token, BridgeToken.parseBasic("basic $jvmB64"))
        assertEquals("BASIC 大写也认", "browser" to token, BridgeToken.parseBasic("BASIC $jvmB64"))
        assertEquals("首尾空白容忍", "browser" to token, BridgeToken.parseBasic("  Basic   $jvmB64  "))
        assertNull("没有冒号 ⇒ 解不出 user:pass", BridgeToken.parseBasic("Basic " + jvmB64Of("browsertoken")))
        assertNull("base64 坏串", BridgeToken.parseBasic("Basic !!!not-base64!!!"))
        assertNull("空 header", BridgeToken.parseBasic(null))
        assertNull("空串", BridgeToken.parseBasic(""))
        assertNull("不是 Basic 方案", BridgeToken.parseBasic("Bearer $jvmB64"))
        assertNull("只有一个词", BridgeToken.parseBasic("Basic"))
    }

    private fun jvmB64Of(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    @Test
    fun `校验要求用户名固定 browser_口令恒定时间比较`() {
        val token = BridgeToken.generateTokenHex(seededRandom())
        val ok = "Basic " + jvmB64Of("browser:$token")
        assertTrue("正确凭据必须过", BridgeToken.matches(token, ok))
        assertFalse("用户名错了不过（哪怕口令对）", BridgeToken.matches(token, "Basic " + jvmB64Of("admin:$token")))
        assertFalse("口令差一位也不过", BridgeToken.matches(token, "Basic " + jvmB64Of("browser:${token.dropLast(1)}0")))
        assertFalse("空 header 不过", BridgeToken.matches(token, null))
        assertFalse("空期望 token 不过（没初始化就不该放行）", BridgeToken.matches("", ok))
    }

    @Test
    fun `打码只露前后各 4 位`() {
        // 64 位 hex（与 generateTokenHex 的输出同长）；别手数 —— 用 repeat 拼，长度写死
        val t = "0123456789abcdef".repeat(4) // 16×4 = 64
        assertEquals(64, t.length)
        val m = BridgeToken.masked(t)
        assertEquals("0123…cdef", m)
        assertFalse("打码串不许含中段", m.contains("89ab"))
        assertEquals("****", BridgeToken.masked("abcd"))
        assertEquals("****", BridgeToken.masked(""))
    }
}
