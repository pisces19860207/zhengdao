// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十五批 · 源码门禁臂：与力学通道分离（不读 727 冲击档、零 injury 引用）、
// unverified 默认关钉死、三分类钉死、默认归流出钉死、唯一出口钉死、
// 流出词条闭集（节律词只在射出侧）。每道闸都配注入自检，防闸没牙。
package com.example.zhengdao.baopu.state.prostate

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProstateSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/prostate/"
    private val files = listOf(
        "ProstateSensation.kt", "Expulsion.kt", "TriggerClass.kt", "ProstateChannel.kt",
    ).map { dir + it }

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    private fun stripped(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")

    private fun codeOf(rel: String): String = stripped(source(rel))

    private val allCode: String get() = files.joinToString("\n") { codeOf(it) }
    private val allRaw: String get() = files.joinToString("\n") { source(it) }

    @Test
    fun `防闸空转_四份生产源码都真被读到且剥注释后非空`() {
        assertEquals(4, files.size)
        for (rel in files) {
            val code = codeOf(rel)
            assertTrue("$rel 剥注释后不得为空", code.length > 100)
            assertTrue("$rel 须真是 prostate 包的源码", "package com.example.zhengdao.baopu.state.prostate" in code)
        }
    }

    @Test
    fun `判据一_与力学通道分离_剥注释后零冲击零injury零力学`() {
        val code = allCode
        for (word in listOf("ImpactTierSource", "ImpactLink", "冲击", "injury", "力学")) {
            assertFalse("生产源码出现力学通道字样 $word", word in code)
        }
    }

    @Test
    fun `判据一_注入injury引用必被咬住_闸有牙自检`() {
        val injected = allCode + "\nimport com.example.zhengdao.baopu.state.injury.ImpactLink\n"
        assertTrue("注入 injury 引用后闸必须咬中", "injury" in injected)
    }

    @Test
    fun `判据三_unverified默认关必须钉死在源码里`() {
        assertTrue("默认关常量须钉 false", "DEFAULT_ALLOW_UNVERIFIED = false" in allRaw)
        assertTrue("出口默认参数须走常量", "allowUnverified: Boolean = DEFAULT_ALLOW_UNVERIFIED" in allRaw)
        val flipped = allRaw.replace("DEFAULT_ALLOW_UNVERIFIED = false", "DEFAULT_ALLOW_UNVERIFIED = true")
        assertFalse("翻成 true 后闸必须咬住（证明闸盯的是这个字面）", "DEFAULT_ALLOW_UNVERIFIED = false" in flipped)
    }

    @Test
    fun `判据三_触发三分类三档三id钉死`() {
        for (id in listOf("\"external\"", "\"self\"", "\"unverified\"")) {
            assertTrue("三分类 id $id 须在源码里", id in allRaw)
        }
        assertTrue("三分类档数须钉 3", "const val COUNT = 3" in codeOf(dir + "TriggerClass.kt"))
    }

    @Test
    fun `判据二_默认归流出钉死`() {
        assertTrue("默认型须钉 OUTFLOW", "val DEFAULT: ExpulsionKind = OUTFLOW" in allRaw)
        assertTrue("出口缺省型须走 DEFAULT", "kind ?: ExpulsionKind.DEFAULT" in allRaw)
        val flipped = allRaw.replace("val DEFAULT: ExpulsionKind = OUTFLOW", "val DEFAULT: ExpulsionKind = EMISSION")
        assertFalse("翻成射出后闸必须咬住", "val DEFAULT: ExpulsionKind = OUTFLOW" in flipped)
    }

    @Test
    fun `判据_唯一出口_事件行只能由emit拼出`() {
        assertEquals("fun emit 全包只许一处", 1, Regex("fun emit").findAll(allCode).count())
        for (rel in files.filter { it != dir + "ProstateChannel.kt" }) {
            assertFalse("$rel 不许自己拼免责行", "DISCLAIMER）" in codeOf(rel))
        }
        assertTrue("出口行须带免责", "（\$DISCLAIMER）" in allRaw)
    }

    @Test
    fun `判据_流出词条闭集_节律词只在射出侧`() {
        assertTrue("节律词闭集须钉在出口文件里", "RHYTHM_WORDS = listOf" in codeOf(dir + "ProstateChannel.kt"))
        assertTrue("流出兜底拒行须存在", "流出行出现节律词" in allRaw)
        val outflowLine = source(dir + "Expulsion.kt").lines().first { "OUTFLOW(" in it }
        for (w in RHYTHM_WORDS) assertFalse("流出词条带节律词 $w：$outflowLine", w in outflowLine)
        val emissionLine = source(dir + "Expulsion.kt").lines().first { "EMISSION(" in it }
        assertTrue("射出词条须带节律口径", "节律" in emissionLine)
    }

    @Test
    fun `判据一_感觉四档档数钉死`() {
        assertTrue("感觉档数须钉 4", "const val COUNT = 4" in codeOf(dir + "ProstateSensation.kt"))
        assertTrue("依据时点须钉 2026-11-20", "2026-11-20" in allRaw)
    }
}
