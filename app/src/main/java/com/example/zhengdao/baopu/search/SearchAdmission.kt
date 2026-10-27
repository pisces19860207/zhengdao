// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十七批（派工单 §1④）：**写死**的排除清单与准入判据。
//
// ## 为什么这张清单不许有开关（派工单 §1④ 原话「用户不能取消」）
// 排除清单不是「偏好」，是读侧的下限：`Android/data` 是别家 App 的私有目录，`.ssh/` 里是私钥，
// `rootfs/` 是运行时镜像（真机实测 349 MB，设计 §12.1）。把它们做成可关的选项，
// 等于把「模型/程序会不会读到这些」变成一次误点的后果。所以本文件里**没有**任何
// prefs／参数能往回加——想放宽只能改代码、过门禁、过验收。
//
// ## 三条口径决定本文件的形状
// 1. **越界判定复用 `rootfs/PathGuard.isInside`**（:37），不自己写字符串比较。
//    设计 §11.10 的负例「另造第二套路径」说的就是这种地方：写侧的越界判据只有一把，
//    读侧另造一把，两把就会在 `..`、软链、`/sdcard` 绑定挂载上给出不同答案。
//    `PathGuard.kt` 本体一行没改（派工单「不许碰」点名它），这里是**只读引用**。
// 2. **按组件比较，不按前缀**：`isInside` 内部走 `java.nio.file.Path.startsWith`（组件级），
//    所以 `/storage/emulated/0-evil` 不会被当成在 `/storage/emulated/0` 里。
// 3. **名字类规则（`.` 开头、密钥名、junk 名）在本文件里自己实现**：它们不是路径判据，
//    与 `LibraryPaths.isJunkName`（:275，private）是「同一类谨慎的两个消费者」。
//    那条 private 我不能改可见性（改它要动 `LibraryPaths.kt`，本批不碰），
//    于是 `SearchAdmissionTest.垃圾名三条与LibraryPaths同源_一边改了另一边不改就红`
//    用**源码文本**把两边钉在一起：
//    LibraryPaths 那三条字面量一旦改动，本文件不同步就红——这才是「一份真相」的可执行版本。
//
// ## 判序（先排后判范围）
// 命中排除清单 ⇒ 连「不索引」都不记（不建 `doc` 行），只在 `watermark.files_skipped` 计数；
// 不在授予根内 ⇒ 同样不建行。两者都给得出原因，因为验收要逐条对账（派工单 §2）。
package com.example.zhengdao.baopu.search

import com.example.zhengdao.core.PathGuard
import java.io.File
import java.nio.file.Paths

/** 命中了哪一条排除（`tag` 是短标签，进日志与 `watermark` 对账用，不含路径 ⇒ 不泄内容）。 */
internal enum class Exclusion(val tag: String) {
    /** `Android/data` —— 别家 App 的私有目录，永远不读。 */
    ANDROID_DATA("android-data"),

    /** `Android/obb` —— 别家 App 的资源包。 */
    ANDROID_OBB("android-obb"),

    /** `Android/` 的其它系统层子目录。 */
    ANDROID_ANY("android-any"),

    /** 任何以 `.` 开头的目录或文件（`.ssh/`、`.aws/`、`.baopu-spill/`、`.归属.jsonl`…）。 */
    DOT("dot"),

    /** `backup/`（派工单 §1④ 点名）。 */
    BACKUP("backup"),

    /** 终端与截图那五个目录（设计 §11.9：清理/搜索/迁移三处都点名排除）。 */
    TERMINAL("terminal"),

    /** 密钥类文件名或扩展名（`*.key`/`*.keystore`/`*.jks`/`id_rsa*`/`.netrc`/`*.p12`）。 */
    SECRET("secret"),

    /** 系统垃圾名（与 `LibraryPaths.isJunkName` 同源那三条）。 */
    JUNK_NAME("junk"),
}

/** 一个候选文件对搜索的处置。 */
internal enum class Call {
    /** 在授予根内、且没命中排除清单 ⇒ 可以进扫描器（能不能认领由扩展名决定，S3 的活）。 */
    IN_SCOPE,

    /** 命中排除清单 ⇒ **不建 doc 行**，只计数。 */
    EXCLUDED,

    /** 不在授予根内 ⇒ **不建 doc 行**（派工单 §2 点名的「甚至不建 doc 行」）。 */
    OUT_OF_SCOPE,
}

/**
 * 一篇文件在索引里的状态（字面量照设计 §6.2 ＋ §6.3 补的 `UNCLAIMED`）。
 *
 * 与 [Exclusion] 是两件不同的事：状态是「**进来了**但读不了/不值得读」，
 * 排除是「**进不来**」。所以被排除的文件没有状态，只有计数。
 */
internal enum class DocStatus(val tag: String) {
    OK("OK"),
    TOO_BIG("TOO_BIG"),
    ENCRYPTED("ENCRYPTED"),
    UNCLAIMED("UNCLAIMED"),

    /** 设计 §6.2 字面量。本批**不产出**它：垃圾名走 [Exclusion.JUNK_NAME]（不建行）。 */
    JUNK("JUNK"),

    /** 设计 §6.2 字面量。本批**不产出**它：目录被排走 [Exclusion]（不建行）。 */
    PRUNED("PRUNED"),
    ;

    companion object {
        fun fromTag(tag: String): DocStatus? = values().firstOrNull { it.tag == tag }
    }
}

/** 待入库的一篇（`title`/`sha256_head` 由 S3 的读取件填，这里不替它编）。 */
internal data class DocDraft(
    val rootId: Long,
    val relPath: String,
    val kind: String,
    val size: Long,
    val mtime: Long,
    val status: DocStatus,
    val indexedAt: Long,
)

/** 一次入库计划的对账读数（「截断与不认领必须可见」——设计 §6.2 原话）。 */
internal data class DocPlan(
    val rows: List<DocDraft>,
    val seen: Int,
    val skipped: Int,
    /** 各条排除命中了几次（`tag → 次数`）；报告与设置页都拿它说话。 */
    val skipTags: Map<String, Int>,
)

/** 一个文件的探针读数（S3 扫描器负责真去 stat，本批只做纯算式）。 */
internal data class Probe(
    val file: File,
    val kind: String = "",
    val size: Long = 0L,
    val mtime: Long = 0L,
    val status: DocStatus = DocStatus.OK,
)

/**
 * 排除清单与准入判据（**纯函数**，不碰 Android API、不读盘）。
 *
 * 落点在扫描器入口（设计 §3.2「写死在只读扫描器里」）。S3 接的时候只能在
 * [call]／[planDocRows] 这两扇门前排队，不许绕过——绕过的话本文件那两条门禁就白钉了。
 */
internal object SearchAdmission {

    /** 设计 §11.9 点名排除的终端与截图目录（**两名都排**：真机实测它现在还叫 `screenshots/`）。 */
    private val TERMINAL_DIRS: Set<String> = setOf("logs", "cache", "agents", "rootfs", "截屏", "screenshots")

    /** 派工单 §1④ 点名的备份目录名。 */
    private val BACKUP_DIRS: Set<String> = setOf("backup")

    /** 密钥类扩展名（小写比较）。 */
    private val SECRET_EXTS: Set<String> = setOf("key", "keystore", "jks", "p12")

    /** 密钥类文件名前缀（`id_rsa`、`id_ed25519` 这类成对私钥的写法）。 */
    private val SECRET_PREFIXES: Set<String> = setOf("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519")

    /** 精确匹配的密钥文件名。 */
    private val SECRET_NAMES: Set<String> = setOf(".netrc", "netrc", ".git-credentials", "credentials.json")

    /**
     * 系统垃圾名（与 `LibraryPaths.isJunkName`（:275）同源那三条，逐字对齐）。
     *
     * 门禁 `SearchAdmissionTest.垃圾名三条与LibraryPaths同源_一边改了另一边不改就红`
     * 读那两个文件的源码比对，一边改了另一边不改就红。
     */
    private val JUNK_NAMES: Set<String> = setOf(".DS_Store", "Thumbs.db")
    private const val JUNK_NAME_PREFIX = "._"

    /** 命中了哪条排除（多条命中时按本文件声明顺序给第一条——原因只给人看，不参与判据）。 */
    fun exclusion(candidate: File): Exclusion? {
        val segs = segments(candidate)
        val name = candidate.name
        if (JUNK_NAMES.contains(name) || name.startsWith(JUNK_NAME_PREFIX)) return Exclusion.JUNK_NAME
        if (isSecretName(name)) return Exclusion.SECRET
        // 任何一层组件以 . 开头（含文件自己）⇒ 点开头的目录或文件。
        if (segs.any { it.startsWith(".") }) return Exclusion.DOT
        if (segs.any { BACKUP_DIRS.contains(it.lowercase()) }) return Exclusion.BACKUP
        if (segs.any { TERMINAL_DIRS.contains(it.lowercase()) }) return Exclusion.TERMINAL
        segs.forEachIndexed { i, s ->
            // 大小写不比：真机上它是系统建出来的 `Android`，但排除清单的失效方向只能是「少搜」，
            // 不该是「ANDROID/」这种变体绕过去（与 backup／terminal 那两条的小写比同口径）。
            if (s.lowercase() == "android") {
                val next = segs.getOrNull(i + 1)?.lowercase()
                return when {
                    next == "data" -> Exclusion.ANDROID_DATA
                    next == "obb" -> Exclusion.ANDROID_OBB
                    else -> Exclusion.ANDROID_ANY
                }
            }
        }
        return null
    }

    /** 密钥名判定（纯函数，可单测）：扩展名、成对私钥前缀、明写的凭据文件名。 */
    fun isSecretName(name: String): Boolean {
        val lower = name.lowercase()
        if (SECRET_NAMES.contains(lower)) return true
        if (SECRET_PREFIXES.any { lower.startsWith(it) }) return true
        val ext = lower.substringAfterLast('.', "")
        return ext.isNotEmpty() && ext != lower && SECRET_EXTS.contains(ext)
    }

    /** 处置：先判排除，再判在不在授予根内（判序的理由见文件头）。 */
    fun call(root: File, candidate: File): Call {
        if (exclusion(candidate) != null) return Call.EXCLUDED
        // 越界判据只有一把：写侧用的那把（rootfs/PathGuard.isInside，只读引用、没改它一行）。
        return if (PathGuard.isInside(root, candidate)) Call.IN_SCOPE else Call.OUT_OF_SCOPE
    }

    /**
     * 相对路径（`doc.rel_path` 的写法）：**先判在不在根内**，不在就返回 null。
     *
     * 不用 `Path.relativize` 直接算——它对根外路径会给出 `../../…`，那种串进了库
     * 就是一份「看起来在根内其实不是」的假账。
     */
    fun relativePath(root: File, candidate: File): String? {
        if (!PathGuard.isInside(root, candidate)) return null
        val r = normalized(root)
        val t = normalized(candidate)
        return runCatching { if (t == r) "" else r.relativize(t).joinToString("/") }.getOrNull()
    }

    /** [DocPlan.skipTags] 里两个不属于排除清单的计数名。 */
    const val TAG_OUT_OF_SCOPE = "out-of-scope"
    const val TAG_ROOT_SELF = "root-is-itself"

    /**
     * 入库计划（**纯函数**）：被排除的、不在根内的 ⇒ **一行都不建**（派工单 §2 点名的判据），
     * 只在 [DocPlan.skipped] 与 [DocPlan.skipTags] 上留数，让「跳过了什么、跳过多少」可见。
     */
    fun planDocRows(rootId: Long, root: File, probes: List<Probe>, now: Long): DocPlan {
        val rows = mutableListOf<DocDraft>()
        val tags = mutableMapOf<String, Int>()
        var skipped = 0
        fun skip(tag: String) {
            skipped++
            tags[tag] = (tags[tag] ?: 0) + 1
        }
        probes.forEach { p ->
            // 一律走 [call] 这一扇门（判据一处出口）：入库计划不许长出第二条「看在不在范围内」的算法。
            when (call(root, p.file)) {
                Call.IN_SCOPE -> {
                    val rel = relativePath(root, p.file)
                    when {
                        rel == null -> skip(TAG_OUT_OF_SCOPE) // call 说在、relativize 说不在 ⇒ 判据断了，按最严的处理
                        rel.isEmpty() -> skip(TAG_ROOT_SELF)
                        else -> rows += DocDraft(rootId, rel, p.kind, p.size, p.mtime, p.status, now)
                    }
                }
                Call.EXCLUDED -> skip(exclusion(p.file)?.tag ?: "excluded")
                Call.OUT_OF_SCOPE -> skip(TAG_OUT_OF_SCOPE)
            }
        }
        return DocPlan(rows.toList(), probes.size, skipped, tags.toMap())
    }

    /** 绝对路径的组件表（排除清单按组件比，不按字符串前缀比——理由见文件头第 2 条）。 */
    private fun segments(candidate: File): List<String> =
        normalized(candidate).map { it.toString() }.filter { it.isNotEmpty() }

    /** 词法归一（`..`/`.` 折掉）；拿不到就退回绝对路径——**宁可多判一层，不许漏判**。 */
    private fun normalized(f: File): java.nio.file.Path = runCatching {
        f.absoluteFile.toPath().normalize()
    }.getOrDefault(Paths.get(f.absolutePath))
}
