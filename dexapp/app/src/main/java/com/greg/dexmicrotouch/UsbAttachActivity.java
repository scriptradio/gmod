package com.greg.dexmicrotouch;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;

public final class UsbAttachActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean auto = getSharedPreferences("bridge", MODE_PRIVATE)
                .getBoolean("auto", true);

        UsbManager usb = (UsbManager)getSystemService(USB_SERVICE);
        UsbDevice d;
        if (Build.VERSION.SDK_INT >= 33) {
            d = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        } else {
            d = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }

        boolean correctDevice = d != null
                && d.getVendorId() == 0x0596
                && d.getProductId() == 0x0300;

        if (auto && correctDevice && usb.hasPermission(d)) {
            try {
                ShizukuShell.init(this);
                startForegroundService(new Intent(this, TouchBridgeService.class));
            } catch (Throwable ignored) {}
        }

        finish();
    }
}
