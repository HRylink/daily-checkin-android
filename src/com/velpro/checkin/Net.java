package com.velpro.checkin;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent cookie jars: never copy GitHub credentials into the API client. */
public class Net {
    public static final String AR = "https://ps.air-outer.com";
    public static final String S25 = "https://ly.77625.xyz";
    public static final String CLIENT_ID = "Ov23lidtiR4LeVZvVRNL";
    private final String ar, s25, github;
    private final int connectTimeout, readTimeout, retryDelay;
    private Session agent;
    private String expectedState = "", stage = "网络请求";
    private java.util.function.Consumer<String> progress;

    public Net() { this(AR, S25); }
    Net(String ar, String s25) { this(ar, s25, "https://github.com"); }
    Net(String ar, String s25, String github) { this(ar, s25, github, 15000, 30000, 600); }
    // Short deadlines for real local HTTP fault tests; production uses the defaults above.
    Net(String ar, String s25, String github, int connectTimeout, int readTimeout, int retryDelay) {
        this.ar = ar; this.s25 = s25; this.github = github;
        this.connectTimeout = connectTimeout; this.readTimeout = readTimeout; this.retryDelay = retryDelay;
        agent = session(false);
    }
    public void setProgressListener(java.util.function.Consumer<String> listener) { progress = listener; }
    private Session session(boolean direct) { return new Session(direct, connectTimeout, readTimeout, retryDelay,
            () -> { if (progress != null) progress.accept(stage + " · 网络波动，重试状态查询（1/1）…"); }); }
    private void stage(String value, String base) {
        stage = value + "（" + URI.create(base).getHost() + "）";
        if (progress != null) progress.accept(stage + "…");
    }

    public static class R {
        public boolean ok, loggedIn, needsAuth;
        public String msg = "", balance = "", siteId = "";
        public JSONObject data;
        public final List<String> githubUpdates = new ArrayList<>();
    }
    private static class Resp {
        int code;
        String body;
        Map<String, List<String>> headers;
    }
    private static class Session {
        final java.net.CookieManager cookies = new java.net.CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        final boolean direct;
        final int connectTimeout, readTimeout, retryDelay;
        final Runnable retryNotice;
        Session(boolean direct, int connectTimeout, int readTimeout, int retryDelay, Runnable retryNotice) {
            this.direct = direct; this.connectTimeout = connectTimeout; this.readTimeout = readTimeout;
            this.retryDelay = retryDelay; this.retryNotice = retryNotice;
        }
        Resp req(String method, String url, String body, Map<String, String> headers) throws IOException {
            return once(method, url, body, headers, readTimeout);
        }
        /** Whitelisted read-only queries only. OAuth GETs are NOT safe to replay. */
        Resp query(String url, Map<String, String> headers) throws IOException {
            String path = URI.create(url).getPath();
            if (!Arrays.asList("/api/auth/me", "/api/user/self", "/api/status").contains(path))
                throw new IOException("已阻止非状态查询自动重试");
            for (int attempt = 0; ; attempt++) {
                try {
                    Resp r = once("GET", url, null, headers, readTimeout);
                    if (attempt == 1 || !(r.code == 502 || r.code == 503 || r.code == 504)) return r;
                } catch (IOException e) {
                    if (attempt == 1 || !(e instanceof SocketTimeoutException || e instanceof SocketException
                            || e instanceof EOFException) || Thread.currentThread().isInterrupted()) throw e;
                }
                retryNotice.run();
                try { Thread.sleep(retryDelay); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("请求已中断", e); }
            }
        }
        Resp optional(String url, Map<String, String> headers) throws IOException {
            return once("GET", url, null, headers, Math.min(readTimeout, 5000));
        }
        Resp once(String method, String url, String body, Map<String, String> headers, int timeout) throws IOException {
            URL target = new URL(url);
            HttpURLConnection c = (HttpURLConnection) (direct
                    ? target.openConnection(Proxy.NO_PROXY) : target.openConnection());
            c.setConnectTimeout(Math.min(connectTimeout, timeout));
            c.setReadTimeout(timeout);
            c.setUseCaches(false);
            c.setRequestMethod(method);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            URI uri = URI.create(url);
            for (Map.Entry<String, List<String>> e : cookies.get(uri, Collections.emptyMap()).entrySet()) {
                if (!e.getValue().isEmpty()) c.setRequestProperty(e.getKey(), String.join("; ", e.getValue()));
            }
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            try {
                if (body != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    c.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
                }
                Resp r = new Resp();
                r.code = c.getResponseCode();
                r.headers = c.getHeaderFields();
                cookies.put(uri, r.headers);
                InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
                r.body = "";
                if (in != null) try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = input.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        if (out.size() > 2_000_000) throw new IOException("响应过大");
                    }
                    r.body = out.toString("UTF-8");
                }
                return r;
            } finally { c.disconnect(); }
        }
    }
    private static JSONObject json(Resp r) throws IOException {
        if (r.code == 429) throw new IOException("请求太频繁（HTTP 429），请稍后再试");
        String challenge = header(r, "cf-mitigated");
        if (r.code == 403 && "challenge".equalsIgnoreCase(challenge))
            throw new IOException("Cloudflare 人机验证，请在站点完成验证后重试（浏览器验证可能不能共享给此客户端）");
        if (r.code >= 500) throw new IOException("站点暂时不可用（HTTP " + r.code + "），请稍后再试");
        if (r.code == 401) throw new IOException("登录状态失效（HTTP 401），请重新登录");
        if (r.code == 403) throw new IOException("站点拒绝访问（HTTP 403），请检查权限或人机验证");
        try { return new JSONObject(r.body); }
        catch (Exception e) { throw new IOException("HTTP " + r.code + "：站点没有返回有效 JSON（可能被人机验证或网络拦截）"); }
    }
    private static String header(Resp r, String name) {
        for (Map.Entry<String, List<String>> e : r.headers.entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
        return "";
    }
    private static String message(JSONObject j) {
        for (String k : new String[]{"message", "detail", "error"}) {
            String value = j.optString(k, "");
            if (!value.isEmpty()) return value;
        }
        return "未返回成功状态";
    }
    public String failureMessage(Exception e) {
        String detail;
        if (e instanceof UnknownHostException) detail = "DNS 解析失败，请检查分流规则与 DNS 设置";
        else if (e instanceof SocketTimeoutException) detail = "连接或等待响应超时，请检查网络/代理或稍后重试";
        else if (e instanceof javax.net.ssl.SSLException) detail = "TLS/证书验证失败，请检查手机时间与网络；未绕过证书验证";
        else if (e instanceof ConnectException) detail = "连接失败，请检查站点是否可访问及代理是否连接";
        else if (e instanceof SocketException || e instanceof EOFException) detail = "连接中断，请检查网络/代理后重试";
        else detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        // Never log a request URL (OAuth query can contain one-time credentials).
        detail = detail.replaceAll("https?://[^\\s）]+", "[请求地址已隐藏]");
        return stage + "：" + detail;
    }
    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (UnsupportedEncodingException e) { throw new AssertionError(e); }
    }
    public static String authorizeUrl(String state) {
        return "https://github.com/login/oauth/authorize?client_id=" + CLIENT_ID + "&state=" + enc(state) + "&scope=user:email";
    }
    public static boolean isCallback(String value) {
        try {
            URI u = URI.create(value);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getUserInfo() == null
                    && (u.getPort() == -1 || u.getPort() == 443)
                    && ("agentrouter.org".equalsIgnoreCase(u.getHost()) || "ps.air-outer.com".equalsIgnoreCase(u.getHost()))
                    && "/oauth/github".equals(u.getPath());
        } catch (Exception e) { return false; }
    }

    /** Restore only our own API cookie, never a GitHub cookie, between manual runs. */
    private static void seed(Session session, String base, String raw) throws IOException {
        if (raw == null || raw.isEmpty()) return;
        List<String> cookies = new ArrayList<>();
        for (String pair : raw.split(";")) {
            String p = pair.trim();
            if (p.contains("=") && !p.startsWith("$") && !p.contains("\r") && !p.contains("\n"))
                cookies.add(p + "; Path=/" + (base.startsWith("https:") ? "; Secure" : ""));
        }
        session.cookies.put(URI.create(base), Collections.singletonMap("Set-Cookie", cookies));
    }
    public void restoreAgentCookie(String raw) throws IOException {
        agent = session(false);
        seed(agent, ar, raw);
    }
    public String agentCookie() {
        try {
            for (Map.Entry<String,List<String>> h : agent.cookies.get(URI.create(ar + "/api/user/logout"), Collections.emptyMap()).entrySet())
                if ("Cookie".equalsIgnoreCase(h.getKey())) return String.join("; ", h.getValue());
        } catch (IOException ignored) { }
        return "";
    }
    /** A balance is distinct from a newly awarded credit. Missing/invalid fields never become zero. */
    public static String confirmedBalance(JSONObject data, String key) {
        if (data == null || data.isNull(key)) return "";
        try {
            java.math.BigDecimal n = new java.math.BigDecimal(data.opt(key).toString());
            return n.signum() < 0 ? "" : n.stripTrailingZeros().toPlainString();
        } catch (Exception ignored) { return ""; }
    }
    /** Restored session only: no logout, OAuth, login or claim. Old installs may discover id via self. */
    public JSONObject agentBalance(String userId) throws IOException {
        Map<String,String> h = new HashMap<>();
        if (userId != null && !userId.isEmpty()) {
            try { if (Long.parseLong(userId) <= 0) throw new NumberFormatException(); }
            catch (NumberFormatException e) { throw new IOException("余额账号 ID 无效", e); }
            h.put("New-Api-User",userId);
        }
        Resp self = agent.optional(ar + "/api/user/self",h);
        JSONObject env = json(self), data = env.optJSONObject("data");
        if (self.code != 200 || !env.optBoolean("success") || data == null || confirmedBalance(data,"quota").isEmpty())
            throw new IOException("余额查询失败");
        double unit = 500000;
        try {
            Resp status = agent.optional(ar + "/api/status",null);
            JSONObject st = json(status).optJSONObject("data");
            double candidate = st == null ? 0 : st.optDouble("quota_per_unit");
            if (status.code == 200 && candidate > 0 && !Double.isInfinite(candidate)) unit=candidate;
        } catch (IOException ignored) { }
        try { data.put("quota_per_unit",unit); }
        catch (Exception e) { throw new IOException("余额格式错误",e); }
        return data;
    }
    private boolean trustedGitHub(URI u) {
        URI root = URI.create(github);
        return u.getUserInfo() == null && root.getScheme().equalsIgnoreCase(u.getScheme())
                && root.getHost().equalsIgnoreCase(u.getHost()) && root.getPort() == u.getPort();
    }
    private static Map<String,String> query(URI u) throws IOException {
        Map<String,String> values = new HashMap<>();
        for (String part : Objects.toString(u.getRawQuery(), "").split("&")) {
            String[] pair = part.split("=",2);
            if (pair.length != 2) continue;
            String k = URLDecoder.decode(pair[0],"UTF-8"), v = URLDecoder.decode(pair[1],"UTF-8");
            if (values.put(k,v) != null) throw new IOException("OAuth 回调参数重复，已阻止");
        }
        return values;
    }

    /** No WebView/UI on a normal run. Only an existing, user-authorized GitHub session is used. */
    public R checkAgentRouter(String rawCookies, String userAgent) {
        R result = new R();
        if (rawCookies == null || !rawCookies.contains("user_session=")) {
            result.needsAuth = true;
            result.msg = "请先登录/授权 GitHub，之后可一键后台领取";
            return result;
        }
        try {
            String state = beginAgentRouter();
            Session gh = session(false);
            seed(gh, github, rawCookies);
            String url = github + "/login/oauth/authorize?client_id=" + CLIENT_ID + "&state=" + enc(state) + "&scope=user:email";
            Map<String,String> headers = new HashMap<>();
            if (userAgent != null && !userAgent.isEmpty()) headers.put("User-Agent", userAgent);
            headers.put("Accept", "text/html,application/xhtml+xml");
            for (int hop = 0; hop < 6; hop++) {
                stage("AgentRouter · GitHub 授权", github);
                Resp r = gh.req("GET", url, null, headers);
                for (Map.Entry<String,List<String>> h : r.headers.entrySet())
                    if (h.getKey() != null && h.getKey().equalsIgnoreCase("Set-Cookie")) result.githubUpdates.addAll(h.getValue());
                if (r.code == 429) throw new IOException("GitHub 请求太频繁（HTTP 429），请稍后再试");
                if (r.code == 200) {
                    result.needsAuth = true;
                    result.msg = "GitHub 登录已失效或需要授权/验证，请点击登录/重新授权";
                    return result;
                }
                if (!(r.code == 301 || r.code == 302 || r.code == 303 || r.code == 307 || r.code == 308))
                    throw new IOException("GitHub 授权失败（HTTP " + r.code + "），请检查手机代理/网络");
                String location = header(r,"Location");
                if (location.isEmpty()) throw new IOException("GitHub 授权缺少跳转地址");
                URI next = URI.create(url).resolve(location);
                if (isCallback(next.toString())) {
                    Map<String,String> params = query(next);
                    R login = finishAgentRouter(params.get("code"), params.get("state"));
                    login.githubUpdates.addAll(result.githubUpdates);
                    return login;
                }
                if (!trustedGitHub(next)) throw new IOException("已阻止非 GitHub 的 OAuth 跳转");
                if (!"/login/oauth/authorize".equals(next.getPath())) {
                    result.needsAuth = true;
                    result.msg = "GitHub 需要重新登录/验证，请点击登录/重新授权";
                    return result;
                }
                url = next.toString();
            }
            throw new IOException("GitHub 授权跳转过多，请稍后重试");
        } catch (Exception e) { result.msg = failureMessage(e); }
        return result;
    }

    public R check77625(String user, String pwd) {
        R result = new R();
        if (user.isEmpty() || pwd.isEmpty()) { result.msg = "未填写77625账号密码，请到设置中保存"; return result; }
        Session s = session(true);
        try {
            stage("77625 · 登录", s25);
            JSONObject login = new JSONObject().put("username", user).put("password", pwd);
            Resp lr = s.req("POST", s25 + "/api/auth/login", login.toString(), null);
            JSONObject lj = json(lr);
            if (lr.code != 200 || (lj.has("success") && !lj.optBoolean("success")))
                throw new IOException("登录失败：" + message(lj));
            stage("77625 · 查询今日状态", s25);
            Resp me = s.query(s25 + "/api/auth/me", null);
            JSONObject account = json(me);
            if (me.code != 200) throw new IOException("登录状态未生效：" + message(account));
            if (account.optBoolean("checked_in_today")) {
                result.ok = true;
                result.msg = "今天已签到，当前 " + account.opt("credits") + " 积分";
                result.balance = confirmedBalance(account,"credits");
                return result;
            }
            stage("77625 · 提交签到", s25);
            Resp ci;
            try { ci = s.req("POST", s25 + "/api/account/checkin", "", null); }
            catch (IOException e) { return reconcile77625(s, failureMessage(e)); }
            JSONObject j;
            if (ci.code == 200 || ci.code >= 500) {
                try { j = json(ci); }
                catch (IOException e) { return reconcile77625(s, failureMessage(e)); }
            } else j = json(ci); // validation, 429 and challenges are not retried
            String text = message(j);
            if (ci.code == 200 && (!j.has("success") || j.optBoolean("success")) && j.has("points")) {
                result.ok = true;
                result.msg = "签到成功 +" + j.opt("points") + " 积分";
            } else if (text.contains("已签到") || text.contains("签到过")) {
                result.ok = true;
                result.msg = "今天已签到";
            } else if (ci.code == 200 && (!j.has("success") || j.optBoolean("success"))) {
                return reconcile77625(s, stage + "：站点未返回明确签到结果");
            } else throw new IOException("签到失败：" + text);
            try {
                stage("77625 · 已确认签到，查询积分", s25);
                Resp updated = s.optional(s25 + "/api/auth/me", null);
                JSONObject u = json(updated);
                if (updated.code == 200 && u.has("credits")) {
                    result.msg += "，当前 " + u.opt("credits") + " 积分";
                    result.balance = confirmedBalance(u,"credits");
                }
            } catch (IOException ignored) { /* reward acknowledged; balance optional and short */ }
        } catch (Exception e) { result.msg = failureMessage(e); }
        return result;
    }

    /** A lost acknowledgement is NOT a reason to repeat a reward POST. */
    private R reconcile77625(Session s, String cause) {
        R r = new R();
        stage("77625 · 签到响应异常，核验今日状态", s25);
        try {
            Resp me = s.query(s25 + "/api/auth/me", null);
            JSONObject account = json(me);
            if (me.code == 200 && account.optBoolean("checked_in_today")) {
                r.ok = true;
                r.msg = "签到响应异常，但已核验今天已签到";
                if (account.has("credits")) r.msg += "，当前 " + account.opt("credits") + " 积分";
                r.balance = confirmedBalance(account,"credits");
                return r;
            }
            r.msg = cause + "；结果未确认，核验时尚未签到，未重复提交，请稍后手动检查";
        } catch (Exception e) {
            r.msg = cause + "；结果未确认，" + failureMessage(e) + "，未重复提交，请稍后手动检查";
        }
        return r;
    }

    /** GET logout really exists; POST returns 404. Drop all old cookies before acquiring state. */
    public String beginAgentRouter() throws IOException {
        expectedState = "";
        stage("AgentRouter · 退出旧会话", ar);
        try {
            Resp out = agent.req("GET", ar + "/api/user/logout", null, null);
            JSONObject j = json(out);
            if (out.code != 200 || !j.optBoolean("success")) throw new IOException("退出失败：" + message(j));
        } finally { agent = session(false); }
        stage("AgentRouter · 获取授权状态", ar);
        Resp r = agent.req("GET", ar + "/api/oauth/state?mode=login", null, null);
        JSONObject j = json(r);
        if (r.code != 200 || !j.optBoolean("success") || j.optString("data").isEmpty())
            throw new IOException("获取登录状态失败：" + message(j));
        expectedState = j.optString("data");
        return expectedState;
    }

    /** Same API cookie jar from state to callback; no GitHub cookies enter this function. */
    public R finishAgentRouter(String code, String state) {
        R result = new R();
        if (code == null || code.isEmpty() || expectedState.isEmpty() || !expectedState.equals(state)) {
            result.msg = "OAuth state 不匹配，请重新登录";
            return result;
        }
        expectedState = ""; // one callback per authorization
        try {
            stage("AgentRouter · 授权回调确认领取", ar);
            Resp ex = agent.req("GET", ar + "/api/oauth/github?code=" + enc(code) + "&state=" + enc(state) + "&mode=login", null, null);
            JSONObject j = json(ex);
            JSONObject data = j.optJSONObject("data");
            if (ex.code != 200 || !j.optBoolean("success") || data == null || data.optLong("id") <= 0)
                throw new IOException("登录失败：" + message(j));
            result.data = data;
            result.loggedIn = true;
            result.ok = data.optBoolean("checked_in");
            result.msg = result.ok ? "站点确认签到，额度已到账" : "登录成功，但站点未确认今日领取";
            try {
                Map<String, String> h = Collections.singletonMap("New-Api-User", String.valueOf(data.optLong("id")));
                stage("AgentRouter · 领取结果已返回，查询余额", ar);
                Resp self = agent.optional(ar + "/api/user/self", h);
                JSONObject sj = json(self);
                JSONObject balance = sj.optJSONObject("data");
                if (self.code == 200 && sj.optBoolean("success") && balance != null && !confirmedBalance(balance,"quota").isEmpty()) {
                    double unit = 500000;
                    try {
                        Resp status = agent.optional(ar + "/api/status", null);
                        JSONObject statusData = json(status).optJSONObject("data");
                        if (status.code == 200 && statusData != null && statusData.optDouble("quota_per_unit") > 0)
                            unit = statusData.optDouble("quota_per_unit");
                    } catch (IOException ignored) { }
                    result.balance = String.format(Locale.US,"%.2f",balance.optDouble("quota") / unit);
                    result.msg += "，余额 $" + result.balance;
                }
            } catch (IOException ignored) { result.msg += "（余额暂未查到）"; }
        } catch (Exception e) { result.msg = failureMessage(e); }
        return result;
    }
}
