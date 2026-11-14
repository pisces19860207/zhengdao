// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 · 学习板块阶段一 · 观察中枢（进程级一份；房型照 BrowserPort 的可注入单例）。
//
// 职责：轮询驱动（每 1.2s 收割活跃页签一次——调用方是 BrowserScreen 的组合内循环，
// 这里不做全局协程）；会话生命周期（开关关／浏览器离树／空闲 10 分钟 ⇒ 收会话）；
// 收会话 ⇒ 四类校验 ⇒ 未学会清单；「就教这一步」的武装与复验；24h 自清。
//
// 依赖注入三件（判据①④的可测性就在这里）：`enabled`（开关供给方）、`clock`（时间），
// `drain`（收割通道）。生产接线在 install(Context)；JVM 测试用 install(rootDir,…)。
// **不记正文**在结构上封死：这里流动的只有 ObserveRows 的封闭键行，没有字符串正文通道。
package com.example.zhengdao.baopu.learn

import android.content.Context
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.bridge.BrowserCommands
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
internal enum class LearnToastKind { ANSWERED, CAPTURE_WAITING, TAUGHT, KEEP_AMBIGUOUS, CAPTURE_TIMEOUT }

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

    @Volatile private var rootDir: File? = null
    @Volatile private var clock: () -> Long = System::currentTimeMillis
    @Volatile private var drain: (tabId: Int?, script: String) -> String? = { _, _ -> null }
    @Volatile private var enabled: () -> Boolean = { false }
    @Volatile private var texts: LearnCardTexts = DEFAULT_TEXTS

    private var store: NotLearnedStore = NotLearnedStore(File("/dev/null", "x"))
    private var session: ObserveSession? = null

    /** 关→开之间积在页内队列的批次**丢弃**（判据①「关后 0 记录」：关着时的操作不补记）。 */
    @Volatile private var discardNext = false

    private val notLearnedFlow = MutableStateFlow(LearnBoardState())
    private val sessionActiveFlow = MutableStateFlow(false)
    private val armedFlow = MutableStateFlow<ArmedTeach?>(null)
    private val toastFlow = MutableStateFlow<LearnToast?>(null)
    private val teachRequestFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private var toastSeq = 0

    val notLearned: StateFlow<LearnBoardState> get() = notLearnedFlow.asStateFlow()
    val sessionActive: StateFlow<Boolean> get() = sessionActiveFlow.asStateFlow()
    val armed: StateFlow<ArmedTeach?> get() = armedFlow.asStateFlow()

    /** 最近一条轻提示（StateFlow＋seq：UI 侧 collectAsState 后按 seq 变化弹 Toast）。 */
    val lastToast: StateFlow<LearnToast?> get() = toastFlow.asStateFlow()

    /** 学习板块页（治理中心）点「就教这一步」：武装＋请求切玄览（MainActivity 收口）。 */
    val teachRequests: SharedFlow<String> get() = teachRequestFlow.asSharedFlow()

    // ── 接线 ─────────────────────────────────────────────────────────────────

    /** 生产接线（ZhengdaoApp 调一次）：落 `filesDir/抱朴记忆/观察/`，文案走资源。 */
    fun install(context: Context, cardTexts: LearnCardTexts) {
        val files = context.applicationContext.filesDir
        install(
            root = File(MemoryLayout.globalMemoryDir(files), DIR_NAME),
            cardTexts = cardTexts,
            clock = System::currentTimeMillis,
            drain = { tabId, script -> drainViaCommands(tabId, script) },
            enabled = { ObservePrefs.on(context.applicationContext) },
        )
    }

    /** 可测接线（JVM）：全依赖注入，磁盘指到 TempSandbox。 */
    fun install(
        root: File,
        cardTexts: LearnCardTexts,
        clock: () -> Long,
        drain: (tabId: Int?, script: String) -> String?,
        enabled: () -> Boolean,
    ) {
        this.rootDir = root
        this.texts = cardTexts
        this.clock = clock
        this.drain = drain
        this.enabled = enabled
        this.store = NotLearnedStore(File(root, NotLearnedStore.FILE_NAME))
        notLearnedFlow.value = store.state()
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
            closeSession()
            if (armedFlow.value == null) return@withContext
            // 「就教这一步」是显式教学，不受观察开关牵连（人点名要教的步，照收）——
            // 但只复验、不入观察账（关后 0 记录仍是硬线）。
            val raw = runCatching { drain(tabId, ObserveScript.DRAIN) }.getOrNull() ?: return@withContext
            val result = DrainResult.parse(raw) ?: return@withContext
            tryCapture(null, result)
            return@withContext
        }
        val raw = runCatching { drain(tabId, ObserveScript.DRAIN) }.getOrNull() ?: return@withContext
        val result = DrainResult.parse(raw) ?: return@withContext
        if (discardNext) {
            discardNext = false // 这一批是关着时积下的：只清页内队列，不入账
            return@withContext
        }
        val active = ensureSession(now) ?: return@withContext
        active.ingest(result)
        sessionActiveFlow.value = true
        if (armedFlow.value != null) tryCapture(active, result)
        if (active.isIdle(IDLE_MS)) closeSession()
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

    /** 清观察数据（学习板块页「清空观察数据」；未学会清单不受影响，按钮分开）。 */
    fun clearObservations() {
        closeSession()
        val dir = rootDir ?: return
        dir.listFiles()?.forEach { f -> if (f.name != NotLearnedStore.FILE_NAME) runCatching { f.delete() } }
        RunLog.log("学习板块：观察数据已清空")
    }

    fun requestTeachStep(itemId: String) {
        val item = notLearnedFlow.value.items.firstOrNull { it.id == itemId } ?: return
        armedFlow.value = ArmedTeach(itemId, item.kind, item.url, clock() + ARM_TIMEOUT_MS)
        toast(LearnToastKind.CAPTURE_WAITING, item.pageName)
        teachRequestFlow.tryEmit(itemId)
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

    private fun drainViaCommands(tabId: Int?, script: String): String? {
        // execJs 是阻塞式 evaluate（内部有 latch 与超时），不需要 suspend；调用方已在 IO 上。
        val raw = BrowserCommands.execJs(script, tabId?.toString())
        val ok = runCatching { JSONObject(raw).optBoolean("ok", false) }.getOrDefault(false)
        return raw.takeIf { ok }
    }

    private val LOCK = Any()
}

/** 学习板块的私有目录段（`filesDir/抱朴记忆/观察/`；231 原文）。 */
internal const val DIR_NAME: String = "观察"
