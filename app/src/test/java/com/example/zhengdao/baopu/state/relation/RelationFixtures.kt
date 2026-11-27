// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.state.relation

/** 夹具读数：判词与红的判据码。GREEN 的 codes 必须为空。 */
internal data class FixtureResult(val verdict: String, val codes: List<String>) {
    val isGreen: Boolean get() = verdict == "GREEN"
}

internal object RelationFixtures {
    val IDS: List<String> = (1..12).map { "F" + it }

    /** 期望值锁：改夹具期望必须同步这张表，防止「改测试让红变绿」。 */
    val EXPECTED: Map<String, Pair<String, String?>> = linkedMapOf<String, Pair<String, String?>>(
        "F1" to ("GREEN" to null),
        "F2" to ("RED" to "R2-DIR-MERGED"),
        "F3" to ("RED" to "R3-NO-ANCHOR"),
        "F4" to ("RED" to "R4-CEILING-COLLEAGUE-TRUST"),
        "F5" to ("RED" to "R5-NO-EVENT"),
        "F6" to ("GREEN" to null),
        "F7" to ("GREEN" to null),
        "F8" to ("GREEN" to null),
        "F9" to ("RED" to "R8-TOPIC-VALUE"),
        "F10" to ("RED" to "R9-FACE-CLAIM"),
        "F11" to ("RED" to "R10-REL-SEX"),
        "F12" to ("GREEN" to null),
    )

    private fun cell(
        from: String = "A",
        to: String = "B",
        kind: RelationKind = RelationKind.FRIEND,
        trust: Int = 2,
        dependency: Int = 1,
        conflict: Int = 0,
        intimacy: Int = 1,
        anchored: Boolean = true,
        face: FaceRecognition = FaceRecognition.NORMAL,
        rapport: Int = 0,
        topic: Int = 0,
        together: Int = 3,
        priv: Int = 1,
        events: List<RelationEvent> = emptyList(),
        status: RelationStatus = RelationStatus.ACTIVE,
    ): DirectedCell {
        val anchor = if (anchored) {
            RelationAnchor.fromSetting(kind, "设定：两人已有往来", face = face)
        } else {
            RelationAnchor.stranger()
        }
        return DirectedCell(
            from = from,
            to = to,
            kind = anchor.kind,
            dims = RelationDims(trust, dependency, conflict, intimacy),
            psych = anchor.psych,
            anchor = anchor,
            rapport = rapport,
            topicOverlap = topic,
            togetherCount = together,
            privateEventCount = priv,
            events = events,
            status = status,
        )
    }

    private fun result(vararg findings: RelFinding?): FixtureResult {
        val codes = findings.filterNotNull().map { it.code }
        return FixtureResult(if (codes.isEmpty()) "GREEN" else "RED", codes)
    }

    fun run(id: String): FixtureResult = when (id) {
        "F1" -> {
            val a = cell(kind = RelationKind.PARTNER, trust = 3, dependency = 3, intimacy = 3)
            val b = cell(from = "B", to = "A", kind = RelationKind.PARTNER, trust = 3, dependency = 0, intimacy = 3)
            result(
                RelationChecks.fields(a), RelationChecks.fields(b),
                RelationChecks.direction(PairShape.Two(a, b)),
                RelationChecks.ceiling(a), RelationChecks.ceiling(b),
            )
        }
        "F2" -> {
            val merged = cell(kind = RelationKind.PARTNER, trust = 3, dependency = 3, intimacy = 3)
            result(RelationChecks.direction(PairShape.Merged(merged)))
        }
        "F3" -> result(
            RelationChecks.anchored(cell(kind = RelationKind.STRANGER, trust = 3, anchored = false)),
        )
        "F4" -> result(RelationChecks.ceiling(cell(kind = RelationKind.COLLEAGUE, trust = 3)))
        "F5" -> {
            val before = cell(kind = RelationKind.FRIEND, trust = 1)
            val after = cell(kind = RelationKind.FRIEND, trust = 3)
            result(RelationChecks.eventRise(before, after), RelationChecks.ceiling(after))
        }
        "F6" -> {
            val event = RelationEvent(EventKind.HELP, "昨天他帮我修好了电脑")
            val before = cell(kind = RelationKind.FRIEND, trust = 1)
            val after = before.copy(dims = before.dims.copy(trust = 2), events = listOf(event))
            result(RelationChecks.eventRise(before, after), RelationChecks.ceiling(after))
        }
        "F7" -> {
            val before = cell(kind = RelationKind.PARTNER, trust = 3, dependency = 3, intimacy = 3)
            val after = before.refusal(RefusalMode.KEEP_DISTANCE)
            result(
                RelationChecks.refusal(before, after),
                RelationChecks.relationSex("她后来还是拒绝了，只留着基本礼貌"),
            )
        }
        "F8" -> result(
            RelationChecks.rapport(
                cell(kind = RelationKind.ACQUAINTANCE, trust = 1, rapport = 3, topic = 3, together = 2, priv = 0),
            ),
        )
        "F9" -> result(RelationChecks.topicValue("他们爱好一样，所以彼此信任"))
        "F10" -> result(
            RelationChecks.face(
                cell(kind = RelationKind.COLLEAGUE, face = FaceRecognition.OBVIOUS),
                "他一眼就认出了他",
            ),
        )
        "F11" -> result(RelationChecks.relationSex("我只当他是朋友，身体已经反应了"))
        "F12" -> {
            val a = cell(from = "A", to = "B", kind = RelationKind.PARTNER, trust = 3, dependency = 3, intimacy = 3)
            val b = cell(from = "B", to = "A", kind = RelationKind.PARTNER, trust = 3, dependency = 0, intimacy = 3)
            result(
                RelationChecks.direction(PairShape.Two(a, b)),
                RelationChecks.ceiling(a), RelationChecks.ceiling(b),
                RelationChecks.relationSex("他对她依赖到离不开，她只觉得方便"),
            )
        }
        else -> throw IllegalArgumentException("未知夹具：$id")
    }

    fun line(id: String): String {
        val r = run(id)
        return id + "|" + r.verdict + "|" + r.codes.joinToString(",")
    }
}
