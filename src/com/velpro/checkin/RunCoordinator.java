package com.velpro.checkin;

/** One user-triggered round. Lifetime belongs to the Service, never an Activity. */
public class RunCoordinator {
    private boolean running;
    public synchronized boolean acquire() {
        if (running) return false;
        running = true;
        return true;
    }
    public synchronized boolean isRunning() { return running; }
    public synchronized void release() { running = false; }
    public interface Listener {
        void status(String message);
        void result(String site, Net.R result);
        void finished();
    }

    /** One uniform site check: a registry entry knows its protocol, the coordinator only calls this. */
    public interface Check { void check(Net net, Listener sink); }

    public static final class Entry {
        public String siteId = "", historyName = "", error = "";
        public boolean enabled = true, invalid;
        public RunCoordinator.Check override;
        public SiteRegistry.Site site;
        public WorkBuddy.Auth wb;
        public Trae.Auth trae;
        public WorkBuddy.Saver wbSaver;
        public Trae.Saver traeSaver;
        public SiteRegistry.Custom custom;
        public String customCookie = "";
        public CustomSite.Saver customSaver;
    }

    public static final class Plan {
        public final java.util.List<Entry> entries = new java.util.ArrayList<>();
        public static Plan empty() { return new Plan(); }

        /** Plan.from converts the account inventory into one uniform check per listed site. */
        public static Plan from(java.util.List<AccountStore.Account> accounts) {
            Plan p = new Plan();
            if (accounts == null) return p;
            for (AccountStore.Account a : accounts) {
                SiteRegistry.Site s = a.custom != null ? SiteRegistry.customSite(a.siteId, a.custom)
                        : SiteRegistry.byId(a.siteId);
                if (s == null) continue;
                Entry e = new Entry();
                e.siteId = a.siteId; e.historyName = s.historyName; e.site = s;
                e.enabled = true; e.invalid = a.invalid; e.error = a.error;
                e.wb = a.wb; e.trae = a.trae; e.wbSaver = a.wbSaver; e.traeSaver = a.traeSaver;
                e.custom = a.custom; e.customCookie = a.customCookie; e.customSaver = a.customSaver;
                p.entries.add(e);
            }
            return p;
        }
    }

    /** Optional self-added accounts; token-bearing values are decrypted from Store by the Service. */
    public static class Accounts {
        public boolean wbEnabled, traeEnabled;
        public WorkBuddy.Auth wb;
        public Trae.Auth trae;
        public WorkBuddy.Saver wbSaver;
        public Trae.Saver traeSaver;
        public static Accounts empty() { return new Accounts(); }
    }

    /** Legacy six-argument entry point: exactly the original two sites, unchanged behaviour. */
    public void execute(Net client, String user, String password, String githubCookies, String ua, Listener sink) {
        execute(client, user, password, githubCookies, ua, Accounts.empty(), sink);
    }

    /** Four independent sites; a failure at one site never prevents the next site. */
    public void execute(Net client, String user, String password, String githubCookies, String ua,
                        Accounts accounts, Listener sink) {
        try {
            sink.status("77625：正在直连登录/签到…");
            sink.result("77625", client.check77625(user, password));
            for (int attempt = 1; attempt <= 2; attempt++) {
                sink.status("AgentRouter：静默退出并重登，确认领取（第" + attempt + "次）…");
                Net.R r = client.checkAgentRouter(githubCookies, ua);
                sink.result("AgentRouter", r);
                if (r.ok || !r.loggedIn || r.needsAuth) break;
            }
            if (accounts == null) accounts = Accounts.empty();
            if (accounts.wbEnabled && accounts.wb != null) workBuddy(accounts.wb, accounts.wbSaver, sink);
            if (accounts.traeEnabled && accounts.trae != null) trae(accounts.trae, accounts.traeSaver, sink);
        } catch (Exception e) {
            Net.R failure = new Net.R();
            failure.msg = "任务中断，请查看领取记录并重试";
            sink.result("后台任务", failure);
        } finally {
            sink.finished(); // caller releases after its final notification/storage work
        }
    }

    private void workBuddy(WorkBuddy.Auth a, WorkBuddy.Saver saver, Listener sink) {
        WorkBuddy wb = new WorkBuddy();
        WorkBuddy.Auth auth = a;
        try {
            if (a.refresh != null && !a.refresh.isEmpty()
                    && (a.access == null || a.access.isEmpty() || expired(a.expires)))
                auth = wb.refresh(a, saver); // saves the rotated pair immediately
        } catch (Exception e) {
            emit(sink, "WorkBuddy", false, true, "WorkBuddy 登录已失效，请到设置页重新登录");
            return;
        }
        try {
            sink.status("WorkBuddy：正在检查今日签到…");
            WorkBuddy.R r = wb.checkIn(auth);
            emit(sink, "WorkBuddy", r.ok(), r.needsAuth,
                    message(r.outcome, r.message, r.credit, r.streak, r.needsAuth));
        } catch (Exception e) {
            emit(sink, "WorkBuddy", false, false, "WorkBuddy 网络异常，签到未完成");
        }
    }

    private void trae(Trae.Auth a, Trae.Saver saver, Listener sink) {
        Trae trae = new Trae();
        Trae.Auth auth = a;
        try {
            if (a.refresh != null && !a.refresh.isEmpty()
                    && (a.token == null || a.token.isEmpty() || expired(a.expires)))
                auth = trae.refresh(a, saver); // one-shot tokens: persist at once
        } catch (Exception e) {
            emit(sink, "Trae", false, true, "Trae CN 登录已失效或凭据轮换失败，请到设置页重新登录");
            return;
        }
        try {
            sink.status("Trae CN：正在检查今日签到…");
            Trae.R r = trae.checkIn(auth);
            emit(sink, "Trae", r.ok(), r.needsAuth,
                    message(r.outcome, r.message, r.credit, 0, r.needsAuth));
        } catch (Exception e) {
            emit(sink, "Trae", false, false, "Trae CN 网络异常，签到未完成");
        }
    }

    /** Generic account-plan run: one check per entry, with per-site failure isolation. */
    /** Only worker threads take this lock: serialize balance refresh with check-in token rotation. */
    public static final Object AUTH_LOCK = new Object();
    private static Listener attributed(Listener sink, String siteId) {
        return new Listener() {
            public void status(String text) { sink.status(text); }
            public void result(String site, Net.R r) { r.siteId=siteId; sink.result(site,r); }
            public void finished() { }
        };
    }
    public void execute(Net client, String user, String password, String githubCookies, String ua,
                        Plan plan, Listener sink) {
        if (plan == null) plan = Plan.empty();
        synchronized (AUTH_LOCK) {
            try {
                for (Entry e : plan.entries) {
                    String label = e.historyName.isEmpty() ? e.siteId : e.historyName;
                    if (!e.enabled) continue;
                    Listener one=attributed(sink,e.siteId);
                    if (e.invalid) {
                        String reason = e.error == null ? "" : e.error.trim();
                        emit(one, label, false, true,
                                (reason.isEmpty() ? "登录已失效" : reason) + "；请到设置页重新登录该账号");
                        continue;
                    }
                    try {
                        Check check = e.override != null ? e.override
                                : SiteRegistry.check(e, user, password, githubCookies, ua);
                        check.check(client, one);
                    } catch (Exception ex) {
                        emit(one, label, false, false, "站点签到失败，已保留记录并继续下一站");
                    }
                }
            } finally {
                sink.finished();
            }
        }
    }

    public static void emit(Listener sink, String site, boolean ok, boolean needsAuth, String msg) {
        emit(sink,site,ok,needsAuth,msg,"");
    }
    public static void emit(Listener sink, String site, boolean ok, boolean needsAuth, String msg, String balance) {
        Net.R out = new Net.R();
        out.ok = ok; out.needsAuth = needsAuth; out.msg = msg; out.balance=balance;
        sink.result(site, out);
    }
    public static boolean expired(long expires) {
        return expires > 0 && System.currentTimeMillis() >= expires - 60_000L;
    }
    public static String message(String outcome, String detail, int credit, int streak, boolean needsAuth) {
        String lead;
        switch (outcome) {
            case "done": lead = "今日已签到"; break;
            case "claimed": lead = "签到成功"; break;
            case "inactive": lead = "活动未开启"; break;
            case "ineligible": lead = "今日不可领取，当天不再重试"; break;
            default: lead = "签到失败"; break;
        }
        StringBuilder s = new StringBuilder(lead);
        if (credit > 0) s.append("，积分 ").append(credit);
        if (streak > 0) s.append("，连续 ").append(streak).append(" 天");
        String d = detail == null ? "" : detail.trim();
        if (!d.isEmpty() && !d.equals(lead)) s.append("：").append(d);
        if (needsAuth) s.append("；请到设置页重新登录");
        return s.toString();
    }
}
