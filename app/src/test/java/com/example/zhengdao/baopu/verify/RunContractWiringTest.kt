// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百七十八批的判据测试（派工单判据①②④的 JVM 面）：
// - 判据①（链路三痕迹）：起单建契约 / 前置读契约 / 收工按契约判——每步的产出在这里直测，
//   真机链路痕迹由源码门禁（RunContractWiringGateTest）钉在引擎上；
// - 判据②（改坏判据 ⇒ FAIL）：PASS 路径在这条测试里立住，`_zd_378_mutate.py` 把
//   ContractSelector/Contracts 判据改坏一条后重跑本文件，PASS 必须变 FAIL（证明真在用）；
// - 判据④（不许接空壳）：断言契约字段非空且来自真实状态（用户原话/真实档位值/真产物读数）。
//
// 全程真文件真读数：临时工作区真落盘、[Contracts.readDoc] 真读、[ContractSelector.evaluate]
// 真判——没有一分.mock 的证据。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.agent.askAllowedForTurn
import com.example.zhengdao.core.BudgetPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RunContractWiringTest {

    private fun tmp(name: String): File = Files.createTempDirectory("baopu-378-$name").toFile()

    private fun orderOf(text: String): RunContractWiring.RunOrder {
        val order = RunContractWiring.startRun("r41@1700000000000", text)
        assertNotNull("起单失败（契约没建成）", order)
        return order!!
    }

    private fun writeDoc(root: File, rel: String, body: String) {
        val f = File(root, rel)
        f.parentFile.mkdirs()
        f.writeText(body)
    }

    /**
     * 本批被测件源码（先剥注释再断言：注释里提到的 `noticeLine` 不算代码里还有它）。
     *
     * 第三百一十七批的反钉用——收工结论只剩**一个**出口（日志行 + 人话那一行），
     * 旧的 notice 出口必须真没了，光看行为测不出来（那条路径不返回东西，只是不再调用）。
     */
    private fun wiringCode(): String = File(
        "src/main/java/com/example/zhengdao/baopu/verify/RunContractWiring.kt",
    ).let { if (it.isFile) it else File("app/src/main/java/com/example/zhengdao/baopu/verify/RunContractWiring.kt") }
        .readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    // ── 判据① 起单：契约建得出来，字段全是真实状态 ──────────────────────────

    @Test
    fun `起单_真实原话建契约_四样齐全且形状合规`() {
        val order = orderOf("帮我把这周的读书笔记整理成一份报告")
        val c = order.contract
        assertTrue("契约形状不合规：${c.violations()}", c.violations().isEmpty())
        // 目标＝用户原话（判据④：参数非空且来自真实状态）
        assertEquals("帮我把这周的读书笔记整理成一份报告", c.goal.text)
        assertTrue("goal.criteria 空——起单没写判据", c.goal.criteria.isNotEmpty())
        assertTrue("交付物空——起单没写交付", c.deliverables.isNotEmpty())
        // 授权范围＝工作区产物目录（真源 ZhengdaoDirs.PRODUCTS_DIR），写必在读内
        assertEquals(listOf("产物"), c.authority.readRoots)
        assertEquals(listOf("产物"), c.authority.writeRoots)
        assertTrue(c.authority.writeRootsEscapingRead().isEmpty())
        // 预算＝常单档的默认值（唯一正路构造口，数值逐档相等）
        assertEquals(BudgetPreset.STANDARD, c.budget.preset)
        assertTrue(c.budget.matchesPreset())
        assertEquals(300, c.budget.maxSteps)
        // 证据与出口：至少一类证据、四槽出口全部在允许集内（violations 已含，这里再点一遍）
        assertTrue(c.evidence.isNotEmpty())
        assertTrue("出口四槽越界：${c.exit.outOfRangeSlots()}", c.exit.outOfRangeSlots().isEmpty())
        assertEquals(RunContractWiring.DECLARED, TaskType.DOC_PRODUCE)
    }

    @Test
    fun `起单_空输入与空白原话_不立单`() {
        assertNull(RunContractWiring.startRun("r41@1", "   "))
        assertNull(RunContractWiring.startRun("", "帮我写个报告"))
        assertNull(RunContractWiring.startRun("r41@1", ""))
    }

    @Test
    fun `起单_超长目标_截到上限不破形状`() {
        val long = "帮我" + "很重要".repeat(200)
        val order = orderOf(long)
        assertTrue(order.contract.goal.text.length <= 200)
        assertTrue(order.contract.violations().isEmpty())
    }

    // ── 判据① 前置读契约：装配层消费的是契约本体（行文来自契约字段） ─────────

    @Test
    fun `前置读契约_起单与注入两行都带真实读数`() {
        val order = orderOf("帮我写一份周报")
        val start = order.startLine()
        val pre = order.precheckLine()
        assertTrue("起单行没带日志头", start.startsWith("抱朴契约：起单 c-r41@1"))
        assertTrue("起单行没带目标", start.contains("帮我写一份周报"))
        assertTrue("起单行没带档位数值（来自契约本体）", start.contains("300步/60分"))
        assertTrue("注入行没带目标", pre.contains("帮我写一份周报"))
        assertTrue("注入行没带范围（来自契约授权段）", pre.contains("产物"))
        assertTrue("注入行没带预算（来自契约预算段）", pre.contains("300步/60分钟"))
    }

    // ── 457 判据：三行的内容必须是契约要素（谁授权/边界/验收口径）────────────

    @Test
    fun `三行内容_契约要素齐全_谁授权_边界_验收口径`() {
        val order = orderOf("帮我把产物目录的周报改成两页")
        val start = order.startLine()
        val pre = order.precheckLine()
        // 谁授权：目标＝用户原话（原话即授权凭据，行里可查可对）
        assertTrue("起单行没带用户原话（授权凭据）", start.contains("帮我把产物目录的周报改成两页"))
        assertTrue("前置读行没带用户原话（授权凭据）", pre.contains("帮我把产物目录的周报改成两页"))
        // 边界：授权读/写范围来自契约本体的授权段
        assertTrue("起单行没带授权边界（读）", start.contains("授权=读["))
        assertTrue("起单行没带授权边界（写）", start.contains("写["))
        assertTrue("前置读行没带范围边界", pre.contains("范围："))
        // 验收口径：起单与前置读两行都写明收工按判据表验收
        assertTrue("起单行没带验收口径", start.contains("收工按判据表验收"))
        assertTrue("前置读行没带验收口径", pre.contains("收工按契约判据表验收"))
        // 收工行：同一口径的三态判定行（不是"收工"流水——顶替必红）
        val report = RunContractWiring.closeRun(order, tmp("contract-lines"), emptyList(), emptyList())
        assertTrue(
            "收工行不是验收口径行（须以'抱朴契约：收工验收'起头）",
            RunContractWiring.reportLine(report).startsWith("抱朴契约：收工验收"),
        )
    }

    // ── 判据① 收工按契约判：真产物真读数，三态由判据表算 ────────────────────

    @Test
    fun `收工_真落盘一份结构完好的md_判PASS`() {
        val root = tmp("pass")
        writeDoc(root, "产物/周报.md", "# 周报\n\n本周完成了三件事，全部有账。\n")
        val order = orderOf("帮我写一份周报")
        val report = RunContractWiring.closeRun(order, root, listOf("产物/周报.md"), emptyList())
        assertEquals(Status.PASS, report.status)
        assertEquals(Reason.OK, report.reason)
        assertEquals(3, report.checks.size)
        assertFalse(
            "317：PASS 之外也不许再有第二个出口——noticeLine 这个口子必须已经拆掉",
            wiringCode().contains("noticeLine"),
        )
        assertTrue(RunContractWiring.reportLine(report).contains("PASS"))
        assertTrue(File(root, "产物/周报.md").delete())
    }

    @Test
    fun `收工_零产物_判据表自己判缺证据FAIL`() {
        val order = orderOf("帮我写一份周报")
        val report = RunContractWiring.closeRun(order, tmp("empty"), emptyList(), emptyList())
        assertEquals(Status.FAIL, report.status)
        assertEquals(Reason.MISSING_EVIDENCE, report.reason)
        // 缺证据也要把契约列的判据逐条摊开（一条都不许吞）
        assertEquals(3, report.checks.size)
        assertTrue(report.checks.all { it.missing })
        assertTrue(
            "缺证据的 FAIL 必须在日志行里点得出来（317 之后那是唯一的书面出口）",
            RunContractWiring.reportLine(report).contains("缺"),
        )
    }

    @Test
    fun `收工_产物里挑不出文档类_不硬凑证据照判缺证据`() {
        val root = tmp("json")
        writeDoc(root, "产物/config.json", "{\"a\":1}\n")
        val order = orderOf("帮我写个配置")
        val report = RunContractWiring.closeRun(order, root, listOf("产物/config.json"), emptyList())
        assertEquals(Status.FAIL, report.status)
        assertEquals(Reason.MISSING_EVIDENCE, report.reason)
    }

    @Test
    fun `收工_真读产物_空文件判FAIL且EXISTS点名`() {
        val root = tmp("blank")
        writeDoc(root, "产物/周报.md", "")
        val order = orderOf("帮我写一份周报")
        val report = RunContractWiring.closeRun(order, root, listOf("产物/周报.md"), emptyList())
        assertEquals(Status.FAIL, report.status)
        assertTrue("EXISTS 必须在未过名单里：${report.failed}", CheckId.EXISTS in report.failed)
        assertFalse(RunContractWiring.reportLine(report).contains("PASS"))
    }

    @Test
    fun `收工_真读产物_结构坏了判FAIL且STRUCTURE点名`() {
        val root = tmp("struct")
        writeDoc(root, "产物/周报.md", "## 二级开头\n\n跳级且不从 H1 起。\n")
        val order = orderOf("帮我写一份周报")
        val report = RunContractWiring.closeRun(order, root, listOf("产物/周报.md"), emptyList())
        assertEquals(Status.FAIL, report.status)
        assertEquals(Reason.VALUE_MISMATCH, report.reason)
        assertTrue(CheckId.STRUCTURE in report.failed)
    }

    @Test
    fun `收工_收尾闸有未过项_打勾如实不成立判FAIL`() {
        val root = tmp("gate")
        writeDoc(root, "产物/周报.md", "# 周报\n\n正文。\n")
        val order = orderOf("帮我写一份周报")
        val findings = listOf(
            Finding("产物/另一份.md", ArtifactVerifier.CHECK_EMPTY, "产物是空文件"),
        )
        val report = RunContractWiring.closeRun(order, root, listOf("产物/周报.md"), findings)
        assertEquals(Status.FAIL, report.status)
        assertTrue(CheckId.USER_CHECKLIST in report.failed)
    }

    @Test
    fun `收工_多件产物_按清单顺序认第一份文档做主交付物`() {
        val root = tmp("multi")
        writeDoc(root, "产物/草稿.md", "# 草稿\n\n第一份。\n")
        writeDoc(root, "产物/终稿.md", "# 终稿\n\n第二份。\n")
        val order = orderOf("帮我写两份稿子")
        val report = RunContractWiring.closeRun(
            order, root, listOf("产物/草稿.md", "产物/终稿.md"), emptyList(),
        )
        assertEquals("第一份是真文档 ⇒ 整体判得出 PASS", Status.PASS, report.status)
        File(root, "产物/草稿.md").delete()
        File(root, "产物/终稿.md").delete()
    }

    @Test
    fun `收工_主交付物在工作区外_287口径照样认_不误判缺证据`() {
        // 401 验机 M7 的第二环：产物以绝对路径落在工作区外（写入侧 287 明文允许），
        // 旧的 resolve 只认工作区内 ⇒ 挑不出主交付物 ⇒ 判「缺证据」。404 起按真实落点认。
        val root = tmp("outside-ws")
        val outside = Files.createTempDirectory("baopu-378-outside").toFile()
        try {
            File(outside, "汇总.md").writeText("# 汇总\n\n五步全有账。\n")
            val order = orderOf("帮我做一个五步的汇总任务")
            val report = RunContractWiring.closeRun(
                order,
                root,
                listOf(File(outside, "汇总.md").absolutePath),
                emptyList(),
            )
            assertEquals("文件在、结构好、收尾闸 0 未过 ⇒ PASS", Status.PASS, report.status)
        } finally {
            outside.deleteRecursively()
        }
    }

    // ── 判据③ 同一句话判 TASK/CHAT ⇒ 弹卡读数不同（纯判定函数） ─────────────

    @Test
    fun `弹卡闸_同一句话TASK放行CHAT不弹_次数因此不同`() {
        // 同一形式的句子，只换词面：一个进任务态、一个落闲聊态
        val taskUtterance = "帮我查一下这周的报告写完没有"
        val chatUtterance = "哈哈哈哈笑死我了"
        assertTrue("任务态该弹卡（弹卡次数 +1）", askAllowedForTurn(taskUtterance))
        assertFalse("闲聊态不该弹卡（弹卡次数 +0）", askAllowedForTurn(chatUtterance))
    }

    @Test
    fun `弹卡闸_没有本轮原话_保守放行不吞该问的`() {
        assertTrue(askAllowedForTurn(null))
        assertTrue(askAllowedForTurn(""))
        assertTrue(askAllowedForTurn("   "))
    }
}
