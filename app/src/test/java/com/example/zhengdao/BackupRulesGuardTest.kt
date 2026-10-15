// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * 备份范围门禁（§B 顺序 1 / issue #19 / P3-3，2026-10-12）。
 *
 * 钉的是什么：`app/src/main/res/xml/backup_rules.xml` 与 `data_extraction_rules.xml`
 * 必须**逐条排除**大目录，且这两个文件必须仍被 `AndroidManifest.xml` 引用。
 *
 * 为什么值得单独钉：这两份文件出厂就是 AGP 模板（`allowBackup="true"` ＋ 零 `<exclude>`），
 * 一旦有人「顺手回滚模板」或把 manifest 那两行删掉，后果都不会在编译期暴露 ——
 * 而是变成「备份一直失败」或「用户的 rootfs / agent 数据被传上 Google Drive」，
 * 两者都要等到发布后才发现。
 *
 * **2026-10-14（审计第 2 期 R-24）改判据：真解析 XML，不再 grep 文本。**
 * 旧写法 `backupRules.contains("<exclude domain=\"file\" path=\"rootfs\"")` 有两条硬伤：
 * ① 把 `<exclude …/>` 用 `<!-- -->` 包掉（元素其实没了）仍然过 —— 正是"门禁绿、功能死"；
 * ② 它假定属性顺序恰好是 `domain` 在前、`path` 在后，属性换个序就误报。
 * 现在用 DOM 取 `<exclude>` 节点、按 `getAttribute("domain")/("path")` 判存在，
 * 两个问题一并消失（顺带把嵌套位置也钉住：cloud-backup 与 device-transfer 各查各自那份）。
 *
 * 注意：这仍是**源码级门禁**（保证规则还在），不是对 Android 备份框架行为的验证 ——
 * 真机上的备份行为无法在 JVM 单测里观察（`adb shell bmgr` 那套要设备）。
 */
class BackupRulesGuardTest {

    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    /** 原始文本（只用于"模板残留"这类**注释内容**的检查：那本来就是文字问题）。 */
    private val backupRulesRaw: String by lazy { appFile("src/main/res/xml/backup_rules.xml").readText() }
    private val extractionRulesRaw: String by lazy { appFile("src/main/res/xml/data_extraction_rules.xml").readText() }

    private val manifest: String by lazy { appFile("src/main/AndroidManifest.xml").readText() }

    // ── 真解析（R-24）────────────────────────────────────────────────────────

    private fun parseRules(rel: String): Document {
        val f = appFile(rel)
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // 本地工程文件，不需要 DTD / 外部实体；不支持该 feature 的解析器也不该因此失败
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        return factory.newDocumentBuilder().parse(f)
    }

    /** 取 [parent] 下**直接子**元素里 tagName == [tag] 的那些（跳过文本/注释节点）。 */
    private fun children(parent: Element, tag: String): List<Element> {
        val out = ArrayList<Element>()
        val nl = parent.childNodes
        for (i in 0 until nl.length) {
            val n = nl.item(i)
            if (n is Element && n.tagName == tag) out += n
        }
        return out
    }

    private fun uniqueChild(parent: Element, tag: String, where: String): Element {
        val hits = children(parent, tag)
        assertEquals("$where：<$tag> 必须恰好有一个（实际 ${hits.size} 个）", 1, hits.size)
        return hits[0]
    }

    /** 断言 [parent] 下真的有一个 domain/path 都对的 `<exclude>` 元素。 */
    private fun assertHasExclude(parent: Element, where: String, domain: String, path: String) {
        val hit = children(parent, "exclude").any {
            it.getAttribute("domain") == domain && it.getAttribute("path") == path
        }
        assertTrue(
            "$where：缺少**真实的** <exclude domain=\"$domain\" path=\"$path\" /> 节点" +
                "（把元素用注释包掉、或把 domain/path 写错写反，这条都会红）",
            hit,
        )
    }

    /** 云备份/全量备份必须排除的六条（大目录与运行时）。 */
    private val mustExcludeEverywhere = listOf(
        "file" to "rootfs",
        "file" to "termux-proot",
        "file" to "proot-tmp",
        "file" to "home",
        "file" to "oc",
        "external" to "workspace",
    )

    @Test
    fun `全量备份规则逐条排除了大目录`() {
        val root = parseRules("src/main/res/xml/backup_rules.xml").documentElement
        assertEquals(
            "backup_rules.xml 的根节点应是 <full-backup-content>（换了根，这份规则就不生效了）",
            "full-backup-content",
            root.tagName,
        )
        for ((domain, path) in mustExcludeEverywhere) {
            assertHasExclude(root, "backup_rules.xml", domain, path)
        }
        // 这两条查的是**注释内容**（模板残留提示），本来就该按文本查
        assertFalse("模板里的 uncomment 提示还留着，说明这份根本没配", backupRulesRaw.contains("uncomment"))
        assertFalse("模板里的 TODO 还留着", backupRulesRaw.contains("TODO"))
    }

    @Test
    fun `云备份规则逐条排除了大目录`() {
        val root = parseRules("src/main/res/xml/data_extraction_rules.xml").documentElement
        assertEquals(
            "data_extraction_rules.xml 的根节点应是 <data-extraction-rules>",
            "data-extraction-rules",
            root.tagName,
        )
        val cloud = uniqueChild(root, "cloud-backup", "data_extraction_rules.xml")
        for ((domain, path) in mustExcludeEverywhere) {
            assertHasExclude(cloud, "data_extraction_rules.xml 的 <cloud-backup>", domain, path)
        }
        assertFalse("模板里的 TODO 还留着", extractionRulesRaw.contains("TODO"))
    }

    @Test
    fun `设备迁移挡系统层与凭据 用户数据留着`() {
        val root = parseRules("src/main/res/xml/data_extraction_rules.xml").documentElement
        val transfer = uniqueChild(root, "device-transfer", "data_extraction_rules.xml")

        for ((domain, path) in listOf(
            "file" to "rootfs",
            "file" to "termux-proot",
            "file" to "proot-tmp",
        )) {
            assertHasExclude(transfer, "设备迁移（系统层）", domain, path)
        }
        // 2026-10-14（第 2 期审计 N-3）：凭据不跟着近场迁移走 —— 迁出去不可撤销，
        // 且 D2D 传输不加密。这三条谁删谁负责：删了等于把 API key 与
        // oc/serve.log 的明文口令送到新机器上。
        for ((domain, path) in listOf(
            "file" to "home/.claude",
            "file" to "home/.hermes",
            "file" to "oc",
        )) {
            assertHasExclude(transfer, "设备迁移（凭据）", domain, path)
        }
        // 用户数据**本体**在设备迁移里仍故意不排除（同机到同机的近场传输，不经云）。
        // 判据必须是**精确** path="home"：`home/.claude` 这类子路径不算命中它。
        val homeWholeExcluded = children(transfer, "exclude").any {
            it.getAttribute("domain") == "file" && it.getAttribute("path") == "home"
        }
        assertFalse(
            "home 整个目录不该在 device-transfer 里被排除（那会把用户数据也挡在迁移之外）",
            homeWholeExcluded,
        )
    }

    /**
     * 浏览器桥 token 不许跟着备份/迁移走（ERRATA E-129 的坑，2026-10-15 补钉）。
     *
     * 上一个骨架（`bridge/BridgeToken.kt` 的前身）把随机口令明文存进 sharedpref，
     * 而两份备份规则都没排 `domain="sharedpref"` ⇒ 口令跟着云备份与近场迁移走。
     * 现在 token 用独立 prefs 文件 `bridge_token.xml`，三条路（全量备份 / 云备份 /
     * 设备迁移）都按**文件名**排除。
     *
     * 为什么同时钉"不许排掉整个 sharedpref domain"：真有人图省事写
     * `<exclude domain="sharedpref" />`，token 是挡住了，但用户的全部设置项
     * （工作区模式、主题、各类开关）也一起不进备份了 —— 那是拿一个缺陷换另一个缺陷。
     * 判据：**必须有精确 path 的那条**，且**不能有没有 path 的整域排除**。
     */
    @Test
    fun `桥 token 三条路都按名排除_且不许顺手排掉整个 sharedpref 域`() {
        val tokenPath = "bridge_token.xml"

        val full = parseRules("src/main/res/xml/backup_rules.xml").documentElement
        assertHasExclude(full, "全量备份（backup_rules.xml）", "sharedpref", tokenPath)

        val root = parseRules("src/main/res/xml/data_extraction_rules.xml").documentElement
        val cloud = uniqueChild(root, "cloud-backup", "data_extraction_rules.xml")
        assertHasExclude(cloud, "云备份（data_extraction_rules.xml）", "sharedpref", tokenPath)
        val transfer = uniqueChild(root, "device-transfer", "data_extraction_rules.xml")
        assertHasExclude(transfer, "设备迁移（data_extraction_rules.xml）", "sharedpref", tokenPath)

        // 三处都不许出现"没有 path 的整域排除"（会把设置项一起挡掉）
        listOf(full, cloud, transfer).forEach { parent ->
            val whole = children(parent, "exclude").any {
                it.getAttribute("domain") == "sharedpref" && it.getAttribute("path").isBlank()
            }
            assertFalse(
                "出现了没有 path 的 <exclude domain=\"sharedpref\" /> —— 那会把用户设置项" +
                    "一起挡在备份之外（用一个缺陷换另一个缺陷）。只按文件名排 bridge_token.xml。",
                whole,
            )
        }
    }

    @Test
    fun `两份规则都还挂在 manifest 上`() {        assertTrue(
            "AndroidManifest 少了 android:fullBackupContent=@xml/backup_rules",
            manifest.contains("""android:fullBackupContent="@xml/backup_rules""""),
        )
        assertTrue(
            "AndroidManifest 少了 android:dataExtractionRules=@xml/data_extraction_rules",
            manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""),
        )
        assertTrue(
            "allowBackup 被关掉的话，这两份规则就成了摆设，门禁的前提变了 —— 请连同注释一起改",
            manifest.contains("""android:allowBackup="true""""),
        )
    }
}
