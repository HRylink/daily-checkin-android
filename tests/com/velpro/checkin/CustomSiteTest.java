package com.velpro.checkin;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;

/** Local HTTP fixtures behind an injected opener; production URLs remain HTTPS-only. */
public class CustomSiteTest {
    static void require(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    static SiteRegistry.Custom config(String path, String method) {
        SiteRegistry.Custom c = new SiteRegistry.Custom();
        c.name = "测试站点"; c.url = "https://fixture.test" + path; c.method = method;
        return c;
    }
    static class Mem implements AccountStore.KV {
        final Map<String,String> values = new HashMap<>();
        public String get(String key) { return values.getOrDefault(key, ""); }
        public boolean put(String key, String value) { values.put(key, value); return true; }
    }
    public static void main(String[] args) throws Exception {
        int groups = 0;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger(), retries = new AtomicInteger(), posts = new AtomicInteger(), noResponses = new AtomicInteger();
        List<String> seen = new ArrayList<>();
        server.createContext("/", x -> {
            String path = x.getRequestURI().getPath();
            x.getResponseHeaders().set("Connection", "close"); // truncated fixtures must not return a broken pooled socket
            requests.incrementAndGet();
            if (path.equals("/post")) {
                seen.add(x.getRequestMethod());
                seen.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                seen.add(x.getRequestHeaders().getFirst("Cookie"));
                seen.add(x.getRequestHeaders().getFirst("X-Requested-With"));
            }
            if (path.equals("/timeout")) {
                posts.incrementAndGet(); x.getRequestBody().readAllBytes();
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (path.equals("/no-response")) {
                noResponses.incrementAndGet(); x.getRequestBody().readAllBytes(); x.close(); return;
            }
            if (path.equals("/drop-post") || path.equals("/retry") || path.equals("/always-drop")) {
                int n = path.equals("/drop-post") ? posts.incrementAndGet() : retries.incrementAndGet();
                x.getRequestBody().readAllBytes();
                if (!path.equals("/retry") || n == 1) {
                    x.sendResponseHeaders(200, 30); x.getResponseBody().write('x');
                    x.getResponseBody().flush(); x.close(); return;
                }
            }
            if (path.equals("/redirect") || path.equals("/post-redirect") || path.equals("/unsafe") || path.equals("/foreign")) {
                String target = path.equals("/unsafe") ? "http://fixture.test/ok"
                        : path.equals("/foreign") ? "https://other.test/ok" : "/ok";
                x.getResponseHeaders().set("Location", target);
                if (path.equals("/post-redirect")) {
                    byte[] reason = "请向新地址提交".getBytes(StandardCharsets.UTF_8);
                    x.sendResponseHeaders(307, reason.length); x.getResponseBody().write(reason);
                } else x.sendResponseHeaders(302, -1);
                x.close(); return;
            }
            int code = path.equals("/401") ? 401 : path.equals("/403") ? 403
                    : path.equals("/404") ? 404 : path.equals("/400") ? 400
                    : path.equals("/429") ? 429 : path.equals("/503") ? 503 : 200;
            String text = code >= 400 ? "{\"msg\":\"账号不存在\"}" : path.equals("/long")
                    ? String.join("", Collections.nCopies(250, "额")) : "今日领取 42 额度";
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(code, bytes.length); x.getResponseBody().write(bytes); x.close();
        });
        server.start();
        AtomicInteger opened = new AtomicInteger();
        CustomSite.Opener opener = url -> {
            opened.incrementAndGet();
            require(url.getProtocol().equals("https"), "opener must receive HTTPS only");
            return (HttpURLConnection) new URL("http://127.0.0.1:" + server.getAddress().getPort()
                    + url.getFile()).openConnection();
        };
        CustomSite client = new CustomSite(500, 500, 1, opener);
        try {
            CustomSite.Result ok = client.check(config("/ok", "GET"), "session=abc");
            require(ok.ok && ok.message.contains("领取成功") && ok.text.equals("今日领取 42 额度")
                    && ok.message.contains(ok.text), "GET 200 must expose original response"); groups++;
            SiteRegistry.Custom post = config("/post", "POST");
            post.body = "{\"a\":1}\n  原样"; post.headers = "X-Requested-With: XMLHttpRequest\nContent-Type: application/json";
            require(client.check(post, "a=1; b=x=y").ok, "POST must succeed");
            require(seen.equals(Arrays.asList("POST", post.body, "a=1; b=x=y", "XMLHttpRequest")),
                    "POST body/cookies/extra headers must be exact: " + seen); groups++;
            for (int code : new int[]{401, 403}) {
                CustomSite.Result r = client.check(config("/" + code, "GET"), "a=1");
                require(!r.ok && r.invalid && r.needsAuth && r.message.contains("重新登录"), "auth invalid " + code);
            } groups++;
            for (int code : new int[]{400, 404, 429, 503}) {
                int before = requests.get(); CustomSite.Result r = client.check(config("/" + code, "GET"), "a=1");
                require(!r.ok && !r.invalid && r.message.contains("账号不存在") && requests.get() == before + 1,
                        "HTTP failure must show raw response without retry " + code);
            } groups++;
            require(client.check(config("/long", "GET"), "a=1").text.codePointCount(0, 200) == 200,
                    "site text must truncate to 200 characters"); groups++;
            require(!client.check(config("/drop-post", "POST"), "a=1").ok && posts.get() == 1,
                    "unconfirmed POST must not be replayed");
            posts.set(0); CustomSite.Result unconfirmed = client.check(config("/drop-post", "POST"), "a=1");
            require(unconfirmed.message.contains("请求已发出但结果未确认") && unconfirmed.message.contains("当天不再重试")
                    && !unconfirmed.invalid && posts.get() == 1, "POST uncertainty semantics"); groups++;
            retries.set(0); require(client.check(config("/retry", "GET"), "a=1").ok && retries.get() == 2,
                    "GET must retry exactly once");
            retries.set(0); CustomSite.Result exhausted = client.check(config("/always-drop", "GET"), "a=1");
            require(!exhausted.ok && retries.get() == 2,
                    "GET must stop after two failures: count=" + retries.get() + ", ok=" + exhausted.ok + ", msg=" + exhausted.message); groups++;
            noResponses.set(0); require(!client.check(config("/no-response", "POST"), "a=1").ok && noResponses.get() == 1,
                    "POST without any HTTP response must not replay");
            noResponses.set(0); opened.set(0); CustomSite.Result noReply = client.check(config("/no-response", "GET"), "a=1");
            // JDK may transparently resend an empty-response GET inside a single connection.
            // Count application attempts here; partial-response fixture above counts exact wire requests.
            require(!noReply.ok && opened.get() == 2, "GET no response must have only two application attempts"); groups++;
            int before = requests.get(); require(client.check(config("/redirect", "GET"), "a=1").ok
                    && requests.get() == before + 2, "safe GET redirect");
            before = requests.get(); CustomSite.Result movedPost = client.check(config("/post-redirect", "POST"), "a=1");
            require(!movedPost.ok && movedPost.unconfirmed && requests.get() == before + 1
                    && movedPost.text.equals("请向新地址提交") && movedPost.message.contains(movedPost.text),
                    "307 must preserve original response without replaying a POST");
            for (String path : new String[]{"/unsafe", "/foreign"}) {
                before = requests.get(); CustomSite.Result unsafe = client.check(config(path, "GET"), "a=1");
                require(!unsafe.ok && unsafe.message.contains("已阻止") && requests.get() == before + 1,
                        "redirect must report blocking without downgrading HTTPS or leaking cookie to other origin");
            } groups++;
            require(CustomSite.validUrl("https://example.com/api/claim?a=1") && !CustomSite.validUrl("http://example.com")
                    && !CustomSite.validUrl("https:///claim") && !CustomSite.validUrl("https://u:p@example.com")
                    && CustomSite.home("https://example.com:8443/api").equals("https://example.com:8443/"), "URL validation");
            SiteRegistry.Custom bad = config("/ok", "DELETE"); before = requests.get();
            require(!client.check(bad, "a=1").ok && requests.get() == before, "only GET/POST allowed");
            bad = config("/ok", "GET"); bad.headers = "Cookie: evil=1";
            require(!client.check(bad, "a=1").ok && requests.get() == before, "headers cannot replace protected cookie"); groups++;
            for (String header : new String[]{"Cookie: evil=1", "Authorization: Bearer secret", "Host: other.test", "Bad header: a", "X-Test: a\rb"}) {
                bad = config("/ok", "GET"); bad.headers = header;
                require(!client.check(bad, "a=1").ok && requests.get() == before, "reject sensitive/control headers: " + header);
            }
            require(CustomSite.validUrl("https://[::1]:443/api") && !CustomSite.validUrl("https://example.com:0/api")
                    && !CustomSite.validUrl("https://example.com:65536/api") && !CustomSite.validUrl("https://example.com/a#frag"), "URL edge cases");
            Map<String,String> cookies = CustomSite.splitCookies(" a=1; b=x=y; empty= ");
            require(CustomSite.joinCookies(cookies).equals("a=1; b=x=y; empty="), "cookie round trip preserves equals"); groups++;

            posts.set(0); CustomSite.Result timedOut = new CustomSite(500, 40, 1, opener).check(config("/timeout", "POST"), "a=1");
            require(timedOut.unconfirmed && !timedOut.invalid && posts.get() == 1 && timedOut.message.equals(CustomSite.UNCONFIRMED),
                    "POST read timeout must not replay"); groups++;
            Mem kv = new Mem(); SiteRegistry.Custom c = config("/ok", "POST");
            require(AccountStore.saveCustom(kv, "custom_0", c, "secret=one"), "save encrypted-path credentials");
            require(kv.get("accounts").isEmpty(), "saving credentials is not verification");
            AccountStore.markInvalid(kv, "custom_0", "网络异常");
            List<AccountStore.Account> pending = AccountStore.load(kv, false);
            require(pending.size() == 1 && !pending.get(0).listed && pending.get(0).invalid
                    && pending.get(0).customCookie.equals("secret=one"), "failed verification retained, unlisted");
            require(AccountStore.addCustom(kv, "custom_0", c, "secret=one"), "verified add");
            require(AccountStore.addCustom(kv, "custom_1", config("/post", "GET"), "secret=two"), "second custom account");
            require(new JSONArray(kv.get("custom_sites")).length() == 2 && new JSONArray(kv.get("accounts")).length() == 2,
                    "configs and account ids stored separately");
            require(!kv.get("custom_sites").contains("secret=") && AccountStore.isSecret(AccountStore.customCookieKey("custom_0"))
                    && AccountStore.customCookieKey("custom_0").equals("custom_custom_0_cookie")
                    && !AccountStore.isSecret("custom_sites"), "cookie secret key classification");
            List<AccountStore.Account> accounts = AccountStore.load(kv, false);
            require(accounts.size() == 2 && accounts.get(0).enabled && !accounts.get(0).invalid
                    && accounts.get(0).custom.url.equals(c.url), "config and decrypted cookies load");
            RunCoordinator.Plan plan = RunCoordinator.Plan.from(accounts);
            require(plan.entries.size() == 2 && plan.entries.get(0).site.kind.equals("custom")
                    && plan.entries.get(0).customCookie.equals("secret=one") && plan.entries.get(0).customSaver != null,
                    "custom accounts, cookies and durable guard must survive Plan.from");
            AccountStore.setEnabled(kv, "custom_0", false);
            require(AccountStore.beginCustomPost(kv, "custom_0"), "persist POST attempt before sending");
            require(AccountStore.customBlocked(kv, "custom_0") && !AccountStore.beginCustomPost(kv, "custom_0"), "no second POST today");
            final List<Net.R> results = new ArrayList<>();
            RunCoordinator.Listener listener = new RunCoordinator.Listener() {
                public void status(String message) { }
                public void result(String site, Net.R r) { results.add(r); }
                public void finished() { }
            };
            SiteRegistry.check(plan.entries.get(0), "", "", "", "").check(new Net(), listener);
            require(results.size() == 1 && results.get(0).msg.equals(CustomSite.UNCONFIRMED), "registry must short-circuit today's uncertain POST");
            CustomSite.Result uncertain = new CustomSite.Result(); uncertain.unconfirmed = true;
            AccountStore.finishCustom(kv, "custom_0", uncertain);
            require(AccountStore.customBlocked(kv, "custom_0"), "unknown result survives reload");
            CustomSite.Result confirmed = new CustomSite.Result(); confirmed.ok = true;
            AccountStore.finishCustom(kv, "custom_0", confirmed);
            require(!AccountStore.customBlocked(kv, "custom_0"), "confirmed response clears uncertainty marker");
            kv.put("custom_0_post_day", "2000-01-01");
            require(!AccountStore.customBlocked(kv, "custom_0"), "next day permits a manual attempt");
            confirmed.ok = false; confirmed.needsAuth = confirmed.invalid = true; confirmed.message = "HTTP 401";
            AccountStore.finishCustom(kv, "custom_0", confirmed);
            require(AccountStore.load(kv, false).get(0).invalid, "daily auth failure marks account invalid"); groups++;
            AccountStore.remove(kv, "custom_0");
            require(AccountStore.load(kv, false).size() == 1 && kv.get("custom_custom_0_cookie").isEmpty()
                    && new JSONArray(kv.get("custom_sites")).length() == 1 && kv.get("custom_0_post_day").isEmpty(),
                    "delete removes config/cookie/guard only for selected id"); groups++;
            Mem failed = new Mem() { @Override public boolean put(String key, String value) {
                if (AccountStore.isSecret(key)) return false; return super.put(key, value);
            }};
            require(!AccountStore.addCustom(failed, "custom_0", c, "secret") && failed.get("accounts").isEmpty(),
                    "failed secret write must not promote account"); groups++;
            System.out.println("PASS CustomSiteTest: " + groups + " groups (HTTP fixtures, retry/no replay, HTTPS, cookie secret keys, custom CRUD/plan)");
        } finally { server.stop(0); }
    }
}
