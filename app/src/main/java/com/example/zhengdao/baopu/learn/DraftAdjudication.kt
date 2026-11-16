// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十二批 · 冲突步人裁（624 阶段二留下的那道口：`DraftStep.candidates` 早就有
// 「两套定位并存」，但**裁定入口没有**——于是含冲突的草案永远卡在「待人裁」）。
//
// 本文件是这件事的纯层，三件东西：
//   · [DraftChoice] —— 人裁的闭集三选：A／B／none（none＝两套都不对 ⇒ 回「重新教这一步」）；
//   · [DraftAdjudication] —— 一条裁定记录：`resolved`＋时间＋用户（判据②的三个字段，一格不缺；
//     `by` 空 ⇒ 整条不认：没有主体的裁定是假账）；
//   · [DraftAdjudications] —— 把裁定落到草案上的**唯一实现**（A ⇒ 候选[0] 上位，B ⇒ 候选[1]
//     上位，未裁／none ⇒ 拦住并说人话），以及落盘壳 [DraftAdjudicationStore]。
//
// 三条硬边界：
//   · **没有自动路径**：本层只接受外界递进来的 [DraftAdjudication]，自己不产生、不推测、
//     不按「命中多的那套」代选（判据⑤：源码断言见 DraftAdjudicateSourceGateTest）；
//   · **草案本体一个字不动**：裁定写在旁边（`裁决-<草案 id>.json`），草案仍留着两套候选——
//     这样「裁了又反悔」只要重写裁记录，不用回滚草案；
//   · **落盘坏了按「未裁」算**（不是按「已裁」）：读不动 ⇒ 空清单 ⇒ 转正门照拦。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 人裁的闭集三选（wire 是落盘字面量，判据②断言到这三个字上）。 */
internal enum class DraftChoice(val wire: String) {
    A("A"),
    B("B"),
    NONE("none");

    /** 卡面与提示里的人话（不猜：就照选项说）。 */
    fun label(): String = when (this) {
        A -> "A"
        B -> "B"
        NONE -> "都不对"
    }

    companion object {
        fun of(wire: String): DraftChoice? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一条裁定（判据②：resolved＋时间＋用户）。 */
internal data class DraftAdjudication(
    val stepIndex: Int,
    val resolved: DraftChoice,
    val atMs: Long,
    val by: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("i", stepIndex)
        .put("resolved", resolved.wire)
        .put("t", atMs)
        .put("by", by)

    companion object {
        /** 键闭集：多一列就算篡改（与 622 内嵌对象的严格键集同口径）。 */
        val KEYS = setOf("i", "resolved", "t", "by")

        fun fromJson(o: JSONObject): DraftAdjudication? = runCatching {
            val choice = DraftChoice.of(o.optString("resolved")) ?: return null
            val by = o.optString("by")
            if (by.isBlank()) return null // 没有主体 ⇒ 不是人裁，整条不认
            DraftAdjudication(
                stepIndex = o.optInt("i", 0),
                resolved = choice,
                atMs = o.optLong("t", 0L),
                by = by,
            )
        }.getOrNull()
    }
}

/** 裁定规则的唯一实现（转正门的判据本体；[SkillDraftRules.checkForPromotion] 调它）。 */
internal object DraftAdjudications {

    /** 人工裁定的缺省主体名（落盘 `by` 字段；空 ⇒ 拒收，见 [DraftAdjudication.fromJson]）。 */
    const val USER = "用户"

    /** 落盘 schema（只认这一版）。 */
    const val SCHEMA = "zl-adj/1"

    /** 一次「把裁定落到草案上」的结局（闭集两态）。 */
    sealed interface Applied {
        /** 全裁完：交出的草案每步只剩一套定位（candidates 已清空）。 */
        data class Ready(val draft: SkillDraft) : Applied

        /** 还有步没裁／裁了 none／候选坏形：一句句人话，照原样往上抛。 */
        data class Blocked(val problems: List<String>) : Applied
    }

    /** 裁定清单 → 步号索引（同一步后裁的胜；坏记录早已在读盘时丢掉）。 */
    fun decisions(adjudications: List<DraftAdjudication>): Map<Int, DraftChoice> =
        adjudications.associate { it.stepIndex to it.resolved }

    /** 该草案还有哪些步待人裁（卡面与「转正」钮的判据）。 */
    fun pending(draft: SkillDraft, adjudications: List<DraftAdjudication>): List<DraftStep> {
        val byStep = decisions(adjudications)
        return draft.skeleton.filter { it.candidates.isNotEmpty() && byStep[it.index] == null }
    }

    /**
     * 裁定落到草案上：A ⇒ `candidates[0]` 上位，B ⇒ `candidates[1]` 上位（选定即清空候选）。
     * 未裁／none／候选不足 ⇒ [Applied.Blocked]，问题一次列全（不在第一处就停）。
     */
    fun apply(draft: SkillDraft, adjudications: List<DraftAdjudication>): Applied {
        val byStep = decisions(adjudications)
        val problems = mutableListOf<String>()
        val steps = draft.skeleton.map { step ->
            if (step.candidates.isEmpty()) return@map step
            when (byStep[step.index]) {
                null -> {
                    problems += SkillDraftRules.unresolvedProblem(step.index)
                    step
                }
                DraftChoice.NONE -> {
                    problems += SkillDraftRules.reteachProblem(step.index)
                    step
                }
                DraftChoice.A -> step.withChosen(0, problems)
                DraftChoice.B -> step.withChosen(1, problems)
            }
        }
        return if (problems.isEmpty()) {
            Applied.Ready(draft.copy(skeleton = steps))
        } else {
            Applied.Blocked(problems.toList())
        }
    }

    /** 选定一套：主位换成它、候选清空；没有第 n 套 ⇒ 记问题并原样返回（不猜）。 */
    private fun DraftStep.withChosen(slot: Int, problems: MutableList<String>): DraftStep {
        val pick = candidates.getOrNull(slot)
        if (pick == null) {
            problems += "第 $index 步没有第 ${slot + 1} 套定位（候选只有 ${candidates.size} 条）——坏形，须重教"
            return this
        }
        return copy(locator = pick, candidates = emptyList())
    }
}

/**
 * 裁定落盘壳（`草案/裁决-<草案 id>.json`）。
 *
 * 为什么与草案同目录、不同前缀：草案区按 `草案-` 前缀扫（[SkillDraftStore]），
 * 裁决文件不会被当成草案读；「清空观察数据」排除整个草案目录 ⇒ 裁决跟着草案一起活。
 * 单草案一文件、整体覆盖写（同 [SkillDraftStore] 的写口规矩）。
 */
internal class DraftAdjudicationStore(private val dir: File) {

    /** 读一份（坏文件／schema 不对 ⇒ 空清单＝按未裁算，**不按已裁算**）。 */
    fun load(draftId: String): List<DraftAdjudication> = runCatching {
        val f = File(dir, fileNameFor(draftId))
        if (!f.isFile) return emptyList()
        parse(f.readText(Charsets.UTF_8))
    }.getOrDefault(emptyList())

    /** 全部（键＝草案 id；供 UI 一次读完，不用每次翻盘）。 */
    fun all(): Map<String, List<DraftAdjudication>> = runCatching {
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            .associate { f ->
                val id = f.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
                id to runCatching { parse(f.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
            }
            .filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())

    /** 记一条（同一步后裁的胜——用户改主意不新增一行）。写失败 false 由调用方说。 */
    fun put(draftId: String, adjudication: DraftAdjudication): Boolean {
        val next = load(draftId).filterNot { it.stepIndex == adjudication.stepIndex } + adjudication
        return save(draftId, next.sortedBy { it.stepIndex })
    }

    /** 弃掉一条草案时一起清（裁决跟着草案走）。 */
    fun clear(draftId: String) {
        File(dir, fileNameFor(draftId)).takeIf { it.isFile }?.let { f -> runCatching { f.delete() } }
    }

    private fun save(draftId: String, list: List<DraftAdjudication>): Boolean = runCatching {
        dir.mkdirs()
        File(dir, fileNameFor(draftId)).writeText(
            JSONObject()
                .put("schema", DraftAdjudications.SCHEMA)
                .put("draft", draftId)
                .put("steps", JSONArray(list.map { it.toJson() }))
                .toString(),
            Charsets.UTF_8,
        )
        true
    }.onFailure {
        RunLog.log("学习板块：裁定落盘失败 ${it.javaClass.simpleName}")
    }.getOrDefault(false)

    private fun parse(text: String): List<DraftAdjudication> = runCatching {
        val o = JSONObject(text)
        if (o.optString("schema") != DraftAdjudications.SCHEMA) return emptyList()
        o.optJSONArray("steps")?.let { a ->
            (0 until a.length()).mapNotNull { k ->
                runCatching { DraftAdjudication.fromJson(a.getJSONObject(k)) }.getOrNull()
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    companion object {
        const val FILE_PREFIX = "裁决-"
        const val FILE_SUFFIX = ".json"

        fun fileNameFor(draftId: String): String = FILE_PREFIX + draftId + FILE_SUFFIX
    }
}
