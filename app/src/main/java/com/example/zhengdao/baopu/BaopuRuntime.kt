// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §9 模块划分（Context 依赖装配成一台机器）+ 仓库纪律
// 「Context 依赖失败路径测法：抽 Context-free 内核 + 闭包注入」——本文件只做装配，
// 判据全在 agent/ 与 baopu/ 的纯函数层。
package com.example.zhengdao.baopu

import android.content.Context
import com.example.zhengdao.baopu.agent.ActiveSessionGate
import com.example.zhengdao.baopu.agent.AskUserTool
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.CompatChannelTools
import com.example.zhengdao.baopu.agent.GroupUnlockStore
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.ListDirTool
import com.example.zhengdao.baopu.agent.RunEngine
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupAudit
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPrefs
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.multiagent.BaopuSubTaskStore
import com.example.zhengdao.baopu.agent.multiagent.LlmSubAgentTurn
import com.example.zhengdao.baopu.agent.multiagent.SubAgentCoordinator
import com.example.zhengdao.baopu.agent.multiagent.SubAgentEnv
import com.example.zhengdao.baopu.agent.multiagent.SubAgentRules
import com.example.zhengdao.baopu.agent.multiagent.SubAgentRunner
import com.example.zhengdao.baopu.agent.multiagent.SubTaskSink
import com.example.zhengdao.baopu.agent.multiagent.SubTaskTool
import com.example.zhengdao.baopu.library.LibraryImport
import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.baopu.library.LibraryPipeline
import com.example.zhengdao.baopu.library.LibraryStore
import com.example.zhengdao.baopu.mcp.McpToolSource
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.KnowledgeBase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 抱朴运行时（刀 1 · B 段）——**Context 装配的唯一处**，进程级单例。
 *
 * 为什么单例：RunEngine 持有 daemon 线程（zd-baopu-run）与数据库句柄，
 * 每次进页面都新建会泄漏线程池；进程级一份，页面只取引用。
 * 全局单活跃会话由 [ActiveSessionGate] 把关（引擎内部），双保险不冲突。
 */
internal object BaopuRuntime {

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

    /** 数据库（进程级一份；SQLiteOpenHelper 自带线程安全）。 */
    fun db(context: Context): BaopuDb =
        dbInstance ?: synchronized(this) {
            dbInstance ?: BaopuDb(context.applicationContext).also { dbInstance = it }
        }

    /**
     * 当前 provider（刀 1 补丁：从 ProviderPrefs 读——抱朴页「换通道」选的通道 + 用户 Key）。
     * 这仍是 provider 的**唯一入口**：RunEngine / BaopuChatController 都不绕过它。
     * 默认值仍是内置免费通道（第一次装起来就有个能点的默认）。
     */
    fun provider(context: Context): Provider = ProviderPrefs.current(context)

    /**
     * 换通道（UI「启用此通道」落点）：存 SP + 重建引擎。
     * 引擎一份的来历见 [engine]——换通道必须换引擎（provider 是构造期注入的），
     * 重建由下次 [engine] 调用完成；旧引擎的 daemon 线程闲着无害（换通道是低频动作）。
     */
    fun switchChannel(context: Context, channelId: String, apiKey: String, baseUrl: String? = null, model: String? = null) {
        val appCtx = context.applicationContext
        ProviderPrefs.save(appCtx, channelId, apiKey, baseUrl, model)
        synchronized(this) { engineInstance = null }
    }

    /** 工作区根（唯一真相源 Workspace.hostDir；守卫与 spill 都以它为根）。 */
    fun workspaceRoot(context: Context): File = BaopuWorkspace.hostDir(context.applicationContext)

    /** 人设文件读写壳（B 跟 App、C 跟工作区）。 */
    fun personaFiles(context: Context): PersonaFiles {
        val appCtx = context.applicationContext
        return PersonaFiles(
            globalRoot = File(appCtx.filesDir, "抱朴记忆"),
            projectRoot = workspaceRoot(appCtx),
        )
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

    /** 五步流水线（认脸/去重/归一/副本进 `整理/`/写索引）＋ 原件导出。 */
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
            globalRoot = File(appCtx.filesDir, "抱朴记忆"),
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
        val reg = ToolRegistry(
            prefs = toolPrefs(appCtx),
            unlocks = toolUnlocks(appCtx),
            // 第一百零三批 A 段：可见集合变化留痕（`[tools] 可见 N 个：…`）。
            // 真机 TOOLS-D1 在代码层复现不出，下一次复测要能直接读出「那一刻的 tools 数组」。
            onVisibleChanged = { line -> RunLog.log(line) },
        )
        BaopuToolCatalog.loadInto(reg) { workspaceRoot(appCtx) }
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
        if (provider.builtinFree) {
            // 第一百一十九批：这两条的 schema 与执行器搬到了 CompatChannelTools
            // （零依赖 object，JVM 门禁测试才够得着）；这里只剩下「按通道决定注册与否」。
            reg.register(CompatChannelTools.readSpec(readFileEnabled = reg.isEnabled("read_file")))
            reg.register(CompatChannelTools.bashSpec())
        }
        // 第一百二十三批：MCP 工具桥接（只搬已连接的 server；**这里绝不联网**）。
        mcpSourceFor(reg)
        return reg
    }

    /**
     * 外部 MCP 管理器（唯一入口；测试不经过它，直接构造 [McpToolSource]）。
     *
     * [registry] 在每次装配时都会调用它：同一个 source 会被 attach 到当前这张表——
     * 换通道后旧连接继续可用，但工具只在新表里出现。
     */
    fun mcp(context: Context): McpToolSource {
        val appCtx = context.applicationContext
        return mcpSourceFor(registry(appCtx, provider(appCtx)))
    }

    private fun mcpSourceFor(reg: ToolRegistry): McpToolSource {
        val source = mcpSource ?: synchronized(this) {
            mcpSource ?: McpToolSource(reg, log = { RunLog.log(it) }).also { mcpSource = it }
        }
        source.attach(reg)
        return source
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

        /** 组开关那份集合（存的是组的 `key`，不是枚举 ordinal——枚举顺序将来会变）。 */
        private const val KEY_DISABLED_GROUPS = "disabled_groups"

        /**
         * 长期同意开启的组（第九十七批：御批卡「一直」与设置页组开关的落点）。
         *
         * 与 [KEY_DISABLED_GROUPS] 是两件事：approved = 我允许它用，disabled = 我不许它用。
         * 都没有 ⇒ 高组就是「已锁」（模型看不见，抱朴要用时会弹卡申请）。
         */
        private const val KEY_APPROVED_GROUPS = "approved_groups"

        @Volatile
        private var appCtx: Context? = null

        /** 装配期注入（进程级一次；UI 侧经 [toolPrefs] 进来也会先 init）。 */
        fun init(context: Context) {
            appCtx = context.applicationContext
        }

        private fun sp(): android.content.SharedPreferences? =
            appCtx?.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)

        override fun isDisabled(name: String): Boolean =
            sp()?.getStringSet(KEY_DISABLED, emptySet())?.contains(name) == true

        override fun setDisabled(name: String, disabled: Boolean) {
            val p = sp() ?: return
            // getStringSet 返回的实例不许原地改（Android 文档）⇒ 拷贝再写回
            val cur = p.getStringSet(KEY_DISABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (disabled) cur.add(name) else cur.remove(name)
            p.edit().putStringSet(KEY_DISABLED, cur).apply()
        }

        override fun disabledNames(): Set<String> =
            sp()?.getStringSet(KEY_DISABLED, mutableSetOf()) ?: emptySet()

        override fun disabledGroups(): Set<ToolGroup> =
            sp()?.getStringSet(KEY_DISABLED_GROUPS, emptySet())
                ?.mapNotNull { ToolGroup.fromKey(it) }?.toSet()
                ?: emptySet()

        override fun setGroupDisabled(group: ToolGroup, disabled: Boolean) {
            val p = sp() ?: return
            val cur = p.getStringSet(KEY_DISABLED_GROUPS, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (disabled) cur.add(group.key) else cur.remove(group.key)
            p.edit().putStringSet(KEY_DISABLED_GROUPS, cur).apply()
        }

        override fun approvedGroups(): Set<ToolGroup> =
            sp()?.getStringSet(KEY_APPROVED_GROUPS, emptySet())
                ?.mapNotNull { ToolGroup.fromKey(it) }?.toSet()
                ?: emptySet()

        override fun setGroupApproved(group: ToolGroup, approved: Boolean) {
            val p = sp() ?: return
            val cur = p.getStringSet(KEY_APPROVED_GROUPS, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (approved) cur.add(group.key) else cur.remove(group.key)
            p.edit().putStringSet(KEY_APPROVED_GROUPS, cur).apply()
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

        @Volatile
        private var appCtx: Context? = null

        fun init(context: Context) {
            appCtx = context.applicationContext
        }

        private fun sp(): android.content.SharedPreferences? =
            appCtx?.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)

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
            p.edit().putStringSet(key(sessionId), cur).apply()
        }

        override fun clear(sessionId: Long) {
            sp()?.edit()?.remove(key(sessionId))?.apply()
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
            val edit = p.edit()
            p.all.keys.filter { it.startsWith("session:") }.forEach { k ->
                val cur = p.getStringSet(k, emptySet())?.toMutableSet() ?: return@forEach
                if (cur.removeAll(drop)) edit.putStringSet(k, cur)
            }
            edit.apply()
        }
    }

    /** 设置页/引擎用：拿会话解锁存储（顺带保证已 init）。 */
    fun toolUnlocks(context: Context): GroupUnlockStore {
        BaopuToolUnlocks.init(context)
        return BaopuToolUnlocks
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

    private fun build(appCtx: Context): RunEngine {
        // 刀 3 B 段 C 段：预热思考深度的进程级缓存（SP ⇒ liveEffort）——
        // 设置页落盘时也会同刷（ModelParams.saveEffort），这里管的是「跨会话」
        // （上轮选过「低」，这轮直接进抱朴页也要生效，不必先去设置页逛一圈）。
        // 档位真正盖到 provider 上是在 LlmClient 发请求前（每轮现读）。
        ModelParams.initEffort(appCtx)
        val provider = provider(appCtx)
        val reg = registry(appCtx, provider)
        val pipeline = GuardPipeline(
            reg,
            { workspaceRoot(appCtx) },
            // 第四十八批 P0-2：原件区权威路径＝终端侧的 KnowledgeBase.rawDir（抱朴工作区
            // 可自定义，原件区跟的是终端工作区）。不在这里拼 "资料库/原始" 字符串。
            { KnowledgeBase.rawDir(appCtx) },
        )
        val db = db(appCtx)
        // 选项卡的落点：引擎负责弹、等、收答案
        val engine = RunEngine(
            provider = provider,
            db = db,
            registry = reg,
            pipeline = pipeline,
            personaFiles = personaFiles(appCtx),
            memoryStore = memoryStore(appCtx),
            spillRoot = { workspaceRoot(appCtx) },
            // 第二十四批 C 段追加：超 2 MB 的图先缩再发。手机照片 3 MB 起步，
            // 不缩等于每张都发不出去——2026-10-18 真机「看不见图」就是这个原因。
            shrinkImage = { file -> ImageShrink.toJpeg(file) },
            log = { RunLog.log(it) },
            // 熔断自动降级：引擎只问"下一个是谁"，怎么挑在 ProviderPrefs（Key 不进引擎）。
            channelFallback = { cur ->
                val (cb, cm) = ProviderPrefs.storedCustom(appCtx)
                ProviderPrefs.fallbackFrom(
                    currentId = cur.id,
                    keyFor = { id -> ProviderPrefs.storedKey(appCtx, id) },
                    customBase = cb,
                    customModel = cm,
                    // 第九十三批 · 模型路由的**只读**接线：读 SP 现值传进判据。
                    // 用户没显式开过 AUTO_ROUTE ⇒ autoRoute=false、preference=null ⇒
                    // fallbackFrom 走「取候选名单第一个」的老路，行为与本两行存在之前一致。
                    // Key 仍只经 keyFor 从 SP 读：不进引擎、不进日志、不进路由理由。
                    autoRoute = ProviderPrefs.autoRouteEnabled(appCtx),
                    preference = ProviderPrefs.routePreference(appCtx),
                )
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
            )
        }
        // 第一百二十七批：多 Agent 协作——run_subtasks 的落点（协调器）在这里装配。
        // 装配层才拿得到 Context/注册表/库 ⇒ 三件生产件（LLM 一轮、工具执行、审计存储）
        // 都在下面 runSubtasks 里拼；引擎侧只认 SubTaskSink 接口（与 AskUserTool.sink
        // 同一房型）。判据与并发全在 multiagent 包的纯函数层，本函数只做接线。
        SubTaskTool.sink = SubTaskSink { args -> runSubtasks(appCtx, engine, reg, args) }
        // 第九十七批：组授权留痕（`[group] …`，可 grep）。只产出文本的纯函数在 agent 包，
        // 落盘统一走 RunLog —— 与 26-B 的 `[kernel]` 留痕同一口径（引擎线程上串行写）。
        ToolGroupAudit.sink = { line -> RunLog.log(line) }
        // 第九十六批：人设补丁留痕（`[persona] …`）。同一条口径：纯函数产行、装配层落盘。
        PersonaAudit.sink = { line -> RunLog.log(line) }
        return engine
    }

    /**
     * 第一百二十七批：`run_subtasks` 的生产落点（解析 ⇒ 协调 ⇒ 聚合）。
     *
     * 三件生产件为什么在这层拼：LLM 一轮要 [com.example.zhengdao.baopu.Provider]、
     * 工具执行要注册表与工作区根、审计存储要 [BaopuDb]——全都带 Context 或进程态，
     * 按「Context 依赖装配成一台机器」的纪律只能出现在本文件。
     *
     * 安全口径（协作模型 §3）：白名单天花板在 [SubAgentRules]（只读六件），
     * 执行层再按**每条子任务自己的**生效白名单拦一次；通道用引擎此刻的
     * （[RunEngine.currentProviderNow]）——熔断兜底时子 Agent 与父同一条。
     */
    private fun runSubtasks(appCtx: Context, engine: RunEngine, reg: ToolRegistry, args: JSONObject): ToolOutcome {
        val sessionId = ActiveSessionGate.current()?.removePrefix("id:")?.toLongOrNull()
            ?: return ToolOutcome.Rejected("当前没有活跃会话：run_subtasks 只能在抱朴干活时调。")
        // 可用集 = 父会话此刻看得见的（组解锁 ∩ 用户开关）——子 Agent 够不着父够不着的东西
        val available = reg.visibleSpecs(sessionId).map { it.name }.toSet()
        val seq = subTaskSeq.incrementAndGet().toInt()
        return when (
            val parsed = SubAgentRules.parseDecomposition(
                args = args,
                available = available,
                sessionId = sessionId,
                now = System.currentTimeMillis(),
                attemptSeq = seq,
            )
        ) {
            is SubAgentRules.ParseResult.Rejected -> ToolOutcome.Rejected(parsed.reason)
            is SubAgentRules.ParseResult.Ok -> {
                val d = parsed.decomposition
                val root = workspaceRoot(appCtx)
                val provider = engine.currentProviderNow()
                val results = SubAgentCoordinator(
                    newRunner = { task ->
                        val whitelist = task.tools.toSet()
                        val toolsJson = if (whitelist.isEmpty()) {
                            null
                        } else {
                            JSONArray().also { arr ->
                                whitelist.forEach { name ->
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
                        SubAgentRunner(
                            SubAgentEnv(
                                turn = LlmSubAgentTurn(provider, reg) { RunLog.log(it) },
                                toolExec = { call ->
                                    when {
                                        call.name !in whitelist -> ToolOutcome.Rejected(
                                            "${call.name} 不在这条子任务的白名单里" +
                                                "（可用：${whitelist.joinToString("、").ifEmpty { "无" }}）",
                                        )

                                        else -> {
                                            val spec = reg.find(call.name)
                                            if (spec == null) {
                                                ToolOutcome.Rejected("工具 ${call.name} 当前不可用（被关闭或组未解锁）")
                                            } else {
                                                spec.executor(resolveSubTaskPaths(call.arguments, root))
                                            }
                                        }
                                    }
                                },
                                toolsJson = toolsJson,
                                isCancelled = { engine.stopRequestedNow() },
                                log = { RunLog.log(it) },
                            ),
                        )
                    },
                    store = BaopuSubTaskStore(db(appCtx)) { RunLog.log(it) },
                    isCancelled = { engine.stopRequestedNow() },
                    now = { System.currentTimeMillis() },
                    log = { RunLog.log(it) },
                ).runAll(d)
                ToolOutcome.Ok(
                    SubAgentRules.aggregate(results) { id -> d.subtasks.firstOrNull { it.id == id }?.goal },
                )
            }
        }
    }

    /**
     * 只读工具的相对路径 ⇒ 工作区根下绝对路径（与 [GuardPipeline] 读类同一口径）。
     *
     * 只解析 `path` 一个键：白名单里六件只读工具的参数名都是它（逐把核过）。
     * 写类工具不进白名单 ⇒ 不需要 P0-2 那套「解析后真实目标判原件区」——子 Agent
     * 结构上就没有写通路（协作模型 §3）。
     */
    private fun resolveSubTaskPaths(args: JSONObject, root: File): JSONObject {
        val raw = args.optString("path").trim()
        if (raw.isEmpty() || File(raw).isAbsolute) return args
        val out = JSONObject(args.toString())
        return out.put("path", File(root, raw).absolutePath)
    }

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
            null
        }
    }

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
    fun recordSkillUseManual(appCtx: Context, name: String, skillFile: java.io.File?) {
        if (name.isBlank()) return
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
        }.getOrElse {
            RunLog.log("抱朴：手动使用记录失败（不影响设置页）${it.javaClass.simpleName}: ${it.message}")
        }
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
                    "Thinking", "Acting", "Compressing" -> c.getInt(0)
                    else -> -1
                }
            }
        }
    }
}
