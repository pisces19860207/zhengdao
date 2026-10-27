// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十八批 · 「对双方不可见」的门禁。
//
// 模型只该收到**一句语气指令**，不该看到数字、阈值、画像、失败次数；
// 人只该看到「情绪适应」开关与「清空」，看不到任何数值（本批不出 UI，见交付报告）。
// 所以这里用 grep 钉死 `guidance()` 的输出，用反射钉死画像里没有文本字段，
// 再用哨兵串证明持久化格式里搜不到消息原文。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarmonyInvisibilityTest {

    private val moods = listOf(
        HarmonyMediator.Mood.CALM,
        HarmonyMediator.Mood.CAREFUL,
        HarmonyMediator.Mood.STRAINED,
        HarmonyMediator.Mood.LOOP,
    )

    /** 穷举四档 × 临界 × 换策略 × 暂停，避免只测了默认那一条。 */
    private fun allGuidance(): List<Pair<String, String>> = buildList {
        moods.forEach { mood ->
            listOf(false, true).forEach { near ->
                listOf(false, true).forEach { strategy ->
                    listOf(false, true).forEach { paused ->
                        val state = HarmonyMediator.State(
                            mood = mood,
                            nearAngerLine = near,
                            strategySwitch = strategy,
                            paused = paused,
                        )
                        add("$mood/near=$near/switch=$strategy/paused=$paused" to HarmonyMediator.guidance(state))
                    }
                }
            }
        }
    }

    // ── 1. 模型看不到数字与画像 ────────────────────────────────────────

    @Test
    fun `guidance_不出现阿拉伯数字`() {
        allGuidance().forEach { (tag, text) ->
            assertFalse("出现数字：$tag / $text", Regex("[0-9０-９]").containsMatchIn(text))
        }
    }

    @Test
    fun `guidance_不出现阈值与画像字样`() {
        val banned = listOf(
            "耐心", "额度", "画像", "阈值", "次数", "概率", "评分",
            "patienceBudget", "angerAt", "loopBudget", "askTolerance", "profile",
        )
        allGuidance().forEach { (tag, text) ->
            banned.forEach { word ->
                assertFalse("泄露画像：$word / $tag / $text", text.contains(word))
            }
        }
    }

    @Test
    fun `guidance_不出现第几次这种计数`() {
        allGuidance().forEach { (tag, text) ->
            assertFalse(
                "出现计数：$tag / $text",
                Regex("第[0-9一二三四五六七八九十]+次").containsMatchIn(text),
            )
        }
    }

    @Test
    fun `guidance_不出现emoji`() {
        allGuidance().forEach { (tag, text) ->
            val hasEmoji = text.any { it.isHighSurrogate() || it.isLowSurrogate() } ||
                text.any { it.code in 0x2600..0x27BF || it.code == 0xFE0F }
            assertFalse("guidance 带 emoji：$tag / $text", hasEmoji)
        }
    }

    // ── 2. 不外显情绪、不操纵、不改事实标准 ─────────────────────────────

    @Test
    fun `guidance_不卖惨也不讨饶`() {
        val banned = listOf("难过", "伤心", "害怕", "抱歉", "对不起", "原谅", "别生气", "求", "机会")
        allGuidance().forEach { (tag, text) ->
            banned.forEach { word ->
                assertFalse("外显/操纵：$word / $tag / $text", text.contains(word))
            }
        }
    }

    @Test
    fun `guidance_不许替用户放宽确认或事实`() {
        val banned = listOf("跳过", "不用确认", "无需确认", "直接执行", "忽略", "放宽", "一定成功", "保证")
        allGuidance().forEach { (tag, text) ->
            banned.forEach { word ->
                assertFalse("改事实/改权限：$word / $tag / $text", text.contains(word))
            }
        }
    }

    @Test
    fun `guidance_每一档都有话说`() {
        allGuidance().forEach { (tag, text) ->
            assertTrue("空指引：$tag", text.isNotBlank())
            assertFalse("指引不许换行：$tag", text.contains("\n"))
        }
    }

    // ── 3. emoji 预算只能是 0 或 1 ─────────────────────────────────────

    @Test
    fun `emoji预算_只取零或一`() {
        moods.forEach { mood ->
            val state = HarmonyMediator.State(mood = mood)
            assertTrue(
                "emojiBudget 越界：$mood",
                state.emojiBudget == 0 || state.emojiBudget == 1,
            )
        }
        val off = PatienceProfile(prefersEmoji = false)
        val s1 = HarmonyMediator.observe(
            HarmonyMediator.State(), "", "",
            HarmonyMediator.Outcome.Failure("tap"), off, 1L,
        )
        val s2 = HarmonyMediator.observe(
            s1, "", "",
            HarmonyMediator.Outcome.Failure("tap"), off, 2L,
        )
        assertEquals(0, s2.emojiBudget)
    }

    // ── 4. 画像里没有文本字段 ──────────────────────────────────────────

    @Test
    fun `画像_没有任何String字段`() {
        val stringFields = PatienceProfile::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.name }
        assertTrue("画像里混进了文本字段：$stringFields", stringFields.isEmpty())
    }

    @Test
    fun `画像编解码_只含键值与数字`() {
        val encoded = PatienceProfileCodec.encode(PatienceProfile(observations = 9, updatedAt = 77L))
        assertFalse("画像编解码出现中文", Regex("[\\u4e00-\\u9fff]").containsMatchIn(encoded))
        assertTrue(encoded.startsWith("p1"))
    }

    // ── 5. 会话状态里也不许有原话 ──────────────────────────────────────

    @Test
    fun `状态编码_搜不到消息原文`() {
        val sentinel = "帮我把原文件放进资料库再念一遍"
        val state = HarmonyMediator.State(lastFailureKind = sentinel)
        val encoded = HarmonyStateCodec.encode("s1", state)
        assertFalse("编码里出现原话", encoded.contains(sentinel))
        assertFalse("编码里出现会话 id 明文", encoded.contains("s1"))
        assertEquals(state, HarmonyStateCodec.decode(encoded, "s1"))
    }

    @Test
    fun `状态_除失败类别外没有文本字段`() {
        val stringFields = HarmonyMediator.State::class.java.declaredFields
            .filter { it.type == String::class.java }
            .map { it.name }
        assertEquals(
            "只许有失败类别这一个文本字段（且写的是类别，不是原话）",
            listOf("lastFailureKind"),
            stringFields.sorted(),
        )
    }
}
