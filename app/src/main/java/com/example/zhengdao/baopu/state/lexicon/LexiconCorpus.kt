// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十一批 · 词表语料：4 档 × 3 距离层，每层 ≥5 条，每条标档位区间 + 归属轴。
// 文本是工程夹具用的抽象可观察短句，**不取任何私材原文**（669 纪律：私材只读、不复制、不打印）。
// 每条语料含且只含一个强度信号词（见 SignalGrades）——保证反向判回有唯一真相，可测可错。
package com.example.zhengdao.baopu.state.lexicon

/** 一条 (档, 距离, 序号) 的唯一键，用字符串拼，便于断言重复。 */
private fun key(grade: Int, band: DistanceBand, n: Int): String = "$grade-${band.name}-$n"

/**
 * 词表唯一真相：4 档 × 3 层 × 5 条 = 60 条。
 * 每档的区间标记：0/1 档落 [0,1]、2 档落 [2,3]、3 档落 [3,3]（相邻档允许判回邻档命中）。
 * 每条 text 含一个且仅一个强度信号词，供反向判回确定性还原。
 */
object LexiconCorpus {
    val ENTRIES: List<LexiconEntry> = buildList {
        // —— 0 档（无/静息），区间 0–1 ——
        addAll(level(0, GradeSpan.G0, "平复", listOf(
            "气息平复下来，肩线松着", "指节舒展，没有用力的痕迹", "呼吸匀长，胸口起伏很浅",
            "坐得松弛，重心落在椅背上", "声息平稳，尾音没有颤",
        ), listOf(
            "房间安静，人的气息也跟着缓", "窗边的影子一动不动", "桌面杯沿的水纹已经静了",
            "灯下的轮廓放松地待着", "整个人的节奏慢下来",
        ), listOf(
            "夜色里只有均匀的呼吸声", "一切归于寻常的安静", "屋内没有多余的动静",
            "远处传来风声，人已安宁", "光与影都停在原处",
        )))
        // —— 1 档（微/初），区间 1–2 ——
        addAll(level(1, GradeSpan.G1, "微微", listOf(
            "指尖微微蜷了一下", "喉结轻轻动了动", "肩头微微一沉", "眉心微微收拢", "脚趾微微扣住地面",
        ), listOf(
            "气息微微乱了半拍", "手指微微攥紧衣角", "身体微微前倾", "嘴角微微抿住", "膝盖微微并拢",
        ), listOf(
            "气氛微微变了味道", "影子微微晃了一下", "空气似乎微微发紧", "节奏微微快起来", "光晕微微偏了一寸",
        )))
        // —— 2 档（明显/中），区间 2–3，用「明显」——
        addAll(level(2, GradeSpan.G2, "明显", listOf(
            "呼吸明显急促起来", "指节明显泛白", "颈侧明显绷出一条线", "唇形明显收紧", "腹肌明显收住",
        ), listOf(
            "整个人明显僵了一瞬", "肩背明显绷直", "手臂明显抬高", "步伐明显顿住", "目光明显定在一点",
        ), listOf(
            "气氛明显沉了下来", "节奏明显加快", "影子明显拉长", "声响明显逼近", "轮廓明显清晰起来",
        )))
        // —— 3 档（剧烈/极），区间 3–3，用「剧烈」——
        addAll(level(3, GradeSpan.G3, "剧烈", listOf(
            "呼吸剧烈起伏，收不住", "指节剧烈发颤", "整条手臂剧烈绷紧", "喉间剧烈抽了一声", "脊背剧烈弓起",
        ), listOf(
            "身体剧烈一颤", "双肩剧烈耸动", "四肢剧烈收紧", "脖颈剧烈后仰", "拳心剧烈攥死",
        ), listOf(
            "整间屋子像剧烈晃了一下", "光影剧烈摇曳", "声浪剧烈叠上来", "气场剧烈翻涌", "四下剧烈地静了一瞬",
        )))
    }

    /** 生成某一档的三层语料：每层把信号词嵌入每条文本（去重后仍需含该词）。 */
    private fun level(
        grade: Int,
        span: GradeSpan,
        signal: String,
        near: List<String>,
        mid: List<String>,
        far: List<String>,
    ): List<LexiconEntry> {
        require(near.size >= 5 && mid.size >= 5 && far.size >= 5) { "每层须 ≥5 条" }
        return listOf(DistanceBand.NEAR to near, DistanceBand.MID to mid, DistanceBand.FAR to far)
            .flatMap { (band, texts) ->
                texts.mapIndexed { n, raw ->
                    val text = if (raw.contains(signal)) raw else raw + "——" + signal
                    LexiconEntry(
                        id = key(grade, band, n + 1),
                        grade = grade,
                        span = span,
                        band = band,
                        axis = "observable",
                        text = text,
                    )
                }
            }
    }

    const val GRADES = 4
    const val PER_LAYER = 5
}
