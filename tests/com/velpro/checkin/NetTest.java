package com.velpro.checkin;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.*;
import java.util.*;

public class NetTest {
    static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static Object client(String base) {
        try {
            return Net.class.getDeclaredConstructor(String.class, String.class).newInstance(base, base);
        } catch (Exception e) {
            throw new AssertionError("missing isolated Net(String ar, String s25) client", e);
        }
    }
    static Object call(Object target, String name, String... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        Arrays.fill(types, String.class);
        return target.getClass().getMethod(name, types).invoke(target, (Object[]) args);
    }
    static class Fixture implements AutoCloseable {
        final HttpServer server;
        final String base;
        final List<String> seen = new ArrayList<>();
        int loginCount, states, exchanges, logouts;
        boolean checked = true, html, limited;
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/", this::serve);
            server.start();
        }
        void serve(HttpExchange x) throws java.io.IOException {
            String path = x.getRequestURI().getPath();
            String method = x.getRequestMethod();
            String cookie = Optional.ofNullable(x.getRequestHeaders().getFirst("Cookie")).orElse("");
            seen.add(method + " " + path + " Cookie=" + cookie);
            int status = 200;
            String body = "{}";
            if (path.equals("/api/auth/login")) {
                loginCount++;
                require(!cookie.contains("s25="), "new 77625 run leaked old cookie");
                x.getResponseHeaders().add("Set-Cookie", "s25=auth; Path=/; HttpOnly");
                body = "{\"success\":true}";
            } else if (path.equals("/api/auth/me")) {
                if (!cookie.contains("s25=auth")) status = 401;
                else body = "{\"checked_in_today\":false,\"credits\":123}";
            } else if (path.equals("/api/account/checkin")) {
                if (!cookie.contains("s25=auth")) { status = 401; body = "{\"detail\":\"not logged in\"}"; }
                else body = html ? "<html>challenge</html>" : "{\"points\":5000}";
            } else if (path.equals("/api/user/logout")) {
                logouts++;
                require(method.equals("GET"), "logout must use GET not POST");
                x.getResponseHeaders().add("Set-Cookie", "session=; Max-Age=0; Path=/");
                body = "{\"success\":true}";
            } else if (path.equals("/api/oauth/state")) {
                states++;
                require(!cookie.contains("s25="), "77625 cookie leaked to AgentRouter");
                require(!cookie.contains("session="), "state acquired using old AgentRouter session");
                if (limited) { status = 429; body = ""; }
                else {
                    x.getResponseHeaders().add("Set-Cookie", "session=state" + states + "; Path=/; HttpOnly");
                    body = "{\"success\":true,\"data\":\"state" + states + "\"}";
                }
            } else if (path.equals("/api/oauth/github")) {
                exchanges++;
                require(cookie.contains("session=state" + states), "OAuth state cookie lost before code exchange");
                body = "{\"success\":true,\"data\":{\"id\":1,\"quota\":0,\"checked_in\":" + checked + "}}";
                x.getResponseHeaders().add("Set-Cookie", "session=loggedin; Path=/; HttpOnly");
            } else if (path.equals("/api/user/self")) {
                require(cookie.contains("session=loggedin"), "self missing fresh session cookie");
                require("1".equals(x.getRequestHeaders().getFirst("New-Api-User")), "self missing user id header");
                body = "{\"success\":true,\"data\":{\"quota\":1500000}}";
            } else if (path.equals("/api/status")) {
                body = "{\"success\":true,\"data\":{\"quota_per_unit\":500000}}";
            } else status = 404;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status, bytes.length);
            x.getResponseBody().write(bytes);
            x.close();
        }
        public void close() { server.stop(0); }
    }
    public static void main(String[] args) throws Exception {
        int passed = 0;
        try (Fixture f = new Fixture()) {
            Object n = client(f.base);
            Net.R r = (Net.R) call(n, "check77625", "user", "password");
            require(r.ok && r.msg.contains("5000"), "77625 authenticated signup failed: " + r.msg);
            call(n, "check77625", "user", "password");
            require(f.loginCount == 2, "77625 must authenticate every run");
            passed++;
            String state = (String) call(n, "beginAgentRouter");
            r = (Net.R) call(n, "finishAgentRouter", "code", state);
            require(r.ok && r.msg.contains("$3.00") && r.msg.contains("到账"), "confirmed quota not shown: " + r.msg);
            require(f.logouts == 1, "no GET logout at start");
            passed++;
            state = (String) call(n, "beginAgentRouter");
            require(f.logouts == 2, "second login did not log out");
            require(f.seen.stream().anyMatch(s -> s.startsWith("GET /api/user/logout Cookie=session=loggedin")), "logout did not send actual previous session");
            r = (Net.R) call(n, "finishAgentRouter", "code", "wrong-state");
            require(!r.ok && f.exchanges == 1, "invalid OAuth state must not be exchanged");
            passed++;
            f.checked = false;
            r = (Net.R) call(n, "finishAgentRouter", "code", state);
            require(!r.ok && !r.msg.contains("额度已到账") && !r.msg.contains("今天已签到"), "unconfirmed login falsely claimed reward: " + r.msg);
            passed++;
            f.html = true;
            r = (Net.R) call(n, "check77625", "user", "password");
            require(!r.ok, "HTML 200 response must not be claimed as signup success");
            passed++;
            f.limited = true;
            try { call(n, "beginAgentRouter"); throw new AssertionError("429 must fail"); }
            catch (InvocationTargetException e) { require(e.getCause().getMessage().contains("429"), "missing 429 detail"); }
            passed++;
        }
        Method cb;
        try { cb = Net.class.getMethod("isCallback", String.class); }
        catch (NoSuchMethodException e) { throw new AssertionError("missing strict callback URL validation", e); }
        require((Boolean) cb.invoke(null, "https://agentrouter.org/oauth/github?code=x&state=y"), "valid callback rejected");
        for (String bad : new String[]{"http://agentrouter.org/oauth/github", "https://evilagentrouter.org/oauth/github", "https://agentrouter.org.evil.test/oauth/github", "https://agentrouter.org/oauth/github/evil"})
            require(!(Boolean) cb.invoke(null, bad), "unsafe callback accepted: " + bad);
        passed++;
        System.out.println("PASS NetTest: " + passed + " test groups (real local HTTP fixtures)");
    }
}
