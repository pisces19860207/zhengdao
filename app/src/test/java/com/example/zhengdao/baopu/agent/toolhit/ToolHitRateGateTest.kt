// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 412 单 · 工具命中率门禁（真注册表 × 53 条语料 × 易混对互指）。
//
// 四道门：
//   ① 语料卫生：≥50 条（派工单判据①「构造 50 条」的硬下限）、id 唯一、
//      来源标签合法、retryOf 指向存在的条目、覆盖 ≥15 个工具；
//   ② 逐条判定与修前实测一致（注册表行为变了必须显式改语料，不许静默漂移）；
//   ③ 易混对互指 15/15（412 修后口径，钉死不回退）；
//   ④ 负例：断言必须真的会红（喂一个不存在的工具名/一段不互指的假 pair）。
//
// 顺带把读数表打到 stdout（CI 日志可见），并落 build/toolhit-report.md 供交付报告引用。
package com.example.zhengdao.baopu.agent.toolhit

import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.agent.AppendPersonaTool
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.SkillTools
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToolHitRateGateTest {

    // ── 装配：与 ToolSchemaAllTest 同一房型（全组解锁，权限保持原样） ──

    private fun registry(): ToolRegistry {
        val root = File("build/toolhit-ws").also { it.mkdirs() }
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        reg.register(SkillTools.spec(workspaceRoot = { root }, personaFiles = { PersonaFiles(File(root, "global"), root) }))
        reg.register(AppendPersonaTool.spec(personaFiles = { PersonaFiles(File(root, "global"), root) }))
        reg.currentSessionId = 412L
        reg.unlockStore().unlock(412L, ToolGroup.values().toList())
        return reg
    }

    private fun corpus(): List<HitCase> {
        val file = File("src/test/resources/toolhit/corpus.json")
        assertTrue("语料文件不存在：${file.absolutePath}", file.isFile)
        val arr = JSONArray(file.readText(Charsets.UTF_8))
        return (0 until arr.length()).map { HitCase.fromJson(arr.getJSONObject(it)) }
    }

    // ── 门 ①：语料卫生 ──────────────────────────────────────────────

    @Test
    fun `语料至少五十条_覆盖至少十五个工具_标签合法`() {
        val cases = corpus()
        assertTrue("语料只有 ${cases.size} 条，判据①硬下限是 50", cases.size >= 50)
        val ids = cases.map { it.id }
        assertEquals("语料 id 有重复", ids.size, ids.toSet().size)
        cases.forEach { c ->
            assertTrue("「${c.source}」不是合法来源标签（${HitCase.SOURCES}）", c.source in HitCase.SOURCES)
            assertTrue("「${c.expectVerdict}」不是合法判定（$c）", c.expectVerdict in HitVerdict.values().toSet())
        }
        val expectTools = cases.map { it.expectTool }.toSet()
        assertTrue(
            "语料只覆盖 ${expectTools.size} 个意图工具（要 ≥15，否则 Top-10 表没有分辨率）",
            expectTools.size >= 15,
        )
        val dangling = cases.filter { it.retryOf.isNotBlank() && it.retryOf !in ids.toSet() }
        assertTrue("retryOf 指向不存在的条目：${dangling.map { it.id }}", dangling.isEmpty())
    }

    // ── 门 ②：逐条判定与修前实测一致 ────────────────────────────────

    @Test
    fun `逐条判定与语料钉住的修前实测一致`() {
        val reg = registry()
        val cases = corpus()
        val drifted = cases.map { it to ToolHitJudge.classify(reg, it) }
            .filter { (c, v) -> c.expectVerdict != v }
        assertTrue(
            "这些条目的实际判定与语料钉住的值不一致（注册表行为变了，要显式更新语料并写明原因）：\n" +
                drifted.joinToString("\n") { (c, v) ->
                    "  ${c.id} 期望=${c.expectVerdict} 实际=$v schema错=${reg.validateCall(c.callTool, c.args)}  ${c.why.take(50)}"
                },
            drifted.isEmpty(),
        )
    }

    // ── 门 ③：易混对互指（412 修后口径 15/15） ──────────────────────

    @Test
    fun `易混对互指全覆盖`() {
        val gaps = ToolSurfaceAudit.audit(registry())
        assertTrue(
            "这些易混对的描述没有互指（模型只能靠名字猜）：\n" +
                gaps.joinToString("\n") { (f, t) -> "  $f 的描述里没有点名 $t" },
            gaps.isEmpty(),
        )
    }

    // ── 门 ④：负例——两道断言都必须真的会红 ────────────────────────

    @Test
    fun `负例_判定器抓得到选错与缺参`() {
        val reg = registry()
        // a 类：工具存在但调错名字
        val wrongTool = HitCase(
            id = "neg-a", intent = "负例", expectTool = "edit_file", callTool = "write_file",
            args = org.json.JSONObject("{\"path\":\"a.md\",\"content\":\"x\"}"),
            expectVerdict = HitVerdict.a, source = "构造", why = "负例", retryOf = "", trap = false,
        )
        assertEquals(HitVerdict.a, ToolHitJudge.classify(reg, wrongTool))
        // c 类：名字根本不存在
        val missing = wrongTool.copy(id = "neg-c", callTool = "bash", expectTool = "bash")
        assertEquals(HitVerdict.c, ToolHitJudge.classify(reg, missing))
        // b 类：缺必填（连 path 都不给）
        val badArgs = wrongTool.copy(id = "neg-b", callTool = "write_file", expectTool = "write_file", args = org.json.JSONObject())
        assertEquals(HitVerdict.b, ToolHitJudge.classify(reg, badArgs))
    }

    @Test
    fun `负例_互指断言抓得到没指路的那一个`() {
        val reg = registry()
        val fake = ToolSurfaceAudit.audit(reg, listOf("read_file" to "绝不存在的工具名_xyz"))
        assertEquals("负例必须恰好抓到一条", 1, fake.size)
        assertEquals("read_file", fake[0].from)
        assertEquals("绝不存在的工具名_xyz", fake[0].to)
        // 反空转：真 pair 里至少有一对现在是通过的（否则断言等于永远红，没有判别力）
        val real = ToolSurfaceAudit.audit(reg)
        assertTrue("互指审计全红 ⇒ 断言自身坏了", real.size < CONFUSABLE_PAIRS.size)
    }

    // ── 读数表：打给 CI 日志 ＋ 落盘给交付报告引用 ───────────────────

    @Test
    fun `读数表_落盘`() {
        val reg = registry()
        val cases = corpus()
        val stats = HitStats.of(reg, cases)
        val lines = buildString {
            appendLine("# 412 工具命中率语料读数（修后，构造口径）")
            appendLine()
            appendLine("- 语料 N=${stats.total}")
            appendLine("- 判定分布：${HitVerdict.values().joinToString(" ") { "${it.name}=${stats.counts[it] ?: 0}" }}")
            appendLine("- 失败率（语料口径）=%.1f%%".format(stats.failureRate() * 100))
            appendLine("- 重试链=${stats.retryChains}（重试率=%.1f%%）".format(stats.retryRate() * 100))
            appendLine()
            appendLine("## 按工具的失败条数（Top-10 数据源）")
            appendLine()
            appendLine("| 工具 | 失败条数 |")
            appendLine("|---|---|")
            stats.byTool.entries.sortedByDescending { it.value }.take(10)
                .forEach { (t, n) -> appendLine("| $t | $n |") }
            appendLine()
            appendLine("## 易混对互指")
            appendLine()
            ToolSurfaceAudit.audit(reg).forEach { (f, t) -> appendLine("- ❌ $f → $t") }
            if (ToolSurfaceAudit.audit(reg).isEmpty()) appendLine("- 15/15 全过（412 修后口径）")
        }
        File("build").mkdirs()
        File("build/toolhit-report.md").writeText(lines, Charsets.UTF_8)
        println(lines)
        assertEquals(53, stats.total)
        assertEquals(3, stats.retryChains)
    }
}
