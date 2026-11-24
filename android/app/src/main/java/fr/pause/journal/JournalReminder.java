package fr.pause.journal;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

public final class JournalReminder extends BroadcastReceiver {
    static final String OPEN_TODAY = "fr.pause.journal.OPEN_TODAY";
    static final String OPEN_WEIGHT = "fr.pause.journal.OPEN_WEIGHT";
    private static final String ALARM = "fr.pause.journal.REMIND";
    private static final String CHANNEL = "journal_reminders";
    private static final int ID = 1301;

    record Settings(boolean enabled, int hour, int minute, boolean weight) {}

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("pause.reminder", Context.MODE_PRIVATE);
    }

    static Settings settings(Context context) {
        SharedPreferences values = preferences(context);
        return new Settings(values.getBoolean("enabled", false), values.getInt("hour", 20), values.getInt("minute", 0),
                values.getBoolean("weight", false));
    }

    static boolean canNotify(Context context) {
        return (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED)
                && context.getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    static void configure(Context context, boolean enabled, int hour, int minute, boolean weight) {
        preferences(context).edit().putBoolean("enabled", enabled).putInt("hour", hour).putInt("minute", minute)
                .putBoolean("weight", weight).apply();
        schedule(context);
    }

    static void ensureScheduled(Context context) {
        if (settings(context).enabled() && canNotify(context)
                && PendingIntent.getBroadcast(context, ID, alarmIntent(context),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE) == null) schedule(context);
    }

    static void schedule(Context context) {
        Settings settings = settings(context);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        Intent intent = alarmIntent(context);
        if (!settings.enabled() || !canNotify(context)) {
            PendingIntent existing = PendingIntent.getBroadcast(context, ID, intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (existing != null) {
                alarms.cancel(existing);
                existing.cancel();
            }
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        LocalTime time = LocalTime.of(settings.hour(), settings.minute());
        ZonedDateTime next = ZonedDateTime.of(now.toLocalDate(), time, now.getZone());
        if (!next.isAfter(now)) next = ZonedDateTime.of(now.toLocalDate().plusDays(1), time, now.getZone());
        intent.putExtra("day", next.toLocalDate().toString());
        PendingIntent pending = PendingIntent.getBroadcast(context, ID, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending);
    }

    private static Intent alarmIntent(Context context) {
        return new Intent(context, JournalReminder.class).setAction(ALARM);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ALARM.equals(intent.getAction())) {
            schedule(context);
            return;
        }
        String day = LocalDate.now().toString();
        try {
            SharedPreferences values = preferences(context);
            Settings settings = settings(context);
            if (settings.enabled() && canNotify(context) && day.equals(intent.getStringExtra("day"))
                    && !day.equals(values.getString("delivered", ""))) {
                Journal journal = new Journal(context);
                LocalDate date = LocalDate.parse(day);
                boolean journalMissing = journal.needsReminder(date);
                boolean weightMissing = settings.weight() && journal.weightAt(date) == null;
                if (!journalMissing && !weightMissing) return;
                notifyUser(context, journalMissing, weightMissing);
                values.edit().putString("delivered", day).apply();
            }
        } finally {
            schedule(context);
        }
    }

    private void notifyUser(Context context, boolean journal, boolean weight) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Rappel du journal", NotificationManager.IMPORTANCE_DEFAULT));
        Intent open = new Intent(context, MainActivity.class).setAction(journal ? OPEN_TODAY : OPEN_WEIGHT)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent link = PendingIntent.getActivity(context, ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_reminder)
                .setContentTitle("Pause")
                .setContentText(weight ? (journal ? "Pense à compléter ton journal et à noter ta pesée."
                        : "Pense à noter ta pesée.") : "Pense à compléter ton journal.")
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(link)
                .setAutoCancel(true)
                .build();
        manager.notify(ID, notification);
    }
}
