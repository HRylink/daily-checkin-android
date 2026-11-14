package com.velpro.checkin;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local HTTP fixtures for both WorkBuddy editions; no credentials or external network. */
public class WorkBuddyTest {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static String read(HttpExchange x) throws IOException {
        try (InputStream in = x.getRequestBody(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }
    static void send(HttpExchange x, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b); x.close();
    }
    static class Fixture implements AutoCloseable {
        final HttpServer server; final String base; final WorkBuddy.Site site;
        final String expectedPlatform, expectedDomain, expectedApp;
        final List<String> seen = new ArrayList<>();
        int tokenPolls, accountPolls, stateCalls, refreshCalls, statusCalls, claimCalls;
        int statusTransient, statusTransientCode;
        boolean active = true, claimed;
        String statusCode = "", claimCode = "", claimHttp = "200";
        volatile boolean accountDelay = true;
        Fixture(WorkBuddy.Site template) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            site = template.withEndpoint(base);
            expectedPlatform = template.platform; expectedDomain = template.domain; expectedApp = template.app;
            server.createContext("/", this::serve); server.start();
        }
        void serve(HttpExchange x) throws IOException {
            String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
            String body = read(x);
            seen.add(method + " " + x.getRequestURI() + " " + body);
            require(("WorkBuddy/" + expectedApp).equals(x.getRequestHeaders().getFirst("User-Agent")),
                    "WorkBuddy User-Agent must follow the site app version");
            if (path.equals("/v2/plugin/auth/state")) {
                stateCalls++;
                require(method.equals("POST") && body.equals("{}"), "state request must be POST {}");
                require(x.getRequestURI().getRawQuery().contains("platform=" + expectedPlatform), "state platform missing");
                for (String h : new String[]{"X-No-Authorization", "X-No-User-Id", "X-No-Enterprise-Id", "X-No-Department-Info"})
                    require("true".equals(x.getRequestHeaders().getFirst(h)), "missing " + h);
                send(x, 200, "{\"code\":0,\"data\":{\"state\":\"private-state\",\"authUrl\":\"https://www.workbuddy.ai/login\"}}");
            } else if (path.equals("/v2/plugin/auth/token")) {
                tokenPolls++;
                if (tokenPolls < 2) { send(x, 429, "{\"code\":11217,\"msg\":\"not ready\"}"); return; }
                send(x, 200, "{\"code\":0,\"data\":{\"accessToken\":\"A1\",\"refreshToken\":\"R1\",\"expiresIn\":3600}}");
            } else if (path.equals("/v2/plugin/login/account")) {
                accountPolls++;
                require("Bearer A1".equals(x.getRequestHeaders().getFirst("Authorization")), "account bearer missing");
                require(expectedDomain.equals(x.getRequestHeaders().getFirst("X-Domain")), "account domain missing");
                require("true".equals(x.getRequestHeaders().getFirst("X-No-User-Id")), "account user header missing");
                require("true".equals(x.getRequestHeaders().getFirst("X-No-Enterprise-Id")), "account enterprise header missing");
                if (accountPolls == 1 && accountDelay) { send(x, 200, "{\"code\":12151,\"msg\":\"not ready\",\"data\":{}}"); return; }
                send(x, 200, "{\"code\":0,\"data\":{\"uid\":\"uid-42\",\"nickname\":\"WB 用户\"}}");
            } else if (path.equals("/v2/plugin/auth/token/refresh")) {
                refreshCalls++;
                require("R1".equals(x.getRequestHeaders().getFirst("X-Refresh-Token")), "refresh header missing");
                require("plugin".equals(x.getRequestHeaders().getFirst("X-Auth-Refresh-Source")), "refresh source missing");
                require(expectedDomain.equals(x.getRequestHeaders().getFirst("X-Domain")), "refresh domain missing");
                send(x, 200, "{\"code\":0,\"data\":{\"accessToken\":\"A2\",\"expiresAt\":9876543210000.0}}");
            } else if (path.equals("/v2/billing/meter/checkin-activity-status")) {
                statusCalls++;
                require("Bearer A2".equals(x.getRequestHeaders().getFirst("Authorization")), "meter bearer missing");
                require("uid-42".equals(x.getRequestHeaders().getFirst("X-User-Id")), "meter uid missing");
                require("SaaS".equals(x.getRequestHeaders().getFirst("X-Product")), "meter product missing");
                require("WorkBuddy".equals(x.getRequestHeaders().getFirst("X-IDE-Type")), "meter IDE missing");
                require(expectedDomain.equals(x.getRequestHeaders().getFirst("X-Domain")), "meter domain missing");
                if (statusTransient-- > 0) {
                    if (statusTransientCode != 0) send(x, 200, "{\"code\":" + statusTransientCode + ",\"msg\":\"busy\"}");
                    else send(x, 503, "{\"code\":0,\"msg\":\"busy\"}");
                    return;
                }
                if (!statusCode.isEmpty()) { send(x, 401, "{\"code\":" + statusCode + ",\"msg\":\"业务错误\"}"); return; }
                send(x, 200, "{\"code\":0,\"data\":{\"active\":" + active
                        + ",\"today_checked_in\":" + claimed + ",\"today_credit\":7}}");
            } else if (path.equals("/v2/billing/meter/daily-checkin")) {
                claimCalls++;
                require(expectedDomain.equals(x.getRequestHeaders().getFirst("X-Domain")), "claim domain missing");
                if (!claimHttp.equals("200")) { send(x, Integer.parseInt(claimHttp), "{\"code\":0,\"msg\":\"http error\"}"); return; }
                if (!claimCode.isEmpty()) { send(x, 200, "{\"code\":" + claimCode + ",\"msg\":\"业务错误\"}"); return; }
                claimed = true;
                send(x, 200, "{\"code\":0,\"data\":{\"credit\":12,\"streak_days\":3,\"message\":\"ok\"}}");
            } else send(x, 404, "{\"code\":0,\"msg\":\"unknown\"}");
        }
        public void close() { server.stop(0); }
    }
    static WorkBuddy client(Fixture f) { return new WorkBuddy(f.site, 100, 100, 1, 1); }
    static WorkBuddy.Auth auth() {
        WorkBuddy.Auth a = new WorkBuddy.Auth();
        a.access = "A2"; a.refresh = "R1"; a.uid = "uid-42"; a.user = "WB 用户"; a.expires = 0;
        return a;
    }
    static int scenarios(WorkBuddy.Site site) throws Exception {
        int groups = 0;
        try (Fixture f = new Fixture(site)) {
            WorkBuddy wb = client(f);
            WorkBuddy.Auth a = wb.signIn();
            require(a != null && a.access.equals("A1") && a.refresh.equals("R1") && a.uid.equals("uid-42")
                    && a.user.equals("WB 用户") && a.expires > 0, "state/token/account sign-in failed");
            require(f.stateCalls == 1 && f.tokenPolls == 2 && f.accountPolls == 2, "poll retry/deadline semantics failed");
            require(f.seen.stream().anyMatch(s -> s.contains("/v2/plugin/auth/token?state=private-state")), "token state query missing");
            groups++;
        }
        try (Fixture f = new Fixture(site)) {
            WorkBuddy.Auth next = client(f).refresh(auth());
            require(next.access.equals("A2") && next.refresh.equals("R1") && next.uid.equals("uid-42")
                    && next.expires == 9876543210000L, "merge must preserve absent fields");
            require(f.refreshCalls == 1, "refresh request missing");
            groups++;
        }
        try (Fixture f = new Fixture(site)) {
            f.claimed = true;
            WorkBuddy.R r = client(f).checkIn(auth());
            require(r.outcome.equals("done") && r.credit == 7 && f.claimCalls == 0, "already checked-in status must not claim");
            groups++;
        }
        try (Fixture f = new Fixture(site)) {
            WorkBuddy.R r = client(f).checkIn(auth());
            require(r.outcome.equals("claimed") && r.credit == 12 && r.streak == 3
                    && f.claimCalls == 1 && f.statusCalls == 1, "claim success failed");
            groups++;
        }
        try (Fixture f = new Fixture(site)) {
            f.statusTransient = 2;
            WorkBuddy.R r = client(f).checkIn(auth());
            require(!r.ok() && r.outcome.equals("failed") && f.statusCalls == 2 && f.claimCalls == 0,
                    "transient status retry must be bounded to once without claiming");
            groups++;
        }
        try (Fixture f = new Fixture(site)) {
            f.statusTransient = 2; f.statusTransientCode = 10000;
            WorkBuddy.R r = client(f).checkIn(auth());
            require(r.outcome.equals("failed") && f.statusCalls == 2 && f.claimCalls == 0,
                    "code 10000 status retry must be bounded to once");
            groups++;
        }
        try (Fixture f = new Fixture(site)) {
            f.claimHttp = "503";
            WorkBuddy.R r = client(f).checkIn(auth());
            require(r.outcome.equals("failed") && f.claimCalls == 1 && f.statusCalls == 1,
                    "failed claim must never be replayed");
            groups++;
        }
        for (String[] c : new String[][]{{"1001", "done", "true"}, {"1002", "ineligible", "false"},
                {"1003", "inactive", "false"}}) try (Fixture f = new Fixture(site)) {
            f.claimCode = c[0];
            WorkBuddy.R r = client(f).checkIn(auth());
            require(r.outcome.equals(c[1]) && r.ok() == Boolean.parseBoolean(c[2]) && f.claimCalls == 1,
                    "claim code " + c[0] + " semantics failed: " + r.outcome);
            groups++;
        }
        try (Fixture f = new Fixture(site)) {
            f.claimHttp = "401";
            WorkBuddy.R r = client(f).checkIn(auth());
            require(r.outcome.equals("failed") && r.needsAuth && f.claimCalls == 1 && f.statusCalls == 1,
                    "WB 401 must ask for reauthorization, not retry claim");
            groups++;
        }
        try (Fixture f = new Fixture(site)) {
            f.active = false;
            WorkBuddy.R r = client(f).checkIn(auth());
            require(r.outcome.equals("inactive") && !r.ok() && f.claimCalls == 0, "inactive activity must not claim");
            groups++;
        }
        return groups;
    }
    static int verifyScenarios(WorkBuddy.Site site) throws Exception {
        int groups = 0;
        try (Fixture f = new Fixture(site)) {
            SiteRegistry.Verify v = client(f).verify(auth());
            require(v.ok && !v.invalid && !v.network && f.statusCalls == 1 && f.claimCalls == 0,
                    "read-only login verification must pass without claiming");
            groups++;
        }
        try (Fixture f = new Fixture(site)) {
            f.statusCode = "1001"; // HTTP 401 + code 1001
            SiteRegistry.Verify v = client(f).verify(auth());
            require(v.invalid && !v.network && !v.ok && f.claimCalls == 0,
                    "401/code 1001 must be reported as invalid credentials");
            groups++;
        }
        try (Fixture f = new Fixture(site)) {
            f.statusTransient = 2; // bounded status retry exhausts; no claim is ever sent
            SiteRegistry.Verify v = client(f).verify(auth());
            require(v.network && !v.invalid && !v.ok && f.statusCalls == 2 && f.claimCalls == 0,
                    "network failure must be distinguished from invalid credentials");
            groups++;
        }
        return groups;
    }
    public static void main(String[] args) throws Exception {
        int groups = 0;
        for (WorkBuddy.Site site : new WorkBuddy.Site[]{WorkBuddy.AI, WorkBuddy.CN}) {
            groups += scenarios(site);
            groups += verifyScenarios(site);
        }
        System.out.println("PASS WorkBuddyTest: " + groups + " groups (AI+CN state polling, refresh, bounded status retry, claim semantics, no claim replay, read-only verification)");
    }
}
