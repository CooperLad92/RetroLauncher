package com.cooperlad.retrolauncher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Synthwave background for the NEON theme: dim striped sun over a perspective grid. */
class SynthGridDrawable extends Drawable {
    private final int bg, line;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    SynthGridDrawable(int bg, int line) {
        this.bg = bg;
        this.line = line;
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        float horizon = h * 0.66f;
        c.drawColor(bg);

        // sun
        float r = w * 0.26f, cx = w / 2f, cy = horizon - r * 0.15f;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, cy - r, 0, cy + r, 0x55FFD23F, 0x55FF2E97, Shader.TileMode.CLAMP));
        c.save();
        c.clipRect(0, 0, w, horizon);
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
        p.setColor(bg);
        float stripe = r * 0.06f;
        for (int i = 0; i < 6; i++) {
            float y = cy + i * r * 0.17f;
            c.drawRect(cx - r, y, cx + r, y + stripe * (1 + i * 0.5f), p);
        }
        c.restore();

        // grid
        p.setColor((line & 0x00FFFFFF) | 0x50000000);
        p.setStrokeWidth(Math.max(1f, w / 400f));
        c.drawLine(0, horizon, w, horizon, p);
        int rows = 12;
        for (int i = 1; i <= rows; i++) {
            float t = i / (float) rows;
            float y = horizon + (h - horizon) * t * t;
            c.drawLine(0, y, w, y, p);
        }
        for (int k = -10; k <= 10; k++) {
            float x0 = cx + k * w * 0.05f;
            float x1 = cx + k * w * 0.42f;
            c.drawLine(x0, horizon, x1, h, p);
        }
    }

    @Override public void setAlpha(int a) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
