// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.keepalive

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/**
 * D-1（第⑪批 B-1）单测：heartbeat 调用线程只入队、零磁盘 IO。
 *
 * 纯 JVM：注入「只记录不执行」的 Executor，ctx 传 null（任务体判空跳过——
 * Context 在 JVM 单测里无法实例化，stub 构造抛 Stub!，实测见交付报告）。
 */
class KeepaliveWatcherTest {

    @Test
    fun `heartbeat_调用线程只入队不落盘`() {
        val queued = mutableListOf<Runnable>()
        val prev = KeepaliveWatcher.ioExecutor
        KeepaliveWatcher.ioExecutor = Executor { r -> queued.add(r) } // 只记录，不执行
        try {
            KeepaliveWatcher.heartbeat(null, "单测事件", 0L) // ctx 判空跳过：执行器只记录不执行
            assertEquals("心跳任务应提交给执行器，而不是在调用线程上做", 1, queued.size)
        } finally {
            KeepaliveWatcher.ioExecutor = prev
        }
    }
}
