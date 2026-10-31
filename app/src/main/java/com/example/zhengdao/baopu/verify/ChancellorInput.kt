// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零二批 · 右相验收闸的**输入形状**（输入隔离，不许扩）。
//
// 右相＝交付与学习成果的独立验收者（2026-10-30 定：左相判打扰、右相判交付、引擎干活、人拍板）。
// 输入只许三样（派工单 302 §一）：
//   ① 交付物（路径＋内容摘要）；
//   ② `Opinion[]`（四器的结构化意见，复用 inspect.Opinion 原型，零拷贝零改形）；
//   ③ 账行（`FileOpLedger`／`DecisionLedger`：动作到底发生过没有）。
//
// 「明令不许」不靠自觉靠形状：本文件**没有**对话全文／执行过程上下文的字段——想喂也喂不进来；
// 判定也不在输入里做（ChancellorGate 只吃这份托单）。
// 账行以**快照入参**进来（[ChancellorLedger]），核心不摸全局单例——谁装配谁取账。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.FileOpEntry
import com.example.zhengdao.baopu.inspect.Opinion
import java.io.File

/** 交付物声称的种类（闭集：判什么由种类定，不猜）。 */
internal enum class ChancellorClaimKind(val label: String) {
    /** 落盘产物：验「存在且可用」（C1）＋「账行对得上」（C2）。 */
    FILE("产物"),

    /**
     * 纯文本交付（如生成一段文字）：无落盘、无副作用账行可对 ⇒ 可**直通** PASS，
     * 但报告必须写明「直通」（派工单 §三「轻量直通」）。
     */
    TEXT_ONLY("纯文本"),

    /** 技能交付：产物两判之外再验契约（C3：success 字段齐全、appliesWhen 可解析）。 */
    SKILL("技能"),

    /** 学习成果入库：契约＋至少一条样本证据＋不撞法条（C4）。 */
    LEARNING("学习成果"),
}

/** 右相输入①：一条交付物声称（路径＋内容摘要；纯文本交付可无路径）。 */
internal data class ChancellorDeliverable(
    val kind: ChancellorClaimKind,
    /** 内容摘要（一句人话；进报告，判据不看它——判据只认路径/契约/账行）。 */
    val summary: String,
    /** 交付路径：工作区相对路径，或落在根内的绝对路径（口径同 [ArtifactVerifier.resolve]）。 */
    val path: String = "",
    /** 契约文本（SKILL.md 正文，带 `<!-- contract … -->` 行）；不给 = 判不了（UNVERIFIABLE），不猜。 */
    val contractText: String? = null,
    /** 学习成果的样本证据引用（账行 evidence／文件路径／意见证据 id）；C4② 要 ≥1 条对得上账。 */
    val evidence: List<String> = emptyList(),
) {
    init {
        require(summary.isNotBlank()) { "交付物声称缺内容摘要" }
    }
}

/**
 * 右相输入③：账行快照（动作到底发生过没有）。
 *
 * 只收两本账的**行**（派工单 302 §一·3 点名 `FileOpLedger`／`DecisionLedger`）；
 * 核心判据只看这份快照，不回查库——查账是装配层的活（[ChancellorWiring]）。
 */
internal data class ChancellorLedger(
    val fileOps: List<FileOpEntry> = emptyList(),
    val decisions: List<DecisionRecord> = emptyList(),
)

/**
 * 右相的一次验收托单（三样输入＋两样判定锚点）。
 *
 * `root`：交付路径的解析根（工作区/spill 根）——判「存在且可用」总得知道路径相对哪里，
 * 它是判定锚点不是第五样输入（不带任何内容）。
 * `urgent`：紧急卡。2026-10-30 定：**紧急卡必须人批，右相不参与**——托单里带这个标记的，
 * 闸直接打回（FAIL），不许绕进来自动批（负例④）。
 */
internal data class ChancellorSubmission(
    val deliverables: List<ChancellorDeliverable>,
    val opinions: List<Opinion>,
    val ledger: ChancellorLedger,
    val root: File? = null,
    val urgent: Boolean = false,
) {
    init {
        require(deliverables.isNotEmpty()) { "右相托单没有交付物声称（没有要验的东西）" }
    }
}
