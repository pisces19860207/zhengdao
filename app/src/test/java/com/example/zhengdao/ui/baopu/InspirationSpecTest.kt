// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十七批 · 灵感卡规格测试：点开同款卡 ⇒ L0 原文逐字在卡面（判据①
// 「可点开」的卡面半边），唯一动作「忽略」，footer 明说这次点开已放行注入。
// 假解析器照 DecisionCardSpecsTest 房型，但读本单自己的 strings_baopu_inspiration.xml。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.R
import com.example.zhengdao.baopu.subconscious.InspirationRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InspirationSpecTest {

    private val idToName: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    private val zhXml: String by lazy {
        File("src/main/res/values/strings_baopu_inspiration.xml")
            .let { if (it.isFile) it else File("app/$it") }
            .readText()
    }

    private val res = DecisionStrRes { id, args ->
        val name = idToName[id] ?: error("R.string id=$id 没有对应字段")
        val m = Regex("<string name=\"$name\">([^<]*)</string>").find(zhXml)
            ?: error("strings_baopu_inspiration.xml 缺键 $name（灵感卡用到了它）")
        val raw = m.groupValues[1]
        if (args.isEmpty()) raw else String.format(raw, *args)
    }

    private val row = InspirationRow(
        id = "inspiration:insp-x",
        atMs = 1_000L,
        l0Id = "insp-x",
        body = "海战中要先占据上风位置",
        tags = listOf("海战"),
        recordedAtMs = 500L,
    )

    @Test
    fun 卡面正文是L0原文逐字() {
        val spec = DecisionSpecs.inspiration(row, res)
        assertEquals("原文逐字——可点开判据的卡面半边", row.body, spec.body)
        assertEquals("键＝行 id（点开对得上）", row.id, spec.key)
        assertEquals("标签上 chips", row.tags, spec.chips)
    }

    @Test
    fun 卡上唯一动作是忽略() {
        val spec = DecisionSpecs.inspiration(row, res)
        assertEquals(1, spec.buttons.size)
        assertEquals("ignore", spec.buttons.first().id)
    }

    @Test
    fun 卡面footer说不点不进上下文() {
        val spec = DecisionSpecs.inspiration(row, res)
        assertTrue(spec.footer.orEmpty().contains("不点不进上下文"))
    }
}
