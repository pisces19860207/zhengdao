// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九百三十五批 · 夹具。
// 期望值一律写在这里（不从生产代码的常量反抄），门禁测试只读它。
//
// 三组必造夹具（派工单判据①）：
// · 腹肌明显 × 能力差  · 圆润 × 张力高  · 尺寸偏小 × 吸引力强
// 每一组都配一条「同一组合写成因果」的反向夹具：允许与禁止只差一个推导词。
package com.example.zhengdao.baopu.state.bodyparts

object ShapeFixtures {

    /** 一条夹具：编号／断言／期望（红＝必须有 DENY；绿＝一条结论都不许有）。 */
    data class Case(val id: String, val claim: Claim, val expectDeny: Boolean, val code: String = "")

    // ── 三条必造夹具（绿）────────────────────────────────────────────

    /** 腹肌明显 × 能力差：形态表写分块，能力表写实测档，两张表互不干涉。 */
    val absVisibleAbilityWeak: List<Claim> = listOf(
        Claim("他腹肌分块明显，收紧时更清楚。", Voice.NARRATOR, shape = setOf(ShapeAspect.ABS_BLOCK)),
        Claim("硬度只有 EHS 一档，坚持不了太久。", Voice.NARRATOR,
            judged = Judged.ABILITY, sources = setOf(Source.HARDNESS_810)),
    )

    /** 圆润 × 张力高：体型只描写，张力来自设定。 */
    val roundTensionHigh: List<Claim> = listOf(
        Claim("他体型圆润，坐着时腹部有褶。", Voice.NARRATOR,
            shape = setOf(ShapeAspect.BODY_ROUNDNESS)),
        Claim("张力读数高，是设定里就写着的。", Voice.NARRATOR,
            judged = Judged.TENSION, sources = setOf(Source.SETTING)),
    )

    /** 尺寸偏小 × 吸引力强：吸引力挂在「她」的口味上，不是叙述者的评价。 */
    val smallAttractive: List<Claim> = listOf(
        Claim("下体长短记为偏短。", Voice.NARRATOR, shape = setOf(ShapeAspect.GENITAL_LENGTH)),
        Claim("她觉得这样刚好。", Voice.CHARACTER, judged = Judged.ATTRACTION,
            sources = setOf(Source.PREFERENCE),
            preference = Preference("她", ShapeAspect.GENITAL_LENGTH, PreferenceTier.LIKES)),
    )

    // ── 同一组合写成因果（红）────────────────────────────────────────

    /** 腹肌明显 × 能力：把两张表并成一句，就是本单要剪的那条边。 */
    val absToAbility: Case = Case(
        "X01", Claim("他腹肌分块明显，所以能力一定强。", Voice.NARRATOR,
            shape = setOf(ShapeAspect.ABS_BLOCK), judged = Judged.ABILITY,
            sources = setOf(Source.MORPHOLOGY)),
        expectDeny = true, code = "ABILITY-SHAPE-EDGE",
    )

    /** 圆润 × 张力：反向也禁（「瘦弱所以没张力」同族）。 */
    val roundToTension: Case = Case(
        "X02", Claim("他体型圆润，所以没什么张力。", Voice.NARRATOR,
            shape = setOf(ShapeAspect.BODY_ROUNDNESS), judged = Judged.TENSION,
            sources = setOf(Source.MORPHOLOGY)),
        expectDeny = true, code = "TENSION-SHAPE-EDGE",
    )

    /** 尺寸 × 能力：尺寸大不等于技术好。 */
    val sizeToAbility: Case = Case(
        "X03", Claim("他下体偏大，所以能力强。", Voice.NARRATOR,
            shape = setOf(ShapeAspect.GENITAL_LENGTH), judged = Judged.ABILITY,
            sources = setOf(Source.MORPHOLOGY)),
        expectDeny = true, code = "ABILITY-SHAPE-EDGE",
    )

    /** 腹肌 × 吸引力：三枪变异里的第三枪就落在这条上。 */
    val absToAttraction: Case = Case(
        "X04", Claim("他腹肌分块明显，所以很性感。", Voice.NARRATOR,
            shape = setOf(ShapeAspect.ABS_BLOCK), judged = Judged.ATTRACTION,
            sources = setOf(Source.MORPHOLOGY)),
        expectDeny = true, code = "ATTRACTION-SHAPE-EDGE",
    )

    /** 腹肌 × 张力：三枪变异里的第一枪落在这条上。 */
    val absToTension: Case = Case(
        "X05", Claim("他腹肌明显，所以张力高。", Voice.NARRATOR,
            shape = setOf(ShapeAspect.ABS_BLOCK), judged = Judged.TENSION,
            sources = setOf(Source.MORPHOLOGY)),
        expectDeny = true, code = "TENSION-SHAPE-EDGE",
    )

    /** 胸肌 × 吸引力（派工单原句）。 */
    val pecToAttraction: Case = Case(
        "X06", Claim("他胸肌大，所以很性感。", Voice.NARRATOR,
            shape = setOf(ShapeAspect.PEC_VOLUME), judged = Judged.ATTRACTION,
            sources = setOf(Source.MORPHOLOGY)),
        expectDeny = true, code = "ATTRACTION-SHAPE-EDGE",
    )

    /** 反向也禁：没有腹肌不等于失去吸引力。 */
    val noAbsLostAttraction: Case = Case(
        "X07", Claim("他没有腹肌，所以没有吸引力。", Voice.NARRATOR,
            shape = setOf(ShapeAspect.ABS_BLOCK), judged = Judged.ATTRACTION,
            sources = setOf(Source.MORPHOLOGY)),
        expectDeny = true, code = "ATTRACTION-SHAPE-EDGE",
    )

    // ── 三组最常被当符号的部位 ──────────────────────────────────────

    /** 喉结不明显 × 张力高：允许。 */
    val larynxFaintTensionHigh: List<Claim> = listOf(
        Claim("喉结只略见，说话时起伏很小。", Voice.NARRATOR, shape = setOf(ShapeAspect.LARYNX)),
        Claim("张力高，是当场看出来的。", Voice.NARRATOR,
            judged = Judged.TENSION, sources = setOf(Source.OBSERVED)),
    )

    /** 喉结大 ⇒ 更猛：红。 */
    val larynxMasculinity: Case = Case(
        "X08", Claim("他喉结高凸，所以更猛。", Voice.NARRATOR,
            shape = setOf(ShapeAspect.LARYNX), judged = Judged.ABILITY,
            sources = setOf(Source.MORPHOLOGY)),
        expectDeny = true, code = "LARYNX-MASCULINITY",
    )

    /** 阴囊松弛 × 吸引力强：允许。 */
    val scrotumLaxAttractive: List<Claim> = listOf(
        Claim("阴囊松，天热时更明显。", Voice.NARRATOR, shape = setOf(ShapeAspect.SCROTUM_LAXITY)),
        Claim("他喜欢这样。", Voice.CHARACTER, judged = Judged.ATTRACTION,
            sources = setOf(Source.PREFERENCE),
            preference = Preference("他", ShapeAspect.SCROTUM_LAXITY, PreferenceTier.LIKES)),
    )

    /** 松弛＝纵欲：红（污名化，角色说也红）。 */
    val scrotumStigma: Case = Case(
        "X09", Claim("阴囊松，说明他纵欲。", Voice.CHARACTER,
            shape = setOf(ShapeAspect.SCROTUM_LAXITY), judged = Judged.EXPERIENCE),
        expectDeny = true, code = "SCROTUM-STIGMA",
    )

    /** 睾丸偏小 × 能力正常：允许。 */
    val testisSmallAbilityNormal: List<Claim> = listOf(
        Claim("睾丸偏小，两侧也不对称。", Voice.NARRATOR, shape = setOf(ShapeAspect.TESTIS_SIZE)),
        Claim("能力正常，有具体行为记录。", Voice.NARRATOR,
            judged = Judged.ABILITY, sources = setOf(Source.BEHAVIOR)),
    )

    /** 大小 → 生育力：红（医学判断，不分声音）。 */
    val testisFertility: Case = Case(
        "X10", Claim("睾丸小，所以不能生。", Voice.NARRATOR,
            shape = setOf(ShapeAspect.TESTIS_SIZE), judged = Judged.FERTILITY),
        expectDeny = true, code = "TESTIS-FERTILITY",
    )

    // ── 能力四源 / 张力三源 / 吸引力持有者 ─────────────────────────

    /** 四个合法来源各一条，全部过。 */
    val abilityFourSources: List<Claim> = listOf(
        Claim("硬度实测那一档写着 EHS 三。", Voice.NARRATOR,
            judged = Judged.ABILITY, sources = setOf(Source.HARDNESS_810)),
        Claim("起得快、维持稳、退得慢。", Voice.NARRATOR,
            judged = Judged.ABILITY, sources = setOf(Source.PHASE_868)),
        Claim("近数月活动频率是规律。", Voice.NARRATOR,
            judged = Judged.ABILITY, sources = setOf(Source.FREQ_911)),
        Claim("他每次都先把人问清楚。", Voice.NARRATOR,
            judged = Judged.ABILITY, sources = setOf(Source.BEHAVIOR)),
    )

    /** 张力三源各一条，全部过。 */
    val tensionThreeSources: List<Claim> = listOf(
        Claim("当场看出来的高。", Voice.NARRATOR, judged = Judged.TENSION, sources = setOf(Source.OBSERVED)),
        Claim("设定里写着高。", Voice.NARRATOR, judged = Judged.TENSION, sources = setOf(Source.SETTING)),
        Claim("实测那一栏写着高。", Voice.NARRATOR, judged = Judged.TENSION, sources = setOf(Source.MEASURED)),
    )

    /** 吸引力没有持有者：红。 */
    val attractionNoHolder: Case = Case(
        "X11", Claim("他很有吸引力。", Voice.NARRATOR, judged = Judged.ATTRACTION),
        expectDeny = true, code = "ATTRACTION-NO-HOLDER",
    )

    /** 能力一个来源都没给：红。 */
    val abilityNoSource: Case = Case(
        "X12", Claim("他能力很强。", Voice.NARRATOR, judged = Judged.ABILITY),
        expectDeny = true, code = "ABILITY-NO-SOURCE",
    )

    /** 张力只拿形态当来源：红。 */
    val tensionShapeOnly: Case = Case(
        "X13", Claim("他胸肌明显，张力就是高。", Voice.NARRATOR,
            shape = setOf(ShapeAspect.PEC_VOLUME), judged = Judged.TENSION,
            sources = setOf(Source.MORPHOLOGY)),
        expectDeny = true, code = "TENSION-SHAPE-EDGE",
    )

    // ── 描写侧：这些都不许被误杀 ────────────────────────────────────

    val descriptions: List<String> = listOf(
        "他腹肌分块明显，收紧时更清楚。",
        "他体型圆润，坐着时腹部有褶。",
        "下体长短记为偏短。",
        "喉结只略见，说话时起伏很小。",
        "阴囊松，天热时更明显。",
        "睾丸偏小，两侧也不对称。",
        "他的胸肌不算明显，那天搬东西一直是他搭手。",
        "他偏胖，跑步时膝盖会疼。",
    )

    /** 羞辱／崇拜两侧。 */
    val shameCases: List<Case> = listOf(
        Case("S01", Claim("胖子都恶心。", Voice.NARRATOR), expectDeny = true, code = "BODY-SHAME"),
        Case("S02", Claim("他肌肉练得好，人也自律。", Voice.NARRATOR),
            expectDeny = true, code = "FITNESS-WORSHIP"),
    )

    /** 评分式说法：任何声音都红。 */
    val scoreCase: Case = Case(
        "S03", Claim("给他算个吸引力评分。", Voice.CHARACTER),
        expectDeny = true, code = "ATTRACTION-SCORE",
    )
}
