// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百四十一批 · 判据④（产物侧）与判据⑤（确定性）。
// 判据④ 有两把尺：结论对象**结构上**不许有浮点／坐标／量纲字段（反射逐字段验），
// 文案**逐条**不许出现数字与单位（全组合穷举）；另有一把源码尺，钉住决策层不碰度量面。
// 判据⑤：同一输入两跑逐字一致，且结论次序不依赖任何 Map 的迭代顺序。
package com.example.zhengdao.baopu.state.containment

import com.example.zhengdao.baopu.constitution.GateIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class ContainmentBoundaryTest {

    private val pkg = "src/main/java/com/example/zhengdao/baopu/state/containment"

    /** 决策层七件：只认档、三态、原因与文案，一律不许碰度量面。 */
    private val decisionFiles = listOf(
        "SpaceGrades.kt", "Stance.kt", "StanceAudit.kt", "ContainmentState.kt", "Phrasebook.kt",
        "ContainmentVerdict.kt", "Containment.kt",
    )

    private val metric = mapOf(
        "厘米" to Regex("""厘米|\bcm\b"""),
        "毫米" to Regex("""毫米|\bmm\b"""),
        "浮点字段" to Regex("""\b(val|var)\s+\w+\s*:\s*(Double|Float)\b"""),
        "浮点返回" to Regex("""\bfun\s+\w+[^=\n]*:\s*(Double|Float)\b"""),
        "坐标类型" to Regex("""\bVec3\b"""),
        "655/660 度量入口" to Regex("""\b(JointChain|PoseValidity|Reachability|SegmentProxy|Environment|support)\b"""),
    )

    @Test
    fun `判据四_结论对象结构上没有浮点没有坐标没有量纲`() {
        val readouts = listOf(
            ContainmentVerdict::class.java,
            ContainmentSurvey::class.java,
            StanceAudit::class.java,
            AuditReading::class.java,
            SpaceProfile::class.java,
            StanceDemand::class.java,
        )
        for (cls in readouts) {
            val bad = cls.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .filter { f ->
                    val type = f.type
                    type == Double::class.java || type == Float::class.java ||
                        type == java.lang.Double::class.java || type == java.lang.Float::class.java ||
                        f.name.contains("cm", ignoreCase = true) || f.name.contains("mm", ignoreCase = true)
                }
                .map { "${it.name}:${it.type.simpleName}" }
            assertTrue("${cls.simpleName} 是判据产物，不许带浮点／量纲字段：$bad", bad.isEmpty())
        }
    }

    @Test
    fun `判据四_穷举全部结论的文案里没有数字没有单位没有坐标`() {
        val digit = Regex("""\d""")
        val unit = Regex("""\bcm\b|\bmm\b|厘米|毫米""")
        val coordinate = Regex("""[（(]\s*-?\d|坐标|x\s*=|z\s*=""")
        var checked = 0
        for (opening in Opening.entries) {
            for (height in Grade.entries) {
                for (width in Grade.entries) {
                    for (depth in Grade.entries) {
                        val space = SpaceProfile(height, width, depth, opening)
                        for (stance in Stance.entries) {
                            for (facing in Facing.entries) {
                                for (broken in listOf(false, true)) {
                                    val audit = if (broken) {
                                        ContainmentFixtures.audit(stance, mapOf(AuditItem.GROUND to AuditState.FAIL))
                                    } else {
                                        ContainmentFixtures.audit(stance)
                                    }
                                    val verdict = Containment.judge(space, stance, facing, audit)
                                    assertTrue("文案里出现数字：${verdict.text}", !digit.containsMatchIn(verdict.text))
                                    assertTrue("文案里出现单位：${verdict.text}", !unit.containsMatchIn(verdict.text))
                                    assertTrue("文案里出现坐标：${verdict.text}", !coordinate.containsMatchIn(verdict.text))
                                    checked++
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("穷举不能空转：$checked", checked >= 1900)
    }

    @Test
    fun `判据四_决策层源码不碰度量面_坐标与尺寸只在唯一的度量桥里出现`() {
        val hits = decisionFiles.flatMap { name ->
            GateIo.scanLines("$pkg/$name", GateIo.read("$pkg/$name"), metric)
        }.map { it.where() }
        assertTrue("决策层七件一件都不许出现厘米／毫米／浮点／坐标／655 度量入口：$hits", hits.isEmpty())
    }

    @Test
    fun `判据四_度量桥只此一件_且桥自己不写单位`() {
        val files = GateIo.kotlinSources(pkg).map { it.first }.sorted()
        assertEquals(
            "本包的源文件就是这八件（七件决策层＋一件度量桥）",
            decisionFiles.sorted() + "StanceLegality.kt",
            files,
        )
        val bridge = "$pkg/StanceLegality.kt"
        val unitOnly = mapOf("厘米" to metric.getValue("厘米"), "毫米" to metric.getValue("毫米"))
        val hits = GateIo.scanLines(bridge, GateIo.read(bridge), unitOnly).map { it.where() }
        assertTrue("度量桥也不许在源码里写单位（它只搬 655 的类型，不复制量纲）：$hits", hits.isEmpty())
    }

    @Test
    fun `判据四_源码尺有牙_注入厘米字段与坐标类型必被咬住`() {
        val dirty = """
            data class Bad(val gapCm: Double, val at: Vec3)
            fun reach(): Double = 1.0
        """.trimIndent()
        val hits = GateIo.scanLines("bad.kt", dirty, metric)
        assertTrue("这把尺必须真能抓：$hits", hits.size >= 2)
        val clean = """
            data class Ok(val grade: Grade, val text: String)
            fun shortage(need: Grade, have: Grade): Int = need.ordinal - have.ordinal
        """.trimIndent()
        assertTrue("干净代码不许误伤：${GateIo.scanLines("ok.kt", clean, metric)}", GateIo.scanLines("ok.kt", clean, metric).isEmpty())
    }

    @Test
    fun `判据五_总表两跑逐字节一致_且重算夹具也一致`() {
        val space = SpaceProfile(Grade.MEDIUM, Grade.SMALL, Grade.LARGE, Opening.FRONT)
        val first = Containment.survey(space, Facing.SIDEWAYS, ContainmentFixtures.legalAudits()).snapshot()
        val second = Containment.survey(space, Facing.SIDEWAYS, ContainmentFixtures.legalAudits()).snapshot()
        assertEquals("同一输入两跑必须逐字节一致", first, second)
        assertEquals("快照＝表头＋四行结论", 5, first.trimEnd().lines().size)
        assertTrue("快照里不许出现数字（只装档与三态）", first.none { it.isDigit() })
        assertTrue("快照要真的装着结论", first.contains("侧身") || first.contains("跪"))
    }

    @Test
    fun `判据五_四档结论的次序与Map无关_乱序输入同一快照`() {
        val space = SpaceProfile(Grade.LARGE, Grade.LARGE, Grade.MEDIUM, Opening.TOP)
        val ordered = ContainmentFixtures.legalAudits()
        val shuffled = linkedMapOf(
            Stance.PRONE to ordered.getValue(Stance.PRONE),
            Stance.SIDLE to ordered.getValue(Stance.SIDLE),
            Stance.KNEEL to ordered.getValue(Stance.KNEEL),
            Stance.STAND_FRONT to ordered.getValue(Stance.STAND_FRONT),
        )
        assertEquals(
            "台账顺序换了，结论次序与文字都不许变",
            Containment.survey(space, Facing.TOWARD, ordered).snapshot(),
            Containment.survey(space, Facing.TOWARD, shuffled).snapshot(),
        )
    }

    @Test
    fun `判据五_理由集合去重且按固定次序`() {
        val space = SpaceProfile(Grade.SMALL, Grade.SMALL, Grade.SMALL, Opening.TOP)
        for (stance in Stance.entries) {
            val audit = ContainmentFixtures.audit(stance, mapOf(AuditItem.GROUND to AuditState.FAIL))
            val verdict = Containment.judge(space, stance, Facing.TOWARD, audit)
            assertEquals("原因不许重复", verdict.reasons.distinct(), verdict.reasons)
            assertEquals("原因按枚举次序", verdict.reasons.sortedBy { it.ordinal }, verdict.reasons)
        }
    }
}
