// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第八十九批 §3「源码门禁：不得出现自动删除用户记忆的调用；合并保留来源」
// 与「负例：把衰减改成删除 ⇒ 用例红；把冲突自动选边 ⇒ 门禁红」。
package com.example.zhengdao.baopu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 记忆质量环的**源码门禁**（第八十九批）。
 *
 * 为什么按源码钉而不是只按行为钉：「不自动删除用户记忆」这类红线，行为用例能证明
 * 「现在没删」，证明不了「谁也删不了」。源码门禁把红线写成**文本形态的不变量**，
 * 谁以后在质量层里加一句 `.delete()`，这里立刻红——和第七十一批②那条写日志门禁同一个思路。
 *
 * 每条判据都同时跑一份**变异负例**（把源码改坏再喂给同一个判据函数）：
 * 判据本身要能被绕过，它就是假的。
 */
class MemoryQualityGateTest {

    private companion object {
        const val QUALITY = "src/main/java/com/example/zhengdao/baopu/MemoryQuality.kt"
        const val STORE = "src/main/java/com/example/zhengdao/baopu/MemoryStore.kt"
        const val DB = "src/main/java/com/example/zhengdao/baopu/BaopuDb.kt"
        const val ENGINE = "src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt"

        /** 读路径（这些方法一个字节都不许写盘：质量是算出来的，不是改出来的）。 */
        val READ_PATHS = listOf(
            "fun loadAll(): List<MemoryEntry>",
            "fun reindex(): ReindexResult",
            "fun recall(query: String, budgetTokens: Int = MemoryRules.RECALL_TOKEN_BUDGET): RecallResult",
            "fun qualityPass(now: Long): MemoryQuality.Pass",
            "fun ranked(now: Long = System.currentTimeMillis()): List<MemoryEntry>",
        )
    }

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    /** 读源码并剥掉注释（注释里写「不许删」不算数，只看代码）。 */
    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 块体函数：从签名到第一个 4 空格收尾（与 MemoryWriteLogGateTest 同房型）。 */
    private fun funBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("找不到函数签名：$signature（被改名了？门禁跟着改）", start >= 0)
        val end = src.indexOf("\n    }", start)
        assertTrue("找不到函数收尾：$signature", end > start)
        return src.substring(start, end)
    }

    /** 表达式体函数也能切：从签名到下一个「四空格开头的成员/文档」为止。 */
    private fun sliceFun(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("找不到函数签名：$signature", start >= 0)
        val marks = listOf("\n    fun ", "\n    private fun ", "\n    internal fun ", "\n    override fun ",
            "\n    /**", "\n    companion", "\n    data class", "\n    val ", "\n    var ", "\n    const ", "\n    }")
        val end = marks.map { src.indexOf(it, start + signature.length) }.filter { it > 0 }.minOrNull()
            ?: src.length
        return src.substring(start, end)
    }

    // ── 判据函数（变异负例喂同一份） ────────────────────────────────────────

    /** 红线 A：质量层没有任何删除动作，也没有文件 IO（纯判据 ⇒ 删不了也写不了）。 */
    private fun qualityIsSideEffectFree(src: String): Boolean {
        val low = src.lowercase()
        return !low.contains("delete") && !low.contains("import ") &&
            !low.contains("android.") && !low.contains("java.io") && !low.contains("mkdirs") &&
            !low.contains("writetext") && !low.contains("sqlite")
    }

    /** 红线 B：合并必须保留全部来源 id（§3「合并保留来源」）。 */
    private fun mergeKeepsEverySource(body: String): Boolean =
        body.contains("mergedFrom = group.map { it.id }") && !body.contains("mergedFrom = emptyList()")

    /** 红线 C：冲突不许选边——每个簇都要出视图，且都要带 CONFLICT。 */
    private fun conflictKeepsBothSides(body: String): Boolean =
        body.contains("for (c in clusters)") && body.contains("FLAG_CONFLICT") &&
            !body.contains("take(1)") && !body.contains("drop(1)") && !body.contains("clusters.first()")

    /** 红线 D：衰减有地板（0 分＝召不到＝不删之删）。 */
    private fun decayHasFloor(src: String): Boolean =
        src.contains("const val MIN_SCORE = 1") && src.contains("coerceIn(MIN_SCORE, MAX_SCORE)")

    /** 红线 E：读路径零写入。 */
    private fun readPathsWriteNothing(store: String): Boolean = READ_PATHS.all { sig ->
        val body = sliceFun(store, sig)
        !body.contains("writeText") && !body.contains("mkdirs") && !body.contains(".delete(") &&
            !body.contains("execSQL")
    }

    /** 红线 F：v14 迁移必须同时挂在新装与升级两条路径上，且看列再动、老数据默认满分。 */
    private fun v14MigrationWired(db: String): Boolean {
        val create = funBody(db, "override fun onCreate(db: SQLiteDatabase)")
        val upgrade = funBody(db, "override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int)")
        val alter = sliceFun(db, "fun memoryQualityColumnsStmt(existingColumns: List<String>): List<Stmt>")
        return create.contains("applyV14Migration(db)") &&
            upgrade.contains("if (oldVersion < 14) applyV14Migration(db)") &&
            alter.contains("filterNot") && alter.contains("ALTER TABLE memory ADD COLUMN") &&
            db.contains("INTEGER NOT NULL DEFAULT \" + MEMORY_QUALITY_DEFAULT_SCORE") &&
            db.contains("const val MEMORY_QUALITY_DEFAULT_SCORE = 100") &&
            db.contains("const val VERSION = 15") // 第一百一十七批升到 v15；v14 迁移体仍在
    }

    /** 红线 G：删除只有人那一把口（[MemoryStore.delete]），质量层与写路径都不碰它。 */
    private fun humanDeleteIsTheOnlyDelete(store: String): Boolean {
        val deleteBody = funBody(store, "fun delete(id: String): Boolean")
        val writeBody = funBody(store, "fun write(entry: MemoryEntry): WriteResult")
        return deleteBody.contains(".delete()") &&
            !writeBody.contains(".delete(") &&
            !sliceFun(store, "fun qualityPass(now: Long): MemoryQuality.Pass").contains(".delete(") &&
            store.lines().count { it.contains(".delete()") } == 1
    }

    /** 红线 I：扫描的「原样清单」一条都不许滤掉——滤掉＝不删之删（检索里直接消失）。 */
    private fun scanKeepsEveryEntry(body: String): Boolean =
        body.contains("val scored = entries.map { marked(it, now) }") &&
            !body.contains("marked(it, now) }.filter") && !body.contains("scored.filter")

    /** 红线 H：本批不许改抱朴的注入行为（引擎那一句原样在，且不伸进质量层）。 */
    private fun injectionCallSiteUnchanged(engine: String): Boolean =
        engine.contains("MemoryRules.trimToBudget(it.loadAll(), ContextAssembler.RECALL_TOKEN_BUDGET)") &&
            !engine.contains("MemoryQuality.")

    // ── 用例 ────────────────────────────────────────────────────────────────

    @Test
    fun `门禁_质量层是纯判据没有删除也没有IO`() {
        val src = code(QUALITY)
        assertTrue("质量层里出现删除/IO/依赖就是踩红线：\n${src.lines().filter { it.contains("delete") || it.contains("File(") }}",
            qualityIsSideEffectFree(src))
        // 负例（派工单 §3 点名：把衰减改成删除 ⇒ 用例红）：给判据加一句自动清理
        assertFalse("加了删除动作后门禁必须红",
            qualityIsSideEffectFree(src + "\n    private fun purgeOld() { File(\"x\").delete() }\n"))
        assertFalse("引了第三方/IO 依赖后门禁必须红",
            qualityIsSideEffectFree("import java.io.File\n" + src))
    }

    @Test
    fun `门禁_合并保留全部来源id`() {
        val body = sliceFun(code(QUALITY), "fun merged(group: List<MemoryEntry>, now: Long): MemoryEntry")
        assertTrue("合并把来源 id 丢了就是「吃原文」：\n$body", mergeKeepsEverySource(body))
        assertFalse("负例：合并只留主体 id ⇒ 门禁必须红",
            mergeKeepsEverySource(body.replace("group.map { it.id }", "listOf(p.id)")))
    }

    @Test
    fun `门禁_冲突不许自动选边`() {
        val body = funBody(code(QUALITY), "fun pass(entries: List<MemoryEntry>, now: Long): Pass")
        assertTrue("冲突分支只许「两组都出视图」：\n$body", conflictKeepsBothSides(body))
        // 负例（派工单 §3 点名：把冲突自动选边 ⇒ 门禁红）——只留第一组
        assertFalse("负例：冲突只留一条（自动选边）⇒ 门禁必须红",
            conflictKeepsBothSides(body.replace("for (c in clusters)", "for (c in clusters.take(1))")))
        assertFalse("负例：丢掉除第一条外的所有簇 ⇒ 门禁必须红",
            conflictKeepsBothSides(body.replace("for (c in clusters)", "for (c in clusters.drop(1))")))
    }

    @Test
    fun `门禁_衰减只降权不降为零`() {
        val src = code(QUALITY)
        assertTrue("分数地板必须是 1：$src", decayHasFloor(src))
        assertFalse("负例：地板改 0（＝不删之删）⇒ 门禁必须红",
            decayHasFloor(src.replace("const val MIN_SCORE = 1", "const val MIN_SCORE = 0")))
        assertFalse("负例：去掉地板（过期能掉到 0 以下）⇒ 门禁必须红",
            decayHasFloor(src.replace("coerceIn(MIN_SCORE, MAX_SCORE)", "coerceAtMost(MAX_SCORE)")))
    }

    @Test
    fun `门禁_读路径一个字节都不写`() {
        val store = code(STORE)
        assertTrue("loadAll/reindex/recall/qualityPass/ranked 只许读：\n" +
            READ_PATHS.joinToString { sliceFun(store, it) }, readPathsWriteNothing(store))
        // 负例：读时顺手把算出来的分数写回文件 ⇒ 等于改写用户记忆
        val mutated = store.replace(
            "fun ranked(now: Long = System.currentTimeMillis()): List<MemoryEntry> = qualityPass(now).merged",
            "fun ranked(now: Long = System.currentTimeMillis()): List<MemoryEntry> = " +
                "qualityPass(now).merged.onEach { File(\"x\").writeText(EntryCodec.render(it)) }",
        )
        assertFalse("负例：读路径写盘 ⇒ 门禁必须红", readPathsWriteNothing(mutated))
    }

    @Test
    fun `门禁_v14迁移挂两处且老数据默认满分`() {
        val db = code(DB)
        assertTrue("v14 必须同时挂 onCreate 与 onUpgrade，且看列再动：\n" +
            sliceFun(db, "fun memoryQualityColumnsStmt(existingColumns: List<String>): List<Stmt>"),
            v14MigrationWired(db))
        assertFalse("负例：老库升级路径漏挂迁移 ⇒ 门禁必须红",
            v14MigrationWired(db.replace("if (oldVersion < 14) applyV14Migration(db)", "/* 忘了 */")))
        assertFalse("负例：默认分从 100 改成 0（老记忆升级当天全沉底）⇒ 门禁必须红",
            v14MigrationWired(db.replace("const val MEMORY_QUALITY_DEFAULT_SCORE = 100",
                "const val MEMORY_QUALITY_DEFAULT_SCORE = 0")))
        assertFalse("负例：加列不看列况直接 ALTER（重跑必炸）⇒ 门禁必须红",
            v14MigrationWired(db.replace(".filterNot { (col, _) -> existingColumns.any { it.equals(col, ignoreCase = true) } }", "")))
    }

    @Test
    fun `门禁_删除只有人那一把口`() {
        val store = code(STORE)
        assertTrue("MemoryStore.delete 是人点的单条删；写路径与质量路径不许碰它", humanDeleteIsTheOnlyDelete(store))
        val writeBody = funBody(store, "fun write(entry: MemoryEntry): WriteResult")
        assertFalse("写路径里出现 update 调用＝顺手改别人的记忆", writeBody.contains("update("))
        // 负例：写路径「顺手清掉旧的那条」＝自动删除用户记忆（§3 点名的红线）
        val autoPurge = store.replace(
            "val stored = allowed.entry",
            "val stored = allowed.entry\n            existing.forEach { File(it.id + \".md\").delete() }",
        )
        assertFalse("负例：写路径自动清旧条 ⇒ 门禁必须红", humanDeleteIsTheOnlyDelete(autoPurge))
    }

    @Test
    fun `门禁_扫描不许滤掉任何一条`() {
        val body = funBody(code(QUALITY), "fun pass(entries: List<MemoryEntry>, now: Long): Pass")
        assertTrue("pass 的原样清单必须与输入同条数（降权不是消失）：\n$body", scanKeepsEveryEntry(body))
        assertFalse("负例：把过期条目从扫描结果里滤掉（＝不删之删）⇒ 门禁必须红",
            scanKeepsEveryEntry(body.replace(
                "val scored = entries.map { marked(it, now) }",
                "val scored = entries.map { marked(it, now) }.filter { it.qualityScore >= STALE_BELOW }",
            )))
    }

    @Test
    fun `门禁_抱朴注入调用点没被本批改写`() {
        val engine = code(ENGINE)
        assertTrue("注入仍走 loadAll＋trimToBudget（质量层不许伸进引擎）", injectionCallSiteUnchanged(engine))
        assertFalse("负例：引擎改成读质量视图 ⇒ 对话行为被动了，门禁必须红",
            injectionCallSiteUnchanged(engine.replace(
                "MemoryRules.trimToBudget(it.loadAll(), ContextAssembler.RECALL_TOKEN_BUDGET)",
                "MemoryRules.trimToBudget(it.ranked(), ContextAssembler.RECALL_TOKEN_BUDGET)",
            )))
    }
}
