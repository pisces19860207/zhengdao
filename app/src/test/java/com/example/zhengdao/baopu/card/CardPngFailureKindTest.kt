// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 259 批 B 段 #5：卡文件读写「没有」和「坏了」必须分开。
// 改前表现（逐条标在下面）：
// · writeBytesNew(...) 撞名 / 父目录没建 ⇒ 都返回 false，调用方拿不到任何区别；
// · File.readAllBytesOrNull() 不存在 / 读不动 ⇒ 都返回 null，盘坏了被说成「没有这张卡」；
// · CardPng.readObject(...) 没卡块 / 卡数据解不开 ⇒ 都返回 null，把 read() 已经写好的那行
//   人话原地丢掉。
// 这三处当时全仓零调用方（grep 过 main+test），所以缺陷没炸在真机上，但口径是错的：
// 工具层一接就等于「读不出来 = 这卡不存在」。
package com.example.zhengdao.baopu.card

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CardPngFailureKindTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bytesOf(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    /** 被测源码（负例闸用）：单测的工作目录是 app/。 */
    private fun pngSrc(): String =
        File("src/main/java/com/example/zhengdao/baopu/card/CardPng.kt").readText()

    // ── 写：撞名 ≠ 没这条路 ─────────────────────────────────────────────

    /** 改前：这两次调用都只回 `false`——同一个值，两种完全不同的处置。 */
    @Test
    fun `写_目标撞名与父目录没建分开说`() {
        assertTrue(
            "新建应当成功",
            CardPng.writeCardBytes(File(tmp.newFolder("d"), "card.png"), bytesOf("A")) is CardWrite.Ok,
        )

        val clash = File(tmp.newFolder("c"), "card.png").also { it.writeText("旧内容") }
        val hitExisting = CardPng.writeCardBytes(clash, bytesOf("B"))
        assertTrue("撞名必须是 Failed：$hitExisting", hitExisting is CardWrite.Failed)
        assertEquals("目标已存在（绝不做覆盖）", (hitExisting as CardWrite.Failed).why)
        assertEquals("CREATE_NEW 判据：失败一个字都不许动", "旧内容", clash.readText())

        val noDir = CardPng.writeCardBytes(File(tmp.root, "没建的目录/card.png"), bytesOf("B"))
        assertTrue(noDir is CardWrite.Failed)
        assertEquals("路径里有一层不存在", (noDir as CardWrite.Failed).why)

        assertNotEquals(
            "改前两者都是 false：区别必须在这里显出来",
            (hitExisting as CardWrite.Failed).why,
            (noDir as CardWrite.Failed).why,
        )
    }

    @Test
    fun `写_失败分类不抄系统原文也不带路径`() {
        val why = (CardPng.writeCardBytes(File(tmp.root, "没这层目录/card.png"), ByteArray(1))
            as CardWrite.Failed).why
        assertFalse("分类里不许出现路径片段：$why", why.contains("没这层目录"))
        assertFalse("分类里不许出现异常类名：$why", why.contains("Exception"))
    }

    // ── 读字节：没有 ≠ 坏了 ─────────────────────────────────────────────

    /** 改前：三种情况全返回 null（目录那条走 `else null`，读坏了走 `catch null`）。 */
    @Test
    fun `读_不存在与是目录都算没有_拿到字节才算有`() {
        val never = File(tmp.root, "从来没有这个文件.png")
        assertEquals("不存在应当是 Missing", CardBytes.Missing, never.readCardBytes())

        val dir = tmp.newFolder("是个目录")
        assertEquals("目录不是普通文件：口径仍是「没有」", CardBytes.Missing, dir.readCardBytes())

        val f = File(tmp.newFolder("r"), "a.png")
        f.writeText("hello")
        val ok = f.readCardBytes()
        assertTrue("正常文件应当是 Ok：$ok", ok is CardBytes.Ok)
        assertEquals("hello", String((ok as CardBytes.Ok).bytes, Charsets.UTF_8))
    }

    /**
     * 「坏了」这一格只能由异常分类器钉住：这台 Windows 造不出稳定的真实读故障
     * （NTFS 上 `setReadable(false)` 不生效；同进程文件锁不拦裸读）。已记进报告 §7。
     */
    @Test
    fun `读_坏了的分类不是Missing`() {
        assertEquals("读写出错（磁盘或进程被系统拦下）", cardIoFailureWhy(IOException("disk died")))
        assertEquals("权限被拒", cardIoFailureWhy(AccessDeniedException("denied")))
    }

    // ── 分类器：一格一类 ────────────────────────────────────────────────

    @Test
    fun `分类_每种异常只落一格`() {
        val table = listOf(
            FileAlreadyExistsException("x") to "目标已存在（绝不做覆盖）",
            NoSuchFileException("x") to "路径里有一层不存在",
            NotDirectoryException("x") to "路径里有一层不是目录",
            AccessDeniedException("x") to "权限被拒",
            FileNotFoundException("x") to "目标不存在或不可写",
            IOException("x") to "读写出错（磁盘或进程被系统拦下）",
        )
        table.forEach { (e, want) ->
            assertEquals("${e.javaClass.simpleName} 归错格", want, cardIoFailureWhy(e))
        }
        assertEquals("未知失败（IllegalStateException）", cardIoFailureWhy(IllegalStateException("x")))
    }

    /**
     * `InvalidPathException` 是 `IllegalArgumentException` 的后代、不是 `IOException` 的：
     * 排在 IOException 那格之前才命中得到（只在 Windows 抛，真实路径造不出来，直接喂类型）。
     */
    @Test
    fun `分类_路径不合法单独一格`() {
        assertEquals("路径字符串本身不合法", cardIoFailureWhy(InvalidPathException("a:b", "Illegal char")))
    }

    @Test
    fun `分类_一个字都不抄异常原文`() {
        val why = cardIoFailureWhy(IOException("C:\\Users\\ somebody \\k.png: 系统找不到指定的文件"))
        assertEquals("读写出错（磁盘或进程被系统拦下）", why)
        assertFalse(why.contains("Users"))
        assertFalse(why.contains("k.png"))
    }

    // ── 活门：export 的写失败也说得出分类 ───────────────────────────────

    /** 改前：`"写入失败：${e.message}"`——换台机器话术就变，且把整条路径带进界面。 */
    @Test
    fun `导出_父目录没建_报分类而不是系统原文`() {
        val r = CardPng.export("""{"name":"张三"}""", CardPng.whiteOnePixelPng(), File(tmp.root, "没建的目录/out.png"))
        assertTrue("应当失败：$r", r is PngWrite.Failed)
        val msg = (r as PngWrite.Failed).message
        assertTrue("要说得出是哪一类失败：$msg", msg.contains("路径里有一层不存在"))
        assertFalse("不许把路径带进话术：$msg", msg.contains("没建的目录"))
    }

    /** 撞名那条话术是别的用例钉过的（CardPngTest），本批不许动。 */
    @Test
    fun `导出_撞名话术一字未改`() {
        val f = File(tmp.newFolder("e"), "out.png")
        assertTrue(CardPng.export("""{"a":1}""", null, f) is PngWrite.Ok)
        val again = CardPng.export("""{"a":2}""", null, f)
        assertTrue(again is PngWrite.Failed)
        assertTrue((again as PngWrite.Failed).message.contains("目标已存在，未覆盖："))
    }

    // ── 卡对象：失败原因不再塌成 null ───────────────────────────────────

    /** 改前：三格全返回 null，`read()` 已经写好的那行人话被丢掉。 */
    @Test
    fun `卡对象_没卡块与卡数据解不开与有卡分得开`() {
        val noCard = CardPng.readCardObject(CardPng.whiteOnePixelPng())
        assertTrue("干净 PNG 应当报「没卡块」：$noCard", noCard is CardObject.Failed)
        assertEquals(CardText.PNG_NO_CARD_BLOCK, (noCard as CardObject.Failed).message)

        val good = File(tmp.newFolder("o"), "c.png")
        assertTrue(CardPng.export("""{"name":"张三"}""", null, good) is PngWrite.Ok)
        val ok = CardPng.readCardObject(good.readBytes())
        assertTrue(ok is CardObject.Ok)
        assertEquals("张三", (ok as CardObject.Ok).json.getString("name"))

        val bad = File(tmp.root, "bad.png")
        assertTrue(CardPng.export("123", null, bad) is PngWrite.Ok)
        val broken = CardPng.readCardObject(bad.readBytes())
        assertTrue(broken is CardObject.Failed)
        assertTrue((broken as CardObject.Failed).message.contains("内容不是 JSON"))
        assertNotEquals("「没卡块」和「解不开」不许共用一句话", (noCard as CardObject.Failed).message,
            (broken as CardObject.Failed).message)
    }

    // ── 负例闸：改前那三处塌成 null/false 的字面量不许回来 ───────────────

    @Test
    fun `负例_旧写法与旧签名都不在文件里`() {
        val text = pngSrc()
        assertFalse(text.contains("internal fun CardPng.readObject(bytes: ByteArray): JSONObject?"))
        assertFalse(text.contains("internal fun CardPng.writeBytesNew(path: File, bytes: ByteArray): Boolean"))
        assertFalse(text.contains("internal fun File.readAllBytesOrNull(): ByteArray?"))
        assertFalse(text.contains("if (isFile) readBytes() else null"))
        assertFalse("写门不许退回抄系统原文", text.contains("e.message ?: e.javaClass.simpleName"))
        assertFalse(text.contains("写入失败："))
    }

    /** 负例4：不是「只加日志」——catch 的出口是返回值。 */
    @Test
    fun `负例_失败是返回值不是日志`() {
        val text = pngSrc()
        assertTrue(text.contains("CardWrite.Failed(cardIoFailureWhy(e))"))
        assertTrue(text.contains("CardBytes.Failed(cardIoFailureWhy(e))"))
        assertTrue(text.contains("CardObject.Failed(r.message)"))
        assertFalse("本文件不开日志口子", text.contains("RunLog.log("))
    }
}
