// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十四批 · ConsiderLog 覆盖扩展（批次 1）：790 接入点接线断言。
// 判据① 用真 Ctx 夹具让全部候选"被否但在诊断圈内"（ERRATA 默认 diagnosticOnly）；
// 判据⑤ 用基线哈希证明算法源码零改。
package com.example.zhengdao.baopu.state.bearing

import com.example.zhengdao.baopu.state.consider.ConsiderLog
import com.example.zhengdao.baopu.state.consider.ConsiderStrategy
import com.example.zhengdao.baopu.state.consider.ConsiderVerdict
import com.example.zhengdao.baopu.state.form.BodyState
import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.geometry.Environment
import com.example.zhengdao.baopu.state.geometry.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ConsiderWiringTest {

    private val body: BodyState by lazy { FormFixtures.state() }

    private fun ctx(target: Vec3, bandCm: Double = 2.0): Ctx = Ctx(
        chain = BearingGeometry.armChain("r", body),
        staticSet = emptyList(),
        permissions = BearingGeometry.Permissions(emptySet(), emptySet()),
        env = Environment(0.0, 0.0),
        targetCm = target,
        bandCm = bandCm,
        side = "r",
        body = body,
    )

    private val zeroAngles: List<Double> get() = BearingGeometry.armChain("r", body).joints.map { 0.0 }

    /** 远处目标：全在诊断圈外 ⇒ 圈外不进主池（ERRATA），池应为空。 */
    private fun farCtx(bandCm: Double = 2.0): Ctx = ctx(Vec3(10000.0, 10000.0, 10000.0), bandCm)

    /**
     * "被否但在诊断圈内"夹具：目标放在零角指尖外侧 band+1cm —— err ∈ (band, band+margin]，
     * verdict=BLOCKED（被否），inDiagCircle=true（圈内）⇒ diagnosticOnly 默认会把它们全记下来。
     */
    private fun nearRejectedCtx(bandCm: Double = 2.0): Ctx {
        val chain = BearingGeometry.armChain("r", body)
        val end = chain.pose(zeroAngles).endEffectorCm
        val target = Vec3(end.x + bandCm + 1.0, end.y, end.z)
        return ctx(target, bandCm)
    }

    @Before
    fun reset() {
        BearingConsiderTracing.reset()
    }

    // ── 判据①：候选全被否决 ⇒ 池里仍看得到（记录下来的那批） ─────────────────

    @Test
    fun 判据一_全部候选被否决时池里仍有记录() {
        val ctx = nearRejectedCtx()
        val search = Search(ctx.bandCm)
        repeat(30) { search.consider(zeroAngles, ctx) }
        val pool = ConsiderLog.shared.query()
        assertTrue("空池⇒红：被否决但圈内的候选也必须看得到", pool.isNotEmpty())
        assertEquals("30 条全记（未到 64 硬顶）", 30, pool.size)
        assertTrue("全被否决 ⇒ 原因码全 BLOCKED", pool.all { it.verdict == ConsiderVerdict.BLOCKED })
        assertTrue("ERRATA 默认 strategy=diagnosticOnly", pool.all { it.strategy == ConsiderStrategy.DIAGNOSTIC_ONLY })
        assertTrue("产生器 id 固定", pool.all { it.generatorId == BearingConsiderTracing.GENERATOR_ID })
        assertTrue("sampledFrom=8192", pool.all { it.sampledFrom == BearingGeometry.SAMPLES })
    }

    @Test
    fun ERRATA_诊断圈外候选不进主池_空池是正确行为() {
        val ctx = farCtx()
        val search = Search(ctx.bandCm)
        repeat(BearingGeometry.SAMPLES) { k ->
            search.consider(
                ctx.chain.joints.mapIndexed { i, j ->
                    val u = BearingGeometry.halton(k + 1 + (i / BearingGeometry.BASES.size) * BearingGeometry.SAMPLES, BearingGeometry.BASES[i % BearingGeometry.BASES.size])
                    j.limit.minRad + u * (j.limit.maxRad - j.limit.minRad)
                },
                ctx,
            )
        }
        assertEquals("ERRATA：圈外一律不进主池（8192 全被判 BELOW_BAR）", 0, ConsiderLog.shared.size())
    }

    @Test
    fun 判据一_被否决候选与产物同在_不因无证据丢记录() {
        val ctx = nearRejectedCtx()
        val attempt = BearingSampler.evaluate(
            ctx.chain, ctx.staticSet, ctx.permissions, ctx.env, ctx.targetCm, ctx.bandCm, ctx.side, ctx.body,
            spanBlocked = false,
        )
        assertEquals("夹具走完 8192+细化", true, attempt.samplesTried >= BearingGeometry.SAMPLES)
        assertTrue("圈内候选被留痕（不随判定丢）", ConsiderLog.shared.size() > 0)
        assertTrue("留痕全是 ERRATA 默认策略", ConsiderLog.shared.query().all { it.strategy == ConsiderStrategy.DIAGNOSTIC_ONLY })
        assertEquals("单次写入 ≤64 ≪2000", true,
            BearingConsiderTracing.mainProbe.writtenMain() <= ConsiderLog.MAX_WRITE_PER_CALL)
    }

    // ── 判据二：四字段 + 两扩展字段 ──────────────────────────────────────────

    @Test
    fun 判据二_接入点写出的记录六字段齐() {
        val ctx = nearRejectedCtx()
        val search = Search(ctx.bandCm)
        repeat(8) { search.consider(zeroAngles, ctx) }
        val pool = ConsiderLog.shared.query()
        assertTrue(pool.isNotEmpty())
        val r = pool.first()
        assertTrue(r.generatorId.isNotBlank())
        assertTrue(r.inputDigest.isNotBlank())
        assertTrue(r.verdict in ConsiderVerdict.entries)
        assertTrue(r.atMs >= 0L)
        assertEquals(8192, r.sampledFrom)
        assertEquals(ConsiderStrategy.DIAGNOSTIC_ONLY, r.strategy)
        val line = ConsiderLog.shared.toJsonl()
        for (key in listOf("generatorId", "inputDigest", "verdict", "atMs", "sampledFrom", "strategy")) {
            assertTrue("JSONL 缺 $key", line.contains("\"$key\""))
        }
        assertTrue("strategy wire=diagnosticOnly", line.contains("\"strategy\":\"diagnosticOnly\""))
    }

    // ── 判据④：诊断开关 ──────────────────────────────────────────────────────

    @Test
    fun 判据四_开关关无diag_开关开diag独立() {
        val ctx = nearRejectedCtx()
        val angles = zeroAngles
        repeat(30) { Search(ctx.bandCm).consider(angles, ctx) }
        assertEquals("关：diag 池空", 0, BearingConsiderTracing.diagProbe.diagSize())
        val noDiag = File.createTempFile("zd844-wiring-off-", ".jsonl")
        try {
            assertEquals(0, BearingConsiderTracing.mainProbe.exportDiag(noDiag))
            assertEquals(0L, noDiag.length())
        } finally { noDiag.delete() }

        BearingConsiderTracing.reset()
        BearingConsiderTracing.diagnostic = true
        val search2 = Search(ctx.bandCm)
        repeat(30) { search2.consider(angles, ctx) }
        assertTrue("开：圈内候选进 diag 独立池", BearingConsiderTracing.diagProbe.diagSize() > 0)
        val diagLog = BearingConsiderTracing.diagProbe.diag!!
        assertTrue("diag 全 diagnosticOnly", diagLog.query().all { it.strategy == ConsiderStrategy.DIAGNOSTIC_ONLY })
        assertTrue("主池不混 diag 策略外内容",
            ConsiderLog.shared.query().all { it.strategy == ConsiderStrategy.DIAGNOSTIC_ONLY })
    }

    // ── 判据⑤：只加留痕，不改算法（源码 diff 断言） ──────────────────────────

    @Test
    fun 判据五_剥掉844标记行后BearingSampler与基线逐字节一致() {
        val dir = File("src/main/java/com/example/zhengdao/baopu/state/bearing")
        val fallback = File("../app/src/main/java/com/example/zhengdao/baopu/state/bearing")
        val root = if (dir.isDirectory) dir else fallback
        val src = File(root, "BearingSampler.kt").readText().replace("\r\n", "\n").replace("\r", "\n")
        val stripped = src.lines().filterNot { it.contains("[844]") }.joinToString("\n").trimEnd('\n') + "\n"
        val digest = MessageDigest.getInstance("SHA-256").digest(stripped.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "剥掉 [844] 标记行后须与接入前基线逐字节一致（算法零改）",
            "f23f9a24c96ab293292542494010da1b3b47356389dcef58c6dbb33725771744",
            digest,
        )
    }

    @Test
    fun 判据五_新生产件只在consider一处留痕_选优助手仍在() {
        val dir = File("src/main/java/com/example/zhengdao/baopu/state/bearing")
        val fallback = File("../app/src/main/java/com/example/zhengdao/baopu/state/bearing")
        val root = if (dir.isDirectory) dir else fallback
        val code = File(root, "BearingSampler.kt").readText().replace("\r\n", "\n")
        val marks = code.lines().count { it.contains("considerProbe.record(") }
        assertEquals("留痕调用必须恰好一处（Search.consider）", 1, marks)
        for (anchor in listOf(
            "insertLowError(err, angles)",
            "if (!diag.clean) return",
            "if (util < qualifyingUtil) {",
            "qualifyingAngles = angles",
            "fun refineFrom(seed: List<Double>, ctx: Ctx): Int {",
        )) {
            assertTrue("选优锚点被改动：$anchor", code.contains(anchor))
        }
    }

    // ── 判据⑥：确定性 ─────────────────────────────────────────────────────────

    @Test
    fun 判据六_同输入同输出_选优与留痕逐字节一致() {
        fun run(): Pair<String, String> {
            BearingConsiderTracing.reset()
            val ctx = nearRejectedCtx()
            val attempt = BearingSampler.evaluate(
                ctx.chain, ctx.staticSet, ctx.permissions, ctx.env, ctx.targetCm, ctx.bandCm, ctx.side, ctx.body,
                spanBlocked = false,
            )
            val fp = listOf(
                attempt.bestErrorCm.toString(), attempt.hasEvidence.toString(), attempt.samplesTried.toString(),
                attempt.bestViolations.map { "${it.kind}:${it.amountMilli}" }.joinToString(";"),
            ).joinToString("|")
            return fp to ConsiderLog.shared.toJsonl()
        }
        val a = run()
        val b = run()
        assertEquals("选优指纹逐字节一致", a.first, b.first)
        assertEquals("留痕逐字节一致", a.second, b.second)
    }
}
