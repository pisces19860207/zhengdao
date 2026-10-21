// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单-2026-10-21-第一百二十八批-知识库v2（本地索引/检索/引用；不上云、不自动全盘扫、
// 不接外部向量库）。设计页：`证道设计报告/知识库v2-设计-2026-10-21.md`。
package com.example.zhengdao.baopu.kb

/**
 * 知识库 v2 的**数据模型**（第一百二十八批）。
 *
 * 三个类型对应索引文件的三层：`KbIndex`（整份索引）→ `KbDoc`（一个文件的账＋指纹）
 * → `KbChunk`（一个切块，检索与引用的最小单位）。
 *
 * ## 为什么 chunk 自带 relPath/heading（doc 里已有还抄一遍）
 * 检索层只跟 chunk 打交道（TopK、预算裁剪、渲染都只要 chunk）——每个 chunk 必须
 * **自包含到能独立渲染出引用**。少一个字段，渲染处就要回头查 doc，引用正确性
 * 就多一条跨对象不变式（负例 N2「无来源编引用」钉的就是这条）。
 */
data class KbChunk(
    /** 相对被索引目录的路径（`/` 分隔；检索层拒收空值——见 [KbRetriever]）。 */
    val relPath: String,
    /** 行号起点（1 起）。html/docx 是**提取文本**的行号（见 [source]）。 */
    val lineStart: Int,
    /** 行号终点（含）。 */
    val lineEnd: Int,
    /** 所属最近标题（md 的 `#` 行；没有则空串）。 */
    val heading: String = "",
    /** 块正文（已 trim；非空——空块在建索引时就被丢掉）。 */
    val text: String,
    /**
     * 来源类型：`file` = 行号就是原文件行号（txt/md 等纯文本）；
     * `extracted` = 行号是**提取文本**的行号（html 剥标签后／docx 抽取后）——
     * 引用时必须标明，不许冒充原文件行号。
     */
    val source: String = SOURCE_FILE,
) {
    companion object {
        const val SOURCE_FILE = "file"
        const val SOURCE_EXTRACTED = "extracted"
    }
}

/** 一个已索引文件的账（指纹 = [relPath] + [size] + [mtime]，增量更新用）。 */
data class KbDoc(
    val relPath: String,
    val size: Long,
    val mtime: Long,
    /** `text` / `docx` / `html`（解析方式；不认领的格式压根不进索引）。 */
    val kind: String,
    val chunks: List<KbChunk>,
)

/** 建索引过程的账（「已索引 N / 跳过 M」明示——静默跳过等于骗模型「就这些了」）。 */
data class KbStats(
    val filesIndexed: Int = 0,
    val filesSkipped: Int = 0,
    val chunksTotal: Int = 0,
    /** 达到文件数上限（[KbIndexer.MAX_FILES]）未列全。 */
    val truncated: Boolean = false,
    /** 明细：过大 / 二进制或读失败 / 格式不认领。 */
    val skippedBig: Int = 0,
    val skippedBinary: Int = 0,
    val skippedUnclaimed: Int = 0,
    /** 增量时指纹没变、chunks 整体留用的文件数。 */
    val reused: Int = 0,
)

/** 整份索引（可整份原子重写的缓存；损坏/缺失 ⇒ 当作没有，重建即可）。 */
data class KbIndex(
    val format: String = FORMAT,
    /** 被索引目录的绝对路径（增量比对用：root 变了旧索引不适用）。 */
    val root: String,
    /** 相对工作区根的展示名（引用与话术里给人看的）。 */
    val rootLabel: String,
    val updatedAt: Long,
    val docs: List<KbDoc>,
    val stats: KbStats = KbStats(),
) {
    companion object {
        /** 格式版本（变结构必须升号：旧号一律当损坏，走重建，不猜）。 */
        const val FORMAT = "kb-index-v1"
    }
}
