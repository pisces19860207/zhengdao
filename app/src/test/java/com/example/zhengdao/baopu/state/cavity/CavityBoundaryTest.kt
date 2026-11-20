// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十六批 · 边界与确定性单测（判据④⑤⑥＋补条 B/D）。
// 扫描设施复用 253 批的只读探针 GateIo；判据词表放在本文件，不外溢。
package com.example.zhengdao.baopu.state.cavity

import com.example.zhengdao.baopu.constitution.GateIo
import com.example.zhengdao.baopu.state.geometry.Environment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 判据④无积分器、判据⑤不动 655/660、判据⑥确定性，外加「ε 只在配置」与依赖面收窄。 */
class CavityBoundaryTest {

    private val cavity = "src/main/java/com/example/zhengdao/baopu/state/cavity"
    private val geometryRoot = "src/main/java/com/example/zhengdao/baopu/state/geometry"
    private val formRoot = "src/main/java/com/example/zhengdao/baopu/state/form"

    private val integrator = mapOf(
        "dt 时间增量" to Regex("""\bdt\b"""),
        "step 调用" to Regex("""\bstep\s*[(.]"""),
        "时间推进动词" to Regex("""\b(advance|tick|integrate|evolve|simulate)\s*\("""),
        "速度加速度状态" to Regex("""\b(val|var)\s+\w*(velocity|accel\w*|speed)\w*\s*:""", setOf(RegexOption.IGNORE_CASE)),
        "帧与历时字段" to Regex("""\b(elapsedTime|timeStep|frameIndex|tickCount|stepCount)\b""", setOf(RegexOption.IGNORE_CASE)),
    )

    private val noise = mapOf(
        "随机" to Regex("""\bRandom\b|Math\.random\(|\.shuffled\(""", setOf(RegexOption.IGNORE_CASE)),
        "时钟" to Regex("""\b(Instant|LocalDate|LocalDateTime|ZonedDateTime|Clock|System\.currentTimeMillis|nanoTime)\b"""),
        "反射" to Regex("""::class\.|java\.lang\.reflect|Class\.forName"""),
        "文件与网络" to Regex("""\bFile\(|java\.io|java\.net|Socket\(|URL\("""),
        "三方物理几何库" to Regex("""\b(bullet|jbullet|dyn4j|physx|box2d|joml|vecmath|jme3|ode4j)\b""", setOf(RegexOption.IGNORE_CASE)),
    )

    private val shapeCreep = mapOf(
        // 只抓「继承 ConvexShape 的新凸体」：构造参数或返回值里挂 655 原语正是本单要求的复用写法，不算 creep。
        "自定义凸体" to Regex("""\b(class|object)\s+\w+\s*(\([^()]*\))?\s*:\s*[^;{]*\bConvexShape\b\s*(?=[,{]|\r?\n)"""),
        "重定义 655 类型" to Regex("""\b(object|class|enum class|data class)\s+(Gjk|Epa|PoseValidity|Reachability|SegmentProxy|Environment|BodyProxies|ConvexShape)\b"""),
    )

    private fun flattened(root: String): List<Pair<String, String>> =
        GateIo.kotlinSources(root).map { it.first to GateIo.stripComments(it.second).lines().joinToString(" ") }

    @Test
    fun `判据四_腔隙包里没有积分器与时间推进`() {
        val found = GateIo.scanTree(cavity, integrator)
        assertTrue("本单只做几何与准静态求解，不许出现时间推进：$found", found.isEmpty())
    }

    @Test
    fun `交付五_落定只按包围盒顶面不许叫GJK与EPA`() {
        val src = GateIo.read("$cavity/GravityContact.kt")
        val re = Regex("""\b(Gjk|Epa)\.""")
        assertTrue(
            "落点取已落定邻体的包围盒顶面就能证明不向下穿透，不必叫 655 判定；" +
                "而且 655 GJK 在退化互穿位形的迭代上限路径上有潜在越界（见交付报告残留），准静态解算不该踩它：" +
                re.findAll(src).map { it.value }.toList(),
            re.findAll(src).none(),
        )
    }

    @Test
    fun `补条B_扫描带词边界_不误伤width与idleDay但真违规照抓`() {
        val lookAlike = """
            class LookAlike {
                val widthCm = 1.0
                val idleDays = 3
                val midHeight = 2.0
                fun spreadSpheres(n: Int, stepCm: Double) = n + idleDays
                fun hideIt(idle: Int) = widthCm
            }
        """.trimIndent()
        assertTrue("误红比漏红更浪费：$lookAlike", GateIo.scanLines("lookalike.kt", lookAlike, integrator).isEmpty())
        val real = "val dt = 0.016\n        step(dt)\n        var velocity: Double = 0.0"
        assertEquals("同名判据要真有牙：三条都得抓", 3, GateIo.scanLines("bad.kt", real, integrator).size)
    }

    @Test
    fun `判据五_655与660被本单消费的入口签名一字不改`() {
        val locks = listOf(
            "$geometryRoot/Epa.kt" to "fun penetration(a: ConvexShape, b: ConvexShape): EpaResult?",
            "$geometryRoot/Gjk.kt" to "fun distance(a: ConvexShape, b: ConvexShape): GjkResult",
            "$geometryRoot/PoseValidity.kt" to "val amountMilli: Int",
            "$geometryRoot/PoseValidity.kt" to "enum class ViolationKind { SELF_COLLISION, GROUND_PENETRATION, SAND_PENETRATION, LIMIT_EXCEEDED }",
            "$geometryRoot/PoseValidity.kt" to "data class Environment(val groundYCm: Double, val sandYCm: Double)",
            "$geometryRoot/Reachability.kt" to "enum class State { REACHABLE, UNREACHABLE, UNKNOWN }",
            "$geometryRoot/GeometryNotes.kt" to """const val NOT_MEASUREMENT = "模拟非测量"""",
            "$formRoot/BodyProxies.kt" to "val all: List<SegmentProxy> get() = segments + genital",
            "$formRoot/BodyState.kt" to "val proxies: BodyProxies,",
            "$formRoot/BodyState.kt" to "val jointLimits: List<JointFlexLimit>,",
        )
        for ((rel, line) in locks) {
            assertTrue("686 不许改动既有口径，$rel 缺：$line", Regex(Regex.escape(line)).containsMatchIn(GateIo.read(rel)))
        }
    }

    @Test
    fun `判据五_655与660的代码不许反向引用686`() {
        val against = mapOf("反向引用 686 或 cavity" to Regex("""\b686\b|[Cc]avity"""))
        val found = GateIo.scanTree(geometryRoot, against) + GateIo.scanTree(formRoot, against)
        assertTrue("686 是新叶子包，不许把名字种回 655/660：$found", found.isEmpty())
    }

    @Test
    fun `判据一_不新增形状原语也不重定义655的类型`() {
        val found = GateIo.scanTree(cavity, shapeCreep)
        assertTrue("四类代理体必须复用 655 原语：$found", found.isEmpty())
        val lookAlike = """
            data class OrganProxy(val id: String, val kind: ProxyKind, val shape: ConvexShape) {
            private fun shifted(shape: ConvexShape, d: Vec3): ConvexShape = when (shape) {
        """.trimIndent()
        val innocent = GateIo.scanLines("lookalike.kt", lookAlike, shapeCreep)
        assertTrue("复用 655 原语的字段与返回值不是新凸体，不许误红：$innocent", innocent.isEmpty())
        val real = """
            class FakeHull : ConvexShape {
            class Fake(val s: String) : ConvexShape, Other {
            class Wrapped : Base(), ConvexShape {
        """.trimIndent()
        assertEquals("同名判据要真有牙：三种继承写法都得抓", 3, GateIo.scanLines("bad.kt", real, shapeCreep).size)
        assertEquals("代理体就四类", 4, ProxyKind.values().size)
        assertEquals("法线就六档", 6, NormalBucket.values().size)
        assertEquals("接触点就四档", 4, ContactBucket.values().size)
    }

    @Test
    fun `补条D_epsilon与容差不许进任何函数签名`() {
        val re = Regex(
            """fun\s+[\w.]+\s*\([^)]*\b(eps|epsilon|tol|tolerance|threshold|cap|rounds|samples)\w*\s*:""",
            setOf(RegexOption.IGNORE_CASE),
        )
        val found = flattened(cavity).flatMap { (rel, text) -> re.findAll(text).map { "$rel → ${it.value.take(90)}" }.toList() }
        assertTrue("ε／上限／轮次只能住在 CavityTolerances，不许当调用参数漂来漂去：$found", found.isEmpty())
    }

    @Test
    fun `补条D_epsilon常数全仓只在CavityTolerances出现一次`() {
        val re = mapOf("ε 字面量" to Regex("""1e-3|1\.0E-3|\b0\.001\b"""))
        val found = GateIo.scanTree(cavity, re).filterNot { it.relPath.endsWith("CavityTolerances.kt") }
        assertTrue("同一口径不许在别处再写一遍：$found", found.isEmpty())
    }

    @Test
    fun `零随机零时钟零反射零写盘零三方物理库`() {
        val found = GateIo.scanTree(cavity, noise)
        assertTrue("几何推算必须可复现且不碰外部世界：$found", found.isEmpty())
    }

    @Test
    fun `依赖面收窄_只import自家state与669相邻表与kotlin标准库`() {
        val re = Regex("""^import\s+([\w.]+)""", setOf(RegexOption.MULTILINE))
        val allowed = listOf(
            "com.example.zhengdao.baopu.state.",
            "com.example.zhengdao.baopu.body.AnatomyZone",
            "kotlin.",
        )
        val found = GateIo.kotlinSources(cavity).flatMap { (rel, src) ->
            re.findAll(GateIo.stripComments(src)).map { it.groupValues[1] }.filter { i -> allowed.none { i.startsWith(it) } }.map { "$rel → $it" }.toList()
        }
        assertTrue("686 的 import 越界：$found", found.isEmpty())
    }

    @Test
    fun `判据六_三量与腔隙与落定与四项全链路两跑逐字节一致`() {
        val ps = CavityFixtures.fromBody()
        val anatomy = CavityFixtures.anatomyZones()
        val env = Environment(0.0, 0.5)
        val (chain, pose) = CavityFixtures.bodyChain()
        val anchors = CavityFixtures.bodyProxies().anchors
        val adjacency = CavityFixtures.bodyAdjacent(ps, anatomy)
        fun snapshot() = listOf(
            Broadphase.fine(ps).toString(),
            ContactProbe.readAll(ps).toString(),
            CavityMap.of(ps, anatomy).toString(),
            GravityContact.settle(ps, env).toString(),
            GravityContact.restProxies(ps, env).toString(),
            ProxyLegality.check(ps, env, chain, pose, anchors, adjacency).toString(),
        )
        val one = snapshot()
        val two = snapshot()
        assertEquals("同输入两次必须逐字节一致", one, two)
        assertTrue("快照不许是空转：${one.map { it.length }}", one.all { it.length > 100 })
    }
}
