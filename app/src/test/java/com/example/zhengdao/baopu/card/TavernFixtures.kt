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
    appendLine("card.tags=${card.tags.joinToString("|")}")
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
        appendLine("e[$i].useProbability=${e.useProbability}")
    }
}

internal object TavernFixtures {

    /** 一张字段齐全的卡＋含三种 key 写法的设定集。 */
    fun bundle(): TavernBundleDto = TavernBundleDto(
        card = TavernCardDto(
            name = "示例角色",
            description = "", // 铁律②
            personality = "沉稳，话少",
            scenario = "雨夜的客栈",
            firstMes = "你来了。",
            mesExample = "<START>示例对话",
            creatorNotes = "示例注",
            tags = listOf("原创", "示例"),
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
                ),
                // ② 多值键
                TavernEntryDto(
                    keys = TavernKeys.tokensOf(listOf("剑", "刀", "暗器")),
                    content = "兵器谱",
                    order = 20,
                    group = "器物",
                    position = 1,
                    useProbability = true,
                ),
                // ③ 正则键 /re/
                TavernEntryDto(
                    keys = TavernKeys.tokensOf(listOf("/(客栈|驿站)/i", "/^第\\d+章$/")),
                    content = "正则触发的补充设定",
                    order = 30,
                    selective = true,
                    disable = true,
                    group = "",
                ),
            ),
        ),
    )

    /** 判据③负例：带一个「我们的自定义字段」。 */
    fun bundleWithCustomField(): TavernBundleDto {
        val b = bundle()
        return b.copy(
            card = b.card.copy(customFields = mapOf("我们的自定义字段" to "这条会丢")),
        )
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

    /** 外部酒馆原生卡（entries 是字典、主键叫 key），验证「别人给的卡也能进来」。 */
    fun silkyNativeWorld(): String {
        val entries = JSONArray()
        // 用 org.json 程序拼，不手写 JSON 文本（与铁律①同口径）
        for (i in 0 until 2) {
            val o = JSONObject()
                .put("uid", i)
                .put("key", JSONArray(listOf("原生键$i")))
                .put("keysecondary", JSONArray(listOf("次级$i")))
                .put("content", "原生正文$i")
                .put("constant", false)
                .put("selective", false)
                .put("order", i)
                .put("depth", 4)
                .put("probability", 100)
                .put("disable", false)
                .put("addMemo", true)
                .put("group", "原生组")
                .put("comment", "表外字段：应报会丢")
                .put("vectorized", false)
            entries.put(o)
        }
        val dict = JSONObject()
        for (i in 0 until entries.length()) dict.put(i.toString(), entries.optJSONObject(i))
        return JSONObject().put("name", "原生书").put("entries", dict).toString(2) + "\n"
    }
}
