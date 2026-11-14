// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十五批 · 结论-证据对账的**生产接线**（627 判据层的装配层）。
//
// 前提更正（627 判据⑤）：只读＝**对被检查对象只读**（不改被检查的账／文件／git 状态／
// 测试 XML）；**自身产出必须可写**（对账结果账行、建议页签行、起居注同形 ACTION 行）。
// 所以本文件分两半：读数一律只读，落账只落自己那本 `对账结果.jsonl`。
//
// 本文件负责五处接线里的 ②③：
// ② 读数接口 [ClaimEvidenceReadings]：账行查询／commit 摘要／测试 XML 四个数
//    （suites·cases·failures·mtimeMs）。金吾卫 [ClaimEvidenceAudit] 只调用接口，
//    自己不连库、不跑 git、不碰盘——跑命令与读盘都是装配层的事。
// ③ 落行判据 [claimEvidenceRowsOf]：缺口 ≥1 且左相允许 ⇒ 落 1 行；0 缺口 ⇒ 0 行。
//
// 两条口径必须分开（本单最容易搞混的一处）：
// · 「跑不跑」＝夜间窗口 [claimEvidenceShouldRun]，复用 239/432 的唯一钟点判据；
// · 「落不落行」＝缺口数 × 左相允许，**不看钟点**。夜里悄悄对账，天亮人打开建议页签看行。
//   627 的 [LeftAdvice] 管的是「要不要即时打扰」，与本单「异步进页签」不是一件事。
package com.example.zhengdao.baopu.inspect

import android.content.Context
import com.example.zhengdao.BuildConfig
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.safety.DiaryCodec
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import com.example.zhengdao.baopu.safety.SupervisionDiary
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * 635 对账接线：装配层读数接口与对账结果行，by-design 声明逐条登记。
 *
 * 635 装配层的**常量位**（by-design：无 install/record 入口、无方法）。
 * 真正的接线入口是顶层函数 [runClaimEvidenceAudit]／[claimEvidenceRound]——它们不是
 * 装配类，不进接线清单；本件只放一处字面常量，供同文件的落行判据与源码门禁取用。
 */
internal object ClaimEvidenceWiring {
    /** 「建议」页签第三类行的类型签（判据③；字面值由源码门禁钉住）。 */
    const val AUDIT_TYPE_LABEL = "对账"
}

/** 对账结果账（自身产出；独立账本，不碰被检查的账）。 */
internal const val AUDIT_LEDGER_FILE = "对账结果.jsonl"

/** 一轮对账最多回看多少行账原文（不扫全账，与 239 的抽检同一口径）。 */
internal const val AUDIT_REPORT_LINES = 40

/** 本单账行的依据格（读回时按它认领自己的行，不误吃起居注其它 ACTION）。 */
internal const val AUDIT_DIARY_WHY = "635 夜间对账"

/**
 * 左相对「异步进建议页签」的既成准许。
 *
 * 进页签**不即时弹卡、不占调解器问卡预算**，所以与 `MediatorWiring.consultAsk`
 * 判的「要不要现在打扰人」不是同一件事；后者归左相那套计数，本单不碰、不改它。
 * 真要即时打扰仍由左相另判——本常量只管「这行能不能落进页签」。
 */
internal const val AUDIT_LEFT_ALLOWS = true

/**
 * 装配层读数接口（判据②）：**全是读**，一格写口都没有。
 *
 * 三格读数对应 627 的 [ClaimEvidenceEnv]：账行查询 → [ledgerLines]、commit 摘要 →
 * [commitShortIds]、测试 XML 四个数 → [TestXmlReading]（suites/cases/failures/mtimeMs）。
 * 其余三类证据（git show／merge-base／真机目录）在 App 运行时取不到，装配层如实给空，
 * 由机检记缺口——**不补、不冒充读过**（627 纪律第 4 条）。
 */
internal interface ClaimEvidenceReadings {
    /** 本轮起点：晚于它的 XML mtime 才算本轮证据。 */
    val roundStartMs: Long

    /** 此刻钟点（0–23）：由调用方注入，本层不读时钟（时间注入夹具）。 */
    val hourOfDay: Int

    /** 账行原文（208 决策账 ＋ 起居注），只读查询。 */
    fun ledgerLines(): List<String>

    /** 本轮 commit 摘要（短号＋摘要一行）。 */
    fun commitShortIds(): List<String>

    /** 测试 XML 四个数；本轮没有 ⇒ null（不许拿上一轮充数）。 */
    fun testXml(): TestXmlReading?
}

/**
 * 自身产出的一行（判据③）：带**稳定 id** 与**时间**，是「建议」页签第三类行的数据源。
 *
 * 一轮至多一行（判据①「称 3 件／账上 2 条 ⇒ 1 行」）：行里汇总所有缺口文案，逐条指缺什么。
 */
internal data class AuditRow(
    val id: String,
    val atMs: Long,
    val typeLabel: String,
    val title: String,
    val subtitle: String,
    val gapCount: Int,
)

/** 把读数接口适配到 627 的只读注入口；金吾卫只认 [ClaimEvidenceEnv]，不认识装配层。 */
private class EnvOf(private val r: ClaimEvidenceReadings) : ClaimEvidenceEnv {
    override val roundStartMs: Long get() = r.roundStartMs
    override val hourOfDay: Int get() = r.hourOfDay
    override fun ledgerLines(): List<String> = r.ledgerLines()
    override fun commitShortIds(): List<String> = r.commitShortIds()
    override fun gitShowPaths(): Set<String> = emptySet()
    override fun mergedIntoMain(branch: String): Boolean = false
    override fun testXml(): TestXmlReading? = r.testXml()
    override fun deviceEvidenceFiles(): List<String> = emptyList()
}

/** ①「跑不跑」：夜间窗口，复用 [PatrolScheduler.inNightWindow]，不另写一套钟点（判据①⑤）。 */
internal fun claimEvidenceShouldRun(hourOfDay: Int): Boolean =
    PatrolScheduler.inNightWindow(hourOfDay)

/** 从 nowMs 换算钟点（本地时区；与 [PatrolScheduler.dayBucket] 同一套日历口径）。 */
internal fun hourOfDayOf(nowMs: Long): Int =
    Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).hour

/**
 * ③ 落行判据（纯函数，JVM 直测）：真调 [ClaimEvidenceAudit.audit]——判据⑤ 的生产调用点。
 *
 * 缺口 ≥1 且左相允许 ⇒ 1 行；否则 0 行。**不看钟点**：钟点只在 [runClaimEvidenceAudit]
 * 里管「这趟跑不跑」，不参与「这轮落不落行」。
 */
internal fun claimEvidenceRowsOf(
    reportText: String,
    readings: ClaimEvidenceReadings,
    leftAllowsDisturb: Boolean,
    atMs: Long,
): List<AuditRow> {
    val out = ClaimEvidenceAudit.audit(reportText, EnvOf(readings), leftAllowsDisturb)
    if (!leftAllowsDisturb || out.gaps.isEmpty()) return emptyList()
    return listOf(
        AuditRow(
            id = "audit:$atMs:${out.gaps.size}",
            atMs = atMs,
            typeLabel = ClaimEvidenceWiring.AUDIT_TYPE_LABEL,
            title = "对账发现 ${out.gaps.size} 条证据缺口",
            subtitle = out.chancellorInput.joinToString("；"),
            gapCount = out.gaps.size,
        ),
    )
}

/** 本单账本的落点（与被检查的 208 账／起居注分开，互不改写）。 */
internal fun auditLedgerFile(root: File): File = File(root, AUDIT_LEDGER_FILE)

/**
 * 被检查对象之一：208 决策账的**原文**最近若干行，只读当对账输入（不解析、不回写）。
 */
internal fun claimReportText(root: File): String = runCatching {
    val f = DecisionLedger.fileIn(root)
    if (f.isFile) f.readLines().takeLast(AUDIT_REPORT_LINES).joinToString("\n") else ""
}.getOrDefault("")

/**
 * 自身产出可写：一行＝一条起居注**同形**的 ACTION（复用 [DiaryCodec]／[DiaryKind.ACTION]）。
 *
 * 为什么不直接给 [SupervisionDiary] 追加：504／469 判据①「每次自动动作一行」的唯一写口在
 * `GuardPipeline.finish`，多开一个写口行数就对不上 ⇒ 本单的 ACTION 落在自己的账本里，
 * 形状与起居注一致、语义独立，被检查对象的账表行数一行不动。
 */
internal fun appendAuditRow(root: File, row: AuditRow): Boolean = runCatching {
    val event = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = row.atMs,
        why = "$AUDIT_DIARY_WHY · ${row.id}",
        what = row.title,
        result = row.subtitle,
    )
    auditLedgerFile(root).appendText(DiaryCodec.encode(event) + "\n")
    true
}.getOrDefault(false)

/** 读回本单账（坏行跳过；页签取最新一轮，不显历史全量）。 */
internal fun readAuditRows(root: File): List<DiaryEvent> = runCatching {
    val f = auditLedgerFile(root)
    if (f.isFile) {
        f.readLines().mapNotNull(DiaryCodec::parse).filter { it.why.startsWith(AUDIT_DIARY_WHY) }
    } else {
        emptyList()
    }
}.getOrDefault(emptyList())

/**
 * 装配层读数实现：**只读**（账本原文／构建信息／注入的 XML 读数），零写口。
 *
 * XML 四个数由调用方注入；App 运行时没有 CI 的测试 XML ⇒ 生产传 null，机检如实记
 * 「没找到本轮测试 XML」缺口，不拿上一轮充数（627 判据③）。
 */
private class ProdReadings(
    private val ctx: Context,
    private val root: File,
    override val roundStartMs: Long,
    override val hourOfDay: Int,
    private val xml: TestXmlReading?,
) : ClaimEvidenceReadings {
    override fun ledgerLines(): List<String> = buildList {
        runCatching { DecisionLedger.fileIn(root) }.getOrNull()?.let { f ->
            if (f.isFile) addAll(f.readLines())
        }
        addAll(SupervisionDiary.readAll(ctx).map(DiaryEvent::line))
    }

    override fun commitShortIds(): List<String> = listOf(BuildConfig.GIT_SHA)

    override fun testXml(): TestXmlReading? = xml
}

/**
 * 一轮对账的完整链路（**不依赖 Android**，JVM 可真跑，判据①⑤的行为夹具测它）。
 *
 * 窗口由 [ClaimEvidenceReadings.hourOfDay] 注入（时间注入夹具）；窗口外直接返回空，
 * **不落账、不 publish**（判据⑤：什么都不做，连上一轮结果都不动）。
 * 窗口内：真调 627 机检 → 逐行交给 [sink]（生产是落账，测试是内存计数）→ publish 给页签。
 *
 * @param sink 自身产出的写口；窗口外或 0 缺口时一次都不调
 */
internal fun claimEvidenceRound(
    reportText: String,
    readings: ClaimEvidenceReadings,
    leftAllowsDisturb: Boolean,
    atMs: Long,
    sink: (AuditRow) -> Unit,
): List<AuditRow> {
    if (!claimEvidenceShouldRun(readings.hourOfDay)) return emptyList()
    val rows = claimEvidenceRowsOf(reportText, readings, leftAllowsDisturb, atMs)
    rows.forEach(sink)
    // 页签只认本进程最近一轮：0 缺口 ⇒ publish(空表) ⇒ 第三类行 0 行（判据③）。
    ClaimEvidenceRowStore.publish(rows)
    return rows
}

/**
 * ① 暗卫排期趟的入口（由 [NocturnalAuto] 在 432 调度 ＋ 479 两级刹车之后调用）。
 *
 * 只做装配：把真实读数、账本落点接进 [claimEvidenceRound]；判据一行不落在这里。
 * 时刻由本趟 nowMs 注入，钟点从它换算（时间注入夹具的生产侧）。
 *
 * @param nowMs 本轮时刻，同时当 [ClaimEvidenceReadings.roundStartMs]
 */
internal fun runClaimEvidenceAudit(
    ctx: Context,
    root: File,
    nowMs: Long,
    leftAllowsDisturb: Boolean,
): List<AuditRow> {
    val hour = hourOfDayOf(nowMs)
    val readings = ProdReadings(ctx, root, nowMs, hour, null)
    return claimEvidenceRound(claimReportText(root), readings, leftAllowsDisturb, nowMs) {
        appendAuditRow(root, it)
    }
}
