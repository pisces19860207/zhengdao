// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 依据的公开接口：Android ContextWrapper（android.content）、JUnit 4（TemporaryFolder）。
package com.example.zhengdao.oc

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * B 步（2026-10-13，太极 401 自治愈）+ C 步（2026-10-13，改由 `/api/session` 探针定案）：
 * 用**合成 serve.log** + **假的鉴权探针**钉住密码解析这条路径。
 *
 * 为什么必须有接缝 [OcManager.serveProbeForTest]：C 步之后解析不再"看顺序猜"，而是拿每个
 * 候选口令去问 `GET /api/session`。单测里绝不能真连本机 14000 端口（万一那里恰好有服务，
 * 结果就会随环境漂移），所以把"哪个口令能过"用假探针写死在用例里。
 *
 * 真机 `files/oc/serve.log` 实录过两种顺序（见 OcManager.parseServePassword 的注释）：
 *   ① `server password <pw>` 先、`server listening on …` 后（调试期多场启动常见）
 *   ② 反过来（干净生产路径单场启动）
 *
 * 为什么是 [ContextWrapper]：`parseServePassword` 只用到 `ctx.filesDir`。单测 classpath 里
 * `android.test.mock.MockContext` 不可见（实测 unresolved），改用 `ContextWrapper(null)` 包一层，
 * 只覆写 [getFilesDir] / [getApplicationContext]（无 Robolectric）。
 * 该方法是 private，用反射调用 —— 这是刻意的：**不为测试放宽生产代码的可见性**。
 */
class OcServePasswordParseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pw1 = "he2Kd9fQx1sT"
    private val pw2 = "Zq7Lm4Bv0Rc8"

    private class FakeFilesContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
        override fun getApplicationContext(): Context = this
    }

    @After
    fun tearDown() {
        OcManager.serveProbeForTest = null   // 接缝用完必须还原，免得污染别的用例
    }

    /** [accepted] = 假设端口上那个 serve 认哪些口令（其余一律 401）。 */
    private fun parse(log: String, accepted: Set<String> = emptySet()): String? {
        OcManager.serveProbeForTest = { pw -> pw in accepted }
        val filesDir = tmp.newFolder()
        File(filesDir, "oc").apply { mkdirs() }
        File(File(filesDir, "oc"), "serve.log").writeText(log, Charsets.UTF_8)
        return parseIn(filesDir)
    }

    private fun parseIn(filesDir: File): String? {
        val method = OcManager::class.java
            .getDeclaredMethod("parseServePassword", Context::class.java)
        method.isAccessible = true
        method.invoke(OcManager, FakeFilesContext(filesDir))
        return OcManager.servePassword
    }

    @Test
    fun `单场 password 先 listening 后 —— 取该场密码`() {
        val log = """
            server password $pw1
            server listening on http://127.0.0.1:14000
        """.trimIndent() + "\n"
        assertEquals(pw1, parse(log, accepted = setOf(pw1)))
    }

    @Test
    fun `单场 listening 先 password 后 —— 也取该场密码`() {
        val log = """
            server listening on http://127.0.0.1:14000
            server password $pw1
        """.trimIndent() + "\n"
        assertEquals(pw1, parse(log, accepted = setOf(pw1)))
    }

    @Test
    fun `两场：最新口令能过鉴权 —— 取最新`() {
        val log = """
            server password $pw1
            server listening on http://127.0.0.1:14000
            server password $pw2
            server listening on http://127.0.0.1:14000
        """.trimIndent() + "\n"
        assertEquals(pw2, parse(log, accepted = setOf(pw2)))
    }

    @Test
    fun `两场：能过鉴权的是旧场次 —— 取旧（不再被日志顺序挡住）`() {
        val log = """
            server password $pw1
            server listening on http://127.0.0.1:14000
            server password $pw2
            server listening on http://127.0.0.1:14000
        """.trimIndent() + "\n"
        // 旧实现只按日志顺序取最后一个 listening 场 ⇒ 必然给 pw2（错的，活着的其实是 pw1 那场）
        assertEquals(pw1, parse(log, accepted = setOf(pw1)))
    }

    /**
     * ⭐ **C 步的翻案点**：这条以前断言"返回上一场旧密码"（现况刻画）。现在新场次的口令一旦
     * 能被端口接受就必须被选中，不再被 pending/awaitingPw 的中间态挡住。
     */
    @Test
    fun `末场已打密码但 listening 未落盘 —— 新口令可用就取新`() {
        val log = """
            server password $pw1
            server listening on http://127.0.0.1:14000
            server password $pw2
        """.trimIndent() + "\n"
        assertEquals(pw2, parse(log, accepted = setOf(pw2)))
    }

    @Test
    fun `候选口令都过不了鉴权 —— null（按未就绪处理，好过拿错口令到处 401）`() {
        val log = """
            server password $pw1
            server password $pw2
        """.trimIndent() + "\n"
        assertNull(parse(log, accepted = emptySet()))
    }

    @Test
    fun `日志文件不存在时返回 null 而不是抛异常`() {
        OcManager.serveProbeForTest = { true }   // 探针恒真也一样：没有日志就没有候选
        assertNull(parseIn(tmp.newFolder()))
    }
}
