// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.FileAlreadyExistsException
import javax.net.ssl.SSLException

/**
 * 异常→人话 文案的回归单测。
 *
 * 锁住的行为：title 说问题、**网络类异常不被并进"文件读写失败"**、
 * 磁盘满只认明确标记、未知异常兜底仍带类简名。
 *
 * 2026-10-08：`hint()` / `full()` 已从 HumanizeError 删除（生产代码零调用，理由见其 KDoc），
 * 对应用例一并移除。
 */
class HumanizeErrorTest {

    @Test
    fun `SecurityException - 没有权限`() {
        assertEquals("没有权限", HumanizeError.title(SecurityException("xxx")))
    }

    @Test
    fun `FileNotFoundException - 文件找不到`() {
        assertEquals("文件找不到", HumanizeError.title(FileNotFoundException("/x/y")))
    }

    @Test
    fun `FileAlreadyExistsException - 目标位置已存在同名文件`() {
        assertEquals("目标位置已存在同名文件", HumanizeError.title(FileAlreadyExistsException("/x")))
    }

    // ── 网络类：全是 IOException 子类，绝不能被 ioTitle 收走 ──────────────

    @Test
    fun `UnknownHostException - 找不到服务器`() {
        assertEquals("找不到服务器（请检查网络）", HumanizeError.title(UnknownHostException("api.example.com")))
    }

    @Test
    fun `ConnectException - 连不上服务器`() {
        assertEquals("连不上服务器（请检查网络）", HumanizeError.title(ConnectException("refused")))
    }

    @Test
    fun `SocketTimeoutException - 网络超时`() {
        assertEquals("网络超时（请重试）", HumanizeError.title(SocketTimeoutException("read timed out")))
    }

    @Test
    fun `ConnectException 不被 SocketException 分支抢走`() {
        // ConnectException extends SocketException：分支顺序颠倒会退化成"网络连接中断"
        assertEquals("连不上服务器（请检查网络）", HumanizeError.title(ConnectException("Connection refused")))
    }

    @Test
    fun `SSLException - 安全连接失败，不是文件读写失败`() {
        assertEquals(
            "安全连接失败（请检查网络）",
            HumanizeError.title(SSLException("Chain validation failed")),
        )
    }

    @Test
    fun `SocketException - 网络连接中断，不是文件读写失败`() {
        assertEquals(
            "网络连接中断（请重试）",
            HumanizeError.title(SocketException("Connection reset by peer")),
        )
    }

    @Test
    fun `EOFException - 网络连接中断，不是文件读写失败`() {
        assertEquals(
            "网络连接中断（请重试）",
            HumanizeError.title(EOFException("unexpected end of stream")),
        )
    }

    // ── IOException 子分支 ────────────────────────────────────────────────

    @Test
    fun `IOException space - 存储空间不足`() {
        assertEquals("存储空间不足", HumanizeError.title(IOException("No space left on device")))
    }

    @Test
    fun `磁盘满 - ENOSPC 与 disk full 也认`() {
        assertEquals("存储空间不足", HumanizeError.title(IOException("write failed: ENOSPC")))
        assertEquals("存储空间不足", HumanizeError.title(IOException("Disk full while writing file")))
    }

    @Test
    fun `磁盘满不按 space 子串误判 - 路径里的 My Space 不算磁盘满`() {
        // 旧实现 contains("space") 会把这条普通失败报成"存储空间不足"
        assertEquals(
            "文件读写失败",
            HumanizeError.title(IOException("/sdcard/My Space/out.txt: open failed")),
        )
    }

    @Test
    fun `IOException Permission denied - 没有写入权限`() {
        assertEquals("没有写入权限", HumanizeError.title(IOException("Permission denied (errno=13)")))
    }

    @Test
    fun `IOException 未知 - 兜底为文件读写失败`() {
        assertEquals("文件读写失败", HumanizeError.title(IOException("disk on fire")))
    }

    // ── 未知异常兜底 ──────────────────────────────────────────────────────

    @Test
    fun `RuntimeException 未知 - 兜底为出错了 + 类简名`() {
        assertEquals("出错了（RuntimeException）", HumanizeError.title(RuntimeException("NPE at line 42")))
    }

    @Test
    fun `title 兜底带类简名，但原始 message 不贴脸`() {
        val msg = HumanizeError.title(
            NullPointerException("Attempt to invoke virtual method '/data/user/0/x' on a null object reference"),
        )
        // 类简名保留可诊断性（旧行为是只剩"出错了"，对不上号）
        assertEquals("出错了（NullPointerException）", msg)
        // 原始 message 里的调用细节与路径仍不外泄
        assertFalse(msg.contains("Attempt"))
        assertFalse(msg.contains("/data/user/0"))
    }
}
