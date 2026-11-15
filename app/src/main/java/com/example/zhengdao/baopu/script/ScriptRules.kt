// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十一批 · 剧本判据（**纯函数**：不碰磁盘、不碰 Context、不碰 Compose）。
//
// 判据逐条对应用户派工单：
//   ① 大纲：场景数有上限、一句戏核不能空、id 不能重、改编必须每场标来源（章＋行）且**真能解析**；
//   ② 一场一次：`scenes` 数组（或任何"一次多场"的形状）当场拒，连场数都报出来；
//   ③ 未批大纲不许写正文：`approved` 为空、或 digest 与现内容不一致（批了之后又改了）一律拒；
//   ④ 档位缺省不许兜底：改编模式下 tier 为空就拒，并说清三档各是什么；
//   ⑤ 反性格冲突必须报：拍了人物禁区里的话/事，`conflicts` 里没有对应项 ⇒ 拒写（**静默通过是红**）；
//      忠于原文档更进一步：出现冲突项就拒（那一档不许偏离，要么改写要么让用户换档）。
package com.example.zhengdao.baopu.script

import org.json.JSONArray
import org.json.JSONObject

internal object ScriptOutlineRules {

    /** 一句话戏核的字数上限（超过就不是"一句"。 */
    const val MAX_SUMMARY_CHARS = 200

    /** 人物上限（再多人名就进不了上下文了）。 */
    const val MAX_CHARACTERS = 40

    /**
     * 解析大纲的场次数组（**纯函数**；`id` 缺省按序号补 `S01`…）。
     *
     * 为什么要补 id：模型常常只给"一句戏核"的清单；让它自己编 id 会编出 `1`、`第一场`、
     * `S1` 三种写法，正文按 id 对大纲就对不上。补号在这里一处，全仓只认 `S01` 这一种形状。
     */
    fun parseScenes(raw: JSONArray?): Parsed<List<OutlineScene>> {
        if (raw == null || raw.length() == 0) {
            return Parsed.Bad("scenes 不能为空：大纲至少要有一场（一场一句戏核）")
        }
        if (raw.length() > ScriptFormat.MAX_SCENES) {
            return Parsed.Bad("场次太多：${raw.length()} 场 > 上限 ${ScriptFormat.MAX_SCENES} 场（再多请拆成两部/一季多集）")
        }
        val out = ArrayList<OutlineScene>(raw.length())
        for (i in 0 until raw.length()) {
            val o = raw.optJSONObject(i) ?: return Parsed.Bad("scenes[$i] 不是对象：每场给 {id?, summary, source?}")
            val summary = o.optString("summary").trim()
            if (summary.isEmpty()) return Parsed.Bad("scenes[$i] 缺 summary（一句戏核）")
            if (summary.length > MAX_SUMMARY_CHARS) {
                return Parsed.Bad("scenes[$i] 的 summary 有 ${summary.length} 字，超过 $MAX_SUMMARY_CHARS 字（一行戏核，不是整段剧情）")
            }
            val rawId = o.optString("id").trim()
            val id = rawId.ifEmpty { ScriptFormat.idOf(i + 1) }
            if (!id.matches(Regex("""S\d{2,3}"""))) {
                return Parsed.Bad("scenes[$i] 的 id「$id」不合形状：要么不给（自动 S01…），要么给 S01 这种")
            }
            val ref = o.optJSONObject("source")?.let { s ->
                SourceRef.of(s) ?: return Parsed.Bad(
                    "scenes[$i].source 形状不对：要给 {chapter, from, to} 三个正整数（章与行都从 1 数）",
                )
            }
            out += OutlineScene(id, summary, ref)
        }
        val dup = out.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) return Parsed.Bad("场次 id 重复：${dup.joinToString("、")}")
        return Parsed.Ok(out)
    }

    /** 解析人物设定（名字必填、不许重名；禁区的 `source` 缺省记「作者设定」）。 */
    fun parseCharacters(raw: JSONArray?): Parsed<List<CharBible>> {
        if (raw == null) return Parsed.Ok(emptyList())
        if (raw.length() > MAX_CHARACTERS) {
            return Parsed.Bad("人物太多：${raw.length()} 个 > 上限 $MAX_CHARACTERS 个")
        }
        val out = ArrayList<CharBible>(raw.length())
        for (i in 0 until raw.length()) {
            val o = raw.optJSONObject(i) ?: return Parsed.Bad("characters[$i] 不是对象")
            val name = o.optString("name").trim()
            if (name.isEmpty()) return Parsed.Bad("characters[$i] 缺 name")
            val traits = ArrayList<String>()
            o.optJSONArray("traits")?.let { a ->
                for (j in 0 until a.length()) a.optString(j).trim().takeIf { it.isNotEmpty() }?.let { traits += it }
            }
            val forbids = ArrayList<Forbid>()
            val fArr = o.optJSONArray("forbids")
            if (fArr != null) {
                for (j in 0 until fArr.length()) {
                    val f = fArr.optJSONObject(j) ?: return Parsed.Bad("characters[$i].forbids[$j] 不是对象")
                    val phrase = f.optString("phrase").trim()
                    if (phrase.isEmpty()) {
                        return Parsed.Bad("characters[$i].forbids[$j] 缺 phrase（这个人不会做的事/说的话）")
                    }
                    forbids += Forbid(phrase, f.optString("source").trim().ifEmpty { "作者设定" })
                }
            }
            out += CharBible(name, traits, forbids)
        }
        val dup = out.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (dup.isNotEmpty()) return Parsed.Bad("人物重名：${dup.joinToString("、")}")
        return Parsed.Ok(out)
    }

    /**
     * 大纲与项目/原文的对账（null ＝通过）。
     *
     * @param source 改编模式下的原文坐标系（原创模式传 null）
     */
    fun check(
        project: ScriptProject,
        scenes: List<OutlineScene>,
        source: NovelSource?,
    ): String? {
        if (project.mode == ScriptMode.ADAPT) {
            if (project.tier == null) {
                return "改编幅度三档必须由人选（${ScriptTier.keys()}）——缺省不许默认「大胆再创作」；" +
                    "先问用户选哪一档，再写大纲"
            }
            if (project.sourceRel.isNullOrBlank()) {
                return "改编模式要给原文路径（source）：分场稿每场都要标「第几章 哪几行」，没有原文就标不出来"
            }
            if (source == null) return "原文读不出来，没法标来源"
            val bad = scenes.firstNotNullOfOrNull { s ->
                val ref = s.source ?: return@firstNotNullOfOrNull "${s.id} 没标来源：改编每场都要给 {chapter, from, to}"
                source.check(ref)?.let { "${s.id}：$it" }
            }
            if (bad != null) return bad
        } else {
            val withSource = scenes.firstOrNull { it.source != null }
            if (withSource != null) {
                return "${withSource.id} 标了原文来源，但这是原创工程（mode=original）：原创不往原文上对；" +
                    "要改编请把 mode 改成 adapt 并给 tier/source"
            }
        }
        return null
    }
}

internal object ScriptWriteRules {

    /** 一场戏核里的人物名单上限（与大纲人物表同量级）。 */
    const val MAX_SCENE_CHARACTERS = ScriptOutlineRules.MAX_CHARACTERS

    /**
     * **一次几场**：只看形状，不看数量意图。
     *
     * 返回 null ＝一次一场（合法）；否则给出拒因（带场数）。
     * 为什么连"1 场的数组"也拒：数组一出现就说明模型想批量写，
     * 下一次就是 5 场——闸要在形状这一层，不在"这次刚好只用了一场"的运气上。
     */
    fun batchProblem(args: JSONObject): String? {
        val sceneArr = args.optJSONArray("scene")
        if (sceneArr != null) {
            return "一次只能写一场：scene 给的是数组（${sceneArr.length()} 场）。" +
                "把它拆成单场对象，一场一轮写——批量写会绕开「一场一场对」的闸"
        }
        val arr = args.optJSONArray("scenes")
        if (arr != null) {
            return "一次只能写一场：这次给了 ${arr.length()} 场。请每轮只写一场（scene 一个对象），" +
                "写完一场再来下一场——批量写会把「一场一场对」的闸整个绕过去"
        }
        return null
    }

    /**
     * 解析并校验一场（返回**可落盘的 [Scene]**；[atMs] 由调用方给）。
     *
     * @param outline 人批过的大纲（id / 来源区间 / 人物禁区都从它来）
     * @param source  改编模式的原文坐标系（原创传 null）
     * @param tier    项目档位（原创可为 null）
     */
    fun parseScene(
        raw: JSONObject,
        outline: Outline,
        source: NovelSource?,
        tier: ScriptTier?,
        atMs: Long,
        rewrite: Boolean,
    ): Parsed<Scene> {
        val parts = when (val gathered = partsOf(raw, outline, tier)) {
            is Parsed.Bad -> return gathered
            is Parsed.Ok -> gathered.value
        }
        // 判据⑤：反性格冲突 —— 禁区命中而没列冲突 ⇒ 拒写。
        val missing = missingConflicts(parts.beats, parts.characters, outline.characters, parts.conflicts)
        if (missing.isNotEmpty()) {
            return Parsed.Bad(
                "人物言行与原作相冲，必须先列冲突项（不许静默改）：\n" +
                    missing.joinToString("\n") {
                        "  · ${it.who} 的「${it.phrase}」出现在${it.where}（原文依据：${it.source}）"
                    } +
                    "\n要么改写这一拍，要么在 conflicts 里写清 {who, source, here, why}",
            )
        }
        if (tier == ScriptTier.FAITHFUL && parts.conflicts.isNotEmpty()) {
            return Parsed.Bad(
                "这一档是「忠于原文」：不许偏离，所以不能带 ${parts.conflicts.size} 条冲突项。\n" +
                    "要么把这几拍改回原文，要么问用户把档位换成 rework（适度重构）/ bold（大胆再创作）",
            )
        }
        return Parsed.Ok(
            Scene(
                id = parts.id,
                no = parts.id.filter { it.isDigit() }.toIntOrNull() ?: 0,
                location = parts.location,
                interior = parts.interior,
                time = parts.time,
                characters = parts.characters,
                beats = parts.beats,
                conflicts = parts.conflicts,
                source = parts.ref,
                tier = tier,
                rewrite = rewrite,
                atMs = atMs,
            ),
        )
    }

    /** 一场的零件（取齐了再判，判据只认这一份——两处各取一遍必然漂）。 */
    private data class Parts(
        val id: String,
        val location: String,
        val interior: Boolean,
        val time: String,
        val characters: List<String>,
        val ref: SourceRef?,
        val beats: List<Beat>,
        val conflicts: List<Conflict>,
    )

    private fun partsOf(raw: JSONObject, outline: Outline, tier: ScriptTier?): Parsed<Parts> {
        val ident = when (val parsed = identityOf(raw, outline)) {
            is Parsed.Bad -> return parsed
            is Parsed.Ok -> parsed.value
        }
        val place = when (val parsed = placeOf(raw)) {
            is Parsed.Bad -> return parsed
            is Parsed.Ok -> parsed.value
        }
        val characters = when (val parsed = charactersOf(raw)) {
            is Parsed.Bad -> return parsed
            is Parsed.Ok -> parsed.value
        }
        val ref = when (val parsed = sourceRangeOf(raw, ident.second, tier, ident.first)) {
            is Parsed.Bad -> return parsed
            is Parsed.Ok -> parsed.value
        }
        val beats = when (val parsed = beatsOf(raw, ref, characters)) {
            is Parsed.Bad -> return parsed
            is Parsed.Ok -> parsed.value
        }
        val conflicts = when (val parsed = conflictsOf(raw)) {
            is Parsed.Bad -> return parsed
            is Parsed.Ok -> parsed.value
        }
        return Parsed.Ok(
            Parts(
                id = ident.first,
                location = place.location,
                interior = place.interior,
                time = place.time,
                characters = characters,
                ref = ref,
                beats = beats,
                conflicts = conflicts,
            ),
        )
    }

    private data class Place(val location: String, val time: String, val interior: Boolean)

    /** 场号与大纲里那一场（`id`/`no` 二选一；两个都给就必须对得上）。 */
    private fun identityOf(raw: JSONObject, outline: Outline): Parsed<Pair<String, OutlineScene>> {
        val rawId = raw.optString("id").trim()
        val no = raw.optInt("no", 0)
        val id = when {
            rawId.isNotEmpty() -> rawId
            no > 0 -> ScriptFormat.idOf(no)
            else -> return Parsed.Bad("scene 缺 id（S01）或 no（第几场）")
        }
        val outlineScene = outline.sceneOf(id)
            ?: return Parsed.Bad(
                "这一场不在人批过的大纲里：$id（大纲现有：" +
                    outline.scenes.joinToString("、") { it.id } + "）——先改大纲并重新请用户批",
            )
        if (no > 0 && ScriptFormat.idOf(no) != id) {
            return Parsed.Bad("id 与 no 对不上：id=$id，no=$no（no 的第几场要等于 id 里的号）")
        }
        return Parsed.Ok(id to outlineScene)
    }

    private fun placeOf(raw: JSONObject): Parsed<Place> {
        val location = raw.optString("location").trim()
        if (location.isEmpty()) return Parsed.Bad("scene 缺 location（地点，如「咖啡馆」）")
        if (location.length > ScriptFormat.MAX_LABEL_CHARS) {
            return Parsed.Bad("location 太长（上限 ${ScriptFormat.MAX_LABEL_CHARS} 字）")
        }
        val time = raw.optString("time").trim()
        if (time.isEmpty()) return Parsed.Bad("scene 缺 time（时间，如「夜」「清晨」）")
        if (time.length > ScriptFormat.MAX_LABEL_CHARS) {
            return Parsed.Bad("time 太长（上限 ${ScriptFormat.MAX_LABEL_CHARS} 字）")
        }
        return Parsed.Ok(Place(location, time, raw.optBoolean("interior", true)))
    }

    private fun charactersOf(raw: JSONObject): Parsed<List<String>> {
        val out = ArrayList<String>()
        raw.optJSONArray("characters")?.let { a ->
            for (i in 0 until a.length()) {
                a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }
        if (out.size > MAX_SCENE_CHARACTERS) return Parsed.Bad("这场人物太多（${out.size} 个）")
        return Parsed.Ok(out)
    }

    /**
     * 本场来源：改编以大纲那一段为准；本场可以给更细的子区间（必须落在里面）；原创不许标。
     */
    private fun sourceRangeOf(
        raw: JSONObject,
        outlineScene: OutlineScene,
        tier: ScriptTier?,
        id: String,
    ): Parsed<SourceRef?> {
        val outlineRef = outlineScene.source
        val rawRef = raw.optJSONObject("source")
            ?: return if (tier != null && outlineRef == null) {
                Parsed.Bad("改编每场都要有来源（章＋行），这一场没有：$id")
            } else {
                Parsed.Ok(outlineRef)
            }
        val given = SourceRef.of(rawRef)
            ?: return Parsed.Bad("scene.source 形状不对：要给 {chapter, from, to} 三个正整数")
        if (outlineRef == null) {
            return Parsed.Bad("大纲里这一场没标来源，正文不许自己编一个（先改大纲）")
        }
        if (given.chapter != outlineRef.chapter || given.from < outlineRef.from || given.to > outlineRef.to) {
            return Parsed.Bad(
                "本场来源 ${given.text()} 不在大纲给的范围 ${outlineRef.text()} 里：" +
                    "要改范围请先改大纲并重新请用户批",
            )
        }
        return Parsed.Ok(given)
    }

    private fun beatsOf(raw: JSONObject, ref: SourceRef?, characters: List<String>): Parsed<List<Beat>> {
        val beatArr = raw.optJSONArray("beats")
            ?: return Parsed.Bad("scene 缺 beats（本场的动作/对白/转场清单）")
        if (beatArr.length() == 0) return Parsed.Bad("beats 不能为空：一场至少要有一拍")
        if (beatArr.length() > ScriptFormat.MAX_BEATS) {
            return Parsed.Bad("一场 ${beatArr.length()} 拍 > 上限 ${ScriptFormat.MAX_BEATS} 拍（这一场该拆成两场）")
        }
        val beats = ArrayList<Beat>(beatArr.length())
        for (i in 0 until beatArr.length()) {
            val b = beatArr.optJSONObject(i) ?: return Parsed.Bad("beats[$i] 不是对象")
            val kind = BeatKind.ofKey(b.optString("kind"))
                ?: return Parsed.Bad("beats[$i].kind 不认识：${b.optString("kind")}（可用 ${BeatKind.keys()}）")
            val text = b.optString("text").trim()
            if (text.isEmpty()) return Parsed.Bad("beats[$i]（${kind.key}）缺 text")
            if (text.length > ScriptFormat.MAX_BEAT_CHARS) {
                return Parsed.Bad(
                    "beats[$i] 有 ${text.length} 字 > ${ScriptFormat.MAX_BEAT_CHARS} 字：一拍不是一段散文，请拆成几拍",
                )
            }
            val who = b.optString("who").trim().ifEmpty { null }
            if (kind == BeatKind.DIALOGUE) {
                dialogueProblem(i, text, who, characters)?.let { return it }
            }
            val src = b.optInt("src", 0).takeIf { it > 0 }
            if (src != null) {
                srcProblem(i, src, ref)?.let { return it }
            }
            beats += Beat(kind, text, who, b.optString("paren").trim().ifEmpty { null }, src)
        }
        return Parsed.Ok(beats)
    }

    /** 对白的三条硬要求（谁说的、行首不撞记号、人已列进本场）。 */
    private fun dialogueProblem(i: Int, text: String, who: String?, characters: List<String>): Parsed.Bad? {
        if (who == null) return Parsed.Bad("beats[$i] 是对白但没有 who（谁说的）")
        // 对白行在 Fountain 里没有转义记号：行首是 `>` / `@` / `(` 会被读成
        // 转场/人物/括号提示（导出就静默换了类型）。在这里挡掉，而不是导出时才失败。
        val head = text.first()
        if (head == '>' || head == '@' || head == '(') {
            return Parsed.Bad(
                "beats[$i] 的对白以「$head」开头：Fountain 会把它读成转场/人物/括号提示。" +
                    "改一下台词开头（语气提示放 paren 里）",
            )
        }
        if (who !in characters) {
            return Parsed.Bad("beats[$i] 的 who＝「$who」不在本场人物名单里（先把他/她列进 characters）")
        }
        return null
    }

    private fun srcProblem(i: Int, src: Int, ref: SourceRef?): Parsed.Bad? {
        if (ref == null) {
            return Parsed.Bad("beats[$i] 标了 src 行号，但这一场没有原文来源（原创场次不标行号）")
        }
        if (src < ref.from || src > ref.to) {
            return Parsed.Bad("beats[$i].src=$src 不在本场来源 ${ref.text()} 里")
        }
        return null
    }

    private fun conflictsOf(raw: JSONObject): Parsed<List<Conflict>> {
        val conArr = raw.optJSONArray("conflicts") ?: return Parsed.Ok(emptyList())
        val out = ArrayList<Conflict>(conArr.length())
        for (i in 0 until conArr.length()) {
            val c = conArr.optJSONObject(i) ?: return Parsed.Bad("conflicts[$i] 不是对象")
            val who = c.optString("who").trim()
            val cSource = c.optString("source").trim()
            val here = c.optString("here").trim()
            val why = c.optString("why").trim()
            if (who.isEmpty() || cSource.isEmpty() || here.isEmpty() || why.isEmpty()) {
                return Parsed.Bad(
                    "conflicts[$i] 四格都要有：who（谁）/ source（原文依据）/ here（剧本里怎么写的）/ why（为什么这么改）",
                )
            }
            out += Conflict(who, cSource, here, why)
        }
        return Parsed.Ok(out)
    }

    /** 一条漏报的冲突（[where] 是"第几拍"，报错时指得回去）。 */
    internal data class MissingConflict(val who: String, val phrase: String, val source: String, val where: String)

    /**
     * 逐拍扫禁区：命中词而 conflicts 里没有该人物的对应项 ⇒ 记一条 [MissingConflict]。
     *
     * 为什么按"人物 + 命中词"配对而不是只按人物：同一个人可以有多条禁区，
     * 只按人物配会让「列了 A 条的冲突」掩盖掉 B 条——那就是另一种静默通过。
     */
    fun missingConflicts(
        beats: List<Beat>,
        sceneCharacters: List<String>,
        bibles: List<CharBible>,
        conflicts: List<Conflict>,
    ): List<MissingConflict> {
        val out = ArrayList<MissingConflict>()
        beats.forEachIndexed { i, beat ->
            val involved = if (beat.who != null) setOf(beat.who) else sceneCharacters.toSet()
            for (bible in bibles) {
                if (bible.name !in involved) continue
                for (forbid in bible.forbids) {
                    if (!beat.text.contains(forbid.phrase)) continue
                    // 覆盖判据：conflicts 里有一条**点名这个人**、且写得下这次偏离的（here 抄了这句，
                    // 或这一拍的正文里含 here 的原话）。只点名不写清不算列过。
                    val covered = conflicts.any {
                        it.who == bible.name && (it.here.contains(forbid.phrase) || beat.text.contains(it.here))
                    }
                    if (!covered) {
                        out += MissingConflict(bible.name, forbid.phrase, forbid.source, "第 ${i + 1} 拍")
                    }
                }
            }
        }
        return out.distinctBy { it.who to it.phrase }
    }
}
