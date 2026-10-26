package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * This fork's weather over the backdrop, after Lumen: rain streaks, drifting snow, a thunderstorm's flashes, slow
 * clouds or fog, soft light shafts on a clear day, and twinkling stars with fireflies on a clear night. It follows
 * the status line's weather (Weather), at the Appearance page's strength. Off by default; about 25 frames a second
 * while Home is on screen and none otherwise, and a single still frame with reduced motion.
 */
public final class WeatherEffectsView extends View {
    private enum Kind { NONE, RAIN, SNOW, STORM, CLOUDS, FOG, SUN, PARTLY_DAY, NIGHT, PARTLY_NIGHT }

    private static final long FRAME_MS = 40;   // 25 fps: the whole of Home recomposites each frame, about 14 ms of GPU on a MiTV-MOEU0

    private final Random random = new Random(7);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sprite = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint beam = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final float density;

    private Kind kind = Kind.NONE;
    private float strength;   // 0..1
    private boolean animate = true;
    private boolean running;
    private long lastFrame;
    private float time;       // seconds since start

    // One set of particles, used by whichever effect is on: x, y, speed, size, phase.
    private float[] px = new float[0], py = px, pv = px, ps = px, pp = px;
    // Clouds or fog banks: x, y, width, speed, alpha.
    private float[] cx = new float[0], cy = cx, cw = cx, cv = cx, ca = cx;
    private float[] sx = new float[0], sy = sx, sp = sx;   // stars
    private Bitmap blob, glow;
    private float flash;
    private float nextFlash = 4f;

    public WeatherEffectsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        line.setStrokeCap(Paint.Cap.ROUND);
        setWillNotDraw(false);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /** The weather to show and how strongly (0 to 100); 0 or no weather hides the view. */
    void set(Weather.Now now, int percent, boolean animations) {
        strength = Math.max(0, Math.min(100, percent)) / 100f;
        animate = animations;
        kind = now == null || strength == 0 ? Kind.NONE : kindOf(now);
        setVisibility(kind == Kind.NONE ? GONE : VISIBLE);
        seed();
        invalidate();
    }

    void start() {
        if (running || kind == Kind.NONE) return;
        running = true;
        lastFrame = SystemClock.uptimeMillis();
        invalidate();
    }

    void stop() {
        running = false;
    }

    private static Kind kindOf(Weather.Now n) {
        int c = n.code;
        if (c >= 95) return Kind.STORM;
        if (c >= 71 && c <= 77 || c == 85 || c == 86) return Kind.SNOW;
        if (c >= 51 && c <= 67 || c >= 80 && c <= 82) return Kind.RAIN;
        if (c == 45 || c == 48) return Kind.FOG;
        if (c == 3) return Kind.CLOUDS;
        if (c == 1 || c == 2) return n.day ? Kind.PARTLY_DAY : Kind.PARTLY_NIGHT;
        return n.day ? Kind.SUN : Kind.NIGHT;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        seed();
    }

    /** Particles for the current kind, spread over the screen so the first frame already looks settled. */
    private void seed() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        int n;
        switch (kind) {
            case RAIN: case STORM: n = Math.round(40 + 110 * strength); break;
            case SNOW: n = Math.round(25 + 75 * strength); break;
            case SUN: case PARTLY_DAY: n = Math.round(6 + 10 * strength); break;   // dust in the light
            case NIGHT: case PARTLY_NIGHT: n = Math.round(6 + 10 * strength); break;   // fireflies
            default: n = 0;
        }
        px = new float[n]; py = new float[n]; pv = new float[n]; ps = new float[n]; pp = new float[n];
        for (int i = 0; i < n; i++) respawn(i, w, h, true);

        int clouds = kind == Kind.FOG ? 6 : kind == Kind.CLOUDS || kind == Kind.STORM || kind == Kind.RAIN ? 5
                : kind == Kind.PARTLY_DAY || kind == Kind.PARTLY_NIGHT ? 3 : kind == Kind.SNOW ? 3 : 0;
        cx = new float[clouds]; cy = new float[clouds]; cw = new float[clouds]; cv = new float[clouds]; ca = new float[clouds];
        for (int i = 0; i < clouds; i++) {
            cw[i] = w * (0.35f + random.nextFloat() * 0.35f);
            cx[i] = random.nextFloat() * (w + cw[i]) - cw[i];
            cy[i] = kind == Kind.FOG ? h * (0.35f + random.nextFloat() * 0.5f) : h * (0.02f + random.nextFloat() * 0.38f);
            cv[i] = (5 + random.nextFloat() * 10) * density;
            ca[i] = (kind == Kind.FOG ? 0.16f : 0.09f) * (0.6f + random.nextFloat() * 0.4f);
        }

        int stars = kind == Kind.NIGHT ? Math.round(40 + 60 * strength) : kind == Kind.PARTLY_NIGHT ? 30 : 0;
        sx = new float[stars]; sy = new float[stars]; sp = new float[stars];
        for (int i = 0; i < stars; i++) {
            sx[i] = random.nextFloat() * w;
            sy[i] = random.nextFloat() * h * 0.55f;
            sp[i] = random.nextFloat() * 6.28f;
        }
        if (blob == null && clouds > 0) blob = softDisc(128, 0xFFFFFFFF);
        if (glow == null && (kind == Kind.NIGHT || kind == Kind.PARTLY_NIGHT)) glow = softDisc(32, 0xFFFFE680);
    }

    private void respawn(int i, int w, int h, boolean anywhere) {
        switch (kind) {
            case RAIN: case STORM:
                px[i] = random.nextFloat() * w * 1.2f;
                py[i] = anywhere ? random.nextFloat() * h : -random.nextFloat() * h * 0.2f;
                pv[i] = (700 + random.nextFloat() * 500) * density / 2f;   // px per second
                ps[i] = (10 + random.nextFloat() * 14) * density;           // streak length
                break;
            case SNOW:
                px[i] = random.nextFloat() * w;
                py[i] = anywhere ? random.nextFloat() * h : -10 * density;
                pv[i] = (18 + random.nextFloat() * 30) * density;
                ps[i] = (1.2f + random.nextFloat() * 2.4f) * density;
                pp[i] = random.nextFloat() * 6.28f;
                break;
            default:   // dust motes and fireflies wander
                px[i] = random.nextFloat() * w;
                py[i] = kind == Kind.SUN || kind == Kind.PARTLY_DAY ? random.nextFloat() * h * 0.6f
                        : h * (0.45f + random.nextFloat() * 0.5f);
                pv[i] = (6 + random.nextFloat() * 10) * density;
                ps[i] = (1.5f + random.nextFloat() * 2f) * density;
                pp[i] = random.nextFloat() * 6.28f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (kind == Kind.NONE) return;
        int w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        float dt = running && animate ? Math.min(0.1f, (now - lastFrame) / 1000f) : 0f;
        lastFrame = now;
        time += dt;

        switch (kind) {
            case SUN: drawBeams(canvas, w, h, 1f); drawMotes(canvas, w, h, dt, false); break;
            case PARTLY_DAY: drawBeams(canvas, w, h, 0.6f); drawClouds(canvas, w, dt); drawMotes(canvas, w, h, dt, false); break;
            case NIGHT: drawStars(canvas); drawMotes(canvas, w, h, dt, true); break;
            case PARTLY_NIGHT: drawStars(canvas); drawClouds(canvas, w, dt); drawMotes(canvas, w, h, dt, true); break;
            case CLOUDS: case FOG: drawClouds(canvas, w, dt); break;
            case RAIN: drawClouds(canvas, w, dt); drawRain(canvas, w, h, dt); break;
            case STORM: drawClouds(canvas, w, dt); drawRain(canvas, w, h, dt); drawFlash(canvas, w, h, dt); break;
            case SNOW: drawClouds(canvas, w, dt); drawSnow(canvas, w, h, dt); break;
            default: break;
        }
        if (running && animate) postInvalidateDelayed(FRAME_MS);
    }

    private void drawRain(Canvas c, int w, int h, float dt) {
        line.setStrokeWidth(1.4f * density);
        line.setColor(alpha(0xFFBFD8FF, 0.22f + 0.3f * strength));
        for (int i = 0; i < px.length; i++) {
            py[i] += pv[i] * dt;
            px[i] -= pv[i] * dt * 0.18f;
            if (py[i] > h || px[i] < -20) respawn(i, w, h, false);
            c.drawLine(px[i], py[i], px[i] + ps[i] * 0.18f, py[i] - ps[i], line);
        }
    }

    private void drawSnow(Canvas c, int w, int h, float dt) {
        dot.setColor(alpha(0xFFFFFFFF, 0.35f + 0.45f * strength));
        for (int i = 0; i < px.length; i++) {
            py[i] += pv[i] * dt;
            pp[i] += dt * 0.9f;
            if (py[i] > h) respawn(i, w, h, false);
            c.drawCircle(px[i] + (float) Math.sin(pp[i]) * 14 * density, py[i], ps[i], dot);
        }
    }

    private void drawFlash(Canvas c, int w, int h, float dt) {
        nextFlash -= dt;
        if (nextFlash <= 0) {
            flash = 0.10f + 0.18f * strength;
            nextFlash = 6 + random.nextFloat() * 10;
        }
        if (flash <= 0) return;
        c.drawColor(alpha(0xFFE8EEFF, flash));
        flash = Math.max(0, flash - dt * 0.9f);
    }

    private void drawClouds(Canvas c, int w, float dt) {
        if (blob == null) return;
        for (int i = 0; i < cx.length; i++) {
            cx[i] += cv[i] * dt;
            if (cx[i] > w) cx[i] = -cw[i];
            float hgt = cw[i] * (kind == Kind.FOG ? 0.22f : 0.32f);
            rect.set(cx[i], cy[i] - hgt / 2, cx[i] + cw[i], cy[i] + hgt / 2);
            sprite.setAlpha(Math.round(255 * ca[i] * (0.5f + strength)));
            c.drawBitmap(blob, null, rect, sprite);
        }
    }

    /** Three soft shafts from the top right, breathing slowly. */
    private void drawBeams(Canvas c, int w, int h, float amount) {
        for (int i = 0; i < 3; i++) {
            float breathe = 0.6f + 0.4f * (float) Math.sin(time * 0.35f + i * 1.7f);
            float a = (0.035f + 0.06f * strength) * amount * breathe;
            float x0 = w * (0.78f + i * 0.07f), spread = w * (0.10f + i * 0.04f);
            path.reset();
            path.moveTo(x0, 0);
            path.lineTo(x0 + w * 0.05f, 0);
            path.lineTo(x0 - w * 0.30f, h * 0.9f);
            path.lineTo(x0 - w * 0.30f - spread, h * 0.9f);
            path.close();
            beam.setShader(new LinearGradient(x0, 0, x0 - w * 0.3f, h * 0.9f,
                    alpha(0xFFFFF4D6, a), 0x00FFF4D6, Shader.TileMode.CLAMP));
            c.drawPath(path, beam);
        }
    }

    private void drawStars(Canvas c) {
        for (int i = 0; i < sx.length; i++) {
            float twinkle = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(time * 1.3f + sp[i]));
            dot.setColor(alpha(0xFFFFFFFF, (0.25f + 0.45f * strength) * twinkle));
            c.drawCircle(sx[i], sy[i], (0.7f + (i % 3) * 0.35f) * density, dot);
        }
    }

    /** Dust drifting in the light by day, fireflies pulsing low on the screen by night. */
    private void drawMotes(Canvas c, int w, int h, float dt, boolean fireflies) {
        for (int i = 0; i < px.length; i++) {
            pp[i] += dt * (fireflies ? 0.8f : 0.4f);
            px[i] += (float) Math.cos(pp[i]) * pv[i] * dt;
            py[i] += (float) Math.sin(pp[i] * 0.7f) * pv[i] * dt * 0.6f;
            if (px[i] < -20 || px[i] > w + 20 || py[i] < -20 || py[i] > h + 20) respawn(i, w, h, true);
            if (fireflies && glow != null) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(time * 2.1f + i);
                float r = ps[i] * 5;
                rect.set(px[i] - r, py[i] - r, px[i] + r, py[i] + r);
                sprite.setAlpha(Math.round(255 * (0.25f + 0.55f * strength) * pulse));
                c.drawBitmap(glow, null, rect, sprite);
            } else {
                dot.setColor(alpha(0xFFFFF6E0, 0.15f + 0.25f * strength));
                c.drawCircle(px[i], py[i], ps[i] * 0.6f, dot);
            }
        }
    }

    private static Bitmap softDisc(int size, int color) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(size / 2f, size / 2f, size / 2f,
                new int[]{color, color & 0x88FFFFFF, color & 0x00FFFFFF}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        new Canvas(b).drawCircle(size / 2f, size / 2f, size / 2f, p);
        return b;
    }

    private static int alpha(int color, float a) {
        return (Math.round(255 * Math.max(0, Math.min(1, a))) << 24) | (color & 0xFFFFFF);
    }
}
