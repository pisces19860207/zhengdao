// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui.taiji

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 顶栏模型胶囊的显示名收敛逻辑单测（[shortModelName]）。
 *
 * 背景（2026-10-07 真机实测）：模型入口直排顶栏主行时，`opencode/longcat-2.5-preview-free`
 * 这类长名把标题列挤到 0 宽 → 标题消失、「已连接」被折成竖排。修复方案之一是胶囊里
 * 只显示 model 段。这里的边界必须锁死，否则重构时很容易把长名又漏回去。
 */
class TaijiModelChipTest {

    @Test
    fun `provider-slash-model 只保留 model 段`() {
        assertEquals("longcat-2.5-preview-free", shortModelName("opencode/longcat-2.5-preview-free"))
    }

    @Test
    fun `多个斜杠时取最后一段`() {
        // 形如 org/team/model 的写法也要收敛到真正的模型名
        assertEquals("gpt-5", shortModelName("vendor/sub/gpt-5"))
    }

    @Test
    fun `没有斜杠时原样返回`() {
        assertEquals("sonnet", shortModelName("sonnet"))
    }

    @Test
    fun `以斜杠结尾且 model 段为空时原样返回`() {
        // 退化输入不能变成空串——空串会让胶囊看上去是坏的
        assertEquals("opencode/", shortModelName("opencode/"))
    }

    @Test
    fun `空串原样返回`() {
        assertEquals("", shortModelName(""))
    }

    @Test
    fun `中文与空白原样保留`() {
        assertEquals("默认", shortModelName("默认"))
    }
}
