// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「本地已有索引那个整包」的**选择规则**（E-049）。
 *
 * 背景：真机上一次「补指纹」白下了 192 MB —— 缓存里躺着的就是索引那个包，检查更新却照样
 * 把 URL 交给下载路径。修法是在**动手之前**先按大小把候选挑出来，再由安装线程逐字节验 sha。
 *
 * 这里只测"挑谁"这一层：**不测 sha**（那是 [RootfsDownloader.sha256Of] 的职责，
 * 已在 RootfsEnvTrustTest 里覆盖）。刻意如此：大小先筛是省时间的关键，
 * 但**大小相符绝不等于内容正确** —— 选中的只是候选，不是证据。
 */
class RootfsLocalCandidateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, bytes: Long): File =
        tmp.newFile(name).apply { writeBytes(ByteArray(bytes.toInt())) }

    /** 名字对得上（按 URL 猜出来的那个）且大小相符 ⇒ 直接选它，不去翻缓存列表。 */
    @Test
    fun preferredMatchingSizeWins() {
        val preferred = file("debian-13.7-base-arm64.tar.zst", 8)
        val other = file("debian-13.6-base-arm64.tar.zst", 8)
        assertEquals(preferred, RootfsCache.pickLocalCandidate(preferred, listOf(other), 8))
    }

    /** 名字那个不存在（或大小不符）⇒ 退到缓存列表里按大小找。 */
    @Test
    fun fallsBackToCachedListWhenPreferredMisses() {
        val preferred = File(tmp.root, "debian-13.7-base-arm64.tar.zst") // 不存在
        val hit = file("debian-13.6-base-arm64.tar.zst", 8)
        assertEquals(hit, RootfsCache.pickLocalCandidate(preferred, listOf(hit), 8))
    }

    /** 名字那个大小不符（下了一半的残片）⇒ 不能选它，继续找列表。 */
    @Test
    fun partialPreferredIsRejected() {
        val partial = file("debian-13.7-base-arm64.tar.zst", 4)
        val whole = file("debian-13.7-base-arm64.tar.zst.part", 8)
        assertEquals(whole, RootfsCache.pickLocalCandidate(partial, listOf(whole), 8))
    }

    /** 谁都不符 ⇒ null（宁可下载，也不拿大小对不上的包去重装）。 */
    @Test
    fun noSizeMatchReturnsNull() {
        val a = file("debian-13.7-base-arm64.tar.zst", 4)
        assertNull(RootfsCache.pickLocalCandidate(a, listOf(a), 8))
    }

    /** 索引没给大小（0 / 负数）⇒ 不猜。 */
    @Test
    fun nonPositiveSizeReturnsNull() {
        val a = file("debian-13.7-base-arm64.tar.zst", 8)
        assertNull(RootfsCache.pickLocalCandidate(a, listOf(a), 0))
        assertNull(RootfsCache.pickLocalCandidate(a, listOf(a), -1))
    }

    /** 目录恰好也是这个长度（文件系统元数据巧合）⇒ 必须排除，只有普通文件算数。 */
    @Test
    fun directoriesAreNotCandidates() {
        val dir = tmp.newFolder("debian-13.7-base-arm64.tar.zst")
        val hit = file("debian-13.6-base-arm64.tar.zst", 8)
        assertEquals(hit, RootfsCache.pickLocalCandidate(dir, listOf(dir, hit), 8))
    }
}
