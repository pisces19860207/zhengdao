package com.example.zhengdao.baopu.state.geometry

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 655 判据的源码断言臂：
 * ① 不引第三方物理库（import 与 gradle 依赖两头扫）；
 * ② 无时间积分（integrator/step 循环类禁词零命中）；
 * ③ 确定性来源（无随机数、无时钟）；
 * ④ 凡结果必带 assumption＋「模拟非测量」（台账口径）。
 */
class GeometrySourceAssertTest {

    private fun projectFile(rel: String): File {
        val cwd = File(".").canonicalFile
        return listOf(File(cwd, rel), File(cwd.parentFile, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.exists() } ?: error("找不到 $rel")
    }

    private val geometryDir: File by lazy {
        projectFile("src/main/java/com/example/zhengdao/baopu/state/geometry")
    }

    private val sources: List<File> by lazy {
        geometryDir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
    }

    /** 剥注释（块注释先于线注释），禁词扫的是真代码不是文档。 */
    private fun stripped(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")

    private fun allCode(): String = sources.joinToString("\n") { stripped(it.readText()) }

    @Test
    fun geometrySourcesExist() {
        assertTrue("几何内核源码不应为空", sources.size >= 8)
    }

    @Test
    fun onlyKotlinStdlibImports() {
        for (f in sources) {
            for (line in f.readText().lines()) {
                val t = line.trim()
                if (t.startsWith("import ")) {
                    assertTrue("${f.name} 只许 import kotlin.*：$t", t.startsWith("import kotlin."))
                }
            }
        }
    }

    @Test
    fun noPhysicsLibraryInGradle() {
        val banned = listOf("jbullet", "dyn4j", "libgdx", "ode4j", "reactphysics", "jmonkeyengine", "bulletphysics", "physics")
        val modules = listOf("", "app/", "core/", "policy/", "native-core/", "macrobenchmark/")
        for (m in modules) {
            val text = projectFile("${m}build.gradle.kts").readText().lowercase()
            for (b in banned) {
                assertTrue("${m}build.gradle.kts 不得引物理库 $b", !text.contains(b))
            }
        }
    }

    @Test
    fun noTimeIntegrationTokens() {
        val banned = listOf(
            "integrator", "integrate(", "timestep", "time_step", "deltatime",
            "fun step(", ".step(", "dt =", "dt:", "simulate(", "thread.sleep",
        )
        val code = allCode().lowercase()
        for (b in banned) {
            assertTrue("几何内核不得含时间积分词 $b", !code.contains(b))
        }
    }

    @Test
    fun noRandomOrClockTokens() {
        val banned = listOf(
            "random(", "math.random", "kotlin.random", "currenttimemillis",
            "nanotime", "instant.now", "localdatetime",
        )
        val code = allCode().lowercase()
        for (b in banned) {
            assertTrue("几何内核不得含随机/时钟词 $b", !code.contains(b))
        }
    }

    @Test
    fun everyResultCarriesAssumptionAndNote() {
        val sphereA = Sphere(Vec3.ZERO, 1.0)
        val sphereB = Sphere(Vec3(2.0, 0.0, 0.0), 2.0)
        val chain = JointChain(listOf(JointSpec("j", 1.0, Vec3.UNIT_Z, JointLimit(-1.0, 1.0))))
        val results = listOf(
            Gjk.distance(sphereA, Sphere(Vec3(5.0, 0.0, 0.0), 1.0)).note to
                Gjk.distance(sphereA, Sphere(Vec3(5.0, 0.0, 0.0), 1.0)).assumption,
            Epa.penetration(sphereA, sphereB)!!.note to Epa.penetration(sphereA, sphereB)!!.assumption,
            chain.pose(listOf(0.0)).note to chain.pose(listOf(0.0)).assumption,
            Reachability.ofChain(chain, 1.0).note to Reachability.ofChain(chain, 1.0).assumption,
            PoseValidity.check(emptyList(), Environment(0.0, 0.0)).note to
                PoseValidity.check(emptyList(), Environment(0.0, 0.0)).assumption,
            TwoBodyCollision.resolve(1.0, 1.0, Vec3.UNIT_X, Vec3.ZERO, Vec3.UNIT_X, 0.5).note to
                TwoBodyCollision.resolve(1.0, 1.0, Vec3.UNIT_X, Vec3.ZERO, Vec3.UNIT_X, 0.5).assumption,
        )
        for ((note, assumption) in results) {
            assertEquals("模拟非测量", note)
            assertTrue(assumption.isNotBlank())
        }
    }
}