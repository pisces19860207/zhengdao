// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十三批 ②（2026-10-31）· **宰相匣的白名单依赖闸**。
//
// 派工单口径：`:policy` 只许依赖 **:core 与 stdlib**；禁 okhttp／openai／`android.*`／
// `baopu.kb.*`／`baopu.inspect.*`（追加裁定再加"禁具体存储 import"）。
//
// 为什么"白名单"而不是 292 那种"黑名单"：黑名单只挡已知坏东西，匣子明天多一条
// `implementation(libs.retrofit)` 照样过；白名单反过来——**不在单子上的就是违规**，
// 加依赖必须先改这张单子，而改单子得在派工单里写理由。两条闸是双保险，不互相替换。
//
// 三把尺，各自能红（每把都带合成负例自证，防止"扫了个空集"变成恒绿）：
//   ① 源码 import：每个 import 必须落在 ALLOWED 前缀内，且不得命中点名的禁止前缀。
//   ② 源码符号：代码面（剥注释、抹字面量）里不得出现实现侧的名字——同包直取不需要 import，
//      只扫 import 会被"同一个包、两个模块"绕过，所以符号位单独钉。
//   ③ 构建脚本：`dependencies { }` 里除 `implementation(project(":core"))` 之外一行都不许有。
//      只钉源码不钉构建脚本，等于"往 build.gradle 加一行就绕过白名单"。
//
// 为什么这闸放在 `:app` 的测试源集而不是 `:policy` 自己：CI 只跑 `:app:` 的任务
// （`.github/workflows/ci.yml` 全篇如此），闸放在没人跑的地方＝写给自己看的注释。
package com.example.zhengdao.baopu

import com.example.zhengdao.law.LawIo
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PolicyBoundaryGateTest {

    // ── 尺①：import 白名单 ─────────────────────────────────────────────────

    /** 允许出现在 `:policy` 源码里的 import 前缀（**闭集**：不在这里就是违规）。 */
    private val allowedPrefixes = listOf(
        // stdlib（Kotlin ＋ JDK；`java.io`／`java.net`／`java.nio` 都不在单子上的那一边）
        "kotlin.",
        "java.lang.",
        "java.util.",
        "java.time.",
        "java.math.",
        "java.text.",
        // 唯一允许的模块依赖：:core（目录真源／RunLog／PathGuard 那一层）
        "com.example.zhengdao.core.",
    )

    /** 点名的禁止前缀 → 违的是哪一条（红字要说清，别让"不在白名单"含糊带过）。 */
    private val bannedPrefixes = listOf(
        "网络" to "okhttp3.",
        "网络" to "com.openai.",
        "网络" to "java.net.",
        "Android 运行时" to "android.",
        "AndroidX（含 Room/Compose/WorkManager）" to "androidx.",
        "具体存储（java.io）" to "java.io.",
        "资料库" to "com.example.zhengdao.baopu.kb.",
        "体检／判据取数层" to "com.example.zhengdao.baopu.inspect.",
        "工具与学习器（:app 侧）" to "com.example.zhengdao.baopu.agent.",
        "终端" to "com.example.zhengdao.terminal.",
        "终端上游" to "com.termux.",
        "界面" to "com.example.zhengdao.ui.",
        "具体账本实现" to "com.example.zhengdao.baopu.DecisionLedger",
    )

    /** 纯函数：一份源码 ⇒ 违规清单（正例与合成负例共用同一把尺）。 */
    private fun importViolations(rel: String, src: String): List<String> {
        val found = mutableListOf<String>()
        src.lineSequence().forEachIndexed { idx, raw ->
            val line = raw.trim()
            if (!line.startsWith("import ")) return@forEachIndexed
            val sym = line.removePrefix("import").trim()
            val where = "$rel:${idx + 1}"
            val hit = bannedPrefixes.firstOrNull { (_, p) -> sym.startsWith(p) }
            if (hit != null) {
                found += "$where 引入禁止依赖（${hit.first}）：$sym"
            } else if (allowedPrefixes.none { sym.startsWith(it) }) {
                found += "$where 引入白名单外的依赖：$sym（白名单：${allowedPrefixes.joinToString("、")}）"
            }
        }
        return found
    }

    @Test
    fun `宰相匣的每个import都在白名单内`() {
        val violations = policySources().flatMap { (rel, src) -> importViolations(rel, src) }
        assertTrue(
            "`:policy` 的依赖白名单被打破：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `白名单闸自己会红_合成负例逐条命中`() {
        // 派工单判据②：塞一个 `import okhttp3` ⇒ 红。这里用内存夹具跑同一把尺，
        // 不往生产源码里插脏字（插了再删＝给真文件留一次无意义的写入痕迹）。
        val drills = listOf(
            "import okhttp3.OkHttpClient" to "网络",
            "import com.openai.client.OpenAIClient" to "网络",
            "import java.net.HttpURLConnection" to "网络",
            "import android.content.Context" to "Android 运行时",
            "import androidx.room.Dao" to "AndroidX（含 Room/Compose/WorkManager）",
            "import java.io.File" to "具体存储（java.io）",
            "import com.example.zhengdao.baopu.kb.KnowledgeBase" to "资料库",
            "import com.example.zhengdao.baopu.inspect.Opinion" to "体检／判据取数层",
            "import com.example.zhengdao.baopu.agent.LearnWiring" to "工具与学习器（:app 侧）",
            "import com.example.zhengdao.baopu.DecisionLedger" to "具体账本实现",
            "import com.google.gson.Gson" to null, // 白名单外，但不是点名的坏东西
        )
        drills.forEach { (line, why) ->
            val v = importViolations("Drill.kt", "package a\n$line\nobject A")
            assertTrue("演练行没被抓出来：$line", v.isNotEmpty())
            if (why != null) {
                assertTrue("抓出来了但没说清违的是哪条（$why）：$v", v.first().contains(why))
            }
        }
        // 白名单内的良性 import 不许误伤
        assertTrue(
            "白名单内的 import 被误杀",
            importViolations(
                "Drill.kt",
                "import kotlin.math.pow\nimport java.util.Locale\nimport com.example.zhengdao.core.RunLog",
            ).isEmpty(),
        )
    }

    // ── 尺②：代码面的实现侧符号（同包直取绕不过 import）──────────────────────

    /**
     * 代码面禁词：这些名字**不需要 import**（同包）就能在 `:policy` 里直取。
     * 逐条都是派工单点名的方向：具体账本、具体存储、org.json、Android 上下文。
     */
    private val bannedSymbols = listOf(
        "DecisionLedger", // 293 ③：宰相只许认 LedgerPort
        "RoyalDecisionLedger",
        "SelfRulingLedger",
        "SharedPreferences",
        "getSharedPreferences",
        "BaopuDb",
        "JSONObject",
        "org.json",
        "context", // 小写也钉：`private val context` 这种字段一样是把 Android 拉进匣子
    )

    private fun symbolViolations(rel: String, src: String): List<String> =
        LawIo.scanCodeLine(rel, src, bannedSymbols.associateWith { Regex("""\b${Regex.escape(it)}\b""") })
            .map { "${it.where()} 代码面出现禁止符号 ${it.api}" }

    @Test
    fun `宰相匣不认具体账本也不认存储`() {
        val violations = policySources().flatMap { (rel, src) -> symbolViolations(rel, src) }
        assertTrue(
            "`:policy` 里出现了实现侧的名字（同包直取，import 闸挡不住）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `符号闸自己会红_同包直取也算`() {
        val v = symbolViolations(
            "Drill.kt",
            "object A { fun w(d: DecisionDraft) = DecisionLedger.record(d) != null }",
        )
        assertTrue("同包直取 `DecisionLedger` 没被抓出来：$v", v.isNotEmpty())
        // 注释与文案里出现不算违规（判据判的是代码面，不是给人看的字）
        assertTrue(
            "注释里的名字被误杀",
            symbolViolations("Drill.kt", "// 说明：DecisionLedger 在 :app\n/* JSONObject 也不许 */").isEmpty(),
        )
        assertTrue(
            "字符串文案里的名字被误杀",
            symbolViolations("Drill.kt", "val s = \"请打开 DecisionLedger 页面\"").isEmpty(),
        )
    }

    // ── 尺③：构建脚本（只钉源码＝给绕过留门）───────────────────────────────

    /** `dependencies { }` 里唯一允许的依赖声明（闭集）。 */
    private val allowedDeclaredDeps = listOf("""implementation(project(":core"))""")

    private val coreDep = """implementation(project(":core"))"""

    private fun depLines(gradle: String): List<String> {
        val body = gradle.substringAfter("dependencies {", "")
        if (body.isEmpty()) return emptyList()
        return body.lineSequence()
            .map { it.trim() }
            .takeWhile { it != "}" }
            .filter { it.isNotEmpty() && !it.startsWith("//") }
            .toList()
    }

    private fun gradleViolations(gradle: String): List<String> {
        val deps = depLines(gradle)
        val found = deps.filter { it !in allowedDeclaredDeps }
            .map { "构建脚本里的依赖声明不在白名单：$it" }
            .toMutableList()
        // 反向依赖只判**代码面**：`policy/build.gradle.kts` 的说明里就写着「本模块不许
        // project(":app")」，对整份原文做 contains 会把这句人话判成违规。口径与 LawIo.codeOnly
        // 一致——判据判代码面，不判给人看的字（否则第一条正例自己就先红，闸门变成狼来了）。
        val codeFace = gradle.lineSequence()
            .map { it.trim() }
            .filter { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
        if (codeFace.any { it.contains("""project(":app")""") }) {
            found += "`:policy` 反向依赖 :app（依赖方向只能是 :app → :policy → :core）"
        }
        if (coreDep !in deps) found += "`:policy` 没声明 $coreDep（正面清单要能证明它确实只引这一条）"
        return found
    }

    @Test
    fun `构建脚本只许一条依赖`() {
        val violations = gradleViolations(PolicyPaths.buildScript())
        assertTrue(
            "`:policy/build.gradle.kts` 的依赖被加宽：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `构建脚本闸自己会红`() {
        val clean = "plugins {\n}\n\ndependencies {\n    $coreDep\n}\n"
        assertTrue("干净的构建脚本被判红：${gradleViolations(clean)}", gradleViolations(clean).isEmpty())
        // 禁令写在注释里不算违规（否则 `policy/build.gradle.kts` 的说明自己就会被判红）
        assertTrue(
            "注释里的一句禁令被判成反向依赖：",
            gradleViolations("dependencies {\n    // 本模块不许 project(\":app\")\n    $coreDep\n}\n").isEmpty(),
        )
        listOf(
            "implementation(libs.okhttp)" to "白名单",
            "implementation(\"com.squareup.okhttp3:okhttp:4.12.0\")" to "白名单",
            "api(project(\":app\"))" to "反向依赖",
        ).forEach { (line, keyword) ->
            val dirty = "dependencies {\n    $coreDep\n    $line\n}\n"
            val v = gradleViolations(dirty)
            assertTrue("塞进 `$line` 却没红", v.isNotEmpty())
            assertTrue("红了但没说到点上（$keyword）：$v", v.any { it.contains(keyword) })
        }
    }

    // ── 反空转下限：扫不到文件＝这条闸恒绿，必须先炸 ─────────────────────────

    @Test
    fun `宰相匣不是空的`() {
        val all = policySources()
        assertTrue(":policy 主源码一个文件都没有（搬空了？cwd=${File(".").absolutePath}）", all.isNotEmpty())
        val names = all.map { (rel, _) -> rel.substringAfterLast('/') }.toSet()
        listOf(
            "HarmonyMediator.kt",
            "MediatorJudge.kt",
            "ReframeFilter.kt",
            "PatienceProfile.kt",
            "MediatorWiring.kt",
            "DecisionDraft.kt",
            "LedgerPort.kt",
        ).forEach { assertTrue(":policy 里少了 $it（扫描对象变了要同步改这里）", it in names) }
        val lines = all.sumOf { (_, src) -> LawIo.codeOnly(src).count { c -> c == '\n' } }
        assertTrue("宰相匣代码面只有 $lines 行，扫它等于没扫", lines >= 800)
    }

    /** `:policy` 主源码（仓根相对路径 to 原文）；读不到就抛，不许空集。 */
    private fun policySources(): List<Pair<String, String>> =
        PolicyPaths.sources().map { f -> PolicyPaths.rel(f.name) to f.readText(Charsets.UTF_8) }
}
