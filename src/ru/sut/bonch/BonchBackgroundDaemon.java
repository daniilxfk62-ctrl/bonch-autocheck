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
    private volatile boolean singleCheckMode = false;
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
            singleCheckMode = false;
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
                "Уведомления работают отлично!"
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
                    flushState();
                    if (win == null && scheduleManager.needsSemesterScan() && !scanningSemester) {
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

        singleCheckMode = "check_single".equals(action);
        storage.setDaemonEnabled(true);
        if (serviceStartMillis == 0L) {
            serviceStartMillis = System.currentTimeMillis();
        }

        String fgTitle = singleCheckMode ? "⚡ Разовый режим (только 1 пара)" : "Подключено • Умный режим";
        startForeground(
            NotificationHelper.FG_NOTIFICATION_ID,
            NotificationHelper.buildForegroundNotification(this, fgTitle)
        );
        scheduleWatchdogAlarm(25000L);
        apiClient.checkCredentialsChanged(login, pass);

        if (!active || workerThread == null || !workerThread.isAlive()) {
            startWorkerLoop();
        }
        flushState();
        return START_STICKY;
    }

    private void scheduleWatchdogAlarm(long delayMs) {
        try {
            Intent restartIntent = new Intent(getApplicationContext(), BootReceiver.class);
            restartIntent.setAction("ru.sut.bonch.WATCHDOG_PING");
            int pFlags = Build.VERSION.SDK_INT >= 23
                ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getBroadcast(getApplicationContext(), 99, restartIntent, pFlags);
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                long triggerAt = SystemClock.elapsedRealtime() + delayMs;
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
            storage.addLog("Вкладка смахнута — фоновая служба продолжает работу.");
            flushState();
            scheduleWatchdogAlarm(2000L);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (storage.isDaemonEnabled()) {
            scheduleWatchdogAlarm(2000L);
        }
        super.onDestroy();
    }

    private void finishSingleCheckMode(String markedInfo) {
        singleCheckMode = false;
        storage.setDaemonEnabled(false);
        active = false;
        serviceStartMillis = 0L;
        statusText = "✅ Отмечен на 1 паре (" + markedInfo + ") • Отключено";
        storage.addLog("⚡ Разовая отметка выполнена (" + markedInfo + "). Автоотключение.");
        flushState();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopForeground(true);
        stopSelf();
    }

    private void startWorkerLoop() {
        active = true;
        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire();
        }

        workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                storage.addLog(singleCheckMode ? "⚡ Запуск разовой отметки (на 1 пару)..." : "Служба запущена.");
                flushState();

                while (active) {
                    if (!storage.isDaemonEnabled()) break;
                    scheduleWatchdogAlarm(25000L);

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
                            updateStatus("Повтор подключения к lk.sut.ru через 10 сек...");
                            sleepQuietly(10000L);
                            continue;
                        }
                        String[] initWin = scheduleManager.getCurrentWindowState(mode, storage);
                        scheduleManager.pollCurrentWeekAndCheckIn(
                            BonchBackgroundDaemon.this, apiClient, storage, login, password, initWin != null ? initWin[0] : null
                        );
                        flushState();
                        if (singleCheckMode && scheduleManager.lastPollMarkedAny) {
                            finishSingleCheckMode("успешно");
                            return;
                        }
                        if (initWin == null && scheduleManager.needsSemesterScan() && !scanningSemester) {
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    scheduleManager.scanFullSemester(apiClient, storage, BonchBackgroundDaemon.this, login, password);
                                }
                            }).start();
                        }
                    }

                    scheduleManager.checkAndNotifySkippedLessonsToday(BonchBackgroundDaemon.this, storage);

                    String[] win = scheduleManager.getCurrentWindowState(mode, storage);

                    // Если включен разовый режим на 1 пару — проверяем ЛК даже если окно пары еще не началось
                    if (singleCheckMode && win == null) {
                        updateStatus("⚡ Разовая отметка • Проверка открытой кнопки в ЛК...");
                        scheduleManager.pollCurrentWeekAndCheckIn(
                            BonchBackgroundDaemon.this, apiClient, storage, login, password, null
                        );
                        if (scheduleManager.lastPollMarkedAny) {
                            finishSingleCheckMode("ОК");
                            return;
                        }
                        String nextLesson = scheduleManager.findNextUnmarkedLessonTime(storage);
                        if (nextLesson != null) {
                            updateStatus("⚡ Разовый режим (1 пара) • Ждем пару в " + nextLesson);
                        } else {
                            updateStatus("⚡ Разовый режим (1 пара) • Ожидание кнопки в ЛК...");
                        }
                        sleepQuietly(Math.max(15000L, (long) (intervalMin * 60000L)));
                        continue;
                    }

                    boolean busyCatchingButton = (win != null && !scheduleManager.completedWindowsToday.contains(win[0]));
                    if (!busyCatchingButton && storage.isNotifyDiaryEnabled() && diaryMonitor.isTimeForPeriodicPoll()) {
                        diaryMonitor.pollDiary(BonchBackgroundDaemon.this, apiClient, storage, login, password, false);
                        flushState();
                    }

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
                        if (singleCheckMode) {
                            finishSingleCheckMode(tMarked != null ? tMarked : winStart);
                            return;
                        }
                        String nextLesson = scheduleManager.findNextLessonAfter(winStart, storage);
                        String waitText = nextLesson != null ? ("Спим до пары в " + nextLesson) : "Пары на сегодня завершены";
                        updateStatus("✅ Отмечен (" + (tMarked != null ? tMarked : "ОК") + ") • " + waitText);
                        sleepQuietly(25000L);
                        continue;
                    }

                    String prefix = singleCheckMode ? "⚡ [1 ПАРА] " : "";
                    if (scheduleManager.waitingTeacherToday.contains(winStart)) {
                        updateStatus(prefix + "⏳ Ждем начала от преподавателя • " + winLabel);
                    } else {
                        updateStatus(prefix + "Ловим кнопку • " + winLabel);
                    }

                    scheduleManager.pollCurrentWeekAndCheckIn(
                        BonchBackgroundDaemon.this, apiClient, storage, login, password, winStart
                    );

                    if (scheduleManager.lastPollMarkedAny || scheduleManager.completedWindowsToday.contains(winStart)) {
                        String tMarked = scheduleManager.checkInTimeByStart.get(winStart);
                        if (singleCheckMode) {
                            finishSingleCheckMode(tMarked != null ? tMarked : "ОК");
                            return;
                        }
                        String nextLesson = scheduleManager.findNextLessonAfter(winStart, storage);
                        String waitText = nextLesson != null ? ("Спим до пары в " + nextLesson) : "Пары на сегодня завершены";
                        updateStatus("✅ Отмечен (" + (tMarked != null ? tMarked : "ОК") + ") • " + waitText);
                        sleepQuietly(25000L);
                    } else {
                        if (scheduleManager.waitingTeacherToday.contains(winStart)) {
                            updateStatus(prefix + "⏳ Ждем начала от преподавателя • " + winLabel);
                        }
                        sleepQuietly(Math.max(15000L, (long) (intervalMin * 60000L)));
                    }
                }
            }
        });
        workerThread.start();
    }

    private void stopDaemon() {
        active = false;
        singleCheckMode = false;
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
