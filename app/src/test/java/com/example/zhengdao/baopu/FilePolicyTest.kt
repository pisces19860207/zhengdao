// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百八十七批（2026-10-30）：**全盘可读写**的判据本体测试。
//
// 判据来自派工单 287 的「三道硬下限」第三条与四条负例：
// - 系统目录、`/data` 非本应用区、别的应用的 `Android/data/<包名>`、`.ssh`/keystore ⇒ **一律拒**；
// - 本应用自己的私有目录 ⇒ 放行（写自己的家不算越界）；
// - 普通外部路径（相册、Download、`/storage/emulated/0/小说…`）⇒ 放行（这就是 287 的全部意义）；
// - 负例「系统目录可写」：`fileAccessCheck` 对系统目录必须非 null（各调用点据此 Rejected）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilePolicyTest {

    private val pkg = "com.example.zhengdao"

    // ── 放行：全盘（287 的正向判据）──────────────────────────────────────

    @Test
    fun `放行_共享存储里的任意目录`() {
        listOf(
            "/storage/emulated/0/小说和人物资料/我的库/笔记.md",
            "/storage/emulated/0/Download/证道/产物/设定.md",
            "/storage/emulated/0/DCIM/Camera/IMG_0001.jpg",
            "/sdcard/Notes/todo.md",
        ).forEach { assertNull("应放行：$it", FilePolicy.denyReasonOf(it, pkg)) }
    }

    @Test
    fun `放行_本应用自己的私有目录`() {
        listOf(
            "/data/data/$pkg/files/baopu/persona.md",
            "/data/user/0/$pkg/files/workspace/x.md",
            "/data/user_de/0/$pkg/files/x.md",
            "/storage/emulated/0/Android/data/$pkg/files/workspace/x.md",
        ).forEach { assertNull("自己的家要放行：$it", FilePolicy.denyReasonOf(it, pkg)) }
    }

    // ── 拒绝：系统区 ────────────────────────────────────────────────────

    @Test
    fun `负例_系统目录一律拒`() {
        listOf(
            "/system/build.prop",
            "/system/bin/sh",
            "/vendor/lib64/libc.so",
            "/proc/self/cmdline",
            "/sys/kernel/mm",
            "/dev/block/sda",
            "/etc/hosts",
        ).forEach { assertNotNull("必须拒：$it", FilePolicy.denyReasonOf(it, pkg)) }
    }

    @Test
    fun `负例_同前缀兄弟目录骗不过`() {
        // 「/system」的字符串前缀兄弟（/systemx）不是系统目录——按组件比较才对
        assertNull(FilePolicy.denyReasonOf("/storage/emulated/0/systemx/a.md", pkg))
        // 反过来：/system 之下的任意深度都要拒
        assertNotNull(FilePolicy.denyReasonOf("/system/../system/a", pkg))
    }

    // ── 拒绝：/data 非应用区 ────────────────────────────────────────────

    @Test
    fun `负例_别的应用的私有目录拒`() {
        listOf(
            "/data/data/com.tencent.mm/shared_prefs/a.xml",
            "/data/user/0/com.other.app/files/x.db",
            "/data/local/tmp/x.sh",
            "/data/misc/wifi/WifiConfigStore.xml",
        ).forEach { assertNotNull("必须拒：$it", FilePolicy.denyReasonOf(it, pkg)) }
    }

    // ── 拒绝：别的应用的外置数据目录 ────────────────────────────────────

    @Test
    fun `负例_Android_data 与 obb 的别的包拒`() {
        listOf(
            "/storage/emulated/0/Android/data/com.tencent.mm/files/x",
            "/storage/emulated/0/Android/obb/com.other.game/main.1.obb",
        ).forEach { assertNotNull("必须拒：$it", FilePolicy.denyReasonOf(it, pkg)) }
    }

    // ── 拒绝：凭据 ──────────────────────────────────────────────────────

    @Test
    fun `负例_凭据目录与密钥文件拒`() {
        listOf(
            "/storage/emulated/0/.ssh/id_rsa",
            "/data/data/$pkg/files/.ssh/known_hosts",
            "/storage/emulated/0/keys/keystore/x",
            "/storage/emulated/0/app/release.jks",
            "/storage/emulated/0/app/sign.keystore",
            "/storage/emulated/0/keys/id_ed25519",
            "/home/u/.gnupg/private-keys-v1.d/x.key",
        ).forEach { assertNotNull("必须拒：$it", FilePolicy.denyReasonOf(it, pkg)) }
    }

    @Test
    fun `拒绝文案是人话_说清是什么地方`() {
        val reason = FilePolicy.denyReasonOf("/system/bin/sh", pkg)
        assertNotNull(reason)
        assertTrue("文案要说明是系统目录：$reason", reason!!.contains("系统目录"))
        val ssh = FilePolicy.denyReasonOf("/storage/emulated/0/.ssh/id_rsa", pkg)
        assertTrue("文案要说明是凭据：$ssh", ssh!!.contains("凭据"))
    }

    // ── 显示口径（287 定稿比喻第三条）──────────────────────────────────

    @Test
    fun `显示_工作区内相对_工作区外绝对`() {
        val root = File("/storage/emulated/0/Download/证道")
        assertEquals(
            "资料库/设定.md",
            FilePaths.display(root, File("/storage/emulated/0/Download/证道/资料库/设定.md")),
        )
        val outside = File("/storage/emulated/0/小说和人物资料/笔记.md")
        assertTrue(
            "工作区外要显示绝对路径（相对路径会变成 ../../ 那种糊的东西）",
            FilePaths.display(root, outside).contains("小说和人物资料"),
        )
    }

    // ── 备份上限（287：>5MB 不备份，但要说）────────────────────────────

    @Test
    fun `备份上限_5MB`() {
        assertEquals(5L * 1024 * 1024, FilePolicy.BACKUP_MAX_BYTES)
        assertTrue("刚好 5MB 仍然备份", !backupSkippedFor(FilePolicy.BACKUP_MAX_BYTES))
        assertTrue("超过 5MB 不备份", backupSkippedFor(FilePolicy.BACKUP_MAX_BYTES + 1))
    }

    @Test
    fun `自检出口_凭据目录返回拒绝原文`() {
        // 这里用凭据目录而不是 /system：JVM 单测在 Windows 上会把 `/system/x` 解释成
        // `C://system//x`（见 FilePolicy.denyReason 的平台口径说明），系统区那一条由
        // 纯函数 `denyReasonOf("/system/…")` 直测（本文件上半部分）。
        assertNull(fileAccessCheck(File("/storage/emulated/0/Download/x.md"), "x.md"))
        val reason = fileAccessCheck(File("/storage/emulated/0/.ssh/id_rsa"), ".ssh/id_rsa")
        assertNotNull("凭据目录必须被工具层自检拦住", reason)
        assertTrue(reason!!.startsWith("路径被拒："))
    }
}
