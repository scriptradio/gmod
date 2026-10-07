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
    private CheckBox swap;
    private CheckBox invX;
    private CheckBox invY;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (USB_ACTION.equals(i.getAction())) {
                UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean ok = d != null && usb.hasPermission(d);
                Toast.makeText(MainActivity.this, ok ? "USB permission granted" : "USB permission denied", Toast.LENGTH_SHORT).show();
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
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4);
        }
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        ShizukuShell.init(this);
        refresh();
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

        root.addView(t("DeX MicroTouch Bridge v0.3", 24));
        root.addView(t("3M MicroTouch USB → Samsung DeX. v0.3 adds ADB/Shizuku-level input injection for DeX.", 15));

        usbInfo = t("", 14);
        displayInfo = t("", 14);
        shizukuInfo = t("", 14);
        status = t("", 14);

        root.addView(t("\n1. USB touchscreen", 18));
        root.addView(usbInfo);
        Button grant = new Button(this);
        grant.setText("Grant USB permission");
        grant.setOnClickListener(v -> grantUsb());
        root.addView(grant);

        root.addView(t("\n2. Shizuku input injector", 18));
        root.addView(shizukuInfo);
        Button shizuku = new Button(this);
        shizuku.setText("REQUEST / CONNECT SHIZUKU");
        shizuku.setOnClickListener(v -> {
            ShizukuShell.requestPermission();
            refreshDelayed();
        });
        root.addView(shizuku);
        root.addView(t("Shizuku must show Running. On a non-rooted phone it can be started entirely on-device using Android Wireless debugging.", 13));

        root.addView(t("\n3. DeX display", 18));
        root.addView(displayInfo);

        Button test = new Button(this);
        test.setText("TEST CENTER TAP ON DEX");
        test.setOnClickListener(v -> {
            Display d = externalDisplay();
            if (d == null) {
                Toast.makeText(this, "No DeX display found", Toast.LENGTH_SHORT).show();
                return;
            }
            android.util.DisplayMetrics m = new android.util.DisplayMetrics();
            d.getRealMetrics(m);
            boolean ok = ShizukuShell.tap(d.getDisplayId(), m.widthPixels / 2f, m.heightPixels / 2f);
            Toast.makeText(this, ok ? "Center tap injected" : "Injection failed / Shizuku not ready", Toast.LENGTH_LONG).show();
            refreshDelayed();
        });
        root.addView(test);

        root.addView(t("\n4. Orientation", 18));
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
        root.addView(swap);
        root.addView(invX);
        root.addView(invY);

        Button start = new Button(this);
        start.setText("START TOUCH BRIDGE");
        start.setOnClickListener(v -> {
            prefs().edit()
                    .putBoolean("swap", swap.isChecked())
                    .putBoolean("invx", invX.isChecked())
                    .putBoolean("invy", invY.isChecked())
                    .apply();
            try {
                ShizukuShell.bind();
                startForegroundService(new Intent(this, TouchBridgeService.class));
                Toast.makeText(this, "Bridge starting", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
            }
            refreshDelayed();
        });
        root.addView(start);

        Button stop = new Button(this);
        stop.setText("STOP");
        stop.setOnClickListener(v -> stopService(new Intent(this, TouchBridgeService.class)));
        root.addView(stop);

        root.addView(t("\nOptional fallback", 18));
        Button access = new Button(this);
        access.setText("Open Accessibility settings");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(access);
        root.addView(t("Accessibility injection did not affect DeX on your phone, but remains available as a fallback.", 13));

        root.addView(t("\nLive diagnostics", 18));
        root.addView(status);

        Button refresh = new Button(this);
        refresh.setText("Refresh status");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);

        setContentView(sv);
    }

    private void refreshDelayed() {
        refresh();
        status.postDelayed(this::refresh, 600);
        status.postDelayed(this::refresh, 1600);
    }

    private void grantUsb() {
        UsbDevice d = find3M();
        if (d == null) {
            Toast.makeText(this, "No 3M USB controller found", Toast.LENGTH_LONG).show();
            return;
        }
        PendingIntent pi = PendingIntent.getBroadcast(
                this, 0, new Intent(USB_ACTION).setPackage(getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        usb.requestPermission(d, pi);
    }

    private UsbDevice find3M() {
        UsbDevice fallback = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (fallback == null) fallback = d;
            if (d.getVendorId() == 0x0596) return d;
        }
        return fallback;
    }

    private Display externalDisplay() {
        DisplayManager dm = (DisplayManager)getSystemService(DISPLAY_SERVICE);
        Display[] presentation = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (presentation.length > 0) return presentation[0];
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        }
        return null;
    }

    private void refresh() {
        UsbDevice d = find3M();
        if (d == null) {
            usbInfo.setText("No USB device detected.");
        } else {
            String name = d.getProductName();
            usbInfo.setText(String.format(Locale.US,
                    "%s\nVID:PID %04X:%04X\nUSB permission: %s",
                    name == null ? "USB device" : name,
                    d.getVendorId(), d.getProductId(),
                    usb.hasPermission(d) ? "YES" : "NO"));
        }

        shizukuInfo.setText(
                "Shizuku: " + ShizukuShell.status + "\n" +
                "Binder running: " + (ShizukuShell.isRunning() ? "YES" : "NO") + "\n" +
                "Permission: " + (ShizukuShell.hasPermission() ? "YES" : "NO") + "\n" +
                "Shell injection success/fail: " + ShizukuShell.injected + " / " + ShizukuShell.failed
        );

        Display x = externalDisplay();
        displayInfo.setText(x == null
                ? "No external logical display found. Start DeX first."
                : "External display: " + x.getName() + " | display ID " + x.getDisplayId());

        status.setText(
                "Bridge: " + TouchBridgeService.state + "\n" +
                "Protocol: " + TouchBridgeService.protocol + "\n" +
                "Packets: " + TouchBridgeService.packets + "\n" +
                "Raw: " + TouchBridgeService.rawX + ", " + TouchBridgeService.rawY +
                " | down=" + TouchBridgeService.down + "\n" +
                "Mapped: " + Math.round(TouchBridgeService.outX) + ", " + Math.round(TouchBridgeService.outY) + "\n" +
                "Injection route: " + TouchBridgeService.injectionRoute + "\n" +
                "Shell injected: " + ShizukuShell.injected + " | failed: " + ShizukuShell.failed + "\n" +
                "Accessibility injected: " + TouchAccessibilityService.injected + "\n" +
                (TouchBridgeService.error.isEmpty() ? "" : "Error: " + TouchBridgeService.error));
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }
}
