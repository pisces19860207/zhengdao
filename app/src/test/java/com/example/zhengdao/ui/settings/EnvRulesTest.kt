// 第八十批 · §B 80（2026-10-20）：设置页「环境与更新」纯判定 [EnvRules] 的单测。
//
// 覆盖 5 个抽出来的片段：更新判定（含"已是最新"判据）、老 manifest 解析、
// 安装状态展示、修复包合格条件、下载进度节流。
// 其中更新判定这一段是**第一次**被单测钉住 —— 它决定"弹不弹确认框"，
// 2026-10-08 那次「同版本被叫成发现新版本」的真机投诉就出在这里。
package com.example.zhengdao.ui.settings

import com.example.zhengdao.ui.InstallProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvRulesTest {

    // ── ① 版本号展示 ─────────────────────────────────────────────────────

    @Test
    fun `版本号_索引给了version就用version`() {
        assertEquals("13.7", EnvRules.displayVersion("13.7", "debian-13.7", "未知版本"))
    }

    @Test
    fun `版本号_version空时退到distro`() {
        assertEquals("debian-13.7", EnvRules.displayVersion("", "debian-13.7", "未知版本"))
    }

    @Test
    fun `版本号_两者都空才用兜底文案`() {
        assertEquals("未知版本", EnvRules.displayVersion("  ", "", "未知版本"))
    }

    // ── ② 已是最新（唯一一条"要不要弹确认框"的判据）─────────────────────

    @Test
    fun `已是最新_指纹相同_大小写不敏感`() {
        assertTrue(EnvRules.isUpToDate("AbC123", "abc123"))
    }

    @Test
    fun `已是最新_指纹不同就不算`() {
        assertFalse(EnvRules.isUpToDate("abc123", "abc124"))
    }

    @Test
    fun `已是最新_老安装没有env行时不算最新_否则永远补不上指纹`() {
        assertFalse(EnvRules.isUpToDate(null, "abc123"))
    }

    // ── ③ 更新判定：8 种口径 ─────────────────────────────────────────────

    /** 计数用的取值闭包：同时用来证明"判定只在需要时才读盘"。 */
    private class Probes(val version: String, val cached: Long?) {
        var versionReads = 0
            private set
        var cachedReads = 0
            private set

        fun readVersion(): String {
            versionReads++
            return version
        }

        fun readCached(): Long? {
            cachedReads++
            return cached
        }
    }

    private fun plan(
        localEnv: String?,
        indexEnv: String = "env-new",
        indexVersion: String = "13.7",
        indexSize: Long = 300_000_000L,
        probes: Probes = Probes(version = "13.7", cached = null),
        patchFrom: String? = null,
        patchSize: Long = 0L,
    ): EnvRules.UpdatePlan = EnvRules.planUpdate(
        localEnv = localEnv,
        indexEnv = indexEnv,
        indexVersion = indexVersion,
        indexSize = indexSize,
        localVersion = probes::readVersion,
        cachedBytes = probes::readCached,
        patchFrom = patchFrom,
        patchSize = patchSize,
    )

    @Test
    fun `判定_老安装且缓存有同版本整包_提示本地已有且不写补丁`() {
        val p = plan(localEnv = null, probes = Probes("13.7", cached = 311_000_000L))
        assertEquals(EnvRules.UpdateNotice.LocalEnvMissingCached, p.notice)
        // 文案里的字节数取缓存包大小，不是索引大小
        assertEquals(311_000_000L, p.sizeBytes)
        assertFalse("这条口径不走增量", p.wantPatch)
    }

    @Test
    fun `判定_老安装缓存有包但版本不同_退成普通的本地已有`() {
        val p = plan(localEnv = null, probes = Probes("13.6", cached = 311_000_000L))
        assertEquals(EnvRules.UpdateNotice.LocalCached, p.notice)
        assertEquals(311_000_000L, p.sizeBytes)
    }

    @Test
    fun `判定_老安装没有缓存但版本相同_提示补指纹`() {
        val p = plan(localEnv = null, indexSize = 298_000_000L, probes = Probes("13.7", cached = null))
        assertEquals(EnvRules.UpdateNotice.LocalEnvMissingFull, p.notice)
        assertEquals(298_000_000L, p.sizeBytes)
    }

    @Test
    fun `判定_老安装没有缓存版本也不同_确实有新版`() {
        val p = plan(localEnv = null, probes = Probes("13.6", cached = null))
        assertEquals(EnvRules.UpdateNotice.NewVersionNoLocalRecord, p.notice)
    }

    @Test
    fun `判定_指纹一致是最新_且一个盘都不读`() {
        val probes = Probes(version = "13.7", cached = 311_000_000L)
        val p = plan(localEnv = "env-new", probes = probes)
        assertEquals(EnvRules.UpdateNotice.UpToDate, p.notice)
        // 迁移前"已是最新"这条主路径上不读本地版本、也不列缓存目录；
        // 传闭包就是为了保住这一点（改成提前算值的话这里会是 1/1）。
        assertEquals("已是最新不该读本地版本", 0, probes.versionReads)
        assertEquals("已是最新不该列缓存目录", 0, probes.cachedReads)
    }

    @Test
    fun `判定_有新版本且缓存里已有整包_字节数取缓存包`() {
        val p = plan(localEnv = "env-old", probes = Probes("13.6", cached = 310_000_000L))
        assertEquals(EnvRules.UpdateNotice.NewVersionCached, p.notice)
        assertEquals(310_000_000L, p.sizeBytes)
    }

    @Test
    fun `判定_有新版本且补丁基线正好是本机_走增量`() {
        val p = plan(
            localEnv = "env-old",
            probes = Probes("13.6", cached = null),
            patchFrom = "env-old",
            patchSize = 4_200_000L,
        )
        assertEquals(EnvRules.UpdateNotice.NewVersionPatch, p.notice)
        assertTrue("基线相符就必须走增量", p.wantPatch)
        // 增量文案里显示的是补丁大小，不是整包大小
        assertEquals(4_200_000L, p.sizeBytes)
    }

    @Test
    fun `判定_补丁基线不是本机_退回全量`() {
        val p = plan(
            localEnv = "env-old",
            indexSize = 300_000_000L,
            probes = Probes("13.6", cached = null),
            patchFrom = "env-other",
            patchSize = 4_200_000L,
        )
        assertEquals(EnvRules.UpdateNotice.NewVersionFull, p.notice)
        assertFalse(p.wantPatch)
        assertEquals(300_000_000L, p.sizeBytes)
    }

    @Test
    fun `判定_索引没给补丁时也走全量`() {
        val p = plan(localEnv = "env-old", probes = Probes("13.6", cached = null), patchFrom = null)
        assertEquals(EnvRules.UpdateNotice.NewVersionFull, p.notice)
        assertFalse(p.wantPatch)
    }

    @Test
    fun `判定_索引env为空串时按有新版本处理_不崩`() {
        // 现网不会走到（页面外层已判 env 非空），这里只钉住"不抛异常、给一个确定的结论"。
        val p = plan(localEnv = "env-old", indexEnv = "", probes = Probes("13.6", cached = null))
        assertEquals(EnvRules.UpdateNotice.NewVersionFull, p.notice)
    }

    @Test
    fun `判定_每次判定最多各读一次盘`() {
        val probes = Probes("13.6", cached = 310_000_000L)
        plan(localEnv = "env-old", probes = probes)
        assertEquals(0, probes.versionReads)
        assertEquals("有新版本时列一次缓存目录就够", 1, probes.cachedReads)
    }

    @Test
    fun `判定_结论与是否最新永远一致`() {
        // 两处判据同源：notice == UpToDate 当且仅当 isUpToDate 为真。
        for ((local, env) in listOf(
            "env-new" to "env-new",
            "ENV-NEW" to "env-new",
            "env-old" to "env-new",
            null to "env-new",
        )) {
            val p = plan(localEnv = local, indexEnv = env)
            assertEquals(
                "localEnv=$local indexEnv=$env",
                EnvRules.isUpToDate(local, env),
                p.notice == EnvRules.UpdateNotice.UpToDate,
            )
        }
    }

    // ── ④ 老 manifest 解析 ──────────────────────────────────────────────

    @Test
    fun `老feed_三个字段都抠得出来`() {
        val m = EnvRules.parseLegacyManifest(
            """{"version": "13.7", "url": "https://x/y.tar.zst", "sha256": "abc"}"""
        )
        assertEquals("13.7", m.version)
        assertEquals("https://x/y.tar.zst", m.url)
        assertEquals("abc", m.sha256)
    }

    @Test
    fun `老feed_缺sha256时只留前两项`() {
        val m = EnvRules.parseLegacyManifest("""{"version":"13.7","url":"https://x/y"}""")
        assertEquals("13.7", m.version)
        assertEquals("https://x/y", m.url)
        assertNull("没有 sha256 就该是 null，不猜", m.sha256)
    }

    @Test
    fun `老feed_原文认不出时三项都是null`() {
        val m = EnvRules.parseLegacyManifest("<html>404 Not Found</html>")
        assertNull(m.version)
        assertNull(m.url)
        assertNull(m.sha256)
    }

    @Test
    fun `老feed_空原文不崩`() {
        val m = EnvRules.parseLegacyManifest("")
        assertNull(m.version)
        assertNull(m.url)
    }

    // ── ⑤ 安装状态 → 展示与可点性 ───────────────────────────────────────

    @Test
    fun `状态_安装中只有进度_没有收尾行也没有重试`() {
        val v = EnvRules.phaseView(InstallProgress.Phase.Running)
        assertEquals("⏳", v.icon)
        assertFalse(v.isError)
        assertFalse("安装中不该出收尾行", v.showCloseRow)
        assertFalse(v.showRetry)
    }

    @Test
    fun `状态_装完是正常色_给知道了但不给重试`() {
        val v = EnvRules.phaseView(InstallProgress.Phase.Done)
        assertEquals("✅", v.icon)
        assertFalse(v.isError)
        assertTrue(v.showCloseRow)
        assertFalse("装完了不该再给重试", v.showRetry)
    }

    @Test
    fun `状态_失败要用错误色_并且必须留重试入口`() {
        val v = EnvRules.phaseView(InstallProgress.Phase.Failed)
        assertEquals("⚠️", v.icon)
        assertTrue("失败必须是错误色（error 色）", v.isError)
        assertTrue(v.showCloseRow)
        assertTrue("失败时必须给重试入口，否则清掉状态后就无路可走", v.showRetry)
    }

    // ── ⑥ 修复环境的本地包合格条件 ───────────────────────────────────────

    @Test
    fun `修复包_恰好100MB不算_必须严格大于`() {
        assertFalse(EnvRules.isRepairCandidate(isFile = true, sizeBytes = 100_000_000L))
        assertTrue(EnvRules.isRepairCandidate(isFile = true, sizeBytes = 100_000_001L))
    }

    @Test
    fun `修复包_不是文件就不算_哪怕够大`() {
        assertFalse(EnvRules.isRepairCandidate(isFile = false, sizeBytes = 900_000_000L))
    }

    @Test
    fun `修复包_碎片文件不算`() {
        assertFalse(EnvRules.isRepairCandidate(isFile = true, sizeBytes = 1024L))
    }

    // ── ⑦ 下载进度节流 ─────────────────────────────────────────────────

    @Test
    fun `进度_长度未知时不推`() {
        assertNull(EnvRules.progressPercent(done = 1024L, total = 0L))
        assertNull(EnvRules.progressPercent(done = 1024L, total = -1L))
    }

    @Test
    fun `进度_每10个百分点推一次`() {
        assertEquals(0, EnvRules.progressPercent(done = 0L, total = 1000L))
        assertEquals(10, EnvRules.progressPercent(done = 100L, total = 1000L))
        assertEquals(50, EnvRules.progressPercent(done = 500L, total = 1000L))
        assertEquals(100, EnvRules.progressPercent(done = 1000L, total = 1000L))
    }

    @Test
    fun `进度_没踩到整十档就不推`() {
        assertNull(EnvRules.progressPercent(done = 55L, total = 1000L))
        assertNull(EnvRules.progressPercent(done = 999L, total = 1000L))
    }

    @Test
    fun `进度_整数除法口径与迁移前一致_不四舍五入`() {
        // 109/1000 = 10.9% ⇒ 整数除法得 10（踩档），而不是 11
        assertEquals(10, EnvRules.progressPercent(done = 109L, total = 1000L))
    }
}
