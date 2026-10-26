// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十七批（派工单 §1①③）：**读侧**授予账本——「搜索能看见哪些根」。
//
// ## 这条线与写侧的关系（本批的全部要害在这一段）
// 派工单 §3.2 立的线：
//     读侧：SearchScope ——「你能看到什么」—— 本文件，独立授予
//     写侧：PathGuard / workspaceBoundaryCheck ——「你能改什么」—— 现成，一行不动
// 读侧授予**永远不进入**写侧判据的输入：`WorkspaceAccessPolicy.granted` 装的是
// `WorkspaceGrants::granted`（`BaopuRuntime.kt:661-662`），本文件既不读它、也不写它、
// 更不 import 它。这条不是注释，是门禁——`SearchReadNeverGrantsWriteTest` 逐字比过
// 「装了只读授予」与「什么都没装」两种状态下 `workspaceBoundaryCheck` 的返回值。
//
// ## 只做甲、丁两档（派工单 §1③）
// - **甲 全文件访问**：`Environment.isExternalStorageManager()` ⇒ 可搜 `/storage/emulated/0` 全量。
//   这里**只认这一条**，不套 `terminal/ProotLauncher.kt:56-60` 那个 `|| WRITE_EXTERNAL_STORAGE`：
//   那条注释自己写着「运行时权限在 API 30+ 已 inert（系统对请求直接静默拒绝）」，
//   在 targetSdk 36 上两个析取支等价；但「拿到 WRITE 权限」≠「能读全盘」，
//   把它当甲档判据入账，是一句将来会变成假话的判据（设计 §4 说的是「复用现成判定」的
//   **位置**，不是把那把 OR 搬来当读侧口径——两处真需要同一判据时应当抽公共件，那是 S3 的事）。
// - **丁 未授权回落**：搜证道根与 App 私有工作区，行为与今天一致（现成回落，零新代码路径）。
// - 乙（媒体档）/丙（SAF 树）不写：manifest 里没有 `READ_MEDIA_*`，且真机 MagicOS 上 SAF
//   禁选根目录（`ui/SettingsCommon.kt:401-407` 原话）——铺一条跑不通的兜底不如把范围讲清楚。
//
// ## 三件东西分得很清（别混）
// 1. **系统授权态**（`isExternalStorageManager`）：能力，App 说了不算；
// 2. ~~用户开关~~（[SearchScopeSettings]，prefs `search_phone_on`）：**第二百二十九批起已停用**——
//    用户 2026-10-26 口径「给了存储权限就默认可全盘找，开关有点多余」，判定不再读它；
//    该键只剩**兼容读取**（老 prefs 里拨过的值不再产生任何效果），也没有写入口了；
// 3. **账本**（`scope` 表）：授权成立才落一行 PHONE。
// 只有授权没有 ⇒ 不读（能力是前提，没有能力谈不了范围）；有授权 ⇒ 直接 PHONE（意愿默认 expressed）。
// 落点在纯函数 [SearchScopeRules.plan]，JVM 直测。
package com.example.zhengdao.baopu.search

import android.content.Context
import android.os.Environment
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.WorkspaceGrantRules
import com.example.zhengdao.rootfs.RunLog
import java.io.File
import java.util.Collections

/** 读侧档位（派工单 §1③：本批只有甲、丁两档）。 */
internal enum class ReadTier {
    /** 甲：全文件访问已授予 ⇒ 手机全盘可进搜索范围（除 [SearchAdmission] 写死的排除清单）。 */
    PHONE,

    /** 丁：未授予 ⇒ 回落，只搜证道根／App 私有工作区（今天的现成行为）。 */
    PRIVATE,
}

/** `scope.kind` 的三个字面量（照设计 §6.2）。 */
internal enum class ScopeKind(val tag: String) {
    /** 证道根／当前工作区——**恒在搜索范围内**，不需要授予（东西本来就是 App 自己的）。 */
    HOME("HOME"),

    /** 手机全盘——要甲档授权 **且** 用户开关开。 */
    PHONE("PHONE"),

    /**
     * 媒体档（乙）。本批**只存在于 DDL 的 CHECK 里**：manifest 没有 `READ_MEDIA_*`，
     * 一片新代码都没写，所以 [SearchScopeRules.plan] 永不产出这个 kind
     * （`SearchScopeTest.负例_本批永不产出媒体档` 钉住）。留位不为写代码，为的是
     * 「形状照 §6.2」这句话可以被字面验收。
     */
    MEDIA("MEDIA"),
    ;

    companion object {
        /** 未知字面量 ⇒ null（不编一个默认档，读不懂的账本宁可当成没有）。 */
        fun fromTag(tag: String): ScopeKind? = values().firstOrNull { it.tag == tag }
    }
}

/** 手机档这一行到底建不建，以及不建是哪一条挡的（原因给文案用，不落库）。 */
internal enum class PhoneAccess {
    /** 甲档 ⇒ 全盘进范围（第二百二十九批起：不再问开关——授权即全盘）。 */
    GRANTED,

    /** 甲档没有，系统没给全文件访问 ⇒ 不落账，界面写「当前只搜证道＋工作区」。 */
    NO_PERMISSION,
}

/** 计划中的一条授予（还没有 `root_id`——id 是库里的代理键，由 [SearchScopeStore.record] 落定）。 */
internal data class ScopeDraft(val absRoot: String, val kind: ScopeKind)

/** `scope` 表的一行。`revokedAt == null` 才是**有效**授予。 */
internal data class ScopeRow(
    val rootId: Long,
    val absRoot: String,
    val kind: ScopeKind,
    val grantedAt: Long,
    val revokedAt: Long?,
)

/** 一次同步的读数（设置页把它写进 RunLog，验收时对着数字看「这次到底动了几行」）。 */
internal data class SyncReport(
    val planned: List<ScopeDraft>,
    val recorded: Int,
    val revoked: List<String>,
    /** 撤掉的根里，有没有还留着 doc 行的（非空＝隐私事故，界面与日志都要响）。 */
    val orphanRoots: List<String>,
) {
    val isNoop: Boolean get() = recorded == 0 && revoked.isEmpty()
}

/**
 * 读侧判据（**纯函数，无 Android、无 IO**）：档位、根选择、账本计划。
 *
 * 路径归一故意复用 `WorkspaceGrantRules.rootKey`（写侧账本同一个函数）：读侧与写侧对
 * 「同一个物理根」必须写出同一个字符串，否则「`/sdcard` 与 `/storage/emulated/0` 是不是同一个根」
 * 会出现两份答案——设计 §11.10 那条「另造第二套路径」的负例，一半就长在这种地方。
 * 复用的是**归一**，不是判据：越界判定另走 [SearchAdmission] 里的 `PathGuard.isInside`。
 */
internal object SearchScopeRules {

    /** 甲／丁的唯一判据：只看「所有文件访问」。 */
    fun tierOf(allFilesAccess: Boolean): ReadTier = if (allFilesAccess) ReadTier.PHONE else ReadTier.PRIVATE

    /**
     * 能力 ⇒ 手机档这一行建不建（第二百二十九批：判定里**没有**「用户开关」这个输入了——
     * 给了存储授权就全盘，老 prefs 里的 `search_phone_on` 不再产生任何效果；负例
     * 「删了 UI 仍读开关」由 `SearchScopeTest` 的源码门禁与这里的签名一起钉死：
     * 想把开关读回来，就得把参数加回签名，门禁当场红）。
     */
    fun phoneAccessOf(tier: ReadTier): PhoneAccess = when (tier) {
        ReadTier.PHONE -> PhoneAccess.GRANTED
        ReadTier.PRIVATE -> PhoneAccess.NO_PERMISSION
    }

    /**
     * 账本计划：HOME 恒有一条（默认范围＝证道＋当前工作区，设计 §11.4），
     * PHONE 只在 [PhoneAccess.GRANTED] 时有——**顺序固定**（HOME 在前），单测与界面都要确定序。
     */
    fun plan(access: PhoneAccess, homeRoot: String, phoneRoot: String): List<ScopeDraft> {
        val home = homeRoot.trim()
        require(home.isNotEmpty()) { "HOME 根不能是空串（空串会被 UNIQUE 当成一个合法根）" }
        val drafts = mutableListOf(ScopeDraft(home, ScopeKind.HOME))
        if (access == PhoneAccess.GRANTED) {
            val phone = phoneRoot.trim().trimEnd('/')
            require(phone.isNotEmpty()) { "手机根不能是空串（甲档已授予却拿不到根路径＝判据断了，不许静默不落账）" }
            if (phone != home) drafts += ScopeDraft(phone, ScopeKind.PHONE)
        }
        return drafts
    }

    /**
     * 本批**允许入账**的档：HOME 与 PHONE。[ScopeKind.MEDIA] 在里面是「形状有、代码没」的那一个。
     * 门禁拿它当白名单：账本计划里出现白名单外的 kind ⇒ 红。
     */
    val KINDS_THIS_BATCH: Set<ScopeKind> = setOf(ScopeKind.HOME, ScopeKind.PHONE)

    /** 根路径的写法（与写侧账本同源，见类注释）。 */
    fun rootKey(root: File): String = WorkspaceGrantRules.rootKey(root)

    /**
     * 账本同步那行日志的**唯一**写法（第一百九十八批派工单 §1⑥「search.db 首次建库／拨开关那条
     * 日志要能被真机看懂」；第二百二十九批起开关没了，这句话跟着改判成「账本同步」的读数——
     * 生产调用点只剩搜索卡扫描前的 `syncLedgerLine` 一处）。
     *
     * 为什么是纯函数而不是 UI 里的一句字符串插值：这一行是真机验收时她唯一能对着看的读数
     * （「账本新增 N 行／撤销 M 个根」那句），它长在哪儿、说了什么必须能在 JVM 里被断言——
     * 散在 UI 里就是一条谁都能顺手改半句的文案。
     *
     * 三件必须说清的事，缺一件就是这条日志没写完：
     * 1. **首次建库**（[dbFirstBuild]）：第一次同步时 `search.db` 这个文件是这一刻才被建出来的，
     *    不说这一句，「新增 1 行」读起来就像「库早就在了、只是多了一条」；
     * 2. **撤销的根里还留着索引行**（[orphanRoots] > 0）：那是隐私事故，界面与日志都得响
     *    （[SearchScopeStore.orphanDocRoots] 那条残留探针就是为它存在的）；
     * 3. **能力与范围的差别**：系统没给「所有文件访问」⇒ 一个根都没入账，
     *      那句「新增 0 行」必须跟着「因为没拿到系统授权」，不许读成「坏了」。
     *
     * 一个查询词都不会出现在这行里（本函数不接任何文本输入，只接计数与档位）。
     * 读写边界也要说清：读是全盘（授权后），写**没有**跟着放大——只限当前工作区与 Download/证道。
     */
    fun syncLine(
        access: PhoneAccess,
        recorded: Int,
        revokedRoots: Int,
        orphanRoots: Int,
        dbFirstBuild: Boolean,
    ): String = buildString {
        append("搜索范围：手机全盘 = ")
        append(if (access == PhoneAccess.GRANTED) "已纳入" else "未纳入")
        append("（只读；账本新增 ").append(recorded).append(" 行、撤销 ").append(revokedRoots).append(" 个根")
        if (dbFirstBuild) append("；这是首次建库：search.db 这个文件是这一刻才被建出来的")
        if (access == PhoneAccess.NO_PERMISSION) {
            append("；系统还没给「所有文件访问」，所以手机档一行都没入账")
        }
        if (orphanRoots > 0) {
            append("；⚠ 撤销的根里还有 ").append(orphanRoots).append(" 个留着索引行（要立刻重建索引）")
        }
        append("；写文件不跟着放大：只限当前工作区与 Download/证道）")
    }
}

/**
 * 账本存取口（一个接口两实现，房型照 [com.example.zhengdao.baopu.WorkspaceGrantStore]）：
 * 生产走 [SqlSearchScopeStore]（`search.db`，跨进程存活），纯 JVM 直测走
 * [InMemorySearchScopeStore]——两边语义必须一致，所以「撤销后不留 doc」这类判据在内存版上
 * 先钉住，SQL 文本再钉一遍（`SearchSqlTest`）。
 */
internal interface SearchScopeStore {

    /** 现在账上的全部根（含已撤销的，界面只展示 [ScopeRow.revokedAt] 为 null 的那些）。 */
    fun all(): List<ScopeRow>

    /** 把计划拨成账本：该有的一行（幂等，重复拨只更新时间戳），返回真正新增或复活的行数。 */
    fun record(drafts: List<ScopeDraft>, now: Long): Int

    /** 把「账上有效、但计划里没有」的根撤掉，返回撤掉的根的绝对路径。 */
    fun revokeAbsentFrom(drafts: List<ScopeDraft>, now: Long): List<String>

    /** 已撤销的根里还有哪些留着 doc 行（撤销只打标不删索引 ⇒ 这里必须为空）。 */
    fun orphanDocRoots(): List<String>
}

/** 未接线环境（纯 JVM 直测）用的内存账本——语义与库版一致，只是活不过进程。 */
internal class InMemorySearchScopeStore : SearchScopeStore {

    private data class Key(val absRoot: String)

    private val rows: MutableMap<Key, ScopeRow> = Collections.synchronizedMap(linkedMapOf<Key, ScopeRow>())
    private var nextId: Long = 1L

    /** 某个根下「还留着哪些 doc 行」——生产的 `doc` 表由 S3 的扫描器写，这里只给单测摆场景。 */
    private val docs: MutableMap<Long, MutableList<String>> = Collections.synchronizedMap(mutableMapOf())

    /** 测试塞数口：手工摆一行（含 doc 残留场景要的「已撤销的根」）。 */
    fun seed(row: ScopeRow) {
        rows[Key(row.absRoot)] = row
        if (row.rootId >= nextId) nextId = row.rootId + 1
    }

    override fun all(): List<ScopeRow> = synchronized(rows) { rows.values.toList() }

    override fun record(drafts: List<ScopeDraft>, now: Long): Int {
        var touched = 0
        synchronized(rows) {
            drafts.forEach { d ->
                val key = Key(d.absRoot)
                val old = rows[key]
                // 写与 [SearchSql.UPSERT_SCOPE] 逐字同语义：一行的值每次都刷（granted_at 跟着 now、
                // revoked_at 归 NULL），但「真变更」只在凭空多一行／复活／换档时计数。
                // 这里若改成「重复拨一个字节都不动」，内存版就和库版分叉，S3 照着内存版测会假绿。
                if (old == null) {
                    rows[key] = ScopeRow(nextId++, d.absRoot, d.kind, now, null)
                } else {
                    rows[key] = old.copy(kind = d.kind, grantedAt = now, revokedAt = null)
                }
                if (old == null || old.revokedAt != null || old.kind != d.kind) touched++
            }
        }
        return touched
    }

    override fun revokeAbsentFrom(drafts: List<ScopeDraft>, now: Long): List<String> {
        val keep = drafts.map { it.absRoot }.toSet()
        val victims = synchronized(rows) {
            rows.values.filter { it.revokedAt == null && it.absRoot !in keep }
        }
        victims.forEach { v ->
            // 撤销 = 打标 + 删索引。只打标不删 doc 是这条账本最坏的失效形态：
            // 用户以为范围收回去了，倒排里还留着他那批文件的词。
            docs.remove(v.rootId)
            synchronized(rows) { rows[Key(v.absRoot)] = v.copy(revokedAt = now) }
        }
        return victims.map { it.absRoot }
    }

    fun seedDocs(rootId: Long, relPaths: List<String>) {
        docs[rootId] = mutableListOf(*relPaths.toTypedArray())
    }

    override fun orphanDocRoots(): List<String> = synchronized(rows) {
        rows.values.filter { it.revokedAt != null && docs[it.rootId]?.isNotEmpty() == true }.map { it.absRoot }
    }
}

/**
 * 生产账本：SQLite 落库（`search.db` 的 `scope` 表）。
 *
 * 每个方法都吞成「没写成／没有行」：库读不出时，搜索范围**只会变小不会变大**
 * （fail closed）——读侧最坏的事是静默多读，不是少读。
 */
internal class SqlSearchScopeStore(private val db: SearchDb) : SearchScopeStore {

    override fun all(): List<ScopeRow> = runCatching {
        db.readable().rawQuery(SearchSql.SELECT_ALL_ROOTS, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val kind = ScopeKind.fromTag(c.getString(2) ?: "")
                    // 读不懂的 kind 直接丢这一行：账面上出现 CHECK 之外的字面量，
                    // 说明库是老结构或被手工改过——猜一个档等于给自己发授权。
                    if (kind != null) {
                        add(
                            ScopeRow(
                                rootId = c.getLong(0),
                                absRoot = c.getString(1) ?: "",
                                kind = kind,
                                grantedAt = c.getLong(3),
                                revokedAt = if (c.isNull(4)) null else c.getLong(4),
                            ),
                        )
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    override fun record(drafts: List<ScopeDraft>, now: Long): Int {
        if (drafts.isEmpty()) return 0
        // 一次快照算「先前状态」（UPSERT 本身幂等，不需要为每条草稿再查一遍库）。
        val before = all().associateBy { it.absRoot }
        var touched = 0
        drafts.forEach { d ->
            if (db.exec(SearchSql.UPSERT_SCOPE, arrayOf<Any?>(d.absRoot, d.kind.tag, now))) {
                val old = before[d.absRoot]
                if (old == null || old.revokedAt != null || old.kind != d.kind) touched++
            } else {
                RunLog.log("读侧记账失败：${d.kind.tag} ${d.absRoot}（本次按没记处理）")
            }
        }
        return touched
    }

    override fun revokeAbsentFrom(drafts: List<ScopeDraft>, now: Long): List<String> {
        val keep = drafts.map { it.absRoot }.toSet()
        val victims = all().filter { it.revokedAt == null && it.absRoot !in keep }
        val done = mutableListOf<String>()
        victims.forEach { v ->
            // 先删 doc（tok 顺级联走）再打撤销标记：反过来的话，中间那一刻「已撤销却仍可搜」。
            val docsGone = db.exec(SearchSql.DELETE_DOCS_OF_ROOT, arrayOf<Any?>(v.rootId))
            val marked = db.exec(SearchSql.MARK_SCOPE_REVOKED, arrayOf<Any?>(now, v.rootId))
            if (docsGone && marked) {
                db.exec(SearchSql.DELETE_WATERMARK_OF_ROOT, arrayOf<Any?>(v.rootId))
                done += v.absRoot
            } else {
                RunLog.log("读侧撤销没做干净：${v.absRoot}（doc 删=$docsGone 打标=$marked，界面按未撤处理）")
            }
        }
        return done
    }

    override fun orphanDocRoots(): List<String> = runCatching {
        db.readable().rawQuery(SearchSql.SELECT_ORPHAN_DOCS, null).use { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(0) ?: "")
            }.distinct()
        }
    }.getOrDefault(emptyList())
}

/**
 * 读侧账本的薄壳（Context 到这一层为止）。
 *
 * [status] 读数、[sync] 把账本拨到与「现在的开关＋现在的授权」一致。
 *
 * ## 谁能改这张账本（第二百二十九批更新：拨开关那个入口随开关一起删了）
 * [sync] 的调用点**只有一个，是用户手动按下去的**：
 * 1. 搜索卡的「扫描本机／重建索引」（[PhoneSearch.syncNow] ← [com.example.zhengdao.ui.baopu.BaopuPhoneSearchCard]）
 *    ——用户主动要结果的那一刻（197 那个「设置页拨开关」入口随开关一起删除：授权即全盘，
 *    没有意愿可改，账本只在扫描/重建前对齐现状）。
 *
 * **没有**第二处，也**故意不加**「App 启动时自动同步」：这张账本是读侧唯一的授权真相，
 * 自动同步等于让「谁在什么时候能改它」变成运行时的随手一刻。要加自动同步，得先说清那句话，
 * 再改钉这件事的门禁（`PhoneSearchEntryGateTest` 扫源码比调用点集合，多一个 ⇒ 红）。
 *
 * 没有 adb／devMode／环境变量旁路（派工单 §2 对 168 批那条口径在这里同样成立）。
 */
internal object SearchScope {

    /** 手机全盘的根（`Environment.getExternalStorageDirectory()`，不在内核里写死字符串）。 */
    fun phoneRoot(): String = runCatching {
        Environment.getExternalStorageDirectory().absolutePath
    }.getOrDefault("")

    /**
     * 证道根／当前工作区（复用 [BaopuWorkspace.hostDir]，不落第二套路径口径）。
     *
     * 一句实话：`hostDir` 自己会 `mkdirs()`（:60）——它是仓库里「当前工作区」的唯一真源，
     * 绕开它另算一条路径才是设计 §11.10 点名的「另造第二套路径」。读侧沿用它，
     * 不等于读侧能写：能写与否只由 `workspaceBoundaryCheck` 说了算（见本文件顶部那条线）。
     */
    fun homeRoot(ctx: Context): String = runCatching {
        SearchScopeRules.rootKey(BaopuWorkspace.hostDir(ctx))
    }.getOrDefault("")

    /** 现读两件东西（能力／根），给界面渲染用；**不碰库、不抛异常、不读任何开关**。 */
    fun status(ctx: Context): SearchScopeStatus {
        val tier = SearchScopeRules.tierOf(
            allFilesAccess = runCatching { Environment.isExternalStorageManager() }.getOrDefault(false),
        )
        return SearchScopeStatus(
            access = SearchScopeRules.phoneAccessOf(tier),
            homeRoot = homeRoot(ctx),
            phoneRoot = phoneRoot(),
        )
    }

    /**
     * 把账本拨到与现状一致（幂等：开着页面反复拨，行数只跟着状态走）。
     *
     * 库打不开、或根路径算不出来（[SearchScopeRules.plan] 对空串直接 `require` 断）
     * ⇒ 返回空报告并记一行日志：**不写也不撤**。账本读不到时 S3 的扫描器看到
     * 「没有 PHONE 行」⇒ 不扫全盘，这是本批唯一可接受的失效方向。
     */
    fun sync(ctx: Context): SyncReport = runCatching {
        val st = status(ctx)
        val plan = SearchScopeRules.plan(st.access, st.homeRoot, st.phoneRoot)
        val db = SearchDb(ctx)
        try {
            val store: SearchScopeStore = SqlSearchScopeStore(db)
            val now = System.currentTimeMillis()
            val recorded = store.record(plan, now)
            val revoked = store.revokeAbsentFrom(plan, now)
            SyncReport(plan, recorded, revoked, store.orphanDocRoots())
        } finally {
            runCatching { db.close() }
        }
    }.getOrElse { e ->
        RunLog.log("读侧账本没同步成（$e）：搜索范围按最小口径处理")
        SyncReport(emptyList(), 0, emptyList(), emptyList())
    }
}

/** 设置页/搜索卡要渲染的那一坨读数（结构在内核、文案在 strings.xml）。 */
internal data class SearchScopeStatus(
    val access: PhoneAccess,
    val homeRoot: String,
    val phoneRoot: String,
)
