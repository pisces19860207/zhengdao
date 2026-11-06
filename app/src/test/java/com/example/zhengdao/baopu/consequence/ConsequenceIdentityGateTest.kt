// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 476 持久身份＋可撤销凭据的判据与负例。
//
// 对着派工单 §五 的判据④⑤ 与 §三 的四条：
//   ④ 首启生成并持久化／账行带身份／撤销即凭据全失效／撤销后历史账可读
//   ⑤ 模型调「改身份／解除撤销」⇒ 拒
// 负例逐条点名（红得有名字，不是一句"assert 失败"）：
//   N1 每次启动都重新生成 id（把稳定身份做成进程代号）
//   N2 轮换时把旧行删掉（＝静默覆盖，账上查不到它存在过）
//   N3 凭据闸只看「id 在不在表里」不看状态（撤销读成有效）
//   N4 解除撤销时顺手换一个 id（撤销变成"换个身份"）
//   N5 模型 actor 改身份／模型解除撤销
//   N6 撤销时把凭据表清空（"失效"做成"删除"＝历史没了）
package com.example.zhengdao.baopu.consequence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsequenceIdentityGateTest {

    private val now = 1_760_000_000_000L
    private val entropyA = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val entropyB = "11111111-2222-3333-4444-555555555555"
    private val idA = "zdx-$entropyA"
    private val idB = "zdx-$entropyB"

    private fun fresh(): List<IdentityRow> {
        val row = IdentityRules.firstRunRow(emptyList(), entropyA, now)
        assertNotNull("首启必须生成一行身份", row)
        return listOf(row!!)
    }

    private fun m(actor: String, op: String, target: String = "") = IdentityMutation(actor, op, target)

    // ── 判据④·首启生成 ────────────────────────────────────────────────────

    @Test
    fun `判据4a_首启生成的id形状合格且带机器前缀`() {
        val row = IdentityRules.firstRunRow(emptyList(), entropyA, now)!!
        assertEquals(idA, row.id)
        assertTrue(IdentityFormat.isWellFormed(row.id))
        assertEquals(IdentityState.ACTIVE, row.state)
        assertEquals(now, row.createdAtMs)
    }

    @Test
    fun `负例N1_已有在用身份时首启不再生成`() {
        // 第二次"启动"传进来的熵完全不同——若实现是"每次都生成"，这里会出一行新 id。
        assertNull(IdentityRules.firstRunRow(fresh(), entropyB, now + 1))
    }

    @Test
    fun `判据4b_认不出的熵串不生成半条身份`() {
        assertNull(IdentityRules.firstRunRow(emptyList(), "不是 uuid", now))
        assertNull(IdentityRules.firstRunRow(emptyList(), "", now))
        assertNull(IdentityFormat.normalize("zdx-deadbeef"))
    }

    @Test
    fun `判据4c_线值认不出返回null不自造档位`() {
        assertNull(IdentityState.ofWire("revokedd"))
        assertNull(IdentityOp.ofWire("delete_me"))
    }

    // ── 判据④·撤销即全失效／历史保留 ───────────────────────────────────────

    @Test
    fun `判据4d_撤销后名下凭据全失效但一行未删`() {
        val rows = fresh()
        val cred = CredentialBinding.issue(CredentialKind.SITE_TRUST, "example.com", rows, now)
        assertNotNull(cred)
        assertTrue(CredentialBinding.permitted(cred!!, rows))

        val (revoked, refuse) = IdentityRules.revokeRows(rows, m("user", IdentityRules.OP_REVOKE), now + 1000)
        assertNull(refuse)
        // ② 凭据全失效
        val denied = CredentialBinding.allows(cred, revoked)
        assertTrue("撤销后必须拦住：$denied", denied is FenceVerdict.Denied)
        assertTrue((denied as FenceVerdict.Denied).reason.contains("全失效"))
        // ③ 历史账保留＝身份表还在、id 还查得到、被拦的条数可核对
        assertEquals("撤销不许删行", 1, revoked.size)
        assertTrue(IdentityRules.wasKnown(revoked, idA))
        assertEquals(1, CredentialBinding.deniedCountAfterRevocation(listOf(cred), revoked))
        assertEquals(IdentityState.REVOKED, revoked.first().state)
        assertEquals(now + 1000, revoked.first().revokedAtMs)
    }

    @Test
    fun `负例N3_闸口看状态而不是看id在不在表里`() {
        val revoked = IdentityRules.revokeRows(fresh(), m("user", IdentityRules.OP_REVOKE), now).first
        val cred = Credential(kind = CredentialKind.MCP_TOKEN, handle = "srv-1", owner = idA, issuedAtMs = now)
        // 只判「id 在表里」的实现会在这里放行——表里确实还有 idA。
        assertFalse(CredentialBinding.permitted(cred, revoked))
    }

    @Test
    fun `判据4e_没有主人格的老凭据不 grandfather`() {
        val legacy = Credential(CredentialKind.TASK_GRANT, "grant-1", CredentialBinding.UNBOUND, now)
        val v = CredentialBinding.allows(legacy, fresh())
        assertTrue("没主人的凭据要点名给人看：$v", v is FenceVerdict.NeedsHuman)
    }

    @Test
    fun `判据4f_签发时没有在用身份就拒签`() {
        assertNull(CredentialBinding.issue(CredentialKind.KEY_POOL, "deepseek", emptyList(), now))
        assertNull(CredentialBinding.issue(CredentialKind.KEY_POOL, "", fresh(), now))
    }

    // ── 判据④·轮换留痕 ────────────────────────────────────────────────────

    @Test
    fun `判据4g_轮换留旧行标已轮换并给新行带出处`() {
        val rows = fresh()
        val r = IdentityRules.rotateOutcome(rows, m("user", IdentityRules.OP_ROTATE, entropyB), entropyB, now + 5)
        assertTrue("轮换该成：$r", r is IdentityRules.Rotation.Accepted)
        r as IdentityRules.Rotation.Accepted
        assertEquals(idA, r.previousId)
        assertEquals(idB, r.newId)
        assertEquals(2, r.rows.size)
        val old = r.rows.first { it.id == idA }
        val news = r.rows.first { it.id == idB }
        assertEquals(IdentityState.ROTATED_OUT, old.state)
        assertEquals(IdentityState.ACTIVE, news.state)
        assertEquals(idA, news.rotatedFrom)
        // 老凭据不跟着新身份走（轮换≠续期）
        val cred = CredentialBinding.issue(CredentialKind.SITE_TRUST, "example.com", rows, now)!!
        assertTrue(CredentialBinding.allows(cred, r.rows) is FenceVerdict.Denied)
        // 逐条 rebind 之后才认新主人
        val rebound = CredentialBinding.rebind(listOf(cred), idA, idB, now + 6)
        assertTrue(CredentialBinding.permitted(rebound.first(), r.rows))
    }

    @Test
    fun `负例N2_轮换不许静默覆盖旧行`() {
        val r = IdentityRules.rotateOutcome(fresh(), m("user", IdentityRules.OP_ROTATE, entropyB), entropyB, now)
            as IdentityRules.Rotation.Accepted
        assertTrue("旧 id 必须还在表里", r.rows.any { it.id == idA })
    }

    @Test
    fun `判据4h_新id与旧id相同算伪轮换`() {
        val rows = fresh()
        val r = IdentityRules.rotateOutcome(rows, m("user", IdentityRules.OP_ROTATE, entropyA), entropyA, now)
        assertTrue(r is IdentityRules.Rotation.Rejected)
        assertTrue((r as IdentityRules.Rotation.Rejected).reason.contains("相同"))
    }

    // ── 判据⑤·模型改不动身份 ──────────────────────────────────────────────

    @Test
    fun `判据5_模型三种改身份的动作全部被拒`() {
        for (op in listOf(IdentityRules.OP_ROTATE, IdentityRules.OP_REVOKE, IdentityRules.OP_UNREVOKE)) {
            val refuse = IdentityRules.refusalOf(m("model", op, entropyB))
            assertNotNull("模型调 $op 必须被拒", refuse)
            assertTrue(refuse!!.contains("actor=model"))
        }
    }

    @Test
    fun `判据5b_被拒的模型动作盘上表一字不变`() {
        val rows = fresh()
        val r = IdentityRules.rotateOutcome(rows, m("model", IdentityRules.OP_ROTATE, entropyB), entropyB, now)
        assertTrue(r is IdentityRules.Rotation.Rejected)
        assertEquals("被拒之后在用的还是原来那个 id", idA, IdentityRules.activeId(rows))
        val (same, refuse) = IdentityRules.revokeRows(rows, m("model", IdentityRules.OP_REVOKE), now)
        assertNotNull(refuse)
        assertEquals(rows, same)
    }

    @Test
    fun `判据5c_解除撤销只许本人做`() {
        val revoked = IdentityRules.revokeRows(fresh(), m("user", IdentityRules.OP_REVOKE), now).first
        val (same, refuse) = IdentityRules.unrevokeRows(revoked, m("auto", IdentityRules.OP_UNREVOKE))
        assertNotNull("auto 不许解除撤销", refuse)
        assertEquals(revoked, same)
        val (back, ok) = IdentityRules.unrevokeRows(revoked, m("user", IdentityRules.OP_UNREVOKE))
        assertNull(ok)
        assertEquals(IdentityState.ACTIVE, back.first().state)
        assertEquals(0L, back.first().revokedAtMs)
    }

    @Test
    fun `负例N4_解除撤销不许顺手换个id`() {
        val revoked = IdentityRules.revokeRows(fresh(), m("user", IdentityRules.OP_REVOKE), now).first
        val (same, refuse) = IdentityRules.unrevokeRows(revoked, m("user", IdentityRules.OP_UNREVOKE, idB))
        assertNotNull(refuse)
        assertTrue(refuse!!.contains("轮换"))
        assertEquals(revoked, same)
    }

    @Test
    fun `判据5d_不认识的动词一律拒`() {
        assertNotNull(IdentityRules.refusalOf(m("user", "erase_history")))
        assertNull(IdentityRules.refusalOf(m("user", IdentityRules.OP_REVOKE)))
        assertNull(IdentityRules.refusalOf(m("auto", IdentityRules.OP_ROTATE)))
    }

    @Test
    fun `负例N6_已经有一个在用身份时不许再解除撤销`() {
        // 形状＝先撤销 A，再用别的通道把 B 变成在用（轮换后的既成事实）。
        val revokedA = IdentityRules.revokeRows(fresh(), m("user", IdentityRules.OP_REVOKE), now).first
        val both = revokedA + IdentityRow(idB, IdentityState.ACTIVE, now)
        val (same, refuse) = IdentityRules.unrevokeRows(both, m("user", IdentityRules.OP_UNREVOKE))
        assertNotNull(refuse)
        assertEquals(both, same)
        // 撤掉 B 之后就剩 A 一条可解除 ⇒ 这条路是通的（不是永久卡死）
        val onlyA = both.filterNot { it.id == idB }
        assertNull(IdentityRules.unrevokeRows(onlyA, m("user", IdentityRules.OP_UNREVOKE)).second)
    }

    // ── 落账形状（判据④「每条账行带身份」的账这一侧）────────────────────────

    @Test
    fun `账行_身份状态行复用稳定键且永不可回滚`() {
        val rows = fresh()
        val revoked = IdentityRules.revokeRows(rows, m("user", IdentityRules.OP_REVOKE), now).first
        val e = IdentityEvent(IdentityOp.REVOKE, "user", idA, idA)
        val d = ConsequenceLedger.draftOf(e, revoked, now)
        assertEquals("identity_state", d.kind.wire)
        assertTrue(d.evidence.startsWith(ConsequenceEvidence.IDENTITY))
        assertTrue(d.evidence.endsWith(":$idA"))
        assertEquals(false, d.reversible)
        assertEquals("", d.rollbackRef)
        // 撤销这一条：before 是被撤的 id，after 必须**空**（拿 targetId 回落＝把作废身份显示成在用）
        assertEquals(idA, d.before)
        assertEquals("", d.after)
        assertTrue(ConsequenceLedger.denialNote(3).contains("3 条凭据全失效"))
    }
}
