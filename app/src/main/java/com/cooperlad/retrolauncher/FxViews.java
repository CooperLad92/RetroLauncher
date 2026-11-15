package com.cooperlad.retrolauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Custom controls and effects used by the skins. */
final class FxViews {
    private FxViews() { }

    /** Original pixel creature, generated from the app name so each app always gets the same one. */
    static Bitmap critter(String name) {
        final int n = 14, half = n / 2;
        Random r = new Random(name.hashCode() * 31L + 7);
        boolean[][] m = new boolean[n][n];
        float hue = r.nextInt(360);
        int body = Color.HSVToColor(new float[]{hue, 0.55f, 0.95f});
        int belly = Color.HSVToColor(new float[]{hue, 0.22f, 1f});
        int dark = Color.HSVToColor(new float[]{hue, 0.75f, 0.35f});
        for (int y = 1; y < n - 2; y++) {
            for (int x = 0; x < half; x++) {
                float dx = (half - 0.5f - x) / half, dy = (y - n * 0.55f) / (n * 0.5f);
                if (dx * dx + dy * dy < 0.5f + r.nextFloat() * 0.35f) { m[y][x] = true; m[y][n - 1 - x] = true; }
            }
        }
        if (r.nextBoolean()) { // ears or horns
            int ex = 1 + r.nextInt(half - 2);
            m[0][ex] = m[0][n - 1 - ex] = m[1][ex] = m[1][n - 1 - ex] = true;
        }
        int fx = 2 + r.nextInt(2); // feet
        m[n - 2][fx] = m[n - 2][n - 1 - fx] = m[n - 1][fx] = m[n - 1][n - 1 - fx] = true;
        int[] px = new int[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (m[y][x]) {
                    boolean isBelly = Math.abs(x - (half - 0.5f)) < 2.2f && y > n * 0.55f && y < n - 2;
                    px[y * n + x] = isBelly ? belly : body;
                } else if ((y > 0 && m[y - 1][x]) || (y < n - 1 && m[y + 1][x]) || (x > 0 && m[y][x - 1]) || (x < n - 1 && m[y][x + 1])) {
                    px[y * n + x] = dark;
                }
            }
        }
        int ey = 4 + r.nextInt(2), ex = half - 3 + r.nextInt(2);
        for (int k = 0; k < 2; k++) {
            int yy = ey + k;
            px[yy * n + ex] = 0xFF111111;
            px[yy * n + (n - 1 - ex)] = 0xFF111111;
        }
        int my = ey + 3;
        if (my < n) { px[my * n + half - 1] = dark; px[my * n + half] = dark; }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
    }

    /** Base for views that animate only while they are on screen. */
    abstract static class AnimView extends View {
        private final int period;
        private boolean running;
        private final Runnable frame = new Runnable() {
            @Override public void run() {
                if (!running) return;
                step();
                invalidate();
                postDelayed(this, period);
            }
        };

        AnimView(Context c, int periodMs) {
            super(c);
            period = periodMs;
        }

        void step() { }

        private void setRunning(boolean r) {
            if (running == r) return;
            running = r;
            removeCallbacks(frame);
            if (r) post(frame);
        }

        @Override protected void onWindowVisibilityChanged(int v) {
            super.onWindowVisibilityChanged(v);
            setRunning(v == VISIBLE && isAttachedToWindow());
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            setRunning(getWindowVisibility() == VISIBLE);
        }

        @Override protected void onDetachedFromWindow() {
            setRunning(false);
            super.onDetachedFromWindow();
        }
    }

    // ------------------------------------------------------------------ radio channel knob

    static final class DialView extends View {
        interface Listener { void step(int delta); void press(); }

        private final Listener l;
        private final Typeface tf;
        private final int fg, body, accent;
        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String readout = "CH ---", sub = "";
        private float angle, acc, lastA, downX, downY;
        private boolean moved;

        DialView(Context c, Typeface tf, int fg, int body, int accent, Listener l) {
            super(c);
            this.tf = tf;
            this.fg = fg;
            this.body = body;
            this.accent = accent;
            this.l = l;
            d = c.getResources().getDisplayMetrics().density;
        }

        void setReadout(String a, String b) {
            readout = a;
            sub = b;
            invalidate();
        }

        private float cx() { return getWidth() - getHeight() / 2f - 6 * d; }

        private float cy() { return getHeight() / 2f; }

        private float radius() { return getHeight() * 0.36f; }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), cx = cx(), cy = cy(), r = radius();
            // readout window
            RectF win = new RectF(0, h * 0.18f, cx - r - 22 * d, h * 0.82f);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF071003);
            c.drawRoundRect(win, 8 * d, 8 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(Skin.alpha(fg, 0x90));
            c.drawRoundRect(win, 8 * d, 8 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(tf);
            p.setColor(fg);
            p.setShadowLayer(4 * d, 0, 0, Skin.alpha(fg, 0xA0));
            p.setTextSize(h * 0.2f);
            c.drawText(readout, win.left + 12 * d, win.top + win.height() * 0.48f, p);
            p.setTextSize(h * 0.11f);
            c.drawText(sub, win.left + 12 * d, win.top + win.height() * 0.8f, p);
            p.clearShadowLayer();
            // detent ticks
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(Skin.alpha(fg, 0x80));
            for (int i = 0; i < 20; i++) {
                double a = Math.toRadians(i * 18);
                float r1 = r + 6 * d, r2 = r + 13 * d;
                c.drawLine(cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1,
                        cx + (float) Math.cos(a) * r2, cy + (float) Math.sin(a) * r2, p);
            }
            // knob with grip ridges
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF1A1C14);
            c.drawCircle(cx, cy, r, p);
            p.setColor(Skin.scale(body, 0.75f));
            c.drawCircle(cx, cy, r * 0.86f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f * d);
            p.setColor(0xFF111309);
            for (int i = 0; i < 28; i++) {
                double a = Math.toRadians(angle + i * (360.0 / 28));
                c.drawLine(cx + (float) Math.cos(a) * r * 0.86f, cy + (float) Math.sin(a) * r * 0.86f,
                        cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, p);
            }
            double pa = Math.toRadians(angle - 90);
            p.setStrokeWidth(4 * d);
            p.setColor(accent);
            c.drawLine(cx + (float) Math.cos(pa) * r * 0.42f, cy + (float) Math.sin(pa) * r * 0.42f,
                    cx + (float) Math.cos(pa) * r * 0.8f, cy + (float) Math.sin(pa) * r * 0.8f, p);
            // PTT cap
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2A2E20);
            c.drawCircle(cx, cy, r * 0.36f, p);
            p.setColor(fg);
            p.setTextSize(r * 0.22f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("PTT", cx, cy + r * 0.08f, p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        private float angleAt(float x, float y) {
            return (float) Math.toDegrees(Math.atan2(y - cy(), x - cx()));
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastA = angleAt(e.getX(), e.getY());
                    downX = e.getX();
                    downY = e.getY();
                    moved = false;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float a = angleAt(e.getX(), e.getY());
                    float da = a - lastA;
                    if (da > 180) da -= 360;
                    if (da < -180) da += 360;
                    lastA = a;
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) > 8 * d) moved = true;
                    angle += da;
                    acc += da;
                    int steps = (int) (acc / 18);
                    if (steps != 0) {
                        acc -= steps * 18;
                        l.step(steps);
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved && Math.hypot(e.getX() - cx(), e.getY() - cy()) < radius() * 0.5f) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        l.press();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    // ------------------------------------------------------------------ handheld D-pad and buttons

    static final class DPadView extends View {
        interface Listener { void key(int k); }

        static final int UP = 0, DOWN = 1, LEFT = 2, RIGHT = 3, A = 4, B = 5, SELECT = 6, START = 7;
        private final Listener l;
        private final Typeface tf;
        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF[] hit = new RectF[8];
        private float cx, cy, arm, len, br, ax, ay, bx, by;
        private int pressed = -1;
        private final Runnable repeat = new Runnable() {
            @Override public void run() {
                if (pressed == UP || pressed == DOWN) {
                    l.key(pressed);
                    postDelayed(this, 110);
                }
            }
        };

        DPadView(Context c, Typeface tf, Listener l) {
            super(c);
            this.tf = tf;
            this.l = l;
            d = c.getResources().getDisplayMetrics().density;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float s = Math.min(w, h);
            cx = w * 0.22f;
            cy = h * 0.42f;
            arm = s * 0.11f;
            len = arm * 2.8f;
            hit[UP] = new RectF(cx - arm, cy - len, cx + arm, cy - arm);
            hit[DOWN] = new RectF(cx - arm, cy + arm, cx + arm, cy + len);
            hit[LEFT] = new RectF(cx - len, cy - arm, cx - arm, cy + arm);
            hit[RIGHT] = new RectF(cx + arm, cy - arm, cx + len, cy + arm);
            br = s * 0.13f;
            ax = w * 0.84f;
            ay = h * 0.34f;
            bx = w * 0.64f;
            by = h * 0.5f;
            hit[A] = new RectF(ax - br, ay - br, ax + br, ay + br);
            hit[B] = new RectF(bx - br, by - br, bx + br, by + br);
            float pw = w * 0.085f, ph = h * 0.05f;
            hit[SELECT] = new RectF(w * 0.4f - pw, h * 0.86f - ph, w * 0.4f + pw, h * 0.86f + ph);
            hit[START] = new RectF(w * 0.58f - pw, h * 0.86f - ph, w * 0.58f + pw, h * 0.86f + ph);
        }

        @Override protected void onDraw(Canvas c) {
            if (hit[0] == null) return;
            // D-pad
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2A2A30);
            c.drawRoundRect(new RectF(cx - arm, cy - len, cx + arm, cy + len), arm * 0.35f, arm * 0.35f, p);
            c.drawRoundRect(new RectF(cx - len, cy - arm, cx + len, cy + arm), arm * 0.35f, arm * 0.35f, p);
            if (pressed >= UP && pressed <= RIGHT) {
                p.setColor(0xFF111114);
                c.drawRect(hit[pressed], p);
            }
            p.setColor(0xFF4A4A55);
            c.drawCircle(cx, cy, arm * 0.45f, p);
            arrow(c, cx, cy - len + arm * 0.9f, 0);
            arrow(c, cx, cy + len - arm * 0.9f, 180);
            arrow(c, cx - len + arm * 0.9f, cy, 270);
            arrow(c, cx + len - arm * 0.9f, cy, 90);
            // A / B
            button(c, ax, ay, "A", pressed == A);
            button(c, bx, by, "B", pressed == B);
            // SELECT / START
            pill(c, hit[SELECT], "SELECT", pressed == SELECT);
            pill(c, hit[START], "START", pressed == START);
            // speaker grille
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3 * d);
            p.setColor(0xFF9A9696);
            float gx = getWidth() * 0.8f, gy = getHeight() * 0.78f;
            for (int i = 0; i < 6; i++) {
                float o = i * 8 * d;
                c.drawLine(gx + o, gy + 18 * d, gx + o + 14 * d, gy - 8 * d, p);
            }
        }

        private void arrow(Canvas c, float x, float y, float rot) {
            c.save();
            c.rotate(rot, x, y);
            Path t = new Path();
            float s = arm * 0.35f;
            t.moveTo(x, y - s);
            t.lineTo(x + s, y + s * 0.6f);
            t.lineTo(x - s, y + s * 0.6f);
            t.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF55555F);
            c.drawPath(t, p);
            c.restore();
        }

        private void button(Canvas c, float x, float y, String label, boolean down) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF6E163C);
            c.drawCircle(x, y + 3 * d, br, p);
            p.setColor(down ? 0xFF6E163C : 0xFF9A2257);
            c.drawCircle(x, y + (down ? 3 * d : 0), br, p);
            p.setColor(0xFF2D2D6B);
            p.setTypeface(tf);
            p.setTextSize(br * 0.6f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(label, x + br * 0.2f, y + br + br * 0.75f, p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        private void pill(Canvas c, RectF r, String label, boolean down) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(down ? 0xFF5E5E66 : 0xFF8C8C94);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, p);
            p.setColor(0xFF2D2D6B);
            p.setTypeface(tf);
            p.setTextSize(r.height() * 0.75f);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText(label, r.centerX(), r.bottom + r.height() * 1.3f, p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        private int hitTest(float x, float y) {
            float slop = 10 * d;
            for (int i = 0; i < hit.length; i++) {
                if (hit[i] == null) continue;
                RectF r = new RectF(hit[i]);
                r.inset(-slop, -slop);
                if (r.contains(x, y)) return i;
            }
            return -1;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    int k = hitTest(e.getX(), e.getY());
                    if (k >= 0) {
                        pressed = k;
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        l.key(k);
                        if (k == UP || k == DOWN) postDelayed(repeat, 380);
                        invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pressed = -1;
                    removeCallbacks(repeat);
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }

    // ------------------------------------------------------------------ spectrum analyser waterfall

    static final class WaterfallView extends AnimView {
        static final int COLS = 96, ROWS = 48;
        private final int[] buf = new int[COLS * ROWS];
        private final float[] cur = new float[COLS];
        private final Bitmap bmp = Bitmap.createBitmap(COLS, ROWS, Bitmap.Config.ARGB_8888);
        private final Paint p = new Paint();
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random();
        private final List<String> markers = new ArrayList<>();
        private final float d;
        private float t;
        private static final int[] HEAT = {0xFF000814, 0xFF0033AA, 0xFF00C8FF, 0xFFFFE14D, 0xFFFF3B30};

        WaterfallView(Context c, Typeface tf, int fg) {
            super(c, 60);
            d = c.getResources().getDisplayMetrics().density;
            Arrays.fill(buf, HEAT[0]);
            p.setFilterBitmap(false);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.5f * d);
            line.setColor(fg);
            txt.setColor(fg);
            txt.setTypeface(tf);
            txt.setTextSize(10 * d);
            txt.setTextAlign(Paint.Align.CENTER);
        }

        void setMarkers(List<String> names) {
            markers.clear();
            for (int i = 0; i < names.size() && i < 6; i++) markers.add(names.get(i));
        }

        private int peakCol(int i, int n) { return n <= 1 ? COLS / 2 : 8 + i * (COLS - 16) / (n - 1); }

        @Override void step() {
            t += 0.06f;
            System.arraycopy(buf, 0, buf, COLS, COLS * (ROWS - 1));
            int n = markers.size();
            for (int x = 0; x < COLS; x++) {
                float v = rnd.nextFloat() * 0.16f;
                for (int i = 0; i < n; i++) {
                    float dx = x - peakCol(i, n);
                    float amp = 0.55f + 0.3f * (float) Math.sin(t * (1.3f + i * 0.37f) + i) + rnd.nextFloat() * 0.1f;
                    v += amp * (float) Math.exp(-dx * dx / 3f);
                }
                v = Math.min(1f, v);
                cur[x] = v;
                buf[x] = heat(v);
            }
            bmp.setPixels(buf, 0, COLS, 0, 0, COLS, ROWS);
        }

        private static int heat(float v) {
            float f = v * 4;
            int i = Math.min(3, (int) f);
            return lerp(HEAT[i], HEAT[i + 1], f - i);
        }

        private static int lerp(int a, int b, float f) {
            int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * f);
            int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * f);
            int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * f);
            return 0xFF000000 | (r << 16) | (g << 8) | bl;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), top = h * 0.4f;
            Path path = new Path();
            for (int x = 0; x < COLS; x++) {
                float px = x * (w - 1) / (COLS - 1), py = top - cur[x] * (top - 16 * d);
                if (x == 0) path.moveTo(px, py);
                else path.lineTo(px, py);
            }
            c.drawPath(path, line);
            int n = markers.size();
            for (int i = 0; i < n; i++) {
                float px = peakCol(i, n) * (w - 1) / (COLS - 1);
                px = Math.max(24 * d, Math.min(w - 24 * d, px));
                String s = markers.get(i);
                c.drawText(s.length() > 7 ? s.substring(0, 7) : s, px, 11 * d, txt);
            }
            c.drawBitmap(bmp, null, new RectF(0, top, w, h), p);
        }
    }

    // ------------------------------------------------------------------ mainframe front panel lamps

    static final class LightsView extends AnimView {
        private static final int N = 16;
        private final boolean[][] on = new boolean[2][N];
        private final Random r = new Random();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private final int lamp, label;

        LightsView(Context c, Typeface tf, int lamp, int label) {
            super(c, 160);
            this.lamp = lamp;
            this.label = label;
            d = c.getResources().getDisplayMetrics().density;
            p.setTypeface(tf);
        }

        @Override void step() {
            for (int k = 0; k < 4; k++) on[r.nextInt(2)][r.nextInt(N)] ^= true;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), lx = 52 * d;
            float gap = (w - lx) / N, rad = Math.min(gap * 0.32f, h * 0.16f);
            String[] names = {"ADDR", "DATA"};
            for (int row = 0; row < 2; row++) {
                float y = h * (row == 0 ? 0.3f : 0.72f);
                p.setColor(label);
                p.setTextSize(11 * d);
                c.drawText(names[row], 0, y + 4 * d, p);
                for (int i = 0; i < N; i++) {
                    float x = lx + gap * i + gap / 2;
                    if (on[row][i]) {
                        p.setColor(Skin.alpha(lamp, 0x55));
                        c.drawCircle(x, y, rad * 1.7f, p);
                        p.setColor(lamp);
                    } else {
                        p.setColor(Skin.scale(lamp, 0.22f));
                    }
                    c.drawCircle(x, y, rad, p);
                }
            }
        }
    }

    // ------------------------------------------------------------------ mainframe tape reels (launch effect)

    static final class ReelView extends AnimView {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String[] lines;
        private final float d;
        private final int fg;
        private float angle;

        ReelView(Context c, Typeface tf, int fg, String text) {
            super(c, 30);
            this.fg = fg;
            lines = text.split("\n");
            d = c.getResources().getDisplayMetrics().density;
            p.setTypeface(tf);
        }

        @Override void step() { angle += 16; }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = w * 0.17f, y = h * 0.38f;
            c.drawColor(0xE80E1013);
            float[] xs = {w * 0.3f, w * 0.7f};
            for (int k = 0; k < 2; k++) {
                float x = xs[k];
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF2B2E33);
                c.drawCircle(x, y, r, p);
                p.setColor(0xFF4A3A22);
                c.drawCircle(x, y, r * (k == 0 ? 0.8f : 0.55f), p); // tape pack moves between reels
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3 * d);
                p.setColor(fg);
                c.drawCircle(x, y, r, p);
                c.drawCircle(x, y, r * 0.2f, p);
                float a0 = angle * (k == 0 ? 1f : 1.4f);
                for (int s = 0; s < 3; s++) {
                    double a = Math.toRadians(a0 + s * 120);
                    c.drawLine(x + (float) Math.cos(a) * r * 0.2f, y + (float) Math.sin(a) * r * 0.2f,
                            x + (float) Math.cos(a) * r * 0.9f, y + (float) Math.sin(a) * r * 0.9f, p);
                }
            }
            p.setStrokeWidth(2 * d);
            p.setColor(0xFF7A5A30);
            c.drawLine(xs[0], y + r, w / 2 - 10 * d, y + r + 24 * d, p);
            c.drawLine(xs[1], y + r, w / 2 + 10 * d, y + r + 24 * d, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF8A8F99);
            c.drawRect(w / 2 - 16 * d, y + r + 20 * d, w / 2 + 16 * d, y + r + 34 * d, p);
            p.setColor(fg);
            p.setTextSize(16 * d);
            p.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < lines.length; i++) c.drawText(lines[i], w / 2, h * 0.72f + i * 24 * d, p);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    // ------------------------------------------------------------------ starfield screensaver

    static final class StarfieldView extends AnimView {
        private static final int N = 180;
        private final float[] x = new float[N], y = new float[N], z = new float[N];
        private final Random r = new Random();
        private final Paint p = new Paint();
        private final float d;

        StarfieldView(Context c) {
            super(c, 33);
            d = c.getResources().getDisplayMetrics().density;
            for (int i = 0; i < N; i++) reset(i, true);
        }

        private void reset(int i, boolean anyDepth) {
            x[i] = (r.nextFloat() - 0.5f) * 2;
            y[i] = (r.nextFloat() - 0.5f) * 2;
            z[i] = anyDepth ? 0.05f + r.nextFloat() : 1f;
        }

        @Override void step() {
            for (int i = 0; i < N; i++) {
                z[i] -= 0.012f;
                if (z[i] <= 0.02f) reset(i, false);
            }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            c.drawColor(0xFF000000);
            for (int i = 0; i < N; i++) {
                float sx = cx + x[i] / z[i] * w * 0.5f, sy = cy + y[i] / z[i] * w * 0.5f;
                if (sx < 0 || sx > w || sy < 0 || sy > h) continue;
                float b = Math.max(0f, Math.min(1f, 1 - z[i]));
                int v = (int) (80 + 175 * b);
                p.setColor(0xFF000000 | (v << 16) | (v << 8) | v);
                float s = (0.6f + b * 2.4f) * d;
                c.drawRect(sx, sy, sx + s, sy + s, p);
            }
        }
    }
}
