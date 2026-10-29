// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第二百六十九批派工单（打包／解包技能）。两个工具，一条纪律：
// **危险的动作先判后做**——打包不覆盖已有包，解包不覆盖已有文件、不越界、不超量。
//
// 为什么值得做成工具（而不是让模型跑 shell）：抱朴不碰 shell（AGENTS 里那条边界）。
// 用户在手机上收到一个 .zip 想解开、或想把一堆图打成一个包发出去，这是**手机上的日常**，
// 而 Android 文件管理器大多只认 zip、不认 tar。
//
// 为什么权限是 CONFIRM：两个工具都写盘（打出一个新包／解开一堆文件），与 move_path、
// image_ops 同一档——用户点一次「就这样」比事后收拾一地被解开的文件便宜。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PersonaGuard
import com.example.zhengdao.baopu.archive.ArchiveExtractor
import com.example.zhengdao.baopu.archive.ArchiveRules
import com.example.zhengdao.baopu.archive.ExtractOutcome
import com.example.zhengdao.baopu.archive.PackResult
import com.example.zhengdao.baopu.archive.ZipPacker
import com.example.zhengdao.baopu.workspaceBoundaryCheck
import com.example.zhengdao.baopu.workspaceWriteBoundaryCheck
import java.io.File

/**
 * `archive_pack` / `archive_extract`：工作区内的 zip 打包与归档解包。
 *
 * 判据与拒绝文案都在 `baopu/archive/` 里（纯函数，JVM 直测）；本文件只做三件事：
 * 解析参数、过边界（读侧放大到全盘、写侧只认工作区与 `Download/证道`）、把结果翻成人话。
 */
internal object ArchiveTools {

    const val ARG_DEST = "dest"
    const val ARG_SOURCES = "sources"
    const val ARG_ARCHIVE = "archive"

    /** 解包目标的默认名：归档名去掉扩展名（`资料.tar.gz` ⇒ `资料/`）。 */
    private fun defaultDestName(archiveName: String): String {
        val lower = archiveName.lowercase()
        val cut = when {
            lower.endsWith(".tar.gz") -> archiveName.length - ".tar.gz".length
            lower.endsWith(".tar.zst") -> archiveName.length - ".tar.zst".length
            lower.endsWith(".tar.xz") -> archiveName.length - ".tar.xz".length
            lower.contains('.') -> archiveName.lastIndexOf('.')
            else -> archiveName.length
        }
        val base = archiveName.substring(0, cut).trim()
        return if (base.isBlank()) "${archiveName}_解压" else base
    }

    // ────────────────────────── 打包 ──────────────────────────

    fun packSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "archive_pack",
        description = "把工作区里的若干文件/目录打成一个 zip（中文名不会乱码，Windows 双击即可打开）。" +
            "大文件自动走 Zip64；jpg/mp4/zip 不再重复压缩。目标已存在时拒绝，不覆盖。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_DEST to jsonObjectOf(
                    "type" to "string",
                    "description" to "要生成的 zip 路径（相对工作区，必须以 .zip 结尾）",
                ),
                ARG_SOURCES to jsonObjectOf(
                    "type" to "array",
                    "description" to "要打包的文件或目录（相对工作区；目录会递归）",
                    "items" to jsonObjectOf("type" to "string"),
                ),
            ),
            "required" to jsonArrayOf(ARG_DEST, ARG_SOURCES),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.ARCHIVE_MS,
        group = ToolGroup.FILES,
        riskTier = "R1",
    ) { args ->
        val sources = args.optJSONArray(ARG_SOURCES)?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        pack(workspaceRoot(), args.optString(ARG_DEST), sources)
    }

    fun pack(root: File, destRel: String, sourcesRel: List<String>): ToolOutcome {
        val dest = destRel.trim()
        if (dest.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_DEST」（要生成的 zip 路径）")
        if (sourcesRel.isEmpty()) return ToolOutcome.Failed("缺少必填参数「$ARG_SOURCES」（要打包的文件或目录）")
        val zipRel = if (dest.lowercase().endsWith(".zip")) dest else "$dest.zip"
        PersonaGuard.blocked(zipRel)?.let { return ToolOutcome.Rejected(it) }
        val zipFile = File(root, zipRel)
        workspaceWriteBoundaryCheck(
            root,
            zipFile,
            zipRel,
            "路径越界被拒：$zipRel 在可写范围（当前工作区与 Download/证道）之外。archive_pack 只往工作区里写包。",
        )?.let { return ToolOutcome.Rejected(it) }

        val sources = ArrayList<File>(sourcesRel.size)
        for (rel in sourcesRel) {
            val one = rel.trim()
            if (one.isBlank()) continue
            PersonaGuard.blocked(one)?.let { return ToolOutcome.Rejected(it) }
            val f = File(root, one)
            workspaceBoundaryCheck(
                root,
                f,
                one,
                "路径越界被拒：$one 在工作区外，archive_pack 不往包里装工作区外的东西。",
            )?.let { return ToolOutcome.Rejected(it) }
            if (!f.exists()) return ToolOutcome.Failed("找不到：$one")
            sources.add(f)
        }

        return when (val r = ZipPacker.pack(zipFile, sources)) {
            is PackResult.Failed -> ToolOutcome.Failed(r.reason)
            is PackResult.Ok -> ToolOutcome.Ok(
                "已打出 $zipRel：${r.entries} 个条目、原始 ${mb(r.bytes)}" +
                    "（其中 ${r.storedEntries} 个按原样存、${r.deflatedEntries} 个压缩）。" +
                    "文件名按 UTF-8 写入并置了 UTF-8 标志位，Windows/macOS 双击打开中文名正常。",
            )
        }
    }

    // ────────────────────────── 解包 ──────────────────────────

    fun extractSpec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "archive_extract",
        description = "解一个归档到工作区目录：.zip 走 Java，.tar/.tar.gz/.tgz/.tar.zst 走 Rust。" +
            "含 ..、绝对路径或符号链接的成员整包拒绝；解压总量上限 ${ArchiveRules.MAX_TOTAL_HUMAN}；" +
            "目标已有同名文件时不覆盖、整包不落盘。.rar/.7z 明确不支持。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_ARCHIVE to jsonObjectOf(
                    "type" to "string",
                    "description" to "要解开的归档（相对工作区）",
                ),
                ARG_DEST to jsonObjectOf(
                    "type" to "string",
                    "description" to "解到哪个目录（相对工作区；不填则用归档名去掉扩展名）",
                ),
            ),
            "required" to jsonArrayOf(ARG_ARCHIVE),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.CONFIRM,
        timeoutMs = ToolTimeout.ARCHIVE_MS,
        group = ToolGroup.FILES,
        riskTier = "R1",
    ) { args ->
        extract(workspaceRoot(), args.optString(ARG_ARCHIVE), args.optString(ARG_DEST))
    }

    fun extract(root: File, archiveRel: String, destRel: String): ToolOutcome {
        val rel = archiveRel.trim()
        if (rel.isBlank()) return ToolOutcome.Failed("缺少必填参数「$ARG_ARCHIVE」（要解开的归档）")
        PersonaGuard.blocked(rel)?.let { return ToolOutcome.Rejected(it) }
        val archive = File(root, rel)
        workspaceBoundaryCheck(
            root,
            archive,
            rel,
            "路径越界被拒：$rel 在工作区外，archive_extract 只解工作区里的包。",
        )?.let { return ToolOutcome.Rejected(it) }
        if (!archive.exists()) return ToolOutcome.Failed("找不到这个归档：$rel")

        val destText = destRel.trim().ifBlank {
            val parent = rel.substringBeforeLast('/', "")
            val name = defaultDestName(archive.name)
            if (parent.isEmpty()) name else "$parent/$name"
        }
        PersonaGuard.blocked(destText)?.let { return ToolOutcome.Rejected(it) }
        val dest = File(root, destText)
        workspaceWriteBoundaryCheck(
            root,
            dest,
            destText,
            "路径越界被拒：$destText 在可写范围（当前工作区与 Download/证道）之外。archive_extract 只往工作区里解。",
        )?.let { return ToolOutcome.Rejected(it) }

        return when (val r = ArchiveExtractor.extract(archive, dest)) {
            is ExtractOutcome.Ok -> ToolOutcome.Ok(
                "已解开 ${archive.name} ⇒ $destText：${r.entries} 个文件、${mb(r.bytes)}。",
            )

            is ExtractOutcome.Rejected -> ToolOutcome.Rejected(r.reason)
            is ExtractOutcome.Failed -> ToolOutcome.Failed(r.reason)
        }
    }

    private fun mb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
}
