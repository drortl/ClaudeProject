package com.drortl.batterywidget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Info screen. Shows the live level while it is open (the battery receiver
 * is registered only between onStart and onStop), explains the widget, and
 * offers a button that asks the launcher to add the widget.
 */
public class MainActivity extends Activity {

    private TextView levelView;
    private TextView statusView;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            show(BatteryState.fromIntent(intent));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        levelView = findViewById(R.id.level);
        statusView = findViewById(R.id.status);

        Button addButton = findViewById(R.id.add_widget);
        addButton.setOnClickListener(v -> requestPinWidget());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // ACTION_BATTERY_CHANGED is a protected system broadcast, so no
        // export flag is needed. The sticky value arrives right away.
        Intent sticky = registerReceiver(batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        show(BatteryState.fromIntent(sticky));
    }

    @Override
    protected void onStop() {
        unregisterReceiver(batteryReceiver);
        // The user is going back to the home screen: make the widget current.
        WidgetUpdater.refreshAll(this);
        super.onStop();
    }

    private void show(BatteryState state) {
        levelView.setText(WidgetUpdater.formatLevel(state.percent));
        levelView.setTextColor(WidgetUpdater.textColor(this, state));
        levelView.setContentDescription(WidgetUpdater.describe(this, state));
        statusView.setText(state.plugged ? R.string.status_plugged : R.string.status_battery);
    }

    private void requestPinWidget() {
        AppWidgetManager manager = getSystemService(AppWidgetManager.class);
        if (manager != null && manager.isRequestPinAppWidgetSupported()) {
            manager.requestPinAppWidget(
                    new ComponentName(this, BatteryWidgetProvider.class), null, null);
        } else {
            Toast.makeText(this, R.string.add_widget_manual, Toast.LENGTH_LONG).show();
        }
    }
}
