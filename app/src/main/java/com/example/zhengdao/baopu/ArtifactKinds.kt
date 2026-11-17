// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百八十五批（产物类型收口）· **唯一的一张表**。
//
// ## 病（681 如实交回的那一条）
//
// 「产物台账收了 jpg／webp，点开却说『这类文件不能用其他应用打开』」。
// 根因不是少两支 MIME：681 把台账准入门槛从「八个文本后缀」放宽到三族时，
// **准入名单**（`TEXT/DOC/IMAGE_ARTIFACT_EXTENSIONS`）和**打开映射**
// （641 在 `ArtifactOpen.mimeOf` 里那个 `when`）各自维护一份——图片族里
// png 两边都有，jpg／webp 只有准入那一半有 ⇒ 登记了、点开打不开。
//
// ## 治法
//
// 一张表（[ArtifactKinds.TABLE]），两处都读它：
//   ① 台账准入（[artifactFamilyOf] → [familyOf]）；
//   ② 「用其他应用打开」（[ArtifactOpen.mimeOf] → [openMimeOf]）。
// 641 那三支 `when` 并进表里，全仓不再有第二份「扩展名 → MIME」。
//
// ## 一条硬规则（④）
//
// **表里没有 mime 的扩展名，一律不登记**——宁可不进台账，也不许「登记了却点不开」
// （那正是 681 用户看到的那句提示）。这条落在 [routeOf]：文档／图片族没有 mime
// 就没有去处 ⇒ 准入为 false。文本族的去处是**内置只读查看器**（38 批那一份），
// 不需要外部 MIME，所以它照样有 mime（「这东西是什么类型」），只是不给外部应用用。
//
// ## 启动自检（⑤）
//
// [selfCheck] 把「台账准入 ⊆ 打开映射」写成可执行断言，[ZhengdaoApp] 冷启时跑一次。
package com.example.zhengdao.baopu

import java.util.Locale

/**
 * 产物三族（台账只认这三种；族外一律不进台账）。
 *
 * 三族各有一条后果，界面上必须分得开：
 * - [Text] 进只读查看器（可内嵌预览）；
 * - [Doc]、[Image] **不做内置预览**，点开＝「用其他应用打开」。
 */
enum class ArtifactFamily { Text, Doc, Image }

/**
 * 一种产物在「登记」与「打开」两侧的全部事实（**唯一真源的一行**）。
 *
 * @param family 点开去哪：[ArtifactFamily.Text] ⇒ 内置查看器；其余 ⇒ 交给别的应用
 * @param mime   这东西是什么类型（同时也是交给别的应用时用的类型）
 */
internal data class ArtifactKind(val family: ArtifactFamily, val mime: String)

/**
 * 点开这一行去哪一层（[ArtifactKinds.routeOf] 的返回值）。
 *
 * 只有两种去处，多一种都说明表里出现了没有去处的行：
 * - [InAppPreview]：文本族，交给 38 批的只读查看器；
 * - [ExternalApp]：文档／图片族，带着 MIME 发 ACTION_VIEW。
 */
internal sealed interface ArtifactOpenRoute {
    /** 内置只读查看器（不离开本应用）。 */
    data object InAppPreview : ArtifactOpenRoute

    /** 交给系统里能接这个 MIME 的应用。 */
    data class ExternalApp(val mime: String) : ArtifactOpenRoute
}

/**
 * 扩展名 → 族 ＋ MIME 的**唯一一张表**（685）。
 *
 * 为什么键是小写无点的扩展名：全仓取扩展名只有 [artifactExtension] 一处，
 * 它已经统一转小写、剥目录、跳过点开头/结尾，这里不重复做第二套解析。
 */
internal object ArtifactKinds {

    // ── MIME 常量（对外只有这一处；641 的 `ArtifactOpen.MIME_*` 是它的别名）──────

    const val MIME_DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val MIME_PNG = "image/png"
    const val MIME_JPEG = "image/jpeg"
    const val MIME_WEBP = "image/webp"

    private const val MIME_JSON = "application/json"
    private const val MIME_TEXT = "text/plain"
    private const val MIME_MARKDOWN = "text/markdown"
    private const val MIME_CSV = "text/csv"
    private const val MIME_YAML = "application/yaml"
    private const val MIME_HTML = "text/html"

    /**
     * **唯一的一张表**。加一行 = 同时登记「进台账」与「点得开」，两边不会再各管一半。
     *
     * 文本族八个后缀与 38 批白名单逐字一致（用户原话「其他的太重了」）；
     * 文档族两支是 681 定的；图片族四支是 685 补齐的（png 早就有，jpg／jpeg／webp 新加，
     * jpeg 与 jpg 同一类型、两条键 —— 手机相册里两种写法都常见）。
     */
    val TABLE: Map<String, ArtifactKind> = linkedMapOf(
        "json" to ArtifactKind(ArtifactFamily.Text, MIME_JSON),
        "txt" to ArtifactKind(ArtifactFamily.Text, MIME_TEXT),
        "md" to ArtifactKind(ArtifactFamily.Text, MIME_MARKDOWN),
        "csv" to ArtifactKind(ArtifactFamily.Text, MIME_CSV),
        "yml" to ArtifactKind(ArtifactFamily.Text, MIME_YAML),
        "yaml" to ArtifactKind(ArtifactFamily.Text, MIME_YAML),
        "log" to ArtifactKind(ArtifactFamily.Text, MIME_TEXT),
        "html" to ArtifactKind(ArtifactFamily.Text, MIME_HTML),
        "docx" to ArtifactKind(ArtifactFamily.Doc, MIME_DOCX),
        "xlsx" to ArtifactKind(ArtifactFamily.Doc, MIME_XLSX),
        "png" to ArtifactKind(ArtifactFamily.Image, MIME_PNG),
        "jpg" to ArtifactKind(ArtifactFamily.Image, MIME_JPEG),
        "jpeg" to ArtifactKind(ArtifactFamily.Image, MIME_JPEG),
        "webp" to ArtifactKind(ArtifactFamily.Image, MIME_WEBP),
    )

    // ── 查表 ──────────────────────────────────────────────────────────────

    /** 扩展名（小写无点）→ 那一行；表外 ⇒ null。 */
    fun kindOf(ext: String): ArtifactKind? = TABLE[ext.lowercase(Locale.ROOT)]

    /** 路径 → 那一行（`/` 与 `\` 都当分隔符，大小写不敏感）。 */
    fun kindOfPath(relPath: String): ArtifactKind? = kindOf(artifactExtension(relPath))

    /**
     * 点开这一行去哪（**唯一的分流判据**）。null ⇒ 没去处 ⇒ 不许登记（④）。
     *
     * 文本族的去处是内置查看器，所以不需要 mime 也能算出路线；文档／图片族
     * **只有**外部应用这一条路，mime 空着就等于「点开打不开」⇒ null。
     */
    fun routeOf(kind: ArtifactKind?): ArtifactOpenRoute? {
        if (kind == null) return null
        return when (kind.family) {
            ArtifactFamily.Text -> ArtifactOpenRoute.InAppPreview
            ArtifactFamily.Doc, ArtifactFamily.Image ->
                kind.mime.takeIf { it.isNotBlank() }?.let { ArtifactOpenRoute.ExternalApp(it) }
        }
    }

    /** 扩展名 → 点开去哪（表外 / 没去处 ⇒ null）。 */
    fun routeOf(ext: String): ArtifactOpenRoute? = routeOf(kindOf(ext))

    /**
     * 台账认不认这个扩展名（④）：**表里有、且点得开**。
     *
     * 这一句就是 681 那类事故的唯一防线——以前准入只看「族名单里有没有」，
     * 于是 jpg 进得了台账、点开却是「不支持」。现在准入直接问「点得开吗」。
     */
    fun isAdmitted(ext: String): Boolean = routeOf(ext) != null

    /** 台账准入的族（表外 / 没去处 ⇒ null；[artifactFamilyOf] 只转这一道）。 */
    fun familyOf(ext: String): ArtifactFamily? = kindOf(ext)?.takeIf { routeOf(it) != null }?.family

    /**
     * 「用其他应用打开」交给别人的类型；null ⇒ 这一击不该发 Intent（641 的 Unsupported）。
     *
     * 文本族虽然表里有 mime，但它的去处是内置查看器，**不走外部打开**——
     * 这是路由规则（族决定去处），不是第二份映射。
     */
    fun openMimeOf(relPath: String): String? =
        (routeOf(kindOfPath(relPath)) as? ArtifactOpenRoute.ExternalApp)?.mime

    /** 某一族的全部扩展名（三份老名单都是它的派生，不再各写一份）。 */
    fun extensionsIn(family: ArtifactFamily): Set<String> =
        TABLE.entries.filter { it.value.family == family }.map { it.key }.toCollection(LinkedHashSet())

    // ── 自检（⑤）──────────────────────────────────────────────────────────

    /** 台账会登记的那批（准入）。 */
    fun admittedIn(table: Map<String, ArtifactKind>): Set<String> =
        table.keys.filter { routeOf(table[it]) != null }.toCollection(LinkedHashSet())

    /** 打开映射能交出类型的那批（mime 非空）。 */
    fun openableIn(table: Map<String, ArtifactKind>): Set<String> =
        table.keys.filter { !table.getValue(it).mime.isBlank() }.toCollection(LinkedHashSet())

    /**
     * 启动自检（**纯函数**，返回违规清单；空 = 通过）。
     *
     * 四条，每条都对着一种「改表改漏了」：
     * 1. 扩展名写法不规范（大写 / 带点 / 空）⇒ [kindOf] 永远查不到它；
     * 2. **表里这一行没有 mime** ⇒ 登记了也打不开（④ 的病灶本身）；
     * 3. 族与 mime 前缀不一致（图片族却给了 `text/plain`）⇒ 发出去没人接；
     * 4. **台账准入 ⊄ 打开映射**（⑤ 的那条不变式）。
     *
     * 为什么返回值而不是直接抛：表是编译期常量，真被改漏了也不该让用户进不了 App；
     * 冷启处记一条 error 日志留痕，单测里断言它为空——两边各管一半。
     */
    fun selfCheck(table: Map<String, ArtifactKind> = TABLE): List<String> {
        val problems = ArrayList<String>()
        table.forEach { (ext, kind) ->
            if (ext.isBlank() || ext != ext.lowercase(Locale.ROOT) || ext.startsWith(".")) {
                problems += "扩展名写法不规范：$ext"
            }
            if (kind.mime.isBlank()) {
                problems += "表里这一行没有 mime：$ext（不许登记后打不开）"
            } else if (!mimeMatchesFamily(kind)) {
                problems += "族与 mime 不一致：$ext → ${kind.family}/${kind.mime}"
            }
        }
        val holes = admittedIn(table) - openableIn(table)
        if (holes.isNotEmpty()) {
            problems += "台账准入里有打开映射查不到的：${holes.sorted()}"
        }
        return problems
    }

    /** 族与 mime 前缀是否自洽（自检第 3 条）。 */
    private fun mimeMatchesFamily(kind: ArtifactKind): Boolean = when (kind.family) {
        ArtifactFamily.Image -> kind.mime.startsWith("image/")
        ArtifactFamily.Doc -> kind.mime.startsWith("application/vnd.")
        ArtifactFamily.Text ->
            kind.mime.startsWith("text/") || kind.mime == MIME_JSON || kind.mime == MIME_YAML
    }
}
