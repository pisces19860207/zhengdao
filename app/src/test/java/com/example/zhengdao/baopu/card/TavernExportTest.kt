// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十五批（2026-11-11）：产物/ 出口、可撤销、不覆盖、不自动上传（边界）。
package com.example.zhengdao.baopu.card

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernExportTest {

    @Test
    fun export_writes_under_products_dir_and_is_schema_clean() = TempSandbox.use("zd585-export") { root ->
        val r = TavernExport.export(root, "示例作品", TavernFixtures.bundle(), "stamp-1")
        assertTrue("schema 自检必须 0 违规", r.schemaViolations.isEmpty())
        assertTrue("产物必须在 产物/ 下", r.outDir.absolutePath.contains("${File.separator}产物${File.separator}"))
        assertEquals(2, r.files.size) // 没有自定义字段 ⇒ 不写「会丢」清单
        assertEquals(setOf("card.json", "world.json"), r.files.map { it.name }.toSet())
        for (f in r.files) {
            assertTrue("${f.name} 应落盘", f.isFile)
            assertTrue("${f.name} 非空", f.length() > 0)
        }
        // 从磁盘读回再判（不拿内存字符串自证）
        val diskCard = File(r.outDir, "card.json").readText(Charsets.UTF_8)
        val diskWorld = File(r.outDir, "world.json").readText(Charsets.UTF_8)
        assertTrue(TavernFields.validateCard(diskCard).isEmpty())
        assertTrue(TavernFields.validateWorld(diskWorld).isEmpty())
    }

    @Test
    fun export_customField_writes_lossReceipt_file() = TempSandbox.use("zd585-loss") { root ->
        val r = TavernExport.export(root, "带自定义字段", TavernFixtures.bundleWithCustomField(), "stamp-2")
        assertEquals(3, r.files.size)
        val loss = File(r.outDir, TavernExport.FILE_LOSSES).readText(Charsets.UTF_8)
        assertTrue("落盘的清单要点名会丢字段", loss.contains("我们的自定义字段"))
        assertTrue("落盘的清单要说会丢", loss.contains("会丢"))
    }

    @Test
    fun undo_deletes_only_this_exports_files() = TempSandbox.use("zd585-undo") { root ->
        val r = TavernExport.export(root, "可撤销", TavernFixtures.bundle(), "stamp-3")
        val productsDir = File(root, TavernExport.PRODUCTS)
        val other = File(productsDir, "别人的文件.txt")
        productsDir.mkdirs()
        other.writeText("别删我", Charsets.UTF_8)

        val u = TavernExport.undo(root, r.receiptId)
        assertEquals("本次两个产物都应被删", 2, u.deleted)
        assertEquals(0, u.skipped)
        assertEquals(0, u.failed)
        assertTrue("别人的文件绝不能被动", other.isFile)
        for (f in r.files) assertFalse("${f.name} 应已删", f.exists())
    }

    @Test
    fun undo_skips_user_modified_files() = TempSandbox.use("zd585-undo-guard") { root ->
        val r = TavernExport.export(root, "用户改过", TavernFixtures.bundle(), "stamp-4")
        val editable = r.files.first { it.name == "card.json" }
        editable.writeText("用户自己改了内容", Charsets.UTF_8)

        val u = TavernExport.undo(root, r.receiptId)
        assertEquals(1, u.deleted) // 只删了没被改过的 world.json
        assertEquals(1, u.skipped) // 被改过的跳过
        assertTrue("用户改过的内容必须原样保留", editable.readText().contains("用户自己改了内容"))
    }

    @Test
    fun export_never_overwrites_existing_target() = TempSandbox.use("zd585-no-overwrite") { root ->
        TavernExport.export(root, "别覆盖", TavernFixtures.bundle(), "stamp-5a")
        val thrown = runCatching {
            TavernExport.export(root, "别覆盖", TavernFixtures.bundle(), "stamp-5b")
        }.exceptionOrNull()
        assertTrue("同名再导出必须整次拒绝", thrown is TavernExportError)
        assertTrue("错误信息要点明未覆盖", thrown?.message?.contains("未覆盖") == true)
    }

    @Test
    fun undo_missing_or_bad_receipt_reports_failure_not_crash() = TempSandbox.use("zd585-undo-miss") { root ->
        val u = TavernExport.undo(root, "不存在的回执")
        assertEquals(0, u.deleted)
        assertEquals(1, u.failed)
    }

    @Test
    fun boundary_no_upload_and_no_frontend_behavior_in_source() {
        val sources = listOf(
            "app/src/main/java/com/example/zhengdao/baopu/card/TavernCodec.kt",
            "app/src/main/java/com/example/zhengdao/baopu/card/TavernExport.kt",
        )
        for (p in sources) {
            val s = com.example.zhengdao.SrcRoots.find(p).readText()
            assertFalse("$p 不许有上传/网络调用", s.contains("upload") || s.contains("HttpURLConnection"))
            assertFalse("$p 不许做 token 裁剪/插入算法", s.contains("tokenBudget") || s.contains("injectDepth"))
        }
    }
}
