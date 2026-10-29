// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第二百六十九批派工单 §1「打包：只做 zip（java.util.zip 零依赖）；中文名不乱码
// （UTF-8 flag）、大文件走 Zip64、已压缩格式（jpg/mp4/zip）不再压」。
//
// 三个决定各自的技术理由（都不是「照抄教程」）：
// 1. **UTF-8 flag（EFS，通用标志位 bit 11）**：只把名字按 UTF-8 写进去不够——Windows 资源
//    管理器不看字节，看这一位；不置位它按本地代码页（中文区 GBK）解释，于是「中文名.zip」
//    双击打开变乱码。`ZipOutputStream(out, UTF_8)` 在 JDK/Android 的 libcore 里都会置这一位
//    （putNextEntry 的 `if (zc.isUTF8()) flag |= 0x0800`），本类再显式传 charset 保证不依赖默认值。
// 2. **Zip64**：`ZipOutputStream` 在条目数 >65535 或偏移/大小 ≥4GB 时自动写 Zip64 扩展字段
//    （`setUseZip64(AsNeeded)` 是默认值），所以「大文件」这件事不需要手写——但**必须不设**
//    `setSize`/`setCompressedSize` 的错值，否则会提前把 32 位字段写死。STORED 条目要预置
//    size/crc（见 3），预置的是**真实**大小，Zip64 仍会按需补齐。
// 3. **STORED 要预置 crc+size**：`java.util.zip` 对 STORED 条目要求调用方先给 `size`/
//    `compressedSize`/`crc`（它不缓存数据、无法事后回填），所以 jpg/mp4/zip 这类条目
//    **读两遍**（第一遍算 crc+大小，第二遍写）。代价是一次顺序读盘，换来的是「不压」这件事
//    真生效——比 deflate 一遍 2GB 的 mp4 便宜得多。
package com.example.zhengdao.baopu.archive

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 打包结果（Ok 带读数：条目数／原始字节／不压条目数，交付报告与工具回执都要贴）。 */
internal sealed interface PackResult {
    data class Ok(val entries: Int, val bytes: Long, val storedEntries: Int, val deflatedEntries: Int) : PackResult
    data class Failed(val reason: String) : PackResult
}

/**
 * 把若干文件/目录打成一个 zip。
 *
 * 条目名口径（可预测，不给模型留解释空间）：
 * - 文件 `a/b/报告.txt` ⇒ 条目 `报告.txt`（取 basename——用户点的是「把这几份打一起」）；
 * - 目录 `a/资料` ⇒ 条目 `资料/…`（保留目录名，解开后是一个文件夹，不会散一地）。
 */
internal object ZipPacker {

    /** 1980-01-01 00:00:00（zip 时间戳下限；早于它的 lastModified 会被 ZipEntry 判非法）。 */
    private const val MIN_DOS_TIME = 315532800000L

    /** 拷贝缓冲：64 KB（顺序写，够大才不会每 4 KB 一次系统调用）。 */
    private const val BUF = 64 * 1024

    /**
     * @param dest 目标 zip（**必须不存在**：覆盖一个已有包等于毁掉上一版，与 move_path/card_export 同口径）
     * @param sources 源文件或目录（可以多个；目录递归）
     */
    fun pack(dest: File, sources: List<File>): PackResult {
        if (dest.exists()) {
            return PackResult.Failed("目标已存在：${dest.name}（不覆盖已有文件；换个名字再打，或先自己删掉它）")
        }
        if (sources.isEmpty()) return PackResult.Failed("没有要打包的东西：sources 是空的")
        for (s in sources) {
            if (!s.exists()) return PackResult.Failed("找不到：${s.path}")
            if (s.canonicalFile == dest.canonicalFile) return PackResult.Failed("目标 zip 不能是源之一：${s.name}")
        }

        val plan = ArrayList<Planned>()
        val seen = HashSet<String>()
        try {
            for (s in sources) {
                when {
                    s.isDirectory -> collectDir(s, s.name, plan, seen)
                    else -> addFile(s, s.name, plan, seen)
                }
            }
        } catch (e: IllegalArgumentException) {
            return PackResult.Failed(e.message ?: "打包清单有问题")
        }
        if (plan.isEmpty()) return PackResult.Failed("这些路径里没有可打包的文件（空目录不会生成条目以外的内容）")

        plan.sortBy { it.name }

        dest.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) return PackResult.Failed("建不了目录：${parent.path}")
        }

        var entries = 0
        var stored = 0
        var deflated = 0
        var bytes = 0L
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(dest), BUF), Charsets.UTF_8).use { zos ->
                for (item in plan) {
                    val entry = ZipEntry(item.name)
                    entry.time = maxOf(item.file.lastModified(), MIN_DOS_TIME)
                    if (item.name.endsWith("/")) {
                        entry.method = ZipEntry.STORED
                        entry.size = 0
                        entry.compressedSize = 0
                        entry.crc = 0
                        zos.putNextEntry(entry)
                        zos.closeEntry()
                        entries++
                        continue
                    }
                    val store = ArchiveRules.shouldStore(item.name)
                    if (store) {
                        // STORED 必须先知道 crc 与大小：第一遍只读不写。
                        val crc = CRC32()
                        var size = 0L
                        item.file.inputStream().use { input ->
                            val buf = ByteArray(BUF)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                crc.update(buf, 0, n)
                                size += n
                            }
                        }
                        entry.method = ZipEntry.STORED
                        entry.size = size
                        entry.compressedSize = size
                        entry.crc = crc.value
                    } else {
                        entry.method = ZipEntry.DEFLATED
                    }
                    zos.putNextEntry(entry)
                    // 第二遍（DEFLATED 只有这一遍）：流式拷，不驻留内存。
                    item.file.inputStream().use { input ->
                        val buf = ByteArray(BUF)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            zos.write(buf, 0, n)
                            bytes += n
                        }
                    }
                    zos.closeEntry()
                    entries++
                    if (store) stored++ else deflated++
                }
            }
        } catch (e: IOException) {
            runCatching { dest.delete() }
            return PackResult.Failed("写 zip 失败（${e.javaClass.simpleName}: ${e.message ?: "无细节"}）；已删掉半个包")
        }
        return PackResult.Ok(entries, bytes, stored, deflated)
    }

    private class Planned(val name: String, val file: File)

    private fun collectDir(dir: File, entryName: String, out: MutableList<Planned>, seen: MutableSet<String>) {
        val prefix = if (entryName.endsWith("/")) entryName else "$entryName/"
        val children = dir.listFiles() ?: return
        // 空目录也要留一个条目，否则解开后目录消失。
        if (children.isEmpty()) {
            val name = prefix
            if (seen.add(name)) out.add(Planned(name, dir))
            return
        }
        for (child in children.sortedBy { it.name }) {
            // 符号链接一律跳过：跟进去可能成环（无限递归），也可能把工作区外的内容打进来。
            if (isSymlink(child)) continue
            if (child.isDirectory) {
                collectDir(child, prefix + child.name, out, seen)
            } else {
                addFile(child, prefix + child.name, out, seen)
            }
        }
    }

    private fun addFile(file: File, entryName: String, out: MutableList<Planned>, seen: MutableSet<String>) {
        if (isSymlink(file)) return
        val name = entryName.replace('\\', '/')
        // 同名条目会让部分解包器只认最后一条（其余静默丢失），宁可拒绝。
        if (!seen.add(name)) {
            throw IllegalArgumentException("打包清单里有重名条目：$name")
        }
        out.add(Planned(name, file))
    }

    private fun isSymlink(f: File): Boolean = runCatching {
        java.nio.file.Files.isSymbolicLink(f.toPath())
    }.getOrDefault(false)
}
