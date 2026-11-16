// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十六批 · 轮廓模块单测：判据①–⑧逐条钉死（10 例）。
// 造红思路照 340/332：不写「大概能过」的断言，只写「方向反了/缺表还敢答」必须红。
package com.example.zhengdao.baopu.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilhouetteTest {

    // ── 判据① 跨档边界：r=0.49/0.5/1.19/1.2/1.99/2.0 六点，态正确切换 ──

    @Test
    fun boundarySixPointsSwitchTier() {
        // 针织内裤(棉) C=2.0：H = r×2，边界左闭右开、压线归高档。
        val cases = listOf(
            0.98 to SilhouetteTier.HIDDEN, // r=0.49
            1.00 to SilhouetteTier.FAINT, // r=0.50 压线进隐约
            2.38 to SilhouetteTier.FAINT, // r=1.19
            2.40 to SilhouetteTier.VISIBLE, // r=1.20 压线进可见
            3.98 to SilhouetteTier.VISIBLE, // r=1.99
            4.00 to SilhouetteTier.PROMINENT, // r=2.00 压线进明显
        )
        for ((h, expected) in cases) {
            val verdict = grade(h, "针织内裤(棉)")
            assertTrue("H=$h 应分档", verdict is SilhouetteVerdict.Graded)
            assertEquals("H=$h 档错", expected, (verdict as SilhouetteVerdict.Graded).tier)
        }
    }

    // ── 判据② H 单调：H 增大不得退档（visible 不回 hidden）──

    @Test
    fun hIncreaseNeverDegradesTier() {
        var previous = Int.MIN_VALUE
        var h = 0.05
        while (h <= 8.0) {
            val verdict = grade(h, "10oz斜纹")
            val rank = (verdict as SilhouetteVerdict.Graded).tier.rank
            assertTrue("H=$h 出现退档：$previous → $rank", rank >= previous)
            previous = rank
            h += 0.05
        }
    }

    // ── 判据③ C 单调（方向更正见阈值文档第七节：C 大=挺括 ⇒ r 小 ⇒ 更难显）──

    @Test
    fun stifferFabricNeverRaisesTier() {
        val sorted = FabricSpec.ALL.sortedBy { it.bendingLengthCm }
        var previous = Int.MAX_VALUE
        for (fabric in sorted) {
            val verdict = grade(4.0, fabric.name)
            val rank = (verdict as SilhouetteVerdict.Graded).tier.rank
            assertTrue("C=${fabric.bendingLengthCm} 挺括面料不该进档：$previous → $rank", rank <= previous)
            previous = rank
        }
        // 参考实现表一同向复核：雪纺明显、厚帆布压到隐藏。
        assertEquals(SilhouetteTier.PROMINENT, gradedTier(4.0, "雪纺/真丝薄料"))
        assertEquals(SilhouetteTier.HIDDEN, gradedTier(4.0, "厚帆布"))
    }

    // ── 判据④ 姿态影响：同 H 下坐 vs 站可不同，且只来自姿态标签（确定性）──

    @Test
    fun postureChangesResultDeterministically() {
        // 10oz斜纹 C=4.4，H=4.62：站 r=1.05（隐约）；坐 C_eff=3.74，r≈1.235（可见）。
        val stand = grade(4.62, "10oz斜纹", posture = SilhouettePosture.STAND) as SilhouetteVerdict.Graded
        val sit = grade(4.62, "10oz斜纹", posture = SilhouettePosture.SIT) as SilhouetteVerdict.Graded
        assertEquals(SilhouetteTier.FAINT, stand.tier)
        assertEquals(SilhouetteTier.VISIBLE, sit.tier)
        assertTrue("坐姿应比站姿更易显", sit.ratio > stand.ratio)
        // 蹲与坐同系数：读数一致；姿态词必须出现在一句话里（来源可核对）。
        val squat = grade(4.62, "10oz斜纹", posture = SilhouettePosture.SQUAT) as SilhouetteVerdict.Graded
        assertEquals(sit.ratio, squat.ratio, 0.0)
        assertTrue(stand.sentence.contains("站"))
        assertTrue(sit.sentence.contains("坐"))
        // 同输入重跑逐字节一致：影响只来自标签，不是随机。
        assertEquals(stand, grade(4.62, "10oz斜纹", posture = SilhouettePosture.STAND))
    }

    // ── 判据⑤ 一句话 ≤40 字，不出现「大概/可能/也许」──

    @Test
    fun sentenceWithin40CharsAndNoHedgingWords() {
        val heights = listOf(0.3, 1.0, 2.0, 3.5, 6.0)
        for (fabric in FabricSpec.ALL) {
            for (position in SilhouettePosition.entries) {
                for (posture in SilhouettePosture.entries) {
                    for (h in heights) {
                        val sentence = (grade(h, fabric.name, position, posture) as SilhouetteVerdict.Graded).sentence
                        assertTrue("超 40 字（${sentence.length}）：$sentence", sentence.length <= 40)
                        for (banned in listOf("大概", "可能", "也许")) {
                            assertTrue("出现模糊词「$banned」：$sentence", !sentence.contains(banned))
                        }
                    }
                }
            }
        }
        // 拒绝语同样守规矩（超长面料名被截断）。
        val refused = grade(1.0, "一个特别特别特别特别特别长的自填面料名(C=5)")
        assertTrue(refused.sentence.length <= 40)
    }

    // ── 判据⑥ C 必须来自实测表：表里没有 ⇒ 明确拒绝（负例：自填 C=5 ⇒ 红）──

    @Test
    fun unmeasuredFabricRefused_adHocC5() {
        assertNull(FabricSpec.byName("自填面料(C=5)"))
        for (name in listOf("自填面料(C=5)", "不存在的尼龙", "")) {
            val verdict = grade(2.0, name)
            assertEquals("「$name」应拒绝", SilhouetteState.REFUSED_NO_FABRIC, verdict.state)
            assertTrue("拒绝语要点明拒绝：${verdict.sentence}", verdict.sentence.contains("拒绝判读"))
        }
        // 实测表本身不含 C=5 的自填值（12oz牛仔是 5.2，不是 5.0）。
        assertTrue(FabricSpec.ALL.none { it.bendingLengthCm == 5.0 })
    }

    // ── 判据⑦ 阈值表缺失 ⇒ fail-closed（拒绝而不是默认可见）──

    @Test
    fun missingThresholdsFailClosed() {
        val broken = listOf(
            SilhouetteThresholds(Double.NaN, 1.2, 2.0), // 缺一档
            SilhouetteThresholds(0.5, 0.4, 2.0), // 不递增
            SilhouetteThresholds(-0.5, 1.2, 2.0), // 非正
        )
        for (thresholds in broken) {
            val verdict = grade(4.0, "雪纺/真丝薄料", thresholds = thresholds)
            assertEquals(SilhouetteState.REFUSED_NO_THRESHOLD, verdict.state)
            assertTrue(!verdict.sentence.contains("明显"))
        }
        // 好面料＋高 H 也不许在缺表时给出可见档——拒绝优先。
        assertEquals(SilhouetteState.REFUSED_NO_THRESHOLD, grade(6.0, "10oz斜纹", thresholds = broken[0]).state)
    }

    // ── 判据⑧ 纯函数、确定性：同输入逐字节一致 ──

    @Test
    fun pureFunctionDeterministic() {
        val a = grade(3.5, "12oz牛仔", SilhouettePosition.CROTCH, SilhouettePosture.STAND)
        val b = grade(3.5, "12oz牛仔", SilhouettePosition.CROTCH, SilhouettePosture.STAND)
        assertEquals(a, b)
        // 输出格式钉死（含 Locale.ROOT 固定小数点）：逐字节比对字面量。
        val graded = a as SilhouetteVerdict.Graded
        assertEquals("12oz牛仔，凸起35mm ⇒ 隐约（裆缝·站）", graded.sentence)
        assertEquals(3.5 / 5.2, graded.ratio, 0.0)
        assertEquals(SilhouetteState.GRADED, a.state)
    }

    // ── 实测表完整性：来源、取值与阈值文档一致 ──

    @Test
    fun fabricTableMatchesThresholdsDoc() {
        assertEquals(7, FabricSpec.ALL.size)
        assertEquals(FabricSpec.ALL.size, FabricSpec.ALL.map { it.name }.toSet().size)
        for (fabric in FabricSpec.ALL) {
            assertTrue("C 须为正有限：${fabric.name}", fabric.bendingLengthCm > 0.0 && fabric.bendingLengthCm.isFinite())
            assertTrue("来源须标注：${fabric.name}", fabric.source.isNotBlank())
        }
        val byName = { n: String -> FabricSpec.byName(n)!!.bendingLengthCm }
        assertEquals(4.4, byName("10oz斜纹"), 0.0)
        assertEquals(11.0, byName("厚帆布"), 0.0)
        assertEquals(1.2, byName("雪纺/真丝薄料"), 0.0)
    }

    // ── 接线：夹具高度场独立成测（形轨建成后换实现，本缝不动）──

    @Test
    fun fixtureHeightFieldWiring() {
        // 夹具三位置读数固定且互异（腰线 1.2 / 裆缝 3.5 / 大腿根 2.8）。
        val heights = SilhouettePosition.entries.map { SilhouetteWire.FIXTURE.bumpHeightCm(it) }
        assertEquals(listOf(1.2, 3.5, 2.8), heights)
        val verdict = SilhouetteWire.grade(SilhouetteWire.FIXTURE, "10oz斜纹", SilhouettePosition.CROTCH, SilhouettePosture.STAND)
        val graded = verdict as SilhouetteVerdict.Graded
        assertEquals(SilhouetteTier.FAINT, graded.tier) // r=3.5/4.4≈0.80
        assertTrue(graded.sentence.contains("凸起35mm"))
        // 缺表经过接线缝同样 fail-closed，不许在缝上兜底。
        val refused = SilhouetteWire.grade(
            SilhouetteWire.FIXTURE,
            "10oz斜纹",
            SilhouettePosition.WAIST,
            SilhouettePosture.STAND,
            thresholds = SilhouetteThresholds(0.5, Double.NaN, 2.0),
        )
        assertEquals(SilhouetteState.REFUSED_NO_THRESHOLD, refused.state)
    }

    // ── 工具 ──

    private fun grade(
        h: Double,
        fabric: String,
        position: SilhouettePosition = SilhouettePosition.CROTCH,
        posture: SilhouettePosture = SilhouettePosture.STAND,
        thresholds: SilhouetteThresholds = SilhouetteThresholds.DEFAULT,
    ): SilhouetteVerdict = Silhouette.grade(h, fabric, position, posture, thresholds)

    private fun gradedTier(h: Double, fabric: String): SilhouetteTier =
        (grade(h, fabric) as SilhouetteVerdict.Graded).tier
}
