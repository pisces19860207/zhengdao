// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十七批 · 锚定集随包出厂：**文件级门禁**。
// 门1：改 asset 不改常量 ⇒ 此处红（真文件指纹对常量）；
// 门2：asset 里出现历史回灌标记 ⇒ 红（真账绝不出厂）；
// 门3：.gitattributes 对该路径显式 -text（判据④；285 教训：EOL 转换毁指纹）；
// 门4：资产路径与读取口各只许一处（装配不可被旁路）。
package com.example.zhengdao.baopu.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnchorAssetGateTest {

    private fun assetFile(): File = listOf(
        File("src/main/assets/policy/anchor-set.jsonl"), // 单测工作目录 = app/
        File("app/src/main/assets/policy/anchor-set.jsonl"), // 工作目录 = 仓根
    ).firstOrNull { it.exists() } ?: error("出厂锚定集不在 src/main/assets")

    private fun gitattributes(): String = listOf(
        File("../.gitattributes"), // 单测工作目录 = app/
        File(".gitattributes"), // 工作目录 = 仓根
    ).firstOrNull { it.exists() }?.readText() ?: error(".gitattributes 不在仓根")

    private fun mainSources(): List<Pair<String, String>> {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path.replace('\\', '/') to it.readText(Charsets.UTF_8) }
            .toList()
    }

    // ── 门1：改 asset 不改常量 ⇒ 红 ──────────────────────────────────────

    @Test
    fun `资产指纹与出厂常量逐字节相符`() {
        assertEquals(
            "assets 文件被改而常量没跟着换 ⇒ 本测试红（改锚＝改标准＝要重新签字发版）",
            AnchorAsset.EXPECTED_SHA256,
            AnchorAsset.sha256Hex(assetFile().readBytes()),
        )
    }

    // ── 门2：真账绝不出厂 ────────────────────────────────────────────────

    @Test
    fun `资产里不许出现历史回灌标记`() {
        val text = assetFile().readText()
        val jsonSpelling = Regex("\"?source\"?\\s*:\\s*\"?history")
        assertFalse(
            "锚定集只能从真账人工挑选出厂，历史回灌的自动账一行不许有（裸字面量与 JSON 拼写都算）",
            text.contains(AnchorAsset.FORBIDDEN_MARKER) || jsonSpelling.containsMatchIn(text),
        )
    }

    // ── 门3：禁 EOL 转换（判据④）──────────────────────────────────────────

    @Test
    fun `gitattributes对锚定集显式禁text转换`() {
        val wanted = "app/src/main/assets/${AnchorAsset.PATH} -text"
        assertTrue(
            "缺这行 ⇒ Windows checkout 会把指纹改废（285 教训）",
            gitattributes().lineSequence().map { it.trim() }.any { it == wanted },
        )
    }

    // ── 门4：装配不可被旁路 ──────────────────────────────────────────────

    @Test
    fun `资产路径字面量全仓只许AnchorAsset一处`() {
        val sites = mainSources().filter { (_, text) -> text.contains(AnchorAsset.PATH) }.map { it.first }
        assertEquals(listOf("java/com/example/zhengdao/baopu/policy/AnchorAsset.kt"), sites)
    }

    @Test
    fun `出厂读取口全仓只有启动装配一处`() {
        val sites = mainSources()
            .filter { (_, text) -> text.contains("AnchorAsset.fromAsset(") }
            .map { it.first }
        assertEquals(listOf("java/com/example/zhengdao/baopu/BaopuRuntime.kt"), sites)
    }

    @Test
    fun `锚定集不进res目录`() {
        val root = listOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull { it.isDirectory }
        val hit = root?.walkTopDown()
            ?.filter { it.isFile && it.extension == "xml" }
            ?.any { it.readText(Charsets.UTF_8).contains(AnchorAsset.PATH) }
            ?: false
        assertFalse("随包判据走 assets 只读，不许落 res/values", hit)
    }
}
