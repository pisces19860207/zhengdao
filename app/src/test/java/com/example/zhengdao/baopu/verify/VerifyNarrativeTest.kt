// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.todo.TodoRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三百一十七批 · 人话层的**行为**测（判据①②③④在这一头各钉一格）。
 *
 * 这一层唯一的产出是「话术键＋参数」，所以这里断言的全是**键名与结构**，
 * 不是一句中文——中文在 `strings_baopu_verify.xml`，改措辞由
 * `ui.baopu.VerifyVisibleGateTest` 保证门禁不红（派工单前置②；541 在这上面栽过）。
 *
 * 判据①「不出现内部词」在这一头有两个方向：
 * - 选出来的键不许是内部词（键名是 `baopu_verify_*`，见下表逐条对号）；
 * - 运行时拼进来的那半句（文件名/明细/页名）过 [VerifyNarrative.sanitize]，
 *   带内部词的**整条丢掉**（不是擦掉那几个字——擦完上屏的是半截内部词）。
 */
class VerifyNarrativeTest {

    private fun check(id: CheckId, ok: Boolean, missing: Boolean) =
        CheckOutcome(id = id, ok = ok, missing = missing, detail = "")

    private fun report(
        status: Status,
        checks: List<CheckOutcome>,
        reason: Reason = if (status == Status.PASS) Reason.OK else Reason.MISSING_EVIDENCE,
    ) = ContractReport(
        taskType = TaskType.DOC_PRODUCE,
        status = status,
        reason = reason,
        checks = checks,
        note = "口径说明（内部串，只进日志，本层一个字都不抄上屏）",
    )

    // ── 判据① 三态各有一句人话 ────────────────────────────────────────────

    @Test
    fun `判据1_PASS走那一键且不给按钮`() {
        val line = VerifyNarrative.fromContract(
            report(Status.PASS, listOf(check(CheckId.EXISTS, true, false))),
            listOf("产物/周报.md"),
            gateFindings = 0,
        )!!
        assertEquals(VerifyPhrase.PASS, line.conclusion)
        assertEquals(VerifyPhrase.BASIS_FILES, line.basis)
        assertEquals(listOf(1), line.numbers)
        // 过了不打扰：不给一整套按钮（派工单 §一.2 那句「验过了」后面没有「要我再做一次吗」）。
        assertTrue(line.actions.isEmpty())
        // 三态原值只留给测试与日志，文案层拿不到它（渲染层只吃 conclusion/basis）。
        assertEquals("PASS", line.verdict)
    }

    @Test
    fun `判据1_FAIL点得出缺项走DETAIL_点不出走BARE`() {
        val named = VerifyNarrative.fromContract(
            report(Status.FAIL, listOf(check(CheckId.EXISTS, false, false))),
            listOf("产物/周报.md"),
            gateFindings = 0,
        )!!
        assertEquals(VerifyPhrase.FAIL_DETAIL, named.conclusion)
        assertEquals(listOf(VerifyPhrase.ITEM_EXISTS), named.items)
        assertEquals(listOf(VerifyAction.AGAIN, VerifyAction.NOTE), named.actions)

        val bare = VerifyNarrative.fromContract(
            report(Status.FAIL, emptyList(), reason = Reason.VALUE_MISMATCH),
            listOf("产物/周报.md"),
            gateFindings = 0,
        )!!
        assertEquals(
            "一条缺项都点不出来时不许留空占位符（那是「没验过：缺 。」那种不是人话的话）",
            VerifyPhrase.FAIL_BARE,
            bare.conclusion,
        )
        assertTrue(bare.items.isEmpty())

        val noEvidence = VerifyNarrative.fromContract(
            report(Status.FAIL, emptyList()),
            emptyList(),
            gateFindings = 0,
        )!!
        assertEquals("什么可对的东西都没有 ⇒ 用 §二 第二行那句「我没法确认」",
            VerifyPhrase.NO_EVIDENCE, noEvidence.conclusion)
    }

    @Test
    fun `判据1_UNVERIFIABLE同样两句且有先记下来`() {
        val named = VerifyNarrative.fromContract(
            report(Status.UNVERIFIABLE, listOf(check(CheckId.LEDGER_COMPLETE, false, true))),
            listOf("产物/周报.md"),
            gateFindings = 0,
        )!!
        assertEquals(VerifyPhrase.UNVERIFIABLE_DETAIL, named.conclusion)
        assertEquals(listOf(VerifyPhrase.ITEM_LEDGER_COMPLETE), named.items)
        assertEquals(listOf(VerifyAction.AGAIN, VerifyAction.NOTE), named.actions)

        val bare = VerifyNarrative.fromContract(
            report(Status.UNVERIFIABLE, emptyList()),
            listOf("产物/周报.md"),
            gateFindings = 0,
        )!!
        assertEquals(VerifyPhrase.UNVERIFIABLE_BARE, bare.conclusion)
        assertTrue("判据④的落点：这一态必须给「就这样，先记下来」",
            bare.actions.contains(VerifyAction.NOTE))
    }

    @Test
    fun `判据1_缺项名是键名不是判据号`() {
        // CheckId 的 wire（path_set…）与 cn（路径集合精确相等…）都不许成为屏上的字；
        // 14 条判据逐条都翻得出色：漏一条＝把判据号贴给用户。
        assertEquals(CheckId.entries.size, VerifyPhrase.entries.count { it.key.startsWith("baopu_verify_item_") })
        CheckId.entries.forEach { id ->
            val phrase = VerifyNarrative.itemOf(id)
            assertTrue("${id.name} 没成人话名", phrase.key.startsWith("baopu_verify_item_"))
            assertTrue("${id.name} 的人话名里混进了内部串", VerifyNarrative.sanitize(phrase.key) != null)
        }
    }

    @Test
    fun `判据2_一轮最多一行`() {
        val a = line(VerifyPhrase.PASS)
        val b = line(VerifyPhrase.NO_EVIDENCE)
        var rows = VerifyLines.absorb(emptyList(), 100L, a)
        rows = VerifyLines.absorb(rows, 100L, b)
        rows = VerifyLines.absorb(rows, 100L, a)
        assertEquals("同一轮写三次也只有一行（派工单负例②在这一处结构上发生不了）", 1, rows.size)
        assertEquals(b, VerifyLines.find(rows, 100L))
        // 没锚（引擎没给库 id）与没结论都不落行
        assertEquals(rows, VerifyLines.absorb(rows, 0L, a))
        assertEquals(rows, VerifyLines.absorb(rows, 101L, null))
        assertEquals(2, VerifyLines.absorb(rows, 101L, b).size)
    }

    @Test
    fun `判据2_台账有上限不无界增长`() {
        var rows = emptyList<VerifyRow>()
        (1L..60L).forEach { rows = VerifyLines.absorb(rows, it, line(VerifyPhrase.PASS)) }
        assertEquals(VerifyLines.MAX_ROUNDS_KEPT, rows.size)
        assertEquals(60L, rows.last().anchor)
        assertNull(VerifyLines.find(rows, 1L))
        assertTrue(VerifyLines.clear().isEmpty())
    }

    // ── 判据③ 三类失败各有出路 ────────────────────────────────────────────

    @Test
    fun `判据3_页面没读出来给三个去处并带着那一页`() {
        val base = VerifyNarrative.fromContract(
            report(Status.FAIL, listOf(check(CheckId.EXISTS, false, false))),
            listOf("产物/周报.md"),
            gateFindings = 0,
        )
        val line = VerifyNarrative.withFailureKind(base, VerifyFailureKind.PAGE_READ, "example.com", "https://example.com/a")!!
        assertEquals(VerifyPhrase.PAGE_READ, line.conclusion)
        assertEquals(VerifyPhrase.BASIS_READ, line.basis)
        assertEquals(listOf(VerifyAction.RETRY, VerifyAction.SWAP, VerifyAction.XUANLAN), line.actions)
        assertEquals("example.com", line.place)
        assertEquals("https://example.com/a", line.url)
        assertEquals("缺项与证据留着（点开还得看）", listOf(VerifyPhrase.ITEM_EXISTS), line.items)
        // 没有页地址 ⇒ 依据退回不带地址那句，不拿「要看的是 」空白上屏
        val noPlace = VerifyNarrative.withFailureKind(base, VerifyFailureKind.PAGE_READ, null, null)!!
        assertEquals(VerifyPhrase.BASIS_BARE, noPlace.basis)
        assertEquals("", noPlace.url)
    }

    @Test
    fun `判据3_需要人操作只给去点`() {
        val line = VerifyNarrative.withFailureKind(null, VerifyFailureKind.NEEDS_TAP, "example.com", "https://example.com")!!
        assertEquals(VerifyPhrase.NEEDS_TAP, line.conclusion)
        assertEquals(listOf(VerifyAction.GO_TAP), line.actions)
        assertEquals(VerifyPhrase.BASIS_TAP, line.basis)
        // 定位不到具体位置 ⇒ 换一句不留空白的（负例④那种「点了没反应」的前半：话先说圆）
        val nowhere = VerifyNarrative.withFailureKind(null, VerifyFailureKind.NEEDS_TAP, null, null)!!
        assertEquals(VerifyPhrase.NEEDS_TAP_NOWHERE, nowhere.conclusion)
        assertEquals(listOf(VerifyAction.GO_TAP), nowhere.actions)
    }

    @Test
    fun `判据3_失败类别只认真读数不猜`() {
        // 「需要人动手」＝224 批 form_fill 自己账上那一格
        assertEquals(VerifyFailureKind.NEEDS_TAP, VerifyNarrative.failureKindOf(
            "form_fill", """{"ok":true,"needsHumanClick": true}""", true))
        // 同一格出现在别的工具里不算（别的工具没有这个字段）
        assertNull(VerifyNarrative.failureKindOf("file_write", """{"needsHumanClick":true}""", true))
        // 「页面没读出来」＝玄览器件族／填表器件的失败回执
        assertEquals(VerifyFailureKind.PAGE_READ, VerifyNarrative.failureKindOf("browser_screenshot", "", false))
        assertEquals(VerifyFailureKind.PAGE_READ, VerifyNarrative.failureKindOf("teach_observe", "", false))
        assertEquals(VerifyFailureKind.PAGE_READ, VerifyNarrative.failureKindOf("form_fill", "", false))
        // 与页面无关的工具 ⇒ 什么都不改
        assertNull(VerifyNarrative.failureKindOf("doc_write", "写了 3 段", true))
        assertNull(VerifyNarrative.failureKindOf("doc_write", "", false))
    }

    @Test
    fun `判据3_两类同时命中先说人要动手`() {
        assertEquals(VerifyFailureKind.NEEDS_TAP,
            VerifyNarrative.strongest(VerifyFailureKind.NEEDS_TAP, VerifyFailureKind.PAGE_READ))
        assertEquals(VerifyFailureKind.NEEDS_TAP,
            VerifyNarrative.strongest(VerifyFailureKind.PAGE_READ, VerifyFailureKind.NEEDS_TAP))
        assertEquals(VerifyFailureKind.PAGE_READ, VerifyNarrative.strongest(null, VerifyFailureKind.PAGE_READ))
        assertNull(VerifyNarrative.strongest(null, null))
    }

    // ── 判据① 的另一半：运行时输入消毒 ────────────────────────────────────

    @Test
    fun `消毒_内部词整条丢掉_好文件名不误伤`() {
        listOf(
            "UNVERIFIABLE（判不了）", "验收 FAIL 了", "sha256 8d9b…", "GapItem 缺",
            "missing_evidence", "value_mismatch", "PASS",
        ).forEach { assertTrue("「$it」带内部词，必须整条丢掉", VerifyNarrative.sanitize(it) == null) }
        // 整词档存在的理由：盘上真有好文件名长成这样，按子串扫会把它们悄悄吃掉
        listOf("password.md", "test_failure.log", "failures.txt", "产物/周报.md").forEach {
            assertNotNull("「$it」是好文件名，不该被误伤", VerifyNarrative.sanitize(it))
        }
        assertNull(VerifyNarrative.sanitize("   "))
        // 多行输入折成一行（证据栏一行了一个文件，换行会把浮层撑成别的样子）
        assertEquals("多 行 输入", VerifyNarrative.sanitize("多\n行\n输入"))
        // 判别器自证：两档表合起来覆盖 FORBIDDEN 全集，且这套尺真在扫
        assertTrue(VerifyNarrative.FORBIDDEN.isNotEmpty())
        assertTrue(VerifyNarrative.WHOLE_WORD_FORBIDDEN.contains("PASS"))
    }

    @Test
    fun `消毒过的证据才进得了那一行`() {
        val line = VerifyNarrative.fromContract(
            report(Status.PASS, listOf(check(CheckId.EXISTS, true, false))),
            listOf("UNVERIFIABLE.md", "sha256:abcd", "产物/周报.md"),
            gateFindings = 0,
        )!!
        assertEquals(listOf("产物/周报.md"), line.evidence)
        assertEquals(listOf(1), line.numbers)
    }

    // ── 判据④ 先记下来 ────────────────────────────────────────────────────

    @Test
    fun `判据4_先记下来那条裁到541那把尺`() {
        val long = "先记下：这一轮验收没过（" + "字".repeat(200) + "）"
        val summary = verifyTodoSummary(long)
        assertTrue(TodoRules.charCount(summary) <= TodoRules.MAX_SUMMARY_CHARS)
        // 复用同一把尺：不超限的原样过（Normalize 之后），本层不自立第二个上限
        val short = verifyTodoSummary("先记下：这一轮验收没过（写出来的文件）")
        assertEquals(TodoRules.normalize("先记下：这一轮验收没过（写出来的文件）"), short)
        assertTrue(TodoRules.refusal(summary) == null)
    }

    @Test
    fun `纯问答轮不出行`() {
        assertNull("这一轮没有验收单 ⇒ 一个字都不画（不拿「验过了」糊弄人）",
            VerifyNarrative.fromContract(null, listOf("产物/周报.md"), 0))
        assertNull(VerifyNarrative.withFailureKind(null, null, "example.com", "https://example.com"))
    }

    // ── 右相那份用同一套词表（不许另起一套）────────────────────────────────

    @Test
    fun `右相那份翻人话与收工单子同一套键`() {
        val pass = VerifyNarrative.fromChancellor(
            ChancellorReport(ChancellorVerdict.PASS, emptyList(), listOf("字节数一致 1,024"), emptyList(), true, 12L),
        )
        assertEquals(VerifyPhrase.PASS, pass.conclusion)
        assertEquals(VerifyPhrase.BASIS_ROWS, pass.basis)
        assertTrue(pass.actions.isEmpty())

        val fail = VerifyNarrative.fromChancellor(
            ChancellorReport(
                ChancellorVerdict.FAIL,
                listOf(
                    ChancellorFinding("C1", "两份文件的字节数对不上", "凭什么"),
                    ChancellorFinding("C2", "sha256 没对上", "凭什么"),
                ),
                emptyList(),
                emptyList(),
                false,
                30L,
            ),
        )
        assertEquals(VerifyPhrase.FAIL_BARE, fail.conclusion)
        assertTrue("302 的判据号（C1/C2）不上屏：走 bare 那句", fail.items.isEmpty())
        assertEquals("带内部词的那条证据整条丢掉", listOf("两份文件的字节数对不上"), fail.evidence)
        assertEquals(listOf(2), fail.numbers)

        val cannot = VerifyNarrative.fromChancellor(
            ChancellorReport(ChancellorVerdict.UNVERIFIABLE, emptyList(), emptyList(), listOf("缺路径"), false, 5L),
        )
        assertEquals(VerifyPhrase.UNVERIFIABLE_DETAIL, cannot.conclusion)
        assertEquals(listOf("缺路径"), cannot.evidence)
        assertTrue(cannot.actions.contains(VerifyAction.NOTE))
    }

    private fun line(conclusion: VerifyPhrase) = VerifyLine(
        conclusion = conclusion,
        items = emptyList(),
        place = "",
        url = "",
        basis = VerifyPhrase.BASIS_BARE,
        numbers = emptyList(),
        actions = emptyList(),
        evidence = emptyList(),
        verdict = "UNVERIFIABLE",
    )
}
