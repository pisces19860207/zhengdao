// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 路径边界判定复用 :core 的 PathGuard（canonicalize + 组件级比较，防同前缀兄弟目录）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.core.PathGuard
import com.example.zhengdao.core.ZhengdaoDirs
import java.io.File

/**
 * 小说资产工具的路径围栏（609 硬要求②）。
 *
 * 只许 `<工作区>/小说/` 与 `<工作区>/资料库/` 两棵子树；
 * `..`／绝对路径越界／symlink 逃逸一律拒（[PathGuard.isInside] 走 canonicalFile，
 * 已存在的软链会被解析后再判，指向外面的链在这里现形）。
 *
 * 拒 ⇒ 调用方回 [ToolOutcome.Rejected] ⇒ GuardPipeline.noteToolRejection 自动记
 * `工具失败审计.jsonl`——**拒并记账**走的是既有通道，本文件不另造机制。
 *
 * 写额外排除 `资料库/原始/`（只读原件区，口径同 DeletePathTool.RAW_GUARD_REL）。
 * 纯函数、零 Android，JVM 直测。
 */
internal object NovelFence {

    sealed class Verdict {
        class Ok(val file: File) : Verdict()
        class Denied(val reason: String) : Verdict()
    }

    fun resolve(root: File, rawPath: String, forWrite: Boolean): Verdict {
        val p = rawPath.trim()
        if (p.isEmpty()) return Verdict.Denied("路径不能为空")
        if (p.split('/', '\\').any { it == ".." }) {
            return Verdict.Denied("路径里不许有「..」（围栏：只许 $NOVEL_ROOT_DIR/ 与 ${ZhengdaoDirs.LEGACY_LIBRARY_DIR}/ 之内）：$p")
        }
        val candidate = if (File(p).isAbsolute) File(p) else File(root, p)
        val novelRoot = File(root, NOVEL_ROOT_DIR)
        val libRoot = File(root, ZhengdaoDirs.LEGACY_LIBRARY_DIR)
        if (!PathGuard.isInside(novelRoot, candidate) && !PathGuard.isInside(libRoot, candidate)) {
            return Verdict.Denied("路径越出围栏（只许 $NOVEL_ROOT_DIR/ 或 ${ZhengdaoDirs.LEGACY_LIBRARY_DIR}/ 之内）：$p")
        }
        if (forWrite && PathGuard.isInside(File(root, DeletePathTool.RAW_GUARD_REL), candidate)) {
            return Verdict.Denied("${DeletePathTool.RAW_GUARD_REL}/ 是只读原件区，不许写：$p")
        }
        val canonical = runCatching { candidate.canonicalFile }.getOrElse { candidate.absoluteFile }
        return Verdict.Ok(canonical)
    }
}