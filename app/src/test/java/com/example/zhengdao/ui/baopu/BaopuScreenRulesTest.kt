// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第七十八批（§B 80 切片）§2——四处纯逻辑的 JVM 判据 ＋ 源码门禁。
// 纯函数直测；门禁只钉「搬走的旧式子不再出现、屏幕上只剩调用」，
// 视觉/布局/文案仍由既有那批门禁（NoticeSlotTest、BaopuCostWiringTest…）原样守着。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BaopuScreenRulesTest {

    // ── 抽屉宽度（原 BaopuScreen.kt:139） ─────────────────────────────────

    @Test
    fun `W1_窄屏按屏宽八成_360给288`() {
        assertEquals(288f, drawerWidthDp(360), 0.001f)
    }

    @Test
    fun `W2_更窄的屏也按八成_320给256`() {
        assertEquals(256f, drawerWidthDp(320), 0.001f)
    }

    @Test
    fun `W3_封顶之前照实算_374给299_2`() {
        assertEquals(299.2f, drawerWidthDp(374), 0.001f)
    }

    @Test
    fun `W4_375恰好八成_边界值仍是300`() {
        assertEquals(300f, drawerWidthDp(375), 0.001f)
    }

    @Test
    fun `W5_超过封顶一律300_不跟着屏宽继续长`() {
        assertEquals(300f, drawerWidthDp(376), 0.001f)
        assertEquals(300f, drawerWidthDp(411), 0.001f)
        assertEquals(300f, drawerWidthDp(1080), 0.001f)
    }

    @Test
    fun `W6_零宽不凭空给300`() {
        assertEquals(0f, drawerWidthDp(0), 0.001f)
    }

    // ── 顶栏状态行（原 BaopuScreen.kt:388-389） ───────────────────────────

    @Test
    fun `S1_没跑起来只有状态文案`() {
        assertEquals("待命中", statusLineWithStep("待命中", 0))
    }

    @Test
    fun `S2_第一步起带步号`() {
        assertEquals("待命中 · 1", statusLineWithStep("待命中", 1))
    }

    @Test
    fun `S3_负数哨兵不加尾巴`() {
        assertEquals("待命中", statusLineWithStep("待命中", -1))
    }

    @Test
    fun `S4_多位数原样带上`() {
        assertEquals("思考中 · 12", statusLineWithStep("思考中", 12))
    }

    // ── 顶栏「最近一条回复」（原 BaopuScreen.kt:236-237） ──────────────────

    private fun text(value: String) = BaopuUiPart(kind = "text", text = value)

    private fun tool(name: String) = BaopuUiPart(kind = "tool", text = "", tool = name)

    private fun msg(id: Long, role: String, vararg parts: BaopuUiPart) =
        BaopuUiMessage(id = id, role = role, parts = parts.toList())

    @Test
    fun `L1_空列表给空串`() {
        assertEquals("", lastNonUserPartText(emptyList()))
    }

    @Test
    fun `L2_末尾是user_跳过它取前面那条非user`() {
        // 原式是 `lastOrNull { role != "user" }`：末尾的 user 消息被跳过，
        // **不是**「最后一条不是 user 就空」。这条钉住跳过本身。
        val messages = listOf(
            msg(1, "assistant", text("更早的回复")),
            msg(2, "user", text("我说的话")),
        )
        assertEquals("更早的回复", lastNonUserPartText(messages))
    }

    @Test
    fun `L2b_只有user消息给空串`() {
        assertEquals("", lastNonUserPartText(listOf(msg(1, "user", text("我")))))
    }

    @Test
    fun `L3_同一条消息只取最后一个text_part`() {
        val messages = listOf(
            msg(1, "assistant", tool("bash"), text("第一段"), text("最后一段")),
        )
        assertEquals("最后一段", lastNonUserPartText(messages))
    }

    @Test
    fun `L4_最后一条非user没有text_part_不回退`() {
        val messages = listOf(
            msg(1, "assistant", text("更早的正文")),
            msg(2, "assistant", tool("read")),
        )
        assertEquals("", lastNonUserPartText(messages))
    }

    @Test
    fun `L5_空白text原样返回_不替用户挑上一条`() {
        val messages = listOf(
            msg(1, "assistant", text("更早的正文")),
            msg(2, "assistant", text("   ")),
        )
        assertEquals("   ", lastNonUserPartText(messages))
    }

    @Test
    fun `L6_非user角色都算_末尾system也取`() {
        val messages = listOf(
            msg(1, "assistant", text("助手")),
            msg(2, "system", text("系统一句")),
        )
        assertEquals("系统一句", lastNonUserPartText(messages))
    }

    // ── 底部操作卡输入（原 BaopuScreen.kt:616-622） ────────────────────────

    @Test
    fun `C1_全没有就是五面空旗`() {
        val input = screenBottomCardsInput(
            hasConfirmCard = false,
            hasAskCard = false,
            pendingMemoryCount = 0,
            memoryCandidateCount = 0,
            skillProposalCount = 0,
        )
        assertFalse(input.confirmCard)
        assertFalse(input.askCard)
        assertFalse(input.pendingMemory)
        assertFalse(input.memoryCandidate)
        assertFalse(input.skillProposal)
        assertFalse("一张卡都没有 ⇒ 卡片区不渲染", bottomCards(input).hasAny)
    }

    @Test
    fun `C2_单个计数为一_对应旗亮而已`() {
        val input = screenBottomCardsInput(false, false, 1, 0, 0)
        assertTrue(input.pendingMemory)
        assertFalse(input.memoryCandidate)
        assertFalse(input.skillProposal)
    }

    @Test
    fun `C3_计数为零就是没有_不看列表是不是存在`() {
        val input = screenBottomCardsInput(false, false, 0, 0, 0)
        assertFalse(input.pendingMemory)
        assertFalse(input.memoryCandidate)
        assertFalse(input.skillProposal)
    }

    @Test
    fun `C4_五张卡同时在_五面旗全亮`() {
        val input = screenBottomCardsInput(true, true, 1, 2, 3)
        assertTrue(input.confirmCard)
        assertTrue(input.askCard)
        assertTrue(input.pendingMemory)
        assertTrue(input.memoryCandidate)
        assertTrue(input.skillProposal)
        val view = bottomCards(input)
        assertEquals("折叠态只展开最上面一张", 1, view.visible.size)
        assertEquals("其余四张折进「还有 N 张」", 4, view.hiddenCount)
    }

    @Test
    fun `C5_负数计数也不当成有卡`() {
        val input = screenBottomCardsInput(false, false, -1, -1, -1)
        assertFalse(input.pendingMemory)
        assertFalse(input.memoryCandidate)
        assertFalse(input.skillProposal)
    }

    // ── 源码门禁 ─────────────────────────────────────────────────────────

    private val screenRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt"
    private val rulesRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuScreenRules.kt"

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 读剥掉注释后的源码（工作目录 = app/，同仓库既有门禁）。 */
    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    @Test
    fun `G1_屏幕只剩四处调用_旧内联式子不再出现`() {
        val screen = code(screenRel)
        listOf(
            "val drawerWidth = drawerWidthDp(screenWidthDp).dp",
            "val lastAssistant = lastNonUserPartText(controller.messages)",
            "text = statusLineWithStep(",
            "val bottomCardsInput = screenBottomCardsInput(",
        ).forEach { call ->
            assertTrue("屏幕上必须只剩这个调用：$call", screen.contains(call))
        }
        listOf(
            "screenWidthDp * 0.8f > 300f",
            "it.role != \"user\"",
            "if (controller.engineStep.value > 0)",
            "controller.pendingMemories.isNotEmpty()",
            "controller.memoryCandidates.isNotEmpty()",
            "controller.pendingSkillProposals.isNotEmpty()",
            // 注意不能直接查 `BottomCardsInput(`：`screenBottomCardsInput(` 里含这个子串。
            "val bottomCardsInput = BottomCardsInput(",
        ).forEach { old ->
            assertFalse("旧内联式子不许留在屏幕里（会变成第二份实现）：$old", screen.contains(old))
        }
    }

    @Test
    fun `G2_helper不碰Compose_Controller_Android_IO`() {
        val rules = code(rulesRel)
        listOf(
            "@Composable",
            "controller.",
            "BaopuChatController",
            "LocalContext",
            "android.",
            "import androidx.",
            "java.io.File",
            "System.currentTimeMillis",
        ).forEach { bad ->
            assertFalse("纯逻辑 helper 不许出现：$bad", rules.contains(bad))
        }
        // 判别器自证：这种坏形状确实会被上面那条抓到
        assertTrue("controller." in "controller.messages")
    }

    @Test
    fun `G3_四个函数在全仓主源码里只定义一次`() {
        val mainRoot = listOf(
            File("src/main/java/com/example/zhengdao"),
            File("app/src/main/java/com/example/zhengdao"),
        ).firstOrNull { it.isDirectory } ?: error("找不到主源码目录")
        val all = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText(Charsets.UTF_8) }
        listOf(
            "fun drawerWidthDp(",
            "fun statusLineWithStep(",
            "fun lastNonUserPartText(",
            "fun screenBottomCardsInput(",
        ).forEach { def ->
            assertEquals("$def 只该有一处定义（屏幕里不许留第二份）", 1, all.split(def).size - 1)
        }
    }

    @Test
    fun `G4_既有钉线原样保留_预览仍走另一个取法`() {
        val screen = code(screenRel)
        assertTrue(
            "顶栏那条既有门禁钉着这一行，变量名不许改",
            screen.contains("when (val slot = topBarSlot(topArtifacts, lastAssistant, topBarExpanded))"),
        )
        assertTrue(
            "抽屉预览仍走 lastAssistantReply（有回退），不许被 lastNonUserPartText 替掉",
            screen.contains("lastAssistantReply(controller.messages)"),
        )
    }
}
