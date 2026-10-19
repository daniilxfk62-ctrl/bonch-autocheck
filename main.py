name: Build Bonch APK v2.1

on:
  push:
    branches: [ "main", "master" ]
  workflow_dispatch:

jobs:
  build-apk:
    runs-on: ubuntu-22.04
    steps:
      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: "temurin"
          java-version: "17"

      - name: Compile Native Android APK v2.1 (Always-On Background)
        run: |
          set -e
          SDK_DIR="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
          ANDROID_JAR=$(ls -d "$SDK_DIR"/platforms/android-* | sort -V | tail -n 1)/android.jar
          BUILD_TOOLS=$(ls -d "$SDK_DIR"/build-tools/* | sort -V | tail -n 1)

          mkdir -p src/ru/sut/bonch res/values build/gen build/obj build/apk

          cat << 'EOF' > res/values/strings.xml
          <?xml version="1.0" encoding="utf-8"?>
          <resources>
              <string name="app_name">Bonch AutoCheck</string>
          </resources>
          EOF

          cat << 'EOF' > AndroidManifest.xml
          <?xml version="1.0" encoding="utf-8"?>
          <manifest xmlns:android="http://schemas.android.com/apk/res/android"
              package="ru.sut.bonch"
              android:versionCode="3"
              android:versionName="2.1">

              <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="33" />
              <uses-permission android:name="android.permission.INTERNET" />
              <uses-permission android:name="android.permission.WAKE_LOCK" />
              <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
              <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
              <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
              <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

              <application
                  android:label="@string/app_name"
                  android:allowBackup="false"
                  android:usesCleartextTraffic="false"
                  android:theme="@android:style/Theme.DeviceDefault.NoActionBar">

                  <service
                      android:name=".BonchForegroundService"
                      android:enabled="true"
                      android:exported="false"
                      android:foregroundServiceType="dataSync" />

                  <receiver
                      android:name=".BootReceiver"
                      android:enabled="true"
                      android:exported="true">
                      <intent-filter>
                          <action android:name="android.intent.action.BOOT_COMPLETED" />
                      </intent-filter>
                  </receiver>

                  <activity
                      android:name=".MainActivity"
                      android:exported="true"
                      android:launchMode="singleTop"
                      android:configChanges="orientation|screenSize|keyboardHidden">
                      <intent-filter>
                          <action android:name="android.intent.action.MAIN" />
                          <category android:name="android.intent.category.LAUNCHER" />
                      </intent-filter>
                  </activity>
              </application>
          </manifest>
          EOF

          cat << 'EOF' > src/ru/sut/bonch/BootReceiver.java
          package ru.sut.bonch;

          import android.content.BroadcastReceiver;
          import android.content.Context;
          import android.content.Intent;
          import android.content.SharedPreferences;
          import android.os.Build;

          public class BootReceiver extends BroadcastReceiver {
              @Override
              public void onReceive(Context context, Intent intent) {
                  if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                      SharedPreferences prefs = context.getSharedPreferences("bonch_prefs", Context.MODE_PRIVATE);
                      String login = prefs.getString("login", "").trim();
                      String pass = prefs.getString("password", "").trim();
                      if (!login.isEmpty() && !pass.isEmpty()) {
                          Intent svc = new Intent(context, BonchForegroundService.class);
                          svc.putExtra("action", "ensure_running");
                          if (Build.VERSION.SDK_INT >= 26) {
                              context.startForegroundService(svc);
                          } else {
                              context.startService(svc);
                          }
                      }
                  }
              }
          }
          EOF

          cat << 'EOF' > src/ru/sut/bonch/BonchForegroundService.java
          package ru.sut.bonch;

          import android.app.Notification;
          import android.app.NotificationChannel;
          import android.app.NotificationManager;
          import android.app.PendingIntent;
          import android.app.Service;
          import android.content.Context;
          import android.content.Intent;
          import android.content.SharedPreferences;
          import android.os.Build;
          import android.os.IBinder;
          import android.os.PowerManager;
          import java.io.ByteArrayOutputStream;
          import java.io.InputStream;
          import java.io.OutputStream;
          import java.net.CookieHandler;
          import java.net.CookieManager;
          import java.net.CookiePolicy;
          import java.net.HttpURLConnection;
          import java.net.URL;
          import java.net.URLEncoder;
          import java.nio.charset.Charset;
          import java.text.SimpleDateFormat;
          import java.util.ArrayList;
          import java.util.Arrays;
          import java.util.Calendar;
          import java.util.Date;
          import java.util.HashMap;
          import java.util.HashSet;
          import java.util.List;
          import java.util.Locale;
          import java.util.Map;
          import java.util.Set;
          import java.util.regex.Matcher;
          import java.util.regex.Pattern;
          import org.json.JSONArray;
          import org.json.JSONObject;

          public class BonchForegroundService extends Service {
              private static final String BASE_URL = "https://lk.sut.ru/cabinet";
              private static final String LOGIN_PAGE_URL = BASE_URL + "/?login=yes";
              private static final String AUTH_URL = BASE_URL + "/lib/autentificationok.php";
              private static final String INI_URL = BASE_URL + "/ini.php";
              private static final String SCHEDULE_URL = BASE_URL + "/project/cabinet/forms/raspisanie.php";

              private static final String CHANNEL_BG = "bonch_bg_vpn_channel";
              private static final String CHANNEL_ALERT = "bonch_checkin_alert_channel";
              private static final int FG_NOTIFICATION_ID = 1001;

              private static final String[] BELL_SCHEDULE = {
                  "09:00", "10:30", "10:45", "13:00", "14:45", "16:30", "18:15", "20:00"
              };

              public static volatile boolean active = false;
              public static volatile String statusText = "Введите логин и пароль для автозапуска";
              public static volatile String nearestDayTitle = "Ближайший учебный день";
              public static final List<String> logs = new ArrayList<>();
              public static JSONArray lastLessonsJson = new JSONArray();

              private static final Set<String> completedWindowsToday = new HashSet<>();
              private static final Set<String> markedRaspIdsToday = new HashSet<>();
              private static final Map<String, String> checkInTimeByKey = new HashMap<>();
              private static final Set<String> parsedTimes = new HashSet<>();
              private static String currentDayStamp = "";

              private PowerManager.WakeLock wakeLock;
              private Thread workerThread;
              private volatile boolean isAuthenticated = false;
              private String lastUsedLogin = "";
              private String lastUsedPass = "";

              @Override
              public void onCreate() {
                  super.onCreate();
                  if (CookieHandler.getDefault() == null) {
                      CookieHandler.setDefault(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
                  }
                  PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                  if (pm != null) {
                      wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BonchAutoCheck::BgVpnLock");
                  }
                  createNotificationChannels();
              }

              @Override
              public int onStartCommand(Intent intent, int flags, int startId) {
                  startForeground(FG_NOTIFICATION_ID, buildForegroundNotification(statusText));

                  String action = intent != null ? intent.getStringExtra("action") : "ensure_running";
                  SharedPreferences prefs = getSharedPreferences("bonch_prefs", Context.MODE_PRIVATE);
                  final String login = prefs.getString("login", "").trim();
                  final String pass = prefs.getString("password", "").trim();

                  if (login.isEmpty() || pass.isEmpty()) {
                      updateStatus("Ожидание ввода логина и пароля...");
                      return START_STICKY;
                  }

                  // Если поменялись логин или пароль — сбрасываем флаг авторизации
                  if (!login.equals(lastUsedLogin) || !pass.equals(lastUsedPass)) {
                      isAuthenticated = false;
                      lastUsedLogin = login;
                      lastUsedPass = pass;
                  }

                  if (!active || workerThread == null || !workerThread.isAlive()) {
                      startWorkerLoop();
                  } else if ("check_once".equals(action)) {
                      new Thread(new Runnable() {
                          @Override
                          public void run() {
                              addLog("Ручное обновление расписания и проверка кнопки...");
                              WindowState win = getCurrentWindowState(prefs.getString("mode", "full_lesson"));
                              pollScheduleAndCheckIn(login, pass, win != null ? win.startTime : null);
                          }
                      }).start();
                  }
                  return START_STICKY;
              }

              private void startWorkerLoop() {
                  active = true;
                  if (wakeLock != null && !wakeLock.isHeld()) {
                      wakeLock.acquire();
                  }

                  workerThread = new Thread(new Runnable() {
                      @Override
                      public void run() {
                          addLog("Фоновая служба запущена (работает постоянно по умолчанию).");
                          while (active) {
                              SharedPreferences prefs = getSharedPreferences("bonch_prefs", Context.MODE_PRIVATE);
                              String login = prefs.getString("login", "").trim();
                              String password = prefs.getString("password", "").trim();
                              String intervalStr = prefs.getString("interval", "1");
                              String mode = prefs.getString("mode", "full_lesson");

                              if (login.isEmpty() || password.isEmpty()) {
                                  updateStatus("Введите логин и пароль от lk.sut.ru");
                                  sleepQuietly(10000L);
                                  continue;
                              }

                              double intervalMin = 1.0;
                              try {
                                  intervalMin = Double.parseDouble(intervalStr);
                              } catch (Exception ignored) {}

                              String today = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());
                              if (!today.equals(currentDayStamp)) {
                                  currentDayStamp = today;
                                  completedWindowsToday.clear();
                                  markedRaspIdsToday.clear();
                                  checkInTimeByKey.clear();
                                  addLog("Наступил новый день (" + today + ") — сброс отметок.");
                              }

                              if (!isAuthenticated) {
                                  updateStatus("Подключение к lk.sut.ru...");
                                  if (!authenticate(login, password)) {
                                      updateStatus("Ошибка входа (проверьте логин/пароль или сеть)");
                                      sleepQuietly(20000L);
                                      continue;
                                  }
                                  pollScheduleAndCheckIn(login, password, null);
                              }

                              WindowState win = getCurrentWindowState(mode);
                              if (win == null) {
                                  updateStatus("Активно в фоне • Ждем следующую пару");
                                  sleepQuietly(25000L);
                              } else if (completedWindowsToday.contains(win.startTime)) {
                                  String markedAt = checkInTimeByKey.get(win.startTime);
                                  String suffix = (markedAt != null) ? (" в " + markedAt) : "";
                                  updateStatus("✅ Отмечен" + suffix + " • Спим до следующей пары");
                                  sleepQuietly(30000L);
                              } else {
                                  updateStatus("Опрос ЛК • " + win.label);
                                  pollScheduleAndCheckIn(login, password, win.startTime);
                                  if (completedWindowsToday.contains(win.startTime)) {
                                      String markedAt = checkInTimeByKey.get(win.startTime);
                                      updateStatus("✅ Отмечен в " + (markedAt != null ? markedAt : "ЛК") + " • Спим до следующей пары");
                                  }
                                  sleepQuietly(Math.max(15000L, (long) (intervalMin * 60000L)));
                              }
                          }
                      }
                  });
                  workerThread.start();
              }

              private void sleepQuietly(long ms) {
                  try {
                      Thread.sleep(ms);
                  } catch (InterruptedException ignored) {}
              }

              private void updateStatus(String newStatus) {
                  statusText = newStatus;
                  NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                  if (nm != null) {
                      nm.notify(FG_NOTIFICATION_ID, buildForegroundNotification(newStatus));
                  }
              }

              public static synchronized void addLog(String msg) {
                  String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                  logs.add("[" + stamp + "] " + msg);
                  if (logs.size() > 90) {
                      logs.remove(0);
                  }
              }

              private void createNotificationChannels() {
                  if (Build.VERSION.SDK_INT >= 26) {
                      NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                      if (nm == null) return;
                      NotificationChannel bgChannel = new NotificationChannel(
                          CHANNEL_BG,
                          "Постоянная фоновая работа Bonch AutoCheck",
                          NotificationManager.IMPORTANCE_LOW
                      );
                      nm.createNotificationChannel(bgChannel);

                      NotificationChannel alertChannel = new NotificationChannel(
                          CHANNEL_ALERT,
                          "Уведомления об успешной отметке",
                          NotificationManager.IMPORTANCE_HIGH
                      );
                      nm.createNotificationChannel(alertChannel);
                  }
              }

              private Notification buildForegroundNotification(String content) {
                  Intent notificationIntent = new Intent(this, MainActivity.class);
                  int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
                  PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

                  Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                      ? new Notification.Builder(this, CHANNEL_BG)
                      : new Notification.Builder(this);
                  builder.setContentTitle("Bonch AutoCheck (Фоновый режим активен)")
                         .setContentText(content)
                         .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                         .setContentIntent(pendingIntent)
                         .setOngoing(true);
                  return builder.build();
              }

              private void sendCheckInAlertNotification(String title, String message) {
                  try {
                      NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                      if (nm != null) {
                          Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                              ? new Notification.Builder(this, CHANNEL_ALERT)
                              : new Notification.Builder(this);
                          builder.setContentTitle(title)
                                 .setContentText(message)
                                 .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                                 .setAutoCancel(true);
                          nm.notify((int) (System.currentTimeMillis() % 100000), builder.build());
                      }
                  } catch (Exception ignored) {}

                  final SharedPreferences prefs = getSharedPreferences("bonch_prefs", Context.MODE_PRIVATE);
                  final String tgToken = prefs.getString("tg_token", "").trim();
                  final String tgChat = prefs.getString("tg_chat", "").trim();
                  final String fTitle = title;
                  final String fMsg = message;

                  if (!tgToken.isEmpty() && !tgChat.isEmpty()) {
                      new Thread(new Runnable() {
                          @Override
                          public void run() {
                              try {
                                  String url = "https://api.telegram.org/bot" + tgToken + "/sendMessage";
                                  String body = "chat_id=" + URLEncoder.encode(tgChat, "UTF-8")
                                      + "&text=" + URLEncoder.encode(fTitle + "\n" + fMsg, "UTF-8");
                                  httpRequest("POST", url, body);
                              } catch (Exception ignored) {}
                          }
                      }).start();
                  }
              }

              private String httpRequest(String method, String urlStr, String postBody) throws Exception {
                  URL url = new URL(urlStr);
                  HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                  conn.setRequestMethod(method);
                  conn.setConnectTimeout(15000);
                  conn.setReadTimeout(15000);
                  conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) Chrome/128.0.0.0 Mobile Safari/537.36");
                  conn.setRequestProperty("Referer", LOGIN_PAGE_URL);
                  conn.setRequestProperty("Origin", "https://lk.sut.ru");
                  conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");

                  if ("POST".equals(method)) {
                      conn.setDoOutput(true);
                      conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                      byte[] out = (postBody != null ? postBody : "").getBytes("UTF-8");
                      try (OutputStream os = conn.getOutputStream()) {
                          os.write(out);
                      }
                  }

                  InputStream is = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
                  if (is == null) return "";
                  ByteArrayOutputStream baos = new ByteArrayOutputStream();
                  byte[] buf = new byte[4096];
                  int n;
                  while ((n = is.read(buf)) != -1) {
                      baos.write(buf, 0, n);
                  }
                  byte[] bytes = baos.toByteArray();
                  String utf8 = new String(bytes, Charset.forName("UTF-8"));
                  String lower = utf8.toLowerCase(Locale.ROOT);
                  if (lower.contains("расписание") || lower.contains("занят") || lower.contains("присутств") || lower.contains("понедельник") || lower.contains("вторник") || utf8.trim().equals("1")) {
                      return utf8;
                  }
                  String cp1251 = new String(bytes, Charset.forName("windows-1251"));
                  if (cp1251.toLowerCase(Locale.ROOT).contains("расписание") || cp1251.toLowerCase(Locale.ROOT).contains("занят")) {
                      return cp1251;
                  }
                  return utf8;
              }

              private synchronized boolean authenticate(String login, String password) {
                  try {
                      httpRequest("GET", LOGIN_PAGE_URL, null);
                      String data = "users=" + URLEncoder.encode(login, "UTF-8")
                                  + "&parole=" + URLEncoder.encode(password, "UTF-8");
                      String resp = httpRequest("POST", AUTH_URL + "?" + data, data);
                      if (resp.trim().equals("1") || resp.contains("1")) {
                          httpRequest("GET", INI_URL, null);
                          httpRequest("GET", LOGIN_PAGE_URL, null);
                          isAuthenticated = true;
                          addLog("Авторизация в ЛК успешна (" + login + ")");
                          return true;
                      }
                      isAuthenticated = false;
                      addLog("Неверный логин или пароль от lk.sut.ru");
                      return false;
                  } catch (Exception e) {
                      isAuthenticated = false;
                      addLog("Ошибка сети: " + e.getMessage());
                      return false;
                  }
              }

              // Восстановленная двухэтапная отправка и проверка отметки (как в Python-версии)
              private boolean sendAndVerifyOpenZan(String raspId, String weekId) {
                  try {
                      String payload = "open=1&rasp=" + raspId + "&week=" + weekId;
                      httpRequest("POST", SCHEDULE_URL + "?" + payload, payload);
                      String verifyHtml = httpRequest("POST", SCHEDULE_URL, "").toLowerCase(Locale.ROOT);
                      return verifyHtml.contains("присутств")
                          || verifyHtml.contains("отмечен")
                          || !verifyHtml.contains("open_zan(" + raspId)
                          || !verifyHtml.contains("open_zan('" + raspId);
                  } catch (Exception e) {
                      return false;
                  }
              }

              private static class DayGroup {
                  String header;
                  Date dateObj;
                  List<JSONObject> lessons = new ArrayList<>();
              }

              private synchronized void pollScheduleAndCheckIn(String login, String password, String currentWindowStart) {
                  if (!isAuthenticated && !authenticate(login, password)) {
                      return;
                  }
                  try {
                      String html = httpRequest("POST", SCHEDULE_URL, "");
                      if (html.trim().length() < 50 || html.toLowerCase(Locale.ROOT).contains("autentification") || !html.toLowerCase(Locale.ROOT).contains("<table")) {
                          if (!authenticate(login, password)) return;
                          html = httpRequest("POST", SCHEDULE_URL, "");
                      }

                      Pattern trPattern = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
                      Pattern dayHeaderPattern = Pattern.compile("(Понедельник|Вторник|Среда|Четверг|Пятница|Суббота|Воскресенье)\\s*(\\d{2}\\.\\d{2}\\.\\d{4})?", Pattern.CASE_INSENSITIVE);
                      Pattern timePattern = Pattern.compile("([01]?\\d|2[0-3])[:.]([0-5]\\d)\\s*[-–—]\\s*(?:[01]?\\d|2[0-3])[:.][0-5]\\d");
                      Pattern zanPattern = Pattern.compile("open_zan\\s*\\(\\s*['\"]?(\\d+)['\"]?\\s*,\\s*['\"]?(\\d+)['\"]?\\s*\\)");
                      Pattern startedPattern = Pattern.compile("занятие началось\\s*(\\d{1,2}[:.]\\d{2})", Pattern.CASE_INSENSITIVE);

                      Matcher trMatcher = trPattern.matcher(html);
                      List<DayGroup> dayGroups = new ArrayList<>();
                      DayGroup currentGroup = null;
                      SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy", Locale.US);

                      List<String> newlyChecked = new ArrayList<>();

                      while (trMatcher.find()) {
                          String trHtml = trMatcher.group(1);
                          String rowText = trHtml.replaceAll("<[^>]+>", " ")
                                                 .replaceAll("&nbsp;", " ")
                                                 .replaceAll("\\s+", " ")
                                                 .trim();
                          if (rowText.isEmpty()) continue;

                          Matcher dm = dayHeaderPattern.matcher(rowText);
                          Matcher tm = timePattern.matcher(rowText);

                          if (dm.find() && !tm.find() && rowText.length() < 45) {
                              currentGroup = new DayGroup();
                              currentGroup.header = rowText;
                              if (dm.group(2) != null) {
                                  try {
                                      currentGroup.dateObj = df.parse(dm.group(2));
                                  } catch (Exception ignored) {}
                              }
                              dayGroups.add(currentGroup);
                              continue;
                          }

                          if (!tm.find()) continue;

                          if (currentGroup == null) {
                              currentGroup = new DayGroup();
                              currentGroup.header = "Ближайший учебный день";
                              dayGroups.add(currentGroup);
                          }

                          String timeRange = tm.group(0);
                          int hh = Integer.parseInt(tm.group(1));
                          String startTime = String.format(Locale.US, "%02d:%s", hh, tm.group(2));
                          synchronized (parsedTimes) {
                              parsedTimes.add(startTime);
                          }

                          Matcher zm = zanPattern.matcher(trHtml);
                          String raspId = null;
                          String weekId = null;
                          if (zm.find()) {
                              raspId = zm.group(1);
                              weekId = zm.group(2);
                          }

                          Matcher sm = startedPattern.matcher(rowText);
                          String startedAt = sm.find() ? sm.group(1) : null;

                          String lowerRow = rowText.toLowerCase(Locale.ROOT);
                          boolean alreadyInHtml = lowerRow.contains("вы присутствуете")
                                               || lowerRow.contains("отмечен")
                                               || lowerRow.contains("присутствует");

                          String lessonKey = (raspId != null) ? raspId : (currentGroup.header + "_" + startTime);

                          if (raspId != null && weekId != null && !alreadyInHtml && !markedRaspIdsToday.contains(raspId)) {
                              if (sendAndVerifyOpenZan(raspId, weekId)) {
                                  String nowStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                                  markedRaspIdsToday.add(raspId);
                                  checkInTimeByKey.put(lessonKey, nowStr);
                                  checkInTimeByKey.put(startTime, nowStr);
                                  completedWindowsToday.add(startTime);
                                  alreadyInHtml = true;

                                  String cleanSubj = rowText.replaceFirst("^\\d+\\s*\\([^)]+\\)\\s*", "");
                                  if (cleanSubj.length() > 70) cleanSubj = cleanSubj.substring(0, 70);
                                  newlyChecked.add(cleanSubj + " (отметка в " + nowStr + ")");
                              }
                          }

                          boolean isCheckedDone = alreadyInHtml
                                               || checkInTimeByKey.containsKey(lessonKey)
                                               || (raspId != null && markedRaspIdsToday.contains(raspId));

                          if (isCheckedDone) {
                              String timeStamp = checkInTimeByKey.get(lessonKey);
                              if (timeStamp == null) {
                                  timeStamp = (startedAt != null) ? startedAt : new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                                  checkInTimeByKey.put(lessonKey, timeStamp);
                              }
                              if (currentWindowStart != null && currentWindowStart.equals(startTime)) {
                                  completedWindowsToday.add(startTime);
                                  checkInTimeByKey.put(startTime, timeStamp);
                              }
                          }

                          String cleanSubjectText = rowText.replace(timeRange, "").replaceFirst("^\\s*\\d+\\s*\\(\\s*\\)\\s*", "").trim();
                          if (cleanSubjectText.length() > 95) {
                              cleanSubjectText = cleanSubjectText.substring(0, 95);
                          }

                          JSONObject item = new JSONObject();
                          item.put("time", timeRange);
                          item.put("subject", cleanSubjectText);
                          item.put("checked", isCheckedDone);
                          String badgeTime = checkInTimeByKey.get(lessonKey);
                          if (badgeTime == null && startedAt != null && isCheckedDone) badgeTime = startedAt;
                          item.put("checkTime", badgeTime != null ? badgeTime : "");
                          currentGroup.lessons.add(item);
                      }

                      // Восстановленный резервный проход по всему HTML (на случай нестандартной верстки кнопки open_zan)
                      Matcher fallbackMatcher = zanPattern.matcher(html);
                      while (fallbackMatcher.find()) {
                          String rId = fallbackMatcher.group(1);
                          String wId = fallbackMatcher.group(2);
                          if (!markedRaspIdsToday.contains(rId)) {
                              if (sendAndVerifyOpenZan(rId, wId)) {
                                  String nowStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                                  markedRaspIdsToday.add(rId);
                                  if (currentWindowStart != null) {
                                      completedWindowsToday.add(currentWindowStart);
                                      checkInTimeByKey.put(currentWindowStart, nowStr);
                                  }
                                  newlyChecked.add("Занятие #" + rId + " (отметка в " + nowStr + ")");
                              }
                          }
                      }

                      DayGroup selectedDay = pickNearestDayGroup(dayGroups);
                      if (selectedDay != null) {
                          nearestDayTitle = selectedDay.header;
                          lastLessonsJson = new JSONArray(selectedDay.lessons);
                      } else {
                          nearestDayTitle = "Ближайший учебный день";
                          lastLessonsJson = new JSONArray();
                      }

                      if (!newlyChecked.isEmpty()) {
                          String nowStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                          for (String s : newlyChecked) {
                              String title = "✅ Отметился на паре в " + nowStr + "!";
                              addLog(title + " — " + s);
                              sendCheckInAlertNotification(title, s);
                          }
                      } else {
                          addLog("Проверено (" + nearestDayTitle + "): новых кнопок нет.");
                      }
                  } catch (Exception e) {
                      addLog("Ошибка опроса: " + e.getMessage());
                  }
              }

              private DayGroup pickNearestDayGroup(List<DayGroup> groups) {
                  if (groups.isEmpty()) return null;
                  Calendar cal = Calendar.getInstance();
                  cal.set(Calendar.HOUR_OF_DAY, 0);
                  cal.set(Calendar.MINUTE, 0);
                  cal.set(Calendar.SECOND, 0);
                  cal.set(Calendar.MILLISECOND, 0);
                  Date todayStart = cal.getTime();

                  for (DayGroup g : groups) {
                      if (!g.lessons.isEmpty() && g.dateObj != null && !g.dateObj.before(todayStart)) {
                          return g;
                      }
                  }
                  for (DayGroup g : groups) {
                      if (!g.lessons.isEmpty()) return g;
                  }
                  return groups.get(0);
              }

              private static class WindowState {
                  String startTime;
                  String label;
              }

              private WindowState getCurrentWindowState(String mode) {
                  int windowMin = "first_30".equals(mode) ? 30 : 95;
                  Set<String> allTimes = new HashSet<>(Arrays.asList(BELL_SCHEDULE));
                  synchronized (parsedTimes) {
                      allTimes.addAll(parsedTimes);
                  }
                  Calendar now = Calendar.getInstance();
                  long nowMillis = now.getTimeInMillis();
                  for (String t : allTimes) {
                      try {
                          String[] parts = t.replace(".", ":").split(":");
                          Calendar start = (Calendar) now.clone();
                          start.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
                          start.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
                          start.set(Calendar.SECOND, 0);
                          double diffMin = (nowMillis - start.getTimeInMillis()) / 60000.0;
                          if (diffMin >= -10.0 && diffMin <= windowMin) {
                              WindowState ws = new WindowState();
                              ws.startTime = t;
                              ws.label = diffMin < 0
                                  ? ("Пара в " + t + " (до начала " + Math.abs((int) diffMin) + " мин)")
                                  : ("Пара " + t + " (идет " + (int) diffMin + " мин)");
                              return ws;
                          }
                      } catch (Exception ignored) {}
                  }
                  if ("always".equals(mode)) {
                      WindowState ws = new WindowState();
                      ws.startTime = "always_" + new SimpleDateFormat("HH", Locale.US).format(new Date());
                      ws.label = "Постоянный режим";
                      return ws;
                  }
                  return null;
              }

              @Override
              public IBinder onBind(Intent intent) {
                  return null;
              }
          }
          EOF

          cat << 'EOF' > src/ru/sut/bonch/MainActivity.java
          package ru.sut.bonch;

          import android.app.Activity;
          import android.content.Context;
          import android.content.Intent;
          import android.content.SharedPreferences;
          import android.os.Build;
          import android.os.Bundle;
          import android.webkit.JavascriptInterface;
          import android.webkit.WebSettings;
          import android.webkit.WebView;
          import org.json.JSONArray;
          import org.json.JSONObject;

          public class MainActivity extends Activity {
              private WebView webView;
              private SharedPreferences prefs;

              @Override
              protected void onCreate(Bundle savedInstanceState) {
                  super.onCreate(savedInstanceState);
                  prefs = getSharedPreferences("bonch_prefs", Context.MODE_PRIVATE);

                  if (Build.VERSION.SDK_INT >= 33) {
                      try {
                          requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
                      } catch (Exception ignored) {}
                  }

                  // Фоновый режим включается АВТОМАТИЧЕСКИ по умолчанию при старте приложения
                  ensureBackgroundServiceRunning("ensure_running");

                  webView = new WebView(this);
                  WebSettings ws = webView.getSettings();
                  ws.setJavaScriptEnabled(true);
                  ws.setDomStorageEnabled(true);
                  webView.addJavascriptInterface(new WebBridge(), "AndroidBridge");
                  setContentView(webView);

                  webView.loadDataWithBaseURL("https://lk.sut.ru", buildHtml(), "text/html", "UTF-8", null);
              }

              private void ensureBackgroundServiceRunning(String action) {
                  Intent svc = new Intent(MainActivity.this, BonchForegroundService.class);
                  svc.putExtra("action", action);
                  if (Build.VERSION.SDK_INT >= 26) {
                      startForegroundService(svc);
                  } else {
                      startService(svc);
                  }
              }

              public class WebBridge {
                  @JavascriptInterface
                  public String getState() {
                      try {
                          JSONObject obj = new JSONObject();
                          obj.put("status", BonchForegroundService.statusText);
                          obj.put("dayTitle", BonchForegroundService.nearestDayTitle);
                          synchronized (BonchForegroundService.class) {
                              obj.put("logs", new JSONArray(BonchForegroundService.logs));
                          }
                          obj.put("lessons", BonchForegroundService.lastLessonsJson);
                          return obj.toString();
                      } catch (Exception e) {
                          return "{}";
                      }
                  }

                  @JavascriptInterface
                  public void saveSettingsOnly(String login, String password, String intervalStr, String mode, String tgToken, String tgChat) {
                      savePrefs(login, password, intervalStr, mode, tgToken, tgChat);
                      ensureBackgroundServiceRunning("ensure_running");
                  }

                  @JavascriptInterface
                  public void saveAndCheckNow(String login, String password, String intervalStr, String mode, String tgToken, String tgChat) {
                      savePrefs(login, password, intervalStr, mode, tgToken, tgChat);
                      ensureBackgroundServiceRunning("check_once");
                  }
              }

              private void savePrefs(String login, String pass, String interval, String mode, String tgToken, String tgChat) {
                  prefs.edit()
                       .putString("login", login != null ? login.trim() : "")
                       .putString("password", pass != null ? pass.trim() : "")
                       .putString("interval", interval != null ? interval : "1")
                       .putString("mode", mode != null ? mode : "full_lesson")
                       .putString("tg_token", tgToken != null ? tgToken.trim() : "")
                       .putString("tg_chat", tgChat != null ? tgChat.trim() : "")
                       .apply();
              }

              private String escapeHtml(String s) {
                  if (s == null) return "";
                  return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
              }

              private String buildHtml() {
                  String l = escapeHtml(prefs.getString("login", ""));
                  String p = escapeHtml(prefs.getString("password", ""));
                  String iv = escapeHtml(prefs.getString("interval", "1"));
                  String md = escapeHtml(prefs.getString("mode", "full_lesson"));
                  String t = escapeHtml(prefs.getString("tg_token", ""));
                  String c = escapeHtml(prefs.getString("tg_chat", ""));

                  return "<!DOCTYPE html><html><head><meta charset='UTF-8'>"
                      + "<meta name='viewport' content='width=device-width,initial-scale=1.0,user-scalable=no'>"
                      + "<style>"
                      + "body{margin:0;background:#090d16;color:#f8fafc;font-family:sans-serif;padding:14px;}"
                      + ".card{background:#111827;border:1px solid #1e293b;border-radius:20px;padding:18px;max-width:440px;margin:0 auto;}"
                      + ".lbl{font-size:11px;color:#94a3b8;margin-top:4px;display:block;}"
                      + "input,select{width:100%;box-sizing:border-box;padding:11px;margin:4px 0 10px;border-radius:10px;border:1px solid #334155;background:#1e293b;color:#fff;font-size:14px;}"
                      + ".btn{width:100%;padding:14px;border-radius:12px;border:none;background:#059669;color:#fff;font-weight:700;font-size:14px;margin-top:6px;}"
                      + ".day-hdr{font-size:13px;font-weight:700;color:#60a5fa;margin:10px 0 6px;}"
                      + ".item{background:#1e293b;padding:10px;border-radius:10px;margin-bottom:6px;font-size:12px;border-left:4px solid #fbbf24;}"
                      + ".item.done{border-left-color:#34d399;}"
                      + ".log{background:#090d16;border:1px solid #1e293b;border-radius:10px;padding:10px;font-family:monospace;font-size:11px;height:130px;overflow-y:auto;white-space:pre-wrap;color:#cbd5e1;}"
                      + ".sec-badge{font-size:11px;color:#34d399;margin-bottom:10px;font-weight:600;}"
                      + "</style></head><body><div class='card'>"
                      + "<div style='font-size:18px;font-weight:800;'>Bonch AutoCheck v2.1</div>"
                      + "<div class='sec-badge'>🟢 Фоновый режим всегда включен по умолчанию • HTTPS</div>"
                      + "<span class='lbl'>Логин / Email от lk.sut.ru</span>"
                      + "<input id='login' placeholder='student@sut.ru' value=\"" + l + "\" onchange='saveCfg()'>"
                      + "<span class='lbl'>Пароль от ЛК (защищен локально)</span>"
                      + "<input id='password' type='password' placeholder='••••••••' value=\"" + p + "\" onchange='saveCfg()'>"
                      + "<span class='lbl'>Интервал отправки запросов</span>"
                      + "<select id='interval' onchange='saveCfg()'>"
                      + "<option value='0.5'" + ("0.5".equals(iv) ? " selected" : "") + ">Каждые 30 секунд (мгновенно)</option>"
                      + "<option value='1'" + ("1".equals(iv) ? " selected" : "") + ">Каждую 1 минуту</option>"
                      + "<option value='5'" + ("5".equals(iv) ? " selected" : "") + ">Каждые 5 минут</option></select>"
                      + "<span class='lbl'>Длительность опроса (после отметки ждет следующую пару)</span>"
                      + "<select id='mode' onchange='saveCfg()'>"
                      + "<option value='full_lesson'" + ("full_lesson".equals(md) ? " selected" : "") + ">За 10 мин до пары + вся пара (95 мин)</option>"
                      + "<option value='first_30'" + ("first_30".equals(md) ? " selected" : "") + ">За 10 мин до пары + первые 30 мин</option>"
                      + "<option value='always'" + ("always".equals(md) ? " selected" : "") + ">Постоянно 24/7</option></select>"
                      + "<input id='tg_token' placeholder='Telegram Bot Token (необязательно)' value=\"" + t + "\" onchange='saveCfg()'>"
                      + "<input id='tg_chat' placeholder='Telegram Chat ID (необязательно)' value=\"" + c + "\" onchange='saveCfg()'>"
                      + "<button class='btn' onclick='checkNow()'>СОХРАНИТЬ И ПРОВЕРИТЬ СЕЙЧАС</button>"
                      + "<div id='status' style='margin:10px 0;font-size:13px;font-weight:700;color:#34d399;'>Статус: Активно в фоне</div>"
                      + "<div id='day_title' class='day-hdr'>Ближайший учебный день</div>"
                      + "<div id='lessons' style='margin-bottom:10px;'></div>"
                      + "<div id='logs' class='log'>Загрузка...</div>"
                      + "</div><script>"
                      + "function val(id){return document.getElementById(id).value;}"
                      + "function saveCfg(){AndroidBridge.saveSettingsOnly(val('login'),val('password'),val('interval'),val('mode'),val('tg_token'),val('tg_chat'));}"
                      + "function checkNow(){AndroidBridge.saveAndCheckNow(val('login'),val('password'),val('interval'),val('mode'),val('tg_token'),val('tg_chat'));setTimeout(poll,600);}"
                      + "function poll(){"
                      + "  try{"
                      + "    var d=JSON.parse(AndroidBridge.getState());"
                      + "    document.getElementById('status').innerText='Статус: '+(d.status||'Активно в фоне');"
                      + "    if(d.dayTitle)document.getElementById('day_title').innerText='📅 '+d.dayTitle;"
                      + "    if(d.logs)document.getElementById('logs').innerText=d.logs.join('\\n');"
                      + "    var h='';"
                      + "    if(d.lessons && d.lessons.length){for(var i=0;i<d.lessons.length;i++){"
                      + "      var x=d.lessons[i];"
                      + "      var cls=x.checked?'item done':'item';"
                      + "      var c=x.checked?'#34d399':'#fbbf24';"
                      + "      var st=x.checked?('✅ Отмечен'+(x.checkTime?' в '+x.checkTime:'')):'⏳ Ждем кнопку';"
                      + "      h+=\"<div class='\"+cls+\"'><b style='color:\"+c+\"'>\"+x.time+\" — \"+st+\"</b><br>\"+x.subject+\"</div>\";"
                      + "    }}else{h=\"<div style='font-size:12px;color:#94a3b8;'>Введите данные и нажмите «Сохранить и проверить сейчас».</div>\";}"
                      + "    document.getElementById('lessons').innerHTML=h;"
                      + "  }catch(e){}"
                      + "}"
                      + "setInterval(poll,1500);poll();"
                      + "</script></body></html>";
              }
          }
          EOF

          "$BUILD_TOOLS/aapt2" compile --dir res -o build/res.zip
          "$BUILD_TOOLS/aapt2" link -o build/unaligned.apk \
            -I "$ANDROID_JAR" \
            --manifest AndroidManifest.xml \
            --java build/gen \
            build/res.zip

          javac -encoding UTF-8 -source 8 -target 8 \
            -bootclasspath "$ANDROID_JAR" \
            -cp "$ANDROID_JAR" \
            -d build/obj \
            build/gen/ru/sut/bonch/R.java \
            src/ru/sut/bonch/BootReceiver.java \
            src/ru/sut/bonch/BonchForegroundService.java \
            src/ru/sut/bonch/MainActivity.java

          "$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --output build/ build/obj/ru/sut/bonch/*.class

          zip -uj build/unaligned.apk build/classes.dex

          "$BUILD_TOOLS/zipalign" -f -p 4 build/unaligned.apk build/aligned.apk

          keytool -genkeypair -v \
            -keystore build/release.keystore \
            -storepass android -keypass android \
            -alias bonchkey -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=BonchAutoCheck,OU=IT,O=SUT,L=SPb,S=SPb,C=RU"

          "$BUILD_TOOLS/apksigner" sign \
            --ks build/release.keystore \
            --ks-pass pass:android \
            --key-pass pass:android \
            --out build/apk/BonchAutoCheck-v2.1.apk \
            build/aligned.apk

          ls -lh build/apk/BonchAutoCheck-v2.1.apk

      - name: Upload APK Artifact
        uses: actions/upload-artifact@v4
        with:
          name: BonchAutoCheck-v2.1-APK
          path: build/apk/BonchAutoCheck-v2.1.apk
