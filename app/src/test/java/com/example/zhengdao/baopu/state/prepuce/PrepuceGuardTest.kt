// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百零四批 · 判据⑤（冠沟合名）与判据④（不出百分比／cm²）、判据②（露冠不推露沟）的闸门证据。
// 两个方向都测：该红的必须红（正例），解剖固定术语与普通描述不许误杀（负例）。
package com.example.zhengdao.baopu.state.prepuce

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepuceGuardTest {

    // —— 判据⑤：合名 ⇒ 红 ——
    @Test
    fun `判据五_合名逐例抓得到`() {
        val merged = listOf(
            "冠沟", "冠沟区", "冠与沟", "冠／沟", "龟头冠沟", "corona+sulcus", "corona sulcus", "冠状沟＋冠",
        )
        for (name in merged) {
            assertTrue("「$name」是把冠与沟合成一格，必须红", PrepuceGuard.isMergedAreaName(name))
        }
    }

    @Test
    fun `判据五_解剖固定术语与单区名不许误杀`() {
        val fine = listOf(
            "冠状沟", "龟头冠", "冠", "沟", "冠状沟缘", "系带", "尿道口", "包皮", "柱身·腹侧（阴茎缝）", "",
        )
        for (name in fine) {
            assertFalse("「$name」是单区名或固定术语，不该红", PrepuceGuard.isMergedAreaName(name))
        }
    }

    @Test
    fun `判据五_合名抛出时带原话`() {
        val error = runCatching { PrepuceGuard.requireSeparateAreaName("冠沟") }.exceptionOrNull()
        assertTrue("必须抛：$error", error is IllegalArgumentException)
        assertTrue(
            "消息须以口径原话开头：${error?.message}",
            error?.message?.startsWith(PrepuceGuard.MERGED_NAME_REJECT) == true,
        )
        assertTrue("消息须带收到的原词：${error?.message}", error?.message?.contains("冠沟") == true)
    }

    @Test
    fun `判据五_两个真实子区名互不覆盖`() {
        assertFalse(PrepuceGuard.isMergedAreaName(SubRegion.CORONA.label))
        assertFalse(PrepuceGuard.isMergedAreaName(SubRegion.SULCUS.label))
        assertTrue("而两区拼在一起就是合名",
            PrepuceGuard.isMergedAreaName(SubRegion.CORONA.label + SubRegion.SULCUS.label))
    }

    // —— 判据④：出百分比／cm² ⇒ 红 ——
    @Test
    fun `判据四_百分比与面积单位逐例抓得到`() {
        val bad = listOf("覆盖 72%", "覆盖 72％", "约 3cm²", "约 3 cm2", "3 平方厘米", "12 毫米", "0.5cm")
        for (text in bad) {
            val error = runCatching { PrepuceGuard.rejectMeasures(text) }.exceptionOrNull()
            assertTrue("「$text」带量，必须红", error is IllegalArgumentException)
            assertTrue(
                "消息须以口径原话开头：${error?.message}",
                error?.message?.startsWith(PrepuceGuard.MEASURE_REJECT) == true,
            )
        }
    }

    @Test
    fun `判据四_纯文字描述不误杀`() {
        val fine = listOf(
            "龟头五区全 covered", "只剩冠与沟还盖着", "半区计 4（共 10）", "疲软偏长降一档", "",
        )
        for (text in fine) {
            PrepuceGuard.rejectMeasures(text)
        }
        assertTrue("这组负例必须非空", fine.isNotEmpty())
    }

    // —— 判据②：露冠不推露沟 ——
    @Test
    fun `判据二_冠露沟覆才算退至冠后`() {
        assertTrue(PrepuceGuard.isRetractedBehindCorona(CoverState.EXPOSED, CoverState.COVERED))
        assertTrue(PrepuceGuard.isRetractedBehindCorona(CoverState.EXPOSED, CoverState.PARTIAL))
        assertFalse("冠没露就不叫退至冠后",
            PrepuceGuard.isRetractedBehindCorona(CoverState.COVERED, CoverState.COVERED))
        assertFalse("沟也露了就不是这个中间态",
            PrepuceGuard.isRetractedBehindCorona(CoverState.EXPOSED, CoverState.EXPOSED))
    }
}
