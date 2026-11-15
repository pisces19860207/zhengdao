// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十一批 · **中文标准剧本**排面（给人看的那一份；不做 PDF 排版）。
//
// 排版规矩（本仓自己定的四行，写清楚免得下一批再猜）：
//   1. 总题：《片名》＋ 一行「原创/改编 · 档位 · 共 N 场」；
//   2. 场景头：`第N场　内景　地点　时间`（全角空格分隔，与 Fountain 的 `INT.` 那份是两套投影）；
//   3. 动作：段首两个全角空格；对白：`人物（提示）：台词`（一行说完，中文剧本的常见写法）；
//      转场：`　　切至：…`；
//   4. 场与场之间空一行；冲突项**不塞进正文**（剧本要能直接发给人看），只进溯源表附录。
package com.example.zhengdao.baopu.script

/** 中文档与溯源表（同一份 [Scene] 列表的两张投影）。 */
internal object ChineseScriptFormat {

    /** 全角空格（段落缩进；手机上等宽显示也稳定）。 */
    const val INDENT = "　　"

    /** 正文（不含溯源附录）。 */
    fun render(title: String, scenes: List<Scene>, mode: ScriptMode, tier: ScriptTier?): String {
        val sb = StringBuilder()
        sb.append("《").append(title).append("》").append('\n')
        val tierText = if (mode == ScriptMode.ADAPT) "改编 · ${ScriptTier.textOf(tier)}" else "原创"
        sb.append(tierText).append(" · 共 ").append(scenes.size).append(" 场").append('\n').append('\n')
        for (scene in scenes) {
            sb.append("第").append(scene.no).append("场　").append(scene.heading()).append('\n')
            if (scene.characters.isNotEmpty()) {
                sb.append("人物：").append(scene.characters.joinToString("、")).append('\n')
            }
            sb.append('\n')
            for (beat in scene.beats) {
                when (beat.kind) {
                    BeatKind.ACTION -> sb.append(INDENT).append(ScriptText.oneLine(beat.text)).append('\n').append('\n')
                    BeatKind.DIALOGUE -> {
                        sb.append(INDENT).append(beat.who.orEmpty())
                        beat.paren?.let { sb.append('（').append(ScriptText.oneLine(it)).append('）') }
                        sb.append("：").append(ScriptText.oneLine(beat.text)).append('\n').append('\n')
                    }

                    BeatKind.TRANSITION -> sb.append(INDENT).append("切至：")
                        .append(ScriptText.oneLine(beat.text)).append('\n').append('\n')
                }
            }
        }
        return sb.toString().trimEnd() + "\n"
    }

    /**
     * 溯源表（改编专用；原创给一份空的也没意义，调用方只在 adapt 时写）。
     *
     * 三列硬信息：**第几场 → 第几章 哪几行 → 这一场改了没有**。
     * [missing] 是"大纲里有、正文还没写"的场：宁可在这张表上明写「未写」，
     * 也不让读者以为剧本只有这么多场。
     */
    fun traceTable(
        title: String,
        sourceName: String,
        scenes: List<Scene>,
        outlineScenes: List<OutlineScene>,
        missing: List<String>,
    ): String {
        val sb = StringBuilder()
        sb.append("# 《").append(title).append("》溯源表").append('\n').append('\n')
        sb.append("原文：").append(sourceName).append("（行号＝原文抽取后文本的绝对行号，从 1 数）").append('\n').append('\n')
        sb.append("| 场 | 场景头 | 章 | 行 | 拍数 | 冲突项 |").append('\n')
        sb.append("| --- | --- | --- | --- | --- | --- |").append('\n')
        for (scene in scenes) {
            val ref = scene.source
            sb.append("| ").append(scene.id)
                .append(" | ").append(scene.heading())
                .append(" | ").append(ref?.chapter?.toString() ?: "—")
                .append(" | ").append(ref?.let { "L${it.from}–L${it.to}" } ?: "—")
                .append(" | ").append(scene.beats.size)
                .append(" | ").append(if (scene.conflicts.isEmpty()) "—" else scene.conflicts.size.toString())
                .append(" |").append('\n')
        }
        sb.append('\n')
        val conflicts = scenes.flatMap { s -> s.conflicts.map { s.id to it } }
        if (conflicts.isEmpty()) {
            sb.append("冲突项：无（没有一处人物言行与原作相冲）").append('\n')
        } else {
            sb.append("## 冲突项（人物言行与原作相冲，逐条列出）").append('\n').append('\n')
            conflicts.forEach { (id, c) -> sb.append("- ").append(id).append("　").append(c.text()).append('\n') }
        }
        if (missing.isNotEmpty()) {
            sb.append('\n').append("## 还没写的场").append('\n').append('\n')
            sb.append(missing.joinToString("、")).append("（大纲共 ${outlineScenes.size} 场）").append('\n')
        }
        return sb.toString()
    }
}
