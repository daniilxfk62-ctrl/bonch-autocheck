package ru.sut.bonch;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class ScheduleManager {
    public static final int TOTAL_SEMESTER_WEEKS = 18;
    private static final String[] RU_DAYS_FULL = {
        "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота"
    };
    private static final String[] RU_DAYS_SHORT = {
        "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"
    };

    private static final Map<String, String> PAIR_NUM_TO_TIME = new HashMap<>();
    static {
        PAIR_NUM_TO_TIME.put("1", "09:00-10:35");
        PAIR_NUM_TO_TIME.put("2", "10:45-12:20");
        PAIR_NUM_TO_TIME.put("3", "13:00-14:35");
        PAIR_NUM_TO_TIME.put("4", "14:45-16:20");
        PAIR_NUM_TO_TIME.put("5", "16:30-18:05");
        PAIR_NUM_TO_TIME.put("6", "18:15-19:50");
        PAIR_NUM_TO_TIME.put("7", "20:00-21:35");
    }

    // Точный паттерн корпуса/подкорпуса lk.sut.ru (например "404; Б22/1" -> Б22/1)
    private static final Pattern ROOM_CORPUS_PAT = Pattern.compile(";\\s*([^\\s;]+/(\\d+))");

    public final Set<String> completedWindowsToday = new HashSet<>();
    public final Set<String> waitingTeacherToday = new HashSet<>();
    public final Set<String> markedRaspIdsToday = new HashSet<>();
    public final Set<String> notifiedSkippedToday = new HashSet<>();
    public final Map<String, String> checkInTimeByStart = new HashMap<>();
    public final Map<String, String> checkInByMap = new HashMap<>();
    public final Map<String, Long> plannedDelayTargetMillis = new HashMap<>();
    public final Set<String> todayRealStartTimes = new HashSet<>();
    public volatile boolean lastPollMarkedAny = false;
    public volatile boolean pauseSemesterScan = false;

    private final Random random = new Random();
    private JSONArray semesterDaysArray = new JSONArray();
    private JSONArray smartCatalogArray = new JSONArray();
    private int todayDayIdx = 0;
    private String currentDayStamp = "";

    public void loadCachedFromStorage(AppStorage storage) {
        semesterDaysArray = storage.readJsonArray(AppStorage.FILE_SEMESTER_DAYS);
        smartCatalogArray = storage.readJsonArray(AppStorage.FILE_SMART_CATALOG);
        recalculateTodayIndex(storage);
    }

    public boolean needsSemesterScan() {
        return smartCatalogArray.length() == 0 || semesterDaysArray.length() < 12;
    }

    public int getTodayDayIdx() {
        return todayDayIdx;
    }

    public void checkNewDayReset(AppStorage storage) {
        String today = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());
        if (!today.equals(currentDayStamp)) {
            currentDayStamp = today;
            completedWindowsToday.clear();
            waitingTeacherToday.clear();
            markedRaspIdsToday.clear();
            notifiedSkippedToday.clear();
            checkInTimeByStart.clear();
            checkInByMap.clear();
            plannedDelayTargetMillis.clear();
            recalculateTodayIndex(storage);
        }
    }

    private boolean isDelayElapsed(AppStorage storage, String startTime, String raspId) {
        String delayMode = storage.getAutoDelayMode();
        if ("instant".equals(delayMode)) return true;

        String key = startTime + "_" + raspId;
        long now = System.currentTimeMillis();
        Long target = plannedDelayTargetMillis.get(key);
        if (target == null) {
            long delayMs;
            if ("2m".equals(delayMode)) {
                delayMs = 120000L;
            } else {
                delayMs = (60 + random.nextInt(181)) * 1000L;
            }

            long baseMillis = now;
            try {
                String[] parts = startTime.replace(".", ":").split(":");
                Calendar startCal = Calendar.getInstance();
                startCal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
                startCal.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
                startCal.set(Calendar.SECOND, 0);
                startCal.set(Calendar.MILLISECOND, 0);
                long lessonStartMillis = startCal.getTimeInMillis();
                if (now <= lessonStartMillis + 300000L) {
                    baseMillis = Math.max(lessonStartMillis, now - 15000L);
                } else {
                    baseMillis = now;
                    delayMs = Math.min(delayMs, 45000L);
                }
            } catch (Exception ignored) {}

            target = baseMillis + delayMs;
            plannedDelayTargetMillis.put(key, target);

            if (now < target) {
                String targetTimeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(target));
                storage.addLog("⏳ Задержка отметки (" + delayMode + "): запланировано на " + targetTimeStr);
            }
        }
        return now >= target;
    }

    public synchronized void syncTodayRealStartTimes(AppStorage storage) {
        String todayStr = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());
        synchronized (todayRealStartTimes) {
            todayRealStartTimes.clear();
            if (semesterDaysArray == null || semesterDaysArray.length() == 0) return;

            for (int i = 0; i < semesterDaysArray.length(); i++) {
                JSONObject d = semesterDaysArray.optJSONObject(i);
                if (d != null && todayStr.equals(d.optString("dateStr", ""))) {
                    int dowIdx = d.optInt("dowIdx", -1);
                    JSONArray lessons = d.optJSONArray("lessons");
                    if (lessons != null) {
                        for (int k = 0; k < lessons.length(); k++) {
                            JSONObject les = lessons.optJSONObject(k);
                            if (les == null) continue;
                            String st = les.optString("startTime", "");
                            String rk = les.optString("ruleKey", "");
                            String subj = les.optString("subjectTitle", "");
                            String teach = les.optString("teacher", "");
                            String rm = les.optString("room", "");
                            boolean checked = les.optBoolean("checked", false);
                            boolean waitT = les.optBoolean("waitingTeacher", false);

                            if (!storage.isSubgroupAllowed(dowIdx, st, subj, teach, rm)) {
                                continue;
                            }

                            if (!st.isEmpty() && storage.isRuleAllowed(rk)) {
                                todayRealStartTimes.add(st);
                                if (checked) {
                                    completedWindowsToday.add(st);
                                    waitingTeacherToday.remove(st);
                                    String ct = les.optString("checkTime", "");
                                    if (!ct.isEmpty() && !checkInTimeByStart.containsKey(st)) {
                                        checkInTimeByStart.put(st, ct);
                                    }
                                } else if (waitT) {
                                    waitingTeacherToday.add(st);
                                }
                            }
                        }
                    }
                    break;
                }
            }
        }
    }

    public synchronized void recalculateTodayIndex(AppStorage storage) {
        if (semesterDaysArray == null || semesterDaysArray.length() == 0) {
            todayDayIdx = 0;
            return;
        }
        SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy", Locale.US);
        String todayStr = df.format(new Date());
        for (int i = 0; i < semesterDaysArray.length(); i++) {
            JSONObject d = semesterDaysArray.optJSONObject(i);
            if (d != null && todayStr.equals(d.optString("dateStr", ""))) {
                todayDayIdx = i;
                syncTodayRealStartTimes(storage);
                return;
            }
        }
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date todayDate = cal.getTime();
        for (int i = 0; i < semesterDaysArray.length(); i++) {
            JSONObject d = semesterDaysArray.optJSONObject(i);
            if (d != null) {
                try {
                    Date dDate = df.parse(d.optString("dateStr", ""));
                    if (dDate != null && !dDate.before(todayDate)) {
                        todayDayIdx = i;
                        syncTodayRealStartTimes(storage);
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }
        syncTodayRealStartTimes(storage);
    }

    public String findNextUnmarkedLessonTime(AppStorage storage) {
        syncTodayRealStartTimes(storage);
        Calendar now = Calendar.getInstance();
        int nowMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        String[] sorted;
        synchronized (todayRealStartTimes) {
            if (todayRealStartTimes.isEmpty()) return null;
            sorted = todayRealStartTimes.toArray(new String[0]);
        }
        Arrays.sort(sorted);
        for (String t : sorted) {
            if (completedWindowsToday.contains(t)) continue;
            try {
                String[] p = t.split(":");
                int m = Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
                if (m + 95 >= nowMins) return t;
            } catch (Exception ignored) {}
        }
        return null;
    }

    public String findNextLessonAfter(String currentStart, AppStorage storage) {
        syncTodayRealStartTimes(storage);
        String[] sorted;
        synchronized (todayRealStartTimes) {
            sorted = todayRealStartTimes.toArray(new String[0]);
        }
        Arrays.sort(sorted);
        for (String t : sorted) {
            if (t.compareTo(currentStart) > 0 && !completedWindowsToday.contains(t)) return t;
        }
        return null;
    }

    /**
     * Возвращает краткое описание следующей пары на сегодня:
     * "13:00 • Физика (Лекция) • ауд. 404 |1"
     * Если afterStartTime задан — ищет строго после него; иначе — ближайшую незавершённую пару.
     */
    public synchronized String findNextLessonBrief(String afterStartTime, AppStorage storage) {
        if (semesterDaysArray == null || semesterDaysArray.length() == 0) return null;

        String todayStr = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());
        Calendar now = Calendar.getInstance();
        int nowMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        JSONObject bestLesson = null;
        String bestStart = null;

        for (int i = 0; i < semesterDaysArray.length(); i++) {
            JSONObject d = semesterDaysArray.optJSONObject(i);
            if (d == null || !todayStr.equals(d.optString("dateStr", ""))) continue;

            int dowIdx = d.optInt("dowIdx", -1);
            JSONArray lessons = d.optJSONArray("lessons");
            if (lessons == null) break;

            for (int k = 0; k < lessons.length(); k++) {
                JSONObject les = lessons.optJSONObject(k);
                if (les == null) continue;

                String st = les.optString("startTime", "").trim();
                if (st.isEmpty()) continue;

                String subj = les.optString("subjectTitle", "").trim();
                String teach = les.optString("teacher", "").trim();
                String room = les.optString("room", "").trim();
                if (!storage.isSubgroupAllowed(dowIdx, st, subj, teach, room)) continue;

                boolean alreadyDone = les.optBoolean("checked", false) || completedWindowsToday.contains(st);
                if (alreadyDone) continue;

                if (afterStartTime != null && !afterStartTime.isEmpty()) {
                    if (st.compareTo(afterStartTime) <= 0) continue;
                } else {
                    try {
                        String[] p = st.replace(".", ":").split(":");
                        int startMins = Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
                        if (startMins + 95 < nowMins) continue;
                    } catch (Exception ignored) {}
                }

                if (bestStart == null || st.compareTo(bestStart) < 0) {
                    bestStart = st;
                    bestLesson = les;
                }
            }
            break;
        }

        if (bestLesson == null) return null;

        String st = bestLesson.optString("startTime", "");
        String subj = bestLesson.optString("subjectTitle", "Пара");
        String lType = bestLesson.optString("lessonType", "");
        String room = bestLesson.optString("room", "").trim();

        StringBuilder sb = new StringBuilder();
        sb.append(st).append(" • ").append(subj);
        if (!lType.isEmpty()) {
            sb.append(" (").append(lType).append(")");
        }
        if (!room.isEmpty() && !"-".equals(room) && !"—".equals(room)) {
            sb.append(" • ").append(room);
        }
        return sb.toString();
    }

    public String[] getCurrentWindowState(String mode, AppStorage storage) {
        syncTodayRealStartTimes(storage);
        int windowMin = "first_30".equals(mode) ? 30 : 95;
        Set<String> activeTimes;
        synchronized (todayRealStartTimes) {
            activeTimes = new HashSet<>(todayRealStartTimes);
        }
        Calendar now = Calendar.getInstance();
        long nowMillis = now.getTimeInMillis();
        for (String t : activeTimes) {
            try {
                String[] parts = t.replace(".", ":").split(":");
                Calendar start = (Calendar) now.clone();
                start.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
                start.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
                start.set(Calendar.SECOND, 0);
                double diffMin = (nowMillis - start.getTimeInMillis()) / 60000.0;
                if (diffMin >= -10.0 && diffMin <= windowMin) {
                    String st = String.format(Locale.US, "%02d:%02d", Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                    String label = diffMin < 0
                        ? ("Пара в " + st + " (до начала " + Math.abs((int) diffMin) + " мин)")
                        : ("Пара " + st + " (идет " + (int) diffMin + " мин)");
                    return new String[]{st, label};
                }
            } catch (Exception ignored) {}
        }
        if ("always".equals(mode)) {
            return new String[]{
                "always_" + new SimpleDateFormat("HH", Locale.US).format(new Date()),
                "Постоянный режим"
            };
        }
        return null;
    }

    public void checkAndNotifySkippedLessonsToday(Context ctx, AppStorage storage) {
        if (!storage.isNotifySkippedEnabled()) return;
        if (semesterDaysArray == null || semesterDaysArray.length() == 0) return;

        String todayStr = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());
        Calendar now = Calendar.getInstance();
        long nowMillis = now.getTimeInMillis();

        for (int i = 0; i < semesterDaysArray.length(); i++) {
            JSONObject d = semesterDaysArray.optJSONObject(i);
            if (d == null || !todayStr.equals(d.optString("dateStr", ""))) continue;

            int dowIdx = d.optInt("dowIdx", -1);
            JSONArray lessons = d.optJSONArray("lessons");
            if (lessons == null) break;

            for (int k = 0; k < lessons.length(); k++) {
                JSONObject les = lessons.optJSONObject(k);
                if (les == null) continue;
                String st = les.optString("startTime", "");
                String rk = les.optString("ruleKey", "");
                String subj = les.optString("subjectTitle", "Пара");
                String lType = les.optString("lessonType", "Занятие");
                String teacher = les.optString("teacher", "");
                String room = les.optString("room", "");

                if (!storage.isSubgroupAllowed(dowIdx, st, subj, teacher, room)) continue;

                if (!st.isEmpty() && !storage.isRuleAllowed(rk)) {
                    String uniqueKey = todayStr + "_" + st + "_" + rk;
                    if (notifiedSkippedToday.contains(uniqueKey)) continue;

                    try {
                        String[] parts = st.replace(".", ":").split(":");
                        Calendar start = (Calendar) now.clone();
                        start.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
                        start.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
                        start.set(Calendar.SECOND, 0);
                        double diffMin = (nowMillis - start.getTimeInMillis()) / 60000.0;

                        if (diffMin >= -10.0 && diffMin <= 45.0) {
                            notifiedSkippedToday.add(uniqueKey);
                            String title = "🔔 Пара без автоотметки (" + st + ")";
                            String msg = subj + " • " + lType + (teacher.isEmpty() ? "" : " (" + teacher + ")")
                                + " — автоотметка выключена.";
                            storage.addLog(title + ": " + msg);
                            NotificationHelper.sendAlert(ctx, title, msg);
                        }
                    } catch (Exception ignored) {}
                }
            }
            break;
        }
    }

    /**
     * Форматирует аудиторию из lk.sut.ru:
     * "702; Б22/1" -> "702 |1"
     * "Спортивный комплекс; Б22/1" -> "Спортивный комплекс"
     */
    public static String formatRoomClean(String rawRoom) {
        if (rawRoom == null) return "";
        String s = rawRoom
            .replace("&nbsp;", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (s.isEmpty() || "-".equals(s) || "—".equals(s)) return "";

        // Для физкультуры оставляем только название спортивного комплекса
        if (s.toLowerCase(Locale.ROOT).contains("спорт")) {
            String sportPart = s.split("[;·]")[0].trim();
            return sportPart.isEmpty() ? "Спорткомплекс" : sportPart;
        }

        // Приводим "пр. Большевиков, 22/1" -> "Б22/1"
        s = s.replaceAll("(?i)пр\\.?\\s*Большевиков[,\\s]*22\\s*/\\s*(\\d+)", "Б22/$1");
        s = s.replaceAll("(?i)пр\\.?\\s*Большевиков[,\\s]*22\\b", "Б22");

        // Паттерн "702; Б22/1" или "702 Б22/1" -> "702 |1"
        Matcher cm = Pattern.compile("(?:;|\\s+)[^\\s;]+/(\\d+)\\b").matcher(s);
        if (cm.find()) {
            String corpusNum = cm.group(1).trim();
            String roomPart = s.substring(0, cm.start()).replaceAll("^[;,·\\s]+|[;,·\\s]+$", "").trim();
            if (!roomPart.isEmpty()) {
                return roomPart + " |" + corpusNum;
            }
            return "|" + corpusNum;
        }

        s = s.replaceAll("\\s*;\\s*", " ");
        s = s.replaceAll("^[;,·\\s]+|[;,·\\s]+$", "");
        return s.trim();
    }

    private static String mergeRooms(String r1, String r2) {
        String a = r1 != null ? r1.trim() : "";
        String b = r2 != null ? r2.trim() : "";
        if (a.isEmpty()) return b;
        if (b.isEmpty() || a.equalsIgnoreCase(b)) return a;

        int pipeA = a.lastIndexOf('|');
        int pipeB = b.lastIndexOf('|');
        if (pipeA > 0 && pipeB > 0) {
            String corpA = a.substring(pipeA).trim();
            String corpB = b.substring(pipeB).trim();
            if (corpA.equalsIgnoreCase(corpB)) {
                String numA = a.substring(0, pipeA).trim();
                String numB = b.substring(0, pipeB).trim();
                List<String> nums = new ArrayList<>();
                for (String p : numA.split("/")) {
                    String t = p.trim();
                    if (!t.isEmpty() && !nums.contains(t)) nums.add(t);
                }
                for (String p : numB.split("/")) {
                    String t = p.trim();
                    if (!t.isEmpty() && !nums.contains(t)) nums.add(t);
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < nums.size(); i++) {
                    if (i > 0) sb.append("/");
                    sb.append(nums.get(i));
                }
                return sb.toString() + " " + corpA;
            }
        }

        if (!a.contains(b)) return a + " / " + b;
        return a;
    }

    private static List<JSONObject> mergeDuplicateLessons(List<JSONObject> rawLessons) {
        if (rawLessons == null || rawLessons.size() <= 1) return rawLessons;
        Map<String, JSONObject> merged = new LinkedHashMap<>();
        for (JSONObject les : rawLessons) {
            if (les == null) continue;
            String key = les.optString("startTime", "")
                + "||" + les.optString("subjectTitle", "").trim().toLowerCase(Locale.ROOT)
                + "||" + les.optString("lessonType", "").trim().toLowerCase(Locale.ROOT)
                + "||" + les.optString("teacher", "").trim().toLowerCase(Locale.ROOT);
            JSONObject existing = merged.get(key);
            if (existing == null) {
                merged.put(key, les);
            } else {
                try {
                    String newRoom = mergeRooms(existing.optString("room", ""), les.optString("room", ""));
                    existing.put("room", newRoom);

                    String t1 = existing.optString("teacher", "").trim();
                    String t2 = les.optString("teacher", "").trim();
                    if (!t2.isEmpty() && !t1.contains(t2)) {
                        existing.put("teacher", t1.isEmpty() ? t2 : (t1 + ", " + t2));
                    }

                    boolean checked = existing.optBoolean("checked", false) || les.optBoolean("checked", false);
                    boolean waitT = (existing.optBoolean("waitingTeacher", false) || les.optBoolean("waitingTeacher", false)) && !checked;
                    existing.put("checked", checked);
                    existing.put("waitingTeacher", waitT);

                    if (existing.optString("raspId", "").isEmpty() && !les.optString("raspId", "").isEmpty()) {
                        existing.put("raspId", les.optString("raspId", ""));
                        existing.put("weekId", les.optString("weekId", ""));
                    }
                    if (existing.optString("checkTime", "").isEmpty() && !les.optString("checkTime", "").isEmpty()) {
                        existing.put("checkTime", les.optString("checkTime", ""));
                    }
                } catch (Exception ignored) {}
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static String normalizeDateStr(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] p = raw.trim().split("\\.");
        if (p.length != 3) return null;
        try {
            int dd = Integer.parseInt(p[0]);
            int mm = Integer.parseInt(p[1]);
            int yy = Integer.parseInt(p[2]);
            if (yy < 100) yy += 2000;
            return String.format(Locale.US, "%02d.%02d.%04d", dd, mm, yy);
        } catch (Exception e) {
            return null;
        }
    }

    private static int dayNameToDowIdx(String dayName) {
        if (dayName == null) return -1;
        String low = dayName.toLowerCase(Locale.ROOT);
        if (low.contains("понедельник")) return 0;
        if (low.contains("вторник")) return 1;
        if (low.contains("среда")) return 2;
        if (low.contains("четверг")) return 3;
        if (low.contains("пятница")) return 4;
        if (low.contains("суббота")) return 5;
        if (low.contains("воскресенье")) return 6;
        return -1;
    }

    private static String inferDateForCurrentWeekDow(int dowIdx) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        int dow = cal.get(Calendar.DAY_OF_WEEK);
        int diffToMon = (dow == Calendar.SUNDAY) ? -6 : (Calendar.MONDAY - dow);
        cal.add(Calendar.DAY_OF_MONTH, diffToMon + Math.max(0, dowIdx));
        return new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(cal.getTime());
    }

    public static class ParsedRowDetails {
        public String subject = "Предмет";
        public String normType = "Практика";
        public String teacher = "";
        public String room = "";
        public String ruleKey = "";
    }

    private static String cleanCellPlain(String cellHtml) {
        return cellHtml
            .replaceAll("(?i)<br\\s*/?>", " | ")
            .replaceAll("<[^>]+>", " ")
            .replace("&nbsp;", " ")
            .replaceAll("(?i)(занятие\\s+началось.*|ждем\\s+начала.*|ждём\\s+начала.*|кнопка\\s+появится.*|начать\\s+занятие.*|обновить.*|вы\\s+присутствуете.*|отметиться.*)", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    public List<ParsedRowDetails> extractStructuredLessonsFromRow(String trInner, String timeRange) {
        ParsedRowDetails base = new ParsedRowDetails();
        List<String> tds = new ArrayList<>();
        Pattern tdPat = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher tdMat = tdPat.matcher(trInner);
        while (tdMat.find()) {
            tds.add(tdMat.group(1));
        }

        Pattern boldPat = Pattern.compile("<(b|strong)[^>]*>(.*?)</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern fioShortPat = Pattern.compile("([А-ЯЁ][а-яё\\-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ](?:\\.?-[А-ЯЁ])?\\.)");
        Pattern fioFullPat = Pattern.compile("([А-ЯЁ][а-яё\\-]{2,}\\s+[А-ЯЁ][а-яё]{2,}\\s+[А-ЯЁ][а-яё]{2,}(?:вич|вна|ична))");
        Pattern roomHintPat = Pattern.compile("(?i)(?:;\\s*[^\\s;]+/\\d+|[БбBb]22|\\b\\d{2,4}[а-яa-z]?\\b\\s*(?:;|/)|ауд\\.|каб\\.|дистанционно|спорт|зал)");

        List<String> boldSubjects = new ArrayList<>();
        int subjectCellIdx = -1;
        for (int i = 0; i < tds.size(); i++) {
            String cellHtml = tds.get(i);
            Matcher bm = boldPat.matcher(cellHtml);
            while (bm.find()) {
                String bText = bm.group(2).replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
                if (bText.length() > 2 && !bText.matches("^[0-9:–\\-\\s()]+$")) {
                    boldSubjects.add(bText);
                    if (subjectCellIdx == -1) {
                        base.subject = bText;
                        subjectCellIdx = i;
                    }
                }
            }
        }

        String fullRowPlain = trInner.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
        String lowerRow = fullRowPlain.toLowerCase(Locale.ROOT);
        if (lowerRow.contains("лабораторн")) {
            base.normType = "Лаба";
        } else if (lowerRow.contains("лекци")) {
            base.normType = "Лекция";
        } else {
            base.normType = "Практика";
        }

        if ("Предмет".equals(base.subject)) {
            for (int i = 0; i < tds.size(); i++) {
                String cp = tds.get(i).replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
                if (cp.contains(timeRange) || cp.matches("^\\d+$")) continue;
                if (cp.length() > 4) {
                    String cleaned = cp
                        .replace(timeRange, "")
                        .replaceAll("(?i)(Лабораторная работа|Практические занятия|Лекция|Зачет|Экзамен|Консультация).*", "")
                        .replaceAll("(?i)(занятие\\s+началось.*|ждем\\s+начала.*|ждём\\s+начала.*)", "")
                        .replaceFirst("^\\s*\\d+\\s*\\(\\s*\\)\\s*", "")
                        .trim();
                    if (!cleaned.isEmpty()) {
                        base.subject = cleaned;
                        subjectCellIdx = i;
                        break;
                    }
                }
            }
        }

        List<String> foundTeachers = new ArrayList<>();
        List<String> foundRooms = new ArrayList<>();

        // 1. Приоритетно извлекаем аудиторию из 4-го <td> (индекс 3), как в raspisanie.php ("404; Б22/1")
        int roomCellIdx = -1;
        if (tds.size() >= 4 && subjectCellIdx != 3) {
            String fourthPlain = cleanCellPlain(tds.get(3));
            if (!fourthPlain.isEmpty()
                && !fourthPlain.equals(timeRange)
                && !fourthPlain.matches("^\\d+\\s+[0-9]{1,2}[:.][0-9]{2}.*")
                && !fioShortPat.matcher(fourthPlain).find()
                && !fioFullPat.matcher(fourthPlain).find()) {
                for (String rp : fourthPlain.split("\\|")) {
                    String cr = formatRoomClean(rp);
                    if (!cr.isEmpty() && !foundRooms.contains(cr)) {
                        foundRooms.add(cr);
                        roomCellIdx = 3;
                    }
                }
            }
        }

        // 2. Разбираем остальные ячейки (преподаватели + резервный поиск аудитории)
        for (int i = 0; i < tds.size(); i++) {
            if (i == subjectCellIdx || i == roomCellIdx) continue;
            String cellPlain = cleanCellPlain(tds.get(i));

            if (cellPlain.isEmpty() || cellPlain.equals(timeRange) || cellPlain.matches("^\\d+\\s*\\(?\\s*\\)?$")) {
                continue;
            }
            if (cellPlain.matches("^\\d+\\s+[0-9]{1,2}[:.][0-9]{2}.*")) {
                continue;
            }

            Matcher ms = fioShortPat.matcher(cellPlain);
            boolean matchedAnyFio = false;
            while (ms.find()) {
                String t = ms.group(1).trim();
                if (!foundTeachers.contains(t)) foundTeachers.add(t);
                matchedAnyFio = true;
            }
            if (!matchedAnyFio) {
                Matcher mf = fioFullPat.matcher(cellPlain);
                while (mf.find()) {
                    String t = mf.group(1).trim();
                    if (!foundTeachers.contains(t)) foundTeachers.add(t);
                    matchedAnyFio = true;
                }
            }
            if (matchedAnyFio) continue;

            if (foundRooms.isEmpty() && (roomHintPat.matcher(cellPlain).find() || ROOM_CORPUS_PAT.matcher(cellPlain).find())) {
                for (String rp : cellPlain.split("\\|")) {
                    String cr = formatRoomClean(rp);
                    if (!cr.isEmpty() && !foundRooms.contains(cr)) {
                        foundRooms.add(cr);
                    }
                }
                continue;
            }

            if (foundTeachers.isEmpty() && subjectCellIdx != -1 && i > subjectCellIdx && cellPlain.matches(".*[А-Яа-яЁё]{3,}.*")) {
                if (!cellPlain.toLowerCase(Locale.ROOT).contains("б22") && !ROOM_CORPUS_PAT.matcher(cellPlain).find()) {
                    foundTeachers.add(cellPlain);
                }
            }
        }

        if (foundTeachers.isEmpty()) {
            Matcher ms = fioShortPat.matcher(fullRowPlain);
            while (ms.find()) {
                String t = ms.group(1).trim();
                if (!foundTeachers.contains(t)) foundTeachers.add(t);
            }
            if (foundTeachers.isEmpty()) {
                Matcher mf = fioFullPat.matcher(fullRowPlain);
                while (mf.find()) {
                    String t = mf.group(1).trim();
                    if (!foundTeachers.contains(t)) foundTeachers.add(t);
                }
            }
        }

        if (foundRooms.isEmpty()) {
            Pattern inlineRoomPat = Pattern.compile("(\\d{2,4}[а-яa-z]?\\s*;\\s*[^\\s;]+/\\d+)");
            Matcher rm = inlineRoomPat.matcher(fullRowPlain);
            while (rm.find()) {
                String cr = formatRoomClean(rm.group(1));
                if (!cr.isEmpty() && !foundRooms.contains(cr)) foundRooms.add(cr);
            }
        }

        for (String ft : foundTeachers) {
            if (base.subject.contains(ft)) {
                base.subject = base.subject.replace(ft, "").trim();
            }
        }

        List<ParsedRowDetails> resultList = new ArrayList<>();
        int countVariants = Math.max(1, Math.max(foundTeachers.size(), boldSubjects.size()));
        if (countVariants > 1 && (foundTeachers.size() > 1 || foundRooms.size() > 1 || boldSubjects.size() > 1)) {
            for (int v = 0; v < countVariants; v++) {
                ParsedRowDetails item = new ParsedRowDetails();
                item.subject = v < boldSubjects.size() ? boldSubjects.get(v) : base.subject;
                item.normType = base.normType;
                item.teacher = v < foundTeachers.size() ? foundTeachers.get(v) : (foundTeachers.isEmpty() ? "" : foundTeachers.get(0));
                item.room = v < foundRooms.size() ? foundRooms.get(v) : (foundRooms.isEmpty() ? "" : foundRooms.get(0));
                item.ruleKey = item.subject + "||" + item.normType;
                resultList.add(item);
            }
        } else {
            base.teacher = foundTeachers.isEmpty() ? "" : foundTeachers.get(0);
            base.room = foundRooms.isEmpty() ? "" : foundRooms.get(0);
            base.ruleKey = base.subject + "||" + base.normType;
            resultList.add(base);
        }
        return resultList;
    }

    private int calcDowIdxFromDate(Date d) {
        if (d == null) return 0;
        Calendar c = Calendar.getInstance();
        c.setTime(d);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        return (dow == Calendar.SUNDAY) ? 6 : (dow - Calendar.MONDAY);
    }

    private static String extractEndTime(String timeRange, String startTime) {
        if (timeRange != null) {
            Matcher m = Pattern.compile("[-–—]\\s*([01]?\\d|2[0-3])[:.]([0-5]\\d)").matcher(timeRange);
            if (m.find()) {
                return String.format(Locale.US, "%02d:%02d", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
        }
        try {
            String[] p = startTime.split(":");
            int totalMins = Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]) + 95;
            return String.format(Locale.US, "%02d:%02d", (totalMins / 60) % 24, totalMins % 60);
        } catch (Exception e) {
            return "";
        }
    }

    private String fetchScheduleHtmlWithFallback(SutApiClient api, AppStorage storage, String login, String password, String weekParam) throws Exception {
        String url = (weekParam == null || weekParam.isEmpty())
            ? SutApiClient.SCHEDULE_URL
            : (SutApiClient.SCHEDULE_URL + "?" + weekParam);
        String body = (weekParam == null) ? "" : weekParam;

        String html = api.httpRequest("POST", url, body);
        if (!isValidScheduleHtml(html)) {
            String getHtml = api.httpRequest("GET", url, null);
            if (isValidScheduleHtml(getHtml)) {
                return getHtml;
            }
            storage.addLog("Сессия lk.sut.ru не отвечает или истекла — переподключение...");
            api.invalidateSession();
            if (api.authenticate(login, password, storage)) {
                html = api.httpRequest("POST", url, body);
                if (!isValidScheduleHtml(html)) {
                    html = api.httpRequest("GET", url, null);
                }
            } else {
                storage.addLog("Не удалось переподключиться к lk.sut.ru: " + api.getLastAuthError());
            }
        }
        return html != null ? html : "";
    }

    private boolean isValidScheduleHtml(String html) {
        if (html == null || html.trim().length() < 50) return false;
        String low = html.toLowerCase(Locale.ROOT);
        if (low.contains("autentification") || low.contains("name=\"parole\"")) return false;
        return low.contains("<table") || low.contains("<tr");
    }

    public synchronized boolean manualCheckIn(
        Context ctx,
        SutApiClient api,
        AppStorage storage,
        String login,
        String password,
        String raspId,
        String weekId,
        String startTime,
        String subjectTitle
    ) {
        if (!api.isAuthenticated() && !api.authenticate(login, password, storage)) {
            storage.addLog("Ручная отметка: ошибка входа в ЛК");
            return false;
        }

        try {
            if (raspId != null && !raspId.isEmpty() && weekId != null && !weekId.isEmpty()) {
                if (api.sendAndVerifyOpenZan(raspId, weekId)) {
                    String nowStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                    markedRaspIdsToday.add(raspId);
                    if (startTime != null && !startTime.isEmpty()) {
                        completedWindowsToday.add(startTime);
                        waitingTeacherToday.remove(startTime);
                        checkInTimeByStart.put(startTime, nowStr);
                        checkInByMap.put(startTime, "manual");
                    }
                    markLessonCheckedInCache(storage, startTime, subjectTitle, nowStr, "manual");
                    storage.addLog("✅ Ручная отметка выполнена: " + subjectTitle + " (" + nowStr + ")");
                    return true;
                }
            }

            String html = fetchScheduleHtmlWithFallback(api, storage, login, password, null);
            Pattern trPat = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern zanPat = Pattern.compile("open_zan\\s*\\(\\s*['\"]?(\\d+)['\"]?\\s*,\\s*['\"]?(\\d+)['\"]?\\s*\\)");
            Matcher trMat = trPat.matcher(html);
            while (trMat.find()) {
                String trInner = trMat.group(1);
                Matcher zm = zanPat.matcher(trInner);
                if (!zm.find()) continue;

                String rowPlain = trInner.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                boolean matches = (startTime != null && !startTime.isEmpty() && rowPlain.contains(startTime))
                    || (subjectTitle != null && !subjectTitle.isEmpty() && rowPlain.toLowerCase(Locale.ROOT).contains(subjectTitle.toLowerCase(Locale.ROOT)));

                if (matches) {
                    String foundRasp = zm.group(1);
                    String foundWeek = zm.group(2);
                    if (api.sendAndVerifyOpenZan(foundRasp, foundWeek)) {
                        String nowStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                        markedRaspIdsToday.add(foundRasp);
                        if (startTime != null && !startTime.isEmpty()) {
                            completedWindowsToday.add(startTime);
                            waitingTeacherToday.remove(startTime);
                            checkInTimeByStart.put(startTime, nowStr);
                            checkInByMap.put(startTime, "manual");
                        }
                        markLessonCheckedInCache(storage, startTime, subjectTitle, nowStr, "manual");
                        storage.addLog("✅ Ручная отметка выполнена: " + subjectTitle + " (" + nowStr + ")");
                        return true;
                    }
                }
            }

            storage.addLog("Кнопка «Отметиться» для «" + subjectTitle + "» пока не открыта в ЛК.");
            return false;
        } catch (Exception e) {
            storage.addLog("Ошибка ручной отметки: " + e.getMessage());
            return false;
        }
    }

    private void markLessonCheckedInCache(AppStorage storage, String startTime, String subjectTitle, String timeStr, String checkedBy) {
        try {
            String todayStr = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());
            for (int i = 0; i < semesterDaysArray.length(); i++) {
                JSONObject d = semesterDaysArray.optJSONObject(i);
                if (d == null || !todayStr.equals(d.optString("dateStr", ""))) continue;
                JSONArray lessons = d.optJSONArray("lessons");
                if (lessons == null) break;
                for (int k = 0; k < lessons.length(); k++) {
                    JSONObject les = lessons.optJSONObject(k);
                    if (les == null) continue;
                    if ((startTime != null && startTime.equals(les.optString("startTime", "")))
                        && (subjectTitle == null || subjectTitle.isEmpty() || subjectTitle.equals(les.optString("subjectTitle", "")))) {
                        les.put("checked", true);
                        les.put("waitingTeacher", false);
                        les.put("checkTime", timeStr);
                        les.put("checkedBy", checkedBy);
                    }
                }
                break;
            }
            storage.writeText(AppStorage.FILE_SEMESTER_DAYS, semesterDaysArray.toString());
        } catch (Exception ignored) {}
    }

    public synchronized void pollCurrentWeekAndCheckIn(
        Context ctx,
        SutApiClient api,
        AppStorage storage,
        String login,
        String password,
        String currentWindowStart
    ) {
        lastPollMarkedAny = false;
        if (!api.isAuthenticated() && !api.authenticate(login, password, storage)) {
            return;
        }
        try {
            String html = fetchScheduleHtmlWithFallback(api, storage, login, password, null);
            if (!isValidScheduleHtml(html)) {
                storage.addLog("Расписание не получено (пустой ответ от ЛК).");
                return;
            }

            Pattern trPattern = Pattern.compile("<tr([^>]*)>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern dayNamePattern = Pattern.compile("(Понедельник|Вторник|Среда|Четверг|Пятница|Суббота|Воскресенье)", Pattern.CASE_INSENSITIVE);
            Pattern dateValPattern = Pattern.compile("(\\d{1,2}\\.\\d{2}\\.\\d{2,4})");
            Pattern timePattern = Pattern.compile("([01]?\\d|2[0-3])[:.]([0-5]\\d)\\s*[-–—]\\s*([01]?\\d|2[0-3])[:.]([0-5]\\d)");
            Pattern pairNumPattern = Pattern.compile("^\\s*([1-7])\\s*(?:пара|\\(\\s*\\))", Pattern.CASE_INSENSITIVE);
            Pattern zanPattern = Pattern.compile("open_zan\\s*\\(\\s*['\"]?(\\d+)['\"]?\\s*,\\s*['\"]?(\\d+)['\"]?\\s*\\)");
            Pattern startedPattern = Pattern.compile("занятие\\s+началось\\s*(\\d{1,2}[:.]\\d{2})", Pattern.CASE_INSENSITIVE);
            Pattern waitBtnPattern = Pattern.compile("Кнопка\\s+появится\\s+в\\s*(\\d{1,2}[:.]\\d{2})", Pattern.CASE_INSENSITIVE);
            Pattern boldCheckPat = Pattern.compile("<(b|strong)[^>]*>.*[А-Яа-яЁёA-Za-z]{3,}.*</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

            Matcher trMatcher = trPattern.matcher(html);
            SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy", Locale.US);
            SimpleDateFormat shortDateFmt = new SimpleDateFormat("dd.MM", Locale.US);
            String todayStr = df.format(new Date());

            Map<String, List<JSONObject>> weekLessonsByDate = new LinkedHashMap<>();
            Date anyDateInWeek = null;
            String curDateStr = null;
            int curDowIdx = 0;
            String lastTimeRange = "";
            String lastStartTime = "";
            List<String> newlyChecked = new ArrayList<>();
            int totalParsedLessons = 0;

            boolean daemonEnabled = storage.isDaemonEnabled();

            while (trMatcher.find()) {
                String trAttrs = trMatcher.group(1);
                String trInner = trMatcher.group(2);
                String fullTrHtml = "<tr" + trAttrs + ">" + trInner + "</tr>";

                String rowText = trInner.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
                if (rowText.isEmpty()) continue;

                Matcher tm = timePattern.matcher(rowText);
                Matcher dm = dayNamePattern.matcher(rowText);
                Matcher dateM = dateValPattern.matcher(rowText);

                boolean hasTime = tm.find();
                boolean hasDayName = dm.find();
                boolean hasDate = dateM.find();

                if (hasDayName && !hasTime && rowText.length() < 160) {
                    String normDate = hasDate ? normalizeDateStr(dateM.group(1)) : null;
                    int dIdx = dayNameToDowIdx(dm.group(1));
                    if (normDate == null && dIdx >= 0) {
                        normDate = inferDateForCurrentWeekDow(dIdx);
                    }
                    if (normDate != null) {
                        curDateStr = normDate;
                        lastTimeRange = "";
                        lastStartTime = "";
                        if (!weekLessonsByDate.containsKey(curDateStr)) {
                            weekLessonsByDate.put(curDateStr, new ArrayList<JSONObject>());
                        }
                        try {
                            Date parsedD = df.parse(curDateStr);
                            curDowIdx = calcDowIdxFromDate(parsedD);
                            if (anyDateInWeek == null) anyDateInWeek = parsedD;
                        } catch (Exception ignored) {}
                    }
                    continue;
                }

                if (hasDayName && hasDate && hasTime) {
                    String normDate = normalizeDateStr(dateM.group(1));
                    if (normDate != null) {
                        curDateStr = normDate;
                        if (!weekLessonsByDate.containsKey(curDateStr)) {
                            weekLessonsByDate.put(curDateStr, new ArrayList<JSONObject>());
                        }
                        try {
                            Date parsedD = df.parse(curDateStr);
                            curDowIdx = calcDowIdxFromDate(parsedD);
                            if (anyDateInWeek == null) anyDateInWeek = parsedD;
                        } catch (Exception ignored) {}
                    }
                }

                String timeRange = null;
                String startTime = null;

                if (hasTime) {
                    timeRange = tm.group(0);
                    int startH = Integer.parseInt(tm.group(1));
                    int startM = Integer.parseInt(tm.group(2));
                    startTime = String.format(Locale.US, "%02d:%02d", startH, startM);
                    lastTimeRange = timeRange;
                    lastStartTime = startTime;
                } else {
                    Matcher pm = pairNumPattern.matcher(rowText);
                    if (pm.find() && PAIR_NUM_TO_TIME.containsKey(pm.group(1))) {
                        timeRange = PAIR_NUM_TO_TIME.get(pm.group(1));
                        startTime = timeRange.substring(0, 5);
                        lastTimeRange = timeRange;
                        lastStartTime = startTime;
                    } else if (curDateStr != null && !lastStartTime.isEmpty() && boldCheckPat.matcher(trInner).find()) {
                        timeRange = lastTimeRange;
                        startTime = lastStartTime;
                    } else {
                        continue;
                    }
                }

                if (curDateStr == null) {
                    curDateStr = todayStr;
                    curDowIdx = calcDowIdxFromDate(new Date());
                    if (!weekLessonsByDate.containsKey(curDateStr)) {
                        weekLessonsByDate.put(curDateStr, new ArrayList<JSONObject>());
                    }
                    if (anyDateInWeek == null) anyDateInWeek = new Date();
                }

                String endTime = extractEndTime(timeRange, startTime);
                List<ParsedRowDetails> variants = extractStructuredLessonsFromRow(trInner, timeRange);

                Matcher zm = zanPattern.matcher(fullTrHtml);
                String raspId = zm.find() ? zm.group(1) : null;
                String weekId = (raspId != null) ? zm.group(2) : null;

                Matcher sm = startedPattern.matcher(rowText);
                String startedAt = sm.find() ? sm.group(1) : null;
                Matcher wm = waitBtnPattern.matcher(rowText);
                String btnAppearTime = wm.find() ? wm.group(1) : null;

                String lowerRow = rowText.toLowerCase(Locale.ROOT);
                boolean waitingForTeacher = lowerRow.contains("ждем начала от преподавателя")
                                         || lowerRow.contains("ждём начала от преподавателя");
                boolean alreadyInHtml = lowerRow.contains("вы присутствуете")
                                     || lowerRow.contains("отмечен")
                                     || lowerRow.contains("присутствует");

                for (ParsedRowDetails structured : variants) {
                    if ("Предмет".equals(structured.subject)) continue;
                    boolean allowedBySmart = storage.isRuleAllowed(structured.ruleKey);
                    boolean allowedBySubgroup = storage.isSubgroupAllowed(curDowIdx, startTime, structured.subject, structured.teacher, structured.room);

                    if (allowedBySubgroup && daemonEnabled) {
                        if (!allowedBySmart) {
                            if (raspId != null && storage.isNotifySkippedEnabled() && todayStr.equals(curDateStr)) {
                                String btnNotifyKey = "btn_" + todayStr + "_" + startTime + "_" + structured.ruleKey;
                                if (!notifiedSkippedToday.contains(btnNotifyKey)) {
                                    notifiedSkippedToday.add(btnNotifyKey);
                                    String title = "🔔 Открылась отметка на паре (" + startTime + ")";
                                    String msg = structured.subject + " (" + structured.normType + ") — кнопка появилась в ЛК, но автоотметка выключена.";
                                    storage.addLog(title + ": " + msg);
                                    NotificationHelper.sendAlert(ctx, title, msg);
                                }
                            }
                            if (currentWindowStart != null && currentWindowStart.equals(startTime)) {
                                completedWindowsToday.add(startTime);
                                waitingTeacherToday.remove(startTime);
                                checkInTimeByStart.put(startTime, "пропуск");
                            }
                        } else if (raspId != null && weekId != null && !alreadyInHtml && !markedRaspIdsToday.contains(raspId)) {
                            if (isDelayElapsed(storage, startTime, raspId)) {
                                if (api.sendAndVerifyOpenZan(raspId, weekId)) {
                                    String nowStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                                    markedRaspIdsToday.add(raspId);
                                    checkInTimeByStart.put(startTime, nowStr);
                                    checkInByMap.put(startTime, "auto");
                                    completedWindowsToday.add(startTime);
                                    waitingTeacherToday.remove(startTime);
                                    alreadyInHtml = true;
                                    waitingForTeacher = false;
                                    lastPollMarkedAny = true;
                                    newlyChecked.add(structured.subject + " (" + structured.normType + ", в " + nowStr + ")");
                                }
                            }
                        }
                    }

                    boolean hasTimeAppeared = (startedAt != null && raspId == null && !waitingForTeacher);
                    boolean isLessonDone = alreadyInHtml
                                        || hasTimeAppeared
                                        || (raspId != null && markedRaspIdsToday.contains(raspId));

                    String displayCheckTime = checkInTimeByStart.get(startTime);
                    if (displayCheckTime == null && startedAt != null) displayCheckTime = startedAt;
                    if (isLessonDone && displayCheckTime == null) {
                        displayCheckTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                    }

                    if (todayStr.equals(curDateStr) && allowedBySubgroup) {
                        if (isLessonDone) {
                            completedWindowsToday.add(startTime);
                            waitingTeacherToday.remove(startTime);
                            if (displayCheckTime != null) checkInTimeByStart.put(startTime, displayCheckTime);
                        } else if (waitingForTeacher) {
                            waitingTeacherToday.add(startTime);
                        } else {
                            waitingTeacherToday.remove(startTime);
                        }
                    }

                    JSONObject item = new JSONObject();
                    item.put("time", timeRange);
                    item.put("startTime", startTime);
                    item.put("endTime", endTime);
                    item.put("subjectTitle", structured.subject);
                    item.put("lessonType", structured.normType);
                    item.put("teacher", structured.teacher);
                    item.put("room", structured.room);
                    item.put("ruleKey", structured.ruleKey);
                    item.put("raspId", raspId != null ? raspId : "");
                    item.put("weekId", weekId != null ? weekId : "");
                    item.put("checked", isLessonDone);
                    item.put("checkedBy", checkInByMap.containsKey(startTime) ? checkInByMap.get(startTime) : (isLessonDone ? "auto" : ""));
                    item.put("waitingTeacher", waitingForTeacher && !isLessonDone);
                    item.put("checkTime", displayCheckTime != null ? displayCheckTime : "");
                    item.put("waitBtnTime", btnAppearTime != null ? btnAppearTime : "");
                    weekLessonsByDate.get(curDateStr).add(item);
                    totalParsedLessons++;
                }
            }

            if (anyDateInWeek != null) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(anyDateInWeek);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                int dow = cal.get(Calendar.DAY_OF_WEEK);
                int diffToMon = (dow == Calendar.SUNDAY) ? -6 : (Calendar.MONDAY - dow);
                cal.add(Calendar.DAY_OF_MONTH, diffToMon);

                Map<String, JSONObject> currentWeekDays = new LinkedHashMap<>();
                for (int dIdx = 0; dIdx < 6; dIdx++) {
                    Date dDate = cal.getTime();
                    String dStr = df.format(dDate);
                    String dShortDate = shortDateFmt.format(dDate);
                    List<JSONObject> dayLessons = weekLessonsByDate.get(dStr);
                    if (dayLessons == null) dayLessons = new ArrayList<>();

                    JSONObject dayJson = new JSONObject();
                    dayJson.put("weekNum", 0);
                    dayJson.put("dowIdx", dIdx);
                    dayJson.put("shortDow", RU_DAYS_SHORT[dIdx]);
                    dayJson.put("shortDate", dShortDate);
                    dayJson.put("dateStr", dStr);
                    dayJson.put("title", RU_DAYS_FULL[dIdx] + " " + dStr);
                    dayJson.put("isOrange", todayStr.equals(dStr));
                    dayLessons = mergeDuplicateLessons(dayLessons);
                    dayJson.put("lessons", new JSONArray(dayLessons));
                    currentWeekDays.put(dStr, dayJson);
                    cal.add(Calendar.DAY_OF_MONTH, 1);
                }

                if (semesterDaysArray.length() == 0) {
                    JSONArray arr = new JSONArray();
                    for (JSONObject dObj : currentWeekDays.values()) arr.put(dObj);
                    semesterDaysArray = arr;
                } else {
                    for (int i = 0; i < semesterDaysArray.length(); i++) {
                        JSONObject existingDay = semesterDaysArray.optJSONObject(i);
                        if (existingDay != null) {
                            String ds = existingDay.optString("dateStr", "");
                            if (currentWeekDays.containsKey(ds)) {
                                JSONObject updated = currentWeekDays.get(ds);
                                updated.put("weekNum", existingDay.optInt("weekNum", 1));
                                semesterDaysArray.put(i, updated);
                            }
                        }
                    }
                }
                recalculateTodayIndex(storage);
                storage.writeText(AppStorage.FILE_SEMESTER_DAYS, semesterDaysArray.toString());
                storage.addLog("Расписание недели обновлено (пар: " + totalParsedLessons + ")");
            } else {
                storage.addLog("На текущей неделе занятий не найдено.");
            }

            if (!newlyChecked.isEmpty()) {
                String nowStr = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                for (String s : newlyChecked) {
                    String title = "✅ Отметился на паре в " + nowStr + "!";
                    storage.addLog(title + " — " + s);
                    NotificationHelper.sendAlert(ctx, title, s);
                }
            }
        } catch (Exception e) {
            if (!(e instanceof java.net.SocketTimeoutException)) {
                api.invalidateSession();
            }
            storage.addLog("Сервер медленно отвечает (" + e.getMessage() + ")");
        }
    }

    private static class SubjectAgg {
        String subject;
        int lecCount = 0;
        int pracCount = 0;
        int labCount = 0;
        Set<String> teachers = new LinkedHashSet<>();
    }

    public void scanFullSemester(
        final SutApiClient api,
        final AppStorage storage,
        final BonchBackgroundDaemon daemon,
        final String login,
        final String password
    ) {
        if (!api.isAuthenticated() && !api.authenticate(login, password, storage)) {
            daemon.onScanProgress(false, "Ошибка авторизации");
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            final AtomicInteger loadedCount = new AtomicInteger(0);
            List<Future<String>> futures = new ArrayList<>();

            for (int w = 1; w <= TOTAL_SEMESTER_WEEKS; w++) {
                final int weekNum = w;
                futures.add(pool.submit(new Callable<String>() {
                    @Override
                    public String call() {
                        try {
                            int waitTicks = 0;
                            while (pauseSemesterScan && waitTicks < 40) {
                                Thread.sleep(300L);
                                waitTicks++;
                            }
                            Thread.sleep(150L);
                            String payload = "week=" + weekNum;
                            String html = api.httpRequestFast("POST", SutApiClient.SCHEDULE_URL + "?" + payload, payload);
                            if (!isValidScheduleHtml(html)) {
                                html = api.httpRequestFast("GET", SutApiClient.SCHEDULE_URL + "?" + payload, null);
                            }
                            int done = loadedCount.incrementAndGet();
                            daemon.onScanProgress(true, "Загрузка семестра: " + done + " из " + TOTAL_SEMESTER_WEEKS + "...");
                            return html != null ? html : "";
                        } catch (Exception e) {
                            loadedCount.incrementAndGet();
                            return "";
                        }
                    }
                }));
            }

            Pattern trPattern = Pattern.compile("<tr([^>]*)>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern dayNamePattern = Pattern.compile("(Понедельник|Вторник|Среда|Четверг|Пятница|Суббота|Воскресенье)", Pattern.CASE_INSENSITIVE);
            Pattern dateValPattern = Pattern.compile("(\\d{1,2}\\.\\d{2}\\.\\d{2,4})");
            Pattern timePattern = Pattern.compile("([01]?\\d|2[0-3])[:.]([0-5]\\d)\\s*[-–—]\\s*([01]?\\d|2[0-3])[:.]([0-5]\\d)");
            Pattern pairNumPattern = Pattern.compile("^\\s*([1-7])\\s*(?:пара|\\(\\s*\\))", Pattern.CASE_INSENSITIVE);
            Pattern zanPattern = Pattern.compile("open_zan\\s*\\(\\s*['\"]?(\\d+)['\"]?\\s*,\\s*['\"]?(\\d+)['\"]?\\s*\\)");
            Pattern startedPattern = Pattern.compile("занятие\\s+началось\\s*(\\d{1,2}[:.]\\d{2})", Pattern.CASE_INSENSITIVE);
            Pattern waitBtnPattern = Pattern.compile("Кнопка\\s+появится\\s+в\\s*(\\d{1,2}[:.]\\d{2})", Pattern.CASE_INSENSITIVE);
            Pattern boldCheckPat = Pattern.compile("<(b|strong)[^>]*>.*[А-Яа-яЁёA-Za-z]{3,}.*</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

            SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy", Locale.US);
            SimpleDateFormat shortDateFmt = new SimpleDateFormat("dd.MM", Locale.US);
            String todayStr = df.format(new Date());

            Map<String, SubjectAgg> subjectMap = new LinkedHashMap<>();
            TreeMap<Long, JSONObject> allDaysMap = new TreeMap<>();

            for (int idx = 0; idx < futures.size(); idx++) {
                int week = idx + 1;
                String html = futures.get(idx).get();
                if (!isValidScheduleHtml(html)) continue;

                Matcher trMatcher = trPattern.matcher(html);
                Map<String, List<JSONObject>> weekLessonsByDate = new LinkedHashMap<>();
                Date anyDateInWeek = null;
                String curDateStr = null;
                String lastTimeRange = "";
                String lastStartTime = "";

                while (trMatcher.find()) {
                    String trInner = trMatcher.group(2);
                    String rowText = trInner.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
                    if (rowText.isEmpty()) continue;

                    Matcher tm = timePattern.matcher(rowText);
                    Matcher dm = dayNamePattern.matcher(rowText);
                    Matcher dateM = dateValPattern.matcher(rowText);

                    boolean hasTime = tm.find();
                    boolean hasDayName = dm.find();
                    boolean hasDate = dateM.find();

                    if (hasDayName && hasDate && !hasTime && rowText.length() < 160) {
                        String normDate = normalizeDateStr(dateM.group(1));
                        if (normDate != null) {
                            curDateStr = normDate;
                            lastTimeRange = "";
                            lastStartTime = "";
                            if (!weekLessonsByDate.containsKey(curDateStr)) {
                                weekLessonsByDate.put(curDateStr, new ArrayList<JSONObject>());
                            }
                            if (anyDateInWeek == null) {
                                try {
                                    anyDateInWeek = df.parse(curDateStr);
                                } catch (Exception ignored) {}
                            }
                        }
                        continue;
                    }

                    if (hasDayName && hasDate && hasTime) {
                        String normDate = normalizeDateStr(dateM.group(1));
                        if (normDate != null) {
                            curDateStr = normDate;
                            if (!weekLessonsByDate.containsKey(curDateStr)) {
                                weekLessonsByDate.put(curDateStr, new ArrayList<JSONObject>());
                            }
                            if (anyDateInWeek == null) {
                                try {
                                    anyDateInWeek = df.parse(curDateStr);
                                } catch (Exception ignored) {}
                            }
                        }
                    }

                    String timeRange = null;
                    String startTime = null;

                    if (hasTime) {
                        timeRange = tm.group(0);
                        int startH = Integer.parseInt(tm.group(1));
                        int startM = Integer.parseInt(tm.group(2));
                        startTime = String.format(Locale.US, "%02d:%02d", startH, startM);
                        lastTimeRange = timeRange;
                        lastStartTime = startTime;
                    } else {
                        Matcher pm = pairNumPattern.matcher(rowText);
                        if (pm.find() && PAIR_NUM_TO_TIME.containsKey(pm.group(1))) {
                            timeRange = PAIR_NUM_TO_TIME.get(pm.group(1));
                            startTime = timeRange.substring(0, 5);
                            lastTimeRange = timeRange;
                            lastStartTime = startTime;
                        } else if (curDateStr != null && !lastStartTime.isEmpty() && boldCheckPat.matcher(trInner).find()) {
                            timeRange = lastTimeRange;
                            startTime = lastStartTime;
                        } else {
                            continue;
                        }
                    }

                    String endTime = extractEndTime(timeRange, startTime);
                    List<ParsedRowDetails> variants = extractStructuredLessonsFromRow(trInner, timeRange);
                    Matcher zm = zanPattern.matcher(trInner);
                    String raspId = zm.find() ? zm.group(1) : "";
                    String weekId = !raspId.isEmpty() ? zm.group(2) : "";

                    for (ParsedRowDetails det : variants) {
                        if ("Предмет".equals(det.subject)) continue;

                        SubjectAgg agg = subjectMap.get(det.subject);
                        if (agg == null) {
                            agg = new SubjectAgg();
                            agg.subject = det.subject;
                            subjectMap.put(det.subject, agg);
                        }
                        if ("Лекция".equals(det.normType)) agg.lecCount++;
                        else if ("Лаба".equals(det.normType)) agg.labCount++;
                        else agg.pracCount++;

                        if (!det.teacher.isEmpty()) agg.teachers.add(det.teacher);

                        if (curDateStr != null) {
                            Matcher sm = startedPattern.matcher(rowText);
                            String startedAt = sm.find() ? sm.group(1) : "";
                            Matcher wm = waitBtnPattern.matcher(rowText);
                            String waitBtn = wm.find() ? wm.group(1) : "";
                            String lowerRow = rowText.toLowerCase(Locale.ROOT);
                            boolean waitingT = lowerRow.contains("ждем начала от преподавателя")
                                            || lowerRow.contains("ждём начала от преподавателя");
                            boolean done = lowerRow.contains("вы присутствуете")
                                        || lowerRow.contains("отмечен")
                                        || lowerRow.contains("присутствует")
                                        || (!startedAt.isEmpty() && !waitingT)
                                        || (todayStr.equals(curDateStr) && (completedWindowsToday.contains(startTime) || (!raspId.isEmpty() && markedRaspIdsToday.contains(raspId))));
                            String finalCheckTime = !startedAt.isEmpty()
                                ? startedAt
                                : (todayStr.equals(curDateStr) && checkInTimeByStart.containsKey(startTime) ? checkInTimeByStart.get(startTime) : "");

                            JSONObject lesObj = new JSONObject();
                            lesObj.put("time", timeRange);
                            lesObj.put("startTime", startTime);
                            lesObj.put("endTime", endTime);
                            lesObj.put("subjectTitle", det.subject);
                            lesObj.put("lessonType", det.normType);
                            lesObj.put("teacher", det.teacher);
                            lesObj.put("room", det.room);
                            lesObj.put("ruleKey", det.ruleKey);
                            lesObj.put("raspId", raspId);
                            lesObj.put("weekId", weekId);
                            lesObj.put("checked", done);
                            lesObj.put("checkedBy", done ? "auto" : "");
                            lesObj.put("waitingTeacher", waitingT && !done);
                            lesObj.put("checkTime", finalCheckTime);
                            lesObj.put("waitBtnTime", waitBtn);
                            weekLessonsByDate.get(curDateStr).add(lesObj);
                        }
                    }
                }

                if (anyDateInWeek != null) {
                    Calendar cal = Calendar.getInstance();
                    cal.setTime(anyDateInWeek);
                    cal.set(Calendar.HOUR_OF_DAY, 0);
                    cal.set(Calendar.MINUTE, 0);
                    cal.set(Calendar.SECOND, 0);
                    cal.set(Calendar.MILLISECOND, 0);
                    int dow = cal.get(Calendar.DAY_OF_WEEK);
                    int diffToMon = (dow == Calendar.SUNDAY) ? -6 : (Calendar.MONDAY - dow);
                    cal.add(Calendar.DAY_OF_MONTH, diffToMon);

                    for (int dIdx = 0; dIdx < 6; dIdx++) {
                        Date dDate = cal.getTime();
                        String dStr = df.format(dDate);
                        String dShortDate = shortDateFmt.format(dDate);
                        List<JSONObject> dayLessons = weekLessonsByDate.get(dStr);
                        if (dayLessons == null) dayLessons = new ArrayList<>();

                        JSONObject dayJson = new JSONObject();
                        dayJson.put("weekNum", week);
                        dayJson.put("dowIdx", dIdx);
                        dayJson.put("shortDow", RU_DAYS_SHORT[dIdx]);
                        dayJson.put("shortDate", dShortDate);
                        dayJson.put("dateStr", dStr);
                        dayJson.put("title", RU_DAYS_FULL[dIdx] + " " + dStr);
                        dayJson.put("isOrange", todayStr.equals(dStr));
                        dayLessons = mergeDuplicateLessons(dayLessons);
                    dayJson.put("lessons", new JSONArray(dayLessons));

                        allDaysMap.put(dDate.getTime(), dayJson);
                        cal.add(Calendar.DAY_OF_MONTH, 1);
                    }
                }
            }

            if (!allDaysMap.isEmpty()) {
                JSONArray fullSemesterDays = new JSONArray();
                for (JSONObject dObj : allDaysMap.values()) {
                    fullSemesterDays.put(dObj);
                }
                semesterDaysArray = fullSemesterDays;
                recalculateTodayIndex(storage);
                storage.writeText(AppStorage.FILE_SEMESTER_DAYS, semesterDaysArray.toString());
            }

            JSONArray newCatalog = new JSONArray();
            for (SubjectAgg agg : subjectMap.values()) {
                JSONObject sObj = new JSONObject();
                sObj.put("subject", agg.subject);
                sObj.put("lecCount", agg.lecCount);
                sObj.put("pracCount", agg.pracCount);
                sObj.put("labCount", agg.labCount);
                sObj.put("total", agg.lecCount + agg.pracCount + agg.labCount);
                StringBuilder tb = new StringBuilder();
                for (String t : agg.teachers) {
                    if (tb.length() > 0) tb.append(", ");
                    tb.append(t);
                }
                sObj.put("teachers", tb.length() > 0 ? tb.toString() : "Не указан");
                newCatalog.put(sObj);
            }

            smartCatalogArray = newCatalog;
            storage.writeText(AppStorage.FILE_SMART_CATALOG, smartCatalogArray.toString());
            storage.addLog("Семестр сохранён (" + smartCatalogArray.length() + " предм.)");
            daemon.onScanProgress(false, "Семестр сохранён");
        } catch (Exception e) {
            storage.addLog("Ошибка анализа семестра: " + e.getMessage());
            daemon.onScanProgress(false, "Ошибка анализа");
        } finally {
            pool.shutdownNow();
        }
    }
}