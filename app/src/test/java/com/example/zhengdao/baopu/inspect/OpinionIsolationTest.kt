package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpinionIsolationTest {
    private val sourceRoot = File("src/main/java/com/example/zhengdao/baopu/inspect")
    private val newFiles = setOf("Opinion.kt", "OpinionAdapters.kt", "OpinionMerger.kt", "OpinionVotes.kt", "OpinionBudget.kt")
    private val uniqueTypes = listOf(
        "Opinion", "OpinionAdapter", "OpinionMerger", "OpinionMergeResult", "OpinionVotes",
        "OpinionFeedback", "OpinionSignal", "EmergencyExemption", "LawRef", "ActionCode",
    )

    private fun code(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .replace(Regex("(?m)//.*$"), " ")

    private fun primaryFields(text: String): List<String> = code(text)
        .substringAfter("data class Opinion(").substringBefore(") {")
        .split(',').map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }

    private fun referencesContract(text: String): Boolean =
        Regex("\\b(${uniqueTypes.joinToString("|")})\\b").containsMatchIn(code(text)) ||
            Regex("""com\.example\.zhengdao\.baopu\.inspect\.(Budget|Source|Severity|\*)""").containsMatchIn(code(text))

    @Test fun `negative two lawRef is mandatory nonnullable with no default`() {
        val fields = primaryFields(File(sourceRoot, "Opinion.kt").readText())
        assertEquals(
            listOf("val source: Source", "val severity: Severity", "val gist: String", "val action: ActionCode", "val lawRef: LawRef", "val evidence: List<String>", "val vote: Int = 0"),
            fields,
        )
    }

    @Test fun `law field guard detects nullable missing and defaulted variants`() {
        val original = File(sourceRoot, "Opinion.kt").readText()
        listOf("", "val lawRef: LawRef? = null,", "val lawRef: LawRef = LawRef.NONE,").forEach { replacement ->
            assertFalse(primaryFields(original.replace("val lawRef: LawRef,", replacement)).contains("val lawRef: LawRef"))
        }
    }

    @Test fun `adapters also require an explicit law and evidence`() {
        val text = code(File(sourceRoot, "OpinionAdapters.kt").readText())
        assertTrue(text.contains("val source: Source"))
        assertTrue(Regex("""lawRef:\s*LawRef\s*,""").containsMatchIn(text))
        assertTrue(Regex("""evidence:\s*List<String>\s*,""").containsMatchIn(text))
        assertFalse(text.contains("LawRef.NONE"))
    }

    @Test fun `negative six existing production files never reference the new contract`() {
        val additions = newFiles.map { File(sourceRoot, it).canonicalFile }.toSet()
        val files = listOf(File("src/main"), File("src/debug")).flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
        }
        assertTrue("No production source files scanned", files.size > additions.size)
        val references = files.filter { it.canonicalFile !in additions && referencesContract(it.readText()) }
        assertEquals("Existing sources must not consume the contract", emptyList<String>(), references.map { it.path })
    }

    @Test fun `isolation guard detects accidental wiring without editing a production file`() {
        assertTrue(referencesContract("val x = OpinionAdapter.PATROL.adapt()"))
        assertTrue(referencesContract("import com.example.zhengdao.baopu.inspect.Budget"))
        assertTrue(referencesContract("import com.example.zhengdao.baopu.inspect.*"))
        assertFalse(referencesContract("// OpinionMerger is not connected"))
        assertFalse(referencesContract("val secondOpinionText = existingFinding"))
    }

    @Test fun `new files import no existing producer scheduler UI or IO service`() {
        newFiles.forEach { name ->
            val file = File(sourceRoot, name)
            assertTrue("Missing contract file $name", file.isFile)
            val text = code(file.readText())
            val imports = Regex("""(?m)^import\s+([^\s]+)""").findAll(text).map { it.groupValues[1] }.toList()
            assertTrue("Unexpected dependency in $name: $imports", imports.all { it in setOf("java.text.Normalizer", "java.util.Locale") })
            listOf("android.", "java.io.", "java.net.", "kotlinx.coroutines", "Thread(", "System.currentTimeMillis", "onClick", "showDialog", "MediatorWiring", "PatrolRunner", "DecisionLedger").forEach {
                assertFalse("$name reaches a live service: $it", text.contains(it))
            }
        }
    }
}
