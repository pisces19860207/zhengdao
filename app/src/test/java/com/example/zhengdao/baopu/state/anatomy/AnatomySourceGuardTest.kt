package com.example.zhengdao.baopu.state.anatomy

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnatomySourceGuardTest {
    @Test
    fun `形体坐标系拓扑三件各自独立`() {
        val shape = Files.readString(source("ShapeAdapter.kt"))
        val frame = Files.readString(source("FrameResolver.kt"))
        val topology = Files.readString(source("TopologyGraph.kt"))

        assertTrue(shape.contains("interface ShapeAdapter"))
        assertTrue(shape.contains("fun adapt(parameters: AdmittedParameters)"))
        assertFalse(shape.contains("Map<String, String>"))
        assertTrue(frame.contains("interface FrameResolver"))
        assertTrue(topology.contains("interface TopologyGraph"))
        assertFalse(shape.contains("FrameResolver") || shape.contains("TopologyGraph") || shape.contains("ConstraintEngine"))
        assertFalse(frame.contains("ShapeAdapter") || frame.contains("TopologyGraph"))
        assertFalse(topology.contains("ShapeAdapter") || topology.contains("FrameResolver"))
        assertFalse(topology.contains("state.geometry") || topology.contains("Transform3") || topology.contains("ConvexShape"))
        assertFalse(Files.readString(source("ConstraintEngine.kt")).contains("interface ShapeAdapter"))
    }

    @Test
    fun `三层实体与统一模型字段齐全`() {
        val model = Files.readString(source("AnatomyRegion.kt"))
        val spec = Files.readString(source("RegionSpec.kt"))
        val fields = listOf(
            "schemaVersion", "regionId", "regionType", "parentRegionId", "side", "anatomicalFrame",
            "morphology", "topology", "state", "constraints", "provenance", "validation",
        )

        assertTrue(model.contains("data class AnatomicalRegionModel("))
        assertTrue(model.contains("require(schemaVersion == ANATOMY_SCHEMA_VERSION)"))
        assertTrue(Files.readString(source("Subzone.kt")).contains("data class Subzone"))
        assertTrue(Files.readString(source("Zone.kt")).contains("data class Zone"))
        assertTrue(spec.contains("class AdmittedParameters"))
        assertFalse(spec.contains("fun copy("))
        for (field in fields) {
            assertTrue("统一模型缺字段：$field", model.contains("val $field:"))
        }
    }

    @Test
    fun `映射实现不含ordinal或下标猜测`() {
        val mapping = Files.readString(source("AnatomyMapping.kt"))
        val ordinalGuess = Regex("""\.ordinal\b|\b(?:index|indices|indexOf)\b|forEachIndexed|mapIndexed|withIndex|\[\s*\d+\s*\]""")
        val violations = productionSources().filter { ordinalGuess.containsMatchIn(Files.readString(it)) }

        assertTrue("发现按序号猜测的映射：$violations", violations.isEmpty())
        assertTrue(mapping.contains("fun forRegion"))
        assertTrue(mapping.contains("fun forSubzone"))
        assertTrue(mapping.contains("fun forZone"))
    }

    @Test
    fun `主包无魔力默认值或Elvis兜底`() {
        val magicDefault = Regex(
            """(?<![\w=!<>])(?:val\s+|var\s+)?[A-Za-z_]\w*\s*:\s*[^,)=\r\n]+=\s*(?:true|false|null|-?\d+(?:\.\d+)?)\b""",
        )
        val inferredDefault = Regex(
            """\b(?:val|var)\s+[A-Za-z_]\w*\s*=\s*(?:true|false|null|-?\d+(?:\.\d+)?)\b""",
        )
        val violations = productionSources().filter { file ->
            val text = Files.readString(file)
            magicDefault.containsMatchIn(text) || inferredDefault.containsMatchIn(text) || text.contains("?:")
        }

        assertTrue(magicDefault.containsMatchIn("val ratio: Double = 0.5"))
        assertTrue(inferredDefault.containsMatchIn("val ratio = 0.5"))
        assertFalse(inferredDefault.containsMatchIn("append(spec, version, allowSameCatalogVersion = false)"))
        assertTrue("发现默认值或 Elvis 兜底：$violations", violations.isEmpty())
    }

    @Test
    fun `文件头有三条守则和参数准入规则`() {
        val header = Files.readAllLines(source("AnatomyCore.kt")).take(6)

        assertTrue(header.contains("// 区域树不承担几何计算。"))
        assertTrue(header.contains("// 拓扑 ≠ 碰撞。"))
        assertTrue(header.contains("// 形态 ≠ 状态。"))
        assertTrue(header.contains("// 无消费方参数本批不接收；后续只可留 extensions/experimental，RegionSpec 拒绝其进入主 schema。"))
    }

    @Test
    fun `不引用865坐标系包或916几何基座包`() {
        val forbidden = Regex("""import com\.example\.zhengdao\.baopu\.state\.(?:frame|geombase)\b""")
        val violations = productionSources().filter { forbidden.containsMatchIn(Files.readString(it)) }

        assertTrue("发现越界引用：$violations", violations.isEmpty())
    }

    @Test
    fun `不出现骨盆类型前缀`() {
        val forbidden = "Pel" + "vic"
        val declaration = Regex("\\b(?:class|interface|object|typealias)\\s+$forbidden[A-Za-z0-9_]*")
        val violations = productionSources().filter { declaration.containsMatchIn(Files.readString(it)) }

        assertTrue("发现被禁止的区域实例类型：$violations", violations.isEmpty())
    }

    private fun source(name: String): Path {
        return sourceDir().resolve(name)
    }

    private fun productionSources(): List<Path> {
        val files = mutableListOf<Path>()
        Files.walk(sourceDir()).use { paths -> paths.forEach { files.add(it) } }
        return files
            .filter { it.toFile().isFile && it.toString().endsWith(".kt") }
            .sortedBy { it.toString() }
    }

    private fun sourceDir(): Path {
        val cwd = Path.of(System.getProperty("user.dir"))
        val root = generateSequence(cwd) { it.parent }.firstOrNull { candidate ->
            candidate.resolve("app/src/main/java").toFile().isDirectory
        }
        requireNotNull(root) { "找不到仓库根" }
        return root.resolve("app/src/main/java/com/example/zhengdao/baopu/state/anatomy")
    }
}
