// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百零九批 · novel_roster（读，R0）：角色树，只给结构不读正文。
// 角色文件：`角色/*.md`（# 标题层级 ⇒ 父子；「别名：」「族群：/门派：/组织：」行 ⇒ 别名/族群）
// 或 `角色/*.json5`（{characters:[{name,aliases?,group?,parent?}]} 或根数组）。
// 关系文件（*.rjson5）推断：kind∈{父亲,母亲,父,母,师父,师傅,师从,parent} ⇒ 父；
// kind∈{门派,族群,组织,帮派,势力,group} ⇒ 族群。显式字段优先于推断。
package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object NovelRosterTool {

    const val ARG_PATH = "path"

    private val PARENT_KINDS = setOf("父亲", "母亲", "父", "母", "师父", "师傅", "师从", "parent")
    private val GROUP_KINDS = setOf("门派", "族群", "组织", "帮派", "势力", "group")
    private val HEADING = Regex("^(#{1,6})\\s+(.+?)\\s*#*$")

    fun spec(workspaceRoot: () -> File): ToolSpec = ToolSpec(
        name = "novel_roster",
        description = "读小说角色树，返回 {name,aliases,group,parent?,children?} 列表（JSON）。" +
            "角色文件：$NOVEL_ROOT_DIR/<书名>/${NovelAssets.ROSTER_DIR}/ 或 资料库/${NovelAssets.ROSTER_DIR}/ 下的 " +
            "*.md（标题层级＝父子）与 *.json5；父子/族群再从关系文件（*.rjson5）推断。" +
            "path 省略＝全收。只读结构不读正文；坏文件报「第 N 行」。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_PATH to jsonObjectOf("type" to "string", "description" to "角色文件或目录（围栏内）；省略＝约定位置全收"),
            ),
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        readOnly = true,
        parallelSafe = true,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args -> read(workspaceRoot(), args.optString(ARG_PATH)) }

    /** 一个角色（同名合并：别名并集，group/parent 显式优先、先到先得）。 */
    internal class Chara(val name: String) {
        val aliases = linkedSetOf<String>()
        var group: String = ""
        var parent: String = ""
    }

    fun read(root: File, path: String): ToolOutcome {
        val files = when (val s = NovelAssets.scope(root, path, NovelAssets.ROSTER_DIR, ".md", ".json5")) {
            is NovelAssets.Scope.Bad -> return s.toOutcome()
            is NovelAssets.Scope.Files -> s.files
        }
        val chars = linkedMapOf<String, Chara>()
        for (f in files) {
            if (f.name.endsWith(".json5", ignoreCase = true)) {
                val (list, error) = jsonChars(f)
                if (error != null) return ToolOutcome.Failed(error)
                list.forEach { merge(chars, it) }
            } else {
                mdChars(f).forEach { merge(chars, it) }
            }
        }
        if (chars.isEmpty()) {
            return ToolOutcome.Failed("角色文件里没解析出角色（md 要有 # 标题；json5 要有 characters 数组或根数组）")
        }
        inferFromRelations(root, chars)?.let { return ToolOutcome.Failed(it) }
        return ToolOutcome.Ok("角色 ${chars.size} 人（${files.size} 个文件）：\n${outputJson(chars).toString(2)}")
    }

    private fun merge(chars: MutableMap<String, Chara>, c: Chara) {
        val old = chars[c.name]
        if (old == null) {
            chars[c.name] = c
            return
        }
        old.aliases += c.aliases
        if (old.group.isEmpty()) old.group = c.group
        if (old.parent.isEmpty()) old.parent = c.parent
    }

    /** Markdown：标题层级 ⇒ 父子（最近的较浅标题）；「别名：」「族群：/门派：/组织：」行挂到当前标题。 */
    private fun mdChars(f: File): List<Chara> {
        val text = runCatching { f.readText() }.getOrElse { return emptyList() }
        val out = mutableListOf<Chara>()
        val stack = mutableListOf<Pair<Int, String>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val m = HEADING.matchEntire(line)
            if (m != null) {
                val level = m.groupValues[1].length
                while (stack.isNotEmpty() && stack.last().first >= level) stack.removeAt(stack.size - 1)
                val c = Chara(m.groupValues[2].trim())
                c.parent = stack.lastOrNull()?.second.orEmpty()
                stack += level to c.name
                out += c
                continue
            }
            val cur = out.lastOrNull() ?: continue
            applyAttr(line, cur)
        }
        return out
    }

    /** 「别名：」「族群：/门派：/组织：」行挂到当前标题（全角/半角冒号都认）。 */
    private fun applyAttr(line: String, cur: Chara) {
        when {
            line.startsWith("别名：") || line.startsWith("别名:") ->
                cur.aliases += splitNames(line.removePrefix("别名：").removePrefix("别名:"))
            line.startsWith("族群：") || line.startsWith("族群:") ->
                cur.group = line.removePrefix("族群：").removePrefix("族群:").trim()
            line.startsWith("门派：") || line.startsWith("门派:") ->
                cur.group = line.removePrefix("门派：").removePrefix("门派:").trim()
            line.startsWith("组织：") || line.startsWith("组织:") ->
                cur.group = line.removePrefix("组织：").removePrefix("组织:").trim()
        }
    }

    private fun splitNames(s: String): List<String> =
        s.split('、', '，', ',', '/', '；', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /** JSON5 角色文件；坏文件/缺必填 ⇒ 错误带「文件名 第 N 行」。 */
    private fun jsonChars(f: File): Pair<List<Chara>, String?> {
        val loaded = NovelAssets.load(f)
        if (loaded is NovelAssets.Loaded.Bad) return emptyList<Chara>() to loaded.message
        val ok = loaded as NovelAssets.Loaded.Ok
        val arr = NovelAssets.rootArray(ok.root, "characters")
            ?: return emptyList<Chara>() to "${f.name} 第 ${ok.root.line} 行：根要是数组，或对象里带 characters 数组"
        val out = mutableListOf<Chara>()
        for (item in arr.items) {
            val o = NovelJson5.obj(item)
                ?: return emptyList<Chara>() to "${f.name} 第 ${item.line} 行：角色条目要是对象 {name,aliases?,group?,parent?}"
            val name = NovelJson5.str(o["name"])?.trim().orEmpty()
            if (name.isEmpty()) return emptyList<Chara>() to "${f.name} 第 ${item.line} 行：角色缺必填 name（非空字符串）"
            val c = Chara(name)
            val error = fillAliases(f, o, c)
            if (error != null) return emptyList<Chara>() to error
            c.group = NovelJson5.str(o["group"])?.trim().orEmpty()
            c.parent = NovelJson5.str(o["parent"])?.trim().orEmpty()
            out += c
        }
        return out to null
    }

    private fun fillAliases(f: File, o: NovelJson5.J5Obj, c: Chara): String? {
        when (val al = o["aliases"]) {
            null -> return null
            is NovelJson5.J5Arr -> al.items.forEach {
                NovelJson5.str(it)?.trim()?.takeIf { s -> s.isNotEmpty() }?.let { s -> c.aliases += s }
            }
            is NovelJson5.J5Str -> c.aliases += splitNames(al.value)
            else -> return "${f.name} 第 ${al.line} 行：aliases 要是数组或字符串"
        }
        return null
    }

    /** 关系推断（显式字段优先）；没有关系文件 ⇒ 不推断；坏关系文件 ⇒ 报行号。 */
    private fun inferFromRelations(root: File, chars: MutableMap<String, Chara>): String? {
        val s = NovelAssets.scope(root, "", NovelAssets.RELATIONS_DIR, NovelAssets.RELATIONS_EXT)
        if (s !is NovelAssets.Scope.Files) return null
        val byName = aliasIndex(chars)
        for (f in s.files) {
            val loaded = NovelAssets.load(f)
            if (loaded is NovelAssets.Loaded.Bad) return loaded.message
            val ok = loaded as NovelAssets.Loaded.Ok
            val arr = NovelAssets.rootArray(ok.root, "relations")
                ?: return "${f.name} 第 ${ok.root.line} 行：根要是数组，或对象里带 relations 数组"
            for (item in arr.items) {
                applyRelation(f, item, byName)?.let { return it }
            }
        }
        return null
    }

    /** 一条关系 ⇒ 推断（显式字段优先）；条目坏 ⇒ 错误带行号；from 不在角色里 ⇒ 跳过。 */
    private fun applyRelation(f: File, item: NovelJson5.J5Node, byName: Map<String, Chara>): String? {
        val o = NovelJson5.obj(item) ?: return "${f.name} 第 ${item.line} 行：关系条目要是对象"
        val from = NovelJson5.str(o["from"])?.trim().orEmpty()
        val to = NovelJson5.str(o["to"])?.trim().orEmpty()
        val kind = NovelJson5.str(o["kind"])?.trim().orEmpty()
        if (from.isEmpty() || to.isEmpty() || kind.isEmpty()) {
            return "${f.name} 第 ${item.line} 行：关系缺必填 from/to/kind"
        }
        val c = byName[from] ?: return null
        if (kind in PARENT_KINDS && c.parent.isEmpty()) c.parent = to
        if (kind in GROUP_KINDS && c.group.isEmpty()) c.group = to
        return null
    }

    /** 名字与别名都指向角色（别名撞名先到先得）。 */
    private fun aliasIndex(chars: Map<String, Chara>): Map<String, Chara> {
        val idx = linkedMapOf<String, Chara>()
        for (c in chars.values) {
            idx[c.name] = c
            for (a in c.aliases) idx.putIfAbsent(a, c)
        }
        return idx
    }

    private fun outputJson(chars: Map<String, Chara>): JSONArray {
        val byName = aliasIndex(chars)
        val kids = linkedMapOf<String, MutableList<String>>()
        for (c in chars.values) {
            val p = byName[c.parent] ?: continue
            kids.getOrPut(p.name) { mutableListOf() } += c.name
        }
        val out = JSONArray()
        for (c in chars.values) {
            val j = JSONObject().put("name", c.name).put("aliases", JSONArray(c.aliases.toList())).put("group", c.group)
            if (c.parent.isNotEmpty()) j.put("parent", c.parent)
            kids[c.name]?.let { j.put("children", JSONArray(it)) }
            out.put(j)
        }
        return out
    }
}