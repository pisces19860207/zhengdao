// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
// 说明：证道第一方代码，沿用 com.termux.view 包名以便与聚合的终端视图层同包，非上游 Termux 文件。

package com.termux.view;

import android.view.ActionMode;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import com.termux.view.textselection.TextSelectionCursorController;

/**
 * 选区与剪贴板职责：持有并驱动 {@link TextSelectionCursorController}（选区手柄与 ActionMode），
 * 以及长按浮动工具条的显示/隐藏时机。
 *
 * <p>从 {@link TerminalView} 按职责搬出（P1-c，纯搬运、零行为变化）。{@code TerminalView} 侧保留
 * {@code isSelectingText()} / {@code getSelectedText()} / {@code getStoredSelectedText()} /
 * {@code unsetStoredSelectedText()} / {@code startTextSelectionMode(MotionEvent)} /
 * {@code stopTextSelectionMode()} / {@code updateFloatingToolbarVisibility(MotionEvent)} /
 * {@code hideFloatingToolbar()} 的同名同签名入口做一行转发，**公开 API 一字不改**。</p>
 */
final class TerminalSelectionController {

    private final TerminalView mView;

    private TextSelectionCursorController mCursorController;

    TerminalSelectionController(TerminalView view) {
        mView = view;
    }

    /**
     * Define functions required for text selection and its handles.
     */
    private TextSelectionCursorController getCursorController() {
        if (mCursorController == null) {
            mCursorController = new TextSelectionCursorController(mView);

            final ViewTreeObserver observer = mView.getViewTreeObserver();
            if (observer != null) {
                observer.addOnTouchModeChangeListener(mCursorController);
            }
        }

        return mCursorController;
    }

    private void showTextSelectionCursors(MotionEvent event) {
        getCursorController().show(event);
    }

    private boolean hideTextSelectionCursors() {
        return getCursorController().hide();
    }

    void renderTextSelection() {
        if (mCursorController != null)
            mCursorController.render();
    }

    /** 把当前选区写进 4 元素数组（无选区时保持调用方给的默认值）。 */
    void getSelectors(int[] sel) {
        if (mCursorController != null)
            mCursorController.getSelectors(sel);
    }

    boolean isSelectingText() {
        if (mCursorController != null) {
            return mCursorController.isActive();
        } else {
            return false;
        }
    }

    /** Get the currently selected text if selecting. */
    @Nullable
    String getSelectedText() {
        if (isSelectingText() && mCursorController != null)
            return mCursorController.getSelectedText();
        else
            return null;
    }

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    @Nullable
    String getStoredSelectedText() {
        return mCursorController != null ? mCursorController.getStoredSelectedText() : null;
    }

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    void unsetStoredSelectedText() {
        if (mCursorController != null) mCursorController.unsetStoredSelectedText();
    }

    @Nullable
    private ActionMode getTextSelectionActionMode() {
        if (mCursorController != null) {
            return mCursorController.getActionMode();
        } else {
            return null;
        }
    }

    void startTextSelectionMode(MotionEvent event) {
        if (!mView.requestFocus()) {
            return;
        }

        showTextSelectionCursors(event);
        mView.mClient.copyModeChanged(isSelectingText());

        mView.invalidate();
    }

    void stopTextSelectionMode() {
        if (hideTextSelectionCursors()) {
            mView.mClient.copyModeChanged(isSelectingText());
            mView.invalidate();
        }
    }

    void decrementYTextSelectionCursors(int decrement) {
        if (mCursorController != null) {
            mCursorController.decrementYTextSelectionCursors(decrement);
        }
    }

    /** View 被 attach 时补挂触摸模式监听（懒创建的控制器也要挂上）。 */
    void onViewAttached() {
        if (mCursorController != null) {
            mView.getViewTreeObserver().addOnTouchModeChangeListener(mCursorController);
        }
    }

    /** View 被 detach 时收摊：停选区、摘监听、通知控制器。 */
    void onViewDetached() {
        if (mCursorController != null) {
            // Might solve the following exception
            // android.view.WindowLeaked: Activity com.termux.app.TermuxActivity has leaked window android.widget.PopupWindow
            stopTextSelectionMode();

            mView.getViewTreeObserver().removeOnTouchModeChangeListener(mCursorController);
            mCursorController.onDetached();
        }
    }

    /**
     * Define functions required for long hold toolbar.
     */
    private final Runnable mShowFloatingToolbar = new Runnable() {
        @Override
        public void run() {
            if (getTextSelectionActionMode() != null) {
                getTextSelectionActionMode().hide(0);  // hide off.
            }
        }
    };

    private void showFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            int delay = ViewConfiguration.getDoubleTapTimeout();
            mView.postDelayed(mShowFloatingToolbar, delay);
        }
    }

    void hideFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            mView.removeCallbacks(mShowFloatingToolbar);
            getTextSelectionActionMode().hide(-1);
        }
    }

    void updateFloatingToolbarVisibility(MotionEvent event) {
        if (getTextSelectionActionMode() != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    hideFloatingToolbar();
                    break;
                case MotionEvent.ACTION_UP:  // fall through
                case MotionEvent.ACTION_CANCEL:
                    showFloatingToolbar();
            }
        }
    }
}
