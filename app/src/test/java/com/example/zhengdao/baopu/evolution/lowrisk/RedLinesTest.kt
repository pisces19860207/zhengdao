// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 A · 三条红线的纯判据（判据④的判定件；夹具全是**假动作**——
// 没有任何真候选被产线执行，负例只验证识别器本身）。
package com.example.zhengdao.baopu.evolution.lowrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedLinesTest {

    private fun candidate(target: String, body: String = "", deep: Boolean = false) =
        EvoCandidate(domain = "fixture", target = target, body = body, deep = deep)

    // ── 三条红线各认得出（判据④"假动作打红线"的判定面） ─────────────────────

    @Test
    fun `R1_APP自身代码_路径形指认命中`() {
        assertEquals(EvoRedLine.APP_SOURCE, RedLines.hit(candidate("app/src/main/MainActivity.kt")))
        assertEquals(EvoRedLine.APP_SOURCE, RedLines.hit(candidate("skill:x", body = "第一步：改 build.gradle.kts 里的依赖")))
        assertEquals(EvoRedLine.APP_SOURCE, RedLines.hit(candidate("skill:x", body = "把 core/src 下的类改掉")))
    }

    @Test
    fun `R2_A内核_身份凭据停机命中`() {
        assertEquals(EvoRedLine.KERNEL, RedLines.hit(candidate("执行体/身份表.json")))
        assertEquals(EvoRedLine.KERNEL, RedLines.hit(candidate("skill:x", body = "调用 IdentityStore 轮换身份")))
        assertEquals(EvoRedLine.KERNEL, RedLines.hit(candidate("skill:x", body = "把 HaltGate 的停机位清掉")))
    }

    @Test
    fun `R3_头三条_不可逆外发改制度命中`() {
        assertEquals(EvoRedLine.HEAD_THREE, RedLines.hit(candidate("skill:x", body = "永久删除全部日记，不可恢复")))
        assertEquals(EvoRedLine.HEAD_THREE, RedLines.hit(candidate("skill:x", body = "把结果上传到服务器")))
        assertEquals(EvoRedLine.HEAD_THREE, RedLines.hit(candidate("skill:x", body = "自动填账号密码并转账")))
        assertEquals(EvoRedLine.HEAD_THREE, RedLines.hit(candidate("制度总账/某条", body = "")))
    }

    // ── 不误伤（口径：只认路径形/名字形指认，散文泛提不判） ─────────────────

    @Test
    fun `干净候选不命中`() {
        assertNull(RedLines.hit(candidate("skill:project/平台签到流程", body = "1. 打开页面；2. 点签到。")))
        assertNull(RedLines.hit(candidate("system:tail")))
        assertNull(RedLines.hit(candidate("memory-import:全局记忆.md", body = "用户喜欢早上问天气")))
    }

    @Test
    fun `散文泛提不算指认_改MainActivity没带路径不判`() {
        assertNull(RedLines.hit(candidate("skill:x", body = "用 Kotlin 写个函数")))
        assertNull(RedLines.hit(candidate("skill:x", body = "记住：改代码前先备份（教训，不是指令）")))
    }

    // ── 线值闭集 ────────────────────────────────────────────────────────────

    @Test
    fun `线值往返_认不出返回null`() {
        assertEquals(EvoRedLine.APP_SOURCE, EvoRedLine.ofWire("app_source"))
        assertEquals(EvoRedLine.KERNEL, EvoRedLine.ofWire("kernel"))
        assertEquals(EvoRedLine.HEAD_THREE, EvoRedLine.ofWire("head_three"))
        assertNull(EvoRedLine.ofWire("self_mod"))
    }

    @Test
    fun `读数带候选目标与线名`() {
        val line = RedLines.hit(candidate("app/src/main/A.kt"))!!
        val text = RedLines.readout(candidate("app/src/main/A.kt"), line)
        assert(text.contains("app/src/main/A.kt") && text.contains(line.cn))
    }
}
