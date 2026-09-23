package com.jianbox.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        JianData data = new JianData(context);
        for (JianData.Reminder reminder : data.reminders()) {
            if (!reminder.done && reminder.time > System.currentTimeMillis()) ReminderReceiver.schedule(context, reminder);
        }
        if (data.serviceEnabled() && !UpdateGuard.hasForceGate(context) && Settings.canDrawOverlays(context)) {
            Intent service = new Intent(context, OverlayService.class).setAction(OverlayService.ACTION_START);
            try {
                context.startForegroundService(service);
            } catch (Exception ignored) {}
        }
    }
}
