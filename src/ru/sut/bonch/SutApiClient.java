package ru.sut.bonch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

public class SutApiClient {
    public static final String ORIGIN_URL = "https://lk.sut.ru";
    public static final String BASE_URL = ORIGIN_URL + "/cabinet";
    public static final String LOGIN_PAGE_URL = BASE_URL + "/?login=yes";
    public static final String AUTH_URL = BASE_URL + "/lib/autentificationok.php";
    public static final String INI_URL = BASE_URL + "/ini.php";
    public static final String SCHEDULE_URL = BASE_URL + "/project/cabinet/forms/raspisanie.php";

    // Подтверждённые реальные URL разделов ЛК СПбГУТ
    public static final String MESSAGE_IN_URL = BASE_URL + "/project/cabinet/forms/message.php?type=in";
    public static final String MESSAGE_READ_URL = BASE_URL + "/project/cabinet/forms/sendto2.php";
    public static final String MESSAGE_SEND_URL = BASE_URL + "/project/cabinet/forms/message.php";
    public static final String MESSAGE_CREATE_URL = BASE_URL + "/project/cabinet/forms/message_create_stud.php";
    public static final String GROUP_FILES_URL = BASE_URL + "/project/cabinet/forms/files_group_pr.php";
    public static final String DIARY_URL = BASE_URL + "/project/cabinet/forms/jurnal_dnevnik.php";

    public static final String[] DIARY_URLS = {
        DIARY_URL,
        BASE_URL + "/project/cabinet/forms/dnevnik.php",
        BASE_URL + "/project/cabinet/forms/jurnal.php"
    };

    public static final String[] MESSAGE_URLS = {
        MESSAGE_IN_URL,
        BASE_URL + "/project/cabinet/forms/message.php"
    };

    public static final String[] GROUP_FILES_URLS = {
        GROUP_FILES_URL,
        BASE_URL + "/project/cabinet/forms/files_group.php"
    };

    public static final String[] PROFILE_URLS = {
        BASE_URL + "/project/cabinet/forms/profile.php",
        BASE_URL + "/project/cabinet/forms/anketa.php",
        BASE_URL + "/project/cabinet/forms/lichnye_dannye.php"
    };

    private static final String USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 8000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 15000;
    private static final int FAST_CONNECT_TIMEOUT_MS = 5000;
    private static final int FAST_READ_TIMEOUT_MS = 9000;
    private static final int MAX_REDIRECTS = 5;

    private final Map<String, String> cookieJar = new LinkedHashMap<>();
    private volatile boolean authenticated = false;
    private volatile boolean lastAuthBadCredentials = false;
    private volatile String lastAuthError = "";
    private boolean cookiesRestored = false;
    private String lastLogin = "";
    private String lastPass = "";

    public SutApiClient() {
        resetCookies();
    }

    public synchronized void resetCookies() {
        cookieJar.clear();
        cookiesRestored = false;
    }

    public synchronized boolean hasCookies() {
        return !cookieJar.isEmpty();
    }

    public boolean wasLastAuthBadCredentials() {
        return lastAuthBadCredentials;
    }

    public String getLastAuthError() {
        return lastAuthError;
    }

    public synchronized void restoreCookiesFromStorage(AppStorage storage) {
        if (storage == null) return;
        try {
            JSONArray arr = storage.readJsonArray(AppStorage.FILE_COOKIES);
            cookieJar.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String name = obj.optString("name", "").trim();
                String value = obj.optString("value", "").trim();
                if (!name.isEmpty() && !"deleted".equalsIgnoreCase(value)) {
                    cookieJar.put(name, value);
                }
            }
            cookiesRestored = true;
        } catch (Exception ignored) {}
    }

    public synchronized void persistCookiesToStorage(AppStorage storage) {
        if (storage == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, String> entry : cookieJar.entrySet()) {
                JSONObject obj = new JSONObject();
                obj.put("name", entry.getKey());
                obj.put("value", entry.getValue());
                obj.put("domain", "lk.sut.ru");
                obj.put("path", "/");
                arr.put(obj);
            }
            storage.writeText(AppStorage.FILE_COOKIES, arr.toString());
        } catch (Exception ignored) {}
    }

    public synchronized String getCookieHeaderForUrl(String urlStr) {
        if (cookieJar.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookieJar.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private synchronized void captureResponseCookies(HttpURLConnection conn) {
        try {
            Map<String, List<String>> headers = conn.getHeaderFields();
            if (headers == null) return;
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                String key = entry.getKey();
                if (key == null || !key.equalsIgnoreCase("Set-Cookie")) continue;
                List<String> values = entry.getValue();
                if (values == null) continue;
                for (String headerVal : values) {
                    if (headerVal == null || headerVal.isEmpty()) continue;
                    int semi = headerVal.indexOf(';');
                    String pair = semi >= 0 ? headerVal.substring(0, semi) : headerVal;
                    int eq = pair.indexOf('=');
                    if (eq <= 0) continue;
                    String name = pair.substring(0, eq).trim();
                    String val = pair.substring(eq + 1).trim();
                    if (name.isEmpty()) continue;
                    if ("deleted".equalsIgnoreCase(val) || val.isEmpty()) {
                        cookieJar.remove(name);
                    } else {
                        cookieJar.put(name, val);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void invalidateSession() {
        authenticated = false;
        resetCookies();
    }

    public void checkCredentialsChanged(String login, String pass) {
        if (!login.equals(lastLogin) || !pass.equals(lastPass)) {
            authenticated = false;
            lastAuthBadCredentials = false;
            lastAuthError = "";
            lastLogin = login;
            lastPass = pass;
            resetCookies();
        }
    }

    public static String resolveUrl(String rawUrl) {
        return resolveUrl(BASE_URL + "/", rawUrl);
    }

    /**
     * Корректно резолвит относительные ссылки (включая ../../../subconto/sendto/...) относительно базового URL страницы.
     */
    public static String resolveUrl(String pageBaseUrl, String rawUrl) {
        if (rawUrl == null) return "";
        String u = rawUrl.trim().replace("&amp;", "&");
        if (u.isEmpty()) return "";
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        if (u.startsWith("//")) return "https:" + u;
        try {
            String base = (pageBaseUrl != null && !pageBaseUrl.isEmpty()) ? pageBaseUrl : (BASE_URL + "/");
            return new URL(new URL(base), u).toString();
        } catch (Exception e) {
            if (u.startsWith("/")) return ORIGIN_URL + u;
            if (u.startsWith("project/") || u.startsWith("lib/") || u.startsWith("subconto/")) {
                return BASE_URL + "/" + u;
            }
            return BASE_URL + "/" + u;
        }
    }

    public String httpRequest(String method, String urlStr, String postBody) throws Exception {
        return httpRequestInternal(method, urlStr, postBody, 2, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public String httpRequestFast(String method, String urlStr, String postBody) throws Exception {
        return httpRequestInternal(method, urlStr, postBody, 1, FAST_CONNECT_TIMEOUT_MS, FAST_READ_TIMEOUT_MS);
    }

    private String httpRequestInternal(
        String method,
        String urlStr,
        String postBody,
        int maxAttempts,
        int connectTimeoutMs,
        int readTimeoutMs
    ) throws Exception {
        Exception lastErr = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeSingleRequest(method, urlStr, postBody, connectTimeoutMs, readTimeoutMs);
            } catch (SocketTimeoutException te) {
                lastErr = te;
                if (attempt < maxAttempts) {
                    try { Thread.sleep(600L * attempt); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) {
                lastErr = e;
                if (attempt < maxAttempts) {
                    try { Thread.sleep(500L); } catch (InterruptedException ignored) {}
                } else {
                    break;
                }
            }
        }
        throw lastErr != null ? lastErr : new Exception("Ошибка соединения с lk.sut.ru");
    }

    private int countValidCyrillic(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'А' && c <= 'я') || c == 'Ё' || c == 'ё') {
                count++;
            }
        }
        return count;
    }

    private String decodeBytes(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) return "";
        String ctLow = contentType != null ? contentType.toLowerCase(Locale.ROOT) : "";

        if (ctLow.contains("windows-1251") || ctLow.contains("cp1251")) {
            return new String(bytes, Charset.forName("windows-1251"));
        }

        String utf8 = new String(bytes, Charset.forName("UTF-8"));
        String cleanUtf8 = utf8.replace("\uFEFF", "").trim();
        if ("1".equals(cleanUtf8)) return cleanUtf8;

        if (ctLow.contains("utf-8") && utf8.indexOf('\uFFFD') == -1) {
            return utf8;
        }

        String cp1251 = new String(bytes, Charset.forName("windows-1251"));
        int cyrUtf8 = countValidCyrillic(utf8);
        int cyrCp1251 = countValidCyrillic(cp1251);

        if (utf8.indexOf('\uFFFD') != -1 && cyrCp1251 > cyrUtf8) {
            return cp1251;
        }
        if (cyrCp1251 > cyrUtf8 * 2 && cyrCp1251 > 10) {
            return cp1251;
        }
        return utf8;
    }

    private String executeSingleRequest(
        String method,
        String urlStr,
        String postBody,
        int connectTimeoutMs,
        int readTimeoutMs
    ) throws Exception {
        String currentUrl = resolveUrl(urlStr);
        String currentMethod = method;
        String currentBody = postBody;

        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(currentMethod);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("Referer", LOGIN_PAGE_URL);
            conn.setRequestProperty("Origin", ORIGIN_URL);

            if ("POST".equalsIgnoreCase(currentMethod)) {
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            }

            String cookieHeader = getCookieHeaderForUrl(currentUrl);
            if (!cookieHeader.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }

            if ("POST".equalsIgnoreCase(currentMethod)) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                byte[] out = (currentBody != null ? currentBody : "").getBytes("UTF-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }
            }

            int code = conn.getResponseCode();
            captureResponseCookies(conn);

            if (code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307
                || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    return "";
                }
                currentUrl = resolveUrl(currentUrl, location);
                if (code == HttpURLConnection.HTTP_SEE_OTHER || code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM) {
                    currentMethod = "GET";
                    currentBody = null;
                }
                continue;
            }

            String contentType = conn.getContentType();
            String contentEncoding = conn.getContentEncoding();
            InputStream rawStream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (rawStream == null) {
                conn.disconnect();
                return "";
            }

            InputStream is = (contentEncoding != null && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip"))
                ? new GZIPInputStream(rawStream)
                : rawStream;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
            } finally {
                try { is.close(); } catch (Exception ignored) {}
                conn.disconnect();
            }

            if (code == 404 || code >= 500) {
                return "";
            }

            return decodeBytes(baos.toByteArray(), contentType);
        }
        return "";
    }

    private boolean isLoginFormHtml(String html) {
        if (html == null || html.trim().isEmpty()) return true;
        String low = html.toLowerCase(Locale.ROOT);
        return low.contains("name=\"parole\"")
            || low.contains("name='parole'")
            || low.contains("autentificationok.php");
    }

    private boolean checkSessionAlive() {
        if (!hasCookies()) return false;
        try {
            String html = executeSingleRequest("POST", SCHEDULE_URL, "", FAST_CONNECT_TIMEOUT_MS, FAST_READ_TIMEOUT_MS);
            if (html == null || html.trim().length() < 60) return false;
            if (isLoginFormHtml(html)) return false;
            String low = html.toLowerCase(Locale.ROOT);
            return low.contains("<table") || low.contains("расписани");
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized boolean authenticate(String login, String password, AppStorage storage) {
        lastAuthBadCredentials = false;
        lastAuthError = "";

        if (login == null || login.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            authenticated = false;
            lastAuthBadCredentials = true;
            lastAuthError = "Введите логин и пароль от lk.sut.ru";
            return false;
        }

        try {
            if (storage != null && storage.isAntiKickEnabled()
                && login.equals(lastLogin) && password.equals(lastPass)) {
                if (!cookiesRestored) {
                    restoreCookiesFromStorage(storage);
                }
                if (hasCookies()) {
                    if (checkSessionAlive()) {
                        authenticated = true;
                        lastLogin = login;
                        lastPass = password;
                        storage.addLog("Сессия восстановлена без повторного входа (Анти-кик)");
                        if (storage.getProfile().optString("name", "").isEmpty()) {
                            try {
                                String cabHtml = httpRequestFast("GET", LOGIN_PAGE_URL, null);
                                if (!isLoginFormHtml(cabHtml)) {
                                    parseMenuAndProfile(cabHtml, login, storage);
                                }
                            } catch (Exception ignored) {}
                        }
                        return true;
                    } else {
                        storage.addLog("Сохранённая сессия lk.sut.ru истекла или сброшена — выполняем повторный вход...");
                    }
                }
            }

            resetCookies();

            String initPage = httpRequest("GET", LOGIN_PAGE_URL, null);
            if (initPage == null || initPage.trim().length() < 20) {
                authenticated = false;
                lastAuthError = "Сервер lk.sut.ru не отвечает. Проверьте интернет.";
                if (storage != null) storage.addLog(lastAuthError);
                return false;
            }

            String data = "users=" + URLEncoder.encode(login, "UTF-8")
                        + "&parole=" + URLEncoder.encode(password, "UTF-8");
            String resp = httpRequest("POST", AUTH_URL, data);
            String trimmed = resp != null ? resp.replace("\uFEFF", "").trim() : "";

            if (trimmed.isEmpty()) {
                resp = httpRequest("POST", AUTH_URL + "?" + data, data);
                trimmed = resp != null ? resp.replace("\uFEFF", "").trim() : "";
            }

            boolean authOk = "1".equals(trimmed)
                || trimmed.startsWith("1\n")
                || trimmed.startsWith("1\r")
                || trimmed.startsWith("1 ");

            if (!authOk) {
                authenticated = false;
                lastAuthBadCredentials = true;
                lastAuthError = "Неверный логин или пароль от lk.sut.ru";
                if (storage != null) storage.addLog(lastAuthError);
                return false;
            }

            httpRequest("GET", INI_URL, null);

            String cabHtml = httpRequest("GET", LOGIN_PAGE_URL, null);
            boolean confirmed = !isLoginFormHtml(cabHtml) && cabHtml.length() > 200;
            if (!confirmed) {
                confirmed = checkSessionAlive();
            }

            if (!confirmed) {
                authenticated = false;
                lastAuthBadCredentials = true;
                lastAuthError = "ЛК не подтвердил вход. Проверьте логин и пароль.";
                if (storage != null) storage.addLog(lastAuthError);
                return false;
            }

            authenticated = true;
            lastLogin = login;
            lastPass = password;
            persistCookiesToStorage(storage);
            parseMenuAndProfile(cabHtml, login, storage);
            if (storage != null) {
                storage.addLog("Вход в ЛК подтверждён (" + login + ")");
            }
            return true;
        } catch (UnknownHostException uhe) {
            invalidateSession();
            lastAuthError = "Нет подключения к интернету (lk.sut.ru недоступен)";
            if (storage != null) storage.addLog(lastAuthError);
            return false;
        } catch (SocketTimeoutException ste) {
            invalidateSession();
            lastAuthError = "Сервер lk.sut.ru слишком долго отвечает. Попробуйте снова.";
            if (storage != null) storage.addLog(lastAuthError);
            return false;
        } catch (Exception e) {
            invalidateSession();
            lastAuthError = "Ошибка подключения к lk.sut.ru: " + e.getMessage();
            if (storage != null) storage.addLog(lastAuthError);
            return false;
        }
    }

    private void parseMenuAndProfile(String cabHtml, String login, AppStorage storage) {
        if (cabHtml == null || cabHtml.isEmpty() || storage == null) return;
        try {
            Pattern formUrlPat = Pattern.compile(
                "(project/cabinet/forms/[a-zA-Z0-9_\\-]+\\.php(?:\\?[^'\"\\s<>)]*)?)",
                Pattern.CASE_INSENSITIVE
            );
            Matcher fm = formUrlPat.matcher(cabHtml);
            Set<String> foundForms = new LinkedHashSet<>();
            while (fm.find()) {
                String rel = fm.group(1);
                foundForms.add(rel);
                String low = rel.toLowerCase(Locale.ROOT);
                String abs = resolveUrl(rel);
                if (low.contains("message.php")) {
                    storage.saveDiscoveredEndpoint("messages", MESSAGE_IN_URL);
                } else if (low.contains("files_group_pr.php")) {
                    storage.saveDiscoveredEndpoint("groupFiles", GROUP_FILES_URL);
                } else if (low.contains("dnevnik") || low.contains("jurnal")) {
                    storage.saveDiscoveredEndpoint("diary", abs);
                } else if (low.contains("profile") || low.contains("anketa")) {
                    storage.saveDiscoveredEndpoint("profile", abs);
                }
            }
            if (!foundForms.isEmpty()) {
                storage.addLog("Найдены разделы ЛК: " + foundForms.toString());
            }

            String plain = cabHtml
                .replaceAll("(?s)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?s)<style[^>]*>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();

            String foundName = "";
            Pattern fioPat = Pattern.compile("([А-ЯЁ][а-яё\\-]{2,}\\s+[А-ЯЁ][а-яё]{2,}\\s+[А-ЯЁ][а-яё]{2,}(?:вич|вна|ична|ович|евич|овна|евна))");
            Matcher nm = fioPat.matcher(plain);
            if (nm.find()) {
                String[] parts = nm.group(1).trim().split("\\s+");
                if (parts.length >= 2) {
                    foundName = parts[1] + " " + parts[0];
                } else {
                    foundName = nm.group(1).trim();
                }
            }

            String foundGroup = "";
            Pattern grpPat = Pattern.compile("\\b([А-ЯЁ]{2,5}-\\d{2,3}[а-яёА-ЯЁ]?)\\b");
            Matcher gm = grpPat.matcher(plain);
            if (gm.find()) {
                foundGroup = gm.group(1).trim();
            }

            storage.saveProfile(true, foundName, foundGroup, login);
        } catch (Exception ignored) {}
    }

    public boolean sendAndVerifyOpenZan(String raspId, String weekId) {
        try {
            String payload = "open=1&rasp=" + raspId + "&week=" + weekId;
            httpRequest("POST", SCHEDULE_URL + "?" + payload, payload);
            String verifyHtml = httpRequest("POST", SCHEDULE_URL, "").toLowerCase(Locale.ROOT);
            return verifyHtml.contains("присутств")
                || verifyHtml.contains("отмечен")
                || verifyHtml.contains("занятие началось")
                || !verifyHtml.contains("open_zan(" + raspId);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean downloadToFile(String fileUrl, File destFile) {
        try {
            URL url = new URL(resolveUrl(fileUrl));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Referer", LOGIN_PAGE_URL);
            String cookieHeader = getCookieHeaderForUrl(fileUrl);
            if (!cookieHeader.isEmpty()) {
                conn.setRequestProperty("Cookie", cookieHeader);
            }

            int code = conn.getResponseCode();
            if (code >= 400) {
                conn.disconnect();
                return false;
            }
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(destFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
            }
            conn.disconnect();
            return destFile.exists() && destFile.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public String httpPostMultipart(
        String urlStr,
        Map<String, String> fields,
        String fileFieldName,
        File file,
        String fileName
    ) throws Exception {
        String boundary = "----PulseBoundary" + System.currentTimeMillis();
        String lineEnd = "\r\n";
        String twoHyphens = "--";

        URL url = new URL(resolveUrl(urlStr));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Referer", MESSAGE_IN_URL);
        conn.setRequestProperty("Origin", ORIGIN_URL);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        String cookieHeader = getCookieHeaderForUrl(urlStr);
        if (!cookieHeader.isEmpty()) {
            conn.setRequestProperty("Cookie", cookieHeader);
        }

        try (OutputStream os = conn.getOutputStream()) {
            if (fields != null) {
                for (Map.Entry<String, String> entry : fields.entrySet()) {
                    os.write((twoHyphens + boundary + lineEnd).getBytes("UTF-8"));
                    os.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"" + lineEnd + lineEnd).getBytes("UTF-8"));
                    os.write((entry.getValue() != null ? entry.getValue() : "").getBytes("UTF-8"));
                    os.write(lineEnd.getBytes("UTF-8"));
                }
            }

            if (file != null && file.exists()) {
                String safeName = (fileName != null && !fileName.trim().isEmpty()) ? fileName.trim() : file.getName();
                os.write((twoHyphens + boundary + lineEnd).getBytes("UTF-8"));
                os.write(("Content-Disposition: form-data; name=\"" + fileFieldName + "\"; filename=\"" + safeName + "\"" + lineEnd).getBytes("UTF-8"));
                os.write(("Content-Type: application/octet-stream" + lineEnd + lineEnd).getBytes("UTF-8"));

                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                }
                os.write(lineEnd.getBytes("UTF-8"));
            }

            os.write((twoHyphens + boundary + twoHyphens + lineEnd).getBytes("UTF-8"));
            os.flush();
        }

        int code = conn.getResponseCode();
        captureResponseCookies(conn);

        String contentType = conn.getContentType();
        String contentEncoding = conn.getContentEncoding();
        InputStream rawStream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (rawStream == null) {
            conn.disconnect();
            return "";
        }

        InputStream is = (contentEncoding != null && contentEncoding.toLowerCase(Locale.ROOT).contains("gzip"))
            ? new GZIPInputStream(rawStream)
            : rawStream;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
        } finally {
            try { is.close(); } catch (Exception ignored) {}
            conn.disconnect();
        }

        return decodeBytes(baos.toByteArray(), contentType);
    }
}
