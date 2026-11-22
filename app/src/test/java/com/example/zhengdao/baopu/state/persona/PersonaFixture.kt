// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 第八百一十九批 · 测试夹具：两个可区分的角色 A/B（台词在句长/用词/称呼/口头禅四维全不同）。
package com.example.zhengdao.baopu.state.persona

internal object PersonaFixture {

    /** 一份合法的 670 快照。 */
    fun snapshot(): TrackStateSnapshot = TrackStateSnapshot(
        salience = 1, stanceLabel = "中立", dStage = 0, turns = 0,
        vetoActive = false, intoxication = 0, fatigue = 0,
    )

    fun core(): CoreLayer = CoreLayer(
        listOf(
            CoreDrive(CoreNeed.RECOGNITION, "被当成空气", 1, "当众被忽略时"),
            CoreDrive(CoreNeed.SECURITY, "被突然抛下", 2, "关系不确定时"),
            CoreDrive(CoreNeed.BELONGING, "无处可去", 3, "进入陌生场合时"),
        ),
    )

    fun trait(): TraitLayer = TraitLayer(
        Sociability.INTROVERT, Directness.INDIRECT, StanceStyle.FOLLOWING,
        Orientation.PRACTICAL, SelfControl.HIGH, Aggressiveness.LOW, Humor.COLD,
    )

    /** A：长句/书面/叫全名/口头禅'说实在的'（4 字）。 */
    fun voiceA(): PersonaVoice = PersonaVoice(
        name = "甲",
        dimensions = VoiceDimensions(
            sentenceLength = SentenceLength.LONG,
            diction = DictionLevel.LITERARY,
            profanity = Profanity.NONE,
            address = "叫全名",
            catchphrase = "说实在的",
            modalParticle = "罢了",
            pauseEllipsis = "多停顿",
            rhetoric = RhetoricStyle.QUESTION,
            interruption = Interruption.NEVER,
            breakdown = "沉默冷战",
        ),
        lines = listOf(
            VoiceLine(
                "我总觉得，所谓的分寸，其实不过是谁愿意先退一步罢了。说实在的。",
                LineFeatures(SentenceLength.LONG, DictionLevel.LITERARY, "叫全名", "说实在的"),
            ),
        ),
    )

    /** B：短句/白话/叫哥/口头禅'得嘞'（2 字）。四维与 A 全不同。 */
    fun voiceB(): PersonaVoice = PersonaVoice(
        name = "乙",
        dimensions = VoiceDimensions(
            sentenceLength = SentenceLength.SHORT,
            diction = DictionLevel.PLAIN,
            profanity = Profanity.MILD,
            address = "叫哥",
            catchphrase = "得嘞",
            modalParticle = "呗",
            pauseEllipsis = "不停顿",
            rhetoric = RhetoricStyle.DIRECT,
            interruption = Interruption.OFTEN,
            breakdown = "拍桌提高音量",
        ),
        lines = listOf(
            VoiceLine("得嘞，就这么办。", LineFeatures(SentenceLength.SHORT, DictionLevel.PLAIN, "叫哥", "得嘞")),
        ),
    )

    /** 与 A 只差一维（句长）的角色——用于"读不出差异"负例。 */
    fun voiceAOnlyLengthDiff(): PersonaVoice {
        val a = voiceA()
        return a.copy(
            name = "甲二",
            dimensions = a.dimensions.copy(sentenceLength = SentenceLength.SHORT),
        )
    }

    fun persona(): Persona = Persona(
        core = core(),
        trait = trait(),
        surface = SurfaceDeriver.derive(snapshot()),
        voice = voiceA(),
    )
}
