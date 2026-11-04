// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十七批 ②③的行为判据（派工单判据①「造真实场景 ⇒ 目标能力被调用」＋②「App
// 内部调用 0 回归」的准入侧）。场景＝MCP 外部执行体的工具调用真的进管线形状的入口：
// 首调登记落盘、再调不重跑、写类调用 canAccept 说真话、工具集变了 needsReprobe 说真话。
package com.example.zhengdao.baopu.intake

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdmissionWiringTest {

    private fun tmp(name: String): File = Files.createTempDirectory("baopu-427-admission-$name").toFile()

    @Test
    fun `判据一_外部执行体首调_未登记才走_登记落盘`() {
        val dir = tmp("first")
        AdmissionWiring.install(dir)
        val log = mutableListOf<String>()
        AdmissionWiring.onExternalToolCall(
            wireName = "mcp_demo_search",
            readOnly = true,
            currentNames = listOf("mcp_demo_search", "mcp_demo_write"),
            log = { log += it },
        )
        // 画像快照与准入账都真落了盘（登记处就是 384-A 造好的那两处）。
        assertTrue("画像快照没落盘", AdmissionFlow.snapshotFile(dir, "mcp-demo").isFile)
        assertEquals("准入账行数不对", 1, AdmissionFlow.history(dir).size)
        assertTrue("登记那行没说话：$log", log.size == 1)
        assertTrue(log[0].contains("已登记"))
        // 未考（空卷）⇒ 门给的结论是 reject_incompetent——如实，不冒充考过。
        assertTrue("结论不是门给的：${log[0]}", log[0].contains("reject_incompetent"))
        assertTrue(log[0].contains("canAccept(READ_ONLY)"))
        AdmissionWiring.resetForTest()
    }

    @Test
    fun `判据二_已登记不再走四步_账不涨`() {
        val dir = tmp("second")
        AdmissionWiring.install(dir)
        val log = mutableListOf<String>()
        val names = listOf("mcp_demo_search", "mcp_demo_write")
        AdmissionWiring.onExternalToolCall("mcp_demo_search", true, names) { log += it }
        AdmissionWiring.onExternalToolCall("mcp_demo_search", true, names) { log += it }
        AdmissionWiring.onExternalToolCall("mcp_demo_write", true, names) { log += it }
        assertEquals("四步链被重跑了（准入账涨行）", 1, AdmissionFlow.history(dir).size)
        // 画像与现状对得上 ⇒ 后两次闭嘴（不刷屏）。
        assertEquals("已登记的调用不该说话", 1, log.size)
        AdmissionWiring.resetForTest()
    }

    @Test
    fun `判据三_写类调用_canAccept说真话`() {
        val dir = tmp("write")
        AdmissionWiring.install(dir)
        val log = mutableListOf<String>()
        AdmissionWiring.onExternalToolCall(
            wireName = "mcp_demo_write",
            readOnly = false,
            currentNames = listOf("mcp_demo_write"),
            log = { log += it },
        )
        // 未考 ⇒ clearance 不是 FULL ⇒ 写任务接不了——canAccept(WRITES)=false 必须说出口。
        assertTrue(log.single().contains("canAccept(WRITES)=false"))
        AdmissionWiring.resetForTest()
    }

    @Test
    fun `判据四_工具集变了_重探说真话`() {
        val dir = tmp("reprobe")
        AdmissionWiring.install(dir)
        val log = mutableListOf<String>()
        AdmissionWiring.onExternalToolCall("mcp_demo_search", true, listOf("mcp_demo_search")) { log += it }
        log.clear()
        // server 换了工具集 ⇒ 现行版本指纹对不上作证版本 ⇒ needsReprobe=true。
        AdmissionWiring.onExternalToolCall("mcp_demo_search", true, listOf("mcp_demo_search", "mcp_demo_new")) {
            log += it
        }
        assertTrue("版本变了没报重探：$log", log.size == 1)
        assertTrue(log[0].contains("needsReprobe=true"))
        AdmissionWiring.resetForTest()
    }

    @Test
    fun `判据五_没挂载_静默不连累主流程`() {
        AdmissionWiring.resetForTest() // root = null：生产未装配时的形状
        val log = mutableListOf<String>()
        AdmissionWiring.onExternalToolCall("mcp_demo_search", true, listOf("mcp_demo_search")) { log += it }
        assertTrue("没挂载还说话/还落盘？", log.isEmpty())
        AdmissionWiring.resetForTest()
    }

    @Test
    fun `判据六_执行体身份与版本指纹_按MCP名还原`() {
        // serverSlug 内部下划线保留：最后一个下划线才是 server 与 tool 的界。
        assertEquals("mcp-my_server", AdmissionWiring.agentIdOf("mcp_my_server_tool1"))
        assertEquals("mcp-demo", AdmissionWiring.agentIdOf("mcp_demo_search"))
        // 非 MCP 名混进来不过指纹（版本只由 MCP 工具集决定）。
        assertEquals("mcp_demo_a,mcp_demo_b", AdmissionWiring.versionOf(listOf("read_file", "mcp_demo_b", "mcp_demo_a")))
    }
}
