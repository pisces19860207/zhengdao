// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.mermaid

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * 637 · **随包离线渲染页的安全闸**（读文件断言，JVM 可跑）。
 *
 * 判据为什么放在这里而不是只写在报告里：这一批最容易被后来人"顺手优化"坏掉的
 * 就是安全配置——`securityLevel` 调成 'loose' 更好看、`htmlLabels` 打开排版更齐、
 * 有人嫌包大把 mermaid.min.js 换成 CDN。三条都能让"注入不执行 / 断网能用"当场失效，
 * 而且在真机上不一定立刻显形（联网时才炸）。所以钉成机器判据：谁改谁红。
 */
class MermaidAssetGateTest {

    /** mermaid 11.4.1 的 dist/mermaid.min.js（UMD/IIFE，无动态 import，file:// 可直载）。 */
    private val pinnedSha256 = "a43bc1afd446f9c4cc66ac5dd45d02e8d65e26fc5344ec0ef787f88d6ddb6f9e"

    private fun asset(path: String): File = SrcRoots.find("src/main/assets/$path")

    private fun html(): String = asset("mermaid/mermaid.html").readText()

    @Test
    fun `页面必须是严格模式且不许 HTML 标签排版`() {
        val s = html()
        assertTrue("securityLevel 必须显式写成 strict", s.contains("securityLevel: 'strict'"))
        assertTrue("htmlLabels 必须显式关掉", s.contains("htmlLabels: false"))
        assertTrue("flowchart 又要单独关一次（mermaid 的两层配置各管一段）", s.contains("flowchart: { htmlLabels: false"))
        assertFalse("不许出现 loose", s.contains("securityLevel: 'loose'"))
        assertFalse("不许打开 htmlLabels", s.contains("htmlLabels: true"))
    }

    @Test
    fun `页面不许引用任何网络地址`() {
        val s = html()
        assertFalse("页面里出现 http(s) 地址就说明有联网企图", s.contains("http://") || s.contains("https://"))
        assertTrue("唯一的脚本来源必须是同目录本地文件", s.contains("<script src=\"mermaid.min.js\"></script>"))
        // CSP 双保险：默认全禁、连接全禁
        assertTrue(s.contains("default-src 'none'"))
        assertTrue(s.contains("connect-src 'none'"))
    }

    @Test
    fun `源码不许拼进 innerHTML`() {
        val s = html()
        // 允许 innerHTML 的地方只有「清空」与「放 mermaid 自己产出的 svg」两处
        val innerHtmlAssignments = Regex("innerHTML\\s*=\\s*([^;]+)").findAll(s)
            .map { it.groupValues[1].trim() }
            .toList()
        assertTrue(
            "不许有第三种 innerHTML 赋值：$innerHtmlAssignments",
            innerHtmlAssignments.all { it == "''" || it == "svgText" },
        )
        // svgText 的来源必须是 mermaid 自己产出的 svg，而不是外部字符串
        assertTrue(s.contains("var svgText = res && res.svg ? res.svg : '';"))
    }

    @Test
    fun `mermaid 库是钉死的那一份且没有动态加载`() {
        val js = asset("mermaid/mermaid.min.js")
        assertTrue("随包库文件不见了", js.isFile)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(js.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertEquals("mermaid.min.js 被换过/改过 ⇒ 必须重新登记许可与体积", pinnedSha256, digest)

        val text = js.readText()
        assertFalse("动态 import 在 file:// 下会失败（离线页会变白）", text.contains("import("))
        assertFalse("ESM 导出在经典 script 标签下用不了", text.contains("export{"))
        assertTrue("必须自己挂到 globalThis.mermaid 上", text.contains("globalThis.mermaid"))
    }

    @Test
    fun `两个新资产都已登记进许可台账`() {
        // 与 tools/check-license-policy.py --assets 同一口径的本地副本：
        // 门禁没跑的时候，这条也能拦住"往 assets 丢东西不登记"。
        // 路径口径同 LicenseTextsTest：单测工作目录是 app/，仓库根在上一级。
        val repo = File("..")
        val thirdParty = File(repo, "licenses/ASSETS.md")
        assertTrue("找不到 licenses/ASSETS.md", thirdParty.isFile)
        val thirdText = thirdParty.readText()
        assertTrue("第三方文件必须登记在 ASSETS.md", thirdText.contains("mermaid/mermaid.min.js"))
        assertTrue("许可必须是 MIT", thirdText.contains("| `mermaid/mermaid.min.js` | 11.4.1 | MIT |"))

        val firstParty = File(repo, "licenses/FIRST-PARTY.md")
        assertTrue("找不到 licenses/FIRST-PARTY.md", firstParty.isFile)
        assertTrue("第一方文件必须登记在 FIRST-PARTY.md", firstParty.readText().contains("mermaid/mermaid.html"))
    }

    @Test
    fun `离屏取图的离线开关是真的在代码里`() {
        val shotter = SrcRoots.find("src/main/java/com/example/zhengdao/browser/offscreen/OffscreenShotter.kt").readText()
        assertTrue("必须断网", shotter.contains("blockNetworkLoads = true"))
        assertTrue("必须禁文件访问", shotter.contains("allowFileAccess = false"))
        assertTrue("必须禁跨源文件", shotter.contains("allowUniversalAccessFromFileURLs = false"))
        // 判"真的没装桥"，不是判"文件里没有这几个字"——注释里出现字样不算违规
        assertFalse(
            "红线：离屏页绝不许装 JS 桥",
            Regex("""addJavascriptInterface\s*\(""").containsMatchIn(shotter),
        )
    }
}
