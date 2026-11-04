// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百四十三批 + 追加三处（原 444）：顶栏显示组合、权重落位、弹层锚点、跑马灯标签/正文分离。
// 本仓无 Robolectric ⇒ 布局判据用**源码级断言**钉住，像素落点另由真机截图（报告 §4）核对。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.ModelLabels
import com.example.zhengdao.baopu.ModelPickerRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ModelLayoutGateTest {

    @Before
    fun installBundledTable() {
        ModelLabels.install(ModelLabels.loadFromSource())
    }

    private fun src(rel: String): String {
        val f = File("src/main/java/com/example/zhengdao/" + rel)
            .let { if (it.exists()) it else File("app/src/main/java/com/example/zhengdao/" + rel) }
        assertTrue("读不到 $rel（cwd=${File("").absolutePath}）", f.exists())
        return f.readText()
    }

    /** 去注释（负向判据只看代码，注释里写参考线不算写死坐标）。 */
    private fun codeOnly(text: String): String = text
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    // ── 判据① 顶栏 [D] V4 flash，不再是 DeepSeek chat ──────────────────────

    @Test
    fun 顶栏显示名与徽标组合是_D_V4_1_Flash_不再是DeepSeek_chat() {
        val company = ModelPickerRules.companyOf(
            com.example.zhengdao.baopu.Providers.DEEPSEEK,
        )
        assertEquals("DeepSeek", company)
        assertEquals("D", BaopuTopBarRules.badgeLetterOf(company))
        assertEquals(
            "V4.1 Flash",
            BaopuTopBarRules.topBarModelName(com.example.zhengdao.baopu.Providers.DEEPSEEK.model, company),
        )
        val top = "[" + BaopuTopBarRules.badgeLetterOf(company) + "] " +
            BaopuTopBarRules.topBarModelName(com.example.zhengdao.baopu.Providers.DEEPSEEK.model, company)
        assertEquals("[D] V4.1 Flash", top)
        assertFalse("顶栏不许再出现 DeepSeek chat", top.contains("DeepSeek chat"))
        // 表外那条死别名按 443 规则只能原样显 ID，绝不包装成产品名
        assertEquals("deepseek-chat", BaopuTopBarRules.topBarModelName("deepseek-chat", company))
        ModelLabels.loadFromSource().forEach {
            assertFalse("${it.modelId} 显示名不许带 chat", it.displayName.lowercase().contains("chat"))
        }
    }

    // ── 追加① 横向落位：用权重推导，不写死 dp ──────────────────────────────

    @Test
    fun 模型段与跑马灯按权重推导_坐标一条都不进代码() {
        val skin = src("ui/baopu/BaopuSkin.kt")
        val screen = codeOnly(src("ui/baopu/BaopuScreen.kt"))
        val area = codeOnly(src("ui/baopu/BaopuModelArea.kt"))
        assertTrue("要有模型段权重常量", skin.contains("BAO_TOPBAR_MODEL_WEIGHT"))
        assertTrue("要有跑马灯权重常量", skin.contains("BAO_TOPBAR_TICKER_WEIGHT"))
        assertTrue("模型区按权重给宽", screen.contains("Modifier.weight(BAO_TOPBAR_MODEL_WEIGHT)"))
        assertTrue("跑马灯按权重给宽", screen.contains("Modifier.weight(BAO_TOPBAR_TICKER_WEIGHT)"))
        assertTrue("胶囊要撑满权重段（右缘才是模型段落点）", area.contains(".fillMaxWidth()"))
        assertFalse("坐标不许写死", screen.contains("368.dp") || screen.contains("384.dp"))
        assertFalse("参考线数值不许进代码", screen.contains("884.dp") || screen.contains("1172.dp"))
        assertTrue("段间只留 16dp 这一处固定间距",
            screen.contains("Spacer(Modifier.width(BAO_TOPBAR_SECTION_GAP_DP.dp))"))
        assertEquals("443 现场反馈：中间挨紧点 ⇒ 8dp", 8, BAO_TOPBAR_SECTION_GAP_DP)
        assertEquals("两段等权：模型段必须够宽装下 [D] V4 flash（1:3 真机实测会裁字）",
            BAO_TOPBAR_MODEL_WEIGHT, BAO_TOPBAR_TICKER_WEIGHT)
        assertEquals(1f, BAO_TOPBAR_MODEL_WEIGHT)
        assertEquals(1f, BAO_TOPBAR_TICKER_WEIGHT)
    }

    @Test
    fun 徽标D与模型名在胶囊内垂直居中_上下padding相等() {
        val area = codeOnly(src("ui/baopu/BaopuModelArea.kt"))
        assertTrue("胶囊 Row 垂直居中", area.contains("verticalAlignment = Alignment.CenterVertically"))
        assertEquals("两颗命中区都显式垂直居中", 2,
            Regex("align\\(Alignment\\.CenterVertically\\)").findAll(area).count())
        assertEquals("两颗都用同一个上下内距常量（上下相等）", 2,
            Regex("padding\\(vertical = BAO_TOPBAR_PILL_INSET_DP\\.dp\\)").findAll(area).count())
        assertEquals(8, BAO_TOPBAR_PILL_INSET_DP)
        assertFalse("不许只给一侧 padding", area.contains("padding(top =") || area.contains("padding(bottom ="))
    }

    // ── 追加② 弹层锚定模型区下沿，不许居中弹窗 ────────────────────────────

    @Test
    fun 两张弹层屏幕垂直居中_条目不再贴顶栏() {
        val area = codeOnly(src("ui/baopu/BaopuModelArea.kt"))
        val rowTail = area.indexOf("Spacer(Modifier.weight(1f))")
        val providerSheet = area.indexOf("if (open == BaopuTopBarSheet.PROVIDER)")
        val modelSheet = area.indexOf("if (open == BaopuTopBarSheet.MODEL)")
        assertTrue("模型区要有收尾 Spacer（胶囊撑满后的剩余）", rowTail > 0)
        assertTrue("供应商弹层挂在 Row 之外的外壳上", providerSheet > rowTail)
        assertTrue("模型弹层挂在 Row 之外的外壳上", modelSheet > rowTail)
        assertEquals("弹层仍只有两张", 2, Regex("Popup\\(").findAll(area).count())
        assertEquals("两张都接 onDismissRequest", 2,
            Regex("onDismissRequest = onDismiss").findAll(area).count())
        // 互斥仍由单值枚举兜住（434 判据不破）
        assertEquals(BaopuTopBarSheet.NONE,
            BaopuTopBarRules.toggleSheet(BaopuTopBarSheet.MODEL, BaopuTopBarSheet.MODEL))
    }

    // ── 追加③ 标签固定宽、正文独占剩余、长文本单行省略 ─────────────────────

    @Test
    fun 跑马灯标签固定宽_正文独占剩余_长文本单行省略() {
        val ticker = codeOnly(src("ui/baopu/BaopuNoticeTicker.kt"))
        assertTrue("标签固定宽", ticker.contains(".width(TICKER_BADGE_WIDTH_DP.dp)"))
        assertFalse("标签不许再 widthIn(min) 自己撑开挤正文",
            ticker.contains("widthIn(min = TICKER_BADGE_WIDTH_DP"))
        assertTrue("正文独占剩余宽度", ticker.contains("modifier = Modifier.weight(1f)"))
        assertFalse("正文不许 fill=false 被标签挤", ticker.contains("fill = false"))
        assertTrue("正文单行", ticker.contains("maxLines = 1") && ticker.contains("softWrap = false"))
        assertTrue("长文本单行省略（或轮播，本仓取 ellipsis）", ticker.contains("TextOverflow.Ellipsis"))
        // 短/长两态都由同一个 Text 承载（轮播在 BaopuNoticeTicker 的 cursor 上，不换组件）
        assertTrue("轮播节拍仍在", ticker.contains("TICKER_CYCLE_MS"))
    }
}
