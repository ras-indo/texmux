package texmux.app.terminal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * A custom HSV color picker View with a saturation/value square panel
 * on the left and a vertical hue slider on the right.
 * <p>
 * Usage:
 * <pre>
 * HsvColorPickerView picker = new HsvColorPickerView(context);
 * picker.setColor(0xFF6750A4);
 * picker.setOnColorChangedListener(color -> { ... });
 * </pre>
 */
public class HsvColorPickerView extends View {

    public interface OnColorChangedListener {
        void onColorChanged(int color);
    }

    public interface OnColorSelectedListener {
        void onColorSelected(int color);
    }

    private static final float SAT_VAL_SIZE_DP = 200f;
    private static final float HUE_SLIDER_WIDTH_DP = 30f;
    private static final float PADDING_BETWEEN_DP = 8f;
    private static final float CROSSHAIR_RADIUS_DP = 8f;
    private static final float THUMB_RADIUS_DP = 10f;
    private static final float BORDER_STROKE_DP = 1f;
    private static final float THUMB_BORDER_STROKE_DP = 2.5f;

    private final float mDensity;

    /**
     * Current HSV values.
     * Hue: 0..360, Sat: 0..1, Val: 0..1.
     */
    private float mCurrentHue;
    private float mCurrentSat;
    private float mCurrentVal;

    private OnColorChangedListener mListener;
    private OnColorSelectedListener mSelectListener;
    private boolean mIsTrackingTouch;

    // Layout bounds computed in onSizeChanged
    private final RectF mSatValRect = new RectF();
    private final RectF mHueSliderRect = new RectF();

    // Cached saturation/value bitmap (recreated when hue changes)
    private Bitmap mSatValBitmap;
    private boolean mSatValDirty;

    // Pre-allocated paints
    private final Paint mSatValPaint;
    private final Paint mCrosshairFillPaint;
    private final Paint mCrosshairBorderPaint;
    private final Paint mHueSliderPaint;
    private final Paint mThumbFillPaint;
    private final Paint mThumbBorderPaint;
    private final Paint mBorderPaint;

    // Pre-allocated array for HSV conversion
    private final float[] mHsvTemp;

    // Hue slider gradient colors (rainbow: red -> yellow -> green -> cyan -> blue -> magenta -> red)
    private static final int HUE_STEPS = 7;
    private static final int[] HUE_COLORS = new int[HUE_STEPS];
    private static final float[] HUE_POSITIONS = new float[HUE_STEPS];

    static {
        for (int i = 0; i < HUE_STEPS; i++) {
            float hue = (i * 60f) % 360f;
            HUE_COLORS[i] = Color.HSVToColor(new float[]{hue, 1f, 1f});
            HUE_POSITIONS[i] = (float) i / (float) (HUE_STEPS - 1);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────────

    public HsvColorPickerView(Context context) {
        this(context, null);
    }

    public HsvColorPickerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HsvColorPickerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDensity = context.getResources().getDisplayMetrics().density;

        mCurrentHue = 270f;   // start at purple/blue
        mCurrentSat = 1f;
        mCurrentVal = 1f;
        mSatValDirty = true;
        mHsvTemp = new float[3];

        // ── Sat/Val panel paint ──
        mSatValPaint = new Paint(Paint.DITHER_FLAG);

        // ── Crosshair paint ──
        final float crosshairRadius = CROSSHAIR_RADIUS_DP * mDensity;

        mCrosshairFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mCrosshairFillPaint.setStyle(Paint.Style.FILL);
        mCrosshairFillPaint.setColor(Color.WHITE);

        mCrosshairBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mCrosshairBorderPaint.setStyle(Paint.Style.STROKE);
        mCrosshairBorderPaint.setStrokeWidth(THUMB_BORDER_STROKE_DP * mDensity);
        mCrosshairBorderPaint.setColor(Color.argb(200, 0, 0, 0));

        // ── Hue slider paint (shader will be set in onSizeChanged) ──
        mHueSliderPaint = new Paint(Paint.DITHER_FLAG);

        // ── Thumb paint ──
        mThumbFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mThumbFillPaint.setStyle(Paint.Style.FILL);
        mThumbFillPaint.setColor(Color.WHITE);

        mThumbBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mThumbBorderPaint.setStyle(Paint.Style.STROKE);
        mThumbBorderPaint.setStrokeWidth(THUMB_BORDER_STROKE_DP * mDensity);
        mThumbBorderPaint.setColor(Color.argb(200, 0, 0, 0));

        // ── Border paint (subtle outline around both panels) ──
        mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(BORDER_STROKE_DP * mDensity);
        mBorderPaint.setColor(Color.argb(60, 0, 0, 0));

        setFocusable(true);
        setClickable(true);
    }

    // ─────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Register a callback invoked when the user changes the selected color (fires on every drag move).
     */
    public void setOnColorChangedListener(OnColorChangedListener listener) {
        mListener = listener;
    }

    /**
     * Register a callback invoked when the user finalizes a color selection (fires on ACTION_UP only).
     */
    public void setOnColorSelectedListener(OnColorSelectedListener listener) {
        mSelectListener = listener;
    }

    /**
     * Set the current color from an ARGB integer.
     * Alpha is ignored internally; the picker always operates on fully opaque colors.
     */
    public void setColor(int color) {
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);

        float[] hsv = mHsvTemp;
        Color.RGBToHSV(red, green, blue, hsv);

        mCurrentHue = hsv[0];
        mCurrentSat = hsv[1];
        mCurrentVal = hsv[2];

        mSatValDirty = true;

        if (getWidth() > 0 && getHeight() > 0) {
            regenerateSatValBitmap();
        }

        invalidate();
    }

    /**
     * Return the current color as a fully opaque ARGB integer.
     */
    public int getColor() {
        float[] hsv = mHsvTemp;
        hsv[0] = mCurrentHue;
        hsv[1] = mCurrentSat;
        hsv[2] = mCurrentVal;
        return 0xFF000000 | Color.HSVToColor(hsv);
    }

    // ─────────────────────────────────────────────────────────────────
    // Measurement & Layout
    // ─────────────────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float minWidth = (SAT_VAL_SIZE_DP + PADDING_BETWEEN_DP + HUE_SLIDER_WIDTH_DP) * mDensity;
        float minHeight = SAT_VAL_SIZE_DP * mDensity;

        int minW = (int) (minWidth + 0.5f);
        int minH = (int) (minHeight + 0.5f);

        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        int width;
        int height;

        if (widthMode == MeasureSpec.EXACTLY) {
            width = Math.max(widthSize, minW);
        } else if (widthMode == MeasureSpec.AT_MOST) {
            width = Math.min(widthSize, Math.max(minW, getSuggestedMinimumWidth()));
        } else {
            width = minW;
        }

        if (heightMode == MeasureSpec.EXACTLY) {
            height = Math.max(heightSize, minH);
        } else if (heightMode == MeasureSpec.AT_MOST) {
            height = Math.min(heightSize, Math.max(minH, getSuggestedMinimumHeight()));
        } else {
            height = minH;
        }

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        final float hueWidth = HUE_SLIDER_WIDTH_DP * mDensity;
        final float padding = PADDING_BETWEEN_DP * mDensity;

        // The sat/val panel is square; size is driven by available height
        float satValSize = h;
        float totalWidthNeeded = satValSize + padding + hueWidth;

        // If the view is wider than needed, the extra space goes to the sat/val panel
        if (totalWidthNeeded < w) {
            satValSize = w - padding - hueWidth;
        }

        // Center vertically
        float top = (h - satValSize) / 2f;
        float left = 0f;

        mSatValRect.set(left, top, left + satValSize, top + satValSize);

        float sliderLeft = mSatValRect.right + padding;
        mHueSliderRect.set(sliderLeft, mSatValRect.top, sliderLeft + hueWidth, mSatValRect.bottom);

        // Rebuild the hue slider gradient shader for the new height
        if (mHueSliderRect.height() > 0f) {
            LinearGradient gradient = new LinearGradient(
                0f, mHueSliderRect.top,
                0f, mHueSliderRect.bottom,
                HUE_COLORS, HUE_POSITIONS,
                Shader.TileMode.CLAMP
            );
            mHueSliderPaint.setShader(gradient);
        }

        mSatValDirty = true;
        regenerateSatValBitmap();
    }

    // ─────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Regenerate the bitmap if the hue changed while the view was off-screen
        if (mSatValDirty) {
            regenerateSatValBitmap();
        }

        drawSatValPanel(canvas);
        drawHueSlider(canvas);
    }

    /**
     * Generate (or regenerate) the cached sat/val Bitmap for the current hue.
     * Uses int[] pixel buffer for ~100x faster generation vs individual setPixel() calls.
     */
    private void regenerateSatValBitmap() {
        int width = (int) mSatValRect.width();
        int height = (int) mSatValRect.height();

        if (width <= 0 || height <= 0) {
            mSatValDirty = true;
            return;
        }

        final float hue = mCurrentHue;
        final int[] pixels = new int[width * height];
        final float[] hsv = new float[3];
        hsv[0] = hue;

        final float w1 = width - 1;
        final float h1 = height - 1;

        int idx = 0;
        for (int y = 0; y < height; y++) {
            hsv[2] = 1f - y / h1;
            for (int x = 0; x < width; x++) {
                hsv[1] = x / w1;
                pixels[idx++] = 0xFF000000 | Color.HSVToColor(hsv);
            }
        }

        // Recycle the old bitmap
        if (mSatValBitmap != null && !mSatValBitmap.isRecycled()) {
            mSatValBitmap.recycle();
        }

        mSatValBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        mSatValDirty = false;
    }

    /**
     * Draw the saturation/value square panel with a crosshair indicator.
     */
    private void drawSatValPanel(Canvas canvas) {
        if (mSatValBitmap == null || mSatValBitmap.isRecycled()) return;

        final float left = mSatValRect.left;
        final float top = mSatValRect.top;

        // Draw the cached gradient bitmap
        canvas.drawBitmap(mSatValBitmap, left, top, mSatValPaint);

        // Subtle border
        canvas.drawRect(mSatValRect, mBorderPaint);

        // Crosshair position
        final float cx = left + mCurrentSat * mSatValRect.width();
        final float cy = top + (1f - mCurrentVal) * mSatValRect.height();
        final float radius = CROSSHAIR_RADIUS_DP * mDensity;

        // Outer border circle
        canvas.drawCircle(cx, cy, radius, mCrosshairBorderPaint);
        // Inner fill circle
        canvas.drawCircle(cx, cy, radius - (THUMB_BORDER_STROKE_DP * mDensity * 0.5f), mCrosshairFillPaint);
    }

    /**
     * Draw the vertical hue slider with a circular thumb indicator.
     */
    private void drawHueSlider(Canvas canvas) {
        final RectF rect = mHueSliderRect;

        // Draw the rainbow gradient using the pre-built LinearGradient shader
        canvas.drawRect(rect, mHueSliderPaint);

        // Subtle border
        canvas.drawRect(rect, mBorderPaint);

        // Thumb position
        final float thumbY = rect.top + (mCurrentHue / 360f) * rect.height();
        final float thumbX = rect.centerX();
        final float radius = THUMB_RADIUS_DP * mDensity;

        // Outer border circle
        canvas.drawCircle(thumbX, thumbY, radius, mThumbBorderPaint);
        // Inner fill circle
        canvas.drawCircle(thumbX, thumbY, radius - (THUMB_BORDER_STROKE_DP * mDensity * 0.5f), mThumbFillPaint);
    }

    // ─────────────────────────────────────────────────────────────────
    // Touch Handling
    // ─────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final float x = event.getX();
        final float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mIsTrackingTouch = true;
                if (!resolveTouch(x, y)) {
                    mIsTrackingTouch = false;
                    return super.onTouchEvent(event);
                }
                fireColorChanged();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (mIsTrackingTouch) {
                    resolveTouch(x, y);
                    fireColorChanged();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_UP:
                if (mIsTrackingTouch) {
                    mIsTrackingTouch = false;
                    resolveTouch(x, y);
                    fireColorChanged();
                    fireColorSelected();
                    performClick();
                    return true;
                }
                return false;

            case MotionEvent.ACTION_CANCEL:
                mIsTrackingTouch = false;
                return true;
        }

        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /**
     * Resolve the touch coordinates and update the appropriate component.
     *
     * @return true if the touch hit a valid area, false otherwise.
     */
    private boolean resolveTouch(float x, float y) {
        if (mSatValRect.contains(x, y)) {
            float sat = (x - mSatValRect.left) / mSatValRect.width();
            float val = 1f - (y - mSatValRect.top) / mSatValRect.height();

            mCurrentSat = clamp01(sat);
            mCurrentVal = clamp01(val);

            invalidate();
            return true;
        }

        if (mHueSliderRect.contains(x, y)) {
            float frac = (y - mHueSliderRect.top) / mHueSliderRect.height();
            mCurrentHue = clamp01(frac) * 360f;
            mSatValDirty = true;

            invalidate();
            return true;
        }

        return false;
    }

    /**
     * Notify the listener of the current color (fires on every change while dragging).
     */
    private void fireColorChanged() {
        if (mListener != null) {
            mListener.onColorChanged(getColor());
        }
    }

    /**
     * Notify the select listener that the user has finalized a color (fires on ACTION_UP only).
     */
    private void fireColorSelected() {
        if (mSelectListener != null) {
            mSelectListener.onColorSelected(getColor());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────

    private static float clamp01(float value) {
        if (value < 0f) return 0f;
        if (value > 1f) return 1f;
        return value;
    }
}
