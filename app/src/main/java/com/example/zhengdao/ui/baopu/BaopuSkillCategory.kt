// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第三十六批 §2 B 段（技能分类**旁挂**）＋ §0 那条格式风险判断：
//   技能正文头 `<!-- status=… uses=… -->` 是**抱朴自己会写**的唯一真相，
//   往里塞第三个字段 ⇒ 抱朴迟早写歪、数据层解析跟着崩。所以分类**不进 SKILL.md**，
//   走旁挂 JSON：`<技能目录>/_分类.json`，形如 `{ "做卡流程": "做卡" }`。
//
// 三条硬口径（都是判据）：
//   1. **只影响显示**（T5）：`SKILL.md` 一个字节都不动、索引行 `【技能】…` 那行的格式一个字不改、
//      人设里那套索引提案（PersonaRules/SkillStore）一概不碰。本文件唯一的写出口是 `_分类.json`
//      （由 `BaopuSkillCategoryTest` 按源码钉住「`writeText` 只出现一次且写的是分类文件」）。
//   2. **幂等**（T4）：编码按技能名**排序**输出，同一状态写两次字节必须一致——
//      所以这里不用 `HashMap` 的迭代顺序，也不塞时间戳。
//   3. **坏 JSON 当空表，不许抛**（T4 后半）：解析器只认「扁平 string→string 对象」这一种形状，
//      别的形状/转义不认识 ⇒ 返回空表并带 `corrupt=true`，由界面报一句。
//      抱朴或用户手改过这个文件是常态，不能让一个坏文件把设置页整页弄崩。
//   4. **不存在的技能名一律不写进去**（派工单原话）：写之前先问盘上有没有 `技能/<名>/SKILL.md`，
//      没有 ⇒ 拒绝。分类表因此不会积累孤儿键（技能删了、归档了，那条键会留在表里但**不再显示**——
//      显示只跟着索引行走，见设置页那一段）。
//
// 为什么自己写编解码而不引库：本批零新增依赖；且本地单测里 `android.jar` 是桩，
// `org.json.JSONObject` 的方法一调就抛 "not mocked" ⇒ 走平台 JSON 等于把这条判据钉不住。
//
// 中英：六档的**存盘值是中文**（派工单给的表就是中文），**显示值走 strings.xml**
// （`labelRes`）——切英文环境时看到英文标签，盘上仍是同一套中文键，不用迁移。
package com.example.zhengdao.ui.baopu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.zhengdao.R
import com.example.zhengdao.baopu.SkillRules
import java.io.File

/**
 * 技能分类的六档（顺序**固定**：显示分组的顺序、选单里的顺序都按这里）。
 *
 * @param jsonValue 存进 `_分类.json` 的值（派工单点名的中文六档）
 * @param labelRes 界面显示用的资源键（中英各一份，判据 T6）
 */
internal enum class SkillCategory(val jsonValue: String, val labelRes: Int) {
    MAKE_CARD("做卡", R.string.skill_cat_make_card),
    LOOKUP("查资料", R.string.skill_cat_lookup),
    READ_IMAGE("看图", R.string.skill_cat_read_image),
    EDIT_IMAGE("改图", R.string.skill_cat_edit_image),
    OTHER("其他", R.string.skill_cat_other),
    UNSET("未分类", R.string.skill_cat_unset),
    ;

    companion object {
        /** 认不出来的值 ⇒ [UNSET]（宁可显示成未分类，也不把一个坏值当成语义未知的分类）。 */
        fun ofJson(raw: String?): SkillCategory =
            values().firstOrNull { it.jsonValue == raw } ?: UNSET
    }
}

/**
 * 读出来的分类表。
 *
 * @param byName 技能名 → 档位（**只含盘上认得的值**；认不出的值按 [SkillCategory.UNSET] 处理）
 * @param corrupt 盘上那份读不通（没这个文件／空白 ⇒ false；坏 JSON ⇒ true）。
 *                界面按这条**报一句**，但照样把表当空的用（判据 T4）。
 */
internal data class SkillCategoryTable(
    val byName: Map<String, SkillCategory>,
    val corrupt: Boolean,
) {
    fun of(name: String): SkillCategory = byName[name] ?: SkillCategory.UNSET
}

/** 分类这一步的下场（同样只带事实，句子归界面）。 */
internal sealed interface CategoryOutcome {
    /** 写成了（只可能写到 `_分类.json`）。 */
    data class Saved(val name: String, val category: SkillCategory) : CategoryOutcome

    /** 不该写：技能名不在盘上（派工单「不存在的技能名一律不写进去」），或名字不合法。 */
    data class Rejected(val name: String, val why: String) : CategoryOutcome

    /** 盘上没做成（建目录／写文件失败），一个字都没落地。 */
    data class IoFailed(val name: String, val why: String) : CategoryOutcome
}

/** 这一层的技能目录（`<根>/技能`）——与数据层同一个口径，不在这里另拼。 */
internal fun skillDirOf(globalRoot: File, projectRoot: File, global: Boolean): File =
    if (global) SkillRules.globalSkillDir(globalRoot) else SkillRules.projectSkillDir(projectRoot)

/** 旁挂文件：`<技能目录>/_分类.json`（按层各一份：全局与项目互不串）。 */
internal fun categoryFileOf(skillDir: File): File = File(skillDir, "_分类.json")

/** 技能名在不在盘上（判据 T5 的旁证：认的是 `SKILL.md`，但**只读不写**）。 */
internal fun skillExists(skillDir: File, name: String): Boolean {
    if (name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
        return false
    }
    return File(File(skillDir, name), SkillRules.SKILL_FILE).isFile
}

/**
 * 编码（**排序输出 ⇒ 幂等**，判据 T4）。
 *
 * 两空格缩进、每行一键，方便用户在文件管理器里直接看懂、手改。
 * 值与键都做 JSON 字符串转义（`"` `\` 换行／回车／制表）；不认识的控制字符不写进来。
 */
internal fun encodeCategories(map: Map<String, SkillCategory>): String {
    val sb = StringBuilder()
    sb.append("{\n")
    val keys = map.keys.sorted()
    keys.forEachIndexed { i, key ->
        sb.append("  \"").append(escapeJsonString(key)).append("\": \"")
            .append(escapeJsonString(map.getValue(key).jsonValue)).append('"')
        if (i < keys.size - 1) sb.append(',')
        sb.append('\n')
    }
    sb.append("}\n")
    return sb.toString()
}

/**
 * 解码（**坏 JSON ⇒ 空表 + corrupt，绝不抛**，判据 T4 后半）。
 *
 * 只认这一种形状：`{ "键": "值", … }`，键与值都必须是字符串。
 * 数组、嵌套对象、数字、裸词、注释、重复的逗号、没引号的键——一律算坏。
 * 认不出的**值**不算坏文件：那一档当 [SkillCategory.UNSET]（少一档比整表报废好）。
 */
internal fun decodeCategories(text: String?): SkillCategoryTable {
    val src = text?.trim().orEmpty()
    if (src.isEmpty()) return SkillCategoryTable(emptyMap(), corrupt = false)
    val pairs = parseFlatObject(src) ?: return SkillCategoryTable(emptyMap(), corrupt = true)
    val map = LinkedHashMap<String, SkillCategory>()
    pairs.forEach { (k, v) -> if (k.isNotBlank()) map[k] = SkillCategory.ofJson(v) }
    return SkillCategoryTable(map, corrupt = false)
}

/** 现读盘（目录不存在／读不到 ⇒ 空表，不是错误）。 */
internal fun readCategories(skillDir: File): SkillCategoryTable =
    decodeCategories(runCatching { categoryFileOf(skillDir).readText(Charsets.UTF_8) }.getOrNull())

/**
 * 写一档分类（**唯一写出口**：`_分类.json`）。
 *
 * 先验技能名在不在盘上（不在 ⇒ [CategoryOutcome.Rejected]，一个字节都不写），
 * 再把现表读出来改掉那一条、排序写回。写失败 ⇒ [CategoryOutcome.IoFailed]。
 * 全程不碰 `SKILL.md`、不碰人设、不碰索引行（判据 T5）。
 */
internal fun setCategory(skillDir: File, name: String, category: SkillCategory): CategoryOutcome {
    if (!skillExists(skillDir, name)) return CategoryOutcome.Rejected(name, "不在盘上")
    val current = readCategories(skillDir)
    val next = current.byName.toMutableMap()
    next[name] = category
    val file = categoryFileOf(skillDir)
    val text = encodeCategories(next)
    val written = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }
    return if (written.isSuccess && runCatching { file.isFile }.getOrDefault(false)) {
        CategoryOutcome.Saved(name, category)
    } else {
        CategoryOutcome.IoFailed(name, written.exceptionOrNull()?.javaClass?.simpleName ?: "write")
    }
}

/** 一组（同一个分类下的行）。 */
internal data class CategoryGroup<T>(val category: SkillCategory, val rows: List<T>)

/**
 * 按分类**重排**（判据 T5 的「分组显示」落点；**稳定排序**：组内保持原顺序，
 * 索引行原本就是人设里的顺序，重排不许把同组两行换个头）。
 *
 * 只做界面重排：不改任何一条行的内容、不动 `SKILL.md`、不动索引行文本。
 * 六档顺序＝枚举声明顺序（做卡／查资料／看图／改图／其他／未分类，没分类的最后一档）。
 */
internal fun <T> sortByCategory(
    rows: List<T>,
    nameOf: (T) -> String,
    table: SkillCategoryTable,
): List<T> = rows.sortedBy { row -> SkillCategory.values().indexOf(table.of(nameOf(row))) }

/**
 * 把排好序的行切成「组标题＋行」（显示用）。
 *
 * 界面只在这一个地方判「什么时候补一行组标题」——判据 T5 要求分组**只影响显示**，
 * 所以这里回的 [CategoryGroup] 里装的就是原来那些行（引用相同，不是拷贝改写）。
 */
internal fun <T> categorize(
    rows: List<T>,
    nameOf: (T) -> String,
    table: SkillCategoryTable,
): List<CategoryGroup<T>> =
    SkillCategory.values().mapNotNull { cat ->
        val group = rows.filter { table.of(nameOf(it)) == cat }
        if (group.isEmpty()) null else CategoryGroup(cat, group)
    }

// ── 极简 JSON（只够 `_分类.json` 这一种形状）───────────────────────────────

/** 返回 null ＝ 这份不是「扁平字符串对象」，按坏 JSON 处理。 */
private fun parseFlatObject(src: String): Map<String, String>? {
    if (!src.startsWith("{") || !src.endsWith("}")) return null
    val end = src.length - 1                    // 末尾 '}' 的位置（不含）
    val out = LinkedHashMap<String, String>()
    var i = skipWs(src, 1)
    if (i >= end) return out                    // 空对象 {} ⇒ 空表，不算坏
    while (true) {
        val key = readJsonString(src, i, end) ?: return null
        i = skipWs(src, key.next)
        if (i >= end || src[i] != ':') return null
        i = skipWs(src, i + 1)
        val value = readJsonString(src, i, end) ?: return null
        out[key.text] = value.text
        i = skipWs(src, value.next)
        if (i >= end) return out
        if (src[i] != ',') return null
        i = skipWs(src, i + 1)
        if (i >= end) return null               // 尾随逗号 ⇒ 坏（不装作读懂了）
    }
}

private data class ReadStr(val text: String, val next: Int)

private fun skipWs(src: String, from: Int): Int {
    var i = from
    while (i < src.length && (src[i] == ' ' || src[i] == '\n' || src[i] == '\r' || src[i] == '\t')) i++
    return i
}

/** 读一个 JSON 字符串字面量（含转义）；不是字符串／没闭合／不认识转义 ⇒ null。 */
private fun readJsonString(src: String, from: Int, end: Int): ReadStr? {
    if (from >= end || src[from] != '"') return null
    val sb = StringBuilder()
    var i = from + 1
    while (i < end) {
        val c = src[i]
        if (c == '"') return ReadStr(sb.toString(), i + 1)
        if (c != '\\') {
            sb.append(c)
            i++
            continue
        }
        if (i + 1 >= end) return null
        val esc = src[i + 1]
        when (esc) {
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            else -> return null
        }
        i += 2
    }
    return null
}

private fun escapeJsonString(raw: String): String {
    val sb = StringBuilder()
    raw.forEach { c ->
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * 分类选单（六档一档一行，当前那档加粗）。
 *
 * 点一档就交回给调用方（界面在那里调 [setCategory] 并说话）——本函数**不碰盘**，
 * 所以它可以在 JVM 单测里被剥源码检查（T5：这里一条写盘语句都没有）。
 */
@Composable
internal fun SkillCategoryDialog(
    name: String,
    current: SkillCategory,
    onDismiss: () -> Unit,
    onPick: (SkillCategory) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.skill_cat_pick_title, name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                SkillCategory.values().forEach { cat ->
                    TextButton(onClick = { onPick(cat) }) {
                        Text(
                            text = stringResource(cat.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (cat == current) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (cat == current) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.skill_cat_close))
                }
            }
        }
    }
}
