// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.oc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * §B 顺序 17：`serve` 二进制的执行位曾经补错地方 —— `chmod` 打在 `.part` 上，
 * 而 `renameTo` 失败时退化的 `copyTo` **不保留执行位** ⇒ 最终文件是 0644，
 * 只能靠 `startServe` 的自愈兜底（真机上表现为 `exec error=13 EACCES`）。
 *
 * 这里钉的就是**顺序与落点**：`chmod` 必须作用在最终路径、且必须发生在文件已经落盘之后。
 * 生产里的 `chmod` 是 `android.system.Os.chmod`（JVM 里跑不了），所以用记账替身注入。
 */
class OcPublishTest {

    @Test
    fun `bin 下的程序 执行位补在最终路径上 而且是落盘之后`() {
        val dir = Files.createTempDirectory("zd-oc-publish").toFile()
        val target = File(dir, "usr/bin/serve")
        target.parentFile!!.mkdirs()
        val part = File(target.parentFile, "serve.part")
        part.writeText("MZ")

        val chmodded = ArrayList<File>()
        val targetExistedAtChmod = ArrayList<Boolean>()
        val partExistedAtChmod = ArrayList<Boolean>()

        OcManager.publishExtractedFile(part, target, "bin/serve") { f ->
            chmodded += f
            targetExistedAtChmod += target.isFile
            partExistedAtChmod += part.exists()
        }

        assertEquals(
            "chmod 必须作用在最终路径上，不能是 .part",
            listOf(target.canonicalPath),
            chmodded.map { it.canonicalPath },
        )
        assertTrue("chmod 时最终文件必须已经落盘", targetExistedAtChmod.single())
        assertFalse(
            "chmod 时临时文件应当已经改名消失（旧代码正是打在它身上 ⇒ 白做）",
            partExistedAtChmod.single(),
        )
        assertEquals("MZ", target.readText())
        assertFalse("落盘后不该留下 .part", part.exists())
    }

    @Test
    fun `非 bin 条目不碰执行位`() {
        val dir = Files.createTempDirectory("zd-oc-publish2").toFile()
        val target = File(dir, "usr/share/doc/readme.md")
        target.parentFile!!.mkdirs()
        val part = File(target.parentFile, "readme.md.part")
        part.writeText("x")

        var called = false
        OcManager.publishExtractedFile(part, target, "share/doc/readme.md") { called = true }

        assertFalse("非 bin/ 条目不该动权限位", called)
        assertEquals("x", target.readText())
    }

    @Test
    fun `bin 前缀判定按整段路径 不是子串`() {
        // `usbin/foo` 这种"包含 bin"但不能算 bin 目录的名字，落点必须仍然不补执行位。
        val dir = Files.createTempDirectory("zd-oc-publish3").toFile()
        val target = File(dir, "usr/usbin/foo")
        target.parentFile!!.mkdirs()
        val part = File(target.parentFile, "foo.part")
        part.writeText("y")

        var called = false
        OcManager.publishExtractedFile(part, target, "usbin/foo") { called = true }

        assertFalse("只有 rel 以 `bin/` 开头才算可执行目录", called)
        assertEquals("y", target.readText())
    }
}
