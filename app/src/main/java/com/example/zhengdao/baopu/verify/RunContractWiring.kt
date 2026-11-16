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
//
// 第三百一十七批改了一格（其余一字未动）：**收工结论不再往 notice 贴**。
// 原来那一句 `noticeLine` = 「【任务契约】验收 UNVERIFIABLE（判不了）：…」把三态原词
// 直接送上了屏，正是 317 派工单负例①点名的形状（内部词不许出现在用户可见文案里）。
// 现在结论只有一个出口：引擎发布 `VerifyNarrative.fromContract` 那一行话（键名＋参数，
// 中文在 `strings_baopu_verify.xml`），`reportLine` 那行原样留在**运行日志**（日志不是屏）。
// 判据、取数、三态口径一个字没改——改的是"说到哪儿去"，不是"怎么判"。
//
// 第六百八十二批（G-673-3）改了**选尺子**这一步，判定仍是 332 的：
// 起单时只有一句话、分不出四类，于是每一单都声明成「文档/内容产出」；可收工时**只读任务
// 根本不产物**，按产物的三条判据收卷必然判「缺证据」（673 真机上 2 次，都是 list_dir 这类）。
// 现在收工先认**任务形态**（`TaskShape`：只读／写入／混合，从本轮真跑成的工具行认，
// 不看用户话、不看证据），认成只读就只判「工具行＋结果摘要」那一条，产物三条摊开标
// 「不适用」；写入/混合一格不放宽（混合按最高要求）。形态与工具行条数都写进收工行，
// 账与面板上看得见「本任务属于只读」——这不是"过了"，是"换了尺子才过的"，两件事得说清。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.preread.PreReadWiring
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
     * 改约在目标里的分隔符（第三百六十批）。
     *
     * 为什么把改约**接在原话后面**而不是替换它：契约的目标是"这次要干成什么"，
     * 用户中途改要求是**在原要求上改**，不是另起一件事——原话丢了就答不出
     * "当初约的是什么"（与军令状「旧版一律不删」同一条纪律）。
     */
    const val AMEND_MARK = "｜改约："

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

        /**
         * 改单那行（进运行日志，第三百六十批）：版本从哪到哪、新目标是什么。
         *
         * 为什么把"授权/预算一格未动"写进日志：改约最容易夹带的就是这两样，
         * 日志上留一句，事后抽检一眼能对上（判据③）。
         */
        fun amendLine(fromRevision: Int): String =
            "$LOG_HEAD：改单 ${contract.contractId}｜第 $fromRevision 版 → 第 ${contract.revision} 版" +
                "｜新目标=${brief(contract.goal.text, 40)}｜按新要求重过闸（授权/预算一格未动）"

        /**
         * 改单的回灌（进 update_plan 的 ack）：模型必须知道**旧单的批准不管用了**。
         *
         * 只说"已改"不够——模型会把"这次没弹卡"读成"用户已经授权"（242 负例②的同一条）。
         */
        fun reissueAck(fromRevision: Int): String =
            "【任务契约重开】用户中途改了要求 ⇒ 本轮契约按新要求重开" +
                "（第 $fromRevision 版 → 第 ${contract.revision} 版）：旧版上的批准与证据不再覆盖新要求，" +
                "之后按新单推进；授权范围与预算一格没放宽，该问的确认卡照旧会问。"
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
     * 改单（第三百六十批）：用户中途改要求 ⇒ **用新要求重开一张单**（revision +1）。
     *
     * 为什么必须重开而不是改字：契约的每一格都是**按上一版要求**算出来的（目标、
     * 交付物、验收判据、预算、授权），改要求等于换了一件要干的事——旧版上的批准与
     * 证据（回执按 [TaskContract.sha256] 绑版本）不能顺延到新要求上。重开一次，
     * 新要求就**重新过一遍单子的形状闸**（[TaskContract.violations]：目标/判据/授权/
     * 预算/证据/出口），旧批准自然作废。
     *
     * 三条边界（判据③「不给 amend 开新后门」的落点）：
     * 1. **只动目标与交付物那一句**——授权、预算、证据、出口**逐字沿用**，改约不许
     *    顺手放宽一格（放宽要另走授权，不在这条路上）；
     * 2. 新单**自己也得合规**（violations 空）才交出去，否则 null：宁可不改，不带病上岗；
     * 3. 空改约、或这条改约已经在单子上 ⇒ null（不空转、不白涨版本）。
     *
     * @return null = 不改单（没改约 / 改约已在单上 / 新单形状不合规）
     */
    fun amendRun(order: RunOrder, amendedRequirement: String): RunOrder? {
        val amended = amendedRequirement.trim()
        if (amended.isEmpty()) return null
        if (order.contract.goal.text.contains(amended)) return null
        val goal = composedGoal(order.contract.goal.text, amended)
        val next = order.contract.copy(
            revision = order.contract.revision + 1,
            goal = order.contract.goal.copy(text = goal),
            deliverables = order.contract.deliverables.map { it.copy(what = brief(goal, 60)) },
        )
        if (next.violations().isNotEmpty()) return null
        return order.copy(contract = next)
    }

    /**
     * 改后的目标串：原话在前、改约在后，超 [TaskGoal.MAX_TEXT_CHARS] 截断。
     *
     * 截断时留的是**原话的头**：原话是这件事的本体，改约的全文另有去处（变更账
     * `计划军令状变更.jsonl` 的 `ops` 一行不落），不靠这里兜。
     */
    private fun composedGoal(original: String, amended: String): String =
        (if (original.isBlank()) amended else original + AMEND_MARK + amended)
            .take(TaskGoal.MAX_TEXT_CHARS)

    /**
     * 收工按契约判（引擎文本收尾调；**右相验收入口**——三态真由 [ContractSelector] 算）。
     *
     * @param workspace 工作区根（引擎的 spillRoot；产物相对路径按它解析）
     * @param touched 本轮真写成功的产物相对路径（收尾闸同一份清单，[com.example.zhengdao.baopu.verify.VerifyTargets] 记账）
     * @param gateFindings 收尾闸的读数（[ArtifactVerifier.check] 的返回；打勾的"凭什么"）
     * @param trace 本轮真跑成的工具行读数（[RoundTrace]；第六百八十二批新增，默认空＝老调用点口径不变）
     */
    fun closeRun(
        order: RunOrder,
        workspace: File,
        touched: List<String>,
        gateFindings: List<Finding>,
        trace: RoundTrace = RoundTrace(),
    ): ContractReport {
        // 第四百三十六批 B：收工对照（判据③「回执说改的文件 vs 实际改的」）。
        // 挂在这一格而不是引擎里：这里已经是"本轮真写过哪些文件"的**唯一口径**
        // （touched ＝ verifyTouched，与 242 契约验收同一份清单），另起一处清单就是两套真相。
        // 只落账与提出，**不参与**下面那个三态结论：预读越界不是验收失败（裁定「不阻断」），
        // 掺进 ContractReport 等于给 242 的判据表加一格——那是另一批的事。
        PreReadWiring.closeout(touched)
        // 第六百八十二批（G-673-3）：**先认形态，再选尺子**。
        // 只读任务（list_dir 这类）根本不产物，按产物的三条判据收卷必然判「缺证据」——
        // 那不是"没做"，是拿错了尺子。形态从工具行读数里认（不看用户话、不看证据），
        // 认成只读就只判工具行那一条，产物三条摊开标「不适用」。
        val shape = ShapeRules.shapeOf(trace, touched)
        if (shape == TaskShape.READ_ONLY) {
            return stamped(Contracts.readOnlyReport(order.declared, trace), shape, trace)
        }
        // 主交付物按 404 口径解析（resolveTarget：与写入侧 287 对齐）——工作区外的
        // 合法落点（绝对路径写全盘）也算数，不再因为「不在工作区」被当没交卷
        // （401 验机 M7：产物全在盘上却判 FAIL 缺证据的误判链第二环）。
        val primary = touched.asSequence()
            .mapNotNull { ArtifactVerifier.resolveTarget(workspace, it) }
            .firstOrNull { it.extension.lowercase() in DOC_EXTS }
        if (primary == null) {
            // 挑不出文档类主交付物：不硬凑证据——判据表自己判"缺证据"（硬规矩②）。
            return stamped(ContractSelector.evaluate(order.declared, evidence = null), shape, trace)
        }
        val evidence = Contracts.readDoc(
            file = primary,
            declaredItems = listOf(STANDING_CRITERION),
            ticks = listOf(tick(gateFindings)),
        )
        return stamped(ContractSelector.evaluate(order.declared, evidence), shape, trace)
    }

    /**
     * 第六百八十二批：把「这单算哪类任务」与工具行读数盖到报告上（进账与运行日志）。
     *
     * 为什么写类/混合也要盖：判据③要的是「账里能看出本任务属于只读」——反过来，
     * 写入单上写着"本任务：写入"，也就等于把"这条线真的在按形态分档"变成可核对的读数。
     * 工具行对写活是**可选档**（产物存在本身就是更强的「真做过」证据），所以只记不判：
     * 它进 note、不进三态。
     */
    private fun stamped(report: ContractReport, shape: TaskShape?, trace: RoundTrace): ContractReport {
        val tag = shape?.cn ?: "未定"
        val rows = if (trace.okRows > 0) {
            "本轮工具行 ${trace.okRows} 条 ok=1（读 ${trace.readRows}／写 ${trace.writeRows}）"
        } else {
            "本轮没有 ok=1 的工具行"
        }
        return report.copy(shape = shape, note = "本任务：$tag｜$rows；" + report.note)
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

    /** 收工那一行（进运行日志；单行，含三态与未过/缺证据的判据号，以及本任务形态）。 */
    fun reportLine(report: ContractReport): String {
        val head = "$LOG_HEAD：收工验收 ${report.status.cn}（${report.reason.cn}）"
        val shapeBit = shapeBitOf(report.shape)
        if (report.checks.isEmpty()) return "$head$shapeBit｜${report.note}"
        val bad = (report.missing.map { "${it.wire}(缺)" } + report.failed.map { "${it.wire}(败)" })
        val na = report.notApplicable.size
        val required = report.checks.size - na
        val detail = if (bad.isEmpty()) {
            if (na > 0) "必须档 $required 条全过（$na 条不适用）" else "判据 ${report.checks.size} 条全过"
        } else {
            "未过：${bad.joinToString("、")}"
        }
        return "$head｜$detail$shapeBit"
    }

    /**
     * 形态那半句（第六百八十二批·判据③）：账与面板里一眼看出「本任务属于只读」。
     *
     * 只读要说得比另外两类更明白——它是**口径变了**（不是"过了"，是"换了尺子才过的"），
     * 所以把放宽的依据（工具行＋结果摘要）一起写在行里，事后再对账不用翻代码。
     */
    private fun shapeBitOf(shape: TaskShape?): String = when {
        shape == null -> "｜本任务：未定（一条工具行都没有）"
        shape.relaxed -> "｜本任务属于只读（按放宽口径验：工具行＋结果摘要）"
        else -> "｜本任务：${shape.cn}"
    }

    private fun brief(raw: String, max: Int = 24): String {
        val one = raw.replace('\n', ' ').trim()
        return if (one.length <= max) one else one.take(max) + "…"
    }
}
