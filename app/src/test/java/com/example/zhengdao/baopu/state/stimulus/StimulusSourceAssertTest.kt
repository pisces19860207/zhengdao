// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十四批 · 源码断言臂：
//   判据④「四段入口不互读」（感受不得由节律/冲击/分泌推出）——源码级硬锁；
//   「无积分器」——生产源码零可变字段；
//   依据带 source/asOf；顺滑只读湿档源、不接数值入参。
// 每道闸配注入自检，防闸没牙（同 728 的 Injection 做法）。
package com.example.zhengdao.baopu.state.stimulus

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StimulusSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/stimulus/"
    private val files = listOf(
        "StimulusGrades.kt", "Rhythm.kt", "SecretionCurve.kt",
        "SensitivityCurve.kt", "TimeAxes.kt", "Sensation.kt",
    )
    private val sensationRel = dir + "Sensation.kt"

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    private fun stripped(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")

    private fun codeOf(rel: String): String = stripped(source(rel))

    @Test
    fun `防闸空转_六份生产源码都真被读到且剥注释后非空`() {
        assertEquals(6, files.size)
        for (name in files) {
            val code = codeOf(dir + name)
            assertTrue("$name 剥注释后须非空", code.length > 100)
            assertTrue("$name 须真是 stimulus 包源码", "package com.example.zhengdao.baopu.state.stimulus" in code)
        }
    }

    /** 感受段禁止出现的其它段标识符——四段入口不互读的靶子清单。 */
    private val foreignSegments = listOf(
        "Rhythm", "SensitivityCurve", "SecretionCurve", "Adaptation", "Recovery", "LongTermAdaptation",
        "frequency", "depth", "withdraw", "intensity", "accumulation", "impact", "冲击",
    )

    @Test
    fun `判据四_感受段不引用任何节律冲击分泌标识符`() {
        val code = codeOf(sensationRel)
        for (token in foreignSegments) {
            assertFalse("感受段不得出现 $token（四段入口不互读）", token in code)
        }
        // 顺滑只读湿档源，不自行推导
        assertTrue("顺滑须读 WetnessSource", "fun wetnessGrade()" in code)
        assertTrue("须有未接默认源", "UnwiredWetness" in code)
    }

    @Test
    fun `判据四_注入外部段引用必被咬住_闸有牙自检`() {
        val code = codeOf(sensationRel)
        val injected = code + "\nval leak = Rhythm.frequencyAt(3)\n"
        assertTrue("注入节律引用后闸必须咬中", foreignSegments.any { it in injected })
    }

    @Test
    fun `判据五_顺滑不接数值入参_只从湿档源读`() {
        val code = codeOf(sensationRel)
        // of() 的入参里没有 smoothness / wetness 数值参数
        val ofBody = code.substringAfter("fun of(", "").substringBefore("): SensationReading", "")
        assertFalse("顺滑不得作为入参自造", "smoothness:" in ofBody)
        assertFalse("湿档数值不得作为入参自造", "wetness:" in ofBody)
        assertTrue("顺滑字段须由湿档源赋值", "smoothness = source.wetnessGrade()" in code)
    }

    @Test
    fun `无积分器_生产源码零可变字段`() {
        val code = files.joinToString("\n") { codeOf(dir + it) }
        // var 只在注释里出现过（已剥离）；生产代码不许有可变字段/累积器
        assertFalse("生产源码不得有 var 可变状态", Regex("\\bvar\\b").containsMatchIn(code))
    }

    @Test
    fun `无积分器_注入var必被咬住_闸有牙自检`() {
        val code = codeOf(dir + "Rhythm.kt")
        val injected = code + "\nvar acc = 0\n"
        assertTrue("注入 var 后闸必须咬中", Regex("\\bvar\\b").containsMatchIn(injected))
    }

    @Test
    fun `依据常量真在源码里且时点正确`() {
        val code = files.joinToString("\n") { source(dir + it) }
        assertTrue("须有 source 常量", "STIMULUS_SOURCE" in code)
        assertTrue("须有 asOf 常量", "STIMULUS_AS_OF" in code)
        assertTrue("asOf 日期须钉死", "2026-11-19" in code)
    }

    @Test
    fun `短时饱和与长期基线分属不同对象不混写`() {
        val sens = codeOf(dir + "SensitivityCurve.kt")
        val time = codeOf(dir + "TimeAxes.kt")
        assertTrue("短期饱和曲线在 SensitivityCurve", "SATURATION_THRESHOLD" in sens)
        assertTrue("长期适应在 TimeAxes", "LongTermAdaptation" in time)
        assertTrue("两种时间轴须分开记", "SLOW_WEEKS" in time && "ADAPT_BEATS" in time)
    }
}
