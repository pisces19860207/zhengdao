// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百五十六批 · corrected 语义收口的**源码门禁**：
//   判据③ 凭空 true ⇒ 红：全仓不许出现 `Outcome(..., corrected = true/false)` 裸布尔写入口；
//           corrected 只能由 evidence 派生，且每个 evidence 构造必带 cause/from/to。
//   判据④ 与 753 loop=rpe 对齐：Rpe 对外形状恒为 `data class Rpe(val value: Int, val note: String)`，
//           value 是 Int（753 reading.rpe 的 Num -1..1 同型）、note 是 String（753 reading.note 的 Text）。
//   本测试从**磁盘读源码**当场重算，不以内存里的字符串自证；判别器先喂违规样本证明咬得住。
package com.example.zhengdao.baopu.bio

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertTrue
import org.junit.Test

class RpeCorrectionGateTest {

    private val striatum = "app/src/main/java/com/example/zhengdao/baopu/bio/Striatum.kt"
    private val habit = "app/src/main/java/com/example/zhengdao/baopu/habit/HabitWiring.kt"

    private fun src(rel: String): String = SrcRoots.find(rel).readText(Charsets.UTF_8)

    /** 剥掉行注释与块注释，避免注释里的字面量把判据顶成假绿。 */
    private fun stripComments(s: String): String =
        s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lines().joinToString("\n") { it.substringBefore("//") }

    /** 裸布尔写入口判别器：命中 `Outcome(...)` 参数位上直接写 corrected= 即违规。 */
    private fun bareBoolOutcomes(code: String): List<String> {
        val re = Regex("Outcome\\s*\\([^)]*\\bcorrected\\s*=", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(code).map { it.value.take(80) }.toList()
    }

    @Test
    fun discriminatorBitesOnViolatingSample() {
        val bad = "Striatum.rpe(Striatum.Outcome(ok = ok, corrected = true)).value"
        assertTrue("判别器自己必须咬得住违规样本", bareBoolOutcomes(bad).isNotEmpty())
        val good = "Striatum.rpe(Striatum.Outcome(ok = ok, evidence = null)).value"
        assertTrue("合法样本不得被误判", bareBoolOutcomes(good).isEmpty())
    }

    @Test
    fun noBareCorrectedBooleanWriteAnywhere() {
        val hits = bareBoolOutcomes(stripComments(src(striatum))) +
            bareBoolOutcomes(stripComments(src(habit)))
        assertTrue("发现裸 corrected= 写入口（凭空 true 可构造）：$hits", hits.isEmpty())
    }

    @Test
    fun correctedIsDerivedReadOnlyProperty() {
        val code = stripComments(src(striatum))
        assertTrue("corrected 必须是 getter 派生", code.contains("val corrected: Boolean get() = evidence != null"))
        assertTrue("Outcome 不再声明裸布尔 corrected 构造参数",
            !Regex("data class Outcome\\s*\\([^)]*\\bcorrected\\s*:\\s*Boolean").containsMatchIn(code))
    }

    @Test
    fun correctionRequiresCauseFromTo() {
        val code = stripComments(src(striatum))
        assertTrue("Correction 必须是三字段构造", code.contains("data class Correction(val cause: String, val from: Int, val to: Int)"))
        assertTrue("cause 必须有非空校验", code.contains("cause.isNotBlank()"))
        assertTrue("from/to 必须有值域校验", code.contains("from in -1..1 && to in -1..1"))
        assertTrue("from==to 必须被拒", code.contains("from != to"))
    }

    @Test
    fun rpeWireShapeMatches753LoopRpe() {
        val code = stripComments(src(striatum))
        assertTrue("Rpe 对外形状须与 753 loop=rpe 的 rpe(Int)/note(String) 一致：",
            code.contains("data class Rpe(val value: Int, val note: String)"))
        assertTrue("Rpe 不得多带 corrected/evidence 字段",
            !Regex("data class Rpe\\s*\\([^)]*(corrected|evidence|cause|from|to)\\b").containsMatchIn(code))
    }

    @Test
    fun productionCallUsesEvidenceNull() {
        val code = stripComments(src(habit))
        assertTrue("生产缝必须如实传 evidence=null（无修正事件源、不回填）",
            code.contains("Striatum.Outcome(ok = ok, evidence = null)"))
    }
}
