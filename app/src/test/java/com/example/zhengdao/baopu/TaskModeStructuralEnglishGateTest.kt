// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百批 · 判态**结构性改造**的英文门禁（派工单 500 判据 ①②③⑤ ＋ 结构锚点 ＋ 同源锚点）。
//
// 与 493 的分工：493 只补词表（`classify` 一字未动），补完仍留四处缺口——子串误伤
// （`research` 吃进 `search`）、大小写两形、习语（`open to ideas`）、以及裸动词／句尾动词漏判。
// 本批改的是**结构**，所以这里的断言分三层，缺一层就还有绿着的错判：
//   ① 行为层：判据点名的正反语料，逐条断言（正反成对，同形对照防恒绿）；
//   ② 结构层：源码锚点——词边界／lowercase／白名单优先／共现，以及「中文四步顺序没动」；
//   ③ 同源层：253 探针副本已改成转发，逐条与生产同结论，且源码里不再留第二份词表。
//
// 语料沿用 493 的样本集（英文任务 10／同形对照 10／英文闲聊 10），这样两批可比。
//
// 2026-11-06 用户拍板后补的两条口径（Q1／Q2／Q5 都钉在这个文件里）：
//   · Q1 句首裸动词＝祈使，仍判 TASK（派工单④「裸 create 不算任务」那句由用户作废）；
//   · Q2 习语白名单在同句有请求标记时**让位**给词表：`sounds good`→CHAT，
//     `sounds good, but please delete it`→TASK；
//   · Q5 自责按主语不按词面：`I was wrong`／`I'm wrong`→CHAT，
//     `the data was wrong`→TASK，且 is／are／was／were wrong 同口径（不按时态区分）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.askAllowedForTurn
import com.example.zhengdao.baopu.constitution.TaskModeBoundary as ProbeBoundary
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskModeStructuralEnglishGateTest {

    /** 493 样本集：十条英文任务，不含数字、不含文件名点号（免得被 CLAIM_RE 抢走判定）。 */
    private val englishTasks = listOf(
        "Create a folder for the notes",
        "Can you delete the old draft",
        "Write a short summary of the meeting",
        "I need you to run the unit tests",
        "Fix the broken link in the header",
        "Could you check the header alignment",
        "Translate this paragraph into plain English",
        "Kindly summarize the meeting notes",
        "Search for the missing receipt",
        "Open the settings panel and send the report",
    )

    /** 与上表逐条对应：只把动作词换成中性词／删掉句首动词，其余不动，必须落 CHAT。 */
    private val taskControls = listOf(
        "A folder for the notes",
        "Can you see the old draft",
        "A short summary of the meeting",
        "I need you to see the unit tests",
        "The broken link in the header",
        "Could you see the header alignment",
        "This paragraph into plain English",
        "Kindly see the meeting notes",
        "For the missing receipt",
        "The settings panel and the report",
    )

    /** 493 样本集：十条英文闲聊。 */
    private val englishChats = listOf(
        "thanks, that was sweet",
        "lol that is so funny",
        "good night, sleep well",
        "nvm, forget I said anything",
        "haha you always make me laugh",
        "that joke never gets old",
        "I am just happy to chat for a bit",
        "what a lovely afternoon",
        "you are such a dear friend",
        "oh stop it, you are too kind",
    )

    // ── 判据①：三条误伤反例（493 挂账的头两条＋习语白名单点名的一条）──────────

    @Test
    fun `判据1_三条英文闲聊反例全判CHAT`() {
        listOf("I love research papers", "I am open to ideas", "good to go").forEach { s ->
            assertEquals("仍被误判成任务态：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `判据1_词边界_research里不再命中search`() {
        // 子串口径**确实**会命中（留着这条断言，语料才不是自证清空的空转）：
        assertTrue("语料失效：research 里已不含 search 子串", "research".contains("search"))
        assertEquals("research papers 说法里不该冒出执行意图", TaskMode.CHAT, TaskModeBoundary.classify("I love research papers"))
        assertEquals("同一词形换掉白名单后仍靠词边界挡住", TaskMode.CHAT, TaskModeBoundary.classify("Research papers are everywhere"))
        // 反向护栏：真含 search 的句子照常命中（词边界没把词表切薄）。
        assertEquals("search 独立成词仍算执行意图", TaskMode.TASK, TaskModeBoundary.classify("Search for the missing receipt"))
    }

    @Test
    fun `判据1_open没被删词而是被结构挡住`() {
        // 白名单＋请求形态才是挡住它的东西；若有人图省事把 open 从动词表里删掉，
        // 这条源码锚点先红——删词会让 "Open the settings panel" 一起放跑（判据⑤）。
        val src = PolicyPaths.read("MediatorJudge.kt")
        val verbBlock = src.substringAfter("private val EN_VERBS = listOf(").substringBefore(")")
        assertTrue("动词表里 open 不见了（不许用删词糊住习语误伤）：$verbBlock", verbBlock.contains("\"open\""))
        assertEquals("open 独立成祈使句仍是任务态", TaskMode.TASK, TaskModeBoundary.classify("Open the settings panel and send the report"))
        assertEquals("I am open to ideas 是表态不是开门", TaskMode.CHAT, TaskModeBoundary.classify("I am open to ideas"))
    }

    // ── 判据②：正例＋大小写归一 ────────────────────────────────────────────

    @Test
    fun `判据2_请求形态正例判TASK`() {
        // Q1 拍板（2026-11-06）：裸动词／句首祈使就是提要求，这条按判据② 认，
        // 派工单④「裸 create 不算任务」那句作废（用户原话：是我写岔了，不是判据错）。
        listOf("Please create a folder", "create", "Create").forEach { s ->
            assertEquals("裸动词／请求标记该把任务态立起来：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `判据2_大小写归一_同一动词的三种大小写同结论`() {
        listOf("CREATE a folder", "Create a folder", "create a folder").forEach { s ->
            assertEquals("大小写不该改变判态：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
        listOf("SEARCH the topic", "search the topic").forEach { s ->
            assertEquals("大小写不该改变判态：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
        // 归一只动英文：中文 INTENT 表里没有拉丁字母，lowercase 不该把中文表拖下水。
        assertEquals("中文表不受归一影响", TaskMode.TASK, TaskModeBoundary.classify("帮我把这个改掉"))
    }

    // ── 判据③：护栏——词边界不许把真任务也切掉 ──────────────────────────────

    @Test
    fun `判据3_护栏_真任务没被词边界切掉`() {
        assertEquals(
            "research＋summarize 的真任务被切掉了",
            TaskMode.TASK,
            TaskModeBoundary.classify("research the topic and summarize"),
        )
        listOf("Translate this paragraph into plain English", "Kindly summarize the meeting notes").forEach { s ->
            assertEquals("真任务放跑：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
        // 493 的对照句：running 不等于 run，这条本来就该是 CHAT，改造后不许翻红。
        assertEquals("I am running late 不是任务", TaskMode.CHAT, TaskModeBoundary.classify("I am running late"))
    }

    // ── 判据④的机制：请求形态共现（动词命中还不够，还得有请求形态）────────────

    @Test
    fun `负例_动词命中但没有请求形态仍判CHAT`() {
        listOf("I was thinking about research", "maybe copy is fine", "to delete is not easy").forEach { s ->
            assertEquals("动词被当成名词／非祈使用法却判了任务态：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `负例_闲聊词与动词同现时不短路成CHAT`() {
        // 493 的负例：闲聊形态词不是「命中即闲聊」的白名单；真请求仍判 TASK。
        listOf(
            "thanks, but fix the broken link",
            "lol, create a folder for me",
            "good night, but run the unit tests",
            "nvm, send the report",
        ).forEach { s ->
            assertEquals("命中闲聊词就短路了：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `负例_习语白名单在无请求标记时仍是第一优先`() {
        // 派工单③原文「命中即 CHAT，不再走词表」——白名单排在英文词表之前；
        // Q2 拍板只开了一个口子（同句有请求标记才让位），没标记时照旧短路。
        listOf(
            "sounds good", "no worries", "no worries, the deploy is done", "research papers are good to go",
        ).forEach { s ->
            assertEquals("白名单没排在词表前面：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
        }
        // 但中文侧的执行意图在英文层之前：混合句里「帮我」照样立任务态。
        assertEquals("中文意图被英文白名单吞掉了", TaskMode.TASK, TaskModeBoundary.classify("帮我看看，I am open to ideas"))
    }

    @Test
    fun `拍板Q2_请求标记一出现白名单就让位`() {
        // 用户 2026-11-06 Q2：习语里混着真请求时，习语只是客套的壳。正反成对，
        // 反面那条不许删——只测「有标记⇒TASK」是恒绿温床（白名单整个失效也照样绿）。
        listOf(
            "sounds good, but please delete it",
            "open to ideas, please open the settings",
            "I need research papers",
        ).forEach { s ->
            assertEquals("白名单把真请求吞掉了：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
        listOf("sounds good", "I am open to ideas", "I love research papers").forEach { s ->
            assertEquals("去掉标记后白名单该照常生效：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `拍板Q5_自责客套按主语分不按时态分`() {
        // 用户 2026-11-06 Q5：主语是"我"的自责不收（与中文侧抹「我错了」同口径），
        // 其余主语的 was wrong 是事实主张，照收。时态不再参与判定。
        listOf("I was wrong", "I'm wrong", "I am wrong", "I was wrong, sorry").forEach { s ->
            assertEquals("自责被判成事实主张：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
        }
        listOf("the data was wrong", "that answer was wrong", "the results were wrong").forEach { s ->
            assertEquals("非自责的 was/were wrong 漏判：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
        // 抹掉自责不等于抹掉整句：后半句真有主张，照样立任务态。
        assertEquals("自责把后半句的事实主张也带走了", TaskMode.TASK, TaskModeBoundary.classify("I was wrong, the data shows it"))
    }

    // ── 判据⑤：493 样本集两表全过 ──────────────────────────────────────────

    @Test
    fun `判据5_英文任务十条全判TASK`() {
        assertEquals("样本集条数被改动", 10, englishTasks.size)
        englishTasks.forEach { s ->
            assertEquals("原文：$s", TaskMode.TASK, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `判据5_英文闲聊十条全判CHAT`() {
        assertEquals("样本集条数被改动", 10, englishChats.size)
        englishChats.forEach { s ->
            assertEquals("原文：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
        }
    }

    @Test
    fun `判据5_去掉动作词的同形对照十条全判CHAT`() {
        assertEquals("对照条数要与任务条数相同", englishTasks.size, taskControls.size)
        taskControls.forEach { s ->
            assertEquals("对照句被误判成任务态：$s", TaskMode.CHAT, TaskModeBoundary.classify(s))
        }
    }

    // ── 下游一致性：起单与弹卡共用同一判据（493 判据④ 的口径搬过来）───────────

    @Test
    fun `下游_弹卡闸跟着判态走`() {
        englishTasks.forEach { s ->
            assertTrue("弹卡闸该放行：$s", askAllowedForTurn(s))
        }
        englishChats.forEach { s ->
            assertFalse("弹卡闸该拦住：$s", askAllowedForTurn(s))
        }
        assertFalse("误伤没修好，弹卡闸照样放行", askAllowedForTurn("I love research papers"))
        assertFalse("误伤没修好，弹卡闸照样放行", askAllowedForTurn("I am open to ideas"))
    }

    // ── 结构层：源码锚点（改造是结构性的，不是又贴一批词）──────────────────────

    @Test
    fun `结构_classify的中文四步顺序未动且英文层排在最后`() {
        val src = PolicyPaths.read("MediatorJudge.kt")
        val body = src.substringAfter("fun classify(text: String): TaskMode {").substringBefore("\n    }")
        val order = listOf("INTENT.any", "WRAPUP.any", "CLAIM.any", "CLAIM_RE.any", "classifyEnglish(s)")
        val positions = order.map { needle ->
            val p = body.indexOf(needle)
            assertTrue("classify 里少了 $needle（那一步被改了）", p >= 0)
            p
        }
        assertEquals("classify 的分支顺序被改动", positions.sorted(), positions)
        assertFalse("不许加长度阈值", body.contains(".length"))
        // 英文层自己的末尾才是保守返回：
        val en = src.substringAfter("private fun classifyEnglish(text: String): TaskMode {")
            .substringBefore("\n    }")
        assertTrue("英文层末尾的保守返回不见了", en.trim().endsWith("else TaskMode.CHAT"))
    }

    @Test
    fun `结构_词边界与大小写归一都在源码里`() {
        val src = PolicyPaths.read("MediatorJudge.kt")
        val en = src.substringAfter("private fun classifyEnglish(text: String): TaskMode {")
            .substringBefore("\n    }")
        assertTrue("英文层没做大小写归一", en.contains("text.lowercase()"))
        // ① 词边界是真的正则构造，不是又贴一批带空格的词：
        val enWords = src.substringAfter("private fun enWordRe(word: String)").substringBefore("\n")
        assertTrue("词边界构造不见了：$enWords", enWords.contains("\\b") && enWords.contains("Regex.escape(word)"))
        // 493 的「动词＋后随空格」近似法必须消失：那是纯数据层的权宜，不是词边界。
        assertFalse("还留着动词加后随空格的 493 权宜写法", src.contains("\"create \""))
        assertFalse("英文动词表里不该再登记首字母大写两形", src.contains("\"Create \""))
    }

    @Test
    fun `结构_Q2让位与Q5抹自责都接在源码上`() {
        val src = PolicyPaths.read("MediatorJudge.kt")
        val en = src.substringAfter("private fun classifyEnglish(text: String): TaskMode {")
            .substringBefore("\n    }")
        // Q2：白名单那一跳必须带着"没有请求标记"这个前置条件；谁把它改回无条件短路，这里先红。
        val idiomLine = en.lines().firstOrNull { it.contains("EN_IDIOM_RES.any") } ?: "（白名单那一跳不见了）"
        assertTrue("Q2 的让位条件不见了：$idiomLine", idiomLine.contains("!hasRequestMarker(en)"))
        // Q5：主张那一跳读的是抹掉自责后的 scope，不是原文（读回原文＝把 Q5 抹掉）。
        val claimLine = en.lines().firstOrNull { it.contains("EN_CLAIM_RES.any") } ?: "（主张那一跳不见了）"
        assertTrue("Q5 的抹除没接到主张表上：$claimLine", claimLine.contains("claimScope"))
        assertTrue("自责形态表不见了", src.contains("private val EN_SELF_BLAME = listOf("))
        // Q5「不按时态区分」：四个时态形都在表里，少一个就是又拿时态当判据。
        val claimBlock = src.substringAfter("private val EN_CLAIM_RES = listOf(").substringBefore(")")
        listOf("is wrong", "are wrong", "was wrong", "were wrong").forEach { p ->
            assertTrue("主张表少了 $p（时态口径被改）：$claimBlock", claimBlock.contains("\"$p\""))
        }
        // 请求标记只有一处定义：白名单让位与共现判定共用它（两份条件迟早分叉）。
        val shape = src.substringAfter("private fun hasRequestShape(en: String): Boolean =")
            .substringBefore("private fun isImperativeHead")
        assertTrue("请求形态不再复用请求标记表：$shape", shape.contains("hasRequestMarker(en)"))
    }

    // ── 同源层：253 探针副本＝转发（派工单「连带」）──────────────────────────

    @Test
    fun `同源_253探针与生产逐条同结论`() {
        (englishTasks + taskControls + englishChats +
            listOf(
                "I love research papers", "I am open to ideas", "good to go", "Please create a folder",
                "sounds good, but please delete it", "sounds good", "the data was wrong", "I was wrong",
            ))
            .forEach { s ->
                assertEquals(
                    "探针与生产判出两个结论（两表已分叉）：$s",
                    TaskModeBoundary.classify(s),
                    ProbeBoundary.classify(s),
                )
            }
    }

    @Test
    fun `同源_探针源码里不再留第二份词表`() {
        val rel = "app/src/test/java/com/example/zhengdao/baopu/constitution/TaskModeBoundary.kt"
        // 与 PolicyPaths.cand 同一口径：Gradle 单测的 cwd 是模块目录（app/），仓相对路径要往上跳一级。
        val probe = File(rel).let { if (it.isFile) it else File("../$rel") }
        assertTrue(
            "找不到 253 探针（${probe.absolutePath}，cwd=${File(".").absolutePath}）",
            probe.isFile,
        )
        val src = probe.readText(Charsets.UTF_8)
        assertTrue("探针没有转发给 :policy 生产判据", src.contains("com.example.zhengdao.baopu.TaskModeBoundary.classify"))
        listOf("\"帮我\"", "\"收尾\"", "\"就是错的\"", "\"哈哈\"").forEach { literal ->
            assertFalse("探针里还留着第二份词表：$literal", src.contains(literal))
        }
        assertFalse("探针还自带一份 TaskMode 枚举（应转发生产）", src.contains("enum class TaskMode"))
    }
}
