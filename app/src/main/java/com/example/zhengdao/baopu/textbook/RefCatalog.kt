// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 798 段 A · 引用闭集。content 必须引到具体字段/判据 id，否则就是读书笔记。
// 字段集锚在 786 评测集 README §三的 15 个字段 + 766 世界模型 4 字段 + 本 schema 10 字段；
// 判据 id 锚在 786 的 NAR-<类码>-<序号>。闭集写死：扩集要另立单，不许"看起来像"就放行。
package com.example.zhengdao.baopu.textbook

internal object RefCatalog {
    /** 可被引用的字段名闭集。 */
    val FIELDS: Set<String> = setOf(
        // 786 叙事连续性评测集（docs/evals/narrative/README.md §三）
        "id", "category", "verdict", "subtype", "difficulty", "expertise", "corpus",
        "fragment", "probe", "question", "answer", "evidence", "accept",
        "absence_scope", "note",
        // 766 世界模型四字段（786 README §十明确不动）
        "world_fact", "observer_belief", "validTime", "transactionTime", "status",
        // 本 schema 十字段（教材之间可互相引用）
        "sourceType", "source", "asOf", "domain", "content", "strength", "scope",
        "revocable", "supersedes",
    )

    /** 786 判据 id：NAR- 真实类码 - 两位序号。类码取 items.jsonl 100 条的实测闭集。 */
    val CRITERION: Regex = Regex("^NAR-(CH|CL|DT|FS|IB|OC|PH|SC|SP|ST|TL)-[0-9]{2}$")

    /** 引用语法： [字段:xxx] 或 [判据:xxx]，方括号内不许再嵌方括号。 */
    val REF: Regex = Regex("""\[(字段|判据):([^\[\]]+)\]""")

    data class Ref(val kind: String, val id: String)

    fun refs(content: String): List<Ref> = REF.findAll(content).map { m ->
        Ref(m.groupValues[1], m.groupValues[2].trim())
    }.toList()

    fun hasRef(content: String): Boolean = refs(content).isNotEmpty()

    /** 返回不在闭集里的引用（人读形式），空表＝全部可回查。 */
    fun invalidRefs(content: String): List<String> = refs(content).mapNotNull { ref ->
        val valid = when (ref.kind) {
            "字段" -> ref.id in FIELDS
            "判据" -> CRITERION.matches(ref.id)
            else -> false
        }
        if (valid) null else "${ref.kind}:${ref.id}"
    }
}
