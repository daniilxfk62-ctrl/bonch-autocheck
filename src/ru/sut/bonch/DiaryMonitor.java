package ru.sut.bonch;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
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

    private static final String[] DOW_SHORT = {"Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"};
    private static final String[] DOW_FULL = {
        "Воскресенье", "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота"
    };

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
    private static final Pattern ATTEST_NUM_PAT = Pattern.compile(
        "(\\d)\\s*(?:-?[яй])?\\s*аттестац|аттестац[^\\d]{0,12}(\\d)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ISO_DATE_INLINE_PAT = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern RU_DATE_INLINE_PAT = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{4})");

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
        int excusedCount = 0;
        int total = 0;
        List<Integer> history = new ArrayList<>();
        List<JSONObject> sessions = new ArrayList<>();
        List<String> grades = new ArrayList<>();
        List<JSONObject> gradeEntries = new ArrayList<>();
        List<JSONObject> controls = new ArrayList<>();
        String credit = "";
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
                String[] dow = parseDowFromIso(dateIso);
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
                    String absenceStatus = classifyAbsence(cellVal);
                    boolean isMissed = !absenceStatus.isEmpty();
                    String sessionStatus = isMissed ? absenceStatus : "present";

                    if (isMissed) {
                        st.history.add(0);
                        if ("sick".equals(absenceStatus) || "excused".equals(absenceStatus)) {
                            st.excusedCount++;
                        }
                    } else {
                        st.attended++;
                        st.history.add(1);
                    }

                    JSONObject sessObj = new JSONObject();
                    sessObj.put("date", shortDate);
                    sessObj.put("dateIso", dateIso);
                    sessObj.put("dow", dow[0]);
                    sessObj.put("dowFull", dow[1]);
                    sessObj.put("status", sessionStatus);
                    sessObj.put("value", cellVal);
                    st.sessions.add(sessObj);

                    extractNumericGradesToStats(
                        cellVal,
                        st,
                        shortDate,
                        dateIso,
                        dow[0],
                        dow[1],
                        "lesson",
                        "Занятие",
                        ""
                    );

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
                boolean isAttestation = "Аттестация".equals(controlType);
                String attNum = isAttestation ? extractAttestationNum(rowLabel) : "";
                String controlLabel = isAttestation
                    ? (attNum.isEmpty() ? "Аттестация" : ("Аттестация " + attNum))
                    : controlType;

                int maxCols = Math.min(tdTextList.size(), columns.size());
                for (int col = 2; col < maxCols; col++) {
                    String subj = columns.get(col);
                    if (subj.isEmpty()) continue;

                    String cellAttrs = tdAttrsList.get(col);
                    String cellVal = tdTextList.get(col);
                    boolean hasValue = !cellVal.isEmpty();

                    if (!hasValue && !isControlCellApplicable(cellAttrs, isAttestation)) {
                        continue;
                    }

                    SubjectDiaryStats st = getOrCreateSubject(bySubject, subj);
                    if (!isAttestation) {
                        st.credit = controlType;
                    }

                    Matcher titleMat = TITLE_ATTR_PAT.matcher(cellAttrs);
                    String rawAttrTitle = titleMat.find() ? titleMat.group(1).trim() : "";
                    String ctrlDateIso = extractIsoDateFromText(rawAttrTitle);
                    String ctrlShortDate = !ctrlDateIso.isEmpty()
                        ? formatIsoToShortDate(ctrlDateIso)
                        : rawAttrTitle;
                    String[] ctrlDow = parseDowFromIso(ctrlDateIso);

                    String normVal = "";
                    String passedState = ""; // "true", "false", or ""

                    if (hasValue) {
                        String lowVal = cellVal.toLowerCase(Locale.ROOT);
                        if (isAttestation) {
                            if (lowVal.contains("не аттест") || lowVal.contains("неаттест")
                                || lowVal.contains("не зач") || lowVal.contains("незач")
                                || lowVal.equals("2")) {
                                normVal = "Неаттестован";
                                passedState = "false";
                            } else if (lowVal.contains("аттест") || lowVal.contains("зач")) {
                                normVal = "Аттестован";
                                passedState = "true";
                            } else {
                                normVal = cellVal;
                            }
                        } else if ("Зачёт".equals(controlType)) {
                            if (lowVal.contains("не зач") || lowVal.contains("незач")) {
                                normVal = "Незачёт";
                                passedState = "false";
                            } else if (lowVal.contains("зач")) {
                                normVal = "Зачёт";
                                passedState = "true";
                            } else {
                                normVal = cellVal;
                            }
                        } else {
                            Matcher gm = GRADE_NUM_PAT.matcher(cellVal);
                            if (gm.find()) {
                                normVal = gm.group(1);
                                passedState = "2".equals(normVal) ? "false" : "true";
                            } else if (lowVal.contains("незач") || lowVal.contains("не зач") || lowVal.contains("неяв")) {
                                normVal = cellVal;
                                passedState = "false";
                            } else {
                                normVal = cellVal;
                            }
                        }
                    }

                    JSONObject ctrlObj = new JSONObject();
                    ctrlObj.put("type", controlType);
                    ctrlObj.put("label", controlLabel);
                    ctrlObj.put("value", normVal);
                    ctrlObj.put("rawValue", cellVal);
                    ctrlObj.put("passed", passedState);
                    ctrlObj.put("date", ctrlShortDate);
                    ctrlObj.put("dateIso", ctrlDateIso);
                    ctrlObj.put("dow", ctrlDow[0]);
                    ctrlObj.put("dowFull", ctrlDow[1]);
                    st.controls.add(ctrlObj);

                    if (hasValue) {
                        recordEntry(
                            subj,
                            !ctrlShortDate.isEmpty() ? ctrlShortDate : controlLabel,
                            !ctrlDateIso.isEmpty() ? ctrlDateIso : controlLabel,
                            "ctrl_" + controlLabel + "_" + col,
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

    /**
     * Классифицирует пропуск:
     * "sick"    — Б (болезнь)
     * "excused" — У (уважительная причина)
     * "absent"  — Н / Н/Я / неявка / пропуск
     * ""        — студент присутствовал
     */
    private static String classifyAbsence(String val) {
        if (val == null || val.isEmpty()) return "";
        String up = val.trim().toUpperCase(Locale.ROOT);
        if (up.equals("Б") || up.startsWith("Б ") || up.startsWith("Б/")) {
            return "sick";
        }
        if (up.equals("У") || up.startsWith("У ") || up.startsWith("У/")) {
            return "excused";
        }
        if (up.equals("Н")
            || up.equals("Н/Я")
            || up.startsWith("Н ")
            || up.startsWith("Н/")
            || up.contains("НЕЯВК")
            || up.contains("ПРОПУСК")) {
            return "absent";
        }
        return "";
    }

    private static void addGradeEntry(
        SubjectDiaryStats st,
        String value,
        String display,
        String shortDate,
        String dateIso,
        String dowShort,
        String dowFull,
        String kind,
        String label
    ) throws Exception {
        st.grades.add(display);
        JSONObject gObj = new JSONObject();
        gObj.put("value", value);
        gObj.put("display", display);
        gObj.put("date", shortDate != null ? shortDate : "");
        gObj.put("dateIso", dateIso != null ? dateIso : "");
        gObj.put("dow", dowShort != null ? dowShort : "");
        gObj.put("dowFull", dowFull != null ? dowFull : "");
        gObj.put("kind", kind != null ? kind : "lesson");
        gObj.put("label", label != null ? label : "Занятие");
        st.gradeEntries.add(gObj);
    }

    private static void extractNumericGradesToStats(
        String cellVal,
        SubjectDiaryStats st,
        String shortDate,
        String dateIso,
        String dowShort,
        String dowFull,
        String kind,
        String label,
        String shortPrefix
    ) throws Exception {
        if (cellVal == null || cellVal.isEmpty()) return;
        Matcher gm = GRADE_NUM_PAT.matcher(cellVal);
        while (gm.find()) {
            String g = gm.group(1);
            String display = (shortPrefix != null && !shortPrefix.isEmpty()) ? (shortPrefix + ": " + g) : g;
            addGradeEntry(st, g, display, shortDate, dateIso, dowShort, dowFull, kind, label);
        }
    }

    private static String detectControlType(String rowLabel) {
        if (rowLabel.contains("экзамен")) return "Экзамен";
        if (rowLabel.contains("зачет с оценк") || rowLabel.contains("зачёт с оценк")) return "Зачёт с оценкой";
        if (rowLabel.contains("зачет") || rowLabel.contains("зачёт")) return "Зачёт";
        if (rowLabel.contains("аттестац")) return "Аттестация";
        return "";
    }

    private static boolean isControlCellApplicable(String cellAttrs, boolean isAttestation) {
        if (cellAttrs == null) return isAttestation;
        String low = cellAttrs.toLowerCase(Locale.ROOT);
        if (low.contains("disabled") || low.contains("background:#ccc") || low.contains("background:#ddd")
            || low.contains("background: #ccc") || low.contains("background: #ddd")
            || low.contains("background-color:#ccc") || low.contains("background-color:#ddd")
            || low.contains("background:gray") || low.contains("background:silver")) {
            return false;
        }
        if (low.contains("title=") || low.contains("onclick=") || low.contains("rasp=") || low.contains("id=")) {
            return true;
        }
        return isAttestation;
    }

    private static String extractAttestationNum(String rowLabel) {
        if (rowLabel == null || rowLabel.isEmpty()) return "";
        Matcher m = ATTEST_NUM_PAT.matcher(rowLabel);
        if (m.find()) {
            String g1 = m.group(1);
            if (g1 != null && !g1.isEmpty()) return g1;
            String g2 = m.group(2);
            if (g2 != null && !g2.isEmpty()) return g2;
        }
        return "";
    }

    private static String extractIsoDateFromText(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        Matcher mIso = ISO_DATE_INLINE_PAT.matcher(raw);
        if (mIso.find()) return mIso.group(1);
        Matcher mRu = RU_DATE_INLINE_PAT.matcher(raw);
        if (mRu.find()) {
            String[] p = mRu.group(1).split("\\.");
            if (p.length == 3) {
                return p[2] + "-" + p[1] + "-" + p[0];
            }
        }
        return "";
    }

    private static String[] parseDowFromIso(String dateIso) {
        if (dateIso == null || dateIso.length() < 10) return new String[]{"", ""};
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d = sdf.parse(dateIso.substring(0, 10));
            if (d != null) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(d);
                int dow = cal.get(Calendar.DAY_OF_WEEK) - 1;
                if (dow >= 0 && dow < 7) {
                    return new String[]{DOW_SHORT[dow], DOW_FULL[dow]};
                }
            }
        } catch (Exception ignored) {}
        return new String[]{"", ""};
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
                obj.put("excusedCount", st.excusedCount);
                obj.put("total", st.total);

                JSONArray histArr = new JSONArray();
                int hStart = Math.max(0, st.history.size() - 10);
                for (int i = hStart; i < st.history.size(); i++) {
                    histArr.put(st.history.get(i));
                }
                obj.put("history", histArr);

                JSONArray sessionsArr = new JSONArray();
                for (JSONObject sess : st.sessions) {
                    sessionsArr.put(sess);
                }
                obj.put("sessions", sessionsArr);

                JSONArray gradesArr = new JSONArray();
                for (String g : st.grades) {
                    gradesArr.put(g);
                }
                obj.put("grades", gradesArr);

                JSONArray gradeEntriesArr = new JSONArray();
                for (JSONObject ge : st.gradeEntries) {
                    gradeEntriesArr.put(ge);
                }
                obj.put("gradeEntries", gradeEntriesArr);

                JSONArray controlsArr = new JSONArray();
                for (JSONObject c : st.controls) {
                    controlsArr.put(c);
                }
                obj.put("controls", controlsArr);

                obj.put("credit", st.credit);
                summaryArr.put(obj);
            }
            storage.writeText(AppStorage.FILE_DIARY_SUMMARY, summaryArr.toString());
        } catch (Exception ignored) {}
    }
}