// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十九批 · 事实层零污染对拍（判据④，宗旨落地③①）：
// 题材模式开着跑一轮完整注入，前后对拍 665/669/660 三份事实数据的**字节**——
// 必须一字不差。再加一道结构闸：fiction 包对仓内任何事实层类**零 import**，
// 污染在依赖图上就不可能，不是"我们小心没碰"，是"碰不到"。
package com.example.zhengdao.baopu.state.fiction

import com.example.zhengdao.SrcRoots
import java.io.File

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactLayerIsolationTest {

    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"), File("app/$rel"), File("../app/$rel"))
        return cands.firstOrNull { it.isFile } ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.path }}）")
    }

    private fun sha(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val factFiles = listOf(
        "docs/facts/facts.json",
        "docs/facts/anatomy-zones.json",
        "docs/facts/variants.json",
    )

    @Test
    fun `判据四_题材模式开着跑完整注入_三份事实数据字节不变`() {
        val lock = LockCanon(
            DepthStage.IN_CAVITY, LockPoint.DUAL_PRESS, LockSource.BOTH,
            LockDuration.SHORT, ReleaseCondition.RELEASED, FirmnessDuringLock.CORONA_HARDEST,
            FictionCanon.SOURCE_CANON, FictionCanon.CANON_VERSION, FictionCanon.AS_OF_CANON,
        )
        val topo = DualSuiteTopology(
            listOf(Outlet("a", "尿道口"), Outlet("b", "阴道口")),
            listOf(SupplyClaim("male", "vessel", "前段"), SupplyClaim("female", "vessel", "后段")),
            HeightBand.UPPER, HeightBand.LOWER,
            FictionCanon.SOURCE_CANON, FictionCanon.CANON_VERSION, FictionCanon.AS_OF_CANON,
        )
        val before = factFiles.associateWith { sha(repoFile(it).readBytes()) }
        // 开着跑：注入十行＋字节数——本包没有任何写盘口，跑完事实文件必须原样。
        val lines = FictionMode.lines(lock, topo, switchOn = true)
        assertEquals(10, lines.size)
        val after = factFiles.associateWith { sha(repoFile(it).readBytes()) }
        for (f in factFiles) {
            assertEquals("判据④红：$f 字节被题材模式改动", before[f], after[f])
        }
    }

    @Test
    fun `结构闸_fiction_包对仓内其他包零_import_物理隔离`() {
        val dir = "app/src/main/java/com/example/zhengdao/baopu/state/fiction/"
        val files = listOf("FictionCanon.kt", "IntersexTopology.kt", "FictionOutput.kt", "FictionMode.kt").map { dir + it }
        for (rel in files) {
            val code = SrcRoots.find(rel).readText(Charsets.UTF_8)
                .replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")
            val internal = Regex("import com\\.example\\.zhengdao\\.(?!baopu\\.state\\.fiction)").findAll(code).toList()
            assertTrue("$rel 不许 import 仓内其他包（事实层物理隔离）：$internal", internal.isEmpty())
        }
    }

    @Test
    fun `结构闸_fiction_包零写盘口`() {
        val dir = "app/src/main/java/com/example/zhengdao/baopu/state/fiction/"
        for (rel in listOf("FictionCanon.kt", "IntersexTopology.kt", "FictionOutput.kt", "FictionMode.kt").map { dir + it }) {
            val code = SrcRoots.find(rel).readText(Charsets.UTF_8)
                .replace(Regex("/\\*[\\s\\S]*?\\*/"), "").replace(Regex("//[^\\n]*"), "")
            for (w in listOf("writeText", "writeBytes", "FileOutputStream", "delete", "mkdir", "putString", "remove(")) {
                assertTrue("$rel 不许出现写盘口 $w", w !in code)
            }
        }
    }
}
