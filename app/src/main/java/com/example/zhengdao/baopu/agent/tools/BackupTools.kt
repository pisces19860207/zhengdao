// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百九十一批（设计修订，用户 2026-11-06）一①「工具入口：import_data(path) /
// export_data()（模型可主动调）」＋ 四「三条硬护栏」。
//
// 三条护栏在这一层怎么落：
// ① 校验先行：`BackupImporter.open` 先解临时目录逐条核对，不过 ⇒ [ToolOutcome.Rejected]，一个文件都不写；
// ② 默认不覆盖：`overwrite` 只在用户点名要覆盖时才由模型填；不填 ⇒ 冲突一律列在回执里、不落盘；
// ③ 原子：落盘/回滚在 `BackupImporter`（暂存→换名→失败逆序退回），本文件不自己写文件。
//
// Context 纪律：工具层不认识 Context（房型同 [IndexTools]）——运行环境由 BaopuRuntime 注入一次。
package com.example.zhengdao.baopu.agent

import android.content.Context
import com.example.zhengdao.backup.BackupInbox
import com.example.zhengdao.backup.BackupShell
import com.example.zhengdao.backup.ImportRun
import com.example.zhengdao.backup.OpenResult
import java.io.File

/**
 * `export_data` / `import_data`：一键备份与还原的**模型入口**。
 *
 * 为什么要有工具入口（而不是只有设置页那个按钮）：用户的真实动作是"把 zip 丢进手机里，
 * 然后说一句话"。设置页按钮只是第三入口；主路径是模型自己找到包、自己导。
 */
internal object BackupTools {

    const val NAME_EXPORT = "export_data"
    const val NAME_IMPORT = "import_data"

    const val ARG_PATH = "path"
    const val ARG_INCLUDE_CHAT = "include_chat"
    const val ARG_OVERWRITE = "overwrite"
    const val ARG_APPLY_SETTINGS = "apply_settings"

    /** 冲突清单在回执里最多列几条（全量在日志与清单里；回执不烧上下文）。 */
    const val CONFLICT_LINES = 20

    /** 实际写入的条目最多列几条。 */
    const val WRITTEN_LINES = 10

    /** Context 注入口（BaopuRuntime 唯一装配点；JVM 单测走纯函数与注入的假件）。 */
    @Volatile
    var ctx: (() -> Context)? = null

    // ── export_data ─────────────────────────────────────────────────────────

    fun exportSpec(): ToolSpec = ToolSpec(
        name = NAME_EXPORT,
        description = "把账本、人设与记忆、技能、设置打成一个备份 zip，放到 Download/证道/backups/，" +
            "附清单（版本/时间/条目数/每文件 sha256）。默认不含 API Key 与聊天原文；" +
            "include_chat=true 才带聊天原文（只导出，不还原）。用户说「备份一下」「导出一份」时用。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_INCLUDE_CHAT to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "要不要把聊天原文也打进去（默认 false；体积大且只导出不还原）",
                ),
            ),
            // 全可选：写空数组并在 ToolSchemaRules.REQUIRED_EMPTY_OK 登记原因（S4/S5 的规矩）
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        readOnly = false,
        timeoutMs = ToolTimeout.ARCHIVE_MS,
        group = ToolGroup.FILES,
        riskTier = "R1",
    ) { args -> runExport(args.optBoolean(ARG_INCLUDE_CHAT, false)) }

    fun runExport(includeChat: Boolean): ToolOutcome {
        val c = ctx?.invoke() ?: return ToolOutcome.Failed(NOT_WIRED)
        val r = BackupShell.export(c, includeChat)
        if (!r.ok) return ToolOutcome.Failed("导出失败：${r.reason.orEmpty()}")
        val extra = if (r.skipped > 0) "；另有 ${r.skipped} 项没进包（原因见清单里的 skipped）" else ""
        return ToolOutcome.Ok(
            "已导出到 ${r.file?.absolutePath.orEmpty()}：${r.entryCount} 项、${r.bytes} 字节$extra。" +
                "包里没有 API Key" + (if (includeChat) "；含聊天原文（只导出、不还原）" else "，也没有聊天原文") + "。",
        )
    }

    // ── import_data ─────────────────────────────────────────────────────────

    fun importSpec(): ToolSpec = ToolSpec(
        name = NAME_IMPORT,
        description = "从备份 zip 还原。**默认不覆盖**：本机已有且内容不同的条目会列出来、一个都不改。" +
            "path 省略 ⇒ 用「Download/证道/导入/」里最近的那个包（启动时就校验好了）。" +
            "用户说「把刚下载那个 zip 导进来」时，先用 find_files（order=recent）定位再把路径传进来。" +
            "只有用户点名要覆盖那几条时才填 overwrite（条目路径，形如 memory/global/决策记录.jsonl）。" +
            "失败整体回滚，不留半状态。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "备份 zip 的路径：绝对路径（/storage/emulated/0/Download/…）或相对工作区；省略＝用导入目录里最近的那个",
                ),
                ARG_OVERWRITE to jsonObjectOf(
                    "type" to "array",
                    "description" to "用户**明确点名**要覆盖的条目路径（默认空＝一条都不覆盖）",
                    "items" to jsonObjectOf("type" to "string"),
                ),
                ARG_APPLY_SETTINGS to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "要不要把设置也写回去（默认 false：只还原文件。用户说「连设置一起」时才给 true）",
                ),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        readOnly = false,
        timeoutMs = ToolTimeout.ARCHIVE_MS,
        group = ToolGroup.FILES,
        riskTier = "R2",
    ) { args ->
        runImport(
            pathRaw = args.optString(ARG_PATH),
            overwrite = stringList(args.optJSONArray(ARG_OVERWRITE)),
            applySettings = args.optBoolean(ARG_APPLY_SETTINGS, false),
        )
    }

    private fun stringList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
    }

    /**
     * 还原入口。
     *
     * @param pathRaw 空 ⇒ 走收件箱（默认导入目录里最近那个）
     */
    fun runImport(pathRaw: String, overwrite: List<String>, applySettings: Boolean): ToolOutcome {
        val c = ctx?.invoke() ?: return ToolOutcome.Failed(NOT_WIRED)
        val zip = resolveZip(c, pathRaw) ?: return ToolOutcome.Failed(noCandidateText(pathRaw))
        val opened = BackupShell.open(c, zip)
        if (opened is OpenResult.Rejected) return ToolOutcome.Rejected("这个包不能用：${opened.reason}")
        val preview = (opened as OpenResult.Ready).preview
        val run = BackupShell.importNow(c, preview, overwrite.toSet(), applySettings)
        if (!run.ok) {
            return ToolOutcome.Failed("导入失败：${run.reason.orEmpty()}" + if (run.rolledBack) "（已回滚，本机文件与导入前一样）" else "（回滚未完成，见日志）")
        }
        return ToolOutcome.Ok(renderImportReport(run))
    }

    /** 定包：给的路径优先；没给就先看收件箱，再扫一遍默认目录。 */
    private fun resolveZip(c: Context, pathRaw: String): File? {
        val inbox = BackupInbox.peek()?.takeIf { it.ok }?.file
        val fresh by lazy { BackupInbox.refresh(c)?.takeIf { it.ok }?.file }
        return chooseSource(pathRaw, inbox) ?: fresh
    }

    private fun noCandidateText(pathRaw: String): String = if (pathRaw.isBlank()) {
        "没找到可导入的包：默认导入目录「Download/证道/导入/」里没有合格 zip（或校验没过，见「最近问题」）。" +
            "如果 zip 在别处，把路径直接给我；不确定在哪就用 find_files（order=recent，找 \".zip\"）定位后把路径传进来。"
    } else {
        "找不到这个文件：$pathRaw（zip 可能在下载目录或别的 App 的私有目录里）"
    }

    private const val NOT_WIRED = "备份工具还没装配运行环境（内部错误）"
}

/**
 * 「导哪个包」的判据（纯函数，JVM 直测）。
 *
 * 顺序写死：**用户/模型点名的路径 > 收件箱里最近那个 > （调用方再去扫一遍默认目录）**。
 * 为什么不是反过来：点了名还去扫目录，等于把用户的话当参考意见。
 */
internal fun chooseSource(pathRaw: String, inbox: File?): File? {
    val p = pathRaw.trim()
    if (p.isNotEmpty()) return File(p)
    return inbox
}

/**
 * 导入回执的**纯渲染**（JVM 直测：给一组读数就该出这段字）。
 *
 * 三条硬要求在这里体现：冲突一律列（不是"已自动跳过"这种模糊话）、
 * 设置没动要说清、热加载结果要说清。
 */
internal fun renderImportReport(run: ImportRun, conflictLimit: Int = BackupTools.CONFLICT_LINES): String {
    val sb = StringBuilder()
    sb.append("导入完成：写入 ").append(run.applied).append(" 项")
    if (run.unchanged > 0) sb.append("；本机已是最新 ").append(run.unchanged).append(" 项")
    if (run.conflicts.isNotEmpty()) sb.append("；**冲突未覆盖 ").append(run.conflicts.size).append(" 项**")
    sb.append("（来源：").append(run.source).append("）\n")
    if (run.written.isNotEmpty()) {
        sb.append("写入：").append(run.written.take(BackupTools.WRITTEN_LINES).joinToString("、"))
        if (run.written.size > BackupTools.WRITTEN_LINES) sb.append(" 等 ").append(run.written.size).append(" 项")
        sb.append('\n')
    }
    if (run.conflicts.isNotEmpty()) {
        sb.append("冲突（本机已有且内容不同，**一个都没改**）：\n")
        run.conflicts.take(conflictLimit).forEach { sb.append(" - ").append(it).append('\n') }
        if (run.conflicts.size > conflictLimit) sb.append(" - …另有 ").append(run.conflicts.size - conflictLimit).append(" 项（见日志）\n")
        sb.append("要覆盖哪几条，请用户明确说出来（或在设置页「备份与还原」里逐条勾）。\n")
    }
    if (run.settingsInPackage && run.settingsApplied == 0) {
        sb.append("设置：包里有，但这次没动（默认不覆盖设置；要还原就说「连设置一起导」）。\n")
    } else if (run.settingsApplied > 0) {
        sb.append("设置：已写回 ").append(run.settingsApplied).append(" 项。\n")
    }
    run.reload?.let { sb.append(it.line()).append('\n') }
    return sb.toString().trimEnd()
}
