// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十二批 · 归因增量的判据靶：判据（ReflectionMisjudgeJudge／AttributionGate）一个字没动，
// 变的只有"判哪些行"——游标之后的新行。本文件逐条钉住派工单的六条判据：
//   ① 连跑两次 ⇒ 第二次落账 0 条
//   ② 追加一条新配对 ⇒ 只新增 1 条（判定 1／新增 1）
//   ③ 日志同时含「判定 N 条／实际新增落账 M 条」两个数
//   ④ 全本重建口存在且能重建（全本重判、不重复落账）
//   ⑤ 游标缺失／损坏／作废 ⇒ 退化全本一次，并记一行
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.ReflectionArchive
import com.example.zhengdao.baopu.ReflectionRunner
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttributionIncrementalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 一行的形状与 265 落的一模一样（`| 日期 | 类型 | 技能/落点 | 主张 | 证据 | 决定 |`）。 */
    private fun row(target: String, decision: String, claim: String = "该把${target}收进技能"): String =
        "| ${LocalDate.now()} | 新技能 | $target | $claim | 技能/$target/SKILL.md:L1 | $decision |"

    /** 把档案写成「n 个提出→否决配对」的样子（append-only：提出在前、否决在后）。 */
    private fun plantArchive(ws: File, pairs: Int): File {
        val file = ReflectionRunner.archiveFile(ws)
        file.parentFile?.mkdirs()
        val body = (1..pairs).flatMap { listOf(row("技能/手册$it", ReflectionArchive.DECISION_PENDING), row("技能/手册$it", ReflectionArchive.DECISION_REJECTED)) }
        file.writeText(ReflectionArchive.header() + "\n" + body.joinToString("\n") + "\n", Charsets.UTF_8)
        return file
    }

    /** 追加一条新配对（提出＋否决两行）——档案 append-only，历史行不改。 */
    private fun appendPair(ws: File, idx: Int): File {
        val file = ReflectionRunner.archiveFile(ws)
        file.appendText(
            row("技能/新增$idx", ReflectionArchive.DECISION_PENDING) + "\n" +
                row("技能/新增$idx", ReflectionArchive.DECISION_REJECTED) + "\n",
            Charsets.UTF_8,
        )
        return file
    }

    /** 第 n 次收工（wiring 层：日志与两个数都在这层）。 */
    private fun settle(ws: File, logs: MutableList<String>, full: Boolean = false): String {
        val before = logs.size
        AttributionWiring.onSettled(ws, full = full, log = { logs += it })
        return logs.drop(before).joinToString("\n")
    }

    // ── ① 连跑两次 ⇒ 第二次落账 0 条 ─────────────────────────────────────────

    @Test
    fun `判据①_连跑两次_第二次新增落账 0 条`() {
        JudgmentExperienceLedger.resetForTest()
        val ws = tmp.newFolder("ws-512-a")
        plantArchive(ws, pairs = 1)
        val logs = mutableListOf<String>()

        val first = settle(ws, logs)
        assertTrue("首跑是全本：没有游标就得全判，实际：$first", first.contains("本次判定 1 条") && first.contains("实际新增落账 1 条"))

        val second = settle(ws, logs)
        assertTrue("第二次判定 0 条，实际：$second", second.contains("本次判定 0 条"))
        assertTrue("第二次新增落账 0 条（判据①），实际：$second", second.contains("实际新增落账 0 条"))
        assertTrue("第二次认出游标起点", second.contains("游标 L"))
    }

    // ── ② 追加一条新配对 ⇒ 只新增 1 条（N=1/M=1）────────────────────────────

    @Test
    fun `判据②_追加一条新配对_只判 1 条且只新增 1 条`() {
        JudgmentExperienceLedger.resetForTest()
        val ws = tmp.newFolder("ws-512-b")
        plantArchive(ws, pairs = 3)
        val logs = mutableListOf<String>()

        val first = settle(ws, logs)
        assertTrue("首跑全本判 3 条，实际：$first", first.contains("本次判定 3 条"))

        appendPair(ws, idx = 4)
        val second = settle(ws, logs)
        assertTrue("只判新增那一条，实际：$second", second.contains("本次判定 1 条"))
        assertTrue("只新增一条，实际：$second", second.contains("实际新增落账 1 条"))
    }

    @Test
    fun `判据②补_同一毫秒两次收工_行 id 不许撞（撞了 distinctBy 会吞行）`() {
        // 2026-11-08 回归：ReflectionAttributor 的 seq 曾是实例字段，每次收工从 0 开始；
        // 同一毫秒两次收工 ⇒ id 都是 `att:<ms>:1` ⇒ readAll 的 distinctBy{id} 吞掉后一行，
        // 症状正是 CI 上间歇红的「本次判定 1 条／实际新增落账 0 条」。
        val ws = tmp.newFolder("ws-512-e")
        plantArchive(ws, pairs = 1)
        val rows = mutableListOf<ExperienceRow>()
        val store = object : JudgmentExperienceStore {
            override fun append(row: ExperienceRow) { rows += row }
            override fun readAll(): List<ExperienceRow> = rows.toList()
        }
        val fixedClock = { 1_700_000_000_000L }
        ReflectionAttributor(ws, store, log = {}, clock = fixedClock).attribute(full = true)
        appendPair(ws, idx = 2)
        ReflectionAttributor(ws, store, log = {}, clock = fixedClock).attribute(full = false)
        assertEquals("两对都该落一行", 2, rows.size)
        assertEquals("同一毫秒的两次收工，行 id 必须唯一（否则 distinctBy 会吞行）",
            rows.size, rows.map { it.id }.distinct().size)
    }

    // ── ③ 日志同时含「判定 N／新增 M」两个数 ─────────────────────────────────

    @Test
    fun `判据③_日志同时记判定数与新增数`() {
        JudgmentExperienceLedger.resetForTest()
        val ws = tmp.newFolder("ws-512-c")
        plantArchive(ws, pairs = 2)
        val logs = mutableListOf<String>()
        val line = settle(ws, logs)

        assertTrue("要有「本次判定」那个数，实际：$line", line.contains("本次判定"))
        assertTrue("要有「实际新增落账」那个数，实际：$line", line.contains("实际新增落账"))
        assertTrue("两个数都必须是本轮的读数，实际：$line", line.contains("本次判定 2 条") && line.contains("实际新增落账 2 条"))
    }

    // ── ④ 全本重建口存在且能重建（重判全部，但不重复落账）────────────────────

    @Test
    fun `判据④_全本重建口_重判全部且不重复落账`() {
        JudgmentExperienceLedger.resetForTest()
        val ws = tmp.newFolder("ws-512-d")
        plantArchive(ws, pairs = 2)
        val logs = mutableListOf<String>()
        settle(ws, logs)

        val rebuilt = settle(ws, logs, full = true)
        assertTrue("全本重建：两条否决行全部重判，实际：$rebuilt", rebuilt.contains("本次判定 2 条"))
        assertTrue("全本重建不许重复落账（第二道去重仍在），实际：$rebuilt", rebuilt.contains("实际新增落账 0 条"))
        assertTrue("日志要写明这次是全本，实际：$rebuilt", rebuilt.contains("全本重建"))
    }

    // ── ⑤ 游标缺失／损坏／作废 ⇒ 退化全本一次，并记一行 ─────────────────────

    @Test
    fun `判据⑤_游标缺失_退化全本一次并记一行`() {
        JudgmentExperienceLedger.resetForTest()
        val ws = tmp.newFolder("ws-512-e")
        plantArchive(ws, pairs = 2)
        val logs = mutableListOf<String>()
        settle(ws, logs)
        assertTrue("先得真落出游标", AttributionCursorFile.fileIn(ws).isFile)
        assertTrue("删掉它", AttributionCursorFile.fileIn(ws).delete())

        val again = settle(ws, logs)
        assertTrue("游标缺失 ⇒ 退化全本，实际：$again", again.contains("游标缺失") && again.contains("全本重建"))
        assertTrue("退化后仍按全本判 2 条，实际：$again", again.contains("本次判定 2 条"))
        assertTrue("已落过的账不再重复（去重仍是第二道保险）", again.contains("实际新增落账 0 条"))
    }

    @Test
    fun `判据⑤_游标损坏_退化全本一次并记一行`() {
        JudgmentExperienceLedger.resetForTest()
        val ws = tmp.newFolder("ws-512-f")
        plantArchive(ws, pairs = 1)
        val logs = mutableListOf<String>()
        settle(ws, logs)
        AttributionCursorFile.fileIn(ws).writeText("{这不是 json", Charsets.UTF_8)

        val again = settle(ws, logs)
        assertTrue("游标损坏 ⇒ 退化全本，实际：$again", again.contains("游标损坏") && again.contains("全本重建"))
        assertTrue("退化后判 1 条，实际：$again", again.contains("本次判定 1 条"))
    }

    @Test
    fun `判据⑤_游标行内容变了_作废并退化全本`() {
        JudgmentExperienceLedger.resetForTest()
        val ws = tmp.newFolder("ws-512-g")
        val archive = plantArchive(ws, pairs = 1)
        val logs = mutableListOf<String>()
        settle(ws, logs)
        // 档案被整本重写（行号还在、内容变了 ⇒ 指纹对不上）：游标必须自证作废
        archive.writeText(
            ReflectionArchive.header() + "\n" + row("技能/换过的", ReflectionArchive.DECISION_PENDING) + "\n" +
                row("技能/换过的", ReflectionArchive.DECISION_REJECTED) + "\n",
            Charsets.UTF_8,
        )

        val again = settle(ws, logs)
        assertTrue("作废 ⇒ 退化全本，实际：$again", again.contains("游标作废") && again.contains("全本重建"))
        assertTrue("按全本重判新内容，实际：$again", again.contains("本次判定 1 条"))
    }

    // ── 附：游标本身的两条性质（hash 稳、行号指向最后一条档案行）──────────────

    @Test
    fun `游标落在档案同一棵目录_行号指向最后一条档案行`() {
        JudgmentExperienceLedger.resetForTest()
        val ws = tmp.newFolder("ws-512-h")
        plantArchive(ws, pairs = 3)
        val result = ReflectionAttributor(ws, InMemoryJudgmentExperienceStore(), log = {}).attribute()

        assertTrue("首跑＝全本（没有游标）", result.fullRebuild)
        assertEquals("三条否决行全部送进判据", 3, result.judged)
        assertEquals("下一条新行从 0 之后起算", 0, result.fromLine)
        val text = ReflectionRunner.archiveFile(ws).readText()
        val cursor = AttributionCursorFile.load(ws, text.lines())
        // 档案：表头 3 行 ＋ 3 对 6 行 ＝ 9 行（末行后面那个换行不算一行）
        assertEquals(
            "游标指向最后一条档案行（不是文末空行）",
            text.lines().indexOfLast { it.isNotBlank() } + 1,
            cursor.cursor?.lastLineNo,
        )
        assertEquals("游标与档案同屋（产物/）", "产物", AttributionCursorFile.fileIn(ws).parentFile?.name)
    }

    @Test
    fun `同一 dedupKey 重复调用_不重复落账`() {
        val ws = tmp.newFolder("ws-512-i")
        plantArchive(ws, pairs = 1)
        val store = InMemoryJudgmentExperienceStore()
        val first = ReflectionAttributor(ws, store, log = {}).attribute()
        val second = ReflectionAttributor(ws, store, log = {}).attribute()

        assertEquals("第一次落一条", 1, first.persisted.size)
        assertTrue("第二次一条都不判（游标已到末行）", second.persisted.isEmpty())
        assertEquals("第二次判定 0 条", 0, second.judged)
        assertEquals("账上仍只有一条", 1, store.readAll().size)
        assertFalse("第二次不是全本", second.fullRebuild)
    }
}
