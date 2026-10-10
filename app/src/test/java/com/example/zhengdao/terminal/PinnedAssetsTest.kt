// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PinnedAssets] 单测——版本固定校验快路径（BUG-2 / E-083）。
 *
 * 这里守的是一条**安全不变量**：戳只是"自那次核对后文件没被动过"的记录，绝不能变成
 * "有戳就放行"。所以最要紧的两个用例是：APK 升版换了钉住的 sha ⇒ 必须重算；
 * 文件大小/mtime 变了 ⇒ 必须重算。任何时候 [PinnedAssets.needsReverify] 说不清，
 * 就返回"要重算"（宁可多算一次 313 KB，也不能让未校验的二进制上桌）。
 */
class PinnedAssetsTest {

    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)

    @Test
    fun `parse 与 format 来回一趟不丢信息`() {
        val stamps = mapOf(
            "proot" to PinnedAssets.Stamp(247_488, 1_700_000_000_000, shaA),
            "loader" to PinnedAssets.Stamp(18_136, 1_700_000_001_000, shaB),
        )
        val text = PinnedAssets.format(stamps)
        assertEquals(stamps, PinnedAssets.parse(text))
    }

    @Test
    fun `format 按名字排序、以换行结尾`() {
        val text = PinnedAssets.format(
            mapOf(
                "proot" to PinnedAssets.Stamp(1, 2, shaA),
                "libtalloc.so.2" to PinnedAssets.Stamp(3, 4, shaB),
            )
        )
        assertEquals(
            "libtalloc.so.2\t3\t4\t$shaB\nproot\t1\t2\t$shaA\n",
            text
        )
    }

    @Test
    fun `parse 遇到坏行一律跳过`() {
        val text = buildString {
            append("# 注释\n")
            append("\n")
            append("proot\t247488\t1700000000000\t$shaA\n")   // 好行
            append("loader\tabc\t1700000000000\t$shaB\n")     // size 不是数字
            append("tloader\t1\t2\n")                         // 字段不足
            append("libtalloc.so.2\t1\t2\tshort\n")           // sha 长度不对
            append("\t1\t2\t$shaB\n")                         // 名字空
        }
        val parsed = PinnedAssets.parse(text)
        assertEquals(setOf("proot"), parsed.keys)
        assertNull(parsed["libtalloc.so.2"])
    }

    @Test
    fun `parse 空文本与垃圾文本返回空表而不是抛异常`() {
        assertTrue(PinnedAssets.parse("").isEmpty())
        assertTrue(PinnedAssets.parse("\u0000\u0001乱码").isEmpty())
    }

    @Test
    fun `needsReverify 没有戳就要重算`() {
        assertTrue(PinnedAssets.needsReverify(247_488, 1_700_000_000_000, shaA, null))
    }

    @Test
    fun `needsReverify 大小 mtime sha 三者全对才跳过`() {
        val stamp = PinnedAssets.Stamp(247_488, 1_700_000_000_000, shaA)
        assertFalse(PinnedAssets.needsReverify(247_488, 1_700_000_000_000, shaA, stamp))
        // 大小写不敏感（sha 来自资产清单，可能带大写）
        assertFalse(PinnedAssets.needsReverify(247_488, 1_700_000_000_000, shaA.uppercase(), stamp))
    }

    @Test
    fun `needsReverify APK 升版换了钉住的 sha 必须重算`() {
        // 这正是快路径最危险的一格：文件没动过、大小 mtime 都对，但期望值变了 ——
        // 说明盘上这份是**旧版本的二进制**，必须重算并让上层重新释放。
        val stamp = PinnedAssets.Stamp(247_488, 1_700_000_000_000, shaA)
        assertTrue(PinnedAssets.needsReverify(247_488, 1_700_000_000_000, shaB, stamp))
    }

    @Test
    fun `needsReverify 文件被写过（大小或 mtime 变了）必须重算`() {
        val stamp = PinnedAssets.Stamp(247_488, 1_700_000_000_000, shaA)
        assertTrue(PinnedAssets.needsReverify(247_489, 1_700_000_000_000, shaA, stamp))
        assertTrue(PinnedAssets.needsReverify(247_488, 1_700_000_000_001, shaA, stamp))
    }

    @Test
    fun `stampFor 把 sha 规整成小写并去掉空白`() {
        val s = PinnedAssets.stampFor(1, 2, "  ${shaA.uppercase()}  ")
        assertEquals(shaA, s.sha)
        assertEquals(1L, s.size)
        assertEquals(2L, s.mtime)
    }
}
