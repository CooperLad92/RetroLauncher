package com.cooperlad.retrolauncher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import java.util.Random;

/** Hand-drawn backgrounds used by the skins. */
final class Drawables {
    private Drawables() { }

    static GradientDrawable box(int fill, int stroke, float strokePx, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (strokePx > 0) g.setStroke(Math.round(strokePx), stroke);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static Drawable bevel(boolean raised, int face, float d) { return new Bevel(raised, face, Math.max(1f, Math.round(d))); }

    static Drawable sticker(int fill, float d) { return new Sticker(fill, d); }

    static Drawable doubleBorder(int fill, int ink, float d) { return new DoubleBorder(fill, ink, d); }

    static Drawable punchCard(float d, int seed) { return new PunchCard(d, seed); }

    static Drawable memphis(int bg, float d) { return new Memphis(bg, d); }

    abstract static class Base extends Drawable {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 90s window bevel: light top-left, dark bottom-right (swapped when sunken). */
    static final class Bevel extends Base {
        final boolean raised;
        final int face;
        final float w;

        Bevel(boolean raised, int face, float w) {
            this.raised = raised;
            this.face = face;
            this.w = w;
            p.setAntiAlias(false);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            p.setColor(face);
            c.drawRect(b, p);
            edge(c, b.left, b.top, b.right, b.bottom,
                    raised ? 0xFFFFFFFF : 0xFF808080, raised ? 0xFF000000 : 0xFFFFFFFF);
            edge(c, b.left + w, b.top + w, b.right - w, b.bottom - w,
                    raised ? 0xFFDFDFDF : 0xFF000000, raised ? 0xFF808080 : 0xFFDFDFDF);
        }

        private void edge(Canvas c, float l, float t, float r, float b, int tl, int br) {
            p.setColor(tl);
            c.drawRect(l, t, r, t + w, p);
            c.drawRect(l, t, l + w, b, p);
            p.setColor(br);
            c.drawRect(l, b - w, r, b, p);
            c.drawRect(r - w, t, r, b, p);
        }
    }

    /** Cartoon sticker: chunky black outline with a hard drop shadow. */
    static final class Sticker extends Base {
        final int fill;
        final float d;

        Sticker(int fill, float d) { this.fill = fill; this.d = d; }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = 4 * d, r = 14 * d, sw = 3 * d;
            RectF body = new RectF(b.left + sw / 2, b.top + sw / 2, b.right - s - sw / 2, b.bottom - s - sw / 2);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF111111);
            c.drawRoundRect(new RectF(body.left + s, body.top + s, body.right + s, body.bottom + s), r, r, p);
            p.setColor(fill);
            c.drawRoundRect(body, r, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            p.setColor(0xFF111111);
            c.drawRoundRect(body, r, r, p);
        }
    }

    /** RPG dialogue box: thick outer border with a thin inner rule. */
    static final class DoubleBorder extends Base {
        final int fill, ink;
        final float d;

        DoubleBorder(int fill, int ink, float d) { this.fill = fill; this.ink = ink; this.d = d; }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float o = 2 * d;
            RectF r1 = new RectF(b.left + o / 2, b.top + o / 2, b.right - o / 2, b.bottom - o / 2);
            p.setStyle(Paint.Style.FILL);
            p.setColor(fill);
            c.drawRoundRect(r1, 6 * d, 6 * d, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(ink);
            p.setStrokeWidth(o);
            c.drawRoundRect(r1, 6 * d, 6 * d, p);
            RectF r2 = new RectF(r1.left + 4 * d, r1.top + 4 * d, r1.right - 4 * d, r1.bottom - 4 * d);
            p.setStrokeWidth(1.2f * d);
            c.drawRoundRect(r2, 3 * d, 3 * d, p);
        }
    }

    /** Punched card with a cut corner and a scatter of holes. */
    static final class PunchCard extends Base {
        final float d;
        final int seed;

        PunchCard(float d, int seed) { this.d = d; this.seed = seed; }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float cut = 10 * d;
            Path card = new Path();
            card.moveTo(b.left + cut, b.top);
            card.lineTo(b.right, b.top);
            card.lineTo(b.right, b.bottom);
            card.lineTo(b.left, b.bottom);
            card.lineTo(b.left, b.top + cut);
            card.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFEADFC4);
            c.drawPath(card, p);
            Random r = new Random(seed);
            p.setColor(0x703B3326);
            float hw = 2.2f * d, hh = 4 * d;
            int cols = (int) (b.width() / (5 * d));
            int rows = Math.max(4, (int) (b.height() / (6 * d)));
            for (int i = 1; i < cols - 1; i++) {
                if (r.nextFloat() < 0.5f) continue;
                int row = r.nextInt(rows);
                float x = b.left + i * 5 * d, y = b.top + 3 * d + row * (b.height() - 8 * d) / rows;
                c.drawRect(x, y, x + hw, y + hh, p);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(0xFF9C8E6A);
            c.drawPath(card, p);
        }
    }

    /** Loud 90s pattern: squiggles, triangles, dots and zigzags. */
    static final class Memphis extends Base {
        final int bg;
        final float d;

        Memphis(int bg, float d) { this.bg = bg; this.d = d; }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            c.drawColor(bg);
            int[] cols = {0xFFFF4FA3, 0xFFFFD23F, 0xFF9BE22D, 0xFF7B2FF7, 0xFFFFFFFF, 0xFF111111};
            Random r = new Random(1990);
            for (int i = 0; i < 30; i++) {
                float x = b.left + r.nextFloat() * b.width(), y = b.top + r.nextFloat() * b.height();
                p.setColor((cols[r.nextInt(cols.length)] & 0x00FFFFFF) | 0xCC000000);
                switch (r.nextInt(5)) {
                    case 0:
                        p.setStyle(Paint.Style.FILL);
                        c.drawCircle(x, y, (8 + r.nextInt(16)) * d, p);
                        break;
                    case 1: {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(3 * d);
                        float s = (14 + r.nextInt(14)) * d;
                        Path t = new Path();
                        t.moveTo(x, y - s);
                        t.lineTo(x + s, y + s);
                        t.lineTo(x - s, y + s);
                        t.close();
                        c.drawPath(t, p);
                        break;
                    }
                    case 2: {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(3.5f * d);
                        Path q = new Path();
                        q.moveTo(x, y);
                        for (int k = 0; k < 4; k++) {
                            q.rQuadTo(8 * d, (k % 2 == 0 ? -12 : 12) * d, 16 * d, 0);
                        }
                        c.drawPath(q, p);
                        break;
                    }
                    case 3:
                        p.setStyle(Paint.Style.FILL);
                        for (int gx = 0; gx < 3; gx++)
                            for (int gy = 0; gy < 3; gy++) c.drawCircle(x + gx * 8 * d, y + gy * 8 * d, 2 * d, p);
                        break;
                    default: {
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(3 * d);
                        Path z = new Path();
                        z.moveTo(x, y);
                        for (int k = 0; k < 5; k++) z.rLineTo(7 * d, (k % 2 == 0 ? -8 : 8) * d);
                        c.drawPath(z, p);
                    }
                }
            }
        }
    }
}
