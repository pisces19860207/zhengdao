// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 判据⑥：身体分与内在分两条线分开存，谁都不许把它们合成一个数。
// 规格 §5.3 原话「身体分和内在分是两条线，不合并成一条」⇒ 这里没有 total，也没有 plus。
package com.example.zhengdao.baopu.state.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorLinesTest {

    private fun lines(body: Int, inner: Int) =
        AnchorLines(BodyLine(body, listOf("读数")), InnerLine(inner, listOf("共事两年")))

    @Test
    fun `判据六_两条线各有各的数_并排给不合并`() {
        val l = lines(90, 10)
        assertEquals(90, l.body.score)
        assertEquals(10, l.inner.score)
        val read = l.readout()
        assertEquals(3, read.size)
        assertTrue(read[0].contains("身体分 90"))
        assertTrue(read[1].contains("内在分 10"))
        assertTrue("第三行是性癖吻合，不是两线之和", read[2].contains("双方性癖未给"))
        assertFalse("读数里不许出现 100 这种合并数", read.any { it.contains("总分") || it.contains("合计") })
    }

    @Test
    fun `判据六_类里没有合并口`() {
        val all = AnchorLines::class.java.declaredFields.toList()
        // `$stable` 这类是 Compose 编译器注入的静态字段，不是本类的成员；只在「恰好三个」这条等式里排除。
        // 禁名扫描**不排除**它们——想借美元号前缀藏一个 total 字段是藏不住的。
        val fields = all.filterNot { it.name.startsWith("$") }
        val names = all.map { it.name.lowercase() } + AnchorLines::class.java.declaredMethods.map { it.name.lowercase() }
        listOf("total", "sum", "plus", "combined", "score", "base", "like", "merge").forEach { ban ->
            assertFalse("判据⑥：不许有合并口 $ban", names.any { it.contains(ban) })
        }
        assertEquals(setOf("body", "inner", "kink"), fields.map { it.name }.toSet())
    }

    @Test
    fun `判据六一_排除项命中是归零不是扣分`() {
        val npc = listOf("薄肌", "露头", "早泄")
        val pref = AnchorPreference(wanted = npc, excluded = listOf("包茎"))
        assertEquals("先验证命中确实会带来分", 45, BodyLine.firstSight(AnchorPreference(wanted = npc), npc).score)
        val zero = BodyLine.firstSight(AnchorPreference(wanted = npc, excluded = listOf("露头")), npc)
        assertEquals(0, zero.score)
        assertTrue(zero.basis.first().contains("归零"))
    }

    @Test
    fun `判据六一_每个命中标签十五分最多算四个`() {
        val labels = AnchorFields.L1_TAGS.take(6)
        val pref = AnchorPreference(wanted = labels.toList())
        assertEquals(60, BodyLine.firstSight(pref, labels).score)
        assertEquals(15, BodyLine.firstSight(pref, labels.take(1)).score)
        assertEquals(0, BodyLine.firstSight(AnchorPreference(), labels).score)
    }

    @Test
    fun `判据六_偏好项只能用十五个L1标签`() {
        assertThrows(AnchorSpecException::class.java) { AnchorPreference(wanted = listOf("肌肉男")) }
        assertThrows(AnchorSpecException::class.java) { AnchorPreference(excluded = listOf("身高180")) }
        assertThrows(AnchorSpecException::class.java) { AnchorPreference(bonus = listOf(" ")) }
        assertEquals(1, AnchorPreference(wanted = listOf("薄肌"), bonus = listOf("声音低哑")).hits(listOf("薄肌", "露头")))
    }

    @Test
    fun `判据六_命中不重复计`() {
        val pref = AnchorPreference(wanted = listOf("薄肌", "薄肌"))
        assertEquals(1, pref.hits(listOf("薄肌", "薄肌")))
    }

    @Test
    fun `判据六_身体分五档按规格边界切`() {
        assertEquals(BodyGrade.PEAK, BodyLine(85, listOf("读数")).grade)
        assertEquals(BodyGrade.HIGH, BodyLine(84, listOf("读数")).grade)
        assertEquals(BodyGrade.HIGH, BodyLine(65, listOf("读数")).grade)
        assertEquals(BodyGrade.FLAT, BodyLine(64, listOf("读数")).grade)
        assertEquals(BodyGrade.FLAT, BodyLine(45, listOf("读数")).grade)
        assertEquals(BodyGrade.COLD, BodyLine(44, listOf("读数")).grade)
        assertEquals(BodyGrade.NONE, BodyLine(0, listOf("读数")).grade)
        assertTrue(BodyLine(85, listOf("读数")).approach.contains("按不住"))
        assertTrue(BodyLine(0, listOf("读数")).approach.contains("装也装不出来"))
    }

    @Test
    fun `判据六_分数越界与无据给分都拒`() {
        listOf(-1, 101).forEach { assertThrows(AnchorSpecException::class.java) { BodyLine(it, listOf("读数")) } }
        assertThrows(AnchorSpecException::class.java) { BodyLine(50, listOf("")) }
        assertThrows(AnchorSpecException::class.java) { InnerLine(86, listOf("事")) }
        assertThrows(AnchorSpecException::class.java) { InnerLine(10, listOf("")) }
    }

    @Test
    fun `判据五五_内在分开局最多15天花板85`() {
        (0..15).forEach { InnerLine.opening(it, listOf("初见")) }
        assertThrows(AnchorSpecException::class.java) { InnerLine.opening(16, listOf("初见")) }
        assertEquals(15, InnerLine.START_CEILING)
        assertEquals(85, InnerLine.CEILING)
        assertTrue(InnerLine(85, listOf("共过事")).atCeiling)
        assertFalse(InnerLine(84, listOf("共过事")).atCeiling)
    }

    @Test
    fun `判据五补_回流条件一内在分六十`() {
        assertTrue(InnerLine.soulMatched(60))
        assertFalse(InnerLine.soulMatched(59))
    }

    @Test
    fun `判据五四_四型开局由两条线各自的高低查表`() {
        assertEquals(ReactionType.TOOL, lines(90, 10).reaction)
        // 内在分用的是它自己的天花板 85（§5.5「不是满分」），不是身体分的 90
        assertEquals(ReactionType.OBSESSED, lines(90, 85).reaction)
        assertEquals(ReactionType.SLOW, lines(10, 85).reaction)
        assertEquals(ReactionType.IGNORE, lines(10, 10).reaction)
        assertEquals("阈值取 §7.4 的 40", 40, ReactionType.THRESHOLD)
        assertEquals(ReactionType.OBSESSED, lines(40, 40).reaction)
        assertEquals(ReactionType.SLOW, lines(39, 40).reaction)
        assertEquals(ReactionType.TOOL, lines(40, 39).reaction)
        assertThrows("内在分过 85 天花板必须拒，不许拿 90 当合法输入",
            AnchorSpecException::class.java) { lines(90, 90) }
    }

    @Test
    fun `判据六_性癖吻合取同向小值相加_折算系数待核`() {
        val a = mapOf("k.play.1" to 3, "k.play.2" to 4, "k.power.1" to 2)
        val b = mapOf("k.play.1" to 5, "k.play.2" to 1, "k.sense.1" to 4)
        val overlap = KinkOverlap.of(a, b)
        assertEquals("3＋1", 4, overlap.raw)
        assertEquals(listOf("k.play.1", "k.play.2"), overlap.sharedItems)
        assertTrue(overlap.folded.contains("○ 待核"))
        assertEquals(0, KinkOverlap.of(a, emptyMap()).raw)
        val l = AnchorLines(BodyLine(50, listOf("读数")), InnerLine(20, listOf("事")), overlap)
        assertTrue(l.readout().last().contains("raw=4"))
    }

    @Test
    fun `防闸空转_真给两线加一个合并字段时断言必须咬住`() {
        // 注入自检：模拟"有人加了个 total 字段"的类，扫描式断言必须判它违规
        val offender = object { val total = 100 }
        val names = offender::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue("闸有牙：total 这种合并口必须被列进禁名", names.any { n -> listOf("total", "sum", "plus").any { n.contains(it) } })
    }
}
