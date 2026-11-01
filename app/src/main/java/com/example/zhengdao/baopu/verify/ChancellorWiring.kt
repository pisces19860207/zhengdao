// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零二批 · 右相验收闸的**生产装配**（谁验收，谁取账）。
//
// 核心判据（[ChancellorGate]）只吃托单；本文件负责把托单装出来：
//   - 交付物声称：调用点给（转正的技能文件＋契约文本＝SKILL.md 正文）；
//   - 账行：现取两本账的**快照**（`FileOpLog` 进程账最近 [FILE_OPS_WINDOW] 笔／
//     `DecisionLedger` 读口全量）——判据不回查库，查账只在这里；
//   - 意见：四器 Opinion 的取数源在 298/306 接线（在飞），先给空集——
//     判据⑤（四器异议）与判据④③（不撞法条）在来源接通后自动生效，不需要再改这里。
//
// 权限同右相本体：**只读**——读技能正文（契约判定要用）与两本账的读口；
// 不写盘、不执行工具、不调模型（源级门禁钉在 ChancellorSourceGateTest）。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.law.Violation
import com.example.zhengdao.core.RunLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object ChancellorWiring {

    /** 报告头（grep 键：运行日志/账行里按它找右相的读数）。 */
    const val REPORT_HEAD = "右相验收"

    /** 三司会审报告头（grep 键：运行日志里按它找会审读数）。 */
    const val COURT_REPORT_HEAD = "三司会审"

    /** 取最近多少笔文件账行当快照（账是滚动 30 天，这里再截一道，别拖爆 800ms 预算）。 */
    const val FILE_OPS_WINDOW = 200

    /**
     * 学习成果入库（技能转正）时刻的右相验收（生产调用点在 `SkillPromote.auditPromote`）。
     *
     * 两本账快照现取现用；契约文本读 SKILL.md 正文（读不出 ⇒ 契约判不了 ⇒ UNVERIFIABLE，
     * 不猜）。样本证据引用给两个约定键：决策账的 `skill:<scope>/<name>` 与技能文件路径
     * ——两本账现无「技能用满 3 次」的样本行 ⇒ 转正读数常态是 **UNVERIFIABLE(NO_SAMPLES)**
     * （蓝总管 2026-10-31 口径：没有样本可判 ≠ 判了不合格，不报 FAIL 免得狼来了）；
     * 298/304 的样本行接通后同一套判据自动转 FAIL/PASS。
     */
    fun verifyLearningPromotion(skillFile: File, scope: String, name: String): ChancellorReport {
        val body = runCatching { skillFile.readText(Charsets.UTF_8) }.getOrNull()
        val submission = ChancellorSubmission(
            deliverables = listOf(
                ChancellorDeliverable(
                    kind = ChancellorClaimKind.LEARNING,
                    summary = "技能入库：$scope/$name",
                    path = skillFile.absolutePath,
                    contractText = body,
                    evidence = listOf("skill:$scope/$name", skillFile.absolutePath),
                ),
            ),
            opinions = emptyList(),
            ledger = ChancellorLedger(
                fileOps = FileOpLog.recent(FILE_OPS_WINDOW),
                decisions = decisionSnapshot(),
            ),
        )
        return ChancellorGate.verify(submission)
    }

    // ── 第三百二十五批 · 三司会审的生产接线（谁把哪笔交付送进会审）──────────
    //
    // 纪律（派工单 325，逐条）：
    //   - 日常交付不进三司：案由没命中任何触发条件 ⇒ 本层直接返回 null，
    //     `ThreeCourts.convene` 一次都不会被调（判据：普通收尾调用次数 = 0）；
    //   - 任一 FAIL ⇒ 交人：汇总裁定是 [ThreeCourts] 的 fail-closed（非多数决），
    //     本层只把裁定书整行进运行日志并原样返回，不数票、不拦流程、不执行动作；
    //   - `convene` 只出结论：本层同样只读账、只出结论——不改文件、不执行工具。
    //
    // 生产调用点三处（④是给 322 治理面板的公开方法，本批只有方法没有 UI）：
    //   ① 不可逆动作的交付 → 引擎收尾（RunEngine 文本收尾处，ArtifactVerifier.check 旁）；
    //   ② 同一交付连续 2 次 FAIL → 右相结论落账处（SkillPromote 的 ChancellorWiring 调用点旁）；
    //   ③ 学习成果入库 → SkillPromote（同一个调用点旁：入库即送审）。

    /** 本 run 真执行过的**删/移类**操作（不可逆触发的口径：账上有行才算发生）。 */
    private val IRREVERSIBLE_KINDS = setOf(FileOpKind.DELETE, FileOpKind.MOVE)

    /** 右相对同一交付的连续 FAIL 计数（触发②的读数；进程内，键＝交付键）。 */
    private val repeatFailStreak = ConcurrentHashMap<String, Int>()

    /** 直测收尾（生产不调；与 `FileOpLog.resetForTest` 同款纪律）。 */
    fun resetCourtStateForTest() {
        repeatFailStreak.clear()
    }

    /**
     * 触发①：不可逆动作的交付（引擎收尾时刻送审）。
     *
     * 「不可逆」只认**删/移类**（账上 [FileOpKind.DELETE]／[FileOpKind.MOVE]）：
     * 覆盖写是日常交付的常态，不算——把 write 也算进去，三司就成日常打卡了。
     * 口径与 [com.example.zhengdao.baopu.law.DiscretionLadder] 的「不可逆必批」同源。
     *
     * @param sessionId 本 run 的会话（文件账按会话分账）
     * @param sinceMs 本 run 的开跑时刻（只认这之后的账行；上一轮的删移不算这一轮的交付）
     * @return null = 本 run 没动过删/移 ⇒ 日常交付，不进三司（convene 一次都没调）
     */
    fun conveneIrreversibleDelivery(
        deliveryId: String,
        sessionId: Long,
        sinceMs: Long,
        nowMs: Long,
    ): ThreeCourtsReport? {
        val fileOps = FileOpLog.recent(FILE_OPS_WINDOW)
        val ops = fileOps
            .filter { it.sessionId == sessionId && it.at >= sinceMs && it.kind in IRREVERSIBLE_KINDS }
            .distinctBy { it.path }
        if (ops.isEmpty()) return null
        val submission = ChancellorSubmission(
            deliverables = ops.map {
                ChancellorDeliverable(
                    kind = ChancellorClaimKind.TEXT_ONLY,
                    summary = "不可逆动作交付：${it.kind.label} ${it.path}（${it.tool}）",
                )
            },
            opinions = emptyList(),
            ledger = ChancellorLedger(fileOps = fileOps, decisions = decisionSnapshot()),
        )
        val factSpecs = ops.map { FactSpec.ledgerRow(it.path) }
        return conveneByReferral(
            CourtReferral(deliveryId = deliveryId, irreversible = true),
            submission,
            factSpecs,
            nowMs,
        )
    }

    /**
     * 触发②＋③：学习成果入库（技能转正）时刻送审（生产调用点在 `SkillPromote.auditPromote`）。
     *
     * 触发③恒命中（入库即送审）；若右相对**同一技能**已连续判了 2 次 FAIL（[repeatFailStreak]
     * 的读数，含本次），触发②优先——案由标签变 REPEAT_FAIL，会审照开一次（每次入库只送一审）。
     *
     * @param chancellor 同一刻右相已出的三态结论（[verifyLearningPromotion] 的读数）：
     *   FAIL ⇒ 连续计数 +1，其余（含 UNVERIFIABLE）⇒ 归零。
     */
    fun conveneAtLearningPromotion(
        skillFile: File,
        scope: String,
        name: String,
        chancellor: ChancellorReport,
        nowMs: Long,
    ): ThreeCourtsReport? {
        val key = "skill:$scope/$name"
        val streak = if (chancellor.verdict == ChancellorVerdict.FAIL) {
            (repeatFailStreak[key] ?: 0) + 1
        } else {
            0
        }
        repeatFailStreak[key] = streak
        val submission = ChancellorSubmission(
            deliverables = listOf(
                ChancellorDeliverable(
                    kind = ChancellorClaimKind.LEARNING,
                    summary = "技能入库：$scope/$name",
                    path = skillFile.absolutePath,
                    contractText = runCatching { skillFile.readText(Charsets.UTF_8) }.getOrNull(),
                    evidence = listOf(key, skillFile.absolutePath),
                ),
            ),
            opinions = emptyList(),
            ledger = ChancellorLedger(
                fileOps = FileOpLog.recent(FILE_OPS_WINDOW),
                decisions = decisionSnapshot(),
            ),
        )
        // 事实核对一条：转正的决策账行（evidence=技能键）在不在——审计旁挂账本没写成 ⇒ 暗卫抓到。
        return conveneByReferral(
            CourtReferral(
                deliveryId = key,
                consecutiveFails = streak,
                learningIngest = true,
            ),
            submission,
            listOf(FactSpec.ledgerRow(key)),
            nowMs,
        )
    }

    /**
     * 触发④：用户点名（决策中心「审一遍」入口，供 322 治理面板调用；本批只有方法没有 UI）。
     *
     * 托单由调用方（322）装配——历史交付长什么样只有面板知道；本方法只管送审与落日志。
     *
     * @param violationHistory 违规历史（L24 累进用；违规账还没有独立存储，322 接账时喂入）
     */
    fun conveneUserNamed(
        deliveryId: String,
        submission: ChancellorSubmission,
        factSpecs: List<FactSpec>,
        nowMs: Long,
        violationHistory: List<Violation> = emptyList(),
    ): ThreeCourtsReport? = conveneByReferral(
        CourtReferral(deliveryId = deliveryId, userNamed = true),
        submission,
        factSpecs,
        nowMs,
        violationHistory,
    )

    /**
     * 会审唯一入口（四个触发都汇到这一处）：日常交付返回 null，其余开审＋裁定书整行进运行日志。
     * 全仓对 `ThreeCourts.convene` 的**唯一**非测试调用点在这里（源级门禁钉）。
     */
    private fun conveneByReferral(
        referral: CourtReferral,
        submission: ChancellorSubmission,
        factSpecs: List<FactSpec>,
        nowMs: Long,
        violationHistory: List<Violation> = emptyList(),
    ): ThreeCourtsReport? {
        if (referral.trigger() == null) return null
        return ThreeCourts.convene(referral, submission, factSpecs, violationHistory, nowMs)
            .also { RunLog.log(it.render()) }
    }

    /** 决策账快照（读口读不到 ⇒ 空集，不猜；与 [verifyLearningPromotion] 同一口径）。 */
    private fun decisionSnapshot(): List<DecisionRecord> =
        DecisionLedger.installedRoot()?.let { DecisionLedger.read(it) } ?: emptyList()
}
