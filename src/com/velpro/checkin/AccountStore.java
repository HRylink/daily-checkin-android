package com.velpro.checkin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Account inventory and credential mapping. Pure Java so it can be exercised on the JVM;
 * Android injects Store through KV. Old v1.3 flat keys remain readable and are migrated once.
 */
public final class AccountStore {
    private static final String LIST = "accounts";
    private static final String PENDING = "pending_accounts";
    private static final String CUSTOM = "custom_sites";
    private static final Set<String> SECRET_FIELDS = new HashSet<>(Arrays.asList(
            "access", "refresh", "uid", "expires", "token", "device_id", "machine_id",
            "device_key", "public_key", "expires"));
    private static final String[] KNOWN_KEYS = {
            "wb_access", "trae_device_key", "workbuddy_cn_access"
    };

    public interface KV {
        String get(String key);
        boolean put(String key, String value);
    }

    public static final class Account {
        public String siteId = "", user = "", error = "";
        public boolean enabled, invalid, listed;
        public SiteRegistry.Custom custom;
        public String customCookie = "";
        public CustomSite.Saver customSaver;
        public WorkBuddy.Auth wb;
        public Trae.Auth trae;
        public WorkBuddy.Saver wbSaver;
        public Trae.Saver traeSaver;
    }

    public static boolean isSecret(String key) {
        if (key == null) return false;
        if (key.equals("pass") || key.equals("ar_cookie")) return true;
        for (String known : KNOWN_KEYS) if (known.equals(key)) return true;
        for (String id : new String[]{"", "workbuddy_cn_", "wb_", "trae_"}) {
            if (!key.startsWith(id)) continue;
            for (String f : SECRET_FIELDS) if (key.equals(id + f)) return true;
        }
        return key.endsWith("_access") || key.endsWith("_refresh") || key.endsWith("_uid")
                || key.endsWith("_expires") || key.endsWith("_token") || key.endsWith("_device_id")
                || key.endsWith("_machine_id") || key.endsWith("_device_key")
                || key.endsWith("_public_key") || key.endsWith("_cookie");
    }

    private static String keyPrefix(String siteId) {
        if ("workbuddy-ai".equals(siteId)) return "wb_";
        if ("trae-cn".equals(siteId)) return "trae_";
        return siteId.replace('-', '_') + "_";
    }
    public static String credentialKey(String siteId, String field) { return keyPrefix(siteId) + field; }

    private static void append(JSONArray array, String value) {
        try { array.put(value); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static Set<String> idsOf(KV kv, String key) {
        Set<String> out = new LinkedHashSet<>();
        JSONArray array;
        try { array = new JSONArray(get(kv, key)); } catch (Exception e) { array = new JSONArray(); }
        for (int i = 0; i < array.length(); i++) {
            String id = array.optString(i, "");
            if (SiteRegistry.byId(id) != null || customConfigs(kv).containsKey(id)) out.add(id);
        }
        return out;
    }
    private static void writeIds(KV kv, String key, Set<String> ids) {
        JSONArray out = new JSONArray();
        for (SiteRegistry.Site s : SiteRegistry.all(kv)) if (ids.contains(s.id)) append(out, s.id);
        put(kv, key, out.toString());
    }
    private static void put(KV kv, String key, String value) {
        kv.put(key, value == null ? "" : value);
    }
    private static String get(KV kv, String key) {
        String v = kv.get(key);
        return v == null ? "" : v;
    }
    private static long number(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }
    private static boolean enabled(KV kv, String siteId, boolean fallback) {
        String raw = get(kv, siteId.equals("workbuddy-ai") ? "wb_enabled"
                : siteId.equals("trae-cn") ? "trae_enabled" : siteId.replace('-', '_') + "_enabled");
        return raw.isEmpty() ? fallback : "1".equals(raw);
    }
    private static void setEnabledKey(KV kv, String siteId, boolean on) {
        put(kv, siteId.equals("workbuddy-ai") ? "wb_enabled"
                : siteId.equals("trae-cn") ? "trae_enabled" : siteId.replace('-', '_') + "_enabled",
                on ? "1" : "0");
    }
    private static String metaKey(String siteId, String field) { return keyPrefix(siteId) + field; }

    private static boolean hasLegacy(KV kv) {
        return !get(kv, "user").isEmpty() || !get(kv, "pass").isEmpty()
                || !get(kv, "ar_cookie").isEmpty() || !get(kv, "wb_access").isEmpty()
                || !get(kv, "trae_token").isEmpty() || !get(kv, "wb_enabled").isEmpty()
                || !get(kv, "trae_enabled").isEmpty();
    }
    /** A login that failed verification keeps its credentials, but is not a verified account. */
    public static List<Account> load(KV kv, boolean githubCookies) {
        if (kv == null) return new ArrayList<>();
        String raw = get(kv, LIST);
        if (raw.isEmpty()) {
            Set<String> pending = idsOf(kv, PENDING);
            List<String> ids = new ArrayList<>();
            // v1.2 parity: the two fixed sites stay listed for any old install, so the user
            // can fill or delete them; a WorkBuddy/Trae-only install is listed on its own.
            if (hasLegacy(kv) || githubCookies) { ids.add("77625"); ids.add("agentrouter"); }
            if (!get(kv, "wb_access").isEmpty() && !pending.contains("workbuddy-ai")) ids.add("workbuddy-ai");
            if (!get(kv, "trae_token").isEmpty() && !pending.contains("trae-cn")) ids.add("trae-cn");
            JSONArray a = new JSONArray();
            for (String id : ids) append(a, id);
            put(kv, LIST, a.toString());
        }
        Set<String> listed = idsOf(kv, LIST), pending = idsOf(kv, PENDING);
        List<Account> out = new ArrayList<>();
        for (SiteRegistry.Site site : SiteRegistry.all(kv)) {
            String id = site.id;
            boolean inList = listed.contains(id), pendingInvalid = pending.contains(id);
            if (!inList && !pendingInvalid) continue;
            Account a = new Account();
            a.siteId = id;
            a.listed = inList;
            a.enabled = inList && enabled(kv, id, true);
            a.invalid = pendingInvalid || "1".equals(get(kv, metaKey(id, "invalid")));
            a.error = get(kv, metaKey(id, "error"));
            if ("workbuddy".equals(site.kind)) {
                a.user = get(kv, credentialKey(id, "user"));
                String access = get(kv, credentialKey(id, "access"));
                if (!access.isEmpty()) {
                    WorkBuddy.Auth auth = new WorkBuddy.Auth();
                    auth.access = access;
                    auth.refresh = get(kv, credentialKey(id, "refresh"));
                    auth.uid = get(kv, credentialKey(id, "uid"));
                    auth.user = a.user;
                    auth.expires = number(get(kv, credentialKey(id, "expires")));
                    a.wb = auth;
                    final String sid = id;
                    a.wbSaver = next -> saveWorkBuddy(kv, sid, next);
                }
            } else if ("trae".equals(site.kind)) {
                a.user = get(kv, credentialKey(id, "user"));
                String token = get(kv, credentialKey(id, "token"));
                if (!token.isEmpty()) {
                    Trae.Auth auth = new Trae.Auth();
                    auth.token = token;
                    auth.refresh = get(kv, credentialKey(id, "refresh"));
                    auth.uid = get(kv, credentialKey(id, "uid"));
                    auth.user = a.user;
                    auth.expires = number(get(kv, credentialKey(id, "expires")));
                    auth.deviceId = get(kv, credentialKey(id, "device_id"));
                    auth.machineId = get(kv, credentialKey(id, "machine_id"));
                    auth.deviceKey = get(kv, credentialKey(id, "device_key"));
                    auth.publicPem = get(kv, credentialKey(id, "public_key"));
                    a.trae = auth;
                    final String sid = id;
                    a.traeSaver = next -> saveTrae(kv, sid, next);
                }
            } else if ("custom".equals(site.kind)) {
                a.custom = customConfigs(kv).get(id);
                a.customCookie = get(kv, customCookieKey(id));
                a.customSaver = new CustomSite.Saver() {
                    public boolean blocked() { return customBlocked(kv, id); }
                    public boolean beforePost() { return beginCustomPost(kv, id); }
                    public void after(CustomSite.Result r) { finishCustom(kv, id, r); }
                };
                a.user = a.custom == null ? "" : a.custom.name;
            } else if ("legacy25".equals(site.kind)) {
                a.user = get(kv, "user");
            } else {
                a.user = githubCookies ? "GitHub 已授权" : "";
            }
            out.add(a);
        }
        return out;
    }

    public static void add(KV kv, String siteId, String user) {
        Set<String> ids = idsOf(kv, LIST);
        ids.add(siteId);
        writeIds(kv, LIST, ids);
        Set<String> pending = idsOf(kv, PENDING);
        if (pending.remove(siteId)) writeIds(kv, PENDING, pending);
        put(kv, metaKey(siteId, "user"), user);
        put(kv, metaKey(siteId, "invalid"), "");
        put(kv, metaKey(siteId, "error"), "");
        setEnabledKey(kv, siteId, true);
    }

    public static void setEnabled(KV kv, String siteId, boolean on) {
        setEnabledKey(kv, siteId, on);
    }

    public static void markInvalid(KV kv, String siteId, String message) {
        if (!idsOf(kv, LIST).contains(siteId)) {
            Set<String> pending = idsOf(kv, PENDING);
            pending.add(siteId);
            writeIds(kv, PENDING, pending);
        }
        put(kv, metaKey(siteId, "invalid"), "1");
        put(kv, metaKey(siteId, "error"), message == null ? "" : message);
        setEnabledKey(kv, siteId, false);
    }

    public static boolean saveBalance(KV kv, String siteId, String value, long now) {
        if (value == null || value.isEmpty() || siteId == null || siteId.isEmpty()) return false;
        if (!kv.put(siteId + "_balance",value)) return false;
        if (!kv.put(siteId + "_balance_at",java.time.Instant.ofEpochMilli(now).toString())) return false;
        kv.put(siteId + "_balance_failed","");
        return true;
    }
    public static void balanceFailed(KV kv, String siteId) { kv.put(siteId + "_balance_failed","1"); }
    public static String freshness(String at, long now) {
        try {
            java.time.Instant stamp=java.time.Instant.parse(at);
            long age=Math.max(0,now-stamp.toEpochMilli());
            if (age < 60_000) return "刚刚";
            java.time.ZoneId zone=java.time.ZoneId.systemDefault();
            java.time.LocalDate day=stamp.atZone(zone).toLocalDate();
            if (day.equals(java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()))
                return age < 3_600_000 ? (age/60_000)+"分钟前" : "今天";
            return day.toString();
        } catch (Exception ignored) { return ""; }
    }
    private static String shortBalance(String s) {
        if (s.codePointCount(0,s.length()) <= 100) return s;
        return s.substring(0,s.offsetByCodePoints(0,100)) + "…";
    }
    public static String balanceText(KV kv, String siteId, long now) {
        String value=get(kv,siteId + "_balance");
        String text;
        if (value.isEmpty()) text="签到后显示";
        else {
            if (siteId.startsWith("custom_")) text="最近领取（" + ("1".equals(get(kv,siteId + "_balance_ok")) ? "成功" : "失败") + "）：" + shortBalance(value);
            else text=("agentrouter".equals(siteId) ? "余额：$" : "积分：") + value;
            String age=freshness(get(kv,siteId + "_balance_at"),now);
            if (!age.isEmpty()) text += " · " + age;
        }
        if ("1".equals(get(kv,siteId + "_balance_failed"))) text += " · 查询失败";
        return text;
    }
    /** Called only with a result attributed to its exact ID, never a potentially duplicate display name. */
    public static void recordResult(KV kv, String siteId, Net.R r, long now) {
        if (siteId == null || siteId.isEmpty() || r == null) return;
        if ("agentrouter".equals(siteId) && r.loggedIn && r.data != null && r.data.optLong("id") > 0)
            kv.put("agentrouter_uid",String.valueOf(r.data.optLong("id")));
        if (siteId.startsWith("custom_")) {
            String raw=r.balance.isEmpty() ? r.msg : r.balance;
            if (saveBalance(kv,siteId,shortBalance(raw),now)) kv.put(siteId + "_balance_ok",r.ok ? "1" : "0");
        } else if (r.ok && !r.balance.isEmpty()) saveBalance(kv,siteId,r.balance,now);
    }

    public static void remove(KV kv, String siteId) {
        Set<String> ids = idsOf(kv, LIST);
        ids.remove(siteId);
        writeIds(kv, LIST, ids);
        Set<String> pending = idsOf(kv, PENDING);
        pending.remove(siteId);
        writeIds(kv, PENDING, pending);
        if (customConfigs(kv).containsKey(siteId)) {
            Map<String,SiteRegistry.Custom> configs = customConfigs(kv);
            configs.remove(siteId);
            writeCustomConfigs(kv, configs);
            put(kv, customCookieKey(siteId), "");
            put(kv, metaKey(siteId, "user"), "");
        }
        put(kv, metaKey(siteId, "post_day"), "");
        for (String field : new String[]{"balance","balance_at","balance_failed","balance_ok"}) put(kv,siteId + "_" + field,"");
        if ("agentrouter".equals(siteId)) put(kv,"agentrouter_uid","");
        String prefix = keyPrefix(siteId);
        String[] fields;
        if ("trae-cn".equals(siteId)) fields = new String[]{"enabled", "token", "refresh", "uid", "user",
                "device_id", "machine_id", "device_key", "public_key", "expires", "invalid", "error"};
        else if ("workbuddy-ai".equals(siteId) || "workbuddy-cn".equals(siteId))
            fields = new String[]{"enabled", "access", "refresh", "uid", "user", "expires", "invalid", "error"};
        else fields = new String[]{"enabled", "invalid", "error"};
        for (String f : fields) put(kv, prefix + f, "");
    }

    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date());
    }
    public static synchronized boolean customBlocked(KV kv, String id) {
        return today().equals(get(kv, metaKey(id, "post_day")));
    }
    /** Durable before-send marker also prevents replay after an Activity/process interruption. */
    public static synchronized boolean beginCustomPost(KV kv, String id) {
        return !customBlocked(kv, id) && kv.put(metaKey(id, "post_day"), today());
    }
    public static synchronized void finishCustom(KV kv, String id, CustomSite.Result r) {
        if (!r.unconfirmed) put(kv, metaKey(id, "post_day"), "");
        if (r.needsAuth) markInvalid(kv, id, r.message);
        Net.R result=new Net.R(); result.ok=r.ok; result.msg=r.message; result.balance=r.text;
        recordResult(kv,id,result,System.currentTimeMillis());
    }

    public static String customCookieKey(String id) { return "custom_" + id + "_cookie"; }

    public static Map<String,SiteRegistry.Custom> customConfigs(KV kv) {
        Map<String,SiteRegistry.Custom> out = new LinkedHashMap<>();
        try {
            JSONArray list = new JSONArray(get(kv, CUSTOM));
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                SiteRegistry.Custom c = SiteRegistry.Custom.fromJson(item);
                if (id.matches("custom_[0-9]+") && CustomSite.validate(c).isEmpty()) out.put(id, c);
            }
        } catch (Exception ignored) { }
        return out;
    }
    private static boolean writeCustomConfigs(KV kv, Map<String,SiteRegistry.Custom> configs) {
        try {
            JSONArray list = new JSONArray();
            for (Map.Entry<String,SiteRegistry.Custom> e : configs.entrySet()) {
                JSONObject item = e.getValue().toJson(); item.put("id", e.getKey()); list.put(item);
            }
            return kv.put(CUSTOM, list.toString());
        } catch (Exception e) { return false; }
    }
    public static String nextCustomId(KV kv) {
        Map<String,SiteRegistry.Custom> configs = customConfigs(kv);
        int n = 0;
        while (configs.containsKey("custom_" + n)) n++;
        return "custom_" + n;
    }
    /** Save config and secret before attempting verification; not yet a listed account. */
    public static boolean saveCustom(KV kv, String id, SiteRegistry.Custom config, String cookie) {
        if (id == null || !id.matches("custom_[0-9]+") || !CustomSite.validate(config).isEmpty()) return false;
        Map<String,SiteRegistry.Custom> configs = customConfigs(kv); configs.put(id, config);
        return writeCustomConfigs(kv, configs) && kv.put(customCookieKey(id), cookie == null ? "" : cookie);
    }
    public static boolean addCustom(KV kv, String id, SiteRegistry.Custom config, String cookie) {
        if (!saveCustom(kv, id, config, cookie)) return false;
        add(kv, id, config.name);
        return idsOf(kv, LIST).contains(id);
    }

    public static void saveWorkBuddy(KV kv, String siteId, WorkBuddy.Auth a) {
        if (a == null) return;
        put(kv, credentialKey(siteId, "access"), a.access);
        put(kv, credentialKey(siteId, "refresh"), a.refresh);
        put(kv, credentialKey(siteId, "uid"), a.uid);
        put(kv, credentialKey(siteId, "user"), a.user);
        put(kv, credentialKey(siteId, "expires"), String.valueOf(a.expires));
    }

    public static void saveTrae(KV kv, String siteId, Trae.Auth a) {
        if (a == null) return;
        put(kv, credentialKey(siteId, "token"), a.token);
        put(kv, credentialKey(siteId, "refresh"), a.refresh);
        put(kv, credentialKey(siteId, "uid"), a.uid);
        put(kv, credentialKey(siteId, "user"), a.user);
        put(kv, credentialKey(siteId, "expires"), String.valueOf(a.expires));
        put(kv, credentialKey(siteId, "device_id"), a.deviceId);
        put(kv, credentialKey(siteId, "machine_id"), a.machineId);
        put(kv, credentialKey(siteId, "device_key"), a.deviceKey);
        put(kv, credentialKey(siteId, "public_key"), a.publicPem);
    }
}
