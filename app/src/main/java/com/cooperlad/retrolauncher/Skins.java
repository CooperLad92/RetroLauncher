package com.cooperlad.retrolauncher;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Every skin in the launcher. Switch with THEME <NAME> or F5 → SKIN. */
final class Skins {
    private Skins() { }

    static final String[] NAMES = {
            "GREEN", "AMBER", "64", "NEON", "ICE",
            "RADIO", "SPECTRUM", "TELETEXT", "LCD", "DESKTOP", "MAINFRAME", "CARTOON", "CRITTER", "VHS"};

    static Skin create(String name) {
        String n = name == null ? "GREEN" : name.toUpperCase(Locale.US);
        switch (n) {
            case "AMBER": return new Terminal("AMBER", 0xFFFFB000, 0xFF1A0E00, 0xFF0B0600, false);
            case "64": return new Terminal("64", 0xFFA79FFF, 0xFF3E31A2, 0xFF7C70DA, false);
            case "NEON": return new Terminal("NEON", 0xFFFF4FD8, 0xFF12002B, 0xFF06000F, true);
            case "ICE": return new Terminal("ICE", 0xFF6CF6FF, 0xFF001820, 0xFF000A0E, false);
            case "RADIO": return new Radio();
            case "SPECTRUM": return new Spectrum();
            case "TELETEXT": return new Teletext();
            case "LCD": return new Lcd();
            case "DESKTOP": return new Desktop();
            case "MAINFRAME": return new Mainframe();
            case "CARTOON": return new Cartoon();
            case "CRITTER": return new Critter();
            case "VHS": return new Terminal("VHS", 0xFFFF3DA5, 0xFF0B0716, 0xFF000000, false); // own home screen: see VhsHome
            default: return new Terminal("GREEN", 0xFF33FF66, 0xFF03170A, 0xFF010804, false);
        }
    }

    static boolean exists(String name) {
        for (String n : NAMES) if (n.equalsIgnoreCase(name)) return true;
        return false;
    }

    static String next(String name) {
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equals(name)) return NAMES[(i + 1) % NAMES.length];
        return NAMES[0];
    }

    static String list() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            sb.append(NAMES[i]);
            sb.append(i % 4 == 3 ? "\n" : " ");
        }
        return sb.toString().trim();
    }

    // ================================================================== 80s terminals

    static class Terminal extends Skin {
        final boolean synth;

        Terminal(String n, int fg, int bg, int bezel, boolean synth) {
            super(n);
            this.fg = fg;
            this.bg = bg;
            this.bezel = bezel;
            this.synth = synth;
        }

        @Override Drawable screenBg(float d) { return synth ? new SynthGridDrawable(bg, fg) : super.screenBg(d); }
    }

    // ================================================================== field radio handset

    static class Radio extends Skin {
        FxViews.DialView dial;

        Radio() {
            super("RADIO");
            fg = 0xFF8DFF3D;
            bg = 0xFF050B03;
            bezel = 0xFF3A3F2C;
            accent = 0xFFFFB000;
            controlsOutside = true;
            cursor = true;
            wantsLocation = true;
            extraHeightDp = 150;
            cornerDp = 18;
            screenMarginDp = 12;
        }

        static String freq(int idx) { return String.format(Locale.US, "%.3f", 30.0 + (idx % 2320) * 0.025); }

        static char zoneLetter() {
            int off = TimeZone.getDefault().getOffset(System.currentTimeMillis());
            if (off % 3600000 != 0) return 'J';
            int hrs = off / 3600000;
            if (hrs == 0) return 'Z';
            if (hrs > 0 && hrs <= 12) return "ABCDEFGHIKLM".charAt(hrs - 1);
            if (hrs < 0 && hrs >= -12) return "NOPQRSTUVWXY".charAt(-hrs - 1);
            return 'J';
        }

        @Override String title(Info i, Date now) { return "FIELD-OS  ·  NET 01  ·  SECURE"; }

        @Override String status(Info i) {
            String grid = i.grid == null ? "-- -- -- --" : i.grid;
            String brg = i.bearing < 0 ? "---" : String.format(Locale.US, "%03d", i.bearing);
            String est = i.estHours < 0 ? "--" : String.format(Locale.US, "%.0f", i.estHours);
            return "GRID " + grid + "  BRG " + brg + "°M\nBATT " + i.batt() + "  EST " + est + "H" + (i.charging ? "  CHG" : "");
        }

        @Override String clock(Date d, boolean blink) { return fmt("HHmm", d) + zoneLetter(); }

        @Override String date(Date d) { return "DTG " + fmt("ddHHmm", d) + zoneLetter() + fmt("MMMyy", d); }

        @Override String header() { return "PRESET CHANNELS"; }

        @Override String footer(Info i, long freeMb) { return i.apps + " CH LOADED  ·  SQUELCH ON"; }

        @Override String row(LauncherActivity.AppEntry e, boolean sel) {
            return String.format(Locale.US, "CH %03d %s", e.idx + 1, e.label);
        }

        @Override String tail(LauncherActivity.AppEntry e) { return " " + freq(e.idx); }

        @Override String hint() { return "ENTER CH # OR NAME"; }

        @Override String prompt() { return "CH> "; }

        @Override String ready() { return "STANDBY."; }

        @Override String launchText(LauncherActivity.AppEntry e) {
            return String.format(Locale.US, "TX → CH %03d  %s\n%s\nSTANDBY.", e.idx + 1, freq(e.idx), e.label);
        }

        @Override String[] keys(Date now) { return new String[]{"1\nVOICE", "2\nTEXT", "3\nWEB", "4\nIMAGE", "5\nMENU"}; }

        @Override String boot(Info i) {
            return "FIELD-OS V4.1\n\n" +
                    "CRYPTO SELF TEST ...... PASS\n" +
                    "KEY FILL .............. OK\n" +
                    "GPS ................... " + (i.grid == null ? "NO FIX" : "FIX") + "\n" +
                    "PRESETS ............... " + i.apps + " CH\n" +
                    "ANTENNA VSWR .......... 1.3:1\n\n" +
                    "STANDBY.\n";
        }

        @Override int numberToIndex(int n) { return n - 1; }

        @Override float size(Part p) { return p == Part.STATUS ? 13 : super.size(p); }

        @Override int color(Part p) { return p == Part.TITLE ? fg : super.color(p); }

        @Override Drawable bg(Part p, float d) {
            if (p == Part.TITLE) return Drawables.box(0, fg, 2 * d, 4 * d);
            return super.bg(p, d);
        }

        @Override Drawable keyBg(int i, float d) { return Drawables.box(0xFF23271B, 0xFF15180F, 2 * d, 8 * d); }

        @Override View extra(LauncherActivity a) {
            dial = new FxViews.DialView(a, a.font(), fg, bezel, accent, new FxViews.DialView.Listener() {
                @Override public void step(int delta) { a.moveSelection(delta); }
                @Override public void press() { a.launchSelected(); }
            });
            return dial;
        }

        @Override void onSelect(LauncherActivity.AppEntry e) {
            if (dial != null) dial.setReadout(String.format(Locale.US, "CH %03d", e.idx + 1), e.label + "  " + freq(e.idx));
        }
    }

    // ================================================================== spectrum analyser

    static class Spectrum extends Skin {
        FxViews.WaterfallView wf;

        Spectrum() {
            super("SPECTRUM");
            fg = 0xFF3BE8FF;
            bg = 0xFF020812;
            bezel = 0xFF0A1220;
            accent = 0xFFFFE14D;
            fx = Fx.NONE;
            cornerDp = 10;
            wantsWifi = true;
            extraHeightDp = 170;
        }

        static String freq(int idx) { return String.format(Locale.US, "%.1f", 87.5 + (idx % 206) * 0.1); }

        @Override String title(Info i, Date now) { return "SPECTRUM-OS  RBW 10K  VBW 3K"; }

        @Override String status(Info i) {
            if (i.rssi <= -127 || i.rssi >= 0) return "WIFI  NO CARRIER";
            int s = Math.max(0, Math.min(9, 9 + (i.rssi + 73) / 6));
            StringBuilder bar = new StringBuilder();
            for (int k = 0; k < 9; k++) bar.append(k < s ? '█' : '.');
            String over = i.rssi > -73 ? "+" + (i.rssi + 73) : "";
            return "WIFI S" + s + over + " " + bar + " " + i.rssi + " DBM";
        }

        @Override String clock(Date d, boolean blink) { return fmt("HH:mm:ss", d); }

        @Override String header() { return "MARKER TABLE"; }

        @Override String footer(Info i, long freeMb) { return "NOISE FLOOR -97 DBM  ·  " + i.apps + " MKR"; }

        @Override String row(LauncherActivity.AppEntry e, boolean sel) {
            return String.format(Locale.US, "M%-3d %s", e.idx + 1, e.label);
        }

        @Override String tail(LauncherActivity.AppEntry e) { return " " + freq(e.idx); }

        @Override String hint() { return "TUNE TO NAME"; }

        @Override String prompt() { return "TUNE> "; }

        @Override String ready() { return "SWEEP OK."; }

        @Override String launchText(LauncherActivity.AppEntry e) {
            return "TUNED " + freq(e.idx) + " MHZ\n" + e.label + "\nSWEEP OK.";
        }

        @Override String boot(Info i) {
            return "SPECTRUM-OS\n\n" +
                    "SELF CAL .......... OK\n" +
                    "LO LOCK ........... OK\n" +
                    "PREAMP ............ ON\n" +
                    "ATTEN ............. 10 DB\n" +
                    "SWEEP 87.5-108.0 MHZ\n" +
                    i.apps + " MARKERS SET\n\n" +
                    "SWEEP OK.\n";
        }

        @Override float size(Part p) { return p == Part.CLOCK ? 44 : super.size(p); }

        @Override int color(Part p) { return p == Part.CLOCK ? accent : super.color(p); }

        @Override int tailColor(int idx, boolean sel) { return accent; }

        @Override View extra(LauncherActivity a) {
            wf = new FxViews.WaterfallView(a, a.font(), fg);
            return wf;
        }

        @Override void onShown(List<LauncherActivity.AppEntry> shown) {
            if (wf == null) return;
            List<String> names = new ArrayList<>();
            for (int i = 0; i < shown.size() && i < 6; i++) names.add(shown.get(i).label);
            wf.setMarkers(names);
        }
    }

    // ================================================================== teletext

    static class Teletext extends Skin {
        static final int WHITE = 0xFFFFFFFF, YELLOW = 0xFFFFFF00, CYAN = 0xFF00FFFF, GREEN = 0xFF00FF00,
                RED = 0xFFFF0000, BLUE = 0xFF0000FF, MAGENTA = 0xFFFF00FF, BLACK = 0xFF000000;

        Teletext() {
            super("TELETEXT");
            fg = WHITE;
            bg = BLACK;
            bezel = 0xFF151515;
            accent = YELLOW;
            glow = false;
            icons = Icons.NONE;
            cornerDp = 22;
        }

        @Override String title(Info i, Date now) { return "P100  RETRO 100  " + fmt("EEE dd MMM", now) + "  " + fmt("HH:mm/ss", now); }

        @Override String status(Info i) { return "RETRO TEXT"; }

        @Override boolean show(Part p) { return p != Part.CLOCK && p != Part.DATE && super.show(p); }

        @Override String header() { return "A-Z INDEX"; }

        @Override String footer(Info i, long freeMb) { return "PAGES 300-" + (300 + Math.max(0, i.apps - 1)) + "  ·  P100 INDEX"; }

        @Override String row(LauncherActivity.AppEntry e, boolean sel) { return label(e); }

        @Override String tail(LauncherActivity.AppEntry e) { return " " + (300 + e.idx); }

        @Override String hint() { return "ENTER PAGE NUMBER"; }

        @Override String prompt() { return "P "; }

        @Override String ready() { return "PAGE READY."; }

        @Override String launchText(LauncherActivity.AppEntry e) { return "P" + (300 + e.idx) + "  " + e.label + "\nPAGE READY."; }

        @Override String[] keys(Date now) { return new String[]{"PHONE", "MSG", "WEB", "CAM", "MENU"}; }

        @Override String boot(Info i) {
            return "P100  SEARCHING...\n\n" +
                    "P101   P102   P103\n" +
                    "P104   P105   P106\n\n" +
                    "RETRO TEXT\n" +
                    "THE SERVICE FOR " + model() + "\n" +
                    i.apps + " PAGES ON AIR\n\n" +
                    "PAGE READY.\n";
        }

        @Override int numberToIndex(int n) { return n >= 300 ? n - 300 : -1; }

        @Override float size(Part p) {
            switch (p) {
                case STATUS: return 26;
                case TITLE: return 12;
                case KEY: return 12;
                default: return super.size(p);
            }
        }

        @Override int gravity(Part p) { return p == Part.TITLE ? Gravity.START : super.gravity(p); }

        @Override int color(Part p) {
            switch (p) {
                case TITLE: return WHITE;
                case STATUS: return YELLOW;
                case RESPONSE: return CYAN;
                case HEADER: return GREEN;
                case FOOTER: return MAGENTA;
                case PROMPT: return GREEN;
                case BOOT: return YELLOW;
                default: return WHITE;
            }
        }

        @Override Drawable bg(Part p, float d) {
            switch (p) {
                case STATUS: return new ColorDrawable(BLUE);
                case BOOT: return new ColorDrawable(BLACK);
                default: return null;
            }
        }

        @Override Drawable keyBg(int i, float d) { return new ColorDrawable(new int[]{RED, GREEN, YELLOW, CYAN, MAGENTA}[i % 5]); }

        @Override int keyColor(int i) { return BLACK; }

        @Override Drawable rowBg(int idx, boolean sel, float d) { return sel ? new ColorDrawable(BLUE) : null; }

        @Override int rowColor(int idx, boolean sel) { return idx % 2 == 0 ? WHITE : CYAN; }

        @Override int tailColor(int idx, boolean sel) { return YELLOW; }

        @Override Drawable listSelector() { return new ColorDrawable(0xFF0000AA); }
    }

    // ================================================================== 4-shade handheld LCD

    static class Lcd extends Skin {
        static final int C0 = 0xFF0F380F, C1 = 0xFF306230, C2 = 0xFF8BAC0F, C3 = 0xFF9BBC0F;

        Lcd() {
            super("LCD");
            fg = C0;
            bg = C3;
            bezel = 0xFFC8C4C4;
            accent = C1;
            glow = false;
            fx = Fx.LCD;
            icons = Icons.PALETTE;
            controlsOutside = true;
            cursor = true;
            cornerDp = 8;
            screenMarginDp = 16;
            contentPadDp = 28;
            extraHeightDp = 210;
        }

        @Override String title(Info i, Date now) { return "◀ POCKET-OS ▶"; }

        @Override String status(Info i) { return "BATT " + i.batt() + "  ·  " + i.apps + " GAMES"; }

        @Override String header() { return "SELECT A GAME"; }

        @Override String footer(Info i, long freeMb) { return "PRESS A TO START"; }

        @Override String row(LauncherActivity.AppEntry e, boolean sel) { return (sel ? "▶ " : "  ") + label(e); }

        @Override String tail(LauncherActivity.AppEntry e) { return ""; }

        @Override String hint() { return "SEARCH"; }

        @Override String prompt() { return "▶ "; }

        @Override String ready() { return "PRESS START"; }

        @Override String launchText(LauncherActivity.AppEntry e) { return "NOW LOADING...\n" + e.label; }

        @Override String boot(Info i) { return "POCKET-OS\n\n(C) 1989 RETRO\n\n\n\nPRESS START\n"; }

        @Override boolean show(Part p) { return p != Part.DOCK && super.show(p); }

        @Override int gravity(Part p) { return p == Part.BOOT ? Gravity.CENTER : super.gravity(p); }

        @Override int[] palette() { return new int[]{C0, C1, C2, C3}; }

        @Override Drawable screenBg(float d) {
            // dark frame around the LCD glass
            GradientDrawable frame = Drawables.box(0xFF55556A, 0, 0, 10 * d);
            GradientDrawable glass = Drawables.box(C3, 0, 0, 3 * d);
            return new LayerDrawable(new Drawable[]{frame, new InsetDrawable(glass, Math.round(14 * d))});
        }

        @Override Drawable bg(Part p, float d) { return p == Part.BOOT ? new ColorDrawable(C3) : super.bg(p, d); }

        @Override Drawable rowBg(int idx, boolean sel, float d) { return sel ? new ColorDrawable(C2) : null; }

        @Override Drawable listSelector() { return new ColorDrawable(alpha(C1, 0x40)); }

        @Override View extra(LauncherActivity a) {
            return new FxViews.DPadView(a, a.font(), k -> {
                switch (k) {
                    case FxViews.DPadView.UP: a.moveSelection(-1); break;
                    case FxViews.DPadView.DOWN: a.moveSelection(1); break;
                    case FxViews.DPadView.LEFT: a.moveSelection(-6); break;
                    case FxViews.DPadView.RIGHT: a.moveSelection(6); break;
                    case FxViews.DPadView.A: a.launchSelected(); break;
                    case FxViews.DPadView.B: a.goBack(); break;
                    case FxViews.DPadView.SELECT: a.cycleSkin(); break;
                    case FxViews.DPadView.START: a.openSystemMenu(); break;
                    default: break;
                }
            });
        }
    }

    // ================================================================== 90s desktop

    static class Desktop extends Skin {
        static final int TEAL = 0xFF008080, GREY = 0xFFC0C0C0, NAVY = 0xFF000080, BLACK = 0xFF000000, WHITE = 0xFFFFFFFF;

        Desktop() {
            super("DESKTOP");
            fg = BLACK;
            bg = TEAL;
            bezel = BLACK;
            accent = NAVY;
            font = FONT_SANS;
            glow = false;
            upper = false;
            fx = Fx.NONE;
            icons = Icons.REAL;
            cornerDp = 0;
            screenMarginDp = 0;
            contentPadDp = 10;
            dynamicKeys = true;
            iconDp = 28;
        }

        @Override String title(Info i, Date now) { return "Program Manager"; }

        @Override String status(Info i) { return "File     Options     Window     Help"; }

        @Override boolean show(Part p) { return p != Part.CLOCK && p != Part.DATE && super.show(p); }

        @Override String header() { return null; }

        @Override String footer(Info i, long freeMb) { return i.apps + " object(s)    " + freeMb + " MB free"; }

        @Override String row(LauncherActivity.AppEntry e, boolean sel) { return e.name; }

        @Override String tail(LauncherActivity.AppEntry e) { return ""; }

        @Override String hint() { return "Type a program name"; }

        @Override String prompt() { return "Run: "; }

        @Override String ready() { return "Ready"; }

        @Override String error(String msg) { return "Cannot find that. Type HELP for commands."; }

        @Override String launchText(LauncherActivity.AppEntry e) { return "Starting " + e.name + "..."; }

        @Override String selectText(LauncherActivity.AppEntry e) { return e.name; }

        @Override String[] keys(Date now) {
            return new String[]{"Start", "Phone", "Messages", "Web", new java.text.SimpleDateFormat("HH:mm", Locale.US).format(now)};
        }

        @Override int[] keyActions() { return new int[]{K_MENU, K_F1, K_F2, K_F3, K_CLOCK}; }

        @Override String boot(Info i) {
            return "Starting RETRO-OS...\n\n" +
                    "HIMEM is testing extended memory... done.\n" +
                    "Loading drivers... done.\n" +
                    i.apps + " programs found.\n";
        }

        @Override float size(Part p) {
            switch (p) {
                case TITLE: return 15;
                case STATUS: return 14;
                case KEY: return 13;
                case ROW: case MENU: return 16;
                case BOOT: return 14;
                default: return super.size(p);
            }
        }

        @Override int gravity(Part p) {
            return (p == Part.TITLE || p == Part.STATUS) ? Gravity.START | Gravity.CENTER_VERTICAL : super.gravity(p);
        }

        @Override int color(Part p) {
            switch (p) {
                case TITLE: return WHITE;
                case BOOT: return GREY;
                default: return BLACK;
            }
        }

        @Override Drawable bg(Part p, float d) {
            switch (p) {
                case TITLE: {
                    GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{NAVY, 0xFF1084D0});
                    return g;
                }
                case LIST: case INPUT: return Drawables.bevel(false, WHITE, d);
                case DOCK: return Drawables.bevel(true, GREY, d);
                case BOOT: return new ColorDrawable(BLACK);
                default: return null;
            }
        }

        @Override Drawable panelBg(float d) { return Drawables.bevel(true, GREY, d); }

        @Override Drawable menuBg(float d) { return Drawables.bevel(true, GREY, d); }

        @Override Drawable keyBg(int i, float d) { return Drawables.bevel(i != 4, GREY, d); }

        @Override int keyColor(int i) { return BLACK; }

        @Override Drawable rowBg(int idx, boolean sel, float d) { return sel ? new ColorDrawable(NAVY) : null; }

        @Override int rowColor(int idx, boolean sel) { return sel ? WHITE : BLACK; }

        @Override Drawable listSelector() { return new ColorDrawable(0x60000080); }

        @Override View screensaver(LauncherActivity a) { return new FxViews.StarfieldView(a); }
    }

    // ================================================================== mainframe operator console

    static class Mainframe extends Skin {
        Mainframe() {
            super("MAINFRAME");
            fg = 0xFFEDE6CF;
            bg = 0xFF111317;
            bezel = 0xFF4A4E55;
            accent = 0xFFFFA726;
            font = FONT_MONO;
            icons = Icons.NONE;
            extraHeightDp = 64;
            cornerDp = 14;
        }

        @Override String title(Info i, Date now) { return "SYSTEM/86  OPERATOR CONSOLE"; }

        @Override String status(Info i) { return "JOBS " + i.apps + "  ·  BATT " + i.batt() + "  ·  TAPE READY"; }

        @Override String date(Date d) { return "DAY " + fmt("DDD", d) + "  ·  " + fmt("yyyy-MM-dd", d); }

        @Override String header() { return "JOB QUEUE"; }

        @Override String footer(Info i, long freeMb) { return "CORE FREE " + freeMb + "K"; }

        @Override String row(LauncherActivity.AppEntry e, boolean sel) {
            return String.format(Locale.US, "JOB %04d  %s", e.idx + 1, e.label);
        }

        @Override String tail(LauncherActivity.AppEntry e) { return " RDY"; }

        @Override String hint() { return "ENTER JOB NAME"; }

        @Override String ready() { return "OPERATOR READY."; }

        @Override String launchText(LauncherActivity.AppEntry e) { return "MOUNT TAPE " + e.label + "\nIPL IN PROGRESS...\nOPERATOR READY."; }

        @Override String[] keys(Date now) { return new String[]{"CALL", "MSG", "NET", "CAM", "OPER"}; }

        @Override String boot(Info i) {
            return "IPL FROM 0A80\n" +
                    "SYSTEM/86 RELEASE 4.2\n\n" +
                    "CORE STORAGE 256K ..... OK\n" +
                    "MOUNTING SYSRES ....... OK\n" +
                    "SPOOLING .............. OK\n" +
                    i.apps + " JOBS QUEUED\n\n" +
                    "OPERATOR READY.\n";
        }

        @Override float size(Part p) { return p == Part.KEY ? 12 : super.size(p); }

        @Override int tailColor(int idx, boolean sel) { return accent; }

        @Override Drawable keyBg(int i, float d) { return Drawables.punchCard(d, 86 + i); }

        @Override int keyColor(int i) { return 0xFF3B3326; }

        @Override View extra(LauncherActivity a) { return new FxViews.LightsView(a, a.font(), accent, fg); }

        @Override long launchFx(LauncherActivity a, LauncherActivity.AppEntry e) {
            View v = new FxViews.ReelView(a, a.font(), fg, "MOUNTING TAPE\n" + e.label);
            a.addFx(v);
            a.removeFx(v, 1000);
            return 900;
        }
    }

    // ================================================================== 90s cartoon

    static class Cartoon extends Skin {
        static final int PINK = 0xFFFF4FA3, YELLOW = 0xFFFFD23F, LIME = 0xFF9BE22D, CYAN = 0xFF34D1F2,
                ORANGE = 0xFFFF8A3D, PURPLE = 0xFF7B2FF7, INK = 0xFF111111, WHITE = 0xFFFFFFFF;
        static final int[] POP = {YELLOW, PINK, LIME, CYAN, ORANGE};
        static final String[] WORDS = {"ZAP!", "POW!", "BOING!", "WHOOSH!", "KABOOM!"};

        Cartoon() {
            super("CARTOON");
            fg = INK;
            bg = 0xFF2EC4D6;
            bezel = PURPLE;
            accent = PINK;
            font = FONT_CARTOON;
            glow = false;
            fx = Fx.NONE;
            icons = Icons.REAL;
            cornerDp = 0;
            iconDp = 30;
        }

        @Override String title(Info i, Date now) { return "~ TOTALLY RAD OS ~"; }

        @Override String status(Info i) { return "BATT " + i.batt() + "  ★  " + i.apps + " APPS"; }

        @Override String header() { return null; }

        @Override String footer(Info i, long freeMb) { return "YOU MADE IT TO THE END! ★"; }

        @Override String row(LauncherActivity.AppEntry e, boolean sel) { return label(e); }

        @Override String tail(LauncherActivity.AppEntry e) { return ""; }

        @Override String hint() { return "WHATCHA LOOKING FOR?"; }

        @Override String prompt() { return "→ "; }

        @Override String ready() { return "LET'S GO!"; }

        @Override String launchText(LauncherActivity.AppEntry e) { return e.label + " TIME!"; }

        @Override String[] keys(Date now) { return new String[]{"PHONE", "TEXT", "WEB", "CAM", "MENU"}; }

        @Override String boot(Info i) {
            return "WHOA!\n\nLOADING THE RADNESS...\n\nCOOL METER ......... MAX\nAPPS FOUND ......... " + i.apps + "\n\nLET'S GO!\n";
        }

        @Override float size(Part p) {
            switch (p) {
                case TITLE: return 18;
                case CLOCK: return 72;
                case DATE: return 16;
                case KEY: return 13;
                case ROW: return 17;
                case BOOT: return 18;
                default: return super.size(p);
            }
        }

        @Override int color(Part p) {
            switch (p) {
                case CLOCK: case DATE: case FOOTER: return WHITE;
                case BOOT: return YELLOW;
                default: return INK;
            }
        }

        @Override void shadow(TextView t, Part p, float d) {
            if (p == Part.CLOCK || p == Part.DATE || p == Part.FOOTER || p == Part.BOOT) t.setShadowLayer(1f, 3 * d, 3 * d, INK);
            else t.setShadowLayer(0, 0, 0, 0);
        }

        @Override Drawable bg(Part p, float d) {
            switch (p) {
                case TITLE: return Drawables.sticker(YELLOW, d);
                case STATUS: case RESPONSE: case INPUT: return Drawables.sticker(WHITE, d);
                case BOOT: return new ColorDrawable(PURPLE);
                default: return null;
            }
        }

        @Override int[] pad(Part p) { return new int[]{14, 8, 18, 12}; }

        @Override Drawable screenBg(float d) { return Drawables.memphis(bg, d); }

        @Override Drawable menuBg(float d) { return Drawables.sticker(WHITE, d); }

        @Override Drawable keyBg(int i, float d) { return Drawables.sticker(POP[i % POP.length], d); }

        @Override int keyColor(int i) { return INK; }

        @Override Drawable listSelector() { return new ColorDrawable(0); }

        @Override Drawable rowBg(int idx, boolean sel, float d) { return Drawables.sticker(sel ? WHITE : POP[idx % POP.length], d); }

        @Override int rowColor(int idx, boolean sel) { return INK; }

        @Override float rowRotation(int idx) { return idx % 2 == 0 ? -1.2f : 1.2f; }

        @Override int rowGapDp() { return 3; }

        @Override int rowPadDp() { return 14; }

        @Override long launchFx(LauncherActivity a, LauncherActivity.AppEntry e) {
            float d = a.density();
            TextView t = new TextView(a);
            t.setTypeface(a.font());
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46);
            t.setTextColor(INK);
            t.setText(WORDS[e.idx % WORDS.length]);
            t.setBackground(Drawables.sticker(YELLOW, d));
            t.setPadding(Math.round(28 * d), Math.round(14 * d), Math.round(32 * d), Math.round(18 * d));
            FrameLayout wrap = new FrameLayout(a);
            wrap.addView(t, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            a.addFx(wrap);
            t.setScaleX(0.2f);
            t.setScaleY(0.2f);
            t.setRotation(-14);
            t.animate().scaleX(1f).scaleY(1f).rotation(-6).setDuration(260)
                    .setInterpolator(new OvershootInterpolator(3f)).start();
            a.removeFx(wrap, 650);
            return 400;
        }
    }

    // ================================================================== original creature-collector RPG

    static class Critter extends Skin {
        static final int INK = 0xFF202028, PAPER = 0xFFF8F8F0;

        Critter() {
            super("CRITTER");
            fg = INK;
            bg = PAPER;
            bezel = 0xFF2F3B66;
            accent = 0xFFE04848;
            glow = false;
            fx = Fx.NONE;
            icons = Icons.CRITTER;
            cursor = true;
            cornerDp = 12;
            iconDp = 30;
        }

        @Override String title(Info i, Date now) { return "CRITTER-OS"; }

        @Override String status(Info i) { return "CRITTERS " + i.apps + "  ·  BATT " + i.batt(); }

        @Override String header() { return "YOUR TEAM"; }

        @Override String footer(Info i, long freeMb) { return i.apps + " CRITTERS CAUGHT"; }

        @Override String row(LauncherActivity.AppEntry e, boolean sel) { return (sel ? "▶" : " ") + label(e); }

        @Override String tail(LauncherActivity.AppEntry e) { return " LV" + Math.min(100, e.blocks); }

        @Override String hint() { return "NAME A CRITTER"; }

        @Override String prompt() { return "▶ "; }

        @Override String ready() { return "READY FOR ADVENTURE!"; }

        @Override String launchText(LauncherActivity.AppEntry e) { return "GO, " + e.label + "!\n" + e.label + " IS READY!"; }

        @Override String[] keys(Date now) { return new String[]{"CALL", "TEXT", "WEB", "PHOTO", "MENU"}; }

        @Override String boot(Info i) {
            return "CRITTER-OS\n\n" + i.apps + " CRITTERS ARE\nWAITING FOR YOU!\n\nLOADING SAVE...\n\n▶ CONTINUE\n";
        }

        @Override float size(Part p) {
            switch (p) {
                case TITLE: return 16;
                case CLOCK: return 56;
                case KEY: return 12;
                default: return super.size(p);
            }
        }

        @Override int color(Part p) { return INK; }

        @Override Drawable bg(Part p, float d) {
            switch (p) {
                case TITLE: case RESPONSE: case INPUT: return Drawables.doubleBorder(PAPER, INK, d);
                case BOOT: return new ColorDrawable(PAPER);
                default: return null;
            }
        }

        @Override int[] pad(Part p) { return new int[]{14, 10, 14, 10}; }

        @Override Drawable menuBg(float d) { return Drawables.doubleBorder(PAPER, INK, d); }

        @Override Drawable keyBg(int i, float d) { return Drawables.doubleBorder(PAPER, INK, d); }

        @Override Drawable rowBg(int idx, boolean sel, float d) { return sel ? new ColorDrawable(0xFFE6E6D8) : null; }

        @Override int tailColor(int idx, boolean sel) { return 0xFF3C9D4E; }

        @Override long launchFx(LauncherActivity a, LauncherActivity.AppEntry e) {
            final View f = new View(a);
            f.setBackgroundColor(0xFFFFFFFF);
            f.setAlpha(0f);
            a.addFx(f);
            f.animate().alpha(1f).setDuration(90).withEndAction(() ->
                    f.animate().alpha(0f).setDuration(90).withEndAction(() ->
                            f.animate().alpha(1f).setDuration(90).withEndAction(() ->
                                    f.animate().alpha(0f).setDuration(160).start()).start()).start()).start();
            a.removeFx(f, 600);
            return 520;
        }
    }
}
