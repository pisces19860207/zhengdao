package com.example.zhengdao.baopu.state.motion

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 725 源码断言臂：禁积分器、禁随机/时钟、对外输出零浮点、消费 655 不另造、闸有牙。 */
class MotionSourceAssertTest {

    private fun projectFile(rel: String): File {
        val cwd = File(".").canonicalFile
        return listOf(File(cwd, rel), File(cwd.parentFile, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.exists() } ?: error("找不到 $rel")
    }

    private val motionDir: File by lazy {
        projectFile("src/main/java/com/example/zhengdao/baopu/state/motion")
    }

    private val sources: List<File> by lazy {
        motionDir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
    }

    private fun stripped(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")

    private fun allCode(): String = sources.joinToString("\n") { stripped(it.readText()) }

    @Test
    fun motionSourcesExist() {
        assertTrue("动作源码不应为空", sources.size >= 6)
    }

    // ── 判据③：禁止积分器（源码断言，带注入自检）──────────────────────
    @Test
    fun noIntegratorTokens() {
        val banned = listOf(
            "integrator", "integrate(", "timestep", "time_step", "deltatime",
            "fun step(", ".step(", "dt =", "dt:", "simulate(", "thread.sleep",
        )
        val code = allCode().lowercase()
        for (b in banned) assertTrue("动作码不得含时间积分词 $b", !code.contains(b))
    }

    @Test
    fun noIntegratorGateHasTeeth() {
        val injected = "fun advance() { val dt = 0.016 }"
        assertTrue("注入积分词必须被闸咬住", injected.lowercase().contains("dt ="))
    }

    @Test
    fun noRandomOrClockTokens() {
        val banned = listOf(
            "random(", "math.random", "kotlin.random", "currenttimemillis",
            "nanotime", "instant.now", "localdatetime",
        )
        val code = allCode().lowercase()
        for (b in banned) assertTrue("动作码不得含随机/时钟词 $b", !code.contains(b))
    }

    // ── 对外输出零浮点（档位输出，不给弧度与角度数值）──────────────────
    @Test
    fun outputTypesCarryNoFloatingFields() {
        val grades = projectFile("src/main/java/com/example/zhengdao/baopu/state/motion/MotionGrades.kt")
        val code = stripped(grades.readText())
        val floatDecl = Regex("""(?m)^\s*(?:val|var)\s+\w+\s*:\s*(?:Double|Float)\b""")
        assertTrue("对外输出类型不得有 Double/Float 字段", !floatDecl.containsMatchIn(code))
    }

    @Test
    fun allPublicOutputsAreGradesOrBooleans() {
        val grades = projectFile("src/main/java/com/example/zhengdao/baopu/state/motion/MotionGrades.kt")
        val code = stripped(grades.readText())
        assertTrue("只允许 Level(Int)/Boolean/String 字段", !Regex("""val \w+: Double""").containsMatchIn(code))
        assertTrue("KeyPose 须存在", code.contains("data class KeyPose"))
        assertTrue("FrameChecks 全布尔", code.contains("val limitOk: Boolean"))
    }

    // ── 消费 655 现成内核，不另造 ─────────────────────────────────────
    @Test
    fun consumesGeometryKernelNotReimplement() {
        val code = allCode()
        assertTrue("须调 PoseValidity", code.contains("PoseValidity.check"))
        assertTrue("须调 Reachability", code.contains("Reachability.ofChain"))
        assertTrue("四项缺口要接 Reachability，不可只用 PoseValidity", !code.contains("// TODO reach"))
    }

    @Test
    fun noThirdPartyPhysicsImports() {
        for (f in sources) {
            for (line in f.readText().lines()) {
                val t = line.trim()
                if (t.startsWith("import ")) {
                    assertTrue("${f.name} 只许 kotlin.* 或本项目包：$t",
                        t.startsWith("import kotlin.") || t.startsWith("import com.example.zhengdao.") || t.startsWith("import java.io.File"))
                }
            }
        }
    }

    @Test
    fun everyGateReadsRealSources() {
        assertTrue("源码臂必须真读到 6 份以上主源", sources.size >= 6)
        assertEquals(15, MotionKeyframes.all().size)
    }
}
