package com.cooperlad.retrolauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * VHS rental store. The home screen is the counter: your favourite apps stand
 * face-out as "Staff Picks". Every app lives on the shelves in the drawer as a
 * tape spine; pick one and it slides off the shelf, turns to show its cover, and
 * PLAY pulls the cassette out of the sleeve and into the VCR.
 */
final class VhsHome {
    static final int PINK = 0xFFFF3DA5, CYAN = 0xFF3DF2FF, VFD = 0xFF5CE6FF, CREAM = 0xFFF4E3C1,
            CARD = 0xFFD9B37C, INK = 0xFF2B1A0C;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String PREF_PICKS = "vhsPicks";
    private static final String[] DEFAULT_PICKS = {
            "com.whatsapp", "com.instagram.android", "com.google.android.dialer", "com.google.android.apps.messaging",
            "com.android.chrome", "com.google.android.GoogleCamera", "com.spotify.music", "com.google.android.youtube"};
    private static final String[] QUOTES = {"\"UNMISSABLE!\"", "\"A MUST-OPEN!\"", "\"PURE DOPAMINE!\"",
            "\"I COULDN'T PUT MY PHONE DOWN!\"", "\"TAP-TASTIC!\"", "\"THE APP OF THE YEAR!\""};
    private static final String[] PAPERS = {"THE DAILY PIXEL", "HOME SCREEN WEEKLY", "RETRO REVIEWS", "THE NOTIFICATION TIMES"};

    /** One app, dressed up as a tape. */
    static final class Tape {
        String name, label, pkg, genre, rating, length, tagline, quote;
        ComponentName cn;
        Bitmap icon;
        int color, color2, ink, minutes, seed;
        boolean isNew;
    }

    private static final class Shelf {
        final String letter;
        final List<Tape> tapes = new ArrayList<>();

        Shelf(String l) { letter = l; }
    }

    private final LauncherActivity a;
    private final SharedPreferences prefs;
    private final float d;
    final FrameLayout root;
    private final Typeface pixel, display, cond, condItalic, hand;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<Tape> tapes = new ArrayList<>();
    private final List<String> picks = new ArrayList<>();
    private final List<Shelf> shelves = new ArrayList<>();
    private final ShelfAdapter shelfAdapter = new ShelfAdapter();
    private final Random rnd = new Random();
    private TextView neon, vfdClock, vfdBatt, countV;
    private LinearLayout picksBox;
    private FrameLayout drawer, modal, menuLayer;
    private EditText search;
    private View current, staticView;
    private boolean busy, playing, resumed, loaded;

    VhsHome(LauncherActivity a, SharedPreferences prefs) {
        this.a = a;
        this.prefs = prefs;
        d = a.getResources().getDisplayMetrics().density;
        Typeface px;
        try {
            px = Typeface.createFromAsset(a.getAssets(), "fonts/retro.ttf");
        } catch (Exception e) {
            px = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        }
        pixel = px;
        display = Typeface.create("sans-serif-black", Typeface.ITALIC);
        cond = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        condItalic = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC);
        hand = Typeface.create("casual", Typeface.BOLD);
        String saved = prefs.getString(PREF_PICKS, null);
        if (saved != null && !saved.isEmpty()) Collections.addAll(picks, saved.split("\n"));

        root = new FrameLayout(a);
        buildHome();
        buildDrawer();
        modal = new FrameLayout(a);
        modal.setVisibility(View.GONE);
        root.addView(modal, match());
        menuLayer = new FrameLayout(a);
        menuLayer.setVisibility(View.GONE);
        root.addView(menuLayer, match());
        a.applyInsets(root);
        renderPicks();
        reload();
    }

    // ------------------------------------------------------------------ lifecycle (called by the activity)

    void onResume() {
        resumed = true;
        if (playing) resetAfterLaunch();
        h.removeCallbacks(tick);
        h.post(tick);
        h.removeCallbacks(flicker);
        h.postDelayed(flicker, 2500);
    }

    void onPause() {
        resumed = false;
        h.removeCallbacks(tick);
        h.removeCallbacks(flicker);
        hideKeyboard();
    }

    void onDestroy() {
        exec.shutdownNow();
        h.removeCallbacksAndMessages(null);
    }

    /** Home pressed while already home: back to the counter. */
    void onHome() {
        closeMenu();
        resetAfterLaunch();
        closeDrawer(true);
    }

    void back() {
        if (menuLayer.getVisibility() == View.VISIBLE) { closeMenu(); return; }
        if (modal.getVisibility() == View.VISIBLE && !playing) { putBack(); return; }
        if (drawer.getVisibility() == View.VISIBLE) {
            if (search.length() > 0) search.setText("");
            else closeDrawer(true);
        }
    }

    void onBattery(int level, boolean charging) {
        if (vfdBatt != null) vfdBatt.setText((charging ? "CHG" : "TAPE") + "\n" + (level < 0 ? "--" : level) + "%");
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            String s = new SimpleDateFormat("HH:mm", Locale.US).format(now);
            if ((System.currentTimeMillis() / 1000) % 2 == 1) s = s.replace(':', ' ');
            vfdClock.setText(s);
            h.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000));
        }
    };

    private final Runnable flicker = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            neon.setAlpha(0.3f);
            h.postDelayed(() -> neon.setAlpha(1f), 60);
            h.postDelayed(() -> neon.setAlpha(0.55f), 130);
            h.postDelayed(() -> neon.setAlpha(1f), 190);
            h.postDelayed(this, 3500 + rnd.nextInt(6000));
        }
    };

    // ------------------------------------------------------------------ app loading

    void reload() {
        final PackageManager pm = a.getPackageManager();
        final String self = a.getPackageName();
        final int iconPx = Math.round(56 * d);
        try {
            exec.execute(() -> {
                List<Tape> out = new ArrayList<>();
                List<ResolveInfo> ris = pm.queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
                long now = System.currentTimeMillis();
                for (ResolveInfo ri : ris) {
                    if (self.equals(ri.activityInfo.packageName)) continue;
                    try {
                        out.add(makeTape(pm, ri, iconPx, now));
                    } catch (Exception ignored) { }
                }
                Collections.sort(out, (x, y) -> x.label.compareTo(y.label));
                h.post(() -> {
                    tapes.clear();
                    tapes.addAll(out);
                    loaded = true;
                    applyDefaultPicks();
                    renderPicks();
                    rebuildShelves();
                });
            });
        } catch (Exception ignored) { } // executor shut down
    }

    private static Tape makeTape(PackageManager pm, ResolveInfo ri, int iconPx, long now) {
        Tape t = new Tape();
        CharSequence l = ri.loadLabel(pm);
        t.pkg = ri.activityInfo.packageName;
        t.name = (l == null ? t.pkg : l.toString()).trim();
        t.label = t.name.toUpperCase(Locale.US);
        t.cn = new ComponentName(t.pkg, ri.activityInfo.name);
        t.seed = Math.abs(t.pkg.hashCode() % 100000);
        Drawable dr = ri.loadIcon(pm);
        Bitmap b = Bitmap.createBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888);
        dr.setBounds(0, 0, iconPx, iconPx);
        dr.draw(new Canvas(b));
        t.icon = b;
        int[] cols = palette(b);
        t.color = cols[0];
        t.color2 = cols[1];
        t.ink = luminance(t.color) > 0.62f ? 0xFF141414 : 0xFFFFFFFF;
        ApplicationInfo ai = ri.activityInfo.applicationInfo;
        t.genre = genre(ai, t.pkg);
        long mb = 1;
        try {
            mb = Math.max(1, new File(ai.sourceDir).length() / (1024 * 1024));
        } catch (Exception ignored) { }
        if (mb < 20) { t.length = "T-60"; t.minutes = 60; }
        else if (mb < 60) { t.length = "T-120"; t.minutes = 120; }
        else if (mb < 120) { t.length = "T-160"; t.minutes = 160; }
        else if (mb < 250) { t.length = "E-180"; t.minutes = 180; }
        else { t.length = "E-240"; t.minutes = 240; }
        try {
            long first = pm.getPackageInfo(t.pkg, 0).firstInstallTime;
            t.isNew = now - first < 14L * 24 * 3600 * 1000 && (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (Exception ignored) { }
        t.rating = rating(t.genre);
        t.tagline = tagline(t.genre, t.seed);
        t.quote = QUOTES[t.seed % QUOTES.length] + " — " + PAPERS[(t.seed / 7) % PAPERS.length];
        return t;
    }

    /** Main and secondary colour from the icon: the two strongest saturated hues. */
    private static int[] palette(Bitmap b) {
        float[] weight = new float[12], hueSum = new float[12], satSum = new float[12], valSum = new float[12];
        float[] hsv = new float[3];
        int w = b.getWidth(), hh = b.getHeight();
        long rs = 0, gs = 0, bs = 0, n = 0;
        for (int y = 0; y < hh; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int p = b.getPixel(x, y);
                if ((p >>> 24) < 200) continue;
                rs += (p >> 16) & 0xFF;
                gs += (p >> 8) & 0xFF;
                bs += p & 0xFF;
                n++;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
                int bi = Math.min(11, (int) (hsv[0] / 30f));
                float wt = hsv[1] * hsv[2];
                weight[bi] += wt;
                hueSum[bi] += hsv[0] * wt;
                satSum[bi] += hsv[1] * wt;
                valSum[bi] += hsv[2] * wt;
            }
        }
        int best = -1, second = -1;
        for (int i = 0; i < 12; i++) {
            if (weight[i] <= 0) continue;
            if (best < 0 || weight[i] > weight[best]) { second = best; best = i; }
            else if (second < 0 || weight[i] > weight[second]) second = i;
        }
        if (best < 0) {
            // grey / monochrome icon: deep cinematic navy with a steel second colour
            if (n > 0 && (rs + gs + bs) / (3 * n) > 200) return new int[]{0xFF2B3A67, 0xFF101828};
            return new int[]{0xFF2B2F3A, 0xFF5B6478};
        }
        int main = bin(best, weight, hueSum, satSum, valSum);
        int sec;
        if (second >= 0 && weight[second] > weight[best] * 0.15f) sec = bin(second, weight, hueSum, satSum, valSum);
        else {
            float[] m = new float[3];
            Color.colorToHSV(main, m);
            sec = Color.HSVToColor(new float[]{(m[0] + 330) % 360, Math.min(1f, m[1] * 1.1f), m[2] * 0.45f});
        }
        return new int[]{main, sec};
    }

    private static int bin(int i, float[] wt, float[] hue, float[] sat, float[] val) {
        float hh = hue[i] / wt[i], s = sat[i] / wt[i], v = val[i] / wt[i];
        return Color.HSVToColor(new float[]{hh, Math.max(0.5f, Math.min(0.92f, s)), Math.max(0.5f, Math.min(0.88f, v))});
    }

    private static float luminance(int c) {
        return (0.299f * ((c >> 16) & 0xFF) + 0.587f * ((c >> 8) & 0xFF) + 0.114f * (c & 0xFF)) / 255f;
    }

    private static String genre(ApplicationInfo ai, String pkg) {
        switch (ai.category) {
            case ApplicationInfo.CATEGORY_GAME: return "GAME";
            case ApplicationInfo.CATEGORY_AUDIO: return "MUSIC";
            case ApplicationInfo.CATEGORY_VIDEO: return "MOVIES";
            case ApplicationInfo.CATEGORY_IMAGE: return "PHOTO";
            case ApplicationInfo.CATEGORY_SOCIAL: return "SOCIAL";
            case ApplicationInfo.CATEGORY_NEWS: return "NEWS";
            case ApplicationInfo.CATEGORY_MAPS: return "TRAVEL";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY: return "WORK";
            default: break;
        }
        String p = pkg.toLowerCase(Locale.US);
        if (p.matches(".*(whatsapp|messag|telegram|signal|chat|discord|facebook|instagram|snapchat|twitter|reddit|tiktok|threads).*")) return "SOCIAL";
        if (p.matches(".*(camera|photo|gallery).*")) return "PHOTO";
        if (p.matches(".*(music|spotify|podcast|radio|audio|sound).*")) return "MUSIC";
        if (p.matches(".*(youtube|netflix|video|\\.tv|prime|disney|player).*")) return "MOVIES";
        if (p.matches(".*(bank|pay|wallet|revolut|money|finance|aib|boi|crypto).*")) return "MONEY";
        if (p.matches(".*(maps|transit|uber|travel|bus|rail|strava|bike|ryanair|aer).*")) return "TRAVEL";
        if (p.matches(".*(mail|outlook).*")) return "MAIL";
        if (p.matches(".*(dialer|phone|contacts).*")) return "CALLS";
        if (p.matches(".*(game|games).*")) return "GAME";
        return "FEATURE";
    }

    private static String rating(String g) {
        switch (g) {
            case "SOCIAL": case "MAIL": return "12";
            case "MOVIES": case "NEWS": return "15";
            case "MONEY": return "18";
            case "PHOTO": case "TRAVEL": case "CALLS": return "U";
            default: return "PG";
        }
    }

    private static String tagline(String g, int seed) {
        String[] o;
        switch (g) {
            case "SOCIAL": o = new String[]{"THIS TIME, IT'S PERSONAL.", "EVERYONE'S TALKING."}; break;
            case "GAME": o = new String[]{"GAME ON.", "INSERT COIN TO CONTINUE."}; break;
            case "MOVIES": o = new String[]{"NOW PLAYING.", "GRAB THE POPCORN."}; break;
            case "MUSIC": o = new String[]{"TURN IT UP.", "FEEL THE BASS."}; break;
            case "PHOTO": o = new String[]{"SAY CHEESE.", "SMILE FOR THE CAMERA."}; break;
            case "NEWS": o = new String[]{"THE STORY CONTINUES.", "STOP THE PRESSES."}; break;
            case "TRAVEL": o = new String[]{"THE JOURNEY BEGINS.", "ARE WE THERE YET?"}; break;
            case "WORK": o = new String[]{"GET IT DONE.", "THE DEADLINE IS COMING."}; break;
            case "MONEY": o = new String[]{"FOLLOW THE MONEY.", "EVERY CENT COUNTS."}; break;
            case "MAIL": o = new String[]{"YOU'VE GOT POST.", "RETURN TO SENDER."}; break;
            case "CALLS": o = new String[]{"DON'T HANG UP.", "IT'S FOR YOU."}; break;
            default: o = new String[]{"COMING SOON TO A PHONE NEAR YOU.", "ONE APP. ONE MISSION."};
        }
        return o[seed % o.length];
    }

    static int ratingColor(String r) {
        switch (r) {
            case "U": return 0xFF2E9E44;
            case "PG": return 0xFFE8B400;
            case "12": return 0xFFF07D00;
            case "15": return 0xFFE5007E;
            default: return 0xFFD2001E;
        }
    }

    // ------------------------------------------------------------------ staff picks (favourites)

    private void applyDefaultPicks() {
        if (prefs.contains(PREF_PICKS)) return;
        for (String pkg : DEFAULT_PICKS) {
            for (Tape t : tapes) {
                if (t.pkg.equals(pkg)) {
                    picks.add(t.cn.flattenToString());
                    break;
                }
            }
            if (picks.size() >= 6) break;
        }
        savePicks();
    }

    private boolean isPick(Tape t) { return picks.contains(t.cn.flattenToString()); }

    private void togglePick(Tape t) {
        String k = t.cn.flattenToString();
        if (!picks.remove(k)) picks.add(k);
        savePicks();
        renderPicks();
    }

    private void savePicks() { prefs.edit().putString(PREF_PICKS, TextUtils.join("\n", picks)).apply(); }

    private List<Tape> pickTapes() {
        Map<String, Tape> by = new HashMap<>();
        for (Tape t : tapes) by.put(t.cn.flattenToString(), t);
        List<Tape> out = new ArrayList<>();
        for (String k : picks) {
            Tape t = by.get(k);
            if (t != null) out.add(t);
        }
        return out;
    }

    private void renderPicks() {
        picksBox.removeAllViews();
        if (!loaded) {
            TextView t = text(cond, 16, CREAM);
            t.setText("STOCKING THE SHELVES...");
            t.setPadding(dp(8), dp(24), dp(8), dp(24));
            picksBox.addView(t);
            return;
        }
        List<Tape> list = pickTapes();
        int total = list.size() + 1;
        for (int start = 0; start < total; start += 3) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.BOTTOM);
            row.setPadding(dp(4), dp(14), dp(4), 0);
            for (int c = 0; c < 3; c++) {
                int i = start + c;
                View cell;
                if (i < list.size()) {
                    final Tape t = list.get(i);
                    final CoverView cv = new CoverView(a, t, true, display, cond, condItalic);
                    cv.setOnClickListener(v -> play(t, cv));
                    cv.setOnLongClickListener(v -> {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        pickMenu(t, cv);
                        return true;
                    });
                    cell = cv;
                } else if (i == list.size()) {
                    AddSlotView add = new AddSlotView(a, cond);
                    add.setOnClickListener(v -> openDrawer(true));
                    cell = add;
                } else {
                    cell = new View(a);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
                lp.setMargins(dp(5), 0, dp(5), 0);
                row.addView(cell, lp);
            }
            picksBox.addView(row);
            View plank = new View(a);
            plank.setBackground(new Plank(d));
            picksBox.addView(plank, new LinearLayout.LayoutParams(MATCH, dp(14)));
        }
    }

    private void pickMenu(Tape t, View cover) {
        menu("\"" + t.label + "\"", new String[]{"▶  PLAY", "◀  MOVE LEFT", "MOVE RIGHT  ▶", "REMOVE FROM STAFF PICKS"}, i -> {
            String k = t.cn.flattenToString();
            int at = picks.indexOf(k);
            switch (i) {
                case 0: play(t, cover); return;
                case 1: if (at > 0) Collections.swap(picks, at, at - 1); break;
                case 2: if (at >= 0 && at < picks.size() - 1) Collections.swap(picks, at, at + 1); break;
                case 3: picks.remove(k); break;
                default: return;
            }
            savePicks();
            renderPicks();
        });
    }

    // ------------------------------------------------------------------ building the screens

    private void buildHome() {
        root.setBackground(new StoreWall(d));
        LinearLayout home = new LinearLayout(a);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setPadding(dp(16), dp(10), dp(16), dp(12));
        root.addView(home, match());

        neon = text(display, 40, PINK);
        neon.setText("REWIND VIDEO");
        neon.setGravity(Gravity.CENTER);
        neon.setShadowLayer(16 * d, 0, 0, PINK);
        neon.setSingleLine(true);
        home.addView(neon, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView sub = text(cond, 12, CYAN);
        sub.setText("OPEN 24/7  ·  NO LATE FEES  ·  BE KIND, REWIND");
        sub.setGravity(Gravity.CENTER);
        sub.setLetterSpacing(0.1f);
        sub.setShadowLayer(8 * d, 0, 0, CYAN);
        home.addView(sub, new LinearLayout.LayoutParams(MATCH, WRAP));

        // VCR deck with a vacuum-fluorescent display
        LinearLayout deck = new LinearLayout(a);
        deck.setOrientation(LinearLayout.HORIZONTAL);
        deck.setGravity(Gravity.CENTER_VERTICAL);
        deck.setBackground(deckBg());
        deck.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout vfd = new LinearLayout(a);
        vfd.setOrientation(LinearLayout.HORIZONTAL);
        vfd.setGravity(Gravity.CENTER_VERTICAL);
        vfd.setBackground(box(0xFF03080C, 0xFF000000, 2, 6));
        vfd.setPadding(dp(12), dp(6), dp(12), dp(6));
        TextView mode = vfdText(13);
        mode.setText("▶ PLAY\nSP");
        vfdClock = vfdText(34);
        vfdClock.setGravity(Gravity.CENTER);
        vfdClock.setOnClickListener(v -> a.openQuietly(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        vfdBatt = vfdText(13);
        vfdBatt.setGravity(Gravity.END);
        vfdBatt.setText("TAPE\n--%");
        vfd.addView(mode, new LinearLayout.LayoutParams(WRAP, WRAP));
        vfd.addView(vfdClock, new LinearLayout.LayoutParams(0, WRAP, 1f));
        vfd.addView(vfdBatt, new LinearLayout.LayoutParams(WRAP, WRAP));
        deck.addView(vfd, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(MATCH, WRAP);
        dl.topMargin = dp(12);
        home.addView(deck, dl);

        TextView hdr = text(cond, 17, INK);
        hdr.setText("★ STAFF PICKS ★");
        hdr.setBackground(box(CARD, 0xFF8A6A3A, 1, 3));
        hdr.setPadding(dp(14), dp(4), dp(14), dp(4));
        hdr.setRotation(-2f);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(WRAP, WRAP);
        hl.topMargin = dp(16);
        hl.leftMargin = dp(4);
        home.addView(hdr, hl);

        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        picksBox = new LinearLayout(a);
        picksBox.setOrientation(LinearLayout.VERTICAL);
        picksBox.setPadding(0, 0, 0, dp(12));
        sv.addView(picksBox, new FrameLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        sl.topMargin = dp(2);
        home.addView(sv, sl);

        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        TextView browse = text(display, 18, CYAN);
        browse.setText("BROWSE ALL TAPES  ▲");
        browse.setGravity(Gravity.CENTER);
        browse.setBackground(box(0x2218F2FF, CYAN, 3, 14));
        browse.setShadowLayer(10 * d, 0, 0, CYAN);
        browse.setPadding(dp(8), dp(14), dp(8), dp(14));
        browse.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            openDrawer(true);
        });
        TextView menuB = text(display, 22, PINK);
        menuB.setText("≡");
        menuB.setGravity(Gravity.CENTER);
        menuB.setBackground(box(0x22FF3DA5, PINK, 3, 14));
        menuB.setShadowLayer(10 * d, 0, 0, PINK);
        menuB.setPadding(dp(20), dp(8), dp(20), dp(8));
        menuB.setOnClickListener(v -> mainMenu());
        bottom.addView(browse, new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(WRAP, MATCH);
        ml.leftMargin = dp(10);
        bottom.addView(menuB, ml);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(MATCH, WRAP);
        bl.topMargin = dp(10);
        home.addView(bottom, bl);
    }

    private void buildDrawer() {
        drawer = new FrameLayout(a);
        drawer.setBackground(new WoodWall(d));
        drawer.setVisibility(View.GONE);
        drawer.setClickable(true);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(10), dp(12), 0);

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(display, 26, CREAM);
        title.setText("ALL TAPES");
        title.setShadowLayer(6 * d, 0, 2 * d, 0xFF000000);
        countV = text(cond, 12, 0xFFC9A877);
        countV.setLetterSpacing(0.08f);
        titles.addView(title);
        titles.addView(countV);
        top.addView(titles, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView close = text(display, 24, CREAM);
        close.setText("✕");
        close.setPadding(dp(16), dp(8), dp(8), dp(8));
        close.setOnClickListener(v -> closeDrawer(true));
        top.addView(close);
        col.addView(top);

        search = new EditText(a);
        search.setTypeface(cond);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setTextColor(INK);
        search.setHint("SEARCH TITLES...");
        search.setHintTextColor(0x996B4A2A);
        search.setBackground(box(0xFFFFF7E6, 0xFFFF7A1A, 2, 22));
        search.setPadding(dp(16), dp(10), dp(16), dp(10));
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void afterTextChanged(Editable s) { rebuildShelves(); }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(MATCH, WRAP);
        sl.topMargin = dp(8);
        sl.bottomMargin = dp(4);
        col.addView(search, sl);

        ListView list = new ListView(a);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(0));
        list.setVerticalScrollBarEnabled(false);
        list.setCacheColorHint(0);
        list.setAdapter(shelfAdapter);
        col.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        drawer.addView(col, match());
        root.addView(drawer, match());
    }

    private void rebuildShelves() {
        String q = search == null ? "" : search.getText().toString().trim().toUpperCase(Locale.US);
        shelves.clear();
        Shelf cur = null;
        int n = 0;
        for (Tape t : tapes) {
            if (!q.isEmpty() && !t.label.contains(q)) continue;
            char c = t.label.isEmpty() ? '#' : t.label.charAt(0);
            String letter = (c >= 'A' && c <= 'Z') ? String.valueOf(c) : "#";
            if (cur == null || !cur.letter.equals(letter)) {
                cur = new Shelf(letter);
                shelves.add(cur);
            }
            cur.tapes.add(t);
            n++;
        }
        countV.setText(n + " TAPES IN STOCK" + (q.isEmpty() ? "" : "  ·  \"" + q + "\""));
        shelfAdapter.notifyDataSetChanged();
    }

    private static final class ShelfHolder {
        TextView tab;
        HorizontalScrollView scroll;
        LinearLayout spines;
    }

    private final class ShelfAdapter extends BaseAdapter {
        @Override public int getCount() { return shelves.size(); }
        @Override public Object getItem(int p) { return shelves.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int pos, View cv, ViewGroup parent) {
            ShelfHolder hd;
            if (cv == null) {
                LinearLayout row = new LinearLayout(a);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(10), 0, 0);
                hd = new ShelfHolder();
                hd.tab = text(display, 20, INK);
                GradientDrawable tab = new GradientDrawable();
                tab.setColor(CREAM);
                float rr = 8 * d;
                tab.setCornerRadii(new float[]{rr, rr, rr, rr, 0, 0, 0, 0});
                hd.tab.setBackground(tab);
                hd.tab.setPadding(dp(14), dp(2), dp(14), dp(2));
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(WRAP, WRAP);
                tl.leftMargin = dp(6);
                row.addView(hd.tab, tl);
                hd.scroll = new HorizontalScrollView(a);
                hd.scroll.setHorizontalScrollBarEnabled(false);
                hd.spines = new LinearLayout(a);
                hd.spines.setOrientation(LinearLayout.HORIZONTAL);
                hd.spines.setGravity(Gravity.BOTTOM);
                hd.spines.setPadding(dp(8), dp(44), dp(8), 0); // headroom so a tape can slide up off the shelf
                hd.scroll.addView(hd.spines, new FrameLayout.LayoutParams(WRAP, WRAP));
                row.addView(hd.scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
                View plank = new View(a);
                plank.setBackground(new Plank(d));
                row.addView(plank, new LinearLayout.LayoutParams(MATCH, dp(16)));
                row.setTag(hd);
                cv = row;
            } else {
                hd = (ShelfHolder) cv.getTag();
            }
            Shelf s = shelves.get(pos);
            hd.tab.setText(s.letter);
            hd.spines.removeAllViews();
            for (Tape t : s.tapes) {
                SpineView sp = new SpineView(a, t, display, cond);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(42 + (t.seed % 4) * 3), dp(214));
                lp.rightMargin = dp(2);
                sp.setOnClickListener(v -> pull((SpineView) v, t));
                sp.setOnLongClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    tapeMenu(t);
                    return true;
                });
                hd.spines.addView(sp, lp);
            }
            hd.scroll.scrollTo(0, 0);
            return cv;
        }
    }

    // ------------------------------------------------------------------ drawer + tape handling

    void openDrawer(boolean anim) {
        if (drawer.getVisibility() == View.VISIBLE) return;
        drawer.setVisibility(View.VISIBLE);
        if (anim && root.getHeight() > 0) {
            drawer.setTranslationY(root.getHeight());
            drawer.animate().translationY(0).setDuration(380).setInterpolator(new DecelerateInterpolator(1.8f)).start();
        } else {
            drawer.setTranslationY(0);
        }
    }

    void closeDrawer(boolean anim) {
        hideKeyboard();
        if (drawer.getVisibility() != View.VISIBLE) return;
        if (!anim) {
            drawer.setVisibility(View.GONE);
            return;
        }
        drawer.animate().translationY(root.getHeight()).setDuration(280).setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> drawer.setVisibility(View.GONE)).start();
    }

    /** Tape slides up off the shelf, then turns to show its cover. */
    private void pull(SpineView sp, Tape t) {
        if (busy) return;
        busy = true;
        hideKeyboard();
        sp.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        sp.animate().translationY(-dp(38)).setDuration(170).setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
            openCase(t, sp);
            sp.animate().translationY(0).setStartDelay(500).setDuration(220).start();
        }).start();
    }

    private void openCase(Tape t, View from) {
        modal.animate().cancel();
        modal.removeAllViews();
        modal.setAlpha(1f);
        modal.setVisibility(View.VISIBLE);
        View dim = new View(a);
        dim.setBackgroundColor(0xE6000000);
        dim.setAlpha(0f);
        dim.setOnClickListener(v -> putBack());
        modal.addView(dim, match());
        dim.animate().alpha(1f).setDuration(250).start();

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int coverW = Math.min(dp(290), Math.round(Math.max(root.getWidth(), dp(320)) * 0.72f));
        final CoverView cover = new CoverView(a, t, false, display, cond, condItalic);
        cover.setOnClickListener(v -> play(t, cover));
        box.addView(cover, new LinearLayout.LayoutParams(coverW, WRAP));

        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView playB = button("▶ PLAY", PINK, true);
        playB.setOnClickListener(v -> play(t, cover));
        final TextView pickB = button(isPick(t) ? "★ PICKED" : "☆ PICK", CYAN, false);
        pickB.setOnClickListener(v -> {
            togglePick(t);
            pickB.setText(isPick(t) ? "★ PICKED" : "☆ PICK");
        });
        TextView backB = button("PUT BACK", CREAM, false);
        backB.setOnClickListener(v -> putBack());
        btns.addView(playB, weighted(1.25f));
        btns.addView(pickB, weighted(1f));
        btns.addView(backB, weighted(1f));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(coverW + dp(48), WRAP);
        bp.topMargin = dp(18);
        box.addView(btns, bp);
        TextView meta = text(cond, 12, 0xFFBBBBBB);
        meta.setText(t.genre + "  ·  " + t.length + "  ·  RATED " + t.rating);
        meta.setGravity(Gravity.CENTER);
        meta.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(WRAP, WRAP);
        mp.topMargin = dp(10);
        box.addView(meta, mp);
        btns.setAlpha(0f);
        meta.setAlpha(0f);
        btns.animate().alpha(1f).setStartDelay(320).setDuration(220).start();
        meta.animate().alpha(1f).setStartDelay(380).setDuration(220).start();
        modal.addView(box, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));

        current = cover;
        cover.setAlpha(0f);
        cover.setCameraDistance(9000 * d);
        cover.post(() -> {
            int[] fl = new int[2], cl = new int[2];
            from.getLocationInWindow(fl);
            cover.getLocationInWindow(cl);
            float fx = fl[0] + from.getWidth() / 2f, fy = fl[1] + from.getHeight() / 2f;
            float cx = cl[0] + cover.getWidth() / 2f, cy = cl[1] + cover.getHeight() / 2f;
            cover.setTranslationX(fx - cx);
            cover.setTranslationY(fy - cy);
            cover.setScaleX(Math.max(0.05f, from.getWidth() / (float) Math.max(1, cover.getWidth())));
            cover.setScaleY(Math.max(0.05f, from.getHeight() / (float) Math.max(1, cover.getHeight())));
            cover.setRotationY(-75f);
            cover.setAlpha(1f);
            cover.animate().translationX(0).translationY(0).scaleX(1f).scaleY(1f).rotationY(0f).setDuration(460)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).withEndAction(() -> busy = false).start();
        });
    }

    private void putBack() {
        if (modal.getVisibility() != View.VISIBLE || playing) return;
        if (current != null) current.animate().rotationY(75f).scaleX(0.2f).scaleY(0.6f).alpha(0f).setDuration(260).start();
        modal.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            modal.setVisibility(View.GONE);
            modal.setAlpha(1f);
            modal.removeAllViews();
            busy = false;
        }).start();
    }

    /** Cassette slides out of the sleeve, drops into the VCR, static, then the app opens. */
    private void play(Tape t, View cover) {
        if (playing) return;
        playing = true;
        busy = true;
        hideKeyboard();
        cover.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (modal.getVisibility() != View.VISIBLE) {
            modal.animate().cancel();
            modal.removeAllViews();
            modal.setAlpha(1f);
            modal.setVisibility(View.VISIBLE);
            View dim = new View(a);
            dim.setBackgroundColor(0xD0000000);
            dim.setAlpha(0f);
            modal.addView(dim, match());
            dim.animate().alpha(1f).setDuration(220).start();
        }
        int[] ml = new int[2], cl = new int[2];
        modal.getLocationInWindow(ml);
        cover.getLocationInWindow(cl);
        float cw = cover.getWidth() * cover.getScaleX(), ch = cover.getHeight() * cover.getScaleY();
        float cx = cl[0] - ml[0], cy = cl[1] - ml[1];
        float mw = Math.max(1, modal.getWidth()), mh = Math.max(1, modal.getHeight());
        float casW = Math.min(mw * 0.86f, Math.max(cw * 0.92f, dp(250)));
        float casH = casW * 0.62f;
        CassetteView cas = new CassetteView(a, t, cond, hand);
        modal.addView(cas, Math.min(1, modal.getChildCount()), new FrameLayout.LayoutParams(Math.round(casW), Math.round(casH)));
        float centerX = cx + cw / 2f;
        cas.setX(centerX - casW / 2f);
        cas.setY(cy + ch * 0.3f);
        cas.setScaleX(Math.min(1f, cw * 0.92f / casW));
        cas.setScaleY(Math.min(1f, cw * 0.92f / casW));

        cover.animate().translationYBy(ch * 0.6f).alpha(0f).setStartDelay(100).setDuration(450)
                .setInterpolator(new AccelerateInterpolator()).start();
        cas.animate().y(Math.max(dp(30), cy - casH * 0.25f)).scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(() ->
                        cas.animate().x((mw - casW) / 2f).y(mh * 0.36f).setDuration(260).withEndAction(() ->
                                cas.animate().y(mh + casH).scaleX(0.82f).scaleY(0.82f).setDuration(300)
                                        .setInterpolator(new AccelerateInterpolator()).withEndAction(() -> tvStatic(t)).start()
                        ).start()
                ).start();
    }

    private void tvStatic(Tape t) {
        staticView = new StaticView(a, pixel);
        root.addView(staticView, match());
        h.postDelayed(() -> {
            if (!a.startComponent(t.cn)) resetAfterLaunch();
        }, 450);
        h.postDelayed(this::resetAfterLaunch, 1400);
    }

    private void resetAfterLaunch() {
        if (staticView != null) {
            root.removeView(staticView);
            staticView = null;
        }
        modal.animate().cancel();
        modal.setVisibility(View.GONE);
        modal.removeAllViews();
        modal.setAlpha(1f);
        if (playing) {
            closeDrawer(false);
            renderPicks();
        }
        playing = false;
        busy = false;
    }

    private void tapeMenu(Tape t) {
        String pickLabel = isPick(t) ? "REMOVE FROM STAFF PICKS" : "★ ADD TO STAFF PICKS";
        menu("\"" + t.label + "\"", new String[]{"▶  PLAY", pickLabel, "APP INFO", "UNINSTALL"}, i -> {
            switch (i) {
                case 0: a.startComponent(t.cn); break;
                case 1: togglePick(t); break;
                case 2: a.openQuietly(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + t.pkg))); break;
                case 3: a.openQuietly(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + t.pkg))); break;
                default: break;
            }
        });
    }

    // ------------------------------------------------------------------ menus

    private void mainMenu() {
        menu("REWIND VIDEO", new String[]{"CHANGE SKIN", "SYSTEM SETTINGS", "CHOOSE DEFAULT LAUNCHER", "ALARMS"}, i -> {
            switch (i) {
                case 0: skinMenu(); break;
                case 1: a.openQuietly(new Intent(Settings.ACTION_SETTINGS)); break;
                case 2: a.openQuietly(new Intent(Settings.ACTION_HOME_SETTINGS)); break;
                case 3: a.openQuietly(new Intent(AlarmClock.ACTION_SHOW_ALARMS)); break;
                default: break;
            }
        });
    }

    private void skinMenu() {
        String[] opts = new String[Skins.NAMES.length];
        for (int i = 0; i < opts.length; i++) opts[i] = Skins.NAMES[i] + ("VHS".equals(Skins.NAMES[i]) ? "  ◀" : "");
        menu("SELECT SKIN", opts, i -> a.switchSkinTo(Skins.NAMES[i]));
    }

    private void menu(String title, String[] opts, IntConsumer cb) {
        menuLayer.removeAllViews();
        menuLayer.setVisibility(View.VISIBLE);
        View dim = new View(a);
        dim.setBackgroundColor(0xB0000000);
        dim.setOnClickListener(v -> closeMenu());
        menuLayer.addView(dim, match());
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(deckBg());
        panel.setPadding(dp(14), dp(14), dp(14), dp(10));
        panel.setClickable(true);
        TextView tt = vfdText(15);
        tt.setText(title);
        tt.setSingleLine(true);
        tt.setEllipsize(TextUtils.TruncateAt.END);
        tt.setBackground(box(0xFF03080C, 0xFF000000, 2, 6));
        tt.setPadding(dp(10), dp(6), dp(10), dp(6));
        panel.addView(tt, new LinearLayout.LayoutParams(MATCH, WRAP));
        ScrollView sv = new ScrollView(a);
        LinearLayout items = new LinearLayout(a);
        items.setOrientation(LinearLayout.VERTICAL);
        sv.addView(items, new FrameLayout.LayoutParams(MATCH, WRAP));
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            TextView o = text(cond, 18, CREAM);
            o.setText(opts[i]);
            o.setPadding(dp(8), dp(12), dp(8), dp(12));
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(0x33FFFFFF));
            o.setBackground(sl);
            o.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                closeMenu();
                cb.accept(idx);
            });
            items.addView(o, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        panel.addView(sv, new LinearLayout.LayoutParams(MATCH, WRAP));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER);
        pl.setMargins(dp(28), dp(60), dp(28), dp(60));
        menuLayer.addView(panel, pl);
    }

    private void closeMenu() {
        menuLayer.removeAllViews();
        menuLayer.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ small helpers

    private int dp(float v) { return Math.round(v * d); }

    private static FrameLayout.LayoutParams match() { return new FrameLayout.LayoutParams(MATCH, MATCH); }

    private LinearLayout.LayoutParams weighted(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, w);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private TextView text(Typeface tf, float sp, int color) {
        TextView t = new TextView(a);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView vfdText(float sp) {
        TextView t = text(pixel, sp, VFD);
        t.setShadowLayer(6 * d, 0, 0, Skin.alpha(VFD, 0xC0));
        return t;
    }

    private TextView button(String label, int color, boolean filled) {
        TextView b = text(display, 15, filled ? 0xFF14070F : color);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setBackground(box(filled ? color : Skin.alpha(color, 0x22), color, 2, 12));
        if (!filled) b.setShadowLayer(8 * d, 0, 0, color);
        b.setPadding(dp(6), dp(12), dp(6), dp(12));
        return b;
    }

    private GradientDrawable box(int fill, int stroke, float strokeDp, float radiusDp) {
        return Drawables.box(fill, stroke, strokeDp * d, radiusDp * d);
    }

    private GradientDrawable deckBg() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF3A3D45, 0xFF1C1D22});
        g.setCornerRadius(12 * d);
        g.setStroke(Math.round(1.5f * d), 0xFF5A5E68);
        return g;
    }

    private void hideKeyboard() {
        if (search == null) return;
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    static float fit(Paint p, String s, float maxW, float start, float min) {
        float size = start;
        p.setTextSize(size);
        while (size > min && p.measureText(s) > maxW) {
            size *= 0.94f;
            p.setTextSize(size);
        }
        return size;
    }

    static int tint(int c, float f) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r += (int) ((255 - r) * f);
        g += (int) ((255 - g) * f);
        b += (int) ((255 - b) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static String studio(String pkg) {
        String[] parts = pkg.split("\\.");
        return (parts.length > 1 ? parts[1] : pkg).toUpperCase(Locale.US);
    }

    // ================================================================== views

    /** The face-out box: poster art built from the app's own icon and colours. */
    static final class CoverView extends View {
        private final Tape t;
        private final boolean mini;
        private final Typeface display, cond, condItalic;
        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        CoverView(Context c, Tape t, boolean mini, Typeface display, Typeface cond, Typeface condItalic) {
            super(c);
            this.t = t;
            this.mini = mini;
            this.display = display;
            this.cond = cond;
            this.condItalic = condItalic;
            d = c.getResources().getDisplayMetrics().density;
        }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            setMeasuredDimension(w, Math.round(w * 1.5f));
        }

        @Override protected void onDraw(Canvas c) {
            float W = getWidth(), H = getHeight();
            if (W <= 0) return;
            float sh = Math.max(2 * d, W * 0.025f);
            float w = W - sh, h = H - sh, r = w * 0.035f, cx = w / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(0x80000000);
            c.drawRoundRect(sh, sh, W, H, r, r, p);
            Path clip = new Path();
            clip.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);

            // poster background + sunburst + vignette
            p.setShader(new LinearGradient(0, 0, w * 0.35f, h, t.color, t.color2, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            float iy = mini ? h * 0.6f : h * 0.55f;
            p.setColor(0x16FFFFFF);
            int off = t.seed % 20;
            for (int i = 0; i < 18; i += 2) {
                Path wedge = new Path();
                wedge.moveTo(cx, iy);
                double a0 = Math.toRadians(i * 20 + off), a1 = Math.toRadians((i + 1) * 20 + off);
                wedge.lineTo(cx + (float) Math.cos(a0) * h, iy + (float) Math.sin(a0) * h);
                wedge.lineTo(cx + (float) Math.cos(a1) * h, iy + (float) Math.sin(a1) * h);
                wedge.close();
                c.drawPath(wedge, p);
            }
            p.setShader(new RadialGradient(cx, iy, h * 0.75f, new int[]{0x00000000, 0x99000000}, new float[]{0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);

            // studio band
            float band = h * 0.07f;
            p.setColor(0xE6000000);
            c.drawRect(0, 0, w, band, p);
            p.setTypeface(cond);
            p.setColor(CREAM);
            p.setLetterSpacing(0.15f);
            String studioLine = mini ? "REWIND PICTURES" : "REWIND PICTURES PRESENTS";
            fit(p, studioLine, w * 0.88f, band * 0.5f, 3 * d);
            c.drawText(studioLine, cx, band * 0.68f, p);
            p.setLetterSpacing(0f);

            // title: up to two lines, shrunk to fit, gold gradient with a dark outline
            p.setTypeface(display);
            float maxW = w * 0.86f, size = w * 0.17f;
            List<String> lines;
            while (true) {
                p.setTextSize(size);
                lines = wrap(p, t.label, maxW);
                if ((lines.size() <= 2 && widest(p, lines) <= maxW) || size < w * 0.06f) break;
                size *= 0.92f;
            }
            if (lines.size() > 2) lines = lines.subList(0, 2);
            float ty = band + size * 1.15f;
            for (String ln : lines) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(size * 0.14f);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(0xD0000000);
                c.drawText(ln, cx, ty, p);
                p.setStyle(Paint.Style.FILL);
                p.setShader(new LinearGradient(0, ty - size, 0, ty, 0xFFFFFFFF, 0xFFFFC85A, Shader.TileMode.CLAMP));
                c.drawText(ln, cx, ty, p);
                p.setShader(null);
                ty += size * 1.02f;
            }
            if (!mini) {
                p.setTypeface(condItalic);
                p.setColor(0xEEFFFFFF);
                fit(p, t.tagline, w * 0.86f, w * 0.05f, 5 * d);
                c.drawText(t.tagline, cx, ty + size * 0.02f, p);
            }

            // the "star": the app icon, lit from behind
            float is = w * (mini ? 0.46f : 0.40f);
            p.setShader(new RadialGradient(cx, iy, is, new int[]{0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, iy, is, p);
            p.setShader(null);
            p.setColor(0x66000000);
            c.drawOval(cx - is * 0.45f, iy + is * 0.42f, cx + is * 0.45f, iy + is * 0.58f, p);
            c.drawBitmap(t.icon, null, new RectF(cx - is / 2, iy - is / 2, cx + is / 2, iy + is / 2), p);

            if (!mini) {
                float qy = h * 0.785f;
                p.setTypeface(cond);
                p.setColor(0xFFFFD84D);
                p.setTextSize(w * 0.055f);
                c.drawText("★★★★★", cx, qy, p);
                p.setTypeface(condItalic);
                p.setColor(0xFFFFFFFF);
                fit(p, t.quote, w * 0.88f, w * 0.05f, 5 * d);
                c.drawText(t.quote, cx, qy + w * 0.065f, p);
                p.setTypeface(cond);
                p.setColor(0x99FFFFFF);
                p.setTextSize(w * 0.026f);
                String bill = "REWIND PICTURES PRESENTS A " + studio(t.pkg) + " PRODUCTION  ·  " + t.label
                        + "  ·  RUNNING TIME " + t.minutes + " MINUTES  ·  " + t.pkg.toUpperCase(Locale.US)
                        + "  ·  ALL RIGHTS REWOUND";
                drawWrapped(c, p, bill, cx, h * 0.87f, w * 0.78f, w * 0.032f, 3);
            }

            // rating badge
            float rb = w * (mini ? 0.17f : 0.11f), rx = w * 0.05f, ry = h - rb - w * 0.05f;
            p.setColor(0xFFFFFFFF);
            c.drawRect(rx, ry, rx + rb, ry + rb, p);
            p.setColor(ratingColor(t.rating));
            c.drawRect(rx + rb * 0.08f, ry + rb * 0.08f, rx + rb * 0.92f, ry + rb * 0.92f, p);
            p.setColor(0xFFFFFFFF);
            p.setTypeface(display);
            p.setTextSize(rb * (t.rating.length() > 1 ? 0.5f : 0.66f));
            c.drawText(t.rating, rx + rb / 2, ry + rb * 0.7f, p);
            if (!mini) {
                float vw = w * 0.16f, vh = w * 0.075f, vx = w - vw - w * 0.05f, vy = h - vh - w * 0.06f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f * d);
                p.setColor(0xDDFFFFFF);
                c.drawRoundRect(vx, vy, vx + vw, vy + vh, 3 * d, 3 * d, p);
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(vh * 0.72f);
                c.drawText("VHS", vx + vw / 2, vy + vh * 0.78f, p);
            }
            if (t.isNew) starburst(c, w * 0.8f, h * (mini ? 0.2f : 0.19f), w * (mini ? 0.17f : 0.13f));

            // plastic sleeve sheen
            p.setShader(new LinearGradient(0, 0, w, h, new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF},
                    new float[]{0.25f, 0.4f, 0.55f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(0x40FFFFFF);
            c.drawRoundRect(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d, r, r, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.LEFT);
        }

        private void starburst(Canvas c, float x, float y, float r) {
            Path s = new Path();
            for (int i = 0; i < 32; i++) {
                double a = Math.toRadians(i * 11.25);
                float rr = i % 2 == 0 ? r : r * 0.74f;
                float px = x + (float) Math.cos(a) * rr, py = y + (float) Math.sin(a) * rr;
                if (i == 0) s.moveTo(px, py);
                else s.lineTo(px, py);
            }
            s.close();
            p.setColor(0xFFFFD21F);
            c.drawPath(s, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f * d);
            p.setColor(0xFFE0301E);
            c.drawPath(s, p);
            p.setStyle(Paint.Style.FILL);
            c.save();
            c.rotate(-12, x, y);
            p.setTypeface(display);
            p.setTextSize(r * 0.42f);
            c.drawText("NEW", x, y - r * 0.02f, p);
            p.setTextSize(r * 0.26f);
            c.drawText("RELEASE", x, y + r * 0.3f, p);
            c.restore();
        }

        private static List<String> wrap(Paint p, String text, float maxW) {
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                if (word.isEmpty()) continue;
                String test = line.length() == 0 ? word : line + " " + word;
                if (p.measureText(test) <= maxW || line.length() == 0) {
                    line.setLength(0);
                    line.append(test);
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            if (line.length() > 0) out.add(line.toString());
            if (out.isEmpty()) out.add(text);
            return out;
        }

        private static float widest(Paint p, List<String> lines) {
            float m = 0;
            for (String s : lines) m = Math.max(m, p.measureText(s));
            return m;
        }

        private static void drawWrapped(Canvas c, Paint p, String text, float cx, float y, float maxW, float lineH, int maxLines) {
            List<String> lines = wrap(p, text, maxW);
            for (int i = 0; i < lines.size() && i < maxLines; i++) c.drawText(lines.get(i), cx, y + i * lineH, p);
        }
    }

    /** The tape as it sits on the shelf: rounded plastic spine with the app's colours. */
    static final class SpineView extends View {
        private final Tape t;
        private final Typeface display, cond;
        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        SpineView(Context c, Tape t, Typeface display, Typeface cond) {
            super(c);
            this.t = t;
            this.display = display;
            this.cond = cond;
            d = c.getResources().getDisplayMetrics().density;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = 3 * d;
            if (w <= 0) return;
            float[] pos = {0f, 0.25f, 0.45f, 0.7f, 1f};
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, w, 0, new int[]{Skin.scale(t.color, 0.55f), t.color,
                    tint(t.color, 0.25f), t.color, Skin.scale(t.color, 0.55f)}, pos, Shader.TileMode.CLAMP));
            c.drawRoundRect(0, 0, w, h, r, r, p);
            float band = h * 0.2f;
            p.setShader(new LinearGradient(0, 0, w, 0, new int[]{Skin.scale(t.color2, 0.6f), t.color2,
                    tint(t.color2, 0.2f), t.color2, Skin.scale(t.color2, 0.6f)}, pos, Shader.TileMode.CLAMP));
            c.drawRoundRect(0, 0, w, band, r, r, p);
            c.drawRect(0, band - r, w, band, p);
            p.setShader(null);

            // app icon on a white label
            float s = w * 0.7f, ix = (w - s) / 2f, iy = (band - s) / 2f;
            p.setColor(0xFFFFFFFF);
            c.drawRoundRect(ix - 2 * d, iy - 2 * d, ix + s + 2 * d, iy + s + 2 * d, 5 * d, 5 * d, p);
            c.drawBitmap(t.icon, null, new RectF(ix, iy, ix + s, iy + s), p);
            p.setColor(0x55000000);
            c.drawRect(0, band, w, band + 1.5f * d, p);

            // bottom block: genre label, rating box, tape length
            float bh = h * 0.21f, by = h - bh;
            p.setColor(0xF0FFFFFF);
            c.drawRect(w * 0.1f, by, w * 0.9f, by + bh * 0.3f, p);
            p.setTypeface(cond);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(0xFF111111);
            fit(p, t.genre, w * 0.74f, bh * 0.22f, 4 * d);
            c.drawText(t.genre, w / 2f, by + bh * 0.235f, p);
            float rb = w * 0.46f, rx = (w - rb) / 2f, ry = by + bh * 0.38f;
            p.setColor(0xFFFFFFFF);
            c.drawRect(rx, ry, rx + rb, ry + rb, p);
            p.setColor(ratingColor(t.rating));
            c.drawRect(rx + rb * 0.1f, ry + rb * 0.1f, rx + rb * 0.9f, ry + rb * 0.9f, p);
            p.setColor(0xFFFFFFFF);
            p.setTypeface(display);
            p.setTextSize(rb * (t.rating.length() > 1 ? 0.48f : 0.62f));
            c.drawText(t.rating, w / 2f, ry + rb * 0.7f, p);
            p.setTypeface(cond);
            p.setColor(Skin.alpha(t.ink, 0xB0));
            fit(p, t.length, w * 0.8f, bh * 0.16f, 4 * d);
            c.drawText(t.length, w / 2f, h - bh * 0.05f, p);

            // title, reading top to bottom like a real spine
            float tx0 = band + 8 * d, avail = by - 8 * d - tx0;
            c.save();
            c.rotate(90);
            p.setTypeface(display);
            p.setTextAlign(Paint.Align.LEFT);
            float size = w * 0.44f;
            p.setTextSize(size);
            String label = t.label;
            while (p.measureText(label) > avail && size > w * 0.24f) {
                size *= 0.95f;
                p.setTextSize(size);
            }
            if (p.measureText(label) > avail) {
                while (label.length() > 2 && p.measureText(label + "…") > avail) label = label.substring(0, label.length() - 1);
                label = label + "…";
            }
            float tw = p.measureText(label), x = tx0 + (avail - tw) / 2f, y = -w / 2f + size * 0.36f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(size * 0.12f);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(t.ink == 0xFFFFFFFF ? 0x99000000 : 0x77FFFFFF);
            c.drawText(label, x, y, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(t.ink);
            c.drawText(label, x, y, p);
            c.restore();

            // shop stickers
            p.setTextAlign(Paint.Align.CENTER);
            if (t.isNew) {
                float sr = w * 0.36f, sx = w / 2f, sy = by - sr - 4 * d;
                p.setColor(0xFFFF6A00);
                c.drawCircle(sx, sy, sr, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.2f * d);
                p.setColor(0xFFFFFFFF);
                c.drawCircle(sx, sy, sr, p);
                p.setStyle(Paint.Style.FILL);
                c.save();
                c.rotate(-20, sx, sy);
                p.setTypeface(display);
                p.setTextSize(sr * 0.62f);
                c.drawText("NEW", sx, sy + sr * 0.22f, p);
                c.restore();
            } else if (t.seed % 6 == 0) {
                float sy = h * 0.6f;
                c.save();
                c.rotate(-14, w / 2f, sy);
                p.setColor(0xFFFFE14D);
                c.drawRect(-w * 0.05f, sy - w * 0.17f, w * 1.05f, sy + w * 0.17f, p);
                p.setColor(0xFF111111);
                p.setTypeface(cond);
                fit(p, "REWIND", w * 0.9f, w * 0.24f, 4 * d);
                c.drawText("REWIND", w / 2f, sy + w * 0.08f, p);
                c.restore();
            }

            // plastic sheen + edge
            p.setShader(new LinearGradient(0, 0, w, 0, new int[]{0x00FFFFFF, 0x45FFFFFF, 0x00FFFFFF},
                    new float[]{0.08f, 0.2f, 0.34f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            p.setColor(0x80000000);
            c.drawRoundRect(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d, r, r, p);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.LEFT);
        }
    }

    /** The cassette itself, with a hand-written label and turning reels. */
    static final class CassetteView extends FxViews.AnimView {
        private final Tape t;
        private final Typeface cond, hand;
        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float angle;

        CassetteView(Context c, Tape t, Typeface cond, Typeface hand) {
            super(c, 30);
            this.t = t;
            this.cond = cond;
            this.hand = hand;
            d = c.getResources().getDisplayMetrics().density;
        }

        @Override void step() { angle += 14; }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h * 0.06f;
            if (w <= 0) return;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFF2C2C2C, 0xFF0E0E0E, Shader.TileMode.CLAMP));
            c.drawRoundRect(0, 0, w, h, r, r, p);
            p.setShader(null);
            p.setColor(0xFF3A3A3A);
            c.drawRect(r, h * 0.02f, w - r, h * 0.04f, p);

            // label
            RectF lab = new RectF(w * 0.07f, h * 0.08f, w * 0.93f, h * 0.46f);
            p.setColor(0xFFF6F1E3);
            c.drawRoundRect(lab, 3 * d, 3 * d, p);
            p.setColor(t.color);
            c.drawRect(lab.left, lab.top, lab.right, lab.top + lab.height() * 0.2f, p);
            p.setColor(t.color2);
            c.drawRect(lab.left, lab.top + lab.height() * 0.2f, lab.right, lab.top + lab.height() * 0.27f, p);
            p.setTypeface(hand);
            p.setColor(0xFF1B2A6B);
            p.setTextAlign(Paint.Align.CENTER);
            fit(p, t.name, lab.width() * 0.88f, lab.height() * 0.34f, 6 * d);
            c.drawText(t.name, lab.centerX(), lab.top + lab.height() * 0.7f, p);
            p.setTypeface(cond);
            p.setTextSize(lab.height() * 0.13f);
            p.setColor(0xFF666666);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText("SP  " + t.length, lab.left + 5 * d, lab.bottom - 5 * d, p);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText("REWIND VIDEO", lab.right - 5 * d, lab.bottom - 5 * d, p);
            p.setTextAlign(Paint.Align.LEFT);

            // window with tape packs and turning hubs
            RectF win = new RectF(w * 0.24f, h * 0.52f, w * 0.76f, h * 0.8f);
            p.setColor(0xFF1A1A1A);
            c.drawRoundRect(win, 5 * d, 5 * d, p);
            float rr = win.height() * 0.36f, ry = win.centerY();
            float lx = win.left + win.width() * 0.26f, rx = win.right - win.width() * 0.26f;
            c.save();
            c.clipRect(win);
            p.setColor(0xFF4A2E1C);
            c.drawCircle(lx, ry, rr * 1.5f, p);
            c.drawCircle(rx, ry, rr * 0.95f, p);
            c.restore();
            for (int k = 0; k < 2; k++) {
                float hx = k == 0 ? lx : rx;
                p.setColor(0xFFEDEDED);
                c.drawCircle(hx, ry, rr * 0.5f, p);
                p.setColor(0xFF1A1A1A);
                c.drawCircle(hx, ry, rr * 0.22f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f * d);
                p.setColor(0xFF999999);
                for (int s = 0; s < 6; s++) {
                    double a = Math.toRadians(angle * (k == 0 ? 1 : 1.5) + s * 60);
                    c.drawLine(hx + (float) Math.cos(a) * rr * 0.24f, ry + (float) Math.sin(a) * rr * 0.24f,
                            hx + (float) Math.cos(a) * rr * 0.48f, ry + (float) Math.sin(a) * rr * 0.48f, p);
                }
                p.setStyle(Paint.Style.FILL);
            }
            p.setShader(new LinearGradient(0, win.top, 0, win.bottom, 0x30FFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP));
            c.drawRoundRect(win, 5 * d, 5 * d, p);
            p.setShader(null);

            // bottom lip and screws
            Path lip = new Path();
            lip.moveTo(w * 0.12f, h);
            lip.lineTo(w * 0.17f, h * 0.86f);
            lip.lineTo(w * 0.83f, h * 0.86f);
            lip.lineTo(w * 0.88f, h);
            lip.close();
            p.setColor(0xFF222222);
            c.drawPath(lip, p);
            p.setColor(0xFF5A5A5A);
            float sr = h * 0.022f;
            c.drawCircle(w * 0.05f, h * 0.06f, sr, p);
            c.drawCircle(w * 0.95f, h * 0.06f, sr, p);
            c.drawCircle(w * 0.05f, h * 0.92f, sr, p);
            c.drawCircle(w * 0.95f, h * 0.92f, sr, p);
        }
    }

    /** TV static with a VCR on-screen display, shown while the app opens. */
    static final class StaticView extends FxViews.AnimView {
        private static final int NW = 90, NH = 160;
        private final Bitmap noise = Bitmap.createBitmap(NW, NH, Bitmap.Config.ARGB_8888);
        private final int[] px = new int[NW * NH];
        private final Random r = new Random();
        private final Paint p = new Paint();
        private final Paint band = new Paint();
        private final Paint osd = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private float roll;

        StaticView(Context c, Typeface pixel) {
            super(c, 40);
            d = c.getResources().getDisplayMetrics().density;
            p.setFilterBitmap(false);
            band.setColor(0x55000000);
            osd.setTypeface(pixel);
            osd.setColor(0xFFFFFFFF);
            osd.setTextSize(26 * d);
            osd.setShadowLayer(2 * d, 2 * d, 2 * d, 0xFF000000);
            setClickable(true);
            step();
        }

        @Override void step() {
            for (int i = 0; i < px.length; i++) {
                int v = r.nextInt(256);
                px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
            }
            noise.setPixels(px, 0, NW, 0, 0, NW, NH);
            roll = (roll + 0.07f) % 1f;
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawBitmap(noise, null, new RectF(0, 0, w, h), p);
            c.drawRect(0, roll * h, w, roll * h + h * 0.12f, band);
            c.drawText("PLAY ▶", 24 * d, 64 * d, osd);
            c.drawText("SP", w - 70 * d, 64 * d, osd);
        }
    }

    /** Empty slot at the end of the staff picks. */
    static final class AddSlotView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface cond;
        private final float d;

        AddSlotView(Context c, Typeface cond) {
            super(c);
            this.cond = cond;
            d = c.getResources().getDisplayMetrics().density;
        }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws);
            setMeasuredDimension(w, Math.round(w * 1.5f));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0) return;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            p.setColor(0x88F4E3C1);
            p.setPathEffect(new DashPathEffect(new float[]{8 * d, 6 * d}, 0));
            c.drawRoundRect(2 * d, 2 * d, w - 2 * d, h - 2 * d, 8 * d, 8 * d, p);
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(cond);
            p.setTextSize(w * 0.4f);
            c.drawText("+", w / 2, h * 0.5f, p);
            p.setTextSize(w * 0.11f);
            c.drawText("ADD A PICK", w / 2, h * 0.64f, p);
        }
    }

    // ================================================================== backgrounds

    /** Store at night: dark purple wall, neon wash, 90s carpet. */
    static final class StoreWall extends Drawables.Base {
        private final float d;

        StoreWall(float d) { this.d = d; }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, b.top, 0, b.bottom, new int[]{0xFF1C1236, 0xFF0E0A1E, 0xFF07050F}, null, Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            p.setShader(new RadialGradient(b.centerX(), b.top + 40 * d, Math.max(1, b.width() * 0.75f),
                    new int[]{0x45FF3DA5, 0x00FF3DA5}, null, Shader.TileMode.CLAMP));
            c.drawRect(b, p);
            p.setShader(null);
            Random r = new Random(77);
            int[] cols = {PINK, CYAN, 0xFFFFD23F, 0xFF7B2FF7};
            float top = b.top + b.height() * 0.55f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
            for (int i = 0; i < 70; i++) {
                float x = b.left + r.nextFloat() * b.width(), y = top + r.nextFloat() * (b.bottom - top);
                p.setColor(Skin.alpha(cols[r.nextInt(cols.length)], 0x26));
                switch (r.nextInt(3)) {
                    case 0: c.drawCircle(x, y, (3 + r.nextInt(5)) * d, p); break;
                    case 1: {
                        Path t = new Path();
                        float s = (4 + r.nextInt(5)) * d;
                        t.moveTo(x, y - s);
                        t.lineTo(x + s, y + s);
                        t.lineTo(x - s, y + s);
                        t.close();
                        c.drawPath(t, p);
                        break;
                    }
                    default: {
                        Path q = new Path();
                        q.moveTo(x, y);
                        q.rQuadTo(4 * d, -6 * d, 8 * d, 0);
                        q.rQuadTo(4 * d, 6 * d, 8 * d, 0);
                        c.drawPath(q, p);
                    }
                }
            }
            p.setStyle(Paint.Style.FILL);
        }
    }

    /** Dark wood panelling behind the shelves. */
    static final class WoodWall extends Drawables.Base {
        private final float d;

        WoodWall(float d) { this.d = d; }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            c.drawColor(0xFF22150C);
            Random r = new Random(5);
            p.setStyle(Paint.Style.FILL);
            for (float x = b.left; x < b.right; x += 3 * d) {
                p.setColor(r.nextBoolean() ? Skin.alpha(0xFF000000, 0x10 + r.nextInt(0x14)) : Skin.alpha(0xFF5A3A22, 0x10 + r.nextInt(0x18)));
                c.drawRect(x, b.top, x + (1 + r.nextInt(3)) * 0.6f * d, b.bottom, p);
            }
        }
    }

    /** Wooden shelf plank with a lit front edge and a shadow beneath. */
    static final class Plank extends Drawables.Base {
        private final float d;

        Plank(float d) { this.d = d; }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float split = b.top + b.height() * 0.7f;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, b.top, 0, split, new int[]{0xFFB07A48, 0xFF8A5A33, 0xFF5A3518}, null, Shader.TileMode.CLAMP));
            c.drawRect(b.left, b.top, b.right, split, p);
            p.setShader(new LinearGradient(0, split, 0, b.bottom, 0x90000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(b.left, split, b.right, b.bottom, p);
            p.setShader(null);
            p.setColor(0xFFD8A46A);
            c.drawRect(b.left, b.top, b.right, b.top + d, p);
        }
    }
}
