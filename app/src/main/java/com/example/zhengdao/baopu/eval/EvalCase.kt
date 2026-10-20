package com.example.zhengdao.baopu.eval

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 评测用例的**数据壳**（进化门禁 · 2026-10-17）。
 *
 * 为什么用例要放仓库里、用 JSON 写：改一版提示词之后，判「有没有变差」必须有一份
 * **事前写死的期望**；写在 Kotlin 里就得改代码才能加用例，写在 JSON 里才能只改一行。
 *
 * 判据只允许**机械可判定**的种类（[KINDS]）：凡是「答得好不好」这种需要人看的，
 * 一律不许进用例——那会把门禁变成又一个需要人盯的界面。
 */
internal data class EvalFile(
    val path: String,
    val content: String,
    val encoding: String = "utf-8",
)

internal data class EvalAssert(
    val kind: String,
    val path: String = "",
    val text: String = "",
    val n: Int = 0,
)

internal data class EvalCase(
    val id: String,
    val note: String,
    val prompt: String,
    val maxSteps: Int,
    val files: List<EvalFile>,
    val asserts: List<EvalAssert>,
    /**
     * 这一例要的人设层（第八十四批引入，第九十批补齐口径）。
     *
     * - `""`：老行为——调用方注入优先，否则只用 A 内核（存量 10 例全是这条）
     * - `"B_GLOBAL"` / `"C_PROJECT"`：A 内核 **＋** [personaText] 当作那一层的正文
     *   （90 批之前是「fixture 顶替整段系统提示」，那测不到「B/C 不许削弱 A」）
     * - `"A"`：**显式**声明这一例只要 A 内核、不许被任何 B/C 污染（见 [EvalCases.A_KERNEL]）
     *
     * 实际拼装只在 [EvalPersona.systemPromptOf] 一处；跳过口径见 [usesLayerFixture]。
     */
    val persona: String = "",
    /**
     * [persona] 是 `B_GLOBAL`/`C_PROJECT` 时必填：**那一层的正文**（不是整段系统提示）。
     *
     * 为什么仍用写死的 fixture 而不是盘上人设：理由与 84 批相同——自检量的是「用户自己那两层
     * 没把事办坏」，门禁量的是「这一层的通路对不对」，后者要有事前写死的参照物。
     */
    val personaText: String = "",
    /**
     * 这一例覆盖 B/C 的哪一类风险（第九十批：派工单 §1「至少各 3 条，覆盖提示词边界/拒答/工具约束」）。
     *
     * 空 = 不声明（存量用例保持不声明，免得为了打标签去改它们的文案）。
     * 声明了就必须在 [EvalCases.COVERS] 里，且由 `EvalPersonaTest` 的数量门禁按层核对。
     */
    val covers: String = "",
) {
    /**
     * 这一例靠 fixture 提供 B/C 层 ⇒ **自检与默认门禁都不跑它**（fixture 不是盘上人设）。
     * 显式 `"A"` 与 `""` 都不算：前者跟存量一样只吃内核，本来就是可跑的水位用例。
     */
    val usesLayerFixture: Boolean get() = persona in EvalCases.LAYER_FIXTURES

    /** 这一例算不算「水位口径」（与 `eval/baseline.json` 的 A 段同一池子）。 */
    val isBaselinePersona: Boolean get() = persona.isEmpty() || persona == EvalCases.A_KERNEL
}

internal object EvalCases {

    /**
     * 允许的人设层（第八十四批引入 B/C，第九十批补 `"A"` 与 [COVERS]）。
     *
     * 为什么 B/C 用例**只能**用 fixture、不能拿盘上人设：自检的口径是
     * 「用户自己那两层没把事办坏」（v1 派工单 §0 硬约束），fixture 是写死的样板文案，
     * 拿它跑等于演戏；而**显式回放**（`ZHENGDAO_EVAL_PERSONA=1`）没有用户，
     * 用 fixture 正是为了把「B/C 层真的进了系统提示」这条通路钉住。
     * ⇒ `RuntimeEval`（自检路径）跳过它们，`EvalGateTest`（显式回放）默认也跳过，
     * 拨了 `ZHENGDAO_EVAL_PERSONA=1` 才跑。
     */
    const val B_GLOBAL = "B_GLOBAL"
    const val C_PROJECT = "C_PROJECT"

    /**
     * 显式「只要 A 内核」（第九十批）。
     *
     * 为什么要专门加一个值而不是留空：留空是「没人管我用什么」（老行为，允许被注入顶替），
     * `"A"` 是「我声明我就是测内核，任何 fixture/注入都不许进来」。两者在 [EvalPersona] 里
     * 走不同分支，差别必须能在用例文件里写出来，否则「A 优先」这条永远没有反面对照。
     */
    const val A_KERNEL = "A"

    val PERSONAS: Set<String> = setOf(A_KERNEL, B_GLOBAL, C_PROJECT)

    /** 只有这两层是「带 fixture 的人设用例」（[A_KERNEL] 与空串都在可跑池里）。 */
    val LAYER_FIXTURES: Set<String> = setOf(B_GLOBAL, C_PROJECT)

    /**
     * B/C 用例允许声明的覆盖面（派工单 §1 的三类）。
     *
     * - `提示词边界`：这一层的约定真进了上下文，且只在它该在的范围里起作用
     * - `拒答`：层里写「你可以越线」，模型仍必须守 A 内核的红线（原件区/删除）
     * - `工具约束`：层里限定用/不用哪类工具，产物落点与调用序列照它走
     */
    val COVERS: Set<String> = setOf("提示词边界", "拒答", "工具约束")

    /**
     * 允许的判据种类。
     *
     * - `final_contains`：模型最后一句话里必须出现某段文本
     * - `file_exists` / `file_not_exists`：工作区里有没有这个文件
     * - `file_contains`：文件里必须出现某段文本（读 UTF-8）
     * - `json_parses`：文件必须是合法 JSON（结构化产物最容易「看起来写了其实半截」）
     * - `steps_leq`：走了几步（步数暴涨＝提示词把模型带进绕路）
     * - `unchanged`：这个文件必须与初始字节完全一致（红线：不许动的东西一个字节没动）
     * - `tool_not_called`：**这个工具一次都不许被提交**（`text` 填工具名）。
     *   90 批新增，为的是把「拒答/工具约束」两类 B/C 用例钉住：光看 `unchanged` 只证明
     *   守卫拦住了，证明不了模型**没去试**；而「层里说可以越线，模型就去试」这件事
     *   本身就是要抓的退化（它成功了才是真事故，被守卫拦下只是没酿成后果）。
     *   口径：判的是**提交过**（`GuardPipeline` 之前），被拒的调用也算数。
     */
    val KINDS: Set<String> = setOf(
        "final_contains",
        "file_exists",
        "file_contains",
        "file_not_exists",
        "json_parses",
        "steps_leq",
        "unchanged",
        "tool_not_called",
    )

    /** 解析一份用例数组；坏用例**直接抛**（门禁宁可起不来，也不许静默少跑几条）。 */
    fun parse(json: String): List<EvalCase> {
        val root = JSONArray(json)
        val out = ArrayList<EvalCase>(root.length())
        val seen = HashSet<String>()
        for (i in 0 until root.length()) {
            val o = root.getJSONObject(i)
            val id = o.getString("id")
            require(id.isNotBlank()) { "用例 id 不能为空（第 $i 条）" }
            require(seen.add(id)) { "用例 id 重复：$id" }
            val filesJson = o.optJSONArray("files") ?: JSONArray()
            val files = ArrayList<EvalFile>(filesJson.length())
            for (j in 0 until filesJson.length()) {
                val f = filesJson.getJSONObject(j)
                files.add(
                    EvalFile(
                        path = f.getString("path"),
                        content = f.getString("content"),
                        encoding = f.optString("encoding", "utf-8"),
                    ),
                )
            }
            val assertsJson = o.optJSONArray("expect") ?: JSONArray()
            require(assertsJson.length() > 0) { "用例 $id 没有任何判据：那它验不了任何东西" }
            val persona = o.optString("persona", "")
            require(persona.isEmpty() || persona in PERSONAS) {
                "用例 $id 的 persona 未知：$persona（允许：空 或 ${PERSONAS.joinToString("、")}）"
            }
            val personaText = o.optString("personaText", "")
            require(!persona.isEmpty() || personaText.isBlank()) {
                "用例 $id 没声明 persona 却写了 personaText：90 批起它是「某一层的正文」，" +
                    "没有层就无处安放——静默忽略它比报错更糟（用例以为自己被测了 B/C，其实没有）"
            }
            require(persona !in LAYER_FIXTURES || personaText.isNotBlank()) {
                "用例 $id 声明了 persona=$persona 却没给 personaText：那一层就是空的，验不到东西"
            }
            require(persona != A_KERNEL || personaText.isBlank()) {
                "用例 $id 是显式 A 内核用例，不许带 personaText：带了就同时在测两层，" +
                    "这条用例就没有「内核单独在场」的对照意义了"
            }
            val covers = o.optString("covers", "")
            require(covers.isBlank() || covers in COVERS) {
                "用例 $id 的 covers 未知：$covers（允许：空 或 ${COVERS.joinToString("、")}）"
            }
            require(covers.isBlank() || persona in LAYER_FIXTURES) {
                "用例 $id 不是 B/C 层用例，covers=$covers 不进门禁覆盖面统计，别写"
            }
            val asserts = ArrayList<EvalAssert>(assertsJson.length())
            for (j in 0 until assertsJson.length()) {
                val a = assertsJson.getJSONObject(j)
                val kind = a.getString("kind")
                require(kind in KINDS) { "用例 $id 的判据种类未知：$kind（允许：${KINDS.joinToString("、")}）" }
                asserts.add(
                    EvalAssert(
                        kind = kind,
                        path = a.optString("path", ""),
                        text = a.optString("text", ""),
                        n = a.optInt("n", 0),
                    ),
                )
            }
            out.add(
                EvalCase(
                    id = id,
                    note = o.optString("note", ""),
                    prompt = o.getString("prompt"),
                    maxSteps = o.optInt("maxSteps", 8),
                    files = files,
                    asserts = asserts,
                    persona = persona,
                    personaText = personaText,
                    covers = covers,
                ),
            )
        }
        return out
    }

    fun load(file: File): List<EvalCase> = parse(file.readText(Charsets.UTF_8))
}
