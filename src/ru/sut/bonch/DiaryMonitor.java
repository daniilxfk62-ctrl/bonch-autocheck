package ru.sut.bonch;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class DiaryMonitor {
    private long lastDiaryPollMillis = 0L;

    private static final Pattern TABLE_PAT = Pattern.compile(
        "<table[^>]*class=[\"'][^\"']*smalltab[^\"']*[\"'][^>]*>(.*?)</table>",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TR_PAT = Pattern.compile(
        "<tr([^>]*)>(.*?)</tr>",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TH_PAT = Pattern.compile(
        "<th[^>]*>(.*?)</th>",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TD_PAT = Pattern.compile(
        "<td([^>]*)>(.*?)</td>",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DATE1_ATTR_PAT = Pattern.compile(
        "date1\\s*=\\s*[\"'](\\d{4}-\\d{2}-\\d{2})[\"']",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RASP_ATTR_PAT = Pattern.compile(
        "rasp\\s*=\\s*[\"']?(\\d+)[\"']?",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TITLE_ATTR_PAT = Pattern.compile(
        "title\\s*=\\s*[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GRADE_NUM_PAT = Pattern.compile("\\b([2345])\\b");
    private static final Pattern SELECT_GROUP_PAT = Pattern.compile(
        "<option[^>]*value=[\"'](\\d+)[\"'][^>]*selected",
        Pattern.CASE_INSENSITIVE
    );

    public boolean isTimeForPeriodicPoll(AppStorage storage) {
        long intervalMs = storage.getAutoUpdateIntervalMillis();
        if (intervalMs <= 0L) return false;
        return (System.currentTimeMillis() - lastDiaryPollMillis) >= intervalMs;
    }

    public synchronized void pollDiary(
        Context ctx,
        SutApiClient api,
        AppStorage storage,
        String login,
        String password,
        boolean manualTrigger
    ) {
        lastDiaryPollMillis = System.currentTimeMillis();
        if (!api.isAuthenticated() && !api.authenticate(login, password, storage)) {
            if (manualTrigger) storage.addLog("Ошибка входа в ЛК при проверке Дневника");
            return;
        }

        try {
            String html = fetchDiaryHtml(api, storage, login, password);
            if (!isValidDiaryHtml(html)) {
                if (manualTrigger) storage.addLog("📚 Дневник: таблица jurnal_dnevnik.php не найдена.");
                return;
            }

            String oldRaw = storage.readText(AppStorage.FILE_DIARY_SNAPSHOT, "");
            boolean hadBaseline = !oldRaw.isEmpty();
            JSONObject oldSnap = hadBaseline ? new JSONObject(oldRaw) : new JSONObject();

            JSONObject newSnap = new JSONObject();
            Iterator<String> oldKeys = oldSnap.keys();
            while (oldKeys.hasNext()) {
                String k = oldKeys.next();
                newSnap.put(k, oldSnap.optString(k, ""));
            }

            List<JSONObject> recentEntries = new ArrayList<>();
            Map<String, JSONObject> uniqueAllEntries = new LinkedHashMap<>();
            Map<String, SubjectDiaryStats> bySubject = new LinkedHashMap<>();
            List<String> newAlerts = new ArrayList<>();

            parseJurnalDnevnikTable(
                html,
                hadBaseline,
                oldSnap,
                newSnap,
                bySubject,
                recentEntries,
                uniqueAllEntries,
                newAlerts
            );

            if (bySubject.isEmpty()) {
                if (manualTrigger) storage.addLog("📚 Дневник: предметов в таблице не обнаружено.");
                return;
            }

            storage.writeText(AppStorage.FILE_DIARY_SNAPSHOT, newSnap.toString());

            JSONArray allJson = new JSONArray();
            for (JSONObject item : uniqueAllEntries.values()) {
                allJson.put(item);
            }
            storage.writeText(AppStorage.FILE_DIARY_ALL, allJson.toString());

            JSONArray recentJson = new JSONArray();
            int startIdx = Math.max(0, recentEntries.size() - 30);
            for (int i = recentEntries.size() - 1; i >= startIdx; i--) {
                recentJson.put(recentEntries.get(i));
            }
            storage.writeText(AppStorage.FILE_DIARY_RECENT, recentJson.toString());

            saveSummary(storage, bySubject);

            if (!newAlerts.isEmpty() && storage.isNotifyDiaryEnabled()) {
                for (String alertText : newAlerts) {
                    String title = "📊 Новая запись в Дневнике!";
                    storage.addLog(title + " " + alertText);
                    NotificationHelper.sendAlert(ctx, title, alertText);
                }
            } else if (manualTrigger) {
                storage.addLog("📚 Дневник обновлён (предметов: " + bySubject.size() + ", записей: " + uniqueAllEntries.size() + ")");
            } else {
                storage.addLog("📚 Дневник синхронизирован (предметов: " + bySubject.size() + ")");
            }
        } catch (Exception e) {
            if (manualTrigger) {
                storage.addLog("Ошибка проверки Дневника: " + e.getMessage());
            }
        }
    }

    private String fetchDiaryHtml(
        SutApiClient api,
        AppStorage storage,
        String login,
        String password
    ) throws Exception {
        String url = SutApiClient.DIARY_URL;
        String html = api.httpRequest("GET", url, null);
        if (isAuthExpired(html)) {
            if (api.authenticate(login, password, storage)) {
                html = api.httpRequest("GET", url, null);
            }
        }
        if (!isValidDiaryHtml(html)) {
            html = api.httpRequest("POST", url, "");
        }
        if (html != null && !TABLE_PAT.matcher(html).find()) {
            Matcher gm = SELECT_GROUP_PAT.matcher(html);
            if (gm.find()) {
                String grpId = gm.group(1);
                String withGrp = api.httpRequest("GET", url + "?selectgroups=" + grpId, null);
                if (isValidDiaryHtml(withGrp)) {
                    return withGrp;
                }
            }
        }
        return html != null ? html : "";
    }

    private boolean isAuthExpired(String html) {
        if (html == null || html.isEmpty()) return false;
        String low = html.toLowerCase(Locale.ROOT);
        return low.contains("autentification") || low.contains("name=\"parole\"") || low.contains("name='parole'");
    }

    private boolean isValidDiaryHtml(String html) {
        if (html == null || html.trim().length() < 60) return false;
        if (isAuthExpired(html)) return false;
        String low = html.toLowerCase(Locale.ROOT);
        if (low.contains("404 not found") || low.contains("403 forbidden")) return false;
        return low.contains("<table") && (low.contains("smalltab") || low.contains("date1="));
    }

    private static class SubjectDiaryStats {
        String subject;
        int attended = 0;
        int total = 0;
        List<Integer> history = new ArrayList<>();
        List<String> grades = new ArrayList<>();
        String credit = "Контроль";
    }

    private void parseJurnalDnevnikTable(
        String html,
        boolean hadBaseline,
        JSONObject oldSnap,
        JSONObject newSnap,
        Map<String, SubjectDiaryStats> bySubject,
        List<JSONObject> recentEntries,
        Map<String, JSONObject> uniqueAllEntries,
        List<String> newAlerts
    ) throws Exception {
        String tableInner = html;
        Matcher tm = TABLE_PAT.matcher(html);
        if (tm.find()) {
            tableInner = tm.group(1);
        }

        List<String> columns = new ArrayList<>();
        String todayIso = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        Matcher trMat = TR_PAT.matcher(tableInner);
        while (trMat.find()) {
            String trAttrs = trMat.group(1);
            String trInner = trMat.group(2);

            if (columns.isEmpty() && trInner.toLowerCase(Locale.ROOT).contains("<th")) {
                Matcher thMat = TH_PAT.matcher(trInner);
                while (thMat.find()) {
                    columns.add(cleanText(thMat.group(1)));
                }
                for (int i = 2; i < columns.size(); i++) {
                    String subj = columns.get(i);
                    if (!subj.isEmpty() && !bySubject.containsKey(subj)) {
                        SubjectDiaryStats st = new SubjectDiaryStats();
                        st.subject = subj;
                        bySubject.put(subj, st);
                    }
                }
                continue;
            }

            if (columns.size() < 3) continue;

            List<String> tdAttrsList = new ArrayList<>();
            List<String> tdTextList = new ArrayList<>();
            Matcher tdMat = TD_PAT.matcher(trInner);
            while (tdMat.find()) {
                tdAttrsList.add(tdMat.group(1));
                tdTextList.add(cleanText(tdMat.group(2)));
            }
            if (tdTextList.size() < 3) continue;

            Matcher date1Mat = DATE1_ATTR_PAT.matcher(trAttrs);
            String dateIso = date1Mat.find() ? date1Mat.group(1) : "";

            if (!dateIso.isEmpty()) {
                if (dateIso.compareTo(todayIso) > 0) {
                    continue;
                }

                String shortDate = formatIsoToShortDate(dateIso);
                int maxCols = Math.min(tdTextList.size(), columns.size());

                for (int col = 2; col < maxCols; col++) {
                    String subj = columns.get(col);
                    if (subj.isEmpty()) continue;
                    SubjectDiaryStats st = getOrCreateSubject(bySubject, subj);

                    String cellAttrs = tdAttrsList.get(col);
                    String cellVal = tdTextList.get(col);
                    boolean hasRasp = RASP_ATTR_PAT.matcher(cellAttrs).find();

                    if (!hasRasp && cellVal.isEmpty()) {
                        continue;
                    }

                    st.total++;
                    boolean isMissed = isAbsenceValue(cellVal);
                    if (isMissed) {
                        st.history.add(0);
                    } else {
                        st.attended++;
                        st.history.add(1);
                    }

                    extractGradesToStats(cellVal, st);

                    if (!cellVal.isEmpty()) {
                        recordEntry(
                            subj,
                            shortDate,
                            dateIso,
                            "d_" + dateIso + "_" + col,
                            cellVal,
                            hadBaseline,
                            oldSnap,
                            newSnap,
                            recentEntries,
                            uniqueAllEntries,
                            newAlerts
                        );
                    }
                }
                continue;
            }

            String rowLabel = (tdTextList.get(0) + " " + tdTextList.get(1)).toLowerCase(Locale.ROOT);
            String controlType = detectControlType(rowLabel);
            if (!controlType.isEmpty()) {
                int maxCols = Math.min(tdTextList.size(), columns.size());
                for (int col = 2; col < maxCols; col++) {
                    String subj = columns.get(col);
                    String cellVal = tdTextList.get(col);
                    if (subj.isEmpty() || cellVal.isEmpty()) continue;

                    SubjectDiaryStats st = getOrCreateSubject(bySubject, subj);
                    if (!"Аттестация".equals(controlType)) {
                        st.credit = controlType;
                    }

                    String lowVal = cellVal.toLowerCase(Locale.ROOT);
                    if (lowVal.contains("аттест")) {
                        if (!st.grades.contains("Зачёт")) {
                            st.grades.add("Зачёт");
                        }
                    } else if (lowVal.contains("зач")) {
                        st.grades.add("Зачёт");
                    } else {
                        extractGradesToStats(cellVal, st);
                    }

                    Matcher titleMat = TITLE_ATTR_PAT.matcher(tdAttrsList.get(col));
                    String attrDate = titleMat.find() ? titleMat.group(1).trim() : controlType;
                    recordEntry(
                        subj,
                        attrDate,
                        attrDate,
                        "ctrl_" + controlType + "_" + col,
                        cellVal,
                        hadBaseline,
                        oldSnap,
                        newSnap,
                        recentEntries,
                        uniqueAllEntries,
                        newAlerts
                    );
                }
            }
        }
    }

    private static SubjectDiaryStats getOrCreateSubject(Map<String, SubjectDiaryStats> map, String subj) {
        SubjectDiaryStats st = map.get(subj);
        if (st == null) {
            st = new SubjectDiaryStats();
            st.subject = subj;
            map.put(subj, st);
        }
        return st;
    }

    private static boolean isAbsenceValue(String val) {
        if (val == null || val.isEmpty()) return false;
        String up = val.trim().toUpperCase(Locale.ROOT);
        return up.equals("Н")
            || up.equals("Н/Я")
            || up.equals("Б")
            || up.equals("У")
            || up.startsWith("Н ")
            || up.startsWith("Н/")
            || up.contains("НЕЯВК")
            || up.contains("ПРОПУСК");
    }

    private static void extractGradesToStats(String cellVal, SubjectDiaryStats st) {
        if (cellVal == null || cellVal.isEmpty()) return;
        Matcher gm = GRADE_NUM_PAT.matcher(cellVal);
        while (gm.find()) {
            st.grades.add(gm.group(1));
        }
    }

    private static String detectControlType(String rowLabel) {
        if (rowLabel.contains("экзамен")) return "Экзамен";
        if (rowLabel.contains("зачет с оценк") || rowLabel.contains("зачёт с оценк")) return "Диф. зачёт";
        if (rowLabel.contains("зачет") || rowLabel.contains("зачёт")) return "Зачёт";
        if (rowLabel.contains("аттестац")) return "Аттестация";
        return "";
    }

    private static String formatIsoToShortDate(String iso) {
        if (iso != null && iso.length() >= 10) {
            return iso.substring(8, 10) + "." + iso.substring(5, 7);
        }
        return iso != null ? iso : "";
    }

    private static String cleanText(String raw) {
        if (raw == null) return "";
        return raw
            .replaceAll("<[^>]+>", " ")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private void recordEntry(
        String subj,
        String shortDate,
        String fullDate,
        String colId,
        String cleanVal,
        boolean hadBaseline,
        JSONObject oldSnap,
        JSONObject newSnap,
        List<JSONObject> recentEntries,
        Map<String, JSONObject> uniqueAllEntries,
        List<String> newAlerts
    ) throws Exception {
        String cellKey = subj + "||" + shortDate + "||" + colId;
        String prevVal = oldSnap.optString(cellKey, "");
        newSnap.put(cellKey, cleanVal);

        JSONObject entry = new JSONObject();
        entry.put("key", cellKey);
        entry.put("subject", subj);
        entry.put("date", shortDate);
        entry.put("fullDate", fullDate);
        entry.put("value", cleanVal);

        recentEntries.add(entry);
        uniqueAllEntries.put(cellKey, entry);

        if (hadBaseline && !cleanVal.equals(prevVal)) {
            newAlerts.add("Предмет: " + subj + " • " + shortDate + " • «" + cleanVal + "»");
        }
    }

    private void saveSummary(AppStorage storage, Map<String, SubjectDiaryStats> bySubject) {
        try {
            JSONArray summaryArr = new JSONArray();
            for (SubjectDiaryStats st : bySubject.values()) {
                JSONObject obj = new JSONObject();
                obj.put("id", "d_" + Math.abs(st.subject.hashCode()));
                obj.put("subject", st.subject);
                obj.put("attended", st.attended);
                obj.put("total", st.total);

                JSONArray histArr = new JSONArray();
                int hStart = Math.max(0, st.history.size() - 10);
                for (int i = hStart; i < st.history.size(); i++) {
                    histArr.put(st.history.get(i));
                }
                obj.put("history", histArr);

                JSONArray gradesArr = new JSONArray();
                for (String g : st.grades) {
                    gradesArr.put(g);
                }
                obj.put("grades", gradesArr);
                obj.put("credit", st.credit);
                summaryArr.put(obj);
            }
            storage.writeText(AppStorage.FILE_DIARY_SUMMARY, summaryArr.toString());
        } catch (Exception ignored) {}
    }
}
