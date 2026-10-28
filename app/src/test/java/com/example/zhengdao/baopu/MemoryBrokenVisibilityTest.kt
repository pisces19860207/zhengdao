// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十九批 B 段第 7 条（257 审计 A-P2-17）的复现用例。
//
// 缺陷原文：`MemoryStore.loadAll()` 里 `EntryCodec.parse(...).getOrNull()` 之后只有一句
// `if (entry != null) out += entry` —— 坏文件**静默跳过**；而同文件的 `reindex()` 反而有
// `brokenFiles`。两条读路一个报一个不报，于是「这条记忆无声消失」：召回路径上看不到条目、
// 也没有任何地方说它读坏了（用户手改坏 front-matter 当天就会撞上）。
//
// 本批要求（蓝总管 2026-10-28 裁定）：分两步——① MemoryStore 先把「读坏了」表达出来；
// ② 调用方接线（RunEngine 批准；BaopuChatController 不批，那文件在 258 手里 ⇒ 报告 §7 待补）。
package com.example.zhengdao.baopu

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryBrokenVisibilityTest {

    @Rule
    @JvmField
    val tmp: TemporaryFolder = TemporaryFolder()

    private lateinit var store: MemoryStore

    private fun setup() {
        store = MemoryStore(tmp.newFolder("app-全局"), tmp.newFolder("ws"))
    }

    private fun confirmed(id: String) = MemoryEntry(
        id = id,
        scope = MemoryScope.PROJECT,
        kind = MemoryKind.PREFERENCE,
        title = "称呼偏好",
        body = "用户希望被称呼为老师",
        tags = listOf("称呼"),
        sourceSession = "s7",
        sourceFile = "资料库/原始/设定.md",
        status = MemoryStatus.CONFIRMED,
        createdAt = 1_729_000_000_000L,
        updatedAt = 1_729_000_000_000L,
    )

    /** 一条好记忆＋一个读坏了的 .md（front-matter 缺尾界＝解析不出）。 */
    private fun seedOneGoodOneBroken(): File {
        setup()
        store.write(confirmed("1729000000000-称呼偏好"))
        val bad = File(store.projectDir(), "坏文件.md")
        bad.writeText("这不是一条合法记忆，没有 front-matter", Charsets.UTF_8)
        return bad
    }

    /**
     * 「读坏了」表达得出来（第一步：MemoryStore 自己）。
     *
     * **改前表现**：`loadAll()` 只给得出「还剩几条」，坏文件的名字在整条读路上没有任何出口
     * （`reindex()` 有 `brokenFiles`，但那是另一把口子，召回路径不经过它）⇒ 本条红。
     */
    @Test
    fun `坏文件有出口_loadAllWithBroken报得出名字`() {
        val bad = seedOneGoodOneBroken()
        val read = store.loadAllWithBroken()
        assertEquals("好记忆照常在内", 1, read.entries.size)
        assertEquals("坏文件必须报出文件名", listOf(bad.name), read.brokenFiles)
    }

    /** loadAll 的既有口径不许动（返回类型、条目集合、与 reindex 同源）。 */
    @Test
    fun `loadAll形态没变_坏文件仍不进条目`() {
        seedOneGoodOneBroken()
        assertEquals("loadAll 仍只给解析得动的条目", 1, store.loadAll().size)
        assertEquals(store.loadAll(), store.reindex().entries)
        assertEquals(store.loadAll(), store.loadAllWithBroken().entries)
        assertEquals(store.reindex().brokenFiles, store.loadAllWithBroken().brokenFiles)
    }

    /** 目录里全是合法文件 ⇒ broken 为空（不是把每条都说成坏）。 */
    @Test
    fun `全合法时broken为空`() {
        setup()
        store.write(confirmed("1729000000000-称呼偏好"))
        store.write(confirmed("1729000000000-赛道偏好").copy(title = "赛道偏好", body = "只做微头条赛道"))
        assertTrue(store.loadAllWithBroken().brokenFiles.isEmpty())
        assertEquals(2, store.loadAll().size)
    }

    /** 记忆目录还不存在（新机器）⇒ 条目空、broken 也空：「没有」与「坏了」不是一回事。 */
    @Test
    fun `目录不存在时不报坏`() {
        store = MemoryStore(tmp.newFolder("app2"), tmp.newFolder("ws2"))
        val read = store.loadAllWithBroken()
        assertTrue(read.entries.isEmpty())
        assertTrue("还没有目录不算读坏了", read.brokenFiles.isEmpty())
    }

    /**
     * 口径统一之后，读路径仍然**一个字节都不写**（MemoryQualityGateTest 的红线跟着钉住新的
     * `scan()`；改前两循环各自受检，合成一份之后必须继续受检，不能挪出闸的视野）。
     */
    @Test
    fun `共用扫描仍只读`() {
        val src = code("src/main/java/com/example/zhengdao/baopu/MemoryStore.kt")
        val body = src.substringAfter("private fun scan(): ReindexResult").substringBefore("\n    }")
        assertTrue("找不到 scan()：两条读路又分家了", body.contains("for (dir in listOf(globalDir(), projectDir()))"))
        listOf("writeText", "mkdirs", ".delete(", "execSQL").forEach { forbidden ->
            assertFalse("读路径出现写入动作 $forbidden", body.contains(forbidden))
        }
        // 三个入口共用同一份扫描（改前是两份各写一遍的循环 ⇒ 才会写出不同口径）
        listOf(
            "fun loadAll(): List<MemoryEntry> = scan().entries",
            "fun reindex(): ReindexResult = scan()",
            "fun loadAllWithBroken(): ReindexResult = scan()",
        ).forEach { sig ->
            assertTrue("三条读路没收到同一个 scan()：缺 $sig", src.contains(sig))
        }
    }

    /**
     * 第二步：调用方接线（RunEngine 批准；蓝总管要求「每处调用方改动都要有对应用例」）。
     *
     * **改前表现**：`RunEngine.kt` 里根本没有 `loadAllWithBroken`／「记忆读侧」这句日志，
     * 召回只经过 `loadAll()`，坏文件连一条日志都不留 ⇒ 本条红。
     * 为什么不写行为级用例：RunEngine 要 Context、要通道、要会话，JVM 里起不来（本仓无
     * Robolectric），而 `MemoryRules.trimToBudget(it.loadAll(), …)` 那行是闸钉住的口径，
     * 不许为了可测改成新 API —— 所以接线用源闸钉，判据本身由上面几条直测。
     */
    @Test
    fun `接线_引擎把坏文件说出去`() {
        val src = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertTrue("引擎必须问坏文件：RunEngine 没接 loadAllWithBroken()",
            src.contains("memoryStore?.loadAllWithBroken()?.brokenFiles"))
        assertTrue("坏文件要进日志（只报事实，不改判据）",
            src.lineSequence().any { it.contains("log(") && it.contains("记忆读侧") })
        // 闸钉的口径仍在（本批不许换掉它）
        assertTrue("loadAll＋trimToBudget 的口径不许动",
            src.contains("MemoryRules.trimToBudget(it.loadAll(), ContextAssembler.recallBudgetFor(windowTokens))"))
    }

    /** §7 待补项的钉子：界面侧（BaopuChatController）**本批没接**，接了就是越租。 */
    @Test
    fun `越租红线_控制器仍走老口子_待258合入后补`() {
        val src = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
        assertFalse("259 不许碰 258 手里的文件（报告 §7 记「待 258 合入后补」）",
            src.contains("loadAllWithBroken"))
    }

    private fun code(rel: String): String = SrcRoots.find(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")
}
