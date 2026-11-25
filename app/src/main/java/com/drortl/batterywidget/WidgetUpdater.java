package com.drortl.batterywidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.widget.RemoteViews;

/**
 * Draws the widget and plans the next refresh.
 *
 * Power rules (keep them when you change this file):
 *  1. Never wake the phone. The refresh alarm is ELAPSED_REALTIME, not
 *     ELAPSED_REALTIME_WAKEUP. If it comes due while the phone sleeps, the
 *     system delivers it the next time the phone wakes up (for example when
 *     the screen turns on), so the number is fresh when the user looks.
 *  2. No service, no wake lock, no updatePeriodMillis (that one wakes the
 *     phone). Each refresh is one short broadcast, then the process can die.
 *
 * Why there is no instant "charger plugged in" refresh:
 *  - ACTION_POWER_CONNECTED is not delivered to manifest receivers for apps
 *    that target Android 8+, and a process that stays alive to listen for it
 *    costs power.
 *  - A JobScheduler job with setRequiresCharging(true) was tried. Android
 *    counts the phone as "charging" for jobs only after the level rises and
 *    then battery_charged_delay_ms (15 min by default) passes, so it never
 *    fired sooner than the normal 15 min refresh. It was removed.
 */
final class WidgetUpdater {

    /** Refresh interval on battery. Battery drops slowly, so 15 min is enough. */
    static final long INTERVAL_ON_BATTERY_MS = 15 * 60 * 1000L;
    /** Refresh interval on the charger. Power is free then, so show progress. */
    static final long INTERVAL_PLUGGED_MS = 2 * 60 * 1000L;

    /** At or below this level (on battery) the number is shown in red. */
    static final int LOW_LEVEL_PERCENT = 15;

    private static final int REQUEST_TAP = 1;
    private static final int REQUEST_TICK = 2;

    private WidgetUpdater() {
    }

    /** Reads the battery, redraws every widget instance, and plans the next refresh. */
    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, BatteryWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(provider);
        if (ids == null || ids.length == 0) {
            stop(context);
            return;
        }

        BatteryState state = BatteryState.read(context);
        // One call updates all instances: they all show the same value.
        manager.updateAppWidget(provider, buildViews(context, state));
        scheduleNext(context, state.plugged);
    }

    /** Cancels the pending refresh alarm. */
    static void stop(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms != null) {
            alarms.cancel(tickIntent(context));
        }
    }

    /** "87%" with a smaller percent sign, so the digits get most of the space. */
    static CharSequence formatLevel(int percent) {
        if (percent < 0) {
            return "?";
        }
        String text = percent + "%";
        SpannableString span = new SpannableString(text);
        span.setSpan(new RelativeSizeSpan(0.45f), text.length() - 1, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    static int textColor(Context context, BatteryState state) {
        if (state.plugged) {
            return context.getColor(R.color.level_charging);
        }
        if (state.percent >= 0 && state.percent <= LOW_LEVEL_PERCENT) {
            return context.getColor(R.color.level_low);
        }
        return context.getColor(R.color.level_normal);
    }

    static String describe(Context context, BatteryState state) {
        if (state.percent < 0) {
            return context.getString(R.string.battery_unknown);
        }
        return context.getString(state.plugged
                ? R.string.battery_desc_plugged
                : R.string.battery_desc, state.percent);
    }

    private static RemoteViews buildViews(Context context, BatteryState state) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_battery);
        views.setTextViewText(R.id.level, formatLevel(state.percent));
        views.setTextColor(R.id.level, textColor(context, state));
        views.setContentDescription(android.R.id.background, describe(context, state));
        views.setOnClickPendingIntent(android.R.id.background, tapIntent(context));
        return views;
    }

    private static void scheduleNext(Context context, boolean plugged) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) {
            return;
        }
        long delay = plugged ? INTERVAL_PLUGGED_MS : INTERVAL_ON_BATTERY_MS;
        // Inexact and non-wakeup: the system may batch it with other work,
        // and it never turns the CPU on by itself. Replaces any pending tick.
        alarms.set(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + delay, tickIntent(context));
    }

    private static PendingIntent tapIntent(Context context) {
        return broadcast(context, BatteryWidgetProvider.ACTION_TAP, REQUEST_TAP);
    }

    private static PendingIntent tickIntent(Context context) {
        return broadcast(context, BatteryWidgetProvider.ACTION_TICK, REQUEST_TICK);
    }

    private static PendingIntent broadcast(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, BatteryWidgetProvider.class).setAction(action);
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
