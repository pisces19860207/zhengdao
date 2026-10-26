// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：docs/派工单-2026-10-19-第三十九批B-格式层落地.md §0／§1／§2（F1–F25 的接线部分）
//   ＋ 规格 §2.0（扩展名门禁在调用方做）／§4.1（体检输入）／§5.4（绝不覆盖）
//
// 两个工具：
// - `card_inspect`（FREE，**只读**）：认脸 ＋ 体检；这条链上一个写盘调用都没有（F11 有源码门禁盯着）
// - `card_export`（CONFIRM，**不覆盖**）：把一段 JSON 写成酒馆角色卡 PNG
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.BaopuGuardCore
import com.example.zhengdao.baopu.card.AssetKind
import com.example.zhengdao.baopu.card.CardAsset
import com.example.zhengdao.baopu.card.CardAssetParser
import com.example.zhengdao.baopu.card.CardBuild
import com.example.zhengdao.baopu.card.CardCheck
import com.example.zhengdao.baopu.card.CardFormat
import com.example.zhengdao.baopu.card.CardJson
import com.example.zhengdao.baopu.card.CardPng
import com.example.zhengdao.baopu.card.CardRoster
import com.example.zhengdao.baopu.card.CheckReport
import com.example.zhengdao.baopu.card.FormatKind
import com.example.zhengdao.baopu.card.FormatVerdict
import com.example.zhengdao.baopu.card.Level
import com.example.zhengdao.baopu.card.PngRead
import com.example.zhengdao.baopu.card.PngWrite
import com.example.zhengdao.baopu.card.WebWork
import com.example.zhengdao.baopu.card.Worldbook
import java.io.File

internal object CardTools {

    const val ARG_PATH = "path"
    const val ARG_OUT = "out"
    const val ARG_BASE = "base"

    /** 单次回灌的最多结论条数（再多就是刷屏，剩下的只报计数）。 */
    private const val MAX_LINES = 20

    /** 人设描述折叠阈值：超过这么多个字就只显示开头（第二百二十五批）。 */
    private const val PERSONA_PREVIEW = 80

    /** 正则／预设／未解析三节各自最多列几条（其余只报计数）。 */
    private const val ASSET_PREVIEW = 3

    /** 折叠长文时把换行压成空格（只影响显示）。 */
    private val WHITESPACE = Regex("\\s+")

    // ── card_inspect（FREE／只读）─────────────────────────────────────────

    fun inspectSpec(): ToolSpec = ToolSpec(
        name = "card_inspect",
        description = "只读体检一张卡/世界书：先认脸（网络平台世界书 / Tavo 世界书·角色卡·正则·预设 / " +
            "酒馆世界书 / CCv3 世界书 / 酒馆角色卡 PNG；认不出就报未知＋看过的键名），再逐条体检" +
            "（字段缺失与越界、关键词过宽、永不触发、分隔符写坏、同名压制）。**只读**：不改、不挪、不删任何文件。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "文件路径（相对工作区根）。只支持 .json 与 .png 两种扩展名",
                ),
            ),
            "required" to jsonArrayOf(ARG_PATH),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true, // 纯文件读：无共享可变状态，可与别的读并行
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        val raw = args.optString(ARG_PATH).trim()
        if (raw.isBlank()) return@ToolSpec ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        val file = File(raw)
        if (!file.exists()) return@ToolSpec ToolOutcome.Failed("不存在：${file.name}（先 list_dir 看结构再决定）")
        if (!file.isFile) return@ToolSpec ToolOutcome.Failed("不是一个文件：${file.name}")
        val ext = file.extension.lowercase()
        if (ext != "json" && ext != "png") {
            return@ToolSpec ToolOutcome.Failed("只支持 .json 和 .png 两种文件。如果是 .txt，请先改回 .json 再导入。")
        }
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return@ToolSpec ToolOutcome.Failed("读不了这个文件（权限或 IO 错误）：${file.name}")
        }
        ToolOutcome.Ok(inspect(bytes, CardRoster.findNear(file)))
    }

    /**
     * 纯逻辑入口（JVM 直测，不碰文件系统）：字节进，人话报告出。
     *
     * **这条链上不许有写盘**（F11）：只有解析与统计。
     */
    fun inspect(bytes: ByteArray, roster: CardRoster? = null): String {
        val verdict = com.example.zhengdao.baopu.card.CardFormat.recognize(bytes)
        if (!verdict.isKnown) return describeUnknown(verdict)
        val parsed = CardAssetParser.parse(bytes)
            ?: return "认出了（${label(verdict.format)}），但读不出结构化内容。${verdict.message ?: ""}"
        val report = CardCheck.check(parsed, roster = roster)
        return buildString {
            appendLine("【认脸】${label(verdict.format)}${subtitle(verdict)}（${conf(verdict)}）")
            appendLine("依据：${verdict.evidence.joinToString("；").ifEmpty { "（无）" }}")
            verdict.entryCount?.let { appendLine("条数：$it") }
            if (verdict.defaultsApplied.isNotEmpty()) {
                appendLine("已补默认值：${verdict.defaultsApplied.joinToString("、")}（平台合法省略，不算缺字段）")
            }
            if (verdict.conflicts.isNotEmpty()) {
                appendLine("并存特征：${verdict.conflicts.joinToString("；")}")
            }
            assetSections(parsed)
            appendLine()
            appendLine("【体检】${sum(report)}")
            val shown = report.findings.take(MAX_LINES)
            for (f in shown) {
                appendLine("- [${f.level}] ${f.message}")
            }
            if (report.findings.size > shown.size) {
                appendLine("（还有 ${report.findings.size - shown.size} 条未显示）")
            }
            if (report.findings.isEmpty()) appendLine("- 没发现问题。")
            appendLine()
            appendLine("【计数】${stats(report)}")
            if (report.skipped.isNotEmpty()) {
                appendLine("【跳过】${report.skipped.joinToString("；") { "${it.check}（${it.reason}）" }}")
            }
            appendLine("（只读体检：没有改动任何文件）")
        }.trimEnd()
    }

    // ── 人设／正则／预设三节（第二百二十五批）──────────────────────────────
    //
    // 落在**既有卡详情**（`card_inspect` 的这份报告）里，不新开页面。
    // 三节都是「有则显示」：源里没有这一段，整节连标题都不出现——
    // 空节比没有更误导（会让人以为「读到了、是空的」）。

    private fun StringBuilder.assetSections(p: CardAssetParser.Parsed) {
        p.persona?.let { personaSection(it) }
        p.regex?.let { regexSection(it) }
        p.preset?.let { presetSection(it) }
        if (p.unparsed.isNotEmpty()) unparsedSection(p.unparsed)
    }

    private fun StringBuilder.personaSection(p: Persona) {
        val bits = ArrayList<String>()
        p.name?.takeIf { it.isNotBlank() }?.let { bits += "名：$it" }
        p.description?.takeIf { it.isNotBlank() }?.let {
            bits += if (it.length > PERSONA_PREVIEW) "描述 ${it.length} 字（长文折叠）" else "描述 ${it.length} 字"
        }
        if (p.traits.isNotEmpty()) bits += "标签 ${p.traits.size} 个"
        p.scenario?.takeIf { it.isNotBlank() }?.let { bits += "场景 ${it.length} 字" }
        if (p.examples.isNotEmpty()) bits += "样例 ${p.examples.size} 段"
        appendLine("【人设】${bits.joinToString("｜")}")
        p.description?.takeIf { it.isNotBlank() }?.let { appendLine("  描述：${fold(it)}") }
        if (p.traits.isNotEmpty()) appendLine("  标签：${p.traits.joinToString("、")}")
    }

    private fun StringBuilder.regexSection(r: RegexScript) {
        appendLine("【正则】${r.entries.size} 条${stateSummary(r.entries.map { it.enabled })}")
        r.entries.take(ASSET_PREVIEW).forEachIndexed { i, e ->
            appendLine("  ${i + 1}. ${regexLine(e)}")
        }
        if (r.entries.size > ASSET_PREVIEW) {
            appendLine("  （还有 ${r.entries.size - ASSET_PREVIEW} 条未显示）")
        }
    }

    private fun regexLine(e: RegexEntryView): String {
        val bits = ArrayList<String>()
        bits += e.find?.let { "匹配 ${shorten(it, 40)}" } ?: "匹配（未写）"
        e.replace?.let { bits += if (it.isEmpty()) "替换（空串）" else "替换 ${shorten(it, 24)}" }
        if (e.flags.isNotEmpty()) bits += "标志 ${e.flags.joinToString("")}"
        if (e.scope.isNotEmpty()) bits += "范围 ${e.scope.joinToString("、")}"
        bits += stateWord(e.enabled)
        return bits.joinToString("｜")
    }

    private fun StringBuilder.presetSection(p: Preset) {
        appendLine("【预设】${p.entries.size} 条${stateSummary(p.entries.map { it.enabled })}")
        p.entries.take(ASSET_PREVIEW).forEachIndexed { i, e ->
            val name = e.name?.takeIf { it.isNotBlank() } ?: "（未写名）"
            val len = e.prompt?.let { "提示词 ${it.length} 字" } ?: "提示词（未写）"
            appendLine("  ${i + 1}. $name｜$len｜${stateWord(e.enabled)}")
        }
        if (p.entries.size > ASSET_PREVIEW) {
            appendLine("  （还有 ${p.entries.size - ASSET_PREVIEW} 条未显示）")
        }
    }

    /** 读不出来的条目：**不丢**，把原文截一段摆出来（截断只影响显示，不影响 raw）。 */
    private fun StringBuilder.unparsedSection(list: List<UnparsedAsset>) {
        appendLine("【未解析】${list.size} 条（原文已保留，未改动任何文件）")
        list.take(ASSET_PREVIEW).forEach { u ->
            val where = if (u.index >= 0) "${assetName(u.kind)}[${u.index + 1}]" else assetName(u.kind)
            appendLine("  $where：${u.reason}｜原文：${shorten(u.rawText, 60)}")
        }
        if (list.size > ASSET_PREVIEW) {
            appendLine("  （还有 ${list.size - ASSET_PREVIEW} 条未显示）")
        }
    }

    private fun stateSummary(states: List<Boolean?>): String {
        if (states.isEmpty()) return ""
        val on = states.count { it == true }
        val off = states.count { it == false }
        val unknown = states.count { it == null }
        return "（启用 $on／关闭 $off／未标 $unknown）"
    }

    private fun stateWord(enabled: Boolean?): String = when (enabled) {
        true -> "启用"
        false -> "关闭"
        null -> "未标启用"
    }

    private fun assetName(kind: AssetKind): String = when (kind) {
        AssetKind.PERSONA -> "人设"
        AssetKind.REGEX -> "正则"
        AssetKind.PRESET -> "预设"
    }

    /** 折成一行再截断：长文只显示开头，尾巴标出总字数（**不改原文**）。 */
    private fun fold(text: String): String {
        val oneLine = text.replace(WHITESPACE, " ").trim()
        if (oneLine.length <= PERSONA_PREVIEW) return oneLine
        return oneLine.take(PERSONA_PREVIEW) + "…（共 ${text.length} 字，已折叠）"
    }

    private fun shorten(text: String, max: Int): String {
        val oneLine = text.replace(WHITESPACE, " ").trim()
        return if (oneLine.length <= max) oneLine else oneLine.take(max) + "…"
    }

    // ── card_export（CONFIRM／不覆盖）─────────────────────────────────────
    fun exportSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "card_export",
        description = "把一段 JSON 写成酒馆角色卡 PNG（内嵌 chara 与 ccv3 两块文本，插在 IEND 之前）。" +
            "**不覆盖**：目标已存在就拒绝；源文件一个字节都不改。需要用户确认后执行。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf("type" to "string", "description" to "源 JSON 文件（相对工作区根）"),
                ARG_OUT to jsonObjectOf("type" to "string", "description" to "目标 PNG（相对工作区根，必须不存在）"),
                ARG_BASE to jsonObjectOf("type" to "string", "description" to "可选：底图 PNG（不给就现画 1×1 白图）"),
            ),
            "required" to jsonArrayOf(ARG_PATH, ARG_OUT),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        val root = workspaceRoot()
        val srcRaw = args.optString(ARG_PATH).trim()
        val outRaw = args.optString(ARG_OUT).trim()
        if (srcRaw.isBlank()) return@ToolSpec ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        if (outRaw.isBlank()) return@ToolSpec ToolOutcome.Failed("缺少必填参数「$ARG_OUT」")

        val src = resolve(root, srcRaw) ?: return@ToolSpec ToolOutcome.Rejected("路径越界被拒：$srcRaw 不在工作区内")
        val out = resolve(root, outRaw) ?: return@ToolSpec ToolOutcome.Rejected("路径越界被拒：$outRaw 不在工作区内")
        if (!src.isFile) return@ToolSpec ToolOutcome.Failed("源文件不存在：${src.name}")
        if (!out.path.lowercase().endsWith(".png")) return@ToolSpec ToolOutcome.Failed("目标必须是 .png 文件")

        val bytes = runCatching { src.readBytes() }.getOrElse {
            return@ToolSpec ToolOutcome.Failed("读不了源文件（权限或 IO 错误）：${src.name}")
        }
        val json = CardJson.decodeUtf8Strict(bytes)
            ?: return@ToolSpec ToolOutcome.Failed("源文件不是 UTF-8，无法无损导出。")
        val base = args.optString(ARG_BASE).trim().takeIf { it.isNotEmpty() }?.let { b ->
            val f = resolve(root, b) ?: return@ToolSpec ToolOutcome.Rejected("路径越界被拒：$b 不在工作区内")
            f.takeIf { it.isFile }?.readBytes()
        }
        when (val r = CardPng.export(json, base, out)) {
            is PngWrite.Ok -> ToolOutcome.Ok("已写出：${r.path.name}（${r.path.length()} B，chara＋ccv3 两块；源文件未改动）")
            is PngWrite.Failed -> ToolOutcome.Failed(r.message)
        }
    }

    // ── 人话 ─────────────────────────────────────────────────────────────

    // ── card_build（CONFIRM／写盘：母档 → 一套产物）────────────────────────

    const val ARG_SPEC = "spec"
    const val ARG_OUT_DIR = "out_dir"

    fun buildSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "card_build",
        description = "从母档生成一套卡/世界书/五件套骨架（会写盘，弹确认卡）。" +
            "format 只认 web／tavo／st：web 出 world_book.json＋提示词/前置词/后置词/内置CSS/详细介绍；" +
            "tavo 出 card.json＋lorebook.json；st 出 card.json＋card.png（内嵌 chara/ccv3）。" +
            "**已存在的文件一律拒绝，不覆盖**；单字关键词会被拒（积分刺客）。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_SPEC to jsonObjectOf(
                    "type" to "string",
                    "description" to "母档 JSON 的相对路径（默认 母档.json）",
                ),
                ARG_OUT_DIR to jsonObjectOf(
                    "type" to "string",
                    "description" to "输出目录（相对工作区根；省略则 成品/<name>/）",
                ),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        val root = workspaceRoot()
        val specRaw = args.optString(ARG_SPEC).trim().ifEmpty { "母档.json" }
        val specFile = resolve(root, specRaw)
            ?: return@ToolSpec ToolOutcome.Rejected("路径越界被拒：$specRaw 不在工作区内")
        if (!specFile.isFile) return@ToolSpec ToolOutcome.Failed("母档不存在：$specRaw")
        val outRel = args.optString(ARG_OUT_DIR).trim().takeIf { it.isNotEmpty() }
        if (outRel != null && resolve(root, outRel) == null) {
            return@ToolSpec ToolOutcome.Rejected("路径越界被拒：$outRel 不在工作区内")
        }
        val text = runCatching { specFile.readText(Charsets.UTF_8) }.getOrElse {
            return@ToolSpec ToolOutcome.Failed("读不了母档：$specRaw")
        }
        val spec = runCatching { CardBuild.parseSpec(text) }.getOrElse {
            return@ToolSpec ToolOutcome.Failed(it.message ?: "母档解析失败")
        }
        val built = runCatching { CardBuild.build(spec, root, outRel) }.getOrElse {
            return@ToolSpec ToolOutcome.Failed(it.message ?: "生成失败")
        }
        val lines = StringBuilder()
        lines.appendLine("已生成 ${built.files.size} 个文件 → ${built.outDir.relativeTo(root).path}")
        built.files.forEach { lines.appendLine("· ${it.name}（${it.length()} B）") }
        // 自检：能认脸的产物先认一次（web 的世界书；tavo/st 的卡）
        val checkTarget = when (spec.format) {
            CardBuild.FORMAT_WEB -> built.files.firstOrNull { it.name == "world_book.json" }
            else -> built.files.firstOrNull { it.name == "card.json" }
        }
        if (checkTarget != null) {
            val verdict = CardFormat.recognize(checkTarget.readBytes())
            lines.appendLine("自检：${if (verdict.isKnown) "认脸通过" else "认脸未通过"}（${verdict.format.name}）")
        }
        ToolOutcome.Ok(lines.toString().trimEnd())
    }

    private fun resolve(root: File, raw: String): File? {
        val f = if (File(raw).isAbsolute) File(raw) else File(root, raw)
        return when (BaopuGuardCore.checkPath(root, f)) {
            is BaopuGuardCore.PathVerdict.Inside -> f
            else -> null
        }
    }

    private fun describeUnknown(v: FormatVerdict): String = buildString {
        appendLine(v.message ?: "认不出这个文件是什么格式。")
        if (v.keysSeen.isNotEmpty()) {
            appendLine("看过的顶层键：${v.keysSeen.joinToString(", ")}")
        }
        if (v.pngTextKeys.isNotEmpty()) {
            appendLine("PNG 里的文本块：${v.pngTextKeys.joinToString(", ")}")
        }
        append("（只读体检：没有改动任何文件）")
    }

    private fun label(f: FormatKind): String = when (f) {
        FormatKind.WEB_BOOK -> "网络平台世界书"
        FormatKind.TAVO_BOOK -> "Tavo 世界书"
        FormatKind.TAVO_CHAR -> "Tavo 角色卡"
        FormatKind.TAVO_REGEX -> "Tavo 正则"
        FormatKind.TAVO_PRESET -> "Tavo 预设"
        FormatKind.ST_WORLD -> "酒馆原生世界书"
        FormatKind.CCV3_LOREBOOK -> "CCv3 独立世界书"
        FormatKind.ST_CARD_PNG -> "酒馆角色卡 PNG"
        FormatKind.UNKNOWN -> "未知"
    }

    private fun subtitle(v: FormatVerdict): String = v.subtype?.let { " / $it" } ?: ""

    private fun conf(v: FormatVerdict): String = v.confidence?.name ?: "不适用"

    private fun sum(r: CheckReport): String {
        val e = r.findings.count { it.level == Level.ERROR }
        val w = r.findings.count { it.level == Level.WARN }
        val i = r.findings.count { it.level == Level.INFO }
        return "必须修 $e / 建议修 $w / 知悉 $i"
    }

    private fun stats(r: CheckReport): String {
        val s = r.stats
        val c = s.cCount?.toString() ?: "未判"
        return "条目 ${s.entries}｜永不触发 $c 条｜未判 ${s.unjudgedCount} 条｜不适用 ${s.notApplicableCount} 条" +
            if (s.broadKeyCount > 0) "｜关键词过宽 ${s.broadKeyCount} 条" else ""
    }

    /** 供测试断言「认脸结果里带着型号」用（避免测试直接依赖内部枚举名）。 */
    internal fun assetKind(a: CardAsset): String = when (a) {
        is Worldbook -> "worldbook:${a.dialect.name}"
        is WebWork -> "work:${a.worldbook?.dialect?.name ?: "-"}"
        else -> a.javaClass.simpleName
    }

}
