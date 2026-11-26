// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.brain.BrainShadow
import com.example.zhengdao.baopu.brain.ShadowFeed
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowWiringTest {

    @Test
    fun `开关关闭_零日志零回调零写盘`() {
        TempSandbox.use("zd829-shadow") { dir ->
            val logs = mutableListOf<String>()
            var calls = 0
            val wrote = ShadowWiring.onRunSettled<ShadowFeed>(
                enabledProvider = { false },
                root = { error("关闭时不许取得账根") },
                round = { error("关闭时不许取得轮次") },
                feeds = { error("关闭时不许取得物类") },
                run = { _, _, _ -> calls++; 99 },
                log = logs::add,
            )
            assertEquals(0, wrote)
            assertEquals(0, calls)
            assertEquals(emptyList<String>(), logs)
            assertTrue(dir.listFiles().isNullOrEmpty())
        }
    }

    @Test
    fun `开关provider抛异常_原样上抛且不记影子日志`() {
        val failure = IllegalStateException("偏好读取失败")
        val logs = mutableListOf<String>()
        var calls = 0
        val thrown = runCatching {
            ShadowWiring.onRunSettled<ShadowFeed>(
                enabledProvider = { throw failure },
                run = { _, _, _ -> calls++; 0 },
                log = logs::add,
            )
        }.exceptionOrNull()
        assertSame("开关 provider 抛异常必须原样向上传递", failure, thrown)
        assertEquals(0, calls)
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `夹具有账根_只列实际缺失的轮次与物类`() {
        TempSandbox.use("zd829-shadow") { dir ->
            val logs = mutableListOf<String>()
            var rootCalls = 0
            val wrote = ShadowWiring.onRunSettled<ShadowFeed>(
                enabledProvider = { true },
                root = { rootCalls++; dir },
                run = { _, _, _ -> error("缺字段不许调用 onRoundEnd") },
                log = logs::add,
            )
            assertEquals(0, wrote)
            assertEquals("已经缺字段时不许解析账根", 0, rootCalls)
            assertEquals(
                listOf("[shadow] outcome=degraded missing=[round, feeds] wrote=0"),
                logs,
            )
            assertTrue(dir.listFiles().isNullOrEmpty())
        }
    }

    @Test
    fun `缺轮次或物类_不解析账根`() {
        val cases = listOf(
            (null to emptyList<ShadowFeed>()) to "[round]",
            (7L to null) to "[feeds]",
        )
        for ((inputs, missing) in cases) {
            val logs = mutableListOf<String>()
            val wrote = ShadowWiring.onRunSettled<ShadowFeed>(
                enabledProvider = { true },
                root = { error("已经缺字段时不许解析账根") },
                round = { inputs.first },
                feeds = { inputs.second },
                run = { _, _, _ -> error("缺字段不许调用 onRoundEnd") },
                log = logs::add,
            )
            assertEquals(0, wrote)
            assertEquals(
                listOf("[shadow] outcome=degraded missing=$missing wrote=0"),
                logs,
            )
        }
    }

    @Test
    fun `字段全缺失_只记实际missing_不触碰写入回调`() {
        val logs = mutableListOf<String>()
        var calls = 0
        val wrote = ShadowWiring.guarded<ShadowFeed>(
            enabled = true,
            root = null,
            round = null,
            feeds = null,
            run = { _, _, _ -> calls++; 99 },
            log = logs::add,
        )
        assertEquals(0, wrote)
        assertEquals(0, calls)
        assertEquals(
            listOf("[shadow] outcome=degraded missing=[root, round, feeds] wrote=0"),
            logs,
        )
    }

    @Test
    fun `部分缺失_只列实际缺失字段`() {
        TempSandbox.use("zd829-shadow") { dir ->
            val cases = listOf(
                Triple(null, 7L, emptyList<ShadowFeed>()) to "[root]",
                Triple(dir, null, emptyList<ShadowFeed>()) to "[round]",
                Triple(dir, 7L, null) to "[feeds]",
            )
            for ((inputs, missing) in cases) {
                val logs = mutableListOf<String>()
                val wrote = ShadowWiring.guarded<ShadowFeed>(
                    enabled = true,
                    root = inputs.first,
                    round = inputs.second,
                    feeds = inputs.third,
                    run = { _, _, _ -> error("缺字段不许调用 onRoundEnd") },
                    log = logs::add,
                )
                assertEquals(0, wrote)
                assertEquals(
                    listOf("[shadow] outcome=degraded missing=$missing wrote=0"),
                    logs,
                )
                assertTrue(dir.listFiles().isNullOrEmpty())
            }
        }
    }

    @Test
    fun `完整夹具_wrote只取真实写盘返回值`() {
        TempSandbox.use("zd829-shadow") { dir ->
            val logs = mutableListOf<String>()
            val feeds = listOf(
                ShadowFeed("glans", proxyTier = 9, stimulusTier = 2),
                ShadowFeed("shaft", proxyTier = 1, stimulusTier = 1),
            )
            val wrote = ShadowWiring.onRunSettled(
                enabledProvider = { true },
                root = { dir },
                round = { 7 },
                feeds = { feeds },
                run = { root, round, rows -> BrainShadow.onRoundEnd(root, round, rows, enabled = true) },
                log = logs::add,
            )
            assertEquals("两条输入只有一条真写入，禁止拿 feeds.size 顶数", 1, wrote)
            assertEquals(listOf("[shadow] ran wrote=1"), logs)
            val lines = File(dir, "脑影.jsonl").readLines()
            assertEquals(1, lines.size)
            assertTrue(lines.single().contains("\"zone\":\"shaft\""))
        }
    }

    @Test
    fun `空物类_记零写入且不建账本`() {
        TempSandbox.use("zd829-shadow") { dir ->
            val logs = mutableListOf<String>()
            val wrote = ShadowWiring.onRunSettled(
                enabledProvider = { true },
                root = { dir },
                round = { 7 },
                feeds = { emptyList() },
                run = { root, round, rows -> BrainShadow.onRoundEnd(root, round, rows, enabled = true) },
                log = logs::add,
            )
            assertEquals(0, wrote)
            assertEquals(listOf("[shadow] ran wrote=0"), logs)
            assertFalse(File(dir, "脑影.jsonl").exists())
        }
    }
}
