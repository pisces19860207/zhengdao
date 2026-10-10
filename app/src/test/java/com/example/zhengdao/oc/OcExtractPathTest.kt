// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.oc

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-5（2026-10-10）：解 opencode 归档时，**条目名不许逃出目标目录**。
 *
 * 现场：`OcManager.extract()` 原先只有 `File(ocRoot, "usr/$rel")` 一句拼路径，于是
 * `data/data/com.termux/files/usr/a/../../../../shared_prefs/x.xml` 这种条目会被原样写到
 * App 私有目录之外——"用归档里的名字决定写到哪儿"。包虽然有 digest 校验，但那是防"传坏了"，
 * 不是防"上游被投毒"。
 *
 * 这套用例锁的是 [OcManager.resolveExtractTarget] 的判据：绝对路径 / `..` 段 / 空段 / NUL
 * 一律拒；通过之后还要 canonical 复核"归一化落点仍在根里"。
 */
class OcExtractPathTest {

    private val root: File by lazy {
        Files.createTempDirectory("zd-oc-extract-").toFile().also { it.mkdirs() }
    }

    private fun resolve(rel: String): File? = OcManager.resolveExtractTarget(root, rel)

    // ── 放行：正经条目 ──────────────────────────────────────────────────────

    @Test
    fun `普通条目落在根里`() {
        val f = resolve("bin/opencode")
        assertNotNull("bin/opencode 是正经条目，不该被拒", f)
        assertEquals(File(root, "bin/opencode").canonicalPath, f!!.path)
        assertTrue("落点必须在根里", f.path.startsWith(root.canonicalPath + File.separator))
    }

    @Test
    fun `深层条目照常放行`() {
        val f = resolve("share/opencode/tui/themes/dark.json")
        assertNotNull(f)
        assertTrue(f!!.path.startsWith(root.canonicalPath + File.separator))
    }

    @Test
    fun `中间的单点段交给规范化处理`() {
        // ./ 不越界，规范化之后仍在根里 ⇒ 放行（tar 里偶尔出现）
        val f = resolve("share/./opencode/x.json")
        assertNotNull(f)
        assertEquals(File(root, "share/opencode/x.json").canonicalPath, f!!.path)
    }

    // ── 拒绝：越界与花活 ────────────────────────────────────────────────────

    @Test
    fun `中段穿越直接拒绝`() {
        assertNull(resolve("a/../../../../shared_prefs/x.xml"))
        assertNull(resolve("bin/../.."))
        assertNull(resolve("../x"))
        assertNull(resolve(".."))
    }

    @Test
    fun `绝对路径拒绝`() {
        assertNull(resolve("/data/data/com.example.zhengdao/shared_prefs/x.xml"))
        assertNull(resolve("/etc/passwd"))
    }

    @Test
    fun `空段与空名字拒绝`() {
        assertNull(resolve(""))
        assertNull(resolve("a//b"))
        assertNull(resolve("a/"))
        assertNull(resolve("."))
    }

    @Test
    fun `带 NUL 的名字拒绝`() {
        assertNull(resolve("bin/opencode\u0000.txt"))
    }

    @Test
    fun `根不必先存在（规范化不看盘上有没有）`() {
        // 记录这条是为了避免误以为"目录不存在就会返回 null"：判据只做字符串层面的
        // canonical 复核，目录由 extract() 负责 mkdirs。
        val ghost = File(root, "ghost-root")
        val f = OcManager.resolveExtractTarget(ghost, "bin/opencode")
        assertNotNull(f)
        assertTrue(f!!.path.startsWith(ghost.canonicalPath + File.separator))
    }

    // ── 链接：resolveExtractTarget 管不到的那一半，由 extract() 跳过链接条目 ──

    @Test
    fun `顺着链接写出去也会被规范化挡下`() {
        val outside = Files.createTempDirectory("zd-oc-outside-").toFile()
        val link = File(root, "escape")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        if (!created) return // 无权限建符号链接（常见于 Windows）：这条只能靠 extract() 的链接判据
        val f = resolve("escape/evil.sh")
        assertNull("链接指向根外 ⇒ 不许顺着它写", f)
        assertFalse(File(outside, "evil.sh").exists())
    }

    // ── E-090（2026-10-11）：条目名 → 相对路径（剥前缀） ──────────────────────
    // 现场：E-082 把 `substringAfter("files/usr/")` 换成 `removePrefix("files/usr/")`，
    // 而条目名以 `data/data/com.termux/` 开头 ⇒ 一个字都没剥掉、整包落到 `oc/usr/data/…`，
    // 于是 `installed()` 判否，App 内装 opencode 报「释放后二进制缺失（包不完整？）」。

    @Test
    fun `真实条目名剥出 bin 下的相对路径`() {
        assertEquals("bin/opencode", OcManager.relPathOf("data/data/com.termux/files/usr/bin/opencode"))
    }

    @Test
    fun `剥完不许残留包布局前缀`() {
        val rel = OcManager.relPathOf("data/data/com.termux/files/usr/lib/libx.so")
        assertEquals("lib/libx.so", rel)
        assertFalse("rel 仍带 data/ ⇒ 就是 E-090 那个错位", rel!!.startsWith("data/"))
    }

    @Test
    fun `非本包条目与目录条目返回 null`() {
        assertNull(OcManager.relPathOf("data/data/com.termux/files/home/x"))
        assertNull(OcManager.relPathOf("usr/bin/opencode"))
        assertNull(OcManager.relPathOf("files/usr/bin/opencode"))
        assertNull(OcManager.relPathOf("data/data/com.termux/files/usr/"))
        assertNull(OcManager.relPathOf(""))
    }

    @Test
    fun `剥完的相对路径正好落在根下的 bin 里`() {
        val rel = OcManager.relPathOf("data/data/com.termux/files/usr/bin/opencode")!!
        val f = resolve(rel)
        assertNotNull(f)
        assertEquals(File(root, "bin/opencode").canonicalPath, f!!.path)
    }
}
