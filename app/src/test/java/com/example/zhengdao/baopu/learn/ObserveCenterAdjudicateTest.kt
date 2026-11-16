// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十二批 · 中枢层的接线判据：`ObserveCenter.adjudicate` 是**唯一**的裁定写入口，
// 它落盘 → 读数流刷新 → 转正门改口（判据①「有冲突未裁」→ 裁完往下一关走）；
// 参数不合法（没冲突／没这套定位／没有这一步）一律如实说不猜。
package com.example.zhengdao.baopu.learn

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ObserveCenterAdjudicateTest {

    private val texts = LearnCardTexts(
        qAmbiguous = "第 {步} 步「{目标}」有 {条数} 条走法",
        qNoEvidence = "第 {步} 步缺证据",
        qParam = "第 {步} 步是参数吗",
        qIntent = "在「{页}」做哪件事",
        optParam = "是参数",
        optFixed = "固定值",
        optTeach = "就教这一步",
        optDrop = "这一步不算",
        freeText = "或者直接说你的想法",
    )

    private var fakeNow = 1_700_000_000_000L

    private fun faces(name: String, xp: String) = ObserveFaces(
        tag = "BUTTON", role = "button", name = name, aria = "", testid = "", id = "",
        inputName = "", placeholder = "", inputType = "", auto = "", formLabel = "",
        xp = xp, cls = emptyList(),
    )

    private fun conflictDraft() = SkillDraft(
        id = "d672", goal = "查装车", targetSite = "example.test", params = emptyList(),
        skeleton = listOf(
            DraftStep(
                index = 1, intent = "查询", kind = "click", url = "https://example.test/list",
                locator = ObserveFaces(
                    tag = "", role = "", name = "", aria = "", testid = "", id = "", inputName = "",
                    placeholder = "", inputType = "", auto = "", formLabel = "", xp = "", cls = emptyList(),
                ),
                param = null, optional = false,
                candidates = listOf(faces("查询", "button[1]"), faces("查货", "button[2]")),
                expect = listOf("urlChanged"),
                evidence = listOf("观察/会话-1.jsonl#seq1"),
            ),
        ),
        variableSteps = emptyList(), mergedFrom = 2, sourceSessions = listOf("1", "2"),
    )

    private fun installIn(root: File) {
        ObserveCenter.resetForTest()
        ObserveCenter.install(
            root = root,
            cardTexts = texts,
            clock = { fakeNow },
            harvest = { _ -> null },
            nodeTable = { _ -> null },
            enabled = { false },
        )
    }

    @Test
    fun `裁定落盘后读数流带出记录_同一步后裁的胜`() {
        TempSandbox.use("zd672-center-adj") { root ->
            installIn(root)
            SkillDraftStore(File(root, SkillDraftStore.DIR_NAME)).add(conflictDraft(), "d672")
            val first = ObserveCenter.adjudicate("d672", 1, DraftChoice.A)
            assertTrue("成功要说人话：$first", first.contains("已记下"))
            assertEquals("读数流里有一份", 1, ObserveCenter.adjudications.value["d672"]?.size)
            ObserveCenter.adjudicate("d672", 1, DraftChoice.B)
            val rec = ObserveCenter.adjudications.value.getValue("d672").single()
            assertEquals("同一步覆盖", DraftChoice.B, rec.resolved)
            assertEquals("用户字段有人", DraftAdjudications.USER, rec.by)
            assertEquals("时间字段是钟给的", fakeNow, rec.atMs)
        }
    }

    @Test
    fun `判据1_未裁时转正被拒_理由有冲突未裁_裁完往下一关走`() {
        TempSandbox.use("zd672-center-confirm") { root ->
            installIn(root)
            SkillDraftStore(File(root, SkillDraftStore.DIR_NAME)).add(conflictDraft(), "d672")
            val before = runBlocking { ObserveCenter.confirmDraft("d672", "查装车", emptyMap()) }
            assertTrue("未裁必须拒：$before", before.contains(SkillDraftRules.CONFLICT_UNRESOLVED))
            ObserveCenter.adjudicate("d672", 1, DraftChoice.A)
            val after = runBlocking { ObserveCenter.confirmDraft("d672", "查装车", emptyMap()) }
            assertTrue("裁完不该再说有冲突未裁：$after", !after.contains(SkillDraftRules.CONFLICT_UNRESOLVED))
            assertTrue(
                "测试里没接工作区根 ⇒ 停在下一条闸上（说明已经过了裁定闸）：$after",
                after.contains("转正落点未接线"),
            )
        }
    }

    @Test
    fun `参数不合法如实说_不猜不写盘`() {
        TempSandbox.use("zd672-center-bad") { root ->
            installIn(root)
            SkillDraftStore(File(root, SkillDraftStore.DIR_NAME)).add(conflictDraft(), "d672")
            assertTrue(ObserveCenter.adjudicate("nope", 1, DraftChoice.A).contains("草案不存在"))
            assertTrue(ObserveCenter.adjudicate("d672", 9, DraftChoice.A).contains("没有第 9 步"))
            assertTrue(ObserveCenter.adjudicate("d672", 1, DraftChoice.NONE).contains(SkillDraftRules.RETEACH))
            assertTrue(
                "裁 none 之后再要 A：照样记（用户改主意），不报错",
                ObserveCenter.adjudicate("d672", 1, DraftChoice.A).contains("已记下"),
            )
            assertNull("没写盘时读数流是空的", ObserveCenter.adjudications.value["nope"])
        }
    }
}
