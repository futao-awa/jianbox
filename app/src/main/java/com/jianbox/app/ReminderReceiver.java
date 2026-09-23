package com.jianbox.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class ReminderReceiver extends BroadcastReceiver {
    private static final String ACTION_FIRE = "com.jianbox.reminder.FIRE";
    private static final String ACTION_KNOW = "com.jianbox.reminder.KNOW";
    private static final String ACTION_SNOOZE = "com.jianbox.reminder.SNOOZE";
    private static final String ACTION_DONE = "com.jianbox.reminder.DONE";
    private static final String EXTRA_ID = "reminder_id";

    @Override public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra(EXTRA_ID);
        if (id == null) return;
        JianData data = new JianData(context);
        JianData.Reminder reminder = data.findReminder(id);
        if (reminder == null) return;
        String action = intent.getAction();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (ACTION_DONE.equals(action)) {
            reminder.done = true; data.updateReminder(reminder); cancel(context, reminder);
            data.pendingReminder(false); nm.cancel(notificationId(reminder)); signal(context, false); return;
        }
        if (ACTION_SNOOZE.equals(action)) {
            reminder.time = System.currentTimeMillis() + 10 * 60_000L; data.updateReminder(reminder); schedule(context, reminder);
            data.pendingReminder(false); nm.cancel(notificationId(reminder)); signal(context, false); return;
        }
        if (ACTION_KNOW.equals(action)) {
            if ("once".equals(reminder.repeat) || (reminder.repeatCount > 0 && reminder.firedCount >= reminder.repeatCount)) { reminder.done = true; data.updateReminder(reminder); }
            data.pendingReminder(false); nm.cancel(notificationId(reminder)); signal(context, false); return;
        }

        boolean useBubble = !"notification".equals(reminder.mode);
        boolean useNotification = !"bubble".equals(reminder.mode);
        data.pendingReminder(useBubble);
        if (useBubble) signal(context, true);
        if (useNotification) notifyReminder(context, reminder);
        reminder.firedCount++;
        if ("hourly".equals(reminder.repeat) || "daily".equals(reminder.repeat)) {
            boolean continueRepeating = reminder.repeatCount == 0 || reminder.firedCount < reminder.repeatCount;
            if (continueRepeating) {
                long step = "hourly".equals(reminder.repeat) ? 60 * 60_000L : 24 * 60 * 60_000L;
                do { reminder.time += step; } while (reminder.time <= System.currentTimeMillis());
                data.updateReminder(reminder); schedule(context, reminder);
            } else data.updateReminder(reminder);
        } else {
            data.updateReminder(reminder);
        }
    }

    static void schedule(Context context, JianData.Reminder reminder) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pending(context, ACTION_FIRE, reminder, PendingIntent.FLAG_UPDATE_CURRENT);
        long when = Math.max(System.currentTimeMillis() + 2_000L, reminder.time);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    static void cancel(Context context, JianData.Reminder reminder) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent existing = pending(context, ACTION_FIRE, reminder, PendingIntent.FLAG_NO_CREATE);
        if (existing != null) am.cancel(existing);
    }

    private static PendingIntent pending(Context c, String action, JianData.Reminder r, int extraFlags) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(action).putExtra(EXTRA_ID, r.id);
        return PendingIntent.getBroadcast(c, r.id.hashCode() ^ action.hashCode(), i, extraFlags | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void notifyReminder(Context c, JianData.Reminder r) {
        NotificationManager manager = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = "jianbox_reminder_" + (r.sound ? "sound" : "quiet") + (r.vibrate ? "_vibrate" : "_still");
        NotificationChannel channel = new NotificationChannel(channelId, "简盒提醒 · " + (r.sound ? "声音" : "静音") + (r.vibrate ? " + 震动" : ""), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("定时备忘与提醒");
        channel.enableVibration(r.vibrate);
        if (!r.sound) channel.setSound(null, null);
        else {
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            channel.setSound(sound, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build());
        }
        manager.createNotificationChannel(channel);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(c, r.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(c, channelId);
        b.setSmallIcon(R.drawable.ic_launcher).setContentTitle("简盒提醒").setContentText(r.title)
                .setStyle(new Notification.BigTextStyle().bigText(r.title)).setContentIntent(content)
                .setAutoCancel(false).setCategory(Notification.CATEGORY_REMINDER).setPriority(Notification.PRIORITY_HIGH)
                .addAction(new Notification.Action.Builder(null, "知道了", pending(c, ACTION_KNOW, r, PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .addAction(new Notification.Action.Builder(null, "稍后10分钟", pending(c, ACTION_SNOOZE, r, PendingIntent.FLAG_UPDATE_CURRENT)).build())
                .addAction(new Notification.Action.Builder(null, "标记完成", pending(c, ACTION_DONE, r, PendingIntent.FLAG_UPDATE_CURRENT)).build());
        manager.notify(notificationId(r), b.build());
    }

    private static int notificationId(JianData.Reminder r) { return 9000 + Math.abs(r.id.hashCode() % 100000); }

    private static void signal(Context context, boolean alert) {
        JianData data = new JianData(context);
        if (!data.serviceEnabled() || !android.provider.Settings.canDrawOverlays(context)) return;
        Intent i = new Intent(context, OverlayService.class).setAction(alert ? OverlayService.ACTION_ALERT : OverlayService.ACTION_REFRESH);
        try {
            context.startForegroundService(i);
        } catch (Exception ignored) {}
    }
}
