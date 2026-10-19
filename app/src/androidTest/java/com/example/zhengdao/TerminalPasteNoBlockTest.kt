// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// P1-6（第五十三批）真机验证：系统文本菜单「粘贴」回调不得在主线程写 pty。
//
// 为什么不用 JVM：这一段是 Activity 回调 + 系统剪贴板 + 真实 TerminalSession，
// JVM 里没有 Android 的 ClipboardManager，也没有 pty。放进 androidTest，跑在真机上：
//   1) 剪贴板塞 20 KB（远超 TerminalSession 4 KiB ByteQueue 的阻塞阈值）；
//   2) 主线程调 onPasteTextFromClipboard(null)（回调不依赖 session 参数）；
//   3) 断言调用本身 < 100 ms 返回（修复前主线程直写 pty 会在这里卡住）；
//   4) 等异步通道把字节送进 pty：终端 transcript 变长。
package com.example.zhengdao

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.view.TerminalView
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalPasteNoBlockTest {

    private fun withActivity(block: (TerminalActivity) -> Unit) {
        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            scenario.onActivity(block)
        }
    }

    private fun TerminalActivity.termSessionOrNull() =
        findViewById<TerminalView>(R.id.terminal_native)?.mTermSession

    private fun ActivityScenario<TerminalActivity>.waitSession(timeoutMs: Long = 15_000): TerminalActivity? {
        var out: TerminalActivity? = null
        onActivity { out = it }
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var has = false
            onActivity { a -> has = a.termSessionOrNull() != null }
            if (has) break
            Thread.sleep(200)
        }
        return out
    }

    @Test
    fun paste_callback_returns_quickly_and_bytes_reach_pty_async() {
        val big = "A".repeat(20_000)
        var started = false
        var elapsed = -1L
        var grew = false

        ActivityScenario.launch(TerminalActivity::class.java).use { scenario ->
            scenario.waitSession()
            var sessionOk = false
            scenario.onActivity { a -> sessionOk = a.termSessionOrNull() != null }
            assumeTrue("没有可用会话（环境未安装或 attach 失败）⇒ 跳过", sessionOk)

            // 剪贴板：20 KB
            scenario.onActivity { a ->
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("zd-paste-test", big))
            }

            var before = 0
            scenario.onActivity { a ->
                before = a.termSessionOrNull()?.emulator?.screen?.transcriptText?.length ?: 0
            }

            scenario.onActivity { a ->
                started = true
                val t0 = SystemClock.uptimeMillis()
                a.onPasteTextFromClipboard(a.termSessionOrNull())
                elapsed = SystemClock.uptimeMillis() - t0
            }

            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                var now = 0
                scenario.onActivity { a ->
                    now = a.termSessionOrNull()?.emulator?.screen?.transcriptText?.length ?: 0
                }
                if (now > before) {
                    grew = true
                    break
                }
                Thread.sleep(200)
            }

            // 收尾：给 shell 一个 Ctrl+C，别把测试文本留在输入行
            scenario.onActivity { a ->
                runCatching { a.termSessionOrNull()?.write("\u0003") }
            }
        }

        assertTrue("回调没有被调用", started)
        assertTrue("粘贴回调在主线程耗时 ${elapsed}ms（>100ms ⇒ 又回主线程写 pty 了）", elapsed in 0..100)
        assertTrue("10 秒内终端的 transcript 没变长 ⇒ 字节没经异步通道送达 pty", grew)
    }
}
