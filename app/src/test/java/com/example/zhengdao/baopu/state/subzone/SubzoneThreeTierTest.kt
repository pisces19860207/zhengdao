// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百八十七批 · 子区三层归口（区／组／子区）：规范页与认账留痕的门禁。
//
// 四道闸各自钉一件事，全部读**原文**（文档与源码），不读运行期拼出来的字符串：
// · Z1 三层定义齐：区 13（事实底盘口径）／组 7（SubzoneGroup）／子区 14（SubzoneId），
//      并核规范页第三节的 14 行表与 `SubzoneId` 逐行相同——改一处不改另一处就红；
// · Z2 corona 双字段都在：`zoneId` 是判定单位（13 区里单列），`group` 是解剖归类（龟头）；
// · Z3 790 的缝认账留痕：原计划／775 提前接入／日期／单号 四要素，删注释即红；
// · Z4 790 的旧用例名全仓零残留，且新名在测试里在场（防「删掉用例」冒充改名）。
//
// 路径一律从仓库根起算：单测的工作目录可能是仓库根，也可能是 `app/`，所以往上找根而不是猜相对路径。
package com.example.zhengdao.baopu.state.subzone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SubzoneThreeTierTest {

    // ── 路径 ─────────────────────────────────────────────────────────────

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "docs/facts/anatomy-zones.json").isFile && File(dir, "app/src/main").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("找不到仓库根：从 ${File("").absolutePath} 往上都没有 docs/facts/anatomy-zones.json")
    }

    private fun docText(): String {
        val doc = File(repoRoot(), TIER_DOC)
        assertTrue("887 的规范页必须在：${doc.path}", doc.isFile)
        return doc.readText()
    }

    private fun sourceText(rel: String): String {
        val file = File(repoRoot(), rel)
        assertTrue("缺文件：$rel", file.isFile)
        return file.readText()
    }

    /** 表格行 → 单元格（去掉首尾竖线，逐格 trim）。非表格行不返回。 */
    private fun tableRows(text: String): List<List<String>> = text.lines()
        .map { it.trim() }
        .filter { it.startsWith("|") && it.endsWith("|") }
        .map { line -> line.removePrefix("|").removeSuffix("|").split("|").map { cell -> cell.trim() } }

    // ── Z1：三层定义齐（区 13／组 7／子区 14）＋ 表与真源逐行相同 ──────────

    @Test
    fun Z1_三层定义齐_区十三组七子区十四() {
        val text = docText()
        val zones = Regex("\"zone_id\":\\s*\"([^\"]+)\"")
            .findAll(sourceText(FACTS)).map { it.groupValues[1] }.toSet()
        assertEquals("区必须是 13 个（口径取事实底盘，不手写常数）", 13, zones.size)
        assertEquals("组必须是 7 个（SubzoneGroup 声明了几个就是几个）", 7, SubzoneGroup.entries.size)
        assertEquals("子区必须是 14 个（SubzoneId 声明了几个就是几个）", 14, SubzoneId.entries.size)
        // 规范页三层节写下的数必须与上面三个真值同：读表格单元格，不读「文件里有没有这几个字」。
        val counts = tableRows(text).filter { it.size == 5 }.associate { it[0] to it[1] }
        assertEquals("规范页三层节的区数", "13", counts["区 zone"])
        assertEquals("规范页三层节的组数", "7", counts["组 group"])
        assertEquals("规范页三层节的子区数", "14", counts["子区 subzone"])
    }

    @Test
    fun Z1_十四子区表与SubzoneId逐行相同() {
        val codes = SubzoneId.entries.map { it.code }
        val rows = tableRows(docText()).filter { it.size == 5 && it[0] in codes }
        assertEquals("对照表必须 14 行，且一个 code 一行", codes.size, rows.size)
        assertEquals("对照表里 code 重了", codes.size, rows.map { it[0] }.distinct().size)
        for (id in SubzoneId.entries) {
            val missing = "总表缺这一行：${id.code}（表里有：${rows.map { it[0] }}）"
            val row = rows.firstOrNull { it[0] == id.code } ?: throw AssertionError(missing)
            assertEquals("${id.code} 的名称", id.label, row[1])
            assertEquals("${id.code} 的组", id.group.label, row[2])
            assertEquals("${id.code} 的宿主区", id.zoneId, row[3])
            assertEquals("${id.code} 的方位", id.side.label, row[4])
        }
    }

    // ── Z2：corona 双字段都在（行为 + 源码声明行） ────────────────────────

    /** 声明行是否两个字段都在。拆出来是为了给闸做负例自检（闸自己也要有人验）。 */
    private fun bothFieldsOn(line: String): Boolean =
        line.contains("ZoneRef.CORONA") && line.contains("SubzoneGroup.GLANS")

    @Test
    fun Z2_corona双字段都在_zone是判定单位group是归类() {
        // 独立预期：13 区词表里 corona 自成一区（`docs/facts/anatomy-zones.json`），
        // 派工单口径里冠部归「龟头」组。两个都是字面量，不拿 ZoneRef 自己证自己。
        assertEquals("corona 的判定单位必须是 13 区的 corona，不许并进 glans", "corona", SubzoneId.GLANS_CROWN.zoneId)
        assertEquals("corona 的解剖归类必须是龟头组", "龟头", SubzoneId.GLANS_CROWN.group.label)
        val decl = sourceText(SUBZONE_IDS).lines().single { it.contains("GLANS_CROWN(") }
        assertTrue("声明行丢了 zoneId=corona（冠被并进龟头就是改 13 区）：$decl", decl.contains("ZoneRef.CORONA"))
        assertTrue("声明行丢了 group=龟头（冠被降成纯呈现用语）：$decl", decl.contains("SubzoneGroup.GLANS"))
        assertTrue("闸有牙自检：缺 group 的假声明必须判 false", !bothFieldsOn("GLANS_CROWN(\"corona\", \"冠部\", ZoneRef.CORONA, SubzoneSide.RING),"))
        assertTrue("闸有牙自检：缺 zone 的假声明必须判 false", !bothFieldsOn("GLANS_CROWN(\"corona\", \"冠部\", SubzoneGroup.GLANS, SubzoneSide.RING),"))
    }

    // ── Z3：790 的缝认账留痕（四要素） ────────────────────────────────────

    @Test
    fun Z3_790缝认账留痕四要素齐() {
        val text = sourceText(SUBZONE_SOURCE)
        // 逐字要**一整句**：四个要素分散在别处不算留痕（实测：只核四个词，删掉认账那句仍绿 ⇒ 闸没牙）。
        val sentence = "此缝原计划由装配单统一接，775 提前接入，2026-11-27 由 887 认账"
        assertTrue("认账原句不在（删这一句必须红）：$SUBZONE_SOURCE", text.contains(sentence))
        for (needle in listOf("装配单", "775 提前接入", "2026-11-27", "887")) {
            assertTrue("认账留痕缺『$needle』（留痕不抹历史：删掉即红）：$SUBZONE_SOURCE", text.contains(needle))
        }
    }

    // ── Z4：旧用例名零残留、新名在场 ──────────────────────────────────────

    @Test
    fun Z4_旧用例名全仓零残留_新名在场() {
        // 旧名用拼接造：本文件自己也在扫描范围里，写整串会把闸自己咬住。
        val oldName = "请求" + "子区即" + "incomplete"
        val hits = ArrayList<String>()
        for (root in listOf("docs", "app/src/test")) {
            File(repoRoot(), root).walkTopDown()
                .filter { it.isFile && (it.extension == "md" || it.extension == "kt") }
                .forEach { file ->
                    val text = file.readText()
                    var at = text.indexOf(oldName)
                    while (at >= 0) {
                        hits.add(file.relativeTo(repoRoot()).path + " @字" + at)
                        at = text.indexOf(oldName, at + 1)
                    }
                }
        }
        assertTrue("旧用例名还在（改了测试没改文档就长这样）：$hits", hits.isEmpty())
        val test = sourceText(BEARING_TEST)
        assertTrue("新名必须在场（防「删掉用例」冒充改名）：$NEW_NAME", test.contains(NEW_NAME))
    }

    private companion object {
        const val TIER_DOC = "docs/区域总表-下体.md"
        const val FACTS = "docs/facts/anatomy-zones.json"
        const val SUBZONE_IDS = "app/src/main/java/com/example/zhengdao/baopu/state/subzone/SubzoneIds.kt"
        const val SUBZONE_SOURCE = "app/src/main/java/com/example/zhengdao/baopu/state/bearing/SubzoneSource.kt"
        const val BEARING_TEST = "app/src/test/java/com/example/zhengdao/baopu/state/bearing/BearingReachTest.kt"
        const val NEW_NAME = "判据五_不在册子区即incomplete_不假装判到子区"
    }
}
