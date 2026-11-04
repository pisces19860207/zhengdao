// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十六批 §2「影子适配器」：影子（预演）从 239 交付起就**只有判定、没有跑的人**——
// `ShadowRehearsal.compare` 在 `app/src/main` 里零调用，249 六步的第⑤步因此恒 `NO_DATA`，
// 自动免卡档永远开不了。本文件补的就是"谁去跑、跑完把哪五样事实交给判据层"。
//
// ## 分工（三条界线，一条都不许糊）
// - **判定归 [ShadowRehearsal]**（纯函数）：副本根／回滚点／路径集合／真盘字节四件事对不对，
//   全在那一份里算；本文件一个字都不再判一遍；
// - **碰盘归沙盒**（[ShadowSandbox] 接口在本文件声明，实现在装配层 `agent/ShadowSandbox.kt`）：
//   建副本、拍快照、在副本里复刻这次动作、量 diff、量真盘字节——勘验层自己不写盘
//   （[InspectReadOnlyGateTest] 那条"纠察只查不改"的门禁就钉在这道界线上：本文件里
//   一个追加口都没有，落账走 [ShadowLedger]）；
// - **要哪次预演归调用方**（249 第⑤步在 `TaskGrantGate` 里问一句），放不放行也归它。
//
// ## §2 的两条窄口径（她 2026-10-30 开工令原文）
// 1. 第一版**只接** `write_file` / `edit_file`；其余工具 ⇒ `UNVERIFIABLE(reason=UNSUPPORTED)`
//    ⇒ 不开放自动档（不是"跳过这一格放行"）；
//    **第三百零六批**起一口一口补：`move_path`／`delete_path`／`convert_encoding` 在副本里
//    真跑真验（各配一条"真实工具 ⇒ CONSISTENT"用例）；`phone_dial`／`notify` 没有文件副作用
//    ⇒ 判 `NOT_APPLICABLE`（**与「还没接」分得开**：一个是"这格不适用"，一个是"下一批接谁"）；
// 2. **结构故障**（副本建不起／快照失败／沙盒没装配／沙盒没交回真盘字节对照）
//    ⇒ `UNVERIFIABLE(reason=STRUCTURE)` 并**取消本次自动资格**——没有"仍然执行"那颗按钮：
//    本层唯一能交回的结论就是这四态，而四态里只有 CONSISTENT 能被 249 第⑤步认。
//
// ## 361 批 · 双盲重做基底＋两处事件接线（2026-11-02 工单；GuardPipeline 一字不碰）
// - **双盲重做**（[ShadowAdapter.redo]）：副本上真跑一遍关键步骤，产出产物集合＋每产物
//   sha256＋结论，与主执行体**只比指纹与结论**（verify/BlindReview 的两枚 digest；
//   不比过程、不看自我报告）；不一致 ⇒ DISAGREEMENT ⇒ L32 认定（提出位自动真、御批位留给人）
//   喂进 [AccountabilityGate.decide]——喂闸的事实流就位，名册机检据此放行。
// - **INTEGRITY_DAMAGED**（账实核对）：判 MISMATCH ⇒ 发 LEDGER_MISMATCH 事实事件（无出处不发）；
// - **CASCADE_FAILURE**：工具回执 FAIL ⇒ [AnomalyFeed.observeTaskFail] 同任务计数，
//   第 3 次且含不可逆 ⇒ 事件。两处都只喂事实进 [AnomalyGate.classify]，判决归判定层。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.law.AccountabilityFinding
import com.example.zhengdao.baopu.law.AccountabilityGate
import com.example.zhengdao.baopu.law.AccountabilityKind
import com.example.zhengdao.baopu.law.AccountabilityVerdict
import com.example.zhengdao.baopu.law.AnomalyFacts
import com.example.zhengdao.baopu.law.AnomalyGate
import com.example.zhengdao.baopu.verify.BlindReview
import com.example.zhengdao.baopu.verify.BlindReviewInput
import com.example.zhengdao.baopu.verify.BlindReviewOutcome
import com.example.zhengdao.baopu.verify.BlindReviewVerdict
import java.io.File
import java.security.MessageDigest

/**
 * §2：能进副本真跑真验的工具（顺序即错误提示里的次序）。
 *
 * 306 批起从两件扩到五件：`move_path`（搬＝源消失＋目标出现，副本里真搬一遍）、
 * `delete_path`（副本里真删一遍，工具自己的 `回收站/` 归它的账不算影响面）、
 * `convert_encoding`（副本里真转一遍，工具自己的 `备份/` 同理）。
 */
internal val SHADOW_TOOLS = listOf("write_file", "edit_file", "move_path", "delete_path", "convert_encoding")

/**
 * 306 批：**没有文件副作用**的工具——影子对它们不适用。
 *
 * 为什么单列而不留在 UNSUPPORTED 里：UNSUPPORTED 说的是"还没接，下一批接谁"；
 * NOT_APPLICABLE 说的是"这一格本来就没有文件可比"（开拨号盘／发系统通知）。
 * 混为一谈的后果是体检永远差两片接不上的"待办"，而它们根本不是待办（负例点名格）。
 */
internal val NOT_APPLICABLE_TOOLS = listOf("phone_dial", "notify")

/**
 * 跑不出结论的**原因**（闭集字面量，进明细与影子账）。
 *
 * 为什么要单独一列：`UNVERIFIABLE` 只有一个态，但"为什么验不了"差得很远——
 * 工具没接（下一批接一个就少一片）／沙盒没装（装配缺陷）／副本建不起（现场故障），
 * 按原因统计才知道自动档不开卡在哪一格。
 */
internal object ShadowUnverifiable {

    /** §2：这个工具还没接适配器。 */
    const val UNSUPPORTED = "UNSUPPORTED"

    /** 306 批：这个工具没有文件副作用，影子对它不适用（**不是**"还没接"）。 */
    const val NOT_APPLICABLE = "NOT_APPLICABLE"

    /** 这次动作连"声明改了哪个对象"都说不出 ⇒ 没有可比的两份清单。 */
    const val NO_TARGET = "NO_TARGET"

    /** 装配层没装沙盒 ⇒ 一次预演都没跑（与"跑了但跑不出"分得开）。 */
    const val NO_SANDBOX = "NO_SANDBOX"

    /** 结构故障：副本建不起／快照失败／沙盒抛异常／真盘字节那一格没交回。 */
    const val STRUCTURE = "STRUCTURE"

    /** 全部原因（门禁钉的是"闭集"，新增必须在这儿挂号）。 */
    val ALL: List<String> = listOf(UNSUPPORTED, NOT_APPLICABLE, NO_TARGET, NO_SANDBOX, STRUCTURE)
}

/**
 * 一次影子预演的**输入**（由 249 第⑤步的构造层填；本层不读时钟、不解析参数、不碰盘）。
 *
 * @param declaredPaths 计划要改的对象，**相对工作区根**、正斜杠（＝六步第①步那个 objectId）
 * @param workspaceRoot 工作区根绝对路径（真盘侧；副本根由沙盒在它下面开）
 */
internal data class ShadowInput(
    val opId: String = "",
    val tool: String = "",
    val workspaceRoot: String = "",
    val declaredPaths: List<String> = emptyList(),
    /** 这次要落的内容（write_file 的整篇／edit_file 的那一段）。 */
    val content: String = "",
    val editOp: String = "",
    val fromLine: Int? = null,
    val toLine: Int? = null,
    val overwrite: Boolean = false,
    /** 306 批：`delete_path` 的 `permanent`（副本里照原样试：false＝进回收站，true＝真删）。 */
    val permanent: Boolean = false,
    val risk: RiskClass? = null,
    val reversible: Boolean = false,
    val sessionId: Long = -1L,
    /**
     * 引擎分配的轮号＋步号（联键的一对；[ActionStepClock] 发的）。
     *
     * **口径变更点（2026-10-30，第三百零六批）**：这一对在此之前的存量账行里恒 `step=0`
     * （授权链取不到引擎步号，见交付报告『遗留·I6 对表』）——旧账行**照原样留着不改写**；
     * 此后的新行由授权链在第⑤步入口盖章（[TaskGrantGate.shadowSignalFor]），`roundId=0`
     * ＝ 不在任何轮里（恢复执行），也不冒充轮号。
     */
    val roundId: Int = 0,
    val step: Int = 0,
    val wallMs: Long = 0L,
    /** 影子账的落点目录（＝208 那本账同一棵 `抱朴记忆/`）；空 = 没装配 ⇒ 只判定不落账。 */
    val ledgerRoot: String = "",
)

/**
 * 沙盒交回的**实测事实**（不含判定）。
 *
 * @param built 副本建起来了且真的在副本里跑了一遍（false ⇒ 结构故障，本次自动资格取消）
 * @param realUntouched 声明对象在**真盘**上的 before/after 字节 hash 是否相同（§1④ 第三条件；
 *   null = 沙盒没测这一格 ⇒ 适配器按结构故障收，不许拿"没测"冒充"没改"）
 * @param landed 副本里声明对象改后确实落了盘（新建 ⇒ 有字节；覆盖 ⇒ 字节与改前不同）
 */
internal data class ShadowRun(
    val built: Boolean = false,
    val note: String = "",
    val copyRoot: String = "",
    /** 拍快照那一刻**已经存在**的声明对象（相对写法）⇒ 折算"覆盖几个/新建几个"。 */
    val existedBefore: List<String> = emptyList(),
    /**
     * 306 批：已存在声明对象的**改前字节数**（归一键 ⇒ 字节数）。
     * `move_path`／`delete_path` 的所称形状要报「源没了、多少字节」——只有沙盒拍快照时知道，
     * 不给这一格，所称就只能拿 0 冒充（比对必红，红的还不知为什么）。
     */
    val existedBytes: Map<String, Long> = emptyMap(),
    /** 副本树 diff 实测到的落点（副本根下的写法；出根的原样带回来，由判据层认定接错）。 */
    val observedPaths: List<String> = emptyList(),
    /** 实测七格（沙盒给 null ⇒ 适配器按 [observedPaths] 造一份只带路径的形状）。 */
    val observed: ImpactShape? = null,
    val rollback: RollbackPoint? = null,
    val realUntouched: Boolean? = null,
    val landed: Boolean = false,
    /** 工具自己的回执：这一趟在副本里跑成了没有（Ok=true；Failed/Rejected=false）。 */
    val trialOk: Boolean = false,
    val trialNote: String = "",
)

/**
 * 沙盒的口（**只有声明**——勘验层一个字节都不写盘）。
 *
 * 实现归装配层 `agent/ShadowSandbox.kt`：那边才知道怎么建 `.shadow/<opId>/`、怎么把
 * `WriteFileTool`/`EditFileTool` 喂相对路径在副本里跑一遍。
 */
internal interface ShadowSandbox {

    /** 跑一次预演并交回实测（不许交回判定）。 */
    fun rehearse(input: ShadowInput): ShadowRun

    /** 清副本（只在判据层说 CONSISTENT 之后由适配器调；违规的副本不许清）。 */
    fun discard(copyRoot: String)
}

/** 适配器产物：判定 ＋ 原因 ＋ 账落没落。 */
internal data class ShadowRehearsalResult(
    val outcome: ShadowOutcome,
    /** [ShadowUnverifiable] 之一；只有 `UNVERIFIABLE` 才非空。 */
    val reason: String = "",
    /** 影子账这一行落上了没有（落点没装配 ⇒ false，只 log，不影响判定）。 */
    val accounted: Boolean = false,
)

/**
 * 361 批 · 双盲重做的**一个产物指纹**：相对路径（副本根下）＋副本内实测 sha256（小写 hex）。
 * 路径与哈希都是机器键——「拿原文当指纹」过不了形状关，比对必须落在 hash 上。
 */
internal data class ShadowArtifactFingerprint(val path: String, val sha256: String)

/**
 * 361 批 · 一次双盲重做的报告：产物集合＋每产物指纹＋结论＋比对结论＋（不一致时的）L32 认定。
 *
 * 与主执行体**只比指纹与结论**（[BlindReview.compare] 的两枚 digest 各自都盖着
 * 产物集合与结论键）：过程（谁先谁后、跑了几步）与自我报告都不进比对——
 * [BlindReviewInput] 形状上就没有自我报告字段。
 *
 * @param review null ＝ 没跑到比对（影子没跑成／副本里没有可指纹的产物——缺位＝没做，不许读成一致）
 * @param finding DISAGREEMENT ⇒ L32 认定（提出位自动真、御批确认位留给人）；CONCUR 恒 null
 * @param standing [AccountabilityGate.decide] 对这条认定的判定（御批没到 ⇒ findingValid=false）
 */
internal data class ShadowRedoReport(
    val stepId: String,
    val artifacts: List<ShadowArtifactFingerprint>,
    /** 结论键（四态的 wire；与产物一起盖进双方指纹）。 */
    val conclusion: String,
    val shadowDigest: String,
    val review: BlindReviewVerdict? = null,
    val finding: AccountabilityFinding? = null,
    val standing: AccountabilityVerdict? = null,
    val note: String = "",
)

/**
 * 影子适配器（编排：问沙盒 → 交给判据层 → 落账 → 按判定清/留副本）。
 *
 * 本层**不产生第二套判定**：四态一律由 [ShadowRehearsal.compare] 给；短路那几条只是
 * "根本没跑成一次预演"的说明，态只有 [ShadowVerdict.UNVERIFIABLE] 一个。
 */
internal object ShadowAdapter {

    /** 第 4 件的键（影子自己的现场：副本在哪、清没清；只进账，不进给模型的那一句）。 */
    const val ITEM_SANDBOX = "sandbox"

    /** 短路那条沿用 [ShadowRehearsal] 已有的"读数拿不到"键（老影子账本解码不变）。 */
    private const val ITEM_MEASURABLE = "measurable"

    /** 装配层装进来的沙盒（没装 ⇒ [ShadowUnverifiable.NO_SANDBOX]，绝不"没装就当过"）。 */
    @Volatile
    private var port: ShadowSandbox? = null

    /** 装配口（照 `DecisionLedger.install` 房型：只装引用，不装 Context）。 */
    fun install(sandbox: ShadowSandbox?) {
        port = sandbox
    }

    /** 当前装的是哪一份（巡按/报告要知道"影子到底有没有人跑"）。 */
    fun installed(): ShadowSandbox? = port

    fun supported(tool: String): Boolean = tool in SHADOW_TOOLS || tool in NOT_APPLICABLE_TOOLS

    /**
     * 361 批 · 双盲重做的执行基底：在副本上把关键步骤真跑一遍（[rehearse] 那条全量路径），
     * 产出**产物集合＋每个产物的 sha256＋结论**，与主执行体**只比指纹与结论**——
     * 比对走 [BlindReview.compare]（两枚 digest 各自都盖着产物与结论键）：
     * 相等 ⇒ CONCUR；不等 ⇒ DISAGREEMENT ⇒ [BlindReview.asFinding] 造 L32 认定
     * （**提出位自动真、御批确认位留给人**）⇒ [AccountabilityGate.decide]。
     *
     * 盲的形状：本函数的输入只有重做所需的事实（[ShadowInput]）与主执行体的**指纹**
     * （[executorDigest]——由执行侧用 [digestOfArtifacts] 对自己的产物集合＋结论算），
     * 没有自我报告这个字段；过程与辩解在类型上进不了比对。
     *
     * @param executorDigest 主执行体的指纹（[BlindReview.isDigest] 形状关；缺位＝没做 ⇒ 不比）
     */
    fun redo(
        input: ShadowInput,
        executorDigest: String,
        override: ShadowSandbox? = port,
        log: (String) -> Unit = { },
    ): ShadowRedoReport {
        val stepId = input.opId.ifBlank { "sr:${input.sessionId}:${input.step}" }
        val (result, run) = rehearseFull(input, override, log)
        if (run == null || result.reason.isNotBlank()) {
            return ShadowRedoReport(
                stepId = stepId,
                artifacts = emptyList(),
                conclusion = "",
                shadowDigest = "",
                note = "影子没跑成（reason=${result.reason.ifBlank { "STRUCTURE" }}）：没有重做就没有比对（缺位＝没做，不许读成一致）",
            )
        }
        val artifacts = artifactsOf(run)
        val conclusion = result.outcome.verdict.wire
        if (artifacts.isEmpty()) {
            return ShadowRedoReport(
                stepId = stepId,
                artifacts = emptyList(),
                conclusion = conclusion,
                shadowDigest = "",
                note = "副本里没有落盘产物可指纹：没有材料就不比（缺位＝没做，不许读成一致）",
            )
        }
        val shadowDigest = digestOfArtifacts(artifacts, conclusion)
        val review = BlindReview.compare(
            BlindReviewInput(stepId = stepId, stepFacts = listOf("tool=${input.tool}") + input.declaredPaths),
            executorDigest = executorDigest,
            shadowDigest = shadowDigest,
        )
        if (review.outcome != BlindReviewOutcome.DISAGREEMENT) {
            log("抱朴双盲：CONCUR（step=$stepId 指纹一致，复核通过）")
            return ShadowRedoReport(stepId, artifacts, conclusion, shadowDigest, review)
        }
        // DISAGREEMENT ⇒ L32 认定：提出位自动真（影子就是双盲任一方）、御批确认位留给人——
        // decide 对没御批的认定判无效（不收紧、不记账），御批到场那天把 confirmedByThrone
        // 置真再走 decide，累进照算。本层只把事实喂进判定层，不替御批批条子。
        val finding = BlindReview.asFinding(
            verdict = review,
            kind = AccountabilityKind.OVER_WHITELIST,
            category = input.tool.ifBlank { "unknown_tool" },
            atMs = input.wallMs,
        )
        val standing = AccountabilityGate.decide(finding, priorSameCategory = emptyList(), nowMs = input.wallMs)
        log(
            "抱朴双盲：DISAGREEMENT（step=$stepId）⇒ L32 认定已喂闸" +
                "（提出位自动真、御批确认位留给人）：${standing.reason}",
        )
        return ShadowRedoReport(stepId, artifacts, conclusion, shadowDigest, review, finding, standing)
    }

    /**
     * 执行侧的指纹口：主执行体对自己的产物集合＋结论算**同一把**指纹（与影子同一形状才可比）。
     * 影子侧的重做指纹也走这里（[redo] 内部）——一处一个口径，两边各算一套必打架。
     */
    fun digestOfArtifacts(artifacts: List<ShadowArtifactFingerprint>, conclusion: String): String =
        BlindReview.digestOf(
            artifacts.sortedBy { it.path }.map { "${it.path}:${it.sha256}" } + "conclusion=$conclusion",
        )

    /** 副本实测产物指纹：实测落点里**真的在盘上**的才算产物（缺的交给结论那一格去红）。 */
    private fun artifactsOf(run: ShadowRun): List<ShadowArtifactFingerprint> {
        val root = File(run.copyRoot)
        return run.observedPaths.mapNotNull { p ->
            val f = File(p)
            if (!f.isFile) return@mapNotNull null
            val rel = runCatching { f.relativeTo(root).path.replace('\\', '/') }.getOrDefault(f.name)
            ShadowArtifactFingerprint(rel, sha256Hex(f.readBytes()))
        }.sortedBy { it.path }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * 跑一次预演。
     *
     * @param override 测试/调用方直接塞沙盒（不依赖装配状态）；缺省取 [installed]。
     */
    fun rehearse(
        input: ShadowInput,
        override: ShadowSandbox? = port,
        log: (String) -> Unit = { },
    ): ShadowRehearsalResult = rehearseFull(input, override, log).first

    /**
     * 全量路径（361 批拆出）：判读结果＋沙盒实测成对交回——
     * 双盲重做要产物坐标（副本根＋实测落点）才算得出自己的指纹，普通预演只要结论；
     * 短路那几条没有实测 ⇒ 第二格为 null（缺位＝没跑成，不许拿 null 冒充产物）。
     */
    private fun rehearseFull(
        input: ShadowInput,
        override: ShadowSandbox?,
        log: (String) -> Unit,
    ): Pair<ShadowRehearsalResult, ShadowRun?> {
        // 409：运行记录的「耗时」一格——进适配器起量；短路也算一次运行（账照落，耗时照记）。
        val t0 = System.currentTimeMillis()
        // 431①：不可逆动作**执行前**采基线（进门第一句——预演挂在授权闸第⑤步、真执行在闸后，
        // 这一刻结构上早于真盘动第一根手指；比沙盒建副本还早，连 UNSUPPORTED／沙盒没装都不漏）。
        val baseline = ShadowBaseline.capture(input, t0)
        preconditionOf(input, override, log, t0)?.let {
            ShadowOnDemand.completeBaseline(baseline, input, null)
            return it
        }
        val sandbox = requireNotNull(override)
        return sandboxTrialOf(input, sandbox, log, t0, baseline)
    }

    /**
     * 进门三道前置（409 批拆出，判据口径一字未动）：工具没接／这格不适用／说不出对象。
     * 命中 ⇒ 短路（账照落）；全过 ⇒ null，往沙盒走。
     */
    private fun preconditionOf(
        input: ShadowInput,
        override: ShadowSandbox?,
        log: (String) -> Unit,
        t0: Long,
    ): Pair<ShadowRehearsalResult, ShadowRun?>? {
        if (!supported(input.tool)) {
            return shortCircuit(
                input,
                ShadowUnverifiable.UNSUPPORTED,
                "工具「${input.tool}」还没接影子适配器（已接 ${SHADOW_TOOLS.joinToString("／")}）⇒ 不开放自动档",
                log,
                t0,
            ) to null
        }
        // 306 批：无文件副作用的工具在「说不出对象」**之前**判——它们本来就轮不到谈路径，
        // 落到 NO_TARGET 会把"这格不适用"谎报成"这次动作说不出对象"（原因码混用＝统计报废）。
        if (input.tool in NOT_APPLICABLE_TOOLS) {
            return shortCircuit(
                input,
                ShadowUnverifiable.NOT_APPLICABLE,
                "工具「${input.tool}」没有文件副作用 ⇒ 影子不适用（NOT_APPLICABLE；与「还没接」两回事，也没有可复刻的文件动作）",
                log,
                t0,
            ) to null
        }
        if (input.declaredPaths.isEmpty() || input.workspaceRoot.isBlank()) {
            return shortCircuit(
                input,
                ShadowUnverifiable.NO_TARGET,
                "这次动作说不出声明对象或工作区根 ⇒ 没有可比的两份清单（不猜路径）",
                log,
                t0,
            ) to null
        }
        if (override == null) {
            return shortCircuit(
                input,
                ShadowUnverifiable.NO_SANDBOX,
                "装配层没装影子沙盒 ⇒ 一次预演都没跑（结构故障不放行）",
                log,
                t0,
            ) to null
        }
        return null
    }

    /**
     * 沙盒那一段（409 批拆出）：副本建起没有、跑挂没有、真盘字节交了没有——
     * 三处任一缺失都是结构故障 ⇒ 短路；全过 ⇒ 判读（[judge]）。
     */
    private fun sandboxTrialOf(
        input: ShadowInput,
        sandbox: ShadowSandbox,
        log: (String) -> Unit,
        t0: Long,
        baseline: ShadowBaselineCapture?,
    ): Pair<ShadowRehearsalResult, ShadowRun?> {
        val run = runCatching { sandbox.rehearse(input) }.getOrElse {
            ShadowOnDemand.completeBaseline(baseline, input, null)
            return shortCircuit(
                input,
                ShadowUnverifiable.STRUCTURE,
                "沙盒跑挂了：${it.javaClass.simpleName}: ${it.message}（本次自动资格取消）",
                log,
                t0,
            ) to null
        }
        if (!run.built) {
            ShadowOnDemand.completeBaseline(baseline, input, null)
            return shortCircuit(
                input,
                ShadowUnverifiable.STRUCTURE,
                "副本没建起来：${run.note.ifBlank { "沙盒没说原因" }}（本次自动资格取消）",
                log,
                t0,
            ) to null
        }
        // §1④ 的第三条件必须有实测：拿不到就当"没验"，不许拿 null 冒充"真盘没动"。
        if (run.realUntouched == null) {
            ShadowOnDemand.completeBaseline(baseline, input, null)
            return shortCircuit(
                input,
                ShadowUnverifiable.STRUCTURE,
                "沙盒没交回真盘 before/after 字节对照 ⇒ §1④ 的第三条件无从可验",
                log,
                t0,
            ) to null
        }
        // 431①：副本重做刚收手、判读还没清副本——这一刻读**预测后态**（判成一致会清副本，
        // 清了就永远读不到）；基线＋预测一次成行，落影子自己的账。
        val predicted = ShadowOnDemand.predictedOf(run.copyRoot, input.declaredPaths)
        ShadowOnDemand.completeBaseline(baseline, input, predicted)
        return judge(input, run, sandbox, log, t0) to run
    }

    /** 交齐了事实的那一条路径：判据层算，本层只落账与收尾。 */
    private fun judge(
        input: ShadowInput,
        run: ShadowRun,
        sandbox: ShadowSandbox,
        log: (String) -> Unit,
        t0: Long,
    ): ShadowRehearsalResult {
        // §1①：跑成一致就清副本；任何不一致/接错/跑不出都**留着**当证据（账上写清留没留）。
        val judged = ShadowRehearsal.compare(
            claimed = claimedOf(input, run),
            observed = run.observed ?: ImpactShape(touchedPaths = run.observedPaths),
            shadowRoot = run.copyRoot,
            realRoot = input.workspaceRoot,
            rollback = run.rollback,
            probes = probesOf(input, run),
            realUntouched = run.realUntouched,
        )
        val kept = judged.verdict != ShadowVerdict.CONSISTENT
        val outcome = judged.copy(items = judged.items + sandboxItem(run, kept))
        if (!kept) runCatching { sandbox.discard(run.copyRoot) }
        val accounted = account(input, outcome, elapsedMs(t0))
        // 361 批两处事件接线（只发事实、无出处不发、不判决——判决归 law/AnomalyGate）：
        feedIntegrityDamaged(input, outcome, log)
        feedCascadeFailure(input, run, log)
        log("抱朴影子：${outcome.verdict.wire}（op=${input.opId.take(12)} 副本=${tailOf(run.copyRoot)} 账=${if (accounted) "已落" else "未落"}）")
        return ShadowRehearsalResult(outcome, "", accounted)
    }

    /**
     * 361 批 · INTEGRITY_DAMAGED 的接线（账实核对）：影子判 MISMATCH ＝ 所称与实测对不上，
     * 这就是账实不符的现场 ⇒ 向 [AnomalyFeed] 发 [AnomalyFactKind.LEDGER_MISMATCH] 事实事件
     * （出处＝影子账行锚；无出处 ⇒ 灾异源自己拒收，本层不发第二遍）。
     *
     * 只发事实、不判决：判定层的输入由本层投影（不可逆动作与所称对不上 ⇒ 最坏按一件可能
     * 写坏/误删计——保守方向，与 convert_encoding 那格同一个口径），喂进 [AnomalyGate.classify]
     * 后**只记日志**——是不是灾异、进不进紧急通道，归判定层与紧急闸（EmergencyWiring），本层不动。
     */
    private fun feedIntegrityDamaged(
        input: ShadowInput,
        outcome: ShadowOutcome,
        log: (String) -> Unit,
    ) {
        if (outcome.verdict != ShadowVerdict.MISMATCH) return
        val src = provenanceOf(input)
        val evidence = listOf(
            "claimed:${outcome.claimed.newFiles}+${outcome.claimed.overwriteTotal}-${outcome.claimed.deleteCount}",
            "observed:${outcome.observed.newFiles}+${outcome.observed.overwriteTotal}-${outcome.observed.deleteCount}",
            "paths:${(outcome.observed.touchedPaths - outcome.claimed.touchedPaths.toSet()).size}",
        )
        val event = AnomalyFeed.report(
            kind = AnomalyFactKind.LEDGER_MISMATCH,
            provenance = src,
            gist = "影子账实核对：所称与实测不一致（verdict=mismatch，出处的影子账行见锚）",
            nowMs = input.wallMs,
            evidence = evidence,
        )
        val verdict = AnomalyGate.classify(
            AnomalyFacts(
                destroyedItems = if (input.reversible) 0 else 1,
                locallyReversible = input.reversible,
                evidenceLines = listOf(src) + evidence,
            ),
        )
        log(
            "抱朴影子：账实不符已发灾异源（${event?.id ?: "无出处不发"}）；" +
                "L29 判定=${if (verdict.isAnomaly) "灾异" else "未达灾异"}（判决归 AnomalyGate，本层只喂事实）",
        )
    }

    /**
     * 361 批 · CASCADE_FAILURE 的接线（同任务第 3 次 FAIL 且含不可逆动作）：
     * 工具在副本里这一趟跑挂了（[ShadowRun.trialOk]＝false）⇒ 交 [AnomalyFeed.observeTaskFail]
     * 按同任务计数；第 3 次到线且本任务段含过不可逆 ⇒ 灾异源自己发 FAIL_STREAK 事件。
     *
     * 同样只喂事实：事件真发出来了才把失败连击投进 [AnomalyGate.classify]（不可逆 ⇒ 不算可局部
     * 回滚），判定归 AnomalyGate，本层只记日志。
     */
    private fun feedCascadeFailure(
        input: ShadowInput,
        run: ShadowRun,
        log: (String) -> Unit,
    ) {
        if (run.trialOk) return
        val src = provenanceOf(input)
        val event = AnomalyFeed.observeTaskFail(
            taskId = input.opId.ifBlank { input.sessionId.toString() },
            irreversible = !input.reversible,
            provenance = src,
            nowMs = input.wallMs,
        )
        if (event == null) return
        val verdict = AnomalyGate.classify(
            AnomalyFacts(
                outageConsecutiveFails = AnomalyFeed.FAIL_STREAK_LEN,
                locallyReversible = false,
                evidenceLines = listOf(src) + event.evidence,
            ),
        )
        log(
            "抱朴影子：同任务三连 FAIL 已发灾异源（${event.id}）；" +
                "L29 判定=${if (verdict.isAnomaly) "灾异" else "未达灾异"}（判决归 AnomalyGate，本层只喂事实）",
        )
    }

    /** 影子账行的出处锚：有 opId 用影子账行 id（`sh:<opId>`），过不了锚判据就退回影子账的行键（`sr:<会话>:<步>`）——两样都是账行 id 形状，无出处不发的那一格交回灾异源自己拒。 */
    private fun provenanceOf(input: ShadowInput): String {
        val row = if (input.opId.isBlank()) "" else "sh:${input.opId}"
        return AnomalyFeed.provenanceAnchor(row) ?: "sr:${input.sessionId}:${input.step}"
    }

    /** 短路与否都落这一行（判据①「账留路径与对比」——路径两侧都进账）。 */
    private fun shortCircuit(
        input: ShadowInput,
        reason: String,
        note: String,
        log: (String) -> Unit,
        t0: Long,
    ): ShadowRehearsalResult {
        val outcome = ShadowOutcome(
            verdict = ShadowVerdict.UNVERIFIABLE,
            items = listOf(
                ShadowItem(key = ITEM_MEASURABLE, cn = "这一格跑不了影子", passed = null, detail = "reason=$reason $note"),
            ),
            claimed = ImpactShape(touchedPaths = input.declaredPaths),
        )
        val accounted = account(input, outcome, elapsedMs(t0))
        log("抱朴影子：unverifiable reason=$reason（op=${input.opId.take(12)} 账=${if (accounted) "已落" else "未落"}）")
        return ShadowRehearsalResult(outcome, reason, accounted)
    }

    /**
     * 所称（声明侧）：路径都是声明的那几个；数字按**这把工具实际会怎么改**折算——
     * 306 批起按工具各算各的（一个工具一套折法，混用一套就是拿错的形状比对的假红/假绿）：
     * · `move_path`：源消失（删 1 份、源字节数）＋目标出现/被覆盖，`moved` 不数（实测侧同样不数，两边零对零）；
     * · `delete_path`：删 1 份、源字节数（副本里工具自己的 `回收站/` 不算影响面——那是它的退路，与 `备份/` 同一格）；
     * · `convert_encoding`：覆盖 1 份（本来就是 UTF-8 时工具「没动」⇒ 实测 0 ≠ 所称 1，红——保守方向）；
     * · 其余（write/edit）：照 296 的通用折法（改前在＝覆盖，不在＝新建）。
     */
    private fun claimedOf(input: ShadowInput, run: ShadowRun): ImpactShape {
        val existed = run.existedBefore.map { relKey(it) }.toSet()
        val declared = input.declaredPaths.map { it.trim() }.filter { it.isNotEmpty() }
        val touched = declared.map { inCopy(run.copyRoot, it) }
        return when (input.tool) {
            "move_path" -> {
                val from = declared.getOrNull(0).orEmpty()
                val to = declared.getOrNull(1).orEmpty()
                ImpactShape(
                    newFiles = if (relKey(to) in existed) 0 else 1,
                    overwriteTotal = if (relKey(to) in existed) 1 else 0,
                    deleteCount = if (relKey(from) in existed) 1 else 0,
                    deleteBytes = run.existedBytes[relKey(from)] ?: 0L,
                    touchedPaths = touched,
                )
            }
            "delete_path" -> ImpactShape(
                deleteCount = if (declared.any { relKey(it) in existed }) 1 else 0,
                deleteBytes = declared.sumOf { run.existedBytes[relKey(it)] ?: 0L },
                touchedPaths = touched,
            )
            "convert_encoding" -> ImpactShape(
                overwriteTotal = if (declared.any { relKey(it) in existed }) 1 else 0,
                touchedPaths = touched,
            )
            else -> ImpactShape(
                newFiles = declared.count { relKey(it) !in existed },
                overwriteTotal = declared.count { relKey(it) in existed },
                touchedPaths = touched,
            )
        }
    }

    /**
     * 两条验收判据在影子里的读数（§9 第 3 件事）。
     *
     * v1 没有 220 那条独立的推演，能观察的只有"这一趟在副本里到底落没落"：
     * ①工具回执（跑成了没有）②副本落盘（声明对象改后确实有东西／覆盖的确实变了字节）。
     * 两条都由实测填 actual，不拿声明冒充读数（拿声明填两侧＝影子恒绿，那才是骗人）。
     */
    private fun probesOf(input: ShadowInput, run: ShadowRun): List<SuccessProbe> = listOf(
        SuccessProbe(
            name = "试跑回执",
            observable = true,
            expected = "成功",
            actual = if (run.trialOk) "成功" else "失败",
        ),
        SuccessProbe(
            name = "副本落盘",
            observable = true,
            expected = if (input.declaredPaths.isEmpty()) "—" else "已落盘",
            actual = if (run.landed) "已落盘" else "没落盘",
        ),
    )

    /** 第 4 件：影子自己的现场（明细只进账；给模型的那一句里没有这些）。 */
    private fun sandboxItem(run: ShadowRun, kept: Boolean): ShadowItem = ShadowItem(
        key = ITEM_SANDBOX,
        cn = if (kept) "副本留作证据" else "副本已清理",
        passed = true,
        detail = "副本=${tailOf(run.copyRoot)} 快照=${run.existedBefore.size} 个" +
            " 实测=${run.observedPaths.size} 个 退路=${if (run.rollback?.usable == true) "可用" else "不可用"}" +
            " 真盘字节=${when (run.realUntouched) { true -> "未动"; false -> "被动"; null -> "未测" }}" +
            " 回执=${run.trialNote}",
    )

    /** 落一行影子账（[ShadowLedger] 那一本；落点没装配就只少一行账，不影响判定）。 */
    private fun account(input: ShadowInput, outcome: ShadowOutcome, elapsedMs: Long): Boolean {
        if (input.ledgerRoot.isBlank()) return false
        val entry = ShadowLedger.entryOf(
            outcome = outcome,
            actions = input.declaredPaths.take(3).map { "${input.tool}:$it" }.ifEmpty { listOf(input.tool) },
            risk = input.risk,
            reversible = input.reversible,
            sessionId = input.sessionId,
            step = input.step,
            atMs = input.wallMs,
            id = if (input.opId.isBlank()) "" else "sh:${input.opId}",
            roundId = input.roundId,
            elapsedMs = elapsedMs,
        )
        return ShadowLedger.append(File(input.ledgerRoot), entry)
    }

    /** 运行记录的耗时格（毫秒；从进适配器那刻起量，负数钳成 0）。 */
    private fun elapsedMs(t0: Long): Long = (System.currentTimeMillis() - t0).coerceAtLeast(0L)

    /** 相对写法归一（正斜杠＋大小写不敏感——与判据层同一个口径，两边各归一套必打架）。 */
    private fun relKey(p: String): String =
        p.replace('\\', '/').trimEnd('/', ' ').lowercase()

    /** 声明路径折进副本根下的写法（所称与实测共用一个坐标系）——纯拼接，与沙盒 observedPaths（`absolutePath + "/" + key`）逐字节同式；剥根那把尺子全仓只许 RunRules 写一次（ConfirmCardPathDisplayTest 门禁）。 */
    private fun inCopy(copyRoot: String, rel: String): String =
        "${copyRoot}/${rel.trim().replace('\\', '/').trimStart('/')}"

    /** 明细里的路径只留末两段（整条家目录进账没意义）。 */
    private fun tailOf(path: String): String =
        if (path.isBlank()) "—" else path.replace('\\', '/').split('/').takeLast(2).joinToString("/")
}
