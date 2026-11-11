// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十九批 · **A2A 准入接线的专项夹具**（判定件全是 546/384 的既有真件，本夹具钉的是接线）：
//   · 真考：九道题经 A2aExamChannel 送到 570 床、真卷落画像（accept_read_only——写类探针
//     对纯文本对端必不过 ⇒ 降权到只读，不是禁用），三本账（画像快照/准入账/出题账）齐；
//   · 缓存：同能力指纹第二次不重考（出题次数不涨——546 判据③在 A2A 侧的读数）；
//   · 重考：能力指纹变 ⇒ 旧卷作废重考（needsReprobe 说真话的根据）；
//   · fail-closed：准入落点没装配 ⇒ no_root 不放行。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.intake.AdmissionFlow
import com.example.zhengdao.baopu.intake.Clearance
import com.example.zhengdao.baopu.intake.IntakeExam
import com.example.zhengdao.testkit.Peer570A2aBed
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnvoyAdmissionTest {

    private lateinit var bed: Peer570A2aBed
    private lateinit var root: File
    private lateinit var admitRoot: File

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        bed = Peer570A2aBed().also { it.start() }
        root = TempSandbox.create("zd549-adm")
        admitRoot = File(root, "准入").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        bed.close()
        assertTrue("沙盒要删干净（水位纪律）", TempSandbox.deleteTree(root) == 0)
    }

    private fun client(): A2aClient = A2aClient(bed.base(), http)

    private fun ensure(version: String? = null): EnvoyAdmissionReading {
        val c = client()
        val card = c.fetchCard()
        return EnvoyAdmission.ensure(
            root = admitRoot,
            endpoint = bed.base(),
            executorVersion = version ?: card.capabilityFingerprint(),
            channel = A2aExamChannel(c, card, pollEveryMs = 1, maxWaitMs = 5_000, sleeper = { true }),
            claimed = card.skills.map { it.id },
            nowMs = System.currentTimeMillis(),
            log = {},
        )
    }

    @Test
    fun `真考_九道题上床_accept_read_only_三本账齐`() {
        val reading = ensure()
        assertTrue("床的考生考完进了只读档", reading.admitted)
        assertEquals("accept_read_only", reading.verdictWire)
        assertEquals(Clearance.READ_ONLY, reading.tier)
        assertTrue("这一次是真考的", reading.examined)
        assertEquals("稳定 id 形状（ag- 前缀）", "ag-", reading.agentId.take(3))

        val snapshot = File(admitRoot, AdmissionFlow.snapshotFile(admitRoot, reading.agentId).name)
        assertTrue("画像快照落盘", snapshot.isFile)
        assertTrue("准入账落盘", File(admitRoot, AdmissionFlow.LEDGER_FILE).isFile)
        val examLedger = File(admitRoot, IntakeExam.LEDGER_FILE).readLines().filter { it.isNotBlank() }
        assertEquals("出题账一行（真考一次）", 1, examLedger.size)
        assertTrue("九道题都出了", examLedger.single().contains("\"asked\":9"))
        assertTrue("结论如实：探针没全过（写类对纯文本对端必不过）", examLedger.single().contains("unverifiable\":false"))
    }

    @Test
    fun `同能力指纹第二次_不重考_出题次数不涨`() {
        ensure()
        val rpcAfterFirst = bed.rpcMethods.size
        val ledgerAfterFirst = File(admitRoot, IntakeExam.LEDGER_FILE).readLines().filter { it.isNotBlank() }.size

        val second = ensure()
        assertFalse("第二次不是真考（画像还在且版本对得上）", second.examined)
        assertEquals("同一结论", "accept_read_only", second.verdictWire)
        assertEquals("床上没有新字节（零考卷）", rpcAfterFirst, bed.rpcMethods.size)
        assertEquals("出题账没有新行（出题次数不涨）", ledgerAfterFirst, File(admitRoot, IntakeExam.LEDGER_FILE).readLines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun `能力指纹变_旧卷作废重考`() {
        ensure()
        val rpcAfterFirst = bed.rpcMethods.size
        val second = ensure(version = "capability-fingerprint-changed")
        assertTrue("指纹变 ⇒ 重考", second.examined)
        assertTrue("床上又有考卷字节了", bed.rpcMethods.size > rpcAfterFirst)
        val examLedger = File(admitRoot, IntakeExam.LEDGER_FILE).readLines().filter { it.isNotBlank() }
        assertEquals("出题账两行（各考一次）", 2, examLedger.size)
    }

    @Test
    fun `落点没装配_fail_closed_no_root_不放行`() {
        val c = client()
        val card = c.fetchCard()
        val reading = EnvoyAdmission.ensure(
            root = null,
            endpoint = bed.base(),
            executorVersion = card.capabilityFingerprint(),
            channel = A2aExamChannel(c, card, pollEveryMs = 1, maxWaitMs = 5_000, sleeper = { true }),
            claimed = card.skills.map { it.id },
            nowMs = System.currentTimeMillis(),
            log = {},
        )
        assertFalse("闸宁可错关", reading.admitted)
        assertEquals(EnvoyAdmission.NO_ROOT, reading.verdictWire)
        assertEquals("零考卷字节", 0, bed.rpcMethods.size)
        assertFalse("快照也没落", File(admitRoot, "准入画像-${reading.agentId}.json").isFile)
    }
}
