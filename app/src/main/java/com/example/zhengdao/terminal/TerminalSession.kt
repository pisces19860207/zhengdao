// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import android.util.Log
import java.io.IOException

/**
 * 一个终端会话 = 一个伪终端 + 一个子进程 + 一个读取线程。
 * 数据流：键盘 → [write] → 伪终端 → 子进程；子进程输出 → 读取线程 → [sink]。
 * M2 起会话由 SessionManager 持有（UI 死 ≠ 会话死）：[sink] 可插拔——
 * 视图不在前台时置 null（输出丢弃，tmux 服务端保状态，重连时靠 resize 重绘恢复画面）。
 */
class TerminalSession(
    cmd: String,
    args: Array<String>,
    env: Array<String>,
    initialCols: Int,
    initialRows: Int,
) {
    companion object {
        private const val TAG = "TerminalSession"
    }

    val fd: Int
    val pid: Int

    /** 当前挂接的视图输出口；null = 无视图（后台），输出直接丢弃 */
    @Volatile
    var sink: ((ByteArray) -> Unit)? = null

    /** 会话进程退出回调（进程级，SessionManager 设置；可能从读取线程调用） */
    @Volatile
    var onExit: ((Int) -> Unit)? = null

    init {
        val packed = Pty.nativeCreate(cmd, args, env, initialCols, initialRows)
        if (packed < 0) throw IOException("无法创建伪终端（nativeCreate 返回 -1）")
        pid = (packed shr 32).toInt()
        fd = (packed and 0xffffffffL).toInt()
    }

    private val readThread = Thread({
        val buf = ByteArray(64 * 1024)
        var chunks = 0
        try {
            Log.i(TAG, "session start pid=$pid fd=$fd")
            while (true) {
                val n = Pty.nativeRead(fd, buf)
                if (n <= 0) break /* 0 = 对端关闭；-1 = 错误 */
                chunks++
                if (chunks <= 5 || chunks % 500 == 0) {
                    Log.i(TAG, "输出块 #$chunks (${n}B): " +
                        buf.copyOf(n).toString(Charsets.UTF_8).take(160))
                }
                sink?.invoke(buf.copyOf(n)) /* 复制切片，回调方持有的数据与本缓冲无关 */
            }
            val code = Pty.nativeWait(pid)
            Log.i(TAG, "会话退出 真实code=$code")
            onExit?.invoke(code.coerceAtLeast(0))
        } catch (t: Throwable) {
            Log.w(TAG, "读取线程结束", t)
            onExit?.invoke(-1)
        }
    }, "pty-reader")

    init {
        readThread.start()
    }

    /** 写入用户键盘输入（UTF-8 文本）。 */
    fun write(data: String) {
        write(data.toByteArray(Charsets.UTF_8))
    }

    /** 写入原始字节（控制字符序列，如 tmux 前缀键）。 */
    fun write(data: ByteArray) {
        if (data.isNotEmpty() && Pty.nativeWrite(fd, data, data.size) < 0) {
            throw IOException("写入伪终端失败")
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (cols > 0 && rows > 0) Pty.nativeResize(fd, pid, cols, rows)
    }

    /** 结束会话（结束整个进程组并回收资源）。 */
    fun kill() {
        Pty.nativeKill(pid)
        Pty.nativeClose(fd)
        try {
            readThread.join(1000)
        } catch (_: InterruptedException) {
        }
    }
}
