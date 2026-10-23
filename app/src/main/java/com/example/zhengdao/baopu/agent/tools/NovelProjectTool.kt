// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百五十五批 · W1/W2 小说工程 v1（写小说 ＋ 续写）。派工单四条硬纪律逐条落地：
// 1. **不另造章节解析**：单文件书的目录仍是 `book_outline`（ChapterSplitter 内核）；
//    工程化项目的「章」就是 `正文/` 目录里一个文件一个章，按文件名排序，无需解析；
// 2. **写作仍走 write_file/edit_file**：本文件只有 novel_create 真写盘（只建骨架：
//    `book.json` ＋ `正文/` 目录，CONFIRM 每次一张卡）；novel_project / novel_continue
//    都是 FREE 只读，**没有任何写盘能力**（连目录都不建——建项目只走 novel_create）；
// 3. **不覆盖已有正文**：续写简报只给「append 追加 / 新开一章」两条路，
//    覆盖是 write_file 的显式参数（overwrite=true 且先备份），本工具不提供也不建议；
// 4. **长文不硬塞**：续写简报只带项目元数据＋最后一章的**末段**（有界截断），
//    整本书从不进上下文；上下文预算归 ContextAssembler/压缩管线管。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 小说项目在 工作区 里的根目录名。 */
internal const val NOVEL_ROOT_DIR = "小说"

/** 项目元数据文件名（放在 `小说/<书名>/` 下）。 */
internal const val NOVEL_META_FILE = "book.json"

/** 正文目录名（一章一个文件；按文件名排序即章节序）。 */
internal const val NOVEL_BODY_DIR = "正文"

// ── 共享内核（纯函数为主；File 读写除外，JVM 直测） ──────────────────────

/** 一个章节条目（目录/续写共用）。 */
internal data class NovelChapter(
    val fileName: String,
    val chars: Int,
    val lines: Int,
)

/** book.json 的解析结果（坏文件给可读提示，不是崩溃 ⇒ 错误放 [error] 不抛异常）。 */
internal data class NovelMeta(
    val title: String,
    val outline: String,
    val chapterTargetWords: Int,
    val characters: List<Pair<String, String>>,
    val recap: String,
    val notes: String,
    val error: String? = null,
)

internal object NovelProjectStore {

    /** 元数据格式标记（坏格式/不认识 ⇒ 可读拒绝，不猜）。 */
    const val FORMAT = "zhengdao-novel/1"

    /** 字数口径：非空白字符数（中文字数惯例；与 book_outline 的「字符数」口径区分写明）。 */
    fun wordCount(text: String): Int = text.count { !it.isWhitespace() }

    /** 项目根：`小说/<书名>/`（title 已经过 [sanitizeTitle]，不可能带路径分隔符）。 */
    fun projectDir(root: File, title: String): File = File(File(root, NOVEL_ROOT_DIR), title)

    /** 书名合法化：非法字符/超长/`..` 一律 null（返回可读原因）。 */
    fun checkTitle(title: String): String? {
        val t = title.trim()
        if (t.isEmpty()) return "书名不能为空"
        if (t.length > 50) return "书名太长（>50 字）：$t"
        if (t == "." || t == "..") return "书名不能是「$t」"
        val bad = t.firstOrNull { it in "\\/:*?\"<>|" || it.code < 32 }
        if (bad != null) return "书名里有不能做目录名的字符（${bad}）：换一个写法"
        return null
    }

    /** 解析 book.json（坏文件 ⇒ [NovelMeta.error] 非空，调用方给可读提示）。 */
    fun parse(json: String): NovelMeta {
        val obj = runCatching { JSONObject(json) }.getOrElse {
            return NovelMeta("", "", 0, emptyList(), "", "", error = "book.json 不是合法的 JSON（可能被改坏）：修好它或删掉整个项目目录重建")
        }
        if (obj.optString("format") != FORMAT) {
            return NovelMeta("", "", 0, emptyList(), "", "", error = "book.json 缺少格式标记「$FORMAT」（不是证道小说项目的元数据文件）")
        }
        val chars = mutableListOf<Pair<String, String>>()
        val arr = runCatching { obj.optJSONArray("characters") ?: JSONArray() }.getOrElse { JSONArray() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            if (name.isNotEmpty()) chars += name to o.optString("desc").trim()
        }
        return NovelMeta(
            title = obj.optString("title").trim(),
            outline = obj.optString("outline").trim(),
            chapterTargetWords = obj.optInt("chapterTargetWords", 3000).coerceIn(100, 100_000),
            characters = chars,
            recap = obj.optString("recap").trim(),
            notes = obj.optString("notes").trim(),
        )
    }

    /** 读项目（目录不存在/不是项目 ⇒ error 可读；绝不抛）。 */
    fun load(root: File, path: String): Pair<File, NovelMeta>? {
        val dir = resolveProject(root, path)
            ?: return null
        val meta = runCatching { parse(File(dir, NOVEL_META_FILE).readText()) }
            .getOrElse { NovelMeta("", "", 0, emptyList(), "", "", error = "book.json 读不了（权限或 IO 错误）") }
        return dir to meta
    }

    /** 把调用方的 path 参数解析成项目目录（相对工作区根；绝对路径也认——管线已守卫过）。 */
    fun resolveProject(root: File, path: String): File? {
        val p = path.trim()
        if (p.isEmpty()) return null
        val f = if (File(p).isAbsolute) File(p) else File(root, p)
        return f.takeIf { it.isDirectory && File(it, NOVEL_META_FILE).isFile }
    }

    /** 工作区里所有小说项目（`小说/<书名>/book.json` 存在即算），按目录名排序。 */
    fun listProjects(root: File): List<File> {
        val base = File(root, NOVEL_ROOT_DIR)
        if (!base.isDirectory) return emptyList()
        return base.listFiles()
            ?.filter { it.isDirectory && File(it, NOVEL_META_FILE).isFile }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * 解析「要看哪个项目」：显式 path 优先；**path 省略时**只有一个项目就用它，
     * 0 个/多个都给可通过的话（不猜、不建目录）。
     *
     * 2026-10-23 真机（Gemini）：模型调 `novel_project` 时漏传必填 `path`，
     * 被 schema 拦下后连续失败、整轮熔断 —— 唯一项目是常态，允许省略比逼模型
     * 再猜一次路径更可靠；多项目仍要求显式指定（不替用户挑）。
     */
    data class Picked(val dir: File?, val error: String?)

    fun pick(root: File, path: String): Picked {
        val p = path.trim()
        if (p.isNotEmpty()) {
            val dir = resolveProject(root, p)
                ?: return Picked(
                    null,
                    "不是一个小说项目：$p（项目目录里要有 $NOVEL_META_FILE；" +
                        "新建用 novel_create，看单本书的目录用 book_outline）",
                )
            return Picked(dir, null)
        }
        val projects = listProjects(root)
        return when (projects.size) {
            0 -> Picked(null, "还没有小说项目：先用 novel_create 建一个（$NOVEL_ROOT_DIR/<书名>/）")
            1 -> Picked(projects[0], null)
            else -> Picked(
                null,
                "工作区里有 ${projects.size} 个小说项目，请用 path 指定一个：" +
                    projects.joinToString("、") { "$NOVEL_ROOT_DIR/${it.name}" },
            )
        }
    }

    /** 正文目录下的章节清单（按文件名排序＝章节序；跳过隐藏/临时文件）。 */
    fun chapters(dir: File): List<NovelChapter> {
        val body = File(dir, NOVEL_BODY_DIR)
        if (!body.isDirectory) return emptyList()
        return body.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.name }
            ?.map { NovelChapter(it.name, wordCount(it.readText()), it.readLines().size) }
            ?: emptyList()
    }

    /** 末段预览（续写「接哪一段」用）：文件尾部最多 [maxChars] 字符，从最近的非空行起。 */
    fun tailPreview(file: File, maxChars: Int = 200): String {
        val text = runCatching { file.readText() }.getOrElse { return "" }
        val trimmedEnd = text.trimEnd()
        return if (trimmedEnd.length <= maxChars) trimmedEnd else trimmedEnd.takeLast(maxChars)
    }

    /** 原子写 JSON（先 .tmp 再 rename；与 write_file 同一口径——半截元数据比不写更坏）。 */
    fun writeMeta(dir: File, meta: JSONObject): Boolean {
        val target = File(dir, NOVEL_META_FILE)
        val tmp = File(dir, "$NOVEL_META_FILE.tmp")
        return runCatching {
            tmp.writeText(meta.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }
}

// ── novel_create（CONFIRM：唯一写盘的入口） ─────────────────────────────

internal object NovelCreateTool {

    const val ARG_TITLE = "title"
    const val ARG_OUTLINE = "outline"
    const val ARG_CHAPTER_WORDS = "chapter_target_words"
    const val ARG_CHARACTERS = "characters"
    const val ARG_RECAP = "recap"
    const val ARG_NOTES = "notes"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "novel_create",
        description = "新建小说项目（小说/<书名>/）：书名、全书大纲、每章目标字数、" +
            "主要人物（每行「名字：简介」）、前情摘要。建好项目后，章节正文用 write_file " +
            "写到 正文/ 目录（一章一个文件，如 正文/第001章-标题.md），后续用 novel_project " +
            "看目录与字数、novel_continue 拿续写简报。每次新建都要用户点确认卡。" +
            "**title 必填**（书名，会做成目录名），参数示例：" +
            "{\"title\":\"我的书\",\"outline\":\"三卷：…\",\"chapter_target_words\":3000}。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_TITLE to jsonObjectOf(
                    "type" to "string",
                    "description" to "书名（会做成目录名；不能含 \\ / : * ? \" < > |）",
                ),
                ARG_OUTLINE to jsonObjectOf("type" to "string", "description" to "全书大纲（可空）"),
                ARG_CHAPTER_WORDS to jsonObjectOf(
                    "type" to "integer",
                    "description" to "每章目标字数（默认 3000；100–100000）",
                ),
                ARG_CHARACTERS to jsonObjectOf(
                    "type" to "string",
                    "description" to "主要人物，每行一个「名字：简介」（可空）",
                ),
                ARG_RECAP to jsonObjectOf("type" to "string", "description" to "前情摘要（可空）"),
                ARG_NOTES to jsonObjectOf("type" to "string", "description" to "设定备注（世界观/力量体系等，可空）"),
            ),
            "required" to jsonArrayOf(ARG_TITLE),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> create(workspaceRoot(), args) }

    /**
     * 建项目（目录已存在 ⇒ 拒绝不覆盖；书名非法 ⇒ 可读拒绝）。
     * 只写 `book.json` ＋ 建 `正文/` 目录，**不写任何正文**——正文走 write_file。
     */
    fun create(root: File, args: JSONObject): ToolOutcome {
        val title = args.optString(ARG_TITLE).trim()
        NovelProjectStore.checkTitle(title)?.let { return ToolOutcome.Failed(it) }
        val dir = NovelProjectStore.projectDir(root, title)
        if (dir.exists()) {
            return ToolOutcome.Failed(
                "项目已存在：$NOVEL_ROOT_DIR/$title（不做覆盖；要改元数据用 edit_file 改 $NOVEL_META_FILE，" +
                    "或换一个书名）",
            )
        }
        val body = File(dir, NOVEL_BODY_DIR)
        if (!body.mkdirs()) {
            return ToolOutcome.Failed("建不了项目目录：$NOVEL_ROOT_DIR/$title（权限或 IO 错误）")
        }
        val meta = JSONObject()
            .put("format", NovelProjectStore.FORMAT)
            .put("title", title)
            .put("outline", args.optString(ARG_OUTLINE).trim())
            .put("chapterTargetWords", args.optInt(ARG_CHAPTER_WORDS, 3000).coerceIn(100, 100_000))
            .put("characters", parseCharacters(args.optString(ARG_CHARACTERS)))
            .put("recap", args.optString(ARG_RECAP).trim())
            .put("notes", args.optString(ARG_NOTES).trim())
        if (!NovelProjectStore.writeMeta(dir, meta)) {
            // 目录已建但元数据写失败 ⇒ 回滚目录，不给用户留半截项目
            runCatching { dir.deleteRecursively() }
            return ToolOutcome.Failed("写 $NOVEL_META_FILE 失败（IO 错误），已放弃并把目录还原")
        }
        return ToolOutcome.Ok(
            "小说项目已建：$NOVEL_ROOT_DIR/$title\n" +
                "－ 元数据：$NOVEL_META_FILE（大纲/每章目标字数/人物/设定都在里面，可随时用 edit_file 改）\n" +
                "－ 正文目录：$NOVEL_BODY_DIR/（空的，还没有章节）\n" +
                "下一步：用 write_file 写第一章到 $NOVEL_BODY_DIR/第001章-<标题>.md" +
                "（一次最多 200 行，长了分次写；写完可用 novel_project 看目录与字数）。",
        )
    }

    /** 「名字：简介」每行一条；支持全角/半角冒号；解析不了的原样当名字（简介空）。 */
    private fun parseCharacters(raw: String): JSONArray {
        val arr = JSONArray()
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val sep = line.indexOfFirst { it == '：' || it == ':' }
                val o = if (sep > 0) {
                    JSONObject().put("name", line.take(sep).trim()).put("desc", line.substring(sep + 1).trim())
                } else {
                    JSONObject().put("name", line)
                }
                arr.put(o)
            }
        return arr
    }
}

// ── novel_project（FREE 只读：项目总览） ─────────────────────────────────

internal object NovelProjectTool {

    const val ARG_PATH = "path"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "novel_project",
        description = "看一个小说项目的总览：书名、大纲、每章目标字数、主要人物、设定、" +
            "正文目录（每章的文件名/实际字数/与目标差）与全书总字数。只读不改。" +
            "**path 可省略**：只有一个小说项目时自动用它，有多个会列出让你指定。" +
            "单文件整本书（txt/md）不归它管——看那种书的目录用 book_outline。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "项目目录的相对路径（如 小说/我的书）；省略＝唯一项目",
                ),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> overview(workspaceRoot(), args.optString(ARG_PATH)) }

    /** 项目总览（目录可列、字数可算、坏元数据可读提示——派工单 §1「可查看/不崩溃」）。 */
    fun overview(root: File, path: String): ToolOutcome {
        val picked = NovelProjectStore.pick(root, path)
        val dir = picked.dir ?: return ToolOutcome.Failed(picked.error ?: "项目目录无效")
        val meta = NovelProjectStore.parse(File(dir, NOVEL_META_FILE).readText())
        if (meta.error != null) return ToolOutcome.Failed(meta.error)
        val chapters = NovelProjectStore.chapters(dir)
        val total = chapters.sumOf { it.chars }
        return ToolOutcome.Ok(
            buildString {
                append("项目：$NOVEL_ROOT_DIR/${meta.title}\n")
                if (meta.outline.isNotEmpty()) append("大纲：${meta.outline}\n")
                append("每章目标字数：${meta.chapterTargetWords}\n")
                if (meta.characters.isNotEmpty()) {
                    append("主要人物：\n")
                    meta.characters.forEach { (n, d) -> append("－ $n${if (d.isEmpty()) "" else "：$d"}\n") }
                }
                if (meta.recap.isNotEmpty()) append("前情：${meta.recap}\n")
                if (meta.notes.isNotEmpty()) append("设定：${meta.notes}\n")
                if (chapters.isEmpty()) {
                    append("正文：还没有章节（写第一章用 write_file 到 $NOVEL_BODY_DIR/）")
                } else {
                    append("正文（${chapters.size} 章，共 $total 字）：\n")
                    append("文件\t字数\t对比目标\n")
                    chapters.forEach { ch ->
                        val diff = ch.chars - meta.chapterTargetWords
                        append(ch.fileName).append('\t').append(ch.chars).append('\t')
                            .append(if (diff >= 0) "+$diff" else "$diff").append('\n')
                    }
                }
            }.trimEnd(),
        )
    }
}

// ── novel_continue（FREE 只读：续写简报） ────────────────────────────────

internal object NovelContinueTool {

    const val ARG_PATH = "path"
    const val ARG_WORDS = "words"

    /** 末段预览上限（「接哪一段」给到能接上就够了，不把整章塞回去）。 */
    const val TAIL_CHARS = 200

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "novel_continue",
        description = "拿一份续写简报：接哪一章哪一段（最后一章的文件与末段原文）、" +
            "主要人物与设定、一致性提示。**path 可省略**：只有一个项目时自动用它。" +
            "**不覆盖已有正文**）或 write_file 新开下一章；本工具只读不改。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "项目目录的相对路径（如 小说/我的书）；省略＝唯一项目",
                ),
                ARG_WORDS to jsonObjectOf(
                    "type" to "integer",
                    "description" to "这次想续多少字（省略则按项目里的每章目标字数）",
                ),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> brief(workspaceRoot(), args.optString(ARG_PATH), args.optInt(ARG_WORDS, 0)) }

    /**
     * 续写简报（W2）：
     * - 「接哪章/哪一段」：最后一章（文件名排序）＋末段原文（截到 [TAIL_CHARS]）；
     * - 一致性提示：只给**事实**（谁在最近一章没出场、本章字数离目标差多少），
     *   冲突判定与修改决定交给模型，**绝不自动改文**（派工单 §2 原话）；
     * - 不覆盖正文：给的两条路都是「追加/新文件」，覆盖参数不在建议里。
     */
    fun brief(root: File, path: String, words: Int): ToolOutcome {
        val picked = NovelProjectStore.pick(root, path)
        val dir = picked.dir ?: return ToolOutcome.Failed(picked.error ?: "项目目录无效")
        val meta = NovelProjectStore.parse(File(dir, NOVEL_META_FILE).readText())
        if (meta.error != null) return ToolOutcome.Failed(meta.error)
        val chapters = NovelProjectStore.chapters(dir)
        if (chapters.isEmpty()) {
            return ToolOutcome.Failed(
                "这个项目还没有正文：$NOVEL_ROOT_DIR/${meta.title}/$NOVEL_BODY_DIR/ 是空的。" +
                    "先写第一章（write_file 到 $NOVEL_BODY_DIR/第001章-<标题>.md），谈续写要有前文可接。",
            )
        }
        val last = chapters.last()
        val lastFile = File(File(dir, NOVEL_BODY_DIR), last.fileName)
        val tail = NovelProjectStore.tailPreview(lastFile, TAIL_CHARS)
        val target = if (words in 100..100_000) words else meta.chapterTargetWords
        return ToolOutcome.Ok(
            buildString {
                append("续写简报：$NOVEL_ROOT_DIR/${meta.title}\n")
                append("接：${last.fileName}（现 ${last.chars} 字 / ${last.lines} 行）\n")
                append("本章目标：$target 字（当前差 ${target - last.chars} 字）\n")
                if (meta.characters.isNotEmpty()) {
                    append("人物：${meta.characters.joinToString("、") { it.first }}\n")
                    val absent = meta.characters.filter { (n, _) ->
                        !lastFile.readText().contains(n)
                    }.map { it.first }
                    if (absent.isNotEmpty()) {
                        append("一致性提示：${absent.joinToString("、")} 在最近一章没有出场" +
                            "（是否该出场由你判断；工具不做结论、更不自动改文）\n")
                    }
                }
                if (meta.recap.isNotEmpty()) append("前情：${meta.recap}\n")
                if (meta.notes.isNotEmpty()) append("设定：${meta.notes}\n")
                append("末段（原文照抄，接这里往下写）：\n$tail\n")
                append("动笔两条路（都不覆盖已有正文）：\n" +
                    "－ 续在本章：edit_file path=\"$NOVEL_BODY_DIR/${last.fileName}\" op=append\n" +
                    "－ 新开一章：write_file path=\"$NOVEL_BODY_DIR/第NNN章-<标题>.md\"" +
                    "（NNN 接现有章号往后排；用 novel_project 确认章数）")
            }.trimEnd(),
        )
    }
}
