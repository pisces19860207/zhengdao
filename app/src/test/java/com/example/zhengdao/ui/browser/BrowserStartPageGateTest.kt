// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玄览起始页源码门禁（第㊱刀，台账 §B 顺序 56）。
 *
 * ## 为什么值得一条门禁
 *
 * 起始页这刀最容易烂在两个地方，而且**都不会有运行期测试红**：
 * ① 后来者把空态分支里的 [BrowserStartPage] 换回一行裸 Text（或删掉调用）——
 *    界面立刻退回"像坏页"的样子，用户原话就是"以为是坏的"；
 * ② 后来者在起始页组件里"顺手"自己解析输入（再写一份 normalize、直接拿引擎
 *    loadUrl）——第二套解析一出现，地址栏与起始页对同一输入会走两条路，
 *    搜索引擎归一化规则从此分叉。这类回归只有源码门禁拦得住。
 *
 * ## 判据钉什么（ERRATA E-133 的教训：钉实质，不钉局部变量名）
 *
 * ① BrowserScreen 的空态分支里出现 `BrowserStartPage(` 组件调用；
 * ② 空态判据 `active == null || !active.state.everLoaded` 原样保留（只涨不落，
 *    不许借机改状态机语义）；
 * ③ 起始页的提交回调在 BrowserScreen 侧接到 `tryNavigate`（既有归一化 + 导航），
 *    且形态是 `onSearch = { ... tryNavigate ... }`；
 * ④ 起始页组件自身零解析、零引擎：剥注释后不得出现 `tryNavigate` /
 *    `normalizeInput` / `Uri.parse` / `engine.` / `loadUrl(`——它是纯 UI + 回调。
 *
 * ## 正例 + 负例自证
 *
 * 每条判据都抽成**纯函数**；正例打真源码，负例打内嵌的反例字符串
 * （模拟"把空态改回裸 Text / 起始页自写解析 / 提交绕开 tryNavigate"三种种
 * 退化形态），断言判据在反例上**必然不通过**——证明判据真有牙，
 * 不是摆设。谁把空态真改回裸 Text，正例用例必红。
 */
class BrowserStartPageGateTest {

    // ── 源码读取（照 NavTransitionGateTest 的双路径兜底：cwd 可能是仓根或 app/） ──

    private fun readSource(relativeFromRoot: String, relativeFromApp: String, what: String): String {
        val roots = listOf(
            java.io.File(relativeFromRoot),
            java.io.File(relativeFromApp),
        )
        return roots.firstOrNull { it.isFile }?.readText()
            ?: error("找不到 $what（cwd=${java.io.File("").absolutePath}）")
    }

    private fun browserScreenSource(): String = readSource(
        "app/src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt",
        "src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt",
        "BrowserScreen.kt",
    )

    private fun startPageSource(): String = readSource(
        "app/src/main/java/com/example/zhengdao/ui/browser/BrowserStartPage.kt",
        "src/main/java/com/example/zhengdao/ui/browser/BrowserStartPage.kt",
        "BrowserStartPage.kt",
    )

    /**
     * 去注释（**必须做**，NavTransitionGateTest 第 19 刀同款教训）：本测试文件与
     * BrowserScreen/BrowserStartPage 的 KDoc 里到处写着 `BrowserStartPage(`、
     * `tryNavigate` 这些词——不剥注释就是自己拦自己，或注释里写一句就算过。
     * **凡断言，先剥注释再断言**（正例负例同此）。
     */
    private fun stripped(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    // ── 判据纯函数（正例负例共用同一份牙口） ──

    /** 判据 A：空态分支出现起始页组件调用。 */
    private fun hasStartPageCall(src: String): Boolean = src.contains("BrowserStartPage(")

    /** 判据 B：空态判据原样保留（everLoaded 只涨不落，一字不许动）。 */
    private fun everLoadedGuardKept(src: String): Boolean =
        Regex("active\\s*==\\s*null\\s*\\|\\|\\s*!active\\.state\\.everLoaded").containsMatchIn(src)

    /** 判据 C：起始页提交回调接到 tryNavigate（既有归一化路径）。 */
    private fun searchRoutedViaTryNavigate(src: String): Boolean =
        Regex("onSearch\\s*=\\s*\\{[\\s\\S]{0,400}?tryNavigate").containsMatchIn(src)

    /** 判据 D：起始页组件自身零解析零引擎（返回违禁字样清单，空 = 干净）。 */
    private fun forbiddenInStartPage(src: String): List<String> =
        listOf("tryNavigate", "normalizeInput", "Uri.parse", "engine.", "loadUrl(")
            .filter { src.contains(it) }

    // ── 正例：打真源码 ──

    @Test
    fun `空态分支调用起始页组件`() {
        val src = stripped(browserScreenSource())
        assertTrue(
            "BrowserScreen 空态分支没有 BrowserStartPage 调用 —— 空态退回裸 Text 就是" +
                "用户说的「以为是坏的」（第㊱刀成果被拆了）",
            hasStartPageCall(src),
        )
    }

    @Test
    fun `空态判据原样保留`() {
        val src = stripped(browserScreenSource())
        assertTrue(
            "空态判据 active == null || !everLoaded 被改动了 —— 判据只涨不落是" +
                "状态机语义红线，不许借起始页这刀顺手改",
            everLoadedGuardKept(src),
        )
    }

    @Test
    fun `起始页搜索走既有归一化导航`() {
        val src = stripped(browserScreenSource())
        assertTrue(
            "onSearch 回调没有接到 tryNavigate —— 起始页提交必须复用既有归一化" +
                "（normalizeInput 的 bing 兜底 + scheme 预检），严禁第二套解析",
            searchRoutedViaTryNavigate(src),
        )
    }

    @Test
    fun `起始页组件自身零解析零引擎`() {
        val src = stripped(startPageSource())
        val hits = forbiddenInStartPage(src)
        assertTrue(
            "BrowserStartPage.kt 出现了不该有的字样 $hits —— 它是纯 UI + 回调，" +
                "解析与导航一律经 onSearch 回 BrowserScreen 走 tryNavigate",
            hits.isEmpty(),
        )
    }

    // ── 负例自证：打内嵌反例，证明判据在退化形态上必不通过 ──

    @Test
    fun `负例_空态改回裸Text时判据A必拦`() {
        val 反例 = """
            if (active == null || !active.state.everLoaded) {
                Text(
                    text = stringResource(R.string.browser_empty_hint),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        """.trimIndent()
        assertFalse("判据 A 对裸 Text 空态应返回 false（否则正例就是摆设）", hasStartPageCall(反例))
        // 同一反例上判据 B 仍应通过（裸 Text 也保留了判据行）——证明 A 与 B 各管各的
        assertTrue(everLoadedGuardKept(反例))
    }

    @Test
    fun `负例_起始页自写解析时判据D必拦`() {
        val 反例 = """
            fun StartPageFake(onSearch: (String) -> Unit) {
                val url = Uri.parse(input).toString()
                engine.loadUrl(url)
            }
        """.trimIndent()
        val hits = forbiddenInStartPage(反例)
        assertTrue("判据 D 对自写解析的假起始页应报出违禁字样（否则是摆设）", hits.isNotEmpty())
    }

    @Test
    fun `负例_提交绕开tryNavigate时判据C必拦`() {
        val 反例 = """
            BrowserStartPage(
                onSearch = { input -> loadUrl(input) },
            )
        """.trimIndent()
        assertFalse("判据 C 对绕开 tryNavigate 的提交应返回 false（否则是摆设）", searchRoutedViaTryNavigate(反例))
    }
}
