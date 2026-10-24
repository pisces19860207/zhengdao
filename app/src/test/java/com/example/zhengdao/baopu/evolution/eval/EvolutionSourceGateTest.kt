// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十三批 · 源码门禁（设计 §9／§10）：
// 新包不许有网络/真模型/用户数据写口；判定不许写 autoPromote=true；
// 夹具只认 fixture: 证据，场景数固定 13。
package com.example.zhengdao.baopu.evolution.eval

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionSourceGateTest {

    private fun packageDir(): File =
        File(EvolutionTestSupport.repoRoot(), "app/src/main/java/com/example/zhengdao/baopu/evolution/eval")

    private fun sources(): Map<String, String> {
        val dir = packageDir()
        assertTrue("新包不存在：$dir", dir.isDirectory)
        return dir.listFiles()!!.filter { it.extension == "kt" }
            .associate { it.name to it.readText(Charsets.UTF_8) }
    }

    @Test
    fun packageHasExactlyTheSevenDesignedFiles() {
        assertEquals(
            setOf(
                "EvolutionCandidate.kt",
                "EvolutionCase.kt",
                "FakePolicyModel.kt",
                "FakeTools.kt",
                "EvolutionRunner.kt",
                "EvolutionCompare.kt",
                "EvolutionReport.kt",
            ),
            sources().keys,
        )
    }

    @Test
    fun noNetworkAndNoRealModel() {
        val banned = listOf(
            "OkHttp", "okhttp", "HttpURLConnection", "java.net", "api.",
            "http://", "https://", "LlmClient", "DeepSeek", "apiKey", "OpenAi", "openai",
        )
        for ((name, text) in sources()) {
            for (token in banned) {
                assertFalse("$name 出现了网络/真模型字样：$token", text.contains(token))
            }
        }
    }

    @Test
    fun noUserDataWrites() {
        val banned = listOf(
            "SkillStore", "PersonaFiles", "MemoryStore", "DiaryStore",
            "filesDir", "getExternalFilesDir", "Environment.getExternalStorage", "/sdcard",
            "write_skill", "append_persona",
        )
        for ((name, text) in sources()) {
            for (token in banned) {
                assertFalse("$name 出现了用户数据写口：$token", text.contains(token))
            }
        }
    }

    @Test
    fun adoptIsNeverPromotion() {
        val report = sources().getValue("EvolutionReport.kt")
        assertTrue(report.contains("AUTO_PROMOTE = false"))
        assertTrue(report.contains("REQUIRES_HUMAN_APPROVAL = true"))
        for ((name, text) in sources()) {
            assertFalse("$name 写了 autoPromote=true", text.contains("autoPromote = true"))
            assertFalse("$name 写了 autoPromote\", true", text.contains("\"autoPromote\", true"))
        }
    }

    @Test
    fun candidatesAreFixtureMappedAndCountedByFour() {
        val dir = File(EvolutionTestSupport.fixtureDir(), "candidates")
        val files = dir.listFiles()!!.filter { it.extension == "json" }.sortedBy { it.name }
        assertEquals(listOf("better.json", "danger.json", "no-diff.json", "worse.json"), files.map { it.name })
        for (f in files) {
            val c = EvolutionCandidate.load(f)
            assertTrue("${f.name} 必须自查 fixture: 证据", c.fixtureMapped)
            assertFalse("${f.name} 被误判为 162 待交付类别", c.mechanismOnly)
        }
    }

    @Test
    fun caseFixtureStaysAtThirteenMechanicalCases() {
        val cases = EvolutionTestSupport.cases()
        assertEquals(13, cases.size)
        for (case in cases) {
            assertTrue("场景 ${case.id} 没有断言", case.asserts.isNotEmpty())
            for (a in case.asserts) {
                assertTrue("场景 ${case.id} 的断言种类越界：${a.kind}", a.kind in EvolutionCases.KINDS)
            }
        }
        // 靶心与危险场景必须都在。
        val ids = cases.map { it.id }.toSet()
        for (required in listOf(
            "evo-skill-visible", "evo-skill-absent", "evo-persona-boundary",
            "evo-memory-recall", "evo-plan-order", "evo-danger-delete",
            "evo-danger-outside", "evo-token-budget",
        )) {
            assertTrue("缺场景 $required", required in ids)
        }
    }
}
