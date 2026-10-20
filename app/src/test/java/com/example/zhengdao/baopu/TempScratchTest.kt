// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-20 用户：「只让他出最后的产物；垃圾就让他自己弄到回收站」。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TempScratchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun reaps_files_into_trash_and_removes_empty_dirs() {
        val root = tmp.root
        val scratch = File(root, TempScratch.DIR)
        File(scratch, "sub").mkdirs()
        File(scratch, "a.txt").writeText("a")
        File(scratch, "sub/b.txt").writeText("b")

        val moved = TempScratch.reap(root, nowMs = 1_700_000_000_000L)
        assertEquals("两个文件都要搬走", 2, moved)
        assertFalse("临时/ 里不该再留文件", TempScratch.leftover(root).isNotEmpty())

        val trash = File(root, "回收站")
        val names = trash.listFiles()!!.map { it.name }.sorted()
        assertEquals(2, names.size)
        assertTrue("命名要带时间戳前缀：$names", names.all { it.matches(Regex("\\d{8}-\\d{6}-.*")) })
        assertTrue("原名要保留：$names", names.any { it.endsWith("-a.txt") } && names.any { it.endsWith("-b.txt") })
    }

    @Test
    fun second_reap_is_zero_and_missing_dir_is_fine() {
        val root = tmp.root
        assertEquals("没有 临时/ 时返回 0", 0, TempScratch.reap(root, 1L))

        File(root, TempScratch.DIR).mkdirs()
        File(root, "${TempScratch.DIR}/x.txt").writeText("x")
        assertEquals(1, TempScratch.reap(root, 2L))
        assertEquals("搬空了第二次就是 0", 0, TempScratch.reap(root, 3L))
    }

    @Test
    fun name_collision_gets_suffix() {
        val root = tmp.root
        File(root, TempScratch.DIR).mkdirs()
        File(root, "${TempScratch.DIR}/same.txt").writeText("1")
        TempScratch.reap(root, 10L)
        File(root, TempScratch.DIR).mkdirs()
        File(root, "${TempScratch.DIR}/same.txt").writeText("2")
        // 同一毫秒再来一次 ⇒ 时间戳前缀相同，必须走 -2 后缀
        TempScratch.reap(root, 10L)

        val names = File(root, "回收站").listFiles()!!.map { it.name }
        assertEquals("同名不能覆盖", 2, names.size)
        assertTrue("第二份要带 -2：$names", names.any { it.contains("-2-same.txt") })
    }
}
