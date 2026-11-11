// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十五批（2026-11-11）：585 酒馆兼容测试夹具。
// 全部**运行时生成**、不落盘、不进 resources（沿用 CardFixtures 红线）。
package com.example.zhengdao.baopu.card

import org.json.JSONArray
import org.json.JSONObject

/** 判据对拍用的「核心字段」快照：把 DTO 压成一张可比的字符串表。 */
internal fun TavernBundleDto.coreSnapshot(): String = buildString {
    appendLine("card.name=${card.name}")
    appendLine("card.description=${card.description}")
    appendLine("card.personality=${card.personality}")
    appendLine("card.scenario=${card.scenario}")
    appendLine("card.firstMes=${card.firstMes}")
    appendLine("card.mesExample=${card.mesExample}")
    appendLine("card.creatorNotes=${card.creatorNotes}")
    appendLine("card.systemPrompt=${card.systemPrompt}")
    appendLine("card.postHistoryInstructions=${card.postHistoryInstructions}")
    appendLine("card.alternateGreetings=${card.alternateGreetings.joinToString("|")}")
    appendLine("card.tags=${card.tags.joinToString("|")}")
    appendLine("card.regex=${card.regexScripts.joinToString("|") { "${it.findRegex}@${it.placement}" }}")
    appendLine("world.name=${world.name}")
    appendLine("world.size=${world.entries.size}")
    world.entries.forEachIndexed { i, e ->
        appendLine("e[$i].keys=${e.keys.joinToString("|") { "${it.kind}:${it.raw}" }}")
        appendLine("e[$i].keysecondary=${e.keysecondary.joinToString("|") { it.raw }}")
        appendLine("e[$i].content=${e.content}")
        appendLine("e[$i].constant=${e.constant}")
        appendLine("e[$i].selective=${e.selective}")
        appendLine("e[$i].order=${e.order}")
        appendLine("e[$i].depth=${e.depth}")
        appendLine("e[$i].probability=${e.probability}")
        appendLine("e[$i].disable=${e.disable}")
        appendLine("e[$i].addMemo=${e.addMemo}")
        appendLine("e[$i].group=${e.group}")
        appendLine("e[$i].position=${e.position}")
        appendLine("e[$i].stPosition=${e.stPosition}")
        appendLine("e[$i].stDepth=${e.stDepth}")
        appendLine("e[$i].stRole=${e.stRole}")
        appendLine("e[$i].useProbability=${e.useProbability}")
    }
}

internal object TavernFixtures {

    /** 一张字段齐全的卡＋含三种 key 写法的设定集（含 position 双轨与正则脚本）。 */
    fun bundle(): TavernBundleDto = TavernBundleDto(
        card = TavernCardDto(
            name = "示例角色",
            description = "", // 铁律②
            personality = "沉稳，话少",
            scenario = "雨夜的客栈",
            firstMes = "你来了。",
            mesExample = "<START>示例对话",
            creatorNotes = "示例注",
            systemPrompt = "示例系统提示",
            postHistoryInstructions = "示例后置指令",
            alternateGreetings = listOf("另一句开场"),
            tags = listOf("原创", "示例"),
            regexScripts = listOf(
                // 判据⑮：完整形式带 flags；显示类三件套
                TavernRegexScript(
                    findRegex = "/【(.+?)】/gi",
                    replaceString = "$1",
                    markdownOnly = true,
                    promptOnly = false,
                    placement = listOf(2),
                ),
            ),
        ),
        world = TavernWorldDto(
            name = "示例设定集",
            entries = listOf(
                // ① 纯文本键
                TavernEntryDto(
                    keys = TavernKeys.tokensOf(listOf("客栈")),
                    keysecondary = TavernKeys.tokensOf(listOf("雨夜")),
                    content = "客栈在城东",
                    constant = true,
                    selective = false,
                    order = 10,
                    depth = 4,
                    probability = 100,
                    disable = false,
                    addMemo = true,
                    group = "地点",
                    position = "before_char",
                    stPosition = 0,
                    useProbability = true,
                ),
                // ② 多值键
                TavernEntryDto(
                    keys = TavernKeys.tokensOf(listOf("剑", "刀", "暗器")),
                    content = "兵器谱",
                    order = 20,
                    group = "器物",
                    position = "after_char",
                    stPosition = 1,
                ),
                // ③ 正则键 /re/ ＋ atDepth 双轨（position=4 配 depth/role）
                TavernEntryDto(
                    keys = TavernKeys.tokensOf(listOf("/(客栈|驿站)/i", "/^第\\d+章$/")),
                    content = "正则触发的补充设定",
                    order = 30,
                    selective = true,
                    disable = true,
                    group = "",
                    position = "before_char",
                    stPosition = 4,
                    stDepth = 2,
                    stRole = 0,
                ),
            ),
        ),
    )

    /** 判据③负例：带一个「我们的自定义字段」。 */
    fun bundleWithCustomField(): TavernBundleDto {
        val b = bundle()
        return b.copy(card = b.card.copy(customFields = mapOf("我们的自定义字段" to "这条会丢")))
    }

    /** 判据⑮负例：正则脚本裸 pattern（会被 ST 拒，必须报会丢且不导出）。 */
    fun bundleWithBareRegex(): TavernBundleDto {
        val b = bundle()
        return b.copy(card = b.card.copy(regexScripts = listOf(TavernRegexScript(findRegex = "裸pattern"))))
    }

    /** 判据⑤：500 条大世界书。 */
    fun bigWorld(n: Int = 500): TavernWorldDto = TavernWorldDto(
        name = "大设定集",
        entries = List(n) { i ->
            TavernEntryDto(
                keys = TavernKeys.tokensOf(listOf("关键词$i", "别名$i")),
                keysecondary = TavernKeys.tokensOf(listOf("次级$i")),
                content = "第 $i 条设定正文：用于测大世界书的导出耗时与产物体积。",
                constant = i % 7 == 0,
                selective = i % 5 == 0,
                order = i,
                depth = i % 21,
                probability = 100,
                disable = i % 11 == 0,
                addMemo = true,
                group = "分组${i % 9}",
            )
        },
    )

    /**
     * 项目既有 ST 链路能认的**独立世界书**（entries 字典 + key）。
     * 判据⑨用它真跑 CardFormat/CardAssetParser/CardCheck。
     */
    fun stStandaloneWorld(): String {
        val dict = JSONObject()
        for (i in 0 until 2) {
            dict.put(
                i.toString(),
                JSONObject()
                    .put("uid", i)
                    .put("key", JSONArray(listOf("原生键$i")))
                    .put("keysecondary", JSONArray(listOf("次级$i")))
                    .put("comment", "示例备注")
                    .put("content", "原生正文$i")
                    .put("constant", false)
                    .put("selective", false)
                    .put("order", i)
                    .put("depth", 4)
                    .put("probability", 100)
                    .put("disable", false)
                    .put("addMemo", true)
                    .put("group", "原生组"),
            )
        }
        return JSONObject().put("name", "原生书").put("entries", dict).toString(2) + "\n"
    }

    /** 外部卡（v2 平铺、无 data 包裹）：验证「v2/v3 都吃」。 */
    fun v2Card(): String = JSONObject()
        .put("spec", TavernFields.CARD_SPEC_V2)
        .put("name", "v2角色")
        .put("description", "")
        .put("personality", "v2性格")
        .put("scenario", "v2场景")
        .put("first_mes", "v2开场")
        .put("mes_example", "")
        .put("creator_notes", "v2注")
        .put("tags", JSONArray(listOf("v2")))
        .toString(2) + "\n"

    /** 卡内 character_book 形状（数组 + keys）的外部样本：验证判据⑫导入。 */
    fun cardBookText(): String = JSONObject()
        .put("name", "卡内书")
        .put("entries", JSONArray().put(stEntryForCardBook()))
        .toString(2) + "\n"

    private fun stEntryForCardBook(): JSONObject = JSONObject()
        .put("keys", JSONArray(listOf("卡内键")))
        .put("keysecondary", JSONArray(listOf("卡内次级")))
        .put("content", "卡内正文")
        .put("constant", false)
        .put("selective", false)
        .put("order", 1)
        .put("depth", 4)
        .put("probability", 100)
        .put("disable", false)
        .put("addMemo", true)
        .put("group", "卡内组")
}
