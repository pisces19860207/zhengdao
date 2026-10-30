// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十八批（2026-10-30）：`bulk_move` / `bulk_copy`——按规则批量选文件再搬/复制。
//
// 为什么是两件而规则参数完全一样：move 与 copy 的安全语义不同（源会不会消失），
// 确认卡与风险档也不同（搬走＝改两处 R2；复制＝只写新文件 R1），合一件就得靠参数
// 区分语义——模型看错一个布尔就是丢文件。分开注册，卡面上写得明明白白。
//
// 规则（条件 AND 叠加）：ext 扩展名数组／type 类型（image/video/audio/doc/archive）/
// older_than_days・newer_than_days（按修改时间）／min_kb・max_kb（按大小）。
// 全空 ＝ 全选（确认卡上会写「全部文件」，用户看得见范围）。
//
// 预览：用 organize_preview（FREE 只读，op=move/copy）拿同一份计划——
// 判据「预览与实际一致」由共享计划函数保证（PathCopyCore.planBulk）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.core.TimeFormats
import com.example.zhengdao.baopu.FileOpKind
import com.example.zhengdao.baopu.FileOpLog
import com.example.zhengdao.baopu.FileOpRules
import com.example.zhengdao.baopu.FilePaths
import com.example.zhengdao.baopu.FilePolicy
import com.example.zhengdao.baopu.PersonaGuard
import com.example.zhengdao.baopu.fileAccessCheck
import java.io.File

internal object BulkPathTools {

    const val ARG_DIR = "dir"
    const val ARG_DEST = "dest"
    const val ARG_EXT = "ext"
    const val ARG_TYPE = "type"
    const val ARG_OLDER = "older_than_days"
    const val ARG_NEWER = "newer_than_days"
    const val ARG_MIN_KB = "min_kb"
    const val ARG_MAX_KB = "max_kb"
    const val ARG_RECURSIVE = "recursive"

    /** 两件共用的规则参数（bulk 与 organize_preview 同款，改要一起改）。 */
    internal fun ruleProperties(): org.json.JSONObject = jsonObjectOf(
        ARG_EXT to jsonObjectOf(
            "type" to "array",
            "items" to jsonObjectOf("type" to "string"),
            "description" to "按扩展名选（如 [\"jpg\",\"png\"]；带不带点都行）",
        ),
        ARG_TYPE to jsonObjectOf(
            "type" to "string",
            "enum" to jsonArrayOf("image", "video", "audio", "doc", "archive"),
            "description" to "按类型选：图片/视频/音频/文档/压缩包",
        ),
        ARG_OLDER to jsonObjectOf(
            "type" to "integer",
            "description" to "只要修改时间早于 N 天前的",
        ),
        ARG_NEWER to jsonObjectOf(
            "type" to "integer",
            "description" to "只要修改时间在近 N 天内的",
        ),
        ARG_MIN_KB to jsonObjectOf("type" to "integer", "description" to "只要大于 N KB 的"),
        ARG_MAX_KB to jsonObjectOf("type" to "integer", "description" to "只要小于 N KB 的"),
    )

    fun moveSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "bulk_move",
        description = "按规则批量搬动文件到目标目录（dir 范围内命中规则的 → dest）。" +
            "规则可叠加：扩展名/类型/日期（早于·近 N 天）/大小（KB）。" +
            "同分区改名（原子）；跨分区复制后源保留并记进 产物/_待删除清单.md——绝不自动删。" +
            "绝不覆盖：撞名不同内容自动加后缀让路，内容相同视为已搬。" +
            "先预览再动手：调 organize_preview（op=move）把计划给用户看，确认后再执行本件。" +
            "确认由 App 弹确认卡完成。",
        inputSchema = bulkSchema(),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.COPY_MS,
    ) { args ->
        run(workspaceRoot(), args, move = true)
    }

    fun copySpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "bulk_copy",
        description = "按规则批量复制文件到目标目录（dir 范围内命中规则的 → dest），源文件全部原地不动。" +
            "规则可叠加：扩展名/类型/日期/大小（与 bulk_move 同款）。" +
            "绝不覆盖：撞名不同内容自动加后缀让路，内容相同视为已复制；保留原修改时间。" +
            "先预览再动手：调 organize_preview（op=copy）把计划给用户看，确认后再执行本件。" +
            "确认由 App 弹确认卡完成。",
        inputSchema = bulkSchema(),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.COPY_MS,
    ) { args ->
        run(workspaceRoot(), args, move = false)
    }

    private fun bulkSchema(): org.json.JSONObject {
        val props = jsonObjectOf(
            ARG_DIR to jsonObjectOf(
                "type" to "string",
                "description" to "源范围目录：相对工作区根，或全盘绝对路径",
            ),
            ARG_DEST to jsonObjectOf(
                "type" to "string",
                "description" to "目标目录（不存在会自动建）",
            ),
            ARG_RECURSIVE to jsonObjectOf(
                "type" to "boolean",
                "description" to "true＝连子目录一起（保留相对层级）；默认 false 只看顶层",
            ),
        )
        for (k in ruleProperties().keys()) props.put(k, ruleProperties().opt(k))
        return jsonObjectOf(
            "type" to "object",
            "properties" to props,
            "required" to jsonArrayOf(ARG_DIR, ARG_DEST),
            "additionalProperties" to false,
        )
    }

    /** 执行体（两件共用；move 决定源要不要消失）。 */
    fun run(root: File, args: org.json.JSONObject, move: Boolean): ToolOutcome {
        val dirRaw = args.optString(ARG_DIR).trim()
        val destRaw = args.optString(ARG_DEST).trim()
        if (dirRaw.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_DIR」")
        if (destRaw.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_DEST」")
        PersonaGuard.blocked(dirRaw)?.let { return ToolOutcome.Rejected(it) }
        PersonaGuard.blocked(destRaw)?.let { return ToolOutcome.Rejected(it) }
        val dir = PathCopyCore.resolve(root, dirRaw)
        val dest = PathCopyCore.resolve(root, destRaw)
        fileAccessCheck(dir, FilePaths.display(root, dir))?.let { return ToolOutcome.Rejected(it) }
        fileAccessCheck(dest, FilePaths.display(root, dest))?.let { return ToolOutcome.Rejected(it) }
        if (PathCopyCore.insideBackup(root, dir) || PathCopyCore.insideBackup(root, dest)) {
            return ToolOutcome.Failed("不能把 备份/ 当批量范围或目标（那是回滚点）")
        }
        if (dest.absolutePath == dir.absolutePath) {
            return ToolOutcome.Failed("范围和目标是同一个目录：$dirRaw（搬了等于没搬）")
        }
        if (dest.absolutePath.startsWith(dir.absolutePath + File.separator) ||
            dest.absolutePath == dir.absolutePath + File.separator
        ) {
            return ToolOutcome.Failed(
                "目标在范围目录里面（$dirRaw 的子目录）：逐层收拢会把自己也搬进去，请换个目标。",
            )
        }
        if (!dir.isDirectory) return ToolOutcome.Failed("范围不是目录（或不存在）：$dirRaw")
        val rule = PathCopyCore.Rule.fromArgs(args)
        val plan = PathCopyCore.planBulk(
            dir, dest, rule,
            recursive = args.optBoolean(ARG_RECURSIVE, false),
            nowMs = System.currentTimeMillis(),
        )
        val verb = if (move) "搬" else "复制"
        if (plan.entries.isEmpty()) {
            return ToolOutcome.Ok(
                "没有命中规则的文件（规则：${rule.describe()}）——没动任何文件。" +
                    "先用 find_files 或 list_dir 看看清有什么。",
            )
        }
        // 预览式回执头：执行前先在话术里报数（卡上已经批过，这里对账）
        val stamp = TimeFormats.fileStamp()
        val kind = if (move) "批量搬动" else "批量复制"
        var receiptId: String? = null
        val report = PathCopyCore.executePlan(plan, move = move, root = root) { src, dst ->
            val id = PathCopyCore.appendReceipt(root, kind, src, dst, stamp)
            if (receiptId == null) receiptId = id
        }
        // 287 入账：管线按 pathKeys 只记 dir/dest 两行；范围里真正动了多少在这里补一句人话
        runCatching {
            val e = FileOpLog.record(
                tool = if (move) "bulk_move" else "bulk_copy",
                kind = if (move) FileOpKind.MOVE else FileOpKind.COPY,
                path = FilePolicy.normalize(dir),
                purpose = "批量$verb ${report.done} 个（${rule.describe()}）",
            )
            com.example.zhengdao.core.RunLog.log(FileOpRules.lineOf(e))
        }
        val tail = receiptId?.let { "；回执 $it 在 产物/_整理回执/，要退回就说一声" } ?: ""
        if (report.failed.isNotEmpty()) {
            return ToolOutcome.Failed(report.line(verb) + tail)
        }
        return ToolOutcome.Ok(
            report.line(verb) + "：$dirRaw → $destRaw$tail" +
                if (plan.skippedSelf > 0) "（另有 ${plan.skippedSelf} 个已在目标位，跳过）" else "",
        )
    }
}
