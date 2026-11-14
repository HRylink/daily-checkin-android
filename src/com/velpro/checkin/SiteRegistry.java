package com.velpro.checkin;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

/** Static directory of check-in sites. Pure Java: adding a site changes this file, not the run loop. */
public final class SiteRegistry {
    public static final String TRAE_GLOBAL_HINT = "Trae 国际版无每日签到活动";
    public static final String UNSUPPORTED = "未识别到该站点，可添加该网站并填写领取接口";
    private static final String CN_ENDPOINT = "https://copilot.tencent.com";

    /** Result of the read-only status call made immediately after a browser login. */
    public static final class Verify {
        public boolean ok, invalid, network;
        public String message = "";
        private Verify(boolean ok, boolean invalid, boolean network, String message) {
            this.ok = ok; this.invalid = invalid; this.network = network;
            this.message = message == null ? "" : message;
        }
        public static Verify pass() { return new Verify(true, false, false, ""); }
        public static Verify invalid(String message) { return new Verify(false, true, false, message); }
        public static Verify network(String message) { return new Verify(false, false, true, message); }
    }

    public static final class Detection {
        public final List<Site> sites = new ArrayList<>();
        public String hint = "", unsupported = "";
    }

    /** Non-sensitive custom claim configuration; Cookie never belongs in this JSON. */
    public static final class Custom {
        public String name = "", url = "", method = "GET", body = "", headers = "";
        public JSONObject toJson() throws Exception {
            return new JSONObject().put("name", name).put("url", url).put("method", method)
                    .put("body", body).put("headers", headers);
        }
        public static Custom fromJson(JSONObject o) {
            Custom c = new Custom();
            c.name = o.optString("name", ""); c.url = o.optString("url", "");
            c.method = o.optString("method", "GET"); c.body = o.optString("body", "");
            c.headers = o.optString("headers", ""); return c;
        }
    }

    public static final class Site {
        public final String id, name, historyName, kind, loginHint, loginActivity;
        public final List<String> domains;
        public final WorkBuddy.Site wbSite;
        Site(String id, String name, String historyName, String kind, String loginHint,
             String loginActivity, WorkBuddy.Site wbSite, String... domains) {
            this.id = id; this.name = name; this.historyName = historyName; this.kind = kind;
            this.loginHint = loginHint; this.loginActivity = loginActivity; this.wbSite = wbSite;
            this.domains = Collections.unmodifiableList(Arrays.asList(domains));
        }
    }

    private static final List<Site> ALL = new ArrayList<>();
    private static final Map<String, Site> BY_ID = new LinkedHashMap<>();

    private static void add(String id, String name, String historyName, String kind,
                            String loginHint, String loginActivity, WorkBuddy.Site wbSite, String... domains) {
        Site s = new Site(id, name, historyName, kind, loginHint, loginActivity, wbSite, domains);
        ALL.add(s); BY_ID.put(s.id, s);
    }

    static {
        add("77625", "77625", "77625", "legacy25", "在上方卡片填写 77625 账号和密码",
                "SettingsActivity", null, "ly.77625.xyz", "77625.xyz");
        add("agentrouter", "AgentRouter", "AgentRouter", "agentrouter", "使用 GitHub 登录态授权 AgentRouter",
                "OAuthActivity", null, "agentrouter.org", "www.agentrouter.org");
        add("workbuddy-ai", "WorkBuddy 国际版", "WorkBuddy", "workbuddy", "在浏览器中登录 WorkBuddy 国际版",
                "WbLoginActivity", WorkBuddy.AI, "workbuddy.ai", "www.workbuddy.ai");
        add("workbuddy-cn", "WorkBuddy 国内版", "WorkBuddy 国内版", "workbuddy", "在浏览器中登录 WorkBuddy 国内版",
                "WbLoginActivity", WorkBuddy.CN.withEndpoint(CN_ENDPOINT), "copilot.tencent.com");
        add("trae-cn", "Trae", "Trae", "trae", "在浏览器中授权 Trae CN",
                "TraeLoginActivity", null, "trae.cn", "www.trae.cn", "api.trae.cn");
    }

    public static List<Site> all() { return Collections.unmodifiableList(ALL); }
    public static Site byId(String id) { return id == null ? null : BY_ID.get(id); }
    public static Site customSite(String id, Custom c) {
        if (c == null || !CustomSite.validate(c).isEmpty()) return null;
        return new Site(id, c.name, c.name, "custom", "添加时会立即领取一次以验证凭据",
                "CustomLoginActivity", null, URI.create(c.url).getHost());
    }
    public static List<Site> customSites(AccountStore.KV kv) {
        List<Site> out = new ArrayList<>();
        for (Map.Entry<String,Custom> item : AccountStore.customConfigs(kv).entrySet())
            out.add(customSite(item.getKey(), item.getValue()));
        return out;
    }
    public static List<Site> all(AccountStore.KV kv) {
        List<Site> out = new ArrayList<>(ALL); out.addAll(customSites(kv)); return out;
    }

    public static Detection detect(String raw) {
        Detection d = new Detection();
        String host = hostOf(raw);
        if (host.isEmpty()) { d.unsupported = UNSUPPORTED; return d; }
        if (host.equals("trae.ai") || host.endsWith(".trae.ai")) {
            d.hint = TRAE_GLOBAL_HINT;
            return d;
        }
        for (Site s : ALL) {
            for (String domain : s.domains) {
                if (host.equals(domain) || host.endsWith("." + domain)) { d.sites.add(s); break; }
            }
        }
        if (d.sites.isEmpty()) d.unsupported = UNSUPPORTED;
        else if (d.sites.size() == 1) d.hint = "识别为：" + d.sites.get(0).name + " → 开始登录";
        else d.hint = "匹配多个站点，请选择要添加的账号";
        return d;
    }

    private static String hostOf(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (!s.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) s = "https://" + s;
        try {
            URI u = new URI(s);
            String host = u.getHost();
            if (host == null) {
                String authority = u.getAuthority();
                if (authority != null) {
                    int at = authority.lastIndexOf('@');
                    if (at >= 0) authority = authority.substring(at + 1);
                    int colon = authority.lastIndexOf(':');
                    if (colon >= 0) authority = authority.substring(0, colon);
                    host = authority;
                }
            }
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            return host;
        } catch (Exception e) { return ""; }
    }

    /** One uniform Check per site; the coordinator only walks a Plan of these. */
    public static RunCoordinator.Check check(RunCoordinator.Entry e, String user, String password,
                                             String githubCookies, String ua) {
        final RunCoordinator.Entry entry = e;
        final Site site = entry.site != null ? entry.site : entry.custom != null
                ? customSite(entry.siteId, entry.custom) : byId(entry.siteId);
        return (net, sink) -> {
            String label = entry.historyName == null || entry.historyName.isEmpty()
                    ? (site == null ? entry.siteId : site.historyName)
                    : entry.historyName;
            if (site == null) {
                RunCoordinator.emit(sink, label, false, false, "未知站点，请删除后重新添加");
                return;
            }
            if ("custom".equals(site.kind)) {
                if (entry.custom != null && "POST".equals(entry.custom.method) && entry.customSaver != null) {
                    if (entry.customSaver.blocked()) { RunCoordinator.emit(sink, label, false, false, CustomSite.UNCONFIRMED); return; }
                    if (!entry.customSaver.beforePost()) {
                        RunCoordinator.emit(sink, label, false, false, "无法保存 POST 防重发状态，本次未发送"); return;
                    }
                }
                sink.status(label + "：正在调用领取接口…");
                CustomSite.Result r = new CustomSite().check(entry.custom, entry.customCookie);
                if (entry.customSaver != null) entry.customSaver.after(r);
                RunCoordinator.emit(sink, label, r.ok, r.needsAuth, r.message, r.text);
                return;
            }
            if ("legacy25".equals(site.kind)) {
                sink.status("77625：正在直连登录/签到…");
                sink.result(label, net.check77625(user, password));
                return;
            }
            if ("agentrouter".equals(site.kind)) {
                for (int attempt = 1; attempt <= 2; attempt++) {
                    sink.status("AgentRouter：静默退出并重登，确认领取（第" + attempt + "次）…");
                    Net.R r = net.checkAgentRouter(githubCookies, ua);
                    sink.result(label, r);
                    if (r.ok || !r.loggedIn || r.needsAuth) break;
                }
                return;
            }
            if ("workbuddy".equals(site.kind)) {
                WorkBuddy.Auth auth = entry.wb;
                if (auth == null || auth.access == null || auth.access.isEmpty()) {
                    RunCoordinator.emit(sink, label, false, true, "凭据缺失，请到设置页重新登录该账号");
                    return;
                }
                try {
                    WorkBuddy wb = new WorkBuddy(site.wbSite, 12000, 20000, 1000, 600);
                    if (!auth.refresh.isEmpty() && (auth.access.isEmpty() || RunCoordinator.expired(auth.expires)))
                        auth = wb.refresh(auth, entry.wbSaver);
                    sink.status(label + "：正在检查今日签到…");
                    WorkBuddy.R r = wb.checkIn(auth);
                    String balance="";
                    if (r.ok()) {
                        try { balance=Balances.workBuddySummary(wb.resourceSummary(auth)); }
                        catch (Exception ignored) { /* successful claim remains successful; keep previous balance */ }
                    }
                    RunCoordinator.emit(sink, label, r.ok(), r.needsAuth,
                            RunCoordinator.message(r.outcome, r.message, r.credit, r.streak, r.needsAuth),balance);
                } catch (Exception ex) {
                    RunCoordinator.emit(sink, label, false, true, "登录已失效，请到设置页重新登录该账号");
                }
                return;
            }
            if ("trae".equals(site.kind)) {
                Trae.Auth auth = entry.trae;
                if (auth == null || auth.token == null || auth.token.isEmpty()) {
                    RunCoordinator.emit(sink, label, false, true, "凭据缺失，请到设置页重新登录该账号");
                    return;
                }
                try {
                    Trae trae = new Trae();
                    if (!auth.refresh.isEmpty() && (auth.token.isEmpty() || RunCoordinator.expired(auth.expires)))
                        auth = trae.refresh(auth, entry.traeSaver);
                    sink.status(label + "：正在检查今日签到…");
                    Trae.R r = trae.checkIn(auth);
                    RunCoordinator.emit(sink, label, r.ok(), r.needsAuth,
                            RunCoordinator.message(r.outcome, r.message, r.credit, 0, r.needsAuth),r.balance);
                } catch (Exception ex) {
                    RunCoordinator.emit(sink, label, false, true, "登录已失效，请到设置页重新登录该账号");
                }
                return;
            }
            RunCoordinator.emit(sink, label, false, false, "该站点未配置签到方式，请到设置页添加网站并填写领取接口");
        };
    }
}
