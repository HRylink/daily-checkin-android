package com.velpro.checkin;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Real local HTTP fault injection. No user credentials or external services. */
public class ReliabilityTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static class Site implements AutoCloseable {
        final HttpServer server;
        final ExecutorService pool = Executors.newCachedThreadPool();
        final String base;
        volatile int logins, reads, posts, callbacks, states, logouts;
        volatile int readFailures, readError = 503, postError, callbackError;
        volatile boolean commit, checked, badBody;
        volatile int slowReads, slowPosts;
        Site() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(pool);
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/", this::serve);
            server.start();
        }
        void pause() {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        void serve(HttpExchange x) throws IOException {
            String path = x.getRequestURI().getPath();
            int status = 200;
            String body = "{\"success\":true}";
            if (path.equals("/api/auth/login")) {
                logins++;
                check(x.getRequestMethod().equals("POST"), "login method changed");
            } else if (path.equals("/api/auth/me")) {
                reads++;
                if (slowReads-- > 0) pause();
                if (readFailures-- > 0) { status = readError; body = "unavailable"; }
                else body = "{\"checked_in_today\":" + checked + ",\"credits\":100}";
            } else if (path.equals("/api/account/checkin")) {
                posts++;
                check(x.getRequestMethod().equals("POST"), "checkin method changed");
                checked = commit;
                if (slowPosts-- > 0) pause();
                if (postError != 0) { status = postError; body = "unavailable"; }
                else body = badBody ? "<html>lost acknowledgement</html>" : "{\"success\":true,\"points\":100}";
            } else if (path.equals("/api/user/logout")) {
                logouts++;
            } else if (path.equals("/api/oauth/state")) {
                states++;
                body = "{\"success\":true,\"data\":\"private-state\"}";
            } else if (path.equals("/api/oauth/github")) {
                callbacks++;
                if (callbackError != 0) { status = callbackError; body = "unavailable"; }
                else body = "{\"success\":true,\"data\":{\"id\":1,\"checked_in\":true}}";
            } else { status = 503; body = "unavailable"; } // optional balance outage
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try {
                x.sendResponseHeaders(status, bytes.length);
                x.getResponseBody().write(bytes);
            } finally { x.close(); }
        }
        public void close() { server.stop(0); pool.shutdownNow(); }
    }
    static Net fast(Site s) {
        try {
            return Net.class.getDeclaredConstructor(String.class, String.class, String.class,
                    int.class, int.class, int.class).newInstance(s.base, s.base, s.base, 100, 100, 10);
        } catch (Exception e) { throw new AssertionError("missing short-timeout HTTP test configuration", e); }
    }
    static Net.R run(Net n) { return n.check77625("fixture-user", "fixture-password"); }
    public static void main(String[] args) throws Exception {
        int groups = 0;
        try (Site s = new Site()) {
            s.readFailures = 1; s.commit = true;
            Net.R r = run(new Net(s.base, s.base));
            check(r.ok && s.posts == 1 && s.logins == 1 && s.reads == 3,
                    "safe status GET must recover once from 503, without replaying POST: " + r.msg);
            groups++;
        }
        for (int status : new int[]{503, 429, 403, 401, 500}) try (Site s = new Site()) {
            s.readFailures = 20; s.readError = status;
            Net.R r = run(new Net(s.base, s.base));
            int expected = status == 503 ? 2 : 1;
            check(!r.ok && s.posts == 0 && s.reads == expected, "GET retry bound/status policy failed: " + status);
            check(r.msg.contains("77625") && r.msg.contains("状态") && r.msg.contains("" + status), "missing stage/HTTP diagnosis: " + r.msg);
            groups++;
        }
        for (boolean commit : new boolean[]{true, false}) try (Site s = new Site()) {
            s.postError = 503; s.commit = commit;
            Net.R r = run(new Net(s.base, s.base));
            check(r.ok == commit && s.posts == 1 && s.logins == 1 && s.reads == 2,
                    "ambiguous checkin must query state and NEVER replay POST: " + r.msg);
            check(commit ? r.msg.contains("核验") : r.msg.contains("结果未确认"), "uncertain result not explained: " + r.msg);
            groups++;
        }
        try (Site s = new Site()) {
            s.badBody = true; s.commit = true;
            Net.R r = run(new Net(s.base, s.base));
            check(r.ok && s.posts == 1 && r.msg.contains("核验"), "lost JSON acknowledgement not reconciled");
            groups++;
        }
        try (Site s = new Site()) {
            s.callbackError = 503;
            Net n = new Net(s.base, s.base);
            String state = n.beginAgentRouter();
            Net.R r = n.finishAgentRouter("private-code", state);
            check(!r.ok && !r.needsAuth && s.callbacks == 1 && s.states == 1 && s.logouts == 1,
                    "OAuth GET code exchange must NOT be automatically replayed");
            check(r.msg.contains("AgentRouter") && r.msg.contains("回调") && r.msg.contains("503"), "missing OAuth stage: " + r.msg);
            check(!r.msg.contains("private-code") && !r.msg.contains("private-state"), "OAuth secrets leaked in error");
            r = n.finishAgentRouter("private-code", state);
            check(!r.ok && s.callbacks == 1, "consumed callback replayed");
            groups++;
        }
        try (Site s = new Site()) {
            s.slowReads = 1; s.commit = true;
            Net.R r = run(fast(s));
            check(r.ok && s.reads == 3 && s.posts == 1, "read timeout GET retry failed: " + r.msg);
            groups++;
        }
        try (Site s = new Site()) {
            s.slowReads = 20;
            Net.R r = run(fast(s));
            check(!r.ok && s.reads == 2 && s.posts == 0 && r.msg.contains("超时"), "timeout retry cap/diagnosis failed: " + r.msg);
            groups++;
        }
        try (Site s = new Site()) {
            s.slowPosts = 1; s.commit = true;
            Net.R r = run(fast(s));
            check(r.ok && s.posts == 1 && r.msg.contains("核验"), "committed POST timeout not safely reconciled: " + r.msg);
            groups++;
        }
        try (Site s = new Site()) {
            Net n = fast(s);
            String state = n.beginAgentRouter();
            Net.R r = n.finishAgentRouter("private-code", state);
            check(r.ok && r.loggedIn && r.msg.contains("到账"), "optional balance must not negate confirmed reward: " + r.msg);
            groups++;
        }
        System.out.println("PASS ReliabilityTest: " + groups + " groups (GET fault/timeout bounds, POST reconciliation, no OAuth replay, safe diagnosis)");
    }
}
