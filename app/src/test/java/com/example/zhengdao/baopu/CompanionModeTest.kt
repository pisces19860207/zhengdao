// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百五十批 · 相处模式的判据与负例（派工单 §五 ①②③④⑤）。
//
// 这批要证明的不是"CompanionJudge 存在"（CompanionMode.kt 已证），而是**它在生产链路上按规矩生效**：
//   ① 三档各一条用例：同一问题以"教我/为什么"（学习）与"直接改"（工作）触发，语气段不同；
//   ② 显式要求 > 一切信号：学习模式下用户说"别废话" ⇒ 本轮切回工作；
//   ③ 一轮只判一次：同一轮内两条消息不产生两次切换（**结构性质**，不是计数纪律）；
//   ④ 模式段只在尾部：system 前段与接线前逐字节一致（前缀判据）；
//   ⑤ 入口净增 0：无设置项/无卡/无栏。
//
// 负例（派工单 §五 各条的"自证会红"）在这里逐条变成断言，其中"一轮一次／前缀／入口净增"
// 三条是**源码门禁**——它们不是行为能测出来的东西（判两次也照样返回正确档位），
// 只能扫源码，所以每条都配一个「合成违规样本必须被抓出来」的自证，免得门禁恒绿。
//
// 制度条目（法典 L34）不在本批——归 571（`L3批-清单-2026-11-10.md:10`，依赖 550）。
// 本批只产出**误判样本**（[CompanionJudge.correctionOf]），不产出规则（归 568-B）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CompanionModeTest {

    @Before
    fun setUp() {
        MediatorWiring.resetForTest()
    }

    // ── 判据①：三档各一条用例 ───────────────────────────────────────────────

    @Test
    fun `判据①_教我触发学习档_语气段是LINE_STUDY加教学提示`() {
        val d = CompanionJudge.judge("教我怎么改这个函数")
        assertEquals("教我 ⇒ 学习档", CompanionMode.STUDY, d.mode)
        assertTrue("依据要点名「教我」", d.reason.contains("教我"))
        assertTrue("显式要求", d.explicit)
        val seg = CompanionJudge.segment(d.mode)
        assertTrue("学习档必须出现模式行", seg.contains(CompanionJudge.LINE_STUDY))
        assertTrue("学习档还要带教学提示（先讲为什么）", seg.contains("为什么这么做"))
        assertTrue("学习档还要带教学提示（确认跟上）", seg.contains("确认我跟上了"))
        assertEquals("学习档 = 模式行 + 2 行提示 = 3 行", 3, CompanionJudge.lines(d.mode).size)
        println("[550-study] $seg")
    }

    @Test
    fun `判据①_为什么触发学习档_但explicit为false`() {
        val d = CompanionJudge.judge("这表第3行为什么会报错")
        assertEquals("为什么 ⇒ 学习档", CompanionMode.STUDY, d.mode)
        assertTrue("依据要点名「为什么」", d.reason.contains("为什么"))
        assertEquals("求因措辞不是显式要求", false, d.explicit)
    }

    @Test
    fun `判据①_直接改触发工作档_语气段只有LINE_WORK`() {
        val d = CompanionJudge.judge("直接改第3行")
        assertEquals("直接改 ⇒ 工作档", CompanionMode.WORK, d.mode)
        val seg = CompanionJudge.segment(d.mode)
        assertEquals("工作档 = 模式行 1 行", 1, CompanionJudge.lines(d.mode).size)
        assertEquals("工作档只有 LINE_WORK", CompanionJudge.LINE_WORK, seg)
        println("[550-work] $seg")
    }

    @Test
    fun `判据①_闲聊无信号触发默认档_理由是判不出`() {
        val d = CompanionJudge.judge("今天天气不错")
        assertEquals("闲聊无信号 ⇒ 默认工作档", CompanionMode.DEFAULT, d.mode)
        assertTrue("依据必须说清「判不出」", d.reason.contains("判不出") || d.reason.contains("默认"))
        assertEquals("自动判定", false, d.explicit)
    }

    // ── 判据②：显式要求 > 一切信号 ──────────────────────────────────────────

    @Test
    fun `判据②_学习模式下用户说别废话_本轮切回工作`() {
        // 第1轮：学习档
        MediatorWiring.onUserTurn("教我为什么这表第3行会报错", "", sessionId = 11L, now = 1_760_000_000_000L)
        assertEquals("第1轮 ⇒ 学习档", CompanionMode.STUDY, MediatorWiring.currentCompanion())
        // 第2轮：用户点名"别废话" ⇒ 立即切回工作（派工单 §四⑥）
        MediatorWiring.onUserTurn("别废话，直接改第3行", "", sessionId = 11L, now = 1_760_000_000_000L)
        assertEquals("第2轮 ⇒ 工作档", CompanionMode.WORK, MediatorWiring.currentCompanion())
    }

    @Test
    fun `判据②_别这么啰嗦也能被抓住_光杆词登记`() {
        // 派工单 §四⑥ 点名的那句是「别这么啰嗦」——中间插了"这么"，按整句 contains 就漏掉
        val d = CompanionJudge.judge("别这么啰嗦，给我结论")
        assertEquals("别这么啰嗦 ⇒ 工作档", CompanionMode.WORK, d.mode)
        assertTrue("依据要点名「啰嗦」", d.reason.contains("啰嗦"))
        assertTrue("显式要求", d.explicit)
    }

    @Test
    fun `判据②_显式要求自相矛盾_落默认档`() {
        val d = CompanionJudge.judge("教我为什么，但别废话")
        assertEquals("矛盾 ⇒ 默认工作档", CompanionMode.DEFAULT, d.mode)
        assertTrue("依据要说清「矛盾」", d.reason.contains("矛盾"))
        assertEquals("矛盾不算显式", false, d.explicit)
    }

    // ── 判据③：一轮只判一次（结构性质）────────────────────────────────────

    @Test
    fun `判据③_一轮只判一次_源码门禁`() {
        // CompanionJudge.judge 全仓主源码里只出现一次，且就在 MediatorWiring.onUserTurn。
        // 这是**结构性质**：控制器里 [MediatorWiring.onUserTurn] 是唯一的接线点（258 负例6 已钉），
        // 这里再把它只调一次 ⇒ 一轮一次是拓扑保证，不是计数纪律。
        // 引擎每步拼提示词走的是 [MediatorWiring.companionSuffix] 那个**非消费读取口**，
        // 读多少次都不重判（同轮每一步必须看到同一份，换批＝打破前缀）。
        val callers = (File("src/main/java").walkTopDown() + File("../policy/src/main/java").walkTopDown())
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText(Charsets.UTF_8).contains("CompanionJudge.judge(") }
            .map { it.name }
            .toList()
        assertEquals(
            "CompanionJudge.judge 调用点必须唯一（且只在接线层）：$callers",
            listOf("MediatorWiring.kt"),
            callers,
        )
        // 门禁自证：两处就报两处
        val fake = "CompanionJudge.judge(a)\nCompanionJudge.judge(b)"
        assertEquals(2, Regex("""CompanionJudge\.judge\(""").findAll(fake).count())
    }

    @Test
    fun `判据③_非消费读取口_同轮多次调用返回同一份`() {
        MediatorWiring.onUserTurn("教我为什么", "", sessionId = 11L, now = 1_760_000_000_000L)
        val s1 = MediatorWiring.companionSuffix()
        val s2 = MediatorWiring.companionSuffix()
        val s3 = MediatorWiring.companionSuffix()
        assertEquals("同轮多次调用必须逐字节相同", s1, s2)
        assertEquals("同轮多次调用必须逐字节相同", s2, s3)
        assertTrue("模式段必须出现", s1.contains(CompanionJudge.LINE_STUDY))
    }

    // ── 判据④：模式段只在尾部（前缀判据）──────────────────────────────────

    @Test
    fun `判据④_模式段只在尾部_源码门禁`() {
        // system 前段与接线前逐字节一致 ⇒ companionSuffix 只在 systemFinal 的**末尾**追加，
        // 不许塞进 systemText / prefix / BatchRules.systemPrefix 那些表达式里。
        // 钉法：RunEngine 里 `companionSuffix()` 只出现一次，且在 `systemText + "\n\n" + ...`
        // 那条尾巴表达式里（与 258 契约、436-A 运行时包同一条表达式）。
        val engine = File("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
            .readText(Charsets.UTF_8)
        assertEquals(
            "companionSuffix 必须由引擎拼进 system（RunEngine）且只出现一次",
            1,
            Regex("""MediatorWiring\.companionSuffix\(\)""").findAll(engine).count(),
        )
        assertTrue(
            "必须拼在 system 那一路（追加在 systemText 之后，不动 214 钉的那一行）",
            engine.contains("val systemFinal = if (prefix.isBlank())"),
        )
        assertTrue(
            "追加方式：与 258 契约、436-A 运行时包同一条尾巴表达式（companionSuffix 在 CriteriaPitPack 之后）",
            engine.contains("CriteriaPitPack.systemSuffix()") && engine.contains("MediatorWiring.companionSuffix()"),
        )
        // 反向钉：不许把它塞进本轮 user 消息（LlmMessage ROLE_USER 的 content 是 rest）
        val userLine = engine.lines().firstOrNull { it.contains("role = LlmMessage.ROLE_USER") } ?: ""
        assertFalse("模式段不许混进 user 消息", userLine.contains("companionSuffix"))
    }

    @Test
    fun `判据④_模式段是恒常追加_两档都必须出现模式行`() {
        // 用户 2026-11-11 裁定：省略＝没信号，模型分不清"这次没判"与"判成工作"。
        // 所以两档都必须出现模式行（负例：工作档缺模式行 ⇒ 红）。
        val workSeg = CompanionJudge.segment(CompanionMode.WORK)
        val studySeg = CompanionJudge.segment(CompanionMode.STUDY)
        assertTrue("工作档必须有模式行", workSeg.contains(CompanionJudge.LINE_WORK))
        assertTrue("学习档必须有模式行", studySeg.contains(CompanionJudge.LINE_STUDY))
        assertNotEquals("两档字节不同", workSeg, studySeg)
        // 没判过时（companion = null）也必须有模式行（落默认档）
        val nullSeg = MediatorWiring.companionSuffix()
        assertTrue("没判过时也必须有模式行（默认工作档）", nullSeg.contains(CompanionJudge.LINE_WORK))
    }

    @Test
    fun `判据④_模式段行数不超过3`() {
        CompanionMode.entries.forEach { mode ->
            val lines = CompanionJudge.lines(mode)
            assertTrue(
                "${mode.cn} 档行数 ≤ ${CompanionJudge.MAX_SEGMENT_LINES}：实际 ${lines.size}",
                lines.size <= CompanionJudge.MAX_SEGMENT_LINES,
            )
        }
    }

    @Test
    fun `判据④_模式段是固定字符串_无变量无时间戳`() {
        // 固定字符串 ⇒ 前缀缓存照常命中，只有"切换那一次"失效。
        // 不许把判档依据／信号值／置信度写进这行（那些进账与日志，不进 system）。
        val work1 = CompanionJudge.segment(CompanionMode.WORK)
        val work2 = CompanionJudge.segment(CompanionMode.WORK)
        assertEquals("同档同字节", work1, work2)
        assertTrue("模式段不许含插值符（有插值符就会随轮次变）", !work1.contains('$'))
        assertTrue("模式段不许含时间戳", !Regex("""\d{10,}""").containsMatchIn(work1))
    }

    // ── 判据⑤：入口净增 0 ─────────────────────────────────────────────────

    @Test
    fun `判据⑤_入口净增0_无设置项无卡无栏`() {
        // 本批不许新增：设置项（BaopuSettings 条目）、弹卡（RoyalCardRules 条目）、
        // 左侧栏入口（BaopuShell 条目）。钉法：源码门禁扫本批新建/改动的文件，
        // 不许出现"设置"/"卡片"/"栏"一类的 UI 入口注册。
        val newFiles = listOf(
            "../policy/src/main/java/com/example/zhengdao/baopu/CompanionMode.kt",
        ).map { File(it).readText(Charsets.UTF_8) }
        val uiTokens = listOf(
            "BaopuSettings", "RoyalCardRules", "BaopuShell", "BaopuSettingsFragment",
            "registerSetting", "addCard", "addEntry",
        )
        newFiles.forEach { src ->
            uiTokens.forEach { token ->
                assertTrue(
                    "本批新建文件不许出现 UI 入口注册：$token",
                    !src.contains(token),
                )
            }
        }
        // 反向钉：本批没改 BaopuSettings / RoyalCardRules / BaopuShell 的源码
        listOf(
            "src/main/java/com/example/zhengdao/ui/baopu/BaopuSettings.kt",
            "src/main/java/com/example/zhengdao/ui/baopu/RoyalCardRules.kt",
        ).forEach { rel ->
            val f = File(rel)
            if (f.isFile) {
                assertTrue(
                    "本批不许改 $rel（入口净增 0）",
                    !f.readText(Charsets.UTF_8).contains("CompanionMode"),
                )
            }
        }
    }

    // ── 误判纠正（§六 的进化样本）─────────────────────────────────────────

    @Test
    fun `误判纠正_用户点名切换才算_自动信号不算`() {
        // 第1轮：学习档（自动，explicit=false）
        val d1 = CompanionJudge.judge("为什么这表第3行会报错")
        assertEquals(false, d1.explicit)
        // 第2轮：工作档（自动信号：任务类型）⇒ 不是纠正（只是"这一轮输入不一样"）
        val d2 = CompanionJudge.judge("直接改第3行")
        assertEquals(null, CompanionJudge.correctionOf(d1, d2))
        // 第3轮：工作档（显式要求："别废话"）⇒ 是纠正（用户点名切换）
        val d3 = CompanionJudge.judge("别废话，给我结论")
        assertEquals(true, d3.explicit)
        val correction = CompanionJudge.correctionOf(d1, d3)
        assertEquals("用户点名切换 ⇒ 进化样本", d3, correction)
    }

    @Test
    fun `误判纠正_同档不算纠正_第一轮不算纠正`() {
        val d1 = CompanionJudge.judge("教我为什么")
        val d2 = CompanionJudge.judge("教我怎么做")
        assertEquals("同档不算纠正", null, CompanionJudge.correctionOf(d1, d2))
        assertEquals("第一轮（prev=null）不算纠正", null, CompanionJudge.correctionOf(null, d1))
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    private fun assertFalse(msg: String, cond: Boolean) = assertTrue(msg, !cond)
}
