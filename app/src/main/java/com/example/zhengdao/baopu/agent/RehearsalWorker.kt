// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百五十八批 · 推演盘（军师）的**后台层**：WorkManager 长跑 Worker ＋ 现场探针 ＋ 计划送进去的线格式。
//
// ## 为什么是 `Worker` 而不是 `CoroutineWorker`/`ListenableWorker`
// 与 [com.example.zhengdao.schedule.ScheduleWorker] 同一条理由：这趟要做的事（复制、跑工具、算摘要）
// 本身就是阻塞式的，`doWork()` 已经跑在 WorkManager 自己的后台线程池上，再套一层协程只会多一条线程。
//
// ## 为什么不升格成"前台服务"
// 官方把 `Worker` 的长跑窗口定在约 10 分钟量级，超了系统会 STOP；前台服务要 `foregroundServiceType`
// （API 34+ 必填）——本仓 `work-runtime:2.12.0` 的 `SystemForegroundService` 在它的 AAR 清单里
// **没有** type 属性（实测自 `~/.gradle/caches` 那份产物），硬升前台要么改库清单要么自建服务，
// 两条都比"给自己设一个更早的时间预算"贵。所以这里用一个**本批自设**的时间预算
// （[RehearsalWorker.SELF_BUDGET_MS]，不是系统窗口的实测值），到点自己收手并按"已取消"出报告。
//
// ## 三条出口都要清副本（派单【判据】②⑤）
// · 跑完：[RehearsalRunner] 的 `finally` 里清；
// · 用户取消：`doWork()` 里的协作式检查（[Worker.isStopped]）清；
// · 进程被杀／系统强制 STOP：`onStopped()` 兜一次 ＋ 下次入队前 [RehearsalSandbox.sweepStale] 再兜一次。
//
// ## 不申请任何新权限
// 电量／热状态／可用空间三格全部来自 App 自身可读的系统服务与私有 `cacheDir`；
// 网络那一格用的 `getNetworkCapabilities` 需要 `ACCESS_NETWORK_STATE`，**本仓清单里已经有**
// （`app/src/main/AndroidManifest.xml:11`），本批零清单改动。
package com.example.zhengdao.baopu.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 推演作业的执行体（派单【做什么】"后台跑用 WorkManager 长跑 Worker，可取消；取消即清副本"）。
 */
class RehearsalWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    companion object {
        /** 诊断标签（`getWorkInfosByTag` 一把捞出军师的所有作业）。 */
        const val TAG: String = "zd-rehearsal"

        /** 作业数据里那四格的键。 */
        const val KEY_RUN_ID = "runId"
        const val KEY_TASK_KEY = "taskKey"
        const val KEY_WORKSPACE = "workspace"
        const val KEY_PLAN = "plan"

        /**
         * 计划 JSON 的字符上限。
         *
         * `Data` 整体上限约 100KB（超出时 WorkManager 只截不报），这里先自己判、自己拒：
         * 一份塞不下的计划**不跑**比"跑到一半发现参数丢了"诚实得多。
         */
        const val MAX_PLAN_CHARS = 60_000

        /**
         * 本批自设的时间预算（7 分钟）：到点按"已取消"收手。
         *
         * 这是**我们自己给的上限**，不是系统长跑窗口的实测值——写在报告里时口径要说清。
         */
        const val SELF_BUDGET_MS: Long = 7L * 60L * 1000L

        /** 唯一作业名：同 runId 只有一份在排（`REPLACE` 让"重开一次推演"顶掉旧的那份）。 */
        fun workName(runId: String): String = "rh-" + runId.trim().replace(Regex("""[^\w.]"""), "_")

        /**
         * 排一趟推演。
         *
         * @return null＝排上了；非 null＝没排上的原因（调用方拿去显示，不抛）
         */
        internal fun enqueue(ctx: Context, plan: RehearsalPlan): String? {
            val json = RehearsalPlanCodec.encode(plan)
            if (json.length > MAX_PLAN_CHARS) {
                return "计划 ${json.length} 字 > $MAX_PLAN_CHARS ⇒ 作业数据装不下，这趟不推演"
            }
            // `cacheDir` 本身就是 File，不再包一层（副本必须落在 App 私有 cache 里，派单【做什么】第一条）。
            val cache = ctx.applicationContext.cacheDir
            // 先把**上一次没清干净**的军师副本扫掉（进程被杀时 finally 走不到，判据②在真机上靠这条）。
            val swept = RehearsalSandbox(cache, 0L).sweepStale(plan.runId, SELF_BUDGET_MS, System.currentTimeMillis())
            if (swept.bytesFreed > 0L) {
                RunLog.log("军师推演：入队前扫掉遗留副本 ${swept.bytesFreed} B（${swept.removedDirs} 个目录）")
            }
            val data = Data.Builder()
                .putString(KEY_RUN_ID, plan.runId)
                .putString(KEY_TASK_KEY, plan.taskKey)
                .putString(KEY_WORKSPACE, plan.workspaceRoot.absolutePath)
                .putString(KEY_PLAN, json)
                .build()
            val request = OneTimeWorkRequestBuilder<RehearsalWorker>()
                .addTag(TAG)
                .setInputData(data)
                .build()
            return runCatching {
                WorkManager.getInstance(ctx.applicationContext)
                    .enqueueUniqueWork(workName(plan.runId), ExistingWorkPolicy.REPLACE, request)
                null
            }.getOrElse {
                // 排不上不是事故（用户还能看见"这次没推演"的理由），但必须留一行——
                // 否则"推演从不跑"只能靠猜（同 HealthScheduler 那条教训）。
                "排不上作业：${it.javaClass.simpleName}: ${it.message}"
            }
        }

        /** 取消一趟（派单§二④"用户随时能取消"）；副本由 `onStopped()` 清。 */
        fun cancel(ctx: Context, runId: String) {
            runCatching { WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(workName(runId)) }
                .onFailure { RunLog.log("军师推演：取消没送达——${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private val startedAtMs = System.currentTimeMillis()

    override fun doWork(): Result {
        val runId = inputData.getString(KEY_RUN_ID).orEmpty()
        val workspace = inputData.getString(KEY_WORKSPACE).orEmpty()
        val plan = RehearsalPlanCodec.decode(
            inputData.getString(KEY_PLAN).orEmpty(), runId,
            inputData.getString(KEY_TASK_KEY).orEmpty(), File(workspace),
        )
        if (plan == null || workspace.isBlank()) {
            // 作业数据不完整＝结构故障：不建副本、不猜参数，直接如实交回。
            RunLog.log("军师推演：作业数据不完整（runId=$runId）⇒ 不跑")
            return Result.failure()
        }
        val ctx = applicationContext
        val report = runCatching {
            RehearsalRunner.run(
                plan = plan,
                cacheRoot = ctx.cacheDir,
                env = RehearsalProbe.envOf(ctx),
                availableBytes = RehearsalProbe.freeBytes(ctx),
                stop = { isStopped || System.currentTimeMillis() - startedAtMs >= SELF_BUDGET_MS },
            )
        }.getOrElse {
            RunLog.log("军师推演：作业趟抛出 ${it.javaClass.simpleName}: ${it.message}")
            return Result.retry()
        }
        // 报告只进日志/报告区，**不写任何账**（派单【隔离】表：军师那一行的账本格子＝不写任何账）。
        report.lines().forEach { RunLog.log("军师推演|$runId|$it") }
        return when {
            // 道被占 ⇒ 排队重试（派单【隔离】④"串行跑"在调度层的落点）。
            report.block == StartBlock.BUSY -> Result.retry()
            report.blocked -> Result.failure()
            else -> Result.success()
        }
    }

    /**
     * 被取消／被系统 STOP 时的兜底清理（幂等：目录已经不在了就什么都不做）。
     *
     * 与 `doWork()` 里那条协作式清理是两条腿：协作式那条走得优雅（出"已取消"的报告），
     * 这一条保证**报告出不出得来，副本都得没**。
     */
    override fun onStopped() {
        val runId = inputData.getString(KEY_RUN_ID).orEmpty()
        if (runId.isBlank()) return
        val clean = RehearsalSandbox(applicationContext.cacheDir, 0L).discardExisting(runId)
        RunLog.log("军师推演：取消落地，清掉 ${clean.bytesFreed} B（目录已消失=${clean.gone}）")
    }
}

/**
 * 现场探针（派单§二③那三条启动闸的读数来源）。
 *
 * 取不到一律交**保守值**（-1 / false），由 [RehearsalRules.blockOf] 按"拦"算——
 * 本对象不含任何判定，判据全在 `RehearsalObjects`，这样六条判据才走得动 JVM 直测。
 */
internal object RehearsalProbe {

    /** 三格一次取齐（电量→热→网，与 [RehearsalRules.blockOf] 的固定顺序同一份）。 */
    fun envOf(ctx: Context): EnvReading = EnvReading(
        batteryPercent = batteryPercent(ctx),
        thermalStatus = thermalStatus(ctx),
        hasNetwork = hasNetwork(ctx),
    )

    /** 副本要落在 `cacheDir` 所在的那个卷上，所以可用空间问的就是这个卷。 */
    fun freeBytes(ctx: Context): Long = runCatching {
        StatFs(ctx.cacheDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    private fun batteryPercent(ctx: Context): Int = runCatching {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrDefault(-1)

    private fun thermalStatus(ctx: Context): Int = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return -1
        pm.currentThermalStatus
    }.getOrDefault(-1)

    private fun hasNetwork(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)
}

/**
 * 计划的线格式（`Data` 只能带基本类型，所以计划过一道 JSON）。
 *
 * 只带**跑法必需的四格**（步号／工具名／参数／声明对象）：现场参数（工作区、runId）走各自的
 * Data 键，不塞进计划里——同一件事在线上有两份写法，是漂移的开始。
 */
internal object RehearsalPlanCodec {

    fun encode(plan: RehearsalPlan): String {
        val arr = JSONArray()
        plan.steps.forEach { step ->
            arr.put(
                JSONObject()
                    .put("n", step.n)
                    .put("tool", step.call.name)
                    .put("args", step.call.arguments)
                    .put("targets", JSONArray(step.targets)),
            )
        }
        return arr.toString()
    }

    /** 解不出来交 null（调用方按结构故障收，不猜半个计划）。 */
    fun decode(json: String, runId: String, taskKey: String, workspace: File): RehearsalPlan? {
        if (json.isBlank() || !workspace.isDirectory) return null
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val steps = mutableListOf<RehearsalStep>()
        for (i in 0 until arr.length()) {
            val one = arr.optJSONObject(i) ?: return null
            val tool = one.optString("tool")
            if (tool.isBlank()) return null
            steps += RehearsalStep(
                n = one.optInt("n", i + 1),
                call = ToolCall(id = "rh-$runId-${i + 1}", name = tool, arguments = one.optJSONObject("args") ?: JSONObject()),
                targets = one.optJSONArray("targets").let { t ->
                    (0 until (t?.length() ?: 0)).mapNotNull { t?.optString(it) }.filter { it.isNotBlank() }
                },
            )
        }
        if (steps.isEmpty()) return null
        return RehearsalPlan(runId, taskKey, workspace, steps)
    }
}
