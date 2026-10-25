package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * The NAS photo frame in the empty space between the clock and the dock's big name. Each photo arrives
 * already scaled and feathered (feather(), on a worker thread), so a change of photo is two bitmaps
 * crossfading and nothing is drawn between changes.
 */
public final class PhotoFrameView extends View {
    private static final int FADE_MS = 1200;

    private final Rect box = new Rect();
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();
    private String focusHint = "";
    private Bitmap current;
    private Bitmap previous;
    private String caption = "";
    private String previousCaption = "";
    private float mix = 1f;
    private ValueAnimator fade;
    private boolean animate = true;

    public PhotoFrameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        captionPaint.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
        captionPaint.setTextAlign(Paint.Align.RIGHT);
        captionPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        captionPaint.setShadowLayer(4 * density, 0, density, 0x99000000);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2.5f * density);
        ringPaint.setColor(0xD9FFFFFF);
        ringPaint.setShadowLayer(10 * density, 0, 0, 0x66FFFFFF);
        setWillNotDraw(false);
        // Reached with Up from the dock (MainActivity), only while a photo is on screen.
        setFocusable(false);
    }

    /** Shown in place of the folder name while the frame has focus, e.g. "OK: options". */
    void setFocusHint(String hint) {
        focusHint = hint == null ? "" : hint;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();
    }

    void setAnimate(boolean animate) {
        this.animate = animate;
    }

    /** The space the photo may use, in this view's coordinates. Photos sit against its right edge. */
    void setBox(int left, int top, int right, int bottom) {
        if (box.left == left && box.top == top && box.right == right && box.bottom == bottom) return;
        box.set(left, top, right, bottom);
        invalidate();
    }

    int boxWidth() {
        return box.width();
    }

    int boxHeight() {
        return box.height();
    }

    /** Crossfades to this photo (already made by feather() for the current box). */
    void show(Bitmap photo, String label) {
        if (fade != null) fade.cancel();
        previous = current;
        previousCaption = caption;
        current = photo;
        caption = label == null ? "" : label;
        setFocusable(true);
        if (!animate || previous == null) {
            previous = null;
            mix = 1f;
            invalidate();
            return;
        }
        mix = 0f;
        fade = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
        fade.addUpdateListener(a -> {
            mix = (float) a.getAnimatedValue();
            invalidate();
        });
        fade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                previous = null;
                invalidate();
            }
        });
        fade.start();
    }

    /** Drops both photos (the switch went off, or memory is short). */
    void clear() {
        if (fade != null) fade.cancel();
        current = null;
        previous = null;
        caption = "";
        setFocusable(false);
        invalidate();
    }

    boolean hasPhoto() {
        return current != null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (box.isEmpty()) return;
        if (previous != null && mix < 1f) draw(canvas, previous, previousCaption, 1f - mix);
        if (current != null) draw(canvas, current, isFocused() && !focusHint.isEmpty() ? focusHint : caption,
                previous != null ? mix : 1f);
        if (current != null && isFocused()) {
            // The feathered edges fade out over about a fifth of the photo; the ring sits where it is still solid.
            float inset = 0.06f * Math.min(current.getWidth(), current.getHeight());
            int x = box.right - current.getWidth();
            int y = box.top + (box.height() - current.getHeight()) / 2;
            ring.set(x + inset * 2.5f, y + inset, box.right - inset, y + current.getHeight() - inset * 1.5f);
            float r = 14 * getResources().getDisplayMetrics().density;
            canvas.drawRoundRect(ring, r, r, ringPaint);
        }
    }

    private void draw(Canvas canvas, Bitmap photo, String label, float alpha) {
        int x = box.right - photo.getWidth();
        int y = box.top + (box.height() - photo.getHeight()) / 2;
        photoPaint.setAlpha(Math.round(255 * alpha));
        canvas.drawBitmap(photo, x, y, photoPaint);
        if (!label.isEmpty()) {
            captionPaint.setColor(Math.round(0xB3 * alpha) << 24 | 0xFFFFFF);
            float pad = 10 * getResources().getDisplayMetrics().density;
            canvas.drawText(label, box.right - pad, y + photo.getHeight() - pad, captionPaint);
        }
    }

    /**
     * Scales src to the box's height (cropping the middle of a panorama wider than the box), then fades every
     * edge to transparent: widest on the left, where the dock app's name sits, and at the bottom, toward the
     * dock, so the photo melts into the backdrop with no visible border.
     */
    static Bitmap feather(Bitmap src, int boxW, int boxH) {
        float scale = (float) boxH / src.getHeight();
        int w = Math.min(boxW, Math.max(1, Math.round(src.getWidth() * scale)));
        int h = boxH;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        float drawnW = src.getWidth() * scale;
        float dx = (w - drawnW) / 2f;   // negative for a cropped panorama: keep its middle
        c.save();
        c.translate(dx, 0);
        c.scale(scale, scale);
        c.drawBitmap(src, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        c.restore();

        Paint erase = new Paint();
        erase.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        int left = Math.round(w * 0.30f);
        int right = Math.round(w * 0.12f);
        int top = Math.round(h * 0.20f);
        int bottom = Math.round(h * 0.26f);
        fade(c, erase, 0, 0, left, h, 0, 0, left, 0);
        fade(c, erase, w - right, 0, w, h, w, 0, w - right, 0);
        fade(c, erase, 0, 0, w, top, 0, 0, 0, top);
        fade(c, erase, 0, h - bottom, w, h, 0, h, 0, h - bottom);
        return out;
    }

    /** Erases a strip: fully at (x0,y0), not at all at (x1,y1), easing in between. */
    private static void fade(Canvas c, Paint erase, int l, int t, int r, int b, float x0, float y0, float x1, float y1) {
        if (r <= l || b <= t) return;
        erase.setShader(new LinearGradient(x0, y0, x1, y1,
                new int[]{0xFF000000, 0xB0000000, 0x40000000, 0x00000000},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(l, t, r, b, erase);
    }
}
