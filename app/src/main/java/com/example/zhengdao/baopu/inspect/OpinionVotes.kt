package com.example.zhengdao.baopu.inspect

internal enum class OpinionSignal(val halfVotes: Int) {
    AGREE(2),
    REJECT(-2),
    SKIP(-1),
    CLOSE(0),
    ASK_AGAIN(-2),
    CONTINUE(2),
    PRAISE(2),
}

internal data class OpinionFeedback(
    val kind: String,
    val atMs: Long,
    val signal: OpinionSignal,
) {
    init {
        require(kind.isNotBlank()) { "kind must identify a card category" }
    }
}

internal object OpinionVotes {
    const val WINDOW_MS = 30L * 24 * 60 * 60 * 1_000

    fun total(kind: String, feedback: List<OpinionFeedback>, nowMs: Long): Long =
        feedback.asSequence()
            .filter { it.kind == kind && it.atMs in (nowMs - WINDOW_MS)..nowMs }
            .sumOf { it.signal.halfVotes.toLong() }

    fun shouldLowerSensitivity(kind: String, feedback: List<OpinionFeedback>, nowMs: Long): Boolean =
        total(kind, feedback, nowMs) < 0L
}
