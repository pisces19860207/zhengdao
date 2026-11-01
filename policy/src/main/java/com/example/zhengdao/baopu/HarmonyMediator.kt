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
     *
     * 第二百五十九批（2026-11-02）：原本一整个 96 行／复杂度 51 的大函数，按
     * 「读信号 → 走计数 → 定档 → 拼状态」四步拆开；每一步都是纯函数，**判据一个字没动**。
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

        val signals = readSignals(userText, modelText, decayed)
        val counters = advanceCounters(decayed, outcome, signals.negation)

        // 连续两次成功：直接回落 CALM，计数清空（派工单 §3.6「任意 → CALM」）。
        // 但「用户暂停」要等人说「继续吧」才能解，成功不能替人做主。
        if (counters.successes >= 2) return State(paused = decayed.paused)

        val mood = resolveMood(decayed.mood, counters, signals, limits)
        return buildState(decayed, counters, signals, mood, outcome, limits, now)
    }

    /** 第一步读出来的文本信号（只有布尔与计数，不含原话）。 */
    private data class Signals(
        val blame: Boolean,
        val pause: Boolean,
        val negation: Boolean,
        val handoff: Boolean,
        val selfDep: Int,
        val reframes: Int,
        val askHash: Int,
        val askRepeats: Int,
    )

    /** 第一步：从原话读信号。`askRepeats` 要对照上一次的哈希，故把 `decayed` 一并传入。 */
    private fun readSignals(userText: String, modelText: String, decayed: State): Signals {
        val pause = ReframeFilter.isPause(userText)
        val normalized = userText.trim().replace(Regex("\\s+"), "")
        val askHash = if (normalized.isEmpty()) 0 else normalized.hashCode()
        return Signals(
            blame = ReframeFilter.isBlame(userText),
            pause = pause,
            negation = ReframeFilter.isShortNegation(userText) && !pause,
            handoff = ReframeFilter.isHandoff(userText),
            selfDep = ReframeFilter.selfDeprecationCount(modelText),
            reframes = if (
                ReframeFilter.needsReframe(userText, Side.USER) ||
                ReframeFilter.needsReframe(modelText, Side.MODEL)
            ) 1 else 0,
            askHash = askHash,
            askRepeats = askRepeatsAfter(askHash, decayed),
        )
    }

    /** 同一句话又问一次 ⇒ 追问计数 +1；换了话 ⇒ 从 1 重新数。空文本不记。 */
    private fun askRepeatsAfter(askHash: Int, decayed: State): Int = when {
        askHash == 0 -> 0
        askHash == decayed.lastAskHash -> decayed.askRepeats + 1
        else -> 1
    }

    /** 第二步走进来的账：同类失败、任务失败、成功、否定连击、失败类别。 */
    private data class Counters(
        val sameKind: Int,
        val taskFailures: Int,
        val successes: Int,
        val lastKind: String?,
        val negations: Int,
    )

    /** 第二步：按本轮结果结算计数。失败同类才累加，换类归 1；成功清零并放开类别。 */
    private fun advanceCounters(decayed: State, outcome: Outcome, negation: Boolean): Counters {
        var successes = decayed.successes
        var taskFailures = decayed.taskFailures
        var sameKind = decayed.consecutiveFailures
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
        return Counters(sameKind, taskFailures, successes, lastKind, negations)
    }

    /** 第三步定档：先问熔断，再问紧张，再问留神；同一任务内只升不降。 */
    private fun resolveMood(prev: Mood, c: Counters, s: Signals, limits: PatienceProfile): Mood {
        val target = when {
            isLoop(c, s, limits, prev) -> Mood.LOOP
            isStrained(c, s, limits) -> Mood.STRAINED
            isCareful(c, s) -> Mood.CAREFUL
            else -> prev
        }
        // 回落只走「两次成功 / 用户复位 / 静置衰减」。
        return if (target.ordinal > prev.ordinal) target else prev
    }

    private fun isLoop(c: Counters, s: Signals, limits: PatienceProfile, prev: Mood): Boolean =
        c.sameKind >= LOOP_SAME_KIND_FAILURES ||
            (limits.loopBudget > 0 && c.taskFailures >= limits.loopBudget) ||
            (s.selfDep >= 2 && prev == Mood.STRAINED)

    private fun isStrained(c: Counters, s: Signals, limits: PatienceProfile): Boolean =
        c.taskFailures >= limits.patienceBudget ||
            s.blame ||
            c.negations >= 2 ||
            s.selfDep >= 2

    private fun isCareful(c: Counters, s: Signals): Boolean =
        c.taskFailures >= 1 || s.negation || s.selfDep >= 1

    /** 第四步拼新状态。所有字段都来自前三步，这里只做组装。 */
    private fun buildState(
        decayed: State,
        c: Counters,
        s: Signals,
        mood: Mood,
        outcome: Outcome,
        limits: PatienceProfile,
        now: Long,
    ): State = State(
        mood = mood,
        consecutiveFailures = c.sameKind,
        taskFailures = c.taskFailures,
        selfDeprecations = s.selfDep,
        reframes = s.reframes,
        emojiBudget = if (limits.prefersEmoji && (mood == Mood.STRAINED || mood == Mood.LOOP)) 1 else 0,
        lastShiftAt = shiftAt(mood, decayed, outcome, s, now),
        lastFailureKind = c.lastKind,
        consecutiveNegations = c.negations,
        successes = c.successes,
        paused = decayed.paused || s.pause,
        strategySwitch = s.handoff ||
            (s.askHash != 0 && s.askRepeats > limits.askTolerance && outcome !is Outcome.Success),
        lastAskHash = if (outcome is Outcome.Success) 0 else s.askHash,
        askRepeats = if (outcome is Outcome.Success) 0 else s.askRepeats,
        nearAngerLine = nearAngerLine(mood, c.taskFailures, limits),
    )

    /** 「下一脚就踩线」：还没到发火点，再来一次就到。 */
    private fun nearAngerLine(mood: Mood, taskFailures: Int, limits: PatienceProfile): Boolean =
        mood != Mood.LOOP &&
            limits.angerAt > 0 &&
            taskFailures > 0 &&
            taskFailures < limits.angerAt &&
            taskFailures + 1 >= limits.angerAt

    /** 高压档的起算时刻：回落 CALM 归零，换档/再出状况刷新，否则沿用。 */
    private fun shiftAt(mood: Mood, decayed: State, outcome: Outcome, s: Signals, now: Long): Long = when {
        mood == Mood.CALM -> 0L
        mood != decayed.mood -> now
        outcome is Outcome.Failure || s.blame || s.selfDep > 0 || s.negation -> now
        else -> decayed.lastShiftAt
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
        val windows = decayWindows(prev, now)
        if (windows == 0) return prev

        var mood = prev.mood
        var taskFailures = prev.taskFailures
        var sameKind = prev.consecutiveFailures
        var negations = prev.consecutiveNegations
        repeat(windows) {
            mood = stepDown(mood)
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

    /** 过了几个静置窗口。0 ＝ 不衰减：还在 CALM / 从没进过高压档 / 时间没往前走。 */
    private fun decayWindows(prev: State, now: Long): Int {
        if (prev.mood == Mood.CALM || prev.lastShiftAt <= 0L || now <= prev.lastShiftAt) return 0
        return ((now - prev.lastShiftAt) / DECAY_WINDOW_MILLIS)
            .toInt()
            .coerceIn(0, MAX_DECAY_STEPS)
    }

    /** 降一档：LOOP → STRAINED → CAREFUL → CALM。 */
    private fun stepDown(mood: Mood): Mood = when (mood) {
        Mood.LOOP -> Mood.STRAINED
        Mood.STRAINED -> Mood.CAREFUL
        Mood.CAREFUL -> Mood.CALM
        Mood.CALM -> Mood.CALM
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
        val map = parseMap(raw) ?: return HarmonyMediator.State()
        if (map["session"] != hex(sessionId)) return HarmonyMediator.State()

        val mood = HarmonyMediator.Mood.entries.firstOrNull { it.name == map["mood"] }
            ?: return HarmonyMediator.State()
        val kind = when (val rawKind = map["lfk"]) {
            null, "-" -> null
            else -> unhex(rawKind) ?: return HarmonyMediator.State()
        }

        val nums = parseNumbers(map) ?: return HarmonyMediator.State()
        val flags = parseFlags(map) ?: return HarmonyMediator.State()
        return HarmonyMediator.State(
            mood = mood,
            consecutiveFailures = nums.cf,
            taskFailures = nums.tf,
            selfDeprecations = nums.sd,
            reframes = nums.rf,
            emojiBudget = nums.eb,
            lastShiftAt = nums.ls,
            lastFailureKind = kind,
            consecutiveNegations = nums.cn,
            successes = nums.ok,
            paused = flags.paused,
            strategySwitch = flags.switch,
            lastAskHash = flags.hash,
            askRepeats = flags.ask,
            nearAngerLine = flags.near,
        )
    }

    /** 版本行对不上就整份作废（null ⇒ 调用方回 CALM）。 */
    private fun parseMap(raw: String): Map<String, String>? {
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != VERSION) return null
        return lines.drop(1).mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
    }

    /** 计数类字段（任一坏掉整份作废，宁可忘掉也不迁怒）。 */
    private data class Numbers(
        val cf: Int,
        val tf: Int,
        val sd: Int,
        val rf: Int,
        val eb: Int,
        val ls: Long,
        val cn: Int,
        val ok: Int,
    )

    /** 开关类字段。 */
    private data class Flags(
        val paused: Boolean,
        val switch: Boolean,
        val hash: Int,
        val ask: Int,
        val near: Boolean,
    )

    private fun parseNumbers(map: Map<String, String>): Numbers? {
        val cf = intOf(map, "cf", 0) ?: return null
        val tf = intOf(map, "tf", 0) ?: return null
        val sd = intOf(map, "sd", 0) ?: return null
        val rf = intOf(map, "rf", 0) ?: return null
        val eb = intOf(map, "eb", 0) ?: return null
        val ls = longOf(map, "ls", 0L) ?: return null
        val cn = intOf(map, "cn", 0) ?: return null
        val ok = intOf(map, "ok", 0) ?: return null
        return Numbers(cf, tf, sd, rf, eb, ls, cn, ok)
    }

    private fun parseFlags(map: Map<String, String>): Flags? {
        val paused = boolOf(map, "paused", false) ?: return null
        val switch = boolOf(map, "switch", false) ?: return null
        val hash = intOf(map, "hash", 0) ?: return null
        val ask = intOf(map, "ask", 0) ?: return null
        val near = boolOf(map, "near", false) ?: return null
        return Flags(paused, switch, hash, ask, near)
    }

    /** 缺键取默认；键在但值坏 ⇒ null（整份作废）。 */
    private fun intOf(map: Map<String, String>, key: String, def: Int): Int? {
        val value = map[key] ?: return def
        return value.toIntOrNull()
    }

    private fun longOf(map: Map<String, String>, key: String, def: Long): Long? {
        val value = map[key] ?: return def
        return value.toLongOrNull()
    }

    private fun boolOf(map: Map<String, String>, key: String, def: Boolean): Boolean? = when (map[key]) {
        null -> def
        "1" -> true
        "0" -> false
        else -> null
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
