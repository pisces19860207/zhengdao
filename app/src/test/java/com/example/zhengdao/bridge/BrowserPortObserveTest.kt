// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.bridge

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 645：OBSERVE（固定观察面）的判据——**端口收了什么、拒了什么，在这里验**。
 *
 * 判据对照（工单 §门禁改动）：
 * - **OBSERVE 脚本 hash 与 assets 一致**（判据④正面）：资产文件算出的 SHA-256 必须
 *   等于 `ObserveAsset.SHA256` 写死值——改脚本必须改 hash（等于强制 review）；
 * - **hash 不符 ⇒ 红**（判据④负例）：`verify` 对篡改文本必须 false（运行时 fail-closed
 *   拒执行并记账的判据本体）；
 * - **传自定义脚本 ⇒ 拒**（判据③负例）：OBSERVE 的执行体只有 assets 那一份，
 *   `script` 字段非空当场被参数闸拒——负例断言 deny＋记账；
 * - **EXEC_JS 仍不可用**（判据②负例）：645 不放开自由 JS——EXEC_JS 保持静态 EXTERNAL，
 *   dispatch 一律回闸；
 * - **隐私三闸三条夹具**（判据⑤）：三闸在**页内源头**执行（assets 固定脚本），JVM 无
 *   JS 引擎 ⇒ 夹具按仓库既有口径对**脚本源头**钉断言（LearnBoardGateTest 对 624 页内
 *   脚本的同款做法）：①敏感整条丢（词表齐＋每个收集器源头就有闸）②值只出形状
 *   （`.value` 只许出现在 shp() 读值处，回包键封闭集）③不回整页 HTML（HTML 通道零命中）。
 *   变异口径见 645 交付报告 §负例。
 *
 * JVM 约束说明：执行层（BrowserCommands→BrowserTabManager）需要真机标签；本文件的
 * "过闸"用例都断言到「闸放行＋执行段如实拒（无上下文）」为止，不造 WebView。
 */
class BrowserPortObserveTest {

    private val events = mutableListOf<BrowserPort.LedgerEvent>()
    private var now = 1_000_000L
    private var halted = false

    @Before
    fun install() {
        events.clear()
        now = 1_000_000L
        halted = false
        BrowserPort.install(
            halt = { halted },
            ledger = { events += it },
            nowMs = { now },
            rateMax = 5,
            rateWindowMs = 60_000L,
        )
    }

    @After
    fun restore() {
        BrowserPort.resetForTest()
    }

    /** 资产文件（工作目录 = app/ 模块根；兜底仓根，与 SourceGateTest 同款双路）。 */
    private fun assetFile(): File {
        val f = File("src/main/assets/${ObserveAsset.PATH}")
            .takeIf { it.isFile }
            ?: File("app/src/main/assets/${ObserveAsset.PATH}")
        assertTrue("找不到 OBSERVE 固定脚本资产：${f.path}", f.isFile)
        return f
    }

    private fun assetText(): String = assetFile().readText()

    /**
     * 剥掉整行 `//` 注释后的脚本代码文本（E-133 同款口径：头注释里提「outerHTML」
     * 「el.value」是说明三闸的存在，不是 HTML 通道本身——三闸夹具只认可执行代码）。
     * 本脚本的注释全部是整行形态；hash 判据仍用未剥的 [assetText]（对资产原文负责）。
     */
    private fun assetCode(): String = assetText().replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun observeCall(item: ObserveItem?) = BrowserPort.PortCall(
        caller = BrowserPort.PortCaller.BAOPU_AGENT,
        action = BrowserPort.PortAction.OBSERVE,
        observeItem = item,
    )

    // ── 判据④：OBSERVE 脚本 hash 与 assets 一致（改脚本必须改 hash） ──────────

    @Test
    fun `判据④ OBSERVE脚本hash与assets一致_写死值就是64位hex`() {
        assertTrue(
            "ObserveAsset.SHA256 必须是 64 位小写 hex",
            ObserveAsset.SHA256.matches(Regex("[0-9a-f]{64}")),
        )
        assertTrue(
            "assets 里的固定脚本被改过而 hash 没改——改脚本必须同步改 ObserveAsset.SHA256（强制 review）",
            ObserveAsset.verify(assetText()),
        )
    }

    @Test
    fun `判据④负例_hash不符_verify必拒`() {
        val tampered = assetText() + "\n// 顺手改一下（闸必须红）"
        assertFalse("篡改后的脚本不许过 hash 闸", ObserveAsset.verify(tampered))
        assertFalse("空白差异也算改", ObserveAsset.verify(assetText().replace("var CAP=120", "var CAP=121")))
    }

    @Test
    fun `sha256Hex已知向量与行尾归一`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ObserveAsset.sha256Hex("abc"),
        )
        // Windows checkout 换行不算「改了脚本」：CRLF/CR 归一成 LF 后同 hash
        assertEquals(ObserveAsset.sha256Hex("a\nb"), ObserveAsset.sha256Hex("a\r\nb"))
        assertEquals(ObserveAsset.sha256Hex("a\nb"), ObserveAsset.sha256Hex("a\rb"))
    }

    @Test
    fun `wrap_唯一变量是枚举字面量_脚本本体原样内嵌`() {
        val script = "(function(item){return item})"
        assertEquals("(function(item){return item})(\"fields_text\")", ObserveAsset.wrap(script, ObserveItem.FIELDS_TEXT))
        assertEquals("(function(item){return item})(\"value_shape\")", ObserveAsset.wrap(script, ObserveItem.VALUE_SHAPE))
    }

    @Test
    fun `枚举三wire与脚本分支一一对应_回包出口恰好三处`() {
        val asset = assetCode()
        for (item in ObserveItem.entries) {
            assertTrue(
                "脚本必须处理观察项 ${item.wire}（枚举与脚本分支一一对应，防两边漂）",
                asset.contains("item==='" + item.wire + "'"),
            )
        }
        // 回包键封闭集：三处 JSON.stringify 恰好是三个观察项的回包，没有别的出口
        assertEquals(3, asset.split("JSON.stringify(").size - 1)
    }

    // ── 判据③负例：传自定义脚本 ⇒ 拒（并记账） ──────────────────────────────

    @Test
    fun `判据③负例_OBSERVE传自定义脚本_参数闸当场拒并记账`() {
        val o = BrowserPort.dispatch(
            observeCall(ObserveItem.NODE_TABLE).copy(script = "return document.cookie"),
        )
        assertTrue(o is BrowserPort.PortOutcome.Stopped)
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertEquals("deny", stop.wire)
        assertTrue(stop.reason.contains("不收脚本"))
        // 拒了什么要找得到：闸停必记账
        assertEquals(1, events.size)
        assertEquals("deny", events.single().kind)
        assertEquals(BrowserPort.PortAction.OBSERVE, events.single().action)
    }

    @Test
    fun `OBSERVE缺观察项_参数闸拒`() {
        val o = BrowserPort.dispatch(observeCall(null))
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertEquals("deny", stop.wire)
        assertTrue(stop.reason.contains("观察项"))
    }

    @Test
    fun `OBSERVE合法调用过五道闸_无上下文在执行段如实拒`() {
        // observeItem 合法且不带 script ⇒ 参数闸放行；JVM 没有 Context ⇒ 执行段拒读资产
        // （真机上这一步之后是读 assets → 核 hash → execJs，见 observeRun）
        val o = BrowserPort.dispatch(observeCall(ObserveItem.VALUE_SHAPE))
        assertTrue(o is BrowserPort.PortOutcome.Stopped)
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertEquals("deny", stop.wire)
        assertTrue(stop.reason.contains("上下文"))
        // 这是执行段的形状问题，不是闸的拒绝：不记账
        assertTrue(events.isEmpty())
    }

    // ── 判据②负例：EXEC_JS 仍不可用（不因本单放开） ──────────────────────────

    @Test
    fun `判据②负例_EXEC_JS仍静态EXTERNAL_回闸不执行`() {
        assertEquals(BrowserPort.PortTier.EXTERNAL, BrowserPort.PortAction.EXEC_JS.tier)
        assertEquals(BrowserPort.PortTier.READ, BrowserPort.PortAction.OBSERVE.tier)
        val o = BrowserPort.dispatch(
            BrowserPort.PortCall(
                caller = BrowserPort.PortCaller.BAOPU_AGENT,
                action = BrowserPort.PortAction.EXEC_JS,
                script = "return 1+1",
            ),
        )
        val stop = (o as BrowserPort.PortOutcome.Stopped).stop
        assertEquals("escalate", stop.wire)
        assertTrue(stop.reason.contains("回抱朴走闸"))
        assertEquals("escalate", events.single().kind)
    }

    @Test
    fun `observe类型化入口_闸停翻译成带stop键的JSON`() {
        val json = BrowserPort.observe(
            BrowserPort.PortCaller.BAOPU_AGENT,
            ObserveItem.NODE_TABLE,
            context = null,
            tabId = null,
            policy = BrowserPort.PortPolicy.OPEN,
        )
        assertTrue(json.contains("\"stop\":\"deny\""))
        assertTrue(json.contains("上下文"))
    }

    // ── 判据⑤：页内隐私三闸三条夹具（对脚本源头钉断言） ──────────────────────

    @Test
    fun `判据⑤夹具①_密码验证码整条丢_词表齐_三个收集器源头就有闸`() {
        val asset = assetCode()
        // 敏感词表与 624 收割脚本同源（密码/验证码/校验码/captcha/otp/短信 + pass/pwd 前缀族）
        assertTrue(asset.contains("SENS=/pass|pwd|密码|验证码|校验码|captcha|otp|短信/i"))
        // autocomplete 敏感值（一次性码/卡号/密码族）
        assertTrue(asset.contains("ACS=/one-time-code|cc-|current-password|new-password/i"))
        // 密码框按 type 整条丢
        assertTrue(asset.contains("=='password'"))
        // 三个收集器（nodes/fields/shapes）每个的循环**源头**就是闸：sens 命中整条跳过
        assertEquals(
            "三个收集器都必须在源头丢敏感元素（密码/验证码连身份面都不许出页）",
            3,
            asset.split("if(sens(el))continue").size - 1,
        )
    }

    @Test
    fun `判据⑤夹具②_值只出形状不出值_点value只许出现在shp读值处`() {
        val asset = assetCode()
        // 形状函数在：五形出页（empty/short/digits/date/long）
        assertTrue(asset.contains("function shp("))
        assertTrue(asset.contains("'empty'") && asset.contains("'digits'") && asset.contains("'date'"))
        // 闸的本体：`.value` 每一处都必须是 shp(el.value …) 的读值——多一处
        // `value:` 之类的直取（例如把 el.value 塞进回包）都会让两个计数失配 ⇒ 红
        val valueReads = asset.split(".value").size - 1
        val shapeReads = asset.split("shp(el.value").size - 1
        assertEquals("el.value 只许在读进 shp() 时碰（值本身不出页）", shapeReads, valueReads)
        // 回包里没有 value 键（形状键是 vs）
        assertFalse(asset.contains("value:"))
        // value_shape 的回包行只带 tag/xp/inputType/vs 四键
        assertTrue(asset.contains("vs:shp(el.value||'')"))
    }

    @Test
    fun `判据⑤夹具③_不回整页HTML_HTML通道零命中`() {
        val asset = assetCode()
        for (channel in listOf("outerHTML", "innerHTML", "document.write", "insertAdjacentHTML", "documentElement")) {
            assertFalse("脚本不许有 HTML 通道：$channel", asset.contains(channel))
        }
    }
}
