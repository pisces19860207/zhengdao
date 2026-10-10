package com.termux.view;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Scroller;

import com.termux.terminal.TerminalEmulator;

/**
 * 手势与滚动职责：持有 {@link GestureAndScaleRecognizer}（触摸手势识别，本类作为它的
 * {@link GestureAndScaleRecognizer.Listener}）与 {@link Scroller}（惯性滚动），并负责把触摸滑动 /
 * 鼠标滚轮换算成终端的滚轮事件或本地回滚缓冲滚动。
 *
 * <p>从 {@link TerminalView} 按职责搬出（P1-c，纯搬运、零行为变化；例外是 P1-d 审计 🟡-6 的滚轮
 * 行数上限，见 {@link #MAX_WHEEL_ROWS}）。{@code TerminalView} 侧保留同名同签名入口做一行转发：
 * {@code onTouchEvent(MotionEvent)}、{@code onGenericMotionEvent(MotionEvent)}、
 * {@code doScroll(MotionEvent, int)}（后者还被 {@code TerminalImeBridge} 调用）。</p>
 */
final class TerminalGestureController implements GestureAndScaleRecognizer.Listener {

    private final TerminalView mView;

    final GestureAndScaleRecognizer mGestureRecognizer;
    final Scroller mScroller;

    /** What was left in from scrolling movement. */
    float mScrollRemainder;

    float mScaleFactor = 1.f;

    /** Keep track of where mouse touch event started which we report as mouse scroll. */
    private int mMouseScrollStartX = -1, mMouseScrollStartY = -1;
    /** Keep track of the time when a touch event leading to sending mouse scroll events started. */
    private long mMouseStartDownTime = -1;

    /** 手指滑动过的标志：用于抑制"鼠标跟踪时把抬手当点击上报"。 */
    private boolean mScrolledWithFinger;

    /**
     * 证道 P1-d 🟡-6（2026-10-10）：**一次手势**最多向 guest 转发多少行滚轮事件。
     *
     * <p>上限取 10 的理由（不是 5，也不是 20）：</p>
     * <ol>
     *   <li>真机一次全屏甩动最多折算约 50–60 行（屏高 2848 px ÷ 行高 ≈45 px），不设上限时
     *       一次手势就会向 guest 连发上百条滚轮事件（每行 = 按下 + 抬起两条），每条都要走
     *       pty 写入 → tmux 处理 → 回显渲染；</li>
     *   <li>10 行 = 每次手势最多 20 条事件，属于「一帧内的瞬时批量」，而不是持续洪峰；</li>
     *   <li>取 5 会把中等力度的正常滑动也截断（手感上像"滚不动"），取 20 则只砍掉极端长甩、
     *       对最坏情况几乎没有约束 —— 而极端长甩正是这里要防的。</li>
     * </ol>
     *
     * <p>注意：只限制**发出去的事件条数**，{@code mScrollRemainder} 仍按真实行数结算，
     * 因此不会因截断而累积滚动漂移。</p>
     */
    private static final int MAX_WHEEL_ROWS = 10;

    TerminalGestureController(TerminalView view) {
        mView = view;
        final Context context = view.getContext();
        mGestureRecognizer = new GestureAndScaleRecognizer(context, this);
        mScroller = new Scroller(context);
    }

    @Override
    public boolean onUp(MotionEvent event) {
        mScrollRemainder = 0.0f;
        if (mView.mEmulator != null && mView.mEmulator.isMouseTrackingActive() && !event.isFromSource(InputDevice.SOURCE_MOUSE) && !mView.isSelectingText() && !mScrolledWithFinger) {
            // Quick event processing when mouse tracking is active - do not wait for check of double tapping
            // for zooming.
            sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, true);
            sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, false);
            return true;
        }
        mScrolledWithFinger = false;
        return false;
    }

    @Override
    public boolean onSingleTapUp(MotionEvent event) {
        if (mView.mEmulator == null) return true;

        if (mView.isSelectingText()) {
            mView.stopTextSelectionMode();
            return true;
        }
        mView.requestFocus();
        mView.mClient.onSingleTapUp(event);
        return true;
    }

    @Override
    public boolean onScroll(MotionEvent e, float distanceX, float distanceY) {
        if (mView.mEmulator == null) return true;

        // ── 证道定制（2026-10-05 真机实测）：让触摸滑动在全屏应用下也能滚动历史 ──
        // 现象：终端里单指上滑没有反应。
        // 根因：tmux / 全屏 TUI 采用"定位光标 + 重绘整屏"的方式输出，不产生行滚动，
        //      所以本地回滚缓冲恒为空（实测 getActiveTranscriptRows() == 0），
        //      下面 doScroll() 那套本地滚动永远滚不动。
        //      而原代码只在"事件来自鼠标源"时才转发滚轮，手机上触摸永远不是鼠标源，
        //      于是滑动被彻底丢弃。
        // 修法：应用若启用了鼠标跟踪（tmux 需 `set -g mouse on`，已由 ProotLauncher
        //      预置到 ~/.tmux.conf），就把触摸滑动按行高换算成滚轮事件转发给应用，
        //      由应用滚动它自己的历史缓冲。
        if (mView.mEmulator.isMouseTrackingActive()) {
            distanceY += mScrollRemainder;
            int wheelRows = (int) (distanceY / mView.mRenderer.mFontLineSpacing);
            mScrollRemainder = distanceY - wheelRows * mView.mRenderer.mFontLineSpacing;
            if (wheelRows != 0) {
                // 证道 P1-d 🟡-6：只给"发出去的事件条数"封顶，remainder 仍按真实行数结算。
                int clampedRows = Math.max(-MAX_WHEEL_ROWS, Math.min(MAX_WHEEL_ROWS, wheelRows));
                // 手指上滑（clampedRows > 0）= 想看更新的内容 = 滚轮向下
                int button = clampedRows > 0
                        ? TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
                        : TerminalEmulator.MOUSE_WHEELUP_BUTTON;
                for (int i = 0; i < Math.abs(clampedRows); i++) {
                    sendMouseEventCode(e, button, true);
                    sendMouseEventCode(e, button, false);
                }
            }
            mScrolledWithFinger = true;
            return true;
        }

        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            // If moving with mouse pointer while pressing button, report that instead of scroll.
            // This means that we never report moving with button press-events for touch input,
            // since we cannot just start sending these events without a starting press event,
            // which we do not do for touch input, only mouse in onTouchEvent().
            sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true);
        } else {
            mScrolledWithFinger = true;
            distanceY += mScrollRemainder;
            int deltaRows = (int) (distanceY / mView.mRenderer.mFontLineSpacing);
            mScrollRemainder = distanceY - deltaRows * mView.mRenderer.mFontLineSpacing;
            doScroll(e, deltaRows);
        }
        return true;
    }

    @Override
    public boolean onScale(float focusX, float focusY, float scale) {
        if (mView.mEmulator == null || mView.isSelectingText()) return true;
        mScaleFactor *= scale;
        mScaleFactor = mView.mClient.onScale(mScaleFactor);
        return true;
    }

    @Override
    public boolean onFling(final MotionEvent e2, float velocityX, float velocityY) {
        if (mView.mEmulator == null) return true;
        // Do not start scrolling until last fling has been taken care of:
        if (!mScroller.isFinished()) return true;

        final boolean mouseTrackingAtStartOfFling = mView.mEmulator.isMouseTrackingActive();
        float SCALE = 0.25f;
        if (mouseTrackingAtStartOfFling) {
            mScroller.fling(0, 0, 0, -(int) (velocityY * SCALE), 0, 0, -mView.mEmulator.mRows / 2, mView.mEmulator.mRows / 2);
        } else {
            mScroller.fling(0, mView.mTopRow, 0, -(int) (velocityY * SCALE), 0, 0, -mView.mEmulator.getScreen().getActiveTranscriptRows(), 0);
        }

        mView.post(new Runnable() {
            private int mLastY = 0;

            @Override
            public void run() {
                if (mouseTrackingAtStartOfFling != mView.mEmulator.isMouseTrackingActive()) {
                    mScroller.abortAnimation();
                    return;
                }
                if (mScroller.isFinished()) return;
                boolean more = mScroller.computeScrollOffset();
                int newY = mScroller.getCurrY();
                int diff = mouseTrackingAtStartOfFling ? (newY - mLastY) : (newY - mView.mTopRow);
                doScroll(e2, diff);
                mLastY = newY;
                if (more) mView.post(this);
            }
        });

        return true;
    }

    @Override
    public boolean onDown(float x, float y) {
        // Why is true not returned here?
        // https://developer.android.com/training/gestures/detector.html#detect-a-subset-of-supported-gestures
        // Although setting this to true still does not solve the following errors when long pressing in terminal view text area
        // ViewDragHelper: Ignoring pointerId=0 because ACTION_DOWN was not received for this pointer before ACTION_MOVE
        // Commenting out the call to mGestureDetector.onTouchEvent(event) in GestureAndScaleRecognizer#onTouchEvent() removes
        // the error logging, so issue is related to GestureDetector
        return false;
    }

    @Override
    public boolean onDoubleTap(MotionEvent event) {
        // Do not treat is as a single confirmed tap - it may be followed by zoom.
        return false;
    }

    @Override
    public void onLongPress(MotionEvent event) {
        if (mGestureRecognizer.isInProgress()) return;
        if (mView.mClient.onLongPress(event)) return;
        if (!mView.isSelectingText()) {
            mView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            mView.startTextSelectionMode(event);
        }
    }

    /** Send a single mouse event code to the terminal. */
    void sendMouseEventCode(MotionEvent e, int button, boolean pressed) {
        int[] columnAndRow = mView.getColumnAndRow(e, false);
        int x = columnAndRow[0] + 1;
        int y = columnAndRow[1] + 1;
        if (pressed && (button == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON || button == TerminalEmulator.MOUSE_WHEELUP_BUTTON)) {
            if (mMouseStartDownTime == e.getDownTime()) {
                x = mMouseScrollStartX;
                y = mMouseScrollStartY;
            } else {
                mMouseStartDownTime = e.getDownTime();
                mMouseScrollStartX = x;
                mMouseScrollStartY = y;
            }
        }
        mView.mEmulator.sendMouseEvent(button, x, y, pressed);
    }

    /** Perform a scroll, either from dragging the screen or by scrolling a mouse wheel. */
    void doScroll(MotionEvent event, int rowsDown) {
        boolean up = rowsDown < 0;
        int amount = Math.abs(rowsDown);
        for (int i = 0; i < amount; i++) {
            if (mView.mEmulator.isMouseTrackingActive()) {
                sendMouseEventCode(event, up ? TerminalEmulator.MOUSE_WHEELUP_BUTTON : TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, true);
            } else if (mView.mEmulator.isAlternateBufferActive()) {
                // Send up and down key events for scrolling, which is what some terminals do to make scroll work in
                // e.g. less, which shifts to the alt screen without mouse handling.
                mView.handleKeyCode(up ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, 0);
            } else {
                mView.setTopRow(Math.min(0, Math.max(-(mView.mEmulator.getScreen().getActiveTranscriptRows()), mView.mTopRow + (up ? -1 : 1))));
                if (!mView.awakenScrollBarsSuper()) mView.invalidate();
            }
        }
    }

    /** Overriding {@link android.view.View#onGenericMotionEvent(MotionEvent)}. */
    boolean onGenericMotionEvent(MotionEvent event) {
        if (mView.mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE) && event.getAction() == MotionEvent.ACTION_SCROLL) {
            // Handle mouse wheel scrolling.
            boolean up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0.0f;
            doScroll(event, up ? -3 : 3);
            return true;
        }
        return false;
    }

    /** Overriding {@link android.view.View#onTouchEvent(MotionEvent)}. */
    boolean onTouchEvent(MotionEvent event) {
        if (mView.mEmulator == null) return true;
        final int action = event.getAction();

        if (mView.isSelectingText()) {
            mView.updateFloatingToolbarVisibility(event);
            mGestureRecognizer.onTouchEvent(event);
            return true;
        } else if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (action == MotionEvent.ACTION_DOWN) mView.showContextMenu();
                return true;
            } else if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                ClipboardManager clipboardManager = (ClipboardManager) mView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clipData = clipboardManager.getPrimaryClip();
                if (clipData != null) {
                    ClipData.Item clipItem = clipData.getItemAt(0);
                    if (clipItem != null) {
                        CharSequence text = clipItem.coerceToText(mView.getContext());
                        if (!TextUtils.isEmpty(text)) mView.mEmulator.paste(text.toString());
                    }
                }
            } else if (mView.mEmulator.isMouseTrackingActive()) { // BUTTON_PRIMARY.
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_UP:
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, event.getAction() == MotionEvent.ACTION_DOWN);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true);
                        break;
                }
            }
        }

        mGestureRecognizer.onTouchEvent(event);
        return true;
    }
}
