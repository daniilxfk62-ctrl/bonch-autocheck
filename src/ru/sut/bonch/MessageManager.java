package ru.sut.bonch;

import android.content.Context;
import java.io.File;
import java.net.URLEncoder;
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

public class MessageManager {
    private long lastMessagesPollMillis = 0L;

    // Точные паттерны строк lk.sut.ru: <tr id='tr_3908722'> и onclick="tr_click(3908722);"
    private static final Pattern TR_ID_PAT = Pattern.compile("id\\s*=\\s*['\"]?tr_(\\d+)['\"]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern TR_CLICK_PAT = Pattern.compile("tr_click\\s*\\(\\s*['\"]?(\\d+)['\"]?\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_ADRESAT_PAT = Pattern.compile(
        "<select[^>]*name\\s*=\\s*['\"]?adresat['\"]?[^>]*>(.*?)</select>",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern OPTION_PAT = Pattern.compile(
        "<option[^>]*value\\s*=\\s*['\"]?(\\d+)['\"]?[^>]*>(.*?)</option>",
        Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern IDINFO_JSON_PAT = Pattern.compile(
        "['\"]?idinfo['\"]?\\s*[:=]\\s*['\"]?(\\d+)['\"]?",
        Pattern.CASE_INSENSITIVE
    );

    public boolean isTimeForPeriodicPoll(AppStorage storage) {
        long intervalMs = storage.getAutoUpdateIntervalMillis();
        if (intervalMs <= 0L) return false;
        long effectiveInterval = Math.min(intervalMs, 1800000L);
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

        int msgCount = pollPersonalMessages(ctx, api, storage, login, password, manualTrigger);
        int fileCount = pollGroupFiles(ctx, api, storage, login, password, manualTrigger);
        if (manualTrigger || storage.getMessageTeachers().length() == 0) {
            pollMessageTeachers(api, storage, login, password);
        }

        if (manualTrigger) {
            storage.addLog("💬 Синхронизация завершена (диалогов: " + msgCount + ", файлов группы: " + fileCount + ")");
        }
    }

    private String fetchExactPage(
        SutApiClient api,
        AppStorage storage,
        String login,
        String password,
        String primaryUrl
    ) {
        try {
            String html = api.httpRequest("POST", primaryUrl, "");
            if (isAuthExpiredHtml(html)) {
                if (api.authenticate(login, password, storage)) {
                    html = api.httpRequest("POST", primaryUrl, "");
                }
            }
            if (isValidContentHtml(html)) return html;

            html = api.httpRequest("GET", primaryUrl, null);
            if (isAuthExpiredHtml(html)) {
                if (api.authenticate(login, password, storage)) {
                    html = api.httpRequest("GET", primaryUrl, null);
                }
            }
            if (isValidContentHtml(html)) return html;
        } catch (Exception ignored) {}
        return "";
    }

    private boolean isAuthExpiredHtml(String html) {
        if (html == null || html.isEmpty()) return false;
        String low = html.toLowerCase(Locale.ROOT);
        return low.contains("autentification") || low.contains("name=\"parole\"") || low.contains("name='parole'");
    }

    private boolean isValidContentHtml(String html) {
        if (html == null || html.trim().length() < 40) return false;
        String low = html.toLowerCase(Locale.ROOT);
        if (isAuthExpiredHtml(html)) return false;
        if (low.contains("404 not found") || low.contains("страница не найдена")) return false;
        return low.contains("<table") || low.contains("<tr") || low.contains("tr_click") || low.contains("subconto/sendto");
    }

    private String extractRowServerId(String trAttrs, String trInner) {
        Matcher m1 = TR_ID_PAT.matcher(trAttrs != null ? trAttrs : "");
        if (m1.find()) return m1.group(1);
        Matcher m2 = TR_CLICK_PAT.matcher((trAttrs != null ? trAttrs : "") + " " + (trInner != null ? trInner : ""));
        if (m2.find()) return m2.group(1);
        return "";
    }

    private int pollPersonalMessages(
        Context ctx,
        SutApiClient api,
        AppStorage storage,
        String login,
        String password,
        boolean manualTrigger
    ) {
        try {
            String pageUrl = SutApiClient.MESSAGE_IN_URL;
            String html = fetchExactPage(api, storage, login, password, pageUrl);
            if (html.isEmpty()) {
                return storage.readJsonArray(AppStorage.FILE_MESSAGES).length();
            }

            JSONArray existingArr = storage.readJsonArray(AppStorage.FILE_MESSAGES);
            Map<String, JSONObject> existingBySender = new LinkedHashMap<>();
            Map<String, JSONObject> cachedBubbleByServerId = new LinkedHashMap<>();
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
                JSONArray th = obj.optJSONArray("thread");
                if (th != null) {
                    for (int j = 0; j < th.length(); j++) {
                        JSONObject b = th.optJSONObject(j);
                        if (b == null) continue;
                        String sid = b.optString("serverId", "");
                        String txt = b.optString("text", "");
                        if (!sid.isEmpty() && !txt.isEmpty() && b.optBoolean("detailsLoaded", false)) {
                            cachedBubbleByServerId.put(sid, b);
                        }
                    }
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
            Pattern linkPat = Pattern.compile("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern datePat = Pattern.compile("(\\d{2}[-.]\\d{2}(?:[-.]\\d{2,4})?(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?|\\d{1,2}:\\d{2})");
            Pattern fioPat = Pattern.compile("([А-ЯЁ][а-яё\\-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.|[А-ЯЁ][а-яё\\-]+\\s+[А-ЯЁ][а-яё]+(?:\\s+[А-ЯЁ][а-яё]+)?)");

            String sanitizedHtml = stripScriptsAndStyles(html);
            boolean hasServerRowIds = TR_ID_PAT.matcher(sanitizedHtml).find()
                || TR_CLICK_PAT.matcher(sanitizedHtml).find();

            Matcher trMat = trPat.matcher(sanitizedHtml);
            int fetchedDetailsCount = 0;
            int maxDetailsPerPass = manualTrigger ? 10 : 5;

            while (trMat.find()) {
                String trAttrs = trMat.group(1);
                String trInner = trMat.group(2);
                String trLow = (trAttrs + " " + trInner).toLowerCase(Locale.ROOT);

                if (trLow.contains("<th") && !trLow.contains("<td")) continue;

                String serverId = extractRowServerId(trAttrs, trInner);
                if (hasServerRowIds && serverId.isEmpty()) continue;

                Matcher tdMat = tdPat.matcher(trInner);
                List<String> cells = new ArrayList<>();
                while (tdMat.find()) {
                    cells.add(cleanHtml(tdMat.group(1)));
                }
                if (cells.size() < 2) continue;

                String joinedLow = cleanHtml(trInner).toLowerCase(Locale.ROOT);
                if (joinedLow.startsWith("дата") && (joinedLow.contains("тема") || joinedLow.contains("отправител"))) {
                    continue;
                }

                // Извлекаем прикреплённые файлы в строке сообщения (<a href='.../subconto/sendto/...'>)
                JSONArray rowAttachments = new JSONArray();
                Matcher lm = linkPat.matcher(trInner);
                while (lm.find()) {
                    String href = lm.group(1).trim();
                    if (href.toLowerCase(Locale.ROOT).contains("subconto/sendto")) {
                        String absUrl = SutApiClient.resolveUrl(pageUrl, href);
                        String aName = cleanHtml(lm.group(2));
                        if (aName.isEmpty()) aName = extractFilenameFromUrl(absUrl);
                        addUniqueAttachment(rowAttachments, aName, absUrl);
                    }
                }

                String foundDate = "";
                int dateIdx = -1;
                for (int i = 0; i < cells.size(); i++) {
                    Matcher dm = datePat.matcher(cells.get(i));
                    if (dm.find()) {
                        foundDate = dm.group(1).trim().replace('-', '.');
                        dateIdx = i;
                        break;
                    }
                }
                if (serverId.isEmpty() && foundDate.isEmpty() && cells.size() < 3) continue;

                String sender = "";
                String subject = "";
                String bodyPreview = "";

                for (int i = 0; i < cells.size(); i++) {
                    if (i == dateIdx) continue;
                    String c = cells.get(i);
                    if (c.isEmpty() || c.matches("^\\d+$") || isGarbageMessageToken(c)) {
                        continue;
                    }
                    if (sender.isEmpty() && (fioPat.matcher(c).find()
                        || c.toLowerCase(Locale.ROOT).contains("деканат")
                        || c.toLowerCase(Locale.ROOT).contains("отдел")
                        || c.toLowerCase(Locale.ROOT).contains("кафедр"))) {
                        sender = c;
                    } else if (subject.isEmpty()) {
                        subject = c;
                    } else if (bodyPreview.isEmpty()) {
                        bodyPreview = c;
                    }
                }

                if (sender.isEmpty()) {
                    if (!subject.isEmpty() && !isGarbageMessageToken(subject)) {
                        sender = subject;
                        subject = "Личное сообщение";
                    } else {
                        continue;
                    }
                }
                sender = sender.replaceAll("\\s*\\([^)]*(?:сотрудник|преподавател)[^)]*\\)", "").trim();
                if (isGarbageMessageToken(sender)) continue;
                if (subject.isEmpty()) subject = "Личное сообщение";

                // Открываем реальный текст, тему и вложения каждого сообщения через POST sendto2.php?id=<ID>&prosmotr
                String fullText = "";
                String detailTitle = "";
                String idinfo = "0";
                String itemnumber = serverId;
                boolean detailsLoaded = false;
                if (!serverId.isEmpty() && cachedBubbleByServerId.containsKey(serverId)) {
                    JSONObject cachedB = cachedBubbleByServerId.get(serverId);
                    fullText = cachedB.optString("text", "");
                    detailTitle = cachedB.optString("subject", "");
                    idinfo = cachedB.optString("idinfo", "0");
                    itemnumber = cachedB.optString("itemnumber", serverId);
                    detailsLoaded = true;
                    JSONArray cachedAtts = cachedB.optJSONArray("attachments");
                    if (cachedAtts != null) {
                        for (int ai = 0; ai < cachedAtts.length(); ai++) {
                            JSONObject aObj = cachedAtts.optJSONObject(ai);
                            if (aObj != null) {
                                addUniqueAttachment(rowAttachments, aObj.optString("name", ""), aObj.optString("url", ""));
                            }
                        }
                    }
                } else if (!serverId.isEmpty() && fetchedDetailsCount < maxDetailsPerPass) {
                    fetchedDetailsCount++;
                    ParsedMessageDetail det = fetchMessageDetailViaSendto2(api, serverId);
                    fullText = det.text;
                    detailTitle = det.title;
                    idinfo = det.idinfo;
                    itemnumber = det.itemnumber;
                    detailsLoaded = !fullText.isEmpty() || !detailTitle.isEmpty() || det.attachments.length() > 0;
                    for (int ai = 0; ai < det.attachments.length(); ai++) {
                        JSONObject aObj = det.attachments.optJSONObject(ai);
                        if (aObj != null) {
                            addUniqueAttachment(rowAttachments, aObj.optString("name", ""), aObj.optString("url", ""));
                        }
                    }
                }
                if (fullText.isEmpty()) {
                    fullText = !bodyPreview.isEmpty() ? bodyPreview : subject;
                }
                String msgSubject = !detailTitle.isEmpty()
                    ? detailTitle
                    : (!"Личное сообщение".equals(subject) ? subject : "");
                String msgCaption = (!bodyPreview.isEmpty()
                    && !bodyPreview.equals(fullText)
                    && !bodyPreview.equals(msgSubject)
                    && !fullText.contains(bodyPreview))
                    ? bodyPreview
                    : "";

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
                    threadObj.put("itemnumber", itemnumber);
                    threadObj.put("idinfo", idinfo);
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
                bubble.put("subject", msgSubject);
                bubble.put("caption", msgCaption);
                bubble.put("text", fullText);
                bubble.put("time", timeShort);
                bubble.put("date", foundDate);
                bubble.put("serverId", serverId);
                bubble.put("idinfo", idinfo);
                bubble.put("itemnumber", itemnumber);
                bubble.put("detailsLoaded", detailsLoaded);
                if (rowAttachments.length() > 0) {
                    bubble.put("attachments", rowAttachments);
                }
                threadObj.getJSONArray("thread").put(bubble);
            }

            // Сохраняем ранее загруженные сообщения (включая старые входящие и ответы "me")
            for (Map.Entry<String, JSONObject> entry : threadsBySender.entrySet()) {
                JSONObject oldThread = existingBySender.get(entry.getKey());
                if (oldThread == null) continue;
                JSONArray oldBubbles = oldThread.optJSONArray("thread");
                if (oldBubbles == null) continue;
                JSONArray curBubbles = entry.getValue().getJSONArray("thread");

                Set<String> presentKeys = new HashSet<>();
                for (int i = 0; i < curBubbles.length(); i++) {
                    JSONObject cb = curBubbles.optJSONObject(i);
                    if (cb == null) continue;
                    String sid = cb.optString("serverId", "");
                    presentKeys.add(!sid.isEmpty()
                        ? ("sid:" + sid)
                        : ("txt:" + cb.optString("from", "") + "|" + cb.optString("date", "") + "|" + cb.optString("text", "")));
                }

                for (int i = 0; i < oldBubbles.length(); i++) {
                    JSONObject b = oldBubbles.optJSONObject(i);
                    if (b == null) continue;
                    String sid = b.optString("serverId", "");
                    String key = !sid.isEmpty()
                        ? ("sid:" + sid)
                        : ("txt:" + b.optString("from", "") + "|" + b.optString("date", "") + "|" + b.optString("text", ""));
                    if (!presentKeys.contains(key)) {
                        presentKeys.add(key);
                        curBubbles.put(b);
                    }
                }
            }

            // Сохраняем более старые диалоги, которые уже не отображаются на текущей странице ЛК
            for (Map.Entry<String, JSONObject> oldEntry : existingBySender.entrySet()) {
                if (!threadsBySender.containsKey(oldEntry.getKey())) {
                    threadsBySender.put(oldEntry.getKey(), oldEntry.getValue());
                }
            }

            // Разворачиваем порядок сообщений в каждом диалоге: сверху старые -> снизу новые
            for (JSONObject t : threadsBySender.values()) {
                JSONArray th = t.optJSONArray("thread");
                if (th == null || th.length() <= 1) continue;
                List<JSONObject> incoming = new ArrayList<>();
                List<JSONObject> outgoing = new ArrayList<>();
                for (int i = 0; i < th.length(); i++) {
                    JSONObject b = th.optJSONObject(i);
                    if (b == null) continue;
                    if ("me".equals(b.optString("from", ""))) {
                        outgoing.add(b);
                    } else {
                        incoming.add(b);
                    }
                }
                // В таблице lk.sut.ru входящие идут сверху вниз (новые -> старые), переворачиваем
                JSONArray ordered = new JSONArray();
                for (int i = incoming.size() - 1; i >= 0; i--) {
                    ordered.put(incoming.get(i));
                }
                for (JSONObject outB : outgoing) {
                    ordered.put(outB);
                }
                t.put("thread", ordered);
            }

            storage.writeText(AppStorage.FILE_MESSAGES_SNAPSHOT, newSnap.toString());

            JSONArray resultArr = new JSONArray();
            for (JSONObject t : threadsBySender.values()) {
                resultArr.put(t);
            }
            storage.writeText(AppStorage.FILE_MESSAGES, resultArr.toString());
            storage.addLog("💬 Личные сообщения обновлены (диалогов: " + resultArr.length() + ")");

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

    private static class ParsedMessageDetail {
        String title = "";
        String text = "";
        String idinfo = "0";
        String itemnumber = "";
        JSONArray attachments = new JSONArray();
    }

    /**
     * Загружает и парсит JSON конкретного сообщения через подтверждённый эндпоинт:
     * POST https://lk.sut.ru/cabinet/project/cabinet/forms/sendto2.php?id=<ID>&prosmotr
     */
    private ParsedMessageDetail fetchMessageDetailViaSendto2(SutApiClient api, String serverId) {
        ParsedMessageDetail det = new ParsedMessageDetail();
        det.itemnumber = serverId;
        try {
            String url = SutApiClient.MESSAGE_READ_URL + "?id=" + URLEncoder.encode(serverId, "UTF-8") + "&prosmotr";
            String body = "id=" + URLEncoder.encode(serverId, "UTF-8") + "&prosmotr=1";
            String resp = api.httpRequestFast("POST", url, body);
            if (resp == null || resp.trim().length() < 2) {
                resp = api.httpRequestFast("GET", url, null);
            }
            if (resp == null || resp.trim().length() < 2 || isAuthExpiredHtml(resp)) {
                return det;
            }

            String trimmed = resp.trim();
            if (trimmed.startsWith("{")) {
                JSONObject json = new JSONObject(trimmed);
                det.itemnumber = json.optString("itemnumber", serverId);
                det.idinfo = json.optString("idinfo", "0");

                String rawTitle = json.optString("title",
                    json.optString("tema", json.optString("subject", "")));
                det.title = cleanMessageText(rawTitle);

                String annotation = json.optString("annotation", "");
                det.text = cleanMessageText(annotation);

                Object filesObj = json.opt("files");
                if (filesObj instanceof String) {
                    extractAttachmentsFromHtml((String) filesObj, SutApiClient.MESSAGE_IN_URL, det.attachments);
                } else if (filesObj instanceof JSONArray) {
                    JSONArray fArr = (JSONArray) filesObj;
                    for (int i = 0; i < fArr.length(); i++) {
                        Object item = fArr.opt(i);
                        if (item instanceof String) {
                            String fStr = ((String) item).trim();
                            if (fStr.contains("<a")) {
                                extractAttachmentsFromHtml(fStr, SutApiClient.MESSAGE_IN_URL, det.attachments);
                            } else if (!fStr.isEmpty()) {
                                String abs = fStr.startsWith("http") || fStr.startsWith("/")
                                    ? SutApiClient.resolveUrl(fStr)
                                    : (SutApiClient.ORIGIN_URL + "/cabinet/ini/subconto/sendto/101/" + det.itemnumber + "/" + fStr);
                                addUniqueAttachment(det.attachments, extractFilenameFromUrl(abs), abs);
                            }
                        } else if (item instanceof JSONObject) {
                            JSONObject fJson = (JSONObject) item;
                            String href = fJson.optString("url", fJson.optString("href", ""));
                            String name = fJson.optString("name", extractFilenameFromUrl(href));
                            if (!href.isEmpty()) {
                                addUniqueAttachment(det.attachments, name, SutApiClient.resolveUrl(href));
                            }
                        }
                    }
                }
            } else {
                det.text = cleanMessageText(trimmed);
                extractAttachmentsFromHtml(trimmed, SutApiClient.MESSAGE_IN_URL, det.attachments);
            }
        } catch (Exception ignored) {}
        return det;
    }

    private static void extractAttachmentsFromHtml(String html, String baseUrl, JSONArray target) {
        if (html == null || html.isEmpty()) return;
        Pattern linkPat = Pattern.compile("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher lm = linkPat.matcher(html);
        while (lm.find()) {
            String href = lm.group(1).trim();
            if (href.isEmpty() || href.startsWith("#") || href.toLowerCase(Locale.ROOT).startsWith("javascript:")) continue;
            String absUrl = SutApiClient.resolveUrl(baseUrl, href);
            String name = cleanHtml(lm.group(2));
            if (name.isEmpty()) name = extractFilenameFromUrl(absUrl);
            addUniqueAttachment(target, name, absUrl);
        }
    }

    private static void addUniqueAttachment(JSONArray arr, String name, String url) {
        if (url == null || url.isEmpty()) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject ex = arr.optJSONObject(i);
            if (ex != null && url.equals(ex.optString("url", ""))) return;
        }
        try {
            JSONObject obj = new JSONObject();
            obj.put("name", (name != null && !name.isEmpty()) ? name : extractFilenameFromUrl(url));
            obj.put("url", url);
            arr.put(obj);
        } catch (Exception ignored) {}
    }

    private static String cleanMessageText(String raw) {
        if (raw == null) return "";
        return raw
            .replaceAll("(?s)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?s)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</p>", "\n")
            .replaceAll("<[^>]+>", " ")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&laquo;", "«")
            .replace("&raquo;", "»")
            .replace("&amp;", "&")
            .replaceAll("[ \\t]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    /**
     * Загружает файлы группы строго с подтверждённого эндпоинта:
     * https://lk.sut.ru/cabinet/project/cabinet/forms/files_group_pr.php
     * Строки вида <tr id='tr_3909552' onclick="tr_click(3909552);"> с прямой ссылкой <a href='.../subconto/sendto/...'>.
     */
    private int pollGroupFiles(
        Context ctx,
        SutApiClient api,
        AppStorage storage,
        String login,
        String password,
        boolean manualTrigger
    ) {
        try {
            String pageUrl = SutApiClient.GROUP_FILES_URL;
            String html = fetchExactPage(api, storage, login, password, pageUrl);
            if (html.isEmpty()) {
                return storage.readJsonArray(AppStorage.FILE_GROUP_FILES).length();
            }

            String snapRaw = storage.readText(AppStorage.FILE_MESSAGES_SNAPSHOT, "");
            boolean hadBaseline = !snapRaw.isEmpty();
            JSONObject snap = hadBaseline ? new JSONObject(snapRaw) : new JSONObject();

            List<JSONObject> filesList = new ArrayList<>();
            Set<String> seenUrls = new HashSet<>();
            List<String> newFileAlerts = new ArrayList<>();

            Pattern trPat = Pattern.compile("<tr([^>]*)>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern tdPat = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern linkPat = Pattern.compile("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            Pattern datePat = Pattern.compile("(\\d{2}[-.]\\d{2}[-.]\\d{2,4}(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?)");
            Pattern sizePat = Pattern.compile("(\\d+(?:[.,]\\d+)?\\s*(?:Б|КБ|МБ|ГБ|KB|MB|GB))", Pattern.CASE_INSENSITIVE);
            Pattern fioPat = Pattern.compile("([А-ЯЁ][а-яё\\-]+\\s+[А-ЯЁ]\\.\\s*[А-ЯЁ]\\.|[А-ЯЁ][а-яё\\-]+\\s+[А-ЯЁ][а-яё]+(?:\\s+[А-ЯЁ][а-яё]+)?)");

            Matcher trMat = trPat.matcher(stripScriptsAndStyles(html));

            while (trMat.find()) {
                String trAttrs = trMat.group(1);
                String trInner = trMat.group(2);
                String rowId = extractRowServerId(trAttrs, trInner);
                int fileIdxInRow = 0;

                Matcher lm = linkPat.matcher(trInner);
                while (lm.find()) {
                    String href = lm.group(1).trim();
                    String linkText = cleanHtml(lm.group(2));
                    if (href.isEmpty() || href.startsWith("#") || href.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                        continue;
                    }

                    String lowHref = href.toLowerCase(Locale.ROOT);
                    boolean looksLikeFile = lowHref.contains("subconto/sendto")
                        || lowHref.matches(".*\\.(pdf|docx?|pptx?|xlsx?|zip|rar|7z|txt|rtf|png|jpe?g|csv|py|cpp|java|c|h|odt|djvu)(\\?.*)?$")
                        || lowHref.contains("download")
                        || lowHref.contains("file")
                        || lowHref.contains("upload");
                    if (!looksLikeFile) continue;

                    String absUrl = SutApiClient.resolveUrl(pageUrl, href);
                    if (seenUrls.contains(absUrl)) continue;
                    seenUrls.add(absUrl);

                    String fileName = !linkText.isEmpty() ? linkText : extractFilenameFromUrl(absUrl);
                    String ext = extractExtension(fileName, absUrl);

                    List<String> cells = new ArrayList<>();
                    Matcher tdMat = tdPat.matcher(trInner);
                    while (tdMat.find()) {
                        cells.add(cleanHtml(tdMat.group(1)));
                    }

                    String rowPlain = cleanHtml(trInner);
                    String dateCell = cells.size() > 2 ? cells.get(2) : rowPlain;
                    Matcher dm = datePat.matcher(dateCell);
                    boolean foundDm = dm.find();
                    if (!foundDm) {
                        dm = datePat.matcher(rowPlain);
                        foundDm = dm.find();
                    }
                    String dateStr = foundDm ? dm.group(1).replace('-', '.') : "";
                    Matcher sm = sizePat.matcher(rowPlain);
                    String sizeStr = sm.find() ? sm.group(1) : "Файл ЛК";
                    String authorCell = cells.size() > 1 ? cells.get(1).trim() : "";
                    Matcher fm = fioPat.matcher(rowPlain);
                    String author = !authorCell.isEmpty() ? authorCell : (fm.find() ? fm.group(1) : "Преподаватель");

                    String subject = (cells.size() > 3 && !cells.get(3).isEmpty()) ? cells.get(3) : "Файлы группы";
                    String comment = (cells.size() > 4) ? cells.get(4) : "";
                    if (comment.equals(fileName) || comment.equals(dateStr) || comment.equals(author)) {
                        comment = "";
                    }

                    fileIdxInRow++;
                    String fileKey = !rowId.isEmpty() ? ("file_" + rowId + "_" + fileIdxInRow) : ("file_" + Math.abs(absUrl.hashCode()));
                    JSONObject fObj = new JSONObject();
                    fObj.put("id", fileKey);
                    fObj.put("serverId", rowId);
                    fObj.put("name", fileName);
                    fObj.put("ext", ext);
                    fObj.put("subject", subject);
                    fObj.put("comment", comment);
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

            // Добавляем ранее сохранённые файлы группы, чтобы старые файлы не пропадали
            JSONArray existingFiles = storage.readJsonArray(AppStorage.FILE_GROUP_FILES);
            for (int i = 0; i < existingFiles.length(); i++) {
                JSONObject oldF = existingFiles.optJSONObject(i);
                if (oldF == null) continue;
                String oldUrl = oldF.optString("url", "");
                if (!oldUrl.isEmpty() && !seenUrls.contains(oldUrl)) {
                    seenUrls.add(oldUrl);
                    filesList.add(oldF);
                }
            }

            JSONArray outArr = new JSONArray();
            for (JSONObject f : filesList) {
                outArr.put(f);
            }
            storage.writeText(AppStorage.FILE_GROUP_FILES, outArr.toString());
            storage.addLog("📎 Файлы группы обновлены (файлов: " + outArr.length() + ")");

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
        return sendReply(api, storage, login, password, threadId, replyText, null, null);
    }

    public synchronized boolean sendReply(
        SutApiClient api,
        AppStorage storage,
        String login,
        String password,
        String threadId,
        String replyText,
        String filePath,
        String fileName
    ) {
        String cleanText = replyText != null ? replyText.trim() : "";
        File attachFile = (filePath != null && !filePath.isEmpty()) ? new File(filePath) : null;
        boolean hasFile = attachFile != null && attachFile.exists() && attachFile.length() > 0;

        if (cleanText.isEmpty() && !hasFile) return false;
        if (hasFile && attachFile.length() > 5L * 1024L * 1024L) {
            storage.addLog("Ошибка: файл больше 5 МБ");
            return false;
        }

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

            String item = targetThread.optString("itemnumber", targetThread.optString("serverId", ""));
            String idinfo = targetThread.optString("idinfo", "0");
            boolean sentToServer = false;

            if (!item.isEmpty() && (api.isAuthenticated() || api.authenticate(login, password, storage))) {
                try {
                    if (hasFile) {
                        Map<String, String> fields = new LinkedHashMap<>();
                        fields.put("id", idinfo);
                        fields.put("item", item);
                        fields.put("upload", "Загрузить");
                        api.httpPostMultipart(SutApiClient.MESSAGE_READ_URL, fields, "userfile", attachFile, fileName);
                    }

                    String payload = "item=" + URLEncoder.encode(item, "UTF-8")
                        + "&idinfo=" + URLEncoder.encode(idinfo, "UTF-8")
                        + "&mes_otvet=" + URLEncoder.encode(cleanText, "UTF-8")
                        + "&saveotv=1";
                    String resp = api.httpRequest("POST", SutApiClient.MESSAGE_SEND_URL, payload);
                    sentToServer = resp != null && !resp.toLowerCase(Locale.ROOT).contains("ошибка");
                } catch (Exception ignored) {}
            }

            JSONObject bubble = new JSONObject();
            bubble.put("from", "me");
            String displayText = cleanText;
            if (hasFile) {
                String safeName = (fileName != null && !fileName.isEmpty()) ? fileName : attachFile.getName();
                displayText = displayText.isEmpty() ? ("📎 " + safeName) : (displayText + "\n📎 " + safeName);
            }
            bubble.put("text", displayText);
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
        } finally {
            if (attachFile != null && attachFile.exists()) {
                try { attachFile.delete(); } catch (Exception ignored) {}
            }
        }
    }
    public synchronized int pollMessageTeachers(
        SutApiClient api,
        AppStorage storage,
        String login,
        String password
    ) {
        if (!api.isAuthenticated() && !api.authenticate(login, password, storage)) {
            return storage.getMessageTeachers().length();
        }
        try {
            String html = fetchExactPage(api, storage, login, password, SutApiClient.MESSAGE_CREATE_URL);
            if (html.isEmpty()) return storage.getMessageTeachers().length();

            Matcher sm = SELECT_ADRESAT_PAT.matcher(html);
            if (!sm.find()) return storage.getMessageTeachers().length();

            JSONArray arr = new JSONArray();
            Matcher om = OPTION_PAT.matcher(sm.group(1));
            while (om.find()) {
                String id = om.group(1).trim();
                String name = cleanHtml(om.group(2));
                if ("0".equals(id) || id.isEmpty() || name.isEmpty() || "...".equals(name)) continue;
                JSONObject obj = new JSONObject();
                obj.put("id", id);
                obj.put("name", name);
                arr.put(obj);
            }
            storage.saveMessageTeachers(arr);
            storage.addLog("👤 Список преподавателей для сообщений обновлён (" + arr.length() + ")");
            return arr.length();
        } catch (Exception e) {
            storage.addLog("Ошибка загрузки списка преподавателей: " + e.getMessage());
            return 0;
        }
    }

    public synchronized boolean sendNewMessage(
        SutApiClient api,
        AppStorage storage,
        String login,
        String password,
        String adresatId,
        String adresatName,
        String title,
        String text,
        String filePath,
        String fileName
    ) {
        String cleanAdresat = adresatId != null ? adresatId.trim() : "";
        String cleanTitle = title != null ? title.trim() : "";
        String cleanText = text != null ? text.trim() : "";
        File attachFile = (filePath != null && !filePath.isEmpty()) ? new File(filePath) : null;
        boolean hasFile = attachFile != null && attachFile.exists() && attachFile.length() > 0;

        if (cleanAdresat.isEmpty() || "0".equals(cleanAdresat) || cleanTitle.isEmpty()) {
            storage.addLog("Ошибка отправки: выберите преподавателя и укажите тему.");
            return false;
        }
        if (hasFile && attachFile.length() > 5L * 1024L * 1024L) {
            storage.addLog("Ошибка: файл больше 5 МБ");
            return false;
        }

        try {
            if (!api.isAuthenticated() && !api.authenticate(login, password, storage)) {
                storage.addLog("Ошибка входа в ЛК при отправке нового сообщения.");
                return false;
            }

            String idinfo = "0";
            if (hasFile) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("id", "0");
                fields.put("item", "0");
                fields.put("upload", "Загрузить");
                String upResp = api.httpPostMultipart(
                    SutApiClient.MESSAGE_CREATE_URL, fields, "userfile", attachFile, fileName
                );
                if (upResp != null) {
                    Matcher im = IDINFO_JSON_PAT.matcher(upResp);
                    if (im.find()) {
                        idinfo = im.group(1);
                    }
                }
            }

            String payload = "idinfo=" + URLEncoder.encode(idinfo, "UTF-8")
                + "&item=0"
                + "&title=" + URLEncoder.encode(cleanTitle, "UTF-8")
                + "&mes_otvet=" + URLEncoder.encode(cleanText, "UTF-8")
                + "&adresat=" + URLEncoder.encode(cleanAdresat, "UTF-8")
                + "&saveotv=";

            String resp = api.httpRequest("POST", SutApiClient.MESSAGE_SEND_URL, payload);
            boolean ok = resp != null && !resp.toLowerCase(Locale.ROOT).contains("ошибка");
            String who = (adresatName != null && !adresatName.trim().isEmpty()) ? adresatName.trim() : ("ID " + cleanAdresat);
            storage.addLog(ok
                ? ("📤 Новое сообщение отправлено: " + who + " («" + cleanTitle + "»)")
                : ("⚠️ Не удалось подтвердить отправку сообщения для " + who));
            return ok;
        } catch (Exception e) {
            storage.addLog("Ошибка отправки нового сообщения: " + e.getMessage());
            return false;
        } finally {
            if (attachFile != null && attachFile.exists()) {
                try { attachFile.delete(); } catch (Exception ignored) {}
            }
        }
    }
    private static String stripScriptsAndStyles(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw
            .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?s)<!--.*?-->", " ");
    }

    private static boolean isGarbageMessageToken(String text) {
        if (text == null) return true;
        String s = text.trim();
        if (s.isEmpty()) return true;
        String low = s.toLowerCase(Locale.ROOT);
        if (low.contains("mem.") || low.contains("history[") || s.contains("'+") || s.contains("+'")
            || low.contains("javascript:") || low.contains("function(")) {
            return true;
        }
        return low.equals("сообщение")
            || low.equals("сообщения")
            || low.equals("сообщение из лк")
            || low.equals("личное сообщение")
            || low.equals("входящие")
            || low.equals("исходящие")
            || low.equals("дата")
            || low.equals("тема")
            || low.equals("отправитель")
            || low.equals("получатель")
            || low.equals("файлы")
            || low.equals("удалить")
            || low.equals("ответить")
            || low.equals("просмотр")
            || low.equals("действие")
            || low.equals("действия");
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