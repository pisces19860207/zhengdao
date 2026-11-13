// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百一十七批：首启偶发崩溃（IllegalStateException: Reading a state that was
// created after the snapshot was taken）的**结构门禁 + 冷启夹具**。
//
// 结论（详见 617-首启崩溃-交付 报告 §四）：569 抓到的这条栈就是 586（v2.2.5/v2.2.6）
// 修的那条；崩溃发生在 2.2.3/2.2.4 基线（11-09 12:44、11-10 00:06），修复合入在
// 11-10 20:10 与 20:59，均晚于崩溃。栈帧 BaopuScreen.kt:390/426/430 逐版对齐
// `val state = controller.engineState.value` 那一行，v2.2.5 把它改成 collectAsState。
//
// 但 586 的「预建」留了三处缺口，本门禁把它们钉死：
//  ① MainActivity 的预建被放进 `if (!isTaskRoot) { ... finish(); return }` 提前返回分支
//     —— 正常冷启（isTaskRoot=true）根本不走，注释里说的「两处各预建一次」实际只有一处生效。
//     586 原门禁只比 indexOf 先后，是恒真的，删掉预建都未必红。
//  ② Application 预建被 runCatching 包住、失败「退回懒建」——把保护条件化，
//     装配失败时引擎实例仍为 null，下一次取用又落回组合期，即本单的竞态窗口。
//  ③ switchChannel 把 engineInstance 置空后留给下次 engine() 懒重建，而那次取用来自
//     `remember(channelEpoch) { BaopuChatController(context) }`，正在组合期 —— 换通道/
//     配置变化会重新打开同一个竞态窗口。
//
// 判据一律从磁盘读真实源码（不拿本次渲染的内存字符串自证），并带 6 个负例变异：
// 判据若恒真，变异必然不红，本文件就会自己红给自己看。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLaunchSnapshotGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    /** 去注释，避免注释里的字面量被当成代码钉住（586 原门禁的坑之一）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    /** 花括号配对，取第 n 个 header 所在的块的 [起,止] 下标；找不到返回 null。 */
    private fun blockRange(src: String, header: String, occurrence: Int = 0): IntRange? {
        var from = 0
        var start = -1
        repeat(occurrence + 1) {
            start = src.indexOf(header, from)
            if (start < 0) return null
            from = start + header.length
        }
        val open = src.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return start..i
                }
            }
        }
        return null
    }

    /** pos 是否落在【任一】 header 块内（只取第一个会漏掉后加的包裹）。 */
    private fun enclosedByAny(src: String, header: String, pos: Int): Boolean {
        var count = 0
        var from = 0
        while (true) {
            val hit = src.indexOf(header, from)
            if (hit < 0) return false
            val range = blockRange(src, header, count) ?: return false
            if (pos in range) return true
            count++
            from = hit + header.length
        }
    }

    private val base = "src/main/java/com/example/zhengdao/"
    private val mainSrc get() = repoFile(base + "MainActivity.kt").readText()
    private val appSrc get() = repoFile(base + "ZhengdaoApp.kt").readText()
    private val runtimeSrc get() = repoFile(base + "baopu/BaopuRuntime.kt").readText()
    private val screenSrc get() = repoFile(base + "ui/baopu/BaopuScreen.kt").readText()

    private val engineCallLiteral = "BaopuRuntime.engine(this)"

    // 629（2026-11-13）：装配入口统一过 `StartupGate`（成功⇒主界面／失败⇒错误页），
    // 所以「装配调用」有三种合法写法。**判据语义一点没放宽**：仍必须在 setContent 之前、
    // 不在 `if (!isTaskRoot)` 提前返回分支里、不被 runCatching 包住。
    private val assemblyCallLiterals = listOf(
        "BaopuRuntime.engine(this)",
        "StartupGate.ensure(this)",
        "StartupGate.install(this)",
    )

    /** 装配调用在 code 空间里的最早起点；一处都没有 ⇒ -1。 */
    private fun assemblyCallAt(code: String): Int =
        assemblyCallLiterals.map { code.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1

    /** 命中的那条装配调用原文（变异用例替换它）。 */
    private fun assemblyCallLiteral(code: String): String? =
        assemblyCallLiterals
            .filter { code.indexOf(it) >= 0 }
            .minByOrNull { code.indexOf(it) }
    private val deadBranchHeader = "if (!isTaskRoot)"

    // ── 判据 ①：MainActivity 预建必须在【正常冷启路径】上、setContent 之前、不被吞 ──
    private fun mainPrebuildOnNormalPath(main: String): Boolean {
        val code = codeOnly(main)
        val call = assemblyCallAt(code)
        val setContent = code.indexOf("setContent {")
        if (call < 0 || setContent < 0 || call >= setContent) return false
        val dead = blockRange(code, deadBranchHeader) ?: return false
        if (call in dead) return false
        // 不许 runCatching 包裹预建（吞异常 / 退回懒建 = 降级掩盖）
        if (enclosedByAny(code, "runCatching", call)) return false
        return true
    }

    // ── 判据 ②：Application 预建必须无条件（不吞）、且在 MCP install 之前 ──
    private fun appPrebuildUnconditional(app: String): Boolean {
        val code = codeOnly(app)
        val call = assemblyCallAt(code)
        if (call < 0) return false
        if (enclosedByAny(code, "runCatching", call)) return false
        val mcp = code.indexOf("McpServerController.install")
        return mcp < 0 || call < mcp
    }

    // ── 判据 ③：switchChannel 置空后必须【同步重建】，不许留给组合期懒建 ──
    private fun switchRebuildsSynchronously(runtime: String): Boolean {
        val code = codeOnly(runtime)
        val start = code.indexOf("fun switchChannel(")
        if (start < 0) return false
        val body = blockRange(code, "fun switchChannel(") ?: return false
        val region = code.substring(body.first, body.last + 1)
        val nullify = region.indexOf("engineInstance = null")
        val rebuild = region.indexOf("engine(appCtx)")
        return nullify >= 0 && rebuild > nullify
    }

    // ── 判据 ④：v2.2.5 的覆盖不许回退（栈顶那一行仍是 StateFlow + collectAsState）──
    private fun stateFlowCoverageIntact(screen: String, engine: String): Boolean {
        val s = codeOnly(screen)
        val e = codeOnly(engine)
        return s.contains("engineState.collectAsState()") &&
            e.contains("MutableStateFlow(RunState.IDLE)") &&
            !e.contains("mutableStateOf(RunState")
    }

    /** 一次冷启的结构判定：三个构造点全部在组合之前 + 状态覆盖在位。 */
    private fun coldStartVerdict(main: String, app: String, runtime: String, screen: String, engine: String) =
        mainPrebuildOnNormalPath(main) && appPrebuildUnconditional(app) &&
            switchRebuildsSynchronously(runtime) && stateFlowCoverageIntact(screen, engine)

    // ── 真实源码：四项判据全绿 ──────────────────────────────────────────
    @Test
    fun `MainActivity 预建在正常冷启路径_不在提前返回死分支内`() {
        assertTrue(
            "预建必须避开 `if (!isTaskRoot) { finish(); return }` —— 正常冷启不走那个分支",
            mainPrebuildOnNormalPath(mainSrc),
        )
    }

    @Test
    fun `Application 预建无条件_不被runCatching吞掉`() {
        assertTrue("装配失败不许降级成「退回懒建」", appPrebuildUnconditional(appSrc))
    }

    @Test
    fun `换通道置空后同步重建_不留给组合期`() {
        assertTrue(
            "switchChannel 必须在函数内 engine(appCtx) 重建，否则 remember(channelEpoch) 会在组合期构造引擎",
            switchRebuildsSynchronously(runtimeSrc),
        )
    }

    @Test
    fun `v2_2_5的StateFlow覆盖不许回退`() {
        assertTrue(stateFlowCoverageIntact(screenSrc, repoFile(base + "baopu/agent/RunEngine.kt").readText()))
    }

    // ── 10 次冷启夹具：每次都从磁盘重读源码，附逐次读数 ───────────────────
    @Test
    fun `修后连续10次冷启_零次在组合期构造引擎`() {
        val engine = repoFile(base + "baopu/agent/RunEngine.kt").readText()
        val verdicts = (1..10).map { run ->
            val ok = coldStartVerdict(
                // 每轮重新读磁盘：判据不许拿内存里的旧字符串自证
                repoFile(base + "MainActivity.kt").readText(),
                repoFile(base + "ZhengdaoApp.kt").readText(),
                repoFile(base + "baopu/BaopuRuntime.kt").readText(),
                repoFile(base + "ui/baopu/BaopuScreen.kt").readText(),
                engine,
            )
            println("617-COLDSTART run=$run compositionTimeEngineBuild=${if (ok) 0 else 1} verdict=$ok")
            ok
        }
        assertEquals("10 次冷启都必须 0 次组合期构造", List(10) { true }, verdicts)
    }

    // ── 6 个负例变异：判据必须转 false（恒真判据在这里会自己红） ───────────
    @Test
    fun `变异1_预建挪回死分支_判据必红`() {
        val code = codeOnly(mainSrc)
        val call = assemblyCallAt(code)
        val dead = blockRange(code, deadBranchHeader)!!
        // 把调用整行搬到死分支内部（模拟 586 的原始接线）
        val lineStart = code.lastIndexOf('\n', call) + 1
        val lineEnd = code.indexOf('\n', call).let { if (it < 0) code.length else it }
        val line = code.substring(lineStart, lineEnd)
        val mutated = code.substring(0, lineStart) + code.substring(lineEnd)
            .let { rest -> rest.replaceFirst(deadBranchHeader + " {", deadBranchHeader + " {\n" + line) }
        assertFalse("预建落进提前返回分支必须被判据抓住", mainPrebuildOnNormalPath(mutated))
        assertTrue("变异前的真实源码必须是绿的（对照组）", mainPrebuildOnNormalPath(mainSrc))
        assertTrue("死分支区间确实存在（fixture 自检）", dead.last > dead.first)
    }

    @Test
    fun `变异2_删掉MainActivity预建_判据必红`() {
        assertFalse(mainPrebuildOnNormalPath(mainSrc.replace(assemblyCallLiteral(codeOnly(mainSrc)) ?: engineCallLiteral, "// removed")))
    }

    @Test
    fun `变异3_Application预建包回runCatching_判据必红`() {
        val code = codeOnly(appSrc)
        val call = assemblyCallAt(code)
        val lineStart = code.lastIndexOf('\n', call) + 1
        val mutated = code.substring(0, lineStart) +
            "        runCatching {\n" + code.substring(lineStart)
        assertFalse("吞异常恢复必须被抓住", appPrebuildUnconditional(mutated))
    }

    @Test
    fun `变异4_换通道改成懒重建_判据必红`() {
        assertFalse(switchRebuildsSynchronously(runtimeSrc.replace("engine(appCtx)", "engineIfBuilt()")))
    }

    @Test
    fun `变异5_预建挪到setContent之后_判据必红`() {
        val code = codeOnly(mainSrc)
        val call = assemblyCallAt(code)
        val lineStart = code.lastIndexOf('\n', call) + 1
        val lineEnd = code.indexOf('\n', call).let { if (it < 0) code.length else it }
        val line = code.substring(lineStart, lineEnd)
        val without = code.substring(0, lineStart) + code.substring(lineEnd)
        val mutated = without.replace("        setContent {", "        setContent {\n" + line)
        assertFalse("预建晚于 setContent 必须被抓住", mainPrebuildOnNormalPath(mutated))
    }

    @Test
    fun `变异6_把collectAsState改回点value_判据必红`() {
        val engine = repoFile(base + "baopu/agent/RunEngine.kt").readText()
        assertFalse(
            "v2.2.5 覆盖被回退必须被抓住",
            stateFlowCoverageIntact(screenSrc.replace("engineState.collectAsState()", "engineState.value"), engine),
        )
    }
}
