// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十七批 · 时刻词禁令的门禁（派工单追加判据⑩：**正则扫相对词 ⇒ 0 命中**）。
//
// 扫两处，两处都必须 0 命中：
//   ① **源码**：账本/判据住的四个包（consequence／escalate／evolution／trace），
//     剥掉注释后正则扫 [AbsoluteTimeGuard.RELATIVE_WORDS] ——写账的代码里就不许有相对词，
//     有一个就是有人想拿"过会儿"冒充时刻；
//   ② **账盘**：走一遍生产写口（深度账／升级账／边界执行账／身份表／凭据表／决策记录），
//     对落盘的字节正则扫同一张词表 ⇒ 0 命中；且所有时刻键的值都是 epoch 毫秒（数字）。
package com.example.zhengdao.baopu.consequence

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.escalate.BoundaryTable
import com.example.zhengdao.baopu.escalate.BoundaryRow
import com.example.zhengdao.baopu.escalate.CapabilityKind
import com.example.zhengdao.baopu.escalate.Escalation
import com.example.zhengdao.baopu.escalate.EscalationAnswer
import com.example.zhengdao.baopu.escalate.EscalationTrigger
import com.example.zhengdao.baopu.escalate.SayFact
import com.example.zhengdao.baopu.escalate.StopFact
import com.example.zhengdao.baopu.escalate.StopMode
import com.example.zhengdao.baopu.evolution.CostFacts
import com.example.zhengdao.baopu.evolution.EvidenceFacts
import com.example.zhengdao.baopu.evolution.EvolutionDepth
import com.example.zhengdao.law.LawIo
import java.io.File
import java.time.ZoneId
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AbsoluteTimeGateTest {

    private lateinit var root: File
    private lateinit var filesDir: File
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        root = Files.createTempDirectory("zd517-time-ledger-").toFile()
        filesDir = Files.createTempDirectory("zd517-time-files-").toFile()
        DecisionLedger.install(root)
        ConsequenceWiring.install(filesDir, now)
        EvolutionDepth.resetForTest()
        BoundaryTable.resetForTest()
        Escalation.resetForTest()
    }

    @After
    fun tearDown() {
        EvolutionDepth.resetForTest()
        BoundaryTable.resetForTest()
        Escalation.resetForTest()
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        root.deleteRecursively()
        filesDir.deleteRecursively()
    }

    // ── 判据件本体 ──────────────────────────────────────────────────────────

    @Test
    fun `判据件_词表三连_命中_渲染口非空必绝对`() {
        assertTrue(AbsoluteTimeGuard.hasRelative("明天再跑"))
        assertEquals(listOf("刚刚", "过会儿"), AbsoluteTimeGuard.hits("刚刚说过会儿再跑"))
        assertFalse(AbsoluteTimeGuard.hasRelative("2026-11-07T22:00:00+08:00 收工"))
        // 渲染口：0/负数 ⇒ 空串（不渲染假时刻）；有值 ⇒ ISO 带时区偏移
        assertEquals("", AbsoluteTimeGuard.absoluteOrBlank(0L))
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}""").matches(AbsoluteTimeGuard.iso(now)))
        // 2026-11-08 钉平台无关：CI（TZ=UTC）下 ISO_OFFSET_DATE_TIME 会渲染成 `…Z`，
        // 本机（+08:00）看不到 Z ⇒ 三个用例只在 CI 红。零偏移也必须写成数字偏移 `+00:00`。
        assertEquals(
            "零偏移也得是数字偏移（不许 Z）",
            "2023-11-14T22:13:20+00:00",
            AbsoluteTimeGuard.iso(now, ZoneId.of("UTC")),
        )
    }

    // ── 门禁①：源码 0 命中 ─────────────────────────────────────────────────

    @Test
    fun `门禁一_账本与判据四个包_剥注释后相对时刻词零命中`() {
        val scopes = listOf(
            "baopu/consequence/",
            "baopu/escalate/",
            "baopu/evolution/",
            "baopu/trace/",
        )
        val bad = LawIo.sources(LawIo.MAIN_ROOT)
            .filter { (rel, _) -> scopes.any { rel.contains(it) } }
            // 词表本体（AbsoluteTimeGuard.kt）必须写这些词——它就是用来抓它们的；
            // 除它之外，账本/判据的源码一个相对词都不许有。
            .filter { (rel, _) -> !rel.endsWith("consequence/AbsoluteTimeGuard.kt") }
            .flatMap { (rel, raw) -> AbsoluteTimeGuard.hits(LawIo.stripComments(raw)).map { "$rel → $it" } }
        assertEquals("账本/判据的源码里不许出现相对时刻词（U5）：$bad", emptyList<String>(), bad)
    }

    // ── 门禁②：账盘 0 命中＋时刻键全是 epoch ────────────────────────────────

    @Test
    fun `门禁二_走一遍生产写口_账盘字节里相对词零命中_时刻键全是epoch`() {
        // 深度账：定一次档
        EvolutionDepth.adjudicate(CostFacts(), EvidenceFacts(), now)
        // 升级账：开一件、答一件（升级请求.jsonl）
        val req = Escalation.raise(
            capability = CapabilityKind.FILE_WRITE,
            trigger = EscalationTrigger.OUT_OF_SCOPE,
            stop = StopFact(StopMode.SAFE_ABORT, "run-517", "②段红线拦下 write_file，动作未发生"),
            say = SayFact("要一次越界授权", "目标在声明边界之外", "换个边界内的目标"),
            nowMs = now,
        )
        Escalation.respond(req.id, EscalationAnswer.REJECT, now + 1)
        // 边界执行账：装表（缺行）＋拒一笔
        BoundaryTable.install(root) { emptyMap() }
        BoundaryTable.checkWrite(CapabilityKind.FILE_WRITE, File(root, "nowhere/x"), now + 2)
        // 身份表＋凭据表＋决策记录：撤销一次（身份行、legacy 不涉；决策记录落身份行）
        ConsequenceWiring.mutateIdentity(
            IdentityMutation(IdentityRules.ACTOR_USER, IdentityRules.OP_REVOKE),
            now + 3,
        )

        val ledgerFiles = listOf(
            File(root, "进化深度.jsonl"),
            File(root, "升级请求.jsonl"),
            File(root, "边界执行.jsonl"),
            File(root, "决策记录.jsonl"),
            ConsequenceDirs.identityFile(filesDir),
            ConsequenceDirs.credentialFile(filesDir),
            File(ConsequenceDirs.root(filesDir), "恢复标记.json"),
        )
        val badWords = ledgerFiles.filter { it.isFile }.flatMap { f ->
            AbsoluteTimeGuard.hits(f.readText(Charsets.UTF_8)).map { "${f.name} → $it" }
        }
        assertEquals("账盘里不许有相对时刻词（U5）：$badWords", emptyList<String>(), badWords)

        // 时刻键检查：所有 json 行里出现的时刻键，值必须是数字（epoch 毫秒）
        val timeKeys = setOf("at", "revokedAt", "expiresAt", "restoredAt", "createdAt")
        val badKeys = ledgerFiles.filter { it.isFile && it.name.endsWith(".jsonl") }.flatMap { f ->
            f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.flatMapIndexed { idx, line ->
                val o = JSONObject(line)
                timeKeys.filter { o.has(it) && !o.isEpoch(it) }.map { "${f.name} 第${idx + 1}行 $it 不是 epoch" }
            }
        }
        assertEquals(badKeys.toString(), emptyList<String>(), badKeys)
    }

    /** 时刻键的值必须是 epoch 毫秒（数字）——字符串时刻也不收（U5：epoch 或 ISO，账行用 epoch）。 */
    private fun JSONObject.isEpoch(key: String): Boolean = runCatching { getLong(key) }.isSuccess
}
