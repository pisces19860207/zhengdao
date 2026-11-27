// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百一十五批 · 源码门禁：禁优化迭代＋655 边界版指纹与绝对阈值锁定。
package com.example.zhengdao.baopu.state.geometry

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * 行尾归一后再算 sha256（仓库形态＝LF）。
     *
     * 为什么必须归一（2026-11-25 修 emit #310）：`.gitattributes` 是 `* text=auto`、
     * 本机 `core.autocrlf=true` ⇒ Windows 工作树是 CRLF、索引/CI（Linux）是 LF。
     * 直接对工作树字节取摘要，同一个提交在两平台会得到**两份指纹**：
     * 862 最初钉的三个值是 CRLF 形态，本机绿、CI 红（Gjk 实测 `baf8cfa8…` 就是 LF 形态）。
     * 口径同 285／645／637：**指纹以仓库内形态（LF）为基准**，算之前把 `\r\n` 折成 `\n`。
     */
    private fun sha256Lf(text: String): String {
        val bytes = text.replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Test
    fun 六五五边界版哈希锁定且形体算法未动() {
        val baseline = mapOf(
            "Gjk.kt" to "a9302574d39997f82d0255dc67b311e68d3f4b46649544d4f6f8bc82affcbf31",
            "Epa.kt" to "c805d8acef7e971f09cb4c9e6c3e947c44b71d655ad1160934759bef793092dc",
            "Shapes.kt" to "619c9af044a92c1006668963ee30257079e7aa3ce15405de93357ff30071b7ef",
        )
        for ((name, expected) in baseline) {
            val actual = sha256Lf(sourceFile(name).readText())
            assertEquals("915 锁定文件被改动：$name", expected, actual)
        }
    }

    @Test
    fun EPA绝对体积阈值未动且非严格兜底与空面零值已移除() {
        val code = stripped(sourceFile("Epa.kt").readText())
        assertEquals(1, Regex("var bestVol = 1e-12").findAll(code).count())
        assertFalse(code.contains("pickTetra(candidates, strict = false)"))
        assertFalse(code.contains("faces.indices.minByOrNull { faces[it].dist } ?: 0"))
        assertFalse(code.contains("EpaResult(maxOf(0.0, face.dist)"))
    }

    @Test
    fun 行尾归一同一内容CRLF与LF得同一指纹() {
        assertEquals(sha256Lf("a\nb\n"), sha256Lf("a\r\nb\r\n"))
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
