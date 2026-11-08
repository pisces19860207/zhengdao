// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百五十五批（御案卡美化）判据①-⑦：御印／朱印角戳／左朱条／君主动词／副标题，
// 以及「零结构变化、零新色相」。UI 测试基建同 `ContainerTierGuardTest`：取源码文本断言。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoyalCardBeautyTest {

    private fun repoFile(rel: String): File =
        listOf(File(rel), File("../$rel")).firstOrNull { it.isFile || it.isDirectory }
            ?: throw AssertionError("找不到 $rel")

    private fun cardSource(): String =
        repoFile("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuRoyalCard.kt").readText()

    private fun strings(lang: String): Map<String, String> {
        val file = repoFile("app/src/main/res/values$lang/strings_baopu_royal.xml")
        return Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(file.readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun generalStrings(lang: String): Map<String, String> {
        val file = repoFile("app/src/main/res/values$lang/strings.xml")
        return Regex("""<string name="([^"]+)">([^<]*)</string>""")
            .findAll(file.readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `判据1_御印十六dp且底色字色取ZhuTint与ZhuSha`() {
        val src = cardSource()
        assertTrue("御印边长必须是 16dp", src.contains("ROYAL_SEAL_DP = 16"))
        assertTrue("御印底必须取 ZhuTint", src.contains(".background(ZhuTint)"))
        assertTrue("御印字必须取 ZhuSha", src.contains("color = ZhuSha"))
        assertTrue("标题条左侧必须挂御印", src.contains("titleIcon = { RoyalSeal() }"))
    }

    @Test
    fun `判据2_朱印角戳四十八dp负十二度半透明且不吞点击`() {
        val src = cardSource()
        assertTrue("角戳边长必须是 48dp", src.contains("ROYAL_STAMP_DP = 48"))
        assertTrue("轻旋 -12°", src.contains("ROYAL_STAMP_ROTATION = -12f"))
        assertTrue("50% 透明", src.contains(".alpha(0.5f)"))
        val stamp = src.substringAfter("private fun RoyalStamp").substringBefore("\n}", "")
        assertTrue("角戳函数必须找得到", stamp.isNotBlank())
        assertFalse("装饰层不许拦点击", stamp.contains("clickable"))
    }

    @Test
    fun `判据3_左朱条四dp且只在御案卡`() {
        val src = cardSource()
        assertTrue("左朱条宽度必须是 4dp", src.contains("ROYAL_SPINE_DP = 4"))
        assertTrue("左朱条用 ZhuSha", src.contains("ZhuSha"))
        val pkg = repoFile("app/src/main/java/com/example/zhengdao/ui/baopu")
        val others = pkg.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "kt" && it.name != "BaopuRoyalCard.kt" }
            .filter { it.readText().contains("ROYAL_SPINE_DP") }
        assertTrue("非御案卡不许出现左朱条：${others.map { it.name }}", others.isEmpty())
    }

    @Test
    fun `判据4_君主动词换成准驳搁置且先观察在搁置后果里`() {
        val zh = strings("")
        assertEquals("准", zh["royal_opt_approve"])
        assertEquals("驳", zh["royal_opt_reject"])
        assertEquals("搁置", zh["royal_opt_hold"])
        assertTrue("先观察必须出现在既有搁置档的后果里", zh["royal_opt_hold_consequence"].orEmpty().contains("先观察"))
        listOf("准了", "不行", "搁一搁").forEach { old ->
            assertFalse("旧文案不许再出现：$old", zh.values.any { it == old })
        }
        val en = strings("-en")
        assertEquals("Approve", en["royal_opt_approve"])
        assertEquals("Deny", en["royal_opt_reject"])
        assertEquals("Hold", en["royal_opt_hold"])
    }

    @Test
    fun `判据5_副标题唯君可决`() {
        assertEquals("唯君可决", generalStrings("")["decision_center_hint"])
        assertEquals("Only you decide", generalStrings("-en")["decision_center_hint"])
    }

    @Test
    fun `判据6_零结构变化_五页签不变`() {
        assertEquals(5, DecisionTab.entries.size)
        assertEquals(DecisionTab.ROYAL, DecisionTab.entries.first())
    }

    @Test
    fun `判据7_不新增色相_只用既有token`() {
        val changed = listOf(
            "app/src/main/java/com/example/zhengdao/ui/baopu/BaopuRoyalCard.kt",
            "app/src/main/java/com/example/zhengdao/ui/baopu/BaopuContainers.kt",
        )
        changed.forEach { rel ->
            val text = repoFile(rel).readText()
            assertFalse("$rel 不许出现新 hex 色值", Regex("""Color\(0x""").containsMatchIn(text))
        }
    }
}
