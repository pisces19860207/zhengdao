// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十七批 · 534 灵感闪回 ①「记录灵感」——灵感 L0 原文库。
//
// 依据：用户 2026-11-08 立单裁定（`裁定记录-2026-11.md` §534）：
//   「灵感捕获：一句话直写 L0，不自动摘要、不自动进 L2、不自动成待办」；
// 四层口径：`_zd-private/docs/设计-记忆双系统-2026-11-05.md` §一（L0 原文层——
//   对话/动作/产物的原文，只追加、**永不自动删**，只有用户能删）。
//
// 房型照 [MemoryStore]（文件是真相，人能读、终端 agent 也能读）：
//   `<工作区>/记忆/灵感/<id>.md`，front-matter（id/created_at/tags）＋ 正文＝原文**逐字**。
// 三条纪律：
//   1. **只追加**：唯一写口 [record] 只新建文件；本文件没有任何删/改路径
//      （源码门禁 InspirationRecallSourceGateTest 扫「本包无删除 API」）；
//   2. **不加工**：原文落盘前后一个字节不动（trim 只去掉录入口误带的空白，
//      正文原样写回）——不摘要、不改写、不进 L2；
//   3. **坏文件不静默**：解析不出的文件跳过但**带回计数**（调用方要能说出来，
//      「读不到 ⇒ 明说，不许猜、不许编」是 M3 三件套的失败兜底口径）。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.core.TimeFormats
import java.io.File

/** 灵感 L0 的一条原文（正文即用户原话，一字不改）。 */
internal data class InspirationRecord(
    /** 稳定 id（`insp-<时间戳>`；也是账行与建议页签行引用的 L0 指针）。 */
    val id: String,
    /** 记录时刻（毫秒；front-matter `created_at`）。 */
    val createdAtMs: Long,
    /** 用户给的标签（可空；「同任务/主题」线索之一）。 */
    val tags: List<String>,
    /** 原文（逐字）。 */
    val body: String,
) {
    /** 30 字预览（625 统一口径：「候选摘要（30 字＋账行 id）」——喂给选择器的形态）。 */
    val preview: String get() = body.take(PREVIEW_CHARS)

    companion object {
        /** 候选摘要的字数上限（625 口径的 30 字；截断不加省略号——那是渲染层的事）。 */
        const val PREVIEW_CHARS = 30
    }
}

/**
 * 灵感 L0 原文库（534 判据①「记一条灵感 ⇒ L0 有原文」的落盘点）。
 *
 * 只依赖注入进来的目录（[dir]），无 Android、无时钟（时刻全由调用方给）⇒ JVM 直测。
 */
internal class InspirationStore(private val dir: File) {

    /** 一条记录的结果；失败必须带人话原因（不静默）。 */
    sealed interface SaveResult {
        data class Saved(val record: InspirationRecord) : SaveResult
        data class Rejected(val reason: String) : SaveResult
    }

    /** 读回的结果：好条目＋坏文件计数（坏文件**说出来**，不冒充没有）。 */
    data class Scan(val records: List<InspirationRecord>, val broken: Int)

    /**
     * 记一条灵感：原文＋时间＋标签进 L0。
     *
     * 空白原文不收（一条没有字的灵感不是灵感）；正文限 [BODY_LIMIT]——
     * 「一句话直写」的形态约束，超长是往 L0 里塞文章，拒收并说清上限。
     */
    fun record(nowMs: Long, body: String, tags: List<String>): SaveResult {
        val text = body.trim()
        if (text.isEmpty()) return SaveResult.Rejected("灵感原文是空的——没有字就不收")
        if (text.length > BODY_LIMIT) {
            return SaveResult.Rejected("灵感限一句话（≤$BODY_LIMIT 字，现在 ${text.length}）——长文请走产物")
        }
        val cleanTags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val record = InspirationRecord(
            id = newId(nowMs),
            createdAtMs = nowMs,
            tags = cleanTags,
            body = text,
        )
        return runCatching {
            dir.mkdirs()
            val file = File(dir, record.id + ".md")
            if (file.exists()) return SaveResult.Rejected("同名灵感已存在（${record.id}）——同毫秒两条，稍候重试")
            file.writeText(render(record))
            SaveResult.Saved(record)
        }.getOrElse { SaveResult.Rejected("灵感落盘失败：${it.javaClass.simpleName}") }
    }

    /** 全量读回（文件名升序＝时间升序；坏文件跳过但计数）。 */
    fun scan(): Scan {
        if (!dir.isDirectory) return Scan(emptyList(), 0)
        var broken = 0
        val records = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { f ->
                parse(f.readText()) ?: run {
                    broken += 1
                    null
                }
            }
        return Scan(records, broken)
    }

    companion object {
        /** 正文上限：一句话直写的形态约束（与 MemoryRules.BODY_LIMIT=500 同量级，更紧）。 */
        const val BODY_LIMIT = 120

        /** 灵感目录（`<工作区>/记忆/灵感/`；在 allowedWrite 的写入边界之内）。 */
        fun dirOf(workspace: File): File =
            File(File(workspace, BaopuKernel.Data.DIR_MEMORY), BaopuKernel.Data.DIR_INSPIRATION)

        /** id：`insp-<文件戳>`（确定性、可从文件名重建——文件是真相）。 */
        fun newId(nowMs: Long): String = "insp-" + TimeFormats.fileStamp(nowMs)

        /** front-matter 渲染（[parse] 的逆；正文原样，不加工）。 */
        fun render(r: InspirationRecord): String = buildString {
            append("---\n")
            append("id: ").append(r.id).append('\n')
            append("created_at: ").append(r.createdAtMs).append('\n')
            append("tags: ").append(r.tags.joinToString(", ")).append('\n')
            append("---\n\n")
            append(r.body).append('\n')
        }

        /** 解析（缺关键格/坏时刻 ⇒ null，由 [scan] 计入 broken，不硬读）。 */
        fun parse(text: String): InspirationRecord? {
            if (!text.startsWith("---\n")) return null
            val end = text.indexOf("\n---\n", startIndex = 4)
            if (end < 0) return null
            val fields = LinkedHashMap<String, String>()
            for (line in text.substring(4, end).split('\n')) {
                if (line.isBlank()) continue
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                fields[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            val id = fields["id"]?.takeIf { it.isNotEmpty() } ?: return null
            val at = fields["created_at"]?.toLongOrNull() ?: return null
            val body = text.substring(end + 5).trim()
            if (body.isEmpty()) return null
            val tags = fields["tags"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            return InspirationRecord(id = id, createdAtMs = at, tags = tags, body = body)
        }
    }
}
