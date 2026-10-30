// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第二百六十九批派工单 §2「解包：zip 走 Java；tar/tgz/txz/zst 走 Rust nativeExtract」。
//
// 分流只按**扩展名**，不按魔数猜：判据⑤要求「rar/7z 明确拒，不假装支持」，
// 而「先试着当 zip 解一下」正是负例③（rar 当 zip 解）的成因——`ZipInputStream`
// 对一串不是 zip 的字节不抛异常，它会安静地读出 0 个条目，最后回一个「解压成功 0 个文件」。
package com.example.zhengdao.baopu.archive

import java.io.File

internal object ArchiveExtractor {

    /**
     * 解一个归档到 [target]。
     *
     * @param maxBytes 总量上限（默认 2 GiB；单测注入小值走同一条判定）
     * @param native Rust 解包调用（null＝生产用 [com.example.zhengdao.rust.CoreNative]）
     */
    fun extract(
        archive: File,
        target: File,
        maxBytes: Long = ArchiveRules.MAX_TOTAL_BYTES,
        native: NativeExtractCall? = null,
    ): ExtractOutcome {
        if (!archive.exists()) return ExtractOutcome.Failed("找不到这个文件：${archive.name}")
        if (!archive.isFile) return ExtractOutcome.Failed("不是一个文件：${archive.name}")
        if (target.exists() && !target.isDirectory) {
            return ExtractOutcome.Failed("目标不是目录：${target.name}（解压目标要是一个文件夹）")
        }

        val route = ArchiveRules.route(archive.name)
        if (!route.supported) return ExtractOutcome.Rejected(route.reason ?: "不支持的归档格式")

        return when (route.kind) {
            ArchiveKind.ZIP -> ZipExtractor.extract(archive, target, maxBytes)
            ArchiveKind.TAR, ArchiveKind.TAR_GZ, ArchiveKind.TAR_ZST, ArchiveKind.TAR_XZ ->
                NativeExtractor.extract(archive, target, route.kind, maxBytes, native)

            ArchiveKind.UNSUPPORTED -> ExtractOutcome.Rejected(route.reason ?: "不支持的归档格式")
        }
    }
}
