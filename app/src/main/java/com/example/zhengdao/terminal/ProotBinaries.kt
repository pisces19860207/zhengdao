// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import android.content.Context
import com.example.zhengdao.rootfs.RunLog
import java.io.File

/**
 * proot 四件套的**单一真相源**（第⑳刀，2026-10-15：assets → jniLibs 迁移）。
 *
 * 背景：targetSdk 28 时代四件套随 APK assets 内置、启动时释放到 `files/termux-proot/`
 * 再 execve（旧 SELinux 域放行数据目录 exec）。第⑳刀起改为直接打进 APK 的
 * `jniLibs/arm64-v8a/`（安装时由系统解压到 `nativeLibraryDir`，天然带执行位），
 * 为第㉑刀抬 targetSdk 铺路——nativeLibraryDir 是 W^X 规则下唯一放行 exec 的位置
 * （该结论已于 2026-10-15 由原型 proot-jnilib-proto 在荣耀 PGT-AN10 · Android 16 ·
 * targetSdk 36 上四判据真机验证，证据①；全链记录见 docs/ERRATA.md E-134）。
 *
 * 四件套（Termux 官方发行二进制 5.1.107.96 / libtalloc 2.5.0 / libandroid-shmem 0.7，
 * 原样聚合分发、只 exec 绝不 loadLibrary——GPL 合规口径见 PROVENANCE.md；
 * SHA256 与历史 pin 逐字节一致，来源与版本登记在案）：
 *  - [PINNED] 里的 `libproot.so`        proot 本体（宿主侧唯一被 execve 的文件）
 *  - `libproot-loader.so`     64 位 loader（proot 经 ptrace 改写 guest execve 指向它；
 *    **必须显式设 PROOT_LOADER 指向它**，否则 proot 自抽取 loader 落临时目录 ⇒ EACCES，
 *    且失败是静默的——原型真机实录）
 *  - `libtalloc.so`           proot 的 DT_NEEDED 依赖；**bionic 按文件名找
 *    `libtalloc.so.2`**，而 jniLibs 只认 `*.so` 结尾 ⇒ 运行时复制改名到
 *    [runtimeDir]（[ensureRuntimeLibs]）
 *  - `libandroid-shmem.so`    proot 的 DT_NEEDED **硬依赖**（原型真机踩坑：缺它
 *    `CANNOT LINK EXECUTABLE "…/libproot.so"`）；文件名恰与 DT_NEEDED 一致，
 *    直接留在 nativeLibraryDir 由 LD_LIBRARY_PATH 命中，无需复制
 *
 * 本 object 只做"路径与名字"的收口：[ProotLauncher]（组参）、`EnvHealth`（体检）、
 * `SystemInfoProvider`（关于页）三处共用，杜绝各写一份路径漂移。
 */
object ProotBinaries {

    /** 四件套（jniLibs 文件名 → 钉住的 SHA256；与 PROVENANCE.md、tools 门禁共用同一组值）。 */
    val PINNED: Map<String, String> = mapOf(
        "libproot.so" to "1545b85b312505db6eb6908ff8b2ded0a77a3bd689c50ae85aa7c1d8445dd717",
        "libproot-loader.so" to "cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551",
        "libtalloc.so" to "742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309",
        "libandroid-shmem.so" to "84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731",
    )

    /** APK 安装器解压原生库的目录（只读、带执行位；W^X 规则放行 exec 的位置）。 */
    fun nativeLibDir(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir)

    /** 宿主侧被 execve 的 proot 本体。 */
    fun prootSo(context: Context): File = File(nativeLibDir(context), "libproot.so")

    /** proot 改写 guest execve 时指向的 loader。 */
    fun loaderSo(context: Context): File = File(nativeLibDir(context), "libproot-loader.so")

    /**
     * 运行期可写库目录（`files/proot-runtime/`）：`libtalloc.so.2`（从 jniLibs 的
     * `libtalloc.so` 复制改名而来）落这里；PinnedAssets 戳文件也落这里
     * （nativeLibraryDir 只读，戳只能放可写处）。
     */
    fun runtimeDir(context: Context): File = File(context.filesDir, "proot-runtime")

    /**
     * 把 jniLibs 的 `libtalloc.so` 复制成 bionic 需要的文件名 `libtalloc.so.2`。
     * 幂等：目标在且与源等长即跳过（源是安装器放的、App 不可改，等长即同内容；
     * 上层还有 SHA 戳记兜底，见 [ProotLauncher] 的版本固定校验）。
     * 复制失败返回 false（调用方 fail-closed：缺 libtalloc ⇒ proot 起不来，
     * 属"必须可见"的失败，不能静默带病硬起）。
     */
    fun ensureRuntimeLibs(context: Context): Boolean = try {
        val dir = runtimeDir(context)
        dir.mkdirs()
        val src = File(nativeLibDir(context), "libtalloc.so")
        val dst = File(dir, "libtalloc.so.2")
        if (!dst.isFile || dst.length() != src.length()) {
            src.copyTo(dst, overwrite = true)
            RunLog.log("已复制 libtalloc.so → ${dst.absolutePath}（bionic 按 DT_NEEDED 文件名查找）")
        }
        true
    } catch (t: Throwable) {
        RunLog.log("proot-runtime 准备失败: ${t.message}")
        false
    }

    /**
     * 第⑳刀升级清理：删掉旧方案（assets 释放）的 `files/termux-proot/` 整目录
     * （含旧四件套与旧戳文件）。幂等；目录不在就是干净态。失败只记日志——
     * 残留几个 300KB 级旧文件无功能影响，下次启动再删就是。
     */
    fun cleanupLegacyTermuxProot(filesDir: File) {
        runCatching {
            val legacy = File(filesDir, "termux-proot")
            if (legacy.isDirectory) {
                legacy.deleteRecursively()
                RunLog.log("已清理旧版 proot 目录 ${legacy.absolutePath}（第⑳刀：改用 jniLibs 内置）")
            }
        }.onFailure { RunLog.log("旧版 proot 目录清理失败（忽略，下次再试）: ${it.message}") }
    }
}
