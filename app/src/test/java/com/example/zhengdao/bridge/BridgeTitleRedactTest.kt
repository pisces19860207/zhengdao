// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.bridge

import com.example.zhengdao.browser.SensitiveRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第48刀A（台账 §B 76 / 派工单 ㊽-A）：桥 tab 标题脱敏门禁。
 *
 * 背景（第㉟刀真机验收批 N2）：`status` 回包里 `url` 已由第㊵刀 `redactUrl` 打码，
 * **同一响应里的 `title` 却明文带出手机号**（注入标题「订单通知 张三 13800138000」⇒
 * 回包 title 原样）。第㊵刀留下的注释把「title 不脱敏」写成已声明边界，本刀按
 * **扩边界**施工：title 统一走 [SensitiveRedactor.redactTabTitle]
 * （redactUrl 两段式 ＋ & 终止键值模式），并扫全类同类裸字段。
 *
 * ## 三层判据
 *
 * 1. **响应体级正例**：经 [BrowserCommands.statusJson]（第48刀A 抽出的无 Android 内核）
 *    直接断言回包 —— 标题含 PHONE / ID_CARD / BANK_CARD / 敏感键值（`token=`）/ URL 形
 *    query，回包里必须只剩 `***`。
 * 2. **负例自证**：普通中文标题（「百度一下」）零误改；带 `?` 但无敏感键的标题不误改；
 *    url 的既有打码（第㊵刀）不得回退；空标题/空 url 不炸。
 * 3. **源码门禁（判据 C）**：响应体里的 title 出口（`"title" to` / `summary.title` 行）
 *    必须含 `redactTabTitle`；第㊵刀那句「title 不脱敏」注释必须已移除、字段级边界表
 *    必须在场。负例：把 title 改回裸值 ⇒ 门禁必红。
 *
 * ## 对派工单措辞的两处对账（本实现的事实）
 *
 * - **没有 `tabs` 命令**：桥白名单 11 条（设计文档 §4.5 全表，`BridgePolicyTest` 钉住
 *   数量），不存在「tabs 列表」回包；派工单㊽-A 的「tabs 列表」在这份代码里无落点。
 *   将来若新增，先补脱敏边界表再过门禁 —— 下方 `白名单没有 tabs 命令` 用例即是钉子。
 * - **`newTab` 回执无标题位**：回执只有 tabId/tabCount（非页面数据，见边界表的
 *   「刻意不进管线」清单），无 title 可脱。
 */
class BridgeTitleRedactTest {

    // ── 源码读取（双路径兜底：cwd 可能是仓根或 app/，同 BridgeEvalAndUrlGateTest） ──

    private fun readSource(relativeFromRoot: String, relativeFromApp: String, what: String): String {
        val roots = listOf(
            java.io.File(relativeFromRoot),
            java.io.File(relativeFromApp),
        )
        return roots.firstOrNull { it.isFile }?.readText()
            ?: error("找不到 $what（cwd=${java.io.File("").absolutePath}）")
    }

    private fun commandsSource(): String = readSource(
        "app/src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt",
        "src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt",
        "BrowserCommands.kt",
    )

    /** 去注释（必须先剥再断言，否则 KDoc 里提一嘴就算自己拦自己）。 */
    private fun stripped(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    /** 判据 C 的扫面对象：响应体里所有 title 出口行。 */
    private fun titleExportLines(src: String): List<String> =
        src.lines().filter { it.contains("\"title\" to") || it.contains("summary.title") }

    // ── 判据一：响应体级正例（statusJson 内核，无需 WebView） ─────────────────

    @Test
    fun `正例_status 回包 title 手机号打码`() {
        val json = BrowserCommands.statusJson(
            tabId = 1,
            url = "https://example.com/orders",
            title = "订单通知 张三 13800138000",
            loading = false,
            tabCount = 1,
        )
        assertFalse("手机号不许明文回包", json.contains("13800138000"))
        assertTrue(json.contains("\"title\":\"订单通知 张三 ***\""))
    }

    @Test
    fun `正例_status 回包 title 身份证号打码`() {
        val json = BrowserCommands.statusJson(1, "https://example.com/kyc", "实名认证 11010119900307701X", false, 1)
        assertFalse(json.contains("11010119900307701X"))
        assertTrue(json.contains("实名认证 ***"))
    }

    @Test
    fun `正例_status 回包 title 银行卡号打码`() {
        val json = BrowserCommands.statusJson(1, "https://example.com/pay", "转账 6222021234567890123", false, 1)
        assertFalse(json.contains("6222021234567890123"))
        assertTrue(json.contains("转账 ***"))
    }

    @Test
    fun `正例_status 回包 title 裸键值打码`() {
        // 没有 ? 前缀 ⇒ redactUrl 整条走 redactText 不命中键值；靠 redactPairs 兜（execJS 同款）
        val json = BrowserCommands.statusJson(1, "https://example.com/s", "会话页 token=abc123456", false, 1)
        assertFalse(json.contains("abc123456"))
        assertTrue(json.contains("token=***"))
    }

    @Test
    fun `正例_status 回包 title 形如 URL 的 query 打码`() {
        val json = BrowserCommands.statusJson(
            1,
            "https://example.com/p",
            "https://shop.example.com/pay?sessionid=zzz123&email=a@b.com",
            false,
            1,
        )
        assertFalse(json.contains("zzz123"))
        assertFalse(json.contains("a@b.com"))
        assertTrue(json.contains("sessionid=***"))
        assertTrue(json.contains("email=***"))
    }

    @Test
    fun `正例_redactTabTitle 对无命中文本恒等`() {
        assertEquals("百度一下", SensitiveRedactor().redactTabTitle("百度一下"))
        assertEquals("", SensitiveRedactor().redactTabTitle(""))
        assertEquals("百度一下?q=你好", SensitiveRedactor().redactTabTitle("百度一下?q=你好"))
    }

    @Test
    fun `正例_URL 形标题脱敏不吃尾_后续参数保留`() {
        // 第48刀A 实测抓过的坑：redactPairs 的值类不认 & ⇒ & 后的参数被整段吞掉（吃尾比漏报更糟）
        val out = SensitiveRedactor().redactTabTitle("https://x.com/pay?token=zzz123&keep=1")
        assertFalse(out.contains("zzz123"))
        assertTrue(out.contains("token=***"))
        assertTrue("& 后的参数不许被吞", out.contains("keep=1"))
    }

    @Test
    fun `正例_裸键值串脱敏不吃尾`() {
        assertEquals("a=1&token=***&b=2", SensitiveRedactor().redactTabTitle("a=1&token=x&b=2"))
    }

    // ── 判据二：负例自证 ─────────────────────────────────────────────────────

    @Test
    fun `负例_普通中文标题与普通 url 零误改`() {
        val json = BrowserCommands.statusJson(1, "https://www.baidu.com", "百度一下", false, 1)
        assertTrue(json.contains("\"title\":\"百度一下\""))
        assertTrue(json.contains("\"url\":\"https://www.baidu.com\""))
        assertFalse("无 PII 的回包不许出现 ***", json.contains("***"))
    }

    @Test
    fun `负例_url 既有打码不得回退`() {
        // 第㊵刀的 redactUrl 口径：query 里的 email/token 必须继续打码
        val json = BrowserCommands.statusJson(
            1,
            "https://example.com/?email=me@example.com&token=abc",
            "普通标题",
            false,
            1,
        )
        assertFalse(json.contains("me@example.com"))
        assertFalse(json.contains("token=abc"))
        assertTrue(json.contains("email=***"))
        assertTrue(json.contains("token=***"))
        assertTrue(json.contains("\"title\":\"普通标题\""))
    }

    @Test
    fun `负例_空标题空 url 不炸且原样回`() {
        val json = BrowserCommands.statusJson(1, "", "", false, 1)
        assertTrue(json.contains("\"title\":\"\""))
        assertTrue(json.contains("\"url\":\"\""))
    }

    @Test
    fun `负例_statusJson 其余字段逐字保持`() {
        val json = BrowserCommands.statusJson(7, "https://example.com/x", "标题", true, 3)
        assertTrue(json.contains("\"loading\":\"true\""))
        assertTrue(json.contains("\"tabId\":\"7\""))
        assertTrue(json.contains("\"tabCount\":\"3\""))
        assertTrue(json.startsWith("{\"ok\":true,"))
    }

    // ── 判据三：源码门禁（判据 C） ───────────────────────────────────────────

    @Test
    fun `门禁_title 出口必经 redactTabTitle`() {
        val bad = titleExportLines(stripped(commandsSource())).filterNot { it.contains("redactTabTitle") }
        assertTrue("title 出口未过脱敏管线：$bad", bad.isEmpty())
    }

    @Test
    fun `门禁_旧不脱敏注释已移除_新边界表在场`() {
        val raw = commandsSource() // 不剥注释——这条注释本身就是判据
        assertFalse("第㊵刀那句「title 不脱敏」必须删掉，不许再留已声明边界", raw.contains("title 不脱敏"))
        assertTrue("字段级脱敏边界表必须在场", raw.contains("脱敏边界（第48刀A 逐字段核定"))
    }

    @Test
    fun `门禁_白名单没有 tabs 命令`() {
        // 派工单㊽-A 措辞里的「tabs 列表」在本实现无落点（11 条白名单由 BridgePolicyTest 钉数）；
        // 将来若新增 tabs，先补类 KDoc 脱敏边界表——本用例是钉子，红就是因为加了却没补。
        assertTrue(BridgePolicy.ALL.none { it.wireName == "tabs" })
    }

    @Test
    fun `负例_把 title 改回裸值必须不过门禁`() {
        val regressed = stripped(commandsSource())
            .replaceFirst("\"title\" to SensitiveRedactor().redactTabTitle(title)", "\"title\" to title")
            .replaceFirst("SensitiveRedactor().redactTabTitle(summary.title)", "summary.title")
        val bad = titleExportLines(regressed).filterNot { it.contains("redactTabTitle") }
        assertFalse("退化成裸 title 后门禁居然还过", bad.isEmpty())
    }
}
