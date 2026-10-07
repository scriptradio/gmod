package com.greg.dexmicrotouch;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String USB_ACTION = "com.greg.dexmicrotouch.USB_PERMISSION";

    private UsbManager usb;
    private TextView status;
    private TextView usbInfo;
    private TextView displayInfo;
    private TextView shizukuInfo;
    private TextView calibrationInfo;
    private TextView audioInfo;
    private CheckBox swap;
    private CheckBox invX;
    private CheckBox invY;
    private CheckBox auto;
    private CheckBox edgeGestures;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (USB_ACTION.equals(i.getAction())) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean ok = d != null && usb.hasPermission(d);
                Toast.makeText(MainActivity.this,
                        ok ? "USB permission granted" : "USB permission denied",
                        Toast.LENGTH_SHORT).show();

                if (ok && prefs().getBoolean("auto", true)) {
                    startBridgeServiceIfUsbAuthorized();
                }
            }
            refresh();
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        usb = (UsbManager)getSystemService(USB_SERVICE);
        ShizukuShell.init(this);
        buildUi();

        IntentFilter f = new IntentFilter();
        f.addAction(USB_ACTION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4);
        }

        refreshDelayed();
    }

    @Override protected void onResume() {
        super.onResume();
        ShizukuShell.init(this);
        refreshDelayed();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p,p,p,p);
        sv.addView(root);

        root.addView(t("DeX MicroTouch Bridge v0.5", 24));
        root.addView(t(
                "Single-touch 3M MicroTouch → Samsung DeX bridge with edge navigation, calibration, and media-output controls.",
                15));

        usbInfo = t("", 14);
        displayInfo = t("", 14);
        shizukuInfo = t("", 14);
        calibrationInfo = t("", 14);
        audioInfo = t("", 14);
        status = t("", 14);

        root.addView(t("\n1. Automatic mode", 18));

        auto = new CheckBox(this);
        auto.setText("Auto-connect whenever DeX + 3M touchscreen are available");
        auto.setChecked(prefs().getBoolean("auto", true));
        auto.setOnCheckedChangeListener((button, checked) -> {
            prefs().edit().putBoolean("auto", checked).apply();

            if (checked) {
                startBridgeServiceIfUsbAuthorized();
                Toast.makeText(this, "Automatic bridge enabled", Toast.LENGTH_SHORT).show();
            } else {
                stopService(new Intent(this, TouchBridgeService.class));
                Toast.makeText(this, "Automatic bridge disabled", Toast.LENGTH_SHORT).show();
            }

            refreshDelayed();
        });
        root.addView(auto);

        edgeGestures = new CheckBox(this);
        edgeGestures.setText("Single-finger DeX edge navigation");
        edgeGestures.setChecked(prefs().getBoolean("edgeGestures", true));
        edgeGestures.setOnCheckedChangeListener((b, checked) ->
                prefs().edit().putBoolean("edgeGestures", checked).apply());
        root.addView(edgeGestures);

        root.addView(t(
                "Edge gestures: swipe inward from either side = Back; swipe up from bottom = Home; swipe up from bottom and hold about 0.7 sec = Recents.",
                13));

        root.addView(t("\n2. USB touchscreen", 18));
        root.addView(usbInfo);

        Button grant = new Button(this);
        grant.setText("Grant USB permission");
        grant.setOnClickListener(v -> grantUsb());
        root.addView(grant);

        root.addView(t("\n3. Shizuku shell injector", 18));
        root.addView(shizukuInfo);

        Button shizuku = new Button(this);
        shizuku.setText("REQUEST / CONNECT SHIZUKU");
        shizuku.setOnClickListener(v -> {
            ShizukuShell.requestPermission();
            refreshDelayed();
        });
        root.addView(shizuku);

        root.addView(t("\n4. DeX display", 18));
        root.addView(displayInfo);

        Button test = new Button(this);
        test.setText("TEST CENTER TAP ON DEX");
        test.setOnClickListener(v -> {
            Display d = externalDisplay();

            if (d == null) {
                Toast.makeText(this, "No DeX display found", Toast.LENGTH_SHORT).show();
                return;
            }

            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);

            boolean ok = ShizukuShell.tap(
                    d.getDisplayId(),
                    m.widthPixels / 2f,
                    m.heightPixels / 2f);

            Toast.makeText(this,
                    ok ? "Center tap injected" : "Injection failed / Shizuku not ready",
                    Toast.LENGTH_LONG).show();

            refreshDelayed();
        });
        root.addView(test);

        Button reset = new Button(this);
        reset.setText("FORCE RELEASE STUCK TOUCH");
        reset.setOnClickListener(v -> {
            Display d = externalDisplay();
            if (d == null) return;

            boolean ok = ShizukuShell.resetTouch(
                    d.getDisplayId(),
                    TouchBridgeService.outX,
                    TouchBridgeService.outY);

            Toast.makeText(this,
                    ok ? "Touch stream reset" : "Could not reset touch stream",
                    Toast.LENGTH_SHORT).show();

            refreshDelayed();
        });
        root.addView(reset);

        root.addView(t("\n5. Fine calibration", 18));
        calibrationInfo = t("", 14);
        root.addView(calibrationInfo);

        root.addView(buttonRow(
                button("X -5", v -> adjustOffset("offsetX", -5)),
                button("X +5", v -> adjustOffset("offsetX", 5))));

        root.addView(buttonRow(
                button("Y -5", v -> adjustOffset("offsetY", -5)),
                button("Y +5", v -> adjustOffset("offsetY", 5))));

        Button resetCalibration = new Button(this);
        resetCalibration.setText("RESET CALIBRATION OFFSET");
        resetCalibration.setOnClickListener(v -> {
            prefs().edit().putInt("offsetX", 0).putInt("offsetY", 0).apply();
            refresh();
        });
        root.addView(resetCalibration);

        root.addView(t("\n6. Orientation", 18));

        swap = new CheckBox(this);
        swap.setText("Swap X / Y");

        invX = new CheckBox(this);
        invX.setText("Invert X");

        invY = new CheckBox(this);
        invY.setText("Invert Y");

        SharedPreferences sp = prefs();

        swap.setChecked(sp.getBoolean("swap", false));
        invX.setChecked(sp.getBoolean("invx", false));
        invY.setChecked(sp.getBoolean("invy", false));

        swap.setOnCheckedChangeListener((b, v) ->
                prefs().edit().putBoolean("swap", v).apply());
        invX.setOnCheckedChangeListener((b, v) ->
                prefs().edit().putBoolean("invx", v).apply());
        invY.setOnCheckedChangeListener((b, v) ->
                prefs().edit().putBoolean("invy", v).apply());

        root.addView(swap);
        root.addView(invX);
        root.addView(invY);

        root.addView(t("\n7. Audio output", 18));
        root.addView(audioInfo);

        root.addView(buttonRow(
                button("AUDIO → HDMI", v -> {
                    String r = AudioRouteHelper.routeToHdmi(this);
                    Toast.makeText(this, r, Toast.LENGTH_LONG).show();
                    refreshDelayed();
                }),
                button("AUDIO → PHONE", v -> {
                    String r = AudioRouteHelper.routeToPhone(this);
                    Toast.makeText(this, r, Toast.LENGTH_LONG).show();
                    refreshDelayed();
                })));

        Button picker = new Button(this);
        picker.setText("OPEN SAMSUNG MEDIA OUTPUT");
        picker.setOnClickListener(v -> AudioRouteHelper.openSystemOutputSwitcher(this));
        root.addView(picker);

        root.addView(t(
                "If One UI exposes the HDMI route to Android, the HDMI/Phone buttons switch it directly. If Samsung hides the route, HDMI falls back to the system Media Output picker.",
                13));

        Button start = new Button(this);
        start.setText("START / RECONNECT BRIDGE NOW");
        start.setOnClickListener(v -> {
            ShizukuShell.bind();
            startBridgeServiceIfUsbAuthorized();
            refreshDelayed();
        });
        root.addView(start);

        root.addView(t("\nLive diagnostics", 18));
        root.addView(status);

        Button refresh = new Button(this);
        refresh.setText("Refresh status");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);

        root.addView(t("\nOptional Accessibility fallback", 18));

        Button access = new Button(this);
        access.setText("Open Accessibility settings");
        access.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(access);

        setContentView(sv);
        refresh();
    }

    private void adjustOffset(String key, int delta) {
        int old = prefs().getInt(key, 0);
        int next = Math.max(-150, Math.min(150, old + delta));
        prefs().edit().putInt(key, next).apply();
        refresh();
    }

    private Button button(String label, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        return b;
    }

    private LinearLayout buttonRow(Button a, Button b) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(a);
        row.addView(b);
        return row;
    }

    private void startBridgeServiceIfUsbAuthorized() {
        UsbDevice d = find3M();

        if (d == null || !usb.hasPermission(d)) {
            Toast.makeText(this,
                    "3M USB permission is required before the bridge can start",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            startForegroundService(new Intent(this, TouchBridgeService.class));
        } catch (Throwable t) {
            Toast.makeText(this,
                    "Could not start bridge: " + t.getClass().getSimpleName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void refreshDelayed() {
        refresh();
        status.postDelayed(this::refresh, 500);
        status.postDelayed(this::refresh, 1500);
    }

    private void grantUsb() {
        UsbDevice d = find3M();

        if (d == null) {
            Toast.makeText(this, "No 3M USB controller found", Toast.LENGTH_LONG).show();
            return;
        }

        PendingIntent pi = PendingIntent.getBroadcast(
                this,
                0,
                new Intent(USB_ACTION).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        usb.requestPermission(d, pi);
    }

    private UsbDevice find3M() {
        UsbDevice fallback = null;

        for (UsbDevice d : usb.getDeviceList().values()) {
            if (fallback == null) fallback = d;

            if (d.getVendorId() == 0x0596 && d.getProductId() == 0x0300) return d;
            if (d.getVendorId() == 0x0596) fallback = d;
        }

        return fallback;
    }

    private Display externalDisplay() {
        DisplayManager dm = (DisplayManager)getSystemService(DISPLAY_SERVICE);

        Display[] presentation =
                dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);

        if (presentation.length > 0) return presentation[0];

        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        }

        return null;
    }

    private void refresh() {
        UsbDevice d = find3M();

        if (d == null) {
            usbInfo.setText("No 3M USB controller detected.");
        } else {
            String name = d.getProductName();

            usbInfo.setText(String.format(Locale.US,
                    "%s\nVID:PID %04X:%04X\nUSB permission: %s",
                    name == null ? "USB device" : name,
                    d.getVendorId(),
                    d.getProductId(),
                    usb.hasPermission(d) ? "YES" : "NO"));
        }

        shizukuInfo.setText(
                "Shizuku: " + ShizukuShell.status + "\n" +
                "Binder running: " + (ShizukuShell.isRunning() ? "YES" : "NO") + "\n" +
                "Permission: " + (ShizukuShell.hasPermission() ? "YES" : "NO") + "\n" +
                "Shell injection success/fail: " +
                ShizukuShell.injected + " / " + ShizukuShell.failed
        );

        Display x = externalDisplay();

        displayInfo.setText(x == null
                ? "No external logical display found."
                : "External display: " + x.getName() +
                  " | display ID " + x.getDisplayId());

        calibrationInfo.setText(
                "X offset: " + prefs().getInt("offsetX", 0) + " px" +
                "    Y offset: " + prefs().getInt("offsetY", 0) + " px");

        audioInfo.setText("Available audio routes: " +
                AudioRouteHelper.describeRoutes(this));

        status.setText(
                "Auto mode: " + (prefs().getBoolean("auto", true) ? "ON" : "OFF") + "\n" +
                "Edge nav: " + (prefs().getBoolean("edgeGestures", true) ? "ON" : "OFF") + "\n" +
                "Bridge: " + TouchBridgeService.state + "\n" +
                "Protocol: " + TouchBridgeService.protocol + "\n" +
                "Packets: " + TouchBridgeService.packets + "\n" +
                "Report status: " + TouchBridgeService.reportStatus + "\n" +
                "Raw: " + TouchBridgeService.rawX + ", " + TouchBridgeService.rawY +
                " | down=" + TouchBridgeService.down + "\n" +
                "Mapped: " + Math.round(TouchBridgeService.outX) + ", " +
                Math.round(TouchBridgeService.outY) + "\n" +
                "Last edge action: " + TouchBridgeService.lastGesture + "\n" +
                "Injection route: " + TouchBridgeService.injectionRoute + "\n" +
                "Shell injected: " + ShizukuShell.injected +
                " | failed: " + ShizukuShell.failed + "\n" +
                (TouchBridgeService.error.isEmpty()
                        ? ""
                        : "Last error: " + TouchBridgeService.error));
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("bridge", MODE_PRIVATE);
    }

    private TextView t(String s, float size) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextIsSelectable(true);
        v.setPadding(0, dp(5), 0, dp(5));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }
}
