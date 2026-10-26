// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十九批 · MediaStore 分流与归属（设计 §5／§8 行 13/14/15／§9 S4）。
//
// 一句话：**按「谁来看」分图**——人要翻相册找的（用户点保存的截屏、模型生图）走
// ContentResolver 进 `Pictures/证道/{截屏,图片}`；模型每步「看一眼」的核对图仍落
// `证道/截屏/`（177 现状），**不进相册**（一次任务几十张会把用户相册刷爆）。
//
// 本文件只装**判据与账本**（纯逻辑 ＋ 薄文件 IO，JVM 临时目录直测）：
//   · 相册落点与相对路径（`RELATIVE_PATH` 的唯一来源）；
//   · `.归属.jsonl` 的编解码与追加（**不升 `BaopuDb` 版本**——它就是一个文本账本）；
//   · 「这条能不能删」的判据（`_id` 对上 **且** `owner` 是本包，缺一条就按非自产处理）；
//   · 不覆盖命名、`sha256` 判重、存量迁移的**计划**（真正搬盘在 `MediaMigration`）。
//
// Android 那一半（`ContentResolver.insert` ＋ `IS_PENDING` 事务式发布 ＋ 按 id 删）在
// `MediaStoreSink`；两者分工照 `GenerateImage`（判据）／`GenerateImageTool`（真网络与落盘）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.library.FaceIndex
import java.io.File
import org.json.JSONObject

/**
 * 相册分流 ＋ 归属账本（纯判据）。
 *
 * 三条硬规矩（都是派工单 §2 的判据，负例在 `MediaOwnershipTest` 里逐条钉）：
 * 1. **写相册只能走 ContentResolver**：本文件**不提供任何 `File` 写相册的方法**，
 *    连文件对象都不往外给（`MediaPathGateTest` 还会再扫一遍主源码）；
 * 2. **归属先有账，才谈删除**：账上没有 ⇒ 一律按「不是自己生成的」走确认；
 * 3. **一份图只落一处**：核对图进 `证道/截屏/`，人看的进 `Pictures/证道/…`，不双写。
 */
internal object MediaOwnership {

    /** 账本文件名（`<工作区>/.归属.jsonl`；一行一条，只追加不重写）。 */
    val LEDGER_NAME: String get() = BaopuKernel.Data.FILE_MEDIA_LEDGER

    /** 一条归属记录的状态字（`deleted` 是**墓碑**：删了也要留痕，否则下轮又当它是新的）。 */
    const val STATE_ADDED = "added"
    const val STATE_DELETED = "deleted"

    /** 来源（进账本的 `source` 字段；只认这几个，写别的会让 178 的分支落空）。 */
    const val SOURCE_SCREENSHOT = "screenshot"
    const val SOURCE_IMAGE = "generate_image"

    // ── 落点（唯一来源：内核常量） ────────────────────────────────────────────

    /** `Pictures/证道/截屏/` —— 用户可见截屏。 */
    fun relShotDir(): String =
        "Pictures/${BaopuKernel.Data.DIR_ALBUM}/${BaopuKernel.Data.DIR_ALBUM_SHOTS}/"

    /** `Pictures/证道/图片/` —— 模型生图/图生图。 */
    fun relImageDir(): String =
        "Pictures/${BaopuKernel.Data.DIR_ALBUM}/${BaopuKernel.Data.DIR_ALBUM_IMAGES}/"

    /** 相册内的相对路径前缀（`Pictures/证道/`）——判断「这条是不是我们的相册」用。 */
    fun relAlbumRoot(): String = "Pictures/${BaopuKernel.Data.DIR_ALBUM}/"

    /** 来源 ⇒ 落点。认不出的来源回 null（调用方报错，不猜）。 */
    fun relDirOf(source: String): String? = when (source) {
        SOURCE_SCREENSHOT -> relShotDir()
        SOURCE_IMAGE -> relImageDir()
        else -> null
    }

    // ── 账本 ─────────────────────────────────────────────────────────────────

    /**
     * 一条归属记录。
     *
     * 字段与派工单逐字对齐：MediaStore `_id`、`sha256`、`owner`（本包名）、创建时间、来源任务。
     * 另加 `name`／`relPath`（人要能看懂是哪张）与 `state`（墓碑）。
     */
    data class Entry(
        val id: Long,
        val name: String,
        val relPath: String,
        val sha256: String,
        val owner: String,
        val createdAt: Long,
        val source: String,
        val bytes: Long = 0L,
        val state: String = STATE_ADDED,
        /** 墓碑时间（`state == deleted` 时才有值；只追加不改写）。 */
        val deletedAt: Long = 0L,
    ) {
        val isLive: Boolean get() = state != STATE_DELETED
    }

    /** 账本文件（`<工作区>/.归属.jsonl`）。**只算路径，不建文件**。 */
    fun ledgerFile(workspace: File): File = File(workspace, LEDGER_NAME)

    /**
     * 一条记录 ⇒ 一行 JSON（键序固定，便于 diff 与逐字节门禁）。
     *
     * 手搓而不是 `JSONObject.toString()`：`org.json` 在 JVM 单测里是参考实现（HashMap 序）、
     * 在真机是插入序，两边字节不一致 ⇒ 门禁抓不住「谁把字段加丢了」。
     */
    fun encode(e: Entry): String = buildString {
        append('{')
        append("\"v\":1")
        append(",\"id\":").append(e.id)
        append(",\"name\":").append(quote(e.name))
        append(",\"relPath\":").append(quote(e.relPath))
        append(",\"sha256\":").append(quote(e.sha256))
        append(",\"owner\":").append(quote(e.owner))
        append(",\"createdAt\":").append(e.createdAt)
        append(",\"source\":").append(quote(e.source))
        append(",\"bytes\":").append(e.bytes)
        append(",\"state\":").append(quote(e.state))
        if (e.deletedAt > 0L) append(",\"deletedAt\":").append(e.deletedAt)
        append('}')
    }

    /** JSON 字符串字面量（只处理账本里会出现的字符；换行/制表折成空格，保住「一行一条」）。 */
    private fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('"')
        for (c in raw) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' || c == '\r' || c == '\t' -> sb.append(' ')
                c.code < 0x20 -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 一行 ⇒ 记录；坏行/缺字段回 null（**不猜**，坏行整条丢，绝不伪造一条「自己生成的」）。 */
    fun decode(line: String): Entry? {
        val text = line.trim()
        if (text.isEmpty() || !text.startsWith("{")) return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val id = o.optLong("id", 0L)
        val name = o.optString("name")
        val sha = o.optString("sha256")
        val owner = o.optString("owner")
        // id/sha/owner 三样缺一 ⇒ 这条记录没有归属意义，宁可当成没有
        if (id <= 0L || name.isBlank() || sha.isBlank() || owner.isBlank()) return null
        return Entry(
            id = id,
            name = name,
            relPath = o.optString("relPath"),
            sha256 = sha,
            owner = owner,
            createdAt = o.optLong("createdAt", 0L),
            source = o.optString("source"),
            bytes = o.optLong("bytes", 0L),
            state = o.optString("state").ifBlank { STATE_ADDED },
            deletedAt = o.optLong("deletedAt", 0L),
        )
    }

    /** 整份账本 ⇒ 记录表（坏行跳过，**不抛**：一条脏行不该让相册功能整块不可用）。 */
    fun parseAll(text: String): List<Entry> =
        text.lineSequence().mapNotNull { decode(it) }.toList()

    /** 读账本；没有文件/读不了 ⇒ 空表（首次运行本来就没有）。 */
    fun readAll(workspace: File): List<Entry> {
        val f = ledgerFile(workspace)
        if (!f.isFile) return emptyList()
        val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return parseAll(text)
    }

    /**
     * 追加一条（**只追加**：重写整本账会丢并发写入，也丢「删过什么」的痕迹）。
     *
     * @return 真写进去了没有（失败由调用方决定回滚——相册条目已在 ⇒ 宁可账上缺一条走确认）
     */
    fun append(workspace: File, e: Entry): Boolean = runCatching {
        val f = ledgerFile(workspace)
        f.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        // 前一行可能没有换行（外部编辑器改过），先补一个，免得两行粘成一行坏记录
        val needsNewline = f.isFile && f.length() > 0L && !endsWithNewline(f)
        f.appendText((if (needsNewline) "\n" else "") + encode(e) + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /** 记一条墓碑（删成功的自产条目）。 */
    fun appendTombstone(workspace: File, entry: Entry, nowMillis: Long): Boolean =
        append(workspace, entry.copy(state = STATE_DELETED, deletedAt = nowMillis))

    private fun endsWithNewline(f: File): Boolean = runCatching {
        java.io.RandomAccessFile(f, "r").use { raf ->
            if (raf.length() <= 0L) true else {
                raf.seek(raf.length() - 1)
                raf.read() == '\n'.code
            }
        }
    }.getOrDefault(true)

    /** 活着的（未删）记录。 */
    fun live(entries: List<Entry>): List<Entry> = entries.filter { it.isLive }

    /** 按 `sha256` 找同图（**同图只存一份**：命中就直接复用那条，不再入库）。 */
    fun findLiveBySha(entries: List<Entry>, sha256: String): Entry? =
        entries.firstOrNull { it.isLive && it.sha256.equals(sha256, ignoreCase = true) }

    /** 按 `_id` 找记录（含墓碑——墓碑命中说明「删过了」，不能再当新条目）。 */
    fun findById(entries: List<Entry>, id: Long): Entry? = entries.firstOrNull { it.id == id }

    /** 按文件名找活记录。 */
    fun findLiveByName(entries: List<Entry>, name: String): Entry? =
        entries.firstOrNull { it.isLive && it.name == name }

    /** 按相对路径找活记录（删除入口传进来的就是这让的路径）。 */
    fun findLiveByRelPath(entries: List<Entry>, relPath: String): Entry? =
        entries.firstOrNull { it.isLive && it.relPath.equals(relPath, ignoreCase = true) }

    // ── 相册路径识别 ─────────────────────────────────────────────────────────

    /**
     * 把用户/模型给的一条路径规整成「相册内相对路径」（`Pictures/证道/…`）；不是相册的 ⇒ null。
     *
     * 认三种写法（都是同一条路，不认就等于让人去猜）：
     * - `Pictures/证道/截屏/x.png`
     * - `/storage/emulated/0/Pictures/证道/截屏/x.png`
     * - `证道/截屏/x.png`（相对 `Pictures/` 的口语写法，**只在账本里能对上时才用**）
     */
    fun albumRelPathOf(path: String): String? {
        val p = path.trim().replace('\\', '/').trimStart('/')
        if (p.isEmpty()) return null
        val root = relAlbumRoot() // "Pictures/证道/"
        val idx = p.indexOf(root)
        if (idx >= 0) return p.substring(idx)
        // 口语写法：证道/截屏/x.png ⇒ Pictures/证道/截屏/x.png
        val bare = "${BaopuKernel.Data.DIR_ALBUM}/"
        if (p.startsWith(bare)) return "Pictures/$p"
        return null
    }

    /** 这条相册路径是不是我们两个相册目录之一（`截屏/` 或 `图片/`）。 */
    fun isAlbumPath(relPath: String): Boolean =
        relPath.startsWith(relShotDir()) || relPath.startsWith(relImageDir())

    // ── 不覆盖命名 ───────────────────────────────────────────────────────────

    /**
     * 一个没被占用的显示名：占用就依次试 `-2`、`-3`……（沿用 179 的 slug 口径，
     * `GenerateImage.uniqueTarget` 是同一套规则的文件版；这里按**名字集合**算，
     * 因为相册里的名字由 MediaStore 定，手上没有「目录」可探）。
     */
    fun uniqueName(taken: Collection<String>, base: String, ext: String): String {
        val dot = if (ext.startsWith(".")) ext else ".$ext"
        val first = "$base$dot"
        if (first !in taken) return first
        var n = 2
        while (true) {
            val next = "$base-$n$dot"
            if (next !in taken) return next
            n++
        }
    }

    /** 拆名字 ⇒ (主干, 扩展名)；没有扩展名时 ext 为空串。**扩展名不许是点开头**。 */
    fun splitName(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return name to ""
        return name.substring(0, dot) to name.substring(dot + 1)
    }

    // ── 删除判据 ─────────────────────────────────────────────────────────────

    /** 删除结论：要么「确认是自己写的，可以删」，要么「按非自产处理，回到确认路径」。 */
    sealed interface DeleteVerdict {
        /** 账上登记过、`_id` 对上、`owner` 是本包 ⇒ 可以删 MediaStore 行。 */
        data class Ours(val entry: Entry) : DeleteVerdict

        /** 任何一条对不上 ⇒ **宁可多问**，不许猜。`reason` 要能直接说给用户听。 */
        data class Ask(val reason: String) : DeleteVerdict
    }

    /**
     * 「这条能不能删」的唯一判据。
     *
     * @param entry 账本里按 [id] 找到的记录（没有就传 null）
     * @param id 目标 MediaStore `_id`
     * @param albumOwner MediaStore 里这一行的 `OWNER_PACKAGE_NAME`（读不到传 null）
     * @param pkg 本包名
     */
    fun deleteVerdict(entry: Entry?, id: Long, albumOwner: String?, pkg: String): DeleteVerdict {
        if (entry == null) return DeleteVerdict.Ask("归属清单里没有这一条（按「不是本 App 生成的」处理）")
        if (entry.id != id) return DeleteVerdict.Ask("归属清单里的 id 对不上（清单 ${entry.id}／目标 $id）")
        if (entry.owner != pkg) return DeleteVerdict.Ask("这一条是 ${entry.owner} 写的，不是本 App")
        if (!entry.isLive) return DeleteVerdict.Ask("这一条已经删过了（账上留着墓碑）")
        if (albumOwner != null && albumOwner != pkg) {
            return DeleteVerdict.Ask("相册里的条目属于 $albumOwner（不是本 App）")
        }
        return DeleteVerdict.Ours(entry)
    }

    // ── 判重 ─────────────────────────────────────────────────────────────────

    /** 文件流式 sha256（复用认脸索引那份实现：64KB 缓冲，大图不整读进内存）。 */
    fun sha256(file: File): String = FaceIndex.sha256(file)

    /** 内存字节的 sha256（生图/截屏本来就在内存里，别再落一次临时盘）。 */
    fun sha256(bytes: ByteArray): String = FaceIndex.sha256Bytes(bytes)

    // ── 存量迁移（只算计划，搬盘在 MediaMigration） ──────────────────────────

    /** 存量/目标里的一项（只认名字与哈希，够算冲突了）。 */
    data class Item(val name: String, val sha256: String)

    /** 一次复制计划。`reason` 写清为什么改名（进对账报告，人也看得懂）。 */
    data class CopyPlan(val from: String, val to: String, val reason: String)

    /** 迁移计划：要复制的、已经在位的、跳过的（三张单子都要能说清）。 */
    data class ShotMigrationPlan(
        val copies: List<CopyPlan> = emptyList(),
        val identical: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
    ) {
        /** 「这一遍什么都没搬」——就是「二遍全零」那条判据的口径。 */
        val isEmpty: Boolean get() = copies.isEmpty()
    }

    /** 冲突原因字（报告与测试共用，防两处各写一遍）。 */
    const val WHY_SAME = "同名同哈希（已在位）"
    const val WHY_RENAMED = "同名不同内容（改名，绝不覆盖）"

    /**
     * `screenshots/` ⇒ `截屏/` 的纯计划。
     *
     * 规则（与 `ZhengdaoDirs.adoptDir` 的纪律一致：**目标已有同名条目绝不覆盖**）：
     * - 目标里**已有同哈希的内容** ⇒ 已在位（不复制，第二遍全零）；
     *   ⚠️ 这一条必须排在名字判据前面：第一次把 `a.png` 改名成 `a-2.png` 之后，
     *   第二遍若只按名字算，会一直往上加 `a-3`、`a-4`……「二遍全零」永远不成立；
     * - 同名且同哈希 ⇒ 已在位；
     * - 同名但内容不同 ⇒ 换个没占用的名字（`-2`、`-3`），原因记 [WHY_RENAMED]；
     * - 没冲突 ⇒ 原名复制；
     * - 哈希为空（读不了/算失败）⇒ 跳过并记一笔（**不许**在没核对的前提下搬）。
     */
    fun planShotMigration(legacy: List<Item>, target: List<Item>): ShotMigrationPlan {
        val taken = target.map { it.name }.toMutableSet()
        val targetBy = target.associateBy { it.name }
        val targetShas = target.map { it.sha256.lowercase() }.toMutableSet()
        val copies = mutableListOf<CopyPlan>()
        val identical = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (item in legacy.sortedBy { it.name }) {
            if (item.name.isBlank() || item.sha256.isBlank()) {
                skipped += item.name.ifBlank { "(空名)" }
                continue
            }
            if (item.sha256.lowercase() in targetShas) {
                identical += item.name
                continue
            }
            // 走到这里 ⇒ 目标里没有这份内容（同名也只可能撞内容不同的那个）
            val hit = targetBy[item.name]
            if (hit == null) {
                copies += CopyPlan(item.name, item.name, "无冲突")
                taken += item.name
            } else {
                val (base, ext) = splitName(item.name)
                val next = uniqueName(taken, base, ext.ifBlank { "png" })
                copies += CopyPlan(item.name, next, WHY_RENAMED)
                taken += next
            }
            // 同一批里两份内容相同的存量：第一份搬完就算「已在位」，不搬第二份（同图只存一份）
            targetShas += item.sha256.lowercase()
        }
        return ShotMigrationPlan(copies = copies, identical = identical, skipped = skipped)
    }
}
