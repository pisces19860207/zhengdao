/* 证道第一方终端视图原型：独立网格绘制、输入与手势控制器，复用 emulator/session。 */
package com.termux.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** Uses the existing emulator/session contract with Zhengdao-owned rendering and input controllers. */
public final class ZhengdaoTerminalView extends TerminalView {

    private final Paint mSurfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mSurfaceBounds = new RectF();
    private final Path mSurfaceClip = new Path();
    private final ZhengdaoTerminalRenderer mTerminalRenderer = new ZhengdaoTerminalRenderer();
    private final TerminalImeBridge mImeBridge = new TerminalImeBridge(this);
    private final ZhengdaoTerminalGestureController mGestureController =
        new ZhengdaoTerminalGestureController(this);
    private TerminalRenderer mRendererMetricsSource;
    private float mCornerRadiusPx;
    private float mBorderWidthPx;
    private boolean mHasSurfaceStyle;

    public ZhengdaoTerminalView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setBackgroundColor(Color.TRANSPARENT);
        setWillNotDraw(false);
    }

    @Override
    public boolean isOpaque() {
        return !mHasSurfaceStyle && super.isOpaque();
    }

    @Override
    public void setTextSize(int textSize) {
        super.setTextSize(textSize);
        invalidate();
    }

    @Override
    public void setTypeface(Typeface typeface) {
        super.setTypeface(typeface);
        invalidate();
    }

    @Override
    public void setLineHeightMultiplier(float multiplier) {
        super.setLineHeightMultiplier(multiplier);
        invalidate();
    }

    /**
     * 配置终端面板颜色。该方法只影响 surface，不修改 emulator ANSI 调色板。
     */
    public void setSurfaceStyle(int backgroundColor, int foregroundColor) {
        final float density = getResources().getDisplayMetrics().density;
        mCornerRadiusPx = 14f * density;
        mBorderWidthPx = Math.max(1f, density);
        mSurfacePaint.setColor(backgroundColor);
        mSurfacePaint.setStyle(Paint.Style.FILL);
        mBorderPaint.setColor(Color.argb(
            54,
            Color.red(foregroundColor),
            Color.green(foregroundColor),
            Color.blue(foregroundColor)
        ));
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(mBorderWidthPx);
        mHasSurfaceStyle = true;
        setBackgroundColor(Color.TRANSPARENT);
        updateSurfaceBounds(getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateSurfaceBounds(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mHasSurfaceStyle) {
            if (mEmulator == null) {
                super.onDraw(canvas);
                return;
            }
        }

        if (mHasSurfaceStyle) {
            canvas.drawRoundRect(mSurfaceBounds, mCornerRadiusPx, mCornerRadiusPx, mSurfacePaint);
        }
        if (mEmulator == null || mRenderer == null) return;

        if (mRendererMetricsSource != mRenderer) {
            mTerminalRenderer.setMetrics(mRenderer);
            mRendererMetricsSource = mRenderer;
        }
        final int saveCount;
        if (mHasSurfaceStyle) {
            saveCount = canvas.save();
            canvas.clipPath(mSurfaceClip);
        } else {
            saveCount = -1;
        }
        final int[] selectors = getSelectionSelectors();
        mTerminalRenderer.render(
            canvas,
            mEmulator,
            mTopRow,
            selectors[0],
            selectors[1],
            selectors[2],
            selectors[3]
        );
        if (saveCount >= 0) canvas.restoreToCount(saveCount);
        renderSelectionHandles();
        if (mHasSurfaceStyle) {
            canvas.drawRoundRect(mSurfaceBounds, mCornerRadiusPx, mCornerRadiusPx, mBorderPaint);
        }
    }

    private void updateSurfaceBounds(int width, int height) {
        final float halfBorder = mBorderWidthPx / 2f;
        mSurfaceBounds.set(halfBorder, halfBorder, width - halfBorder, height - halfBorder);
        mSurfaceClip.reset();
        if (mHasSurfaceStyle && width > 0 && height > 0) {
            mSurfaceClip.addRoundRect(
                mSurfaceBounds,
                mCornerRadiusPx,
                mCornerRadiusPx,
                Path.Direction.CW
            );
        }
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        return mImeBridge.onCreateInputConnection(outAttrs);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        return mImeBridge.onKeyPreIme(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mImeBridge.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return mImeBridge.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return mGestureController.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return mGestureController.onGenericMotionEvent(event);
    }

    @Override
    public void computeScroll() {
        super.computeScroll();
        mGestureController.computeScroll();
    }

    void scrollTerminalRows(int rowsDown) {
        mGestureController.scrollByRows(rowsDown);
    }

    boolean sendTerminalKeyCode(int keyCode, int modifiers) {
        return mImeBridge.handleKeyCode(keyCode, modifiers);
    }

    int[] getGridCell(float x, float y) {
        if (mRenderer == null || mRenderer.mFontWidth <= 0 || mRenderer.mFontLineSpacing <= 0) {
            return new int[]{0, 0};
        }
        final int column = (int) (x / mRenderer.mFontWidth);
        final int row = (int) ((y - mRenderer.mFontLineSpacingAndAscent) / mRenderer.mFontLineSpacing);
        return new int[]{column, row};
    }
}
