package com.example.zhengdao.baopu.state.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureChecksTest {
    private val namedBlacklist = listOf(
        "抿嘴", "咬唇", "握拳", "攥紧", "深吸一口气", "瞳孔一缩", "喉结滚动", "皱眉", "挑眉",
        "垂眸", "捏眉心", "歪头", "歪脖子", "斜嘴", "嘴角勾起一抹弧度", "斜睨", "眯眼", "嗤笑",
        "单边冷笑", "下颌微收", "抬下巴", "似笑非笑", "邪魅一笑", "眼神一暗", "眸色渐深",
        "扯了扯嘴角", "垂眸一笑",
    )

    @Test
    fun `范式黑名单30条且点名27条逐条在表`() {
        val phrases = GestureCatalog.BLACKLIST.map { it.phrase }
        assertEquals(30, phrases.size)
        assertTrue(GestureCatalog.BLACKLIST.size >= GestureCatalog.MIN_BLACKLIST)
        namedBlacklist.forEach { assertTrue("$it 必须在表内", it in phrases) }
        assertTrue("歪嘴" in phrases)
        assertEquals(phrases.size, phrases.toSet().size)
        assertEquals(GestureCatalog.BLACKLIST.size, GestureCatalog.BLACKLIST.map { it.id }.toSet().size)
        assertTrue(GestureCatalog.LITERARY.none { it in phrases })
    }

    @Test
    fun `每人二到四条越界红`() {
        (1..5).forEach { count ->
            val gestures = (1..count).map { GestureFixture.gesture(id = "x$it", action = "左手第${it}次抬起") }
            val report = GestureChecks.check(listOf(PersonaGestureProfile("甲", gestures)))
            assertEquals(
                "count=$count：${report.line()}",
                count !in GestureCatalog.MIN_PER_PERSON..GestureCatalog.MAX_PER_PERSON,
                "GESTURE_COUNT" in GestureFixture.codes(report),
            )
            if (count in GestureCatalog.MIN_PER_PERSON..GestureCatalog.MAX_PER_PERSON) assertTrue(report.passed)
        }
    }

    @Test
    fun `来源只认930表达层与747锚定的封闭表`() {
        val valid = listOf(
            GestureSourceKind.EXPRESSION_930 to "surface.tags",
            GestureSourceKind.EXPRESSION_930 to "mood",
            GestureSourceKind.EXPRESSION_930 to "emotion",
            GestureSourceKind.ANCHOR_747 to "posture.habit",
        )
        valid.forEach { (kind, ref) ->
            val profile = GestureFixture.profileA().copy(
                habitGestures = listOf(GestureFixture.gesture(source = GestureFixture.source(kind, ref)), GestureFixture.gA2()),
            )
            assertFalse("$kind/$ref 应合法", "GESTURE_SOURCE_UNKNOWN" in GestureFixture.codes(GestureChecks.check(listOf(profile))))
        }
        listOf(null, GestureSourceRef(GestureSourceKind.ANCHOR_747, "posture.hobby")).forEach { bad ->
            val profile = GestureFixture.profileA().copy(
                habitGestures = listOf(GestureFixture.gesture(source = bad), GestureFixture.gA2()),
            )
            assertTrue("来源 $bad 应红", "GESTURE_SOURCE_UNKNOWN" in GestureFixture.codes(GestureChecks.check(listOf(profile))))
        }
    }

    @Test
    fun `跨人物复用红而不同人物不同动作绿`() {
        assertTrue(GestureChecks.check(GestureFixture.profiles()).passed)
        val reused = GestureFixture.profileB().copy(
            habitGestures = listOf(
                GestureFixture.gB1().copy(id = "gB1x", action = GestureFixture.gesture().action),
                GestureFixture.gB2(),
            ),
        )
        val report = GestureChecks.check(listOf(GestureFixture.profileA(), reused))
        assertTrue("跨人物同一动作必须红：${report.line()}", "GESTURE_CROSS_CHARACTER" in GestureFixture.codes(report))
    }

    @Test
    fun `同一人物同一动作重复红`() {
        val profile = GestureFixture.profileA().copy(
            habitGestures = listOf(
                GestureFixture.gesture(),
                GestureFixture.gA2().copy(id = "gA2x", action = GestureFixture.gesture().action),
            ),
        )
        val report = GestureChecks.check(listOf(profile))
        assertTrue("同人重复必须红：${report.line()}", "GESTURE_ACTION_DUP" in GestureFixture.codes(report))
    }

    @Test
    fun `同一动作第1与第5段红第1与第6段绿`() {
        val early = GestureFixture.profileA().copy(beats = listOf(GestureFixture.beat(1), GestureFixture.beat(5)))
        val late = GestureFixture.profileA().copy(beats = listOf(GestureFixture.beat(1), GestureFixture.beat(6)))
        assertTrue("五段窗口内应红", "GESTURE_FREQUENCY" in GestureFixture.codes(GestureChecks.check(listOf(early))))
        assertTrue("跨满五段应绿：${GestureChecks.check(listOf(late)).line()}", GestureChecks.check(listOf(late)).passed)
    }

    @Test
    fun `不同角色或不同动作不累计次数`() {
        val differentAction = GestureFixture.profileA().copy(
            beats = listOf(GestureFixture.beat(1), GestureFixture.beat(3, gestureId = "gA2")),
        )
        assertTrue(GestureChecks.check(listOf(differentAction)).passed)
        val profiles = listOf(
            GestureFixture.profileA().copy(beats = listOf(GestureFixture.beat(1))),
            GestureFixture.profileB().copy(beats = listOf(GestureFixture.beat(3, "乙", "gB1"))),
        )
        assertTrue(GestureChecks.check(profiles).passed)
    }

    @Test
    fun `她没动完全合法且情绪非空也不强配动作`() {
        val beats = (1..4).map {
            GestureFixture.beat(paragraph = it, gestureId = null, text = "她没动。", emotion = Emotion.SADNESS)
        }
        val report = GestureChecks.check(listOf(GestureFixture.profileA().copy(beats = beats)))
        assertTrue("不外化动作不该红：${report.line()}", report.passed)
        assertFalse("不外化动作不该产生任何动作码", "GESTURE_" in report.line())
    }

    @Test
    fun `部位朝向幅度时长缺一红`() {
        val broken = listOf(
            GestureFixture.gesture().copy(part = null),
            GestureFixture.gesture().copy(direction = null),
            GestureFixture.gesture().copy(amplitude = null),
            GestureFixture.gesture().copy(durationMs = null),
            GestureFixture.gesture().copy(durationMs = 0),
        )
        broken.forEach { gesture ->
            val profile = GestureFixture.profileA().copy(habitGestures = listOf(gesture, GestureFixture.gA2()))
            val report = GestureChecks.check(listOf(profile))
            assertTrue("${gesture} 四要素缺项应红：${report.line()}", "GESTURE_PHYSICAL_MISSING" in GestureFixture.codes(report))
        }
        assertTrue(GestureChecks.check(GestureFixture.profiles()).passed)
    }

    @Test
    fun `文学化形容在动作与叙述里都红而物理描述绿`() {
        val action = GestureFixture.gesture(action = "意味深长地看着他")
        val actionReport = GestureChecks.check(
            listOf(GestureFixture.profileA().copy(habitGestures = listOf(action, GestureFixture.gA2()))),
        )
        assertTrue("GESTURE_LITERARY" in GestureFixture.codes(actionReport))
        val textReport = GestureChecks.check(
            listOf(GestureFixture.profileA().copy(beats = listOf(GestureFixture.beat(text = "她意味深长地看着他。")))),
        )
        assertTrue("GESTURE_LITERARY" in GestureFixture.codes(textReport))
        assertTrue(GestureChecks.check(GestureFixture.profiles()).passed)
    }

    @Test
    fun `30条范式逐条在动作里红且747来源也不豁免`() {
        GestureCatalog.BLACKLIST.forEach { entry ->
            val gesture = GestureFixture.gesture(
                id = "x", action = entry.phrase,
                source = GestureFixture.source(GestureSourceKind.ANCHOR_747, "posture.habit"),
            )
            val profile = PersonaGestureProfile("甲", listOf(gesture, GestureFixture.gA2()))
            val report = GestureChecks.check(listOf(profile))
            assertTrue(
                "${entry.id}/${entry.phrase} 应红：${report.line()}",
                report.items.any { it.code == "GESTURE_CLIHE_ACTION" && entry.phrase in it.message },
            )
        }
    }

    @Test
    fun `同段两条范式额外红REPEAT`() {
        val two = GestureChecks.check(
            listOf(GestureFixture.profileA().copy(beats = listOf(GestureFixture.beat(text = "她抿嘴，又皱眉。")))),
        )
        assertTrue("GESTURE_CLIHE_NARRATOR" in GestureFixture.codes(two))
        assertTrue("GESTURE_CLIHE_REPEAT" in GestureFixture.codes(two))
        val one = GestureChecks.check(
            listOf(GestureFixture.profileA().copy(beats = listOf(GestureFixture.beat(text = "她抿嘴。")))),
        )
        assertTrue("GESTURE_CLIHE_NARRATOR" in GestureFixture.codes(one))
        assertFalse("GESTURE_CLIHE_REPEAT" in GestureFixture.codes(one))
    }

    @Test
    fun `台词位置的范式词不判红而不完整引号按叙述`() {
        listOf("她说：「你别抿嘴了。」", "她问：你为什么抿嘴？").forEach { text ->
            val report = GestureChecks.check(
                listOf(GestureFixture.profileA().copy(beats = listOf(GestureFixture.beat(text = text)))),
            )
            assertTrue("台词 $text 不该红：${report.line()}", report.passed)
        }
        val broken = GestureChecks.check(
            listOf(GestureFixture.profileA().copy(beats = listOf(GestureFixture.beat(text = "她抿嘴说“算了。")))),
        )
        assertTrue("不完整引号按叙述：${broken.line()}", "GESTURE_CLIHE_NARRATOR" in GestureFixture.codes(broken))
    }

    @Test
    fun `小动作贴类型标签两方向都红而纯动作绿`() {
        listOf("她歪着嘴，透着腹黑。", "她像个腹黑一样抬了抬下巴。").forEach { text ->
            val report = GestureChecks.check(
                listOf(GestureFixture.profileA().copy(beats = listOf(GestureFixture.beat(text = text)))),
            )
            assertTrue("符号化 $text 应红：${report.line()}", "GESTURE_SYMBOLIC" in GestureFixture.codes(report))
        }
        assertTrue(GestureChecks.check(GestureFixture.profiles()).passed)
    }

    @Test
    fun `台词里贴类型标签不按叙述符号化判红`() {
        val report = GestureChecks.check(
            listOf(GestureFixture.profileA().copy(beats = listOf(GestureFixture.beat(text = "她说：「他一看就是腹黑。」")))),
        )
        assertFalse("GESTURE_SYMBOLIC" in GestureFixture.codes(report))
    }

    @Test
    fun `事件引用簿外动作红`() {
        val report = GestureChecks.check(
            listOf(GestureFixture.profileA().copy(beats = listOf(GestureFixture.beat(gestureId = "gX")))),
        )
        assertTrue("GESTURE_BEAT_UNKNOWN" in GestureFixture.codes(report))
    }

    @Test
    fun `同一输入逐字节确定`() {
        val first = GestureChecks.check(GestureFixture.profiles()).line()
        val second = GestureChecks.check(GestureFixture.profiles()).line()
        assertEquals(first, second)
    }
}
