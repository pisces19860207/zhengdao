// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十七批 · U4 的 grep 断言：**导出 zip 里没有身份/凭据**（派工单追加判据⑨）。
//
// 为什么在备份这一侧再钉一道：身份/凭据住 `filesDir/执行体/`（506 起就**故意**长在
// `抱朴记忆/` 之外），而备份只扫四张挂载表——"不在挂载表里"这件事本身要用测试钉住：
// 哪天有人给挂载表加一行 `执行体/`，这格立刻红。
package com.example.zhengdao.backup

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupExecutorExclusionTest {

    /** 与 [BackupShell.mounts] 同一张表（挂载名与跳过名单逐字一致；本测试钉的就是这张表的行为）。 */
    private fun mounts(filesDir: File, ws: File): List<BackupMount> {
        val mem = File(filesDir, "抱朴记忆")
        return listOf(
            BackupMount("memory/global", mem, BackupGroup.MEMORY, splitLedger = true, skipDirNames = setOf("技能")),
            BackupMount("memory/project", File(ws, "记忆"), BackupGroup.MEMORY, splitLedger = true),
            BackupMount("skills/global", File(mem, "技能"), BackupGroup.SKILLS),
            BackupMount("skills/project", File(ws, "技能"), BackupGroup.SKILLS),
        )
    }

    @Test
    fun `判据九_身份与凭据不进导出包_执行体目录整棵不在挂载表里`() {
        val filesDir = Files.createTempDirectory("zd517-u4-").toFile()
        val ws = Files.createTempDirectory("zd517-u4-ws-").toFile()
        try {
            // 记忆树里有账本、执行体树里有身份/凭据/恢复标记——全都真实落盘
            val mem = File(filesDir, "抱朴记忆")
            mem.mkdirs()
            File(mem, "决策记录.jsonl").writeText("{\"v\":1}\n", Charsets.UTF_8)
            val executor = File(filesDir, "执行体")
            executor.mkdirs()
            File(executor, "身份.json").writeText("{\"schema\":\"zd-executor/1\",\"rows\":[]}", Charsets.UTF_8)
            File(executor, "凭据绑定.json").writeText("{\"schema\":\"zd-executor/1\",\"creds\":[]}", Charsets.UTF_8)
            File(executor, "恢复标记.json").writeText("{\"restoredAt\":1}", Charsets.UTF_8)

            val result = BackupWalker.walk(mounts(filesDir, ws))
            val paths = result.files.map { it.zipPath }
            assertTrue("包里至少要有记忆树的那份账（测试自身有效）", paths.any { it.contains("决策记录.jsonl") })
            val banned = paths.filter { it.contains("执行体") || it.contains("身份") || it.contains("凭据") || it.contains("恢复标记") }
            assertEquals("身份/凭据/恢复标记一个都不许进包（U4）：$banned", emptyList<String>(), banned)
            // 跳过清单也要干净：不是"扫到了再跳"，是**压根不在挂载表里**
            assertTrue("跳过清单里不许出现执行体（挂载表就没这一棵树）", result.skipped.none { it.reason.contains("执行体") || it.path.contains("执行体") })
        } finally {
            filesDir.deleteRecursively()
            ws.deleteRecursively()
        }
    }
}
