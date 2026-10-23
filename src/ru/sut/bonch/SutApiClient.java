package ru.sut.bonch;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.CookieStore;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Locale;

public class SutApiClient {
    public static final String BASE_URL = "https://lk.sut.ru/cabinet";
    public static final String LOGIN_PAGE_URL = BASE_URL + "/?login=yes";
    public static final String AUTH_URL = BASE_URL + "/lib/autentificationok.php";
    public static final String INI_URL = BASE_URL + "/ini.php";
    public static final String SCHEDULE_URL = BASE_URL + "/project/cabinet/forms/raspisanie.php";
    public static final String[] DIARY_URLS = {
        BASE_URL + "/project/cabinet/forms/dnevnik.php",
        BASE_URL + "/project/cabinet/forms/jurnal.php",
        BASE_URL + "/project/cabinet/forms/usp.php"
    };

    private CookieManager cookieManager;
    private volatile boolean authenticated = false;
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
            lastLogin = login;
            lastPass = pass;
        }
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

    private String executeSingleRequest(String method, String urlStr, String postBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(25000);
        conn.setReadTimeout(40000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Connection", "close");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) Chrome/128.0.0.0 Mobile Safari/537.36");
        conn.setRequestProperty("Referer", LOGIN_PAGE_URL);
        conn.setRequestProperty("Origin", "https://lk.sut.ru");
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
        if (code >= 300 && code < 400) {
            conn.disconnect();
            return "";
        }

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
        String lower = utf8.toLowerCase(Locale.ROOT);
        if (lower.contains("расписание") || lower.contains("занят") || lower.contains("присутств")
            || lower.contains("понедельник") || lower.contains("вторник") || lower.contains("дневник")
            || lower.contains("оценк") || utf8.trim().equals("1")) {
            return utf8;
        }
        String cp1251 = new String(bytes, Charset.forName("windows-1251"));
        if (cp1251.toLowerCase(Locale.ROOT).contains("расписание")
            || cp1251.toLowerCase(Locale.ROOT).contains("занят")
            || cp1251.toLowerCase(Locale.ROOT).contains("дневник")) {
            return cp1251;
        }
        return utf8;
    }

    public synchronized boolean authenticate(String login, String password, AppStorage storage) {
        try {
            resetCookies();
            httpRequest("GET", LOGIN_PAGE_URL, null);
            String data = "users=" + URLEncoder.encode(login, "UTF-8")
                        + "&parole=" + URLEncoder.encode(password, "UTF-8");
            String resp = httpRequest("POST", AUTH_URL + "?" + data, data);
            if (resp.trim().equals("1") || resp.contains("1")) {
                httpRequest("GET", INI_URL, null);
                httpRequest("GET", LOGIN_PAGE_URL, null);
                authenticated = true;
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
}
