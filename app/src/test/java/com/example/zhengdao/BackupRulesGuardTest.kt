// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份范围门禁（§B 顺序 1 / issue #19 / P3-3，2026-10-12）。
 *
 * 钉的是什么：`app/src/main/res/xml/backup_rules.xml` 与 `data_extraction_rules.xml`
 * 必须**逐条排除**大目录，且这两个文件必须仍被 `AndroidManifest.xml` 引用。
 *
 * 为什么值得单独钉：这两份文件出厂就是 AGP 模板（`allowBackup="true"` ＋ 零 `<exclude>`），
 * 一旦有人「顺手回滚模板」或把 manifest 那两行删掉，后果都不会在编译期暴露 ——
 * 而是变成「备份一直失败」或「用户的 rootfs / agent 数据被传上 Google Drive」，
 * 两者都要等到发布后才发现。用真实路径读文件断言，比注释里写一句可靠。
 *
 * 注意：这是**文本门禁**（保证规则还在），不是对 Android 备份框架行为的验证 ——
 * 真机上的备份行为无法在 JVM 单测里观察（`adb shell bmgr` 那套要设备）。
 */
class BackupRulesGuardTest {

    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private val backupRules: String by lazy {
        appFile("src/main/res/xml/backup_rules.xml").readText()
    }

    private val extractionRules: String by lazy {
        appFile("src/main/res/xml/data_extraction_rules.xml").readText()
    }

    private val manifest: String by lazy { appFile("src/main/AndroidManifest.xml").readText() }

    /** 取两个标签之间的片段（XML 里的小工具，够用就行）。 */
    private fun section(text: String, tag: String): String {
        val open = "<$tag>"
        val close = "</$tag>"
        val i = text.indexOf(open)
        assertTrue("找不到 <$tag> 段", i >= 0)
        val j = text.indexOf(close, i)
        assertTrue("找不到 </$tag>", j > i)
        return text.substring(i, j)
    }

    /** 云备份/全量备份必须排除的六条（大目录与运行时）。 */
    private val mustExcludeEverywhere = listOf(
        """domain="file" path="rootfs"""",
        """domain="file" path="termux-proot"""",
        """domain="file" path="proot-tmp"""",
        """domain="file" path="home"""",
        """domain="file" path="oc"""",
        """domain="external" path="workspace"""",
    )

    @Test
    fun `全量备份规则逐条排除了大目录`() {
        for (rule in mustExcludeEverywhere) {
            assertTrue(
                "backup_rules.xml 少了排除：$rule（这就是 issue #19：rootfs/home/oc 会被传上云）",
                backupRules.contains("<exclude $rule")
            )
        }
        assertFalse("模板里的 uncomment 提示还留着，说明这份根本没配", backupRules.contains("uncomment"))
        assertFalse("模板里的 TODO 还留着", backupRules.contains("TODO"))
    }

    @Test
    fun `云备份规则逐条排除了大目录`() {
        val cloud = section(extractionRules, "cloud-backup")
        for (rule in mustExcludeEverywhere) {
            assertTrue(
                "data_extraction_rules.xml 的 <cloud-backup> 少了排除：$rule",
                cloud.contains("<exclude $rule")
            )
        }
        assertFalse("模板里的 TODO 还留着", extractionRules.contains("TODO"))
    }

    @Test
    fun `设备迁移挡系统层与凭据 用户数据留着`() {
        val transfer = section(extractionRules, "device-transfer")
        for (rule in listOf(
            """domain="file" path="rootfs"""",
            """domain="file" path="termux-proot"""",
            """domain="file" path="proot-tmp"""",
        )) {
            assertTrue("设备迁移必须排除系统层：$rule", transfer.contains("<exclude $rule"))
        }
        // 2026-10-14（第 2 期审计 N-3）：凭据不跟着近场迁移走 —— 迁出去不可撤销，
        // 且 D2D 传输不加密。这三条谁删谁负责：删了等于把 API key 与
        // oc/serve.log 的明文口令送到新机器上。
        for (rule in listOf(
            """domain="file" path="home/.claude"""",
            """domain="file" path="home/.hermes"""",
            """domain="file" path="oc"""",
        )) {
            assertTrue("设备迁移必须排除凭据：$rule", transfer.contains("<exclude $rule"))
        }
        // 用户数据**本体**在设备迁移里仍故意不排除（同机到同机的近场传输，不经云）。
        // 注意这里用带尾引号的整目录写法：`home/.claude` 这类子路径排除不会误命中它。
        assertFalse(
            "home 整个目录不该在 device-transfer 里被排除（那会把用户数据也挡在迁移之外）",
            transfer.contains("""<exclude domain="file" path="home"""")
        )
    }

    @Test
    fun `两份规则都还挂在 manifest 上`() {
        assertTrue(
            "AndroidManifest 少了 android:fullBackupContent=@xml/backup_rules",
            manifest.contains("""android:fullBackupContent="@xml/backup_rules"""")
        )
        assertTrue(
            "AndroidManifest 少了 android:dataExtractionRules=@xml/data_extraction_rules",
            manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules"""")
        )
        assertTrue(
            "allowBackup 被关掉的话，这两份规则就成了摆设，门禁的前提变了 —— 请连同注释一起改",
            manifest.contains("""android:allowBackup="true"""")
        )
    }
}
