package com.cooperlad.retrolauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Screen effect drawn over the UI. CRT: scanlines, rolling band and vignette.
 * LCD: a fine pixel grid. Both add rounded screen corners in the bezel colour.
 * It never takes touches, so everything underneath stays tappable.
 */
public class CrtOverlayView extends View {
    private final Paint scan = new Paint();
    private final Paint lcd = new Paint();
    private final Paint vignette = new Paint();
    private final Paint band = new Paint();
    private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cornerPath = new Path();
    private final float density;
    private Skin.Fx mode = Skin.Fx.CRT;
    private float cornerPx;
    private boolean effectOn = true;
    private boolean running = false;
    private final float bandH;

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, 50); // ~20 fps: smooth enough, kind to the battery
        }
    };

    public CrtOverlayView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        int gap = Math.max(3, Math.round(3 * density));
        int dark = Math.max(1, Math.round(density));
        Bitmap pattern = Bitmap.createBitmap(1, gap, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < gap; y++) pattern.setPixel(0, y, y < dark ? 0x40000000 : 0x00000000);
        scan.setShader(new BitmapShader(pattern, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        Bitmap grid = Bitmap.createBitmap(gap, gap, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < gap; y++)
            for (int x = 0; x < gap; x++) grid.setPixel(x, y, (x == gap - 1 || y == gap - 1) ? 0x1A000000 : 0);
        lcd.setShader(new BitmapShader(grid, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        bandH = 140 * density;
        band.setShader(new LinearGradient(0, 0, 0, bandH,
                new int[]{0x00FFFFFF, 0x0DFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        corner.setColor(0xFF000000);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void configure(Skin.Fx mode, float cornerPx, int bezel) {
        this.mode = mode;
        this.cornerPx = cornerPx;
        corner.setColor(bezel);
        buildCorners(getWidth(), getHeight());
        invalidate();
    }

    void setEffectOn(boolean on) { effectOn = on; invalidate(); }

    void setRunning(boolean r) {
        r = r && mode == Skin.Fx.CRT && effectOn;
        if (running == r) return;
        running = r;
        removeCallbacks(frame);
        if (r) post(frame);
    }

    private void buildCorners(int w, int h) {
        cornerPath.reset();
        if (cornerPx <= 0 || w == 0) return;
        cornerPath.setFillType(Path.FillType.EVEN_ODD);
        cornerPath.addRect(0, 0, w, h, Path.Direction.CW);
        cornerPath.addRoundRect(new RectF(0, 0, w, h), cornerPx, cornerPx, Path.Direction.CW);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        vignette.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.72f,
                new int[]{0x00000000, 0x00000000, 0x8C000000}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
        buildCorners(w, h);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (effectOn && mode == Skin.Fx.CRT) {
            c.drawRect(0, 0, w, h, scan);
            long period = 7000;
            float t = (SystemClock.uptimeMillis() % period) / (float) period;
            c.save();
            c.translate(0, t * (h + bandH) - bandH);
            c.drawRect(0, 0, w, bandH, band);
            c.restore();
            c.drawRect(0, 0, w, h, vignette);
        } else if (effectOn && mode == Skin.Fx.LCD) {
            c.drawRect(0, 0, w, h, lcd);
        }
        if (cornerPx > 0) c.drawPath(cornerPath, corner);
    }
}
