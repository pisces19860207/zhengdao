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
import com.example.zhengdao.baopu.FileOpLog
import java.io.File

internal object ChancellorWiring {

    /** 报告头（grep 键：运行日志/账行里按它找右相的读数）。 */
    const val REPORT_HEAD = "右相验收"

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
                decisions = DecisionLedger.installedRoot()
                    ?.let { DecisionLedger.read(it) } ?: emptyList(),
            ),
        )
        return ChancellorGate.verify(submission)
    }
}
