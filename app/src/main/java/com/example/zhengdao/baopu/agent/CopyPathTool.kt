// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十八批（2026-10-30）：`copy_path`——复制一个**文件或整个目录**。
//
// 为什么需要它：move_path 只能「搬」（源消失），整理前的安全备份、跨目录留底
// 都没有工具可做，只能靠模型逐步硬凑。本件是纯复制：**源永远原地不动**。
//
// 五条硬纪律：
// 1. 边界只有一条（287）：系统与凭据目录一律拒（`fileAccessCheck`）；全盘可复制；
// 2. **流式复制**：64 KB 逐块拷（判据「复制 1GB 不崩」的结构性保证），保留 mtime；
// 3. **绝不覆盖**：撞名不同内容 ⇒ sha 后缀让路；内容相同 ⇒ 幂等回「已复制」；
// 4. 目录递归复制：整棵子树落过去，App 自管目录（备份/回收站/记忆…）不进范围；
// 5. 权限 CONFIRM——每次复制都要用户看一眼 from → to（源只读，卡上写清）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.core.PathGuard
import com.example.zhengdao.baopu.FilePaths
import com.example.zhengdao.baopu.FilePolicy
import com.example.zhengdao.baopu.PersonaGuard
import com.example.zhengdao.baopu.fileAccessCheck
import java.io.File

internal object CopyPathTool {

    const val ARG_FROM = "from"
    const val ARG_TO = "to"
    const val ARG_CREATE_DIRS = "create_dirs"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "copy_path",
        description = "复制一个文件或整个目录（from → to；相对工作区，或全盘绝对路径），源文件原地不动。" +
            "流式复制，1GB 的大文件也不会撑爆内存，且保留原修改时间。" +
            "绝不覆盖：目标已有同名且内容不同 ⇒ 自动加后缀让路；内容相同 ⇒ 视为已复制。" +
            "复制目录时整棵子树落过去（备份/回收站/记忆这些 App 自管目录不进范围）。" +
            "确认由 App 弹确认卡完成，直接调用即可，不要在回复里先问一句。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_FROM to jsonObjectOf(
                    "type" to "string",
                    "description" to "源：文件或目录，相对工作区根（如 投递/某书.txt），或全盘绝对路径",
                ),
                ARG_TO to jsonObjectOf(
                    "type" to "string",
                    "description" to "目标路径（相对工作区根，或全盘绝对路径）；源是目录时 to 就是新目录的位置",
                ),
                ARG_CREATE_DIRS to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "目标目录不存在时是否自动建（默认 true）",
                ),
            ),
            "required" to jsonArrayOf(ARG_FROM, ARG_TO),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.COPY_MS,
    ) { args ->
        run(
            root = workspaceRoot(),
            from = args.optString(ARG_FROM).trim(),
            to = args.optString(ARG_TO).trim(),
            createDirs = args.optBoolean(ARG_CREATE_DIRS, true),
        )
    }

    /** 复制（纯函数：root 已定、无 Android 依赖，JVM 直测）。 */
    fun run(
        root: File,
        from: String,
        to: String,
        createDirs: Boolean = true,
    ): ToolOutcome {
        if (from.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_FROM」")
        if (to.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_TO」")
        val guard = guardCopy(root, from, to)
        if (guard is Guard.Bad) return guard.outcome
        val (src, dst) = (guard as Guard.Ok)
        return if (src.isDirectory) copyDir(root, src, dst, createDirs) else copySingleFile(root, src, dst, from, createDirs)
    }

    private sealed interface Guard {
        data class Ok(val src: File, val dst: File) : Guard

        data class Bad(val outcome: ToolOutcome) : Guard
    }

    /** 前置守卫：人设区/系统凭据区/备份区/同路径/源存在/目标形态——一条不过都别进复制。 */
    private fun guardCopy(root: File, from: String, to: String): Guard {
        // 人设区照 move_path 口径：源/目标任一命中即拒（复制进人设区更是改）
        PersonaGuard.blocked(from)?.let { return Guard.Bad(ToolOutcome.Rejected(it)) }
        PersonaGuard.blocked(to)?.let { return Guard.Bad(ToolOutcome.Rejected(it)) }
        val src = PathCopyCore.resolve(root, from)
        val dst = PathCopyCore.resolve(root, to)
        fileAccessCheck(src, FilePaths.display(root, src))?.let { return Guard.Bad(ToolOutcome.Rejected(it)) }
        fileAccessCheck(dst, FilePaths.display(root, dst))?.let { return Guard.Bad(ToolOutcome.Rejected(it)) }
        // 备份区：源只读本无害，但与 move_path 保持同一句口径（备份区是回滚点，别拿它当料）
        if (PathCopyCore.insideBackup(root, src) || PathCopyCore.insideBackup(root, dst)) {
            return Guard.Bad(ToolOutcome.Failed("不能把 备份/ 当复制源或目标（那是回滚点，模型只读）"))
        }
        if (src.absolutePath == dst.absolutePath) {
            return Guard.Bad(ToolOutcome.Failed("源和目标同一路径：$from"))
        }
        if (!src.exists()) return Guard.Bad(ToolOutcome.Failed("源不存在：$from"))
        if (dst.exists() && dst.isDirectory && !src.isDirectory) {
            return Guard.Bad(
                ToolOutcome.Failed(
                    "目标位置已是个目录：${PathCopyCore.relOf(root, dst)}（把 $ARG_TO 写到具体文件名）",
                ),
            )
        }
        return Guard.Ok(src, dst)
    }

    /** 单文件复制：撞名让路／幂等（与 move_path 同口径，但源永远保留）。 */
    private fun copySingleFile(root: File, src: File, dst: File, from: String, createDirs: Boolean): ToolOutcome {
        val parent = dst.parentFile
        if (parent != null && !parent.exists()) {
            if (!createDirs) {
                return ToolOutcome.Failed("目标目录不存在：${parent.absolutePath}（把 $ARG_CREATE_DIRS 设为 true）")
            }
            if (!parent.mkdirs()) return ToolOutcome.Failed("建不了目标目录：${parent.absolutePath}")
        }
        val sha = PathCopyCore.sha256(src)
            ?: return ToolOutcome.Failed("读不出源文件（IO 错误）：$from")
        var target = dst
        var note = ""
        if (dst.exists()) {
            if (PathCopyCore.sha256(dst) == sha) {
                return ToolOutcome.Ok(
                    "目标已有相同内容（sha 前 8 位 ${sha.take(8)}），视为已复制。源与目标都在，没动任何文件。",
                )
            }
            target = PathCopyCore.bypassTarget(dst, sha)
            note = "；目标已有同名文件，改名让路 ⇒ ${target.name}"
        }
        if (!PathCopyCore.copyFile(src, target)) {
            runCatching { if (target.exists()) target.delete() }
            return ToolOutcome.Failed("复制失败（IO 错误）：$from → ${PathCopyCore.relOf(root, target)}。源文件原样未动。")
        }
        return ToolOutcome.Ok(
            "已复制：$from → ${PathCopyCore.relOf(root, target)}" +
                "（${src.length() / 1024} KB，修改时间已保留）$note。源文件原地未动。",
        )
    }

    /** 目录递归复制：计划与执行走 PathCopyCore 同一条路（与预览/整理一致）。 */
    private fun copyDir(root: File, src: File, dst: File, createDirs: Boolean): ToolOutcome {
        if (dst.exists() && !dst.isDirectory) {
            return ToolOutcome.Failed("目标位置已是个文件：${PathCopyCore.relOf(root, dst)}")
        }
        if (!dst.exists()) {
            if (!createDirs) {
                return ToolOutcome.Failed("目标目录不存在：${PathCopyCore.relOf(root, dst)}")
            }
            if (!runCatching { dst.mkdirs() }.getOrDefault(false)) {
                return ToolOutcome.Failed("建不了目标目录：${dst.absolutePath}")
            }
        }
        val rule = PathCopyCore.Rule() // 目录复制不筛：全部文件
        val plan = PathCopyCore.planBulk(src.absoluteFile, dst.absoluteFile, rule, recursive = true, nowMs = 0L)
        if (plan.entries.isEmpty()) {
            return ToolOutcome.Ok("目录是空的（或只有子目录没有文件）：${PathCopyCore.relOf(root, src)}，已建好目标目录。")
        }
        val report = PathCopyCore.executePlan(plan, move = false, root = root) { _, _ -> }
        return if (report.failed.isEmpty()) {
            ToolOutcome.Ok(
                "已复制整个目录：${PathCopyCore.relOf(root, src)} → ${PathCopyCore.relOf(root, dst)}；" +
                    report.line("复制") + "。源目录原地未动。",
            )
        } else {
            ToolOutcome.Failed("目录复制有失败项（其余已复制成功）：" + report.line("复制"))
        }
    }
}
