// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：用户 2026-10-19 拍板的闭环四步（第三十七批）第一步；账见 docs/FEATURE-LEDGER.md §A #106。
package com.example.zhengdao.baopu

/**
 * B/C 层人设与技能索引的**保护闸**（第三十七批 A 段）。
 *
 * 为什么需要它（这是真实漏洞，不是防君子）
 *    C 层人设 `<工作区>/抱朴记忆/人设-本项目.md` **就在工作区里**，而 `write_file` 本就能写
 *    工作区里的任何文件 —— 模型只要调一次 `write_file` 把路径指过去，就能**绕过提案、
 *    绕过备份、绕过留痕**直接改人设。技能索引的 `【技能】…` 行也写在这同一个文件里，
 *    等于顺带把技能索引也绕过去了。目前唯一挡住它的只有「模型没想起来这么干」。
 *    所以闸必须落在**工具动手之前**，且是纯函数（无 I/O，JVM 直测）。
 *
 * 保护什么（按代码真身，不是按派工单的字面写法）
 *    - C 项目层：`<工作区>/抱朴记忆/人设-本项目.md`（父目录可以是 `抱朴记忆`，也允许工作区根直放）；
 *    - B 全局层：`<App 私有>/抱朴记忆/全局/人设.md`（工具本来够不到私有目录，但**显式拒**，
 *      不靠「路径不在工作区」这种隐式兜底——哪天私有目录挪进工作区，隐式兜底会静默失效）；
 *    - 上述文件的**任何变体写法**：`./`、`a/../`、反斜杠、重复斜杠、盘符/绝对路径、大小写差异。
 *
 * 怎么判（不许用字符串前缀比较）
 *    先[normalize]成段序列（`\`→`/`、丢 `.`、回退 `..`、`//` 合并、盘符当作普通段），
 *    再**逐段精确比较**：文件名必须命中、父目录必须在允许集合内（或没有父目录＝工作区根）。
 *    用 `startsWith` 这类前缀比较会两头错：`抱朴记忆2/人设-本项目.md` 这种同前缀兄弟被误拦，
 *    而 `./抱朴记忆/…` 又拦不住（判据 L2 的负例就是这两条）。
 *
 * 判据
 *    L1：四个写类工具（write_file / edit_file / move_path 的源与目标 / delete_path）
 *        撞上受保护路径 ⇒ 全部 Rejected，话术与 [REJECT_PREFIX] 一字不差；
 *    L2：变体路径一个都绕不过去。
 */
object PersonaGuard {

    /**
     * 拒绝话术的前缀（**固定口径，不许改**）。完整话术 = 本前缀 + 调用方传进来的相对路径。
     * 放在这里而不是四个工具各写一份，是为了让「一字不差」只有一个真相。
     */
    const val REJECT_PREFIX = "人设与技能索引只能走提案或设置页，工具不许直接写："

    /** 受保护的文件名（小写比对 —— 大小写差异也别想绕）。 */
    private val PROTECTED_NAMES: Set<String> = setOf(
        PersonaFiles.FILE_PERSONA_PROJECT.lowercase(), // 人设-本项目.md（C 层，含【技能】索引行）
        PersonaFiles.FILE_PERSONA.lowercase(), // 人设.md（B 层）
    )

    /**
     * 允许的**直接父目录**名（小写）。人设只在 `抱朴记忆/` 下（`全局/` 是 B 层的真身一层），
     * 其余位置（例如 `技能/`、`备份/`、同前缀的 `抱朴记忆2/`）的同名文件**不拦** ——
     * 保护的是人设，不是所有叫这个名字的文件。
     */
    private val ALLOWED_PARENTS: Set<String> = setOf("抱朴记忆", "全局")

    /**
     * 命中保护 ⇒ 返回**完整的固定话术**（调用方直接当 `ToolOutcome.Rejected` 的 error 用）；
     * 没命中 ⇒ null（放行）。
     *
     * @param relPath 工具拿到的路径（相对工作区，也可以是绝对路径／带变体写法）
     */
    fun blocked(relPath: String): String? {
        val raw = relPath.trim()
        if (raw.isEmpty()) return null
        val segs = normalize(raw) ?: return null // 越出根 ⇒ 交给既有的越界判据，这里不抢
        if (segs.isEmpty()) return null
        // 第一百六十五批：人设包正文也只许走设置页/导入 —— 模型拿 write_file 直接改 persona.md，
        // 就绕过了大小/编码/坏 JSON 的导入判据（虽然包内容本身改不了权限，仍属「人设只能走提案或设置页」）。
        if (isPersonaPackPath(segs)) return REJECT_PREFIX + raw
        val name = segs.last().lowercase()
        if (name !in PROTECTED_NAMES) return null
        val parent = segs.getOrNull(segs.size - 2)?.lowercase()
        if (parent != null && parent !in ALLOWED_PARENTS) return null
        return REJECT_PREFIX + raw
    }

    /**
     * 人设包正文：`<任意前缀>/抱朴记忆/人设/<包名>/persona.md`。
     * 只认「抱朴记忆」下面紧挨着「人设」的那一棵；同名的 persona.md 在别处不拦。
     */
    private fun isPersonaPackPath(segs: List<String>): Boolean {
        val lower = segs.map { it.lowercase() }
        if (lower.lastOrNull() != "persona.md") return false
        val root = lower.indexOf("抱朴记忆")
        if (root < 0) return false
        if (lower.getOrNull(root + 1) != "人设") return false
        return lower.size >= root + 2
    }

    /**
     * 路径归一：反斜杠转正斜杠、丢掉 `.`、回退 `..`、合并重复斜杠、盘符/前导斜杠当普通段吃掉。
     *
     * @return 段序列；`..` 多到越出根 ⇒ null（越界，不归本闸管）
     */
    fun normalize(raw: String): List<String>? {
        val out = mutableListOf<String>()
        for (part in raw.replace('\\', '/').split('/')) {
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> {
                    if (out.isEmpty()) return null
                    out.removeAt(out.lastIndex)
                }
                else -> out += part
            }
        }
        return out
    }
}
