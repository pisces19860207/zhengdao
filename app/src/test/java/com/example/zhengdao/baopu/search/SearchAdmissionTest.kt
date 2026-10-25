// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十七批（派工单 §1④、§2）：**写死**的排除清单与准入判据。
//
// 覆盖派工单 §2 三条负例的可测半边：
// - 「扫描 `Android/data`」→ 命中排除且**一行 doc 都不建**（哪怕手机根已授予、路径确实在根内）；
// - 「未授权目录搜不到（甚至不建 doc 行）」→ 根外 ⇒ OUT_OF_SCOPE ⇒ 不建行，只留计数；
// - 「偷全盘扫」的另一半在 `SearchScopeTest`（没授予不落 PHONE 行），这里兜「扫到的东西越界也不入账」。
//
// 路径全部用**合成的手机路径**（`/storage/emulated/0/…`）：判据是纯词法的（组件表 + 归一），
// 不碰真盘 ⇒ Windows 的 JVM 测试机上也能逐条断言；`File("/storage/…")` 在 Windows 下会补当前
// 盘符，根与候选补的是同一个盘符 ⇒ `startsWith` 的组件比较结论不变。
package com.example.zhengdao.baopu.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchAdmissionTest {

    private val phoneRoot = "/storage/emulated/0"
    private val homeRoot = "/storage/emulated/0/Download/证道"

    private fun f(path: String): File = File(path)

    // ── 放行侧（正常内容必须搜得到：排除清单不能把用户笔记排掉） ──────────────

    @Test
    fun `正常笔记在授予根内_放行且给出相对路径`() {
        val root = f(homeRoot)
        val target = f("$homeRoot/Documents/体检报告.md")
        assertEquals(Call.IN_SCOPE, SearchAdmission.call(root, target))
        assertEquals("相对路径只用 / 分隔、不带前导斜杠、不含根前缀", "Documents/体检报告.md", SearchAdmission.relativePath(root, target))
    }

    @Test
    fun `深层普通目录不因名字被误伤`() {
        // 反例守卫：排除清单是**精确名**比对（backup/logs/rootfs…），不是「路径里含有这个词」。
        // 用户写「我的backups」「截屏笔记」这类目录名不能被排掉——那才是这个 App 的主用途。
        listOf(
            "$homeRoot/我的backups/计划.md",
            "$homeRoot/截屏笔记/会议.md",
            "$homeRoot/android-studio-学习笔记.md",
            "$homeRoot/keyboard-shortcuts.md",
            "$homeRoot/monitoring/keynote.md",
        ).forEach { p ->
            assertNull("「$p」不该命中任何排除", SearchAdmission.exclusion(f(p)))
            assertEquals(Call.IN_SCOPE, SearchAdmission.call(f(homeRoot), f(p)))
        }
    }

    // ── 排除清单（派工单 §1④ 逐条） ──────────────────────────────────────────

    @Test
    fun `表驱动_Android三层各归自己的类`() {
        val cases = listOf(
            "$phoneRoot/Android/data/com.tencent.mm/MicroMsg/x.dat" to Exclusion.ANDROID_DATA,
            "$phoneRoot/Android/obb/com.miHoYo.Genshin/patch.obb" to Exclusion.ANDROID_OBB,
            "$phoneRoot/Android/media/com.whatsapp/WhatsApp/x.jpg" to Exclusion.ANDROID_ANY,
            "$phoneRoot/ANDROID/data/com.other.app/x.dat" to Exclusion.ANDROID_DATA,
            "$phoneRoot/Documents/Android/data/x/y.md" to Exclusion.ANDROID_DATA,
        )
        cases.forEach { (path, want) ->
            assertEquals("「$path」", want, SearchAdmission.exclusion(f(path)))
        }
    }

    @Test
    fun `表驱动_点开头备份终端与截图都排`() {
        val cases = listOf(
            "$phoneRoot/.baopu-spill/spill.md" to Exclusion.DOT,
            "$phoneRoot/Documents/.ssh/x" to Exclusion.DOT,
            "$phoneRoot/backup/2026-01.md" to Exclusion.BACKUP,
            "$phoneRoot/Backup/2026-02.md" to Exclusion.BACKUP,
            "$phoneRoot/Download/证道/rootfs/bin/proot" to Exclusion.TERMINAL,
            "$phoneRoot/Pictures/screenshots/1.png" to Exclusion.TERMINAL,
            "$phoneRoot/Pictures/截屏/Screenshot_2026.png" to Exclusion.TERMINAL,
            "$phoneRoot/logs/run.md" to Exclusion.TERMINAL,
            "$phoneRoot/cache/tmp.md" to Exclusion.TERMINAL,
            "$phoneRoot/agents/影子.md" to Exclusion.TERMINAL,
        )
        cases.forEach { (path, want) ->
            assertEquals("「$path」", want, SearchAdmission.exclusion(f(path)))
        }
    }

    @Test
    fun `表驱动_密钥名与系统垃圾名`() {
        val cases = listOf(
            "$phoneRoot/Documents/server.key" to Exclusion.SECRET,
            "$phoneRoot/Documents/Server.KEY" to Exclusion.SECRET,
            "$phoneRoot/Documents/release.keystore" to Exclusion.SECRET,
            "$phoneRoot/Documents/keystore.jks" to Exclusion.SECRET,
            "$phoneRoot/Documents/trust.p12" to Exclusion.SECRET,
            "$phoneRoot/.ssh/id_rsa" to Exclusion.SECRET,
            "$phoneRoot/Download/id_ed25519" to Exclusion.SECRET,
            "$phoneRoot/Download/id_ecdsa.pub" to Exclusion.SECRET,
            "$phoneRoot/Download/.netrc" to Exclusion.SECRET,
            "$phoneRoot/Download/.git-credentials" to Exclusion.SECRET,
            "$phoneRoot/Pictures/.DS_Store" to Exclusion.JUNK_NAME,
            "$phoneRoot/Pictures/Thumbs.db" to Exclusion.JUNK_NAME,
            "$phoneRoot/Pictures/._photo.jpg" to Exclusion.JUNK_NAME,
        )
        cases.forEach { (path, want) ->
            assertEquals("「$path」", want, SearchAdmission.exclusion(f(path)))
        }
    }

    @Test
    fun `负例_密钥名判据不牵连正常文件`() {
        // 「带 key 这个词」不等于密钥：误伤一次，用户就少搜到一篇笔记，而且他看不出为什么。
        listOf("keynote.md", "monkey.md", "key-derivation-讨论.txt", "turnkey.md", "keyboard.md").forEach { n ->
            assertFalse("普通文件名「$n」被判成密钥", SearchAdmission.isSecretName(n))
        }
        listOf("id_rsa", "id_dsa.pub", "a.key", "a.P12", ".netrc", "credentials.json", "x.jks").forEach { n ->
            assertTrue("密钥名「$n」没认出来", SearchAdmission.isSecretName(n))
        }
    }

    @Test
    fun `判序_多条命中时按声明顺序给第一条`() {
        // 原因只给人看，不参与判据（两条都排 ⇒ 结果一样）。钉死顺序，报告里的计数才不会随重构漂。
        assertEquals(Exclusion.SECRET, SearchAdmission.exclusion(f("$phoneRoot/.ssh/id_rsa")))
        assertEquals(Exclusion.SECRET, SearchAdmission.exclusion(f("$phoneRoot/backup/.netrc")))
        assertEquals(Exclusion.JUNK_NAME, SearchAdmission.exclusion(f("$phoneRoot/.DS_Store")))
        assertEquals(Exclusion.JUNK_NAME, SearchAdmission.exclusion(f("$phoneRoot/Android/data/x/.DS_Store")))
        assertEquals(Exclusion.DOT, SearchAdmission.exclusion(f("$phoneRoot/Android/data/com.x/.config/y.md")))
        assertEquals(Exclusion.BACKUP, SearchAdmission.exclusion(f("$phoneRoot/rootfs/backup/x.md")))
    }

    // ── 越界（复用写侧那一把 PathGuard，不另造第二套） ─────────────────────────

    @Test
    fun `负例_同前缀兄弟目录不算在根内`() {
        // PathGuard 的口径是「组件级」：/storage/emulated/0-evil 不是 /storage/emulated/0 的孩子。
        val outside = f("$phoneRoot-evil/Documents/别人的.md")
        assertEquals(Call.OUT_OF_SCOPE, SearchAdmission.call(f(phoneRoot), outside))
        assertNull(SearchAdmission.relativePath(f(phoneRoot), outside))
    }

    @Test
    fun `负例_两点逃逸不算在根内`() {
        // 归一之后仍在根外 ⇒ 越界。这条只用「没命中排除」的路径：
        // 排除优先于范围（上面 `判序_排除优先于范围` 钉的就是那个次序），混用会看不出判据走的是哪一支。
        val escaped = f("$homeRoot/Documents/../../Documents/y.md")
        assertEquals(Call.OUT_OF_SCOPE, SearchAdmission.call(f(homeRoot), escaped))
        assertNull("逃逸路径的相对写法必须是 null：relativize 会给 ../../ 串，进库就是一份假账", SearchAdmission.relativePath(f(homeRoot), escaped))
        val legitInside = f("$homeRoot/Documents/../Documents/x.md")
        assertEquals(Call.IN_SCOPE, SearchAdmission.call(f(homeRoot), legitInside))
        assertEquals("Documents/x.md", SearchAdmission.relativePath(f(homeRoot), legitInside))
    }

    @Test
    fun `判序_排除优先于范围`() {
        // 根外的排除目录先报「排除」，不报「越界」：两种 skipped 的语义不能混，
        // 混了之后「S3 是不是扫到根外去了」这个问题就没法从计数里读出来。
        val root = f(homeRoot)
        val plan = SearchAdmission.planDocRows(
            7L, root,
            listOf(Probe(f("$phoneRoot/Android/data/com.tencent.mm/x.dat"))),
            1000L,
        )
        assertEquals(0, plan.rows.size)
        assertEquals(mapOf("android-data" to 1), plan.skipTags)
    }

    // ── 入库计划（派工单 §2「甚至不建 doc 行」） ───────────────────────────────

    @Test
    fun `负例_越界与被排除的一行都不建_只留可对账的计数`() {
        val root = f(homeRoot)
        val probes = listOf(
            Probe(f("$homeRoot/Documents/体检报告.md"), kind = "md", size = 1024L, mtime = 5L),
            Probe(f("$homeRoot/Documents/工资条.txt"), kind = "txt", size = 256L, mtime = 6L),
            Probe(f("$phoneRoot/Android/data/com.tencent.mm/MicroMsg/x.dat")),   // 排除（且根外）
            Probe(f("$homeRoot/backup/旧笔记.md")),                               // 排除（根内）
            Probe(f("/data/data/com.other.app/files/x.md")),                      // 越界
            Probe(f(homeRoot)),                                                   // 根自己
        )
        val plan = SearchAdmission.planDocRows(42L, root, probes, 9000L)
        assertEquals("六条都看见", 6, plan.seen)
        assertEquals("四条不建行", 4, plan.skipped)
        assertEquals(
            "能进索引的只有那两篇正常笔记",
            listOf("Documents/体检报告.md", "Documents/工资条.txt"),
            plan.rows.map { it.relPath },
        )
        assertEquals(
            "skipped 必须能按原因读出来（验收要逐条对账）",
            mapOf("android-data" to 1, "backup" to 1, SearchAdmission.TAG_OUT_OF_SCOPE to 1, SearchAdmission.TAG_ROOT_SELF to 1),
            plan.skipTags,
        )
        // 建出来的行带着根号、状态与时间戳（S3 照着写库，本批不替它编内容）
        plan.rows.forEach { row ->
            assertEquals(42L, row.rootId)
            assertEquals(9000L, row.indexedAt)
            assertEquals(DocStatus.OK, row.status)
        }
        assertEquals(1024L, plan.rows.first().size)
    }

    @Test
    fun `进来了但读不了的才有状态_被排除的没有状态`() {
        // DocStatus 与 Exclusion 是两件事：状态＝「进来了但读不了/不值得读」，排除＝「进不来」。
        val root = f(homeRoot)
        val probes = listOf(
            Probe(f("$homeRoot/Documents/大文件.pdf"), kind = "pdf", size = 999L, status = DocStatus.TOO_BIG),
            Probe(f("$homeRoot/Documents/加密.docx"), kind = "docx", status = DocStatus.ENCRYPTED),
            Probe(f("$homeRoot/Documents/x.bin"), kind = "bin", status = DocStatus.UNCLAIMED),
            Probe(f("$homeRoot/.ssh/id_rsa"), status = DocStatus.TOO_BIG), // 被排除 ⇒ 它带的状态一概不进计划
        )
        val plan = SearchAdmission.planDocRows(1L, root, probes, 10L)
        assertEquals(
            listOf(DocStatus.TOO_BIG, DocStatus.ENCRYPTED, DocStatus.UNCLAIMED),
            plan.rows.map { it.status },
        )
        assertEquals(1, plan.skipped)
        assertEquals(setOf("secret"), plan.skipTags.keys)
    }

    @Test
    fun `状态字面量与DDL对得上_读不出的字面量不猜`() {
        // SqlSearchScopeStore 把库里的 status 读回枚举：CHECK 之外的字面量（手工改库／老库）必须是 null，
        // 「猜一个默认状态」等于把一条不知道来历的行当 OK 搜出来。
        listOf("OK", "TOO_BIG", "ENCRYPTED", "JUNK", "PRUNED", "UNCLAIMED").forEach { tag ->
            assertEquals(tag, DocStatus.fromTag(tag)?.tag)
        }
        assertNull(DocStatus.fromTag("ok"))
        assertNull(DocStatus.fromTag(""))
        assertNull(DocStatus.fromTag("LOADED"))
        // DDL 的 CHECK 里就得是这几个字面量（两边同源）
        val ddl = SearchSql.DDL.joinToString("\n")
        DocStatus.values().forEach { s -> assertTrue("DDL 里没有 ${s.tag}", ddl.contains("'${s.tag}'")) }
    }

    // ── 源码门禁：这张清单是写死的，不是可关的 ────────────────────────────────

    @Test
    fun `排除清单本体不读prefs_开关只能管范围不能管清单`() {
        // 派工单 §1④「用户不能取消」的形状就是这个断言：整个包里唯一碰 prefs 的文件是
        // SearchScopeSettings.kt（它只回答「用户拨没拨」），判据文件一个字都不碰。
        // 哪天 SearchAdmission／SearchScope 里冒出 getBoolean，就是给排除清单装了个关。
        val banned = listOf("getBoolean", "putBoolean", "SharedPreferences", "System.getenv", "devMode")
        searchSources().forEach { (name, text) ->
            if (name == "SearchScopeSettings.kt") return@forEach
            banned.forEach { b ->
                assertFalse("$name 里出现「$b」：排除清单是读侧的下限，能关就是一次误点的后果（派工单 §1④）", code(text).contains(b))
            }
        }
        // 清单条目也不许长成可变的集合（只有 val 的常量表，没有 add/remove/清空的口）。
        val admission = code(sourceOf("SearchAdmission.kt"))
        listOf("mutableSetOf", ".add(", ".remove").forEach { mut ->
            assertFalse("SearchAdmission 里出现「$mut」＝清单能在运行时被改窄/改宽", admission.contains(mut))
        }
    }

    @Test
    fun `垃圾名三条与LibraryPaths同源_一边改了另一边不改就红`() {
        val here = code(sourceOf("SearchAdmission.kt"))
        val there = code(repoFile("src/main/java/com/example/zhengdao/baopu/library/LibraryPaths.kt").readText())
        listOf("\".DS_Store\"", "\"Thumbs.db\"", "\"._\"").forEach { lit ->
            assertTrue("LibraryPaths 的垃圾名里少了 $lit（那边改了⇒本文件的清单要跟着改）", there.contains(lit))
            assertTrue("SearchAdmission 的垃圾名里少了 $lit（两份规则漂了）", here.contains(lit))
        }
    }

    @Test
    fun `越界判据只用写侧那一把_本包不自己算路径包含`() {
        val text = code(sourceOf("SearchAdmission.kt"))
        // 两扇门（call 与 relativePath）都得引同一把尺；名字判据里的 startsWith 不算第二把尺，
        // 所以禁的是「对路径字符串做前缀/规范比较」那几种写法。
        assertTrue("准入判据必须引 PathGuard.isInside（设计 §11.10「另造第二套路径」）", text.contains("PathGuard.isInside"))
        assertEquals("越界判定一处出口：isInside 只许被这两扇门各引一次", 2, Regex("PathGuard\\.isInside").findAll(text).count())
        listOf("canonicalFile", "getCanonicalPath", "absolutePath.startsWith", "path.startsWith", ".relativeTo(").forEach { forbidden ->
            assertFalse("SearchAdmission 里出现「$forbidden」＝另造越界判据", text.contains(forbidden))
        }
    }

    // ── helper ──────────────────────────────────────────────────────────────

    /** 从工作目录逐级向上找仓库里的相对路径（照双路径惯例，但连 app/ 当工作目录的情形也兜住）。 */
    private fun repoFile(rel: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            listOf(File(dir, "app/$rel"), File(dir, rel)).firstOrNull { it.exists() }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $rel（工作目录 ${File(".").absolutePath}）")
    }

    private fun sourceOf(name: String): String = repoFile("src/main/java/com/example/zhengdao/baopu/search/$name").readText()

    private fun searchSources(): List<Pair<String, String>> {
        val dir = repoFile("src/main/java/com/example/zhengdao/baopu/search")
        val files = dir.listFiles { fd: File -> fd.isFile && fd.extension == "kt" }?.toList() ?: emptyList()
        assertTrue("baopu/search 里没有源文件＝门禁失效", files.isNotEmpty())
        return files.map { it.name to it.readText() }
    }

    /**
     * 剥注释（行注释＋块注释，KDoc 也在里面）。
     *
     * 块注释用 DOT_MATCHES_ALL 非贪婪，且**本文件的注释里不写字面的成对星斜杠**——
     * Kotlin 的块注释能嵌套，写了就会让这里在错的地方断开。
     */
    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")
}
