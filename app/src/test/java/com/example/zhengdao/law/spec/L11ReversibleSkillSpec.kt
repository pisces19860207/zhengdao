// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// ⚠️ 本文件是**规范（spec）**，不是门禁：生产未接线（#271-L11），它**不会红**。
//    它钉的是「判据长什么样」；接线那天把判据搬进生产、用例名不变，它才升格为门禁。
//
// 第二百七十一批 · 法典规范（spec，非门禁） **L11 不可逆必有回滚点**（派工单 271）
// ＋ 同一号在法典卷三的另一半：**技能必须可证伪**（`appliesWhen`/`success`/`protected` 缺一不可）。
//
// ## 判据（可执行）
// ① **技能侧（生产已落地）**：`SkillContract.validate` 对缺段/读不成的正文必须给出问题清单
//    （缺契约的技能不许被自动选中）；三段齐才返回空表；
// ② **动作侧（判据，未接线）**：不可逆动作必须带快照/回滚点，没有就**拒**，不是"警告"。
//
// ## 生产状态（诚实标注）——**本文件是规范，不是门禁；生产未接线（#271-L11），它不会红**
// ① 在 `SkillContract`（生产）；② 只有判据——`DecisionLedger.build` 至今允许
//    「reversible=true 但 rollbackRef 空」的账落下去（缺陷 #253-16a 仍在账，见 253 的 L16 门禁）
//    ⇒ 缺陷 #271-L11 在账：接线时把 ② 挪进 `DecisionLedger.build` 或 `TaskGrantGate` 第④步。
//
// ## 负例（造违规即红——红的是**判据**，不是生产：生产里还没有它）
// ① 缺 `success` 段 ⇒ `validate` 必须报「缺 success 段」；
// ② 不可逆且无快照 ⇒ 必须判拒。
package com.example.zhengdao.law.spec


import com.example.zhengdao.baopu.SkillContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L11ReversibleSkillSpec {

    private val fullContract = """
        # 整理台账
        <!-- contract appliesWhen url_domain=a.com -->
        <!-- contract success element_appears=结果 -->
        <!-- contract protected path:/admin -->
        - 按日期排
    """.trimIndent()

    // ── 判据②：不可逆必带快照（判据探针） ──────────────────────────────────

    internal object SnapshotJudge {
        /**
         * @param reversible 这一步能不能退回去
         * @param snapshotRef 快照/备份/回滚点的引用（空 = 没有回滚点）
         */
        fun refuse(reversible: Boolean, snapshotRef: String): String? = when {
            reversible -> null
            snapshotRef.isBlank() ->
                "不可逆动作没有快照/回滚点：退不回去的事不许直接做（先出卡或先做备份）"
            else -> null
        }
    }

    // ── 正例：三段齐 + 不可逆但给了快照 ─────────────────────────────────────

    @Test
    fun `L11_正例_契约三段齐可证伪_不可逆但有快照可做`() {
        assertEquals(
            "三段齐的契约不该报问题：${SkillContract.validate(fullContract)}",
            emptyList<String>(),
            SkillContract.validate(fullContract),
        )
        assertEquals("不可逆但有快照 ⇒ 放行", null, SnapshotJudge.refuse(reversible = false, snapshotRef = ".bak-1"))
        assertEquals("可逆动作不要求快照", null, SnapshotJudge.refuse(reversible = true, snapshotRef = ""))
    }

    // ── 负例①：缺契约段 ⇒ 不许入库 ─────────────────────────────────────────

    @Test
    fun `L11_负例1_缺success段必须被契约校验拒`() {
        val noSuccess = """
            # 整理台账
            <!-- contract appliesWhen url_domain=a.com -->
            - 按日期排
        """.trimIndent()
        val problems = SkillContract.validate(noSuccess)
        assertTrue("缺 success 必须报出来：$problems", problems.any { it.contains("success") })

        val absent = SkillContract.validate("- 只有一句人话，没有任何契约行")
        assertTrue("一条契约都没有必须报：$absent", absent.any { it.contains("contract_missing") })

        // 反面对照：补上 success 就不该再报这一条
        val fixed = noSuccess + "\n<!-- contract success element_appears=结果 -->"
        assertFalse(
            "补上 success 后不该再报缺段：${SkillContract.validate(fixed)}",
            SkillContract.validate(fixed).any { it.contains("success") },
        )
    }

    // ── 负例②：不可逆无快照 ⇒ 拒 ───────────────────────────────────────────

    @Test
    fun `L11_负例2_不可逆且无快照必须拒`() {
        val refusal = SnapshotJudge.refuse(reversible = false, snapshotRef = "")
        assertTrue("不可逆且没有回滚点必须拒（不是警告）", refusal != null && refusal.isNotBlank())
        assertTrue("拒的理由要说清怎么办", refusal!!.contains("快照") || refusal.contains("回滚"))
        // 空白串等于没给
        assertTrue("空白引用等于没给", SnapshotJudge.refuse(reversible = false, snapshotRef = "   ") != null)
    }
}
