package com.velpro.checkin;

import java.util.*;
import org.json.JSONArray;

/** Pure-JVM tests for the v1.4 registry, account migration and generic run loop. */
public class FrameworkTest {
    static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    static final class Mem implements AccountStore.KV {
        final Map<String,String> values = new HashMap<>();
        @Override public String get(String key) { String v = values.get(key); return v == null ? "" : v; }
        @Override public boolean put(String key, String value) { values.put(key, value); return true; }
    }
    static final class Sink implements RunCoordinator.Listener {
        final List<String> sites = new ArrayList<>(), statuses = new ArrayList<>();
        final List<Net.R> results = new ArrayList<>();
        boolean finished;
        @Override public void status(String message) { statuses.add(message); }
        @Override public void result(String site, Net.R result) { sites.add(site); results.add(result); }
        @Override public void finished() { finished = true; }
    }
    static RunCoordinator.Check fake(String name, List<String> calls, boolean ok) {
        return (net, sink) -> {
            calls.add(name);
            Net.R r = new Net.R(); r.ok = ok; r.msg = name;
            sink.result(name, r);
        };
    }
    static Net offlineNet() { return new Net("http://127.0.0.1:1", "http://127.0.0.1:1"); }

    public static void main(String[] args) throws Exception {
        int groups = 0;

        // SiteRegistry: protocol/path stripping, exact hosts and subdomains, special hints.
        require(SiteRegistry.byId("workbuddy-ai") != null && SiteRegistry.byId("workbuddy-cn") != null
                && SiteRegistry.byId("trae-cn") != null, "registry must contain both WorkBuddy editions and Trae CN");
        require(SiteRegistry.detect("https://www.workbuddy.ai/login?next=/profile").sites.size() == 1
                && SiteRegistry.detect("https://www.workbuddy.ai/login?next=/profile").sites.get(0).id.equals("workbuddy-ai"),
                "WorkBuddy AI URL detection failed");
        require(SiteRegistry.detect("login.workbuddy.ai/start").sites.size() == 1
                && SiteRegistry.detect("login.workbuddy.ai/start").sites.get(0).id.equals("workbuddy-ai"),
                "WorkBuddy AI subdomain detection failed");
        require(SiteRegistry.detect("https://copilot.tencent.com/ide/checkin").sites.get(0).id.equals("workbuddy-cn"),
                "WorkBuddy CN URL detection failed");
        require(SiteRegistry.detect("https://api.trae.cn/v2").sites.get(0).id.equals("trae-cn"),
                "Trae CN host detection failed");
        require(SiteRegistry.detect("https://www.trae.cn/authorization").sites.get(0).id.equals("trae-cn"),
                "Trae CN www host detection failed");
        require(SiteRegistry.detect("https://ly.77625.xyz/api").sites.get(0).id.equals("77625"),
                "77625 URL detection failed");
        require(SiteRegistry.detect("https://agentrouter.org/home").sites.get(0).id.equals("agentrouter"),
                "AgentRouter URL detection failed");
        require(SiteRegistry.TRAE_GLOBAL_HINT.equals(SiteRegistry.detect("https://app.trae.ai/home").hint),
                "trae.ai must explain that the global edition has no daily check-in");
        SiteRegistry.Detection unknown = SiteRegistry.detect("https://workbuddy.ai.evil.example/path");
        require(unknown.sites.isEmpty() && SiteRegistry.UNSUPPORTED.equals(unknown.unsupported),
                "unsupported host must give the planned hint");
        SiteRegistry.Site cn = SiteRegistry.byId("workbuddy-cn");
        require(cn.wbSite != null && cn.wbSite.endpoint.equals("https://copilot.tencent.com")
                && cn.wbSite.platform.equals("workbuddy") && cn.wbSite.domain.equals("copilot.tencent.com")
                && WorkBuddy.APP.equals(cn.wbSite.app), "CN WorkBuddy site configuration wrong");
        require(SiteRegistry.byId("workbuddy-ai").wbSite.platform.equals("workbuddy-ai"),
                "AI WorkBuddy platform must stay workbuddy-ai");
        groups++;

        // v1.3 flat keys migrate into a stable accounts list without rewriting credentials.
        Mem kv = new Mem();
        kv.put("user", "old-77625"); kv.put("pass", "old-pass"); kv.put("ar_cookie", "old-cookie");
        kv.put("wb_access", "A1"); kv.put("wb_refresh", "R1"); kv.put("wb_expires", "4102444800000");
        kv.put("wb_uid", "uid-42"); kv.put("wb_user", "WB 用户"); kv.put("wb_enabled", "0");
        kv.put("trae_token", "T1"); kv.put("trae_refresh", "TR1"); kv.put("trae_device_id", "1234567890123456");
        kv.put("trae_machine_id", "0123456789abcdef0123456789abcdef");
        kv.put("trae_device_key", "PRIVATE-PEM"); kv.put("trae_public_key", "PUBLIC-PEM");
        kv.put("trae_uid", "trae-42"); kv.put("trae_user", "Trae 用户"); kv.put("trae_enabled", "1");
        List<AccountStore.Account> migrated = AccountStore.load(kv, false);
        JSONArray ids = new JSONArray(kv.get("accounts"));
        require(ids.length() == 4, "migration must record the two legacy sites and both v1.3 self-added sites");
        require(ids.getString(0).equals("77625") && ids.getString(1).equals("agentrouter")
                && ids.getString(2).equals("workbuddy-ai") && ids.getString(3).equals("trae-cn"),
                "accounts list must use the planned siteId order");
        require(migrated.size() == 4 && migrated.get(2).siteId.equals("workbuddy-ai")
                && migrated.get(2).user.equals("WB 用户") && !migrated.get(2).enabled,
                "legacy wb_enabled=0 must be preserved");
        require(migrated.get(3).enabled && migrated.get(3).trae != null
                && migrated.get(3).trae.token.equals("T1") && migrated.get(3).traeSaver != null,
                "legacy Trae credentials must load with a persistence callback");
        require(migrated.get(2).wb != null && migrated.get(2).wb.access.equals("A1")
                && migrated.get(2).wbSaver != null, "legacy WorkBuddy credentials must load with a persistence callback");
        require(AccountStore.credentialKey("workbuddy-ai", "access").equals("wb_access")
                && AccountStore.credentialKey("trae-cn", "device_key").equals("trae_device_key")
                && AccountStore.credentialKey("workbuddy-cn", "access").equals("workbuddy_cn_access"),
                "credential key mapping must preserve v1.3 names and use siteId prefixes for new sites");
        groups++;

        // New accounts, disabled switches, invalid verification marks and deletion.
        WorkBuddy.Auth fresh = new WorkBuddy.Auth();
        fresh.access = "CN-A"; fresh.refresh = "CN-R"; fresh.uid = "cn-42"; fresh.user = "国内用户";
        fresh.expires = 4102444800000L;
        AccountStore.saveWorkBuddy(kv, "workbuddy-cn", fresh);
        require(kv.get("workbuddy_cn_access").equals("CN-A") && kv.get("workbuddy_cn_refresh").equals("CN-R"),
                "new site credentials must use siteId-prefixed encrypted keys");
        AccountStore.add(kv, "workbuddy-cn", "国内用户");
        List<AccountStore.Account> five = AccountStore.load(kv, false);
        require(five.size() == 5 && five.get(3).siteId.equals("workbuddy-cn")
                && five.get(3).user.equals("国内用户") && five.get(3).enabled,
                "CN account must be added in planned order and enabled by default");
        AccountStore.setEnabled(kv, "workbuddy-cn", false);
        require(!AccountStore.load(kv, false).get(3).enabled, "签到开关 must persist disabled state");
        AccountStore.markInvalid(kv, "workbuddy-cn", "凭据无效，请重新登录");
        AccountStore.Account invalid = AccountStore.load(kv, false).get(3);
        require(invalid.invalid && invalid.error.contains("凭据无效"), "failed verification must mark retained credentials invalid");
        AccountStore.add(kv, "workbuddy-cn", "国内用户");
        AccountStore.Account repaired = AccountStore.load(kv, false).get(3);
        require(!repaired.invalid && repaired.error.isEmpty(), "successful re-login must clear the invalid mark");
        AccountStore.remove(kv, "workbuddy-cn");
        List<AccountStore.Account> removed = AccountStore.load(kv, false);
        require(removed.size() == 4 && kv.get("workbuddy_cn_access").isEmpty(), "delete must remove the account and its credentials");
        groups++;

        // A failed first-time verification keeps credentials but must not enter the verified accounts list.
        Mem pendingKv = new Mem();
        AccountStore.saveWorkBuddy(pendingKv, "workbuddy-cn", fresh);
        AccountStore.markInvalid(pendingKv, "workbuddy-cn", "网络异常，无法验证凭据");
        String pendingList = pendingKv.get("accounts");
        require(pendingList.isEmpty() || new JSONArray(pendingList).length() == 0,
                "failed verification must not add the site to accounts");
        List<AccountStore.Account> pending = AccountStore.load(pendingKv, false);
        require(pending.size() == 1 && pending.get(0).siteId.equals("workbuddy-cn")
                && pending.get(0).invalid && !pending.get(0).listed
                && pending.get(0).wb != null && pending.get(0).wb.access.equals("CN-A"),
                "pending invalid credentials stay visible with their tokens for retry");
        AccountStore.add(pendingKv, "workbuddy-cn", "国内用户");
        JSONArray verified = new JSONArray(pendingKv.get("accounts"));
        require(verified.length() == 1 && verified.getString(0).equals("workbuddy-cn"),
                "only verified re-login writes the site into accounts");
        List<AccountStore.Account> recovered = AccountStore.load(pendingKv, false);
        require(recovered.size() == 1 && recovered.get(0).listed && recovered.get(0).enabled
                && !recovered.get(0).invalid, "verified account must be listed, enabled and valid");
        groups++;

        // Empty old devices with only a GitHub WebView cookie still retain the two legacy sites.
        Mem cookiesOnly = new Mem();
        List<AccountStore.Account> legacy = AccountStore.load(cookiesOnly, true);
        require(legacy.size() == 2 && legacy.get(0).siteId.equals("77625")
                && legacy.get(1).siteId.equals("agentrouter"), "legacy GitHub cookie must migrate both fixed sites");
        groups++;

        // RunCoordinator walks the account plan through one uniform Check per site.
        AccountStore.add(kv, "workbuddy-cn", "国内用户");
        RunCoordinator.Plan plan = RunCoordinator.Plan.from(AccountStore.load(kv, false));
        List<String> expected = Arrays.asList("77625", "AgentRouter", "WorkBuddy", "WorkBuddy 国内版", "Trae");
        require(plan.entries.size() == expected.size(), "plan must contain all five enabled sites");
        List<String> calls = new ArrayList<>();
        for (int i = 0; i < plan.entries.size(); i++) {
            RunCoordinator.Entry e = plan.entries.get(i);
            require(e.historyName.equals(expected.get(i)), "plan order/history label wrong at index " + i);
            e.override = fake(e.historyName, calls, true);
        }
        RunCoordinator job = new RunCoordinator();
        Sink sink = new Sink();
        job.execute(offlineNet(), "u", "p", "user_session=mobile", "mobile-UA", plan, sink);
        require(calls.equals(expected) && sink.sites.equals(expected) && sink.finished,
                "generic loop must check every enabled site once in registry order");
        groups++;

        // A retained-but-invalid account and a thrown checker both record failure without stopping later sites.
        RunCoordinator.Plan isolated = RunCoordinator.Plan.empty();
        RunCoordinator.Entry bad = new RunCoordinator.Entry();
        bad.siteId = "workbuddy-ai"; bad.historyName = "WorkBuddy"; bad.enabled = true;
        bad.invalid = true; bad.error = "401";
        bad.override = fake("should-not-run", calls, true);
        isolated.entries.add(bad);
        RunCoordinator.Entry throwing = new RunCoordinator.Entry();
        throwing.siteId = "workbuddy-cn"; throwing.historyName = "WorkBuddy 国内版"; throwing.enabled = true;
        throwing.override = (net, s) -> { throw new IllegalStateException("site crashed"); };
        isolated.entries.add(throwing);
        RunCoordinator.Entry next = new RunCoordinator.Entry();
        next.siteId = "trae-cn"; next.historyName = "Trae"; next.enabled = true;
        next.override = fake("Trae", calls, true);
        isolated.entries.add(next);
        calls.clear(); Sink sink2 = new Sink();
        job.execute(offlineNet(), "u", "p", "gh", "ua", isolated, sink2);
        require(calls.equals(Collections.singletonList("Trae")), "invalid/broken checks must not consume later accounts");
        require(sink2.sites.equals(Arrays.asList("WorkBuddy", "WorkBuddy 国内版", "Trae"))
                && sink2.results.get(0).needsAuth && sink2.results.get(0).msg.contains("到设置页重新登录该账号")
                && !sink2.results.get(1).ok && sink2.results.get(2).ok && sink2.finished,
                "per-site failures must be recorded while the loop continues");
        RunCoordinator.Entry cnEntry = new RunCoordinator.Entry();
        cnEntry.siteId = "workbuddy-cn"; cnEntry.site = SiteRegistry.byId("workbuddy-cn");
        require(SiteRegistry.check(cnEntry, "u", "p", "gh", "ua") != null,
                "SiteRegistry must point new sites at a Check implementation");
        groups++;

        System.out.println("PASS FrameworkTest: " + groups + " groups (registry detection, v1.3 migration, account CRUD, generic run loop, failure isolation)");
    }
}
