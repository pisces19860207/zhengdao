// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：docs/派工单-2026-10-19-第三十九批B-格式层落地.md §0／§1／§2（F1–F25 的接线部分）
//   ＋ 规格 §2.0（扩展名门禁在调用方做）／§4.1（体检输入）／§5.4（绝不覆盖）
//
// 两个工具：
// - `card_inspect`（FREE，**只读**）：认脸 ＋ 体检；这条链上一个写盘调用都没有（F11 有源码门禁盯着）
// - `card_export`（CONFIRM，**不覆盖**）：把一段 JSON 写成酒馆角色卡 PNG
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.BaopuGuardCore
import com.example.zhengdao.baopu.card.CardAsset
import com.example.zhengdao.baopu.card.CardAssetParser
import com.example.zhengdao.baopu.card.CardCheck
import com.example.zhengdao.baopu.card.CardJson
import com.example.zhengdao.baopu.card.CardPng
import com.example.zhengdao.baopu.card.CheckReport
import com.example.zhengdao.baopu.card.FengyueWork
import com.example.zhengdao.baopu.card.FormatKind
import com.example.zhengdao.baopu.card.FormatVerdict
import com.example.zhengdao.baopu.card.Level
import com.example.zhengdao.baopu.card.PngRead
import com.example.zhengdao.baopu.card.PngWrite
import com.example.zhengdao.baopu.card.Worldbook
import java.io.File

internal object CardTools {

    const val ARG_PATH = "path"
    const val ARG_OUT = "out"
    const val ARG_BASE = "base"

    /** 单次回灌的最多结论条数（再多就是刷屏，剩下的只报计数）。 */
    private const val MAX_LINES = 20

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
        ToolOutcome.Ok(inspect(bytes))
    }

    /**
     * 纯逻辑入口（JVM 直测，不碰文件系统）：字节进，人话报告出。
     *
     * **这条链上不许有写盘**（F11）：只有解析与统计。
     */
    fun inspect(bytes: ByteArray): String {
        val verdict = com.example.zhengdao.baopu.card.CardFormat.recognize(bytes)
        if (!verdict.isKnown) return describeUnknown(verdict)
        val parsed = CardAssetParser.parse(bytes)
            ?: return "认出了（${label(verdict.format)}），但读不出结构化内容。${verdict.message ?: ""}"
        val report = CardCheck.check(parsed)
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
        FormatKind.FENGYUE -> "网络平台世界书"
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
        is FengyueWork -> "work:${a.worldbook?.dialect?.name ?: "-"}"
        else -> a.javaClass.simpleName
    }

}
