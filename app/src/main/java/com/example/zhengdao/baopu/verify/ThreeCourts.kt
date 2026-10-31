// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十一批 · 三司会审——只对「重大」，日常不进（派工单 311 §一）。
//
// 三司（各司其职，谁也不替谁判）：
//   大理寺（审）＝右相 [ChancellorGate]：交付/学习成果合格与否（三态，302 定稿，只调用不改）；
//   都察院（察）＝暗卫抽检 [Duchayuan]：声称 vs 事实，右相判了 PASS 而事实不符 ⇒ 假PASS，
//     按 L24 累进记违规（法典卷七 [ProgressivePenalty]，RECORD→ASK→BLOCK→FUSE）；
//   刑部（核）＝勘验 [Xingbu]：事实认定——文件在不在 / 字节对不对 / 账行有没有。
//     机械核对，**模型自述不是事实种类**（[FactKind] 闭集里没有那个口子）。
//
// 触发四条件（命中任一才审，[CourtReferral.trigger]；全不命中 = 日常交付，不进三司）：
//   ① 不可逆动作的交付 ② 同一交付连续 2 次 FAIL ③ 学习成果入库 ④ 用户点名。
//
// 汇总裁定（**不是多数决**，fail-closed）：
//   任一司投 FAIL、或右相 UNVERIFIABLE、或暗卫抓到假PASS ⇒ 状态 FAIL 并交人（escalate）；
//   三司全过才 PASS。没有「2:1 放行」这条路——数票的代码不存在。
//
// 房型纪律（照 302/275）：
//   - 零 IO、零时钟、零写盘：账行走 [ChancellorLedger] 快照入参，`nowMs` 由调用方给；
//   - 日常交付进不了会审：[ThreeCourts.convene] 对无触发的案由直接抛（断言调用次数=0 的判定层版本）；
//   - 本文件不 import `agent.LearnWiring`（右相反向监督①的另一半在源级门禁测试里钉）。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.law.PenaltyVerdict
import com.example.zhengdao.baopu.law.ProgressivePenalty
import com.example.zhengdao.baopu.law.Violation
import java.io.File

/** 触发四条件（命中任一才审；顺序即 [CourtReferral.trigger] 的取值优先级）。 */
internal enum class CourtTrigger(val label: String) {
    /** ① 不可逆动作的交付。 */
    IRREVERSIBLE("不可逆动作的交付"),

    /** ② 同一交付连续 2 次 FAIL。 */
    REPEAT_FAIL("同一交付连续两次FAIL"),

    /** ③ 学习成果入库。 */
    LEARNING_INGEST("学习成果入库"),

    /** ④ 用户点名。 */
    USER_NAMED("用户点名"),
}

/**
 * 一起三司案由（谁把哪笔交付送进会审）。
 *
 * 四个旗标全由调用方给（装配层从交付流水里取事实），本层不读流水；
 * 全 false = 日常交付 ⇒ [trigger] 为 null，[ThreeCourts.convene] 拒收。
 */
internal data class CourtReferral(
    val deliveryId: String,
    val irreversible: Boolean = false,
    val consecutiveFails: Int = 0,
    val learningIngest: Boolean = false,
    val userNamed: Boolean = false,
) {
    init {
        require(deliveryId.isNotBlank()) { "三司案由缺交付 id" }
        require(consecutiveFails >= 0) { "连续 FAIL 次数不许为负" }
    }

    /** 命中的触发条件；全不命中（日常交付）⇒ null。 */
    fun trigger(): CourtTrigger? = when {
        irreversible -> CourtTrigger.IRREVERSIBLE
        consecutiveFails >= REPEAT_FAIL_THRESHOLD -> CourtTrigger.REPEAT_FAIL
        learningIngest -> CourtTrigger.LEARNING_INGEST
        userNamed -> CourtTrigger.USER_NAMED
        else -> null
    }

    companion object {
        /** 触发条件②的门槛：同一交付连续 2 次 FAIL。 */
        const val REPEAT_FAIL_THRESHOLD = 2

        /** 日常交付的案由（四旗标全 false；只够证明「不触发」，进不了会审）。 */
        fun daily(deliveryId: String): CourtReferral = CourtReferral(deliveryId)
    }
}

/** 刑部的事实种类（**闭集**：机械核对只有这三种；「模型自述」不是事实，没有那个口子）。 */
internal enum class FactKind(val label: String) {
    FILE_EXISTS("文件在不在"),
    BYTES_MATCH("字节对不对"),
    LEDGER_ROW("账行有没有"),
}

/**
 * 一条事实核对单（构造口只有 companion 的三个工厂——形状封死，喂不进自述文本）。
 */
internal data class FactSpec private constructor(
    val kind: FactKind,
    val target: String,
    val expectedBytes: Long,
) {
    companion object {
        private const val NO_BYTES_CHECK = -1L

        /** 文件在不在：工作区相对路径（口径同 [ArtifactVerifier.resolve]）。 */
        fun fileExists(target: String): FactSpec = FactSpec(FactKind.FILE_EXISTS, target, NO_BYTES_CHECK)

        /** 字节对不对：文件字节数必须等于 [expectedBytes]（账行/确认卡上的数，不是模型说的数）。 */
        fun bytesMatch(target: String, expectedBytes: Long): FactSpec {
            require(expectedBytes >= 0) { "字节核对缺期望值" }
            return FactSpec(FactKind.BYTES_MATCH, target, expectedBytes)
        }

        /** 账行有没有：按键查快照（fileOps 路径 / 决策行 id、回滚引用、证据 id）。 */
        fun ledgerRow(key: String): FactSpec = FactSpec(FactKind.LEDGER_ROW, key, NO_BYTES_CHECK)
    }
}

/** 刑部一条核对结论（ok=false 时 [detail] 必说清差在哪）。 */
internal data class ProbeResult(
    val kind: FactKind,
    val target: String,
    val ok: Boolean,
    val detail: String,
)

/** 刑部（勘验）：事实认定——只做机械核对，一个字都不问模型。 */
internal object Xingbu {

    /** 核一批单子。root 为空时文件类核对一律不成立（判不了 ≠ 判过了）。 */
    fun verify(root: File?, specs: List<FactSpec>, ledger: ChancellorLedger): List<ProbeResult> =
        specs.map { probe(root, it, ledger) }

    private fun probe(root: File?, spec: FactSpec, ledger: ChancellorLedger): ProbeResult = when (spec.kind) {
        FactKind.FILE_EXISTS -> fileProbe(root, spec)
        FactKind.BYTES_MATCH -> bytesProbe(root, spec)
        FactKind.LEDGER_ROW -> ledgerProbe(ledger, spec)
    }

    private fun fileProbe(root: File?, spec: FactSpec): ProbeResult {
        val r = root ?: return ProbeResult(spec.kind, spec.target, false, "无解析根，核不了（不当过）")
        val rel = ArtifactVerifier.resolve(r, spec.target)
            ?: return ProbeResult(spec.kind, spec.target, false, "路径越界或不可解析：${spec.target}")
        val f = File(r, rel)
        val ok = f.isFile
        return ProbeResult(spec.kind, spec.target, ok, if (ok) "文件在" else "文件不存在：$rel")
    }

    private fun bytesProbe(root: File?, spec: FactSpec): ProbeResult {
        val r = root ?: return ProbeResult(spec.kind, spec.target, false, "无解析根，核不了（不当过）")
        val rel = ArtifactVerifier.resolve(r, spec.target)
            ?: return ProbeResult(spec.kind, spec.target, false, "路径越界或不可解析：${spec.target}")
        val f = File(r, rel)
        if (!f.isFile) return ProbeResult(spec.kind, spec.target, false, "文件不存在：$rel")
        val ok = f.length() == spec.expectedBytes
        return ProbeResult(
            spec.kind,
            spec.target,
            ok,
            if (ok) "字节相符（${spec.expectedBytes}）" else "字节不符：账面 ${spec.expectedBytes}，实存 ${f.length()}",
        )
    }

    private fun ledgerProbe(ledger: ChancellorLedger, spec: FactSpec): ProbeResult {
        val ok = hasRow(ledger, spec.target)
        return ProbeResult(spec.kind, spec.target, ok, if (ok) "账行在" else "账上没有：${spec.target}")
    }

    private fun hasRow(ledger: ChancellorLedger, key: String): Boolean =
        ledger.fileOps.any { it.path == key } ||
            ledger.decisions.any { it.id == key || it.rollbackRef == key || it.evidence == key }
}

/** 大理寺（审）＝右相：只调用 302 定稿的 [ChancellorGate]，本层一个字不改它的判据。 */
internal object Dali {
    fun judge(submission: ChancellorSubmission): ChancellorReport = ChancellorGate.verify(submission)
}

/** 都察院（察）＝暗卫：抽查右相判了 PASS 的交付，假PASS 按 L24 累进记违规。 */
internal object Duchayuan {

    /** L24 累进分组的违规 kind（同 kind 才累进）。 */
    const val VIOLATION_KIND = "ducha_fake_pass"

    data class SpotResult(
        val fakePass: Boolean,
        /** 假PASS 时必非空（负例「抽检不记违规」钉在这）；干净交付为 null。 */
        val penalty: PenaltyVerdict?,
        val detail: String,
    )

    /** 只抽查 PASS 判定；右相自己投了 FAIL/UNVERIFIABLE 的不用暗卫再查一遍。 */
    fun spotCheck(
        judged: ChancellorVerdict,
        probes: List<ProbeResult>,
        history: List<Violation>,
        nowMs: Long,
    ): SpotResult {
        if (judged != ChancellorVerdict.PASS) {
            return SpotResult(fakePass = false, penalty = null, detail = "右相没判 PASS，无假PASS 可查")
        }
        val bad = probes.filterNot { it.ok }
        if (bad.isEmpty()) {
            return SpotResult(fakePass = false, penalty = null, detail = "抽查通过：声称与事实一致")
        }
        val penalty = ProgressivePenalty.decide(
            fresh = Violation(kind = VIOLATION_KIND, atMs = nowMs),
            history = history,
            nowMs = nowMs,
        )
        return SpotResult(
            fakePass = true,
            penalty = penalty,
            detail = "假PASS：${bad.size} 处事实不符（如 ${bad.first().kind.label}·${bad.first().target}）",
        )
    }
}

/** 会审总状态：三司全过才 PASS；任一 FAIL/UNVERIFIABLE/假PASS ⇒ FAIL 并交人。 */
internal enum class CourtStatus { PASS, FAIL }

/** 三司会审的裁定书（人读的部分在 [render]；机器消费的是字段本身）。 */
internal data class ThreeCourtsReport(
    val status: CourtStatus,
    /** 交人：任一 FAIL / UNVERIFIABLE / 假PASS ⇒ true；PASS 时恒 false。 */
    val escalate: Boolean,
    val trigger: CourtTrigger,
    val dali: ChancellorReport,
    val probes: List<ProbeResult>,
    val ducha: Duchayuan.SpotResult,
    /** 交人理由（谁投了什么、差在哪）；PASS 恒空。 */
    val reasons: List<String>,
) {
    fun render(): String = buildString {
        append("三司会审：")
        append(if (status == CourtStatus.PASS) "PASS" else "FAIL（交人）")
        append("〔触发：").append(trigger.label).append("〕\n")
        for (r in reasons) append("- ").append(r).append('\n')
        append(dali.render())
        if (ducha.fakePass) append("暗卫：").append(ducha.detail).append('\n')
    }
}

/** 三司会审本体（纯判定：零 IO、零时钟；装配层喂案由/托单/事实单/违规历史）。 */
internal object ThreeCourts {

    /**
     * 开审。案由没命中任何触发条件 ⇒ 直接抛——日常交付在形状上进不了会审。
     */
    fun convene(
        referral: CourtReferral,
        submission: ChancellorSubmission,
        factSpecs: List<FactSpec>,
        violationHistory: List<Violation>,
        nowMs: Long,
    ): ThreeCourtsReport {
        val trigger = referral.trigger()
            ?: throw IllegalArgumentException("三司只审重大：案由 ${referral.deliveryId} 未命中任何触发条件（日常交付不进三司）")
        val dali = Dali.judge(submission)
        val probes = Xingbu.verify(submission.root, factSpecs, submission.ledger)
        val ducha = Duchayuan.spotCheck(dali.verdict, probes, violationHistory, nowMs)
        val reasons = buildList {
            when (dali.verdict) {
                ChancellorVerdict.FAIL -> add("大理寺（右相）投 FAIL：交付不合格")
                ChancellorVerdict.UNVERIFIABLE -> add("大理寺（右相）UNVERIFIABLE：判不了也交人")
                ChancellorVerdict.PASS -> Unit
            }
            probes.filterNot { it.ok }.forEach { add("刑部（勘验）事实不符：${it.kind.label}·${it.target}") }
            if (ducha.fakePass) add("都察院（暗卫）抓到假PASS")
        }
        return ThreeCourtsReport(
            status = if (reasons.isEmpty()) CourtStatus.PASS else CourtStatus.FAIL,
            escalate = reasons.isNotEmpty(),
            trigger = trigger,
            dali = dali,
            probes = probes,
            ducha = ducha,
            reasons = reasons,
        )
    }
}
