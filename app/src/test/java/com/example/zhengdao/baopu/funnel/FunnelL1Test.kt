// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十七批 · 能力路由漏斗 L1 的**行为判据**（纯函数直测＋IO 壳直测，全部零模型）。
//
// 钉判据（派工单 647）：
//   ① 脚本命中 ⇒ route=确定性执行（判据层零模型调用——本文件跑在 JVM 上，
//      没有任何模型通道可注入，「零模型」由 [FunnelL1Rules.judge] 的纯函数形状自证）；
//   ② 四条都不中 ⇒ 未命中（这里给足反例输入：硬猜一个组就是红）；
//   ③ 输出三字段齐；不在闭集组名里 ⇒ 视作未命中（闭集外 previousRoute 是现成的注入向量）；
//   ④ 读数每轮一行、字段齐；同一输入两次结果一致（确定性）；
//   判序固定：R1 → R2 → R3 → R4 → 未命中，同输入同输出。
package com.example.zhengdao.baopu.funnel

import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.TeachAction
import com.example.zhengdao.baopu.TeachElementSnapshot
import com.example.zhengdao.baopu.TeachExpect
import com.example.zhengdao.baopu.TeachExpectKind
import com.example.zhengdao.baopu.TeachFingerprint
import com.example.zhengdao.baopu.TeachFingerprintAnchor
import com.example.zhengdao.baopu.TeachLocator
import com.example.zhengdao.baopu.TeachLocatorKind
import com.example.zhengdao.baopu.TeachReplayStore
import com.example.zhengdao.baopu.TeachSkill
import com.example.zhengdao.baopu.TeachStep
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunnelL1Test {

    private val groups = setOf(
        "core", "edit", "browser", "files", "library", "image", "draw", "card",
        "skill", "preview", "mcp", "agent", "data", "websearch", "schedule", "device", "logistics",
    )

    private fun input(
        text: String,
        hasImage: Boolean = false,
        scripts: List<FunnelScript> = emptyList(),
        previousRoute: String? = null,
    ) = FunnelTurnInput(text, hasImage, scripts, previousRoute)

    private fun judge(text: String, previousRoute: String? = null, scripts: List<FunnelScript> = emptyList(), hasImage: Boolean = false) =
        FunnelL1Rules.judge(input(text, hasImage, scripts, previousRoute), groups)

    // ── ① R1 脚本命中 ⇒ 确定性执行 ──────────────────────────────────────────

    @Test
    fun `R1_话里点名脚本_判确定性执行`() {
        val d = judge("帮我跑一遍对账流程", scripts = listOf(FunnelScript("对账流程", "逐行核对发货表", "erp.example.com")))
        assertEquals("确定性执行", d.route)
        assertTrue("evidence 要带脚本名", d.evidence.contains("R1脚本=对账流程"))
    }

    @Test
    fun `R1_点名且网址同站_判确定性执行`() {
        val d = judge(
            "在 https://erp.example.com/login 跑对账流程",
            scripts = listOf(FunnelScript("对账流程", "核对", "erp.example.com")),
        )
        assertEquals("确定性执行", d.route)
        assertTrue(d.evidence.contains("R1同站=erp.example.com"))
    }

    @Test
    fun `R1_点名但网址异站_不判确定性执行`() {
        val d = judge(
            "在 https://other-site.com/x 跑对账流程",
            scripts = listOf(FunnelScript("对账流程", "核对", "erp.example.com")),
        )
        assertEquals("异站就核不上——落到 R3 的 URL 锚点", "browser", d.route)
    }

    @Test
    fun `R1_多条网址里有一条同站_就算同站`() {
        val d = judge(
            "先看 https://other-site.com/x 再在 https://erp.example.com/y 跑对账流程",
            scripts = listOf(FunnelScript("对账流程", "核对", "erp.example.com")),
        )
        assertEquals("确定性执行", d.route)
    }

    @Test
    fun `R1_优先于R2_点名脚本又带关键词_判确定性执行`() {
        val d = judge("查物流之前先跑对账流程", scripts = listOf(FunnelScript("对账流程", "核对", "")))
        assertEquals("确定性执行", d.route)
    }

    // ── ② R2 关键词/显式命令（闭集词表） ────────────────────────────────────

    @Test
    fun `R2_查物流判logistics_做卡判card_通读判library`() {
        assertEquals("logistics", judge("查物流今天到了几件").route)
        assertEquals("card", judge("帮我做卡").route)
        assertEquals("library", judge("通读这本教材").route)
        assertEquals("library", judge("写剧本第一幕").route)
    }

    @Test
    fun `R2_表序即判序_两词都在按表里先出现的算`() {
        val d = judge("先读表再查物流")
        assertEquals("词表里「查物流」排在「读表」前", "logistics", d.route)
    }

    @Test
    fun `R2_优先于R3_关键词带网址_判组不判browser`() {
        val d = judge("查物流 https://erp.example.com/page")
        assertEquals("logistics", d.route)
    }

    // ── ③ R3 引用锚点 ───────────────────────────────────────────────────────

    @Test
    fun `R3_at文件判files_图片判edit_网址判browser`() {
        assertEquals("files", judge("看看 @产物/报告.md 写得对不对").route)
        assertEquals("edit", judge("这张图裁一下", hasImage = true).route)
        assertEquals("browser", judge("打开 https://a.com/x 读读").route)
    }

    @Test
    fun `R3_锚点优先级_at文件压过图片与网址`() {
        val d = judge("对比 @笔记/a.md 和 https://a.com/x", hasImage = true)
        assertEquals("files", d.route)
    }

    // ── ④ R4 会话状态：只作建议 ─────────────────────────────────────────────

    @Test
    fun `R4_上一轮在某组_本轮建议同组且reason写明只作建议`() {
        val d = judge("继续", previousRoute = "library")
        assertEquals("library", d.route)
        assertTrue("reason 必须明写「只作建议」", d.reason.contains("只作建议"))
        assertTrue(d.evidence.contains("R4上一轮=library"))
    }

    @Test
    fun `R4_上一轮是确定性执行_不作为建议`() {
        assertEquals("确定性执行不是组名——R4 不接", "未命中", judge("继续", previousRoute = "确定性执行").route)
    }

    // ── 判据②：没锚点就是未命中，不硬猜 ─────────────────────────────────────

    @Test
    fun `判据二_四条全无锚点_判未命中且evidence空`() {
        val d = judge("今天天气怎么样")
        assertEquals("未命中", d.route)
        assertTrue("未命中不许带依据", d.evidence.isEmpty())
        assertTrue(d.reason.contains("不硬猜"))
    }

    // ── 判据③：闭集校验 ─────────────────────────────────────────────────────

    @Test
    fun `判据三_组名不在闭集_视作未命中`() {
        assertEquals("闭集外的 previousRoute 不许当有效路由", "未命中", judge("继续", previousRoute = "意图分类器").route)
    }

    @Test
    fun `判据三_合法route只许三态`() {
        val legal = FunnelL1Rules.legalRoutes(groups)
        assertTrue(legal.containsAll(groups + setOf("确定性执行", "未命中")))
        for (text in listOf("帮我跑一遍对账流程", "查物流", "打开 https://a.com", "继续", "今天天气怎么样")) {
            val d = judge(text, scripts = listOf(FunnelScript("对账流程", "核对", "")), previousRoute = "library")
            assertTrue("route 必须落在闭集：${d.route}", d.route in legal)
        }
    }

    @Test
    fun `判据三_输出三字段齐_reason非空`() {
        val d = judge("查物流")
        assertEquals("logistics", d.route)
        assertTrue(d.reason.isNotBlank())
        assertTrue(d.evidence.isNotEmpty())
    }

    // ── 判据④：确定性 ───────────────────────────────────────────────────────

    @Test
    fun `判据四_同一输入两次判定逐字段相等`() {
        val a = judge("在 https://erp.example.com 跑对账流程", previousRoute = "library", scripts = listOf(FunnelScript("对账流程", "核对", "erp.example.com")))
        val b = judge("在 https://erp.example.com 跑对账流程", previousRoute = "library", scripts = listOf(FunnelScript("对账流程", "核对", "erp.example.com")))
        assertEquals(a, b)
    }

    @Test
    fun `判据四_判一轮远快于十毫秒预算`() {
        val scripts = List(20) { FunnelScript("流程$it", "用途", "s$it.example.com") }
        val start = System.nanoTime()
        repeat(2_000) { judge("查物流 https://s3.example.com/a", scripts = scripts, previousRoute = "library") }
        val perCallMs = (System.nanoTime() - start) / 2_000 / 1_000_000.0
        assertTrue("单次判定 ${perCallMs}ms——L1 层预算 ≤10ms", perCallMs < 10.0)
    }

    // ── 投影读数：装载数与段数 ───────────────────────────────────────────────

    private val table = mapOf(
        "core" to setOf("read_file", "list_dir"),
        "edit" to setOf("write_file"),
        "logistics" to setOf("logistics_query", "logistics_sheet"),
    )

    @Test
    fun `确定性执行的建议装载数是零`() {
        assertEquals(0, FunnelL1Rules.suggestedToolCount("确定性执行", table, setOf("core", "edit"), setOf(), setOf()))
    }

    @Test
    fun `未命中的建议装载数等于默认可见底数`() {
        val baseline = FunnelL1Rules.suggestedToolCount("未命中", table, setOf("core", "edit"), setOf(), setOf())
        assertEquals("底数＝core2＋edit1", 3, baseline)
    }

    @Test
    fun `命中组并上组成员_重复成员不双计`() {
        val withGroup = FunnelL1Rules.suggestedToolCount("logistics", table, setOf("core", "edit"), setOf(), setOf())
        assertEquals(3 + 2, withGroup)
        val overlap = mapOf("core" to setOf("read_file"), "logistics" to setOf("read_file", "extra_one"))
        assertEquals("并集口径：read_file 只数一次", 2, FunnelL1Rules.suggestedToolCount("logistics", overlap, setOf("core"), setOf(), setOf()))
    }

    @Test
    fun `注入段数投影_确定性零段_命中加一段_未命中照底数`() {
        assertEquals(0, FunnelL1Rules.expectedSegmentCount("确定性执行"))
        assertEquals(FunnelL1Rules.BASE_SEGMENTS + 1, FunnelL1Rules.expectedSegmentCount("logistics"))
        assertEquals(FunnelL1Rules.BASE_SEGMENTS, FunnelL1Rules.expectedSegmentCount("未命中"))
    }

    @Test
    fun `读数行四字段齐全`() {
        val d = judge("查物流")
        val line = FunnelRead.readingLine("7", d, 14, 5)
        assertTrue(line.startsWith("抱朴漏斗L1：会话=7 "))
        assertTrue(line.contains("route=logistics"))
        assertTrue(line.contains("命中=R2词"))
        assertTrue(line.contains("建议装载工具数=14"))
        assertTrue(line.contains("预计注入段数=5"))
    }

    @Test
    fun `命中取规则号_未命中就是未命中`() {
        assertEquals("R1脚本", FunnelL1Rules.hitLabel(judge("跑对账流程", scripts = listOf(FunnelScript("对账流程", "核", "")))))
        assertEquals("R2词", FunnelL1Rules.hitLabel(judge("查物流")))
        assertEquals("R3锚", FunnelL1Rules.hitLabel(judge("打开 https://a.com")))
        assertEquals("R4上一轮", FunnelL1Rules.hitLabel(judge("继续", previousRoute = "card")))
        assertEquals("未命中", FunnelL1Rules.hitLabel(judge("今天天气怎么样")))
    }

    // ── 同站判与主机名 ───────────────────────────────────────────────────────

    @Test
    fun `同站判_子域与www算同站_前后缀伪装不算`() {
        assertTrue(FunnelL1Rules.sameSite("erp.example.com", "example.com"))
        assertTrue(FunnelL1Rules.sameSite("www.a.com", "a.com"))
        assertFalse("evil-a.com 不是 a.com", FunnelL1Rules.sameSite("evil-a.com", "a.com"))
        assertFalse(FunnelL1Rules.sameSite("a.com.evil.io", "a.com"))
    }

    @Test
    fun `主机名_带端口取到端口_抠不出就null`() {
        assertEquals("a.com:8080", FunnelL1Rules.hostOf("https://a.com:8080/x/y"))
        assertEquals(null, FunnelL1Rules.hostOf("https://"))
    }

    // ── IO 壳：脚本表读取＋每轮去向 ─────────────────────────────────────────

    private fun teachSkill(name: String, startUrl: String) = TeachSkill(
        name = name,
        purpose = "查货",
        createdAtMs = 0L,
        startUrl = startUrl,
        allowedDomains = listOf("example.com"),
        clickTagWhitelist = listOf("BUTTON"),
        fingerprint = TeachFingerprint(listOf(TeachFingerprintAnchor("button", "搜索"))),
        steps = listOf(
            TeachStep(
                index = 1,
                action = TeachAction.CLICK,
                url = startUrl,
                locators = listOf(TeachLocator(TeachLocatorKind.ROLE_NAME, "button|搜索")),
                value = null,
                snapshot = TeachElementSnapshot("button", "button", "搜索", "搜索", null),
                expect = listOf(TeachExpect(TeachExpectKind.TEXT_APPEARS, "结果")),
            ),
        ),
    )

    /** 绕闸摆一条盘上脚本（照 ScriptEvoTest.putRawScript：只摆 回放.json 就够 load 读）。 */
    private fun putRawScript(root: File, skill: TeachSkill) {
        val dir = File(SkillRules.projectSkillDir(root), skill.name).apply { mkdirs() }
        File(dir, TeachReplayStore.REPLAY_FILE).writeText(skill.toJsonText(), Charsets.UTF_8)
    }

    @Test
    fun `壳_脚本表按名字排序_v1老脚本从startUrl抠站`() {
        TempSandbox.useResult("zd647a") { root ->
            putRawScript(root, teachSkill("乙流程", "https://erp.example.com/start"))
            putRawScript(root, teachSkill("甲流程", "https://shop.example.com/start"))
            val scripts = FunnelRead.scriptsOf(root)
            assertEquals(listOf("乙流程", "甲流程").sorted(), scripts.map { it.name })
            assertEquals("v1 没记 targetSite——从 startUrl 抠", "erp.example.com", scripts.first { it.name == "乙流程" }.targetSite)
        }
    }

    @Test
    fun `壳_空目录读出空表_不炸`() {
        TempSandbox.useResult("zd647b") { root ->
            assertEquals(emptyList<FunnelScript>(), FunnelRead.scriptsOf(root))
        }
    }

    @Test
    fun `壳_每轮一行后去向入格_未命中不覆盖上一轮`() {
        TempSandbox.useResult("zd647c") { root ->
            val sid = "647-shell-1"
            runBlocking {
                FunnelRead.onUserTurn(sid, "查物流", false, root)
                assertEquals("命中组要落进会话格", "logistics", FunnelRead.snapshot()[sid])
                FunnelRead.onUserTurn(sid, "今天天气怎么样", false, root)
                assertEquals("未命中不覆盖——那件事可能还在做", "logistics", FunnelRead.snapshot()[sid])
            }
            FunnelRead.clearForTest(sid)
            assertFalse(FunnelRead.snapshot().containsKey(sid))
        }
    }

    @Test
    fun `壳_脚本命中轮_会话格记确定性执行`() {
        TempSandbox.useResult("zd647d") { root ->
            putRawScript(root, teachSkill("对账流程", "https://erp.example.com/start"))
            val sid = "647-shell-2"
            runBlocking {
                FunnelRead.onUserTurn(sid, "帮我跑一遍对账流程", false, root)
                assertEquals("确定性执行", FunnelRead.snapshot()[sid])
            }
            FunnelRead.clearForTest(sid)
        }
    }

    @Test
    fun `壳_同一输入两次判定同结果`() {
        TempSandbox.useResult("zd647e") { root ->
            val a = judge("查物流 https://a.com")
            val b = judge("查物流 https://a.com")
            assertEquals(a, b)
        }
    }
}
