// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：java.io.File / JUnit 4（TemporaryFolder）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.kernel.BaopuKernel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 157 **全局记忆** [GlobalMemory] 的 JVM 直测（临时目录，不碰 Context）。
 *
 * 覆盖派工单判据（用户原话「App 私有、全局一份」「全局与工作区不许互相覆盖」）：
 * - [GlobalMemory.fileIn] 指向 App 私有（`filesDir/抱朴记忆/全局记忆.md`），
 *   与工作区/工作区记忆目录**不同根**（负例由 [MemoryLayoutTest] 的 allowedWrite 钉死，
 *   这里补存管三法）；
 * - save/load round trip：原子写（不留 `.tmp`）、再写覆盖、空文件视为没有；
 * - 父路径被文件占用 ⇒ save 返回 false **不抛**（调用方记日志即可）。
 */
class GlobalMemoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun f(parent: File, rel: String, text: String = "内容"): File {
        val out = File(parent, rel)
        out.parentFile?.mkdirs()
        out.writeText(text)
        return out
    }

    @Test
    fun `fileIn指向App私有全局记忆_与工作区不同根`() {
        val files = tmp.newFolder("files")
        val ws = tmp.newFolder("工作区")
        val g = GlobalMemory.fileIn(files)
        assertEquals(MemoryLayout.globalMemoryFile(files), g)
        assertEquals(
            File(File(files, BaopuKernel.Data.DIR_GLOBAL_MEMORY), BaopuKernel.Data.FILE_GLOBAL_MEMORY).path,
            g.path,
        )
        // 不在工作区里，也不在工作区记忆目录里 ⇒ 结构上不可能互相覆盖
        assertFalse(g.startsWith(ws))
        assertFalse(g.startsWith(MemoryLayout.memoryDir(ws)))
    }

    @Test
    fun `不存在时load为null_save后roundTrip_再save覆盖`() {
        val files = tmp.newFolder("files")
        assertNull("没写过应为 null", GlobalMemory.load(files))

        assertTrue(GlobalMemory.save(files, "偏好：中文\n"))
        assertEquals("偏好：中文\n", GlobalMemory.load(files))

        assertTrue(GlobalMemory.save(files, "偏好：中文、简洁"))
        assertEquals("偏好：中文、简洁", GlobalMemory.load(files))
        // 原子写：成功后不留 .tmp
        val dir = GlobalMemory.fileIn(files).parentFile!!
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `空内容视为没有`() {
        val files = tmp.newFolder("files")
        f(GlobalMemory.fileIn(files).parentFile!!, BaopuKernel.Data.FILE_GLOBAL_MEMORY, "   \n\t ")
        assertNull(GlobalMemory.load(files))
    }

    @Test
    fun `父路径被文件占用_save返回false不抛_load仍为null`() {
        val files = tmp.newFolder("files")
        // 把 filesDir/抱朴记忆 占成一个普通文件 ⇒ mkdirs 必失败
        f(files, BaopuKernel.Data.DIR_GLOBAL_MEMORY, "占位文件")
        val ok = GlobalMemory.save(files, "写不进去")
        assertFalse("父路径被占应返回 false（不抛）", ok)
        assertNull(GlobalMemory.load(files))
    }
}
