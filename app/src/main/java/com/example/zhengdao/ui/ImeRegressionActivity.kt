package com.example.zhengdao.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 输入回归页（Issue #2 / 设计方案 v3 §3）：进 30 秒回归 IME + 渲染 + 快捷键条。
 *
 * 与生产终端的**差别只在会话内容**：这里跑 `/system/bin/cat`（没有提示符、没有 proot），
 * 所以「终端缓冲最后一行」就等于刚敲进去的那串字，判定不需要解析提示符；而 IME 走的是
 * **同一个 `TerminalView` 的 `onCreateInputConnection`**（vendored 源码，见 `TerminalView.java`），
 * 输入法行为与生产完全一致——没装运行环境时也能回归。
 *
 * 三个场景与判定口径见 [ImeProbe]。
 */
class ImeRegressionActivity : ComponentActivity(), TerminalViewClient, TerminalSessionClient {

    private lateinit var termView: TerminalView
    private var session: TerminalSession? = null
    /** 自检的起跑时间 / 起跑就失败的原因（见 [beginImeSelfCheck] 与 [pollImeSelfCheck]）。 */
    private var selfCheckStartedAt = 0L
    private var selfCheckError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val ready = remember { mutableStateOf(0) }
                val verdicts = remember { mutableStateMapOf<String, ImeProbe.Verdict>() }
                val typedLines = remember { mutableStateMapOf<String, String>() }
                var logLines by remember { mutableStateOf(ImeProbe.snapshot()) }
                var mountTick by remember { mutableStateOf(0) }
                LaunchedEffect(mountTick) {
                    while (true) {
                        logLines = ImeProbe.snapshot()
                        delay(700)
                    }
                }
                ImeRegressionScreen(
                    verdicts = verdicts,
                    typedLines = typedLines,
                    logLines = logLines,
                    onMount = { mountTick++ },
                    onJudge = { scenario -> judge(scenario, verdicts, typedLines) },
                    onRestart = {
                        ImeProbe.clear()
                        verdicts.clear()
                        typedLines.clear()
                        logLines = emptyList()
                        restartProbeSession()
                    },
                    onCopy = { copyLog(logLines) },
                    onSelfCheck = {
                        // 异步等回显：主线程让出去，终端的输出处理才推得动（见 beginImeSelfCheck）。
                        beginImeSelfCheck()
                        var verdict: ImeProbe.Verdict? = null
                        while (verdict == null) {
                            delay(120)
                            verdict = pollImeSelfCheck()
                        }
                        verdict
                    },
                )
            }
        }
    }

    // ── 判定 / 会话 ────────────────────────────────────────────────────────────

    private fun judge(
        scenario: ImeProbe.Scenario,
        verdicts: MutableMap<String, ImeProbe.Verdict>,
        typedLines: MutableMap<String, String>,
    ) {
        val transcript = termView.mEmulator?.screen?.transcriptText ?: ""
        val line = ImeProbe.lastNonEmptyLine(transcript)
        typedLines[scenario.id] = line
        verdicts[scenario.id] = ImeProbe.verdict(scenario.expected, line)
    }

    /**
     * 重开探针会话：换一屏干净的缓冲。
     *
     * 每个场景都从空屏开始，判定才稳定 —— 上一场景的组合串残留、`cat` 回显、退格留下的擦除
     * 空格都会污染「最后一行」（真机连跑踩过：缓冲里混出「你好吗你好」「你好你好吗」这种假现场，
     * 想按「缓冲增量」绕开又会被终端重排骗到）。
     */
    internal fun restartProbeSession() {
        session?.finishIfRunning()
        termView.attachSession(newCatSession())
    }

    /**
     * 供 Compose 的 AndroidView 工厂调用：认领这个 [TerminalView] 并挂上 `cat` 探针会话。
     *
     * 视图必须先经过 `TerminalPrefs.applyTo`（渲染器/字号），否则 `attachSession` 会空指针。
     */
    internal fun attachProbeSession(view: TerminalView) {
        termView = view
        view.attachSession(newCatSession())
    }

    /** 探针会话是否已挂上（Compose 的 AndroidView 工厂是异步组合出来的，测试要等它）。 */
    internal fun isTerminalReady(): Boolean {
        if (!this::termView.isInitialized) return false
        val emu = termView.mEmulator ?: return false
        // 光有 emulator 还不够：会话是在 Compose 工厂里挂上的，那时视图还是 0×0，终端网格也是
        // 0 行 0 列，此后往会话里写什么都不可能有回显（真机自检踩过：会话在跑、回显为空）。
        return emu.mRows > 0 && emu.mColumns > 0
    }

    /** 自检 = 跑第一个场景（中文组合输入）；其余场景由页面按钮/仪器测试逐个跑 [beginScenario]。 */
    internal fun beginImeSelfCheck() = beginScenario(ImeProbe.SCENARIOS.first().id)

    /** 自检/场景脚本：起跑即失败的原因，以及这一轮期望的上屏文本。 */
    private var selfCheckExpected = "你好"

    /**
     * **场景脚本第一步**：绕过系统输入法，按 [ImeProbe.SCENARIOS] 里该场景的脚本驱动
     * [TerminalView] 的 `InputConnection`（真正的中文输入法上屏也是走这几个调用）。
     * 只发不等、立刻返回，结果用 [pollScenario] 轮着看。
     *
     * 覆盖的是「InputConnection 层往下的整条路径」：composition 处理 → `sendTextToTerminal` →
     * PTY 回显 → 缓冲读取。系统输入法自己的组合行为（候选窗位置、联想时序）不在脚本范围内，
     * 那部分靠页面上的手工场景卡。
     *
     * ⚠️ **绝不能在这里 sleep 轮询**：终端的输出处理要回到主线程才推进，主线程一堵，PTY 回显
     * 就永远等不到（真机踩过：自检里直接写 PTY 也不回显，一放开主线程整行字立刻出现）。
     */
    internal fun beginScenario(id: String) {
        val sc = ImeProbe.SCENARIOS.firstOrNull { it.id == id }
        selfCheckStartedAt = System.currentTimeMillis()
        selfCheckError = null
        if (sc == null) {
            selfCheckError = "未知场景 $id"
            return
        }
        selfCheckExpected = sc.expected
        if (!this::termView.isInitialized) {
            selfCheckError = "终端视图还没挂上"
            return
        }
        // 先换一屏干净缓冲，再跑脚本：判定读的就是这一屏的最后一行。
        restartProbeSession()
        if (!isTerminalReady()) {
            selfCheckError = "终端还没准备好（会话未挂上）"
            return
        }
        val s = session
        if (s == null) {
            selfCheckError = "探针会话不存在"
            return
        }
        val ic = termView.onCreateInputConnection(android.view.inputmethod.EditorInfo())
        if (ic == null) {
            selfCheckError = "onCreateInputConnection 返回 null"
            return
        }
        ImeProbe.record("scenario", id)
        deletePhase = 0
        when (id) {
            // ① 组合输入：setComposingText 建组合串，commitText 上屏，finishComposingText 收尾。
            "compose" -> {
                ic.setComposingText("你好", 1)
                ic.commitText("你好", 1)
                ic.finishComposingText()
            }
            // ② 词中删字符后重输：先上屏整词「你好吗」，退掉「吗」、等擦除回显，再重新上屏「吗」。
            // 分段走的原因见 [advanceDeletePhase]。
            "delete" -> {
                ic.commitText("你好吗", 1)
                deletePhase = 1
            }
            // ③ 快速连续输入多词：连打三段、中间不停顿。
            "rapid" -> {
                ic.commitText("你好", 1)
                ic.commitText("世界", 1)
                ic.commitText("测试", 1)
            }
        }
    }

    /** 「词中删字符后重输」走到了第几段（1=等整词落下，2=等擦除回显，3=已重输）。 */
    private var deletePhase = 0
    private var deletePhaseAt = 0L

    private fun transcriptText(): String = termView.mEmulator?.screen?.transcriptText ?: ""

    /**
     * 「词中删字符后重输」必须**分段**：退格是当作按键异步送进 PTY 的，一口气把「退格 + 重输」
     * 发完，真机上重输的字可能先落进缓冲、退格随后才到（第一次跑三场景就是这么挂的：缓冲里
     * 出现「你好吗 你好」，末两字是上一段重输的内容）。分段后每段都等上一段的回显，顺序稳定。
     *
     * 返回 true = 这一段刚发出去（调用方继续轮询，不要下结论）。
     */
    private fun advanceDeletePhase(line: String): Boolean = when (deletePhase) {
        // 整词落下后再退格。
        1 -> if (line.startsWith("你好吗")) {
            commitThroughInputConnection { it.deleteSurroundingText(1, 0) }
            deletePhase = 2
            deletePhaseAt = System.currentTimeMillis()
            true
        } else false

        // 退格的回显（擦除空格/光标回退）需要一点时间：等一小会儿再重输，别和退格抢顺序。
        2 -> if (System.currentTimeMillis() - deletePhaseAt >= 300) {
            commitThroughInputConnection { it.commitText("吗", 1) }
            deletePhase = 3
            true
        } else false

        else -> false
    }

    /** 拿一个当前输入连接做一次动作（每次现取，避免拿着过期的连接发键）。 */
    private fun commitThroughInputConnection(block: (android.view.inputmethod.InputConnection) -> Unit) {
        val ic = termView.onCreateInputConnection(android.view.inputmethod.EditorInfo()) ?: return
        block(ic)
    }

    /**
     * **场景脚本第二步**：非阻塞地看一眼终端缓冲。
     *
     * 返回 `null` = 还没看见（调用方 `delay`/`sleep` 后再问一次，主线程必须让出去）；
     * 返回非 null = 最终结论（[timeoutMs] 内一直没出现就判负，附分诊信息）。
     */
    internal fun pollScenario(id: String, timeoutMs: Long = 8_000): ImeProbe.Verdict? {
        selfCheckError?.let { return ImeProbe.Verdict(ImeProbe.Status.FAIL, it) }
        // 每个场景都是从空屏开始的（见 [restartProbeSession]），所以判定就是看这一屏的最后一行
        // ——和页面上的手工「判定」按钮同一口径，人眼看到的和脚本看到的一致。
        val line = ImeProbe.lastNonEmptyLine(transcriptText())
        // 分段场景：先把下一段发出去再继续轮询，不要在这一刻下结论。
        if (id == "delete" && advanceDeletePhase(line)) return null
        val verdict = ImeProbe.verdict(selfCheckExpected, line)
        if (verdict.status == ImeProbe.Status.PASS) return verdict
        val started = if (selfCheckStartedAt == 0L) System.currentTimeMillis() else selfCheckStartedAt
        if (System.currentTimeMillis() - started < timeoutMs) return null
        return ImeProbe.Verdict(verdict.status, verdict.detail + "｜场景=$id｜" + diagnoseImeGap())
    }

    internal fun pollImeSelfCheck(timeoutMs: Long = 8_000): ImeProbe.Verdict? =
        pollScenario(ImeProbe.SCENARIOS.first().id, timeoutMs)

    /**
     * 自检失败时的分诊：是「会话/回显这一层就没通」还是「只有 InputConnection 这一层没通」。
     * 直接往 PTY 写一个标记，看得见就说明终端本身是好的（问题在 IME 那条路），看不见就是会话没跑。
     */
    private fun diagnoseImeGap(): String {
        val s = session
        val running = s?.isRunning == true
        val marker = "ZDPTY"
        s?.write(marker + "\r")
        val raw = (termView.mEmulator?.screen?.transcriptText ?: "").replace("\n", "\\n").take(120)
        val grid = "${termView.mEmulator?.mRows}×${termView.mEmulator?.mColumns}"
        val echoed = raw.contains(marker)
        return "会话在跑=$running，分诊标记已写入=$echoed，网格=$grid，视图=${termView.width}×${termView.height}，缓冲原文=[$raw]"
    }

    private fun newCatSession(): TerminalSession {
        val s = TerminalSession(
            "/system/bin/cat",
            filesDir.absolutePath,
            arrayOf("/system/bin/cat"),
            arrayOf("TERM=xterm-256color", "HOME=/", "LANG=C.UTF-8"),
            1000,
            this,
        )
        session = s
        return s
    }

    private fun copyLog(lines: List<String>) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("zd-ime-probe", lines.joinToString("\n")))
    }

    private fun showKeyboard() {
        // 与生产终端一致：TerminalView 不是 EditText，必须先抢焦点再要求弹键盘，
        // 否则 showSoftInput 拿不到输入连接、PC 侧 adb 注入的按键也没人接（真机踩过）。
        termView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(termView, InputMethodManager.SHOW_IMPLICIT)
    }

    // ── TerminalViewClient ────────────────────────────────────────────────────

    override fun onScale(scale: Float): Float = 1f  // 回归页不做捏合缩放，避免和判定抢手势

    override fun onSingleTapUp(e: MotionEvent) = showKeyboard()

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    /** 与生产终端一致：中文 IME 组合输入需要字符级（见 TerminalActivity）。 */
    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {}

    // ── TerminalSessionClient ─────────────────────────────────────────────────

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession === session) runOnUiThread { termView.onScreenUpdated() }
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        Log.i(TAG, "回归会话结束")
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("zd-ime-probe", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {}

    override fun onBell(session: TerminalSession) {}

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    /** 与生产终端一致的光标样式（TUI 应用运行时会用自己的转义序列覆盖，属正常）。 */
    override fun getTerminalCursorStyle(): Int = com.termux.terminal.TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "stack", e)
    }

    companion object {
        private const val TAG = "ZD-IME-PROBE"
    }
}

@Composable
private fun ImeRegressionScreen(
    verdicts: Map<String, ImeProbe.Verdict>,
    typedLines: Map<String, String>,
    logLines: List<String>,
    onMount: () -> Unit,
    onJudge: (ImeProbe.Scenario) -> Unit,
    onRestart: () -> Unit,
    onCopy: () -> Unit,
    onSelfCheck: suspend () -> ImeProbe.Verdict,
) {
    var selfCheck by remember { mutableStateOf<ImeProbe.Verdict?>(null) }
    var selfCheckRunning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("输入回归页", style = MaterialTheme.typography.titleLarge)
        Text(
            "进 30 秒回归 IME：下面这块就是终端（跑 cat，无提示符），点它唤起输入法；" +
                "按顺序做完三个场景，各自点「判定」——读的是终端缓冲最后一行。",
            style = MaterialTheme.typography.bodySmall,
        )

        TerminalProbeHost(onMount = onMount)

        ImeProbe.SCENARIOS.forEach { scenario ->
            ScenarioCard(
                scenario = scenario,
                verdict = verdicts[scenario.id],
                typed = typedLines[scenario.id],
                onJudge = { onJudge(scenario) },
            )
        }

        val dup = ImeProbe.duplicateCommits(logLines)
        if (dup.isNotEmpty()) {
            Text(
                "⚠️ 疑似重复上屏：commitText 连续送了同一串 ${dup.joinToString(" / ")}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRestart) { Text("重开会话") }
            OutlinedButton(onClick = onCopy) { Text("复制事件日志") }
            OutlinedButton(
                enabled = !selfCheckRunning,
                onClick = {
                    selfCheckRunning = true
                    selfCheck = null
                    scope.launch {
                        selfCheck = onSelfCheck()
                        selfCheckRunning = false
                    }
                },
            ) { Text(if (selfCheckRunning) "自检中…" else "自检（模拟输入法上屏）") }
        }
        selfCheck?.let {
            val mark = when (it.status) {
                ImeProbe.Status.PASS -> "✓ 通过"
                ImeProbe.Status.FAIL -> "✗ 不通过"
                ImeProbe.Status.EMPTY -> "· 未就绪"
            }
            Text(
                "自检 $mark — ${it.detail}（绕过系统输入法，直接走 InputConnection；" +
                    "覆盖 composition→上屏→回显→读缓冲这条链）",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text("IME 事件日志（${logLines.size} 条）", style = MaterialTheme.typography.titleSmall)
        Text(
            if (logLines.isEmpty()) "（还没有事件——点终端唤起输入法后开始输入）"
            else logLines.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ScenarioCard(
    scenario: ImeProbe.Scenario,
    verdict: ImeProbe.Verdict?,
    typed: String?,
    onJudge: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${scenario.index}. ${scenario.title}", style = MaterialTheme.typography.titleSmall)
            Text(scenario.script, style = MaterialTheme.typography.bodySmall)
            Text("期望上屏：${scenario.expected}", style = MaterialTheme.typography.bodySmall)
            typed?.let { Text("实测读取：$it", style = MaterialTheme.typography.bodySmall) }
            verdict?.let {
                val mark = when (it.status) {
                    ImeProbe.Status.PASS -> "✓ 通过"
                    ImeProbe.Status.FAIL -> "✗ 不通过"
                    ImeProbe.Status.EMPTY -> "· 待输入"
                }
                Text("$mark — ${it.detail}", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onJudge) { Text("判定") }
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun TerminalProbeHost(onMount: () -> Unit) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ImeRegressionActivity
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        factory = {
            val host = FrameLayout(activity)
            val tv = TerminalView(activity, null)
            // ⚠️ 这三步的顺序都是约束：
            //   1) setTerminalViewClient 必须先于 updateSize——TerminalView 的构造器**不再**从
            //      context 里取 client（vendored 版改成显式注入），漏掉会在 updateSize 的
            //      mClient.onEmulatorSet() 处空指针（真机 Crash 验证过）。
            //   2) applyTo 必须先于 attachSession（mRenderer 只在 setTextSize 里建）。
            tv.setTerminalViewClient(activity)
            com.example.zhengdao.terminal.TerminalPrefs.applyTo(tv, host, activity)
            tv.mImeProbeObserver = TerminalView.ImeProbeObserver { kind, text ->
            ImeProbe.record(kind, text)
            // 同一份事件流也打一份到 logcat：仪器测试失败时能直接看到「谁在什么时候写了什么」。
            Log.i("ZD-IME-EVENT", "$kind|$text")
        }
            activity.attachProbeSession(tv)
            // TerminalView 自己只看手势、不抢焦点（生产页是 TerminalActivity 显式 requestFocus）。
            // 回归页必须补上：焦点不在它身上时，软键盘和 PC 侧 adb 注入的按键都到不了终端。
            tv.isFocusable = true
            tv.isFocusableInTouchMode = true
            tv.post { tv.requestFocus() }
            host.addView(
                tv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            onMount()
            host
        },
    )
}
