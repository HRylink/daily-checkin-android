package com.velpro.checkin;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.json.JSONObject;

/** Real local read-only HTTP fixtures; never reaches a production site or claim endpoint. */
public class BalancesTest {
    static void require(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    static class KV implements AccountStore.KV {
        final Map<String,String> values = new HashMap<>();
        public String get(String k) { return values.getOrDefault(k, ""); }
        public boolean put(String k, String v) { values.put(k,v); return true; }
    }
    static class Fixture implements AutoCloseable {
        final HttpServer server;
        final String base;
        final List<String> seen = new ArrayList<>();
        String summary = "{\"CreditGroups\":[{\"Kind\":\"plan_base\",\"CycleTotalCapacity\":100,\"CycleUsedCapacity\":52},{\"Kind\":\"extra\",\"CycleTotalCapacity\":50,\"CycleUsedCapacity\":2}],\"Packages\":[{\"CycleTotalCapacity\":150,\"CycleUsedCapacity\":54},{\"CycleRemainCapacity\":20}]}";
        int status = 200, refreshCalls;
        String unit = "1000", credits = "7", wbToken = "A1", traeToken = "T1";
        boolean saved, checked;
        String handlerError = "";
        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/", x -> {
                try { serve(x); } catch (Throwable e) { handlerError = e.toString(); x.close(); }
            }); server.start();
        }
        void serve(HttpExchange x) throws Exception {
            String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
            String body = new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            seen.add(method + " " + path);
            if (path.equals("/v2/plugin/auth/token/refresh")) {
                refreshCalls++; require("R1".equals(x.getRequestHeaders().getFirst("X-Refresh-Token")),"WB refresh header");
                wbToken = "A2";
                send(x,200,"{\"code\":0,\"data\":{\"accessToken\":\"A2\",\"refreshToken\":\"R2\",\"expiresIn\":3600}}"); return;
            }
            if (path.equals("/cloudide/api/v3/trae/oauth/ExchangeToken")) {
                refreshCalls++; traeToken = "T2";
                send(x,200,"{\"Result\":{\"Token\":\"T2\",\"RefreshToken\":\"RT2\",\"TokenExpireAt\":9876543210}}"); return;
            }
            if (path.equals("/trae/api/v3/oauth/ExchangeToken")) {
                send(x,200,"{\"Result\":{\"Token\":\"T2\",\"RefreshToken\":\"RT2\",\"TokenExpireAt\":9876543210}}"); return;
            }
            if (path.equals("/api/user/self")) {
                require(method.equals("GET"),"AR must GET self");
                require("42".equals(x.getRequestHeaders().getFirst("New-Api-User")),"AR user id header");
                require(x.getRequestHeaders().getFirst("Cookie").contains("session=private"),"AR restored cookie");
                send(x,status,"{\"success\":true,\"data\":{\"id\":42,\"quota\":2500}}"); return;
            }
            if (path.equals("/api/status")) {
                send(x,status,"{\"success\":true,\"data\":{\"quota_per_unit\":" + unit + "}}"); return;
            }
            if (path.equals("/billing/meter/get-user-resource-summary")) {
                require(method.equals("POST") && body.equals("{}"),"WB read-only POST {}");
                require(("Bearer " + wbToken).equals(x.getRequestHeaders().getFirst("Authorization")),"WB restored/refreshed bearer");
                require("uid-42".equals(x.getRequestHeaders().getFirst("X-User-Id")),"WB user header");
                require("SaaS".equals(x.getRequestHeaders().getFirst("X-Product")),"WB meter headers");
                require(x.getRequestHeaders().getFirst("X-Domain") != null,"WB domain");
                if (refreshCalls > 0) require(saved,"WB saver must run before summary");
                send(x,status,"{\"code\":0,\"data\":" + summary + "}"); return;
            }
            if (path.equals("/trae/api/v2/ug/checkin_credits/status")) {
                require(method.equals("POST") && body.equals("{}"),"Trae read-only POST {}");
                require(("Cloud-IDE-JWT " + traeToken).equals(x.getRequestHeaders().getFirst("Authorization")),"Trae ug bearer");
                require(x.getRequestHeaders().getFirst("x-device-id") != null,"Trae ug device");
                if (refreshCalls > 0) require(saved,"Trae saver before status");
                send(x,status,"{\"code\":0,\"checked_in\":" + checked + ",\"credits\":" + credits + ",\"extra_credits\":999}"); return;
            }
            throw new AssertionError("NON READ-ONLY ENDPOINT " + path);
        }
        void send(HttpExchange x,int code,String text) throws IOException {
            byte[] b = text.getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(code,b.length);
            x.getResponseBody().write(b); x.close();
        }
        Balances client() {
            return new Balances(new Net(base,base,base,300,300,1),
                site -> new WorkBuddy(site.withEndpoint(base),300,300,1,1),new Trae(base,base,300,300));
        }
        public void close() { server.stop(0); require(handlerError.isEmpty(),handlerError); }
    }
    static AccountStore.Account account(String id) {
        AccountStore.Account a = new AccountStore.Account(); a.siteId=id; a.listed=true;
        a.wb = new WorkBuddy.Auth(); a.wb.access="A1"; a.wb.refresh="R1"; a.wb.uid="uid-42";
        a.trae = new Trae.Auth(); a.trae.token="T1"; a.trae.refresh="RT1"; a.trae.uid="uid-42"; a.trae.deviceId="device";
        return a;
    }
    static KV kv() { KV k = new KV(); k.put("ar_cookie","session=private"); k.put("agentrouter_uid","42"); return k; }
    public static void main(String[] args) throws Exception {
        int tests=0;
        try (Fixture f = new Fixture()) {
            KV k=kv(); Balances b=f.client(); AccountStore.Account a=account("agentrouter");
            require(b.refresh(k,a,1_700_000_000_000L),"initial query");
            require("2.50".equals(k.get("agentrouter_balance")),"AR quota/unit");
            require(f.seen.equals(Arrays.asList("GET /api/user/self","GET /api/status")),"AR only self/status"); tests++;
        }
        try (Fixture f = new Fixture()) {
            f.unit="null"; Balances.Result r=f.client().query(kv(),account("agentrouter"));
            require(r.ok && "0.01".equals(r.value),"AR fallback 500000"); tests++;
        }
        try (Fixture f = new Fixture()) {
            Balances.Result r=f.client().query(kv(),account("workbuddy-ai"));
            require(r.ok && r.value.equals("54/150 · 预付剩余 20"),"plan+extra, no package double-count, prepaid"); tests++;
        }
        try (Fixture f = new Fixture()) {
            f.summary="{\"Packages\":[{\"CycleTotalCapacity\":\"100\",\"CycleUsedCapacity\":\"4\"},{\"CycleTotalCapacity\":50,\"CycleUsedCapacity\":2}]}";
            require("6/150".equals(f.client().query(kv(),account("workbuddy-cn")).value),"CN package fallback"); tests++;
        }
        try (Fixture f = new Fixture()) {
            f.summary="{\"Packages\":[{\"CycleRemainCapacity\":\"42\"}]}";
            require("预付剩余 42".equals(f.client().query(kv(),account("workbuddy-ai")).value),"prepaid only"); tests++;
        }
        try (Fixture f = new Fixture()) {
            require("7".equals(f.client().query(kv(),account("trae-cn")).value),"Trae credits excludes extra");
            f.credits="null"; require(!f.client().query(kv(),account("trae-cn")).ok,"missing credits must fail, not zero"); tests++;
        }
        try (Fixture f = new Fixture()) {
            JSONObject summary=new JSONObject(f.summary);
            summary.getJSONArray("Packages").put(new JSONObject("{\"CycleRemainCapacity\":\"\"}"));
            f.summary=summary.toString();
            require("54/150 · 预付剩余 20".equals(f.client().query(kv(),account("workbuddy-ai")).value),"empty prepaid is absent, not malformed");
            f.summary="{\"CreditGroups\":[{\"Kind\":\"plan_base\"}],\"Packages\":[{\"CycleTotalCapacity\":100,\"CycleUsedCapacity\":4}]}";
            require(!f.client().query(kv(),account("workbuddy-ai")).ok,"nonempty groups must not fall back to possibly duplicate packages"); tests++;
        }
        try (Fixture f = new Fixture()) {
            f.status=503; f.checked=true;
            Trae.R result=new Trae(f.base,f.base,300,300).checkIn(account("trae-cn").trae);
            require(result.balance.isEmpty(),"non-2xx status cannot confirm check-in balance");
            require(f.seen.size()==1,"HTTP status fixture must not claim"); tests++;
        }
        for (String id : Arrays.asList("agentrouter","workbuddy-ai","trae-cn")) {
            for (int code : new int[]{401,403,429,503}) try (Fixture f = new Fixture()) {
                KV k=kv(); AccountStore.saveBalance(k,id,"88",1_700_000_000_000L);
                f.status=code; f.client().refresh(k,account(id),1_700_000_030_000L);
                require("88".equals(k.get(id+"_balance")),"failure retains cache " + id);
                require(AccountStore.balanceText(k,id,1_700_000_030_000L).contains("查询失败"),"failed badge");
                require(f.seen.size()==1,"HTTP errors not retried " + code);
            }
        } tests++;
        try (Fixture f = new Fixture()) {
            KV k=kv(); Balances b=f.client(); AccountStore.Account a=account("workbuddy-ai");
            b.refresh(k,a,100_000); require(!b.refresh(k,a,129_999),"30s throttle");
            require(b.refresh(k,a,130_000),"30s boundary"); require(f.seen.size()==2,"only two queries");
            require(!b.refresh(k,account("77625"),200_000),"no 77625 query");
            require(!b.refresh(k,account("custom_0"),200_000),"NO CUSTOM CLAIM");
            a.invalid=true; require(!b.refresh(k,a,200_000),"pending not queried"); tests++;
        }
        try (Fixture f = new Fixture()) {
            KV k=kv(); AccountStore.Account a=account("workbuddy-ai"); a.wb.expires=1;
            a.wbSaver = next -> { AccountStore.saveWorkBuddy(k,a.siteId,next); f.saved=true; };
            require(f.client().query(k,a).ok && f.refreshCalls==1 && k.get("wb_access").equals("A2"),"WB refresh+saver");
            require(f.client().query(k,a).ok && f.refreshCalls==1,"WB rotated snapshot must not refresh twice"); tests++;
        }
        try (Fixture f = new Fixture()) {
            KV k=kv(); AccountStore.Account a=account("trae-cn"); a.trae.expires=1;
            Trae.Device d=Trae.newDevice(); a.trae.deviceKey=d.privatePem; a.trae.publicPem=d.publicPem;
            a.traeSaver = next -> { AccountStore.saveTrae(k,a.siteId,next); f.saved=true; };
            require(f.client().query(k,a).ok && f.refreshCalls==1 && k.get("trae_token").equals("T2"),"Trae refresh+saver");
            require(f.client().query(k,a).ok && f.refreshCalls==1,"Trae rotated snapshot must not refresh twice"); tests++;
        }
        try (Fixture f = new Fixture()) {
            KV k=kv(); AccountStore.saveBalance(k,"agentrouter","old",100_000);
            Balances b=f.client(); f.server.stop(0); b.refresh(k,account("agentrouter"),130_000);
            require("old".equals(k.get("agentrouter_balance")),"network fail retains old"); tests++;
        }
        KV k=new KV(); long now=Instant.parse("2026-11-14T04:00:00Z").toEpochMilli();
        AccountStore.saveBalance(k,"workbuddy-cn","52/100",now);
        require(k.get("workbuddy-cn_balance_at").equals("2026-11-14T04:00:00Z"),"literal siteId keys and ISO");
        require(!AccountStore.isSecret("workbuddy-cn_balance"),"balance non-sensitive");
        require(AccountStore.balanceText(k,"workbuddy-cn",now).contains("刚刚"),"fresh");
        require(AccountStore.freshness(k.get("workbuddy-cn_balance_at"),now+120_000).contains("2分钟前"),"minutes");
        require(AccountStore.freshness("bad",now).isEmpty(),"bad date safe");
        require(AccountStore.freshness("2026-11-12T00:00:00Z",now).contains("2026-11-12"),"old date");
        AccountStore.remove(k,"workbuddy-cn"); require(k.get("workbuddy-cn_balance").isEmpty(),"delete cache"); tests++;
        Net.R r=new Net.R(); r.ok=true; r.balance="42";
        AccountStore.recordResult(k,"77625",r,now); require(k.get("77625_balance").equals("42"),"confirmed balance");
        r.ok=false; r.balance="999"; AccountStore.recordResult(k,"77625",r,now); require(k.get("77625_balance").equals("42"),"failure cannot overwrite confirmed balance");
        r=new Net.R(); r.msg="原文失败🙂"; AccountStore.recordResult(k,"custom_0",r,now);
        require(AccountStore.balanceText(k,"custom_0",now).contains("原文失败🙂"),"custom failure raw cache"); tests++;
        File old=File.createTempFile("balance-history",".jsonl");
        try {
            try (Writer out=new OutputStreamWriter(new FileOutputStream(old),StandardCharsets.UTF_8)) {
                out.write("{\"date\":\"2026-11-12\",\"time\":\"12:00:00\",\"site\":\"77625\",\"ok\":true,\"credits\":100}\n");
                out.write("{\"date\":\"2026-11-13\",\"time\":\"12:00:00\",\"site\":\"77625\",\"ok\":true,\"msg\":\"已领取 3 积分，当前 103 积分\"}\n");
                out.write("{\"date\":\"2026-11-14\",\"site\":\"77625\",\"ok\":true,\"msg\":\"已领取 3 积分\"}\n");
                out.write("{\"site\":\"77625\",\"ok\":false,\"credits\":999}\nBROKEN\n");
            }
            JSONObject prior=new History(old).latest77625Balance();
            require(prior.optString("balance").equals("103"),"history confirmed balance, not reward or failure");
            require(prior.optString("at").startsWith("2026-11-13"),"historical timestamp, not current refresh time");
        } finally { old.delete(); } tests++;
        RunCoordinator.Plan plan=RunCoordinator.Plan.empty(); RunCoordinator.Entry entry=new RunCoordinator.Entry();
        entry.siteId="custom_1"; entry.historyName="77625";
        entry.override=(client,sink) -> RunCoordinator.emit(sink,"77625",true,false,"站点原文","站点原文");
        plan.entries.add(entry); final int[] callbacks={0,0};
        new RunCoordinator().execute(new Net(),"","","","",plan,new RunCoordinator.Listener() {
            public void status(String message) { }
            public void result(String label,Net.R result) {
                require(result.siteId.equals("custom_1"),"identity must not follow duplicate display name");
                AccountStore.recordResult(k,result.siteId,result,now); callbacks[0]++;
            }
            public void finished() { callbacks[1]++; }
        });
        require(callbacks[0]==1 && callbacks[1]==1 && "站点原文".equals(k.get("custom_1_balance")),"run result saved to exact account");
        require("42".equals(k.get("77625_balance")),"custom name must not overwrite fixed balance"); tests++;
        System.out.println("PASS BalancesTest " + tests + " groups");
    }
}
