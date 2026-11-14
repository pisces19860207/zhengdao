// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 学习板块阶段一门禁（源码断言＋负例变异，房型照 XuanlanCapsuleGateTest）。
//
// 为什么是源码级断言：JVM 单测起不来 Compose + WebView（同一句仓规），判据一律
// 「剥注释后的真源码」＋ 纯函数实跑（运行时那半边在 ObserveRowsTest／ObserveCenterTest／
// NotLearnedStoreTest／LearnTriggersTest／ObserveTtlTest）。每条判据配**负例变异**：
// 先对真源码判「绿」，再把变异喂给同一个判别函数判「红」——变异不红＝判据空洞，本文件自己红。
package com.example.zhengdao.baopu.learn

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnBoardGateTest {

    private fun repoFile(rel: String): File =
        listOf(File(rel), File("../$rel"), File("app/$rel"))
            .firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 $rel")

    /** 剥注释（先块、再整行）：判据只读代码，KDoc 里写着判据词不许被当成实现。 */
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

    private val learnDir = "app/src/main/java/com/example/zhengdao/baopu/learn"
    private val prefsRel = "$learnDir/ObservePrefs.kt"
    private val scriptRel = "$learnDir/ObserveScript.kt"
    private val rowsRel = "$learnDir/ObserveRows.kt"
    private val centerRel = "$learnDir/ObserveCenter.kt"
    private val triggersRel = "$learnDir/LearnTriggers.kt"
    private val storeRel = "$learnDir/NotLearnedStore.kt"
    private val browserRel = "app/src/main/java/com/example/zhengdao/ui/browser/BrowserScreen.kt"
    private val pillRel = "app/src/main/java/com/example/zhengdao/ui/browser/NotLearnedCapsulePill.kt"
    private val sectionRel = "app/src/main/java/com/example/zhengdao/ui/baopu/LearnBoardSection.kt"
    private val govRel = "app/src/main/java/com/example/zhengdao/ui/baopu/GovernanceCenterScreen.kt"

    /** learn 包全部源码（剥注释）；空转守卫：一件都没有 ⇒ 门禁自己红。 */
    private fun learnSources(): List<String> {
        val dir = listOf(File(learnDir), File("../$learnDir")).firstOrNull { it.isDirectory }
            ?: throw AssertionError("learn 包目录不存在")
        val files = dir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray()
        assertTrue("门禁不许空转（learn 包至少 1 件）", files.isNotEmpty())
        return files.map { stripComments(it.readText()) }
    }

    /** 变异喂给**同一个**判别函数：真源码必须绿、变异必须红，否则这条判据空洞。 */
    private fun assertMutationCaught(good: String, mutated: String, what: String, discriminator: (String) -> Boolean) {
        assertTrue("真源码必须过判别（$what）", discriminator(good))
        assertFalse("变异必须被抓（$what）", discriminator(mutated))
    }

    // ── 判据①：默认开、可关；关后 0 记录 ────────────────────────────────────────

    @Test
    fun `判据1_观察开关默认开`() {
        val src = code(prefsRel)
        assertMutationCaught(
            src,
            src.replaceFirst("getBoolean(KEY, true)", "getBoolean(KEY, false)"),
            "默认开",
        ) { it.contains("getBoolean(KEY, true)") }
    }

    @Test
    fun `判据1_收割循环有开关守卫`() {
        val src = code(centerRel)
        val tick = block(src, "suspend fun tick")
        assertNotNull("tick 必须有", tick)
        assertMutationCaught(
            tick!!,
            tick.replaceFirst("if (!enabled()) {", "if (false) {"),
            "tick 开关守卫",
        ) { it.contains("if (!enabled())") && it.contains("closeSession()") }
        // 丢批次机制＝「声明 + tick 内消费」两处 discardNext = false；拆掉任意一处 ⇒ 计数少一
        val occurrences = Regex("discardNext = false").findAll(src).count()
        assertTrue("丢批次机制在（声明＋消费两处，实测 $occurrences 处）", occurrences >= 2)
        val noDiscard = src.replaceFirst("discardNext = false", "/* no-op */")
        assertEquals("变异（丢批次机制被拆）必须被抓", occurrences - 1, Regex("discardNext = false").findAll(noDiscard).count())
    }

    @Test
    fun `判据1_开关拨动立即回读`() {
        val src = code(sectionRel)
        val switch = block(src, "onCheckedChange")
        assertNotNull("学习板块页必须有开关接线", switch)
        assertMutationCaught(
            switch!!,
            switch.replaceFirst("ObserveCenter.refresh()", ""),
            "拨动回读",
        ) { it.contains("ObserveCenter.refresh()") }
    }

    // ── 判据②：不记正文、不记整页 HTML、不出网 ──────────────────────────────────

    @Test
    fun `判据2_注入脚本无HTML通道_无正文键_无值通道`() {
        val src = code(scriptRel)
        assertMutationCaught(
            src,
            src.replaceFirst("function faces(el)", "function faces(el){var _h=document.body.innerHTML;}function faces2(el)"),
            "无 HTML 通道",
        ) { s -> !s.contains("innerHTML") && !s.contains("outerHTML") }
        assertFalse("事件载荷不许带 value 键（值永不出页）", Regex("value\\s*:").containsMatchIn(src))
        assertFalse("载荷不许带 text 键（正文不出页）", Regex("[{,]\\s*text\\s*:").containsMatchIn(src))
        // textContent 只许出现在身份面助手 txt() 里（accName 的落点），不许抓段落
        val hits = Regex("textContent").findAll(src).count()
        assertTrue("textContent 只许在身份面助手 txt() 里（实测 $hits 处）", hits in 1..2)
    }

    @Test
    fun `判据2_事件行键封闭集_无正文列`() {
        val rows = code(rowsRel)
        // 事件行的 KEYS（以 "k" 开头那个；身份面 KEYS 同文件另有其一，不许混）
        val keys = Regex("val KEYS = setOf\\(\"k\"[^)]*\\)").find(rows)?.groupValues?.get(0)
            ?: throw AssertionError("事件行 KEYS 闭集必须存在")
        assertTrue(keys.contains("\"vs\""))
        // 判别函数：键集里出现任何正文列都算违规；变异（加 text 列）必须被抓
        assertMutationCaught(keys, keys.replace("\"vs\")", "\"vs\", \"text\")"), "事件行无正文列") { s -> !s.contains("text") }
        assertTrue("事件行九键（k/t/doc/seq/a/url/nc/el/vs）", keys.contains("\"el\"") && keys.contains("\"nc\""))
    }

    @Test
    fun `判据2_learn包不出网`() {
        learnSources().forEachIndexed { i, src ->
            assertFalse("learn 包文件 #$i 不许有 http://", src.contains("http://"))
            assertFalse("learn 包文件 #$i 不许有 https://", src.contains("https://"))
            assertFalse("learn 包文件 #$i 不许有 URL(", src.contains("URL("))
            assertFalse("learn 包文件 #$i 不许有 okhttp", src.contains("okhttp"))
            assertFalse("learn 包文件 #$i 不许有 HttpURLConnection", src.contains("HttpURLConnection"))
        }
    }

    // ── 判据③：密码/验证码整条丢弃（页内源头闸＋原生第二道）──────────────────────

    @Test
    fun `判据3_页内敏感词表齐_源头丢弃`() {
        val src = code(scriptRel)
        listOf("密码", "验证码", "captcha", "otp", "pass").forEach { w ->
            assertTrue("页内词表必须有 $w", src.contains(w))
        }
        val push = block(src, "function push")
        assertNotNull(push)
        assertTrue("敏感源在 push 第一行整条丢弃", push!!.contains("if(sens(el))return"))
        // 变异：源头闸放行 ⇒ 红
        assertMutationCaught(push, push.replaceFirst("if(sens(el))return", ""), "源头闸") {
            it.contains("if(sens(el))return")
        }
    }

    @Test
    fun `判据3_原生第二道闸词表齐`() {
        val src = code(rowsRel)
        assertMutationCaught(
            src,
            src.replace("验证码", ""),
            "第二道闸词表",
        ) { it.contains("验证码") && it.contains("isSensitive()") }
    }

    @Test
    fun `判据3_敏感节点身份面抹空`() {
        val src = code(rowsRel)
        assertMutationCaught(
            src,
            src.replaceFirst("f.copy(name = \"\"", "f.copy(name = f.name"),
            "敏感节点身份抹空",
        ) { it.contains("f.copy(name = \"\"") }
    }

    // ── 判据④：24h 自清（时间注入） ─────────────────────────────────────────────

    @Test
    fun `判据4_TTL常量与注入时钟在源码`() {
        val store = code(storeRel)
        assertTrue("TTL = 24h 字面", store.contains("86_400_000L"))
        val center = code(centerRel)
        assertTrue("pruneAll 按 id 判旧", center.contains("pruneAll"))
        assertTrue("时钟可注入", center.contains("clock: () -> Long"))
        assertTrue("收割通道可注入", center.contains("harvest: (tabId: Int?) -> String?"))
        assertTrue("观察面通道可注入", center.contains("nodeTable: (tabId: Int?) -> String?"))
    }

    // ── 判据①（对齐 622/645 口径）：除 BrowserPort 内部实现外零 BrowserCommands 引用 ──

    @Test
    fun `判据1对齐622_645_learn包零BrowserCommands直引_两通道只认端口`() {
        learnSources().forEachIndexed { i, src ->
            assertFalse("learn 包文件 #$i 不许直引 BrowserCommands（622/645 判据①）", src.contains("BrowserCommands."))
        }
        val center = code(centerRel)
        assertTrue("事件收割走端口 probe（TeachRecorder 同款模板脚本口径）", center.contains("BrowserPort.probe"))
        assertTrue("节点表走端口 observe（NODE_TABLE 枚举，脚本内置传不进）", center.contains("BrowserPort.observe"))
        assertTrue("观察者身份＝TEACH_RECORD", center.contains("PortCaller.TEACH_RECORD"))
        assertTrue("观察项＝枚举，不传脚本", center.contains("ObserveItem.NODE_TABLE"))
        // 负例：换成 BrowserCommands 直引 ⇒ 判别函数必抓
        assertMutationCaught(
            center,
            center.replaceFirst("BrowserPort.probe", "BrowserCommands.execJs"),
            "零直引",
        ) { s -> !s.contains("BrowserCommands.") }
    }

    // ── 判据⑤：四类触发各出一条卡（≤3 问） ──────────────────────────────────────

    @Test
    fun `判据5_四类触发都在校验器里`() {
        val src = code(triggersRel)
        listOf("AMBIGUOUS_LOCATOR", "NO_EVIDENCE", "PARAM_UNCERTAIN", "INTENT_UNCERTAIN").forEach { t ->
            assertTrue("校验器必须认 $t", src.contains(t))
        }
        val validate = block(src, "fun validate")
        assertNotNull(validate)
        listOf("ambiguous(", "noEvidence(", "paramUncertain(", "intentUncertain(").forEach { f ->
            assertTrue("validate 必须调 $f", validate!!.contains(f))
        }
    }

    @Test
    fun `判据5_卡面走AskUserCard且选项封四`() {
        val src = code(triggersRel)
        assertTrue("卡面是 203 房型 AskUserCard", src.contains("AskUserCard"))
        assertMutationCaught(
            src,
            src.replace("take(4)", "take(40)"), // 全量替换：歧义定位与意图卡两处都钉
            "选项封 4（不刷屏）",
        ) { it.contains("take(4)") }
    }

    // ── 判据⑥：「就教这一步」只补该步（不重录、不建草案）─────────────────────────

    @Test
    fun `判据6_learn包不启示教录制_不建草案`() {
        learnSources().forEachIndexed { i, src ->
            assertFalse("learn 包文件 #$i 不许启 TeachRecorder", src.contains("TeachRecorder("))
            assertFalse("learn 包文件 #$i 不许启 TeachSessionUiModel", src.contains("TeachSessionUiModel"))
            assertFalse("learn 包文件 #$i 不许建技能草稿", src.contains("TeachSkillDraft"))
        }
    }

    @Test
    fun `判据6_单步复验与武装超时在源码`() {
        val src = code(centerRel)
        val capture = block(src, "private fun tryCapture")
        assertNotNull(capture)
        assertMutationCaught(
            capture!!,
            capture.replaceFirst("locatorMatchCount(page, ev.faces) > 1", "false"),
            "命中>1 整条不教",
        ) { it.contains("locatorMatchCount(page, ev.faces) > 1") }
        assertTrue("武装有超时", src.contains("ARM_TIMEOUT_MS"))
        assertTrue("补录不落观察账（关观察支路不 ingest）", src.contains("tryCapture(null, result)"))
    }

    // ── 判据⑦：清单与数字一致、归零隐藏 ──────────────────────────────────────────

    @Test
    fun `判据7_计数只有一个出口`() {
        val src = code(storeRel)
        // 判别函数：getter 必须恰是 items.size（拼上 resolved 也算违规）
        assertMutationCaught(
            src,
            src.replaceFirst("val count: Int get() = items.size", "val count: Int get() = items.size + resolved.size"),
            "count 唯一出口＝未消数",
        ) { s -> s.contains("val count: Int get() = items.size") && !s.contains("items.size +") }
        assertTrue("文件名就是 未学会.json", src.contains("FILE_NAME = \"未学会.json\""))
    }

    @Test
    fun `判据7_胶囊橙点归零不组合_教学态不显示`() {
        val pill = code(pillRel)
        assertMutationCaught(
            pill,
            pill.replaceFirst("if (count <= 0) return", "if (count < 0) return"),
            "归零即返回（不组合）",
        ) { it.contains("if (count <= 0) return") }
        val browser = code(browserRel)
        val teaching = block(browser, "if (teaching)")
        assertNotNull("623 教学块还在", teaching)
        assertTrue("教学块里是教学胶囊（623 锚点原样）", teaching!!.contains("XuanlanCapsuleLayer("))
        assertFalse("橙点件不许混进教学块（教学模式不显示橙点）", teaching.contains("NotLearnedCapsulePill("))
        assertMutationCaught(
            browser,
            browser.replaceFirst("learnState.count > 0", "learnState.count >= 0"),
            "兄弟分支 count>0（归零不亮）",
        ) { it.contains("else if (learnState.count > 0)") }
    }

    @Test
    fun `判据7_清单入口在治理中心学习板块块`() {
        val center = code(govRel)
        assertTrue("治理中心要挂学习板块（单一入口）", center.contains("LearnBoardSection()"))
        val section = code(sectionRel)
        assertTrue("块内有开关", section.contains("ObservePrefs.set"))
        assertTrue("块内有清观察", section.contains("clearObservations"))
        assertTrue("块内有清单", section.contains("learn_list_title"))
        assertTrue("块内答问走 ObserveCenter.answer（与清单同一真相源）", section.contains("ObserveCenter.answer"))
    }

    @Test
    fun `落盘真源_抱朴记忆下的观察目录`() {
        val center = code(centerRel)
        assertTrue("目录真源走 MemoryLayout", center.contains("MemoryLayout.globalMemoryDir"))
        assertTrue("观察目录段（231 原文）", center.contains("DIR_NAME"))
        val store = code(storeRel)
        assertTrue("清单文件名（231 原文）", store.contains("FILE_NAME = \"未学会.json\""))
    }
}
