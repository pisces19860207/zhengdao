// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零二批 · 右相验收闸：交付与学习成果的**独立验证**（三态＋fail-closed）。
//
// 判据（派工单 302 §二，确定性、零模型）：
//   C1 交付物存在且可用：路径存在＋大小>0＋（可判时）哈希；
//   C2 声称与事实一致：声称写了 ⇒ 账行必须有对应记录；**没有账行 = 未完成**（不是失败）；
//   C3 契约满足（技能交付）：success 段字段齐全、appliesWhen 可解析（解析复用 SkillContract）；
//   C4 学习成果：①有契约 ②有至少一条样本证据 ③不撞法条；
//     ②的口径（蓝总管 2026-10-31 改）：**样本集为空（没带引用/引用对不上账）⇒ UNVERIFIABLE
//     (NO_SAMPLES)**——「没有样本可判」和「判了不合格」是两件事，都报 FAIL 人就不看红了；
//     报告写明「等 298/304 供样本后自动转 FAIL/PASS」。
//   C5 四器异议：非建议级的 Opinion（带法条或重大级）是约束，有一票就不能绿灯。
//   C0 紧急卡：**不进本闸**——必须人批（2026-10-30 定），带 urgent 标记即打回。
//
// 三态与 fail-closed（§三；汇总优先级：超时恒 UNVERIFIABLE＞确定不过（FAIL，缺什么的
// 行照进报告）＞判不了（UNVERIFIABLE）＞PASS——坏契约不许被「等样本」盖住）：
//   PASS         全部判据可判且通过（纯只读交付可直通，但报告写明「直通」）；
//   FAIL         任一判据**确定**不通过（附哪条判据、什么证据）；
//   UNVERIFIABLE 判不了（信息不足/读不了/**超时**）⇒ 必须进报告、带回「缺什么」，不许当通过。
//
// 权限（§四，克制）：右相只有三态结论——不改文件、不执行工具、不替人做产品判断；
// 单次验收超 [TIMEOUT_BUDGET_MS] 直接降级 UNVERIFIABLE（不阻塞流水线），判到哪一步带哪一步的读数。
// 本文件零模型、零对话、零写盘：只读交付物文件本体（判 C1 要的），其余全靠托单喂进来。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.SkillContract
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import java.io.File
import java.security.MessageDigest

/** 三态（fail-closed：判不了不算过）。 */
internal enum class ChancellorVerdict { PASS, FAIL, UNVERIFIABLE }

/** 一条验收发现：哪条判据、什么证据、凭什么（FAIL 必带；UNVERIFIABLE 带判到哪了）。 */
internal data class ChancellorFinding(
    val criterion: String,
    val evidence: String,
    val note: String,
)

/** 右相的验收报告（它唯一的产出：三态结论＋发现＋缺什么；UNVERIFIABLE 必进报告）。 */
internal data class ChancellorReport(
    val verdict: ChancellorVerdict,
    val findings: List<ChancellorFinding>,
    /** 支撑读数（哈希/字节数/直通标注/对上的证据键）——PASS 与半程读数都放这里。 */
    val notes: List<String>,
    /** UNVERIFIABLE 必带回「缺什么」（缺证据/缺契约/缺路径/超时）。 */
    val missing: List<String>,
    /** 直通 PASS（纯只读、无副作用账行）；其余恒 false。 */
    val directPass: Boolean,
    val elapsedMs: Long,
) {
    fun render(): String = buildString {
        append("右相验收：").append(
            when (verdict) {
                ChancellorVerdict.PASS -> "PASS"
                ChancellorVerdict.FAIL -> "FAIL（打回）"
                ChancellorVerdict.UNVERIFIABLE -> "UNVERIFIABLE（判不了，不当通过）"
            },
        )
        if (directPass) append("〔直通：纯只读、无副作用账行〕")
        append('\n')
        for (f in findings.take(MAX_RENDER)) {
            append("- [").append(f.criterion).append("] ").append(f.evidence).append("：").append(f.note).append('\n')
        }
        if (findings.size > MAX_RENDER) append("……还有 ").append(findings.size - MAX_RENDER).append(" 条\n")
        for (m in missing) append("缺：").append(m).append('\n')
        for (n in notes.take(MAX_RENDER)) append("· ").append(n).append('\n')
    }

    companion object {
        private const val MAX_RENDER = 20
    }
}

/** 右相闸本体（纯判定：只读托单里点名的交付物文件，其余零 IO）。 */
internal object ChancellorGate {

    /** 单次验收预算（派工单 §三：>800ms 直接 UNVERIFIABLE，不阻塞）。 */
    const val TIMEOUT_BUDGET_MS = 800L

    const val C0_URGENT = "C0紧急卡"
    const val C1_USABLE = "C1交付物可用"
    const val C2_LEDGER = "C2声称与事实一致"
    const val C3_CONTRACT = "C3契约"
    const val C4_LEARNING = "C4学习成果"
    const val C5_OPINION = "C5四器异议"

    /**
     * 验一批交付声称。
     * @param now 单调时钟（纳秒）；生产用默认，测试注入假钟钉超时降级。
     */
    fun verify(sub: ChancellorSubmission, now: () -> Long = System::nanoTime): ChancellorReport {
        val startNanos = now()
        val findings = ArrayList<ChancellorFinding>()
        val notes = ArrayList<String>()
        val missing = ArrayList<String>()

        // C0：紧急卡不进本闸（必须人批）——带标记即打回，不存在「紧急的自动批」。
        if (sub.urgent) {
            findings += ChancellorFinding(
                C0_URGENT,
                "urgent=true",
                "紧急卡不进本闸：必须人批（2026-10-30 定），右相不参与",
            )
            return assemble(findings, notes, missing, sub, startNanos, now)
        }
        // C5：非建议级异议是约束（带法条或重大级），一票就不能绿灯。
        for (o in sub.opinions) {
            if (!o.suggestionOnly) {
                findings += ChancellorFinding(
                    C5_OPINION,
                    opinionKey(o),
                    "非建议级异议（法条 ${o.lawRef}）：不能当通过",
                )
            }
        }
        for (d in sub.deliverables) {
            if (overBudget(startNanos, now)) {
                missing += TIMEOUT_LINE
                // 超时恒 UNVERIFIABLE（派工单 §三硬规则，不随半程 FAIL 翻成 FAIL）。
                return assemble(findings, notes, missing, sub, startNanos, now, forceUnverifiable = true)
            }
            when (d.kind) {
                ChancellorClaimKind.TEXT_ONLY ->
                    notes += "直通：${d.summary}（纯只读、无副作用账行）"
                ChancellorClaimKind.FILE, ChancellorClaimKind.SKILL ->
                    checkArtifact(d, sub, findings, notes, missing)
                ChancellorClaimKind.LEARNING ->
                    checkLearning(d, sub, findings, notes, missing)
            }
            if (overBudget(startNanos, now)) {
                missing += TIMEOUT_LINE
                return assemble(findings, notes, missing, sub, startNanos, now, forceUnverifiable = true)
            }
        }
        return assemble(findings, notes, missing, sub, startNanos, now)
    }

    /** 超时降级那一行（grep 键；判据③）。 */
    const val TIMEOUT_LINE = "验收超 $TIMEOUT_BUDGET_MS ms：判到一半就停（不阻塞流水线），带回人工/后续补验"

    /**
     * 样本集为空的降级行（grep 键 `NO_SAMPLES`；蓝总管 2026-10-31 口径）。
     * 「没有样本可判」≠「判了不合格」：后者是 FAIL，前者先挂起，等 298/304 供样本。
     */
    const val NO_SAMPLES_LINE =
        "NO_SAMPLES：没有样本可判（学习成果②先挂起，不是 FAIL）——等 298/304 供样本后自动转 FAIL/PASS"

    /**
     * 汇总三态（fail-closed；优先级见文件头）：
     * 超时恒 UNVERIFIABLE ＞ 有确定不过 ⇒ FAIL（缺什么的行照进报告，坏契约不许被
     * 「等样本」盖住）＞ 有判不了 ⇒ UNVERIFIABLE ＞ PASS。
     */
    private fun assemble(
        findings: List<ChancellorFinding>,
        notes: List<String>,
        missing: List<String>,
        sub: ChancellorSubmission,
        startNanos: Long,
        now: () -> Long,
        forceUnverifiable: Boolean = false,
    ): ChancellorReport {
        val verdict = when {
            forceUnverifiable -> ChancellorVerdict.UNVERIFIABLE
            findings.isNotEmpty() -> ChancellorVerdict.FAIL
            missing.isNotEmpty() -> ChancellorVerdict.UNVERIFIABLE
            else -> ChancellorVerdict.PASS
        }
        val direct = verdict == ChancellorVerdict.PASS &&
            sub.deliverables.all { it.kind == ChancellorClaimKind.TEXT_ONLY }
        return ChancellorReport(
            verdict = verdict,
            findings = findings,
            notes = notes,
            missing = missing,
            directPass = direct,
            elapsedMs = (now() - startNanos) / 1_000_000,
        )
    }

    private fun overBudget(startNanos: Long, now: () -> Long): Boolean =
        (now() - startNanos) / 1_000_000 > TIMEOUT_BUDGET_MS

    /** 产物/技能交付：C1 存在可用 ＋ C2 账行对得上 ＋（技能）C3 契约。 */
    private fun checkArtifact(
        d: ChancellorDeliverable,
        sub: ChancellorSubmission,
        findings: ArrayList<ChancellorFinding>,
        notes: ArrayList<String>,
        missing: ArrayList<String>,
    ) {
        val root = sub.root
        if (root == null) {
            missing += "缺解析根（C1 判不了「${d.path}」在不在）"
            return
        }
        if (d.path.isBlank()) {
            missing += "缺交付路径（C1 判不了：只有摘要没有路径）"
            return
        }
        val rel = ArtifactVerifier.resolve(root, d.path)
        if (rel == null) {
            findings += ChancellorFinding(C1_USABLE, d.path, "路径越界或不可解析，已拒绝")
            return
        }
        val f = File(root, rel)
        if (!f.exists()) {
            findings += ChancellorFinding(C1_USABLE, rel, "交付物不存在")
            return
        }
        if (!f.isFile) {
            findings += ChancellorFinding(C1_USABLE, rel, "路径存在但不是文件")
            return
        }
        if (f.length() == 0L) {
            findings += ChancellorFinding(C1_USABLE, rel, "文件是 0 字节（不可用）")
            return
        }
        val head = readHead(f)
        if (head == null) {
            missing += "交付物读不了（C1 哈希判不了）：$rel"
            return
        }
        notes += "${d.summary}：$rel（${head.size} 字节，sha256 前 8 位 ${sha8(head)}，C1 过）"
        // C2：声称写了 ⇒ 账上必须有对应的写/移/拷行；没有 = 未完成（不是失败）。
        if (!ledgerHasWrite(sub.ledger, root, rel)) {
            findings += ChancellorFinding(
                C2_LEDGER,
                rel,
                "声称写了但账上没有对应的写/移/拷行——未完成（不是失败）",
            )
        }
        if (d.kind == ChancellorClaimKind.SKILL) checkContract(d, findings, notes, missing, C3_CONTRACT)
    }

    /** 契约判定（C3 技能交付／C4① 学习成果共用；文本缺 ⇒ 判不了，不猜）。 */
    private fun checkContract(
        d: ChancellorDeliverable,
        findings: ArrayList<ChancellorFinding>,
        notes: ArrayList<String>,
        missing: ArrayList<String>,
        criterion: String,
    ) {
        val text = d.contractText
        if (text == null) {
            missing += "缺契约文本（$criterion 判不了，不猜）：${d.summary}"
            return
        }
        when (val p = SkillContract.parse(text)) {
            is SkillContract.Parsed.Absent ->
                findings += ChancellorFinding(criterion, d.summary, "契约缺失（一条 contract 行都没有）")
            is SkillContract.Parsed.Invalid ->
                findings += ChancellorFinding(criterion, d.summary, "契约解析不过：${p.errors.joinToString("；")}")
            is SkillContract.Parsed.Contracted -> {
                val a = p.contract.appliesWhen.size
                val s = p.contract.success.size
                if (a == 0 || s == 0) {
                    findings += ChancellorFinding(criterion, d.summary, "契约不齐（缺 ${p.missingSections}）")
                } else {
                    notes += "契约齐：appliesWhen $a 条／success $s 条"
                }
            }
        }
    }

    /** 学习成果（C4）：①契约（FAIL）②样本证据（空⇒UNVERIFIABLE，见下）③不撞法条（FAIL）。 */
    private fun checkLearning(
        d: ChancellorDeliverable,
        sub: ChancellorSubmission,
        findings: ArrayList<ChancellorFinding>,
        notes: ArrayList<String>,
        missing: ArrayList<String>,
    ) {
        // ①②在同一份契约文本上，缺文本时 ②仍可判（证据在账行/意见里），所以不提前返回。
        checkContract(d, findings, notes, missing, C4_LEARNING)
        val refs = d.evidence.map { it.trim() }.filter { it.isNotEmpty() }
        // ②（蓝总管 2026-10-31 口径）：样本集为空 ⇒ UNVERIFIABLE(NO_SAMPLES)，不是 FAIL——
        // 「没有样本可判」和「判了不合格」是两件事；等 298/304 供样本后自动转 FAIL/PASS。
        val hit = refs.firstOrNull { resolvesInLedger(it, sub) }
        when {
            refs.isEmpty() -> missing += NO_SAMPLES_LINE
            hit == null -> missing += NO_SAMPLES_LINE + "（引用 ${refs.joinToString("／")} 在账行/意见里对不上）"
            else -> notes += "样本证据对上账：$hit"
        }
        // ③撞法条：带法条号的异议就是「这条成果与现行条文相撞」的确定证据。
        for (o in sub.opinions) {
            if (o.lawRef != LawRef.NONE) {
                findings += ChancellorFinding(C4_LEARNING, opinionKey(o), "③撞法条（${o.lawRef}）")
            }
        }
    }

    /** C2 的账行匹配：写/移/拷三类、解析后路径逐字相等（口径同 [ArtifactVerifier.resolve]）。 */
    private fun ledgerHasWrite(ledger: ChancellorLedger, root: File, rel: String): Boolean =
        ledger.fileOps.any { e ->
            when (e.kind) {
                FileOpKind.WRITE, FileOpKind.MOVE, FileOpKind.COPY ->
                    ArtifactVerifier.resolve(root, e.path) == rel
                else -> false
            }
        }

    /** 样本证据引用能不能对上账：决策行 evidence／意见证据 id／文件账行路径。 */
    private fun resolvesInLedger(ref: String, sub: ChancellorSubmission): Boolean =
        sub.ledger.decisions.any { it.evidence == ref } ||
            sub.opinions.any { it.evidence.contains(ref) } ||
            sub.ledger.fileOps.any {
                it.path.trim() == ref ||
                    (sub.root != null && ArtifactVerifier.resolve(sub.root, it.path) == ref)
            }

    private fun opinionKey(o: Opinion): String =
        "${o.source}/${o.severity}/${o.gist}"

    /** 只读文件头（超上限只读前 4 MB，防大文件拖爆 800ms 预算）；读不了 ⇒ null。 */
    private fun readHead(f: File): ByteArray? = runCatching {
        f.inputStream().use { ins ->
            val len = minOf(f.length(), ArtifactVerifier.MAX_CHECK_BYTES).toInt()
            val buf = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = ins.read(buf, read, len - read)
                if (n <= 0) break
                read += n
            }
            if (read <= 0) null else buf.copyOf(read)
        }
    }.getOrNull()

    /** 短的稳定哈希（8 位十六进制；「可判时」的哈希证据）。 */
    private fun sha8(bytes: ByteArray): String = runCatching {
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(8)
    }.getOrDefault("不可算")
}
