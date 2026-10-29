// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// ⚠️ 本文件是**规范（spec）**，不是门禁：生产未接线（#271-L26），它**不会红**。
//    它钉的是「判据长什么样」；接线那天把判据搬进生产、用例名不变，它才升格为门禁。
//
// 第二百七十一批 · 法典规范（spec，非门禁） **L26 审批与撤销必须绑定操作身份**（法典卷九 L26）。
//
// ## 判据（可执行）
// 任何授权（开单）与撤销，都绑 **`actionId ＋ argsHash ＋ 对象清单摘要`** 三元组：
//   ① 执行前重算三者，与授权时存的比，**任一不符 ⇒ 视为未授权（拦，不是警告）**；
//   ② 撤销也按同一三元组定位，**不许按文本匹配**（文本相同的两件事可能不是一件事）。
//
// 摘要用**生产那一份**（`RunTraceRules.argsHash`，16 位 SHA-256 前缀）——
// 判据层不再另造一个哈希，否则"授权时算一种、执行时算另一种"就会永远对不上。
//
// ## 生产状态（诚实标注）——**本文件是规范，不是门禁；生产未接线（#271-L26），它不会红**
// 三元组绑定还没落进生产（249 的授权只绑对象清单、不绑参数摘要；`RunTrace` 已有 argsHash 形态
// 但没人拿它跟授权对拍）⇒ 判据形状规范，缺陷 #271-L26 在账。
//
// ## 负例（造违规即红——红的是**判据**，不是生产：生产里还没有它）
// ① 改一个参数后仍用旧授权 ⇒ 必拒；② 按旧摘要撤销 ⇒ 必拒；③ 文本相同但对象不同的两件事 ⇒ 必拒。
package com.example.zhengdao.law.spec


import com.example.zhengdao.baopu.agent.RunTraceRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L26ApprovalBindingSpec {

    internal data class Binding(
        val actionId: String,
        val argsHash: String,
        val objectsDigest: String,
    )

    internal object Bindings {

        /** 三元组全等才算同一件事（**不做任何"近似匹配"**）。 */
        fun same(a: Binding, b: Binding): Boolean = a == b

        /** 执行闸：授权不是这次动作 ⇒ 视为未授权。 */
        fun executeAllowed(granted: Binding?, actual: Binding): Pair<Boolean, String> = when {
            granted == null -> false to "没有授权：先开单"
            same(granted, actual) -> true to "三元组一致：放行"
            granted.actionId != actual.actionId -> false to "actionId 不符：这不是同一个动作"
            granted.argsHash != actual.argsHash -> false to "argsHash 不符：参数被改过，旧授权作废"
            else -> false to "对象清单摘要不符：动的是别的对象"
        }

        /** 撤销闸：按三元组定位；按旧摘要撤销 ⇒ 拒（撤销错对象同样拒）。 */
        fun revokeAllowed(granted: Binding, target: Binding): Pair<Boolean, String> = when {
            same(granted, target) -> true to "三元组一致：可撤销"
            else -> false to "撤销目标与授权时不是同一个三元组：不许按文本匹配撤销"
        }
    }

    private fun args(target: String) = JSONObject().put("target", target).put("text", "x")
    private fun digestOf(vararg ids: String) = ids.joinToString("|")

    private fun binding(actionId: String = "act-1", target: String = "a.md", objects: String = digestOf("a.md")) =
        Binding(
            actionId = actionId,
            argsHash = RunTraceRules.argsHash(args(target)),
            objectsDigest = objects,
        )

    // ── 正例：三元组一致 ⇒ 放行 / 可撤销 ───────────────────────────────────

    @Test
    fun `L26_正例_三元组一致才放行且可撤销`() {
        val granted = binding()
        val (ok, why) = Bindings.executeAllowed(granted, binding())
        assertTrue("三元组一致应当放行：$why", ok)
        assertTrue("撤销同一件事也应允许", Bindings.revokeAllowed(granted, binding()).first)
        // 参数摘要必须是真哈希，不是原文（红线：RunTrace 不存正文）
        assertFalse("摘要里不许出现参数原文", granted.argsHash.contains("a.md"))
        assertEquals("摘要长度固定", 16, granted.argsHash.length)
    }

    // ── 负例①：改一个参数后仍用旧授权 ─────────────────────────────────────

    @Test
    fun `L26_负例1_改一个参数后旧授权必须作废`() {
        val granted = binding(target = "a.md")
        val changed = binding(target = "b.md")
        assertFalse("改了参数摘要却没变＝摘要没用", granted.argsHash == changed.argsHash)

        val (ok, why) = Bindings.executeAllowed(granted, changed)
        assertFalse("改一个参数后仍用旧授权 ⇒ 必拒", ok)
        assertTrue("理由要点明参数被改过：$why", why.contains("参数"))

        // 换 actionId / 换对象清单同样拒
        assertFalse("换 actionId 必拒", Bindings.executeAllowed(granted, changed.copy(actionId = "act-2")).first)
        assertFalse(
            "换对象清单必拒",
            Bindings.executeAllowed(granted, granted.copy(objectsDigest = digestOf("a.md", "c.md"))).first,
        )
        assertFalse("没有授权当然拒", Bindings.executeAllowed(null, granted).first)
    }

    // ── 负例②：按旧摘要撤销 ───────────────────────────────────────────────

    @Test
    fun `L26_负例2_按旧摘要撤销必须拒`() {
        val granted = binding(target = "a.md")
        val stale = binding(target = "b.md") // 旧摘要：参数已经变了
        val (ok, why) = Bindings.revokeAllowed(granted, stale)
        assertFalse("按旧摘要撤销 ⇒ 必拒", ok)
        assertTrue("理由要点明不许按文本匹配：$why", why.contains("文本"))

        // 文本相同、对象不同的两件事，不许被当成同一件
        val sameTextOtherObject = Binding("act-1", granted.argsHash, digestOf("z.md"))
        assertFalse("对象清单不同 ⇒ 不是同一件事", Bindings.revokeAllowed(granted, sameTextOtherObject).first)
    }
}
