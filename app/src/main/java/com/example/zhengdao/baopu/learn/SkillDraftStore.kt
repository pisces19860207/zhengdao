// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 B/C（阶段二）· 技能草稿区（`filesDir/抱朴记忆/观察/草案/`）。
//
// 规矩（派工单/231 逐句）：
//   · 归纳产物**进草稿区，不转正**——本目录不是 `技能/`，落成技能只有一条路：
//     人批（ObserveCenter.confirmDraft → DraftConverter → TeachReplayStore.saveConfirmed
//     凭证/不可逆/入库三闸照走）；
//   · **同意图两次观察 ⇒ 归并成一条**（判据③）：新草案的 goal 与草稿区某条待定草案
//     归一化后相等 ⇒ SessionMerger.merge，N 累加（判据③「合并成一条并标 N」的接线点）；
//     定位冲突在归并里两套并存（判据⑤），不擅自选；
//   · 入区必过 [SkillDraftRules.validate]（判据①②的运行时落点：定位全空／无证据拒收）；
//   · 草案**没有 24h 自清**（它们是人待办，不是观察数据——与阶段一口径①的界线在报告里写明）；
//     封顶 20 条，满了拒收新的并如实说。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

internal class SkillDraftStore(private val dir: File) {

    /** 入区结果（闭集三态）。 */
    sealed interface AddOutcome {
        /** 新草案入区（首条观察）。 */
        data class Created(val draft: SkillDraft) : AddOutcome

        /** 与既有待定草案同意图 ⇒ 归并成一条并标 N（判据③的接线点）。 */
        data class Merged(val draft: SkillDraft, val withN: Int) : AddOutcome

        /** 结构校验拒收（判据①②：定位全空／无证据／goal 空等）。 */
        data class Rejected(val problems: List<String>) : AddOutcome

        /** 草稿区满（封顶拒收，不静默挤掉旧的）。 */
        data class Full(val cap: Int) : AddOutcome
    }

    fun add(draft: SkillDraft, newId: String): AddOutcome = synchronized(LOCK) {
        val check = SkillDraftRules.validate(draft)
        if (check is DraftCheck.Invalid) return AddOutcome.Rejected(check.problems)
        val pending = pendingList()
        if (pending.size >= CAP) return AddOutcome.Full(CAP)
        // 同意图归并：goal 归一化相等 ⇒ 合成一条（判据③；N 累加）
        val sameGoal = pending.firstOrNull { normalize(it.goal) == normalize(draft.goal) }
        if (sameGoal != null) {
            val merged = when (val m = SessionMerger.merge(sameGoal, draft, newId)) {
                is MergeOutcome.Merged -> m.draft
                is MergeOutcome.NotSameIntent -> return AddOutcome.Created(draft) // 归一化前不同（理论到不了）：按新草案入区
            }
            if (SkillDraftRules.validate(merged) is DraftCheck.Invalid) {
                // 归并产物若坏形（理论到不了：两条合法草案的归并仍合法），如实拒，不动旧条
                return AddOutcome.Rejected(listOf("归并产物未过结构校验"))
            }
            removeLocked(sameGoal.id)
            writeLocked(merged)
            return AddOutcome.Merged(merged, merged.mergedFrom)
        }
        writeLocked(draft)
        AddOutcome.Created(draft)
    }

    /** 待定草案（按入区顺序）。 */
    fun pending(): List<SkillDraft> = synchronized(LOCK) { pendingList() }

    /**
     * 698 判据③：这条草案是不是「**同一段观察**、同一个意图」又被归纳了一遍？
     *
     * 判据两半，缺一不可：goal 归一化相等 ＋ 来源会话有交集。只比 goal 会把「两次真观察
     * 的同一件事」误判成重复（那该走 624 的归并）；只看会话会把「同一段轨迹换个意图名」
     * 误判成重复（那是人改主意，不是撞车）。
     *
     * 命中 ⇒ 交回既有那条。**调用方必须发声**（toast 说撞了哪条 ＋ 账里一行）：
     * 690 报的正是「被吞掉、连 toast 都没有」，所以本函数只负责判定，不允许被静默使用。
     */
    fun duplicateOf(draft: SkillDraft): SkillDraft? = synchronized(LOCK) {
        pendingList().firstOrNull { existing ->
            normalize(existing.goal) == normalize(draft.goal) &&
                existing.sourceSessions.any { it in draft.sourceSessions }
        }
    }

    fun byId(id: String): SkillDraft? = synchronized(LOCK) { pendingList().firstOrNull { it.id == id } }

    /** 人批销账（转正在 ObserveCenter.confirmDraft；这里只把草案移出待定）。 */
    fun resolve(id: String) = synchronized(LOCK) { removeLocked(id) }

    companion object {
        const val DIR_NAME = "草案"
        const val CAP = 20
        const val FILE_PREFIX = "草案-"
        const val FILE_SUFFIX = ".json"
        private val LOCK = SkillDraftStore::class.java

        private fun normalize(goal: String): String = goal.trim().replace(Regex("\\s+"), "")

        fun fileNameFor(id: String): String = FILE_PREFIX + id + FILE_SUFFIX
    }

    private fun pendingList(): List<SkillDraft> = (dir.listFiles() ?: emptyArray())
        .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
        .mapNotNull { f ->
            runCatching { SkillDraft.fromJson(JSONObject(f.readText(Charsets.UTF_8))) }.getOrNull() }
        .sortedBy { it.id }

    private fun removeLocked(id: String) {
        File(dir, fileNameFor(id)).takeIf { it.isFile }?.let { f -> runCatching { f.delete() } }
    }

    private fun writeLocked(draft: SkillDraft) {
        runCatching {
            dir.mkdirs()
            File(dir, fileNameFor(draft.id)).writeText(draft.toJson().toString(), Charsets.UTF_8)
        }.onFailure { RunLog.log("学习板块：草案写盘失败 ${it.javaClass.simpleName}") }
    }
}
