// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 遗留记忆插件清理判定单测（[isLegacyMemPlugin]）。
 *
 * 背景：2026-10-06 起把第三方插件 `opencode-mem` 预置进 `opencode.json` 的 `plugin`
 * 数组，2026-10-07 用户拍板整个摘除（该插件装上后从未产出过记忆，却带 656 MB 本地
 * 向量模型 + 1.9 GB 依赖）。清理必须**只针对它自己**——`plugin` 数组是用户可自定的
 * 位置，误伤别的插件等于替用户做决定。
 *
 * 需要覆盖两种真实形态：配置里写的裸名 `"opencode-mem"`，以及 opencode 解析出的带
 * 版本 spec（真机缓存目录名实测为 `opencode-mem@latest`）。
 */
class LegacyMemPluginTest {

    @Test
    fun `认得裸名与带版本 spec`() {
        assertTrue(isLegacyMemPlugin("opencode-mem"))
        assertTrue(isLegacyMemPlugin("opencode-mem@latest"))
        assertTrue(isLegacyMemPlugin("opencode-mem@2.29.0"))
    }

    @Test
    fun `不误伤名字相近的其它包`() {
        assertFalse(isLegacyMemPlugin("opencode-memory"))
        assertFalse(isLegacyMemPlugin("opencode-memo"))
        assertFalse(isLegacyMemPlugin("my-opencode-mem"))     // 前缀不同，不该命中
        assertFalse(isLegacyMemPlugin("@scope/opencode-mem"))  // 作用域包不是我们写的那个
        assertFalse(isLegacyMemPlugin("opencode-helicone-session"))
    }

    @Test
    fun `空串与无关名不命中`() {
        assertFalse(isLegacyMemPlugin(""))
        assertFalse(isLegacyMemPlugin("@my-org/custom-plugin"))
        assertFalse(isLegacyMemPlugin("opencode-wakatime"))
    }

    @Test
    fun `过滤语义：只摘掉自己并保留其余项与原顺序`() {
        val input = listOf("opencode-wakatime", "opencode-mem", "@my-org/custom-plugin")
        val left = input.filterNot { isLegacyMemPlugin(it) }
        assertEquals(listOf("opencode-wakatime", "@my-org/custom-plugin"), left)
    }

    @Test
    fun `过滤语义：数组里只有它时结果为空`() {
        assertTrue(listOf("opencode-mem").filterNot { isLegacyMemPlugin(it) }.isEmpty())
        assertTrue(listOf("opencode-mem@latest").filterNot { isLegacyMemPlugin(it) }.isEmpty())
    }
}
