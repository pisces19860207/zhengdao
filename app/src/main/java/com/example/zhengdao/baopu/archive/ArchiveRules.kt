// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第二百六十九批派工单 §1／§2／§3——打包口径（只做 zip、零依赖）、解包分流
// （zip 走 Java、tar 家族走 Rust）、三条安全线（zip-slip／≤2GB／默认不覆盖）。
// 本文件只放**判据**（纯函数，无 IO、无 Android 依赖，JVM 直测），落盘在 Packer/Extractor。
package com.example.zhengdao.baopu.archive

import java.io.File

/** 归档种类：按扩展名分流，**不猜内容**（判据⑤要求 rar/7z 明确拒，不许「试一下当 zip 解」）。 */
internal enum class ArchiveKind {
    ZIP,
    TAR,
    TAR_GZ,
    TAR_ZST,
    TAR_XZ,
    UNSUPPORTED,
}

/** 分流结果：认得＝kind；不认得＝一句人话（直接回灌给模型/用户）。 */
internal data class ArchiveRoute(val kind: ArchiveKind, val reason: String? = null) {
    val supported: Boolean get() = kind != ArchiveKind.UNSUPPORTED
}

/**
 * 三条安全线的数字与判据。
 *
 * 「2GB」的口径：**2 GiB = 2147483648 字节**（`2L * 1024 * 1024 * 1024`）。写死在这里，
 * 文案里同时给出字节数——用户拿 2,000,000,000 字节的包来验时能一眼看出差在哪。
 */
internal object ArchiveRules {

    /** 解压总量上限（声明值与实测值都按它判；两处都判才是「不许靠包头撒谎」）。 */
    const val MAX_TOTAL_BYTES: Long = 2L * 1024 * 1024 * 1024

    /** 人话版本（错误文案统一用它，避免各处写 2.0／2048MB 不一致）。 */
    const val MAX_TOTAL_HUMAN: String = "2 GB（2147483648 字节）"

    /**
     * 打包时**不再压**的扩展名（已压缩格式再 deflate 一遍只烧 CPU，体积几乎不变）。
     *
     * 口径来自派工单 §1：jpg/mp4/zip 三种点名；顺带带上同族的 jpeg（jpg 的长写）。
     * 刻意**不**加 png/gif/mp3：它们也有压缩，但派工单只点了三种，多扩一类就多一处要解释的口径。
     */
    val STORED_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "mp4", "zip")

    /** 单条目名字长度上限（tar 的 name 字段 100 字节、pax 可更长；这里按「人能看的路径」封顶）。 */
    const val MAX_NAME_BYTES: Int = 4096

    /** 这个扩展名要不要走 STORED（不压）。 */
    fun shouldStore(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return name.substring(dot + 1).lowercase() in STORED_EXTENSIONS
    }

    /**
     * 按扩展名分流（大小写不敏感；复合扩展名先匹配长的）。
     *
     * rar / 7z **单独给话**：它们是「明确不支持」，不是「认不出」——用户最容易在这里
     * 期待「你试试看」，所以文案要堵死这条路（判据⑤、负例③）。
     */
    fun route(fileName: String): ArchiveRoute {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".zip") -> ArchiveRoute(ArchiveKind.ZIP)
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> ArchiveRoute(ArchiveKind.TAR_GZ)
            lower.endsWith(".tar.zst") || lower.endsWith(".tzst") || lower.endsWith(".zst") ->
                ArchiveRoute(ArchiveKind.TAR_ZST)

            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> ArchiveRoute(ArchiveKind.TAR_XZ)
            lower.endsWith(".tar") -> ArchiveRoute(ArchiveKind.TAR)
            lower.endsWith(".rar") -> ArchiveRoute(
                ArchiveKind.UNSUPPORTED,
                "不支持 .rar：本项目只有 zip（Java 自带）与 tar 家族（Rust）两条解包链路，" +
                    "没有 RAR 解码器。请先用电脑上的工具转成 .zip 再放进来——不会拿它当 zip 硬解。",
            )

            lower.endsWith(".7z") -> ArchiveRoute(
                ArchiveKind.UNSUPPORTED,
                "不支持 .7z：没有 7-Zip 解码器（零新增依赖）。请先转成 .zip 再放进来——不会拿它当 zip 硬解。",
            )

            lower.endsWith(".gz") || lower.endsWith(".xz") || lower.endsWith(".bz2") || lower.endsWith(".z") ->
                ArchiveRoute(
                    ArchiveKind.UNSUPPORTED,
                    "只支持「tar + 压缩」的整包（.tar.gz/.tgz/.tar.zst/.tar.xz），" +
                        "单文件压缩壳（.gz/.xz/.bz2）不是归档、没有成员清单，解出来只有一个无名文件。请先打成 tar。",
                )

            else -> ArchiveRoute(
                ArchiveKind.UNSUPPORTED,
                "认不出这个归档格式（只支持 .zip 与 .tar/.tar.gz/.tgz/.tar.zst/.tar.xz）。",
            )
        }
    }

    /** 名字判定的三种结局。 */
    internal enum class NameVerdict {
        OK,

        /** 绝对路径（`/x`、`C:\x`、`\\server\x`）。 */
        ABSOLUTE,

        /** 含 `..` 段（zip-slip 本体）。 */
        PARENT,

        /** 空名/纯空白。 */
        EMPTY,

        /** 名字过长。 */
        TOO_LONG,
    }

    /**
     * 判一个归档成员名能不能落盘（**纯字符串判定**，不看文件系统）。
     *
     * 反斜杠按分隔符处理：zip 规范用 `/`，但 Windows 造的包会写 `\`，而落盘时 Windows
     * 会把它当分隔符——只按 `/` 判就等于给 `..\..\x` 开了后门。
     */
    fun checkName(rawName: String): NameVerdict {
        val name = rawName.replace('\\', '/')
        if (name.isBlank() || name == "/") return NameVerdict.EMPTY
        if (name.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) return NameVerdict.TOO_LONG
        // 绝对路径：POSIX 根、Windows 盘符、UNC。
        if (name.startsWith("/")) return NameVerdict.ABSOLUTE
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) return NameVerdict.ABSOLUTE
        // 逐段判：任何一段是 `..` 就拒（`a/../b` 也拒——规范化后虽然还在里面，但它是
        // 「有人在试探」的信号，且各平台规范化规则不一致，不如一律拒）。
        for (segment in name.split('/')) {
            if (segment == "..") return NameVerdict.PARENT
        }
        return NameVerdict.OK
    }

    /** 把成员名落到 [root] 下，并做**二次**边界校验（规范化后再比前缀，防符号链接/大小写等意外）。 */
    fun resolveInside(root: File, memberName: String): File? {
        val name = memberName.replace('\\', '/').trimStart('/')
        val candidate = File(root, name)
        val rootPath = root.canonicalFile.path + File.separator
        val target = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return if (target.path == root.canonicalFile.path || target.path.startsWith(rootPath)) target else null
    }

    /** 拒绝文案（判据②要「可读原因」，所以逐字说明是哪种穿越）。 */
    fun rejectReason(memberName: String, verdict: NameVerdict): String = when (verdict) {
        NameVerdict.PARENT ->
            "拒绝解压：成员「$memberName」含上级目录（..），会跳出目标目录（zip-slip），整包不落盘。"

        NameVerdict.ABSOLUTE ->
            "拒绝解压：成员「$memberName」是绝对路径（从盘符或根开始），会写到目标目录之外，整包不落盘。"

        NameVerdict.TOO_LONG ->
            "拒绝解压：成员名超过 ${MAX_NAME_BYTES} 字节（「${memberName.take(80)}…」），拒绝落盘。"

        NameVerdict.EMPTY ->
            "拒绝解压：包里有空名字成员，无法判定落点，整包不落盘。"

        NameVerdict.OK -> ""
    }
}
