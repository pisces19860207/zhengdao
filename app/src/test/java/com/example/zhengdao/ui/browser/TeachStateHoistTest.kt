// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.browser

import com.example.zhengdao.baopu.TeachFingerprint
import com.example.zhengdao.baopu.TeachSessionState
import com.example.zhengdao.baopu.TeachSkill
import com.example.zhengdao.baopu.TeachSkillDraft
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 第六百三十八批 · 教学态上提门禁（解 623 判据③方向二）。
 *
 * 证据分层，不混级：
 * · 夹具跑的是**真** [TeachSessionState]（进程级 object、真 Compose state、真合成口径
 *   [TeachSessionState.teachingNow]），不是照着源码另写一份影子实现；
 * · 源码闸从**磁盘**读真源码，逐条配内存变异（负例），判据恒真就会自己红；
 * · 「真机上切页发一条」属于 ① 级证据，本 JVM 夹具不冒充（见交付报告）。
 *
 * 五条判据对应：
 * ① 方向二可过 → `判据1_方向二...`
 * ② 切标签不结束 → `判据2_...` ＋ 源码闸（tab 切换 effect / onBrowserHidden 不许复位）
 * ③ 显式停止三态归零 → `判据3_...`
 * ④ 只有一处定义 → `判据4_...` ＋ 两处负例变异
 * ⑤ 进程重启教学结束 → `判据5_...` ＋ 无持久化源码闸
 */
class TeachStateHoistTest {

    private enum class Page { BAOPU, XUANLAN }

    /**
     * 方向二夹具：两页共用的**那一份**会话消息（生产里是 MainActivity 的 `chatController`，
     * 623 的源码闸已钉死「全仓只有一个构造点、两页吃同一个变量」）。
     * 切页只做「玄览组合被销毁」这件事，**不碰** TeachSessionState —— 若生产在切页时
     * 复位了教学态，下面的 `capsulePresent()` 立刻为 false，判据①就红。
     */
    private class TwoPageFixture {
        var page = Page.XUANLAN
            private set
        val sharedMessages = mutableListOf<String>()

        fun switchTo(next: Page) {
            page = next
        }

        fun sendFromBaopu(text: String) {
            require(page == Page.BAOPU) { "这条必须从抱朴页发出" }
            sharedMessages += text
        }

        /** 胶囊在不在：只看玄览在屏 + 真合成口径。 */
        fun capsulePresent(): Boolean = page == Page.XUANLAN && TeachSessionState.teachingNow()

        /** 胶囊能不能看到那条：胶囊只读 controller.messages（源码闸另验这一行）。 */
        fun capsuleCanSee(text: String): Boolean = capsulePresent() && text in sharedMessages
    }

    private fun draft(name: String = "638-流程"): TeachSkillDraft = TeachSkillDraft(
        skill = TeachSkill(
            name = name,
            purpose = "夹具",
            createdAtMs = 0L,
            startUrl = "https://example.com/",
            allowedDomains = emptyList(),
            clickTagWhitelist = emptyList(),
            fingerprint = TeachFingerprint(emptyList()),
            steps = emptyList(),
        ),
        warnings = emptyList(),
        summary = "夹具草案",
    )

    @Before
    fun freshProcess() {
        // 每个用例都从「新进程」起步，避免 object 单例把状态带到下一条用例。
        TeachSessionState.simulateNewProcess()
    }

    // ── 判据①：方向二（教学中切抱朴发一条 → 回玄览胶囊仍在、能看到那条）─────────

    @Test
    fun `判据1_方向二_切抱朴发消息后回玄览胶囊仍在且能看到那条`() {
        val f = TwoPageFixture()
        TeachSessionState.beginRecording()
        TeachSessionState.noteSteps(2)
        assertTrue("前提：教学已开始", TeachSessionState.teachingNow())

        f.switchTo(Page.BAOPU)
        assertFalse("623 收窄不变：抱朴页没有胶囊", f.capsulePresent())
        f.sendFromBaopu("[当前页: https://example.com/] 638-step-ok")

        f.switchTo(Page.XUANLAN)
        assertTrue("切回玄览，教学必须还活着（623B 偏差 11 的病）", TeachSessionState.teachRecording)
        assertEquals("步数不丢", 2, TeachSessionState.teachStepCount)
        assertTrue("胶囊仍在", f.capsulePresent())
        assertTrue(
            "能看到抱朴发的那条",
            f.capsuleCanSee("[当前页: https://example.com/] 638-step-ok"),
        )
    }

    @Test
    fun `判据1负例_教学没开始就切页_胶囊不该在`() {
        val f = TwoPageFixture()
        f.switchTo(Page.BAOPU)
        f.sendFromBaopu("没在教学时发的")
        f.switchTo(Page.XUANLAN)
        assertFalse("非教学模式整个浮层不进组合树（判据⑨）", f.capsulePresent())
        assertFalse(f.capsuleCanSee("没在教学时发的"))
    }

    // ── 判据②：切页 / 切标签不结束教学 ─────────────────────────────────────

    @Test
    fun `判据2_切标签不结束教学_状态原样恢复`() {
        val f = TwoPageFixture()
        TeachSessionState.beginRecording()
        TeachSessionState.noteSteps(5)
        f.switchTo(Page.BAOPU)
        f.switchTo(Page.XUANLAN)
        assertTrue("录制中切标签回来还在录", TeachSessionState.teachRecording)
        assertEquals("步数原样恢复", 5, TeachSessionState.teachStepCount)

        val d = draft()
        TeachSessionState.finishRecording(d)
        f.switchTo(Page.BAOPU)
        f.switchTo(Page.XUANLAN)
        assertEquals("草案原样恢复", d, TeachSessionState.teachDraft)
        assertFalse("录制位已停", TeachSessionState.teachRecording)
        assertTrue("待确认仍算教学未结束（623 判据⑪）", TeachSessionState.teachingNow())
    }

    @Test
    fun `判据2负例_切标签若复位教学_断言必须抓到`() {
        TeachSessionState.beginRecording()
        TeachSessionState.noteSteps(3)
        assertTrue("前提：切标签前在录制", TeachSessionState.teachRecording)
        // 错误实现的等价动作（把复位塞进切标签链）：三态归零 ⇒ 判据应判红
        TeachSessionState.finishRecording(null)
        assertFalse("错误实现必须让 teachingNow 变 false", TeachSessionState.teachingNow())
        assertEquals(0, TeachSessionState.teachStepCount)
    }

    // ── 判据③：显式停止 / 保存 / 放弃 ──────────────────────────────────────

    @Test
    fun `判据3_显式停止三态归零胶囊消失`() {
        TeachSessionState.beginRecording()
        TeachSessionState.noteSteps(7)
        assertTrue(TeachSessionState.teachingNow())
        TeachSessionState.finishRecording(null) // 0 步 / 没出草案：623 真机「结束示教」那条路
        assertFalse(TeachSessionState.teachRecording)
        assertEquals(0, TeachSessionState.teachStepCount)
        assertNull(TeachSessionState.teachDraft)
        assertFalse("胶囊与半屏一起消失", TeachSessionState.teachingNow())
    }

    @Test
    fun `判据3_出了草案要等保存或放弃才结束_参数化只换内容`() {
        TeachSessionState.beginRecording()
        TeachSessionState.noteSteps(4)
        val d = draft()
        TeachSessionState.finishRecording(d)
        assertTrue("待确认 ⇒ 教学未结束", TeachSessionState.teachingNow())
        assertEquals(d, TeachSessionState.teachDraft)

        TeachSessionState.replaceDraft(draft("改过名字"))
        assertTrue("参数化不改教学是否进行", TeachSessionState.teachingNow())
        assertEquals("改过名字", TeachSessionState.teachDraft?.skill?.name)

        TeachSessionState.endTeaching() // 保存 / 放弃草案
        assertFalse(TeachSessionState.teachRecording)
        assertEquals(0, TeachSessionState.teachStepCount)
        assertNull(TeachSessionState.teachDraft)
        assertFalse(TeachSessionState.teachingNow())
    }

    // ── 判据⑤：进程重启 ⇒ 教学已结束 ───────────────────────────────────────

    @Test
    fun `判据5_进程重启教学已结束_内存态不落盘`() {
        TeachSessionState.beginRecording()
        TeachSessionState.noteSteps(9)
        TeachSessionState.finishRecording(draft())
        assertTrue("前提：重启前教学还在（待确认）", TeachSessionState.teachingNow())

        TeachSessionState.simulateNewProcess()
        assertFalse(TeachSessionState.teachRecording)
        assertEquals(0, TeachSessionState.teachStepCount)
        assertNull(TeachSessionState.teachDraft)
        assertFalse(TeachSessionState.teachingNow())
    }

    // ── 判据④ ＋ 其它源码闸：从磁盘读真源码，逐条带负例变异 ──────────────────

    private fun repoFile(rel: String): File =
        listOf(File(rel), File("../$rel"), File("app/$rel"))
            .firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 $rel")

    private fun strip(src: String): String = src.replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun code(rel: String): String = strip(repoFile(rel).readText(Charsets.UTF_8))

    private val browserRel = "app/src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt"
    private val stateRel = "app/src/main/java/com/example/zhengdao/baopu/TeachSessionState.kt"
    private val wiringRel = "app/src/main/java/com/example/zhengdao/baopu/BaopuBrowserWiring.kt"
    private val holderRel = "app/src/main/java/com/example/zhengdao/ui/browser/BrowserHolder.kt"
    private val mainRel = "app/src/main/java/com/example/zhengdao/MainActivity.kt"
    private val capsuleRel = "app/src/main/java/com/example/zhengdao/ui/browser/XuanlanCapsule.kt"

    /** 全仓主源码（文件名 → 剥注释后的源码）。 */
    private fun sources(): List<Pair<String, String>> =
        repoFile("app/src/main/java/com/example/zhengdao").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to strip(it.readText(Charsets.UTF_8)) }
            .toList()

    /** `teachRecording` 的**定义**（var/val 声明），不是读取；注释已剥掉。 */
    private val defRegex = Regex("""\b(?:var|val)\s+teachRecording\b""")

    private fun definitionCount(list: List<Pair<String, String>>): Int =
        list.sumOf { (_, src) -> defRegex.findAll(src).count() }

    private fun definitions(list: List<Pair<String, String>>): List<String> =
        list.flatMap { (name, src) ->
            src.lines().filter { defRegex.containsMatchIn(it) }.map { "$name: ${it.trim()}" }
        }

    @Test
    fun `判据4_全仓只有一处定义teachRecording_且玄览零定义`() {
        val list = sources()
        val hits = definitions(list)
        assertEquals("三态只许一处定义：\n${hits.joinToString("\n")}", 1, hits.size)
        assertTrue("那一份必须在进程级持有者里", hits.first().startsWith("TeachSessionState.kt"))
        assertEquals("玄览不许再留任何本地定义", 0, defRegex.findAll(code(browserRel)).count())
        assertTrue("玄览只读持有者", code(browserRel).contains("val teaching = TeachSessionState.teachingNow()"))
        // 「不许造第二个真相源」的另一半：动作层不许再另存步数 / 草案。
        val replay = code("app/src/main/java/com/example/zhengdao/baopu/TeachReplay.kt")
        assertFalse("TeachSessionUiModel 不许再存 stepCount", replay.contains("var stepCount"))
        assertFalse("TeachSessionUiModel 不许再存 draft", replay.contains("var draft"))
        assertTrue("动作层的 recording 只是录制器守卫（派生读）", replay.contains("val recording: Boolean get() = recorder != null"))
    }

    @Test
    fun `判据4负例_再造一个remember定义必红`() {
        val list = sources()
        assertEquals("前提：真源码恰好一处", 1, definitionCount(list))
        val mutated = list.map { (name, src) ->
            name to if (name == "BrowserScreen.kt") {
                src + "\n    var teachRecording by remember { mutableStateOf(false) }\n"
            } else {
                src
            }
        }
        assertEquals("第二个 remember 必须被数出来", 2, definitionCount(mutated))
        assertEquals("判据必须因此判红", 2, definitions(mutated).size)
    }

    @Test
    fun `判据4负例_把唯一定义删掉必红`() {
        val list = sources()
        val mutated = list.map { (name, src) ->
            name to if (name == "TeachSessionState.kt") {
                src.replace("var teachRecording: Boolean by mutableStateOf(false)", "// removed")
            } else {
                src
            }
        }
        assertEquals("定义被删 ⇒ 0 处，判据必红", 0, definitionCount(mutated))
        assertEquals("前提：真源码仍在", 1, definitionCount(list))
    }

    /** 取一个 `header` 开头的大括号块（与 XuanlanCapsuleGateTest 同款）。 */
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

    /** 同款但收全：rebase 后 624 又加了一个 DisposableEffect(Unit)，漏一个＝漏一条离屏链。 */
    private fun blocks(src: String, header: String): List<String> {
        val out = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = src.indexOf(header, from)
            if (at < 0) break
            val open = src.indexOf('{', at)
            if (open < 0) break
            var depth = 0
            var end = -1
            for (i in open until src.length) {
                when (src[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { end = i + 1; break }
                    }
                }
            }
            if (end < 0) break
            out += src.substring(open, end)
            from = end
        }
        return out
    }

    /** 切页 / 切标签链里若碰到教学态（尤其复位），判据②就该红。 */
    private fun tabSwitchEndsTeaching(browser: String): Boolean {
        val tabEffects = blocks(browser, "LaunchedEffect(active?.id)")
        val disposeEffects = blocks(browser, "DisposableEffect(Unit)")
        if (tabEffects.isEmpty() || disposeEffects.isEmpty()) return true
        return (tabEffects + disposeEffects).any { it.contains("TeachSessionState.") }
    }

    @Test
    fun `判据2_源码闸_切标签与玄览离屏都不许复位教学`() {
        val browser = code(browserRel)
        assertNotNullBlock(block(browser, "LaunchedEffect(active?.id)"))
        assertFalse("切标签 effect 不许碰教学态归零", tabSwitchEndsTeaching(browser))
        val onHidden = block(code(holderRel), "internal fun onBrowserHidden()")
        assertNotNullBlock(onHidden)
        assertFalse(
            "玄览离屏不许复位教学（BrowserHolder 只管 WebView）",
            onHidden!!.contains("TeachSessionState"),
        )
    }

    private fun assertNotNullBlock(b: String?) {
        assertTrue("源码块必须切得到", b != null)
    }

    @Test
    fun `判据2负例_切标签塞进一行归零必红`() {
        val browser = code(browserRel)
        val tabEffect = block(browser, "LaunchedEffect(active?.id)")!!
        val injected = tabEffect.replaceFirst("{", "{\n        TeachSessionState.endTeaching()")
        val mutated = browser.replace(tabEffect, injected)
        assertTrue("塞进去的复位必须被判据抓住", tabSwitchEndsTeaching(mutated))
        assertFalse("真源码必须干净", tabSwitchEndsTeaching(browser))
    }

    /** 教学复位只许落在玄览的「保存 / 放弃」路径；别处（含 622/624 新链）一律不许。 */
    private fun resetSites(list: List<Pair<String, String>>): List<String> =
        list.flatMap { (name, src) ->
            src.lines().filter { it.contains("TeachSessionState.endTeaching()") }.map { name }
        }

    @Test
    fun `判据2_源码闸_复位只许发生在玄览保存放弃路径`() {
        val list = sources()
        val sites = resetSites(list)
        assertTrue("复位入口必须存在（保存 / 放弃）", sites.isNotEmpty())
        assertTrue("别处不许复位教学：$sites", sites.all { it == "BrowserScreen.kt" })
    }

    @Test
    fun `判据2负例_离屏钩子里偷加一次复位必红`() {
        val list = sources()
        assertTrue("前提：624 的 ObserveCenter.kt 确实在扫的树里", list.any { it.first == "ObserveCenter.kt" })
        val mutated = list.map { (name, src) ->
            name to if (name == "ObserveCenter.kt") {
                src + "\ninternal fun leaky() = TeachSessionState.endTeaching()\n"
            } else {
                src
            }
        }
        assertTrue(
            "离屏链里偷偷复位必须被数出来",
            resetSites(mutated).any { it != "BrowserScreen.kt" },
        )
        assertTrue("真源码必须干净", resetSites(list).all { it == "BrowserScreen.kt" })
    }

    /** 状态持有者不许出现任何持久化 API（纯内存 ⇒ 进程被杀即结束）。 */
    private fun persistsState(state: String): Boolean = listOf(
        "getSharedPreferences(", "openOrCreateDatabase(", "SQLiteOpenHelper(",
        ".jsonl", "writeText(", "putString(", "putInt(", "DataStore",
    ).any { state.contains(it) }

    @Test
    fun `判据5_源码闸_持有者纯内存无任何持久化`() {
        val state = code(stateRel)
        assertFalse("教学态不落盘（发现持久化 API）", persistsState(state))
        assertTrue("合成口径只认录制中与待确认", state.contains("fun teachingNow(): Boolean = teachRecording || teachDraft != null"))
        assertTrue("它是 object（进程级，不是 Activity/组合字段）", state.contains("object TeachSessionState"))
    }

    @Test
    fun `判据5负例_往持有者里加一行落盘必红`() {
        val state = code(stateRel)
        assertFalse("前提：真源码干净", persistsState(state))
        assertTrue(
            "加一行 SP 写入必须被抓住",
            persistsState(state + "\nprivate fun leak() = getSharedPreferences(\"teach\", 0)\n"),
        )
    }

    @Test
    fun `判据1_源码闸_同一控制器两页共享_胶囊只读那份messages`() {
        val main = code(mainRel)
        assertTrue("控制器只造一次", Regex("""BaopuChatController\(""").findAll(main).count() == 1)
        assertTrue("控制器在 when(tab) 之上（切页不重建）", main.indexOf("val chatController") < main.indexOf("when (tab)"))
        assertTrue("玄览接同一个 controller", main.contains("controller = chatController"))
        assertTrue("抱朴接同一个 controller", main.contains("BaopuScreen(\n                    controller = chatController"))
        val capsule = code(capsuleRel)
        assertTrue("胶囊只读主会话 messages", capsule.contains("messages = controller.messages"))
        assertTrue("胶囊展示尾部消息", capsule.contains("messages.takeLast("))
    }

    @Test
    fun `判据1负例_控制器造两份必红`() {
        val main = code(mainRel)
        assertEquals("前提：真源码只造一次", 1, Regex("""BaopuChatController\(""").findAll(main).count())
        val mutated = main.replaceFirst("val chatController", "val chatController2 = com.example.zhengdao.ui.baopu.BaopuChatController(homeContext)\n    val chatController")
        assertEquals("多造一份必须被数出来", 2, Regex("""BaopuChatController\(""").findAll(mutated).count())
    }

    @Test
    fun `判据1_源码闸_UI会话模型是进程级实例_作用域不随组合取消`() {
        val wiring = code(wiringRel)
        val browser = code(browserRel)
        assertTrue("玄览取的是接线层那份单例", wiring.contains("internal fun uiTeachModel(appCtx: Context): TeachSessionUiModel = synchronized(this)"))
        assertTrue("共用进程级 teachScope", wiring.contains("scope = teachScope"))
        assertTrue("teachScope 是 SupervisorJob 进程级域", wiring.contains("CoroutineScope(SupervisorJob() + Dispatchers.Main)"))
        assertFalse("玄览不许再有 rememberCoroutineScope（切页会取消录制器）", browser.contains("rememberCoroutineScope"))
        assertFalse("玄览不许再自己 new 会话模型", browser.contains("TeachSessionUiModel("))
    }

    @Test
    fun `判据11负例_合成口径改成恒真必红`() {
        val state = code(stateRel)
        assertTrue("前提：真口径只认两态", state.contains("fun teachingNow(): Boolean = teachRecording || teachDraft != null"))
        val mutated = state.replace(
            "fun teachingNow(): Boolean = teachRecording || teachDraft != null",
            "fun teachingNow(): Boolean = true",
        )
        assertFalse("恒真口径必须被判据抓住", mutated.contains("fun teachingNow(): Boolean = teachRecording || teachDraft != null"))
        assertTrue("变异确实改了文件", mutated.contains("fun teachingNow(): Boolean = true"))
    }
}
