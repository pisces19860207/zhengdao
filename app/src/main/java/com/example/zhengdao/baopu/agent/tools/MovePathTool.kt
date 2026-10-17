// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴线「工具补强」（2026-10-17，用户口径「那对话的时候也可以让抱朴自己搬吧？」）。
// 为什么需要它：`write_file` 能写新文件，却挪不动旧文件——收料后半程
//（把 投递/ 里看过的书归到 整理/书目/、把书改名、建目录）只能靠人手动点文件管理器。
//
// 五条硬纪律（逐条对应口径）：
// 1. 只在工作区根内搬，越界即拒（PathGuard.isInside 双保险，纯函数层可测）；
// 2. 同分区优先 rename（原子，不留半截）；跨分区只能复制 ⇒ **不自动删源**，
//    把源文件记进 `资料库/整理/_待删除清单.md`，由用户自己确认后删（与刀 2 的「待删除清单」同一口径）；
// 3. **绝不覆盖**：撞名不同内容 ⇒ 加 sha 后缀让路；内容相同（sha 一致）⇒ 幂等回「已搬」；
// 4. 只搬**文件**，不搬目录（目录搬动一步能毁掉半个资料库，等有需求再单开一刀）；
// 5. 权限 CONFIRM——每次搬都要用户看一眼 from → to。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.rootfs.PathGuard
import com.example.zhengdao.util.TimeFormats
import java.io.File
import java.security.MessageDigest

/**
 * `move_path`：在工作区内搬动/改名**一个文件**。
 *
 * 为什么 rename 优先：同分区 rename 是原子的——要么搬完，要么原样；复制＋删除中间失败会
 * 留下「两边都有」或「两边都没有」，后者是资料丢失。
 *
 * 为什么跨分区不删源：删除是用户明确不让自动化的动作（刀 2 铁律）。复制成功后把源路径写进
 * `资料库/整理/_待删除清单.md`，用户自己在文件管理器里点删除——这一条与刀 2 的「搬 = 复制进
 * 私有 原始/ ＋ 对投递原件出待删除清单」完全同源。
 *
 * 为什么撞名加 sha 而不是覆盖：覆盖一个已有文件是不可逆的（旧内容没有备份点），
 * 而「同名不同内容」往往是两本不同的书。加后缀让路，两份都在，用户自己挑。
 */
internal object MovePathTool {

    const val ARG_FROM = "from"
    const val ARG_TO = "to"
    const val ARG_CREATE_DIRS = "create_dirs"

    /** 待删除清单（工作区相对路径；用户可见，也与刀 2 的出清单口径一致）。 */
    const val PENDING_LIST_REL = "资料库/整理/_待删除清单.md"

    /** 备份/禁区目录名（与 write_file、edit_file 同一处；搬东西不许把备份区当源或目标）。 */
    const val BACKUP_DIR_NAME = ".baopu-bak"

    /** sha 后缀取前 8 位（可读性与唯一性够用）。 */
    private const val SHA_SUFFIX_LEN = 8

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "move_path",
        description = "在工作区内搬动或改名一个文件（from → to），也可以顺带建目标目录。" +
            "同分区用 rename（原子）；跨分区只复制、把源文件记进「资料库/整理/_待删除清单.md」，" +
            "不会自动删源。绝不覆盖：撞名不同内容自动加 sha 后缀让路，内容相同则视为已搬。" +
            "只搬文件不搬目录；每次搬都要用户确认。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_FROM to jsonObjectOf(
                    "type" to "string",
                    "description" to "源文件（相对工作区根，如 资料库/投递/某书.txt）",
                ),
                ARG_TO to jsonObjectOf(
                    "type" to "string",
                    "description" to "目标路径（相对工作区根；末段就是新文件名，如 资料库/整理/武侠/某书.txt）",
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
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        run(
            args = args,
            root = workspaceRoot(),
            createDirs = args.optBoolean(ARG_CREATE_DIRS, true),
        )
    }

    /**
     * 搬文件（**纯函数**：root 已定、无 Android 依赖，JVM 直测）。
     *
     * @param root       工作区根（越界判定基准）
     * @param from       源（相对或绝对；绝对也必须落在 root 内）
     * @param to         目标（同上）
     * @param createDirs 目标目录不存在时是否自动建（false ⇒ Failed，让模型先自己建）
     * @param stamp      待删除清单里的时间戳（注入以便 JVM 直测确定性输出）
     * @param preferCopy 测试钩子：true 时跳过 rename，直接走「复制 ＋ 记待删除清单」分支
     *                   （跨分区行为在单测里没有真实的跨分区，只能这样确定性地验）
     */
    fun move(
        root: File,
        from: String,
        to: String,
        createDirs: Boolean = true,
        stamp: String = TimeFormats.fileStamp(),
        preferCopy: Boolean = false,
    ): ToolOutcome {
        val fromRel = from.trim()
        val toRel = to.trim()
        if (fromRel.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_FROM」")
        if (toRel.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_TO」")
        val src = resolve(root, fromRel)
        val dst = resolve(root, toRel)
        if (!PathGuard.isInside(root, src)) {
            return ToolOutcome.Rejected(
                "路径越界被拒：$fromRel 不在工作区（${root.absolutePath}）内。" +
                    "move_path 只能在资料库里搬文件。",
            )
        }
        if (!PathGuard.isInside(root, dst)) {
            return ToolOutcome.Rejected(
                "路径越界被拒：$toRel 不在工作区（${root.absolutePath}）内。" +
                    "move_path 搬不到资料库外面去。",
            )
        }
        val backupDir = File(root, BACKUP_DIR_NAME)
        if (PathGuard.isInside(backupDir, src) || PathGuard.isInside(backupDir, dst)) {
            return ToolOutcome.Failed("不能把 $BACKUP_DIR_NAME/ 当搬动源或目标（那是旧版本备份区）")
        }
        if (src.absolutePath == dst.absolutePath) {
            return ToolOutcome.Failed("源和目标同一路径：$fromRel（改名请把 $ARG_TO 写成新名字）")
        }
        if (!src.exists()) return ToolOutcome.Failed("源文件不存在：$fromRel")
        if (src.isDirectory) {
            return ToolOutcome.Failed(
                "源是个目录：${src.name}。move_path 只搬单个文件——目录请逐个文件搬，或改用库导入流水线。",
            )
        }
        val parent = dst.parentFile
        if (parent != null && !parent.exists()) {
            if (!createDirs) {
                return ToolOutcome.Failed(
                    "目标目录不存在：${parent.absolutePath}（把 $ARG_CREATE_DIRS 设为 true，或先建目录）",
                )
            }
            if (!parent.mkdirs()) {
                return ToolOutcome.Failed("建不了目标目录：${parent.absolutePath}（权限或 IO 错误）")
            }
        }
        val srcSha = sha256(src)
            ?: return ToolOutcome.Failed("读不出源文件（IO 错误）：$fromRel")
        // 目标已存在：内容相同 ⇒ 幂等；不同 ⇒ 加 sha 后缀让路（绝不覆盖）
        var target = dst
        var renameNote = ""
        if (dst.exists() && !dst.isDirectory) {
            val dstSha = sha256(dst)
            if (dstSha != null && dstSha == srcSha) {
                return ToolOutcome.Ok(
                    "目标已有相同内容（sha 前 8 位 ${srcSha.take(SHA_SUFFIX_LEN)}），视为已搬。" +
                        "源文件仍在 $fromRel，没动任何文件。",
                )
            }
            target = bypassTarget(dst, srcSha)
            renameNote = "；目标已有同名文件，改名让路 ⇒ ${target.name}"
        }
        if (target.exists() && target.isDirectory) {
            return ToolOutcome.Failed("目标位置是个目录：${target.absolutePath}")
        }
        if (!preferCopy) {
            val renamed = runCatching { src.renameTo(target) }.getOrDefault(false)
            if (renamed) {
                return ToolOutcome.Ok(
                    "已搬动（rename，原子）：$fromRel → ${relOf(root, target)}$renameNote",
                )
            }
        }
        // 跨分区（或 rename 失败）：复制，然后把源记进待删除清单——不自动删
        val copied = runCatching { src.copyTo(target, overwrite = false) }.isSuccess
        if (!copied) {
            runCatching { if (target.exists()) target.delete() }
            return ToolOutcome.Failed("复制失败（IO 错误）：$fromRel → ${relOf(root, target)}。源文件原样未动。")
        }
        val listed = appendPendingDelete(root, src, target, stamp)
        return ToolOutcome.Ok(
            "已复制到 ${relOf(root, target)}$renameNote；源文件 $fromRel 保留未删" +
                if (listed) {
                    "，已记进 $PENDING_LIST_REL，你确认后再删"
                } else {
                    "（待删除清单写不进去，请自己留意这个源文件）"
                },
        )
    }

    /** 撞名让路：`名.ext` ⇒ `名.<sha8>.ext`；再撞就 `名.<sha8>-2.ext`。 */
    private fun bypassTarget(dst: File, sha: String): File {
        val base = dst.name.substringBeforeLast('.', dst.name)
        val ext = dst.name.substringAfterLast('.', "")
        val tail = if (ext.isEmpty()) "" else ".$ext"
        var candidate = File(dst.parentFile, "$base.${sha.take(SHA_SUFFIX_LEN)}$tail")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dst.parentFile, "$base.${sha.take(SHA_SUFFIX_LEN)}-$n$tail")
            n++
        }
        return candidate
    }

    /**
     * 把源文件追加进待删除清单（返回是否写成功；写失败不影响「文件已经复制好了」这个事实）。
     *
     * 清单是 md，一行一条；幂等性靠「同路径同目标只写一次」——这里刻意不做去重扫描
     * （清单是给人看的，重复一行比读整个文件再改便宜，也不会误导人）。
     */
    private fun appendPendingDelete(root: File, src: File, dst: File, stamp: String): Boolean {
        val listFile = File(root, PENDING_LIST_REL)
        return runCatching {
            listFile.parentFile?.mkdirs()
            val isNew = !listFile.exists()
            if (isNew) {
                listFile.writeText(
                    "# 待删除清单\n\n" +
                        "抱朴搬文件时，凡是跨分区只能复制、没能 rename 的，源文件都记在这里——" +
                        "**不会自动删**，你在文件管理器里确认后再点删除。\n\n",
                    Charsets.UTF_8,
                )
            }
            listFile.appendText("- $stamp 已复制到 ${relOf(root, dst)}，源可删：${src.absolutePath}\n")
        }.isSuccess
    }

    private fun resolve(root: File, path: String): File =
        if (File(path).isAbsolute) File(path) else File(root, path)

    private fun relOf(root: File, f: File): String = runCatching { f.relativeTo(root).path }.getOrDefault(f.name)

    /** 文件 sha256（小写 hex）；IO 失败返回 null。 */
    private fun sha256(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** executor：参数已过 SchemaCheck。 */
    private fun run(args: org.json.JSONObject, root: File, createDirs: Boolean): ToolOutcome {
        val from = args.optString(ARG_FROM).trim()
        val to = args.optString(ARG_TO).trim()
        if (from.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_FROM」")
        if (to.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_TO」")
        return move(root, from, to, createDirs = createDirs)
    }
}
