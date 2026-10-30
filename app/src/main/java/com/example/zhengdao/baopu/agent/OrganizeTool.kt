// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十八批（2026-10-30）：`organize` + `organize_preview`——按规则把一个目录
// 归入子目录（按类型/按日期），**先预览再执行，执行后留回执可一键退回**。
//
// 两件分工（预览必须免确认——预览是「看」，不该每次都让人点一遍）：
// - organize_preview：FREE + 只读。同一份参数算出「将发生什么」，一行一条 源→目标；
//   判据「预览与实际一致」由共享计划函数保证（PathCopyCore.planOrganize/planBulk）。
// - organize：CONFIRM。真正动手；成功后写回执（产物/_整理回执/<id>.md），
//   undo_receipt 参数按回执逐条搬回原位（重名不覆盖）。
//
// 与技能打通：一次整理调好规则后 save_recipe 起个名字，规则存成
// `产物/_整理技能/<名字>.json`；下次 organize/organize_preview 带 recipe=名字
// 直接重放同一套规则（调用的显式参数优先于配方里的值）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.FileOpRules
import com.example.zhengdao.baopu.FilePaths
import com.example.zhengdao.baopu.FilePolicy
import com.example.zhengdao.baopu.PersonaGuard
import com.example.zhengdao.baopu.fileAccessCheck
import org.json.JSONObject
import java.io.File

internal object OrganizeTool {

    const val ARG_DIR = "dir"
    const val ARG_DEST = "dest"
    const val ARG_MODE = "mode"
    const val ARG_RECURSIVE = "recursive"
    const val ARG_UNDO = "undo_receipt"
    const val ARG_SAVE_RECIPE = "save_recipe"
    const val ARG_RECIPE = "recipe"
    const val ARG_OP = "op"

    /** 整理技能（配方）目录（工作区相对）。 */
    const val RECIPES_DIR_REL = "产物/_整理技能"

    // ── organize（执行 + 撤销） ───────────────────────────────────────────

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "organize",
        description = "整理一个目录：按规则把文件归入子目录（mode=type 按类型分 图片/视频/音频/文档/压缩包/其他；" +
            "mode=date 按修改年月分 2026-10 这样的子目录）。绝不覆盖：撞名不同内容自动加后缀让路。" +
            "执行成功后写回执（产物/_整理回执/），把 undo_receipt 给成回执 id 就能一键退回原位。" +
            "规则满意后用 save_recipe 存成技能，下次带 recipe=名字 直接重放同一套规则。" +
            "先预览再动手：用 organize_preview（同样参数）把计划给用户看，确认后再执行本件。",
        inputSchema = organizeSchema(),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.COPY_MS,
    ) { args ->
        val root = workspaceRoot()
        // 撤销分支：给了 undo_receipt 就是「按回执搬回」，其他整理参数全都不看
        val undo = args.optString(ARG_UNDO).trim()
        if (undo.isNotEmpty()) return@ToolSpec undo(root, undo)
        run(root, mergeRecipe(args, root))
    }

    // ── organize_preview（只读预览，免确认） ─────────────────────────────

    fun previewSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "organize_preview",
        description = "预览批量/整理计划，**不碰任何文件**：给 organize 或 bulk_move/bulk_copy 的同一套参数，" +
            "返回一行一条「源 → 目标」的完整计划（op=organize 整理；op=move 批量搬；op=copy 批量复制）。" +
            "只读不写盘，不需要确认。先让它把计划给用户看，用户点头后再调对应的执行工具。" +
            "带 recipe=名字 可以预览一个已保存的整理技能。",
        inputSchema = previewSchema(),
        permission = ToolPermission.FREE,
        timeoutMs = ToolTimeout.COPY_MS,
        readOnly = true,
        parallelSafe = true,
    ) { args ->
        val root = workspaceRoot()
        val op = args.optString(ARG_OP).trim().lowercase().ifEmpty { "organize" }
        val merged = mergeRecipe(args, root)
        when (op) {
            "move" -> previewBulk(root, merged, move = true)
            "copy" -> previewBulk(root, merged, move = false)
            "organize" -> previewOrganize(root, merged)
            else -> ToolOutcome.Failed("op 只认 organize / move / copy（收到「$op」）")
        }
    }

    // ── 参数与配方 ───────────────────────────────────────────────────────

    private fun organizeSchema(): org.json.JSONObject {
        val props = jsonObjectOf(
            ARG_DIR to jsonObjectOf(
                "type" to "string",
                "description" to "要整理的目录：相对工作区根，或全盘绝对路径",
            ),
            ARG_MODE to jsonObjectOf(
                "type" to "string",
                "enum" to jsonArrayOf("type", "date"),
                "description" to "子目录怎么分：type=按类型；date=按修改年月（默认 type）",
            ),
            ARG_DEST to jsonObjectOf(
                "type" to "string",
                "description" to "分类目录建在哪（默认就在 dir 里归）",
            ),
            ARG_RECURSIVE to jsonObjectOf(
                "type" to "boolean",
                "description" to "true＝连子目录里的文件一起收（默认 true）",
            ),
            ARG_UNDO to jsonObjectOf(
                "type" to "string",
                "description" to "要退回的回执 id（在 产物/_整理回执/ 下）。给了它就执行撤销，其他整理参数忽略",
            ),
            ARG_SAVE_RECIPE to jsonObjectOf(
                "type" to "string",
                "description" to "把这次整理的规则存成技能（给个名字，如 每月收料）；下次 recipe=名字 重放",
            ),
            ARG_RECIPE to jsonObjectOf(
                "type" to "string",
                "description" to "用已保存的整理技能（产物/_整理技能/ 下的名字）",
            ),
        )
        for (k in BulkPathTools.ruleProperties().keys()) props.put(k, BulkPathTools.ruleProperties().opt(k))
        return jsonObjectOf(
            "type" to "object",
            "properties" to props,
            "required" to jsonArrayOf(ARG_DIR),
            "additionalProperties" to false,
        )
    }

    private fun previewSchema(): org.json.JSONObject {
        val props = jsonObjectOf(
            ARG_OP to jsonObjectOf(
                "type" to "string",
                "enum" to jsonArrayOf("organize", "move", "copy"),
                "description" to "预览哪种：organize=整理（默认）；move=bulk_move 的计划；copy=bulk_copy 的计划",
            ),
            ARG_DIR to jsonObjectOf("type" to "string", "description" to "范围目录"),
            ARG_DEST to jsonObjectOf(
                "type" to "string",
                "description" to "目标目录（op=move/copy 必给；organize 可省）",
            ),
            ARG_MODE to jsonObjectOf(
                "type" to "string",
                "enum" to jsonArrayOf("type", "date"),
                "description" to "organize 的分法（默认 type）",
            ),
            ARG_RECURSIVE to jsonObjectOf(
                "type" to "boolean",
                "description" to "连子目录一起（organize 默认 true，move/copy 默认 false）",
            ),
            ARG_RECIPE to jsonObjectOf("type" to "string", "description" to "用已保存的整理技能"),
        )
        for (k in BulkPathTools.ruleProperties().keys()) props.put(k, BulkPathTools.ruleProperties().opt(k))
        return jsonObjectOf(
            "type" to "object",
            "properties" to props,
            "required" to jsonArrayOf(ARG_DIR),
            "additionalProperties" to false,
        )
    }

    /** 配方合并：recipe 里存的规则作底，本次显式给的参数优先。 */
    internal fun mergeRecipe(args: JSONObject, root: File): JSONObject {
        val name = args.optString(ARG_RECIPE).trim()
        if (name.isEmpty()) return args
        val f = File(File(root, RECIPES_DIR_REL), "${safeName(name)}.json")
        val stored = runCatching { JSONObject(f.readText(Charsets.UTF_8)) }.getOrNull()
            ?: return args
        val merged = JSONObject()
        for (k in stored.keys()) merged.put(k, stored.opt(k))
        for (k in args.keys()) {
            if (k == ARG_RECIPE) continue
            merged.put(k, args.opt(k))
        }
        return merged
    }

    private fun safeName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5_-]"), "_").trim('_').ifBlank { "unnamed" }

    // ── 范围守卫（预览与执行共用同一份判据） ─────────────────────────────

    private sealed interface ScopeCheck {
        data class Ok(val dir: File, val dest: File) : ScopeCheck

        /** 拒绝/不可用，[reason] 是给模型的人话原文。 */
        data class Bad(val reason: String) : ScopeCheck
    }

    private fun checkScope(root: File, args: JSONObject, requireDest: Boolean): ScopeCheck {
        val dirRaw = args.optString(ARG_DIR).trim()
        if (dirRaw.isBlank()) return ScopeCheck.Bad("缺少必填参数「$ARG_DIR」")
        val destRaw = args.optString(ARG_DEST).trim()
        if (requireDest && destRaw.isEmpty()) {
            return ScopeCheck.Bad("op=move/copy 需要给 $ARG_DEST（目标目录）")
        }
        PersonaGuard.blocked(dirRaw)?.let { return ScopeCheck.Bad(it) }
        PersonaGuard.blocked(destRaw)?.let { return ScopeCheck.Bad(it) }
        val dir = PathCopyCore.resolve(root, dirRaw)
        val dest = if (destRaw.isEmpty()) dir else PathCopyCore.resolve(root, destRaw)
        fileAccessCheck(dir, FilePaths.display(root, dir))?.let { return ScopeCheck.Bad(it) }
        fileAccessCheck(dest, FilePaths.display(root, dest))?.let { return ScopeCheck.Bad(it) }
        if (PathCopyCore.insideBackup(root, dir) || PathCopyCore.insideBackup(root, dest)) {
            return ScopeCheck.Bad("不能把 备份/ 当范围或目标（那是回滚点，模型只读）")
        }
        if (!dir.isDirectory) return ScopeCheck.Bad("范围不是目录（或不存在）：$dirRaw")
        if (dest.absolutePath != dir.absolutePath &&
            dest.absolutePath.startsWith(dir.absolutePath + File.separator)
        ) {
            return ScopeCheck.Bad("目标在范围目录里面：收拢会把自己也搬进去，请换个目标")
        }
        return ScopeCheck.Ok(dir, dest)
    }

    // ── 预览 ─────────────────────────────────────────────────────────────

    private fun previewText(
        root: File,
        plan: PathCopyCore.Plan,
        verb: String,
        rule: PathCopyCore.Rule,
    ): ToolOutcome {
        if (plan.entries.isEmpty()) {
            return ToolOutcome.Ok(
                "预览：没有会$verb 的文件（规则：${rule.describe()}）——执行的话也是什么都不动。" +
                    "先用 find_files 或 list_dir 看清范围里有什么。",
            )
        }
        val shown = plan.entries.take(100)
        val rest = plan.entries.size - shown.size
        val head = buildString {
            append("预览（未动任何文件）：将").append(verb).append(' ')
            append(plan.entries.size).append(" 个文件（")
            append(plan.totalBytes / 1024).append(" KB；规则：").append(rule.describe()).append('）')
            if (plan.skippedSelf > 0) {
                append("，另有 ").append(plan.skippedSelf).append(" 个已在目标位会跳过")
            }
            append("：")
        }
        val body = shown.joinToString("\n") { (s, d) ->
            "- ${PathCopyCore.relOf(root, s)} → ${PathCopyCore.relOf(root, d)}"
        }
        val tail = if (rest > 0) "\n…其余 $rest 条略（执行后回执里有全表）" else ""
        return ToolOutcome.Ok(head + "\n" + body + tail)
    }

    private fun previewOrganize(root: File, args: JSONObject): ToolOutcome {
        val scope = checkScope(root, args, requireDest = false)
        if (scope is ScopeCheck.Bad) return ToolOutcome.Failed(scope.reason)
        val (dir, dest) = (scope as ScopeCheck.Ok)
        val rule = PathCopyCore.Rule.fromArgs(args)
        val plan = PathCopyCore.planOrganize(
            dir, dest, args.optString(ARG_MODE, "type"), rule,
            recursive = args.optBoolean(ARG_RECURSIVE, true),
            nowMs = System.currentTimeMillis(),
        )
        return previewText(root, plan, "归位", rule)
    }

    private fun previewBulk(root: File, args: JSONObject, move: Boolean): ToolOutcome {
        val scope = checkScope(root, args, requireDest = true)
        if (scope is ScopeCheck.Bad) return ToolOutcome.Failed(scope.reason)
        val (dir, dest) = (scope as ScopeCheck.Ok)
        val rule = PathCopyCore.Rule.fromArgs(args)
        val plan = PathCopyCore.planBulk(
            dir, dest, rule,
            recursive = args.optBoolean(BulkPathTools.ARG_RECURSIVE, false),
            nowMs = System.currentTimeMillis(),
        )
        return previewText(root, plan, if (move) "搬动" else "复制", rule)
    }

    // ── 执行 ─────────────────────────────────────────────────────────────

    fun run(root: File, args: JSONObject): ToolOutcome {
        val dirRaw = args.optString(ARG_DIR).trim()
        if (dirRaw.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_DIR」")
        val scope = checkScope(root, args, requireDest = false)
        if (scope is ScopeCheck.Bad) return ToolOutcome.Failed(scope.reason)
        val (dir, dest) = (scope as ScopeCheck.Ok)
        val rule = PathCopyCore.Rule.fromArgs(args)
        val mode = args.optString(ARG_MODE).trim().ifEmpty { "type" }
        if (mode !in setOf("type", "date")) {
            return ToolOutcome.Failed("$ARG_MODE 只认 type / date（收到「$mode」）")
        }
        val plan = PathCopyCore.planOrganize(
            dir, dest, mode, rule,
            recursive = args.optBoolean(ARG_RECURSIVE, true),
            nowMs = System.currentTimeMillis(),
        )
        if (plan.entries.isEmpty()) {
            return ToolOutcome.Ok("没有需要归位的文件（规则：${rule.describe()}）——目录已经是整整齐齐的。")
        }
        val stamp = TimeFormats.fileStamp()
        var receiptId: String? = null
        val report = PathCopyCore.executePlan(plan, move = true, root = root) { src, dst ->
            val id = PathCopyCore.appendReceipt(root, "整理", src, dst, stamp)
            if (receiptId == null) receiptId = id
        }
        runCatching {
            val e = FileOpLog.record(
                tool = "organize",
                kind = FileOpKind.MOVE,
                path = FilePolicy.normalize(dir),
                purpose = "整理 ${report.done} 个（$mode·${rule.describe()}）",
            )
            com.example.zhengdao.core.RunLog.log(FileOpRules.lineOf(e))
        }
        val recipeLine = args.optString(ARG_SAVE_RECIPE).trim()
            .takeIf { it.isNotEmpty() }
            ?.let { saveRecipe(root, it, args) }
            ?: ""
        val undoLine = receiptId
            ?.let { "；要退回就 organize 带 undo_receipt=\"$it\"" }
            ?: "（回执没写上：文件已归位，但这次没有可一键退回的回执，请自己在文件管理器里核对）"
        if (report.failed.isNotEmpty()) {
            return ToolOutcome.Failed(report.line("归位") + undoLine + recipeLine)
        }
        return ToolOutcome.Ok(
            report.line("归位") + "：${PathCopyCore.relOf(root, dir)}" + undoLine + recipeLine +
                "（回执在 产物/_整理回执/，逐条记着哪个搬到了哪）",
        )
    }

    /** 存配方；返回一句话（成功带落点，失败如实说）。 */
    private fun saveRecipe(root: File, rawName: String, args: JSONObject): String {
        val name = safeName(rawName)
        val dir = File(root, RECIPES_DIR_REL)
        val stored = JSONObject()
        for (k in listOf(
            ARG_DIR, ARG_DEST, ARG_MODE, BulkPathTools.ARG_EXT, BulkPathTools.ARG_TYPE,
            BulkPathTools.ARG_OLDER, BulkPathTools.ARG_NEWER, BulkPathTools.ARG_MIN_KB,
            BulkPathTools.ARG_MAX_KB, ARG_RECURSIVE,
        )) {
            if (args.has(k)) stored.put(k, args.opt(k))
        }
        return runCatching {
            dir.mkdirs()
            File(dir, "$name.json").writeText(stored.toString(2), Charsets.UTF_8)
            "；整理技能已存：recipe=\"$name\"（$RECIPES_DIR_REL/$name.json），下次直接重放"
        }.getOrDefault("；整理技能没存上（写盘失败），规则没丢，本次已执行完")
    }

    // ── 撤销 ─────────────────────────────────────────────────────────────

    private fun undo(root: File, receiptRef: String): ToolOutcome {
        val result = PathCopyCore.undoReceipt(root, receiptRef) { _, _ -> }
            ?: return ToolOutcome.Failed(
                "读不到回执：$receiptRef（在 ${PathCopyCore.RECEIPTS_DIR_REL}/ 下找 .md；" +
                    "执行整理/批量时话术里给过回执 id）",
            )
        val (restored, skipped, failed) = result
        runCatching {
            val e = FileOpLog.record(
                tool = "organize",
                kind = FileOpKind.MOVE,
                path = FilePolicy.normalize(File(root, PathCopyCore.RECEIPTS_DIR_REL)),
                purpose = "按回执退回：还原 $restored / 跳过 $skipped / 失败 $failed",
            )
            com.example.zhengdao.core.RunLog.log(FileOpRules.lineOf(e))
        }
        val body = buildString {
            append("已按回执退回：还原 ").append(restored).append(" 个")
            if (skipped > 0) append("；").append(skipped).append(" 个目标已不在原位（你可能自己动过），跳过")
            if (failed > 0) append("；").append(failed).append(" 个搬不动（权限或 IO）")
            append("。重名的没有覆盖——退回时会加后缀让路，两个都在。")
        }
        return if (failed > 0) ToolOutcome.Failed(body) else ToolOutcome.Ok(body)
    }
}
