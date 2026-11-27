package com.example.zhengdao.baopu.state.persona

/** 940 行为夹具：两个人物，各两条物理参数齐全、互不重复的私有动作。 */
internal object GestureFixture {
    fun source(kind: GestureSourceKind = GestureSourceKind.EXPRESSION_930, ref: String = "surface.tags") =
        GestureSourceRef(kind, ref)

    fun gesture(
        id: String = "gA1",
        characterId: String = "甲",
        action: String = "左手食指在桌沿轻敲两下",
        part: GesturePart? = GesturePart.HAND,
        direction: GestureDirection? = GestureDirection.DOWN,
        amplitude: GestureAmplitude? = GestureAmplitude.SMALL,
        durationMs: Int? = 400,
        source: GestureSourceRef? = GestureSourceRef(GestureSourceKind.EXPRESSION_930, "surface.tags"),
    ) = HabitGesture(id, characterId, action, part, direction, amplitude, durationMs, source)

    fun gA2() = gesture(
        id = "gA2", action = "右手把杯沿向桌内推一寸", direction = GestureDirection.INWARD,
        amplitude = GestureAmplitude.MEDIUM, durationMs = 700, source = source(GestureSourceKind.ANCHOR_747, "posture.habit"),
    )

    fun gB1() = gesture(
        id = "gB1", characterId = "乙", action = "左脚跟在地面轻点三下", part = GesturePart.FOOT,
        source = source(GestureSourceKind.ANCHOR_747, "posture.habit"),
    )

    fun gB2() = gesture(
        id = "gB2", characterId = "乙", action = "右肩向门外转半寸", part = GesturePart.SHOULDER,
        direction = GestureDirection.OUTWARD, amplitude = GestureAmplitude.MICRO, durationMs = 350,
        source = source(GestureSourceKind.EXPRESSION_930, "emotion"),
    )

    fun profileA() = PersonaGestureProfile("甲", listOf(gesture(), gA2()))

    fun profileB() = PersonaGestureProfile("乙", listOf(gB1(), gB2()))

    fun profiles() = listOf(profileA(), profileB())

    fun beat(
        paragraph: Int = 1,
        characterId: String = "甲",
        gestureId: String? = "gA1",
        text: String = "她把左手食指放在桌沿。",
        emotion: Emotion? = null,
    ) = GestureBeat(paragraph, characterId, gestureId, text, emotion)

    fun codes(report: CheckReport): List<String> = report.items.map { it.code }
}
