// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * 第㊷刀扩围回归：桥的请求体必须按 Content-Length 的**字节数**读取。
 *
 * 旧实现整条请求走 UTF-8 Reader、body 按 Content-Length 个**字符**读——但
 * Content-Length 是**字节数**，中文一字 3 字节 ⇒ 非 ASCII 请求体必错位
 * （真机实测：type 带中文 ⇒ 空回 RC=52；ASCII 碰巧一字一字节所以此前没暴露）。
 *
 * 负例自证：内嵌一段「旧按字符读」的参考实现，喂一条「body 之后不 EOF」的流
 * （正是 socket 的形态——对端在等响应，不会关写端）⇒ 参考实现要么读错要么
 * 抛异常；新实现 [BrowserAgentBridge.readRequestBody] 读满字节即停，不受影响。
 */
class BridgeHttpBodyTest {

    /** 模拟 socket 的流：发完给定字节后不 EOF，再读就抛（对端在等响应）。 */
    private class SocketLikeStream(private val data: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int =
            if (pos < data.size) data[pos++].toInt() and 0xFF
            else throw IOException("模拟 socket：body 之后无 EOF，继续读会超时")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) throw IOException("模拟 socket：body 之后无 EOF，继续读会超时")
            val n = minOf(len, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    /** 旧实现的参考复刻：Reader 按 Content-Length 个**字符**读（缺陷形态）。 */
    private fun oldCharBasedRead(input: InputStream, contentLength: Int): String {
        val reader = InputStreamReader(input, Charset.forName("UTF-8"))
        val buf = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = reader.read(buf, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    @Test
    fun `中文请求体按字节数读全`() {
        val body = "{\"selector\":\"#inp\",\"text\":\"证道42桥修复\"}"
        val bytes = body.toByteArray(Charset.forName("UTF-8"))
        val got = BrowserAgentBridge.readRequestBody(ByteArrayInputStream(bytes), bytes.size)
        assertEquals(body, got)
    }

    @Test
    fun `中文 body 的字符数小于字节数_新实现按字节读不缺字`() {
        // 「证道」= 2 字符 6 字节：Content-Length: 6 ⇒ 新实现读回 2 个字符的完整串
        val bytes = "证道".toByteArray(Charset.forName("UTF-8"))
        assertEquals(6, bytes.size)
        assertEquals("证道", BrowserAgentBridge.readRequestBody(ByteArrayInputStream(bytes), 6))
    }

    @Test
    fun `请求行与头按行读_含 CRLF 与空行`() {
        val body = "{\"code\":\"1+1\"}"
        val bodyBytes = body.toByteArray(Charset.forName("UTF-8"))
        val raw = "POST /bridge/v1/execJS HTTP/1.1\r\n" +
            "Content-Length: ${bodyBytes.size}\r\n" +
            "\r\n" + body
        val input = ByteArrayInputStream(raw.toByteArray(Charset.forName("UTF-8")))
        assertEquals("POST /bridge/v1/execJS HTTP/1.1", BrowserAgentBridge.readByteLine(input))
        assertEquals("Content-Length: ${bodyBytes.size}", BrowserAgentBridge.readByteLine(input))
        assertEquals("", BrowserAgentBridge.readByteLine(input))
        assertEquals(body, BrowserAgentBridge.readRequestBody(input, bodyBytes.size))
    }

    @Test
    fun `EOF 语义与旧 readLine 对齐`() {
        assertNull(BrowserAgentBridge.readByteLine(ByteArrayInputStream(ByteArray(0))))
        // 无结尾换行的一行：读到 EOF 返回已有内容
        assertEquals("hello", BrowserAgentBridge.readByteLine(ByteArrayInputStream("hello".toByteArray())))
    }

    @Test
    fun `负例_旧按字符读在 socket 形态的流上必炸而新实现不受影响`() {
        val body = "{\"text\":\"证道\"}"
        val bytes = body.toByteArray(Charset.forName("UTF-8"))
        // socket 形态：body 发完不 EOF。旧实现想再凑「字符数=字节数」的量 ⇒ 必然
        // 撞上底层异常（真机上表现为挂到 SO_TIMEOUT 再断连，curl 侧空回）。
        val oldFailed = runCatching { oldCharBasedRead(SocketLikeStream(bytes), bytes.size) }.isFailure
        assertTrue("旧按字符读参考实现必须失败（负例自证）", oldFailed)
        // 新实现读满字节即停，不碰 body 之后的流
        assertEquals(body, BrowserAgentBridge.readRequestBody(SocketLikeStream(bytes), bytes.size))
    }
}
