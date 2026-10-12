package com.termux.view;

/** Allocation-free run boundary decisions shared by the renderer and JVM tests. */
final class TerminalRenderRunGrouper {

    private TerminalRenderRunGrouper() {}

    static boolean canAppend(
        long runStyle,
        boolean runSelected,
        boolean runCursor,
        int runCellWidth,
        boolean runSimpleGlyph,
        long nextStyle,
        boolean nextSelected,
        boolean nextCursor,
        int nextCellWidth,
        boolean nextSimpleGlyph
    ) {
        return runStyle == nextStyle &&
            runSelected == nextSelected &&
            runCursor == nextCursor &&
            runCellWidth == 1 &&
            nextCellWidth == 1 &&
            runSimpleGlyph &&
            nextSimpleGlyph;
    }

    static int nextRunEnd(
        int start,
        int count,
        long[] styles,
        boolean[] selected,
        boolean[] cursor,
        int[] cellWidths,
        boolean[] simpleGlyphs
    ) {
        if (start < 0 || start >= count) throw new IndexOutOfBoundsException("start=" + start);
        int end = start + 1;
        while (end < count && canAppend(
            styles[start], selected[start], cursor[start], cellWidths[start], simpleGlyphs[start],
            styles[end], selected[end], cursor[end], cellWidths[end], simpleGlyphs[end]
        )) {
            end++;
        }
        return end;
    }
}
