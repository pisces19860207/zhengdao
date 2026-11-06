// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第三百四十二批 · 任务契约对象的判据用例。
//
// 覆盖面按派工单排（每条都是一个能单独被做红的断言，不做"打包绿"）：
//   §1 六样齐的单子：序列化往返、指纹绑对象、字段形状（六样就是六样）
//   §2 硬规矩 1：done ≠ 验收通过（五态闭集、禁夹带验收键）
//   §3 硬规矩 2：外部执行体不许写我们的账本（禁夹带账本键／改单键、账行只许引用、契约层零 I/O）
//   §4 严收与两遍校验：未知键／版本／类型 ⇒ 拒收；额度越界 ⇒ 收得下但判据红
//   §5 六样各自的红法：空目标、无验收契约、写根越界、出口槽位越界、禁词、重复项
//   §6 预设档（342-B，2026-11-01 裁定 Q2）：四档数值逐个钉、`long`＝标签不是配额、
//      挂档不许改默认值、没挂档另判一条、`preset` 是必填键、同数不同档算出不同指纹
package com.example.zhengdao.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import org.json.JSONObject

class TaskContractTest {

    // ── 样例：一张真能用得上的单子（抄 335 那类"数据整理"任务）──────────────

    private fun sampleContract() = TaskContract(
        contractId = "zd-342-demo",
        revision = 1,
        goal = TaskGoal(
            text = "把源表整理成周报数据",
            criteria = listOf(
                "output/weekly.csv 有表头且列数=5",
                "行数与源表逐月一致",
            ),
        ),
        deliverables = listOf(
            TaskDeliverable("d1", "output/weekly.csv", listOf("列数=5", "行数与源表一致")),
            TaskDeliverable("d2", "output/weekly.md", listOf("含「结论」一节且引用了 d1")),
        ),
        authority = TaskAuthority(readRoots = listOf("input", "output"), writeRoots = listOf("output")),
        budget = BudgetPreset.STANDARD.budget(),
        evidence = listOf(EvidenceKind.EXISTS, EvidenceKind.SHA256, EvidenceKind.LEDGER),
        exit = TaskExit(
            onPause = ExitAction.HOLD,
            onCancel = ExitAction.ROLLBACK,
            onTimeout = ExitAction.ASK_USER,
            onFailure = ExitAction.RETRY,
        ),
    )

    private fun sampleReceipt(contract: TaskContract, state: ReceiptState = ReceiptState.DONE) = TaskReceipt(
        contractId = contract.contractId,
        contractSha256 = contract.sha256(),
        state = state,
        atMs = 1_730_000_000_000L,
        evidence = contract.evidence.map { ReceiptEvidence(it, "${it.wire}://output/weekly.csv") },
        note = if (state == ReceiptState.FAILED || state == ReceiptState.REJECTED) "源表缺 9 月" else "",
    )

    /** 往一条合法回执行里夹带一个键，返回夹带后的原文（键序与实现无关，所以这里直接走 JSONObject）。 */
    private fun smuggle(line: String, key: String, value: Any): String =
        JSONObject(line).put(key, value).toString()

    // ── §1 六样齐的单子 ────────────────────────────────────────────────────

    @Test
    fun `六样齐的单子_序列化读回逐字一致`() {
        val c = sampleContract()
        assertEquals("样例本身就该合格：${c.violations()}", emptyList<String>(), c.violations())
        val back = TaskContract.parse(c.jsonText())
        assertNotNull("读不回来（原文=${c.jsonText()}）", back)
        assertEquals("写后读回必须与原对象逐格相等", c, back)
        assertEquals("读回后再序列化，指纹不许变", c.sha256(), back!!.sha256())
    }

    @Test
    fun `交付物顺序与内容按原样保留`() {
        val c = sampleContract()
        val back = TaskContract.parse(c.jsonText())!!
        assertEquals(c.deliverables.map { it.id to it.acceptance }, back.deliverables.map { it.id to it.acceptance })
    }

    @Test
    fun `契约就是六样_没夹带第七格`() {
        // 派工单点名的六样（目标／交付物／授权／预算／证据／出口）＋身份两格（id／版本）。
        // 多一格就得改派工单，不许悄悄加。
        assertEquals(
            listOf("authority", "budget", "contractId", "deliverables", "evidence", "exit", "goal", "revision"),
            TaskContract::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .sorted(),
        )
        // 六样的 JSON 段名也钉住（协议适配器将来只能读这份键名表）
        assertTrue(TaskContract.KEYS.containsAll(listOf("goal", "deliverables", "authority", "budget", "evidence", "exit")))
    }

    @Test
    fun `回执只有自报六格_里面没有验收格`() {
        assertEquals(
            listOf("atMs", "contractId", "contractSha256", "evidence", "note", "state"),
            TaskReceipt::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .sorted(),
        )
        // 三张禁键表里的任何一个都不许出现在回执的合法键名里（禁键表与键名表必须互斥）
        val banned = TaskReceipt.VERDICT_KEYS + TaskReceipt.LEDGER_KEYS + TaskReceipt.CONTRACT_KEYS
        assertTrue(
            "回执的合法键名撞上了禁键表：${TaskReceipt.KEYS.filter { it.lowercase() in banned }}",
            TaskReceipt.KEYS.none { it.lowercase() in banned },
        )
    }

    @Test
    fun `指纹绑对象_改一处判据指纹就换`() {
        val c = sampleContract()
        val tightened = c.copy(goal = c.goal.copy(criteria = c.goal.criteria + "行数不含表头"))
        assertFalse("改了判据指纹却没变", c.sha256() == tightened.sha256())
        assertEquals("同一张单子算两遍必须同一个数", c.sha256(), sampleContract().sha256())
        assertTrue("指纹是 64 位小写十六进制", Regex("^[0-9a-f]{64}$").matches(c.sha256()))
    }

    @Test
    fun `规范串按键名字典序_所以指纹不依赖键的写入顺序`() {
        val c = sampleContract()
        val canonical = c.canonicalText()
        val order = listOf(
            "\"authority\"", "\"budget\"", "\"contract_id\"", "\"deliverables\"",
            "\"evidence\"", "\"exit\"", "\"goal\"", "\"revision\"", "\"v\"",
        )
        val positions = order.map { canonical.indexOf(it) }
        assertTrue("规范串缺段：$canonical", positions.all { it >= 0 })
        assertEquals("规范串的段名必须是字典序（否则跨 JSON 实现哈希会飘）", positions.sorted(), positions)

        // 乱序输入进来，认出来的对象指纹仍与正序一致
        val src = JSONObject(c.jsonText())
        val reordered = JSONObject()
        src.keys().asSequence().toList().reversed().forEach { k -> reordered.put(k, src.get(k)) }
        assertEquals(c.sha256(), TaskContract.from(reordered)!!.sha256())
    }

    @Test
    fun `回执绑单子是 id 与指纹双双对上`() {
        val c = sampleContract()
        val r = sampleReceipt(c)
        assertTrue(r.bindsTo(c))
        assertFalse("指纹不同就不算绑住", r.bindsTo(c.copy(revision = 2)))
        assertFalse("id 不同就不算绑住", r.copy(contractId = "别的单子").bindsTo(c))
    }

    // ── §2 硬规矩 1：done ≠ 验收通过 ────────────────────────────────────────

    @Test
    fun `五态闭集_没有验收通过这个取值`() {
        assertEquals(
            listOf("accepted", "done", "failed", "rejected", "running"),
            ReceiptState.entries.map { it.wire }.sorted(),
        )
        // ACCEPTED＝接单、DONE＝交完；两个最容易误读的名字各带一句中文注（改字要过这条）
        assertEquals("接单", ReceiptState.ACCEPTED.cn)
        assertEquals("交完", ReceiptState.DONE.cn)
        assertNull("合格不是自报态", ReceiptState.ofWire("passed"))
        assertNull("验收通过不是自报态", ReceiptState.ofWire("accepted_by_right"))
    }

    @Test
    fun `夹带验收字段的回执整行拒收`() {
        val line = sampleReceipt(sampleContract()).jsonText()
        listOf("pass", "verdict", "score", "accepted_by", "signed_off").forEach { key ->
            val dirty = smuggle(line, key, if (key == "score") 95 else true)
            assertNull("夹带「$key」的回执居然被收下了", TaskReceipt.parse(dirty))
            val why = TaskReceipt.rejectionReason(dirty)
            assertNotNull(why)
            assertTrue("拒因没说明是验收权的问题：$why", why!!.contains("done 不等于验收通过"))
        }
    }

    @Test
    fun `done 不交证据_自报的毛病照样记一条`() {
        val c = sampleContract()
        val empty = sampleReceipt(c).copy(evidence = emptyList())
        assertNotNull("done 无证据仍是一张收得下的回执（只是不合格）", TaskReceipt.parse(empty.jsonText()))
        assertTrue(empty.violations().any { it.contains("done 却不带任何证据") })
        assertEquals("交齐了就没有这一条", emptyList<String>(), sampleReceipt(c).violations())
    }

    @Test
    fun `缺证据只报缺口_不下合格结论`() {
        val c = sampleContract()
        val partial = sampleReceipt(c).copy(evidence = listOf(ReceiptEvidence(EvidenceKind.EXISTS, "output/weekly.csv")))
        assertEquals(listOf(EvidenceKind.SHA256, EvidenceKind.LEDGER), partial.missingEvidenceFor(c))
        assertTrue(sampleReceipt(c).missingEvidenceFor(c).isEmpty())
        // 缺口算空 ≠ 合格：合格要右相拿着 332 的契约表判，回执里根本没有那一格。
        // 查实例方法面（合成静态桥 `access$getXXX_KEYS$cp` 是给禁键表用的，不算读法）。
        val reads = TaskReceipt::class.java.declaredMethods
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertFalse(
            "回执类型给出了「合格」读法：${reads.filter { n -> listOf("accepted", "passed", "verdict", "score").any { it in n.lowercase() } }}",
            reads.any { n -> listOf("accepted", "passed", "verdict", "score").any { it in n.lowercase() } },
        )
        assertEquals(
            "回执的读法面就是自报六格，多一格都是口子",
            listOf("getAtMs", "getContractId", "getContractSha256", "getEvidence", "getNote", "getState"),
            reads.filter { it.startsWith("get") }.sorted(),
        )
    }

    // ── §3 硬规矩 2：外部执行体不许写我们的账本 ─────────────────────────────

    @Test
    fun `夹带账本字段的回执整行拒收`() {
        val line = sampleReceipt(sampleContract()).jsonText()
        listOf("ledger", "ledger_write", "write_ledger", "record", "append_ledger").forEach { key ->
            val dirty = smuggle(line, key, mapOf("op" to "append", "row" to "我说了算"))
            assertNull("夹带「$key」的回执居然被收下了", TaskReceipt.parse(dirty))
            val why = TaskReceipt.rejectionReason(dirty)
            assertTrue("拒因没说明是记账权的问题：$why", why!!.contains("账由我们记"))
        }
    }

    @Test
    fun `夹带改单字段的回执整行拒收`() {
        val line = sampleReceipt(sampleContract()).jsonText()
        listOf("authority", "budget", "deliverables", "grant", "extend").forEach { key ->
            val dirty = smuggle(line, key, "再给我十倍预算")
            assertNull("夹带「$key」的回执居然被收下了", TaskReceipt.parse(dirty))
            val why = TaskReceipt.rejectionReason(dirty)
            assertTrue("拒因没说明是改单权的问题：$why", why!!.contains("不许改自己的单子"))
        }
    }

    @Test
    fun `账行引用交得回来_LEDGER 是指针不是写账`() {
        val c = sampleContract()
        val r = sampleReceipt(c).copy(
            evidence = listOf(ReceiptEvidence(EvidenceKind.LEDGER, "决策记录.jsonl#4321")),
        )
        assertEquals(r, TaskReceipt.parse(r.jsonText()))
    }

    @Test
    fun `契约层零IO零网络_本文件只做对象`() {
        // 「不发布任何协议」的机验面：契约与回执这一层不碰盘、不碰网、不起进程。
        // 谁往这里加了写盘或出口调用，这条当场红（发协议的适配器是另一批、另一个文件）。
        val src = contractSource()
        val code = src.map { stripLineComment(it) }.filterNot { it.isBlank() }
        assertTrue("剥完注释只剩 ${code.size} 行，扫描等于没扫", code.size > 150)
        val banned = listOf(
            "File(", "FileWriter", "FileInputStream", "FileOutputStream", "RandomAccessFile",
            ".writeText(", ".printWriter(", ".appendText(", "mkdir",
            "Socket(", "HttpURL", "URLConnection", "OkHttp", "URL(", "URI(",
            "ProcessBuilder", "Runtime.getRuntime",
        )
        val hits = code.flatMap { line -> banned.filter { line.contains(it) }.map { "$it｜$line" } }
        assertEquals("契约层出现了写盘／出网／起进程的调用：\n$hits", emptyList<String>(), hits)
    }

    @Test
    fun `零 IO 门禁自检_造一行违规必须被抓到`() {
        // 门禁自己也得能被做红（同 CoreBoundaryGateTest 的做法）
        val probe = listOf("    val f = File(\"x\")", "    val u = URL(\"https://example.invalid\")")
        val banned = listOf("File(", "URL(")
        val hits = probe.flatMap { line -> banned.filter { line.contains(it) } }
        assertEquals(2, hits.size)
        // 反过来说：注释里提到这些词不算依赖（文件头就必然要写"没有磁盘写入"这几个字）
        assertTrue(stripLineComment("// 用 File( 写盘是禁止的").isEmpty())
    }

    // ── §4 严收与两遍校验 ───────────────────────────────────────────────────

    @Test
    fun `未知键一律拒收_夹带回调地址这种不收`() {
        val c = sampleContract()
        val smuggled = smuggle(c.jsonText(), "callback_url", "https://example.invalid/a2a")
        assertNull("契约里居然能塞进一个协议字段", TaskContract.parse(smuggled))
        assertTrue(TaskContract.rejectionReason(smuggled)!!.contains("callback_url"))
        assertNull(TaskReceipt.parse(smuggle(sampleReceipt(c).jsonText(), "next_run_ms", 1)))
    }

    @Test
    fun `外发永远为否_true 拒收_false 照收`() {
        val c = sampleContract()
        assertEquals("false", JSONObject(c.jsonText()).getJSONObject("authority").get("egress").toString())
        val turnedOn = JSONObject(c.jsonText())
        turnedOn.getJSONObject("authority").put("egress", true)
        assertNull("egress=true 的单子被收下了", TaskContract.parse(turnedOn.toString()))
        assertTrue(TaskContract.rejectionReason(turnedOn.toString())!!.contains("外发不可谈判"))
        // 而且这个类型根本没有能把外发打开的入口（[egressAllowed] 是只读属性，不是字段）
        assertFalse(
            "TaskAuthority 暴露了可写的外发字段",
            TaskAuthority::class.java.declaredFields.any { it.name.equals("egressAllowed", true) },
        )
        assertFalse(sampleContract().authority.egressAllowed)
        // 手写一个 false 的单子照样收（写出 false 不是为了允许改成 false，是为了"读得回来"）
        val off = JSONObject(c.jsonText())
        off.getJSONObject("authority").put("egress", false)
        assertEquals(c, TaskContract.parse(off.toString()))
    }

    @Test
    fun `行格式版本不认识_拒收`() {
        val c = sampleContract()
        assertNull(TaskContract.parse(smuggle(c.jsonText(), "v", 2)))
        assertNull(TaskReceipt.parse(smuggle(sampleReceipt(c).jsonText(), "v", 99)))
    }

    @Test
    fun `数字写成字符串_拒收`() {
        val c = sampleContract()
        val stepsAsText = JSONObject(c.jsonText())
        stepsAsText.getJSONObject("budget").put("max_steps", "40")
        assertNull("预算步数写成字符串居然收下了", TaskContract.parse(stepsAsText.toString()))

        val revisionAsDouble = JSONObject(c.jsonText())
        revisionAsDouble.put("revision", 1.0)
        // org.json 的 put(String, double) 会把整数值的小数折成整数，所以 1.0 与 1 是同一格——
        // 这是实现行为，不是这里的宽松；真正要拒的是"小数"和"字符串"两种写法（下面两条）。
        assertEquals(c, TaskContract.parse(revisionAsDouble.toString()))

        val revisionFractional = JSONObject(c.jsonText())
        revisionFractional.put("revision", 1.5)
        assertNull("版本号写成小数居然收下了", TaskContract.parse(revisionFractional.toString()))

        val wallAsText = JSONObject(c.jsonText())
        wallAsText.getJSONObject("budget").put("max_wall_ms", "600000")
        assertNull(TaskContract.parse(wallAsText.toString()))
    }

    @Test
    fun `额度越界收得下但判据红_拒收与不合格不混`() {
        val c = sampleContract().copy(budget = TaskBudget(
            maxWallMs = 86_400_000L,
            maxSteps = 999,
            maxInterruptions = 1,
            preset = BudgetPreset.STANDARD,
        ))
        val back = TaskContract.parse(c.jsonText())
        assertNotNull("形状是对的，parse 不该拒", back)
        val v = back!!.violations()
        // 三档里两档越界（墙钟、步数）＋挂档却改了默认值＝三条，各自点名，不合并成一条含糊的"预算不对"
        assertEquals(v.joinToString("｜"), 3, v.count { it.startsWith("budget.") })
        assertEquals(v.joinToString("｜"), 2, v.count { it.contains("越界") })
        assertTrue(v.any { it.contains("max_wall_ms") })
        assertTrue(v.any { it.contains("max_steps") })
        assertTrue(v.any { it.contains("挂了档") })
    }

    @Test
    fun `三档上限是抄本_数值与出处一起钉住`() {
        // 这四个界值不是这里定的：真源在 :app 侧（GrantLimits／BaopuKernel／OpinionBudget）,
        // :core 不能反向 import，只能带出处抄一份。抄本自己漂了，判据就换了口径——
        // 所以数值与出处一起钉：要动界值必须连同出处那一起动，这条先红给看。
        assertEquals(10_000L, TaskBudget.MIN_WALL_MS)
        assertEquals(3_600_000L, TaskBudget.MAX_WALL_MS)
        assertEquals(1L, TaskBudget.MIN_STEPS.toLong())
        assertEquals(300L, TaskBudget.MAX_STEPS.toLong())
        assertEquals(0L, TaskBudget.MIN_INTERRUPTIONS.toLong())
        assertEquals(3L, TaskBudget.MAX_INTERRUPTIONS.toLong())

        // 界值本身可用；界值 +1 三档各判一条红（不许靠"parse 拒收"混过去）。
        val c = sampleContract()
        val atCeil = c.copy(budget = TaskBudget(
            TaskBudget.MAX_WALL_MS, TaskBudget.MAX_STEPS, TaskBudget.MAX_INTERRUPTIONS,
            preset = BudgetPreset.STANDARD,
        ))
        assertEquals(0, atCeil.violations().count { it.startsWith("budget.") })
        val overWall = c.copy(budget = TaskBudget(
            TaskBudget.MAX_WALL_MS + 1, TaskBudget.MAX_STEPS, TaskBudget.MAX_INTERRUPTIONS,
        ))
        assertTrue(overWall.violations().any { it.startsWith("budget.max_wall_ms 越界") })
        val overSteps = c.copy(budget = TaskBudget(
            TaskBudget.MIN_WALL_MS, TaskBudget.MAX_STEPS + 1, TaskBudget.MAX_INTERRUPTIONS,
        ))
        assertTrue(overSteps.violations().any { it.startsWith("budget.max_steps 越界") })
        val overInter = c.copy(budget = TaskBudget(
            TaskBudget.MIN_WALL_MS, TaskBudget.MAX_STEPS, TaskBudget.MAX_INTERRUPTIONS + 1,
        ))
        assertTrue(overInter.violations().any { it.startsWith("budget.max_interruptions 越界") })
    }

    // ── §6 预设档（342-B：2026-11-01 裁定 Q2）────────────────────────────────

    @Test
    fun `四档预设是抄本_十二个数字逐个钉住`() {
        assertEquals(listOf("probe", "standard", "long", "exec"), BudgetPreset.WIRES)
        assertEquals(90_000L, BudgetPreset.PROBE.wallMs)
        assertEquals(16L, BudgetPreset.PROBE.steps.toLong())
        assertEquals(0L, BudgetPreset.PROBE.interruptions.toLong())
        assertEquals(3_600_000L, BudgetPreset.STANDARD.wallMs)
        assertEquals(300L, BudgetPreset.STANDARD.steps.toLong())
        assertEquals(3L, BudgetPreset.STANDARD.interruptions.toLong())
        assertEquals(3_600_000L, BudgetPreset.LONG.wallMs)
        assertEquals(300L, BudgetPreset.LONG.steps.toLong())
        assertEquals(3L, BudgetPreset.LONG.interruptions.toLong())
        assertEquals(3_600_000L, BudgetPreset.EXEC.wallMs)
        assertEquals(300L, BudgetPreset.EXEC.steps.toLong())
        assertEquals(3L, BudgetPreset.EXEC.interruptions.toLong())
        // 标准档的数值就是硬界值：顶格单＝标准档，不需要自定义
        assertEquals(TaskBudget.MAX_WALL_MS, BudgetPreset.STANDARD.wallMs)
        assertEquals(TaskBudget.MAX_STEPS.toLong(), BudgetPreset.STANDARD.steps.toLong())
        assertEquals(TaskBudget.MAX_INTERRUPTIONS.toLong(), BudgetPreset.STANDARD.interruptions.toLong())
        // 每个档位都得有个中文名（拒收理由与判据都要念它，空档名＝念了等于没说）
        BudgetPreset.entries.forEach { assertTrue("${it.wire} 档没有中文名", it.cn.isNotBlank()) }
    }

    @Test
    fun `long 是标签不是配额_数值与 standard 逐档相等`() {
        // Q1 定了"单契约不许放宽"，所以 long 只能标"这单要拆"，不能多给一秒一步。
        assertEquals(BudgetPreset.STANDARD.wallMs, BudgetPreset.LONG.wallMs)
        assertEquals(BudgetPreset.STANDARD.steps.toLong(), BudgetPreset.LONG.steps.toLong())
        assertEquals(BudgetPreset.STANDARD.interruptions.toLong(), BudgetPreset.LONG.interruptions.toLong())
        BudgetPreset.entries.forEach { p ->
            assertTrue("${p.wire} 的墙钟越界", p.wallMs in TaskBudget.MIN_WALL_MS..TaskBudget.MAX_WALL_MS)
            assertTrue("${p.wire} 的步数越界", p.steps in TaskBudget.MIN_STEPS..TaskBudget.MAX_STEPS)
            assertTrue(
                "${p.wire} 的打扰越界",
                p.interruptions in TaskBudget.MIN_INTERRUPTIONS..TaskBudget.MAX_INTERRUPTIONS,
            )
        }
    }

    @Test
    fun `requiresSplit 只在 long 为真`() {
        assertTrue(BudgetPreset.LONG.requiresSplit)
        assertFalse(BudgetPreset.PROBE.requiresSplit)
        assertFalse(BudgetPreset.STANDARD.requiresSplit)
        assertFalse(BudgetPreset.EXEC.requiresSplit)
    }

    @Test
    fun `挂档却改默认值_判据红点名档位`() {
        val c = sampleContract()
        val onShape = c.copy(budget = BudgetPreset.STANDARD.budget())
        assertEquals(
            onShape.violations().joinToString("｜"),
            0,
            onShape.violations().count { it.startsWith("budget.") },
        )
        val fudged = c.copy(budget = TaskBudget(
            maxWallMs = BudgetPreset.STANDARD.wallMs,
            maxSteps = BudgetPreset.STANDARD.steps,
            maxInterruptions = 1,
            preset = BudgetPreset.STANDARD,
        ))
        val v = fudged.violations().filter { it.startsWith("budget.") }
        assertEquals(v.joinToString("｜"), 1, v.size)
        assertTrue(v[0].contains("挂了档「standard」"))
        assertTrue("拒因要说清档位是形状不是申请额度：" + v[0], v[0].contains("不是申请额度"))
        assertFalse(fudged.isWellFormed())
    }

    @Test
    fun `没挂档另判一条_与越界分开算`() {
        val c = sampleContract().copy(budget = TaskBudget(600_000L, 40, 1))
        val v = c.violations().filter { it.startsWith("budget.") }
        assertEquals(v.joinToString("｜"), 1, v.size)
        assertTrue(v[0].contains("budget.preset 缺失"))
        assertTrue("拒因要把四档念出来，让人知道往哪挂：" + v[0], v[0].contains("probe"))
        assertFalse(c.isWellFormed())
    }

    @Test
    fun `档位是必填键_缺它拒收_档名不认识也拒收`() {
        val c = sampleContract()
        val noPreset = JSONObject(c.jsonText())
        noPreset.getJSONObject("budget").remove("preset")
        assertNull("budget 没有档位居然还收下了", TaskContract.parse(noPreset.toString()))
        val badName = JSONObject(c.jsonText())
        badName.getJSONObject("budget").put("preset", "turbo")
        assertNull("四档之外不该有第五种形状", TaskContract.parse(badName.toString()))
        assertEquals(
            "形状不符（缺键、类型不对或版本不认识）",
            TaskContract.rejectionReason(noPreset.toString()),
        )
    }

    @Test
    fun `同数不同档算出不同指纹_往返保住档位`() {
        val c = sampleContract()
        val std = c.copy(budget = BudgetPreset.STANDARD.budget())
        val lon = c.copy(budget = BudgetPreset.LONG.budget())
        val exe = c.copy(budget = BudgetPreset.EXEC.budget())
        assertEquals(std.budget.maxWallMs, lon.budget.maxWallMs)
        assertTrue("long 与 standard 数值相同、档名不同 ⇒ 指纹必须分开", std.sha256() != lon.sha256())
        assertTrue("exec 与 long 也必须分开", lon.sha256() != exe.sha256())
        val back = TaskContract.parse(lon.jsonText())
        assertNotNull(back)
        assertEquals(BudgetPreset.LONG, back!!.budget.preset)
        assertEquals(lon.sha256(), back.sha256())
        assertTrue(back.budget.preset!!.requiresSplit)
    }

    // ── §5 六样各自的红法 ───────────────────────────────────────────────────

    @Test
    fun `目标空判据缺都算不合格`() {
        val c = sampleContract()
        assertTrue(c.copy(goal = TaskGoal("  ", emptyList())).violations().any { it.contains("goal.text 为空") })
        assertTrue(c.copy(goal = c.goal.copy(criteria = emptyList())).violations().any { it.contains("至少一条判据") })
        assertTrue(
            c.copy(goal = c.goal.copy(criteria = (1..6).map { "判据$it" })).violations()
                .any { it.contains("goal.criteria 超") },
        )
    }

    @Test
    fun `判据与验收契约里不许有建议应该更好`() {
        val c = sampleContract()
        assertTrue(
            c.copy(goal = c.goal.copy(criteria = listOf("应该把表排好"))).violations()
                .any { it.contains("价值判断词") },
        )
        assertTrue(
            c.copy(deliverables = listOf(TaskDeliverable("d1", "x", listOf("建议再看一眼")))).violations()
                .any { it.contains("价值判断词") },
        )
    }

    @Test
    fun `交付物缺验收契约_重复项号_超十项都红`() {
        val c = sampleContract()
        assertTrue(c.copy(deliverables = emptyList()).violations().any { it.contains("deliverables 至少一项") })
        assertTrue(
            c.copy(deliverables = listOf(TaskDeliverable("d1", "x", emptyList()))).violations()
                .any { it.contains("没有验收契约") },
        )
        assertTrue(
            c.copy(deliverables = c.deliverables + c.deliverables.first().copy(what = "换个说法"))
                .violations().any { it.contains("deliverable.id 重复") },
        )
        val many = (1..11).map { TaskDeliverable("d$it", "第 $it 项", listOf("列数=5")) }
        assertTrue(c.copy(deliverables = many).violations().any { it.contains("deliverables 超") })
    }

    @Test
    fun `写根必须落在读根之内`() {
        val c = sampleContract()
        assertEquals(emptyList<String>(), c.authority.writeRootsEscapingRead())
        // 组件级比较：同前缀的兄弟目录不算在内（PathGuard 同口径）
        val escaped = c.copy(authority = TaskAuthority(readRoots = listOf("outputs"), writeRoots = listOf("outputs-evil")))
        assertEquals(listOf("outputs-evil"), escaped.authority.writeRootsEscapingRead())
        assertTrue(escaped.violations().any { it.contains("越出读范围") })
        // 词法归一后越界也算越界
        val dotDot = c.copy(authority = TaskAuthority(readRoots = listOf("output"), writeRoots = listOf("output/../secret")))
        assertTrue(dotDot.authority.writeRootsEscapingRead().contains("output/../secret"))
    }

    @Test
    fun `授权根声明必须是相对路径_绝对与盘符不收`() {
        val c = sampleContract()
        // `C:relative` 是**盘符相对**写法：Windows 上 Paths.get 判它不是绝对路径、Linux 上更只是普通相对名，
        // 只有「盘符前缀命中即拒」才拦得住——补这一条，判据在两平台都能复现（2026-11-06 readings-emit 红）。
        listOf("/etc/passwd", "C:/Windows", "C:relative", "z:/x", "a\\b", "..", "  ").forEach { bad ->
            val dirty = c.copy(authority = TaskAuthority(readRoots = listOf(bad)))
            assertTrue("非法声明「$bad」没被判红", dirty.violations().any { it.contains("不是合法声明") })
        }
    }

    @Test
    fun `出口四槽必填_槽位越界红`() {
        val c = sampleContract()
        // 失败槽只许 重试／降级／交人／中止：把回滚塞进失败槽是另一回事
        assertTrue(
            c.copy(exit = c.exit.copy(onFailure = ExitAction.ROLLBACK)).violations()
                .any { it.contains("on_failure") },
        )
        // 暂停槽不许"交人"（挂起是停手，不是发问）
        assertTrue(c.copy(exit = c.exit.copy(onPause = ExitAction.ASK_USER)).violations().any { it.contains("on_pause") })
        assertEquals(emptyList<String>(), c.exit.outOfRangeSlots())
        assertNull("不认识的槽名必须给 null（否则槽位表形同虚设）", TaskExit.allowedFor("on_whatever"))
        // 四槽一个都不许缺
        val missingSlot = JSONObject(c.jsonText())
        missingSlot.getJSONObject("exit").remove("on_timeout")
        assertNull("缺一个出口槽居然收下了", TaskContract.parse(missingSlot.toString()))
    }

    @Test
    fun `证据必须至少一类且不重复`() {
        val c = sampleContract()
        assertTrue(c.copy(evidence = emptyList()).violations().any { it.contains("evidence 至少一类") })
        assertTrue(
            c.copy(evidence = listOf(EvidenceKind.CI, EvidenceKind.CI)).violations().any { it.contains("evidence 重复") },
        )
    }

    @Test
    fun `六类证据闭集_与三百四十批点名的口径逐字一致`() {
        assertEquals(
            listOf("ci", "exists", "law_gate", "ledger", "sha256", "test_xml"),
            EvidenceKind.entries.map { it.wire }.sorted(),
        )
        assertEquals(EvidenceKind.LAW_GATE, EvidenceKind.ofWire(" LAW_Gate "))
        assertNull("闭集之外不许认", EvidenceKind.ofWire("vibes"))
    }

    @Test
    fun `failed 与 rejected 必须带原因`() {
        val c = sampleContract()
        assertTrue(
            sampleReceipt(c, ReceiptState.FAILED).copy(note = "").violations().any { it.contains("failed 必须写原因") },
        )
        assertTrue(
            sampleReceipt(c, ReceiptState.REJECTED).copy(note = "").violations().any { it.contains("rejected 必须写拒因") },
        )
        // 接单与在做没话可说是正常的
        assertEquals(emptyList<String>(), sampleReceipt(c, ReceiptState.RUNNING).violations())
        assertTrue(sampleReceipt(c, ReceiptState.FAILED).isWellFormed())
    }

    @Test
    fun `自报五态之外的态一概不认`() {
        val c = sampleContract()
        listOf("passed", "aborted", "accepted_by_right", "done_but_later", "").forEach { state ->
            val dirty = smuggle(sampleReceipt(c).jsonText(), "state", state)
            assertNull("自报态「$state」不在闭集里却被认下了", TaskReceipt.parse(dirty))
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    /** 读自己的生产源码（`:core` 单测的工作目录就是模块根，同 CoreBoundaryGateTest）。 */
    private fun contractSource(): List<String> {
        val f = File("src/main/java/com/example/zhengdao/core/TaskContract.kt")
        assertTrue("没找到契约源码，门禁失效了（工作目录=${File(".").absolutePath}）", f.isFile)
        return f.readLines()
    }

    /** 粗略剥掉行注释与块注释里的内容（本文件只用行注释；块注释另按 `*` 开头的 KDoc 行处理）。 */
    private fun stripLineComment(line: String): String {
        val t = line.trim()
        if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) return ""
        return line.substringBefore("//")
    }
}
