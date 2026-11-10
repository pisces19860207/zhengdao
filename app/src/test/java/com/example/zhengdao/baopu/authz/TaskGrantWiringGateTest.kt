// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P1：249 那六步**接没接上真实执行路径**的源级门禁（判据①＋负例 N4）。
//
// 为什么这一批的门禁要打在源码上而不是只打行为：249 交付时六步是「全绿但零调用」——
// 判定层自己测得再干净，管线不问它一句，生产上就还是逐次弹卡。所以本文件钉的是
// **接线现场**的四件事：
//   1. 六步在 `app/src/main` 里**有且只有一个**调用点，而且它在构造层（`TaskGrantWire`），
//      不在管线里（管线自己判＝出现第二个口径，249 §4 的分工就散了）；
//   2. 调用点在契约之后、`mustAsk` 之前（范围先谈完，「这一格要不要问」才轮到授权链）；
//   3. L26 的三元组排在六步**之前**（份都对不上，不配谈范围）；
//   4. 接管 208 那条通用账时，「非只读才落账」的嵌套形状与 `AUTO_ACT` 的写入
//      不许动（L4／L16 两条门禁钉的就是这些）。
//
// 负例 N4 就落在第 1、2 条上：把管线里那一行删掉，本文件当场红。
//
// 第五百五十七批去脆化：整句字面（构造参数／return／if 条件）全部换成
// 正则形态锚＋括号配对切片（GateBodies）——局部名、参数序、可见性、空白随便改；
// 注释/字符串过滤换到 CodeText 这把全仓统一的尺（旧版只滤整行注释，行尾注释与
// 字符串里的字面仍能骗绿）。稳定 API 的调用点计数（evaluate/decide/record 恰一处）
// 保留——那数的不是局部标识符，是「只有一个口径」这条不变量本身。
package com.example.zhengdao.baopu.authz

import com.example.zhengdao.CodeText
import com.example.zhengdao.GateBodies
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGrantWiringGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun guard(): String =
        repoFile("src/main/java/com/example/zhengdao/baopu/agent/GuardPipeline.kt").readText()

    private fun wire(): String =
        repoFile("src/main/java/com/example/zhengdao/baopu/authz/TaskGrantWire.kt").readText()

    private fun board(): String =
        repoFile("src/main/java/com/example/zhengdao/baopu/authz/TaskGrantBoard.kt").readText()

    private fun binding(): String =
        repoFile("src/main/java/com/example/zhengdao/baopu/authz/ApprovalBinding.kt").readText()

    /** `app/src/main` 下全部 Kotlin 源（判据①要的是"生产"，测试源不算）。 */
    private fun mainSources(): List<File> {
        val dir = repoFile("src/main/java")
        require(dir.isDirectory) { "main 源目录没找到：${dir.absolutePath}" }
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    /**
     * 只留代码（注释与字符串里的字面**不算**接线）。
     *
     * 为什么要这一层：本批注释里就写着「`DecisionLedger.record(` 的调用形状一个字节没动」
     * 这类原话——不剥注释，一条删除接线的变异能靠一句注释把门禁骗绿。
     * 557 起用 CodeText（行尾注释、字符串字面量一并清空；换行保留，行号仍对齐）。
     */
    private fun codeOnly(src: String): String = CodeText.codeOnly(src)

    private fun String.occurrences(needle: String): Int =
        if (needle.isEmpty()) 0 else Regex(Regex.escape(needle)).findAll(this).count()

    // ── 判据①／负例 N4：六步有真实调用点，而且只有一个 ────────────────────────

    @Test
    fun 六步在main里只有一个生产调用点() {
        val hits = mainSources().filter { codeOnly(it.readText()).contains("TaskGrantGate.evaluate(") }
        assertEquals(
            "六步的调用点必须**只在构造层**（管线直接判＝第二个口径；一处没有＝249 那种零调用交付）",
            listOf("TaskGrantWire.kt"),
            hits.map { it.name }.sorted(),
        )
        assertEquals(
            "调用点只许一处（多一处就多一个口径）",
            1,
            hits.sumOf { codeOnly(it.readText()).occurrences("TaskGrantGate.evaluate(") },
        )
    }

    @Test
    fun 管线通过注入口问授权链一次() {
        // N4 的另一半：删掉这一行（＝把六步重新变成"全绿但零调用"），这里当场红。
        assertEquals(
            "管线里 `grantWiring.decide(` 必须恰好一处",
            1,
            codeOnly(guard()).occurrences("grantWiring.decide("),
        )
        assertTrue(
            "接线口必须带默认值（存量调用点零改）；默认实现就是 TaskGrantWire",
            Regex("""grantWiring\s*:\s*GrantWiring\s*=\s*TaskGrantWire""")
                .containsMatchIn(codeOnly(guard())),
        )
    }

    @Test
    fun 管线不许自己判六步或三元组() {
        // 判定只在两处：TaskGrantGate（六步）与 ApprovalBindings（L26）。管线只按处置分派。
        val g = codeOnly(guard())
        listOf("TaskGrantGate.", "ApprovalBindings.", "ShadowSignal").forEach { bad ->
            assertFalse("GuardPipeline.kt 里出现了 $bad：判定不许长在管线上", g.contains(bad))
        }
    }

    @Test
    fun 接线位置在契约之后mustAsk之前() {
        val g = codeOnly(guard())
        val iContract = g.indexOf("contracts.noteAction(")
        val iLane = g.indexOf("grantWiring.decide(")
        // 弹卡决定＝「单条件 if 直接 return NeedsConfirm」那一格；条件名不钉（局部名随便改）。
        val iAsk = Regex("""if\s*\(\s*\w+\s*\)\s*return\s+Prepared\.Settled\(\s*Result\.NeedsConfirm\(""")
            .find(g)?.range?.first ?: -1
        assertTrue("三个点位都得找得到（找不到＝接线被人挪走了）：$iContract/$iLane/$iAsk",
            iContract >= 0 && iLane >= 0 && iAsk >= 0)
        assertTrue("契约先谈范围，授权链再谈要不要问", iContract < iLane)
        assertTrue("授权链必须在弹卡决定之前（排在后面就永远免不掉任何一张卡）", iLane < iAsk)
    }

    @Test
    fun 三元组排在六步之前() {
        val w = codeOnly(wire())
        val iBind = w.indexOf("ApprovalBindings.executeAllowed(")
        val iGate = w.indexOf("TaskGrantGate.evaluate(")
        assertTrue("两处都得在（$iBind/$iGate）", iBind >= 0 && iGate >= 0)
        assertTrue("L26 先判：份都对不上，不配谈范围", iBind < iGate)
    }

    // ── 判据②的口径：五种处置里只有两种改变今天的行为 ─────────────────────────

    @Test
    fun 只有放行与硬拒两种处置改变行为() {
        val g = codeOnly(guard())
        assertTrue("ALLOW 必须直接免卡（否则免卡分支是死的）",
            Regex("""WiringDisposition\.ALLOW\s*->\s*return\s+Prepared\.Ready""").containsMatchIn(g))
        assertTrue("REJECT 必须发生成动作都不做",
            Regex("""WiringDisposition\.REJECT\s*->""").containsMatchIn(g))
        assertTrue("其余三种一律 fall through（逐字走今天）",
            Regex("""else\s*->\s*Unit""").containsMatchIn(g))
        listOf("WiringDisposition.STOPPED ->", "WiringDisposition.UNAUTHORIZED ->", "WiringDisposition.NOT_APPLICABLE ->").forEach { bad ->
            assertFalse("管线里出现了 $bad：这一种处置不许有第二套动作", g.contains(bad))
        }
    }

    @Test
    fun P1生产不传影子() {
        // 第⑤步恒 `None` ⇒ 六步停在 NO_DATA ⇒「P1 一张卡也不会少弹」不是承诺，是这一格的事实。
        // 实参段用括号配对取（旧版钉「换行＋八格缩进＋)」的排版，重排格式就假红）。
        val calls = GateBodies.callArgs(guard(), "grantWiring.decide(")
        assertEquals("管线里 grantWiring.decide( 必须恰好一处", 1, calls.size)
        assertFalse("P1 不许在管线里喂影子：${calls[0]}",
            codeOnly(calls[0]).contains("shadow", ignoreCase = true))
        assertTrue("影子那一格必须留在注入接口上（负例 N6 打的就是它）",
            Regex("""shadow\s*:\s*ShadowSignal\s*=\s*ShadowSignal\.None""").containsMatchIn(codeOnly(wire())))
    }

    // ── 判据③：撤销按三元组，不许文本匹配 ───────────────────────────────────

    @Test
    fun 撤销收的是三元组不是文本() {
        val w = wire()
        // 只看 revoke 的**参数表**（签名段），不看函数体——体内声明一个同型局部变量
        // 不算「入口收三元组」；入口改成 String 就地红。
        val sig = GateBodies.callArgs(w, "fun revoke(")
        assertEquals("撤销入口应恰一处", 1, sig.size)
        assertTrue("撤销入口收 ApprovalBinding（换成字符串＝又回到文本匹配）",
            Regex("""target\s*:\s*ApprovalBinding""").containsMatchIn(codeOnly(sig[0])))
        assertTrue("撤销必须过 L26 那一闸", codeOnly(w).contains("ApprovalBindings.revokeAllowed("))
        // 板上不许出现"按文本找单"的形态：前缀、包含、下标三样都算。
        listOf("startsWith(", "contains(\"", "indexOf(").forEach { bad ->
            assertFalse("TaskGrantBoard.kt 里出现了 $bad：定位只许按三元组与 opId 相等", codeOnly(board()).contains(bad))
        }
    }

    @Test
    fun 绑定就是三格且比较是整份相等() {
        val block = codeOnly(binding()).substringAfter("internal data class ApprovalBinding(").substringBefore("\n)")
        val fields = Regex("""^\s*val (\w+):""", RegexOption.MULTILINE).findAll(block).map { it.groupValues[1] }.toList()
        assertEquals("三元组就是这三格（多一格＝把别的事实也绑进「同一件事」）", listOf("actionId", "argsHash", "objectsDigest"), fields)
        // 「整份相等」＝两个裸标识符直接 ==（不是 null 判断、不是逐字段比、不是 equals 文本比）。
        // 参数叫什么不钉；逐字段比较（a.x == b.y）顶不上这一格。
        val wholeEquality = Regex("""(?:^|[^\w.])\w+\s*==\s*(?!null\b)\w+(?![\w.])""")
        listOf("fun executeAllowed(" to "执行闸", "fun revokeAllowed(" to "撤销闸").forEach { (marker, name) ->
            val body = GateBodies.functionBody(binding(), marker)
            assertTrue("$name 不在了？", body != null)
            assertTrue("$name 必须整份相等", wholeEquality.containsMatchIn(codeOnly(body!!)))
        }
        val b = codeOnly(binding())
        assertFalse("不许出现近似匹配（忽略大小写／前缀／包含）",
            b.contains("ignoreCase") || b.contains("startsWith") || b.contains(".contains("))
    }

    // ── 红线（Q4 允许接管，但 208 的形状不许动） ──────────────────────────────

    @Test
    fun 接管通用账没有动208的字面() {
        val g = codeOnly(guard())
        // 「非只读才落账」那条 if：对象名不钉（spec 是局部名），只钉 `!….readOnly` 形态。
        val roIf = Regex("""if\s*\(\s*!\s*\w+\.readOnly\s*\)\s*\{""")
        val roBody = GateBodies.blockBody(g, roIf)
        assertTrue("L16 钉的那条 if 不在了", roBody != null)
        assertTrue("AUTO_ACT 的写入不许改", g.contains("DecisionKind.AUTO_ACT"))
        assertTrue("接管＝在那条 if 里再套一格否定 if，不是换掉它",
            Regex("""if\s*\(\s*!\s*\w+\s*\)\s*\{""").containsMatchIn(roBody!!))
        val iPending = g.indexOf("TaskGrantBoard.pendingFor(")
        val iIf = roIf.find(g)?.range?.first ?: -1
        val iRecord = g.indexOf("TaskGrantWire.recordApplied(")
        assertTrue("三个点位都得在（$iPending/$iIf/$iRecord）", iPending >= 0 && iIf >= 0 && iRecord >= 0)
        assertTrue("先问归属再决定通用账落不落", iPending < iIf)
        assertTrue("授权账在通用账那一格之后落（同一段里只可能有一条）", iIf < iRecord)
    }

    @Test
    fun 接管之后不补第二笔账() {
        val g = codeOnly(guard())
        assertTrue("落过的 opId 必须钉在板上（重试第二遍不许补通用账）",
            g.contains("TaskGrantBoard.markAccounted(") && g.contains("TaskGrantBoard.isAccounted("))
        assertEquals("管线里 AUTO_ACT 的记账只有一处（接管不是新造第二处）",
            1, g.occurrences("DecisionKind.AUTO_ACT"))
        assertEquals("管线里 208 的写入调用只有一处", 1, g.occurrences("DecisionLedger.record("))
    }

    // ── 249 纪律延伸到本批新增件 ────────────────────────────────────────────

    @Test
    fun 判定与内存板不带IO() {
        // 与 TaskGrantSourceGateTest 同一条纪律，只是对象换成 P1 新增的两份：
        // 碰盘只许发生在 TaskGrantWire.kt（构造层），否则"零 IO 判定层"这条线就从新文件漏出去。
        listOf(codeOnly(binding()) to "ApprovalBinding.kt", codeOnly(board()) to "TaskGrantBoard.kt").forEach { (src, name) ->
            listOf("java.io.File", "SharedPreferences", "readText", "writeText", "appendText", "java.nio.file", "RandomAccessFile")
                .forEach { bad ->
                    assertFalse("$name 里出现了 $bad：判定层与内存板不许自己做 IO", src.contains(bad))
                }
        }
        assertTrue("唯一碰盘的那一份必须显式声明它是构造层",
            Regex("""object\s+TaskGrantWire\s*:\s*GrantWiring""").containsMatchIn(codeOnly(wire())))
    }

    @Test
    fun 本批新增件不许自带卡壳() {
        // 249 那条「不要新建第二张卡」对本批同样有效：接线层只做事实与判定，一张卡都不画。
        listOf(wire() to "TaskGrantWire.kt", board() to "TaskGrantBoard.kt", binding() to "ApprovalBinding.kt").forEach { (src, name) ->
            listOf("BaopuActionContainer", "Surface(", "androidx.compose").forEach { bad ->
                assertFalse("$name 里出现了 $bad：授权接线层不许带 UI", src.contains(bad))
            }
        }
    }
}
