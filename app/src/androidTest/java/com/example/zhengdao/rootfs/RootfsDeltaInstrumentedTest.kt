// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：Android instrumented test 官方文档；Apache Commons Compress 官方文档（TarArchiveOutputStream）。
package com.example.zhengdao.rootfs

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * 增量更新内核 [RootfsDelta.applyTo] 的真机（或模拟器）验证（协议 §6）。
 *
 * **完全自足**：不依赖设备上任何真实 rootfs 包，全部在 `context.cacheDir` 下现造：
 * 一个假的"已装旧环境"目录树 + 一个用 commons-compress 现写的**未压缩 .tar 补丁**
 * （顺带验证 `RootfsInstaller.openTar` 新支持的纯 tar 格式）。
 * 覆盖：新增文件、覆盖文件、删除文件与目录树、改 mode、换软链目标、
 * 补丁元数据不落地、标记 env 更新、以及基线不匹配时"动手之前就失败且原树不动"。
 */
@RunWith(AndroidJUnit4::class)
class RootfsDeltaInstrumentedTest {

    private val baseEnv = "1111111111111111"
    private val newEnv = "2222222222222222"
    private val distro = "debian-13.7"

    private fun workDir(): File {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return File(ctx.cacheDir, "rootfs-delta-instr").apply { deleteRecursively(); mkdirs() }
    }

    /** 造一个"已装好的旧环境"：普通文件、目录、软链、可执行位、以及带 env 的标记文件。 */
    private fun makeBaseTree(root: File, env: String = baseEnv) {
        root.mkdirs()
        File(root, "keep.txt").writeText("keep")                       // 补丁不提及 ⇒ 必须原样保留
        File(root, "over.txt").writeText("old")                        // 被覆盖
        File(root, "mode.sh").apply {                                  // 只改权限位
            writeText("echo old\n")
            Os.chmod(absolutePath, 420)                                // 0644
        }
        File(root, "old.txt").writeText("delete me")                   // 被删文件
        File(root, "dir/sub.txt").apply {                              // 被整树删掉的目录
            parentFile!!.mkdirs()
            writeText("delete me too")
        }
        Os.symlink("keep.txt", File(root, "link").absolutePath)        // 软链目标要换
        RootfsMarker.write(root, distro, env, RootfsMarker.nowIso())
    }

    /** 现写一个未压缩 tar 补丁：第一个成员固定是补丁元数据，其余是实际改动。 */
    private fun makePatchTar(file: File, base: String, new: String) {
        FileOutputStream(file).use { fos ->
            TarArchiveOutputStream(fos).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tarText(
                    tar, "./${RootfsDelta.PATCH_INFO_NAME}",
                    "zhengdao-patch v1\nbase=$base\nnew=$new\ndeletes=2\nold.txt\ndir\n"
                )
                tarText(tar, "./new.txt", "brand new\n", 420)
                tarText(tar, "./over.txt", "new content\n", 420)
                tarText(tar, "./mode.sh", "echo new\n", 493)            // 0755
                tarSymlink(tar, "./link", "over.txt")
            }
        }
    }

    private fun tarText(tar: TarArchiveOutputStream, name: String, content: String, mode: Int = 420) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val e = TarArchiveEntry(name)
        e.mode = mode
        e.size = bytes.size.toLong()
        tar.putArchiveEntry(e)
        tar.write(bytes)
        tar.closeArchiveEntry()
    }

    private fun tarSymlink(tar: TarArchiveOutputStream, name: String, target: String) {
        val e = TarArchiveEntry(name, TarConstants.LF_SYMLINK)
        e.linkName = target
        e.mode = 511                                                    // 0777（软链权限惯例）
        tar.putArchiveEntry(e)
        tar.closeArchiveEntry()
    }

    @Test
    fun 增量应用_新增覆盖删除改权换链与标记都正确() {
        val work = workDir()
        val base = File(work, "rootfs").apply { mkdirs() }
        val tmp = File(work, RootfsInstaller.TMP_NAME)
        val patch = File(work, "rootfs-patch-$baseEnv-to-$newEnv.tar")
        makeBaseTree(base)
        makePatchTar(patch, baseEnv, newEnv)

        // 补丁元数据必须先能读出来（CI 产物格式的守门）
        val info = RootfsDelta.readPatchInfo(patch)
        assertNotNull("补丁第一个成员必须是可解析的 .zhengdao-patch-info", info)
        assertEquals(baseEnv, info!!.base)
        assertEquals(newEnv, info.new)
        assertEquals(listOf("old.txt", "dir"), info.deletes)

        // 基线一致 ⇒ 可以应用
        RootfsDelta.applyTo(base, tmp, patch, info)

        // ① 新增
        assertEquals("brand new\n", File(base, "new.txt").readText())
        // ② 覆盖（克隆出的副本与旧树共享 inode 时必须"先删再写"，否则会改到旧环境）
        assertEquals("new content\n", File(base, "over.txt").readText())
        // ③ 未提及的文件原样保留
        assertEquals("keep", File(base, "keep.txt").readText())
        // ④ 删除清单：文件与目录整树
        assertFalse("old.txt 应被删除", File(base, "old.txt").exists())
        assertFalse("dir 应被整树删除", File(base, "dir").exists())
        // ⑤ 权限位被改对（0755）
        assertEquals(493, Os.stat(File(base, "mode.sh").absolutePath).st_mode and 4095)
        // ⑥ 软链目标被换掉
        assertEquals("over.txt", Os.readlink(File(base, "link").absolutePath))
        // ⑦ 补丁元数据绝不能落进文件树
        assertFalse(
            "补丁元数据不该出现在环境树里",
            File(base, RootfsDelta.PATCH_INFO_NAME).exists()
        )
        // ⑧ 标记：env 换成新值，distro 沿用旧值
        assertEquals(newEnv, RootfsMarker.installedEnv(base))
        assertEquals(distro, RootfsMarker.read(base)?.distro)
        // ⑨ 原子替换：临时目录已消失（被 rename 成了 rootfs）
        assertFalse("临时目录不该残留", tmp.exists())
    }

    @Test
    fun 基线不匹配时动手之前就抛错且原树完全不变() {
        val work = workDir()
        val base = File(work, "rootfs").apply { mkdirs() }
        val tmp = File(work, RootfsInstaller.TMP_NAME)
        val patch = File(work, "rootfs-patch-mismatch.tar")
        makeBaseTree(base)
        makePatchTar(patch, "deadbeefdeadbeef", newEnv)     // 补丁基线 ≠ 本地 env

        val info = RootfsDelta.readPatchInfo(patch)!!
        assertThrows(RootfsDelta.BaseMismatch::class.java) {
            RootfsDelta.applyTo(base, tmp, patch, info)
        }

        // 原树逐项未被改动
        assertEquals("old", File(base, "over.txt").readText())
        assertEquals("keep", File(base, "keep.txt").readText())
        assertEquals("echo old\n", File(base, "mode.sh").readText())
        assertEquals("delete me", File(base, "old.txt").readText())
        assertEquals("delete me too", File(base, "dir/sub.txt").readText())
        assertEquals("keep.txt", Os.readlink(File(base, "link").absolutePath))
        assertEquals(baseEnv, RootfsMarker.installedEnv(base))
        assertFalse("失败不该留临时目录", tmp.exists())
    }
}
