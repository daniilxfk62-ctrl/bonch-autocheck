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
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

public class BonchBackgroundDaemon extends Service {
    public static final String FILE_AUTH_STATUS = "auth_status_v4.json";

    private AppStorage storage;
    private SutApiClient apiClient;
    private ScheduleManager scheduleManager;
    private DiaryMonitor diaryMonitor;
    private MessageManager messageManager;

    private PowerManager.WakeLock wakeLock;
    private Thread workerThread;
    private final AtomicBoolean syncInProgress = new AtomicBoolean(false);

    private volatile boolean active = false;
    private volatile boolean singleCheckMode = false;
    private volatile boolean scanningSemester = false;
    private volatile String scanStatusText = "";
    private volatile String statusText = "Отключено";
    private volatile long serviceStartMillis = 0L;
    private volatile long lastAutoSyncMillis = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        storage = new AppStorage(this);
        apiClient = new SutApiClient();
        scheduleManager = new ScheduleManager();
        diaryMonitor = new DiaryMonitor();
        messageManager = new MessageManager();

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

    private void saveAuthStatus(String authState, String authError) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("authState", authState != null ? authState : "");
            obj.put("authError", authError != null ? authError : "");
            obj.put("updatedAt", System.currentTimeMillis());
            storage.writeText(FILE_AUTH_STATUS, obj.toString());
        } catch (Exception ignored) {}
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

    private void runParallelSecondarySync(final String login, final String pass, final boolean manualTrigger) {
        Thread diaryThread = new Thread(new Runnable() {
            @Override
            public void run() {
                diaryMonitor.pollDiary(BonchBackgroundDaemon.this, apiClient, storage, login, pass, manualTrigger);
                flushState();
            }
        });
        Thread msgThread = new Thread(new Runnable() {
            @Override
            public void run() {
                messageManager.pollAll(BonchBackgroundDaemon.this, apiClient, storage, login, pass, manualTrigger);
                flushState();
            }
        });
        diaryThread.start();
        msgThread.start();
        try {
            diaryThread.join(25000L);
            msgThread.join(25000L);
        } catch (InterruptedException ignored) {}
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

        if ("logout".equals(action)) {
            singleCheckMode = false;
            storage.clearSessionData();
            saveAuthStatus("", "");
            apiClient.invalidateSession();
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
                "🔔 Тест уведомлений Pulse",
                "Уведомления работают отлично!"
            );
            storage.addLog("Отправлено тестовое уведомление.");
            flushState();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        final String login = cfg.optString("login", "").trim();
        final String pass = cfg.optString("password", "").trim();
        final String mode = cfg.optString("mode", "full_lesson");

        if ("sync_messages".equals(action)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (login.isEmpty() || pass.isEmpty()) return;
                    messageManager.pollAll(BonchBackgroundDaemon.this, apiClient, storage, login, pass, true);
                    flushState();
                }
            }).start();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        if ("send_message".equals(action) && intent != null) {
            final String threadId = intent.getStringExtra("threadId");
            final String text = intent.getStringExtra("text");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    messageManager.sendReply(apiClient, storage, login, pass, threadId, text);
                    flushState();
                }
            }).start();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        if ("manual_checkin".equals(action) && intent != null) {
            final String raspId = intent.getStringExtra("raspId");
            final String weekId = intent.getStringExtra("weekId");
            final String startTime = intent.getStringExtra("startTime");
            final String subject = intent.getStringExtra("subject");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (login.isEmpty() || pass.isEmpty()) {
                        storage.addLog("Войдите в аккаунт вуза в Настройках.");
                        flushState();
                        return;
                    }
                    boolean ok = scheduleManager.manualCheckIn(
                        BonchBackgroundDaemon.this, apiClient, storage, login, pass, raspId, weekId, startTime, subject
                    );
                    if (ok) {
                        diaryMonitor.pollDiary(BonchBackgroundDaemon.this, apiClient, storage, login, pass, false);
                    }
                    flushState();
                }
            }).start();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        if ("login_and_sync".equals(action)) {
            if (!syncInProgress.compareAndSet(false, true)) {
                return active ? START_STICKY : START_NOT_STICKY;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (login.isEmpty() || pass.isEmpty()) {
                            storage.saveProfile(false, "", "", login);
                            saveAuthStatus("error", "Введите логин и пароль от lk.sut.ru");
                            flushState();
                            return;
                        }

                        saveAuthStatus("checking", "");
                        statusText = "Проверка входа в lk.sut.ru...";
                        flushState();

                        apiClient.checkCredentialsChanged(login, pass);
                        if (apiClient.authenticate(login, pass, storage)) {
                            // Вход подтверждён сервером — сразу открываем доступ в UI
                            saveAuthStatus("ok", "");
                            statusText = "Загрузка расписания...";
                            flushState();

                            // 1. Загружаем текущую неделю и сразу отдаём в UI
                            String[] win = scheduleManager.getCurrentWindowState(mode, storage);
                            scheduleManager.pollCurrentWeekAndCheckIn(
                                BonchBackgroundDaemon.this, apiClient, storage, login, pass, win != null ? win[0] : null
                            );
                            flushState();

                            // 2. Параллельно грузим Дневник и Сообщения/Файлы
                            runParallelSecondarySync(login, pass, false);
                            lastAutoSyncMillis = System.currentTimeMillis();
                            if (!active) {
                                statusText = "Подключено";
                            }
                            flushState();

                            // 3. Сканирование всего семестра запускаем в фоне
                            if (scheduleManager.needsSemesterScan() && !scanningSemester) {
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        scheduleManager.scanFullSemester(apiClient, storage, BonchBackgroundDaemon.this, login, pass);
                                        diaryMonitor.pollDiary(BonchBackgroundDaemon.this, apiClient, storage, login, pass, false);
                                        flushState();
                                    }
                                }).start();
                            }
                        } else {
                            // Ошибка входа или нет сети — не пускаем в аккаунт
                            String errMsg = apiClient.getLastAuthError();
                            if (errMsg == null || errMsg.isEmpty()) {
                                errMsg = "Не удалось войти в lk.sut.ru";
                            }
                            if (apiClient.wasLastAuthBadCredentials()) {
                                storage.clearSessionData();
                            } else {
                                storage.saveProfile(false, "", "", login);
                            }
                            saveAuthStatus("error", errMsg);
                            statusText = errMsg;
                            flushState();
                        }
                    } finally {
                        syncInProgress.set(false);
                    }
                }
            }).start();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        if ("check_diary_now".equals(action)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (login.isEmpty() || pass.isEmpty()) {
                        storage.addLog("Введите логин и пароль в Настройках!");
                        flushState();
                        return;
                    }
                    storage.addLog("📚 Проверка Дневника...");
                    flushState();
                    String[] win = scheduleManager.getCurrentWindowState(mode, storage);
                    scheduleManager.pollCurrentWeekAndCheckIn(
                        BonchBackgroundDaemon.this, apiClient, storage, login, pass, win != null ? win[0] : null
                    );
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
                        storage.addLog("Введите логин и пароль в Настройках!");
                        flushState();
                        return;
                    }
                    if (scanningSemester) return;
                    scheduleManager.scanFullSemester(apiClient, storage, BonchBackgroundDaemon.this, login, pass);
                    runParallelSecondarySync(login, pass, false);
                    flushState();
                }
            }).start();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        if ("check_once".equals(action) || "sync_all".equals(action)) {
            if (!syncInProgress.compareAndSet(false, true)) {
                return active ? START_STICKY : START_NOT_STICKY;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (login.isEmpty() || pass.isEmpty()) {
                            storage.addLog("Сначала войдите в аккаунт в Настройках!");
                            flushState();
                            return;
                        }
                        storage.addLog("Синхронизация расписания, дневника и сообщений...");
                        flushState();
                        String[] win = scheduleManager.getCurrentWindowState(mode, storage);
                        scheduleManager.pollCurrentWeekAndCheckIn(
                            BonchBackgroundDaemon.this, apiClient, storage, login, pass, win != null ? win[0] : null
                        );
                        flushState();
                        runParallelSecondarySync(login, pass, false);
                        lastAutoSyncMillis = System.currentTimeMillis();
                        flushState();
                        if (win == null && scheduleManager.needsSemesterScan() && !scanningSemester) {
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    scheduleManager.scanFullSemester(apiClient, storage, BonchBackgroundDaemon.this, login, pass);
                                    flushState();
                                }
                            }).start();
                        }
                    } finally {
                        syncInProgress.set(false);
                    }
                }
            }).start();
            return active ? START_STICKY : START_NOT_STICKY;
        }

        if (login.isEmpty() || pass.isEmpty()) {
            statusText = "Укажите логин и пароль в Настройках!";
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

        String fgTitle = singleCheckMode ? "⚡ Разовый режим (1 пара)" : "Pulse • Автоотметка активна";
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
                    try {
                        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                    } catch (SecurityException se) {
                        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi);
                    }
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
                storage.addLog(singleCheckMode ? "⚡ Запуск разовой отметки (на 1 пару)..." : "Служба Pulse запущена.");
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
                        runParallelSecondarySync(login, password, false);
                        lastAutoSyncMillis = System.currentTimeMillis();
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
                            updateStatus("⚡ Разовый режим (1 пара) • Ждём пару в " + nextLesson);
                        } else {
                            updateStatus("⚡ Разовый режим (1 пара) • Ожидание кнопки в ЛК...");
                        }
                        sleepQuietly(Math.max(15000L, (long) (intervalMin * 60000L)));
                        continue;
                    }

                    boolean busyCatchingButton = (win != null && !scheduleManager.completedWindowsToday.contains(win[0]));

                    long autoUpdInterval = storage.getAutoUpdateIntervalMillis();
                    if (!busyCatchingButton && autoUpdInterval > 0L
                        && (System.currentTimeMillis() - lastAutoSyncMillis) >= autoUpdInterval) {
                        lastAutoSyncMillis = System.currentTimeMillis();
                        scheduleManager.pollCurrentWeekAndCheckIn(
                            BonchBackgroundDaemon.this, apiClient, storage, login, password, null
                        );
                        runParallelSecondarySync(login, password, false);
                        flushState();
                    } else if (!busyCatchingButton && storage.isNotifyMessagesEnabled() && messageManager.isTimeForPeriodicPoll(storage)) {
                        messageManager.pollAll(BonchBackgroundDaemon.this, apiClient, storage, login, password, false);
                        flushState();
                    } else if (!busyCatchingButton && storage.isNotifyDiaryEnabled() && diaryMonitor.isTimeForPeriodicPoll(storage)) {
                        diaryMonitor.pollDiary(BonchBackgroundDaemon.this, apiClient, storage, login, password, false);
                        flushState();
                    }

                    if (win == null) {
                        String nextLesson = scheduleManager.findNextUnmarkedLessonTime(storage);
                        if (nextLesson != null) {
                            updateStatus("Подключено • Ждём пару в " + nextLesson);
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
                        updateStatus(prefix + "⏳ Ждём начала от преподавателя • " + winLabel);
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
                            updateStatus(prefix + "⏳ Ждём начала от преподавателя • " + winLabel);
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