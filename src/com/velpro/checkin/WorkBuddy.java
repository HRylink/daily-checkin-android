package com.velpro.checkin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** WorkBuddy international protocol: browser-state login, refresh, meter check-in. Pure Java + org.json. */
public class WorkBuddy {
    public static final String ENDPOINT = "https://www.workbuddy.ai";
    public static final String DOMAIN = "www.workbuddy.ai";
    public static final String APP = "5.9.3";
    public static final Site AI = new Site(ENDPOINT, "workbuddy-ai", DOMAIN, APP);
    public static final Site CN = new Site("https://copilot.tencent.com", "workbuddy",
            "copilot.tencent.com", APP);
    private static final long SIGN_IN_MS = 5 * 60 * 1000L;

    /** One WorkBuddy deployment; the protocol is identical, only these four values differ. */
    public static final class Site {
        public final String endpoint, platform, domain, app;
        public Site(String endpoint, String platform, String domain, String app) {
            this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
            this.platform = platform; this.domain = domain; this.app = app;
        }
        public Site withEndpoint(String endpoint) { return new Site(endpoint, platform, domain, app); }
    }

    private final Site site;
    private final int connectMs, readMs, pollMs, retryDelayMs;

    public WorkBuddy() { this(AI, 12000, 20000, 1000, 600); }
    public WorkBuddy(String endpoint, int connectMs, int readMs, int pollMs) {
        this(AI.withEndpoint(endpoint), connectMs, readMs, pollMs, 600);
    }
    public WorkBuddy(Site site, int connectMs, int readMs, int pollMs, int retryDelayMs) {
        this.site = site;
        this.connectMs = connectMs; this.readMs = readMs; this.pollMs = pollMs; this.retryDelayMs = retryDelayMs;
    }

    public static class Auth {
        public String access = "", refresh = "", uid = "", user = "";
        public long expires;
    }
    public static class R {
        public String outcome = "failed", message = "";
        public int credit, streak;
        public boolean needsAuth;
        public boolean ok() { return "done".equals(outcome) || "claimed".equals(outcome); }
    }
    public interface Saver { void save(Auth a); }
    public static class Start {
        public final String state, url;
        public final long deadline;
        Start(String state, String url, long deadline) { this.state = state; this.url = url; this.deadline = deadline; }
    }

    private static final class Resp { final int status; final String text; Resp(int s, String t) { status = s; text = t; } }
    public static final class Api extends Exception {
        final int status, code;
        Api(int status, int code, String msg) { super(msg); this.status = status; this.code = code; }
    }

    private static String hex(int bytes) {
        SecureRandom r = new SecureRandom(); byte[] b = new byte[bytes]; r.nextBytes(b);
        StringBuilder s = new StringBuilder(bytes * 2);
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }
    private static String first(String... v) { for (String s : v) if (s != null && !s.isEmpty()) return s; return ""; }
    private static int num(Object o, int fallback) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o != null) try { return (int) Double.parseDouble(o.toString()); } catch (Exception ignored) { }
        return fallback;
    }
    private static long when(Object o, long fallback) {
        if (o instanceof Number) { long n = ((Number) o).longValue(); return n < 100_000_000_000L ? n * 1000 : n; }
        if (o != null) { try { long n = (long) Double.parseDouble(o.toString()); return n < 100_000_000_000L ? n * 1000 : n; } catch (Exception ignored) { } }
        return fallback;
    }

    private Resp request(String method, String path, Map<String, String> headers, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(site.endpoint + path).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(connectMs); c.setReadTimeout(readMs);
        if ("/billing/meter/get-user-resource-summary".equals(path)) c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "WorkBuddy/" + site.app);
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8); c.setDoOutput(true);
            if ("/billing/meter/get-user-resource-summary".equals(path)) c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) {
            try (InputStream i = in; ByteArrayOutputStream b = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096]; int n; while ((n = i.read(buf)) != -1) b.write(buf, 0, n);
                text = b.toString("UTF-8");
            }
        }
        c.disconnect();
        return new Resp(status, text);
    }

    public JSONObject resourceSummary(Auth auth) throws IOException, Api {
        return call("POST","/billing/meter/get-user-resource-summary",meterHeaders(auth),"{}");
    }

    private JSONObject call(String method, String path, Map<String, String> headers, String body) throws IOException, Api {
        Resp r = request(method, path, headers, body);
        JSONObject env = new JSONObject();
        int code = 0; String msg = "";
        try { env = new JSONObject(r.text); } catch (Exception ignored) { }
        code = env.optInt("code", 0); msg = env.optString("msg", env.optString("message", ""));
        if (r.status < 200 || r.status >= 300) throw new Api(r.status, code, msg.isEmpty() ? ("HTTP " + r.status) : msg);
        if (code != 0) throw new Api(r.status, code, msg);
        return env.optJSONObject("data");
    }

    private JSONObject poll(String method, String path, Map<String, String> headers, int[] retry, long deadline) throws IOException, Api {
        while (true) {
            if (System.currentTimeMillis() > deadline) throw new Api(0, 0, "WorkBuddy 登录超时");
            try {
                JSONObject data = call(method, path, headers, null);
                if (data != null && data.length() > 0) return data;
            } catch (Api e) {
                boolean retryable = false;
                for (int c : retry) if (c == e.code) { retryable = true; break; }
                if (!retryable && e.status != 408 && e.status != 429) throw e;
            }
            try { Thread.sleep(pollMs); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    private Map<String, String> stateHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-No-Authorization", "true"); h.put("X-No-User-Id", "true");
        h.put("X-No-Enterprise-Id", "true"); h.put("X-No-Department-Info", "true");
        return h;
    }

    public Start begin() throws IOException, Api {
        JSONObject data = call("POST", "/v2/plugin/auth/state?platform=" + urlenc(site.platform), stateHeaders(), "{}");
        if (data == null || data.optString("state").isEmpty() || data.optString("authUrl").isEmpty())
            throw new IOException("WorkBuddy 登录状态接口返回异常");
        String state = data.optString("state");
        String auth = data.optString("authUrl");
        String sep = auth.contains("?") ? "&" : "?";
        String url = auth + sep + "version=" + urlenc(site.app) + "&loginSessionId=" + hex(16);
        return new Start(state, url, System.currentTimeMillis() + SIGN_IN_MS);
    }

    public Auth finish(Start start) throws IOException, Api {
        String q = urlenc(start.state);
        JSONObject token = poll("GET", "/v2/plugin/auth/token?state=" + q, Collections.<String, String>emptyMap(),
                new int[]{11217}, start.deadline);
        Auth a = new Auth();
        a.access = token.optString("accessToken");
        a.refresh = token.optString("refreshToken");
        a.expires = when(token.opt("expiresAt"), 0);
        if (a.expires == 0 && token.opt("expiresIn") instanceof Number) a.expires = System.currentTimeMillis() + (long) token.optDouble("expiresIn") * 1000;
        if (a.access.isEmpty()) throw new IOException("WorkBuddy 未返回登录令牌");
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + a.access);
        h.put("X-Domain", site.domain);
        h.put("X-No-User-Id", "true"); h.put("X-No-Enterprise-Id", "true");
        JSONObject who = poll("GET", "/v2/plugin/login/account?state=" + q, h, new int[]{12151}, start.deadline);
        a.uid = who.optString("uid", "");
        a.user = first(who.optString("nickname"), who.optString("phoneNumber"), a.uid);
        return a;
    }

    public Auth signIn() throws IOException, Api { Start s = begin(); return finish(s); }

    private Map<String, String> refreshHeaders(Auth a) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Refresh-Token", a.refresh);
        h.put("X-Auth-Refresh-Source", "plugin");
        h.put("X-Domain", site.domain);
        return h;
    }

    public Auth refresh(Auth a) throws IOException, Api {
        JSONObject got = call("POST", "/v2/plugin/auth/token/refresh", refreshHeaders(a), "{}");
        if (got == null || got.optString("accessToken").isEmpty()) throw new Api(0, 0, "WorkBuddy 刷新未返回令牌");
        Auth out = new Auth();
        out.access = got.optString("accessToken");
        out.refresh = got.has("refreshToken") ? got.optString("refreshToken") : a.refresh;
        out.expires = when(got.opt("expiresAt"), a.expires);
        if (got.opt("expiresAt") == null && got.has("expiresIn"))
            out.expires = System.currentTimeMillis() + (long) got.optDouble("expiresIn") * 1000;
        out.uid = a.uid; out.user = a.user;
        return out;
    }
    public Auth refresh(Auth a, Saver saver) throws IOException, Api {
        Auth next = refresh(a);
        if (saver != null) saver.save(next);
        return next;
    }

    private Map<String, String> meterHeaders(Auth a) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + a.access);
        h.put("X-User-Id", a.uid);
        h.put("X-Domain", site.domain);
        h.put("X-Product", "SaaS");
        h.put("X-IDE-Type", "WorkBuddy");
        return h;
    }

    private JSONObject status(Auth a) throws IOException, Api {
        int tried = 0;
        while (true) {
            try { return call("POST", "/v2/billing/meter/checkin-activity-status", meterHeaders(a), "{}"); }
            catch (Api e) {
                if ((e.status >= 500 || e.code == 10000) && tried++ == 0) { try { Thread.sleep(retryDelayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } continue; }
                throw e;
            }
        }
    }

    public R checkIn(Auth a) {
        R r = new R();
        JSONObject st;
        try {
            st = status(a);
            if (st == null) st = new JSONObject();
        } catch (Api e) {
            if (e.code == 1001) { r.outcome = "done"; r.message = e.getMessage(); return r; }
            if (e.status == 401) { r.outcome = "failed"; r.needsAuth = true; r.message = e.getMessage(); return r; }
            r.outcome = "failed"; r.message = e.getMessage(); return r;
        } catch (IOException e) {
            r.outcome = "failed"; r.message = e.getMessage(); return r;
        }
        if (!st.optBoolean("active", true)) { r.outcome = "inactive"; r.message = "WorkBuddy 活动未开启"; return r; }
        boolean checked = st.optBoolean("today_checked_in") || st.optBoolean("todayCheckedIn");
        if (checked) {
            r.outcome = "done";
            r.credit = num(st.has("today_credit") ? st.opt("today_credit") : st.opt("daily_credit"), 0);
            return r;
        }
        try {
            JSONObject got = call("POST", "/v2/billing/meter/daily-checkin", meterHeaders(a), "{}");
            r.outcome = "claimed";
            r.credit = num(got == null ? null : got.opt("credit"), 0);
            r.streak = num(got == null ? null : got.opt("streak_days"), 0);
            if (got != null) r.message = got.optString("message", "");
        } catch (Api e) {
            if (e.code == 1001) r.outcome = "done";
            else if (e.code == 1002) r.outcome = "ineligible";
            else if (e.code == 1003) r.outcome = "inactive";
            else if (e.status == 401) { r.outcome = "failed"; r.needsAuth = true; }
            else r.outcome = "failed";
            r.message = e.getMessage();
        } catch (IOException e) {
            r.outcome = "failed"; r.message = e.getMessage();
        }
        return r;
    }

    /** Read-only credential check used immediately after login; it never sends a claim. */
    public SiteRegistry.Verify verify(Auth a) {
        try {
            status(a);
            return SiteRegistry.Verify.pass();
        } catch (Api e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (e.status == 401 || e.code == 1001) return SiteRegistry.Verify.invalid("凭据无效，请重新登录：" + m);
            return SiteRegistry.Verify.network("网络异常，无法验证：" + m);
        } catch (Exception e) {
            return SiteRegistry.Verify.network("网络异常，无法验证：" + e.getMessage());
        }
    }

    private static String urlenc(String v) { try { return URLEncoder.encode(v, "UTF-8"); } catch (Exception e) { return v; } }
}
