// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.FileAlreadyExistsException

/**
 * 异常→人话 文案的回归单测。
 *
 * 设计原则（见 HumanizeError KDoc）：title 说问题、hint 给下一步、未知异常兜底。
 * 锁住行为，免得哪天有人改 when 分支把"存储空间不足"误改回"No space left on device"。
 */
class HumanizeErrorTest {

    @Test
    fun `SecurityException - 没有权限 + 引导到设置`() {
        val t = SecurityException("xxx")
        assertEquals("没有权限", HumanizeError.title(t))
        assertNotNull(HumanizeError.hint(t))
    }

    @Test
    fun `FileNotFoundException - 文件找不到`() {
        assertEquals("文件找不到", HumanizeError.title(FileNotFoundException("/x/y")))
    }

    @Test
    fun `FileAlreadyExistsException - 目标位置已存在同名文件`() {
        assertEquals("目标位置已存在同名文件", HumanizeError.title(FileAlreadyExistsException("/x")))
    }

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
    fun `IOException space - 存储空间不足 + 引导到存储设置`() {
        val t = IOException("No space left on device")
        assertEquals("存储空间不足", HumanizeError.title(t))
        assertNotNull(HumanizeError.hint(t))
    }

    @Test
    fun `IOException Permission denied - 没有写入权限 + 引导到权限设置`() {
        val t = IOException("Permission denied (errno=13)")
        assertEquals("没有写入权限", HumanizeError.title(t))
        assertNotNull(HumanizeError.hint(t))
    }

    @Test
    fun `IOException 未知 - 兜底为文件读写失败`() {
        assertEquals("文件读写失败", HumanizeError.title(IOException("disk on fire")))
    }

    @Test
    fun `RuntimeException 未知 - 兜底为出错了`() {
        assertEquals("出错了", HumanizeError.title(RuntimeException("NPE at line 42")))
        assertNotNull(HumanizeError.hint(RuntimeException("NPE")))
    }

    @Test
    fun `full - 有 hint 时拼接，无 hint 时仅 title`() {
        val t1 = IOException("No space left on device")
        assertEquals("存储空间不足：到「设置 → 存储」清一些旧版本或旧日志", HumanizeError.full(t1))

        val t2 = UnknownHostException("a.com")
        assertEquals("找不到服务器（请检查网络）：检查 Wi-Fi 或移动数据是否可用", HumanizeError.full(t2))

        val t3 = IOException("disk on fire")
        assertEquals("文件读写失败", HumanizeError.full(t3))
    }

    @Test
    fun `title 不含 Java 类名 - 避免 NPE 在 title 里出现`() {
        val t = NullPointerException("Attempt to invoke virtual method")
        val msg = HumanizeError.title(t)
        // 兜底应当给"出错了"，绝不直接把 NPE 字样贴脸
        assertEquals("出错了", msg)
        assert(!msg.contains("NullPointer"))
        assert(!msg.contains("Exception"))
    }

    @Test
    fun `hint 始终非空（兜底）`() {
        // 任何 Throwable 都应当有 hint，title 帮不上时 hint 兜底
        val known = UnknownHostException("x")
        val unknown = RuntimeException()
        assertNotNull(HumanizeError.hint(known))
        assertNotNull(HumanizeError.hint(unknown))
    }
}
