// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第二百一十二批 · **接线与红线**门禁（判据都在别处测过，这里钉「接线上别走样」）。
 *
 * 六条：
 * ① 号码只许 `ACTION_DIAL`（递进拨号盘），`ACTION_CALL` 这条链整条禁止（误点＝真打出去）；
 * ② 答案卡**没有提交**：壳里可以定义 [BaopuResultSubmitBar]（给将来的输入卡），但一次都不许调；
 * ③ 「≤ 屏 60%」是一个常量，不是散在各处的魔法数；
 * ④ 壳不许知道怎么查库（三态只改呈现，重查无从写起）；
 * ⑤ 模板 JSON 里不许写字面文案（只有 `labelKey`），且每个键在中英 strings 与文案表里都找得到；
 * ⑥ 文案表与中英 strings **双向**一致（你加了键忘了另一边，这里先红）。
 */
class BaopuResultCardGateTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    private val shell by lazy { src("src/main/java/com/example/zhengdao/ui/baopu/BaopuResultCard.kt") }
    private val stringsKt by lazy { src("src/main/java/com/example/zhengdao/ui/baopu/BaopuCardStrings.kt") }
    private val screen by lazy { src("src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt") }
    private val zhXml by lazy { src("src/main/res/values/strings_baopu_cards.xml") }
    private val enXml by lazy { src("src/main/res/values-en/strings_baopu_cards.xml") }

    private fun xmlKeys(xml: String): Set<String> =
        Regex("""<string name="([a-zA-Z0-9_]+)"""")
            .findAll(xml)
            .map { it.groupValues[1] }
            .toSet()

    private fun tableKeys(kt: String): Set<String> =
        Regex(""""([a-zA-Z0-9_]+)"\s*->\s*res\.getString""")
            .findAll(kt)
            .map { it.groupValues[1] }
            .toSet()

    private fun jsonFiles(): List<File> {
        val dir = File("src/main/assets/baopu/cards").let {
            if (it.isDirectory) it else File("app/src/main/assets/baopu/cards")
        }
        assertTrue("模板目录不在：${dir.path}", dir.isDirectory)
        return dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
    }

    @Test
    fun `红线一 号码只递拨号盘_不外呼`() {
        assertTrue("必须用 ACTION_DIAL 把号码递进系统拨号盘", shell.contains("Intent.ACTION_DIAL"))
        assertFalse(
            "答案卡里不许出现直接外呼（误点一下就是一通电话）",
            shell.contains("ACTION_CALL") || shell.contains("CALL_PHONE"),
        )
    }

    @Test
    fun `红线二 答案卡没有提交`() {
        // 定义可以（给将来的输入卡复用 §2.6 的样式），调用不行
        assertTrue("校验样式件应当还在（定义）", shell.contains("internal fun BaopuResultSubmitBar("))
        // 只查「卡那一段」（校验样式件在文件末尾，以分隔注释切开）
        val cardPart = shell.substringAfter("fun ResultSheetBody(").substringBefore("// ── 校验样式")
        assertFalse(
            "结果卡不许弹任何提交/回填控件（§0 三条硬边界之一）",
            cardPart.contains("BaopuResultSubmitBar"),
        )
        assertFalse("结果卡不许出现按钮式提交", cardPart.contains("onSubmit"))
    }

    @Test
    fun `红线三 六成屏是一个常量`() {
        assertTrue(shell.contains("RESULT_CARD_MAX_FRACTION = 0.6f"))
        assertTrue("上限要真的接到高度上", shell.contains("heightIn(max = maxSheetHeight)"))
        assertTrue("折行也要按同一个预算算", shell.contains("ResultCardBudget.maxRows("))
    }

    @Test
    fun `红线四 壳不会查库`() {
        listOf("LogisticsQuery", "LogisticsAsk", "LogisticsStore").forEach { word ->
            assertFalse("壳里不许出现 $word（重查这条路要写不出来才叫「不重查」）", shell.contains(word))
        }
        // 三态的回调只改状态
        assertTrue(screen.contains("ResultCards.collapse(resultCards)"))
        assertTrue(screen.contains("ResultCards.expand(resultCards, index)"))
    }

    @Test
    fun `红线五 模板 JSON 不写字面文案_且每个键都找得到`() {
        val zh = xmlKeys(zhXml)
        val en = xmlKeys(enXml)
        val table = tableKeys(stringsKt)
        jsonFiles().forEach { f ->
            val text = f.readText()
            assertFalse("${f.name} 里出现了字面文案（只许 labelKey）", Regex(""""label"\s*:""").containsMatchIn(text))
            val keys = Regex(""""(labelKey|titleKey|emptyTextKey)"\s*:\s*"([a-zA-Z0-9_]+)"""")
                .findAll(text)
                .map { it.groupValues[2] }
                .toSet()
            assertTrue("${f.name} 至少要有几个 labelKey", keys.size >= 3)
            keys.forEach { k ->
                assertTrue("${f.name} 的键「$k」不在 values/strings_baopu_cards.xml", k in zh)
                assertTrue("${f.name} 的键「$k」不在 values-en/strings_baopu_cards.xml", k in en)
                assertTrue("${f.name} 的键「$k」不在 BaopuCardStrings 的映射表里（用户会看到键名）", k in table)
            }
        }
    }

    @Test
    fun `红线五补 三份模板随包`() {
        assertEquals(
            listOf("logistics.waybill.json", "study.knowledge.json", "write.topic.json"),
            jsonFiles().map { it.name },
        )
    }

    @Test
    fun `红线六 文案表与中英 strings 双向一致`() {
        val zh = xmlKeys(zhXml)
        val en = xmlKeys(enXml)
        val table = tableKeys(stringsKt)
        assertEquals("中英两份 XML 的键必须一一对应", zh, en)
        assertEquals("文案表里的键必须与 XML 完全一致（多一个少一个都是漂移）", zh, table)
        assertTrue("这张表不能是空的", table.size > 40)
    }
}
