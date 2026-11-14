// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.bridge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 622 判据①（源码断言，645 收窄例外口径）：**只有执行端口（BrowserPort）能派发浏览器
 * 动作**——**除 BrowserPort 内部实现外**（全仓只许 `bridge/BrowserPort.kt` 这一个文件，
 * 645 起从"整个 bridge 包"收窄到端口一个文件），任何生产源码出现 `BrowserCommands.`
 * 引用即红；三处旧入口（抱朴网关 / 示教回放 / 探针）全部只认 BrowserPort。
 *
 * 扫描口径与仓库其它源码门禁一致：工作目录 = app/，剥注释再断言（E-133：
 * KDoc 里提「BrowserCommands」是说明历史，不算引用）。
 *
 * 边界：
 * - 例外清单只有 `BrowserPort.kt`（端口的执行段选 BrowserCommands 入口）；其余 bridge
 *   文件（含 `BrowserCommands.kt` 自己）在**代码里**零自引用——645 的 OBSERVE 执行体
 *   之所以长在 BrowserPort.kt 里而不另立文件，正是为了不破坏这条；
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
    fun `判据① 除BrowserPort内部实现外_零BrowserCommands引用_三处旧入口全部收口`() {
        // 645 口径收窄：例外从「bridge 包整体」缩到「BrowserPort 内部实现」一个文件
        val allowed = setOf("com/example/zhengdao/bridge/BrowserPort.kt")
        val offenders = productionSources()
            .filter { it.first !in allowed }
            .filter { strip(it.second).contains("BrowserCommands.") }
            .map { it.first }
        assertTrue(
            "执行端口收口破坏（645 判据①：除 BrowserPort 内部实现外，bridge 之外零 BrowserCommands. 引用）：" +
                "以下文件仍在直接引用 BrowserCommands（必须改走 BrowserPort）→ $offenders",
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
