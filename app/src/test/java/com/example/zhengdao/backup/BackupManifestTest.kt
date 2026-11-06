// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十一批 · 清单的编解码与**校验**（清单是外部输入，一律不信）。
package com.example.zhengdao.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {

    private fun sample(): BackupManifest = BackupManifest(
        appVersion = "2.2.0",
        createdAt = 1_762_400_000_000L,
        createdAtText = "2026-11-06 14:13:20",
        includesChat = false,
        entries = listOf(
            BackupEntry("memory/global/决策记录.jsonl", BackupGroup.LEDGER, 12L, "a".repeat(64)),
            BackupEntry("skills/global/整理书稿/SKILL.md", BackupGroup.SKILLS, 3L, "b".repeat(64)),
            BackupEntry(BackupImporter.SETTINGS_PATH, BackupGroup.SETTINGS, 7L, "c".repeat(64)),
        ),
        skipped = listOf(BackupSkip("memory/global/大日志.txt", "单文件太大")),
    )

    @Test
    fun 编码再解码逐字段一致() {
        val m = sample()
        val r = BackupManifestCodec.decode(BackupManifestCodec.encode(m))
        val back = (r as? ManifestRead.Ok)?.manifest ?: error("解不回来：$r")
        assertEquals(m.appVersion, back.appVersion)
        assertEquals(m.createdAt, back.createdAt)
        assertEquals(m.createdAtText, back.createdAtText)
        assertEquals(m.includesChat, back.includesChat)
        assertEquals(m.entries, back.entries)
        assertEquals(m.entryCount, back.entryCount)
        assertEquals(m.totalBytes, back.totalBytes)
        assertEquals(m.skipped, back.skipped)
        assertEquals(1, back.countIn(BackupGroup.SKILLS))
    }

    /** 改一个字段再解码：每个拒绝都要有具体理由，不许只给"不合格"。 */
    private fun decodeOf(mutate: (String) -> String): ManifestRead =
        BackupManifestCodec.decode(mutate(BackupManifestCodec.encode(sample())))

    private fun reasonOf(r: ManifestRead): String {
        assertTrue("本应拒绝，结果 $r", r is ManifestRead.Bad)
        return (r as ManifestRead.Bad).reason
    }

    @Test
    fun 不是证道的包要说清是标签不对() {
        val why = reasonOf(decodeOf { it.replace("zhengdao-backup", "other-app") })
        assertTrue(why, why.contains("zhengdao-backup"))
    }

    @Test
    fun 未来版本要说清是版本高() {
        val why = reasonOf(decodeOf { it.replace("\"formatVersion\": 1", "\"formatVersion\": 9") })
        assertTrue(why, why.contains("更新版"))
    }

    @Test
    fun 条目数对不上要拒() {
        val why = reasonOf(decodeOf { it.replace("\"entryCount\": 3", "\"entryCount\": 4") })
        assertTrue(why, why.contains("条目数"))
    }

    @Test
    fun 字节总数对不上要拒() {
        val why = reasonOf(decodeOf { it.replace("\"totalBytes\": 22", "\"totalBytes\": 23") })
        assertTrue(why, why.contains("字节总数"))
    }

    @Test
    fun hash形状不合法要拒() {
        val bad = BackupManifestCodec.encode(sample()).replace("c".repeat(64), "c".repeat(10))
        val why = reasonOf(BackupManifestCodec.decode(bad))
        assertTrue(why, why.contains("sha256"))
    }

    @Test
    fun 路径带上级目录要拒() {
        val bad = BackupManifestCodec.encode(sample()).replace("memory/global/决策记录.jsonl", "../../etc/passwd")
        val why = reasonOf(BackupManifestCodec.decode(bad))
        assertTrue(why, why.contains("路径不合法"))
    }

    @Test
    fun 重复条目要拒() {
        val bad = BackupManifestCodec.encode(sample()).replace(
            "skills/global/整理书稿/SKILL.md",
            "memory/global/决策记录.jsonl",
        )
        val why = reasonOf(BackupManifestCodec.decode(bad))
        assertTrue(why, why.contains("重复"))
    }

    @Test
    fun 不认识的分组要拒() {
        val bad = BackupManifestCodec.encode(sample()).replace("\"group\": \"skills\"", "\"group\": \"whatever\"")
        val why = reasonOf(BackupManifestCodec.decode(bad))
        assertTrue(why, why.contains("分组"))
    }

    @Test
    fun 不是JSON要拒而不是抛异常() {
        val why = reasonOf(BackupManifestCodec.decode("{"))
        assertTrue(why, why.contains("JSON"))
    }
}
