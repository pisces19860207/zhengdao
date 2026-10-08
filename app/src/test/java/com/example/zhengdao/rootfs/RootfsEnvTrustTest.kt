// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **信任锚**单测：全量安装写不写 `env=` 行，只看"索引 sha256 是否等于实际校验通过的 sha256"。
 *
 * 为什么需要这条规则（`RootfsInstaller.envForMarker` 的 KDoc 里也写了）：索引是 CI 产出的，
 * 可能因为流水线半途失败/上传错序而指向一个"还不是这次这个包"的 env；而标记里的 env 是
 * 后续增量更新的**唯一基线**，一旦写错，下一次就会拿基线不匹配的补丁去改用户的环境。
 * 所以宁可少写一次 env（下次老实全量），也不能写错。
 *
 * 这里覆盖三种情形：一致（写）、不一致（不写）、索引/边车拿不到（不写），
 * 并顺带验证"写进标记的内容真的能被 installedEnv 读回来"。
 *
 * 2026-10-08 追加第二组：**重装同一个包时不许把基线抹掉**（[RootfsInstaller.envForReinstall]）。
 * 真机 bug 的原始现场：修复前「已是最新版本（13.7，环境 51e1cc0c32f099aa）」→ 点「修复环境」→
 * 再检查变成「缺少环境指纹记录」⇒ 修一次环境，白下一次 192 MB。
 */
class RootfsEnvTrustTest {

    private val idxEnv = "aabbccddeeff0011"
    private val actualSha = "a".repeat(64)
    private val otherSha = "b".repeat(64)
    private val distro = "debian-13.7"

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "zd-trust-${System.nanoTime()}").apply { mkdirs() }

    /** 模拟 `install()` 落标记那一步：env / archiveSha256 为 null 就不写对应行。 */
    private fun writeMarkerLikeInstall(rootfsDir: File, env: String?, archiveSha256: String? = null) {
        RootfsMarker.write(rootfsDir, distro, env, RootfsMarker.nowIso(), archiveSha256)
    }

    @Test
    fun `索引 sha 与实际校验值一致时写 env 且能读回`() {
        val root = tempRoot()
        try {
            val env = RootfsInstaller.envForMarker(idxEnv, actualSha, actualSha)
            assertEquals(idxEnv, env)

            writeMarkerLikeInstall(root, env)
            assertEquals(idxEnv, RootfsMarker.installedEnv(root))           // 读回同一个值
            assertEquals(distro, RootfsMarker.read(root)?.distro)           // distro 照旧保留
            assertFalse(RootfsMarker.fileIn(root).readText().contains("env=null"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `索引 sha 与实际校验值不一致时安装照做但不写 env（下次全量）`() {
        val root = tempRoot()
        try {
            // 索引说这个包的 sha 是 actualSha，但边车/实际校验通过的是别的值（索引陈旧）
            assertNull(RootfsInstaller.envForMarker(idxEnv, actualSha, otherSha))
            assertNull(RootfsInstaller.envForMarker(idxEnv, otherSha, actualSha)) // 反着也一样

            writeMarkerLikeInstall(root, RootfsInstaller.envForMarker(idxEnv, actualSha, otherSha))
            val text = RootfsMarker.fileIn(root).readText()
            assertFalse("不一致时标记里不该出现 env=：\n$text", text.contains("env="))
            // 标记仍写出来了（安装成功），但本地"无版本记录" ⇒ 下次走全量
            assertNull(RootfsMarker.installedEnv(root))
            assertEquals(distro, RootfsMarker.read(root)?.distro)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `索引或校验值缺失时不写 env`() {
        assertNull(RootfsInstaller.envForMarker(null, actualSha, actualSha))       // 索引取不到
        assertNull(RootfsInstaller.envForMarker(idxEnv, null, actualSha))          // 索引没有 sha256
        assertNull(RootfsInstaller.envForMarker(idxEnv, "", actualSha))
        assertNull(RootfsInstaller.envForMarker(idxEnv, actualSha, null))          // 边车拿不到校验值
        assertNull(RootfsInstaller.envForMarker(idxEnv, actualSha, ""))
        assertNull(RootfsInstaller.envForMarker(null, null, null))
    }

    @Test
    fun `env 比较忽略大小写与空白并统一成小写`() {
        assertEquals(idxEnv, RootfsInstaller.envForMarker("AABBCCDDEEFF0011", "$actualSha\n", "  $actualSha  "))
        assertEquals(idxEnv, RootfsInstaller.envForMarker(idxEnv, actualSha.uppercase(), actualSha))
    }

    // ── 「重装同一份包」保留基线（2026-10-08 真机 bug 的回归测试） ──

    @Test
    fun `重装同一个包时 envForReinstall 把 env 原样写回（修复环境不再抹掉基线）`() {
        val root = tempRoot()
        try {
            // 当年那次全量安装：env 与那个包的 sha256 都记在标记里
            writeMarkerLikeInstall(root, idxEnv, actualSha)
            // 修复/回退时现算 sha256Of(缓存包) 与标记里的比 ⇒ 相等就写回同一个 env
            assertEquals(idxEnv, RootfsInstaller.envForReinstall(root, actualSha))
            // 容错：大小写与首尾空白（人工核对副本、或换过摘要工具）
            assertEquals(idxEnv, RootfsInstaller.envForReinstall(root, actualSha.uppercase()))
            assertEquals(idxEnv, RootfsInstaller.envForReinstall(root, "  $actualSha  "))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `包不匹配或标记没记 sha 时 envForReinstall 返回 null（照装但不写 env）`() {
        val root = tempRoot()
        try {
            // ① 换了另一个包（回退到别的版本）⇒ 对不上，不能凭猜写回旧基线
            writeMarkerLikeInstall(root, idxEnv, actualSha)
            assertNull(RootfsInstaller.envForReinstall(root, otherSha))
            // ② 老标记（2026-10-08 之前装的，没有 archive-sha256 行）⇒ 一律不做推断
            writeMarkerLikeInstall(root, idxEnv, null)
            assertNull(RootfsInstaller.envForReinstall(root, actualSha))
            // ③ 调用方拿不到包 sha（用户自选文件那条路）⇒ 不做推断
            writeMarkerLikeInstall(root, idxEnv, actualSha)
            assertNull(RootfsInstaller.envForReinstall(root, null))
            assertNull(RootfsInstaller.envForReinstall(root, ""))
            assertNull(RootfsInstaller.envForReinstall(root, "   "))
            // ④ 标记里的 sha 形态不对（人工改坏 / 半个 sha）⇒ 视同"没记过"
            RootfsMarker.write(root, distro, idxEnv, null, "not-a-sha")
            assertNull(RootfsInstaller.envForReinstall(root, actualSha))
            RootfsMarker.write(root, distro, idxEnv, null, actualSha.dropLast(1))
            assertNull(RootfsInstaller.envForReinstall(root, actualSha))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `重装同源包但标记里本来没有 env 时不会凭空造一个`() {
        val root = tempRoot()
        try {
            RootfsMarker.write(root, distro, null, null, actualSha)
            assertNull(RootfsInstaller.envForReinstall(root, actualSha))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sha256Of 与一次性摘要一致且 verifySha256 忽略大小写`() {
        val root = tempRoot()
        val f = File(root, "pkg.bin")
        try {
            // 300 KB：必须跨过 128 KB 的缓冲边界，才能证明是"流式且不丢尾巴"
            val bytes = ByteArray(300_000) { (it % 251).toByte() }
            f.writeBytes(bytes)
            val expected = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, RootfsDownloader.sha256Of(f))
            RootfsDownloader.verifySha256(f, expected.uppercase())   // 边车文件写成大写也不该拦
            var threw = false
            try {
                RootfsDownloader.verifySha256(f, "0".repeat(64))
            } catch (_: Throwable) {
                threw = true
            }
            assertTrue("sha 不符时必须抛异常（否则信任锚形同虚设）", threw)
        } finally {
            root.deleteRecursively()
        }
    }
}
