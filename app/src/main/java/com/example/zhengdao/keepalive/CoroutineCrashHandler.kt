// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.keepalive

import com.example.zhengdao.rootfs.RunLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job

/**
 * P4-4（issue #17）：协程未捕获异常的兜底 handler。
 *
 * 审计原文：「有 `keepalive/KeepaliveWatcher.kt:218 setDefaultUncaughtExceptionHandler`，
 * `CoroutineExceptionHandler` 仍 0」。
 *
 * ## 为什么需要它
 *
 * 没有 CoroutineExceptionHandler 时，协程未捕获异常走
 * `handleCoroutineException` → 线程未捕获处理器（= [KeepaliveWatcher] 装的那个）
 * ⇒ 崩溃行为与留档都有，**但丢了协程上下文**：出问题的是哪个协程（名字）、
 * 挂在哪个 Job 下，堆栈里看不出来。排查"SSE 循环为什么死了"这类问题时，
 * 协程名就是第一线索。
 *
 * ## 为什么不"吞"
 *
 * 项目既有哲学（见 [KeepaliveWatcher.installCrashHandler] 的 KDoc）：
 * 「不吞、不覆盖系统行为——用户看到的仍是系统的崩溃提示」。本 handler 照旧：
 * 记一笔带协程上下文的日志，然后**把异常原样交回线程未捕获处理器**，
 * 崩不崩、怎么留档，与没有本 handler 时逐字一致。
 * （⚠️ 不能简单 `throw`：kotlinx 会吞掉 handler 自己抛出的异常，
 *   那样反而把崩溃弄没了——必须显式委派。）
 *
 * ## 装在哪
 *
 * - **UI 侧所有 `rememberCoroutineScope`**（9 处：MainActivity / HomeScreen /
 *   PluginsScreen / BaopuComponents / BaopuScreen×2 / SettingsData / SettingsEnv /
 *   SettingsStorage / ImeRegressionActivity）：一行 `rememberCoroutineScope { CoroutineCrashHandler }`，
 *   失败路径与装之前逐字相同（日志 + 崩溃），白得协程上下文线索。
 * - **`oc/` 目录里的进程级作用域**（OcRuntime 的 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，
 *   SSE / 轮询引擎）**没装**——那是用户划的禁区，本刀不碰；要装得她解禁
 *   （该作用域是唯一长生命周期的，协程名线索价值最高，解禁后优先装它）。
 * - **`lifecycleScope`** 不装：同样跑 Main 派发器，与上述口径相同；要装也是同一行改法。
 */
val CoroutineCrashHandler = CoroutineExceptionHandler { ctx, error ->
    val coroutineName = ctx[CoroutineName]?.name ?: "-"
    val job = ctx[Job]
    val jobText = job?.toString()?.take(80) ?: "-"
    runCatching {
        RunLog.log(
            "协程未捕获异常（coroutine=$coroutineName job=$jobText）：" +
                "${error.javaClass.simpleName}: ${error.message}"
        )
    }
    // 照旧交给线程未捕获处理器（KeepaliveWatcher 装的）：行为与装本 handler 前一致
    runCatching {
        Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(Thread.currentThread(), error)
    }
}
