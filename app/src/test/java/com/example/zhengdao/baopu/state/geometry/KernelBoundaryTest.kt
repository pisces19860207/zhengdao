package com.example.zhengdao.baopu.state.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class KernelBoundaryTest {

    @Test
    fun 已知入口带近贴_不抛且显式退化深度零() {
        val a = Sphere(Vec3.ZERO, 50.0)
        val centerX = 100.0 + 5e-8
        val b = Sphere(Vec3(centerX, 0.0, 0.0), 50.0)
        val gjk = Gjk.distance(a, b)
        val epa = Epa.penetration(a, b)
        val trace = Epa.initialTetraTrace(a, b)
        val representableGap = centerX - 100.0

        println("KNOWN_915 gjk=${gjk.status} epa=${epa?.status} depth=${epa?.depthCm} tetra=${trace.source} reason=${epa?.reason}")
        assertFalse(gjk.intersecting)
        assertEquals(representableGap, gjk.distanceCm, 0.0)
        assertTrue(abs(gjk.distanceCm - 5e-8) <= Math.ulp(100.0))
        assertNotNull(epa)
        assertEquals(KernelStatus.Degenerate, epa!!.status)
        assertEquals(0.0, epa.depthCm, 0.0)
        assertTrue(epa.reason?.isNotBlank() == true)
        assertEquals(InitialTetraSource.STRICT_SCAN, trace.source)
        assertEquals(4, trace.vertices.size)
        assertEquals("分离近贴无穿透深度", epa.reason)
    }

    @Test
    fun 退化线段与球分离_EPA仍显式保留退化状态() {
        val line = ConvexHull(listOf(Vec3(-1.0, 0.0, 0.0), Vec3(1.0, 0.0, 0.0)))
        val ball = Sphere(Vec3(0.0, 5.0, 0.0), 1.0)
        val gjk = Gjk.distance(line, ball)
        val epa = Epa.penetration(line, ball)

        assertEquals(KernelStatus.Degenerate, gjk.status)
        assertNotNull(epa)
        assertEquals(KernelStatus.Degenerate, epa!!.status)
        assertEquals(0.0, epa.depthCm, 0.0)
        assertEquals(gjk.reason, epa.reason)
    }

    @Test
    fun 点入盒_退化标记不提前掐断既有有限深度() {
        val point = ConvexHull(listOf(Vec3(0.2, 0.1, 0.0)))
        val box = Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0))
        val gjk = Gjk.distance(point, box)
        val epa = Epa.penetration(point, box)

        assertEquals(KernelStatus.Degenerate, gjk.status)
        assertNotNull(epa)
        assertEquals(KernelStatus.Degenerate, epa!!.status)
        assertEquals(5, epa.iterations)
        assertEquals(0.8, epa.depthCm, 1e-15)
    }

    @Test
    fun 自定义支撑抛异常_GJK与EPA都显式Unsupported() {
        val throwing = object : ConvexShape {
            override fun support(dir: Vec3): Vec3 = error("fixture boom")
        }
        val ball = Sphere(Vec3.ZERO, 1.0)
        val gjk = Gjk.distance(throwing, ball)
        val epa = Epa.penetration(throwing, ball)

        assertEquals(KernelStatus.Unsupported, gjk.status)
        assertTrue(gjk.distanceCm.isInfinite())
        assertTrue(gjk.reason?.isNotBlank() == true)
        assertNotNull(epa)
        assertEquals(KernelStatus.Unsupported, epa!!.status)
        assertTrue(epa.depthCm.isNaN())
        assertTrue(epa.reason?.isNotBlank() == true)
    }

    @Test
    fun 非有限形体与非法旋转_显式Unsupported不产零距离() {
        val cases = listOf(
            Sphere(Vec3(Double.NaN, 0.0, 0.0), 1.0),
            Sphere(Vec3.ZERO, Double.POSITIVE_INFINITY),
            Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0), Rot3(Vec3.UNIT_X, Vec3.UNIT_X, Vec3.UNIT_Z)),
        )
        for (shape in cases) {
            val result = Gjk.distance(shape, Sphere(Vec3.ZERO, 1.0))
            assertEquals(KernelStatus.Unsupported, result.status)
            assertTrue(result.distanceCm.isInfinite())
            assertFalse(result.intersecting)
        }
    }

    @Test
    fun 尺度比百万档_显式Unsupported且EPA不伪造深度() {
        val a = Sphere(Vec3.ZERO, 1e3)
        val far = Vec3(1e16, 0.0, 0.0)
        val smallShapes = listOf(
            Sphere(Vec3(1.0, 0.0, 0.0), 1e-3),
            Capsule(far, far, 1e-3),
            Ellipsoid(far, Vec3(1e-3, 1e-3, 1e-3)),
            Box(far, Vec3(1e-3, 1e-3, 1e-3)),
        )
        for (b in smallShapes) {
            val gjk = Gjk.distance(a, b)
            val epa = Epa.penetration(a, b)

            assertEquals("${b.javaClass.simpleName} 不应靠世界坐标相消绕过尺度门禁", KernelStatus.Unsupported, gjk.status)
            assertTrue(gjk.distanceCm.isInfinite())
            assertNotNull(epa)
            assertEquals(KernelStatus.Unsupported, epa!!.status)
            assertTrue(epa.depthCm.isNaN())
        }
    }

    @Test
    fun 复合刚体球尺度按声明半径_大坐标相消不逃过比例拒绝() {
        val proxy = CompoundProxy(
            "offset-scale",
            listOf(
                ProxyChunk(
                    "a", Sphere(Vec3.ZERO, 1e-3),
                    LocalTransform(Vec3(1e16, 0.0, 0.0), Rot3.IDENTITY),
                ),
                ProxyChunk(
                    "b", Sphere(Vec3.ZERO, 1e-3),
                    LocalTransform(Vec3(-1e16, 0.0, 0.0), Rot3.IDENTITY),
                ),
            ),
        )
        val other = Sphere(Vec3.ZERO, 1e3)
        val hit = proxy.anyHit(other)
        val distance = proxy.minDistance(other)

        assertEquals(KernelStatus.Unsupported, hit.status)
        assertFalse(hit.intersecting)
        assertEquals(KernelStatus.Unsupported, distance.status)
        assertTrue(distance.distanceCm.isInfinite())
        assertTrue(distance.reason?.contains("1e6") == true)
    }

    @Test
    fun 零长正半径胶囊仍按球处理_零半径才退化() {
        val sphereLike = Capsule(Vec3.ZERO, Vec3.ZERO, 1.0)
        val other = Capsule(Vec3(2.5, 0.0, 0.0), Vec3(2.5, 0.0, 0.0), 1.0)
        val valid = Gjk.distance(sphereLike, other)
        val line = Gjk.distance(Capsule(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), 0.0), other)

        assertEquals(KernelStatus.Success, valid.status)
        assertEquals(0.5, valid.distanceCm, 1e-12)
        assertEquals(KernelStatus.Degenerate, line.status)
        assertTrue(line.distanceCm.isFinite())
    }

    @Test
    fun 复合非法刚体变换_查询显式Unsupported不抛() {
        val badRotation = Rot3(Vec3.UNIT_X, Vec3.UNIT_X, Vec3.UNIT_Z)
        val proxy = CompoundProxy(
            "bad-transform",
            listOf(
                ProxyChunk("a", Sphere(Vec3.ZERO, 1.0), LocalTransform(Vec3.ZERO, badRotation)),
                ProxyChunk("b", Sphere(Vec3(2.0, 0.0, 0.0), 1.0), LocalTransform.IDENTITY),
            ),
        )
        val other = Sphere(Vec3.ZERO, 1.0)
        val hit = proxy.anyHit(other)
        val distance = proxy.minDistance(other)
        val pair = requireNotNull(
            CompoundPairs.read(
                SegmentProxy("p", proxy.chunks.first().shape),
                SegmentProxy("o", other),
                mapOf("p" to proxy),
            ),
        )

        assertEquals(KernelStatus.Unsupported, hit.status)
        assertFalse(hit.intersecting)
        assertEquals(KernelStatus.Unsupported, distance.status)
        assertTrue(distance.distanceCm.isInfinite())
        assertEquals(KernelStatus.Unsupported, pair.status)
        assertTrue(pair.depthCm.isNaN())
    }

    @Test
    fun 复合底层线段退化_刚体包装后仍显式Degenerate() {
        val proxy = CompoundProxy(
            "line",
            listOf(
                ProxyChunk("a", Capsule(Vec3.ZERO, Vec3.UNIT_X, 0.0), LocalTransform.IDENTITY),
                ProxyChunk("b", Sphere(Vec3(2.0, 0.0, 0.0), 1.0), LocalTransform.IDENTITY),
            ),
        )
        val other = Sphere(Vec3(0.5, 2.0, 0.0), 1.0)
        assertEquals(KernelStatus.Degenerate, proxy.anyHit(other).status)
        assertEquals(KernelStatus.Degenerate, proxy.minDistance(other).status)
    }

    @Test
    fun 关节链无效根位姿与近零轴_显式Unsupported() {
        val joint = JointSpec("j", 1.0, Vec3.UNIT_Z, JointLimit(-PI, PI))
        val badRoot = JointChain(listOf(joint), rootRot = Rot3(Vec3.UNIT_X, Vec3.UNIT_X, Vec3.UNIT_Z))
        val nearZeroAxis = JointChain(
            listOf(JointSpec("j", 1.0, Vec3(1e-13, 0.0, 0.0), JointLimit(-PI, PI))),
        )

        val infiniteLimit = JointChain(
            listOf(JointSpec("j", 1.0, Vec3.UNIT_Z, JointLimit(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY))),
        )

        assertEquals(KernelStatus.Unsupported, badRoot.pose(listOf(0.0)).status)
        val pose = nearZeroAxis.pose(listOf(PI / 2.0))
        assertEquals(KernelStatus.Unsupported, pose.status)
        assertTrue(pose.reason?.isNotBlank() == true)
        assertEquals(KernelStatus.Unsupported, infiniteLimit.pose(listOf(0.0)).status)
        assertNull(Rot3.fromAxisAngle(Vec3(1e-13, 0.0, 0.0), PI / 2.0))
    }
}
