// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §9 模块划分（Context 依赖装配成一台机器）+ 仓库纪律
// 「Context 依赖失败路径测法：抽 Context-free 内核 + 闭包注入」——本文件只做装配，
// 判据全在 agent/ 与 baopu/ 的纯函数层。
package com.example.zhengdao.baopu

import android.content.Context
import com.example.zhengdao.baopu.agent.ActiveSessionGate
import com.example.zhengdao.baopu.agent.GuardPipeline
import com.example.zhengdao.baopu.agent.RunEngine
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolRegistry
import com.example.zhengdao.baopu.agent.ToolRunner
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.ListDirTool
import com.example.zhengdao.baopu.agent.ReadFileTool
import com.example.zhengdao.baopu.agent.jsonObjectOf
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.Workspace
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

    /** 当前 provider（本刀固定内置免费通道预设；设置页/换通道归后续刀）。 */
    fun provider(): Provider = Providers.BUILTIN_FREE

    /** 工作区根（唯一真相源 Workspace.hostDir；守卫与 spill 都以它为根）。 */
    fun workspaceRoot(context: Context): File = Workspace.hostDir(context.applicationContext)

    /** 人设文件读写壳（B 跟 App、C 跟工作区）。 */
    fun personaFiles(context: Context): PersonaFiles {
        val appCtx = context.applicationContext
        return PersonaFiles(
            globalRoot = File(appCtx.filesDir, "抱朴记忆"),
            projectRoot = workspaceRoot(appCtx),
        )
    }

    /** 记忆存储壳（文件即真相；global 跟 App、project 跟工作区）。 */
    fun memoryStore(context: Context): MemoryStore {
        val appCtx = context.applicationContext
        return MemoryStore(
            globalRoot = File(appCtx.filesDir, "抱朴记忆"),
            projectRoot = workspaceRoot(appCtx),
        )
    }

    /**
     * 工具注册表（本刀只有两个读工具；写工具归刀 2）。
     * 免费通道另补 read/bash 两个兼容条目——② 台账取证：免费档要求 tools 数组里
     * 含这两个名字，否则服务端 403 FreeTierError。read 映射到 read_file 执行器；
     * bash 没有对应能力，执行器**明确拒绝并说明**（不静默、不假装）。
     */
    fun registry(provider: Provider): ToolRegistry = ToolRegistry().apply {
        register(ReadFileTool.spec())
        register(ListDirTool.spec())
        if (provider.builtinFree) {
            register(
                ToolSpec(
                    name = "read",
                    description = "免费网关兼容占位：等价于 read_file（路径相对工作区根）。",
                    inputSchema = ReadFileTool.spec().inputSchema,
                    permission = ToolPermission.FREE,
                ) { args -> ReadFileTool.spec().executor(args) },
            )
            register(
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
    }

    /**
     * 运行引擎（进程级一份；double-check lazy）。
     *
     * 注意：provider 目前**固定**内置免费通道 ⇒ 引擎也一份。将来设置页可换通道时，
     * 换通道 = 停当前会话 + 重建引擎（ActiveSessionGate 释放后新引擎可抢锁）。
     */
    fun engine(context: Context): RunEngine =
        engineInstance ?: synchronized(this) {
            engineInstance ?: build(context.applicationContext).also { engineInstance = it }
        }

    private fun build(appCtx: Context): RunEngine {
        val provider = provider()
        val reg = registry(provider)
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
        )
    }

    /** 最近一个未归档会话的 id（进页面恢复用；没有 ⇒ null）。 */
    fun latestSessionId(db: BaopuDb): Long? = db.read { d ->
        d.rawQuery(
            "SELECT id FROM session WHERE archived = 0 ORDER BY updated_at DESC LIMIT 1",
            emptyArray(),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    /** 会话的 interrupted_step（-1 = 未中断）。 */
    fun interruptedStep(db: BaopuDb, sessionId: Long): Int = db.read { d ->
        d.rawQuery(
            "SELECT COALESCE(interrupted_step, -1) FROM session WHERE id = ?",
            arrayOf(sessionId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
    }
}
