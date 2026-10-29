// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十一批 · 法典门禁 **L17 无证据的反思不得落盘**（派工单 271；265 交付后补门禁）。
//
// ## 判据（可执行）
// 一张反思候选卡要出卡（进而才能被人点「记住它」落盘），必须**证据 ≥2 条且条条可点开**
// （`ReflectionCandidate.presentable`）。证据是「路径＋行号/日期/runId」，可点开＝生成时
// 那个文件/那一行真的在。
//
// 为什么是"≥2"而不是"≥1"：单条证据说不出「反复」——反思要指出的是**模式**，
// 一条只能说明「发生过一次」，那是流水账，不是反思。
//
// ## 负例（本文件自证会红）
// ① 一条证据 ⇒ 不许出卡；② 两条但有一条点不开（文件不在了）⇒ 不许出卡；
// ③ 零证据 ⇒ 不许出卡。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.ReflectionCandidate
import com.example.zhengdao.baopu.ReflectionChangeKind
import com.example.zhengdao.baopu.ReflectionEvidence
import com.example.zhengdao.baopu.ReflectionEvidenceKind
import com.example.zhengdao.baopu.ReflectionShape
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L17ReflectionEvidenceGateTest {

    private fun evidence(anchor: String, openable: Boolean = true, path: String = "记忆/日记/2026-10-27.md") =
        ReflectionEvidence(
            display = "日记 $anchor",
            path = path,
            anchor = anchor,
            kind = ReflectionEvidenceKind.DIARY,
            openable = openable,
        )

    private fun candidate(evidences: List<ReflectionEvidence>) = ReflectionCandidate(
        shape = ReflectionShape.REPEAT_LABOR,
        target = "日报整理",
        changeKind = ReflectionChangeKind.NEW_SKILL,
        claim = "同样的整理做了三次，值得沉淀成技能",
        evidences = evidences,
        benefit = "省一次手抄",
        risk = "技能过期要重教",
        landing = "技能/日报整理/SKILL.md",
        createdAt = 1_800_000_000_000L,
    )

    // ── 正例：两条可点开的证据 ⇒ 出卡 ───────────────────────────────────────

    @Test
    fun `L17_正例_两条可点开证据才出卡`() {
        val ok = candidate(listOf(evidence("L12"), evidence("L40")))
        assertTrue("两条可点开的证据应当出卡", ok.presentable)
        // 三条当然也行
        assertTrue("三条更好", candidate(listOf(evidence("L1"), evidence("L2"), evidence("L3"))).presentable)
    }

    // ── 负例：证据不足或点不开 ⇒ 不许落盘 ──────────────────────────────────

    @Test
    fun `L17_负例_证据不足或点不开一律不许出卡`() {
        assertFalse("零证据的反思是空话", candidate(emptyList()).presentable)
        assertFalse("一条证据只能说「发生过一次」，不算模式", candidate(listOf(evidence("L12"))).presentable)
        assertFalse(
            "两条里有一条点不开（文件没了）也不许出卡",
            candidate(listOf(evidence("L12"), evidence("L40", openable = false))).presentable,
        )
        assertFalse(
            "全点不开更不许",
            candidate(listOf(evidence("L1", openable = false), evidence("L2", openable = false))).presentable,
        )
    }
}
