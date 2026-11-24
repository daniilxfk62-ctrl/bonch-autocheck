package ru.sut.bonch;

import android.content.Context;
import android.content.pm.PackageInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

public class UpdateChecker {
    public static final String FILE_UPDATE_CACHE = "update_cache_v1.json";

    private static final String PRIMARY_REPO = "daniilxfk62-ctrl/bonch-autocheck";
    private static final String FALLBACK_REPO = "g1tm4estr0/bonch-autocheck";
    private static final String PRIMARY_API = "https://api.github.com/repos/" + PRIMARY_REPO + "/releases/latest";
    private static final String FALLBACK_API = "https://api.github.com/repos/" + FALLBACK_REPO + "/releases/latest";

    private static final long CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L; // 12 часов
    private static final AtomicBoolean checking = new AtomicBoolean(false);

    public static String getInstalledVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (pi != null && pi.versionName != null && !pi.versionName.trim().isEmpty()) {
                return pi.versionName.trim();
            }
        } catch (Exception ignored) {}
        return AppStorage.APP_VERSION;
    }

    public static String getCachedInfoJson(Context ctx, AppStorage storage) {
        String installed = getInstalledVersion(ctx);
        JSONObject cached = storage.readJson(FILE_UPDATE_CACHE, "{}");
        try {
            cached.put("currentVersion", installed);
            String latest = cached.optString("latestVersion", "");
            if (!latest.isEmpty()) {
                cached.put("hasUpdate", compareVersions(latest, installed) > 0);
            } else {
                cached.put("hasUpdate", false);
            }
            cached.put("checking", checking.get());
        } catch (Exception ignored) {}
        return cached.toString();
    }

    public static void checkAsync(final Context ctx, final AppStorage storage, final boolean force) {
        if (!force) {
            JSONObject cached = storage.readJson(FILE_UPDATE_CACHE, "{}");
            long lastChecked = cached.optLong("checkedAt", 0L);
            if (lastChecked > 0L && (System.currentTimeMillis() - lastChecked) < CHECK_INTERVAL_MS) {
                return;
            }
        }
        if (!checking.compareAndSet(false, true)) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String currentVer = getInstalledVersion(ctx);
                    JSONObject result = new JSONObject();
                    result.put("checkedAt", System.currentTimeMillis());
                    result.put("currentVersion", currentVer);
                    result.put("hasUpdate", false);
                    result.put("latestVersion", currentVer);
                    result.put("releaseName", "");
                    result.put("releaseNotes", "");
                    result.put("apkUrl", "");
                    result.put("apkName", "");
                    result.put("releaseUrl", "");
                    result.put("sourceRepo", PRIMARY_REPO);
                    result.put("isFallback", false);
                    result.put("error", "");

                    // 1. Приоритетно проверяем основной репозиторий
                    ReleaseInfo primary = fetchLatestRelease(PRIMARY_API, PRIMARY_REPO);
                    if (primary != null && compareVersions(primary.version, currentVer) > 0) {
                        fillResult(result, primary, false);
                        storage.writeText(FILE_UPDATE_CACHE, result.toString());
                        storage.addLog("🆕 Найдено обновление v" + primary.version + " (" + PRIMARY_REPO + ")");
                        return;
                    }

                    // 2. Если на основном обновлений нет — проверяем запасной репозиторий
                    ReleaseInfo fallback = fetchLatestRelease(FALLBACK_API, FALLBACK_REPO);
                    if (fallback != null && compareVersions(fallback.version, currentVer) > 0) {
                        fillResult(result, fallback, true);
                        storage.writeText(FILE_UPDATE_CACHE, result.toString());
                        storage.addLog("🆕 Найдено обновление v" + fallback.version + " (запасной: " + FALLBACK_REPO + ")");
                        return;
                    }

                    if (primary == null && fallback == null) {
                        result.put("error", "Не удалось проверить GitHub");
                    } else if (primary != null) {
                        result.put("latestVersion", primary.version);
                        result.put("releaseUrl", primary.releaseUrl);
                    }
                    storage.writeText(FILE_UPDATE_CACHE, result.toString());
                } catch (Exception e) {
                    try {
                        JSONObject errObj = storage.readJson(FILE_UPDATE_CACHE, "{}");
                        errObj.put("checkedAt", System.currentTimeMillis());
                        errObj.put("error", "Ошибка проверки обновлений");
                        storage.writeText(FILE_UPDATE_CACHE, errObj.toString());
                    } catch (Exception ignored) {}
                } finally {
                    checking.set(false);
                }
            }
        }).start();
    }

    private static void fillResult(JSONObject dst, ReleaseInfo rel, boolean isFallback) throws Exception {
        dst.put("hasUpdate", true);
        dst.put("latestVersion", rel.version);
        dst.put("releaseName", rel.name);
        dst.put("releaseNotes", rel.notes);
        dst.put("apkUrl", rel.apkUrl);
        dst.put("apkName", rel.apkName);
        dst.put("releaseUrl", rel.releaseUrl);
        dst.put("sourceRepo", rel.repo);
        dst.put("isFallback", isFallback);
        dst.put("error", "");
    }

    private static class ReleaseInfo {
        String repo = "";
        String version = "";
        String name = "";
        String notes = "";
        String apkUrl = "";
        String apkName = "";
        String releaseUrl = "";
    }

    private static ReleaseInfo fetchLatestRelease(String apiUrl, String repoName) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "Pulse-Bonch-App");
            conn.setRequestProperty("Accept", "application/vnd.github+json");

            int code = conn.getResponseCode();
            if (code != 200) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = conn.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    baos.write(buf, 0, n);
                }
            }
            String jsonStr = new String(baos.toByteArray(), "UTF-8");
            JSONObject obj = new JSONObject(jsonStr);
            String tag = obj.optString("tag_name", "").trim();
            if (tag.isEmpty()) return null;

            ReleaseInfo info = new ReleaseInfo();
            info.repo = repoName;
            info.version = cleanVersion(tag);
            info.name = obj.optString("name", tag).trim();
            String body = obj.optString("body", "").trim();
            info.notes = body.length() > 600 ? (body.substring(0, 600) + "…") : body;
            info.releaseUrl = obj.optString("html_url", "https://github.com/" + repoName + "/releases");

            JSONArray assets = obj.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String aName = a.optString("name", "");
                    String dlUrl = a.optString("browser_download_url", "");
                    if (aName.toLowerCase(Locale.ROOT).endsWith(".apk") && !dlUrl.isEmpty()) {
                        info.apkName = aName;
                        info.apkUrl = dlUrl;
                        break;
                    }
                }
            }
            return info;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String cleanVersion(String raw) {
        if (raw == null) return "0";
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int dash = s.indexOf('-');
        if (dash > 0) s = s.substring(0, dash);
        return s.trim();
    }

    public static int compareVersions(String v1, String v2) {
        String[] p1 = cleanVersion(v1).split("\\.");
        String[] p2 = cleanVersion(v2).split("\\.");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int n1 = parseIntSafe(i < p1.length ? p1[i] : "0");
            int n2 = parseIntSafe(i < p2.length ? p2[i] : "0");
            if (n1 != n2) return Integer.compare(n1, n2);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }
}