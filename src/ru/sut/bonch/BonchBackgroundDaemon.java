package ru.sut.bonch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONObject;

public class BonchBackgroundDaemon extends Service {
    private AppStorage storage;
    private SutApiClient apiClient;
    private ScheduleManager scheduleManager;
    private DiaryMonitor diaryMonitor;

    private PowerManager.WakeLock wakeLock;
    private Thread workerThread;

    private volatile boolean active = false;
    private volatile boolean scanningSemester = false;
    private volatile String scanStatusText = "";
    private volatile String statusText = "Отключено";
    private volatile long serviceStartMillis = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        storage = new AppStorage(this);
        apiClient = new SutApiClient();
        scheduleManager = new ScheduleManager();
        diaryMonitor = new DiaryMonitor();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BonchDaemon::ModularWakeLock");
        }
        NotificationHelper.createChannels(this);
        storage.loadLogsFromDisk();
        scheduleManager.loadCachedFromStorage(storage);
    }

    public void onScanProgress(boolean scanning, String msg) {
        this.scanningSemester = scanning;
        this.scanStatusText = msg;
        flushState();
    }

    private void flushState() {
        storage.saveRuntimeState(
            active,
            statusText,
            scanningSemester,
            scanStatusText,
            serviceStartMillis,
            scheduleManager.getTodayDayIdx()
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        JSONObject cfg = storage.getUserConfig();
        String action = intent != null ? intent.getStringExtra("action") : null;
        if (action == null) {
            action = storage.isDaemonEnabled() ? "start" : "stop";
        }

        if ("stop".equals(action)) {
            storage.setDaemonEnabled(false);
            stopDaemon();
            return START_NOT_STICKY;
        }

        if ("refresh_rules".equals(action)) {
            scheduleManager.syncTodayRealStartTimes(storage);
            flushState();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        if ("test_notif".equals(action)) {
            NotificationHelper.sendAlert(
                this,
                "🔔 Тест уведомлений Bonch AutoCheck",
                "Модульная архитектура v" + AppStorage.APP_VERSION + " работает отлично!"
            );
            storage.addLog("Отправлено тестовое уведомление.");
            flushState();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        final String login = cfg.optString("login", "").trim();
        final String pass = cfg.optString("password", "").trim();
        final String mode = cfg.optString("mode", "full_lesson");

        if ("check_diary_now".equals(action)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (login.isEmpty() || pass.isEmpty()) {
                        storage.addLog("Введите логин и пароль для проверки Дневника!");
                        flushState();
                        return;
                    }
                    storage.addLog("📚 Проверка раздела «Учеба -> Дневник»...");
                    flushState();
                    diaryMonitor.pollDiary(BonchBackgroundDaemon.this, apiClient, storage, login, pass, true);
                    flushState();
                }
            }).start();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        if ("scan_semester".equals(action)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (login.isEmpty() || pass.isEmpty()) {
                        storage.addLog("Введите логин и пароль для анализа семестра!");
                        flushState();
                        return;
                    }
                    if (scanningSemester) return;
                    scheduleManager.scanFullSemester(apiClient, storage, BonchBackgroundDaemon.this, login, pass);
                    flushState();
                }
            }).start();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        if ("check_once".equals(action)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (login.isEmpty() || pass.isEmpty()) {
                        storage.addLog("Сначала введите логин и пароль!");
                        flushState();
                        return;
                    }
                    storage.addLog("Проверка текущей недели...");
                    flushState();
                    String[] win = scheduleManager.getCurrentWindowState(mode, storage);
                    scheduleManager.pollCurrentWeekAndCheckIn(
                        BonchBackgroundDaemon.this, apiClient, storage, login, pass, win != null ? win[0] : null
                    );
                    if (storage.isNotifyDiaryEnabled()) {
                        diaryMonitor.pollDiary(BonchBackgroundDaemon.this, apiClient, storage, login, pass, false);
                    }
                    flushState();
                    if (scheduleManager.needsSemesterScan() && !scanningSemester) {
                        scheduleManager.scanFullSemester(apiClient, storage, BonchBackgroundDaemon.this, login, pass);
                        flushState();
                    }
                }
            }).start();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        if (login.isEmpty() || pass.isEmpty()) {
            statusText = "Введите логин и пароль от lk.sut.ru!";
            storage.addLog(statusText);
            storage.setDaemonEnabled(false);
            flushState();
            stopSelf();
            return START_NOT_STICKY;
        }

        storage.setDaemonEnabled(true);
        if (serviceStartMillis == 0L) {
            serviceStartMillis = System.currentTimeMillis();
        }

        startForeground(
            NotificationHelper.FG_NOTIFICATION_ID,
            NotificationHelper.buildForegroundNotification(this, "Подключено • Умный режим")
        );
        scheduleWatchdogAlarm();
        apiClient.checkCredentialsChanged(login, pass);

        if (!active || workerThread == null || !workerThread.isAlive()) {
            startWorkerLoop();
        }
        flushState();
        return START_STICKY;
    }

    private void scheduleWatchdogAlarm() {
        try {
            Intent restartIntent = new Intent(getApplicationContext(), BootReceiver.class);
            restartIntent.setAction("ru.sut.bonch.WATCHDOG_PING");
            int pFlags = Build.VERSION.SDK_INT >= 23
                ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(getApplicationContext(), 99, restartIntent, pFlags);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                long triggerAt = SystemClock.elapsedRealtime() + 25000L;
                if (Build.VERSION.SDK_INT >= 23) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (storage.isDaemonEnabled()) {
            storage.addLog("Окно закрыто — фоновый демон продолжает работу.");
            flushState();
            scheduleWatchdogAlarm();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (storage.isDaemonEnabled()) {
            scheduleWatchdogAlarm();
        }
        super.onDestroy();
    }

    private void startWorkerLoop() {
        active = true;
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                storage.addLog("Модульная служба v" + AppStorage.APP_VERSION + " запущена.");
                flushState();

                while (active) {
                    if (!storage.isDaemonEnabled()) break;
                    scheduleWatchdogAlarm();

                    JSONObject cfg = storage.getUserConfig();
                    final String login = cfg.optString("login", "").trim();
                    final String password = cfg.optString("password", "").trim();
                    String intervalStr = cfg.optString("interval", "1");
                    String mode = cfg.optString("mode", "full_lesson");

                    double intervalMin = 1.0;
                    try {
                        intervalMin = Double.parseDouble(intervalStr);
                    } catch (Exception ignored) {}

                    scheduleManager.checkNewDayReset(storage);

                    if (!apiClient.isAuthenticated()) {
                        updateStatus("Подключение к lk.sut.ru...");
                        if (!apiClient.authenticate(login, password, storage)) {
                            updateStatus("Ошибка входа (проверьте логин/пароль)");
                            sleepQuietly(20000L);
                            continue;
                        }
                        String[] initWin = scheduleManager.getCurrentWindowState(mode, storage);
                        scheduleManager.pollCurrentWeekAndCheckIn(
                            BonchBackgroundDaemon.this, apiClient, storage, login, password, initWin != null ? initWin[0] : null
                        );
                        if (storage.isNotifyDiaryEnabled()) {
                            diaryMonitor.pollDiary(BonchBackgroundDaemon.this, apiClient, storage, login, password, false);
                        }
                        flushState();
                        if (scheduleManager.needsSemesterScan() && !scanningSemester) {
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    scheduleManager.scanFullSemester(apiClient, storage, BonchBackgroundDaemon.this, login, password);
                                }
                            }).start();
                        }
                    }

                    scheduleManager.checkAndNotifySkippedLessonsToday(BonchBackgroundDaemon.this, storage);

                    if (storage.isNotifyDiaryEnabled() && diaryMonitor.isTimeForPeriodicPoll()) {
                        diaryMonitor.pollDiary(BonchBackgroundDaemon.this, apiClient, storage, login, password, false);
                        flushState();
                    }

                    String[] win = scheduleManager.getCurrentWindowState(mode, storage);

                    if (win == null) {
                        String nextLesson = scheduleManager.findNextUnmarkedLessonTime(storage);
                        if (nextLesson != null) {
                            updateStatus("Подключено • Ждем пару в " + nextLesson);
                        } else {
                            updateStatus("Подключено • На сегодня активных пар больше нет");
                        }
                        sleepQuietly(25000L);
                        continue;
                    }

                    String winStart = win[0];
                    String winLabel = win[1];

                    if (scheduleManager.completedWindowsToday.contains(winStart)) {
                        String tMarked = scheduleManager.checkInTimeByStart.get(winStart);
                        String nextLesson = scheduleManager.findNextLessonAfter(winStart, storage);
                        String waitText = nextLesson != null ? ("Спим до пары в " + nextLesson) : "Пары на сегодня завершены";
                        updateStatus("✅ Отмечен (" + (tMarked != null ? tMarked : "ОК") + ") • " + waitText);
                        sleepQuietly(25000L);
                        continue;
                    }

                    updateStatus("Ловим кнопку • " + winLabel);
                    scheduleManager.pollCurrentWeekAndCheckIn(
                        BonchBackgroundDaemon.this, apiClient, storage, login, password, winStart
                    );

                    if (scheduleManager.completedWindowsToday.contains(winStart)) {
                        String tMarked = scheduleManager.checkInTimeByStart.get(winStart);
                        String nextLesson = scheduleManager.findNextLessonAfter(winStart, storage);
                        String waitText = nextLesson != null ? ("Спим до пары в " + nextLesson) : "Пары на сегодня завершены";
                        updateStatus("✅ Отмечен (" + (tMarked != null ? tMarked : "ОК") + ") • " + waitText);
                        sleepQuietly(25000L);
                    } else {
                        sleepQuietly(Math.max(15000L, (long) (intervalMin * 60000L)));
                    }
                }
            }
        });
        workerThread.start();
    }

    private void stopDaemon() {
        active = false;
        serviceStartMillis = 0L;
        statusText = "Отключено";
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (workerThread != null) {
            workerThread.interrupt();
        }
        storage.addLog("Автоотметка отключена.");
        flushState();
        stopForeground(true);
        stopSelf();
    }

    private void updateStatus(String newStatus) {
        statusText = newStatus;
        flushState();
        if (active) {
            NotificationHelper.updateForeground(this, newStatus);
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
