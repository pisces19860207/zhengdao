package com.example.zhengdao.baopu.state.geometry

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test
class KernelNormalBitsTest {

    private val pairs = listOf(
        "GjkTest.sphereSphereMatchesAnalytic" to pair(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(5.0, 0.0, 0.0), 2.0)),
        "GjkTest.boxBoxMatchesAnalytic" to pair(Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0)), Box(Vec3(4.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0))),
        "GjkTest.sphereBoxMatchesAnalytic" to pair(Sphere(Vec3(4.0, 0.0, 0.0), 1.0), Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0))),
        "GjkTest.capsuleCapsuleMatchesAnalytic" to pair(
            Capsule(Vec3.ZERO, Vec3(2.0, 0.0, 0.0), 0.5),
            Capsule(Vec3(0.0, 3.0, 0.0), Vec3(2.0, 3.0, 0.0), 0.5),
        ),
        "GjkTest.rotatedBoxMatchesAnalytic" to pair(
            Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0), checkNotNull(Rot3.fromAxisAngle(Vec3.UNIT_Z, Math.PI / 4.0))),
            Sphere(Vec3(5.0, 0.0, 0.0), 1.0),
        ),
        "GjkTest.ellipsoidSphereMatchesAnalytic" to pair(Ellipsoid(Vec3.ZERO, Vec3(2.0, 1.0, 1.0)), Sphere(Vec3(6.0, 0.0, 0.0), 1.0)),
        "GjkTest.overlappingSpheresReportZero" to pair(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(2.0, 0.0, 0.0), 2.0)),
        "GjkTest.witnessesSpanTheGap" to pair(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(5.0, 0.0, 0.0), 2.0)),
        "GjkTest.distanceIsSymmetric" to pair(
            Box(Vec3.ZERO, Vec3(1.0, 2.0, 0.5)),
            Capsule(Vec3(4.0, 1.0, 0.0), Vec3(5.0, 2.0, 1.0), 0.7),
        ),
        "GjkTest.sameInputTwiceIdentical" to pair(
            Sphere(Vec3(0.3, -0.7, 1.2), 1.1),
            Box(Vec3(4.2, 0.0, -1.0), Vec3(1.0, 1.0, 1.0)),
        ),
        "GjkTest.resultCarriesAssumptionAndNote" to pair(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(5.0, 0.0, 0.0), 1.0)),
        "EpaTest.sphereSphereDepthMatchesAnalytic" to pair(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(2.9, 0.0, 0.0), 2.0)),
        "EpaTest.boxBoxDepthMatchesAnalytic" to pair(Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0)), Box(Vec3(1.5, 0.0, 0.0), Vec3(1.0, 1.0, 1.0))),
        "EpaTest.boxBoxPicksMinOverlapAxis" to pair(Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0)), Box(Vec3(1.5, 1.9, 1.95), Vec3(1.0, 1.0, 1.0))),
        "EpaTest.sphereBoxDepthMatchesAnalytic" to pair(Sphere(Vec3(0.5, 0.0, 0.0), 1.0), Box(Vec3.ZERO, Vec3(1.0, 1.0, 1.0))),
        "EpaTest.capsuleCapsuleDepthMatchesAnalytic" to pair(
            Capsule(Vec3.ZERO, Vec3(2.0, 0.0, 0.0), 0.5),
            Capsule(Vec3(0.0, 0.6, 0.0), Vec3(2.0, 0.6, 0.0), 0.5),
        ),
        "EpaTest.separatedReturnsNull" to pair(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(5.0, 0.0, 0.0), 2.0)),
        "EpaTest.depthMonotoneInOverlap" to pair(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(2.9, 0.0, 0.0), 2.0)),
        "EpaTest.normalPointsFromAToB" to pair(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(2.0, 0.0, 0.0), 2.0)),
        "EpaTest.movingAAlongMinusNormalSeparates" to pair(Sphere(Vec3.ZERO, 1.0), Sphere(Vec3(2.0, 0.0, 0.0), 2.0)),
    )

    @Test
    fun 正常输入二十例_改前改后原始位一致() {
        val actual = pairs.joinToString("\n") { (name, pair) ->
            val gjk = Gjk.distance(pair.first, pair.second)
            val epa = Epa.penetration(pair.first, pair.second)
            "$name|GJK|${bits(gjk.distanceCm)}|${vec(gjk.witnessA)}|${vec(gjk.witnessB)}|${gjk.iterations}|${gjk.intersecting}|" +
                if (epa == null) "EPA|null" else "EPA|${bits(epa.depthCm)}|${vec(epa.normal)}|${epa.iterations}"
        }
        assertEquals("正常输入数值/见证点/法向/迭代次数必须逐位不变", BASELINE, actual)
        assertEquals("正常输入原始位摘要必须与基线一致", NORMAL_BITS_SHA256, sha256(actual))
        for ((name, pair) in pairs) {
            assertEquals("$name GJK 正常输入不得误标退化", KernelStatus.Success, Gjk.distance(pair.first, pair.second).status)
            Epa.penetration(pair.first, pair.second)?.let {
                assertEquals("$name EPA 正常输入不得误标退化", KernelStatus.Success, it.status)
            }
        }
        println("NORMAL_BITS_MATCH cases=${pairs.size}")
        println("EXISTING_FIXTURE_MATCH_915 cases=${pairs.size} strings=$NORMAL_STRINGS_SHA256 bits=${sha256(actual)}")
    }

    @Test
    fun 正常输入二十例_完整toString逐字不变() {
        val actual = pairs.joinToString("\n") { (name, pair) ->
            val gjk = Gjk.distance(pair.first, pair.second)
            val epa = Epa.penetration(pair.first, pair.second)
            "$name|GJK|$gjk|EPA|${epa ?: "null"}"
        }
        val hash = sha256(actual)
        println("NORMAL_TOSTRING_915 old=$NORMAL_STRINGS_SHA256 new=$hash bytes=${actual.toByteArray(Charsets.UTF_8).size}")
        println(actual)
        assertEquals("正常输入完整 toString 必须逐字不变", NORMAL_STRINGS_SHA256, hash)
    }

    @Test
    fun 小球重叠_严格扫描裕度保持旧深度与迭代次数() {
        val radius = 0.0014440962907508583
        val a = Sphere(Vec3.ZERO, radius)
        val b = Sphere(
            Vec3(0.0024723907245537196, -0.00013967782197494866, -0.0002026173089783328),
            radius,
        )
        val gjk = Gjk.distance(a, b)
        val epa = requireNotNull(Epa.penetration(a, b))

        assertEquals(KernelStatus.Success, epa.status)
        assertEquals(0.00040358361714357247, epa.depthCm, 0.0)
        assertEquals(37, epa.iterations)
        assertEquals(SMALL_STRINGS_SHA256, sha256("GJK|$gjk|EPA|$epa"))
    }

    @Test
    fun 各向异性极扁盒_绝对体积门槛有牙() {
        val thickness = 1e-9
        val a = Box(Vec3.ZERO, Vec3(1.0, 1.0, thickness / 2.0))
        val b = Box(Vec3(0.0, 0.0, thickness / 2.0), Vec3(1.0, 1.0, thickness / 2.0))
        val epa = requireNotNull(Epa.penetration(a, b))
        val trace = Epa.initialTetraTrace(a, b)

        assertEquals(KernelStatus.Success, epa.status)
        assertEquals(0x3e012e0be826d695L, epa.depthCm.toRawBits())
        assertEquals(InitialTetraSource.STRICT_SCAN, trace.source)
        assertEquals(4, trace.vertices.size)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun pair(a: ConvexShape, b: ConvexShape) = a to b

    private fun bits(value: Double): String = value.toRawBits().toString(16).padStart(16, '0')

    private fun vec(value: Vec3): String = listOf(bits(value.x), bits(value.y), bits(value.z)).joinToString("|")

    private companion object {
        const val NORMAL_STRINGS_SHA256 = "11c74ad7be72511e34a01c294a348b5784067c98023363a7046d743d0f09efe4"
        const val NORMAL_BITS_SHA256 = "5f775f1fc9b34b2f970f426c5c76e464b7dc585ec39b0c933fc5b05637e93d3d"
        const val SMALL_STRINGS_SHA256 = "81f2c89ce35998cbe6b0a73dc986c937e3d38d746299212f35001c4d86a4486b"

        val BASELINE: String = listOf(
            "GjkTest.sphereSphereMatchesAnalytic|GJK|4000000000000000|3ff0000000000000|0000000000000000|0000000000000000|4008000000000000|0000000000000000|0000000000000000|1|false|EPA|null",
            "GjkTest.boxBoxMatchesAnalytic|GJK|4000000000000000|3ff0000000000000|3ff0000000000000|3ff0000000000000|4008000000000000|3ff0000000000000|3ff0000000000000|1|false|EPA|null",
            "GjkTest.sphereBoxMatchesAnalytic|GJK|4000000000000001|4008000000000001|-41b12d70a64f4511|-41b12d70a64f4511|3ff0000000000000|-41b12d7098000000|-41b12d7098000000|9|false|EPA|null",
            "GjkTest.capsuleCapsuleMatchesAnalytic|GJK|400000000000001c|3fefffffffffffc9|3fdfffffffffff90|0000000000000000|3ff000000000001c|400400000000000e|0000000000000000|8|false|EPA|null",
            "GjkTest.rotatedBoxMatchesAnalytic|GJK|4004afb0ccc0621a|3ff6a09e667f3bcc|-4360000000000000|3e3589d800000000|4010000000000000|-438b427e98e09b39|3e3589d8011c8077|5|false|EPA|null",
            "GjkTest.ellipsoidSphereMatchesAnalytic|GJK|4008000000000000|4000000000000000|0000000000000000|0000000000000000|4014000000000000|0000000000000000|0000000000000000|1|false|EPA|null",
            "GjkTest.overlappingSpheresReportZero|GJK|0000000000000000|3fe5555555555556|0000000000000000|0000000000000000|3fe5555555555555|0000000000000000|0000000000000000|2|true|EPA|3feffffffa89b451|3feffffffa89b451|-40f0f26bcd4cf07c|-40defdb2eea1ef80|96",
            "GjkTest.witnessesSpanTheGap|GJK|4000000000000000|3ff0000000000000|0000000000000000|0000000000000000|4008000000000000|0000000000000000|0000000000000000|1|false|EPA|null",
            "GjkTest.distanceIsSymmetric|GJK|4002666666666666|3ff0000000000000|3ff0000000007598|3d7fd12000000000|400a666666666665|3ff0000000007599|3d7fd1342c83c85a|9|false|EPA|null",
            "GjkTest.sameInputTwiceIdentical|GJK|40004ec9dd8e5760|3ff5100c6bb3f5c2|-40199999999a48fc|3fe8f0f175cb44f3|400999999999999a|-40199999999a8b6a|0000000000000000|25|false|EPA|null",
            "GjkTest.resultCarriesAssumptionAndNote|GJK|4008000000000000|3ff0000000000000|0000000000000000|0000000000000000|4010000000000000|0000000000000000|0000000000000000|1|false|EPA|null",
            "EpaTest.sphereSphereDepthMatchesAnalytic|GJK|0000000000000000|3feeeeeeeeeeeeee|0000000000000000|0000000000000000|3feeeeeeeeeeeeef|0000000000000000|0000000000000000|2|true|EPA|3fb99999953af6ae|3feffffffa89b452|-40f0f26bd2cdab87|-40defdb2ed701bc4|96",
            "EpaTest.boxBoxDepthMatchesAnalytic|GJK|0000000000000000|3fe8000000000000|3ff0000000000000|3ff0000000000000|3fe8000000000000|3ff0000000000000|3ff0000000000000|2|true|EPA|3fe0000000000000|3ff0000000000000|0000000000000000|0000000000000000|3",
            "EpaTest.boxBoxPicksMinOverlapAxis|GJK|0000000000000000|3fe8000000000002|3fef333333333330|3ff0000000000000|3fe7fffffffffffe|3fef333333333326|3feffffffffffff0|4|true|EPA|3fa99999999999a0|-8000000000000000|-8000000000000000|3ff0000000000000|2",
            "EpaTest.sphereBoxDepthMatchesAnalytic|GJK|0000000000000000|3fd40a5cf87bb096|3faff78237767c58|3faff78237767c58|3fd40a5cf87bb090|3faff78237767c50|3faff78237767c50|3|true|EPA|3ff7fffffffe048d|-4010000000000000|-422c87304cabb63d|3ddb669a8dd427fb|15",
            "EpaTest.capsuleCapsuleDepthMatchesAnalytic|GJK|0000000000000000|3ff0000000000000|3fd3333333333331|0000000000000000|3fefffffffffffff|3fd3333333333336|0000000000000000|3|true|EPA|3fd999999962ff4a|3d42bd3f06d5aa3d|3fefffffffc9c4f5|3efd74e27dab7f88|34",
            "EpaTest.separatedReturnsNull|GJK|4000000000000000|3ff0000000000000|0000000000000000|0000000000000000|4008000000000000|0000000000000000|0000000000000000|1|false|EPA|null",
            "EpaTest.depthMonotoneInOverlap|GJK|0000000000000000|3feeeeeeeeeeeeee|0000000000000000|0000000000000000|3feeeeeeeeeeeeef|0000000000000000|0000000000000000|2|true|EPA|3fb99999953af6ae|3feffffffa89b452|-40f0f26bd2cdab87|-40defdb2ed701bc4|96",
            "EpaTest.normalPointsFromAToB|GJK|0000000000000000|3fe5555555555556|0000000000000000|0000000000000000|3fe5555555555555|0000000000000000|0000000000000000|2|true|EPA|3feffffffa89b451|3feffffffa89b451|-40f0f26bcd4cf07c|-40defdb2eea1ef80|96",
            "EpaTest.movingAAlongMinusNormalSeparates|GJK|0000000000000000|3fe5555555555556|0000000000000000|0000000000000000|3fe5555555555555|0000000000000000|0000000000000000|2|true|EPA|3feffffffa89b451|3feffffffa89b451|-40f0f26bcd4cf07c|-40defdb2eea1ef80|96",
        ).joinToString("\n")
    }
}
