package com.greg.dexmicrotouch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class UsbAttachActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean auto = getSharedPreferences("bridge", MODE_PRIVATE)
                .getBoolean("auto", true);

        if (auto) {
            try {
                startForegroundService(new Intent(this, TouchBridgeService.class));
            } catch (Throwable ignored) {}
        }
        finish();
    }
}
