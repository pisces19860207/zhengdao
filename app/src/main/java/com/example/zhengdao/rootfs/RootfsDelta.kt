// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 依据的公开标准与官方文档：tar 归档格式（POSIX 1003.1-1988 ustar），经 Apache Commons
// Compress 官方文档使用 TarArchiveInputStream；java.nio.file.Files 的 createLink /
// createSymbolicLink / setPosixFilePermissions（JDK 标准库，Android API 26+ 可用）。
package com.example.zhengdao.rootfs

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 环境包**增量**更新（协议 §6）。
 *
 * 端侧算法（顺序即安全性）：
 * 1. **前置校验**：本地标记 `env` 必须等于补丁基线，否则 [BaseMismatch]（**动任何文件之前**）；
 * 2. `rootfs` 整树克隆到 `rootfs.tmp`（同内容文件优先硬链接，省时省空间）；
 * 3. 解补丁到 tmp，跳过元数据成员 [PATCH_INFO_NAME]（走 Java/commons-compress 路径）；
 * 4. 按 `deletes` 递归删；
 * 5. 写新标记（`env = info.new`）；
 * 6. 原子替换：删旧 `rootfs` → `tmp.renameTo(rootfs)`，失败退整树复制。
 *
 * **刻意不做**全树逐文件校验（协议 §6）：保留用户 `apt upgrade` 造成的漂移。
 * 但删除清单与前置 env 校验必做——否则新旧混装会产出"看似成功实则半旧"的环境。
 *
 * 任何一步失败都抛异常，由调用方**回退全量**；tmp 会先清理，绝不留下半装状态。
 */
object RootfsDelta {

    private const val TAG = "RootfsDelta"

    /** 本地基线与补丁不匹配（调用方见到它就回退全量）。 */
    class BaseMismatch(message: String) : Exception(message)

    /**
     * 补丁元数据（协议 §3，补丁包的第一个成员）。
     * @param deletes 需要递归删除的相对路径（无 `./` 前缀，目录直接给目录名）
     */
    data class PatchInfo(val base: String, val new: String, val deletes: List<String>)

    /** 补丁元数据成员名（带 `./` 前缀的写法同样被识别）。 */
    const val PATCH_INFO_NAME = ".zhengdao-patch-info"

    private const val HEADER = "zhengdao-patch v1"

    /**
     * 解析补丁元数据（**纯函数**）。以下情况返回 null（调用方回退全量，绝不半信半疑地装）：
     * - 首行不是 `zhengdao-patch v1`；
     * - 缺 `base=` / `new=` / `deletes=` 任一行；
     * - `deletes=` 不是非负整数；
     * - 实际删除行数**少于**声明数（补丁下载被截断的典型征兆）。
     */
    fun parsePatchInfo(text: String): PatchInfo? {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines.first() != HEADER) return null

        var base: String? = null
        var new: String? = null
        var declared: Int? = null
        val deletes = mutableListOf<String>()
        var headerDone = false
        for (line in lines.drop(1)) {
            if (!headerDone) {
                when {
                    line.startsWith("base=") -> base = line.substringAfter('=').trim().ifEmpty { null }
                    line.startsWith("new=") -> new = line.substringAfter('=').trim().ifEmpty { null }
                    line.startsWith("deletes=") -> {
                        declared = line.substringAfter('=').trim().toIntOrNull()?.takeIf { it >= 0 }
                        headerDone = true
                    }
                    else -> return null // 头部出现未知行 ⇒ 不是本协议的补丁
                }
            } else {
                deletes.add(line)
            }
        }
        val count = declared ?: return null
        if (base == null || new == null) return null
        val parsed = parseDeletes(deletes)
        if (parsed.size < count) {
            Log.w(TAG, "补丁删除清单不完整：声明 $count 行，实到 ${parsed.size} 行")
            return null
        }
        return PatchInfo(base = base.lowercase(), new = new.lowercase(), deletes = parsed)
    }

    /**
     * 删除清单规整（**纯函数**）：去空白行、去 `#` 注释、去 `./` 前缀与尾部 `/`、按序去重。
     * 不在这里做越界判定——那是 [deletePath] 的职责（它拿得到 rootDir）。
     */
    fun parseDeletes(lines: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val cleaned = line.removePrefix("./").trimEnd('/')
            if (cleaned.isEmpty() || cleaned == ".") continue
            out.add(cleaned)
        }
        return out.toList()
    }

    /**
     * 从补丁包里读第一个成员当元数据。读不到/不是本协议的成员/文件不是 tar 家族 ⇒ null。
     * 只读第一个成员就返回（补丁很大，不能整包过一遍）。
     */
    fun readPatchInfo(patchFile: File): PatchInfo? = try {
        RootfsInstaller.openTar(patchFile).use { tar ->
            val entry = tar.nextTarEntry
            if (entry == null || !entry.isFile) {
                null
            } else if (entry.name.removePrefix("./") != PATCH_INFO_NAME) {
                null
            } else {
                parsePatchInfo(tar.readBytes().toString(Charsets.UTF_8))
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "读取补丁元数据失败: ${patchFile.name}", t)
        null
    }

    /** 本地已装 env 与补丁基线是否匹配（不匹配 ⇒ 只能全量）。 */
    fun canApply(context: Context, patch: PatchRef): Boolean =
        canApplyTo(RootfsMarker.installedEnv(context), patch)

    /** [canApply] 的纯函数内核（便于 JVM 单测）。 */
    internal fun canApplyTo(installedEnv: String?, patch: PatchRef): Boolean =
        !installedEnv.isNullOrBlank() && installedEnv.equals(patch.from, ignoreCase = true)

    /**
     * 公开入口：按真实目录（`<filesDir>/rootfs`、`<filesDir>/rootfs.tmp`）应用补丁。
     * 失败抛异常 ⇒ 调用方回退全量。
     *
     * @param expectedSha256 索引给出的补丁包 sha256（非 null 时由 Rust 对账；对不上硬失败）
     */
    fun apply(
        context: Context,
        patchFile: File,
        info: PatchInfo,
        onEntry: (String) -> Unit = {},
        expectedSha256: String? = null,
    ) {
        val rootfsDir = File(context.filesDir, "rootfs")
        val tmpDir = File(context.filesDir, RootfsInstaller.TMP_NAME)
        applyTo(rootfsDir, tmpDir, patchFile, info, onEntry, expectedSha256)
    }

    /**
     * 可单测/可仪器测试的内核：全部参数都是 [File]，不碰 Android Context。
     *
     * @param tmpDir 待替换的临时树目录（存在即先清理——它按约定是"可以随时丢弃"的中间产物）
     * @param expectedSha256 补丁包应有的 sha256（交给 Rust 对账；null = 只算不校验）
     */
    internal fun applyTo(
        rootfsDir: File,
        tmpDir: File,
        patchFile: File,
        info: PatchInfo,
        onEntry: (String) -> Unit = {},
        expectedSha256: String? = null,
    ) {
        // ── 1. 前置校验（协议 §6）：必须在动任何文件之前 ──
        val installed = RootfsMarker.installedEnv(rootfsDir)
        if (installed == null || !installed.equals(info.base, ignoreCase = true)) {
            throw BaseMismatch(
                "本地环境（${installed ?: "未记录版本"}）与补丁基线（${info.base}）不一致，改走全量"
            )
        }

        // ── 2. 清 tmp 并整树克隆 ──
        tmpDir.deleteRecursively()
        if (!tmpDir.mkdirs()) throw RootfsInstaller.InstallFailed("无法创建临时目录：${tmpDir.path}")
        val oldDistro = RootfsMarker.read(rootfsDir)?.distro

        try {
            cloneTree(rootfsDir, tmpDir)

            // ── 3. 解补丁（跳过元数据成员；Rust 优先，失败回退 Java —— 与全量装同一条路径） ──
            RootfsInstaller.extractArchive(
                archive = patchFile,
                destDir = tmpDir,
                skipNames = setOf(PATCH_INFO_NAME),
                expectedSha256 = expectedSha256,
                onEntry = onEntry,
            )

            // ── 4. 删除清单 ──
            for (rel in info.deletes) deletePath(tmpDir, rel)

            // ── 5. 新标记（distro 沿用旧值；旧标记读不到 distro 就不写该行） ──
            RootfsMarker.write(tmpDir, oldDistro, info.new, RootfsMarker.nowIso())

            // ── 6. 原子替换 ──
            RootfsInstaller.swapIntoPlace(tmpDir, rootfsDir)
            Log.i(TAG, "增量更新完成：${info.base} → ${info.new}")
        } catch (t: Throwable) {
            // 失败即丢弃半成品：绝不把 tmp 留在可能被误认成环境的状态
            runCatching { tmpDir.deleteRecursively() }
            throw t
        }
    }

    /**
     * 目录树整体复制：
     * - 软链重建软链（不跟随）；
     * - 普通文件优先 `Files.createLink` 硬链接（同 inode 省一次 GB 级拷贝），失败退流复制；
     * - 目录权限在内容写完后再设（否则只读目录会挡住写入）；
     * - FIFO/设备节点跳过（与解包逻辑一致：proot 方案下不需要真实创建）。
     *
     * 注意：硬链接与旧树共享 inode，因此**后续任何写入都必须先 delete 目标**（见
     * `RootfsInstaller.extractEntry`），否则会改到正在被 proot 使用的旧环境。
     */
    internal fun cloneTree(src: File, dst: File) {
        if (Files.isSymbolicLink(src.toPath())) {
            val link = runCatching { Files.readSymbolicLink(src.toPath()) }.getOrNull()
            dst.parentFile?.mkdirs()
            dst.delete()
            if (link == null) {
                Log.w(TAG, "读取软链失败，跳过：${src.path}")
            } else {
                runCatching { Files.createSymbolicLink(dst.toPath(), link) }
                    .onFailure { Log.w(TAG, "重建软链失败，跳过：${dst.path}", it) }
            }
            return
        }
        when {
            src.isDirectory -> {
                dst.mkdirs()
                src.listFiles()?.forEach { child -> cloneTree(child, File(dst, child.name)) }
                copyMode(src, dst)
            }
            src.isFile -> {
                dst.parentFile?.mkdirs()
                linkOrCopy(src, dst)
                copyMode(src, dst)
            }
            else -> Log.w(TAG, "跳过特殊文件：${src.path}")
        }
    }

    private fun linkOrCopy(src: File, dst: File) {
        val linked = runCatching {
            Files.deleteIfExists(dst.toPath())
            Files.createLink(dst.toPath(), src.toPath())
        }.isSuccess
        if (!linked) {
            // 硬链接在部分文件系统/权限下不可用（设计文档实测坑 #4：SELinux 拒非 root 建硬链接）
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun copyMode(src: File, dst: File) {
        runCatching {
            Files.setPosixFilePermissions(dst.toPath(), Files.getPosixFilePermissions(src.toPath()))
        }.onFailure { Log.w(TAG, "复制权限失败：${dst.path}", it) }
    }

    /**
     * 递归删除 `rootDir` 下的相对路径。
     *
     * 安全性：**只接受落在 rootDir 内的普通相对路径**，以下一律抛 [IllegalArgumentException]，
     * 绝不做"容错修正"（`../x`、`/abs`、`a/../../b`、含空段或 `.` 段、Windows 盘符）。
     */
    internal fun deletePath(rootDir: File, rel: String) {
        val raw = rel.trim()
        if (raw.isEmpty()) throw IllegalArgumentException("删除路径为空")
        val normalized = raw.replace('\\', '/')
        require(!normalized.startsWith("/")) { "删除路径不能是绝对路径: $rel" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(normalized)) { "删除路径不能带盘符: $rel" }
        val cleaned = normalized.removePrefix("./").trimEnd('/')
        val parts = cleaned.split('/')
        require(parts.isNotEmpty() && parts.none { it.isEmpty() || it == "." || it == ".." }) {
            "删除路径含非法段: $rel"
        }

        val target = File(rootDir, cleaned)
        val rootPath = rootDir.canonicalFile.path
        val targetPath = target.canonicalFile.path
        require(targetPath != rootPath && targetPath.startsWith(rootPath + File.separator)) {
            "删除路径越界: $rel"
        }
        if (target.exists() && !target.deleteRecursively()) {
            throw RootfsInstaller.InstallFailed("删除失败: $rel")
        }
    }
}
