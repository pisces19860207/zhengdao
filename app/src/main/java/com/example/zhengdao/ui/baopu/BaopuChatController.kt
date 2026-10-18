// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（B 段抱朴页）+ 第⑮刀经验（可观察性 bug 改状态载体：
// 引擎状态用 mutableStateOf + State 暴露）+ 设计 §9 施工细节第 1/8 条
// （流式期间内存累积、记忆写入要在会话里可见）。
// 本文件是派工单 §4.3 表格之外的第 6 个 UI 文件（偏差清单报备）：
// 没有它，装配逻辑只能散在 Composable 里，进一次页面建一次线程池。
package com.example.zhengdao.ui.baopu

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.zhengdao.R
import com.example.zhengdao.baopu.AskUserCard
import com.example.zhengdao.baopu.AskUserOption
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.BaopuSql
import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.SessionCompressionStore
import com.example.zhengdao.baopu.MemoryRefine
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.MemoryStatus
import com.example.zhengdao.baopu.MemoryStore
import com.example.zhengdao.baopu.PendingStore
import com.example.zhengdao.baopu.SessionPendingStore
import com.example.zhengdao.baopu.agent.ConfirmRequest
import com.example.zhengdao.baopu.agent.EngineEvent
import com.example.zhengdao.baopu.agent.RunRules
import com.example.zhengdao.baopu.agent.RunState
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
import org.json.JSONObject

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

    /** 当前会话（null = 还没有；首次发送时建）。 */
    var sessionId by mutableStateOf(BaopuRuntime.latestSessionId(db))
        private set

    /** 会话里落库的 interrupted_step（-1 = 未中断）。 */
    var interruptedStep by mutableStateOf(-1)
        private set

    /** 消息列表（从 db 读——先落库再渲染，UI 永远不比库新）。 */
    var messages by mutableStateOf(listOf<BaopuUiMessage>())
        private set

    /** 记忆候选（status=CANDIDATE 的条目；确认/不记都在这里操作）。 */
    var memoryCandidates by mutableStateOf(listOf<com.example.zhengdao.baopu.MemoryEntry>())
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

    /** 资料库动作的一句话结果（收料/导出；成功失败都明说，不静默）。 */
    var libraryNote by mutableStateOf<String?>(null)
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

    /** 会话中（可停止）。 */
    val busy: Boolean
        get() = engineState.value in setOf(
            RunState.THINKING, RunState.ACTING, RunState.COMPRESSING, RunState.AWAIT_CONFIRM,
        )

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

    /** 技能动作的一句话结果（上限拒绝 / 写盘失败等；显示几秒就清）。 */
    var skillNote by mutableStateOf<String?>(null)
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

    init {
        engine.onEvent = { ev -> onEngineEvent(ev) }
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
     * summary 由工具名＋参数**重新生成**（[RunRules.confirmSummary]——与在线
     * 出卡同一个函数，不落库；callId 合成 `restored-<pending_at>`，稳定）。
     * 参数坏 JSON ⇒ 摘要照给（路径那行显示工具名），批准时引擎侧同样兜底。
     */
    private fun restoreConfirmCard() {
        val sid = sessionId ?: return
        val row = pendingStore.load(sid) ?: return
        val args = runCatching { JSONObject(row.argsJson) }.getOrNull() ?: JSONObject()
        confirmCard = ConfirmRequest(
            callId = "restored-${row.at}",
            tool = row.tool,
            summary = RunRules.confirmSummary(row.tool, args),
            argsJson = row.argsJson,
        )
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

    /**
     * 本轮要挂到用户消息上的图片（**相对工作区路径**，第二十四批 B 段）。
     *
     * 为什么在这里暂存、不在 send 里直接写库：用户消息的 messageId 由**引擎**在
     * 落库那一刻才产生（`RunEngine.persistUserMessage`），而 agent 包本单禁改。
     * 所以顺序是：send 时存下附件 → 引擎落完消息发 [EngineEvent.MessagePersisted] →
     * 在事件处理里按那个 messageId 补写 image part。写库发生在主线程（见
     * [onEngineEvent] 的线程纪律），此时 `zd-baopu-db` 单写者已空闲，不会自锁。
     */
    private var pendingImagePaths: List<String> = emptyList()

    /**
     * 发送（首次自动建会话）。
     *
     * @param attachments 本轮附件（Composer 传来）。**只挑 `isImage` 的落 image part**；
     *   非图片附件仍只出现在文本 hint 里（本轮不做文件块）。
     */
    fun send(text: String, attachments: List<BaopuAttachments.Saved> = emptyList()) {
        if (text.isBlank() || busy) return
        // 先记附件再发：引擎可能在 send 返回后就落库并发事件
        pendingImagePaths = AttachmentParts.imageRelPaths(attachments)
        val sid = sessionId ?: engine.createSession(
            title = text.take(40),
            now = System.currentTimeMillis(),
        ).also { created ->
            sessionId = created
            recordSessionWorkspace(created)
        }
        // 第二十四批 C 段：同一批判据喂给引擎——**只传相对路径**，读盘在引擎线程里做
        // （这里是主线程，不许在这儿读 2 MB 的图）。B 段的落库路径仍走 pendingImagePaths。
        engine.send(sid, text, pendingImagePaths)
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

    /** 停止（半成品保留；本刀只读无写半成品）。 */
    fun stop() = engine.stop()

    /** 一键续跑（中断于第 N 步）。 */
    fun resume() {
        val sid = sessionId ?: return
        engine.resume(sid, interruptedStep)
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
        refineMarkSession = -1L
        refineMarkUserCount = -1
        confirmCard = null
        confirmRestored = false
        compressionNote = null // 新会话没压过，细条不许跟着上个会话走
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
        val blankIds = rows.filter { it.title.isBlank() }.map { it.id }
        if (blankIds.isEmpty()) return rows
        val firstTexts = readFirstUserTexts(blankIds)
        // 标题兜底（§2.1.2）：空标题取首条用户消息**前 20 字**；再空由 UI 显示「未命名会话」
        return rows.map { e ->
            if (e.title.isNotBlank()) {
                e
            } else {
                e.copy(title = firstTexts[e.id].orEmpty().trim().take(20))
            }
        }
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
        refineMarkSession = -1L
        refineMarkUserCount = -1
        confirmCard = null
        confirmRestored = false
        compressionNote = null
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
        refineMarkSession = -1L
        refineMarkUserCount = -1
        confirmCard = null
        confirmRestored = false
        compressionNote = null
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
                com.example.zhengdao.baopu.BaopuSql.deleteSessionStatements(sid).forEach { stmt ->
                    d.execSQL(stmt.sql, stmt.args)
                }
            }.get()
        }.isSuccess
        if (ok && sessionId == sid) {
            clearSessionView()
        }
        return ok
    }

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
        db.write { d ->
            val stmt = com.example.zhengdao.baopu.BaopuSql.deleteMemory(id)
            d.execSQL(stmt.sql, stmt.args)
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

    /** 清掉资料库动作结果那句话。 */
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
            libraryNote = "没能对上选项（${option.id}）——没有执行任何动作"
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
            libraryNote = "没能对上选项，请直接点上面的按钮（不猜、不静默执行）"
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
        libraryNote = "收料处理中…"
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
            libraryNote = parts.joinToString("")
            refreshBackupStats()
        }
    }

    /** 导出原件（D 段第 1/3 条）：`textOnly` = 只导文本件那一档。 */
    fun exportOriginals(textOnly: Boolean) {
        exportBusy = true
        libraryNote = "导出中…"
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
                    report.exported.isEmpty() -> "没有可导出的原件（索引是空的）。" +
                        (report.notes.firstOrNull()?.let { "（$it）" } ?: "")
                    else -> buildString {
                        append("导出完成：${report.exported.size} 件（在 整理/原件导出-<时间戳>/）")
                        if (report.notes.isNotEmpty()) append("；另有 ${report.notes.size} 条备注（跳过/撞名/失败）")
                    }
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
                val paths = pendingImagePaths
                pendingImagePaths = emptyList()
                attachImageParts(ev.messageId, paths)
            }
            val force = ev is EngineEvent.Settled
            refresh(force)
            if (force) {
                refineOnSettle()
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
        val userTexts = messages
            .filter { it.role == "user" }
            .flatMap { m -> m.parts.filter { it.kind == "text" }.map { it.text } }
        val already = if (refineMarkSession == sid) refineMarkUserCount.coerceAtLeast(0) else 0
        val fresh = userTexts.drop(already)
        if (fresh.isEmpty()) return // 没有新用户输入（空跑/纯恢复）不提炼
        refineMarkSession = sid
        refineMarkUserCount = userTexts.size

        // 本轮的消息范围：从第一条新用户消息开始（工具统计只算本轮的）
        val firstFreshId = messages.filter { it.role == "user" }
            .let { users -> if (users.isEmpty()) null else users[users.size - fresh.size].id }
        val roundMsgs = messages.filter { it.id >= (firstFreshId ?: Long.MAX_VALUE) }
        val toolsUsed = roundMsgs
            .flatMap { m -> m.parts.filter { it.kind == "tool" }.map { it.tool } }
            .filter { it.isNotBlank() }
            .distinct()

        val facts = MemoryRefine.SessionFacts(
            sessionId = sid,
            userTexts = fresh,
            toolsUsed = toolsUsed,
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
        val merged = (pendingMemories + r.candidates).distinctBy { it.body }
        if (merged.size > maxPending) {
            com.example.zhengdao.rootfs.RunLog.log(
                "[baopu-refine] 待过目候选超过 $maxPending 条，截断 ${merged.size - maxPending} 条（body 去重后仍超限）",
            )
        }
        pendingMemories = merged.take(maxPending)
        introspectionNote = r.introspection.text
        runCatching {
            notePrefs.edit().putString("note_$sid", r.introspection.text).apply()
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
        val r = com.example.zhengdao.baopu.SkillRefine.refine(facts, globalLines + projectLines)
        if (r.candidates.isEmpty()) {
            if (r.dropped > 0) {
                skillNote = appCtx.getString(
                    R.string.skill_refine_dropped, r.dropped,
                )
            }
            return
        }
        val total = globalLines.size + projectLines.size
        if (total + r.candidates.size > com.example.zhengdao.baopu.SkillRules.INDEX_CAP) {
            // K2：超上限 ⇒ 拒绝并提示先删旧的（不是截断）
            skillNote = appCtx.getString(
                R.string.skill_cap_reached, total, com.example.zhengdao.baopu.SkillRules.INDEX_CAP,
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
        val adding = com.example.zhengdao.baopu.SkillRules.parseIndexLines(p.proposed, scope).size -
            com.example.zhengdao.baopu.SkillRules.parseIndexLines(p.current, scope).size
        if (total + adding > com.example.zhengdao.baopu.SkillRules.INDEX_CAP) {
            skillNote = appCtx.getString(
                R.string.skill_cap_reached, total, com.example.zhengdao.baopu.SkillRules.INDEX_CAP,
            )
            return
        }
        val ok = pf.applyProposal(p)
        if (!ok) {
            skillNote = appCtx.getString(R.string.skill_apply_failed)
            return
        }
        pendingSkillProposals = pendingSkillProposals.filterNot { it === p }
    }

    /** 技能提案：丢弃（只在内存列表里移除——PENDING 本来就没落盘，没有文件可删）。 */
    fun discardSkillProposal(p: com.example.zhengdao.baopu.PersonaProposal) {
        pendingSkillProposals = pendingSkillProposals.filterNot { it === p }
    }

    /** 清掉技能动作那句话（UI 显示几秒后调）。 */
    fun clearSkillNote() {
        skillNote = null
    }

    /**
     * 重读消息列表 ＋ 中断标记 ＋ 记忆候选（一次 db 读，不逐事件逐表）。
     *
     * @param force true = 不看"在不在跑"，直接重算中断标记（[EngineEvent.Settled] 专用）
     */
    fun refresh(force: Boolean = false) {
        val sid = sessionId
        messages = if (sid == null) emptyList() else readMessages(sid)
        // 步骤卡汇总行跟着消息一起刷（同一轮生命周期；只读一条 SELECT，跟着 refresh 走最省心）
        stepRows = if (sid == null) emptyList() else readStepRows(sid)
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
        if (force || !runningNow()) {
            interruptedStep = if (sid == null) -1 else BaopuRuntime.interruptedStep(db, sid)
        }
        // 确认卡的生命周期跟 DB 的 pending 行走（派工单 §3.3）：活的卡在等 ⇒ 库里
        // 有行；引擎超时/处理完清行 ⇒ 卡跟着消失（不等用户手动关——T4 的「卡消失」）。
        if (confirmCard != null && sid != null && pendingStore.load(sid) == null) {
            confirmCard = null
            confirmRestored = false
        }
        // 压缩细条跟着库走：压成功那一刻库里才有行，细条才出现（失败/跳过 ⇒ 不出现，
        // 不许凭空报「已压缩」）。一条主键查询，跟着 refresh 走最省心，也最不容易漂。
        if (sid != null) restoreCompression()
        refreshMemory()
    }

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

    private fun refreshMemory() {
        memoryCandidates = store.loadAll().filter { it.status == MemoryStatus.CANDIDATE }
    }

    private fun readMessages(sessionId: Long): List<BaopuUiMessage> = db.read { d ->
        d.rawQuery(
            "SELECT message.id, message.role, message.created_at, part.kind, part.payload " +
                "FROM message LEFT JOIN part ON part.message_id = message.id " +
                "WHERE message.session_id = ? ORDER BY message.seq ASC, part.seq ASC",
            arrayOf(sessionId.toString()),
        ).use { c ->
            val out = mutableListOf<BaopuUiMessage>()
            var curId = -1L
            var curRole = ""
            var curAt = 0L
            var parts = mutableListOf<BaopuUiPart>()
            var curPlan: BaopuUiPlan? = null
            fun flush() {
                // 计划 part（第十七批 C 段）：解析进 msg.plan，**不进 parts**（payload
                // 原文不当正文渲染——J16）。只有 plan part 的消息 parts 为空、plan 非空，
                // 照样建消息（计划卡挂在轮上，气泡本身由列表层跳过）。
                if (curId >= 0 && (parts.isNotEmpty() || curPlan != null)) {
                    out += BaopuUiMessage(curId, curRole, parts.toList(), curAt, curPlan)
                }
            }
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id != curId) {
                    flush()
                    curId = id
                    curRole = c.getString(1)
                    curAt = if (c.isNull(2)) 0L else c.getLong(2)
                    parts = mutableListOf()
                    curPlan = null
                }
                val kind = if (c.isNull(3)) "text" else c.getString(3)
                val payload = if (c.isNull(4)) "" else c.getString(4)
                if (kind == "plan") {
                    // 同消息多条 plan（理论不会）⇒ 后一条赢；解析不出条目 ⇒ 当作没有
                    BaopuUiPlan.fromPayload(payload)?.let { curPlan = it }
                    continue
                }
                parts += if (kind == "tool") {
                    val json = runCatching { JSONObject(payload) }.getOrNull()
                    BaopuUiPart(
                        kind = "tool",
                        tool = json?.optString("tool") ?: "",
                        text = json?.optString("result") ?: payload,
                    )
                } else {
                    // 保留库里的原始 kind（不硬归一成 "text"）：part.kind 是自由文本，
                    // 未来新增 kind（如压缩标记）不该在列表层被静默吞掉。本刀实际只会有 text。
                    BaopuUiPart(kind = kind, text = payload)
                }
            }
            flush()
            out
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
