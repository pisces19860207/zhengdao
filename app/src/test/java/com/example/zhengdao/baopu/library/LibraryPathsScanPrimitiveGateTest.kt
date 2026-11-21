// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.library

import com.example.zhengdao.CodeText
import com.example.zhengdao.GateBodies
import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 772 源码门禁（E-197 ③）：`LibraryPaths` 里**不许出现裸 `.listFiles()`**——改回即红。
 *
 * 716 崩溃链的最后一帧就是这里：`java.io.File.list()` 的 JNI 路径（list0→NewStringUTF）
 * 在 CheckJNI 严格态遇非法 UTF-8 名直接 SIGABRT 整进程，Kotlin 层 catch 接不住。
 * 765 换了列举原语但漏了本文件的 scanTree/countTopLevel（E-197 教训①：「接了 25 处」
 * ≠ 接到了崩溃那条链上）——本门禁把这个文件的列举原语钉死在
 * `ScanTolerance.listTolerant` 一条路上，谁改回裸 java.io 原语，单测当场红。
 *
 * 三条判据：
 * - **A 全文件禁令**：剥注释与字面量后，裸 `.listFiles()` 计数 == 0
 *   （判别器与派工单③的正则 `\.listFiles\(\)` 逐字一致；注释里提 listFiles 不算）；
 * - **B 负自证**：把回归形态（`cur.listFiles() ?: continue` 等）注回代码文本，
 *   A 的判别器必须咬红——门禁不许恒真；
 * - **C 接线锚**：scanTree／countTopLevel／listTopLevelFiles 三个列举函数体各自真调
 *   `ScanTolerance.listTolerant`——不许整体拆掉换别的原语绕开门禁。
 */
class LibraryPathsScanPrimitiveGateTest {

    private val src: String by lazy {
        SrcRoots.find("app/src/main/java/com/example/zhengdao/baopu/library/LibraryPaths.kt").readText()
    }

    /** A 的判别器（A／B 共用；与 E-197 ③ 的正则同形）。 */
    private fun bareListFiles(code: String): List<String> =
        Regex("""\.listFiles\(\)""").findAll(code).map { it.value }.toList()

    @Test
    fun `门禁A_LibraryPaths剥注释后不许出现裸listFiles`() {
        val code = CodeText.codeOnly(src)
        val hits = bareListFiles(code)
        assertEquals(
            "LibraryPaths 还有裸 .listFiles()（716 崩溃原语，改走 ScanTolerance.listTolerant）：$hits",
            0,
            hits.size,
        )
    }

    @Test
    fun `门禁B_负自证_注回回归形态判别器必须咬红`() {
        val code = CodeText.codeOnly(src)
        // M1 形态：scanTree 的列举点改回 765 之前的一行 ⇒ 必须咬红
        val m1 = code.replace(
            "val children = ScanTolerance.listTolerant(cur).entries",
            "val children = cur.listFiles() ?: continue",
        )
        assertTrue("scanTree 注回裸 listFiles 后应咬红", bareListFiles(m1).isNotEmpty())
        // M2 形态：countTopLevel 同理
        val m2 = code.replace(
            "val children = ScanTolerance.listTolerant(dir).entries",
            "val children = dir.listFiles() ?: return 0",
        )
        assertTrue("countTopLevel 注回裸 listFiles 后应咬红", bareListFiles(m2).isNotEmpty())
    }

    @Test
    fun `门禁C_三个列举函数各自真调listTolerant`() {
        for (funName in listOf("scanTree", "countTopLevel", "listTopLevelFiles")) {
            val body = GateBodies.functionBody(src, "fun $funName(")
            assertNotNull("找不到函数体：$funName", body)
            assertTrue(
                "$funName 必须经 ScanTolerance.listTolerant（E-197：不许换别的原语绕开门禁）",
                body!!.contains("ScanTolerance.listTolerant("),
            )
        }
    }
}
