// 第五百八十四单 · 源级门禁：单一绑定点 / TODO(548) 边界标记 / 471 接线不被摘。
//
// 「有 import」≠接上（437 的教训）——这几条钉的都是源码结构，谁把接线拆了当场红。
package com.example.zhengdao.baopu.mcp.server

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerGateTest {

    /** 仓根：测试工作目录 = app/ 的上一级（Gradle 默认）。 */
    private val root = File(System.getProperty("user.dir")).let { if (it.name == "app") it.parentFile else it }

    private fun mainSrc(rel: String): String =
        File(root, "app/src/main/java/com/example/zhengdao/$rel").readText(Charsets.UTF_8)

    /** 扫 app/src/main 的全部 .kt（含 core/terminal 等模块不在内——服务端只在 :app）。 */
    private fun appMainKtSources(): List<File> {
        val dir = File(root, "app/src/main/java")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `服务端包内唯一 ServerSocket 绑定点在传输层实现里（判定⑤的单一闸门）`() {
        // 全仓还有既有的 PreviewServer（玄览预览，不归本单）——这里只钉**本单的服务端包**。
        val pkg = appMainKtSources().filter { it.path.replace('\\', '/').contains("baopu/mcp/server/") }
        val hits = pkg.filter { it.readText().contains("ServerSocket(") }
        assertEquals(
            "ServerSocket 只许出现在 LoopbackHttpTransport（服务端包内别的文件出现即红）",
            listOf("McpServerTransport.kt"),
            hits.map { it.name },
        )
    }

    @Test
    fun `548 边界标记在位：凭据库与传输层两处 TODO(548)（边界不许静默摘除）`() {
        assertTrue("McpVault.kt 缺 TODO(548)", mainSrc("baopu/mcp/server/McpVault.kt").contains("TODO(548)"))
        assertTrue("McpServerTransport.kt 缺 TODO(548)", mainSrc("baopu/mcp/server/McpServerTransport.kt").contains("TODO(548)"))
    }

    @Test
    fun `服务端包零新增 HTTP 依赖（不许引 okhttp 与别的 HTTP 栈）`() {
        val pkgDir = File(root, "app/src/main/java/com/example/zhengdao/baopu/mcp/server")
        pkgDir.listFiles()!!.filter { it.extension == "kt" }.forEach { f ->
            val src = f.readText()
            assertTrue("${f.name} 不许引 okhttp", !src.contains("import okhttp"))
            assertTrue("${f.name} 不许引 Apache HttpClient", !src.contains("org.apache.http"))
        }
    }

    @Test
    fun `471 接线钉在源码上：控制器问 HaltGate，App 装配在位`() {
        val ctl = mainSrc("baopu/mcp/server/McpServerController.kt")
        assertTrue("控制器必须问 HaltGate.halted()（停机联动不能只有看门狗一条命）", ctl.contains("HaltGate.halted()"))
        val app = mainSrc("ZhengdaoApp.kt")
        assertTrue("ZhengdaoApp 必须 install McpServerController", app.contains("McpServerController.install"))
    }

    @Test
    fun `五闸的顺序钉在核心源码上：停机→鉴权→路由→白名单→租约→执行件`() {
        val core = mainSrc("baopu/mcp/server/McpServerCore.kt")
        val order = listOf("haltProbe()", "vault.matches", "request.path", "EV_TOOL_REJECT", "McpCallLease.admit", "runner.run")
        var last = -1
        order.forEach { marker ->
            val idx = core.indexOf(marker)
            assertTrue("五闸顺序被破坏：$marker 没按序出现", idx > last)
            last = idx
        }
    }
}
