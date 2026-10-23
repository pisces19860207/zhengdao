// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第九批 §4.3（B 段抱朴页）+ 第⑮刀经验（可观察性 bug 改状态载体：
// 引擎状态用 mutableStateOf + State 暴露）+ 设计 §9 施工细节第 1/8 条
// （流式期间内存累积、记忆写入要在会话里可见）。
// 本文件是派工单 §4.3 表格之外的第 6 个 UI 文件（偏差清单报备）：
// 没有它，装配逻辑只能散在 Composable 里，进一次页面建一次线程池。
package com.example.zhengdao.ui.baopu

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.example.zhengdao.R
import com.example.zhengdao.baopu.AskUserCard
import com.example.zhengdao.baopu.AskUserOption
import com.example.zhengdao.baopu.agent.AskKind
import com.example.zhengdao.baopu.agent.AskUserRequest
import com.example.zhengdao.baopu.accumulateUsage
import com.example.zhengdao.baopu.BaopuUsageRow
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.BaopuSql
import com.example.zhengdao.baopu.DiaryEntry
import com.example.zhengdao.baopu.DiaryEvidence
import com.example.zhengdao.baopu.DiaryIndexStore
import com.example.zhengdao.baopu.DiaryRules
import com.example.zhengdao.baopu.DiaryStore
import com.example.zhengdao.baopu.EvolutionAuditLog
import com.example.zhengdao.baopu.EvolutionDecision
import com.example.zhengdao.baopu.EvolutionDecisionPort
import com.example.zhengdao.baopu.EvolutionEvidence
import com.example.zhengdao.baopu.EvolutionShadowHuman
import com.example.zhengdao.baopu.EvolutionShadowLog
import com.example.zhengdao.baopu.ShadowJudgement
import com.example.zhengdao.baopu.agreementShadow
import com.example.zhengdao.baopu.latestByCandidate
import com.example.zhengdao.baopu.shadowSummaryLine
import com.example.zhengdao.baopu.shadowVerdictOf
import com.example.zhengdao.baopu.tallyShadow
import com.example.zhengdao.baopu.emptyRoundUsage
import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.SessionCompressionStore
import com.example.zhengdao.baopu.MemoryBackup
import com.example.zhengdao.baopu.MemoryEntry
import com.example.zhengdao.baopu.MemoryInjectionBudget
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.baopu.MemoryRefine
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.MemoryStatus
import com.example.zhengdao.baopu.MemoryStore
import com.example.zhengdao.baopu.PendingStore
import com.example.zhengdao.baopu.ProposalCandidateStore
import com.example.zhengdao.baopu.ProposalRules
import com.example.zhengdao.baopu.SessionPendingStore
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.eval.EvalAttempt
import com.example.zhengdao.baopu.eval.EvalEvidenceSource
import com.example.zhengdao.baopu.eval.EvalResultFile
import com.example.zhengdao.baopu.eval.ProdEval
import com.example.zhengdao.baopu.eval.ProdEvalGate
import com.example.zhengdao.baopu.eval.ProdEvalOutcome
import com.example.zhengdao.baopu.eval.ProdEvalResultFile
import com.example.zhengdao.baopu.eval.ProdEvalSchedule
import com.example.zhengdao.baopu.eval.ProposalRedline
import com.example.zhengdao.baopu.eval.ProposalRestore
import com.example.zhengdao.baopu.eval.ProposalSelfCheck
import com.example.zhengdao.baopu.eval.RuntimeEval
import com.example.zhengdao.baopu.eval.SelfCheckGate
import com.example.zhengdao.baopu.eval.SelfCheckOutcome
import com.example.zhengdao.baopu.eval.SelfCheckText
import com.example.zhengdao.baopu.eval.SkillBodyGuard
import com.example.zhengdao.baopu.eval.SkillBodyInput
import com.example.zhengdao.baopu.eval.SkillBodyOutcome
import com.example.zhengdao.baopu.eval.TargetKind
import com.example.zhengdao.baopu.eval.TargetSnapshot
import com.example.zhengdao.baopu.agent.ConfirmRequest
import com.example.zhengdao.baopu.agent.EngineEvent
import com.example.zhengdao.baopu.agent.RunState
import com.example.zhengdao.baopu.agent.SendAcceptance
import com.example.zhengdao.baopu.library.LibraryItem
import com.example.zhengdao.baopu.library.LibraryKind
import com.example.zhengdao.baopu.library.LibrarySource
import com.example.zhengdao.baopu.library.MoveChoice
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 会话库一行（第二十批 §2.1 浮层数据源；SQL 在
 * [com.example.zhengdao.baopu.BaopuSql.sessionLibraryEntries]）。
 *
 * [closedAt] / [workspace] 可空都是**事实**不是缺省：closed_at 空 = 没封过卷（可继续），
 * workspace 空 = 老会话当时没记（不许拿当前工作区补——K3）。
 * [rounds] = 真轮数（role='user' 消息条数，SQL 实时数出；第二十五批 A 段——
 * 之前叫 msgCount 读 msg_count 列，那列没维护、真机全线 0）。
 */
data class BaopuSessionEntry(
    val id: Long,
    val title: String,
    val rounds: Int,
    val updatedAt: Long,
    val closedAt: Long?,
    val workspace: String?,
)

/** 一个消息的 UI 形状（role 分组由列表层做）。 */
data class BaopuUiMessage(
    val id: Long,
    val role: String,
    val parts: List<BaopuUiPart>,
    /** 落库时间（十六批第一件：轮窗口与「消息时间差」兜底用时的数据源；只读透出）。 */
    val createdAt: Long = 0L,
    /**
     * 第十七批 C 段：本条消息挂的计划（`part(kind='plan')` 解析而来；没有 ⇒ null）。
     * 计划**只走这个字段**——parts 里永远不出现 kind='plan'（J16：payload 不当正文）。
     */
    val plan: BaopuUiPlan? = null,
)

/** 一个 part 的 UI 形状（text 直显；tool 显示调用名 + 结果摘要，可展开全文）。 */
data class BaopuUiPart(
    // 库里 part.kind 原值："text" | "tool" | "compressed" | "image"
    // （第十七批的 "plan" 在 readMessages 里解析进 BaopuUiMessage.plan，不进这里——见该函数 KDoc；
    //  第二十四批 B 段的 "image" 直接进这里：text 字段装的**就是相对路径**，由列表层渲染缩略图）
    val kind: String,
    val text: String,
    val tool: String = "",
)

/**
 * 便签级别（第六十一批 §2「错误留痕 1b」）。
 *
 * 现场：`libraryNote` / `skillNote` 这两类一句话里混着两种东西——
 * 「记下来中…／收料处理中…／导出中…」是**进度**，
 * 「技能上限拒绝／写盘失败／资料库选项对不上」是**出错**。
 * 而 UI 只有一条规矩：非空 8 秒后清空。于是错误和进度一起蒸发，
 * 用户 8 秒后看到的是一片安静，什么都没发生。
 *
 * 两档各走各的：**Info 照旧 8 秒自清**（进度提示不许钉在屏上），
 * **Error 常驻、点掉才清**（那条错误就是用户下一步的依据）。
 */
internal enum class BaopuNoteLevel { Info, Error }

/**
 * 一条便签 = 文案 ＋ 级别（§2 选「`data class Note(text, level)`」这一型，不选「多一个
 * `*NoteLevel` 字段」）：级别跟文案同一个对象 ⇒ **赋值点不可能只写文案漏标级别**，
 * 也不可能出现「文案是错误、级别还是 Info」那种半新半旧的状态。
 *
 * 副作用范围：只影响「这条提示怎么留」，不改 SKILL 写盘与资料库动作本身。
 */
internal data class BaopuNote(val text: String, val level: BaopuNoteLevel) {
    /**
     * 这条会不会自己走。判据本体只有一个（[BaopuNotePolicy.shouldAutoClear]），
     * 这里只是 UI 那边少写一层的入口——别在显示处再抄一遍 `level == Info`。
     */
    val autoClears: Boolean get() = BaopuNotePolicy.shouldAutoClear(level)
}

/**
 * 级别 → 行为 的纯判据（零 Android，§2 判据直接打）。
 */
internal object BaopuNotePolicy {

    /** 只有进度（Info）挂 8 秒自清；错误常驻——这是那条延迟唯一的开关。 */
    fun shouldAutoClear(level: BaopuNoteLevel): Boolean = level == BaopuNoteLevel.Info

    /**
     * 结果播报的定级：有出问题的件数（被拒／跳过／撞名／失败）⇒ Error。
     *
     * 为什么不能一律 Info：「收料完成：3 件进私有；被拒 5 件」这句话 8 秒后没了，
     * 用户记住的只有「完成了」半截——那 5 件为什么没进，一个字都不剩。
     */
    fun levelOfResult(problemCount: Int): BaopuNoteLevel =
        if (problemCount > 0) BaopuNoteLevel.Error else BaopuNoteLevel.Info
}

/**
 * 一条「进化建议」：86 的候选快照 ＋ 88 决策口的判定（第九十九批，供设置页那张卡读）。
 *
 * 为什么带 payload：卡上要显示建议正文给人看——**审计里不进**（[EvolutionAuditEntry] 只有
 * 输入摘要与判定），这里只是 UI 状态，不是账本。
 *
 * @param requiresApproval 是否需要人批（v1 恒 true：L3 开关默认关）
 * @param reasons 只带证据 id 的原因链（渲染时按原因码查 strings.xml，不把 id 当文案显示）
 * @param evalSource 第一百六十八批：本次判定用的 eval 证据来源（null = 没有可用证据 ⇒ policy 判 DEFER）
 * @param evalRanAt 证据产出时刻（null = 不知道 ⇒ 卡上显示「日期不详」，不编）
 */
internal data class EvolutionSuggestion(
    val candidateId: String,
    val kind: com.example.zhengdao.baopu.ProposalKind,
    val target: String,
    val payload: String,
    val createdAt: Long,
    val recommendation: com.example.zhengdao.baopu.EvolutionRecommendation,
    val confidence: Double,
    val riskClass: com.example.zhengdao.baopu.EvolutionRisk,
    val requiresApproval: Boolean,
    val reasons: List<com.example.zhengdao.baopu.EvolutionReason>,
    val evalSource: com.example.zhengdao.baopu.eval.EvalEvidenceSource? = null,
    val evalRanAt: Long? = null,
)

/**
 * 第一百批：采纳前的基线快照（**写盘之前**取）。[source] 的三态含义见
 * [com.example.zhengdao.baopu.PostAdoptionBaseline]。
 */
private data class PreAdoptionBaseline(
    val counts: Map<com.example.zhengdao.baopu.SkillOutcome, Int>,
    val source: com.example.zhengdao.baopu.PostAdoptionBaseline,
)

/**
 * 抱朴页的胶水（进程级单例挂在 [BaopuRuntime]；本类只持 UI 可观察状态）。
 *
 * 数据流：引擎落库（先落库再渲染）⇒ onEvent ⇒ [refresh] 重读 db ⇒ Compose 状态更新。
 * UI 不回写引擎数据，只调动作（send/stop/resume/记忆确认）。
 */
internal class BaopuChatController(context: Context) {

    private val appCtx = context.applicationContext
    // 2026-10-18 真机：「这次学到怎么没有啊」——自省便签原来只在内存里，进程一重启就没了，
    // 而「已压缩」是从库里恢复的 ⇒ 跑马灯只剩一条。这里按会话 id 落到 SP，重启后照旧可读。
    private val notePrefs = context.applicationContext
        .getSharedPreferences("baopu-introspection", android.content.Context.MODE_PRIVATE)
    private val db = BaopuRuntime.db(appCtx)
    private val engine = BaopuRuntime.engine(appCtx)
    private val store: MemoryStore = BaopuRuntime.memoryStore(appCtx)

    /** pending 三列的存取（控制器重建确认卡与销卡都经它；清库由引擎负责）。 */
    private val pendingStore: PendingStore = SessionPendingStore(db)

    /**
     * 工作区根 = 确认卡显示相对路径的基准（第一百四十九批 组5）。
     *
     * 必须是 [BaopuRuntime.workspaceRoot] 那一个目录：装配层把它注入给引擎的 `spillRoot`，
     * 管线②段改写绝对路径用的也是它 ⇒ 同一个根才剥得掉。这里现取不缓存，
     * 因为换工作区（[BaopuRuntime] 支持）后卡面要跟着一变。
     */
    val workspaceRoot: File get() = BaopuRuntime.workspaceRoot(appCtx)

    /** UI 线程 Handler：引擎事件从后台线程来，统一 post 回主线程再刷（见 [onEngineEvent]）。 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 壳层自己的协程 scope（资料库收料/导出是 IO 活，压主线程会 ANR）。
     *
     * 房型与 `oc/` 曾经的进程级作用域同款：Main 派发（状态写回天然在主线程）＋
     * SupervisorJob（一个活失败不连坐）＋ 协程名 ＋ [CoroutineCrashHandler]
     * （未捕获异常照旧交系统处理器，只多一行带协程名的日志）。
     */
    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Main +
            CoroutineName("zd-baopu-shell") +
            com.example.zhengdao.keepalive.CoroutineCrashHandler,
    )

    /**
     * 第五十批：模型提案**落盘后**的自检（单飞）。
     *
     * 三个刻意的设计：
     * 1. `runEval` 里**现读**人设与通道，不缓存任何东西——落盘后调用时读到的就是新版
     *    （用 `BaopuPreset.personaText` 单独跑＝测旧人设，派工单 §0 的硬约束）；
     * 2. **只挂「模型提案被采纳」这一条路**（人设 B/C 采纳、技能卡采纳），设置页手写
     *    （`BaopuSettingsScreen` / `BaopuSkillCard`）是用户自己的编辑，不挂；
     * 3. 不阻塞：自检在 IO 上跑，用户照旧继续聊；第二个并发调用直接 Busy，不互相回滚。
     */
    private val proposalSelfCheck: ProposalSelfCheck by lazy {
        ProposalSelfCheck(
            runEval = {
                val provider = RuntimeEval.providerOf(appCtx)
                val why = RuntimeEval.unusableReason(provider)
                if (why != null) {
                    EvalAttempt.Skip(why)
                } else {
                    val run = RuntimeEval.runFor(
                        appCtx,
                        com.example.zhengdao.baopu.BaopuRuntime.personaFiles(appCtx),
                        com.example.zhengdao.baopu.BaopuWorkspace.hostDir(appCtx),
                        provider,
                    )
                    if (run == null) {
                        EvalAttempt.Skip("用例读不到（APK 里没有 eval/cases.json）")
                    } else {
                        EvalAttempt.Ran(run)
                    }
                }
            },
        )
    }

    /**
     * 排一次自检：先照快照与允许集合算红线，再在 IO 上跑（结果回写 [skillNote]）。
     *
     * [targets] 必须**落盘前**建好（`TargetSnapshot.of` 记当时的 sha256 与"文件在不在"）。
     */
    private fun scheduleSelfCheck(scene: String, targets: List<TargetSnapshot>) {
        if (targets.isEmpty()) return
        val workspace = com.example.zhengdao.baopu.BaopuWorkspace.hostDir(appCtx)
        val pf = com.example.zhengdao.baopu.BaopuRuntime.personaFiles(appCtx)
        val globalRoot = java.io.File(pf.globalFile().parentFile ?: workspace, "")
        val redline = targets.flatMap { t ->
            ProposalRedline.violationsOf(
                target = t.file,
                workspace = workspace,
                allowedFiles = listOf(pf.globalFile(), pf.projectFile()),
                allowedDirs = listOf(
                    SkillRules.projectSkillDir(workspace),
                    SkillRules.globalSkillDir(globalRoot),
                ),
            )
        }
        // 派工单 84 批 v1 适配项：main 上 skillNote 已是 BaopuNote（便签系统）而非 String
        skillNote = BaopuNote("自检中…", BaopuNoteLevel.Info)
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                proposalSelfCheck.execute(scene, targets, redline)
            }
            // Busy 不覆盖屏上已有的「自检中…」（另一个自检还在跑）
            if (out != SelfCheckOutcome.Busy) {
                skillNote = BaopuNote(SelfCheckText.of(out).orEmpty(), selfCheckLevel(out))
            }
        }
    }

    /** 自检结果 → 便签级别（派工单 84 批 v1 适配项：等级与 SelfCheckText 口径同源）。 */
    private fun selfCheckLevel(o: SelfCheckOutcome): BaopuNoteLevel = when (o) {
        is SelfCheckOutcome.Passed -> BaopuNoteLevel.Info
        is SelfCheckOutcome.NotRun -> BaopuNoteLevel.Info
        else -> BaopuNoteLevel.Error
    }

    /**
     * 第一百六十八批：ProdEval 调用点（派工单 §2：会话收尾触发、节流、不阻塞）。
     *
     * 四个刻意的设计：
     * 1. **闸门第一道**：开关关着 ⇒ 除了读一次 prefs 什么都不做（不查网络、不排协程、
     *    不读用例）——「零动作」而不是「很快返回」；
     * 2. **节流 24h**：同一台设备最多一天一轮（[ProdEvalSchedule.shouldRun] 纯判定，
     *    JVM 直测）；烧的是用户的额度，收尾事件比它勤得多；
     * 3. **单飞**：上一轮还在 IO 上跑就不排第二次（[ProdEvalSchedule.tryAcquire]）；
     * 4. **不阻塞**：整轮在 Dispatchers.IO 上跑，任何失败只变 [ProdEvalOutcome.NotRun]
     *    （[ProdEval.execute] 自己兜底），聊天路径一行都不等它。
     *
     * 只有一个调用点：这里（会话收尾）。引擎（RunEngine）不接线——两处触发就会出现
     * 「同一天烧两轮额度」，`ProdEvalTest` 的源码门禁盯着这件事。
     */
    private fun scheduleProdEvalOnSettle() {
        runCatching {
            val ctx = appCtx
            if (!ProdEvalGate.enabledNow(ctx)) return
            if (!ProdEvalSchedule.tryAcquire()) return
            val due = ProdEvalSchedule.shouldRun(
                enabled = true,
                networkAvailable = networkAvailable(ctx),
                lastRanAt = ProdEvalSchedule.lastRanAt(ctx),
                now = System.currentTimeMillis(),
            )
            if (!due) {
                ProdEvalSchedule.release()
                return
            }
            scope.launch {
                val out = runCatching {
                    withContext(Dispatchers.IO) {
                        ProdEval.of(
                            ctx,
                            com.example.zhengdao.baopu.BaopuRuntime.personaFiles(ctx),
                            com.example.zhengdao.baopu.BaopuWorkspace.hostDir(ctx),
                        ).execute("会话收尾")
                    }
                }.getOrNull()
                // 只有真跑成了才记节流账（NotRun 不记：没 key 记一笔会堵掉之后 24h）。
                if (out is ProdEvalOutcome.Recorded) {
                    ProdEvalSchedule.markRan(ctx, System.currentTimeMillis())
                }
                ProdEvalSchedule.release()
            }
        }.onFailure {
            // 只记录不阻塞（65/89 口径）：排程自己崩了也不许把会话收尾带下去。
            ProdEvalSchedule.release()
            com.example.zhengdao.rootfs.RunLog.log(
                "[prod-eval] 排程异常（不阻塞对话）：${it.javaClass.simpleName}: ${it.message}",
            )
        }
    }

    /**
     * 网络通不通（节流判据之一）。
     *
     * 与 [com.example.zhengdao.ui.EnvHealth] 的体检同款取法，但只做轻量判断：
     * 拿不到能力／无活跃网络 ⇒ 按不通处理（不赌，也不做 TCP 探测——那是体检的活）。
     */
    private fun networkAvailable(ctx: android.content.Context): Boolean = try {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        cm.getNetworkCapabilities(net)
            ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (t: Throwable) {
        false
    }

    /** 第八十四批 v2：技能正文自检器（本地判据 + 失败回滚，不调模型）。 */
    private val skillBodyGuard: SkillBodyGuard by lazy { SkillBodyGuard() }

    /** 当前会话（null = 还没有；首次发送时建）。 */
    var sessionId by mutableStateOf(BaopuRuntime.latestSessionId(db))
        private set

    /** 会话里落库的 interrupted_step（-1 = 未中断）。 */
    var interruptedStep by mutableIntStateOf(-1)
        private set

    /** 消息列表（从 db 读——先落库再渲染，UI 永远不比库新）。 */
    var messages by mutableStateOf(listOf<BaopuUiMessage>())
        private set

    /** 记忆候选（status=CANDIDATE 的条目；确认/不记都在这里操作）。 */
    var memoryCandidates by mutableStateOf(listOf<com.example.zhengdao.baopu.MemoryEntry>())
        private set

    /**
     * 记忆注入预算的只读预览（第九十一批 R3）。
     *
     * **不驱动实际注入**：RunEngine 的注入仍走原来的 `MemoryRules.trimToBudget`；
     * 这里只把「按分数/新鲜度/相关性排序后，条数/字符预算装得下几条」算出来，
     * 供后续批次与报告取读数（本批 UI 不渲染它，视觉零变化）。
     */
    internal var memoryInjectionPreview by mutableStateOf(MemoryInjectionBudget.Plan.EMPTY)
        private set

    /**
     * C 段提炼出的候选记忆（[com.example.zhengdao.baopu.MemoryStatus.PENDING]、**未落盘**）。
     *
     * 为什么单独一份而不是并进 [memoryCandidates]：那边是库里已存在的 CANDIDATE
     * （刀 0b 的老机制），这边是本刀新产的、**还没资格落盘**的候选（派工单 C 段：
     * 确认前一律 PENDING）。两条通道的确认动作也不同（老通道 update、新通道 write）。
     */
    var pendingMemories by mutableStateOf(listOf<com.example.zhengdao.baopu.MemoryEntry>())
        private set

    /** C 段自省便签（**恰好一条**；本轮结束时刷新，新会话清空）。 */
    var introspectionNote by mutableStateOf<String?>(null)
        private set

    /**
     * 第九十九批：进化建议（PENDING 候选＋判定快照；每轮收尾判定后刷新）。
     *
     * 这是 §2 要的「controller 可观察状态」接缝：judge 结果**只影响展示**，
     * 没有任何自动执行路径读它。UI 批未到之前它就先在这儿等着（判据钉它非空链路）。
     */
    var evolutionSuggestions by mutableStateOf(listOf<EvolutionSuggestion>())
        private set

    // ── 第一百零四批：决策卡家族＋全局单卡槽（设计稿 v1.4 §2/§2.3）────────────
    //
    // 四类事（授权 97 / 效果 100 / 提案 96 / 建议 99）共用一张卡；对话流里
    // **任何时刻只展示一张**，按优先级占槽，其余排队（判据在 DecisionCardRules，
    // JVM 直测）。本类只回答两件事：这一刻有哪些待办、当前这一件的规格是什么。

    /**
     * 第一百批的效果卡从「单张」改成「队列」：recheck 可能一次返回多张，
     * 旧代码 `if (postAdoptionNotice == null)` 会把多出来的**直接丢掉**——
     * 而账本已经记了 SUGGESTED，丢掉＝再也不回来（只弹一次被浪费在排队上）。
     * 现在进队列，按槽位优先级逐张出示。
     */
    var postAdoptionQueue by mutableStateOf(listOf<com.example.zhengdao.baopu.PostAdoptionNotice>())
        private set

    /** 槽里当前展示的那一件（null = 空槽；判据见 [decisionSlotCurrent]）。 */
    private var decisionCurrent by mutableStateOf<DecisionItem?>(null)

    /**
     * 这一刻的全部待办（授权/效果/提案/建议四类；普通 `ask_user` 提问**不进**决策槽——
     * 那是对话不是决策，仍走原选项卡 [pendingChoice]）。
     *
     * 投影本身是纯函数（[decisionPendingItems]），这里只负责把可观察状态摊平。
     */
    val decisionPending: List<DecisionItem>
        get() = decisionPendingItems(
            choiceKind = pendingChoice?.kind,
            choiceSubject = pendingChoice?.subject.orEmpty(),
            effectIds = postAdoptionQueue.map { it.eventId },
            suggestionIds = evolutionSuggestions.map { it.candidateId },
        )

    /** 当前该展示哪一件（不抢占：处理/消失才换，见 [decisionSlotCurrent]）。 */
    val decisionSlot: DecisionItem?
        get() = decisionSlotCurrent(decisionCurrent, decisionPending)

    /**
     * 槽位展示登记（屏幕层在 [androidx.compose.runtime.SideEffect] 里调）。
     *
     * 为什么需要它：不抢占（§2.3 第 2 条）靠「当前展示的那一件被钉住」实现——
     * 每次重组都重算「优先级最高的一件」的话，后来的高优先级卡会把用户正在看的卡
     * 抢走。登记是幂等的（同值不写），item 为 null＝空槽。
     */
    fun noteDecisionShown(item: DecisionItem?) {
        if (decisionCurrent != item) decisionCurrent = item
    }

    /** 排队数（「还有 N 条在排队」那一行）。 */
    val decisionQueueCount: Int
        get() = decisionQueueCount(decisionCurrent, decisionPending)

    /** 顶栏盾牌徽标（前三页签待处理总数；记录页不计）。0 = 不显示。 */
    val decisionBadge: Int
        get() {
            val auth = if (pendingChoice?.kind == AskKind.GROUP_AUTHORIZATION) 1 else 0
            val proposal = if (pendingChoice?.kind == AskKind.PERSONA_PROPOSAL) 1 else 0
            return decisionBadgeCount(auth, postAdoptionQueue.size + evolutionSuggestions.size, proposal)
        }

    // ── 决策卡规格（接线层填文案；判据在 DecisionCardRules，JVM 直测） ──────────

    /** 当前槽位那一件的卡片规格（null = 空槽，不渲染卡）。 */
    fun decisionCardSpec(): DecisionCardSpec? {
        val slot = decisionSlot ?: return null
        return when (slot.kind) {
            DecisionVariant.AUTHORIZATION -> pendingChoice
                ?.takeIf { it.kind == AskKind.GROUP_AUTHORIZATION }
                ?.let { authorizationSpec(it) }

            DecisionVariant.PERSONA -> pendingChoice
                ?.takeIf { it.kind == AskKind.PERSONA_PROPOSAL }
                ?.let { personaSpec(it) }

            DecisionVariant.POST_ADOPTION -> postAdoptionQueue
                .firstOrNull { "effect:${it.eventId}" == slot.id }
                ?.let { effectSpec(it) }

            DecisionVariant.SUGGESTION -> evolutionSuggestions
                .firstOrNull { "suggestion:${it.candidateId}" == slot.id }
                ?.let { suggestionSpec(it) }
        }
    }

    // 四变体规格的构建在 `DecisionCardSpecs.kt`（纯映射＋文案解析器注入，JVM 直测）；
    // 这里只包一层「生产解析器」——appCtx.getString 就是那唯一的 Android 依赖。
    private val decisionStr = DecisionStrRes { id, args -> appCtx.getString(id, *args) }

    private fun authorizationSpec(req: AskUserRequest): DecisionCardSpec =
        DecisionSpecs.authorization(req, decisionStr)

    private fun personaSpec(req: AskUserRequest): DecisionCardSpec =
        DecisionSpecs.persona(req, decisionStr)

    private fun suggestionSpec(s: EvolutionSuggestion): DecisionCardSpec =
        DecisionSpecs.suggestion(s, decisionStr)

    private fun effectSpec(n: com.example.zhengdao.baopu.PostAdoptionNotice): DecisionCardSpec =
        DecisionSpecs.effect(n, decisionStr)

    /**
     * 卡上点了一枚按钮（屏幕层原样转交 id，本层按当前槽位归类）。
     *
     * 授权/提案的按钮 id 就是引擎选项 id ⇒ 原路 [answerChoice] 答回去，
     * 执行路径（解锁/落盘）一个字没动；「去设置页处理」由屏幕层拦截（它拥有
     * 设置页开关），不往控制器传。
     */
    fun onDecisionAction(id: String) {
        val slot = decisionSlot ?: return
        onDecisionCardAction(slot.id, id)
    }

    /**
     * 对**指定**的一张卡动手（决策中心里点行开的卡；不依赖当前槽位）。
     *
     * @param key 卡键（`auth:`/`persona:`/`effect:`/`suggestion:` 前缀，见 [DecisionItem]）
     */
    fun onDecisionCardAction(key: String, buttonId: String) {
        when {
            key.startsWith("effect:") -> {
                val eventId = key.removePrefix("effect:")
                when (buttonId) {
                    "keep" -> keepPostAdoption(eventId)
                    "later" -> laterPostAdoption(eventId)
                    "rollback" -> rollbackPostAdoption(eventId)
                    else -> Unit // tosettings：屏幕层拦截
                }
            }

            key.startsWith("suggestion:") -> {
                val candidateId = key.removePrefix("suggestion:")
                when (buttonId) {
                    "reject" -> decideSuggestion(candidateId, SuggestionDecision.REJECT)
                    "defer" -> decideSuggestion(candidateId, SuggestionDecision.DEFER)
                    "adopt" -> decideSuggestion(candidateId, SuggestionDecision.ADOPT)
                    else -> Unit
                }
            }

            else -> answerChoice(buttonId) // auth: / persona: ⇒ 引擎选项 id 原路答回
        }
    }

    // ── 决策中心的数据口（屏幕层只渲染，不自己判） ────────────────────────────

    /** 待授权页签：当前那张授权卡（引擎单槽，最多一张）。 */
    fun pendingAuthorizationSpec(): DecisionCardSpec? =
        pendingChoice?.takeIf { it.kind == AskKind.GROUP_AUTHORIZATION }?.let { authorizationSpec(it) }

    /** 提案页签：当前那张人设提案卡。 */
    fun pendingProposalSpec(): DecisionCardSpec? =
        pendingChoice?.takeIf { it.kind == AskKind.PERSONA_PROPOSAL }?.let { personaSpec(it) }

    /**
     * 建议页签的行（99 建议＋100 效果双类型；效果排前，类型签区分——不并串）。
     *
     * 行可点开同款卡；行上的徽标/副标题与卡面同源（标题函数复用）。
     */
    fun decisionCenterSuggestionRows(): List<DecisionCenterRow> = suggestionTabRows(
        buildList {
            postAdoptionQueue.forEach { n ->
                val retire = n.verdict == com.example.zhengdao.baopu.PostAdoptionVerdict.RETIRE
                add(
                    DecisionCenterRow(
                        id = "effect:${n.eventId}",
                        variant = DecisionVariant.POST_ADOPTION,
                        title = appCtx.getString(R.string.post_adopt_title, n.skillName),
                        subtitle = appCtx.getString(
                            if (retire) R.string.post_adopt_verdict_retire else R.string.post_adopt_verdict_rollback,
                        ),
                        badge = appCtx.getString(R.string.decision_badge_effect),
                        typeLabel = appCtx.getString(R.string.decision_type_effect),
                    ),
                )
            }
            evolutionSuggestions.forEach { s ->
                add(
                    DecisionCenterRow(
                        id = "suggestion:${s.candidateId}",
                        variant = DecisionVariant.SUGGESTION,
                        title = DecisionSpecs.suggestionTitle(s, decisionStr),
                        subtitle = appCtx.getString(
                            R.string.decision_suggest_judge,
                            appCtx.getString(recommendationRes(s.recommendation)),
                        ),
                        badge = appCtx.getString(riskBadgeRes(s.riskClass)),
                        typeLabel = appCtx.getString(R.string.decision_type_suggestion),
                    ),
                )
            }
        },
    )

    /** 按卡键取规格（决策中心点行开卡用；键不存在 ⇒ null）。 */
    fun decisionCardSpecFor(key: String): DecisionCardSpec? = when {
        key.startsWith("auth:") -> pendingChoice
            ?.takeIf { it.kind == AskKind.GROUP_AUTHORIZATION && "auth:${it.subject}" == key }
            ?.let { authorizationSpec(it) }

        key.startsWith("persona:") -> pendingChoice
            ?.takeIf { it.kind == AskKind.PERSONA_PROPOSAL && "persona:${it.subject}" == key }
            ?.let { personaSpec(it) }

        key.startsWith("effect:") -> postAdoptionQueue
            .firstOrNull { "effect:${it.eventId}" == key }
            ?.let { effectSpec(it) }

        key.startsWith("suggestion:") -> evolutionSuggestions
            .firstOrNull { "suggestion:${it.candidateId}" == key }
            ?.let { suggestionSpec(it) }

        else -> null
    }

    // ── 建议卡三决策（99 批报告 §6：采纳只走现有落盘通道） ─────────────────────

    /** 建议卡上人的三个动作（卡上永远三钮俱全，建议只是建议）。 */
    internal enum class SuggestionDecision { ADOPT, REJECT, DEFER }

    /** 高风险采纳的二次确认层（验收 #8：无确认不可采纳）。 */
    var pendingAdoptConfirm by mutableStateOf<EvolutionSuggestion?>(null)
        private set

    /**
     * 二次确认层的「采纳前」正文（IO 现读；null = 还没读出来或首写）。
     *
     * 高风险采纳的确认层要展示完整 diff（设计稿 §0 决策 #4）——「后」就是
     * [EvolutionSuggestion.payload]，「前」在这里现读盘（项目层优先、全局层兜底，
     * 与 [skillFileOf] 同一口径；人设类读 C 层正文）。
     */
    var pendingAdoptBefore by mutableStateOf<String?>(null)
        private set

    fun dismissAdoptConfirm() {
        pendingAdoptConfirm = null
        pendingAdoptBefore = null
    }

    /** 二次确认层里点「确认采纳」。 */
    fun confirmAdoptSuggestion() {
        val s = pendingAdoptConfirm ?: return
        pendingAdoptConfirm = null
        pendingAdoptBefore = null
        applySuggestionAdopt(s)
    }

    /** 打开二次确认层，并异步读「采纳前」正文（IO 不压重组）。 */
    private fun openAdoptConfirm(s: EvolutionSuggestion) {
        pendingAdoptConfirm = s
        pendingAdoptBefore = null
        scope.launch {
            pendingAdoptBefore = withContext(Dispatchers.IO) {
                runCatching {
                    when (s.kind) {
                        com.example.zhengdao.baopu.ProposalKind.SKILL_BODY -> {
                            val globalRoot = java.io.File(appCtx.filesDir, "抱朴记忆")
                            val projectRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(appCtx)
                            skillFileOf(s.target, projectRoot, globalRoot)?.readText()
                        }

                        // 2026-10-23：TOOL_RULE 采纳落点＝本项目人设（C 层），
                        // 与 PERSONA_APPEND 同一条有备份的写盘通道；采纳前也要读旧正文做对比。
                        com.example.zhengdao.baopu.ProposalKind.PERSONA_APPEND,
                        com.example.zhengdao.baopu.ProposalKind.TOOL_RULE ->
                            com.example.zhengdao.baopu.BaopuRuntime.personaFiles(appCtx)
                                .read(com.example.zhengdao.baopu.PersonaLayer.C_PROJECT)
                    }
                }.getOrNull()
            }
        }
    }

    fun decideSuggestion(candidateId: String, decision: SuggestionDecision) {
        val s = evolutionSuggestions.firstOrNull { it.candidateId == candidateId } ?: return
        when (decision) {
            SuggestionDecision.REJECT -> {
                // 出卡即离槽（队列补位）；拒绝留痕同 57 批口径：同 hash 以后不再判定
                evolutionSuggestions = evolutionSuggestions.filter { it.candidateId != candidateId }
                scope.launch {
                    withContext(Dispatchers.IO) {
                        recordSkillDecision(
                            name = s.target.ifBlank { "（人设）" },
                            adopted = false,
                            hash = s.candidateId,
                            detail = "进化建议卡拒绝",
                        )
                    }
                    auditSuggestionAction(s, "reject")
                }
                skillNote = BaopuNote(
                    appCtx.getString(R.string.decision_history_rejected, s.target),
                    BaopuNoteLevel.Info,
                )
            }

            SuggestionDecision.DEFER -> {
                // 只写审计＋记录页「搁置中」；候选仍是 PENDING（下一轮收尾再判）
                evolutionSuggestions = evolutionSuggestions.filter { it.candidateId != candidateId }
                rememberDecisionAction("defer", s.target)
                scope.launch { auditSuggestionAction(s, "defer") }
            }

            SuggestionDecision.ADOPT -> {
                if (!suggestionAdoptEnabled(s.kind)) {
                    skillNote = BaopuNote(
                        appCtx.getString(R.string.decision_suggest_adopt_blocked_tool_rule),
                        BaopuNoteLevel.Error,
                    )
                    return
                }
                if (adoptNeedsConfirm(s.riskClass)) {
                    openAdoptConfirm(s)
                    return
                }
                applySuggestionAdopt(s)
            }
        }
    }

    /**
     * 采纳一条建议 ⇒ **现有落盘通道**（99 批报告 §6）：
     * - SKILL_BODY：`SkillStore.plan/apply`（写前备份＋索引更新时挂自检）；
     * - PERSONA_APPEND：`PersonaFiles.applyProposal`（写前自动备份）。
     * 两条都不新开写盘路径；TOOL_RULE 自 2026-10-23 起也走这里（追加进 C 层人设）。
     */
    private fun applySuggestionAdopt(s: EvolutionSuggestion) {
        skillNote = BaopuNote("采纳中…", BaopuNoteLevel.Info)
        scope.launch {
            // 第一百批 §1：基线必须在**写盘之前**取（写完后读到的就是「采纳后」了）
            val baseline = if (s.kind == com.example.zhengdao.baopu.ProposalKind.SKILL_BODY) {
                withContext(Dispatchers.IO) { postAdoptionBaseline(s.target) }
            } else {
                null
            }
            val out = withContext(Dispatchers.IO) {
                when (s.kind) {
                    com.example.zhengdao.baopu.ProposalKind.SKILL_BODY ->
                        // 与学习卡**共用同一个落盘口**（writeLearnedSkill → SkillStore.plan/apply）：
                        // 99 批报告 §6「采纳只走现有落盘通道，不新开写盘路径」——把建议包成
                        // 学习卡同形状的提案，门禁（危险指令/平台事实/同名）也一并复用。
                        writeLearnedSkill(learnProposalOf(s))

                    // 2026-10-23：TOOL_RULE 也走这条人设追加通道（payload 形如
                    // 「工具使用约束：同一工具连续失败两次就停下…」），
                    // 有 PersonaFiles 的写前自动备份；不新开写盘路径。
                    com.example.zhengdao.baopu.ProposalKind.PERSONA_APPEND,
                    com.example.zhengdao.baopu.ProposalKind.TOOL_RULE -> appendSuggestionPersona(s)
                }
            }
            if (out.level != BaopuNoteLevel.Error) {
                withContext(Dispatchers.IO) {
                    recordSkillDecision(
                        name = s.target.ifBlank { "（人设）" },
                        adopted = true,
                        hash = s.candidateId,
                        detail = "进化建议卡采纳",
                    )
                    // 真落了盘才记采纳事件 ⇒ 100 的效果回环接着盯它（同学习卡口径）
                    if (s.kind == com.example.zhengdao.baopu.ProposalKind.SKILL_BODY && baseline != null) {
                        val globalRoot = java.io.File(appCtx.filesDir, "抱朴记忆")
                        val projectRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(appCtx)
                        recordPostAdoption(
                            channel = com.example.zhengdao.baopu.PostAdoptionChannel.PROPOSAL_CANDIDATE,
                            candidateId = s.candidateId,
                            skillName = s.target,
                            file = skillFileOf(s.target, projectRoot, globalRoot),
                            baseline = baseline,
                        )
                    }
                }
                rememberDecisionAction("adopt", s.target)
                auditSuggestionAction(s, "adopt")
            }
            evolutionSuggestions = evolutionSuggestions.filter { it.candidateId != s.candidateId }
            skillNote = out
        }
    }

    /**
     * 把一条技能正文建议包成**学习卡同形状**的提案（落盘与门禁全部复用
     * [writeLearnedSkill]：`SkillStore.plan/apply`＋写前备份＋索引自检）。
     *
     * id 取 `suggestion-<target>`：[com.example.zhengdao.baopu.SkillRefine.nameOf] 按
     * `-` 后半段取名 ⇒ 正好是候选的 target；正文走 [com.example.zhengdao.baopu.SkillRefine.skillBodyFor]
     * 的结构化包装（场景＝payload）——与 103 批 EVO-D1 的标题/正文自检同一条路。
     */
    private fun learnProposalOf(s: EvolutionSuggestion): com.example.zhengdao.baopu.SkillRefine.LearnProposal =
        com.example.zhengdao.baopu.SkillRefine.LearnProposal(
            id = "suggestion-${s.target}",
            content = s.payload,
            source = "进化建议卡",
            suggest = com.example.zhengdao.baopu.SkillRefine.Suggest.DRAFT,
            fromUser = false,
        )

    /** 把一条人设追加建议写进 C 层（通道＝`PersonaFiles.applyProposal`，写前自动备份）。 */
    private fun appendSuggestionPersona(s: EvolutionSuggestion): BaopuNote {
        val pf = com.example.zhengdao.baopu.BaopuRuntime.personaFiles(appCtx)
        val current = pf.read(com.example.zhengdao.baopu.PersonaLayer.C_PROJECT).orEmpty()
        val proposed = if (current.isBlank()) s.payload else current.trimEnd() + "\n\n" + s.payload
        val p = com.example.zhengdao.baopu.PersonaProposal(
            layer = com.example.zhengdao.baopu.PersonaLayer.C_PROJECT,
            current = current,
            proposed = proposed,
            reason = "进化建议卡采纳",
        )
        return if (pf.applyProposal(p)) {
            BaopuNote("已追加进人设（旧版已自动备份，可回滚）", BaopuNoteLevel.Info)
        } else {
            BaopuNote("没能写进人设（可能超上限或被规则挡下）——一个字节都没动", BaopuNoteLevel.Error)
        }
    }

    /** 建议卡动作审计（99 批报告 §6 定的格式；失败只 log，不回滚用户的选择）。 */
    private fun auditSuggestionAction(s: EvolutionSuggestion, action: String) {
        runCatching {
            com.example.zhengdao.baopu.EvolutionAuditLog
                .under(java.io.File(appCtx.filesDir, "抱朴记忆"))
                .recordAction(
                    candidateId = s.candidateId,
                    action = action,
                    riskClass = s.riskClass.name,
                    recommendation = s.recommendation.name,
                )
        }.onFailure {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴：建议动作审计没记上（${action}/${s.candidateId}）${it.javaClass.simpleName}: ${it.message}",
            )
        }
    }

    // ── 决策中心·记录页（撤销仅可回退操作；验收 #9/#24） ──────────────────────

    /** 本进程里发生过的建议/效果处置（记录页「搁置中/先留着/稍后处理/已回滚」行）。 */
    private val decisionActionLog = mutableStateOf(listOf<DecisionActionEntry>())

    private data class DecisionActionEntry(val action: String, val name: String, val atMs: Long)

    /** 记一条进程内处置（出卡即离槽的那些：defer/keep/later/rollback/adopt）。 */
    private fun rememberDecisionAction(action: String, name: String) {
        decisionActionLog.value = decisionActionLog.value + DecisionActionEntry(
            action = action,
            name = name,
            atMs = System.currentTimeMillis(),
        )
    }

    /** 本进程里撤销过的授权（「已撤销」行；跨进程不保留——账本在 [ToolGroupAudit]）。 */
    private val undoneGrants = mutableStateOf(listOf<String>())

    /**
     * 记录页数据（四来源，全部只读现算）：
     * ① 会话授权（`toolUnlocks`，可撤销＝还回「已锁」）；
     * ② 长期授权（`prefs.approvedGroups`，可撤销＝还回「已锁」，不是「关掉」）；
     * ③ 技能决策行（`skill_event` 的 decision 行：已应用/已拒绝建议）；
     * ④ 进程内处置（搁置/先留着/稍后处理/已回滚/已采纳）。
     */
    fun decisionHistoryRows(): List<DecisionCenterRow> {
        val out = mutableListOf<DecisionCenterRow>()
        val prefs = com.example.zhengdao.baopu.BaopuRuntime.toolPrefs(appCtx)
        val sessionIdNow = sessionId ?: -1L
        val sessionGroups = runCatching {
            com.example.zhengdao.baopu.BaopuRuntime.toolUnlocks(appCtx).unlocked(sessionIdNow)
        }.getOrDefault(emptySet())
        val approved = prefs.approvedGroups()
        approved.forEach { g ->
            out += DecisionCenterRow(
                id = "undo:always:${g.key}",
                variant = DecisionVariant.AUTHORIZATION,
                title = appCtx.getString(R.string.decision_history_grant_always, appCtx.getString(baopuGroupTitle(g))),
                undoable = true,
            )
        }
        (sessionGroups - approved).forEach { g ->
            out += DecisionCenterRow(
                id = "undo:session:${g.key}",
                variant = DecisionVariant.AUTHORIZATION,
                title = appCtx.getString(R.string.decision_history_grant_session, appCtx.getString(baopuGroupTitle(g))),
                undoable = true,
            )
        }
        undoneGrants.value.forEachIndexed { i, name ->
            out += DecisionCenterRow(
                // 行键必须唯一：同一个组先撤销、再授权、又撤销 ⇒ 两条「已撤销」
                // （LazyColumn 同键会崩）⇒ 带序号
                id = "undone:$i:$name",
                variant = DecisionVariant.AUTHORIZATION,
                title = appCtx.getString(R.string.decision_history_undone_row, name),
            )
        }
        // ③ 技能决策行（DB；含学习卡与建议卡的采纳/拒绝留痕）
        runCatching {
            db.read { d ->
                d.rawQuery(
                    "SELECT name, ok, at FROM skill_event WHERE kind = 'decision' ORDER BY id DESC LIMIT 20",
                    emptyArray(),
                ).use { c ->
                    var i = 0
                    while (c.moveToNext()) {
                        val name = c.getString(0).orEmpty()
                        val ok = c.getInt(1) != 0
                        val at = c.getLong(2)
                        out += DecisionCenterRow(
                            // 同毫秒同名的两行也要能区分 ⇒ 带序号
                            id = "decision:$i:$at:$name",
                            variant = DecisionVariant.SUGGESTION,
                            title = appCtx.getString(
                                if (ok) R.string.decision_history_applied else R.string.decision_history_rejected,
                                name,
                            ),
                        )
                        i++
                    }
                }
            }
        }
        // ④ 进程内处置
        decisionActionLog.value.forEachIndexed { i, e ->
            val res = when (e.action) {
                "defer" -> R.string.decision_history_deferred
                "keep" -> R.string.decision_history_kept
                "later" -> R.string.decision_history_later
                "rollback" -> R.string.decision_history_rolled_back
                "adopt" -> R.string.decision_history_applied
                else -> null
            } ?: return@forEachIndexed
            out += DecisionCenterRow(
                id = "action:$i:${e.atMs}:${e.action}",
                variant = if (e.action == "keep" || e.action == "later" || e.action == "rollback") {
                    DecisionVariant.POST_ADOPTION
                } else {
                    DecisionVariant.SUGGESTION
                },
                title = appCtx.getString(res, e.name),
            )
        }
        return out
    }

    /**
     * 撤销一条授权（记录页；**仅可回退操作**——不制造新待处理）。
     *
     * 会话授权 ⇒ 还回「已锁」（本会话不再可用，模型下次要再申请）；
     * 长期授权 ⇒ 还回「已锁」（不是「关掉」——关掉是设置页那个用户开关，
     * 连卡都不弹；撤销只是收回「同意」这件事本身）。
     */
    fun undoDecisionGrant(id: String) {
        val key = when {
            id.startsWith("undo:session:") -> id.removePrefix("undo:session:")
            id.startsWith("undo:always:") -> id.removePrefix("undo:always:")
            else -> return
        }
        val group = com.example.zhengdao.baopu.agent.ToolGroup.fromKey(key) ?: return
        val prefs = com.example.zhengdao.baopu.BaopuRuntime.toolPrefs(appCtx)
        val scope = if (id.startsWith("undo:always:")) "长期" else "本次会话"
        if (id.startsWith("undo:always:")) {
            prefs.setGroupApproved(group, false)
            prefs.setGroupDisabled(group, false)
        }
        com.example.zhengdao.baopu.BaopuRuntime.toolUnlocks(appCtx).relockAll(listOf(group))
        com.example.zhengdao.baopu.agent.ToolGroupAudit.undo(group, scope)
        val name = appCtx.getString(baopuGroupTitle(group))
        undoneGrants.value = undoneGrants.value + name
    }

    /** 动作结果的一句话（确认失败/重复等；给 UI 显示，5 秒后由 UI 清）。 */
    var pendingNote by mutableStateOf<String?>(null)
        private set

    // ── 确认制（刀 3 A 段） ──────────────────────────────────────────────

    /**
     * 文件改动确认卡（要动文件时出；批准/拒绝后清）。
     *
     * 两个来源：① 引擎的 [ConfirmRequest] 事件（在线挂起——活的卡）；
     * ② 构造时从 session 的 pending 三列重建（进程被杀后重进——恢复的卡，
     * 见 [restoreConfirmCard]）。[confirmRestored] 记是哪一种，批准/拒绝
     * 据此走不同的引擎入口（活的等挂起苏醒；恢复的直接执行）。
     */
    var confirmCard by mutableStateOf<ConfirmRequest?>(null)
        private set

    /** 当前卡是重启重建的（没有活的挂起可苏醒）⇒ 批准/拒绝走恢复路径。 */
    private var confirmRestored = false

    // ── 资料库（A 段收料提问卡 / D 段导出 / 顶条统计）─────────────────────────

    /**
     * 收料提问卡（投递里有料时出；答完即清）。
     *
     * 卡由 [com.example.zhengdao.baopu.library.LibraryImport.card] 造（纯函数），
     * 壳层只负责出示与回收——**形状判据全在数据层**（AskUserCardRules），
     * UI 不自己发明选项（判据 §5.7）。
     */
    var askCard by mutableStateOf<AskUserCard?>(null)
        private set

    /**
     * 资料库动作的一句话结果（收料/导出；成功失败都明说，不静默）。
     *
     * 第六十一批 §2：带级别——进度（收料处理中…）8 秒自清，错误（选项对不上、
     * 什么都没做成、有件被拒）常驻到用户点掉。
     */
    var libraryNote by mutableStateOf<BaopuNote?>(null)
        private set

    /** 顶条防丢提醒要的两个数（[BackupStats.lastExportAt] ≤0 = 从没导出过）。 */
    data class BackupStats(val lastExportAt: Long, val unexported: Int, val total: Int)

    var backupStats by mutableStateOf(BackupStats(0L, 0, 0))
        private set

    /** 导出是否在跑（备份对话框按钮的禁用态；顶条「去导出」也看它）。 */
    var exportBusy by mutableStateOf(false)
        private set

    /**
     * 已出过卡（或已答过）的投递签名——防同一批料反复问。
     *
     * 签名 = 件数:文本字节:mtime 之和。用户答完（含「先不搬」）后签名不变就不再问；
     * 真往投递里丢了新文件 ⇒ 签名变 ⇒ 再问。没有签名机制，搬完那一刻投递还在
     * （搬＝复制进私有＋列待删除清单，不删源），卡会立刻重新弹出来。
     */
    private var deliverSignature: String? = null

    /** 首次搬原件进私有的一次性提示Flag（D 段第 2 条；记在库里，同一件事只提示一次）。 */
    private val noticeFirstMove = "first-move"

    /** 引擎可观察状态（直接透出 Compose State）。 */
    val engineState: androidx.compose.runtime.State<RunState> get() = engine.state
    val engineStep: androidx.compose.runtime.State<Int> get() = engine.step
    val engineNotice: androidx.compose.runtime.State<String?> get() = engine.notice

    /** `ask_user` 的选项卡（输入栏上方那张卡）；null = 没有卡。 */
    val pendingChoice: com.example.zhengdao.baopu.agent.AskUserRequest? get() = engine.choice.value

    /** 用户点了一个选项。 */
    fun answerChoice(id: String) = engine.answerChoice(id)

    // ── 「这次学到」提案卡（第三十七批 B 段）─────────────────────────────
    //
    // 用户 2026-10-19 口径：**收尾才出卡、只出卡不落盘、点采纳才写、拒绝一字不动**。

    /**
     * 引擎收尾给的提案（null = 现在没有卡 / 全部不合格）。
     *
     * 第一百零三批 §B②：**前置校验在这里**——每条候选真跑 `SkillBodySelfCheck`
     * （[com.example.zhengdao.baopu.SkillRefine.learnPrecheckIssues]），不合格的
     * 不进卡（用户看不到采纳按钮＝「不出采纳卡」），只落一条审计（同一 id 同一套原因只记一次）。
     * 全都不合格 ⇒ 返回 null（不出空卡：空壳比没卡更像坏了）。
     */
    val learnProposals: List<com.example.zhengdao.baopu.SkillRefine.LearnProposal>?
        get() {
            val raw = engine.learn.value ?: return null
            val kept = raw.filter { p ->
                val issues = com.example.zhengdao.baopu.SkillRefine.learnPrecheckIssues(p)
                if (issues.isEmpty()) {
                    true
                } else {
                    auditLearnPrecheck(p, issues)
                    false
                }
            }
            return kept.ifEmpty { null }
        }

    /**
     * 第一百零三批 §B②：采纳/出卡前的前置校验（不合格 ⇒ 审计＋一句人话，返回 false）。
     *
     * 抽出来是为了让 [adoptLearn] 主路径保持「取基线 → 写盘」两行之内
     * （既有门禁 `BaopuLearnCardGuardTest` 用 12 行窗钉这点，别把它挤出去）。
     */
    private fun precheckOrRefuse(
        p: com.example.zhengdao.baopu.SkillRefine.LearnProposal,
        prefix: String = "这条候选本身不合格，没有写盘",
    ): Boolean {
        val issues = com.example.zhengdao.baopu.SkillRefine.learnPrecheckIssues(p)
        if (issues.isEmpty()) return true
        auditLearnPrecheck(p, issues)
        skillNote = BaopuNote("$prefix：${issues.joinToString("；")}", BaopuNoteLevel.Error)
        return false
    }

    /** 前置校验不过的候选已记过审计的键（id＋原因；同一套只记一次，不刷日志）。 */
    private val learnPrecheckAudited = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 上一次「坏候选没给出」已经提示过的那批（同批只提示一次，不每轮重弹）。 */
    private var learnFilteredNoted: String? = null

    /**
     * 第一百零三批 §B②：坏候选**不出卡**，但用户要能看到一句人话原因。
     *
     * 只在收尾（Settled）看一次：引擎这一轮确实产了候选、且其中有过不了前置校验的，
     * 用既有的 `skillNote`（技能动作的一句话结果）说清是哪条、为什么——同一条不重复说。
     */
    private fun noteFilteredLearn() {
        val raw = engine.learn.value ?: return
        val bad = raw.mapNotNull { p ->
            val issues = com.example.zhengdao.baopu.SkillRefine.learnPrecheckIssues(p)
            if (issues.isEmpty()) null else p to issues
        }
        if (bad.isEmpty()) {
            learnFilteredNoted = null
            return
        }
        val sig = bad.joinToString("|") { (p, issues) -> p.id + ":" + issues.joinToString("；") }
        if (sig == learnFilteredNoted) return
        learnFilteredNoted = sig
        val (p, issues) = bad.first()
        skillNote = BaopuNote(
            "这次「学到」有 ${bad.size} 条没给出来（" +
                "${com.example.zhengdao.baopu.SkillRefine.nameOf(p)}：${issues.joinToString("；")}）" +
                "——修好再记，或直接告诉我要记什么。",
            BaopuNoteLevel.Error,
        )
    }

    /** 前置校验不过 ⇒ 一条审计（`[skill-body-selfcheck]` 锚，与 v2 自检同一个 grep 口）。 */
    private fun auditLearnPrecheck(
        p: com.example.zhengdao.baopu.SkillRefine.LearnProposal,
        issues: List<String>,
    ) {
        val key = p.id + "|" + issues.joinToString("；")
        if (learnPrecheckAudited.add(key)) {
            com.example.zhengdao.rootfs.RunLog.log(
                "[skill-body-selfcheck] 采纳卡前置校验不过（${p.id}）：${issues.joinToString("；")}",
            )
        }
    }

    /** 用户点了「不记」⇒ 只把卡收起来，**一个字节都不写**。 */
    fun dismissLearn() {
        // 第五十七批 §1.3：整卡「不记」= 全部候选记成丢弃留痕（下轮提炼不再重复提）。
        engine.learn.value?.forEach { p ->
            recordSkillDecision(
                name = com.example.zhengdao.baopu.SkillRefine.nameOf(p),
                adopted = false,
                hash = com.example.zhengdao.baopu.SkillRefine.dedupeKeyOf(p),
                detail = null,
            )
        }
        engine.dismissLearn()
    }

    /**
     * 采纳一条 ⇒ **唯一落盘路径**：`SkillStore.plan` → `SkillStore.apply`
     * （写前先备份、进索引并标「草稿·未验证」）。没点采纳就什么都不写。
     */
    fun adoptLearn(id: String) {
        val list = engine.learn.value ?: return
        val p = list.firstOrNull { it.id == id } ?: return
        // 第一百零三批 §B②：卡上已经滤过一遍，这里再拒一次（卡可能过期/被旧版本填过）；
        // 拒在取基线之前、写盘之前（J7 的顺序不会被打乱）。
        if (!precheckOrRefuse(p)) return
        skillNote = BaopuNote("记下来中…", BaopuNoteLevel.Info)
        scope.launch {
            val name = com.example.zhengdao.baopu.SkillRefine.nameOf(p)
            // 第一百批 §1：基线必须**写盘之前**取（写完之后再读就是「采纳后」了，口径会反）
            val baseline = withContext(Dispatchers.IO) { postAdoptionBaseline(name) }
            val out = withContext(Dispatchers.IO) { writeLearnedSkill(p) }
            // 第五十七批 §1.3：采纳留痕（新草稿的 uses 头从 0 起算——试用期从采纳起算）
            recordSkillDecision(
                name = name,
                adopted = true,
                hash = com.example.zhengdao.baopu.SkillRefine.dedupeKeyOf(p),
                detail = null,
            )
            // 第一百批 §1：**真落了盘**才记采纳事件。判据用便签级别：writeLearnedSkill 只有
            // 「成」才给 Info，被门禁挡住/自检不过（含已回滚）一律 Error——那种情形等于没采纳。
            if (out.level != BaopuNoteLevel.Error) {
                withContext(Dispatchers.IO) {
                    val globalRoot = File(appCtx.filesDir, "抱朴记忆")
                    val projectRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(appCtx)
                    recordPostAdoption(
                        channel = com.example.zhengdao.baopu.PostAdoptionChannel.LEARN_CARD,
                        candidateId = p.id,
                        skillName = name,
                        file = skillFileOf(name, projectRoot, globalRoot),
                        baseline = baseline,
                    )
                }
            }
            skillNote = out
            engine.dismissLearn() // 采纳完就收卡，不重复写（收卡这里不记丢弃——上面记过采纳了）
        }
    }

    /** 卡片要用「已有技能名」判同名冲突（引擎出卡时的快照，纯展示用）。 */
    val learnExistingNames: Set<String> get() = engine.learnExistingNames

    /** 采纳前的三道门禁（危险指令／平台事实没标未实测／同名冲突），IO 线程现读，纯本地判断。 */
    private fun reviewBeforeWrite(
        p: com.example.zhengdao.baopu.SkillRefine.LearnProposal,
        root: java.io.File,
    ): com.example.zhengdao.baopu.SkillRefine.LearnReview {
        val existing = runCatching {
            com.example.zhengdao.baopu.SkillRules.projectSkillDir(root).listFiles()
                ?.filter { it.isDirectory }?.map { it.name }?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
        return com.example.zhengdao.baopu.SkillRefine.reviewLearn(p, existing)
    }

    /** 把卡上一条提案写成技能（草稿/转正式按卡上的建议）。 */
    private fun writeLearnedSkill(p: com.example.zhengdao.baopu.SkillRefine.LearnProposal): BaopuNote {
        val root = com.example.zhengdao.baopu.BaopuWorkspace.hostDir(appCtx)
        val pf = com.example.zhengdao.baopu.BaopuRuntime.personaFiles(appCtx)
        val globalRoot = java.io.File(pf.globalFile().parentFile ?: root, "")
        // C 段硬闸：卡上标红只是提示，**真落盘前再查一次**（卡可能已经过期）
        val review = reviewBeforeWrite(p, root)
        if (!review.ok) return BaopuNote("不能写：${review.summary}", BaopuNoteLevel.Error)
        val name = com.example.zhengdao.baopu.SkillRefine.nameOf(p)
        val proposal = com.example.zhengdao.baopu.SkillProposal(
            scope = com.example.zhengdao.baopu.SkillScope.PROJECT,
            name = name,
            body = com.example.zhengdao.baopu.SkillRefine.skillBodyFor(p),
            reason = "「这次学到」卡片采纳",
            draft = p.suggest != com.example.zhengdao.baopu.SkillRefine.Suggest.FORMAL,
        )
        val plan = com.example.zhengdao.baopu.SkillStore.plan(
            proposal,
            root,
            globalRoot,
            com.example.zhengdao.util.TimeFormats.fileStamp(),
        )
        plan.blocked?.let { return BaopuNote(it, BaopuNoteLevel.Error) }
        val layer = com.example.zhengdao.baopu.PersonaLayer.C_PROJECT
        // 第五十批：落盘**前**记快照（sha256 + 文件在不在）——回滚要核的就是这一刻
        val skillTarget = TargetSnapshot.of(plan.file, TargetKind.SKILL)
        val personaTarget = TargetSnapshot.of(pf.projectFile(), TargetKind.PERSONA)
        return when (val r = com.example.zhengdao.baopu.SkillStore.apply(plan, pf, pf.read(layer).orEmpty())) {
            is com.example.zhengdao.baopu.SkillWriteResult.Written -> {
                // 技能正文本身不在 v1 的 eval 覆盖内（现 10 例不测技能正文）；
                // 只有**索引（B/C 人设）被改**时，这份新索引才值得跑一次自检。
                if (r.indexUpdated) {
                    scheduleSelfCheck(
                        scene = "技能提案（索引已改）",
                        targets = listOf(skillTarget, personaTarget),
                    )
                }
                // 派工单 84 批 v1 适配项：main 上返回值从 String 改成 BaopuNote（信息便签）
                val written = BaopuNote(
                    "已记下：${r.file.name}" + (r.backup?.let { "（旧版备份：$it）" } ?: ""),
                    BaopuNoteLevel.Info,
                )
                // 第八十四批 v2：**正文级**自检（派工单 §0.1）。默认不跑（SelfCheckGate），
                // 跑了也不过是本地四条判据 + 失败回滚，不调模型、不要 key、不新增备份通道。
                val guarded = skillBodySelfCheck(
                    scene = "技能正文（${r.file.name}）",
                    target = skillTarget,
                    name = name,
                    file = r.file,
                ) ?: return written
                return when (guarded) {
                    is SkillBodyOutcome.FailedRolledBack -> BaopuNote(
                        "已写但自检不过（${guarded.reason}），已回滚（sha256 与落盘前一致）",
                        BaopuNoteLevel.Error,
                    )

                    is SkillBodyOutcome.RollbackFailed -> BaopuNote(
                        "已写但自检不过（${guarded.reason}），回滚也没成功：${guarded.paths.joinToString("、")}——请手动核对",
                        BaopuNoteLevel.Error,
                    )

                    SkillBodyOutcome.Passed -> written
                }
            }
            is com.example.zhengdao.baopu.SkillWriteResult.Rejected ->
                BaopuNote(r.reason, BaopuNoteLevel.Error)
        }
    }

    /**
     * 第八十四批 v2：技能正文自检（[SkillBodyGuard]）。返回 null = 闸门关着，没跑。
     *
     * 闸门默认关（[SelfCheckGate]）：调试构建/CI 开箱即跑，Release 要用户自己拨开关。
     */
    private fun skillBodySelfCheck(
        scene: String,
        target: TargetSnapshot,
        name: String,
        file: java.io.File,
    ): SkillBodyOutcome? {
        if (!SelfCheckGate.enabledNow(com.example.zhengdao.BuildConfig.DEBUG, appCtx)) return null
        val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        // 第一百零三批 §B：`SkillStore` 落盘时给草稿前置了机器头（`<!-- status=draft uses=0 -->`），
        // 而结构自检把「第一个非空行」当标题 ⇒ 草稿永远判「缺标题段」、采纳永远回滚
        // （真机 EVO-D1 逐字如此）。机器头是纯注释、不是正文 ⇒ 喂判据前去掉它（判据本体不动）。
        val body = com.example.zhengdao.baopu.SkillRefine.contentWithoutMachineHeader(raw)
        val input = SkillBodyInput(
            name = name,
            body = body,
            backups = com.example.zhengdao.baopu.SkillStore.backupsOf(file).size,
            existed = target.existed,
        )
        return skillBodyGuard.execute(scene, target, input)
    }

    /** 会话中（可停止）。 */
    val busy: Boolean
        get() = engineState.value in setOf(
            RunState.THINKING, RunState.ACTING, RunState.COMPRESSING, RunState.AWAIT_CONFIRM,
        )

    /**
     * 本会话还没开跑的排队条数（第一百一十七批：输入栏上方那一行）。
     *
     * 真源在引擎内存队列（同步可见），这里只是把 `QueueChanged` / `refresh` 的读数
     * 摊成 Compose 状态；**不是**另一本账，切会话/新会话都按引擎的读数重写。
     */
    var queuedCount by mutableIntStateOf(0)
        private set

    /**
     * 有「要人动手」的卡在 ⇒ 最近一轮不许收（十六批第一件 C 段：确认卡/问人卡/
     * 待过目候选卡都是要人点的东西，它们所在的轮收起来就是事故）。
     *
     * 口径说明：确认卡与问人卡跟着当前 run 走（[confirmCard] / [askCard]）；
     * 待过目候选（[pendingMemories]）本轮刚产、等用户点。刀 0b 的老候选
     * （[memoryCandidates]）可跨轮积压多日，**不**参与钉住——不然最近一轮
     * 永远收不起来；卡片本体渲染在消息流之外，本就不受收纳影响。
     */
    val actionCardsPresent: Boolean
        // 2026-10-18 修：这里原来还带着 `|| pendingMemories.isNotEmpty()`，与上面自己写的
        // 口径互相打架（候选卡渲染在消息流之外，钉住那一轮毫无意义），后果是**只要有一条
        // 没点的记忆候选，最近一轮就永远收不起来**——用户点的正是这句「干了几步点开后
        // 没法收回」。钉住只留给「跟当前 run 走」的两张卡。
        get() = confirmCard != null || askCard != null

    /**
     * 提炼游标（防同一轮双提炼）：记下「哪个会话、已经提炼到第几条用户消息」。
     *
     * 为什么需要：[EngineEvent.Settled] 每轮发一次，但同一会话里用户会连发多轮；
     * 没有游标就会把老用户的原话反复提炼（候选 id 含时间戳，body 去重能挡住一部分，
     * 但「这次学到什么」的自省会重复刷）。游标只认**新增**的用户消息。
     */
    private var refineMarkSession = -1L
    private var refineMarkUserCount = -1

    // ── 情景记忆（147）：Settled ⇒ 日记 upsert（结构化拼装，无模型文本）────────
    //
    // 与提炼游标同款手法：记「哪个会话、已经记到第几行 run_log」，只认基线之后
    // 的新增行——老历史不回填（日记从本功能落地后的新活动开始记，如实）。
    // 活动判据是**结构化**的（判据 3）：本轮有成功工具步（stepRows 增量里 ok=true）
    // 或有新增产物（engine.roundArtifacts），两者皆无 ⇒ 不记，闲聊不产生日记。

    private var diaryMarkSession = -1L
    private var diaryMarkSteps = 0

    /** 跑前基线：用户消息落库时标定（此刻 stepRows 还不含本轮）。 */
    private fun diaryMarkBeforeRun() {
        val sid = sessionId ?: return
        if (diaryMarkSession != sid) {
            diaryMarkSession = sid
            diaryMarkSteps = stepRows.size
        } else if (diaryMarkSteps > stepRows.size) {
            diaryMarkSteps = stepRows.size
        }
    }

    private fun diaryOnSettle() {
        val sid = sessionId ?: return
        if (diaryMarkSession != sid) {
            // 没经过本控制器跑前标定（如切回老会话后直接收尾）⇒ 只标定不回填。
            diaryMarkSession = sid
            diaryMarkSteps = stepRows.size
            return
        }
        val delta = stepRows.drop(diaryMarkSteps.coerceAtMost(stepRows.size))
        diaryMarkSteps = stepRows.size
        val okSteps = delta.count { it.ok }
        val newArtifacts = DiaryRules.sanitizeArtifacts(engine.roundArtifacts.value)
        if (!DiaryRules.hasActivity(okSteps, newArtifacts.size)) return // 判据 3：结构化无产出不记

        val now = System.currentTimeMillis()
        val date = DiaryRules.dateOf(now)
        // 157：日记根随记忆目录走 `<工作区>/记忆/日记/`（MemoryLayout 单一真源）。
        val wsRoot = BaopuRuntime.workspaceRoot(appCtx)
        val store = DiaryStore(MemoryLayout.memoryDir(wsRoot), db)
        val prev = store.loadDay(date).firstOrNull { it.id == DiaryRules.diaryId(sid, date) }
        // 当日累计（同会话同日多轮合并；provenance 六字段原样带回，见 mergeDailyEntry，
        // MEM-D1：原来 timeToMs 写 now，第二次起会被 amendGuard 全拒）。
        val entry = DiaryRules.mergeDailyEntry(
            prev = prev,
            id = DiaryRules.diaryId(sid, date),
            date = date,
            sessionId = sid,
            now = now,
            firstActivityAt = delta.firstOrNull()?.createdAt ?: now,
            okDelta = okSteps,
            failedDelta = delta.size - okSteps,
            newArtifacts = newArtifacts,
        )
        // 157 四步：①写前备份（App 私有 `filesDir/抱朴记忆/备份/` 快照，保留最近 N 份，
        // 不进工作区、模型只读）→ ②当日已有条目走 **amend**（provenance 六字段闸：
        // id/date/sessionId/timeFromMs/createdAt 不许改；首条走 upsert）→ ③索引增量替换
        // ＋ 全量重建（文件是真相，索引只是加速）→ ④收尾维护。
        runCatching {
            MemoryBackup(MemoryLayout.memoryBackupDir(appCtx.filesDir))
                .snapshot(MemoryLayout.memoryDir(wsRoot), now)
        }.onFailure {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴日记：写前备份失败 ${it.javaClass.simpleName}: ${it.message}（仍继续写日记）",
            )
        }
        val res = if (prev != null) store.amend(entry) else store.upsert(entry)
        if (res.ok) {
            DiaryIndexStore(db).replace(entry)
            store.rebuildIndex()
            // 147 ②：收尾顺手维护（浓缩/归档/清理）——判据在 DiaryRecall（纯函数），
            // 失败不阻塞会话收尾（try/catch ＋ 运行日志，同 MemoryStore 既有风格）。
            runCatching { store.maintain(now) }
                .onFailure {
                    com.example.zhengdao.rootfs.RunLog.log(
                        "抱朴日记：维护失败 ${it.javaClass.simpleName}: ${it.message}",
                    )
                }
        } else {
            com.example.zhengdao.rootfs.RunLog.log("抱朴日记：$res.reason")
        }
    }

    /** 待过目候选的进程内上限（UI 同屏放得下 3 张；超了先到先得，截断记日志）。 */
    private val maxPending = 6

    // ── 技能沉淀（第十九批）：封卷提炼的提案，等用户在确认卡上拍板 ──────────

    /**
     * 技能索引提案（PersonaProposal，layer=B/C，PENDING）：封卷提炼与启动补算产出，
     * 用户「采纳」⇒ 走 [PersonaFiles.applyProposal] 落盘（唯一合法写盘口，K4）；
     * 「丢弃」⇒ 只从列表移除（本来就是内存态，没有文件可删）。
     */
    var pendingSkillProposals by mutableStateOf<List<com.example.zhengdao.baopu.PersonaProposal>>(emptyList())
        private set

    /**
     * 技能动作的一句话结果（上限拒绝 / 写盘失败 / 记下来中…／已记下）——
     * 第六十一批 §2：出错那一档常驻、点掉才清，进度那一档仍是 8 秒自己走。
     */
    var skillNote by mutableStateOf<BaopuNote?>(null)
        private set


    // ⚠️ 这两行必须声明在 init **之前**：init 里的 refresh() → restoreCompression()
    // 会写 compressionNote；Kotlin 按声明顺序初始化属性，声明在后面时委托对象还是 null
    // ⇒ 进程重进时（库里有压缩记录）一开抱朴页就 NPE 崩（2026-10-18 真机 T2 抓出）。
    /** 已压缩一条的展示数据（界面细条用；null = 还没压过，不显示）。 */
    var compressionNote by mutableStateOf<CompressionNote?>(null)
        private set

    /** 界面细条的数据（条数 + 摘要全文）。 */
    data class CompressionNote(val count: Int, val summary: String)

    /**
     * 全会话的 run_log 活动行（十六批第一件 · 步骤卡汇总行的数据源）。
     *
     * 只读：引擎照旧自己写 run_log，壳层只 SELECT；轮窗口切片与汇总判据
     * 全在 [BaopuMessageList.kt] 的纯函数里（J1–J7 JVM 直测）。
     */
    var stepRows by mutableStateOf<List<BaopuStepRow>>(emptyList())
        private set

    // ── 本轮用量 ＋ 本轮产物（第三十八批 A/B 段）──────────────────────────

    /**
     * 本轮的用量账（累计 ＋ 每步明细）——顶栏跑马灯「本轮用量」的唯一数据源。
     *
     * 三个数都在 [accumulateUsage] 里长（**纯函数，JVM 直测 A1/A2/A4**），控制器只做
     * 「收到 ⇒ 换一份 state」这一件事。为什么不是从 `stepRows` 反推：run_log 的
     * `latency_ms` 是**工具**耗时，模型那几步花了多久与 token 数都不在那条 SELECT 里。
     *
     * 线程：写它的是 `engine.onUsage` 回调，跑在 `zd-baopu-run` 上——`mutableStateOf`
     * 可跨线程写（本仓既有口径），所以回调体里不改别的、不读盘、不起线程（判据 A6）。
     */
    var roundUsage by mutableStateOf(emptyRoundUsage())

    /** 顶栏「玄览正在干什么」那一行（2026-10-19）；`Settled` 时清空。 */
    var browserActivity by mutableStateOf<String?>(null)

    /** `临时/` 还剩几个文件（收尾时由引擎事件告知）。 */
    var scratchLeft by mutableIntStateOf(0)
        private set

    /**
     * 本轮够格的文本产物（相对路径，写入顺序）。
     *
     * 引擎那份 [com.example.zhengdao.baopu.agent.RunEngine.roundArtifacts] 的只读透传：
     * 组合里读它会随引擎写入自动重组（同一个 `mutableStateOf`，别再抄一份进控制器——
     * 抄一份就有两个真源，产物行会比实际落盘慢一拍）。
     */
    val roundArtifacts: List<String> get() = engine.roundArtifacts.value

    /** 现在点开的是哪个产物（null = 查看器没开）。只读查看器自己不开，由产物行点片写。 */
    var viewingArtifact by mutableStateOf<String?>(null)
        private set

    fun openArtifact(relPath: String) {
        viewingArtifact = relPath
    }

    fun closeArtifact() {
        viewingArtifact = null
    }

    /**
     * 回到「本轮还没有用量」的样子（判据 A4 的落点）。
     *
     * 三件事一起做：清累计与每步列表、请引擎清产物视图、关掉还开着的查看器。
     * 调用时机 = **新开一轮**（[send]）与**换会话**（[newSession] / [openSession] /
     * [clearSessionView]）——后三个是顺手补的：上个会话的「本轮用量」赖在顶栏
     * 跑马灯上，切过去就能看到一串不属于这个会话的数，那是假账。
     */
    private fun resetRoundUsage() {
        roundUsage = emptyRoundUsage()
        engine.clearRoundArtifactView()
        viewingArtifact = null
    }

    init {
        engine.onEvent = { ev -> onEngineEvent(ev) }
        // 第三十八批 A 段：把「每步拿到 usage 就回调一次」挂到本轮用量账上。
        // 判据 A6 钉的就是**这个 lambda 的实现体**：它跑在 `zd-baopu-run` 线程上，
        // 里面只许改 `mutableStateOf`——出现 `File(`／`Thread(`／`Dispatchers` 就是
        // 拿后台线程读盘或另起线程（BaopuCostAndArtifactsTest 的 A6 用源码级用例拦它，
        // 并配判别器自证：往体里塞一行 `File("x").readText()` 必须报红）。
        //
        // 三列**照抄可空**（null = 服务端没测到）：不在这里 `?: 0` 把它坐实成 0，
        // 显示层（usageTotalLine / usageDetailLines）自己会跳过没读到的那一项。
        // `?.toLong()` 只是类型口径：LlmUsage 是 Int?（各家返回的都是 32 位内的 token 数），
        // 而累计账用 Long——单步永远不溢出，累加到几十万轮也不会。
        engine.onUsage = { s ->
            roundUsage = accumulateUsage(
                roundUsage,
                BaopuUsageRow(
                    step = s.step,
                    tokensIn = s.usage.promptTokens?.toLong(),
                    tokensOut = s.usage.completionTokens?.toLong(),
                    cacheHit = s.usage.cacheHitTokens?.toLong(),
                    elapsedMs = s.elapsedMs,
                ),
            )
        }
        refresh()
        // 进程重启后重建确认卡（派工单 §3.3：pending_tool 非空 ⇒ 卡还在）
        restoreConfirmCard()
        // 进程重启后把压过的摘要与跳过列表塞回引擎（第十三批 C 段：不然等于白压）
        restoreCompression()
        // 第十九批：封卷自检补算（K6）——closed_at 有、refined_at 没有的会话补提炼一次
        selfCheckUnclosedSessions()
        // 进页面先看投递有没有料（A 段：扫投递 → 一次 ask_user）与顶条的两个数
        checkDeliver()
        refreshBackupStats()
    }

    /**
     * 进程重启后恢复压缩（第十三批 C 段 · 派工单 §4.3）：`compression` 表里有本会话
     * 最近一条记录 ⇒ 摘要与跳过列表一起塞回引擎，界面细条也照这条渲染。
     *
     * **没有就什么都不做**（现状不变）——新会话本来就没压过，不许凭空显示「已压缩」。
     */
    private fun restoreCompression() {
        val sid = sessionId ?: return
        val rec = runCatching { SessionCompressionStore(db).latest(sid) }.getOrNull() ?: return
        engine.restoreCompression(rec.summary, rec.compressedIds.toSet())
        compressionNote = CompressionNote(count = rec.compressedIds.size, summary = rec.summary)
    }


    /**
     * 进程重启后重建确认卡（T3 的第一半）：session 的 pending 三列非空 ⇒ 造一张卡。
     *
     * 三格的合成判据在 [BaopuConfirmRestore]（第七十二批抽出的纯函数）：
     * summary 由工具名＋参数**重新生成**（与在线出卡同一个函数，不落库；
     * callId 合成 `restored-<pending_at>`，稳定）；参数坏 JSON ⇒ 摘要照给。
     */
    private fun restoreConfirmCard() {
        val sid = sessionId ?: return
        val row = pendingStore.load(sid) ?: return
        // 三格合成在 BaopuConfirmRestore（第七十二批抽出的纯函数，坏 JSON 也不抛）
        // 组5：给根 ⇒ 摘要里的路径与在线出卡同一口径（相对工作区显示）
        confirmCard = BaopuConfirmRestore.card(row, workspaceRoot)
        confirmRestored = true
    }

    /** 确认卡：批准（恢复卡走引擎的恢复执行；活卡走挂起苏醒）。 */
    fun approveConfirm() {
        val card = confirmCard ?: return
        val sid = sessionId
        confirmCard = null
        if (confirmRestored && sid != null) {
            engine.approveRestoredPending(sid)
        } else {
            engine.resumeWithApproval(card.callId, approved = true, note = null)
        }
    }

    /** 确认卡：拒绝（不执行；回灌「用户拒绝」，对话继续）。 */
    fun rejectConfirm() {
        val card = confirmCard ?: return
        val sid = sessionId
        confirmCard = null
        if (confirmRestored && sid != null) {
            engine.rejectRestoredPending(sid, card.summary)
        } else {
            engine.resumeWithApproval(card.callId, approved = false, note = null)
        }
    }

    /** 确认等待期间App进后台（屏幕生命周期报给引擎：前台计时暂停）。 */
    fun onScreenPause() = engine.noteConfirmBackground()

    /** 确认等待期间App回前台（前台计时继续）。 */
    fun onScreenResume() = engine.noteConfirmForeground()

    // 第一百一十七批：图片路径**不再**停在控制器这一格（单格排在队列里会串图：
    // 用户连发两条、各自带图，事件回来时全挂到最后一条上）。现在路径随每条
    // QueuedTurn 走，引擎落库后在 MessagePersisted.imageRelPaths 里带回；
    // 事件处理直接按 messageId 补 part（线程纪律不变：apply 恒在主线程）。

    /**
     * 发送（首次自动建会话）。
     *
     * 第一百一十七批起**不再「忙就丢」**：本会话跑着 ⇒ 进该会话 FIFO 队列；
     * 别的会话占着全局单活跃门 ⇒ 明确拒绝（原文留在输入框）。
     *
     * @return 引擎接没接（Composer 据此决定清不清输入框）：
     *   [SendAcceptance.STARTED] 立刻开跑；[SendAcceptance.QUEUED] 已排队；
     *   `false` = 拒绝（原因由引擎 notice 显示）。
     * @param attachments 本轮附件（Composer 传来）。**只挑 `isImage` 的落 image part**；
     *   非图片附件仍只出现在文本 hint 里（本轮不做文件块）。
     */
    fun send(text: String, attachments: List<BaopuAttachments.Saved> = emptyList()): Boolean {
        if (text.isBlank()) return false
        // 第二十四批 C 段：同一批判据喂给引擎——**只传相对路径**，读盘在引擎线程里做
        // （这里是主线程，不许在这儿读 2 MB 的图）。图片路径随这条消息排队，不共享格子。
        val imageRelPaths = AttachmentParts.imageRelPaths(attachments)
        val sid = sessionId ?: engine.createSession(
            title = text.take(40),
            now = System.currentTimeMillis(),
        ).also { created ->
            sessionId = created
            recordSessionWorkspace(created)
        }
        val verdict = engine.send(sid, text, imageRelPaths)
        if (verdict == SendAcceptance.STARTED) {
            // 第三十八批 A4：新一轮开局清账（与 introspectionNote 同一处节奏）——
            // 「本轮累计」必须是**本轮**的。只在本轮真开跑时清：排队的消息还没跑，
            // 清了会把正在跑那一轮的账抹掉。
            resetRoundUsage()
        }
        return verdict.accepted
    }

    /**
     * 把本轮附件按**相对路径**补写成用户消息的 `part.kind='image'`（B1）。
     *
     * seq 从 1 起：0 是引擎落的 `text` part。写入是**事务**（要么整批在、要么都不在），
     * 避免"三张图只落了两张"这种半边状态。
     *
     * 只存相对路径：不存 base64（库会被撑爆）、不存绝对路径（换工作区就全废，且是
     * 本机路径该待的地方之外）。
     */
    private fun attachImageParts(messageId: Long, relPaths: List<String>) {
        if (relPaths.isEmpty()) return
        runCatching {
            val now = System.currentTimeMillis()
            db.write { d ->
                d.beginTransaction()
                try {
                    relPaths.forEachIndexed { i, rel ->
                        val stmt = BaopuSql.insertPart(messageId, i + 1, "image", rel, now)
                        d.execSQL(stmt.sql, stmt.args)
                    }
                    d.setTransactionSuccessful()
                } finally {
                    d.endTransaction()
                }
            }.get()
        }.onFailure {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴附件：写 image part 失败 ${it.javaClass.simpleName}: ${it.message}",
            )
        }
    }

    /**
     * 新会话记下当时的工作区（第二十批 §2.3.2，K2）。
     *
     * 引擎的 createSession 不认识这列（agent 包本批禁改）⇒ 建完补一条 UPDATE；
     * 写的是 [com.example.zhengdao.terminal.Workspace.hostDir] 的**解析结果**
     * （含无权限回落私有等既有口径），跟终端/资料库看到的是同一个目录。
     */
    private fun recordSessionWorkspace(sid: Long) {
        val path = com.example.zhengdao.baopu.BaopuWorkspace.hostDir(appCtx).absolutePath
        val stmt = com.example.zhengdao.baopu.BaopuSql.setSessionWorkspace(sid, path)
        runCatching { db.write { d -> d.execSQL(stmt.sql, stmt.args) }.get() }
            .onFailure {
                com.example.zhengdao.rootfs.RunLog.log(
                    "抱朴会话：记工作区失败 ${it.javaClass.simpleName}: ${it.message}",
                )
            }
    }

    /**
     * 停止（半成品保留）。
     *
     * 第一百一十七批：引擎同时**清掉本会话还没开跑的排队条**（当前这一轮照旧收尾）；
     * 计数由 `QueueChanged` 回来更新，这里不先斩后奏地写 0。
     */
    fun stop() = engine.stop()

    /**
     * 「继续排队」：冷启动恢复的队列**不会自己跑**（见设计页「恢复边界」），
     * 用户点一下才接上；别的会话占着门时引擎会明确拒绝并给 notice。
     */
    fun continueQueue() {
        val sid = sessionId ?: return
        if (engine.resumeQueue(sid)) refresh()
    }

    /** 「清空排队」：只清还没开跑的消息，不动正在跑的这一轮。 */
    fun clearQueue() {
        val sid = sessionId ?: return
        engine.clearQueue(sid)
        queuedCount = 0
    }

    /** 一键续跑（中断于第 N 步）。 */
    fun resume() {
        val sid = sessionId ?: return
        engine.resume(sid, interruptedStep)
    }

    // ── 截断 / 重试 / 编辑上一条（第四十批）───────────────────────────────

    /** 上一次回答是不是被服务端截在输出上限（引擎给的真实读数）。 */
    val tailTruncated: Boolean get() = engine.tailTruncated.value

    /** 上一次跑挂了 / 被截断后停下来 ⇒ 可以「重试」。 */
    val canRetry: Boolean
        get() = !busy && sessionId != null && engineState.value == RunState.ABORTED

    /**
     * 「继续说完」：接着把话写完，**不新增一条用户消息**。
     *
     * 走引擎续跑那条链（`resume` ⇒ `runLoop(userText=null, resumed=true)`）：历史从库里
     * 重建，模型看到的就是自己那半截回答，接着说。
     */
    fun continueTruncated() {
        val sid = sessionId ?: return
        if (busy) return
        engine.clearTailTruncated()
        engine.resume(sid, -1)
    }

    /** 「重试」：把失败的那一轮重发一次（同样不新增用户消息，步数接着算）。 */
    fun retryLastTurn() {
        val sid = sessionId ?: return
        if (busy) return
        engine.clearTailTruncated()
        engine.resume(sid, -1)
    }

    /** 某条用户消息的正文（长按菜单「编辑这条」预填用）。 */
    fun userMessageText(messageId: Long): String? = db.read { d ->
        d.rawQuery(
            "SELECT payload FROM part WHERE message_id = ? AND kind = 'text' ORDER BY seq ASC LIMIT 1",
            arrayOf(messageId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    /**
     * 改某条用户消息并让它重跑（用户 2026-10-19：长按那条消息 ⇒ 编辑 ⇒ 重新输出）。
     *
     * 三步，**一个事务**：① 改正文 part；② 删掉它之后的所有消息（助手回复、工具卡、计划卡
     * 一并清掉，part 靠外键级联）；③ 清掉该会话的计划快照（旧计划不该跟着新内容走）。
     * 然后引擎续跑——**不新增用户消息**，模型看到的就是改后的那句。
     *
     * @param messageId 长按的那条用户消息 id（不一定是最后一条：改中间那条 ＝ 从那儿重来）
     */
    fun editUser(messageId: Long, newText: String) {
        val sid = sessionId ?: return
        if (busy) return
        val text = newText.trim()
        if (text.isEmpty()) return
        runCatching {
            db.write { d ->
                d.beginTransaction()
                try {
                    val u = BaopuSql.updateUserText(messageId, text)
                    d.execSQL(u.sql, u.args)
                    val del = BaopuSql.deleteMessagesAfter(sid, messageId)
                    d.execSQL(del.sql, del.args)
                    val plan = BaopuSql.deletePlanBatches(sid)
                    d.execSQL(plan.sql, plan.args)
                    d.setTransactionSuccessful()
                } finally {
                    d.endTransaction()
                }
            }.get()
        }.onFailure {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴：编辑消息失败 ${it.javaClass.simpleName}: ${it.message}",
            )
        }
        engine.clearTailTruncated()
        interruptedStep = -1
        refresh(force = true)
        engine.resume(sid, -1)
    }

    /** 新会话：释放旧视图状态（库里的会话不删——账本只增）。 */
    fun newSession() {
        // 第十九批 §2.4：点「新会话」＝旧会话**封卷**——先写 closed_at，提炼成功才写
        // refined_at；没跑成留给下次启动自检补算（同一会话只补一次，见 selfCheckUnclosedSessions）。
        closeCurrentSession()
        sessionId = null
        interruptedStep = -1
        messages = emptyList()
        pendingMemories = emptyList()
        introspectionNote = null
        scratchLeft = 0
        pendingNote = null   // 50 批遗留：换会话必须清掉"没记住"便签（原来只靠 UI 8 秒计时器兜底）
        refineMarkSession = -1L
        refineMarkUserCount = -1
        confirmCard = null
        confirmRestored = false
        compressionNote = null // 新会话没压过，细条不许跟着上个会话走
        queuedCount = 0       // 新会话没有队列，计数不许跟着上个会话走
        resetRoundUsage() // 用量与产物同理：顶栏跑马灯不许把上个会话的本轮账带过来
    }

    // ── 会话库（第二十批 §2.1：旧会话能开能删）─────────────────────────────

    /**
     * 读会话库（**只读**；排序/徽标/分组的判据在浮层的纯函数里，这里只搬运库行）。
     * 标题兜底（空标题取首条用户消息前 20 字）在这里一并做——一条补查，不进 SQL 主句。
     */
    fun loadSessionLibrary(): List<BaopuSessionEntry> {
        val stmt = com.example.zhengdao.baopu.BaopuSql.sessionLibraryEntries()
        val rows = db.read { d ->
            d.rawQuery(stmt.sql, stmt.args).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            BaopuSessionEntry(
                                id = c.getLong(0),
                                title = c.getString(1) ?: "",
                                rounds = c.getInt(2),
                                updatedAt = c.getLong(3),
                                closedAt = if (c.isNull(4)) null else c.getLong(4),
                                workspace = if (c.isNull(5)) null else c.getString(5),
                            ),
                        )
                    }
                }
            }
        }
        // 标题兜底（§2.1.2）：空标题取首条用户消息**前 20 字**；再空由 UI 显示「未命名会话」。
        // 判据本体在 BaopuSessionTitles（第七十二批抽出的纯函数），这里只补查数据源。
        val blankIds = BaopuSessionTitles.blankIds(rows)
        if (blankIds.isEmpty()) return rows
        return BaopuSessionTitles.withFallback(rows, readFirstUserTexts(blankIds))
    }

    /** 空标题会话的首条用户消息正文（标题兜底的数据源；没有 ⇒ 不在该表里）。 */
    private fun readFirstUserTexts(ids: List<Long>): Map<Long, String> = db.read { d ->
        val out = mutableMapOf<Long, String>()
        val marks = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        d.rawQuery(
            "SELECT message.session_id, part.payload FROM message " +
                "JOIN part ON part.message_id = message.id " +
                "WHERE message.session_id IN ($marks) AND message.role = 'user' AND part.kind = 'text' " +
                "ORDER BY message.session_id ASC, message.seq ASC, part.seq ASC",
            args,
        ).use { c ->
            while (c.moveToNext()) {
                val sid = c.getLong(0)
                if (!out.containsKey(sid)) {
                    val t = c.getString(1)
                    if (!t.isNullOrBlank()) out[sid] = t
                }
            }
        }
        out
    }

    /**
     * 打开旧会话接着聊（第二十批 §2.1.3）。
     *
     * 三条口径：
     * - **busy 拒切**：引擎单活跃门还挂在旧会话上，跑到一半切走现场必乱；
     * - **不封卷当前会话**：切走不是定稿（「新会话」才是），再切回来还能接着聊；
     * - **恢复卡由现有机制负责**（[refresh] ＋ [restoreConfirmCard]，别自己重造）：
     *   中断可续照旧由 [interruptedStep] 与 BaopuSessionBar 承担；目标会话若挂着
     *   pending 确认卡，进来就重建。
     */
    fun openSession(sid: Long) {
        if (busy) return
        if (sessionId == sid) return
        sessionId = sid
        interruptedStep = -1
        messages = emptyList()
        pendingMemories = emptyList()
        introspectionNote = null
        scratchLeft = 0
        pendingNote = null   // 50 批遗留：换会话必须清掉"没记住"便签（原来只靠 UI 8 秒计时器兜底）
        refineMarkSession = -1L
        refineMarkUserCount = -1
        confirmCard = null
        confirmRestored = false
        compressionNote = null
        queuedCount = 0 // 先归零，refresh 再按目标会话的引擎队列写回真值
        resetRoundUsage() // 切会话 ⇒ 上个会话的本轮账一起清（A4 同一口径）
        refresh()
        restoreConfirmCard()
    }

    // ── 单活跃门的「先问人」（第二十九批 B 段）─────────────────────────────
    //
    // 引擎那一侧一个字没改（`baopu/agent/**` 本批禁改，[com.example.zhengdao.baopu.agent.ActiveSessionGate]
    // 本身也不动）：门照旧挡第二个 run，`已有另一个会话在跑（全局单活跃）` 那句照旧是它的。
    // 这一批改的是**壳层怎么把这件事交给人**：以前抽屉/会话库点一条在跑的别的会话，
    // [openSession] 第一行 `if (busy) return` 就哑了——用户看到的是"点了没反应"。
    // 现在点它 ⇒ 出确认卡：「X 还在跑。要停掉它，并切到 Y 继续吗？」
    // ⛔ 不许自动停：用户没点就停别人的会话是事故（派工单 §2 B 段红字）。

    /** 确认卡的数据（两句标题都是**给用户看的名字**，不是内部 id）。 */
    data class SessionSwitchAsk(
        val targetSessionId: Long,
        val targetTitle: String,
        val runningSessionId: Long?,
        val runningTitle: String,
    )

    /** 等用户拍板的那张卡（null = 没在问）。只有 [requestOpenSession] 撞门时会立起来。 */
    var sessionSwitchAsk by mutableStateOf<SessionSwitchAsk?>(null)
        private set

    /**
     * 用户已拍板"停掉并切过去"、但那个 run 还没收尾时记的目标会话。
     *
     * 为什么需要这一格：`engine.stop()` 只是把停止意图立起来，循环在**工具调用中间**
     * 不回来看这个标志——要等手头那步跑完才收尾并释放单活跃门。所以"停"和"切"天然不在
     * 同一毫秒：切过去的动作必须等 [EngineEvent.Settled]（引擎在放门之后才发它）。
     * 这一格只由用户点击产生，[dismissSessionSwitch]（取消）与切成功都会清掉。
     */
    private var pendingSwitchTo: Long? = null

    /**
     * 抽屉里点一条会话（A5／B 段入口）。
     *
     * 三条分支：① 点的就是当前会话 ⇒ 什么都不做；② 门是空的 ⇒ 直接 [openSession]；
     * ③ 别的会话在跑 ⇒ **只出卡，不停任何东西**。
     */
    fun requestOpenSession(sid: Long) {
        if (sessionId == sid) return
        if (!busy && !runningNow()) {
            openSession(sid)
            return
        }
        val running = runningSessionId()
        sessionSwitchAsk = SessionSwitchAsk(
            targetSessionId = sid,
            targetTitle = sessionTitleOf(sid),
            runningSessionId = running,
            runningTitle = sessionTitleOf(running),
        )
    }

    /** 取消：卡收起来，**什么都不做**（在跑的那个会话继续跑，不许被这次点击停掉）。 */
    fun dismissSessionSwitch() {
        sessionSwitchAsk = null
    }

    /** 「停掉并切过来」：走既有停止路径（[stop] = `engine.stop()`），不新造任何停止状态。 */
    fun acceptSessionSwitch() {
        val ask = sessionSwitchAsk ?: return
        sessionSwitchAsk = null
        pendingSwitchTo = ask.targetSessionId
        stop()
        // 那个 run 其实已经收尾了（点击与收尾撞在一起）⇒ 门已经空了，立刻切，不必等下一次事件。
        if (!runningNow()) consumePendingSwitch()
    }

    /** 本轮彻底结束（[EngineEvent.Settled]）时兑现用户已经拍板的切换；没拍板就什么都不做。 */
    private fun consumePendingSwitch() {
        val target = pendingSwitchTo ?: return
        if (runningNow() || busy) return // 还没停干净：等下一次 Settled（不硬切，见上面那一格的理由）
        pendingSwitchTo = null
        openSession(target)
    }

    /** 单活跃门现在挂在哪个会话上（只读 [com.example.zhengdao.baopu.agent.ActiveSessionGate]；没有 ⇒ null）。 */
    private fun runningSessionId(): Long? =
        com.example.zhengdao.baopu.agent.ActiveSessionGate.current()
            ?.removePrefix("id:")
            ?.toLongOrNull()

    /** 会话标题（确认卡那句话要用）：库里读一条，兜底口径与会话库浮层同一个 [displayTitle]。 */
    private fun sessionTitleOf(sid: Long?): String {
        if (sid == null) return appCtx.getString(R.string.baopu_session_unnamed)
        val row = runCatching { loadSessionLibrary().firstOrNull { it.id == sid } }.getOrNull()
        return displayTitle(row?.title.orEmpty(), appCtx.getString(R.string.baopu_session_unnamed))
    }

    /**
     * 清空会话视图（第二十五批 C 段：删当前会话后的落点）——与「新会话」同样的清空动作，
     * 但**不封卷**（会话行已经删了，没有可封的对象）。回空态「还没有对话」；
     * **不许**自动跳到别的会话——用户没选，别替他选（P4）。
     */
    private fun clearSessionView() {
        sessionId = null
        interruptedStep = -1
        messages = emptyList()
        pendingMemories = emptyList()
        introspectionNote = null
        scratchLeft = 0
        pendingNote = null   // 50 批遗留：换会话必须清掉"没记住"便签（原来只靠 UI 8 秒计时器兜底）
        refineMarkSession = -1L
        refineMarkUserCount = -1
        confirmCard = null
        confirmRestored = false
        compressionNote = null
        queuedCount = 0
        resetRoundUsage() // 删掉当前会话回空态 ⇒ 顶栏也不许留着它的本轮账
    }

    /**
     * 永久删除一个会话（第二十批 §2.1.4；**唯一调用方是浮层删除确认框的确认按钮**）。
     *
     * 五步语句与顺序由 [com.example.zhengdao.baopu.BaopuSql.deleteSessionStatements]
     * 给定（ops 子批 → 提案批 → run_log → message → session 本体；part 靠外键级联），
     * 同一个写事务里跑完——删一半断电等于没删（单写者线程串行，天然原子）。
     *
     * 第二十五批追加一 A/D4：**Future 必须 .get() 等真落库再返回**——之前不 get，
     * 删除排队异步跑，浮层 tick++ 重读列表可能抢在事务前 ⇒ 会话还在 ⇒ 真机「删不掉」
     * 的另一半真因；且异常吞在 Future 里 ⇒ 失败静默。现在同步等结果，成败返回给
     * 浮层（失败 Toast「删除失败」，不许静默）。
     * 删的**不是**当前会话 ⇒ 只删库，当前会话不动（P5）；删的**正是**当前会话 ⇒
     * [clearSessionView] 回空态（P4；提示由浮层负责）。
     */
    fun deleteSession(sid: Long): Boolean {
        if (busy) return false // 在跑不许删：引擎还在往库里写
        val ok = runCatching {
            db.write { d ->
                val stmt = com.example.zhengdao.baopu.BaopuSql.softDeleteSession(sid, System.currentTimeMillis())
                d.execSQL(stmt.sql, stmt.args)
            }.get()
        }.isSuccess
        if (ok && sessionId == sid) {
            clearSessionView()
        }
        return ok
    }

    /** 从回收站恢复一个会话（第四十七批）；返回是否成功。 */
    fun restoreSession(sid: Long): Boolean = runCatching {
        db.write { d ->
            val stmt = com.example.zhengdao.baopu.BaopuSql.restoreSession(sid, System.currentTimeMillis())
            d.execSQL(stmt.sql, stmt.args)
        }.get()
    }.isSuccess

    /** 从回收站彻底删除（复用既有的五条删除语句）；返回是否成功。 */
    fun purgeSession(sid: Long): Boolean = runCatching {
        db.write { d ->
            com.example.zhengdao.baopu.BaopuSql.deleteSessionStatements(sid).forEach { stmt ->
                d.execSQL(stmt.sql, stmt.args)
            }
        }.get()
    }.isSuccess

    /** 记忆候选：确认（落盘 CONFIRMED）。 */
    fun confirmMemory(id: String) {
        val e = memoryCandidates.firstOrNull { it.id == id } ?: return
        val confirmed = e.copy(status = MemoryStatus.CONFIRMED, updatedAt = System.currentTimeMillis())
        store.update(confirmed)
        syncIndex(confirmed)
        refreshMemory()
    }

    /** 记忆候选：不记（文件与索引都删——候选没资格占地方）。 */
    fun forgetMemory(id: String) {
        store.delete(id)
        // P2-2（第五十六批）：写完**等它落库**再刷新。旧实现把 db.write 的 Future 丢掉：
        // 写失败被静默吞掉，refreshMemory() 照常按"成功"刷新 ⇒ 用户看到候选没了，
        // 库里那行还在，下次进页面又冒出来。
        runCatching {
            db.write { d ->
                val stmt = com.example.zhengdao.baopu.BaopuSql.deleteMemory(id)
                d.execSQL(stmt.sql, stmt.args)
            }.get()
        }.onFailure {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴：忘记记忆失败 ${it.javaClass.simpleName}: ${it.message}",
            )
            pendingNote = "这条没删掉（写库失败），稍后再试；错误已记日志"
        }
        refreshMemory()
    }

    // ── C 段：会话结束提炼（候选未落盘，等用户过目）─────────────────────────

    /**
     * 提炼候选：确认 ⇒ 转 CONFIRMED 落盘（[com.example.zhengdao.baopu.MemoryStore.write]
     * 只收 CONFIRMED，且自己过校验＋去重）⇒ 从待过目列表移除。
     *
     * 失败不静默：重复/不合格都会在 [pendingNote] 里说出来（条目留在列表里，
     * 用户可以改天再试或点「不记」）——**绝不自动落盘**（派工单 C 段红线）。
     */
    fun confirmPendingMemory(id: String) {
        val e = pendingMemories.firstOrNull { it.id == id } ?: return
        val confirmed = e.copy(status = MemoryStatus.CONFIRMED, updatedAt = System.currentTimeMillis())
        val r = store.write(confirmed)
        if (!r.ok) {
            pendingNote = r.reason
            return
        }
        syncIndex(confirmed)
        pendingMemories = pendingMemories.filterNot { it.id == id }
    }

    /** 提炼候选：不记（只从进程里的待过目列表扔——它本来就没落盘，没有可删的文件）。 */
    fun forgetPendingMemory(id: String) {
        pendingMemories = pendingMemories.filterNot { it.id == id }
    }

    /** 清掉动作结果那句话（UI 显示几秒后调）。 */
    fun clearPendingNote() {
        pendingNote = null
    }

    /**
     * 清掉资料库动作结果那句话。
     *
     * 第六十一批 §2：进度那一档由 UI 的 8 秒定时器调；错误那一档**只**由用户点便签
     * 上的 `×` 调（错误不许自己走）。
     */
    fun clearLibraryNote() {
        libraryNote = null
    }

    // ── 资料库动作（A 段收料 / D 段导出；全部 IO 线程，状态回主线程） ───────────

    /**
     * 扫 `投递/`：有料就出一次提问卡（没料什么都不做——不打扰）。
     *
     * 进页面与每轮引擎 Settled 时由 UI 调；重复调无副作用（签名机制挡着）。
     */
    fun checkDeliver() {
        if (askCard != null) return
        scope.launch {
            val imp = BaopuRuntime.libraryImport(appCtx)
            val scan = withContext(Dispatchers.IO) { imp.scan() }
            if (scan.files.isEmpty()) {
                deliverSignature = null // 料被清空了 ⇒ 下次有新料要能再问
                return@launch
            }
            val sig = "${scan.files.size}:${scan.textBytes}:${scan.files.sumOf { it.mtime }}"
            if (sig == deliverSignature) return@launch
            deliverSignature = sig
            askCard = imp.card(scan)
        }
    }

    /** 提问卡：点选项（[AskUserOption.id] 就是档位名字，见 [MoveChoice]）。 */
    fun answerAskCard(option: AskUserOption) {
        val choice = runCatching { MoveChoice.valueOf(option.id.uppercase()) }.getOrNull()
        if (choice == null) {
            libraryNote = BaopuNote("没能对上选项（${option.id}）——没有执行任何动作", BaopuNoteLevel.Error)
            return
        }
        runImport(choice)
    }

    /**
     * 提问卡：自由输入（**永远留的那一行空白**）⇒ 档位。
     *
     * 解释是纯函数（[com.example.zhengdao.baopu.library.LibraryImport.parseChoice]），
     * 对不上就说对不上——**不猜、不静默按某个档执行**（那是替用户做决定）。
     */
    fun answerAskCardFree(text: String) {
        val imp = BaopuRuntime.libraryImport(appCtx)
        val choice = imp.parseChoice(text)
        if (choice == null) {
            libraryNote = BaopuNote("没能对上选项，请直接点上面的按钮（不猜、不静默执行）", BaopuNoteLevel.Error)
            return
        }
        runImport(choice)
    }

    /**
     * 执行收料（A 段第 3/4 步）：私有复制 ＋ 两份清单 ＋ 索引镜像 ＋ 五步流水线。
     *
     * 顺序（派工单 A 段原话「每次收料，顺序不许改」）：先搬（含 `isolated` 标注与
     * 待删除清单），再跑流水线出 `整理/` 产出与 `_认脸索引.json`，最后首次提示一次。
     * **不删投递里的任何文件**（§2.8）。
     */
    private fun runImport(choice: MoveChoice) {
        askCard = null
        libraryNote = BaopuNote("收料处理中…", BaopuNoteLevel.Info)
        scope.launch {
            val imp = BaopuRuntime.libraryImport(appCtx)
            val store = BaopuRuntime.libraryStore(appCtx)
            val pipeline = BaopuRuntime.libraryPipeline(appCtx)
            val out = withContext(Dispatchers.IO) { imp.execute(choice, System.currentTimeMillis()) }
            // ① 索引镜像（library_item）：复制档当场标 isolated=0（A 段第 3 步）
            withContext(Dispatchers.IO) {
                out.copied.forEach { c ->
                    runCatching {
                        store.insertItemIfAbsent(
                            LibraryItem(
                                sha256 = c.sha256,
                                name = c.relPath,
                                size = c.size,
                                mtime = System.currentTimeMillis(),
                                source = LibrarySource.DELIVER,
                                isolated = c.isolated,
                                kind = LibraryKind.TEXT,
                                privatePath = c.privateRelPath,
                                createdAt = System.currentTimeMillis(),
                            ),
                        ).get()
                    }
                    if (!c.isolated) runCatching { store.markNotIsolated(c.sha256).get() }
                }
            }
            // ② 五步流水线（私有 → 整理/ ＋ _认脸索引.json）；同批跑两遍第二遍全记 duplicate
            val report = withContext(Dispatchers.IO) {
                pipeline.run(
                    source = LibrarySource.DELIVER,
                    isolatedBySha = out.copied.associate { it.sha256 to it.isolated },
                    onItem = { item ->
                        // 镜像写失败不该炸掉整趟收料（文件侧产出已经是真的）⇒ 单独兜住
                        runCatching {
                            store.insertItemIfAbsent(
                                LibraryItem(
                                    sha256 = item.sha256,
                                    name = item.name,
                                    size = item.size,
                                    mtime = item.mtime,
                                    source = item.source,
                                    isolated = item.isolated,
                                    kind = item.kind,
                                    createdAt = System.currentTimeMillis(),
                                ),
                            ).get()
                        }
                    },
                )
            }
            // ③ 首次搬原件提示一次（D 段第 2 条；记在 library_move_log，行在即提示过）
            val firstMove = withContext(Dispatchers.IO) {
                out.copied.isNotEmpty() && !store.hasNotice(noticeFirstMove)
            }
            if (firstMove) withContext(Dispatchers.IO) { runCatching { store.markNoticed(noticeFirstMove).get() } }

            val parts = mutableListOf<String>()
            when (choice) {
                MoveChoice.SKIP -> parts += "先不搬——投递里的件原样没动。"
                else -> {
                    val moved = out.copied.count { !it.alreadyThere }
                    parts += "收料完成：$moved 件进私有" +
                        if (out.copied.any { it.alreadyThere }) "（${out.copied.count { it.alreadyThere }} 件本来就在）" else ""
                    // 派工单 A 段第 3 步原话：选「复制」必须在 UI 上写明「这批没隔离」
                    if (choice == MoveChoice.COPY) {
                        parts += "；注意：选的是复制，共享区里还留着原件，这批没有隔离"
                    }
                    if (out.leftBehind.isNotEmpty()) parts += "；仍在投递 ${out.leftBehind.size} 件"
                    if (out.pendingDelete.isNotEmpty()) parts += "；待删除清单 ${out.pendingDelete.size} 件（在 整理/_待删除清单.json，删不删你定）"
                    if (out.rejected.isNotEmpty()) parts += "；被拒 ${out.rejected.size} 件"
                    parts += "；整理产出 ${report.processed.size} 件"
                }
            }
            if (firstMove) parts += "（首次搬原件：以后在 设置 → 终端与资料 → 抱朴 里能一键导出备份）"
            // §2：这句里带着「被拒 N 件」那半截失败——整句 8 秒蒸发后用户只记得"完成"，
            // 所以有被拒就按错误留痕（选「先不搬」那一档不报被拒，仍是 Info）。
            val rejectedCount = if (choice == MoveChoice.SKIP) 0 else out.rejected.size
            libraryNote = BaopuNote(parts.joinToString(""), BaopuNotePolicy.levelOfResult(rejectedCount))
            refreshBackupStats()
        }
    }

    /** 导出原件（D 段第 1/3 条）：`textOnly` = 只导文本件那一档。 */
    fun exportOriginals(textOnly: Boolean) {
        exportBusy = true
        libraryNote = BaopuNote("导出中…", BaopuNoteLevel.Info)
        scope.launch {
            try {
                val pipeline = BaopuRuntime.libraryPipeline(appCtx)
                val store = BaopuRuntime.libraryStore(appCtx)
                val report = withContext(Dispatchers.IO) {
                    val onExported: (String, Long) -> Unit = { sha, at ->
                        runCatching { store.markExported(sha, at).get() }
                    }
                    if (textOnly) {
                        pipeline.exportKind(LibraryKind.TEXT, System.currentTimeMillis(), onExported)
                    } else {
                        pipeline.exportAll(System.currentTimeMillis(), onExported)
                    }
                }
                libraryNote = when {
                    // 一件都没导出去 = 这次动作没做成，用户得知道为什么 ⇒ Error 常驻
                    report.exported.isEmpty() -> BaopuNote(
                        "没有可导出的原件（索引是空的）。" +
                            (report.notes.firstOrNull()?.let { "（$it）" } ?: ""),
                        BaopuNoteLevel.Error,
                    )
                    else -> BaopuNote(
                        buildString {
                            append("导出完成：${report.exported.size} 件（在 整理/原件导出-<时间戳>/）")
                            if (report.notes.isNotEmpty()) append("；另有 ${report.notes.size} 条备注（跳过/撞名/失败）")
                        },
                        // 「另有 N 条备注（跳过/撞名/失败）」那半截是部分失败 ⇒ Error
                        BaopuNotePolicy.levelOfResult(report.notes.size),
                    )
                }
                refreshBackupStats()
            } finally {
                exportBusy = false
            }
        }
    }

    /** 读顶条要的两个数（db 读，走 IO；进页面与导出/收料后调）。 */
    fun refreshBackupStats() {
        scope.launch {
            val store = BaopuRuntime.libraryStore(appCtx)
            val s = withContext(Dispatchers.IO) {
                BackupStats(store.lastExportAt(), store.unexportedCount(), store.totalCount())
            }
            backupStats = s
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /**
     * 引擎事件一律**回到主线程**再刷新。
     *
     * 为什么（2026-10-17 真机两次实测对比）：引擎事件是从 `zd-baopu-run` 后台线程发的，
     * 在后台线程直接写 Compose 的 `mutableStateOf`（messages / interruptedStep）时，
     * 界面**有时**跟着变、有时不变——"续跑完了中断红字还挂着"就是这么来的。
     * 主线程里读库 + 写状态才稳定（同第㊳刀「View.post 对未 attach 的 View 无限期推迟」的教训：
     * 跨线程的界面更新要显式回到主线程，别指望别人替你切）。
     */
    private fun onEngineEvent(ev: EngineEvent) {
        // `Settled` 是"本轮彻底结束"的权威信号（引擎在释放单活跃门之后才发）⇒ 强制重算中断标记，
        // 不再靠 `runningNow()` 去猜（真机上猜错过一次：正常完成的会话红字挂着不下来）。
        // 引擎事件一律**回到主线程**再写状态（2026-10-17 真机两次实测：后台线程直写
        // Compose 状态会时灵时不灵）。
        val apply: () -> Unit = {
            if (ev is ConfirmRequest) {
                confirmCard = ev
                confirmRestored = false
            }
            // 第二十四批 B 段：用户消息刚落库 ⇒ 把本轮附件补成 image part（B1）。
            // 必须在 refresh **之前**写：refresh 会重读 db，晚一步就要等下一次刷新才看得见图。
            // 线程纪律：apply 恒在主线程（上面那层判断保证），所以这里 db.write{}.get()
            // 只是等单写者把活干完，不会与引擎自己的写事务互相等待。
            if (ev is EngineEvent.MessagePersisted && ev.role == HistoryMessage.ROLE_USER) {
                // 路径随事件回来（排队时各自独立）——别再从一个共享格子里取。
                attachImageParts(ev.messageId, ev.imageRelPaths)
                // 147：跑前基线（提炼游标同款）——只认本会话在此之后新增的 run_log 行。
                diaryMarkBeforeRun()
            }
            if (ev is EngineEvent.QueueChanged && ev.sessionId == sessionId) {
                queuedCount = ev.queued
            }
            // 顶栏玄览状态：只有浏览器工具在跑时才出现，本轮结束就撤（不留在屏上骗人）
            if (ev is EngineEvent.ScratchLeft) scratchLeft = ev.count
            if (ev is EngineEvent.ToolCallVisible) {
                com.example.zhengdao.baopu.browserActivityLine(ev.tool, ev.summary)?.let {
                    browserActivity = it
                }
            }
            val force = ev is EngineEvent.Settled
            if (force) browserActivity = null
            refresh(force)
            if (force) {
                // 147：日记 upsert 放在 refresh 之后——stepRows 刚重读过，拿到的是本轮的最终行。
                diaryOnSettle()
                refineOnSettle()
                // 第八十六批：任务收尾 ⇒ 从已有记录生成提案候选（只生成、不应用）。
                // 放在 refineOnSettle 之后：学习卡在引擎收尾时已经出好，这里读的是同一轮。
                generateProposalCandidatesOnSettle()
                // 第一百六十八批：ProdEval 调用点（收尾触发；闸门默认关，
                // 关着这一行除了读一次 prefs 什么都不做；IO 上跑，不阻塞聊天）。
                scheduleProdEvalOnSettle()
                // 第一百零三批 §B②：被前置校验挡下的候选不出卡，但要给一句人话原因
                // （审计行在采样处已落 `[skill-body-selfcheck]` 锚）。
                noteFilteredLearn()
                // 第二十九批 B 段：用户在确认卡上拍过「停掉并切过来」⇒ 门一放就兑现那一下切换。
                // 放在这里而不是 acceptSessionSwitch 里死等：Settled 由引擎在释放单活跃门之后才发，
                // 是"这个 run 真结束了"的唯一权威信号（同上面对 interruptedStep 的那条口径）。
                consumePendingSwitch()
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            apply()
        } else {
            main.post { apply() }
        }
    }

    // ── C 段提炼（本刀不跑模型：纯确定性推导，见 MemoryRefine 的 KDoc）────────

    /**
     * 一轮结束 ⇒ 提炼一次（派工单 C 段：Done/Aborted 时提炼，候选一律 PENDING、
     * 顺带恰好一条自省、**不额外多跑一轮模型**）。
     *
     * 触发点选在这里而不是 BaopuScreen 观察状态：`Settled` 由引擎在单活跃门**释放后**
     * 发，是「本轮彻底结束」的权威信号；[refresh] 刚在同一次主线程块里重读过 messages，
     * 这里拿到的就是刚落库的最终消息集。
     *
     * 事实三查（都不出进程、不联网）：用户原话（[readMessages] 已读出的 text part）、
     * 用过的工具（本轮消息里 tool part 的名字，去重保序）、步数与结束状态（run_log）。
     */
    private fun refineOnSettle() {
        val sid = sessionId ?: return
        // 游标切片与工具统计是纯判据（第七十二批抽到 BaopuRefineWindow）：返回 null = 没新输入
        val slice = BaopuRefineWindow.slice(
            messages = messages,
            sessionId = sid,
            markSession = refineMarkSession,
            markCount = refineMarkUserCount,
        ) ?: return
        refineMarkSession = sid
        refineMarkUserCount = slice.markCount

        val facts = MemoryRefine.SessionFacts(
            sessionId = sid,
            userTexts = slice.freshUserTexts,
            toolsUsed = slice.toolsUsed,
            steps = db.read { d ->
                d.rawQuery(
                    "SELECT COALESCE(MAX(step), 0) FROM run_log WHERE session_id = ?",
                    arrayOf(sid.toString()),
                ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            },
            endState = db.read { d ->
                d.rawQuery(
                    "SELECT state FROM run_log WHERE session_id = ? ORDER BY id DESC LIMIT 1",
                    arrayOf(sid.toString()),
                ).use { c -> if (c.moveToFirst()) c.getString(0) ?: "Done" else "Done" }
            },
            now = System.currentTimeMillis(),
        )
        val r = MemoryRefine.refine(facts)
        // 追加（不冲掉用户还没过目的老候选）＋ 同正文去重 ＋ 上限截断（先到先得）
        val merge = BaopuRefineWindow.mergePending(pendingMemories, r.candidates, maxPending)
        if (merge.dropped > 0) {
            com.example.zhengdao.rootfs.RunLog.log(
                "[baopu-refine] 待过目候选超过 $maxPending 条，截断 ${merge.dropped} 条（body 去重后仍超限）",
            )
        }
        pendingMemories = merge.kept
        introspectionNote = r.introspection.text
        runCatching {
            notePrefs.edit { putString("note_$sid", r.introspection.text) }
        }
    }

    // ── 提案候选（第八十六批）：任务后从已有记录生成，只存不应用 ─────────────

    /**
     * 一轮收尾 ⇒ 生成一次提案候选（派工单第八十六批 §1/§2）。
     *
     * **只生成、不应用**：候选写进 `抱朴记忆/提案候选.json` 等 82/⑤ 来读；
     * 这里绝不调 `SkillStore.apply` / `PersonaFiles.applyProposal` / `restore`，
     * 也不碰任何技能/人设文件。
     *
     * 输入全是**已有记录**：引擎刚出的学习卡（[RunEngine.learn]，主线程先快照）、
     * `skill_event` 的 use/decision 行、`run_log` 的工具行；不新抓用户正文、不跑模型。
     * 读库/读盘放在 IO 线程，收尾主线程只做一次状态快照，不压 UI。
     *
     * 第九十九批：生成之后**紧接着判定**（[judgeProposalCandidates]）——候选没人读就永远是
     * 死代码，判定结果进 [evolutionSuggestions] 与 `决策审计.jsonl`。判定失败只 log，
     * 不堵主流程（65/89 口径）；判定只影响展示与审计，不影响任何自动执行路径。
     */
    private fun generateProposalCandidatesOnSettle() {
        val sid = sessionId ?: return
        // Compose State 只在主线程读；先快照，再进 IO。
        val learn = engine.learn.value.orEmpty()
        val now = System.currentTimeMillis()
        scope.launch {
            val settled = runCatching {
                withContext(Dispatchers.IO) {
                    appendProposalCandidates(sid, learn, now)
                    val suggestions = judgeProposalCandidates()
                    // 第一百批：同一个收尾点复测「采纳之后」的效果——只判、只出建议卡，
                    // 不执行任何回滚（回滚要人点，见 rollbackPostAdoption）
                    suggestions to postAdoptionRecheck()
                }
            }
            settled.onFailure {
                com.example.zhengdao.rootfs.RunLog.log(
                    "[baopu-proposal] 候选生成本轮失败（${it.javaClass.simpleName}）：${it.message}",
                )
            }
            // 回主线程再写状态（withContext 回来天然在 Main；失败就保留上一轮的判定）
            settled.onSuccess {
                evolutionSuggestions = it.first
                // 第一百零四批：效果卡进**队列**而不是「有就丢」——账本已记 SUGGESTED，
                // 丢掉＝再也不回（只弹一次被浪费在排队上）。同 eventId 去重。
                it.second?.let { notice ->
                    if (postAdoptionQueue.none { q -> q.eventId == notice.eventId }) {
                        postAdoptionQueue = postAdoptionQueue + notice
                    }
                }
            }
        }
    }

    /**
     * 读记录 → 纯判据生成 → 追加落盘（IO 线程）。返回真正写入的条数。
     *
     * 「同会话」口径（派工单 §1 的阈值都挂在这上面）：
     * - skill `use` 行本来就带 `session_id`，直接按它取；
     * - `decision` 行（第五十七批）当时没写 `session_id`，只能按**会话时间窗**
     *   `[session.created_at, now]` 取。单活跃门保证同一时刻只有一轮在跑，
     *   这是现有账本下最窄、可解释的口径（不新增列、不动 migration）。
     * - `run_log` 工具行带 `session_id`，直接按它取。
     */
    private fun appendProposalCandidates(
        sid: Long,
        learn: List<com.example.zhengdao.baopu.SkillRefine.LearnProposal>,
        now: Long,
    ): Int {
        val since = db.read { d ->
            d.rawQuery(
                "SELECT created_at FROM session WHERE id = ?",
                arrayOf(sid.toString()),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }.takeIf { it > 0L } ?: now

        val uses = db.read { d ->
            d.rawQuery(
                "SELECT id, name, ok, at, step FROM skill_event " +
                    "WHERE kind = 'use' AND session_id = ? ORDER BY id",
                arrayOf(sid.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            ProposalRules.SkillEvent(
                                id = c.getLong(0),
                                name = c.getString(1).orEmpty(),
                                ok = c.getInt(2) != 0,
                                atMs = c.getLong(3),
                                step = if (c.isNull(4)) null else c.getInt(4),
                            ),
                        )
                    }
                }
            }
        }

        val decisions = db.read { d ->
            d.rawQuery(
                "SELECT id, target, name, ok, at FROM skill_event " +
                    "WHERE kind = 'decision' AND at >= ? AND at <= ? ORDER BY id",
                arrayOf(since.toString(), now.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            ProposalRules.SkillEvent(
                                id = c.getLong(0),
                                name = c.getString(2).orEmpty(),
                                ok = c.getInt(3) != 0,
                                atMs = c.getLong(4),
                                target = c.getString(1).orEmpty().ifEmpty { "skill" },
                            ),
                        )
                    }
                }
            }
        }

        val toolRuns = db.read { d ->
            d.rawQuery(
                "SELECT id, tool, ok, created_at FROM run_log " +
                    "WHERE session_id = ? AND tool != '' ORDER BY id",
                arrayOf(sid.toString()),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(
                            ProposalRules.ToolRun(
                                id = c.getLong(0),
                                tool = c.getString(1).orEmpty(),
                                ok = c.getInt(2) != 0,
                                atMs = c.getLong(3),
                            ),
                        )
                    }
                }
            }
        }

        val globalRoot = File(appCtx.filesDir, "抱朴记忆")
        val projectRoot = BaopuRuntime.workspaceRoot(appCtx)
        val store = ProposalCandidateStore.under(globalRoot)
        val pf = BaopuRuntime.personaFiles(appCtx)
        val personaText = listOfNotNull(
            pf.read(com.example.zhengdao.baopu.PersonaLayer.B_GLOBAL),
            pf.read(com.example.zhengdao.baopu.PersonaLayer.C_PROJECT),
        ).joinToString("\n")

        val input = ProposalRules.Input(
            sessionId = sid,
            nowMs = now,
            learn = learn,
            skillUses = uses,
            decisions = decisions,
            toolRuns = toolRuns,
            existingSkillBodies = readExistingSkillBodies(globalRoot, projectRoot),
            personaText = personaText,
            // 读失败 ⇒ 空 set（只记录的链断了也不拦生成；与 refineSkillsForSession 同口径）。
            rejectedIds = runCatching { readRejectedDecisionHashes() }.getOrDefault(emptySet()),
            pendingIds = store.listPending().mapTo(mutableSetOf()) { it.id },
        )
        val r = ProposalRules.generate(input)
        val written = store.appendNew(r.candidates, now)
        if (r.candidates.isNotEmpty() || r.dropped > 0 || r.skipped.isNotEmpty()) {
            // 只记计数与目标，不记 payload/用户正文（证据留痕的同一口径）。
            com.example.zhengdao.rootfs.RunLog.log(
                "[baopu-proposal] 候选生成：产出 ${r.candidates.size} 条，写入 $written，" +
                    "跳过 ${r.skipped.size} 条，超上限丢弃 ${r.dropped} 条",
            )
        }
        return written
    }

    /** 两层技能正文（项目覆盖全局；只读，供「已有技能」去重）。 */
    private fun readExistingSkillBodies(globalRoot: File, projectRoot: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (dir in listOf(
            com.example.zhengdao.baopu.SkillRules.globalSkillDir(globalRoot),
            com.example.zhengdao.baopu.SkillRules.projectSkillDir(projectRoot),
        )) {
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (!child.isDirectory) continue
                val f = File(child, com.example.zhengdao.baopu.SkillRules.SKILL_FILE)
                val text = runCatching {
                    if (f.isFile) f.readText(Charsets.UTF_8) else null
                }.getOrNull() ?: continue
                out[child.name] = text
            }
        }
        return out
    }

    // ── 进化闭环 v1（第九十九批）：候选判定（证据桥 → judge → 审计 → 状态）──────

    /**
     * 对 PENDING 候选逐条判定（派工单 §2）。**只读＋记账，不写技能/人设一个字节**：
     *   候选   ← [ProposalCandidateStore.listPending]（86 的唯一读口；已被 57 批拒绝留痕
     *            挡掉的同 hash 候选直接跳过，不重复判定）；
     *   效果   ← [com.example.zhengdao.baopu.SkillEffects.outcomesFor]（85 的按技能聚合）；
     *   自检   ← [com.example.zhengdao.baopu.dryRunSelfCheck]（84 判据层事前试跑，只读）；
     *   eval   ← [com.example.zhengdao.baopu.eval.selectEvalEvidence]（第一百六十八批：
     *            优先 7 天内的个人生产证据 files/eval/prod-result.json，否则回退发版证据
     *            assets/eval/eval-result.json；两者都没有 ⇒ null ⇒ DEFER，不许默认过线；
     *            来源与日期随证据带进审计与建议卡，不许两套分数混用）；
     *   决策   ← [EvolutionDecisionPort.decide]（88 决策口，每次调用恰好落一条审计）。
     *
     * 失败不阻塞主流程：任一条候选判定抛异常只 log 并跳过它（65/89「只记录不阻塞」），
     * 其余候选照判；整段失败由调用方兜（[generateProposalCandidatesOnSettle]）。
     *
     * @return 「候选＋判定」快照（进 [evolutionSuggestions] 供 UI 读；**只影响展示**）
     */
    private fun judgeProposalCandidates(): List<EvolutionSuggestion> {
        val globalRoot = File(appCtx.filesDir, "抱朴记忆")
        val store = ProposalCandidateStore.under(globalRoot)
        // 57 批拒绝留痕：同 hash 被明确拒绝过的候选不再判定（用户说「不」要算数）
        val rejected = runCatching { readRejectedDecisionHashes() }.getOrDefault(emptySet())
        val pending = store.listPending().filter { it.id !in rejected }
        if (pending.isEmpty()) return emptyList()

        val projectRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(appCtx)
        val audit = EvolutionAuditLog.under(globalRoot)
        // 决策口自带默认策略（全人批；L3 开关默认 false）——本类不实例化 EvolutionPolicy
        val port = EvolutionDecisionPort(audit = audit)
        // eval 证据：第一百六十八批起按**来源**选（派工单 §2 消费点 B）——
        // 优先 7 天内的个人生产证据（本机 files/eval/prod-result.json），
        // 否则回退发版证据（assets/eval/eval-result.json）；
        // 两者都没有 ⇒ null ⇒ policy 判 DEFER（不许默认过线）。
        val prodEval = ProdEvalResultFile.latest(appCtx)
        val releaseEval = EvalResultFile.latest(appCtx)
        val evalResult = com.example.zhengdao.baopu.eval.selectEvalEvidence(
            prod = prodEval,
            release = releaseEval,
            now = System.currentTimeMillis(),
        )
        if (prodEval != null && evalResult !== prodEval) {
            // 有生产证据但已过期：回退到发版证据可以，但要留一行账——
            // 不许安静地换来源（派工单「来源和日期必须标」在日志这一侧的同款要求）。
            com.example.zhengdao.rootfs.RunLog.log(
                "[baopu-eval] 个人生产证据已过期（ranAt=${prodEval.ranAt}），本次判定按发版证据",
            )
        }
        // 第一百零九批：L3-lite **影子**。同一轮判定顺带问一句「若把低风险自动批准那条闸
        // 开起来，这条会批／会拒／会搁置」，只往 进化影子.jsonl 追加账。红线三条：
        //   不改候选状态、不自批、不碰开关（L3_AUTO_LOW_RISK 仍是 false，本类也不实例化策略）。
        val shadowLog = EvolutionShadowLog.under(globalRoot)
        val shadowAt = System.currentTimeMillis()
        val shadowRows = ArrayList<ShadowJudgement>(pending.size)

        val out = ArrayList<EvolutionSuggestion>(pending.size)
        for (c in pending) {
            val suggestion = runCatching {
                val evidence = EvolutionEvidence(
                    effect = if (c.target.isNotBlank()) {
                        com.example.zhengdao.baopu.SkillEffects.outcomesFor(db, c.target)
                    } else {
                        emptyMap()
                    },
                    effectSignalIds = com.example.zhengdao.baopu.evidenceIdsOf(c),
                    selfCheck = com.example.zhengdao.baopu.dryRunSelfCheck(
                        c,
                        skillFileOf(c.target, projectRoot, globalRoot),
                    ),
                    eval = evalResult,
                )
                val input = com.example.zhengdao.baopu.inputFor(c, evidence)
                val decision: EvolutionDecision = port.decide(input, operatorId = "settle")
                // 影子单独兜：账记不上**不许**把真判定一起带走——人是决定，影子只是数。
                runCatching {
                    shadowRows += shadowVerdictOf(input, decision, shadowAt)
                }.onFailure {
                    com.example.zhengdao.rootfs.RunLog.log(
                        "[baopu-shadow] 影子判定跳过（${it.javaClass.simpleName}）：${it.message}",
                    )
                }
                EvolutionSuggestion(
                    candidateId = c.id,
                    kind = c.kind,
                    target = c.target,
                    payload = c.payload,
                    createdAt = c.createdAt,
                    recommendation = decision.recommendation,
                    confidence = decision.confidence,
                    riskClass = decision.riskClass,
                    requiresApproval = decision.requiresApproval,
                    reasons = decision.reasons,
                    // 第一百六十八批：证据来源与日期带上卡（UI 必须标「个人生产证据/发版证据」）。
                    evalSource = evalResult?.source,
                    evalRanAt = evalResult?.ranAt,
                )
            }.getOrElse {
                com.example.zhengdao.rootfs.RunLog.log(
                    "[baopu-evolution] 候选判定跳过（${it.javaClass.simpleName}）：${it.message}",
                )
                null
            }
            if (suggestion != null) out += suggestion
        }
        if (shadowRows.isNotEmpty()) {
            // 落账 → 汇总，两步各自兜底：影子是攒给「以后开不开闸」看的数，
            // 记不上/读不动只 log，绝不回头堵住 99 那条判定链（65/89「只记录不阻塞」口径）。
            runCatching { shadowRows.forEach { shadowLog.record(it) } }.onFailure {
                com.example.zhengdao.rootfs.RunLog.log(
                    "[baopu-shadow] 影子账本写入失败（本轮不记账）${it.javaClass.simpleName}: ${it.message}",
                )
            }
            runCatching {
                // 汇总读**整份账本**并按候选取最后一行：同一候选每轮都被重判，
                // 不去重就会把一致率灌满重复票。人工留痕读不到 ⇒ 本轮没有配对样本（不猜）。
                val dedup = latestByCandidate(shadowLog.load().orEmpty())
                val human = EvolutionShadowHuman.fromDb(db)
                com.example.zhengdao.rootfs.RunLog.log(
                    "[baopu-shadow] " + shadowSummaryLine(tallyShadow(dedup), agreementShadow(dedup, human)),
                )
            }.onFailure {
                com.example.zhengdao.rootfs.RunLog.log(
                    "[baopu-shadow] 汇总跳过（${it.javaClass.simpleName}）：${it.message}",
                )
            }
        }
        return out
    }

    /**
     * 候选目标技能在盘上的文件（项目层优先、全局层兜底；两层都没有 ⇒ null = 首写）。
     *
     * 只读现查，给 [com.example.zhengdao.baopu.dryRunSelfCheck] 判「回滚走哪条路」用——
     * 查找优先级与 [readExistingSkillBodies] 同一口径（项目覆盖全局）。
     */
    private fun skillFileOf(name: String, projectRoot: File, globalRoot: File): File? {
        if (name.isBlank()) return null
        val project = File(
            File(
                com.example.zhengdao.baopu.SkillRules.projectSkillDir(projectRoot),
                name,
            ),
            com.example.zhengdao.baopu.SkillRules.SKILL_FILE,
        )
        if (project.isFile) return project
        val global = File(
            File(
                com.example.zhengdao.baopu.SkillRules.globalSkillDir(globalRoot),
                name,
            ),
            com.example.zhengdao.baopu.SkillRules.SKILL_FILE,
        )
        return global.takeIf { it.isFile }
    }

    // ── 采纳后效果回环（第一百批）：记事件 → 窗口复测 → **只出建议卡** ──────────────
    // （效果卡队列 postAdoptionQueue 声明在决策卡槽那一节——它同时是槽位的数据源）

    /**
     * 回环端口：读数走 85 的 `SkillEffects.outcomesFor`，版本走盘上正文的 sha12。
     *
     * 两个依赖都现取现传（不在端口里 new DB），所以端口本身可以拿假数据直测。
     */
    private fun postAdoptionPort(): com.example.zhengdao.baopu.PostAdoptionPort {
        val globalRoot = File(appCtx.filesDir, "抱朴记忆")
        val projectRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(appCtx)
        return com.example.zhengdao.baopu.PostAdoptionPort(
            log = com.example.zhengdao.baopu.PostAdoptionLog.under(globalRoot),
            readOutcomes = { name ->
                runCatching { com.example.zhengdao.baopu.SkillEffects.outcomesFor(db, name) }.getOrNull()
            },
            versionOf = { name ->
                skillFileOf(name, projectRoot, globalRoot)
                    ?.let { com.example.zhengdao.baopu.SkillAudit.shaOf(it) }
                    ?: "-"
            },
        )
    }

    /**
     * 采纳**之前**的基线（第一百批 §1 的三态）。
     *
     * 顺序是硬的：这个函数必须在写盘之前调用。写完之后再读，读到的就已经混进了
     * 「采纳后」的样本——那样比出来的增量永远偏小，等于把效果藏起来。
     */
    private fun postAdoptionBaseline(name: String): PreAdoptionBaseline {
        val globalRoot = File(appCtx.filesDir, "抱朴记忆")
        val projectRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(appCtx)
        val file = skillFileOf(name, projectRoot, globalRoot)
        // 文件本来不存在 ⇒ 基线全 0 是事实（不是「读不到」）
        if (file == null) return PreAdoptionBaseline(emptyMap(), com.example.zhengdao.baopu.PostAdoptionBaseline.NEW)
        return runCatching {
            PreAdoptionBaseline(
                com.example.zhengdao.baopu.SkillEffects.outcomesFor(db, name),
                com.example.zhengdao.baopu.PostAdoptionBaseline.MEASURED,
            )
        }.getOrElse {
            // 读不到就标 UNKNOWN：判定时只出 DEFER，绝不当 0 用
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴：采纳前基线读失败（记 UNKNOWN）${it.javaClass.simpleName}: ${it.message}",
            )
            PreAdoptionBaseline(emptyMap(), com.example.zhengdao.baopu.PostAdoptionBaseline.UNKNOWN)
        }
    }

    /**
     * 记一条采纳事件（**写盘之后**，因为「有没有备份」要现读一次）。
     *
     * 名字过不了 85 的名字闸门 ⇒ 整条不写并 log（与 `SkillEffects.toStmt` 同口径：
     * 名字里混进正文/用户原话就不许进账本）。
     */
    private fun recordPostAdoption(
        channel: com.example.zhengdao.baopu.PostAdoptionChannel,
        candidateId: String?,
        skillName: String,
        file: java.io.File?,
        baseline: PreAdoptionBaseline,
    ) {
        if (com.example.zhengdao.baopu.SkillEffects.nameToken(skillName) == null) {
            com.example.zhengdao.rootfs.RunLog.log("抱朴：采纳事件的名字过不了闸门，不记（channel=$channel）")
            return
        }
        val backups = file?.let { com.example.zhengdao.baopu.SkillStore.backupsOf(it) }.orEmpty()
        val now = System.currentTimeMillis()
        val event = com.example.zhengdao.baopu.PostAdoptionEvent(
            eventId = "adopt-" + (candidateId ?: skillName) + "-" + now,
            channel = channel,
            candidateId = candidateId,
            skillName = skillName,
            skillVersion = file?.let { com.example.zhengdao.baopu.SkillAudit.shaOf(it) } ?: "-",
            appliedAtMs = now,
            baseline = baseline.counts,
            baselineSource = baseline.source,
            // 现读：写完之后盘上到底有没有备份（不按「应该备了」写 true）
            rollbackAvailable = backups.isNotEmpty(),
            backupIds = backups.mapNotNull {
                it.name.substringAfter(com.example.zhengdao.baopu.SkillStore.BACKUP_MARK, "")
                    .takeIf(String::isNotBlank)
            },
        )
        postAdoptionPort().onAdopted(event)
    }

    /**
     * 复测所有还开着的事件（挂在会话收尾，见 [generateProposalCandidatesOnSettle]）。
     *
     * 失败只 log：采纳后效果是账本，不该把收尾流程拖垮（57/65/89 同口径）。
     *
     * @return 本轮新要弹的那一张卡（通常 0 张）
     */
    private fun postAdoptionRecheck(): com.example.zhengdao.baopu.PostAdoptionNotice? =
        runCatching { postAdoptionPort().recheck().firstOrNull() }.getOrElse {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴：采纳后复测失败（不影响收尾）${it.javaClass.simpleName}: ${it.message}",
            )
            null
        }

    /**
     * 卡上点「回滚」⇒ **唯一**会真的换文件的入口（人批之后）。
     *
     * 走到这里说明用户刚点过按钮，`approvedBy` 就是「人批」这件事本身；
     * 失败要把原因说出来，并**保留卡**（让人可以再试或改主意）。
     *
     * 第一百零四批：入参从「当前那张」改成 eventId——决策中心里也能对排队中的
     * 某一张动手，不再隐式绑定槽位。
     */
    fun rollbackPostAdoption(eventId: String) {
        val notice = postAdoptionQueue.firstOrNull { it.eventId == eventId } ?: return
        postAdoptionQueue = postAdoptionQueue.filter { it.eventId != eventId }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (notice.channel == com.example.zhengdao.baopu.PostAdoptionChannel.SKILL_PROPOSAL) {
                    // 人设索引没有「技能正文」可滚：这条通道不出口滚卡（没有量尺 ⇒ 永远 DEFER），
                    // 万一到了这里也只是指路，不擅自动人设。
                    com.example.zhengdao.baopu.PostAdoptionRollbackResult.Rejected(
                        "人设索引的回滚请到设置页用人设回滚，这里不代劳",
                    )
                } else {
                    val globalRoot = File(appCtx.filesDir, "抱朴记忆")
                    val projectRoot = com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(appCtx)
                    val file = skillFileOf(notice.skillName, projectRoot, globalRoot)
                    val backups = file?.let { com.example.zhengdao.baopu.SkillStore.backupsOf(it) }.orEmpty()
                    val r = com.example.zhengdao.baopu.PostAdoptionRollback.applyOnUserApproval(
                        channel = notice.channel,
                        rollbackAvailable = backups.isNotEmpty(),
                        approvedBy = "user",
                        skillFile = file,
                        personaFile = null,
                        stamp = com.example.zhengdao.util.TimeFormats.fileStamp(),
                    )
                    if (r is com.example.zhengdao.baopu.PostAdoptionRollbackResult.Done) {
                        postAdoptionPort().recordUserAction(
                            eventId = notice.eventId,
                            action = com.example.zhengdao.baopu.PostAdoptionAction.APPLIED,
                            verdict = notice.verdict,
                            reason = notice.reason,
                            delta = notice.delta,
                        )
                    }
                    r
                }
            }
            // 失败（Rejected）⇒ 把卡放回去，别逼人再等一轮收尾
            if (result is com.example.zhengdao.baopu.PostAdoptionRollbackResult.Rejected) {
                postAdoptionQueue = postAdoptionQueue + notice
            } else {
                rememberDecisionAction("rollback", notice.skillName)
            }
            skillNote = when {
                result is com.example.zhengdao.baopu.PostAdoptionRollbackResult.Done ->
                    BaopuNote("已回滚到备份：${result.backupName}", BaopuNoteLevel.Info)

                else -> BaopuNote(
                    "没回滚：${(result as com.example.zhengdao.baopu.PostAdoptionRollbackResult.Rejected).reason}",
                    BaopuNoteLevel.Error,
                )
            }
        }
    }

    /** 卡上点「先留着」⇒ 接受风险，结案不再提。 */
    fun keepPostAdoption(eventId: String) {
        val notice = postAdoptionQueue.firstOrNull { it.eventId == eventId } ?: return
        postAdoptionQueue = postAdoptionQueue.filter { it.eventId != eventId }
        rememberDecisionAction("keep", notice.skillName)
        scope.launch {
            withContext(Dispatchers.IO) {
                postAdoptionPort().recordUserAction(
                    eventId = notice.eventId,
                    action = com.example.zhengdao.baopu.PostAdoptionAction.DISMISSED,
                    verdict = notice.verdict,
                    reason = notice.reason,
                    delta = notice.delta,
                )
            }
            skillNote = BaopuNote("这条建议不再提：${notice.skillName}", BaopuNoteLevel.Info)
        }
    }

    /** 卡上点「稍后处理」⇒ 只关卡，事件**仍未结案**，下次收尾再判。 */
    fun laterPostAdoption(eventId: String) {
        val notice = postAdoptionQueue.firstOrNull { it.eventId == eventId } ?: return
        postAdoptionQueue = postAdoptionQueue.filter { it.eventId != eventId }
        rememberDecisionAction("later", notice.skillName)
        scope.launch {
            withContext(Dispatchers.IO) {
                postAdoptionPort().recordUserAction(
                    eventId = notice.eventId,
                    action = com.example.zhengdao.baopu.PostAdoptionAction.LATER,
                    verdict = notice.verdict,
                    reason = notice.reason,
                    delta = notice.delta,
                )
            }
        }
    }

    // ── 技能沉淀（第十九批）：会话封卷 → 提炼 → 提案等确认 ──────────────────

    /**
     * 封卷当前会话（点「新会话」的第一步）：写 `closed_at` → 提炼一次 → **成功才写**
     * `refined_at`。提炼抛异常 ⇒ refined_at 不写，留给下次启动自检补算（K6）。
     */
    private fun closeCurrentSession() {
        // 触发点判据（K6）：技能提炼只挂在会话封卷，每轮结束留给记忆候选
        if (com.example.zhengdao.baopu.SkillRefine.skillTriggerFor("new_session") == null) return
        val sid = sessionId ?: return
        val now = System.currentTimeMillis()
        db.write { d ->
            val stmt = com.example.zhengdao.baopu.BaopuSql.markSessionClosed(sid, now)
            d.execSQL(stmt.sql, stmt.args)
        }
        val ok = runCatching { refineSkillsForSession(sid) }
            .onFailure {
                com.example.zhengdao.rootfs.RunLog.log(
                    "[baopu-skill] 封卷提炼失败（${it.javaClass.simpleName}）：${it.message} ⇒ 留给下次启动补算",
                )
            }
            .isSuccess
        if (ok) markRefined(sid, now)
    }

    /** 启动自检补算（K6 的另一半）：封了卷没提炼成功的会话，每个只补一次。 */
    private fun selfCheckUnclosedSessions() {
        // 触发点判据（K6）：启动自检也是技能提炼的合法触发点
        if (com.example.zhengdao.baopu.SkillRefine.skillTriggerFor("startup") == null) return
        val ids = db.read { d ->
            val out = mutableListOf<Long>()
            val stmt = com.example.zhengdao.baopu.BaopuSql.listUnclosedRefinements()
            d.rawQuery(stmt.sql, stmt.args).use { c ->
                while (c.moveToNext()) out += c.getLong(0)
            }
            out
        }
        for (sid in ids) {
            val ok = runCatching { refineSkillsForSession(sid) }
                .onFailure {
                    com.example.zhengdao.rootfs.RunLog.log(
                        "[baopu-skill] 自检补算失败（${it.javaClass.simpleName}）：${it.message}",
                    )
                }
                .isSuccess
            if (ok) markRefined(sid, System.currentTimeMillis())
        }
    }

    private fun markRefined(sid: Long, at: Long) {
        db.write { d ->
            val stmt = com.example.zhengdao.baopu.BaopuSql.markSessionRefined(sid, at)
            d.execSQL(stmt.sql, stmt.args)
        }
    }

    /**
     * 对一个会话跑技能提炼（封卷与补算共用）：从库里读该会话的用户原话（不依赖
     * 当前视图——补算时 [messages] 是别的会话），产出 [PersonaProposal]（PENDING）
     * 进 [pendingSkillProposals] 等用户拍板。**这里绝不写人设**（K4：未确认不落盘）。
     *
     * 索引总量两层合计超 20 ⇒ 拒绝并提示先删旧的（[skillNote]），**不许靠截断兜底**（K2）。
     */
    private fun refineSkillsForSession(sid: Long) {
        val pf = com.example.zhengdao.baopu.BaopuRuntime.personaFiles(appCtx)
        val bText = pf.read(com.example.zhengdao.baopu.PersonaLayer.B_GLOBAL)
        val cText = pf.read(com.example.zhengdao.baopu.PersonaLayer.C_PROJECT)
        val globalLines = com.example.zhengdao.baopu.SkillRules.parseIndexLines(
            bText, com.example.zhengdao.baopu.MemoryScope.GLOBAL,
        )
        val projectLines = com.example.zhengdao.baopu.SkillRules.parseIndexLines(
            cText, com.example.zhengdao.baopu.MemoryScope.PROJECT,
        )
        val facts = com.example.zhengdao.baopu.MemoryRefine.SessionFacts(
            sessionId = sid,
            userTexts = readUserTextsFor(sid),
            now = System.currentTimeMillis(),
        )
        // 第五十七批 §1.3：被丢弃过的候选（名称＋正文摘要 hash）这一轮直接跳过——
        // 丢弃留痕的另一半：丢过的东西不许下轮再端上来。读失败 ⇒ 空 set（只记录的链
        // 断了也不拦提炼，与「记录不阻塞」同一口径）。
        val rejectedKeys = runCatching {
            readRejectedDecisionHashes()
        }.getOrDefault(emptySet())
        val r = com.example.zhengdao.baopu.SkillRefine.refine(
            facts, globalLines + projectLines, rejectedKeys,
        )
        if (r.candidates.isEmpty()) {
            if (r.dropped > 0) {
                skillNote = BaopuNote(
                    appCtx.resources.getQuantityString(R.plurals.skill_refine_dropped, r.dropped, r.dropped),
                    BaopuNoteLevel.Error,
                )
            }
            return
        }
        val total = globalLines.size + projectLines.size
        // 上限判据只有一处算术（BaopuIndexBudget，第七十二批抽出）
        if (BaopuIndexBudget.wouldExceed(total, r.candidates.size)) {
            // K2：超上限 ⇒ 拒绝并提示先删旧的（不是截断）
            skillNote = BaopuNote(
                appCtx.resources.getQuantityString(
                    R.plurals.skill_cap_reached, total, total, com.example.zhengdao.baopu.SkillRules.INDEX_CAP,
                ),
                BaopuNoteLevel.Error,
            )
            return
        }
        for (c in r.candidates) {
            val layer = if (c.scope == com.example.zhengdao.baopu.MemoryScope.GLOBAL) {
                com.example.zhengdao.baopu.PersonaLayer.B_GLOBAL
            } else {
                com.example.zhengdao.baopu.PersonaLayer.C_PROJECT
            }
            val current = pf.read(layer).orEmpty()
            val add = com.example.zhengdao.baopu.SkillRules.addIndexLine(
                current,
                com.example.zhengdao.baopu.SkillRules.IndexLine(c.scope, c.name, c.scenario),
            )
            val newText = (add as? com.example.zhengdao.baopu.SkillRules.EditResult.Ok)?.newText
                ?: continue
            if (newText == current) continue
            val reason = appCtx.getString(
                if (c.fromUser) R.string.skill_reason_user else R.string.skill_reason_refine,
            )
            val proposal = com.example.zhengdao.baopu.PersonaRules.propose(layer, current, newText, reason)
                ?: continue // A 内核不可提案（K5）——B/C 不会走到这，防御性跳过
            pendingSkillProposals = pendingSkillProposals + proposal
        }
    }

    /** 从库里读一个会话的全部用户原话（text part 的 payload 就是正文，按序）。 */
    private fun readUserTextsFor(sid: Long): List<String> = db.read { d ->
        val out = mutableListOf<String>()
        d.rawQuery(
            "SELECT part.payload FROM message JOIN part ON part.message_id = message.id " +
                "WHERE message.session_id = ? AND message.role = 'user' AND part.kind = 'text' " +
                "ORDER BY message.seq ASC, part.seq ASC",
            arrayOf(sid.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val t = c.getString(0)
                if (!t.isNullOrBlank()) out += t
            }
        }
        out
    }

    /**
     * 技能提案：采纳。落盘前**再查一次**两层合计上限（用户可能刚在设置页手动加过）；
     * 走 [PersonaFiles.applyProposal]——它只收 PENDING 且可写层（K4 的执行面）。
     */
    fun confirmSkillProposal(p: com.example.zhengdao.baopu.PersonaProposal) {
        val pf = com.example.zhengdao.baopu.BaopuRuntime.personaFiles(appCtx)
        val total = com.example.zhengdao.baopu.SkillRules.totalIndexCount(
            pf.read(com.example.zhengdao.baopu.PersonaLayer.B_GLOBAL),
            pf.read(com.example.zhengdao.baopu.PersonaLayer.C_PROJECT),
        )
        val scope = if (p.layer == com.example.zhengdao.baopu.PersonaLayer.B_GLOBAL) {
            com.example.zhengdao.baopu.MemoryScope.GLOBAL
        } else {
            com.example.zhengdao.baopu.MemoryScope.PROJECT
        }
        val adding = BaopuIndexBudget.addedIndexLines(p.proposed, p.current, scope)
        if (BaopuIndexBudget.wouldExceed(total, adding)) {
            skillNote = BaopuNote(
                appCtx.resources.getQuantityString(
                    R.plurals.skill_cap_reached, total, total, com.example.zhengdao.baopu.SkillRules.INDEX_CAP,
                ),
                BaopuNoteLevel.Error,
            )
            return
        }
        // 第五十批：落盘前记快照；采纳成功且真的落了盘才排自检
        val personaTarget = TargetSnapshot.of(pf.fileOf(p.layer), TargetKind.PERSONA)
        val ok = pf.applyProposal(p)
        if (!ok) {
            skillNote = BaopuNote(
                appCtx.getString(R.string.skill_apply_failed),
                BaopuNoteLevel.Error,
            )
            return
        }
        pendingSkillProposals = pendingSkillProposals.filterNot { it === p }
        // 第五十七批 §1.3：采纳留痕（名字/摘要从 proposed 比 current 多出的那一行提）
        recordSkillDecisionFor(p, adopted = true, detail = null)
        // 第一百批 §1：记采纳事件（人设索引通道）。这一条**没有技能正文**（file = null ⇒
        // 版本记 "-"、备份记 false）：技能效果信号本来也量不到人设索引，所以它之后只会
        // 得到 DEFER，不会凭「没有效果信号」被判成变差——这是刻意的保守，不是漏做。
        val indexName = com.example.zhengdao.baopu.SkillRefine.proposalNameOf(p)
        if (indexName != null) {
            // 注意：本函数上面有个同名局部 val scope（MemoryScope），协程作用域要显式取成员
            this.scope.launch {
                withContext(Dispatchers.IO) {
                    val baseline = runCatching {
                        PreAdoptionBaseline(
                            com.example.zhengdao.baopu.SkillEffects.outcomesFor(db, indexName),
                            com.example.zhengdao.baopu.PostAdoptionBaseline.MEASURED,
                        )
                    }.getOrElse {
                        PreAdoptionBaseline(emptyMap(), com.example.zhengdao.baopu.PostAdoptionBaseline.UNKNOWN)
                    }
                    recordPostAdoption(
                        channel = com.example.zhengdao.baopu.PostAdoptionChannel.SKILL_PROPOSAL,
                        candidateId = null,
                        skillName = indexName,
                        file = null,
                        baseline = baseline,
                    )
                }
            }
        }
        // 第五十批：采纳成功且真的落了盘才排自检（v1）
        scheduleSelfCheck(scene = "人设/索引提案采纳", targets = listOf(personaTarget))
    }

    /** 技能提案：丢弃（只在内存列表里移除——PENDING 本来就没落盘，没有文件可删）。 */
    fun discardSkillProposal(
        p: com.example.zhengdao.baopu.PersonaProposal,
        reason: String? = null,
    ) {
        pendingSkillProposals = pendingSkillProposals.filterNot { it === p }
        // 第五十七批 §1.3：丢弃留痕（理由可空不强制；记了 hash，下轮提炼跳过同一候选）
        recordSkillDecisionFor(p, adopted = false, detail = reason?.take(120))
    }

    // ── 第五十七批 §1.3：采纳/丢弃留痕（skill_event 表；只记录、写失败只 log）────

    /**
     * 记一条 decision（学习卡候选口径：名字＋content 摘要 hash）。
     * 写失败只 RunLog——留痕是账本不是闸门，账本卡住业务就是本末倒置（红线原话口径）。
     */
    private fun recordSkillDecision(name: String, adopted: Boolean, hash: String?, detail: String?) {
        runCatching {
            val stmt = com.example.zhengdao.baopu.BaopuSql.insertSkillDecision(
                "skill", name, adopted, hash, detail, System.currentTimeMillis(),
            )
            db.write { d -> d.execSQL(stmt.sql, stmt.args) }
                .get(3, java.util.concurrent.TimeUnit.SECONDS)
        }.onFailure {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴：skill_decision 记录失败（不影响本轮）${it.javaClass.simpleName}: ${it.message}",
            )
        }
    }

    /** 封卷确认卡候选口径：名字/摘要从 proposed 比 current 多出的那一行提（提不出 ⇒ 不记 hash）。 */
    private fun recordSkillDecisionFor(
        p: com.example.zhengdao.baopu.PersonaProposal,
        adopted: Boolean,
        detail: String?,
    ) {
        val name = com.example.zhengdao.baopu.SkillRefine.proposalNameOf(p)
        if (name == null) {
            com.example.zhengdao.rootfs.RunLog.log("抱朴：提案留痕提不出名字，只记条目不记 hash")
            recordSkillDecision("（提不出名字）", adopted, hash = null, detail = detail)
            return
        }
        recordSkillDecision(name, adopted, com.example.zhengdao.baopu.SkillRefine.dedupeKeyOfProposal(p), detail)
    }

    /** 被**丢弃**过的候选去重键全集（提炼时跳过；读失败由调用方当空 set 处理）。 */
    private fun readRejectedDecisionHashes(): Set<String> {
        val stmt = com.example.zhengdao.baopu.BaopuSql.rejectedDecisionHashes()
        return db.read { d ->
            d.rawQuery(stmt.sql, stmt.args).use { c ->
                buildSet {
                    while (c.moveToNext()) c.getString(0)?.let { add(it) }
                }
            }
        }
    }

    /**
     * 清掉技能动作那句话。
     *
     * 第六十一批 §2：同 [clearLibraryNote]——进度走 8 秒自清，错误只走用户那一击。
     */
    fun clearSkillNote() {
        skillNote = null
    }

    /**
     * 重读消息列表 ＋ 中断标记 ＋ 记忆候选（一次 db 读，不逐事件逐表）。
     *
     * P2-1（第五十六批）：**库读全部在 `Dispatchers.IO` 上做**。旧实现在主线程直读
     * `readMessages` / `readStepRows` / `interruptedStep`——引擎事件每个都回主线程调一次
     * refresh，消息多时逐帧掉。状态写回仍留主线程：2026-10-17 真机两次实测过「后台线程
     * 直写 Compose 状态会时灵时不灵」，那条教训不变，所以只搬读、不搬写。
     *
     * 对外语义不变：`force` 仍表示「不看在不在跑，直接重算中断标记」；更新顺序
     * （消息 → 步骤行 → 自省便签 → 中断标记 → 确认卡 → 压缩细条 → 记忆候选）与原实现一致。
     *
     * @param force true = 不看"在不在跑"，直接重算中断标记（[EngineEvent.Settled] 专用）
     */
    fun refresh(force: Boolean = false) {
        scope.launch {
            val sid = sessionId
            val recomputeInterrupt = force || !runningNow()
            val queued = if (sid == null) 0 else engine.queuedCount(sid)
            val snap = if (sid == null) null else withContext(Dispatchers.IO) {
                // 第九十一批：记忆一次读、两处用（候选列表 ＋ 注入预算预览），
                // 不再在 refresh 收尾时回主线程读文件。
                val memories = store.loadAll()
                RefreshSnapshot(
                    messages = readMessages(sid),
                    stepRows = readStepRows(sid),
                    interruptedStep = if (recomputeInterrupt) BaopuRuntime.interruptedStep(db, sid) else null,
                    pendingGone = pendingStore.load(sid) == null,
                    compression = runCatching { SessionCompressionStore(db).latest(sid) }.getOrNull(),
                    memories = memories,
                    injectionPreview = MemoryInjectionBudget.plan(
                        memories.filter { it.status == MemoryStatus.CONFIRMED },
                        System.currentTimeMillis(),
                    ),
                )
            }
            messages = snap?.messages ?: emptyList()
            // 步骤卡汇总行跟着消息一起刷（同一轮生命周期；只读一条 SELECT，跟着 refresh 走最省心）
            stepRows = snap?.stepRows ?: emptyList()
            // 排队计数跟引擎内存队列走（切回页面/后台跑完都靠这一下对齐）
            if (sid != null) queuedCount = queued
            if (introspectionNote == null && sid != null) {
                introspectionNote = runCatching { notePrefs.getString("note_$sid", null) }.getOrNull()
            }
            // 中断标记：**引擎在跑的时候不刷新**。真机实测（2026-10-17）两条教训：
            // ① 跑到第 N 步时库里最后一条 run_log 正是 Thinking/Acting，此刻重算会把
            //    "正在思考"误读成"被杀了"——所以运行中跳过；
            // ② 判"在不在跑"**不能看 `engine.state`**：那是后台线程写的 Compose State，
            //    主线程读到的可能是旧值（本次实测读到过 DONE），于是运行中照样重算并误报。
            //    改用 `ActiveSessionGate`（AtomicReference，跨线程立即可见）——它在整个
            //    循环期间被持有，收尾 finally 里才释放，正是"本轮还没结束"的真值。
            // 收尾那一下引擎会发 `EngineEvent.Settled`，带着 `force = true` 过来，因此
            // "跑完了红字还不下来"这种情况不可能再出现（不依赖上面那个判据的对错）。
            if (recomputeInterrupt) interruptedStep = snap?.interruptedStep ?: -1
            // 确认卡的生命周期跟 DB 的 pending 行走（派工单 §3.3）：活的卡在等 ⇒ 库里
            // 有行；引擎超时/处理完清行 ⇒ 卡跟着消失（不等用户手动关——T4 的「卡消失」）。
            if (confirmCard != null && snap?.pendingGone == true) {
                confirmCard = null
                confirmRestored = false
            }
            // 压缩细条跟着库走：压成功那一刻库里才有行，细条才出现（失败/跳过 ⇒ 不出现，
            // 不许凭空报「已压缩」）。一条主键查询，跟着 refresh 走最省心，也最不容易漂。
            val rec = snap?.compression
            if (sid != null && rec != null) {
                engine.restoreCompression(rec.summary, rec.compressedIds.toSet())
                compressionNote = CompressionNote(count = rec.compressedIds.size, summary = rec.summary)
            }
            refreshMemory(snap?.memories, snap?.injectionPreview)
        }
    }

    /** [refresh] 在 IO 上读到的一批快照（读与写分开：读在 IO，写回主线程）。 */
    private data class RefreshSnapshot(
        val messages: List<BaopuUiMessage>,
        val stepRows: List<BaopuStepRow>,
        val interruptedStep: Int?,
        val pendingGone: Boolean,
        val compression: com.example.zhengdao.baopu.CompressionRecord?,
        /** 第九十一批：全会话记忆（IO 段读，主线程只消费）。 */
        val memories: List<MemoryEntry>,
        /** 第九十一批：注入预算只读预览（纯计算，不写盘、不删记忆）。 */
        val injectionPreview: MemoryInjectionBudget.Plan,
    )

    /** 本轮是否还在跑（单活跃门的持有即"在跑"）。 */
    private fun runningNow(): Boolean =
        com.example.zhengdao.baopu.agent.ActiveSessionGate.current() != null

    /**
     * 读全会话 run_log 活动行（十六批第一件 · **只读**；SQL 是
     * [com.example.zhengdao.baopu.BaopuSql.RUN_LOG_STEPS_SELECT] 那一条常量）。
     * 汇总判据不在这里：窗口切片与步数/失败数/用时全在 UI 层纯函数（可 JVM 直测）。
     */
    private fun readStepRows(sessionId: Long): List<BaopuStepRow> = db.read { d ->
        d.rawQuery(
            com.example.zhengdao.baopu.BaopuSql.RUN_LOG_STEPS_SELECT,
            arrayOf(sessionId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        BaopuStepRow(
                            tool = c.getString(0),
                            ok = c.getInt(1) == 1,
                            latencyMs = if (c.isNull(2)) 0L else c.getLong(2),
                            err = if (c.isNull(3)) "" else c.getString(3),
                            createdAt = c.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 刷新记忆候选与注入预算预览。
     *
     * [all] / [preview] 由 [refresh] 在 IO 段读好传入；其它调用点（确认/忘记之后）
     * 不传 ⇒ 沿用旧行为现场读（这两处是低频用户操作，不在每帧热路径上）。
     */
    private fun refreshMemory(
        all: List<MemoryEntry>? = null,
        preview: MemoryInjectionBudget.Plan? = null,
    ) {
        val entries = all ?: store.loadAll()
        memoryCandidates = entries.filter { it.status == MemoryStatus.CANDIDATE }
        memoryInjectionPreview = preview ?: MemoryInjectionBudget.plan(
            entries.filter { it.status == MemoryStatus.CONFIRMED },
            System.currentTimeMillis(),
        )
    }

    private fun readMessages(sessionId: Long): List<BaopuUiMessage> = db.read { d ->
        d.rawQuery(
            "SELECT message.id, message.role, message.created_at, part.kind, part.payload " +
                "FROM message LEFT JOIN part ON part.message_id = message.id " +
                "WHERE message.session_id = ? ORDER BY message.seq ASC, part.seq ASC",
            arrayOf(sessionId.toString()),
        ).use { c ->
            // 装配判据（plan 进 msg.plan、tool JSON、空消息丢不丢）是纯函数，
            // 第七十二批抽到 BaopuMessageAssembler；这里只把游标摊成行。
            val rows = buildList {
                while (c.moveToNext()) {
                    add(
                        BaopuPartRow(
                            messageId = c.getLong(0),
                            role = c.getString(1),
                            createdAt = if (c.isNull(2)) 0L else c.getLong(2),
                            kind = if (c.isNull(3)) "text" else c.getString(3),
                            payload = if (c.isNull(4)) "" else c.getString(4),
                        ),
                    )
                }
            }
            BaopuMessageAssembler.assemble(rows)
        }
    }

    /** 索引同步（0b 定的职责：文件是真相，SQLite 只是索引）。 */
    private fun syncIndex(entry: com.example.zhengdao.baopu.MemoryEntry) {
        db.write { d ->
            val stmt = com.example.zhengdao.baopu.BaopuSql.upsertMemory(entry)
            d.execSQL(stmt.sql, stmt.args)
        }
    }
}
