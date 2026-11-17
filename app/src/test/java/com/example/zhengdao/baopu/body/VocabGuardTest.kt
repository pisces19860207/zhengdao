// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百六十九批 · vocab_guard 十条规则夹具（每规则正反各一，共 20 例）＋
// 确定性・只报不改写・宾转主 suggest。夹具为本单自撰的教材级短句，非素材正文。
// 纯内存断言，零写盘、零临时目录（水位 607 口径不受影响）。
package com.example.zhengdao.baopu.body

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabGuardTest {

    private val zones = AnatomyZones.parse(
        listOf(File(AnatomyZones.REL_PATH), File("../${AnatomyZones.REL_PATH}"))
            .first { it.isFile }.readText(),
    )
    private val guard = VocabGuard(zones)

    // ── 二十夹具：P＝正例（须咬中）／N＝负例（须干净），全部自撰临床短句 ──

    private val p01 = "系带位于背侧正中。"
    private val n01 = "系带是腹侧连接包皮与龟头的小褶。"
    private val p02 = "尿道口是正中间的一个圆孔。"
    private val n02 = "尿道口是龟头尖端略偏腹侧的纵裂，不是正中心的圆孔。"
    private val p03 = "龟头与柱身同质同色。"
    private val n03 = "龟头表面是黏膜样，颜色比柱身深一档。"
    private val p04 = "阴囊永远紧缩着。"
    private val n04 = "阴囊随温度变化，冷时收缩上提、热时松弛下垂。"
    private val p05 = "左右睾丸完全对称、同高。"
    private val n05 = "左侧睾丸通常略低，附睾贴在后方。"
    private val p06 = "龟头冠末端鼓起一个球状结构。"
    private val n06 = "龟头冠是龟头近端的隆起边缘，界限清晰。"
    private val p07 = "柱身是单一均匀的圆柱，无背腹差异。"
    private val n07 = "柱身背侧偏硬、腹侧偏软，横截面偏椭圆。"
    private val p08 = "文中写到了阴茎缝、精索和包皮内板。"
    private val n08 = "阴茎缝是腹侧正中一条色略深的线；精索是从腹股沟到睾丸的条索；包皮内板是黏膜、外板是皮肤。"
    private val p09 = "根部完全露出，清晰可见。"
    private val n09 = "根部埋于会阴，体表不可见。"
    private val p10 = "柱身的直径就是周长，约十二厘米。"
    private val n10 = "周长是绕一圈的长度，除以π才得直径。"

    /** 正例通用断言：恰咬中指定规则，severity/zoneId/正确说法齐，span 取自原文子句。 */
    private fun assertBites(text: String, ruleId: String, severity: GuardSeverity, zoneId: String, spanText: String) {
        val findings = guard.scan(text)
        assertEquals("只许咬中 $ruleId", setOf(ruleId), findings.map { it.ruleId }.toSet())
        val f = findings.first()
        assertEquals(severity, f.severity)
        assertEquals(zoneId, f.zoneId)
        assertTrue("正确说法须非空", f.correction.isNotBlank())
        assertEquals("span 须取自原文", spanText, text.substring(f.start, f.end))
    }

    @Test
    fun `VG01_正例_系带写背侧_咬中`() {
        assertBites(p01, "VG-01", GuardSeverity.ERROR, "frenulum", "系带位于背侧正中")
    }

    @Test
    fun `VG01_负例_系带在腹侧_干净`() {
        assertTrue(guard.scan(n01).isEmpty())
    }

    @Test
    fun `VG02_正例_尿道口正中圆孔_咬中`() {
        assertBites(p02, "VG-02", GuardSeverity.ERROR, "meatus", "尿道口是正中间的一个圆孔")
    }

    @Test
    fun `VG02_负例_纵裂偏腹侧带否定_干净`() {
        assertTrue(guard.scan(n02).isEmpty())
    }

    @Test
    fun `VG03_正例_龟头与柱身同质同色_咬中`() {
        assertBites(p03, "VG-03", GuardSeverity.ERROR, "glans", "龟头与柱身同质同色")
    }

    @Test
    fun `VG03_负例_黏膜样色深一档_干净`() {
        assertTrue(guard.scan(n03).isEmpty())
    }

    @Test
    fun `VG04_正例_阴囊永远紧缩_咬中warn`() {
        assertBites(p04, "VG-04", GuardSeverity.WARN, "scrotum", "阴囊永远紧缩着")
    }

    @Test
    fun `VG04_负例_随温度变化_干净`() {
        assertTrue(guard.scan(n04).isEmpty())
    }

    @Test
    fun `VG05_正例_左右睾丸同高_咬中`() {
        assertBites(p05, "VG-05", GuardSeverity.ERROR, "testis", "左右睾丸完全对称、同高")
    }

    @Test
    fun `VG05_负例_左侧略低有附睾_干净`() {
        assertTrue(guard.scan(n05).isEmpty())
    }

    @Test
    fun `VG06_正例_龟头冠球状_咬中`() {
        assertBites(p06, "VG-06", GuardSeverity.ERROR, "corona", "龟头冠末端鼓起一个球状结构")
    }

    @Test
    fun `VG06_负例_隆起边缘界限清晰_干净`() {
        assertTrue(guard.scan(n06).isEmpty())
    }

    @Test
    fun `VG07_正例_柱身均匀圆柱无背腹_咬中`() {
        assertBites(p07, "VG-07", GuardSeverity.ERROR, "shaft", "柱身是单一均匀的圆柱，无背腹差异")
    }

    @Test
    fun `VG07_负例_背硬腹软偏椭圆_干净`() {
        assertTrue(guard.scan(n07).isEmpty())
    }

    @Test
    fun `VG08_正例_三术语无结构说明_三条warn`() {
        val findings = guard.scan(p08)
        assertEquals(3, findings.size)
        assertTrue(findings.all { it.ruleId == "VG-08" && it.severity == GuardSeverity.WARN })
        assertEquals(setOf("raphe", "cord", "prepuce"), findings.map { it.zoneId }.toSet())
        assertTrue(findings.all { it.correction.isNotBlank() })
        assertEquals("文中写到了阴茎缝、精索和包皮内板", p08.substring(findings[0].start, findings[0].end))
    }

    @Test
    fun `VG08_负例_各带结构说明_干净`() {
        assertTrue(guard.scan(n08).isEmpty())
    }

    @Test
    fun `VG09_正例_根部当可见段_咬中`() {
        assertBites(p09, "VG-09", GuardSeverity.ERROR, "radix", "根部完全露出，清晰可见")
    }

    @Test
    fun `VG09_负例_埋于会阴不可见_干净`() {
        assertTrue(guard.scan(n09).isEmpty())
    }

    @Test
    fun `VG10_正例_周长当直径_咬中`() {
        assertBites(p10, "VG-10", GuardSeverity.ERROR, "shaft", "柱身的直径就是周长，约十二厘米")
    }

    @Test
    fun `VG10_负例_除以π换算说明_干净`() {
        assertTrue(guard.scan(n10).isEmpty())
    }

    // ── 确定性・只报不改写・suggest ──

    @Test
    fun `确定性_同输入两次扫描逐字节一致`() {
        val text = p01 + p05 + p09
        val a = guard.scan(text)
        val b = guard.scan(text)
        assertEquals(a, b)
        assertEquals(a.toString(), b.toString())
    }

    @Test
    fun `多规则文本_按偏移稳定排序且span各指其句`() {
        val text = p01 + p05 + p09
        val findings = guard.scan(text)
        assertEquals(listOf("VG-01", "VG-05", "VG-09"), findings.map { it.ruleId })
        assertEquals("系带位于背侧正中", text.substring(findings[0].start, findings[0].end))
        assertEquals("左右睾丸完全对称、同高", text.substring(findings[1].start, findings[1].end))
        assertEquals("根部完全露出，清晰可见", text.substring(findings[2].start, findings[2].end))
    }

    @Test
    fun `只报不改写_原文逐字不变`() {
        val text = p03 + p07
        val before = text.toByteArray()
        guard.scan(text)
        guard.suggest(text)
        assertTrue("原文不许被改写", before.contentEquals(text.toByteArray()))
    }

    @Test
    fun `空文本_零发现零提示`() {
        assertTrue(guard.scan("").isEmpty())
        assertTrue(guard.suggest("").isEmpty())
    }

    @Test
    fun `suggest_正例_部位只作宾语_提示宾转主`() {
        val text = "她握住柱身，抵住龟头。"
        val suggestions = guard.suggest(text)
        assertEquals(listOf("柱身", "龟头"), suggestions.map { it.term })
        assertEquals(listOf("shaft", "glans"), suggestions.map { it.zoneId })
        assertTrue(suggestions.all { "宾语挪到主语" in it.advice })
        assertEquals("柱身", text.substring(suggestions[0].start, suggestions[0].end))
    }

    @Test
    fun `suggest_负例_部位作主语_不提示`() {
        assertTrue(guard.suggest("柱身背侧偏硬、腹侧偏软；龟头颜色更深。").isEmpty())
    }

    @Test
    fun `suggest_宾主混现_不提示`() {
        assertTrue(guard.suggest("她握住柱身，柱身随之变硬。").isEmpty())
    }

    @Test
    fun `suggest_单字别名不进提示层`() {
        assertTrue(guard.suggest("握住蛋。").isEmpty())
    }

    @Test
    fun `装配闸_规则依据分区必须在表里`() {
        val partial = zones.filter { it.zoneId != "frenulum" }
        assertFalse("缺 frenulum 仍装配就是漂移", runCatching { VocabGuard(partial) }.isSuccess)
    }
}