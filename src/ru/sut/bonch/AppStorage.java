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
    public static final String APP_VERSION = "02.00.00";

    public static final String FILE_CONFIG = "user_config_v4.json";
    public static final String FILE_PROFILE = "profile_v4.json";
    public static final String FILE_COOKIES = "session_cookies_v4.json";
    public static final String FILE_ENDPOINTS = "discovered_endpoints_v4.json";

    public static final String FILE_SMART_RULES = "smart_rules_v4.json";
    public static final String FILE_GROUP_CHOICES = "group_choices_v4.json";
    public static final String FILE_NOTIF_PREFS = "notif_prefs_v4.json";
    public static final String FILE_SEMESTER_DAYS = "semester_days_v4.json";
    public static final String FILE_SMART_CATALOG = "smart_catalog_v4.json";

    public static final String FILE_DIARY_RECENT = "diary_recent_v4.json";
    public static final String FILE_DIARY_ALL = "diary_all_v4.json";
    public static final String FILE_DIARY_SUMMARY = "diary_summary_v4.json";
    public static final String FILE_DIARY_SNAPSHOT = "diary_snapshot_v4.json";

    public static final String FILE_MESSAGES = "messages_v4.json";
    public static final String FILE_GROUP_FILES = "group_files_v4.json";
    public static final String FILE_MESSAGES_SNAPSHOT = "messages_snapshot_v4.json";
    public static final String FILE_MUTED_SENDERS = "muted_senders_v4.json";
    public static final String FILE_TEACHERS = "message_teachers_v4.json";
    public static final String FILE_DAY_NOTES = "day_notes_v4.json";
    public static final String FILE_NOTE_NOTIFIED = "note_notified_v4.json";

    public static final String FILE_BG_STATE = "bg_state_v4.json";
    public static final String FILE_LOGS = "app_logs_v4.json";
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

    public synchronized void deleteFile(String fileName) {
        try {
            File f = new File(filesDir, fileName);
            if (f.exists()) f.delete();
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
    public String getFileVersionKey(String fileName) {
        File f = new File(filesDir, fileName);
        if (!f.exists()) return "0_0";
        return f.lastModified() + "_" + f.length();
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
        JSONObject existing = getUserConfig();
        String autoUpd = existing.optString("autoUpdateHours", "2");
        String autoDelay = existing.optString("autoDelay", "random");
        boolean antiKick = existing.optBoolean("antiKick", true);
        String themeMode = existing.optString("themeMode", "light");
        saveUserConfigComplete(login, pass, interval, mode, autoUpd, autoDelay, antiKick, themeMode);
    }

    public void saveUserConfigFull(String login, String pass, String interval, String mode, String autoUpdateHours) {
        JSONObject existing = getUserConfig();
        String autoDelay = existing.optString("autoDelay", "random");
        boolean antiKick = existing.optBoolean("antiKick", true);
        String themeMode = existing.optString("themeMode", "light");
        saveUserConfigComplete(login, pass, interval, mode, autoUpdateHours, autoDelay, antiKick, themeMode);
    }

    public void savePulseSettings(String autoDelay, boolean antiKick, String themeMode) {
        JSONObject existing = getUserConfig();
        saveUserConfigComplete(
            existing.optString("login", ""),
            existing.optString("password", ""),
            existing.optString("interval", "1"),
            existing.optString("mode", "full_lesson"),
            existing.optString("autoUpdateHours", "2"),
            autoDelay,
            antiKick,
            themeMode
        );
    }

    public void saveUserConfigComplete(
        String login,
        String pass,
        String interval,
        String mode,
        String autoUpdateHours,
        String autoDelay,
        boolean antiKick,
        String themeMode
    ) {
        try {
            JSONObject cfg = new JSONObject();
            cfg.put("login", login != null ? login.trim() : "");
            cfg.put("password", pass != null ? pass.trim() : "");
            cfg.put("interval", (interval != null && !interval.isEmpty()) ? interval : "1");
            cfg.put("mode", (mode != null && !mode.isEmpty()) ? mode : "full_lesson");
            cfg.put("autoUpdateHours", (autoUpdateHours != null && !autoUpdateHours.isEmpty()) ? autoUpdateHours : "2");
            cfg.put("autoDelay", (autoDelay != null && !autoDelay.isEmpty()) ? autoDelay : "random");
            cfg.put("antiKick", antiKick);
            cfg.put("themeMode", (themeMode != null && !themeMode.isEmpty()) ? themeMode : "light");
            writeText(FILE_CONFIG, cfg.toString());
        } catch (Exception ignored) {}
    }

    public JSONObject getUserConfig() {
        return readJson(
            FILE_CONFIG,
            "{\"interval\":\"1\",\"mode\":\"full_lesson\",\"autoUpdateHours\":\"2\",\"autoDelay\":\"random\",\"antiKick\":true,\"themeMode\":\"light\"}"
        );
    }

    public String getAutoDelayMode() {
        return getUserConfig().optString("autoDelay", "random");
    }

    public boolean isAntiKickEnabled() {
        return getUserConfig().optBoolean("antiKick", true);
    }

    public String getThemeMode() {
        return getUserConfig().optString("themeMode", "light");
    }

    public long getAutoUpdateIntervalMillis() {
        String hStr = getUserConfig().optString("autoUpdateHours", "2");
        if ("0".equals(hStr) || "off".equalsIgnoreCase(hStr)) return 0L;
        try {
            double h = Double.parseDouble(hStr);
            if (h <= 0) return 0L;
            return (long) (h * 3600000L);
        } catch (Exception e) {
            return 7200000L;
        }
    }

    public JSONObject getProfile() {
        JSONObject p = readJson(FILE_PROFILE, "{\"loggedIn\":false,\"name\":\"\",\"group\":\"\",\"email\":\"\"}");
        if (p.optString("email", "").isEmpty()) {
            try {
                p.put("email", getUserConfig().optString("login", ""));
            } catch (Exception ignored) {}
        }
        return p;
    }

    public void saveProfile(boolean loggedIn, String name, String group, String email) {
        try {
            JSONObject existing = getProfile();
            JSONObject obj = new JSONObject();
            obj.put("loggedIn", loggedIn);
            obj.put("name", (name != null && !name.trim().isEmpty()) ? name.trim() : existing.optString("name", "Студент СПбГУТ"));
            obj.put("group", (group != null && !group.trim().isEmpty()) ? group.trim() : existing.optString("group", ""));
            obj.put("email", email != null ? email.trim() : existing.optString("email", ""));
            writeText(FILE_PROFILE, obj.toString());
        } catch (Exception ignored) {}
    }

    public void clearSessionData() {
        setDaemonEnabled(false);
        deleteFile(FILE_COOKIES);
        JSONObject cfg = getUserConfig();
        saveUserConfigComplete(
            cfg.optString("login", ""),
            "",
            cfg.optString("interval", "1"),
            cfg.optString("mode", "full_lesson"),
            cfg.optString("autoUpdateHours", "2"),
            cfg.optString("autoDelay", "random"),
            cfg.optBoolean("antiKick", true),
            cfg.optString("themeMode", "light")
        );
        try {
            JSONObject p = new JSONObject();
            p.put("loggedIn", false);
            p.put("name", "");
            p.put("group", "");
            p.put("email", cfg.optString("login", ""));
            writeText(FILE_PROFILE, p.toString());
        } catch (Exception ignored) {}
    }

    public JSONObject getDiscoveredEndpoints() {
        return readJson(FILE_ENDPOINTS, "{}");
    }

    public synchronized void saveDiscoveredEndpoint(String key, String url) {
        if (key == null || key.isEmpty() || url == null || url.isEmpty()) return;
        try {
            JSONObject ep = getDiscoveredEndpoints();
            ep.put(key, url);
            writeText(FILE_ENDPOINTS, ep.toString());
        } catch (Exception ignored) {}
    }

    public JSONObject getMutedSenders() {
        return readJson(FILE_MUTED_SENDERS, "{}");
    }

    public boolean isSenderMuted(String sender) {
        if (sender == null || sender.trim().isEmpty()) return false;
        return getMutedSenders().optBoolean(sender.trim(), false);
    }

    public synchronized void setSenderMuted(String sender, boolean muted) {
        if (sender == null || sender.trim().isEmpty()) return;
        try {
            JSONObject obj = getMutedSenders();
            if (muted) {
                obj.put(sender.trim(), true);
            } else {
                obj.remove(sender.trim());
            }
            writeText(FILE_MUTED_SENDERS, obj.toString());
        } catch (Exception ignored) {}
    }
    public JSONArray getMessageTeachers() {
        return readJsonArray(FILE_TEACHERS);
    }

    public synchronized void saveMessageTeachers(JSONArray arr) {
        writeText(FILE_TEACHERS, arr != null ? arr.toString() : "[]");
    }

    public String getDayNotesJson() {
        return readText(FILE_DAY_NOTES, "{}");
    }

    public synchronized void saveDayNotesJson(String json) {
        writeText(FILE_DAY_NOTES, (json != null && !json.trim().isEmpty()) ? json : "{}");
    }

    public boolean isRuleAllowed(String ruleKey) {
        if (ruleKey == null || ruleKey.isEmpty()) return true;
        JSONObject rules = readJson(FILE_SMART_RULES, "{}");
        if (!rules.has(ruleKey)) return true;
        return rules.optBoolean(ruleKey, true);
    }

    public JSONObject getGroupChoices() {
        return readJson(FILE_GROUP_CHOICES, "{}");
    }

    /**
     * Проверяет, подходит ли пара по выбранной пользователем группе/предмету на одно время.
     */
    public boolean isSubgroupAllowed(int dowIdx, String startTime, String subject, String teacher, String room) {
        JSONObject choices = getGroupChoices();
        String sNorm = subject != null ? subject.trim() : "";
        String tNorm = teacher != null ? teacher.trim() : "";
        String rNorm = ScheduleManager.formatRoomClean(room != null ? room : "");

        if (dowIdx >= 0 && startTime != null && !startTime.isEmpty()) {
            String slotKey = "SLOT||" + dowIdx + "||" + startTime;
            if (choices.has(slotKey) && !matchesChoiceValue(choices.opt(slotKey), sNorm, tNorm, rNorm)) {
                return false;
            }
        }

        if (!sNorm.isEmpty() && choices.has(sNorm)) {
            if (!matchesChoiceValue(choices.opt(sNorm), sNorm, tNorm, rNorm)) {
                return false;
            }
        }

        return true;
    }

    private boolean matchesChoiceValue(Object val, String sNorm, String tNorm, String rNorm) {
        if (val == null) return true;
        if (val instanceof JSONArray) {
            JSONArray arr = (JSONArray) val;
            if (arr.length() == 0) return true;
            for (int i = 0; i < arr.length(); i++) {
                String tok = arr.optString(i, "").trim();
                if (tok.isEmpty() || "ALL".equals(tok) || matchesChoiceToken(tok, sNorm, tNorm, rNorm)) {
                    return true;
                }
            }
            return false;
        }
        String s = String.valueOf(val).trim();
        if (s.isEmpty() || "ALL".equals(s)) return true;
        return matchesChoiceToken(s, sNorm, tNorm, rNorm);
    }
    private boolean matchesChoiceToken(String chosenToken, String sNorm, String tNorm, String rNorm) {
        String[] parts = chosenToken.split("\\|\\|");
        if (parts.length >= 3) {
            String cSubj = parts[0].trim();
            String cTeach = parts[1].trim();
            String cRoom = ScheduleManager.formatRoomClean(parts[2].trim());
            if (!cSubj.isEmpty() && !sNorm.isEmpty() && !cSubj.equalsIgnoreCase(sNorm)) return false;
            if (!cTeach.isEmpty() && !tNorm.isEmpty() && !cTeach.equalsIgnoreCase(tNorm)) return false;
            if (cTeach.isEmpty() && !cRoom.isEmpty() && !rNorm.isEmpty() && !cRoom.equalsIgnoreCase(rNorm)) return false;
            return true;
        } else {
            String cTeach = parts.length > 0 ? parts[0].trim() : "";
            String cRoom = parts.length > 1 ? ScheduleManager.formatRoomClean(parts[1].trim()) : "";
            if (!cTeach.isEmpty() && !tNorm.isEmpty()) {
                return cTeach.equalsIgnoreCase(tNorm);
            }
            if (!cRoom.isEmpty() && !rNorm.isEmpty()) {
                return cRoom.equalsIgnoreCase(rNorm);
            }
            return true;
        }
    }

    public boolean isNotifyDiaryEnabled() {
        return readJson(FILE_NOTIF_PREFS, "{\"notifyDiary\":true,\"notifySkippedLessons\":true,\"notifyMessages\":true}")
            .optBoolean("notifyDiary", true);
    }

    public boolean isNotifySkippedEnabled() {
        return readJson(FILE_NOTIF_PREFS, "{\"notifyDiary\":true,\"notifySkippedLessons\":true,\"notifyMessages\":true}")
            .optBoolean("notifySkippedLessons", true);
    }

    public boolean isNotifyMessagesEnabled() {
        return readJson(FILE_NOTIF_PREFS, "{\"notifyDiary\":true,\"notifySkippedLessons\":true,\"notifyMessages\":true}")
            .optBoolean("notifyMessages", true);
    }

    public synchronized void addLog(String msg) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = "[" + stamp + "] " + msg;
        JSONArray arr = getLogsArray();
        arr.put(line);
        JSONArray trimmed = new JSONArray();
        int start = Math.max(0, arr.length() - 120);
        for (int i = start; i < arr.length(); i++) {
            trimmed.put(arr.optString(i, ""));
        }
        memoryLogs.clear();
        for (int i = 0; i < trimmed.length(); i++) {
            memoryLogs.add(trimmed.optString(i, ""));
        }
        writeText(FILE_LOGS, trimmed.toString());
    }

    public synchronized JSONArray getLogsArray() {
        File f = new File(filesDir, FILE_LOGS);
        if (f.exists()) {
            return readJsonArray(FILE_LOGS);
        }
        JSONObject state = readJson(FILE_BG_STATE, "{}");
        JSONArray legacy = state.optJSONArray("logs");
        return legacy != null ? legacy : new JSONArray();
    }

    public synchronized void loadLogsFromDisk() {
        JSONArray arr = getLogsArray();
        memoryLogs.clear();
        for (int i = 0; i < arr.length(); i++) {
            memoryLogs.add(arr.optString(i, ""));
        }
    }

    public synchronized void saveRuntimeState(
        boolean active,
        String statusText,
        boolean scanningSemester,
        String scanStatusText,
        long serviceStartMillis,
        int todayDayIdx,
        String serverWarning
    ) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("active", active && isDaemonEnabled());
            obj.put("status", statusText);
            obj.put("scanningSemester", scanningSemester);
            obj.put("scanStatus", scanStatusText);
            obj.put("startMillis", serviceStartMillis);
            obj.put("todayDayIdx", todayDayIdx);
            obj.put("serverWarning", serverWarning != null ? serverWarning : "");
            obj.put("logs", getLogsArray());
            writeText(FILE_BG_STATE, obj.toString());
        } catch (Exception ignored) {}
    }
}