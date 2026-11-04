// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.bridge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BrowserCommands 的结构守卫（383 批自原 `BrowserBridgeGateTest` 迁入，2026-11-05）。
 *
 * ## 为什么这批判据活过了"删桥"
 *
 * 原 `BrowserBridgeGateTest` 十组判据里，①②③④⑦⑨（bind/白名单 dispatch/启动点/token/
 * 桥线程/param）钉的是 HTTP 桥本身，随桥（383 批，用户裁定）一起删除；**⑤⑥⑩ 三组
 * 从第一天起读的就是 [BrowserCommands] 的源码**——抱朴（`BaopuBrowserWiring`）与玄览
 * 至今共用这一层，判据原文未动、只换了个文件继续守：
 *
 * - **⑤ 脱敏不可绕**：execJS/snapshot 的返回必须过 [com.example.zhengdao.browser.SensitiveRedactor]
 *   （PII/键值对/DOM），结果限长防拉爆内存；
 * - **⑥ 不碰 App 私有数据**：命令层不许 openFileInput/filesDir 写入/起别的 App；
 *   截图只落共享目录；
 * - **⑩ 主线程亲和**：四条控制命令对 BrowserTabManager/WebView 的直调必须在
 *   `onMain` 块内（第㉓刀修的正是 HTTP 工作线程直调必崩这件事；命令层如今跑在
 *   调用方线程上，这条仍然成立）。
 *
 * 中英文案对称（原⑧前半）迁至 `StatutesSlimGateTest`；「桥的文案别再回来」那半
 * 本来就在那里。凡断言"某字样不出现"，先剥注释再断言（E-133 口径）。
 */
class BrowserCommandsGateTest {

    // ── 基础设施（工作目录 = app/，与仓库其它源码门禁一致） ────────────────

    private fun file(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    /** 剥注释：块注释整段、单行注释只认整行（`http://` 这种串里的 `//` 不能误伤）。 */
    private fun strip(s: String): String = s
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val commandsRel = "src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt"
    private val commands by lazy { strip(file(commandsRel)) }

    // ── ⑤ 脱敏不可绕（红线⑦） ─────────────────────────────────────────────

    @Test
    fun `离开命令层的数据一律过脱敏_execJS 与 snapshot 都拦`() {
        // 第㊵刀：判据从字面串升级为语义——execJs 函数体内必须同时过 redactText
        // （号码/PII 值）与 redactPairs（cookie/localStorage 键值对，P3 接线），
        // 少任何一个都算"调用方能读原始隐私值绕过脱敏"。
        val execJsBody = commands.substringAfter("fun execJs(").substringBefore("\n    fun ")
        assertTrue(
            "execJS 的返回值必须过 SensitiveRedactor.redactText —— 否则调用方用它读原始隐私值绕过脱敏",
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

    // ── ⑥ 不许碰 App 私有数据 ─────────────────────────────────────────────

    @Test
    fun `命令层不许碰 App 私有数据与其他 App`() {
        assertFalse("不许 openFileInput/openFileOutput（App 私有文件）", commands.contains("openFileInput") || commands.contains("openFileOutput"))
        assertFalse("不许直接拿 Context 的 filesDir 写东西", commands.contains("context.filesDir"))
        assertFalse("不许起别的 App", commands.contains("startActivity"))
        // 唯一允许的目录写入是截图落共享目录（ZhengdaoDirs.root = Downloads/证道）。
        // 第一百七十七批：目录名从 `screenshots` 改成 `截屏`（用户口径），落点仍必须是
        // 共享目录、仍不许是 App 私有目录 —— 判据跟着改名走，不是放宽。
        assertTrue(
            "截图必须落共享目录（不落 App 私有目录）",
            commands.contains("File(ZhengdaoDirs.root(context), SHOT_DIR)"),
        )
        assertTrue(
            "截图目录名必须是 截屏（用户 2026-10-24 口径）",
            commands.contains("internal const val SHOT_DIR = \"截屏\""),
        )
    }

    // ── ⑩ 主线程亲和（第㉓刀） ─────────────────────────────────────────────

    /**
     * 判别器：在（已剥注释的）BrowserCommands 源码里，找出「绕过 onMain 直调
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
                "以下调用点绕过了 onMain（工作线程直调）——第㉓刀修的正是这个：\n" +
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
