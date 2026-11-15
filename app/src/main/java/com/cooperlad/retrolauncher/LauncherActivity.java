package com.cooperlad.retrolauncher;

import android.Manifest;
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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.AlarmClock;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
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
 * RETRO-OS: a skinnable retro Android home screen. Apps are listed in a
 * scrolling list, you can type to filter or run commands, F1-F4 are
 * assignable quick-launch keys, and each skin restyles the whole screen.
 */
public class LauncherActivity extends Activity {

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final String[] F_DEFAULT = {"PHONE", "MSG", "WEB", "CAM"};
    private static final int REQ_LOCATION = 7;
    private static final long SAVER_AFTER_MS = 60_000;

    static final class AppEntry {
        String label; // upper case
        String name;  // as the app names itself
        ComponentName cn;
        ResolveInfo ri;
        int blocks;
        int idx;
    }

    // state
    private SharedPreferences prefs;
    private Skin skin;
    private Typeface font;
    private float fontScale = 1f;
    private float density;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ExecutorService iconExec = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private final Set<String> iconPending = new HashSet<>();
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shown = new ArrayList<>();
    private final Skin.Info info = new Skin.Info();
    private boolean appsDirty = true, launching = false;
    private int selected = -1;
    private long lastTouch;
    private int drainStartLevel = -1;
    private long drainStartTime;
    private SensorManager sensors;
    private final float[] rotM = new float[9], orient = new float[3];
    private long lastBearingUi;

    // views
    private FrameLayout root, screen, menuOverlay;
    private LinearLayout content;
    private TextView titleV, statusV, clockV, dateV, responseV, headerV, footerV, promptV, bootView;
    private View saver;
    private EditText input;
    private ListView list;
    private CrtOverlayView overlay;
    private final TextView[] keys = new TextView[5];
    private AppAdapter adapter;

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("retro", MODE_PRIVATE);
        skin = Skins.create(prefs.getString("skin", prefs.getString("theme", "GREEN")));
        loadFont();
        buildUi();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        listen(packageReceiver, pf);

        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        loadApps();
        lastTouch = SystemClock.uptimeMillis();
        if (saved == null || prefs.getBoolean("bootNext", false)) {
            prefs.edit().remove("bootNext").apply();
            boot();
        }
        if (skin.wantsLocation) requestLocationIfNeeded();
    }

    @Override protected void onResume() {
        super.onResume();
        listen(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (appsDirty) loadApps();
        refreshFooter();
        h.removeCallbacks(tick);
        h.post(tick);
        overlay.setRunning(true);
        if (skin.wantsLocation) {
            startCompass();
            updateLocation();
        }
        launching = false;
        lastTouch = SystemClock.uptimeMillis();
        if (bootView == null) say(skin.ready());
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) { }
        h.removeCallbacks(tick);
        overlay.setRunning(false);
        if (sensors != null) sensors.unregisterListener(compass);
        hideSaver();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(packageReceiver); } catch (Exception ignored) { }
        iconExec.shutdownNow();
        h.removeCallbacksAndMessages(null);
    }

    /** Home pressed while already home: reset to a clean prompt. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideSaver();
        dismissMenu();
        input.setText("");
        hideKeyboard();
        list.setSelection(0);
    }

    @Override public void onBackPressed() { goBack(); }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        lastTouch = SystemClock.uptimeMillis();
        if (saver != null) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) hideSaver();
            return true;
        }
        return super.dispatchTouchEvent(ev);
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
            info.battery = lvl < 0 ? -1 : Math.round(lvl * 100f / scale);
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            info.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            estimateDrain();
            updateStatus();
        }
    };

    /** Rough hours-remaining from the drain rate seen since the last unplug. */
    private void estimateDrain() {
        long now = SystemClock.elapsedRealtime();
        if (info.charging || info.battery < 0) {
            drainStartLevel = -1;
            info.estHours = -1;
        } else if (drainStartLevel < 0 || info.battery > drainStartLevel) {
            drainStartLevel = info.battery;
            drainStartTime = now;
        } else {
            float hrs = (now - drainStartTime) / 3_600_000f;
            int drop = drainStartLevel - info.battery;
            if (drop >= 2 && hrs > 0.05f) info.estHours = info.battery / (drop / hrs);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            boolean blink = (System.currentTimeMillis() / 1000) % 2 == 1;
            setIf(titleV, skin.title(info, now));
            setIf(clockV, skin.clock(now, blink));
            setIf(dateV, skin.date(now));
            if (skin.wantsWifi) updateRssi();
            updateStatus();
            if (skin.dynamicKeys) refreshKeys();
            checkSaver();
            h.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000));
        }
    };

    private static void setIf(TextView t, String s) {
        if (t != null && !TextUtils.equals(t.getText(), s)) t.setText(s);
    }

    // ------------------------------------------------------------------ sensors

    private final SensorEventListener compass = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            SensorManager.getRotationMatrixFromVector(rotM, e.values);
            SensorManager.getOrientation(rotM, orient);
            info.bearing = (int) ((Math.toDegrees(orient[0]) + 360) % 360);
            long now = SystemClock.uptimeMillis();
            if (now - lastBearingUi > 250) {
                lastBearingUi = now;
                updateStatus();
            }
        }

        @Override public void onAccuracyChanged(Sensor s, int a) { }
    };

    private void startCompass() {
        if (sensors == null) return;
        Sensor rv = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv != null) sensors.registerListener(compass, rv, SensorManager.SENSOR_DELAY_UI);
    }

    private void requestLocationIfNeeded() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) return;
        if (prefs.getBoolean("askedLocation", false)) return;
        prefs.edit().putBoolean("askedLocation", true).apply();
        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_LOCATION) updateLocation();
    }

    private void updateLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return;
        try {
            Location best = null;
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) setGrid(best);
            if (Build.VERSION.SDK_INT >= 30) {
                String p = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                        : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
                if (p != null) lm.getCurrentLocation(p, null, getMainExecutor(), l -> { if (l != null) setGrid(l); });
            }
        } catch (SecurityException | IllegalArgumentException ignored) { }
    }

    private void setGrid(Location l) {
        info.grid = Mgrs.fromLatLon(l.getLatitude(), l.getLongitude());
        updateStatus();
    }

    @SuppressWarnings("deprecation")
    private void updateRssi() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            info.rssi = wi == null ? -127 : wi.getRssi();
        } catch (Exception e) {
            info.rssi = -127;
        }
    }

    // ------------------------------------------------------------------ helpers used by skins

    Typeface font() { return font; }

    float density() { return density; }

    int dp(float v) { return Math.round(v * density); }

    void addFx(View v) {
        screen.addView(v, screen.indexOfChild(overlay), new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    void removeFx(View v, long delayMs) {
        h.postDelayed(() -> screen.removeView(v), delayMs);
    }

    void moveSelection(int d) {
        if (shown.isEmpty()) return;
        int s = selected < 0 ? 0 : selected + d;
        s = Math.max(0, Math.min(shown.size() - 1, s));
        selected = s;
        adapter.notifyDataSetChanged();
        list.smoothScrollToPosition(s + list.getHeaderViewsCount());
        AppEntry e = shown.get(s);
        say(skin.selectText(e));
        skin.onSelect(e);
    }

    void launchSelected() {
        if (selected >= 0 && selected < shown.size()) launch(shown.get(selected));
        else moveSelection(0);
    }

    void goBack() {
        if (menuOverlay != null) { dismissMenu(); return; }
        if (input.length() > 0) { input.setText(""); return; }
        hideKeyboard();
        list.setSelection(0);
    }

    void cycleSkin() { switchSkin(Skins.next(skin.name)); }

    void openSystemMenu() { systemMenu(); }

    // ------------------------------------------------------------------ UI

    private void loadFont() {
        switch (skin.font) {
            case Skin.FONT_MONO:
                font = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
                fontScale = 1f;
                break;
            case Skin.FONT_SANS:
                font = Typeface.create("sans-serif", Typeface.NORMAL);
                fontScale = 0.95f;
                break;
            case Skin.FONT_CARTOON:
                font = Typeface.create("casual", Typeface.BOLD);
                fontScale = 1f;
                break;
            default:
                try {
                    font = Typeface.createFromAsset(getAssets(), "fonts/retro.ttf");
                    fontScale = 1.15f;
                } catch (Exception e) {
                    font = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
                    fontScale = 1f;
                }
        }
    }

    private TextView text(Skin.Part p) {
        TextView t = new TextView(this);
        t.setTypeface(font);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, skin.size(p) * fontScale);
        t.setGravity(skin.gravity(p));
        if (skin.upper && skin.font != Skin.FONT_PIXEL) t.setAllCaps(true);
        paint(t, p);
        return t;
    }

    private void paint(TextView t, Skin.Part p) {
        t.setTextColor(skin.color(p));
        Drawable b = skin.bg(p, density);
        if (b != null) {
            t.setBackground(b);
            int[] pd = skin.pad(p);
            t.setPadding(dp(pd[0]), dp(pd[1]), dp(pd[2]), dp(pd[3]));
        }
        skin.shadow(t, p, density);
    }

    private void add(LinearLayout parent, View v, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(topMarginDp);
        parent.addView(v, lp);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(skin.bezel);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        root.addView(outer, new FrameLayout.LayoutParams(MATCH, MATCH));

        screen = new FrameLayout(this);
        screen.setBackground(skin.screenBg(density));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        int m = dp(skin.screenMarginDp);
        slp.setMargins(m, m, m, skin.controlsOutside ? dp(6) : m);
        outer.addView(screen, slp);

        View bgv = skin.background(this);
        if (bgv != null) screen.addView(bgv, new FrameLayout.LayoutParams(MATCH, MATCH));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int cp = dp(skin.contentPadDp);
        content.setPadding(cp, skin.contentPadDp > 16 ? cp : dp(14), cp, skin.contentPadDp > 16 ? cp : dp(10));
        screen.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        Drawable pb = skin.panelBg(density);
        if (pb != null) {
            panel.setBackground(pb);
            panel.setPadding(dp(4), dp(4), dp(4), dp(6));
        }
        content.addView(panel, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        titleV = text(Skin.Part.TITLE);
        titleV.setSingleLine(true);
        titleV.setEllipsize(TextUtils.TruncateAt.END);
        if (skin.bg(Skin.Part.TITLE, density) == null) titleV.setPadding(dp(4), dp(5), dp(4), dp(5));
        add(panel, titleV, 0);

        statusV = text(Skin.Part.STATUS);
        statusV.setMaxLines(2);
        add(panel, statusV, 6);

        clockV = text(Skin.Part.CLOCK);
        clockV.setSingleLine(true);
        clockV.setOnClickListener(v -> openClock());
        add(panel, clockV, 10);

        dateV = text(Skin.Part.DATE);
        add(panel, dateV, 0);

        responseV = text(Skin.Part.RESPONSE);
        responseV.setMaxLines(14);
        add(panel, responseV, 10);

        list = new ListView(this);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setCacheColorHint(0);
        list.setBackgroundColor(0);
        Drawable lb = skin.bg(Skin.Part.LIST, density);
        if (lb != null) {
            list.setBackground(lb);
            list.setPadding(dp(4), dp(4), dp(4), dp(4));
        }
        list.setSelector(skin.listSelector());
        headerV = text(Skin.Part.HEADER);
        LinearLayout headerWrap = new LinearLayout(this);
        headerWrap.setPadding(0, dp(6), 0, dp(4));
        headerWrap.addView(headerV, new LinearLayout.LayoutParams(WRAP, WRAP));
        String hd = skin.header();
        if (hd == null) headerV.setVisibility(View.GONE);
        else headerV.setText(hd);
        list.addHeaderView(headerWrap, null, false);
        footerV = text(Skin.Part.FOOTER);
        footerV.setPadding(dp(4), dp(8), dp(4), dp(12));
        list.addFooterView(footerV, null, false);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, pos, id) -> {
            Object o = parent.getItemAtPosition(pos);
            if (o instanceof AppEntry) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                launch((AppEntry) o);
            }
        });
        list.setOnItemLongClickListener((parent, v, pos, id) -> {
            Object o = parent.getItemAtPosition(pos);
            if (!(o instanceof AppEntry)) return false;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            appMenu((AppEntry) o);
            return true;
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        llp.topMargin = dp(6);
        panel.addView(list, llp);

        // command line
        LinearLayout inRow = new LinearLayout(this);
        inRow.setOrientation(LinearLayout.HORIZONTAL);
        inRow.setGravity(Gravity.CENTER_VERTICAL);
        Drawable ib = skin.bg(Skin.Part.INPUT, density);
        if (ib != null) {
            inRow.setBackground(ib);
            int[] pd = skin.pad(Skin.Part.INPUT);
            inRow.setPadding(dp(pd[0]), dp(pd[1]), dp(pd[2]), dp(pd[3]));
        } else {
            inRow.setPadding(0, dp(6), 0, dp(6));
        }
        promptV = new TextView(this);
        promptV.setTypeface(font);
        promptV.setTextSize(TypedValue.COMPLEX_UNIT_SP, skin.size(Skin.Part.PROMPT) * fontScale);
        promptV.setTextColor(skin.color(Skin.Part.PROMPT));
        skin.shadow(promptV, Skin.Part.PROMPT, density);
        promptV.setText(skin.prompt());
        inRow.addView(promptV);
        input = new EditText(this);
        input.setTypeface(font);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, skin.size(Skin.Part.INPUT) * fontScale);
        input.setBackground(null);
        input.setPadding(0, dp(4), 0, dp(4));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (skin.upper ? InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS : 0) | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setHint(skin.hint());
        int ic = skin.color(Skin.Part.INPUT);
        input.setTextColor(ic);
        input.setHintTextColor(Skin.alpha(ic, 0x70));
        skin.shadow(input, Skin.Part.INPUT, density);
        if (Build.VERSION.SDK_INT >= 29) {
            android.graphics.drawable.GradientDrawable cur = new android.graphics.drawable.GradientDrawable();
            cur.setColor(ic);
            cur.setSize(dp(skin.font == Skin.FONT_PIXEL ? 10 : 2), dp(20));
            input.setTextCursorDrawable(cur);
        }
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
        add(panel, inRow, 6);

        // extra controls + function keys: inside the screen, or on the device body
        LinearLayout controls;
        if (skin.controlsOutside) {
            controls = new LinearLayout(this);
            controls.setOrientation(LinearLayout.VERTICAL);
            controls.setPadding(dp(10), 0, dp(10), dp(8));
            outer.addView(controls, new LinearLayout.LayoutParams(MATCH, WRAP));
        } else {
            controls = content;
        }
        View ex = skin.extra(this);
        if (ex != null) {
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(MATCH, dp(skin.extraHeightDp));
            elp.topMargin = dp(6);
            controls.addView(ex, elp);
        }
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        Drawable db = skin.bg(Skin.Part.DOCK, density);
        if (db != null) {
            dock.setBackground(db);
            dock.setPadding(dp(3), dp(3), dp(3), dp(3));
        }
        final int[] actions = skin.keyActions();
        for (int i = 0; i < 5; i++) {
            final int act = actions[i];
            TextView k = text(Skin.Part.KEY);
            k.setGravity(Gravity.CENTER);
            k.setMaxLines(2);
            k.setEllipsize(TextUtils.TruncateAt.END);
            k.setBackground(skin.keyBg(i, density));
            k.setTextColor(skin.keyColor(i));
            k.setPadding(dp(2), dp(8), dp(2), dp(8));
            k.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                fKey(act);
            });
            k.setOnLongClickListener(v -> {
                fKeyLong(act);
                return true;
            });
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, WRAP, 1f);
            kl.setMargins(dp(3), dp(4), dp(3), 0);
            dock.addView(k, kl);
            keys[i] = k;
        }
        if (skin.show(Skin.Part.DOCK)) controls.addView(dock, new LinearLayout.LayoutParams(MATCH, WRAP));

        overlay = new CrtOverlayView(this);
        overlay.configure(skin.fx, dp(skin.cornerDp), skin.bezel);
        overlay.setEffectOn(prefs.getBoolean("crt", true));
        screen.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));

        for (Skin.Part p : new Skin.Part[]{Skin.Part.TITLE, Skin.Part.STATUS, Skin.Part.CLOCK, Skin.Part.DATE, Skin.Part.RESPONSE}) {
            if (!skin.show(p)) viewFor(p).setVisibility(View.GONE);
        }

        // keep the keyboard closed on start
        content.setFocusableInTouchMode(true);
        content.requestFocus();

        setContentView(root);
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);

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
        refreshKeys();
    }

    private View viewFor(Skin.Part p) {
        switch (p) {
            case TITLE: return titleV;
            case STATUS: return statusV;
            case CLOCK: return clockV;
            case DATE: return dateV;
            default: return responseV;
        }
    }

    private void updateStatus() {
        info.apps = allApps.size();
        setIf(statusV, skin.status(info));
    }

    private void refreshFooter() {
        long freeMb = 0;
        try {
            StatFs s = new StatFs(Environment.getDataDirectory().getPath());
            freeMb = s.getAvailableBytes() / (1024 * 1024);
        } catch (Exception ignored) { }
        footerV.setText(skin.footer(info, freeMb));
    }

    private void say(String msg) { responseV.setText(msg); }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
        content.requestFocus();
    }

    // ------------------------------------------------------------------ screensaver

    private void checkSaver() {
        if (saver != null || bootView != null || menuOverlay != null) return;
        if (SystemClock.uptimeMillis() - lastTouch < SAVER_AFTER_MS) return;
        View v = skin.screensaver(this);
        if (v == null) return;
        hideKeyboard();
        saver = v;
        addFx(v);
    }

    private void hideSaver() {
        if (saver != null) {
            screen.removeView(saver);
            saver = null;
        }
        lastTouch = SystemClock.uptimeMillis();
    }

    // ------------------------------------------------------------------ boot

    private void boot() {
        if (bootView != null) return;
        dismissMenu();
        bootView = text(Skin.Part.BOOT);
        bootView.setPadding(dp(20), dp(24), dp(20), dp(20));
        bootView.setOnClickListener(v -> endBoot());
        addFx(bootView);

        final String full = skin.boot(info);
        final int[] i = {0};
        Runnable step = new Runnable() {
            @Override public void run() {
                if (bootView == null || full.isEmpty()) return;
                char c = full.charAt(Math.min(i[0], full.length() - 1));
                i[0] = Math.min(full.length(), i[0] + (c == '.' || c == '\n' ? 1 : 2));
                bootView.setText(full.substring(0, i[0]) + (skin.font == Skin.FONT_PIXEL ? "█" : "_"));
                if (i[0] < full.length()) {
                    h.postDelayed(this, c == '.' ? 45 : c == '\n' ? 140 : 14);
                } else {
                    h.postDelayed(LauncherActivity.this::endBoot, 700);
                }
            }
        };
        if (skin.fx == Skin.Fx.CRT) {
            // CRT power-on: the picture snaps open from a horizontal line
            screen.setScaleY(0.01f);
            screen.setAlpha(0.4f);
            screen.animate().scaleY(1f).alpha(1f).setDuration(420)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        } else {
            screen.setAlpha(0f);
            screen.animate().alpha(1f).setDuration(350).start();
        }
        h.postDelayed(step, 450);
    }

    private void endBoot() {
        if (bootView == null) return;
        final View b = bootView;
        bootView = null;
        say(skin.ready());
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
            e.name = (l == null ? ri.activityInfo.packageName : l.toString()).trim();
            e.label = e.name.toUpperCase(Locale.US);
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
        for (int k = 0; k < allApps.size(); k++) allApps.get(k).idx = k;
        filter(input.getText().toString());
        updateStatus();
        refreshKeys();
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
        selected = skin.cursor && !shown.isEmpty() ? 0 : -1;
        adapter.notifyDataSetChanged();
        skin.onShown(shown);
        if (selected == 0) skin.onSelect(shown.get(0));
    }

    private AppEntry best(String term) {
        if (term.isEmpty()) return null;
        AppEntry contains = null;
        for (AppEntry e : allApps) {
            if (e.label.equals(term) || e.label.startsWith(term)) return e;
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
        if (launching) return;
        final Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(e.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        say(skin.launchText(e));
        input.setText("");
        hideKeyboard();
        Runnable go = () -> {
            launching = false;
            try {
                startActivity(i);
            } catch (Exception ex) {
                say(skin.error("?FILE NOT FOUND  ERROR"));
            }
        };
        long delay = skin.launchFx(this, e);
        if (delay > 0) {
            launching = true;
            h.postDelayed(go, delay);
        } else {
            go.run();
        }
    }

    private boolean start(Intent i, String err) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception ex) {
            say(skin.error(err));
            return false;
        }
    }

    private boolean startQuiet(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void openClock() { start(new Intent(AlarmClock.ACTION_SHOW_ALARMS), "?DEVICE NOT PRESENT  ERROR"); }

    // ------------------------------------------------------------------ commands

    private String help() {
        return "COMMANDS:\n" +
                " <NAME>      RUN BEST MATCH\n" +
                " RUN <NAME>  LOAD A PROGRAM\n" +
                " THEME [X]   CHANGE SKIN\n" +
                " SKINS       LIST SKINS\n" +
                " CRT         SCREEN EFFECT ON/OFF\n" +
                " ICONS       ICONS ON/OFF\n" +
                " WEB <Q>     SEARCH THE WEB\n" +
                " CALL <NUM>  DIAL A NUMBER\n" +
                " SETTINGS    SYSTEM SETTINGS\n" +
                " HOME        CHANGE LAUNCHER\n" +
                " REBOOT      RUN BOOT SEQUENCE\n" +
                "LONG-PRESS A PROGRAM FOR OPTIONS.";
    }

    private void runCommand(String raw) {
        String c = raw.trim().toUpperCase(Locale.US);
        if (c.isEmpty()) return;
        String[] parts = c.split("\\s+", 2);
        String cmd = parts[0];
        String arg = parts.length > 1 ? parts[1].trim() : "";
        input.setText("");
        if (c.matches("\\d{1,4}")) {
            int idx = skin.numberToIndex(Integer.parseInt(c));
            if (idx >= 0 && idx < allApps.size()) launch(allApps.get(idx));
            else say(skin.error("?FILE NOT FOUND  ERROR"));
            return;
        }
        switch (cmd) {
            case "HELP": case "?":
                say(help());
                break;
            case "LIST": case "DIR": case "CLS": case "CLEAR":
                say(skin.ready());
                list.setSelection(0);
                break;
            case "SKINS":
                say("SKINS:\n" + Skins.list() + "\n" + skin.ready());
                break;
            case "THEME": case "SKIN": case "COLOR": case "COLOUR": {
                if (arg.isEmpty()) { cycleSkin(); break; }
                if (!Skins.exists(arg)) { say(skin.error("?ILLEGAL QUANTITY  ERROR\nSKINS:\n" + Skins.list())); break; }
                switchSkin(arg);
                break;
            }
            case "CRT": case "FX": {
                boolean on = !prefs.getBoolean("crt", true);
                prefs.edit().putBoolean("crt", on).apply();
                overlay.setEffectOn(on);
                overlay.setRunning(on);
                say("SCREEN EFFECT " + (on ? "ON" : "OFF") + "\n" + skin.ready());
                break;
            }
            case "ICONS": {
                boolean on = !prefs.getBoolean("icons", true);
                prefs.edit().putBoolean("icons", on).apply();
                adapter.notifyDataSetChanged();
                say("ICONS " + (on ? "ON" : "OFF") + "\n" + skin.ready());
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
                if (arg.isEmpty()) { fKey(Skin.K_F3); break; }
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
                say(Skin.fmt("HH:mm:ss  EEE dd MMM yyyy", new Date()) + "\n" + skin.ready());
                break;
            case "ABOUT": case "VER":
                say("RETRO-OS LAUNCHER V2.0  SKIN " + skin.name + "\n" + Build.MANUFACTURER.toUpperCase(Locale.US) + " "
                        + Skin.model() + "\nANDROID " + Build.VERSION.RELEASE
                        + " (API " + Build.VERSION.SDK_INT + ")\n" + skin.ready());
                break;
            case "RUN": case "LOAD": {
                AppEntry e = best(arg);
                if (e != null) launch(e);
                else say(skin.error("?FILE NOT FOUND  ERROR"));
                break;
            }
            default: {
                AppEntry e = best(c);
                if (e != null) launch(e);
                else say(skin.error("?SYNTAX  ERROR\nTYPE HELP FOR COMMANDS"));
            }
        }
    }

    private void switchSkin(String name) {
        prefs.edit().putString("skin", name.toUpperCase(Locale.US)).putBoolean("bootNext", true).apply();
        hideKeyboard();
        recreate();
    }

    // ------------------------------------------------------------------ function keys

    private void refreshKeys() {
        if (keys[0] == null) return;
        String[] labels = skin.keys(new Date());
        int[] actions = skin.keyActions();
        for (int i = 0; i < 5; i++) {
            String label = labels[i];
            int act = actions[i];
            if (act >= Skin.K_F1 && act <= Skin.K_F4) {
                AppEntry e = byComponent(prefs.getString("f" + act, null));
                if (e != null) {
                    int nl = label.indexOf('\n');
                    label = (nl >= 0 ? label.substring(0, nl + 1) : "") + shorten(skin.label(e), 8);
                }
            }
            setIf(keys[i], label);
        }
    }

    private String shorten(String s, int n) {
        String first = s.split(" ")[0];
        return first.length() <= n ? first : first.substring(0, n);
    }

    private void fKey(int act) {
        if (act == Skin.K_MENU) { systemMenu(); return; }
        if (act == Skin.K_CLOCK) { openClock(); return; }
        AppEntry e = byComponent(prefs.getString("f" + act, null));
        if (e != null) { launch(e); return; }
        Intent d;
        switch (act) {
            case Skin.K_F1: d = new Intent(Intent.ACTION_DIAL); break;
            case Skin.K_F2: d = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MESSAGING); break;
            case Skin.K_F3: d = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER); break;
            default: d = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA); break;
        }
        if (start(d, "?DEVICE NOT PRESENT  ERROR")) say(skin.ready());
    }

    private void fKeyLong(int act) {
        if (act == Skin.K_MENU) { systemMenu(); return; }
        if (act == Skin.K_CLOCK) { openClock(); return; }
        String cur = prefs.getString("f" + act, null);
        if (cur == null) {
            say("F" + (act + 1) + " = " + F_DEFAULT[act] + " (DEFAULT)\nLONG-PRESS A PROGRAM TO ASSIGN IT.\n" + skin.ready());
            return;
        }
        showMenu("F" + (act + 1) + " KEY", new String[]{"RESET TO " + F_DEFAULT[act], "CANCEL"}, pick -> {
            if (pick == 0) {
                prefs.edit().remove("f" + act).apply();
                refreshKeys();
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
                    refreshKeys();
                    say("F" + pick + " = " + e.label + "\n" + skin.ready());
                    break;
                case 5:
                    start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + e.cn.getPackageName())), "?DEVICE NOT PRESENT  ERROR");
                    break;
                case 6:
                    start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.cn.getPackageName())),
                            "?CAN'T DELETE  ERROR");
                    break;
                default: break;
            }
        });
    }

    private void systemMenu() {
        String[] opts = {
                "SKIN: " + skin.name + "  → CHANGE",
                "SCREEN EFFECT: " + (prefs.getBoolean("crt", true) ? "ON" : "OFF"),
                "ICONS: " + (prefs.getBoolean("icons", true) ? "ON" : "OFF"),
                "SYSTEM SETTINGS",
                "CHOOSE DEFAULT LAUNCHER",
                "REBOOT",
                "HELP"};
        showMenu("SYSTEM MENU", opts, pick -> {
            switch (pick) {
                case 0: skinMenu(); break;
                case 1: runCommand("CRT"); break;
                case 2: runCommand("ICONS"); break;
                case 3: runCommand("SETTINGS"); break;
                case 4: runCommand("HOME"); break;
                case 5: runCommand("REBOOT"); break;
                case 6: runCommand("HELP"); break;
                default: break;
            }
        });
    }

    private void skinMenu() {
        String[] opts = new String[Skins.NAMES.length];
        for (int i = 0; i < opts.length; i++) {
            opts[i] = Skins.NAMES[i] + (Skins.NAMES[i].equals(skin.name) ? "  ◀" : "");
        }
        showMenu("SELECT SKIN", opts, pick -> switchSkin(Skins.NAMES[pick]));
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
        box.setBackground(skin.menuBg(density));
        box.setPadding(dp(16), dp(14), dp(16), dp(10));

        TextView t = text(Skin.Part.TITLE);
        t.setText(title);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (skin.bg(Skin.Part.TITLE, density) == null) t.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));

        ScrollView sv = new ScrollView(this);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        sv.addView(items, new FrameLayout.LayoutParams(MATCH, WRAP));
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            TextView o = text(Skin.Part.MENU);
            o.setText((i + 1) + ". " + opts[i]);
            o.setPadding(dp(4), dp(11), dp(4), dp(11));
            android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Skin.alpha(skin.color(Skin.Part.MENU), 0x3A)));
            o.setBackground(sl);
            o.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                dismissMenu();
                cb.pick(idx);
            });
            items.addView(o, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        box.addView(sv, new LinearLayout.LayoutParams(MATCH, WRAP));

        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER);
        bl.setMargins(dp(24), dp(40), dp(24), dp(40));
        ov.addView(box, bl);
        addFx(ov);
        menuOverlay = ov;
    }

    private void dismissMenu() {
        if (menuOverlay != null) {
            screen.removeView(menuOverlay);
            menuOverlay = null;
        }
    }

    // ------------------------------------------------------------------ list + icons

    private static final class Row {
        LinearLayout row;
        ImageView icon;
        TextView name, tail;
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return shown.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override public View getView(int pos, View cv, ViewGroup parent) {
            Row r;
            if (cv == null) {
                FrameLayout outer = new FrameLayout(LauncherActivity.this);
                int g = dp(skin.rowGapDp());
                outer.setPadding(dp(2), g, dp(2), g);
                r = new Row();
                r.row = new LinearLayout(LauncherActivity.this);
                r.row.setOrientation(LinearLayout.HORIZONTAL);
                r.row.setGravity(Gravity.CENTER_VERTICAL);
                outer.addView(r.row, new FrameLayout.LayoutParams(MATCH, WRAP));
                r.icon = new ImageView(LauncherActivity.this);
                r.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(skin.iconDp), dp(skin.iconDp));
                il.rightMargin = dp(10);
                r.row.addView(r.icon, il);
                r.name = text(Skin.Part.ROW);
                r.name.setSingleLine(true);
                r.name.setEllipsize(TextUtils.TruncateAt.END);
                r.row.addView(r.name, new LinearLayout.LayoutParams(0, WRAP, 1f));
                r.tail = text(Skin.Part.TAIL);
                r.tail.setSingleLine(true);
                r.row.addView(r.tail);
                outer.setTag(r);
                cv = outer;
            } else {
                r = (Row) cv.getTag();
            }
            AppEntry e = shown.get(pos);
            boolean sel = pos == selected;
            r.row.setBackground(skin.rowBg(e.idx, sel, density));
            int rp = dp(skin.rowPadDp());
            r.row.setPadding(rp, dp(7), rp, dp(7));
            r.name.setText(skin.row(e, sel));
            r.name.setTextColor(skin.rowColor(e.idx, sel));
            String tail = skin.tail(e);
            r.tail.setVisibility(tail.isEmpty() ? View.GONE : View.VISIBLE);
            r.tail.setText(tail);
            r.tail.setTextColor(skin.tailColor(e.idx, sel));
            cv.setRotation(skin.rowRotation(e.idx));
            bindIcon(r.icon, e);
            return cv;
        }
    }

    private void bindIcon(ImageView iv, AppEntry e) {
        if (skin.icons == Skin.Icons.NONE || !prefs.getBoolean("icons", true)) {
            iv.setVisibility(View.GONE);
            return;
        }
        iv.setVisibility(View.VISIBLE);
        final String key = e.cn.flattenToString();
        iv.setTag(key);
        Bitmap b = iconCache.get(key);
        if (b != null) {
            iv.setImageDrawable(iconDrawable(b));
            return;
        }
        iv.setImageDrawable(null);
        if (!iconPending.add(key)) return;
        final Skin s = skin;
        final ResolveInfo ri = e.ri;
        final String label = e.label;
        final PackageManager pm = getPackageManager();
        final int realPx = dp(s.iconDp);
        iconExec.execute(() -> {
            Bitmap bm;
            try {
                bm = makeIcon(s, ri, label, pm, realPx);
            } catch (Exception ex) {
                bm = null;
            }
            final Bitmap res = bm;
            h.post(() -> {
                iconPending.remove(key);
                if (res == null || s != skin) return;
                iconCache.put(key, res);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private BitmapDrawable iconDrawable(Bitmap b) {
        BitmapDrawable d = new BitmapDrawable(getResources(), b);
        boolean smooth = skin.icons == Skin.Icons.REAL;
        d.setFilterBitmap(smooth); // pixel icons keep hard edges when scaled up
        d.setAntiAlias(smooth);
        return d;
    }

    private static Bitmap makeIcon(Skin s, ResolveInfo ri, String label, PackageManager pm, int realPx) {
        switch (s.icons) {
            case CRITTER:
                return FxViews.critter(label);
            case REAL: {
                Drawable d = ri.loadIcon(pm);
                Bitmap b = Bitmap.createBitmap(realPx, realPx, Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, realPx, realPx);
                d.draw(new Canvas(b));
                return b;
            }
            default:
                return pixelate(ri.loadIcon(pm), s.palette());
        }
    }

    /** Renders the app icon at 16x16 and remaps it to the skin's four shades. */
    private static Bitmap pixelate(Drawable d, int[] pal) {
        final int n = 16;
        Bitmap small = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        int[] px = new int[n * n];
        small.getPixels(px, 0, n, 0, 0, n, n);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            if ((p >>> 24) < 110) {
                px[i] = 0;
                continue;
            }
            float lum = (0.299f * ((p >> 16) & 0xFF) + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF)) / 255f;
            px[i] = pal[lum > 0.75f ? 3 : lum > 0.5f ? 2 : lum > 0.25f ? 1 : 0];
        }
        small.setPixels(px, 0, n, 0, 0, n, n);
        return small;
    }
}
