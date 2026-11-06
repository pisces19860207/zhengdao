// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第四百九十一批①「一键导出 zip」与②「带 manifest.json：版本、时间、条目数、每文件 hash」。
//
// 三个决定：
// 1. **一遍过算 hash**：边写边喂 MessageDigest（不再读一遍源文件）——账本可能几千行，
//    少一遍顺序读是白捡的；hash 与实际写进去的字节**逐字节同源**，不会出现"算的和写的不是两份"。
// 2. **清单最后写**：`manifest.json` 是最后一条 entry，它列的是"已经写进包里的东西"，
//    而不是"打算写的"——中途被杀时包不完整，但不会有"清单说有 12 个、包里只有 3 个"的假账。
// 3. **失败删包**：写到一半失败（存储满/被杀）留下的半截 zip 看起来像备份，最危险，
//    所以任何异常都把目标文件删掉再报错。
package com.example.zhengdao.backup

import com.example.zhengdao.core.TimeFormats
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 导出的元信息（时间戳由调用方给，方便单测钉死，不在这里读系统时钟）。 */
internal data class ExportMeta(
    val appVersion: String,
    val includesChat: Boolean,
    val createdAt: Long,
) {
    val createdAtText: String get() = TimeFormats.isoSeconds(createdAt)
}

/** 不落盘的条目（设置快照就这一种：内容由 [BackupRules.dumpSettings] 现算）。 */
internal data class InMemoryFile(val zipPath: String, val group: BackupGroup, val bytes: ByteArray)

/** 导出结果：成功带"包在哪 + 清单 + 跳过清单"，失败给一句人话。 */
internal sealed interface ExportOutcome {
    data class Ok(val file: File, val manifest: BackupManifest) : ExportOutcome
    data class Failed(val reason: String) : ExportOutcome
}

/**
 * 把 [PlannedFile] 与 [extra] 写成一个 zip（含清单）。
 *
 * 只做"写"，不做"扫"（扫在 [BackupWalker]）——一个函数一件事，出事好定位。
 */
internal object BackupExporter {

    fun export(
        dest: File,
        files: List<PlannedFile>,
        extra: List<InMemoryFile>,
        skipped: List<BackupSkip>,
        meta: ExportMeta,
    ): ExportOutcome {
        if (files.isEmpty() && extra.isEmpty()) return ExportOutcome.Failed("没有可导出的东西")
        val target = uniqueDest(dest)
        target.parentFile?.let { if (!it.isDirectory) runCatching { it.mkdirs() } }
        val entries = ArrayList<BackupEntry>(files.size + extra.size)
        return runCatching {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(target)), Charsets.UTF_8).use { zip ->
                for (f in files) entries += writeFile(zip, f)
                for (x in extra) entries += writeBytes(zip, x)
                val manifest = BackupManifest(
                    appVersion = meta.appVersion,
                    createdAt = meta.createdAt,
                    createdAtText = meta.createdAtText,
                    includesChat = meta.includesChat,
                    entries = entries.sortedBy { it.path },
                    skipped = skipped,
                )
                val manifestBytes = BackupManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)
                writeBytes(zip, InMemoryFile(BackupManifestCodec.MANIFEST_NAME, BackupGroup.SETTINGS, manifestBytes))
                ExportOutcome.Ok(target, manifest)
            }
        }.getOrElse { e ->
            // 半截 zip 比没有更糟：它会被当成"一份备份"留着
            runCatching { target.delete() }
            ExportOutcome.Failed("写包失败（${e.javaClass.simpleName}）：${e.message ?: "没有更多信息"}")
        }
    }

    /** 目标名被占用时顺延 `-2`、`-3`（同分钟内连点两次不该互相覆盖）。 */
    internal fun uniqueDest(dest: File): File {
        if (!dest.exists()) return dest
        val name = dest.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (n < 1000) {
            val f = File(dest.parentFile, "$stem-$n$ext")
            if (!f.exists()) return f
            n++
        }
        return File(dest.parentFile, "$stem-${System.currentTimeMillis()}$ext")
    }

    /** 拷一个真文件进包，返回它的清单条目（`sha256` 与写进去的字节同源）。 */
    private fun writeFile(zip: ZipOutputStream, f: PlannedFile): BackupEntry {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        zip.putNextEntry(ZipEntry(f.zipPath))
        f.source.inputStream().use { input ->
            val buf = ByteArray(BackupRules.BUF_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
                zip.write(buf, 0, n)
                size += n
            }
        }
        zip.closeEntry()
        return BackupEntry(f.zipPath, f.group, size, BackupRules.hexOf(md.digest()))
    }

    private fun writeBytes(zip: ZipOutputStream, x: InMemoryFile): BackupEntry {
        zip.putNextEntry(ZipEntry(x.zipPath))
        zip.write(x.bytes)
        zip.closeEntry()
        return BackupEntry(x.zipPath, x.group, x.bytes.size.toLong(), BackupRules.sha256Of(x.bytes))
    }
}
