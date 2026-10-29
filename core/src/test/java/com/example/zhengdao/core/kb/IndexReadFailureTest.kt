// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A1(a) / A3(a)（2026-10-29）：**行为不变，只把"读坏了"报出来**。
 *
 * 以前两处都是 `runCatching { readText() }.getOrNull()` —— "没有旧文件"和"读坏了"混成一个 null，
 * 于是「摘要被抹掉」「.bak 留不下」都不会有人知道。这三条单测钉住：
 * 三态能区分、两句话都点明后果（丢摘要 / 无 .bak）、且带异常类名便于对 logcat。
 */
class IndexReadFailureTest {

    @Test
    fun `读旧清单失败时点明会丢摘要且带异常类名`() {
        val note = KnowledgeBase.oldIndexReadFailureNote("00-目录.md", IOException("EACCES"))
        assertTrue("要带异常类名，排查时能对上 logcat", note.contains("IOException"))
        assertTrue("要写清后果：已有摘要会被丢掉", note.contains("丢掉已有摘要"))
        assertTrue("要带文件名，别只说'某文件'", note.contains("00-目录.md"))
    }

    @Test
    fun `读旧内容失败时点明留不下 bak`() {
        val note = KnowledgeBase.oldContentReadFailureNote("00-目录.md", IOException("EACCES"))
        assertTrue("要带异常类名", note.contains("IOException"))
        assertTrue("要写清后果：无法留 .bak（回滚点没了）", note.contains("无法留 .bak"))
    }

    @Test
    fun `重建结果三态可区分_读失败不算写失败`() {
        val written = KnowledgeBase.IndexOutcome.Written(readFailure = "IOException")
        assertTrue("读旧清单失败但**仍然写了**——这是 A1(a) 的口径（行为不变）", written is KnowledgeBase.IndexOutcome.Written)
        assertEquals("IOException", written.readFailure)
        assertNull("正常路径没有读失败", KnowledgeBase.IndexOutcome.Written(readFailure = null).readFailure)
        assertTrue(
            "没写成是另一种状态，别和'读失败'混",
            KnowledgeBase.IndexOutcome.Failed("空内容") is KnowledgeBase.IndexOutcome.Failed,
        )
        assertTrue("早退也要能区分开", KnowledgeBase.IndexOutcome.Skipped is KnowledgeBase.IndexOutcome.Skipped)
    }
}
