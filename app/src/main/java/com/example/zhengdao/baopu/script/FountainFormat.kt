// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 依据的公开规范：Fountain（John August / Nima Yousefi，fountain.io 的语法说明）——
//   标题页 `Key: value`、场景头 `INT./EXT.`、强制场景头 `.`、人物名 `@`、转场 `>`、
//   强制动作 `!`、括号提示 `( )`、空行分段。**只用规范里写明的记号，不用私有扩展。**
//
// 第六百二十一批 · Fountain **写出去 + 读回来**（判据①「导出能被解析器回读」）。
//
// 为什么要自带读回：用户判据要的不是"生成了一份 .fountain 文件"，而是"下游解析器能读"。
// 本仓不引第三方 Fountain 库（Android 侧零新依赖），所以判据落成两件可核的事：
//   1. 写出去的每一行只用规范记号（场景头/人物/转场/动作各有强制前缀）；
//   2. 写完后立刻用 [FountainIn] **按规范**读回来，逐场比标题与每一拍；
//      读回来对不上 ⇒ 导出直接失败（宁可不出文件，也不出一份读不回来的文件）。
//
// 中文档（`ChineseScriptFormat`）不参与读回：它的规矩是给人看的排版，不是机器格式。
package com.example.zhengdao.baopu.script

/** 导出用的纯文本工具（写与读共用同一份空白归一，避免"写进去的是空格、读回来的是换行"）。 */
internal object ScriptText {

    /** 一拍/一行压成一行：换行变空格、连续空白压一个、去首尾。 */
    fun oneLine(raw: String): String = raw.replace("\r", " ").replace("\n", " ")
        .replace(Regex("""\s+"""), " ").trim()

    /** 场景标题的规范形（中文档与自检共用）：`内景 咖啡馆 夜`。 */
    fun headingOf(scene: Scene): String = scene.heading()
}

/** Fountain 导出结果（[text] 是**已经读回校验过**的那一份；[scenes] 是读回来的场次）。 */
internal data class FountainExport(val text: String, val scenes: Int, val lines: Int)

internal object FountainOut {

    /** 标题页的署名（本仓自己的产品名；用户可改产物文件，不回写工程）。 */
    const val AUTHOR = "证道"

    /**
     * 生成 Fountain 文本。
     *
     * @param draftDate 日期（`2026-11-15` 这种形状由调用方给，本文件不取时钟）
     */
    fun write(title: String, scenes: List<Scene>, draftDate: String): String {
        val sb = StringBuilder()
        sb.append("Title: ").append(ScriptText.oneLine(title)).append('\n')
        sb.append("Author: ").append(AUTHOR).append('\n')
        sb.append("Draft date: ").append(draftDate).append('\n')
        sb.append('\n')
        for (scene in scenes) {
            sb.append(headingOf(scene)).append('\n').append('\n')
            for (beat in scene.beats) {
                when (beat.kind) {
                    BeatKind.ACTION -> sb.append(action(beat.text)).append('\n').append('\n')
                    BeatKind.DIALOGUE -> {
                        // 人物名用 `@` 强制（中文名没有大小写，靠大写规则会被读成动作行）
                        sb.append('@').append(ScriptText.oneLine(beat.who.orEmpty())).append('\n')
                        beat.paren?.let { sb.append('(').append(ScriptText.oneLine(it)).append(')').append('\n') }
                        sb.append(ScriptText.oneLine(beat.text)).append('\n').append('\n')
                    }

                    BeatKind.TRANSITION -> sb.append("> ").append(ScriptText.oneLine(beat.text)).append('\n').append('\n')
                }
            }
        }
        return sb.toString()
    }

    /** 场景头：`INT. 咖啡馆 - 夜`（Fountain 要求 ` - ` 分隔地点与时间）。 */
    fun headingOf(scene: Scene): String {
        val where = ScriptText.oneLine(scene.location).replace(" - ", "-")
        val whenText = ScriptText.oneLine(scene.time).replace(" - ", "-")
        return "${if (scene.interior) "INT" else "EXT"}. $where - $whenText"
    }

    /**
     * 动作行：首字符要是会被读成别的记号（`.`/`!`/`@`/`>`/`#`/`=`），前面补 `!`（强制动作）。
     *
     * 这条不是洁癖：一句「@沈砚 转身就走」不转会读成人物名＋对白，导出就静默串了类型。
     */
    fun action(text: String): String {
        val line = ScriptText.oneLine(text)
        val head = line.firstOrNull() ?: return "!"
        return if (head in charArrayOf('.', '!', '@', '>', '#', '=', '~')) "!$line" else line
    }
}

/** 读回来的结果（[beats] 与 [Scene] 同形，便于逐拍比对）。 */
internal data class FountainScene(val heading: String, val beats: List<Beat>)

/**
 * Fountain **读回器**（只认 [FountainOut] 会写出的那几种记号；越界即报错，不猜）。
 *
 * 它同时是"能不能被解析器读"的判据：解析出错的文本＝下游也读不通。
 */
internal object FountainIn {

    private val TITLE_KEYS = setOf("title", "author", "draft date", "draft_date", "contact", "copyright", "notes")

    /**
     * @return 场次列表；[Parsed.Bad] 带行号（哪一行读不下去）
     */
    fun parse(text: String): Parsed<List<FountainScene>> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val reader = Reader()
        var i = skipTitlePage(lines)
        while (i < lines.size) {
            val line = lines[i].trim()
            i++
            val problem = reader.consume(line, i)
            if (problem != null) return problem
        }
        return reader.finish()
    }

    /** 开头连续的标题页 `Key: value`（到第一个空行结束）⇒ 正文起始下标。 */
    private fun skipTitlePage(lines: List<String>): Int {
        var i = 0
        var inTitle = true
        while (i < lines.size && inTitle) {
            val line = lines[i]
            if (line.isBlank()) {
                inTitle = false
                i++
                continue
            }
            val key = line.substringBefore(':', "").trim().lowercase()
            if (key.isEmpty() || key !in TITLE_KEYS) break
            i++
        }
        return i
    }

    /** 逐行状态机（人物名/括号提示只活到下一个空行——空行就是这一段结束）。 */
    private class Reader {
        private val scenes = ArrayList<FountainScene>()
        private val beats = ArrayList<Beat>()
        private var heading: String? = null
        private var character: String? = null
        private var paren: String? = null
        private var waitingForDialogue = false

        fun consume(line: String, lineNo: Int): Parsed.Bad? {
            if (line.isEmpty()) {
                waitingForDialogue = false
                character = null
                paren = null
                return null
            }
            if (FountainIn.isSceneHeading(line)) {
                flushScene()
                heading = line.removePrefix(".").trim()
                character = null
                paren = null
                return null
            }
            if (heading == null) {
                return Parsed.Bad("Fountain 第 $lineNo 行在第一个场景头之前有正文：$line")
            }
            return when {
                line.startsWith(">") -> transition(line, lineNo)
                line.startsWith("@") -> person(line, lineNo)
                character != null -> dialogue(line)
                else -> action(line, lineNo)
            }
        }

        fun finish(): Parsed<List<FountainScene>> {
            if (waitingForDialogue) return Parsed.Bad("Fountain 末尾有一个人物名没有对白：$character")
            flushScene()
            if (scenes.isEmpty()) return Parsed.Bad("Fountain 里一场都没读到（没有 INT./EXT. 场景头）")
            return Parsed.Ok(scenes)
        }

        private fun transition(line: String, lineNo: Int): Parsed.Bad? {
            val text = line.removePrefix(">").trim()
            if (text.isEmpty()) return Parsed.Bad("Fountain 第 $lineNo 行是空转场")
            beats += Beat(BeatKind.TRANSITION, text, null, null, null)
            character = null
            paren = null
            return null
        }

        private fun person(line: String, lineNo: Int): Parsed.Bad? {
            val name = line.removePrefix("@").trim()
            if (name.isEmpty()) return Parsed.Bad("Fountain 第 $lineNo 行是空人物名")
            character = name
            paren = null
            waitingForDialogue = true
            return null
        }

        private fun dialogue(line: String): Parsed.Bad? {
            if (line.startsWith("(") && line.endsWith(")")) {
                paren = line.trim('(', ')').trim()
                waitingForDialogue = true
                return null
            }
            beats += Beat(BeatKind.DIALOGUE, line, character, paren, null)
            paren = null
            waitingForDialogue = false
            return null
        }

        private fun action(line: String, lineNo: Int): Parsed.Bad? {
            val text = if (line.startsWith("!")) line.removePrefix("!").trim() else line
            if (text.isEmpty()) return Parsed.Bad("Fountain 第 $lineNo 行是空动作行")
            beats += Beat(BeatKind.ACTION, text, null, null, null)
            waitingForDialogue = false
            return null
        }

        private fun flushScene() {
            val h = heading ?: return
            scenes += FountainScene(h, beats.toList())
            beats.clear()
        }
    }

    /** 场景头判定：`INT.` / `EXT.` / `INT./EXT.` / `EST.` / `.`（强制）起头。 */
    fun isSceneHeading(line: String): Boolean {
        if (line.startsWith(".")) return true
        val head = line.substringBefore(' ').uppercase()
        return head.startsWith("INT") || head.startsWith("EXT") || head == "EST." || head == "I/E"
    }
}

/**
 * 导出的**自检**：把 [scenes] 与读回来的那份逐场逐拍比，返回 null ＝一致。
 *
 * 比的是"结构事实"：场数、场景头、每一拍的类型/说话人/括号/文本（空白归一后）。
 * 不比配乐/字体/分页——那些不在这份文本里。
 */
internal object FountainSelfCheck {

    fun compare(written: List<Scene>, readBack: List<FountainScene>): String? {
        if (written.size != readBack.size) {
            return "读回来是 ${readBack.size} 场，写出去是 ${written.size} 场"
        }
        written.forEachIndexed { idx, scene ->
            val back = readBack[idx]
            val expectHeading = FountainOut.headingOf(scene)
            if (back.heading != expectHeading) {
                return "第 ${idx + 1} 场的场景头读回来变成「${back.heading}」，写出去是「$expectHeading」"
            }
            if (back.beats.size != scene.beats.size) {
                return "第 ${idx + 1} 场读回来 ${back.beats.size} 拍，写出去 ${scene.beats.size} 拍"
            }
            scene.beats.forEachIndexed { bi, beat ->
                val b = back.beats[bi]
                if (b.kind != beat.kind) {
                    return "第 ${idx + 1} 场第 ${bi + 1} 拍的类型读回来是 ${b.kind.key}，写出去是 ${beat.kind.key}"
                }
                if (ScriptText.oneLine(b.text) != ScriptText.oneLine(beat.text)) {
                    return "第 ${idx + 1} 场第 ${bi + 1} 拍的文本读回来对不上"
                }
                if (beat.kind == BeatKind.DIALOGUE) {
                    if (ScriptText.oneLine(b.who.orEmpty()) != ScriptText.oneLine(beat.who.orEmpty())) {
                        return "第 ${idx + 1} 场第 ${bi + 1} 拍的说话人读回来对不上"
                    }
                    if (ScriptText.oneLine(b.paren.orEmpty()) != ScriptText.oneLine(beat.paren.orEmpty())) {
                        return "第 ${idx + 1} 场第 ${bi + 1} 拍的括号提示读回来对不上"
                    }
                }
            }
        }
        return null
    }
}
