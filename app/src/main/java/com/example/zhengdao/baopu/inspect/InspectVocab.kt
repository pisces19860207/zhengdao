// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十九批 §7：纠察（Inspector）是三权里缺的第三权，它的输出定名 **「勘验」**。
// 三个词各管一层，谁也不许冒领别层的性质：
//   推演 = **意见**（推测，必标"推测"）；
//   审计 = **自报的账**（谁做了什么）；
//   勘验 = **独立复核后的事实认定**（准/不准、有/没有）。
//
// 本文件是这一层唯一的词表：检查项、认定态、阈值、一行报告的形制。
// 零 Android、零 IO、零时钟——所有判据都能拿临时目录与手搓行直测。
//
// 红线（派工单 §6 与用户 2026-10-26 定稿）：
// - **只查不改**：纠察没有写权，改数据/改技能不归它；
// - **输入只能是日志与账本**：模型的自述不是证据（§7.3 第三条）；
// - **明细不进模型上下文**：给模型的永远只有一句聚合；
// - **不阻断执行**（hardDeny 是 219 的活，影子不符是 I6 自己的闸）。
package com.example.zhengdao.baopu.inspect

/** 六项勘验（闭集：`wire` 是巡按台账的稳定键，改字面量＝老账本读不出来）。 */
internal enum class InspectCheck(val wire: String, val cn: String) {

    /** I1 审计漏行：有动作痕迹（工具执行成功）但 208 没有对应记录。 */
    AUDIT_GAP("audit_gap", "审计漏行"),

    /** I2 数字无出处：回答里的具体数字在本轮工具回灌里找不到（复用 227 `AnswerGate`）。 */
    NUMBER_GROUNDING("number_grounding", "数字无出处"),

    /** I3 契约空洞：`appliesWhen`/`success` 为空或恒真（`success` 写成"完成"这类不可观察）。 */
    CONTRACT_HOLLOW("contract_hollow", "契约空洞"),

    /** I4 调和器偏袒：免问回滚率／确认密度／虚报率／秒批率四率越线。 */
    MEDIATOR_BIAS("mediator_bias", "调和器偏袒"),

    /** I5 反复失败未归档：同一技能近 7 天失败 ≥3 次，却既没进待学完也没退役。 */
    UNARCHIVED_FAILURE("unarchived_failure", "反复失败未归档"),

    /** I6 影子执行不符：影子里跑出来的实际影响 ≠ 推演所称（或回滚点不可用）。 */
    SHADOW_MISMATCH("shadow_mismatch", "影子执行不符"),
    ;

    companion object {
        /** 线值 ⇒ 检查项；认不出 ⇒ null（自造检查项一律不渲染，也不静默当别的项）。 */
        fun ofWire(wire: String): InspectCheck? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一条勘验认定（闭集三态）。
 *
 * 为什么必须有 [NO_DATA]：派工单点名的四率里，虚报率与秒批率在现有仓库**没有**持久口
 * （批准时刻的"人什么时候点的"根本没落库）。没有这一态，"查不了"就会被写成"查过没问题"——
 * 那正是本批要纠的弊，自己不能先犯。
 */
internal enum class InspectVerdict(val wire: String) {

    /** 命中：确有此事（事实认定，不是意见）。 */
    HIT("hit"),

    /** 清白：这一项查过了，样本够，没查出问题。 */
    CLEAR("clear"),

    /** 无从勘验：口径缺持久口或样本不够 ⇒ **永不算通过**，也不进通过率分母。 */
    NO_DATA("no_data"),
}

/** 一条勘验结论（= 巡按栏明细的一行）。 */
internal data class InspectFinding(
    val check: InspectCheck,
    val verdict: InspectVerdict,
    /** 给人看的那一行（**构造时即钳到 [InspectLimits.MAX_LINE_CHARS]**，不是显示期才截）。 */
    val oneLine: String,
    /** 一句建议（§2.3：需要改的出建议，由人或模型自己改）；负例⑤"只查不建"钉这格非空。 */
    val suggestion: String,
    /** 明细（时间、工具、路径、技能名、证据 id）：**只进巡按栏，永不进模型上下文**。 */
    val evidence: List<String> = emptyList(),
    /** 命中几条（阈值判据按它走）。 */
    val count: Int = 0,
    val atMs: Long = 0L,
) {
    /** 这一行是不是短到能进巡按栏（钳过就不可能超；留着给门禁当断言靶）。 */
    val fitsLine: Boolean get() = oneLine.length <= InspectLimits.MAX_LINE_CHARS
}

/** 阈值与形制上限（派工单 §1 那张表的数字全部住在这里，别处不许再抄一份）。 */
internal object InspectLimits {

    /** §2.4「一行结论（≤40 字）」——也是模型侧那一句的上限。 */
    const val MAX_LINE_CHARS = 40

    /** §3「报告形态（三行，人能 3 秒看完）」＋负例②「超过三行即红」。 */
    const val MAX_REPORT_LINES = 3

    // ── 抽检规模（§2.2：抽检而非全检；全检会拖垮手机，也没必要）──────────────

    /** I2 每次抽几条回答。 */
    const val ANSWER_SAMPLE = 10

    /** I3 每次抽几条技能。 */
    const val SKILL_SAMPLE = 5

    /** I4 周报窗口（天）。 */
    const val WEEK_WINDOW_DAYS = 7

    /**
     * §10④（2026-10-27）：**两条率**（虚报率、秒批率——报告 §10 第 6 问点名的「没有持久口」那两条）
     * 的窗口——**滚动 30 天**、按"动作实例"计数（不按会话；这两率的分母本来就是逐实例的：
     * 回答轮次／摆卡次数）。**只喂这两条**：I2 抽检、I5 的 [FAILURE_WINDOW_DAYS]=7、
     * I4 另两率（免问回滚／确认密度）的口径都不动。
     * 分母 <10 记 unknown 的那条线就是 [MIN_RATIO_SAMPLE] 之外的 [com.example.zhengdao.baopu.inspect.MediatorBiasCheck.MIN_RATE_SAMPLE]=10。
     */
    const val RATE_WINDOW_DAYS = 30

    /** I5「近 7 天」窗口（天）——与 I4 同口径但**各写一个常量**：两处窗口打架过一次就再合不上。 */
    const val FAILURE_WINDOW_DAYS = 7

    /** I5 触发阈值：同一技能失败这么多次还没收拾。 */
    const val FAILURE_MIN_HITS = 3

    /** I1 触发阈值：漏 1 条就报（派工单原文"漏行 ≥1 就报"）。 */
    const val GAP_MIN_HITS = 1

    /** I2/I3 触发阈值：抽到的样本里命中 1 条就报。 */
    const val SAMPLE_MIN_HITS = 1

    /**
     * I1 配对容差（毫秒）。
     *
     * `auto_act` 写在 `GuardPipeline.finish` 里、`run_log` 的 Acting 行紧随其后由
     * `persistToolExchange` 落，两者在同一次工具交换里 ⇒ 正常相差不到一秒。给 90 秒
     * 是为了扛写事务排队（`zd-baopu-db` 单线程写队列），不是为了"宽容"。
     */
    const val GAP_TOLERANCE_MS = 90_000L

    // ── I4 四率阈值（派工单 §1 那一行的四个数）──────────────────────────────

    /** 免问回滚率上限（%）：>5% 即报。与 236 `DelegationMetrics.ROLLBACK_RATE_LIMIT_PCT` 同数。 */
    const val SILENT_ROLLBACK_PCT = 5.0

    /** 确认密度红线（次/任务）：>5 即报。 */
    const val CONFIRM_DENSITY_LINE = 5.0

    /**
     * 为什么是 5 而不是 236 的 2.0：`CONFIRM_DENSITY_TARGET=2.0` 是**目标**（人该舒服到哪个数），
     * 本批查的是**偏袒**（调和器已经离谱到哪个数）。两个数各管一件事，合成一个就两头都不准。
     * 建议句里会把目标值一起报出来，不藏。
     */
    const val CONFIRM_DENSITY_TARGET = 2.0

    /** 虚报率上限（%）：>20% 即报。 */
    const val OVERCLAIM_PCT = 20.0

    /** 秒批率上限（%）：>60% 即报（秒批＝御批退化成了橡皮图章）。 */
    const val INSTANT_APPROVAL_PCT = 60.0

    /** 秒批判定窗口（毫秒）：从摆卡到下一步落账小于这个数算"秒批"。 */
    const val INSTANT_MS = 5_000L

    /**
     * 比率类判据的最小样本：少于这个数**不出 HIT**，只出 [InspectVerdict.NO_DATA]。
     * 与 236 `DelegationMetrics.MIN_SAMPLE` 同数（3 次里 1 次回滚＝33%，那是噪声）。
     */
    const val MIN_RATIO_SAMPLE = 20

    /** §7.2 勘验通过率窗口（天）。 */
    const val PASS_RATE_WINDOW_DAYS = 30

    /** 一次巡按最多读多少行账本（防把整本台账搬进内存；照 208 的 `READ_LIMIT` 房型）。 */
    const val READ_LIMIT = 2_000

    /** 巡按栏一屏渲染几行明细（其余点「加载更多」；照 237 `FailureAuditLog.PAGE`）。 */
    const val PAGE = 50
}

/** 读盘与渲染的分层上限（明细条数：巡按栏里一条 finding 最多摊开几行证据）。 */
internal object InspectDetailLimits {
    const val MAX_EVIDENCE_PER_FINDING = 20
}
