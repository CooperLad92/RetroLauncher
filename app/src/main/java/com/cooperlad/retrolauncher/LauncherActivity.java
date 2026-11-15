package com.cooperlad.retrolauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RETRO-OS: an 80s home-computer / CRT terminal style Android home screen.
 * Apps are shown as a disk directory listing, you can type to filter or run
 * commands, F1-F4 are assignable quick-launch keys, and a CRT overlay adds
 * scanlines and a vignette.
 */
public class LauncherActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String[] F_DEFAULT = {"PHONE", "MSG", "WEB", "CAM"};

    private static final String HELP =
            "COMMANDS:\n" +
            " <NAME>      RUN BEST MATCH\n" +
            " RUN <NAME>  LOAD A PROGRAM\n" +
            " THEME [X]   " + RetroTheme.names() + "\n" +
            " CRT         SCANLINES ON/OFF\n" +
            " ICONS       PIXEL ICONS ON/OFF\n" +
            " WEB <Q>     SEARCH THE WEB\n" +
            " CALL <NUM>  DIAL A NUMBER\n" +
            " SETTINGS    SYSTEM SETTINGS\n" +
            " HOME        CHANGE LAUNCHER\n" +
            " REBOOT      RUN BOOT SEQUENCE\n" +
            "LONG-PRESS A PROGRAM FOR OPTIONS.";

    // state
    private SharedPreferences prefs;
    private RetroTheme theme;
    private Typeface font;
    private float fontScale = 1f;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService iconExec = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private final Set<String> iconPending = new HashSet<>();
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private boolean appsDirty = true;
    private int battery = -1;
    private boolean charging;

    // views
    private FrameLayout root, screen, menuOverlay;
    private LinearLayout content;
    private TextView titleBar, statusLine, clockView, dateView, responseView, dirHeader, dirFooter, promptView, bootView;
    private EditText input;
    private ListView list;
    private CrtOverlayView crt;
    private final TextView[] fKeys = new TextView[5];
    private final List<TextView> themed = new ArrayList<>();
    private final List<TextView> reversed = new ArrayList<>();
    private AppAdapter adapter;

    static final class AppEntry {
        String label;
        ComponentName cn;
        ResolveInfo ri;
        int blocks;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("retro", MODE_PRIVATE);
        theme = RetroTheme.byName(prefs.getString("theme", "GREEN"));
        if (theme == null) theme = RetroTheme.ALL[0];
        font = loadFont();
        buildUi();
        applyTheme();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        listen(packageReceiver, pf);

        loadApps();
        if (saved == null) boot();
    }

    @Override protected void onResume() {
        super.onResume();
        listen(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (appsDirty) loadApps();
        refreshFooter();
        h.removeCallbacks(tick);
        h.post(tick);
        crt.setRunning(true);
        if (bootView == null) say("READY.");
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
        h.removeCallbacks(tick);
        crt.setRunning(false);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
        iconExec.shutdownNow();
    }

    /** Home pressed while already home: reset to a clean prompt. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dismissMenu();
        input.setText("");
        hideKeyboard();
        list.setSelection(0);
    }

    @Override public void onBackPressed() {
        // A launcher should never close on back.
        if (menuOverlay != null) { dismissMenu(); return; }
        if (input.length() > 0) { input.setText(""); return; }
        hideKeyboard();
    }

    /** System broadcasts only, but Android 13+ wants the export flag stated explicitly. */
    private void listen(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(r, f);
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            appsDirty = true;
            loadApps();
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            battery = lvl < 0 ? -1 : Math.round(lvl * 100f / scale);
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            updateStatus();
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            String hm = new SimpleDateFormat("HH:mm", Locale.US).format(now);
            if ((System.currentTimeMillis() / 1000) % 2 == 1) hm = hm.replace(':', ' ');
            clockView.setText(hm);
            dateView.setText(new SimpleDateFormat("EEE dd MMM yyyy", Locale.US).format(now).toUpperCase(Locale.US));
            h.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000));
        }
    };

    // ------------------------------------------------------------------ UI

    private Typeface loadFont() {
        try {
            Typeface t = Typeface.createFromAsset(getAssets(), "fonts/retro.ttf");
            fontScale = 1.15f;
            return t;
        } catch (Exception e) {
            fontScale = 1f;
            return Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(float sp) {
        TextView t = new TextView(this);
        t.setTypeface(font);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * fontScale);
        t.setLetterSpacing(0f);
        style(t);
        return t;
    }

    private void style(TextView t) {
        t.setTextColor(theme.fg);
        t.setShadowLayer(dp(5), 0, 0, theme.withAlpha(0xB0)); // phosphor glow
    }

    private TextView track(TextView t) { themed.add(t); return t; }

    private TextView trackReversed(TextView t) { reversed.add(t); return t; }

    private void buildUi() {
        root = new FrameLayout(this);
        screen = new FrameLayout(this);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(MATCH, MATCH);
        slp.setMargins(dp(6), dp(6), dp(6), dp(6));
        root.addView(screen, slp);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(10));
        screen.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        titleBar = trackReversed(text(13));
        titleBar.setGravity(Gravity.CENTER);
        titleBar.setPadding(dp(4), dp(5), dp(4), dp(5));
        titleBar.setText("**** RETRO-OS 64 BASIC V2 ****");
        content.addView(titleBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        statusLine = track(text(12));
        statusLine.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(6);
        content.addView(statusLine, lp);

        clockView = track(text(64));
        clockView.setGravity(Gravity.CENTER);
        clockView.setOnClickListener(v -> openClock());
        lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(14);
        content.addView(clockView, lp);

        dateView = track(text(14));
        dateView.setGravity(Gravity.CENTER);
        content.addView(dateView, new LinearLayout.LayoutParams(MATCH, WRAP));

        responseView = track(text(13));
        responseView.setMaxLines(14);
        lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(14);
        content.addView(responseView, lp);

        list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setCacheColorHint(0);
        list.setBackgroundColor(0);
        dirHeader = trackReversed(text(14));
        dirHeader.setPadding(dp(4), dp(3), dp(4), dp(3));
        dirHeader.setText("0 \"RETRO-OS DISK  \" 64 2A");
        LinearLayout headerWrap = new LinearLayout(this);
        headerWrap.setPadding(0, dp(8), 0, dp(4));
        headerWrap.addView(dirHeader, new LinearLayout.LayoutParams(WRAP, WRAP));
        list.addHeaderView(headerWrap, null, false);
        dirFooter = track(text(14));
        dirFooter.setPadding(0, dp(8), 0, dp(12));
        list.addFooterView(dirFooter, null, false);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Object o = p.getItemAtPosition(pos);
            if (o instanceof AppEntry) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); launch((AppEntry) o); }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Object o = p.getItemAtPosition(pos);
            if (!(o instanceof AppEntry)) return false;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            appMenu((AppEntry) o);
            return true;
        });
        content.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        // command line
        LinearLayout inRow = new LinearLayout(this);
        inRow.setOrientation(LinearLayout.HORIZONTAL);
        inRow.setGravity(Gravity.CENTER_VERTICAL);
        inRow.setPadding(0, dp(6), 0, dp(6));
        promptView = track(text(17));
        promptView.setText("> ");
        inRow.addView(promptView);
        input = new EditText(this);
        input.setTypeface(font);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17 * fontScale);
        input.setBackground(null);
        input.setPadding(0, dp(4), 0, dp(4));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setHint("TYPE A PROGRAM OR HELP");
        input.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                runCommand(input.getText().toString());
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
        });
        inRow.addView(input, new LinearLayout.LayoutParams(0, WRAP, 1f));
        content.addView(inRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        // function keys
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            TextView k = track(text(11));
            k.setGravity(Gravity.CENTER);
            k.setLines(2);
            k.setPadding(dp(2), dp(8), dp(2), dp(8));
            k.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); fKey(idx); });
            k.setOnLongClickListener(v -> { fKeyLong(idx); return true; });
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, WRAP, 1f);
            kl.setMargins(dp(3), dp(4), dp(3), 0);
            dock.addView(k, kl);
            fKeys[i] = k;
        }
        content.addView(dock, new LinearLayout.LayoutParams(MATCH, WRAP));

        crt = new CrtOverlayView(this);
        crt.setEffectOn(prefs.getBoolean("crt", true));
        screen.addView(crt, new FrameLayout.LayoutParams(MATCH, MATCH));

        // keep the keyboard closed on start
        content.setFocusableInTouchMode(true);
        content.requestFocus();

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, ins) -> {
                Insets sb = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = ins.getInsets(WindowInsets.Type.ime());
                v.setPadding(sb.left, sb.top, sb.right, Math.max(sb.bottom, ime.bottom));
                return WindowInsets.CONSUMED;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
    }

    private void applyTheme() {
        root.setBackgroundColor(theme.border);
        screen.setBackground(theme.synthGrid
                ? new SynthGridDrawable(theme.screen, theme.fg)
                : new ColorDrawable(theme.screen));
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        for (TextView t : themed) style(t);
        for (TextView t : reversed) {
            t.setTextColor(theme.screen);
            t.setBackgroundColor(theme.fg);
            t.setShadowLayer(0, 0, 0, 0);
        }
        for (TextView k : fKeys) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(theme.withAlpha(0x14));
            g.setStroke(dp(2), theme.fg);
            k.setBackground(g);
        }
        input.setTextColor(theme.fg);
        input.setHintTextColor(theme.withAlpha(0x70));
        input.setShadowLayer(dp(5), 0, 0, theme.withAlpha(0xB0));
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cur = new GradientDrawable();
            cur.setColor(theme.fg);
            cur.setSize(dp(10), dp(20));
            input.setTextCursorDrawable(cur);
        }
        list.setSelector(new ColorDrawable(theme.withAlpha(0x3A)));
        crt.setBezelColor(theme.border);
        iconCache.clear();
        iconPending.clear();
        adapter.notifyDataSetChanged();
        updateStatus();
        refreshDock();
    }

    private void updateStatus() {
        String b = battery < 0 ? "--" : battery + "%";
        statusLine.setText("BATT " + b + (charging ? " ⚡" : "") + "  ·  " + allApps.size() + " PROGRAMS  ·  "
                + theme.name);
    }

    private void refreshFooter() {
        long freeMb = 0;
        try {
            StatFs s = new StatFs(Environment.getDataDirectory().getPath());
            freeMb = s.getAvailableBytes() / (1024 * 1024);
        } catch (Exception ignored) { }
        dirFooter.setText(freeMb + " BLOCKS FREE.");
    }

    private void say(String msg) {
        responseView.setText(msg);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        content.requestFocus();
    }

    // ------------------------------------------------------------------ boot

    private void boot() {
        if (bootView != null) return;
        dismissMenu();
        bootView = text(14);
        bootView.setPadding(dp(20), dp(24), dp(20), dp(20));
        bootView.setGravity(Gravity.TOP | Gravity.START);
        bootView.setBackgroundColor(theme.screen);
        bootView.setOnClickListener(v -> endBoot());
        screen.addView(bootView, screen.indexOfChild(crt), new FrameLayout.LayoutParams(MATCH, MATCH));

        final String full =
                "RETRO-OS BIOS V1.986\n" +
                "(C) 1986 RETRO SYSTEMS INC.\n\n" +
                "CPU 6502 @ 1.02 MHZ ...... OK\n" +
                "MEMORY TEST 65536 BYTES .. OK\n" +
                "DEVICE: " + Build.MODEL.toUpperCase(Locale.US) + "\n" +
                "KERNAL ANDROID " + Build.VERSION.RELEASE + " ..... OK\n" +
                "SCANNING DISK ......... " + allApps.size() + " PRG\n\n" +
                "LOADING SHELL...\n\n" +
                "READY.\n";
        final int[] i = {0};
        Runnable step = new Runnable() {
            @Override public void run() {
                if (bootView == null) return;
                char c = full.charAt(i[0]);
                i[0] = Math.min(full.length(), i[0] + (c == '.' || c == '\n' ? 1 : 2));
                bootView.setText(full.substring(0, i[0]) + "█");
                if (i[0] < full.length()) {
                    h.postDelayed(this, c == '.' ? 45 : c == '\n' ? 140 : 14);
                } else {
                    h.postDelayed(LauncherActivity.this::endBoot, 700);
                }
            }
        };
        // CRT power-on: the picture snaps open from a horizontal line
        screen.setScaleY(0.01f);
        screen.setAlpha(0.4f);
        screen.animate().scaleY(1f).alpha(1f).setDuration(420)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        h.postDelayed(step, 450);
    }

    private void endBoot() {
        if (bootView == null) return;
        final View b = bootView;
        bootView = null;
        say("READY.");
        b.animate().alpha(0f).setDuration(220).withEndAction(() -> screen.removeView(b)).start();
    }

    // ------------------------------------------------------------------ apps

    private void loadApps() {
        appsDirty = false;
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(i, 0);
        allApps.clear();
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            AppEntry e = new AppEntry();
            CharSequence l = ri.loadLabel(pm);
            e.label = (l == null ? ri.activityInfo.packageName : l.toString()).trim().toUpperCase(Locale.US);
            e.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            e.ri = ri;
            try {
                long len = new File(ri.activityInfo.applicationInfo.sourceDir).length();
                e.blocks = (int) Math.max(1, Math.min(999, len / (1024 * 1024)));
            } catch (Exception ex) {
                e.blocks = 1;
            }
            allApps.add(e);
        }
        Collections.sort(allApps, (a, b) -> a.label.compareTo(b.label));
        filter(input.getText().toString());
        updateStatus();
        refreshDock();
    }

    private String searchTerm(String raw) {
        String q = raw.trim().toUpperCase(Locale.US);
        for (String p : new String[]{"RUN ", "LOAD "}) if (q.startsWith(p)) return q.substring(p.length()).trim();
        return q;
    }

    private void filter(String raw) {
        String term = searchTerm(raw);
        shown.clear();
        List<AppEntry> rest = new ArrayList<>();
        for (AppEntry e : allApps) {
            if (term.isEmpty()) shown.add(e);
            else if (e.label.startsWith(term)) shown.add(e);
            else if (e.label.contains(term)) rest.add(e);
        }
        shown.addAll(rest);
        adapter.notifyDataSetChanged();
    }

    private AppEntry best(String term) {
        if (term.isEmpty()) return null;
        AppEntry contains = null;
        for (AppEntry e : allApps) {
            if (e.label.equals(term)) return e;
            if (e.label.startsWith(term)) return e;
            if (contains == null && e.label.contains(term)) contains = e;
        }
        return contains;
    }

    private AppEntry byComponent(String flat) {
        if (flat == null) return null;
        for (AppEntry e : allApps) if (e.cn.flattenToString().equals(flat)) return e;
        return null;
    }

    private void launch(AppEntry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(e.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            say("LOAD \"" + e.label + "\",8,1\n\nSEARCHING FOR " + e.label + "\nLOADING\nREADY.");
            startActivity(i);
            input.setText("");
            hideKeyboard();
        } catch (Exception ex) {
            say("?FILE NOT FOUND  ERROR\nREADY.");
        }
    }

    private boolean start(Intent i, String err) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception ex) {
            say(err + "\nREADY.");
            return false;
        }
    }

    private void openClock() {
        start(new Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS), "?DEVICE NOT PRESENT  ERROR");
    }

    // ------------------------------------------------------------------ commands

    private void runCommand(String raw) {
        String c = raw.trim().toUpperCase(Locale.US);
        if (c.isEmpty()) return;
        String[] parts = c.split("\\s+", 2);
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1].trim() : "";
        input.setText("");
        switch (cmd) {
            case "HELP": case "?":
                say(HELP);
                break;
            case "LIST": case "DIR": case "CLS": case "CLEAR":
                say("READY.");
                list.setSelection(0);
                break;
            case "THEME": case "COLOR": case "COLOUR": {
                RetroTheme t = arg.isEmpty() ? theme.next() : RetroTheme.byName(arg);
                if (t == null) { say("?ILLEGAL QUANTITY  ERROR\nTHEMES: " + RetroTheme.names() + "\nREADY."); break; }
                applyRetroTheme(t);
                say("THEME " + t.name + "\nREADY.");
                break;
            }
            case "CRT": {
                boolean on = !prefs.getBoolean("crt", true);
                prefs.edit().putBoolean("crt", on).apply();
                crt.setEffectOn(on);
                say("CRT " + (on ? "ON" : "OFF") + "\nREADY.");
                break;
            }
            case "ICONS": {
                boolean on = !prefs.getBoolean("icons", true);
                prefs.edit().putBoolean("icons", on).apply();
                adapter.notifyDataSetChanged();
                say("ICONS " + (on ? "ON" : "OFF") + "\nREADY.");
                break;
            }
            case "SETTINGS": case "SYS":
                start(new Intent(Settings.ACTION_SETTINGS), "?DEVICE NOT PRESENT  ERROR");
                hideKeyboard();
                break;
            case "HOME": case "LAUNCHER":
                start(new Intent(Settings.ACTION_HOME_SETTINGS), "?DEVICE NOT PRESENT  ERROR");
                hideKeyboard();
                break;
            case "WEB": case "SEARCH": {
                if (arg.isEmpty()) { fKey(2); break; }
                Intent w = new Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", arg.toLowerCase(Locale.US));
                if (!startQuiet(w)) start(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/search?q=" + Uri.encode(arg))), "?NO BROWSER  ERROR");
                hideKeyboard();
                break;
            }
            case "CALL": case "DIAL":
                start(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(arg))), "?DEVICE NOT PRESENT  ERROR");
                hideKeyboard();
                break;
            case "REBOOT": case "RESET":
                hideKeyboard();
                boot();
                break;
            case "TIME":
                say(new SimpleDateFormat("HH:mm:ss  EEE dd MMM yyyy", Locale.US).format(new Date()).toUpperCase(Locale.US) + "\nREADY.");
                break;
            case "ABOUT": case "VER":
                say("RETRO-OS LAUNCHER V1.0\n" + Build.MANUFACTURER.toUpperCase(Locale.US) + " "
                        + Build.MODEL.toUpperCase(Locale.US) + "\nANDROID " + Build.VERSION.RELEASE
                        + " (API " + Build.VERSION.SDK_INT + ")\nREADY.");
                break;
            case "RUN": case "LOAD": {
                AppEntry e = best(arg);
                if (e != null) launch(e); else say("?FILE NOT FOUND  ERROR\nREADY.");
                break;
            }
            default: {
                AppEntry e = best(c);
                if (e != null) launch(e); else say("?SYNTAX  ERROR\nTYPE HELP FOR COMMANDS\nREADY.");
            }
        }
    }

    private boolean startQuiet(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return true; }
        catch (Exception e) { return false; }
    }

    private void applyRetroTheme(RetroTheme t) {
        theme = t;
        prefs.edit().putString("theme", t.name).apply();
        applyTheme();
    }

    // ------------------------------------------------------------------ function keys

    private void refreshDock() {
        if (fKeys[0] == null) return;
        for (int i = 0; i < 4; i++) {
            AppEntry e = byComponent(prefs.getString("f" + i, null));
            String label = e != null ? shorten(e.label, 6) : F_DEFAULT[i];
            fKeys[i].setText("F" + (i + 1) + "\n" + label);
        }
        fKeys[4].setText("F5\nMENU");
    }

    private String shorten(String s, int n) {
        String first = s.split(" ")[0];
        return first.length() <= n ? first : first.substring(0, n);
    }

    private void fKey(int i) {
        if (i == 4) { systemMenu(); return; }
        AppEntry e = byComponent(prefs.getString("f" + i, null));
        if (e != null) { launch(e); return; }
        Intent d;
        switch (i) {
            case 0: d = new Intent(Intent.ACTION_DIAL); break;
            case 1: d = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING); break;
            case 2: d = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER); break;
            default: d = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA); break;
        }
        if (start(d, "?DEVICE NOT PRESENT  ERROR")) say("READY.");
    }

    private void fKeyLong(int i) {
        if (i == 4) { systemMenu(); return; }
        String cur = prefs.getString("f" + i, null);
        if (cur == null) {
            say("F" + (i + 1) + " = " + F_DEFAULT[i] + " (DEFAULT)\nLONG-PRESS A PROGRAM TO ASSIGN IT.\nREADY.");
            return;
        }
        showMenu("F" + (i + 1) + " KEY", new String[]{"RESET TO " + F_DEFAULT[i], "CANCEL"}, pick -> {
            if (pick == 0) {
                prefs.edit().remove("f" + i).apply();
                refreshDock();
            }
        });
    }

    // ------------------------------------------------------------------ menus

    interface MenuPick { void pick(int index); }

    private void appMenu(AppEntry e) {
        String[] opts = {"RUN", "ASSIGN TO F1", "ASSIGN TO F2", "ASSIGN TO F3", "ASSIGN TO F4", "APP INFO", "UNINSTALL"};
        showMenu("\"" + e.label + "\"", opts, pick -> {
            switch (pick) {
                case 0: launch(e); break;
                case 1: case 2: case 3: case 4:
                    prefs.edit().putString("f" + (pick - 1), e.cn.flattenToString()).apply();
                    refreshDock();
                    say("F" + pick + " = " + e.label + "\nREADY.");
                    break;
                case 5:
                    start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + e.cn.getPackageName())), "?DEVICE NOT PRESENT  ERROR");
                    break;
                case 6:
                    start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.cn.getPackageName())),
                            "?CAN'T DELETE  ERROR");
                    break;
            }
        });
    }

    private void systemMenu() {
        String[] opts = {
                "THEME: " + theme.name + " → " + theme.next().name,
                "CRT EFFECT: " + (prefs.getBoolean("crt", true) ? "ON" : "OFF"),
                "PIXEL ICONS: " + (prefs.getBoolean("icons", true) ? "ON" : "OFF"),
                "SYSTEM SETTINGS",
                "CHOOSE DEFAULT LAUNCHER",
                "REBOOT",
                "HELP"};
        showMenu("SYSTEM MENU", opts, pick -> {
            switch (pick) {
                case 0: runCommand("THEME"); break;
                case 1: runCommand("CRT"); break;
                case 2: runCommand("ICONS"); break;
                case 3: runCommand("SETTINGS"); break;
                case 4: runCommand("HOME"); break;
                case 5: runCommand("REBOOT"); break;
                case 6: runCommand("HELP"); break;
            }
        });
    }

    private void showMenu(String title, String[] opts, MenuPick cb) {
        dismissMenu();
        hideKeyboard();
        FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(0xA0000000);
        ov.setClickable(true);
        ov.setOnClickListener(v -> dismissMenu());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(theme.screen);
        bg.setStroke(dp(2), theme.fg);
        box.setBackground(bg);
        box.setPadding(dp(16), dp(14), dp(16), dp(10));

        TextView t = text(14);
        t.setText(" " + title + " ");
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextColor(theme.screen);
        t.setBackgroundColor(theme.fg);
        t.setShadowLayer(0, 0, 0, 0);
        t.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));

        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            TextView o = text(15);
            o.setText((i + 1) + ". " + opts[i]);
            o.setPadding(dp(4), dp(11), dp(4), dp(11));
            GradientDrawable press = new GradientDrawable();
            press.setColor(theme.withAlpha(0x3A));
            android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_pressed}, press);
            o.setBackground(sl);
            o.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                dismissMenu();
                cb.pick(idx);
            });
            box.addView(o, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER);
        bl.setMargins(dp(24), dp(24), dp(24), dp(24));
        ov.addView(box, bl);
        screen.addView(ov, screen.indexOfChild(crt), new FrameLayout.LayoutParams(MATCH, MATCH));
        menuOverlay = ov;
    }

    private void dismissMenu() {
        if (menuOverlay != null) {
            screen.removeView(menuOverlay);
            menuOverlay = null;
        }
    }

    // ------------------------------------------------------------------ list + pixel icons

    private static final class Row {
        ImageView icon;
        TextView name, type;
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int pos, View cv, ViewGroup parent) {
            Row r;
            if (cv == null) {
                LinearLayout row = new LinearLayout(LauncherActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(7), 0, dp(7));
                r = new Row();
                r.icon = new ImageView(LauncherActivity.this);
                r.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(24), dp(24));
                il.rightMargin = dp(10);
                row.addView(r.icon, il);
                r.name = text(15);
                r.name.setSingleLine(true);
                r.name.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(r.name, new LinearLayout.LayoutParams(0, WRAP, 1f));
                r.type = text(15);
                row.addView(r.type);
                row.setTag(r);
                cv = row;
            } else {
                r = (Row) cv.getTag();
            }
            AppEntry e = shown.get(pos);
            style(r.name);
            style(r.type);
            r.name.setText(String.format(Locale.US, "%-4d\"%s\"", e.blocks, e.label));
            r.type.setText(" PRG");
            bindIcon(r.icon, e);
            return cv;
        }
    }

    private void bindIcon(ImageView iv, AppEntry e) {
        if (!prefs.getBoolean("icons", true)) {
            iv.setVisibility(View.GONE);
            return;
        }
        iv.setVisibility(View.VISIBLE);
        final String key = e.cn.flattenToString();
        iv.setTag(key);
        Bitmap b = iconCache.get(key);
        if (b != null) {
            iv.setImageDrawable(pixelDrawable(b));
            return;
        }
        iv.setImageDrawable(null);
        if (!iconPending.add(key)) return;
        final int fg = theme.fg;
        final ResolveInfo ri = e.ri;
        final PackageManager pm = getPackageManager();
        iconExec.execute(() -> {
            Bitmap bm;
            try { bm = pixelate(ri.loadIcon(pm), fg); } catch (Exception ex) { bm = null; }
            final Bitmap res = bm;
            h.post(() -> {
                iconPending.remove(key);
                if (res == null || fg != theme.fg) return; // theme changed meanwhile
                iconCache.put(key, res);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private BitmapDrawable pixelDrawable(Bitmap b) {
        BitmapDrawable d = new BitmapDrawable(getResources(), b);
        d.setFilterBitmap(false); // keep hard pixel edges when scaled up
        d.setAntiAlias(false);
        return d;
    }

    /** Renders the app icon at 16x16 and remaps it to 4 shades of the phosphor colour. */
    private static Bitmap pixelate(Drawable d, int fg) {
        final int n = 16;
        Bitmap small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        int[] px = new int[n * n];
        small.getPixels(px, 0, n, 0, 0, n, n);
        int fr = (fg >> 16) & 0xFF, fgG = (fg >> 8) & 0xFF, fb = fg & 0xFF;
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int a = (p >>> 24);
            if (a < 110) { px[i] = 0; continue; }
            float lum = (0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF)) / 255f;
            float lv = lum > 0.75f ? 1f : lum > 0.5f ? 0.72f : lum > 0.25f ? 0.45f : 0.22f;
            px[i] = 0xFF000000 | ((int) (fr * lv) << 16) | ((int) (fgG * lv) << 8) | (int) (fb * lv);
        }
        small.setPixels(px, 0, n, 0, 0, n, n);
        return small;
    }
}
