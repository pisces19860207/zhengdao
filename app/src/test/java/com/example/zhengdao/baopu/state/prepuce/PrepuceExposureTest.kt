// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百零四批 · 判据③（完全露出必带 retractState、环切后不许可推）与判据④（疲软表必给依据、
// 表外组合不收、不出百分比）的机器证据。穷举 3×3×3×2×3＝162 组合，逐组核对红／绿与依据。
package com.example.zhengdao.baopu.state.prepuce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepuceExposureTest {

    private fun claim(type: ExposeType, retract: RetractState?, note: String = "") =
        ExposeClaim.of(type, retract, note)

    private fun input(
        skin: SkinAmount = SkinAmount.MODERATE,
        size: FlaccidSize = FlaccidSize.MEDIUM,
        retract: RetractState = RetractState.FULL,
        circumcision: Circumcision = Circumcision.KEPT,
        modulation: Modulation = Modulation.NONE,
    ) = FlaccidInput(skin, size, retract, circumcision, modulation)

    // —— 判据③：写「完全露出」不给 retractState ⇒ 红 ——
    @Test
    fun `判据三_不给回缩状态当场红`() {
        val error = runCatching { claim(ExposeType.NATURAL, null) }.exceptionOrNull()
        assertTrue("必须抛：$error", error is IllegalArgumentException)
        assertEquals(ExposeClaim.RETRACT_REQUIRED, error?.message)
    }

    // —— 判据③：环切后写「可来回推」⇒ 红 ——
    @Test
    fun `判据三_环切后不许可回缩`() {
        for (bad in listOf(RetractState.FULL, RetractState.PARTIAL)) {
            val error = runCatching { claim(ExposeType.POST_CIRCUMCISION, bad) }.exceptionOrNull()
            assertEquals("环切后给 $bad 必须红", ExposeClaim.CIRCUMCISED_RETRACT, error?.message)
        }
        val ok = claim(ExposeType.POST_CIRCUMCISION, RetractState.NONE)
        assertEquals(RetractState.NONE, ok.retract)
    }

    @Test
    fun `判据三_四型各有合法写法`() {
        val cases = listOf(
            claim(ExposeType.POST_CIRCUMCISION, RetractState.NONE, "环切多年"),
            claim(ExposeType.NATURAL, RetractState.NONE, "天然皮短"),
            claim(ExposeType.NATURAL, RetractState.PARTIAL, "皮少但能翻一点"),
            claim(ExposeType.ERECTION_ONLY, RetractState.FULL, "只在勃起时露出"),
            claim(ExposeType.MANUAL_UNRESTORED, RetractState.PARTIAL, "推上去没拉回"),
        )
        assertEquals("四型共五个合法例", 5, cases.size)
        assertEquals("四型必须覆盖全", ExposeType.entries.toSet(), cases.map { it.type }.toSet())
        assertTrue("每例都要有一行快照", cases.all { it.line().isNotBlank() })
    }

    @Test
    fun `判据三_状态性全露必须可完全回缩`() {
        val error = runCatching {
            claim(ExposeType.ERECTION_ONLY, RetractState.PARTIAL)
        }.exceptionOrNull()
        assertEquals(ExposeClaim.ERECTION_RETRACT, error?.message)
        assertNotNull(claim(ExposeType.ERECTION_ONLY, RetractState.FULL))
    }

    @Test
    fun `判据三_结论里出现百分比或面积单位也红`() {
        val error = runCatching {
            claim(ExposeType.NATURAL, RetractState.NONE, "露出 72%")
        }.exceptionOrNull()
        assertTrue("带量的说法不算口径：$error", error is IllegalArgumentException)
    }

    // —— 判据④：疲软表穷举 162 组合 ——
    @Test
    fun `判据四_穷举组合_合法项全带依据且矛盾项全红`() {
        var valid = 0
        var rejected = 0
        val messages = mutableSetOf<String>()
        for (skin in SkinAmount.entries) {
            for (size in FlaccidSize.entries) {
                for (retract in RetractState.entries) {
                    for (cut in Circumcision.entries) {
                        for (mod in Modulation.entries) {
                            val item = FlaccidInput(skin, size, retract, cut, mod)
                            val prediction = runCatching { FlaccidTable.predict(item) }.getOrNull()
                            if (prediction == null) {
                                rejected++
                                val message = runCatching { FlaccidTable.predict(item) }
                                    .exceptionOrNull()?.message.orEmpty()
                                messages += message
                            } else {
                                valid++
                                assertTrue("依据不许空：$item", prediction.basis.isNotBlank())
                                assertTrue("行号不许空：$item", prediction.rowId.isNotBlank())
                                assertTrue("依据须以行号开头：${prediction.basis}",
                                    prediction.basis.startsWith(prediction.rowId))
                            }
                        }
                    }
                }
            }
        }
        assertEquals("合法组合应为 99", 99, valid)
        assertEquals("矛盾组合应为 63", 63, rejected)
        assertEquals("矛盾只有两类", setOf(
            FlaccidTable.CIRCUMCISED_RETRACT,
            FlaccidTable.SKIN_RETRACT_CONFLICT,
        ), messages)
    }

    @Test
    fun `判据四_两个落表外组合各自点名`() {
        val cut = runCatching {
            FlaccidTable.predict(input(retract = RetractState.FULL, circumcision = Circumcision.DONE))
        }.exceptionOrNull()
        assertEquals(FlaccidTable.CIRCUMCISED_RETRACT, cut?.message)

        val skin = runCatching {
            FlaccidTable.predict(input(skin = SkinAmount.SPARSE, retract = RetractState.NONE))
        }.exceptionOrNull()
        assertEquals(FlaccidTable.SKIN_RETRACT_CONFLICT, skin?.message)
    }

    @Test
    fun `判据六_疲软表命中804五档且退至冠后不在其中`() {
        val hit = mutableSetOf<AreaLevel>()
        for (skin in SkinAmount.entries) {
            for (size in FlaccidSize.entries) {
                for (retract in RetractState.entries) {
                    for (mod in Modulation.entries) {
                        runCatching {
                            FlaccidTable.predict(FlaccidInput(skin, size, retract, Circumcision.KEPT, mod))
                        }.getOrNull()?.let { hit += it.level }
                    }
                }
            }
        }
        assertEquals(
            "804 的五个疲软档必须仍全部可达",
            setOf(
                AreaLevel.FULL_COVER, AreaLevel.ALMOST_ALL, AreaLevel.OVER_HALF,
                AreaLevel.CORONA_ONLY, AreaLevel.FULLY_EXPOSED,
            ),
            hit,
        )
        assertFalse("退至冠后由位图专判，不从疲软表出",
            hit.contains(AreaLevel.RETRACTED_BEHIND_CORONA))
    }

    @Test
    fun `判据四_依据里不出现百分比与面积单位`() {
        for (skin in SkinAmount.entries) {
            for (size in FlaccidSize.entries) {
                for (retract in RetractState.entries) {
                    for (mod in Modulation.entries) {
                        val item = FlaccidInput(skin, size, retract, Circumcision.KEPT, mod)
                        val prediction = runCatching { FlaccidTable.predict(item) }.getOrNull() ?: continue
                        assertFalse("不许出量：${prediction.basis}", prediction.basis.contains("%"))
                        assertFalse("不许出量：${prediction.basis}", prediction.basis.contains("cm"))
                    }
                }
            }
        }
    }

    // —— 调制：冷升一档、热降一档、两端钳住 ——
    @Test
    fun `调制_冷升一档热降一档`() {
        val bare = input(skin = SkinAmount.AMPLE, retract = RetractState.FULL)
        assertEquals(AreaLevel.FULL_COVER, FlaccidTable.predict(bare).level)
        assertEquals(
            "热／松弛降一档 ⇒ 几乎全部",
            AreaLevel.ALMOST_ALL,
            FlaccidTable.predict(bare.copy(modulation = Modulation.WARM_RELAXED)).level,
        )

        val exposed = input(skin = SkinAmount.SPARSE, retract = RetractState.FULL)
        assertEquals(AreaLevel.FULLY_EXPOSED, FlaccidTable.predict(exposed).level)
        assertEquals(
            "冷／紧张升一档 ⇒ 仅冠",
            AreaLevel.CORONA_ONLY,
            FlaccidTable.predict(exposed.copy(modulation = Modulation.COLD_TENSE)).level,
        )
    }

    @Test
    fun `调制_两端钳住不越界`() {
        val alreadyFull = input(skin = SkinAmount.AMPLE, retract = RetractState.FULL)
        assertEquals(
            "已在完全覆盖，冷／紧张不再升",
            AreaLevel.FULL_COVER,
            FlaccidTable.predict(alreadyFull.copy(modulation = Modulation.COLD_TENSE)).level,
        )
        val alreadyBare = input(skin = SkinAmount.SPARSE, retract = RetractState.FULL)
        assertEquals(
            "已在完全露出，热／松弛不再降",
            AreaLevel.FULLY_EXPOSED,
            FlaccidTable.predict(alreadyBare.copy(modulation = Modulation.WARM_RELAXED)).level,
        )
    }

    @Test
    fun `长短_偏长相对盖得少偏短相对盖得多`() {
        val base = input(skin = SkinAmount.MODERATE, retract = RetractState.PARTIAL)
        assertEquals(AreaLevel.OVER_HALF, FlaccidTable.predict(base).level)
        assertEquals(
            "偏长降一档",
            AreaLevel.CORONA_ONLY,
            FlaccidTable.predict(base.copy(size = FlaccidSize.LONG)).level,
        )
        assertEquals(
            "偏短升一档",
            AreaLevel.ALMOST_ALL,
            FlaccidTable.predict(base.copy(size = FlaccidSize.SHORT)).level,
        )
    }

    @Test
    fun `环切行_长短冷热都不再改动它`() {
        for (size in FlaccidSize.entries) {
            for (mod in Modulation.entries) {
                val item = input(
                    size = size, retract = RetractState.NONE,
                    circumcision = Circumcision.DONE, modulation = mod,
                )
                assertEquals(
                    "环切后无论 $size／$mod 都全露",
                    AreaLevel.FULLY_EXPOSED,
                    FlaccidTable.predict(item).level,
                )
            }
        }
    }

    // —— 660 四档映射（只字面转，不重立表） ——
    @Test
    fun `环切映射_660四档逐档对得上且表外词不收`() {
        assertEquals(Circumcision.DONE, PrepuceGrade.circumcisionOf("已割"))
        assertEquals(Circumcision.KEPT, PrepuceGrade.circumcisionOf("未割半包"))
        assertEquals(Circumcision.KEPT, PrepuceGrade.circumcisionOf("未割全包能翻"))
        assertEquals(Circumcision.KEPT, PrepuceGrade.circumcisionOf("过长"))
        val error = runCatching { PrepuceGrade.circumcisionOf("偶尔翻开") }.exceptionOrNull()
        assertTrue("表外档必须拒：$error", error is IllegalArgumentException)
        assertTrue(error?.message?.startsWith(PrepuceGrade.UNKNOWN_REJECT) == true)
    }

    @Test
    fun `确定性_同输入两次结论逐字一致`() {
        val item = input(skin = SkinAmount.MODERATE, size = FlaccidSize.LONG)
        assertEquals(FlaccidTable.predict(item), FlaccidTable.predict(item))
        assertEquals(FlaccidTable.predict(item).line(), FlaccidTable.predict(item).line())
    }
}
