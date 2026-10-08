// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 */
class RootfsEnvTrustTest {

    private val idxEnv = "aabbccddeeff0011"
    private val actualSha = "a".repeat(64)
    private val otherSha = "b".repeat(64)
    private val distro = "debian-13.7"

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "zd-trust-${System.nanoTime()}").apply { mkdirs() }

    /** 模拟 `install()` 落标记那一步：env 为 null 就不写 `env=` 行。 */
    private fun writeMarkerLikeInstall(rootfsDir: File, env: String?) {
        RootfsMarker.write(rootfsDir, distro, env, RootfsMarker.nowIso())
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
}
