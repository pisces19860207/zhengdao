// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十三批 · 进化评估的场景数据壳（设计 §4／§9）。
//
// 与 168 的 `baopu/eval/EvalCase.kt` 的关系：语义照抄（final_contains / file_* /
// json_parses / steps_leq），但**不复用那个文件、也不改它**——168 是生产评测水位，
// 163 是候选双跑，两边的夹具生命周期不同。这里另加三种离线专用判据：
// danger_asked / danger_asked_or_rejected / tokens_leq（后一种是跨臂判据）。
package com.example.zhengdao.baopu.evolution.eval

import java.io.File
import org.json.JSONArray

/** 场景开始前写进临时工作区的种子文件（只写临时目录）。 */
internal data class EvolutionSeedFile(
    val path: String,
    val content: String,
)

/**
 * 一条机械判据。
 *
 * [path] 一律是**相对临时工作区**的路径；[n] 的含义按 kind 定（步数上限／确认次数下限／token 百分比）。
 */
internal data class EvolutionAssert(
    val kind: String,
    val path: String = "",
    val text: String = "",
    val n: Int = 0,
)

internal data class EvolutionCase(
    val id: String,
    val note: String,
    val prompt: String,
    val maxSteps: Int,
    val files: List<EvolutionSeedFile>,
    val asserts: List<EvolutionAssert>,
)

internal object EvolutionCases {

    /** 设计硬线：实际跑的场景 < 10 ⇒ DEFER。 */
    const val MIN_CASES = 10

    /**
     * 允许的判据种类。
     *
     * 前六种与 168 的机械判据同义；后三种是离线双跑新增：
     * - `danger_asked`：危险工具**被提交并弹确认**的次数下限（不许跳过确认直接宣布做完）
     * - `danger_asked_or_rejected`：越界/危险调用「问过或被拒」的次数下限
     * - `tokens_leq`：**跨臂**判据，候选臂该例 token 不得超过基线臂的 (1 + n%) 倍
     */
    val KINDS: Set<String> = linkedSetOf(
        "final_contains",
        "file_exists",
        "file_contains",
        "file_not_exists",
        "json_parses",
        "steps_leq",
        "unchanged",
        "tool_not_called",
        "danger_asked",
        "danger_asked_or_rejected",
        "tokens_leq",
    )

    /** 跨臂判据（单臂执行时不判，交由 [EvolutionCompare] 两臂一起判）。 */
    val CROSS_ARM_KINDS: Set<String> = setOf("tokens_leq")

    /** 解析一份场景数组；坏场景**直接抛**（宁可起不来，也不许静默少跑几条）。 */
    fun parse(json: String): List<EvolutionCase> {
        val root = JSONArray(json)
        val out = ArrayList<EvolutionCase>(root.length())
        val seen = HashSet<String>()
        for (i in 0 until root.length()) {
            val o = root.getJSONObject(i)
            val id = o.getString("id")
            require(id.isNotBlank()) { "场景 id 不能为空（第 $i 条）" }
            require(seen.add(id)) { "场景 id 重复：$id" }
            val prompt = o.getString("prompt")
            require(prompt.isNotBlank()) { "场景 $id 的 prompt 是空的" }
            val maxSteps = o.optInt("maxSteps", 8)
            require(maxSteps in 1..20) { "场景 $id 的 maxSteps 越界：$maxSteps（1..20）" }

            val filesJson = o.optJSONArray("files") ?: JSONArray()
            val files = ArrayList<EvolutionSeedFile>(filesJson.length())
            for (j in 0 until filesJson.length()) {
                val f = filesJson.getJSONObject(j)
                val path = f.getString("path")
                requireLegalRelPath(id, "种子文件", path)
                files.add(EvolutionSeedFile(path, f.getString("content")))
            }

            val assertsJson = o.optJSONArray("expect")
            require(assertsJson != null && assertsJson.length() > 0) {
                "场景 $id 没有任何判据：那它验不了任何东西"
            }
            val asserts = ArrayList<EvolutionAssert>(assertsJson.length())
            for (j in 0 until assertsJson.length()) {
                val a = assertsJson.getJSONObject(j)
                val kind = a.getString("kind")
                require(kind in KINDS) { "场景 $id 的判据种类未知：$kind（允许：${KINDS.joinToString("、")}）" }
                val path = a.optString("path", "")
                if (path.isNotEmpty()) requireLegalRelPath(id, "判据路径", path)
                val n = a.optInt("n", 0)
                require(n >= 0) { "场景 $id 的判据 n 不能是负数：$n" }
                asserts.add(EvolutionAssert(kind, path, a.optString("text", ""), n))
            }
            out.add(
                EvolutionCase(
                    id = id,
                    note = o.optString("note", ""),
                    prompt = prompt,
                    maxSteps = maxSteps,
                    files = files,
                    asserts = asserts,
                ),
            )
        }
        require(out.size >= MIN_CASES) {
            "场景只有 ${out.size} 条，低于设计硬线 $MIN_CASES 条——样本不足不许出判定"
        }
        return out
    }

    fun load(file: File): List<EvolutionCase> = parse(file.readText(Charsets.UTF_8))

    /** 场景文件指纹（两臂配置指纹的一部分）。 */
    fun sha256(file: File): String = EvolutionHash.sha256(file.readBytes())

    private fun requireLegalRelPath(caseId: String, what: String, path: String) {
        require(path.isNotBlank()) { "场景 $caseId 的${what}路径是空的" }
        require(!path.startsWith("/") && !path.startsWith("\\")) {
            "场景 $caseId 的${what}路径不许是绝对路径：$path"
        }
        require(path.split('/', '\\').none { it == ".." }) {
            "场景 $caseId 的${what}路径不许含 ..：$path"
        }
    }
}
