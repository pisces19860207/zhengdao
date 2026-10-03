// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import android.util.Log
import java.io.IOException

/**
 * 一个终端会话 = 一个伪终端 + 一个子进程 + 一个读取线程。
 * 数据流：键盘 → [write] → 伪终端 → 子进程；子进程输出 → 读取线程 → [onData]。
 */
class TerminalSession(
    cmd: String,
    args: Array<String>,
    env: Array<String>,
    initialCols: Int,
    initialRows: Int,
    private val onData: (ByteArray) -> Unit,
    private val onExit: (Int) -> Unit,
) {
    companion object {
        private const val TAG = "TerminalSession"
    }

    val fd: Int
    val pid: Int

    init {
        val packed = Pty.nativeCreate(cmd, args, env, initialCols, initialRows)
        if (packed < 0) throw IOException("无法创建伪终端（nativeCreate 返回 -1）")
        pid = (packed shr 32).toInt()
        fd = (packed and 0xffffffffL).toInt()
    }

    private val readThread = Thread({
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = Pty.nativeRead(fd, buf)
                if (n <= 0) break /* 0 = 对端关闭；-1 = 错误 */
                onData(buf.copyOf(n)) /* 复制切片，回调方持有的数据与本缓冲无关 */
            }
            onExit(0)
        } catch (t: Throwable) {
            Log.w(TAG, "读取线程结束", t)
            onExit(-1)
        }
    }, "pty-reader")

    init {
        readThread.start()
    }

    /** 写入用户键盘输入（UTF-8 文本）。 */
    fun write(data: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        if (bytes.isNotEmpty() && Pty.nativeWrite(fd, bytes, bytes.size) < 0) {
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
