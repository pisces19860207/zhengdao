// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十七批（2026-11-12）：立绘外貌两件（单 597）。
//
// - `portrait_look`（FREE，只读图＋落一行账）：判据①本地特征（分区域主色/宽高比/半身全身，
//   纯判据层 [PortraitFeatures]，**零模型**）；同角色 ≥2 张自动列差异（判据④：**只报不改**，
//   本工具没有写卡参数）。每张卡都弹的话"看一眼"就成打扰——账本写盘与 ledger_add 同口径
//   （FREE＋R1，账是 `.appearance.jsonl` 点号文件）。
// - `portrait_appearance`（CONFIRM）：三个动作。
//   · `draft`：把外貌文本记成**草稿**（判据③：只是账上一行，没进卡）；
//   · `describe`：**全仓唯一**把图片字节发出去的动作——先过 [PortraitDescribeGate]
//     （`Provider.vision=false` ⇒ "该模型看不了图"，一个字节不发；负例②），能发也因为
//     本工具是 CONFIRM：管线第③段末尾过 467 租约闸（命中免卡、未命中弹卡；判据③），
//     出参 ≤120 字（[PortraitDescribeGate.clamp]）；
//   · `merge`：人批过的草稿进卡 `data.extensions.appearance`——**已有外貌一律拒**
//     （[PortraitMerge]，判据④负例），只进 JSON 卡（PNG 本单不重写，如实说）。
//
// 纪律（判据⑦）：图片字节只活在 describe 的 dataUrl 里（人批那次）；账本/草稿只存
// 路径与颜色文本，产物目录不会多出任何图片文件。
package com.example.zhengdao.baopu.agent

import android.graphics.BitmapFactory
import com.example.zhengdao.baopu.BaopuGuardCore
import com.example.zhengdao.baopu.LlmClient
import com.example.zhengdao.baopu.LlmEvent
import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.VisionRules
import com.example.zhengdao.baopu.portrait.PortraitAccount
import com.example.zhengdao.baopu.portrait.PortraitDecoder
import com.example.zhengdao.baopu.portrait.PortraitDescribeGate
import com.example.zhengdao.baopu.portrait.PortraitDraft
import com.example.zhengdao.baopu.portrait.PortraitFeatures
import com.example.zhengdao.baopu.portrait.PortraitLedger
import com.example.zhengdao.baopu.portrait.PortraitMerge
import com.example.zhengdao.baopu.portrait.PortraitPixels
import com.example.zhengdao.baopu.portrait.PortraitRead
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** 视觉描述出口：生产走 [LlmClient]；测试换假件数请求次数（负例②③的数法）。 */
internal fun interface VisionPort {
    fun describe(imageDataUrl: String, prompt: String): String
}

/** 生产解码器（Android）。判据层零 Android，解码在这里。 */
internal object AndroidPortraitDecoder : PortraitDecoder {
    override fun decode(file: File): PortraitPixels? = runCatching {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        PortraitPixels(w, h, px)
    }.getOrNull()
}

/** 生产视觉出口：一轮无工具对话，图随消息走，收满文本为止（房型同 LlmSubAgentTurn）。 */
internal class LlmVisionPort(private val provider: Provider) : VisionPort {
    override fun describe(imageDataUrl: String, prompt: String): String {
        val textBuf = StringBuilder()
        val latch = CountDownLatch(1)
        var failure: String? = null
        val call = LlmClient(provider).streamAsync(
            listOf(LlmMessage(LlmMessage.ROLE_USER, prompt, listOf(imageDataUrl))),
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
            throw IllegalStateException("等视觉模型超过 ${RunRules.STREAM_WAIT_TIMEOUT_MS / 1000}s，本轮作废")
        }
        failure?.let { throw IllegalStateException("视觉模型请求失败：$it") }
        return textBuf.toString()
    }
}

internal object PortraitAppearanceTool {

    const val ARG_PATHS = "paths"
    const val ARG_CHARACTER = "character"
    const val ARG_ACTION = "action"
    const val ARG_PATH = "path"
    const val ARG_APPEARANCE = "appearance"
    const val ARG_DRAFT_ID = "draft_id"
    const val ARG_CARD = "card"

    const val ACTION_DRAFT = "draft"
    const val ACTION_DESCRIBE = "describe"
    const val ACTION_MERGE = "merge"

    /** 一次最多看几张（与 VisionRules.MAX_IMAGES 同口径）。 */
    const val MAX_PATHS = 4

    /** 单图外发上限（与 VisionRules.MAX_IMAGE_BYTES 同口径；超了让模型先 resize）。 */
    private const val MAX_SEND_BYTES = VisionRules.MAX_IMAGE_BYTES

    /** 装配层注入（BaopuRuntime.registry；测试换假件）。 */
    internal class Env(
        val visionSupported: Boolean,
        val modelId: String,
        val port: VisionPort,
        val decoder: PortraitDecoder,
        val now: () -> Long = System::currentTimeMillis,
    )

    @Volatile internal var env: Env? = null

    private fun envOf(): Env? = env

    // ── portrait_look（FREE）───────────────────────────────────────────────

    fun lookSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "portrait_look",
        description = "看立绘的本地特征（不调模型、不发网）：每张给出 尺寸/宽高比、半身还是全身" +
            "（头身比）、发区/瞳区/服饰区主色。同一批 ≥2 张自动列差异（发色/瞳色/取景/宽高比），" +
            "**只报不改**——本工具没有任何写卡参数。每次看图落一行账（产物/.appearance.jsonl，" +
            "模型=本地、未外发）。",
        inputSchema = lookSchema(),
        permission = ToolPermission.FREE, // 读图＋落一行账；每张弹卡就把"看一眼"变成打扰（ledger_add 同口径）
        riskTier = "R1",
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> look(workspaceRoot(), args) }

    private fun lookSchema() = jsonObjectOf(
        "type" to "object",
        "properties" to jsonObjectOf(
            ARG_CHARACTER to jsonObjectOf(
                "type" to "string",
                "description" to "角色名（落账与草稿归属用；可省）",
            ),
            ARG_PATHS to jsonObjectOf(
                "type" to "array",
                "items" to jsonObjectOf("type" to "string"),
                "minItems" to 1,
                "maxItems" to MAX_PATHS,
                "description" to "立绘路径（相对工作区根，png/jpg/webp；1–$MAX_PATHS 张）",
            ),
        ),
        "required" to jsonArrayOf(ARG_PATHS),
        "additionalProperties" to false,
    )

    private fun look(root: File, args: JSONObject): ToolOutcome {
        val e = envOf() ?: return ToolOutcome.Failed("portrait_look 未装配（重启 App 后重试）")
        val paths = args.optJSONArray(ARG_PATHS)
            ?: return ToolOutcome.Failed("缺必填参数「$ARG_PATHS」")
        if (paths.length() < 1 || paths.length() > MAX_PATHS) {
            return ToolOutcome.Failed("paths 要 1–$MAX_PATHS 张（给了 ${paths.length()}）")
        }
        val character = args.optString(ARG_CHARACTER).trim()
        val lines = StringBuilder()
        val reads = ArrayList<Pair<String, PortraitRead?>>()
        for (i in 0 until paths.length()) {
            val rel = paths.optString(i).trim()
            val read = readOne(e.decoder, root, rel)?.second
            reads.add(rel to read)
            lines.appendLine(lookLine(rel, read))
            PortraitLedger.appendAccount(
                root,
                PortraitAccount(
                    ts = e.now(), op = PortraitLedger.OP_LOOK, image = rel,
                    model = "本地", sentOut = false, ok = read != null,
                    note = if (read == null) "解码失败" else "",
                ),
            )
        }
        if (character.isNotEmpty()) lines.insert(0, "角色：$character\n")
        if (reads.size >= 2) appendDiffs(lines, reads)
        lines.appendLine("——已落账 ${reads.size} 张（模型=本地；未外发）→ 产物/.appearance.jsonl")
        return ToolOutcome.Ok(lines.toString().trimEnd())
    }

    /** 一张图的一行读数（判不出的位置如实写"判不出"）。 */
    private fun lookLine(rel: String, read: PortraitRead?): String {
        if (read == null) return "· $rel：读不了（不是图或文件坏）"
        val head = 1.0 / read.headFraction.coerceAtLeast(1e-9)
        return "· $rel：${read.width}×${read.height}（宽高比 ${read.aspect}）" +
            "·${read.extent.label}（头身比≈1:%.1f）".format(head) +
            "·发 ${read.hairColor?.hex ?: "判不出"}" +
            "·瞳 ${read.eyeColor?.hex ?: "判不出"}" +
            "·服饰 ${read.clothingColor?.hex ?: "判不出"}"
    }

    /** 差异清单（判据④：只列差异；对比对象=第一张）。 */
    private fun appendDiffs(lines: StringBuilder, reads: List<Pair<String, PortraitRead?>>) {
        val first = reads.first().second ?: return
        var any = false
        for (i in 1 until reads.size) {
            val other = reads[i].second ?: continue
            val diffs = PortraitFeatures.compare(first, other)
            if (diffs.isEmpty()) continue
            any = true
            val detail = diffs.joinToString("；") { "${it.kind} ${it.detail}" }
            lines.appendLine("差异 ①↔${'①' + i}（只报不改）：$detail")
        }
        if (!any) lines.appendLine("差异：这几张没判出可报的差异（同发色/瞳色/取景）。")
    }

    // ── portrait_appearance（CONFIRM）─────────────────────────────────────

    fun appearanceSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "portrait_appearance",
        description = "外貌三动作（每次弹确认卡）：draft＝把外貌文本记成**草稿**（人批后才进卡）；" +
            "describe＝调视觉模型出 ≤120 字外貌描述——当前通道看不了图就直接说看不了（**不发请求**），" +
            "能看也只发确认卡上那一张；merge＝把人批的草稿写进卡的 data.extensions.appearance，" +
            "**卡里已有外貌一律拒（不覆盖）**，只进 JSON 卡。",
        inputSchema = appearanceSchema(),
        permission = ToolPermission.CONFIRM,
        riskTier = "R1",
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        val root = workspaceRoot()
        when (args.optString(ARG_ACTION).trim()) {
            ACTION_DRAFT -> draft(root, args)
            ACTION_DESCRIBE -> describe(root, args)
            ACTION_MERGE -> merge(root, args)
            else -> ToolOutcome.Failed(
                "action 只认 $ACTION_DRAFT/$ACTION_DESCRIBE/$ACTION_MERGE",
            )
        }
    }

    private fun appearanceSchema() = jsonObjectOf(
        "type" to "object",
        "properties" to jsonObjectOf(
            ARG_ACTION to jsonObjectOf(
                "type" to "string",
                "enum" to jsonArrayOf(ACTION_DRAFT, ACTION_DESCRIBE, ACTION_MERGE),
                "description" to "draft=记草稿；describe=视觉模型描述（唯一外发动作）；merge=草稿进卡",
            ),
            ARG_CHARACTER to jsonObjectOf("type" to "string", "description" to "角色名（draft 用）"),
            ARG_PATH to jsonObjectOf(
                "type" to "string",
                "description" to "立绘路径（相对工作区根；draft/describe 用）",
            ),
            ARG_APPEARANCE to jsonObjectOf(
                "type" to "string",
                "description" to "外貌文本（draft 用；人批后才进卡）",
            ),
            ARG_DRAFT_ID to jsonObjectOf("type" to "string", "description" to "草稿 id（merge 用）"),
            ARG_CARD to jsonObjectOf(
                "type" to "string",
                "description" to "角色卡 JSON 路径（相对工作区根；merge 用）",
            ),
        ),
        "required" to jsonArrayOf(ARG_ACTION),
        "additionalProperties" to false,
    )

    // ── 三个动作 ────────────────────────────────────────────────────────────

    /** draft：记草稿（判据③：只是账上一行，没进卡）。 */
    private fun draft(root: File, args: JSONObject): ToolOutcome {
        val e = envOf() ?: return ToolOutcome.Failed("portrait_appearance 未装配")
        val character = args.optString(ARG_CHARACTER).trim()
        val rel = args.optString(ARG_PATH).trim()
        val text = args.optString(ARG_APPEARANCE).trim()
        if (character.isEmpty()) return ToolOutcome.Failed("draft 需要 character（外貌归谁）")
        if (rel.isEmpty()) return ToolOutcome.Failed("draft 需要 path（特征从哪张图来）")
        if (text.isEmpty()) return ToolOutcome.Failed("draft 需要 appearance（外貌文本；先 portrait_look 再写）")
        val read = readOne(e.decoder, root, rel)?.second
        val features = read?.let { lookLine(rel, it).removePrefix("· $rel：") } ?: "本地特征：解码失败"
        val saved = PortraitLedger.appendDraft(
            root,
            PortraitDraft(
                id = "", ts = e.now(), character = character, image = rel,
                appearance = text, features = features, model = "本地", sentOut = false,
            ),
        )
        return ToolOutcome.Ok(
            "草稿 ${saved.id} 已记（只是草稿，没进卡）。\n" +
                "人批途径：portrait_appearance action=$ACTION_MERGE draft_id=${saved.id} card=<角色卡.json>（确认卡）。",
        )
    }

    /** describe：判据②③的执行点——闸说了算，闸说不行就一个字节都不发。 */
    private fun describe(root: File, args: JSONObject): ToolOutcome {
        val e = envOf() ?: return ToolOutcome.Failed("portrait_appearance 未装配")
        val rel = args.optString(ARG_PATH).trim()
        if (rel.isEmpty()) return ToolOutcome.Failed("describe 需要 path（描述哪张图）")
        val decoded = readOne(e.decoder, root, rel)
            ?: return ToolOutcome.Failed("读不了这张图（不在工作区/不是图）：$rel")
        val (file, read) = decoded
        if (read == null) return ToolOutcome.Failed("解码不了这张图（文件坏或格式不受支持）：$rel")
        val lines = StringBuilder(lookLine(rel, read)).appendLine()
        val verdict = PortraitDescribeGate.decide(visionSupported = e.visionSupported, approved = true)
        if (!verdict.allowed) {
            PortraitLedger.appendAccount(
                root,
                account(e, PortraitLedger.OP_DESCRIBE, rel, sentOut = false, ok = true, note = verdict.note),
            )
            lines.appendLine(verdict.note)
            lines.append("——本地特征如上；没发任何请求。")
            return ToolOutcome.Ok(lines.toString())
        }
        return describeSend(root, e, file, rel, lines)
    }

    /** 闸放行后的唯一外发路径（CONFIRM 弹卡/467 租约在管线已拦过一道）。 */
    private fun describeSend(
        root: File, e: Env, file: File, rel: String, lines: StringBuilder,
    ): ToolOutcome {
        if (file.length() > MAX_SEND_BYTES) {
            return ToolOutcome.Failed("这张图超过 12MB：先用 image_ops resize 再 describe。")
        }
        val dataUrl = dataUrlOf(file)
        if (dataUrl == null) {
            return ToolOutcome.Failed("认不出图片格式（只发 png/jpg/webp）：$rel")
        }
        val description = try {
            PortraitDescribeGate.clamp(e.port.describe(dataUrl, PortraitDescribeGate.PROMPT))
        } catch (t: Throwable) {
            PortraitLedger.appendAccount(root, account(e, PortraitLedger.OP_DESCRIBE, rel, true, false, t.message ?: "失败"))
            return ToolOutcome.Failed("视觉模型请求失败：${t.message}")
        }
        PortraitLedger.appendAccount(
            root, account(e, PortraitLedger.OP_DESCRIBE, rel, sentOut = true, ok = true, note = ""),
        )
        lines.appendLine("外貌描述（≤${PortraitDescribeGate.MAX_DESC_CHARS} 字，模型=${e.modelId}，该图已外发一次）：")
        lines.appendLine(description)
        lines.append("——要写卡：portrait_appearance action=$ACTION_DRAFT appearance=… （人批后 merge 进卡）。")
        return ToolOutcome.Ok(lines.toString())
    }

    private fun account(e: Env, op: String, image: String, sentOut: Boolean, ok: Boolean, note: String) =
        PortraitAccount(
            ts = e.now(), op = op, image = image, model = e.modelId, sentOut = sentOut, ok = ok, note = note,
        )

    /** merge：人批的草稿进卡（判据③/④：不覆盖、只进一次、只进 JSON 卡）。 */
    private fun merge(root: File, args: JSONObject): ToolOutcome {
        val e = envOf() ?: return ToolOutcome.Failed("portrait_appearance 未装配")
        val draftId = args.optString(ARG_DRAFT_ID).trim()
        val cardRel = args.optString(ARG_CARD).trim()
        if (draftId.isEmpty()) return ToolOutcome.Failed("merge 需要 draft_id")
        if (cardRel.isEmpty()) return ToolOutcome.Failed("merge 需要 card（进哪张卡）")
        val d = PortraitLedger.draftById(root, draftId)
            ?: return ToolOutcome.Failed("没有这份草稿：$draftId（先 action=$ACTION_DRAFT）")
        if (draftId in PortraitLedger.mergedDraftIds(root)) {
            return ToolOutcome.Rejected("草稿 $draftId 已进过卡：不重复进。")
        }
        val card = resolveIn(root, cardRel)
            ?: return ToolOutcome.Rejected("路径越界被拒：$cardRel 不在工作区内")
        if (!card.extension.equals("json", ignoreCase = true)) {
            return ToolOutcome.Failed("只进 JSON 角色卡；PNG 卡请先导出 JSON（本单不重写 PNG）。")
        }
        val before = runCatching { JSONObject(card.readText(Charsets.UTF_8)) }.getOrElse {
            return ToolOutcome.Failed("卡不是合法 JSON：$cardRel")
        }
        return when (val v = PortraitMerge.apply(before, d.appearance)) {
            is PortraitMerge.Verdict.Refuse -> ToolOutcome.Rejected(v.reason)
            is PortraitMerge.Verdict.Ok -> {
                card.writeText(v.card.toString(), Charsets.UTF_8)
                PortraitLedger.appendMerged(root, draftId, cardRel, e.now())
                ToolOutcome.Ok(
                    "已进卡 $cardRel（data.extensions.appearance；人批于确认卡）。\n外貌：${d.appearance}",
                )
            }
        }
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────

    /** 工作区内解析＋解码＋本地特征（读不了 ⇒ null，让调用方如实回话）。 */
    private fun readOne(decoder: PortraitDecoder, root: File, rel: String): Pair<File, PortraitRead?>? {
        val file = resolveIn(root, rel) ?: return null
        if (!file.isFile) return null
        if (ImageOps.formatOf(file.name) == null) return null
        return file to decoder.decode(file)?.let { PortraitFeatures.read(it) }
    }

    private fun resolveIn(root: File, raw: String): File? {
        val f = if (File(raw).isAbsolute) File(raw) else File(root, raw)
        return when (BaopuGuardCore.checkPath(root, f)) {
            is BaopuGuardCore.PathVerdict.Inside -> f
            else -> null
        }
    }

    /** 图 → data URL（判据⑦：只有这里、且只有人批那次才碰字节）。 */
    private fun dataUrlOf(file: File): String? {
        val mime = when (ImageOps.formatOf(file.name)) {
            "png" -> "image/png"
            "jpg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> return null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
    }
}
