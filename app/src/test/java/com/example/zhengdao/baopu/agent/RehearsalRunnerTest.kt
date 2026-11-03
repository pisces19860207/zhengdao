// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百五十八批 · 推演盘（军师）**跑法层**的判据测试：派单 §四 的①②③④⑤⑥逐条落盘直测。
//
// 为什么这一份必须落真盘：六条判据里有五条说的是「盘上发生了什么」——真目录一格没动、
// 副本只在 `rehearsal/rh-*` 且事后没了、>8MB 没进副本、配额不够就降级、取消之后清干净。
// 这些句话拿假数据是钉不住的，所以这里用临时目录真建真删（`@After` 里递归清，
// 测试自己也不给宿主留垃圾）。
//
// ## 三件看着像"多余"的写法，各有来路
// 1. **跑中途用 `stop` 抓一张快照**：`stop` 在每一步之间问一次，是这层唯一能在
//    "副本还活着"那一瞬插进去的探针。判据②说"副本只在 rehearsal/ 下"，
//    跑完之后树已经删了，只能靠这一张中途快照来证明它**曾经**在哪。
// 2. **数字与一次独立重数比**（判据⑥）：报告里的 N 不跟它自己比，
//    跟"这趟到底复制了几个文件、改了哪个"这套从计划里数出来的期望比。
// 3. **8MB 用缩小的阈值测不了这一层**：跑法层的阈值就是派单那个 8MB，
//    所以这里真造一个 9MB 的**稀疏**文件（`setLength`，不落实际簇），
//    量的是"长度与摘要"这两格，稀疏不影响判据。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.inspect.RehearsalBaselineStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

class RehearsalRunnerTest {

    private lateinit var cache: File
    private lateinit var workspace: File

    /** 现场读数的默认档：电量 80%、热状态 NONE、有网 ⇒ 启动闸全放行。 */
    private val okEnv = EnvReading(batteryPercent = 80, thermalStatus = 0, hasNetwork = true)

    /** 可用空间给 2GB ⇒ 配额按上限折到 100MB，足够把小文件复制进副本。 */
    private val roomy = 2L * 1024L * 1024L * 1024L

    @Before
    fun setUp() {
        cache = Files.createTempDirectory("rh358-cache-").toFile()
        workspace = Files.createTempDirectory("rh358-ws-").toFile()
        RehearsalLane.resetForTest()
        RehearsalSite.resetForTest()
        RehearsalBaselineStore.resetForTest()
    }

    @After
    fun tearDown() {
        RehearsalLane.resetForTest()
        RehearsalSite.resetForTest()
        RehearsalBaselineStore.resetForTest()
        cache.deleteRecursively()
        workspace.deleteRecursively()
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    private fun File.put(rel: String, text: String): File {
        val f = File(this, rel)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    /** 稀疏地"占"这么大：只改长度，不落簇（判据看的是 length／sha，不是占用簇数）。 */
    private fun File.putSize(rel: String, bytes: Long): File {
        val f = File(this, rel)
        f.parentFile?.mkdirs()
        RandomAccessFile(f, "rw").use { it.setLength(bytes) }
        return f
    }

    private fun stepOf(n: Int, tool: String, args: JSONObject = JSONObject(), vararg targets: String) =
        RehearsalStep(n, ToolCall(id = "c$n", name = tool, arguments = args), targets.toList())

    private fun planOf(vararg steps: RehearsalStep, runId: String = "run1", taskKey: String = "task-1") =
        RehearsalPlan(runId = runId, taskKey = taskKey, workspaceRoot = workspace, steps = steps.toList())

    private fun run(
        plan: RehearsalPlan,
        availableBytes: Long = roomy,
        env: EnvReading = okEnv,
        stop: () -> Boolean = { false },
    ): RehearsalReport = RehearsalRunner.run(plan, cache, env, availableBytes, stop)

    /** 此刻 `cacheDir` 里还剩哪些文件（跑中途抓的快照用它；跑完应当是空）。 */
    private fun leftoverFiles(): List<File> = cache.walkTopDown().filter { it.isFile }.toList()

    private fun rhRoot(runId: String = "run1"): File =
        File(File(cache, RehearsalSandbox.ROOT_DIR_NAME), RehearsalSandbox.RUN_ID_PREFIX + runId)

    // ── 判据①：真目录零写入（mtime／size 不变）──────────────────────────────

    @Test
    fun `判据1_写侧四种动作跑完_真盘的内容长度与mtime一格没动`() {
        val a = workspace.put("notes/a.md", "第一行\n第二行\n第三行\n")
        val b = workspace.put("notes/b.md", "别的一份\n")
        val before = listOf(a, b).map { Triple(it.absolutePath, it.length(), it.lastModified()) }

        val report = run(
            planOf(
                stepOf(1, "edit_file", JSONObject().put("op", "replace_lines")
                    .put("from_line", 1).put("to_line", 1).put("content", "副本里的新第一行"), "notes/a.md"),
                stepOf(2, "write_file", JSONObject().put("content", "整篇覆盖").put("overwrite", true), "notes/b.md"),
                stepOf(3, "move_path", JSONObject(), "notes/b.md", "notes/moved.md"),
                stepOf(4, "delete_path", JSONObject().put("permanent", true), "notes/a.md"),
            ),
        )

        assertEquals("真盘两格都不许变：" + report.steps.map { it.tool to it.ok }, true, report.realUntouched)
        val after = listOf(a, b).map { Triple(it.absolutePath, it.length(), it.lastModified()) }
        assertEquals(before, after)
        assertEquals("内容也必须逐字不变", "第一行\n第二行\n第三行\n", a.readText())
        assertEquals("别的一份\n", b.readText())
        assertFalse("真工作区里不许长出副本根", File(workspace, RehearsalSandbox.ROOT_DIR_NAME).exists())
    }

    // ── 判据②：副本只在 rehearsal/ 且事后消失 ───────────────────────────────

    @Test
    fun `判据2_副本只长在cacheDir的rehearsal下_跑完两边都清空`() {
        workspace.put("notes/a.md", "甲乙丙\n")
        var midRun: List<File> = emptyList()
        var rootWasThere = false

        val report = run(
            planOf(
                stepOf(1, "write_file", JSONObject().put("content", "第一步").put("overwrite", true), "notes/a.md"),
                stepOf(2, "edit_file", JSONObject().put("op", "append").put("content", "第二步"), "notes/a.md"),
            ),
            // `stop` 在步与步之间问一次 ⇒ 这一刻副本树还活着，是唯一能拍到"它在哪"的窗口。
            stop = {
                if (midRun.isEmpty()) {
                    midRun = leftoverFiles()
                    rootWasThere = rhRoot().isDirectory
                }
                false
            },
        )

        assertTrue("中途快照里副本根应当存在", rootWasThere)
        assertTrue("中途确实有复制进来的原件", midRun.isNotEmpty())
        assertTrue(
            "跑中途的每个文件都必须在 " + rhRoot().absolutePath + " 里面",
            midRun.all { it.absolutePath.startsWith(rhRoot().absolutePath) },
        )
        assertEquals("跑完一个文件都不许留下", emptyList<File>(), leftoverFiles())
        assertFalse(File(cache, RehearsalSandbox.ROOT_DIR_NAME).exists())
        assertTrue(report.cleanedBytes > 0L)
        assertTrue(report.copyBytesPeak > 0L)
    }

    // ── 判据③：>8MB 不复制（断言无副本），但仍算进影响面 ─────────────────────

    @Test
    fun `判据3_超过8MB的对象只做元数据_副本里没有它但仍报会改`() {
        val big = workspace.putSize("media/big.bin", RehearsalRules.METADATA_ONLY_BYTES + 1024L * 1024L)
        val report = run(
            planOf(
                stepOf(1, "edit_file", JSONObject().put("op", "append").put("content", "真实跑会追加这点"), "media/big.bin"),
            ),
        )

        assertEquals(1, report.metaSteps)
        assertEquals(StepKind.METADATA, report.steps.single().kind)
        assertEquals("没复制 ⇒ 副本峰值只能是 0 字节", 0L, report.copyBytesPeak)
        assertEquals("没复制 ⇒ 清理量只能是 0 字节", 0L, report.cleanedBytes)
        assertEquals("但真实跑会改它：影响面必须算它一条", listOf("media/big.bin"), report.listedPaths)
        assertEquals("原件仍是 8MB+1MB 这么大，一格没被推演写过", 9L * 1024L * 1024L, big.length())
        assertTrue(
            "元数据那格是实测出来的：大小／可写／首尾抽样都得有",
            report.steps.single().note.contains("9216KB") && report.steps.single().note.contains("可写"),
        )
    }

    // ── 判据④：空间不足 ⇒ 降级为纯计划层，且报告明说 ─────────────────────────

    @Test
    fun `判据4_配额装不下原件_整趟降级为纯计划层并在标题行说清`() {
        workspace.put("notes/a.md", "x".repeat(2000))
        // 可用 1000B ⇒ 配额 50B，装不下 2000B 的原件（派单 §二②：不足就降级并明说）。
        val report = run(
            planOf(stepOf(1, "write_file", JSONObject().put("content", "甲"), "notes/a.md")),
            availableBytes = 1000L,
        )

        assertEquals(RehearsalMode.PLAN_ONLY, report.mode)
        assertTrue(report.degraded)
        val lines = report.lines()
        assertTrue(lines[0], lines[0].contains("纯计划层预演"))
        assertTrue(lines[0], lines[0].contains("未做文件预演"))
        assertTrue(lines[0], lines[0].contains("配额"))
        assertEquals("降级那一趟一步都不该跑", 0, report.readSteps + report.writeSteps + report.metaSteps)
        assertTrue(report.steps.all { it.kind == StepKind.NOT_RUN })
        assertEquals("清单退回计划声明，仍然数得出 N", listOf("notes/a.md"), report.listedPaths)
        assertTrue(lines.last(), lines.last().contains("清单来自计划声明"))
        assertEquals(emptyList<File>(), leftoverFiles())
    }

    @Test
    fun `判据4b_可用空间读不到按零配额算_也是降级而不是硬跑或抛`() {
        workspace.put("notes/a.md", "甲\n")
        val report = run(
            planOf(stepOf(1, "write_file", JSONObject().put("content", "乙"), "notes/a.md")),
            availableBytes = 0L,
        )
        assertEquals(RehearsalMode.PLAN_ONLY, report.mode)
        assertTrue(report.degradeNote, report.degradeNote.contains("建副本没有落点"))
        assertEquals(emptyList<File>(), leftoverFiles())
    }

    // ── 判据⑤：取消之后副本清空 + 报告写"已取消" ────────────────────────────

    @Test
    fun `判据5_第一步之前就收到取消_副本清空且报告写已取消`() {
        workspace.put("notes/a.md", "甲\n")
        val report = run(
            planOf(stepOf(1, "write_file", JSONObject().put("content", "乙"), "notes/a.md")),
            stop = { true },
        )
        assertTrue(report.cancelled)
        assertTrue(report.steps.all { it.kind == StepKind.NOT_RUN })
        assertTrue(report.lines().any { it.contains("已取消") })
        assertEquals("取消也不许留半截副本", emptyList<File>(), leftoverFiles())
        assertTrue(report.realUntouched)
    }

    @Test
    fun `判据5b_跑到一半取消_已跑的留着剩下的标没跑且副本清空`() {
        workspace.put("notes/a.md", "第一行\n第二行\n")
        var asks = 0
        val report = run(
            planOf(
                stepOf(1, "edit_file", JSONObject().put("op", "append").put("content", "第二段"), "notes/a.md"),
                stepOf(2, "edit_file", JSONObject().put("op", "append").put("content", "第三段"), "notes/a.md"),
                stepOf(3, "edit_file", JSONObject().put("op", "append").put("content", "第四段"), "notes/a.md"),
            ),
            // 第一次问（第 1 步之前）放行，第二次问（第 2 步之前）取消 ⇒ 恰好跑过一步。
            stop = { asks++; asks > 1 },
        )
        assertTrue(report.cancelled)
        assertEquals(1, report.steps.count { it.kind != StepKind.NOT_RUN })
        assertEquals(2, report.steps.count { it.kind == StepKind.NOT_RUN })
        assertNull("没跑到的步不算卡点：它只是没跑到", report.riskiestStep)
        val line = report.lines().first { it.contains("卡点") }
        assertTrue(line, line.contains("1 步跑通") && line.contains("另 2 步没跑到"))
        assertEquals(emptyList<File>(), leftoverFiles())
        assertTrue(report.lines().any { it.contains("已取消") })
    }

    @Test
    fun `判据3b_中途抛异常_副本照样清空_道也还回去`() {
        workspace.put("notes/a.md", "甲\n")
        workspace.put("notes/b.md", "乙\n")
        var asks = 0
        // 派单 §四③说的是"结束／失败两种路径都被清"：正常return那条判据2已经钉了，
        // 这一条钉**抛出去**那条——清理不在 finally 里的话副本就留在机上没人管了。
        val boom = runCatching {
            run(
                planOf(
                    stepOf(1, "edit_file", JSONObject().put("op", "append").put("content", "补一段"), "notes/a.md"),
                    stepOf(2, "edit_file", JSONObject().put("op", "append").put("content", "再补一段"), "notes/b.md"),
                ),
                stop = {
                    asks++
                    if (asks == 2) throw IllegalStateException("测试用：这一趟探针抛了")
                    false
                },
            )
        }
        assertTrue("异常要如实往上交（吞了就把事故报成跑完）：$boom", boom.isFailure)
        assertEquals("失败路径也不许留副本", emptyList<File>(), leftoverFiles())
        assertNull("抛完还得把串行道还回去，否则同任务的影子永远排队", RehearsalLane.current("task-1"))
    }

    // ── 判据⑥：报告数字来自实测（与一次独立重数比）──────────────────────────

    @Test
    fun `判据6_两个文件复制进副本只有一个真被改_N只能是1不是2`() {
        val a = workspace.put("notes/a.md", "第一行\n第二行\n")
        val b = workspace.put("notes/b.md", "另一份\n")
        val report = run(
            planOf(
                // 这一步改成了 a.md。
                stepOf(1, "edit_file", JSONObject().put("op", "replace_lines")
                    .put("from_line", 1).put("to_line", 1).put("content", "改了的一行"), "notes/a.md"),
                // 这一步在副本里跑挂（行号越界）⇒ b.md 复制了但一格没动。
                stepOf(2, "edit_file", JSONObject().put("op", "insert_after")
                    .put("from_line", 999).put("content", "不该出现"), "notes/b.md"),
            ),
        )
        assertEquals("两步都在副本里真写过", 2, report.writeSteps)
        assertEquals("只有真改了的那个算影响面：" + report.listedPaths, listOf("notes/a.md"), report.listedPaths)
        assertEquals(1, report.filesToChange)
        assertTrue("副本里确实存过两个原件", report.copyBytesPeak >= a.length() + b.length())
        assertEquals(2, report.riskiestStep?.n ?: -1)
        assertTrue(report.elapsedMs >= 0L)
    }

    @Test
    fun `判据6b_等长覆盖也算改了_只比大小会漏报`() {
        workspace.put("notes/a.md", "旧的九字AAAAA\n")
        val report = run(
            planOf(
                stepOf(1, "edit_file", JSONObject().put("op", "replace_lines")
                    .put("from_line", 1).put("to_line", 1).put("content", "新的九字BBBBB"), "notes/a.md"),
            ),
        )
        assertEquals(listOf("notes/a.md"), report.listedPaths)
        assertEquals(1, report.filesToChange)
    }

    @Test
    fun `判据6c_只读步真读了盘_内容来自真文件而不是编的`() {
        workspace.put("notes/real.md", "这一句只有真盘上有：甲乙丙丁\n")
        workspace.put("notes/b.md", "不被声明\n")
        val report = run(
            planOf(
                stepOf(1, "read_file", JSONObject().put("offset", 0).put("limit", 10), "notes/real.md"),
                stepOf(2, "list_dir", JSONObject(), "notes"),
            ),
        )
        assertEquals(2, report.readSteps)
        assertEquals(0, report.writeSteps)
        assertTrue(report.steps[0].note.contains("甲乙丙丁"))
        assertTrue(report.steps[1].note.contains("real.md"))
        assertTrue(report.realUntouched)
        assertEquals(emptyList<File>(), leftoverFiles())
    }

    // ── 派单【隔离】①：交叉读取必须被拒 ─────────────────────────────────────

    @Test
    fun `隔离1_推演去读影子箱那棵树_直接拒并且不碰真盘`() {
        workspace.put("notes/a.md", "甲\n")
        workspace.put("${FileShadowSandbox.SHADOW_DIR_NAME}/op1/notes/a.md", "影子的副本\n")
        val report = run(
            planOf(stepOf(1, "read_file", JSONObject(), "${FileShadowSandbox.SHADOW_DIR_NAME}/op1/notes/a.md")),
        )
        assertEquals(StepKind.UNSUPPORTED, report.steps.single().kind)
        assertFalse(report.steps.single().ok)
        assertTrue(report.steps.single().note, report.steps.single().note.contains("影子箱"))
        assertEquals(emptyList<File>(), leftoverFiles())
    }

    @Test
    fun `隔离1b_声明路径指向军师自己的根_也拒_不做递归预演`() {
        workspace.put("notes/a.md", "甲\n")
        val report = run(
            planOf(stepOf(1, "read_file", JSONObject(), "rehearsal/rh-run1/notes/a.md"), runId = "run1"),
        )
        assertEquals(1, report.unsupportedSteps)
        assertTrue(report.steps.single().note, report.steps.single().note.contains("军师自己的根"))
        assertEquals(emptyList<File>(), leftoverFiles())
    }

    // ── 派单 §二③：启动闸拦下时一步不跑、一个字节不占 ────────────────────────

    @Test
    fun `启动闸_电量低于20%时不启动_不建副本并把理由写进行里`() {
        workspace.put("notes/a.md", "甲\n")
        val report = run(
            planOf(stepOf(1, "write_file", JSONObject().put("content", "乙"), "notes/a.md")),
            env = EnvReading(batteryPercent = 19, thermalStatus = 0, hasNetwork = true),
        )
        assertTrue(report.blocked)
        assertEquals(StartBlock.LOW_BATTERY, report.block)
        val lines = report.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].contains("推演未启动") && lines[0].contains("电量不足"))
        assertTrue(lines[1], lines[1].contains("一步没跑"))
        assertEquals(emptyList<File>(), leftoverFiles())
    }

    @Test
    fun `隔离4_同任务的影子正占着道_这一次不叠上去且没把别人的道还掉`() {
        workspace.put("notes/a.md", "甲\n")
        assertTrue(RehearsalLane.tryBegin("task-1", RehearsalLaneOwner.SHADOW))
        val report = run(planOf(stepOf(1, "write_file", JSONObject().put("content", "乙"), "notes/a.md")))
        assertEquals(StartBlock.BUSY, report.block)
        assertTrue(report.blocked)
        assertTrue(report.blockNote, report.blockNote.contains("影子"))
        assertEquals(emptyList<File>(), leftoverFiles())
        assertEquals(
            "抢不到道的那一位不许把持道者的道还掉",
            RehearsalLaneOwner.SHADOW, RehearsalLane.current("task-1"),
        )
    }

    // ── 场地闸与勘验基线（御裁 2026-11-04 04:29 的 §一⑥／04:36 的「勘验」接线）────

    @Test
    fun `同场地已有两趟在跑_第三趟排队且报告点名那两位`() {
        workspace.put("notes/a.md", "甲\n")
        val site = workspace.absolutePath
        assertTrue(RehearsalSite.tryEnter(site, "rh-甲趟"))
        assertTrue(RehearsalSite.tryEnter(site, "rh-乙趟"))
        val report = run(planOf(stepOf(1, "write_file", JSONObject().put("content", "乙"), "notes/a.md")))
        assertEquals(StartBlock.BUSY, report.block)
        assertTrue(report.blocked)
        assertTrue(report.blockNote, report.blockNote.contains("rh-甲趟"))
        assertTrue(report.blockNote, report.blockNote.contains("rh-乙趟"))
        assertEquals("排队这一趟不许碰真盘", "甲\n", File(workspace, "notes/a.md").readText())
        assertEquals(emptyList<File>(), leftoverFiles())
        assertEquals("排队的这一位不许把别人的名额吞掉", 2, RehearsalSite.count(site))
        assertNull("道占了一半就退，也得还干净", RehearsalLane.current("task-1"))
    }

    @Test
    fun `跑完一趟场地名额就还回去_抛出去那条也还`() {
        workspace.put("notes/a.md", "甲\n")
        val site = workspace.absolutePath
        run(planOf(stepOf(1, "write_file", JSONObject().put("content", "乙"), "notes/a.md")))
        assertEquals("正常收工必须把名额交回场地", 0, RehearsalSite.count(site))
        var asks = 0
        runCatching {
            run(
                planOf(stepOf(1, "edit_file", JSONObject().put("op", "append").put("content", "补一段"), "notes/a.md")),
                stop = {
                    asks++
                    if (asks == 1) throw IllegalStateException("测试用：这一趟探针抛了")
                    false
                },
            )
        }.also { assertTrue("异常照上抛这条前提没变：$it", it.isFailure) }
        assertEquals("抛出去那条路径同样得把名额交回", 0, RehearsalSite.count(site))
    }

    @Test
    fun `文件预演跑完_清单交给勘验当事后核验基线`() {
        workspace.put("notes/a.md", "甲\n")
        workspace.put("notes/b.md", "乙\n")
        val report = run(
            planOf(
                stepOf(1, "edit_file", JSONObject().put("op", "append").put("content", "补一段"), "notes/a.md"),
                stepOf(2, "write_file", JSONObject().put("content", "丙\n"), "notes/c.md"),
            ),
        )
        assertTrue(RehearsalBaselineStore.has("rh-run1"))
        assertEquals(
            "勘验手里那份必须就是报告里这份（两处口径不许分叉）",
            report.listedPaths, RehearsalBaselineStore.baseline("rh-run1"),
        )
        assertEquals(
            "实际改的就是清单里这些 ⇒ 勘验判对得上",
            "对得上：${report.listedPaths.size} 条一条不差",
            RehearsalBaselineStore.verdict("rh-run1", report.listedPaths),
        )
    }

    @Test
    fun `降级那趟不交基线_它一步文件都没跑`() {
        workspace.put("notes/a.md", "甲\n")
        val report = run(
            planOf(stepOf(1, "edit_file", JSONObject().put("op", "append").put("content", "补一段"), "notes/a.md")),
            availableBytes = 1L,
        )
        assertEquals(RehearsalMode.PLAN_ONLY, report.mode)
        assertFalse(
            "纯计划层那份清单不是文件预演实测出来的，递过去就是把'少了/多了'判成假话",
            RehearsalBaselineStore.has("rh-run1"),
        )
    }

    @Test
    fun `取消那趟不交基线_它的清单是半截的`() {
        workspace.put("notes/a.md", "甲\n")
        workspace.put("notes/b.md", "乙\n")
        var asks = 0
        val report = run(
            planOf(
                stepOf(1, "edit_file", JSONObject().put("op", "append").put("content", "补一段"), "notes/a.md"),
                stepOf(2, "edit_file", JSONObject().put("op", "append").put("content", "再补一段"), "notes/b.md"),
            ),
            stop = {
                asks++
                asks > 1 // 第一步真跑过了（清单不是空的），第二步之前才取消 ⇒ 交过去的是半截清单
            },
        )
        assertTrue("前提：这一趟确实是取消掉的", report.cancelled)
        assertTrue(
            "前提：影响面不是空的——空清单本来就被纪律 1 拦住，那样这条测的就不是取消这一档",
            report.listedPaths.isNotEmpty(),
        )
        assertFalse(
            "半截清单不能当事后核验基线",
            RehearsalBaselineStore.has("rh-run1"),
        )
    }

    // ── 风险档来自生产表（不是推演自己给的估计）─────────────────────────────

    @Test
    fun `风险档取自生产矩阵_读是R0搬是R2`() {
        workspace.put("notes/a.md", "甲\n")
        val report = run(
            planOf(
                stepOf(1, "read_file", JSONObject(), "notes/a.md"),
                stepOf(2, "move_path", JSONObject(), "notes/a.md", "notes/moved.md"),
            ),
        )
        assertEquals(RiskClass.R0, report.steps[0].risk)
        assertEquals(RiskClass.R2, report.steps[1].risk)
    }

    @Test
    fun `不在覆盖的工具直说不覆盖_不混进跑通了`() {
        val report = run(
            planOf(
                stepOf(1, "shell", JSONObject(), "notes/a.md"),
                stepOf(2, "phone_dial", JSONObject(), "notes/a.md"),
            ),
        )
        assertEquals(2, report.unsupportedSteps)
        assertFalse(report.steps[0].ok)
        assertTrue(report.steps[0].note.contains("不在推演覆盖"))
        assertTrue(report.steps[1].ok)
        assertTrue(report.steps[1].note.contains("无文件副作用"))
    }
}
