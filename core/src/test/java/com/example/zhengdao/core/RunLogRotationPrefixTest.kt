// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A7（2026-10-29）：日志轮换时**读旧内容失败**不许伪装成「空日志」。
 *
 * 改前 `runCatching { f.readText() }.getOrNull().orEmpty()` —— 读失败时把旧日志当空的，
 * 轮换后只剩一句「已截断」，事后排查分不清「本来就是空」还是「读坏了」。
 * 这条单测钉住：失败要写进日志头部一句**可查的失败说明**（行为不变、不抛异常）。
 */
class RunLogRotationPrefixTest {

    @Test
    fun `读成功时不加任何前缀`() {
        assertEquals("正常轮换不该多出一句废话", "", RunLog.rotationPrefix(null))
    }

    @Test
    fun `读失败时写明异常类名与旧日志可能已丢`() {
        val note = RunLog.rotationPrefix(IOException("EACCES: 读日志被拒"))
        assertTrue("必须点名异常类型（排查时要能对上 logcat）", note.contains("IOException"))
        assertTrue("必须写清「旧日志可能已丢」，别让读者以为日志本来就是空", note.contains("旧日志可能已丢"))
        assertTrue("前缀要自成语句（以省略号包住），拼进日志后不会跟正文粘在一起", note.startsWith("…"))
    }
}
