// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第五百三十一批 · 子智能体 v2》六条判据。本文件按判据一条一组：
// ①上下文隔离（50 轮父会话 ⇒ 子 ≤ 父 1/3、无父早期原文）
// ②白名单（名单外/写类未授权/禁递归 各拒且落 REJECT 账）
// ③账归属（每行带 agent_id＋parent_id，可按子任务分组）
// ④回滚粒度（子 A 成功保留、子 B 失败全撤，复用 470 保险箱）
// ⑤既有口径不放宽（并行 ≤2、单次 ≤5）
//
// 全部 JVM 直测：turn/toolExec/时钟都是假件；④用临时目录跑真保险箱（真写盘、真回滚）。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.safety.RollbackVault
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SubAgentV2Test {

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private class ScriptedTurn(private val script: List<TurnOutcome>) : SubAgentTurn {
        val seen = mutableListOf<List<LlmMessage>>()
        private var i = 0

        override fun round(messages: List<LlmMessage>, tools: org.json.JSONArray?): TurnOutcome {
            seen += messages
            val out = script.getOrElse(i) { TurnOutcome.Failed("脚本用尽") }
            i++
            return out
        }
    }

    private fun task(
        id: String = "sg-1-rs-1-1",
        parentId: String = "sg-1-rs-1",
        seq: Int = 1,
        goal: String = "读文件写摘要",
        input: String = "",
        acceptance: String = "",
        tools: List<String> = listOf("read_file"),
        writes: List<String> = emptyList(),
    ) = SubTask(
        id = id, parentId = parentId, seq = seq, goal = goal, input = input,
        acceptance = acceptance, tools = tools, writes = writes,
    )

    private fun env(
        turn: SubAgentTurn,
        toolExec: (ToolCall) -> ToolOutcome = { ToolOutcome.Ok("结果") },
        parentTokens: Int = Int.MAX_VALUE,
        isWrite: (String) -> Boolean = { false },
        beforeWrite: (ToolCall) -> Unit = {},
        ledger: (SubAgentLedgerRow) -> Unit = {},
    ) = SubAgentEnv(
        turn = turn, toolExec = toolExec, toolsJson = null, now = { 0L },
        parentTokens = parentTokens, isWrite = isWrite, beforeWrite = beforeWrite, ledger = ledger,
    )

    private fun call(name: String, vararg args: Pair<String, Any?>): ToolCall =
        ToolCall("c-" + name, name, JSONObject().also { j -> args.forEach { (k, v) -> j.put(k, v) } })

    private val tmpDirs = mutableListOf<File>()

    private fun tmpRoot(): File {
        val f = Files.createTempDirectory("zd531-").toFile()
        tmpDirs += f
        return f
    }

    @After
    fun cleanUp() {
        tmpDirs.forEach { it.deleteRecursively() }
    }

    // ── 判据①：上下文隔离 ────────────────────────────────────────────────

    @Test
    fun `判据1_五十轮父会话_子上下文不超三分之一且无父早期原文`() {
        val parent = (1..50).map { n ->
            LlmMessage(LlmMessage.ROLE_USER, "父第${n}轮原文：" + "这一轮聊的是完全不同的话题。".repeat(8))
        }
        val parentTokens = SubAgentIsolation.tokensOf(parent)
        val child = SubAgentIsolation.seed(
            task(goal = "汇总 a.md", input = "资料/a.md 的前半段", acceptance = "200 字内"),
            parentTokens = parentTokens,
        )
        val childTokens = SubAgentIsolation.tokensOf(child)
        assertTrue(
            "子上下文必须 ≤ 父的 1/3（子=$childTokens，父=$parentTokens）",
            childTokens * SubAgentIsolation.SHARE <= parentTokens,
        )
        // 结构隔离：父的任何一轮原文都不许出现（连第一轮都不行，不只是「早期」）
        val joined = child.joinToString("\n") { it.content }
        assertFalse("子上下文里出现了父会话原文：$joined", joined.contains("父第"))
        assertNull(
            "父第一条消息的正文不许漏进子上下文",
            parent.firstOrNull { joined.contains(it.content) },
        )
        assertEquals("开局只有 system＋user 两条", 2, child.size)
    }

    @Test
    fun `判据1_父预算紧时裁剪input_不裁目标与验收`() {
        val long = "材料".repeat(1000) // 2000 字，正好是解析上限
        val t = task(goal = "读长材料", input = long, acceptance = "一段话")
        val capped = SubAgentIsolation.seed(t, parentTokens = 6_000) // 预算 2000
        val tokens = SubAgentIsolation.tokensOf(capped)
        assertTrue("裁剪后要进预算：$tokens", tokens <= SubAgentIsolation.childCap(6_000))
        val body = capped.last().content
        assertTrue("目标要留全", body.contains("读长材料"))
        assertTrue("验收要留全", body.contains("一段话"))
        assertTrue("材料要被裁短（原 2000 字）", !body.contains(long))
    }

    // ── 判据②：白名单（收窄兑现） ────────────────────────────────────────

    @Test
    fun `判据2_白名单外工具被拒并落REJECT账`() {
        val ledger = MemorySubAgentLedger()
        val executed = mutableListOf<String>()
        val turn = ScriptedTurn(
            listOf(
                TurnOutcome.Calls(listOf(call("browser_click", "selector" to "#buy")), ""),
                TurnOutcome.Text("收工"),
            ),
        )
        val r = SubAgentRunner(
            env(
                turn,
                toolExec = { executed += it.name; ToolOutcome.Ok("不该跑到这") },
                ledger = { ledger.append(it) },
            ),
        ).run(task(tools = listOf("read_file")))

        assertEquals(SubTaskStatus.DONE, r.status)
        assertTrue("名单外的工具一件都不许执行：$executed", executed.isEmpty())
        val rejects = ledger.rowsOf("sg-1-rs-1").filter { it.kind == SubAgentLedgerKind.REJECT }
        assertEquals("被拒要记账（一条）", 1, rejects.size)
        assertEquals("agent_id 是子任务 id", "sg-1-rs-1-1", rejects[0].agentId)
        assertEquals("parent_id 是分解 id", "sg-1-rs-1", rejects[0].parentId)
        assertEquals("browser_click", rejects[0].tool)
        assertFalse("被拒的行 ok=false", rejects[0].ok)
        assertTrue("拒因要点名名单：${rejects[0].detail}", rejects[0].detail.contains("白名单"))
        // 被拒也要回灌给模型（不是静默丢弃）
        val fed = turn.seen[1].joinToString("\n") { it.content }
        assertTrue("回灌里要有被拒原因：$fed", fed.contains("被拒") && fed.contains("browser_click"))
    }

    @Test
    fun `判据2_写类未授权被拒_显式授予才生效`() {
        // 没给 grant_writes ⇒ writes 为空 ⇒ write_file 不在名单里
        val t = task(tools = listOf("read_file"), writes = emptyList())
        val ledger = MemorySubAgentLedger()
        val executed = mutableListOf<String>()
        val turn = ScriptedTurn(
            listOf(
                TurnOutcome.Calls(listOf(call("write_file", "path" to "a.md", "content" to "x")), ""),
                TurnOutcome.Text("完了"),
            ),
        )
        SubAgentRunner(
            env(turn, toolExec = { executed += it.name; ToolOutcome.Ok("写了") }, ledger = { ledger.append(it) }),
        ).run(t)

        assertTrue("未授权的写类不许执行：$executed", executed.isEmpty())
        assertTrue(
            "要有一条写类被拒的账",
            ledger.rowsOf(t.parentId).any { it.kind == SubAgentLedgerKind.REJECT && it.tool == "write_file" },
        )
        // 授予之后同一把工具就进名单（数据说了算，不是代码）
        val granted = SubAgentWhitelist.effectiveWrites(
            listOf("write_file"), setOf("read_file", "write_file"), isWrite = { it == "write_file" },
        )
        assertEquals(listOf("write_file"), granted)
    }

    @Test
    fun `判据2_子任务调run_subtasks被拒_禁递归`() {
        val t = task(tools = listOf("read_file"), writes = listOf("write_file"))
        val ledger = MemorySubAgentLedger()
        val executed = mutableListOf<String>()
        val turn = ScriptedTurn(
            listOf(
                TurnOutcome.Calls(listOf(call(SubTaskTool.NAME, "subtasks" to org.json.JSONArray())), ""),
                TurnOutcome.Text("我自己做"),
            ),
        )
        val r = SubAgentRunner(
            env(turn, toolExec = { executed += it.name; ToolOutcome.Ok("不该跑到这") }, ledger = { ledger.append(it) }),
        ).run(t)

        assertEquals(SubTaskStatus.DONE, r.status)
        assertTrue("禁递归：子任务不许再拆：$executed", executed.isEmpty())
        val reject = ledger.rowsOf(t.parentId).first { it.kind == SubAgentLedgerKind.REJECT }
        assertEquals(SubTaskTool.NAME, reject.tool)
        assertTrue("拒因要点名禁递归：${reject.detail}", reject.detail.contains("禁递归"))
        // 就算有人把它塞进写名单，判定层也不放行
        val smuggled = t.copy(writes = listOf("write_file", SubTaskTool.NAME))
        assertTrue(
            "名单里塞了 run_subtasks 也照样拒",
            SubAgentWhitelist.decide(SubTaskTool.NAME, smuggled) is SubAgentWhitelist.Decision.Reject,
        )
    }

    // ── 判据③：账归属 ────────────────────────────────────────────────────

    @Test
    fun `判据3_每行带agent与parent_可按子任务分组`() {
        val ledger = MemorySubAgentLedger()
        val sink = { row: SubAgentLedgerRow -> ledger.append(row) }
        val a = task(id = "sg-9-rs-1-1", parentId = "sg-9-rs-1", seq = 1, tools = listOf("read_file"))
        val b = task(id = "sg-9-rs-1-2", parentId = "sg-9-rs-1", seq = 2, tools = listOf("list_dir"))

        SubAgentRunner(
            env(
                ScriptedTurn(
                    listOf(
                        TurnOutcome.Calls(listOf(call("read_file", "path" to "a.md")), ""),
                        TurnOutcome.Calls(listOf(call("write_file", "path" to "x")), ""),
                        TurnOutcome.Text("A 好了"),
                    ),
                ),
                ledger = sink,
            ),
        ).run(a)
        SubAgentRunner(
            env(
                ScriptedTurn(listOf(TurnOutcome.Calls(listOf(call("list_dir", "path" to ".")), ""), TurnOutcome.Text("B 好了"))),
                ledger = sink,
            ),
        ).run(b)

        val rows = ledger.rowsOf("sg-9-rs-1")
        assertTrue("两任务都要有账", rows.size >= 6)
        assertTrue("每行都要带 agent_id", rows.all { it.agentId.isNotBlank() })
        assertTrue("每行都要带 parent_id", rows.all { it.parentId == "sg-9-rs-1" })
        val grouped = SubAgentLedger.groupByAgent(rows)
        assertEquals("按子任务分成两组", setOf("sg-9-rs-1-1", "sg-9-rs-1-2"), grouped.keys)
        assertTrue("A 组里要有它自己那条被拒（write_file 未授权）",
            grouped.getValue("sg-9-rs-1-1").any { it.kind == SubAgentLedgerKind.REJECT })
        assertTrue("B 组里不许混进 A 的账",
            grouped.getValue("sg-9-rs-1-2").all { it.tool != "write_file" })
        assertEquals("被拒计数＝1", 1, SubAgentLedger.rejectedCount(rows))
        assertTrue("摘要行带子任务数与行数：${SubAgentLedger.summaryLine(rows)}",
            SubAgentLedger.summaryLine(rows).contains("2 条子任务"))
    }

    // ── 判据④：回滚粒度 ──────────────────────────────────────────────────

    /** ④ 的存储假件（协调器要一个 SubTaskStore；账不是本判据的重点）。 */
    private class FakeStore : SubTaskStore {
        val finished = mutableListOf<SubTaskResult>()

        override fun insertBatch(tasks: List<SubTask>, sessionId: Long, now: Long) = Unit

        override fun markRunning(task: SubTask, at: Long) = Unit

        override fun markFinished(
            result: SubTaskResult,
            goal: String,
            input: String,
            acceptance: String,
            tools: List<String>,
            attempts: Int,
        ) {
            finished += result
        }

        override fun childrenOf(parentId: String): List<SubTaskRecord> = emptyList()

        override fun ofSession(sessionId: Long): List<SubTaskRecord> = emptyList()
    }

    @Test
    fun `判据4_子A成功子B失败_A产物保留_B的写全撤`() {
        val root = tmpRoot()
        RollbackVault.install(root)
        // 现场：b.md 已存在（要被改回原样）；a.md 与两条新建文件都不存在
        val b = File(root, "b.md").apply { writeText("B原样") }
        val guard = VaultSubTaskWriteGuard(
            sessionKey = "session-7",
            workspaceRoot = root,
            isWrite = { it == "write_file" },
            now = { 1_000L },
        )
        val a = task(id = "sg-7-rs-1-1", parentId = "sg-7-rs-1", seq = 1, writes = listOf("write_file"))
        val taskB = task(id = "sg-7-rs-1-2", parentId = "sg-7-rs-1", seq = 2, writes = listOf("write_file"))

        val coordinator = SubAgentCoordinator(
            newRunner = { t ->
                // 路径用**绝对路径**给工具：生产链上装配层会先把相对路径改写成根下绝对
                // （SubAgentWrites.absolutize），这里照同一口径喂，才验得到「入库/回滚是同一目标」。
                fun abs(name: String) = File(root, name).absolutePath
                val turn: SubAgentTurn = if (t.id == a.id) {
                    ScriptedTurn(
                        listOf(
                            TurnOutcome.Calls(listOf(call("write_file", "path" to abs("a.md"), "content" to "A成品")), ""),
                            TurnOutcome.Calls(listOf(call("write_file", "path" to abs("a-new.md"), "content" to "A新建")), ""),
                            TurnOutcome.Text("A 完成"),
                        ),
                    )
                } else {
                    ScriptedTurn(
                        listOf(
                            TurnOutcome.Calls(
                                listOf(
                                    call("write_file", "path" to abs("b.md"), "content" to "B改坏"),
                                    call("write_file", "path" to abs("b-new.md"), "content" to "B新建"),
                                ),
                                "",
                            ),
                        ),
                    )
                }
                SubAgentRunner(
                    env(
                        turn,
                        toolExec = { c ->
                            val path = File(c.arguments.getString("path"))
                            path.parentFile?.mkdirs()
                            path.writeText(c.arguments.getString("content"))
                            ToolOutcome.Ok("写了 ${path.name}")
                        },
                        isWrite = { it == "write_file" },
                        beforeWrite = { c -> guard.beforeWrite(t, c) },
                    ),
                )
            },
            store = FakeStore(),
            isCancelled = { false },
            now = { 1_000L },
            log = {},
            writeGuard = guard,
        )
        val results = coordinator.runAll(
            TaskDecomposition("sg-7-rs-1", 7L, "两件各写一处", listOf(a, taskB), 1_000L),
        )

        assertEquals(2, results.size)
        assertTrue("A 要成功", results.first { it.id == a.id }.ok)
        assertFalse("B 要失败", results.first { it.id == taskB.id }.ok)
        // A 的产物：改的与新建的都在
        assertEquals("A成品", File(root, "a.md").readText())
        assertTrue("A 新建的也要留住", File(root, "a-new.md").isFile)
        // B 的写：改的回原样、新建的被删
        assertEquals("B 改的要撤回到原字节", "B原样", b.readText())
        assertFalse("B 新建的要撤掉", File(root, "b-new.md").exists())
    }

    // ── 判据⑤：既有口径不放宽 ────────────────────────────────────────────

    @Test
    fun `判据5_并行不超2_单次不超5_都没有放宽`() {
        assertEquals("并行上限还是 2", 2, SubAgentRules.MAX_PARALLEL_SUBAGENTS)
        assertEquals("单次条数上限还是 5", 5, SubAgentRules.MAX_SUBTASKS)
        assertEquals(2, SubAgentRules.nextStartable(running = 0, pending = 9, maxParallel = 2))
        assertEquals(0, SubAgentRules.nextStartable(running = 2, pending = 9, maxParallel = 2))
        val six = JSONObject().put(
            "subtasks",
            org.json.JSONArray().also { a -> (1..6).forEach { a.put(JSONObject().put("goal", "任务$it")) } },
        )
        assertTrue(
            "六条仍要被拒",
            SubAgentRules.parseDecomposition(six, setOf("read_file"), 1L, 0L, 1) is SubAgentRules.ParseResult.Rejected,
        )
    }

    // ── 路径口径（②的边界：写只落工作区） ────────────────────────────────

    @Test
    fun `写目标_越界拦_区内放_相对路径按根解析`() {
        val root = tmpRoot()
        val inside = SubAgentWrites.absoluteOf("产物/a.md", root)
        assertTrue("区内相对路径要放", SubAgentWrites.insideRoot(inside, root))
        val outside = File(root.parentFile, "越界.md").absolutePath
        assertFalse("区外要拦（含 .. 折叠）", SubAgentWrites.insideRoot(outside, root))
        assertFalse(
            ".. 折叠后也要拦",
            SubAgentWrites.insideRoot(File(root, "../逃逸.md").absolutePath, root),
        )
        val args = JSONObject()
            .put("path", "产物/a.md")
            .put("paths", org.json.JSONArray().put("产物/x.md"))
        val abs = SubAgentWrites.absolutize(args, root)
        assertTrue("单数键要改写", File(abs.getString("path")).isAbsolute)
        assertTrue("批量键第一项也要改写", File(abs.getJSONArray("paths").getString(0)).isAbsolute)
    }
}
