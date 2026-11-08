// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十七批 · 租约闸在**真管线**上的行为：命中不弹（且真执行）／未命中照弹／
// skipConfirm 不二次扣次／别会话不命中。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.RiskTable
import com.example.zhengdao.baopu.ToolRule
import com.example.zhengdao.baopu.authz.LeaseScope
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.authz.LeaseLimits
import com.example.zhengdao.baopu.authz.LeaseOffer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LeasePipelineTest {

    private lateinit var root: File
    private val runs = AtomicInteger(0)
    private lateinit var reg: ToolRegistry
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("baopu467-pipe").toFile()
        File(root, "产物").mkdirs()
        LeaseStore.resetForTest()
        logs.clear()
        runs.set(0)
        reg = ToolRegistry().apply {
            // FILES 组默认不可见：不解锁的话 `find` 给的是"组锁存根"，执行器必然拒
            setGroupApproved(ToolGroup.FILES, true)
            register(
                ToolSpec(
                    name = "delete_path",
                    description = "假删除（只数次数，不真删）",
                    inputSchema = jsonObjectOf(
                        "type" to "object",
                        "properties" to jsonObjectOf("path" to jsonObjectOf("type" to "string")),
                        "required" to jsonArrayOf("path"),
                    ),
                    permission = ToolPermission.CONFIRM,
                    group = ToolGroup.FILES,
                ) { _ ->
                    runs.incrementAndGet()
                    ToolOutcome.Ok("删了")
                },
            )
        }
    }

    @After
    fun tearDown() {
        LeaseStore.resetForTest()
        root.deleteRecursively()
    }

    private fun pipeline(tier: AskTier = AskTier.STANDARD): GuardPipeline = GuardPipeline(
        registry = reg,
        workspaceRoot = { root },
        rawDir = { null },
        runner = ToolRunner(Executors.newSingleThreadExecutor()),
        log = { logs.add(it) },
        riskTable = RiskTable.DEFAULT,
        askTier = { tier },
    )

    private fun call(path: String, id: String = "c1"): ToolCall =
        ToolCall(id, "delete_path", JSONObject().put("path", path))

    private fun needsConfirm(p: GuardPipeline.Prepared): GuardPipeline.Result.NeedsConfirm =
        (p as GuardPipeline.Prepared.Settled).result as GuardPipeline.Result.NeedsConfirm

    @Test
    fun `首次_R2删文件_弹卡且带租约报价`() {
        val nc = needsConfirm(pipeline().prepare(call("产物/a.md")))
        val offer = nc.leaseOffer
        assertTrue("R2 删文件应当给报价", offer != null)
        assertEquals("files/delete", offer!!.cls)
        assertEquals(LeaseLimits.DEFAULT_CAP, offer.cap)
    }

    @Test
    fun `命中_同级目录第二个文件_不弹卡且真执行`() {
        reg.currentSessionId = 11L
        val p = pipeline()
        val offer = needsConfirm(p.prepare(call("产物/a.md"))).leaseOffer!!
        LeaseStore.grant(offer, 11L, "card-1")

        val second = p.execute(call("产物/b.md", id = "c2"), { _, _ -> null })
        assertTrue("命中租约应当真执行（Done），实得 $second；log=$logs", second is GuardPipeline.Result.Done)
        assertEquals("工具真的跑了一次", 1, runs.get())
    }

    @Test
    fun `未命中_越界到兄弟目录_照旧弹卡且不执行`() {
        reg.currentSessionId = 12L
        val p = pipeline()
        val offer = needsConfirm(p.prepare(call("产物/a.md"))).leaseOffer!!
        LeaseStore.grant(offer, 12L, "card-1")

        val out = p.execute(call("别处/x.md", id = "c2"), { _, _ -> null })
        assertTrue("越界应回到弹卡", out is GuardPipeline.Result.NeedsConfirm)
        assertEquals("一步都没执行", 0, runs.get())
    }

    @Test
    fun `别会话不继承_同一张租约在另一个会话照样弹卡`() {
        reg.currentSessionId = 21L
        val p = pipeline()
        val offer = needsConfirm(p.prepare(call("产物/a.md"))).leaseOffer!!
        LeaseStore.grant(offer, 21L, "card-1")

        reg.currentSessionId = 22L
        val out = p.execute(call("产物/b.md", id = "c2"), { _, _ -> null })
        assertTrue("B 会话不该继承 A 的租约", out is GuardPipeline.Result.NeedsConfirm)
        assertEquals(0, runs.get())
    }

    @Test
    fun `用尽_恢复弹卡`() {
        reg.currentSessionId = 31L
        val p = pipeline()
        val offer = needsConfirm(p.prepare(call("产物/a.md"))).leaseOffer!!
        // 授一张只够一次的同款租约（模拟"用到头了"）
        LeaseStore.grant(offer.copy(cap = 1), 31L, "card-1")
        assertTrue(p.execute(call("产物/b.md", id = "c2"), { _, _ -> null }) is GuardPipeline.Result.Done)
        val out = p.execute(call("产物/c.md", id = "c3"), { _, _ -> null })
        assertTrue("用尽之后必须回到弹卡，实得 $out；log=$logs", out is GuardPipeline.Result.NeedsConfirm)
    }

    @Test
    fun `批准路径 skipConfirm 不再扣次`() {
        reg.currentSessionId = 41L
        val p = pipeline()
        val offer = needsConfirm(p.prepare(call("产物/a.md"))).leaseOffer!!
        LeaseStore.grant(offer.copy(cap = 2), 41L, "card-1")
        assertTrue(p.execute(call("产物/b.md", id = "c2"), { _, _ -> null }) is GuardPipeline.Result.Done)
        assertEquals("用掉 1 次，log=$logs active=${LeaseStore.active(41L).map { it.used }}", 1, LeaseStore.active(41L).first().used)
        // 批准后的第二遍 prepare：skipConfirm=true ⇒ 不进判定、不扣次
        p.prepare(call("产物/c.md", id = "c3"), skipConfirm = true)
        assertEquals("不许二次扣次", 1, LeaseStore.active(41L).first().used)
    }

    @Test
    fun `不可授的情形_卡上不摆租约那一句`() {
        reg.currentSessionId = 51L
        // permanent=true ⇒ 不进回收站、不可逆 ⇒ 499 硬线之二 ⇒ 一律不给报价
        val call = ToolCall("cc", "delete_path", JSONObject().put("path", "产物/x").put("permanent", true))
        val nc = needsConfirm(pipeline().prepare(call))
        assertEquals("不可逆的动作不许给租约报价", null, nc.leaseOffer)
    }

    @Test
    fun `范围已备好_是本次目标的共同父目录`() {
        reg.currentSessionId = 61L
        val nc = needsConfirm(pipeline().prepare(call("产物/a.md")))
        val scope = nc.leaseOffer!!.scope
        assertEquals(LeaseScope.Kind.DIR, scope.kind)
        assertEquals(File(root, "产物").canonicalFile.path, scope.value)
    }
}
