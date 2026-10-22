// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百三十六批 · 自定义通道协议选择》§1/§2。
//
// 为什么用源码扫描：本仓无 Robolectric，Compose 弹层在 JVM 里渲染不了（131 批同款做法）。
// 能钉死的是**接线**：协议选项在自定义展开后的第一项、Anthropic 不进拉清单分支、
// 协议从弹层一路传到 SP、Key 不因这次改动多出任何日志/字符串化出口。
// 判据本体（decode/端点/头）在 `CustomProtocolTest`，这里只钉 UI 与装配。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.ProviderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CustomProtocolGateTest {

    private val root = "src/main/java/com/example/zhengdao/"
    private val alt = "app/src/main/java/com/example/zhengdao/"

    private fun src(rel: String): String {
        val f = File(root + rel).let { if (it.exists()) it else File(alt + rel) }
        assertTrue("读不到 $rel（工作目录形态变了？$f）", f.exists())
        return f.readText()
    }

    /** 去掉注释，只留代码（负向判据用它；注释里写「不许 Log」不是违规）。 */
    private fun codeOnly(text: String): String {
        val sb = StringBuilder()
        var inBlock = false
        text.replace("\r\n", "\n").lines().forEach { raw ->
            val line = raw.trim()
            if (inBlock) {
                if (line.contains("*/")) inBlock = false
                return@forEach
            }
            if (line.startsWith("/*")) {
                if (!line.contains("*/")) inBlock = true
                return@forEach
            }
            if (line.startsWith("//") || line.startsWith("*")) return@forEach
            sb.append(stripLineComment(line)).append('\n')
        }
        return sb.toString()
    }

    /** 去掉行尾注释，但**不碰字符串里的 `//`**（`https://` 就长在字符串里）。 */
    private fun stripLineComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) { i += 2; continue }
            if (c == '"') inString = !inString
            if (!inString && c == '/' && i + 1 < line.length && line[i + 1] == '/') {
                return line.substring(0, i)
            }
            i++
        }
        return line
    }

    private fun between(text: String, startMark: String, endMark: String): String {
        val s = text.indexOf(startMark)
        assertTrue("首锚点没了：$startMark", s >= 0)
        val e = text.indexOf(endMark, s + startMark.length)
        assertTrue("尾锚点没了：$endMark", e > s)
        return text.substring(s, e)
    }

    private val sheetRaw = src("ui/baopu/BaopuChannelSheet.kt")
    private val sheet = codeOnly(sheetRaw)
    private val prefsRaw = src("baopu/ProviderPrefs.kt")
    private val draftRaw = src("ui/baopu/ModelSheetDraft.kt")
    private val screenRaw = src("ui/baopu/BaopuScreen.kt")
    private val runtimeRaw = src("baopu/BaopuRuntime.kt")
    private val rulesRaw = src("baopu/ModelPickerRules.kt")
    private val providerRaw = src("baopu/Provider.kt")

    // ── 1. 自定义展开后第一项＝协议二选一，再往下才是地址/模型/Key ──────────

    @Test
    fun 协议二选一是自定义展开后的第一项() {
        val block = between(
            sheetRaw,
            "// 第一百三十六批 · 自定义行展开后的第一项",
            "// ── 二、模型",
        )
        val openAi = block.indexOf("OpenAI 兼容")
        val anthropic = block.indexOf("Anthropic")
        val baseUrl = block.indexOf("接口地址（")
        assertTrue("OpenAI 兼容这一项没了", openAi >= 0)
        assertTrue("Anthropic 这一项没了", anthropic >= 0)
        assertTrue("接口地址必须在协议之后（协议是第一项）", baseUrl > anthropic)
        assertTrue("OpenAI 兼容在前、Anthropic 在后", openAi < anthropic)
        assertTrue("默认选中 OpenAI 兼容", block.contains("selected = protocolText == ProviderPrefs.PROTOCOL_OPENAI_COMPAT"))
        assertTrue("Anthropic 的选中态走同一个判据", block.contains("selected = protocolText == ProviderPrefs.PROTOCOL_ANTHROPIC"))
        assertTrue("两条子行各自写出真实端点，别让人猜", block.contains("/v1/chat/completions") && block.contains("/v1/messages"))
    }

    @Test
    fun 协议草稿位从SP起稿且能恢复() {
        assertTrue("起稿读 SP 归一值，而不是写死 OpenAI",
            sheetRaw.contains("ProviderPrefs.protocolKeyOf(customProtocol)"))
        assertTrue("跳玄览返回后读回草稿协议",
            sheetRaw.contains("if (restored) ModelSheetDraft.protocol"))
        assertTrue("协议改动回写草稿", sheetRaw.contains("ModelSheetDraft.protocol = protocolText"))
        assertTrue("切家时协议跟着这一家走",
            sheetRaw.contains("ModelSheetDraft.moveTo(id, baseUrlOf(id), modelOf(id), protocolOf(id))"))
        assertTrue("草稿对象有 protocol 位", draftRaw.contains("var protocol: String"))
        assertTrue("clear 会抹掉协议位",
            draftRaw.substringAfter("fun clear() {").substringBefore("}").contains("protocol = \"\""))
    }

    // ── 2. Anthropic 不拉清单，只显示手填提示 ─────────────────────────────

    @Test
    fun 自定义Anthropic不硬拉模型清单() {
        assertEquals("该协议需手填模型名", com.example.zhengdao.baopu.ModelPickerRules.ANTHROPIC_MANUAL_MODEL_HINT)
        val head = between(
            sheetRaw,
            "LaunchedEffect(selected, effectiveBase, keyForFetch, refreshTick, manualModelOnly)",
            "val neverCache = isFreeLane",
        )
        assertTrue("拉清单前先判 manualModelOnly", head.contains("if (manualModelOnly)"))
        assertTrue("命中就摆出那句话", head.contains("ModelPickerRules.ANTHROPIC_MANUAL_MODEL_HINT"))
        assertTrue("命中就直接收尾，不许落到 fetchEntries", head.contains("return@LaunchedEffect"))
        val beforeFetch = sheetRaw.substring(
            sheetRaw.indexOf("LaunchedEffect(selected, effectiveBase"),
            sheetRaw.indexOf("ModelCatalog.fetchEntries("),
        )
        assertTrue("manualModelOnly 的判断必须排在 fetchEntries 之前",
            beforeFetch.contains("manualModelOnly") && beforeFetch.contains("return@LaunchedEffect"))
        assertFalse("协议名不许写死字符串比较（用 ProviderPrefs 的线值）",
            sheet.contains("== \"anthropic\"") || sheet.contains("== \"openai_compat\""))
    }

    // ── 3. 协议持久化与装配：弹层 → Runtime → SP → resolve ────────────────

    @Test
    fun 协议从弹层一路传到ProviderPrefs() {
        assertTrue("SP 格名就是派工单写的 custom_protocol",
            prefsRaw.contains("KEY_CUSTOM_PROTOCOL = \"custom_protocol\""))
        assertTrue("save 写协议格", prefsRaw.contains("KEY_CUSTOM_PROTOCOL,"))
        assertTrue("resolve 按协议构造自定义 Provider",
            prefsRaw.contains("protocol = protocolOf(customProtocol)"))
        assertTrue("候选名单与回退也要带协议",
            prefsRaw.contains("customProtocol: String? = null"))
        assertTrue("弹层提交口带协议",
            sheetRaw.contains("if (isCustom) protocolText else null"))
        assertTrue("抱朴页把 SP 里的协议交给弹层",
            screenRaw.contains("customProtocol = ProviderPrefs.storedCustomProtocol(context)"))
        assertTrue("Runtime 的 switchChannel 把协议落进 save",
            runtimeRaw.contains("model, protocol)"))
        assertTrue("运行时读 SP 的协议（降级候选不许丢协议）",
            runtimeRaw.contains("ProviderPrefs.storedCustomProtocol(appCtx)"))
    }

    // ── 4. Key 纪律：这次改动不新增任何字符串化/日志出口 ───────────────────

    @Test
    fun 本批新增代码不打印不内插Key() {
        listOf(sheetRaw, prefsRaw, draftRaw, rulesRaw).map(::codeOnly).forEach { code ->
            listOf("Log.", "println(", "print(", "android.util").forEach { needle ->
                assertFalse("协议这批的代码里出现了 $needle", code.contains(needle))
            }
        }
        listOf("println(", "android.util").forEach { needle ->
            assertFalse("Runtime/Screen 的新增行也不许自己打印：$needle", runtimeRaw.contains(needle) || screenRaw.contains(needle))
        }
        val protocolCode = codeOnly(sheetRaw) + codeOnly(prefsRaw) + codeOnly(draftRaw) + codeOnly(rulesRaw)
        assertFalse("不许把 Key 变量内插进任何字符串", Regex("\\$\\{?(key|apiKey)\\b").containsMatchIn(protocolCode))
        assertFalse("也不许内插 Provider 对象（data class toString 会带 apiKey）",
            Regex("\\$\\{?(provider|p|custom)\\b").containsMatchIn(protocolCode))
    }

    // ── 5. baseUrl 安全校验与 Provider 数据层一行不动 ──────────────────────

    @Test
    fun 协议选择不碰Provider的URL安全校验() {
        assertTrue("resolve 仍先过 isPermittedBaseUrl", prefsRaw.contains("Provider.isPermittedBaseUrl(base)"))
        val providerCode = codeOnly(providerRaw)
        assertTrue("许可判据本体还在", providerCode.contains("fun isPermittedBaseUrl(url: String): Boolean"))
        assertTrue("https 放行", providerCode.contains("url.startsWith(\"https://\")"))
        assertTrue("环回 http 放行", providerCode.contains("url.startsWith(\"http://127.0.0.1\")"))
        assertTrue("localhost 放行", providerCode.contains("url.startsWith(\"http://localhost\")"))
        // ProviderPrefs 的协议归一只是二选一，不新增第三个枚举值。
        assertTrue(ProviderPrefs.protocolKeyOf("whatever") == ProviderPrefs.PROTOCOL_OPENAI_COMPAT)
    }
}
