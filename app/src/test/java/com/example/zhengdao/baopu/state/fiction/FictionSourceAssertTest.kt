// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十九批 · 源码门禁臂（判据③⑤⑥⑦⑧＋设置页单写口）：
// · 判据③：CANON 块内容哈希 ↔ 批准版本 成对钉死——改设定谱而不升 canonVersion 即红；
// · 判据⑤：球状体禁词表必须在**剥注释后的代码**里（断言不是注释），且两处调用；
// · 判据⑥：DEFAULT_ON = false 字面钉死（翻 true 即红）；
// · 判据⑦：引擎/注入面零接线——包外引用只许设置页一处（关着零行为变化的结构证明）；
// · 判据⑧：判"不做"素材的字样在本单全部代码/配置/索引零命中（禁词用拼接构造，防自咬）。
package com.example.zhengdao.baopu.state.fiction

import com.example.zhengdao.SrcRoots
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FictionSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/fiction/"
    private val files = listOf("FictionCanon.kt", "IntersexTopology.kt", "FictionOutput.kt", "FictionMode.kt")
        .map { dir + it }

    private fun source(rel: String): String = SrcRoots.find(rel).readText(Charsets.UTF_8)

    private fun stripped(text: String): String =
        text.replace("\r\n", "\n").replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")

    private fun codeOf(rel: String): String = stripped(source(rel))

    private val allCode: String get() = files.joinToString("\n") { codeOf(it) }
    private val allRaw: String get() = files.joinToString("\n") { source(it) }

    private fun sha(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `防闸空转_四份生产源码都真被读到且剥注释后非空`() {
        assertEquals(4, files.size)
        for (rel in files) {
            val code = codeOf(rel)
            assertTrue("$rel 剥注释后不得为空", code.length > 100)
            assertTrue("$rel 须真是 fiction 包源码", "package com.example.zhengdao.baopu.state.fiction" in code)
        }
    }

    // ── 判据③：设定谱只读，改而不升版即红 ──────────────────────────────────

    @Test
    fun `判据三_CANON_块哈希与批准版本成对钉死`() {
        val t = source(dir + "FictionCanon.kt").replace("\r\n", "\n")
        val b = t.indexOf("// CANON-BEGIN")
        val e = t.indexOf("// CANON-END")
        assertTrue("CANON 块标记必须成对存在", b >= 0 && e > b)
        val block = t.substring(b, e)
        assertEquals(
            "判据③红：设定谱正文被改动而批准哈希未更新——改设定谱须人批升版（canonVersion 递增＋本闸批准值同步改）",
            "34a676c19bd8341d3331758f76708d021ff257291fa519cc9bacd43f6ea0d26f",
            sha(block),
        )
        assertTrue("当前批准版须钉 1", "const val CANON_VERSION = 1" in allRaw)
    }

    @Test
    fun `判据三_偷改一个档位字而版号不动_哈希必变_闸有牙自检`() {
        val t = source(dir + "FictionCanon.kt").replace("\r\n", "\n")
        val block = t.substring(t.indexOf("// CANON-BEGIN"), t.indexOf("// CANON-END"))
        val tampered = block.replace("压紧", "压紧膨大结")
        assertFalse("篡改块内容后哈希必须与批准值不同（证明闸盯的是内容不是版号一行）", sha(tampered) == sha(block))
    }

    // ── 判据⑤：禁令是断言不是注释 ──────────────────────────────────────────

    @Test
    fun `判据五_禁词表在剥注释后的代码里且七个词全在`() {
        val guardCode = codeOf(dir + "FictionCanon.kt")
        assertTrue("禁词表必须是代码不是注释", "FORBIDDEN_WORDS = listOf" in guardCode)
        for (w in listOf("球状体", "球状腺", "鼓球", "膨大结", "结状物", "knot", "bulbus")) {
            assertTrue("禁词 $w 须在代码里", "\"$w\"" in guardCode)
        }
    }

    @Test
    fun `判据五_禁令闸至少两处调用_构造与出口各一`() {
        val calls = Regex("BulbusGuard\\.rejectIfBulbus\\(").findAll(allCode).toList()
        assertTrue("rejectIfBulbus 调用点须 ≥2（构造＋输出），得 ${calls.size}", calls.size >= 2)
        assertTrue("LockCanon 构造须扫 lockSource", "BulbusGuard.rejectIfBulbus(\"lockSource\"" in codeOf(dir + "FictionCanon.kt"))
        assertTrue("输出拼行须扫", "BulbusGuard.rejectIfBulbus(field" in codeOf(dir + "FictionOutput.kt"))
    }

    // ── 判据⑥：默认关字面钉死 ──────────────────────────────────────────────

    @Test
    fun `判据六_DEFAULT_ON_必须钉_false`() {
        assertTrue("默认关常量须钉 false", "DEFAULT_ON = false" in allRaw)
        assertTrue("读口默认值须走常量", "getBoolean(KEY_ON, DEFAULT_ON)" in allRaw)
        val flipped = allRaw.replace("DEFAULT_ON = false", "DEFAULT_ON = true")
        assertFalse("翻成 true 后闸必须咬住", "DEFAULT_ON = false" in flipped)
    }

    @Test
    fun `开关键名与唯一写口_钉死`() {
        assertTrue("PREFS 名钉死", "PREFS_NAME = \"baopu_fiction_mode\"" in allRaw)
        assertTrue("KEY 名钉死", "KEY_ON = \"fiction_mode_on\"" in allRaw)
        assertTrue("读失败兜底须 fail-closed", "getOrDefault(DEFAULT_ON)" in allRaw)
    }

    // ── 判据⑦：包外零接线（不进 738 默认注入的结构证明）────────────────────

    @Test
    fun `判据七_引擎与注入面对_fiction_零引用_包外只许设置页`() {
        val mainRoot = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory } ?: throw AssertionError("找不到 main java 根")
        val refs = mainRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { f ->
                f.absolutePath.replace('\\', '/').contains("/state/fiction/")
                    .not() && stripped(f.readText(Charsets.UTF_8)).contains("state.fiction")
            }
            .map { it.name }
            .toSet()
        assertEquals("包外引用 fiction 的文件只许设置页一个（关着零行为变化的结构证明）", setOf("BaopuSettingsScreen.kt"), refs)
    }

    @Test
    fun `判据七_设置页读写各一处`() {
        val page = stripped(SrcRoots.find("app/src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt").readText(Charsets.UTF_8))
        assertEquals("写口只许一处", 1, Regex("FictionModeGate\\.set\\(").findAll(page).count())
        assertEquals("读口只许一处", 1, Regex("FictionModeGate\\.on\\(").findAll(page).count())
    }

    // ── 判据⑧：判"不做"素材零出现（禁词拼接构造，防自咬）───────────────────

    @Test
    fun `判据八_本单全部代码与文案_零命中不做素材字样`() {
        val banned = listOf("母" + "犬", "发" + "情", "08" + "_母")
        val corpus = StringBuilder()
        for (rel in files) corpus.append(source(rel))
        for (rel in listOf(
            "src/main/res/values/strings_baopu_fiction.xml",
            "src/main/res/values-en/strings_baopu_fiction.xml",
        )) {
            corpus.append(SrcRoots.find(rel).readText(Charsets.UTF_8))
        }
        val text = corpus.toString()
        for (w in banned) {
            assertFalse("判据⑧红：本单代码/文案出现「$w」", w in text)
        }
    }

    @Test
    fun `判据八_资料包索引与设定谱清单_无不做项条目`() {
        // 本包不产出任何索引文件；设定谱清单就是 CANON 块——块内逐档对照派工单 §三，
        // 只有锁结六字段，没有第三份素材的分组/键名。
        val t = source(dir + "FictionCanon.kt")
        val block = t.substring(t.indexOf("// CANON-BEGIN"), t.indexOf("// CANON-END"))
        assertFalse("CANON 块不许含不做素材字样", ("母" + "犬") in block)
        assertEquals("CANON 块只含六个设定谱枚举", 6, Regex("enum class").findAll(block).count())
    }
}
