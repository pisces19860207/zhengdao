// 第五百八十四单 · 总闸单测：期望对齐（起/停/换绑/停机联动），全部打假传输层（零真套接字）。
package com.example.zhengdao.baopu.mcp.server

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpServerControllerTest {

    /** 假传输层：只记 start/stop 与绑定档，不碰套接字。 */
    private class FakeTransport : McpServerTransport {
        var started: String? = null
        var stopped = 0
        var running = false

        override fun start(config: McpServerTransport.Config, handler: McpServerTransport.Handler): Boolean {
            started = "${config.host}:${config.port}"
            running = true
            return true
        }

        override fun stop() {
            stopped += 1
            running = false
            started = null
        }

        override fun isRunning(): Boolean = running

        override fun endpoint(): String? = started?.takeIf { running }
    }

    private val transport = FakeTransport()
    private val vault = InMemoryMcpVault()
    private val config = McpServerController.MemoryConfig()
    private var halted = false

    @Before
    fun wire() {
        McpAuditLedger.resetForTest()
        halted = false
        transport.stop()
    }

    @After
    fun clean() {
        McpAuditLedger.resetForTest()
        transport.stop()
    }

    /** 开关动一下触发一次 sync 并**等到它跑完**（与 UI 点按同一条路；生产另有看门狗 1s 兜底）。 */
    private fun poke(on: Boolean) {
        McpServerController.setEnabled(on)
        McpServerController.flushSyncForTest()
    }

    private fun install() {
        McpServerController.install(
            config = config,
            vault = vault,
            transport = transport,
            haltProbe = { halted },
            runner = McpToolRunner.UNAVAILABLE,
            version = "test",
            withWatchdog = false,
        )
    }

    @Test
    fun `默认不开：install后不监听`() {
        install()
        poke(false)
        assertNull(transport.started)
        val st = McpServerController.status()
        assertFalse(st.running)
        assertFalse(st.enabled)
        assertNull(st.endpoint)
    }

    @Test
    fun `开关开了没停机 ⇒ 绑127-0-0-1-8765 起（判据⑤默认绑定）`() {
        install()
        poke(true)
        assertEquals("127.0.0.1:${McpServerController.PORT}", transport.started)
        assertTrue(McpServerController.status().running)
        assertTrue("起服务时 token 自动生成", vault.currentToken() != null)
        assertTrue(
            McpAuditLedger.replay().any { it.optString("ev") == McpAuditLedger.EV_SERVER && it.optString("detail") == "started" },
        )
    }

    @Test
    fun `停机闸落下 ⇒ 停服并落账（判据④的闸侧证据）`() {
        install()
        poke(true)
        assertTrue(transport.running)
        halted = true
        poke(true) // 再点一次触发对齐（生产是看门狗 1s，这里手动 poke）
        assertFalse("停机期不许监听", transport.running)
        assertNull(transport.endpoint())
        assertTrue(
            McpAuditLedger.replay().any { it.optString("ev") == McpAuditLedger.EV_SERVER && it.optString("detail") == "halted_stop" },
        )
        // 解除停机 ⇒ 自动复活。
        halted = false
        poke(true)
        assertEquals("127.0.0.1:${McpServerController.PORT}", transport.started)
    }

    @Test
    fun `开关关 ⇒ 停服（审计写明原因）`() {
        install()
        poke(true)
        assertTrue(transport.running)
        poke(false)
        assertFalse(transport.running)
        assertTrue(
            McpAuditLedger.replay().any { it.optString("ev") == McpAuditLedger.EV_SERVER && it.optString("detail") == "disabled_stop" },
        )
    }

    @Test
    fun `运行中换绑 0-0-0-0 ⇒ 重启换绑（可选档显式开才生效）`() {
        install()
        poke(true)
        assertEquals("127.0.0.1:${McpServerController.PORT}", transport.started)
        McpServerController.setBindAll(true)
        McpServerController.flushSyncForTest()
        assertEquals("0.0.0.0:${McpServerController.PORT}", transport.started)
        McpServerController.setBindAll(false)
        McpServerController.flushSyncForTest()
        assertEquals("127.0.0.1:${McpServerController.PORT}", transport.started)
    }

    @Test
    fun `status 读数：端点-白名单数-停机行-指纹齐`() {
        install()
        poke(true)
        val st = McpServerController.status()
        assertTrue(st.running)
        assertEquals("127.0.0.1:${McpServerController.PORT}", st.endpoint)
        assertTrue("白名单读数来自真目录", st.whitelistCount > 0)
        assertTrue((st.tokenFingerprint ?: "").length == 8)
        assertFalse(st.halted)
    }

    @Test
    fun `rotateToken 换钥匙即刻生效`() {
        install()
        val old = vault.ensureToken()
        McpServerController.rotateToken()
        assertFalse("旧 token 必须立刻不认", vault.matches(old))
    }
}
