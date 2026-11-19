// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百六十批 · 形轨.形体参数**源码断言**（判据⑩、裁定①③、§五边界、追加①④的机器钉法）。
// 口径：剥掉注释后扫代码——注释里说"不做什么"不算证据，代码里"没有"才算。
// 只读 form 包十二份源码文本（SrcRoots 定位），零写盘。每道闸配注入自检，防"闸没牙"。
package com.example.zhengdao.baopu.state.form

import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormSourceAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/state/form/"

    private val mainFiles = listOf(
        "BodyFacts.kt", "FactsFromMd.kt", "FormTables.kt", "HumanBodySpec.kt", "SpecGuard.kt",
        "BodyFrame.kt", "GenitalModel.kt", "ZoneGuard.kt", "HeightField.kt", "BodyProxies.kt",
        "BodyState.kt", "Flexibility.kt",
    ).map { dir + it }

    /** 业务码＝除事实底常量表以外的全部（裁定①：分布字面量只许住在 FactsFromMd）。 */
    private val businessFiles = mainFiles.filter { !it.endsWith("FactsFromMd.kt") }

    /** 判据⑩黑名单：组织名与器官学名。13 区部位名（龟头/阴囊/睾丸/精索/耻骨…）是工单口径，不在内。 */
    private val anatomyTerms = listOf(
        "海绵体", "白膜", "尿道球腺", "阴茎背神经", "球海绵体肌", "提睾肌", "肉膜", "鞘膜",
        "阴茎骨", "球状腺", "尿道海绵体", "阴茎深动脉",
        "cavernos", "spongios", "tunica", "albuginea", "cremaster", "dartos",
        "bulbourethral", "Cowper", "corpus", "Buck", "Colles",
    )

    /** 裁定①：事实底盘的分布字面量不许抄进业务码（180.0 只豁免度转弧度 PI / 180.0）。 */
    private val factLiterals = listOf(
        "13.12", "131.0", "1.66", "3.71", "4.53", "4.6", "46.0",
        "116.6", "134.7", "142.2", "153.0", "159.0", "170.0",
    )

    private val degreeConstant = Regex("(?<!PI / )180\\.0")

    /** 裁定③：本单零装配，也不许抢 SceneState 这个名字。 */
    private val assemblyTokens = listOf(
        "StateSummaryWiring", "CharacterTrack", "attach(", "ZhengdaoApp", "SceneState",
    )

    /** §五边界：不做有限元/组织建模。 */
    private val mechanicsTokens = listOf("有限元", "finiteElement", "navier", "stress", "strain", "PDE")

    /** ④消费 655 既有几何件，不另造同类型。 */
    private val redeclareTokens = listOf(
        "class SegmentProxy", "interface ConvexShape", "class Ellipsoid", "class Capsule",
        "class Sphere", "class Box", "class JointLimit", "class JointChain", "object JointNames",
    )

    /** 追加④：不许内置"人类不能"清单。 */
    private val banTokens = listOf("不可能", "人类不能", "禁止", "forbiddenPose", "IMPOSSIBLE", "bannedPoses")

    /** 669 同款：模型/网络＋时钟/随机＋写盘口。 */
    private val engineTokens = listOf(
        "Provider", "Http", "http", "URL", "OkHttp", "Retrofit", "java.net",
        "Llm", "llm", "Prompt", "prompt", "apiKey", "completion", "ChatClient",
        "Random", "random", "nanoTime", "currentTimeMillis", "Instant", "LocalDate", "Thread",
        "writeText", "FileWriter", "OutputStream", "PrintWriter", "mkdir", "delete",
    )

    @Test
    fun `判据十_剥注释后不含组织名与器官学名`() {
        val code = mainFiles.joinToString("\n") { codeOf(it) }
        for (t in anatomyTerms) assertTrue("形体源码（剥注释后）不得出现解剖学术语 $t", t !in code)
    }

    @Test
    fun `判据十_注入组织名必被咬住_闸有牙自检`() {
        val injected = codeOf(mainFiles[6]) + "\nval tissue = \"海绵体\"\n"
        assertTrue("注入组织名后闸必须咬中", anatomyTerms.any { it in injected })
    }

    @Test
    fun `裁定一_业务码不许写事实分布字面量`() {
        val code = businessFiles.joinToString("\n") { codeOf(it) }
        for (t in factLiterals) assertTrue("业务码不得出现事实字面量 $t（一律经 FactSource）", t !in code)
        assertTrue("180.0 只许作度转弧度常数", !degreeConstant.containsMatchIn(code))
    }

    @Test
    fun `裁定一_注入上限字面量必被咬住_闸有牙自检`() {
        val injected = codeOf(dir + "GenitalModel.kt") + "\nval cap = 46.0\n"
        assertTrue("注入 46.0 后闸必须咬中", factLiterals.any { it in injected })
        assertTrue("注入裸 180.0 后闸必须咬中", degreeConstant.containsMatchIn(injected + "\nval x = 180.0\n"))
    }

    @Test
    fun `裁定一_事实字面量确实只住在FactsFromMd一份里`() {
        val carriers = mainFiles.filter { "131.0" in codeOf(it) }
        assertEquals("长度中位值只许有一份载体", listOf(dir + "FactsFromMd.kt"), carriers)
        assertTrue("载体里须真有上限常量", "46.0" in codeOf(dir + "FactsFromMd.kt"))
        assertTrue("载体须自带来源与时点", "事实底盘 md §四" in source(dir + "FactsFromMd.kt"))
    }

    @Test
    fun `裁定三_零装配_不碰状态机与App也不抢SceneState`() {
        val code = mainFiles.joinToString("\n") { codeOf(it) }
        for (t in assemblyTokens) assertTrue("本单零装配，源码不得出现 $t", t !in code)
    }

    @Test
    fun `裁定三_注入挂接行必被咬住_闸有牙自检`() {
        val injected = codeOf(dir + "BodyState.kt") + "\nfun wire() { track.attach(body) }\n"
        assertTrue("注入 attach( 后闸必须咬中", assemblyTokens.any { it in injected })
    }

    @Test
    fun `边界_不做有限元与组织力学`() {
        val code = mainFiles.joinToString("\n") { codeOf(it) }
        for (t in mechanicsTokens) assertTrue("本单只出参数与示意图，不得出现 $t", t !in code)
        val injected = code + "\nval stress = solveNavier()\n"
        assertTrue("注入应力解算后闸必须咬中", mechanicsTokens.any { it in injected })
    }

    @Test
    fun `交付四_消费655几何件而不另造同类型`() {
        val code = mainFiles.joinToString("\n") { codeOf(it) }
        for (t in redeclareTokens) assertTrue("不许另造 655 已有的几何件：$t", t !in code)
        val proxies = codeOf(dir + "BodyProxies.kt")
        assertTrue("代理体须 import 655 的 SegmentProxy", "import com.example.zhengdao.baopu.state.geometry.SegmentProxy" in proxies)
        assertTrue("限位须 import 655 的 JointLimit", "import com.example.zhengdao.baopu.state.geometry.JointLimit" in codeOf(dir + "Flexibility.kt"))
        val injected = proxies + "\ndata class SegmentProxy(val n: String)\n"
        assertTrue("注入同名声明后闸必须咬中", redeclareTokens.any { it in injected })
    }

    @Test
    fun `追加四_源码无人类不能硬编码`() {
        val code = mainFiles.joinToString("\n") { codeOf(it) }
        for (t in banTokens) assertTrue("不许内置\"人类不能\"清单，源码不得出现 $t", t !in code)
        val injected = codeOf(dir + "Flexibility.kt") + "\nval impossible = \"人类不能\"\n"
        assertTrue("注入禁令字面量后闸必须咬中（字符串里的也算）", banTokens.any { it in injected })
    }

    @Test
    fun `追加一_判定码不写死档位系数与边缘带`() {
        val code = codeOf(dir + "Flexibility.kt")
        for (t in listOf("0.6", "1.4", "1.15")) {
            assertTrue("档位系数/边缘带须取口径表，不得写死 $t", t !in code)
        }
        assertTrue("须从口径表取柔韧规则", "props.flexibility" in code)
        assertTrue("限位须由基准活动度乘档位系数得出", "base.high * k" in code)
        val injected = code + "\nval k = 1.4\n"
        assertTrue("注入写死系数后闸必须咬中", "1.4" in injected)
    }

    @Test
    fun `裁定一_换底只换一处_FactsFromMd只出现在默认参数一行`() {
        val hits = businessFiles.flatMap { f ->
            codeOf(f).lines().mapIndexedNotNull { i, l -> if ("FactsFromMd" in l) "$f:${i + 1}" to l else null }
        }
        assertEquals("业务码里只许有一处引用事实底实现", 1, hits.size)
        assertTrue("那一处须在 BodyState 的 load 默认参数上", hits[0].first.startsWith(dir + "BodyState.kt"))
        assertTrue("且只能是默认参数", "facts: FactSource = FactsFromMd" in hits[0].second)
        val injected = hits + ("${dir}GenitalModel.kt:9" to "val f = FactsFromMd")
        assertEquals("注入第二处引用后闸必须咬中", 2, injected.size)
    }

    @Test
    fun `引擎口_无模型网络时钟随机写盘`() {
        val code = mainFiles.joinToString("\n") { codeOf(it) }
        for (t in engineTokens) assertTrue("形体源码（剥注释后）不得出现 $t", t !in code)
        val injected = code + "\nval probe = okhttp3.OkHttpClient()\n"
        assertTrue("注入网络调用后闸必须咬中", engineTokens.any { it in injected })
    }

    @Test
    fun `防闸空转_十二份源码都真被读到且剥注释后仍有代码`() {
        assertEquals(12, mainFiles.size)
        for (f in mainFiles) {
            val code = codeOf(f)
            assertTrue("$f 剥注释后不得为空", code.length > 200)
            assertTrue("$f 须真是 form 包的源码", "package com.example.zhengdao.baopu.state.form" in code)
        }
    }

    private fun source(rel: String): String = SrcRoots.find(rel).readText()

    private fun codeOf(rel: String): String = stripComments(source(rel))

    /** 行注释/块注释整段换空格（换行保留）；字符串原样保留——禁词藏字符串里也算数。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "//" -> i = skipLine(src, i, out)
                two == "/*" -> i = skipBlock(src, i, out)
                src[i] == '"' -> i = copyString(src, i, out)
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun skipLine(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        out.append("  ")
        while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
        return i
    }

    private fun skipBlock(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        var depth = 1
        out.append("  ")
        while (i < src.length && depth > 0) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "/*" -> { depth++; out.append("  "); i += 2 }
                two == "*/" -> { depth--; out.append("  "); i += 2 }
                else -> { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
            }
        }
        return i
    }

    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 1
        out.append('"')
        while (i < src.length) {
            val c = src[i]
            out.append(c)
            if (c == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2 }
            else if (c == '"') return i + 1
            else i++
        }
        return i
    }
}