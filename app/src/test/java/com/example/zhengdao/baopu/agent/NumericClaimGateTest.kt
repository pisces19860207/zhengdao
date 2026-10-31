// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 340 · 值级验收的判据测试（三条硬规矩逐条钉住；改判据必须过这里）。
 */
class NumericClaimGateTest {

    private fun dir(vararg files: Pair<String, String>): File {
        val d = Files.createTempDirectory("zd-340-").toFile()
        for ((p, c) in files) {
            val f = File(d, p)
            f.parentFile?.mkdirs()
            f.writeText(c)
        }
        return d
    }

    private val nums = "notes/nums.txt" to "407\n3859\n620\n9438\n5176\n280\n8094\n3745\n6102\n9538\n4671\n7290"

    @Test
    fun `求和正确_VERIFIED`() {
        val d = dir(nums)
        val rs = NumericClaimGate.check("12 个数字相加得 59220，已写入 sum.txt。", d)
        val r = rs.single { it.kind == NumericClaimGate.Kind.SUM }
        assertEquals(NumericClaimGate.Status.VERIFIED, r.status)
        assertEquals("59220", r.actual!!.stripTrailingZeros().toPlainString())
    }

    @Test
    fun `求和错误_FAILED并报差多少`() {
        val d = dir(nums)
        val rs = NumericClaimGate.check("12 个数字相加得 55220，已写入 sum.txt。", d)
        val r = rs.single { it.kind == NumericClaimGate.Kind.SUM }
        assertEquals(NumericClaimGate.Status.FAILED, r.status)
        assertTrue(r.detail.contains("4000"))
    }

    @Test
    fun `差1也算差_钉住`() {
        val d = dir(nums)
        val rs = NumericClaimGate.check("12 个数字相加得 59221，已写入 sum.txt。", d)
        val r = rs.single { it.kind == NumericClaimGate.Kind.SUM }
        assertEquals(NumericClaimGate.Status.FAILED, r.status)
        assertTrue(r.detail.contains("差 1"))
    }

    @Test
    fun `平均_按声称精度重算_错则FAILED`() {
        val nums11 = "notes/nums.txt" to "407\n3859\n620\n9438\n5176\n280\n8094\n3745\n6102\n9538\n4671"
        val d = dir(nums11)
        // 真值 51930/11 = 4720.909… ⇒ 一位小数 4720.9
        val ok = NumericClaimGate.check("11 个数字合计 51930，平均 4720.9（保留一位小数），已写入 avg.txt。", d)
        assertEquals(NumericClaimGate.Status.VERIFIED, ok.single { it.kind == NumericClaimGate.Kind.AVG }.status)
        val bad = NumericClaimGate.check("11 个数字合计 49430，平均 4493.6（保留一位小数），已写入 avg.txt。", d)
        val r = bad.single { it.kind == NumericClaimGate.Kind.AVG }
        assertEquals(NumericClaimGate.Status.FAILED, r.status)
        assertEquals("4720.9", r.actual!!.toPlainString())
    }

    @Test
    fun `行段求和_按行号口径重算_差1也算差`() {
        val d = dir(nums)
        // 第 3–8 行：620+9438+5176+280+8094+3745 = 27353（335 range-sum 形状）
        val ok = NumericClaimGate.check("第 3–8 行是 620、9438、5176、280、8094、3745，和是 27353。", d)
        assertEquals(NumericClaimGate.Status.VERIFIED, ok.single { it.kind == NumericClaimGate.Kind.SUM }.status)
        val bad = NumericClaimGate.check("第 3–8 行是 620、9438、5176、280、8094、3745，和是 27354。", d)
        val r = bad.single { it.kind == NumericClaimGate.Kind.SUM }
        assertEquals(NumericClaimGate.Status.FAILED, r.status)
        assertTrue(r.detail.contains("差 1"))
    }

    @Test
    fun `百分比_按来源总和重算_差1也算差`() {
        val d = dir(nums)
        // 59220 × 15% = 8883 整
        val ok = NumericClaimGate.check("12 个数字合计 59220，15% 是 8883，已写入 pct.txt。", d)
        assertEquals(NumericClaimGate.Status.VERIFIED, ok.single { it.kind == NumericClaimGate.Kind.PERCENT }.status)
        val bad = NumericClaimGate.check("12 个数字合计 59220，15% 是 8882，已写入 pct.txt。", d)
        val r = bad.single { it.kind == NumericClaimGate.Kind.PERCENT }
        assertEquals(NumericClaimGate.Status.FAILED, r.status)
        assertTrue(r.detail.contains("差 1"))
    }

    @Test
    fun `行数_正确VERIFIED_错则FAILED`() {
        val lines = (1..150).joinToString("\n") { "L${it.toString().padStart(3, '0')}-行内容" }
        val d = dir("a.txt" to lines)
        val ok = NumericClaimGate.check("a.txt 共 150 行，最后一行是 L150-行内容。", d)
        assertEquals(NumericClaimGate.Status.VERIFIED, ok.single { it.kind == NumericClaimGate.Kind.LINES }.status)
        val bad = NumericClaimGate.check("a.txt 共 149 行。", d)
        assertEquals(NumericClaimGate.Status.FAILED, bad.single { it.kind == NumericClaimGate.Kind.LINES }.status)
    }

    @Test
    fun `文件数_按目录重算`() {
        val d = dir(
            "backup/f1.md" to "1", "backup/f2.md" to "2", "backup/f3.md" to "3",
            "notes/f1.md" to "1",
        )
        val ok = NumericClaimGate.check("backup/ 下 3 个文件都备份好了。", d)
        assertEquals(NumericClaimGate.Status.VERIFIED, ok.single { it.kind == NumericClaimGate.Kind.FILE_COUNT }.status)
        val bad = NumericClaimGate.check("backup/ 下 4 个文件都备份好了。", d)
        assertEquals(NumericClaimGate.Status.FAILED, bad.single { it.kind == NumericClaimGate.Kind.FILE_COUNT }.status)
    }

    @Test
    fun `无来源_UNVERIFIABLE`() {
        val d = dir("readme.md" to "没有任何数字的说明。")
        val rs = NumericClaimGate.check("12 个数字相加得 100，已写入 sum.txt。", d)
        val r = rs.single { it.kind == NumericClaimGate.Kind.SUM }
        assertEquals(NumericClaimGate.Status.UNVERIFIABLE, r.status)
        assertEquals(NumericClaimGate.UNVERIFIED_NO_SOURCE, r.detail)
    }

    @Test
    fun `来源不唯一_UNVERIFIABLE_不猜`() {
        val d = dir(
            "notes/nums.txt" to "1\n2\n3",
            "notes/nums2.txt" to "4\n5\n6",
        )
        val rs = NumericClaimGate.check("12 个数字相加得 21，已写入 sum.txt。", d)
        val r = rs.single { it.kind == NumericClaimGate.Kind.SUM }
        assertEquals(NumericClaimGate.Status.UNVERIFIABLE, r.status)
        assertEquals(NumericClaimGate.UNVERIFIED_AMBIGUOUS, r.detail)
    }

    @Test
    fun `交付物排除后_来源唯一化`() {
        // sum.txt 是产出（exclude），不算来源——否则"来源不唯一"把唯一真来源顶掉
        val d = dir(nums, "sum.txt" to "59220")
        val rs = NumericClaimGate.check("12 个数字相加得 59220，已写入 sum.txt。", d, exclude = setOf("sum.txt"))
        assertEquals(NumericClaimGate.Status.VERIFIED, rs.single { it.kind == NumericClaimGate.Kind.SUM }.status)
    }

    @Test
    fun `来源读不了_UNVERIFIABLE`() {
        val d = dir("notes/nums.bin" to "407\u00003859\u0000620")
        val rs = NumericClaimGate.check("3 个数字相加得 4386，已写入 sum.txt。", d)
        // 二进制源读文本失败 ⇒ 判不了（不是猜一个数）
        val r = rs.single { it.kind == NumericClaimGate.Kind.SUM }
        assertEquals(NumericClaimGate.Status.UNVERIFIABLE, r.status)
    }

    @Test
    fun `无算术自称_不产任何结果`() {
        val d = dir(nums)
        assertTrue(NumericClaimGate.check("已收到，我会尽快处理。", d).isEmpty())
    }
}
