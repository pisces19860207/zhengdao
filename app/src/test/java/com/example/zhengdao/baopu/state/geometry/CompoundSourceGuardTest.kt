// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百一十五批 · 源码门禁：边界状态先于数值、复合指纹锁定、凹深档只接收。
package com.example.zhengdao.baopu.state.geometry

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompoundSourceGuardTest {

    @Test
    fun 旧PoseValidity位置参数签名仍可直接调用() {
        val verdict = PoseValidity.check(
            listOf(SegmentProxy("a", Sphere(Vec3(0.0, 5.0, 0.0), 1.0))),
            Environment(0.0, 0.0), null, emptySet(), 0.0,
        )
        assertTrue(verdict.valid)
    }

    @Test
    fun 旧TwoBodyCollision位置参数签名仍可直接调用() {
        val result = TwoBodyCollision.resolve(1.0, 1.0, Vec3.UNIT_X, -Vec3.UNIT_X, Vec3.UNIT_X, 1.0)
        assertTrue(result.collided)
        var calls = 0
        val separated = TwoBodyCollision.resolve(1.0, 1.0, Vec3.UNIT_X, Vec3.UNIT_X, Vec3.UNIT_X, 1.0) {
            calls++
            false
        }
        assertFalse(separated.collided)
        assertEquals("候选回调须在既有提前退出之前", 1, calls)
    }

    @Test
    fun PoseValidity保留单次查询并在读数值前检查边界状态() {
        val code = stripped(source("PoseValidity.kt"))
        assertTrue(code.contains("compounds: Map<String, CompoundProxy> = emptyMap()"))
        assertEquals(1, count(code, "CompoundPairs.read(a, b, compounds)"))
        assertEquals(1, count(code, "Gjk.distance(a.shape, b.shape)"))
        assertEquals(1, count(code, "Epa.penetration(a.shape, b.shape)"))
        assertTrue(code.contains("reading.status == KernelStatus.Unsupported"))
        assertTrue(code.contains("gjk.status == KernelStatus.Unsupported"))
        assertTrue(code.contains("epa.status == KernelStatus.Unsupported"))
        assertFalse(code.contains("Gjk.distance(a.shape, b.shape).distanceCm"))
        assertFalse(code.contains("Epa.penetration(a.shape, b.shape)?.depthCm ?: 0.0"))
        val pairs = stripped(source("CompoundPairs.kt"))
        assertEquals(1, Regex("\\.anyHit\\(").findAll(pairs).count())
        assertEquals(1, Regex("\\.minDistance\\(").findAll(pairs).count())
    }

    @Test
    fun ProxyLegality注入点留在cavity真实包且不反向依赖calibration() {
        val raw = source("ProxyLegality.kt", "state/cavity")
        val code = stripped(raw)
        assertTrue(raw.startsWith("// 独立开发声明"))
        assertTrue(code.contains("package com.example.zhengdao.baopu.state.cavity"))
        assertFalse(code.contains("import com.example.zhengdao.baopu.calibration"))
        assertEquals(1, count(code, "CompoundWiring.compoundsFor(proxies.map { it.id })"))
        assertEquals(1, count(code, "compounds = compounds"))
    }

    @Test
    fun TwoBodyCollision只加默认空候选且不冒充生产触发() {
        val code = stripped(source("TwoBodyCollision.kt"))
        assertTrue(code.contains("candidate: (() -> Boolean)? = null"))
        assertEquals(1, count(code, "candidate?.invoke() == false"))
        assertTrue(code.indexOf("candidate?.invoke() == false") < code.indexOf("if (u1 - u2 <= 0.0)"))
    }

    @Test
    fun 生产安装默认关闭且校准provider缝可重置() {
        val runtime = stripped(source("BaopuRuntime.kt", ""))
        val marker = "com.example.zhengdao.baopu.state.cavity.CompoundWiring.install("
        val start = runtime.indexOf(marker)
        assertTrue(start >= 0)
        val end = runtime.indexOf("\n        )", start)
        assertTrue(end > start)
        val install = runtime.substring(start, end) + "\n        )"
        assertTrue(install.contains("enabled = false"))
        assertTrue(install.contains("CalibrationWiring.compoundProvider?.invoke().orEmpty()"))
        assertTrue(install.contains("trace = { RunLog.log(it) }"))
        val calibration = stripped(source("CalibrationWiring.kt", "calibration"))
        assertTrue(calibration.contains("var compoundProvider: (() -> List<CompoundRegistration>)? = null"))
        assertTrue(calibration.contains("compoundProvider = null"))
        assertTrue(calibration.contains("CompoundWiring.detach()"))
    }

    @Test
    fun 九一五复合代理边界版整文件指纹锁定() {
        val expected = "036d1537482d6ad86ac6fb32b0f9b09d623943e54c1fd99c14a34ff19d5e54c7"
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(source("CompoundProxy.kt").replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("915 边界状态 propagation 与既有分解算法都不许再漂", expected, actual)
    }

    @Test
    fun 新装配源码无凹深档名字面量且不构造PartSpec() {
        val scanned = scan(source("CompoundPairs.kt") + "\n" + source("CompoundWiring.kt", "state/cavity"))
        val code = scanned.code
        val commented = "// /*\nPartSpec(\"凹\")\n// */"
        assertTrue(stripped(commented).contains("PartSpec("))
        assertTrue(stripped(commented).contains("凹"))
        assertEquals(" val x = 1", stripped("/* outer /* inner */ still */ val x = 1"))
        val quoted = "val s = \"// /* keep */\""
        assertEquals(quoted, stripped(quoted))
        val raw = "val s = \"\"\" \" /*\nkeep\n\"\"\"\nval x = 1"
        assertEquals(raw, stripped(raw))
        assertEquals(listOf(" \" /*\nkeep\n"), scan(raw).literals)
        val escaped = "val s = \"a \\\" /* keep */\"\nval x = 1"
        assertEquals(escaped, stripped(escaped))
        assertEquals(listOf("a \\\" /* keep */"), scan(escaped).literals)
        val template = "val s = \"\"\"\${PartSpec(\"凹\")}\"\"\""
        assertEquals(template, stripped(template))
        assertEquals(1, scan(template).literals.count { it.contains("凹") })
        val bad = scanned.literals.filter { it.contains("凹") || it.contains("档") }
        assertEquals("凹深档 0–2 只接收不自造：$bad", emptyList<String>(), bad)
        assertFalse(code.contains("NotchTier"))
        assertFalse(code.contains("PartSpec("))
    }

    private data class ScanResult(val code: String, val literals: List<String>)

    private class SourceScanner(private val text: String) {
        private val code = StringBuilder(text.length)
        private val literals = mutableListOf<String>()
        private var index = 0

        fun scan(): ScanResult {
            scanCode(inTemplate = false)
            return ScanResult(code.toString(), literals)
        }

        private fun scanCode(inTemplate: Boolean) {
            var braceDepth = 0
            while (index < text.length) {
                val char = text[index]
                when {
                    inTemplate && braceDepth == 0 && char == '}' -> {
                        code.append(char)
                        index++
                        return
                    }
                    startsWith("//") -> skipLine()
                    startsWith("/*") -> {
                        index += 2
                        skipBlock()
                    }
                    startsWith("\"\"\"") -> scanString(raw = true)
                    char == '"' -> scanString(raw = false)
                    char == '\'' -> scanChar()
                    inTemplate && char == '{' -> {
                        code.append(char)
                        index++
                        braceDepth++
                    }
                    inTemplate && char == '}' -> {
                        code.append(char)
                        index++
                        braceDepth--
                    }
                    else -> {
                        code.append(char)
                        index++
                    }
                }
            }
        }

        private fun scanString(raw: Boolean) {
            val delimiter = if (raw) "\"\"\"" else "\""
            val literal = StringBuilder()
            code.append(delimiter)
            index += delimiter.length
            while (index < text.length) {
                if (startsWith(delimiter)) {
                    code.append(delimiter)
                    index += delimiter.length
                    literals += literal.toString()
                    return
                }
                val char = text[index++]
                when {
                    char == '$' && startsWith("{") -> {
                        code.append("\${")
                        index++
                        scanCode(inTemplate = true)
                    }
                    !raw && char == '\\' -> {
                        appendLiteral(char, literal)
                        text.getOrNull(index)?.let { escaped ->
                            appendLiteral(escaped, literal)
                            index++
                        }
                    }
                    else -> appendLiteral(char, literal)
                }
            }
            literals += literal.toString()
        }

        private fun scanChar() {
            val literal = StringBuilder()
            code.append('\'')
            index++
            while (index < text.length) {
                val char = text[index++]
                appendLiteral(char, literal)
                when {
                    char == '\\' -> text.getOrNull(index)?.let { escaped ->
                        appendLiteral(escaped, literal)
                        index++
                    }
                    char == '\'' -> {
                        literals += literal.dropLast(1).toString()
                        return
                    }
                }
            }
            literals += literal.toString()
        }

        private fun appendLiteral(char: Char, literal: StringBuilder) {
            code.append(char)
            literal.append(char)
        }

        private fun startsWith(token: String): Boolean = text.startsWith(token, index)

        private fun skipLine() {
            index += 2
            while (index < text.length && text[index] != '\n') index++
        }

        private fun skipBlock() {
            var depth = 1
            while (index < text.length && depth > 0) {
                when {
                    startsWith("/*") -> {
                        depth++
                        index += 2
                    }
                    startsWith("*/") -> {
                        depth--
                        index += 2
                    }
                    else -> index++
                }
            }
        }
    }

    private fun scan(text: String): ScanResult = SourceScanner(text).scan()

    private fun stripped(text: String): String = scan(text).code

    private fun count(code: String, token: String): Int = Regex(Regex.escape(token)).findAll(code).count()

    private fun source(name: String, area: String = "state/geometry"): String {
        val rel = "src/main/java/com/example/zhengdao/baopu/$area/$name"
        val cwd = File(".").canonicalFile
        val parent = cwd.parentFile ?: cwd
        return listOf(File(cwd, rel), File(parent, rel), File(cwd, "app/$rel"), File(parent, "app/$rel"))
            .firstOrNull { it.exists() }?.readText() ?: error("找不到源码 $rel")
    }
}
