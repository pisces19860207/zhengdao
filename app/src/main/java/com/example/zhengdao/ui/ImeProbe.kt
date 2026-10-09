package com.example.zhengdao.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 输入回归页的**纯逻辑**（不依赖 Android，可单测）。
 *
 * 为什么要有这一页：Termux 的 `TerminalView` 把 IME 的 composition 事件自己合成上屏
 * （见 `com/termux/view/TerminalView.java` 的 `onCreateInputConnection`），社区反复报告过
 * 「compositionend 阶段重复插入 / 丢首字符」两类事故。中文输入法是本项目的**高风险持续投入项**，
 * 所以 APK 内置这一页：进入 → 按提示输入 → 一键读终端缓冲判定，30 秒回归一轮。
 *
 * 判定口径只有一条：**终端缓冲最后一行**（回归会话跑的是 `cat`，没有提示符，所以最后一行就是
 * 刚敲进去的那串字）与期望串逐字比对；同时把 IME 真实送进来的事件流留档。
 */
object ImeProbe {

    /** 三个验收场景（设计方案 v3 §3「IME 验收细化」+ Issue #2 验收条件）。 */
    data class Scenario(
        val id: String,
        val index: Int,
        val title: String,
        /** 给测试者看的操作脚本。 */
        val script: String,
        /** 判定用的期望文本。 */
        val expected: String,
    )

    val SCENARIOS: List<Scenario> = listOf(
        Scenario(
            id = "compose",
            index = 1,
            title = "中文组合输入（候选窗位置）",
            script = "点一下终端唤起输入法，输入「你好」。候选窗应贴着终端底部；上屏应为完整的「你好」。",
            expected = "你好",
        ),
        Scenario(
            id = "delete",
            index = 2,
            title = "词中删字符后重输",
            script = "先输入「你好吗」，把光标移到「好」后面删掉「吗」，再重新输入「吗」。不应丢首字符、不应重复。",
            expected = "你好吗",
        ),
        Scenario(
            id = "rapid",
            index = 3,
            title = "快速连续输入多词",
            script = "不做停顿，快速连打「你好世界测试」六个字（中途不要看候选窗）。",
            expected = "你好世界测试",
        ),
    )

    /** 事件日志上限：够复盘一次输入，又不至于把内存撑起来。 */
    const val MAX_EVENTS = 240

    private val events = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 记录一条 IME 事件（由 `TerminalView.mImeProbeObserver` 回调，可能在任意线程）。 */
    @Synchronized
    fun record(kind: String, text: String) {
        val safe = text.replace("\n", "\\n").replace("\r", "\\r")
        events.addLast("${clock.format(Date())} $kind ${safe.take(120)}")
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    @Synchronized
    fun clear() {
        events.clear()
    }

    @Synchronized
    fun snapshot(): List<String> = events.toList()

    /**
     * 疑似重复上屏：相邻两次 `commitText` 内容完全相同。
     *
     * 这正是 compositionend 阶段重复插入的典型现场（上游 fix 提交
     * `8e629b9f38 flush committed IME text on compositionend` 就是治这个）。
     */
    fun duplicateCommits(lines: List<String>): List<String> {
        val commits = lines.filter { it.contains(" commitText ") }
            .map { it.substringAfter(" commitText ").trim() }
            .filter { it.isNotEmpty() }
        return commits.zipWithNext().filter { (a, b) -> a == b }.map { it.first }
    }

    /** 比对时忽略空白（终端里可能有换行/尾随空格）。 */
    fun normalize(s: String): String = s.filterNot { it.isWhitespace() }

    enum class Status { PASS, FAIL, EMPTY }

    data class Verdict(val status: Status, val detail: String)

    /** 拿期望串与「终端缓冲最后一行」做判定。 */
    fun verdict(expected: String, typed: String): Verdict {
        val got = normalize(typed)
        val want = normalize(expected)
        if (got.isEmpty()) return Verdict(Status.EMPTY, "还没读到内容——先在终端里把这一条敲完再点判定")
        if (got == want) return Verdict(Status.PASS, "上屏内容与期望一致")
        if (got == want + want) return Verdict(Status.FAIL, "疑似重复上屏（同一串被送了两遍）")
        if (want.startsWith(got)) return Verdict(Status.FAIL, "少字：只收到「$got」，期望「$want」")
        if (got.startsWith(want)) return Verdict(Status.FAIL, "多字：收到「$got」，期望「$want」")
        if (got.length == want.length) return Verdict(Status.FAIL, "串字：收到「$got」，期望「$want」")
        return Verdict(Status.FAIL, "不符：收到「$got」，期望「$want」")
    }

    /** 终端缓冲里最后一行非空文本（回归会话是 `cat`，所以这就是刚输入的那一行）。 */
    fun lastNonEmptyLine(transcript: String): String =
        transcript.lines().asReversed().firstOrNull { it.isNotBlank() }?.trim() ?: ""
}
