// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十九批 · 双套型拓扑四约束（判据⑤，一条约束一条夹具）：
// a) 出口各自独立——共用开口即拒；
// b) 血供/神经不重复占段——两套抢同段同通道即拒；
// c) 上下高度给档——同档即拒，且类型层不存在厘米字段；
// d) 与肛门阴道型互斥——两模块同时加载即拒。
package com.example.zhengdao.baopu.state.fiction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntersexTopologyTest {

    private fun topo(
        outlets: List<Outlet> = listOf(Outlet("urination_ejaculation", "尿道口"), Outlet("vaginal", "阴道口")),
        claims: List<SupplyClaim> = listOf(
            SupplyClaim("male", "vessel", "阴部内前段"),
            SupplyClaim("female", "vessel", "阴部内后段"),
            SupplyClaim("male", "nerve", "背神经上段"),
            SupplyClaim("female", "nerve", "背神经下段"),
        ),
        male: HeightBand = HeightBand.UPPER,
        female: HeightBand = HeightBand.LOWER,
    ) = DualSuiteTopology(outlets, claims, male, female, FictionCanon.SOURCE_CANON, FictionCanon.CANON_VERSION, FictionCanon.AS_OF_CANON)

    // ── 约束 a：出口各自独立 ────────────────────────────────────────────────

    @Test
    fun `约束a_两出口共用同一开口_红`() {
        assertThrows(IllegalArgumentException::class.java) {
            topo(outlets = listOf(Outlet("penile", "会阴口"), Outlet("vaginal", "会阴口")))
        }
    }

    @Test
    fun `约束a_出口各自独立_绿`() {
        assertEquals(2, topo().outlets.size)
    }

    // ── 约束 b：血供/神经不重复占段 ─────────────────────────────────────────

    @Test
    fun `约束b_两套抢同段血管_红`() {
        assertThrows(IllegalArgumentException::class.java) {
            topo(claims = listOf(
                SupplyClaim("male", "vessel", "阴部内共用段"),
                SupplyClaim("female", "vessel", "阴部内共用段"),
            ))
        }
    }

    @Test
    fun `约束b_两套抢同段神经_红`() {
        assertThrows(IllegalArgumentException::class.java) {
            topo(claims = listOf(
                SupplyClaim("male", "nerve", "共用神经段"),
                SupplyClaim("female", "nerve", "共用神经段"),
            ))
        }
    }

    @Test
    fun `约束b_血管与神经同段但通道不同_绿`() {
        val t = topo(claims = listOf(
            SupplyClaim("male", "vessel", "共段"),
            SupplyClaim("female", "nerve", "共段"),
        ))
        assertEquals(2, t.claims.size)
    }

    @Test
    fun `约束b_占用声明的_system_channel_闭集`() {
        assertThrows(IllegalArgumentException::class.java) { SupplyClaim("other", "vessel", "段") }
        assertThrows(IllegalArgumentException::class.java) { SupplyClaim("male", "muscle", "段") }
    }

    // ── 约束 c：上下高度给档不给厘米 ────────────────────────────────────────

    @Test
    fun `约束c_两套同档_红`() {
        assertThrows(IllegalArgumentException::class.java) {
            topo(male = HeightBand.MIDDLE, female = HeightBand.MIDDLE)
        }
    }

    @Test
    fun `约束c_高度只有枚举档_类型层无厘米字段`() {
        val fields = DualSuiteTopology::class.java.declaredFields.map { it.name to it.type.name }
        assertTrue("高度字段必须是枚举：$fields", fields.any { it.first == "maleBand" && it.second.contains("HeightBand") })
        assertTrue("高度字段必须是枚举：$fields", fields.any { it.first == "femaleBand" && it.second.contains("HeightBand") })
        // 数值字段只许 canonVersion 一个（版本号）与编译器合成件（$stable 等）；
        // 任何带长度/厘米语义的数值字段都不许存在。
        val numeric = fields.filter {
            it.second in setOf("int", "double", "float", "long") &&
                it.first != "canonVersion" && !it.first.startsWith("$")
        }
        assertEquals("双套型拓扑不许有尺寸（厘米）数值字段：$numeric", 0, numeric.size)
    }

    // ── 约束 d：与肛门阴道型互斥 ────────────────────────────────────────────

    @Test
    fun `约束d_两模块同时加载_拒`() {
        val r = ModuleExclusion.load(setOf(IntersexModule.DUAL_SUITE, IntersexModule.ANAL_VAGINAL))
        assertTrue("同时加载必须被拒：$r", r is ModuleLoad.Rejected)
        assertTrue("拒绝理由要点名互斥：$r", (r as ModuleLoad.Rejected).reason.contains("互斥"))
    }

    @Test
    fun `约束d_单模块加载_放行`() {
        assertTrue(ModuleExclusion.load(setOf(IntersexModule.DUAL_SUITE)) is ModuleLoad.Loaded)
        assertTrue(ModuleExclusion.load(setOf(IntersexModule.ANAL_VAGINAL)) is ModuleLoad.Loaded)
        assertTrue(ModuleExclusion.load(emptySet()) is ModuleLoad.Loaded)
    }

    // ── canon 戳 ────────────────────────────────────────────────────────────

    @Test
    fun `拓扑记录也带_canon_戳`() {
        assertThrows(IllegalArgumentException::class.java) {
            DualSuiteTopology(
                topo().outlets, topo().claims, HeightBand.UPPER, HeightBand.LOWER,
                "假设", FictionCanon.CANON_VERSION, FictionCanon.AS_OF_CANON,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DualSuiteTopology(
                topo().outlets, topo().claims, HeightBand.UPPER, HeightBand.LOWER,
                FictionCanon.SOURCE_CANON, 99, FictionCanon.AS_OF_CANON,
            )
        }
    }
}
