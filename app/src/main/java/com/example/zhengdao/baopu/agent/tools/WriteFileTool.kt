// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴线刀 2 追加包（通用写工具）。四条硬纪律逐条落地：
// 1. 权限 CONFIRM——每次写都走单步确认（GuardPipeline 第 ③ 段 ⇒ NeedsConfirm，
//    RunEngine 现有 AwaitConfirm 骨架；确认后的执行归下一刀，本刀只交工具本身）；
// 2. 路径必须过 PathGuard.isInside(工作区)，越界即拒（管线第 ② 段已按 path 参数名
//    守卫过一次，这里自检是双保险，且让纯函数层 JVM 直测）；
// 3. overwrite=true 时先把旧文件备份到 <工作区>/.baopu-bak/ 再写（不静默毁内容）；
// 4. 单次写入上限 200 行，超了拒绝并提示分次；**不提供任何删除能力**。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.rootfs.PathGuard
import com.example.zhengdao.util.TimeFormats
import java.io.File

/**
 * `write_file`：把文本写进工作区内的一个文件（刀 2 追加包）。
 *
 * 为什么备份而不是直接覆盖：模型「覆盖写」最常见的用法是改文件——直接盖掉旧内容，
 * 用户连「原来写过什么」都看不到；`.baopu-bak/` 留在工作区内，用户自己看得见、
 * 摸得着，也不需要 App 额外提供「恢复」入口（那是另一把刀的事）。
 *
 * 为什么拒绝写 `.baopu-bak/` 自己：备份区被写就会互相覆盖，备份就白做了。
 *
 * 路径不在本文件守卫第一道（管线第 ② 段按 path 参数名做）；本文件的 [write]
 * 自检 [PathGuard.isInside] 是第二道——纯函数层因此可直测「越界即拒」。
 */
internal object WriteFileTool {

    const val ARG_PATH = "path"
    const val ARG_CONTENT = "content"
    const val ARG_OVERWRITE = "overwrite"

    /** 单次写入行数上限（超了拒绝并提示分次——一次写 200 行以上的「文件」应该分几次给）。 */
    const val MAX_WRITE_LINES = 200

    /** 备份目录名（工作区内；用户可见）。 */
    const val BACKUP_DIR_NAME = ".baopu-bak"

    /** 构造注册用 Spec（workspaceRoot 注入：备份目录要落在工作区根下）。 */
    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "write_file",
        description = "把文本写进工作区内的一个文件（不存在则新建，存在需 overwrite=true " +
            "且会把旧版本备份到 .baopu-bak/）。单次最多 $MAX_WRITE_LINES 行，超了请分次写。" +
            "每次写都要用户确认；本工具没有任何删除能力。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "相对工作区根的目标路径（如 资料库/整理/设定.md）",
                ),
                ARG_CONTENT to jsonObjectOf(
                    "type" to "string",
                    "description" to "要写入的完整文本（不超过 $MAX_WRITE_LINES 行）",
                ),
                ARG_OVERWRITE to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "目标已存在时是否覆盖（默认 false；true 时先把旧文件备份到 .baopu-bak/）",
                ),
            ),
            "required" to jsonArrayOf(ARG_PATH, ARG_CONTENT),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot()) }

    /**
     * 写文件（**纯函数**：root/content 已定、无 Android 依赖，JVM 直测）。
     *
     * 四种拒绝（追加包判据①）：
     * - 越界：[Rejected]（终止本轮，与 GuardPipeline 第 ② 段同口径）；
     * - 超行数：[Failed]（可重试——模型看到上限会分次写）；
     * - 已存在而未 overwrite：[Failed]（可重试——模型可改 overwrite 或换路径）；
     * - 无删除能力：本对象没有任何删除 API；Schema 也没有 delete 参数
     *   （`additionalProperties: false`，幻觉出的 delete 参数过不了 SchemaCheck）。
     *
     * @param root   工作区根（越界判定 + 备份目录定位）
     * @param path   相对路径（或绝对路径；绝对路径也必须落在 root 内）
     * @param stamp 备份文件名时间戳（注入以便 JVM 直测确定性输出）
     */
    fun write(
        root: File,
        path: String,
        content: String,
        overwrite: Boolean,
        stamp: String = TimeFormats.fileStamp(),
    ): ToolOutcome {
        val rel = path.trim()
        if (rel.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        if (content.isEmpty()) {
            return ToolOutcome.Failed("参数「$ARG_CONTENT」为空（没有要写的内容；不想写就别调 write_file）")
        }
        val target = if (File(rel).isAbsolute) File(rel) else File(root, rel)
        if (!PathGuard.isInside(root, target)) {
            return ToolOutcome.Rejected(
                "路径越界被拒：$rel 不在工作区（${root.absolutePath}）内。" +
                    "write_file 只能写工作区里的文件。",
            )
        }
        val backupDir = File(root, BACKUP_DIR_NAME)
        if (PathGuard.isInside(backupDir, target)) {
            return ToolOutcome.Failed(
                "不能写进 $BACKUP_DIR_NAME/（那是旧版本备份区，写进去会互相覆盖）",
            )
        }
        val lines = content.lines()
        if (lines.size > MAX_WRITE_LINES) {
            return ToolOutcome.Failed(
                "一次最多写 $MAX_WRITE_LINES 行，这次有 ${lines.size} 行。" +
                    "请分次写（比如先写前 100 行，再写剩下的），或改用 read_file 确认结构后精简。",
            )
        }
        val exists = target.exists()
        if (exists && !overwrite) {
            return ToolOutcome.Failed(
                "目标已存在：${target.name}。要覆盖它就把 overwrite 设为 true" +
                    "（旧版本会先备份到 $BACKUP_DIR_NAME/），或者换个路径写。",
            )
        }
        if (exists && target.isDirectory) {
            return ToolOutcome.Failed("目标是个目录：${target.name}（write_file 只写文件）")
        }
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return ToolOutcome.Failed("建不了目标目录：${parent.name}（权限或 IO 错误）")
        }
        // 覆盖前先备份（备份失败 ⇒ 拒绝写：宁可写不进去，也不能把旧内容弄丢）
        var backupNote = ""
        if (exists) {
            val backupName = rel.replace('/', '_').replace('\\', '_') + "." + stamp + ".bak"
            val backupFile = File(backupDir, backupName)
            val ok = runCatching {
                backupDir.mkdirs()
                target.copyTo(backupFile, overwrite = true)
            }.isSuccess
            if (!ok) {
                return ToolOutcome.Failed(
                    "备份旧版本失败（${target.name} ⇒ $BACKUP_DIR_NAME/$backupName），已放弃本次写入。" +
                        "旧文件原样未动。",
                )
            }
            backupNote = "；旧版本已备份到 $BACKUP_DIR_NAME/$backupName"
        }
        // 原子写：先 .tmp 再 rename（与资料库导出口径一致——半截文件比不写更坏）
        val tmp = File(target.parentFile, target.name + ".write-tmp")
        val ok = runCatching {
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                // rename 失败（个别文件系统）退回复制，再删临时文件
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }.isSuccess
        if (!ok) {
            runCatching { tmp.delete() }
            return ToolOutcome.Failed("写入失败（IO 错误）：${target.name}；目标目录状态未保证，请用 read_file 复核")
        }
        val verb = if (exists) "已覆盖" else "已新建"
        return ToolOutcome.Ok(
            "$verb ${target.name}（${lines.size} 行，${content.length} 字符）$backupNote",
        )
    }

    /** executor：arguments 里的 path 已是守卫过的绝对路径（无 path 参数时走原始值）。 */
    private fun run(args: org.json.JSONObject, root: File): ToolOutcome {
        val path = args.optString(ARG_PATH).trim()
        if (path.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        return write(
            root = root,
            path = path,
            content = args.optString(ARG_CONTENT),
            overwrite = args.optBoolean(ARG_OVERWRITE, false),
        )
    }
}
