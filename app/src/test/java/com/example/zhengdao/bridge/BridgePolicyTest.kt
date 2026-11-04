// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.bridge

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BridgePolicy] 的参数校验单测（383 批裁剪，2026-11-05）。
 *
 * ## 这里测什么、不再测什么
 *
 * 桥（HTTP 端点）已整体删除：原白名单（Command/checkCommand）、路径解析
 * （commandFromPath）、端点常量与硬红线名册的判据随桥清出；原 [BridgeToken]
 * 纯函数内核单测（`BridgeTokenTest`）一并删除——token 随桥消亡，没有替代物。
 *
 * 留下的是 BrowserCommands（抱朴/玄览共用命令层）**仍在调用**的三道参数闸：
 * navigate 的 scheme、selector 的注入逃逸、snapshot 的 mode。判定逻辑是纯函数
 * （零 android.* 依赖）："输入字符串 → 输出判定"，JVM 表驱动直测。
 *
 * ## 这里钉的是"安全判据"，不是"能跑"
 *
 * 参数最终会被拼进一段 JS / 交给 WebView 加载，**漏拦**的代价远大于**漏放**：
 * 漏放只是某个调用用不了（报错明确）；漏拦是拿到了设计说不该给的能力
 * （而且不会有任何测试变红）。所以下面每条都朝着"换种写法还能不能绕过"去写
 * （ERRATA E-133 的教训：判据钉实质，不钉字面）。
 */
class BridgePolicyTest {

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
}
