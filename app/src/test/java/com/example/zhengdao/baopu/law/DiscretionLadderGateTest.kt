// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十五批 · 法典卷二 **L9 裁量三级**的门禁测试。
//
// 这一条是**门禁**，不是规范：它调的是生产码 [DiscretionLadder]（`app/src/main/.../baopu/law/`），
// 生产判错它就红。271 批那份测试侧 spec（`law/spec/L9ClaimEvidenceSpec.kt`）自带 `DiscretionJudge`，
// 生产怎么改都不会红；本文件不定义判据，只消费 `baopu/law/` 里的生产判据。
//
// 覆盖判据（法典 L9 原文＋§275 排单口径）：
// ①便宜行事／②先斩后奏／③题请三档各归其位；②的四要素（可回滚／≤20 文件／不删／不发）缺一条就落③；
// ③的「永不下放」清单（R3／不可逆／权限／契约／白名单）与禁区命中即③；一组动作取最严；
// 声明档位比允许档位宽 ⇒ 违规＋降回题请。
//
// 本批**不测** 24 小时追认窗口：第二百四十九批（2026-10-28 改判）已作废「先斩后奏＋追认」，
// 红旗写着「不许把追认那套偷偷加回来」；「超时不算同意」那半句由 L27 的 [TimeoutFallbackGateTest] 钉。
package com.example.zhengdao.baopu.law

import com.example.zhengdao.baopu.RiskClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscretionLadderGateTest {

    private fun a(
        id: String,
        risk: RiskClass,
        reversible: Boolean = true,
        files: Int = 0,
        delete: Boolean = false,
        out: Boolean = false,
        zone: Boolean = false,
        permission: Boolean = false,
        contract: Boolean = false,
        whitelist: Boolean = false,
        inside: Boolean = true,
    ) = PlannedAction(
        actionId = id,
        risk = risk,
        reversible = reversible,
        affectedFiles = files,
        hasDelete = delete,
        hasExfiltration = out,
        inProtectedZone = zone,
        touchesPermission = permission,
        touchesContract = contract,
        requiresWhitelist = whitelist,
        insideWorkspace = inside,
    )

    /** 一条完全够格的②先斩后奏动作（四要素齐），当对照组用。 */
    private fun cleanR2(id: String = "r2-ok", files: Int = DiscretionLadder.MAX_FILES) =
        a(id, RiskClass.R2, reversible = true, files = files)

    // ── 正例：三档各归其位；四要素齐才许②；越界与永不下放清单必落③；一组取最严 ──

    @Test
    fun `L9_正例_三档各归其位_四要素缺一条就落题请_一组动作取最严`() {
        // ①便宜行事：R0 只读
        val read = DiscretionLadder.decide(listOf(a("read-db", RiskClass.R0)))
        assertEquals("R0 只读落到①便宜行事", DiscretionTier.ACT_ON_FACE, read.tier)
        assertTrue("①才给自行处置", read.permitsSelfDisposal)
        assertFalse("①不许喊先斩后奏", read.permitsActThenReport)
        assertFalse("①不用先请旨", read.mustAskFirst)
        assertTrue("①的解法要点留痕：${read.howToLift}", read.howToLift.contains("留痕"))
        assertEquals("法条引用钉死", "L9", read.lawRef)

        // ①便宜行事：R1 写、可回滚、在地盘内、不删不发
        val localWrite = DiscretionLadder.decide(listOf(a("write-note", RiskClass.R1, inside = true)))
        assertEquals("R1 可逆且在工作区内落到①", DiscretionTier.ACT_ON_FACE, localWrite.tier)

        // ②先斩后奏：R2＋四要素齐（文件数正好压在上限上仍算齐）
        val preempt = DiscretionLadder.decide(listOf(cleanR2(files = 20)))
        assertEquals("R2 四要素齐落到②先斩后奏", DiscretionTier.ACT_THEN_REPORT, preempt.tier)
        assertTrue("②才给先斩后奏", preempt.permitsActThenReport)
        assertFalse("②不算便宜行事", preempt.permitsSelfDisposal)
        assertFalse("②不必先请旨", preempt.mustAskFirst)
        assertTrue("②没有卡点：${preempt.blockReasons}", preempt.blockReasons.isEmpty())

        // 四要素逐个拆：缺哪条都必须落到③，且理由点名缺的那条
        val tooMany = DiscretionLadder.decide(listOf(cleanR2("bulk-edit", files = 21)))
        assertEquals("21 个文件 ⇒ 越出②的文件数上限，落③题请", DiscretionTier.PETITION_FIRST, tooMany.tier)
        assertTrue("卡点要点明文件数：${tooMany.blockReasons}", tooMany.blockReasons.any { it.contains("超过") })
        assertEquals("卡住它的动作要点名", listOf("bulk-edit"), tooMany.bindingActionIds)

        val irreversible = DiscretionLadder.decide(listOf(a("wipe-cache", RiskClass.R2, reversible = false)))
        assertEquals("不可回滚落③（同 L5 不可逆必批）", DiscretionTier.PETITION_FIRST, irreversible.tier)
        assertTrue("不可回滚的理由要点名不可逆：${irreversible.blockReasons}", irreversible.blockReasons.any { it.contains("不可逆") })

        val withDelete = DiscretionLadder.decide(listOf(a("del-files", RiskClass.R2, delete = true)))
        assertEquals("含删除落③", DiscretionTier.PETITION_FIRST, withDelete.tier)
        assertTrue("卡点要点明删除：${withDelete.blockReasons}", withDelete.blockReasons.any { it.contains("删除") })

        val withOut = DiscretionLadder.decide(listOf(a("send-mail", RiskClass.R2, out = true)))
        assertEquals("含外发落③", DiscretionTier.PETITION_FIRST, withOut.tier)
        assertTrue("卡点要点明外发：${withOut.blockReasons}", withOut.blockReasons.any { it.contains("外发") })

        // ③的永不下放清单：命中任一条就是题请，跟文件数无关
        for ((tag, action) in listOf(
            "R3" to a("pay-api", RiskClass.R3),
            "禁区" to a("orig-file", RiskClass.R2, zone = true),
            "权限" to a("set-perm", RiskClass.R1, permission = true),
            "契约" to a("change-law", RiskClass.R1, contract = true),
            "白名单" to a("install-app", RiskClass.R1, whitelist = true),
        )) {
            val v = DiscretionLadder.decide(listOf(action))
            assertEquals("$tag 必须落③题请", DiscretionTier.PETITION_FIRST, v.tier)
            assertTrue("$tag 的理由要点名是哪一条：${v.blockReasons}", v.blockReasons.any { it.contains(tag) })
            assertTrue("$tag 必须先请旨", v.mustAskFirst)
            assertFalse("$tag 不许自行处置", v.permitsSelfDisposal)
            assertFalse("$tag 不许先斩后奏", v.permitsActThenReport)
        }

        // R1 写在地盘外：既够不上①（不在工作区）也够不上②（不是 R2）⇒ 兜底落③
        val outside = DiscretionLadder.decide(listOf(a("write-elsewhere", RiskClass.R1, inside = false)))
        assertEquals("R1 写在工作区外落③", DiscretionTier.PETITION_FIRST, outside.tier)

        // 一组动作取最严：整组只许做到其中最窄的那一档
        val mixed = DiscretionLadder.decide(listOf(a("read-1", RiskClass.R0), cleanR2("edit-2", files = 5)))
        assertEquals("读＋②动作混在一组 ⇒ 整组按②", DiscretionTier.ACT_THEN_REPORT, mixed.tier)
        assertEquals("只有②那条是卡点", listOf("edit-2"), mixed.bindingActionIds)

        val dragged = DiscretionLadder.decide(
            listOf(a("read-1", RiskClass.R0), cleanR2("edit-2", files = 5), cleanR2("bulk-3", files = 30)),
        )
        assertEquals("组里有一条越界 ⇒ 整组落③", DiscretionTier.PETITION_FIRST, dragged.tier)
        assertEquals("点名只点越界那条", listOf("bulk-3"), dragged.bindingActionIds)

        // 口径钉死：文件数上限是法典原文的 20，不是随手数
        assertEquals("②的文件数上限 20", 20, DiscretionLadder.MAX_FILES)

        // 输入自相矛盾 ⇒ 判据拒收，不许把填错的事实读成"没文件"而放行
        assertThrows(IllegalArgumentException::class.java) {
            DiscretionLadder.decide(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiscretionLadder.decide(listOf(a("  ", RiskClass.R0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiscretionLadder.decide(listOf(cleanR2("neg", files = -1)))
        }
    }

    // ── 负例：声明的走法比允许档位宽 ⇒ 必记违规并降回题请（法典「怎么判／后果」）──

    @Test
    fun `L9_负例_越界仍声明先斩后奏必须记违规并降回题请`() {
        // ① 文件数越界却走了② ⇒ 红
        val bulk = DiscretionLadder.decide(listOf(cleanR2("bulk-edit", files = 21)), claimedTier = DiscretionTier.ACT_THEN_REPORT)
        assertTrue("越界仍走先斩后奏必须记违规", bulk.violation != null)
        assertTrue("违规原文要点明走法：${bulk.violation}", bulk.violation!!.contains("先斩后奏"))
        assertTrue("违规原文要点明允许档位：${bulk.violation}", bulk.violation!!.contains("题请"))
        assertTrue("违规原文要点名动作：${bulk.violation}", bulk.violation!!.contains("bulk-edit"))
        assertTrue("违规必须降回题请", bulk.downgradeToPetition)
        assertEquals("降档后整组只到③", DiscretionTier.PETITION_FIRST, bulk.tier)
        assertFalse("越界后不许还留着先斩后奏的放行证", bulk.permitsActThenReport)
        assertTrue("越界后必须先请旨", bulk.mustAskFirst)

        // ② 含删除却走了② ⇒ 红
        val del = DiscretionLadder.decide(listOf(a("del-files", RiskClass.R2, delete = true)), claimedTier = DiscretionTier.ACT_THEN_REPORT)
        assertTrue("含删除走②必须记违规：${del.violation}", del.violation != null)
        assertTrue("违规原文要点明删除：${del.violation}", del.violation!!.contains("删除"))

        // ③ 含外发却走了② ⇒ 红
        val exfil = DiscretionLadder.decide(listOf(a("send-mail", RiskClass.R2, out = true)), claimedTier = DiscretionTier.ACT_THEN_REPORT)
        assertTrue("含外发走②必须记违规：${exfil.violation}", exfil.violation!!.contains("外发"))

        // ④ 不可逆却走了② ⇒ 红（不许把 L5 的东西藏在②里）
        val ir = DiscretionLadder.decide(listOf(a("wipe", RiskClass.R2, reversible = false)), claimedTier = DiscretionTier.ACT_THEN_REPORT)
        assertTrue("不可回滚走②必须记违规：${ir.violation}", ir.violation!!.contains("不可"))

        // ⑤ 永不下放的东西被声明成①便宜行事 ⇒ 红
        val cheap = DiscretionLadder.decide(listOf(a("pay-api", RiskClass.R3)), claimedTier = DiscretionTier.ACT_ON_FACE)
        assertTrue("R3 走①必须记违规：${cheap.violation}", cheap.violation!!.contains("便宜行事"))
        assertTrue("R3 走①也要降回题请", cheap.downgradeToPetition)

        // ⑥ 禁区里的动作被声明成② ⇒ 红
        val zone = DiscretionLadder.decide(listOf(a("orig-file", RiskClass.R2, zone = true)), claimedTier = DiscretionTier.ACT_THEN_REPORT)
        assertTrue("禁区走②必须记违规：${zone.violation}", zone.violation!!.contains("禁区"))

        // ⑥b 只许②却声明走了①：连"事后上呈御案"都省了 ⇒ 同样越界（rank 越大＝放得越宽）
        val skipReport = DiscretionLadder.decide(listOf(cleanR2("quiet-edit")), claimedTier = DiscretionTier.ACT_ON_FACE)
        assertTrue("只许先斩后奏却声明便宜行事必须记违规：${skipReport.violation}", skipReport.violation != null)
        assertTrue("违规原文点明走法：${skipReport.violation}", skipReport.violation!!.contains("便宜行事"))
        assertTrue("四要素齐时也要说清为什么宽不得：${skipReport.violation}", skipReport.violation!!.contains("上呈御案"))
        assertTrue("违规原文不许留空格子：${skipReport.violation}", !skipReport.violation!!.contains("（）"))
        assertTrue("也要降回题请", skipReport.downgradeToPetition)

        // ⑦ 防假门禁：声明与允许一致（或比允许更严）都不算违规
        assertNull("四要素齐的②，声明②不是违规", DiscretionLadder.decide(listOf(cleanR2("ok-1")), claimedTier = DiscretionTier.ACT_THEN_REPORT).violation)
        assertNull("该走③却声明只走③，不是违规", DiscretionLadder.decide(listOf(cleanR2("bulk-1", files = 21)), claimedTier = DiscretionTier.PETITION_FIRST).violation)
        assertNull("该走②却声明只走③（更严），不是违规", DiscretionLadder.decide(listOf(cleanR2("ok-2")), claimedTier = DiscretionTier.PETITION_FIRST).violation)
        assertFalse("没违规就不许喊降档", DiscretionLadder.decide(listOf(cleanR2("ok-3")), claimedTier = DiscretionTier.ACT_THEN_REPORT).downgradeToPetition)

        // ⑧ 反证：文件数换成 20（没越界）同样声明② ⇒ 必须不违规，
        // 证明①那几条红是四要素闸造成的，不是"声明②就红"
        val atLimit = DiscretionLadder.decide(listOf(cleanR2("ok-4", files = 20)), claimedTier = DiscretionTier.ACT_THEN_REPORT)
        assertEquals("压在上限上仍算四要素齐", DiscretionTier.ACT_THEN_REPORT, atLimit.tier)
        assertNull("压在上限上声明②不违规", atLimit.violation)

        // ⑨ 只问不判：不给声明档位 ⇒ 不许凭空造出违规
        assertNull("事前只问能走到哪，不该有违规", DiscretionLadder.decide(listOf(cleanR2("ask-only", files = 21))).violation)
        assertFalse("事前询问也不许降档", DiscretionLadder.decide(listOf(cleanR2("ask-only-2", files = 21))).downgradeToPetition)
    }
}
