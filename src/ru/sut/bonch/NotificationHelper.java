package ru.sut.bonch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class NotificationHelper {
    private static final String CHANNEL_BG = "bonch_mod_service_010401";
    private static final String CHANNEL_ALERT = "bonch_mod_alert_010401";
    public static final int FG_NOTIFICATION_ID = 2015;

    public static void createChannels(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            NotificationChannel bgChannel = new NotificationChannel(
                CHANNEL_BG,
                "Фоновая служба автоотметки",
                NotificationManager.IMPORTANCE_LOW
            );
            bgChannel.setShowBadge(false);
            bgChannel.enableVibration(false);
            bgChannel.setSound(null, null);
            nm.createNotificationChannel(bgChannel);

            NotificationChannel alertChannel = new NotificationChannel(
                CHANNEL_ALERT,
                "Уведомления (Отметки, Дневник и Пары)",
                NotificationManager.IMPORTANCE_HIGH
            );
            nm.createNotificationChannel(alertChannel);
        }
    }

    public static Notification buildForegroundNotification(Context ctx, String content) {
        return buildForegroundNotification(ctx, "Pulse • Автоотметка", content);
    }

    public static Notification buildForegroundNotification(Context ctx, String title, String content) {
        int flags = Build.VERSION.SDK_INT >= 23
            ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
            : PendingIntent.FLAG_UPDATE_CURRENT;

        Intent openIntent = new Intent(ctx, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(ctx, 0, openIntent, flags);

        Intent stopIntent = new Intent(ctx, BonchBackgroundDaemon.class);
        stopIntent.putExtra("action", "stop");
        PendingIntent stopPending = PendingIntent.getService(ctx, 1, stopIntent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(ctx, CHANNEL_BG)
            : new Notification.Builder(ctx);

        String safeTitle = (title != null && !title.trim().isEmpty()) ? title : "Pulse • Автоотметка";
        String safeContent = content != null ? content : "";

        builder.setContentTitle(safeTitle)
               .setContentText(safeContent)
               .setStyle(new Notification.BigTextStyle().bigText(safeContent))
               .setSmallIcon(R.drawable.ic_notif_pulse)
               .setContentIntent(openPending)
               .setOngoing(true)
               .setShowWhen(false);

        if (Build.VERSION.SDK_INT >= 21) {
            builder.setCategory(Notification.CATEGORY_SERVICE)
                   .setVisibility(Notification.VISIBILITY_PUBLIC);
        }

        Notification.Action stopAction = new Notification.Action.Builder(
            0, "ОТКЛЮЧИТЬСЯ", stopPending
        ).build();
        builder.addAction(stopAction);

        return builder.build();
    }

    public static void updateForeground(Context ctx, String content) {
        updateForeground(ctx, "Pulse • Автоотметка", content);
    }

    public static void updateForeground(Context ctx, String title, String content) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(FG_NOTIFICATION_ID, buildForegroundNotification(ctx, title, content));
        }
    }

    public static void sendAlert(Context ctx, String title, String message) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(ctx, CHANNEL_ALERT)
                    : new Notification.Builder(ctx);
                builder.setContentTitle(title)
                       .setContentText(message)
                       .setStyle(new Notification.BigTextStyle().bigText(message))
                       .setSmallIcon(R.drawable.ic_notif_pulse)
                       .setAutoCancel(true);
                nm.notify((int) (System.currentTimeMillis() % 100000), builder.build());
            }
        } catch (Exception ignored) {}
    }
}
