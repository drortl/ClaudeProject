package com.drortl.batterywidget;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/** Battery level and charger state, read from the sticky ACTION_BATTERY_CHANGED intent. */
final class BatteryState {

    /** Level in percent (0..100), or -1 when the system did not report it. */
    final int percent;
    /** True when a charger (AC, USB, wireless or dock) is connected. */
    final boolean plugged;

    private BatteryState(int percent, boolean plugged) {
        this.percent = percent;
        this.plugged = plugged;
    }

    /**
     * Reads the last battery broadcast that the system keeps ("sticky").
     * Passing a null receiver registers nothing, so this is a single cheap
     * binder call and is allowed from inside a BroadcastReceiver.
     */
    static BatteryState read(Context context) {
        Intent intent = context.getApplicationContext().registerReceiver(
                null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return fromIntent(intent);
    }

    static BatteryState fromIntent(Intent intent) {
        if (intent == null) {
            return new BatteryState(-1, false);
        }
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int percent = (level >= 0 && scale > 0) ? Math.min(100, level * 100 / scale) : -1;
        boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        return new BatteryState(percent, plugged);
    }
}
