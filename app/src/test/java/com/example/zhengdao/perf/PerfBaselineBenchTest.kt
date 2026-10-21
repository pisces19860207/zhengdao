// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百一十八批（性能与启动优化 v1）§1 测量件：**微基准（micro-bench）**，不是断言件。
//
// ## 为什么要有这份文件
// 派工单的前置是「先出测量报告，再决定修哪 3 个；不许凭感觉"顺手优化"」。
// 真机那一侧本批被红线挡住（不跑真机），于是还能拿到**毫秒**的只剩一条路：
// 把已知热点里的**纯函数**（不碰 Android API、不碰 DB、不碰 Compose）在 JVM 里跑起来计时。
// `tools/perf-scan.py` 给的是**结构计数**（谁在每次调用里现编正则、冷启动链上有几处阻塞调用），
// 这份文件给的是**这些计数换算成钱是多少**。两份合起来才叫基线。
//
// ## 三条纪律（写代码之前先定死）
// 1. **不断言耗时**。桌面 JVM 的绝对数不代表手机，同一台机器抖动就能 ±30%。
//    拿它当门禁 ⇒ 必然产出随机红灯。这里只**打印**；数字抄进
//    `证道设计报告/性能基线-2026-10-21.md`。前后对比靠**同一台机器、同一条命令、同一份样本**，
//    用的是相对比较，不是绝对阈值。
// 2. **样本必须是真的**。清单正文用仓库里那份已签名的 `rootfs/agents.json`（与
//    `AgentManifestVerifyTest` 同源）；日志行取 `redact()` 实际要脱敏的形状；
//    消息行取 `readMessages` 摊平后的真实形状（text/tool/plan 混排）。
//    造一份"看着像"的输入 ⇒ 测的是想象。
// 3. **先自检再计时**。每个 bench 先跑一次并校验产出非退化（解析条目数、替换是否命中）。
//    热点函数哪天签名变了、静默返空 ⇒ 计时照样给出一个漂亮的数字，那是假基线，比没有更糟。
//
// ## 怎么跑（复现命令，仓库根）
//   JAVA_HOME='D:/Program Files/Android/Android Studio/jbr' \
//     ./gradlew.bat --offline --max-workers=1 testDebugUnitTest --tests "*PerfBaselineBenchTest*"
//   # 读数：app/build/test-results/testDebugUnitTest/TEST-*PerfBaselineBenchTest.xml 的 <system-out>
//
// 每轮先 [WARMUP_ROUNDS] 轮热身（让 JIT 把热路径编成机器码），再取 [TIMED_ROUNDS] 轮的
// **中位数**——不用均值：一次 GC 停顿就能把均值拽高，中位数才代表"typical 一次"。
package com.example.zhengdao.perf

import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.ui.AgentManifest
import com.example.zhengdao.ui.baopu.BaopuMessageAssembler
import com.example.zhengdao.ui.baopu.BaopuPartRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfBaselineBenchTest {

    // ── 计时原语 ────────────────────────────────────────────────────────────

    /**
     * 一轮 [ops] 次调用，返回 µs/op。
     *
     * `acc` 把每次返回值的 **identityHashCode**（O(1)，与返回值体量无关）累加起来并在计时窗外读一次，
     * 目的是让 JIT 不能把被测调用当死代码删掉。代价是每次多一个 `identityHashCode`
     * （十纳秒量级），相对被测的正则/JSON 工作（微秒量级）可以忽略，
     * 且**改动前后两个版本拿到的是同一份偏移** ⇒ 相对比较仍然成立。
     */
    private fun timedRound(ops: Int, body: (Int) -> Any?): Double {
        var acc = 0L
        val t0 = System.nanoTime()
        for (i in 0 until ops) {
            val r = body(i)
            acc += if (r === null) 1L else System.identityHashCode(r).toLong()
        }
        val ns = System.nanoTime() - t0
        if (acc == 0L && ns < 0L) error("unreachable：只为把 acc 保持在活")
        return ns.toDouble() / ops / 1000.0
    }

    /**
     * 跑一个 bench：热身 → [TIMED_ROUNDS] 轮计时 → 取中位数打印。
     *
     * 打印格式固定成 `name / ops / median / min / max / rounds / sample`，报告里能直接抄，
     * 也方便前后两份输出逐行 diff。
     */
    private fun bench(name: String, ops: Int, sample: () -> String, body: (Int) -> Any?) {
        val note = sample()
        repeat(WARMUP_ROUNDS) { timedRound(ops / 4 + 1, body) }
        val rounds = (0 until TIMED_ROUNDS).map { timedRound(ops, body) }.sorted()
        println(
            "[bench] name=$name ops=$ops" +
                " median_us_per_op=%.1f min_us_per_op=%.1f max_us_per_op=%.1f rounds=%d sample=%s"
                .format(rounds[rounds.size / 2], rounds.first(), rounds.last(), TIMED_ROUNDS, note),
        )
    }

    // ── 样本（全部取仓库里的真实形态，别造幻影输入）────────────────────────

    /** 仓库内那份已签名清单正文（与 AgentManifestVerifyTest 同源：3 条目 / ~1.4KB）。 */
    private fun realManifest(): String {
        val f = listOf(
            File("../rootfs/agents.json"),
            File("rootfs/agents.json"),
            File("../../rootfs/agents.json"),
        ).firstOrNull { it.isFile } ?: error("找不到 rootfs/agents.json，清单解析基线无法测")
        return f.readText()
    }

    /**
     * 深度扫描出 JSON 里**第二层**的对象（根对象 = 1 层，`agents` 数组的元素 = 2 层）。
     *
     * 不用第三方解析器：本批红线是「不引入新依赖」，而这里只需要把真实条目复制放大。
     * 字符串里的花括号靠引号/转义状态跳过，否则正文字段里的 `{` 会把深度算歪。
     */
    private fun agentObjects(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in text.indices) {
            val c = text[i]
            when {
                esc -> esc = false
                inStr && c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                c == '{' -> {
                    depth++
                    if (depth == 2) start = i
                }
                c == '}' -> {
                    if (depth == 2 && start >= 0) out += text.substring(start, i + 1)
                    depth--
                }
            }
        }
        return out
    }

    /**
     * `redact()` 的真实工作对象：一行**带私有绝对路径 + Basic 凭据**的运行日志。
     *
     * 形状取自 `RunLog.log` 的实际入参形态（时间戳 + 级别 + 标签 + 异常原文），
     * 而不是空串——空串会让 6 条 `replace` 全走"无匹配"快路径，测不出替换分支的成本。
     */
    private fun redactLine(filesDir: String): String =
        "2026-10-21 16:04:11.882 ERROR ProotLauncher: 启动失败 java.io.IOException: " +
            "bind $filesDir/rootfs/usr/bin/proot 权限不足；" +
            "请求头 Authorization: Basic ZGVmYXVsdDpzZWNyZXQxMjM0NQ== 已重试 3 次；" +
            "回退路径 /data/user/0/com.example.zhengdao/files/work/x 同样不可用 passwd=root " +
            "token=abcd1234 结束"

    /** `readMessages` 摊平后的行形状：text 为主，夹 tool（带 JSON payload）与 plan。 */
    private fun partRows(messages: Int, partsPerMessage: Int): List<BaopuPartRow> =
        (0 until messages).flatMap { m ->
            (0 until partsPerMessage).map { p ->
                val kind = when {
                    p == partsPerMessage - 1 && m % 7 == 0 -> "plan"
                    p % 3 == 2 -> "tool"
                    else -> "text"
                }
                val payload = when (kind) {
                    "tool" -> """{"tool":"read_file","result":"第 ${p} 段工具输出，长度约八十字符，用于让 JSON 解析走到真实体量的字符串上而不是空对象。"}"""
                    "plan" -> """{"items":[{"text":"步骤 ${m}","status":"done"},{"text":"步骤 ${m}b","status":"pending"}]}"""
                    else -> "这是第 $m 条消息的第 $p 段正文，长度控制在几十字符，与实际会话里 assistant 一句话的体量相当。"
                }
                BaopuPartRow(
                    messageId = m.toLong(),
                    role = if (m % 2 == 0) "user" else "assistant",
                    createdAt = 1_700_000_000_000L + m,
                    kind = kind,
                    payload = payload,
                )
            }
        }

    // ── 1. 清单解析（怡然首屏 Agent 卡的数据源）────────────────────────────

    @Test
    fun `bench01_AgentManifest_parse_真实清单`() {
        val text = realManifest()
        val entries = AgentManifest.parse(text)
        assertTrue("真实清单必须解析出 >=3 条（与 AgentManifestVerifyTest 同下界）", entries.size >= 3)
        bench(
            name = "AgentManifest.parse(real ${entries.size} entries, ${text.length} chars)",
            ops = 200,
            sample = { "entries=${entries.size}" },
            body = { AgentManifest.parse(text) },
        )
    }

    /**
     * 斜率件：把清单**复制放大**到 20 条目，看解析成本随条目数怎么长。
     *
     * 为什么要这一条——`parse` 的正则是在逐字段查找里现编的（`ui/AgentManifest.kt:74`
     * 那个局部 `fun str`），所以成本应当是 **O(条目 × 字段)** 次 `Pattern.compile`。
     * 真实清单只有 3 条目 ⇒ 绝对数好看；线上清单是会发版变长的。斜率出来才谈得上
     * 判断"缓存/预编译"值不值、它够不够 Top3。
     * id 用 `synthetic-<i>` 保证互异（否则 `parse` 的逐 id 找块会命中同一块，测的就不是 20 条的工作量）。
     */
    @Test
    fun `bench02_AgentManifest_parse_斜率_20条目`() {
        val real = realManifest()
        val objs = agentObjects(real)
        assertTrue("深度扫描没拿到 agents 条目，放大样本无法构造", objs.isNotEmpty())
        val body = (0 until 20).joinToString(",\n") { i ->
            objs[i % objs.size]
                .replaceFirst(Regex("\"id\"\\s*:\\s*\"[^\"]+\""), "\"id\": \"synthetic-$i\"")
                .replaceFirst(Regex("\"name\"\\s*:\\s*\"[^\"]*\""), "\"name\": \"合成 $i\"")
        }
        val text = """{"version": 999999, "agents": [$body]}"""
        val entries = AgentManifest.parse(text)
        assertEquals("放大样本必须解析出 20 条，否则测的是别的东西", 20, entries.size)
        assertEquals("id 必须互异", 20, entries.map { it.id }.distinct().size)
        bench(
            name = "AgentManifest.parse(synthetic 20 entries, ${text.length} chars)",
            ops = 200,
            sample = { "entries=${entries.size}" },
            body = { AgentManifest.parse(text) },
        )
    }

    // ── 2b. 同轮 A/B：现编 vs 预编译（跨轮差不可信，见下面 KDoc）──────────────

    /**
     * 对照实现 B：与线上 `parse` **同算法**，只把现编的正则换成按 key/id 缓存的。
     *
     * ## 为什么要同轮 A/B/C，而不是"改前一轮、改后一轮"
     * 本 harness 连跑三轮实测过跨轮抖动：`parse` 这种大活儿差 ±16%，但 `Regex reused`
     * 从 0.8 µs 跳到 3.0 µs、`assemble` 从 353 µs 跳到 698 µs —— **跨轮能差 2~4 倍**。
     * 拿跨轮差当"优化收益"，测到的很可能全是 JVM 当天的状态。
     * 同轮里并排跑（同一份 JIT、同一串 GC 历史、同一个热状态）才可比。
     *
     * ## 凭什么叫等价
     * 不是嘴上说的：产出必须与 `AgentManifest.parse` **逐条相等**（`Entry` 是 data class，直接 `==`）。
     * 不等 ⇒ 后面的数字一律不作数（bench 里有 assertEquals 闸门）。
     * 照搬的坑：`\}` 的花括号转义（MagicOS/ICU 真机拒绝字符类内裸 `}`，桌面 JVM 容忍，
     * 单测发现不了 ⇒ 一个字都不许多改）。
     */
    private fun parseCachedRegex(
        text: String,
        cache: MutableMap<String, Regex>,
    ): List<AgentManifest.Entry> {
        fun rx(key: String): Regex = cache.getOrPut("field:$key") {
            Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        }
        fun blockRx(id: String): Regex = cache.getOrPut("block:$id") {
            Regex("\"id\"\\s*:\\s*\"$id\"[^\\}]*\\}")
        }
        fun str(id: String, key: String): String {
            val block = blockRx(id).find(text)?.value ?: return ""
            return rx(key).find(block)?.groupValues?.get(1) ?: ""
        }
        val ids = cache.getOrPut("ids") { Regex("\"id\"\\s*:\\s*\"([^\"]+)\"") }
            .findAll(text).map { it.groupValues[1] }.toList()
        return ids.mapNotNull { id ->
            val name = str(id, "name")
            val install = str(id, "installCmd")
            if (name.isBlank() || install.isBlank()) null
            else AgentManifest.Entry(
                id = id,
                name = name,
                desc = str(id, "desc"),
                launchCmd = str(id, "launchCmd").ifBlank { id },
                installCmd = install,
                npmPackage = str(id, "npmPackage").ifBlank { null },
                uninstallCmd = str(id, "uninstall").ifBlank { null },
            )
        }
    }

    /**
     * 对照实现 C：块**每条只找一次**（+ 复用 B 的正则缓存）。
     *
     * 这是 B 那轮 A/B 打脸之后加的一条——`Pattern.compile` 根本不是大头（缓存了只快 1.1~1.3 倍）。
     * 真正的大头是**扫描**：线上 `str(id,key)` 每次都重算 `block`，而 block 是 (text,id) 的纯函数
     * ⇒ 一条消息 6 个字段 = 6 次从文本头部开始的全文正则扫描。条目 × 6 次扫描才是那 3.5 ms。
     * C 把 block 提到每条一次（1 次扫描），语义等价性同上。
     */
    private fun parseBlockOnce(
        text: String,
        cache: MutableMap<String, Regex>,
    ): List<AgentManifest.Entry> {
        fun rx(key: String): Regex = cache.getOrPut("field:$key") {
            Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
        }
        val ids = cache.getOrPut("ids") { Regex("\"id\"\\s*:\\s*\"([^\"]+)\"") }
            .findAll(text).map { it.groupValues[1] }.toList()
        return ids.mapNotNull { id ->
            val block = cache.getOrPut("block:$id") {
                Regex("\"id\"\\s*:\\s*\"$id\"[^\\}]*\\}")
            }.find(text)?.value ?: ""
            fun field(key: String): String =
                if (block.isEmpty()) "" else rx(key).find(block)?.groupValues?.get(1) ?: ""
            val name = field("name")
            val install = field("installCmd")
            if (name.isBlank() || install.isBlank()) null
            else AgentManifest.Entry(
                id = id,
                name = name,
                desc = field("desc"),
                launchCmd = field("launchCmd").ifBlank { id },
                installCmd = install,
                npmPackage = field("npmPackage").ifBlank { null },
                uninstallCmd = field("uninstall").ifBlank { null },
            )
        }
    }

    /**
     * 三向对照：A 线上 / B 只缓存正则 / C 块只找一次。真实清单与放大清单各跑一遍。
     *
     * 这条是 T2 的**立项依据**：哪个对照真的快，才允许去改线上代码；
     * 都没快 ⇒ 那条优化就死在这里（B 就是这么被否掉的）。
     */
    @Test
    fun `bench07_AgentManifest_parse_同轮ABC`() {
        val real = realManifest()
        val objs = agentObjects(real)
        val body = (0 until 20).joinToString(",\n") { i ->
            objs[i % objs.size]
                .replaceFirst(Regex("\"id\"\\s*:\\s*\"[^\"]+\""), "\"id\": \"synthetic-$i\"")
                .replaceFirst(Regex("\"name\"\\s*:\\s*\"[^\"]*\""), "\"name\": \"H$i\"")
        }
        val twenty = """{"version": 999999, "agents": [$body]}"""
        // 名字一律 ASCII：真机日志/CI 控制台按平台默认码页（GBK）落盘，中文名在
        // test-results 的 <system-out> 里会变成乱码 ⇒ 证据不可读（实测踩过）。
        for ((label, text) in listOf("real" to real, "20x" to twenty)) {
            val cache = HashMap<String, Regex>()
            val a = AgentManifest.parse(text)
            val b = parseCachedRegex(text, cache)
            val c = parseBlockOnce(text, cache)
            assertEquals("$label：B 与线上不等价，A/B 作废", a, b)
            assertEquals("$label：C 与线上不等价，A/C 作废", a, c)
            val ops = if (label == "real") 200 else 100
            // 标签更正（T1 落地后）：这里原名 `A_prod_scan6x`，T1 之后线上实现已经是
            // block-once，"scan6x" 就成了假话。A 现在应当与 C 同档（实测确实同档），
            // B 保留的是**被同轮对照否掉的那条路**（旧算法 + 正则缓存），留着它才解释得了
            // 为什么当初的立项假设是错的。
            bench(
                name = "parse[$label] A_prod",
                ops = ops,
                sample = { "entries=${a.size}" },
                body = { AgentManifest.parse(text) },
            )
            bench(
                name = "parse[$label] B_cachedRegex",
                ops = ops,
                sample = { "entries=${b.size} regexKeys=${cache.size}" },
                body = { parseCachedRegex(text, cache) },
            )
            bench(
                name = "parse[$label] C_blockOnce",
                ops = ops,
                sample = { "entries=${c.size} regexKeys=${cache.size}" },
                body = { parseBlockOnce(text, cache) },
            )
        }
    }

    // ── 2. 单次 Pattern.compile 的单价（把 bench01/02 换算成"几次编译"用的标尺）─

    @Test
    fun `bench03_正则现编_vs_复用_单价对照`() {
        val pattern = "\"name\"\\s*:\\s*\"([^\"]*)\""
        val hay = realManifest()
        assertTrue("单价样本没命中，测的是空气", Regex(pattern).find(hay) != null)
        bench(
            name = "Regex(compile per call) + find",
            ops = 2000,
            sample = { "hits=${Regex(pattern).findAll(hay).count()}" },
            body = { Regex(pattern).find(hay) },
        )
        val pre = Regex(pattern)
        bench(
            name = "Regex reused + find",
            ops = 2000,
            sample = { "hits=${pre.findAll(hay).count()}" },
            body = { pre.find(hay) },
        )
    }

    // ── 3. 日志脱敏（每行 6 次现编正则；本批红线不许动 rootfs/**，只留数）──────

    @Test
    fun `bench04_RunLog_redact_每行`() {
        val filesDir = "/data/user/0/com.example.zhengdao/files"
        val line = redactLine(filesDir)
        val out = RunLog.redact(line, filesDir)
        // 自检：规则必须**都**命中。有一条没命中 ⇒ 样本喂错 ⇒ 计时偏低（少走了替换分支）。
        assertTrue("私有路径未替掉", out.contains("<私有目录>"))
        assertTrue("凭据未打码", out.contains("****"))
        assertTrue("filesDir 未被替换，样本与 redact 的形状不匹配", !out.contains(filesDir))
        assertTrue("passwd 未打码", !out.contains("passwd=root"))
        assertTrue("token 未打码", !out.contains("token=abcd1234"))
        bench(
            name = "RunLog.redact(1 line, 6 inline Regex)",
            ops = 3000,
            sample = { "in=${line.length}ch out=${out.length}ch" },
            body = { RunLog.redact(line, filesDir) },
        )
    }

    // ── 4. 消息装配（抱朴 refresh 每个引擎事件全量重跑的那一段）──────────────

    @Test
    fun `bench05_BaopuMessageAssembler_assemble_400行`() {
        val rows = partRows(messages = 100, partsPerMessage = 4)
        assertEquals("行数样本不对，400 行才对得上一次会话的体量", 400, rows.size)
        val out = BaopuMessageAssembler.assemble(rows)
        assertEquals("100 条消息应当装配出 100 条", 100, out.size)
        bench(
            name = "BaopuMessageAssembler.assemble(400 rows)",
            ops = 300,
            sample = { "msgs=${out.size} rows=${rows.size}" },
            body = { BaopuMessageAssembler.assemble(rows) },
        )
    }

    // ── 5. 技能头读写（技能抽屉每次开合逐文件走一遍的那两个函数）───────────────

    @Test
    fun `bench06_SkillStore_状态头读写`() {
        val body = SkillStore.draftHeader(status = "draft", uses = 3) + "\n" +
            "## 触发\n用户说要整理截图\n\n## 步骤\n1. 定位工作区\n2. 逐张改名\n"
        assertEquals("statusOf 读错，样本没命中头部", "draft", SkillStore.statusOf(body))
        assertEquals("usesOf 读错，样本没命中头部", 3, SkillStore.usesOf(body))
        bench(
            name = "SkillStore.statusOf + usesOf",
            ops = 3000,
            sample = { "status=${SkillStore.statusOf(body)} uses=${SkillStore.usesOf(body)}" },
            body = { SkillStore.statusOf(body) to SkillStore.usesOf(body) },
        )
    }

    private companion object {
        /** 热身轮：让 JIT 编热路径。太少 ⇒ 测到解释执行；太多 ⇒ 全量门禁变慢。 */
        const val WARMUP_ROUNDS = 3

        /** 计时轮：取中位数，5 轮够稳，也够便宜。 */
        const val TIMED_ROUNDS = 5
    }
}
