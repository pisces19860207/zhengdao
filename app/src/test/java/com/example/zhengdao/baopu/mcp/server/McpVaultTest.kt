// 第五百八十四单 · McpVault 单测：生成/幂等/轮换/常数比较/不泄日志。
package com.example.zhengdao.baopu.mcp.server

import com.example.zhengdao.baopu.mcp.InMemoryMcpSecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpVaultTest {

    private val logs = mutableListOf<String>()

    private fun vault(store: InMemoryMcpSecretStore = InMemoryMcpSecretStore()) =
        PrefsMcpVault(store, log = { logs.add(it) })

    @Test
    fun `ensureToken 生成 43 位 base64url 且幂等`() {
        val v = vault()
        assertNull(v.currentToken())
        val t1 = v.ensureToken()
        assertEquals(43, t1.length)
        assertTrue("base64url 不许带 +/=", t1.none { it == '+' || it == '/' || it == '=' })
        assertEquals("第二次 ensure 必须拿到同一把", t1, v.ensureToken())
        assertEquals(t1, v.currentToken())
    }

    @Test
    fun `matches 常数比较口：对、错、空、null 四格`() {
        val v = vault()
        val t = v.ensureToken()
        assertTrue(v.matches(t))
        assertFalse(v.matches(t.dropLast(1) + if (t.last() == 'A') 'B' else 'A'))
        assertFalse(v.matches(""))
        assertFalse(v.matches(null))
    }

    @Test
    fun `rotate 旧钥匙立即失效`() {
        val v = vault()
        val old = v.ensureToken()
        val new = v.rotate()
        assertNotEquals(old, new)
        assertFalse("旧 token 必须立刻不认", v.matches(old))
        assertTrue(v.matches(new))
        assertEquals(new, v.currentToken())
    }

    @Test
    fun `日志与指纹永不出现 token 本体`() {
        val v = vault()
        val t = v.ensureToken()
        v.rotate()
        logs.forEach { line ->
            assertFalse("日志行里不许有 token 明文：$line", line.contains(t))
        }
        val fp = v.fingerprint()!!
        assertEquals("指纹是 8 位 hex", 8, fp.length)
        assertFalse("指纹不等于 token 片段", t.contains(fp))
    }

    @Test
    fun `存储写坏也不抛，本轮内存照用`() {
        // 写必炸的假仓：ensureToken 第一把存不进去 ⇒ 不抛、内存可用、matches 仍认。
        val broken = object : com.example.zhengdao.baopu.mcp.McpSecretStore {
            override fun read(key: String): String? = null
            override fun write(key: String, value: String) = throw IllegalStateException("disk full")
            override fun remove(key: String) = throw IllegalStateException("disk full")
            override fun keys(): Set<String> = emptySet()
        }
        val v = PrefsMcpVault(broken)
        val t = v.ensureToken()
        assertTrue(v.matches(t))
    }
}
