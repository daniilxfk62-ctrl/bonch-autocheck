package ru.sut.bonch;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class AppStorage {
    public static final String APP_VERSION = "01.04.01";

    public static final String FILE_CONFIG = "user_config_v4.json";
    public static final String FILE_SMART_RULES = "smart_rules_v4.json";
    public static final String FILE_NOTIF_PREFS = "notif_prefs_v4.json";
    public static final String FILE_SEMESTER_DAYS = "semester_days_v4.json";
    public static final String FILE_SMART_CATALOG = "smart_catalog_v4.json";
    public static final String FILE_DIARY_RECENT = "diary_recent_v4.json";
    public static final String FILE_DIARY_SNAPSHOT = "diary_snapshot_v4.json";
    public static final String FILE_BG_STATE = "bg_state_v4.json";
    public static final String FILE_ENABLED_FLAG = "daemon_enabled.flag";

    private final File filesDir;
    private final List<String> memoryLogs = new ArrayList<>();

    public AppStorage(Context ctx) {
        this.filesDir = ctx.getFilesDir();
    }

    public synchronized String readText(String fileName, String fallback) {
        try {
            File f = new File(filesDir, fileName);
            if (!f.exists()) return fallback;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
            }
            return new String(baos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return fallback;
        }
    }

    public synchronized void writeText(String fileName, String content) {
        try {
            File f = new File(filesDir, fileName);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write((content != null ? content : "").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {}
    }

    public JSONObject readJson(String fileName, String fallbackJson) {
        try {
            return new JSONObject(readText(fileName, fallbackJson));
        } catch (Exception e) {
            try {
                return new JSONObject(fallbackJson);
            } catch (Exception ex) {
                return new JSONObject();
            }
        }
    }

    public JSONArray readJsonArray(String fileName) {
        try {
            return new JSONArray(readText(fileName, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public long getFileMtime(String fileName) {
        File f = new File(filesDir, fileName);
        return f.exists() ? f.lastModified() : 0L;
    }

    public boolean isDaemonEnabled() {
        return new File(filesDir, FILE_ENABLED_FLAG).exists();
    }

    public void setDaemonEnabled(boolean enabled) {
        File f = new File(filesDir, FILE_ENABLED_FLAG);
        try {
            if (enabled) {
                if (!f.exists()) f.createNewFile();
            } else {
                if (f.exists()) f.delete();
            }
        } catch (Exception ignored) {}
    }

    public void saveUserConfig(String login, String pass, String interval, String mode) {
        try {
            JSONObject cfg = new JSONObject();
            cfg.put("login", login != null ? login.trim() : "");
            cfg.put("password", pass != null ? pass.trim() : "");
            cfg.put("interval", (interval != null && !interval.isEmpty()) ? interval : "1");
            cfg.put("mode", (mode != null && !mode.isEmpty()) ? mode : "full_lesson");
            writeText(FILE_CONFIG, cfg.toString());
        } catch (Exception ignored) {}
    }

    public JSONObject getUserConfig() {
        return readJson(FILE_CONFIG, "{\"interval\":\"1\",\"mode\":\"full_lesson\"}");
    }

    public boolean isRuleAllowed(String ruleKey) {
        if (ruleKey == null || ruleKey.isEmpty()) return true;
        JSONObject rules = readJson(FILE_SMART_RULES, "{}");
        if (!rules.has(ruleKey)) return true;
        return rules.optBoolean(ruleKey, true);
    }

    public boolean isNotifyDiaryEnabled() {
        return readJson(FILE_NOTIF_PREFS, "{\"notifyDiary\":true,\"notifySkippedLessons\":true}")
            .optBoolean("notifyDiary", true);
    }

    public boolean isNotifySkippedEnabled() {
        return readJson(FILE_NOTIF_PREFS, "{\"notifyDiary\":true,\"notifySkippedLessons\":true}")
            .optBoolean("notifySkippedLessons", true);
    }

    public synchronized void addLog(String msg) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        memoryLogs.add("[" + stamp + "] " + msg);
        if (memoryLogs.size() > 90) {
            memoryLogs.remove(0);
        }
    }

    public synchronized void loadLogsFromDisk() {
        JSONObject state = readJson(FILE_BG_STATE, "{}");
        JSONArray arr = state.optJSONArray("logs");
        if (arr != null) {
            memoryLogs.clear();
            for (int i = 0; i < arr.length(); i++) {
                memoryLogs.add(arr.optString(i, ""));
            }
        }
    }

    public synchronized void saveRuntimeState(
        boolean active,
        String statusText,
        boolean scanningSemester,
        String scanStatusText,
        long serviceStartMillis,
        int todayDayIdx
    ) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("active", active && isDaemonEnabled());
            obj.put("status", statusText);
            obj.put("scanningSemester", scanningSemester);
            obj.put("scanStatus", scanStatusText);
            obj.put("startMillis", serviceStartMillis);
            obj.put("todayDayIdx", todayDayIdx);
            obj.put("logs", new JSONArray(memoryLogs));
            writeText(FILE_BG_STATE, obj.toString());
        } catch (Exception ignored) {}
    }
}
