// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.verify.CheckId
import com.example.zhengdao.baopu.verify.Status
import com.example.zhengdao.baopu.verify.VerifyNarrative
import com.example.zhengdao.baopu.verify.VerifyPhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第三百一十七批 · 验收那一行上屏的**接线门禁**（判据①②③④＋四条负例）。
 *
 * 与 178/136 批同族：行为在 `VerifyNarrativeTest`（真数据、真三态），这里只钉
 * 「接线上不许走样」，而且**钉的是 key，不是中文**（派工单前置②；541 在这上面栽过）：
 * - J1 三态各用哪个 key（键表与 `VerifyPhrase` 一一对应，漏一键当场红）；
 * - J2 中英两份 XML 对称、值里没有内部词、占位符条数按 [placeholderCounts] 钉死；
 * - J3 文案全在 strings：两个新文件的**代码**（剥注释后）里一个中文都不许有；
 * - J4 一轮只有一行：台账唯一的写入口是 `VerifyLines.absorb`，渲染层唯一的读法是 `find`；
 * - J5 出路按钮全接到真存在的动作（判据③），且没有 `else` 兜住的空分支；
 * - J6 生产调用点传的是真台账（不许让默认值 `emptyList()` 把这一行糊掉）。
 *
 * 每条「不许出现 X」都配判别器自证（38 批同款纪律），防门禁空跑。
 *
 * 扫描一律先剥注释：注释里提到 `PASS`／`noticeLine` 不算代码里还有它。
 */
class VerifyVisibleGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun raw(rel: String): String = repoFile(rel).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private fun strip(rel: String): String = raw(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private val narrativeRel = "src/main/java/com/example/zhengdao/baopu/verify/VerifyNarrative.kt"
    private val lineRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuVerifyLine.kt"
    private val listRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuMessageList.kt"
    private val screenRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuScreen.kt"
    private val controllerRel = "src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"
    private val engineRel = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"
    private val zhRel = "src/main/res/values/strings_baopu_verify.xml"
    private val enRel = "src/main/res/values-en/strings_baopu_verify.xml"

    private fun keys(text: String): Map<String, String> =
        Regex("""<string name="(baopu_verify_[a-z0-9_]+)"[^>]*>(.*?)</string>""")
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun zh() = keys(raw(zhRel))
    private fun en() = keys(raw(enRel))

    /** 键表（`VerifyPhrase` → `R.string.*`）从源码里读出来：映射只此一处，不用反射。 */
    private fun table(): Map<String, String> =
        Regex("""VerifyPhrase\.([A-Z_0-9]+)\s+to\s+R\.string\.(baopu_verify_[a-z0-9_]+)""")
            .findAll(strip(lineRel))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun count(src: String, needle: String): Int = Regex(Regex.escape(needle)).findAll(src).count()

    /** 每条文案要喂几位参数（**判据钉的是这张表，不是中文**）；没列进来的键一律 0 位。 */
    private val placeholderCounts = mapOf(
        "baopu_verify_fail_detail" to 1,
        "baopu_verify_unverifiable_detail" to 1,
        "baopu_verify_needs_tap" to 1,
        "baopu_verify_basis_files" to 1,
        "baopu_verify_basis_gate" to 1,
        "baopu_verify_basis_read" to 1,
        "baopu_verify_evidence_more" to 1,
        "baopu_verify_note_item" to 1,
    )

    // ── J1 判据①：三态各钉一个 key ────────────────────────────────────────

    @Test
    fun `J1_三态用哪个key钉死`() {
        val t = table()
        assertEquals("baopu_verify_pass", t["PASS"])
        assertEquals("baopu_verify_fail_detail", t["FAIL_DETAIL"])
        assertEquals("baopu_verify_fail_bare", t["FAIL_BARE"])
        assertEquals("baopu_verify_unverifiable_detail", t["UNVERIFIABLE_DETAIL"])
        assertEquals("baopu_verify_unverifiable_bare", t["UNVERIFIABLE_BARE"])
        // §二 那三类
        assertEquals("baopu_verify_page_read", t["PAGE_READ"])
        assertEquals("baopu_verify_no_evidence", t["NO_EVIDENCE"])
        assertEquals("baopu_verify_needs_tap", t["NEEDS_TAP"])
        assertEquals("baopu_verify_needs_tap_nowhere", t["NEEDS_TAP_NOWHERE"])
        // 14 条判据的人话名一条不许漏（漏了＝把判据号贴给用户）
        assertEquals(CheckId.entries.size, t.keys.count { it.startsWith("ITEM_") })
        // 判别器自证：这张表真在读源码（读歪了会是空表）
        assertTrue(t.isNotEmpty())
    }

    @Test
    fun `J1_键表与话术枚举一一对应`() {
        val t = table()
        val names = VerifyPhrase.entries.map { it.name }.toSet()
        assertEquals("键表多了枚举里没有的键", names, t.keys)
        assertEquals("键表有重复（map 会静默吞掉后一条）", names.size, t.size)
        VerifyPhrase.entries.forEach { assertTrue("它没有对应的 <string>：${it.key}", zh().containsKey(it.key)) }
    }

    // ── J2 中英对称 ＋ 值里无内部词 ＋ 占位符按表 ──────────────────────────

    @Test
    fun `J2_中英两份对称且每条key都在`() {
        assertEquals("values-en 与 values 的键集合不一样", zh().keys, en().keys)
        assertEquals(VerifyPhrase.entries.size, zh().size)
        assertTrue("中英两份都取到了（取不到＝文件没落到 res/ 下）", zh().isNotEmpty() && en().isNotEmpty())
    }

    @Test
    fun `J2_值里没有内部词`() {
        // 同一把尺：VerifyNarrative.sanitize 就是上屏前那道消毒，这里不另立一套词表。
        listOf(zh(), en()).forEach { doc ->
            doc.forEach { (name, value) ->
                assertNotNull("「$name」的值带了内部词：$value", VerifyNarrative.sanitize(value))
            }
        }
        // 判别器自证：这套扫法真扫得出来
        assertNull(VerifyNarrative.sanitize("验收 UNVERIFIABLE（判不了）"))
        assertNull(VerifyNarrative.sanitize("见 GapItem 与 sha256"))
        assertTrue(VerifyNarrative.FORBIDDEN.isNotEmpty())
    }

    @Test
    fun `J2_占位符条数按表钉死`() {
        listOf(zh(), en()).forEach { doc ->
            doc.forEach { (name, value) ->
                val want = placeholderCounts[name] ?: 0
                val got = Regex("%\\d\\$[a-z]").findAll(value).count()
                assertEquals("「$name」的占位符条数与表不符（改了文案不改表＝运行时崩）", want, got)
            }
        }
        // 判别器自证：扫法真数得出来
        assertEquals(1, Regex("%\\d\\$[a-z]").findAll("本轮落了 %1\$s 个文件").count())
        assertEquals(0, Regex("%\\d\\$[a-z]").findAll("验过了：该写的都写了").count())
    }

    @Test
    fun `J2_带参数的那几键在渲染层真拿得到参数`() {
        val src = strip(lineRel)
        val argOf = src.substringAfter("private fun argOf").substringBefore("internal fun verifyText")
        listOf(
            "FAIL_DETAIL", "UNVERIFIABLE_DETAIL", "NOTE_ITEM", "NEEDS_TAP",
            "BASIS_READ", "BASIS_FILES", "BASIS_GATE",
        ).forEach { assertTrue("argOf 没管 $it 这一位", argOf.contains("VerifyPhrase.$it")) }
        // 脚注那条自己带数（「还有 N 条」），不走 argOf
        assertTrue(src.contains("VerifyPhrase.EVIDENCE_MORE, res, more.toString()"))
    }

    // ── J3 前置②：代码里不许写死中文 ──────────────────────────────────────

    @Test
    fun `J3_文案全在strings代码里一个中文都没有`() {
        listOf(narrativeRel, lineRel).forEach { rel ->
            val hits = strip(rel).lines().filter { Regex("[\\u4e00-\\u9fff]").containsMatchIn(it) }
            assertTrue("剥掉注释后「$rel」的代码里还有中文：$hits", hits.isEmpty())
        }
        // 判别器自证：strip 真在剥注释，注释里确实有中文（否则上面那条是空跑）
        assertTrue(raw(narrativeRel).contains("人话"))
        assertTrue(strip(narrativeRel).length < raw(narrativeRel).length)
    }

    @Test
    fun `J3_每条key都有人用不养死资源`() {
        val used = table().values.toSet()
        zh().keys.forEach { assertTrue("XML 里有键没人映射：$it", it in used) }
    }

    // ── J4 判据②：一轮最多一行 ────────────────────────────────────────────

    @Test
    fun `J4_台账唯一写入口是absorb没有第二种加法`() {
        val c = strip(controllerRel)
        assertEquals("控制器只许通过 VerifyLines.absorb 写行", 1, count(c, "VerifyLines.absorb("))
        assertEquals("新建/切换/删除会话三处一起清（不许带上一会话的结论）", 3, count(c, "VerifyLines.clear()"))
        assertFalse("不许绕过台账自己拼一行", c.contains("verifyLines = verifyLines +"))
        assertFalse(c.contains("verifyLines += "))
    }

    @Test
    fun `J4_渲染层每轮只取那一行`() {
        val l = strip(listRel)
        assertEquals("每轮按锚取那一行，只读一次", 1, count(l, "VerifyLines.find("))
        assertEquals("两个分支各一处渲染口（不是每轮两次）", 2, count(l, "BaopuVerifyLineRow("))
        assertEquals("LazyColumn 的 key 只挂一次（同 key 重复会崩，那正是二行的形状）", 1, count(l, "baopu-verify-"))
        assertEquals("组件本体只定义一处", 1, count(strip(lineRel), "internal fun BaopuVerifyLineRow("))
    }

    // ── J5 判据③④：出路按钮都有真实去处 ──────────────────────────────────

    @Test
    fun `J5_六个出路各接到真动作且没有空分支`() {
        val body = strip(controllerRel)
            .substringAfter("fun onVerifyAction(line: VerifyLine, action: VerifyAction)")
            .substringBefore("private fun verifyNoteDown")
        assertFalse("when 不许用 else 兜（加了新出路就会漏接）", body.contains("else ->"))
        assertTrue(body.contains("VerifyAction.RETRY"))
        assertTrue(body.contains("VerifyAction.AGAIN"))
        assertTrue(body.contains("retryLastTurn()"))
        assertTrue("换个做法只预填，发不发由人", body.contains("BaopuIntents.requestPrompt("))
        assertTrue(body.contains("VerifyAction.XUANLAN"))
        assertTrue(body.contains("VerifyAction.GO_TAP"))
        assertTrue("带地址就带着地址交卷给玄览", body.contains("openInXuanlan(line.url)"))
        assertTrue("没地址也要把人送到玄览那一页（不许点了没反应）", body.contains("requestBrowserTab()"))
        assertTrue("先记下来 ⇒ 541 的账", body.contains("verifyNoteDown(line)"))
        assertTrue(count(body, "VerifyAction.") >= 6)
    }

    @Test
    fun `J5_先记下来真写盘并且有回执`() {
        val c = strip(controllerRel)
        val body = c.substringAfter("private fun verifyNoteDown").take(1_600)
        assertTrue("写 541 的待办账", body.contains("TodoStore.add("))
        assertTrue("摘要裁的是 541 那把尺", body.contains("verifyTodoSummary("))
        assertTrue("来源名也走 strings", body.contains("verifyNoteSourceText("))
        assertTrue("记上了要有回执（负例④那种点了没反应）", body.contains("verifyNoteDoneText("))
        assertTrue("没记上要说没记上", body.contains("verifyNoteFailText("))
        assertTrue("盘上的写不许占主线程", body.contains("Dispatchers.IO"))
    }

    @Test
    fun `J5_需要人点那一下必须给跳转`() {
        val n = strip(narrativeRel)
        val tap = n.substringAfter("VerifyFailureKind.NEEDS_TAP ->").substringBefore("VerifyFailureKind.PAGE_READ ->")
        assertTrue("那一行必须带 GO_TAP", tap.contains("listOf(VerifyAction.GO_TAP)"))
        assertTrue("定位不到位置时换一句，不留空白", tap.contains("NEEDS_TAP_NOWHERE"))
        assertTrue("url 只给跳转用", tap.contains("url = url.orEmpty()"))
        val page = n.substringAfter("VerifyFailureKind.PAGE_READ ->").substringBefore("private fun shortfall(")
        assertTrue("页面没读出来给三个去处",
            page.contains("listOf(VerifyAction.RETRY, VerifyAction.SWAP, VerifyAction.XUANLAN)"))
    }

    // ── 负例①：内部词不许上屏（第二个出口必须拆掉）──────────────────────────

    @Test
    fun `负例1_结论只有一个出口`() {
        val e = strip(engineRel)
        assertEquals("一处发布＋一处清空，别的都不许写它", 2, count(e, "_roundVerifyLine.value ="))
        assertFalse("旧的第二处措辞（notice 那两句）必须没了", e.contains("contractNotice"))
        assertEquals("发布口只有一个（State 声明那两行不算）", 1, count(e, "val roundVerifyLine"))
        val c = strip(controllerRel)
        assertEquals("壳层只读引擎那份，不自己抄一份", 1, count(c, "engine.roundVerifyLine.value"))
        assertTrue("收工结论走人话层，不在壳层另拼句子", c.contains("VerifyNarrative.withFailureKind("))
    }

    @Test
    fun `负例1_渲染层不吃三态原值`() {
        val l = strip(lineRel)
        assertFalse("不许把 Status.cn 之类贴到屏上", l.contains(".cn"))
        assertFalse(l.contains("Status."))
        assertFalse(l.contains("reason.code"))
        assertTrue("屏上那一句唯一来源是键表翻译", l.contains("verifyRowText(line, res)"))
        // 判别器自证：三态原值确实在数据里（渲染层不读它，读到了就是红）
        assertEquals("FAIL（不过）", Status.FAIL.cn)
        assertEquals("path_set", CheckId.PATH_SET.wire)
    }

    // ── 负例③：失败不许只给错误码 ──────────────────────────────────────────

    @Test
    fun `负例3_失败那几句是完整的人话不是码`() {
        val doc = zh()
        val codes = CheckId.entries.map { it.wire }.toSet() + CheckId.entries.map { it.cn } +
            Status.entries.map { it.cn }
        listOf(
            "baopu_verify_pass", "baopu_verify_fail_detail", "baopu_verify_fail_bare",
            "baopu_verify_unverifiable_detail", "baopu_verify_unverifiable_bare",
            "baopu_verify_page_read", "baopu_verify_no_evidence", "baopu_verify_needs_tap",
        ).forEach { name ->
            val value = doc[name]
            assertNotNull("少了这条人话：$name", value)
            assertTrue("「$name」太短，不像一句人话：$value", (value?.length ?: 0) >= 4)
            assertTrue("「$name」里没有中文：$value", value != null && Regex("[\\u4e00-\\u9fff]").containsMatchIn(value))
            assertFalse("「$name」的值就是内部串本身：$value", value in codes)
        }
        // 缺项点不出来时有兜底句（不留「缺 。」那种空占位符上屏）
        assertTrue(strip(lineRel).contains("VerifyPhrase.DETAIL_NONE"))
    }

    // ── J6 生产调用点传真台账 ──────────────────────────────────────────────

    @Test
    fun `J6_屏幕层传的是控制器的真台账`() {
        val s = strip(screenRel)
        assertTrue("不许让默认值 emptyList() 把这一行糊掉", s.contains("verifyLines = controller.verifyLines"))
        assertTrue(s.contains("controller.onVerifyAction(line, action)"))
        // 判别器自证：默认值确实存在（漏接线时渲染层不会报错，只会静默什么都不画）
        assertTrue(strip(listRel).contains("verifyLines: List<VerifyRow> = emptyList()"))
    }

    @Test
    fun `J6_会话边界把结论一起清`() {
        val lines = strip(controllerRel).lines()
        val clears = lines.withIndex().filter { it.value.contains("verifyLines = VerifyLines.clear()") }.map { it.index }
        assertEquals("三处会话边界都清了", 3, clears.size)
        clears.forEach { idx ->
            val near = lines.drop((idx - 1).coerceAtLeast(0)).take(2) + lines.drop(idx + 1).take(1)
            assertTrue(
                "清台账的口子挨着本轮账（同一个口径，别各清各的）：${lines[idx]}",
                near.any { it.contains("resetRoundUsage()") || it.contains("queuedCount = 0") },
            )
        }
    }
}
