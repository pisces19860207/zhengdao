package com.termux.view;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;

import androidx.annotation.RequiresApi;

/**
 * 自动填充 / 无障碍：{@link View} 的 AutoFill API 状态与请求逻辑（P1-c 从 {@link TerminalView} 原样搬出，
 * 零行为变化）。
 *
 * <p>上游把这组职责和其他五类职责混在同一个 {@link TerminalView} 里。搬家只做这些改动：
 * 用 {@code mView.xxx} 访问 View 的成员；{@link View} 的静态常量（{@code AUTOFILL_TYPE_*} /
 * {@code IMPORTANT_FOR_AUTOFILL_*}）从"继承自 View 的隐式名字"改成 {@code View.xxx} 显式限定
 * （本类不是 View 的子类）；{@code autofillManager.requestAutofill(this)} 改成
 * {@code requestAutofill(mView)}（那里的 {@code this} 必须是被搬出去的那个 View，不是本委托对象）；
 * {@code getContext()} 改成 {@code mView.getContext()}。SDK 版本分支、判断顺序、日志文案、常量取值、
 * {@code synchronized} 语义与搬家前逐字一致；{@link TerminalView} 侧保留同名同签名方法做纯转发。
 *
 * <p>搬走的四个字段里只有 {@code mAutoFillType} / {@code mAutoFillImportance} / {@code mAutoFillHints}
 * 是 AutoFill 自己的状态；{@code mAccessibilityEnabled} 原本只被 {@link TerminalView} 的文本更新路径读一次
 * （决定要不要 {@code setContentDescription()}），因此本类给它留了包内访问器
 * {@link #isAccessibilityEnabled()}，读取时机（构造期从 {@link AccessibilityManager} 取一次）不变。
 */
final class TerminalA11yDelegate {

    /** 日志 TAG：与 {@link TerminalView} 搬家前用的同一个字符串，保证日志文案不变。 */
    private static final String LOG_TAG = "TerminalView";

    private final TerminalView mView;

    /**
     * The current AutoFill type returned for {@link View#getAutofillType()} by {@link #getAutofillType()}.
     *
     * The default is {@link View#AUTOFILL_TYPE_NONE} so that AutoFill UI, like toolbar above keyboard
     * is not shown automatically, like on Activity starts/View create. This value should be updated
     * to required value, like {@link View#AUTOFILL_TYPE_TEXT} before calling
     * {@link AutofillManager#requestAutofill(View)} so that AutoFill UI shows. The updated value
     * set will automatically be restored to {@link View#AUTOFILL_TYPE_NONE} in
     * {@link #autofill(AutofillValue)} so that AutoFill UI isn't shown anymore by calling
     * {@link #resetAutoFill()}.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private int mAutoFillType = View.AUTOFILL_TYPE_NONE;

    /**
     * The current AutoFill type returned for {@link View#getImportantForAutofill()} by
     * {@link #getImportantForAutofill()}.
     *
     * The default is {@link View#IMPORTANT_FOR_AUTOFILL_NO} so that view is not considered important
     * for AutoFill. This value should be updated to required value, like
     * {@link View#IMPORTANT_FOR_AUTOFILL_YES} before calling {@link AutofillManager#requestAutofill(View)}
     * so that Android and apps consider the view as important for AutoFill to process the request.
     * The updated value set will automatically be restored to {@link View#IMPORTANT_FOR_AUTOFILL_NO} in
     * {@link #autofill(AutofillValue)} by calling {@link #resetAutoFill()}.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private int mAutoFillImportance = View.IMPORTANT_FOR_AUTOFILL_NO;

    /**
     * The current AutoFill hints returned for {@link View#getAutofillHints()} ()} by {@link #getAutofillHints()} ()}.
     *
     * The default is an empty `string[]`. This value should be updated to required value. The
     * updated value set will automatically be restored an empty `string[]` in
     * {@link #autofill(AutofillValue)} by calling {@link #resetAutoFill()}.
     */
    private String[] mAutoFillHints = new String[0];

    /**
     * 无障碍服务是否开启（原 {@link TerminalView} 构造器末尾读取；搬家后在本委托构造器里读取，
     * {@link TerminalView} 侧经 {@link #isAccessibilityEnabled()} 读同一个值）。
     */
    private final boolean mAccessibilityEnabled;

    TerminalA11yDelegate(TerminalView view) {
        mView = view;
        AccessibilityManager am = (AccessibilityManager) view.getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        mAccessibilityEnabled = am.isEnabled();
    }

    /** 供 {@link TerminalView} 决定是否 {@code setContentDescription()} 用。 */
    boolean isAccessibilityEnabled() {
        return mAccessibilityEnabled;
    }

    /**
     * Define functions required for AutoFill API
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    void autofill(AutofillValue value) {
        if (value.isText()) {
            mView.mTermSession.write(value.getTextValue().toString());
        }

        resetAutoFill();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    int getAutofillType() {
        return mAutoFillType;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    String[] getAutofillHints() {
        return mAutoFillHints;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    AutofillValue getAutofillValue() {
        return AutofillValue.forText("");
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    int getImportantForAutofill() {
        return mAutoFillImportance;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    synchronized void resetAutoFill() {
        // Restore none type so that AutoFill UI isn't shown anymore.
        mAutoFillType = View.AUTOFILL_TYPE_NONE;
        mAutoFillImportance = View.IMPORTANT_FOR_AUTOFILL_NO;
        mAutoFillHints = new String[0];
    }

    AutofillManager getAutoFillManagerService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;

        try {
            Context context = mView.getContext();
            if (context == null) return null;
            return context.getSystemService(AutofillManager.class);
        } catch (Exception e) {
            mView.mClient.logStackTraceWithMessage(LOG_TAG, "Failed to get AutofillManager service", e);
            return null;
        }
    }

    boolean isAutoFillEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;

        try {
            AutofillManager autofillManager = getAutoFillManagerService();
            return autofillManager != null && autofillManager.isEnabled();
        } catch (Exception e) {
            mView.mClient.logStackTraceWithMessage(LOG_TAG, "Failed to check if Autofill is enabled", e);
            return false;
        }
    }

    synchronized void requestAutoFillUsername() {
        requestAutoFill(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new String[]{View.AUTOFILL_HINT_USERNAME} :
                null);
    }

    synchronized void requestAutoFillPassword() {
        requestAutoFill(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new String[]{View.AUTOFILL_HINT_PASSWORD} :
            null);
    }

    synchronized void requestAutoFill(String[] autoFillHints) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (autoFillHints == null || autoFillHints.length < 1) return;

        try {
            AutofillManager autofillManager = getAutoFillManagerService();
            if (autofillManager != null && autofillManager.isEnabled()) {
                // Update type that will be returned by `getAutofillType()` so that AutoFill UI is shown.
                mAutoFillType = View.AUTOFILL_TYPE_TEXT;
                // Update importance that will be returned by `getImportantForAutofill()` so that
                // AutoFill considers the view as important.
                mAutoFillImportance = View.IMPORTANT_FOR_AUTOFILL_YES;
                // Update hints that will be returned by `getAutofillHints()` for which to show AutoFill UI.
                mAutoFillHints = autoFillHints;
                autofillManager.requestAutofill(mView);
            }
        } catch (Exception e) {
            mView.mClient.logStackTraceWithMessage(LOG_TAG, "Failed to request Autofill", e);
        }
    }

    synchronized void cancelRequestAutoFill() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (mAutoFillType == View.AUTOFILL_TYPE_NONE) return;

        try {
            AutofillManager autofillManager = getAutoFillManagerService();
            if (autofillManager != null && autofillManager.isEnabled()) {
                resetAutoFill();
                autofillManager.cancel();
            }
        } catch (Exception e) {
            mView.mClient.logStackTraceWithMessage(LOG_TAG, "Failed to cancel Autofill request", e);
        }
    }
}
