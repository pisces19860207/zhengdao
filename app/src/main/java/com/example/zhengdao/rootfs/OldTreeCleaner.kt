// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.rootfs

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors

/**
 * 旧环境树的**后台**清理（第六十五批 · 必做 #3）。
 *
 * 改前（真机基线，2026-10-20）：换树 0ms，但紧接着的 `rootfs.old.deleteRecursively()`
 * 在**前台同步**删了 **3839ms**（1.5–2GB、几万个小文件）——「修复完成」要等它删完才出现。
 * 本对象把那一步挪到独立后台线程：换树成功后先把 `rootfs.old` 改名成
 * `rootfs.old.<毫秒时间戳>`（同一目录内的原子改名，毫秒级），安装立刻返回，
 * 调用方的 `InstallFlow.finish` 随即提示完成；真正删除在后台进行，失败只记日志，
 * 留给下次启动的 [RootfsInstaller.cleanupPartial]（它会扫掉所有 `rootfs.old.<数字>`）。
 *
 * 为什么改名而不是直接后台删 `rootfs.old`：
 * - `rootfs.old` 这个名字在启动时**有恢复语义**（rootfs 缺失 ⇒ 它是用户唯一的环境）。
 *   若后台删除进行到一半进程被杀，留下半棵 `rootfs.old`，下次启动会把它当成完整环境恢复。
 *   改成带时间戳的垃圾名后，它在语义上永远是"垃圾"，启动时只删不恢复；
 *   真正的恢复语义只属于"换树中途死掉"留下的那个 `rootfs.old`。
 * - 改名后 `rootfs.old` 这个位置立即空出来，紧接着的下一次安装不会撞名。
 *
 * 线程纪律：单线程、**有名 + daemon + MIN_PRIORITY**（与仓库裸线程守卫一致）；
 * 不持有安装锁，安装完成后才派发，下一次安装不会等它。
 */
internal object OldTreeCleaner {

    /** 垃圾目录前缀：`rootfs.old.<毫秒>`。 */
    internal const val TRASH_PREFIX = "rootfs.old."

    /**
     * 测试注入点：非 null 时代替真实执行器（单测用它把任务扣在手里，证明安装先返回）。
     * 生产路径永远走 [executor]。
     */
    @Volatile
    internal var dispatch: ((Runnable) -> Unit)? = null

    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "zd-rootfs-clean").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    /** 把旧树改名成垃圾名；改不动就返回原名（后台照样会删，只是失去"永不恢复"的语义）。 */
    fun park(oldTree: File): File {
        if (!oldTree.exists()) return oldTree
        val base = System.currentTimeMillis()
        for (i in 0 until 1_000) {
            val trash = File(oldTree.parentFile, "$TRASH_PREFIX${base + i}")
            if (!trash.exists() && oldTree.renameTo(trash)) return trash
            if (!oldTree.exists()) return oldTree
        }
        return oldTree
    }

    /** 后台删除（不阻塞调用方）；删除耗时与结果各写一行日志。 */
    fun submitDelete(trash: File) {
        val task = Runnable {
            val at = System.nanoTime()
            val ok = deleteTree(trash)
            val ms = (System.nanoTime() - at) / 1_000_000L
            RunLog.log(
                "旧环境后台清理：" + (if (ok) "完成" else "未删净（不影响使用，下次启动会再试）") +
                    "，${ms}ms，${trash.name}"
            )
        }
        val injected = dispatch
        if (injected != null) {
            // 测试注入也包一层：注入点自己的异常同样不能影响安装
            runCatching { injected(task) }
                .onFailure { RunLog.log("旧树后台清理派发失败（测试注入）：${it.message}") }
            return
        }
        runCatching { executor.execute(task) }
            .onFailure { RunLog.log("旧树后台清理派发失败（不影响使用，下次启动会再试）：${it.message}") }
    }

    /**
     * 深删旧树，但**不跟随符号链接**（2026-10-20 真机证据）。
     *
     * 为什么不用 `File.deleteRecursively()`：它用 `File.isDirectory` 判定类型，会跟随符号链接。
     * rootfs 里的 `/dev/fd -> /proc/self/fd` 这类链接会把删除带进宿主 `/proc`，SELinux 拒绝
     * （logcat：`avc: denied { write } ... name="fd" ... proc`），函数返回 false——第一次真机
     * 改进版跑完，旧树其实已删净，日志却报「失败」；在策略宽松的设备上还可能误删应用目录
     * 之外的宿主文件。
     *
     * `Files.walkFileTree` 默认**不跟随**符号链接：链接本身按文件访问、只删链接。目录先补上
     * owner 写位（解包保留的只读目录也能删空），单条失败不中断其余条目，最后以**路径真的
     * 不存在**判成功，而不是信某个中间返回值。
     */
    internal fun deleteTree(root: File): Boolean {
        val path = root.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        runCatching { dir.toFile().setWritable(true, true) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        runCatching { Files.deleteIfExists(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        // 单条删不掉（被占用/权限）不中断整棵树；总账最后按 exists 判
                        runCatching { Files.deleteIfExists(file) }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        runCatching { Files.deleteIfExists(dir) }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    /** `rootfs.old.<纯数字>` 判定（纯函数，单测直接调）。 */
    internal fun isTrashName(name: String): Boolean {
        if (!name.startsWith(TRASH_PREFIX)) return false
        val digits = name.substring(TRASH_PREFIX.length)
        return digits.isNotEmpty() && digits.all { it in '0'..'9' }
    }

    /**
     * 启动时扫掉上次进程遗留的垃圾目录（[RootfsInstaller.cleanupPartial] 调用）。
     * **不碰** `rootfs.old` 本身——那个名字留给恢复语义。返回删掉的目录数。
     */
    fun sweep(filesDir: File): Int {
        val dirs = filesDir.listFiles { f -> f.isDirectory && isTrashName(f.name) } ?: return 0
        var deleted = 0
        for (d in dirs) {
            if (deleteTree(d)) deleted++
            else RunLog.log("旧树垃圾目录未删净（下次再试）：${d.name}")
        }
        return deleted
    }
}
