// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第六百一十批（通读框架 · 工具两件）。
// - `novel_readthrough`（FREE 只读，R0）：开跑**前**的预检——编码逐条报、切片异常逐条报、
//   归并层数、预算 {片数/调用数/token} 与是否允许、略读漏章清单、断点现状。零网络零写盘；
// - `novel_readthrough_run`（CONFIRM，R2）：真开跑。**再过一遍预算闸**（超额不启动，
//   不许「plan 说不行、run 硬跑」）；模型出口从 [Env.port] 注入（装配层 BaopuRuntime
//   接线，测试换假件）；落盘 append-only（[FactStore]），断点续跑不重跑已完成片。
//
// 路径口径：读章用既有 machinery（TextDecode + ChapterSplitter + read_file 同一套
// 行号 0 起含端点），不依赖 609。事实默认落 `<工作区>/产物/通读/<书>/`（157 收口：
// 产物是唯一输出口）；`out_dir` 参数可改（相对工作区根，越界拒）——任务文本里的
// 「资料库/通读/」是 legacy 名，要落那儿就 out_dir="资料库/通读"。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.FilePaths
import com.example.zhengdao.baopu.LlmClient
import com.example.zhengdao.baopu.LlmEvent
import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.fileAccessCheck
import com.example.zhengdao.baopu.library.Chapter
import com.example.zhengdao.baopu.library.ChapterSplitter
import com.example.zhengdao.baopu.readthrough.BudgetCheck
import com.example.zhengdao.baopu.readthrough.BudgetLimits
import com.example.zhengdao.baopu.readthrough.BudgetVerdict
import com.example.zhengdao.baopu.readthrough.Checkpoint
import com.example.zhengdao.baopu.readthrough.CheckpointStore
import com.example.zhengdao.baopu.readthrough.ChapterBrief
import com.example.zhengdao.baopu.readthrough.EncodingReport
import com.example.zhengdao.baopu.readthrough.ExtractionPort
import com.example.zhengdao.baopu.readthrough.FactStore
import com.example.zhengdao.baopu.readthrough.MergePlanner
import com.example.zhengdao.baopu.readthrough.MergePlan
import com.example.zhengdao.baopu.readthrough.ReadMode
import com.example.zhengdao.baopu.readthrough.ReadSlice
import com.example.zhengdao.baopu.readthrough.ReadthroughRunner
import com.example.zhengdao.baopu.readthrough.ReadthroughSlicer
import com.example.zhengdao.baopu.readthrough.RunRequest
import com.example.zhengdao.baopu.readthrough.SkimPlanner
import com.example.zhengdao.baopu.readthrough.SkimSelection
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 生产出口：一轮无工具对话收满文本（房型同 LlmVisionPort，纯文本）。 */
internal class LlmExtractionPort(private val provider: Provider) : ExtractionPort {
    override fun call(prompt: String): String {
        val textBuf = StringBuilder()
        val latch = CountDownLatch(1)
        var failure: String? = null
        val call = LlmClient(provider).streamAsync(
            listOf(LlmMessage(LlmMessage.ROLE_USER, prompt)),
            tools = null,
        ) { ev ->
            when (ev) {
                is LlmEvent.Delta -> textBuf.append(ev.text)
                is LlmEvent.Finished -> latch.countDown()
                is LlmEvent.Failed -> {
                    failure = ev.error.message ?: ev.error.javaClass.simpleName
                    latch.countDown()
                }
            }
        }
        val arrived = runCatching {
            latch.await(RunRules.STREAM_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!arrived) {
            runCatching { call.cancel() }
            throw IllegalStateException("等模型超过 ${RunRules.STREAM_WAIT_TIMEOUT_MS / 1000}s，本次作废")
        }
        failure?.let { throw IllegalStateException("模型请求失败：$it") }
        return textBuf.toString()
    }
}

internal object NovelReadthroughTool {

    const val ARG_PATH = "path"
    const val ARG_MODE = "mode"
    const val ARG_QUERY = "query"
    const val ARG_MAX_CALLS = "max_calls"
    const val ARG_MAX_TOKENS = "max_tokens"
    const val ARG_MAX_SLICE_CHARS = "max_slice_chars"
    const val ARG_OUT_DIR = "out_dir"
    const val ARG_RESUME = "resume"

    const val MODE_DEEP = "deep"
    const val MODE_SKIM = "skim"

    /** 预算默认上限（工具参数可调；见 BudgetCheck 的 token 口径）。 */
    const val DEFAULT_MAX_CALLS = 200
    const val DEFAULT_MAX_TOKENS = 3_000_000L

    /** 通读书文件大小上限（整本进内存解码；32MB ≈ 千章级长篇）。 */
    const val MAX_FILE_BYTES = 32L * 1024 * 1024

    /** 输出根（157 收口：产物是唯一输出口；名字与 NovelProjectTool 无关，独立子目录）。 */
    const val OUTPUT_ROOT = "产物/通读"

    /** 装配层注入（BaopuRuntime；测试换假件）。 */
    internal class Env(val port: ExtractionPort)

    @Volatile internal var env: Env? = null

    // ── 两个 Spec ───────────────────────────────────────────────────────────

    /** `novel_readthrough`：预检报告（FREE 只读）。 */
    fun planSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "novel_readthrough",
        description = "通读一本书前的**预检报告**（不跑模型不写盘）：编码判定与疑点、" +
            "切片数与切分异常逐条、归并层数（片→章→每100章→全书）、" +
            "预算 {片数, 调用数, token} 是否允许、略读漏章清单、断点续跑现状。" +
            "mode=deep 精读全部章；mode=skim 要配 query，只抽标题/首句命中的章并**列出漏章**。" +
            "预算超限会明说拒绝——改 max_calls/max_tokens 或分卷跑。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "书的路径（相对工作区根，如 投递/斗罗大陆.txt）",
                ),
                ARG_MODE to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf(MODE_DEEP, MODE_SKIM),
                    "description" to "deep=精读（默认）；skim=略读（配 query）",
                ),
                ARG_QUERY to jsonObjectOf(
                    "type" to "string",
                    "description" to "略读关键词（空格分词，命中标题或首句即选中；精读忽略）",
                ),
                ARG_MAX_CALLS to jsonObjectOf(
                    "type" to "integer",
                    "description" to "预算：最多几次模型调用（默认 $DEFAULT_MAX_CALLS）",
                    "minimum" to 1,
                ),
                ARG_MAX_TOKENS to jsonObjectOf(
                    "type" to "integer",
                    "description" to "预算：最多多少 token（默认 $DEFAULT_MAX_TOKENS）",
                    "minimum" to 1000,
                ),
                ARG_MAX_SLICE_CHARS to jsonObjectOf(
                    "type" to "integer",
                    "description" to "单片字数上限（默认 ${ReadthroughSlicer.MAX_SLICE_CHARS}，最大 12000）",
                    "minimum" to 1000,
                    "maximum" to 12000,
                ),
                ARG_OUT_DIR to jsonObjectOf(
                    "type" to "string",
                    "description" to "事实落盘目录（相对工作区根；默认 $OUTPUT_ROOT/<书名>）",
                ),
            ),
            "required" to jsonArrayOf(ARG_PATH),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> planRun(args, workspaceRoot()) }

    /** `novel_readthrough_run`：真开跑（CONFIRM；预算再闸一遍）。 */
    fun runSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "novel_readthrough_run",
        description = "按 novel_readthrough 的预检结果**真开跑**通读（会多次调用模型、写 jsonl）。" +
            "开跑前再过一遍预算闸，超限不启动；断点续跑不重跑已完成片；" +
            "网络差自动降并发或暂停（checkpoint 已存，续跑接着跑）。" +
            "先调 novel_readthrough 看预算与异常，再跑本工具。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "书的路径（同 novel_readthrough）",
                ),
                ARG_MODE to jsonObjectOf(
                    "type" to "string",
                    "enum" to jsonArrayOf(MODE_DEEP, MODE_SKIM),
                    "description" to "deep=精读（默认）；skim=略读（配 query）",
                ),
                ARG_QUERY to jsonObjectOf(
                    "type" to "string",
                    "description" to "略读关键词（精读忽略）",
                ),
                ARG_MAX_CALLS to jsonObjectOf(
                    "type" to "integer",
                    "description" to "预算：最多几次模型调用（默认 $DEFAULT_MAX_CALLS）",
                    "minimum" to 1,
                ),
                ARG_MAX_TOKENS to jsonObjectOf(
                    "type" to "integer",
                    "description" to "预算：最多多少 token（默认 $DEFAULT_MAX_TOKENS）",
                    "minimum" to 1000,
                ),
                ARG_MAX_SLICE_CHARS to jsonObjectOf(
                    "type" to "integer",
                    "description" to "单片字数上限（默认 ${ReadthroughSlicer.MAX_SLICE_CHARS}）",
                    "minimum" to 1000,
                    "maximum" to 12000,
                ),
                ARG_OUT_DIR to jsonObjectOf(
                    "type" to "string",
                    "description" to "事实落盘目录（相对工作区根；默认 $OUTPUT_ROOT/<书名>）",
                ),
                ARG_RESUME to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "续跑（默认 true；false=清账重跑，已落的 jsonl 不删、只重抽片）",
                ),
            ),
            "required" to jsonArrayOf(ARG_PATH),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        readOnly = false,
        timeoutMs = ToolTimeout.CONFIRM_MS,
    ) { args -> runRun(args, workspaceRoot()) }

    // ── 预检核心（plan/run 共用；run 在此之上再闸预算、接 Port） ─────────────

    internal class Prepared(
        val bookName: String,
        val lines: List<String>,
        val chapters: List<Chapter>,
        val slices: List<ReadSlice>,
        val skim: SkimSelection?,
        val selectedSlices: List<ReadSlice>,
        val mergePlan: MergePlan,
        val encodingNote: String,
        val encodingOk: Boolean,
        val outDir: File,
        val outDirRel: String,
        val checkpoint: Checkpoint?,
        val estimate: com.example.zhengdao.baopu.readthrough.BudgetEstimate,
        val verdict: BudgetVerdict,
        val limits: BudgetLimits,
    )

    /** 预检（纯计算；写盘只发生在 run 的落盘层）。复杂度闸内：装载与选章拆两个助手。 */
    internal fun prepare(args: JSONObject, root: File): Prepared {
        val (file, display, decoded) = loadBook(args, root)
        val text = decoded.text ?: throw IllegalArgumentException(decoded.error ?: "读不了：$display")
        val encVerdict = EncodingReport.analyze(decoded, display)
        val lines = TextDecode.splitLines(text)
        val chapters = ChapterSplitter.split(lines.joinToString("\n"))
        val maxChars = args.optInt(ARG_MAX_SLICE_CHARS, ReadthroughSlicer.MAX_SLICE_CHARS)
            .coerceIn(1000, 12000)
        val sliced = ReadthroughSlicer.sliceBook(lines, chapters, maxChars)
        val (mode, skim) = skimOf(args, chapters, lines)
        val selectedChapters = skim?.selected?.toSet() ?: chapters.map { it.index }.toSet()
        val selectedSlices = sliced.slices.filter { it.chapterIndex in selectedChapters }

        val mergePlan = MergePlanner.plan(selectedSlices.groupBy { it.chapterIndex }.toSortedMap()
            .map { (ci, ss) -> ci to ss.size })
        val sliceChars = selectedSlices.sumOf { it.textOf(lines).length }
        val estimate = BudgetCheck.estimate(selectedSlices.size, sliceChars, mergePlan.calls.size)
        val limits = BudgetLimits(
            maxCalls = args.optInt(ARG_MAX_CALLS, DEFAULT_MAX_CALLS).coerceAtLeast(1),
            maxTokens = args.optLong(ARG_MAX_TOKENS, DEFAULT_MAX_TOKENS).coerceAtLeast(1000),
        )
        val verdict = BudgetCheck.check(estimate, limits)

        val bookName = sanitizeBookName(file.nameWithoutExtension)
        val outDir = resolveOutDir(root, args.optString(ARG_OUT_DIR).trim(), bookName)
        val cpFile = File(outDir, FactStore.PROGRESS_FILE)
        val checkpoint = if (cpFile.exists()) {
            CheckpointStore.fromJson(cpFile.readText(Charsets.UTF_8))
                ?.takeIf { it.matches(CheckpointStore.bookSha(text), mode.name) }
        } else null

        return Prepared(
            bookName = bookName,
            lines = lines,
            chapters = chapters,
            slices = sliced.slices,
            skim = skim,
            selectedSlices = selectedSlices,
            mergePlan = mergePlan,
            encodingNote = encVerdict.note,
            encodingOk = encVerdict.ok,
            outDir = outDir,
            outDirRel = FilePaths.display(root, outDir),
            checkpoint = checkpoint,
            estimate = estimate,
            verdict = verdict,
            limits = limits,
        )
    }

    /** 找书＋守卫＋解码（越界/不存在/超大/读不了各自 IllegalArgumentException）。 */
    private fun loadBook(args: JSONObject, root: File): Triple<File, String, TextDecode.Decoded> {
        val path = args.optString(ARG_PATH).trim()
        if (path.isBlank()) throw IllegalArgumentException("缺少必填参数「$ARG_PATH」")
        // 相对路径相对工作区根（与 read_file 同口径）；绝对路径直用
        val file = File(path).let { if (it.isAbsolute) it else File(root, path) }
        val display = FilePaths.display(root, file)
        fileAccessCheck(file, display)?.let { reason -> throw IllegalArgumentException(reason) }
        if (!file.exists() || !file.isFile) {
            throw IllegalArgumentException("不存在或不是文件：$display（先用 find_files / list_dir 找）")
        }
        if (file.length() > MAX_FILE_BYTES) {
            throw IllegalArgumentException(
                "文件太大不整本通读：$display 有 ${file.length()} B，超过 $MAX_FILE_BYTES B。先切卷或分批。",
            )
        }
        val decoded = TextDecode.decode(file, file.readBytes())
        return Triple(file, display, decoded)
    }

    /** 模式与略读选章（skim 不配 query 就地拒）。 */
    private fun skimOf(
        args: JSONObject,
        chapters: List<Chapter>,
        lines: List<String>,
    ): Pair<ReadMode, SkimSelection?> {
        val mode = if (args.optString(ARG_MODE, MODE_DEEP) == MODE_SKIM) ReadMode.SKIM else ReadMode.DEEP
        val query = args.optString(ARG_QUERY).trim()
        if (mode == ReadMode.SKIM && query.isEmpty()) {
            throw IllegalArgumentException("mode=skim 必须配 query（略读要有关键词才算得清漏章）")
        }
        if (mode == ReadMode.DEEP) return ReadMode.DEEP to null
        val briefs = chapters.map { ch -> ChapterBrief(ch.index, ch.title, previewOf(ch.textOf(lines))) }
        return ReadMode.SKIM to SkimPlanner.select(query, briefs)
    }

    /** plan 工具入口。 */
    private fun planRun(args: JSONObject, root: File): ToolOutcome = try {
        val p = prepare(args, root)
        ToolOutcome.Ok(renderPlan(p))
    } catch (e: IllegalArgumentException) {
        ToolOutcome.Failed(e.message ?: "参数不合法")
    }

    /** run 工具入口。 */
    private fun runRun(args: JSONObject, root: File): ToolOutcome {
        val port = env?.port ?: return ToolOutcome.Failed(
            "模型出口没接上（装配层没注入）——通读要真调模型，先在抱朴里启用本功能",
        )
        val p = try {
            prepare(args, root)
        } catch (e: IllegalArgumentException) {
            return ToolOutcome.Failed(e.message ?: "参数不合法")
        }
        if (!p.encodingOk) return ToolOutcome.Failed("编码预检不过，不启动：${p.encodingNote}")
        val selected = p.skim?.selected
        if (selected != null && selected.isEmpty()) {
            return ToolOutcome.Failed("略读 0 章命中（查询没打到任何标题/首句）——换 query 或用精读")
        }
        val deny = p.verdict as? BudgetVerdict.Deny
        if (deny != null) {
            return ToolOutcome.Failed("预算超限，不启动（判据：超额不启动）：" + deny.reasons.joinToString("；"))
        }
        val mode = if (p.skim == null) ReadMode.DEEP else ReadMode.SKIM
        val bookSha = CheckpointStore.bookSha(p.lines.joinToString("\n"))
        val resume = args.optBoolean(ARG_RESUME, true)
        val startCp = if (resume) seedFromExisting(p, bookSha, mode.name) else null
        val runner = ReadthroughRunner(port)
        val report = runner.run(
            RunRequest(
                bookName = p.bookName,
                bookSha = bookSha,
                modeLabel = mode.name,
                lines = p.lines,
                slices = p.selectedSlices,
                mergeCalls = p.mergePlan.calls,
                outDir = p.outDir,
                checkpoint = startCp,
                persist = { cp ->
                    FactStore.writeProgress(p.outDir, CheckpointStore.toJson(cp))
                },
            ),
        )
        if (report.anomalies.isNotEmpty()) {
            FactStore.append(
                p.outDir, FactStore.ANOMALY_FILE,
                report.anomalies.map { JSONObject().put("type", "anomaly").put("text", it).toString() },
            )
        }
        return ToolOutcome.Ok(report.render())
    }

    // ── 渲染 ────────────────────────────────────────────────────────────────

    private fun renderPlan(p: Prepared): String = buildString {
        append("通读预检：${p.bookName}\n")
        append("编码：${p.encodingNote}\n")
        append("章数：${p.chapters.size}（切片 ${p.selectedSlices.size} 片，单片上限 ${ReadthroughSlicer.MAX_SLICE_CHARS} 字）\n")
        p.skim?.let { s ->
            append("略读：选中 ${s.selected.size} 章，漏 ${s.missed.size} 章：")
            append(SkimPlanner.renderMissed(s.missed)).append('\n')
        }
        append("归并：共 ${p.mergePlan.layers} 层（片→章→每100章→书），归并调用 ${p.mergePlan.calls.size} 次，")
        append("单次归并最多 ${p.mergePlan.maxInputs} 个输入\n")
        when (val v = p.verdict) {
            is BudgetVerdict.Allow -> append(
                "预算：调用 ${v.estimate.totalCalls}/${p.limits.maxCalls}，" +
                    "token ${v.estimate.totalTokens}/${p.limits.maxTokens} ⇒ 允许开跑\n",
            )
            is BudgetVerdict.Deny -> append(
                "预算：调用 ${v.estimate.totalCalls}/${p.limits.maxCalls}，" +
                    "token ${v.estimate.totalTokens}/${p.limits.maxTokens} ⇒ **拒绝**（" +
                    v.reasons.joinToString("；") + "）——不启动\n",
            )
        }
        val existing = FactStore.existingChapters(p.outDir)
        if (p.checkpoint != null) {
            append("断点：已有 checkpoint（片 ${p.checkpoint.doneSlices.size}、章 ${p.checkpoint.doneChapters.size} 已完成）——续跑只补缺\n")
        } else if (existing.isNotEmpty()) {
            val missing = CheckpointStore.missingChapters(p.skim?.selected ?: p.chapters.map { it.index }, existing)
            append("断点：无 checkpoint；输出目录已有 ${existing.size} 章的事实文件，缺 ${missing.size} 章（重跑只补缺）\n")
        }
        append("输出：${p.outDirRel}/（chapter-NNNN.jsonl 每章一份，append-only）")
    }

    // ── 小件 ────────────────────────────────────────────────────────────────

    /**
     * 续跑起点：checkpoint 优先；账本丢了（读不出/书变了）⇒ 按「补缺章」口径种一份
     * 临时账——输出目录里已有事实文件的章当作已完成（append-only 的账不会重写），
     * 只有缺的章真抽。判据：重跑只补缺章。
     */
    private fun seedFromExisting(p: Prepared, bookSha: String, modeLabel: String): Checkpoint? {
        if (p.checkpoint != null) return p.checkpoint
        val existing = FactStore.existingChapters(p.outDir)
        if (existing.isEmpty()) return null
        val selected = p.skim?.selected?.toSet() ?: p.chapters.map { it.index }.toSet()
        val doneChapters = existing.intersect(selected)
        if (doneChapters.isEmpty()) return null
        val doneSlices = p.selectedSlices.filter { it.chapterIndex in doneChapters }.map { it.id }.toSet()
        return Checkpoint(bookSha, modeLabel, "", doneSlices, doneChapters, emptySet(), 0)
    }

    private fun previewOf(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40) ?: ""

    private fun sanitizeBookName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(60).ifEmpty { "book" }

    /** 输出目录：默认 产物/通读/<书>；out_dir 相对工作区根（越界拒）。 */
    private fun resolveOutDir(root: File, outDirArg: String, bookName: String): File {
        val f = if (outDirArg.isEmpty()) File(root, "$OUTPUT_ROOT/$bookName") else File(root, outDirArg)
        val rootCanon = root.canonicalPath
        val fCanon = f.canonicalPath
        require(fCanon == rootCanon || fCanon.startsWith(rootCanon + File.separator)) {
            "out_dir 越出工作区（$outDirArg）——只许落在工作区内"
        }
        return f
    }
}
