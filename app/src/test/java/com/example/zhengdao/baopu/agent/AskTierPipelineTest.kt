// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十九批 · A 段判据的**管线级**测试：档位闸长在 GuardPipeline ③ 段，
// 三档行为要用真工具（write_file／delete_path）穿过整条管线才算数。
// 判据：①三档行为（管线级）②硬拒三条任何档都拒 ③不可逆无回收站任何档都问
// ⑤放过仍记账（真账行）＋ D 段③压卡落账的源码门禁。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AskTierPipelineTest {

    private fun tmp(name: String): File =
        Files.createTempDirectory("baopu-tier-$name").toFile()

    private fun registry(root: File): ToolRegistry = ToolRegistry().apply {
        register(WriteFileTool.spec { root })
        register(DeletePathTool.spec { root })
        register(ReadFileTool.spec())
        // 文件组不解锁会被组闸拦下（先调 capabilities）——与本单无关的闸，这里直接开
        currentSessionId = 1L
        unlockStore().unlock(1L, ToolGroup.entries.toList())
        check(root.isDirectory)
    }

    private fun pipeline(root: File, tier: AskTier): GuardPipeline =
        GuardPipeline(registry(root), { root }, { null }, askTier = { tier })

    private fun writeCall(root: File, rel: String): ToolCall =
        ToolCall("c1", "write_file", JSONObject().put("path", File(root, rel).absolutePath).put("content", "x"))

    private fun deleteCall(root: File, rel: String, extra: JSONObject = JSONObject()): ToolCall {
        val args = JSONObject().put("path", File(root, rel).absolutePath)
        extra.keys().forEach { k -> args.put(k, extra.get(k)) }
        return ToolCall("c2", "delete_path", args)
    }

    private fun existingFile(root: File, rel: String): File =
        File(root, rel).apply { parentFile?.mkdirs(); writeText("old") }

    // ── ① 标准档（缺省）：根内新建 R1 不问，直接执行 ─────────────────────

    @Test
    fun `①标准档_根内新建R1不问直接执行`() {
        val root = tmp("standard-new")
        val r = pipeline(root, AskTier.STANDARD).execute(writeCall(root, "产物/new.txt"), { _, _ -> null })
        assertTrue("标准档根内新建 R1 ⇒ 先做后审，不该弹卡：$r", r is GuardPipeline.Result.Done)
        assertTrue(File(root, "产物/new.txt").isFile)
    }

    @Test
    fun `①严格档_同一个根内新建R1照旧弹卡`() {
        val root = tmp("strict-new")
        val r = pipeline(root, AskTier.STRICT).execute(writeCall(root, "产物/new.txt"), { _, _ -> null })
        assertTrue("严格档＝现状：R1 CONFIRM 照问：$r", r is GuardPipeline.Result.NeedsConfirm)
    }

    @Test
    fun `①放权档_根内新建R1不问`() {
        val root = tmp("delegated-new")
        val r = pipeline(root, AskTier.DELEGATED).execute(writeCall(root, "产物/new.txt"), { _, _ -> null })
        assertTrue(r is GuardPipeline.Result.Done)
    }

    // ── ① 放权档 R2：默认进回收站的删除不问；彻底删要问 ────────────────────

    @Test
    fun `①放权档_进回收站的删除不问`() {
        val root = tmp("delegated-trash")
        val f = existingFile(root, "旧文件.txt")
        val r = pipeline(root, AskTier.DELEGATED).execute(deleteCall(root, "旧文件.txt"), { _, _ -> null })
        assertTrue("回收站可捞回 ⇒ 放权档不问：$r", r is GuardPipeline.Result.Done)
        assertFalse("原件从原位消失＝真删了？不——应在回收站里", f.exists())
        assertTrue("回收站里有它（可捞回）", File(root, "回收站").listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun `③放权档_彻底删permanent不问不行`() {
        val root = tmp("delegated-permanent")
        existingFile(root, "绝版.txt")
        val r = pipeline(root, AskTier.DELEGATED)
            .execute(deleteCall(root, "绝版.txt", JSONObject().put("permanent", true)), { _, _ -> null })
        assertTrue("permanent=true 无回收站 ⇒ 硬线①：任何档都问：$r", r is GuardPipeline.Result.NeedsConfirm)
    }

    @Test
    fun `③严格档_进回收站的删除也问_现状`() {
        val root = tmp("strict-trash")
        existingFile(root, "旧文件.txt")
        val r = pipeline(root, AskTier.STRICT).execute(deleteCall(root, "旧文件.txt"), { _, _ -> null })
        assertTrue("严格档＝现状：delete_path 是 CONFIRM 工具，照问：$r", r is GuardPipeline.Result.NeedsConfirm)
    }

    // ── ⑤ 放过仍记账：放行的动作在治理账上有行，依据带档位 ────────────────

    @Test
    fun `⑤标准档放行的写盘留一行账`() {
        val root = tmp("standard-ledger")
        AskGovLedger.resetForTest()
        val r = pipeline(root, AskTier.STANDARD).execute(writeCall(root, "产物/账.txt"), { _, _ -> null })
        assertTrue(r is GuardPipeline.Result.Done)
        val rows = AskGovLedger.replay().filter { it.kind == AskGovLedger.KIND_PASS && it.tool == "write_file" }
        assertTrue("放过必须仍记账（判据⑤）", rows.isNotEmpty())
        assertTrue("依据带当时档位", rows.first().basis.contains("tier=standard"))
        AskGovLedger.resetForTest()
    }

    // ── ② 硬拒：原件区只读，任何档位都拒（拒绝，不是询问）────────────────

    @Test
    fun `②原件区硬拒_任何档位都拒`() {
        for (tier in AskTier.entries) {
            val root = tmp("harddeny-${tier.wire}")
            val raw = File(root, "资料库/原始").apply { mkdirs() }
            File(raw, "原件.txt").writeText("原始数据")
            val r = pipeline(root, tier)
                .execute(ToolCall("c3", "write_file", JSONObject().put("path", File(raw, "原件.txt").absolutePath).put("content", "x")), { _, _ -> null })
            assertTrue("档位=$tier：原件区是硬红线 ⇒ Rejected（不是问）", r is GuardPipeline.Result.Rejected)
        }
    }

    @Test
    fun `⑪硬拒落一行治理账_原因不超过80字`() {
        val root = tmp("harddeny-ledger")
        AskGovLedger.resetForTest()
        val raw = File(root, "资料库/原始").apply { mkdirs() }
        pipeline(root, AskTier.STANDARD)
            .execute(ToolCall("c5", "write_file", JSONObject().put("path", File(raw, "a.txt").absolutePath).put("content", "x")), { _, _ -> null })
        val rows = AskGovLedger.replay().filter { it.kind == AskGovLedger.KIND_REFUSE }
        assertTrue(rows.isNotEmpty())
        assertTrue("硬拒原因 ≤80 字（C 段）", rows.first().basis.length <= AskGovernance.HARD_REJECT_MAX)
        AskGovLedger.resetForTest()
    }

    // ── D 段③：压卡落账（who=LEFT）的生产调用点在引擎里（源码门禁范式）────

    @Test
    fun `D③_runEngine的askUser里可见压卡落账who=LEFT`() {
        val src = java.io.File("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
            .takeIf { it.isFile }
            ?: java.io.File("app/src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        val body = src.readText()
        val start = body.indexOf("fun askUser(")
        val end = body.indexOf("private fun askUserRaw(")
        assertTrue("找不到 askUser 切片", start >= 0 && end > start)
        val askUserBody = body.substring(start, end)
        assertTrue(
            "askUser 压卡分支必须落治理账（D 段③：who=LEFT）",
            askUserBody.contains("noteSuppressLeft"),
        )
    }

    @Test
    fun `D③压卡账行的who是LEFT_依据带预算`() {
        AskGovLedger.resetForTest()
        val row = AskGovernance.noteSuppressLeft(
            reason = "已熔断：不再弹问卡",
            budgetBasis = "耐心阈值 0（画像 askTolerance 2、LOOP 清零）—本回合已问 2",
            atMs = 50_000L,
        )
        assertEquals(AskGovLedger.KIND_SUPPRESS, row.kind)
        assertEquals("who=LEFT 是 D 段判据的字面", "LEFT", row.who)
        assertTrue("依据带档位＋预算的那一句", row.basis.contains("耐心阈值"))
        AskGovLedger.resetForTest()
    }

    // ── 可回滚事实表（判据①的资格闸；不可逆的两格在这里钉） ────────────────

    @Test
    fun `可回滚事实_delete默认可回滚_permanent与清空不可逆`() {
        val dir = tmp("rev")
        val f = existingFile(dir, "a.txt")
        val args = JSONObject()
        assertTrue(AskReversibility.of("delete_path", args, listOf(f)).reversible)
        assertFalse(
            AskReversibility.of("delete_path", JSONObject().put("permanent", true), listOf(f)).reversible,
        )
        assertFalse(
            AskReversibility.of("delete_path", JSONObject().put("empty_trash", true), emptyList()).reversible,
        )
    }

    @Test
    fun `可回滚事实_move类不可逆_纯新建可回滚`() {
        val dir = tmp("rev2")
        val src = existingFile(dir, "b.txt")
        val dst = File(dir, "c.txt")
        assertFalse("move 源会消失，没有回收站语义", AskReversibility.of("move_path", JSONObject(), listOf(src, dst)).reversible)
        assertTrue("copy 到全新目标 ⇒ 只写新文件", AskReversibility.of("copy_path", JSONObject(), listOf(dst)).reversible)
        assertFalse("copy 目标已存在 ⇒ 会覆盖", AskReversibility.of("copy_path", JSONObject(), listOf(src)).reversible)
    }
}
