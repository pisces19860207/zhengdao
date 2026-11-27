// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百四十批 · 情绪动作个体化：纯函数判据。零 IO、零随机、零时钟。
// 位置遮罩借 930 的「引号 / 直接引语」思路，但不调用语言闸、不复制临床词表。
package com.example.zhengdao.baopu.state.persona

internal object GestureChecks {
    private val QUOTED = Regex("\"([^\"]*)\"|「([^」]*)」|“([^”]*)”")
    private val DIRECT_SPEECH = Regex("(?:(?:他|她)?(?:说|问|答)|低声说)\\s*[：:]\\s*")
    private val SENTENCE_BREAKS = charArrayOf('。', '！', '？', '!', '?', '；', ';', '\n')
    private val SYMBOL_PATTERN = symbolPattern()

    /** 一次性总检；同输入必得逐字节相同的 CheckReport.line()。 */
    fun check(profiles: List<PersonaGestureProfile>): CheckReport {
        val out = mutableListOf<CheckItem>()
        val gestures = profiles.flatMap { it.habitGestures }
        out += rosterChecks(profiles, gestures)
        out += gestureChecks(gestures)
        profiles.forEach { profile ->
            out += beatChecks(profile, gestures)
            out += textChecks(profile)
        }
        return CheckReport(out)
    }

    /** ①人数 2–4；②同人动作不重；③跨人物动作键零复用；④id 不重。 */
    private fun rosterChecks(profiles: List<PersonaGestureProfile>, gestures: List<HabitGesture>): List<CheckItem> {
        val out = mutableListOf<CheckItem>()
        profiles.forEach { profile ->
            val count = profile.habitGestures.size
            if (count !in GestureCatalog.MIN_PER_PERSON..GestureCatalog.MAX_PER_PERSON) {
                out += err(
                    "GESTURE_COUNT",
                    "${profile.characterId} 习惯动作须 ${GestureCatalog.MIN_PER_PERSON}–" +
                        "${GestureCatalog.MAX_PER_PERSON} 个，实得 $count",
                )
            }
            profile.habitGestures.groupBy { it.actionKey }.filterValues { it.size > 1 }.forEach { (key, list) ->
                out += err("GESTURE_ACTION_DUP", "${profile.characterId} 同一动作写了 ${list.size} 次：$key")
            }
        }
        gestures.groupBy { it.actionKey }.forEach { (key, list) ->
            val owners = list.map { it.characterId }.toSet()
            if (owners.size > 1) {
                out += err("GESTURE_CROSS_CHARACTER", "动作跨人物复用：$key → ${owners.joinToString("/")}")
            }
        }
        gestures.groupBy { it.id }.filterValues { it.size > 1 }.forEach { (id, list) ->
            out += err("GESTURE_ID_DUP", "动作 id 跨人物复用：$id → ${list.size} 次")
        }
        return out
    }

    /** 四要素与来源，逐项列出缺口；范式词与文学词都在动作层直接判红。 */
    private fun gestureChecks(gestures: List<HabitGesture>): List<CheckItem> {
        val out = mutableListOf<CheckItem>()
        gestures.forEach { gesture ->
            val missing = mutableListOf<String>()
            if (gesture.part == null) missing += "部位"
            if (gesture.direction == null) missing += "朝向"
            if (gesture.amplitude == null) missing += "幅度"
            if (gesture.durationMs == null || gesture.durationMs <= 0) missing += "时长"
            if (missing.isNotEmpty()) {
                out += err("GESTURE_PHYSICAL_MISSING", "${gesture.id} 缺物理参数：${missing.joinToString("/")}")
            }
            val source = gesture.source
            if (source == null || !source.known) {
                out += err("GESTURE_SOURCE_UNKNOWN", "${gesture.id} 来源不在 930/747 可指表内：${source?.ref}")
            }
            GestureCatalog.LITERARY.filter { it in gesture.action }.forEach { phrase ->
                out += err("GESTURE_LITERARY", "${gesture.id} 文学化形容不物理：$phrase")
            }
            GestureCatalog.BLACKLIST.filter { it.phrase in gesture.action }.forEach { entry ->
                out += err("GESTURE_CLIHE_ACTION", "${gesture.id} 范式动作：${entry.id}/${entry.phrase}")
            }
        }
        return out
    }

    /** 事件只认本人动作簿里的 id；gestureId=null 的事件不产生任何 finding。 */
    private fun beatChecks(profile: PersonaGestureProfile, gestures: List<HabitGesture>): List<CheckItem> {
        val byId = gestures.associateBy { it.id }
        val out = mutableListOf<CheckItem>()
        profile.beats.forEach { beat ->
            val gestureId = beat.gestureId ?: return@forEach
            if (gestureId !in byId) {
                out += err("GESTURE_BEAT_UNKNOWN", "${profile.characterId} 第 ${beat.paragraph} 段引用了簿外动作：$gestureId")
            }
        }
        val byAction = profile.beats.mapNotNull { beat ->
            byId[beat.gestureId]?.let { beat.paragraph to it.actionKey }
        }.groupBy { it.second }
        byAction.forEach { (key, events) ->
            val paragraphs = events.map { it.first }.sorted()
            paragraphs.zipWithNext().forEach { (earlier, later) ->
                if (later - earlier < GestureCatalog.FREQUENCY_WINDOW) {
                    out += err(
                        "GESTURE_FREQUENCY",
                        "${profile.characterId} 动作 '$key' 第 $earlier/$later 段重复，" +
                            "每 ${GestureCatalog.FREQUENCY_WINDOW} 段至多 1 次",
                    )
                }
            }
        }
        return out
    }

    /** 叙述文字三闸：范式词（同段≥2 另记）、文学化、小动作→类型。台词位置被遮罩。 */
    private fun textChecks(profile: PersonaGestureProfile): List<CheckItem> {
        val out = mutableListOf<CheckItem>()
        profile.beats.groupBy { it.paragraph }.forEach { (paragraph, beats) ->
            val narrator = beats.joinToString(" ") { maskDialogue(it.text) }
            val hits = GestureCatalog.BLACKLIST.flatMap { entry ->
                List(occurrences(narrator, entry.phrase)) { entry.phrase }
            }
            if (hits.isNotEmpty()) {
                out += err("GESTURE_CLIHE_NARRATOR", "第 $paragraph 段叙述范式：${hits.joinToString("/")}")
            }
            if (hits.size >= 2) {
                out += err("GESTURE_CLIHE_REPEAT", "第 $paragraph 段范式动作 ${hits.size} 次（同段≥2）")
            }
            GestureCatalog.LITERARY.filter { it in narrator }.forEach { phrase ->
                out += err("GESTURE_LITERARY", "第 $paragraph 段文学化形容：$phrase")
            }
            if (SYMBOL_PATTERN.containsMatchIn(narrator)) {
                out += err("GESTURE_SYMBOLIC", "第 $paragraph 段用动作给人物贴类型标签")
            }
        }
        return out
    }

    /** 与 930 的 positionAt 同一思路：完整引号与引导式直接引语按台词处理，其余按叙述。 */
    private fun maskDialogue(text: String): String {
        val chars = text.toCharArray()
        QUOTED.findAll(text).forEach { match -> blank(chars, match.range.first, match.range.last + 1) }
        DIRECT_SPEECH.findAll(text).forEach { match ->
            val start = match.range.last + 1
            val end = SENTENCE_BREAKS.asIterable()
                .mapNotNull { ch -> text.indexOf(ch, start).takeIf { index -> index >= 0 } }
                .minOrNull() ?: text.length
            blank(chars, start, end)
        }
        return String(chars)
    }

    private fun blank(chars: CharArray, start: Int, end: Int) {
        for (index in start until end.coerceAtMost(chars.size)) chars[index] = ' '
    }

    private fun occurrences(text: String, phrase: String): Int {
        val source = text.lowercase()
        val needle = phrase.lowercase()
        var from = 0
        var count = 0
        while (true) {
            val hit = source.indexOf(needle, from)
            if (hit < 0) return count
            count++
            from = hit + needle.length
        }
    }

    private fun symbolPattern(): Regex {
        val labels = GestureCatalog.SYMBOL_LABELS.joinToString("|")
        val cues = GestureCatalog.SYMBOL_CUES.joinToString("|")
        val gap = "[^。！？!?；;\\n]{0,8}"
        return Regex("(?:$cues)$gap(?:$labels)|(?:$labels)$gap(?:$cues)")
    }

    private fun err(code: String, message: String): CheckItem = CheckItem(Severity.ERROR, code, message)
}
