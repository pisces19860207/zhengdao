package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.BaopuPreset
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.DataFence
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.InMemoryToolPrefs
import com.example.zhengdao.baopu.agent.SpillResult
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolCallCodec
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 进化门禁的回放执行器（2026-10-17）。
 *
 * 一循环：拼提示词 → 真调模型 → 解 tool_calls → 走**真实护栏**执行 → 断言产物。
 *
 * 三个刻意的取舍：
 * 1. **不跑 RunEngine**：它绑 `BaopuDb`（Android SQLite），JVM 里只会得到「表都没建」的假红。
 *    `ToolRegistry` / `GuardPipeline` 零 Android 依赖，判据与线上同源，重搭这两个接口层足够。
 * 2. **不走 LlmClient**：它是流式 SSE，而评测一次一回合、只看结果。这里用**非流式**直连，
 *    顺带拿到 `usage`（token 记账）与 `thinking` 开关。
 * 3. **关思考**（`thinking:{"type":"disabled"}`）：第一次真跑就撞上
 *    `HTTP 400 The reasoning_content in the thinking mode must be passed back to the API`
 *    ——思考模式下多轮回传有硬约束（已记进刀 3·B 段）。评测要量的是**提示词与工具**，
 *    不是这条传输约束，所以先关掉；等 B 段把 reasoning 回传做通，再打开对比一版。
 */
internal data class EvalConfig(
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val apiKey: String,
    val disableThinking: Boolean = true,
    val timeoutSeconds: Long = 120,
    val maxOutputTokens: Int = 2_048,
    /**
     * 系统提示由**调用方注入**（第五十批新增）。
     *
     * - `null` ⇒ 退回老行为「只用 A 内核」——只有构建期门禁（`EvalGateTest`）该这么用：
     *   它量的是「提示词/工具改动有没有让本事退化」，只需要一个稳定基线；
     * - 运行时自检**必须**传「A 内核 ＋ 落盘后的 B/C」的拼装结果。拿 `BaopuPreset.personaText`
     *   单独跑，测的是改之前的人设，自检就是演戏。
     */
    val systemPrompt: String? = null,
)

internal data class CaseResult(
    val id: String,
    val note: String,
    val ok: Boolean,
    val steps: Int,
    val failures: List<String>,
    val finalText: String,
    val toolCalls: List<String>,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val error: String? = null,
    val dir: File? = null,
)

internal object EvalHarness {

    /** 评测专用的会话号（第五十九批：解锁按会话分格，评测用它自己那一格，不碰任何真会话）。 */
    private const val EVAL_SESSION_ID = 1L

    fun runCase(case: EvalCase, cfg: EvalConfig): CaseResult {
        val dir = kotlin.io.path.createTempDirectory("zd-eval-${case.id}-").toFile()
        return try {
            val before = seed(case, dir)
            val reg = registryOf(dir)
            val pipeline = GuardPipeline(reg, { dir }, { null })
            // 传会话号：数组由那条唯一的可见性链算出（registryOf 已把各组在这一格解锁）
            val tools = reg.toolsJsonOpenAi(EVAL_SESSION_ID)

            val messages = JSONArray()
            // 注入优先：运行时自检传的是「落盘后的有效人设」，构建期门禁不传 ⇒ A 内核（老行为）。
            // 第八十四批：带 persona 的用例用**写死的 fixture**（显式回放才有用户之外的参照物），
            // 自检路径压根不跑这类用例（见 RuntimeEval），所以 fixture 与盘上人设不会碰面。
            val system = when {
                case.personaText.isNotBlank() -> case.personaText
                else -> cfg.systemPrompt ?: BaopuPreset.personaText(dir.absolutePath)
            }
            messages.put(JSONObject().put("role", "system").put("content", system))
            messages.put(JSONObject().put("role", "user").put("content", case.prompt))

            val calls = ArrayList<String>()
            val failures = ArrayList<String>()
            var finalText = ""
            var step = 0
            var tokensIn = 0
            var tokensOut = 0

            while (step < case.maxSteps) {
                step++
                val round = call(cfg, messages, tools)
                tokensIn += round.tokensIn
                tokensOut += round.tokensOut
                round.error?.let { return finish(case, dir, step, calls, listOf(it), "", tokensIn, tokensOut, it) }
                if (round.calls.isEmpty()) {
                    finalText = round.text
                    break
                }
                messages.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", round.text)
                        .put("tool_calls", round.rawCalls),
                )
                for (call in round.calls) {
                    calls.add(call.name)
                    val r = pipeline.execute(call) { fileName, text -> spill(dir, fileName, text) }
                    val feed = when (r) {
                        is GuardPipeline.Result.Done -> r.wrapped
                        is GuardPipeline.Result.Failed ->
                            DataFence.wrap(call.name, System.currentTimeMillis(), "失败：${r.error}")
                        is GuardPipeline.Result.Rejected ->
                            DataFence.wrap(call.name, System.currentTimeMillis(), "被拒：${r.reason}")
                        is GuardPipeline.Result.NeedsConfirm ->
                            DataFence.wrap(call.name, System.currentTimeMillis(), "等待确认（评测里不该出现）")
                    }
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", call.id)
                            .put("content", feed),
                    )
                }
            }
            if (finalText.isBlank()) failures.add("${case.maxSteps} 步内没有给出终答（最后一轮仍在调工具）")
            for (a in case.asserts) check(a, dir, before, finalText, step, failures)
            finish(case, dir, step, calls, failures, finalText, tokensIn, tokensOut, null)
        } catch (t: Throwable) {
            CaseResult(
                id = case.id,
                note = case.note,
                ok = false,
                steps = 0,
                failures = listOf("跑崩：${t.javaClass.simpleName}: ${t.message}"),
                finalText = "",
                toolCalls = emptyList(),
                error = t.message,
                dir = dir,
            )
        }
    }

    private fun finish(
        case: EvalCase,
        dir: File,
        steps: Int,
        calls: List<String>,
        failures: List<String>,
        finalText: String,
        tokensIn: Int,
        tokensOut: Int,
        error: String?,
    ) = CaseResult(
        id = case.id,
        note = case.note,
        ok = failures.isEmpty() && error == null,
        steps = steps,
        failures = failures,
        finalText = finalText,
        toolCalls = calls,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        error = error,
        dir = dir,
    )

    // ── 一回合：非流式直连 ────────────────────────────────────────────────

    private class Round(
        val text: String,
        val calls: List<ToolCall>,
        val rawCalls: JSONArray,
        val tokensIn: Int,
        val tokensOut: Int,
        val error: String?,
    )

    private fun call(cfg: EvalConfig, messages: JSONArray, tools: JSONArray): Round {
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", messages)
            .put("tools", tools)
            .put("stream", false)
            .put("max_tokens", cfg.maxOutputTokens)
        if (cfg.disableThinking) body.put("thinking", JSONObject().put("type", "disabled"))

        val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(cfg.timeoutSeconds, TimeUnit.SECONDS)
            .build()
        val req = Request.Builder()
            .url(cfg.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val raw: String
        try {
            http.newCall(req).execute().use { resp ->
                raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        JSONObject(raw).getJSONObject("error").optString("message")
                    }.getOrDefault(raw.take(160))
                    return Round("", emptyList(), JSONArray(), 0, 0, "HTTP ${resp.code}｜$msg")
                }
            }
        } catch (e: Exception) {
            return Round("", emptyList(), JSONArray(), 0, 0, "网络失败：${e.javaClass.simpleName}: ${e.message}")
        }

        val root = JSONObject(raw)
        val usage = root.optJSONObject("usage")
        val tokensIn = usage?.optInt("prompt_tokens", 0) ?: 0
        val tokensOut = usage?.optInt("completion_tokens", 0) ?: 0
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: return Round("", emptyList(), JSONArray(), tokensIn, tokensOut, "响应没有 choices")
        val msg = choice.optJSONObject("message") ?: JSONObject()
        val content = msg.optString("content", "")
        val rawCalls = msg.optJSONArray("tool_calls") ?: JSONArray()
        if (rawCalls.length() == 0) return Round(content, emptyList(), rawCalls, tokensIn, tokensOut, null)

        val parsed = ArrayList<ToolCall>(rawCalls.length())
        for (i in 0 until rawCalls.length()) {
            val c = rawCalls.getJSONObject(i)
            val fn = c.optJSONObject("function") ?: continue
            val name = fn.optString("name")
            val args = ToolCallCodec.parseArgs(fn.optString("arguments", "{}"))
                ?: return Round(content, emptyList(), rawCalls, tokensIn, tokensOut, "工具参数不是合法 JSON：$name")
            parsed.add(ToolCall(id = c.optString("id", "call_$i"), name = name, arguments = args))
        }
        return Round(content, parsed, rawCalls, tokensIn, tokensOut, null)
    }

    // ── 工作区：撒种、注册表、spill ───────────────────────────────────────

    /** 撒种，返回「初始字节」快照：`unchanged` 判据要跟它比，不能跟文件自己比。 */
    private fun seed(case: EvalCase, root: File): Map<String, ByteArray> {
        val before = HashMap<String, ByteArray>()
        for (f in case.files) {
            val target = File(root, f.path)
            target.parentFile?.mkdirs()
            val bytes = when (f.encoding.lowercase()) {
                "gbk", "gb18030" -> f.content.toByteArray(charset("GBK"))
                else -> f.content.toByteArray(Charsets.UTF_8)
            }
            target.writeBytes(bytes)
            before[f.path] = bytes
        }
        return before
    }

    /**
     * 注册表：工具表原样加载，然后把**写类工具降级成免确认**。
     *
     * 门禁验的是「模型能不能把活干对」；确认卡属于线上交互（刀 3·A 段），
     * 在这里出现只会让每条用例都停在同一处。路径守卫、Schema 校验、围栏、spill 全部保留。
     */
    private fun registryOf(root: File): ToolRegistry {
        val reg = ToolRegistry(InMemoryToolPrefs())
        BaopuToolCatalog.loadInto(reg) { root }
        // 第五十九批（渐进披露）：评测集量的是「会不会干活」，不是「要不要先解锁」。
        // 所以这里给评测一个专用会话号，把每一组都解锁 ⇒ 判据仍是线上同一条
        // [com.example.zhengdao.baopu.agent.visibleTools]（没有为评测开后门），只是入参取
        // 「全开」，语义等于 59 批之前的「启用的全部发给模型」。
        // ⚠️ 必须在下面那个 CONFIRM→FREE 重注册**之前**做：那个循环取的是 `reg.find(name)`，
        //    未解锁时它拿到的是「该工具组未解锁」的拒绝桩，再包装一次就把真能力换成了假能力
        //    ——那样评测集体挂的是自己的脚手架，不是模型。
        reg.currentSessionId = EVAL_SESSION_ID
        reg.unlockStore().unlock(EVAL_SESSION_ID, ToolGroup.values().toList())
        for (name in reg.names().toList()) {
            val spec = reg.find(name) ?: continue
            if (spec.permission != ToolPermission.CONFIRM) continue
            reg.register(
                ToolSpec(
                    name = spec.name,
                    description = spec.description,
                    inputSchema = spec.inputSchema,
                    permission = ToolPermission.FREE,
                ) { args -> spec.executor(args) },
            )
        }
        return reg
    }

    private fun spill(root: File, fileName: String, text: String): SpillResult? = runCatching {
        val d = File(root, ".baopu-spill").apply { mkdirs() }
        val f = File(d, fileName)
        f.writeText(text, Charsets.UTF_8)
        SpillResult(f, text.length)
    }.getOrNull()

    // ── 判据 ─────────────────────────────────────────────────────────────

    private fun check(
        a: EvalAssert,
        root: File,
        before: Map<String, ByteArray>,
        finalText: String,
        steps: Int,
        out: MutableList<String>,
    ) {
        when (a.kind) {
            "final_contains" -> if (!finalText.contains(a.text)) {
                out.add("终答里没有「${a.text}」（实际：${finalText.take(120)}）")
            }

            "file_exists" -> if (!File(root, a.path).isFile) out.add("缺产物文件：${a.path}")
            "file_not_exists" -> if (File(root, a.path).exists()) out.add("不该存在的文件还在：${a.path}")

            "file_contains" -> {
                val f = File(root, a.path)
                if (!f.isFile) out.add("缺产物文件：${a.path}")
                else if (!f.readText(Charsets.UTF_8).contains(a.text)) out.add("${a.path} 里没有「${a.text}」")
            }

            "json_parses" -> {
                val f = File(root, a.path)
                if (!f.isFile) out.add("缺产物文件：${a.path}")
                else {
                    val body = f.readText(Charsets.UTF_8)
                    val parsed = runCatching { JSONArray(body) }.isSuccess ||
                        runCatching { JSONObject(body) }.isSuccess
                    if (!parsed) out.add("${a.path} 不是合法 JSON")
                }
            }

            "steps_leq" -> if (steps > a.n) out.add("步数 $steps 超过上限 ${a.n}")

            "unchanged" -> {
                val seed = before[a.path]
                val f = File(root, a.path)
                when {
                    seed == null -> out.add("用例里没有撒 ${a.path} 的种子，判不了 unchanged")
                    !f.isFile -> out.add("红线文件被删了：${a.path}")
                    !f.readBytes().contentEquals(seed) -> out.add("红线文件被改了：${a.path}")
                }
            }

            else -> out.add("未知判据：${a.kind}")
        }
    }
}
