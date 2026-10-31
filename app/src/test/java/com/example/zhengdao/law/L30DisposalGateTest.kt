// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百二十三批 · 法典门禁 **L30 灾异处置权**（法典卷十 L30；316 对账时是五条真缺口之一，本批落地）。
//
// 判据落进生产 `baopu/law/EmergencyGate.kt`（315 的对象添 L30 判定口，不另起炉灶）：
// 白名单内可先斩（0 秒止损）；白名单外处置权只能是人（回 L21）；「永久排除」四类谁都不许先斩。
//
// ## 判据（可执行，判的是生产 EmergencyGate＋EmergencyWiring 的签名与路径）
//   ① 白名单五件（停机／隔离／回滚／冻结写入／撤销本次授权）逐字在 law 层；
//   ② **程序可执行的每一个止损动作都必须在白名单内**（StopLossAction 枚举 ⊆ law 名单）——
//      程序路径拿不到白名单外的处置权，这是结构性的：签名只收闭集枚举，任意外来 wire 进不来；
//   ③ 永久排除四类的 wire 键**只许住在 law 层**——生产其余任何路径都不许出现
//      （出现＝有第二条可执行的处置路，当场红）。
//
// ## 负例（造违规即红）
// 永久排除四类申请程序处置 ⇒ 拒；白名单外来路不明的动作 ⇒ 拒（处置权只能是人）；
// 白名单枚举里混进名单外动作 ⇒ 红。
package com.example.zhengdao.law

import com.example.zhengdao.baopu.inspect.StopLossAction
import com.example.zhengdao.baopu.law.EmergencyGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class L30DisposalGateTest {

    // ── 正例：白名单内可先做；程序可执行的止损全部在 law 名单内 ──────────────────

    @Test
    fun `L30_正例_白名单五件可先做_程序可执行的止损动作全部在law名单内`() {
        // 名单逐字（法典原文五件；改名单＝改 law 层＋御批）
        assertEquals(
            "L30 止损白名单必须逐字是这五件",
            setOf("stop_machine", "quarantine", "rollback", "freeze_writes", "revoke_grant"),
            EmergencyGate.STOP_LOSS_WHITELIST,
        )
        // 白名单内 ⇒ null（可先做，0 秒止损授权）
        EmergencyGate.STOP_LOSS_WHITELIST.forEach { wire ->
            assertNull("白名单内可先做（$wire）", EmergencyGate.programDisposalRejection(wire))
            assertTrue("白名单判定自洽（$wire）", EmergencyGate.isStopLossWhitelisted(wire))
        }
        // 结构性判据：程序可执行的每一个止损动作（315 的闭集枚举）都必须在 law 名单内——
        // 枚举是闭集 ⇒ 程序构造不出名单外的处置动作（签名上就拿不到处置权）
        StopLossAction.entries.forEach { action ->
            assertTrue(
                "程序可执行的止损 ${action.wire} 不在 law 白名单内：程序路径拿到了名单外的处置权",
                EmergencyGate.isStopLossWhitelisted(action.wire),
            )
        }
        // 签名与路径：executeStopLoss 只收 StopLossAction（闭集枚举），且它是生产里唯一的执行口
        val wiring = LawIo.stripComments(
            LawIo.read("src/main/java/com/example/zhengdao/baopu/inspect/EmergencyWiring.kt"),
        )
        val signature = wiring.substringAfter("fun executeStopLoss(", "").substringBefore("{")
        assertTrue("executeStopLoss 签名没找到", signature.isNotEmpty())
        assertTrue(
            "止损执行口只许收 StopLossAction（闭集白名单枚举）：收 String wire 就能绕开 law 名单",
            signature.contains("action: StopLossAction") && !signature.contains("action: String"),
        )
        val executors = LawIo.sources().mapNotNull { (path, src) ->
            if (LawIo.stripComments(src).contains("executeStopLoss(")) path else null
        }
        assertEquals(
            "生产里出现第二个止损执行口：程序路径多了一条拿到处置权的技术（必须先过 law 判定并登记）",
            listOf("baopu/inspect/EmergencyWiring.kt"),
            executors,
        )
    }

    // ── 负例：永久排除与白名单外，一律不许程序先斩 ──────────────────────────────

    @Test
    fun `L30_负例_永久排除四类与白名单外动作申请程序处置必须拒`() {
        // 永久排除四类：连人都不能授权程序做，拒因要点名「永久排除」
        EmergencyGate.DISPOSAL_PERMANENTLY_EXCLUDED.forEach { wire ->
            val rejection = EmergencyGate.programDisposalRejection(wire)
            assertNotNull("永久排除 $wire 必须拒", rejection)
            assertTrue("拒因要点名永久排除（$wire）", rejection!!.contains("永久排除"))
        }
        assertEquals(
            "永久排除必须逐字是这四类",
            setOf("delete_data", "exfiltrate_data", "alter_law", "alter_grant"),
            EmergencyGate.DISPOSAL_PERMANENTLY_EXCLUDED,
        )
        // 白名单外、来路不明的动作：处置权只能是人（回 L21）
        val unknown = EmergencyGate.programDisposalRejection("launch_missiles")
        assertNotNull("白名单外必须拒", unknown)
        assertTrue("拒因要点名处置权只能是人", unknown!!.contains("处置权只能是人"))
        // 白名单 ∪ 永久排除不许有交集（一件动作不许同时"可先做"与"永久排除"）
        assertTrue(
            "白名单与永久排除不许有交集",
            EmergencyGate.STOP_LOSS_WHITELIST.intersect(EmergencyGate.DISPOSAL_PERMANENTLY_EXCLUDED).isEmpty(),
        )
    }

    @Test
    fun `L30_负例_永久排除的wire键只许住在law层_生产其余路径不许出现`() {
        // 防空转：扫描器必须真的看见了生产树（路径写错时空集恒绿）
        LawIo.assertNotBlind(LawIo.MAIN_ROOT, minFiles = 100, minLines = 10_000)
        // 永久排除四类的 wire 键是"可执行动作的键"：除 law 层的定义本身外，
        // 生产任何路径都不许出现——出现＝有第二条以这些键执行的路（注释不算，字面量算）
        val outsideLaw = LawIo.sources()
            .filterNot { it.first.startsWith("baopu/law/") }
            .filter { (_, src) ->
                val code = LawIo.stripComments(src)
                EmergencyGate.DISPOSAL_PERMANENTLY_EXCLUDED.any { wire -> code.contains(wire) }
            }
            .map { it.first }
        assertEquals(
            "永久排除四类的执行键在生产非 law 层出现（$outsideLaw）：那是一条绕开处置权判定的暗路",
            emptyList<String>(),
            outsideLaw,
        )
    }
}
