// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十二批（追加判据）· 归因游标**不许把「产物 N 件」读数顶上去**：
//   ① 把 `进化档案.游标.json` 放进 `产物/` ⇒ 体检单「产物 N 件」不变；
//   ② 同一夹具下候选提取结果与改前一致。
// 口径写在 `ReflectionRunner.isCountedProduct`：点号开头与 `*.游标.json` 都不算产物——
// 不靠"候选正则 `^\d{8}_` 不命中"来豁免（那管的是候选，读数才是要守的账）。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReflectionProductReadoutTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var global: File

    /** 一个空工作区（`产物/` 建好）。 */
    private fun workspace(): File {
        global = tmp.newFolder("global")
        return tmp.newFolder("ws").also { MemoryLayout.productsDir(it).mkdirs() }
    }

    /** 三篇同赛道跨三天的产物 —— 这是「重复劳动→新技能」候选的夹具，能真出候选。 */
    private fun plantProducts(ws: File) {
        val dir = MemoryLayout.productsDir(ws)
        listOf("20260101_周报_1.md", "20260102_周报_2.md", "20260103_周报_3.md").forEach {
            File(dir, it).writeText("正文", Charsets.UTF_8)
        }
    }

    private fun scan(ws: File): ReflectionScan = ReflectionRunner(ws, global, log = {}).scan()

    /** 体检单上「产物 N 件」那一行（可得性表 PRODUCT_LEDGER 的 note）。 */
    private fun productNote(ws: File): String =
        scan(ws).availability.first { it.name == ReflectionSourceName.PRODUCT_LEDGER }.note

    @Test
    fun `判据①_归因游标放进产物目录_产物件数不变`() {
        val ws = workspace()
        plantProducts(ws)
        val before = productNote(ws)
        assertEquals("夹具读数（三篇产物、无台账）", "台账无＋产物 3 件", before)

        // 归因游标落进同一个目录（512 的真实落点）
        File(MemoryLayout.productsDir(ws), "进化档案.游标.json")
            .writeText("""{"v":1,"line":6,"hash":"0f1e2d3c4b5a6978"}""", Charsets.UTF_8)
        assertEquals("游标不是产物：读数不许 +1", before, productNote(ws))

        // 以后换名成点号件也一样不数
        File(MemoryLayout.productsDir(ws), ".进化档案.游标").writeText("{}", Charsets.UTF_8)
        assertEquals("点号隐藏件也不数", before, productNote(ws))
    }

    @Test
    fun `判据②_同一夹具下候选与改前一致`() {
        val ws = workspace()
        plantProducts(ws)
        val before = scan(ws).candidates.map { it.dedupKey }.sorted()
        assertEquals("夹具必须真出候选，否则「一致」是空的", listOf("周报::新技能"), before)

        File(MemoryLayout.productsDir(ws), "进化档案.游标.json")
            .writeText("""{"v":1,"line":6,"hash":"0f1e2d3c4b5a6978"}""", Charsets.UTF_8)
        val after = scan(ws).candidates.map { it.dedupKey }.sorted()
        assertEquals("游标进出不影响候选提取", before, after)
    }

    @Test
    fun `读数口径_点号件与游标后缀都不算产物`() {
        val dir = tmp.newFolder("p")
        val real = File(dir, "20260101_周报_1.md").apply { writeText("x", Charsets.UTF_8) }
        val cursor = File(dir, "进化档案.游标.json").apply { writeText("{}", Charsets.UTF_8) }
        val hidden = File(dir, ".隐藏件").apply { writeText("x", Charsets.UTF_8) }

        assertTrue("真产物照数", ReflectionRunner.isCountedProduct(real))
        assertFalse("`*.游标.json` 不数（换名也漏不进）", ReflectionRunner.isCountedProduct(cursor))
        assertFalse("点号开头不数", ReflectionRunner.isCountedProduct(hidden))
        assertFalse("目录本来就不算", ReflectionRunner.isCountedProduct(dir))
    }
}
