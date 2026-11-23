// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 判据⑤：同输入同输出，可回放到任意事件。
// 不用「跑一遍看着一样」当证据：逐字节比、换对象比、换日志次序比、换回放进法比。
package com.example.zhengdao.baopu.state.causal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CausalDeterminismTest {

    private val logs = listOf(
        CausalScenarios.injuryChain(),
        CausalScenarios.environmentChain(),
        CausalScenarios.fatigueChain(),
    )

    @Test
    fun `判据五_同一日志十次回放逐字节一致`() {
        logs.forEach { log ->
            val first = CausalRenderer.render(CausalReplay.of(log).at(Span825.MINUTES))
            repeat(9) { i ->
                val again = CausalRenderer.render(CausalReplay.of(log).at(Span825.MINUTES))
                assertEquals("第 ${i + 2} 次投影不一致：$first", first, again)
            }
        }
    }

    @Test
    fun `判据五_换一个回放对象结果也相同`() {
        logs.forEach { log ->
            val a = CausalReplay.of(log).at(Span825.HOURS)
            val b = CausalReplay.of(CausalLog(log.ordered())).at(Span825.HOURS)
            assertEquals("换对象必须同值", a, b)
        }
    }

    @Test
    fun `判据五_日志倒序输入结果不变（按刻度重排）`() {
        logs.forEach { log ->
            val forward = CausalReplay.of(log).at(Span825.CROSS_DAY)
            val backward = CausalReplay.of(CausalLog(log.events.reversed())).at(Span825.CROSS_DAY)
            assertEquals("倒序输入必须同值", forward.levels, backward.levels)
            assertEquals("倒序输入必须同文本", CausalRenderer.render(forward), CausalRenderer.render(backward))
        }
    }

    @Test
    fun `判据五_每个事件都能回放且可重复`() {
        logs.forEach { log ->
            log.events.forEach { e ->
                val a = CausalReplay.of(log).toEvent(e.eventId)
                val b = CausalReplay.of(log).toEvent(e.eventId)
                assertEquals("回放到 ${e.eventId} 两次不一致", a, b)
            }
        }
    }

    @Test
    fun `判据五_投影文本不带随机与对象地址`() {
        logs.forEach { log ->
            val text = CausalRenderer.render(CausalReplay.of(log).at(Span825.OVERNIGHT))
            assertFalse("不许出现 null：$text", text.contains("null"))
            assertFalse("不许出现对象地址：$text", Regex("@[0-9a-f]{4,}").containsMatchIn(text))
            assertFalse("不许出现墙钟：$text", text.contains("Timestamp"))
        }
    }

    @Test
    fun `判据五_四行次序固定`() {
        val text = CausalRenderer.render(CausalReplay.of(CausalScenarios.injuryChain()).at(Span825.MINUTES))
        val lines = text.lines()
        assertTrue("部位行在第二行", lines[1].startsWith("部位 "))
        assertTrue("姿态行在第三行", lines[2].startsWith("姿态 "))
        assertTrue("感觉行在第四行", lines[3].startsWith("感觉 "))
        assertTrue("环境行在第五行", lines[4].startsWith("环境 "))
        assertTrue("末行是滞后清单", lines.last().startsWith("滞后未到 ") || lines.last().startsWith("光影缺省 "))
    }

    @Test
    fun `判据五_没写过的维度印未给而不是无`() {
        val text = CausalRenderer.render(CausalReplay.of(CausalScenarios.injuryChain()).at(Span825.MINUTES))
        // 断言写死「未给」两个字：拿 CausalRenderer.ABSENT 自证的话，把常量改成「无」也能绿。
        assertTrue("没算过的汗要印未给：$text", text.contains("皮肤·湿：未给"))
        assertTrue("没算过的日照要印未给：$text", text.contains("光影·日照：未给"))
        assertFalse("没算过不等于没有：$text", text.contains("皮肤·湿：无"))
        assertFalse("没算过不等于没有：$text", text.contains("光影·日照：无"))
    }
}
