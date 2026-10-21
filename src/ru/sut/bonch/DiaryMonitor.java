package ru.sut.bonch;

import android.content.Context;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class DiaryMonitor {
    private long lastDiaryPollMillis = 0L;

    public boolean isTimeForPeriodicPoll() {
        return (System.currentTimeMillis() - lastDiaryPollMillis) > 600000L;
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
            List<String> newAlerts = new ArrayList<>();

            Pattern trPattern = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern cellPattern = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern dateRegex = Pattern.compile("(\\d{2}\\.\\d{2}(?:\\.\\d{2,4})?)");
            Pattern cyrillicSubjectRegex = Pattern.compile("[А-Яа-яЁё]{4,}");

            String todayDateShort = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());

            for (String url : SutApiClient.DIARY_URLS) {
                String html = api.httpRequest("POST", url, "");
                if (html.length() < 40 || !html.toLowerCase(Locale.ROOT).contains("<table")) continue;

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

                    if (cells.size() == 1 && cyrillicSubjectRegex.matcher(cells.get(0)).find()
                        && cells.get(0).length() > 5 && cells.get(0).length() < 90) {
                        currentSectionSubject = cells.get(0);
                        continue;
                    }

                    String rowSubj = "";
                    int firstDataCol = 1;
                    if (cyrillicSubjectRegex.matcher(cells.get(0)).find() && !cells.get(0).matches("^\\d+$")) {
                        rowSubj = cells.get(0);
                        firstDataCol = 1;
                    } else if (cells.size() >= 2 && cyrillicSubjectRegex.matcher(cells.get(1)).find()) {
                        rowSubj = cells.get(1);
                        firstDataCol = 2;
                    }

                    if (!rowSubj.isEmpty() && !currentHeaderDates.isEmpty() && cells.size() == currentHeaderDates.size()) {
                        for (int col = firstDataCol; col < cells.size(); col++) {
                            String cellVal = cells.get(col).trim();
                            if (cellVal.isEmpty() || "-".equals(cellVal) || "—".equals(cellVal)) continue;
                            String dateLabel = currentHeaderDates.get(col);
                            if (dateLabel == null || dateLabel.isEmpty()) dateLabel = todayDateShort;

                            String cellKey = rowSubj + "||" + dateLabel + "||col" + col;
                            String prevVal = oldSnap.optString(cellKey, "");
                            newSnap.put(cellKey, cellVal);

                            JSONObject entry = new JSONObject();
                            entry.put("subject", rowSubj);
                            entry.put("date", dateLabel);
                            entry.put("value", cellVal);
                            recentEntries.add(entry);

                            if (hadBaseline && !cellVal.equals(prevVal)) {
                                newAlerts.add("Предмет: " + rowSubj + " • Число: " + dateLabel + " • В клетке: «" + cellVal + "»");
                            }
                        }
                        continue;
                    }

                    String foundDate = "";
                    for (String c : cells) {
                        Matcher dm = dateRegex.matcher(c);
                        if (dm.find()) {
                              foundDate = dm.group(1);
                              break;
                        }
                    }

                    if (!foundDate.isEmpty() && cells.size() >= 3) {
                        String subjName = !rowSubj.isEmpty() ? rowSubj : (!currentSectionSubject.isEmpty() ? currentSectionSubject : "Дисциплина");
                        String lastCell = cells.get(cells.size() - 1).trim();
                        String prevCell = cells.size() >= 4 ? cells.get(cells.size() - 2).trim() : "";
                        String combinedVal = (!lastCell.isEmpty() && !lastCell.equals(foundDate)) ? lastCell : prevCell;

                        if (!combinedVal.isEmpty() && !"-".equals(combinedVal) && !combinedVal.equals(subjName)) {
                            String cellKey = subjName + "||" + foundDate + "||" + cells.get(0);
                            String oldVal = oldSnap.optString(cellKey, "");
                            newSnap.put(cellKey, combinedVal);

                            JSONObject entry = new JSONObject();
                            entry.put("subject", subjName);
                            entry.put("date", foundDate);
                            entry.put("value", combinedVal);
                            recentEntries.add(entry);

                            if (hadBaseline && !combinedVal.equals(oldVal)) {
                                newAlerts.add("Предмет: " + subjName + " • Число: " + foundDate + " • В клетке: «" + combinedVal + "»");
                            }
                        }
                    }
                }
            }

            storage.writeText(AppStorage.FILE_DIARY_SNAPSHOT, newSnap.toString());

            JSONArray recentJson = new JSONArray();
            int startIdx = Math.max(0, recentEntries.size() - 25);
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
                storage.addLog("📚 Дневник проверен (ячеек: " + newSnap.length() + ", новых: " + newAlerts.size() + ").");
            }
        } catch (Exception e) {
            if (manualTrigger) {
                storage.addLog("Ошибка проверки Дневника: " + e.getMessage());
            }
        }
    }
}
