// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十八批-B §4 判据 J8–J12 ＋ 主单 §2.4.1。分两层（与第十七批 C 段
// 同房型）：**纯函数层**（bottomCards / shouldFollow / shouldShowJump，JVM 直测）
// ＋ **源码级门禁**（卡片调用位置、键盘 padding 组合、文案复用——Compose 布局
// 不可 JVM 直测，本仓无 Robolectric，照 BaopuPresetGuardTest 范式钉源码）。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuChatScrollTest {

    // ── 源码/字符串读取小工具 ────────────────────────────────────────────

    private fun source(name: String): String {
        val roots = listOf(
            File("app/src/main/java/com/example/zhengdao/ui/baopu"),
            File("src/main/java/com/example/zhengdao/ui/baopu"),
        )
        val dir = roots.firstOrNull { it.isDirectory }
            ?: error("找不到 ui/baopu 目录（cwd=${File("").absolutePath}）")
        val f = File(dir, name)
        assertTrue("找不到 $name：${f.path}", f.isFile)
        return f.readText()
    }

    private fun strings(path: String): String {
        val roots = listOf(File("app/$path"), File(path))
        val f = roots.firstOrNull { it.isFile } ?: error("找不到 $path（cwd=${File("").absolutePath}）")
        return f.readText()
    }

    private val allCards = BottomCardsInput(
        confirmCard = true,
        askCard = true,
        pendingMemory = true,
        memoryCandidate = true,
    )

    // ── J8：操作卡在输入框上方 ───────────────────────────────────────────

    @Test
    fun `J8_四种卡都归底部卡片区`() {
        val view = bottomCards(allCards, expandAll = true)
        assertEquals(
            "四种卡都映射到底部卡片区（枚举序＝优先级）",
            listOf(
                BottomCardKind.CONFIRM,
                BottomCardKind.ASK,
                BottomCardKind.PENDING,
                BottomCardKind.CANDIDATE,
            ),
            view.visible,
        )
        assertEquals(0, view.hiddenCount)
    }

    @Test
    fun `J8_卡片调用在消息列表之后_输入框之前_源码级`() {
        val src = source("BaopuScreen.kt")
        val listIdx = src.indexOf("BaopuMessageList(")
        val composerIdx = src.indexOf("BaopuComposer(")
        assertTrue("屏幕里应调 BaopuMessageList", listIdx > 0)
        assertTrue("屏幕里应调 BaopuComposer", composerIdx > listIdx)
        listOf(
            "BaopuConfirmCard(",
            "BaopuAskUserCard(",
            "BaopuPendingMemoryCard(",
            "BaopuMemoryCard(",
        ).forEach { call ->
            val idx = src.indexOf(call)
            assertTrue("$call 必须出现在屏幕上", idx > 0)
            assertTrue("$call 必须在消息列表之后（挪出上半屏）", idx > listIdx)
            assertTrue("$call 必须在输入框之前（贴着输入框上方）", idx < composerIdx)
        }
    }

    // ── J9：多卡折叠 ────────────────────────────────────────────────────

    @Test
    fun `J9_多卡只展开最上面一张_其余折成还有N张`() {
        val view = bottomCards(allCards, expandAll = false)
        assertEquals("只展开最上面一张（确认卡最急）", listOf(BottomCardKind.CONFIRM), view.visible)
        assertEquals("其余 3 张折起来", 3, view.hiddenCount)
        assertTrue(view.hasAny)
    }

    @Test
    fun `J9_两张卡_可见一张_还有一行`() {
        val view = bottomCards(
            BottomCardsInput(confirmCard = false, askCard = true, pendingMemory = true, memoryCandidate = false),
        )
        assertEquals(1, view.visible.size)
        assertEquals(BottomCardKind.ASK, view.visible.first())
        assertEquals(1, view.hiddenCount)
    }

    @Test
    fun `J9_点开才全部展开`() {
        val collapsed = bottomCards(allCards, expandAll = false)
        val expanded = bottomCards(allCards, expandAll = true)
        assertEquals(1, collapsed.visible.size)
        assertEquals(4, expanded.visible.size)
        assertEquals("全展开后没有隐藏行", 0, expanded.hiddenCount)
    }

    @Test
    fun `J9_优先级固定_确认卡永远最上`() {
        // 只有候选卡 ⇒ 它就是第一张（优先级是排列序，不是写死某张）
        val onlyCandidate = bottomCards(
            BottomCardsInput(false, false, false, true),
        )
        assertEquals(listOf(BottomCardKind.CANDIDATE), onlyCandidate.visible)
        // 问人卡与候选卡同在做 ⇒ 问人卡在上（它堵着引擎）
        val askVsCandidate = bottomCards(
            BottomCardsInput(false, true, false, true),
        )
        assertEquals(BottomCardKind.ASK, askVsCandidate.visible.first())
    }

    @Test
    fun `J9_一张卡都没有_整个区不渲染`() {
        val view = bottomCards(BottomCardsInput(false, false, false, false))
        assertFalse(view.hasAny)
        assertTrue(view.visible.isEmpty())
        assertEquals(0, view.hiddenCount)
    }

    // ── J11：贴底跟随 ＋ 回到最新 ───────────────────────────────────────

    @Test
    fun `J11_贴底才跟随_上翻不抢`() {
        assertTrue("贴着底部 ⇒ 跟随", shouldFollow(atBottom = true))
        assertFalse("划上去读历史 ⇒ 不抢（负例 2 靶心）", shouldFollow(atBottom = false))
    }

    @Test
    fun `J11_浮标不在底部才出现_回底即消失`() {
        assertTrue(
            "划上去 ⇒ 出现「回到最新」（用户记得的贴心小设计）",
            shouldShowJump(atBottom = false, hasNew = true),
        )
        assertFalse(
            "贴底 ⇒ 绝不出现",
            shouldShowJump(atBottom = true, hasNew = true),
        )
        assertFalse(
            "贴底且没新内容 ⇒ 更不出现",
            shouldShowJump(atBottom = true, hasNew = false),
        )
    }

    @Test
    fun `J11_小圆点只在新内容时点亮`() {
        assertTrue("上翻且有新内容 ⇒ 点亮", jumpHasNewDot(atBottom = false, hasNew = true))
        assertFalse("上翻但没新内容 ⇒ 不亮（浮标仍在）", jumpHasNewDot(atBottom = false, hasNew = false))
        assertFalse("贴底 ⇒ 不亮", jumpHasNewDot(atBottom = true, hasNew = true))
    }

    // ── J10：键盘不遮卡（作者侧布局/源码断言；真机截图归蓝总管） ──────────

    @Test
    fun `J10_卡片区紧贴输入框_composer带imePadding_源码级`() {
        val screen = source("BaopuScreen.kt")
        val zoneIdx = screen.indexOf("底部操作卡区")
        val composerIdx = screen.indexOf("BaopuComposer(")
        assertTrue("屏幕上应有底部卡片区", zoneIdx > 0)
        assertTrue("卡片区必须在输入框之前", zoneIdx < composerIdx)
        // 键盘方案：composer 自己的 imePadding（既有）——卡片区与输入框同在
        // 一个 Column 里、位于其上方，IME 弹出时 composer 被顶起，卡片随之
        // 保持在输入框上方可见。不新增 navigationBarsPadding（导航条由
        // 系统/Scaffold 处理，现状未变）。
        val composer = source("BaopuComposer.kt")
        assertTrue("composer 必须带 imePadding（键盘顶起方案）", composer.contains("imePadding"))
    }

    // ── J12：文案复用 ───────────────────────────────────────────────────

    @Test
    fun `J12_复用taiji_back_to_latest且不新建重复文案`() {
        val screen = source("BaopuScreen.kt")
        assertTrue(
            "浮标必须引用旧键 R.string.taiji_back_to_latest（不许新建）",
            screen.contains("R.string.taiji_back_to_latest"),
        )
        // 字符串表里带「回到最新」语义的键必须**恰好是原来那一对**（label＋desc）——
        // 新增任何语义重复的键（哪怕叫别的名字）这里就红。
        val zh = strings("src/main/res/values/strings.xml")
        val zhKeys = Regex("<string name=\"([^\"]+)\">[^<]*回到最新[^<]*</string>")
            .findAll(zh).map { it.groupValues[1] }.toSet()
        assertEquals(
            "中文「回到最新」只允许原来那一对旧键",
            setOf("taiji_back_to_latest", "taiji_back_to_latest_desc"),
            zhKeys,
        )
        val en = strings("src/main/res/values-en/strings.xml")
        val enKeys = Regex("<string name=\"([^\"]+)\">[^<]*[Bb]ack to latest[^<]*</string>")
            .findAll(en).map { it.groupValues[1] }.toSet()
        assertEquals(
            "英文「Back to latest」只允许原来那个旧键",
            setOf("taiji_back_to_latest"),
            enKeys,
        )
    }

    @Test
    fun `J12_展开箭头复用旧键common_expand_arrow`() {
        val screen = source("BaopuScreen.kt")
        assertTrue(
            "「还有 N 张」行的展开箭头复用旧键（strings 零新增）",
            screen.contains("R.string.common_expand_arrow"),
        )
    }
}
