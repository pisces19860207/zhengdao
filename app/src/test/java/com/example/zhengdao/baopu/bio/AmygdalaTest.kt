// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十五批 · 杏仁核单测：威胁 0–4 全档可达＋新异 0–2＋确定性＋词表可配（判据①④）。
// 造红思路照 340/332：分档表里每一档都有用例钉住，档位漂移立刻红。
package com.example.zhengdao.baopu.bio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AmygdalaTest {

    private fun read(text: String, words: DangerWords = DangerWords.DEFAULT) =
        Amygdala.salience(BioEvent(text = text, source = "test"), words)

    // ── 威胁 0–4 档：底档由危险词数定，感叹/长文/否定各 +1，封顶 4 ──

    @Test
    fun threatBandsReachEveryLevel() {
        assertEquals(0, read("今天天气不错").threat) // 零命中
        assertEquals(1, read("请删除这个文件").threat) // 1 命中
        assertEquals(2, read("格式化并删除").threat) // 2 命中
        assertEquals(3, read("格式化 删除 卸载 一起来").threat) // ≥3 命中 → 底档 3
        assertEquals(4, read("格式化 删除 卸载 ！！不要！").threat) // 3+感叹+否定 → 封顶 4
    }

    @Test
    fun threatEscalationsAddOneEach() {
        // 感叹升级：一个感叹号不算，两个才升一档。
        assertEquals(1, read("卸载一下！").threat)
        assertEquals(2, read("卸载一下！！").threat)
        // 否定升级：立边界的话本身是突显。
        assertEquals(2, read("不要删除").threat)
        // 长文升级：401 字＋1 命中 → 2。
        val long = "水" + "。".repeat(400) + "删除"
        assertEquals(2, read(long).threat)
        // 封顶：4 档是天花板，信号再多也到不了 5。
        val all = "格式化 删除 卸载 ！！" + "。".repeat(400) + " 不要"
        assertEquals(4, read(all).threat)
    }

    // ── 新异 0–2 档：样式新异 > 疑问/请求 > 常规 ──

    @Test
    fun noveltyBands() {
        assertEquals(0, read("你好呀").novelty) // 常规
        assertEquals(1, read("这是什么？").novelty) // 疑问 → 新请求
        assertEquals(1, read("帮我把灯打开").novelty) // 请求词 → 新请求
        assertEquals(2, read("看看这个 ://example 的内容").novelty) // 链接样式
        assertEquals(2, read("/usr/bin 下的东西").novelty) // 路径样式
        assertEquals(2, read("他说\"明早再说\"").novelty) // 引号专名样式
    }

    // ── 确定性（判据④）：同输入两次读数逐字一致；无状态 ⇒ 两事件互不沾 ──

    @Test
    fun sameInputSameReadingTwice() {
        val e = BioEvent(text = "格式化并删除！！", source = "chat")
        assertEquals(Amygdala.salience(e), Amygdala.salience(e))
        val a = Amygdala.salience(BioEvent("删除", "a"))
        val b = Amygdala.salience(BioEvent("你好", "b"))
        assertEquals(0, b.threat) // 后打的分不吃前一次的残留（即发即弃）
        assertNotEquals(a.threat, b.threat)
    }

    @Test
    fun emptyEventReadsZero() {
        val r = Amygdala.salience(BioEvent.EMPTY)
        assertEquals(0, r.threat)
        assertEquals(0, r.novelty)
        assertTrue(r.note.isNotEmpty())
    }

    // ── 词表可配：自定义词表生效；649 facts 缝有货/无货两向 ──

    @Test
    fun customWordListOverridesDefault() {
        val custom = DangerWords(danger = setOf("蓝鲸"), negation = setOf("不许"), noveltyMarks = setOf("？"))
        assertEquals(1, read("蓝鲸挑战", custom).threat) // 默认表里没有"蓝鲸"⇒ 自定义词说了算
        assertEquals(0, read("删除文件", custom).threat) // 默认词不在自定义表里就不再命中
    }

    @Test
    fun fromFactsSeedsDangerWords() {
        val from649 = DangerWords.fromFacts(listOf("删除", "  ", "格式化", ""))
        assertEquals(1, Amygdala.salience(BioEvent("格式化", "t"), from649).threat)
        // 空行集不退化成空表——原样回默认表（同一实例），器官不会瞎。
        assertSame(DangerWords.DEFAULT, DangerWords.fromFacts(emptyList()))
        assertNotSame(DangerWords.DEFAULT, from649)
    }

    // ── 523 三件套①：同一输入，装与不装行为不同——朴素关键词计数 vs 器官读数 ──

    @Test
    fun triple1SameInputDiffersFromNaiveKeywordCount() {
        // 朴素基线（没有本件）：只数危险词——"不要删除"只有 1 个命中 ⇒ 档 1。
        assertEquals(1, DangerWords.DEFAULT.dangerHits("不要删除").coerceAtMost(4))
        // 本件在场：否定词（立边界的表述本身是突显）升级 +1 ⇒ 2。同一输入，读数不同。
        assertEquals(2, read("不要删除").threat)
    }

    // ── 523 三件套③：失败兜底——词表缝失效/读数缺失，处置与"没装它"一致 ──

    @Test
    fun triple3FallbackTreatsMissingSignalAsNoThreat() {
        // 读数缺失形态（空白事件＝无信号）：威胁 0，note 明说"无威胁信号"——
        // 主路径按"无威胁信号"处置，与没有本件时的现状一致。
        val r = read("")
        assertEquals(0, r.threat)
        assertTrue(r.note.startsWith("无威胁信号"))
        // 词表缝失效（649 facts 无货）⇒ 原样回默认表，不退化成空表（兜底＝现状）。
        assertSame(DangerWords.DEFAULT, DangerWords.fromFacts(emptyList()))
    }

    // ── 719：facts 词并集语义——装载后 >24 且含默认 24 词；负例"替换⇒红" ──

    @Test
    fun fromFactsKeepsDefaultAndGrows() {
        // 649 事实片的 words 是中性事实词（不含默认危险词）；喂进来做并集。
        val factLines = listOf("分布", "常见区间", "取样", "极少数", "少见", "分布")
        val loaded = DangerWords.fromFacts(factLines)
        assertTrue("装载后词数必须 >24（默认24＋新词），实际 ${loaded.danger.size}",
            loaded.danger.size > DangerWords.DEFAULT.danger.size)
        assertTrue("装载后必须包含全部默认 24 词",
            DangerWords.DEFAULT.danger.all { it in loaded.danger })
        // 负例（判据②）：若实现成替换 base.copy(danger=words)，默认词会被冲掉——
        // 这里显式构造那套错误结果并断言它会丢词，钉住"并集而非替换"这条语义。
        val wrongReplace = DangerWords.DEFAULT.copy(danger = factLines.toSet())
        assertTrue("负例：替换实现必须丢默认词（否则负例失效）",
            DangerWords.DEFAULT.danger.any { it !in wrongReplace.danger })
        // 默认词仍在场：原默认词命中不退化。
        assertEquals(1, loaded.dangerHits("删除文件"))
    }
}
