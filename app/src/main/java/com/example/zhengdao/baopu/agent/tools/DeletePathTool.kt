// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：用户 2026-10-18 逐字「删除工具没加啊？」（真机上抱朴自己说「我没有删除工具」）。
// 口径（同一晚定下，与资料库那批一脉相承）：
//   - 唯一红线是 `资料库/原始/`（App 私有的原件区）——一个字节都不许碰；
//   - 普通文件/截图/会话/技能，**用户点头就可以删**；
//   - 但「点头」必须是**每次一张确认卡**，且默认可反悔（先进回收站，再谈彻底删）。
//
// 五条硬纪律：
// 1. 越界判据统一走 workspaceBoundaryCheck（156 批起：开关开＋本运行已授权可越界，
//    开关关＝原文拒绝）；禁区（备份/、回收站/、原件区）仍用 PathGuard 硬拒；
// 2. **默认进回收站**（`回收站/<时间戳>-<原名>`，同分区 rename 原子），`permanent=true` 才真删；
// 3. 只删**单个文件**：目录一律拒（删目录一步能毁半个资料库，等真有需求再单开一刀）；
// 4. 禁区一律拒并说清：`备份/`（157 起可见化，原 `.baopu-bak`，旧版本备份）、`回收站/`（回收站本身）、
//    `资料库/原始/`（157 前的旧原件区形态；现已迁 App 私有，此形态只可能是存量残留或用户自建——
//    拦住它靠 GuardPipeline 的原始区只读红线（第四十八批 P0-2）与本工具的字符串保险，两层都在）；
// 5. 权限 CONFIRM——每次删都要用户看一眼「删哪个、进回收站还是真删」。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.MediaDelete
import com.example.zhengdao.baopu.MediaOwnership
import com.example.zhengdao.baopu.MediaPublisher
import com.example.zhengdao.baopu.PersonaGuard
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.workspaceWriteBoundaryCheck
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
    /** 一次清空整个回收站（一个确认卡干完，省得十几个文件点十几次）。 */
    const val ARG_EMPTY_TRASH = "empty_trash"

    /** 回收站目录名（工作区根下**不带点的可见目录**，用户自己看得见、能清）。 */
    const val TRASH_DIR = "回收站"

    /** 旧版本备份区（157 起＝可见的 `备份/`；与 write_file／move_path 同一处，删它等于删回滚点）。 */
    const val BACKUP_DIR_NAME = BaopuKernel.Data.DIR_BACKUP

    /**
     * 第二道保险：**按模型给的相对路径字符串**再判一次原件区。
     *
     * 历史（第四十八批 P0-2）：彼时默认工作区（`Download/证道`）下 `资料库/原始` 就在
     * 工作区**之内**。157 目录收口后原件区已迁 App 私有（工作区外，PathGuard 天然挡），
     * 真正兜底的是 `GuardPipeline` 的原始区红线（按解析后的真实绝对路径判，改写后也骗不过）；
     * 本常量仍认旧形态字符串——迁移残留或用户自建的 `资料库/原始/` 也要拦住，两层都在。
     */
    const val RAW_GUARD_REL = "资料库/原始"

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "delete_path",
        description = "删工作区里的一个文件。**默认进回收站**（工作区/回收站/时间戳-原名），" +
            "说一声就能捞回来；permanent=true 才是彻底删（确认卡上会写明「删了就没了」）。" +
            "只删单个文件：目录、备份区 $BACKUP_DIR_NAME/、回收站本身、资料库/原始/ 一律拒绝并说清原因。" +
            "用户说「清空回收站」时用 empty_trash=true 一次清完（一张确认卡，不用逐个删）。" +
            "相册里本 App 生成的图（Pictures/证道/截屏/、Pictures/证道/图片/）也走这条，path 给相册路径" +
            "（如 Pictures/证道/截屏/证道截屏-20261025-083000.png）；只有**归属清单里登记为本 App 写的**才删，" +
            "别人的照片一律拒绝。" +
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
                ARG_EMPTY_TRASH to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "true = 一次清空整个回收站（不需要 path；只清文件，子目录留着）",
                ),
            ),
            // path 不是必填：empty_trash=true 时不需要（清空整个回收站）
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        val root = workspaceRoot()
        if (args.optBoolean(ARG_EMPTY_TRASH, false)) {
            return@ToolSpec emptyTrash(root)
        }
        run(
            path = args.optString(ARG_PATH).trim(),
            permanent = args.optBoolean(ARG_PERMANENT, false),
            root = root,
            // 199：相册条目经 MediaPublisher（唯一 ContentResolver 出口）
            albumDelete = { relPath -> MediaPublisher.deleteByRelPath(relPath) },
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
        /**
         * 第一百九十九批：相册里的条目（`Pictures/证道/{截屏,图片}/`）交给它删。
         *
         * 为什么单独一个参数：相册行**不在工作区里**，`File` 删不到也删不干净
         * （只删文件会在相册里留幽灵缩略图）；而 `ContentResolver` 只许活在装配层。
         * `null`（JVM 单测默认）⇒ 相册路径一律回「通道没接上」，不假装删成功。
         */
        albumDelete: ((relPath: String) -> MediaDelete)? = null,
    ): ToolOutcome {
        val rel = path.trim()
        if (rel.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        // 第一百九十九批：相册条目先分流——它是「只删自己写的」那条线，判据在
        // MediaOwnership.deleteVerdict（账上没这条/不是本包 ⇒ 一律拒绝，宁可多问）
        val albumRel = MediaOwnership.albumRelPathOf(rel)?.takeIf { MediaOwnership.isAlbumPath(it) }
        if (albumRel != null) {
            val del = albumDelete
                ?: return ToolOutcome.Failed("相册删除通道没接上（App 内部装配缺失）：$albumRel")
            return when (val r = del(albumRel)) {
                is MediaDelete.Done ->
                    ToolOutcome.Ok("已删掉相册里的 ${r.entry.relPath}（相册里同步消失，不留缩略图）")

                is MediaDelete.Ask -> ToolOutcome.Rejected(
                    "没有删：${r.reason}。相册里不是本 App 生成的照片，请自己到系统相册里处理。",
                )

                is MediaDelete.Failed -> ToolOutcome.Failed("没删掉：${r.reason}")
            }
        }
        // 第三十七批 A 段：删掉人设＝让人设静默消失，同样不许（回收站那套逻辑原样保留）
        PersonaGuard.blocked(rel)?.let { return ToolOutcome.Rejected(it) }
        val target = resolve(root, rel)
        // 第二百二十九批：写侧硬边界——删只允许当前工作区＋默认工作区（Download/证道），
        // 其余一律拒绝（不是弹卡）。回收站/备份区/原件区的既有禁区一字不动（下面各条照旧）。
        workspaceWriteBoundaryCheck(
            root,
            target,
            rel,
            "路径越界被拒：$rel 在可写范围（当前工作区与 Download/证道）之外。" +
                "delete_path 只能在资料库/工作区里删。",
        )?.let { return ToolOutcome.Rejected(it) }
        if (target.absolutePath == root.absolutePath) {
            return ToolOutcome.Failed("不许删工作区根目录本身：${root.absolutePath}")
        }
        val trashDir = File(root, TRASH_DIR)
        // 回收站里的东西**直接彻底清**（用户 2026-10-18 两次真机反馈：让抱朴清回收站总被拦），
        // 不再要求 permanent —— 对回收站再做「移进回收站」是原地打转，用户的本意就是清掉；
        // 确认卡照样弹，卡上写的就是「从回收站彻底删除」，反悔窗口在回收站里而不是再套一层。
        val insideTrash = PathGuard.isInside(trashDir, target)
        if (PathGuard.isInside(File(root, BACKUP_DIR_NAME), target)) {
            return ToolOutcome.Failed("不许删 $BACKUP_DIR_NAME/ 里的东西——那是旧版本备份区（回滚点）。")
        }
        // 第二道保险（字符串层）：管线已按真实绝对路径判过原始区红线（P0-2）；
        // 这里再挡一次「模型明确写了 资料库/原始/…」的显式形态（直调工具时也生效）。
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
        if (permanent || insideTrash) {
            val deleted = runCatching { target.delete() }.getOrDefault(false)
            if (!deleted) {
                return ToolOutcome.Failed("删不掉：$rel（权限或 IO 错误）。文件还在。")
            }
            return ToolOutcome.Ok(
                if (insideTrash) "已从回收站彻底删除：$rel（$size B）。这一步不可恢复。"
                else "已彻底删除：$rel（$size B）。这一步不可恢复。",
            )
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

    /**
     * 清空回收站（**一张确认卡干完**）。
     *
     * 为什么单开这一条：2026-10-19 用户真机「清空回收站」，里面 14 个文件 ⇒ 逐个删要 14 步、
     * 14 次确认卡，撞上步数上限还停在一半。批量清理是这件活的本意，不该按单个文件计价。
     *
     * 边界：只删 `回收站/` 下的**文件**（子目录留着，逐层删才有反悔余地）；
     * 一个也删不掉就如实报哪几个删不动，不假装清空。
     */
    fun emptyTrash(root: File): ToolOutcome {
        val trashDir = File(root, TRASH_DIR)
        if (!trashDir.isDirectory) return ToolOutcome.Ok("回收站是空的（还没有东西进来），不用清。")
        val files = trashDir.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) return ToolOutcome.Ok("回收站里没有文件（只有子目录或本来就是空的）。")
        var deleted = 0
        var bytes = 0L
        val failed = mutableListOf<String>()
        for (f in files) {
            val len = f.length()
            if (runCatching { f.delete() }.getOrDefault(false)) {
                deleted++
                bytes += len
            } else {
                failed += f.name
            }
        }
        val head = "已清空回收站：删掉 $deleted 个文件（${bytes / 1024} KB）。这一步不可恢复。"
        return if (failed.isEmpty()) {
            ToolOutcome.Ok(head)
        } else {
            ToolOutcome.Ok(head + " 另有 ${failed.size} 个删不动：" + failed.take(3).joinToString("、"))
        }
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
