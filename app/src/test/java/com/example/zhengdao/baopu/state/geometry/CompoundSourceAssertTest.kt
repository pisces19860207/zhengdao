// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百六十二批 · 源码门禁：禁优化迭代（带负例注入）＋655 三文件零 diff 复算。
package com.example.zhengdao.baopu.state.geometry

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompoundSourceAssertTest {

    private fun sourceFile(name: String): File {
        val rel = "src/main/java/com/example/zhengdao/baopu/state/geometry/$name"
        val cwd = File(".").canonicalFile
        val parent = cwd.parentFile ?: cwd
        return listOf(File(cwd, rel), File(parent, rel), File(cwd, "app/$rel"), File(parent, "app/$rel"))
            .firstOrNull { it.exists() } ?: error("找不到几何源文件 $name")
    }

    /** 剥注释（块注释先于行注释），禁词扫的是真代码不是说明。 */
    private fun stripped(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")

    private val newCode: String by lazy { stripped(sourceFile("CompoundProxy.kt").readText()) }

    /** 优化迭代的六类典型落点：收敛循环、目标函数、最小二乘、梯度族、拟牛顿、退火。 */
    private val banned = listOf(
        "while (", "do {", "minimize(", "objective(", "leastSquares", "gradient", "newton", "annealing",
    )

    private fun hits(code: String): List<String> {
        val lower = code.lowercase()
        return banned.filter { lower.contains(it.lowercase()) }
    }

    @Test
    fun 新源码无优化迭代token() {
        assertTrue("复合层不得含优化迭代 token：${hits(newCode)}", hits(newCode).isEmpty())
    }

    @Test
    fun 负例注入每个禁词都必须被咬住() {
        for (token in banned) {
            val injected = "$newCode\n$token"
            assertTrue("禁词注入未被咬：$token", hits(injected).contains(token))
        }
    }

    @Test
    fun 剥注释后注释里的说明词不算命中() {
        val commented = "// gradient leastSquares minimize( annealing\nval x = 1"
        assertTrue("注释里的说明词不该判命中：${hits(stripped(commented))}", hits(stripped(commented)).isEmpty())
    }

    @Test
    fun 距离调用只有Gjk_distance() {
        val calls = Regex("Gjk\\.\\w+").findAll(newCode).map { it.value }.toList()
        assertTrue("复合层必须真的调用 Gjk.distance", calls.size >= 2)
        assertTrue("距离来源只能是 Gjk.distance：$calls", calls.all { it == "Gjk.distance" })
        assertTrue("复合层不许碰 EPA", !newCode.contains("Epa."))
    }

    @Test
    fun 六五五三文件哈希与基线逐字相等() {
        val baseline = mapOf(
            "Gjk.kt" to "f3a94b8de9b953db8ffd0b620189b8b7e2056f33681b7b6dae09b3c3dff6bcd9",
            "Epa.kt" to "e54bd6af67ef9eee301e63093a15d54a22400d8c2d6ff4b63a5b6f0834ab4c73",
            "Shapes.kt" to "2ca479fb37f0d9f5da514ca67a8938c64f381c7eef23824d75798c9106a96358",
        )
        for ((name, expected) in baseline) {
            val digest = MessageDigest.getInstance("SHA-256").digest(sourceFile(name).readBytes())
            val actual = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals("655 算法文件被改动：$name", expected, actual)
        }
    }

    @Test
    fun 源码门禁对改动有牙_变异注入必红() {
        val mutations = listOf(
            "while (true) { }",
            "val f = objective(x)",
            "val g = gradientOf(x)",
            "val h = leastSquares(xs)",
            "val n = newtonStep(x)",
            "val a = annealing(x)",
        )
        for (m in mutations) {
            assertTrue("变异未被咬：$m", hits(newCode + "\n" + m).isNotEmpty())
        }
    }
}
