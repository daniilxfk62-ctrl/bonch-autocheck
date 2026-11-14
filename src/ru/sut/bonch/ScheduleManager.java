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
import java.util.Set;
import java.util.TreeMap;
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

    public final Set<String> completedWindowsToday = new HashSet<>();
    public final Set<String> waitingTeacherToday = new HashSet<>();
    public final Set<String> markedRaspIdsToday = new HashSet<>();
    public final Set<String> notifiedSkippedToday = new HashSet<>();
    public final Map<String, String> checkInTimeByStart = new HashMap<>();
    public final Set<String> todayRealStartTimes = new HashSet<>();
    public volatile boolean lastPollMarkedAny = false;

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
            recalculateTodayIndex(storage);
        }
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
                                + " — автоотметка выключена в 🤖.";
                            storage.addLog(title + ": " + msg);
                            NotificationHelper.sendAlert(ctx, title, msg);
                        }
                    } catch (Exception ignored) {}
                }
            }
            break;
        }
    }

    public static String formatRoomClean(String rawRoom) {
        if (rawRoom == null) return "";
        String s = rawRoom.trim();
        s = s.replaceAll("(?i)\\s*[;,\\s]*\\b(?:пр\\.?\\s*Большевиков[^,/]*|[БбBb]22)\\s*/\\s*(\\d+)", "/$1");
        s = s.replaceAll("(?i)\\s*[;,\\s]*\\b(?:пр\\.?\\s*Большевиков[^,;]*|[БбBb]22)\\b", "");
        s = s.replaceAll("^[;,\\s]+|[;,\\s]+$", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    public static class ParsedRowDetails {
        public String subject = "Предмет";
        public String normType = "Практика";
        public String teacher = "";
        public String room = "";
        public String ruleKey = "";
    }

    /**
     * Извлекает один или несколько вариантов занятий из одной строки <tr>.
     * Если в одной строке таблицы сразу указаны 2 преподавателя и 2 кабинета (например, 2 группы Иностранного языка),
     * метод возвращает 2 объекта ParsedRowDetails, чтобы оба отобразились в выборе подгрупп!
     */
    public List<ParsedRowDetails> extractStructuredLessonsFromRow(String trInner, String timeRange) {
        ParsedRowDetails base = new ParsedRowDetails();
        List<String> tds = new ArrayList<>();
        Pattern tdPat = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher tdMat = tdPat.matcher(trInner);
        while (tdMat.find()) {
            tds.add(tdMat.group(1));
        }

        Pattern boldPat = Pattern.compile("<(b|strong)[^>]*>(.*?)</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern fioShortPat = Pattern.compile("([А-ЯЁ][а-яё\\-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.)");
        Pattern fioFullPat = Pattern.compile("([А-ЯЁ][а-яё\\-]{2,}\\s+[А-ЯЁ][а-яё]{2,}\\s+[А-ЯЁ][а-яё]{2,}(?:вич|вна|ична))");
        Pattern roomHintPat = Pattern.compile("(?i)(?:[БбBb]22|\\b\\d{3}[а-яa-z]?\\b\\s*(?:;|/|\\b)|ауд\\.|каб\\.|дистанционно|спорт|зал)");

        List<String> boldSubjects = new ArrayList<>();
        int subjectCellIdx = -1;
        for (int i = 0; i < tds.size(); i++) {
            String cellHtml = tds.get(i);
            Matcher bm = boldPat.matcher(cellHtml);
            while (bm.find()) {
                String bText = bm.group(2).replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
                if (bText.length() > 2 && !bText.matches("^[0-9:–\\-\\s()]+$")) {
                    boldSubjects.add(bText);
                    if (subjectCellIdx == -1) {
                        base.subject = bText;
                        subjectCellIdx = i;
                    }
                }
            }
        }

        String fullRowPlain = trInner.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
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
                String cp = tds.get(i).replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
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

        for (int i = 0; i < tds.size(); i++) {
            if (i == subjectCellIdx) continue;
            String cellHtml = tds.get(i);
            String cellPlain = cellHtml
                .replaceAll("(?i)<br\\s*/?>", " | ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("(?i)(занятие\\s+началось.*|ждем\\s+начала.*|ждём\\s+начала.*|кнопка\\s+появится.*|начать\\s+занятие.*|обновить.*|вы\\s+присутствуете.*|отметиться.*)", "")
                .replaceAll("\\s+", " ")
                .trim();

            if (cellPlain.isEmpty() || cellPlain.equals(timeRange) || cellPlain.matches("^\\d+\\s*\\(?\\s*\\)?$")) {
                continue;
            }
            if (cellPlain.matches("^\\d+\\s+[0-9]{1,2}[:.][0-9]{2}.*")) {
                continue;
            }

            // Ищем всех преподавателей в ячейке
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

            // Ищем аудитории (может быть несколько через <br> или |)
            if (roomHintPat.matcher(cellPlain).find() || (subjectCellIdx != -1 && i == subjectCellIdx + 1)) {
                String[] roomParts = cellPlain.split("\\|");
                for (String rp : roomParts) {
                    String cr = formatRoomClean(rp);
                    if (!cr.isEmpty() && !foundRooms.contains(cr)) {
                        foundRooms.add(cr);
                    }
                }
                continue;
            }

            if (foundTeachers.isEmpty() && subjectCellIdx != -1 && i > subjectCellIdx && cellPlain.matches(".*[А-Яа-яЁё]{3,}.*")) {
                if (!cellPlain.toLowerCase(Locale.ROOT).contains("б22")) {
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
            Pattern inlineRoomPat = Pattern.compile("(\\d{2,4}[а-яa-z]?\\s*;?\\s*[БбBb]22(?:\\s*/\\s*\\d+)?)");
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
            String html = api.httpRequest("POST", SutApiClient.SCHEDULE_URL, "");
            if (html.trim().length() < 50 || html.toLowerCase(Locale.ROOT).contains("autentification") || !html.toLowerCase(Locale.ROOT).contains("<table")) {
                if (!api.authenticate(login, password, storage)) return;
                html = api.httpRequest("POST", SutApiClient.SCHEDULE_URL, "");
            }

            Pattern trPattern = Pattern.compile("<tr([^>]*)>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern dayNamePattern = Pattern.compile("(Понедельник|Вторник|Среда|Четверг|Пятница|Суббота|Воскресенье)", Pattern.CASE_INSENSITIVE);
            Pattern dateValPattern = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4})");
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

            while (trMatcher.find()) {
                String trAttrs = trMatcher.group(1);
                String trInner = trMatcher.group(2);
                String fullTrHtml = "<tr" + trAttrs + ">" + trInner + "</tr>";

                String rowText = trInner.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
                if (rowText.isEmpty()) continue;

                Matcher tm = timePattern.matcher(rowText);
                Matcher dm = dayNamePattern.matcher(rowText);
                Matcher dateM = dateValPattern.matcher(rowText);

                String timeRange = null;
                String startTime = null;

                if (tm.find()) {
                    timeRange = tm.group(0);
                    int startH = Integer.parseInt(tm.group(1));
                    int startM = Integer.parseInt(tm.group(2));
                    startTime = String.format(Locale.US, "%02d:%02d", startH, startM);
                    lastTimeRange = timeRange;
                    lastStartTime = startTime;
                } else {
                    if (dm.find() && dateM.find() && rowText.length() < 80) {
                        curDateStr = dateM.group(1);
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
                        continue;
                    }
                    // Проверяем, не является ли эта строка второй подгруппой/предметом на то же самое время (rowspan без повтора времени)
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

                    if (allowedBySubgroup) {
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
                                checkInTimeByStart.put(startTime, "пропуск 🤖");
                            }
                        } else if (raspId != null && weekId != null && !alreadyInHtml && !markedRaspIdsToday.contains(raspId)) {
                            if (api.sendAndVerifyOpenZan(raspId, weekId)) {
                                String nowStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                                markedRaspIdsToday.add(raspId);
                                checkInTimeByStart.put(startTime, nowStr);
                                completedWindowsToday.add(startTime);
                                waitingTeacherToday.remove(startTime);
                                alreadyInHtml = true;
                                waitingForTeacher = false;
                                lastPollMarkedAny = true;
                                newlyChecked.add(structured.subject + " (" + structured.normType + ", отметка в " + nowStr + ")");
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

                    if (curDateStr != null) {
                        JSONObject item = new JSONObject();
                        item.put("time", timeRange);
                        item.put("startTime", startTime);
                        item.put("subjectTitle", structured.subject);
                        item.put("lessonType", structured.normType);
                        item.put("teacher", structured.teacher);
                        item.put("room", structured.room);
                        item.put("ruleKey", structured.ruleKey);
                        item.put("checked", isLessonDone);
                        item.put("waitingTeacher", waitingForTeacher && !isLessonDone);
                        item.put("checkTime", displayCheckTime != null ? displayCheckTime : "");
                        item.put("waitBtnTime", btnAppearTime != null ? btnAppearTime : "");
                        weekLessonsByDate.get(curDateStr).add(item);
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
            }

            if (!newlyChecked.isEmpty()) {
                String nowStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                for (String s : newlyChecked) {
                    String title = "✅ Отметился на паре в " + nowStr + "!";
                    storage.addLog(title + " — " + s);
                    NotificationHelper.sendAlert(ctx, title, s);
                }
            }
        } catch (Exception e) {
            api.invalidateSession();
            storage.addLog("Переподключение (" + e.getMessage() + ")");
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
        SutApiClient api,
        AppStorage storage,
        BonchBackgroundDaemon daemon,
        String login,
        String password
    ) {
        if (!api.isAuthenticated() && !api.authenticate(login, password, storage)) {
            daemon.onScanProgress(false, "Ошибка авторизации");
            return;
        }

        try {
            Pattern trPattern = Pattern.compile("<tr([^>]*)>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern dayNamePattern = Pattern.compile("(Понедельник|Вторник|Среда|Четверг|Пятница|Суббота|Воскресенье)", Pattern.CASE_INSENSITIVE);
            Pattern dateValPattern = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4})");
            Pattern timePattern = Pattern.compile("([01]?\\d|2[0-3])[:.]([0-5]\\d)\\s*[-–—]\\s*([01]?\\d|2[0-3])[:.]([0-5]\\d)");
            Pattern pairNumPattern = Pattern.compile("^\\s*([1-7])\\s*(?:пара|\\(\\s*\\))", Pattern.CASE_INSENSITIVE);
            Pattern startedPattern = Pattern.compile("занятие\\s+началось\\s*(\\d{1,2}[:.]\\d{2})", Pattern.CASE_INSENSITIVE);
            Pattern waitBtnPattern = Pattern.compile("Кнопка\\s+появится\\s+в\\s*(\\d{1,2}[:.]\\d{2})", Pattern.CASE_INSENSITIVE);
            Pattern boldCheckPat = Pattern.compile("<(b|strong)[^>]*>.*[А-Яа-яЁёA-Za-z]{3,}.*</\\1>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

            SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy", Locale.US);
            SimpleDateFormat shortDateFmt = new SimpleDateFormat("dd.MM", Locale.US);
            String todayStr = df.format(new Date());

            Map<String, SubjectAgg> subjectMap = new LinkedHashMap<>();
            TreeMap<Long, JSONObject> allDaysMap = new TreeMap<>();

            for (int week = 1; week <= TOTAL_SEMESTER_WEEKS; week++) {
                daemon.onScanProgress(true, "Загрузка семестра: неделя " + week + " из " + TOTAL_SEMESTER_WEEKS + "...");

                String payload = "week=" + week;
                String html = api.httpRequest("POST", SutApiClient.SCHEDULE_URL + "?" + payload, payload);
                if (html.isEmpty() || !html.toLowerCase(Locale.ROOT).contains("<table")) continue;

                Matcher trMatcher = trPattern.matcher(html);
                Map<String, List<JSONObject>> weekLessonsByDate = new LinkedHashMap<>();
                Date anyDateInWeek = null;
                String curDateStr = null;
                String lastTimeRange = "";
                String lastStartTime = "";

                while (trMatcher.find()) {
                    String trInner = trMatcher.group(2);
                    String rowText = trInner.replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("\\s+", " ").trim();
                    if (rowText.isEmpty()) continue;

                    Matcher tm = timePattern.matcher(rowText);
                    Matcher dm = dayNamePattern.matcher(rowText);
                    Matcher dateM = dateValPattern.matcher(rowText);

                    String timeRange = null;
                    String startTime = null;

                    if (tm.find()) {
                        timeRange = tm.group(0);
                        int startH = Integer.parseInt(tm.group(1));
                        int startM = Integer.parseInt(tm.group(2));
                        startTime = String.format(Locale.US, "%02d:%02d", startH, startM);
                        lastTimeRange = timeRange;
                        lastStartTime = startTime;
                    } else {
                        if (dm.find() && dateM.find() && rowText.length() < 80) {
                            curDateStr = dateM.group(1);
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
                            continue;
                        }
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

                    List<ParsedRowDetails> variants = extractStructuredLessonsFromRow(trInner, timeRange);

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
                                        || (!startedAt.isEmpty() && !waitingT);

                            JSONObject lesObj = new JSONObject();
                            lesObj.put("time", timeRange);
                            lesObj.put("startTime", startTime);
                            lesObj.put("subjectTitle", det.subject);
                            lesObj.put("lessonType", det.normType);
                            lesObj.put("teacher", det.teacher);
                            lesObj.put("room", det.room);
                            lesObj.put("ruleKey", det.ruleKey);
                            lesObj.put("checked", done);
                            lesObj.put("waitingTeacher", waitingT && !done);
                            lesObj.put("checkTime", startedAt);
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
            storage.addLog("🤖 Семестр сохранен (" + smartCatalogArray.length() + " предм.)");
            daemon.onScanProgress(false, "Семестр сохранен");
        } catch (Exception e) {
            storage.addLog("Ошибка анализа семестра: " + e.getMessage());
            daemon.onScanProgress(false, "Ошибка анализа");
        }
    }
}
