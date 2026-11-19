// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十批 · 触觉索引判据夹具。纯内存断言，零写盘（水位 607 口径不受影响）。
// 判据⑤三件齐缺一拒判；派工单五判例＋歧义；强特征优先于兜底；手册档位闭集。
package com.example.zhengdao.baopu.body

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TactileIndexTest {

    private fun root(): File =
        listOf(File("."), File("..")).first { File(it, DeicticIndex.REL_PATH).isFile }

    private val ti: TactileIndex by lazy { TactileIndex.load(root()) }

    private fun key(contact: String, action: String, firm: String, elastic: String, glide: String, warm: String) =
        TactileKey(contact, action, mapOf(
            "firmness" to firm, "elasticity" to elastic, "glide" to glide, "warmth" to warm,
        ))

    // ── 派工单五判例 ──
    @Test
    fun `判例_一按即回加边缘台阶_得corona_sulcus`() {
        val r = ti.probe(key("指腹", "按", "软", "一按即回", "微涩", "体温"), "边缘有台阶")
        assertEquals(listOf("corona", "sulcus"), r.zones)
        assertTrue(r.matchedRules.contains("TACT-01"))
        assertTrue(r.ambiguous)
        assertNotNull(r.distinguish)
    }

    @Test
    fun `判例_捏起薄皮可滑动_得prepuce`() {
        val r = ti.probe(key("指尖", "捏", "软", "慢回", "顺滑", "体温"), "薄皮")
        assertEquals(listOf("prepuce"), r.zones)
        assertFalse(r.ambiguous)
        assertNull(r.distinguish)
    }

    @Test
    fun `判例_托起有重量加褶皱_得scrotum`() {
        val r = ti.probe(key("掌根", "托", "软", "慢回", "微涩", "偏凉"), "褶皱")
        assertEquals(listOf("scrotum"), r.zones)
    }

    @Test
    fun `判例_卵圆可滑动_得testis`() {
        val r = ti.probe(key("指腹", "摸", "有韧性", "慢回", "顺滑", "体温"), "卵圆")
        assertEquals(listOf("testis"), r.zones)
    }

    @Test
    fun `判例_沿细管_得cord`() {
        val r = ti.probe(key("指侧", "滑", "有韧性", "一按即回", "微涩", "体温"), "细管")
        assertEquals(listOf("cord"), r.zones)
    }

    @Test
    fun `判例_只说软_兜底得scrotum_glans歧义`() {
        val r = ti.probe(key("指腹", "按", "软", "一按即回", "微涩", "体温"), "")
        assertEquals(listOf("scrotum", "glans"), r.zones)
        assertEquals(listOf("TACT-06"), r.matchedRules)
        assertTrue(r.ambiguous)
    }

    @Test
    fun `强特征优先_有台阶不被兜底污染成四区`() {
        val r = ti.probe(key("指腹", "按", "软", "一按即回", "微涩", "体温"), "边缘有台阶")
        assertEquals("强特征命中时兜底规则不应生效", listOf("TACT-01"), r.matchedRules)
        assertEquals(listOf("corona", "sulcus"), r.zones)
    }

    // ── 判据⑤ 三件齐，缺一拒判 ──
    @Test
    fun `判据5_缺触点拒判`() {
        assertFalse(runCatching { ti.validate(key("", "按", "软", "一按即回", "微涩", "体温")) }.isSuccess)
    }

    @Test
    fun `判据5_缺动作拒判`() {
        assertFalse(runCatching { ti.validate(key("指腹", "", "软", "一按即回", "微涩", "体温")) }.isSuccess)
    }

    @Test
    fun `判据5_缺一维手感拒判`() {
        val bad = TactileKey("指腹", "按", mapOf(
            "firmness" to "软", "elasticity" to "一按即回", "glide" to "微涩",
        ))
        assertFalse(runCatching { ti.validate(bad) }.isSuccess)
    }

    @Test
    fun `判据5_非法触点与非法档位拒判`() {
        assertFalse(runCatching { ti.validate(key("脚跟", "按", "软", "一按即回", "微涩", "体温")) }.isSuccess)
        assertFalse(runCatching { ti.validate(key("指腹", "按", "硬邦邦", "一按即回", "微涩", "体温")) }.isSuccess)
    }

    @Test
    fun `判据5_probe入口即校验_不合法直接抛`() {
        assertFalse(runCatching { ti.probe(key("指腹", "", "软", "一按即回", "微涩", "体温")) }.isSuccess)
    }

    // ── 装配闸 / 来源 / 确定性 ──
    @Test
    fun `装配闸_规则触点悬空拒绝装配`() {
        val json = File(root(), DeicticIndex.REL_PATH).readText()
            .replace("\"contacts\": [\"指腹\", \"指尖\"],", "\"contacts\": [\"脚跟\", \"指尖\"],")
        assertFalse(runCatching { TactileIndex.parse(org.json.JSONObject(json).getJSONObject("tactile")) }.isSuccess)
    }

    @Test
    fun `每条规则带source_asOf`() {
        ti.rules.forEach {
            assertTrue("规则 ${it.id} source/asOf 不齐", it.source.isNotBlank() && it.asOf.isNotBlank())
        }
    }

    @Test
    fun `确定性_同键两次probe一致`() {
        val k = key("指腹", "按", "软", "一按即回", "微涩", "体温")
        assertEquals(ti.probe(k, "边缘有台阶"), ti.probe(k, "边缘有台阶"))
    }
}
