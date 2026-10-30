// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十七批（派工单 §1①③④、§2）：读侧档位的**判据**与授予账本的语义。
//
// 全部 JVM 直测：档位判定、根选择、账本计划三个算式都是纯函数；账本语义走内存实现
// （`SqlSearchScopeStore` 的 SQL 文本由 `SearchSqlTest` 钉，本仓无 Robolectric ⇒ SQLite 进不了 JVM）。
//
// 派工单 §2 那五条负例里，本文件负责三条：
// - 「未授权目录搜不到（甚至不建 doc 行）」的另一半——**没授予就不落 PHONE 行**（偷全盘扫的前置）；
// - 「只读授予让写判据少弹一张卡」→ 在 `SearchReadNeverGrantsWriteTest`（那里逐字比对）；
// - 「凭空记一条没拿到的授予」→ `手机档只在真授权时入账`。
package com.example.zhengdao.baopu.search

import com.example.zhengdao.baopu.FilePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SearchScopeTest {

    private val home = "/storage/emulated/0/Download/证道"
    private val phone = "/storage/emulated/0"

    private fun kinds(drafts: List<ScopeDraft>): List<ScopeKind> = drafts.map { it.kind }

    // ── 档位（派工单 §1③：只做甲、丁） ───────────────────────────────────────

    @Test
    fun `甲丁两档_只看所有文件访问这一条`() {
        assertEquals(ReadTier.PHONE, SearchScopeRules.tierOf(allFilesAccess = true))
        assertEquals(ReadTier.PRIVATE, SearchScopeRules.tierOf(allFilesAccess = false))
        // 本批没有乙/丙档：档位集合就是这两档，多出来的一律算红（防止有人顺手加 SAF 兜底）
        assertEquals(setOf("PHONE", "PRIVATE"), ReadTier.values().map { it.name }.toSet())
    }

    @Test
    fun `授权即全盘_判定里没有开关这个输入`() {
        // 第二百二十九批（2026-10-26 用户口径「给了存储权限就默认可全盘找，开关有点多余」）：
        // 意愿开关从判定里除名——签名里连参数位都没有，「删了 UI 仍读开关」要作祟，
        // 得先把参数加回来（编译期就红）＋下面的源码门禁（运行期也红）。
        assertEquals(PhoneAccess.GRANTED, SearchScopeRules.phoneAccessOf(ReadTier.PHONE))
        assertEquals(PhoneAccess.NO_PERMISSION, SearchScopeRules.phoneAccessOf(ReadTier.PRIVATE))
        // SWITCH_OFF 那一档随开关一起删了：档位集合就两值，多出来一律算红。
        assertEquals(setOf("GRANTED", "NO_PERMISSION"), PhoneAccess.values().map { it.name }.toSet())
        // JVM 字节码钉签名：phoneAccessOf 只许一个入参（boolean 就是开关还魂的形状）。
        val sigs = Class.forName("com.example.zhengdao.baopu.search.SearchScopeRules")
            .declaredMethods
            .filter { it.name == "phoneAccessOf" && !it.name.contains("\$default") }
            .map { it.parameterTypes.map { t -> t.name } }
        assertEquals(
            "phoneAccessOf 只该有一个入参（ReadTier）——冒出 boolean 就是开关爬回了判定",
            1,
            sigs.size,
        )
        assertTrue(sigs.single().all { it != "boolean" })
    }

    // ── 账本计划（负例「偷偷全盘扫」「凭空记授予」） ──────────────────────────

    @Test
    fun `授权到位才落两行_HOME恒在前`() {
        val drafts = SearchScopeRules.plan(PhoneAccess.GRANTED, home, phone)
        assertEquals(
            listOf(ScopeDraft(home, ScopeKind.HOME), ScopeDraft(phone, ScopeKind.PHONE)),
            drafts,
        )
    }

    @Test
    fun `负例_没授权_绝不落手机档`() {
        // 229 起唯一挡手机档的就是「系统没给全文件访问」：挡住时只该有 HOME 一行——
        // 落 PHONE 行＝给 S3 的扫描器发一份假账，它会照着去扫全盘（偷全盘扫的前置）。
        val drafts = SearchScopeRules.plan(PhoneAccess.NO_PERMISSION, home, phone)
        assertEquals("没授权时只该有 HOME 一行", listOf(ScopeKind.HOME), kinds(drafts))
        assertFalse(
            "没授权落 PHONE 行＝给 S3 的扫描器发一份假账，它会照着扫全盘",
            drafts.any { it.kind == ScopeKind.PHONE },
        )
    }

    @Test
    fun `负例_本批永不产出媒体档`() {
        // 乙档（MEDIA）只在 DDL 的 CHECK 里存在；manifest 没有 READ_MEDIA_*，代码一片没写。
        PhoneAccess.values().forEach { a ->
            val drafts = SearchScopeRules.plan(a, home, phone)
            assertTrue("$a 时出现了本批不支持的档", drafts.all { it.kind in SearchScopeRules.KINDS_THIS_BATCH })
        }
        assertFalse("MEDIA 不该在本批白名单里", ScopeKind.MEDIA in SearchScopeRules.KINDS_THIS_BATCH)
    }

    @Test
    fun `根算不出来必须断_不许静默少一行`() {
        listOf("", "   ").forEach { bad ->
            try {
                SearchScopeRules.plan(PhoneAccess.GRANTED, bad, phone)
                fail("HOME 根为空串还返回计划＝把「拿不到根」当「没有根」")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("HOME"))
            }
        }
        try {
            SearchScopeRules.plan(PhoneAccess.GRANTED, home, "  ")
            fail("甲档已授予却拿不到手机根＝判据断了，得响")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("手机根"))
        }
    }

    @Test
    fun `同一个物理根不落两行`() {
        // 工作区被设成手机根本身（custom 模式的极端形态）：HOME 与 PHONE 是同一个 abs_root，
        // UNIQUE(abs_root) 会撞 ⇒ 计划阶段就该只给一行，别让 UPSERT 去猜谁赢。
        val drafts = SearchScopeRules.plan(PhoneAccess.GRANTED, phone, "$phone/")
        assertEquals(listOf(ScopeKind.HOME), kinds(drafts))
    }

    @Test
    fun `rootKey_与写侧账本同一个写法`() {
        // 设计 §11.10「另造第二套路径」的一半就长在归一上：读侧与写侧对同一个根必须给出同一个字符串。
        val f = File("/storage/emulated/0/Download/证道")
        assertEquals(FilePolicy.rootKey(f), SearchScopeRules.rootKey(f))
    }

    // ── 账本语义（内存实现；SQL 侧由 SearchSqlTest 钉文本） ────────────────────

    @Test
    fun `记账幂等_反复拨开关不涨行数`() {
        val store = InMemorySearchScopeStore()
        val plan = SearchScopeRules.plan(PhoneAccess.GRANTED, home, phone)
        assertEquals("第一次两行都算新增", 2, store.record(plan, 1000L))
        assertEquals("第二次一行都不该新增（UPSERT 幂等）", 0, store.record(plan, 2000L))
        assertEquals(2, store.all().size)
        // 重复拨「不涨行数」，但 granted_at 要跟着刷——这与 UPSERT_SCOPE 的文本是同一条语义。
        // 两边谁改成「一个字节都不动」谁就跑偏：S3 的扫描器只看 granted_at 决定要不要重扫。
        assertTrue(
            "库版 UPSERT 会刷 granted_at；内存版若不跟着刷，两本账就对不上（本条是它们同语义的锚）",
            SearchSql.UPSERT_SCOPE.contains("granted_at = excluded.granted_at"),
        )
        assertEquals("与库版同语义：重复拨刷时间戳，但行数一行的都不加", 2000L, store.all().first { it.absRoot == phone }.grantedAt)
        assertNull(store.all().first { it.absRoot == phone }.revokedAt)
    }

    @Test
    fun `撤过的根重新拨算复活`() {
        val store = InMemorySearchScopeStore()
        val plan = SearchScopeRules.plan(PhoneAccess.GRANTED, home, phone)
        store.record(plan, 1000L)
        store.revokeAbsentFrom(listOf(ScopeDraft(home, ScopeKind.HOME)), 2000L)
        val revived = store.record(plan, 3000L)
        assertEquals("PHONE 行从撤销态回到有效态，算一次变更", 1, revived)
        assertNull(store.all().first { it.absRoot == phone }.revokedAt)
    }

    @Test
    fun `负例_撤销必须把索引一起带走_不留能搜的根`() {
        val store = InMemorySearchScopeStore()
        val plan = SearchScopeRules.plan(PhoneAccess.GRANTED, home, phone)
        store.record(plan, 1000L)
        val phoneRow = store.all().first { it.absRoot == phone }
        store.seedDocs(phoneRow.rootId, listOf("Documents/体检报告.md", "Documents/工资条.txt"))
        assertTrue("造场景没造出来：没撤销过的根不该进残留清单", store.orphanDocRoots().isEmpty())

        val revoked = store.revokeAbsentFrom(listOf(ScopeDraft(home, ScopeKind.HOME)), 2000L)
        assertEquals(listOf(phone), revoked)
        assertEquals(
            "撤销只打 revoked_at 不删 doc＝用户以为范围收回去了，倒排里还留着他那批文件的词",
            emptyList<String>(),
            store.orphanDocRoots(),
        )
        // HOME 行不受牵连
        assertEquals(1, store.all().count { it.revokedAt == null })
        // 正对照（防探针恒空假绿）：拿 seedDocs 绕过撤销直接塞一行残留，探针必须响。
        // 没有这一条，上面那次「为空」可能只是探针本身坏了。
        store.seedDocs(phoneRow.rootId, listOf("Documents/工资条.txt"))
        assertEquals("撤过的根底下真有 doc 行时探针不响＝门禁是假的", listOf(phone), store.orphanDocRoots())
    }

    @Test
    fun `撤销不在计划里的_计划内的一个不动`() {
        val store = InMemorySearchScopeStore()
        store.record(listOf(ScopeDraft(home, ScopeKind.HOME)), 1000L)
        store.seed(ScopeRow(900L, "/storage/emulated/0/Documents", ScopeKind.PHONE, 900L, null))
        val revoked = store.revokeAbsentFrom(listOf(ScopeDraft(home, ScopeKind.HOME)), 2000L)
        assertEquals(listOf("/storage/emulated/0/Documents"), revoked)
        val homeRow = store.all().first { it.absRoot == home }
        assertNull("计划内的根不许被打撤销标记", homeRow.revokedAt)
        assertEquals("也不许被刷时间戳", 1000L, homeRow.grantedAt)
    }

    @Test
    fun `未知kind的行读不出来_不猜一个档`() {
        // ScopeKind.fromTag 是唯一入口：CHECK 之外的字面量（老库/手工改库）必须落到 null，
        // SqlSearchScopeStore 遇到 null 丢整行——「猜一个默认档」等于给自己发授权。
        assertNull(ScopeKind.fromTag("MEDIA2"))
        assertNull(ScopeKind.fromTag(""))
        assertNotNull(ScopeKind.fromTag("HOME"))
        assertEquals(ScopeKind.PHONE, ScopeKind.fromTag("PHONE"))
    }

    // ── 写口与开关（源码门禁；房型照 168 批 ProdEvalTest） ────────────────────

    @Test
    fun `负例_写口一个都没有_prefs布尔不许再写`() {
        // 第二百二十九批：设置页开关连写口一起删了——`putBoolean` 在读侧包里**零出现**。
        // （197/198 时代是「只许 SearchScopeSettings 一处」；229 之后是「哪里都不许」。）
        val writers = searchSources().filter { (_, text) ->
            code(text).contains("putBoolean")
        }.map { it.first }
        assertEquals(
            "读侧包里冒出 putBoolean：账本能自己给自己发授权（写口已随 229 删除，一处都不许有）",
            emptyList<String>(),
            writers,
        )
    }

    @Test
    fun `负例_删了UI仍读开关_判定路径不许碰旧prefs`() {
        // 派工单 §3 负例：「删了 UI 仍读开关（false 就只搜工作区）」。
        // 判定路径（SearchScope.kt 与除 SearchScopeSettings 外的全部读侧文件）不许出现
        // 旧键名与旧设置口——老 prefs 只兼容读、不再产生效果，这件事钉在源码上。
        searchSources().forEach { (name, text) ->
            val c = code(text)
            if (name != "SearchScopeSettings.kt") {
                assertFalse(
                    "$name 出现旧键名 search_phone_on：开关已删（229），判定不许再读它",
                    c.contains("search_phone_on"),
                )
                assertFalse(
                    "$name 引用 SearchScopeSettings：兼容读口不该有任何调用方（删了 UI 仍读开关就是这个样子）",
                    c.contains("SearchScopeSettings"),
                )
            }
        }
        // 兼容读口自身还在（老 prefs 的历史值要有一个明确的读法），但**必须没有写函数**。
        val settings = code(searchSources().first { it.first == "SearchScopeSettings.kt" }.second)
        assertTrue("兼容读口要保留（KEY_PHONE_ON 常量）", settings.contains("const val KEY_PHONE_ON"))
        assertTrue("兼容读口的缺省值还是 false（老值不产生效果，这个缺省只是读法的形状）", settings.contains("getBoolean(KEY_PHONE_ON, false)"))
        assertFalse("写函数 set 必须已删除", settings.contains("fun set("))
    }

    @Test
    fun `旁路一个都没有_devMode_adb_环境变量都不许读`() {
        val banned = listOf("devMode", "BuildConfig.DEBUG", "System.getenv", "getprop", "adb ")
        searchSources().forEach { (name, text) ->
            banned.forEach { b ->
                assertFalse("$name 出现旁路读法「$b」——只有用户在设置里拨过才算数（派工单 §2 对 168 批那条口径）", code(text).contains(b))
            }
        }
    }

    // ── helper ──────────────────────────────────────────────────────────────

    /** `baopu/search/` 下全部主源文件（名字 → 文本）。找不到目录就红，不许假绿。 */
    private fun searchSources(): List<Pair<String, String>> {
        val rels = listOf(
            "app/src/main/java/com/example/zhengdao/baopu/search",
            "../app/src/main/java/com/example/zhengdao/baopu/search",
        )
        val dir = rels.map(::File).firstOrNull { it.isDirectory }
            ?: throw AssertionError("找不到 baopu/search 目录（工作目录 ${File(".").absolutePath}）")
        val files = dir.listFiles { f: File -> f.isFile && f.extension == "kt" }?.toList() ?: emptyList()
        assertTrue("baopu/search 里没有源文件＝门禁失效", files.isNotEmpty())
        return files.map { it.name to it.readText() }
    }

    /**
     * 剥注释（行注释＋块注释，KDoc 也在里面）。本批源码到处在讲「不碰写侧那套判据」，
     * 那些词全在注释里，不剥就是自己判红。
     *
     * 块注释用 DOT_MATCHES_ALL 非贪婪：Kotlin 的块注释**能嵌套**，所以剥的人自己也不许在
     * 源码里写字面的成对星斜杠——这个正则就靠这一条正则匹配，写出来就断在错的地方。
     */
    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("(?m)^[ \\t]*//.*$"), "")
}
