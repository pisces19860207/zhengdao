// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零六批接线 · 源码门禁：钉的是结构性事实，不是行为（RunEngine 在 JVM 里起不来，
// 引擎层的接线照本仓门禁范式用源码形状钉；同款范式见 QuietWiringGateTest）。
//
// 钉六件事：
//   G1 两个弹卡口（askUser／askGroup）都先问调解器，再问免打扰——压下的卡不进补问队列
//      （少问不是延后问；LOOP 的契约是"只给两条路"，不是过会儿再问）；
//   G2 askGroup 里账本短路口仍在调解器闸之前（同批已答过 ⇒ 根本没到"要不要问"那一步）；
//   G3 排序建议在闸之后、拆卡之前——压掉的组没有排序这回事；采纳只许 copy 重排（只给建议）；
//   G4 必问档的口子只有一个形状：viaUserChoice（用户点名的④路）——别处不许开豁免；
//   G5 判据文件的内容不可见是**结构性**的：不收 userText/modelText，零时钟零 IO 零 Android，
//      且一个字都碰不到右相的三态（ChancellorGate／UNVERIFIABLE）；
//   G6 调解器判据只经 MediatorWiring 一道门进引擎——引擎不许直呼 :policy 的 MediatorDecide。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediatorDecideGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    /** 去注释的源码（结构性断言用；免得门禁被改注释绕过）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val engine = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

    private fun engineCode(): String = codeOnly(read(engine))

    /** 从 [start] 那行起、到 [end] 首次出现为止的源码切片（含端点前的内容）。 */
    private fun slice(src: String, start: String, end: String): String {
        val s = src.indexOf(start)
        require(s >= 0) { "找不到切片起点：$start" }
        val e = src.indexOf(end, s + start.length)
        require(e > s) { "找不到切片终点：$end" }
        return src.substring(s, e)
    }

    @Test
    fun `G1_askUser先问调解器再问免打扰`() {
        val body = slice(engineCode(), "fun askUser(", "private fun askUserRaw(")
        val consult = body.indexOf("MediatorWiring.consultAsk(")
        val quiet = body.indexOf("quietDefer(")
        assertTrue("askUser 里没问调解器（406 接线丢了）", consult >= 0)
        assertTrue("调解器闸必须在免打扰闸之前：压下的卡不进补问队列", consult < quiet)
    }

    @Test
    fun `G2_askGroup账本短路口仍在调解器闸之前`() {
        val body = slice(engineCode(), "fun askGroup(", "private fun askGroupRaw(")
        val ledger = body.indexOf("AskBatchLedger.answers(")
        val consult = body.indexOf("MediatorWiring.consultAsk(")
        val quiet = body.indexOf("quietDefer(")
        assertTrue(ledger >= 0)
        assertTrue(consult >= 0)
        assertTrue("已答过的同批根本不该走到调解器闸", ledger < consult)
        assertTrue("调解器闸必须在免打扰闸之前（整组一起判）", consult < quiet)
    }

    @Test
    fun `G3_排序建议在闸之后拆卡之前_采纳只许重排`() {
        val body = slice(engineCode(), "fun askGroup(", "fun answerGroup(")
        val consult = body.indexOf("MediatorWiring.consultAsk(")
        val order = body.indexOf("MediatorWiring.orderAdvice(")
        val split = body.indexOf("AskGroupRules.split(")
        assertTrue(order >= 0)
        assertTrue("压掉的组没有排序这回事", consult < order)
        assertTrue("重排要在拆卡之前定下（拆卡序即卡序）", order < split)
        assertTrue("采纳只许 copy 重排（只给排序建议，不许改卡丢卡）", body.contains("copy(questions = order.order"))
    }

    @Test
    fun `G4_必问豁免只有一个形状_用户点名的四路`() {
        val src = engineCode()
        assertTrue(
            "askGroup 的必问参数要有缺省（既有调用点一个不改）",
            src.contains("viaUserChoice: Boolean = false"),
        )
        val exempt = src.indexOf("viaUserChoice = true")
        assertTrue("④路（用户点名「告诉我该怎么做」）必须豁免", exempt >= 0)
        // 豁免只许出现在④那一处：数一数。
        assertEquals("必问豁免的口子只许开一个", 1, Regex("viaUserChoice\\s*=\\s*true").findAll(src).count())
    }

    @Test
    fun `G5_判据文件内容不可见且碰不到右相三态`() {
        val src = codeOnly(PolicyPaths.read("MediatorDecide.kt"))
        assertFalse("判据不收用户原话（内容不可见是结构，不是自觉）", src.contains("userText"))
        assertFalse("判据不收模型原话", src.contains("modelText"))
        assertFalse("零时钟（now 由调用方给）", src.contains("System.currentTimeMillis"))
        assertFalse("零 IO", src.contains("java.io"))
        assertFalse("零 Android", src.contains("android."))
        // 铁律「不许改三态」：右相的验收闸在 :app 的 verify 包，:policy 的判据碰不到——
        // 这里连名字都不许出现，免得有人下一步把它 import 进来。
        assertFalse(src.contains("ChancellorGate"))
        assertFalse(src.contains("UNVERIFIABLE"))
        assertFalse(src.contains("VerifyVerdict"))
    }

    @Test
    fun `G6_引擎只经接线层一道门_不许直呼判据`() {
        val src = engineCode()
        assertFalse(
            "RunEngine 不许直接 import MediatorDecide（判据只能经 MediatorWiring 进来）",
            src.contains("MediatorDecide."),
        )
        val wiring = codeOnly(PolicyPaths.read("MediatorWiring.kt"))
        assertTrue("接线层要开放 consultAsk", wiring.contains("fun consultAsk("))
        assertTrue("接线层要开放 orderAdvice", wiring.contains("fun orderAdvice("))
        assertTrue("判据本体在 :policy，接线层只搬运", wiring.contains("MediatorDecide.shouldAsk("))
    }
}
