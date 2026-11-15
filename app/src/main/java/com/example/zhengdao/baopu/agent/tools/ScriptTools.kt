// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十一批（2026-11-15）· 剧本五件（原创＋小说改编）。
//
// 分工（判据就是这条分工本身）：
//   `script_outline`  写/更新大纲——**人批的对象**；改编必须带档位与原文来源，否则拒。
//   `script_approve`  请**用户**在大纲卡上点「批准」；只有用户点的那一下能解锁正文。
//   `script_write`    **一次一场**写正文；未批大纲、档位缺省、来源对不上、反性格冲突漏报 ⇒ 拒。
//   `script_export`   导出 Fountain ＋ 中文标准剧本（Fountain 先读回自检，读不回来不出文件）。
//   `script_trace`    抽几场点回原文（把原文行原样贴出来，带行号）。
//
// 三条边界：
//   1. **不碰 .fdx、不做 PDF 排版**（本批范围之外的格式一个都不写）；
//   2. 真源是 `场次.jsonl`：导出是投影，导出过程绝不回写工程；
//   3. 批准只认用户在卡上的那一下——approve 的 `who` 是卡片回答换来的常量，
//      模型给的任何参数都到不了它。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.UserPath
import com.example.zhengdao.baopu.office.Step
import com.example.zhengdao.baopu.script.CharBible
import com.example.zhengdao.baopu.script.ChineseScriptFormat
import com.example.zhengdao.baopu.script.FountainIn
import com.example.zhengdao.baopu.script.FountainOut
import com.example.zhengdao.baopu.script.FountainSelfCheck
import com.example.zhengdao.baopu.script.NovelSource
import com.example.zhengdao.baopu.script.Outline
import com.example.zhengdao.baopu.script.Parsed
import com.example.zhengdao.baopu.script.ScriptFormat
import com.example.zhengdao.baopu.script.ScriptMode
import com.example.zhengdao.baopu.script.ScriptOutlineRules
import com.example.zhengdao.baopu.script.ScriptProject
import com.example.zhengdao.baopu.script.ScriptStore
import com.example.zhengdao.baopu.script.ScriptTier
import com.example.zhengdao.baopu.script.Scene
import com.example.zhengdao.baopu.script.ScriptWriteRules
import com.example.zhengdao.baopu.script.WHO_USER
import com.example.zhengdao.core.TimeFormats
import java.io.File
import org.json.JSONObject

// ── 共用小件 ───────────────────────────────────────────────────────────────

/** 工程目录 + 项目头（大多数工具的第一步；取不到就带着人话回去）。 */
private class ProjectCtx(val dir: File, val project: ScriptProject)

private fun ctxOf(wsRoot: File, rawProject: String): Parsed<ProjectCtx> {
    val dir = when (val d = ScriptStore.dirOf(wsRoot, rawProject)) {
        is Parsed.Bad -> return d
        is Parsed.Ok -> d.value
    }
    return when (val p = ScriptStore.readProject(dir)) {
        is Parsed.Bad -> p
        is Parsed.Ok -> Parsed.Ok(ProjectCtx(dir, p.value))
    }
}

private fun outlineOf(dir: File): Parsed<Outline> = ScriptStore.readOutline(dir)

/** 改编工程才载原文（原创没有原文，也就没有"点回原文"这回事）。 */
private fun sourceOf(wsRoot: File, project: ScriptProject): Parsed<NovelSource> {
    if (project.mode != ScriptMode.ADAPT) return Parsed.Bad("这是原创工程，没有原文（改编工程才标来源）")
    val rel = project.sourceRel
    if (rel.isNullOrBlank()) return Parsed.Bad("这个改编工程没记原文路径（source）")
    return NovelSource.load(UserPath.resolve(wsRoot, rel))
}

// ── script_outline ─────────────────────────────────────────────────────────

internal object ScriptOutlineTool {

    const val NAME = "script_outline"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "写/更新一部剧本的大纲（这是**人批的对象**，批了才能写正文）。" +
            "参数：project 片名；scenes 每场一句戏核 {summary, source?}；characters 人物表 " +
            "[{name, traits[], forbids[{phrase, source}]}]。" +
            "原创 mode=original；小说改编 mode=adapt，另给 source（原文路径）与 tier：" +
            "faithful 忠于原文 / rework 适度重构 / bold 大胆再创作——**必须由人选**，不给就拒（不默认任何一档）；" +
            "改编每场还要给 {chapter, from, to}（第几章、哪几行，从 1 数）。" +
            "写完不会自动开工：要把大纲念给用户，再 script_approve 请他批；之后改一个字，批准自动作废。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                "project" to jsonObjectOf("type" to "string", "description" to "片名（也是工程目录名）；也可写 剧本/片名"),
                "mode" to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf("original", "adapt"),
                    "description" to "原创 original / 小说改编 adapt（首建必给；之后与工程记的一致）",
                ),
                "tier" to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf("faithful", "rework", "bold"),
                    "description" to "改编幅度：faithful 忠于原文 / rework 适度重构 / bold 大胆再创作。改编必给，缺省不接受",
                ),
                "source" to jsonObjectOf(
                    "type" to "string",
                    "description" to "改编的原文路径（.txt/.md/.docx/.doc；相对工作区或绝对路径）",
                ),
                "scenes" to jsonObjectOf(
                    "type" to "array",
                    "description" to "场次：每场一句戏核；id 可省（自动 S01…），给就给 S01 这种",
                    "items" to jsonObjectOf(
                        "type" to "object",
                        "properties" to jsonObjectOf(
                            "id" to jsonObjectOf("type" to "string"),
                            "summary" to jsonObjectOf("type" to "string", "description" to "一句戏核（≤200 字）"),
                            "source" to jsonObjectOf(
                                "type" to "object",
                                "description" to "改编必给：这一场对应原文 {chapter, from, to}",
                                "properties" to jsonObjectOf(
                                    "chapter" to jsonObjectOf("type" to "integer", "description" to "第几个章节块（从 1 数）"),
                                    "from" to jsonObjectOf("type" to "integer", "description" to "起始行号（含）"),
                                    "to" to jsonObjectOf("type" to "integer", "description" to "结束行号（含）"),
                                ),
                                "required" to jsonArrayOf("chapter", "from", "to"),
                                "additionalProperties" to false,
                            ),
                        ),
                        "required" to jsonArrayOf("summary"),
                        "additionalProperties" to false,
                    ),
                ),
                "characters" to jsonObjectOf(
                    "type" to "array",
                    "description" to "人物表（改编强烈建议给 forbids：这个人不会做的事/说的话＋原文依据，写正文时据此扫反性格冲突）",
                    "items" to jsonObjectOf(
                        "type" to "object",
                        "properties" to jsonObjectOf(
                            "name" to jsonObjectOf("type" to "string"),
                            "traits" to jsonObjectOf("type" to "array", "items" to jsonObjectOf("type" to "string")),
                            "forbids" to jsonObjectOf(
                                "type" to "array",
                                "items" to jsonObjectOf(
                                    "type" to "object",
                                    "properties" to jsonObjectOf(
                                        "phrase" to jsonObjectOf("type" to "string", "description" to "这个人不会做/说的话（一句短语）"),
                                        "source" to jsonObjectOf("type" to "string", "description" to "原文依据（如 第3章 L120）"),
                                    ),
                                    "required" to jsonArrayOf("phrase"),
                                    "additionalProperties" to false,
                                ),
                            ),
                        ),
                        "required" to jsonArrayOf("name"),
                        "additionalProperties" to false,
                    ),
                ),
            ),
            "required" to jsonArrayOf("project", "scenes"),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        riskTier = "R1", // 只写工作区 剧本/ 下的工程文件，不动用户既有文件、不联网
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot(), System.currentTimeMillis()) }

    fun run(args: JSONObject, wsRoot: File, nowMs: Long): ToolOutcome = guarded {
        val dir = valueOf(ScriptStore.dirOf(wsRoot, args.optString("project")))
        val existing = (ScriptStore.readProject(dir) as? Parsed.Ok)?.value
        val mode = valueOf(modeOf(args, existing))
        if (existing != null && existing.mode != mode) {
            abort(
                "这个工程已经是「${existing.mode.zh}」：模式不能中途改（" +
                    "${existing.mode.key} → ${mode.key}）。要换模式请换个片名另建工程",
            )
        }
        val tier = valueOf(tierOf(args, mode))
        val sourceRaw = args.optString("source").trim().ifEmpty { existing?.sourceRel.orEmpty() }
        val source = valueOf(outlineSourceOf(wsRoot, mode, sourceRaw))
        val scenes = valueOf(ScriptOutlineRules.parseScenes(args.optJSONArray("scenes")))
        val characters = valueOf(ScriptOutlineRules.parseCharacters(args.optJSONArray("characters")))
        val project = ScriptProject(
            title = dir.name,
            mode = mode,
            tier = tier,
            sourceRel = sourceRaw.ifEmpty { null },
            createdMs = existing?.createdMs ?: nowMs,
        )
        ScriptOutlineRules.check(project, scenes, source)?.let { abort(it) }
        if (!dir.isDirectory && !dir.mkdirs()) {
            abort("建不了工程目录：${dir.name}（权限或 IO 错误）")
        }
        if (!ScriptStore.writeProject(dir, project)) {
            abort("项目头写不进去（${ScriptFormat.PROJECT_FILE}）：权限或 IO 错误，没改成")
        }
        val outline = valueOf(ScriptStore.saveOutline(dir, scenes, characters))
        val modeText = if (mode == ScriptMode.ADAPT) "${mode.zh} · ${ScriptTier.textOf(tier)}" else mode.zh
        val approvalLine = outline.approved?.let {
            "上一次批准已**作废**（批的是 ${it.digest}，现在是 ${outline.digest}）：改过大纲就必须重批。"
        } ?: "现在**还没批**：把大纲逐场念给用户，再调 script_approve 请他点「批准」——批过才可以写正文。"
        ToolOutcome.Ok(
            "《${dir.name}》大纲 v${outline.rev} 已落盘：${scenes.size} 场（$modeText）。\n" +
                charLine(characters) + "\n" +
                "摘要码 ${outline.digest}\n" +
                "$approvalLine 之后只要改一个字（哪怕只改一句戏核），批准都会作废、要重批。",
        )
    }

    /** 模式：首建必给；之后可省（沿用工程记的）；中途改模式一律拒。 */
    private fun modeOf(args: JSONObject, existing: ScriptProject?): Parsed<ScriptMode> {
        val raw = args.optString("mode").trim()
        if (raw.isEmpty()) {
            return existing?.let { Parsed.Ok(it.mode) }
                ?: Parsed.Bad("首建工程要给 mode（原创 original / 改编 adapt）")
        }
        val mode = ScriptMode.ofKey(raw)
            ?: return Parsed.Bad("mode 不认识：$raw（可用 ${ScriptMode.keys()}）")
        return Parsed.Ok(mode)
    }

    /**
     * 档位：改编必须显式给（**缺省不兜底**）；原创给了就拒（档位只在改编时有意义）。
     */
    private fun tierOf(args: JSONObject, mode: ScriptMode): Parsed<ScriptTier?> {
        val raw = args.optString("tier").trim()
        if (raw.isEmpty()) {
            return if (mode == ScriptMode.ADAPT) {
                Parsed.Bad(
                    "改编幅度三档必须由人选（${ScriptTier.keys()}）：先说清按哪一档改，再写大纲；" +
                        "缺省不会默认「大胆再创作」",
                )
            } else {
                Parsed.Ok(null)
            }
        }
        val tier = ScriptTier.ofKey(raw)
            ?: return Parsed.Bad("tier 不认识：$raw（可用 ${ScriptTier.keys()}）")
        if (mode == ScriptMode.ORIGINAL) {
            return Parsed.Bad("原创不需要改编幅度（tier）：档位只在改编时有意义")
        }
        return Parsed.Ok(tier)
    }

    /** 改编要读原文（每场都要标章＋行）；原创不读。 */
    private fun outlineSourceOf(wsRoot: File, mode: ScriptMode, sourceRaw: String): Parsed<NovelSource?> {
        if (mode != ScriptMode.ADAPT) return Parsed.Ok(null)
        if (sourceRaw.isEmpty()) {
            return Parsed.Bad("改编要给原文路径 source：每场都要标「第几章 哪几行」，没有原文标不出来")
        }
        return when (val loaded = NovelSource.load(UserPath.resolve(wsRoot, sourceRaw))) {
            is Parsed.Bad -> loaded
            is Parsed.Ok -> Parsed.Ok(loaded.value)
        }
    }

    private fun charLine(characters: List<CharBible>): String {
        if (characters.isEmpty()) {
            return "人物表：空（改编时没有人物禁区，写正文时就扫不出反性格冲突——只能靠自觉）"
        }
        return "人物：" + characters.joinToString("、") { c ->
            c.name + if (c.forbids.isEmpty()) "" else "（禁区 ${c.forbids.size} 条）"
        }
    }
}

internal object ScriptApproveTool {

    const val NAME = "script_approve"

    /** 批准卡上的选项 id（只有这个算批过）。 */
    const val OPT_APPROVE = "approve"

    const val OPT_LATER = "later"

    fun spec(
        workspaceRoot: () -> File,
        ask: ((AskUserRequest) -> AskUserOption?)? = null,
    ): ToolSpec = ToolSpec(
        name = NAME,
        description = "请**用户**批准这部片的大纲：弹一张卡给用户看（片名/档位/场数/每场戏核/摘要码），" +
            "他点「批准」才算批过；点别的、或没界面，都算没批（不会替他点）。" +
            "批准之后 script_write 才能写正文；大纲一改，批准自动作废，要重新批。" +
            "把大纲念完再调它，别在用户没看过的情况下直接弹。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                "project" to jsonObjectOf("type" to "string", "description" to "片名（或 剧本/片名）"),
            ),
            "required" to jsonArrayOf("project"),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE, // 变的是工程状态；真正的闸是用户在那张卡上的手
        riskTier = "R1",
        readOnly = false,
        timeoutMs = ToolTimeout.CONFIRM_MS, // 等人点卡（与 ask_user 同一条时限）
    ) { args ->
        run(args, workspaceRoot(), ask ?: { AskUserTool.sink?.invoke(it) }, System.currentTimeMillis())
    }

    fun run(
        args: JSONObject,
        wsRoot: File,
        ask: ((AskUserRequest) -> AskUserOption?)?,
        nowMs: Long,
    ): ToolOutcome = guarded {
        val ctx = valueOf(ctxOf(wsRoot, args.optString("project")))
        val outline = valueOf(outlineOf(ctx.dir))
        if (ask == null) {
            abort("现在没有界面能弹批准卡（后台运行时不支持）：如实告诉用户，别当成批过了")
        }
        val detail = buildString {
            append("档位：")
                .append(if (ctx.project.mode == ScriptMode.ADAPT) ScriptTier.textOf(ctx.project.tier) else "原创（无档位）")
                .append('\n')
            append("场数：").append(outline.scenes.size).append(" 场\n")
            outline.scenes.take(12).forEach { append("· ").append(it.id).append(' ').append(it.summary).append('\n') }
            if (outline.scenes.size > 12) append("…（还有 ").append(outline.scenes.size - 12).append(" 场）\n")
            append("摘要码 ").append(outline.digest)
        }
        val picked = ask(
            AskUserRequest(
                question = "《${ctx.dir.name}》大纲 v${outline.rev}：批准后才可以写正文？",
                options = listOf(
                    AskUserOption(OPT_APPROVE, "批准", "按这份大纲开工", recommended = true),
                    AskUserOption(OPT_LATER, "先不改", "大纲还要调，暂不批"),
                ),
                detailText = detail,
            ),
        ) ?: abort("用户没有回答（超时或取消了）：这次不算批过，如实说，别自己往下写")
        if (picked.id != OPT_APPROVE) {
            return@guarded ToolOutcome.Ok("用户选了「${picked.label}」：**还没批**。按他的意见改大纲，改完再请他批。")
        }
        val stamped = valueOf(ScriptStore.approve(ctx.dir, WHO_USER, nowMs))
        ToolOutcome.Ok(
            "用户批准了《${ctx.dir.name}》大纲 v${stamped.rev}（摘要码 ${stamped.digest}）：" +
                "现在可以 script_write 一场一场写了。",
        )
    }
}

internal object ScriptWriteTool {

    const val NAME = "script_write"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "写**一场**正文（一次一场：给 scenes 数组会被直接拒）。" +
            "scene = {id 或 no, location, time, interior?, characters[], " +
            "beats[{kind: action/dialogue/transition, text, who?, paren?, src?}], conflicts[]?, source?}。" +
            "前提：大纲已由**用户**批准（未批、或批过又改了 ⇒ 拒写）。" +
            "改编每场都要能对上原文（章+行）；人物做了人物表禁区里的事，必须在 conflicts 里写清 " +
            "{who, source, here, why}，漏了会拒写（不许静默改）。" +
            "已写过的场要重写给 rewrite=true（旧行留档）。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                "project" to jsonObjectOf("type" to "string", "description" to "片名（或 剧本/片名）"),
                "rewrite" to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "这一场已经写过、要重写就 true（旧行留在场次账里）",
                ),
                "scene" to jsonObjectOf(
                    "type" to "object",
                    "description" to "一场（只能一个对象）",
                    "properties" to jsonObjectOf(
                        "id" to jsonObjectOf("type" to "string", "description" to "S01 这种场号（与 no 二选一）"),
                        "no" to jsonObjectOf("type" to "integer", "description" to "第几场（与 id 二选一）"),
                        "location" to jsonObjectOf("type" to "string", "description" to "地点，如 咖啡馆"),
                        "time" to jsonObjectOf("type" to "string", "description" to "时间，如 夜 / 清晨"),
                        "interior" to jsonObjectOf("type" to "boolean", "description" to "内景 true（缺省）/ 外景 false"),
                        "characters" to jsonObjectOf("type" to "array", "items" to jsonObjectOf("type" to "string")),
                        "beats" to jsonObjectOf(
                            "type" to "array",
                            "items" to jsonObjectOf(
                                "type" to "object",
                                "properties" to jsonObjectOf(
                                    "kind" to jsonObjectOf(
                                        "type" to "string",
                                        "enum" to jsonArrayOf("action", "dialogue", "transition"),
                                    ),
                                    "text" to jsonObjectOf("type" to "string"),
                                    "who" to jsonObjectOf(
                                        "type" to "string",
                                        "description" to "对白必给：谁说的（要在 characters 里）",
                                    ),
                                    "paren" to jsonObjectOf("type" to "string", "description" to "括号提示，如 低声"),
                                    "src" to jsonObjectOf(
                                        "type" to "integer",
                                        "description" to "这一拍对应原文行号（可选，要在本场来源区间内）",
                                    ),
                                ),
                                "required" to jsonArrayOf("kind", "text"),
                                "additionalProperties" to false,
                            ),
                        ),
                        "source" to jsonObjectOf(
                            "type" to "object",
                            "description" to "本场原文区间（可选；给了必须在人批大纲给的范围之内）",
                            "properties" to jsonObjectOf(
                                "chapter" to jsonObjectOf("type" to "integer"),
                                "from" to jsonObjectOf("type" to "integer"),
                                "to" to jsonObjectOf("type" to "integer"),
                            ),
                            "required" to jsonArrayOf("chapter", "from", "to"),
                            "additionalProperties" to false,
                        ),
                        "conflicts" to jsonObjectOf(
                            "type" to "array",
                            "description" to "人物言行与原作相冲的地方，逐条写清（没有就空着）",
                            "items" to jsonObjectOf(
                                "type" to "object",
                                "properties" to jsonObjectOf(
                                    "who" to jsonObjectOf("type" to "string"),
                                    "source" to jsonObjectOf("type" to "string", "description" to "原文依据（章/行或原话）"),
                                    "here" to jsonObjectOf("type" to "string", "description" to "剧本里怎么写的"),
                                    "why" to jsonObjectOf("type" to "string", "description" to "为什么这么改"),
                                ),
                                "required" to jsonArrayOf("who", "source", "here", "why"),
                                "additionalProperties" to false,
                            ),
                        ),
                    ),
                    "required" to jsonArrayOf("location", "time", "beats"),
                    "additionalProperties" to false,
                ),
            ),
            "required" to jsonArrayOf("project", "scene"),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        riskTier = "R1",
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot(), System.currentTimeMillis()) }

    fun run(args: JSONObject, wsRoot: File, nowMs: Long): ToolOutcome = guarded {
        ScriptWriteRules.batchProblem(args)?.let { abort(it) }
        val ctx = valueOf(ctxOf(wsRoot, args.optString("project")))
        val outline = valueOf(outlineOf(ctx.dir))
        requireApproved(outline) // 判据③：未批（或批过又改了）⇒ 不写正文。
        requireTier(ctx.project) // 判据④：改编必须有档位，且不许兜底。
        val rawScene = args.optJSONObject("scene")
            ?: abort("缺 scene：一次写一场（scene 是一个对象，不是数组）")
        val source = if (ctx.project.mode == ScriptMode.ADAPT) {
            valueOf(sourceOf(wsRoot, ctx.project))
        } else {
            null
        }
        val rewrite = args.optBoolean("rewrite", false)
        val scene = valueOf(
            ScriptWriteRules.parseScene(rawScene, outline, source, ctx.project.tier, nowMs, rewrite),
        )
        val written = valueOf(ScriptStore.readScenes(ctx.dir))
        val existing = written.firstOrNull { it.id == scene.id }
        if (existing != null && !rewrite) {
            abort(
                "这一场已经写过了（${scene.id}，${existing.beats.size} 拍）：要重写给 rewrite=true，" +
                    "旧的那一版会留在场次账里（append-only，不删历史）",
            )
        }
        scene.source?.let { ref -> source?.check(ref)?.let { abort("来源对不上原文：$it") } }
        if (!ScriptStore.appendScene(ctx.dir, scene)) {
            abort("场次账写不进去（盘上原有内容没动）——如实说，别当写成了")
        }
        val left = outline.scenes.map { it.id } - (written.map { it.id }.toSet() + scene.id)
        ToolOutcome.Ok(
            buildString {
                append("已写第 ${scene.no} 场（${scene.id}）：").append(scene.heading())
                append("，").append(scene.beats.size).append(" 拍")
                scene.source?.let { append("，来源 ").append(it.text()) }
                if (rewrite) append("（重写：旧版本留在场次账里）")
                append('\n')
                if (scene.conflicts.isEmpty()) {
                    append("冲突项：无\n")
                } else {
                    append("冲突项 ").append(scene.conflicts.size).append(" 条（人物言行与原作相冲，已逐条记进这一场）：\n")
                    scene.conflicts.forEach { append("  · ").append(it.text()).append('\n') }
                }
                if (left.isEmpty()) {
                    append("大纲里的场都写完了，可以 script_export 导出。")
                } else {
                    append("还差 ").append(left.size).append(" 场：").append(left.joinToString("、"))
                }
            },
        )
    }

    /**
     * 判据③：大纲没批、或批过之后又改了 ⇒ 一个字都不写。
     *
     * 为什么单独成函数：这条闸要说两句不同的人话（"还没批" vs "批过又改了，批的是哪一版"），
     * 摆在 run 里就是四个分支；拆出来 run 只留一次调用。
     */
    private fun requireApproved(outline: Outline) {
        val approval = outline.approved
            ?: abort("大纲还没批：先把大纲念给用户，script_approve 请他点「批准」，批过再写正文。这次一个字都没写。")
        if (!outline.approvalValid()) {
            abort(
                "大纲在批准之后又改过（批的是 ${approval.digest}，现在是 ${outline.digest}）：" +
                    "批准已作废，要重新请用户批（script_approve）。这次一个字都没写。",
            )
        }
    }

    /** 判据④：改编工程必须已经选了档位（`ScriptTier.ofKey("")` 回 null，不许兜底）。 */
    private fun requireTier(project: ScriptProject) {
        if (project.mode == ScriptMode.ADAPT && project.tier == null) {
            abort(
                "这个改编工程没有选档位（${ScriptTier.keys()}）：缺省不许默认「大胆再创作」，" +
                    "先问用户选哪一档并写进大纲",
            )
        }
    }
}

internal object ScriptExportTool {

    const val NAME = "script_export"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "把已写的场导出两份：Fountain（.fountain，机器格式，能被 Fountain 解析器读）" +
            "与中文标准剧本（.md）。只落 产物/，同名不覆盖。" +
            "导出前会用 Fountain 读回器自检：读不回来就不出文件（宁可不出，也不出一份读不回来的）。" +
            "改编工程另出一份 -溯源.md（每场对应第几章哪几行、冲突项、还没写的场）。" +
            "不做 .fdx、不做 PDF 排版。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                "project" to jsonObjectOf("type" to "string", "description" to "片名（或 剧本/片名）"),
                "name" to jsonObjectOf("type" to "string", "description" to "产物文件名基（缺省用片名）"),
            ),
            "required" to jsonArrayOf("project"),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        riskTier = "R1",
        readOnly = false,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot(), System.currentTimeMillis()) }

    fun run(args: JSONObject, wsRoot: File, nowMs: Long): ToolOutcome = guarded {
        val ctx = valueOf(ctxOf(wsRoot, args.optString("project")))
        val outline = valueOf(outlineOf(ctx.dir))
        val scenes = valueOf(ScriptStore.readScenes(ctx.dir))
        if (scenes.isEmpty()) abort("一场都还没写，导出没有内容（先 script_write 写一场）")
        val missing = outline.scenes.map { it.id }.filter { id -> scenes.none { it.id == id } }
        val stamp = TimeFormats.fileStamp(nowMs)
        val base = OfficeSink.baseOf(args.optString("name").ifEmpty { ctx.project.title }, "剧本")
        val fountain = FountainOut.write(ctx.project.title, scenes, TimeFormats.dayStamp(nowMs))
        // 判据①：先按规范读回来（读不回来就不出文件）。
        when (val back = FountainIn.parse(fountain)) {
            is Parsed.Bad -> abort("Fountain 读回失败（这次不出文件）：${back.reason}")
            is Parsed.Ok -> FountainSelfCheck.compare(scenes, back.value)?.let {
                abort("Fountain 读回自检没过（这次不出文件）：$it")
            }
        }
        val fountainFile = when (
            val w = OfficeSink.write(wsRoot, base, "fountain", fountain.toByteArray(Charsets.UTF_8), stamp)
        ) {
            is Step.Bad -> abort(w.reason)
            is Step.Ok -> w.value
        }
        val shownFountain = OfficeSink.record(NAME, wsRoot, fountainFile, "剧本导出 Fountain")
        val chinese = ChineseScriptFormat.render(ctx.project.title, scenes, ctx.project.mode, ctx.project.tier)
        val chineseFile = when (
            val w = OfficeSink.write(wsRoot, base, "md", chinese.toByteArray(Charsets.UTF_8), stamp)
        ) {
            is Step.Bad -> abort(w.reason)
            is Step.Ok -> w.value
        }
        val shownChinese = OfficeSink.record(NAME, wsRoot, chineseFile, "剧本导出 中文标准剧本")
        var traceLine = ""
        if (ctx.project.mode == ScriptMode.ADAPT) {
            val sourceName = ctx.project.sourceRel.orEmpty().substringAfterLast('/')
            val trace = ChineseScriptFormat.traceTable(ctx.project.title, sourceName, scenes, outline.scenes, missing)
            val traceFile = when (
                val w = OfficeSink.write(wsRoot, "$base-溯源", "md", trace.toByteArray(Charsets.UTF_8), stamp)
            ) {
                is Step.Bad -> abort(w.reason)
                is Step.Ok -> w.value
            }
            traceLine = "\n溯源表：" + OfficeSink.record(NAME, wsRoot, traceFile, "剧本溯源表")
        }
        val tail = if (missing.isEmpty()) {
            "大纲 ${outline.scenes.size} 场都写完了。"
        } else {
            "注意：大纲还有 ${missing.size} 场没写（${missing.joinToString("、")}），这两份只有已写的场。"
        }
        ToolOutcome.Ok(
            "已导出 ${scenes.size} 场：\nFountain：$shownFountain\n中文标准剧本：$shownChinese$traceLine\n$tail",
        )
    }
}

internal object ScriptTraceTool {

    const val NAME = "script_trace"

    /** 一次最多抽几场（再多就该分几次，别把原文哗哗贴进上下文）。 */
    const val MAX_SAMPLE = 5

    /** 一场最多贴多少行原文（超了截断并明说截了）。 */
    const val MAX_LINES = 60

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = NAME,
        description = "抽几场点回原文：给 scenes（场号数组）或 sample（默认 3 场＝第一场/中间/最后一场），" +
            "把每场对应的原文行**原样**贴出来（带 L 行号），并列出这一场的拍数与冲突项。" +
            "用来核对「剧本这一场到底是从原文哪几行来的」，不是让模型复述。改编工程才有原文；" +
            "原创会如实说没有。一次最多 $MAX_SAMPLE 场、每场最多 $MAX_LINES 行。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                "project" to jsonObjectOf("type" to "string", "description" to "片名（或 剧本/片名）"),
                "scenes" to jsonObjectOf(
                    "type" to "array",
                    "items" to jsonObjectOf("type" to "string"),
                    "description" to "要抽的场号（S01…）；不给就按 sample 抽",
                ),
                "sample" to jsonObjectOf("type" to "integer", "description" to "抽几场（1–$MAX_SAMPLE，缺省 3）"),
            ),
            "required" to jsonArrayOf("project"),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        riskTier = "R0",
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot()) }
    fun run(args: JSONObject, wsRoot: File): ToolOutcome = guarded {
        val ctx = valueOf(ctxOf(wsRoot, args.optString("project")))
        val outline = valueOf(outlineOf(ctx.dir))
        val source = valueOf(sourceOf(wsRoot, ctx.project))
        val written = valueOf(ScriptStore.readScenes(ctx.dir))
        val wanted = wantedScenes(args, outline)
        if (wanted.size > MAX_SAMPLE) {
            abort("一次最多抽 $MAX_SAMPLE 场（这次给了 ${wanted.size} 场）：分几次抽，或按 sample 抽")
        }
        val sb = StringBuilder()
        sb.append("《").append(ctx.dir.name).append("》原文：").append(source.file.name)
            .append("（").append(source.kind).append("，共 ").append(source.lineCount).append(" 行）\n")
        for (id in wanted) {
            appendSceneTrace(sb, id, outline, written, source)
        }
        ToolOutcome.Ok(sb.toString().trimEnd())
    }

    /** 要抽哪几场：给了 scenes 就按给的；没给就按 sample 等距抽（第一场与最后一场一定在内）。 */
    private fun wantedScenes(args: JSONObject, outline: Outline): List<String> {
        val given = ArrayList<String>()
        args.optJSONArray("scenes")?.let { a ->
            for (i in 0 until a.length()) a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { given += it }
        }
        if (given.isNotEmpty()) return given
        val sample = args.optInt("sample", 3).coerceIn(1, MAX_SAMPLE)
        val ids = outline.scenes.map { it.id }
        if (ids.isEmpty()) abort("大纲里一场都没有")
        if (ids.size <= sample) return ids
        val picks = LinkedHashSet<String>()
        picks += ids.first()
        picks += ids.last()
        if (sample > 2) {
            for (k in 1 until sample - 1) picks += ids[(k * (ids.size - 1)) / (sample - 1)]
        }
        return picks.take(sample).toList()
    }

    /** 一场的溯源块：场号／戏核／来源章行／写没写／冲突项／原文行（原样带行号）。 */
    private fun appendSceneTrace(
        sb: StringBuilder,
        id: String,
        outline: Outline,
        written: List<Scene>,
        source: NovelSource,
    ) {
        val outlineScene = outline.sceneOf(id)
            ?: abort("大纲里没有 $id（现有：" + outline.scenes.joinToString("、") { it.id } + "）")
        val ref = outlineScene.source ?: abort("$id 在大纲里没标来源（改编每场都要标，先改大纲）")
        source.check(ref)?.let { abort("$id 的来源对不上原文：$it") }
        val scene = written.firstOrNull { it.id == id }
        sb.append("\n— ").append(id).append("　").append(outlineScene.summary).append('\n')
        sb.append("  来源 ").append(ref.text())
            .append("（").append(source.chapter(ref.chapter)?.title.orEmpty()).append("）\n")
        if (scene == null) {
            sb.append("  还没写（大纲有、正文没有）\n")
        } else {
            sb.append("  已写：").append(scene.heading()).append("　").append(scene.beats.size).append(" 拍")
            sb.append(if (scene.conflicts.isEmpty()) "　冲突项：无\n" else "　冲突项 ${scene.conflicts.size} 条\n")
            scene.conflicts.forEach { c -> sb.append("    · ").append(c.text()).append('\n') }
        }
        val lines = source.excerpt(ref.from, ref.to)
        if (lines.size <= MAX_LINES) {
            sb.append("  原文：\n")
            lines.forEach { sb.append("    ").append(it).append('\n') }
        } else {
            sb.append("  原文（").append(ref.from).append("–").append(ref.to)
                .append("，只贴前 ").append(MAX_LINES).append(" 行）：\n")
            lines.take(MAX_LINES).forEach { sb.append("    ").append(it).append('\n') }
            sb.append("    …（还有 ").append(lines.size - MAX_LINES).append(" 行没贴）\n")
        }
    }
}


/**
 * 把域层 [Parsed] 的「要么值要么一句人话」折成一条出口。
 *
 * 为什么不每个取数点都写 `when (...) { is Bad -> return Failed; is Ok -> value }`：
 * 每个取数点两枝，一个工具五六个取数点就把圈复杂度顶到 20+（detekt 硬墙 15）。
 * [abort] 抛出、[guarded] 在最外层捕一次，错误消息**原样是域层那句人话**。
 */
private class ScriptAbort(val reason: String) : RuntimeException(reason)

private fun <T> valueOf(parsed: Parsed<T>): T = when (parsed) {
    is Parsed.Ok -> parsed.value
    is Parsed.Bad -> throw ScriptAbort(parsed.reason)
}

/** 工具层的「就此打住，把这句人话回给模型」。 */
private fun abort(message: String): Nothing = throw ScriptAbort(message)

/** 工具执行器的唯一出口：域层的每一次 abort 都从这里变成一条 [ToolOutcome.Failed]。 */
private inline fun guarded(block: () -> ToolOutcome): ToolOutcome = try {
    block()
} catch (e: ScriptAbort) {
    ToolOutcome.Failed(e.reason)
}
