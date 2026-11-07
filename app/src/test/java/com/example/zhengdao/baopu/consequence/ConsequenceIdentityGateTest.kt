// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 476 持久身份＋可撤销凭据的判据与负例。
// 第五百一十七批（2026-11-07）· Q2 并入后的口径更新（U2/U3/Q1，以 517 派工单原话为准）：
//   · **U2 撤销持久不可逆**：解除撤销整条路拆掉（本人也不行）；恢复只能人工铸新（mint）；
//     撤销后的重启**不许静默长出新身份**（首启生成的前提收成"整表为空"）。
//   · **U3 铸新身份仅人工**：轮换与铸新都只认 `actor=user`；模型与装配层（auto）都拒。
//   · **Q1 legacy grandfather**：没主人格的老凭据给 30 天迁移期（绝对到期日），到期自动失效。
//
// 对着 506 派工单 §五 的判据④⑤ 与 §三 的四条，517 追加判据⑦⑧⑪：
//   ④ 首启生成并持久化／账行带身份／撤销即凭据全失效／撤销后历史账可读
//   ⑤ 模型调「改身份」⇒ 拒（U3 之后轮换/铸新对模型依旧全闭）
// 负例逐条点名（红得有名字，不是一句"assert 失败"）：
//   N1 每次启动都重新生成 id（把稳定身份做成进程代号）
//   N2 轮换时把旧行删掉（＝静默覆盖，账上查不到它存在过）
//   N3 凭据闸只看「id 在不在表里」不看状态（撤销读成有效）
//   N4 撤销之后重启静默长出新身份（U2 被绕过）
//   N5 模型 actor 改身份／装配层铸新（U3）
//   N6 撤销时把凭据表清空（"失效"做成"删除"＝历史没了）
//   N7 铸新时复用历史 id（撤销过的身份借壳复活）
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
        assertTrue(CredentialBinding.permitted(cred!!, rows, now))

        val (revoked, refuse) = IdentityRules.revokeRows(rows, m("user", IdentityRules.OP_REVOKE), now + 1000)
        assertNull(refuse)
        // ② 凭据全失效
        val denied = CredentialBinding.allows(cred, revoked, now + 1000)
        assertTrue("撤销后必须拦住：$denied", denied is FenceVerdict.Denied)
        assertTrue((denied as FenceVerdict.Denied).reason.contains("全失效"))
        // ③ 历史账保留＝身份表还在、id 还查得到、被拦的条数可核对
        assertEquals("撤销不许删行", 1, revoked.size)
        assertTrue(IdentityRules.wasKnown(revoked, idA))
        assertEquals(1, CredentialBinding.deniedCountAfterRevocation(listOf(cred), revoked, now + 1000))
        assertEquals(IdentityState.REVOKED, revoked.first().state)
        assertEquals(now + 1000, revoked.first().revokedAtMs)
    }

    @Test
    fun `负例N3_闸口看状态而不是看id在不在表里`() {
        val revoked = IdentityRules.revokeRows(fresh(), m("user", IdentityRules.OP_REVOKE), now).first
        val cred = Credential(kind = CredentialKind.MCP_TOKEN, handle = "srv-1", owner = idA, issuedAtMs = now)
        // 只判「id 在表里」的实现会在这里放行——表里确实还有 idA。
        assertFalse(CredentialBinding.permitted(cred, revoked, now))
    }

    @Test
    fun `判据Q1_没主人格的老凭据进了迁移期就能用到到期日`() {
        // 未打标（sweep 没跑到）⇒ 还是要点名给人看，不静默放也不静默杀
        val unstamped = Credential(CredentialKind.TASK_GRANT, "grant-1", CredentialBinding.UNBOUND, now)
        assertTrue(
            "没打迁移标的老凭据不许静默放行：${CredentialBinding.allows(unstamped, fresh(), now)}",
            CredentialBinding.allows(unstamped, fresh(), now) is FenceVerdict.NeedsHuman,
        )
        // sweep 打标（Q1：默认 30 天，到期日=绝对时刻）
        val (stamped, newly) = LegacyCredentials.grandfather(listOf(unstamped), now)
        assertEquals(1, newly)
        val legacy = stamped.first()
        assertTrue(legacy.isLegacy)
        assertEquals(now + LegacyCredentials.DEFAULT_TTL_MS, legacy.expiresAtMs)
        // 迁移期内 ⇒ 放行（grandfather）
        assertTrue(CredentialBinding.allows(legacy, fresh(), now + LegacyCredentials.DEFAULT_TTL_MS - 1) is FenceVerdict.Allowed)
        // 到期（假时钟：+31 天）⇒ 自动失效，拒因带**绝对时刻**到期日（U5）
        val expired = CredentialBinding.allows(legacy, fresh(), now + LegacyCredentials.DEFAULT_TTL_MS + 1)
        assertTrue("到期必须失效：$expired", expired is FenceVerdict.Denied)
        val why = (expired as FenceVerdict.Denied).reason
        assertTrue("拒因要带绝对时刻到期日：$why", why.contains("到期="))
        assertTrue(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?[+-]\d{2}:\d{2}""").containsMatchIn(why))
        // 重复 sweep 不把到期日往后漂
        val (again, againNew) = LegacyCredentials.grandfather(stamped, now + LegacyCredentials.DEFAULT_TTL_MS)
        assertEquals(0, againNew)
        assertEquals(legacy.expiresAtMs, again.first().expiresAtMs)
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
        assertTrue(CredentialBinding.allows(cred, r.rows, now + 5) is FenceVerdict.Denied)
        // 逐条 rebind 之后才认新主人
        val rebound = CredentialBinding.rebind(listOf(cred), idA, idB, now + 6)
        assertTrue(CredentialBinding.permitted(rebound.first(), r.rows, now + 6))
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
    fun `判据5c_撤销不可逆_本人也不许解除撤销`() {
        val revoked = IdentityRules.revokeRows(fresh(), m("user", IdentityRules.OP_REVOKE), now).first
        // U2：解除撤销对**所有** actor 都是死路（506 的"本人可以"被 517 拍板改掉），拒因明说不可逆
        for (actor in listOf("user", "auto")) {
            val refuse = IdentityRules.refusalOf(m(actor, IdentityRules.OP_UNREVOKE))
            assertNotNull("actor=$actor 也不许解除撤销（U2）", refuse)
            assertTrue(refuse!!.contains("不可逆"))
        }
        // 恢复的唯一路＝人工铸新（U3）：撤销行原样保留、永不复用
        val r = IdentityRules.mintOutcome(revoked, m("user", IdentityRules.OP_MINT, entropyB), entropyB, now + 5)
        assertTrue("人工铸新该成：$r", r is IdentityRules.Rotation.Accepted)
        r as IdentityRules.Rotation.Accepted
        assertEquals(2, r.rows.size)
        assertEquals("", r.previousId)
        // 旧身份**保留不可用**：状态还是已撤销（不是已轮换），id 还查得到，在用的只有新铸的
        val old = r.rows.first { it.id == idA }
        assertEquals(IdentityState.REVOKED, old.state)
        assertTrue(IdentityRules.wasKnown(r.rows, idA))
        assertEquals(idB, IdentityRules.activeId(r.rows))
        // 撤销身份名下的凭据在铸新之后**依旧失效**（旧身份历史保留不可用）
        val cred = Credential(kind = CredentialKind.SITE_TRUST, handle = "example.com", owner = idA, issuedAtMs = now)
        assertTrue(CredentialBinding.allows(cred, r.rows, now + 6) is FenceVerdict.Denied)
    }

    @Test
    fun `判据U3_轮换与铸新只认人工_模型与装配层都拒`() {
        for (op in listOf(IdentityRules.OP_ROTATE, IdentityRules.OP_MINT)) {
            val modelRefuse = IdentityRules.refusalOf(m("model", op, entropyB))
            assertNotNull("模型调 $op 必须被拒（U3）", modelRefuse)
            assertTrue(modelRefuse!!.contains("模型不许改"))
            val autoRefuse = IdentityRules.refusalOf(m("auto", op, entropyB))
            assertNotNull("装配层调 $op 也必须被拒（U3 铸新身份仅人工）", autoRefuse)
            assertTrue(autoRefuse!!.contains("只许人工"))
            assertNull("本人调 $op 放行", IdentityRules.refusalOf(m("user", op, entropyB)))
        }
        // 撤销仍然是"人或系统"都能做的动作（不在 U3 之列）
        assertNull(IdentityRules.refusalOf(m("auto", IdentityRules.OP_REVOKE)))
    }

    @Test
    fun `负例N4_撤销之后重启不许静默长出新身份`() {
        // 撤销后表里还有一行（已撤销）——"首启生成"的新前提是**整表为空**，
        // 有历史但没在用行 ⇒ 不生成，恢复只有人工铸新那一条路（U2）。
        val revoked = IdentityRules.revokeRows(fresh(), m("user", IdentityRules.OP_REVOKE), now).first
        assertNull(
            "有历史没在用行不许再生成（N4）",
            IdentityRules.firstRunRow(revoked, entropyB, now + 10_000),
        )
    }

    @Test
    fun `负例N7_铸新不许复用历史id_有在用身份也不许铸新`() {
        // 复用已撤销的 id ＝借壳复活（U2 第二道闩）
        val revoked = IdentityRules.revokeRows(fresh(), m("user", IdentityRules.OP_REVOKE), now).first
        val reuse = IdentityRules.mintOutcome(revoked, m("user", IdentityRules.OP_MINT, entropyA), entropyA, now + 5)
        assertTrue(reuse is IdentityRules.Rotation.Rejected)
        assertTrue((reuse as IdentityRules.Rotation.Rejected).reason.contains("复用"))
        // 已有在用身份 ⇒ 铸新不越权（那是轮换的事）
        val both = revoked + IdentityRow(idB, IdentityState.ACTIVE, now)
        val mintWhileActive = IdentityRules.mintOutcome(both, m("user", IdentityRules.OP_MINT, entropyB), entropyB, now)
        assertTrue(mintWhileActive is IdentityRules.Rotation.Rejected)
        assertTrue((mintWhileActive as IdentityRules.Rotation.Rejected).reason.contains("轮换"))
    }

    @Test
    fun `判据5d_不认识的动词一律拒`() {
        assertNotNull(IdentityRules.refusalOf(m("user", "erase_history")))
        assertNull(IdentityRules.refusalOf(m("user", IdentityRules.OP_REVOKE)))
        assertNull(IdentityRules.refusalOf(m("user", IdentityRules.OP_ROTATE)))
    }

    @Test
    fun `负例N6_有历史没在用行时撤销的那条永远不可复活`() {
        // 517（U2）改写：506 的这条负例原钉"解除撤销要有在用行"——那条路整条拆了。
        // 现在钉的是：撤销行永远停在已撤销（unrevoke 对一切 actor 拒），铸新只加新行不改旧行。
        val revoked = IdentityRules.revokeRows(fresh(), m("user", IdentityRules.OP_REVOKE), now).first
        assertTrue(revoked.all { it.state != IdentityState.ACTIVE })
        val minted = IdentityRules.mintOutcome(revoked, m("user", IdentityRules.OP_MINT, entropyB), entropyB, now)
            as IdentityRules.Rotation.Accepted
        assertTrue("撤销行原样保留", minted.rows.any { it.id == idA && it.state == IdentityState.REVOKED })
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
