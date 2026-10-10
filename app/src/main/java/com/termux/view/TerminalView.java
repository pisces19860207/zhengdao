package com.termux.view;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.Build;
import android.util.AttributeSet;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.textselection.TextSelectionCursorController;

/** View displaying and interacting with a {@link TerminalSession}. */
public final class TerminalView extends View {

    /** Log terminal view key and IME events. 只能经 {@link #setIsTerminalViewKeyLoggingEnabled(boolean)} 改。 */
    private static boolean sKeyLoggingEnabled = false;

    /**
     * @return 是否正在记录终端按键与 IME 事件。
     *
     * <p>P1-c 接口收窄：搬家后的职责类（{@link TerminalImeBridge} / {@link TerminalCursorBlinker}）
     * 读开关走这个包内访问器，开关本身不再对包内可写。</p>
     */
    static boolean isKeyLoggingEnabled() {
        return sKeyLoggingEnabled;
    }

    /** The currently displayed terminal session, whose emulator is {@link #mEmulator}. */
    public TerminalSession mTermSession;
    /** Our terminal emulator whose session is {@link #mTermSession}. */
    public TerminalEmulator mEmulator;

    public TerminalRenderer mRenderer;

    public TerminalViewClient mClient;

    /**
     * 证道定制（输入回归页，2026-10-09）：IME 事件观察者。
     *
     * <p>只**观察**不改行为——实现里除了回调不碰任何状态；为 {@code null} 时（默认，也是生产
     * 终端的常态）连一次判断都不产生额外开销。回归页把它挂上，用来记录输入法真实送进来的
     * composition / commit / delete 事件流，作为「中文组合输入是否丢字/重复上屏」的现场证据。
     */
    public interface ImeProbeObserver {
        void onImeEvent(String kind, String text);
    }

    public ImeProbeObserver mImeProbeObserver;

    /** 选区与剪贴板（P1-c 从本类搬出的职责类，见 {@link TerminalSelectionController}）。 */
    private final TerminalSelectionController mSelectionController = new TerminalSelectionController(this);

    /** 光标闪烁（P1-c 从本类搬出的职责类，见 {@link TerminalCursorBlinker}）。 */
    private final TerminalCursorBlinker mCursorBlinker = new TerminalCursorBlinker(this);

    /** 尺寸与布局（P1-c 从本类搬出的职责类，见 {@link TerminalSizeResolver}）。 */
    private final TerminalSizeResolver mSizeResolver = new TerminalSizeResolver(this);
    private boolean mCursorInvisibleIgnoreOnce;
    public static final int TERMINAL_CURSOR_BLINK_RATE_MIN = 100;
    public static final int TERMINAL_CURSOR_BLINK_RATE_MAX = 2000;

    /** The top row of text to display. Ranges from -activeTranscriptRows to 0. */
    int mTopRow;
    int[] mDefaultSelectors = new int[]{-1,-1,-1,-1};

    /**
     * 手势与滚动职责（P1-c 从本类搬出的职责类，见 {@link TerminalGestureController}）。
     * 字段 {@code mGestureRecognizer} / {@code mScroller} / {@code mScrollRemainder} /
     * {@code mScaleFactor} / {@code mMouseScrollStartX} / {@code mMouseScrollStartY} /
     * {@code mMouseStartDownTime} 随职责一并搬进该类。
     */
    private final TerminalGestureController mGestureController = new TerminalGestureController(this);

    /** If non-zero, this is the last unicode code point received if that was a combining character. */
    int mCombiningAccent;

    /**
     * 自动填充 / 无障碍（P1-c 从本类搬出的职责类，见 {@link TerminalA11yDelegate}）。
     * 字段 {@code mAutoFillType} / {@code mAutoFillImportance} / {@code mAutoFillHints} /
     * {@code mAccessibilityEnabled} 随职责一并搬进该类。
     */
    private final TerminalA11yDelegate mA11yDelegate = new TerminalA11yDelegate(this);
    /** 输入法与硬件键（P1-c 从本类搬出的职责类，见 {@link TerminalImeBridge}）。 */
    private final TerminalImeBridge mImeBridge = new TerminalImeBridge(this);

    /** The {@link KeyEvent} is generated from a virtual keyboard, like manually with the {@link KeyEvent#KeyEvent(int, int)} constructor. */
    public final static int KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = KeyCharacterMap.VIRTUAL_KEYBOARD; // -1

    /** The {@link KeyEvent} is generated from a non-physical device, like if 0 value is returned by {@link KeyEvent#getDeviceId()}. */
    public final static int KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0;

    public TerminalView(Context context, AttributeSet attributes) { // NO_UCD (unused code)
        super(context, attributes);
        // 无障碍开关（mAccessibilityEnabled）已随 TerminalA11yDelegate 的构造器读取。
    }



    /**
     * @param client The {@link TerminalViewClient} interface implementation to allow
     *                           for communication between {@link TerminalView} and its client.
     */
    public void setTerminalViewClient(TerminalViewClient client) {
        this.mClient = client;
    }

    /**
     * Sets whether terminal view key logging is enabled or not.
     *
     * @param value The boolean value that defines the state.
     */
    public void setIsTerminalViewKeyLoggingEnabled(boolean value) {
        sKeyLoggingEnabled = value;
    }



    /**
     * Attach a {@link TerminalSession} to this view.
     *
     * @param session The {@link TerminalSession} this view will be displaying.
     */
    public boolean attachSession(TerminalSession session) {
        if (session == mTermSession) return false;
        // The emulator's cached value will be read in `updateSize()` when emulator is set.
        setTopRow(0, false);

        mTermSession = session;
        mEmulator = null;
        mCombiningAccent = 0;

        updateSize();

        // Wait with enabling the scrollbar until we have a terminal to get scroll position from.
        setVerticalScrollBarEnabled(true);

        return true;
    }

    /*
     * 输入法连接（组合 / 提交 / 删除文本）本体已搬到 {@link TerminalImeBridge}，
     * 这里只保留同签名转发。
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        return mImeBridge.onCreateInputConnection(outAttrs);
    }

    @Override
    protected int computeVerticalScrollRange() {
        return mEmulator == null ? 1 : mEmulator.getScreen().getActiveRows();
    }

    @Override
    protected int computeVerticalScrollExtent() {
        return mEmulator == null ? 1 : mEmulator.mRows;
    }

    @Override
    protected int computeVerticalScrollOffset() {
        return mEmulator == null ? 1 : mEmulator.getScreen().getActiveRows() + mTopRow - mEmulator.mRows;
    }

    public void onScreenUpdated() {
        onScreenUpdated(false);
    }

    public void onScreenUpdated(boolean skipScrolling) {
        if (mEmulator == null) return;

        int rowsInHistory = mEmulator.getScreen().getActiveTranscriptRows();
        if (mTopRow < -rowsInHistory) setTopRow(-rowsInHistory);

        if (isSelectingText() || mEmulator.isAutoScrollDisabled()) {

            // Do not scroll when selecting text.
            int rowShift = mEmulator.getScrollCounter();
            if (-mTopRow + rowShift > rowsInHistory) {
                // .. unless we're hitting the end of history transcript, in which
                // case we abort text selection and scroll to end.
                if (isSelectingText())
                    stopTextSelectionMode();

                if (mEmulator.isAutoScrollDisabled()) {
                    setTopRow(-rowsInHistory);
                    skipScrolling = true;
                }
            } else {
                skipScrolling = true;
                setTopRow(mTopRow - rowShift);
                decrementYTextSelectionCursors(rowShift);
            }
        }

        if (!skipScrolling && mTopRow != 0) {
            // Scroll down if not already there.
            if (mTopRow < -3) {
                // Awaken scroll bars only if scrolling a noticeable amount
                // - we do not want visible scroll bars during normal typing
                // of one row at a time.
                awakenScrollBars();
            }
            setTopRow(0);
        }

        mEmulator.clearScrollCounter();

        invalidate();
        if (mA11yDelegate.isAccessibilityEnabled()) setContentDescription(getText());
    }

    /** This must be called by the hosting activity in {@link Activity#onContextMenuClosed(Menu)}
     * when context menu for the {@link TerminalView} is started by
     * {@link TextSelectionCursorController#ACTION_MORE} is closed. */
    public void onContextMenuClosed(Menu menu) {
        // Unset the stored text since it shouldn't be used anymore and should be cleared from memory
        unsetStoredSelectedText();
    }

    /**
     * Sets the text size, which in turn sets the number of rows and columns.
     *
     * @param textSize the new font size, in density-independent pixels.
     */
    public void setTextSize(int textSize) {
        mSizeResolver.setTextSize(textSize);
    }

    public void setTypeface(Typeface newTypeface) {
        mSizeResolver.setTypeface(newTypeface);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public boolean isOpaque() {
        return true;
    }

    /**
     * Get the zero indexed column and row of the terminal view for the
     * position of the event.
     *
     * @param event The event with the position to get the column and row for.
     * @param relativeToScroll If true the column number will take the scroll
     * position into account. E.g. if scrolled 3 lines up and the event
     * position is in the top left, column will be -3 if relativeToScroll is
     * true and 0 if relativeToScroll is false.
     * @return Array with the column and row.
     */
    public int[] getColumnAndRow(MotionEvent event, boolean relativeToScroll) {
        int column = (int) (event.getX() / mRenderer.mFontWidth);
        int row = (int) ((event.getY() - mRenderer.mFontLineSpacingAndAscent) / mRenderer.mFontLineSpacing);
        if (relativeToScroll) {
            row += mTopRow;
        }
        return new int[] { column, row };
    }

    /*
     * 本体已搬到 {@link TerminalGestureController}。
     * 仍在本类保留一个包内转发口，是因为 {@link TerminalImeBridge} 需要调用它。
     */
    void doScroll(MotionEvent event, int rowsDown) {
        mGestureController.doScroll(event, rowsDown);
    }

    /*
     * {@link View#awakenScrollBars()} 是 protected，本体搬到 {@link TerminalGestureController}
     * 之后无法从外部调用，故留一个包内转发口（与按键那三个 super 转发口同理，是本刀另一处
     * 非搬运新增）。
     */
    boolean awakenScrollBarsSuper() {
        return awakenScrollBars();
    }

    /*
     * 本体已搬到 {@link TerminalGestureController}，这里只保留同签名转发。
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return mGestureController.onGenericMotionEvent(event);
    }

    /*
     * 本体已搬到 {@link TerminalGestureController}，这里只保留同签名转发。
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    @TargetApi(23)
    public boolean onTouchEvent(MotionEvent event) {
        return mGestureController.onTouchEvent(event);
    }

    /*
     * 本体已搬到 {@link TerminalImeBridge}，这里只保留同签名转发。
     */
    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        return mImeBridge.onKeyPreIme(keyCode, event);
    }

    /*
     * 本体已搬到 {@link TerminalImeBridge}，这里只保留同签名转发。
     */
    /**
     * Key presses in software keyboards will generally NOT trigger this listener, although some
     * may elect to do so in some situations. Do not rely on this to catch software key presses.
     * Gboard calls this when shouldEnforceCharBasedInput() is disabled (InputType.TYPE_NULL) instead
     * of calling commitText(), with deviceId=-1. However, Hacker's Keyboard, OpenBoard, LG Keyboard
     * call commitText().
     *
     * This function may also be called directly without android calling it, like by
     * `TerminalExtraKeys` which generates a KeyEvent manually which uses {@link KeyCharacterMap#VIRTUAL_KEYBOARD}
     * as the device (deviceId=-1), as does Gboard. That would normally use mappings defined in
     * `/system/usr/keychars/Virtual.kcm`. You can run `dumpsys input` to find the `KeyCharacterMapFile`
     * used by virtual keyboard or hardware keyboard. Note that virtual keyboard device is not the
     * same as software keyboard, like Gboard, etc. Its a fake device used for generating events and
     * for testing.
     *
     * We handle shift key in `commitText()` to convert codepoint to uppercase case there with a
     * call to {@link Character#toUpperCase(int)}, but here we instead rely on getUnicodeChar() for
     * conversion of keyCode, for both hardware keyboard shift key (via effectiveMetaState) and
     * `mClient.readShiftKey()`, based on value in kcm files.
     * This may result in different behaviour depending on keyboard and android kcm files set for the
     * InputDevice for the event passed to this function. This will likely be an issue for non-english
     * languages since `Virtual.kcm` in english only by default or at least in AOSP. For both hardware
     * shift key (via effectiveMetaState) and `mClient.readShiftKey()`, `getUnicodeChar()` is used
     * for shift specific behaviour which usually is to uppercase.
     *
     * For fn key on hardware keyboard, android checks kcm files for hardware keyboards, which is
     * `Generic.kcm` by default, unless a vendor specific one is defined. The event passed will have
     * {@link KeyEvent#META_FUNCTION_ON} set. If the kcm file only defines a single character or unicode
     * code point `\\uxxxx`, then only one event is passed with that value. However, if kcm defines
     * a `fallback` key for fn or others, like `key DPAD_UP { ... fn: fallback PAGE_UP }`, then
     * android will first pass an event with original key `DPAD_UP` and {@link KeyEvent#META_FUNCTION_ON}
     * set. But this function will not consume it and android will pass another event with `PAGE_UP`
     * and {@link KeyEvent#META_FUNCTION_ON} not set, which will be consumed.
     *
     * Now there are some other issues as well, firstly ctrl and alt flags are not passed to
     * `getUnicodeChar()`, so modified key values in kcm are not used. Secondly, if the kcm file
     * for other modifiers like shift or fn define a non-alphabet, like { fn: '\u0015' } to act as
     * DPAD_LEFT, the `getUnicodeChar()` will correctly return `21` as the code point but action will
     * not happen because the `handleKeyCode()` function that transforms DPAD_LEFT to `\033[D`
     * escape sequence for the terminal to perform the left action would not be called since its
     * called before `getUnicodeChar()` and terminal will instead get `21 0x15 Negative Acknowledgement`.
     * The solution to such issues is calling `getUnicodeChar()` before the call to `handleKeyCode()`
     * if user has defined a custom kcm file, like done in POC mentioned in #2237. Note that
     * Hacker's Keyboard calls `commitText()` so don't test fn/shift with it for this function.
     * https://github.com/termux/termux-app/pull/2237
     * https://github.com/agnostic-apollo/termux-app/blob/terminal-code-point-custom-mapping/terminal-view/src/main/java/com/termux/view/TerminalView.java
     *
     * Key Character Map (kcm) and Key Layout (kl) files info:
     * https://source.android.com/devices/input/key-character-map-files
     * https://source.android.com/devices/input/key-layout-files
     * https://source.android.com/devices/input/keyboard-devices
     * AOSP kcm and kl files:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/data/keyboards
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/packages/InputDevices/res/raw
     *
     * KeyCodes:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/view/KeyEvent.java
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/native/include/android/keycodes.h
     *
     * `dumpsys input`:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/services/inputflinger/reader/EventHub.cpp;l=1917
     *
     * Loading of keymap:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/services/inputflinger/reader/EventHub.cpp;l=1644
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/Keyboard.cpp;l=41
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/InputDevice.cpp
     * OVERLAY keymaps for hardware keyboards may be combined as well:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=165
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=831
     *
     * Parse kcm file:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=727
     * Parse key value:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=981
     *
     * `KeyEvent.getUnicodeChar()`
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/view/KeyEvent.java;l=2716
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/view/KeyCharacterMap.java;l=368
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/jni/android_view_KeyCharacterMap.cpp;l=117
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=231
     *
     * Keyboard layouts advertised by applications, like for hardware keyboards via #ACTION_QUERY_KEYBOARD_LAYOUTS
     * Config is stored in `/data/system/input-manager-state.xml`
     * https://github.com/ris58h/custom-keyboard-layout
     * Loading from apps:
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=1221
     * Set:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/hardware/input/InputManager.java;l=89
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/hardware/input/InputManager.java;l=543
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:packages/apps/Settings/src/com/android/settings/inputmethod/KeyboardLayoutDialogFragment.java;l=167
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=1385
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/PersistentDataStore.java
     * Get overlay keyboard layout
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=2158
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/services/core/jni/com_android_server_input_InputManagerService.cpp;l=616
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mImeBridge.onKeyDown(keyCode, event);
    }

    /*
     * 输入法与硬件键本体已搬到 {@link TerminalImeBridge}，以下三个公开入口只做一行转发（公开 API 不变）。
     */
    public void inputCodePoint(int eventSource, int codePoint, boolean controlDownFromEvent, boolean leftAltDownFromEvent) {
        mImeBridge.inputCodePoint(eventSource, codePoint, controlDownFromEvent, leftAltDownFromEvent);
    }

    /** Input the specified keyCode if applicable and return if the input was consumed. */
    public boolean handleKeyCode(int keyCode, int keyMod) {
        return mImeBridge.handleKeyCode(keyCode, keyMod);
    }

    public boolean handleKeyCodeAction(int keyCode, int keyMod) {
        return mImeBridge.handleKeyCodeAction(keyCode, keyMod);
    }

    /*
     * 本体已搬到 {@link TerminalImeBridge}，这里只保留同签名转发。
     */
    /**
     * Called when a key is released in the view.
     *
     * @param keyCode The keycode of the key which was released.
     * @param event   A {@link KeyEvent} describing the event.
     * @return Whether the event was handled.
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return mImeBridge.onKeyUp(keyCode, event);
    }

    // ── 以下三个 super 转发口 ──────────────────────────────────────────────
    // P1-c 把按键本体搬到 TerminalImeBridge 后，桥不是 View 的子类，无法亲自调用
    // super.onKeyPreIme()/onKeyDown()/onKeyUp()，故在子类 TerminalView 里留这三个包内转发口，
    // 由桥回调。除这三行之外，桥与本刀没有引入任何行为变化。
    boolean onKeyPreImeSuper(int keyCode, KeyEvent event) {
        return super.onKeyPreIme(keyCode, event);
    }

    boolean onKeyDownSuper(int keyCode, KeyEvent event) {
        return super.onKeyDown(keyCode, event);
    }

    boolean onKeyUpSuper(int keyCode, KeyEvent event) {
        return super.onKeyUp(keyCode, event);
    }

    /**
     * This is called during layout when the size of this view has changed. If you were just added to the view
     * hierarchy, you're called with the old values of 0.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mSizeResolver.onSizeChanged();
    }

    /** Check if the terminal size in rows and columns should be updated. */
    public void updateSize() {
        mSizeResolver.updateSize();
    }

    /** 尺寸/emulator 变化后需要跟随的内部组件（目前是光标闪烁器）。 */
    void refreshEmulatorDependents() {
        mCursorBlinker.setEmulator(mEmulator);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mEmulator == null) {
            canvas.drawColor(0XFF000000);
        } else {
            // render the terminal view and highlight any selected text
            int[] sel = mDefaultSelectors;
            mSelectionController.getSelectors(sel);

            mRenderer.render(mEmulator, canvas, mTopRow, sel[0], sel[1], sel[2], sel[3]);

            // render the text selection handles
            mSelectionController.renderTextSelection();
        }
    }

    public TerminalSession getCurrentSession() {
        return mTermSession;
    }

    private CharSequence getText() {
        return mEmulator.getScreen().getSelectedText(0, mTopRow, mEmulator.mColumns, mTopRow + mEmulator.mRows);
    }

    public int getCursorX(float x) {
        return (int) (x / mRenderer.mFontWidth);
    }

    public int getCursorY(float y) {
        return (int) (((y - 40) / mRenderer.mFontLineSpacing) + mTopRow);
    }

    public int getPointX(int cx) {
        if (cx > mEmulator.mColumns) {
            cx = mEmulator.mColumns;
        }
        return Math.round(cx * mRenderer.mFontWidth);
    }

    public int getPointY(int cy) {
        return Math.round((cy - mTopRow) * mRenderer.mFontLineSpacing);
    }

    public int getTopRow() {
        return mTopRow;
    }

    public void setTopRow(int topRow) {
        setTopRow(topRow, true);
    }

    public void setTopRow(int topRow, boolean updateEmulator) {
        mTopRow = topRow;
        if (updateEmulator && mEmulator != null) {
            mEmulator.setTopRow(mTopRow);
        }
    }



    /**
     * Define functions required for AutoFill API
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void autofill(AutofillValue value) {
        mA11yDelegate.autofill(value);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int getAutofillType() {
        return mA11yDelegate.getAutofillType();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public String[] getAutofillHints() {
        return mA11yDelegate.getAutofillHints();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public AutofillValue getAutofillValue() {
        return mA11yDelegate.getAutofillValue();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int getImportantForAutofill() {
        return mA11yDelegate.getImportantForAutofill();
    }

    public AutofillManager getAutoFillManagerService() {
        return mA11yDelegate.getAutoFillManagerService();
    }

    public boolean isAutoFillEnabled() {
        return mA11yDelegate.isAutoFillEnabled();
    }

    public synchronized void requestAutoFillUsername() {
        mA11yDelegate.requestAutoFillUsername();
    }

    public synchronized void requestAutoFillPassword() {
        mA11yDelegate.requestAutoFillPassword();
    }

    public synchronized void requestAutoFill(String[] autoFillHints) {
        mA11yDelegate.requestAutoFill(autoFillHints);
    }

    public synchronized void cancelRequestAutoFill() {
        mA11yDelegate.cancelRequestAutoFill();
    }





    /**
     * Set terminal cursor blinker rate. It must be between {@link #TERMINAL_CURSOR_BLINK_RATE_MIN}
     * and {@link #TERMINAL_CURSOR_BLINK_RATE_MAX}, otherwise it will be disabled.
     *
     * The {@link #setTerminalCursorBlinkerState(boolean, boolean)} must be called after this
     * for changes to take effect if not disabling.
     *
     * @param blinkRate The value to set.
     * @return Returns {@code true} if setting blinker rate was successfully set, otherwise [@code false}.
     */
    public synchronized boolean setTerminalCursorBlinkerRate(int blinkRate) {
        return mCursorBlinker.setRate(blinkRate);
    }

    /**
     * Sets whether cursor blinker should be started or stopped. Cursor blinker will only be
     * started if the blink rate does not equal 0 and is between
     * {@link #TERMINAL_CURSOR_BLINK_RATE_MIN} and {@link #TERMINAL_CURSOR_BLINK_RATE_MAX}.
     *
     * This should be called when the view holding this activity is resumed or stopped so that
     * cursor blinker does not run when activity is not visible. If you call this on onResume()
     * to start cursor blinking, then ensure that {@link #mEmulator} is set, otherwise wait for the
     * {@link TerminalViewClient#onEmulatorSet()} event after calling {@link #attachSession(TerminalSession)}
     * for the first session added in the activity since blinking will not start if {@link #mEmulator}
     * is not set, like if activity is started again after exiting it with double back press. Do not
     * call this directly after {@link #attachSession(TerminalSession)} since {@link #updateSize()}
     * may return without setting {@link #mEmulator} since width/height may be 0. Its called again in
     * {@link #onSizeChanged(int, int, int, int)}. Calling on onResume() if emulator is already set
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
     * itself to be called with the delay set by the blink rate. When cursor
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
     * we log only if {@link #isKeyLoggingEnabled()} is enabled, otherwise would clutter
     * the log. We don't start the blinking with a delay to immediately show cursor in case it was
     * previously not visible.
     *
     * @param start If cursor blinker should be started or stopped.
     * @param startOnlyIfCursorEnabled If set to {@code true}, then it will also be checked if the
     *                                 cursor is even enabled by {@link TerminalEmulator} before
     *                                 starting the cursor blinker.
     */
    public synchronized void setTerminalCursorBlinkerState(boolean start, boolean startOnlyIfCursorEnabled) {
        mCursorBlinker.setState(start, startOnlyIfCursorEnabled);
    }

    /**
     * Cancel the terminal cursor blinker callbacks
     */
    private void stopTerminalCursorBlinker() {
        mCursorBlinker.stop();
    }

    /**
     * Define functions required for text selection and its handles.
     *
     * 证道 P1-c（2026-10-10）：本体已搬到 {@link TerminalSelectionController}，这里只留同签名转发，
     * 公开 API 一字不改。
     */
    public boolean isSelectingText() {
        return mSelectionController.isSelectingText();
    }

    /** Get the currently selected text if selecting. */
    public String getSelectedText() {
        return mSelectionController.getSelectedText();
    }

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    @Nullable
    public String getStoredSelectedText() {
        return mSelectionController.getStoredSelectedText();
    }

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    public void unsetStoredSelectedText() {
        mSelectionController.unsetStoredSelectedText();
    }

    public void startTextSelectionMode(MotionEvent event) {
        mSelectionController.startTextSelectionMode(event);
    }

    public void stopTextSelectionMode() {
        mSelectionController.stopTextSelectionMode();
    }

    private void decrementYTextSelectionCursors(int decrement) {
        mSelectionController.decrementYTextSelectionCursors(decrement);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mSelectionController.onViewAttached();

        // 证道 P1-a（2026-10-10）：与 onDetachedFromWindow() 里的 stopTerminalCursorBlinker() 配对。
        // 否则"离开终端页再回来"（View 被 detach 又 attach、Activity 没重建）时光标会停在上一次的
        // 闪烁状态不动。setTerminalCursorBlinkerState() 内部先 stop 再 start，重复调用是幂等的。
        if (mEmulator != null) setTerminalCursorBlinkerState(true, false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        // 证道 P1-a（2026-10-10）：上游漏了这一句（审计 🟡-5）。不停止的话，主线程 Handler 会一直
        // 持有 BlinkerRunnable（TerminalCursorBlinker 的非静态内部类）-> TerminalCursorBlinker
        // -> TerminalView -> Activity 的引用链，
        // 每进出一次终端页就留一条 600 ms 的永久空转循环（每次还白做一次整屏合成）。
        stopTerminalCursorBlinker();

        mSelectionController.onViewDetached();
    }



    /**
     * Define functions required for long hold toolbar.
     *
     * 证道 P1-c（2026-10-10）：本体已搬到 {@link TerminalSelectionController}，这里只留同签名转发。
     */
    @RequiresApi(api = Build.VERSION_CODES.M)
    void hideFloatingToolbar() {
        mSelectionController.hideFloatingToolbar();
    }

    public void updateFloatingToolbarVisibility(MotionEvent event) {
        mSelectionController.updateFloatingToolbarVisibility(event);
    }

}
