// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十三批 · 仿生件**三件套门禁**（M3 收口：每个仿生件交付必须三件齐备，缺一不收）。
//
// 三件套是什么（派工单 §一）：
//   ① 可测差异——同输入下有无该件行为不同，且被测试钉住（钉法在各件自己的测试类，
//      本门禁钉的是"九件清单不漂移"：少一件、改名躲闸，先红在这里）；
//   ② 不越权声明——源码**声明**"本件无判定权"（不产生 RiskDecision/PROCEED/REJECT、
//      不改档、不写权威账），再由本门禁**断言**声明在、且剥注释后的代码里
//      判定词/档写/权威账写一个都不出现；
//   ③ 失败兜底——接线处必须有"崩了只记一行"的兜底壳，件炸了主链路照常走
//      （负例：把兜底壳拆掉 ⇒ 红——失败导致主链路变化就是这个红）。
//
// 房型照 677/678 的源码门禁：判别器先喂**故意违规**的样本自证咬得住，再对真实源码
// 断言干净（防恒真绿灯）；剥注释后扫代码——注释里说"不做什么"不算证据。
package com.example.zhengdao.baopu.bio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BioTripleGateTest {

    private companion object {
        const val SRC_ROOT = "app/src/main/java/com/example/zhengdao/baopu"

        /** 九件闭集（六体＋下意识/灵感/重放）；每件的主判据文件放首位——声明钉在它头上。 */
        val PIECES: Map<String, List<String>> = linkedMapOf(
            "杏仁核" to listOf("bio/Amygdala.kt"),
            "海马" to listOf("bio/Hippocampus.kt"),
            "前额叶" to listOf("bio/PrefrontalCortex.kt"),
            "纹状体" to listOf("bio/Striatum.kt"),
            "岛叶" to listOf("bio/Insula.kt"),
            "丘脑" to listOf("bio/Thalamus.kt"),
            "下意识" to listOf(
                "subconscious/SubconsciousRunner.kt",
                "subconscious/SubconsciousEcho.kt",
                "subconscious/SubconsciousRowStore.kt",
                "subconscious/SubconsciousWiring.kt",
            ),
            "灵感" to listOf(
                "subconscious/InspirationRecall.kt",
                "subconscious/InspirationStore.kt",
                "subconscious/InspirationSwitch.kt",
            ),
            "重放" to listOf(
                "subconscious/HippocampalReplay.kt",
                "subconscious/ReplayStores.kt",
                "subconscious/ReplayWiring.kt",
            ),
        )

        const val DECLARATION = "本件无判定权"

        /**
         * 三件接线件（下意识/灵感/重放）的禁区词——剥注释后一个都不许出现。
         * 判定词取大写整词（SaveResult.Rejected 是拒收不是判定，不误伤）；
         * 档写/权威账写/记忆写按调用形状认，不认单词（照 676 SubconsciousGateTest 口径）。
         */
        val WIRED_FORBIDDEN = listOf(
            "RiskDecision", "PROCEED", "REJECT",
            "AskTier", "AskGovernance", "noteQuietGrant",
            "LedgerStore.append", "LedgerStore.moveTo", "LedgerStore.recordDone",
            "LedgerStore.archive", "TodoStore.add", "TodoStore.confirm",
            "SupervisionDiary.append", "MemoryEntry(",
            "execSQL", "INSERT INTO", "DELETE FROM",
        )
    }

    /** 从测试工作目录回仓库根找文件（照 SubconsciousGateTest.repoFile 同房型）。 */
    private fun repoFile(rel: String): File {
        for (f in listOf(File(rel), File("app/$rel"), File("../$rel"))) {
            if (f.isFile) return f
        }
        error("找不到源文件：$rel（模块搬家了？门禁跟着改）")
    }

    /** 只删整行注释与块注释（字符串里的内容保留——照 677 同款简化版）。 */
    private fun stripComments(src: String): String = src
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .lines()
        .filterNot { it.trimStart().startsWith("//") }
        .joinToString("\n")

    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .let(::stripComments)

    // ── ②声明：九件的主判据文件必须带着"本件无判定权"的声明行 ──────────────────

    @Test
    fun `九件声明都在_主判据文件带头`() {
        for ((piece, files) in PIECES) {
            val primary = "$SRC_ROOT/${files.first()}"
            assertTrue(
                "$piece 的声明不在 $primary——三件套②要求源码先声明",
                repoFile(primary).readText(Charsets.UTF_8).contains(DECLARATION),
            )
        }
    }

    @Test
    fun `负例_拆掉声明_门禁必须红`() {
        val primary = "$SRC_ROOT/${PIECES.values.first().first()}"
        val with = repoFile(primary).readText(Charsets.UTF_8)
        val without = with.replace(DECLARATION, "")
        assertTrue("前提：声明本在", with.contains(DECLARATION))
        assertFalse("拆掉声明 ⇒ 断言必须红", without.contains(DECLARATION))
    }

    // ── ②断言：三件接线件剥注释后零判定词/零档写/零权威账写 ────────────────────

    @Test
    fun `三件接线件_代码零判定零档写零权威账写`() {
        val wired = PIECES.filterKeys { it in setOf("下意识", "灵感", "重放") }
        for ((piece, files) in wired) {
            for (rel in files) {
                val src = code("$SRC_ROOT/$rel")
                val hits = WIRED_FORBIDDEN.filter { it in src }
                assertTrue(
                    "$piece/$rel 代码里出现禁区词 $hits——只出信号不改账（三件套②）",
                    hits.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `负例_往接线件里塞判定词或写口_门禁必须红`() {
        val runner = code("$SRC_ROOT/subconscious/SubconsciousRunner.kt")
        assertTrue("前提：执行器代码是干净的", WIRED_FORBIDDEN.none { it in runner })
        // 塞判定：产生 PROCEED 结论 ⇒ 红。
        assertFalse(
            "判定词必须咬住",
            WIRED_FORBIDDEN.none { it in runner + "\nprivate fun x() = \"PROCEED\"\n" },
        )
        // 塞档写：AskTier 引用 ⇒ 红。
        assertFalse(
            "档写必须咬住",
            WIRED_FORBIDDEN.none { it in runner + "\nprivate fun y(t: AskTier) = t\n" },
        )
        // 塞账写：LedgerStore.append ⇒ 红。
        assertFalse(
            "权威账写必须咬住",
            WIRED_FORBIDDEN.none { it in runner + "\nprivate fun z() = LedgerStore.append(null, null)\n" },
        )
        // 塞记忆写：MemoryEntry 构造 ⇒ 红。
        assertFalse(
            "记忆写必须咬住",
            WIRED_FORBIDDEN.none { it in runner + "\nprivate fun m() = MemoryEntry(id = \"x\")\n" },
        )
    }

    // ── ③兜底：三件接线处的"崩了只记一行"壳必须在——拆壳＝失败搅动主链路 ⇒ 红 ──

    @Test
    fun `兜底壳三处都在_件炸了只记一行`() {
        // 下意识：收工触发走 TriggerWiring.safe——回响炸了不许把会话收尾带下去（65/89 口径）。
        val wiring = repoFile("$SRC_ROOT/inspect/TriggerWiring.kt").readText(Charsets.UTF_8)
        assertTrue(
            "回响触发必须包在 safe(\"回响\") 里（拆壳＝回响炸了带崩收工路径）",
            wiring.contains("safe(\"回响\") { subconsciousOnRunSettled"),
        )
        // 灵感：页签刷新整轮 runCatching——L0 读不到/账写不进 ⇒ 只记一行，页签照常。
        val controller = repoFile("$SRC_ROOT/../ui/baopu/BaopuChatController.kt")
            .readText(Charsets.UTF_8)
        assertTrue("灵感刷新必须带 runCatching 兜底壳", controller.contains("runCatching"))
        assertTrue("灵感刷新失败必须只记一行（不静默、不抛）", controller.contains("灵感唤醒没跑成"))
        // 重放：页签刷新与灭屏触发两处兜底壳——重放炸了不许搅动控制器/广播路径。
        assertTrue("重放刷新失败必须只记一行", controller.contains("重放没跑成"))
        assertTrue("重放撤销失败必须给人话（getOrDefault）", controller.contains("撤销没跑成"))
        val replayWiring = code("$SRC_ROOT/subconscious/ReplayWiring.kt")
        assertTrue(
            "灭屏触发必须包在 runCatching 里（拆壳＝重放炸了带崩广播线程）",
            replayWiring.contains("runCatching") && replayWiring.contains("重放灭屏触发没跑成"),
        )
    }

    @Test
    fun `负例_拆掉兜底壳_门禁必须红`() {
        // 拆 safe 壳：回响裸调 ⇒ 红（失败导致主链路变化就是这个红）。
        val wiring = repoFile("$SRC_ROOT/inspect/TriggerWiring.kt").readText(Charsets.UTF_8)
        val unwrapped = wiring.replace("safe(\"回响\") { subconsciousOnRunSettled", "subconsciousOnRunSettled")
        assertFalse(
            "拆掉 safe 壳 ⇒ 断言必须红",
            unwrapped.contains("safe(\"回响\") { subconsciousOnRunSettled"),
        )
        // 拆灵感兜底：失败日志行删掉 ⇒ "只记一行"的断言失败。
        val controller = repoFile("$SRC_ROOT/../ui/baopu/BaopuChatController.kt")
            .readText(Charsets.UTF_8)
        assertFalse(
            "拆掉失败日志 ⇒ 断言必须红",
            controller.replace("灵感唤醒没跑成", "").contains("灵感唤醒没跑成"),
        )
    }
}
