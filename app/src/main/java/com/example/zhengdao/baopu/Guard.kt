// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口与仓库内既有实现（均只读调用，不改动）：
//   - rootfs/PathGuard.kt 的 isInside（按路径组件比较，已填"同前缀兄弟目录"的坑）；
//   - terminal/Workspace.kt 的 hostDir（工作区唯一真相源）；
//   - terminal/KnowledgeBase.kt 的目录骨架（资料库/原始、资料库/改版）与既有纪律
//     （原始/ 只读、只增不减、同名不覆盖、软链不跟随不复制）。
package com.example.zhengdao.baopu

import com.example.zhengdao.rootfs.PathGuard
import java.io.File
import java.nio.file.Files

/**
 * 抱朴守卫的**纯判据内核**（无 Android、无 Context，可 JVM 单测）。
 *
 * 三道口全在这里：
 * - **路径守卫**（甲之前的第一道）：一切文件操作的落点必须在当前工作区内；
 * - **开工闸门**（甲）：`资料库/原始/` 里没有素材 ⇒ 拒绝开工；
 * - **归档闸门**（乙）：写 `改版/` 或任何产物之前，原件必须先躺进 `原始/`。
 *
 * 拒绝**绝不静默**：每个拒绝都带人话理由（调用方记 run_log / 回给模型），
 * 不允许"悄悄跳过"——那是本项目最反感的事故形态。
 */
internal object BaopuGuardCore {

    /**
     * 单次扫描文件数上限（与 KnowledgeBase.MAX_SCAN 对齐）。
     * 达到上限时 [MaterialVerdict.Ready.truncated] 置位——**截断必须告知**，
     * 静默截断会让模型以为"资料全在这儿了"。
     */
    const val MAX_SCAN = 2000

    // ── 路径守卫 ──────────────────────────────────────────────────────────

    sealed interface PathVerdict {
        /** 在工作区内（file 为归一后的目标）。 */
        data class Inside(val file: File) : PathVerdict

        /** 越界（带上根与目标，供诊断）。 */
        data class Outside(val root: File, val target: File) : PathVerdict
    }

    /**
     * [target] 是否落在 [root]（当前工作区）内。
     *
     * 判据复用 [PathGuard.isInside]：按**路径组件**比较（不是字符串前缀），
     * `rootfs-evil` 这类"同前缀兄弟目录"过不来；canonicalize 能解析的软链也过不来。
     */
    fun checkPath(root: File, target: File): PathVerdict =
        if (PathGuard.isInside(root, target)) {
            PathVerdict.Inside(target.absoluteFile.normalize())
        } else {
            PathVerdict.Outside(root, target)
        }

    // ── 开工闸门（甲） ────────────────────────────────────────────────────

    sealed interface MaterialVerdict {
        /** 有素材（count = 文件数；truncated = 是否触达扫描上限）。 */
        data class Ready(val count: Int, val truncated: Boolean) : MaterialVerdict

        /** 没素材（reason 是人话，直接可回给用户/模型）。 */
        data class Empty(val reason: String) : MaterialVerdict
    }

    /**
     * `原始/` 里有没有素材。
     *
     * 计数纪律与 KnowledgeBase 扫描一致：递归、**跳过符号链接**（不跟随、不计数）、
     * 触达 [MAX_SCAN] 上限置 truncated（不静默）。
     */
    fun checkMaterial(rawDir: File, maxScan: Int = MAX_SCAN): MaterialVerdict {
        if (!rawDir.isDirectory) {
            return MaterialVerdict.Empty("资料库的「原始」目录不存在（先建好资料库再开工）")
        }
        var count = 0
        var sawDir = false
        val stack = ArrayDeque<File>()
        stack.addLast(rawDir)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            for (f in dir.listFiles() ?: continue) {
                if (isSymlink(f)) continue
                if (f.isDirectory) {
                    sawDir = true
                    stack.addLast(f)
                } else if (f.isFile) {
                    count++
                    if (count >= maxScan) {
                        return MaterialVerdict.Ready(count, truncated = true)
                    }
                }
            }
        }
        return when {
            count > 0 -> MaterialVerdict.Ready(count, truncated = false)
            sawDir -> MaterialVerdict.Empty("「原始」里只有子目录、没有文件（把资料文件放进来再开工）")
            else -> MaterialVerdict.Empty("「原始」里还没有文件（把资料放进去再开工）")
        }
    }

    // ── 归档闸门（乙） ────────────────────────────────────────────────────

    sealed interface ArchiveVerdict {
        /**
         * 放行。[needsCopy] = true 表示"该复制"，只有 [ensureArchived] 会真复制；
         * 复制完成后它自己返回 needsCopy = false 的放行。[note] 说明放行原因。
         */
        data class Archived(val needsCopy: Boolean, val note: String) : ArchiveVerdict

        /** 拒绝（reason 是人话）。 */
        data class Refused(val reason: String) : ArchiveVerdict
    }

    /**
     * 归档名合法性：相对 `原始/` 的路径，不许绝对、不许 `..`、不许空。
     * （调用方传来的名字可能来自模型输出，这里是第一道校验。）
     */
    fun isLegalRelName(relName: String): Boolean {
        if (relName.isBlank()) return false
        if (relName.startsWith("/") || relName.startsWith("\\")) return false
        if (relName.contains("..")) return false
        return true
    }

    /**
     * **只判定、不复制**的归档判据（纯函数，单测钉的就是这张表）：
     * - 原件就在 `原始/` 里且内容相同 ⇒ 放行，无需重复归档；
     * - 原件在 `原始/` 里但内容不同 ⇒ 放行，**不覆盖**（原始/ 铁律：只增不减）；
     * - 原件在别处、`原始/` 没有同名 ⇒ 放行但 [ArchiveVerdict.Archived.needsCopy]；
     * - 原件在别处、`原始/` 已有同名（无论内容）⇒ 放行，不覆盖；
     * - 原件不存在 ⇒ 放行（这是新建内容，没有原件要保）。
     */
    fun checkArchive(rawDir: File, source: File, relName: String): ArchiveVerdict {
        if (!isLegalRelName(relName)) {
            return ArchiveVerdict.Refused("归档名非法（只接受相对「原始」的路径，不许绝对路径或 ..）：$relName")
        }
        if (isSymlink(source)) {
            return ArchiveVerdict.Refused("原件是符号链接：不跟随、也不复制链接本身（与资料库复制的既有纪律一致）")
        }
        val dst = File(rawDir, relName)
        if (sameFile(source, dst)) {
            return ArchiveVerdict.Archived(false, "原件就在「原始」里，无需归档")
        }
        if (dst.exists()) {
            return ArchiveVerdict.Archived(
                false,
                if (contentSame(source, dst)) {
                    "「原始」里已有内容相同的原件，无需重复归档"
                } else {
                    "「原始」里已有同名文件（原件只增不减，不覆盖）"
                },
            )
        }
        if (!source.isFile) {
            return ArchiveVerdict.Archived(false, "没有可归档的原件（这是新建内容）")
        }
        return ArchiveVerdict.Archived(true, "需要归档：原件将被复制进「原始」")
    }

    /**
     * 判定 + 执行：判据表只有 [checkArchive] 一张；这里只在它说"该复制"时真复制。
     *
     * 复制用 `Files.copy` **不带 REPLACE_EXISTING**——目标突然存在就抛异常转拒绝，
     * 绝不会覆盖 `原始/` 里的任何既有文件。
     */
    fun ensureArchived(rawDir: File, source: File, relName: String): ArchiveVerdict {
        val verdict = checkArchive(rawDir, source, relName)
        if (verdict !is ArchiveVerdict.Archived || !verdict.needsCopy) {
            return verdict
        }
        val dst = File(rawDir, relName)
        return try {
            dst.parentFile?.mkdirs()
            Files.copy(source.toPath(), dst.toPath())
            ArchiveVerdict.Archived(false, "原件已归档到「原始」/$relName")
        } catch (e: Exception) {
            // 原文只进这里（调用方记 RunLog）；reason 给人话。
            ArchiveVerdict.Refused("归档失败：无法把原件复制进「原始」（${e.javaClass.simpleName}）")
        }
    }

    /** 两个 File 是否指向同一个文件（先比规范路径，省一次 IO；不同则 false）。 */
    fun sameFile(a: File, b: File): Boolean =
        a.absoluteFile.normalize().path == b.absoluteFile.normalize().path

    /**
     * 内容相同判定（分块字节比对，不吃内存）。
     * 任一侧不是普通文件（不存在 / 目录 / 软链）⇒ false——"拿不到就不放行"。
     */
    fun contentSame(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile) return false
        if (isSymlink(a) || isSymlink(b)) return false
        if (a.length() != b.length()) return false
        return runCatching {
            Files.newInputStream(a.toPath()).use { sa ->
                Files.newInputStream(b.toPath()).use { sb ->
                    val ba = ByteArray(CHUNK)
                    val bb = ByteArray(CHUNK)
                    var same = true
                    while (same) {
                        val na = sa.read(ba)
                        val nb = sb.read(bb)
                        when {
                            na != nb -> same = false
                            na < 0 -> break
                            else -> if (!ba.sliceArray(0 until na).contentEquals(bb.sliceArray(0 until nb))) {
                                same = false
                            }
                        }
                    }
                    same
                }
            }
        }.getOrDefault(false)
    }

    private const val CHUNK = 64 * 1024

    /** 软链判定（拿不到时保守判 false——调用方还有 isFile 等其他闸门）。 */
    fun isSymlink(f: File): Boolean =
        runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
}
