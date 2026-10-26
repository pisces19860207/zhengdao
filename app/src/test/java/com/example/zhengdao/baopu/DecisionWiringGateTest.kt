// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零八批 §3 负例①⑥：**「有副作用但零记录」的路径必须红**。
//
// 做法：对十类 kind 的**唯一落点**做源码门禁——每个文件里必须同时出现
//   ① `DecisionKind.<那一类>`（写的是那一类，不是别的）
//   ② `DecisionLedger.record`（真的落了账）
// 少一条就红。这是「哪天有人把 record 那一行删掉、或者把副作用的写口换个地方」
// 能当场发现的唯一机检手段（真跑盘的成本在 CI 上不可接受）。
//
// 另加两条硬闸：
//   · `BaopuDb.VERSION` 不许动（记录走既有通道，不动库结构）；
//   · 十类的清单与 `DecisionKind.REQUIRED` 一字不差（自造 kind 就红）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DecisionWiringGateTest {

    private val src = File("src/main/java/com/example/zhengdao")

    private fun text(rel: String): String {
        val f = File(src, rel)
        assertTrue("文件不见了：${f.absolutePath}（门禁按它找接线点）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /**
     * 每一类在落点文件里的**可检索证据**。
     *
     * 为什么不是一律查 `DecisionKind.X`：设置页那份源码被 `BaopuSkillCategoryTest.T5`
     * 钉着「整份文件不许出现 kind」（分类不许进正文头）——所以 `read_scope` 走专用落点
     * `DecisionLedger.recordReadScope(`，这里就查那一次调用。门禁更准，不是更松：
     * 它查的是「这一类的那次调用在不在」，而不是「文件里提没提这个词」。
     */
    private fun markerOf(kind: DecisionKind): String = when (kind) {
        DecisionKind.READ_SCOPE -> "DecisionLedger.recordReadScope("
        // 带 `kind = ` 前缀：只查那个**写入口传的**是哪一类。不这样钉的话，
        // 同一个文件里「判读某条记录属于哪类」的等值比较（`it.kind == DecisionKind.X`）
        // 会把接线被摘掉的变异放过去（2026-10-26 变异 M1 实证）。
        else -> "kind = DecisionKind.${kind.name}"
    }

    /** 落点文件正文（把全限定名前缀去掉，好让 `com.…baopu.DecisionKind.X` 也能匹配）。 */
    private fun bodyOf(rel: String): String = text(rel).replace("com.example.zhengdao.baopu.", "")

    /** (kind, 落点文件)：仍会**产生新行**的九类，一类一行。
     *
     * 第二百二十九批起 `READ_SCOPE` 退出本表：读侧开关随 229 删除（授权即全盘），
     * `read_scope` 新行**不再产生**——但 kind 本身保留（老账本要读得出来），
     * 那半边由下面的 `READ_SCOPE_229` 测试钉住。
     */
    private val sites: List<Pair<DecisionKind, String>> = listOf(
        DecisionKind.SKILL_PROMOTE to "baopu/SkillPromote.kt",
        DecisionKind.SKILL_AUTO_WRITE to "baopu/SkillAudit.kt",
        DecisionKind.SKILL_RETIRE to "ui/baopu/SkillLibraryScreen.kt",
        DecisionKind.SKILL_DRAFT_RETIRE to "ui/baopu/SkillLibraryScreen.kt",
        DecisionKind.ARTIFACT_DELETE to "ui/baopu/BaopuArtifactPicker.kt",
        DecisionKind.DIARY_MAINTAIN to "baopu/DiaryStore.kt",
        DecisionKind.DIARY_PURGE to "baopu/DiaryStore.kt",
        DecisionKind.EVAL_VERDICT to "ui/baopu/BaopuChatController.kt",
        DecisionKind.AUTO_ACT to "baopu/agent/GuardPipeline.kt",
    )

    @Test
    fun `W1_负例_九类在产kind每一类都有落点_且落点真的调了record`() {
        assertEquals("派工单 §1 六类 + §2.7 四类，减去 229 停产的 read_scope", 9, sites.size)
        assertEquals(
            "在产九类不许自行增删（REQUIRED 里另外那个 READ_SCOPE 由 229 测试单独钉）",
            DecisionKind.REQUIRED.toSet() - DecisionKind.READ_SCOPE,
            sites.map { it.first }.toSet(),
        )
        sites.forEach { (kind, rel) ->
            val body = bodyOf(rel)
            assertTrue(
                "$rel 里没写 ${markerOf(kind)}（这一类没接线）",
                body.contains(markerOf(kind)),
            )
            assertTrue(
                "$rel 里没有 DecisionLedger.record（有副作用却零记录）",
                body.contains("DecisionLedger.record"),
            )
        }
    }

    @Test
    fun `W6_负例_read_scope随229停产_kind保留_落点零调用`() {
        // 第二百二十九批：设置页开关整卡删除 ⇒ read_scope **新行不再产生**；
        // 但 `DecisionKind.READ_SCOPE` 与 `recordReadScope` 都保留——老账本里的历史行
        // 还要读得出来、还能在决策中心渲染（删 kind ＝ 老账变哑账，派工单 §3 负例点名）。
        assertTrue("kind 必须还在 REQUIRED 里（保留给老账本）", DecisionKind.READ_SCOPE in DecisionKind.REQUIRED.toSet())
        // `DecisionLedger.recordReadScope(` 是**调用点**的形状（定义处是 `fun recordReadScope(`，
        // 不带前缀）——全仓零调用就是「不再产生」这句话的机检形态。
        val callers = src.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .count { f -> f.readText(Charsets.UTF_8).contains("DecisionLedger.recordReadScope(") }
        assertEquals("read_scope 的调用点必须为零（停产，不是删账）", 0, callers)
        // 停产的来路要说得清：设置页那处调用随开关卡一起没了（文件里不许再出现写口）。
        val settings = text("ui/baopu/BaopuSettingsScreen.kt")
        assertTrue("设置页不再调 recordReadScope", !settings.contains("recordReadScope"))
    }

    @Test
    fun `W2_记录落点装配在运行时那一处_不是各写各的`() {
        val runtime = text("baopu/BaopuRuntime.kt")
        assertTrue("落点必须由装配层注入", runtime.contains("DecisionLedger.install("))
        assertTrue("落点＝抱朴记忆根", runtime.contains("BaopuKernel.Data.DIR_GLOBAL_MEMORY"))
        // 全仓只许有装配层这一处 install（别处再装一次就是两本账）
        val callers = src.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .count { f -> f.readText(Charsets.UTF_8).contains("DecisionLedger.install(") }
        assertEquals("install 只许一处", 1, callers)
    }

    @Test
    fun `W3_负例_不许动库版本`() {
        val db = text("baopu/BaopuDb.kt")
        assertTrue("BaopuDb.VERSION 被改了（记录走既有通道，本批一个字节都不许动库结构）", db.contains("const val VERSION = 19"))
    }

    @Test
    fun `W4_记录页只有一套UI_不新开页面`() {
        val screen = text("ui/baopu/DecisionCenterScreen.kt")
        assertTrue("记录页要在既有四 tab 里做", screen.contains("DecisionTab.HISTORY"))
        assertTrue("分组卡渲染必须有", screen.contains("foldDecisionRecords("))
        assertTrue("待抽检组要有两个结论按钮", screen.contains("decision_record_judge_wrong"))
        // 不许出现第二个决策中心页面
        val centers = src.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .count { f -> f.readText(Charsets.UTF_8).contains("fun DecisionCenterScreen(") }
        assertEquals("决策中心只许一个入口", 1, centers)
    }

    @Test
    fun `W5_十类的回滚含义与抽检档写死在枚举里`() {
        // 不可回滚的三类（真删、评测结论、回滚行自己）
        listOf(DecisionKind.DIARY_PURGE, DecisionKind.EVAL_VERDICT, DecisionKind.ROLLBACK_OF)
            .forEach { assertTrue("${it.wire} 不许标可回滚", !it.reversible) }
        // 必抽两类
        assertEquals(DecisionAudit.ALWAYS, DecisionKind.DIARY_PURGE.audit)
        assertEquals(DecisionAudit.ALWAYS, DecisionKind.SKILL_RETIRE.audit)
        // 周配额两类
        assertEquals(DecisionAudit.WEEKLY, DecisionKind.SKILL_DRAFT_RETIRE.audit)
        assertEquals(DecisionAudit.WEEKLY, DecisionKind.DIARY_MAINTAIN.audit)
        // 其余不抽
        assertEquals(DecisionAudit.NONE, DecisionKind.SKILL_PROMOTE.audit)
        assertEquals(DecisionAudit.NONE, DecisionKind.ARTIFACT_DELETE.audit)
    }
}
