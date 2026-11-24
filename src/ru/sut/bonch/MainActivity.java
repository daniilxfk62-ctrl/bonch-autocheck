package ru.sut.bonch;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;
    private AppStorage storage;
    private MessageManager messageManager;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQ_CODE = 2026;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        storage = new AppStorage(this);
        messageManager = new MessageManager();

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
            } catch (Exception ignored) {}
        }

        webView = new WebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQ_CODE);
                    return true;
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
            }
        });
        webView.addJavascriptInterface(new WebBridge(), "AndroidBridge");
        setContentView(webView);

        webView.loadUrl("file:///android_asset/index.html");
        UpdateChecker.checkAsync(this, storage, false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQ_CODE) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Uri uri = data.getData();
                    if (uri != null) {
                        results = new Uri[]{uri};
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }
    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript(
                "(function(){ return window.__pulseHandleBack ? !!window.__pulseHandleBack() : false; })()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        if (!"true".equals(value)) {
                            MainActivity.super.onBackPressed();
                        }
                    }
                }
            );
            return;
        }
        super.onBackPressed();
    }
    private void requestBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent bi = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    bi.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(bi);
                }
            } catch (Exception ignored) {}
        }
    }

    private void sendDaemonCommand(String action) {
        sendDaemonIntent(new Intent(MainActivity.this, BonchBackgroundDaemon.class).putExtra("action", action));
    }

    private void sendDaemonIntent(Intent svc) {
        String action = svc.getStringExtra("action");
        boolean requiresForeground = "start".equals(action) || "check_single".equals(action);
        try {
            if (requiresForeground && Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            storage.addLog("Ошибка запуска службы (" + action + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            try {
                startService(svc);
            } catch (Exception e2) {
                storage.addLog("Повторный запуск службы не удался (" + action + "): " + e2.getClass().getSimpleName() + ": " + e2.getMessage());
                if (requiresForeground) {
                    storage.setDaemonEnabled(false);
                }
            }
        }
    }

    private String buildCookieHeaderFromStorage() {
        try {
            JSONArray arr = storage.readJsonArray(AppStorage.FILE_COOKIES);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c == null) continue;
                String name = c.optString("name", "");
                String val = c.optString("value", "");
                if (name.isEmpty()) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(name).append("=").append(val);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public class WebBridge {
        @JavascriptInterface
        public String getSavedConfig() {
            return storage.getUserConfig().toString();
        }

        @JavascriptInterface
        public String getProfile() {
            return storage.getProfile().toString();
        }

        @JavascriptInterface
        public String getSmartRules() {
            return storage.readText(AppStorage.FILE_SMART_RULES, "{}");
        }

        @JavascriptInterface
        public void saveSmartRules(String rulesJsonStr) {
            storage.writeText(AppStorage.FILE_SMART_RULES, rulesJsonStr != null ? rulesJsonStr : "{}");
            sendDaemonCommand("refresh_rules");
        }

        @JavascriptInterface
        public String getGroupChoices() {
            return storage.readText(AppStorage.FILE_GROUP_CHOICES, "{}");
        }

        @JavascriptInterface
        public void saveGroupChoices(String choicesJsonStr) {
            storage.writeText(AppStorage.FILE_GROUP_CHOICES, choicesJsonStr != null ? choicesJsonStr : "{}");
            sendDaemonCommand("refresh_rules");
        }

        @JavascriptInterface
        public String getNotifPrefs() {
            return storage.readText(
                AppStorage.FILE_NOTIF_PREFS,
                "{\"notifyDiary\":true,\"notifySkippedLessons\":true,\"notifyMessages\":true}"
            );
        }

        @JavascriptInterface
        public void saveNotifPrefs(String prefsJsonStr) {
            storage.writeText(AppStorage.FILE_NOTIF_PREFS, prefsJsonStr != null ? prefsJsonStr : "{}");
        }

        @JavascriptInterface
        public String getCachedSemesterDays() {
            return storage.readText(AppStorage.FILE_SEMESTER_DAYS, "[]");
        }

        @JavascriptInterface
        public String getCachedSmartCatalog() {
            return storage.readText(AppStorage.FILE_SMART_CATALOG, "[]");
        }

        @JavascriptInterface
        public String getCachedDiarySummary() {
            return storage.readText(AppStorage.FILE_DIARY_SUMMARY, "[]");
        }

        @JavascriptInterface
        public String getCachedDiaryRecent() {
            return storage.readText(AppStorage.FILE_DIARY_RECENT, "[]");
        }

        @JavascriptInterface
        public String getCachedDiaryAll() {
            return storage.readText(AppStorage.FILE_DIARY_ALL, "[]");
        }

        @JavascriptInterface
        public String getCachedMessages() {
            return storage.readText(AppStorage.FILE_MESSAGES, "[]");
        }

        @JavascriptInterface
        public String getCachedGroupFiles() {
            return storage.readText(AppStorage.FILE_GROUP_FILES, "[]");
        }

        @JavascriptInterface
        public String getState() {
            try {
                boolean enabled = storage.isDaemonEnabled();
                JSONObject state = storage.readJson(AppStorage.FILE_BG_STATE, "{}");
                state.put("active", enabled && state.optBoolean("active", false));
                state.put("daemonEnabled", enabled);
                if (!state.has("status")) {
                    state.put("status", enabled ? "Запуск фоновой службы..." : "Отключено");
                }
                JSONObject authSt = storage.readJson(BonchBackgroundDaemon.FILE_AUTH_STATUS, "{}");
                state.put("authState", authSt.optString("authState", ""));
                state.put("authError", authSt.optString("authError", ""));
                state.put("daysMtime", storage.getFileMtime(AppStorage.FILE_SEMESTER_DAYS));
                state.put("catMtime", storage.getFileMtime(AppStorage.FILE_SMART_CATALOG));
                state.put("diaryMtime", storage.getFileMtime(AppStorage.FILE_DIARY_SUMMARY) + storage.getFileMtime(AppStorage.FILE_DIARY_ALL));
                state.put("messagesMtime", storage.getFileMtime(AppStorage.FILE_MESSAGES));
                state.put("filesMtime", storage.getFileMtime(AppStorage.FILE_GROUP_FILES));
                state.put("profileMtime", storage.getFileMtime(AppStorage.FILE_PROFILE));
                state.put("teachersMtime", storage.getFileMtime(AppStorage.FILE_TEACHERS));
                state.put("notesMtime", storage.getFileMtime(AppStorage.FILE_DAY_NOTES));
                state.put("logs", storage.getLogsArray());
                return state.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void saveSettingsOnly(String login, String password, String intervalStr, String mode) {
            storage.saveUserConfig(login, password, intervalStr, mode);
        }

        @JavascriptInterface
        public void saveFullSettings(String login, String password, String intervalStr, String mode, String autoUpdateHours) {
            storage.saveUserConfigFull(login, password, intervalStr, mode, autoUpdateHours);
        }

        @JavascriptInterface
        public void savePulseSettings(String autoDelay, boolean antiKick, String themeMode) {
            storage.savePulseSettings(autoDelay, antiKick, themeMode);
            sendDaemonCommand("refresh_rules");
        }

        @JavascriptInterface
        public void loginAccount(String login, String password) {
            JSONObject cfg = storage.getUserConfig();
            storage.saveUserConfig(
                login,
                password,
                cfg.optString("interval", "1"),
                cfg.optString("mode", "full_lesson")
            );
            // НЕ ставим loggedIn = true до реального подтверждения от lk.sut.ru
            storage.saveProfile(false, "", "", login);
            try {
                JSONObject authObj = new JSONObject();
                authObj.put("authState", "checking");
                authObj.put("authError", "");
                authObj.put("updatedAt", System.currentTimeMillis());
                storage.writeText(BonchBackgroundDaemon.FILE_AUTH_STATUS, authObj.toString());
            } catch (Exception ignored) {}
            sendDaemonCommand("login_and_sync");
        }

        @JavascriptInterface
        public void logoutAccount() {
            sendDaemonCommand("logout");
        }

        @JavascriptInterface
        public void setAutoCheckEnabled(boolean enabled) {
            if (enabled) {
                JSONObject cfg = storage.getUserConfig();
                String login = cfg.optString("login", "").trim();
                String pass = cfg.optString("password", "").trim();
                if (login.isEmpty() || pass.isEmpty()) {
                    storage.setDaemonEnabled(false);
                    storage.addLog("Автоотметка не запущена: логин или пароль не сохранены. Войдите в аккаунт заново.");
                    return;
                }
                storage.setDaemonEnabled(true);
                storage.addLog("Включение автоотметки...");
                sendDaemonCommand("start");
                requestBatteryOptimizationIfNeeded();
            } else {
                storage.setDaemonEnabled(false);
                storage.addLog("Отключение автоотметки...");
                sendDaemonCommand("stop");
            }
        }

        @JavascriptInterface
        public void toggleAutoCheck(String login, String password, String intervalStr, String mode) {
            if (login != null && !login.isEmpty() && password != null && !password.isEmpty()) {
                storage.saveUserConfig(login, password, intervalStr, mode);
            }
            if (storage.isDaemonEnabled()) {
                sendDaemonCommand("stop");
            } else {
                requestBatteryOptimizationIfNeeded();
                sendDaemonCommand("start");
            }
        }

        @JavascriptInterface
        public void manualCheckIn(String raspId, String weekId, String startTime, String subject) {
            Intent svc = new Intent(MainActivity.this, BonchBackgroundDaemon.class);
            svc.putExtra("action", "manual_checkin");
            svc.putExtra("raspId", raspId != null ? raspId : "");
            svc.putExtra("weekId", weekId != null ? weekId : "");
            svc.putExtra("startTime", startTime != null ? startTime : "");
            svc.putExtra("subject", subject != null ? subject : "");
            sendDaemonIntent(svc);
        }

        @JavascriptInterface
        public void syncAll() {
            sendDaemonCommand("sync_all");
        }

        @JavascriptInterface
        public void syncMessagesNow() {
            sendDaemonCommand("sync_messages");
        }

        @JavascriptInterface
        public void markMessageRead(String threadId) {
            if (threadId != null && !threadId.isEmpty()) {
                messageManager.markThreadRead(storage, threadId);
            }
        }

        @JavascriptInterface
        public boolean toggleSenderMute(String threadId) {
            if (threadId == null || threadId.isEmpty()) return false;
            return messageManager.toggleSenderMute(storage, threadId);
        }

        @JavascriptInterface
        public void sendMessage(String threadId, String text) {
            if (threadId == null || text == null || text.trim().isEmpty()) return;
            Intent svc = new Intent(MainActivity.this, BonchBackgroundDaemon.class);
            svc.putExtra("action", "send_message");
            svc.putExtra("threadId", threadId);
            svc.putExtra("text", text.trim());
            sendDaemonIntent(svc);
        }
        @JavascriptInterface
        public String getDayNotes() {
            return storage.getDayNotesJson();
        }

        @JavascriptInterface
        public void saveDayNotes(String notesJsonStr) {
            storage.saveDayNotesJson(notesJsonStr);
            NotificationHelper.rescheduleNoteAlarms(MainActivity.this, storage);
        }

        @JavascriptInterface
        public String getMessageTeachers() {
            return storage.getMessageTeachers().toString();
        }

        @JavascriptInterface
        public void refreshMessageTeachers() {
            sendDaemonCommand("fetch_teachers");
        }

        @JavascriptInterface
        public boolean sendNewMessage(
            String adresatId,
            String adresatName,
            String title,
            String text,
            String fileName,
            String base64Data
        ) {
            if (adresatId == null || adresatId.trim().isEmpty() || "0".equals(adresatId.trim())) return false;
            if (title == null || title.trim().isEmpty()) return false;

            String savedPath = "";
            String safeName = (fileName != null && !fileName.trim().isEmpty())
                ? fileName.trim().replaceAll("[\\\\/:*?\"<>|]", "_")
                : "attachment";

            if (base64Data != null && !base64Data.isEmpty()) {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    if (bytes.length > 5 * 1024 * 1024) {
                        storage.addLog("Ошибка: файл больше 5 МБ");
                        return false;
                    }
                    File tmp = new File(getCacheDir(), "newmsg_" + System.currentTimeMillis() + "_" + safeName);
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        fos.write(bytes);
                    }
                    savedPath = tmp.getAbsolutePath();
                } catch (Exception e) {
                    storage.addLog("Ошибка подготовки файла: " + e.getMessage());
                    return false;
                }
            }

            Intent svc = new Intent(MainActivity.this, BonchBackgroundDaemon.class);
            svc.putExtra("action", "send_new_message");
            svc.putExtra("adresatId", adresatId.trim());
            svc.putExtra("adresatName", adresatName != null ? adresatName.trim() : "");
            svc.putExtra("title", title.trim());
            svc.putExtra("text", text != null ? text.trim() : "");
            svc.putExtra("filePath", savedPath);
            svc.putExtra("fileName", safeName);
            sendDaemonIntent(svc);
            return true;
        }

        @JavascriptInterface
        public boolean sendMessageWithFile(String threadId, String text, String fileName, String base64Data) {
            if (threadId == null || threadId.isEmpty()) return false;
            String cleanText = text != null ? text.trim() : "";
            String savedPath = "";
            String safeName = (fileName != null && !fileName.trim().isEmpty())
                ? fileName.trim().replaceAll("[\\\\/:*?\"<>|]", "_")
                : "attachment";

            if (base64Data != null && !base64Data.isEmpty()) {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    if (bytes.length > 5 * 1024 * 1024) {
                        storage.addLog("Ошибка: файл больше 5 МБ");
                        return false;
                    }
                    File tmp = new File(getCacheDir(), "upload_" + System.currentTimeMillis() + "_" + safeName);
                    try (FileOutputStream fos = new FileOutputStream(tmp)) {
                        fos.write(bytes);
                    }
                    savedPath = tmp.getAbsolutePath();
                } catch (Exception e) {
                    storage.addLog("Ошибка подготовки файла: " + e.getMessage());
                    return false;
                }
            }

            if (cleanText.isEmpty() && savedPath.isEmpty()) return false;

            Intent svc = new Intent(MainActivity.this, BonchBackgroundDaemon.class);
            svc.putExtra("action", "send_message");
            svc.putExtra("threadId", threadId);
            svc.putExtra("text", cleanText);
            svc.putExtra("filePath", savedPath);
            svc.putExtra("fileName", safeName);
            sendDaemonIntent(svc);
            return true;
        }

        @JavascriptInterface
        public boolean downloadGroupFile(String fileUrl, String fileName) {
            if (fileUrl == null || fileUrl.trim().isEmpty()) return false;
            String absUrl = SutApiClient.resolveUrl(fileUrl);
            String safeName = (fileName != null && !fileName.trim().isEmpty())
                ? fileName.trim().replaceAll("[\\\\/:*?\"<>|]", "_")
                : "sut_file";
            try {
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(absUrl));
                    String cookieHeader = buildCookieHeaderFromStorage();
                    if (!cookieHeader.isEmpty()) {
                        req.addRequestHeader("Cookie", cookieHeader);
                    }
                    req.addRequestHeader("Referer", SutApiClient.LOGIN_PAGE_URL);
                    req.setTitle(safeName);
                    req.setDescription("Загрузка из ЛК СПбГУТ");
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName);
                    dm.enqueue(req);
                    storage.addLog("📥 Загрузка файла: " + safeName);
                    return true;
                }
            } catch (Exception e) {
                try {
                    Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(absUrl));
                    startActivity(viewIntent);
                    return true;
                } catch (Exception ignored) {}
            }
            return false;
        }

        @JavascriptInterface
        public void checkSingleNow(String login, String password, String intervalStr, String mode) {
            storage.saveUserConfig(login, password, intervalStr, mode);
            requestBatteryOptimizationIfNeeded();
            sendDaemonCommand("check_single");
        }

        @JavascriptInterface
        public void checkNow(String login, String password, String intervalStr, String mode) {
            storage.saveUserConfig(login, password, intervalStr, mode);
            sendDaemonCommand("check_once");
        }

        @JavascriptInterface
        public void checkDiaryNow(String login, String password, String intervalStr, String mode) {
            storage.saveUserConfig(login, password, intervalStr, mode);
            sendDaemonCommand("check_diary_now");
        }

        @JavascriptInterface
        public void testNotifNow() {
            sendDaemonCommand("test_notif");
        }

        @JavascriptInterface
        public void scanSemesterNow(String login, String password, String intervalStr, String mode) {
            if (login != null && !login.isEmpty() && password != null && !password.isEmpty()) {
                storage.saveUserConfig(login, password, intervalStr, mode);
            }
            sendDaemonCommand("scan_semester");
        }

        @JavascriptInterface
        public String getUpdateInfo() {
            return UpdateChecker.getCachedInfoJson(MainActivity.this, storage);
        }

        @JavascriptInterface
        public void checkForUpdatesNow() {
            UpdateChecker.checkAsync(MainActivity.this, storage, true);
        }

        @JavascriptInterface
        public boolean downloadAndInstallUpdate(String apkUrl, String fileName, String fallbackPageUrl) {
            String targetUrl = (apkUrl != null && !apkUrl.trim().isEmpty()) ? apkUrl.trim() : "";
            String pageUrl = (fallbackPageUrl != null && !fallbackPageUrl.trim().isEmpty()) ? fallbackPageUrl.trim() : targetUrl;
            if (targetUrl.isEmpty() && pageUrl.isEmpty()) return false;

            if (!targetUrl.isEmpty()) {
                String safeName = (fileName != null && !fileName.trim().isEmpty())
                    ? fileName.trim().replaceAll("[\\\\/:*?\"<>|]", "_")
                    : "Pulse-Update.apk";
                try {
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(targetUrl));
                        req.setTitle("Обновление Pulse");
                        req.setDescription("Нажмите после загрузки для установки");
                        req.setMimeType("application/vnd.android.package-archive");
                        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName);
                        dm.enqueue(req);
                        storage.addLog("⬇️ Загрузка обновления: " + safeName);
                        return true;
                    }
                } catch (Exception ignored) {}
            }

            return openExternalUrl(!targetUrl.isEmpty() ? targetUrl : pageUrl);
        }

        @JavascriptInterface
        public boolean openExternalUrl(String url) {
            if (url == null || url.trim().isEmpty()) return false;
            try {
                Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()));
                startActivity(viewIntent);
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}