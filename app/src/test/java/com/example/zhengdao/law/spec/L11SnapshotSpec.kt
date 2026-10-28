// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// ⚠️ 本文件是**规范（spec）**，不是门禁：生产未接线（#271-L11），它**不会红**。
//    它钉的是「判据长什么样」；接线那天把判据搬进生产、用例名不变，它才升格为门禁。
//
// 第二百七十一批 · 法典规范（spec，非门禁） **L11 不可逆必有回滚点（动作面）**
// ——派工单 271 原文「不可逆操作前必须有备份/快照（负例：无回滚点 ⇒ 拒）」。
//
// ## 判据（可执行）
// 不可逆动作必须带快照/回滚点，没有就**拒**（不是"警告"）；可逆动作不要求快照。
//
// ## 生产状态（诚实标注）——**本文件是规范，不是门禁；生产未接线（#271-L11），它不会红**
// `DecisionLedger.build` 至今允许「reversible=true 但 rollbackRef 空」的账落下去
// （缺陷 #253-16a 仍在账，见 253 的 L16 门禁）⇒ **#271-L11 并入 #253-16a 记账，不重复立**。
// 接线点：把这条判据挪进 `DecisionLedger.build` 或 `TaskGrantGate` 第④步。
// 本条的契约面**已经接线**，在门禁账上：`law/L11ContractGateTest.kt`。
//
// ## 负例（造违规即红——红的是**判据**，不是生产：生产里还没有它）
// 关掉"无回滚点即拒" ⇒ `L11_负例2_不可逆且无快照必须拒` 红。
package com.example.zhengdao.law.spec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class L11SnapshotSpec {

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

    // ── 正例：不可逆但给了快照 ⇒ 可做 ───────────────────────────────────────

    @Test
    fun `L11_正例2_不可逆但有快照可做`() {
        assertEquals("不可逆但有快照 ⇒ 放行", null, SnapshotJudge.refuse(reversible = false, snapshotRef = ".bak-1"))
        assertEquals("可逆动作不要求快照", null, SnapshotJudge.refuse(reversible = true, snapshotRef = ""))
    }

    // ── 负例：不可逆无快照 ⇒ 拒 ─────────────────────────────────────────────

    @Test
    fun `L11_负例2_不可逆且无快照必须拒`() {
        val refusal = SnapshotJudge.refuse(reversible = false, snapshotRef = "")
        assertTrue("不可逆且没有回滚点必须拒（不是警告）", refusal != null && refusal.isNotBlank())
        assertTrue("拒的理由要说清怎么办", refusal!!.contains("快照") || refusal.contains("回滚"))
        // 空白串等于没给
        assertTrue("空白引用等于没给", SnapshotJudge.refuse(reversible = false, snapshotRef = "   ") != null)
    }
}
