// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零四批 · 源级门禁（接线闭合清单 #3 的闭合判据②「有门禁钉住（删了调用就红）」）：
//   1. **evolution 有生产调用点**：`baopu/evolution/trigger/` 必须被 src/main 里
//      evolution 之外的文件引用（删掉 BaopuChatController 那一行 ⇒ 红，失败信息带 文件:行）；
//   2. **触发器真的调 163 批的离线评测机**（`EvolutionRunner`/`EvolutionCases`）——
//      不许"触发了却什么都没跑"；
//   3. **不许让模型自评**：trigger 包不许出现网络/真模型/自判类依赖——评测只能跑用例/规则；
//   4. 随包数据走构建期复制（真源唯一，不许手存第二份）。
package com.example.zhengdao.baopu.evolution.trigger

import com.example.zhengdao.baopu.evolution.eval.EvolutionTestSupport
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalTriggerSourceGateTest {

    private fun mainRoot(): File = File("src/main/java")

    private fun triggerDir(): File =
        File(mainRoot(), "com/example/zhengdao/baopu/evolution/trigger")

    private fun walk(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** 第一次出现关键字的 行号（从 1 起；找不到返回 null）。 */
    private fun firstLine(f: File, token: String): Int? =
        f.readLines(Charsets.UTF_8).indexOfFirst { it.contains(token) }.takeIf { it >= 0 }?.plus(1)

    // ── 1. 生产调用点 ─────────────────────────────────────────────────────

    @Test
    fun `trigger 包有 evolution 之外的生产调用点（删了就红，信息带 文件：行）`() {
        val root = mainRoot().absoluteFile
        val sites = walk(root)
            .filter { !it.invariantSeparatorsPath.contains("baopu/evolution/") }
            .mapNotNull { f ->
                if (f.readText(Charsets.UTF_8).contains("EvalTriggerWiring")) {
                    "${f.relativeTo(root).invariantSeparatorsPath}:${firstLine(f, "EvalTriggerWiring")}"
                } else {
                    null
                }
            }
        assertTrue(
            "baopu/evolution/trigger 没有任何生产调用点——触发器又成了死模块。" +
                "在会话收尾（EngineEvent.Settled 处理里）接 EvalTriggerWiring.onRunSettled。",
            sites.isNotEmpty(),
        )
        println("[eval-trigger] 生产调用点：${sites.joinToString()}")
    }

    // ── 2. 触发器真的跑 163 批的离线评测 ─────────────────────────────────

    @Test
    fun `生产评测实现引用 EvolutionRunner 与用例解析（触发≠空转）`() {
        val wiring = triggerDir().resolve("EvalTriggerWiring.kt")
        assertTrue("生产装配不存在：$wiring", wiring.isFile)
        for (token in listOf("EvolutionRunner(", "EvolutionCases.parse(", "FakePolicyModel.parse(")) {
            val line = firstLine(wiring, token)
            assertTrue("EvalTriggerWiring.kt 缺生产调用 $token（触发了却不评测＝假闭环）", line != null)
            println("[eval-trigger] $token → EvalTriggerWiring.kt:$line")
        }
    }

    // ── 3. 不许让模型自评（评测只能跑用例/规则） ─────────────────────────

    @Test
    fun `trigger 包零网络零真模型零自判依赖`() {
        val banned = listOf(
            "OkHttp", "okhttp", "HttpURLConnection", "java.net", "http://", "https://",
            "LlmClient", "DeepSeek", "apiKey", "OpenAi", "openai",
            // 评测只许跑离线用例/规则：网络评测壳与任何"判模型"的口都不许进触发器。
            "RuntimeEval", "EvalHarness", "MediatorJudge", "ProdEval",
        )
        val sources = walk(triggerDir()).associate { it.name to it.readText(Charsets.UTF_8) }
        assertTrue("trigger 包是空的（源级门禁扫了个寂寞）", sources.isNotEmpty())
        for ((name, text) in sources) {
            for (token in banned) {
                assertFalse("$name 出现了网络/真模型/自评字样：$token", text.contains(token))
            }
        }
    }

    // ── 4. 随包数据：构建期复制、真源唯一 ────────────────────────────────

    @Test
    fun `evolution 用例与假决策表走 stageEvalAssets 进包（真源唯一）`() {
        val gradle = File("build.gradle.kts").readText(Charsets.UTF_8)
        for (token in listOf("\"evolution/cases.json\"", "\"evolution/fake-policy.json\"")) {
            assertTrue(
                "StageEvalAssetsTask 没带 $token——随包评测数据缺失，触发后只会落「未跑」",
                gradle.contains(token),
            )
        }
        // 与 EvalAssetsTest 同一条纪律：assets 里不许手存第二真源。
        assertFalse(
            "src/main/assets/eval 只该由构建期生成；手存一份就等于有了第二真源",
            File("src/main/assets/eval").exists(),
        )
        // 仓库真源本身可解析（13 场景＋假决策表），且触发器声明的资产路径与 staging 对齐。
        val cases = EvolutionTestSupport.cases()
        assertEquals(13, cases.size)
        val fixtureDir = EvolutionTestSupport.fixtureDir()
        val wiring = triggerDir().resolve("EvalTriggerWiring.kt").readText(Charsets.UTF_8)
        for (name in listOf("cases.json", "fake-policy.json")) {
            assertTrue("仓库真源缺 eval/evolution/$name", File(fixtureDir, name).isFile)
            assertTrue("EvalTriggerWiring 没声明资产路径 eval/evolution/$name", wiring.contains("\"eval/evolution/$name\""))
        }
        assertTrue(
            "fake-policy.json 解析不了（假决策表坏了＝离线评测起不来）",
            runCatching { com.example.zhengdao.baopu.evolution.eval.FakePolicyModel.load(EvolutionTestSupport.policyFile()) }.isSuccess,
        )
    }
}
