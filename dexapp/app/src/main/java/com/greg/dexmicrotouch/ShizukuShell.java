package com.greg.dexmicrotouch;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.view.MotionEvent;

import rikka.shizuku.Shizuku;

public final class ShizukuShell {
    public static volatile String status = "Not initialized";
    public static volatile long injected = 0;
    public static volatile long failed = 0;

    private static Context app;
    private static volatile IShellInputService service;
    private static boolean initialized;
    private static boolean binding;

    private static final Shizuku.OnBinderReceivedListener BINDER_RECEIVED = () -> {
        status = "Shizuku running";
        if (hasPermission()) bind();
    };

    private static final Shizuku.OnBinderDeadListener BINDER_DEAD = () -> {
        service = null;
        binding = false;
        status = "Shizuku stopped";
    };

    private static final Shizuku.OnRequestPermissionResultListener PERMISSION_RESULT =
            (requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    status = "Shizuku permission granted";
                    bind();
                } else {
                    status = "Shizuku permission denied";
                }
            };

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            service = IShellInputService.Stub.asInterface(binder);
            try {
                status = "Shell injector ready (UID " + service.getUid() + ")";
            } catch (Throwable t) {
                status = "Shell injector connected";
            }
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            binding = false;
            service = null;
            status = "Shell injector disconnected";
        }
    };

    private ShizukuShell() {}

    public static synchronized void init(Context context) {
        app = context.getApplicationContext();
        if (initialized) return;
        initialized = true;
        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED);
        Shizuku.addBinderDeadListener(BINDER_DEAD);
        Shizuku.addRequestPermissionResultListener(PERMISSION_RESULT);

        if (Shizuku.pingBinder()) {
            status = hasPermission() ? "Shizuku ready" : "Shizuku permission required";
            if (hasPermission()) bind();
        } else {
            status = "Install/start Shizuku";
        }
    }

    public static boolean isRunning() {
        try { return Shizuku.pingBinder(); }
        catch (Throwable t) { return false; }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.pingBinder()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                status = "Shizuku is not running";
                return;
            }
            if (hasPermission()) {
                status = "Shizuku permission already granted";
                bind();
                return;
            }
            status = "Waiting for Shizuku permission";
            Shizuku.requestPermission(4404);
        } catch (Throwable t) {
            status = "Shizuku error: " + t.getClass().getSimpleName();
        }
    }

    public static synchronized void bind() {
        if (app == null || service != null || binding) return;
        try {
            if (!hasPermission()) return;
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(app, ShellInputUserService.class))
                    .processNameSuffix("dexinput")
                    .debuggable(false)
                    .daemon(false)
                    .tag("dexmicrotouch-input")
                    .version(5);
            binding = true;
            status = "Starting shell injector...";
            Shizuku.bindUserService(args, CONNECTION);
        } catch (Throwable t) {
            binding = false;
            status = "Could not start shell injector: " + t.getClass().getSimpleName();
        }
    }

    public static boolean ready() { return service != null; }

    public static boolean inject(int displayId, int action, float x, float y) {
        IShellInputService s = service;
        if (s == null) return false;
        try {
            boolean ok = s.inject(displayId, action, x, y);
            if (ok) injected++; else failed++;
            return ok;
        } catch (Throwable t) {
            failed++;
            service = null;
            binding = false;
            status = "Shell injector lost";
            return false;
        }
    }

    public static boolean resetTouch(int displayId, float x, float y) {
        IShellInputService s = service;
        if (s == null) return false;
        try {
            return s.resetTouch(displayId, x, y);
        } catch (Throwable t) {
            service = null;
            binding = false;
            return false;
        }
    }

    public static boolean tap(int displayId, float x, float y) {
        resetTouch(displayId, x, y);
        boolean a = inject(displayId, MotionEvent.ACTION_DOWN, x, y);
        try { Thread.sleep(35); } catch (InterruptedException ignored) {}
        boolean b = inject(displayId, MotionEvent.ACTION_UP, x, y);
        return a && b;
    }

    public static boolean key(int displayId, int keyCode) {
        IShellInputService s = service;
        if (s == null) return false;
        try {
            boolean ok = s.key(displayId, keyCode);
            if (ok) injected++; else failed++;
            return ok;
        } catch (Throwable t) {
            failed++;
            service = null;
            binding = false;
            status = "Shell injector lost";
            return false;
        }
    }
}
