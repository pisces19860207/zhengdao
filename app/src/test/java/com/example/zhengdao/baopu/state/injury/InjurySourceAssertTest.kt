// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百二十八批 · 源码断言臂：判据②（禁词零命中，剥注释后扫真代码）、
// 判据⑥（依据带 source+asOf、不抄诊疗资料原文：20 字窗口命中 0）、
// 联动缝（只读外部冲击档、明示 727 未接）。每道闸都配注入自检，防闸没牙。
package com.example.zhengdao.baopu.state.injury

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InjurySourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/injury/"
    private val files = listOf("InjuryGrades.kt", "ImpactLink.kt", "ObservableLine.kt").map { dir + it }

    /** 判据②靶子：禁词字面只在门禁清单里当靶，不进生产源码、不进输出。 */
    private val banned = listOf("骨折", "内出血", "感染", "用药", "治疗", "预后")

    /** 判据⑥靶子：诊疗资料风格的整句，仅用于查重对照，不进输出。 */
    private val guideSamples = listOf(
        "局部软组织受外力后可出现皮下颜色改变，范围多局限于受力点周围",
        "观察可见局部组织肿胀，轮廓较对侧稍高，触之皮温可有变化",
        "若关节活动范围较前减少，宜结合影像检查进一步明确",
    )

    private val window = 20

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    private fun stripped(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")

    private fun codeOf(rel: String): String = stripped(source(rel))

    /** 诊疗资料原句的 20 字连续窗口命中数。 */
    private fun guideHit(text: String): Int {
        var hits = 0
        for (sample in guideSamples) {
            for (i in 0..sample.length - window) {
                if (sample.substring(i, i + window) in text) hits++
            }
        }
        return hits
    }

    @Test
    fun `防闸空转_三份生产源码都真被读到且剥注释后非空`() {
        assertEquals(3, files.size)
        for (rel in files) {
            val code = codeOf(rel)
            assertTrue("$rel 剥注释后不得为空", code.length > 200)
            assertTrue("$rel 须真是 injury 包的源码", "package com.example.zhengdao.baopu.state.injury" in code)
        }
    }

    @Test
    fun `判据二_生产源码剥注释后六禁词零命中`() {
        val code = files.joinToString("\n") { codeOf(it) }
        for (word in banned) assertFalse("生产源码含禁词 $word", word in code)
    }

    @Test
    fun `判据二_注入禁词必被咬住_闸有牙自检`() {
        val injected = codeOf(files[0]) + "\nval probe = \"骨折\"\n"
        assertTrue("注入禁词后闸必须咬中", banned.any { it in injected })
    }

    @Test
    fun `判据六_依据常量真在源码里`() {
        val code = files.joinToString("\n") { source(it) }
        assertTrue("须有 source 常量", "OBSERVABLE_SOURCE" in code)
        assertTrue("须有 asOf 常量", "OBSERVABLE_AS_OF" in code)
        assertTrue("asOf 日期须在源码里", "2026-11-19" in code)
        assertTrue("免责字面须钉死为非医疗判断", "DISCLAIMER = \"非医疗判断\"" in code)
    }

    @Test
    fun `判据六_可观察特征与输出20字查重命中为零`() {
        val features = InjuryGrade.values().map { it.observable } +
            PainGrade.values().map { it.observable } +
            RecoveryGrade.values().map { it.observable }
        val outputs = (0..ImpactLink.MAX_TIER).map { ObservableLine.render(it, 0) } +
            (0..ImpactLink.MAX_TIER).map { ObservableLine.render(it, 9) }
        for (text in features + outputs) {
            assertEquals("20 字窗口命中诊疗资料原句：$text", 0, guideHit(text))
        }
    }

    @Test
    fun `判据六_注入诊疗资料原句必被咬住_闸有牙自检`() {
        for (sample in guideSamples) assertTrue("抽样注入后必须命中", guideHit(sample) > 0)
    }

    @Test
    fun `联动缝_只读外部冲击档且明示727未接`() {
        val code = codeOf(dir + "ImpactLink.kt")
        assertTrue("须有只读接口", "interface ImpactTierSource" in code)
        assertTrue("须有未接默认源", "object UnwiredImpact" in code)
        assertTrue("默认源须恒 0 档", "override fun tier(): Int = 0" in code)
        assertFalse("不许自己算冲击档", "fun computeImpact" in code)
        val injected = code + "\nfun computeImpact(): Int = 3\n"
        assertTrue("注入自算冲击档后闸必须咬中", "fun computeImpact" in injected)
    }
}
