// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第六百一十批（通读框架 · 编排骨架）。执行序：
//   预算闸（工具层已过）→ 断点续跑过滤 → 片抽取（波次并发，网络闸逐波判定
//   降并发/暂停）→ 章归并 → 块归并（每 100 章）→ 书归并 → 人物覆盖抽样 → 报告。
// 五条硬纪律：
// 1. 模型出口只从 [ExtractionPort] 进——测试换假件数调用次数，生产走 LlmExtractionPort；
// 2. 断点续跑不重跑已完成片：成功的才记账，失败的留在待跑（判据⑥）；
// 3. 网络闸说暂停就停：checkpoint 落盘、报告如实（判据④）；
// 4. 落盘只有追加（[FactStore]）；坏行/坏回文记异常，不崩不静默；
// 5. 归并严格按 [MergePlanner] 的树走——本类自己不做任何「一把合」的决定。
package com.example.zhengdao.baopu.readthrough

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** 模型出口（生产走 LlmClient；测试换假件——数调用次数、给回灌文）。 */
internal fun interface ExtractionPort {
    /** 阻塞调用；失败抛异常（网络闸按失败计）。 */
    fun call(prompt: String): String
}

/** 一次通读的输入（全部由工具层预检过：预算已 Allow、书已解码、片已切好）。 */
internal class RunRequest(
    val bookName: String,
    val bookSha: String,
    val modeLabel: String,
    val lines: List<String>,
    val slices: List<ReadSlice>,
    val mergeCalls: List<MergeCall>,
    val outDir: File,
    val checkpoint: Checkpoint?,
    /** checkpoint 每次推进都落盘（工具层注：写 .progress.json）。 */
    val persist: (Checkpoint) -> Unit,
)

internal data class RunReport(
    val plannedSlices: Int,
    val ranSlices: Int,
    val skippedSlices: Int,
    val extractionCalls: Int,
    val mergeCalls: Int,
    val paused: Boolean,
    val pauseReason: String?,
    val anomalies: List<String>,
    val chaptersDone: Int,
    val totalChapters: Int,
    val coverage: CharacterCoverage.Report?,
) {
    /** 章覆盖率（判据①：成功跑完必须 = 1.0）。 */
    val chapterCoverage: Double
        get() = if (totalChapters == 0) 1.0 else chaptersDone.toDouble() / totalChapters

    fun render(): String = buildString {
        append("通读完成：计划 ${plannedSlices} 片，本次跑 $ranSlices 片（跳过已完成的 $skippedSlices 片），")
        append("抽取 $extractionCalls 次、归并 $mergeCalls 次；")
        append("章覆盖 $chaptersDone/$totalChapters（${"%.0f".format(chapterCoverage * 100)}%）")
        if (paused) append("；已暂停（$pauseReason）——checkpoint 已存，续跑接着跑")
        append("\n")
        if (anomalies.isNotEmpty()) {
            append("异常 ${anomalies.size} 条（逐条）：\n")
            anomalies.forEach { append("  - $it\n") }
        }
        coverage?.let { cov ->
            append("人物覆盖抽样：${cov.sampled} 章，整体 ${"%.1f".format(cov.overall * 100)}%")
            append("（阈值 ${"%.0f".format(cov.threshold * 100)}%，${if (cov.pass) "达标" else "未达标"}）\n")
            cov.failing.forEach { (ch, c) ->
                append("  - 第$ch 章覆盖率 ${"%.0f".format(c * 100)}%，低于阈值\n")
            }
        }
    }
}

/** 模型回文 → 事实的宽松解析（坏条目记异常，不让一条脏数据崩一轮跑）。 */
internal object FactParser {

    /**
     * @return（事实清单，异常话清单）。行号夹回片范围内（夹过 = 异常，逐条报）。
     */
    fun parse(modelText: String, slice: ReadSlice, now: Long): Pair<List<FactLine>, List<String>> {
        val issues = mutableListOf<String>()
        val a = modelText.indexOf('[')
        val b = modelText.lastIndexOf(']')
        if (a < 0 || b <= a) {
            return emptyList<FactLine>() to
                listOf("片 ${slice.id} 的回文里没有 JSON 数组（前 80 字：${modelText.take(80)}）")
        }
        val arr = runCatching { JSONArray(modelText.substring(a, b + 1)) }.getOrElse {
            return emptyList<FactLine>() to listOf("片 ${slice.id} 的回文不是合法 JSON 数组")
        }
        val facts = mutableListOf<FactLine>()
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrElse {
                issues += "片 ${slice.id} 第 $i 条不是 JSON 对象，跳过"
                null
            } ?: continue
            val fact = FactLine.fromJson(o)
            if (fact == null) {
                issues += "片 ${slice.id} 第 $i 条缺字段/行号非法，跳过"
                continue
            }
            if (fact.startLine < slice.startLine || fact.endLine > slice.endLine || fact.chapter != slice.chapterIndex) {
                facts += fact.copy(
                    startLine = fact.startLine.coerceIn(slice.startLine, slice.endLine),
                    endLine = fact.endLine.coerceIn(slice.startLine, slice.endLine),
                    chapter = slice.chapterIndex,
                )
                issues += "片 ${slice.id} 第 $i 条行号越界（${fact.startLine}–${fact.endLine}），已夹回片范围"
            } else {
                facts += fact
            }
        }
        return facts.map { it.copy(createdAt = now, sliceId = slice.id) } to issues
    }
}

/**
 * 通读编排器。不认识 Android、不认识 LlmClient——只认 [ExtractionPort]。
 * 单次使用（一次 run 一份状态）；续跑 ＝ 新起一次 run、带上同一份 checkpoint。
 */
internal class ReadthroughRunner(private val port: ExtractionPort) {

    fun run(req: RunRequest): RunReport {
        val cpIn = req.checkpoint?.takeIf { it.matches(req.bookSha, req.modeLabel) }
        val st = State(port)
        if (req.checkpoint != null && cpIn == null) {
            st.anomalies += "已有 checkpoint 与本书/本模式不符，作废重跑（书 sha ${req.bookSha.take(8)}…）"
        }
        var cp = cpIn ?: Checkpoint(req.bookSha, req.modeLabel, "", emptySet(), emptySet(), emptySet(), 0)
        val pending = CheckpointStore.pendingSlices(req.slices, cpIn, req.bookSha, req.modeLabel)

        // checkpoint 推进 = 更新内存态 + 立即落盘（req.persist）——断点续跑的账只在盘上
        val onCp: (Checkpoint) -> Unit = { next ->
            cp = next
            req.persist(next)
        }
        val paused = st.runSlices(req, pending, cp, onCp)
        if (!paused) st.runMerges(req, cp, onCp)

        return RunReport(
            plannedSlices = req.slices.size,
            ranSlices = st.extractionCalls,
            skippedSlices = req.slices.size - pending.size,
            extractionCalls = st.extractionCalls,
            mergeCalls = st.mergeCalls,
            paused = paused,
            pauseReason = st.pauseReason,
            anomalies = st.anomalies.toList(),
            chaptersDone = cp.doneChapters.size,
            totalChapters = req.slices.map { it.chapterIndex }.distinct().size,
            coverage = coverageOfRun(req, cp),
        )
    }

    /** 单次使用的运行态（一次 run 一份；port 从编排器传入）。 */
    private class State(private val port: ExtractionPort) {
        val anomalies = mutableListOf<String>()
        var extractionCalls = 0
        var mergeCalls = 0
        var pauseReason: String? = null

        /** 片 id 形状（c12t3）；章层组产物是 c12r1g0 这类，不带 t。 */
        private val SLICE_ID = Regex("""c\d+t\d+""")

        private data class SliceOutcome(
            val sliceId: String,
            val chapterIndex: Int,
            val facts: List<FactLine>?,
            val error: String?,
            val rttMs: Long,
        )

        /** 片抽取（波次并发；逐波过网络闸）。@return 是否暂停。 */
        fun runSlices(req: RunRequest, pending: List<ReadSlice>, cp0: Checkpoint, onCp: (Checkpoint) -> Unit): Boolean {
            if (pending.isEmpty()) return false
            var cp = cp0
            var concurrency = NetworkGuard.DEFAULT_CONCURRENCY
            val windowResults = mutableListOf<Boolean>()
            val windowRtts = mutableListOf<Long>()
            var consecutiveFailures = 0
            val pool = Executors.newFixedThreadPool(NetworkGuard.MAX_CONCURRENCY)
            try {
                var i = 0
                while (i < pending.size) {
                    val wave = pending.subList(i, (i + concurrency).coerceAtMost(pending.size))
                    val outcomes = pool.invokeAll(wave.map { s -> Callable { callSlice(req, s) } })
                        .map { f ->
                            runCatching { f.get() }
                                .getOrElse { e -> SliceOutcome(e.message ?: e.javaClass.simpleName, 0, null, null, 0L) }
                        }
                    outcomes.forEachIndexed { wi, o ->
                        val slice = wave[wi]
                        windowResults += (o.facts != null)
                        if (o.rttMs > 0) windowRtts += o.rttMs
                        if (o.facts != null) {
                            extractionCalls++
                            consecutiveFailures = 0
                            FactStore.append(
                                req.outDir,
                                FactStore.chapterFileName(slice.chapterIndex),
                                o.facts.map { it.toJson().toString() },
                            )
                        } else {
                            consecutiveFailures++
                            anomalies += "片 ${slice.id} 抽取失败：${o.error}"
                        }
                    }
                    cp = cp.withSlices(
                        outcomes.mapIndexed { wi, o -> if (o.facts != null) wave[wi].id else null }.filterNotNull(),
                        outcomes.mapIndexed { wi, o -> if (o.facts != null) wave[wi].chapterIndex else null }
                            .filterNotNull().distinct(),
                        System.currentTimeMillis(),
                    )
                    onCp(cp)
                    val decision = NetworkGuard.decide(
                        NetSample(windowRtts, windowResults, consecutiveFailures, reconnects = 0),
                        concurrency,
                    )
                    windowResults.clear()
                    windowRtts.clear()
                    when (decision) {
                        is NetDecision.Pause -> {
                            pauseReason = decision.reason
                            return true
                        }
                        is NetDecision.Proceed -> concurrency = decision.concurrency
                    }
                    i += wave.size
                }
            } finally {
                pool.shutdownNow()
            }
            return false
        }

        /** 单片抽取：行号清单进提示词，回文宽松解析；失败返回 error。 */
        private fun callSlice(req: RunRequest, slice: ReadSlice): SliceOutcome {
            val prompt = factPrompt(slice, req.lines)
            val t0 = System.nanoTime()
            val answer = port.call(prompt)
            val rtt = (System.nanoTime() - t0) / 1_000_000
            val (facts, issues) = FactParser.parse(answer, slice, System.currentTimeMillis())
            synchronized(anomalies) { anomalies += issues }
            return SliceOutcome(slice.id, slice.chapterIndex, facts, null, rtt)
        }

        /** 归并（严格按 MergePlanner 的树；输入未就绪如实跳过）。 */
        fun runMerges(req: RunRequest, cp0: Checkpoint, onCp: (Checkpoint) -> Unit) {
            var cp = cp0
            req.mergeCalls.forEach { call ->
                if (call.outputId in cp.doneMerges) return@forEach
                val inputs = mergeInputs(req, call)
                if (inputs == null) {
                    anomalies += "归并 ${call.outputId}（${call.kind}）跳过：输入未就绪"
                    return@forEach
                }
                val answer = runCatching { port.call(mergePrompt(call, inputs)) }.getOrElse { e ->
                    anomalies += "归并 ${call.outputId} 失败：${e.message}"
                    return@forEach
                }
                mergeCalls++
                val out = MergeLine.payloadFrom(answer) ?: run {
                    anomalies += "归并 ${call.outputId} 的回文里没有 JSON 对象，跳过落盘"
                    return@forEach
                }
                FactStore.append(
                    req.outDir,
                    mergeFileOf(call),
                    listOf(MergeLine(typeOf(call.kind), call.outputId, call.inputIds.size, out, System.currentTimeMillis()).toJson().toString()),
                )
                cp = cp.withMerge(call.outputId, System.currentTimeMillis())
                onCp(cp)
            }
        }

        /**
         * 归并输入的取材：章层＝各片的全部事实（片 id）或章层中间产物（>100 片的章
         * 就地分层后的组产物，从章 jsonl 按 id 读回）；块层＝各章的章摘要
         * （**单片章没有章归并调用 ⇒ 就地合成确定性本地摘要**，零调用、有界——
         * 块层提示词才有界）；书层＝各块的块摘要。任何一个输入缺失 ⇒ null（如实跳过）。
         */
        private fun mergeInputs(req: RunRequest, call: MergeCall): String? {
            val parts = call.inputIds.map { id ->
                when (call.kind) {
                    MergeCall.KIND_CHAPTER -> chapterInputText(req, id)
                    MergeCall.KIND_BLOCK -> ensureChapterArtifact(req, id)
                    else -> mergeLinePayload(req, FactStore.blockFileName(blockIdIndex(id)), id)
                }
            }
            if (parts.any { it == null }) return null
            return parts.filterNotNull().joinToString("\n")
        }

        /** 章层输入：片 id ⇒ 该片的全部事实；组产物 id ⇒ 那次归并的摘要（盘上读回）。 */
        private fun chapterInputText(req: RunRequest, id: String): String? =
            if (SLICE_ID.matches(id)) sliceFactsText(req, id)
            else mergeLinePayload(req, FactStore.chapterFileName(chapterIdIndex(id)), id)

        /**
         * 章摘要（块层的输入）：
         * - 多片章：章归并调用已把摘要写进章 jsonl（type=chapter-merge, id=cN）——直接用；
         * - 单片章：没有章归并调用 ⇒ 用**确定性本地摘要**（标题＋事实条数＋首条事实），
         *   落一行 `local:true` 的 chapter-merge（append-only，续跑直接复用）。零模型调用。
         * 没有事实文件的章 ⇒ null（那章根本没抽过，块层如实跳过）。
         */
        private fun ensureChapterArtifact(req: RunRequest, chapterId: String): String? {
            val ci = chapterIdIndex(chapterId)
            val file = File(req.outDir, FactStore.chapterFileName(ci))
            if (!file.exists()) return null
            mergeLinePayload(req, FactStore.chapterFileName(ci), chapterId)?.let { return it }
            val facts = FactStore.readLines(file)
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .filter { it.optString("type") == "fact" }
            val slice = req.slices.firstOrNull { it.chapterIndex == ci }
            val title = slice?.chapterTitle ?: "第$ci 章"
            val first = facts.firstOrNull()
            val digest = "$title｜${facts.size} 条事实" + when {
                first == null -> "｜（本片未抽到事实）"
                else -> "｜首条：${first.optString("subject")} ${first.optString("predicate")}" +
                    " ${first.optString("object")}"
            }
            val payload = JSONObject()
                .put("summary", digest)
                .put("chapter", ci)
                .put("local", true)
            FactStore.append(
                req.outDir,
                FactStore.chapterFileName(ci),
                listOf(MergeLine(MergeLine.TYPE_CHAPTER_MERGE, chapterId, 1, payload, System.currentTimeMillis()).toJson().toString()),
            )
            return payload.toString()
        }

        /** 一个片的全部事实（从章 jsonl 按 slice_id 滤——内存不存账，盘上是唯一真源）。 */
        private fun sliceFactsText(req: RunRequest, sliceId: String): String? {
            val chapter = Regex("""c(\d+)t""").find(sliceId)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val facts = FactStore.readLines(File(req.outDir, FactStore.chapterFileName(chapter)))
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .filter { it.optString("type") == "fact" && it.optString("slice_id") == sliceId }
                .map { it.toString() }
            if (facts.isEmpty()) return null
            return facts.joinToString("\n")
        }

        /** 从产物文件里捞指定 id 的归并摘要行。 */
        private fun mergeLinePayload(req: RunRequest, fileName: String, id: String): String? =
            FactStore.readLines(File(req.outDir, fileName))
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .lastOrNull { it.optString("type").endsWith("-merge") && it.optString("id") == id }
                ?.optJSONObject("payload")
                ?.toString()

        private fun mergePrompt(call: MergeCall, body: String): String {
            val target = when (call.kind) {
                MergeCall.KIND_CHAPTER -> "把这一章各片的事实合成一份章摘要"
                MergeCall.KIND_BLOCK -> "把这些章摘要合成一份块摘要（含贯穿线索与人物线）"
                else -> "把这些块摘要合成全书摘要（主线、主要人物弧、伏笔清单）"
            }
            return "你是小说归并器。$target。只输出一个 JSON 对象" +
                "（含 summary 字段 ≤500 字；人物保留 subject_id，**同名不同 id 不许合并**）。" +
                "输入 ${call.inputIds.size} 份，如下：\n$body"
        }

        private fun factPrompt(slice: ReadSlice, lines: List<String>): String = buildString {
            append("你是小说事实抽取器。下面是第 ${slice.chapterIndex} 章「${slice.chapterTitle}」")
            append("第 ${slice.sliceIndex + 1}/${slice.sliceCount} 片（行 ${slice.startLine}–${slice.endLine}，0 起含端点）。\n")
            append("只输出 JSON 数组（不解释），每个元素：")
            append("{\"chapter\":${slice.chapterIndex},\"start_line\":行,\"end_line\":行,\"subject\":\"\",")
            append("\"subject_id\":\"名字#序\",\"predicate\":\"\",\"object\":\"\",\"quote\":\"原文≤30字\"}\n")
            append("硬规则：行号用正文左侧真实行号；同名不同人必须不同 subject_id；最多 40 条；无事实输出 []。\n")
            append("=== 正文 ===\n")
            if (slice.charStart >= 0) {
                // 超长单段的行内切片：这一行的部分内容，行号仍是真实行号（末片同）
                append(slice.startLine).append('\t').append(slice.textOf(lines)).append('\n')
            } else {
                for (n in slice.startLine..slice.endLine.coerceAtMost(lines.size - 1)) {
                    append(n).append('\t').append(lines[n]).append('\n')
                }
            }
        }

        private fun mergeFileOf(call: MergeCall): String = when (call.kind) {
            MergeCall.KIND_CHAPTER -> FactStore.chapterFileName(chapterIdIndex(call.outputId))
            MergeCall.KIND_BLOCK -> FactStore.blockFileName(blockIdIndex(call.outputId))
            else -> FactStore.BOOK_FILE
        }

        private fun typeOf(kind: String): String = when (kind) {
            MergeCall.KIND_CHAPTER -> MergeLine.TYPE_CHAPTER_MERGE
            MergeCall.KIND_BLOCK -> MergeLine.TYPE_BLOCK_MERGE
            else -> MergeLine.TYPE_BOOK_MERGE
        }

        private fun chapterIdIndex(id: String): Int =
            id.removePrefix("c").takeWhile { it.isDigit() }.toIntOrNull()
                ?: throw IllegalStateException("章产物 id 不认识：$id")

        private fun blockIdIndex(id: String): Int =
            id.removePrefix("b").takeWhile { it.isDigit() }.toIntOrNull()
                ?: throw IllegalStateException("块产物 id 不认识：$id")
    }

    // ── 人物覆盖抽样（每 N 章抽一；事实从盘上读，续跑后抽样口径不变） ────────

    private fun coverageOfRun(req: RunRequest, cp: Checkpoint): CharacterCoverage.Report? {
        val chapters = cp.doneChapters.sorted()
        val samples = chapters.filterIndexed { idx, _ -> idx % CharacterCoverage.SAMPLE_EVERY == 0 }
            .mapNotNull { ch ->
                val file = File(req.outDir, FactStore.chapterFileName(ch))
                if (!file.exists()) return@mapNotNull null
                val facts = FactStore.readLines(file)
                    .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                    .filter { it.optString("type") == "fact" }
                    .mapNotNull { FactLine.fromJson(it) }
                val slices = req.slices.filter { it.chapterIndex == ch }
                if (slices.isEmpty()) return@mapNotNull null
                val text = req.lines
                    .subList(slices.minOf { it.startLine }, slices.maxOf { it.endLine } + 1)
                    .joinToString("\n")
                CharacterCoverage.Sample(ch, text, facts)
            }
        return CharacterCoverage.sample(samples)
    }
}
