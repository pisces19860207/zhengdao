// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百七十五批 · 子区层：源码断言（判据④禁数值／判据⑤单一来源／与事实底盘对齐／缝一致）。
//
// 这些闸全部**剥注释后扫代码**：注释里写着「不许 mm」不算数，字段里冒出 mm 才算。
// 每条闸都带自检：往夹具里塞一条脏的，闸必须咬住；咬不住就是闸空转（比没闸更坏）。
package com.example.zhengdao.baopu.state.subzone

import com.example.zhengdao.baopu.state.bearing.SubzoneSource
import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SubzoneSourceAssertTest {

    // ── 路径与工具 ───────────────────────────────────────────────────────

    private fun subzoneDir(): File {
        val dir = File("src/main/java/com/example/zhengdao/baopu/state/subzone")
        return if (dir.isDirectory) dir else File("../app/src/main/java/com/example/zhengdao/baopu/state/subzone")
    }

    private fun stateDir(): File = requireNotNull(subzoneDir().parentFile) { "找不到 state 目录" }

    private fun ktFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    private fun subzoneSources(): List<Pair<String, String>> =
        subzoneDir().listFiles { f -> f.isFile && f.name.endsWith(".kt") }.orEmpty()
            .map { it.name to it.readText() }

    private fun factsFile(): File = listOf(
        "docs/facts/anatomy-zones.json",
        "../docs/facts/anatomy-zones.json",
    ).map(::File).first { it.isFile }

    /** 与 790／GateIo 同口径：注释替空格，行号与字符串内容不变。 */
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
            if (three) { inRaw = true; out.append("\"\"\""); i += 3; continue }
            if (src[i] == '"') { inStr = true; out.append('"'); i++; continue }
            if (src.startsWith("//", i)) { inLine = true; i += 2; continue }
            if (src.startsWith("/*", i)) { inBlock = true; i += 2; continue }
            out.append(src[i]); i++
        }
        return out.toString()
    }

    /**
     * 长度／比例口径**不在这里另立一份**：直接用 805 的 `BodyPartGuard.measureHit`。
     *
     * 为什么不用「字面包含」：第一版就是这么写的，结果把 `hardCommonSense` 里的 `mm`（Co-mm-on）
     * 判成了长度单位——自己写一份词表，边界就迟早各写各的。805 的闸已经按「独立词」判
     * （缩写后面紧跟 ASCII 字母不算），且它的词表是全仓唯一一份。
     */
    private fun measureHit(code: String): String? = BodyPartGuard.measureHit(code)

    // ── 判据④：禁数值（禁 mm） ──────────────────────────────────────────

    @Test
    fun 判据四_生产代码零长度单位_零百分比() {
        for ((name, src) in subzoneSources()) {
            val hit = measureHit(stripComments(src))
            assertTrue("$name 里出现长度／比例词「$hit」（本单元只出档，不出量）", hit == null)
        }
    }

    @Test
    fun 判据四_结论类型零浮点() {
        for ((name, src) in subzoneSources()) {
            val code = stripComments(src)
            for (token in listOf("Double", "Float", "toDouble", "toFloat")) {
                assertTrue("$name 出现 $token：档位是整数档，结论里不许有浮点", !code.contains(token))
            }
        }
    }

    @Test
    fun 判据四_闸有牙_注入长度单位必被咬住() {
        val dirty = "val hint = \"3" + "m" + "m" + "\"\nval tail = 2\n"
        assertTrue("自检前提：夹具里得真有那个词", dirty.contains("m" + "m"))
        assertTrue("注入的长度单位没被咬住", measureHit(dirty) != null)
        assertTrue("干净夹具不该报红", measureHit("val tier = 2\n") == null)
        // 边界控制项：标识符里夹 mm（Co-mm-on）不算长度单位——第一版闸就是在这里误报的。
        assertTrue("标识符里的 mm 被误判成长度单位", measureHit("fun hardCommonSense() = 1\n") == null)
    }

    // ── 判据⑤：不新增第二套子区（id 只定义一次） ────────────────────────

    @Test
    fun 判据五_全state只有一个SubzoneId定义点() {
        val definers = ktFiles(stateDir()).filter { stripComments(it.readText()).contains("enum class SubzoneId") }
        assertEquals("子区 id 定义点必须唯一：${definers.map { it.name }}", 1, definers.size)
        assertTrue("定义点必须是 subzone/SubzoneIds.kt", definers.single().path.replace('\\', '/').endsWith("subzone/SubzoneIds.kt"))
    }

    @Test
    fun 判据五_子区code字面量只出现在subzone包() {
        val stray = ktFiles(stateDir()).filter { file ->
            val code = stripComments(file.readText())
            code.contains("\"glansbody\"") || code.contains("\"prepuceOrifice\"") || code.contains("\"meatusSlit\"")
        }.filterNot { it.parentFile.name == "subzone" }
        assertTrue("别处出现了子区 code 字面量（第二套清单）：${stray.map { it.path }}", stray.isEmpty())
    }

    @Test
    fun 判据五_804与831的包零改动线索() {
        // 结构证据：prepuce 包里没有任何一处引用本单元类型（804／831 原样不动，靠 git 记录另证）。
        val prepuce = File(stateDir(), "prepuce")
        assertTrue("prepuce 包必须还在", prepuce.isDirectory)
        for (file in ktFiles(prepuce)) {
            val code = stripComments(file.readText())
            assertTrue("${file.name} 引用了 subzone：804／831 不许被本单改动", !code.contains("state.subzone"))
        }
    }

    // ── 与事实底盘对齐（三条硬常识的出处） ──────────────────────────────

    private fun zoneBlock(zoneId: String): String {
        val text = factsFile().readText().replace(Regex("\\s+"), " ")
        val start = text.indexOf("\"zone_id\": \"$zoneId\"")
        assertTrue("事实底盘里找不到区 $zoneId", start >= 0)
        return text.substring(start, minOf(text.length, start + WINDOW))
    }

    @Test
    fun 硬常识一_事实底盘说系带在腹侧() {
        val block = zoneBlock("frenulum")
        assertTrue("669 的系带位置应写腹侧：$block", block.contains("腹侧"))
        assertEquals("本表也必须记腹侧（不许一处腹侧一处背面）", SubzoneSide.VENTRAL, SubzoneId.FRENULUM.side)
    }

    @Test
    fun 硬常识二_事实底盘说尿道口是偏腹侧纵裂() {
        val block = zoneBlock("meatus")
        assertTrue("669 的尿道口位置应写纵裂：$block", block.contains("纵裂"))
        assertTrue("669 的尿道口位置应写偏腹侧：$block", block.contains("偏腹侧"))
        assertEquals(
            "本表的裂隙方向也必须是偏腹侧纵裂",
            "纵裂偏腹侧",
            SubzoneTable.row(SubzoneId.MEATUS_SLIT).formOf(FormAxis.SLIT)!!.label,
        )
    }

    @Test
    fun 硬常识三_龟头与柱身的质地色档方向不反() {
        // 660 的表面粗糙度序是「明显皱 → 细皱 → 光滑」，秩大＝更光滑；本表同向。
        // 只核方向，不核数值：龟头秩 > 柱身秩，龟头色档步长 > 柱身步长。
        val glansRank = SubzoneId.ofGroup(SubzoneGroup.GLANS).minOf { SubzoneTable.row(it).texture.rank }
        val shaftRank = SubzoneId.ofGroup(SubzoneGroup.SHAFT).maxOf { SubzoneTable.row(it).texture.rank }
        assertTrue("龟头必须比柱身光滑（秩更大）", glansRank > shaftRank)
        val glansStep = SubzoneId.ofGroup(SubzoneGroup.GLANS).minOf { SubzoneTable.row(it).color.step }
        val shaftStep = SubzoneId.ofGroup(SubzoneGroup.SHAFT).maxOf { SubzoneTable.row(it).color.step }
        assertTrue("龟头必须比柱身深（步长更大）", glansStep > shaftStep)
    }

    @Test
    fun 宿主区都在十三区词表里() {
        val ids = Regex("\"zone_id\":\\s*\"([^\"]+)\"").findAll(factsFile().readText()).map { it.groupValues[1] }.toSet()
        assertEquals("十三区词表应仍是 13 区", 13, ids.size)
        val mine = setOf(ZoneRef.GLANS, ZoneRef.CORONA, ZoneRef.SULCUS, ZoneRef.FRENULUM,
            ZoneRef.SHAFT, ZoneRef.RADIX, ZoneRef.MEATUS, ZoneRef.PREPUCE)
        val unknown = mine - ids
        assertTrue("本单元引用了词表里没有的区：$unknown", unknown.isEmpty())
        for (id in SubzoneId.entries) {
            assertTrue("${id.code} 的宿主区 ${id.zoneId} 不在十三区里", id.zoneId in ids)
        }
    }

    // ── 与 790 的缝一致（775 落地后这条缝只转发） ───────────────────────

    @Test
    fun 缝一致_SubzoneSource转发本单元_不另存清单() {
        for (zone in setOf(ZoneRef.GLANS, ZoneRef.CORONA, ZoneRef.SULCUS, ZoneRef.FRENULUM,
            ZoneRef.SHAFT, ZoneRef.RADIX, ZoneRef.MEATUS, ZoneRef.PREPUCE)) {
            assertEquals(
                "区 $zone 的缝与本表不一致",
                SubzoneId.ofZone(zone).map { it.code },
                SubzoneSource.of(zone),
            )
        }
        assertTrue("睾丸不在本单范围：缝必须如实给空表", SubzoneSource.of("testis").isEmpty())
        assertTrue("缝的出处必须点明 775 已落地", SubzoneSource.SOURCE.contains("775"))
    }

    private companion object {
        const val WINDOW = 1400
    }
}
