package com.greg.dexmicrotouch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

public final class TouchAccessibilityService extends AccessibilityService {
    private static volatile TouchAccessibilityService instance;
    public static volatile long injected = 0;

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean wantDown;
    private boolean inFlight;
    private boolean active;
    private int displayId;
    private final PointF wanted = new PointF();
    private final PointF end = new PointF();
    private GestureDescription.StrokeDescription stroke;

    public static boolean ready() { return instance != null; }

    public static void inject(int display, boolean down, float x, float y) {
        TouchAccessibilityService s = instance;
        if (s != null) s.h.post(() -> s.accept(display, down, x, y));
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() { reset(); }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        reset();
        super.onDestroy();
    }

    private void accept(int display, boolean down, float x, float y) {
        if (active && displayId != display) {
            wantDown = false;
            pump();
            return;
        }
        displayId = display;
        wanted.set(x, y);
        wantDown = down;
        pump();
    }

    private void pump() {
        if (inFlight) return;
        if (!active) {
            if (wantDown) startStroke();
            return;
        }
        continueStroke(!wantDown);
    }

    private void startStroke() {
        Path p = new Path();
        p.moveTo(wanted.x, wanted.y);
        stroke = new GestureDescription.StrokeDescription(p, 0, 24, true);
        end.set(wanted.x, wanted.y);
        active = true;
        dispatch(stroke, false);
    }

    private void continueStroke(boolean finish) {
        if (stroke == null) {
            reset();
            if (wantDown) startStroke();
            return;
        }
        Path p = new Path();
        p.moveTo(end.x, end.y);
        p.lineTo(wanted.x, wanted.y);
        try {
            stroke = stroke.continueStroke(p, 0, 24, !finish);
            end.set(wanted.x, wanted.y);
            dispatch(stroke, finish);
        } catch (Throwable t) {
            reset();
            if (wantDown) h.postDelayed(this::startStroke, 20);
        }
    }

    private void dispatch(GestureDescription.StrokeDescription s, boolean last) {
        GestureDescription g = new GestureDescription.Builder()
                .setDisplayId(displayId)
                .addStroke(s)
                .build();
        inFlight = true;
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gd) {
                inFlight = false;
                injected++;
                if (last) {
                    stroke = null;
                    active = false;
                    if (wantDown) h.post(TouchAccessibilityService.this::startStroke);
                } else {
                    h.postDelayed(TouchAccessibilityService.this::pump, 4);
                }
            }
            @Override public void onCancelled(GestureDescription gd) {
                inFlight = false;
                stroke = null;
                active = false;
                if (wantDown) h.postDelayed(TouchAccessibilityService.this::startStroke, 20);
            }
        }, h);
        if (!ok) {
            inFlight = false;
            stroke = null;
            active = false;
        }
    }

    private void reset() {
        wantDown = false;
        inFlight = false;
        active = false;
        stroke = null;
    }
}
