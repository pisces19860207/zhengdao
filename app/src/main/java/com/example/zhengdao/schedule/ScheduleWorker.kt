// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.zhengdao.rootfs.RunLog

/**
 * 定时任务的执行体（第一百六十七批 §3）：WorkManager 那条路的落点，也是精确闹钟叫醒后的补扫落点。
 *
 * 为什么是 `Worker` 不是 `ListenableWorker`：这一趟要做的事（读表、建会话、发一句话、写表）
 * 本身就是阻塞式的，`doWork()` 已经跑在 WorkManager 自己的后台线程池上，
 * 再套一层协程只会多出一条线程与一处回调（红线 32「不自建裸线程」同样适用于这里）。
 *
 * **不做**的事：不等这一轮跑完、不订阅引擎事件、不感知工具审批。
 * 派工单 §红线把「后台自动执行工具 / 写产物 / 审批代理」全部排除在外，
 * 所以这一层的职责到「消息进队」为止；跑成什么样由抱朴会话页与 169 的完成通知去说。
 */
class ScheduleWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val outcome = runCatching { ScheduleRunner.runDueTasks(applicationContext) }
            .getOrElse {
                // 整趟抛异常（表读不出来 / 引擎装配失败）时不静默：留在日志里，并让 WorkManager 退避重试。
                RunLog.log("定时任务：作业趟抛出 ${it.javaClass.simpleName}: ${it.message}")
                return Result.retry()
            }
        return if (outcome.needsRetry) Result.retry() else Result.success()
    }
}
