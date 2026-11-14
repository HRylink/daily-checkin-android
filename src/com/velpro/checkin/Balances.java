package com.velpro.checkin;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only balances. In particular, a custom site's configured URL is NEVER a balance API. */
public final class Balances {
    public static final long INTERVAL_MS = 30_000L;
    interface WorkBuddyFactory { WorkBuddy make(WorkBuddy.Site site); }
    public static final class Result {
        public final boolean ok;
        public final String value;
        private Result(boolean ok, String value) { this.ok=ok; this.value=value; }
        static Result value(String value) { return new Result(true,value); }
        static Result failed() { return new Result(false,""); }
    }
    private final Net net;
    private final WorkBuddyFactory workBuddy;
    private final Trae trae;
    private final Map<String,Long> attempts = new HashMap<>();
    public Balances() {
        this(new Net("https://agentrouter.org",Net.S25),
                site -> new WorkBuddy(site,12000,20000,1000,600),new Trae());
    }
    Balances(Net net, WorkBuddyFactory workBuddy, Trae trae) {
        this.net=net; this.workBuddy=workBuddy; this.trae=trae;
    }
    public static boolean supported(String id) {
        return "agentrouter".equals(id) || "workbuddy-ai".equals(id)
                || "workbuddy-cn".equals(id) || "trae-cn".equals(id);
    }
    /** One shared instance in Settings retains throttling across pause/resume and configuration changes. */
    public synchronized boolean refresh(AccountStore.KV kv, AccountStore.Account a, long now) {
        if (a == null || a.invalid || !supported(a.siteId)) return false;
        Long last=attempts.get(a.siteId);
        if (last != null && now-last < INTERVAL_MS && now >= last) return false;
        attempts.put(a.siteId,now); // failures are throttled too
        Result r=query(kv,a);
        if (r.ok) AccountStore.saveBalance(kv,a.siteId,r.value,now);
        else AccountStore.balanceFailed(kv,a.siteId);
        return true;
    }
    /** Serialize token rotation with a manual check-in; refresh savers run before the read-only query. */
    public Result query(AccountStore.KV kv, AccountStore.Account a) {
        if (a == null || a.invalid || !supported(a.siteId)) return Result.failed();
        synchronized (RunCoordinator.AUTH_LOCK) {
            try {
                if ("agentrouter".equals(a.siteId)) {
                    String cookie=kv.get("ar_cookie");
                    if (cookie == null || cookie.isEmpty()) return Result.failed();
                    net.restoreAgentCookie(cookie);
                    JSONObject self=net.agentBalance(kv.get("agentrouter_uid"));
                    BigDecimal quota=number(self.opt("quota"));
                    if (self.optLong("id") > 0) kv.put("agentrouter_uid",String.valueOf(self.optLong("id")));
                    BigDecimal unit=number(self.opt("quota_per_unit"));
                    return Result.value(quota.divide(unit,2,java.math.RoundingMode.HALF_UP).toPlainString());
                }
                if ("trae-cn".equals(a.siteId)) {
                    Trae.Auth auth=a.trae;
                    if (auth == null || auth.token.isEmpty()) return Result.failed();
                    String stored=kv.get(AccountStore.credentialKey(a.siteId,"token"));
                    if (!stored.isEmpty() && !stored.equals(auth.token)) return Result.failed(); // another worker rotated this snapshot
                    if (!auth.refresh.isEmpty() && RunCoordinator.expired(auth.expires)) {
                        if (a.traeSaver == null) return Result.failed();
                        auth=trae.refresh(auth,a.traeSaver); a.trae=auth;
                    }
                    return Result.value(compact(number(trae.balanceStatus(auth).opt("credits"))));
                }
                SiteRegistry.Site site=SiteRegistry.byId(a.siteId);
                WorkBuddy wb=workBuddy.make(site.wbSite);
                WorkBuddy.Auth auth=a.wb;
                if (auth == null || auth.access.isEmpty()) return Result.failed();
                String stored=kv.get(AccountStore.credentialKey(a.siteId,"access"));
                if (!stored.isEmpty() && !stored.equals(auth.access)) return Result.failed();
                if (!auth.refresh.isEmpty() && RunCoordinator.expired(auth.expires)) {
                    if (a.wbSaver == null) return Result.failed();
                    auth=wb.refresh(auth,a.wbSaver); a.wb=auth;
                }
                return Result.value(workBuddySummary(wb.resourceSummary(auth)));
            } catch (Exception ignored) { return Result.failed(); } // do not invalidate a usable account on a balance failure
        }
    }
    private static BigDecimal number(Object v) {
        if (v == null || v == JSONObject.NULL || v.toString().trim().isEmpty()) throw new IllegalArgumentException("missing capacity");
        BigDecimal n=new BigDecimal(v.toString().trim());
        if (n.signum() < 0) throw new IllegalArgumentException("negative capacity");
        return n;
    }
    private static String compact(BigDecimal n) { return n.stripTrailingZeros().toPlainString(); }
    private static boolean hasTotal(JSONObject p) {
        return p != null && p.opt("CycleTotalCapacity") != null && p.opt("CycleTotalCapacity") != JSONObject.NULL
                && !p.opt("CycleTotalCapacity").toString().isEmpty();
    }
    /** CreditGroups are authoritative; Packages are fallback, not a second copy of the same credits. */
    static String workBuddySummary(JSONObject summary) {
        if (summary == null) throw new IllegalArgumentException("missing summary");
        BigDecimal total=BigDecimal.ZERO, used=BigDecimal.ZERO, left=BigDecimal.ZERO;
        int groups=0, bags=0;
        JSONArray gs=summary.optJSONArray("CreditGroups"), ps=summary.optJSONArray("Packages");
        if (gs != null) for (int i=0;i<gs.length();i++) {
            JSONObject p=gs.optJSONObject(i); if (!hasTotal(p)) continue;
            total=total.add(number(p.opt("CycleTotalCapacity")));
            used=used.add(number(p.opt("CycleUsedCapacity"))); groups++;
        }
        boolean hasGroups=gs != null && gs.length() > 0;
        if (ps != null) for (int i=0;i<ps.length();i++) {
            JSONObject p=ps.optJSONObject(i); if (p == null) continue;
            if (hasTotal(p)) {
                if (!hasGroups) { total=total.add(number(p.opt("CycleTotalCapacity"))); used=used.add(number(p.opt("CycleUsedCapacity"))); groups++; }
            } else if (p.opt("CycleRemainCapacity") != null && p.opt("CycleRemainCapacity") != JSONObject.NULL
                    && !p.opt("CycleRemainCapacity").toString().isEmpty()) {
                left=left.add(number(p.opt("CycleRemainCapacity"))); bags++;
            }
        }
        if (groups == 0 && bags == 0) throw new IllegalArgumentException("unparseable summary");
        String value=groups > 0 ? compact(used)+"/"+compact(total) : "";
        if (bags > 0) value+=(value.isEmpty() ? "" : " · ")+"预付剩余 "+compact(left);
        return value;
    }
}
