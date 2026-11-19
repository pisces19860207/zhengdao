// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 代理体导出核验：17 段与关节一一对应・15 区锚点齐全・纯平移・与高度场同源。
package com.example.zhengdao.baopu.state.form

import com.example.zhengdao.baopu.state.geometry.Box
import com.example.zhengdao.baopu.state.geometry.Capsule
import com.example.zhengdao.baopu.state.geometry.Ellipsoid
import com.example.zhengdao.baopu.state.geometry.JointNames
import com.example.zhengdao.baopu.state.geometry.SegmentProxy
import com.example.zhengdao.baopu.state.geometry.Sphere
import com.example.zhengdao.baopu.state.geometry.Vec3
import com.example.zhengdao.baopu.state.geometry.lowestY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyProxiesTest {

    private val props = FormFixtures.props

    private fun build(genital: GenitalInput = GenitalInput()): Triple<BodyFrame, GenitalModel, BodyProxies> {
        val spec = FormFixtures.spec(genital = genital)
        val frame = BodyFrame(spec, props)
        val g = GenitalModel(genital, spec, frame, FactsFromMd, FormFixtures.zones, FormFixtures.anatomy)
        return Triple(frame, g, ProxyExport.of(frame, props, g))
    }

    private fun seg(p: BodyProxies, name: String) = p.segments.first { it.name == name }

    private fun zoneProxy(p: BodyProxies, name: String) = p.genital.first { it.name == name }

    @Test
    fun `十七段代理体与655关节一一对应`() {
        val (_, _, p) = build()
        assertEquals(17, p.segments.size)
        assertEquals(JointNames.ALL.toSet(), p.segments.map { it.name }.toSet())
        assertEquals(17, p.segments.map { it.name }.distinct().size)
        assertEquals(32, p.all.size)
    }

    @Test
    fun `十三区出十五个代理体_左右各一的不合并`() {
        val (_, _, p) = build()
        assertEquals(15, p.genital.size)
        assertEquals(13, p.genital.map { it.name.substringBefore('.') }.toSet().size)
        assertTrue(p.genital.any { it.name == "testis.left" })
        assertTrue(p.genital.any { it.name == "testis.right" })
        assertTrue(p.genital.any { it.name == "cord.left" })
        assertTrue(p.genital.any { it.name == "shaft.mid" })
    }

    @Test
    fun `每段每区锚点齐全且指向合法关节`() {
        val (_, _, p) = build()
        assertEquals(p.all.map { it.name }.toSet(), p.anchors.keys)
        for ((name, joint) in p.anchors) assertTrue("$name 锚点 $joint 不是合法关节", joint in JointNames.ALL)
        for (s in p.segments) assertEquals("段锚点就是它自己", s.name, p.anchors[s.name])
        for (z in p.genital) assertEquals("区锚点须是根部", JointNames.ROOT, p.anchors[z.name])
    }

    @Test
    fun `锚点不齐即拒_名重复即拒_段名缺关节即拒`() {
        val (_, _, p) = build()
        assertThrows(IllegalArgumentException::class.java) { p.copy(anchors = p.anchors - "head") }
        assertThrows(IllegalArgumentException::class.java) {
            p.copy(anchors = p.anchors + ("head" to "nowhere"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            p.copy(segments = p.segments + SegmentProxy("head", Sphere(Vec3.ZERO, 1.0)))
        }
        assertThrows(IllegalArgumentException::class.java) { p.copy(segments = p.segments.drop(1)) }
        assertThrows(IllegalArgumentException::class.java) { p.copy(genital = emptyList()) }
    }

    @Test
    fun `形状类型按口径表_躯干拆胸与脊_足是盒`() {
        val (_, _, p) = build()
        assertTrue(seg(p, JointNames.CHEST).shape is Ellipsoid)
        assertTrue(seg(p, JointNames.SPINE).shape is Ellipsoid)
        assertTrue(seg(p, JointNames.ROOT).shape is Ellipsoid)
        assertTrue(seg(p, JointNames.HEAD).shape is Ellipsoid)
        assertTrue(seg(p, JointNames.NECK).shape is Capsule)
        assertTrue(seg(p, JointNames.SHOULDER_L).shape is Capsule)
        assertTrue(seg(p, JointNames.HIP_R).shape is Capsule)
        assertTrue(seg(p, JointNames.WRIST_L).shape is Ellipsoid)
        assertTrue(seg(p, JointNames.ANKLE_L).shape is Box)
        assertTrue(zoneProxy(p, "testis.left").shape is Sphere)
        assertTrue(zoneProxy(p, "meatus.mid").shape is Box)
        assertTrue(zoneProxy(p, "shaft.mid").shape is Capsule)
        assertTrue(zoneProxy(p, "glans.mid").shape is Ellipsoid)
    }

    @Test
    fun `左右肢体横偏异号_长度与半径同源`() {
        val (frame, _, p) = build()
        val l = seg(p, JointNames.HIP_L).shape as Capsule
        val r = seg(p, JointNames.HIP_R).shape as Capsule
        assertTrue(l.a.x < 0.0 && r.a.x > 0.0)
        assertEquals(-l.a.x, r.a.x, 1e-12)
        assertEquals(frame.radii.getValue("thigh").value, l.radius, 1e-12)
        assertEquals(frame.cm("thigh"), l.a.y - l.b.y, 1e-9)
        assertEquals(frame.layout.hipY, l.a.y, 1e-9)
    }

    @Test
    fun `足盒尺寸取口径表_踝离地高与前后半长分明`() {
        val (frame, _, p) = build()
        val box = seg(p, JointNames.ANKLE_L).shape as Box
        val r = frame.radii.getValue("foot").value
        val halfZ = r * props.constant("foot_length_factor").default
        assertEquals(frame.cm("foot") / 2.0, box.half.y, 1e-12)
        assertEquals(r, box.half.x, 1e-12)
        assertEquals(halfZ, box.half.z, 1e-12)
        assertEquals(halfZ * (1.0 - 2.0 * props.constant("foot_heel_frac").default), box.center.z, 1e-12)
        assertEquals(0.0, box.lowestY(), 1e-9)
        assertTrue("足长须明显大于足宽", box.half.z > box.half.x)
    }

    @Test
    fun `区代理体是规范形的纯平移_姿态不在此单`() {
        val (_, g, p) = build()
        val root = g.rootCm()
        for (z in g.zones) {
            val proxy = zoneProxy(p, "${z.zoneId}.${z.sideLabel}")
            val center = when (val s = proxy.shape) {
                is Sphere -> s.center
                is Ellipsoid -> s.center
                is Box -> s.center
                is Capsule -> (s.a + s.b) * 0.5
                else -> throw IllegalArgumentException("未知凸体")
            }
            val expect = if (z.proxyKind == "capsule") {
                val (a, b) = g.zoneEnds(z)
                (a + b) * 0.5 + root
            } else {
                z.centerCm + root
            }
            assertEquals("${z.zoneId} 平移错", expect.x, center.x, 1e-9)
            assertEquals("${z.zoneId} 平移错", expect.y, center.y, 1e-9)
            assertEquals("${z.zoneId} 平移错", expect.z, center.z, 1e-9)
        }
    }

    @Test
    fun `代理体高度不超过颅顶_最低点落在足底`() {
        val (frame, _, p) = build()
        val head = seg(p, JointNames.HEAD).shape as Ellipsoid
        assertEquals(frame.layout.topY, head.center.y + head.radii.y, 1e-9)
        val minY = p.segments.minOf { it.shape.lowestY() }
        assertEquals(0.0, minY, 1e-9)
    }

    @Test
    fun `接触参数随代理体一起交付`() {
        val (_, g, p) = build(GenitalInput(turgorGrade = 3))
        assertEquals(g.contact, p.contact)
        assertTrue(p.contact.current in p.contact.restitution.low..p.contact.restitution.high)
    }

    @Test
    fun `同参数两次逐字节一致_与高度场同源`() {
        val (frameA, gA, a) = build(GenitalInput(turgorGrade = 2, curvature = 0.4))
        val (frameB, gB, b) = build(GenitalInput(turgorGrade = 2, curvature = 0.4))
        assertEquals(dump(a), dump(b))
        assertEquals(frameA.segments, frameB.segments)
        assertEquals(gA.zones, gB.zones)
        val spec = FormFixtures.spec(genital = GenitalInput(turgorGrade = 2, curvature = 0.4))
        val f1 = BodyHeightField(frameA, props, spec.parts, gA)
        val f2 = BodyHeightField(frameB, props, spec.parts, gB)
        assertEquals(f1.grid, f2.grid)
        assertEquals(f1.contours, f2.contours)
    }

    @Test
    fun `覆盖值改了代理体就跟着改_不许各算各的`() {
        val base = FormFixtures.spec()
        val wide = base.copy(sections = mapOf("chest_upper" to SectionOverride(widthCm = FormFixtures.override(40.0))))
        val fa = BodyFrame(base, props)
        val fb = BodyFrame(wide, props)
        val ga = GenitalModel(base.genital, base, fa, FactsFromMd, FormFixtures.zones, FormFixtures.anatomy)
        val gb = GenitalModel(wide.genital, wide, fb, FactsFromMd, FormFixtures.zones, FormFixtures.anatomy)
        val ea = (ProxyExport.of(fa, props, ga).segments.first { it.name == JointNames.CHEST }.shape as Ellipsoid).radii.x
        val eb = (ProxyExport.of(fb, props, gb).segments.first { it.name == JointNames.CHEST }.shape as Ellipsoid).radii.x
        assertEquals(16.07, ea, 0.05)
        assertEquals(20.0, eb, 1e-9)
    }

    private fun dump(p: BodyProxies): String =
        p.all.joinToString("\n") { "${it.name}|${it.shape}" } + "\n" +
            p.anchors.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n" + p.contact
}