// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十六批 · 判据②的**另一半**：接线前后执行路径不变——记账是纯旁观，
// 没出处／没挂账／落盘失败三种情况下，主流程结局一个字节都不许变。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.inspect.DeviationLedger
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeviationWiringSilentTest {

    private lateinit var detachRoot: File

    @Before
    fun setup() {
        // 出处信箱空着（等于"引擎还没起轮"——存量 JVM 测试的真实状态）。
        DeviationWiring.resetForTest()
        // 账本挂载让给一个一次性空目录：写不写都不碰本测试的数据区。
        detachRoot = Files.createTempDirectory("baopu-386-silent").toFile()
        DeviationLedger.install(detachRoot)
    }

    @After
    fun teardown() {
        DeviationWiring.resetForTest()
        DeviationLedger.install(Files.createTempDirectory("baopu-386-detach").toFile())
        runCatching { detachRoot.deleteRecursively() }
    }

    /** 没出处（runId 空）⇒ 一个字节不写：363 硬规矩 3 在接线口这侧的体现。 */
    @Test
    fun `没出处_三个记账口都静默_账文件不存在`() {
        DeviationWiring.recordOverSteps(300, 300, 300)
        DeviationWiring.recordTimeout(1_000L, 500L, "slow_probe")
        DeviationWiring.recordSuspendTimeout(1L, "问题", false)

        assertFalse("账文件根本没建", DeviationLedger.fileIn(detachRoot).isFile)
    }

    /** 有出处但落盘会炸（挂载点是个文件，mkdirs/append 必失败）⇒ 不抛，write 的兜底在 363。 */
    @Test
    fun `落盘失败_记账口不抛`() {
        val notADir = File(detachRoot, "占位文件").apply { writeText("不是目录") }
        DeviationLedger.install(notADir)
        DeviationWiring.installRun("r-silent@0")

        // 三个口全走一遍：一个都不许把异常抛给主流程。
        DeviationWiring.recordOverSteps(300, 300, 300)
        DeviationWiring.recordTimeout(1_000L, 500L, "slow_probe")
        DeviationWiring.recordSuspendTimeout(1L, "问题", false)
    }

    /** 主流程结局不变（判据②）：没出处时真跑一次工具超时，结局与账面都照旧。 */
    @Test
    fun `没出处真跑工具超时_结局照旧_账面干净`() {
        val runner = ToolRunner(Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } })
        val spec = ToolSpec(
            name = "slow_probe",
            description = "测试用慢工具（执行器真睡过时限）",
            inputSchema = jsonObjectOf("type" to "object", "properties" to jsonObjectOf()),
            permission = ToolPermission.FREE,
            timeoutMs = 100L,
        ) {
            Thread.sleep(500L)
            ToolOutcome.Ok("迟到的结果")
        }

        val outcome = runner.run(spec, JSONObject())

        assertTrue("结局照旧是超时失败", outcome is ToolOutcome.Failed)
        assertEquals("原文照旧是超时那句", ToolRunner.timeoutText("slow_probe", 100L), (outcome as ToolOutcome.Failed).error)
        assertFalse("账面干净（没出处不落账）", DeviationLedger.fileIn(detachRoot).isFile)
    }
}
