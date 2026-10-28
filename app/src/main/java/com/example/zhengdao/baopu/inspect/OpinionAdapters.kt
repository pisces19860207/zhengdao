package com.example.zhengdao.baopu.inspect

internal enum class OpinionAdapter(val source: Source) {
    CLASSIFIER(Source.CLASSIFIER),
    PATROL(Source.PATROL),
    AUDIT(Source.AUDIT),
    SHADOW(Source.SHADOW),
    ;

    fun adapt(
        severity: Severity,
        gist: String,
        action: ActionCode,
        lawRef: LawRef,
        evidence: List<String>,
        vote: Int = 0,
    ): Opinion = Opinion(source, severity, gist, action, lawRef, evidence.toList(), vote)
}
