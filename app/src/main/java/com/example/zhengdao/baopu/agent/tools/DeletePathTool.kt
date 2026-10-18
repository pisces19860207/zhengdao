// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：用户 2026-10-18 逐字「删除工具没加啊？」（真机上抱朴自己说「我没有删除工具」）。
// 口径（同一晚定下，与资料库那批一脉相承）：
//   - 唯一红线是 `资料库/原始/`（App 私有的原件区）——一个字节都不许碰；
//   - 普通文件/截图/会话/技能，**用户点头就可以删**；
//   - 但「点头」必须是**每次一张确认卡**，且默认可反悔（先进回收站，再谈彻底删）。
//
// 五条硬纪律：
// 1. 只在工作区根内删，越界即拒（PathGuard.isInside，组件级比较）；
// 2. **默认进回收站**（`.回收站/<时间戳>-<原名>`，同分区 rename 原子），`permanent=true` 才真删；
// 3. 只删**单个文件**：目录一律拒（删目录一步能毁半个资料库，等真有需求再单开一刀）；
// 4. 禁区一律拒并说清：`.baopu-bak/`（旧版本备份）、`.回收站/`（回收站本身）、
//    `资料库/原始/`（App 私有原件区，结构上本来就在工作区外，这里是第二道保险）；
// 5. 权限 CONFIRM——每次删都要用户看一眼「删哪个、进回收站还是真删」。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.rootfs.PathGuard
import com.example.zhengdao.util.TimeFormats
import java.io.File

/**
 * `delete_path`：删工作区里的**一个文件**——默认进回收站，`permanent=true` 才彻底删。
 *
 * 为什么默认回收站：删错了还能捞回来，而真删是不可逆的。用户要清截图、清废稿是常态，
 * 每次都得先想清楚「这一步之后真的没了」并不现实；先扔回收站，回收站满了自己清，
 * 比"让模型保证不删错"可靠得多。
 *
 * 为什么只删文件不删目录：目录删除是递归的，一次误判能带走整批资料；真需要批量清理时
 * 让模型逐个文件来，用户每个都能在确认卡上看见。
 */
internal object DeletePathTool {

    const val ARG_PATH = "path"
    const val ARG_PERMANENT = "permanent"

    /** 回收站目录名（工作区根下**不带点的可见目录**，用户自己看得见、能清）。 */
    const val TRASH_DIR = "回收站"

    /** 旧版本备份区（与 write_file／move_path 同一处，删它等于删回滚点）。 */
    const val BACKUP_DIR_NAME = ".baopu-bak"

    /** 第二道保险：App 私有原件区在工作区外，但库里/历史里出现过这个相对路径就拒。 */
    const val RAW_GUARD_REL = "资料库/原始"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "delete_path",
        description = "删工作区里的一个文件。**默认进回收站**（工作区/.回收站/时间戳-原名），" +
            "说一声就能捞回来；permanent=true 才是彻底删（确认卡上会写明「删了就没了」）。" +
            "只删单个文件：目录、备份区 .baopu-bak/、回收站本身、资料库/原始/ 一律拒绝并说清原因。" +
            "确认由 App 弹确认卡完成，直接调用即可，不要在回复里先问一句。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "要删的文件（相对工作区根，如 附件/20261018-231057-shot.jpg）",
                ),
                ARG_PERMANENT to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "true = 彻底删（不进回收站，不可恢复）；默认 false = 进回收站",
                ),
            ),
            "required" to jsonArrayOf(ARG_PATH),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        run(
            path = args.optString(ARG_PATH).trim(),
            permanent = args.optBoolean(ARG_PERMANENT, false),
            root = workspaceRoot(),
        )
    }

    /**
     * 删文件（**纯函数**：root 已定、无 Android 依赖，JVM 直测）。
     *
     * @param root 工作区根（越界判定基准）
     * @param path 目标（相对或绝对；绝对也必须落在 root 内）
     * @param permanent true = 真删；false = 进回收站
     * @param stamp 回收站文件名的前缀（注入以便 JVM 直测确定性）
     */
    fun run(
        path: String,
        permanent: Boolean = false,
        root: File,
        stamp: String = TimeFormats.fileStamp(),
    ): ToolOutcome {
        val rel = path.trim()
        if (rel.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        val target = resolve(root, rel)
        if (!PathGuard.isInside(root, target)) {
            return ToolOutcome.Rejected(
                "路径越界被拒：$rel 不在工作区（${root.absolutePath}）内。delete_path 只能在资料库/工作区里删。",
            )
        }
        if (target.absolutePath == root.absolutePath) {
            return ToolOutcome.Failed("不许删工作区根目录本身：${root.absolutePath}")
        }
        val trashDir = File(root, TRASH_DIR)
        // 回收站里的东西**可以清**（用户 2026-10-18 真机反馈：让抱朴清回收站被守卫拦下），
        // 但只能 permanent —— 对回收站再做「移进回收站」没有任何意义，那是原地打转。
        if (PathGuard.isInside(trashDir, target) && !permanent) {
            return ToolOutcome.Failed(
                "$rel 已经在 $TRASH_DIR/ 里了。要清空请带 permanent=true（这一步不可恢复）；" +
                    "不带的默认行为是进回收站，对回收站里的东西等于原地打转。",
            )
        }
        if (PathGuard.isInside(File(root, BACKUP_DIR_NAME), target)) {
            return ToolOutcome.Failed("不许删 $BACKUP_DIR_NAME/ 里的东西——那是旧版本备份区（回滚点）。")
        }
        // 第二道保险：私有原件区在工作区外，但「相对路径长得像它」也一律拒
        val norm = rel.replace('\\', '/').removePrefix("./")
        if (norm == RAW_GUARD_REL || norm.startsWith("$RAW_GUARD_REL/")) {
            return ToolOutcome.Rejected(
                "路径被拒：$RAW_GUARD_REL/ 是原件区，一个字节都不许碰（用户红线）。",
            )
        }
        if (!target.exists()) {
            return ToolOutcome.Failed("文件不存在：$rel（先用 list_dir 看清有什么）")
        }
        if (target.isDirectory) {
            return ToolOutcome.Failed(
                "这是个目录：${target.name}。delete_path 只删单个文件——目录请逐个文件删，" +
                    "批量清理走资料库整理（library_organize）。",
            )
        }
        val size = target.length()
        if (permanent) {
            val deleted = runCatching { target.delete() }.getOrDefault(false)
            if (!deleted) {
                return ToolOutcome.Failed("删不掉：$rel（权限或 IO 错误）。文件还在。")
            }
            return ToolOutcome.Ok("已彻底删除：$rel（$size B）。这一步不可恢复。")
        }
        // 默认：进回收站（同分区 rename 原子；失败退回复制+删除，两步都失败就如实说）
        val trashName = uniqueTrashName(trashDir, "$stamp-${target.name}")
        val dest = File(trashDir, trashName)
        if (!trashDir.exists() && !trashDir.mkdirs()) {
            return ToolOutcome.Failed("建不了回收站目录：${trashDir.absolutePath}（权限或 IO 错误）。文件没动。")
        }
        val renamed = runCatching { target.renameTo(dest) }.getOrDefault(false)
        if (!renamed) {
            val copied = runCatching { target.copyTo(dest, overwrite = false) }.isSuccess
            if (!copied) {
                return ToolOutcome.Failed("移进回收站失败：$rel（权限或 IO 错误）。文件原样未动。")
            }
            if (!runCatching { target.delete() }.getOrDefault(false)) {
                return ToolOutcome.Failed(
                    "已复制到 $TRASH_DIR/$trashName，但原件删不掉（权限？）——现在两处都有，请自己删原件。",
                )
            }
        }
        return ToolOutcome.Ok(
            "已移进回收站：$rel → $TRASH_DIR/$trashName（$size B）。" +
                "要恢复就用 move_path 把它搬回原位；想彻底清就自己删 $TRASH_DIR/。",
        )
    }

    /** 回收站里撞名就让路：`x-2`、`x-3`……（同秒删两个同名文件时不覆盖前一个）。 */
    private fun uniqueTrashName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        var n = 2
        while (File(dir, "$name-$n").exists()) n++
        return "$name-$n"
    }

    private fun resolve(root: File, path: String): File =
        if (File(path).isAbsolute) File(path) else File(root, path)
}
