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
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.Locale;

public final class TouchBridgeService extends Service {
    private static final String CHANNEL = "microtouch_bridge";
    private static final float MOVE_THRESHOLD_PX = 2.5f;

    private static final int EDGE_NONE = 0;
    private static final int EDGE_LEFT = 1;
    private static final int EDGE_RIGHT = 2;
    private static final int EDGE_BOTTOM = 3;

    public static volatile String state = "Stopped";
    public static volatile String protocol = "None";
    public static volatile String error = "";
    public static volatile String injectionRoute = "None";
    public static volatile String reportStatus = "--";
    public static volatile String lastGesture = "--";
    public static volatile long packets = 0;
    public static volatile int rawX = 0;
    public static volatile int rawY = 0;
    public static volatile boolean down = false;
    public static volatile float outX = 0;
    public static volatile float outY = 0;

    private volatile boolean running;
    private Thread worker;
    private UsbDeviceConnection conn;
    private UsbInterface intf;

    @Override public void onCreate() {
        super.onCreate();
        ShizukuShell.init(this);

        NotificationManager nm =
                (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL,
                "DeX touchscreen bridge",
                NotificationManager.IMPORTANCE_LOW));

        startForeground(77, note("Waiting for DeX / touchscreen"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        ShizukuShell.bind();

        if (worker == null || !worker.isAlive()) {
            running = true;
            worker = new Thread(this::supervisorLoop, "MicroTouchBridge");
            worker.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        releaseUsb();
        state = "Stopped";
        injectionRoute = "None";
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void supervisorLoop() {
        UsbManager um = (UsbManager)getSystemService(USB_SERVICE);

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                if (!getSharedPreferences("bridge", MODE_PRIVATE)
                        .getBoolean("auto", true)) {
                    state = "Automatic mode disabled";
                    updateNote(state);
                    sleepQuiet(1000);
                    continue;
                }

                ShizukuShell.bind();

                if (!ShizukuShell.ready()) {
                    state = ShizukuShell.isRunning()
                            ? "Waiting for Shizuku permission / injector"
                            : "Waiting for Shizuku to start";
                    injectionRoute = "Waiting for Shizuku";
                    updateNote(state);
                    sleepQuiet(900);
                    continue;
                }

                UsbDevice dev = findDevice(um);
                if (dev == null) {
                    state = "Waiting for 3M MicroTouch USB controller";
                    protocol = "None";
                    updateNote(state);
                    sleepQuiet(700);
                    continue;
                }

                if (!um.hasPermission(dev)) {
                    state = "Waiting for USB permission";
                    updateNote(state);
                    sleepQuiet(700);
                    continue;
                }

                Display display = externalDisplay();
                if (display == null) {
                    state = "Waiting for DeX display";
                    updateNote(state);
                    sleepQuiet(700);
                    continue;
                }

                runSession(um, dev, display);

            } catch (Throwable t) {
                error = t.getClass().getSimpleName() + ": " +
                        String.valueOf(t.getMessage());
                state = "Recovering: " + error;
                updateNote(state);
                releaseUsb();
                sleepQuiet(750);
            }
        }

        releaseUsb();
    }

    private void runSession(UsbManager um, UsbDevice dev, Display display) throws Exception {
        releaseUsb();

        Pair p = findInput(dev);
        if (p == null) throw new Exception("No USB IN endpoint found");

        intf = p.i;
        conn = um.openDevice(dev);
        if (conn == null) throw new Exception("Could not open USB device");
        if (!conn.claimInterface(intf, true)) {
            throw new Exception("Could not claim USB interface");
        }

        int pid = dev.getProductId();
        boolean ex2 = dev.getVendorId() == 0x0596 && pid == 0x0001;
        boolean rx151 = dev.getVendorId() == 0x0596 && pid == 0x0102;
        boolean dx = dev.getVendorId() == 0x0596 && pid == 0x0300;

        if (ex2) {
            protocol = "3M EX II / 0596:0001";
            initEx2(conn);
        } else if (rx151) {
            protocol = "3M RX151 / 0596:0102";
        } else if (dx) {
            protocol = "3M MicroTouch DX / 0596:0300";
        } else {
            protocol = String.format(Locale.US,
                    "3M/USB %04X:%04X",
                    dev.getVendorId(),
                    pid);
        }

        DisplayMetrics m = new DisplayMetrics();
        display.getRealMetrics(m);
        final int width = m.widthPixels;
        final int height = m.heightPixels;
        final int displayId = display.getDisplayId();

        ShizukuShell.resetTouch(displayId, 0f, 0f);

        state = "Active on DeX display " + displayId +
                " (" + width + "x" + height + ")";
        injectionRoute = "Shizuku shell InputManager";
        error = "";
        updateNote("Touch active on DeX");

        byte[] buf = new byte[Math.max(64, p.e.getMaxPacketSize())];

        boolean streamDown = false;
        float lastSentX = Float.NaN;
        float lastSentY = Float.NaN;

        int edgeMode = EDGE_NONE;
        float gestureStartX = 0f;
        float gestureStartY = 0f;
        long gestureStartTime = 0L;

        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                if (!isDevicePresent(um, dev)
                        || !ShizukuShell.ready()
                        || !isDisplayPresent(displayId)) {
                    break;
                }

                int n = conn.bulkTransfer(p.e, buf, buf.length, 250);
                if (n <= 0) continue;

                packets++;

                int x;
                int y;
                boolean pressed;
                int max;

                if (n >= 7 && (buf[0] & 0xff) == 0x01) {
                    int statusByte = buf[1] & 0xff;
                    reportStatus = String.format(Locale.US, "0x%02X", statusByte);

                    if ((statusByte & 0x02) == 0) {
                        continue;
                    }

                    pressed = (statusByte & 0x01) != 0;
                    x = (((buf[3] & 0xff) << 8) |
                            (buf[2] & 0xff)) & 0x03ff;
                    y = (((buf[5] & 0xff) << 8) |
                            (buf[4] & 0xff)) & 0x03ff;
                    max = 1023;

                    if (!rx151 && !dx) {
                        protocol = "3M-compatible 7-byte Report 1";
                    }

                } else if (n >= 11 && ex2) {
                    int statusByte = buf[2] & 0xff;
                    reportStatus = String.format(Locale.US, "0x%02X", statusByte);
                    pressed = (statusByte & 0x40) != 0;
                    x = ((buf[8] & 0xff) << 8) | (buf[7] & 0xff);
                    y = ((buf[10] & 0xff) << 8) | (buf[9] & 0xff);
                    max = 16384;

                } else {
                    state = "USB active; unsupported packet length " + n;
                    continue;
                }

                rawX = x;
                rawY = y;
                down = pressed;

                SharedPreferences sp = getSharedPreferences("bridge", MODE_PRIVATE);
                boolean swap = sp.getBoolean("swap", false);
                boolean invx = sp.getBoolean("invx", false);
                boolean invy = sp.getBoolean("invy", false);
                int offsetX = sp.getInt("offsetX", 0);
                int offsetY = sp.getInt("offsetY", 0);
                boolean edgeGestures = sp.getBoolean("edgeGestures", true);

                double nx = clamp01(x / (double)max);
                double ny = clamp01(y / (double)max);

                if (swap) {
                    double q = nx;
                    nx = ny;
                    ny = q;
                }
                if (invx) nx = 1.0 - nx;
                if (invy) ny = 1.0 - ny;

                outX = clampPx((float)(nx * Math.max(0, width - 1)) + offsetX, width);
                outY = clampPx((float)(ny * Math.max(0, height - 1)) + offsetY, height);

                if (pressed && !streamDown && edgeMode == EDGE_NONE) {
                    int detectedEdge = edgeGestures ? detectEdge(outX, outY, width, height) : EDGE_NONE;

                    if (detectedEdge != EDGE_NONE) {
                        edgeMode = detectedEdge;
                        gestureStartX = outX;
                        gestureStartY = outY;
                        gestureStartTime = android.os.SystemClock.uptimeMillis();
                        ShizukuShell.resetTouch(displayId, outX, outY);
                        continue;
                    }

                    ShizukuShell.resetTouch(displayId, outX, outY);

                    boolean ok = ShizukuShell.inject(
                            displayId,
                            MotionEvent.ACTION_DOWN,
                            outX,
                            outY);

                    if (!ok) {
                        throw new Exception("Touch DOWN injection failed");
                    }

                    streamDown = true;
                    lastSentX = outX;
                    lastSentY = outY;

                } else if (pressed && edgeMode != EDGE_NONE) {
                    // Hold the touch locally until release so the edge gesture
                    // doesn't drag or click the foreground app.
                    continue;

                } else if (pressed && streamDown) {
                    float dxp = outX - lastSentX;
                    float dyp = outY - lastSentY;

                    if (Float.isNaN(lastSentX)
                            || (dxp * dxp + dyp * dyp)
                            >= MOVE_THRESHOLD_PX * MOVE_THRESHOLD_PX) {

                        boolean ok = ShizukuShell.inject(
                                displayId,
                                MotionEvent.ACTION_MOVE,
                                outX,
                                outY);

                        if (!ok) {
                            throw new Exception("Touch MOVE injection failed");
                        }

                        lastSentX = outX;
                        lastSentY = outY;
                    }

                } else if (!pressed && edgeMode != EDGE_NONE) {
                    performEdgeGesture(
                            displayId,
                            edgeMode,
                            gestureStartX,
                            gestureStartY,
                            outX,
                            outY,
                            android.os.SystemClock.uptimeMillis() - gestureStartTime);

                    edgeMode = EDGE_NONE;
                    gestureStartTime = 0L;

                } else if (!pressed && streamDown) {
                    boolean ok = ShizukuShell.inject(
                            displayId,
                            MotionEvent.ACTION_UP,
                            outX,
                            outY);

                    if (!ok) {
                        ShizukuShell.resetTouch(displayId, outX, outY);
                    }

                    streamDown = false;
                    lastSentX = Float.NaN;
                    lastSentY = Float.NaN;
                }
            }

        } finally {
            down = false;
            if (streamDown) {
                ShizukuShell.resetTouch(displayId, outX, outY);
            }
            releaseUsb();
            state = "Reconnecting";
            updateNote(state);
        }
    }

    private int detectEdge(float x, float y, int width, int height) {
        float edge = Math.max(34f, Math.min(width, height) * 0.035f);

        if (y >= height - edge) return EDGE_BOTTOM;
        if (x <= edge) return EDGE_LEFT;
        if (x >= width - edge) return EDGE_RIGHT;
        return EDGE_NONE;
    }

    private void performEdgeGesture(
            int displayId,
            int edge,
            float sx,
            float sy,
            float ex,
            float ey,
            long durationMs) {

        float dx = ex - sx;
        float dy = ey - sy;
        boolean handled = false;

        if (edge == EDGE_LEFT && dx > 80f && Math.abs(dx) > Math.abs(dy) * 0.7f) {
            handled = ShizukuShell.key(displayId, KeyEvent.KEYCODE_BACK);
            lastGesture = "Back (left edge)";
        } else if (edge == EDGE_RIGHT && dx < -80f && Math.abs(dx) > Math.abs(dy) * 0.7f) {
            handled = ShizukuShell.key(displayId, KeyEvent.KEYCODE_BACK);
            lastGesture = "Back (right edge)";
        } else if (edge == EDGE_BOTTOM && dy < -85f) {
            if (durationMs >= 650L) {
                handled = ShizukuShell.key(displayId, KeyEvent.KEYCODE_APP_SWITCH);
                lastGesture = "Recents (bottom hold)";
            } else {
                handled = ShizukuShell.key(displayId, KeyEvent.KEYCODE_HOME);
                lastGesture = "Home (bottom swipe)";
            }
        }

        if (!handled) {
            ShizukuShell.tap(displayId, ex, ey);
            lastGesture = "Edge tap";
        }
    }

    private boolean isDevicePresent(UsbManager um, UsbDevice target) {
        UsbDevice found = um.getDeviceList().get(target.getDeviceName());
        return found != null
                && found.getVendorId() == target.getVendorId()
                && found.getProductId() == target.getProductId();
    }

    private boolean isDisplayPresent(int displayId) {
        DisplayManager dm = (DisplayManager)getSystemService(DISPLAY_SERVICE);
        return dm.getDisplay(displayId) != null;
    }

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static float clampPx(float v, int limit) {
        if (v < 0f) return 0f;
        float max = Math.max(0, limit - 1);
        if (v > max) return max;
        return v;
    }

    private UsbDevice findDevice(UsbManager um) {
        UsbDevice any3m = null;

        for (UsbDevice d : um.getDeviceList().values()) {
            if (d.getVendorId() == 0x0596 && d.getProductId() == 0x0300) {
                return d;
            }
            if (d.getVendorId() == 0x0596 && any3m == null) {
                any3m = d;
            }
        }
        return any3m;
    }

    private Pair findInput(UsbDevice d) {
        Pair fallback = null;

        for (int a = 0; a < d.getInterfaceCount(); a++) {
            UsbInterface ui = d.getInterface(a);

            for (int b = 0; b < ui.getEndpointCount(); b++) {
                UsbEndpoint e = ui.getEndpoint(b);

                if (e.getDirection() != UsbConstants.USB_DIR_IN) continue;

                Pair p = new Pair(ui, e);

                if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) {
                    return p;
                }
                if (fallback == null
                        && e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    fallback = p;
                }
            }
        }
        return fallback;
    }

    private Display externalDisplay() {
        DisplayManager dm = (DisplayManager)getSystemService(DISPLAY_SERVICE);

        Display[] pres =
                dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (pres.length > 0) return pres[0];

        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY) return d;
        }
        return null;
    }

    private void initEx2(UsbDeviceConnection c) throws Exception {
        byte[] fw = new byte[16];
        c.controlTransfer(0xC0, 10, 0, 0, fw, fw.length, 1000);

        if (c.controlTransfer(0x40, 7, 1, 0, null, 0, 1000) < 0) {
            throw new Exception("3M reset failed");
        }

        Thread.sleep(150);

        int r = -1;
        for (int x = 0; x < 3 && r < 0; x++) {
            r = c.controlTransfer(0x40, 1, 1, 1, null, 0, 1000);
            if (r < 0) Thread.sleep(25);
        }

        if (r < 0) throw new Exception("3M async mode failed");
    }

    private synchronized void releaseUsb() {
        try {
            if (conn != null && intf != null) {
                conn.releaseInterface(intf);
            }
        } catch (Exception ignored) {}

        try {
            if (conn != null) conn.close();
        } catch (Exception ignored) {}

        conn = null;
        intf = null;
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
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
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE))
                .notify(77, note(s));
    }

    private static final class Pair {
        final UsbInterface i;
        final UsbEndpoint e;

        Pair(UsbInterface i, UsbEndpoint e) {
            this.i = i;
            this.e = e;
        }
    }
}
