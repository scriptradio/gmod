package com.greg.dexmicrotouch;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

public final class ShellInputUserService extends IShellInputService.Stub {
    private Object inputManager;
    private Method injectInputEvent;
    private Method setDisplayId;
    private long downTime;

    public ShellInputUserService() {
        init();
    }

    public ShellInputUserService(Context context) {
        init();
    }

    private void init() {
        try {
            Class<?> im = Class.forName("android.hardware.input.InputManager");
            Method getInstance = im.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            inputManager = getInstance.invoke(null);

            injectInputEvent = im.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
            injectInputEvent.setAccessible(true);

            setDisplayId = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
            setDisplayId.setAccessible(true);
        } catch (Throwable t) {
            throw new RuntimeException("Input injector init failed", t);
        }
    }

    @Override
    public int getUid() {
        return Process.myUid();
    }

    @Override
    public synchronized boolean inject(int displayId, int action, float x, float y) {
        try {
            long now = SystemClock.uptimeMillis();
            if (action == MotionEvent.ACTION_DOWN || downTime == 0L) {
                downTime = now;
            }

            MotionEvent event = MotionEvent.obtain(
                    downTime,
                    now,
                    action,
                    x,
                    y,
                    0
            );
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            setDisplayId.invoke(event, displayId);

            Object result = injectInputEvent.invoke(inputManager, event, 0);
            event.recycle();

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                downTime = 0L;
            }

            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
