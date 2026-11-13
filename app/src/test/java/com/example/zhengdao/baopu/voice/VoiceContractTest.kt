// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 判据⑥（权限拒绝）＋ 两档/两模式闭集 ＋ 本地模型未装灰掉。 */
class VoiceContractTest {

    @Test
    fun `判据6_权限拒绝_按钮不可用且必须给引导`() {
        val d = VoiceGate.decide(
            source = VoiceSource.SYSTEM,
            permissionGranted = false,
            systemAvailable = true,
            localInstalled = false,
        )
        assertFalse("权限被拒 ⇒ 麦克风按钮不可用", d.enabled)
        assertEquals(VoiceGuide.PERMISSION_DENIED, d.guide)
        assertTrue("不许静默失败：必须有可见引导", guideVisible(d.guide))
    }

    @Test
    fun `判据6_判别器自证_没有引导时判假`() {
        assertFalse(guideVisible(VoiceGuide.NONE))
        assertTrue(guideVisible(VoiceGuide.PERMISSION_DENIED))
        assertTrue(guideVisible(VoiceGuide.SYSTEM_UNAVAILABLE))
        assertTrue(guideVisible(VoiceGuide.LOCAL_NOT_INSTALLED))
    }

    @Test
    fun `本地模型未装_选项灰掉且明确说未装_不偷偷回退系统`() {
        assertFalse(VoiceGate.localOptionEnabled(false))
        assertTrue(VoiceGate.localOptionEnabled(true))
        val d = VoiceGate.decide(VoiceSource.LOCAL, true, true, false)
        assertFalse(d.enabled)
        assertEquals(VoiceGuide.LOCAL_NOT_INSTALLED, d.guide)
        val ok = VoiceGate.decide(VoiceSource.LOCAL, true, true, true)
        assertTrue(ok.enabled)
        assertEquals(VoiceGuide.NONE, ok.guide)
    }

    @Test
    fun `系统ASR不可用_启动前就被挡住`() {
        val d = VoiceGate.decide(VoiceSource.SYSTEM, true, false, false)
        assertFalse(d.enabled)
        assertEquals(VoiceGuide.SYSTEM_UNAVAILABLE, d.guide)
    }

    @Test
    fun `权限先于能力判定_权限没给时能力齐全也不可用`() {
        val d = VoiceGate.decide(VoiceSource.SYSTEM, false, true, true)
        assertEquals(VoiceGuide.PERMISSION_DENIED, d.guide)
        assertFalse(d.enabled)
    }

    @Test
    fun `两档闭集_不许加第三档_默认系统`() {
        assertEquals(2, VoiceSource.values().size)
        assertEquals(setOf("system", "local"), VoiceSource.values().map { it.wire }.toSet())
        assertEquals(VoiceSource.SYSTEM, VoicePrefs.normalizeSource(null))
        assertEquals(VoiceSource.SYSTEM, VoicePrefs.normalizeSource("cloud"))
        assertEquals(VoiceSource.LOCAL, VoicePrefs.normalizeSource("local"))
    }

    @Test
    fun `两模式闭集_默认点按_非法值回落`() {
        assertEquals(2, VoiceMode.values().size)
        assertEquals(setOf("hold", "tap"), VoiceMode.values().map { it.wire }.toSet())
        assertEquals(VoiceMode.TAP, VoicePrefs.normalizeMode(null))
        assertEquals(VoiceMode.TAP, VoicePrefs.normalizeMode("third-mode"))
        assertEquals(VoiceMode.HOLD, VoicePrefs.normalizeMode("hold"))
    }

    private fun guideVisible(guide: VoiceGuide): Boolean = guide != VoiceGuide.NONE
}
