// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：Android instrumented test 官方文档、GNU tar 格式（POSIX ustar 头）。
package com.example.zhengdao.rust

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/**
 * 加固-1（2026-10-10 / E-085）的**真机**取证：解包流水线遇到越界软链时必须不落链接。
 *
 * 为什么单独一个类：真机取证要能按类名筛用例；既有 [CoreNativeExtractInstrumentedTest]
 * 的对拍用例会整装 311MB 真实归档（动设备上的 rootfs），这里只用手工合成的小 tar.gz。
 *
 * 为什么用 gzip 壳：解压流水线按**魔数**识别（zstd 主壳 + gzip 兜底），两条壳最后走同一段
 * tar 落盘代码，gzip 足以验到软链分支。
 *
 * ⚠️ 两个坑（都是本用例自己踩出来的）：
 * 1. **目录条目的 mode 必须是 0755**。第一版图省事一律给 0644，`set_mode(目录, 0644)` 抹掉
 *    目录的执行位后，往里面写下一个成员直接 `EACCES (os error 13)` —— 真实 rootfs 归档的
 *    目录都是 0755，所以线上不会踩到。用例里专门留了 0644 的**对照组**把这个失败模式钉住。
 * 2. 跑真机用例**不要用 `connectedAndroidTest`**（E-072）：它会卸载 App、清空整个运行环境。
 *    正确姿势是 `assembleDebugAndroidTest` → `adb install -r` 两个 APK →
 *    `adb shell am instrument -w -e class …`。
 */
@RunWith(AndroidJUnit4::class)
class CoreNativeSymlinkGuardInstrumentedTest {

    private fun log(s: String) = Log.i("BENCH", s)

    /** tar 成员：typeflag `'0'` 普通文件 / `'5'` 目录 / `'2'` 软链（linkname 落在 157 偏移）。 */
    private data class Member(
        val name: String,
        val type: Char = '0',
        val link: String = "",
        val mode: Long = 420L, // 0644
        val body: String = "",
    )

    /** ustar 头 512 B；校验位 = 先把 chksum 区填八个空格，整体求和后写成 6 位八进制 + NUL + 空格。 */
    private fun header(name: String, size: Long, type: Char, link: String, mode: Long): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            b.copyInto(h, off, 0, minOf(b.size, 512 - off))
        }
        fun octal(off: Int, len: Int, v: Long) = put(off, v.toString(8).padStart(len - 1, '0') + "\u0000")

        put(0, name)                  // name[100]
        octal(100, 8, mode)           // mode[8]
        octal(108, 8, 0L)             // uid[8]
        octal(116, 8, 0L)             // gid[8]
        octal(124, 12, size)          // size[12]
        octal(136, 12, 0L)            // mtime[12]
        put(148, "        ")          // chksum[8]（先填空格）
        h[156] = type.code.toByte()   // typeflag
        put(157, link)                // linkname[100]
        put(257, "ustar\u0000")       // magic
        put(263, "00")                // version
        var sum = 0L
        for (b in h) sum += (b.toInt() and 0xff)
        put(148, sum.toString(8).padStart(6, '0') + "\u0000 ")
        return h
    }

    private fun writeTar(tar: File, members: List<Member>) {
        tar.outputStream().buffered().use { out ->
            for (m in members) {
                val body = m.body.toByteArray(Charsets.UTF_8)
                out.write(header(m.name, body.size.toLong(), m.type, m.link, m.mode))
                if (body.isNotEmpty()) out.write(body)
                val pad = (512 - body.size % 512) % 512
                if (pad > 0) out.write(ByteArray(pad))
            }
            out.write(ByteArray(1024)) // 两个全零块收尾
        }
    }

    /** 跑一例（同一组成员、只换目录 mode），返回一句可读结果并打日志。 */
    private fun runCase(label: String, ctx: Context, dirMode: Long): String {
        val members = listOf(
            Member("sub/", '5', mode = dirMode),
            Member("sub/a.txt", '0', body = "hello"),
            // 树内软链：相对链接所在目录（Debian 里绝大多数就是这个形状）
            Member("sub/inside", '2', link = "../inside_target"),
            // 越界软链：一路 .. 出去
            Member("escape", '2', link = "../../../../etc/passwd"),
            // 越界软链：绝对形式（按 guest 根解释后仍在根外）
            Member("escape_abs", '2', link = "/../../etc/passwd"),
        )
        val tar = File(ctx.cacheDir, "lg_$label.tar")
        val gz = File(ctx.cacheDir, "lg_$label.tar.gz")
        val out = File(ctx.cacheDir, "lg_$label").apply { deleteRecursively(); mkdirs() }
        writeTar(tar, members)
        GZIPOutputStream(gz.outputStream().buffered()).use { z -> tar.inputStream().use { it.copyTo(z) } }

        val desc = runCatching { CoreNative.extract(gz.canonicalPath, out.canonicalPath, null) }.fold(
            onSuccess = { (entries, bytes, _) ->
                val inside = File(out, "sub/inside")
                val esc = File(out, "escape")
                val abs = File(out, "escape_abs")
                "ok entries=$entries bytes=$bytes " +
                    "inside.link=${Files.isSymbolicLink(inside.toPath())} " +
                    "escape.link=${Files.isSymbolicLink(esc.toPath())}/len=${esc.length()} " +
                    "escapeAbs.link=${Files.isSymbolicLink(abs.toPath())}/len=${abs.length()}"
            },
            onFailure = { "ERR ${it.javaClass.simpleName}: ${it.message}" },
        )
        log("LINK_GUARD[$label dirMode=$dirMode] $desc")
        return desc
    }

    @Test
    fun 越界软链_真机不落链接() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "libzhengdao_core.so 没加载起来——先跑 CoreNativeExtractInstrumentedTest.rust链路可用",
            CoreNative.isRustAvailable(),
        )

        // 对照组：目录 0644 ⇒ 目录少了执行位，往里写成员必然 EACCES（第一版用例的翻车原因）
        val control = runCase("dir0644", ctx, 420L)
        assertTrue("目录 0644 的对照组应当失败，否则说明这条诊断思路不对: $control", control.startsWith("ERR"))

        val ok = runCase("dir0755", ctx, 493L)
        assertTrue("正常归档（目录 0755）必须解压成功: $ok", ok.startsWith("ok"))

        val out = File(ctx.cacheDir, "lg_dir0755")
        val inside = File(out, "sub/inside")
        val escape = File(out, "escape")
        val escapeAbs = File(out, "escape_abs")
        assertTrue("树内软链必须照常落成链接", Files.isSymbolicLink(inside.toPath()))
        assertFalse("越界软链（相对形式）不能落成链接", Files.isSymbolicLink(escape.toPath()))
        assertTrue("越界软链（相对形式）应退化成空文件占位", escape.isFile && escape.length() == 0L)
        assertFalse("越界软链（绝对形式）不能落成链接", Files.isSymbolicLink(escapeAbs.toPath()))
        assertEquals("越界软链不应把内容写出去", 0L, escapeAbs.length())

        // 全树复查：解出来的树里不允许存在任何指向树外的链接（"没把链接建到环境外"的直接证据）
        var outside = 0
        val rootNorm = out.toPath().normalize()
        Files.walk(out.toPath()).use { s ->
            s.filter { Files.isSymbolicLink(it) }.forEach { p ->
                val resolved = p.parent.resolve(Files.readSymbolicLink(p)).normalize()
                if (!resolved.startsWith(rootNorm)) {
                    outside++
                    log("LINK_GUARD 树外链接: ${out.toPath().relativize(p)} -> ${Files.readSymbolicLink(p)}")
                }
            }
        }
        assertEquals("解出来的树里还有指向树外的链接", 0, outside)

        listOf(File(ctx.cacheDir, "lg_dir0644"), out).forEach { it.deleteRecursively() }
    }
}
