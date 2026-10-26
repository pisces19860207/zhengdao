// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百二十六批门禁（G2/G4/G5 ＋ 回放入口）：行为用例钉判据，源码门禁钉「只走既有口」。
// 红的定义与主单一致：这几条一旦被绕开，用例必须失败。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.SelfCheckEvidence
import com.example.zhengdao.baopu.TeachParamRun
import com.example.zhengdao.baopu.TeachRunReport
import com.example.zhengdao.baopu.shouldPresentSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.example.zhengdao.core.RunLog

class E2eRest226GatesTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun controller(): String =
        repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt").readText()

    private fun screen(): String =
        repoFile("src/main/java/com/example/zhengdao/ui/baopu/SkillLibraryScreen.kt").readText()

    private fun runner(): String =
        repoFile("src/main/java/com/example/zhengdao/baopu/ReflectionRunner.kt").readText()

    private fun teach(): String =
        repoFile("src/main/java/com/example/zhengdao/baopu/TeachReplay.kt").readText()

    private fun capture(): String =
        repoFile("src/main/java/com/example/zhengdao/bridge/TeachCapture.kt").readText()

    private fun engine(): String =
        repoFile("src/main/java/com/example/zhengdao/browser/WebViewEngine.kt").readText()

    private fun strings(): String =
        repoFile("src/main/res/values/strings.xml").readText()

    // ── G4：同一句「本轮无候选」只许写一遍 ────────────────────────────────────

    @Test
    fun `G4_无候选那行只由反思器写_控制器不许再写一遍`() {
        assertTrue("ReflectionRunner.present 才是那一行的唯一写点", runner().contains("log(scan.logLine)"))
        assertFalse(
            "控制器再写一遍就是 222 验收里的 08:52:26 ×2",
            controller().contains("RunLog.log(scan.logLine)"),
        )
        assertFalse("也不许换个壳再写（log 后面直接跟 scan.logLine）", controller().contains("log(scan.logLine)"))
    }

    // ── G5：自检不过的候选不出卡 ─────────────────────────────────────────────

    @Test
    fun `G5_自检三态_只有明确没过才不出卡`() {
        assertTrue("没有自检证据＝还没验，不是验了没过", shouldPresentSuggestion(null))
        assertTrue(
            shouldPresentSuggestion(
                SelfCheckEvidence(passed = true, rollbackAvailable = true, caseIds = listOf("c1")),
            ),
        )
        assertFalse(
            "自检没过就是不给用户看",
            shouldPresentSuggestion(
                SelfCheckEvidence(passed = false, rollbackAvailable = false, failedRuleIds = listOf("R1")),
            ),
        )
    }

    @Test
    fun `G5_控制器在入卡前先问shouldPresentSuggestion_并留一行日志`() {
        val c = controller()
        val gate = c.indexOf("shouldPresentSuggestion(selfCheck)")
        val push = c.indexOf("out += suggestion", gate)
        assertTrue("必须先过闸再入卡", gate > 0 && push > gate)
        assertTrue("被压下去的候选要留一行日志（不许安静消失）", c.contains("自检未过 ⇒ 不出卡"))
    }

    // ── G2：点击即回传＋跨域重注入＋轮询兜底＋按序号去重 ─────────────────────

    @Test
    fun `G2_即时回传走JS桥_事件带文档id与序号_并保留轮询兜底`() {
        val t = teach()
        assertTrue("录制壳要挂即时桥", t.contains("TeachCaptureBridge.attach("))
        assertTrue("事件必须带 d 与 s（两路去重的键）", t.contains("d:t.doc") && t.contains("s:++t.seq"))
        assertTrue("点击当场回传原生", t.contains("__zdTeachBridge"))
        assertTrue("去重键是 doc#seq", t.contains("\"#\" + seq"))
        assertTrue("600ms 轮询继续当兜底", t.contains("delay(pollMs)"))
        val b = capture()
        assertTrue("桥本身要 addJavascriptInterface", b.contains("addJavascriptInterface("))
        assertTrue("停止录制要摘桥（不许一直挂着）", b.contains("removeJavascriptInterface("))
        assertTrue("跨域导航后重新注入", b.contains("onDocumentStarted") && b.contains("inject()"))
        assertTrue(
            "引擎必须把 onPageStarted 交给录制（不是改 delegate）",
            engine().contains("var onDocumentStarted") && engine().contains("onDocumentStarted?.invoke("),
        )
    }

    @Test
    fun `G2_停止时说清是空还是只录到导航`() {
        assertTrue("三种结局各有其名", teach().contains("enum class TeachStopKind"))
        assertTrue(
            "0 步＋见过导航 ≠ 没录到任何操作",
            strings().contains("<string name=\"teach_empty_nav\">录到了页面跳转，但没有可复用的操作"),
        )
        assertTrue(
            "页面按结局挑文案（不是一律 teach_empty）",
            repoFile("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt").readText()
                .contains("TeachStopKind.EMPTY_NAV_ONLY ->"),
        )
    }

    @Test
    fun `负例_G2桥没事件时0步不许报成功_两种空都给人话`() {
        val b = repoFile("src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt").readText()
        assertTrue("0 步无导航 ⇒ 说没录到", b.contains("TeachStopKind.EMPTY_NO_EVENTS -> uiMsg = res.getString(R.string.teach_empty)"))
        assertTrue("0 步有导航 ⇒ 说只录到导航", b.contains("TeachStopKind.EMPTY_NAV_ONLY -> uiMsg = res.getString(R.string.teach_empty_nav)"))
        assertTrue(
            "0 步绝不带草稿出去（只有 DRAFT 才交草稿）",
            teach().contains("draft = if (kind == TeachStopKind.DRAFT) d else null"),
        )
    }

    // ── 回放入口：技能页那一颗按钮 ───────────────────────────────────────────

    @Test
    fun `回放钮只给草稿与正式_退役不给_且不许静默失败`() {
        val s = screen()
        val btn = s.indexOf("R.string.skill_library_replay")
        assertTrue("技能页必须有回放钮", btn > 0)
        val guard = s.lastIndexOf("row.status != SkillLibraryStatus.RETIRED", btn)
        assertTrue("按钮必须在「非退役」分支里（退役行退回滚，不回放）", guard in (btn - 1800) until btn)
        assertTrue("没有浏览器会话要给人话", strings().contains("<string name=\"skill_replay_no_session\">"))
        val c = controller()
        assertTrue("回放入口在控制器", c.contains("fun prepareSkillReplay(") && c.contains("fun runSkillReplayAsync("))
        assertTrue("走既有参数口", c.contains("paramsOf(row.name)"))
        assertTrue("走既有回放口", c.contains("replayWith(row.name, sentence"))
        assertTrue("先查会话再开跑", c.contains("BrowserTabManager.activeTab() == null"))
    }

    @Test
    fun `负例_回放没有会话不许静默失败`() {
        val c = controller()
        val msg = c.indexOf("skill_replay_no_session")
        val back = c.indexOf("return@launch", msg)
        val run = c.indexOf("replayWith(row.name, sentence", msg)
        assertTrue("没会话必须当场返回", msg > 0 && back > msg)
        assertTrue("返回前不许已经开跑", run > back)
        assertTrue("页面必须把那句人话显示出来", screen().contains("controller.prepareSkillReplay(row)"))
        assertTrue(screen().contains("onActionDone(err)"))
    }

    @Test
    fun `负例_回放不许自己点提交_走的仍是196执行器`() {
        val c = controller()
        val body = c.substring(c.indexOf("fun runSkillReplayAsync("), c.indexOf("private fun teachModel()"))
        assertFalse("入口不许自己发 execJs／自己点", body.contains("execJs") || body.contains("click("))
        assertTrue("带参回放仍构造 196 执行器（安全闸在那层）", teach().contains("TeachExecutor("))
        assertTrue(
            "提交类按钮的拒绝文本还在（提交｜支付｜结算…）",
            repoFile("src/main/java/com/example/zhengdao/baopu/TeachSafety.kt").readText()
                .contains("CLICK_REFUSAL_TEXT"),
        )
        // 每步取证也来自执行器（入口不另造一份证据目录逻辑）
        assertTrue(c.contains("replayWith(row.name, sentence"))
    }

    @Test
    fun `回放结果一句话_带步数_失败报第几步_带证据目录_被拦动作要说`() {
        val ok = TeachParamRun(
            TeachRunReport("查货", true, 3, null, null, 12L, emptyList(), emptyList(), null, "/ws/产物/玄览技能/查货/1"),
            null, emptyList(), null,
        )
        assertEquals(
            "跑完了：3 步\n每步证据：/ws/产物/玄览技能/查货/1",
            replayAnswerLine(ok),
        )

        val failed = TeachParamRun(
            TeachRunReport("查货", false, 2, 3, "第 3 步找不到「导出」按钮", 9L, emptyList(), emptyList(), null, null),
            null, emptyList(), null,
        )
        assertTrue("失败要停住并报第几步", replayAnswerLine(failed).startsWith("停下了：2 步，原因：第 3 步"))

        val refused = TeachParamRun(
            TeachRunReport("查货", true, 1, null, null, 5L, emptyList(), listOf("提交"), null, null),
            null, emptyList(), null,
        )
        assertTrue("提交类被拦下要说出来", replayAnswerLine(refused).contains("拦下 1 个不该点的动作"))

        val asked = TeachParamRun(null, "要查哪一天？", emptyList(), null)
        assertEquals("参数不全就反问，一个动作都不做", "要查哪一天？", replayAnswerLine(asked))
    }
}
