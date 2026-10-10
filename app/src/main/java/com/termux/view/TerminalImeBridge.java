package com.termux.view;

import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalEmulator;

/**
 * 输入法与硬件键职责：软键盘的 {@link InputConnection}（组合 / 提交 / 删除文本），以及硬件与虚拟键盘的
 * {@code onKeyPreIme()} / {@code onKeyDown()} / {@code onKeyUp()} 路径与 code point 转换。
 *
 * <p>证道 P1-c（2026-10-10）：从 {@link TerminalView} 按职责搬出（纯搬运、零行为变化）。{@code TerminalView}
 * 侧保留 {@code onCreateInputConnection(EditorInfo)} / {@code onKeyPreIme(int, KeyEvent)} /
 * {@code onKeyDown(int, KeyEvent)} / {@code onKeyUp(int, KeyEvent)} /
 * {@code inputCodePoint(int, int, boolean, boolean)} / {@code handleKeyCode(int, int)} /
 * {@code handleKeyCodeAction(int, int)} 的同名同签名入口做一行转发，**公开 API 一字不改**。</p>
 *
 * <p>搬家只做这些改动：直接访问的 View 成员改成 {@code mView.xxx}；{@link TerminalView} 的包内静态
 * （{@code LOG_TAG} / {@code TERMINAL_VIEW_KEY_LOGGING_ENABLED} / {@code KEY_EVENT_SOURCE_*}）加类名限定
 * （本类不是它的子类）；匿名 {@link BaseInputConnection} 的目标 View 由 {@code this} 改成 {@code mView}
 * （必须是那个被输入法绑定的 View，而不是本桥）；原来写在本类里的 {@code super.onKeyXxx(...)} 改走
 * {@link TerminalView} 的包内转发口 {@code onKeyPreImeSuper()} / {@code onKeyDownSuper()} / {@code onKeyUpSuper()}
 * —— {@link android.view.View} 的子类是 {@link TerminalView} 而不是本桥。判断顺序、日志文案、常量取值、
 * 注释与搬家前逐字一致。</p>
 */
final class TerminalImeBridge {

    private final TerminalView mView;

    TerminalImeBridge(TerminalView view) {
        mView = view;
    }

    InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // Ensure that inputType is only set if TerminalView is selected view with the keyboard and
        // an alternate view is not selected, like an EditText. This is necessary if an activity is
        // initially started with the alternate view or if activity is returned to from another app
        // and the alternate view was the one selected the last time.
        if (mView.mClient.isTerminalViewSelected()) {
            if (mView.mClient.shouldEnforceCharBasedInput()) {
                // Some keyboards seems do not reset the internal state on TYPE_NULL.
                // Affects mostly Samsung stock keyboards.
                // https://github.com/termux/termux-app/issues/686
                // However, this is not a valid value as per AOSP since `InputType.TYPE_CLASS_*` is
                // not set and it logs a warning:
                // W/InputAttributes: Unexpected input class: inputType=0x00080090 imeOptions=0x02000000
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:packages/inputmethods/LatinIME/java/src/com/android/inputmethod/latin/InputAttributes.java;l=79
                //
                // ── 证道定制（2026-10-05）──────────────────────────────────────────
                // 原值 `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS`
                // 属于**密码类输入**，在荣耀/华为/小米等国产 ROM 上会被系统判定为敏感输入，
                // 从而**强制接管为「安全键盘」**（无联想、无剪贴板、手感差），用户明确要求避免。
                // 改为普通文本类型：既非密码类（不触发安全键盘），又保留关联想与多行。
                //   - TYPE_CLASS_TEXT                输入法按普通文本处理（中文输入法正常）
                //   - TYPE_TEXT_FLAG_NO_SUGGESTIONS  关闭候选/联想（终端不需要）
                //   - TYPE_TEXT_FLAG_MULTI_LINE      回车当作换行，而不是"完成/发送"
                // 注：Termux 选 VISIBLE_PASSWORD 是为了规避三星键盘在 TYPE_NULL 下不重置
                // 内部状态的问题（termux-app#686）；本机为荣耀，该规避不适用，而安全键盘
                // 是实测正在发生的真问题，故以本机体验为准。
                outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_NORMAL
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            } else {
                // Using InputType.NULL is the most correct input type and avoids issues with other hacks.
                //
                // Previous keyboard issues:
                // https://github.com/termux/termux-packages/issues/25
                // https://github.com/termux/termux-app/issues/87.
                // https://github.com/termux/termux-app/issues/126.
                // https://github.com/termux/termux-app/issues/137 (japanese chars and TYPE_NULL).
                outAttrs.inputType = InputType.TYPE_NULL;
            }
        } else {
            // Corresponds to android:inputType="text"
            outAttrs.inputType =  InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
        }

        // Note that IME_ACTION_NONE cannot be used as that makes it impossible to input newlines using the on-screen
        // keyboard on Android TV (see https://github.com/termux/termux-app/issues/221).
        // 证道定制：同时声明"不用于个性化学习"，进一步降低输入法把终端输入当敏感内容的概率
        //（部分 ROM 会依据编辑器属性决定是否启用安全键盘/隐私模式）。
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;

        return new BaseInputConnection(mView, true) {

            @Override
            public boolean finishComposingText() {
                if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED) mView.mClient.logInfo(TerminalView.LOG_TAG, "IME: finishComposingText()");
                super.finishComposingText();

                if (mView.mImeProbeObserver != null) mView.mImeProbeObserver.onImeEvent("finishComposingText", String.valueOf(getEditable()));
                sendTextToTerminal(getEditable());
                getEditable().clear();
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // 观察点（输入回归页）：组合中的候选字串。仅记录，不改 Termux 行为。
                if (mView.mImeProbeObserver != null) mView.mImeProbeObserver.onImeEvent("setComposingText", String.valueOf(text));
                return super.setComposingText(text, newCursorPosition);
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mView.mClient.logInfo(TerminalView.LOG_TAG, "IME: commitText(\"" + text + "\", " + newCursorPosition + ")");
                }
                super.commitText(text, newCursorPosition);

                if (mView.mImeProbeObserver != null) mView.mImeProbeObserver.onImeEvent("commitText", String.valueOf(text));
                if (mView.mEmulator == null) return true;

                Editable content = getEditable();
                sendTextToTerminal(content);
                content.clear();
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int leftLength, int rightLength) {
                if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mView.mClient.logInfo(TerminalView.LOG_TAG, "IME: deleteSurroundingText(" + leftLength + ", " + rightLength + ")");
                }
                // The stock Samsung keyboard with 'Auto check spelling' enabled sends leftLength > 1.
                if (mView.mImeProbeObserver != null) mView.mImeProbeObserver.onImeEvent("deleteSurroundingText", leftLength + "," + rightLength);
                KeyEvent deleteKey = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < leftLength; i++) sendKeyEvent(deleteKey);
                return super.deleteSurroundingText(leftLength, rightLength);
            }

            void sendTextToTerminal(CharSequence text) {
                mView.stopTextSelectionMode();
                final int textLengthInChars = text.length();
                for (int i = 0; i < textLengthInChars; i++) {
                    char firstChar = text.charAt(i);
                    int codePoint;
                    if (Character.isHighSurrogate(firstChar)) {
                        if (++i < textLengthInChars) {
                            codePoint = Character.toCodePoint(firstChar, text.charAt(i));
                        } else {
                            // At end of string, with no low surrogate following the high:
                            codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR;
                        }
                    } else {
                        codePoint = firstChar;
                    }

                    // Check onKeyDown() for details.
                    if (mView.mClient.readShiftKey())
                        codePoint = Character.toUpperCase(codePoint);

                    boolean ctrlHeld = false;
                    if (codePoint <= 31 && codePoint != 27) {
                        if (codePoint == '\n') {
                            // The AOSP keyboard and descendants seems to send \n as text when the enter key is pressed,
                            // instead of a key event like most other keyboard apps. A terminal expects \r for the enter
                            // key (although when icrnl is enabled this doesn't make a difference - run 'stty -icrnl' to
                            // check the behaviour).
                            codePoint = '\r';
                        }

                        // E.g. penti keyboard for ctrl input.
                        ctrlHeld = true;
                        switch (codePoint) {
                            case 31:
                                codePoint = '_';
                                break;
                            case 30:
                                codePoint = '^';
                                break;
                            case 29:
                                codePoint = ']';
                                break;
                            case 28:
                                codePoint = '\\';
                                break;
                            default:
                                codePoint += 96;
                                break;
                        }
                    }

                    inputCodePoint(TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD, codePoint, ctrlHeld, false);
                }
            }

        };
    }

    boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mView.mClient.logInfo(TerminalView.LOG_TAG, "onKeyPreIme(keyCode=" + keyCode + ", event=" + event + ")");
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mView.cancelRequestAutoFill();
            if (mView.isSelectingText()) {
                mView.stopTextSelectionMode();
                return true;
            } else if (mView.mClient.shouldBackButtonBeMappedToEscape()) {
                // Intercept back button to treat it as escape:
                switch (event.getAction()) {
                    case KeyEvent.ACTION_DOWN:
                        return onKeyDown(keyCode, event);
                    case KeyEvent.ACTION_UP:
                        return onKeyUp(keyCode, event);
                }
            }
        } else if (mView.mClient.shouldUseCtrlSpaceWorkaround() &&
                   keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed()) {
            /* ctrl+space does not work on some ROMs without this workaround.
               However, this breaks it on devices where it works out of the box. */
            return onKeyDown(keyCode, event);
        }
        return mView.onKeyPreImeSuper(keyCode, event);
    }

    /*
     * 详细说明（kcm/kl 映射、getUnicodeChar 与 handleKeyCode 的调用顺序、上游相关 issue 链接）
     * 保留在公开入口 TerminalView#onKeyDown(int, KeyEvent) 的 javadoc 里，此处不再重复一份。
     */
    boolean onKeyDown(int keyCode, KeyEvent event) {
        if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mView.mClient.logInfo(TerminalView.LOG_TAG, "onKeyDown(keyCode=" + keyCode + ", isSystem()=" + event.isSystem() + ", event=" + event + ")");
        if (mView.mEmulator == null) return true;
        if (mView.isSelectingText()) {
            mView.stopTextSelectionMode();
        }

        if (mView.mClient.onKeyDown(keyCode, event, mView.mTermSession)) {
            mView.invalidate();
            return true;
        } else if (event.isSystem() && (!mView.mClient.shouldBackButtonBeMappedToEscape() || keyCode != KeyEvent.KEYCODE_BACK)) {
            return mView.onKeyDownSuper(keyCode, event);
        } else if (event.getAction() == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            mView.mTermSession.write(event.getCharacters());
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH) {
            return mView.onKeyDownSuper(keyCode, event);
        }

        final int metaState = event.getMetaState();
        final boolean controlDown = event.isCtrlPressed() || mView.mClient.readControlKey();
        final boolean leftAltDown = (metaState & KeyEvent.META_ALT_LEFT_ON) != 0 || mView.mClient.readAltKey();
        final boolean shiftDown = event.isShiftPressed() || mView.mClient.readShiftKey();
        final boolean rightAltDownFromEvent = (metaState & KeyEvent.META_ALT_RIGHT_ON) != 0;

        int keyMod = 0;
        if (controlDown) keyMod |= KeyHandler.KEYMOD_CTRL;
        if (event.isAltPressed() || leftAltDown) keyMod |= KeyHandler.KEYMOD_ALT;
        if (shiftDown) keyMod |= KeyHandler.KEYMOD_SHIFT;
        if (event.isNumLockOn()) keyMod |= KeyHandler.KEYMOD_NUM_LOCK;
        // https://github.com/termux/termux-app/issues/731
        if (!event.isFunctionPressed() && handleKeyCode(keyCode, keyMod)) {
            if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED) mView.mClient.logInfo(TerminalView.LOG_TAG, "handleKeyCode() took key event");
            return true;
        }

        // Clear Ctrl since we handle that ourselves:
        int bitsToClear = KeyEvent.META_CTRL_MASK;
        if (rightAltDownFromEvent) {
            // Let right Alt/Alt Gr be used to compose characters.
        } else {
            // Use left alt to send to terminal (e.g. Left Alt+B to jump back a word), so remove:
            bitsToClear |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        }
        int effectiveMetaState = event.getMetaState() & ~bitsToClear;

        if (shiftDown) effectiveMetaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        if (mView.mClient.readFnKey()) effectiveMetaState |= KeyEvent.META_FUNCTION_ON;

        int result = event.getUnicodeChar(effectiveMetaState);
        if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mView.mClient.logInfo(TerminalView.LOG_TAG, "KeyEvent#getUnicodeChar(" + effectiveMetaState + ") returned: " + result);
        if (result == 0) {
            return false;
        }

        int oldCombiningAccent = mView.mCombiningAccent;
        if ((result & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            // If entered combining accent previously, write it out:
            if (mView.mCombiningAccent != 0)
                inputCodePoint(event.getDeviceId(), mView.mCombiningAccent, controlDown, leftAltDown);
            mView.mCombiningAccent = result & KeyCharacterMap.COMBINING_ACCENT_MASK;
        } else {
            if (mView.mCombiningAccent != 0) {
                int combinedChar = KeyCharacterMap.getDeadChar(mView.mCombiningAccent, result);
                if (combinedChar > 0) result = combinedChar;
                mView.mCombiningAccent = 0;
            }
            inputCodePoint(event.getDeviceId(), result, controlDown, leftAltDown);
        }

        if (mView.mCombiningAccent != oldCombiningAccent) mView.invalidate();

        return true;
    }

    void inputCodePoint(int eventSource, int codePoint, boolean controlDownFromEvent, boolean leftAltDownFromEvent) {
        if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mView.mClient.logInfo(TerminalView.LOG_TAG, "inputCodePoint(eventSource=" + eventSource + ", codePoint=" + codePoint + ", controlDownFromEvent=" + controlDownFromEvent + ", leftAltDownFromEvent="
                + leftAltDownFromEvent + ")");
        }

        if (mView.mTermSession == null) return;

        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        if (mView.mEmulator != null)
            mView.mEmulator.setCursorBlinkState(true);

        final boolean controlDown = controlDownFromEvent || mView.mClient.readControlKey();
        final boolean altDown = leftAltDownFromEvent || mView.mClient.readAltKey();

        if (mView.mClient.onCodePoint(codePoint, controlDown, mView.mTermSession)) return;

        if (controlDown) {
            if (codePoint >= 'a' && codePoint <= 'z') {
                codePoint = codePoint - 'a' + 1;
            } else if (codePoint >= 'A' && codePoint <= 'Z') {
                codePoint = codePoint - 'A' + 1;
            } else if (codePoint == ' ' || codePoint == '2') {
                codePoint = 0;
            } else if (codePoint == '[' || codePoint == '3') {
                codePoint = 27; // ^[ (Esc)
            } else if (codePoint == '\\' || codePoint == '4') {
                codePoint = 28;
            } else if (codePoint == ']' || codePoint == '5') {
                codePoint = 29;
            } else if (codePoint == '^' || codePoint == '6') {
                codePoint = 30; // control-^
            } else if (codePoint == '_' || codePoint == '7' || codePoint == '/') {
                // "Ctrl-/ sends 0x1f which is equivalent of Ctrl-_ since the days of VT102"
                // - http://apple.stackexchange.com/questions/24261/how-do-i-send-c-that-is-control-slash-to-the-terminal
                codePoint = 31;
            } else if (codePoint == '8') {
                codePoint = 127; // DEL
            }
        }

        if (codePoint > -1) {
            // If not virtual or soft keyboard.
            if (eventSource > TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
                // Work around bluetooth keyboards sending funny unicode characters instead
                // of the more normal ones from ASCII that terminal programs expect - the
                // desire to input the original characters should be low.
                switch (codePoint) {
                    case 0x02DC: // SMALL TILDE.
                        codePoint = 0x007E; // TILDE (~).
                        break;
                    case 0x02CB: // MODIFIER LETTER GRAVE ACCENT.
                        codePoint = 0x0060; // GRAVE ACCENT (`).
                        break;
                    case 0x02C6: // MODIFIER LETTER CIRCUMFLEX ACCENT.
                        codePoint = 0x005E; // CIRCUMFLEX ACCENT (^).
                        break;
                }
            }

            // If left alt, send escape before the code point to make e.g. Alt+B and Alt+F work in readline:
            mView.mTermSession.writeCodePoint(altDown, codePoint);
        }
    }

    /** Input the specified keyCode if applicable and return if the input was consumed. */
    boolean handleKeyCode(int keyCode, int keyMod) {
        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        if (mView.mEmulator != null)
            mView.mEmulator.setCursorBlinkState(true);

        if (handleKeyCodeAction(keyCode, keyMod))
            return true;

        TerminalEmulator term = mView.mTermSession.getEmulator();
        String code = KeyHandler.getCode(keyCode, keyMod, term.isCursorKeysApplicationMode(), term.isKeypadApplicationMode());
        if (code == null) return false;
        mView.mTermSession.write(code);
        return true;
    }

    boolean handleKeyCodeAction(int keyCode, int keyMod) {
        boolean shiftDown = (keyMod & KeyHandler.KEYMOD_SHIFT) != 0;

        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                // shift+page_up and shift+page_down should scroll scrollback history instead of
                // scrolling command history or changing pages
                if (shiftDown) {
                    long time = SystemClock.uptimeMillis();
                    MotionEvent motionEvent = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0, 0, 0);
                    mView.doScroll(motionEvent, keyCode == KeyEvent.KEYCODE_PAGE_UP ? -mView.mEmulator.mRows : mView.mEmulator.mRows);
                    motionEvent.recycle();
                    return true;
                }
        }

       return false;
    }

    /**
     * Called when a key is released in the view.
     *
     * @param keyCode The keycode of the key which was released.
     * @param event   A {@link KeyEvent} describing the event.
     * @return Whether the event was handled.
     */
    boolean onKeyUp(int keyCode, KeyEvent event) {
        if (TerminalView.TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mView.mClient.logInfo(TerminalView.LOG_TAG, "onKeyUp(keyCode=" + keyCode + ", event=" + event + ")");

        // Do not return for KEYCODE_BACK and send it to the client since user may be trying
        // to exit the activity.
        if (mView.mEmulator == null && keyCode != KeyEvent.KEYCODE_BACK) return true;

        if (mView.mClient.onKeyUp(keyCode, event)) {
            mView.invalidate();
            return true;
        } else if (event.isSystem()) {
            // Let system key events through.
            return mView.onKeyUpSuper(keyCode, event);
        }

        return true;
    }
}
