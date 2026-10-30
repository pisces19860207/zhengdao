package com.example.zhengdao.baopu.inspect

internal enum class OpinionAdapter(val source: Source) {
    CLASSIFIER(Source.CLASSIFIER),
    PATROL(Source.PATROL),
    AUDIT(Source.AUDIT),
    SHADOW(Source.SHADOW),

    // 第二百九十批：斥候与四器**同一套契约**（同一把 ≤8 字/证据/法典尺），只是来源不同。
    SCOUT(Source.SCOUT),
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
