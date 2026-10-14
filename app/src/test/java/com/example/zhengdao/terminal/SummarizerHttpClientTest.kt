// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * §B 顺序 4 · **P1-7** 的回归用例：资料库摘要每次任务都 `new OkHttpClient()`。
 *
 * 为什么是缺陷：`OkHttpClient` 自带**连接池 + Dispatcher 线程池**，每次新建就等于每次留下
 * 一套（闲置）线程与连接；而摘要是"每次整理跑一遍"的动作 ⇒ 资源只增不减。题面记的
 * "headline 已被 `oc/OcRuntime.kt` 的进程级单例消解"只覆盖了另一处，这一处当时没动。
 *
 * 判据三条：
 * ① 同进程反复取客户端 ⇒ **同一个实例**；
 * ② 鉴权头 = `Basic base64(opencode:<pw>)`，且**随口令变化**（serve 重启会换口令，
 *    所以"客户端共享 + 口令按次注入"是绑在一起的设计，不能只做一半）；
 * ③ 文本门禁：源码里不再出现"每次新建客户端"的入口。
 */
class SummarizerHttpClientTest {

    @Test
    fun clientIsSharedAcrossRuns() {
        val first = KnowledgeBaseSummarizerRunner.httpClientFor("pw-1")
        val second = KnowledgeBaseSummarizerRunner.httpClientFor("pw-2")

        assertSame(
            "客户端必须是进程级单例，否则每跑一次摘要就漏一套连接池与线程池",
            first,
            second,
        )
    }

    @Test
    fun authorizationHeaderUsesBasicOpenCodeScheme() {
        val expected = "Basic " + Base64.getEncoder()
            .encodeToString("opencode:pw-3".toByteArray(Charsets.UTF_8))

        assertEquals(expected, KnowledgeBaseSummarizerRunner.basicAuthHeader("pw-3"))
    }

    @Test
    fun authorizationHeaderFollowsTheCurrentPassword() {
        val first = KnowledgeBaseSummarizerRunner.basicAuthHeader("first-pw")
        val second = KnowledgeBaseSummarizerRunner.basicAuthHeader("second-pw")

        assertTrue("鉴权头必须以 Basic 开头", first.startsWith("Basic "))
        assertFalse("口令不该以明文出现在头里", first.contains("first-pw"))
        assertNotEquals("口令换了，鉴权头必须跟着换", first, second)
    }

    @Test
    fun sourceNoLongerCreatesAClientPerRun() {
        val source = File(
            "src/main/java/com/example/zhengdao/terminal/KnowledgeBaseSummarizerRunner.kt"
        ).readText()

        assertFalse("不该再有「每次任务新建客户端」的入口 newClient(...)", source.contains("newClient("))
        assertEquals(
            "`OkHttpClient.Builder()` 只应出现一次（sharedClient 的懒建）",
            1,
            Regex("OkHttpClient\\.Builder\\(\\)").findAll(source).count(),
        )
        assertTrue(
            "共享客户端必须是懒建单例（by lazy），否则类初始化就会建连接池",
            source.contains("private val sharedClient: OkHttpClient by lazy"),
        )
    }
}
