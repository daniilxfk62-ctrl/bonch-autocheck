package ru.sut.bonch;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public class SutApiClient {
    public static final String ORIGIN_URL = "https://lk.sut.ru";
    public static final String BASE_URL = ORIGIN_URL + "/cabinet";
    public static final String LOGIN_PAGE_URL = BASE_URL + "/?login=yes";
    public static final String AUTH_URL = BASE_URL + "/lib/autentificationok.php";
    public static final String INI_URL = BASE_URL + "/ini.php";
    public static final String SCHEDULE_URL = BASE_URL + "/project/cabinet/forms/raspisanie.php";

    public static final String[] DIARY_URLS = {
        BASE_URL + "/project/cabinet/forms/dnevnik.php",
        BASE_URL + "/project/cabinet/forms/jurnal.php",
        BASE_URL + "/project/cabinet/forms/stud_jurnal.php",
        BASE_URL + "/project/cabinet/forms/usp.php",
        BASE_URL + "/project/cabinet/forms/zach.php"
    };

    public static final String[] MESSAGE_URLS = {
        BASE_URL + "/project/cabinet/forms/message.php",
        BASE_URL + "/project/cabinet/forms/messages.php",
        BASE_URL + "/project/cabinet/forms/soobscheniya.php"
    };

    public static final String[] GROUP_FILES_URLS = {
        BASE_URL + "/project/cabinet/forms/files_group_pr.php",
        BASE_URL + "/project/cabinet/forms/files_group.php",
        BASE_URL + "/project/cabinet/forms/fayly_gruppy.php"
    };

    public static final String[] PROFILE_URLS = {
        BASE_URL + "/project/cabinet/forms/profile.php",
        BASE_URL + "/project/cabinet/forms/anketa.php",
        BASE_URL + "/project/cabinet/forms/lichnye_dannye.php"
    };

    private CookieManager cookieManager;
    private volatile boolean authenticated = false;
    private boolean cookiesRestored = false;
    private String lastLogin = "";
    private String lastPass = "";

    public SutApiClient() {
        resetCookies();
    }

    public synchronized void resetCookies() {
        try {
            if (cookieManager != null) {
                CookieStore store = cookieManager.getCookieStore();
                if (store != null) store.removeAll();
            }
            cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            CookieHandler.setDefault(cookieManager);
            cookiesRestored = false;
        } catch (Exception ignored) {}
    }

    public synchronized void restoreCookiesFromStorage(AppStorage storage) {
        if (storage == null) return;
        try {
            JSONArray arr = storage.readJsonArray(AppStorage.FILE_COOKIES);
            if (arr.length() == 0) return;
            CookieStore store = cookieManager.getCookieStore();
            URI baseUri = new URI(ORIGIN_URL);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String name = obj.optString("name", "");
                String value = obj.optString("value", "");
                if (name.isEmpty()) continue;
                HttpCookie cookie = new HttpCookie(name, value);
                cookie.setDomain(obj.optString("domain", "lk.sut.ru"));
                cookie.setPath(obj.optString("path", "/"));
                cookie.setVersion(0);
                store.add(baseUri, cookie);
            }
            cookiesRestored = true;
        } catch (Exception ignored) {}
    }

    public synchronized void persistCookiesToStorage(AppStorage storage) {
        if (storage == null || cookieManager == null) return;
        try {
            CookieStore store = cookieManager.getCookieStore();
            List<HttpCookie> cookies = store.getCookies();
            JSONArray arr = new JSONArray();
            for (HttpCookie c : cookies) {
                if (c.hasExpired()) continue;
                JSONObject obj = new JSONObject();
                obj.put("name", c.getName());
                obj.put("value", c.getValue());
                obj.put("domain", c.getDomain() != null ? c.getDomain() : "lk.sut.ru");
                obj.put("path", c.getPath() != null ? c.getPath() : "/");
                arr.put(obj);
            }
            storage.writeText(AppStorage.FILE_COOKIES, arr.toString());
        } catch (Exception ignored) {}
    }

    public synchronized String getCookieHeaderForUrl(String urlStr) {
        if (cookieManager == null) return "";
        try {
            CookieStore store = cookieManager.getCookieStore();
            List<HttpCookie> cookies = store.getCookies();
            StringBuilder sb = new StringBuilder();
            for (HttpCookie c : cookies) {
                if (c.hasExpired()) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(c.getName()).append("=").append(c.getValue());
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
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
            lastLogin = login;
            lastPass = pass;
        }
    }

    public static String resolveUrl(String rawUrl) {
        if (rawUrl == null) return "";
        String u = rawUrl.trim().replace("&amp;", "&");
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("/")) return ORIGIN_URL + u;
        if (u.startsWith("project/") || u.startsWith("lib/")) return BASE_URL + "/" + u;
        return BASE_URL + "/" + u;
    }

    public String httpRequest(String method, String urlStr, String postBody) throws Exception {
        Exception lastErr = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return executeSingleRequest(method, urlStr, postBody);
            } catch (SocketTimeoutException te) {
                lastErr = te;
                try { Thread.sleep(1500L * attempt); } catch (InterruptedException ignored) {}
            } catch (Exception e) {
                lastErr = e;
                if (attempt < 2) {
                    try { Thread.sleep(1200L); } catch (InterruptedException ignored) {}
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

    private String executeSingleRequest(String method, String urlStr, String postBody) throws Exception {
        URL url = new URL(resolveUrl(urlStr));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(25000);
        conn.setReadTimeout(40000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) Chrome/128.0.0.0 Mobile Safari/537.36");
        conn.setRequestProperty("Referer", LOGIN_PAGE_URL);
        conn.setRequestProperty("Origin", ORIGIN_URL);
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");

        if ("POST".equals(method)) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] out = (postBody != null ? postBody : "").getBytes("UTF-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(out);
            }
        }

        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is == null) {
            conn.disconnect();
            return "";
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        conn.disconnect();

        byte[] bytes = baos.toByteArray();
        String utf8 = new String(bytes, Charset.forName("UTF-8"));
        if (utf8.trim().equals("1")) return utf8;

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

    /**
     * Проверяет, жива ли текущая cookie-сессия без повторного входа (для режима «Анти-кик»).
     */
    private boolean checkSessionAlive() {
        try {
            String html = executeSingleRequest("POST", SCHEDULE_URL, "");
            if (html == null || html.trim().length() < 60) return false;
            String low = html.toLowerCase(Locale.ROOT);
            if (low.contains("autentification") || low.contains("name=\"parole\"")) return false;
            return low.contains("<table") || low.contains("расписани");
        } catch (Exception e) {
            return false;
        }
    }

    public synchronized boolean authenticate(String login, String password, AppStorage storage) {
        try {
            // Защита сессии (Анти-кик): сначала пробуем переиспользовать сохранённые cookies
            if (storage != null && storage.isAntiKickEnabled()) {
                if (!cookiesRestored) {
                    restoreCookiesFromStorage(storage);
                }
                if (checkSessionAlive()) {
                    authenticated = true;
                    lastLogin = login;
                    lastPass = password;
                    storage.addLog("Сессия восстановлена без повторного входа (Анти-кик)");
                    if (storage.getProfile().optString("name", "").isEmpty()) {
                        try {
                            String cabHtml = httpRequest("GET", LOGIN_PAGE_URL, null);
                            parseMenuAndProfile(cabHtml, login, storage);
                        } catch (Exception ignored) {}
                    }
                    return true;
                }
            }

            resetCookies();
            httpRequest("GET", LOGIN_PAGE_URL, null);
            String data = "users=" + URLEncoder.encode(login, "UTF-8")
                        + "&parole=" + URLEncoder.encode(password, "UTF-8");
            String resp = httpRequest("POST", AUTH_URL + "?" + data, data);
            String trimmed = resp != null ? resp.trim() : "";

            boolean authOk = "1".equals(trimmed)
                || trimmed.startsWith("1\n")
                || trimmed.startsWith("1\r")
                || trimmed.startsWith("1 ");

            if (authOk) {
                httpRequest("GET", INI_URL, null);
                String cabHtml = httpRequest("GET", LOGIN_PAGE_URL, null);
                authenticated = true;
                lastLogin = login;
                lastPass = password;
                persistCookiesToStorage(storage);
                parseMenuAndProfile(cabHtml, login, storage);
                storage.addLog("Вход в ЛК выполнен (" + login + ")");
                return true;
            }
            authenticated = false;
            storage.addLog("Неверный логин или пароль от lk.sut.ru");
            return false;
        } catch (Exception e) {
            invalidateSession();
            storage.addLog("Сервер ЛК долго отвечает (" + e.getMessage() + "), повтор...");
            return false;
        }
    }

    /**
     * Извлекает ФИО, учебную группу и реальные URL разделов (Сообщения, Файлы группы, Дневник)
     * из HTML главной страницы ЛК после авторизации.
     */
    private void parseMenuAndProfile(String cabHtml, String login, AppStorage storage) {
        if (cabHtml == null || cabHtml.isEmpty() || storage == null) return;
        try {
            // 1. Автообнаружение URL форм из меню ЛК
            Pattern formUrlPat = Pattern.compile(
                "(project/cabinet/forms/[a-zA-Z0-9_\\-]+\\.php(?:\\?[^'\"\\s<>)]*)?)",
                Pattern.CASE_INSENSITIVE
            );
            Matcher fm = formUrlPat.matcher(cabHtml);
            while (fm.find()) {
                String rel = fm.group(1);
                String low = rel.toLowerCase(Locale.ROOT);
                String abs = resolveUrl(rel);
                if (low.contains("message") || low.contains("soobsch")) {
                    storage.saveDiscoveredEndpoint("messages", abs);
                } else if (low.contains("file") && low.contains("group")) {
                    storage.saveDiscoveredEndpoint("groupFiles", abs);
                } else if (low.contains("dnevnik") || low.contains("jurnal")) {
                    storage.saveDiscoveredEndpoint("diary", abs);
                } else if (low.contains("profile") || low.contains("anketa")) {
                    storage.saveDiscoveredEndpoint("profile", abs);
                }
            }

            // 2. Извлечение ФИО и группы из текста шапки ЛК
            String plain = cabHtml
                .replaceAll("(?s)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?s)<style[^>]*>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .trim();

            String foundName = "";
            Pattern fioPat = Pattern.compile("([А-ЯЁ][а-яё\\-]{2,}\\s+[А-ЯЁ][а-яё]{2,}\\s+[А-ЯЁ][а-яё]{2,}(?:вич|вна|ична|ович|евич|овна|евна))");
            Matcher nm = fioPat.matcher(plain);
            if (nm.find()) {
                String[] parts = nm.group(1).trim().split("\\s+");
                if (parts.length >= 2) {
                    foundName = parts[1] + " " + parts[0]; // Имя Фамилия
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

    /**
     * Скачивает файл из ЛК (например, из «Файлов группы») с текущими cookies сессии.
     */
    public boolean downloadToFile(String fileUrl, File destFile) {
        try {
            URL url = new URL(resolveUrl(fileUrl));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(25000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) Chrome/128.0.0.0 Mobile Safari/537.36");
            conn.setRequestProperty("Referer", LOGIN_PAGE_URL);

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
}