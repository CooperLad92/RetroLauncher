package com.cooperlad.retrolauncher;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A skin decides how every part of the home screen looks, plus a few behaviours
 * (extra controls, launch animations, screensaver). The default implementation
 * is the original green-screen terminal.
 */
abstract class Skin {
    enum Part { TITLE, STATUS, CLOCK, DATE, RESPONSE, HEADER, FOOTER, PROMPT, INPUT, LIST, DOCK, KEY, ROW, TAIL, BOOT, MENU }
    enum Icons { PIXEL_TINT, PALETTE, REAL, CRITTER, NONE }
    enum Fx { CRT, LCD, NONE }

    static final int FONT_PIXEL = 0, FONT_MONO = 1, FONT_SANS = 2, FONT_CARTOON = 3;
    static final int K_F1 = 0, K_F2 = 1, K_F3 = 2, K_F4 = 3, K_MENU = 4, K_CLOCK = 5;

    /** Live device readings the skins can show. */
    static final class Info {
        int battery = -1;
        boolean charging;
        int apps;
        int rssi = -127;
        String grid;
        int bearing = -1;
        float estHours = -1;

        String batt() { return battery < 0 ? "--" : battery + "%"; }
    }

    final String name;
    int fg, bg, bezel, accent;
    int font = FONT_PIXEL;
    boolean glow = true, upper = true, controlsOutside = false, cursor = false;
    boolean dynamicKeys = false, wantsWifi = false, wantsLocation = false;
    Icons icons = Icons.PIXEL_TINT;
    Fx fx = Fx.CRT;
    int cornerDp = 26, screenMarginDp = 6, contentPadDp = 16, extraHeightDp = 0, iconDp = 24;

    Skin(String name) { this.name = name; }

    // ------------------------------------------------------------------ text

    String label(LauncherActivity.AppEntry e) { return upper ? e.label : e.name; }

    String title(Info i, Date now) { return "**** RETRO-OS 64 BASIC V2 ****"; }

    String status(Info i) {
        return "BATT " + i.batt() + (i.charging ? " ⚡" : "") + "  ·  " + i.apps + " PRG  ·  " + name;
    }

    String clock(Date d, boolean blink) {
        String s = fmt("HH:mm", d);
        return blink ? s.replace(':', ' ') : s;
    }

    String date(Date d) { return fmt("EEE dd MMM yyyy", d); }

    /** List header text, or null for none. */
    String header() { return "0 \"RETRO-OS DISK  \" 64 2A"; }

    String footer(Info i, long freeMb) { return freeMb + " BLOCKS FREE."; }

    String row(LauncherActivity.AppEntry e, boolean sel) {
        return String.format(Locale.US, "%-4d\"%s\"", e.blocks, label(e));
    }

    String tail(LauncherActivity.AppEntry e) { return " PRG"; }

    String hint() { return "TYPE A PROGRAM OR HELP"; }

    String prompt() { return "> "; }

    String ready() { return "READY."; }

    String error(String msg) { return msg + "\n" + ready(); }

    String launchText(LauncherActivity.AppEntry e) {
        return "LOAD \"" + e.label + "\",8,1\n\nSEARCHING FOR " + e.label + "\nLOADING\n" + ready();
    }

    String selectText(LauncherActivity.AppEntry e) { return row(e, false).trim(); }

    String[] keys(Date now) { return new String[]{"F1\nPHONE", "F2\nMSG", "F3\nWEB", "F4\nCAM", "F5\nMENU"}; }

    int[] keyActions() { return new int[]{K_F1, K_F2, K_F3, K_F4, K_MENU}; }

    String boot(Info i) {
        return "RETRO-OS BIOS V1.986\n" +
                "(C) 1986 RETRO SYSTEMS INC.\n\n" +
                "CPU 6502 @ 1.02 MHZ ...... OK\n" +
                "MEMORY TEST 65536 BYTES .. OK\n" +
                "DEVICE: " + model() + "\n" +
                "KERNAL ANDROID " + Build.VERSION.RELEASE + " ..... OK\n" +
                "SCANNING DISK ......... " + i.apps + " PRG\n\n" +
                "LOADING SHELL...\n\n" +
                "READY.\n";
    }

    /** Typed number to app index (e.g. teletext page 301), or -1 if numbers mean nothing here. */
    int numberToIndex(int n) { return -1; }

    // ------------------------------------------------------------------ look

    float size(Part p) {
        switch (p) {
            case TITLE: return 13;
            case STATUS: return 12;
            case CLOCK: return 64;
            case DATE: return 14;
            case PROMPT: case INPUT: return 17;
            case KEY: return 11;
            case ROW: case TAIL: case MENU: return 15;
            case HEADER: case FOOTER: case BOOT: return 14;
            default: return 13;
        }
    }

    boolean show(Part p) { return true; }

    int gravity(Part p) {
        switch (p) {
            case TITLE: case STATUS: case CLOCK: case DATE: return Gravity.CENTER;
            case BOOT: return Gravity.TOP | Gravity.START;
            default: return Gravity.START | Gravity.CENTER_VERTICAL;
        }
    }

    int color(Part p) { return (p == Part.TITLE || p == Part.HEADER) ? bg : fg; }

    Drawable bg(Part p, float d) {
        switch (p) {
            case TITLE: case HEADER: return new ColorDrawable(fg);
            case BOOT: return new ColorDrawable(bg);
            default: return null;
        }
    }

    /** Padding (dp: left, top, right, bottom) used when a part has a background. */
    int[] pad(Part p) { return new int[]{6, 4, 6, 4}; }

    void shadow(TextView t, Part p, float d) {
        if (glow && p != Part.TITLE && p != Part.HEADER) t.setShadowLayer(5 * d, 0, 0, alpha(color(p), 0xB0));
        else t.setShadowLayer(0, 0, 0, 0);
    }

    Drawable screenBg(float d) { return new ColorDrawable(bg); }

    Drawable panelBg(float d) { return null; }

    Drawable menuBg(float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setStroke(Math.round(2 * d), fg);
        return g;
    }

    Drawable keyBg(int i, float d) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(alpha(fg, 0x14));
        g.setStroke(Math.round(2 * d), fg);
        return g;
    }

    int keyColor(int i) { return fg; }

    Drawable listSelector() { return new ColorDrawable(alpha(fg, 0x3A)); }

    Drawable rowBg(int idx, boolean sel, float d) { return sel ? new ColorDrawable(alpha(fg, 0x3A)) : null; }

    int rowColor(int idx, boolean sel) { return fg; }

    int tailColor(int idx, boolean sel) { return rowColor(idx, sel); }

    float rowRotation(int idx) { return 0; }

    int rowGapDp() { return 0; }

    int rowPadDp() { return 4; }

    /** Four shades, darkest first, used to repaint app icons. */
    int[] palette() { return new int[]{scale(fg, 0.22f), scale(fg, 0.45f), scale(fg, 0.72f), fg}; }

    // ------------------------------------------------------------------ behaviour hooks

    View background(LauncherActivity a) { return null; }

    View extra(LauncherActivity a) { return null; }

    View screensaver(LauncherActivity a) { return null; }

    /** Plays a launch effect; returns how long to wait before opening the app. */
    long launchFx(LauncherActivity a, LauncherActivity.AppEntry e) { return 0; }

    void onShown(List<LauncherActivity.AppEntry> shown) { }

    void onSelect(LauncherActivity.AppEntry e) { }

    // ------------------------------------------------------------------ helpers

    static int alpha(int c, int a) { return (c & 0x00FFFFFF) | (a << 24); }

    static int scale(int c, float f) {
        int r = (int) (((c >> 16) & 0xFF) * f), g = (int) (((c >> 8) & 0xFF) * f), b = (int) ((c & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static String fmt(String pattern, Date d) {
        return new SimpleDateFormat(pattern, Locale.US).format(d).toUpperCase(Locale.US);
    }

    static String model() { return Build.MODEL.toUpperCase(Locale.US); }
}
