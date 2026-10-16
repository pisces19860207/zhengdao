// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥命令双门禁（第㊵刀，台账 §B 顺序 68 / 69）：
 *
 * ## 判据 A（P1）：`onMain` 块内不得阻塞等待主线程回调
 *
 * 第㊵刀修的真机 P0：`evalJs` 旧写法在 [BrowserCommands] 的 `onMain` 主线程块内
 * `latch.await` 等 `evaluateJavascript` 的结果回调，而该回调必须由主线程执行
 * ⇒ 自己等自己，8 秒必超时（execJS 连 `1+1` 都超时、snapshot/click/type/scroll
 * 全废）。正确形态：**闩等待在调用线程，主线程块只发起**。
 *
 * 这个坑不会有运行期测试红（编译过、单测绿、界面不崩，只是命令全超时），只有
 * 源码门禁拦得住：剥注释后逐个提取 `onMain { … }` 块，块内不得出现
 * `CountDownLatch` / `latch.await` / `.await(`。
 *
 * ## 判据 B（P2）：url 出口必经 redactUrl
 *
 * 真机实证过 `status` 的 url 明文带回 URL query 里的 PII（email/phone/id/card）。
 * 凡把 tab url 写进响应体的出口（`ok("url" to …)` / `ok("navigating" to …)`），
 * 所在行必须含 `redactUrl`——防回归成「新增一个 url 出口忘了脱敏」。
 *
 * ## 正例 + 负例自证
 *
 * 每条判据抽纯函数；正例打真源码，负例打内嵌的反例串（模拟「把 await 写回
 * onMain 块」与「url 出口绕开 redactUrl」两种退化形态），断言判据在负例上
 * **必然不通过**。真源码级负例演练（临时改回一行 ⇒ 必红 ⇒ 还原复绿）留痕于报告。
 */
class BridgeEvalAndUrlGateTest {

    // ── 源码读取（双路径兜底：cwd 可能是仓根或 app/） ──

    private fun readSource(relativeFromRoot: String, relativeFromApp: String, what: String): String {
        val roots = listOf(
            java.io.File(relativeFromRoot),
            java.io.File(relativeFromApp),
        )
        return roots.firstOrNull { it.isFile }?.readText()
            ?: error("找不到 $what（cwd=${java.io.File("").absolutePath}）")
    }

    private fun commandsSource(): String = readSource(
        "app/src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt",
        "src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt",
        "BrowserCommands.kt",
    )

    /** 去注释（必须先剥再断言，否则 KDoc 里提一嘴就算自己拦自己）。 */
    private fun stripped(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    // ── 判据纯函数 ──

    /**
     * 提取所有 `onMain { … }` 块体（花括号配对，跳过字符串/字符字面量）。
     * 返回空列表 = 一处 onMain 都没有（判据 A 自然通过）。
     */
    private fun onMainBlocks(src: String): List<String> {
        val blocks = mutableListOf<String>()
        var i = 0
        while (true) {
            val at = src.indexOf("onMain {", i)
            if (at < 0) break
            val open = src.indexOf('{', at)
            var depth = 0
            var j = open
            var inStr = false
            var inChar = false
            while (j < src.length) {
                val c = src[j]
                when {
                    inStr -> if (c == '\\') j++ else if (c == '"') inStr = false
                    inChar -> if (c == '\\') j++ else if (c == '\'') inChar = false
                    c == '"' -> inStr = true
                    c == '\'' -> inChar = true
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                j++
            }
            if (j <= src.length) blocks.add(src.substring(open + 1, j.coerceAtMost(src.length)))
            i = j + 1
        }
        return blocks
    }

    /** 判据 A：每个 onMain 块都不得阻塞等待（CountDownLatch / latch.await / .await(）。 */
    private fun noBlockingInsideOnMain(src: String): Boolean =
        onMainBlocks(src).none { block ->
            block.contains("CountDownLatch") || block.contains("latch.await") || block.contains(".await(")
        }

    /**
     * 判据 B：url 出口（`"url" to` / `"navigating" to` 赋值行）必须含 redactUrl。
     * 按行匹配（`ok(` 与字段分居两行，不能把 `ok("url" to` 当一个整式）。
     */
    private fun urlExportsRedacted(src: String): Boolean =
        src.lines()
            .filter { it.contains("\"url\" to") || it.contains("\"navigating\" to") }
            .all { it.contains("redactUrl") }

    // ── 正例（打真源码） ──

    @Test
    fun `onMain 块内无阻塞等待`() {
        assertTrue(noBlockingInsideOnMain(stripped(commandsSource())))
    }

    @Test
    fun `url 出口必经 redactUrl`() {
        assertTrue(urlExportsRedacted(stripped(commandsSource())))
    }

    // ── 负例（真源码退化一处，判据必须不通过） ──

    @Test
    fun `负例_把 await 写回 onMain 块必须不过判据A`() {
        // 模拟第㊵刀前的旧 evalJs 形态：主线程块内建闩并 await
        val regressed = """
            private fun evalJs(webView: WebView, script: String): String? =
                onMain {
                    val latch = CountDownLatch(1)
                    val box = arrayOfNulls<String>(1)
                    webView.evaluateJavascript(script) { box[0] = it; latch.countDown() }
                    runCatching { latch.await(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                    normalizeJsResult(box[0])
                }.value
        """.trimIndent()
        assertFalse(noBlockingInsideOnMain(regressed))
    }

    @Test
    fun `负例_url 出口绕开 redactUrl 必须不过判据B`() {
        // 第48刀A 起 status 的 ok(...) 组装搬进 statusJson（参数名 st.url → url），
        // 夹具跟着换字面量；判据意图不变：url 出口绕开 redactUrl ⇒ 门禁必不过。
        val regressed = stripped(commandsSource())
            .replaceFirst(Regex("\"url\" to SensitiveRedactor\\(\\)\\.redactUrl\\(url\\)"), "\"url\" to url")
        assertFalse(urlExportsRedacted(regressed))
    }
}
