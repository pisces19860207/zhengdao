// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：工具补强第二刀（编码处理）。五条硬纪律：
// 1. 权限 CONFIRM——它会**改写盘上的文件**，每次都要用户点头（与 write_file/edit_file 同款）；
// 2. 转换前先把**原始字节**备份到 `<工作区>/.baopu-bak/`（备份失败 ⇒ 放弃本次转换）；
// 3. 幂等：本来就是 UTF-8 就明说「没动」，不重写、不再备份（重跑不会堆一堆备份）；
// 4. 二进制一律拒绝（转了只会毁文件）；
// 5. 写回走「临时文件 + rename」原子替换，中途失败不留半个文件。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.rootfs.PathGuard
import com.example.zhengdao.util.TimeFormats
import java.io.File

/**
 * `convert_encoding`：把一个非 UTF-8 的文本文件**落盘转成 UTF-8**（工具补强第二刀）。
 *
 * 为什么单独立一个工具，而不是让 read_file 顺手转：
 * - 读的时候转，只能让模型这次看见正确文本，**盘上还是 GBK**——下次别的工具（切章、
 *   搜索、导出）还是一样踩坑；
 * - 转换是显式、可确认、可回滚的动作（有备份），不该藏在「读」里偷偷发生。
 */
internal object ConvertEncodingTool {

    const val ARG_PATH = "path"

    /** 备份目录（与 write_file/edit_file/move_path 同一处，用户看得见）。 */
    const val BACKUP_DIR_NAME = ".baopu-bak"

    /** 单文件大小上限（超过 8 MB 的书让用户先拆，别在 5 秒超时里赌）。 */
    const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** 构造注册用 Spec（workspaceRoot 注入：备份目录 + 越界自检都要它）。 */
    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "convert_encoding",
        description = "把一个非 UTF-8 的文本文件（GBK/GB18030、UTF-16）转成 UTF-8 写回原文件。" +
            "转换前自动把原文件备份到 $BACKUP_DIR_NAME/。本来就是 UTF-8 时不改（幂等）。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf(
                    "type" to "string",
                    "description" to "相对工作区根的文件路径",
                ),
            ),
            "required" to jsonArrayOf(ARG_PATH),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> run(args, workspaceRoot()) }

    /**
     * 转码（**纯函数**：root/file 已定、无 Android 依赖，JVM 直测；会写盘）。
     *
     * @param root 工作区根（越界自检 + 备份目录的基准）
     * @param file 目标文件（绝对路径；调用方保证已在工作区内）
     */
    fun convert(root: File, file: File): ToolOutcome {
        if (!file.exists()) {
            return ToolOutcome.Failed("不存在：${file.name}（工作区里没有这个文件；先用 find_files 或 list_dir 找）")
        }
        if (!file.isFile) {
            return ToolOutcome.Failed("不是一个文件：${file.name}（convert_encoding 只转文件）")
        }
        if (!PathGuard.isInside(root, file)) {
            return ToolOutcome.Rejected("路径越界被拒：${file.name} 不在工作区（${root.absolutePath}）内。")
        }
        val backupDir = File(root, BACKUP_DIR_NAME)
        if (PathGuard.isInside(backupDir, file)) {
            return ToolOutcome.Failed("不能转 $BACKUP_DIR_NAME/ 里的文件（那是旧版本备份区，改它等于毁掉退路）")
        }
        if (file.length() > MAX_FILE_BYTES) {
            return ToolOutcome.Failed(
                "文件太大不转：${file.name} 有 ${file.length()} B，超过 $MAX_FILE_BYTES B 上限。先拆小再转。",
            )
        }
        val bytes = runCatching { file.readBytes() }
            .getOrElse { return ToolOutcome.Failed("读不了这个文件（权限或 IO 错误）：${file.name}") }
        // 编码只留一份实现（2026-10-17 行 180 的口径）：复用 TextDecode，不另起一套猜法。
        // TextDecode.decode 对二进制直接返回 text=null（error 里带人话）⇒ 这里按拒绝处理。
        val decoded = TextDecode.decode(file, bytes)
        val text = decoded.text
            ?: return ToolOutcome.Rejected(decoded.error ?: "不动这个文件：${file.name}")
        if (decoded.encoding == FileInfoTool.Encodings.UTF8) {
            return ToolOutcome.Ok("本来就是 UTF-8，没动：${relPath(root, file)}")
        }
        if (decoded.encoding == FileInfoTool.Encodings.UTF8_BOM) {
            return ToolOutcome.Ok(
                "本来就是 UTF-8（只是开头有 BOM），没动：${relPath(root, file)}。" +
                    "BOM 不影响读，也不影响切章。",
            )
        }
        // 备份原始字节（不是转码后的文本——回滚要能一字不差地回去）
        // 门禁 B-补-24：主源码不许裸 SimpleDateFormat，时间戳一律走 TimeFormats
        val stamp = TimeFormats.fileStamp()
        val rel = relPath(root, file)
        val backupName = rel.replace('/', '_').replace('\\', '_') + "." + stamp + ".bak"
        val backupFile = File(backupDir, backupName)
        val backupOk = runCatching {
            backupDir.mkdirs()
            backupFile.writeBytes(bytes)
        }.isSuccess
        if (!backupOk) {
            return ToolOutcome.Failed("备份原文件失败（$BACKUP_DIR_NAME/$backupName），已放弃本次转换。")
        }
        // 原子替换：先写同目录临时文件，再 rename
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val tmp = File(file.parentFile ?: root, file.name + ".baopu-tmp")
        val written = runCatching {
            tmp.writeBytes(utf8)
            if (file.exists() && !file.delete()) error("旧文件删不掉")
            if (!tmp.renameTo(file)) error("rename 失败")
        }.isSuccess
        if (!written) {
            runCatching { tmp.delete() }
            runCatching { file.writeBytes(bytes) } // 尽力恢复原字节（备份仍在）
            return ToolOutcome.Failed(
                "写回失败：${file.name}。已尽力恢复原字节；备份在 $BACKUP_DIR_NAME/$backupName。",
            )
        }
        val lines = TextDecode.splitLines(text).size
        return ToolOutcome.Ok(
            "已转：${relPath(root, file)}  ${decoded.encoding} → UTF-8（$lines 行，${utf8.size} B）；" +
                "原文件已备份到 $BACKUP_DIR_NAME/$backupName",
        )
    }

    /** executor：arguments 里的 path 已是守卫过的绝对路径。 */
    private fun run(args: org.json.JSONObject, root: File): ToolOutcome {
        val path = args.optString(ARG_PATH).trim()
        if (path.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_PATH」")
        return convert(root, File(path))
    }

    /** 相对路径渲染（统一 `/`）。 */
    private fun relPath(root: File, f: File): String = runCatching {
        f.absoluteFile.toRelativeString(root.absoluteFile).replace('\\', '/')
    }.getOrDefault(f.name)
}
