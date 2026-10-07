package com.greg.dexmicrotouch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class AutoStartReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean auto = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
                .getBoolean("auto", true);
        if (!auto) return;

        try {
            context.startForegroundService(new Intent(context, TouchBridgeService.class));
        } catch (Throwable ignored) {}
    }
}
