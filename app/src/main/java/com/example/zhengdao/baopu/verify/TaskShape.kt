// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十二批 · 任务形态与证据要求分档（修 G-673-3：只读任务收工被判「缺证据」）。
//
// 病灶（673 真机轮三，`run_log.state` 原文）：
//   `抱朴契约：收工验收 FAIL（不过）（缺证据）｜未过：exists(缺)、structure(缺)、user_checklist(缺)`
// 出现两次，两次都是 `list_dir` 这类**只读**目标。
//
// 为什么会这样（不是判据写错，是**拿写活的尺子量读活**）：
//   378 的接线层起单时手里只有用户一句话（词级判据分不出四类），于是**每一单**都声明成
//   「文档/内容产出」；收工要找一份 .md/.txt 当主交付物。只读任务根本不产物 ⇒ 挑不出文档
//   ⇒ 证据为 null ⇒ 332 判据表照硬规矩②判「缺证据」。判据本身没错，错在尺子。
//
// 本文件只做两件事（派工单①②）：
//   ① 认出「这单算哪类任务」——从**真跑过的工具行**里认，不从用户话里猜、也不从证据里猜；
//   ② 把每一条判据的要求分成三档：必须（REQUIRED）／可选（OPTIONAL）／不适用（NOT_APPLICABLE）。
//
// 三类 × 三档（闭集；改集合＝改口径，`ReadOnlyContractTest` 钉死）：
//   只读  产物三条（存在/结构/检查项）＝**不适用**；工具行一条＝**必须**
//   写入  产物三条＝**必须**；工具行＝**可选**（产物存在本身就是「真做过」的更强证据）
//   混合  **按最高要求**（派工单原话）：与写入同档，一格不放宽
//
// 形态的三个来源（[RoundTrace]，引擎在工具**成功**分支累加，见 `RunEngine.noteRoundToolRow`）：
//   readRows  —— 只读工具（注册表 `ToolSpec.readOnly=true`）跑成了几条；
//   writeRows —— 写类工具跑成了几条（不在注册表里的工具按写处理，与 531 同一条兜底）；
//   summaries —— 这些工具行的结果摘要（「结果摘要」就是这一档证据的实体）。
// 一条工具行都没有 ⇒ 形态是 null（未定）：没做过的事，不替它写「属于只读」。
//
// 三条边界（不许越界）：
//  ① 判据、取数、三态仍全在 332（`Contracts`／`ContractSelector`）：本文件只**改档**、
//     不**改判**——只读口径下工具行拿不出 ⇒ 仍是 FAIL(missing_evidence)；
//  ② 混合绝不出现「只过读的那几条就算过」：`relaxed` 只有纯只读为真；
//  ③ 只读口径不放宽「是否真做过」：工具行必须带结果摘要，空账不算（派工单④）。
package com.example.zhengdao.baopu.verify

/**
 * 任务形态（闭集三档）：一单活**相对产物**长什么样。
 *
 * 注意它与 [TaskType] 是两个正交的轴：`TaskType` 说的是「这份活的产物是哪一族」
 * （四类，332 的判据表按它分列）；`TaskShape` 说的是「这份活到底产不产物」。
 * 只读任务在 `TaskType` 上仍是「文档/内容产出」（起单只有一句话，改不了），
 * 但在 `TaskShape` 上是「只读」——判据要求由后者收窄。
 */
internal enum class TaskShape(val wire: String, val cn: String) {
    READ_ONLY("read_only", "只读"),
    WRITE("write", "写入"),
    MIXED("mixed", "混合"),
    ;

    /** 只有纯只读吃放宽口径；混合按最高要求（派工单②）。 */
    val relaxed: Boolean get() = this == READ_ONLY

    companion object {
        fun ofWire(raw: String): TaskShape? = entries.firstOrNull { it.wire == raw.trim().lowercase() }
    }
}

/** 证据要求三档（闭集）。`OPTIONAL` 的落点是「只记不判」——有就记进账行摘要，没有也不判不过。 */
internal enum class CheckNeed(val cn: String) {
    REQUIRED("必须"),
    OPTIONAL("可选"),
    NOT_APPLICABLE("不适用"),
}

/**
 * 本轮真跑成的工具行读数（引擎累加，不是猜的）。
 *
 * @param readRows  只读工具成功条数
 * @param writeRows 写类工具成功条数
 * @param summaries 这些工具行的结果摘要（首几行的短摘要；空串不算摘要）
 */
internal data class RoundTrace(
    val readRows: Int = 0,
    val writeRows: Int = 0,
    val summaries: List<String> = emptyList(),
) {
    /** ok=1 的工具行总数（失败/被拒的调用不进这一格，与 run_log 的 ok 列同口径）。 */
    val okRows: Int get() = readRows + writeRows

    /** 形态：读写都有 ⇒ 混合；只有写 ⇒ 写入；只有读 ⇒ 只读；一条都没有 ⇒ null（未定）。 */
    val shape: TaskShape?
        get() = when {
            writeRows > 0 && readRows > 0 -> TaskShape.MIXED
            writeRows > 0 -> TaskShape.WRITE
            readRows > 0 -> TaskShape.READ_ONLY
            else -> null
        }

    /** 只读口径的证据实体：至少一条 ok=1 的工具行，且它带结果摘要。 */
    val hasReadEvidence: Boolean
        get() = okRows > 0 && summaries.any { it.isNotBlank() }
}

/** 形态判定与分档表（纯函数，零 IO；`ReadOnlyContractTest` 与 `Contracts` 都按它取档）。 */
internal object ShapeRules {

    /** 本轮工具行摘要最多留几条（[RoundTrace.summaries] 只要够证明"有结果可对"，账行要短）。 */
    const val MAX_SUMMARIES = 8

    /**
     * 形态判定：`touched`（本轮真写成功的产物路径，收尾闸同一份清单）也算「写过」。
     *
     * 为什么两条都要看：`writeRows` 是工具行计数，`touched` 是落盘读数——同一件事的两侧。
     * 生产上两者一致；测试里只给 `touched`、不给 trace 的旧调用点也据此判成「写入」，
     * 于是**老路径一格不变**（不回归）。
     */
    fun shapeOf(trace: RoundTrace, touched: List<String>): TaskShape? {
        if (touched.isEmpty() || trace.writeRows > 0) return trace.shape
        return trace.copy(writeRows = 1).shape
    }

    /**
     * 每一条判据在本形态下的要求（三档）。顺序＝[Contracts.checksOf] 的顺序，末尾补工具行那一条。
     *
     * 只给「文档/内容产出」开只读口子：其余三类的判据（路径集合／编译测试／行列数）本来就
     * 只在真产物上才有意义，这类任务一旦真只读，说明**根本没干**，按必须判（本批不乱放口子）。
     */
    fun needsOf(type: TaskType, shape: TaskShape?): Map<CheckId, CheckNeed> {
        val product = if (shape?.relaxed == true && type == TaskType.DOC_PRODUCE) {
            CheckNeed.NOT_APPLICABLE
        } else {
            CheckNeed.REQUIRED
        }
        val rows = if (shape?.relaxed == true && type == TaskType.DOC_PRODUCE) {
            CheckNeed.REQUIRED
        } else {
            CheckNeed.OPTIONAL
        }
        return Contracts.checksOf(type).associateWith { product } + (CheckId.TOOL_ROWS to rows)
    }

    /** 这一档里要**参与判定**的判据（`必须`），报告只按它们算三态。 */
    fun requiredOf(type: TaskType, shape: TaskShape?): List<CheckId> =
        needsOf(type, shape).filterValues { it == CheckNeed.REQUIRED }.keys.toList()

    /** 这一档里明写「不适用」的判据（报告里照样摊开，省得人问「怎么没验」）。 */
    fun notApplicableOf(type: TaskType, shape: TaskShape?): List<CheckId> =
        needsOf(type, shape).filterValues { it == CheckNeed.NOT_APPLICABLE }.keys.toList()
}
