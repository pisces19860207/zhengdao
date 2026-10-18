// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §9 模块划分（Context 依赖装配成一台机器）+ 仓库纪律
// 「Context 依赖失败路径测法：抽 Context-free 内核 + 闭包注入」——本文件只做装配，
// 判据全在 agent/ 与 baopu/ 的纯函数层。
package com.example.zhengdao.baopu

import android.content.Context
import com.example.zhengdao.baopu.agent.ActiveSessionGate
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.RunEngine
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolPrefs
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.ListDirTool
import com.example.zhengdao.baopu.agent.ReadFileTool
import com.example.zhengdao.baopu.agent.jsonObjectOf
import com.example.zhengdao.baopu.library.LibraryImport
import com.example.zhengdao.baopu.library.LibraryPaths
import com.example.zhengdao.baopu.library.LibraryPipeline
import com.example.zhengdao.baopu.library.LibraryStore
import com.example.zhengdao.rootfs.RunLog
import org.json.JSONObject
import java.io.File

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
     */
    fun registry(context: Context, provider: Provider): ToolRegistry {
        val appCtx = context.applicationContext
        // 浏览器六件的网关（进程内直调玄览；幂等，重复注入只换同一份）
        BaopuBrowserWiring.install(appCtx)
        val reg = ToolRegistry(toolPrefs(appCtx))
        BaopuToolCatalog.loadInto(reg) { workspaceRoot(appCtx) }
        if (provider.builtinFree) {
            val readSchema = ReadFileTool.spec().inputSchema
            reg.register(
                if (reg.isEnabled("read_file")) {
                    ToolSpec(
                        name = "read",
                        description = "免费网关兼容占位：等价于 read_file（路径相对工作区根）。",
                        inputSchema = readSchema,
                        permission = ToolPermission.FREE,
                    ) { args -> ReadFileTool.spec().executor(args) }
                } else {
                    // read_file 被关 ⇒ 别名同样拒绝（否则设置页的开关就白设了）
                    ToolSpec(
                        name = "read",
                        description = "免费网关兼容占位：read_file 已被用户关闭，本别名同样不可用。",
                        inputSchema = readSchema,
                        permission = ToolPermission.FREE,
                    ) { _ ->
                        ToolOutcome.Rejected(
                            "read_file 已被用户关闭（在 设置 → 终端与资料 → 抱朴工具 里打开）。" +
                                "本 App 不提供别的读取通道，也不会替你打开它。",
                        )
                    }
                },
            )
            reg.register(
                ToolSpec(
                    name = "bash",
                    description = "免费网关兼容占位：本 App 不提供 shell 执行（安全边界，不跨 rootfs）。",
                    inputSchema = jsonObjectOf("type" to "object", "properties" to JSONObject()),
                    permission = ToolPermission.FREE,
                ) { _ ->
                    ToolOutcome.Rejected("bash 被拒：本 App 不提供 shell 执行（不跨 rootfs 是硬边界）；" +
                        "文件相关的事用 list_dir / read_file。")
                },
            )
        }
        return reg
    }

    /**
     * 工具开关存储（SharedPreferences 持久；进程级单例，注册表与设置页共用它）。
     *
     * 放在 [BaopuRuntime]（装配层）而不是 agent 包：注册表要零 Android 依赖
     * （JVM 直测），Context 只能出现在这一层。
     */
    internal object BaopuToolPrefs : ToolPrefs {
        private const val FILE = "baopu_tools"
        private const val KEY_DISABLED = "disabled"

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
        val pipeline = GuardPipeline(reg, { workspaceRoot(appCtx) })
        val db = db(appCtx)
        return RunEngine(
            provider = provider,
            db = db,
            registry = reg,
            pipeline = pipeline,
            personaFiles = personaFiles(appCtx),
            memoryStore = memoryStore(appCtx),
            spillRoot = { workspaceRoot(appCtx) },
            log = { RunLog.log(it) },
            // 熔断自动降级：引擎只问"下一个是谁"，怎么挑在 ProviderPrefs（Key 不进引擎）。
            channelFallback = { cur ->
                val (cb, cm) = ProviderPrefs.storedCustom(appCtx)
                ProviderPrefs.fallbackFrom(
                    currentId = cur.id,
                    keyFor = { id -> ProviderPrefs.storedKey(appCtx, id) },
                    customBase = cb,
                    customModel = cm,
                )
            },
        )
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
            "SELECT s.id FROM session s WHERE s.archived = 0 " +
                "AND EXISTS (SELECT 1 FROM message m WHERE m.session_id = s.id) " +
                "ORDER BY s.updated_at DESC LIMIT 1",
            emptyArray(),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
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
