// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-补-18（2026-10-16）：字号阶梯守卫。
 *
 * 台账条目（`docs/FEATURE-LEDGER.md` §B-补 ④）：
 * `- **B-补-18**（美化第 6 条 / 统一美化方案批次 3）字号没补：ui/theme/Type.kt 仍只 3 档
 *  （bodyLarge 16sp / titleLarge 22sp / labelSmall 11sp），iOS 目标 34 / 17 / 15 / 13 / 10sp
 *  一个都没落`。
 *
 * 病根是**没有阶梯**：原先只显式覆盖 `bodyLarge`，`titleLarge` / `labelSmall` 躺在注释块里，
 * 其余槽位吃 Material 默认 —— 同一界面里 12 / 14 / 16 / 22 各来一遍，层级靠碰巧。
 *
 * 本刀把 app 实际用到的 11 个槽位写成显式档位（2026-10-16 逐文件 grep 过用到哪些槽位），
 * 并借 iOS 的层级间距：34（品牌/首屏大标题）/ 22（区块标题）/ 17（正文）/ 15（次级正文）/
 * 13（脚注）/ 10（最小标记）。未用到的槽位**保持 Material 默认**，不许现在瞎定尺寸。
 *
 * 判据一律读**剥掉注释并归一 EOL**后的源码 —— 类 KDoc 与文件里的对照表本身就写着
 * `34sp` / `17sp` 这些数字，直接对原文断言会把「解释设计的表」当成设计本身。
 */
class TypeScaleGuardTest {

    private val rel = "app/src/main/java/com/example/zhengdao/ui/theme/Type.kt"

    /** 单测的工作目录是模块目录（`app/`），也可能是仓库根。 */
    private fun repoFile(): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    private fun code(): String = repoFile().readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 本刀定下的档位表：槽位 → (fontSize, lineHeight)。 */
    private val ladder = linkedMapOf(
        "displayLarge" to (34.0 to 41.0),   // 品牌/首屏大标题（iOS largeTitle 34）
        "headlineSmall" to (22.0 to 28.0),  // 区块标题（iOS title2 22）
        "titleLarge" to (22.0 to 28.0),     // 区块标题：抽屉/调试页
        "titleMedium" to (17.0 to 22.0),    // 列表主标题 / B-补-17 收起后的大标题
        "bodyLarge" to (17.0 to 22.0),      // 正文
        "bodyMedium" to (15.0 to 20.0),     // 次级正文
        "titleSmall" to (15.0 to 20.0),     // 小节标题
        "labelLarge" to (15.0 to 20.0),     // 按钮文字
        "bodySmall" to (13.0 to 18.0),      // 脚注、说明
        "labelMedium" to (13.0 to 18.0),    // 小标签
        "labelSmall" to (10.0 to 13.0),     // 最小标记
    )

    /** 取某个槽位在 `Typography(...)` 里的 (fontSize, lineHeight)；取不到就断言失败。 */
    private fun slot(slot: String): Pair<Double, Double> {
        val src = code()
        val m = Regex(Regex.escape(slot) + "\\s*=\\s*TextStyle\\(([^)]*)\\)", RegexOption.DOT_MATCHES_ALL)
            .find(src)
        assertTrue("Type.kt 里找不到槽位 $slot = TextStyle(…)", m != null)
        val body = m!!.groupValues[1]
        val fs = Regex("fontSize = ([0-9]+(?:\\.[0-9]+)?)\\.sp").find(body)
        val lh = Regex("lineHeight = ([0-9]+(?:\\.[0-9]+)?)\\.sp").find(body)
        assertTrue("槽位 $slot 没有显式 fontSize", fs != null)
        assertTrue("槽位 $slot 没有显式 lineHeight", lh != null)
        return fs!!.groupValues[1].toDouble() to lh!!.groupValues[1].toDouble()
    }

    @Test
    fun `门禁_B补18_十一个槽位都写成了显式档位`() {
        val missing = ladder.keys.filterNot { code().contains("$it = TextStyle(") }
        assertEquals("这些槽位没有显式档位（还吃着 Material 默认值）：$missing", emptyList<String>(), missing)
        ladder.forEach { (slotName, want) ->
            val got = slot(slotName)
            assertEquals("槽位 $slotName 的字号不对（设计表 $want）", want.first, got.first, 0.001)
        }
    }

    @Test
    fun `门禁_B补18_行高与字号成比例`() {
        ladder.forEach { (slotName, _) ->
            val (fs, lh) = slot(slotName)
            assertTrue("槽位 $slotName 的行高 $lh 小于字号 $fs（多行中文会挤在一起）", lh >= fs)
            assertTrue("槽位 $slotName 的行高 $lh 相对字号 $fs 过大（>1.5×，行距散架）", lh <= fs * 1.5)
        }
    }

    @Test
    fun `门禁_B补18_档位从大到小且恰好六档`() {
        val sizes = ladder.map { (slotName, _) -> slot(slotName).first }
        val distinct = sizes.distinct().sortedDescending()
        assertEquals("阶梯应当恰好是 34 / 22 / 17 / 15 / 13 / 10 六档", listOf(34.0, 22.0, 17.0, 15.0, 13.0, 10.0), distinct)
        // B-补-17 的「大标题 → 收起成紧凑标题」要有明确落差，否则收缩看不出来
        val gap = slot("titleLarge").first - slot("titleMedium").first
        assertTrue("titleLarge 与 titleMedium 的落差只有 $gap sp，B-补-17 的收缩会看不出来", gap >= 4.0)
    }

    @Test
    fun `门禁_B补18_未使用的槽位不许瞎定尺寸`() {
        // 这四个槽位 2026-10-16 全仓 grep 不到使用点 —— 将来真要用时再按设计表补一档
        listOf("displayMedium", "displaySmall", "headlineMedium", "headlineLarge").forEach { unused ->
            assertFalse(
                "槽位 $unused 目前无人使用，不应替将来的用法瞎定尺寸（要加请连同使用点与门禁一起加）",
                code().contains("$unused = TextStyle("),
            )
        }
    }

    @Test
    fun `门禁_B补18_注释块里的老字号不许回潮`() {
        val raw = repoFile().readText()
        assertFalse(
            "又出现了 Material 默认样式的注释块（本刀已把它整块替换成真实档位表）",
            raw.contains("Other default text styles to override"),
        )
        assertFalse("老字号 16.sp 不许作为 bodyLarge 回潮", code().contains("fontSize = 16.sp,\n        lineHeight = 24.sp"))
        assertTrue("文件里要留下本刀口径（B-补-18，2026-10-16）", raw.contains("B-补-18，2026-10-16"))
    }

    @Test
    fun `门禁_B补18_iOS对照表在文件里`() {
        val raw = repoFile().readText()
        listOf("largeTitle 34", "title2 22", "body 17", "subhead 15", "footnote 13", "caption 10").forEach { step ->
            assertTrue("对照表缺少「$step」这一档", raw.contains(step))
        }
    }
}
