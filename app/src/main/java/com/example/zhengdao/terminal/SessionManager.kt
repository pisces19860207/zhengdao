// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.zhengdao.rootfs.RunLog

/**
 * M2 会话持有者（保活骨架 §2 简化为单会话模型，用户拍板）：
 * 会话（pty + tmux 客户端）归 [SessionManager] 进程级持有，终端 UI 只是挂上来的视图——
 * UI 关闭 ≠ 会话死：detach 后 pty 继续跑，前台服务（SessionService）负责保活锚点。
 *
 * 进程级语义说明（诚实工程记录）：proot/tmux 是本进程的子进程，进程被系统杀死时
 * 全部消亡——前台服务 + WakeLock 的职责是「阻止被杀」，不是「死后复活」。
 * 用户重进 App 时：进程还活着 → attach 恢复现场；进程死了 → 全新启动（感知为重连）。
 */
object SessionManager {

    private const val PREFS = "zhengdao-session"

    /** 唯一会话（单会话模型，用户拍板：多会话以后有需求再说） */
    var session: TerminalSession? = null
        private set

    /** 会话启动时间（通知栏运行时长用） */
    var startedAtMs: Long = 0L
        private set

    /** 当前会话是否由 tmux 保持（绿点分屏按钮的前置条件；UI 重建后也要能取到） */
    @Volatile
    var usesTmux: Boolean = false

    /** 会话死亡回调（前台 UI 订阅以刷新视图；可能从读取线程触发，已切主线程） */
    var onSessionDied: ((Int) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun isAlive(): Boolean = session != null

    /**
     * 启动全新会话（调用方保证当前无存活会话，或接受旧会话被终止）。
     * 返回启动计划（横幅、isFallback 等由视图层消费）。
     */
    fun start(context: Context, cols: Int, rows: Int): ProotLauncher.LaunchPlan {
        kill(context)
        val plan = ProotLauncher.buildLaunchPlan(context)
        val s = TerminalSession(plan.cmd, plan.args, plan.env, cols, rows)
        s.onExit = { code ->
            mainHandler.post {
                if (session === s) {
                    session = null
                    startedAtMs = 0L
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().remove("started_at").apply()
                    SessionService.stop(context)
                    RunLog.log("会话退出 code=$code")
                    onSessionDied?.invoke(code)
                }
            }
        }
        session = s
        startedAtMs = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("started_at", startedAtMs).apply()
        SessionService.start(context)
        RunLog.log("会话启动 pid=${s.pid}")
        return plan
    }

    /** 视图挂接：把输出口接到当前视图。 */
    fun attach(sink: (ByteArray) -> Unit) {
        session?.sink = sink
    }

    /** 视图脱离：输出丢弃，会话继续跑。 */
    fun detach() {
        session?.sink = null
    }

    fun write(text: String) {
        session?.write(text)
    }

    fun write(bytes: ByteArray) {
        session?.write(bytes)
    }

    fun resize(cols: Int, rows: Int) {
        session?.resize(cols, rows)
    }

    /**
     * 显式终止（通知栏「停止会话」/ 安装完成切换环境）。
     * 先清引用再杀进程：onExit 里 session === s 不成立，避免二次处理。
     */
    fun kill(context: Context) {
        val s = session ?: return
        session = null
        startedAtMs = 0L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("started_at").apply()
        s.kill()
        SessionService.stop(context)
        RunLog.log("会话已停止")
    }
}
