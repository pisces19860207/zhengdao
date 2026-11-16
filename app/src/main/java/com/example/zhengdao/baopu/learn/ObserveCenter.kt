// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 学习板块阶段一 · 观察中枢（进程级一份；房型照 BrowserPort 的可注入单例）。
//
// 职责：轮询驱动（每 1.2s 收割活跃页签一次——调用方是 BrowserScreen 的组合内循环，
// 这里不做全局协程）；会话生命周期（开关关／浏览器离树／空闲 10 分钟 ⇒ 收会话）；
// 收会话 ⇒ 四类校验 ⇒ 未学会清单；「就教这一步」的武装与复验；24h 自清。
//
// 依赖注入（判据①④的可测性就在这里）：`enabled`（开关供给方）、`clock`（时间）、
// `harvest`（事件收割通道）＋`nodeTable`（一次性观察面通道）。生产接线在 install(Context)
// ——两通道都只认 BrowserPort（probe＝模板脚本／observe＝NODE_TABLE 枚举）；
// JVM 测试用 install(root,…) 注假件。
// **不记正文**在结构上封死：这里流动的只有 ObserveRows 的封闭键行，没有字符串正文通道。
package com.example.zhengdao.baopu.learn

import android.content.Context
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.baopu.TeachReplayStore
import com.example.zhengdao.baopu.TeachSkillDraft
import com.example.zhengdao.bridge.BrowserPort
import com.example.zhengdao.bridge.BrowserPort.PortCaller
import com.example.zhengdao.bridge.BrowserPort.PortPolicy
import com.example.zhengdao.bridge.ObserveItem
import com.example.zhengdao.core.RunLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 「就教这一步」的一次武装（等页面上匹配 kind 的下一条事件，超时自动解除）。 */
internal data class ArmedTeach(
    val itemId: String,
    val kind: String,
    val url: String,
    val deadlineMs: Long,
)

/** 给 UI 的轻提示（文案键在 UI 层映射；arg 是人话参数，如条目页名；seq 保证同款连发不丢）。 */
internal enum class LearnToastKind {
    ANSWERED, CAPTURE_WAITING, TAUGHT, KEEP_AMBIGUOUS, CAPTURE_TIMEOUT,
    DRAFT_CREATED, DRAFT_MERGED, DRAFT_REJECTED,
}

internal data class LearnToast(val seq: Int, val kind: LearnToastKind, val arg: String = "")

/** 文案缺省（install 前的占位；生产必被资源文案覆盖，install 时传入）。 */
private val DEFAULT_TEXTS = LearnCardTexts(
    qAmbiguous = "第 {步} 步「{目标}」在页面上有 {条数} 条走法，你平时用哪条？",
    qNoEvidence = "第 {步} 步点了「{目标}」但页面没变化，这一步想做什么？",
    qParam = "第 {步} 步「{目标}」填的内容每次都不同吗？",
    qIntent = "这次是在「{页}」做哪件事？",
    optParam = "是参数（每次会变）",
    optFixed = "固定值（每次一样）",
    optTeach = "就教这一步",
    optDrop = "这一步不算",
    freeText = "或者直接说你的想法",
)

/** 观察中枢（单一入口页与浏览器胶囊共用的同一个真相源）。 */
internal object ObserveCenter {

    /** 收割间隔（ms）：TeachRecorder 600ms 的近亲；只在浏览器前台跑。 */
    const val TICK_MS: Long = 1_200L

    /** 空闲收会话阈值（ms）。 */
    const val IDLE_MS: Long = 600_000L

    /** 武装超时（ms）：到点没等到那一步，自动解除。 */
    const val ARM_TIMEOUT_MS: Long = 60_000L

    /** 「重新教这一步」的武装 id 前缀（它不是清单条目，只是同一套武装通道的一次占用）。 */
    const val RETEACH_ITEM_PREFIX: String = "reteach:"

    @Volatile private var rootDir: File? = null
    @Volatile private var clock: () -> Long = System::currentTimeMillis

    /** 事件收割通道（生产＝BrowserPort.probe＋本层模板脚本；见 install 注释）。 */
    @Volatile private var harvest: (tabId: Int?) -> String? = { _ -> null }

    /** 一次性观察面通道（生产＝BrowserPort.observe＋NODE_TABLE 枚举；脚本在 assets，传不进）。 */
    @Volatile private var nodeTable: (tabId: Int?) -> String? = { _ -> null }

    @Volatile private var enabled: () -> Boolean = { false }
    @Volatile private var texts: LearnCardTexts = DEFAULT_TEXTS

    private var store: NotLearnedStore = NotLearnedStore(File("/dev/null", "x"))
    private var session: ObserveSession? = null
    private var draftStore: SkillDraftStore = SkillDraftStore(File("/dev/null", "x"))

    /** 冲突步人裁的落盘壳（672；与草案同目录、不同前缀，见 [DraftAdjudicationStore]）。 */
    private var adjudicationStore: DraftAdjudicationStore = DraftAdjudicationStore(File("/dev/null", "x"))

    /** 最近收口的会话快照（B 归纳的输入；「从最近观察建草案」用）。 */
    @Volatile private var lastSession: ObserveSnapshot? = null

    /** 确认转正的落盘依赖（工作区根／全局根／人设文件；缺省 null ⇒ confirm 如实拒）。 */
    @Volatile private var projectRootSupplier: (() -> File)? = null
    @Volatile private var globalRootSupplier: (() -> File)? = null
    @Volatile private var personaFilesSupplier: (() -> com.example.zhengdao.baopu.PersonaFiles)? = null

    /** 关→开之间积在页内队列的批次**丢弃**（判据①「关后 0 记录」：关着时的操作不补记）。 */
    @Volatile private var discardNext = false

    private val notLearnedFlow = MutableStateFlow(LearnBoardState())
    private val sessionActiveFlow = MutableStateFlow(false)
    private val armedFlow = MutableStateFlow<ArmedTeach?>(null)
    private val toastFlow = MutableStateFlow<LearnToast?>(null)
    private val teachRequestFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val draftsFlow = MutableStateFlow<List<SkillDraft>>(emptyList())
    private val adjudicationsFlow = MutableStateFlow<Map<String, List<DraftAdjudication>>>(emptyMap())
    private var toastSeq = 0

    val notLearned: StateFlow<LearnBoardState> get() = notLearnedFlow.asStateFlow()
    val sessionActive: StateFlow<Boolean> get() = sessionActiveFlow.asStateFlow()
    val armed: StateFlow<ArmedTeach?> get() = armedFlow.asStateFlow()

    /** 最近一条轻提示（StateFlow＋seq：UI 侧 collectAsState 后按 seq 变化弹 Toast）。 */
    val lastToast: StateFlow<LearnToast?> get() = toastFlow.asStateFlow()

    /** 学习板块页（治理中心）点「就教这一步」：武装＋请求切玄览（MainActivity 收口）。 */
    val teachRequests: SharedFlow<String> get() = teachRequestFlow.asSharedFlow()

    /** 技能草稿区读数（B/C 产物；人批前不落技能）。 */
    val drafts: StateFlow<List<SkillDraft>> get() = draftsFlow.asStateFlow()

    /** 冲突步的人裁记录（672；键＝草案 id——裁定卡与「转正」钮都读这一份）。 */
    val adjudications: StateFlow<Map<String, List<DraftAdjudication>>> get() = adjudicationsFlow.asStateFlow()

    // ── 接线 ─────────────────────────────────────────────────────────────────

    /**
     * 生产接线（ZhengdaoApp 调一次）：落 `filesDir/抱朴记忆/观察/`，文案走资源。
     *
     * 通道（622/645 判据①：**除 BrowserPort 内部实现外零 BrowserCommands 引用**）：
     *   · 事件收割（自装自收模板脚本）走 `BrowserPort.probe`（TeachRecorder 同款口径，
     *     645 交付报告 §四2/§七1 授权 624 裁定：模板脚本性质，非自由 JS）；
     *   · 节点表（一次性观察面）走 `BrowserPort.observe`——调用方只传 `ObserveItem`
     *     枚举（NODE_TABLE），脚本内置 assets、hash 写死，传不进任何脚本或参数。
     */
    fun install(context: Context, cardTexts: LearnCardTexts) {
        val app = context.applicationContext
        projectRootSupplier = { com.example.zhengdao.baopu.BaopuRuntime.workspaceRoot(app) }
        globalRootSupplier = { File(app.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY) }
        personaFilesSupplier = { com.example.zhengdao.baopu.BaopuRuntime.personaFiles(app) }
        install(
            root = File(MemoryLayout.globalMemoryDir(app.filesDir), DIR_NAME),
            cardTexts = cardTexts,
            clock = System::currentTimeMillis,
            harvest = { tabId ->
                BrowserPort.probe(PortCaller.TEACH_RECORD, ObserveScript.DRAIN, tabId?.toString(), PortPolicy.OPEN)
            },
            nodeTable = { tabId ->
                BrowserPort.observe(PortCaller.TEACH_RECORD, ObserveItem.NODE_TABLE, app, tabId?.toString(), PortPolicy.OPEN)
            },
            enabled = { ObservePrefs.on(app) },
        )
    }

    /** 可测接线（JVM）：全依赖注入，磁盘指到 TempSandbox。 */
    fun install(
        root: File,
        cardTexts: LearnCardTexts,
        clock: () -> Long,
        harvest: (tabId: Int?) -> String?,
        nodeTable: (tabId: Int?) -> String?,
        enabled: () -> Boolean,
    ) {
        this.rootDir = root
        this.texts = cardTexts
        this.clock = clock
        this.harvest = harvest
        this.nodeTable = nodeTable
        this.enabled = enabled
        this.store = NotLearnedStore(File(root, NotLearnedStore.FILE_NAME))
        this.draftStore = SkillDraftStore(File(root, SkillDraftStore.DIR_NAME))
        this.adjudicationStore = DraftAdjudicationStore(File(root, SkillDraftStore.DIR_NAME))
        notLearnedFlow.value = store.state()
        refreshDrafts()
        pruneAll()
    }

    /** 测试复位（install 前／后都可调；生产代码不许调）。 */
    fun resetForTest() {
        synchronized(LOCK) {
            session = null
            discardNext = false
            armedFlow.value = null
            notLearnedFlow.value = LearnBoardState()
            sessionActiveFlow.value = false
        }
    }

    // ── 轮询（BrowserScreen 每 TICK_MS 调一次；IO 上跑，收割是阻塞式 evaluate）────

    suspend fun tick(tabId: Int?) = withContext(Dispatchers.IO) {
        val now = clock()
        if (!enabled()) {
            tickWhenDisabled(tabId)
            return@withContext
        }
        val raw = runCatching { harvest(tabId) }.getOrNull() ?: return@withContext
        val result = DrainResult.parse(raw) ?: return@withContext
        if (discardNext) {
            discardNext = false // 这一批是关着时积下的：只清页内队列，不入账
            return@withContext
        }
        val active = ensureSession(now) ?: return@withContext
        active.ingest(result)
        fetchNodesIfNeeded(active, result, tabId, now)
        sessionActiveFlow.value = true
        if (armedFlow.value != null) tryCapture(active, result)
        if (active.isIdle(IDLE_MS)) closeSession()
    }

    /** 关态轮询：收会话；「就教这一步」的武装照复验（显式教学不受开关牵连），但**不入观察账**。 */
    private fun tickWhenDisabled(tabId: Int?) {
        closeSession()
        if (armedFlow.value == null) return
        val raw = runCatching { harvest(tabId) }.getOrNull() ?: return
        val result = DrainResult.parse(raw) ?: return
        tryCapture(null, result)
    }

    /** 页面变化（新文档／url／节点数）⇒ 端口 OBSERVE(NODE_TABLE) 现取一份定位面（2s 节流）。 */
    private fun fetchNodesIfNeeded(active: ObserveSession, result: DrainResult, tabId: Int?, now: Long) {
        val facts = result.page ?: active.lastFacts(result.docId)
        if (!active.needsNodeTable(result.docId, facts, now)) return
        runCatching { nodeTable(tabId) }.getOrNull()
            ?.let { ObserveSurface.parse(it, result.docId, now) }
            ?.let { active.ingestNodes(it) }
    }

    /** 浏览器离树（DisposableEffect onDispose）：会话就地收，触发卡照出。 */
    fun onBrowserGone() {
        closeSession()
    }

    /** 开关拨动后的回读（关 ⇒ 立刻收会话＋丢弃在途批次：关后 0 记录，判据①）。 */
    fun refresh() {
        if (!enabled()) {
            closeSession()
            discardNext = true
        }
    }

    // ── F：未学会清单与「就教这一步」───────────────────────────────────────────

    fun answer(itemId: String, text: String) {
        store.answer(itemId, text, clock())
        notLearnedFlow.value = store.state()
        toast(LearnToastKind.ANSWERED)
    }

    /** 观察数据文件数（学习板块页的摘要行；不含未学会.json）。 */
    fun dataFileCount(): Int = rootDir?.listFiles()?.count { it.name != NotLearnedStore.FILE_NAME } ?: 0

    fun clearItems() {
        store.clear()
        notLearnedFlow.value = store.state()
    }

    /** 清观察数据（学习板块页「清空观察数据」；未学会清单与技能草稿区不受影响，按钮分开）。 */
    fun clearObservations() {
        closeSession()
        val dir = rootDir ?: return
        dir.listFiles()?.forEach { f ->
            if (f.name != NotLearnedStore.FILE_NAME && f.name != SkillDraftStore.DIR_NAME) {
                runCatching { f.delete() }
            }
        }
        RunLog.log("学习板块：观察数据已清空")
    }

    fun requestTeachStep(itemId: String) {
        val item = notLearnedFlow.value.items.firstOrNull { it.id == itemId } ?: return
        armedFlow.value = ArmedTeach(itemId, item.kind, item.url, clock() + ARM_TIMEOUT_MS)
        toast(LearnToastKind.CAPTURE_WAITING, item.pageName)
        teachRequestFlow.tryEmit(itemId)
    }

    // ── B/C：意图归纳与多次归并（阶段二）───────────────────────────────────────

    /**
     * 「从最近观察建草案」（B 的入口）：把最近收口的会话归纳成技能草案进草稿区。
     * goal 由人给（观察数据里没有意图，不猜）。同意图草案已在区里 ⇒ 自动归并成一条
     * （C；判据③的接线点），冲突步两套候选并存（判据⑤），不转正。
     */
    fun induceDraft(goal: String): SkillDraftStore.AddOutcome {
        val snapshot = lastSession
            ?: return SkillDraftStore.AddOutcome.Rejected(listOf("还没有观察会话——先在玄览里操作几步"))
        val sessionId = snapshot.openedAtMs.toString(36)
        val outcome = when (val o = DraftInducer.induce(snapshot, sessionId, goal, clock().toString(36))) {
            is DraftInducer.Outcome.Drafted -> o.draft
            is DraftInducer.Outcome.Rejected -> return rejected(o.problems)
            DraftInducer.Outcome.NoSteps -> return rejected(
                listOf("那段轨迹里没有可成步的操作（只有滚动或空会话）"),
            )
        }
        val result = draftStore.add(outcome, clock().toString(36))
        refreshDrafts()
        when (result) {
            is SkillDraftStore.AddOutcome.Merged -> toast(LearnToastKind.DRAFT_MERGED, result.withN.toString())
            is SkillDraftStore.AddOutcome.Created -> toast(LearnToastKind.DRAFT_CREATED)
            else -> Unit
        }
        return result
    }

    /** 人批转正（判据⑥）：草案 → zl-teach/2 → TeachReplayStore.saveConfirmed（凭证/不可逆/入库三闸照走）。 */
    suspend fun confirmDraft(id: String, skillName: String, paramDefaults: Map<String, String>): String = withContext(Dispatchers.IO) {
        val draft = draftStore.byId(id)
            ?: return@withContext "草案不存在（可能已被归并或清掉）"
        val adjudications = adjudicationStore.load(id)
        val skill = when (val c = DraftConverter.toSkill(draft, skillName, paramDefaults, clock(), adjudications)) {
            is DraftConverter.Outcome.Skill -> c.skill
            is DraftConverter.Outcome.Refused -> return@withContext c.reason
        }
        val projectRoot = projectRootSupplier?.invoke()
            ?: return@withContext "转正落点未接线（工作区根缺失）——如实拒，不猜"
        val result = TeachReplayStore.saveConfirmed(
            draft = TeachSkillDraft(skill, warnings = emptyList(), summary = draft.goal),
            projectRoot = projectRoot,
            globalRoot = globalRootSupplier?.invoke() ?: File(projectRoot, ".."),
            personaFiles = personaFilesSupplier?.invoke()
                ?: return@withContext "人设文件未接线——如实拒，不猜",
            stampText = clock().toString(36),
        )
        if (result.written != null) {
            draftStore.resolve(id)
            adjudicationStore.clear(id) // 草案转正走了，裁定跟着走（不留在盘上误导下一条）
            refreshDrafts()
            RunLog.log("学习板块：草案已转正为技能（$skillName，经 643 候选链）")
        }
        result.reason ?: "已落成技能：$skillName"
    }

    /** 弃掉一条草案（裁决一起弃——裁定是这条草案的附注，草案没了就无处可依）。 */
    fun discardDraft(id: String) {
        draftStore.resolve(id)
        adjudicationStore.clear(id)
        refreshDrafts()
    }

    // ── 672：冲突步人裁（三选一口气；**没有自动路径**）────────────────────────────

    /**
     * 人裁一条冲突步（判据②）：[choice] ∈ A／B／none，落盘 `resolved`＋时间＋[by]。
     *
     * 为什么签名里必须有 [by] 且缺省是 [DraftAdjudications.USER]：裁定是**人握主权**的动作，
     * 记录没有主体就等于没有发生过。本函数只由治理中心「学习」区的卡片调用
     * （源码断言见 DraftAdjudicateSourceGateTest；工具层没有任何入口）。
     *
     * 返回一句人话（成功／失败都如实说；不抛）。
     */
    fun adjudicate(
        draftId: String,
        stepIndex: Int,
        choice: DraftChoice,
        by: String = DraftAdjudications.USER,
    ): String {
        val draft = draftStore.byId(draftId) ?: return "草案不存在（可能已被归并或清掉）"
        val step = draft.skeleton.firstOrNull { it.index == stepIndex } ?: return "草案里没有第 $stepIndex 步"
        if (step.candidates.isEmpty()) return "第 $stepIndex 步没有定位冲突，不用裁"
        val slot = when (choice) {
            DraftChoice.A -> 0
            DraftChoice.B -> 1
            DraftChoice.NONE -> return record(draftId, stepIndex, choice, by, "已记为「都不对」——回「重新教这一步」")
        }
        if (step.candidates.getOrNull(slot) == null) {
            return "第 $stepIndex 步只有 ${step.candidates.size} 套定位，没有走法 ${choice.wire} 可裁"
        }
        return record(draftId, stepIndex, choice, by, "已记下：第 $stepIndex 步用走法 ${choice.wire}")
    }

    /** 「重新教这一步」的武装（判据②的出口：裁 none 不是终点，是回到玄览把这一步再做一遍）。 */
    fun armReteachStep(url: String, kind: String) {
        if (url.isBlank() || kind.isBlank()) return
        armedFlow.value = ArmedTeach(RETEACH_ITEM_PREFIX + url + "#" + kind, kind, url, clock() + ARM_TIMEOUT_MS)
        toast(LearnToastKind.CAPTURE_WAITING)
        teachRequestFlow.tryEmit("reteach")
    }

    /** 最近收口会话的只读快照（裁定卡算命中数与证据回读用；没有 ⇒ null，卡面如实说未知）。 */
    fun observation(): ObserveSnapshot? = lastSession

    private fun record(
        draftId: String,
        stepIndex: Int,
        choice: DraftChoice,
        by: String,
        okText: String,
    ): String {
        val record = DraftAdjudication(stepIndex, choice, clock(), by.ifBlank { DraftAdjudications.USER })
        if (!adjudicationStore.put(draftId, record)) return "裁定没写进盘（存储不可写）——如实说，不装作已记"
        refreshAdjudications()
        return okText
    }

    // ── 654：模型提交口与轨迹摘要（两条归纳路共用同一道结构门）────────────────────

    /**
     * 模型提交草案（`learn_draft_submit` 的落点）：解析 → [SkillDraftRules] → 草稿区。
     *
     * 与 [induceDraft]（脚本侧）**只差归纳者**：录入、校验、归并、落区全同一条链——
     * 结构门只有一处，写口只有 [SkillDraftStore] 一处（本函数不碰文件）。
     * 收进草稿区**不转正**（转正仍需人批 [confirmDraft]）。
     */
    fun submitDraft(payload: JSONObject): LearnSubmit.Outcome {
        val now = clock()
        val result = LearnSubmit.submit(
            payload = payload,
            newId = now.toString(36),
            lastSessionId = lastSession?.openedAtMs?.toString(36),
            store = draftStore,
        )
        when (result) {
            is LearnSubmit.Outcome.Accepted -> {
                refreshDrafts()
                if (result.merged) toast(LearnToastKind.DRAFT_MERGED, result.withN.toString())
                else toast(LearnToastKind.DRAFT_CREATED)
            }
            is LearnSubmit.Outcome.Rejected -> toast(LearnToastKind.DRAFT_REJECTED, result.reasons.joinToString("；"))
        }
        return result
    }

    /** 最近一次教学轨迹的摘要（≤300 字；没有会话 ⇒ 空串，由调用方如实说）。 */
    fun traceSummary(): String = lastSession?.let { TraceSummary.of(it) } ?: ""

    private fun refreshDrafts() {
        draftsFlow.value = draftStore.pending()
        refreshAdjudications()
    }

    /** 裁定读数回读（一份草案一个文件；空文件（未裁）不入表——UI 侧 `.orEmpty()` 兜住）。 */
    private fun refreshAdjudications() {
        adjudicationsFlow.value = adjudicationStore.all()
    }

    private fun rejected(problems: List<String>): SkillDraftStore.AddOutcome.Rejected {
        toast(LearnToastKind.DRAFT_REJECTED, problems.joinToString("；"))
        return SkillDraftStore.AddOutcome.Rejected(problems)
    }

    private fun toast(kind: LearnToastKind, arg: String = "") {
        toastSeq += 1
        toastFlow.value = LearnToast(toastSeq, kind, arg)
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private fun ensureSession(now: Long): ObserveSession? {
        session?.let { return it }
        val root = rootDir ?: return null
        val id = now.toString(36)
        val created = ObserveSession(id, File(root, ObserveSession.fileNameFor(id)), clock)
        session = created
        return created
    }

    private fun closeSession() {
        val active = session ?: return
        session = null
        sessionActiveFlow.value = false
        val snapshot = runCatching { active.close() }.getOrNull() ?: return
        lastSession = snapshot // B 归纳的输入（「从最近观察建草案」）
        val triggers = LearnTriggers.validate(snapshot)
        if (triggers.isEmpty()) return
        val now = clock()
        store.add(
            triggers.map { t -> LearnTriggers.toItem(t, LearnTriggers.card(t, texts), now) },
        )
        notLearnedFlow.value = store.state()
    }

    private fun tryCapture(active: ObserveSession?, result: DrainResult) {
        val arm = armedFlow.value ?: return
        val now = clock()
        if (now > arm.deadlineMs) {
            armedFlow.value = null
            toast(LearnToastKind.CAPTURE_TIMEOUT)
            return
        }
        val ev = result.events.firstOrNull { it.kind.wire == arm.kind } ?: return
        val page = active?.latestPage(ev.docId)
        // 复验：命中 >1 ⇒ 定位仍不唯一，条目保留（不教含糊的一步）；命中 ≤1 ⇒ 只补这一步
        if (LearnTriggers.locatorMatchCount(page, ev.faces) > 1) {
            armedFlow.value = null
            toast(LearnToastKind.KEEP_AMBIGUOUS)
            return
        }
        store.taught(arm.itemId, ev.kind.wire + " @ " + ev.url, now)
        notLearnedFlow.value = store.state()
        armedFlow.value = null
        toast(LearnToastKind.TAUGHT)
    }

    private fun pruneAll() {
        val dir = rootDir ?: return
        val horizon = clock() - NotLearnedStore.TTL_MS
        dir.listFiles()?.forEach { f ->
            val id = f.name.removePrefix(ObserveSession.FILE_PREFIX).removeSuffix(ObserveSession.FILE_SUFFIX)
            val opened = id.toLongOrNull(36)
            if (opened != null && opened < horizon) runCatching { f.delete() }
        }
        store.prune(clock())
        notLearnedFlow.value = store.state()
    }

    private val LOCK = Any()
}

/** 学习板块的私有目录段（`filesDir/抱朴记忆/观察/`；231 原文）。 */
internal const val DIR_NAME: String = "观察"
