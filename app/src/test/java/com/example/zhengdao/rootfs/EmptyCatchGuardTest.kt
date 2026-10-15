package com.example.zhengdao.rootfs

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2-7（issue #17）：空 catch 全仓清零的守卫。
 *
 * 台账原文「空 catch 只剩 2 处」；本刀开工时实测是 **3 处**（数量会漂，所以守卫按
 * "一个都不许有"写，而不是钉死数字）：
 * - `rootfs/RootfsDownloader.kt` releaseIdleResources 两处（连接池 evictAll）
 * - `terminal/ProotLauncher.kt` proot/loader 释放后的 chmod 一处
 *   （第⑳刀注：该 chmod 随 assets→jniLibs 迁移**整体删除**——四件套由安装器落盘、
 *   App 无需也无法 chmod。best-effort 失败落日志的纪律由新的「戳记文件写失败」
 *   继承，见下方断言。）
 *
 * 立场：best-effort 的操作**可以**失败后继续，但不能**静默**——项目踩过太多
 * "吞掉异常 ⇒ 现场只剩一个看不懂的下游错误"的坑（E-054 保活锁、第⑥刀 Looper 崩）。
 * 现在的口径：要么降级为 debug 日志（RootfsDownloader），要么落 RunLog（ProotLauncher）。
 */
class EmptyCatchGuardTest {

    /** 单行/多行空 catch：`catch (x: T) {}` 或 `catch (x: T) {\n}`。 */
    private val emptyCatch = Regex("""catch\s*\([^)]*\)\s*\{\s*\}""")

    @Test
    fun `主源码不许有空 catch`() {
        val root = File("src/main/java")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val hit = emptyCatch.findAll(f.readText()).toList()
                if (hit.isEmpty()) null else f.path to hit.map { it.value }
            }
            .toList()
        assertTrue(
            "发现 ${offenders.size} 处空 catch（P2-7 要求清零）：" +
                offenders.joinToString("；") { (path, hits) -> "$path ×${hits.size}" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `门禁_连接池清空与 chmod 的失败都落了日志`() {
        val downloader = File("src/main/java/com/example/zhengdao/rootfs/RootfsDownloader.kt").readText()
        assertTrue(
            "releaseIdleResources 的 evictAll 失败必须落日志",
            downloader.contains("连接池清空失败（best-effort，忽略）"),
        )
        val launcher = File("src/main/java/com/example/zhengdao/terminal/ProotLauncher.kt").readText()
        assertTrue(
            "best-effort 失败必须落日志（第⑳刀起：chmod 已删，由「戳记文件写失败」继承该纪律）",
            launcher.contains("戳记文件写失败") && launcher.contains("RunLog.log(\"警告: 戳记文件写失败"),
        )
    }
}
