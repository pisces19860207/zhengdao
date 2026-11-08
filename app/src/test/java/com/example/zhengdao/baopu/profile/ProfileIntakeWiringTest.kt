// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十九批 · 画像装配接线的判据测试（派工单 §二①–⑤）：
//   ① 生产赋值点不再是 null（源码 grep）；② 有稳定习惯 ⇒ 同类问题不弹卡；
//   ③ 画像为空 ⇒ 与接线前逐字一致；④ 开关关 ⇒ 回 null；⑤ 采样原文不进代答文本。
// 造画像只走 UserProfileBlackbox.feed（唯一写口）。两道闸分开钉：「≥2 来源」是写入闸（feed 侧
// 记不记），「≥20 有效样本＋连续 14 天」是免问闸——所以正例造 40 条（两道都过），另钉 2 条
// 仍问（写入过了、免问不过 ⇒ 回 null，不造数）。
package com.example.zhengdao.baopu.profile

import com.example.zhengdao.baopu.agent.AskClass
import com.example.zhengdao.baopu.agent.AskGovLedger
import com.example.zhengdao.baopu.agent.AskIntakeGate
import com.example.zhengdao.baopu.agent.AskIntakeOutcome
import com.example.zhengdao.baopu.agent.ProfileBoundary
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileIntakeWiringTest {

    private val day = TwoLayerProfile.DAY_MS
    private val t0 = 1_790_000_000_000L

    private fun tmp(tag: String): File =
        File.createTempFile("zd529-wire-$tag", "").let { it.delete(); it.mkdirs(); it }

    @After
    fun tearDown() {
        ProfileIntakeWiring.resetForTest()
        UserProfileBlackbox.resetForTest()
        AskIntakeGate.resetForTest()
        AskGovLedger.resetForTest()
    }

    private fun freshInstall(tag: String) {
        UserProfileBlackbox.install(File(tmp(tag), UserProfileBlackbox.INTERNAL_DIR))
    }

    /** 造一份「preference 类已成稳定偏好」的画像：t0 喂 30 条起算，15 天后再喂 10 条。 */
    private fun stablePreference(now: Long) {
        repeat(30) {
            UserProfileBlackbox.feed("preference", 1, true, t0, screenOn = true, foreground = true)
        }
        // 起算「连续 2 周」的钟（读侧复评时才起算）
        UserProfileBlackbox.isStableKind("preference", t0)
        repeat(10) {
            UserProfileBlackbox.feed("preference", 1, true, now, screenOn = true, foreground = true)
        }
    }

    private fun code(rel: String): String {
        val f = File(rel).takeIf { it.isFile } ?: File("app/$rel")
        return f.readText(Charsets.UTF_8).lines()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }.joinToString("\n")
    }

    // ── 判据①：生产赋值点不再是 null ────────────────────────────────────────

    @Test
    fun `判据①_装配层挂上读数口_不再是恒null`() {
        val runtime = code("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue(
            "BaopuRuntime 必须有 ProfileIntakeWiring.install 的生产调用",
            runtime.contains("ProfileIntakeWiring.install("),
        )
        val wiring = code("src/main/java/com/example/zhengdao/baopu/profile/ProfileIntakeWiring.kt")
        assertTrue(
            "读数口必须真的赋给 AskIntakeGate.profileProvider（非 null）",
            wiring.contains("AskIntakeGate.profileProvider = { cls -> answerFor(cls) }"),
        )
    }

    // ── 判据②：有稳定习惯 ⇒ 同类问题不问 ────────────────────────────────────

    @Test
    fun `判据②_稳定习惯在档_偏好类问题不弹卡`() {
        freshInstall("known")
        val now = t0 + 15 * day
        stablePreference(now)
        ProfileIntakeWiring.install(now = { now })
        val out = AskIntakeGate.beforeAsk(
            title = "配色偏好选哪种",
            taskId = "t-known",
            dayKey = 52_900,
            nowMs = now,
        )
        assertTrue("同类问题应被判已知从而不弹卡：$out", out is AskIntakeOutcome.Answered)
        val text = (out as AskIntakeOutcome.Answered).text
        assertTrue(text.contains("未弹卡"))
        assertTrue(text.contains("长期做法"))
        assertTrue(
            "自决必须落账",
            AskGovLedger.replay().any { it.kind == AskGovLedger.KIND_PROFILE },
        )
    }

    @Test
    fun `判据②反钉_只有两条事实_仍问且不造数`() {
        freshInstall("few")
        val now = t0 + 15 * day
        // 两条只过写入闸（记下了），过不了免问闸（<20 有效样本）⇒ 本口必为 null。
        repeat(2) {
            UserProfileBlackbox.feed("preference", 1, true, now, screenOn = true, foreground = true)
        }
        ProfileIntakeWiring.install(now = { now })
        assertNull("样本不足 ⇒ 没有画像读数（弱先验闸不放宽）", ProfileIntakeWiring.answerFor(AskClass.PREFERENCE))
        val out = AskIntakeGate.beforeAsk(title = "配色偏好选哪种", taskId = "t-few", dayKey = 52_901, nowMs = now)
        assertTrue("两条事实买不到免问：$out", out is AskIntakeOutcome.Proceed)
    }

    // ── 判据③：画像为空 ⇒ 与接线前逐字一致 ──────────────────────────────────

    @Test
    fun `判据③_画像为空_行为与接线前逐字一致`() {
        val title = "配色偏好选哪种"
        // 接线前：profileProvider = null
        AskIntakeGate.resetForTest()
        val before = AskIntakeGate.beforeAsk(title = title, taskId = "t-empty", dayKey = 52_902, nowMs = t0)
        assertTrue(before is AskIntakeOutcome.Proceed)
        AskIntakeGate.resetForTest()
        AskGovLedger.resetForTest()
        // 接线后：黑箱装配了但一条画像都没有
        freshInstall("empty")
        ProfileIntakeWiring.install(now = { t0 })
        assertNull(ProfileIntakeWiring.answerFor(AskClass.PREFERENCE))
        val after = AskIntakeGate.beforeAsk(title = title, taskId = "t-empty", dayKey = 52_902, nowMs = t0)
        assertTrue(after is AskIntakeOutcome.Proceed)
        assertEquals(
            "空画像 ⇒ 卡面与接线前逐字一致",
            (before as AskIntakeOutcome.Proceed).note,
            (after as AskIntakeOutcome.Proceed).note,
        )
    }

    @Test
    fun `判据③_黑箱未装配_也是null`() {
        UserProfileBlackbox.resetForTest()
        ProfileIntakeWiring.install()
        assertNull(ProfileIntakeWiring.answerFor(AskClass.PREFERENCE))
    }

    // ── 判据④：开关关掉 ⇒ 回 null 行为 ─────────────────────────────────────

    @Test
    fun `判据④_开关关掉_有画像也回null`() {
        freshInstall("off")
        val now = t0 + 15 * day
        stablePreference(now)
        var on = false
        ProfileIntakeWiring.install(enabled = { on }, now = { now })
        assertNull("开关关 ⇒ 不查画像", ProfileIntakeWiring.answerFor(AskClass.PREFERENCE))
        val off = AskIntakeGate.beforeAsk(title = "配色偏好选哪种", taskId = "t-off", dayKey = 52_903, nowMs = now)
        assertTrue("开关关 ⇒ 卡照弹：$off", off is AskIntakeOutcome.Proceed)
        // 同一份画像拨开 ⇒ 不弹（证明差异只在开关）
        on = true
        val out = AskIntakeGate.beforeAsk(title = "语气偏好正式点吗", taskId = "t-on", dayKey = 52_903, nowMs = now)
        assertTrue("开关开 ⇒ 免问：$out", out is AskIntakeOutcome.Answered)
    }

    // ── 判据⑤：采样原文不进代答文本（负例） ─────────────────────────────────

    @Test
    fun `判据⑤_代答文本无采样原文与哈希`() {
        freshInstall("nosha")
        val now = t0 + 15 * day
        val sha = "deadbeefcafe1234"
        repeat(30) {
            UserProfileBlackbox.feed("preference", 1, true, t0, screenOn = true, foreground = true, textSha = sha, textLen = 123)
        }
        UserProfileBlackbox.isStableKind("preference", t0)
        repeat(10) {
            UserProfileBlackbox.feed("preference", 1, true, now, screenOn = true, foreground = true, textSha = sha, textLen = 123)
        }
        ProfileIntakeWiring.install(now = { now })
        val out = AskIntakeGate.beforeAsk(title = "配色偏好选哪种", taskId = "t-nosha", dayKey = 52_904, nowMs = now)
        assertTrue(out is AskIntakeOutcome.Answered)
        val text = (out as AskIntakeOutcome.Answered).text
        assertFalse("代答文本不许带采样哈希", text.contains(sha))
        assertFalse("代答文本不许带采样字段", text.contains("textSha") || text.contains("textLen"))
        // 结构负例：本件不碰上下文拼装（画像符号不进 ContextAssembler 由 ProfileInvisibilityTest 钉）
        val wiring = code("src/main/java/com/example/zhengdao/baopu/profile/ProfileIntakeWiring.kt")
        assertFalse(wiring.contains("ContextAssembler"))
        assertFalse(wiring.contains("MemoryInjection"))
    }

    // ── 授权类：画像连读数都不给（499 边界的生产侧） ─────────────────────────

    @Test
    fun `授权类_画像恒null_卡照弹`() {
        freshInstall("authz")
        val now = t0 + 15 * day
        repeat(30) {
            UserProfileBlackbox.feed("authorization", 1, true, t0, screenOn = true, foreground = true)
        }
        UserProfileBlackbox.isStableKind("authorization", t0)
        repeat(10) {
            UserProfileBlackbox.feed("authorization", 1, true, now, screenOn = true, foreground = true)
        }
        ProfileIntakeWiring.install(now = { now })
        assertNull("授权类没有画像读数", ProfileIntakeWiring.answerFor(AskClass.AUTHORIZATION))
        val out = AskIntakeGate.beforeAsk(title = "可以删除这个目录里的旧备份吗", taskId = "t-authz", dayKey = 52_905, nowMs = now)
        assertTrue("授权类卡照弹（真人给）：$out", out is AskIntakeOutcome.Proceed)
        // 行为这层是「双保险」（候选类里本来就没有授权档），所以还要把**短路那句**钉在源码上：
        // 以后有人把候选扩到授权档时，第一行这道闸不许先没（变异演练 M5 验它咬得住）。
        val src = code("src/main/java/com/example/zhengdao/baopu/profile/ProfileIntakeWiring.kt")
        val firstStmt = src.substringAfter("fun answerFor(cls: AskClass): ProfileBoundary.ProfileAnswer? {")
            .lines().first { it.isNotBlank() && !it.trim().startsWith("//") }.trim()
        assertTrue("授权类短路必须是函数第一句：[$firstStmt]",
            firstStmt == "if (cls == AskClass.AUTHORIZATION) return null")
    }

    // ── Q2：样本字段是下限声明，置信度只取后验份额（不参与比率） ────────────

    @Test
    fun `Q2_样本字段报免问闸下限_置信度只来自后验份额`() {
        freshInstall("floor")
        val now = t0 + 15 * day
        stablePreference(now)
        ProfileIntakeWiring.install(now = { now })
        val ans = ProfileIntakeWiring.answerFor(AskClass.PREFERENCE)
        assertNotNull("这份画像是免问档（本条前提）", ans)
        assertEquals("样本字段报的是免问闸下限，不是测量值", ProfileBoundary.MIN_SAMPLES, ans!!.sampleCount)
        val kinds = AskClass.values().filter { it != AskClass.AUTHORIZATION }.map { it.wire }
        val share = UserProfileBlackbox.distribution(now, kinds).getValue("preference")
        assertEquals("置信度＝后验份额（与样本字段无关）", share, ans.confidence, 1e-9)
        // 结构钉：置信那行只许写 share；样本下限那两处不许出现在任何除法/乘法里。
        val src = code("src/main/java/com/example/zhengdao/baopu/profile/ProfileIntakeWiring.kt")
        assertTrue("置信赋值行要就是 confidence = share", src.lines().any { it.trim() == "confidence = share," })
        val floorLines = src.lines().filter { it.contains("SAMPLE_COUNT_FLOOR") }
        assertEquals("下限只许出现在声明与赋值两处：$floorLines", 2, floorLines.size)
        assertTrue("下限不许进任何比率算式：$floorLines", floorLines.none { it.contains("/") || it.contains("*") })
    }
}