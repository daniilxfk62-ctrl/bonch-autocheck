package ru.sut.bonch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;
    private AppStorage storage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        storage = new AppStorage(this);

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
            } catch (Exception ignored) {}
        }

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

        webView = new WebView(this);
        webView.clearCache(true);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.addJavascriptInterface(new WebBridge(), "AndroidBridge");
        setContentView(webView);

        webView.loadUrl("file:///android_asset/index.html");
    }

    private void sendDaemonCommand(String action) {
        Intent svc = new Intent(MainActivity.this, BonchBackgroundDaemon.class);
        svc.putExtra("action", action);
        if ("start".equals(action) && Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }

    public class WebBridge {
        @JavascriptInterface
        public String getSavedConfig() {
            return storage.getUserConfig().toString();
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
        public String getNotifPrefs() {
            return storage.readText(AppStorage.FILE_NOTIF_PREFS, "{\"notifyDiary\":true,\"notifySkippedLessons\":true}");
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
        public String getCachedDiaryRecent() {
            return storage.readText(AppStorage.FILE_DIARY_RECENT, "[]");
        }

        @JavascriptInterface
        public String getState() {
            try {
                boolean enabled = storage.isDaemonEnabled();
                JSONObject state = storage.readJson(AppStorage.FILE_BG_STATE, "{}");
                state.put("active", enabled && state.optBoolean("active", false));
                if (!state.has("status")) {
                    state.put("status", enabled ? "Запуск фоновой службы..." : "Отключено");
                }
                state.put("daysMtime", storage.getFileMtime(AppStorage.FILE_SEMESTER_DAYS));
                state.put("catMtime", storage.getFileMtime(AppStorage.FILE_SMART_CATALOG));
                state.put("diaryMtime", storage.getFileMtime(AppStorage.FILE_DIARY_RECENT));
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
        public void toggleAutoCheck(String login, String password, String intervalStr, String mode) {
            storage.saveUserConfig(login, password, intervalStr, mode);
            if (storage.isDaemonEnabled()) {
                sendDaemonCommand("stop");
            } else {
                sendDaemonCommand("start");
            }
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
            storage.saveUserConfig(login, password, intervalStr, mode);
            sendDaemonCommand("scan_semester");
        }
    }
}
