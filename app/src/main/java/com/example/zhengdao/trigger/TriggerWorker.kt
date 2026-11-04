// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.zhengdao.core.RunLog

/**
 * 事件触发的执行体（第三百九十九批）：WorkManager 那条路的落点，也是精确闹钟叫醒后的补扫落点。
 *
 * 167 的 ScheduleWorker 同款口径：`doWork()` 已在 WorkManager 的后台线程池上，不再套协程；
 * **不做**的事——不等这一轮跑完、不订阅引擎事件、不感知工具审批（「只做触发＋唤起」，
 * 跑成什么样由抱朴会话页与完成通知去说）。
 */
class TriggerWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val outcome = runCatching { TriggerRunner.runDue(applicationContext) }
            .getOrElse {
                // 整趟抛异常（表读不出来/引擎装配失败）不静默：落日志并让 WorkManager 退避重试。
                RunLog.log("事件触发：作业趟抛出 ${it.javaClass.simpleName}: ${it.message}")
                return Result.retry()
            }
        return if (outcome.needsRetry) Result.retry() else Result.success()
    }
}
