// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十六批（2026-11-05）：批量形参的**共享判据**。
//
// 起因（用户口径「一次问清，不要逐项问」）：模型删 10 个文件就是 10 次 delete_path、
// 10 张确认卡、点 10 次批准；撞上步数上限还会停在一半。本文件把三件工具
// （delete_path / move_path / copy_path）的 `paths` 收成**一处**：取参、二选一闸、
// 逐项结局、汇总回执。判据只有一份，就不会慢慢漂成「删按批、搬按个」两套语义。
//
// 三条纪律：
// 1. **不改风险档**：批量不是「更省事的快捷键」——三件工具仍是 CONFIRM（每次都弹卡），
//    变的只是「一张卡问清 N 个」。卡没了＝风险降了，那是另一件事，不在本文件里做。
// 2. **原子只在拒绝那一侧**：用户点拒绝 ⇒ 管线根本不会执行，一个都不动（免费）；
//    批准 ⇒ 逐项做，一项失败不影响其余（部分成功如实报数）——
//    「删一半就停」不是原子，是半夜的残局。
// 3. **不编数**：大小只 stat 不读正文；读不到按 0；失败的项照原样列出来，不吞不并。
package com.example.zhengdao.baopu.agent

import java.io.File
import java.util.Locale
import org.json.JSONObject

/**
 * 批量形参的**唯一口径**（`delete_path` / `move_path` / `copy_path` 共用）。
 *
 * 为什么不各自实现一遍：三件的「怎么取 paths、怎么算总大小、怎么折叠卡面、怎么汇总回执」
 * 是同一个问题；各写一遍的下场是「删的回执写 9 个成功、搬的回执写 9 个失败」这种
 * 数字对不上账的分叉，而用户只看卡面。
 */
internal object PathBatch {

    /** 批量形参名（三件工具共用同一个名字：模型学一次就够）。 */
    const val ARG_PATHS = "paths"

    /**
     * 卡面逐条列出的上限：超过就**不逐条列**，只报「共 N 项」。
     *
     * 为什么是 20（不是 50）：确认卡是标题不是日志——30 行逐条清单会把「拒绝」按钮
     * 挤出屏幕，用户反而更难做决定；20 项以内一屏能看完，超过时「数量＋总大小」才是
     * 人真正要看的那两格。
     */
    const val PREVIEW_MAX = 20

    /**
     * 一项的账（回执一行）。
     *
     * [line] 是**已经成句**的那一句（含路径），不是路径＋原因的拼接：
     * 逐项文案的唯一来源是各工具自己的回执，本对象只负责去掉第二句以后的细节
     * （回执是清单，不是日志）。
     */
    data class Item(val ok: Boolean, val line: String)

    /**
     * `paths` → 非空、去重、保序的路径清单。
     *
     * 去重口径：**按原串**去重（同一个文件写两遍是同一件事，不是两次动作）；
     * 不做规范化比较——`a.txt` 与 `./a.txt` 在盘上是一个文件，但把它们合并会让
     * 「模型给了两个不同写法」这件事从账上消失，宁可让第二项如实报「不存在」。
     * 空数组/没给 ⇒ 空清单（调用方据此回「至少给一个」）。
     */
    fun of(args: JSONObject): List<String> {
        val arr = args.optJSONArray(ARG_PATHS) ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val one = arr.optString(i).trim()
            if (one.isNotEmpty()) out += one
        }
        return out.toList()
    }

    /**
     * 二选一闸（466 判据④）：单个 `path` 与批量 `paths` **同时给 ⇒ 拒绝**。
     *
     * 为什么不猜哪个优先：两个写法表达的是两件事（一个文件 / 一批文件）。猜错的代价
     * 是不对称的——把批量当单个，用户以为只删一个，实际动了十个；反之则少做一半。
     * 拒掉让模型重说一遍，比替用户决定动几个安全得多。
     *
     * @param singleKey 该工具的单个路径参数名（`path` / `from`）
     * @return null = 通过；非 null = 给模型的拒绝原文
     */
    fun bothGiven(args: JSONObject, singleKey: String): String? {
        val hasSingle = args.optString(singleKey).trim().isNotEmpty()
        if (!hasSingle || of(args).isEmpty()) return null
        return "参数「$singleKey」与「$ARG_PATHS」只能给一个（同时给会被拒：猜哪个优先等于替你决定动几个文件）。" +
            "动一个文件用 $singleKey，动一批用 $ARG_PATHS。"
    }

    /** 一项的字节数（不是文件/读不到 ⇒ 0；只 stat，不读正文——卡面说真话但不读内容）。 */
    fun bytesOf(raw: String, root: File?): Long {
        val f = if (root == null || File(raw).isAbsolute) File(raw) else File(root, raw)
        return runCatching { if (f.isFile) f.length() else 0L }.getOrDefault(0L)
    }

    /** 一批的总字节（缺失项按 0 计——总大小是给眼睛的量级，不是审计账）。 */
    fun totalOf(paths: List<String>, root: File?): Long = paths.sumOf { bytesOf(it, root) }

    /** 人话大小（B / KB / MB 三档；与卡面、回执是同一个函数）。 */
    fun sizeText(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    }

    /**
     * 卡面上的逐项清单（466 判据②）：≤ [PREVIEW_MAX] 项 ⇒ 每项一行（相对路径）；
     * 超过 ⇒ 折叠成一行「共 N 项」（见 [PREVIEW_MAX] 的理由）。
     */
    fun linesOf(paths: List<String>, root: File?): String {
        if (paths.size > PREVIEW_MAX) {
            return "· 共 ${paths.size} 项（超过 $PREVIEW_MAX 项，不逐条列出；批准后逐个照做，回执逐项报）"
        }
        return paths.joinToString("\n") { "· " + display(it, root) }
    }

    /** 逐项结局（工具回执 → 回执行）：只取**第一句**，去掉后面的操作建议。 */
    fun item(result: ToolOutcome): Item = when (result) {
        is ToolOutcome.Ok -> Item(ok = true, line = result.text.substringBefore("。"))
        is ToolOutcome.Failed -> Item(ok = false, line = result.error.substringBefore("。"))
        is ToolOutcome.Rejected -> Item(ok = false, line = result.reason.substringBefore("。"))
    }

    /**
     * 汇总回执（批量形参的**唯一形状**）：成功 N ／ 失败 M ＋ 逐项一行。
     *
     * 全失败 ⇒ `Failed`（一次都没成，拿 Ok 假装成功是最坏的那种谎）；只要有成功 ⇒ `Ok`，
     * 失败项照样逐条列出来——「删了 9 个、1 个没有」这句话必须让模型看见，
     * 否则它会对着用户说「都删好了」。
     */
    fun outcome(verb: String, items: List<Item>): ToolOutcome {
        val done = items.count { it.ok }
        val bad = items.size - done
        val head = "已$verb $done 个文件" +
            if (bad == 0) "" else "，$bad 个没成（其余照做，没有中断）"
        val body = items.joinToString("\n") { (if (it.ok) "✔ " else "✘ ") + it.line }
        val text = "$head\n$body"
        return if (done == 0) ToolOutcome.Failed(text) else ToolOutcome.Ok(text)
    }

    /** 卡面显示口径：给了根就剥成相对（绝对路径又长又泄目录结构）。 */
    private fun display(raw: String, root: File?): String =
        if (root == null) raw else RunRules.relativizePath(raw, root)
}
