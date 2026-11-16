// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 C（阶段三前段）· 判据②⑤的**源码断言**（JVM 无 Compose/JS 引擎的仓规）：
//   判据⑤ 不改 622/645 的端口与闸——bridge 文件名单一个不少一个不多、PortAction/PortCaller
//   的 wire 闭集逐字钉死（改端口＝改名单，测试当场红）；correct 包零 BrowserCommands／零桥；
//   RunEngine 的插话转发只有一行（不改插话语义）。
//   判据② 不许要求重录——correct 包与暂停报告**不出现重录话术**（负例：塞进「请重新录制」
//   必红）；编辑闭集没有「重录」这个 op。
package com.example.zhengdao.baopu.correct

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachCorrectGateTest {

    private fun repoFile(rel: String): File =
        listOf(File(rel), File("../$rel")).firstOrNull { it.exists() }
            ?: throw AssertionError("找不到 $rel")

    /** 剥注释（块＋整行）：判据只读代码，注释里的词不算实现。 */
    private fun code(rel: String): String = stripComments(repoFile(rel).readText())

    private fun stripComments(src: String): String = src.replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val correctDir = "app/src/main/java/com/example/zhengdao/baopu/correct"
    private val bridgeDir = "app/src/main/java/com/example/zhengdao/bridge"

    private fun correctFiles(): List<Pair<String, String>> {
        val dir = listOf(File(correctDir), File("../$correctDir")).firstOrNull { it.isDirectory }
            ?: throw AssertionError("correct 包目录不存在")
        val files = dir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray()
        assertTrue("门禁不许空转", files.isNotEmpty())
        return files.map { it.name to stripComments(it.readText()) }
    }

    // ── 判据⑤：不改 622/645 的端口与闸 ──────────────────────────────────────────

    /** bridge 包的文件名单（645 终态：9 件；多一件＝端口被动过，少一件＝闸被拆）。 */
    private val BRIDGE_FILES = setOf(
        "BridgePolicy.kt", "BrowserCommands.kt", "BrowserPort.kt", "EvidenceSnapshot.kt",
        "JsEmbed.kt", "ObserveAsset.kt", "PortIrreversible.kt", "ShotContract.kt", "TeachCapture.kt",
    )

    /** PortAction 的 wire 闭集（645 终态 26 个；判据⑤钉「一个不增一个不减」）。 */
    private val PORT_ACTIONS = listOf(
        "navigate", "new_tab", "back", "forward", "reload", "scroll", "scroll_into_view",
        "wait", "await_stable", "status", "snapshot", "evidence", "screenshot", "tabs_list",
        "probe", "observe", "click", "type", "select", "hover", "tap", "swipe", "long_press",
        "key", "switch_tab", "close_tab", "act", "exec_js",
    )

    /** PortCaller 的 wire 闭集（7 个）。 */
    private val PORT_CALLERS = listOf(
        "baopu_agent", "teach_record", "teach_replay", "teach_form",
        "drift_probe", "impact_probe", "visual_evidence",
    )

    @Test
    fun `判据5_bridge包文件名单一个不增一个不减`() {
        val dir = File(bridgeDir).let { if (it.isDirectory) it else File("../$bridgeDir") }
        val names = dir.listFiles { f -> f.name.endsWith(".kt") }!!.map { it.name }.toSet()
        assertEquals("bridge 包名单（622/645 终态；判据⑤）", BRIDGE_FILES, names)
    }

    @Test
    fun `判据5_PortAction闭集逐字钉死_EXEC_JS维持EXTERNAL`() {
        val port = code("$bridgeDir/BrowserPort.kt")
        val wires = Regex("\\(\"([a-z_]+)\", PortTier\\.[A-Z]+\\)").findAll(port)
            .map { it.groupValues[1] }.toList()
        assertEquals("PortAction wire 闭集（顺序无关，内容逐字）", PORT_ACTIONS.toSet(), wires.toSet())
        assertTrue("EXEC_JS 维持静态 EXTERNAL（645 口径，不因纠正放开自由 JS）", port.contains("EXEC_JS(\"exec_js\", PortTier.EXTERNAL)"))
        // 负例变异：给闭集加一个新动作 ⇒ 判别函数必抓
        assertFalse(
            "变异（端口加新动作）必须被抓",
            wires.toSet() == PORT_ACTIONS.toSet() + listOf("correct_js"),
        )
    }

    @Test
    fun `判据5_PortCaller闭集逐字钉死`() {
        val port = code("$bridgeDir/BrowserPort.kt")
        val wires = Regex("PortCaller\\(val wire: String\\)").let { _ ->
            port.substringAfter("enum class PortCaller(val wire: String)").substringBefore("}")
                .let { body -> Regex("\"([a-z_]+)\"").findAll(body).map { it.groupValues[1] }.toList() }
        }
        assertEquals("PortCaller wire 闭集", PORT_CALLERS, wires)
    }

    @Test
    fun `判据5_correct包零BrowserCommands零桥_纠正走既有端口条目`() {
        correctFiles().forEachIndexed { i, (name, src) ->
            assertFalse("correct 文件 #$i（$name）不许直引 BrowserCommands", src.contains("BrowserCommands."))
            assertFalse("correct 文件 #$i（$name）不许挂 JS 桥", src.contains("addJavascriptInterface"))
            assertFalse("correct 文件 #$i（$name）不许造第二套安全闸", src.contains("refuseClickReason"))
        }
        val history = code("$correctDir/TeachScriptHistory.kt")
        assertTrue("纠正写盘走 TeachReplayStore.saveConfirmed（三闸照走）", history.contains("TeachReplayStore.saveConfirmed"))
    }

    @Test
    fun `判据5_插话语义一字未改_只加一行转发`() {
        val engine = code("app/src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertTrue("转发口在", engine.contains("CorrectInbox.noteInterjection(text)"))
        // 插话的本体契约不动：FIFO offer、状态判定、所有者过户三行原样在
        assertTrue(engine.contains("if (!interjections.offer(text)) return false"))
        assertTrue(engine.contains("RunState.THINKING, RunState.ACTING, RunState.AWAIT_CONFIRM, RunState.COMPRESSING"))
    }

    // ── 判据②：不许要求重录（源码半边） ────────────────────────────────────────

    @Test
    fun `判据2_correct包与暂停报告不出现重录话术`() {
        correctFiles().forEachIndexed { i, (name, src) ->
            assertFalse("correct 文件 #$i（$name）不许出现重录话术", src.contains("重新录制") || src.contains("请重录"))
        }
        // 暂停报告（executor 的 paused 函数体）同样不给重录话术——给的是续跑指引
        val executor = code("app/src/main/java/com/example/zhengdao/baopu/TeachExecutor.kt")
        val pausedBody = executor.substringAfter("fun paused(atStep: Int, correction: String)").substringBefore("\n        }")
        assertTrue("暂停建议给的是 resume 指引", pausedBody.contains("teach_correct_resume"))
        // 负例变异：往暂停报告塞「请重新录制」⇒ 判别函数必抓
        // （判别函数只禁「要求重录」的祈使句——建议里的「不重录」是承诺，不许自咬）
        fun noRerecord(body: String): Boolean = !body.contains("请重新录制") && !body.contains("重新录制")
        assertTrue(noRerecord(pausedBody))
        assertFalse("变异（重录话术）必须被抓", noRerecord(pausedBody + "请重新录制"))
    }

    @Test
    fun `判据2_编辑闭集没有重录这个op_工具描述给的是不重录`() {
        val tools = code("app/src/main/java/com/example/zhengdao/baopu/agent/tools/TeachTools.kt")
        assertTrue("编辑闭集五件", tools.contains("setOf(\"setValue\", \"setLocator\", \"setExpect\", \"deleteStep\", \"moveStep\")"))
        assertTrue("工具描述明说「不重录」", tools.contains("不重录"))
        val ops = com.example.zhengdao.baopu.correct.CorrectEdit.OPS
        assertFalse("op 闭集里没有重录/重教类动作", ops.any { it.contains("record") || it.contains("relearn") })
    }
}
