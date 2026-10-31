// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百零三批 · 法典判定层的**源级接线门禁**（派工单第 4 条：law/ 每个类名必须出现在非测试文件里，零引用即红）。
//
// ## 为什么门禁打在源码上
// `app/src/main/java/com/example/zhengdao/baopu/law/` 里每个 `internal object` 都是一条**法典判据**
// （L9／L21／L22／L24／L25／L27／L30b）。判据住在生产包里却没人调，读代码的人看不出来——
// 249 交付时"全绿但零调用"就是这个坑。276-P1 为同一件事写了 `TaskGrantWiringGateTest`，
// 本文件照它的形状做，只是对象换成法典判定层。
//
// ## 钉的七件事
//   1. **自动发现**：law/ 新增一个判定源而没进名册 ⇒ 红（不许悄悄多出一条没人调的法条）；
//   2. **已接线**：按"文件→次数"逐字登记，少一处即红（派工单判据④的机制）；
//   3. **挂账**：四格齐全——判据在哪／该接的生产点位／谁接／为什么现在接不了，空话即红；
//   4. **挂账不许隐身**：真被生产调了却还挂在未接线名册上 ⇒ 红（逼着改名册，账实相符）；
//   5. **只扫生产**：app/src/test 里的引用一律不算（负例④"门禁只扫测试"），law/ 自引也不算；
//   6. **手抄份数**：L21 那条"critical＋L21 才有豁免资格"在生产里抄了几份、抄在哪，逐字钉住——
//      多抄一份即红，开始收敛也红一次（还是逼登记）；
//   7. **假接兜底**（负例①）：谁把 EmergencyGate 登记成已接线，那条判据的手抄件就必须同时清零——
//      只加一句调用、判据还留在原地，本文件红。
//
// ## 今天的账（303 在基线 29667ed9 上取数）
// 七条判定源**全部零生产引用** ⇒ WIRED 空、BOOKED 七条。今天真在咬人的是第 3／5／6 条
// （1／2／4 防的是以后）：接线那一批把 `inspect/OpinionBudget.kt:14` 换成
// `EmergencyGate.qualifiesForExemption(...)` 时，必须同一次提交把 EmergencyGate 从 BOOKED 移到
// WIRED 并登记两处调用点——此后任何一次删调用，第 2 条当场红。
package com.example.zhengdao.baopu.law

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `law/` 在生产树里的相对路径（互引不算接线：law/ 里互相点一句，生产还是没人调）。 */
private const val LAW_PATH = "com/example/zhengdao/baopu/law/"

/** 一个源文件：路径相对 `app/src/main/java` 或 `app/src/test/java`，[code] 已剥注释。 */
private data class Source(val name: String, val raw: String) {
    val code: String get() = codeOnly(raw)
}

/**
 * 只留代码行（注释里的字面**不算**接线）。
 *
 * 与 `TaskGrantWiringGateTest` 同一口径：本仓注释里满是「某某调用形状一个字节没动」这类原话，
 * 不剥注释，一条删接线的变异能靠一句注释把门禁骗绿。
 */
private fun codeOnly(src: String): String = src
    .split("\n")
    .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
    .joinToString("\n")

class LawSourceWiringGateTest {

    // ── 名册（改这张表就是改账：必须与生产实数逐字相等） ──────────────────────

    /** 已接线的判定源。[sites]＝(相对 `app/src/main/java` 的文件, 点号引用次数)，登记即判据。 */
    private data class Wired(val entry: String, val sites: List<Pair<String, Int>>)

    /** 挂了账的判定源：四格缺一即红。 */
    private data class Booked(
        val entry: String,
        val law: String,
        val host: String,
        val owner: String,
        val blockedBy: String,
    )

    private val WIRED: List<Wired> = emptyList()

    private val BOOKED = listOf(
        Booked(
            entry = "EmergencyGate",
            law = "EmergencyGate 的 qualifiesForExemption／reasonStated（303 新补：L21 资格与理由的唯一口径）",
            host = "inspect/OpinionBudget.kt:14/:26/:29（exemptionFor 与 EmergencyExemption 构造器）、agent/OpinionWiring.kt:250（judge 第⑤格）",
            owner = "298（判决时查经验表：这两份文件正握在它手里）",
            blockedBy = "OpinionBudget.kt 在 274 定的契约隔离白名单内（OpinionIsolationTest.kt 只许它 import Normalizer/Locale），" +
                "要放行 baopu.law.EmergencyGate 得改那张白名单——而 OpinionIsolationTest.kt 也在 298 名下",
        ),
        Booked(
            entry = "DiscretionLadder",
            law = "DiscretionLadder 的 decide（L9 三级：便宜行事／先斩后奏／题请）",
            host = "agent/GuardPipeline.kt:538 的 mustAsk 决定处（第②档会免掉今天必弹的那张卡）",
            owner = "另行（2026-10-31 御裁 Q1：L9 另行立项，批号未定；303 交付报告 §7 裁定记录）",
            blockedBy = "接进去就是把 R2 的「必问」换成「先做后报」，行为变了；" +
                "RiskMatrix 的 BATCH_OVER_20 与 MAX_FILES 只是数字相同，判据不同（L9 的①档不限文件数）",
        ),
        Booked(
            entry = "ProgressivePenalty",
            law = "ProgressivePenalty 的 decide（L24 累进：记／问／拦／停，30 天窗口）",
            host = "agent/GuardPipeline.kt:433 的 verdict.rejected 分支（275 头注释原写的下一个宿主）",
            owner = "311（三司会审／累进，2026-10-31 批示：L24→311）",
            blockedBy = "生产没有同类违规计数可接：RiskMatrix 的频次升档数的是**调用**次数不是**违规**次数，" +
                "接上去等于凭空给拦截加一档",
        ),
        Booked(
            entry = "TimeoutFallback",
            law = "TimeoutFallback 的 settle（L27 超时未批⇒维持原状；二次上奏取窄口径）",
            host = "agent/RunRules.kt:227 的 confirmExpired（生产唯一「等御批」的超时线）",
            owner = "另行（2026-10-31 御裁 Q1：L27 另行立项，批号未定；边界已由 Q3 定为 >=；303 交付报告 §7 裁定记录）",
            blockedBy = "边界已不再有分歧（2026-10-31 御裁 Q3 定为 >=，与生产 RunRules.kt:227／TaskGrantGate.kt:282 的「到线即超时」同口径）。" +
                "仍拦着的是窗口与形状：生产超时线是 15 分钟／60 分钟（TaskGrantGate 的租约到期），settle 的窗写死 24 小时；" +
                "且 settle 返回完整裁决（APPROVED／REJECTED／二次上奏），生产点位只想要一个布尔",
        ),
        Booked(
            entry = "NarrowGate",
            law = "NarrowGate 的 judge（L25 窄门自执行四条件、含删除必拒、30 天 >3 关通道）",
            host = "agent/GuardPipeline.kt 的 mustAsk 之后、动作真正执行之前（生产没有「自执行」这条通道）",
            owner = "312（军令状，2026-10-31 批示：L25→312）",
            blockedBy = "生产里没有任何窄门判据可收敛（grep 窄门／自执行 在 app/src/main 零命中），" +
                "接上就是第一次给模型「不问就做」的权力",
        ),
        Booked(
            entry = "Revocation",
            law = "Revocation 的 judge（L22 撤销单五栏＋24 小时违规线）",
            host = "authz/TaskGrantWire.kt:336 的 revoke／authz/TaskGrantRevokeRow.kt:95 的 revokeNow（撤销二段确认）",
            owner = "306（步键，2026-10-31 批示：L22→306）",
            blockedBy = "撤销通道已存在但没有撤销单：judge 要五栏事实，现路径只带三元组；" +
                "补五栏＝新落盘格式，与 208 写入格式那条红线撞在同一张卡上",
        ),
        Booked(
            entry = "EmergencyClocks",
            law = "EmergencyClocks 的 informDeadlineMs／informBypass／ratifyDueAtMs（L30b 三时钟：止损 0 秒／告知 3 秒／追认 24 小时）",
            host = "agent/ConfirmFlow.kt:376 的等待循环（生产唯一「等用户」的地方）＋ inspect/NocturnalPatrol.kt:243 的投递档位",
            owner = "304（触发，2026-10-31 批示：L30b→304）",
            blockedBy = "NocturnalPatrol.kt:243 的 EMERGENCY 是**投递档位**（暗卫夜间通道），三样要件一个都没判；" +
                "把三时钟接进去要先有「先动手后追认」的执行通道，本批没有",
        ),
    )

    /** 批号 token：独立三位数字（日期 `2026-10-31` 里的 202 不算）。 */
    private val batchToken = Regex("""(?<!\d)\d{3}(?!\d)""")

    // ── 扫描工具（只认代码行、只认生产树） ────────────────────────────────────

    private fun repoFile(rel: String): File = File(rel).let { if (it.exists()) it else File("app/$rel") }

    private val lawDir = repoFile("src/main/java/com/example/zhengdao/baopu/law")
    private val mainRoot = repoFile("src/main/java")
    private val testRoot = repoFile("src/test/java")

    private fun relOf(root: File, file: File): String = file.relativeTo(root).path.replace('\\', '/')

    /** `app/src/main` 全部 Kotlin 源（判据①要的"生产"；测试树结构上就进不来）。 */
    private val mainSources: List<Source> by lazy {
        require(mainRoot.isDirectory) { "main 源目录没找到：${mainRoot.absolutePath}" }
        mainRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .map { Source(relOf(mainRoot, it), it.readText()) }
            .toList()
    }

    private val testSources: List<Source> by lazy {
        require(testRoot.isDirectory) { "test 源目录没找到：${testRoot.absolutePath}" }
        testRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name != "LawSourceWiringGateTest.kt" }
            .map { Source(relOf(testRoot, it), it.readText()) }
            .toList()
    }

    /** 一个判定源在生产里被点了几次（`Entry.` 形态），按文件给明细、law/ 自己排除。 */
    private fun refsOf(sources: List<Source>, entry: String, excludeLaw: Boolean): List<Pair<String, Int>> {
        val pat = Regex("""\b${Regex.escape(entry)}\s*\.""")
        return sources.filterNot { excludeLaw && it.name.startsWith(LAW_PATH) }
            .map { it.name to pat.findAll(it.code).count() }
            .filter { it.second > 0 }
            .sortedBy { it.first }
    }

    private fun refsInMain(entry: String): List<Pair<String, Int>> = refsOf(mainSources, entry, excludeLaw = true)

    private fun refsInTests(entry: String): Int = refsOf(testSources, entry, excludeLaw = false).sumOf { it.second }

    /** law/ 里全部判定源——**自动发现**，不靠人记得登记。 */
    private fun discovered(): List<String> = mainSources
        .filter { it.name.startsWith(LAW_PATH) }
        .flatMap { Regex("""(?m)^\s*internal object (\w+)""").findAll(it.code).map { m -> m.groupValues[1] } }
        .sorted()

    // ── 1. 名册与源码一一对上（新判据不许隐身） ───────────────────────────────

    @Test
    fun 每条法典判据要么已接线要么挂账() {
        val listed = (WIRED.map { it.entry } + BOOKED.map { it.entry }).sorted()
        assertEquals(
            "law/ 的判定源与名册不是一张表：新增一条法典判据必须同一次登记（已接线或挂账），不许让它零引用还绿灯",
            discovered(),
            listed,
        )
        assertEquals("同一条不许登记两次（两头挂＝两头都不咬人）", listed.size, listed.distinct().size)
        assertTrue("名册空了？门禁会因此全绿而一条都没判", listed.isNotEmpty())
    }

    // ── 2. 已接线：调用点逐字相等（判据④"去掉一处 ⇒ 红"的机制） ────────────────

    @Test
    fun 已接线的调用点与登记逐字相等() {
        WIRED.forEach { w ->
            assertTrue("${w.entry} 摆在已接线名册上却一个调用点都没登记", w.sites.isNotEmpty())
            assertEquals(
                "${w.entry} 的生产调用点与登记不符：少一处＝接线被删；多一处＝第二个口径冒出来了",
                w.sites.sortedBy { it.first },
                refsInMain(w.entry),
            )
        }
    }

    @Test
    fun 挂账的判据现在必须真没人调() {
        BOOKED.forEach { b ->
            assertEquals(
                "${b.entry} 已经在生产被调用，却还挂在未接线名册上：移到 WIRED 并逐字登记调用点",
                emptyList<Pair<String, Int>>(),
                refsInMain(b.entry),
            )
        }
    }

    @Test
    fun 接线之后手抄的判据必须清零() {
        // 负例①（只加调用不改行为＝假接）的防线：源级门禁只看得见"有没有人调"，
        // 看不见"调了以后有没有把原来那份手抄删掉"。所以凡登记为已接线的判定源，
        // 它那条判据在生产里就不许再有手抄件——L21 对应的正是那三处。
        if ("EmergencyGate" in WIRED.map { it.entry }) {
            val copies = mainSources.sumOf { l21Copies.findAll(it.code).count() }
            assertEquals("EmergencyGate 已登记为已接线，L21 的判据却还在别处手写：只加调用不删抄件＝假接", 0, copies)
        }
    }

    // ── 3. 挂账不许写成空话 ────────────────────────────────────────────────────

    @Test
    fun 挂账四格齐全且点名宿主与批次() {
        BOOKED.forEach { b ->
            listOf(
                b.law to "判据在哪",
                b.host to "该接的生产点位",
                b.owner to "谁接",
                b.blockedBy to "为什么现在接不了",
            ).forEach { (text, what) ->
                assertTrue("${b.entry} 的「$what」是空的：挂账不是免罪牌", text.isNotBlank())
            }
            assertTrue("${b.entry} 的宿主点位要指到具体文件：${b.host}", b.host.contains(".kt"))
            assertTrue(
                "${b.entry} 的「谁接」要点批次号（三条路：真批号／「另行」立项／挂到某批的报告）：${b.owner}",
                batchToken.containsMatchIn(b.owner),
            )
        }
    }

    @Test
    fun 批号判据不被日期糊弄() {
        // `\d{3}` 会被 2026-10-31 里的 202 满足——"待御裁（2026-10-31）"就白挂了账。
        // 批号必须是独立的数字 token：前后都不许再连数字。
        val token = batchToken
        assertFalse("日期里的三位不能当批号", token.containsMatchIn("待御裁（2026-10-31）"))
        assertFalse("四位数字里也不许截出三位", token.containsMatchIn("3033"))
        assertTrue("真批号要认：298", token.containsMatchIn("298（判决时查经验表）"))
        assertTrue("真批号要认：312", token.containsMatchIn("312（军令状）"))
    }

    // ── 4. 只扫生产，不认测试与注释与 law 自引（负例④） ────────────────────────

    @Test
    fun 零引用判的是生产不是测试() {
        // 每条挂账的判据在测试侧都被大量调用、在生产侧一次都没有：
        // 两个数取自两棵目录树，所以"零引用"说的是没人调，不是用例没写。
        BOOKED.forEach { b ->
            assertTrue(
                "${b.entry} 在 app/src/test 里一处都没调用：本门禁的口径要重看",
                refsInTests(b.entry) > 0,
            )
            assertEquals("${b.entry} 在 app/src/main 里被调了：账过期", 0, refsInMain(b.entry).sumOf { it.second })
        }
        assertTrue("扫到的生产源这么点？目录口径错了", mainSources.size > 100)
    }

    @Test
    fun 计数只认生产代码行不认注释也不认law自引() {
        // 三条骗绿的路子逐条堵死（不靠"我相信扫描逻辑"，靠这四行合成输入）。
        val synthetic = listOf(
            Source(LAW_PATH + "Other.kt", "        val x = TimeoutFallback.settle(req) // law 内互引不算接线"),
            Source("other/CommentOnly.kt", "    // TimeoutFallback.settle 在这里被提到"),
            Source("other/CommentOnly.kt", "    /* TimeoutFallback.settle 也算注释 */"),
            Source("other/Host.kt", "        val v = TimeoutFallback.settle(req)"),
        )
        assertEquals("注释与 law 自引都被算进来了", listOf("other/Host.kt" to 1), refsOf(synthetic, "TimeoutFallback", true))
        // 反向自检：不排除 law 时那句互引必须被数到（否则"排除"是假的）
        assertEquals(listOf(LAW_PATH + "Other.kt" to 1, "other/Host.kt" to 1), refsOf(synthetic, "TimeoutFallback", false))
    }

    // ── 5. L21 手抄份数（"判据散成两处"的本体） ────────────────────────────────

    private val l21Copies = Regex(
        """Severity\.CRITICAL\s*&&[^\n]*LawRef\.L21|LawRef\.L21\s*&&[^\n]*Severity\.CRITICAL""",
    )

    @Test
    fun L21资格判据在生产里的手抄份数逐字钉住() {
        val got = mainSources.map { it.name to l21Copies.findAll(it.code).count() }.filter { it.second > 0 }.sortedBy { it.first }
        assertEquals(
            "critical＋L21 这条判据在生产的抄件数变了。多一处＝又散成两处；少一处＝有人已开始收敛，" +
                "请把 EmergencyGate 从 BOOKED 移到 WIRED 并同步这张表",
            listOf(
                "com/example/zhengdao/baopu/agent/OpinionWiring.kt" to 1,
                "com/example/zhengdao/baopu/inspect/OpinionBudget.kt" to 2,
            ),
            got,
        )
        mainSources.filter { it.name.startsWith(LAW_PATH) }.forEach {
            assertFalse("law/ 里出现了手抄的字面判据：${it.name}", l21Copies.containsMatchIn(it.code))
        }
    }

    @Test
    fun L21的唯一口径住在law里且不依赖契约层() {
        val gate = mainSources.first { it.name == LAW_PATH + "EmergencyGate.kt" }
        assertTrue(
            "L21 的资格判据（critical＋L21）只许写在 EmergencyGate 这一处",
            gate.code.contains("severityIsCritical && lawRefIsL21"),
        )
        assertTrue("L21 的理由判据（说没说清）只许写在 EmergencyGate 这一处", gate.code.contains("fun reasonStated(reason: String): Boolean"))
        assertFalse(
            "判定层不许反过来依赖契约层（事实由调用方投影，与 275 的 Violation 同一口径）",
            gate.code.contains("com.example.zhengdao.baopu.inspect"),
        )
    }
}
