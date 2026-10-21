// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百零九批 §3 的**源码门禁**（影子模式 v1）。每条门禁都配一段负例样本——
// 门禁必须能红，否则它只是句好看的话。派工单红线逐条对应：
//   S1 `L3_AUTO_LOW_RISK` 保持 false，全 main 没有任何开闸写法；
//   S2 不另造第二套策略：EvolutionPolicy 的生产构造点仍然只有决策口一处；
//   S3–S7 影子零副作用：不 apply／不回滚／不改候选／不写库／不定时／不接 UI；
//   S8–S10 影子必须真的有生产调用点（防又做成死代码），且接线不动 99 的真决策链；
//   S11–S14 影子走独立账本，不挤 88 的审计行、不加表、不升库版本、正文不进账。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvolutionShadowGateTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 目录版：单测的工作目录在不同 Gradle 版本下会换（`app/` 或仓库根），两种都兜住。 */
    private fun repoDir(rel: String): File = File(rel).let { if (it.isDirectory) it else File("app/$rel") }

    private fun raw(rel: String): String =
        repoFile(rel).readText(Charsets.UTF_8).replace("\r\n", "\n")

    private val mainRoot = "src/main/java/com/example/zhengdao"

    private val shadow = raw("$mainRoot/baopu/EvolutionShadow.kt")
    private val policy = raw("$mainRoot/baopu/EvolutionPolicy.kt")
    private val decision = raw("$mainRoot/baopu/EvolutionDecision.kt")
    private val audit = raw("$mainRoot/baopu/EvolutionAudit.kt")
    private val inputs = raw("$mainRoot/baopu/EvolutionInputs.kt")
    private val controller = raw("$mainRoot/ui/baopu/BaopuChatController.kt")
    private val db = raw("$mainRoot/baopu/BaopuDb.kt")

    /** 收尾判定链那一段（与 99 的门禁同一个区间口径，一字不差地照搬）。 */
    private val judgeRegion: String
        get() = controller
            .substringAfter("private fun judgeProposalCandidates()")
            .substringBefore("候选目标技能在盘上的文件")

    private val allMain: List<File> by lazy {
        repoDir(mainRoot).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun allMainTexts(): List<Pair<String, String>> = allMain.map {
        it.path.replace('\\', '/') to it.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    /** main 下引用某 token 的 .kt 文件（排除定义所在文件；定义不算「有人用」）。 */
    private fun mainRefs(token: String, excludeDefSuffix: String): List<String> {
        val root = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText(Charsets.UTF_8).contains(token) }
            .map { it.toRelativeString(root.parentFile).replace('\\', '/') }
            .filterNot { it.endsWith(excludeDefSuffix) }
            .sorted()
            .toList()
    }

    // ── 门禁函数（负例直接喂字符串进去）────────────────────────────────────

    /** S3：影子文件的「零副作用」门禁——名单照 88/99 那份，一个词都没松。 */
    private fun noDirectWrite(src: String): Boolean = listOf(
        "appendNew(",
        "applyProposal(",
        "SkillStore.apply(",
        "SkillStore.plan(",
        "writeLearnedSkill(",
        ".restore(",
        "PersonaFiles.",
        "Files.move",
        "SQLite",
        "execSQL",
        "CREATE TABLE",
    ).none { src.contains(it) }

    /** S1：开闸写法——常量改 true、显式传 true，两种都算。 */
    private fun noSwitchFlip(src: String): Boolean =
        listOf("L3_AUTO_LOW_RISK = true", "autoLowRiskEnabled = true", "autoLowRiskEnabled =  true")
            .none { src.contains(it) }

    /** S4：影子不许自己碰开关——不读环境变量、不设 setter、连参数名都不出现。 */
    private fun noSwitchTouch(src: String): Boolean = listOf(
        "autoLowRiskEnabled",
        "System.getenv",
        "getenv(",
        "SharedPreferences",
        "preferences",
        "fun setAuto",
        "var autoLowRisk",
    ).none { src.contains(it) }

    /** S5：读数只许一条 SELECT，写库字样一个都不许出现。 */
    private fun readOnlySql(src: String): Boolean = listOf("INSERT", "UPDATE", "DELETE", "ALTER", "DROP")
        .none { src.uppercase().contains(it) } && src.contains("SELECT")

    /** S6：不自己起来干活——没有定时、没有线程、没有新权限。 */
    private fun noScheduling(src: String): Boolean = listOf(
        "WorkManager",
        "AlarmManager",
        "JobScheduler",
        "setRepeating",
        "PeriodicWorkRequest",
        "Timer(",
        "Thread(",
        "Thread {",
        "Executors.",
        "java.util.concurrent",
    ).none { src.contains(it) }

    /** S7：派工单 §2「不做 UI」。 */
    private fun noUi(src: String): Boolean = listOf(
        "androidx.compose",
        "mutableStateOf",
        "Button",
        "Screen",
        "ui.baopu",
        "Modifier",
    ).none { src.contains(it) }

    // ── S1／S2 红线：开关与策略唯一性 ──────────────────────────────────────

    @Test
    fun `S1_L3常量仍是false`() {
        assertTrue("88 的常量必须原样不动", policy.contains("const val L3_AUTO_LOW_RISK = false"))
        val offenders = allMainTexts().filter { !noSwitchFlip(it.second) }.map { it.first }
        assertTrue("全 main 不许出现开闸写法：$offenders", offenders.isEmpty())
        // 负例：常量被改、调用点被传 true，两种都得红
        assertFalse("负例：常量改 true 必须红", noSwitchFlip("const val L3_AUTO_LOW_RISK = true"))
        assertFalse(
            "负例：影子偷偷传 true 必须红",
            noSwitchFlip("EvolutionPolicy(autoLowRiskEnabled = true).judge(input)"),
        )
    }

    @Test
    fun `S2_影子不另造第二套策略_构造点仍只决策口一处`() {
        assertFalse("影子文件不许重算阈值", shadow.contains("evalFloor"))
        assertFalse("影子文件不许重算效果判据", shadow.contains("effectVerdictOf"))
        val sites = policyConstructionSites(allMainTexts())
        assertEquals("生产只许决策口造默认策略，本批没加第二处：$sites", 1, sites.size)
        assertTrue("那一处就是 EvolutionDecision.kt", sites.single().endsWith("baopu/EvolutionDecision.kt"))
        // 负例：影子自己 new 一个策略（还传 true）必须被这条门禁抓到
        val bad = policyConstructionSites(
            listOf("baopu/EvolutionShadow.kt" to "val p = EvolutionPolicy(autoLowRiskEnabled = true)"),
        )
        assertEquals("负例：影子自建策略实例必须红", listOf("baopu/EvolutionShadow.kt"), bad)
    }

    /** 谁在 main 里构造策略实例（类声明自己不算）。99 的门禁口径，一字不改地复用。 */
    private fun policyConstructionSites(texts: List<Pair<String, String>>): List<String> = texts
        .filter { (_, text) -> Regex("\\bEvolutionPolicy\\(").containsMatchIn(text) }
        .filter { (path, _) -> !path.endsWith("EvolutionPolicy.kt") }
        .map { it.first }
        .sorted()

    @Test
    fun `S2b_影子判据的输入就是决策口那份`() {
        assertTrue("影子必须吃 88 的决策输出", shadow.contains("decision: EvolutionDecision"))
        assertTrue("影子必须吃 88 的输入", shadow.contains("input: EvolutionDecisionInput"))
        assertTrue("影子按原因链核对硬条件，不重算证据", shadow.contains("SHADOW_HARD_CODES"))
        for (code in listOf(
            "SELF_CHECK_PASSED",
            "EVAL_PASSED",
            "EFFECT_POSITIVE",
            "ROLLBACK_AVAILABLE",
        )) {
            assertTrue("四条码缺一不可：$code", shadow.contains("EvolutionReasonCode.$code"))
        }
    }

    // ── S3–S7 零副作用 ────────────────────────────────────────────────────

    @Test
    fun `S3_影子文件零副作用_负例必须红`() {
        assertTrue("真源码必须过门禁", noDirectWrite(shadow))
        assertTrue("真源码必须过门禁（决策口那四个文件也没被本批改）", noDirectWrite(decision + policy + audit + inputs))
        assertFalse("负例：影子触发 apply 必须红", noDirectWrite("SkillStore.apply(plan, pf, text)"))
        assertFalse("负例：影子执行回滚必须红", noDirectWrite("snapshot.restore(backup)"))
        assertFalse("负例：影子改候选状态必须红", noDirectWrite("store.appendNew(listOf(candidate))"))
        assertFalse("负例：影子写人设必须红", noDirectWrite("PersonaFiles.applyProposal(proposal)"))
    }

    @Test
    fun `S4_影子不碰开关不读环境不设setter_负例必须红`() {
        assertTrue("影子文件一个字都不许提开关参数名", noSwitchTouch(shadow))
        assertTrue("开关只以**常量读取**的方式进账本", shadow.contains("EvolutionPolicy.L3_AUTO_LOW_RISK"))
        assertFalse("负例：影子读环境变量必须红", noSwitchTouch("val on = System.getenv(\"L3_AUTO_LOW_RISK\")"))
        assertFalse("负例：影子加 setter 必须红", noSwitchTouch("fun setAutoLowRisk(v: Boolean)"))
        assertFalse("负例：影子接设置页必须红", noSwitchTouch("preferences.getBoolean(\"l3\", false)"))
    }

    @Test
    fun `S5_影子读人工留痕只有SELECT_负例必须红`() {
        val stmtLine = EvolutionShadowHuman.decisionsStmt().sql
        assertTrue("读数必须过只读门禁", readOnlySql(stmtLine))
        assertTrue("读数只碰 skill_event 的 decision 行", stmtLine.contains("kind = 'decision'"))
        assertTrue("按时间排：后写的覆盖先写的", stmtLine.contains("ORDER BY at"))
        assertFalse("负例：影子写库必须红", readOnlySql("UPDATE skill_event SET ok = 1 WHERE hash = ?"))
        assertFalse("负例：影子建表必须红", readOnlySql("CREATE TABLE shadow (id INTEGER)"))
    }

    @Test
    fun `S6_影子没有定时线程与新权限_负例必须红`() {
        assertTrue("影子文件不许自己起来干活", noScheduling(shadow))
        assertTrue("接线段也不许引入定时", noScheduling(judgeRegion))
        assertFalse("负例：影子挂定时任务必须红", noScheduling("WorkManager.getInstance(ctx)"))
        assertFalse("负例：影子起线程偷偷跑必须红", noScheduling("Thread { judge() }.start()"))
        assertFalse("负例：影子排队列周期跑必须红", noScheduling("Executors.newSingleThreadScheduledExecutor()"))
    }

    @Test
    fun `S7_影子不接UI_负例必须红`() {
        assertTrue("影子是数，不是卡", noUi(shadow))
        assertFalse("负例：影子自己冒 UI 必须红", noUi("Button(onClick = { apply() }) {}"))
        // 99 留下的可观察状态一字未改：影子没有往 evolutionSuggestions 里塞东西
        assertTrue(
            "状态接缝仍是 99 那一行",
            controller.contains("var evolutionSuggestions by mutableStateOf(listOf<EvolutionSuggestion>())"),
        )
        assertTrue("影子不进建议列表（它不是给人点的决定）", !judgeRegion.contains("evolutionSuggestions +="))
    }

    // ── S8–S10 接线（防死代码）──────────────────────────────────────────────

    @Test
    fun `S8_影子零件都有生产调用点`() {
        for ((token, defFile) in listOf(
            "shadowVerdictOf" to "baopu/EvolutionShadow.kt",
            "EvolutionShadowLog" to "baopu/EvolutionShadow.kt",
            "EvolutionShadowHuman" to "baopu/EvolutionShadow.kt",
            "latestByCandidate" to "baopu/EvolutionShadow.kt",
            "tallyShadow" to "baopu/EvolutionShadow.kt",
            "agreementShadow" to "baopu/EvolutionShadow.kt",
            "shadowSummaryLine" to "baopu/EvolutionShadow.kt",
        )) {
            val refs = mainRefs(token, defFile)
            assertTrue("$token 必须有生产调用点：$refs", refs.isNotEmpty())
            assertTrue("$token 的调用点必须在收尾判定链上：$refs", refs.any { it.endsWith("ui/baopu/BaopuChatController.kt") })
        }
    }

    @Test
    fun `S9_接线在判定链内_99的真决策口径没动`() {
        assertTrue("真决策仍走决策口", judgeRegion.contains("port.decide(input, operatorId = \"settle\")"))
        assertTrue("决策口仍自带默认策略（控制器不实例化）", judgeRegion.contains("EvolutionDecisionPort(audit = audit)"))
        assertTrue("候选仍只读 listPending", judgeRegion.contains("store.listPending()"))
        assertTrue("四类证据一个字没改", judgeRegion.contains("SkillEffects.outcomesFor(db, c.target)"))
        assertTrue("自检 dry-run 没动", judgeRegion.contains("dryRunSelfCheck("))
        assertTrue("eval 仍只认结果文件", judgeRegion.contains("EvalResultFile.latest(appCtx)"))
        assertTrue("组装仍走证据桥", judgeRegion.contains("inputFor(c, evidence)"))
        assertTrue("影子账本接在收尾判定链上", judgeRegion.contains("EvolutionShadowLog.under(globalRoot)"))
        assertTrue("影子判定逐条接上", judgeRegion.contains("shadowRows += shadowVerdictOf(input, decision, shadowAt)"))
        assertTrue("操作者仍是 settle，影子没有冒充人批", judgeRegion.contains("operatorId = \"settle\""))
    }

    @Test
    fun `S10_影子失败不带走真判定`() {
        assertTrue("每条候选的影子各自 runCatching 兜住", judgeRegion.contains("影子单独兜"))
        assertTrue("影子判定跳过要留痕", judgeRegion.contains("[baopu-shadow] 影子判定跳过"))
        assertTrue("写账本失败要留痕", judgeRegion.contains("[baopu-shadow] 影子账本写入失败"))
        assertTrue("汇总失败要留痕", judgeRegion.contains("[baopu-shadow] 汇总跳过"))
        assertTrue("人工留痕读不动要有 log", shadow.contains("本轮按无配对样本处理"))
        assertTrue("账本读不动要有 log", shadow.contains("影子账本读失败"))
        // 真判定的兜底一个字没改（99 的那句 log 还在）
        assertTrue("真判定跳过的留痕不变", judgeRegion.contains("候选判定跳过"))
    }

    // ── S11–S14 边界与账本卫生 ────────────────────────────────────────────

    @Test
    fun `S11_影子走独立账本_不挤88的审计行`() {
        assertFalse("88 的审计文件本批一个字节没改", audit.contains("ShadowVerdict"))
        assertFalse("审计行里没有影子键", audit.contains("\"hold\"") || audit.contains("WOULD_"))
        assertEquals("决策审计文件名不变", "决策审计.jsonl", EvolutionAuditLog.FILE_NAME)
        assertEquals("影子另开一条流", "进化影子.jsonl", EvolutionShadowLog.FILE_NAME)
        assertFalse("两份账不许同名", EvolutionShadowLog.FILE_NAME == EvolutionAuditLog.FILE_NAME)
    }

    @Test
    fun `S12_没有新表没有migration_库版本仍是14`() {
        for (token in listOf("CREATE TABLE", "onUpgrade", "ALTER TABLE", "versionCode", "versionName")) {
            assertFalse("影子文件不许出现：$token", shadow.contains(token))
        }
        // 第一百一十七批把库升到 v15（run_queue）；本批（影子）自己依旧不动库。
        assertTrue("BaopuDb.VERSION 已是 16（v16 = 第一百二十七批 sub_task；本批不动库）", db.contains("const val VERSION = 16"))
        assertTrue("影子读数不碰 DDL", !shadow.contains("DATABASE"))
    }

    @Test
    fun `S13_账本里没有正文`() {
        for (key in listOf("\"payload\"", "\"body\"", "\"content\"", "\"detail\"", "\"text\"", "payload")) {
            assertFalse("影子文件不许出现正文键：$key", shadow.contains(key))
        }
        val json = ShadowJudgement(
            atMs = 1L,
            candidateId = "c1",
            kind = ProposalKind.SKILL_BODY,
            riskCategory = EvolutionRiskCategory.SKILL_BODY_MINOR,
            riskClass = EvolutionRisk.LOW,
            verdict = ShadowVerdict.WOULD_APPROVE,
            hold = ShadowHold.NONE,
            recommendation = EvolutionRecommendation.ADOPT,
            confidence = 1.0,
            requiresApproval = true,
            evidenceIds = listOf("run_log:3"),
            reasons = listOf(EvolutionReason(EvolutionReasonCode.CANDIDATE_EVIDENCE, listOf("run_log:3"))),
        ).toJson().toString()
        assertFalse("行里只有 id 与数值", json.contains("用户原话"))
        assertTrue("证据只以 id 出现", json.contains("run_log:3"))
    }

    @Test
    fun `S14_候选读口没被影子换掉`() {
        val refs = mainRefs("listPending", "baopu/ProposalCandidateStore.kt")
        assertTrue("候选档必须有读口调用点：$refs", refs.isNotEmpty())
        assertFalse("影子文件不许读候选档（它吃的是决策口那份输入）", shadow.contains("listPending"))
        assertFalse("影子文件不许写候选档", shadow.contains("ProposalCandidateStore"))
    }

    // ── 汇总口径钉死（防「口径被人顺手改掉」）──────────────────────────────

    @Test
    fun `一致率口径_影子弃权不进分母_零配对没有比率`() {
        // 这三条是「口径」，不是「算法」：比率怎么算由派工单 §2 定，改动必须留痕。
        val defer = ShadowJudgement(
            atMs = 1L,
            candidateId = "d1",
            kind = ProposalKind.SKILL_BODY,
            riskCategory = EvolutionRiskCategory.PERSONA_CORE,
            riskClass = EvolutionRisk.HIGH,
            verdict = ShadowVerdict.WOULD_DEFER,
            hold = ShadowHold.RISK_NOT_LOW,
            recommendation = EvolutionRecommendation.ADOPT,
            confidence = 1.0,
            requiresApproval = true,
            evidenceIds = listOf("run_log:1"),
            reasons = listOf(EvolutionReason(EvolutionReasonCode.CANDIDATE_EVIDENCE, listOf("run_log:1"))),
        )
        val agreement = agreementShadow(listOf(defer), mapOf("d1" to true))
        assertEquals("弃权不进分母", 0, agreement.paired)
        assertFalse("零配对不许冒出一个比率", agreement.rate != null)
        assertEquals("弃权单独一格，将来算「人工替影子干了多少活」", 1, agreement.abstainedHumanDecided)
        assertTrue("汇总行必须写横杠，不写 0", shadowSummaryLine(tallyShadow(listOf(defer)), agreement).contains("一致率 —"))
    }

    @Test
    fun `汇总行的分母口径写进源码注释`() {
        assertTrue("口径必须落在源码里，不能只在报告里", shadow.contains("影子敢表态"))
        assertTrue("弃权不进分母这件事必须写在源码上", shadow.contains("不进分母"))
        assertTrue("零样本没有比率必须写在源码上", shadow.contains("不是 0.0"))
    }
}
