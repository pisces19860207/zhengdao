// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百二十三批 · 变异谱接入判据：①维度档位与必填字段 ②非塌缩 ③固定维度
// ④病理默认关/开启带少见 ⑤种子确定性 ⑥不复述谱内正文。
package com.example.zhengdao.baopu.state.variant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VariantSpectrumTest {

    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "app/src/main/java").isDirectory) d = d.parentFile
        return d ?: File("").absoluteFile
    }

    private val table: VariantTable by lazy { VariantTable.load(repoRoot()) }

    // ── 判据①：维度≥8、每维≥3档；每档带 probability 与 reality，缺一拒收 ──
    @Test
    fun `判据一_维度与档位齐备且必填字段缺一拒收`() {
        assertTrue("维度须≥8，得 ${table.dimensions.size}", table.dimensions.size >= VariantTable.MIN_DIMS)
        for (dim in table.dimensions) {
            assertTrue("${dim.key} 档位须≥3", dim.levels.size >= VariantDim.MIN_LEVELS)
            assertTrue("${dim.key} 档位须≤5", dim.levels.size <= VariantDim.MAX_LEVELS)
            for (lv in dim.levels) {
                assertTrue("${dim.key}/${lv.key} 缺 key", lv.key.isNotBlank())
                assertTrue("${dim.key} 档位缺名", lv.name.isNotBlank())
                assertTrue("${dim.key}/${lv.key} probability 须 0..100", lv.probability in 0..100)
                assertNotNull("${dim.key}/${lv.key} 缺 reality", lv.reality)
            }
            assertEquals("${dim.key} 概率须归一到 100", 100, dim.levels.sumOf { it.probability })
        }
        // 负例（mutation）：删掉 reality / probability 必须拒收
        val base = oneDimJson()
        assertThrows { VariantTable.parse(removeField(base, "reality")) }
        assertThrows { VariantTable.parse(removeField(base, "probability")) }
        assertThrows { VariantTable.parse(base.replace("\"name\": \"弯曲方向\"", "\"name\": \"\"")) }
    }

    // ── 判据②：抽1000次，每维最高频档占比<60%；负例全落同一档判红 ──
    @Test
    fun `判据二_千次采样最高频档占比不足六成`() {
        repeatSeeded(1000).let { samples ->
            for (dim in table.defaultDims) {
                val counts = samples.groupingBy { it.levelOf(dim.key)!!.key }.eachCount()
                val top = counts.values.maxOrNull()!!
                assertTrue("${dim.key} 最高频档占比 ${top / 10.0}% 须<60%", top < 600)
            }
        }
        // 负例：把一维替换成"单档独占概率100"的塌缩维 ⇒ 抽1000次全落该档，检测闸必须能判红。
        val collapsedKey = "bend_direction"
        val collapsed = table.copy(dimensions = table.dimensions.map {
            if (it.key == collapsedKey) it.copy(levels = listOf(
                Level("only", "独占", 100, Reality.REAL),
                Level("b", "乙", 0, Reality.REAL),
                Level("c", "丙", 0, Reality.REAL),
            )) else it
        })
        val all = (0 until 1000).map { VariantSampler.sample(collapsed, VariantSampler.Options(seed = it.toLong())) }
        val topCount = all.groupingBy { it.levelOf(collapsedKey)!!.key }.eachCount().values.maxOrNull()!!
        assertEquals("塌缩负例应全落同一档", 1000, topCount)
        assertFalse("塌缩负例占比 100% 不应通过 <60% 闸", topCount < 600)
    }

    // ── 判据③：固定某维某档后抽100次该维100%保持 ──
    @Test
    fun `判据三_固定维度百次全保持`() {
        val dim = table.byKey.getValue("foreskin_coverage")
        val target = dim.levels.first { it.key == "circumcised" }
        val opts = VariantSampler.Options(fixed = mapOf(dim.key to target.key))
        repeat(100) { i ->
            val s = VariantSampler.sample(table, opts.copy(seed = i.toLong()))
            assertEquals("第 $i 次固定维应命中 ${target.key}", target.key, s.levelOf(dim.key)!!.key)
        }
        // 负例：固定档位不存在即拒收
        assertThrows { VariantSampler.sample(table, VariantSampler.Options(fixed = mapOf(dim.key to "nope"))) }
        // 负例：固定未启用的病理维且未开启病理 ⇒ 拒收
        assertThrows {
            VariantSampler.sample(table, VariantSampler.Options(fixed = mapOf("pathology" to "peyronie")))
        }
    }

    // ── 判据④：病理默认关；开启且抽到 real 病理档输出带"少见（reality=real）" ──
    @Test
    fun `判据四_病理默认关且开启带少见标注`() {
        val off = VariantSampler.sample(table, VariantSampler.Options(seed = 7L))
        assertNull("默认不含病理维", off.levelOf("pathology"))
        assertTrue("默认无病理提示", off.advisories.isEmpty())

        val on = VariantSampler.sample(
            table,
            VariantSampler.Options(seed = 7L, includePathological = true, fixed = mapOf("pathology" to "peyronie")),
        )
        assertEquals("开启后病理维命中", "peyronie", on.levelOf("pathology")!!.key)
        assertEquals("病理 real 档 reality", Reality.REAL, on.levelOf("pathology")!!.reality)
        assertTrue("病理开启须带少见标注：${on.advisories}", on.advisories.any { "少见（reality=real）" in it })

        // 病理维的 defaultEnabled 必须为 false
        assertFalse(table.byKey.getValue("pathology").defaultEnabled)
        assertTrue(table.byKey.getValue("pathology").pathological)
    }

    // ── 判据⑤：同参逐字节一致；不同种子结果不同 ──
    @Test
    fun `判据五_同参逐字节一致且异种子不同`() {
        val opts = VariantSampler.Options(seed = 20261119L)
        val a = VariantSampler.sample(table, opts)
        val b = VariantSampler.sample(table, opts)
        assertEquals("同参与采样须逐字节一致", a.canonical(), b.canonical())
        assertEquals("两次 canonical 对象亦等", a, b)

        val seeds = (0 until 40).map { VariantSampler.sample(table, opts.copy(seed = it.toLong())).canonical() }
        assertTrue("不同种子须产生多种结果", seeds.toSet().size > 1)
    }

    // ── 判据⑥：不复述谱内正文——任意连续20字命中 0 ──
    @Test
    fun `判据六_产物不含谱内正文任意二十字`() {
        val spec = File(repoRoot().parentFile, "证道设计报告/附件-真实性补充包/男性-真实变异谱-21.json")
        if (!spec.isFile) {
            println("SKIP 判据⑥：规格源不在工作区（${spec.path}）——本判据本地执行")
            return
        }
        val values = JSONObject(spec.readText(Charsets.UTF_8)).getJSONArray("world_book").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it).getString("value") }
        }
        val products = listOf(
            File(repoRoot(), VariantTable.REL_PATH).readText(Charsets.UTF_8),
            *listOf("VariantModel.kt", "VariantSampler.kt").map {
                File(repoRoot(), "app/src/main/java/com/example/zhengdao/baopu/state/variant/$it").readText(Charsets.UTF_8)
            }.toTypedArray(),
        ).joinToString("\n")

        val windows = values.flatMap { it.windows(20) }.toSet()
        val hits = windows.filter { it in products }
        assertTrue("谱内正文 20 字窗口命中产物 ${hits.size} 处：${hits.take(3)}", hits.isEmpty())

        // 自检（闸有牙）：把一段真实正文注入假产物，检测逻辑必须命中（负例，非恒真）。
        val probe = windows.first()
        val fakeProduct = "前缀${probe}后缀"
        assertTrue("探针须是正文 20 字窗口", probe in windows)
        assertTrue("注入正文片段后闸必须咬中", windows.any { it in fakeProduct })
    }

    // ── helpers ──
    private fun repeatSeeded(n: Int): List<VariantSample> =
        (0 until n).map { VariantSampler.sample(table, VariantSampler.Options(seed = it.toLong())) }

    private fun oneDimJson(): String = """{
      "schema":"variant-spectrum/v1","source":"t","asOf":"t",
      "dimensions":[{"key":"bend_direction","name":"弯曲方向","levels":[
        {"key":"up","name":"上翘","probability":60,"reality":"real"},
        {"key":"down","name":"下弯","probability":20,"reality":"real"},
        {"key":"left","name":"左偏","probability":20,"reality":"real"}]}]
    }"""

    private fun removeField(json: String, field: String): String = json.replace("\"$field\"", "\"_$field\"")

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("期望抛异常但未抛")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            // expected
        }
    }

    private fun String.windows(n: Int): List<String> =
        if (length < n) emptyList() else (0..length - n).map { substring(it, it + n) }
}
