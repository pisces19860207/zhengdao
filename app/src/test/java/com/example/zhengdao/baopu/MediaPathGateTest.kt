// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第一百九十九批 · **门禁**：相册这条路只许有一条（设计 §2.5／§5.3／派工单 §2）。
 *
 * 六条（每条都配一个「它到底在防什么」；268 起截屏只有一条路，判据跟随）：
 * ① **`"Pictures/` 字面量只有一处**：路径只能由 `MediaOwnership.relShotDir/relImageDir` 拼，
 *    别处再写一次就是「第二套路径」——这正是设计 §2.5 点名的那种漂移；
 * ② **`File` 直写 `Pictures/` 在代码里不存在**（派工单原话：视为失败）：
 *    同一行既出现 `Pictures` 又出现 `writeBytes/writeText/FileOutputStream/outputStream(` 一律红；
 * ③ **`MediaStore.` / `contentResolver` 只许出现在 `MediaStoreSink`**：写入与删除的唯一出口；
 * ④ **桥的截图支路零 MediaStore**：落盘经 `MediaPublisher.publish` 一处（268 起 album
 *    分叉已撤，全部截图同一条路）；
 * ⑤ **账本名只有内核一处定义**：`.归属.jsonl` 在别处出现就是第二份真源；
 * ⑥ **177 的目录真源没被搬走**：`SHOT_DIR = "截屏"` 还在（268 起只服务存量迁移）、
 *    `VisionFiles.extraRoot` 还在、引擎仍把 `shotRoot` 传给读图管线——搬走＝模型读不到自己截的图。
 */
class MediaPathGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun mainKt(): List<File> {
        val dir = repoFile("src/main/java/com/example/zhengdao")
        require(dir.isDirectory) { "主源码目录没找到：${dir.absolutePath}" }
        return dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.sortedBy { it.path }.toList()
    }

    /** 剥掉注释行（`//`、块注释里的 `*`）——判据只管代码，KDoc 里说 `Pictures/证道/` 是应该的。 */
    private fun codeLines(f: File): List<Pair<Int, String>> =
        f.readLines().mapIndexed { i, line ->
            (i + 1) to line
        }.filter { (_, line) ->
            val t = line.trim()
            !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/"))
        }

    @Test
    fun `G1_Pictures路径字面量只有一处来源`() {
        val offenders = mutableListOf<String>()
        for (f in mainKt()) {
            if (f.name == "MediaOwnership.kt") continue
            for ((n, line) in codeLines(f)) {
                // `"Pictures/` 这个形态＝在拼路径（说明文案里是「（Pictures/…」不会命中）
                if (line.contains("\"Pictures/")) offenders += "${f.name}:$n  ${line.trim()}"
            }
        }
        assertTrue("相册路径只许在 MediaOwnership 里拼，别处要用 relShotDir()/relImageDir()：$offenders", offenders.isEmpty())
    }

    @Test
    fun `G2_File直写Pictures在代码里不存在`() {
        val writeCalls = listOf("writeBytes(", "writeText(", "FileOutputStream", "outputStream(", "appendText(")
        val offenders = mutableListOf<String>()
        for (f in mainKt()) {
            for ((n, line) in codeLines(f)) {
                if (!line.contains("Pictures")) continue
                writeCalls.firstOrNull { line.contains(it) }?.let {
                    offenders += "${f.name}:$n  ${line.trim()}"
                }
            }
        }
        assertTrue("用户可见的图必须走 ContentResolver（File 写 Pictures 不进相册）：$offenders", offenders.isEmpty())
    }

    @Test
    fun `G3_MediaStore与相册写删只有一个出口`() {
        // 只钉「对 MediaStore 的写/删」这一件事：`contentResolver` 在本仓还有一大批正当用途
        // （SAF 选文件、附件导入、日志导出），把它们一起禁掉＝门禁在管错东西。
        val writes = listOf(
            "MediaStore.",
            "contentResolver.insert(",
            "contentResolver.delete(",
            "contentResolver.update(",
        )
        val offenders = mutableListOf<String>()
        for (f in mainKt()) {
            if (f.name == "MediaStoreSink.kt") continue
            for ((n, line) in codeLines(f)) {
                writes.firstOrNull { line.contains(it) }?.let {
                    offenders += "${f.name}:$n  ${line.trim()}"
                }
            }
        }
        assertTrue("MediaStore 的写/删只许在 MediaStoreSink（唯一出口）：$offenders", offenders.isEmpty())
        // 正例：出口里确实有 insert 与 delete（防止门禁靠「哪儿都没有」蒙过去）
        val sink = repoFile("src/main/java/com/example/zhengdao/baopu/MediaStoreSink.kt").readText()
        assertTrue(sink.contains("resolver.insert("))
        assertTrue(sink.contains("resolver.delete("))
    }

    @Test
    fun `G4_桥截图支路零MediaStore`() {
        val bridge = repoFile("src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt").readText()
        assertFalse("桥不许直接碰 MediaStore（落盘只经 MediaPublisher 一处）", bridge.contains("MediaStore."))
        assertFalse("桥不许拿 contentResolver", bridge.contains("contentResolver"))
        // 落盘必须经 MediaPublisher（268 起全部截图同一条路，分叉不许回潮）
        assertTrue("截图落盘必须经 MediaPublisher", bridge.contains("MediaPublisher.publish("))
    }

    @Test
    fun `G5_账本名只有内核一处定义`() {
        val offenders = mutableListOf<String>()
        for (f in mainKt()) {
            if (f.name == "BaopuKernel.kt") continue
            for ((n, line) in codeLines(f)) {
                if (line.contains("\".归属.jsonl\"")) offenders += "${f.name}:$n  ${line.trim()}"
            }
        }
        assertTrue("账本名只许在内核定义（一处改、处处改）：$offenders", offenders.isEmpty())
        // 正例：内核里确实有（防止门禁靠「哪儿都没有」蒙过去）
        val kernel = repoFile("src/main/java/com/example/zhengdao/baopu/kernel/BaopuKernel.kt").readText()
        assertTrue(kernel.contains("\".归属.jsonl\""))
    }

    @Test
    fun `G6_177的目录真源没被搬走_读图第二根还在`() {
        val bridge = repoFile("src/main/java/com/example/zhengdao/bridge/BrowserCommands.kt").readText()
        // 268 起它只服务存量迁移（MediaMigration 的目标），常量不许删
        assertTrue("存量迁移目标（SHOT_DIR）不许搬走", bridge.contains("internal const val SHOT_DIR = \"截屏\""))
        val vision = repoFile("src/main/java/com/example/zhengdao/baopu/VisionFiles.kt").readText()
        assertTrue("VisionFiles.load 必须保留 extraRoot（截屏夹是第二个读根）", vision.contains("extraRoot"))
        val engine = repoFile("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt").readText()
        assertTrue("引擎读图时仍要把 shotRoot 传进去", engine.contains("extraRoot = shotRoot()"))
    }
}
