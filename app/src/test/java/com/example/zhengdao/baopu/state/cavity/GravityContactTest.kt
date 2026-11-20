// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 交付⑤：重力接触（准静态）。
// 口径：沿 −Y 位移，直到落在支撑面（655 Environment 的地面/沙面）或已落定邻体的包围盒顶面；
// 自下而上一趟解算，每颗只解一次，**没有求根、没有时间步、没有速度、没有积分器**。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.GeometryNotes
import com.example.zhengdao.baopu.state.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GravityContactTest {

    private fun settle(vararg ps: OrganProxy, env: Environment = Environment(0.0, 0.0)): List<RestingContact> =
        GravityContact.settle(ps.toList(), env)

    private fun one(ps: List<RestingContact>, id: String): RestingContact = ps.first { it.proxyId == id }

    @Test
    fun `悬空球沿重力落到地面`() {
        val r = one(settle(CavityFixtures.sphereAtY("a", 5.0)), "a")
        assertEquals("最低点离地 4cm ⇒ 位移 40mm", 40, r.dropMilli)
        assertEquals(SupportKind.GROUND, r.kind)
        assertEquals("ground", r.restsOn)
        assertEquals(NormalBucket.PLUS_Y, r.normal)
        assertEquals(0, r.overlapMilli)
    }

    @Test
    fun `沙面高于地面时先落在沙面`() {
        val r = one(settle(CavityFixtures.sphereAtY("a", 5.0), env = Environment(0.0, 2.0)), "a")
        assertEquals("沙面在 2cm ⇒ 只走 20mm", 20, r.dropMilli)
        assertEquals(SupportKind.SAND, r.kind)
        assertEquals("sand", r.restsOn)
    }

    @Test
    fun `已经陷进沙面不反弹_只记重叠`() {
        val r = one(settle(CavityFixtures.sphereAtY("a", 2.5), env = Environment(0.0, 2.0)), "a")
        assertEquals("重力只会往下，不许往上抬", 0, r.dropMilli)
        assertEquals(SupportKind.SAND, r.kind)
        assertEquals("陷进去 0.5cm", 5, r.overlapMilli)
    }

    @Test
    fun `已经穿地同样只记账`() {
        val r = one(settle(CavityFixtures.sphereAtY("a", 0.5)), "a")
        assertEquals(0, r.dropMilli)
        assertEquals(SupportKind.GROUND, r.kind)
        assertEquals(5, r.overlapMilli)
    }

    @Test
    fun `上球落在已落定的下球上而不是穿到地面`() {
        val ps = settle(CavityFixtures.sphereAtY("s0", 6.0), CavityFixtures.sphereAtY("s1", 10.0))
        assertEquals(50, one(ps, "s0").dropMilli)
        val up = one(ps, "s1")
        assertEquals("下球落定后顶面在 2cm ⇒ 上球最低点从 9cm 走到 2cm（落到地面要走 90mm）", 70, up.dropMilli)
        assertEquals(SupportKind.PROXY, up.kind)
        assertEquals("s0", up.restsOn)
        assertEquals(NormalBucket.PLUS_Y, up.normal)
    }

    @Test
    fun `侧向错开的球各自落地`() {
        val ps = settle(
            CavityFixtures.sphere("s0", Vec3(0.0, 6.0, 0.0)),
            CavityFixtures.sphere("s1", Vec3(6.0, 10.0, 0.0)),
        )
        assertEquals(50, one(ps, "s0").dropMilli)
        assertEquals(90, one(ps, "s1").dropMilli)
        assertEquals(SupportKind.GROUND, one(ps, "s1").kind)
    }

    @Test
    fun `三球叠落_每一颗都踩在下一颗的落定位置上`() {
        val ps = settle(
            CavityFixtures.sphereAtY("s0", 6.0),
            CavityFixtures.sphereAtY("s1", 10.0),
            CavityFixtures.sphereAtY("s2", 14.0),
        )
        assertEquals("自下而上依次踩住：50 / 70 / 90", listOf(50, 70, 90), ps.map { it.dropMilli })
        assertEquals("s0", one(ps, "s1").restsOn)
        assertEquals("落定后由下往上依次承托", "s1", one(ps, "s2").restsOn)
    }

    @Test
    fun `盒状代理体同样按最低点落`() {
        val r = one(settle(CavityFixtures.boxProxy("f", Vec3(0.0, 3.0, 0.0), Vec3(1.0, 2.0, 1.0))), "f")
        assertEquals(10, r.dropMilli)
        assertEquals(SupportKind.GROUND, r.kind)
    }

    @Test
    fun `判据二七_落定后不再有向下的穿透`() {
        val ps = listOf(
            CavityFixtures.sphereAtY("s0", 6.0),
            CavityFixtures.sphereAtY("s1", 10.0),
            CavityFixtures.sphereAtY("s2", 13.0),
            CavityFixtures.sphere("s3", Vec3(3.0, 9.0, 0.0)),
        )
        val rest = GravityContact.restProxies(ps, Environment(0.0, 0.0))
        for (i in rest.indices) {
            for (j in i + 1 until rest.size) {
                val gap = CavityFixtures.signedGapCm(rest[i], rest[j])
                assertTrue("${rest[i].id}↔${rest[j].id} 落定后仍穿透 $gap", gap >= -CavityTolerances.SETTLE_TOL_CM)
            }
            assertTrue("${rest[i].id} 落定后仍穿地", rest[i].lowestY() >= -CavityTolerances.SETTLE_TOL_CM)
        }
    }

    @Test
    fun `纯函数_不改写入参`() {
        val ps = listOf(CavityFixtures.sphereAtY("s0", 6.0), CavityFixtures.sphereAtY("s1", 10.0))
        val before = ps.map { it.id to it.lowestY() }
        GravityContact.settle(ps, Environment(0.0, 0.0))
        assertEquals(before, ps.map { it.id to it.lowestY() })
    }

    @Test
    fun `空清单出空结果`() {
        assertEquals(emptyList<RestingContact>(), GravityContact.settle(emptyList(), Environment(0.0, 0.0)))
    }

    @Test
    fun `输出按代理体名升序_同输入两次逐字节一致`() {
        val ps = listOf(
            CavityFixtures.sphereAtY("z9", 12.0),
            CavityFixtures.sphereAtY("a1", 7.0),
            CavityFixtures.sphere("m5", Vec3(1.0, 9.0, 2.0)),
        )
        val first = GravityContact.settle(ps, Environment(0.0, 1.0))
        assertEquals(listOf("a1", "m5", "z9"), first.map { it.proxyId })
        assertEquals(first.toString(), GravityContact.settle(ps, Environment(0.0, 1.0)).toString())
    }

    @Test
    fun `承托读数都带假设与非测量注记`() {
        for (r in GravityContact.settle(CavityFixtures.fromBody(), Environment(0.0, 0.0))) {
            assertTrue(r.assumption.isNotBlank())
            assertEquals(GeometryNotes.NOT_MEASUREMENT, r.note)
            assertTrue("承托对象不能是自己：$r", r.restsOn != r.proxyId)
        }
    }

    @Test
    fun `真形体落地后没有代理体悬浮在空中`() {
        val rest = GravityContact.restProxies(CavityFixtures.fromBody(), Environment(0.0, 0.0))
        assertEquals(32, rest.size)
        assertTrue("落定后整体最低点该贴住地面", rest.minOf { it.lowestY() } <= CavityTolerances.SETTLE_TOL_CM)
    }

    @Test
    fun `支撑面参数只有一个来源_环境高度不许在别处写死`() {
        val ps = listOf(CavityFixtures.sphereAtY("a", 5.0))
        assertEquals(40, GravityContact.settle(ps, Environment(0.0, 0.0)).first().dropMilli)
        assertEquals(30, GravityContact.settle(ps, Environment(1.0, 1.0)).first().dropMilli)
        assertEquals(0, GravityContact.settle(ps, Environment(4.0, 4.0)).first().dropMilli)
    }
}
