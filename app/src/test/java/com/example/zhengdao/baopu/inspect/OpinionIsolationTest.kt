package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpinionIsolationTest {
    private val sourceRoot = File("src/main/java/com/example/zhengdao/baopu/inspect")
    private val newFiles = setOf("Opinion.kt", "OpinionAdapters.kt", "OpinionMerger.kt", "OpinionVotes.kt", "OpinionBudget.kt")
    /**
     * 契约的**合法消费方**（2026-10-29 蓝总管加，274 批）。
     *
     * 契约钉原口径是"270 之前的旧文件一个都不许引"，但**接线必须有消费方**——
     * 所以把它拆成两张名单：`newFiles`＝契约本体；本表＝**经批准的消费方**（相对 `src/main/java/` 的路径）。
     *
     * 加一项的门槛（写在这是给后来的人看的）：
     * 1) 该文件**确实构造/消费** `Opinion`（不是顺手 import）；
     * 2) 它属于某一批**明确要求接线**的派工单（本表每项都带批号）；
     * 3) 不许为了让它变绿而把"未接线的旧文件"塞进来——那是把契约做没。
     */
    private val approvedConsumers = mapOf(
        // 274 批：把分类器的判定包成 Opinion 送调解器做预算判定（第一条真实链路）。
        "com/example/zhengdao/baopu/agent/OpinionWiring.kt" to "274",
        // 272 批：L28 旁路对拍器——派工单明令它产 `Opinion(source=PATROL, lawRef=L28)`，
        // findings＋意见全部走勘验的路（真机实测见交付报告 §5）。
        "com/example/zhengdao/baopu/inspect/TraceCrossCheck.kt" to "272",
        // 277 批：L33 最小版——判了"不打扰"要留理由（`追认队列.jsonl`），
        // 那条理由必须带来源意见的 gist/lawRef，所以它是契约的真消费方，不是顺手 import。
        "com/example/zhengdao/baopu/agent/SelfRulingLedger.kt" to "277",
        // 283 批：学习器挡在调解器前面的一闸（派工单 283「学习器↔反思器接进调解器」）——
        // 按类（gist）记用户信号、判必问集（lawRef/severity）、压下时落 L33 自决单，
        // 三件都构造/消费 Opinion；反射归因（ReflectAttribution）不碰契约，无需登记。
        "com/example/zhengdao/baopu/agent/LearnWiring.kt" to "283",
        // 278 批：中书舍人拟稿——把一条意见写成"人能一眼看懂的三行"，
        // 必须读 opinion 的 gist 与 lawRef 才能选对那一版人话（不是顺手 import）。
        "com/example/zhengdao/ui/baopu/RoyalDrafts.kt" to "278",
        // 279 批：暗卫抽检产 `Opinion(source=PATROL)`；金吾卫报告里的 issue 带 `LawRef`；
        // 互查按 `(check, evidence, verdict)` 对表。三处都是构造/消费，不是顺手 import。
        "com/example/zhengdao/baopu/inspect/NocturnalPatrol.kt" to "279",
        "com/example/zhengdao/baopu/inspect/GoldenGuard.kt" to "279",
        "com/example/zhengdao/baopu/inspect/PatrolMutual.kt" to "279",
    )
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
        // 消费方按"相对 src/main/java 的路径"放行（文件不存在时既不放行也不报错——它还没被创建）。
        val mainRoot = File("src/main/java").canonicalFile
        val allowed = approvedConsumers.keys.map { File(mainRoot, it).canonicalFile }.toSet()
        val references = files.filter {
            it.canonicalFile !in additions && it.canonicalFile !in allowed && referencesContract(it.readText())
        }
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
