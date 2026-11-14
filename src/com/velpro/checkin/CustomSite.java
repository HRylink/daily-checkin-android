package com.velpro.checkin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLException;

/** Cookie-authenticated generic claim endpoint. No Android dependencies or automatic POST replay. */
public final class CustomSite {
    public static final String UNCONFIRMED = "请求已发出但结果未确认，请到网站查看，当天不再重试";
    public static final class Resp {
        public final int status;
        public final String text;
        public Resp(int status, String text) { this.status = status; this.text = text == null ? "" : text; }
    }
    public static final class Result {
        public boolean ok, invalid, needsAuth, unconfirmed;
        public String message = "", text = "";
    }
    public interface Saver {
        boolean blocked();
        boolean beforePost();
        void after(Result result);
    }
    interface Opener { HttpURLConnection open(URL url) throws IOException; }
    private final int connectTimeout, readTimeout, retryDelay;
    private final Opener opener;
    public CustomSite() { this(12000, 20000, 600, url -> (HttpURLConnection) url.openConnection()); }
    // Package-local connection injection keeps HTTP fixtures out of production URL policy.
    CustomSite(int connectTimeout, int readTimeout, int retryDelay, Opener opener) {
        this.connectTimeout = connectTimeout; this.readTimeout = readTimeout;
        this.retryDelay = retryDelay; this.opener = opener;
    }
    public static boolean validUrl(String raw) {
        try {
            URI u = new URI(raw);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isEmpty()
                    && u.getRawUserInfo() == null && u.getRawFragment() == null
                    && (u.getPort() == -1 || u.getPort() > 0 && u.getPort() <= 65535);
        } catch (Exception e) { return false; }
    }
    public static String home(String url) {
        if (!validUrl(url)) throw new IllegalArgumentException("领取接口必须是合法的 https 地址");
        URI u = URI.create(url);
        return "https://" + u.getRawAuthority() + "/";
    }
    public static String validate(SiteRegistry.Custom c) {
        if (c == null || c.name == null || c.name.trim().isEmpty()) return "请填写站点名称";
        if (!validUrl(c.url)) return "领取接口必须是合法的 https 地址（不能包含账号密码或片段）";
        if (!"GET".equals(c.method) && !"POST".equals(c.method)) return "方法只能是 GET 或 POST";
        try { headers(c.headers); } catch (IllegalArgumentException e) { return e.getMessage(); }
        return "";
    }
    public static Map<String,String> splitCookies(String raw) {
        Map<String,String> out = new LinkedHashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split(";")) {
            int equals = pair.indexOf('=');
            if (equals > 0) out.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
        }
        return out;
    }
    public static String joinCookies(Map<String,String> cookies) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String,String> pair : cookies.entrySet()) {
            if (out.length() > 0) out.append("; ");
            out.append(pair.getKey()).append('=').append(pair.getValue());
        }
        return out.toString();
    }
    private static boolean control(String value) {
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 || value.charAt(i) == 127) return true;
        return false;
    }
    private static Map<String,String> headers(String raw) {
        Map<String,String> out = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String line : raw.split("\\r?\\n", -1)) {
            if (line.trim().isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new IllegalArgumentException("额外请求头格式应为 名称: 值（每行一个）");
            String name = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
            if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || control(value))
                throw new IllegalArgumentException("额外请求头包含非法字符");
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals("cookie") || lower.equals("authorization") || lower.equals("proxy-authorization")
                    || lower.equals("host") || lower.equals("content-length") || lower.equals("transfer-encoding")
                    || lower.equals("connection"))
                throw new IllegalArgumentException("额外请求头不能包含 Cookie/Authorization 或连接控制头；Cookie 由登录捕获");
            out.put(name, value);
        }
        return out;
    }
    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                && (a.getPort() == -1 ? 443 : a.getPort()) == (b.getPort() == -1 ? 443 : b.getPort());
    }
    private static String shorten(String text) {
        if (text == null) return "";
        int count = text.codePointCount(0, text.length());
        return count <= 200 ? text : text.substring(0, text.offsetByCodePoints(0, 200));
    }
    public Resp request(String url, String method, String body, String cookie, String extraHeaders) throws IOException {
        if (!validUrl(url)) throw new ProtocolException("领取接口必须使用 https");
        if (!"GET".equals(method) && !"POST".equals(method)) throw new ProtocolException("方法只能是 GET 或 POST");
        if (cookie == null || control(cookie)) throw new ProtocolException("Cookie 格式无效");
        Map<String,String> extra = headers(extraHeaders);
        URI original = URI.create(url), current = original;
        String verb = method;
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection c = opener.open(current.toURL());
            try {
                c.setInstanceFollowRedirects(false); // never forward credentials to another origin
                c.setConnectTimeout(connectTimeout); c.setReadTimeout(readTimeout);
                c.setRequestMethod(verb);
                c.setRequestProperty("Cookie", cookie);
                for (Map.Entry<String,String> h : extra.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                if ("POST".equals(verb)) {
                    byte[] bytes = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
                    c.setDoOutput(true);
                    // Streaming mode also disables HttpURLConnection's implicit POST replay.
                    c.setFixedLengthStreamingMode(bytes.length);
                    try (java.io.OutputStream out = c.getOutputStream()) { out.write(bytes); }
                }
                int status = c.getResponseCode();
                if (status < 100) throw new IOException("服务器未返回 HTTP 响应");
                boolean redirect = status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
                // POST 307/308 is a response to show, not permission to resend the body.
                if (redirect && c.getHeaderField("Location") != null
                        && !("POST".equals(verb) && (status == 307 || status == 308))) {
                    URI next;
                    try { next = current.resolve(c.getHeaderField("Location")); }
                    catch (Exception e) { throw new ProtocolException("站点重定向地址无效"); }
                    if (!validUrl(next.toString()) || !sameOrigin(original, next))
                        throw new ProtocolException("已阻止非 https 或跨源重定向，Cookie 仅发送至原站点");
                    if (redirects >= 5) throw new ProtocolException("站点重定向过多");
                    current = next;
                    if (status == 303 || "POST".equals(verb) && (status == 301 || status == 302)) verb = "GET";
                    continue;
                }
                InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
                if (stream == null) return new Resp(status, "");
                ByteArrayOutputStream kept = new ByteArrayOutputStream();
                long received = 0;
                try (InputStream in = stream) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) != -1) {
                        received += n;
                        if (kept.size() < 4096) kept.write(buf, 0, Math.min(n, 4096 - kept.size()));
                    }
                }
                long expected = c.getContentLengthLong();
                if (expected >= 0 && received < expected) throw new IOException("响应传输中断");
                return new Resp(status, shorten(new String(kept.toByteArray(), StandardCharsets.UTF_8)));
            } finally { c.disconnect(); }
        }
    }
    public Result check(SiteRegistry.Custom config, String cookie) {
        Result r = new Result();
        String error = validate(config);
        if (!error.isEmpty()) { r.message = error; return r; }
        if (cookie == null || cookie.isEmpty()) {
            r.invalid = r.needsAuth = true; r.message = "凭据缺失，请重新登录"; return r;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Resp response = request(config.url, config.method, config.body, cookie, config.headers);
                r.text = shorten(response.text);
                r.ok = response.status >= 200 && response.status < 300;
                r.invalid = r.needsAuth = response.status == 401 || response.status == 403;
                r.unconfirmed = "POST".equals(config.method) && (response.status == 307 || response.status == 308);
                r.message = r.unconfirmed ? "站点要求重发 POST，已阻止自动重放；" + UNCONFIRMED
                        : r.ok ? "领取成功" : r.invalid ? "凭据无效/未登录，请重新登录（HTTP " + response.status + "）"
                        : "领取失败（HTTP " + response.status + "）";
                if (!r.text.isEmpty()) r.message += "：" + r.text;
                return r;
            } catch (IOException e) {
                if ("GET".equals(config.method) && attempt == 0 && !(e instanceof ProtocolException) && !(e instanceof SSLException)) {
                    try { Thread.sleep(retryDelay); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); r.message = "网络请求已取消"; return r; }
                    continue;
                }
                r.unconfirmed = "POST".equals(config.method);
                r.message = r.unconfirmed ? UNCONFIRMED : e instanceof ProtocolException
                        ? e.getMessage() : e instanceof SSLException ? "网站证书/TLS 验证失败，已停止请求"
                        : "网络异常，无法领取；请检查网络后重试";
                return r;
            } catch (IllegalArgumentException e) { r.message = e.getMessage(); return r; }
        }
        return r;
    }
}
