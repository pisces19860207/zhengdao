package com.termux.view;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class TerminalRenderRunGrouperTest {

    @Test
    public void groupsCellsWithIdenticalStyleAndState() {
        assertRuns(
            new long[]{1, 1, 1, 1},
            new boolean[4],
            new boolean[4],
            new int[]{1, 1, 1, 1},
            new boolean[]{true, true, true, true},
            4
        );
    }

    @Test
    public void splitsAtStyleChanges() {
        assertRuns(
            new long[]{1, 1, 2, 2, 1},
            new boolean[5],
            new boolean[5],
            new int[]{1, 1, 1, 1, 1},
            new boolean[]{true, true, true, true, true},
            2, 4, 5
        );
    }

    @Test
    public void isolatesWideAndCombiningGlyphs() {
        assertRuns(
            new long[]{1, 1, 1, 1},
            new boolean[4],
            new boolean[4],
            new int[]{1, 2, 1, 1},
            new boolean[]{true, false, true, false},
            1, 2, 3, 4
        );
    }

    @Test
    public void splitsAtSelectionBoundaries() {
        assertRuns(
            new long[]{1, 1, 1, 1},
            new boolean[]{false, false, true, true},
            new boolean[4],
            new int[]{1, 1, 1, 1},
            new boolean[]{true, true, true, true},
            2, 4
        );
    }

    @Test
    public void isolatesCursorCellFromAdjacentCells() {
        assertRuns(
            new long[]{1, 1, 1},
            new boolean[3],
            new boolean[]{false, true, false},
            new int[]{1, 1, 1},
            new boolean[]{true, true, true},
            1, 2, 3
        );
    }

    private static void assertRuns(
        long[] styles,
        boolean[] selected,
        boolean[] cursor,
        int[] cellWidths,
        boolean[] simpleGlyphs,
        int... expectedEnds
    ) {
        final List<Integer> ends = new ArrayList<>();
        int start = 0;
        while (start < styles.length) {
            final int end = TerminalRenderRunGrouper.nextRunEnd(
                start, styles.length, styles, selected, cursor, cellWidths, simpleGlyphs
            );
            ends.add(end);
            start = end;
        }
        assertEquals(ends.toString(), expectedEnds.length, ends.size());
        for (int index = 0; index < expectedEnds.length; index++) {
            assertEquals("run " + index, expectedEnds[index], (int) ends.get(index));
        }
    }
}
