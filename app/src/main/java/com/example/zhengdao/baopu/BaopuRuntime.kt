// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：抱朴-Agent设计-程枢版 §9 模块划分（Context 依赖装配成一台机器）+ 仓库纪律
// 「Context 依赖失败路径测法：抽 Context-free 内核 + 闭包注入」——本文件只做装配，
// 判据全在 agent/ 与 baopu/ 的纯函数层。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.PathGuard
import androidx.core.net.toUri

import android.content.Context
import android.os.Environment
import android.provider.OpenableColumns
import androidx.core.content.edit
import com.example.zhengdao.baopu.evolution.trigger.EvalTriggerWiring
import com.example.zhengdao.baopu.evolution.trigger.GoalCloseout
import com.example.zhengdao.baopu.plan.GoalBoard
import com.example.zhengdao.baopu.agent.ActiveSessionGate
import com.example.zhengdao.baopu.agent.ArtifactRaster
import com.example.zhengdao.baopu.agent.AskDailyBudget
import com.example.zhengdao.baopu.agent.AskGovLedger
import com.example.zhengdao.baopu.agent.AskGovernance
import com.example.zhengdao.baopu.agent.AskHabitSwitch
import com.example.zhengdao.baopu.agent.AskIntakeGate
import com.example.zhengdao.baopu.agent.AskTier
import com.example.zhengdao.baopu.agent.AndroidPortraitDecoder
import com.example.zhengdao.baopu.agent.LlmExtractionPort
import com.example.zhengdao.baopu.agent.LlmVisionPort
import com.example.zhengdao.baopu.agent.NovelReadthroughTool
import com.example.zhengdao.baopu.agent.PortraitAppearanceTool
import com.example.zhengdao.baopu.agent.AskUserTool
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.DeviceTools
import com.example.zhengdao.baopu.agent.DrawPublish
import com.example.zhengdao.baopu.agent.FileShadowSandbox
import com.example.zhengdao.baopu.agent.GenerateImage
import com.example.zhengdao.baopu.agent.GenerateImageTool
import com.example.zhengdao.baopu.agent.GroupUnlockStore
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.JudgmentExperienceLedger
import com.example.zhengdao.baopu.agent.KbTools
import com.example.zhengdao.baopu.agent.LearnWiring
import com.example.zhengdao.baopu.agent.ListDirTool
import com.example.zhengdao.baopu.agent.OpinionWiring
import com.example.zhengdao.baopu.agent.OrchestrationGate
import com.example.zhengdao.baopu.agent.RunEngine
import com.example.zhengdao.baopu.agent.RunRules
import com.example.zhengdao.baopu.agent.ScheduleTaskTool
import com.example.zhengdao.baopu.agent.ToolCall
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupAudit
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPrefs
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.multiagent.BaopuSubAgentLedger
import com.example.zhengdao.baopu.agent.multiagent.BaopuSubTaskStore
import com.example.zhengdao.baopu.agent.multiagent.LlmSubAgentTurn
import com.example.zhengdao.baopu.agent.multiagent.SubAgentCoordinator
import com.example.zhengdao.baopu.agent.multiagent.SubAgentEnv
import com.example.zhengdao.baopu.agent.multiagent.SubAgentLedger
import com.example.zhengdao.baopu.agent.multiagent.SubAgentLedgerRow
import com.example.zhengdao.baopu.agent.multiagent.SubAgentLedgerSink
import com.example.zhengdao.baopu.agent.multiagent.SubAgentRules
import com.example.zhengdao.baopu.agent.multiagent.SubAgentRunner
import com.example.zhengdao.baopu.agent.multiagent.SubAgentWhitelist
import com.example.zhengdao.baopu.agent.multiagent.SubAgentWrites
import com.example.zhengdao.baopu.agent.multiagent.SubTask
import com.example.zhengdao.baopu.agent.multiagent.SubTaskSink
import com.example.zhengdao.baopu.agent.multiagent.SubTaskTool
import com.example.zhengdao.baopu.agent.multiagent.SubTaskWriteGuard
import com.example.zhengdao.baopu.agent.multiagent.TaskDecomposition
import com.example.zhengdao.baopu.agent.multiagent.VaultSubTaskWriteGuard
import com.example.zhengdao.baopu.consequence.CanaryBand
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.baopu.escalate.BoundaryTable
import com.example.zhengdao.baopu.evolution.DepthEvalFeed
import com.example.zhengdao.baopu.evolution.DepthFactsWiring
import com.example.zhengdao.baopu.evolution.DepthSideReadings
import com.example.zhengdao.baopu.inspect.DeviationLedger
import com.example.zhengdao.baopu.inspect.ShadowAdapter
import com.example.zhengdao.baopu.promptexport.PromptExport
import com.example.zhengdao.baopu.intake.AdmissionWiring
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.library.LibraryImport
import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.baopu.library.LibraryPipeline
import com.example.zhengdao.baopu.library.LibraryStore
import com.example.zhengdao.baopu.mcp.McpServerStore
import com.example.zhengdao.baopu.mcp.McpBudgetStore
import com.example.zhengdao.baopu.mcp.McpAuthFlow
import com.example.zhengdao.baopu.mcp.McpOAuthClient
import com.example.zhengdao.baopu.mcp.McpTokenVault
import com.example.zhengdao.baopu.mcp.McpToolSource
import com.example.zhengdao.baopu.mcp.PrefsMcpBudgetStorage
import com.example.zhengdao.baopu.mcp.PrefsMcpSecretStore
import com.example.zhengdao.baopu.mcp.PrefsMcpServerStorage
import com.example.zhengdao.baopu.policy.AnchorAsset
import com.example.zhengdao.baopu.policy.PolicyAssets
import com.example.zhengdao.baopu.policy.PolicyRuntime
import com.example.zhengdao.baopu.profile.ProfileIntakeWiring
import com.example.zhengdao.baopu.profile.UserProfileBlackbox
import com.example.zhengdao.baopu.profile.VerdictExperienceStore
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.ZhengdaoDirs
import com.example.zhengdao.core.kb.KnowledgeBase
import com.example.zhengdao.core.notify.AppNotifications
import com.example.zhengdao.unattended.UnattendedRecorder
import com.example.zhengdao.unattended.UnattendedStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import com.example.zhengdao.schedule.ScheduleStore

/**
 * 抱朴运行时（刀 1 · B 段）——**Context 装配的唯一处**，进程级单例。
 *
 * 为什么单例：RunEngine 持有 daemon 线程（zd-baopu-run）与数据库句柄，
 * 每次进页面都新建会泄漏线程池；进程级一份，页面只取引用。
 * 全局单活跃会话由 [ActiveSessionGate] 把关（引擎内部），双保险不冲突。
 */
internal object BaopuRuntime {

    /** 深度读数"昨日窗"的宽度（误拦申诉较昨日上升＝拿这一格算；只作读数，不作判据）。 */
    private const val DAY_MS_FOR_FACTS: Long = 24L * 3_600_000L

    /** 灰度档在后果治理账里的能力名（深度裁定的灰度读数从这一档读）。 */
    private const val CANARY_CAPABILITY: String = "evolution"

    @Volatile
    private var dbInstance: BaopuDb? = null

    @Volatile
    private var engineInstance: RunEngine? = null

    /**
     * 外部 MCP server 管理器（第一百二十三批；进程级一份）。
     *
     * 为什么放在这里且**不随引擎重建**：换通道会重建 RunEngine/注册表，但用户手动连上的
     * server 不该因此断掉——[McpToolSource.attach] 只把已注册的工具搬进新表，不联网。
     * 配置只在内存（v1 不落盘），进程重启后自然为空。
     */
    @Volatile
    private var mcpSource: McpToolSource? = null

    /**
     * 第六百三十单：MCP 装配账本（pin / LRU / 预算比例；进程级一份）。
     *
     * 与 server 配置同份 prefs 文件（key 不同）；[McpToolSource] 与 [McpBudgetWiring]
     * 共用这一份——LRU 由工具执行侧写、装配侧读，账本必须同一个。
     */
    @Volatile
    private var mcpBudget: McpBudgetStore? = null

    /** OAuth 授权流程（进程级单例；token 只在它背后的加密存储里）。 */
    // 同对象的 dbInstance/engineInstance/mcpSource 全有 @Volatile，唯独它漏了：装配线程
    // 发布、引擎线程读（A-P2-9）。
    @Volatile
    private var mcpAuth: McpAuthFlow? = null

    /**
     * 引擎**正在用**的那张工具注册表（第一百七十批 B）。
     *
     * 为什么要有这个字段：MCP 工具是运行时注册进表的（不是装配期静态注册），注册到
     * 一张临时表里 = 模型永远看不见。旧实现的 `mcp(context)` 每次调用都 `registry(...)`
     * 新建一张表，于是「连上了但抱朴说没有这个工具」——本批的靶心。
     *
     * 赋值点只有一处（[build] 里取到 reg 之后）；[switchChannel] 换引擎时置空。
     */
    @Volatile
    private var engineRegistry: ToolRegistry? = null

    /**
     * 锚定集随包出厂快照（第三百二十七批）：[build] 启动装配时读 assets 一次。
     *
     * fail closed：校验失败（禁词/指纹/形态/规模任一不过）⇒ 拒收态，
     * [AnchorAsset.Snapshot.forAdmission] 恒空 ⇒ 判据准入一律 REFUSED——
     * 不是「跳过继续跑」。装配前给拒收态兜底，读它不会 NPE 也不会漏拒。
     */
    @Volatile
    private var anchorAssetSnapshot: AnchorAsset.Snapshot = AnchorAsset.Snapshot.refuse("尚未启动装配")

    /** 锚定集快照唯一读口（准入侧只许经 forAdmission 取集）。 */
    val anchorSnapshot: AnchorAsset.Snapshot get() = anchorAssetSnapshot

    /** 数据库（进程级一份；SQLiteOpenHelper 自带线程安全）。 */
    fun db(context: Context): BaopuDb =
        dbInstance ?: synchronized(this) {
            dbInstance ?: BaopuDb(context.applicationContext).also { dbInstance = it }
        }

    /**
     * 把**文件操作账本**绑到本机库（287 补充；幂等）。
     *
     * 调它的地方：装配层 [build]（引擎一开跑就得是库版账本）与律令页「文件操作记录」卡
     * （用户先开设置、后跑抱朴的情形——那时引擎还没建，账本仍是内存版，卡上会是空的）。
     * 只换引用、不清库：库是真相（30 天可回放），内存版只是没接线时的兜底。
     */
    fun installFileOpLog(context: Context) {
        FileOpLog.install(SessionFileOpStore(db(context.applicationContext)))
    }

    /**
     * 当前 provider（刀 1 补丁：从 ProviderPrefs 读——抱朴页「换通道」选的通道 + 用户 Key）。
     * 这仍是 provider 的**唯一入口**：RunEngine / BaopuChatController 都不绕过它。
     * 默认值仍是内置免费通道（第一次装起来就有个能点的默认）。
     */
    fun provider(context: Context): Provider = ProviderPrefs.current(context)

    /**
     * 换通道（UI「启用此通道」落点）：存 SP + 重建引擎。
     * 引擎一份的来历见 [engine]——换通道必须换引擎（provider 是构造期注入的）。
     *
     * 617：**重建必须在本函数里同步做完**，不许把「实例为空」留到下一次 [engine] 调用。
     * 调用点全是点击回调（非组合期），而 [engine] 的下一次调用来自
     * `BaopuScreen` 的 `remember(channelEpoch) { BaopuChatController(context) }`——那是在
     * **组合期/Scaffold 的 measure 子组合里**构造控制器，控制器构造又取 [engine]。
     * 若把重建留给那一刻，引擎里十几个 `mutableStateOf` 就会绑定到子组合快照 id，
     * 主组合再读它们即抛 `Reading a state that was created after the snapshot was taken`
     * ——与 617 首启崩溃同一根因、同一条栈，只是触发口换成「换通道／配置变化」。
     * 旧引擎的 daemon 线程闲着无害（换通道是低频动作）。
     */
    fun switchChannel(
        context: Context,
        channelId: String,
        apiKey: String,
        baseUrl: String? = null,
        model: String? = null,
        protocol: String? = null,
    ) {
        val appCtx = context.applicationContext
        ProviderPrefs.save(appCtx, channelId, apiKey, baseUrl, model, protocol)
        synchronized(this) {
            engineInstance = null
            // 引擎重建 ⇒ 表也重建；旧表的引用不能再当成「引擎正在用的那张」。
            engineRegistry = null
        }
        // 617：立刻在全局快照里把新引擎建好（engine() 是 double-check 幂等，
        // 本次一定触发 build；此后组合期再取只会走快路径拿现成实例，不会重新绑快照 id）。
        engine(appCtx)
    }

    /** 工作区根（唯一真相源 Workspace.hostDir；守卫与 spill 都以它为根）。 */
    fun workspaceRoot(context: Context): File = BaopuWorkspace.hostDir(context.applicationContext)

    /** 人设文件读写壳（B 跟 App、C 跟工作区）。 */
    fun personaFiles(context: Context): PersonaFiles {
        val appCtx = context.applicationContext
        val ws = workspaceRoot(appCtx)
        return PersonaFiles(
            globalRoot = File(appCtx.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY),
            // 199：C 层落到 `<工作区>/抱朴记忆/`（157「根里没有裸文件」；设计 §8 行 14）
            projectRoot = File(ws, PersonaPackRules.DIR_ROOT),
            // 旧落点是工作区根（只读回退：升级那一刻读起来不能像「人设丢了」）
            legacyProjectRoot = ws,
            // 285：出厂 C 层（assets 只读）——active 与旧落点都没有时才兜底
            factoryProjectText = PersonaFactory.read(appCtx),
        )
    }

    /**
     * 第一百六十五批 · 人设包：工作区 `抱朴记忆/人设/` 的读写壳。
     * 包是数据不是代码——这里只读写 persona.md / pack.json，不碰人设三层与工具权限。
     */
    fun personaPackStore(context: Context): PersonaPackStore = PersonaPackStore(workspaceRoot(context))

    /**
     * 第一百九十五批 · 查询存储壳（`logistics.db`）。
     *
     * 缓存一份而不是每次 new：`SQLiteOpenHelper` 每个实例各带一套连接，面板每次重组都新建
     * 就是在漏连接；工具与面板共用同一份 ⇒ 「查询面板的数」与「模型报的数」必然同源。
     */
    fun logisticsStore(context: Context): com.example.zhengdao.baopu.logistics.LogisticsStore {
        logisticsStoreRef?.let { return it }
        return synchronized(this) {
            logisticsStoreRef ?: com.example.zhengdao.baopu.logistics.SqlLogisticsStore(
                context.applicationContext,
            ).also { logisticsStoreRef = it }
        }
    }

    /** [logisticsStore] 的缓存位（只在 [synchronized] 里读写）。 */
    private var logisticsStoreRef: com.example.zhengdao.baopu.logistics.LogisticsStore? = null

    /**
     * 当前工作区启用的那套人设包；没启用、包被删、读不了 ⇒ null（按抱朴默认人设走）。
     * 读不了时留一行日志，不静默——用户至少能在日志里看到为什么包没生效。
     */
    fun activePersonaPack(context: Context): PersonaPackPayload? {
        val appCtx = context.applicationContext
        val workspace = workspaceRoot(appCtx)
        val dir = PersonaPackPrefs.activeDir(appCtx, workspace) ?: return null
        return when (val r = personaPackStore(appCtx).load(dir)) {
            is PersonaPackResult.Ok -> r.pack
            is PersonaPackResult.Rejected -> {
                RunLog.log("人设包「$dir」没读成（${r.error}${if (r.detail.isEmpty()) "" else ":" + r.detail}），本轮按抱朴默认人设")
                null
            }
        }
    }

    // ── 资料库（刀 2 A/B/D 段；路径只在这一处拼，别处不自己 File(…)） ──────────

    /** 收料（投递 → 私有 `原始/`）：扫描、提问卡、执行、待删除清单。 */
    fun libraryImport(context: Context): LibraryImport {
        val appCtx = context.applicationContext
        return LibraryImport(
            deliverDir = LibraryPaths.deliverDir(appCtx),
            doneDir = LibraryPaths.doneDir(appCtx),
            rawPrivateDir = LibraryPaths.rawPrivateDir(appCtx),
        )
    }

    /** 五步流水线（认脸/去重/归一/副本进 `产物/`/写索引）＋ 原件导出。 */
    fun libraryPipeline(context: Context): LibraryPipeline {
        val appCtx = context.applicationContext
        return LibraryPipeline(
            rawPrivateDir = LibraryPaths.rawPrivateDir(appCtx),
            doneDir = LibraryPaths.doneDir(appCtx),
        )
    }

    /** 认脸索引的库内镜像（`library_item` / `library_move_log` 两表；文件索引仍是真相）。 */
    fun libraryStore(context: Context): LibraryStore = LibraryStore(db(context))

    /** 记忆存储壳（文件即真相；global 跟 App、project 跟工作区）。 */
    fun memoryStore(context: Context): MemoryStore {
        val appCtx = context.applicationContext
        return MemoryStore(
            globalRoot = File(appCtx.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY),
            projectRoot = workspaceRoot(appCtx),
        )
    }

    /**
     * 工具注册表（刀 2 追加包：从 [BaopuToolCatalog] 一张表加载，开关走
     * [BaopuToolPrefs]——引擎不再写死工具清单）。
     *
     * 免费通道另补 read/bash 两个兼容条目——② 台账取证：免费档要求 tools 数组里
     * 含这两个名字，否则服务端 403 FreeTierError。read 映射到 read_file 执行器
     * （**read_file 被用户关掉时，read 同样拒**——别名不许当成绕开开关的洞）；
     * bash 没有对应能力，执行器**明确拒绝并说明**（不静默、不假装）。
     *
     * 开关是热的：注册表持 [BaopuToolPrefs] 单例，设置页改开关后下一轮请求的
     * tools 数组就变（被关的模型看不见；幻觉调用拿到「已被用户关闭」的拒绝）。
     *
     * 第五十九批 §1.4 起这里还注入**会话解锁存储** [BaopuToolUnlocks]：
     * 注册表因此同时握着「表 + 用户开关 + 本会话解锁」三样输入，`capabilities`
     * 的名册与解锁都在它自己肚子里算，不再需要第二条通道。
     */
    fun registry(context: Context, provider: Provider): ToolRegistry {
        val appCtx = context.applicationContext
        // 浏览器六件的网关（进程内直调玄览；幂等，重复注入只换同一份）
        BaopuBrowserWiring.install(appCtx)
        // 628：content:// 读取端口 —— 装配层注入真 ContentResolver（工具层零 Android 依赖）。
        // 只捕获 ContentResolver，不把 Context 放进 object 静态字段（lint StaticFieldLeak）。
        installContentUriOpener(appCtx)
        val reg = ToolRegistry(
            prefs = toolPrefs(appCtx),
            unlocks = toolUnlocks(appCtx),
            // 第一百零三批 A 段：可见集合变化留痕（`[tools] 可见 N 个：…`）。
            // 真机 TOOLS-D1 在代码层复现不出，下一次复测要能直接读出「那一刻的 tools 数组」。
            onVisibleChanged = { line -> RunLog.log(line) },
        )
        BaopuToolCatalog.loadInto(reg) { workspaceRoot(appCtx) }
        // 第二百一十八批 · 工具补齐 v1 的宿主注入（房型照 CapabilitiesTool.sink：工具层不认识 Context）。
        // DeviceHost：剪贴板/通知/拨号盘的 Android 实现；ScheduleToolSink：写完库重排系统闹钟。
        ScheduleTaskTool.storeProvider = { ScheduleStore.of(appCtx) }
        ScheduleTaskTool.sink = object : com.example.zhengdao.baopu.agent.ScheduleToolSink {
            override fun reschedule() {
                com.example.zhengdao.schedule.ScheduleScheduler.rescheduleAll(
                    appCtx, ScheduleStore.of(appCtx), System.currentTimeMillis(),
                )
            }
        }
        installTriggerToolHost(appCtx)
        DeviceTools.host = object : com.example.zhengdao.baopu.agent.DeviceHost {
            override fun copyToClipboard(text: String): Boolean = runCatching {
                val cm = appCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("baopu", text))
                true
            }.getOrDefault(false)

            override fun notificationsEnabled(): Boolean =
                androidx.core.content.ContextCompat.checkSelfPermission(
                    appCtx, android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            override fun postNotification(title: String, body: String, channel: String?): Boolean = runCatching {
                com.example.zhengdao.core.notify.AppNotifications.ensureAll(appCtx)
                val ch = if (channel.isNullOrBlank()) {
                    com.example.zhengdao.core.notify.AppNotifications.AGENT
                } else {
                    channel
                }
                val n = android.app.Notification.Builder(appCtx, ch)
                    .setSmallIcon(com.example.zhengdao.R.drawable.ic_terminal)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build()
                appCtx.getSystemService(android.app.NotificationManager::class.java)?.notify(20261103, n)
                true
            }.getOrDefault(false)

            override fun openDialer(number: String): Boolean = runCatching {
                // 只 ACTION_DIAL（打开拨号盘填号）；绝不 ACTION_CALL/CALL_PHONE（派工单 §1⑥ 红线）
                val intent = android.content.Intent(android.content.Intent.ACTION_DIAL)
                    .setData(("tel:" + android.net.Uri.encode(number)).toUri())
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                appCtx.startActivity(intent)
                true
            }.getOrDefault(false)
        }
        // 第一百五十八批：文生图（`generate_image`）的接线。
        // Key 的口径：当前通道若是 Gemini 原生就用它的（含用户填的 Key）；否则退回
        // 「设置 → Google Gemini」里存的那把——生图是独立于对话通道的能力，不该逼用户
        // 为了画一张图把对话也切过去。两处都没有 ⇒ 工具回一句「去哪儿填 Key」。
        val liveGemini = provider.protocol == LlmProtocol.GEMINI_NATIVE
        val geminiKey = if (liveGemini) {
            provider.apiKey.ifBlank { ProviderPrefs.storedKey(appCtx, Providers.GEMINI.id).orEmpty() }
        } else {
            ProviderPrefs.storedKey(appCtx, Providers.GEMINI.id).orEmpty()
        }
        GenerateImageTool.env = GenerateImageTool.Env(
            provider = Providers.GEMINI.copy(
                apiKey = geminiKey,
                baseUrl = if (liveGemini) provider.baseUrl else Providers.GEMINI.baseUrl,
            ),
            // 157 目录收口：默认/自定义/私有工作区一律 ⇒ <工作区>/产物（旧 资料库/整理 已迁入）。
            artifactDir = { ws -> GenerateImage.artifactDir(ws) },
            // 2026-10-23：装配时若池子全在冷却，上面那份 key 是空的、且会被固化；
            // 执行时再走同一套 Key 池判据取一次（取不到仍如实回「去哪儿填」）。
            keyProvider = { ProviderPrefs.storedKey(appCtx, Providers.GEMINI.id).orEmpty() },
            // 第一百九十九批：生图进手机相册（Pictures/证道/图片/，相册可见）＋ 归属账本。
            // 通道没接上/没权限 ⇒ 工具退回 产物/ 并在回执里说明（不假装相册里有）。
            publishAlbum = { bytes, name, mime, ext ->
                MediaPublisher.publish(appCtx, bytes, name, mime, MediaOwnership.SOURCE_IMAGE, ext)
            },
        )
        // 第五百九十七批：立绘外貌两件（portrait_look / portrait_appearance）的装配。
        // 视觉能力**只读当前通道的 Provider.vision**（597 判据②：无能力 ⇒ 工具回
        // "该模型看不了图"，一个字节不发）；真外发是 CONFIRM——管线第③段末尾过
        // 467 租约闸（命中免卡、未命中弹卡），工具里再拦一道 PortraitDescribeGate。
        // 解码器与视觉出口都从这条缝注入：测试换假件（ImageIO 解码／假 port 数请求）。
        PortraitAppearanceTool.env = PortraitAppearanceTool.Env(
            visionSupported = provider.vision,
            modelId = provider.model,
            port = LlmVisionPort(provider),
            decoder = AndroidPortraitDecoder,
        )
        // 第六百一十批：通读两件（novel_readthrough 预检 FREE／novel_readthrough_run 开跑
        // CONFIRM）的装配。模型出口只在这里接一次（同 LlmVisionPort 房型：
        // 装配层注入，测试换假件数调用）；事实落 产物/通读/<书>/，append-only。
        NovelReadthroughTool.env = NovelReadthroughTool.Env(port = LlmExtractionPort(provider))
        // 第一百八十六批：画板/图表/表格的 PNG 截图器。
        // ArtifactRaster 是 App 侧唯一的 WebView 沙箱实现（JS 关、断网、只放 about/data）；
        // 纯排版层 DrawPublish 只认接口，测试里换成假截图器即可直测。
        ArtifactRaster.env = ArtifactRaster.Env { appCtx }
        DrawPublish.rasterizer = ArtifactRaster
        // 第一百二十八批：知识库 v2 的索引存储壳（App 私有 filesDir/baopu/kb）。
        // 工具（kb_index/kb_search）与引擎的每轮自动注入共用同一份壳——房型同
        // AskUserTool.sink／PlanTool.sink：Context 只到装配层这一处。
        KbTools.store = com.example.zhengdao.baopu.kb.KbIndexStore(appCtx.filesDir)
        // 492 B②：索引浏览/检索两件（index_browse/index_search）的 Context 注入口——
        // 走 PhoneSearch/IndexBrowse 门面读 search.db，工具层自己不认识 Context（同上房型）。
        com.example.zhengdao.baopu.agent.IndexTools.ctx = { appCtx }
        // 第四百九十一批（设计修订）：备份/还原两件工具（export_data/import_data）的 Context 注入口。
        // 同一房型：工具层不认识 Context，装配层注入一次 ⇒ JVM 单测可以拿假件直接打纯函数。
        com.example.zhengdao.baopu.agent.BackupTools.ctx = { appCtx }
        // 第一百九十五批：查询工具的存储壳（App 私有 filesDir/物流/logistics.db，与主库无关）。
        // 同一房型：Context 只到装配层这一处，工具与面板都从这里拿同一份壳 ⇒ 两处读到的数才是一个。
        com.example.zhengdao.baopu.agent.LogisticsQueryTool.store = logisticsStore(appCtx)
        // 第五百二十七批：治理出口（governance_state）的运行环境（注入口见 [wireGovernanceState]）
        // 482 前置②：目标收口事实的出口在同一处装配（见 wireGoalCloseout）
        wireGovernanceState(appCtx)
        // 2026-10-18：抱朴自己写技能（确认制；写前备份、同名即改、写完进索引）
        reg.register(
            com.example.zhengdao.baopu.agent.SkillTools.spec(
                workspaceRoot = { workspaceRoot(appCtx) },
                personaFiles = { personaFiles(appCtx) },
                // 第五十七批 §1.1：技能被用 ⇒ 记一条 skill_event（被动留痕；写失败只 log）
                // 第八十五批 §2：同一次使用**再**记一条效果信号（kind='effect'；只记不决策）
                useRecorder = com.example.zhengdao.baopu.agent.SkillTools.UseRecorder { name, ok ->
                    val tip = recordSkillUse(appCtx, name, ok)
                    recordSkillEffect(appCtx, name, ok)
                    tip
                },
            ),
        )
        // 第五十八批：C 层人设**追加-only**通道（改/删既有条款仍被 guard 拦）
        reg.register(
            com.example.zhengdao.baopu.agent.AppendPersonaTool.spec(
                personaFiles = { personaFiles(appCtx) },
            ),
        )
        // 第二百四十八批（第四刀）：免费通道的 read/bash 兼容条目（COMPAT 组）已退役 ——
        // agent 运行时随终端搬去 zhengdao-term，主仓不再注册这两条。
        // 第一百二十三批：MCP 工具桥接（只搬已连接的 server；**这里绝不联网**）。
        mcpSourceFor(reg, appCtx)
        // 第一百七十批 B：记下引擎真正要用的表（MCP 面板要靠它把工具注册进对的表）。
        engineRegistry = reg
        return reg
    }

    /**
     * 目标收口事实的出口（第四百八十二批前置②：**只写不聚合**）。
     *
     * 落到 495 已经在读的那本触发账（`filesDir/evolution/trigger-ledger.jsonl`），
     * 行形仍是既有 13 键、`type` 仍是既有 `manual`、`triggered=false` ⇒
     * 不新增 kind、不新增文件格式、不进 495 的聚合（见 [GoalCloseout] 文件头）。
     */
    /**
     * 628：`content://` 的真实现（**全仓唯一**碰 `ContentResolver.openInputStream` 的地方）。
     *
     * 只把 [android.content.ContentResolver] 捕进端口对象，不把 [Context] 存进静态字段；
     * `DISPLAY_NAME` 用于判 .xlsx/.csv，`SIZE` 让上层能在开流前就拒超限（不截断）。
     * 权限失效 / URI 失效 ⇒ `openInputStream` 抛 SecurityException / FileNotFoundException，
     * 这里返回 null，由上层的 [ContentRead.Unavailable] 统一说「URI 无权限或已失效」。
     */
    private fun installContentUriOpener(appCtx: Context) {
        val resolver = appCtx.contentResolver
        ContentUriSource.opener = object : ContentOpener {
            override fun open(uri: String): ContentStream? {
                val parsed = runCatching { uri.trim().toUri() }.getOrNull() ?: return null
                val stream = runCatching { resolver.openInputStream(parsed) }.getOrNull() ?: return null
                val meta = runCatching { queryMeta(resolver, parsed) }.getOrNull()
                return ContentStream(meta?.first, meta?.second, stream)
            }
        }
    }

    /** 返回「显示名 / 声明大小」；provider 不给就都是 null（上层按魔数与边读边数兜）。 */
    private fun queryMeta(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
    ): Pair<String?, Long?>? = resolver.query(uri, null, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return@use null
        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
        val name = if (nameIdx >= 0) c.getString(nameIdx) else null
        val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null
        name to size
    }

    private fun wireGoalCloseout(appCtx: Context) {
        GoalBoard.attachCloseoutSink { fact ->
            GoalCloseout.append(EvalTriggerWiring.ledgerFile(appCtx), fact)
        }
        // 第五百六十八批 B 域③：目标模板候选的出口（482 的提炼 → 候选）。
        // 只注入不判据：提炼逻辑在 GoalRules.templateCandidate（482，一字不动），
        // 灰度链的判定全在中风险门；候选只产值不启用（GoalModeTest 源级门禁钉着）。
        GoalBoard.attachTemplateSink { candidate, atMs ->
            com.example.zhengdao.baopu.evolution.midrisk.MidRiskEvo.onGoalTemplate(candidate, atMs)
        }
    }

    /**
     * 第五百二十七批：治理出口（governance_state）的运行环境注入口——执行体的 filesDir
     * 与 run_log 取数壳（`MetricsSources.logSteps`，METRICS.md §1.3 的唯一真相源；SQLite
     * 没有 jsonl 副本，所以这一路是注入的**只读查询**，不是另造一套）。房型同 IndexTools.ctx：
     * Context 只到装配层这一处，JVM 单测拿假件直接打六段对拍；logSteps 惰性取 db
     * （首次调用才建库壳）。
     */
    private fun wireGovernanceState(appCtx: Context) {
        wireGoalCloseout(appCtx)
        // 第五百六十八批 B 域②：相处规则候选的出口（550 的误判纠正 → 候选）。
        // 只注入不判据：判档逻辑在 CompanionJudge（550，一字不动），灰度链的判定全在中风险门。
        com.example.zhengdao.baopu.MediatorWiring.companionRuleSink = { prev, corrected, turns, sessionId, atMs ->
            com.example.zhengdao.baopu.evolution.midrisk.MidRiskEvo.onCompanionCorrection(
                prev,
                corrected,
                turns,
                sessionId,
                atMs,
            )
        }
        // 第五百六十八批 C 域①：工具白名单候选的出口（组授权批准/手动/撤销 → 候选）。
        // 只注入不判据：组授权语义（97/104 批）一字未动，方向与加宽准入的判定全在高风险门。
        com.example.zhengdao.baopu.agent.ToolGroupAudit.whitelistSink = { groupKey, widening, atMs ->
            com.example.zhengdao.baopu.evolution.highrisk.HighRiskEvo.onWhitelistEvent(groupKey, widening, atMs)
        }
        com.example.zhengdao.baopu.agent.GovernanceStateTool.env =
            com.example.zhengdao.baopu.agent.GovernanceStateTool.Env(
                identityDir = { appCtx.filesDir },
                logSteps = { fromMs ->
                    com.example.zhengdao.baopu.inspect.MetricsSources.logSteps(db(appCtx), fromMs)
                },
            )
    }

    /**
     * 外部 MCP 管理器（唯一入口；测试不经过它，直接构造 [McpToolSource]）。
     *
     * **必须绑在引擎那张表上**：MCP 工具是运行时注册的，绑到临时表里模型看不见
     * （见 [engineRegistry] 的注释）。引擎还没建时先建一次（首次打开面板的代价；
     * 抱朴页本来就持有 controller，实际几乎不会走到）。
     *
     * 不联网：这里只登记/搬运，联网只在用户点「连接」后由面板调到 [McpToolSource.connect]。
     */
    fun mcp(context: Context): McpToolSource {
        val appCtx = context.applicationContext
        val reg = engineRegistry ?: synchronized(this) {
            engineRegistry ?: run {
                engine(appCtx) // build() 里会把 engineRegistry 填上
                // 装配层契约：build() 必须登记引擎的注册表；违反即程序性错误，宁可崩也不
                // 退化成「每次新建一张临时表」（那正是本批修掉的病根）。
                checkNotNull(engineRegistry) { "engine(appCtx) 返回后 engineRegistry 必须已登记" }
            }
        }
        return mcpSourceFor(reg, appCtx)
    }

    /**
     * MCP 的 OAuth 流程（唯一实例）。
     *
     * 三件东西在这里装配，且只在这里装配：
     * · [McpTokenVault]：token 唯一落点（AndroidKeyStore AES-GCM 加密的私有 prefs）；
     * · [McpOAuthClient]：发现/注册/换 token/刷新（不跟重定向）；
     * · 回调地址固定 `zhengdao://mcp/oauth`（与 Manifest 的 intent-filter 一字不差）。
     *
     * 不联网、不读 token：构造只是把三样东西拼起来（真正的网络在用户点授权之后）。
     */
    fun mcpAuth(context: Context): McpAuthFlow {
        val appCtx = context.applicationContext
        return mcpAuth ?: synchronized(this) {
            mcpAuth ?: McpAuthFlow(
                vault = McpTokenVault(
                    PrefsMcpSecretStore(appCtx),
                    log = { RunLog.log(it) },
                ),
                oauth = McpOAuthClient(McpOAuthClient.defaultHttp(), log = { RunLog.log(it) }),
                log = { RunLog.log(it) },
            ).also { mcpAuth = it }
        }
    }

    private fun mcpSourceFor(reg: ToolRegistry, context: Context): McpToolSource {
        val source = mcpSource ?: synchronized(this) {
            mcpSource ?: McpToolSource(
                reg,
                auth = mcpAuth(context),
                log = { RunLog.log(it) },
                // 配置落 prefs（JSON，不碰 BaopuDb.VERSION）：进程重启后地址还在，连接不自动重建。
                store = McpServerStore(
                    PrefsMcpServerStorage(context.applicationContext),
                    log = { RunLog.log(it) },
                ),
                // 630：装配账本（pin/LRU/比例）落同一份 prefs 文件；与装配器共用同一实例。
                budget = mcpBudgetStore(context),
            ).also { mcpSource = it }
        }
        source.attach(reg)
        return source
    }

    /** MCP 装配账本（进程级单例；[McpToolSource] 与 [McpBudgetWiring] 共用同一份）。 */
    internal fun mcpBudgetStore(context: Context): McpBudgetStore {
        val appCtx = context.applicationContext
        return mcpBudget ?: synchronized(this) {
            mcpBudget ?: McpBudgetStore(
                PrefsMcpBudgetStorage(appCtx),
                log = { RunLog.log(it) },
            ).also {
                it.load()
                mcpBudget = it
            }
        }
    }

    /**
     * 工具开关存储（SharedPreferences 持久；进程级单例，注册表与设置页共用它）。
     *
     * 放在 [BaopuRuntime]（装配层）而不是 agent 包：注册表要零 Android 依赖
     * （JVM 直测），Context 只能出现在这一层。
     *
     * 第五十九批 §1.6 起这里多一档**组开关**（key `disabled_groups`）：它与单工具开关
     * 存在同一份 SP 文件里、走同一套读法，但**语义不是"另一种禁用"**——
     * 组关掉后 `capabilities` 也解不开（判据只在 [com.example.zhengdao.baopu.agent.isVisibleTool]
     * 那一处），而单工具关掉只影响那一个工具。两档并存是派工单硬要求的，不许合并成一档。
     */
    internal object BaopuToolPrefs : ToolPrefs {
        private const val FILE = "baopu_tools"
        private const val KEY_DISABLED = "disabled"

        /**
         * 609：用户**显式开过**的工具名（只对 [BaopuToolCatalog.DEFAULT_DISABLED] 里的
         * 默认关件有意义）。三态：显式关（KEY_DISABLED）> 显式开（本键）> 默认。
         * 与 InMemoryToolPrefs 同一口径——两处语义必须逐字一致，否则单测与真机分叉。
         */
        private const val KEY_ENABLED = "enabled_default_off"

        /** 组开关那份集合（存的是组的 `key`，不是枚举 ordinal——枚举顺序将来会变）。 */
        private const val KEY_DISABLED_GROUPS = "disabled_groups"

        /**
         * 长期同意开启的组（第九十七批：御批卡「一直」与设置页组开关的落点）。
         *
         * 与 [KEY_DISABLED_GROUPS] 是两件事：approved = 我允许它用，disabled = 我不许它用。
         * 都没有 ⇒ 高组就是「已锁」（模型看不见，抱朴要用时会弹卡申请）。
         */
        private const val KEY_APPROVED_GROUPS = "approved_groups"

        /**
         * 进程级 SharedPreferences 实例。
         *
         * 134 批 lint(StaticFieldLeak)：原字段是 `@Volatile var appCtx: Context?` —— 存的是
         * application context（本来就不会泄漏 Activity），但 lint 对「Kotlin object 里的
         * Context 字段」一律按泄漏风险报。改成只存 SP 实例：SP 由 Context 内部持有、进程级
         * 缓存，语义与「每次从 applicationContext 取」等价，也不再直接持 Context 引用。
         */
        @Volatile
        private var prefs: android.content.SharedPreferences? = null

        /** 装配期注入（进程级一次；UI 侧经 [toolPrefs] 进来也会先 init）。 */
        fun init(context: Context) {
            prefs = context.applicationContext.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
        }

        private fun sp(): android.content.SharedPreferences? = prefs

        override fun isDisabled(name: String): Boolean {
            val p = sp() ?: return name in BaopuToolCatalog.DEFAULT_DISABLED
            if (p.getStringSet(KEY_DISABLED, emptySet())?.contains(name) == true) return true
            return name in BaopuToolCatalog.DEFAULT_DISABLED &&
                p.getStringSet(KEY_ENABLED, emptySet())?.contains(name) != true
        }

        override fun setDisabled(name: String, disabled: Boolean) {
            val p = sp() ?: return
            // getStringSet 返回的实例不许原地改（Android 文档）⇒ 拷贝再写回
            val cur = p.getStringSet(KEY_DISABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
            val en = p.getStringSet(KEY_ENABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (disabled) {
                cur.add(name)
                en.remove(name)
            } else {
                cur.remove(name)
                if (name in BaopuToolCatalog.DEFAULT_DISABLED) en.add(name)
            }
            p.edit {
                putStringSet(KEY_DISABLED, cur)
                putStringSet(KEY_ENABLED, en)
            }
        }

        override fun disabledNames(): Set<String> {
            val p = sp() ?: return BaopuToolCatalog.DEFAULT_DISABLED
            val explicit = p.getStringSet(KEY_DISABLED, emptySet()) ?: emptySet()
            val enabled = p.getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()
            return explicit + (BaopuToolCatalog.DEFAULT_DISABLED - enabled)
        }

        override fun disabledGroups(): Set<ToolGroup> =
            sp()?.getStringSet(KEY_DISABLED_GROUPS, emptySet())
                ?.mapNotNull { ToolGroup.fromKey(it) }?.toSet()
                ?: emptySet()

        override fun setGroupDisabled(group: ToolGroup, disabled: Boolean) {
            val p = sp() ?: return
            val cur = p.getStringSet(KEY_DISABLED_GROUPS, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (disabled) cur.add(group.key) else cur.remove(group.key)
            p.edit { putStringSet(KEY_DISABLED_GROUPS, cur) }
        }

        override fun approvedGroups(): Set<ToolGroup> =
            sp()?.getStringSet(KEY_APPROVED_GROUPS, emptySet())
                ?.mapNotNull { ToolGroup.fromKey(it) }?.toSet()
                ?: emptySet()

        override fun setGroupApproved(group: ToolGroup, approved: Boolean) {
            val p = sp() ?: return
            val cur = p.getStringSet(KEY_APPROVED_GROUPS, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (approved) cur.add(group.key) else cur.remove(group.key)
            p.edit { putStringSet(KEY_APPROVED_GROUPS, cur) }
        }
    }

    /**
     * 会话解锁存储（第五十九批 §1.4：`capabilities` 解开的组**按会话**留着）。
     *
     * 落点选 SharedPreferences（不是 `session` 表、也不是新开一张小表），三条理由，
     * 交付报告里逐字记：
     * 1. **派工单给的二选一里有"单独小表"**，但那要动 `BaopuDb.kt` 的迁移（v9→v10）——
     *    本批红线写着「不碰工具实现、不碰 GuardPipeline 权限语义」，而 56 批
     *    （`fix/p2-concurrency-six`）**正在改这台 DB 的并发语义**：同处落笔 = 把两条在飞
     *    分支的冲突提前引爆，代价远大于这里的"存储选型不够正统"；
     * 2. 与 [BaopuToolPrefs] 同房：解锁状态与开关状态是同一件事的两半（谁能看见什么），
     *    分两处存就要两处冷启动、两处迁移、两处测试假件；
     * 3. 生命周期天然匹配：解锁是"进程内偏好级"的短命状态，SP 的语义正是这个
     *    （新会话回到默认 = 一个新 sessionId 天然查不到旧 key，不需要任何清理逻辑）。
     *
     * 🛑 一格一档（§4 打回第三条）：key 是 `session:<id>`，**没有**全局那一格。
     * 会话删除后残留的那几个 key 是无害垃圾（sessionId 单调增、不回绕 ⇒ 不会被新会话捡走），
     * 清理入口 [clear] 已备好，但"删会话"那一处不在本批红线内 ⇒ 如实写在报告里。
     */
    internal object BaopuToolUnlocks : GroupUnlockStore {
        private const val FILE = "baopu_tool_unlocks"

        /** 进程级 SP 实例（134 批 StaticFieldLeak：不再持 Context 字段，理由同 [BaopuToolPrefs]）。 */
        @Volatile
        private var prefs: android.content.SharedPreferences? = null

        fun init(context: Context) {
            prefs = context.applicationContext.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
        }

        private fun sp(): android.content.SharedPreferences? = prefs

        private fun key(sessionId: Long) = "session:$sessionId"

        override fun unlocked(sessionId: Long): Set<ToolGroup> =
            sp()?.getStringSet(key(sessionId), emptySet())
                ?.mapNotNull { ToolGroup.fromKey(it) }?.toSet()
                ?: emptySet()

        override fun unlock(sessionId: Long, groups: Collection<ToolGroup>) {
            val p = sp() ?: return
            if (groups.isEmpty()) return
            // 读-改-写不做成原子：同一会话的解锁只由那一轮的工具调用串行发起
            // （RunEngine 单写者线程），并发写在这里等于"两个会话互写各自的 key"——不互相覆盖。
            val cur = p.getStringSet(key(sessionId), emptySet())?.toMutableSet() ?: mutableSetOf()
            cur += groups.map { it.key }
            p.edit { putStringSet(key(sessionId), cur) }
        }

        override fun clear(sessionId: Long) {
            sp()?.edit { remove(key(sessionId)) }
        }

        /**
         * 第九十七批 §3.5：设置页手动关组 ⇒ 「会话内已解锁的一并失效」。
         *
         * 实现是扫一遍 `session:*` 键（解锁状态本来就只按会话分格、没有全局格），
         * 把要撤的组从每一格里去掉。扫描量＝历史会话数（几十个），发生在用户点开关时，
         * 不在每轮热路径上。
         */
        override fun relockAll(groups: Collection<ToolGroup>) {
            val p = sp() ?: return
            if (groups.isEmpty()) return
            val drop = groups.map { it.key }.toSet()
            p.edit {
                p.all.keys.filter { it.startsWith("session:") }.forEach { k ->
                    val cur = p.getStringSet(k, emptySet())?.toMutableSet() ?: return@forEach
                    if (cur.removeAll(drop)) putStringSet(k, cur)
                }
            }
        }
    }

    /** 设置页/引擎用：拿会话解锁存储（顺带保证已 init）。 */
    fun toolUnlocks(context: Context): GroupUnlockStore {
        BaopuToolUnlocks.init(context)
        return BaopuToolUnlocks
    }

    /**
     * 第一百九十九批 · 相册通道 ＋ 两笔存量迁移（设计 §5／§7／§8 行 13、14）。
     *
     * 装配三件事：
     * 1. [MediaPublisher] 的环境（Context ＋ 工作区闭包）——桥截图与生图都经它；
     * 2. `screenshots/` ⇒ `截屏/`：先哈希对账再复制，**旧目录不删**（只落 `.待删.txt`）；
     * 3. 根直放的人设存量：**只登记不搬**（新写入已改落 `抱朴记忆/`）。
     *
     * 全程 `runCatching`：迁移是顺手活，**绝不许**因为一个只读文件把引擎装不起来。
     */
    /**
     * 第三百九十九批 · 事件触发源的宿主注入（房型照 ScheduleToolSink：工具层不认识 Context）。
     * 库、排程、环境现状（源开关与日历权限——工具结果里要如实说）三件，抽成独立函数
     * 让 [registry] 保持原长（LongMethod 阈值 60，本批不许添新债）。
     */
    private fun installTriggerToolHost(appCtx: Context) {
        com.example.zhengdao.trigger.TriggerTool.storeProvider = {
            com.example.zhengdao.trigger.TriggerRunner.ruleStore(appCtx)
        }
        com.example.zhengdao.trigger.TriggerTool.sink = object : com.example.zhengdao.trigger.TriggerToolSink {
            override fun reschedule() {
                com.example.zhengdao.trigger.TriggerRunner.rescheduleAll(appCtx)
            }
        }
        com.example.zhengdao.trigger.TriggerTool.envProvider = {
            com.example.zhengdao.trigger.TriggerToolEnv(
                schedOn = com.example.zhengdao.trigger.TriggerSwitch.schedOn(appCtx),
                calendarOn = com.example.zhengdao.trigger.TriggerSwitch.calendarOn(appCtx),
                calendarGranted = com.example.zhengdao.trigger.TriggerCalendarProbe.granted(appCtx),
            )
        }
    }

    private fun installMedia(appCtx: Context, ws: File) {
        MediaPublisher.env = MediaPublisher.Env(
            // 晚绑定：用户在设置页换工作区后，账本跟着新工作区走（不是装配那一刻的快照）
            workspace = { workspaceRoot(appCtx) },
        )
        // 工具侧（delete_path 只有路径、没有 Context）走这条闭包：Context 不进静态字段
        MediaPublisher.installAlbumDeleter { rel ->
            MediaPublisher.deleteByRelPath(appCtx, rel)
        }
        runCatching {
            // 622：经端口转发（目录常量本身非动作；转发口让「bridge 之外不碰
            // BrowserCommands」的源码断言零例外——见 BrowserPort.legacyShotDir 的 KDoc）
            val target = com.example.zhengdao.bridge.BrowserPort.legacyShotDir(appCtx)
            val legacy = File(ZhengdaoDirs.root(appCtx), MediaMigration.LEGACY_SHOT_DIR)
            MediaMigration.migrateShots(ws, legacy, target)
        }.onFailure { RunLog.log("抱朴截屏目录迁移跳过：${it.javaClass.simpleName}") }
        runCatching { MediaMigration.registerPersonaResidue(ws) }
            .onFailure { RunLog.log("人设存量登记跳过：${it.javaClass.simpleName}") }
    }

    /** 设置页用：拿开关存储（顺带保证已 init）。 */
    fun toolPrefs(context: Context): ToolPrefs {
        BaopuToolPrefs.init(context)
        return BaopuToolPrefs
    }

    /**
     * 运行引擎（进程级一份；double-check lazy）。
     *
     * 刀 1 补丁：provider 从 ProviderPrefs 读（可换通道）。换通道 = [switchChannel]
     * 存 SP + 置空引擎实例 ⇒ 下次 [engine] 用新通道重建（ActiveSessionGate 是进程级的，
     * 与引擎实例无关，重建不影响它的互斥语义）。
     */
    fun engine(context: Context): RunEngine =
        engineInstance ?: synchronized(this) {
            engineInstance ?: build(context.applicationContext).also { engineInstance = it }
        }

    /**
     * 第三百八十八单：值守页取压着的卡（**不触发装配**——引擎没建过就给 null，
     * 不为一眼读数把整个引擎拉起来）。
     */
    internal fun engineIfBuilt(): RunEngine? = engineInstance

    private fun build(appCtx: Context): RunEngine {
        // 第一百二十二批：**先装政策表，再装任何会读政策的零件**。
        // 组闸 / 工具可见性 / 进化决策口都在本函数后半段构造；装晚了它们会拿到默认值。
        // fail closed 由 PolicyRuntime 负责：缺表回默认、坏表整张拒绝；这里只负责落审计行。
        RunLog.log(PolicyRuntime.install(PolicyAssets.load(appCtx)))
        // 第三百二十七批：锚定集随包出厂——启动读一次转不可变快照；校验失败＝拒收态，
        // 准入一律 REFUSED（fail closed）。读数落 RunLog，坏包在这里就能看出来。
        anchorAssetSnapshot = AnchorAsset.fromAsset(appCtx.assets.open(AnchorAsset.PATH).readBytes())
        RunLog.log("锚定集出厂校验：${anchorAssetSnapshot.summary()}")
        // 刀 3 B 段 C 段：预热思考深度的进程级缓存（SP ⇒ liveEffort）——
        // 设置页落盘时也会同刷（ModelParams.saveEffort），这里管的是「跨会话」
        // （上轮选过「低」，这轮直接进抱朴页也要生效，不必先去设置页逛一圈）。
        // 档位真正盖到 provider 上是在 LlmClient 发请求前（每轮现读）。
        ModelParams.initEffort(appCtx)
        // 157 记忆目录约定（用户 2026-10-23 拍板）：进工作区自动建空 记忆/日记/
        // （不弹卡、不写内容）；旧布局一次性迁移（`资料库/{整理,投递,原始,改版}`、
        // `.baopu-bak`、工作区根 `项目记忆.md`）。迁移幂等（二遍全零）、冲突不覆盖
        // （跳过并记账）、只删空目录——「不能弄丢已有资料库文件」逐条落在 MemoryLayout。
        val wsRoot = workspaceRoot(appCtx)
        MemoryLayout.ensureDirs(wsRoot)
        MemoryLayout.migrate(wsRoot, appCtx.filesDir)
        // 第一百八十二批：知识库自动刷新的读侧索引合并（幂等；Application 已装时只是重复确认）。
        KnowledgeRefresh.install(appCtx)
        // 第一百九十九批：相册通道 ＋ 两笔顺带迁移（都幂等，二遍全零）。
        // 装在这里的理由与上面那行一样：引擎一开跑就可能截图/生图，通道必须先可用；
        // 而「接 Android 的手只许有一只」⇒ ContentResolver 只经 MediaPublisher 这一处。
        installMedia(appCtx, wsRoot)
        val provider = provider(appCtx)
        val reg = registry(appCtx, provider)
        val pipeline = GuardPipeline(
            reg,
            { workspaceRoot(appCtx) },
            // 第四十八批 P0-2：原件区权威路径＝终端侧的 KnowledgeBase.rawDir。157 目录收口后
            // rawDir 即 App 私有原件区（filesDir/baopu/library/原始/）——天然在工作区外，
            // PathGuard 与 rawRejection 两道都成立。不在这里拼 "资料库/原始" 字符串。
            { KnowledgeBase.rawDir(appCtx) },
            // 492 A 段：多根工作区的附加根清单（`<filesDir>/baopu/workspace_roots.json`）。
            // 主根就是上面那行 workspaceRoot，恒第 0 条；附加根缺省空表 ⇒ 单根行为逐字不变。
            extraRoots = { WorkspaceRoots.extras(appCtx) },
            // 第五百零四单（安全三件）：生产监督缝——停机闸/回滚保险箱/起居注。
            // 不传＝不监督（单测与旧调用点维持 NONE 缺省）；生产从这里接上。
            supervision = com.example.zhengdao.baopu.safety.SafetyWiring.hooks,
        )
        val db = db(appCtx)
        // 第二百八十七批：文件操作账本落库（v20 的 file_op 表）——全盘操作入账，30 天可回放。
        // 装在这里而不在别处：引擎一开跑就可能碰盘，账本必须先于第一次工具调用可用。
        FileOpLog.install(SessionFileOpStore(db))
        // 选项卡的落点：引擎负责弹、等、收答案
        val engine = RunEngine(
            provider = provider,
            db = db,
            registry = reg,
            pipeline = pipeline,
            // 第六百三十单：MCP 全局预算装配（pin→意图→LRU→连接序，装到预算为止）。
            // 注册表取「每次最新的」：换通道重建表后名单仍写进引擎正在用的那张。
            mcpAssembler = com.example.zhengdao.baopu.mcp.McpBudgetWiring(
                registry = { engineRegistry ?: reg },
                budget = mcpBudgetStore(appCtx),
                source = { mcpSourceFor(reg, appCtx) },
                provider = { provider },
                log = { RunLog.log(it) },
            ),
            personaFiles = personaFiles(appCtx),
            // 第一百六十五批：人设包按工作区现读（设置页换包后下一轮生效；文件是真相）。
            personaPack = { activePersonaPack(appCtx) },
            // 第二百一十四批：编排引擎开关（默认关；关掉＝今天行为一行不改）。
            // 只读闸门现读 SP，写口在 OrchestrationSettings——引擎不持 Context。
            orchestrationEnabled = { OrchestrationGate.enabledNow(appCtx) },
            historyPrefilterEnabled = { ScaffoldSwitches.historyPrefilterEnabled(appCtx) },
            compressionPromptEnabled = { ScaffoldSwitches.compressionPromptEnabled(appCtx) },
            // 第三百八十八单（离场值守）：锁屏探针＋事件口——引擎不摸 SP/KeyguardManager，
            // Context 只在装配层（与上面 orchestrationEnabled 同一条规矩）。不开值守时
            // 探针恒 false、事件口不响，行为一行不改。
            awayLocked = { UnattendedStatus.awayAndLocked() },
            awayEvent = { headline ->
                UnattendedRecorder.onDefer(appCtx, headline, System.currentTimeMillis())
            },
            // 第五百零七批：停机记录落 App 私有 `filesDir/baopu/`（与 workspace_roots.json
            // 同一机器状态目录）——不在任何 workspace root 里，模型文件工具够不着，
            // 「解除只能人做」连改文件这条路都不存在。Context 只在这一层（引擎不摸盘路径）。
            stopState = com.example.zhengdao.baopu.agent.StopStateStore(
                File(File(appCtx.filesDir, "baopu"), "run-stop-state.json"),
            ),
            memoryStore = memoryStore(appCtx),
            spillRoot = { workspaceRoot(appCtx) },
            // 第二十四批 C 段追加：超 2 MB 的图先缩再发。手机照片 3 MB 起步，
            // 不缩等于每张都发不出去——2026-10-18 真机「看不见图」就是这个原因。
            shrinkImage = { file -> ImageShrink.toJpeg(file) },
            // 第二百六十八批：截图改落系统截屏夹 `Pictures/Screenshots/`（与用户自己截的图
            // 同目录），读根跟着换成 `<SD根>/Pictures`——`VisionFiles.load` 的 extraRoot
            // 只放开这一个目录（不是整张 SD 卡），`Screenshots/…` 的相对路径在这里解析。
            // 没有这条，引擎会把抱朴自己截的图按「越界」拒掉（模型回读失败＝派工单负例③）。
            shotRoot = { File(Environment.getExternalStorageDirectory(), "Pictures") },
            // 第一百二十八批：知识库自动注入（索引在 ⇒ 每轮把相关片段按 2k 预算拼进
            // 本轮输入；索引不在 ⇒ null，静默跳过不阻塞）。真实存储在 KbTools.store
            // （registry() 里注入的同一份壳），Context 依旧只出现在装配层。
            kbRecall = { text -> KbTools.autoInjectNote(text) },
            // 第四百五十九批：提示词导出（debug 默认开、release 默认关；SP 可拨）。
            // 只把**这一步拼好的** system 段递出去，落盘／脱敏／开关全在 PromptExport 里；
            // 闭包返回 Unit ⇒ 引擎没有任何回写提示词的通道（派工单边界「只读复制」）。
            promptTap = { sys, prov ->
                PromptExport.capture(appCtx, sys, prov.model, prov.id, prov.protocol.name)
            },
            log = { RunLog.log(it) },
            // 熔断自动降级 ＋ 第一百三十一批 ② 多 Key 轮询：引擎只问"下一步是谁"，
            // 怎么挑通道、罚哪把 Key 全在这一个闭包里（Key 不进引擎、不进日志）。
            channelFallback = { cur, err ->
                // 顺序有讲究：**先问池子**。派工单 ② 说的是 429/403/额度耗尽 ⇒ 切下一个 key，
                // 换通道是同一模型把所有 Key 走完之后的事——同一条模型还有一把能用的 Key
                // 就先烧那把，别把用户已经配好的模型悄悄换成别的。
                // 写池子（记冷却/置禁用＋挪游标）只发生在 rotateAfterFailure 里，而它返回
                // Rotated 时引擎**必然**消费这把 Key（FailurePolicy 的 NETWORK / MODEL_CHANNEL
                // 两支都短路成 Fallback，候选非空 ⇒ channelDecision 必走 SwitchTo）
                // ⇒ 不存在"罚了这把、却没人用它"的中间态。异常 ⇒ 当作"与 Key 无关"，
                // 本批接线整体退回第一百三十一批之前的行为（池子写不进去也不能把这一轮弄崩）。
                val rotation = runCatching {
                    ProviderPrefs.rotateAfterFailure(appCtx, cur.id, cur.apiKey, err)
                }.getOrNull() ?: ProviderPrefs.KeyRotation.NotKeyRelated
                if (rotation is ProviderPrefs.KeyRotation.Rotated) {
                    // 同 id、只换凭据 ⇒ 交给引擎的是一条"看起来还是这条模型"的 Provider。
                    // Key 原文只进这一处 copy，日志与 trace 都拿不到它（RunEngine 只打 id/model）。
                    RunRules.ChannelCandidate(
                        next = cur.copy(apiKey = rotation.key),
                        keySwap = true,
                    )
                } else {
                    // 与 Key 无关（超时/断流/5xx）或全池不可用 ⇒ 照旧走通道降级。
                    // Exhausted 的那句人话（不含任何 Key 原文）挂在 notice 上带给界面。
                    val notice = (rotation as? ProviderPrefs.KeyRotation.Exhausted)?.notice.orEmpty()
                    val (cb, cm) = ProviderPrefs.storedCustom(appCtx)
                    val cp = ProviderPrefs.storedCustomProtocol(appCtx)
                    RunRules.ChannelCandidate(
                        // Key 只经 keyFor 从 SP 读：不进引擎、不进日志。
                        next = ProviderPrefs.fallbackFrom(
                            currentId = cur.id,
                            keyFor = { id -> ProviderPrefs.storedKey(appCtx, id) },
                            customBase = cb,
                            customModel = cm,
                            customProtocol = cp,
                        ),
                        notice = notice,
                    )
                }
            },
            // 安全点切换效应（第六十四批）：写只经 ProviderPrefs（唯一真相）。
            // 解析没落到目标（如 custom 三件不齐被兜底成免费通道）⇒ 回滚选择位，
            // SP 与引擎不漂移；异常同样回滚后返回 null（状态机走 FALLBACK）。
            switchChannel = { oldId, targetId ->
                try {
                    ProviderPrefs.selectChannel(appCtx, targetId)
                    val p = ProviderPrefs.current(appCtx)
                    if (p.id != targetId) {
                        ProviderPrefs.selectChannel(appCtx, oldId)
                        null
                    } else {
                        p
                    }
                } catch (t: Throwable) {
                    runCatching { ProviderPrefs.selectChannel(appCtx, oldId) }
                    null
                }
            },
        )
        // ask_user：工具线程阻塞在 engine.askUser 上，UI 点一下即回（有界，与确认卡同超时）
        AskUserTool.sink = { req -> engine.askUser(req) }
        // 第二百零三批：问题组（一卡多问、一次提交）走 engine.askGroup——阻塞到整组
        // 答完并成一条 answers；同会话同批只问一次（账本在引擎里，与 171 同口径）
        AskUserTool.groupSink = { req -> engine.askGroup(req) }
        // 第四百九十九批：打扰治理的装配三件（Context 只到装配层这一只手的延续）。
        // ① 授权档：SP 回填（新装无值 ⇒ 标准，判据⑥）；切档写 SP（档位只能人改，写口在这）。
        // ② 先自查钩子：文件位置类问题列工作区根当答案，不弹卡（B 段判据⑦的生产落点）。
        // ③ 画像读数口（529 批接上）：查询本体在 ProfileIntakeWiring（profile 包），这里只挂
        //    一行；开关默认开、关掉即回 null 行为；画像为空/样本不足一律 null（不造数）。
        AskGovernance.restore(askTierStored(appCtx) ?: AskTier.DEFAULT)
        AskGovernance.tierSink = { t -> askTierStore(appCtx, t) }
        AskIntakeGate.selfCheck = { _ -> selfCheckListing(workspaceRoot(appCtx)) }
        ProfileIntakeWiring.install(enabled = { AskHabitSwitch.on(appCtx) })
        // 第五百七十一批（2026-11-12）：相处模式制度面（法典 L34）的观察口——左相判完档，
        // 把这一轮的既成事实递给右相：学习档收口的达成判定＋改判落一行账（判定件在
        // verify/ 的纯判定区；账与接线在 agent/，与 AskGovLedger 同乡——verify 禁时钟写盘）。
        // 只读：观察者不改判档，原判/新判/依据进自治理中心。
        com.example.zhengdao.baopu.MediatorWiring.turnObserver = { fact ->
            com.example.zhengdao.baopu.agent.CompanionLawWiring.onTurn(
                turn = fact.turn,
                sessionId = fact.sessionId,
                prevMode = fact.prevMode,
                decided = fact.decided,
                correction = fact.correction,
                userText = fact.userText,
            )
        }
        // 第二百八十七批：每轮入口喂会话身份（文件操作账按会话分账，回放时看得清是哪一轮）。
        engine.onSessionEnter = { FileOpLog.enterSession(it) }
        // 第一百二十四批 · 发图同意闸的候选来源：「已验证能看图、且真能切过去」的那几条通道。
        // 判据在 VisionRouting（候选规矩照用 ProviderPrefs.routeCandidates，能力过滤照用
        // ModelRouter + Need(vision=true)）；Key 只在这里现取现用 ⇒ 引擎不持 Key、不进日志。
        engine.visionCandidates = { currentId ->
            val (cb, cm) = ProviderPrefs.storedCustom(appCtx)
            VisionRouting.verifiedCandidates(
                currentId = currentId,
                keyFor = { id -> ProviderPrefs.storedKey(appCtx, id) },
                customBase = cb,
                customModel = cm,
                customProtocol = ProviderPrefs.storedCustomProtocol(appCtx),
            )
        }
        // 147 ②：日记召回源（闭包注入同 visionCandidates 房型）。文件是真相——
        // 每步现读现选（DiaryRecall 只读不写；同会话内日记不中途变化 ⇒ 前缀稳定）。
        // 作用域随工作区（硬点 1）：换工作树即换日记，跨区不回灌。
        // 157：日记根随记忆目录走 `<工作区>/记忆/日记/`（MemoryLayout 单一真源）。
        engine.diaryRecall = {
            DiaryStore(MemoryLayout.memoryDir(workspaceRoot(appCtx))).loadAll().entries
        }
        // 第一百二十七批：多 Agent 协作——run_subtasks 的落点（协调器）在这里装配。
        // 装配层才拿得到 Context/注册表/库 ⇒ 三件生产件（LLM 一轮、工具执行、审计存储）
        // 都在下面 runSubtasks 里拼；引擎侧只认 SubTaskSink 接口（与 AskUserTool.sink
        // 同一房型）。判据与并发全在 multiagent 包的纯函数层，本函数只做接线。
        SubTaskTool.sink = SubTaskSink { args -> runSubtasks(appCtx, engine, reg, args) }
        // 第九十七批：组授权留痕（`[group] …`，可 grep）。只产出文本的纯函数在 agent 包，
        // 落盘统一走 RunLog —— 与 26-B 的 `[kernel]` 留痕同一口径（引擎线程上串行写）。
        ToolGroupAudit.sink = { line -> RunLog.log(line) }
        // 第二百零八批 §1：**决策记录流**的落点（`<App私有>/抱朴记忆/决策记录.jsonl`）。
        // 只存 File 不存 Context（`StaticFieldLeak` 的教训）；各域的写口（技能/产物/日记/评测/
        // 守卫管线）都经 `DecisionLedger.record(...)` 落账，找不到落点就只留一行日志、不静默。
        DecisionLedger.install(File(appCtx.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY))
        // 第五百三十七批：意见冲突旁证落 `<工作区>/产物/.conflicts.jsonl`（点号开头、不计产物数）。
        // 落点随工作区走（多区天然隔离），不塞进全局 `抱朴记忆/`。
        OpinionWiring.installConflictProducts { MemoryLayout.productsDir(workspaceRoot(appCtx)) }
        // 第三百六十三批：偏差账（`偏差账.jsonl`，五类事实只记不判）与决策记录同区挂载；
        // 收工读数（RunEngine.settle）只认它自己的 installedRoot，不回头碰 208 主账。
        DeviationLedger.install(File(appCtx.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY))
        // 第四百二十七批：外部执行体准入（`准入画像-*.json` ＋ `准入账.jsonl`，同区挂载）——
        // MCP 外部执行体第一次调工具时未登记才走 384-A 的四步链（App 内部调用不进准入）；
        // 只登记不拦人，没挂载时管线侧静默（intake/AdmissionWiring 的纪律）。
        AdmissionWiring.install(File(appCtx.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY))
        // 第五百一十七批：**身份/凭据装配**（506 缝的接线位，承诺写在 ConsequenceWiring 头上）——
        // 顺序在账本落点之后：身份自己也要落一行「首启生成」的账。真首装 ⇒ 生成；
        // 恢复后缺身份（U4）⇒ 不生成，体检格与日志提示「需铸新身份」（人工铸新才恢复）。
        ConsequenceWiring.install(appCtx.filesDir, System.currentTimeMillis())
        // 第五百八十九批（生产调用点①，全仓只此一处）：**进化判定样本池**的落点（`<工作区>/.zd-evo/`）。
        // 与 `.zd-ledger.jsonl` 同一个工作区根（点号开头 ⇒ 不计入「产物 N 件」）；没装 ⇒ 读数说
        // "没装配"、攒样本返回 false，启动路径不受影响（`EvoSampleWiring` 自己兜住，不抛）。
        com.example.zhengdao.baopu.evolution.samples.EvoSampleWiring.install(workspaceRoot(appCtx))
        // 第五百一十七批：**边界声明表**（唯一数据源）装配——越界判定/执行闸/体检展示都读它。
        // 可写根类展开表在这里给：workspace＝RW 工作区根，private＝应用私有树。
        BoundaryTable.install(File(appCtx.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY)) {
            val primary = workspaceRoot(appCtx)
            val extras = WorkspaceRoots.extras(appCtx)
            mapOf(
                "workspace" to (listOf(primary) + extras.filter { it.mode == RootMode.RW }.map { it.dir }),
                "private" to listOf(appCtx.filesDir),
            )
        }
        // 第五百一十七批：**深度裁定的左相读数源**（evolution 不许反 import agent，缝在这里接）。
        // 缺哪格就留默认（保守 ⇒ 浅档）：画像置信今天没有生产读数口，给 0＝升不了档——
        // 这是「读数为空 ⇒ 浅档」判据的生产形状，不是遗漏。
        DepthFactsWiring.sideReadings = {
            val now = System.currentTimeMillis()
            val day = AskGovernance.dayKey(now)
            val todayRows = AskGovLedger.replay().filter { AskGovernance.dayOf(it.atMs) == AskGovernance.dayOf(now) }
            val appealsToday = todayRows.count { it.kind == AskGovLedger.KIND_APPEAL }
            val appealsYesterday = AskGovLedger.replay().count {
                it.kind == AskGovLedger.KIND_APPEAL &&
                    AskGovernance.dayOf(it.atMs) == AskGovernance.dayOf(now - DAY_MS_FOR_FACTS)
            }
            DepthSideReadings(
                cardBudgetLeft = AskDailyBudget.LIMIT - AskDailyBudget.usedToday(day),
                profileConfidence = 0.0,
                interruptedToday = todayRows.count { it.kind == AskGovLedger.KIND_CARD },
                authTier = if (AskGovernance.currentTier == AskTier.DELEGATED) 2 else 1,
                falseBlockRising = appealsToday > appealsYesterday,
                canaryAvailable = ConsequenceWiring.currentCanaryBand(CANARY_CAPABILITY) == CanaryBand.FULL,
            )
        }
        // 第五百六十三批：**深度裁定的右相证据位改读 495 评估账**（`evolution/trigger-ledger.jsonl`
        // ——304 的触发账，每次触发跑完评测落一行，带 run/passed/passRate）。这里只交文件，
        // 读与判在 evolution 包（本包零 Android 的房型，与上面那条缝同款）。
        // 没装/文件不在 ⇒ 读不到 ⇒ "证据不足" ⇒ 停在浅档（保守方向，不是遗漏）。
        DepthEvalFeed.ledgerFile = { EvalTriggerWiring.ledgerFile(appCtx) }
        // 第二百九十六批 §2：**影子预演**的沙盒在这里装配（这是全仓唯一一处 `ShadowAdapter.install`）。
        // 没装 ⇒ 第⑤步只能报 `NO_SANDBOX` ⇒ 六步停在 NO_DATA ⇒ 一张卡也不会少弹（保守方向）。
        // 只交引用、不交 Context：沙盒要的全部事实（工作区根／声明对象／内容）都由调用方给。
        ShadowAdapter.install(FileShadowSandbox())
        // 第二百八十批：画像黑箱（`filesDir/baopu_internal/`——**不在**抱朴记忆/工作区等可读
        // 目录，M7 判据「画像文件不在可读目录」）；喂样/重算/判据消费的挂接点在行为路径，
        // 由 M7 与 283 的接线批来接（本批纯数据层，不进 UI 不进上下文）。
        UserProfileBlackbox.install(File(appCtx.filesDir, UserProfileBlackbox.INTERNAL_DIR))
        // 第二百八十批：判决经验跨重装副本——冷启动把三本判决账（御批/意见判定/追认队列）
        // 与 `<工作区>/判决经验/`（默认 `Download/证道/判决经验/`）双向并集同步：重装后私有账
        // 空了，公共副本回灌回来，判决历史不随卸载消失（283 的前置）。
        VerdictExperienceStore.install(workspaceRoot(appCtx), File(appCtx.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY))
        VerdictExperienceStore.sync()
        // 第二百九十八批：**判决时真查表**的装配点（进化环第三圈）。283 把学到的东西写在
        // 内存桩里（进程一散就没了），宰相判"弹不弹"时从没查过那张表——本批补这条链：
        // 换成落盘账本（`抱朴记忆/判决经验账.jsonl`）⇒ 当场把账折成**不可变快照** ⇒
        // 此后每次用户表态/草稿被否都**原子换指针**（判决路径只一次 volatile 读，不加锁）。
        // 顺序要紧：必须在上面 `sync()` **之后**——回灌是把上一世的历史搬回私有账，
        // 先装表等于装一张空表（30 天窗白学一次）。
        LearnWiring.installStore(JudgmentExperienceLedger)
        // 第九十六批：人设补丁留痕（`[persona] …`）。同一条口径：纯函数产行、装配层落盘。
        PersonaAudit.sink = { line -> RunLog.log(line) }
        return engine
    }

    /**
     * 第一百二十七批：`run_subtasks` 的生产落点（解析 ⇒ 协调 ⇒ 聚合）；
     * 第五百三十一批 v2 加四件：上下文按父预算收、写类要父显式授予、账按子任务归、
     * 子任务不成功就按它自己的 opId 撤回写。
     *
     * 三件生产件为什么在这层拼：LLM 一轮要 [com.example.zhengdao.baopu.Provider]、
     * 工具执行要注册表与工作区根、审计存储要 [BaopuDb]——全都带 Context 或进程态，
     * 按「Context 依赖装配成一台机器」的纪律只能出现在本文件。
     *
     * 安全口径：读名单天花板在 [SubAgentRules]（六件只读），写名单要父在
     * `grant_writes` 里逐件点名（[SubAgentWhitelist]）；执行层再按**每条子任务自己的**
     * 名单拦一次（双保险）；写只许落在工作区根内，写前入库、失败整条撤回（470 保险箱）。
     * 通道用引擎此刻的（[RunEngine.currentProviderNow]）——熔断兜底时子 Agent 与父同一条。
     */
    private fun runSubtasks(appCtx: Context, engine: RunEngine, reg: ToolRegistry, args: JSONObject): ToolOutcome {
        val sessionId = ActiveSessionGate.current()?.removePrefix("id:")?.toLongOrNull()
            ?: return ToolOutcome.Rejected("当前没有活跃会话：run_subtasks 只能在抱朴干活时调。")
        // 可用集 = 父会话此刻看得见的（组解锁 ∩ 用户开关）——子 Agent 够不着父够不着的东西
        val available = reg.visibleSpecs(sessionId).map { it.name }.toSet()
        val isWrite = writeClassOf(reg)
        val seq = subTaskSeq.incrementAndGet().toInt()
        return when (
            val parsed = SubAgentRules.parseDecomposition(
                args = args,
                available = available,
                sessionId = sessionId,
                now = System.currentTimeMillis(),
                attemptSeq = seq,
                isWrite = isWrite,
            )
        ) {
            is SubAgentRules.ParseResult.Rejected -> ToolOutcome.Rejected(parsed.reason)
            is SubAgentRules.ParseResult.Ok ->
                runDecomposition(appCtx, engine, reg, parsed.decomposition, isWrite)
        }
    }

    /** 一笔分解的跑法：造守卫/账/每条执行器，交给协调器，收成聚合文本。 */
    private fun runDecomposition(
        appCtx: Context,
        engine: RunEngine,
        reg: ToolRegistry,
        d: TaskDecomposition,
        isWrite: (String) -> Boolean,
    ): ToolOutcome {
        val root = workspaceRoot(appCtx)
        val provider = engine.currentProviderNow()
        // 账双写：落库（持久、可分组查）＋内存一份（收尾那行摘要当场就有真数字，
        // 不等单写者线程刷盘——否则日志会写出「0 行」这种假读数）。
        val rows = java.util.Collections.synchronizedList(mutableListOf<SubAgentLedgerRow>())
        val dbLedger = BaopuSubAgentLedger(db(appCtx), d.sessionId) { RunLog.log(it) }
        val ledger = SubAgentLedgerSink { row -> rows += row; dbLedger.append(row) }
        val guard = VaultSubTaskWriteGuard(d.sessionId.toString(), root, isWrite, ledger, log = { RunLog.log(it) })
        val parentTokens = parentTokensNow(appCtx, d.sessionId)
        val results = SubAgentCoordinator(
            newRunner = { task ->
                subAgentRunner(task, reg, root, provider, engine, isWrite, guard, ledger, parentTokens)
            },
            store = BaopuSubTaskStore(db(appCtx)) { RunLog.log(it) },
            isCancelled = { engine.stopRequestedNow() },
            now = { System.currentTimeMillis() },
            log = { RunLog.log(it) },
            writeGuard = guard,
        ).runAll(d)
        // 第一百八十一批 #5：子任务 token 记账一行进 run_log（判据 WiringKnife2.subAgentUsageLine）。
        subAgentUsageLine(d.parentId, subAgentUsageOf(results))?.let { RunLog.log(it) }
        // 第五百三十一批 ③：账也留一行摘要（明细在 sub_agent_ledger，可按子任务分组）。
        runCatching { RunLog.log(SubAgentLedger.summaryLine(rows.toList())) }
        return ToolOutcome.Ok(
            SubAgentRules.aggregate(results) { id -> d.subtasks.firstOrNull { it.id == id }?.goal },
        )
    }

    /** 一条子任务的执行器（白名单序列化＋执行＋账＋写前入库）。 */
    private fun subAgentRunner(
        task: SubTask,
        reg: ToolRegistry,
        root: File,
        provider: Provider,
        engine: RunEngine,
        isWrite: (String) -> Boolean,
        guard: SubTaskWriteGuard,
        ledger: SubAgentLedgerSink,
        parentTokens: Int,
    ): SubAgentRunner = SubAgentRunner(
        SubAgentEnv(
            turn = LlmSubAgentTurn(provider, reg) { RunLog.log(it) },
            toolExec = { call -> execSubAgentTool(call, task, reg, root, isWrite) },
            toolsJson = subAgentToolsJson(task, reg),
            isCancelled = { engine.stopRequestedNow() },
            log = { RunLog.log(it) },
            parentTokens = parentTokens,
            isWrite = isWrite,
            beforeWrite = { call -> guard.beforeWrite(task, call) },
            ledger = { row -> ledger.append(row) },
        ),
    )

    /** 子 Agent 一条子任务能用哪些工具（读名单＋写名单；空 ⇒ 不发 tools 数组）。 */
    private fun subAgentToolsJson(task: SubTask, reg: ToolRegistry): JSONArray? {
        val allowed = SubAgentWhitelist.allowed(task)
        if (allowed.isEmpty()) return null
        return JSONArray().also { arr ->
            allowed.forEach { name ->
                reg.find(name)?.let { s ->
                    arr.put(
                        JSONObject()
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", s.name)
                                    .put("description", s.description)
                                    .put("parameters", s.inputSchema),
                            ),
                    )
                }
            }
        }
    }

    /** 执行一件子任务调用：名单双保险＋写只许落工作区根内（越界不写）。 */
    private fun execSubAgentTool(
        call: ToolCall,
        task: SubTask,
        reg: ToolRegistry,
        root: File,
        isWrite: (String) -> Boolean,
    ): ToolOutcome {
        val spec = reg.find(call.name)
            ?: return ToolOutcome.Rejected("工具 ${call.name} 当前不可用（被关闭或组未解锁）")
        val allowed = SubAgentWhitelist.allowed(task)
        if (allowed.isNotEmpty() && call.name !in allowed) {
            return ToolOutcome.Rejected("${call.name} 不在这条子任务的白名单里（可用：${allowed.joinToString("、")}）")
        }
        if (isWrite(call.name)) {
            val outside = SubAgentWrites.targetsOf(call, root).firstOrNull { !SubAgentWrites.insideRoot(it, root) }
            if (outside != null) {
                return ToolOutcome.Rejected("子任务只允许写工作区内：$outside 在工作区外，这次不写。")
            }
        }
        return spec.executor(SubAgentWrites.absolutize(call.arguments, root))
    }

    /**
     * 注册表的写类判定（v2 ②：写名单靠它，不靠另抄一张工具表）。
     *
     * 注册表里没有的名字按**写**处理（保守：宁可不给），与 [ToolSpec.readOnly]
     * 「忘了标＝按写处理」同一立场。
     */
    private fun writeClassOf(reg: ToolRegistry): (String) -> Boolean =
        { name -> reg.find(name)?.let { !it.readOnly } ?: true }

    /** 父上下文此刻的 token（最近一步的 `tokens_in`；读不到 ⇒ 0，子上下文只吃地板）。 */
    private fun parentTokensNow(appCtx: Context, sessionId: Long): Int = runCatching {
        val stmt = BaopuSql.lastRunTokensIn(sessionId)
        db(appCtx).read { d ->
            d.rawQuery(stmt.sql, stmt.args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }
    }.getOrDefault(0)

    /** 同会话内第几笔 run_subtasks（分解 id 用它防同毫秒相撞；进程级计数）。 */
    private val subTaskSeq = AtomicLong(0L)

    /**
     * 第五十七批 §1.1：`write_skill(use)` 收口写一条 `skill_event(kind='use')`。
     *
     * - sessionId：从 [ActiveSessionGate] 解析（`"id:<n>"`——门里存的就是这个串）；
     * - step：`run_log` 里本会话最新一步（引擎每步都 persistStep，工具执行时它就是当前步）；
     * - **写失败只 log**（`.get(3s)` 等提交、超时/异常都算失败）——红线「只记录、不阻塞
     *   主流程」：记录是账本，不是闸门，账本卡住业务就是本末倒置。
     * - §1.2「答复里给一句提醒」：记录成功后**顺手查一次过期判据**（刚记的这条也在
     *   窗口里），命中「待重验」就把一句话带回去拼进工具答复。只提醒，**不拦**。
     */
    private fun recordSkillUse(appCtx: Context, name: String, ok: Boolean): String? {
        if (name.isBlank()) return null
        return runCatching {
            val d = db(appCtx)
            val sid = com.example.zhengdao.baopu.agent.ActiveSessionGate.current()
                ?.removePrefix("id:")?.toLongOrNull()
            val step = sid?.let { s ->
                d.read { x ->
                    x.rawQuery(
                        "SELECT MAX(step) FROM run_log WHERE session_id = ?",
                        arrayOf(s.toString()),
                    ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null }
                }
            }
            val now = System.currentTimeMillis()
            val stmt = BaopuSql.insertSkillUse(name, sid, step, ok, now)
            d.write { x -> x.execSQL(stmt.sql, stmt.args) }.get(3, java.util.concurrent.TimeUnit.SECONDS)
            // 第五百二十五批：工具真跑成 ⇒ 许愿灰度的一次真实使用（幂等键＝来源＋技能名；
            // 不在灰度期 / 对不上归属 ⇒ 只留痕不计数。写失败只出声，不影响本函数原返回值）。
            if (ok) runCatching {
                com.example.zhengdao.baopu.agent.WishUsageWiring.onToolHit(appCtx, name)
            }
            // §1.2：提醒。查不到/判不了 ⇒ null（没提醒），同样只读不拦。
            val oks = d.read { x ->
                val s2 = BaopuSql.recentUseOks(name, SkillRefine.SkillExpiry.FAIL_WINDOW)
                x.rawQuery(s2.sql, s2.args).use { c ->
                    (0 until c.count).map { c.moveToPosition(it); c.getInt(0) == 1 }
                }
            }
            val last = d.read { x ->
                val s2 = BaopuSql.lastUseAt(name)
                x.rawQuery(s2.sql, s2.args).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
                }
            }
            val v = SkillRefine.expiryVerdict(last, oks, now)
            if (v.needsRecheck) "（提醒：这条技能${SkillRefine.reasonOf(v)}，先用起来验证它还行不行，别照单全收）" else null
        }.getOrElse {
            RunLog.log("抱朴：skill_use 记录失败（不影响本轮）${it.javaClass.simpleName}: ${it.message}")
            // #10：改前这里回 `null`——「这一条没记进账本」和「读过了、不用提醒」在答复里
            // 一模一样；账本没写成必须说得出（但不拦本轮，57 批红线照旧）。
            skillUseLedgerNote(it)
        }
    }

    /**
     * 账本没写成时带进工具答复的那一句（B 段 #10）。
     *
     * 纯函数、只吃 [Throwable] ⇒ JVM 直测（`recordSkillUse` 本体要 Context＋SQLite，进不了
     * 单测）。只**说明**降级，不改本轮结果——记录是账本，不是闸门。
     */
    internal fun skillUseLedgerNote(e: Throwable): String =
        "（提醒：这条技能这次的使用没记进账本（${e.javaClass.simpleName}），本轮照跑；" +
            "晋升/退役的统计会少这一条）"

    /**
     * 第八十五批 §2：一次技能使用**再**记一条效果信号（`skill_event(kind='effect')`）。
     *
     * 与 57 批的分工：`kind='use'` 记「被用过 ＋ 这次调用成没成」，本条记
     * 「用了之后有没有用」（SUCCESS／FAILURE／USER_CORRECTED／RETRIED）。
     * 过期判据只读 `kind='use'` ⇒ 新行不搅动晋升／退役（红线），两行各写各的、互不覆盖。
     *
     * **只存 id／枚举／时刻**：技能名、正文摘要（[SkillAudit.shaOf] 的 12 位十六进制）、
     * outcome／source／reasonCode、会话号、步号、时刻。提示词、工具参数、文件内容、用户原话
     * 一个字都不进表——用户消息只为「判纠正」在内存里过一遍（[SkillEffects.correctionCode]），
     * 认完就丢；真要写进去的东西先过 [SkillEffects.toStmt] 的闸门，不合格 ⇒ 整条不写并 log。
     *
     * 判定优先级（[SkillEffects.classify]，派工单 §2「判不了就不猜」）：
     * ① 本轮用户否定／改写上一条 ⇒ USER_CORRECTED；② 上一条失败／被纠正且在窗口内 ⇒ RETRIED；
     * ③ 其余按这次调用本身的成败记 SUCCESS／FAILURE。没有会话号 ⇒ 直接走 ③。
     *
     * 写失败**只 log**（与 57 批同口径：账本卡住业务就是本末倒置）；`.get(3s)` 等提交，
     * 超时与异常都算失败。
     */
    private fun recordSkillEffect(appCtx: Context, name: String, ok: Boolean) {
        if (name.isBlank()) return
        runCatching {
            val d = db(appCtx)
            val now = System.currentTimeMillis()
            val sid = com.example.zhengdao.baopu.agent.ActiveSessionGate.current()
                ?.removePrefix("id:")?.toLongOrNull()
            val step = sid?.let { s ->
                val st = BaopuSql.lastRunLogStep(s)
                d.read { x ->
                    x.rawQuery(st.sql, st.args).use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null
                    }
                }
            }
            val (outcome, reason) = if (sid == null) {
                // 没有会话号 ⇒ 上一条信号与本轮用户的话都定位不了（判不了就不猜）
                SkillEffects.classify(null, null, null, ok)
            } else {
                val (prev, prevAt) = SkillEffects.lastEffect(d, name, sid)
                val correction = prevAt?.let { t ->
                    SkillEffects.correctionCode(SkillEffects.userTextAfter(d, sid, t))
                }
                SkillEffects.classify(prev, prevAt?.let { now - it }, correction, ok)
            }
            val skillFile = File(
                SkillRules.projectSkillDir(workspaceRoot(appCtx)),
                "$name/${SkillRules.SKILL_FILE}",
            )
            val signal = SkillEffects.Signal(
                name = name,
                skillVersion = SkillAudit.shaOf(skillFile),
                outcome = outcome,
                source = SkillSource.AUTO,
                reason = reason,
                sessionId = sid,
                step = step,
                attemptOk = ok,
                at = now,
            )
            val stmt = SkillEffects.toStmt(signal)
            if (stmt == null) {
                // 闸门拦下：name／版本形状不对（正文混进来的唯一路径就长这样）。宁缺毋滥。
                RunLog.log("抱朴：skill_effect 被闸门拦下（字段不是合法 token）name=${name.take(8)}")
                return@runCatching
            }
            d.write { x -> x.execSQL(stmt.sql, stmt.args) }.get(3, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrElse {
            RunLog.log("抱朴：skill_effect 记录失败（不影响本轮）${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 最近一个**有内容**的未归档会话的 id（进页面恢复用；没有 ⇒ null）。
     *
     * 为什么要 `EXISTS(message)`（2026-10-17 真机实测）：新建会话是先落 `session` 行、
     * 之后才落 user message。若用户在"刚发出去就被杀"的窗口里死掉，库里会留下一个
     * **空壳会话**（有 session 行、没有任何 message），而它 `updated_at` 最新 ⇒
     * 按 updated_at 取"最近一个"就会选中空壳，重进看到"还没有对话"，
     * 上一轮真正的对话看起来像丢了。过滤掉没有消息的会话即可。
     */
    fun latestSessionId(db: BaopuDb): Long? = db.read { d ->
        d.rawQuery(
            "SELECT s.id FROM session s WHERE s.archived = 0 AND s.deleted_at IS NULL " +
                "AND EXISTS (SELECT 1 FROM message m WHERE m.session_id = s.id) " +
                "ORDER BY s.updated_at DESC LIMIT 1",
            emptyArray(),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    /**
     * 第九十九批 §1.5.3：**手动使用入口**——设置页技能组「用一次」背后记两行账：
     * `kind='use'`（57 批那张表、同一个形状）＋ `kind='effect'` 且 `source=MANUAL`
     * （85 §2 点名的那条：用户点用与模型自用是两种来源，效果聚合时分得开）。
     *
     * 为什么单开一个入口而不是给 [recordSkillEffect] 加开关：设置页这一击**没有会话上下文**
     * （用户在设置里点按钮，不在对话轮里）⇒ 上一条信号、本轮用户的话都定位不了 ⇒
     * 按 85「判不了就不猜」只记裸成败（SUCCESS/FAILURE），不猜 RETRIED/USER_CORRECTED。
     *
     * 复用现有函数、不新开写盘路径：use 行走 [BaopuSql.insertSkillUse]，效果行走
     * [SkillEffects.toStmt]（先过闸门再拼 SQL）；写失败只 log（账本卡住业务就是本末倒置）。
     *
     * @param skillFile 盘上那个技能正文文件（只为取 sha256 版本戳；读不到 ⇒ "-"）
     */
    /**
     * @return 两条账（use＋effect）是否**真落库**（#10：改前返回 Unit，设置页那句
     *   「已记一次」在写失败时照样弹）。界面接线在 `BaopuSettingsScreen`（本单清单外），
     *   先记进报告 §7；这里把区别交出去，调用方爱用不用。
     */
    fun recordSkillUseManual(appCtx: Context, name: String, skillFile: java.io.File?): Boolean {
        if (name.isBlank()) return false
        var wrote = false
        runCatching {
            val d = db(appCtx)
            val now = System.currentTimeMillis()
            val useStmt = BaopuSql.insertSkillUse(name, null, null, true, now)
            d.write { x -> x.execSQL(useStmt.sql, useStmt.args) }
                .get(3, java.util.concurrent.TimeUnit.SECONDS)
            val (outcome, reason) = SkillEffects.classify(null, null, null, true)
            val signal = SkillEffects.Signal(
                name = name,
                skillVersion = SkillAudit.shaOf(skillFile ?: java.io.File(name)),
                outcome = outcome,
                source = SkillSource.MANUAL,
                reason = reason,
                sessionId = null,
                step = null,
                attemptOk = true,
                at = now,
            )
            val stmt = SkillEffects.toStmt(signal)
            if (stmt == null) {
                RunLog.log("抱朴：手动使用效果信号被闸门拦下（字段不是合法 token）name=${name.take(8)}")
                return@runCatching
            }
            d.write { x -> x.execSQL(stmt.sql, stmt.args) }
                .get(3, java.util.concurrent.TimeUnit.SECONDS)
            wrote = true
        }.getOrElse {
            RunLog.log("抱朴：手动使用记录失败（不影响设置页）${it.javaClass.simpleName}: ${it.message}")
        }
        return wrote
    }

    /**
     * 本会话「中断于第 N 步」（-1 = 未中断）。
     *
     * **判据是推导的，不读 `session.interrupted_step`**——那个列被 `RunEngine.persistStep`
     * 当「进度」每步都写、正常结束时**不清零**，于是"没被杀也显示中断"（2026-10-17 真机
     * 实测：403 失败收尾后 UI 仍写「上次中断于第 1 步（进程当时被杀了）」）。
     *
     * 改读 `run_log` 的最后一条：本引擎每步必落一条状态，正常收尾必写 `Done`/`Aborted`
     * （`AwaitConfirm` 也算有收尾——它是"等你确认"，不是被杀了）。所以：
     * - 最后一条是 `Thinking`/`Acting`/`Compressing` ⇒ 跑到这一步就没再往下 ⇒ 被杀；
     * - 最后一条是 `Done`/`Aborted`/`AwaitConfirm` ⇒ 有收尾 ⇒ 不算中断。
     *
     * 这样引擎侧一行不用改（A 段状态机零风险），而且**杀进程留下的痕迹天然保留**：
     * 被杀时来不及写收尾行，最后一条就停在 Thinking/Acting。
     */
    fun interruptedStep(db: BaopuDb, sessionId: Long): Int = db.read { d ->
        d.rawQuery(
            "SELECT step, state FROM run_log WHERE session_id = ? ORDER BY id DESC LIMIT 1",
            arrayOf(sessionId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) {
                -1
            } else {
                when (c.getString(1) ?: "") {
                    // 214：批 checkpoint 也是「跑到一半」的行（kill 在批里 ⇒ 必须报中断）
                    "Thinking", "Acting", "Compressing", "BatchCheckpoint" -> c.getInt(0)
                    else -> -1
                }
            }
        }
    }
}

// ── 第四百九十九批：授权档的 SP 两格＋先自查的列目录（Context 只在这里摸）──────

private const val ASK_TIER_SP_FILE = "baopu_ask_governance"
private const val ASK_TIER_SP_KEY = "tier"

/** 读存的档位（认不出/没存过 ⇒ null，调用方落回 [AskTier.DEFAULT]）。 */
private fun askTierStored(ctx: Context): AskTier? = runCatching {
    AskTier.ofWire(
        ctx.applicationContext.getSharedPreferences(ASK_TIER_SP_FILE, Context.MODE_PRIVATE)
            .getString(ASK_TIER_SP_KEY, "").orEmpty(),
    )
}.getOrNull()

/** 切档落 SP（进程重启后 [askTierStored] 读回同档——"放权"不许悄悄漂回缺省）。 */
private fun askTierStore(ctx: Context, tier: AskTier) {
    runCatching {
        ctx.applicationContext.getSharedPreferences(ASK_TIER_SP_FILE, Context.MODE_PRIVATE)
            .edit { putString(ASK_TIER_SP_KEY, tier.wire) }
    }
}

/**
 * 文件位置类问题的自查（B 段判据⑦的生产落点）：列工作区根一层（目录带斜杠标记）。
 * 返回的文本就是"已查"的答案——模型拿它自己定位，不再弹卡。
 */
private fun selfCheckListing(root: File): String? = runCatching {
    val entries = root.listFiles()?.take(30).orEmpty()
    if (entries.isEmpty()) return null
    val names = entries.sortedBy { it.name.lowercase() }
        .joinToString("、") { if (it.isDirectory) "${it.name}/" else it.name }
    "已查：列了工作区根（${root.name}）这 ${entries.size} 项：$names。" +
        "仍不确定是哪一份时，把具体文件名放进问题里再问。"
}.getOrNull()
