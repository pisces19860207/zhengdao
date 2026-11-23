// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十四批 · ConsiderLog 覆盖扩展（批次 1）：790 接入点接线断言。
// 判据① 用真 Ctx 夹具让全部候选被否决；判据⑤ 用基线哈希证明算法源码零改。
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

    /** 真链 + 指定目标：近处（零角指尖）用于诊断圈内夹具，远处用于全否决夹具。 */
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

    /** 真链 + 远处目标：全部候选误差远大于带宽 ⇒ 全被否决（无合格证据）。 */
    private fun farCtx(bandCm: Double = 2.0): Ctx = ctx(Vec3(10000.0, 10000.0, 10000.0), bandCm)

    @Before
    fun reset() {
        BearingConsiderTracing.reset()
    }

    // ── 判据①：候选全被否决 ⇒ 池里仍看得到（记录下来的那批） ─────────────────

    @Test
    fun 判据一_全部候选被否决时池里仍有记录() {
        val ctx = farCtx()
        val search = Search(ctx.bandCm)
        // 8192 点原始采样：目标极远，逐一 consider 全部 BELOW_BAR（无合格构型）。
        repeat(BearingGeometry.SAMPLES) { k ->
            search.consider(
                ctx.chain.joints.mapIndexed { i, j ->
                    val u = BearingGeometry.halton(k + 1 + (i / BearingGeometry.BASES.size) * BearingGeometry.SAMPLES, BearingGeometry.BASES[i % BearingGeometry.BASES.size])
                    j.limit.minRad + u * (j.limit.maxRad - j.limit.minRad)
                },
                ctx,
            )
        }
        assertFalse("夹具须真做到无合格证据", search.hasEvidence)
        val pool = ConsiderLog.shared.query()
        assertTrue("空池⇒红：候选全被否决也必须留痕", pool.isNotEmpty())
        assertEquals("stride 64 条硬顶", 64, pool.size)
        assertTrue("全部被否决 ⇒ 原因码应全为 BELOW_BAR", pool.all { it.verdict == ConsiderVerdict.BELOW_BAR })
        assertTrue("产生器 id 固定", pool.all { it.generatorId == BearingConsiderTracing.GENERATOR_ID })
    }

    @Test
    fun 判据一_被否决候选与产物同在_不因无证据丢记录() {
        val ctx = farCtx()
        val attempt = BearingSampler.evaluate(
            ctx.chain, ctx.staticSet, ctx.permissions, ctx.env, ctx.targetCm, ctx.bandCm, ctx.side, ctx.body,
            spanBlocked = false,
        )
        assertFalse("夹具：无合格证据", attempt.hasEvidence)
        assertEquals("夹具：最近误差仍巨大", true, attempt.bestErrorCm > 100.0)
        assertTrue("留痕不随无证据而空", ConsiderLog.shared.size() > 0)
        assertEquals("8192 采样 + 细化，单次写入仍 ≤64 ≪2000", true,
            BearingConsiderTracing.mainProbe.writtenMain() <= ConsiderLog.MAX_WRITE_PER_CALL)
    }

    // ── 判据二：四字段 + 两扩展字段 ──────────────────────────────────────────

    @Test
    fun 判据二_接入点写出的记录六字段齐() {
        val ctx = farCtx()
        val search = Search(ctx.bandCm)
        repeat(200) { k -> search.consider(ctx.chain.joints.map { 0.0 }, ctx) }
        val pool = ConsiderLog.shared.query()
        assertTrue(pool.isNotEmpty())
        val r = pool.first()
        assertTrue(r.generatorId.isNotBlank())
        assertTrue(r.inputDigest.isNotBlank())
        assertTrue(r.verdict in ConsiderVerdict.entries)
        assertTrue(r.atMs >= 0L)
        assertEquals(8192, r.sampledFrom)
        assertTrue(r.strategy in ConsiderStrategy.entries)
        val line = ConsiderLog.shared.toJsonl()
        for (key in listOf("generatorId", "inputDigest", "verdict", "atMs", "sampledFrom", "strategy")) {
            assertTrue("JSONL 缺 $key", line.contains("\"$key\""))
        }
    }

    // ── 判据④：诊断开关 ──────────────────────────────────────────────────────

    @Test
    fun 判据四_开关关无diag_开关开diag独立() {
        val ctx = farCtx()
        val search = Search(ctx.bandCm)
        val angles = ctx.chain.joints.map { 0.0 }
        repeat(100) { search.consider(angles, ctx) }
        assertEquals("关：diag 池空", 0, BearingConsiderTracing.diagProbe.diagSize())
        val noDiag = File.createTempFile("zd844-wiring-off-", ".jsonl")
        try {
            assertEquals(0, BearingConsiderTracing.mainProbe.exportDiag(noDiag))
            assertEquals(0L, noDiag.length())
        } finally { noDiag.delete() }

        BearingConsiderTracing.reset()
        BearingConsiderTracing.diagnostic = true
        val search2 = Search(ctx.bandCm)
        // 远处目标误差极大 ⇒ inDiagCircle=false ⇒ diag 不记（正确：诊断圈外不该进 diag）。
        repeat(100) { search2.consider(angles, ctx) }
        assertEquals("诊断圈外不进 diag", 0, BearingConsiderTracing.diagProbe.diagSize())

        // 近处目标：诊断圈内应进 diag。
        BearingConsiderTracing.reset()
        BearingConsiderTracing.diagnostic = true
        val nearCtx = ctx(ctx.chain.pose(angles).endEffectorCm)
        val search3 = Search(nearCtx.bandCm)
        repeat(100) { search3.consider(angles, nearCtx) }
        assertTrue("诊断圈内候选应进 diag 独立池", BearingConsiderTracing.diagProbe.diagSize() > 0)
        val diagLog = BearingConsiderTracing.diagProbe.diag!!
        assertTrue("diag 全为 diagnosticOnly", diagLog.query().all { it.strategy == ConsiderStrategy.DIAGNOSTIC_ONLY })
        assertTrue("主池不混 diagnosticOnly",
            ConsiderLog.shared.query(strategy = ConsiderStrategy.DIAGNOSTIC_ONLY).isEmpty())
    }

    // ── 判据⑤：只加留痕，不改算法（源码 diff 断言） ──────────────────────────

    @Test
    fun 判据五_剥掉844标记行后BearingSampler与基线逐字节一致() {
        val dir = File("src/main/java/com/example/zhengdao/baopu/state/bearing")
        val fallback = File("../app/src/main/java/com/example/zhengdao/baopu/state/bearing")
        val root = if (dir.isDirectory) dir else fallback
        val src = File(root, "BearingSampler.kt").readText().replace("\r\n", "\n").replace("\r", "\n")
        // 规范化到一个且仅一个结尾换行（Kotlin lines() 会留一个尾部空串，先 trim 再补）。
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
        // 留痕调用恰好一处：只在 consider 里 record，refineFrom 的 consider 调用保持原样。
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
            val ctx = farCtx()
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
