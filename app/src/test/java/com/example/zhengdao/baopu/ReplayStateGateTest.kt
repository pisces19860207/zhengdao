// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 第六百七十一批 · 回放可见门禁 —— 判据①–⑥ 逐条对，每条带负例。
 *
 * 两种证据各管一半：
 * · **真跑**：真学习器造技能 → 真执行器跑假 DOM（[FakeDom]），ReplayState 的刻度、停止钮、
 *   失败事实都是**跑出来的**，不是照着源码另写一份影子实现（判据②③④⑤的写口）；
 * · **源码断言**：组合树与「只有一处定义」这类判据只能在源码上验（本仓 JVM 起不来
 *   Compose + WebView，见 [com.example.zhengdao.ui.browser.XuanlanCapsuleGateTest] 同一句说明）。
 *
 * 注释先剥再断言：KDoc 里写着判据词不算实现（623 那条教训）。
 */
class ReplayStateGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        // 单例状态：用例之间必须互不污染（生产侧走 begin/end，夹具这里只能显式清）
        ReplayState.reset()
    }

    // ── 夹具：与 TeachExecutorTest 同一套页面（真学习器的五事件就落在这三张页上）────────

    private val START = "https://search.example.com/start"
    private val RESULTS = "https://search.example.com/results?wb=WB12345678"
    private val EXPORT = "https://search.example.com/export"

    private fun rows(n: Int, prefix: String = "行"): List<FakeEl> =
        (1..n).map { FakeEl(tag = "tr", text = "$prefix$it") }

    private fun searchPages(): Map<String, FakePage> {
        val startPage = FakePage(
            START,
            listOf(
                FakeEl(tag = "th", text = "关键词"),
                FakeEl(
                    tag = "input", id = "search-input", nameAttr = "q", inputType = "search",
                    placeholder = "输入关键词", formLabel = "关键词", classes = listOf("filter-box"),
                ),
                FakeEl(
                    tag = "button", text = "搜索", testid = "search-btn", id = "btn-search",
                    classes = listOf("btn", "primary"),
                    onClick = { it.navigate(RESULTS) },
                ),
                FakeEl(tag = "button", text = "提交", classes = listOf("trap")),
            ),
        )
        val resultsPage = FakePage(
            RESULTS,
            listOf(
                FakeEl(tag = "th", text = "运单号"),
                FakeEl(
                    tag = "input", id = "filter-input", nameAttr = "filter", inputType = "text",
                    placeholder = "筛选运单号", formLabel = "筛选",
                ),
                FakeEl(
                    tag = "button", text = "下一页", classes = listOf("pager-next"),
                    onClick = { dom -> dom.replacePage(RESULTS) { els -> els.filter { it.upperTag != "TR" } + rows(15, "次") } },
                ),
                FakeEl(
                    tag = "button", text = "导出", classes = listOf("btn", "export"),
                    onClick = { it.navigate(EXPORT) },
                ),
            ) + rows(20),
        )
        val exportPage = FakePage(EXPORT, listOf(FakeEl(tag = "th", text = "导出清单"), FakeEl(tag = "button", text = "返回")))
        return mapOf(START to startPage, RESULTS to resultsPage, EXPORT to exportPage)
    }

    private fun skillFromTeaching(): TeachSkill = TeachLearner.buildDraft(
        TeachLearnerTestAccess.fiveEvents(), "查货", "查当日报文",
        TeachPageFacts(START, listOf("关键词"), listOf("搜索", "提交")),
        nowMs = 1_728_000_000_000L,
    ).skill

    private fun executor(
        dom: FakeDom,
        onStep: (TeachStepProgress) -> Unit = {},
        onFailed: (TeachStepProgress, String) -> Unit = { _, _ -> },
        stopRequest: () -> Boolean = { false },
    ): TeachExecutor {
        var now = 0L
        return TeachExecutor(
            dom = dom,
            evidence = FileTeachEvidenceSink(tmp.newFolder()),
            clock = { now },
            sleeper = { now += TeachExecutor.EXPECT_POLL_MS },
            onStep = onStep,
            onFailed = onFailed,
            stopRequest = stopRequest,
        )
    }

    // ── 判据①：指示条整行 ≤40 字（纯函数真跑）────────────────────────────────

    @Test
    fun `判据1_指示条整行不超过40字_步号永远完整`() {
        val head = "回放中 2/5 · "
        assertEquals("正常长度的动作短语照原样", "回放中 2/5 · 正在点「提交」", ReplayText.fitBar(head, "正在点「提交」"))
        // 极端：目标名很长的短语（页面上的长按钮名）必须被截
        val long = "正在点「" + "很长的按钮名字".repeat(8) + "」"
        val line = ReplayText.fitBar(head, long)
        assertTrue("整行 ≤40 字：${line.length}（$line）", line.length <= ReplayText.BAR_MAX)
        assertTrue("步号必须完整留在行首", line.startsWith("回放中 2/5 · "))
        assertTrue("截断要留省略号，别硬切一半字", line.endsWith("…"))
    }

    @Test
    fun `判据1负例_不截断的整行必须超40_证明上面不是恒真`() {
        val head = "回放中 2/5 · "
        val long = "正在点「" + "很长的按钮名字".repeat(8) + "」"
        assertTrue("前提：不截断确实超 40", (head + long).length > ReplayText.BAR_MAX)
        // 变异：拿掉 take 那一层（等价于直接拼），判据必须红
        val naive = head + long
        assertFalse("恒真的判据没有意义", naive.length <= ReplayText.BAR_MAX)
    }

    /** 判据①的另一半：玄览那条指示条只在 playing 时进组合树（不是 GONE、不是常驻）。 */
    @Test
    fun `判据1_指示条只在回放中进组合树`() {
        val browser = code(browserRel)
        val body = block(browser, "if (ReplayState.playing)")
            ?: throw AssertionError("玄览必须有一条 `if (ReplayState.playing)` 的指示条分支")
        assertTrue("分支里是指示条", body.contains("ReplayIndicatorBar("))
        assertTrue("停止钮接的是状态源的唯一出口", body.contains("ReplayState.requestStop()"))
        // 负例：把条件改成恒真，就变成「一直挂着」
        assertNull("恒真的写法不许通过", block(browser.replace("if (ReplayState.playing)", "if (true)"), "if (ReplayState.playing)"))
    }

    /** 判据①的第三半：回放一开始自动切玄览（宿主看同一个状态源）。 */
    @Test
    fun `判据1_回放开始自动切玄览`() {
        val main = code(mainRel)
        assertTrue("宿主必须观察 ReplayState.playing", main.contains("ReplayState.playing"))
        assertTrue("playing 翻真就切到玄览页", main.contains("if (replayPlaying && tab != TabIndex.BROWSER) tab = TabIndex.BROWSER"))
        assertFalse(
            "切页条件写成恒假必须红",
            main.replace(
                "if (replayPlaying && tab != TabIndex.BROWSER) tab = TabIndex.BROWSER",
                "if (false) Unit",
            ).contains("if (replayPlaying && tab != TabIndex.BROWSER) tab = TabIndex.BROWSER"),
        )
    }

    // ── 判据②：真跑一遍，步号 1→N 单调、N 等于脚本步数 ─────────────────────────

    @Test
    fun `判据2_步号1到N单调_且N等于脚本步数`() {
        val skill = skillFromTeaching()
        val seen = mutableListOf<Int>()
        val totals = mutableListOf<Int>()
        val phrases = mutableListOf<String>()
        val dom = FakeDom(searchPages(), START)
        ReplayState.begin(skill.name, skill.steps.size)
        val report = executor(
            dom,
            onStep = { p ->
                // 生产侧就是这一句（TeachReplay 的接线）：写状态源，UI 只读它
                ReplayState.noteStep(p.index, p.total, p.action, p.target)
                seen += ReplayState.stepIndex
                totals += ReplayState.stepTotal
                phrases += ReplayState.currentAction
            },
        ).run(skill)
        ReplayState.end()

        assertTrue("回放应通过：${report.stopReason}", report.ok)
        assertEquals("5 步技能 ⇒ 步号序列 1..5", listOf(1, 2, 3, 4, 5), seen)
        assertEquals("单调不减", seen.sorted(), seen)
        assertEquals("N 与脚本步数一致", skill.steps.size, ReplayState.stepTotal)
        assertEquals("每一步上报的 total 都是脚本步数", setOf(skill.steps.size), totals.toSet())
        assertEquals("最后停在 N", skill.steps.size, ReplayState.stepIndex)
        assertFalse("跑完就不再是回放中", ReplayState.playing)
        assertTrue("动作短语要说得清在干什么：$phrases", phrases.all { it.startsWith("正在") })
        assertTrue("第 1 步是输入", phrases[0].startsWith("正在填「"))
        assertTrue("第 2 步是点击", phrases[1].startsWith("正在点「搜索」"))
    }

    @Test
    fun `判据2负例_步号只上报第一步时N对不上必红`() {
        val skill = skillFromTeaching()
        var count = 0
        ReplayState.begin(skill.name, skill.steps.size)
        executor(
            FakeDom(searchPages(), START),
            onStep = { p ->
                count++
                if (count <= 1) ReplayState.noteStep(p.index, p.total, p.action, p.target)
            },
        ).run(skill)
        ReplayState.end()
        assertEquals("只上报一步 ⇒ stepIndex 停在 1", 1, ReplayState.stepIndex)
        assertFalse("这与「最后停在 N」不是一回事，判据能分辨", ReplayState.stepIndex == skill.steps.size)
    }

    // ── 判据③：停止钮 ⇒ playing=false 且指示条消失 ─────────────────────────────

    @Test
    fun `判据3_停止钮立刻落playing_指示条随之消失`() {
        ReplayState.begin("查货", 3)
        assertTrue("前提：回放中", ReplayState.playing)
        ReplayState.requestStop()
        assertFalse("判据③：playing 必须立刻为假（指示条的组合条件就是它）", ReplayState.playing)
        assertTrue("执行器看得到这个请求", ReplayState.stopRequested())
        // 人点停止不是「技能坏了」：这一刻的失败事实不许立卡
        ReplayState.markFailure(2, 3, "下一页", 1, "用户点了停止")
        assertNull("停止不成失败卡", ReplayState.failure)
    }

    @Test
    fun `判据3_停止后执行器不再动手_停在下一步之前`() {
        val skill = skillFromTeaching()
        val dom = FakeDom(searchPages(), START)
        var steps = 0
        ReplayState.begin(skill.name, skill.steps.size)
        val report = executor(
            dom,
            onStep = { p ->
                ReplayState.noteStep(p.index, p.total, p.action, p.target)
                steps++
                // 第 2 步一上报就点停止（模拟人在第 1 步做完后按停止）
                if (steps == 2) ReplayState.requestStop()
            },
            // 生产侧就是这一句（TeachReplay 的接线）：执行器读状态源的停止请求
            stopRequest = { ReplayState.stopRequested() },
        ).run(skill)
        ReplayState.end()

        assertFalse("这一趟不算跑通", report.ok)
        // 五步技能：第 1 步是「填关键词」，第 2 步才是「点搜索」——停止落在第 2 步之前，
        // 所以一次点击都不该发生，填过的只有第 1 格
        assertTrue("停下来之后一次都没点：${dom.clicks}", dom.clicks.isEmpty())
        assertEquals("只有第 1 步真动过手（填关键词）", 1, dom.typed.size)
        assertEquals("第 2 步没执行", 1, report.stepsDone)
        assertEquals("停在第 2 步之前", 2, report.failedStep)
        assertTrue("原因说得清是「人点的停止」：${report.stopReason}", report.stopReason!!.contains("用户点了停止"))
    }

    @Test
    fun `判据3负例_不点停止就一直在回放中_负例必须能红`() {
        ReplayState.begin("查货", 3)
        assertTrue("没点停止 ⇒ 仍在回放中（指示条留着）", ReplayState.playing)
        assertFalse("停止请求没被误置", ReplayState.stopRequested())
    }

    // ── 判据④：失败卡三钮齐 ＋ 失败报告非空且带得上原文 ─────────────────────────

    @Test
    fun `判据4_失败卡三钮齐_报告带第几步目标命中数与原因`() {
        val dom = FakeDom(searchPages(), START)
        // 变异：给 RESULTS 页再放一颗「下一页」⇒ 定位命中 2 个 ⇒ 执行器停下问人
        dom.replacePage(RESULTS) { els -> els + FakeEl(tag = "button", text = "下一页", classes = listOf("pager-next-2")) }
        val skill = skillFromTeaching()
        var failed: TeachStepProgress? = null
        var reason = ""
        ReplayState.begin(skill.name, skill.steps.size)
        val report = executor(
            dom,
            onStep = { p -> ReplayState.noteStep(p.index, p.total, p.action, p.target) },
            onFailed = { p, r ->
                failed = p
                reason = r
                ReplayState.markFailure(p.index, p.total, p.target, p.hits, r)
            },
        ).run(skill)
        ReplayState.end()

        assertFalse("这一趟必须停", report.ok)
        val p = failed!!
        assertEquals("第几步", 3, p.index)
        assertEquals("目标", "下一页", p.target)
        assertEquals("命中几个", 2, p.hits)
        val f = ReplayState.failure!!
        assertEquals("失败卡读的是同一份事实", 3, f.step)
        assertEquals(2, f.hits)
        assertTrue("原因要带上「命中 2 个」的原文：$reason", reason.contains("命中 2 个"))
        // 判据④的「带失败报告」：给抱朴的那段文本非空，且四样都在
        val text = f.reportText()
        assertTrue("失败报告不许为空", text.isNotBlank())
        assertTrue("带第几步：$text", text.contains("第 3 步"))
        assertTrue("带目标", text.contains("目标：下一页"))
        assertTrue("带命中几个", text.contains("定位命中：2 个"))
        assertTrue("带原因", text.contains("原因："))
        assertTrue("明确要求抱朴改这条技能", text.contains("改这条技能"))
    }

    @Test
    fun `判据4负例_命中数拿不到时说未知_不编0`() {
        val f = ReplayFailure("查货", 2, 5, "搜索", null, "第 2 步后页面跳出授权域，停下")
        val text = f.reportText()
        assertTrue("没找过元素 ⇒ 未知", text.contains("定位命中：未知"))
        assertFalse("不许编一个 0 出来", text.contains("定位命中：0 个"))
        // 起点检查失败（step=null）也要说得清
        val start = ReplayFailure("查货", null, 5, "", 0, "当前页不在授权域").reportText()
        assertTrue("没有步号时说「起手检查」", start.contains("起手检查"))
    }

    @Test
    fun `判据4_失败卡三个动作在本文件与玄览侧都接上了`() {
        val ui = code(replayUiRel)
        val card = block(ui, "internal fun ReplayFailureCard(")
            ?: throw AssertionError("失败卡组件必须在")
        // 参数在函数体之前 ⇒ 出口齐不齐看签名段（block() 只从第一个 { 取到配对的 }）
        val signature = ui.substringAfter("internal fun ReplayFailureCard(").substringBefore("{")
        listOf("onAskBaopu", "onReteach", "onLater").forEach {
            assertTrue("卡上缺一个出口：$it", signature.contains("$it: () -> Unit"))
        }
        assertEquals("三个出口各一枚按钮", 3, Regex("TextButton\\(").findAll(card).count())
        assertTrue("第一钮是「让抱朴改这条技能」", card.contains("R.string.replay_ask_baopu"))
        assertTrue("第二钮是「重新教」", card.contains("R.string.replay_reteach"))
        assertTrue("第三钮是「先不管」", card.contains("R.string.replay_later"))
        // 玄览侧：三钮都接上了动作本体（不是空 lambda）
        val browser = code(browserRel)
        assertTrue("失败卡在玄览里组合", browser.contains("ReplayFailureCard("))
        assertTrue("让抱朴改：把失败报告原文发给同一个 controller", browser.contains("controller.send(f.reportText())"))
        assertTrue("重新教：走底栏那颗键的同一套起手", browser.contains("TeachSessionState.beginRecording()"))
        assertTrue("先不管：只收卡", browser.contains("onLater = { ReplayState.dismissFailure() }"))
    }

    // ── 判据⑤：单一真源（只有一处定义，三处读同一份）──────────────────────────

    @Test
    fun `判据5_全仓只有一处定义_指示条胶囊失败卡读同一份`() {
        val defs = mainSources().flatMap { f ->
            stripComments(f.readText()).lines()
                .filter { it.contains("object ReplayState") }
                .map { "${f.name}: ${it.trim()}" }
        }
        assertEquals("ReplayState 只许定义一处：\n${defs.joinToString("\n")}", 1, defs.size)
        assertTrue("定义必须在 baopu/ReplayState.kt", defs.first().startsWith("ReplayState.kt"))
        // 三处读同一份：指示条（ReplayUi）／胶囊（XuanlanCapsule 的进参在玄览）／失败卡
        val ui = code(replayUiRel)
        assertTrue("指示条读步号", ui.contains("ReplayState.stepIndex"))
        assertTrue("指示条读动作短语", ui.contains("ReplayState.currentAction"))
        assertTrue("失败卡读失败事实", ui.contains("ReplayState.failure") || code(browserRel).contains("ReplayState.failure"))
        assertTrue("胶囊进参来自状态源", code(browserRel).contains("ReplayText.capsuleText(ReplayState.stepIndex, ReplayState.stepTotal)"))
        // 写口只有执行器那条链（TeachReplay 的两处接线）
        val replay = code(replayRel)
        assertEquals("两条回放入口各接一次写口", 2, Regex("ReplayState\\.noteStep\\(").findAll(replay).count())
        assertEquals("两个 begin", 2, Regex("ReplayState\\.begin\\(").findAll(replay).count())
        // 页面不许写状态源（判据⑤的负例方向：谁都能写就等于没有真源）
        listOf(browserRel, replayUiRel, capsuleRel).forEach { rel ->
            val src = code(rel)
            assertFalse("$rel 不许写状态", src.contains("ReplayState.begin(") || src.contains("ReplayState.noteStep("))
        }
    }

    @Test
    fun `判据5负例_再造一个同名字段定义必红`() {
        val defs = mainSources().flatMap { f ->
            stripComments(f.readText()).lines().filter { it.contains("object ReplayState") }
        }
        val fake = defs + "object ReplayState { var playing by mutableStateOf(false) }"
        assertEquals("第二份定义必须被数出来", 2, fake.size)
    }

    // ── 判据⑥：不改 623 胶囊形态、不新开端口（622/645 一个字节不动）─────────────

    @Test
    fun `判据6_胶囊几何与形态没改_回放只换中间那格字`() {
        val capsule = code(capsuleRel)
        assertTrue("48dp 胶囊尺寸原值不动", capsule.contains("const val CAPSULE = 48f"))
        assertTrue("红点仍是 8dp 点不是数", capsule.contains("val DOT = 8f"))
        // 回放态只多一行字：这一支不许顺手改几何（尺寸/偏移/形状）
        val button = block(capsule, "private fun CapsuleButton(")
            ?: throw AssertionError("胶囊按钮必须还在")
        assertTrue("回放那一支只换中间那格字", button.contains("if (replayText != null)"))
        assertTrue("图标与字的容器仍是一格 Box", button.contains("Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center)"))
        // 回放态只多一个参数、只换中间那一格
        assertTrue("层上多一个进度入参", capsule.contains("replayProgress: String? = null"))
        assertTrue("按钮上多一个进度字", capsule.contains("replayText: String? = null"))
        assertTrue("非回放仍画图标", capsule.contains("CapsuleGlyph("))
        // 判据⑥的另一半：不新造浮层 —— 玄览里只有那两处胶囊层，且都是同一组件
        assertEquals(
            "胶囊层只许出现在「教学」与「回放」两条互斥分支里",
            2,
            Regex("XuanlanCapsuleLayer\\(").findAll(code(browserRel)).count(),
        )
        assertTrue("回放分支复用 623 的胶囊（同一组件，不是新浮层）", code(browserRel).contains("} else if (ReplayState.playing) {"))
    }

    @Test
    fun `判据6_不碰端口与执行器旧判据_新代码不直连桥`() {
        listOf(replayStateRel, replayUiRel).forEach { rel ->
            val src = code(rel)
            listOf("BrowserPort", "execJs", "BrowserCommands", "EvaluateJavascript").forEach {
                assertFalse("$rel 不许碰端口/桥：$it", src.contains(it))
            }
        }
        // 执行器的三条新参数都是**可选**入参：老调用点零改动（否则 196/201/228/624C 的判据要跟着改）
        val executor = code(executorRel)
        listOf(
            "private val onStep: (TeachStepProgress) -> Unit = {}",
            "private val onFailed: (TeachStepProgress, String) -> Unit = { _, _ -> }",
            "private val stopRequest: () -> Boolean = { false }",
        ).forEach { assertTrue("新入参必须带缺省：$it", executor.contains(it)) }
        // 停止闸排在动手之前：先上报、再看停止、最后才过暂停闸/定位
        val loop = executor.substringAfter("for (step in skill.steps.drop(fromStep - 1))")
        val atStop = loop.indexOf("if (stopRequest())")
        val atPause = loop.indexOf("pauseGate()")
        assertTrue("停止闸必须在暂停闸之前", atStop in 0 until atPause)
    }

    // ── 源码工具（与 623 门禁同一套口径）──────────────────────────────────────

    private val browserRel = "app/src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt"
    private val capsuleRel = "app/src/main/java/com/example/zhengdao/ui/browser/XuanlanCapsule.kt"
    private val replayUiRel = "app/src/main/java/com/example/zhengdao/ui/browser/ReplayUi.kt"
    private val replayStateRel = "app/src/main/java/com/example/zhengdao/baopu/ReplayState.kt"
    private val replayRel = "app/src/main/java/com/example/zhengdao/baopu/TeachReplay.kt"
    private val executorRel = "app/src/main/java/com/example/zhengdao/baopu/TeachExecutor.kt"
    private val mainRel = "app/src/main/java/com/example/zhengdao/MainActivity.kt"

    private fun repoFile(rel: String): File =
        listOf(File(rel), File("../$rel"), File("app/$rel"))
            .firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 $rel")

    private fun code(rel: String): String = stripComments(repoFile(rel).readText())

    private fun stripComments(src: String): String = src.replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun block(src: String, header: String): String? {
        val at = src.indexOf(header)
        if (at < 0) return null
        val open = src.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
        }
        return null
    }

    /** 生产源码全量（`app/src/main/java` 下所有 kotlin 文件）。 */
    private fun mainSources(): List<File> {
        val root = listOf(
            File("app/src/main/java/com/example/zhengdao"),
            File("../app/src/main/java/com/example/zhengdao"),
        ).firstOrNull { it.isDirectory } ?: throw AssertionError("找不到生产源码根")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
