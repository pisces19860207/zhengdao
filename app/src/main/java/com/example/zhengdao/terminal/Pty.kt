// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

/**
 * JNI 桥：封装 POSIX 伪终端（forkpty）。
 * C 实现见 app/src/main/cpp/pty.c，接口全部是 POSIX 标准 API。
 */
object Pty {

    init {
        System.loadLibrary("zhengdao_pty")
    }

    /** 返回打包值：高 32 位 = 子进程 pid，低 32 位 = 主端 fd。失败返回 -1。 */
    external fun nativeCreate(
        cmd: String,
        args: Array<String>,
        env: Array<String>,
        cols: Int,
        rows: Int,
    ): Long

    /** 写数据到伪终端主端。返回写入字节数，-1 = 失败。 */
    external fun nativeWrite(fd: Int, data: ByteArray, len: Int): Int

    /** 阻塞读（由读取线程循环调用）。0 = 对端关闭，-1 = 错误。 */
    external fun nativeRead(fd: Int, buf: ByteArray): Int

    /** 等待子进程结束并取真实退出码。 */
    external fun nativeWait(pid: Int): Int

    /** 修改终端尺寸并向子进程转发 SIGWINCH。 */
    external fun nativeResize(fd: Int, pid: Int, cols: Int, rows: Int)

    external fun nativeClose(fd: Int)

    /** 结束整个进程组并回收子进程。 */
    external fun nativeKill(pid: Int)
}
