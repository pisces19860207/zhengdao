// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百五十八批 · 推演盘 **判定层**的判据测试（派单 §四 的①④⑥里不含盘的那几半）。
//
// 这一份只喂数据、不碰盘：配额除法、启动闸、报告形制——派单说"数字必须来自实测"，
// 那这句话就得能在没有手机、没有磁盘的情况下被钉住，否则它只是验收报告里的一句口号。
//
// 三条负例（本文件自己会红的地方）：
// · 卡点**只认实测跑挂的那一步**——全部跑通时必须写"没有卡点"，不许先验挑一个步号充数；
// · 耗时没测到必须写"未测到"，不许印 `0.0 秒` 冒充知道；
// · 文件数必须等于实测清单去重后的长度，两处口径不许分叉。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RiskClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RehearsalRulesTest {

    private fun step(
        n: Int,
        tool: String,
        kind: StepKind,
        ok: Boolean,
        note: String = "",
        ms: Long = 5L,
        paths: List<String> = emptyList(),
    ) = StepResult(n, tool, kind, RiskClass.R1, ok, note, ms, paths)

    private fun reportOf(
        mode: RehearsalMode = RehearsalMode.FILE,
        block: StartBlock = StartBlock.OK,
        blockNote: String = "",
        steps: List<StepResult> = emptyList(),
        paths: List<String> = emptyList(),
        cancelled: Boolean = false,
        degradeNote: String = "",
        elapsedMs: Long = 1_234L,
    ) = RehearsalReport(
        runId = "rh-1",
        mode = mode,
        block = block,
        blockNote = blockNote,
        steps = steps,
        measuredPaths = paths,
        cancelled = cancelled,
        degradeNote = degradeNote,
        realUntouched = true,
        copyBytesPeak = 2L * 1024 * 1024,
        cleanedBytes = 2L * 1024 * 1024,
        elapsedMs = elapsedMs,
    )

    // ── 配额（派单 §二②：min(可用×5%, 100MB)）───────────────────────────────

    @Test
    fun `配额是可用空间的5%但不超过100MB`() {
        assertEquals(1_000_000L, RehearsalRules.quotaBytes(20_000_000L))
        assertEquals(RehearsalRules.QUOTA_CAP_BYTES, RehearsalRules.quotaBytes(100L * 1024 * 1024 * 1024))
    }

    @Test
    fun `可用空间读数缺失按零配额算_不是按放行算`() {
        assertEquals(0L, RehearsalRules.quotaBytes(0L))
        assertEquals(0L, RehearsalRules.quotaBytes(-5L))
    }

    @Test
    fun `配额正好装下算够_边界不多一次无谓降级`() {
        assertTrue(RehearsalRules.fits(usedBytes = 600L, quotaBytes = 1_000L, needBytes = 400L))
        assertFalse(RehearsalRules.fits(usedBytes = 600L, quotaBytes = 1_000L, needBytes = 401L))
    }

    // ── 启动闸（派单 §二③）──────────────────────────────────────────────────

    @Test
    fun `电量低于20拦_到20放行`() {
        val low = EnvReading(batteryPercent = 19, thermalStatus = 0, hasNetwork = true)
        val edge = EnvReading(batteryPercent = 20, thermalStatus = 0, hasNetwork = true)
        assertEquals(StartBlock.LOW_BATTERY, RehearsalRules.blockOf(low))
        assertEquals(StartBlock.OK, RehearsalRules.blockOf(edge))
    }

    @Test
    fun `热状态到SEVERE及以上拦_阈值取自android36实测`() {
        val hot = EnvReading(80, RehearsalRules.THERMAL_STATUS_SEVERE, true)
        val mild = EnvReading(80, RehearsalRules.THERMAL_STATUS_SEVERE - 1, true)
        assertEquals(StartBlock.OVERHEAT, RehearsalRules.blockOf(hot))
        assertEquals(StartBlock.OK, RehearsalRules.blockOf(mild))
        assertEquals(3, RehearsalRules.THERMAL_STATUS_SEVERE)
    }

    @Test
    fun `读数取不到一律按拦算_不在没读数的前提下照跑`() {
        assertEquals(StartBlock.LOW_BATTERY, RehearsalRules.blockOf(EnvReading(-1, -1, false)))
        assertEquals(StartBlock.OVERHEAT, RehearsalRules.blockOf(EnvReading(80, -1, true)))
        assertEquals(StartBlock.NO_NETWORK, RehearsalRules.blockOf(EnvReading(80, 0, false)))
    }

    @Test
    fun `拦截理由按电量到热到网的固定顺序取第一个_同读数两次报告不许变`() {
        val both = EnvReading(batteryPercent = 5, thermalStatus = 9, hasNetwork = false)
        assertEquals(StartBlock.LOW_BATTERY, RehearsalRules.blockOf(both))
        val hotAndOffline = EnvReading(80, 9, false)
        assertEquals(StartBlock.OVERHEAT, RehearsalRules.blockOf(hotAndOffline))
    }

    @Test
    fun `拦截文案带实测数字_取不到就直说取不到`() {
        val low = EnvReading(7, 0, true)
        assertTrue(RehearsalRules.blockNote(low, StartBlock.LOW_BATTERY).contains("7%"))
        assertTrue(
            RehearsalRules.blockNote(EnvReading(-1, 0, true), StartBlock.LOW_BATTERY).contains("取不到"),
        )
    }

    // ── 报告形制（派单 §一③：≤8 行；§四判据②⑥）─────────────────────────────

    @Test
    fun `报告最多8行_常规`() {
        val lines = reportOf(
            steps = listOf(step(1, "read_file", StepKind.READ_REAL, true)),
            paths = listOf("notes/a.md", "notes/b.md"),
        ).lines()
        assertTrue("报告 ${lines.size} 行 > 8：$lines", lines.size <= 8)
        assertTrue(lines[1].contains("会改 2 个文件"))
        assertTrue(lines.last().contains("notes/a.md"))
    }

    @Test
    fun `报告最多8行_取消那一格只在真取消时出现`() {
        val plain = reportOf(steps = listOf(step(1, "read_file", StepKind.READ_REAL, true))).lines()
        val cancelled = reportOf(
            steps = listOf(step(1, "write_file", StepKind.WRITE_COPY, true)), cancelled = true,
        ).lines()
        assertFalse(plain.any { it.contains("已取消") })
        assertTrue(cancelled.any { it.contains("已取消") })
        assertTrue(cancelled.size <= 8)
        assertTrue(plain.size + 1 == cancelled.size)
    }

    @Test
    fun `报告最多8行_未启动只两行说清为什么没跑`() {
        val lines = reportOf(
            block = StartBlock.LOW_BATTERY, blockNote = "电量 7% < 20%",
            steps = listOf(step(1, "write_file", StepKind.NOT_RUN, false, ms = -1L)),
        ).lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("推演未启动"))
        assertTrue(lines[0].contains("电量不足"))
        assertTrue(lines[1].contains("一步没跑"))
    }

    @Test
    fun `文件数等于实测清单去重后的长度_两处口径不许分叉`() {
        val report = reportOf(
            steps = listOf(
                step(1, "write_file", StepKind.WRITE_COPY, true, paths = listOf("notes/a.md")),
                step(2, "edit_file", StepKind.WRITE_COPY, true, paths = listOf("notes/a.md")),
            ),
            paths = listOf("notes/a.md", "notes/A.MD", "notes/b.md"),
        )
        assertEquals(report.listedPaths.size, report.filesToChange)
        assertEquals("大小写与反斜杠写法归一后算同一个", 2, report.filesToChange)
    }

    @Test
    fun `降级报告在标题行就说清未做文件预演`() {
        val lines = reportOf(
            mode = RehearsalMode.PLAN_ONLY, degradeNote = "配额 51KB 装不下 200KB",
            steps = listOf(step(1, "write_file", StepKind.NOT_RUN, false, ms = -1L)),
            paths = listOf("notes/a.md"),
        ).lines()
        assertTrue(lines[0].contains("纯计划层预演"))
        assertTrue(lines[0].contains("未做文件预演"))
        assertTrue("清单来路要写在脸上：" + lines.last(), lines.last().contains("清单来自计划声明"))
    }

    // ── 负例①：卡点不许是估的 ───────────────────────────────────────────────

    @Test
    fun `负例_全部跑通时报告写没有卡点_不许先验挑一步充数`() {
        val report = reportOf(
            steps = listOf(
                step(1, "read_file", StepKind.READ_REAL, true),
                step(2, "write_file", StepKind.WRITE_COPY, true),
            ),
            paths = listOf("notes/a.md"),
        )
        assertNull(report.riskiestStep)
        assertTrue(report.lines().first { it.contains("卡点") }.contains("没有卡点"))
    }

    @Test
    fun `负例_卡点只认实测跑挂的第一步_并点名工具与原因`() {
        val report = reportOf(
            steps = listOf(
                step(1, "read_file", StepKind.READ_REAL, true),
                step(2, "edit_file", StepKind.WRITE_COPY, false, note = "锚点找不到：第 3 行"),
                step(3, "write_file", StepKind.WRITE_COPY, false, note = "第二个也不会被选中"),
            ),
            paths = listOf("notes/a.md"),
        )
        assertEquals(2, report.riskiestStep?.n ?: -1)
        val line = report.lines().first { it.contains("最可能卡") }
        assertTrue(line.contains("第 2 步"))
        assertTrue(line.contains("锚点找不到"))
    }

    @Test
    fun `负例_没跑的步不算卡点_它只是没跑`() {
        val report = reportOf(
            steps = listOf(step(1, "write_file", StepKind.NOT_RUN, false, ms = -1L)),
        )
        assertNull(report.riskiestStep)
        assertEquals(1, report.steps.count { it.kind == StepKind.NOT_RUN })
    }

    // ── 负例②：没测到的格子不许印成 0 ───────────────────────────────────────

    @Test
    fun `负例_耗时没测到写未测到_不写0秒假装知道`() {
        val lines = reportOf(
            steps = listOf(step(1, "read_file", StepKind.READ_REAL, true, ms = RunTraceRules.UNKNOWN_DURATION_MS)),
            elapsedMs = RunTraceRules.UNKNOWN_DURATION_MS,
        ).lines()
        assertTrue(lines.any { it.contains("实测耗时 未测到") })
        assertFalse(lines.any { it.contains("0.0 秒") })
    }

    @Test
    fun `步数与只读和副本写的分格各自数自己的那一档`() {
        val report = reportOf(
            steps = listOf(
                step(1, "read_file", StepKind.READ_REAL, true),
                step(2, "write_file", StepKind.WRITE_COPY, true),
                step(3, "write_file", StepKind.METADATA, true),
                step(4, "phone_dial", StepKind.UNSUPPORTED, true, note = "无文件副作用 ⇒ 不预演"),
            ),
            paths = listOf("notes/a.md", "notes/b.md"),
        )
        assertEquals(1, report.readSteps)
        assertEquals(1, report.writeSteps)
        assertEquals(1, report.metaSteps)
        assertEquals(1, report.unsupportedSteps)
        assertTrue(report.lines()[1].contains("元数据预演 1 步（未复制）"))
        assertTrue(report.lines().first { it.contains("卡点") }.contains("另有 1 步不在覆盖"))
    }

    @Test
    fun `没跑到的步不冒充跑通_取消那一趟分开报两档`() {
        val report = reportOf(
            cancelled = true,
            steps = listOf(
                step(1, "write_file", StepKind.WRITE_COPY, true),
                step(2, "edit_file", StepKind.NOT_RUN, false, ms = RunTraceRules.UNKNOWN_DURATION_MS),
                step(3, "move_path", StepKind.NOT_RUN, false, ms = RunTraceRules.UNKNOWN_DURATION_MS),
            ),
        )
        val line = report.lines().first { it.contains("卡点") }
        assertTrue(line + "：只许说 1 步跑通", line.contains("1 步跑通"))
        assertTrue(line, line.contains("另 2 步没跑到"))
        assertFalse(line, line.contains("全部跑通"))
    }

    // ── 归一写法与阈值（与副本沙盒同一个口径）───────────────────────────────

    @Test
    fun `相对写法归一_反斜杠与点斜杠前缀与大小写折成同一个键`() {
        // 这串只喂纯函数（不落盘），所以平台闸的"反斜杠拼路径"不在这里管——
        // 它测的正是「两种写法归一到同一个键」这件事。
        val key = RehearsalRules.relKey("./Notes/Sub\\A.MD")
        assertEquals("notes/sub/a.md", key)
        assertEquals(RehearsalRules.relKey("notes/sub/a.md"), RehearsalRules.relKey("Notes\\Sub\\A.md"))
    }

    @Test
    fun `阈值与抽样的格子都取自派单点名的数`() {
        assertEquals(8L * 1024 * 1024, RehearsalRules.METADATA_ONLY_BYTES)
        assertEquals(100L * 1024 * 1024, RehearsalRules.QUOTA_CAP_BYTES)
        assertEquals(20, RehearsalRules.MIN_BATTERY_PERCENT)
    }

    @Test
    fun `元数据那一行把可写与抽样字节都说出来_不存在就说要新建`() {
        val present = MetaReading("v/a.mp4", exists = true, bytes = 9L * 1024 * 1024,
            writable = true, headBytes = 4096, tailBytes = 4096)
        assertTrue(present.brief().contains("9216KB"))
        assertTrue(present.brief().contains("可写"))
        assertTrue(present.brief().contains("抽样首4096+尾4096"))
        assertTrue(MetaReading("n/new.md", false, 0L, true, 0, 0).brief().contains("将新建"))
    }
}
