// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百批接线 · **源码门禁**：读侧接线钉的是结构性事实，不是行为——
// 御案列表／反思出卡在 JVM 里跑不起来（Compose＋Android 框架），接线照本仓门禁范式
// 用源码形状钉（同款范式见 QuietWiringGateTest／DecisionWiringGateTest）。
//
// 钉四件事：
//   G1 读不许改三态：LearnRead.kt 源码里不许出现任何写口（append/refreshTable/
//      enqueueOnly/recordDraftVeto/DecisionLedger）——铁律①的源级形态；
//   G2 样本地板钉死在 5：删地板＝薄样本驱动行为（铁律②）；
//   G3 御案列表三问都接线：排序/压卡/经验区三处调用缺一不可；
//   G4 反思候选出卡前必须过闸：压着的类不摆卡。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnReadGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun read(rel: String): String = repoFile(rel).readText()

    /** 去注释的源码（结构性断言用；免得门禁被改注释绕过）。 */
    private fun codeOnly(src: String): String =
        src.split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    private val learnRead = "src/main/java/com/example/zhengdao/baopu/LearnRead.kt"
    private val center = "src/main/java/com/example/zhengdao/ui/baopu/DecisionCenterScreen.kt"
    private val chat = "src/main/java/com/example/zhengdao/ui/baopu/BaopuChatController.kt"

    // ── G1：读不许改三态（铁律①的源级形态） ────────────────────────────────

    @Test
    fun `G1_LearnRead源码不许出现任何写口`() {
        val code = codeOnly(read(learnRead))
        val banned = listOf(
            ".append(", // 经验账写口（store.append）
            "refreshTable", // 经验表刷新（写侧 298 的活）
            "enqueueOnly", // 意见队列入队
            "recordDraftVeto", // 草稿查表记账
            "DecisionLedger.", // 208 台簿写口
            "SelfRulingLedger.note", // 自决单写口
        )
        for (b in banned) {
            assertFalse("读侧不许写三态：发现写口「$b」", code.contains(b))
        }
        assertTrue("IO 壳只许读全本（replay）", code.contains("LearnWiring.replay()"))
    }

    // ── G2：样本地板钉死在 5 ────────────────────────────────────────────────

    @Test
    fun `G2_样本地板钉死为5_薄样本不驱动行为`() {
        val code = codeOnly(read(learnRead))
        assertTrue(
            "样本 <5 不生效是派工单铁律：MIN_SAMPLES 必须钉死在 5",
            code.contains("const val MIN_SAMPLES = 5"),
        )
    }

    // ── G3：御案列表三问都接线 ──────────────────────────────────────────────

    @Test
    fun `G3_御案列表_排序压卡经验区三处都要接线`() {
        val code = codeOnly(read(center))
        assertTrue(
            "②排序必须走 LearnReadJudge.ordered（orderRulings/stats 的读侧口）",
            code.contains("LearnReadJudge.ordered("),
        )
        assertTrue(
            "①弹卡前必须问 shouldSuppress/mustAsk（LearnReadJudge.consult）",
            code.contains("LearnReadJudge.consult("),
        )
        assertTrue(
            "③证据区必须带最近同类经验（LearnReadJudge.recent）",
            code.contains("LearnReadJudge.recent("),
        )
    }

    // ── G4：反思候选出卡前过闸 ──────────────────────────────────────────────

    @Test
    fun `G4_反思候选出卡前必须过学习闸`() {
        val code = codeOnly(read(chat))
        assertTrue(
            "反思候选出卡前必须问学习（压着的类不摆卡）",
            code.contains("LearnRead.cardAllowed(\"反思:\${c.dedupKey}\")"),
        )
    }
}
