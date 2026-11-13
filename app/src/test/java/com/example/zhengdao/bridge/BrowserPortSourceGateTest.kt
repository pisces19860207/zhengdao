// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.bridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 622 判据①（源码断言）：**只有执行端口（BrowserPort）能派发浏览器动作**——
 * `bridge/` 之外的任何生产源码出现 `BrowserCommands.` 引用即红；三处旧入口
 * （抱朴网关 / 示教回放 / 探针）全部只认 BrowserPort。
 *
 * 扫描口径与仓库其它源码门禁一致：工作目录 = app/，剥注释再断言（E-133：
 * KDoc 里提「BrowserCommands」是说明历史，不算引用）。
 *
 * 边界：
 * - `BrowserCommands.kt` 自己与 `BrowserPort.kt`（端口调执行层）在 bridge 包内，天然合法；
 * - androidTest 的探针测试直接打执行层是**测试设备取证**用的（patrol/），不属于生产派发路，
 *   不在 main 扫描面里（本测试只扫 src/main/java）。
 */
class BrowserPortSourceGateTest {

    private fun file(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    /** 剥注释：块注释整段、单行注释只认整行（`http://` 这种串里的 `//` 不能误伤）。 */
    private fun strip(s: String): String = s
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val mainRoot = File("src/main/java").let { if (it.isDirectory) it else File("app/src/main/java") }

    private fun productionSources(): List<Pair<String, String>> {
        assertTrue("找不到 main 源码根：${mainRoot.path}", mainRoot.isDirectory)
        return mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(mainRoot).invariantSeparatorsPath to it.readText() }
            .toList()
    }

    @Test
    fun `判据① bridge之外零BrowserCommands引用_三处旧入口全部收口`() {
        val offenders = productionSources()
            .filter { !it.first.startsWith("com/example/zhengdao/bridge/") }
            .filter { strip(it.second).contains("BrowserCommands.") }
            .map { it.first }
        assertTrue(
            "执行端口收口破坏：以下文件仍在直接引用 BrowserCommands（必须改走 BrowserPort）→ $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `判据① 旧的三处入口都只认端口`() {
        val wiring = strip(file("src/main/java/com/example/zhengdao/baopu/BaopuBrowserWiring.kt"))
        assertTrue(
            "抱朴网关必须走 BrowserPort",
            wiring.contains("BrowserPort.open") && wiring.contains("PortCaller.BAOPU_AGENT"),
        )
        val teach = strip(file("src/main/java/com/example/zhengdao/baopu/TeachReplay.kt"))
        assertTrue(
            "示教/回放必须走 BrowserPort（probe/act/screenshotRef/navigate）",
            teach.contains("BrowserPort.probe") &&
                teach.contains("BrowserPort.act") &&
                teach.contains("BrowserPort.screenshotRef") &&
                teach.contains("BrowserPort.navigate"),
        )
        val drift = strip(file("src/main/java/com/example/zhengdao/baopu/DriftWire.kt"))
        assertTrue("漂移探针必须走 BrowserPort", drift.contains("BrowserPort.probe"))
        val impact = strip(file("src/main/java/com/example/zhengdao/baopu/ImpactFacts.kt"))
        assertTrue("影响面探针必须走 BrowserPort", impact.contains("BrowserPort.probe"))
        val form = strip(file("src/main/java/com/example/zhengdao/baopu/TeachForm.kt"))
        assertTrue("表单探针必须走 BrowserPort", form.contains("BrowserPort.probe"))
    }

    @Test
    fun `五道闸都在gate一处判_端口内不许出现第二套判据`() {
        val port = strip(file("src/main/java/com/example/zhengdao/bridge/BrowserPort.kt"))
        // gate() 函数体里五道闸按序出现（急停→三档→参数→域→限速）
        val gateBody = port.substringAfter("private fun gate(call: PortCall)").substringBefore("\n    /**")
        assertTrue("gate 里必须第一道判急停", gateBody.contains("haltCheck()"))
        assertTrue("gate 里必须第二道判三档", gateBody.contains("tierStop(call)"))
        assertTrue("gate 里必须第三道判参数", gateBody.contains("paramStop(call)"))
        assertTrue("gate 里必须第四道判域", gateBody.contains("domainStop(call)"))
        assertTrue("gate 里必须第五道判限速", gateBody.contains("throttleStop(call)"))
        // 判据本体只有一份：拒点词表/域判定在 bridge 的唯一实现里，示教层只许委托
        val safety = strip(file("src/main/java/com/example/zhengdao/baopu/TeachSafety.kt"))
        assertTrue(
            "TeachSafety 的拒点判定必须委托 PortIrreversible（词表全仓唯一）",
            safety.contains("PortIrreversible.reasonIn"),
        )
        assertTrue(
            "TeachSafety 的域判定必须委托 BridgePolicy.hostAllowedIn（判据全仓唯一）",
            safety.contains("BridgePolicy.hostAllowedIn"),
        )
    }
}
