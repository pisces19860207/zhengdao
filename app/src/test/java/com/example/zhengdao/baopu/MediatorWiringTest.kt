// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百五十八批 · 调解器接线的判据与负例（派工单 §1 W1–W3、§2 N1、§4 六条负例）。
//
// 这批要证明的不是"实现存在"（238 已证），而是**它在生产链路上真的生效**：
//   W1 换档：每轮用户输入换一次档，档位只在进程内（重启回 CALM、换会话回 CALM）；
//   W2 落账：附和／空转道歉／情绪镜像各在 208 落一行；闲聊态一行都没有；
//   W3 回灌：`guidance()` 那一句进上下文，且**只有那一句**（无档位名、无数字、无画像词）；
//   N1 对照：同一段假对话，接线前 208 零行、接线后一行。
//
// 六条负例（派工单 §4）在这里逐条变成断言，其中"落盘／两个调用点／改账本"三条是**源码门禁**——
// 它们不是行为能测出来的东西（写盘也照样返回正确档位），只能扫源码，所以每条都配一个
// 「合成违规样本必须被抓出来」的自证，免得门禁恒绿。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class MediatorWiringTest {

    private lateinit var root: File

    /** 判据里的"现在"：固定值，不吃系统时钟（调解器的 30 分钟降档靠它）。 */
    private val now = 1_760_000_000_000L

    @Before
    fun setUp() {
        root = File.createTempFile("zd258-mediator", "").let { it.delete(); it.mkdirs(); it }
        DecisionLedger.install(root)
        MediatorWiring.resetForTest()
    }

    @After
    fun tearDown() {
        MediatorWiring.resetForTest()
        DecisionLedger.uninstallForTest()
        root.deleteRecursively()
    }

    // ── 工具 ────────────────────────────────────────────────────────────────

    /** 208 的行（读成字符串列表；没有落点文件 ⇒ 空）。 */
    private fun ledgerLines(): List<String> {
        val f = DecisionLedger.fileIn(root)
        if (!f.isFile) return emptyList()
        return f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
    }

    /** 源码门禁的扫描器：只扫代码行（整行注释剔掉，"零 IO 声明"里出现词不算依赖）。 */
    private fun scanCode(src: String, tokens: List<String>): List<String> = src.lineSequence()
        .map { it.trimStart() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("*/") }
        .map { it.substringBefore("//") }
        .filter { line -> tokens.any { line.contains(it) } }
        .toList()

    private fun src(rel: String): String = File(rel).readText(Charsets.UTF_8)

    /** 档位名/阈值/画像词：prompt 里一个都不许出现（W3 与负例②）。 */
    private val FORBIDDEN_IN_PROMPT = listOf(
        "CALM", "CAREFUL", "STRAINED", "LOOP",
        "档位", "耐心", "额度", "画像", "阈值", "失败次数",
    )

    private fun leaksInPrompt(line: String): List<String> =
        FORBIDDEN_IN_PROMPT.filter { line.contains(it) } +
            if (line.any { it.isDigit() }) listOf("阿拉伯数字") else emptyList()

    // ── W1：换档 ────────────────────────────────────────────────────────────

    @Test
    fun `W1_每轮用户输入换一次档_纠错后进CAREFUL`() {
        assertEquals(HarmonyMediator.Mood.CALM, MediatorWiring.currentMood())
        MediatorWiring.onUserTurn(
            userText = "这表第3行不对",
            modelText = "",
            sessionId = 11L,
            now = now,
        )
        assertEquals("一句短促否定就该进 CAREFUL", HarmonyMediator.Mood.CAREFUL, MediatorWiring.currentMood())
        assertEquals(1, MediatorWiring.turns())
        // 档位是**进程内**的事：208 一条都不该因为"换档"产生（换档不是违规）
        assertEquals("换档不该落账", emptyList<String>(), ledgerLines())
    }

    @Test
    fun `W1_不落盘_换会话即回CALM`() {
        MediatorWiring.onUserTurn("不对", "", sessionId = 11L, now = now)
        assertEquals(HarmonyMediator.Mood.CAREFUL, MediatorWiring.currentMood())
        // 同一会话继续 ⇒ 档位还在（进程内带得走）
        MediatorWiring.onUserTurn("帮我再看一下", "", sessionId = 11L, now = now)
        assertEquals(HarmonyMediator.Mood.CAREFUL, MediatorWiring.currentMood())
        // 换会话 ⇒ 立刻回 CALM，轮次清零（不跨会话带）
        MediatorWiring.onUserTurn("你好呀", "", sessionId = 12L, now = now)
        assertEquals("换会话必须回 CALM", HarmonyMediator.Mood.CALM, MediatorWiring.currentMood())
        assertEquals("换会话轮次要清零", 1, MediatorWiring.turns())
    }

    @Test
    fun `W1_日志行带档位_无违规与有违规两种形态`() {
        MediatorWiring.onUserTurn("这表第3行不对", "", sessionId = 11L, now = now)
        assertEquals(
            "真机日志要长这样（W1 的判据行）",
            "抱朴调解器：第 1 轮档位 CAREFUL（本轮带语气指令）｜无违规",
            MediatorWiring.logLine(MediatorWiring.currentState(), emptyList(), MediatorWiring.turns()),
        )
        val flags = MediatorWiring.onUserTurn("帮我查第3行", "你这个废物", sessionId = 11L, now = now)
        assertEquals(
            "有违规时要点名（档位也跟着走）",
            "抱朴调解器：第 2 轮档位 CAREFUL（本轮带语气指令）｜判出 1 条：mirroring",
            MediatorWiring.logLine(MediatorWiring.currentState(), flags, MediatorWiring.turns()),
        )
    }

    @Test
    fun `负例1_档位落盘_源码门禁`() {
        val tokens = listOf(
            "SharedPreferences", "getSharedPreferences", "openFileOutput", "contentResolver",
            "File(", "Files.", "Paths.", "RandomAccessFile",
            "BaopuDb", "insertOrReplace", "HarmonyStateCodec", "putString", "putInt", "putLong",
            "System.currentTimeMillis",
        )
        val hits = scanCode(src(MEDIATOR_WIRING), tokens)
        assertEquals("MediatorWiring 不许有任何持久化/取时钟的痕迹：$hits", emptyList<String>(), hits)

        // 合成违规样本必须被抓出来（否则上面那条只是"没扫到"）
        listOf(
            "val p = ctx.getSharedPreferences(\"mediator\", 0)",
            "HarmonyStateCodec.encode(\"s\", state)",
            "val now = System.currentTimeMillis()",
        ).forEach { line ->
            assertTrue("门禁没抓住违规行：$line", scanCode(line, tokens).isNotEmpty())
        }
        // 良性行不许误伤（本文件的注释里就写着"没有 SharedPreferences"）
        listOf(
            "// 本文件没有 SharedPreferences／File／数据库",
            " * 零 Android / 零 IO",
        ).forEach { line ->
            assertTrue("误伤注释行：$line", scanCode(line, tokens).isEmpty())
        }
    }

    @Test
    fun `负例6_换档只有一处调用点_源码门禁`() {
        val controller = src(CONTROLLER)
        assertEquals(
            "控制器里 onUserTurn 必须恰好一处（两处会让档位乱跳）",
            1,
            Regex("""MediatorWiring\.onUserTurn\(""").findAll(controller).count(),
        )
        // 全仓主源码里 `HarmonyMediator.observe(` 也只有一处，且就在接线层
        val callers = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .filter { it.readText(Charsets.UTF_8).contains("HarmonyMediator.observe(") }
            .map { it.name }
            .toList()
        assertEquals("换档调用点必须唯一（且只在接线层）：$callers", listOf("MediatorWiring.kt"), callers)

        // 门禁自证：两处就报两处
        val fake = "MediatorWiring.onUserTurn(a)\nMediatorWiring.onUserTurn(b)"
        assertEquals(2, Regex("""MediatorWiring\.onUserTurn\(""").findAll(fake).count())
    }

    // ── W2：违规真落 208 ────────────────────────────────────────────────────

    @Test
    fun `W2_附和_任务态翻转且无新证据_208落一行`() {
        // 第1轮：模型反对（立场 REJECT）——这一轮只是把上一轮回复喂给判官
        MediatorWiring.onUserTurn(
            userText = "这表就是错的，第3行应该是42",
            modelText = "这个结论不成立，第3行是合计行，38 没问题",
            sessionId = 11L,
            now = now,
        )
        assertEquals("上一轮只是反对，还不算附和", emptyList<String>(), ledgerLines())
        // 第2轮：模型改口赞成，且这一轮没有任何新证据
        val flags = MediatorWiring.onUserTurn(
            userText = "你再核对一遍第3行",
            modelText = "你说得对，确实错了",
            sessionId = 11L,
            now = now,
        )
        assertEquals(listOf(MediatorBan.FLATTERY), flags.map { it.ban })
        val lines = ledgerLines()
        assertEquals("附和必须落 208 一行：$lines", 1, lines.size)
        assertTrue("kind 要能读回来：${lines[0]}", lines[0].contains("\"kind\":\"eval_verdict\""))
        assertTrue("证据要带禁令键与档位：${lines[0]}", lines[0].contains("\"evidence\":\"mediator:flattery:mood="))
        assertTrue("reason 要在：${lines[0]}", lines[0].contains("模型附和"))
        assertTrue(
            "reason 的**固定前缀**必须是 mediator:flattery（kind 复用了 eval_verdict，只能靠它挂牌）：${lines[0]}",
            lines[0].contains("\"reason\":\"mediator:flattery "),
        )
        // 账本里不许出现原话（策略表 §3 第三行）
        assertTrue("208 里不许记原话", !lines[0].contains("第3行"))
        // 证据行（交付报告里引用的就是这一行；JUnit XML 的 system-out 里能抓到）
        println("[258-208] ${lines[0]}")
    }

    @Test
    fun `W2_空转道歉_连续两轮无新信息_208落一行`() {
        MediatorWiring.onUserTurn(
            userText = "帮我改第3行",
            modelText = "对不起，我太笨了，没查出来。",
            sessionId = 11L,
            now = now,
        )
        assertEquals("只一轮空转道歉不判（LIMIT=2 的边界）", emptyList<String>(), ledgerLines())
        val flags = MediatorWiring.onUserTurn(
            userText = "帮我再查一下第3行",
            modelText = "实在抱歉，我什么都做不好……",
            sessionId = 11L,
            now = now,
        )
        assertEquals(listOf(MediatorBan.APOLOGY_LOOP), flags.map { it.ban })
        val lines = ledgerLines()
        assertEquals("空转道歉必须落 208 一行：$lines", 1, lines.size)
        assertTrue(lines[0].contains("\"evidence\":\"mediator:apology_loop:mood="))
        assertTrue(
            "reason 必须带固定前缀（读侧靠它从 eval_verdict 里摘出来）：${lines[0]}",
            lines[0].contains("\"reason\":\"mediator:apology_loop "),
        )
    }

    @Test
    fun `W2_情绪镜像_攻击词与感叹堆叠_各判一次`() {
        val attack = MediatorWiring.onUserTurn(
            userText = "帮我查第3行",
            modelText = "你这个废物，说了几遍都听不懂",
            sessionId = 11L,
            now = now,
        )
        assertEquals(listOf(MediatorBan.MIRRORING), attack.map { it.ban })
        MediatorWiring.resetForTest()
        val bangs = MediatorWiring.onUserTurn(
            userText = "帮我查第3行",
            modelText = "太好了！！！真的可以～～",
            sessionId = 11L,
            now = now,
        )
        assertEquals("讨好性感叹堆叠也属情绪镜像", listOf(MediatorBan.MIRRORING), bangs.map { it.ban })
        assertEquals("两次各一行", 2, ledgerLines().size)
    }

    @Test
    fun `W2_道歉但带新选项_不算空转`() {
        MediatorWiring.onUserTurn(
            userText = "帮我改第3行",
            modelText = "对不起，上一版确实不对。改成这样：选项 A 修正公式，或选项 B 先出对照列。",
            sessionId = 11L,
            now = now,
        )
        MediatorWiring.onUserTurn(
            userText = "帮我再查一下第3行",
            modelText = "非常抱歉。改成这样：选项 A 修正公式，或选项 B 先出对照列。",
            sessionId = 11L,
            now = now,
        )
        assertEquals("道歉不罚、空转才罚——给了新出路就不判", emptyList<String>(), ledgerLines())
    }

    @Test
    fun `负例4_闲聊态不判_一个字都不落`() {
        val rounds = listOf(
            "哈哈哈" to "哈哈，你也很可爱啊！！！",
            "小笨蛋～今天也要加油哦！" to "对不起对不起对不起！！！",
            "笑死我了，你真是废物" to "你说得对！！我确实错了～～",
        )
        rounds.forEach { (user, model) ->
            val flags = MediatorWiring.onUserTurn(user, model, sessionId = 11L, now = now)
            assertEquals("闲聊态不该判违规：$user / $model", emptyList<MediatorBan>(), flags.map { it.ban })
        }
        assertEquals("闲聊态 208 必须零行", emptyList<String>(), ledgerLines())
    }

    @Test
    fun `负例3_违规必须进208_不是只记日志`() {
        val flags = MediatorWiring.onUserTurn(
            userText = "帮我查第3行",
            modelText = "你这个废物",
            sessionId = 11L,
            now = now,
        )
        assertTrue("先要有违规", flags.isNotEmpty())
        val f = DecisionLedger.fileIn(root)
        assertTrue("208 落点文件必须存在（只写日志＝负例）", f.isFile)
        assertEquals(1, ledgerLines().size)
        // 没装配落点时**不许静默**：record 返回 null 且留下 lastError（这批的失败可见口径）
        DecisionLedger.uninstallForTest()
        val draft = DecisionDraft(
            kind = DecisionKind.EVAL_VERDICT,
            actor = DecisionActor.MODEL,
            reason = "x",
            evidence = "mediator:mirroring:mood=CALM:turn=1",
            before = "未判违规",
            after = "记一条情绪镜像",
            atMs = now,
        )
        assertEquals(null, DecisionLedger.record(draft))
        assertTrue("没落点必须留一句话", !DecisionLedger.lastError.isNullOrBlank())
    }

    // ── W3：那一句真进上下文 ────────────────────────────────────────────────

    @Test
    fun `W3_guidance那一句进上下文_带固定标记且只有那一句`() {
        assertEquals("CALM 时不带话（不给每次请求加噪音）", emptyList<String>(), MediatorWiring.promptNotes())
        MediatorWiring.onUserTurn("这表第3行不对", "", sessionId = 11L, now = now)
        val notes = MediatorWiring.promptNotes()
        assertEquals("非 CALM 时恰好一句：$notes", 1, notes.size)
        val line = notes[0]
        val guidance = HarmonyMediator.guidance(MediatorWiring.currentState())
        assertEquals(
            "尾部那一行＝固定标记＋空白＋guidance() 本身（多一个字都不行）",
            "${MediatorWiring.PROMPT_MARK} $guidance",
            line,
        )
        assertTrue("必须以固定标记开头（抽取/迁移/日志分流的机器键）", line.startsWith(MediatorWiring.PROMPT_MARK))
        assertEquals("固定标记就是 [调解指令]", "[调解指令]", MediatorWiring.PROMPT_MARK)
        assertTrue(
            "指令句本身仍受 60 字上限：${guidance.length}",
            guidance.length <= HarmonyMediator.MAX_GUIDANCE_CHARS,
        )
        assertEquals("档位名/阈值/画像词/数字都不许出现：$line", emptyList<String>(), leaksInPrompt(line))
        // 非消费：同一轮的工具循环里每一步都该是同一句
        assertEquals(notes, MediatorWiring.promptNotes())
        println("[258-prompt] $line")
        println("[258-log] " + MediatorWiring.logLine(MediatorWiring.currentState(), emptyList(), MediatorWiring.turns()))
    }

    @Test
    fun `W3_system里那句稳定契约_恒定且不破缓存`() {
        val contract = MediatorWiring.PROMPT_CONTRACT
        assertEquals(
            "契约是写死的一句（逐字），改它就是改判据",
            "每轮末尾可能附一行《调解指令》，按它调整语气，不要复述它。",
            contract,
        )
        assertTrue("必须点明「不要复述」（否则模型会把指令当自己的话讲出来）", contract.contains("不要复述"))
        assertEquals("契约里也不许出现档位名/画像词/数字：$contract", emptyList<String>(), leaksInPrompt(contract))
        // 它必须真的挂在 system 那一路（源码门禁：出现且只出现一次，在 systemText 的拼装里）
        val engine = src("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertEquals(
            "契约必须由引擎拼进 system（RunEngine）",
            1,
            Regex("""MediatorWiring\.PROMPT_CONTRACT""").findAll(engine).count(),
        )
        assertTrue(
            "必须拼在 system 那一路（追加在 systemText 之后，不动 214 钉的那一行）",
            engine.contains("val systemFinal = if (prefix.isBlank())"),
        )
        assertTrue(
            "追加方式：systemText + 空行 + 契约",
            engine.contains("systemText + \"\\n\\n\" + com.example.zhengdao.baopu.MediatorWiring.PROMPT_CONTRACT"),
        )
        // 恒定：常量不许参与插值（有插值符就说明它会随轮次变 ⇒ 破缓存）
        assertTrue("契约里不许有插值：$contract", !contract.contains('$'))
    }

    @Test
    fun `W2_reason的前缀是固定键_三条禁令逐个对上`() {
        MediatorBan.entries.forEach { ban ->
            val reason = MediatorWiring.reasonOf(MediatorFlag(ban, "类别句"))
            assertTrue(
                "reason 必须以 mediator:${ban.wire} 开头：$reason",
                reason.startsWith("${MediatorWiring.REASON_PREFIX}${ban.wire} "),
            )
            assertTrue("前缀常量就是 mediator:", MediatorWiring.REASON_PREFIX == "mediator:")
        }
        // 门禁自证：没前缀的行必须对不上（否则 I4 那条排除义务无处落地）
        listOf("评测评测", "eval_verdict:跑完了", " mediator:flattery").forEach { bad ->
            assertTrue("这样的 reason 不该被当成调解器写的：$bad", !bad.startsWith(MediatorWiring.REASON_PREFIX))
        }
    }

    @Test
    fun `W3_那一句真进组装后的提示词_且不在system前缀里`() {
        // 这一条把「进上下文」从"列表里有"抬到"组装出来的请求文本里有"：
        // 引擎侧就一行 `extraNotes = ... + MediatorWiring.promptNotes()`（RunEngine.kt:2429-2434），
        // 这里用同一个组装器、同一批 extraNotes 验成品。
        MediatorWiring.onUserTurn("这表第3行不对", "", sessionId = 11L, now = now)
        val notes = MediatorWiring.promptNotes()
        assertEquals(1, notes.size)
        val prompt = ContextAssembler.assemble(
            persona = PersonaRules.assemble("A内核", "B全局", "C项目"),
            facts = HardFacts(
                workspacePath = "/sdcard/Download/证道",
                model = "test-model",
                decisions = emptyList(),
                formatRules = emptyList(),
            ),
            stickyMemories = emptyList(),
            newMemories = emptyList(),
            history = listOf(
                HistoryMessage(
                    id = "m1",
                    role = HistoryMessage.ROLE_ASSISTANT,
                    text = "上一轮回复：第3行是合计行",
                ),
            ),
            current = "这表第3行不对",
            extraNotes = notes,
        )
        val rendered = prompt.render()
        assertTrue("那一句必须出现在组装后的提示词里：$rendered", rendered.contains(notes[0]))
        assertEquals(
            "只出现一次（不重复灌）",
            1,
            Regex(Regex.escape(notes[0])).findAll(rendered).count(),
        )
        // 事实摆出来：它落在**本轮输入尾部**（CURRENT 段），不在 system 前缀里——
        // system 前缀是提示词缓存的键，每轮变一次就会把缓存全打掉（见报告 §7 缺陷 258-1）。
        assertTrue("人设/硬事实那一段（system）里不许有语气指令", !prompt.prefixText().contains(notes[0]))
    }

    @Test
    fun `负例2_档位名进了prompt会被抓出来_门禁自证`() {
        listOf(
            "CAREFUL：回答更短",
            "档位 STRAINED，耐心额度还剩 2",
            "用户画像显示失败次数 3",
        ).forEach { bad ->
            assertTrue("门禁没抓住泄漏：$bad", leaksInPrompt(bad).isNotEmpty())
        }
        listOf(
            "用户已指出问题：回答更短、先给结论、别再解释。",
            "已熔断：不再重试，只给「换件事」或「存档下次继续」两条路。",
        ).forEach { good ->
            assertEquals("误伤正常指令：$good", emptyList<String>(), leaksInPrompt(good))
        }
    }

    // ── N1：接线前 vs 接线后 ────────────────────────────────────────────────

    @Test
    fun `N1_同一段假对话_接线前208零行接线后一行`() {
        val dialogue = listOf(
            "这表就是错的，第3行应该是42" to "这个结论不成立，第3行是合计行，38 没问题",
            "你再核对一遍第3行" to "你说得对，确实错了",
        )
        // 接线前：只有 238 的既有入口（纯判据，没有落账能力）——生产链路当时就是零调用
        var prev = HarmonyMediator.State()
        dialogue.forEach { (user, model) ->
            prev = HarmonyMediator.observe(
                prev = prev,
                userText = user,
                modelText = model,
                outcome = HarmonyMediator.Outcome.None,
                now = now,
            )
        }
        // 这段对话是任务态（态本来就判得出）——所以"208 零行"不是"没进判据"，是**没接线**
        assertNotEquals(
            "这段对话不是闲聊",
            TaskMode.CHAT,
            TaskModeBoundary.classify(dialogue[1].first),
        )
        assertEquals("接线前档位机照跑（纯判据）", HarmonyMediator.Mood.CALM, prev.mood)
        assertEquals("接线前 208 无行", emptyList<String>(), ledgerLines())

        // 接线后：同一段话走生产接线点
        MediatorWiring.resetForTest()
        dialogue.forEach { (user, model) ->
            MediatorWiring.onUserTurn(user, model, sessionId = 11L, now = now)
        }
        assertEquals("接线后 208 有一行", 1, ledgerLines().size)
    }

    // ── 负例5：越界改账本 ───────────────────────────────────────────────────

    @Test
    fun `负例5_没有改DecisionLedger_也没自造kind`() {
        val ledger = src("src/main/java/com/example/zhengdao/baopu/DecisionLedger.kt")
        assertTrue(
            "DecisionLedger.kt 里不该出现调解器的任何字样（本批红线：不动它）",
            !ledger.contains("Mediator") && !ledger.contains("mediator"),
        )
        assertEquals("没有新增 MEDIATOR_FLAG 这种 kind", null, DecisionKind.ofWire("mediator_flag"))
        assertTrue(
            "复用既有 kind（EVAL_VERDICT）",
            DecisionKind.ofWire("eval_verdict") == DecisionKind.EVAL_VERDICT,
        )
    }

    // ── 词表锚点：道歉句/攻击词不许自造第二套 ───────────────────────────────

    @Test
    fun `道歉句词表锚在AnswerFirstRules_攻击词锚在ReframeFilter`() {
        val answerFirst = src("src/main/java/com/example/zhengdao/baopu/AnswerFirstRules.kt")
        assertTrue(
            "致歉 token 必须还在 AnswerFirstRules 里（不然本判据的词表就成孤儿）",
            answerFirst.contains("(?:非常|真|太|实在)?(?:抱歉|对不起|不好意思)"),
        )
        assertTrue("攻击词表仍在 ReframeFilter", ReframeFilter.isAttack("你这个废物"))
        assertTrue("自贬词表仍可数", ReframeFilter.selfDeprecationCount("我太笨了") == 1)
    }

    private companion object {
        const val CONTROLLER = "src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"
        const val MEDIATOR_WIRING = "src/main/java/com/example/zhengdao/baopu/MediatorWiring.kt"
    }
}
