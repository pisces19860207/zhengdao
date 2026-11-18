// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 纹状体+岛叶+仿生门的**源码门禁**（判据的文字形态不变量）。
//
// 三组钉子：
//   ① 纹状体**不直写技能**：habit 包里不许出现任何技能写口/转正口——建议走许愿流水线，
//     转正永远人批。行为用例证明「现在没写」，本门禁证明「谁也写不了」。
//   ② **全仓唯一执行器**：不另造调度器/执行器——habit／insula／biogate 三个新包
//     不许有第二个 Runner 类、不许有线程/定时器/自旋（沿用 SubconsciousRunner 的
//     机会式触发；`SubconsciousRunner.run(` 全仓恰好一处）。
//   ③ 触发线在案：TriggerWiring 里岛叶/纹状体两行触发存在（收工即机会，不轮询）。
// 每条判据都带**变异负例**（判据本身能被绕过它就是假的）。
package com.example.zhengdao.baopu.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

internal class HabitSourceGateTest {

    private companion object {
        /** 三个新包的主源码目录（新增文件自动进扫描；门禁扫的是代码、注释剥掉）。 */
        val HABIT_DIR = "app/src/main/java/com/example/zhengdao/baopu/habit"
        val INSULA_DIR = "app/src/main/java/com/example/zhengdao/baopu/insula"
        val GATE_DIR = "app/src/main/java/com/example/zhengdao/baopu/biogate"

        /** 判据①：技能写口/转正口的禁词——一个都不许出现在纹状体包里。 */
        val FORBIDDEN_SKILL_WRITES = listOf(
            "write_skill",
            "SkillTools",
            "SkillAutoWrite",
            "SkillStore",
            "SkillPromote",
            "promoteBody",
            "canPromote",
            "PROMOTE_USES",
            "SkillDraftGovernor",
            "tryAutoWrite",
            "undoAutoWrite",
        )

        /** 判据②：调度构造的禁词（全仓唯一执行器——机会式触发，不轮询、不常驻）。 */
        val FORBIDDEN_SCHEDULING = listOf(
            "Thread(", "Timer(", "ScheduledExecutor", "Executors.", "runBlocking",
            "while (true)", "WorkManager", "CoroutineScope(", "delay(",
        )

        /** 判据①的正面钉子：唯一合法的去路是既有许愿流水线＋694 差读数消费。 */
        val REQUIRED_IN_HABIT = listOf("WishOutcome", "Striatum.rpe", "WishSubmit")
    }

    /** 从测试工作目录回仓库根找文件/目录（与 SubconsciousGateTest.repoFile 同房型）。 */
    private fun repoFile(rel: String): File {
        for (f in listOf(File(rel), File("app/$rel"), File("../$rel"))) {
            if (f.isFile || f.isDirectory) return f
        }
        error("找不到源文件：$rel（模块搬家了？门禁跟着改）")
    }

    /** 读源码并剥掉注释（注释里提到禁词不算数，只看代码）。 */
    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \t]*//.*$"), "")

    /** 门禁本体（给负例复用）：禁词一个都不许出现。 */
    private fun eatsNo(forbidden: List<String>, src: String): Boolean =
        forbidden.none { src.contains(it) }

    private fun dirSources(dir: String): List<Pair<String, String>> =
        repoFile(dir).listFiles().orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to code("$dir/${it.name}") }
            .sortedBy { it.first }

    // ── 判据①：不直写技能 ─────────────────────────────────────────────────

    /** 纹状体包里没有技能写口/转正口（新增文件自动进扫描）。 */
    @Test
    fun 源级_纹状体包不直写技能() {
        dirSources(HABIT_DIR).forEach { (name, src) ->
            assertTrue("$name 里出现技能写口：${FORBIDDEN_SKILL_WRITES.filter { src.contains(it) }}",
                eatsNo(FORBIDDEN_SKILL_WRITES, src))
        }
    }

    /** 正面钉子：纹状体包里必须有合法去路（许愿流水线）与差读数消费（694 器官）。 */
    @Test
    fun 源级_纹状体包走许愿流水线() {
        val all = dirSources(HABIT_DIR).joinToString("\n") { it.second }
        REQUIRED_IN_HABIT.forEach { needle ->
            assertTrue("纹状体包缺合法去路的引用：$needle", all.contains(needle))
        }
    }

    /** 变异负例：往纹状体包源码里塞一行转正调用 ⇒ 门禁必须红（判据咬得住）。 */
    @Test
    fun 负例_塞进转正调用门禁必须红() {
        val sample = dirSources(HABIT_DIR).first().second
        val poisoned = sample + "\nprivate fun sneak() = SkillStore.promoteBody(\"x\")\n"
        assertFalse("变异负例必须红（否则判据是假的）", eatsNo(FORBIDDEN_SKILL_WRITES, poisoned))
    }

    /** 变异负例：塞 write_skill 直写 ⇒ 门禁必须红。 */
    @Test
    fun 负例_塞进write_skill门禁必须红() {
        val sample = dirSources(HABIT_DIR).first().second
        val poisoned = sample + "\nprivate fun sneak2() = SkillTools.spec()\n"
        assertFalse(eatsNo(FORBIDDEN_SKILL_WRITES, poisoned))
    }

    // ── 判据②：全仓唯一执行器 ─────────────────────────────────────────────

    /** 三个新包不许有第二个 Runner、不许有调度构造（沿用既有执行器与机会式触发）。 */
    @Test
    fun 源级_新包不造调度器执行器() {
        (dirSources(HABIT_DIR) + dirSources(INSULA_DIR) + dirSources(GATE_DIR)).forEach { (name, src) ->
            assertFalse(
                "$name 不许出现 Runner 类或调度构造",
                Regex("(class|object)\\s+\\w*Runner").containsMatchIn(src),
            )
            assertTrue("$name 出现调度构造：${FORBIDDEN_SCHEDULING.filter { src.contains(it) }}",
                eatsNo(FORBIDDEN_SCHEDULING, src))
        }
    }

    /** 变异负例：在新包塞一个 Runner 类 ⇒ 门禁必须红（正则必须咬得住它）。 */
    @Test
    fun 负例_塞进Runner类门禁必须红() {
        val sample = dirSources(HABIT_DIR).first().second
        val poisoned = sample + "\ninternal object HabitRunner { fun run() = 0 }\n"
        assertTrue(
            "变异负例必须红（Runner 类必须被咬住，否则唯一执行器的判据是假的）",
            Regex("(class|object)\\s+\\w*Runner").containsMatchIn(poisoned),
        )
    }

    /** `SubconsciousRunner.run(` 全仓恰好一处（既有执行器仍是唯一入口；同 676 门禁口径）。 */
    @Test
    fun 源级_全仓唯一执行器入口仍是一处() {
        val root = repoFile("app/src/main/java")
        var count = 0
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                count += Regex("SubconsciousRunner\\.run\\(")
                    .findAll(f.readText(Charsets.UTF_8).normalizeCode()).count()
            }
        assertEquals("SubconsciousRunner.run( 全仓必须恰好一处", 1, count)
    }

    /** 剥注释的另一种入口（拿现成 File 读；给全仓扫描用，不走 repoFile 的相对路径拼接）。 */
    private fun String.normalizeCode(): String =
        replace("\r\n", "\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \t]*//.*$"), "")

    // ── 判据③：触发线在案 ─────────────────────────────────────────────────

    /** TriggerWiring 里两行触发存在（岛叶先调参、纹状体后消费；崩了走 safe 一行日志）。 */
    @Test
    fun 源级_收工触发两行在案() {
        val trigger = code("app/src/main/java/com/example/zhengdao/baopu/inspect/TriggerWiring.kt")
        assertTrue("岛叶触发线丢了", trigger.contains("safe(\"岛叶自调\") { insulaOnRunSettled(ctx, nowMs) }"))
        assertTrue("纹状体触发线丢了", trigger.contains("safe(\"纹状体固化\") { habitOnRunSettled(ctx, nowMs) }"))
    }

    /** 变异负例：删掉触发线 ⇒ 门禁必须红。 */
    @Test
    fun 负例_删触发线门禁必须红() {
        val trigger = code("app/src/main/java/com/example/zhengdao/baopu/inspect/TriggerWiring.kt")
        assertFalse(
            "变异负例必须红（否则触发线在案的判据是假的）",
            trigger.replace("insulaOnRunSettled(ctx, nowMs)", "stubSettled(ctx, nowMs)")
                .let { it.contains("safe(\"岛叶自调\") { insulaOnRunSettled(ctx, nowMs) }") },
        )
    }
}
