// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.feedback

import android.content.Context
import android.os.Build
import com.example.zhengdao.BuildConfig
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.core.RunLog
import java.io.File

/**
 * 把本机状态采成[FeedbackDiagnostics]认识的五个键（第四百五十二批）。
 *
 * 采集是**只读、按需、本地**的：只在用户点反馈按钮时跑一次，不落盘、不建后台任务、
 * 不发起任何请求。账摘要只取决策记录的枚举列（kind / group / 可撤销 / 已回滚 / 时刻），
 * `reason`、`evidence`、`before`、`after`、`rollbackRef` 这些自由文本一列都不投影；
 * 失败原因只给分类标签，不取 `run_log.err` 那种可能混着模型输出与工具参数的原文。
 */
internal object FeedbackSnapshot {

    /** 五个白名单键之外，本函数不往 map 里放任何东西。 */
    fun collect(context: Context): Map<String, Any?> {
        val app = context.applicationContext
        val globalRoot = File(app.filesDir, BaopuKernel.Data.DIR_GLOBAL_MEMORY)
        val records = runCatching {
            DecisionLedger.read(globalRoot, FeedbackDiagnostics.LEDGER_LIMIT)
        }.getOrDefault(emptyList())
        return mapOf(
            FeedbackDiagnostics.KEY_VERSION to BuildConfig.VERSION_NAME,
            FeedbackDiagnostics.KEY_MODEL to Build.MODEL,
            FeedbackDiagnostics.KEY_SYSTEM to
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            FeedbackDiagnostics.KEY_LEDGER to records.map { row ->
                LedgerDigest(
                    atMs = row.atMs,
                    kind = row.kind.name,
                    group = row.group.name,
                    reversible = row.reversible,
                    rolledBack = row.rolledBack,
                )
            },
            FeedbackDiagnostics.KEY_FAILURE to failureDigests(app, records),
        )
    }

    private fun failureDigests(
        app: Context,
        records: List<com.example.zhengdao.baopu.DecisionRecord>,
    ): List<FailureDigest> {
        val rows = mutableListOf<FailureDigest>()
        records.filter { it.kind.name.contains("FAIL", ignoreCase = true) }
            .take(FeedbackDiagnostics.FAILURE_LIMIT)
            .forEach { rows.add(FailureDigest(it.atMs, it.kind.name)) }
        if (rows.isEmpty()) {
            val hadErrors = runCatching { RunLog.lastRunHadErrors(app) }.getOrDefault(false)
            val label = if (hadErrors) "上次运行日志有错误" else "最近无失败记录"
            rows.add(FailureDigest(0L, label))
        }
        return rows
    }
}
