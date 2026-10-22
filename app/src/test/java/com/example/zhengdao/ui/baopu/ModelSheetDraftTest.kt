// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：11:51 补充节 §三末句「跳玄览返回后，抽屉保持跳转前的公司名与已填内容，不许重置回默认通道」。
//
// 这一条为什么能在 JVM 里测：[ModelSheetDraft] 是进程级对象、零 Android 依赖（房型与 BrowserHolder
// 同一族）。抽屉的"回写"与"作废"全走它，所以恢复语义 = 这个对象的状态语义，不需要 Compose。
//
// 下面 [填过] 那个助手刻意照着 `BaopuChannelSheet` 里的回写段写（用户改一位 ⇒ 草稿跟一位），
// 而不是给草稿对象另开一条生产里不存在的起稿路：草稿**故意没有** startFrom——第一次打开该显示
// 什么是界面自己按 SP/预设现值算的，这里再算一遍就是第二真相（同一条 ModelSheetDraft 文件注释）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.ModelPickerRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelSheetDraftTest {

    private val custom = "custom"

    @Before
    fun 复位() {
        ModelSheetDraft.clear()
    }

    /**
     * 生产形态的"这一位用户填过了"：抽屉每改一位就回写一位（`ModelSheetDraft.showing = true` 起的那七行）。
     *
     * 默认值全空＝这一位没碰过。第一次打开时 `showing` 是假的 ⇒ 界面读 SP/预设现值，
     * 草稿不许假装自己有值（那就是 [没起稿时每一位都是空的] 要钉的起点）。
     */
    private fun 填过(
        channelId: String,
        baseUrl: String = "",
        model: String = "",
        addKey: String = "",
        query: String = "",
        picked: ModelPickerRules.ModelOverride? = null,
        pickedFor: String = "",
    ) {
        ModelSheetDraft.showing = true
        ModelSheetDraft.channelId = channelId
        ModelSheetDraft.baseUrl = baseUrl
        ModelSheetDraft.model = model
        ModelSheetDraft.addKey = addKey
        ModelSheetDraft.query = query
        ModelSheetDraft.picked = picked
        ModelSheetDraft.pickedFor = pickedFor
    }

    @Test
    fun 没起稿时每一位都是空的() {
        // 起点空 ⇒ 非恢复路径才会去读 SP/预设现值；任何一位有残留都会假装"用户填过"
        assertFalse("showing 假＝没开着抽屉，回抱朴页时不会凭空重弹", ModelSheetDraft.showing)
        assertEquals("", ModelSheetDraft.channelId)
        assertEquals("新 Key 输入位不许回填已存的", "", ModelSheetDraft.addKey)
        assertEquals("", ModelSheetDraft.baseUrl)
        assertEquals("", ModelSheetDraft.model)
        assertEquals("", ModelSheetDraft.query)
        assertNull(ModelSheetDraft.picked)
        assertEquals("", ModelSheetDraft.pickedFor)
    }

    @Test
    fun 跳玄览返回后公司名与已填内容都还在() {
        // 补充节 §三那一句话的完整链路：填东西 → 跳玄览（抱朴页整个离开组合）→ 返回 → 恢复
        填过(
            custom,
            baseUrl = "https://myhost.example.cn/v1",
            model = "my-model",
            addKey = "sk-user-typing-abcdef0011",
            query = "free",
            picked = ModelPickerRules.ModelOverride("gpt-4o", 128_000, null),
            pickedFor = custom,
        )

        // 跳玄览这一段：openInXuanlan 只写 BrowserHolder，草稿一个字都不动
        assertEquals(custom, ModelSheetDraft.channelId)
        assertEquals("https://myhost.example.cn/v1", ModelSheetDraft.baseUrl)
        assertEquals("my-model", ModelSheetDraft.model)
        assertEquals("free", ModelSheetDraft.query)
        assertEquals("sk-user-typing-abcdef0011", ModelSheetDraft.addKey)
        assertEquals("gpt-4o", ModelSheetDraft.picked?.id)

        // 返回后重新起抱朴页 ⇒ showing 为真，抽屉被重弹回来（BaopuScreen 读的就是这两个位）
        assertTrue(ModelSheetDraft.showing)
        assertEquals(custom, ModelSheetDraft.channelId)
    }

    @Test
    fun 不许重置回默认通道() {
        填过("openrouter-free")
        ModelSheetDraft.moveTo("openai-compat", "https://api.openai.com/v1", "gpt-4o-mini")
        assertEquals("切到另一家也得停在那一家，不回默认", "openai-compat", ModelSheetDraft.channelId)
        assertTrue("换家不算关掉抽屉：草稿仍然活着，返回后还是开着的", ModelSheetDraft.showing)
    }

    @Test
    fun 换一家时输入位跟着这家的真实状态走() {
        填过(
            custom,
            baseUrl = "https://a/v1",
            model = "m-a",
            addKey = "sk-typing-12345678",
            query = "free",
            picked = ModelPickerRules.ModelOverride("x"),
            pickedFor = custom,
        )

        ModelSheetDraft.moveTo("deepseek", "https://api.deepseek.com/v1", "deepseek-chat")
        assertEquals("deepseek", ModelSheetDraft.channelId)
        assertEquals("自定义的地址不串到预设头上", "https://api.deepseek.com/v1", ModelSheetDraft.baseUrl)
        assertEquals("deepseek-chat", ModelSheetDraft.model)
        assertEquals("正在打的那把新 Key 作废（它属于上一家）", "", ModelSheetDraft.addKey)
        assertNull(ModelSheetDraft.picked)
        assertEquals("跨家串味的防线：pickedFor 归零", "", ModelSheetDraft.pickedFor)
        assertEquals(
            "moveTo 不动搜索位：清空是 UI 的 selectProvider 干的，随后草稿回写把两处对齐",
            "free", ModelSheetDraft.query,
        )
    }

    @Test
    fun 关掉抽屉就把那串Key原文抹掉() {
        填过(custom, addKey = "sk-secret-abcdefghijklmnop")
        ModelSheetDraft.showing = false
        assertFalse(ModelSheetDraft.showing)
        ModelSheetDraft.clear()
        assertEquals("clear 之后不留任何输入（关抽屉即清，不过夜）", "", ModelSheetDraft.addKey)
        assertEquals("", ModelSheetDraft.channelId)
        assertEquals("", ModelSheetDraft.baseUrl)
        assertEquals("", ModelSheetDraft.model)
        assertEquals("", ModelSheetDraft.query)
        assertNull(ModelSheetDraft.picked)
        assertFalse(ModelSheetDraft.showing)
    }

    @Test
    fun pickedFor不是当前这家就当用户没点过() {
        // 这条测的是抽屉提交口的语义（BaopuChannelSheet 里那句 `if (pickedFor == selected && !isCustom)`）：
        // 判据本身写在 UI 里（Compose），这里锁住草稿侧提供的两个位不会自己凑成一对。
        填过("deepseek")
        ModelSheetDraft.picked = ModelPickerRules.ModelOverride("gpt-4o")
        ModelSheetDraft.pickedFor = "openai-compat"
        assertFalse("草稿不许假装 pickedFor 就是当前这家",
            ModelSheetDraft.pickedFor == ModelSheetDraft.channelId)
        ModelSheetDraft.moveTo("openai-compat", "https://api.openai.com/v1", "gpt-4o-mini")
        assertNull("换家 ⇒ 上一家的点选作废，不会被当成『这一家也点过』", ModelSheetDraft.picked)
    }
}
