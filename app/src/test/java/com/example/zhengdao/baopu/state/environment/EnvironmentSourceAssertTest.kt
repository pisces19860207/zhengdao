package com.example.zhengdao.baopu.state.environment

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 657 判据的源码断言臂（仿 655 GeometrySourceAssertTest）：
 * ① 零新依赖：生产源码只许 import kotlin.*；
 * ② 无时间积分（integrator/step 循环类禁词零命中）；
 * ③ 确定性来源（无随机数、无时钟）；
 * ④ 凡结果必带 assumption＋「模拟结果，非测量」（字面量钉死，不拿被测常量比常量）。
 */
class EnvironmentSourceAssertTest {

    private fun projectFile(rel: String): File {
        val cwd = File(".").canonicalFile
        return listOf(File(cwd, rel), File(cwd.parentFile, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.exists() } ?: error("找不到 $rel")
    }

    private val environmentDir: File by lazy {
        projectFile("src/main/java/com/example/zhengdao/baopu/state/environment")
    }

    private val sources: List<File> by lazy {
        environmentDir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
    }

    /** 剥注释（块注释先于线注释），禁词扫的是真代码不是文档。 */
    private fun stripped(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")

    private fun allCode(): String = sources.joinToString("\n") { stripped(it.readText()) }

    @Test
    fun environmentSourcesExist() {
        assertTrue("环境求解器源码不应为空", sources.size >= 6)
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
    fun noTimeIntegrationTokens() {
        val banned = listOf(
            "integrator", "integrate(", "timestep", "time_step", "deltatime",
            "fun step(", ".step(", "dt =", "dt:", "simulate(", "thread.sleep",
        )
        val code = allCode().lowercase()
        for (b in banned) {
            assertTrue("环境求解器不得含时间积分词 $b", !code.contains(b))
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
            assertTrue("环境求解器不得含随机/时钟词 $b", !code.contains(b))
        }
    }

    @Test
    fun everyResultCarriesAssumptionAndNote() {
        val chain = listOf(
            SolarSolver.solve(18.0, 172, 12.0).note to SolarSolver.solve(18.0, 172, 12.0).assumption,
            SolarSolver.sunsetOf(18.0, 172).note to SolarSolver.sunsetOf(18.0, 172).assumption,
            SolarSolver.toTrueSolarTime(12.0, 120.0, 8.0, 172).note to
                SolarSolver.toTrueSolarTime(12.0, 120.0, 8.0, 172).assumption,
            WindTable.of(3, 90.0).note to WindTable.of(3, 90.0).assumption,
            HumiditySolver.humidityOf(30.0, 60.0, 2.0).note to HumiditySolver.humidityOf(30.0, 60.0, 2.0).assumption,
            HumiditySolver.sweatOf(2, 30.0, 60.0, 1.0, 1).note to HumiditySolver.sweatOf(2, 30.0, 60.0, 1.0, 1).assumption,
            HumiditySolver.sandMoistureOf(1).note to HumiditySolver.sandMoistureOf(1).assumption,
            EnvironmentSolver.solve(18.0, 172, 12.0, 3, 90.0, 30.0, 60.0, 1, 0).note to
                EnvironmentSolver.solve(18.0, 172, 12.0, 3, 90.0, 30.0, 60.0, 1, 0).assumption,
        )
        for ((note, assumption) in chain) {
            assertEquals("模拟结果，非测量", note)
            assertTrue(assumption.isNotBlank())
        }
    }
}