// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第三百七十八批 · 契约层接线：TaskContract（342）与 ContractSelector（332）的**生产装配**。
//
// 接线前的事实（378 派工单查的）：342 的 `TaskContract` 在生产侧**零真实引用**（只有注释提到）；
// 332 的 `ContractSelector` 只被自己的测试调用——"契约存在"与"跑任务时真用了"同时为假。
// 本文件把三处接上，**只加调用、不改判定**：
//   · 起单：引擎每轮任务（`TaskModeBoundary` 判任务态）起一张 `TaskContract`
//     （目标＝用户原话／交付物／授权范围＝产物目录／预算档＝常单）；
//   · 前置：契约的目标、范围、预算进本轮上下文（模型真读；[RunOrder.precheckLine]）；
//   · 收工：引擎文本收尾调 [closeRun]——证据由 [Contracts] 亲读真产物（不许调用方填数），
//     **PASS/FAIL/UNVERIFIABLE 真由 [ContractSelector.evaluate] 算**（332 判据表，一字未改）。
//
// 为什么声明类型固定是文档/内容产出：起单时刻引擎手里只有用户一句话（词级判据分不出四类），
// 而派工单 332 硬规矩①禁止"从证据里猜类型"——所以类型必须是**起单就写上的声明**。
// 引擎的交付物通道是工作区产物（`write_file` 一路，收尾闸验的也是它），于是常备声明就是
// 文档/内容产出：**先声明、后交卷**，收工时按这张单子对证据，不是反过来。词级细分四类
// （复制/表格/代码）是后续单的事，本单不发明新判据。
//
// 证据口径（判据④"传真数据"）：主交付物＝本轮真落盘的**文档类**产物（收尾闸同一份清单里
// 挑第一个 .md/.txt）；打勾的"凭什么"＝**收尾闸（ArtifactVerifier）的既有读数**——本文件
// 不新造任何判据，勾是收尾闸替它背书的。产物里挑不出文档 ⇒ 证据为 null 交给判据表自己判
// （硬规矩②：缺证据是 FAIL(missing_evidence)，不是跳过——"没交卷"就是不过）。
//
// 与 347 军令状收口的关系：收口（有状对账）照旧独立跑；本文件的验收是**无状的日常任务单**
// 也有的一张验收读数。两行都进运行日志，互不替代。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.core.BudgetPreset
import com.example.zhengdao.core.EvidenceKind
import com.example.zhengdao.core.ExitAction
import com.example.zhengdao.core.TaskAuthority
import com.example.zhengdao.core.TaskContract
import com.example.zhengdao.core.TaskDeliverable
import com.example.zhengdao.core.TaskExit
import com.example.zhengdao.core.TaskGoal
import com.example.zhengdao.core.ZhengdaoDirs
import java.io.File

/** 运行日志/提示词里本批各行的 grep 键（对账、回放、真机验证都按它捞）。 */
internal object RunContractWiring {

    /** 三处痕迹共用的日志头。 */
    const val LOG_HEAD = "抱朴契约"

    /** 起单的常备任务类型（口径见文件头：先声明、后交卷）。 */
    val DECLARED: TaskType = TaskType.DOC_PRODUCE

    /** 交付物项号（一张契约只有一项：本轮的产物交付）。 */
    const val DELIVERABLE_ID = "d1"

    /**
     * 常备判据（起单写上、收工打勾的那一条）。
     *
     * 为什么只有这一条：判据里写的必须是**引擎真的能验**的事——收尾闸（ArtifactVerifier）
     * 的既有读数（存在/非空/编码/JSON）正好覆盖它；"写得好不好"永远归人与判据表，
     * 引擎不替它们打勾（ArtifactVerifier 文件头的同一条边界）。
     */
    const val STANDING_CRITERION = "本轮产物全部落得住：存在、非空、可读（收尾闸读数为准）"

    /** 主交付物认这些扩展名（文档族；认不出 ⇒ 证据为 null，交给判据表判缺证据）。 */
    private val DOC_EXTS = setOf("md", "markdown", "txt")

    /**
     * 一张已起的单：契约本体 ＋ 起单时写上的声明类型。
     *
     * 类型为什么不在 [TaskContract] 里：342 只承载声明、332 只管判定，两边靠词表对齐
     * （332 文件头原文"不靠 import"）——接线层把"声明的是哪类"拿在手里，收工时交给
     * [ContractSelector.evaluate] 的 `declared` 参数。
     */
    internal data class RunOrder(val runId: String, val contract: TaskContract, val declared: TaskType) {

        /** 起单那行（进运行日志；目标截断与 [TaskGoal.MAX_TEXT_CHARS] 同口径）。 */
        fun startLine(): String {
            val b = contract.budget
            return "$LOG_HEAD：起单 ${contract.contractId}｜目标=${brief(contract.goal.text)}" +
                "｜档=${b.preset?.cn ?: "未挂档"}（${b.maxSteps}步/${b.maxWallMs / 60_000L}分/${b.maxInterruptions}次打扰）" +
                "｜授权=读${contract.authority.readRoots}写${contract.authority.writeRoots}" +
                "｜声明=${DECLARED.cn}｜收工按判据表验收"
        }

        /**
         * 前置读契约的那一行（拼进本轮上下文；模型真读得到）。
         *
         * 内容只从契约对象里取（目标/范围/预算），不另抄一份常量——"前置读过契约"
         * 指的是这一步：装配层消费的是契约本体。
         */
        fun precheckLine(): String =
            "【任务契约】目标：${brief(contract.goal.text, 60)}" +
                "｜范围：${contract.authority.writeRoots.joinToString("、")}（读写）" +
                "｜预算：${contract.budget.maxSteps}步/${contract.budget.maxWallMs / 60_000L}分钟" +
                "｜收工按契约判据表验收"
    }

    /**
     * 起单建契约（引擎在每轮任务入口调）。
     *
     * 每个字段都来自真实状态：目标＝用户原话（截到 [TaskGoal.MAX_TEXT_CHARS]）；
     * 授权范围＝工作区产物目录（[ZhengdaoDirs.PRODUCTS_DIR]，引擎对外输出口的真源）；
     * 预算＝常单档的默认值（[BudgetPreset.STANDARD.budget] 是**唯一的正路构造口**，
     * 300 步正好是引擎自己的步数上限）；出口四槽照引擎今天的真实行为（停=留半成品、
     * 超时=中止、失败=重试）。
     *
     * @return null = 形状不合规宁可不立单（不该发生；[TaskContractTest] 与本批测试都钉
     *   violations 为空——真发生了说明 342 的判据改了，接线先退场，不带病上岗）。
     */
    fun startRun(runId: String, userText: String): RunOrder? {
        val text = userText.trim()
        if (runId.isBlank() || text.isEmpty()) return null
        val contract = TaskContract(
            contractId = "c-$runId",
            revision = TaskContract.MIN_REVISION,
            goal = TaskGoal(
                text = text.take(TaskGoal.MAX_TEXT_CHARS),
                criteria = listOf(STANDING_CRITERION),
            ),
            deliverables = listOf(
                TaskDeliverable(
                    id = DELIVERABLE_ID,
                    what = brief(text, 60),
                    acceptance = listOf(STANDING_CRITERION),
                ),
            ),
            authority = TaskAuthority(
                readRoots = listOf(ZhengdaoDirs.PRODUCTS_DIR),
                writeRoots = listOf(ZhengdaoDirs.PRODUCTS_DIR),
            ),
            budget = BudgetPreset.STANDARD.budget(),
            evidence = listOf(EvidenceKind.EXISTS),
            exit = TaskExit(
                onPause = ExitAction.HOLD,
                onCancel = ExitAction.KEEP_MARKED,
                onTimeout = ExitAction.ABORT,
                onFailure = ExitAction.RETRY,
            ),
        )
        if (contract.violations().isNotEmpty()) return null
        return RunOrder(runId = runId, contract = contract, declared = DECLARED)
    }

    /**
     * 收工按契约判（引擎文本收尾调；**右相验收入口**——三态真由 [ContractSelector] 算）。
     *
     * @param workspace 工作区根（引擎的 spillRoot；产物相对路径按它解析）
     * @param touched 本轮真写成功的产物相对路径（收尾闸同一份清单，[com.example.zhengdao.baopu.verify.VerifyTargets] 记账）
     * @param gateFindings 收尾闸的读数（[ArtifactVerifier.check] 的返回；打勾的"凭什么"）
     */
    fun closeRun(
        order: RunOrder,
        workspace: File,
        touched: List<String>,
        gateFindings: List<Finding>,
    ): ContractReport {
        // 主交付物按 404 口径解析（resolveTarget：与写入侧 287 对齐）——工作区外的
        // 合法落点（绝对路径写全盘）也算数，不再因为「不在工作区」被当没交卷
        // （401 验机 M7：产物全在盘上却判 FAIL 缺证据的误判链第二环）。
        val primary = touched.asSequence()
            .mapNotNull { ArtifactVerifier.resolveTarget(workspace, it) }
            .firstOrNull { it.extension.lowercase() in DOC_EXTS }
        if (primary == null) {
            // 挑不出文档类主交付物：不硬凑证据——判据表自己判"缺证据"（硬规矩②）。
            return ContractSelector.evaluate(order.declared, evidence = null)
        }
        val evidence = Contracts.readDoc(
            file = primary,
            declaredItems = listOf(STANDING_CRITERION),
            ticks = listOf(tick(gateFindings)),
        )
        return ContractSelector.evaluate(order.declared, evidence)
    }

    /**
     * 判据打勾（判据表吃的输入；勾的依据是收尾闸的**既有读数**，本文件不新造判据）。
     *
     * 收尾闸 0 项未过 ＝ 勾成立；有未过项 ＝ 如实不勾（note 里带前几项原文，一条都不吞）。
     */
    private fun tick(gateFindings: List<Finding>): Evidence.Tick {
        if (gateFindings.isEmpty()) {
            return Evidence.Tick(item = STANDING_CRITERION, done = true, note = "收尾闸读数：本轮产物 0 项未过")
        }
        return Evidence.Tick(
            item = STANDING_CRITERION,
            done = false,
            note = "收尾闸读数：${gateFindings.size} 项未过——${ArtifactVerifier.summarize(gateFindings)}",
        )
    }

    /** 收工那一行（进运行日志；单行，含三态与未过/缺证据的判据号）。 */
    fun reportLine(report: ContractReport): String {
        val head = "$LOG_HEAD：收工验收 ${report.status.cn}（${report.reason.cn}）"
        if (report.checks.isEmpty()) return "$head｜${report.note}"
        val bad = (report.missing.map { "${it.wire}(缺)" } + report.failed.map { "${it.wire}(败)" })
        val detail = if (bad.isEmpty()) "判据 ${report.checks.size} 条全过" else "未过：${bad.joinToString("、")}"
        return "$head｜$detail"
    }

    /** 收工没过 ⇒ 给用户的一句实话（进 notice；PASS 返回 null——过了不打扰）。 */
    fun noticeLine(report: ContractReport): String? =
        if (report.status == Status.PASS) {
            null
        } else {
            "【任务契约】验收 ${report.status.cn}：${report.note}"
        }

    private fun brief(raw: String, max: Int = 24): String {
        val one = raw.replace('\n', ' ').trim()
        return if (one.length <= max) one else one.take(max) + "…"
    }
}
