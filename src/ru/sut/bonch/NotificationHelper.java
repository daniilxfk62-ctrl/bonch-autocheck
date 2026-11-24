package ru.sut.bonch;

import android.app.AlarmManager;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

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
    public static final String ACTION_NOTE_REMINDER = "ru.sut.bonch.NOTE_REMINDER";
    private static final int NOTE_ALARM_REQ_CODE = 109;

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
    public static void rescheduleNoteAlarms(Context ctx, AppStorage storage) {
        processNoteAlarms(ctx, storage, false);
    }

    public static void fireDueNotesAndReschedule(Context ctx, AppStorage storage) {
        createChannels(ctx);
        processNoteAlarms(ctx, storage, true);
    }

    private static void processNoteAlarms(Context ctx, AppStorage storage, boolean fireDue) {
        try {
            JSONObject allNotes = storage.readJson(AppStorage.FILE_DAY_NOTES, "{}");
            JSONObject notified = storage.readJson(AppStorage.FILE_NOTE_NOTIFIED, "{}");
            long now = System.currentTimeMillis();
            long minNextTrigger = Long.MAX_VALUE;
            boolean notifiedChanged = false;

            Iterator<String> dates = allNotes.keys();
            while (dates.hasNext()) {
                String dateStr = dates.next();
                JSONArray list = allNotes.optJSONArray(dateStr);
                if (list == null) continue;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject note = list.optJSONObject(i);
                    if (note == null || !note.optBoolean("notify", false)) continue;
                    String id = note.optString("id", "");
                    if (id.isEmpty() || notified.optBoolean(id, false)) continue;

                    String timeMode = note.optString("timeMode", "morning");
                    String timeStart = note.optString("timeStart", "");
                    String timeEnd = note.optString("timeEnd", "");
                    long triggerAt = computeNoteTriggerMillis(dateStr, timeMode, timeStart);
                    if (triggerAt <= 0L) continue;

                    if (fireDue && triggerAt <= now + 30000L && triggerAt >= now - 12L * 3600000L) {
                        String timeLabel = "morning".equals(timeMode)
                            ? (timeStart.isEmpty() ? "08:00" : timeStart)
                            : ("range".equals(timeMode) && !timeStart.isEmpty()
                                ? (timeEnd.isEmpty() ? timeStart : (timeStart + "–" + timeEnd))
                                : dateStr);
                        String title = "📝 Заметка · " + timeLabel;
                        String body = note.optString("text", "");
                        sendAlert(ctx, title, body);
                        notified.put(id, true);
                        notifiedChanged = true;
                    } else if (triggerAt > now + 15000L && triggerAt < minNextTrigger) {
                        minNextTrigger = triggerAt;
                    }
                }
            }

            if (notifiedChanged) {
                storage.writeText(AppStorage.FILE_NOTE_NOTIFIED, notified.toString());
            }

            if (minNextTrigger < Long.MAX_VALUE) {
                scheduleNoteAlarmAt(ctx, minNextTrigger);
            } else {
                cancelNoteAlarm(ctx);
            }
        } catch (Exception ignored) {}
    }

    private static long computeNoteTriggerMillis(String dateStr, String timeMode, String timeStart) {
        if (dateStr == null || dateStr.trim().isEmpty()) return 0L;
        String hhmm = "08:00";
        if (timeStart != null && timeStart.matches("^\\d{1,2}:\\d{2}$")) {
            hhmm = timeStart.length() == 4 ? ("0" + timeStart) : timeStart;
        }
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US);
            sdf.setLenient(false);
            Date d = sdf.parse(dateStr.trim() + " " + hhmm);
            return d != null ? d.getTime() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static PendingIntent buildNoteAlarmPendingIntent(Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), BootReceiver.class);
        i.setAction(ACTION_NOTE_REMINDER);
        int flags = Build.VERSION.SDK_INT >= 23
            ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
            : PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getBroadcast(ctx.getApplicationContext(), NOTE_ALARM_REQ_CODE, i, flags);
    }

    private static void scheduleNoteAlarmAt(Context ctx, long triggerAtMillis) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = buildNoteAlarmPendingIntent(ctx);
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
                } catch (SecurityException se) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
                }
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            }
        } catch (Exception ignored) {}
    }

    private static void cancelNoteAlarm(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(buildNoteAlarmPendingIntent(ctx));
            }
        } catch (Exception ignored) {}
    }
}
