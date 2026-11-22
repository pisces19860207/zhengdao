// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百零五批 · 结构判据：表齐不齐、左右与姿态是不是硬要求、档域与依据拦不拦得住。
// 每条拒收都断言**消息点名**（哪部位哪维），不吃「反正抛了」的含糊过关。
package com.example.zhengdao.baopu.state.bodyparts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyPartSheetTest {

    // ── 表结构 ────────────────────────────────────────────────────────

    @Test
    fun `表结构_八部位二十三维且每维都有归属`() {
        assertEquals(8, BodyPart.entries.size)
        assertEquals(Dim.entries.size, BodyPartCatalog.specs.size)
        assertEquals(8, BodyPartCatalog.specs.map { it.part }.toSet().size)
        BodyPart.entries.forEach { part ->
            assertTrue(part.label + " 在表里没有维度", BodyPartCatalog.dimsOf(part).isNotEmpty())
        }
    }

    @Test
    fun `表结构_档名与档序从零连续`() {
        BodyPartCatalog.specs.forEach { spec ->
            val words = spec.words
            assertEquals(spec.dim.label + " 档数", spec.orderMax + 1, words.size)
            words.forEachIndexed { i, w ->
                assertTrue(spec.dim.label + " 第 " + i + " 档名为空", w.isNotBlank())
            }
            assertEquals(spec.dim.label + " 档名重复", words.size, words.toSet().size)
        }
    }

    @Test
    fun `表结构_三处关键档数`() {
        // 派工单点名的三处：喉结 0–4、乳晕倍数 4 档、乳晕表面 2 档。
        assertEquals(5, BodyPartCatalog.orderMax(Dim.LARYNX) + 1)
        assertEquals(4, BodyPartCatalog.orderMax(Dim.AREOLA_RATIO) + 1)
        assertEquals(2, BodyPartCatalog.orderMax(Dim.AREOLA_SURFACE) + 1)
        assertEquals("喉结 0 档是常见档（几乎看不见）", LarynxGrade.HIDDEN.label, "几乎看不见")
    }

    @Test
    fun `表结构_读数条数由表算出并等于整单纯度`() {
        assertEquals(72, BodyPartCatalog.totalReadings)
        assertEquals(72, BodyPartFixtures.clean().size)
    }

    // ── 硬规矩①：左右 ＋ 姿态 ────────────────────────────────────────

    @Test
    fun `缺一侧_拒且点名胸肌体积缺左`() {
        val full = BodyPartFixtures.clean().all()
        val cut = full.filterNot {
            it.dim == Dim.PEC_VOLUME && it.side == Side.LEFT && it.posture == Posture.RELAXED
        }
        val msg = messageOf { BodyParts(cut) }
        assertTrue(msg, msg.contains("胸肌·体积"))
        assertTrue(msg, msg.contains("缺左"))
    }

    @Test
    fun `腹肌缺收紧态_拒`() {
        val full = BodyPartFixtures.clean().all()
        val cut = full.filterNot { it.dim == Dim.ABS_BLOCK && it.posture == Posture.TIGHTENED }
        val msg = messageOf { BodyParts(cut) }
        assertTrue(msg, msg.contains("腹肌·分块可见度"))
        assertTrue(msg, msg.contains("收紧"))
    }

    @Test
    fun `腹肌只记放松与屈曲_不算两态`() {
        // 腹肌只认放松／收紧；拿屈曲用力顶替，必须被姿态规则拦下。
        val msg = messageOf {
            Reading(Dim.ABS_BLOCK, Side.LEFT, Posture.FLEXED, AbsBlock.UPPER.level)
        }
        assertTrue(msg, msg.contains("腹肌·分块可见度"))
        assertTrue(msg, msg.contains("放松"))
    }

    @Test
    fun `同一组合记两次_拒`() {
        val full = BodyPartFixtures.clean().all()
        val msg = messageOf { BodyParts(full + full.first()) }
        assertTrue(msg, msg.contains("两次"))
    }

    @Test
    fun `双侧部位写中_拒`() {
        val msg = messageOf {
            Reading(Dim.PEC_VOLUME, Side.MIDLINE, Posture.RELAXED, PecVolume.FULL.level)
        }
        assertTrue(msg, msg.contains("分左右两侧"))
    }

    @Test
    fun `中轴部位写左_拒`() {
        val msg = messageOf {
            Reading(Dim.LARYNX, Side.LEFT, Posture.RELAXED, LarynxGrade.FAINT.level)
        }
        assertTrue(msg, msg.contains("中轴维度"))
    }

    // ── 档域与依据 ───────────────────────────────────────────────────

    @Test
    fun `档序越界_拒且给出域`() {
        val msg = messageOf {
            Reading(Dim.AREOLA_SURFACE, Side.LEFT, Posture.RELAXED, AreolaSurface.GLANDULAR.level + 1)
        }
        assertTrue(msg, msg.contains("越界"))
        assertTrue(msg, msg.contains("0–1"))
    }

    @Test
    fun `乳头高凸缺依据_拒`() {
        val msg = messageOf {
            Reading(Dim.NIPPLE_HEIGHT, Side.LEFT, Posture.RELAXED, NippleHeight.HIGH.level)
        }
        assertTrue(msg, msg.contains("高凸"))
        assertTrue(msg, msg.contains("依据"))
    }

    @Test
    fun `乳头高凸带依据_收`() {
        val r = Reading(Dim.NIPPLE_HEIGHT, Side.LEFT, Posture.RELAXED, NippleHeight.HIGH.level, "侧面看明显突起")
        assertEquals("高凸", r.label)
    }

    @Test
    fun `喉结三档缺依据_拒`() {
        val msg = messageOf {
            Reading(Dim.LARYNX, Side.MIDLINE, Posture.RELAXED, LarynxGrade.OBVIOUS.level)
        }
        assertTrue(msg, msg.contains("明显"))
        assertTrue(msg, msg.contains("依据"))
    }

    @Test
    fun `依据里带长度单位_拒`() {
        val withUnit = "约 " + "7" + "m" + "m"
        val msg = messageOf {
            Reading(Dim.NIPPLE_HEIGHT, Side.LEFT, Posture.RELAXED, NippleHeight.HIGH.level, withUnit)
        }
        assertTrue(msg, msg.contains("不出长度"))
    }

    @Test
    fun `依据里带色号_拒`() {
        val hex = "#" + "8B5A2B"
        val msg = messageOf {
            Reading(Dim.NIPPLE_COLOR, Side.LEFT, Posture.RELAXED, ColorRef.DARKER.level, "接近" + hex)
        }
        assertTrue(msg, msg.contains("相对肤色"))
    }

    @Test
    fun `依据里带诊断词_拒`() {
        val bad = "形状" + "异" + "常"
        val msg = messageOf {
            Reading(Dim.NIPPLE_HEIGHT, Side.LEFT, Posture.RELAXED, NippleHeight.HIGH.level, bad)
        }
        assertTrue(msg, msg.contains("不下医学结论"))
    }

    // ── 出口与来源 ───────────────────────────────────────────────────

    @Test
    fun `出口_八部位都在且无单位无诊断词`() {
        val text = BodyPartFixtures.clean().line()
        BodyPart.entries.forEach { part ->
            assertTrue("出口缺 " + part.label, text.contains(part.label))
        }
        assertEquals(null, BodyPartGuard.measureHit(text))
        assertTrue(BodyPartGuard.diagnosisHits(text).isEmpty())
    }

    @Test
    fun `出口_逐维逐侧逐态都在`() {
        val text = BodyPartFixtures.clean().line()
        assertEquals(72, text.split("=").size - 1)
    }

    @Test
    fun `来源_留空即写无来源`() {
        val r = Reading(
            Dim.PEC_VOLUME, Side.LEFT, Posture.RELAXED, PecVolume.SLIGHT.level,
            source = "", asOf = "",
        )
        assertEquals(NO_SOURCE, r.attributedSource)
        assertFalse(r.attributedSource.isBlank())
    }

    @Test
    fun `读数行_带部位维度侧别姿态`() {
        val r = Reading(Dim.PEC_VOLUME, Side.RIGHT, Posture.FLEXED, PecVolume.THICK.level)
        val line = r.line()
        assertTrue(line, line.contains("胸肌"))
        assertTrue(line, line.contains("右"))
        assertTrue(line, line.contains("屈曲用力"))
        assertTrue(line, line.contains("厚实"))
    }

    /** 取拒收消息；没抛就是失败（不吃「抛没抛都算过」）。 */
    private inline fun messageOf(block: () -> Unit): String {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
        throw AssertionError("应当拒收但没有抛异常")
    }
}
