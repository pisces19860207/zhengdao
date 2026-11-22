// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百九十批 · 形轨.方位可达：源码断言（闸有牙：剥注释后扫代码，注入自检钉住闸本身）。
package com.example.zhengdao.baopu.state.bearing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BearingSourceAssertTest {

    private fun bearingSources(): List<Pair<String, String>> {
        val dir = File("src/main/java/com/example/zhengdao/baopu/state/bearing")
        val fallback = File("../app/src/main/java/com/example/zhengdao/baopu/state/bearing")
        val root = if (dir.isDirectory) dir else fallback
        return root.listFiles { f -> f.isFile && f.name.endsWith(".kt") }.orEmpty()
            .map { it.name to it.readText() }
    }

    /** 与 GateIo/check-criteria-gates 同口径：注释替空格，行号不变。 */
    private fun stripComments(src: String): String {
        val out = StringBuilder()
        var i = 0
        var inStr = false
        var inRaw = false
        var inLine = false
        var inBlock = false
        while (i < src.length) {
            val three = src.startsWith("\"\"\"", i)
            if (inRaw) {
                if (three) { inRaw = false; out.append("\"\"\""); i += 3; continue }
                out.append(src[i]); i++; continue
            }
            if (inLine) {
                if (src[i] == '\n') { inLine = false; out.append('\n') } else out.append(' ')
                i++; continue
            }
            if (inBlock) {
                if (src.startsWith("*/", i)) { inBlock = false; out.append("  "); i += 2; continue }
                out.append(if (src[i] == '\n') '\n' else ' '); i++; continue
            }
            if (inStr) {
                if (src[i] == '\\' && i + 1 < src.length) { out.append(src.substring(i, i + 2)); i += 2; continue }
                if (src[i] == '"') inStr = false
                out.append(src[i]); i++; continue
            }
            if (three && !inBlock) { inRaw = true; out.append("\"\"\""); i += 3; continue }
            if (src[i] == '"') { inStr = true; out.append('"'); i++; continue }
            if (src.startsWith("//", i)) { inLine = true; i += 2; continue }
            if (src.startsWith("/*", i)) { inBlock = true; i += 2; continue }
            out.append(src[i]); i++
        }
        return out.toString()
    }

    private fun violationsInCode(code: String, banned: List<String>): List<String> =
        banned.filter { code.contains(it) }.map { "含『$it』" }

    private fun violations(banned: List<String>): List<String> {
        val hits = ArrayList<String>()
        for ((name, src) in bearingSources()) {
            for (h in violationsInCode(stripComments(src), banned)) hits.add("$name $h")
        }
        return hits
    }

    @Test
    fun 断言_包内零随机零时钟零联网零写盘() {
        val hits = violations(
            listOf(
                "kotlin.random", "Random(", "currentTimeMillis", "nanoTime", "Instant",
                "LocalDateTime", "Thread.sleep", "OkHttpClient", "Http", "URL(",
                "File(", "writeText", "outputStream",
            ),
        )
        assertTrue("确定性四闸被碰：$hits", hits.isEmpty())
    }

    @Test
    fun 断言_无积分器无连续求解无步进() {
        val hits = violations(
            listOf(
                "integrator", "integrate(", "timestep", "time_step", "deltatime",
                "fun step(", ".step(", "dt =", "dt:", "simulate(", "thread.sleep",
            ),
        )
        assertTrue("无积分器闸被碰：$hits", hits.isEmpty())
    }

    @Test
    fun 断言_零装配_不碰状态机与App() {
        val hits = violations(listOf("ZhengdaoApp", "StateSummaryWiring", "CharacterTrack", "attach(", "SceneState"))
        assertTrue("零装配闸被碰：$hits", hits.isEmpty())
    }

    @Test
    fun 断言_判据不读事实底_无FactSource依赖() {
        val hits = violations(listOf("FactSource", "FactsFromMd"))
        assertTrue("判据读事实底：$hits", hits.isEmpty())
    }

    @Test
    fun 断言_消费655不另造几何原语() {
        val hits = violations(
            listOf(
                "object Gjk", "class Gjk", "object Epa", "class Epa",
                "data class Capsule", "data class Sphere", "data class Ellipsoid", "data class Box",
                "class JointChain", "data class JointLimit", "object Reachability", "object PoseValidity",
            ),
        )
        assertTrue("另造 655 同类件：$hits", hits.isEmpty())
    }

    @Test
    fun 判据五_包内无子区清单_枚举唯一来源只有SubzoneSource一处() {
        val banned = listOf("背缘", "腹缘", "侧缘", "颈缘", "上极", "下极", "dorsal", "ventral", "upper_pole", "lower_pole")
        val hits = violations(banned)
        assertTrue("子区枚举出现第二份来源：$hits", hits.isEmpty())
        val definers = bearingSources().filter { (_, src) ->
            stripComments(src).contains("fun of(zoneId")
        }.map { it.first }
        assertEquals("子区枚举定义点必须唯一（SubzoneSource）", listOf("SubzoneSource.kt"), definers)
    }

    @Test
    fun 判据五_子区缝今日如实落地_恒空不假装() {
        val src = bearingSources().first { it.first == "SubzoneSource.kt" }.second
        val code = stripComments(src)
        assertTrue("SubzoneSource.of 未按今日口径返回空表（775 未落地）", code.contains("= emptyList()"))
    }

    @Test
    fun 判据六_档位常量出身声明_工程假设不当事实底盘() {
        for ((tier, spec) in BearingGeometry.TIERS) {
            assertTrue("$tier 的 source 缺工程假设标记：${spec.source}", spec.source.startsWith("工程假设○"))
            assertTrue("$tier 的 source 未声明非事实底盘：${spec.source}", spec.source.contains("非事实底盘"))
        }
        val source = BearingGeometry.UTIL_SOURCE
        assertTrue("利用率阈值出身缺工程假设标记", source.startsWith("工程假设○"))
        assertTrue("利用率阈值出身未声明非事实底盘", source.contains("非事实底盘"))
        assertTrue("接触容差/豁免守卫未标工程口径", BearingGeometry.CONTACT_DEPTH_TOL >= 0.0)
    }

    @Test
    fun 防闸空转_五份主源都真被读到且剥注释后仍有代码() {
        val files = bearingSources().map { it.first }
        for (expected in listOf("BearingTypes.kt", "SubzoneSource.kt", "BearingGeometry.kt", "BearingSampler.kt", "BearingReach.kt")) {
            assertTrue("缺主源 $expected", files.contains(expected))
        }
        for ((name, src) in bearingSources()) {
            val code = stripComments(src).trim()
            assertTrue("$name 剥注释后没有代码（闸空转）", code.length > 200)
        }
    }

    @Test
    fun 闸有牙自检_注入积分器与子区清单必被咬住() {
        val clean = violationsInCode(stripComments("package x\nfun judge(x: Int) = x\n"), listOf("fun step(", "integrate(", "上极"))
        assertTrue("自检前提：干净夹具不该报红", clean.isEmpty())
        val dirty = violationsInCode(
            stripComments("package x\nfun step(x: Int): Int = x\nval dt: Double = 0.0\nval subzones = listOf(\"上极\", \"下极\")\n"),
            listOf("fun step(", "integrate(", "上极"),
        )
        assertTrue("注入积分器未被咬住：$dirty", dirty.size >= 2)
        assertTrue("注入子区名未被咬住", dirty.any { it.contains("上极") })
    }
}
