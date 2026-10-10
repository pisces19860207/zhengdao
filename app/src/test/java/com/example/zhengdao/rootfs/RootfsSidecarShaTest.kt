// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本地包该拿哪个 sha256 去校验」的**决策规则**（E-053）。
 *
 * 背景（真机假失败）：终端页弹 `安装失败：SHA256 校验失败：actual=d80639e7dc5c…`。
 * 包没坏、网络也没问题 —— 是这条路径**优先信了 `debian-….tar.zst.sha256` 这个本地边车**，
 * 而它是远端换包之前留下的过期副本（`d12cd1d3…`）。**全仓没有任何代码写它**（只读不写＝
 * 没人维护的死文件，E-033 记过同款），远端一换包它必然变馊。
 *
 * 规则（见 [RootfsCache.pickExpectedSha]）：① 本地包"长得就是索引那个包"（文件名 == 索引
 * url 末段 且 字节数 == 索引 size）⇒ 用**被签名背书的索引** sha；② 否则按这个文件自己的
 * 说法：本地边车 → 线上 `$url.sha256`（用户留着旧包本来就可能要装旧版本，索引描述的是
 * "最新那个包"，不是"这个文件"）；③ 三样都没有 ⇒ 本机**上次装成的那个包**的 sha256
 * （P3-6，2026-10-10：这一档以前是"跳过校验照装"，现在至少要能对上上次那个包）。
 */
class RootfsSidecarShaTest {

    private val name = "debian-13.7-base-arm64.tar.zst"
    private val indexUrl =
        "https://github.com/pisces19860207/zhengdao/releases/download/latest/$name"
    private val indexSha = "d80639e7dc5c055fb731e5af62b6789d6ac4d00d07dafe9717f6aed726174f02"
    private val staleSha = "d12cd1d37e0c4767e6730fd709eb796f5fe996b27e94e40b480bdb96afec9e27"
    private val size = 201_632_517L

    /** 真机那条：索引与本地边车冲突时**必须用索引**（旧代码用边车 ⇒ 误报"安装失败"）。 */
    @Test
    fun indexWinsOverStaleSidecar() {
        val c = RootfsCache.pickExpectedSha(name, size, indexUrl, size, indexSha, staleSha, indexSha)
        assertEquals(indexSha, c.sha)
        assertEquals("索引", c.source)
        assertTrue("边车过期要被标出来（校验通过后自愈重写）", c.staleSidecar)
    }

    /** 边车与索引一致 ⇒ 正常情况，不标记过期、不重写。 */
    @Test
    fun matchingSidecarIsNotStale() {
        val c = RootfsCache.pickExpectedSha(name, size, indexUrl, size, indexSha, indexSha, null)
        assertEquals(indexSha, c.sha)
        assertFalse(c.staleSidecar)
    }

    /** 大小写/首尾空白不该把"一致"判成"过期"（sha 十六进制大小写无意义）。 */
    @Test
    fun sidecarComparisonIgnoresCaseAndBlank() {
        val c = RootfsCache.pickExpectedSha(
            name, size, indexUrl, size, indexSha, "  ${indexSha.uppercase()}  ", null,
        )
        assertEquals(indexSha, c.sha)
        assertFalse(c.staleSidecar)
    }

    /** 拿不到索引（离线 / 没签名）⇒ 退回本地边车，行为与修前一致。 */
    @Test
    fun fallsBackToSidecarWithoutIndex() {
        val c = RootfsCache.pickExpectedSha(name, size, null, 0, null, staleSha, indexSha)
        assertEquals(staleSha, c.sha)
        assertEquals("本地 .sha256", c.source)
        assertFalse(c.staleSidecar)
    }

    /** 用户留着的是**别的版本**（名字不同）⇒ 不能拿索引去卡它，用这个文件自己的边车。 */
    @Test
    fun differentNameIsNotTheIndexedPackage() {
        val c = RootfsCache.pickExpectedSha(
            "debian-13.6-base-arm64.tar.zst", size, indexUrl, size, indexSha, staleSha, indexSha,
        )
        assertEquals(staleSha, c.sha)
        assertEquals("本地 .sha256", c.source)
    }

    /** 同名但字节数不同（老构建 / 下了一半）⇒ 也不是索引那个包。 */
    @Test
    fun differentSizeIsNotTheIndexedPackage() {
        val c = RootfsCache.pickExpectedSha(name, size - 1, indexUrl, size, indexSha, staleSha, indexSha)
        assertEquals(staleSha, c.sha)
        assertEquals("本地 .sha256", c.source)
    }

    /** 下载路径：文件还没落盘、大小未知（`<= 0`）⇒ 只按名字认索引，拿它的 sha 当下载校验值。 */
    @Test
    fun downloadPathWithUnknownSizeUsesIndexByName() {
        val c = RootfsCache.pickExpectedSha(name, -1L, indexUrl, size, indexSha, null, indexSha)
        assertEquals(indexSha, c.sha)
        assertEquals("索引", c.source)
    }

    /** 索引没给 sha（空串/空白）⇒ 当作没有索引，继续往下退。 */
    @Test
    fun blankIndexShaFallsThrough() {
        val c = RootfsCache.pickExpectedSha(name, size, indexUrl, size, "   ", staleSha, null)
        assertEquals(staleSha, c.sha)
        assertEquals("本地 .sha256", c.source)
    }

    /** 边车是空文件（下过一半的残留）⇒ 当没有，用线上那份。 */
    @Test
    fun blankSidecarFallsToOnline() {
        val c = RootfsCache.pickExpectedSha(name, size, null, 0, null, "  \n", indexSha)
        assertEquals(indexSha, c.sha)
        assertEquals("线上 .sha256", c.source)
    }

    /** 四个来源都没有 ⇒ null（调用方**拒绝安装**，P3-6；修前是"跳过校验照装"）。 */
    @Test
    fun nothingAvailableReturnsNull() {
        val c = RootfsCache.pickExpectedSha(name, size, null, 0, null, null, null)
        assertNull(c.sha)
        assertEquals("无", c.source)
        assertFalse(c.staleSidecar)
    }

    /** P3-6：索引/边车/线上全拿不到，但本机记得"上次装成的那个包" ⇒ 用它兜底。 */
    @Test
    fun rememberedShaIsTheLastResort() {
        val c = RootfsCache.pickExpectedSha(name, size, null, 0, null, null, null, staleSha)
        assertEquals(staleSha, c.sha)
        assertEquals("上次装过的包", c.source)
        assertFalse(c.staleSidecar)
    }

    /** P3-6：兜底不得抢前三个来源的位置——线上有说法就听线上那份。 */
    @Test
    fun rememberedShaDoesNotOutrankOnline() {
        val c = RootfsCache.pickExpectedSha(name, size, null, 0, null, null, indexSha, staleSha)
        assertEquals(indexSha, c.sha)
        assertEquals("线上 .sha256", c.source)
    }

    /** P3-6：记忆是空白（老标记 / 读坏）⇒ 当作没有，仍返回 null（调用方拒绝安装）。 */
    @Test
    fun blankRememberedShaIsIgnored() {
        val c = RootfsCache.pickExpectedSha(name, size, null, 0, null, null, null, "  \n")
        assertNull(c.sha)
        assertEquals("无", c.source)
    }

    /** 索引 url 带查询串时，取末段前要先掐掉 `?…`（镜像地址常见）。 */
    @Test
    fun queryStringIsStrippedFromIndexUrl() {
        val c = RootfsCache.pickExpectedSha(
            name, size, "$indexUrl?token=abc", size, indexSha, staleSha, null,
        )
        assertEquals(indexSha, c.sha)
        assertTrue(c.staleSidecar)
    }
}
