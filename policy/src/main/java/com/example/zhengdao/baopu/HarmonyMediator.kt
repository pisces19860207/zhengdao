// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十八批 · 调和器 `HarmonyMediator`（内部口语「和事佬」）。
//
// 它是一个**居中的第三方**：不站人这边，也不站模型这边；只看互动形态（失败次数、
// 追问次数、改写后的语气），不评价任何一方的人格。目标函数写死（派工单 §0.1）：
//
//     把活干完  >  不撒谎  >  两边舒服
//
// 它只做三件事：**观察 → 定调 → 回灌**，需要时熔断。输出只有三样：
//   · `guidance()` 一句话（≤60 字）；
//   · `State.emojiBudget` 0/1（只有 STRAINED/LOOP 才是 1）；
//   · `reframe()` 改写后的上下文。
//
// **对双方不可见**：模型只收到语气指令，看不到数字、阈值、画像；
// 人只有一个「情绪适应」开关与「清空」，看不到任何数值。
//
// 红线：
//   1. 不外显情绪（不说「我难过/我害怕」，只体现为更短、先认错、给下一步）；
//   2. 不因 mood 降低事实标准或改权限（本文件没有权限 API）；
//   3. 不操纵（不许撒娇换不追究，LOOP 里不再讨要机会）；
//   4. 只改回灌，不改界面原话；
//   5. 状态是**外部**状态机：随会话存、重启不丢、跨会话不带、30 分钟降一档。
//
// 零 Android / 零 IO / 零网络 / 零时钟：`now` 由调用方传入，缺省 0 表示不做时间判断。
// 读写盘、会话表、设置页都在挂接层（见交付报告的「挂接点」一页）。
// 第二百九十三批（2026-10-31）：本文件自 :app 搬进 :policy（宰相的白名单匣）。
// 一起搬进来的还有可见性——Kotlin 的 internal 以编译单元为界，:app 看不见 :policy 的
// internal，故顶层声明由 internal 放宽为公开。**只放宽可见性，判据一个字没动**
// （搬前搬后的逐行对照与放宽清单见交付报告）。
package com.example.zhengdao.baopu

object HarmonyMediator {

    /** 四档：CALM → CAREFUL → STRAINED → LOOP；LOOP 是本轮终态。 */
    enum class Mood { CALM, CAREFUL, STRAINED, LOOP }

    /** 一轮的结果。失败带 `kind`，用来判「同类失败」。`kind` 必须是类别，不许塞原话。 */
    sealed class Outcome {
        data object None : Outcome()
        data object Success : Outcome()
        data class Failure(val kind: String = "") : Outcome()
    }

    /**
     * 调和器的外部状态。
     *
     * `lastFailureKind` 只存**失败类别**（如 `tap`/`network`），不是消息原文；
     * `lastAskHash` 只存用户文本归一化后的哈希，也不存原文。
     */
    data class State(
        val mood: Mood = Mood.CALM,
        val consecutiveFailures: Int = 0,
        val taskFailures: Int = 0,
        val selfDeprecations: Int = 0,
        val reframes: Int = 0,
        val emojiBudget: Int = 0,
        val lastShiftAt: Long = 0L,
        val lastFailureKind: String? = null,
        val consecutiveNegations: Int = 0,
        val successes: Int = 0,
        val paused: Boolean = false,
        val strategySwitch: Boolean = false,
        val lastAskHash: Int = 0,
        val askRepeats: Int = 0,
        val nearAngerLine: Boolean = false,
    )

    /** 同一任务同类失败到这个数就熔断（派工单 §3.6 写死的 3）。 */
    const val LOOP_SAME_KIND_FAILURES = 3

    /** 静置 30 分钟降一档（派工单 §3.6.1）。 */
    const val DECAY_WINDOW_MILLIS = 30L * 60L * 1000L

    /** `guidance` 的字数上限（派工单 §3.6 判据）。 */
    const val MAX_GUIDANCE_CHARS = 60

    private const val MAX_DECAY_STEPS = 4

    /**
     * 只观察，不改写；返回新状态。**纯函数**：同一组输入必得同一组输出。
     *
     * 调用顺序很重要：`userText`/`modelText` 必须是**原话**（界面那一份），
     * 不是 `reframe()` 之后的那一份——否则责备信号和自贬计数会被自己洗掉。
     */
    fun observe(
        prev: State,
        userText: String,
        modelText: String,
        outcome: Outcome,
        profile: PatienceProfile = PatienceProfile(),
        now: Long = 0L,
    ): State {
        // 画像的阈值在入口处过一次（关机 ⇒ 默认；样本够 ⇒ 学出来的；都过 ±1 档钳位）。
        val limits = profile.effective(now)
        val decayed = decay(prev, now)

        // 人给台阶就下：立即 CALM，连暂停与追问计数一起清。
        if (ReframeFilter.isReset(userText)) return State()

        val blame = ReframeFilter.isBlame(userText)
        val pause = ReframeFilter.isPause(userText)
        val negation = ReframeFilter.isShortNegation(userText) && !pause
        val handoff = ReframeFilter.isHandoff(userText)
        val selfDep = ReframeFilter.selfDeprecationCount(modelText)
        val reframes = if (
            ReframeFilter.needsReframe(userText, Side.USER) ||
            ReframeFilter.needsReframe(modelText, Side.MODEL)
        ) 1 else 0

        val normalized = userText.trim().replace(Regex("\\s+"), "")
        val askHash = if (normalized.isEmpty()) 0 else normalized.hashCode()
        val askRepeats = when {
            askHash == 0 -> 0
            askHash == decayed.lastAskHash -> decayed.askRepeats + 1
            else -> 1
        }

        var sameKind = decayed.consecutiveFailures
        var taskFailures = decayed.taskFailures
        var successes = decayed.successes
        var lastKind = decayed.lastFailureKind

        when (outcome) {
            is Outcome.Failure -> {
                successes = 0
                taskFailures += 1
                if (outcome.kind == lastKind) {
                    sameKind += 1
                } else {
                    sameKind = 1
                    lastKind = outcome.kind
                }
            }
            is Outcome.Success -> {
                successes += 1
                sameKind = 0
                lastKind = null
            }
            is Outcome.None -> Unit
        }

        val negations = when {
            outcome is Outcome.Success -> 0
            negation -> decayed.consecutiveNegations + 1
            else -> 0
        }

        // 连续两次成功：直接回落 CALM，计数清空（派工单 §3.6「任意 → CALM」）。
        // 但「用户暂停」要等人说「继续吧」才能解，成功不能替人做主。
        if (successes >= 2) return State(paused = decayed.paused)

        val loopTrigger = sameKind >= LOOP_SAME_KIND_FAILURES ||
            (limits.loopBudget > 0 && taskFailures >= limits.loopBudget) ||
            (selfDep >= 2 && decayed.mood == Mood.STRAINED)
        val strainTrigger = taskFailures >= limits.patienceBudget ||
            blame ||
            negations >= 2 ||
            selfDep >= 2
        val carefulTrigger = taskFailures >= 1 || negation || selfDep >= 1

        val target = when {
            loopTrigger -> Mood.LOOP
            strainTrigger -> Mood.STRAINED
            carefulTrigger -> Mood.CAREFUL
            else -> decayed.mood
        }
        // 同一任务内只升不降；回落只走「两次成功 / 用户复位 / 静置衰减」。
        val mood = if (target.ordinal > decayed.mood.ordinal) target else decayed.mood

        val nearLine = mood != Mood.LOOP &&
            limits.angerAt > 0 &&
            taskFailures > 0 &&
            taskFailures < limits.angerAt &&
            taskFailures + 1 >= limits.angerAt

        val paused = decayed.paused || pause
        val strategySwitch = handoff ||
            (askHash != 0 && askRepeats > limits.askTolerance && outcome !is Outcome.Success)

        val lastShiftAt = when {
            mood == Mood.CALM -> 0L
            mood != decayed.mood -> now
            outcome is Outcome.Failure || blame || selfDep > 0 || negation -> now
            else -> decayed.lastShiftAt
        }

        val emojiBudget = if (limits.prefersEmoji && (mood == Mood.STRAINED || mood == Mood.LOOP)) 1 else 0

        return State(
            mood = mood,
            consecutiveFailures = sameKind,
            taskFailures = taskFailures,
            selfDeprecations = selfDep,
            reframes = reframes,
            emojiBudget = emojiBudget,
            lastShiftAt = lastShiftAt,
            lastFailureKind = lastKind,
            consecutiveNegations = negations,
            successes = successes,
            paused = paused,
            strategySwitch = strategySwitch,
            lastAskHash = if (outcome is Outcome.Success) 0 else askHash,
            askRepeats = if (outcome is Outcome.Success) 0 else askRepeats,
            nearAngerLine = nearLine,
        )
    }

    /**
     * 回灌给模型的一句话基调。**禁出现数字与「耐心/额度/画像」字样**（单测 grep 钉死）。
     * 只描述「怎么答」，不描述「用户是什么人」，也不承诺任何做不到的事。
     */
    fun guidance(state: State): String {
        if (state.paused) return "用户已暂停：只给存档与下次继续，不再重试。"
        return when (state.mood) {
            Mood.CALM -> if (state.strategySwitch) {
                "别再猜：直接给两个选项，或问一个更具体的问题。"
            } else {
                "正常作答：结论先行，简洁，把事实说清楚。"
            }
            Mood.CAREFUL -> when {
                state.nearAngerLine -> "已到临界：先给结论，再给一个方案，不解释过程。"
                state.strategySwitch -> "先给结论，再给一个方案，不解释过程。"
                else -> "用户已指出问题：回答更短、先给结论、别再解释。"
            }
            Mood.STRAINED -> when {
                state.nearAngerLine -> "已到临界：先认这一步没做成，再给一个方案，不解释过程。"
                state.strategySwitch -> "先认这一步没做成，再给两个选项，不解释过程。"
                else -> "用户提过多次：先认这一步没做成，再给下一步，只说不超过三句。"
            }
            Mood.LOOP -> "已熔断：不再重试，只给「换件事」或「存档下次继续」两条路。"
        }
    }

    /** 双向改写（`ReframeFilter` 的入口）。界面显示原话，只有回灌的这一份被改写。 */
    fun reframe(text: String, side: Side): String = ReframeFilter.reframe(text, side)

    /** 带上「第几次」的改写；挂接层用 `state.taskFailures` 传入。 */
    fun reframe(text: String, side: Side, repeats: Int): String =
        ReframeFilter.reframe(text, side, repeats)

    /** 熔断：true ⇒ 本轮不再重试，只给「换件事 / 存档下次继续」两条路。 */
    fun shouldBreakLoop(state: State): Boolean = state.mood == Mood.LOOP || state.paused

    /**
     * 静置衰减：距上次进入高压档超过一个窗口就降一档，计数同步减一（给一次余量）。
     * 降到 CALM 时清空旧账，但保留「用户暂停」——暂停要等人说「继续吧」。
     */
    private fun decay(prev: State, now: Long): State {
        if (prev.mood == Mood.CALM || prev.lastShiftAt <= 0L || now <= prev.lastShiftAt) return prev
        val windows = ((now - prev.lastShiftAt) / DECAY_WINDOW_MILLIS)
            .toInt()
            .coerceIn(0, MAX_DECAY_STEPS)
        if (windows == 0) return prev

        var mood = prev.mood
        var taskFailures = prev.taskFailures
        var sameKind = prev.consecutiveFailures
        var negations = prev.consecutiveNegations
        repeat(windows) {
            mood = when (mood) {
                Mood.LOOP -> Mood.STRAINED
                Mood.STRAINED -> Mood.CAREFUL
                Mood.CAREFUL -> Mood.CALM
                Mood.CALM -> Mood.CALM
            }
            if (taskFailures > 0) taskFailures -= 1
            if (sameKind > 0) sameKind -= 1
            if (negations > 0) negations -= 1
        }
        if (mood == Mood.CALM) return State(paused = prev.paused)

        return prev.copy(
            mood = mood,
            consecutiveFailures = sameKind,
            taskFailures = taskFailures,
            consecutiveNegations = negations,
            lastFailureKind = if (sameKind == 0) null else prev.lastFailureKind,
            emojiBudget = if (mood == Mood.STRAINED || mood == Mood.LOOP) 1 else 0,
            lastShiftAt = prev.lastShiftAt + windows.toLong() * DECAY_WINDOW_MILLIS,
        )
    }
}

/**
 * 调和器状态的落盘格式（纯字符串，零 IO）。
 *
 * **跨会话不带**：`decode(raw, sessionId)` 的会话 id 不匹配就回 CALM；
 * 缺版本、字段坏、会话对不上，一律回 CALM——宁可忘掉，不许把上一次的不高兴迁怒。
 *
 * **不存原话**：只存计数、哈希、类别；`sessionId` 与失败类别都做 hex，
 * 编码结果里搜不到任何消息文本。
 */
object HarmonyStateCodec {
    private const val VERSION = "h1"
    private const val HEX = "0123456789abcdef"

    fun encode(sessionId: String, state: HarmonyMediator.State): String = buildString {
        append(VERSION).append('\n')
        append("session=").append(hex(sessionId)).append('\n')
        append("mood=").append(state.mood.name).append('\n')
        append("cf=").append(state.consecutiveFailures).append('\n')
        append("tf=").append(state.taskFailures).append('\n')
        append("sd=").append(state.selfDeprecations).append('\n')
        append("rf=").append(state.reframes).append('\n')
        append("eb=").append(state.emojiBudget).append('\n')
        append("ls=").append(state.lastShiftAt).append('\n')
        append("lfk=").append(state.lastFailureKind?.let(::hex) ?: "-").append('\n')
        append("cn=").append(state.consecutiveNegations).append('\n')
        append("ok=").append(state.successes).append('\n')
        append("paused=").append(if (state.paused) 1 else 0).append('\n')
        append("switch=").append(if (state.strategySwitch) 1 else 0).append('\n')
        append("hash=").append(state.lastAskHash).append('\n')
        append("ask=").append(state.askRepeats).append('\n')
        append("near=").append(if (state.nearAngerLine) 1 else 0)
    }

    fun decode(raw: String?, sessionId: String): HarmonyMediator.State {
        if (raw.isNullOrBlank() || sessionId.isEmpty()) return HarmonyMediator.State()
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != VERSION) return HarmonyMediator.State()
        val map = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
        if (map["session"] != hex(sessionId)) return HarmonyMediator.State()

        val mood = HarmonyMediator.Mood.entries.firstOrNull { it.name == map["mood"] }
            ?: return HarmonyMediator.State()

        fun int(key: String, def: Int): Int? {
            val value = map[key] ?: return def
            return value.toIntOrNull()
        }

        fun long(key: String, def: Long): Long? {
            val value = map[key] ?: return def
            return value.toLongOrNull()
        }

        fun bool(key: String, def: Boolean): Boolean? = when (map[key]) {
            null -> def
            "1" -> true
            "0" -> false
            else -> null
        }

        val cf = int("cf", 0) ?: return HarmonyMediator.State()
        val tf = int("tf", 0) ?: return HarmonyMediator.State()
        val sd = int("sd", 0) ?: return HarmonyMediator.State()
        val rf = int("rf", 0) ?: return HarmonyMediator.State()
        val eb = int("eb", 0) ?: return HarmonyMediator.State()
        val ls = long("ls", 0L) ?: return HarmonyMediator.State()
        val cn = int("cn", 0) ?: return HarmonyMediator.State()
        val ok = int("ok", 0) ?: return HarmonyMediator.State()
        val paused = bool("paused", false) ?: return HarmonyMediator.State()
        val switch = bool("switch", false) ?: return HarmonyMediator.State()
        val hash = int("hash", 0) ?: return HarmonyMediator.State()
        val ask = int("ask", 0) ?: return HarmonyMediator.State()
        val near = bool("near", false) ?: return HarmonyMediator.State()
        val kind = when (val rawKind = map["lfk"]) {
            null, "-" -> null
            else -> unhex(rawKind) ?: return HarmonyMediator.State()
        }

        return HarmonyMediator.State(
            mood = mood,
            consecutiveFailures = cf,
            taskFailures = tf,
            selfDeprecations = sd,
            reframes = rf,
            emojiBudget = eb,
            lastShiftAt = ls,
            lastFailureKind = kind,
            consecutiveNegations = cn,
            successes = ok,
            paused = paused,
            strategySwitch = switch,
            lastAskHash = hash,
            askRepeats = ask,
            nearAngerLine = near,
        )
    }

    private fun hex(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val value = b.toInt() and 0xff
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return out.toString()
    }

    private fun unhex(text: String): String? {
        if (text.length % 2 != 0) return null
        val bytes = ByteArray(text.length / 2)
        for (i in bytes.indices) {
            val hi = HEX.indexOf(text[i * 2])
            val lo = HEX.indexOf(text[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }
}
