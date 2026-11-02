// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百四十六批 B · 339 三类事件里剩下的最后一段接线：**② 段路径守卫判越界的那一刻**发
// [AnomalyFactKind.OVER_SCOPE] 事实（派工单 346；御批口径与 339 同一张表）。
//
// 339 交采集器本体＋账，361 接了两类（账实不符／同任务三连 FAIL），本批接越权越界——
// 接线位＝`GuardPipeline.guardPath` 的两处红线：系统与凭据目录（`fileAccessCheck`）、
// 原件区只读（`rawRejection`）。
//
// ## 正例
// 两处红线各发恰好一条：出处＝这一笔动作自己的账键 `gp:<opId>`，机器键 `tool:`／`path:`／`redline:`；
// 事件只有事实列（七键、没有判决列）；**拒绝文案与 RunLog 一个字没改**（接线不许改行为）；
// 账落盘 append-only（内存清了盘还在）。
//
// ## 负例（造违规即红）
// 工作区内的正常读 ⇒ 零事件（发事件的是「拦下」，不是「调用」）；
// `path` 为空是参数错、不是越界 ⇒ 零事件；
// 动作键合不上数字锚 ⇒ 证据源自己拒收（本层不造第二把出处判据）；
// 源级：管线只喂采集器（一处 `AnomalyFeed.report(`、两处喂），代码面零引用判定层——
// 谁在管线里引 `law/AnomalyGate`，名册就得跟着手改，而工单明令不许手改（见 LawSourceWiringGateTest）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.inspect.AnomalyFactKind
import com.example.zhengdao.baopu.inspect.AnomalyFeed
import com.example.zhengdao.law.LawIo
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

class GuardScopeEscapeFeedTest {

    private lateinit var root: File
    private lateinit var ledgerRoot: File
    private val lines = mutableListOf<String>()

    /** 凭据目录（287 的硬下限之一）——与 `FileAccessPipelineTest` 用同一个靶子。 */
    private val credentialPath = "/storage/emulated/0/.ssh/id_rsa"

    private fun directRunner() = ToolRunner(Executors.newSingleThreadExecutor())

    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(ReadFileTool.spec())
        register(WriteFileTool.spec { root })
    }

    /** 生产注入形状（[raw] 就是 `KnowledgeBase.rawDir` 那格；null＝这台环境没有原件区）。 */
    private fun pipeline(raw: File?): GuardPipeline =
        GuardPipeline(registry(), { root }, { raw }, directRunner(), { lines += it })

    private fun readCall(id: String, path: String): ToolCall =
        ToolCall(id, "read_file", JSONObject().put("path", path))

    @Before
    fun setUp() {
        AnomalyFeed.resetForTest()
        root = Files.createTempDirectory("zd346-ws").toFile()
        ledgerRoot = Files.createTempDirectory("zd346-ledger").toFile()
        DecisionLedger.install(ledgerRoot)
        FileOpLog.resetForTest()
        FileOpLog.enterSession(SESSION_ID)
    }

    @After
    fun tearDown() {
        AnomalyFeed.resetForTest()
        DecisionLedger.uninstallForTest()
        FileOpLog.resetForTest()
        root.deleteRecursively()
        ledgerRoot.deleteRecursively()
    }

    // ── 正例：两处红线各发一条，只有事实列，行为一字没改 ────────────────────────

    @Test
    fun `红线一_凭据目录拦下_发一条越界事实_拒绝文案与日志照旧`() {
        val r = pipeline(raw = null).execute(readCall("c-346-1", credentialPath), { _, _ -> null })
        assertTrue("凭据目录必须拒", r is GuardPipeline.Result.Rejected)
        val reason = (r as GuardPipeline.Result.Rejected).reason
        assertTrue("文案还是 287 那一句（接线不许改行为）：$reason", reason.contains("凭据"))
        assertTrue("RunLog 那行还在：$lines", lines.any { it.contains("拒绝 read_file") })

        val ev = AnomalyFeed.replay()
        assertEquals("拦下一笔 ⇒ 恰好一条事实事件", 1, ev.size)
        val e = ev[0]
        assertEquals(AnomalyFactKind.OVER_SCOPE, e.kind)
        assertEquals("over_scope", e.kind.wire)
        assertEquals("出处＝这一笔动作自己的账键", "gp:c-346-1", e.provenance)
        assertTrue("机器键要有撞的是哪条红线：${e.evidence}", e.evidence.contains("redline:policy"))
        assertTrue("机器键要有工具名：${e.evidence}", e.evidence.contains("tool:read_file"))
        assertTrue("机器键要有解析后的目标：${e.evidence}", e.evidence.any { it.startsWith("path:") })
        assertTrue("事实＝守卫拦下了这句原文（不另写一套说法）", e.gist.contains(reason))
        assertFalse("证据源不许顺手判决：${e.gist}", e.gist.contains("灾异") || e.gist.contains("紧急"))
        assertEquals(
            "事实事件的列必须是那七样（没有判决列）",
            setOf("v", "id", "at", "kind", "src", "gist", "ev"),
            e.toJson().keys().asSequence().toSet(),
        )
    }

    @Test
    fun `红线二_原件区写入拦下_发一条且redline记raw_原件一个字节没动`() {
        val raw = File(root, "资料库/原始").apply { mkdirs() }
        val target = File(raw, "book.txt").apply { writeText("原件") }
        val call = ToolCall(
            "c-346-2",
            "write_file",
            JSONObject().put("path", target.absolutePath).put("content", "改原件"),
        )
        val r = pipeline(raw = raw).execute(call, { _, _ -> null })
        assertTrue("原件区只读：全盘可写也翻不了它", r is GuardPipeline.Result.Rejected)

        val ev = AnomalyFeed.replay()
        assertEquals(1, ev.size)
        assertEquals("gp:c-346-2", ev[0].provenance)
        assertTrue("撞的是原件区红线：${ev[0].evidence}", ev[0].evidence.contains("redline:raw"))
        assertEquals("动作未发生（事件说的是「拦下」，不是「做过」）", "原件", target.readText())
    }

    @Test
    fun `账落盘_append_only_内存清了盘还在`() {
        pipeline(raw = null).execute(readCall("c-346-3", credentialPath), { _, _ -> null })
        assertEquals(1, AnomalyFeed.replay().size)
        AnomalyFeed.resetForTest()
        val onDisk = AnomalyFeed.replay()
        assertEquals("内存清了盘还在（与 208 账本同一纪律）", 1, onDisk.size)
        assertEquals("gp:c-346-3", onDisk[0].provenance)
        assertEquals(AnomalyFactKind.OVER_SCOPE, onDisk[0].kind)
    }

    // ── 负例：不发事件的三件事 ─────────────────────────────────────────────────

    @Test
    fun `放行不发_工作区内的正常读_零事件`() {
        File(root, "资料库").mkdirs()
        File(root, "资料库/设定.md").writeText("工作区里的内容")
        val r = pipeline(raw = null).execute(readCall("c-346-4", "资料库/设定.md"), { _, _ -> null })
        assertTrue("正常读要过：$r", r is GuardPipeline.Result.Done)
        assertTrue("发事件的是「拦下」，不是「调用」", AnomalyFeed.replay().isEmpty())
    }

    @Test
    fun `参数为空不是越界_零事件`() {
        val r = pipeline(raw = null).execute(readCall("c-346-5", "   "), { _, _ -> null })
        assertFalse("没给路径不算放行", r is GuardPipeline.Result.Done)
        assertTrue("「没给路径」是参数错，不进越界事实：${AnomalyFeed.replay()}", AnomalyFeed.replay().isEmpty())
    }

    @Test
    fun `无出处不发_动作键合不上数字锚_由证据源自己拒收`() {
        // 生产的键由 ToolCallCodec 兜底（`call_$i`／`toolu_$i`，必带数字）；这里造一笔合不上的。
        val r = pipeline(raw = null).execute(readCall("call-abc", credentialPath), { _, _ -> null })
        assertTrue("该拒还是拒（拒与发事件是两件事）", r is GuardPipeline.Result.Rejected)
        assertTrue(
            "出处判据只在证据源一处：合不上锚就不发，本层不塞一个假出处顶数",
            AnomalyFeed.replay().isEmpty(),
        )
    }

    // ── 源级：只喂采集器，零引用判定层（名册不许手改的前提） ─────────────────────

    @Test
    fun `源级_管线只喂采集器_代码面零引用判定层`() {
        LawIo.assertNotBlind(LawIo.MAIN_ROOT, minFiles = 100, minLines = 10_000)
        val code = LawIo.stripComments(
            LawIo.read("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt"),
        )
        assertTrue("接线本身要在（防空转）", code.contains("AnomalyFactKind.OVER_SCOPE"))
        assertEquals(
            "采集口只许一处 AnomalyFeed.report(（多一处＝第二个形状）",
            1,
            Regex("""AnomalyFeed\s*\.\s*report\s*\(""").findAll(code).count(),
        )
        assertEquals(
            "两处红线各喂一次（多了＝第二个口径冒出来；少了＝有一段红线没接）",
            2,
            Regex("""feedScopeEscape\(\s*call\.id""").findAll(code).count(),
        )
        listOf("AnomalyGate", "AnomalyVerdict", "AccountabilityGate", "baopu.law.").forEach { bad ->
            assertFalse(
                "管线代码面不许引用判定层（$bad）：判决归 law——引了就得手改名册，工单明令不许",
                code.contains(bad),
            )
        }
    }

    private companion object {
        const val SESSION_ID = 346L
    }
}
