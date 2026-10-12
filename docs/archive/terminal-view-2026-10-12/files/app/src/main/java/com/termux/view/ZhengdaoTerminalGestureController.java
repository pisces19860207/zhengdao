/*
 * 证道第一方触摸、鼠标与滚动控制器。
 *
 * 复用 Android 手势识别和 emulator 的鼠标报告/alternate-buffer 语义，
 * 不调用旧 TerminalGestureController。
 */
package com.termux.view;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.OverScroller;

import com.termux.terminal.TerminalEmulator;

/** Owns touch gestures, terminal mouse reporting, scrollback and fling behavior. */
final class ZhengdaoTerminalGestureController
    implements GestureDetector.OnGestureListener,
    GestureDetector.OnDoubleTapListener,
    ScaleGestureDetector.OnScaleGestureListener {

    private static final int MAX_WHEEL_ROWS_PER_CALLBACK = 10;
    private final ZhengdaoTerminalView mView;
    private final GestureDetector mGestureDetector;
    private final ScaleGestureDetector mScaleDetector;
    private final OverScroller mScroller;
    private float mScrollRemainder;
    private float mScaleFactor = 1f;
    private int mLastScrollerY;
    private int mDownColumn = 1;
    private int mDownRow = 1;
    private long mMouseScrollDownTime = -1;
    private boolean mMouseTrackingAtFlingStart;

    ZhengdaoTerminalGestureController(ZhengdaoTerminalView view) {
        mView = view;
        final Context context = view.getContext();
        mGestureDetector = new GestureDetector(context, this);
        mGestureDetector.setOnDoubleTapListener(this);
        mScaleDetector = new ScaleGestureDetector(context, this);
        mScaleDetector.setQuickScaleEnabled(false);
        mScroller = new OverScroller(context);
    }

    boolean onTouchEvent(MotionEvent event) {
        if (mView.mEmulator == null) return true;
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mScrollRemainder = 0f;
            final int[] cell = mView.getGridCell(event.getX(), event.getY());
            mDownColumn = cell[0] + 1;
            mDownRow = cell[1] + 1;
            if (!mScroller.isFinished()) mScroller.abortAnimation();
        }

        if (mView.isSelectingText()) {
            mView.updateFloatingToolbarVisibility(event);
            mGestureDetector.onTouchEvent(event);
            mScaleDetector.onTouchEvent(event);
            return true;
        }

        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && handleMouseButton(event)) {
            return true;
        }

        mScaleDetector.onTouchEvent(event);
        mGestureDetector.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            mScrollRemainder = 0f;
        }
        return true;
    }

    boolean onGenericMotionEvent(MotionEvent event) {
        if (mView.mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.getAction() == MotionEvent.ACTION_SCROLL) {
            final boolean up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0f;
            scrollRows(event, up ? -3 : 3);
            return true;
        }
        return false;
    }

    void computeScroll() {
        if (mScroller.isFinished() || mView.mEmulator == null) return;
        if (!mScroller.computeScrollOffset()) return;
        final int currentY = mScroller.getCurrY();
        final int delta = currentY - mLastScrollerY;
        mLastScrollerY = currentY;
        if (mMouseTrackingAtFlingStart != mView.mEmulator.isMouseTrackingActive()) {
            mScroller.abortAnimation();
            return;
        }
        if (delta != 0) {
            final long now = android.os.SystemClock.uptimeMillis();
            final MotionEvent synthetic = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_MOVE, 0, 0, 0
            );
            try {
                scrollRows(synthetic, delta);
            } finally {
                synthetic.recycle();
            }
        }
        mView.postInvalidateOnAnimation();
    }

    void scrollByRows(int rowsDown) {
        final long now = android.os.SystemClock.uptimeMillis();
        final MotionEvent synthetic = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 0, 0, 0);
        try {
            scrollRows(synthetic, rowsDown);
        } finally {
            synthetic.recycle();
        }
    }

    @Override
    public boolean onDown(MotionEvent event) {
        return true;
    }

    @Override
    public void onShowPress(MotionEvent event) {}

    @Override
    public boolean onSingleTapUp(MotionEvent event) {
        return true;
    }

    @Override
    public boolean onSingleTapConfirmed(MotionEvent event) {
        if (mView.isSelectingText()) {
            mView.stopTextSelectionMode();
            return true;
        }
        if (mView.mEmulator != null && mView.mEmulator.isMouseTrackingActive()) {
            if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON, mDownColumn, mDownRow, true);
                sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON, mDownColumn, mDownRow, false);
            }
        } else {
            mView.requestFocus();
            mView.mClient.onSingleTapUp(event);
        }
        return true;
    }

    @Override
    public boolean onScroll(MotionEvent down, MotionEvent current, float distanceX, float distanceY) {
        if (mView.mEmulator == null) return true;
        if (mView.isSelectingText()) {
            mScrollRemainder = 0f;
            return true;
        }
        if (mView.mEmulator.isMouseTrackingActive()) {
            mScrollRemainder += distanceY;
            final int rows = (int) (mScrollRemainder / mView.mRenderer.mFontLineSpacing);
            mScrollRemainder -= rows * mView.mRenderer.mFontLineSpacing;
            sendWheelEvents(current, rows);
        } else if (current.isFromSource(InputDevice.SOURCE_MOUSE)) {
            sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, mDownColumn, mDownRow, true);
        } else {
            mScrollRemainder += distanceY;
            final int rows = (int) (mScrollRemainder / mView.mRenderer.mFontLineSpacing);
            mScrollRemainder -= rows * mView.mRenderer.mFontLineSpacing;
            scrollRows(current, rows);
        }
        return true;
    }

    @Override
    public void onLongPress(MotionEvent event) {
        if (!mScaleDetector.isInProgress() && !mView.isSelectingText() &&
            !mView.mClient.onLongPress(event)) {
            mView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            mView.startTextSelectionMode(event);
        }
    }

    @Override
    public boolean onFling(MotionEvent down, MotionEvent up, float velocityX, float velocityY) {
        if (mView.mEmulator == null) {
            return true;
        }
        if (!mScroller.isFinished()) return true;
        mMouseTrackingAtFlingStart = mView.mEmulator.isMouseTrackingActive();
        mLastScrollerY = mMouseTrackingAtFlingStart ? 0 : mView.mTopRow;
        final int minY = mMouseTrackingAtFlingStart
            ? -mView.mEmulator.mRows / 2
            : -mView.mEmulator.getScreen().getActiveTranscriptRows();
        final int maxY = mMouseTrackingAtFlingStart ? mView.mEmulator.mRows / 2 : 0;
        final int velocityRows = (int) (-velocityY / mView.mRenderer.mFontLineSpacing * 0.25f);
        mScroller.fling(0, mLastScrollerY, 0, velocityRows, 0, 0, minY, maxY);
        mView.postInvalidateOnAnimation();
        return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent event) {
        return false;
    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent event) {
        return false;
    }

    @Override
    public boolean onScale(ScaleGestureDetector detector) {
        if (mView.mEmulator == null || mView.isSelectingText()) return true;
        mScaleFactor = mView.mClient.onScale(mScaleFactor * detector.getScaleFactor());
        return true;
    }

    @Override
    public boolean onScaleBegin(ScaleGestureDetector detector) {
        return mView.mEmulator != null && !mView.isSelectingText();
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector detector) {}

    private void sendWheelEvents(MotionEvent event, int rows) {
        final int bounded = Math.max(-MAX_WHEEL_ROWS_PER_CALLBACK, Math.min(MAX_WHEEL_ROWS_PER_CALLBACK, rows));
        if (bounded == 0 || mView.mEmulator == null) return;
        final int button = bounded > 0
            ? TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
            : TerminalEmulator.MOUSE_WHEELUP_BUTTON;
        final int[] cell = mView.getGridCell(event.getX(), event.getY());
        int column = cell[0] + 1;
        int row = cell[1] + 1;
        if (mMouseScrollDownTime == event.getDownTime()) {
            column = mDownColumn;
            row = mDownRow;
        } else {
            mMouseScrollDownTime = event.getDownTime();
            mDownColumn = column;
            mDownRow = row;
        }
        for (int index = 0; index < Math.abs(bounded); index++) {
            sendMouse(button, column, row, true);
            sendMouse(button, column, row, false);
        }
    }

    private void scrollRows(MotionEvent event, int rowsDown) {
        if (mView.mEmulator == null) return;
        final boolean up = rowsDown < 0;
        final int amount = Math.abs(rowsDown);
        for (int index = 0; index < amount; index++) {
            if (mView.mEmulator.isMouseTrackingActive()) {
                if (index >= MAX_WHEEL_ROWS_PER_CALLBACK) break;
                final int[] cell = mView.getGridCell(event.getX(), event.getY());
                final int button = up
                    ? TerminalEmulator.MOUSE_WHEELUP_BUTTON
                    : TerminalEmulator.MOUSE_WHEELDOWN_BUTTON;
                sendMouse(button, cell[0] + 1, cell[1] + 1, true);
                sendMouse(button, cell[0] + 1, cell[1] + 1, false);
            } else if (mView.mEmulator.isAlternateBufferActive()) {
                mView.sendTerminalKeyCode(up ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, 0);
            } else {
                final int history = mView.mEmulator.getScreen().getActiveTranscriptRows();
                mView.setTopRow(Math.max(-history, Math.min(0, mView.mTopRow + (up ? -1 : 1))));
            }
        }
        mView.invalidate();
    }

    private void sendMouse(int button, int column, int row, boolean pressed) {
        if (mView.mEmulator != null) {
            mView.mEmulator.sendMouseEvent(button, Math.max(1, column), Math.max(1, row), pressed);
        }
    }

    private boolean handleMouseButton(MotionEvent event) {
        final int action = event.getActionMasked();
        final boolean buttonTransition = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP;
        final boolean secondary = event.isButtonPressed(MotionEvent.BUTTON_SECONDARY) ||
            (buttonTransition && event.getActionButton() == MotionEvent.BUTTON_SECONDARY);
        final boolean tertiary = event.isButtonPressed(MotionEvent.BUTTON_TERTIARY) ||
            (buttonTransition && event.getActionButton() == MotionEvent.BUTTON_TERTIARY);
        if (secondary) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) mView.showContextMenu();
            return true;
        }
        if (tertiary) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                final ClipboardManager clipboard =
                    (ClipboardManager) mView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                final ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0) {
                    final CharSequence text = clip.getItemAt(0).coerceToText(mView.getContext());
                    if (!android.text.TextUtils.isEmpty(text)) mView.mEmulator.paste(text.toString());
                }
            }
            return true;
        }
        if (!mView.mEmulator.isMouseTrackingActive()) return false;
        final int[] cell = mView.getGridCell(event.getX(), event.getY());
        final int[] buttonActions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP};
        for (int candidate : buttonActions) {
            if (event.getActionMasked() == candidate) {
                sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON, cell[0] + 1, cell[1] + 1,
                    candidate == MotionEvent.ACTION_DOWN);
                return true;
            }
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            sendMouse(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, cell[0] + 1, cell[1] + 1, true);
            return true;
        }
        return false;
    }

}
