// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.core.ZhengdaoDirs
import java.io.File

/**
 * 小说资产工具的共享内核（609）：目录约定、范围解析、带行号的加载、文本级追加。
 *
 * 目录约定（派工单只给了关系一条，其余按同一房型补齐，写进各工具 description）：
 * - 关系：`小说/<书名>/关系/` 或 `资料库/关系/` 下的 `*.rjson5`
 * - 时间线：`…/时间线/` 下的 `*.tjson5`；批注：`…/批注/` 下的 `*.ajson5`；
 *   角色：`…/角色/` 下的 `*.md` 与 `*.json5`
 *
 * 纯函数、零 Android，JVM 直测。
 */
internal object NovelAssets {

    const val RELATIONS_DIR = "关系"
    const val TIMELINE_DIR = "时间线"
    const val ANNOTATIONS_DIR = "批注"
    const val ROSTER_DIR = "角色"
    const val RELATIONS_EXT = ".rjson5"
    const val TIMELINE_EXT = ".tjson5"
    const val ANNOTATIONS_EXT = ".ajson5"

    /** 加载并解析一个资产文件：坏 JSON5 ⇒ [Bad] 带「文件名 第 N 行：…」（不猜、不静默空）。 */
    sealed class Loaded {
        class Ok(val file: File, val text: String, val root: NovelJson5.J5Node) : Loaded()
        class Bad(val message: String) : Loaded()
    }

    fun load(file: File): Loaded {
        val text = runCatching { file.readText() }.getOrElse {
            return Loaded.Bad("读不了 ${file.name}（权限或 IO 错误）")
        }
        val r = NovelJson5.parse(text)
        if (r.error != null) return Loaded.Bad("${file.name} ${r.error.text}")
        return Loaded.Ok(file, text, r.node!!)
    }

    /** 范围解析结果：一组文件（按文件名排序）或可读拒绝。[Bad.denied]＝围栏拒绝（调用方须回 Rejected 记账）。 */
    sealed class Scope {
        class Files(val files: List<File>) : Scope()
        class Bad(val message: String, val denied: Boolean = false) : Scope()
    }

    /**
     * path 给了 ⇒ 过围栏后按「文件=它自己 / 目录=直接子文件（不递归）」收；
     * path 省略 ⇒ 各小说项目的 [subDir] ＋ `资料库/<subDir>` 全收。
     * 一个文件都没有 ⇒ [Scope.Bad] 说清约定位置（空结果也要说人话，不静默）。
     */
    fun scope(root: File, path: String, subDir: String, vararg exts: String): Scope {
        val p = path.trim()
        if (p.isNotEmpty()) {
            val v = NovelFence.resolve(root, p, forWrite = false)
            if (v is NovelFence.Verdict.Denied) return Scope.Bad(v.reason, denied = true)
            val f = (v as NovelFence.Verdict.Ok).file
            return when {
                f.isFile -> Scope.Files(listOf(f))
                f.isDirectory -> Scope.Files(filesIn(f, exts))
                else -> Scope.Bad("路径不存在：$p")
            }
        }
        val dirs = NovelProjectStore.listProjects(root).map { File(it, subDir) } +
            File(File(root, ZhengdaoDirs.LEGACY_LIBRARY_DIR), subDir)
        val files = dirs.flatMap { filesIn(it, exts) }
        if (files.isEmpty()) {
            return Scope.Bad(
                "没找到 ${exts.joinToString("/")} 文件（约定位置：$NOVEL_ROOT_DIR/<书名>/$subDir/ " +
                    "或 ${ZhengdaoDirs.LEGACY_LIBRARY_DIR}/$subDir/；也可用 path 显式指定文件或目录）",
            )
        }
        return Scope.Files(files)
    }

    private fun filesIn(dir: File, exts: Array<out String>): List<File> =
        dir.listFiles()
            ?.filter { f -> f.isFile && exts.any { f.name.endsWith(it, ignoreCase = true) } }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** 根是数组 ⇒ 它自己；根是对象 ⇒ 取 [key] 键的数组；都不是 ⇒ null（调用方报行号）。 */
    fun rootArray(root: NovelJson5.J5Node, key: String): NovelJson5.J5Arr? =
        NovelJson5.arr(root) ?: NovelJson5.arr(NovelJson5.obj(root)?.get(key))

    /**
     * 文本级追加（ANH 无损口径）：新元素插在最后一个元素之后（空数组插在 `[` 之后），
     * **已有字节一个不动**——缩进/注释/尾逗号/字段序全保留，对拍判据由此成立。
     */
    fun appendToArray(text: String, arr: NovelJson5.J5Arr, elem: String): String {
        val last = arr.items.lastOrNull()
        return if (last == null) {
            text.substring(0, arr.start + 1) + "\n  " + elem + "\n" + text.substring(arr.start + 1)
        } else {
            text.substring(0, last.end) + ",\n  " + elem + text.substring(last.end)
        }
    }

    /** 给对象文本级补一个键（插在 `}` 前；空对象不带前导逗号）。已有字节不动。 */
    fun insertKey(text: String, obj: NovelJson5.J5Obj, key: String, valueJson: String): String {
        val pos = obj.end - 1
        val piece = if (obj.entries.isEmpty()) "\"$key\":$valueJson" else ",\"$key\":$valueJson"
        return text.substring(0, pos) + piece + text.substring(pos)
    }

    /** 原子写（先 .tmp 再 rename；口径同 NovelProjectStore.writeMeta——半截文件比不写更坏）。 */
    fun atomicWrite(file: File, text: String): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return runCatching {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }
}