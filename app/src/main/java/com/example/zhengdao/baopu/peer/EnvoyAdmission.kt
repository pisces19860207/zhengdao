// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十九批 · 外部 agent 的**准入画像接线**（派工单 549 ②：未过准入不许派活）。
//
// 全部判定件都是既有真件，本文件一行判卷代码都没有：
//   出题＝546 的 [com.example.zhengdao.baopu.intake.IntakeExam]（九道题：5 探针＋4 品行，
//   考场量产物、缓存按 (题目版本, 执行体版本)、UNVERIFIABLE 不进缓存）；
//   判定＝384 的 [com.example.zhengdao.baopu.intake.AdmissionGate]（Accept(tier)／Reject(kind)，
//   顺序即法条：malice → repeat_over_scope → allProbesFailed → accept）；
//   落盘＝[com.example.zhengdao.baopu.intake.AdmissionFlow]（准入画像快照＋准入账.jsonl）。
//
// 与 427 那条 MCP 准入（AdmissionWiring）的两处不同，都是**派工单定的语义**：
//   · 身份不从 wire 名还原，用 548 的稳定 id（[AgentIds.stableId]，endpoint 规范化哈希）——
//     MCP 侧与 A2A 侧共用一本画像账（同一把 `ag-` 键）；
//   · 版本指纹不用工具名集，用**卡上的能力指纹**（[A2aCard.capabilityFingerprint]）——
//     对端的能力清单一变（升版/改技能），旧卷作废重考（needsReprobe 说真话的根据）；
//   · **这里是人（未过准入不许派活），不是 427 那种"只登记不拦人"**：委派入口拿着本闸的
//     结论当门禁用——Reject ⇒ 一件活都不派，Accept(READ_ONLY) ⇒ 写类活不派。
//
// fail-closed：准入落点没装配（root=null）或读数认不出 ⇒ 一律不放行——闸宁可错关，不可错开。
//
// 第六百四十八批 ② 加的入口是 [retest]（主动出题/补考），并且把两条入口的考试体折成
// 一个 [examine]：本文件里 `IntakeExam.take` 只出现一次，「两套考试」这条路从结构上堵死。
//
// ## 两把尺（648 三、口径差**只记录不修**，这里是最容易被当成矛盾的那一格）
//   · `准入出题账.jsonl` 的 `conclusion` 量的是**能不能写**（5 枚探针里有写类题，纯文本对端必不过
//     ⇒ 常记 `fail`）；
//   · `准入账.jsonl` 的 `accept_read_only` 量的是**收编到哪一档**（345 硬规矩①：算错/纪律差只降权，
//     不清退）。
//   所以「出题 fail ＋准入 accept_read_only」是**同一份事实的两句实话**，不是自相矛盾：
//   要看结论去出题账，要看档位去准入账。委派侧只认档位那一格（[EnvoyAdmissionReading.allows]）。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.intake.AdmissionFlow
import com.example.zhengdao.baopu.intake.AdmissionGate
import com.example.zhengdao.baopu.intake.Clearance
import com.example.zhengdao.baopu.intake.ExamChannel
import com.example.zhengdao.baopu.intake.IntakeExam
import com.example.zhengdao.baopu.intake.SelfReport
import com.example.zhengdao.baopu.intake.TaskKind
import java.io.File

/** 准入闸的一次读数（委派入口只看 [admitted] 与 [tierFor]，账上看 [verdictWire]）。 */
internal data class EnvoyAdmissionReading(
    val agentId: String,
    /** 准入结论的稳定机器键：`accept_full`／`accept_read_only`／`reject_<kind>`／`no_root`。 */
    val verdictWire: String,
    val admitted: Boolean,
    /** 门开了才有的档（[Clearance.FULL]／[Clearance.READ_ONLY]）。 */
    val tier: Clearance?,
    /** 这一次是真考了（false＝画像快照还在、没重考）。 */
    val examined: Boolean,
    /** 一行人话（闸拒时回给调用方的就是它）。 */
    val human: String,
) {
    /** 这个档接不接这一类活（写类活要 FULL；读类活门开了就接）。 */
    fun allows(kind: TaskKind): Boolean = when {
        !admitted -> false
        kind == TaskKind.READ_ONLY -> true
        else -> tier == Clearance.FULL
    }
}

internal object EnvoyAdmission {

    /** 落点没装配的结论键（fail-closed 的那一格；与 AdmissionGate 的闭集分得清清楚楚）。 */
    const val NO_ROOT = "no_root"

    /**
     * 过一遍准入：画像在且版本对得上 ⇒ 直接给读数（不重考）；
     * 没画像或对端能力指纹变了 ⇒ 真考一次（546 的九道题，经 [channel] 送达）并落盘。
     *
     * 这是**委派路上**那道闸（自动）；用户主动要考一场走 [retest]，两条走同一个 [examine]。
     *
     * @param root 准入账落点（`<App私有>/抱朴记忆/`；null＝没装配 ⇒ 不放行）
     * @param executorVersion 对端能力指纹（变 ⇒ 旧卷作废重考）
     * @param channel 出题通道（委派入口装的是 [A2aExamChannel]；null ⇒ 空卷 ⇒ UNVERIFIABLE ⇒ 门不开）
     * @param claimed 对端卡上自报的技能 id（只存档不参与放行——365 源级钉死的口径）
     */
    fun ensure(
        root: File?,
        endpoint: String,
        executorVersion: String,
        channel: ExamChannel?,
        claimed: List<String>,
        nowMs: Long,
        log: (String) -> Unit,
    ): EnvoyAdmissionReading {
        val agentId = AgentIds.stableId(endpoint)
        if (root == null) return noRoot(agentId)
        val known = AdmissionFlow.load(root, agentId)
        if (known != null && !known.profile.needsReprobe(executorVersion)) {
            return readingOf(known, examined = false)
        }
        return examine(root, agentId, executorVersion, channel, claimed, nowMs, log, useCache = true)
    }

    /**
     * 第六百四十八批 ② · **主动补考**：用户点了「给这台出题/补考」就该真考一场，
     * 而不是从缓存里翻出旧卷念一遍（[IntakeExam.take] 的 `useCache=false` 那一格）。
     *
     * 与 [ensure] 走的是**同一个** [examine]——题面、判卷、快照、三本账一条不缺，
     * 也不另开第二套考试（派工单 648 二判据③「共用同一个考试实现（源码断言，防两套）」）。
     * 补考**不改判定**：判的还是 384 那道门，考出 FAIL 就照 FAIL 落 `accept_read_only`／`reject_*`。
     */
    fun retest(
        root: File?,
        endpoint: String,
        executorVersion: String,
        channel: ExamChannel?,
        claimed: List<String>,
        nowMs: Long,
        log: (String) -> Unit,
    ): EnvoyAdmissionReading {
        val agentId = AgentIds.stableId(endpoint)
        if (root == null) return noRoot(agentId)
        return examine(root, agentId, executorVersion, channel, claimed, nowMs, log, useCache = false)
    }

    /** 落点没装配的读数（fail-closed，两条入口共用）。 */
    private fun noRoot(agentId: String): EnvoyAdmissionReading = EnvoyAdmissionReading(
        agentId = agentId,
        verdictWire = NO_ROOT,
        admitted = false,
        tier = null,
        examined = false,
        human = "准入账没装配（落点未挂）：派活前先装准入——闸宁可错关，不可错开",
    )

    /**
     * 全仓 peer 侧**唯一**的一处出题→判卷→落盘（`IntakeExam.take` 只在这里调一次）。
     *
     * 两条入口（首调 [ensure]／主动 [retest]）的差别只有 [useCache] 一个布尔——
     * 判卷器、考卷缓存、画像快照、`准入账.jsonl`、`准入出题账.jsonl` 全是同一件。
     */
    private fun examine(
        root: File,
        agentId: String,
        executorVersion: String,
        channel: ExamChannel?,
        claimed: List<String>,
        nowMs: Long,
        log: (String) -> Unit,
        useCache: Boolean,
    ): EnvoyAdmissionReading {
        val exam = IntakeExam.take(root, agentId, executorVersion, channel, useCache = useCache)
        val record = AdmissionFlow.run(
            AdmissionFlow.Request(
                agentId = agentId,
                version = executorVersion,
                probedAtMs = nowMs,
                selfReport = SelfReport(agentId = agentId, version = executorVersion, claimedCapabilities = claimed),
                probeTraces = exam.probeTraces,
                conductTraces = exam.conductTraces,
                currentVersion = executorVersion,
            ),
        )
        AdmissionFlow.save(root, record)
        val reading = readingOf(record, examined = true)
        log(
            "抱朴使臣：「$agentId」准入${if (exam.fromCache) "（缓存命中，没重考）" else "真考（${exam.asked} 道·${exam.conclusion.cn}）"}" +
                "——${reading.verdictWire}",
        )
        return reading
    }

    /** 快照/新卷 ⇒ 读数（判定全在门的结论里，本文件不重判）。 */
    private fun readingOf(record: AdmissionFlow.Record, examined: Boolean): EnvoyAdmissionReading {
        val tier = (record.admission as? AdmissionGate.Admission.Accept)?.tier
        return EnvoyAdmissionReading(
            agentId = record.agentId,
            verdictWire = record.wire(),
            admitted = record.admission is AdmissionGate.Admission.Accept,
            tier = tier,
            examined = examined,
            human = record.line(),
        )
    }
}
