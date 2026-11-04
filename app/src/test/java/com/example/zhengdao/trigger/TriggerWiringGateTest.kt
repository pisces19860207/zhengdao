// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 事件触发源的源码门禁（第三百九十九批）——负例里那些「运行时没有可观察下场」的约束
 * 只能钉在源码上（QuietWiringGateTest / ProdEvalTest 同款范式）：
 *
 * 1. **只做触发＋唤起**：装配层除了 `createSession` / `send` 没有第二条进引擎的路；
 *    审批、代答、直接执行工具这类口子一个都不许出现（派工单 §四边界）。
 * 2. **默认全关**：`TriggerSwitch` 的生产读法必须是「键不存在 ⇒ false」——
 *    新装用户一个自动动作都不会有（判据②）。
 * 3. **纯账层零 Android**：TriggerPass/TriggerRules/TriggerRule 不 import android.*，
 *    JVM 上真跑才可能（它们是判据的主体，mock 自证不算数）。
 * 4. **账本房型**：事件触发账一处追加口＋一处自建父目录（363 偏差账同款纪律）。
 * 5. **限频与去重的常数只写一遍**：判据层的 N 别处不许再抄一份。
 */
class TriggerWiringGateTest {

    private fun src(rel: String): String {
        val f = listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile }
            ?: error("找不到 $rel（CWD 应为 app/ 或仓库根）")
        return f.readText(Charsets.UTF_8)
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("(?m)//.*$"), " ")
    }

    private fun triggerDir(): List<File> {
        val dir = listOf(File("src/main/java/com/example/zhengdao/trigger"), File("app/src/main/java/com/example/zhengdao/trigger"))
            .firstOrNull { it.isDirectory } ?: error("找不到 trigger 包")
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }!!.sortedBy { it.name }
    }

    // ── 1. 只做触发＋唤起 ────────────────────────────────────────────────

    @Test
    fun `装配层没有审批与代答的口子_只有引擎同一入口`() {
        val banned = listOf(
            "resumeWithApproval", "approveRestoredPending", "answerChoice", "submitDecision",
            "GuardPipeline", "ToolExecutor", "executeWithSwitch",
        )
        triggerDir().forEach { f ->
            val text = src(f.path)
            banned.forEach { word ->
                assertFalse("${f.name} 里出现了「$word」——触发层只许唤起，不许替人执行（399 §四）", text.contains(word))
            }
        }
        val runner = src("src/main/java/com/example/zhengdao/trigger/TriggerRunner.kt")
        assertTrue("装配层要显式走 engine.send（与用户敲字同一入口）", runner.contains("engine.send("))
    }

    @Test
    fun `装配层不读日历正文_探针只取三列`() {
        val probe = src("src/main/java/com/example/zhengdao/trigger/TriggerCalendarProbe.kt")
        val banned = listOf("DESCRIPTION", "EVENT_LOCATION", "AVAILABILITY")
        banned.forEach { word ->
            assertFalse("探针不许读日历的 $word（不读用户隐私内容，399 §四）", probe.contains(word))
        }
    }

    // ── 2. 默认全关 ──────────────────────────────────────────────────────

    @Test
    fun `源级开关默认值必须是false_新装用户零自动动作`() {
        val text = src("src/main/java/com/example/zhengdao/trigger/TriggerSwitch.kt")
        assertTrue("生产读法必须 getBoolean(key, false)——默认全关是判据②的字面证据", text.contains("false"))
        assertFalse("不许有默认开的后门", Regex("""getBoolean\([^)]*,\s*true\s*\)""").containsMatchIn(text))
    }

    // ── 3. 纯账层零 Android ──────────────────────────────────────────────

    @Test
    fun `纯账层不import android`() {
        val pure = listOf("TriggerPass.kt", "TriggerRules.kt", "TriggerRule.kt", "TriggerLedger.kt", "TriggerStore.kt", "TriggerSource.kt")
        pure.forEach { name ->
            val text = src("src/main/java/com/example/zhengdao/trigger/$name")
            assertFalse("$name 是纯账层，不许 import android.*（判据要在 JVM 真跑）", Regex("""import android""").containsMatchIn(text))
        }
    }

    // ── 4. 账本房型 ──────────────────────────────────────────────────────

    @Test
    fun `事件触发账_一处追加口_一处自建父目录`() {
        val text = src("src/main/java/com/example/zhengdao/trigger/TriggerLedger.kt")
        assertEquals("appendText 只许一处", 1, Regex("""\.appendText\(""").findAll(text).count())
        assertEquals("mkdirs 只许一处", 1, Regex("""mkdirs\(""").findAll(text).count())
    }

    // ── 5. 限频常数只写一遍 ──────────────────────────────────────────────

    @Test
    fun `限频N只在TriggerRules里定义_别处不抄第二份`() {
        val offender = triggerDir().filter { it.name != "TriggerRules.kt" && it.name != "TriggerRulesTest.kt" }
            .map { it.name to src(it.path) }
            .firstOrNull { it.second.contains("RATE_LIMIT_PER_HOUR =") }
        assertEquals("限频常数第二处定义：${offender?.first}", null, offender)
    }
}
