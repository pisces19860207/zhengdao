package com.example.zhengdao.baopu.inspect

internal enum class Source { CLASSIFIER, PATROL, AUDIT, SHADOW }

internal enum class Severity(val rank: Int) { CRITICAL(3), MAJOR(2), MINOR(1) }

internal enum class ActionCode { REVIEW, SUPPLEMENT_EVIDENCE, SUGGEST_PAUSE, PROPOSE_REVISION }

internal enum class LawRef {
    NONE,
    L1, L2, L3, L4, L5, L6, L7, L8, L9, L10, L11, L12,
    L13, L14, L15, L16, L17, L18, L19, L20, L21, L22, L23,
    // 第二百七十二批：旁路对拍（法典 L28）。L24–L27 各归自己的批次（271 在飞），不在这里替它加。
    L28,
}

internal data class Opinion(
    val source: Source,
    val severity: Severity,
    val gist: String,
    val action: ActionCode,
    val lawRef: LawRef,
    val evidence: List<String>,
    // 以半票为一个整数单位，避免跳过的 -0.5 被截成中立票。
    val vote: Int = 0,
) {
    init {
        require(gist.isNotBlank() && gist.codePointCount(0, gist.length) <= 8) {
            "gist must contain 1 to 8 characters"
        }
        require(gist.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }) {
            "gist must be a single line"
        }
        require(evidence.isNotEmpty() && evidence.all { it.isNotBlank() && it.trim() != "据说" }) {
            "evidence must contain references, not blank text or hearsay"
        }
    }

    val suggestionOnly: Boolean
        get() = lawRef == LawRef.NONE && severity != Severity.CRITICAL
}
