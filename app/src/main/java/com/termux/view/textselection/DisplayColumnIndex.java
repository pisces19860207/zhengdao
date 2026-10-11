// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
// 说明：证道第一方代码，沿用 com.termux.view.textselection 包名以便与聚合的终端选词层同包，非上游 Termux 文件。

package com.termux.view.textselection;

import com.termux.terminal.WcWidth;

/**
 * 显示列 ↔ 字符索引的**单一判据**（B-补-47②，2026-10-11 抽出）。
 *
 * <p>终端是按显示列排版的：宽字符（CJK）占两列、组合字符与不可见字符占零列、代理对（emoji）占两列。
 * 选词手柄、选区端点却都按**列**给坐标，于是「列 → 合法游标列」这一步在选词链路上被反复调用
 * （{@code TextSelectionCursorController.getValidCurX}，两侧手柄各一次）。上游把这段判据与
 * {@code com.termux.terminal.TerminalRow#findStartOfColumn(int)}（整行 char[] 版）各写了一份，
 * 两份必须同源同判据，否则列与字符索引来回换算会漂。</p>
 *
 * <p><b>与上游那份内联实现的唯一差别</b>：上游遇到零宽字符（组合字符、ZWJ、变体选择符）时
 * 无条件 {@code return col}，于是只要这一行里出现过零宽字符，无论手柄在哪一列都会被夹回该字符的
 * 起始列（旧行为，读码推得：{@code "a\u0301b"} 上第 2 列也算成第 1 列）⇒ 选区末端少一列、手柄往左跳。
 * 本类改成「只有手柄**确实**停在该字符起始列时才返回它」，其余列继续往后走。零宽字符本身不占列，
 * 这条修正不改变任何"边界列"的结果，只修掉被夹回去的那部分（单测 {@code DisplayColumnIndexTest}
 * 把新旧两种取值都写成了断言）。</p>
 *
 * <p>纯计算、不碰 Android 视图与终端状态，因此可被 JVM 单测直接钉住
 * （宽字符内部、零宽字符、代理对、行尾、越界列都有断言）。</p>
 */
public final class DisplayColumnIndex {

    private DisplayColumnIndex() {
    }

    /**
     * 把「手柄所在的列」换算成**合法游标列**：
     * <ul>
     *   <li>列正好落在某字符的起始列 ⇒ 返回该列（零宽字符也走这条，不会被推到它之后）；</li>
     *   <li>列落在宽字符内部（只占一半）⇒ 返回该字符**右侧**的列，手柄不会停在半个字上；</li>
     *   <li>列超出本行已打印内容 ⇒ 原样返回（调用方自己夹取）。</li>
     * </ul>
     *
     * @param line   该行已打印的文本（调用方负责按需要截取或整行传入）
     * @param column 手柄所在的显示列（从 0 起）
     * @return 合法的游标显示列
     */
    public static int validCursorColumn(CharSequence line, int column) {
        if (line == null || line.length() == 0) return column;

        int col = 0;
        for (int i = 0, len = line.length(); i < len; ) {
            char ch1 = line.charAt(i);
            if (ch1 == 0) break;

            int wc;
            if (Character.isHighSurrogate(ch1) && i + 1 < len) {
                char ch2 = line.charAt(i + 1);
                wc = WcWidth.width(Character.toCodePoint(ch1, ch2));
                i += 2;
            } else {
                wc = WcWidth.width(ch1);
                i += 1;
            }

            if (column == col) {
                // 手柄就停在这个字符的起始列上：停这儿。零宽字符（wc == 0）也走这条，
                // 而不是像上游那样"碰到零宽字符就无条件回退"。
                return col;
            }

            final int cend = col + wc;
            if (column > col && column < cend) {
                // 落在宽字符内部：贴到它右侧边界。
                return cend;
            }
            col = cend;
        }
        return column;
    }
}
