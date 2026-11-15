// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十一批 · 剧本工程的**唯一碰盘处**。
//
// 四个文件（口径写死在这里，工具层不许自己拼路径）：
//   `剧本/<片名>/project.json`   项目头：模式（原创/改编）、档位、原文路径
//   `剧本/<片名>/大纲.json`      人批的对象：场次一句戏核＋原文区间＋人物禁区
//   `剧本/<片名>/场次.jsonl`      **真源**：一行一场（同 id 后行覆盖前行，重写留痕）
//   `剧本/<片名>/.批准.jsonl`     批准流水（append-only；点号开头 ⇒ 不计入「产物 N 件」）
//
// 三条纪律：
//   1. **批准只由人写**：[approve] 的 `who` 只认 [WHO_USER]；模型没有任何工具能调到它
//      （工具层只把用户在卡上点的那一下原样传进来，见 ScriptTools 的 `ask` 注入）。
//   2. **大纲一改，批准自然失效**：[saveOutline] 每次换 rev 就清掉 approved 并重算 digest。
//   3. **场次只追加**：重写用同 id 的 `rewrite=true` 新行，旧行永远留着（回放与追责要它）。
package com.example.zhengdao.baopu.script

import java.io.File
import org.json.JSONObject

/** 项目头（`project.json`）。[tier] 只在改编时有值，且**不许**缺省兜底。 */
internal data class ScriptProject(
    val title: String,
    val mode: ScriptMode,
    val tier: ScriptTier?,
    val sourceRel: String?,
    val createdMs: Long,
) {

    fun json(): JSONObject = JSONObject()
        .put("format", ScriptFormat.FORMAT)
        .put("title", title)
        .put("mode", mode.key)
        .put("created", createdMs)
        .also { o ->
            tier?.let { o.put("tier", it.key) }
            sourceRel?.let { o.put("source", it) }
        }
}

/** 批准流水的「谁」：只认这两个值，[WHO_USER] 才能让正文开工。 */
internal const val WHO_USER = "USER"

internal const val WHO_MODEL = "MODEL"

internal object ScriptStore {

    /** 工作区里的剧本根：`<工作区>/剧本/`。 */
    fun rootDir(wsRoot: File): File = File(wsRoot, ScriptFormat.ROOT_DIR)

    /**
     * 片名合法化（null ＝不合法，给一句人话）。
     *
     * 片名同时是目录名 ⇒ 分隔符、`..`、控制字符、空白片名一律拒；不许超长。
     */
    fun titleProblem(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return "片名不能为空"
        if (t.length > ScriptFormat.MAX_TITLE_CHARS) return "片名太长（${t.length} 字，上限 ${ScriptFormat.MAX_TITLE_CHARS}）"
        if (t.split('/', '\\').any { it == ".." }) return "片名里不许有「..」"
        if (t.any { it == '/' || it == '\\' }) return "片名里不许有路径分隔符（片名会当目录名用）"
        if (t.any { it.isISOControl() }) return "片名里有控制字符"
        if (t == "." || t == "..") return "片名不能是「$t」"
        return null
    }

    /**
     * 把工具参数里的 `project` 解析成工程目录。
     *
     * 两种写法都收：`片名` 或 `剧本/片名`（都必须在 `剧本/` 之下，不许绝对路径逃逸）。
     */
    fun dirOf(wsRoot: File, rawProject: String): Parsed<File> {
        var p = rawProject.trim()
        while (p.length > 1 && (p.endsWith("/") || p.endsWith("\\"))) p = p.dropLast(1)
        if (p.isEmpty()) return Parsed.Bad("缺工程名：project 给片名（或 剧本/片名）")
        // 只认两种写法：`片名` 或 `剧本/片名`。按段拆开判，不在这里另写一遍"前缀相减"的算法
        // （那种写法全仓只许有一处，见 ConfirmCardPathDisplayTest「剥根这件事全仓只写一次」）。
        val parts = p.split('/', '\\').filter { it.isNotEmpty() }
        val title = when {
            parts.size == 1 -> parts[0]
            parts.size == 2 && parts[0] == ScriptFormat.ROOT_DIR -> parts[1]
            else -> return Parsed.Bad("工程位置只许 剧本/<片名>：$rawProject")
        }
        titleProblem(title)?.let { return Parsed.Bad(it) }
        return Parsed.Ok(File(rootDir(wsRoot), title))
    }

    fun projectFile(dir: File): File = File(dir, ScriptFormat.PROJECT_FILE)

    fun outlineFile(dir: File): File = File(dir, ScriptFormat.OUTLINE_FILE)

    fun scenesFile(dir: File): File = File(dir, ScriptFormat.SCENES_FILE)

    fun approvalsFile(dir: File): File = File(dir, ScriptFormat.APPROVALS_FILE)

    /** 读项目头（不存在 ⇒ [Parsed.Bad]，让上层说"先建工程"）。 */
    fun readProject(dir: File): Parsed<ScriptProject> {
        val f = projectFile(dir)
        if (!f.isFile) return Parsed.Bad("没有这个剧本工程：${dir.name}（先 script_outline 建大纲，工程会一起建）")
        val o = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return Parsed.Bad("${ScriptFormat.PROJECT_FILE} 不是合法 JSON（被手改坏了？）")
        if (o.optString("format") != ScriptFormat.FORMAT) {
            return Parsed.Bad("${ScriptFormat.PROJECT_FILE} 的 format 不认识：${o.optString("format")}")
        }
        val mode = ScriptMode.ofKey(o.optString("mode"))
            ?: return Parsed.Bad("${ScriptFormat.PROJECT_FILE} 的 mode 不认识（可用 ${ScriptMode.keys()}）")
        return Parsed.Ok(
            ScriptProject(
                title = o.optString("title").trim().ifEmpty { dir.name },
                mode = mode,
                tier = ScriptTier.ofKey(o.optString("tier")),
                sourceRel = o.optString("source").trim().ifEmpty { null },
                createdMs = o.optLong("created", 0L),
            ),
        )
    }

    /** 写项目头（原子写：先 `.tmp` 再改名）。 */
    fun writeProject(dir: File, project: ScriptProject): Boolean = atomicWrite(projectFile(dir), project.json().toString(2))

    /** 读大纲（不存在 ⇒ [Parsed.Bad]）。 */
    fun readOutline(dir: File): Parsed<Outline> {
        val f = outlineFile(dir)
        if (!f.isFile) return Parsed.Bad("工程「${dir.name}」还没有大纲")
        val o = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return Parsed.Bad("${ScriptFormat.OUTLINE_FILE} 不是合法 JSON（被手改坏了？）")
        return ScriptCodec.outline(o)
    }

    /**
     * 写大纲 = **换一版**：rev+1、重算 digest；上一次的批准记录**留着但立刻失效**。
     *
     * 为什么保留而不是清空：清空之后只能说「还没批」，模型与用户都不知道"其实批过、
     * 是改过才作废"；留着旧 digest，写正文时能说清「批的是 sha256:aa，现在是 sha256:bb」。
     * 失效靠 digest 比对（[Outline.approvalValid]），不靠删记录——rev 一加，digest 必变，
     * 所以「改完又改回原样」也必须重批（同一个 rev 不会重现）。
     */
    fun saveOutline(dir: File, scenes: List<OutlineScene>, characters: List<CharBible>): Parsed<Outline> {
        val previous = (readOutline(dir) as? Parsed.Ok)?.value
        val rev = previous?.rev?.plus(1) ?: 1
        val digest = ScriptCodec.digestOf(rev, scenes, characters)
        val outline = Outline(rev, scenes, characters, digest, approved = previous?.approved)
        if (!dir.isDirectory && !dir.mkdirs()) return Parsed.Bad("建不了工程目录：${dir.name}（权限或 IO 错误）")
        if (!atomicWrite(outlineFile(dir), ScriptCodec.outlineJson(outline))) {
            return Parsed.Bad("大纲写不进去（盘上仍是旧版本）")
        }
        return Parsed.Ok(outline)
    }

    /**
     * **人批**（唯一入口；`who` 不是 [WHO_USER] 直接拒）。
     *
     * 这不是"权限检查"而是"来源检查"：模型没有工具能走到这里，
     * 只有工具把用户在卡上点的那一下原样传进来，`who` 才会是 USER。
     */
    fun approve(dir: File, who: String, nowMs: Long): Parsed<Outline> {
        if (who != WHO_USER) return Parsed.Bad("批准只能由用户点：$who 不能批")
        val current = readOutline(dir)
        if (current is Parsed.Bad) return current
        val outline = (current as Parsed.Ok).value
        val approval = Approval(outline.rev, outline.digest, WHO_USER, nowMs)
        val stamped = outline.copy(approved = approval)
        if (!atomicWrite(outlineFile(dir), ScriptCodec.outlineJson(stamped))) {
            return Parsed.Bad("批准写不进盘（大纲文件写失败），这一次不算批过")
        }
        appendApproval(dir, outline.rev, outline.digest, nowMs)
        return Parsed.Ok(stamped)
    }

    /** 批准流水一行（append-only；写不进去只记日志，不影响这次批准本身的结论）。 */
    private fun appendApproval(dir: File, rev: Int, digest: String, nowMs: Long) {
        runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            val row = JSONObject()
                .put("op", "outline")
                .put("rev", rev)
                .put("digest", digest)
                .put("who", WHO_USER)
                .put("at", nowMs)
            approvalsFile(dir).appendText(row.toString() + "\n", Charsets.UTF_8)
        }
    }

    /** 批准流水（读不出来 ⇒ 空表；它只是账，不是判据）。 */
    fun approvals(dir: File): List<JSONObject> {
        val f = approvalsFile(dir)
        if (!f.isFile) return emptyList()
        return runCatching {
            f.readLines(Charsets.UTF_8).mapNotNull { line ->
                line.trim().takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 读场次（**同 id 后行覆盖前行**——重写靠这个语义；返回按场号排好）。
     *
     * 坏行不跳过：[Parsed.Bad] 带行号回去——静默跳过坏行等于让某一版正文凭空消失。
     */
    fun readScenes(dir: File): Parsed<List<Scene>> {
        val f = scenesFile(dir)
        if (!f.isFile) return Parsed.Ok(emptyList())
        val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrNull()
            ?: return Parsed.Bad("场次文件读不出（${ScriptFormat.SCENES_FILE}）")
        val byId = LinkedHashMap<String, Scene>()
        lines.forEachIndexed { i, line ->
            if (line.isBlank()) return@forEachIndexed
            when (val parsed = ScriptCodec.scene(line, i + 1)) {
                is Parsed.Bad -> return parsed
                is Parsed.Ok -> byId[parsed.value.id] = parsed.value
            }
        }
        return Parsed.Ok(byId.values.sortedBy { it.no })
    }

    /** 追加一场（真源只追加；盘上原有内容一个字节不动）。 */
    fun appendScene(dir: File, scene: Scene): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        val f = scenesFile(dir)
        val needsBreak = f.isFile && f.length() > 0 && !f.readText(Charsets.UTF_8).endsWith("\n")
        f.appendText((if (needsBreak) "\n" else "") + scene.json().toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 原子写（先 `.tmp` 再改名；半截大纲比没有大纲更坏）。 */
    fun atomicWrite(target: File, text: String): Boolean = runCatching {
        val dir = target.parentFile ?: return@runCatching false
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        if (target.exists() && !target.isFile) return@runCatching false
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (target.exists() && !target.delete()) return@runCatching false
        val ok = tmp.renameTo(target)
        if (!ok) runCatching { tmp.delete() }
        ok
    }.getOrDefault(false)
}
