/*
 * 证道第一方终端网格 Renderer。
 *
 * 模拟器、行数据与 ANSI 样式格式沿用项目现有终端核心；Canvas 绘制路径独立于
 * Termux TerminalRenderer，便于单独迭代证道的字符网格视觉。
 */
package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/** Draws the emulator's visible fixed-cell grid using the Zhengdao surface renderer. */
final class ZhengdaoTerminalRenderer {

    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint mTextRunPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint mCellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mGlyphBounds = new Rect();
    private final StringBuilder mCellText = new StringBuilder(8);
    private float mCellWidth;
    private float mLineHeight;
    private float mBaselineOffset;
    private Typeface mTypeface = Typeface.MONOSPACE;
    private int mTextSize;

    ZhengdaoTerminalRenderer() {
        mTextPaint.setTextAlign(Paint.Align.LEFT);
        mTextRunPaint.setTextAlign(Paint.Align.LEFT);
        mTextRunPaint.setFontFeatureSettings("'kern' 0, 'liga' 0, 'clig' 0");
    }

    void setMetrics(TerminalRenderer metrics) {
        mCellWidth = metrics.mFontWidth;
        mLineHeight = metrics.mFontLineSpacing;
        mBaselineOffset = metrics.mFontLineSpacingAndAscent;
        if (mTextSize != metrics.mTextSize || mTypeface != metrics.mTypeface) {
            mTextSize = metrics.mTextSize;
            mTypeface = metrics.mTypeface;
            mTextPaint.setTextSize(mTextSize);
            mTextPaint.setTypeface(mTypeface);
            mTextRunPaint.setTextSize(mTextSize);
            mTextRunPaint.setTypeface(mTypeface);
        }
    }

    void render(
        Canvas canvas,
        TerminalEmulator emulator,
        int topRow,
        int selectionY1,
        int selectionY2,
        int selectionX1,
        int selectionX2
    ) {
        if (mCellWidth <= 0 || mLineHeight <= 0) return;

        final TerminalBuffer screen = emulator.getScreen();
        final int rows = emulator.mRows;
        final int columns = emulator.mColumns;
        final int[] palette = emulator.mColors.mCurrentColors;
        final int cursorX = emulator.getCursorCol();
        final int cursorY = emulator.getCursorRow();
        final boolean cursorVisible = emulator.shouldCursorBeVisible();
        final boolean reverseVideo = emulator.isReverseVideo();
        final int cursorStyle = emulator.getCursorStyle();

        canvas.drawColor(
            palette[reverseVideo ? TextStyle.COLOR_INDEX_FOREGROUND : TextStyle.COLOR_INDEX_BACKGROUND],
            PorterDuff.Mode.SRC
        );

        for (int row = 0; row < rows; row++) {
            final int terminalRow = topRow + row;
            final TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(terminalRow));
            final float top = row * mLineHeight + mBaselineOffset;
            final float baseline = top + mLineHeight - mBaselineOffset;
            int charIndex = 0;
            int column = 0;
            int textRunEndColumn = -1;
            final int used = line.getSpaceUsed();

            while (column < columns) {
                final int cellStartCharIndex = charIndex;
                int codePoint;
                int charCount;
                if (charIndex < used) {
                    final char first = line.mText[charIndex];
                    charCount = Character.isHighSurrogate(first) && charIndex + 1 < used ? 2 : 1;
                    codePoint = charCount == 2
                        ? Character.toCodePoint(first, line.mText[charIndex + 1])
                        : first;
                } else {
                    codePoint = ' ';
                    charCount = 0;
                }

                int cellColumns = WcWidth.width(codePoint);
                if (cellColumns < 1) cellColumns = 1;
                cellColumns = Math.min(cellColumns, columns - column);
                final boolean simpleGlyph = charCount == 1 &&
                    codePoint >= 0x21 && codePoint <= 0x7e && cellColumns == 1;

                mCellText.setLength(0);
                mCellText.appendCodePoint(codePoint);
                charIndex += charCount;

                while (charIndex < used) {
                    final char next = line.mText[charIndex];
                    final int nextCount = Character.isHighSurrogate(next) && charIndex + 1 < used ? 2 : 1;
                    final int nextCodePoint = nextCount == 2
                        ? Character.toCodePoint(next, line.mText[charIndex + 1])
                        : next;
                    if (WcWidth.width(nextCodePoint) > 0) break;
                    mCellText.appendCodePoint(nextCodePoint);
                    charIndex += nextCount;
                }
                final boolean noCombiningMarks = charIndex == cellStartCharIndex + charCount;

                final long style = line.getStyle(column);
                final int effect = TextStyle.decodeEffect(style);
                final boolean selected = isSelected(
                    terminalRow,
                    column,
                    cellColumns,
                    selectionY1,
                    selectionY2,
                    selectionX1,
                    selectionX2
                );
                final boolean cursor = cursorVisible && terminalRow == cursorY &&
                    cursorX >= column && cursorX < column + cellColumns;
                int foreground = resolveColor(TextStyle.decodeForeColor(style), palette);
                int background = resolveColor(TextStyle.decodeBackColor(style), palette);
                if ((effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0) {
                    final int indexedForeground = TextStyle.decodeForeColor(style);
                    if (indexedForeground >= 0 && indexedForeground < 8) {
                        foreground = palette[indexedForeground + 8];
                    }
                }

                if (reverseVideo ^ ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0)) {
                    final int swap = foreground;
                    foreground = background;
                    background = swap;
                }

                if (selected) {
                    final int selectedBackground = palette[TextStyle.COLOR_INDEX_SELECTION];
                    if (selectedBackground != 0) {
                        background = selectedBackground;
                    } else {
                        final int swap = foreground;
                        foreground = background;
                        background = swap;
                    }
                }

                if (column >= textRunEndColumn &&
                    background != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
                    drawBackground(canvas, column, top, cellColumns, background);
                }
                if (cursor && cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                    final int cursorColor = palette[TextStyle.COLOR_INDEX_CURSOR];
                    drawBackground(canvas, column, top, cellColumns, cursorColor);
                    foreground = background;
                }

                if (column >= textRunEndColumn && simpleGlyph && noCombiningMarks &&
                    (effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
                    configureTextPaint(foreground, effect);
                    if (isBatchSafeGlyph(line.mText, cellStartCharIndex)) {
                        int runEndColumn = column + 1;
                        int nextCharIndex = cellStartCharIndex + 1;
                        while (runEndColumn < columns && nextCharIndex < used) {
                            final char nextChar = line.mText[nextCharIndex];
                            if (nextChar < 0x21 || nextChar > 0x7e) break;
                            final long nextStyle = line.getStyle(runEndColumn);
                            final boolean nextSelected = isSelected(
                                terminalRow,
                                runEndColumn,
                                1,
                                selectionY1,
                                selectionY2,
                                selectionX1,
                                selectionX2
                            );
                            final boolean nextCursor = cursorVisible && terminalRow == cursorY &&
                                cursorX == runEndColumn;
                            if (!TerminalRenderRunGrouper.canAppend(
                                style,
                                selected,
                                cursor,
                                cellColumns,
                                true,
                                nextStyle,
                                nextSelected,
                                nextCursor,
                                1,
                                true
                            ) || !isBatchSafeGlyph(line.mText, nextCharIndex)) {
                                break;
                            }
                            runEndColumn++;
                            nextCharIndex++;
                        }
                        if (runEndColumn > column + 1) {
                            textRunEndColumn = runEndColumn;
                            if (background != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
                                for (int backgroundColumn = column + 1;
                                     backgroundColumn < runEndColumn;
                                     backgroundColumn++) {
                                    drawBackground(canvas, backgroundColumn, top, 1, background);
                                }
                            }
                            final int runCharCount = runEndColumn - column;
                            canvas.drawTextRun(
                                line.mText,
                                cellStartCharIndex,
                                runCharCount,
                                cellStartCharIndex,
                                runCharCount,
                                column * mCellWidth,
                                baseline,
                                false,
                                mTextRunPaint
                            );
                        }
                    }
                }

                if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0 &&
                    codePoint != ' ' && column >= textRunEndColumn) {
                    configureTextPaint(foreground, effect);
                    final float x = column * mCellWidth;
                    final float expectedWidth = cellColumns * mCellWidth;
                    final float measuredWidth = mTextPaint.measureText(mCellText, 0, mCellText.length());
                    if (measuredWidth > 0f && Math.abs(measuredWidth - expectedWidth) > 0.01f) {
                        final int save = canvas.save();
                        canvas.scale(expectedWidth / measuredWidth, 1f, x, baseline);
                        canvas.drawText(mCellText, 0, mCellText.length(), x, baseline, mTextPaint);
                        canvas.restoreToCount(save);
                    } else {
                        canvas.drawText(mCellText, 0, mCellText.length(), x, baseline, mTextPaint);
                    }
                }

                if (cursor && cursorStyle != TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                    mCellPaint.setColor(palette[TextStyle.COLOR_INDEX_CURSOR]);
                    if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
                        canvas.drawRect(
                            column * mCellWidth,
                            top + mLineHeight * 0.75f,
                            (column + cellColumns) * mCellWidth,
                            top + mLineHeight,
                            mCellPaint
                        );
                    } else {
                        canvas.drawRect(
                            column * mCellWidth,
                            top,
                            column * mCellWidth + Math.max(1f, mCellWidth * 0.25f),
                            top + mLineHeight,
                            mCellPaint
                        );
                    }
                }

                if ((effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0) {
                    mCellPaint.setColor(foreground);
                    canvas.drawRect(
                        column * mCellWidth,
                        top + mLineHeight - Math.max(1f, mLineHeight * 0.07f),
                        (column + cellColumns) * mCellWidth,
                        top + mLineHeight,
                        mCellPaint
                    );
                }
                if ((effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0) {
                    mCellPaint.setColor(foreground);
                    canvas.drawRect(
                        column * mCellWidth,
                        top + mLineHeight * 0.55f,
                        (column + cellColumns) * mCellWidth,
                        top + mLineHeight * 0.59f,
                        mCellPaint
                    );
                }

                column += cellColumns;
            }
        }
    }

    private void configureTextPaint(int color, int effect) {
        final boolean bold =
            (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final float skew = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0 ? -0.35f : 0f;
        final int resolvedColor =
            (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0 ? dimColor(color) : color;
        configureTextPaint(mTextPaint, bold, skew, resolvedColor);
        configureTextPaint(mTextRunPaint, bold, skew, resolvedColor);
    }

    private static void configureTextPaint(Paint paint, boolean bold, float skew, int color) {
        paint.setFakeBoldText(bold);
        paint.setTextSkewX(skew);
        paint.setColor(color);
        paint.setAlpha(255);
    }

    private boolean isBatchSafeGlyph(char[] text, int index) {
        if (Math.abs(mTextPaint.measureText(text, index, 1) - mCellWidth) >= 0.01f) return false;
        mTextPaint.getTextBounds(text, index, 1, mGlyphBounds);
        return mGlyphBounds.left >= 1 && mGlyphBounds.right <= mCellWidth - 1f;
    }

    private static int dimColor(int color) {
        final int red = colorChannel(color, 16) * 2 / 3;
        final int green = colorChannel(color, 8) * 2 / 3;
        final int blue = colorChannel(color, 0) * 2 / 3;
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    private static int colorChannel(int color, int shift) {
        return (color >>> shift) & 0xff;
    }

    private void drawBackground(Canvas canvas, int column, float top, int width, int color) {
        mCellPaint.setColor(color);
        canvas.drawRect(
            column * mCellWidth,
            top,
            (column + width) * mCellWidth,
            top + mLineHeight,
            mCellPaint
        );
    }

    private static boolean isSelected(
        int row,
        int column,
        int cellColumns,
        int selectionY1,
        int selectionY2,
        int selectionX1,
        int selectionX2
    ) {
        if (selectionY1 < 0 || row < selectionY1 || row > selectionY2) return false;
        final int start = row == selectionY1 ? selectionX1 : 0;
        final int end = row == selectionY2 ? selectionX2 : Integer.MAX_VALUE;
        return column <= end && column + cellColumns - 1 >= start;
    }

    private static int resolveColor(int color, int[] palette) {
        if ((color & 0xff000000) == 0xff000000) return color;
        if (color < 0 || color >= palette.length) return palette[TextStyle.COLOR_INDEX_FOREGROUND];
        return palette[color];
    }
}
