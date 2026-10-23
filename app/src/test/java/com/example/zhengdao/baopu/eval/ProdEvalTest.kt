// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九十批 ② · 生产 eval 入口的门禁（派工单 §2 ＋ §3 的负例）；
// 第一百六十八批：调用点接上后，「没人调用」那条哨兵改写为「照派工单的样式调用」。
//
// **不联网、不花钱、不需要 key**：这里量的是「该不该跑、跑了记下什么、崩了会不会拖累对话」；
// 真跑模型那条路归 `EvalGateTest`（有 key 才跑）。
//
// 负例分别落在哪：
// - 「默认就打开 eval」⇒ [#闸门_纯判定_只有用户拨过才开] ＋ [#源码门禁_没有devMode并联] ＋ [#关掉时零调用]；
// - 「把失败改成阻塞对话」⇒ [#跑批抛异常也只记录_绝不冒到调用方] ＋ [#源码门禁_第一百六十八批接线后_单点收尾_不阻塞_闸门只读_写口在UI]；
// - 「顺手自动晋升/改技能/回滚」⇒ [#源码门禁_不调用任何回滚与晋升原语] ＋ 产物里 blocking/rollback 恒 false。
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProdEvalTest {

    // ── ① 闸门：默认关 ────────────────────────────────────────────────────

    @Test
    fun `闸门_纯判定_只有用户拨过才开`() {
        assertFalse("派工单 §2：默认关", ProdEvalGate.PREF_DEFAULT)
        assertFalse("没拨 ⇒ 关（生产跑批烧的是用户的额度，默认必须是关）", ProdEvalGate.enabled(false))
        assertTrue(ProdEvalGate.enabled(true))
        assertEquals("键名与自检分开（两件事两种授权）", "eval_prod_on", ProdEvalGate.PREF_KEY)
        assertFalse("不许跟 SelfCheckGate 用同一个键", ProdEvalGate.PREF_KEY == SelfCheckGate.PREF_KEY)
    }

    @Test
    fun `源码门禁_没有devMode并联_调试构建也不许自动跑`() {
        // SelfCheckGate 是 devMode || pref：自检测的是用户自己的提案，调试构建可以开箱即跑。
        // 生产 eval 烧钱，**没有**这条并联。谁加上来，这里立刻红。
        val gate = codeOf("ProdEval.kt").substringAfter("object ProdEvalGate")
        assertTrue(
            "enabled 必须只认用户开关（这段里出现 devMode/BuildConfig.DEBUG 就是给自动跑开了门）",
            gate.contains("fun enabled(prefOn: Boolean): Boolean = prefOn"),
        )
        assertFalse("闸门不许读调试构建标记", gate.contains("BuildConfig.DEBUG"))
        assertFalse("闸门不许读 devMode", gate.contains("devMode"))
    }

    @Test
    fun `关掉时零调用_不读用例不建目录不发请求`() {
        val f = Fakes(gateOn = false)
        val out = f.eval().execute("任意场景")
        assertEquals(ProdEvalOutcome.Disabled, out)
        // 「零额外耗时」要能被证伪：不是「返回得快」，是一个下游口子都没碰
        for ((name, n) in f.counts) {
            if (name != "gateOn") assertEquals("开关关着却调用了 $name（$n 次）", 0, n)
        }
        assertTrue("关着还写了日志（写日志=打开文件+取时间戳，不叫零耗时）：${f.logs}", f.logs.isEmpty())
    }

    // ── ② 打开：跑并记录 ─────────────────────────────────────────────────

    @Test
    fun `打开时跑并落产物_字段是决策口要的那几个`() {
        val f = Fakes(gateOn = true, json = casesJson("a1" to "", "a2" to ""))
        val out = f.eval().execute("夜间对账")
        assertTrue("应该跑：${describe(out)}", out is ProdEvalOutcome.Recorded)
        val rec = out as ProdEvalOutcome.Recorded
        assertEquals(2, rec.total)
        assertEquals(2, rec.passed)
        assertEquals(1.0, rec.passRate, 0.0)
        assertTrue("产物写成功才算有证据", rec.artifactWritten)

        val doc = JSONObject(requireNotNull(f.written) { "产物没落盘" })
        // 99 批证据桥的最低要求：passRate（0–1）＋ cases[].id。少一个，消费方只能 DEFER。
        assertEquals("zhengdao-prod-eval/1", doc.getString("schema"))
        val rate = doc.getDouble("passRate")
        assertTrue("passRate 必须在 0–1（EvolutionEval 的构造要求）：$rate", rate in 0.0..1.0)
        val cases = doc.getJSONArray("cases")
        assertEquals(2, cases.length())
        assertEquals("a1", cases.getJSONObject(0).getString("id"))
        assertTrue(cases.getJSONObject(0).getBoolean("ok"))
        // 口径要一起写出去，否则下次换模型/换通道跑出来的数没法比
        assertEquals("test-model", doc.getString("model"))
        assertEquals("test-provider", doc.getString("provider"))
        assertEquals("夜间对账", doc.getString("scene"))
        assertTrue("要带时间戳（决策口按「最近一次」取）", doc.getLong("ranAt") > 0)
        val det = doc.getJSONObject("determinism")
        assertTrue("默认档必须诚实标非确定性", det.getString("label").startsWith("NON_DETERMINISTIC"))
        assertTrue("没发 seed 就如实写 null", det.isNull("seed"))
        assertFalse("不许自称可复现", det.getBoolean("deterministic"))
        assertTrue("花费要可见（烧的是用户的额度）", doc.getString("costYuanEstimate").isNotBlank())
        assertTrue("日志要带 [prod-eval] 前缀", f.logs.any { it.startsWith("[prod-eval]") })
    }

    @Test
    fun `打开时fixture层用例被跳过但要留名`() {
        val f = Fakes(gateOn = true, json = casesJson("a1" to "", "b1" to "B_GLOBAL", "c1" to "C_PROJECT"))
        f.eval().execute("场景")
        assertEquals("生产口径只跑水位池", listOf("a1"), f.givenIds)
        val doc = JSONObject(requireNotNull(f.written))
        val skip = doc.getJSONArray("skipped")
        assertEquals(2, skip.length())
        assertEquals("b1", skip.getJSONObject(0).getString("id"))
        assertEquals(ProdEval.PROD_EVAL_SKIP_FIXTURE, skip.getJSONObject(0).getString("reason"))
        val personas = doc.getJSONObject("personas")
        assertEquals(2, personas.getInt("fixtureSkipped"))
        assertTrue("要写明这两层仍未实测", personas.getString("note").contains("未实测"))
    }

    @Test
    fun `产物与日志里绝不带key`() {
        val f = Fakes(gateOn = true, json = casesJson("a1" to ""), apiKey = SECRET)
        f.eval().execute("场景")
        val text = (f.written ?: "") + f.logs.joinToString("\n")
        assertFalse("key 漏进了产物/日志（它只许进请求头）", text.contains(SECRET))
        assertFalse("凭据类字段一个都不许序列化", text.contains("apiKey"))
    }

    // ── ③ 跑不了要说清，不许静默、不许阻塞 ────────────────────────────────

    @Test
    fun `没有可用通道或用例时_明确NotRun不静默`() {
        fun outcome(json: String?, provider: Provider): ProdEvalOutcome =
            Fakes(gateOn = true, json = json, provider = provider).eval().execute("场景")

        val noKey = outcome(casesJson("a1" to ""), okProvider(key = ""))
        assertTrue("没 key 必须明说：${describe(noKey)}", noKey is ProdEvalOutcome.NotRun)
        assertTrue(noKey.reason().contains("无可用通道"))

        val builtin = outcome(casesJson("a1" to ""), okProvider(key = SECRET).copy(builtinFree = true))
        assertTrue("内置免费通道整条 403，不能拿它假装跑过", builtin is ProdEvalOutcome.NotRun)

        val anthropic = outcome(casesJson("a1" to ""), okProvider(key = SECRET).copy(protocol = LlmProtocol.ANTHROPIC))
        assertTrue("评测只实现了 OpenAI 兼容形状，换协议要明说", anthropic is ProdEvalOutcome.NotRun)

        val noCases = outcome(null, okProvider())
        assertTrue("APK 里没用例要明说", noCases is ProdEvalOutcome.NotRun)
        assertTrue(noCases.reason().contains("用例读不到"))

        val broken = outcome("{ 这不是合法用例数组", okProvider())
        assertTrue("用例坏了要抛原因，不许当没这回事", broken is ProdEvalOutcome.NotRun)
        assertTrue("原因要能看出是解析失败：${broken.reason()}", broken.reason().contains("解析失败"))

        assertTrue("0 条用例不算跑过", outcome("[]", okProvider()) is ProdEvalOutcome.NotRun)
        val onlyFixture = outcome(casesJson("b1" to "B_GLOBAL"), okProvider())
        assertTrue("全是要跳过的 fixture ⇒ 没有可跑的水位，按没跑处理", onlyFixture is ProdEvalOutcome.NotRun)
    }

    @Test
    fun `跑批抛异常也只记录_绝不冒到调用方`() {
        // 负例：谁把「失败」改成「抛出/阻塞」，这里立刻红。
        val f = Fakes(gateOn = true, json = casesJson("a1" to ""))
        f.runThrows = IllegalStateException("模型通道炸了")
        val out = f.eval().execute("场景")
        assertTrue("崩了要变 NotRun：${describe(out)}", out is ProdEvalOutcome.NotRun)
        assertTrue(out.reason().contains("模型通道炸了"))
        assertTrue("崩了也要留日志（不许静默）", f.logs.any { it.contains("[prod-eval]") })

        // 连「读开关」这一步崩了也不能抛回调用方
        val boomLogs = mutableListOf<String>()
        val boom = ProdEval(
            gateOn = { throw IllegalStateException("prefs 炸了") },
            casesJson = { null },
            providerOf = { okProvider() },
            systemPromptOf = { "人设" },
            artifactPath = { "/tmp/none.json" },
            writeArtifact = { true },
            runCases = { _, _ -> error("不该被调用") },
            log = { boomLogs += it },
        )
        val o2 = runCatching { boom.execute("场景") }
        assertTrue("入口兜底没生效：抛出了 ${o2.exceptionOrNull()}", o2.isSuccess)
        val got = o2.getOrNull()
        assertTrue("读开关崩了也要变 NotRun：$got", got is ProdEvalOutcome.NotRun)
        assertTrue("这条也得留一行日志（不许静默）", boomLogs.any { it.contains("[prod-eval]") })
    }

    @Test
    fun `产物写不进时读数只进日志_但明说没有证据`() {
        val f = Fakes(gateOn = true, json = casesJson("a1" to ""), writeOk = false)
        val out = f.eval().execute("场景") as ProdEvalOutcome.Recorded
        assertFalse("写失败要如实记 false（决策口按「没有证据」DEFER）", out.artifactWritten)
        assertTrue(f.logs.any { it.contains("不许当通过") })
    }

    // ── ④ 红线：只产出数据 ────────────────────────────────────────────────

    @Test
    fun `源码门禁_不调用任何回滚与晋升原语`() {
        val code = codeOf("ProdEval.kt")
        // 判**调用**而不是判字：产物里那两个字段（blocking/rollbackPerformed）本来就写着 false。
        for (forbidden in listOf(
            "rollback(", "rollBack(", "restore(", "revert(", "approve(", "adopt(",
            "EvolutionPolicy.", "ProposalSelfCheck", "SelfCheckGate", "SkillBodySelfCheck",
            "personaFiles?.write", "PersonaFiles(", "setPrefOn(",
        )) {
            assertFalse("生产入口不许调用「$forbidden」：它只产出数据，点不点是人（§2）", code.contains(forbidden))
        }
        val doc = ProdEval.artifactOf(
            scene = "场景",
            ranAt = 1_792_000_000_000L,
            provider = okProvider(),
            cfg = RuntimeEval.configOf(okProvider(), "人设"),
            run = EvalRun(listOf(result("a1")), 10L),
            personaSkipped = listOf("b1"),
            determinism = EvalDeterminism(),
        )
        assertFalse("blocking 恒 false：跑批不许变成对话路径上的闸门", doc.getBoolean("blocking"))
        assertFalse("rollbackPerformed 恒 false：本批不动任何用户文件", doc.getBoolean("rollbackPerformed"))
        assertEquals("每条用例都要能对上 id（99 的解析口径）", "a1", doc.getJSONArray("cases").getJSONObject(0).getString("id"))
        assertFalse("产物里没有 key", doc.toString().contains(SECRET))
    }

    @Test
    fun `源码门禁_第一百六十八批接线后_单点收尾_不阻塞_闸门只读_写口在UI`() {
        // 168 批把调用点接上了（派工单 §2：会话收尾触发、节流、不阻塞）。
        // 这条哨兵从「谁也不许调用」改写为「照派工单的样式调用」——
        // ① 接线在、且只在控制器的收尾点；② 跑批放 IO；③ 引擎不接线（单点，防同一天烧两轮）；
        // ④ 闸门文件（ProdEval.kt）仍不养写口；⑤ 写口在 ProdEvalSettings，且设置页接上了。
        val controller = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt")
        val engine = File("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertTrue("找不到 $controller（挪走了要一起改这条门禁，不许悄悄放行）", controller.isFile)
        assertTrue("找不到 $engine", engine.isFile)
        val ctrl = codeOfAt(controller)
        assertTrue("调用点不见了：ProdEval 必须接在控制器的会话收尾点", ctrl.contains("ProdEval"))
        assertTrue("必须经 scheduleProdEvalOnSettle 排程（收尾触发）", ctrl.contains("scheduleProdEvalOnSettle"))
        assertTrue("跑批必须放 IO 线程（不阻塞对话）", ctrl.contains("Dispatchers.IO"))
        assertTrue("必须过单飞门（防并发烧两轮额度）", ctrl.contains("ProdEvalSchedule.tryAcquire"))
        assertFalse(
            "RunEngine 里出现了 ProdEval：调用点只能有一个（收尾），引擎不接线",
            codeOfAt(engine).contains("ProdEval"),
        )
        // 闸门文件不养写口（上面那条 forbidden 名单也盯着 setPrefOn）
        assertFalse("ProdEval.kt 里出现了写口（putBoolean）", codeOf("ProdEval.kt").contains("putBoolean"))
        // 写入口在 ProdEvalSettings，键名沿用闸门的，不许另起一套
        val settings = File("src/main/java/com/example/zhengdao/baopu/eval/ProdEvalSettings.kt")
        assertTrue("写入口文件不见了：ProdEvalSettings.kt", settings.isFile)
        val sw = codeOfAt(settings)
        assertTrue("写口必须落 putBoolean", sw.contains("putBoolean"))
        assertTrue("写口必须写 ProdEvalGate.PREF_KEY（eval_prod_on，不许另起键名）", sw.contains("ProdEvalGate.PREF_KEY"))
        val screen = File("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")
        assertTrue("找不到设置页", screen.isFile)
        assertTrue(
            "设置页没接写口（开关必须调 ProdEvalSettings）",
            codeOfAt(screen).contains("ProdEvalSettings"),
        )
        // 标签分开：生产跑批不许冒用自检前缀（账上要分得清是谁起的这一轮、烧的谁的额度）
        assertEquals("prod-eval", RuntimeEval.PROD_EVAL_TAG)
        assertEquals("proposal-selfcheck", RuntimeEval.SELF_CHECK_TAG)
        assertTrue(codeOf("ProdEval.kt").contains("tag = RuntimeEval.PROD_EVAL_TAG"))
    }

    // ── 夹具 ─────────────────────────────────────────────────────────────

    private fun ProdEvalOutcome.reason(): String = when (this) {
        is ProdEvalOutcome.NotRun -> this.reason
        else -> describe(this)
    }
}

private const val SECRET = "sk-SECRETCANARY-1234"

private fun describe(o: ProdEvalOutcome): String = when (o) {
    is ProdEvalOutcome.NotRun -> "NotRun(${o.reason})"
    is ProdEvalOutcome.Recorded -> "Recorded(${o.passed}/${o.total}，产物 ${o.artifactPath})"
    ProdEvalOutcome.Disabled -> "Disabled"
}

private fun okProvider(key: String = "sk-test-not-a-secret"): Provider = Provider(
    id = "test-provider",
    label = "测试通道",
    protocol = LlmProtocol.OPENAI_COMPAT,
    baseUrl = "https://api.test.invalid",
    model = "test-model",
    apiKey = key,
)

private fun result(id: String): CaseResult = CaseResult(
    id = id,
    note = "",
    ok = true,
    steps = 2,
    failures = emptyList(),
    finalText = "终答",
    toolCalls = listOf("read_file"),
    tokensIn = 12_000,
    tokensOut = 120,
)

private fun casesJson(vararg specs: Pair<String, String>): String {
    val arr = JSONArray()
    for ((id, persona) in specs) {
        val o = JSONObject()
            .put("id", id)
            .put("prompt", "随便一句")
            .put("maxSteps", 3)
            .put("expect", JSONArray().put(JSONObject().put("kind", "steps_leq").put("n", 3)))
        if (persona.isNotEmpty()) {
            o.put("persona", persona)
                .put("personaText", "【全局人设 · B 层】自称「在下」。")
                .put("covers", "拒答")
        }
        arr.put(o)
    }
    return arr.toString()
}

private fun codeOf(name: String): String = codeOfAt(File("src/main/java/com/example/zhengdao/baopu/eval/$name"))

/**
 * 把注释剥掉再 grep。
 *
 * 为什么不整文件扫：本批的文件头把红线原文写在注释里（「不回滚、不改技能、不晋升」），
 * 拿它做黑名单会**永远命中**——那样这条门禁就变成装饰。判的是代码里有没有调用，不是有没有提到。
 */
private fun codeOfAt(f: File): String {
    val src = f.readText(Charsets.UTF_8)
    val noBlock = Regex("/\\*\\*?.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(src) { " " }
    return Regex("//[^\\r\\n]*").replace(noBlock, " ")
}

/**
 * 依赖全注入，是为了把「关掉时零调用」**量出来**而不是说出来。
 * 计数器记每个口子被碰了几次（[counts]），断言直接读它。
 */
private class Fakes(
    val gateOn: Boolean,
    val json: String? = null,
    val apiKey: String = "sk-test-not-a-secret",
    val provider: Provider? = null,
    val writeOk: Boolean = true,
) {
    val counts = LinkedHashMap<String, Int>()
    val logs = mutableListOf<String>()
    var written: String? = null
    var givenIds: List<String> = emptyList()
    var runThrows: Throwable? = null

    private fun bump(name: String) {
        counts[name] = (counts[name] ?: 0) + 1
    }

    fun eval(): ProdEval = ProdEval(
        gateOn = { bump("gateOn"); gateOn },
        casesJson = { bump("casesJson"); json },
        providerOf = { bump("providerOf"); provider ?: okProvider(apiKey) },
        systemPromptOf = { bump("systemPromptOf"); "有效人设" },
        artifactPath = { bump("artifactPath"); "/tmp/eval90/prod-result.json" },
        writeArtifact = { text -> bump("writeArtifact"); if (writeOk) { written = text; true } else false },
        runCases = { cases, _ ->
            bump("runCases")
            givenIds = cases.map { it.id }
            runThrows?.let { throw it }
            EvalRun(cases.map { result(it.id) }, 10L)
        },
        log = { logs += it },
    )
}
