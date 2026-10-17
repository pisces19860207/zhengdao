// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 目的：抱朴工具开关的文案在 strings.xml 里，映射在 SettingsDataActivity（fail-fast）。
// 这里守**文案这一半**：catalog 里每个工具都必须有 中文名/中文说明/英文名/英文说明 四条。
// 漏一条 ⇒ 该工具在设置页要么崩、要么显示成 key，用户没法开关它。
package com.example.zhengdao.baopu.agent

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuToolStringsTest {

    private fun strings(path: String): String {
        val f = File(path)
        assertTrue("找不到 $path（单测工作目录应在 app/ 下）", f.exists())
        return f.readText()
    }

    @Test
    fun 每个工具都有中英文案() {
        val zh = strings("src/main/res/values/strings.xml")
        val en = strings("src/main/res/values-en/strings.xml")
        val names = BaopuToolCatalog.ENTRIES.map { it.name }
        assertTrue("工具表不该是空的", names.isNotEmpty())
        for (name in names) {
            for (suffix in listOf("", "_desc")) {
                val key = "baopu_tool_$name$suffix"
                assertTrue("缺中文 $key", zh.contains("name=\"$key\""))
                assertTrue("缺英文 $key", en.contains("name=\"$key\""))
            }
        }
    }

    @Test
    fun 工具名不超过描述长度上限() {
        // ToolSpec.MAX_DESC = 500：描述会整个进系统提示，超了就是吃上下文
        for (spec in BaopuToolCatalog.ENTRIES.map { it.factory { File(".") } }) {
            assertTrue(
                "${spec.name} 描述 ${spec.description.length} 字，超过 ${ToolSpec.MAX_DESC}",
                spec.description.length <= ToolSpec.MAX_DESC,
            )
        }
    }
}
