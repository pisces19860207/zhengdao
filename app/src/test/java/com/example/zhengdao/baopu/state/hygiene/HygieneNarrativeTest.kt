// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.hygiene

import com.example.zhengdao.baopu.state.prepuce.AreaLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HygieneNarrativeTest {

    @Test
    fun `判据三_低于明显档整行不写气味`() {
        for (tier in listOf(SmegmaTier.NONE, SmegmaTier.TRACE)) {
            val state = HygieneFixtures.state(smegmaTier = tier)
            val line = HygieneRenderer.render(state, HygieneDeriver.derive(state, HygieneFixtures.context()))
            assertFalse(line.contains("味"))
            assertFalse(line.contains("臭"))
            assertFalse(line.contains("气味强度"))
        }
    }

    @Test
    fun `判据三_达到明显档才写气味强度与三项缺席`() {
        for (tier in listOf(SmegmaTier.OBVIOUS, SmegmaTier.BUILDUP)) {
            val state = HygieneFixtures.state(smegmaTier = tier)
            val line = HygieneRenderer.render(state, HygieneDeriver.derive(state, HygieneFixtures.context()))
            assertTrue(line.contains("气味强度="))
            assertTrue(line.contains("味型=○"))
            assertTrue(line.contains("菌群=○"))
            assertTrue(line.contains("病理性气味=○"))
        }
    }

    @Test
    fun `判据二_职业正向与负向刻板印象都红`() {
        val forbidden = listOf(
            "军警消讲究",
            "医生干净",
            "医生爱干净",
            "医生整洁",
            "医生臭",
            "程序员爱干净",
            "程序员卫生差",
            "工人脏",
            "工人不卫生",
            "工人不讲卫生",
            "护士有洁癖",
            "护士不爱干净",
            "军人清爽",
            "老板卫生好",
            "员工不讲卫生",
            "运动员体味",
            "律师爱干净",
            "医生注意卫生",
            "经理不爱洗澡",
            "职业病讲究",
            "主播爱干净",
            "助理爱干净",
        )
        for (text in forbidden) {
            assertThrows(text, IllegalArgumentException::class.java) { HygieneGuard.rejectOccupationBias(text) }
        }
        HygieneGuard.rejectOccupationBias("医生的工作与清洁无关")
        HygieneGuard.rejectOccupationBias("生理发育正常且爱干净")
        HygieneGuard.rejectOccupationBias("这里只记录个人意愿")
    }

    @Test
    fun `判据二_脏与恶心评价都红`() {
        for (text in listOf(
            "这个人脏", "真不洁", "这个人不干净", "真腌臜", "令人恶心", "令人反胃", "邋遢", "真嫌弃",
            "难闻", "熏人", "刺鼻", "真骚",
        )) {
            assertThrows(text, IllegalArgumentException::class.java) { HygieneGuard.requireNarrative(text) }
        }
        HygieneGuard.requireNarrative("包皮垢档=明显")
    }

    @Test
    fun `判据四_没洗澡不得推出局部没洗`() {
        assertThrows(IllegalArgumentException::class.java) {
            HygieneGuard.rejectLocalInference("没洗澡，所以没洗脚")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HygieneGuard.rejectLocalInference("没洗下体，因为没洗澡")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HygieneGuard.rejectLocalInference("没洗澡，所以没洗下身")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HygieneGuard.rejectLocalInference("没有洗澡，因此下面没洗")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HygieneGuard.rejectLocalInference("洗全身必洗脚")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HygieneGuard.rejectLocalInference("没洗澡，证明没洗脚")
        }
        HygieneGuard.rejectLocalInference("没洗澡，但洗了脚")
        HygieneGuard.rejectLocalInference("没洗澡，但没洗脚")
        HygieneGuard.rejectLocalInference("没洗澡，但不意味着没洗脚")
        HygieneGuard.rejectLocalInference("没洗澡，不能说明没洗脚")
        HygieneGuard.rejectLocalInference("没洗澡，无法证明没洗脚")
        HygieneGuard.rejectLocalInference("没洗脚，不是因为没洗澡")
        HygieneGuard.rejectLocalInference("全身每天洗，局部隔日洗")
    }

    @Test
    fun `判据四_他人代做不写施舍控制`() {
        HygieneGuard.requireNarrative("他人代做：备水、放好衣物")
        for (text in listOf(
            "他人代做是施舍", "备水是控制", "放好衣物显得可怜", "他人代做是赏赐", "备水是恩赐", "放好衣物是鄙视",
            "代洗是控制", "帮洗是支配", "替对方洗是占便宜", "替他洗是施舍",
        )) {
            assertThrows(text, IllegalArgumentException::class.java) { HygieneGuard.requireNarrative(text) }
        }
    }

    @Test
    fun `判据三_气味闸门按观察档而不是表推档`() {
        for (text in listOf(
            "气味强度=轻", "酸馊", "有异味", "腥", "发霉", "汗臭", "酸味", "难闻", "熏人", "刺鼻", "骚", "臊",
        )) {
            assertThrows(text, IllegalArgumentException::class.java) {
                HygieneGuard.requireOdorMention(SmegmaTier.TRACE, text)
            }
        }
        HygieneGuard.requireOdorMention(SmegmaTier.OBVIOUS, "气味强度=轻")
    }

    @Test
    fun `边界_说明文字构造即拒绝医学建议与具体味型`() {
        assertThrows(IllegalArgumentException::class.java) { Absent("感染") }
        assertThrows(IllegalArgumentException::class.java) { Absent("炎症") }
        assertThrows(IllegalArgumentException::class.java) { Absent("建议就医") }
        assertThrows(IllegalArgumentException::class.java) {
            SmegmaPrediction(SmegmaTier.TRACE, "S-X", "建议多洗")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmellSlot.Given(SmellIntensity.SLIGHT, "O-X", "酸馊")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmellSlot.Given(SmellIntensity.SLIGHT, "O-X", "汗臭")
        }
        assertThrows(IllegalArgumentException::class.java) { Absent("酸味") }
        assertThrows(IllegalArgumentException::class.java) { Absent("真菌多") }
        assertThrows(IllegalArgumentException::class.java) { Absent("注意卫生") }
        assertThrows(IllegalArgumentException::class.java) { Absent("瘙痒") }
        assertThrows("糖尿病", IllegalArgumentException::class.java) { Absent("糖尿病") }
        assertThrows("综合征", IllegalArgumentException::class.java) { Absent("综合征") }
        assertThrows("包茎", IllegalArgumentException::class.java) { Absent("包茎") }
        assertThrows("湿疣", IllegalArgumentException::class.java) { Absent("湿疣") }
        assertThrows("艾滋病", IllegalArgumentException::class.java) { Absent("艾滋病") }
        HygieneGuard.requireNeutralText("味型不可算")
        HygieneGuard.requireNeutralText("不做病理性判断")
    }

    @Test
    fun `边界_所有渲染出口都不给医学判断健康建议或病名`() {
        val forbidden = listOf(
            "诊断", "治疗", "用药", "感染", "发炎", "炎症", "病变", "疾病", "就医", "健康建议", "建议", "病名",
            "肿瘤", "癌症", "症状", "患病", "吃药", "抗生素", "医院", "真菌", "细菌", "瘙痒", "红肿", "溃烂",
            "包皮炎", "龟头炎", "性病", "传染", "药膏", "洗液", "消毒", "注意卫生", "保持卫生", "多洗", "勤洗",
        )
        for (tier in SmegmaTier.entries) {
            for (coverage in AreaLevel.entries) {
                val state = HygieneFixtures.state(smegmaTier = tier)
                val context = HygieneFixtures.context(coverage = coverage)
                val line = HygieneRenderer.render(state, HygieneDeriver.derive(state, context))
                for (word in forbidden) assertFalse("$word：$line", word in line)
            }
        }
    }

    @Test
    fun `边界_行号与说明字段不能注入新列或换行`() {
        for (rowId in listOf("S-X|菌群=多", "医生干净", "S X", "S-X\n菌群=多")) {
            assertThrows(rowId, IllegalArgumentException::class.java) {
                SmegmaPrediction(SmegmaTier.TRACE, rowId, "S-X 起档 1")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmegmaPrediction(SmegmaTier.TRACE, "S-X", "依据|菌群=多")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmegmaPrediction(SmegmaTier.TRACE, "S-X", "依据\n菌群=多")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmellSlot.Given(SmellIntensity.SLIGHT, "O-X|味型=酸", "依据")
        }
        assertThrows(IllegalArgumentException::class.java) { Absent("不可算）|菌群=多") }
        assertThrows(IllegalArgumentException::class.java) { Absent("不可算\n菌群=多") }
    }
}
