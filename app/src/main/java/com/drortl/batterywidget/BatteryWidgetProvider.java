package com.drortl.batterywidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

/**
 * The home screen widget. All work is done in {@link WidgetUpdater}; this
 * class only routes the broadcasts to it.
 *
 * Broadcasts it gets:
 *  - APPWIDGET_UPDATE: widget added, phone booted, or launcher asked.
 *  - ACTION_TAP:       the user tapped the widget.
 *  - ACTION_TICK:      the next scheduled (non-wakeup) refresh.
 *  - MY_PACKAGE_REPLACED: app was updated; pending alarms were dropped.
 */
public class BatteryWidgetProvider extends AppWidgetProvider {

    static final String ACTION_TAP = "com.drortl.batterywidget.action.TAP";
    static final String ACTION_TICK = "com.drortl.batterywidget.action.TICK";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_TAP.equals(action)
                || ACTION_TICK.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            WidgetUpdater.refreshAll(context);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        WidgetUpdater.refreshAll(context);
    }

    @Override
    public void onDisabled(Context context) {
        // The last widget was removed: leave nothing scheduled behind.
        WidgetUpdater.stop(context);
    }
}
