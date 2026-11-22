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
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;
    private AppStorage storage;
    private MessageManager messageManager;

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
        webView.addJavascriptInterface(new WebBridge(), "AndroidBridge");
        setContentView(webView);

        webView.loadUrl("file:///android_asset/index.html");
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
        } catch (IllegalStateException e) {
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    startForegroundService(svc);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
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
                requestBatteryOptimizationIfNeeded();
                sendDaemonCommand("start");
            } else {
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
    }
}