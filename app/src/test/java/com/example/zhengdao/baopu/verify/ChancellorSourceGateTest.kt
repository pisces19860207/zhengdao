// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 右相验收闸的**源级门禁**（判据⑤⑥，扫源码、不是扫注释）：
//   ⑤ 全程无模型调用／不读对话与执行过程／不执行工具／不写盘／不自取时钟（超时靠注入的钟）；
//   ⑥ 有生产调用点：钉在交付入口（SkillPromote 转正＝学习成果入库时刻），删了调用即红。
//
// 判定核（ChancellorGate／ChancellorInput）额外禁全局账本单例——账行必须以快照入参进来
// （输入隔离的另一半：判据只看托单，不回查库）。
//
// 扫之前先证明门禁本身会咬人（合成违规行必须被抓出来），免得「全绿」只是没扫到。
package com.example.zhengdao.baopu.verify

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChancellorSourceGateTest {

    private val verifyDir = File("src/main/java/com/example/zhengdao/baopu/verify")
    private val mainDir = File("src/main/java/com/example/zhengdao")

    /** 全包禁（判据⑤＋负例：调模型审／读对话全文／改文件或执行工具／超时死等）。 */
    private val forbiddenEverywhere = listOf(
        // 模型调用（「拿模型再审一遍」在这里没有路）
        "LlmClient", "ModelRouter", "BaopuChatController", "LlmRequest", "streamRound", "sendRound",
        // 对话/执行过程上下文（「读对话全文」没有路）
        "RunEngine", "HistoryCodec", "RunTrace", "OpinionWiring",
        // 工具执行
        "ToolRegistry", "executeTool",
        // 网络/协程/线程（没有外送面，也没有「死等」的载体）
        "okhttp", "HttpURLConnection", "kotlinx.coroutines", "Thread(",
        // 写盘（右相只有三态结论：不改文件；读是允许的——判 C1 要读交付物本体）
        "writeText", "writeBytes", "appendText", "outputStream", "mkdir", "delete()",
        "deleteRecursively", "renameTo", "copyTo",
        // 时钟（超时预算用的必须是注入的钟，不自取）
        "System.currentTimeMillis", "System.nanoTime",
    )

    /** 判定核（Gate/Input）额外禁全局账本与库：账行必须以快照入参进来。 */
    private val forbiddenInCore = listOf("DecisionLedger", "FileOpLog", "FileOpStore", "BaopuDb")

    private val coreFiles = listOf("ChancellorGate.kt", "ChancellorInput.kt")

    /** 只扫代码行：整行注释与行尾注释都剔掉（注释里说「零模型」不算依赖）。 */
    private fun scan(src: String, forbidden: List<String>): List<String> = src.lineSequence()
        .map { it.trimStart() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") || it.startsWith("*/") }
        .map { it.substringBefore("//") }
        .flatMap { line -> forbidden.filter { line.contains(it) }.map { "$it ← ${line.trim().take(80)}" } }
        .toList()

    private fun kotlinFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `门禁_合成违规行会被抓出来`() {
        val violations = listOf(
            "import com.example.zhengdao.baopu.agent.RunEngine",
            "val client: LlmClient = model.client",
            "f.writeText(\"内容\")",
            "val t = Thread(Runnable { })",
            "val now = System.nanoTime()",
            "toolRegistry.executeTool(\"write_file\", args)",
        )
        for (line in violations) {
            assertTrue("没抓住违规：$line", scan(line, forbiddenEverywhere).isNotEmpty())
        }
        assertTrue(scan("DecisionLedger.read(root)", forbiddenInCore).isNotEmpty())
        // 读是被允许的（判 C1 要读交付物本体）：合成读行不得误伤。
        assertTrue(scan("val head = f.inputStream().use { it.readBytes() }", forbiddenEverywhere).isEmpty())
        assertTrue(scan("val text = skillFile.readText(Charsets.UTF_8)", forbiddenEverywhere).isEmpty())
    }

    @Test
    fun `verify包_零模型_零对话_零执行_零写盘_源级门禁`() {
        val hits = kotlinFiles(verifyDir).flatMap { f ->
            scan(f.readText(Charsets.UTF_8), forbiddenEverywhere).map { "${f.name}: $it" }
        }
        assertTrue("verify 包出现禁用依赖：\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun `判定核_不摸全局账本_账行必须走入参`() {
        val hits = coreFiles.flatMap { name ->
            val f = File(verifyDir, name)
            assertTrue("判定核文件不在：$name", f.isFile)
            scan(f.readText(Charsets.UTF_8), forbiddenInCore).map { "$name: $it" }
        }
        assertTrue("判定核出现全局账本引用：\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun `托单形状_输入只三样_字段封死`() {
        val src = File(verifyDir, "ChancellorInput.kt").readText(Charsets.UTF_8)
        val region = src.substringAfter("internal data class ChancellorSubmission").substringBefore("init {")
        val fields = Regex("""val (\w+)""").findAll(region).map { it.groupValues[1] }.toSet()
        // 交付物／Opinion[]／账行三样＋两个判定锚点（解析根、紧急标记），没有第五个口子。
        assertEquals(setOf("deliverables", "opinions", "ledger", "root", "urgent"), fields)
    }

    @Test
    fun `生产调用点_钉在交付入口_删了即红`() {
        val promote = File("src/main/java/com/example/zhengdao/baopu/SkillPromote.kt")
        val src = promote.readText(Charsets.UTF_8)
        assertTrue(
            "生产调用点被删：SkillPromote（交付入口）必须调用 ChancellorWiring.verifyLearningPromotion",
            src.contains("ChancellorWiring.verifyLearningPromotion"),
        )
        // 全仓口径：verify/ 之外至少一处真调用（防止只有测试在调）。
        val callSites = kotlinFiles(mainDir)
            .filter { !it.absolutePath.replace('\\', '/').contains("/baopu/verify/") }
            .filter { it.readText(Charsets.UTF_8).contains("ChancellorWiring.") }
            .map { it.absolutePath }
        assertTrue("右相在生产代码里零调用（零引用即死模块）", callSites.isNotEmpty())
    }
}
