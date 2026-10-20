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
     * 这一例要的人设层（第八十四批 §0.2：原来只用 A 内核，B/C 没人覆盖）。
     *
     * - `""`：只用 A（老行为，全部存量 10 例）
     * - `"B_GLOBAL"` / `"C_PROJECT"`：这一例的系统提示**只用 fixture**（[personaText]），
     *   不用盘上人设——理由见 [EvalCases.PERSONAS] 与 `RuntimeEval` 的跳过口径。
     */
    val persona: String = "",
    /** [persona] 非空时必填：整段系统提示（A 内核之后直接拼这一段，方便 fixture 自包含）。 */
    val personaText: String = "",
)

internal object EvalCases {

    /**
     * 允许的人设层（第八十四批）。
     *
     * 为什么带 persona 的用例**只能**用 fixture、不能拿盘上人设：自检的口径是
     * 「用户自己那两层没把事办坏」（v1 派工单 §0 硬约束），fixture 是写死的样板文案，
     * 拿它跑等于演戏；而**显式回放**（`ZHENGDAO_EVAL_PERSONA=1`）没有用户，
     * 用 fixture 正是为了把「B/C 层真的进了系统提示」这条通路钉住。
     * ⇒ `RuntimeEval`（自检路径）跳过它们，`EvalGateTest`（显式回放）默认也跳过，
     * 拨了 `ZHENGDAO_EVAL_PERSONA=1` 才跑。
     */
    val PERSONAS: Set<String> = setOf("B_GLOBAL", "C_PROJECT")

    /**
     * 允许的判据种类。
     *
     * - `final_contains`：模型最后一句话里必须出现某段文本
     * - `file_exists` / `file_not_exists`：工作区里有没有这个文件
     * - `file_contains`：文件里必须出现某段文本（读 UTF-8）
     * - `json_parses`：文件必须是合法 JSON（结构化产物最容易「看起来写了其实半截」）
     * - `steps_leq`：走了几步（步数暴涨＝提示词把模型带进绕路）
     * - `unchanged`：这个文件必须与初始字节完全一致（红线：不许动的东西一个字节没动）
     */
    val KINDS: Set<String> = setOf(
        "final_contains",
        "file_exists",
        "file_contains",
        "file_not_exists",
        "json_parses",
        "steps_leq",
        "unchanged",
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
            require(persona.isEmpty() || personaText.isNotBlank()) {
                "用例 $id 声明了 persona=$persona 却没给 personaText：那一层就是空的，验不到东西"
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
                ),
            )
        }
        return out
    }

    fun load(file: File): List<EvalCase> = parse(file.readText(Charsets.UTF_8))
}
