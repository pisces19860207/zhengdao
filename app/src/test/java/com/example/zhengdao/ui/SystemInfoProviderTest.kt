// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * node 二进制版本扫描单测（[SystemInfoProvider.scanNodeVersion]）。
 *
 * 背景（2026-10-07 真机实测）：系统信息卡里 Node 一直显示 "v26.x"——环境包里的
 * zhengdao-rootfs.info 只记了大版本（构建期常量），而 node 的精确版本以 ASCII
 * 字符串编译在二进制的只读数据段里（实测 v26.10.0 落在 149 MB 文件的 46 MB 处）。
 * 只能顺序扫，所以边界必须锁死：误匹配（二进制里还有 v8、openssl 等版本串）、
 * 两段版本号、多余的点、以及跨块边界，都要有确定行为。
 */
class SystemInfoProviderTest {

    private fun temp(bytes: ByteArray): File =
        File.createTempFile("node-scan", ".bin").apply { writeBytes(bytes) }

    @Test
    fun `读出完整三段版本号`() {
        val f = temp("ELF....v26.10.0....\u0000".toByteArray())
        assertEquals("v26.10.0", SystemInfoProvider.scanNodeVersion(f, "26"))
    }

    @Test
    fun `主版本约束住——不先去撞别的版本串`() {
        // node 二进制里同时存在 v8（V8 引擎）、openssl 等版本串，泛匹配会先命中它们
        val f = temp("v8.1.2 \u0000 v22.3.1 \u0000 v26.10.0".toByteArray())
        assertEquals("v26.10.0", SystemInfoProvider.scanNodeVersion(f, "26"))
    }

    @Test
    fun `只有两段不算版本号`() {
        val f = temp("v26.10 \u0000".toByteArray())
        assertNull(SystemInfoProvider.scanNodeVersion(f, "26"))
    }

    @Test
    fun `三段之后多余的点不吞进结果`() {
        // "v26.10.0.1" 这类（如某些编译器版本串）只能取到三段为止
        val f = temp("v26.10.0.1".toByteArray())
        assertEquals("v26.10.0", SystemInfoProvider.scanNodeVersion(f, "26"))
    }

    @Test
    fun `版本串横跨块边界也能取到`() {
        // 读块 256 KiB：把 "v26" 留在第一块末尾、".10.0" 落到第二块
        val head = ByteArray((1 shl 18) - 3) { 0x78 } // 'x'
        val f = temp(head + "v26.10.0".toByteArray())
        assertEquals("v26.10.0", SystemInfoProvider.scanNodeVersion(f, "26"))
    }

    @Test
    fun `主版本缺失时不做猜测`() {
        val f = temp("v26.10.0".toByteArray())
        assertNull(SystemInfoProvider.scanNodeVersion(f, null))
        assertNull(SystemInfoProvider.scanNodeVersion(f, ""))
    }

    @Test
    fun `空文件与不存在的文件都返回 null`() {
        assertNull(SystemInfoProvider.scanNodeVersion(temp(ByteArray(0)), "26"))
        assertNull(SystemInfoProvider.scanNodeVersion(File("/no/such/file"), "26"))
    }

    // ── 内嵌 uv 版本（取自 hermes 工具目录名）──

    @Test
    fun `从 hermes 工具目录名读出 uv 版本`() {
        assertEquals("0.12.3", SystemInfoProvider.uvVersionFromDirName("uv-0.12.3-linux-arm64"))
        assertEquals("0.8.15", SystemInfoProvider.uvVersionFromDirName("uv-0.8.15-linux-arm64"))
    }

    @Test
    fun `不是 uv 的目录与无版本段的目录都不认`() {
        assertNull(SystemInfoProvider.uvVersionFromDirName("python-3.14.7-linux-arm64"))
        assertNull(SystemInfoProvider.uvVersionFromDirName("node-26.7.0-linux-arm64"))
        assertNull(SystemInfoProvider.uvVersionFromDirName("uv-linux-arm64"))
        assertNull(SystemInfoProvider.uvVersionFromDirName("uvx-0.12.3-linux-arm64"))
    }
}
