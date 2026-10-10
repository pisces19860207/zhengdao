package com.termux.view;

import android.os.Handler;
import android.os.Looper;

import com.termux.terminal.TerminalEmulator;

/**
 * 光标闪烁：定时器 + 重绘（P1-c 从 {@link TerminalView} 原样搬出，零行为变化）。
 *
 * <p>上游把它和其他五类职责混在同一个 1646 行的 {@link TerminalView} 里。搬家只做这些改动：
 * 用 {@code mView.xxx} 访问 View 的成员、把 {@code mTerminalCursorBlinkerXxx} 字段改成
 * {@code mHandler} / {@code mRunnable} / {@code mRate}。调度方式、日志文案、常量、判断顺序
 * 与搬家前逐字一致；{@link TerminalView} 侧的三个方法保留原签名做纯转发。
 *
 * <p>证道 P1-a（2026-10-10）在这条路径上的改动：闪烁时只重绘光标那一格
 * （{@link #invalidateCursorCell()}），并在停止时置空 Runnable 以断开
 * Handler → Runnable → View → Activity 的引用链。见 ERRATA E-086 / E-087、审计 🟡-5。
 */
final class TerminalCursorBlinker {

    private final TerminalView mView;

    /** 懒创建（上游同款）：只有真正要闪的时候才建 Handler。 */
    private Handler mHandler;
    private BlinkerRunnable mRunnable;
    private int mRate;

    TerminalCursorBlinker(TerminalView view) {
        mView = view;
    }

    /**
     * 会话切换时把新 emulator 交给正在跑的 Runnable（原 {@code TerminalView.updateSize()} 里的逻辑）。
     */
    void setEmulator(TerminalEmulator emulator) {
        if (mRunnable != null) mRunnable.setEmulator(emulator);
    }

    /**
     * Set terminal cursor blinker rate. It must be between {@link TerminalView#TERMINAL_CURSOR_BLINK_RATE_MIN}
     * and {@link TerminalView#TERMINAL_CURSOR_BLINK_RATE_MAX}, otherwise it will be disabled.
     *
     * The {@link #setState(boolean, boolean)} must be called after this
     * for changes to take effect if not disabling.
     *
     * @param blinkRate The value to set.
     * @return Returns {@code true} if setting blinker rate was successfully set, otherwise [@code false}.
     */
    synchronized boolean setRate(int blinkRate) {
        boolean result;

        // If cursor blinking rate is not valid
        if (blinkRate != 0 && (blinkRate < TerminalView.TERMINAL_CURSOR_BLINK_RATE_MIN || blinkRate > TerminalView.TERMINAL_CURSOR_BLINK_RATE_MAX)) {
            mView.mClient.logError(TerminalView.LOG_TAG, "The cursor blink rate must be in between " + TerminalView.TERMINAL_CURSOR_BLINK_RATE_MIN + "-" + TerminalView.TERMINAL_CURSOR_BLINK_RATE_MAX + ": " + blinkRate);
            mRate = 0;
            result = false;
        } else {
            mView.mClient.logVerbose(TerminalView.LOG_TAG, "Setting cursor blinker rate to " + blinkRate);
            mRate = blinkRate;
            result = true;
        }

        if (mRate == 0) {
            mView.mClient.logVerbose(TerminalView.LOG_TAG, "Cursor blinker disabled");
            stop();
        }

        return result;
    }

    /**
     * Sets whether cursor blinker should be started or stopped. Cursor blinker will only be
     * started if {@link #mRate} does not equal 0 and is between
     * {@link TerminalView#TERMINAL_CURSOR_BLINK_RATE_MIN} and {@link TerminalView#TERMINAL_CURSOR_BLINK_RATE_MAX}.
     *
     * This should be called when the view holding this activity is resumed or stopped so that
     * cursor blinker does not run when activity is not visible. If you call this on onResume()
     * to start cursor blinking, then ensure that {@link TerminalView#mEmulator} is set, otherwise wait for the
     * {@link TerminalViewClient#onEmulatorSet()} event after calling {@link TerminalView#attachSession(TerminalSession)}
     * for the first session added in the activity since blinking will not start if {@link TerminalView#mEmulator}
     * is not set, like if activity is started again after exiting it with double back press. Do not
     * call this directly after {@link TerminalView#attachSession(TerminalSession)} since {@link TerminalView#updateSize()}
     * may return without setting {@link TerminalView#mEmulator} since width/height may be 0. Its called again in
     * {@link TerminalView#onSizeChanged(int, int, int, int)}. Calling on onResume() if emulator is already set
     * is necessary, since onEmulatorSet() may not be called after activity is started after device
     * display timeout with double tap and not power button.
     *
     * It should also be called on the
     * {@link com.termux.terminal.TerminalSessionClient#onTerminalCursorStateChange(boolean)}
     * callback when cursor is enabled or disabled so that blinker is disabled if cursor is not
     * to be shown. It should also be checked if activity is visible if blinker is to be started
     * before calling this.
     *
     * It should also be called after terminal is reset with {@link TerminalSession#reset()} in case
     * cursor blinker was disabled before reset due to call to
     * {@link com.termux.terminal.TerminalSessionClient#onTerminalCursorStateChange(boolean)}.
     *
     * How cursor blinker starting works is by registering a {@link Runnable} with the looper of
     * the main thread of the app which when run, toggles the cursor blinking state and re-registers
     * itself to be called with the delay set by {@link #mRate}. When cursor
     * blinking needs to be disabled, we just cancel any callbacks registered. We don't run our own
     * "thread" and let the thread for the main looper do the work for us, whose usage is also
     * required to update the UI, since it also handles other calls to update the UI as well based
     * on a queue.
     *
     * Note that when moving cursor in text editors like nano, the cursor state is quickly
     * toggled `-> off -> on`, which would call this very quickly sequentially. So that if cursor
     * is moved 2 or more times quickly, like long hold on arrow keys, it would trigger
     * `-> off -> on -> off -> on -> ...`, and the "on" callback at index 2 is automatically
     * cancelled by next "off" callback at index 3 before getting a chance to be run. For this case
     * we log only if {@link TerminalView#TERMINAL_VIEW_KEY_LOGGING_ENABLED} is enabled, otherwise would clutter
     * the log. We don't start the blinking with a delay to immediately show cursor in case it was
     * previously not visible.
     *
     * @param start If cursor blinker should be started or stopped.
     * @param startOnlyIfCursorEnabled If set to {@code true}, then it will also be checked if the
     *                                 cursor is even enabled by {@link TerminalEmulator} before
     *                                 starting the cursor blinker.
     */
    synchronized void setState(boolean start, boolean startOnlyIfCursorEnabled) {
        // Stop any existing cursor blinker callbacks
        stop();

        if (mView.mEmulator == null) return;

        mView.mEmulator.setCursorBlinkingEnabled(false);

        if (start) {
            // If cursor blinker is not enabled or is not valid
            if (mRate < TerminalView.TERMINAL_CURSOR_BLINK_RATE_MIN || mRate > TerminalView.TERMINAL_CURSOR_BLINK_RATE_MAX)
                return;
            // If cursor blinder is to be started only if cursor is enabled
            else if (startOnlyIfCursorEnabled && ! mView.mEmulator.isCursorEnabled()) {
                if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                    mView.mClient.logVerbose(TerminalView.LOG_TAG, "Ignoring call to start cursor blinker since cursor is not enabled");
                return;
            }

            // Start cursor blinker runnable
            if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mView.mClient.logVerbose(TerminalView.LOG_TAG, "Starting cursor blinker with the blink rate " + mRate);
            if (mHandler == null)
                mHandler = new Handler(Looper.getMainLooper());
            mRunnable = new BlinkerRunnable(mView.mEmulator, mRate);
            mView.mEmulator.setCursorBlinkingEnabled(true);
            mRunnable.run();
        }
    }

    /**
     * Cancel the terminal cursor blinker callbacks
     */
    synchronized void stop() {
        if (mHandler != null && mRunnable != null) {
            if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mView.mClient.logVerbose(TerminalView.LOG_TAG, "Stopping cursor blinker");
            mHandler.removeCallbacks(mRunnable);
            // 证道 P1-a（2026-10-10）：断开 Handler -> Runnable -> TerminalView -> Activity 的引用链。
            mRunnable = null;
        }
    }

    /**
     * 证道 P1-a（2026-10-10）：光标闪烁只重绘光标所在的那一格。
     *
     * <p>背景：上游每 {@code mBlinkRate} 毫秒调用一次 {@code invalidate()}（整屏）。真机实测
     * （docs/证道-P0渲染层测量-2026-10-10.md）整屏重绘一帧的记录开销 = 2.3 ms（framestats 的 draw
     * 相位 p50），直接在 {@code onDraw} 里计时则是 2.5–19.8 ms（41 行 CJK，中位 ≈5 ms）；屏幕静止时
     * 每秒仍要白付约 1.7 帧（600 ms 闪烁率），而变化的只有光标那一格。见 ERRATA E-086 / 审计 🟡-5。
     *
     * <p>无法可靠定位光标时回退整屏 {@code invalidate()}：未启用光标 / 回滚了历史（{@code mTopRow != 0}）/
     * 行列越界 / 尺寸未就绪。宁可多画一屏，也不能留下残影。
     */
    private void invalidateCursorCell() {
        if (mView.mEmulator == null || mView.mRenderer == null) {
            mView.invalidate();
            return;
        }
        // 应用主动隐藏了光标（如 tmux 里跑全屏程序）时，这一帧根本不需要重绘。
        if (!mView.mEmulator.isCursorEnabled()) return;
        // 回滚历史时光标不在可见区，且此时内容随时可能整体滚动，直接整屏重绘。
        if (mView.mTopRow != 0) {
            mView.invalidate();
            return;
        }
        final int screenRow = mView.mEmulator.getCursorRow() - mView.mTopRow;
        final int cursorCol = mView.mEmulator.getCursorCol();
        if (screenRow < 0 || screenRow >= mView.mEmulator.mRows || cursorCol < 0 || cursorCol >= mView.mEmulator.mColumns) {
            mView.invalidate();
            return;
        }
        // 与 TerminalRenderer.render() 同一套度量：heightOffset 从 mFontLineSpacingAndAscent 起，
        // 每行先 += mFontLineSpacing，再以该 baseline 绘制此行。
        final int lineSpacing = mView.mRenderer.mFontLineSpacing;
        final float fontWidth = mView.mRenderer.mFontWidth;
        final float baseline = mView.mRenderer.mFontLineSpacingAndAscent + (screenRow + 1) * (float) lineSpacing;
        // 横向取到 (cursorCol .. cursorCol + 2) 两格：覆盖宽字符（CJK）与 BLOCK 反色块；
        // 纵向一整行上下各留 2 px，避免抗锯齿边缘残留。
        final int left = (int) Math.floor(cursorCol * fontWidth) - 1;
        final int right = (int) Math.ceil((cursorCol + 2) * fontWidth) + 1;
        final int top = (int) Math.floor(baseline - lineSpacing) - 2;
        final int bottom = (int) Math.ceil(baseline) + 2;
        mView.invalidate(left, top, right, bottom);
    }

    private final class BlinkerRunnable implements Runnable {

        private TerminalEmulator mEmulator;
        private final int mBlinkRate;

        // Initialize with false so that initial blink state is visible after toggling
        boolean mCursorVisible = false;

        BlinkerRunnable(TerminalEmulator emulator, int blinkRate) {
            mEmulator = emulator;
            mBlinkRate = blinkRate;
        }

        void setEmulator(TerminalEmulator emulator) {
            mEmulator = emulator;
        }

        public void run() {
            try {
                if (mEmulator != null) {
                    // Toggle the blink state and then invalidate() the view so
                    // that onDraw() is called, which then calls TerminalRenderer.render()
                    // which checks with TerminalEmulator.shouldCursorBeVisible() to decide whether
                    // to draw the cursor or not
                    mCursorVisible = !mCursorVisible;
                    //mView.mClient.logVerbose(TerminalView.LOG_TAG, "Toggling cursor blink state to " + mCursorVisible);
                    mEmulator.setCursorBlinkState(mCursorVisible);
                    // 证道 P1-a（2026-10-10）：只重绘光标所在的那一格，不再整屏 invalidate()。
                    invalidateCursorCell();
                }
            } finally {
                // Recall the Runnable after mBlinkRate milliseconds to toggle the blink state
                mHandler.postDelayed(this, mBlinkRate);
            }
        }
    }
}
