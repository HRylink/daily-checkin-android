package com.velpro.checkin;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;

public class OAuthTest {
    static void check(boolean b, String m) { if (!b) throw new AssertionError(m); }
    static class GitHub implements AutoCloseable {
        final HttpServer server;
        final String base;
        String mode = "ok";
        String error = "";
        int requests;
        GitHub() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/", this::serve); server.start();
        }
        void serve(HttpExchange x) throws java.io.IOException {
            requests++;
            String cookie = Objects.toString(x.getRequestHeaders().getFirst("Cookie"), "");
            if (!cookie.contains("user_session=mobile") || cookie.contains("session=state") || cookie.contains("session=loggedin")) error = "wrong GitHub cookie boundary: " + cookie;
            if (!"mobile-UA".equals(x.getRequestHeaders().getFirst("User-Agent"))) error = "WebView UA not preserved";
            String state = "";
            for (String pair : Objects.toString(x.getRequestURI().getRawQuery(), "").split("&")) {
                if (pair.startsWith("state=")) state = URLDecoder.decode(pair.substring(6), "UTF-8");
            }
            int status = 302;
            String body = "";
            String location = "https://agentrouter.org/oauth/github?code=code&state=" + state;
            if (mode.equals("expired")) location = base + "/login";
            if (mode.equals("consent")) { status = 200; body = "<html>Authorize application</html>"; }
            if (mode.equals("wrong")) location = "https://agentrouter.org/oauth/github?code=code&state=wrong";
            if (mode.equals("evil")) location = "https://evilagentrouter.org/oauth/github?code=code&state=" + state;
            if (mode.equals("limit")) { status = 429; body = ""; }
            if (mode.equals("rotate")) x.getResponseHeaders().add("Set-Cookie", "user_session=newmobile; Path=/; HttpOnly; Secure");
            x.getResponseHeaders().add("Location", location);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) x.getResponseBody().write(bytes);
            x.close();
        }
        public void close() { server.stop(0); }
    }
    static Net create(String api, String github) {
        try { return Net.class.getDeclaredConstructor(String.class,String.class,String.class).newInstance(api,api,github); }
        catch (Exception e) { throw new AssertionError("missing silent OAuth Net(ar, s25, github) client",e); }
    }
    static Net.R run(Net n, String cookies) throws Exception {
        try { return (Net.R) Net.class.getMethod("checkAgentRouter", String.class,String.class).invoke(n,cookies,"mobile-UA"); }
        catch (NoSuchMethodException e) { throw new AssertionError("missing silent checkAgentRouter",e); }
    }
    static boolean needsAuth(Net.R r) throws Exception { return Net.R.class.getField("needsAuth").getBoolean(r); }
    public static void main(String[] args) throws Exception {
        int passed = 0;
        try (NetTest.Fixture api = new NetTest.Fixture(); GitHub gh = new GitHub()) {
            Net n = create(api.base, gh.base);
            Net.R r = run(n,"user_session=mobile; dotcom_user=fixture");
            check(r.ok && r.loggedIn && !needsAuth(r), "valid saved GitHub session failed: " + r.msg);
            check(gh.error.isEmpty(), gh.error);
            check(api.seen.stream().noneMatch(v -> v.contains("user_session")), "GitHub credential leaked to API");
            String previous = (String) Net.class.getMethod("agentCookie").invoke(n);
            check(previous.contains("session=loggedin"), "missing AR session export");
            Net n2 = create(api.base, gh.base);
            Net.class.getMethod("restoreAgentCookie",String.class).invoke(n2, previous);
            r = run(n2,"user_session=mobile");
            check(r.ok, "restored client failed");
            check(api.seen.stream().anyMatch(v -> v.startsWith("GET /api/user/logout Cookie=session=loggedin")), "real previous API session not logged out");
            passed++;
            for (String mode : new String[]{"expired", "consent", "wrong", "evil", "limit"}) {
                gh.mode = mode;
                int ex = api.exchanges, hits = gh.requests;
                r = run(n2,"user_session=mobile");
                check(!r.ok && !r.loggedIn && api.exchanges == ex, "unsafe response exchanged: " + mode);
                check(gh.requests == hits + 1, "followed unsafe/login redirect: " + mode);
                if (mode.equals("expired") || mode.equals("consent")) check(needsAuth(r), "no reauthorization signal: " + mode);
                if (mode.equals("limit")) check(!needsAuth(r) && r.msg.contains("429"), "429 should not destroy auth");
                passed++;
            }
            int hits = gh.requests;
            r = run(n2, "");
            check(needsAuth(r) && !r.ok && gh.requests == hits, "missing session must request interactive setup");
            passed++;
            gh.mode = "ok"; api.checked = false;
            r = run(n2,"user_session=mobile");
            check(r.loggedIn && !r.ok && !needsAuth(r) && !r.msg.contains("到账"), "unconfirmed login falsely reported reward");
            passed++;
            gh.mode = "rotate"; api.checked = true;
            r = run(n2,"user_session=mobile");
            Object updates = Net.R.class.getField("githubUpdates").get(r);
            check(((List<?>)updates).stream().anyMatch(v -> v.toString().contains("newmobile")), "rotated cookie not returned for persistence");
            passed++;
        }
        System.out.println("PASS OAuthTest: " + passed + " groups (silent login, credential boundaries, persisted logout, auth challenges)");
    }
}
