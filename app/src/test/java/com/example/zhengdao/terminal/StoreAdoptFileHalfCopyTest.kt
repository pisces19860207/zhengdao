// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 审计第 2 期 **B-4** 的验收：`Store.adoptFile` 遇到「目标已存在但是半截」必须重拷。
 *
 * 现场怎么造：`legacy.copyTo(target)` 中途抛异常（存储写满 / 权限 / 被杀）会在目标名下留下
 * 源的**前 N 字节** —— 这正是"半截目标"。旧写法 `target.exists() → return false` 把它当成
 * "已搬过"，于是**永不重拷**（脏残留 + 永不修复）。
 *
 * 新判据（[Store] 里 `isHalfCopy`）：目标**严格短于**源、且字节逐位等于源的前缀 ⇒ 清掉重拷。
 * 反过来，内容对不上的同名文件（用户自己放在公共区的另一份）**绝不能被覆盖** —— 这条纪律
 * 与 [Store.adoptDir] 的「绝不覆盖」一致，本测试一并钉住。
 *
 * 为什么能用真单测（而不是像 `StorePartialCopyGuardTest` 那样只做源码门禁）：判据本身是
 * 纯文件操作，不依赖 `renameTo` 失败、也不依赖把复制打断 —— "半截目标"可以直接写出来。
 */
class StoreAdoptFileHalfCopyTest {

    private fun dir(name: String): File =
        File.createTempFile(name, "").let { it.delete(); it.mkdirs(); it }

    private fun File.child(name: String, content: String): File =
        File(this, name).apply { parentFile?.mkdirs(); writeText(content) }

    @Test
    fun `目标已存在但是半截时会重拷且字节与源一致`() {
        val pub = dir("pub")
        val old = dir("old")
        val content = "完整的源内容"
        val legacy = old.child("session.json", content)
        val expected = legacy.readBytes()

        // 造"上次复制中途夭折"的现场：目标存在，但只写了源的前两个字（＝源的前缀）
        val target = File(pub, "session.json").apply { writeText("完整") }
        assertTrue("（前提）目标必须确实比源短", target.length() < legacy.length())

        assertTrue(
            "半截目标必须触发重拷（旧写法 target.exists() → false ⇒ 永不重拷）",
            Store.adoptFile(target, legacy),
        )
        assertArrayEquals("最终字节必须与源逐字节一致", expected, File(pub, "session.json").readBytes())
        assertFalse("搬完之后源不该还在", legacy.exists())
    }

    @Test
    fun `零字节目标也按半截处理`() {
        val pub = dir("pub")
        val old = dir("old")
        val legacy = old.child("x.json", "内容")
        val target = File(pub, "x.json").apply { createNewFile() } // 0 字节：复制夭折在第一步

        assertTrue("0 字节目标也是半截，必须重拷", Store.adoptFile(target, legacy))
        assertEquals("内容", File(pub, "x.json").readText())
    }

    @Test
    fun `内容对不上的同名目标绝不被覆盖`() {
        val pub = dir("pub")
        val old = dir("old")
        val legacy = old.child("session.json", "更新的内容")
        val target = pub.child("session.json", "内容") // 用户自己放的另一个同名文件

        assertFalse("内容不是源的前缀 ⇒ 不是残片，一律不覆盖", Store.adoptFile(target, legacy))
        assertEquals("内容", File(pub, "session.json").readText())
        assertTrue("被跳过的源必须留着（绝不静默丢数据）", legacy.exists())
    }

    @Test
    fun `与源不短于源的目标视为完整不重拷`() {
        val pub = dir("pub")
        val old = dir("old")
        val content = "内容"
        val legacy = old.child("session.json", content)
        val target = pub.child("session.json", content) // 完整副本

        assertFalse("长度不短于源 ⇒ 视为已搬完，不再重拷（幂等）", Store.adoptFile(target, legacy))
        assertEquals(content, target.readText())
        assertTrue(legacy.exists())
    }
}
