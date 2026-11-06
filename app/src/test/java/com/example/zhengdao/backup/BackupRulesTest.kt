// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百九十一批 · 纯判据（hash／密钥识别／路径安全／设置白名单与差异／挂载表正反一致）。
package com.example.zhengdao.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackupRulesTest {

    // ── hash ────────────────────────────────────────────────────────────────

    @Test
    fun sha256对上标准向量() {
        // FIPS 180-2 附录里的 "abc" 向量（公开标准值，不是本机算出来的）
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BackupRules.sha256Of("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun 空文件的hash是e3b0而不是空串() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            BackupRules.sha256Of(ByteArray(0)),
        )
    }

    @Test
    fun 读不出来的文件返回空串而不是当空文件() {
        assertFalse("空串必须过不了 isSha256Hex", BackupRules.isSha256Hex(BackupRules.sha256Of(File("/no/such/zd491"))))
    }

    // ── 密钥识别 ────────────────────────────────────────────────────────────

    @Test
    fun 键名像密钥的一律认出() {
        for (k in listOf("user_keys_openai", "api_key", "custom_token", "auth", "PASSWORD", "cookie_jar", "user_key_anthropic")) {
            assertTrue("应判为密钥：$k", BackupRules.isSecretPrefKey(k))
        }
    }

    @Test
    fun 正常设置不许被误杀() {
        for (k in listOf("workspace_mode", "workspace_path", "ui_language", "keyboard_layout", "monkey_state", "keepalive_mode")) {
            assertFalse("不该判为密钥：$k", BackupRules.isSecretPrefKey(k))
        }
    }

    // ── 路径安全 ────────────────────────────────────────────────────────────

    @Test
    fun 越界与绝对路径一律不安全() {
        val bad = listOf("", "/etc/passwd", "../x", "a/../../b", "a/./b", "a\\b", "C:/x", "a//b", "a/\u0000b")
        for (p in bad) assertFalse("应判不安全：$p", BackupRules.isSafeEntryPath(p))
        assertTrue(BackupRules.isSafeEntryPath("memory/global/决策记录.jsonl"))
    }

    // ── 设置白名单 ──────────────────────────────────────────────────────────

    @Test
    fun 设置导出剔掉密钥与集合只留标量() {
        val dump = BackupRules.dumpSettings(
            mapOf(
                "zhengdao-settings" to mapOf(
                    "workspace_mode" to "default",
                    "some_flag" to true,
                    "some_int" to 7,
                    "some_long" to 9_000_000_000L,
                    "some_float" to 0.5f,
                    "user_keys_openai" to "sk-live-xxx",
                    "last_tags" to setOf("a", "b"),
                ),
                "zhengdao-appearance" to mapOf("appearance" to "dark"),
            ),
        )
        assertEquals("两个文件共 6 个标量", 6, dump.keyCount)
        assertTrue(
            "密钥与集合都要在 dropped 里（带文件名）：${dump.dropped}",
            dump.dropped.containsAll(listOf("zhengdao-settings/user_keys_openai", "zhengdao-settings/last_tags")),
        )
        assertFalse(dump.json.contains("sk-live-xxx"))
        // 再解回来
        val back = BackupRules.parseSettings(dump.json) ?: error("解不回来")
        val main = back.getValue("zhengdao-settings")
        assertEquals("default", main["workspace_mode"])
        assertEquals(true, main["some_flag"])
        assertEquals(7, main["some_int"])
        assertEquals(9_000_000_000L, main["some_long"])
        assertEquals("dark", back.getValue("zhengdao-appearance")["appearance"])
    }

    @Test
    fun 设置快照被改坏时解析返回null() {
        assertNull(BackupRules.parseSettings("{}"))
        assertNull(BackupRules.parseSettings("不是 json"))
        val dump = BackupRules.dumpSettings(mapOf("zhengdao-settings" to mapOf("a" to 1)))
        assertNull(BackupRules.parseSettings(dump.json.replace("zhengdao-settings", "other")))
    }

    @Test
    fun 设置差异分三档() {
        val diff = BackupRules.diffSettings(
            mapOf("f" to mapOf("same" to "v", "changed" to "old")),
            mapOf("f" to mapOf("same" to "v", "changed" to "new", "brand_new" to "x")),
        )
        assertEquals(1, diff.added)
        assertEquals(1, diff.changed)
        assertEquals(1, diff.same)
        assertTrue(diff.isConflict)
        assertTrue("空差异", BackupRules.diffSettings(emptyMap(), emptyMap()).isEmpty)
        assertTrue("整份文件都没有时按新增算", BackupRules.diffSettings(emptyMap(), mapOf("f" to mapOf("k" to 1))).added == 1)
    }

    @Test
    fun 数字跨类型按值比() {
        val diff = BackupRules.diffSettings(mapOf("f" to mapOf("n" to 7L)), mapOf("f" to mapOf("n" to 7)))
        assertEquals("7L 与 7 是同一个值", 1, diff.same)
        assertEquals(0, diff.changed)
    }

    // ── 挂载表 ──────────────────────────────────────────────────────────────

    private val mounts = listOf(
        BackupMount("memory/global", File("/live/mem"), BackupGroup.MEMORY, splitLedger = true, skipDirNames = setOf("技能")),
        BackupMount("skills/global", File("/live/mem/技能"), BackupGroup.SKILLS),
        BackupMount("memory/project", File("/live/ws/抱朴记忆"), BackupGroup.MEMORY, splitLedger = true),
    )

    @Test
    fun 前缀不许互相吞并() {
        assertNull("memory/globalx 不该被 memory/global 吃掉", BackupLayout.resolve(mounts, "memory/globalx/文件.md"))
        assertNull("chat/ 没有落点", BackupLayout.resolve(mounts, "chat/baopu.db"))
        assertNull("settings 没有文件落点", BackupLayout.resolve(mounts, BackupImporter.SETTINGS_PATH))
    }

    @Test
    fun 正反算一致() {
        for ((mount, rel) in listOf(
            mounts[0] to "决策记录.jsonl",
            mounts[1] to "整理书稿/SKILL.md",
            mounts[2] to "人设-本项目.md",
        )) {
            val zipPath = BackupLayout.zipPathOf(mount, rel)
            val back = BackupLayout.resolve(mounts, zipPath) ?: error("反查不到：$zipPath")
            assertEquals(File(mount.liveRoot, rel).path.replace('\\', '/'), back.live.path.replace('\\', '/'))
            assertEquals(BackupLayout.groupOf(mount, rel), back.group)
        }
    }

    @Test
    fun 账本按扩展名归组而路径照落点走() {
        val ledgerZip = BackupLayout.zipPathOf(mounts[0], "漂移.json")
        assertEquals("memory/global/漂移.json", ledgerZip)
        assertEquals(BackupGroup.LEDGER, BackupLayout.resolve(mounts, ledgerZip)!!.group)
        assertEquals(BackupGroup.MEMORY, BackupLayout.resolve(mounts, "memory/global/人设.md")!!.group)
    }

    @Test
    fun 条目状态三分() {
        val dir = kotlin.io.path.createTempDirectory("zd491-state").toFile()
        try {
            val f = File(dir, "a.md")
            f.writeText("hello\n", Charsets.UTF_8)
            val sha = BackupRules.sha256Of(f)
            assertEquals(BackupRules.ItemState.SAME, BackupRules.stateOf(f, sha))
            assertEquals(BackupRules.ItemState.CONFLICT, BackupRules.stateOf(f, "0".repeat(64)))
            assertEquals(BackupRules.ItemState.NEW, BackupRules.stateOf(File(dir, "missing.md"), sha))
            assertEquals(BackupRules.ItemState.UNTARGETED, BackupRules.stateOf(null, sha))
        } finally {
            dir.deleteRecursively()
        }
    }
}
