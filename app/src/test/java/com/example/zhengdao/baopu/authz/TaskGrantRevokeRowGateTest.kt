// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 Q2 补的**形状**门禁（源级，剥注释后比字面）。
//
// 为什么形状也要钉：她这条裁定的原话是「开工卡上**一行**『撤销本单』→ 确认 → 调撤销 API →
// 落账」，并且同一份裁定里写着「**不做**撤销列表／历史／批量——那是后面的事，别扩」。
// 行为套件能证明"撤得对"，证明不了"没长成第二张卡／没多一个入口／没绕开重算"。
// 这四件事一旦漂动，249 那条「不要新建第二张卡」和 L26 那条「不许按文本匹配撤销」就都虚了，
// 而漂动是看不出来的——下一个批次往卡面上再加一个按钮、或者图省事直接拿板上那份引用去比，
// 眼睛只会看到"它还是那一行"。
//
// 与 `TaskGrantWiringGateTest` 同一条纪律：**先剥注释**再比。本批注释里满是
// 「撤销本单」「不是第二张卡」这类原话，不剥注释的话，一条把行改成第二张卡的变异能靠注释骗绿。
package com.example.zhengdao.baopu.authz

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGrantRevokeRowGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    /** Q2 新增那一份（流程 + 行）。 */
    private fun row(): String =
        repoFile("src/main/java/com/example/zhengdao/baopu/authz/TaskGrantRevokeRow.kt").readText()

    /** 249 的授权段（本批**不许**碰的那一份）。 */
    private fun section(): String =
        repoFile("src/main/java/com/example/zhengdao/baopu/authz/TaskGrantSection.kt").readText()

    /** 开工卡（计划卡）。 */
    private fun card(): String =
        repoFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuPlanCard.kt").readText()

    /** 208 的账本（不在本批租约里）。 */
    private fun ledger(): String =
        repoFile("src/main/java/com/example/zhengdao/baopu/DecisionLedger.kt").readText()

    private fun mainSources(): List<File> {
        val dir = repoFile("src/main/java")
        require(dir.isDirectory) { "main 源目录没找到：${dir.absolutePath}" }
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    private fun codeOnly(src: String): String = src
        .split("\n")
        .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
        .joinToString("\n")

    private fun String.occurrences(needle: String): Int =
        if (needle.isEmpty()) 0 else Regex(Regex.escape(needle)).findAll(this).count()

    private fun keysOf(f: File): List<String> =
        Regex("""name="([^"]+)"""").findAll(f.readText()).map { it.groupValues[1] }.toList()

    private fun valueOf(f: File, key: String): String =
        Regex("""name="$key"[^>]*>([^<]*)<""").find(f.readText())?.groupValues?.get(1).orEmpty()

    // ── 一、范围：一行，不是第二张卡 ────────────────────────────────────────

    @Test
    fun 撤销这一行住在自己的文件里() {
        // 「新数据构造一律放新文件」：249 那五份零 IO 文件本批一个字节没动（Q2 也在这一格里）。
        assertTrue("缺 Q2 那一份新文件", repoFile("src/main/java/com/example/zhengdao/baopu/authz/TaskGrantRevokeRow.kt").isFile)
        val s = codeOnly(section())
        listOf("Revoke", "revoke", "撤销").forEach { bad ->
            assertFalse("249 的授权段里出现了 $bad：撤销不该长在它那一份上（它要留着零字节可对账）", s.contains(bad))
        }
    }

    @Test
    fun 行不是第二个按钮也不是新页面() {
        val r = codeOnly(row())
        listOf(
            "Button(", "Dialog", "Surface(", "BaopuActionContainer", "AlertDialog",
            "navigate(", "startActivity", "Intent(", "show(", "BottomSheet",
        ).forEach { bad ->
            assertFalse("撤销那一行里出现了 $bad：她说的是卡上一行字，不是一个新页面／第二张卡", r.contains(bad))
        }
        assertTrue("这一行必须是「整行可点的小字」（与展开／收起同一房型）",
            r.contains(".clickable") && r.contains("Text("))
    }

    @Test
    fun 卡面上只有一处撤销() {
        val c = codeOnly(card())
        assertEquals("开工卡上 `BaopuGrantRevokeRow(` 恰好一处（两处＝两处打断）",
            1, c.occurrences("BaopuGrantRevokeRow("))
        assertTrue("撤销行要接在授权段**之后**（先看见批的是什么，再看见怎么收回）",
            c.indexOf("BaopuPlanGrantSection(") in 0 until c.indexOf("BaopuGrantRevokeRow("))
    }

    // ── 二、口径：撤销只有一个入口，且只在确认那一下之后 ─────────────────────

    @Test
    fun 全仓只有一个撤销入口() {
        val hits = mainSources().flatMap { f ->
            val n = codeOnly(f.readText()).occurrences("TaskGrantRevokeFlow.revokeNow(")
            List(n) { f.name }
        }
        assertEquals("撤销入口只许长在卡面那一行上（多一处＝多一个口径）", listOf("TaskGrantRevokeRow.kt"), hits.sorted())
        assertEquals("卡面不许自己调底层撤销",
            0, codeOnly(card()).occurrences("TaskGrantRevokeFlow.revokeNow("))
    }

    @Test
    fun 撤销API只在构造层一处() {
        val total = mainSources().sumOf { codeOnly(it.readText()).occurrences("TaskGrantWire.revoke(") }
        assertEquals("`TaskGrantWire.revoke(` 的调用点全仓只有一处（就是这一行）", 1, total)
        listOf("TaskGrantRuntime.end(", "noteRevoke(", "TaskGrantWire.revoke(").forEach { bad ->
            assertFalse("开工卡里出现了 $bad：卡面只许摆这一行，不许直接收口", codeOnly(card()).contains(bad))
        }
    }

    @Test
    fun 确认之前不碰授权() {
        val r = codeOnly(row())
        assertTrue("第一下走的是唯一口径 `nextRevokeStage`", r.contains("stage = nextRevokeStage(stage)"))
        val iArmed = r.indexOf("if (stage == RevokeStage.ARMED) {")
        val iCall = r.indexOf("TaskGrantRevokeFlow.revokeNow(")
        assertTrue("两处点位都得在（$iArmed/$iCall）", iArmed >= 0 && iCall >= 0)
        assertTrue("动手那一下必须排在「已经是确认格」的判断里（第一下不许真的撤）", iArmed < iCall)
        assertEquals("三格状态：没点／已确认／已按到底", 3, RevokeStage.entries.size)
    }

    // ── 三、L26：交出去的是重算的三元组 ─────────────────────────────────────

    @Test
    fun 交出去的是重算的三元组() {
        val r = codeOnly(row())
        assertTrue("重算必须走开单同一个构造", r.contains("TaskGrantWire.bindingOf(approved.grant)"))
        // 拿引用比永远相等 ⇒ L26 成了摆设。这一格是整条 Q2 理由②（「三元组对不对，一按就知道」）能否成立的地方。
        assertFalse("不许直接拿板上那份引用去比（引用比引用恒等）", r.contains("approved.binding"))
    }

    @Test
    fun 卡面条件绑的是那张开着的单() {
        val c = codeOnly(card())
        assertTrue("这一行只在「这张卡就是那张开着的单」时出现",
            c.contains("TaskGrantBoard.openedGrantId() == grant.grantId"))
        assertTrue("行上摆的是板上那一份批准（不是重新算一张候选）", c.contains("TaskGrantBoard.currentOffer()"))
    }

    // ── 四、落账：用 208 既有的 kind，不删批准行，不牵动租约外那份 ────────────

    @Test
    fun 落账用的是208既有的回滚kind() {
        val r = codeOnly(row())
        assertEquals("回滚账只落一处", 1, r.occurrences("DecisionLedger.record("))
        assertTrue(r.contains("kind = DecisionKind.ROLLBACK_OF,"))
        assertTrue("回滚行必须带指向（`validate` 对 ROLLBACK_OF 的要求）", r.contains("rollbackOf = target"))
        assertTrue("指不出目标就不落账", r.contains("if (target.isBlank()) {"))
        assertFalse("不许删账（撤销收的是授权，不是那段历史）", r.contains("delete"))
    }

    @Test
    fun 为了这一行没有改208() {
        // 208 不在本批租约里：它不该知道"任务级授权"这件事的存在。
        val l = codeOnly(ledger())
        listOf("TaskGrant", "authz", "撤销本单").forEach { bad ->
            assertFalse("DecisionLedger.kt 里出现了 $bad：为这一格开口子＝把租约外的文件拖进来了", l.contains(bad))
        }
    }

    // ── 五、文案：只从资源里取，且中英两份都有这几把键 ────────────────────────

    @Test
    fun UI半区不硬编码文案() {
        val src = codeOnly(row())
        val marker = "internal fun BaopuGrantRevokeRow("
        assertTrue("找不到那一行的本体（被改名＝这条门禁失去对象）", src.contains(marker))
        // 本体与那句结论是这个文件的尾巴：从这儿到文末都不该有一个引号。
        val ui = src.substringAfter(marker)
        assertTrue("UI 那半边一个引号都不该有（话全在 strings_baopu_authz.xml）", !ui.contains('"'))
        assertTrue("UI 至少六处取资源", ui.occurrences("R.string.baopu_authz_revoke_") >= 6)
    }

    @Test
    fun 撤销的七把键中英都有() {
        val zh = repoFile("src/main/res/values/strings_baopu_authz.xml")
        val en = repoFile("src/main/res/values-en/strings_baopu_authz.xml")
        val need = listOf(
            "baopu_authz_revoke_this", "baopu_authz_revoke_confirm", "baopu_authz_revoke_cancel",
            "baopu_authz_revoke_hint", "baopu_authz_revoke_done", "baopu_authz_revoke_refused",
            "baopu_authz_revoke_no_ledger",
        )
        need.forEach { k ->
            assertTrue("中文缺 $k", keysOf(zh).contains(k))
            assertTrue("英文缺 $k", keysOf(en).contains(k))
        }
        val r = codeOnly(row())
        need.forEach { k -> assertTrue("行上没有引用 $k（键成了死字）", r.contains("R.string.$k")) }
        val fmt = "%1" + '$' + "s"
        // 「拦下」那句必须带得上差在哪一格（她理由②那句「一按就知道」在卡面上的落点）。
        assertTrue("中文那句缺占位符：" + valueOf(zh, "baopu_authz_revoke_refused"),
            valueOf(zh, "baopu_authz_revoke_refused").contains(fmt))
        assertTrue("英文那句缺占位符：" + valueOf(en, "baopu_authz_revoke_refused"),
            valueOf(en, "baopu_authz_revoke_refused").contains(fmt))
    }
}
