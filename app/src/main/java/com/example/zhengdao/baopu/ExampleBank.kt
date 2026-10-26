// 独立开发声明：本文件为本项目原创。
//
// 第二百零二批 · 口味档案与范例库的**落盘口**（唯一的读写路径）。
// 图纸：`_zd-private/docs/派工单-2026-10-25-第二百零二批-题材偏好与范例库.md` §1/§2/§3。
//
// 落点（与台账同屋，不另造第二套路径）：
// - `<工作区>/产物/口味档案.md`                       人可读档案（可手改，改完下一轮生效）
// - `<工作区>/产物/范例/正/*.md`、`/负/*.md`          正/负样本（负样本头里写一句原因）
// - `<工作区>/产物/范例/已移出.txt`                   「移出」清单（一行一个 `正/文件名`）
//
// 三条硬纪律：
// ① **不缓存**：每次注入都现读文件 ⇒ 设置页删掉一条，下一轮就按新的来（负例 2 的结构保证，
//    不是靠「记得清缓存」）；
// ② **移出不删文件**：只往 [TasteProfile.MUTED_FILE] 追加一行，原稿留在盘上（负例 4）；
// ③ **注入前脱敏**：正文与负样本原因都过 `SensitiveRedactor.redactText`（负例 3）。
//
// 目录解析复用 179 的 `BaopuLongformStore.vaultRoot/productDirOf`——`产物/` 只有一个来源。
package com.example.zhengdao.baopu

import android.content.Context
import com.example.zhengdao.browser.SensitiveRedactor
import com.example.zhengdao.ui.baopu.BaopuLongformStore
import com.example.zhengdao.util.TimeFormats
import java.io.File
import java.util.Locale

internal object ExampleBank {

    /** 单篇最多读这么多字（正文落 1200 已够注入；读盘别把整篇 10 万字拎进内存）。 */
    private const val READ_LIMIT = 20000

    /** 能当范例的文件后缀。 */
    private val SAMPLE_EXT = setOf("md", "txt")

    /** 负样本「一句原因」的两种写法（HTML 注释 / 引用行）。 */
    private val REASON_COMMENT = Regex("<!--\\s*原因\\s*[：:]\\s*(.+?)\\s*-->")
    private val REASON_QUOTE = Regex("(?m)^>\\s*原因\\s*[：:]\\s*(.+)$")

    // ── 路径（纯函数：只吃 File，JVM 可测） ────────────────────────────────

    fun archiveFile(productDir: File): File = File(productDir, TasteProfile.ARCHIVE_NAME)

    fun examplesRoot(productDir: File): File = File(productDir, TasteProfile.EXAMPLES_DIR)

    fun examplesDir(productDir: File, kind: SampleKind): File = File(examplesRoot(productDir), kind.dirName)

    fun mutedFile(productDir: File): File = File(examplesRoot(productDir), TasteProfile.MUTED_FILE)

    // ── 档案读 / 写 / 删 ──────────────────────────────────────────────────

    /** 现读档案（文件不存在 ⇒ 空档案，**不自动建**：没写过就是没写过）。 */
    fun loadArchive(productDir: File): TasteArchive {
        val f = archiveFile(productDir)
        if (!f.isFile) return TasteArchive()
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return TasteArchive()
        return TasteProfile.parse(text)
    }

    /** 写回档案（临时文件 + rename，先留 .bak——用户手改的内容不能丢）。 */
    fun saveArchive(productDir: File, archive: TasteArchive): Boolean {
        val f = archiveFile(productDir)
        return runCatching {
            val dir = f.parentFile ?: return@runCatching false
            if (!dir.isDirectory) dir.mkdirs()
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(TasteProfile.render(archive, stamp()), Charsets.UTF_8)
            if (f.isFile) f.copyTo(File(dir, f.name + ".bak"), overwrite = true)
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    /** 首次建模板（只在文件不存在时写；用户已有的内容一个字都不覆盖）。 */
    fun ensureArchive(productDir: File): TasteArchive {
        val cur = loadArchive(productDir)
        if (archiveFile(productDir).isFile) return cur
        runCatching {
            val f = archiveFile(productDir)
            val dir = f.parentFile ?: return@runCatching
            if (!dir.isDirectory) dir.mkdirs()
            f.writeText(TasteProfile.template(stamp()), Charsets.UTF_8)
        }
        return loadArchive(productDir)
    }

    /** 删一条偏好（写回文件）；返回删后的档案，文件写失败返回 null（UI 如实说没删掉）。 */
    fun deleteEntry(productDir: File, id: String): TasteArchive? {
        val cur = loadArchive(productDir)
        val next = TasteProfile.remove(cur, id)
        if (next.entries.size == cur.entries.size) return cur
        return if (saveArchive(productDir, next)) next else null
    }

    // ── 范例库 ────────────────────────────────────────────────────────────

    /**
     * 扫正/负两个目录，返回**未移出**的样本。
     * 不建目录、不写盘——只读（用户没放范例就是空库）。
     */
    fun loadSamples(productDir: File): List<TasteSample> {
        val muted = mutedSet(productDir)
        val out = mutableListOf<TasteSample>()
        SampleKind.entries.forEach { kind ->
            val dir = examplesDir(productDir, kind)
            val files = runCatching { dir.listFiles()?.filter { it.isFile } ?: emptyList() }.getOrDefault(emptyList())
            files.filter { it.extension.lowercase(Locale.ROOT) in SAMPLE_EXT }
                .filter { "${kind.dirName}/${it.name}" !in muted && it.name !in muted }
                .sortedBy { it.name }
                .forEach { f ->
                    val text = readLimited(f, READ_LIMIT)
                    if (text.isBlank()) return@forEach
                    out += TasteSample(
                        kind = kind,
                        name = f.name,
                        title = firstTitle(text) ?: f.name.substringBeforeLast('.'),
                        reason = if (kind == SampleKind.BAD) reasonOf(text) else null,
                        body = text,
                    )
                }
        }
        return out
    }

    /** 已移出的行（`正/xxx.md`，也兼容只写文件名）。 */
    fun mutedSet(productDir: File): Set<String> {
        val f = mutedFile(productDir)
        if (!f.isFile) return emptySet()
        return runCatching {
            f.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * 从库里移出一篇（**只记一行，不删文件**）。已经在清单里就返回 false（不重复追加）。
     */
    fun mute(productDir: File, kind: SampleKind, name: String): Boolean {
        val f = mutedFile(productDir)
        val line = "${kind.dirName}/$name"
        val cur = mutedSet(productDir)
        if (line in cur || name in cur) return false
        return runCatching {
            val dir = f.parentFile ?: return@runCatching false
            if (!dir.isDirectory) dir.mkdirs()
            f.appendText("$line\n", Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    /** 「移出」清单的篇数（设置页显示「已移出 3 篇，文件还在」）。 */
    fun mutedCount(productDir: File): Int = mutedSet(productDir).size

    /** 负样本的一句原因：先找 HTML 注释，再找 `> 原因：` 行；都没有 ⇒ null。 */
    fun reasonOf(text: String): String? =
        REASON_COMMENT.find(text)?.groupValues?.get(1)?.trim()
            ?: REASON_QUOTE.find(text)?.groupValues?.get(1)?.trim()

    /** 正文首行的标题（`# xxx` 或第一行非空）。 */
    private fun firstTitle(text: String): String? = text.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.removePrefix("#")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun readLimited(f: File, max: Int): String = runCatching {
        f.bufferedReader(Charsets.UTF_8).use { r ->
            val buf = CharArray(max)
            var n = 0
            while (n < max) {
                val k = r.read(buf, n, max - n)
                if (k <= 0) break
                n += k
            }
            String(buf, 0, n)
        }
    }.getOrDefault("")

    // ── 注入（每轮现读，不缓存） ──────────────────────────────────────────

    /**
     * 给一篇候选算注入材料。**每次都重读档案与范例库**——这是「删掉下一轮立即生效」的
     * 唯一实现方式，不要在这里加缓存字段。
     */
    fun inject(productDir: File, seed: TopicSeed, style: TopicStyle?): TasteInjection? {
        val archive = loadArchive(productDir)
        val samples = loadSamples(productDir)
        val redactor = SensitiveRedactor()
        // 两道脱敏：号码/证件走 SensitiveRedactor，客户姓名走 TasteProfile.redactNames
        // （范例里「客户王女士」这类称呼不比手机号少见，派工单 §2 点名要遮）。
        return TasteProfile.injection(archive, seed, style, samples) {
            TasteProfile.redactNames(redactor.redactText(it))
        }
    }

    /** 种子 → 正样本相似度（候选排序用；同样现读）。 */
    fun affinity(productDir: File, seeds: List<TopicSeed>, style: TopicStyle?): Map<String, Double> {
        val samples = loadSamples(productDir)
        if (samples.isEmpty()) return emptyMap()
        return seeds.associate { it.id to TasteProfile.affinityOf(it, style, samples) }
    }

    // ── Context 薄壳（生产入口；逻辑全在上面那些吃 File 的函数里） ──────────

    /** `<工作区>/产物/`（复用 179 的落点判据，不另拼路径）。 */
    fun productDir(ctx: Context): File =
        BaopuLongformStore.productDirOf(BaopuLongformStore.vaultRoot(ctx.applicationContext))

    fun loadArchive(ctx: Context): TasteArchive = loadArchive(productDir(ctx))

    fun loadSamples(ctx: Context): List<TasteSample> = loadSamples(productDir(ctx))

    fun deleteEntry(ctx: Context, id: String): TasteArchive? = deleteEntry(productDir(ctx), id)

    fun mute(ctx: Context, kind: SampleKind, name: String): Boolean = mute(productDir(ctx), kind, name)

    fun ensureArchive(ctx: Context): TasteArchive = ensureArchive(productDir(ctx))

    fun injectFor(ctx: Context, seed: TopicSeed, style: TopicStyle?): TasteInjection? =
        inject(productDir(ctx), seed, style)

    fun archivePath(ctx: Context): String = archiveFile(productDir(ctx)).absolutePath

    /** 档案里「版本注释」用的时间戳（唯一碰时钟的地方，纯展示；格式走 util/TimeFormats）。 */
    fun stamp(nowMillis: Long = System.currentTimeMillis()): String = TimeFormats.minuteStamp(nowMillis)
}
