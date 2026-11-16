// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.subconscious

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 676 的**源码门禁**（判据⑥「不做动作、不改档」＋ 边界「共用执行器」的文本形态不变量）。
 *
 * 为什么按源码钉而不是只按行为钉：行为用例能证明「现在没做」，证明不了「谁也做不了」——
 * 执行器一旦长出对项目账的写调用，就是下意识伸手改档；本门禁让那一步先红。
 * 每条判据都带**变异负例**（判据本身能被绕过它就是假的）。
 */
class SubconsciousGateTest {

    private companion object {
        /** 本包的**主源码**目录（新增文件自动进扫描；门禁扫的是代码、注释剥掉）。 */
        val PACKAGE_DIR = "app/src/main/java/com/example/zhengdao/baopu/subconscious"

        /** 判据⑥：这些写口一个都不许出现在下意识包里（不做动作、不改档）。 */
        val FORBIDDEN_WRITES = listOf(
            "LedgerStore.append",
            "LedgerStore.moveTo",
            "LedgerStore.recordDone",
            "LedgerStore.archive",
            "LedgerStore.add(",
            "TodoStore.add",
            "TodoStore.confirm",
            "SupervisionDiary.append",
            "ledger_add",
            "todo_add",
        )

        /** 边界：不做推送/通知——只出页签行。 */
        val FORBIDDEN_PUSH = listOf(
            "NotificationManager",
            "NotificationCompat",
            "SafetyNotifier",
            "QuietCardGate(",
        )
    }

    /** 从测试工作目录回仓库根找文件/目录（与 MemoryTriStateGateTest.repoFile 同房型；目录也认）。 */
    private fun repoFile(rel: String): File {
        for (f in listOf(File(rel), File("app/$rel"), File("../$rel"))) {
            if (f.isFile || f.isDirectory) return f
        }
        error("找不到源文件：$rel（模块搬家了？门禁跟着改）")
    }

    /** 读源码并剥掉注释（注释里提到禁词不算数，只看代码——同房型）。 */
    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \t]*//.*$"), "")

    /** 门禁本体（给负例复用）：禁词一个都不许出现。 */
    private fun eatsNo(forbidden: List<String>, src: String): Boolean =
        forbidden.none { src.contains(it) }

    private fun packageSources(): List<Pair<String, String>> =
        repoFile(PACKAGE_DIR).listFiles().orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name to code("$PACKAGE_DIR/${it.name}") }
            .sortedBy { it.first }

    // ── 判据⑥：不做动作、不改档 ────────────────────────────────────────────

    @Test
    fun `门禁_下意识包没有任何账本或待办的写口`() {
        val sources = packageSources()
        assertTrue("包里应该至少有 4 个主源码件（门禁要扫的是真文件）", sources.size >= 4)
        sources.forEach { (name, src) ->
            val hits = FORBIDDEN_WRITES.filter { src.contains(it) }
            assertTrue(
                "$name 里出现了写口 $hits——下意识只出信号，不执行不改档（判据⑥）",
                eatsNo(FORBIDDEN_WRITES, src),
            )
        }
    }

    @Test
    fun `负例_往执行器里塞一个账本写口门禁必须红`() {
        val clean = packageSources().first { it.first == "SubconsciousRunner.kt" }.second
        assertTrue("前提：执行器本身是干净的", eatsNo(FORBIDDEN_WRITES, clean))
        assertFalse(
            "负例：执行器里调 LedgerStore.moveTo ⇒ 门禁必须红",
            eatsNo(FORBIDDEN_WRITES, clean + "\nprivate fun x() = LedgerStore.moveTo(null, \"\", LedgerState.FROZEN, 0L)\n"),
        )
        assertFalse(
            "负例：执行器里追加账行 ⇒ 门禁必须红",
            eatsNo(FORBIDDEN_WRITES, clean + "\nprivate fun y() = LedgerStore.append(null, null)\n"),
        )
    }

    // ── 边界：不做推送/通知，只在页签里 ─────────────────────────────────────

    @Test
    fun `门禁_下意识包不弹卡不推送`() {
        packageSources().forEach { (name, src) ->
            assertTrue(
                "$name 里出现了弹卡/推送口——回响只在页签里（本单边界）",
                eatsNo(FORBIDDEN_PUSH, src),
            )
        }
    }

    // ── 边界：执行器全仓唯一（给 677/678 复用，接口留好） ─────────────────────

    @Test
    fun `编排入口只有一个_SubconsciousRunner_run`() {
        val hits = packageSources().count { (_, src) -> src.contains("SubconsciousRunner.run(") }
        assertTrue("执行器入口只许在 SubconsciousRunner 一处定义（677/678 复用它，不另造）", hits == 1)
    }

    @Test
    fun `触发接线就位_收工一行进TriggerWiring`() {
        val wiring = code("app/src/main/java/com/example/zhengdao/baopu/inspect/TriggerWiring.kt")
        assertTrue(
            "TriggerWiring.onRunSettled 里必须有一行回响触发（删了就是暗收）",
            wiring.contains("subconsciousOnRunSettled"),
        )
    }

    @Test
    fun `免打扰快照口是只读的_授权写口仍然只有一条`() {
        val engine = code("app/src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        assertTrue(
            "RunEngine 要有 676 的只读快照口 quietGrantSnapshot",
            engine.contains("internal fun quietGrantSnapshot(): QuietGrant? = quietGrant"),
        )
        val snapshot = engine.substringAfter("internal fun quietGrantSnapshot")
        val body = snapshot.substringBefore("\n    }")
        assertFalse("快照口必须是一行只读表达式（不许长出赋值）", body.contains("quietGrant ="))
    }

    // ── typeLabel 的字面钉住（判据：灵感/想起/下意识；灵感归 677） ──────────────

    @Test
    fun `页签类型签字面_下意识与想起_灵感留给677`() {
        // 只看**元素值**不看注释：注释里写"灵感"是给 677 留条子的说明，不占键。
        val res = repoFile("app/src/main/res/values/strings_baopu_subconscious.xml")
            .readText(Charsets.UTF_8)
            .replace(Regex("(?s)<!--.*?-->"), "")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)^[ \t]*//.*$"), "")
        assertTrue(res.contains("decision_type_subconscious"))
        assertTrue(res.contains(">下意识<"))
        assertTrue(res.contains("decision_type_recall"))
        assertTrue(res.contains(">想起<"))
        assertFalse("「灵感」是 677 的键，本批不占", res.contains("灵感"))
        // 卡面要如实说「只出信号」：判据⑥对人可见的那一格
        assertTrue(res.contains("只出信号") || res.contains("没有执行任何动作"))
    }

    @Test
    fun `门禁清单防漂移_钉的文件必须真存在`() {
        assertTrue(repoFile(PACKAGE_DIR).isDirectory)
        assertTrue(repoFile("app/src/main/java/com/example/zhengdao/baopu/inspect/TriggerWiring.kt").isFile)
        assertTrue(repoFile("app/src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt").isFile)
    }
}
