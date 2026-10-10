package com.termux.view;

import android.graphics.Typeface;

/**
 * 尺寸与布局职责：把 View 的像素尺寸（以及字号/字体族变更）换算成终端的行列数，并驱动会话与 emulator 跟随。
 *
 * <p>从 {@link TerminalView} 按职责搬出（P1-c，纯搬运、零行为变化）。{@code TerminalView} 侧保留
 * {@code onSizeChanged(int,int,int,int)} / {@code updateSize()} / {@code setTextSize(int)} /
 * {@code setTypeface(Typeface)} 的同名同签名入口做一行转发，**公开 API 一字不改**。</p>
 */
final class TerminalSizeResolver {

    private final TerminalView mView;

    TerminalSizeResolver(TerminalView view) {
        mView = view;
    }

    /** @see TerminalView#onSizeChanged(int, int, int, int) */
    void onSizeChanged() {
        updateSize();
    }

    /** @see TerminalView#updateSize() */
    void updateSize() {
        final TerminalView view = mView;
        int viewWidth = view.getWidth();
        int viewHeight = view.getHeight();
        if (viewWidth == 0 || viewHeight == 0 || view.mTermSession == null) return;

        // Set to 80 and 24 if you want to enable vttest.
        int newColumns = Math.max(4, (int) (viewWidth / view.mRenderer.mFontWidth));
        int newRows = Math.max(4, (viewHeight - view.mRenderer.mFontLineSpacingAndAscent) / view.mRenderer.mFontLineSpacing);

        if (view.mEmulator == null || (newColumns != view.mEmulator.mColumns || newRows != view.mEmulator.mRows)) {
            view.mTermSession.updateSize(newColumns, newRows, (int) view.mRenderer.getFontWidth(), view.mRenderer.getFontLineSpacing());
            view.mEmulator = view.mTermSession.getEmulator();
            view.mClient.onEmulatorSet();

            // 尺寸变化后需要跟随新 emulator 的内部组件（目前是光标闪烁器）。
            view.refreshEmulatorDependents();

            // Restore cached top row value if session/emulator was switched back from a
            // different session or after activity restart. The top row value also needs to be
            // maintained after opening/closing soft keyboard.
            int topRow = 0;
            if (view.mEmulator != null) {
                int rowsInHistory = view.mEmulator.getScreen().getActiveTranscriptRows();
                int cachedTopRow = view.mEmulator.getTopRow();
                if (cachedTopRow >= -rowsInHistory) {
                    topRow = cachedTopRow;
                }
            }
            view.setTopRow(topRow);
            view.scrollTo(0, 0);
            view.invalidate();
        }
    }

    /** @see TerminalView#setTextSize(int) */
    void setTextSize(int textSize) {
        final TerminalView view = mView;
        view.mRenderer = new TerminalRenderer(textSize, view.mRenderer == null ? Typeface.MONOSPACE : view.mRenderer.mTypeface);
        updateSize();
    }

    /** @see TerminalView#setTypeface(Typeface) */
    void setTypeface(Typeface newTypeface) {
        final TerminalView view = mView;
        view.mRenderer = new TerminalRenderer(view.mRenderer.mTextSize, newTypeface);
        updateSize();
        view.invalidate();
    }
}
