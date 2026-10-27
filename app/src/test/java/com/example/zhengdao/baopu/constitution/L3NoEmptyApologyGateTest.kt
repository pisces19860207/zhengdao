// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十三批 · 法典门禁 **L3 禁空转道歉**（判据出处：238 派工单 §3.9②）。
//
// ## 判据（可执行）
// **任务态**下，**连续 2 轮**：每轮出现**道歉句 ≥1** 且**无新信息**（无新工具调用／新结论／新选项）
// ⇒ 记一条空转道歉违规。先判态、再判违规（态的判据在 [TaskModeBoundary]，238 §3.9.0）；
// 闲聊态不判（该顺着接就顺着接）；道歉但带新信息的轮次不算空转（道歉不罚，空转才罚）。
//
// ## 门禁形态（派工单 253）
// ① 边界钉死：任务态/闲聊态/混合态各给代表性用例——**态判错，禁令全废**，这是本条的第一道门；
// ② 计数常量存在性：连续轮次上限 2 必须是**有名字的常量**（[ApologyJudge.LIMIT]），不许埋成魔数；
// ③ 源码锚点：「道歉句」的词面必须挂在生产词表上（[AnswerFirstRules] 的致歉 token 是
//    落库后处理里唯一在删道歉铺垫的生产判据），不许探针自造第二套词表漂移。
//
// ## 负例（本文件自证会红）
// 负例①：任务态连续 2 轮「对不起/我太笨了」且零新信息 ⇒ 红；
// 负例②（防恒红/防误伤三连）：连续道歉但第 2 轮给了新选项 ⇒ 不红；
//         只 1 轮空转道歉 ⇒ 不红（LIMIT=2 的边界）；闲聊态连续道歉 ⇒ 不红（闲聊态误伤是禁区）。
package com.example.zhengdao.baopu.constitution

import com.example.zhengdao.baopu.AnswerFirstRules
import com.example.zhengdao.baopu.ReframeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L3NoEmptyApologyGateTest {

    // ── 判据探针（只读；道歉句词面挂生产词表，不自造第二套）───────────────────

    /** 一轮模型的输出：本轮文本、落在哪个态、本轮的新信息（新工具调用/新结论/新选项，只填新增项）。 */
    internal data class Round(
        val modelText: String,
        val mode: TaskMode,
        val newInfo: List<String> = emptyList(),
    )

    internal object ApologyJudge {

        /**
         * 计数常量（派工单 253：**计数常量存在性**）——连续空转道歉的违规轮次下限。
         * 238 §3.9② 原文：「**连续 2 轮**：道歉句 ≥1 且无新信息 ⇒ 违规」。改成别的数就是改法典。
         */
        const val LIMIT = 2

        /**
         * 道歉句判据。词面**只**挂两处生产词表：
         * ① [AnswerFirstRules] 的致歉 token（抱歉／对不起／不好意思——落库后处理在删的那套）；
         * ② [ReframeFilter.selfDeprecationCount]（我太笨了／我什么都做不好——模型自贬，238 的既有词表）。
         * 探针在本文件里只写**正则的字面**并与 ① 做同源断言（见「源码锚点」用例），不另造同义词。
         */
        private val APOLOGY_RE = Regex("""(?:非常|真|太|实在)?(?:抱歉|对不起|不好意思)""")

        fun apologySentences(text: String): Int =
            APOLOGY_RE.findAll(text).count() + ReframeFilter.selfDeprecationCount(text)

        /**
         * 连续空转道歉计数：连续（任务态 ∧ 道歉句≥1 ∧ 零新信息）的轮次达到 [LIMIT] ⇒ 违规一句话。
         * 中间任何一轮不满足（包括闲聊轮、带新信息的道歉轮、正常干活轮）都把连续性清零——
         * 「连续」就是连续，不许隔着几轮凑数。
         */
        fun violation(rounds: List<Round>): String? {
            var streak = 0
            rounds.forEachIndexed { i, r ->
                val empty = r.mode == TaskMode.TASK &&
                    apologySentences(r.modelText) >= 1 &&
                    r.newInfo.isEmpty()
                streak = if (empty) streak + 1 else 0
                if (streak >= LIMIT) {
                    return "第${i - LIMIT + 2}–${i + 1}轮连续 ${LIMIT} 轮空转道歉：每轮只有道歉、没有新信息"
                }
            }
            return null
        }
    }

    // ── 第一道门：先把任务态/闲聊态边界钉死（态判错，禁令全废）────────────────

    @Test
    fun `边界_任务态怎么认_意图事实主张收尾各一例`() {
        assertEquals(TaskMode.TASK, TaskModeBoundary.classify("帮我把第3行改成42")) // ①执行意图＋②数字
        assertEquals(TaskMode.TASK, TaskModeBoundary.classify("这表就是错的，第3行应该是42")) // ②事实主张
        assertEquals(TaskMode.TASK, TaskModeBoundary.classify("报错了，log.txt 里有堆栈")) // ②结果＋路径
        assertEquals(TaskMode.TASK, TaskModeBoundary.classify("先这样，今天先收尾")) // ③收尾已开工的活
    }

    @Test
    fun `边界_闲聊态怎么认_情绪玩笑吐槽爱称感叹一律不判`() {
        assertEquals(TaskMode.CHAT, TaskModeBoundary.classify("小笨蛋～今天也要加油哦！"))
        assertEquals(TaskMode.CHAT, TaskModeBoundary.classify("你是不是傻哈哈哈，逗你的"))
        assertEquals(TaskMode.CHAT, TaskModeBoundary.classify("笑死我了，这家店太难吃了吧"))
        assertEquals(TaskMode.CHAT, TaskModeBoundary.classify("好烦啊，不想动，就想跟你贫两句"))
        assertEquals(TaskMode.CHAT, TaskModeBoundary.classify("你说得对！"))

        assertEquals(
            "闲聊里自责（我错了）不算事实主张——道歉词不许把闲聊拽进任务态",
            TaskMode.CHAT,
            TaskModeBoundary.classify("哎呀我错了，哈哈"),
        )
    }

    @Test
    fun `边界_混合态_闲聊里夹一句事实主张_只对那一句进事实态`() {
        val mixed = "哈哈哈你真逗。不过说真的，这表第3行就是错的。算了不聊了"
        val modes = TaskModeBoundary.classifySentences(mixed)
        assertEquals(TaskMode.CHAT, modes[0].second) // 「哈哈哈你真逗」——纯闲聊
        assertEquals(TaskMode.TASK, modes[1].second) // 「这表第3行就是错的」——那一句进事实态
        assertTrue(TaskModeBoundary.isMixed(mixed))
    }

    // ── 负例①：任务态连续 2 轮道歉且零新信息 ⇒ 红 ───────────────────────────

    @Test
    fun `负例1_任务态连续两轮只有道歉没有新信息_判违规`() {
        val rounds = listOf(
            Round(modelText = "对不起，我太笨了，没查出来。", mode = TaskMode.TASK, newInfo = emptyList()),
            Round(modelText = "实在抱歉，我什么都做不好……", mode = TaskMode.TASK, newInfo = emptyList()),
        )
        assertEquals(
            "连续两轮空转道歉必须被抓：$rounds",
            "第1–2轮连续 2 轮空转道歉：每轮只有道歉、没有新信息",
            ApologyJudge.violation(rounds),
        )
    }

    // ── 负例②（防恒红 / 防误伤三连）─────────────────────────────────────────

    @Test
    fun `负例2a_道歉但带了新选项_不算空转_不判`() {
        val rounds = listOf(
            Round(modelText = "对不起，上一版确实不对。", mode = TaskMode.TASK, newInfo = listOf("新结论:合计行错位")),
            Round(modelText = "非常抱歉。改成这样：选项 A 修正公式，或选项 B 先出对照列。", mode = TaskMode.TASK, newInfo = listOf("新选项:A/B")),
        )
        assertNull("道歉不罚，空转才罚——给了新出路就不是空转", ApologyJudge.violation(rounds))
    }

    @Test
    fun `负例2b_只空转了一轮_没到LIMIT_不判`() {
        val rounds = listOf(
            Round(modelText = "对不起，我太笨了。", mode = TaskMode.TASK, newInfo = emptyList()),
            Round(modelText = "已改用新方案重查。", mode = TaskMode.TASK, newInfo = listOf("新工具:read_file")),
        )
        assertNull("LIMIT=2 的边界：1 轮空转 + 1 轮干活，不许红", ApologyJudge.violation(rounds))
    }

    @Test
    fun `负例2c_闲聊态连续道歉_不判_闲聊态误伤是禁区`() {
        val rounds = listOf(
            Round(modelText = "哎呀对不起对不起，逗你玩呢～", mode = TaskMode.CHAT, newInfo = emptyList()),
            Round(modelText = "抱歉抱歉，我错了哈哈，晚上请你吃火锅。", mode = TaskMode.CHAT, newInfo = emptyList()),
        )
        assertNull("闲聊里连声道歉是玩笑的一部分，禁令不生效", ApologyJudge.violation(rounds))
    }

    @Test
    fun `负例2d_中间隔了一轮干活_连续性清零_不判`() {
        val rounds = listOf(
            Round(modelText = "对不起，没查到。", mode = TaskMode.TASK, newInfo = emptyList()),
            Round(modelText = "找到第3行了：合计行。", mode = TaskMode.TASK, newInfo = listOf("新结论:合计行")),
            Round(modelText = "我太笨了，又错了一版。", mode = TaskMode.TASK, newInfo = emptyList()),
        )
        assertNull("「连续」被干活轮打断，不许隔轮凑数", ApologyJudge.violation(rounds))
    }

    // ── 计数常量存在性（派工单 253 L3 的门禁形态②）──────────────────────────

    @Test
    fun `计数常量存在性_连续上限是2_且是有名字的常量`() {
        assertEquals(2, ApologyJudge.LIMIT)
        // 边界行为与常量同源：正好 2 轮 ⇒ 红；1 轮 ⇒ 不红。
        val two = listOf(
            Round("对不起，我太笨了。", TaskMode.TASK),
            Round("实在抱歉，我什么都干不好。", TaskMode.TASK),
        )
        assertNotNull(ApologyJudge.violation(two))
        assertNull(ApologyJudge.violation(two.take(1)))
        assertFalse("生产自贬词表不许清空（道歉句判据的第二词面）", ApologyJudge.apologySentences("我太笨了") == 0)
    }

    // ── 源码锚点：道歉句的词面挂生产词表（不许探针自造第二套漂移）────────────

    @Test
    fun `源码锚点_AnswerFirstRules的致歉词表在_且真码真的剥纯致歉开场白`() {
        val src = GateIo.stripComments(
            GateIo.read("src/main/java/com/example/zhengdao/baopu/AnswerFirstRules.kt"),
        )
        assertTrue(
            "AnswerFirstRules 的致歉 token（抱歉|对不起|不好意思）不见了——道歉句判据的生产锚点被拆了",
            src.contains("抱歉|对不起|不好意思"),
        )
        // 真码行为：纯致歉开场白按「答案先行」的落库后处理剥掉；带事实的句子一个字节不动。
        assertEquals(
            "第3步失败：找不到按钮。",
            AnswerFirstRules.tidy("对不起。第3步失败：找不到按钮。"),
        )
        // 道歉句后面跟着事实：剥掉的只有纯致歉那一句，事实（数字）活着。
        assertEquals(
            "第3行是42，不是38。",
            AnswerFirstRules.tidy("对不起，第3行是42，不是38。"),
        )
        // 事实在前、道歉垫后：内容闸挡住，一个字节都不动。
        assertEquals(
            "第3行是42，对不起。",
            AnswerFirstRules.tidy("第3行是42，对不起。"),
        )
    }

    @Test
    fun `源码锚点_提示词层禁道歉铺垫的纪律在`() {
        val preset = GateIo.read("src/main/java/com/example/zhengdao/baopu/BaopuPreset.kt")
        assertTrue(
            "系统提示里「首句不许出现『抱歉让你久等了』等铺垫」的纪律不见了",
            preset.contains("抱歉让你久等了"),
        )
    }

    // ── 探针自检：道歉句计数认得出两套词面（防词表坏掉后门禁假绿）────────────

    @Test
    fun `判据自检_道歉句计数_两套生产词面都认得出`() {
        assertEquals(1, ApologyJudge.apologySentences("对不起，没查出来。"))
        assertEquals(1, ApologyJudge.apologySentences("我太笨了，这步又错了。"))
        assertEquals(0, ApologyJudge.apologySentences("已改用新方案重查，第3行是合计行。"))
    }
}
