// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-26-第二百二十批-后果预览 §1（只读事实：文件 stat/列目录、
// 台账/DB 索引计数、网页 DOM 读取 ≤150ms）。
//
// 这是 [LocalFacts] 的**生产实现**：把「文件系统 / 台账 / 网页 DOM」三处只读事实接到
// 纯函数层。纪律：
// - 只读：本文件没有 `writeText/delete/mkdirs/rename` 任何字面（负例 1 的靶心之一，
//   由 `ImpactGateTest` 从源码层钉）；
// - 不持 `Context` 静态字段（lint `StaticFieldLeak` 会红）：[of] 每次现造一个实例，
//   只拿 `applicationContext`，用完即弃；
// - DOM 探针失败一律 null（→ 界面只报目标名，**不编**「已填 0 项」）。
package com.example.zhengdao.baopu

import android.content.Context
import com.example.zhengdao.bridge.BrowserCommands
import com.example.zhengdao.terminal.KnowledgeBase
import java.io.File
import org.json.JSONObject

/**
 * 只读事实的**生产实现**（文件系统 ＋ 台账 ＋ 网页 DOM）。
 *
 * @param appCtx applicationContext（不持 Activity，避免泄漏）
 * @param root 工作区根（与引擎同一个根：`BaopuRuntime.workspaceRoot`）
 * @param db 抱朴库（台账计数用；null = 不算台账，`ledgerRows=0` 且不假装知道）
 */
internal class AndroidFacts(
    appCtx: Context,
    private val rootDir: File,
    private val db: BaopuDb?,
) : LocalFacts {

    private val ctx: Context = appCtx.applicationContext

    override val root: String get() = rootDir.absolutePath

    override val rawDir: String? by lazy {
        runCatching { KnowledgeBase.rawDir(ctx).absolutePath }.getOrNull()
    }

    override fun sizeOf(path: String): Long? = runCatching {
        val f = File(path)
        if (!f.exists()) null else if (f.isDirectory) null else f.length()
    }.getOrNull()

    override fun isDir(path: String): Boolean =
        runCatching { File(path).isDirectory }.getOrDefault(false)

    override fun listNames(path: String): List<String>? = runCatching {
        File(path).list()?.toList()
    }.getOrNull()

    /**
     * 台账命中行数：产物台账（`artifact`，按 `workspace + rel_path`）＋
     * 资料库台账（`library_item`，按文件名或私有路径）。
     *
     * 为什么按"行数"而不是"文件数"：一条文件可能同时有两行账（产物一行、资料库一行），
     * 而派工单 §1 的字段名就是「会被影响的台账行数」。宁可如实多报一行，
     * 也不做猜测性去重（少报会让用户以为"没入过账"）。
     */
    override fun ledgerRowsFor(paths: List<String>): Int {
        val d = db ?: return 0
        if (paths.isEmpty()) return 0
        val rels = paths.mapNotNull { relOf(it) }.ifEmpty { return 0 }
        val names = paths.map { nameOf(it) }.filter { it.isNotEmpty() }
        return runCatching {
            d.read { raw ->
                var n = 0
                raw.rawQuery(
                    "SELECT COUNT(*) FROM artifact WHERE workspace = ? AND rel_path IN (" +
                        rels.joinToString(",") { "?" } + ")",
                    (listOf(rootDir.absolutePath) + rels).toTypedArray(),
                ).use { c -> if (c.moveToNext()) n += c.getInt(0) }
                if (names.isNotEmpty()) {
                    raw.rawQuery(
                        "SELECT COUNT(*) FROM library_item WHERE name IN (" +
                            names.joinToString(",") { "?" } + ")",
                        names.toTypedArray(),
                    ).use { c -> if (c.moveToNext()) n += c.getInt(0) }
                }
                n
            }
        }.getOrDefault(0)
    }

    /**
     * 网页表单读数（**一次** execJs，≤150ms 预算的软闸）。
     *
     * 线程纪律：`BrowserCommands.execJs` 会自己 post 到主线程再等回调（见其 `evalJs`），
     * 所以**必须从非主线程调**（预览跑在 IO 协程上）；主线程调会走 `onMain` 的失败分支
     * 返回 null ⇒ 界面只报目标名，不会翻车。
     */
    override fun formState(selector: String): FormState? = runCatching {
        val raw = BrowserCommands.execJs(ImpactDom.script(selector))
        ImpactDom.parse(raw)
    }.getOrNull()

    override fun nowMs(): Long = System.nanoTime() / 1_000_000

    private fun relOf(abs: String): String? {
        val r = rootDir.absolutePath
        val a = runCatching { File(abs).absolutePath }.getOrNull() ?: return null
        if (!a.startsWith(r)) return null
        return a.removePrefix(r).trimStart('/', '\\').replace('\\', '/').ifEmpty { null }
    }

    private fun nameOf(abs: String): String =
        abs.substringAfterLast('/').substringAfterLast('\\')

    companion object {
        /**
         * 造一份事实源。
         *
         * @param db 可空：某些页面（预览/单测壳）没有库 —— null 时 `ledgerRows` 一律 0，
         *   这是**已知为零**（不是"未知"），因为没库就没台账。
         */
        fun of(appCtx: Context, root: File, db: BaopuDb?): LocalFacts =
            AndroidFacts(appCtx, root, db)
    }
}

/**
 * 网页表单探针（**纯**：脚本在这儿、解析也在这儿，避免"页面里拼一套、Kotlin 里解一套"）。
 *
 * 口径与 `PageFingerprint` 同源：
 * - 分隔符用 U+0001（页面几乎不可能含它），不用 JSON（`org.json` 在 JVM 单测里是 stub）；
 * - 解析不出 ⇒ null（**不装**"已填 0 项"）。
 *
 * 取五个事实：已填字段数、必填空着几项、提交目标主机、当前页主机、按钮短名。
 */
internal object ImpactDom {

    /** 字段分隔符（U+0001）。 */
    const val SEP: Char = '\u0001'

    /**
     * 读 DOM 的脚本（**只读**：`querySelector` 与属性读取，不写页面、不提交、不跳转）。
     *
     * 为什么要 `encodeURIComponent`-级的转义：selector 来自模型，含引号/反斜杠时
     * 直接拼会破坏脚本（`TeachScripts.cssAttrValue` 同款坑）。
     */
    fun script(selector: String): String {
        val lit = "'" + selector.replace("\\", "\\\\").replace("'", "\\'") + "'"
        return "(function(){" +
            "var el=document.querySelector($lit);" +
            "var f=document.querySelectorAll('input,select,textarea');" +
            "var filled=0,req=0;" +
            "for(var i=0;i<f.length;i++){var x=f[i];" +
            "if(x.type=='hidden'||x.type=='submit'||x.type=='button')continue;" +
            "var v=(x.value||'');" +
            "if(v.length>0)filled++;" +
            "var must=x.required||x.getAttribute('aria-required')=='true';" +
            "if(must&&v.length==0)req++}" +
            "var act=el?(el.closest('form')||{}).action||'' : '';" +
            "var host=function(u){try{return u?new URL(u,location.href).host:location.host}catch(e){return ''}};" +
            "var name=el?(el.innerText||el.value||el.id||''):'';" +
            "return [filled,req,host(act),location.host," +
            "(name+'').replace(/[\\r\\n]/g,' ').slice(0,12)]" +
            ".join(String.fromCharCode(1))})()"
    }

    /**
     * 解析桥回执（`{"result":"…"}` / `{"error":…}` / null）。
     *
     * 桥回执是 JSON 字符串，但 `result` 里我们自己的脚本用 U+0001 分隔 —— 所以先剥壳
     * 再按分隔符切。任何一步不对劲 ⇒ null（上层只报目标名）。
     */
    fun parse(raw: String?): FormState? {
        if (raw.isNullOrBlank()) return null
        val body = runCatching {
            val o = JSONObject(raw)
            if (o.has("error")) return null
            o.optString("result").ifEmpty { null } ?: return null
        }.getOrNull() ?: return null
        val parts = body.split(SEP)
        if (parts.size != 5) return null
        val filled = parts[0].trim().toIntOrNull() ?: return null
        val req = parts[1].trim().toIntOrNull() ?: return null
        return FormState(
            filled = filled,
            requiredEmpty = req,
            actionHost = parts[2].trim(),
            currentHost = parts[3].trim(),
            buttonText = parts[4].trim(),
        )
    }
}
