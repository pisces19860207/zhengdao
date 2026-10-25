// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-26-第二百二十批-后果预览 §2 覆盖表的最后三行
// （技能回放／**技能自治**／**日记自治**）。
//
// 这三类的共同点：**判据全是别人写好的纯函数**——技能回放读技能 JSON（196）、
// 技能自治读 `SkillDraftGovernor.plan`（205）、日记自治读 `DiaryRecall`（147）。
// 本文件只把它们折成 `Impact` 事实，**一行写盘都没有**（执行壳分别在各 UI 侧）。
//
// 为什么单独成文件：它们是"结构化的既有判据 → 事实"，与 `ImpactPreview` 那种
// "问文件系统/问库"的活不是一类；混在一起既难看也占行数额度。
package com.example.zhengdao.baopu

import org.json.JSONObject

/** 技能回放／技能自治／日记自治的算（纯函数，零 IO）。 */
internal object ImpactGovernance {

    // ── 技能回放（第一百九十六批）──────────────────────────────────────
    //
    // 技能 JSON 里的步骤数与"碰几位文件"的读数：**解析不出来就当没有**（不显示），
    // 因为"预计 0 步"会让人以为什么都没干。
    fun skillReplay(skillBody: String): Impact {
        val steps = runCatching {
            val o = JSONObject(skillBody)
            val arr = o.optJSONArray("steps") ?: return@runCatching null
            val files = buildSet {
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    for (k in listOf("path", "file", "target_file")) {
                        val v = s.optString(k).trim()
                        if (v.isNotEmpty()) add(v)
                    }
                }
            }
            arr.length() to files.size
        }.getOrNull() ?: return Impact()
        val n = steps.first
        return Impact(
            Impact.Kind.SKILL_REPLAY,
            stepsLo = n,
            // 回放允许一次重试/一次补步：上限给 +2（区间而不是点值，别把话说满）
            stepsHi = n + 2,
            touches = steps.second,
        )
    }

    // ── 技能自治（第二百零五批纯规则，只读复用）──────────────────────────
    fun skillGovern(plan: GovernancePlan, statsSize: Int, recentUsed: Int): Impact {
        val retire = plan.idleRetire + plan.overflowRetire
        if (plan.isEmpty) {
            return Impact(Impact.Kind.SKILL_GOVERN, keepCount = statsSize, recentlyUsed = recentUsed)
        }
        return Impact(
            Impact.Kind.SKILL_GOVERN,
            retireCount = retire.size,
            retireNames = retire.map { it.name }.take(ImpactPreview.MAX_NAMES),
            promoteCount = plan.promoteDue.size,
            keepCount = (statsSize - retire.size).coerceAtLeast(0),
            recentlyUsed = recentUsed,
        )
    }

    // ── 日记自治（第一百四十七批维护，只读复用判据）────────────────────
    //
    // 两批条目分开传：`entries` = 现役日记（`记忆/日记/`，归档候选从这里出）、
    // `archived` = 已归档那批（`记忆/归档/`，清理候选从这里出）。为什么不自己读盘：
    // 判断"这条算不算归档"要看**文件位置**（`DiaryStore.loadArchive` 的活），
    // 预览只借它的结果，不重算一遍路径。
    fun diaryMaintain(
        entries: List<DiaryEntry>,
        archived: List<DiaryEntry>,
        nowMs: Long,
    ): Impact {
        val candidates = DiaryRecall.archiveCandidates(entries, nowMs, DiaryRecall.RETAIN_DAYS)
        // 清理判据的另一半是「归档满 N 天」（看文件时间戳，属 IO）——那半在 DiaryStore
        // 的 maintain 里。预览这边只算**能算的那半**：条目被同月摘要完整承载的条数。
        // 因此这个数是**上界**（真实清理 ≤ 它）；界面/注入的措辞必须写成「最多 N 条」。
        val digests = entries.filter { DiaryRecall.isDigest(it.id) } + archived.filter { DiaryRecall.isDigest(it.id) }
        val clean = if (archived.isEmpty()) emptyList() else DiaryRecall.cleanupCandidates(archived, digests)
        return Impact(
            Impact.Kind.DIARY_MAINTAIN,
            archiveCount = candidates.size,
            cleanCount = clean.size,
            indexRebuild = candidates.isNotEmpty() || clean.isNotEmpty(),
        )
    }
}
