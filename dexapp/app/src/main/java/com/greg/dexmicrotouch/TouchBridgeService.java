package com.greg.dexmicrotouch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Display;

import java.util.Locale;

public final class TouchBridgeService extends Service {
    private static final String CHANNEL = "microtouch_bridge";
    public static volatile String state = "Stopped";
    public static volatile String protocol = "None";
    public static volatile String error = "";
    public static volatile long packets = 0;
    public static volatile int rawX = 0, rawY = 0;
    public static volatile boolean down = false;
    public static volatile float outX = 0, outY = 0;

    private volatile boolean running;
    private Thread worker;
    private UsbDeviceConnection conn;
    private UsbInterface intf;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "DeX touchscreen bridge", NotificationManager.IMPORTANCE_LOW));
        startForeground(77, note("Starting"));
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (worker == null || !worker.isAlive()) {
            running = true;
            worker = new Thread(this::runBridge, "MicroTouchBridge");
            worker.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        cleanup();
        state = "Stopped";
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    private void runBridge() {
        try {
            error = "";
            packets = 0;
            UsbManager um = (UsbManager)getSystemService(USB_SERVICE);
            UsbDevice dev = findDevice(um);
            if (dev == null) throw new Exception("No USB device found");
            if (!um.hasPermission(dev)) throw new Exception("USB permission missing");

            Pair p = findInput(dev);
            if (p == null) throw new Exception("No USB IN endpoint found");
            intf = p.i;
            conn = um.openDevice(dev);
            if (conn == null) throw new Exception("Could not open USB device");
            if (!conn.claimInterface(intf, true)) throw new Exception("Could not claim USB interface");

            int pid = dev.getProductId();
            boolean ex2 = dev.getVendorId() == 0x0596 && pid == 0x0001;
            boolean rx = dev.getVendorId() == 0x0596 && pid == 0x0102;
            if (ex2) {
                protocol = "3M EX II / 0596:0001";
                initEx2(conn);
            } else if (rx) {
                protocol = "3M RX151-style HID / 0596:0102";
            } else {
                protocol = String.format(Locale.US, "3M/USB fallback %04X:%04X", dev.getVendorId(), pid);
            }

            Display d = externalDisplay();
            if (d == null) throw new Exception("No DeX/external display found");
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            int w = m.widthPixels, h = m.heightPixels;
            int displayId = d.getDisplayId();

            if (!TouchAccessibilityService.ready()) throw new Exception("Accessibility service is not enabled");

            SharedPreferences sp = getSharedPreferences("bridge", MODE_PRIVATE);
            boolean swap = sp.getBoolean("swap", false);
            boolean invx = sp.getBoolean("invx", false);
            boolean invy = sp.getBoolean("invy", false);

            byte[] buf = new byte[Math.max(64, p.e.getMaxPacketSize())];
            state = "Running on display " + displayId + " (" + w + "x" + h + ")";
            updateNote(state);

            while (running && !Thread.currentThread().isInterrupted()) {
                int n = conn.bulkTransfer(p.e, buf, buf.length, 1000);
                if (n <= 0) continue;
                packets++;

                int x, y;
                boolean pressed;
                int max;

                if (n >= 7 && (buf[0] & 0xff) == 0x01) {
                    pressed = (buf[1] & 0xff) != 0;
                    x = (((buf[3] & 0xff) << 8) | (buf[2] & 0xff)) & 0x03ff;
                    y = (((buf[5] & 0xff) << 8) | (buf[4] & 0xff)) & 0x03ff;
                    max = 1023;
                    if (!rx) protocol = "RX151-compatible 7-byte Report 1";
                } else if (n >= 11 && ex2) {
                    pressed = ((buf[2] & 0xff) & 0x40) != 0;
                    x = ((buf[8] & 0xff) << 8) | (buf[7] & 0xff);
                    y = ((buf[10] & 0xff) << 8) | (buf[9] & 0xff);
                    max = 16384;
                } else {
                    state = "Receiving USB packets, unsupported report format (len " + n + ")";
                    continue;
                }

                rawX = x; rawY = y; down = pressed;
                double nx = clamp(x / (double)max);
                double ny = clamp(y / (double)max);
                if (swap) { double q = nx; nx = ny; ny = q; }
                if (invx) nx = 1.0 - nx;
                if (invy) ny = 1.0 - ny;
                outX = (float)(nx * Math.max(0, w - 1));
                outY = (float)(ny * Math.max(0, h - 1));
                TouchAccessibilityService.inject(displayId, pressed, outX, outY);
            }
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
            state = "Error";
            updateNote(error);
        } finally {
            cleanup();
        }
    }

    private static double clamp(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private UsbDevice findDevice(UsbManager um) {
        UsbDevice any = null;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (any == null) any = d;
            if (d.getVendorId() == 0x0596) return d;
        }
        return any;
    }

    private Pair findInput(UsbDevice d) {
        Pair fallback = null;
        for (int a=0; a<d.getInterfaceCount(); a++) {
            UsbInterface ui = d.getInterface(a);
            for (int b=0; b<ui.getEndpointCount(); b++) {
                UsbEndpoint e = ui.getEndpoint(b);
                if (e.getDirection() != UsbConstants.USB_DIR_IN) continue;
                Pair p = new Pair(ui,e);
                if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) return p;
                if (fallback == null && e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) fallback = p;
            }
        }
        return fallback;
    }

    private Display externalDisplay() {
        DisplayManager dm = (DisplayManager)getSystemService(DISPLAY_SERVICE);
        Display[] pres = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (pres.length > 0) return pres[0];
        for (Display d : dm.getDisplays()) if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        return null;
    }

    private void initEx2(UsbDeviceConnection c) throws Exception {
        byte[] fw = new byte[16];
        c.controlTransfer(0xC0, 10, 0, 0, fw, fw.length, 1000);
        if (c.controlTransfer(0x40, 7, 1, 0, null, 0, 1000) < 0) throw new Exception("3M reset failed");
        Thread.sleep(150);
        int r = -1;
        for (int x=0; x<3 && r<0; x++) {
            r = c.controlTransfer(0x40, 1, 1, 1, null, 0, 1000);
            if (r < 0) Thread.sleep(25);
        }
        if (r < 0) throw new Exception("3M async mode failed");
    }

    private void cleanup() {
        try { if (conn != null && intf != null) conn.releaseInterface(intf); } catch (Exception ignored) {}
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        conn = null; intf = null;
    }

    private Notification note(String s) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("DeX MicroTouch Bridge")
                .setContentText(s)
                .setOngoing(true)
                .build();
    }

    private void updateNote(String s) {
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(77, note(s));
    }

    private static final class Pair {
        final UsbInterface i; final UsbEndpoint e;
        Pair(UsbInterface i, UsbEndpoint e) { this.i=i; this.e=e; }
    }
}
