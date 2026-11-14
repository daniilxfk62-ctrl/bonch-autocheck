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
    public static final String APP_VERSION = "01.04.05";

    public static final String FILE_CONFIG = "user_config_v4.json";
    public static final String FILE_SMART_RULES = "smart_rules_v4.json";
    public static final String FILE_GROUP_CHOICES = "group_choices_v4.json";
    public static final String FILE_NOTIF_PREFS = "notif_prefs_v4.json";
    public static final String FILE_SEMESTER_DAYS = "semester_days_v4.json";
    public static final String FILE_SMART_CATALOG = "smart_catalog_v4.json";
    public static final String FILE_DIARY_RECENT = "diary_recent_v4.json";
    public static final String FILE_DIARY_ALL = "diary_all_v4.json";
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
        JSONObject existing = getUserConfig();
        String autoUpd = existing.optString("autoUpdateHours", "2");
        saveUserConfigFull(login, pass, interval, mode, autoUpd);
    }

    public void saveUserConfigFull(String login, String pass, String interval, String mode, String autoUpdateHours) {
        try {
            JSONObject cfg = new JSONObject();
            cfg.put("login", login != null ? login.trim() : "");
            cfg.put("password", pass != null ? pass.trim() : "");
            cfg.put("interval", (interval != null && !interval.isEmpty()) ? interval : "1");
            cfg.put("mode", (mode != null && !mode.isEmpty()) ? mode : "full_lesson");
            cfg.put("autoUpdateHours", (autoUpdateHours != null && !autoUpdateHours.isEmpty()) ? autoUpdateHours : "2");
            writeText(FILE_CONFIG, cfg.toString());
        } catch (Exception ignored) {}
    }

    public JSONObject getUserConfig() {
        return readJson(FILE_CONFIG, "{\"interval\":\"1\",\"mode\":\"full_lesson\",\"autoUpdateHours\":\"2\"}");
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
     * Поддерживает как выбор для конкретного слота ("SLOT||dowIdx||startTime" -> "Предмет||Препод||Кабинет"),
     * так и выбор по названию предмета ("Предмет" -> "Препод||Кабинет").
     */
    public boolean isSubgroupAllowed(int dowIdx, String startTime, String subject, String teacher, String room) {
        JSONObject choices = getGroupChoices();
        String sNorm = subject != null ? subject.trim() : "";
        String tNorm = teacher != null ? teacher.trim() : "";
        String rNorm = ScheduleManager.formatRoomClean(room != null ? room : "");

        // 1. Проверяем привязку к слоту времени (если в одно время стоят несколько разных предметов или несколько групп)
        if (dowIdx >= 0 && startTime != null && !startTime.isEmpty()) {
            String slotKey = "SLOT||" + dowIdx + "||" + startTime;
            if (choices.has(slotKey)) {
                String chosenSlot = choices.optString(slotKey, "").trim();
                if (!chosenSlot.isEmpty() && !"ALL".equals(chosenSlot)) {
                    if (!matchesChoiceToken(chosenSlot, sNorm, tNorm, rNorm)) {
                        return false;
                    }
                }
            }
        }

        // 2. Проверяем привязку по названию предмета
        if (!sNorm.isEmpty() && choices.has(sNorm)) {
            String chosenSubj = choices.optString(sNorm, "").trim();
            if (!chosenSubj.isEmpty() && !"ALL".equals(chosenSubj)) {
                if (!matchesChoiceToken(chosenSubj, sNorm, tNorm, rNorm)) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean matchesChoiceToken(String chosenToken, String sNorm, String tNorm, String rNorm) {
        String[] parts = chosenToken.split("\\|\\|");
        if (parts.length >= 3) {
            // Формат: Предмет||Преподаватель||Кабинет
            String cSubj = parts[0].trim();
            String cTeach = parts[1].trim();
            String cRoom = ScheduleManager.formatRoomClean(parts[2].trim());
            if (!cSubj.isEmpty() && !sNorm.isEmpty() && !cSubj.equalsIgnoreCase(sNorm)) return false;
            if (!cTeach.isEmpty() && !tNorm.isEmpty() && !cTeach.equalsIgnoreCase(tNorm)) return false;
            if (cTeach.isEmpty() && !cRoom.isEmpty() && !rNorm.isEmpty() && !cRoom.equalsIgnoreCase(rNorm)) return false;
            return true;
        } else {
            // Формат: Преподаватель||Кабинет
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
