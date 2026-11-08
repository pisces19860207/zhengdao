// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十二批（原第五百三十八批并入）· 模型小黑板的**行为判据**。
//
// 判据逐条对上（派工单 §五·二）：
//   ① board_write 后 board_read 读回**逐字一致**（整篇替换，不是追加）
//   ② 超 4KB / 20 行 ⇒ 拒写并给原因；边界值（正好 4096 字节 / 正好 20 行）放行
//   ③ 工具**不给**"从对话里搬一段"的口子（schema 只有一个 text）
//   ④ 写小黑板不产生**任何别的写入**（盘面净增 1 件，且不在任何记忆树下）
//   ⑤ 不参与判定：RiskMatrix 给的是 R0/R1、不是必问；GuardPipeline 源码里没有它
//   ⑥ 落账只记长度与行数，不记正文
//   ⑦ 点号开头 ⇒ 不计入「产物 N 件」（512 的 `isCountedProduct` 同一把尺）
//
// 证据等级说明（诚实交代）：判据④在派工单里写作「记忆表行数不变」，而记忆表是 SQLite，
// JVM 单测开不了（全仓没有 Robolectric）。本文件把它拆成两条能真跑的：
//   ④-a 源级：本工具族**不引用**任何记忆/账本写口（MemoryStore / MemoryTiers / BaopuDb / DecisionLedger…）；
//   ④-b 行为级：跑完 write 后**盘面净增恰好一件**（工作区树＋App 私有目录一起数），且路径就是便签。
// 比"行数不变"更强的一条是它连"别处偷偷多写了一件"也拦得住；反过来它证明不了
// "记忆表被原地改了一行"——那条留给有 SQLite 的环境（真机/CI），本批没验，写进报告挂账。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.ReflectionRunner
import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskCtx
import com.example.zhengdao.baopu.RiskDecision
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.RiskVerdict
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoardToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var wsSeq = 0

    /** 每个用例可能要多个工作区（同一名字会被 TemporaryFolder 判"已存在"）⇒ 递增命名。 */
    private fun ws(): File = tmp.newFolder("workspace${wsSeq++}")

    private fun write(text: String, ws: File = ws()): ToolOutcome =
        BoardWriteTool.run(JSONObject().put(BoardWriteTool.ARG_TEXT, text), ws)

    private fun read(ws: File): JSONObject {
        val out = BoardReadTool.run(ws)
        assertTrue("board_read 必须成功（拿到的：$out）", out is ToolOutcome.Ok)
        return JSONObject((out as ToolOutcome.Ok).text)
    }

    // ── ① 写什么读什么（整篇替换）────────────────────────────────────────────

    @Test
    fun `写入后读回逐字一致_且是整篇替换不是追加`() {
        val ws = ws()
        val first = "待办：\n1. 查 527 第四段\n2. 跑 detekt"
        assertTrue("首写必须成功", write(first, ws) is ToolOutcome.Ok)
        assertEquals("首写读回必须逐字一致", first, read(ws).optString("text"))

        val second = "只剩一件：先跑 detekt"
        assertTrue(write(second, ws) is ToolOutcome.Ok)
        val got = read(ws).optString("text")
        assertEquals("整篇替换：读回的就是第二份", second, got)
        assertFalse("不是追加：第一份的内容不许还在", got.contains("527 第四段"))
        // 盘上也只有一份（.board.md），没有 .board.md.1 之类
        val files = BoardStore.fileOf(ws).parentFile?.listFiles()?.map { it.name }?.toSet().orEmpty()
        assertEquals("产物目录里只该有便签一件（临时文件已改名）", setOf(BoardStore.FILE_NAME), files)
    }

    @Test
    fun `空串等于清空_读回是空串不是读不到`() {
        val ws = ws()
        write("先写点东西", ws)
        assertTrue(write("", ws) is ToolOutcome.Ok)
        val json = read(ws)
        assertEquals("", json.optString("text"))
        assertEquals("行数为 0", 0, json.optInt("lines"))
        assertEquals("字节为 0", 0, json.optInt("bytes"))
    }

    // ── ② 上限：拒写不截断；边界值放行 ────────────────────────────────────────

    @Test
    fun `超4KB拒写_原文不受损`() {
        val ws = ws()
        val original = "旧的几行，别丢"
        write(original, ws)
        val tooBig = "a".repeat(BoardRules.MAX_BYTES + 1)
        val out = write(tooBig, ws)
        assertTrue("超 4KB 必须是被拒（Failed），不是静默截断", out is ToolOutcome.Failed)
        val why = (out as ToolOutcome.Failed).error
        assertTrue("拒因要报字节数与上限：$why", why.contains("${BoardRules.MAX_BYTES + 1} 字节"))
        assertTrue("拒因要让人先删旧：$why", why.contains("删旧"))
        assertEquals("拒写之后盘上还是旧内容", original, read(ws).optString("text"))
    }

    @Test
    fun `超20行拒写_边界20行放行_4096字节放行`() {
        // 21 行 ⇒ 拒
        val lines21 = (1..21).joinToString("\n") { "L$it" }
        assertTrue("21 行必须被拒", write(lines21) is ToolOutcome.Failed)

        // 正好 20 行 ⇒ 放行
        val ws20 = ws()
        val lines20 = (1..20).joinToString("\n") { "L$it" }
        assertEquals(20, BoardRules.lineCount(lines20))
        assertTrue("正好 20 行必须放行", write(lines20, ws20) is ToolOutcome.Ok)
        assertEquals(lines20, read(ws20).optString("text"))

        // 末尾换行的 20 行也按 20 算（不然"20 行整"会被自己判成 21 行）
        val trailing = (1..20).joinToString("\n") { "L$it" } + "\n"
        assertEquals("末尾换行不额外算一行", 20, BoardRules.lineCount(trailing))

        // 正好 4096 字节 ⇒ 放行
        val wsBytes = ws()
        val exact = "a".repeat(BoardRules.MAX_BYTES)
        assertEquals(BoardRules.MAX_BYTES, BoardRules.byteCount(exact))
        assertTrue("正好 4096 字节必须放行", write(exact, wsBytes) is ToolOutcome.Ok)
        assertEquals(BoardRules.MAX_BYTES, read(wsBytes).optInt("bytes"))
    }

    @Test
    fun `中文按UTF8字节算_三字节一个字`() {
        assertEquals(3, BoardRules.byteCount("中"))
        // 1366 个汉字＝4098 字节 ⇒ 超限（按字符数算就会漏过去）
        assertTrue("按字符数算会假放行", write("中".repeat(1366)) is ToolOutcome.Failed)
        assertTrue("1365 个汉字＝4095 字节 ⇒ 放行", write("中".repeat(1365)) is ToolOutcome.Ok)
    }

    // ── ③ 没有"从对话搬一段"的口子 ───────────────────────────────────────────

    @Test
    fun `schema只有一个text参数_没有对话或消息入口`() {
        val ws = ws()
        val spec = BoardWriteTool.spec { ws }
        val props = spec.inputSchema.getJSONObject("properties")
        assertEquals("写工具只该有 text 一个参数（不许给'搬用户原话'留口子）", setOf("text"), props.keys().asSequence().toSet())
        assertEquals(setOf("text"), spec.inputSchema.getJSONArray("required").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        })
        // 读工具无参数
        assertEquals(0, BoardReadTool.spec { ws }.inputSchema.getJSONObject("properties").length())
    }

    // ── ④ 不产生别的写入（记忆/账本一概不碰）─────────────────────────────────

    @Test
    fun `写小黑板_盘面净增恰好一件_且不在记忆树下`() {
        val ws = ws()
        val filesDir = tmp.newFolder("files")
        val before = snapshot(ws) to snapshot(filesDir)
        assertTrue(write("记一笔中间态", ws) is ToolOutcome.Ok)
        val after = snapshot(ws) to snapshot(filesDir)

        val addedWs = after.first - before.first
        val addedFiles = after.second - before.second
        assertEquals("工作区里净增的只有便签一件：$addedWs", 1, addedWs.size)
        assertTrue(
            "便签必须落在 产物/ 下：$addedWs",
            addedWs.all { it.replace('\\', '/').endsWith("产物/${BoardStore.FILE_NAME}") },
        )
        assertTrue("App 私有目录一个字节都不许多：$addedFiles", addedFiles.isEmpty())
        assertFalse("便签不许落进任何记忆树", addedWs.any { it.contains("抱朴记忆") })
    }

    @Test
    fun `工具族不引用任何记忆或账本写口`() {
        val src = codeOnly(source("baopu/agent/tools/BoardTools.kt"))
        listOf(
            "MemoryStore", "MemoryTiers", "MemoryLayout.memoryDir", "BaopuDb",
            "AppendPersona", "DecisionLedger", "ReflectionRunner", "抱朴记忆",
        ).forEach { banned ->
            assertFalse("小黑板工具不许碰「$banned」（判据④：不进记忆、不写账）", src.contains(banned))
        }
        // 落点必须经 MemoryLayout.productsDir（与全仓同一口径），不许自己拼 "Download/证道"
        assertTrue("落点要走 MemoryLayout.productsDir", src.contains("MemoryLayout.productsDir"))
    }

    @Test
    fun `落账只记长度与行数_不记正文`() {
        val src = codeOnly(source("baopu/agent/tools/BoardTools.kt"))
        val logLines = src.split("\n").filter { it.contains("RunLog.log(") }
        assertTrue("本文件必须有落账（写成功那一行）", logLines.isNotEmpty())
        logLines.forEach { line ->
            assertFalse("任何一行落账都不许带正文（判据⑥）：$line", line.contains("\$text"))
            assertFalse("任何一行落账都不许带正文：$line", line.contains("\${text}"))
        }
        val success = logLines.filter { it.contains("board_write:") }
        assertEquals("成功那一行只该有一条", 1, success.size)
        assertTrue("落账要带字节数：${success.first()}", success.first().contains("bytes"))
        assertTrue("落账要带行数：${success.first()}", success.first().contains("lines"))
    }

    // ── ⑤ 不参与判定 ─────────────────────────────────────────────────────────

    @Test
    fun `风险档是R0与R1_不是必问_且GuardPipeline不认识它`() {
        val ws = ws()
        val read = BoardReadTool.spec { ws }
        val write = BoardWriteTool.spec { ws }
        assertEquals("读：R0", "R0", read.riskTier)
        assertEquals("写：R1（只写工作区里的一件便签）", "R1", write.riskTier)
        assertTrue("读：只读", read.readOnly)
        assertTrue("读：可并行", read.parallelSafe)
        assertFalse("写：不是只读", write.readOnly)
        assertFalse("写：不许并行", write.parallelSafe)

        // 矩阵必须有两行，且都不是「必问」
        val readVerdict = verdict("board_read")
        val writeVerdict = verdict("board_write")
        assertEquals(RiskClass.R0, readVerdict.risk)
        assertEquals(RiskClass.R1, writeVerdict.risk)
        assertEquals(
            "便签不触发必问（每次一张卡＝把'记一笔'变成打扰）",
            RiskDecision.PROCEED,
            writeVerdict.decision,
        )
        assertFalse("便签不该被标成未分类（那就是 fail-closed 必问了）", writeVerdict.unclassified)

        // GuardPipeline 源码里没有它：判定链不该知道有一块便签
        val guard = codeOnly(source("baopu/agent/GuardPipeline.kt"))
        assertFalse("GuardPipeline 不许读小黑板（判据⑤）", guard.contains("board_read"))
        assertFalse("GuardPipeline 不许读小黑板（判据⑤）", guard.contains("board_write"))
        assertFalse("GuardPipeline 不许读便签文件", guard.contains(".board.md"))
    }

    // ── ⑦ 不计入产物数 ───────────────────────────────────────────────────────

    @Test
    fun `点号件不计入产物数_与512游标同一把尺`() {
        val ws = ws()
        write("x", ws)
        val board = BoardStore.fileOf(ws)
        assertTrue("便签得真在盘上", board.isFile)
        assertTrue("落点文件名必须点号开头", board.name.startsWith("."))
        assertFalse("512 的 isCountedProduct 必须把它排除在产物数外", ReflectionRunner.isCountedProduct(board))
        // 同一把尺的对照：普通文件要算产物（否则这条判据对任何文件都"成立"）
        val normal = File(board.parentFile, "README.md").apply { writeText("x") }
        assertTrue("对照：普通产物文件要算数", ReflectionRunner.isCountedProduct(normal))
    }

    @Test
    fun `人工清空后读回空串_文件不再躺在盘上`() {
        val ws = ws()
        write("要被清掉的内容", ws)
        assertTrue("清空要成功", BoardStore.clear(ws))
        assertFalse("清空＝删文件，不是写空串（盘上不躺一件空便签）", BoardStore.fileOf(ws).exists())
        assertEquals("清空后读回空串", "", read(ws).optString("text"))
        // 清空一个本来就没有的：不报错（幂等）
        assertTrue("空便签上再清一次也得成", BoardStore.clear(ws))
    }

    @Test
    fun `没有便签就是空_不是错误`() {
        val ws = ws()
        assertEquals("没写过 ⇒ 空串", "", BoardStore.read(ws))
        val json = read(ws)
        assertEquals("空便签读回空串", "", json.optString("text"))
        assertFalse("没写过 ⇒ exists=false（与'写过但是空的'分开）", json.getBoolean("exists"))
    }

    @Test
    fun `便签位置被目录占了_不抛也不假装成空便签`() {
        // 盘上那件东西不是普通文件（比如被同名的目录占了位置）：读侧不许抛，
        // 写侧必须如实失败——而不是"看起来写成功了"。
        val ws = ws()
        val f = BoardStore.fileOf(ws)
        f.mkdirs()
        assertEquals("不是普通文件 ⇒ 读回空串（不抛）", "", BoardStore.read(ws))
        assertTrue("写不下去必须如实失败", write("x", ws) is ToolOutcome.Failed)
    }

    // ── 小件 ─────────────────────────────────────────────────────────────────

    /** 目录树快照（相对路径，排序后）：用来数"净增了几件"。 */
    private fun snapshot(root: File): Set<String> {
        if (!root.isDirectory) return emptySet()
        return root.walkTopDown().filter { it.isFile }
            .map { it.absolutePath.removePrefix(root.absolutePath).trim('/', '\\') }
            .toSet()
    }

    /** 矩阵判据（与 `RiskMatrixTest` 同一把尺：同一条 `classify` 入口）。 */
    private fun verdict(name: String, args: Map<String, Any?> = emptyMap()): RiskVerdict {
        val json = JSONObject()
        args.forEach { (k, v) -> json.put(k, v) }
        return RiskMatrix.classify(ToolCall("t", name, json), RiskCtx())
    }

    /** 生产源码（相对 `com/example/zhengdao/` 的后缀）：三种工作目录都试一遍（同本仓既有门禁的写法）。 */
    private fun source(suffix: String): String {
        val candidates = listOf(
            "src/main/java/com/example/zhengdao/$suffix",
            "../app/src/main/java/com/example/zhengdao/$suffix",
            "app/src/main/java/com/example/zhengdao/$suffix",
        )
        val f = candidates.map(::File).firstOrNull(File::isFile)
            ?: throw AssertionError("找不到 $suffix（工作目录 ${File(".").absolutePath}）")
        return f.readText(Charsets.UTF_8)
    }

    /** 去注释后的代码文本（结构性断言用；免得被注释绕过，也免得注释里的词误伤）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
}
