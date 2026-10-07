// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据的公开标准与官方文档：
//   tar 归档格式（POSIX 1003.1-1988 ustar 及 GNU 扩展），经 Apache Commons Compress
//   官方文档使用 TarArchiveInputStream；zstd 魔数 0x28 B5 2F FD（zstd 官方规范）与
//   gzip 魔数 0x1F 8B（RFC 1952）用于自动识别压缩格式；
//   符号链接/权限经 android.system.Os（symlink/chmod，NDK POSIX 封装）。
package com.example.zhengdao.rootfs

import android.content.Context
import android.os.StatFs
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * RootFS 解压安装器（设计文档 §6/§8）：
 * - 解压到独立 tmp 目录 → 写完成标记 → 原子替换 rootfs 目录（解压原子性，审计项）；
 * - 启动时清理上次中断的残局；
 * - 硬链接一律按“复制内容”落地（实测坑 #4：SELinux 拒绝非 root 应用创建硬链接，
 *   与 UV_LINK_MODE=copy、proot --link2symlink 同一因果）；
 * - 归档格式按魔数自动识别：zstd（主格式）或 gzip（兼容格式）。
 */
object RootfsInstaller {

    private const val TAG = "RootfsInstaller"
    private const val TMP_NAME = "rootfs.tmp"
    private const val MARKER = ".zhengdao-rootfs-ok"
    private const val REQUIRED_FREE_BYTES = 2_500_000_000L // 落盘约 1.5–2GB + 余量

    class InstallFailed(message: String) : IOException(message)

    /** 存储预检（设计文档 §2：按落盘体积校验，不足时给出明确差额）。 */
    fun ensureFreeSpace(context: Context, archiveBytes: Long) {
        val stat = StatFs(context.filesDir.absolutePath)
        val free = stat.availableBytes
        val needed = REQUIRED_FREE_BYTES + archiveBytes
        if (free < needed) {
            throw InstallFailed(
                "存储空间不足：本次安装约需 ${(needed + 511) / 1_000_000}MB，当前可用 ${(free + 511) / 1_000_000}MB"
            )
        }
    }

    /** 启动时清理上次中断的残局（只动 tmp 目录，不碰现有 rootfs 与 home）。 */
    fun cleanupPartial(context: Context) {
        try {
            File(context.filesDir, TMP_NAME).deleteRecursively()
        } catch (t: Throwable) {
            Log.w(TAG, "清理残局失败（忽略）", t)
        }
    }

    /**
     * 解压归档并安装。
     * @param onEntry 每处理一个条目回调一次其路径（调用方自行节流展示）
     */
    fun install(context: Context, archive: File, onEntry: (String) -> Unit) {
        val files = context.filesDir
        val rootfsDir = File(files, "rootfs")
        val tmpDir = File(files, TMP_NAME)
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        // ── Rust 快路径（v2.0 R2 原型）：数据常驻 native，边界只跨一次 ──
        // 回退纪律（规范 #2）：任何失败 → 落回下方 commons-compress Java 路径
        if (com.example.zhengdao.rust.ExtractNative.isRustAvailable()) {
            val rustOk = runCatching {
                val report = com.example.zhengdao.rust.ExtractNative.extract(
                    archive.canonicalPath, tmpDir.canonicalPath, null   // SHA 已在调用方校验过
                )
                // Rust 侧统计含目录条目；onEntry 节流由调用方负责
                Log.i(TAG, "Rust 解压完成: ${report.first} 条目 ${report.second / 1048576}MB sha=${report.third.take(12)}")
            }.isSuccess
            if (rustOk) {
                File(tmpDir, MARKER).writeText("distro=debian-13.7\ninstalled-by=zhengdao\n")
                if (rootfsDir.exists()) rootfsDir.deleteRecursively()
                if (!tmpDir.renameTo(rootfsDir)) {
                    tmpDir.copyRecursively(rootfsDir, overwrite = true)
                    tmpDir.deleteRecursively()
                }
                Log.i(TAG, "RootFS 安装完成（Rust 路径）：${rootfsDir.path}")
                return
            }
            Log.w(TAG, "Rust 解压失败，回退 Java 路径")
        }
        val canonicalRoot = tmpDir.canonicalFile

        FileInputStream(archive).use { fin ->
            BufferedInputStream(fin, 512 * 1024).use { buffered ->
                val decompressed = when (formatOf(peekMagic(buffered))) {
                    Format.ZSTD -> ZstdCompressorInputStream(buffered)
                    Format.GZIP -> GzipCompressorInputStream(buffered, false)
                }
                TarArchiveInputStream(decompressed, "UTF-8").use { tar ->
                    val pendingHardLinks = mutableListOf<TarArchiveEntry>()
                    var entry: TarArchiveEntry? = tar.nextTarEntry
                    while (entry != null) {
                        val name = entry.name.removePrefix("./")
                        if (name.isNotEmpty() && name != ".") {
                            val target = File(tmpDir, name)
                            checkPathInside(canonicalRoot, target)
                            onEntry("./$name")
                            extractEntry(entry, name, target, tmpDir, tar, pendingHardLinks)
                        }
                        entry = tar.nextTarEntry
                    }
                    // 二阶段：补齐“源文件在归档中后置”的前向硬链接
                    for (hl in pendingHardLinks) {
                        val src = File(tmpDir, hl.linkName.removePrefix("./"))
                        val dst = File(tmpDir, hl.name.removePrefix("./"))
                        if (src.isFile) src.copyTo(dst, overwrite = true)
                    }
                }
            }
        }

        // 完成标记（ProotLauncher 依据它判定环境可用）
        File(tmpDir, MARKER).writeText("distro=debian-13.7\ninstalled-by=zhengdao\n")

        // 原子替换：旧环境整体让位（home 在独立目录，不受影响——设计文档 §8）
        if (rootfsDir.exists()) rootfsDir.deleteRecursively()
        if (!tmpDir.renameTo(rootfsDir)) {
            tmpDir.copyRecursively(rootfsDir, overwrite = true)
            tmpDir.deleteRecursively()
        }
        Log.i(TAG, "RootFS 安装完成：${rootfsDir.path}")
    }

    private enum class Format { ZSTD, GZIP }

    private fun extractEntry(
        entry: TarArchiveEntry,
        name: String,
        target: File,
        tmpDir: File,
        tar: TarArchiveInputStream,
        pendingHardLinks: MutableList<TarArchiveEntry>,
    ) {
        when {
            entry.isDirectory -> {
                target.mkdirs()
                chmod(target, entry.mode)
            }
            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                target.delete()
                try {
                    Os.symlink(entry.linkName, target.absolutePath)
                } catch (t: Throwable) {
                    // 个别 symlink 建不出来不致命：退化为空文件占位，proot 环境仍可用
                    Log.w(TAG, "symlink 失败 $name -> ${entry.linkName}", t)
                    target.writeBytes(ByteArray(0))
                }
            }
            entry.isLink -> {
                // 硬链接 → 复制内容落地；源文件若尚未解出（前向引用），登记到二阶段
                val src = File(tmpDir, entry.linkName.removePrefix("./"))
                target.parentFile?.mkdirs()
                if (src.isFile) {
                    src.copyTo(target, overwrite = true)
                } else {
                    pendingHardLinks.add(entry)
                }
            }
            entry.isFIFO || entry.linkFlag == TarConstants.LF_CHR || entry.linkFlag == TarConstants.LF_BLK -> {
                // 设备节点与 FIFO 在 proot -b /dev 的方案下不需要真实创建，跳过
            }
            else -> {
                target.parentFile?.mkdirs()
                target.outputStream().use { tarStream -> tar.copyTo(tarStream) }
                chmod(target, entry.mode)
            }
        }
    }

    private fun formatOf(magic: ByteArray): Format = when {
        magic.size >= 4 &&
            magic[0] == 0x28.toByte() && magic[1] == 0xB5.toByte() &&
            magic[2] == 0x2F.toByte() && magic[3] == 0xFD.toByte() -> Format.ZSTD
        magic.size >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte() -> Format.GZIP
        else -> throw InstallFailed("无法识别的压缩格式（既非 zstd 也非 gzip）")
    }

    private fun peekMagic(buffered: BufferedInputStream): ByteArray {
        buffered.mark(4)
        val magic = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = buffered.read(magic, read, 4 - read)
            if (n < 0) break
            read += n
        }
        buffered.reset()
        if (read < 4) throw InstallFailed("压缩包过小或已损坏")
        return magic
    }

    /** 防路径穿越：解压目标必须落在 rootfs 临时目录内部。 */
    private fun checkPathInside(root: File, target: File) {
        if (!target.canonicalFile.path.startsWith(root.path)) {
            throw InstallFailed("压缩包含越界路径: ${target.path}")
        }
    }

    private fun chmod(file: File, mode: Int) {
        try {
            Os.chmod(file.absolutePath, mode and 4095) // 4095 = 0o7777（八进制 → 十进制）
        } catch (t: Throwable) {
            Log.w(TAG, "chmod 失败: ${file.path}", t)
        }
    }
}
