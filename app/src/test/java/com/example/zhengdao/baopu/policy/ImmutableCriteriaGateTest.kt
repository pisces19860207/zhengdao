// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十批 · 不可进化清单的**源级牙齿**（用户裁定的分工：规则归 policy、牙齿归门禁）。
//
// 这里盯的是三件事，全是「清单自己有没有被做瘦」：
//   1. 三条判据的锚点必须**还在生产源文件里**——清单钉在一个已经消失的符号上，
//      就等于宣布那条硬闸已经没人守了，而门禁还是绿的；
//   2. 止损白名单必须与 `inspect/EmergencyWiring.kt` 的 `StopLossAction` **逐字对齐**
//      （对齐断了 ⇒ 例外会悄悄变宽或变窄）；
//   3. 清单本体不许长出开关（负例①「把必须人确认进化成可配置」在源级的那一半）。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.constitution.GateIo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImmutableCriteriaGateTest {

    private fun mainRoot(): File = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")

    private fun sourceOf(relFromMain: String): String {
        val f = File(mainRoot(), relFromMain)
        assertTrue("生产源文件不见了：${f.path}（清单的锚点不能指向空气）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /**
     * `路径#符号` 拆两半，文件名补回 `.kt`。
     *
     * 清单里锚点**不写** `.kt`：那是字符串常量，而法典接线闸按原始文本（连注释一起）
     * 数 `EmergencyGate.` 这类调用点，带后缀会把清单自己算成一处生产接线。
     */
    private fun pathOf(anchor: String): String =
        anchor.substringBefore('#').removePrefix("app/src/main/") + ".kt"

    private fun symbolOf(anchor: String): String = anchor.substringAfter('#').substringAfterLast('.')

    @Test
    fun `清单恰好三条且id固定`() {
        assertEquals(
            listOf(
                ImmutableCriteria.DELIVERABLE_ON_DISK,
                ImmutableCriteria.DESTRUCTIVE_CONFIRM,
                ImmutableCriteria.EMERGENCY_APPROVAL,
            ),
            ImmutableCriteria.intactIds(),
        )
        assertEquals("三条，一条不多一条不少", 3, ImmutableCriteria.ALL.size)
    }

    @Test
    fun `三条的法典依据号是既有条文不是本批新造`() {
        val byId = ImmutableCriteria.ALL.associate { it.id to it.lawRef }
        assertEquals(
            mapOf(
                ImmutableCriteria.DELIVERABLE_ON_DISK to "L19",
                ImmutableCriteria.DESTRUCTIVE_CONFIRM to "L16",
                ImmutableCriteria.EMERGENCY_APPROVAL to "L21",
            ),
            byId,
        )
    }

    @Test
    fun `每条锚点都还在生产源文件里`() {
        ImmutableCriteria.ALL.forEach { criterion ->
            assertTrue("${criterion.id} 一个锚点都没有（清单被改瘦）", criterion.anchors.isNotEmpty())
            criterion.anchors.forEach { anchor ->
                val text = sourceOf(pathOf(anchor))
                assertTrue(
                    "${criterion.cn} 的锚点 $anchor 已不在源文件里——这条硬闸现在没人守",
                    text.contains(symbolOf(anchor)),
                )
            }
        }
    }

    @Test
    fun `止损白名单与StopLossAction逐字对齐`() {
        val text = sourceOf("java/com/example/zhengdao/baopu/inspect/EmergencyWiring.kt")
        val block = text.substringAfter("enum class StopLossAction").substringBefore("fun ofWire")
        val wires = Regex("""\("([a-z_]+)",\s*"[^"]+"\)""").findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(
            "止损四件与白名单不再一致：进化通道就能多（或少）一件不经人批的自决动作",
            ImmutableCriteria.STOP_LOSS_WHITELIST,
            wires,
        )
    }

    @Test
    fun `负例一_清单不许被配置化`() {
        val raw = GateIo.stripComments(sourceOf("java/com/example/zhengdao/baopu/policy/ImmutableCriteria.kt"))
        // 开洞词表自己就写着「可配置／configurable」这些词：扫描时把那张词表挖掉，
        // 不然这条断言会咬到自己（口径：清单的**结构**不许有开关，措辞表另说）。
        val text = raw.substringBefore("val RELAXATION_MARKERS") + raw.substringAfter("val ALL")
        listOf(
            "var ALL", "mutableListOf", "enabled", "configurable",
            "JSONObject", "SharedPreferences", "fun set", "override",
        ).forEach { token ->
            assertFalse("不可进化清单里出现「$token」：清单一旦可配置就不再是清单", text.contains(token))
        }
        assertTrue("三条必须是 val（写死在代码里）", raw.contains("val ALL: List<ImmutableCriterion>"))
    }

    @Test
    fun `清单不许从数据源读入`() {
        val offenders = GateIo.kotlinSources("src/main/java/com/example/zhengdao/baopu/policy")
            .filter { (path, _) -> !path.endsWith("PolicyAssets.kt") }
            .filter { (_, text) ->
                val code = GateIo.stripComments(text)
                code.contains("ImmutableCriteria") && (code.contains("JSONObject") || code.contains("assets"))
            }
            .map { it.first }
        assertEquals("把不可进化清单变成表／资产＝把它交回给可进化通道：$offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `归一化不让斜杠与大小写躲过锚点`() {
        val real = "app/src/main/java/com/example/zhengdao/baopu/law/EmergencyGate#requiresApproval"
        assertNotNullMatch(ImmutableCriteria.match(real.replace('/', '\\').uppercase()))
        assertNotNullMatch(ImmutableCriteria.match("  " + real + " "))
        assertEquals(null, ImmutableCriteria.match(""))
    }

    @Test
    fun `加后缀躲不过清单（锚点不写点kt 是接线闸的口径不是逃逸口）`() {
        val hit = ImmutableCriteria.match(
            "app/src/main/java/com/example/zhengdao/baopu/verify/ArtifactVerifier.kt#check"
        )
        assertNotNull("清单锚点省了 .kt，候选判据把 .kt 加回来就该照样命中", hit)
        assertEquals(ImmutableCriteria.DELIVERABLE_ON_DISK, hit?.id)
    }

    private fun assertNotNullMatch(hit: ImmutableCriterion?) {
        assertTrue("反斜杠／大写／空格不该躲过清单", hit != null)
    }

    @Test
    fun `开洞词表覆盖中文与英文两种写法`() {
        assertTrue(ImmutableCriteria.RELAXATION_MARKERS.any { it == "可配置" })
        assertTrue(ImmutableCriteria.RELAXATION_MARKERS.any { it == "configurable" })
        assertEquals("开关", ImmutableCriteria.relaxationMarker("给紧急卡加个开关"))
        assertEquals(null, ImmutableCriteria.relaxationMarker("把误判的那一类补上"))
    }
}
