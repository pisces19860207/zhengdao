// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A4（2026-10-29，安全修正，已御批）：**软链探测失败必须当「可疑」跳过**，不许 fail-open。
 *
 * 改前是 `runCatching { Files.isSymbolicLink(..) }.getOrDefault(false)` —— 探测一抛异常就
 * 当「不是软链」，于是本该校验的条目会被当普通文件读/搬。这条单测就是那个负例：
 * **探测失败 ⇒ 跳过（true）**，而且**必须报出来**（onUnknown 被调用，不静默）。
 */
class SymlinkProbeFailsClosedTest {

    @Test
    fun `探测抛异常时当可疑跳过_并报出来`() {
        val reported = mutableListOf<Throwable>()
        val skip = KnowledgeBase.symlinkProbeFailsClosed(
            probe = { throw IOException("EPERM: 探测被拒") },
            onUnknown = { reported += it },
        )
        assertTrue("探测失败必须按可疑跳过（fail-closed）；改前的 fail-open 会让它返回 false", skip)
        assertEquals("失败必须被报出去（不许静默拒），且恰好报一次", 1, reported.size)
        assertTrue("报出来的要是原始异常", reported[0] is IOException)
    }

    @Test
    fun `探测正常时沿用它的值_不误报`() {
        val reported = mutableListOf<Throwable>()
        assertTrue(
            "真是软链 ⇒ 跳过",
            KnowledgeBase.symlinkProbeFailsClosed(probe = { true }, onUnknown = { reported += it }),
        )
        assertFalse(
            "不是软链 ⇒ 不跳过",
            KnowledgeBase.symlinkProbeFailsClosed(probe = { false }, onUnknown = { reported += it }),
        )
        assertTrue("正常路径不该报失败", reported.isEmpty())
    }
}
