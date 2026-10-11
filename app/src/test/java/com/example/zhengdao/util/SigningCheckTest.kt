// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 签名自检里唯一能本机测的部分：**字节 → 大写十六进制**（E-065）。
 *
 * `SigningCheck.check()` 本身要 Context 与真实安装包，只能在真机上验（见 ERRATA E-065 的验证一节）；
 * 这里守住的是最容易写错、也最容易被忽略的一环 —— 格式。格式一旦变了（小写、带冒号、少补零），
 * 官方包会被误判成"非官方"，那是比不检查更糟的结果。
 */
class SigningCheckTest {

    @Test
    fun `空数组是空串`() {
        assertEquals("", SigningCheck.hexOf(ByteArray(0)))
    }

    @Test
    fun `单字节补零到大写两位`() {
        assertEquals("00", SigningCheck.hexOf(byteArrayOf(0)))
        assertEquals("0F", SigningCheck.hexOf(byteArrayOf(0x0F)))
        assertEquals("FF", SigningCheck.hexOf(byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun `负数字节按无符号处理`() {
        // Kotlin 里 ByteArray 的 -1 就是 0xFF；写成有符号会得到 "FFFFFFFF"
        assertEquals("FF80", SigningCheck.hexOf(byteArrayOf(0xFF.toByte(), 0x80.toByte())))
    }

    @Test
    fun `输出是大写且不带冒号`() {
        val hex = SigningCheck.hexOf(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        assertEquals("DEADBEEF", hex)
        assertEquals(hex.uppercase(), hex)
        assertEquals(false, hex.contains(":"))
    }

    @Test
    fun `32 字节输出 64 个字符与官方常量同形`() {
        val hex = SigningCheck.hexOf(ByteArray(32) { 0xAB.toByte() })
        assertEquals(64, hex.length)
        assertEquals(64, SigningCheck.OFFICIAL_RELEASE_SHA256.length)
        assertEquals(true, SigningCheck.OFFICIAL_RELEASE_SHA256.all { it in "0123456789ABCDEF" })
    }

    @Test
    fun `两枚官方指纹都是 64 位大写十六进制`() {
        assertEquals(2, SigningCheck.OFFICIAL_SHA256S.size)
        SigningCheck.OFFICIAL_SHA256S.forEach { fp ->
            assertEquals("指纹长度: $fp", 64, fp.length)
            assertEquals("指纹须为大写十六进制: $fp", true, fp.all { it in "0123456789ABCDEF" })
        }
    }

    @Test
    fun `白名单同时含新 release 与历史 debug 两枚且互不相同`() {
        // E-099：2026-10-11 起正式包改用独立 release key；旧包（debug key 签）仍是官方包，
        // 不该被报成"非官方"。两枚要是写成一个，白名单就等于白做。
        assertEquals(true, SigningCheck.OFFICIAL_RELEASE_SHA256 in SigningCheck.OFFICIAL_SHA256S)
        assertEquals(true, SigningCheck.OFFICIAL_LEGACY_DEBUG_SHA256 in SigningCheck.OFFICIAL_SHA256S)
        assertEquals(
            "两枚指纹不能相同（相同 = 白名单实际只剩一把）",
            false,
            SigningCheck.OFFICIAL_RELEASE_SHA256 == SigningCheck.OFFICIAL_LEGACY_DEBUG_SHA256,
        )
    }
}
