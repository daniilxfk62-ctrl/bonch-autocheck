package ru.sut.bonch;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class DiaryMonitor {
    private long lastDiaryPollMillis = 0L;

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
            List<String> newAlerts = new ArrayList<>();

            JSONArray prevAllArr = storage.readJsonArray(AppStorage.FILE_DIARY_ALL);
            for (int i = 0; i < prevAllArr.length(); i++) {
                JSONObject obj = prevAllArr.optJSONObject(i);
                if (obj != null) {
                    String uk = obj.optString("key", obj.optString("subject") + "||" + obj.optString("date") + "||" + i);
                    uniqueAllEntries.put(uk, obj);
                }
            }

            String todayDateShort = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());
            Set<String> fetchedSubPages = new HashSet<>();

            for (String url : SutApiClient.DIARY_URLS) {
                String html = "";
                try {
                    html = api.httpRequest("POST", url, "");
                    if (html.length() < 80 || !html.toLowerCase(Locale.ROOT).contains("<table")) {
                        html = api.httpRequest("GET", url, null);
                    }
                } catch (Exception ignored) {}

                if (html == null || html.length() < 40) continue;

                parseDiaryHtmlDocument(
                    html,
                    todayDateShort,
                    hadBaseline,
                    oldSnap,
                    newSnap,
                    recentEntries,
                    uniqueAllEntries,
                    newAlerts
                );

                // Проверяем, есть ли на странице кнопки раскрытия журнала по конкретным дисциплинам: open_uch(id) / open_zan / Subj
                Pattern subCallPat = Pattern.compile("(?:open_uch|open_jurnal|show_jurnal|open_predm)\\s*\\(\\s*['\"]?(\\d+)['\"]?", Pattern.CASE_INSENSITIVE);
                Matcher subMat = subCallPat.matcher(html);
                while (subMat.find() && fetchedSubPages.size() < 25) {
                    String subId = subMat.group(1);
                    if (fetchedSubPages.contains(url + "#" + subId)) continue;
                    fetchedSubPages.add(url + "#" + subId);
                    try {
                        String body = "id=" + subId + "&predm=" + subId + "&uch=" + subId + "&open=1";
                        String subHtml = api.httpRequest("POST", url + "?" + body, body);
                        if (subHtml != null && subHtml.toLowerCase(Locale.ROOT).contains("<table")) {
                            parseDiaryHtmlDocument(
                                subHtml,
                                todayDateShort,
                                hadBaseline,
                                oldSnap,
                                newSnap,
                                recentEntries,
                                uniqueAllEntries,
                                newAlerts
                            );
                        }
                    } catch (Exception ignored) {}
                }
            }

            // Дополнительно синхронизируем посещаемость и пропуски из уже загруженных дней расписания (прошедшие даты)
            syncAttendanceFromScheduleDays(storage, uniqueAllEntries, recentEntries);

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

            if (!newAlerts.isEmpty() && storage.isNotifyDiaryEnabled()) {
                for (String alertText : newAlerts) {
                    String title = "📊 Новая запись в Дневнике!";
                    storage.addLog(title + " " + alertText);
                    NotificationHelper.sendAlert(ctx, title, alertText);
                }
            } else if (manualTrigger) {
                storage.addLog("📚 Дневник обновлен (записей: " + uniqueAllEntries.size() + ", новых: " + newAlerts.size() + ").");
            }
        } catch (Exception e) {
            if (manualTrigger) {
                storage.addLog("Ошибка проверки Дневника: " + e.getMessage());
            }
        }
    }

    private void parseDiaryHtmlDocument(
        String html,
        String todayDateShort,
        boolean hadBaseline,
        JSONObject oldSnap,
        JSONObject newSnap,
        List<JSONObject> recentEntries,
        Map<String, JSONObject> uniqueAllEntries,
        List<String> newAlerts
    ) throws Exception {
        Pattern trPattern = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern cellPattern = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Pattern dateRegex = Pattern.compile("(\\d{2}\\.\\d{2}(?:\\.\\d{2,4})?)");
        Pattern cyrillicSubjectRegex = Pattern.compile("[А-Яа-яЁё]{3,}");
        Pattern gradeOrNRegex = Pattern.compile("^(?:[2345]|[1-9]\\d?(?:[.,]\\d+)?|[Нн]|н/я|неявка|зач(?:ет|тено)?|\\+|присутств.*)$", Pattern.CASE_INSENSITIVE);

        Matcher trMat = trPattern.matcher(html);
        List<String> currentHeaderDates = new ArrayList<>();
        String currentSectionSubject = "";

        while (trMat.find()) {
            String trInner = trMat.group(1);
            Matcher tdMat = cellPattern.matcher(trInner);
            List<String> cells = new ArrayList<>();
            while (tdMat.find()) {
                String cText = tdMat.group(1)
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
                cells.add(cText);
            }
            if (cells.isEmpty()) continue;

            // 1. Проверяем, не шапка ли это с датами (например: Предмет | 02.09 | 09.09 | 16.09 ...)
            int dateCountInRow = 0;
            for (String c : cells) {
                if (dateRegex.matcher(c).find()) dateCountInRow++;
            }
            if (dateCountInRow >= 2 && cells.size() >= 3) {
                currentHeaderDates.clear();
                for (String c : cells) {
                    Matcher dm = dateRegex.matcher(c);
                    currentHeaderDates.add(dm.find() ? dm.group(1) : c);
                }
                continue;
            }

            // 2. Строка-заголовок названия предмета
            if (cells.size() == 1 && cyrillicSubjectRegex.matcher(cells.get(0)).find()
                && cells.get(0).length() > 4 && cells.get(0).length() < 110) {
                String cand = cells.get(0);
                if (!cand.toLowerCase(Locale.ROOT).contains("дисциплин") && !cand.toLowerCase(Locale.ROOT).contains("расписани")) {
                    currentSectionSubject = cand;
                }
                continue;
            }

            // Определяем название предмета в строке
            String rowSubj = "";
            int firstDataCol = 1;
            for (int cIdx = 0; cIdx < Math.min(3, cells.size()); cIdx++) {
                String candidate = cells.get(cIdx);
                if (cyrillicSubjectRegex.matcher(candidate).find()
                    && !candidate.matches("^\\d+$")
                    && !dateRegex.matcher(candidate).matches()
                    && !gradeOrNRegex.matcher(candidate).matches()
                    && !candidate.equalsIgnoreCase("лекция")
                    && !candidate.equalsIgnoreCase("практика")
                    && !candidate.toLowerCase(Locale.ROOT).contains("практические")
                    && !candidate.toLowerCase(Locale.ROOT).contains("лабораторн")) {
                    rowSubj = candidate;
                    firstDataCol = cIdx + 1;
                    break;
                }
            }

            // 3. Если есть шапка дат (матричная таблица журнала)
            if (!rowSubj.isEmpty() && !currentHeaderDates.isEmpty() && cells.size() >= 3) {
                int maxCols = Math.min(cells.size(), currentHeaderDates.size());
                for (int col = firstDataCol; col < maxCols; col++) {
                    String cellVal = cells.get(col).trim();
                    if (cellVal.isEmpty() || "-".equals(cellVal) || "—".equals(cellVal) || ".".equals(cellVal)) continue;
                    String dateLabel = currentHeaderDates.get(col);
                    if (dateLabel == null || dateLabel.isEmpty() || !dateRegex.matcher(dateLabel).find()) {
                        dateLabel = todayDateShort;
                    }
                    recordDiaryCell(rowSubj, dateLabel, "col" + col, cellVal, hadBaseline, oldSnap, newSnap, recentEntries, uniqueAllEntries, newAlerts);
                }
                continue;
            }

            // 4. Построчная таблица дневника (где в одной из колонок есть дата dd.mm.yyyy или dd.mm, а в другой — оценка / Н / статус)
            String foundDate = "";
            int dateColIdx = -1;
            for (int i = 0; i < cells.size(); i++) {
                Matcher dm = dateRegex.matcher(cells.get(i));
                if (dm.find()) {
                    foundDate = dm.group(1);
                    dateColIdx = i;
                    break;
                }
            }

            if (!foundDate.isEmpty() && cells.size() >= 2) {
                String subjName = !rowSubj.isEmpty() ? rowSubj : (!currentSectionSubject.isEmpty() ? currentSectionSubject : "");
                if (subjName.isEmpty()) continue;

                // Ищем ячейку со значением оценки или пропуска (с конца строки)
                String extractedVal = "";
                for (int i = cells.size() - 1; i >= 0; i--) {
                    if (i == dateColIdx) continue;
                    String v = cells.get(i).trim();
                    if (v.isEmpty() || v.equals(subjName) || "-".equals(v) || "—".equals(v)) continue;
                    if (gradeOrNRegex.matcher(v).matches()) {
                        extractedVal = v;
                        break;
                    }
                    String vl = v.toLowerCase(Locale.ROOT);
                    if (vl.equals("н") || vl.equals("н/я") || vl.contains("неявк") || vl.contains("пропуск")) {
                        extractedVal = "Н";
                        break;
                    }
                    if (vl.contains("присутств") || vl.contains("отмечен")) {
                        extractedVal = "+";
                        break;
                    }
                    // Если в последней колонке стоит оценка с комментарием
                    Matcher numInside = Pattern.compile("(?:оценка|балл|отметка)?\\s*[:=-]?\\s*([2345])\\b", Pattern.CASE_INSENSITIVE).matcher(v);
                    if (numInside.find()) {
                        extractedVal = numInside.group(1);
                        break;
                    }
                }

                if (!extractedVal.isEmpty()) {
                    recordDiaryCell(subjName, foundDate, "row_" + cells.get(0), extractedVal, hadBaseline, oldSnap, newSnap, recentEntries, uniqueAllEntries, newAlerts);
                }
            }
        }
    }

    private void recordDiaryCell(
        String subj,
        String dateStr,
        String colId,
        String rawVal,
        boolean hadBaseline,
        JSONObject oldSnap,
        JSONObject newSnap,
        List<JSONObject> recentEntries,
        Map<String, JSONObject> uniqueAllEntries,
        List<String> newAlerts
    ) throws Exception {
        String cleanVal = rawVal.trim();
        if (cleanVal.equalsIgnoreCase("н/я") || cleanVal.equalsIgnoreCase("неявка") || cleanVal.equalsIgnoreCase("пропуск") || cleanVal.equalsIgnoreCase("н")) {
            cleanVal = "Н";
        }
        String shortDate = dateStr.length() >= 5 ? dateStr.substring(0, 5) : dateStr;
        String cellKey = subj + "||" + shortDate + "||" + colId;
        String prevVal = oldSnap.optString(cellKey, "");
        newSnap.put(cellKey, cleanVal);

        JSONObject entry = new JSONObject();
        entry.put("key", cellKey);
        entry.put("subject", subj);
        entry.put("date", shortDate);
        entry.put("fullDate", dateStr);
        entry.put("value", cleanVal);

        recentEntries.add(entry);
        uniqueAllEntries.put(cellKey, entry);

        if (hadBaseline && !cleanVal.equals(prevVal) && !"+".equals(cleanVal)) {
            newAlerts.add("Предмет: " + subj + " • Число: " + shortDate + " • В клетке: «" + cleanVal + "»");
        }
    }

    /**
     * Дополняет Дневник данными о посещениях/пропусках из расписания (по прошедшим дням семестра),
     * чтобы статистика пропусков (Н) и посещаемости работала даже тогда, когда преподаватель ведет учет только через кнопки в расписании.
     */
    private void syncAttendanceFromScheduleDays(
        AppStorage storage,
        Map<String, JSONObject> uniqueAllEntries,
        List<JSONObject> recentEntries
    ) {
        try {
            JSONArray days = storage.readJsonArray(AppStorage.FILE_SEMESTER_DAYS);
            if (days == null || days.length() == 0) return;

            SimpleDateFormat df = new SimpleDateFormat("dd.MM.yyyy", Locale.US);
            Date now = new Date();
            String todayStr = df.format(now);

            for (int i = 0; i < days.length(); i++) {
                JSONObject d = days.optJSONObject(i);
                if (d == null) continue;
                String dStr = d.optString("dateStr", "");
                String shortDate = d.optString("shortDate", dStr.length() >= 5 ? dStr.substring(0, 5) : dStr);
                int dowIdx = d.optInt("dowIdx", -1);
                if (dStr.isEmpty()) continue;

                Date lessonDate = df.parse(dStr);
                if (lessonDate == null || lessonDate.after(now)) continue;
                boolean isToday = todayStr.equals(dStr);

                JSONArray lessons = d.optJSONArray("lessons");
                if (lessons == null) continue;

                for (int k = 0; k < lessons.length(); k++) {
                    JSONObject les = lessons.optJSONObject(k);
                    if (les == null) continue;
                    String subj = les.optString("subjectTitle", "");
                    String st = les.optString("startTime", "");
                    String teach = les.optString("teacher", "");
                    String rm = les.optString("room", "");
                    boolean checked = les.optBoolean("checked", false);

                    if (subj.isEmpty() || !storage.isSubgroupAllowed(dowIdx, st, subj, teach, rm)) {
                        continue;
                    }

                    // Если это сегодняшний день и пара еще не закончилась — не ставим пропуск
                    if (isToday && !checked) continue;

                    String schedKey = subj + "||" + shortDate + "||sched_" + st;
                    // Проверяем, нет ли уже оценки из журнала за эту дату по этому предмету
                    boolean alreadyHasJournalEntry = false;
                    for (String existingKey : uniqueAllEntries.keySet()) {
                        if (existingKey.startsWith(subj + "||" + shortDate + "||") && !existingKey.contains("sched_")) {
                            alreadyHasJournalEntry = true;
                            break;
                        }
                    }
                    if (alreadyHasJournalEntry) continue;

                    JSONObject entry = new JSONObject();
                    entry.put("key", schedKey);
                    entry.put("subject", subj);
                    entry.put("date", shortDate);
                    entry.put("fullDate", dStr);
                    entry.put("value", checked ? "+" : "Н");
                    uniqueAllEntries.put(schedKey, entry);
                    if (recentEntries.size() < 30) {
                        recentEntries.add(entry);
                    }
                }
            }
        } catch (Exception ignored) {}
    }
}
