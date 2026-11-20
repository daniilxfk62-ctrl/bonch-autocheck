package ru.sut.bonch;

import android.content.Context;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class MessageManager {
    private long lastMessagesPollMillis = 0L;

    public boolean isTimeForPeriodicPoll(AppStorage storage) {
        long intervalMs = storage.getAutoUpdateIntervalMillis();
        if (intervalMs <= 0L) return false;
        long effectiveInterval = Math.min(intervalMs, 1800000L); // не реже раза в 30 мин, если автообновление включено
        return (System.currentTimeMillis() - lastMessagesPollMillis) >= effectiveInterval;
    }

    public synchronized void pollAll(
        Context ctx,
        SutApiClient api,
        AppStorage storage,
        String login,
        String password,
        boolean manualTrigger
    ) {
        lastMessagesPollMillis = System.currentTimeMillis();
        if (!api.isAuthenticated() && !api.authenticate(login, password, storage)) {
            if (manualTrigger) {
                storage.addLog("Ошибка входа в ЛК при синхронизации сообщений");
            }
            return;
        }

        int msgCount = pollPersonalMessages(ctx, api, storage, manualTrigger);
        int fileCount = pollGroupFiles(ctx, api, storage, manualTrigger);

        if (manualTrigger) {
            storage.addLog("💬 Синхронизация завершена (диалогов: " + msgCount + ", файлов группы: " + fileCount + ")");
        }
    }

    private List<String> buildCandidateUrls(AppStorage storage, String endpointKey, String[] fallbacks) {
        Set<String> urls = new LinkedHashSet<>();
        String discovered = storage.getDiscoveredEndpoints().optString(endpointKey, "").trim();
        if (!discovered.isEmpty()) {
            urls.add(discovered);
        }
        for (String u : fallbacks) {
            urls.add(u);
        }
        return new ArrayList<>(urls);
    }

    private String fetchWorkingPage(SutApiClient api, List<String> candidates, String[] outUsedUrl) {
        for (String url : candidates) {
            try {
                String html = api.httpRequest("POST", url, "");
                if (isValidContentHtml(html)) {
                    outUsedUrl[0] = url;
                    return html;
                }
                html = api.httpRequest("GET", url, null);
                if (isValidContentHtml(html)) {
                    outUsedUrl[0] = url;
                    return html;
                }
            } catch (Exception ignored) {}
        }
        return "";
    }

    private boolean isValidContentHtml(String html) {
        if (html == null || html.trim().length() < 60) return false;
        String low = html.toLowerCase(Locale.ROOT);
        if (low.contains("autentification") || low.contains("name=\"parole\"")) return false;
        if (low.contains("404 not found") || low.contains("страница не найдена")) return false;
        return low.contains("<table") || low.contains("<a ") || low.contains("<tr") || low.contains("сообщен") || low.contains("файл");
    }

    private int pollPersonalMessages(Context ctx, SutApiClient api, AppStorage storage, boolean manualTrigger) {
        try {
            String[] usedUrl = new String[]{""};
            List<String> candidates = buildCandidateUrls(storage, "messages", SutApiClient.MESSAGE_URLS);
            String html = fetchWorkingPage(api, candidates, usedUrl);
            if (html.isEmpty()) {
                return storage.readJsonArray(AppStorage.FILE_MESSAGES).length();
            }

            if (!usedUrl[0].isEmpty()) {
                storage.saveDiscoveredEndpoint("messages", usedUrl[0]);
            }

            // Загружаем локальное состояние (чтобы не терять отметки «прочитано» и локальные ответы)
            JSONArray existingArr = storage.readJsonArray(AppStorage.FILE_MESSAGES);
            Map<String, JSONObject> existingBySender = new LinkedHashMap<>();
            Set<String> readLocallyIds = new HashSet<>();
            for (int i = 0; i < existingArr.length(); i++) {
                JSONObject obj = existingArr.optJSONObject(i);
                if (obj == null) continue;
                String sender = obj.optString("sender", "");
                if (!sender.isEmpty()) {
                    existingBySender.put(sender, obj);
                }
                if (!obj.optBoolean("unread", false)) {
                    readLocallyIds.add(obj.optString("id", ""));
                    readLocallyIds.add(obj.optString("serverId", ""));
                }
            }

            String snapRaw = storage.readText(AppStorage.FILE_MESSAGES_SNAPSHOT, "");
            boolean hadBaseline = !snapRaw.isEmpty();
            JSONObject oldSnap = hadBaseline ? new JSONObject(snapRaw) : new JSONObject();
            JSONObject newSnap = new JSONObject();
            Iterator<String> snapKeys = oldSnap.keys();
            while (snapKeys.hasNext()) {
                String k = snapKeys.next();
                newSnap.put(k, oldSnap.optString(k, ""));
            }

            Map<String, JSONObject> threadsBySender = new LinkedHashMap<>();
            List<String[]> newMessageAlerts = new ArrayList<>();

            Pattern trPat = Pattern.compile("<tr([^>]*)>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern tdPat = Pattern.compile("<t[dh][^>]*>(.*?)</t[dh]>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern datePat = Pattern.compile("(\\d{2}\\.\\d{2}(?:\\.\\d{2,4})?(?:\\s+\\d{1,2}:\\d{2})?|\\d{1,2}:\\d{2})");
            Pattern msgIdPat = Pattern.compile("(?:show_message|open_message|read_message|show_mes|prosmotr|del_mes)\\s*\\(\\s*['\"]?(\\d+)['\"]?", Pattern.CASE_INSENSITIVE);
            Pattern fioPat = Pattern.compile("([А-ЯЁ][а-яё\\-]+\\s+[А-ЯЁ]\\.[А-ЯЁ]\\.|[А-ЯЁ][а-яё\\-]+\\s+[А-ЯЁ][а-яё]+(?:\\s+[А-ЯЁ][а-яё]+)?)");

            Matcher trMat = trPat.matcher(html);
            int fetchedDetailsCount = 0;
            int rowCounter = 0;

            while (trMat.find()) {
                String trAttrs = trMat.group(1);
                String trInner = trMat.group(2);
                String trLow = (trAttrs + " " + trInner).toLowerCase(Locale.ROOT);

                // Пропускаем шапки таблиц и служебные скрытые строки без ячеек
                if (trLow.contains("<th") && !trLow.contains("<td")) continue;

                Matcher tdMat = tdPat.matcher(trInner);
                List<String> cells = new ArrayList<>();
                while (tdMat.find()) {
                    String text = cleanHtml(tdMat.group(1));
                    cells.add(text);
                }
                if (cells.size() < 2) continue;

                String joinedLow = cleanHtml(trInner).toLowerCase(Locale.ROOT);
                if (joinedLow.startsWith("дата") && (joinedLow.contains("тема") || joinedLow.contains("отправител"))) {
                    continue;
                }

                String foundDate = "";
                int dateIdx = -1;
                for (int i = 0; i < cells.size(); i++) {
                    Matcher dm = datePat.matcher(cells.get(i));
                    if (dm.find()) {
                        foundDate = dm.group(1).trim();
                        dateIdx = i;
                        break;
                    }
                }
                if (foundDate.isEmpty() && cells.size() < 3) continue;

                Matcher idMat = msgIdPat.matcher(trInner);
                String serverId = idMat.find() ? idMat.group(1) : "";

                String sender = "";
                String subject = "";
                String bodyPreview = "";

                for (int i = 0; i < cells.size(); i++) {
                    if (i == dateIdx) continue;
                    String c = cells.get(i);
                    if (c.isEmpty() || c.matches("^\\d+$") || c.equalsIgnoreCase("удалить") || c.equalsIgnoreCase("ответить")) {
                        continue;
                    }
                    if (sender.isEmpty() && (fioPat.matcher(c).find() || c.toLowerCase(Locale.ROOT).contains("деканат") || c.toLowerCase(Locale.ROOT).contains("отдел"))) {
                        sender = c;
                    } else if (subject.isEmpty()) {
                        subject = c;
                    } else if (bodyPreview.isEmpty()) {
                        bodyPreview = c;
                    }
                }

                if (sender.isEmpty()) {
                    if (!subject.isEmpty()) {
                        sender = subject;
                        subject = "Сообщение из ЛК";
                    } else {
                        continue;
                    }
                }
                if (subject.isEmpty()) subject = "Личное сообщение";

                // Если текст сообщения скрыт за кликом — пробуем подгрузить тело для первых 12 свежих писем
                String fullText = bodyPreview;
                if (fullText.isEmpty() && !serverId.isEmpty() && fetchedDetailsCount < 12 && !usedUrl[0].isEmpty()) {
                    fetchedDetailsCount++;
                    String fetched = fetchMessageBody(api, usedUrl[0], serverId);
                    if (!fetched.isEmpty()) {
                        fullText = fetched;
                    }
                }
                if (fullText.isEmpty()) {
                    fullText = subject;
                }

                boolean looksUnread = trLow.contains("<b")
                    || trLow.contains("<strong")
                    || trLow.contains("font-weight:bold")
                    || trLow.contains("font-weight: bold")
                    || trLow.contains("unread")
                    || trLow.contains("new_mes")
                    || trLow.contains("не прочит");

                String msgKey = !serverId.isEmpty()
                    ? ("msg_" + serverId)
                    : ("msg_" + Math.abs((sender + "|" + foundDate + "|" + subject).hashCode()));
                String timeShort = formatMessageTime(foundDate);

                boolean isUnread = looksUnread && !readLocallyIds.contains(msgKey) && !readLocallyIds.contains(serverId);
                boolean isMuted = storage.isSenderMuted(sender);

                // Проверяем новизну для уведомлений
                if (!oldSnap.has(msgKey)) {
                    newSnap.put(msgKey, fullText.substring(0, Math.min(80, fullText.length())));
                    if (hadBaseline && !isMuted) {
                        newMessageAlerts.add(new String[]{sender, fullText});
                    }
                } else {
                    newSnap.put(msgKey, oldSnap.optString(msgKey, ""));
                }

                JSONObject threadObj = threadsBySender.get(sender);
                if (threadObj == null) {
                    threadObj = new JSONObject();
                    threadObj.put("id", "m_" + Math.abs(sender.hashCode()));
                    threadObj.put("serverId", serverId);
                    threadObj.put("sender", sender);
                    threadObj.put("initials", buildInitials(sender));
                    threadObj.put("role", subject);
                    threadObj.put("time", timeShort);
                    threadObj.put("unread", isUnread);
                    threadObj.put("muted", isMuted);
                    threadObj.put("thread", new JSONArray());
                    threadsBySender.put(sender, threadObj);
                } else {
                    if (isUnread) threadObj.put("unread", true);
                    if (!serverId.isEmpty() && threadObj.optString("serverId", "").isEmpty()) {
                        threadObj.put("serverId", serverId);
                    }
                }

                JSONObject bubble = new JSONObject();
                bubble.put("from", "them");
                bubble.put("text", fullText);
                bubble.put("time", timeShort);
                bubble.put("serverId", serverId);
                threadObj.getJSONArray("thread").put(bubble);

                rowCounter++;
                if (rowCounter >= 60) break;
            }

            // Сохраняем локальные ответы пользователя ("from": "me") из старого кэша
            for (Map.Entry<String, JSONObject> entry : threadsBySender.entrySet()) {
                JSONObject oldThread = existingBySender.get(entry.getKey());
                if (oldThread == null) continue;
                JSONArray oldBubbles = oldThread.optJSONArray("thread");
                if (oldBubbles == null) continue;
                JSONArray curBubbles = entry.getValue().getJSONArray("thread");
                for (int i = 0; i < oldBubbles.length(); i++) {
                    JSONObject b = oldBubbles.optJSONObject(i);
                    if (b != null && "me".equals(b.optString("from", ""))) {
                        curBubbles.put(b);
                    }
                }
            }

            storage.writeText(AppStorage.FILE_MESSAGES_SNAPSHOT, newSnap.toString());

            JSONArray resultArr = new JSONArray();
            for (JSONObject t : threadsBySender.values()) {
                resultArr.put(t);
            }
            if (resultArr.length() > 0 || existingArr.length() == 0) {
                storage.writeText(AppStorage.FILE_MESSAGES, resultArr.toString());
            }

            if (!newMessageAlerts.isEmpty() && storage.isNotifyMessagesEnabled()) {
                for (String[] alert : newMessageAlerts) {
                    String title = "💬 " + alert[0];
                    String preview = alert[1].length() > 140 ? alert[1].substring(0, 140) + "…" : alert[1];
                    storage.addLog("Новое сообщение от " + alert[0]);
                    NotificationHelper.sendAlert(ctx, title, preview);
                }
            }

            return resultArr.length();
        } catch (Exception e) {
            if (manualTrigger) {
                storage.addLog("Ошибка синхронизации сообщений: " + e.getMessage());
            }
            return 0;
        }
    }

    private String fetchMessageBody(SutApiClient api, String baseUrl, String serverId) {
        try {
            String body = "id=" + serverId + "&prosmotr=1&open=1";
            String resp = api.httpRequest("POST", baseUrl + "?" + body, body);
            if (resp == null || resp.trim().length() < 10) return "";
            if (resp.toLowerCase(Locale.ROOT).contains("<table") && resp.length() > 4000) {
                // Сервер вернул всю страницу целиком вместо текста одного сообщения
                return "";
            }
            String text = cleanHtml(resp);
            if (text.length() > 600) text = text.substring(0, 600) + "…";
            return text;
        } catch (Exception e) {
            return "";
        }
    }

    private int pollGroupFiles(Context ctx, SutApiClient api, AppStorage storage, boolean manualTrigger) {
        try {
            String[] usedUrl = new String[]{""};
            List<String> candidates = buildCandidateUrls(storage, "groupFiles", SutApiClient.GROUP_FILES_URLS);
            String html = fetchWorkingPage(api, candidates, usedUrl);
            if (html.isEmpty()) {
                return storage.readJsonArray(AppStorage.FILE_GROUP_FILES).length();
            }

            if (!usedUrl[0].isEmpty()) {
                storage.saveDiscoveredEndpoint("groupFiles", usedUrl[0]);
            }

            String snapRaw = storage.readText(AppStorage.FILE_MESSAGES_SNAPSHOT, "");
            boolean hadBaseline = !snapRaw.isEmpty();
            JSONObject snap = hadBaseline ? new JSONObject(snapRaw) : new JSONObject();

            List<JSONObject> filesList = new ArrayList<>();
            Set<String> seenUrls = new HashSet<>();
            List<String> newFileAlerts = new ArrayList<>();

            Pattern trPat = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern tdPat = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern linkPat = Pattern.compile("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern datePat = Pattern.compile("(\\d{2}\\.\\d{2}\\.\\d{2,4})");
            Pattern sizePat = Pattern.compile("(\\d+(?:[.,]\\d+)?\\s*(?:Б|КБ|МБ|ГБ|KB|MB|GB))", Pattern.CASE_INSENSITIVE);
            Pattern fioPat = Pattern.compile("([А-ЯЁ][а-яё\\-]+\\s+[А-ЯЁ]\\.[А-ЯЁ]\\.)");

            Matcher trMat = trPat.matcher(html);
            int idx = 0;

            while (trMat.find()) {
                String trInner = trMat.group(1);
                Matcher lm = linkPat.matcher(trInner);
                while (lm.find()) {
                    String href = lm.group(1).trim();
                    String linkText = cleanHtml(lm.group(2));
                    if (href.isEmpty() || href.startsWith("#") || href.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                        continue;
                    }
                    String lowHref = href.toLowerCase(Locale.ROOT);
                    boolean looksLikeFile = lowHref.matches(".*\\.(pdf|docx?|pptx?|xlsx?|zip|rar|7z|txt|rtf|png|jpe?g|csv|py|cpp|java|c|h)(\\?.*)?$")
                        || lowHref.contains("download")
                        || lowHref.contains("file")
                        || lowHref.contains("upload");
                    if (!looksLikeFile) continue;

                    String absUrl = SutApiClient.resolveUrl(href);
                    if (seenUrls.contains(absUrl)) continue;
                    seenUrls.add(absUrl);

                    String fileName = !linkText.isEmpty() ? linkText : extractFilenameFromUrl(absUrl);
                    String ext = extractExtension(fileName, absUrl);

                    // Парсим соседние ячейки строки (предмет, автор, дата, размер)
                    List<String> cells = new ArrayList<>();
                    Matcher tdMat = tdPat.matcher(trInner);
                    while (tdMat.find()) {
                        cells.add(cleanHtml(tdMat.group(1)));
                    }

                    String rowPlain = cleanHtml(trInner);
                    Matcher dm = datePat.matcher(rowPlain);
                    String dateStr = dm.find() ? dm.group(1) : "";
                    Matcher sm = sizePat.matcher(rowPlain);
                    String sizeStr = sm.find() ? sm.group(1) : "Файл ЛК";
                    Matcher fm = fioPat.matcher(rowPlain);
                    String author = fm.find() ? fm.group(1) : "Преподаватель";

                    String subject = "Файлы группы";
                    for (String c : cells) {
                        if (c.isEmpty() || c.equals(fileName) || c.equals(dateStr) || c.equals(author) || c.matches("^\\d+$")) {
                            continue;
                        }
                        if (c.length() >= 3 && c.length() <= 70 && !c.contains(fileName)) {
                            subject = c;
                            break;
                        }
                    }

                    idx++;
                    String fileKey = "file_" + Math.abs(absUrl.hashCode());
                    JSONObject fObj = new JSONObject();
                    fObj.put("id", fileKey);
                    fObj.put("name", fileName);
                    fObj.put("ext", ext);
                    fObj.put("subject", subject);
                    fObj.put("size", sizeStr);
                    fObj.put("date", dateStr.isEmpty() ? "Недавно" : dateStr);
                    fObj.put("author", author);
                    fObj.put("url", absUrl);
                    filesList.add(fObj);

                    if (!snap.has(fileKey)) {
                        snap.put(fileKey, fileName);
                        if (hadBaseline) {
                            newFileAlerts.add(fileName + " (" + subject + ")");
                        }
                    }
                }
            }

            storage.writeText(AppStorage.FILE_MESSAGES_SNAPSHOT, snap.toString());

            JSONArray outArr = new JSONArray();
            for (JSONObject f : filesList) {
                outArr.put(f);
            }
            if (outArr.length() > 0 || storage.readJsonArray(AppStorage.FILE_GROUP_FILES).length() == 0) {
                storage.writeText(AppStorage.FILE_GROUP_FILES, outArr.toString());
            }

            if (!newFileAlerts.isEmpty() && storage.isNotifyMessagesEnabled()) {
                String msg = newFileAlerts.size() == 1
                    ? newFileAlerts.get(0)
                    : (newFileAlerts.get(0) + " и ещё " + (newFileAlerts.size() - 1));
                storage.addLog("📎 Новый файл группы: " + msg);
                NotificationHelper.sendAlert(ctx, "📎 Новый файл группы", msg);
            }

            return outArr.length();
        } catch (Exception e) {
            if (manualTrigger) {
                storage.addLog("Ошибка синхронизации файлов группы: " + e.getMessage());
            }
            return 0;
        }
    }

    public synchronized void markThreadRead(AppStorage storage, String threadId) {
        try {
            JSONArray arr = storage.readJsonArray(AppStorage.FILE_MESSAGES);
            boolean changed = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && threadId.equals(obj.optString("id", ""))) {
                    if (obj.optBoolean("unread", false)) {
                        obj.put("unread", false);
                        changed = true;
                    }
                    break;
                }
            }
            if (changed) {
                storage.writeText(AppStorage.FILE_MESSAGES, arr.toString());
            }
        } catch (Exception ignored) {}
    }

    public synchronized boolean toggleSenderMute(AppStorage storage, String threadId) {
        try {
            JSONArray arr = storage.readJsonArray(AppStorage.FILE_MESSAGES);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && threadId.equals(obj.optString("id", ""))) {
                    String sender = obj.optString("sender", "");
                    boolean nextMuted = !storage.isSenderMuted(sender);
                    storage.setSenderMuted(sender, nextMuted);
                    obj.put("muted", nextMuted);
                    storage.writeText(AppStorage.FILE_MESSAGES, arr.toString());
                    return nextMuted;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public synchronized boolean sendReply(
        SutApiClient api,
        AppStorage storage,
        String login,
        String password,
        String threadId,
        String replyText
    ) {
        if (replyText == null || replyText.trim().isEmpty()) return false;
        String cleanText = replyText.trim();
        String nowTime = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());

        try {
            JSONArray arr = storage.readJsonArray(AppStorage.FILE_MESSAGES);
            JSONObject targetThread = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && threadId.equals(obj.optString("id", ""))) {
                    targetThread = obj;
                    break;
                }
            }

            if (targetThread == null) return false;

            String serverId = targetThread.optString("serverId", "");
            String subject = targetThread.optString("role", "Ответ");
            boolean sentToServer = false;

            if (!serverId.isEmpty() && (api.isAuthenticated() || api.authenticate(login, password, storage))) {
                List<String> candidates = buildCandidateUrls(storage, "messages", SutApiClient.MESSAGE_URLS);
                String targetUrl = candidates.get(0);
                try {
                    String payload = "save=1&id=" + URLEncoder.encode(serverId, "UTF-8")
                        + "&title=" + URLEncoder.encode("Re: " + subject, "UTF-8")
                        + "&mes_otvet=" + URLEncoder.encode(cleanText, "UTF-8")
                        + "&text=" + URLEncoder.encode(cleanText, "UTF-8");
                    String resp = api.httpRequest("POST", targetUrl, payload);
                    sentToServer = resp != null && !resp.toLowerCase(Locale.ROOT).contains("ошибка");
                } catch (Exception ignored) {}
            }

            JSONObject bubble = new JSONObject();
            bubble.put("from", "me");
            bubble.put("text", cleanText);
            bubble.put("time", nowTime);
            targetThread.getJSONArray("thread").put(bubble);
            targetThread.put("time", nowTime);
            targetThread.put("unread", false);
            storage.writeText(AppStorage.FILE_MESSAGES, arr.toString());

            storage.addLog(sentToServer
                ? ("📤 Ответ отправлен: " + targetThread.optString("sender", ""))
                : ("📤 Ответ сохранён локально (проверьте ЛК): " + targetThread.optString("sender", "")));
            return true;
        } catch (Exception e) {
            storage.addLog("Ошибка отправки сообщения: " + e.getMessage());
            return false;
        }
    }

    private static String cleanHtml(String raw) {
        if (raw == null) return "";
        return raw
            .replaceAll("(?s)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?s)<style[^>]*>.*?</style>", " ")
            .replaceAll("<[^>]+>", " ")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&amp;", "&")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static String buildInitials(String name) {
        if (name == null || name.trim().isEmpty()) return "ЛК";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String cleaned = p.replaceAll("[^А-Яа-яЁёA-Za-z]", "");
            if (!cleaned.isEmpty()) {
                sb.append(cleaned.substring(0, 1).toUpperCase(Locale.ROOT));
                if (sb.length() == 2) break;
            }
        }
        return sb.length() > 0 ? sb.toString() : "ЛК";
    }

    private static String formatMessageTime(String rawDate) {
        if (rawDate == null || rawDate.trim().isEmpty()) return "";
        String s = rawDate.trim();
        String today = new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(new Date());
        if (s.startsWith(today) && s.length() > 11) {
            return s.substring(11).trim();
        }
        if (s.length() >= 10 && s.matches("^\\d{2}\\.\\d{2}\\.\\d{4}.*")) {
            return s.substring(0, 5);
        }
        return s;
    }

    private static String extractFilenameFromUrl(String url) {
        try {
            int q = url.indexOf('?');
            String path = q >= 0 ? url.substring(0, q) : url;
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            return name.isEmpty() ? "Документ_ЛК" : name;
        } catch (Exception e) {
            return "Документ_ЛК";
        }
    }

    private static String extractExtension(String fileName, String url) {
        String source = fileName.contains(".") ? fileName : url;
        int q = source.indexOf('?');
        if (q >= 0) source = source.substring(0, q);
        int dot = source.lastIndexOf('.');
        if (dot >= 0 && dot < source.length() - 1) {
            String ext = source.substring(dot + 1).replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
            if (!ext.isEmpty() && ext.length() <= 5) return ext;
        }
        return "FILE";
    }
}