package com.phonemedic.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import java.util.Calendar;

/** Rappel hebdomadaire de nettoyage (dimanche 18 h), reprogrammé après un redémarrage. */
public class ReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL = "rappel";
    private static final String PREFS = "phonemedic";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (isEnabled(ctx)) schedule(ctx);
            return;
        }
        notifyNow(ctx);
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("reminder", false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putBoolean("reminder", on).apply();
        if (on) schedule(ctx); else cancel(ctx);
    }

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ctx, ReminderReceiver.class).setAction("com.phonemedic.app.REMINDER");
        return PendingIntent.getBroadcast(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void schedule(Context ctx) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
        c.set(Calendar.HOUR_OF_DAY, 18);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.WEEK_OF_YEAR, 1);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), AlarmManager.INTERVAL_DAY * 7, alarmIntent(ctx));
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(alarmIntent(ctx));
    }

    public static void notifyNow(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Rappel de nettoyage", NotificationManager.IMPORTANCE_DEFAULT));
        }
        String text = "Lance une analyse pour libérer de la place et garder ton téléphone rapide.";
        try {
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            long used = Math.round(100.0 * (st.getTotalBytes() - st.getAvailableBytes()) / st.getTotalBytes());
            text = "Stockage occupé à " + used + " %. " + (used >= 85 ? "Il est temps de faire du tri." : "Un petit nettoyage hebdomadaire ?");
        } catch (Exception ignored) {}
        Intent open = new Intent(ctx, MainActivity.class).putExtra("open", "v-opt").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(ctx, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CHANNEL) : new Notification.Builder(ctx);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Phone Medic")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true);
        try { nm.notify(1, b.build()); } catch (SecurityException ignored) {}
    }
}
