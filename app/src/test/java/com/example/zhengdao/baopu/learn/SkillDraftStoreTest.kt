// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 B/C（阶段二）· 草稿区测试：同意图自动归并成一条（判据③的接线）、
// 拒收不入区（判据①②）、封顶、弃；**不转正**负例——入区只写草稿区，技能目录零落盘。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SkillDraftStoreTest {

    private fun faces(name: String = "查询", xp: String = "/html/body/button[1]", tag: String = "BUTTON") = ObserveFaces(
        tag = tag, role = "button", name = name, aria = "", testid = "", id = "",
        inputName = "", placeholder = "", inputType = "", auto = "", formLabel = "",
        xp = xp, cls = emptyList(),
    )

    private fun step(index: Int, name: String = "查询", kind: String = "click", param: String? = null, xp: String = "button[$index]") = DraftStep(
        index = index, intent = name, kind = kind, url = "https://example.test/list",
        locator = faces(name = name, xp = xp, tag = if (kind == "type") "INPUT" else "BUTTON"),
        param = param, optional = false, candidates = emptyList(),
        expect = listOf("urlChanged"), evidence = listOf("观察/会话-1.jsonl#seq$index"),
    )

    private fun draft(goal: String, steps: List<DraftStep>, id: String = "d${goal.hashCode()}") = SkillDraft(
        id = id, goal = goal, targetSite = "example.test",
        params = steps.filter { it.param != null }.map { DraftParam(it.param!!, "step${it.index}.input") },
        skeleton = steps, variableSteps = steps.filter { it.param != null }.map { it.index },
        mergedFrom = 1, sourceSessions = listOf("1"),
    )

    private fun storeIn(tmp: File) = SkillDraftStore(File(tmp, SkillDraftStore.DIR_NAME))

    @Test
    fun `判据3_同意图第二份入区自动归并成一条并标N`() {
        TempSandbox.use("zd624b-store-merge") { tmp ->
            val store = storeIn(tmp)
            val first = store.add(draft("查装车", listOf(step(1), step(2, kind = "type", param = "单号", name = "单号"))), "d1")
            assertTrue(first is SkillDraftStore.AddOutcome.Created)
            val second = store.add(draft("查装车", listOf(step(1), step(2, kind = "type", param = "单号", name = "单号"))), "d2")
            assertTrue(second is SkillDraftStore.AddOutcome.Merged)
            val merged = (second as SkillDraftStore.AddOutcome.Merged).draft
            assertEquals("合成一条并标 N", 2, merged.mergedFrom)
            assertEquals("区里只有一条", 1, store.pending().size)
            // 回环：归并产物落盘可读
            assertEquals(merged, store.byId(merged.id))
        }
    }

    @Test
    fun `不同意图_两条各自入区`() {
        TempSandbox.use("zd624b-store-two") { tmp ->
            val store = storeIn(tmp)
            store.add(draft("查装车", listOf(step(1))), "d1")
            store.add(draft("查对账", listOf(step(1), step(2, name = "导出", xp = "button[2]"))), "d2")
            assertEquals(2, store.pending().size)
        }
    }

    @Test
    fun `判据1_2_定位全空与无证据的草案拒收不入区`() {
        TempSandbox.use("zd624b-store-reject") { tmp ->
            val store = storeIn(tmp)
            val blank = ObserveFaces(
                tag = "", role = "", name = "", aria = "", testid = "", id = "",
                inputName = "", placeholder = "", inputType = "", auto = "", formLabel = "",
                xp = "", cls = emptyList(),
            )
            val r1 = store.add(draft("查装车", listOf(step(1, name = "").copy(locator = blank))), "d1")
            assertTrue("判据①负例被拒", r1 is SkillDraftStore.AddOutcome.Rejected)
            assertTrue((r1 as SkillDraftStore.AddOutcome.Rejected).problems.any { it.contains(SkillDraftRules.BLANK_LOCATOR) })
            val r2 = store.add(draft("查装车", listOf(step(1, name = "").copy(evidence = emptyList()))), "d2")
            assertTrue("判据②负例被拒", r2 is SkillDraftStore.AddOutcome.Rejected)
            assertTrue((r2 as SkillDraftStore.AddOutcome.Rejected).problems.any { it.contains(SkillDraftRules.NO_EVIDENCE) })
            assertEquals("拒收的不入区", 0, store.pending().size)
        }
    }

    @Test
    fun `封顶20条_满了拒收新的`() {
        TempSandbox.use("zd624b-store-cap") { tmp ->
            val store = storeIn(tmp)
            for (i in 1..SkillDraftStore.CAP) {
                assertTrue(store.add(draft("意图$i", listOf(step(1, name = "按钮$i"))), "d$i") is SkillDraftStore.AddOutcome.Created)
            }
            val over = store.add(draft("再补一条", listOf(step(1, name = "再多一钮"))), "d-over")
            assertTrue(over is SkillDraftStore.AddOutcome.Full)
            assertEquals(SkillDraftStore.CAP, store.pending().size)
        }
    }

    @Test
    fun `弃掉一条_区里消失`() {
        TempSandbox.use("zd624b-store-discard") { tmp ->
            val store = storeIn(tmp)
            store.add(draft("查装车", listOf(step(1))), "d1")
            val id = store.pending().single().id
            store.resolve(id)
            assertNull(store.byId(id))
            assertEquals(0, store.pending().size)
        }
    }

    @Test
    fun `不转正负例_入区不落技能目录_也整不出回放json`() {
        TempSandbox.use("zd624b-store-notpromote") { tmp ->
            val store = storeIn(tmp)
            store.add(draft("查装车", listOf(step(1))), "d1")
            assertFalse("技能目录不该存在（转正必须人批）", File(tmp, "技能").isDirectory)
            assertFalse(File(tmp, "技能").exists())
            assertTrue("草稿区有草案文件", store.pending().isNotEmpty())
        }
    }
}
