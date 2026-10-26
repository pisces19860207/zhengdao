// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-24-第一百八十二批 §0.5（不赌 FileObserver ⇒ MediaStore 增量＋
// 周期扫描兜底；刷新是发现变化不是全量重扫；索引可重建；不做后台无脑全扫）。
package com.example.zhengdao.baopu

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import com.example.zhengdao.AppForeground
import com.example.zhengdao.baopu.kb.KbIndexStore
import com.example.zhengdao.baopu.knowledge.KnowledgeIndexStore
import com.example.zhengdao.baopu.knowledge.KnowledgeLayout
import com.example.zhengdao.baopu.knowledge.KnowledgeRefreshRules
import com.example.zhengdao.baopu.knowledge.KnowledgeRefreshRules.Trigger
import com.example.zhengdao.baopu.knowledge.RefreshControl
import com.example.zhengdao.baopu.knowledge.RefreshState
import com.example.zhengdao.baopu.knowledge.RefreshStatus
import com.example.zhengdao.core.AppPrefs
import com.example.zhengdao.rootfs.RunLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 知识库**自动刷新的装配层**（第一百八十二批；Context 到这一层为止）。
 *
 * ## 三条触发（都不赌 FileObserver）
 * 1. **启动/回前台**：进程启动一次增量 ＋ 每次进前台一次（[AppForeground] 的边沿监听）；
 * 2. **MediaStore 增量**：[ContentResolver] 观察 `MediaStore.Files`，2 秒防抖。
 *    观察者**只是可选加速**：装不上、漏事件都只记日志，正确性仍由下面这条兜住；
 * 3. **周期扫描兜底**：仅前台、默认 10 分钟；后台一律不跑（负例「后台无脑扫」）。
 *
 * 另有 `导入完成`（抱朴收料结束后调用 [request]）与用户手动/整理完成。
 * 真正的增量、删除 diff、索引可重建都在 [KnowledgeIndexStore]/[KnowledgeSnapshot] 里。
 */
internal object KnowledgeRefresh {

    private const val KEY_AUTO = "kb_refresh_auto_v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val installed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val pending = AtomicBoolean(false)

    @Volatile
    private var control: RefreshControl? = null

    @Volatile
    private var periodic: Job? = null

    @Volatile
    private var lastPeriodAt: Long = 0

    @Volatile
    private var lastObserverAt: Long = 0

    @Volatile
    var status: RefreshStatus = RefreshStatus()
        private set

    /** 索引文件（供 [KbIndexStore] 读侧合并；晚绑定工作区）。 */
    fun indexFileForWorkspace(workspace: File): File = KnowledgeLayout.indexFile(workspace)

    /** 界面读数（**IO 线程调用**：会读一次索引 JSON）。 */
    data class Summary(
        val files: Int,
        val updatedAt: Long,
        val hasIndex: Boolean,
        val autoEnabled: Boolean,
        val status: RefreshStatus,
        val indexPath: String,
    )

    fun summary(ctx: Context): Summary {
        val ws = BaopuWorkspace.hostDir(ctx)
        val idx = KnowledgeIndexStore.load(ws)
        return Summary(
            files = idx?.stats?.filesIndexed ?: 0,
            updatedAt = idx?.updatedAt ?: 0L,
            hasIndex = idx != null,
            autoEnabled = autoEnabled(ctx),
            status = status,
            indexPath = KnowledgeLayout.indexDir(ws).absolutePath,
        )
    }

    /** 自动刷新开关（默认开；关掉只剩手动）。 */
    fun autoEnabled(ctx: Context): Boolean =
        AppPrefs.prefs(ctx).getBoolean(KEY_AUTO, true)

    fun setAutoEnabled(ctx: Context, on: Boolean) {
        AppPrefs.prefs(ctx).edit { putBoolean(KEY_AUTO, on) }
    }

    /**
     * 装一次（幂等）：注入读侧额外索引、装前台边沿监听、装 MediaStore 观察者、
     * 并跑一次启动增量。所有失败都只记日志，绝不让 App 起不来。
     */
    fun install(context: Context) {
        val appCtx = context.applicationContext
        if (!installed.compareAndSet(false, true)) return
        KbIndexStore.installExtra { indexFileForWorkspace(BaopuWorkspace.hostDir(appCtx)) }
        AppForeground.addListener { front ->
            if (front) {
                request(appCtx, Trigger.FOREGROUND)
                startPeriodic(appCtx)
            } else {
                stopPeriodic()
                // 后台不许继续跑（负例「后台无脑扫」）：正在跑的这批也取消，索引保持上一版。
                control?.cancel()
            }
        }
        registerObserver(appCtx)
        scope.launch {
            delay(1_500L) // 别和冷启动抢 IO
            request(appCtx, Trigger.START)
        }
    }

    /**
     * 请求一次刷新（非阻塞）。门控在 [KnowledgeRefreshRules.gate]：
     * 自动关掉时只有手动；不在前台时除启动/手动外一律不跑。
     */
    fun request(context: Context, trigger: Trigger) {
        val appCtx = context.applicationContext
        val now = System.currentTimeMillis()
        val gate = KnowledgeRefreshRules.gate(
            trigger = trigger,
            foreground = AppForeground.isForeground(),
            autoEnabled = autoEnabled(appCtx),
            lastPeriodAt = lastPeriodAt,
            now = now,
        )
        if (!gate.run) {
            if (trigger == Trigger.MANUAL) RunLog.log("知识库刷新未执行：${gate.why}")
            return
        }
        if (trigger == Trigger.PERIODIC) lastPeriodAt = now
        if (!running.compareAndSet(false, true)) {
            pending.set(true)
            return
        }
        val c = RefreshControl()
        control = c
        scope.launch { doRefresh(appCtx, trigger, c) }
    }

    /** UI 手动刷新（IO 线程调用；跑完返回状态）。已在跑 ⇒ 直接返回当前状态。 */
    fun refreshNow(context: Context, trigger: Trigger = Trigger.MANUAL): RefreshStatus {
        val appCtx = context.applicationContext
        if (!running.compareAndSet(false, true)) return status
        val c = RefreshControl()
        control = c
        doRefresh(appCtx, trigger, c)
        return status
    }

    /** 取消当前刷新（索引不保存半截；下一次接着来）。 */
    fun cancel() {
        control?.cancel()
    }

    private fun doRefresh(ctx: Context, trigger: Trigger, c: RefreshControl) {
        val startedAt = System.currentTimeMillis()
        var resolved = RefreshState.RUNNING
        var message = ""
        try {
            val ws = BaopuWorkspace.hostDir(ctx)
            if (!KnowledgeLayout.ensureDirs(ws)) {
                resolved = RefreshState.FAILED
                message = "建不了 知识库/{原始,整理,索引} 目录"
            } else {
                // 旧 资料库/原始 的存量：先备份→复制→校验→待删清单；旧文件一个都不删。
                val migrated = KnowledgeLayout.migrateLegacy(ws)
                if (!migrated.isEmpty) RunLog.log(migrated.summary())
                val out = KnowledgeIndexStore.build(
                    workspace = ws,
                    filesDir = ctx.filesDir,
                    previousIndex = KnowledgeIndexStore.load(ws),
                    previousSnapshot = KnowledgeIndexStore.loadSnapshot(ws),
                    control = c,
                    now = System.currentTimeMillis(),
                )
                if (out.cancelled) {
                    resolved = RefreshState.CANCELLED
                    message = "已取消：索引保持上一版"
                } else {
                    val index = out.index
                    if (index == null) {
                        resolved = RefreshState.FAILED
                        message = "索引没建出来"
                    } else if (!KnowledgeIndexStore.save(ws, index, out.snapshot)) {
                        resolved = RefreshState.FAILED
                        message = "索引写不进去（App 私有/共享存储 IO 错误）"
                    } else {
                        resolved = RefreshState.DONE
                        message = out.report
                        status = RefreshStatus(
                            state = RefreshState.DONE,
                            reason = trigger.key,
                            done = c.done,
                            total = c.total,
                            files = index.stats.filesIndexed,
                            added = out.diff.added.size,
                            modified = out.diff.modified.size,
                            removed = out.diff.removed.size,
                            elapsedMs = System.currentTimeMillis() - startedAt,
                            at = System.currentTimeMillis(),
                            message = message,
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            resolved = RefreshState.FAILED
            // 用户文案不甩异常原文（原文只进日志）。
            message = "读写或索引失败（详情见日志）"
            RunLog.log("知识库刷新异常：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            if (resolved != RefreshState.DONE) {
                status = status.copy(
                    state = resolved,
                    reason = trigger.key,
                    done = c.done,
                    total = c.total,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    at = System.currentTimeMillis(),
                    message = message,
                )
            }
            RunLog.log(
                "知识库刷新[${trigger.key}]：${status.state.name} " +
                    "文件 ${status.files}、新增 ${status.added}、改动 ${status.modified}、删除 ${status.removed}、" +
                    "用时 ${status.elapsedMs} ms${if (message.isBlank()) "" else "；$message"}",
            )
            running.set(false)
            control = null
            if (pending.getAndSet(false)) request(ctx, Trigger.FOREGROUND)
        }
    }

    private fun startPeriodic(ctx: Context) {
        if (periodic?.isActive == true) return
        periodic = scope.launch {
            while (isActive) {
                delay(KnowledgeRefreshRules.PERIOD_MS)
                if (AppForeground.isForeground()) request(ctx, Trigger.PERIODIC)
            }
        }
    }

    private fun stopPeriodic() {
        periodic?.cancel()
        periodic = null
    }

    /**
     * MediaStore 观察者（可选加速）。装不上只记一行；**正确性不依赖它**——
     * 周期扫描与前台增量的「上轮快照 diff」照样能发现新增/修改/删除。
     */
    private fun registerObserver(ctx: Context) {
        val ok = MediaStoreSink.registerFilesObserver(ctx) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastObserverAt < KnowledgeRefreshRules.OBSERVER_DEBOUNCE_MS) return@registerFilesObserver
            lastObserverAt = now
            request(ctx, Trigger.MEDIASTORE)
        }
        if (!ok) {
            RunLog.log("知识库：MediaStore 观察者没装上——按前台/周期兜底刷新，功能不受影响")
        }
    }
}
