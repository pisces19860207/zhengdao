// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百一十八批 §2 **T1** 的钉桩：`AgentManifest.parse` 的块锚点每条只算一次。
//
// ## 为什么钉源码而不是"跑一遍看快没快"
// 快不快由 `PerfBaselineBenchTest` 的**同轮 A/B/C** 负责（跨轮 JVM 抖动 2~4 倍，
// 只有同一进程内的对照可信；数字见交付报告）。这个文件只管另一件事：
// **别让它退回 6 次全文扫描**——那正是本刀修掉的东西，退回去就前功尽弃。
//
// ## 两条钉的分工
// - [扫描次数只许两次]：形状钉。逐字段查找必须作用在"块"上，不是全文。
// - [字段取舍判据逐条不变]：语义钉。性能优化不许改行为，所以把改前后的判据一条条写死
//   （哪条丢、哪个字段回退成 id、空串与缺字段都成 null、字段不许串到别条）。
package com.example.zhengdao.perf

import com.example.zhengdao.ui.AgentManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentManifestParseScanGateTest {

    /** 主源码文本；单测工作目录可能是 `app/` 也可能是仓库根，两种写法都要认。 */
    private fun src(rel: String): String {
        val f = listOf(
            File("src/main/java/com/example/zhengdao/$rel"),
            File("app/src/main/java/com/example/zhengdao/$rel"),
            File("../../app/src/main/java/com/example/zhengdao/$rel"),
        ).firstOrNull { it.isFile } ?: error("gate cannot find $rel - pin path needs update")
        return f.readText()
    }

    private fun manifestText(): String {
        val f = listOf(
            File("../rootfs/agents.json"),
            File("rootfs/agents.json"),
            File("../../rootfs/agents.json"),
        ).firstOrNull { it.isFile } ?: error("gate cannot find rootfs/agents.json")
        return f.readText()
    }

    private fun parseBody(): String = src("ui/AgentManifest.kt")
        .substringAfter("fun parse(text: String): List<Entry> {")
        .substringBefore("\n    /** 已安装 Agent 的版本探测")

    @Test
    fun `T1_扫描次数只许两次`() {
        val body = parseBody()
        // **作用在全文上的扫描**只许两次：`findAll(text)` 取全部 id、`find(text)` 取本条的块。
        // 逐字段查找的是块，所以形参写 `(text)` 的地方一共 2 处；
        // 回到 6~7 处（每个字段各算一遍 block）就是本刀要修的那条，出现即红。
        assertEquals("parse: full-text scan count", 2, body.split("(text)").size - 1)
        // 逐字段查找作用在**块**上
        assertEquals("parse: per-field find on block count", 1, body.split("find(block)").size - 1)
        // 搬移前那个"每次调用都重算 block"的局部函数不许复活
        assertFalse("old str(id, key) signature is back", body.contains("fun str(id: String, key: String)"))
        // 真机坑：花括号转义必须原样留着（MagicOS/ICU 拒绝字符类内裸 }）。
        // 钉的是**源文件里的写法**：它在 Kotlin 字符串字面量内，故为双反斜杠形态。
        assertTrue("brace escape lost in block regex", body.contains("""[^\\}]*\\}"""))
    }

    @Test
    fun `T1_字段取舍判据逐条不变`() {
        val entries = AgentManifest.parse(manifestText())
        assertTrue("real manifest must parse entries", entries.size >= 3)
        // 判据 1：name / installCmd 缺一项 ⇒ 整条丢
        val noInstall = """{"version": 1, "agents": [{"id":"a","name":"甲","desc":"d"}]}"""
        assertEquals("missing installCmd must drop entry", emptyList<AgentManifest.Entry>(), AgentManifest.parse(noInstall))
        val noName = """{"version": 1, "agents": [{"id":"b","installCmd":"curl x"}]}"""
        assertEquals("missing name must drop entry", emptyList<AgentManifest.Entry>(), AgentManifest.parse(noName))
        // 判据 2：launchCmd 为空 ⇒ 回退成 id（不是空串）
        val bare = """{"version": 1, "agents": [{"id":"c","name":"丙","installCmd":"curl x"}]}"""
        val c = AgentManifest.parse(bare).single()
        assertEquals("blank launchCmd falls back to id", "c", c.launchCmd)
        assertEquals("empty npmPackage -> null", null, c.npmPackage)
        assertEquals("absent uninstall -> null", null, c.uninstallCmd)
        // 判据 3：字段只在**本条块内**找，不许串到别条
        val two = """{"version": 1, "agents": [""" +
            """{"id":"d","name":"丁","installCmd":"cmd-d"},""" +
            """{"id":"e","name":"戊","installCmd":"cmd-e","desc":"只有戊有 desc"}]}"""
        val byId = AgentManifest.parse(two).associateBy { it.id }
        assertEquals("entry d must not pick up e desc", "", byId["d"]?.desc)
        assertEquals("entry d installCmd", "cmd-d", byId["d"]?.installCmd)
        assertEquals("entry e installCmd", "cmd-e", byId["e"]?.installCmd)
    }
}
