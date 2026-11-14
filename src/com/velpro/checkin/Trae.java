package com.velpro.checkin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** Trae CN protocol: device identity, browser authorization, bound token exchange, ug check-in. Pure Java + org.json. */
public class Trae {
    public static final String WEB_HOST = "https://www.trae.cn";
    public static final String AUTH_HOST = "https://api.trae.cn";
    public static final String CLIENT_ID = "ono9krqynydwx5";
    public static final String BRAND = "ASUS TUF Gaming A15 FA507RM_FA507RM";
    public static final String IDE_VERSION = "3.3.65";
    public static final String PLUGIN_VERSION = "2.3.24254";
    public static final String CLIENT_VERSION = "0.1.69";
    private static final String DEVICE_EXCHANGE_PATH = "/trae/api/v3/oauth/ExchangeToken";
    private static final String STATUS_PATH = "/trae/api/v2/ug/checkin_credits/status";
    private static final String CLAIM_PATH = "/trae/api/v2/ug/checkin_credits/claim";
    private static final String[][] AS = {
        {"en1oxy7wnw8j9n", "SOLO_PC", "0.1.64"},
        {CLIENT_ID, "IDE_PC", "3.3.99"}
    };

    private final String webHost, authHost;
    private final int connectMs, readMs;

    public Trae() { this(WEB_HOST, AUTH_HOST, 12000, 20000); }
    public Trae(String webHost, String authHost, int connectMs, int readMs) {
        this.webHost = trim(webHost); this.authHost = trim(authHost);
        this.connectMs = connectMs; this.readMs = readMs;
    }
    private static String trim(String s) { s = s.endsWith("/") ? s.substring(0, s.length() - 1) : s; return s; }

    public static class Device {
        public String deviceId = "", machineId = "", publicPem = "", privatePem = "";
    }
    public static class Auth {
        public String token = "", refresh = "", uid = "", user = "";
        public String deviceId = "", machineId = "", deviceKey = "", publicPem = "";
        public long expires;
        public String clientId = CLIENT_ID;
    }
    public static class Proof {
        public final String signature, nonce; public final long timestamp;
        public Proof(String signature, long timestamp, String nonce) { this.signature = signature; this.timestamp = timestamp; this.nonce = nonce; }
    }
    public static class R {
        public String outcome = "failed", message = "", balance = "";
        public int credit;
        public boolean needsAuth;
        public boolean ok() { return "done".equals(outcome) || "claimed".equals(outcome); }
    }
    public interface Saver { void save(Auth a); }

    private static final class Resp { final int status; final String text; Resp(int s, String t) { status = s; text = t; } }
    public static final class Api extends Exception {
        final int status, code;
        Api(int status, int code, String msg) { super(msg); this.status = status; this.code = code; }
    }

    public static Device newDevice() {
        Device d = new Device();
        d.deviceId = digits(16);
        d.machineId = hex(16);
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            d.publicPem = pem("PUBLIC KEY", kp.getPublic().getEncoded());
            d.privatePem = pem("PRIVATE KEY", kp.getPrivate().getEncoded());
        } catch (Exception e) { throw new RuntimeException(e); }
        return d;
    }

    static String digits(int n) {
        SecureRandom r = new SecureRandom(); StringBuilder s = new StringBuilder(n);
        for (int i = 0; i < n; i++) s.append((char) ('0' + (i == 0 ? 1 + r.nextInt(9) : r.nextInt(10))));
        return s.toString();
    }
    static String hex(int bytes) { SecureRandom r = new SecureRandom(); byte[] b = new byte[bytes]; r.nextBytes(b); return hex(b); }
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(b.length * 2); for (byte x : b) s.append(String.format("%02x", x & 0xff)); return s.toString(); }
    private static String pem(String type, byte[] der) {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder s = new StringBuilder("-----BEGIN " + type + "-----\n");
        for (int i = 0; i < b64.length(); i += 64) s.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        return s.append("-----END " + type + "-----\n").toString();
    }
    private static byte[] depem(String pem) {
        StringBuilder s = new StringBuilder();
        for (String line : pem.split("\\r?\\n")) if (!line.contains("-----")) s.append(line.trim());
        return Base64.getDecoder().decode(s.toString());
    }
    public static PrivateKey privateKey(String pem) {
        try { return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(depem(pem))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    public static PublicKey publicKey(String pem) {
        try { return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(depem(pem))); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String ugDeviceId(String deviceId, String uid) {
        String id = deviceId == null ? "" : deviceId;
        if (id.matches("\\d{15}")) return id;
        if (id.isEmpty() && (uid == null || uid.isEmpty())) return id;
        byte[] digest = sha256(("trae-cn-device\u0000" + id + "\u0000" + (uid == null ? "" : uid)).getBytes(StandardCharsets.UTF_8));
        String h = hex(digest).substring(0, 30);
        java.math.BigInteger n = new java.math.BigInteger(h, 16);
        java.math.BigInteger out = new java.math.BigInteger("100000000000000").add(n.mod(new java.math.BigInteger("900000000000000")));
        return out.toString();
    }
    static byte[] seeded(String seed, String salt, int n) {
        byte[] out = new byte[n]; int filled = 0;
        for (int i = 0; filled < n; i++) {
            MessageDigest md = sha256Digest();
            md.update((salt + ":" + seed).getBytes(StandardCharsets.UTF_8));
            md.update(new byte[]{(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
            byte[] d = md.digest();
            int take = Math.min(d.length, n - filled);
            System.arraycopy(d, 0, out, filled, take); filled += take;
        }
        return out;
    }
    public static String shapeDevice(String uid) {
        byte[] b = seeded(uid == null ? "" : uid, "devid", 15);
        StringBuilder s = new StringBuilder(15);
        for (byte x : b) s.append((char) ('0' + ((x & 0xff) % 10)));
        return s.toString();
    }
    public static String shapeMarketUser(String uid) {
        byte[] b = seeded(uid == null ? "" : uid, "market", 16);
        b[6] = (byte) ((b[6] & 0x0f) | 0x40);
        b[8] = (byte) ((b[8] & 0x3f) | 0x80);
        String h = hex(b);
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20);
    }
    public static String sessionId(String uid) { return hex(seeded(uid == null ? "" : uid, "sess", 32)); }

    private static MessageDigest sha256Digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new RuntimeException(e); } }
    private static byte[] sha256(byte[] in) { return sha256Digest().digest(in); }

    public static String proofText(String path, String clientId, String refresh, long timestamp, String nonce) {
        return String.join("\n", "POST", path, clientId, refresh, String.valueOf(timestamp), nonce);
    }
    public static Proof proof(String path, String clientId, String refresh, String privatePem, long timestamp, String nonce) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(privateKey(privatePem));
            s.update(proofText(path, clientId, refresh, timestamp, nonce).getBytes(StandardCharsets.UTF_8));
            return new Proof(Base64.getEncoder().encodeToString(s.sign()), timestamp, nonce);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static long when(Object o, long fallback) {
        if (o instanceof Number) { long n = ((Number) o).longValue(); return n < 100_000_000_000L ? n * 1000 : n; }
        if (o != null) { try { long n = (long) Double.parseDouble(o.toString()); return n < 100_000_000_000L ? n * 1000 : n; } catch (Exception ignored) { } }
        return fallback;
    }
    private static int num(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o != null) try { return (int) Double.parseDouble(o.toString()); } catch (Exception ignored) { }
        return 0;
    }

    private Resp request(String host, String method, String path, Map<String, String> headers, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(host + path).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(connectMs); c.setReadTimeout(readMs);
        if (STATUS_PATH.equals(path)) c.setInstanceFollowRedirects(false);
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8); c.setDoOutput(true);
            if (STATUS_PATH.equals(path)) c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream o = c.getOutputStream()) { o.write(bytes); }
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = "";
        if (in != null) { try (InputStream i = in; ByteArrayOutputStream b = new ByteArrayOutputStream()) { byte[] buf = new byte[4096]; int n; while ((n = i.read(buf)) != -1) b.write(buf, 0, n); text = b.toString("UTF-8"); } }
        c.disconnect();
        return new Resp(status, text);
    }
    private static JSONObject parse(String text) { try { return new JSONObject(text); } catch (Exception e) { return new JSONObject(); } }
    /** Android's org.json put() has a checked exception; the desktop fixture jar does not. */
    private static void put(JSONObject j, String key, Object value) {
        try { j.put(key, value); } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static JSONObject resultOf(JSONObject root) {
        JSONObject r = root.optJSONObject("Result");
        return r != null ? r : root.optJSONObject("result");
    }

    private JSONObject exchange(String refresh, String clientId) throws IOException, Api {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        JSONObject body = new JSONObject();
        put(body,"ClientID",clientId); put(body,"RefreshToken",refresh); put(body,"ClientSecret","-"); put(body,"UserID","");
        Resp r = request(authHost, "POST", "/cloudide/api/v3/trae/oauth/ExchangeToken", h, body.toString());
        JSONObject env = parse(r.text); JSONObject v = resultOf(env);
        if (v == null) v = env;
        String token = v.optString("Token", v.optString("token", ""));
        if (r.status < 200 || r.status >= 300 || token.isEmpty()) {
            String msg = v.optString("Message", v.optString("message", ""));
            if (msg.isEmpty()) msg = "HTTP " + r.status;
            throw new Api(r.status, env.optInt("code", 0), msg);
        }
        JSONObject out = new JSONObject();
        put(out,"Token",token);
        put(out,"RefreshToken",v.optString("RefreshToken", v.optString("refreshToken", refresh)));
        put(out,"ExpiresAt",v.has("TokenExpireAt") ? v.opt("TokenExpireAt") : JSONObject.NULL);
        return out;
    }

    public Auth completeSignIn(String host, String refresh, String uid, String name, Device d) throws IOException, Api {
        Auth a = new Auth();
        a.clientId = CLIENT_ID; a.refresh = refresh; a.uid = uid; a.user = name;
        a.deviceId = d.deviceId; a.machineId = d.machineId; a.deviceKey = d.privatePem; a.publicPem = d.publicPem;
        JSONObject x = exchange(refresh, CLIENT_ID);
        a.token = x.optString("Token"); a.refresh = x.optString("RefreshToken", refresh); a.expires = when(x.opt("ExpiresAt"), 0);
        if (a.token.isEmpty()) throw new IOException("Trae 未返回登录令牌");
        boundExchange(a);
        return a;
    }

    /** Bind an already-issued browser token without spending the refresh token on an exchange. */
    public Auth bind(Auth a) throws IOException, Api { boundExchange(a); return a; }

    private static Auth copy(Auth a) {
        Auth b = new Auth();
        b.token = a.token; b.refresh = a.refresh; b.uid = a.uid; b.user = a.user;
        b.deviceId = a.deviceId; b.machineId = a.machineId; b.deviceKey = a.deviceKey;
        b.publicPem = a.publicPem; b.expires = a.expires; b.clientId = a.clientId;
        return b;
    }
    /** Trae refresh tokens are one-shot: persist the rotated pair immediately. */
    public Auth refresh(Auth a) throws IOException, Api { return refresh(a, null); }
    public Auth refresh(Auth a, Saver saver) throws IOException, Api {
        Auth next = copy(a);
        JSONObject x = exchange(next.refresh, next.clientId);
        next.token = x.optString("Token");
        next.refresh = x.optString("RefreshToken", next.refresh);
        next.expires = when(x.opt("ExpiresAt"), next.expires);
        if (next.token.isEmpty()) throw new IOException("Trae 刷新未返回令牌");
        boundExchange(next);
        if (saver != null) saver.save(next);
        return next;
    }

    private void boundExchange(Auth a) throws IOException, Api {
        String authToken = a.token;
        boolean any = false;
        for (String[] as : AS) {
            String clientId = as[0], platform = as[1], version = as[2];
            long at = System.currentTimeMillis() / 1000;
            String nonce = hex(16);
            JSONObject body = new JSONObject();
            put(body,"ClientID",clientId); put(body,"ClientSecret","");
            put(body,"RefreshToken",a.refresh);
            JSONObject info = new JSONObject();
            put(info,"DeviceID",a.deviceId); put(info,"MachineID",a.machineId);
            put(info,"PlatformCode",platform); put(info,"DeviceType","PC");
            put(info,"DeviceName",""); put(info,"DeviceModel","");
            put(info,"ClientVersion",version); put(info,"DevicePublicKey",a.publicPem);
            put(info,"DeviceBrand",BRAND); put(info,"DeviceCPU","AMD");
            put(info,"OSInfo","Windows"); put(info,"OSVersion","10.0.22631");
            put(body,"DeviceInfo",info);
            Proof p = proof(DEVICE_EXCHANGE_PATH, clientId, a.refresh, a.deviceKey, at, nonce);
            JSONObject pr = new JSONObject();
            put(pr,"Signature",p.signature); put(pr,"Timestamp",p.timestamp); put(pr,"Nonce",p.nonce);
            put(body,"DeviceProof",pr);
            put(body,"IDEVersion",version);
            Map<String, String> h = new LinkedHashMap<>();
            h.put("Content-Type", "application/json");
            h.put("Authorization", "Cloud-IDE-JWT " + authToken);
            Resp r = request(authHost, "POST", DEVICE_EXCHANGE_PATH, h, body.toString());
            JSONObject env = parse(r.text); JSONObject v = resultOf(env);
            if (v == null) v = env;
            String token = v.optString("Token", v.optString("token", ""));
            if (r.status < 200 || r.status >= 300 || token.isEmpty()) {
                String msg = v.optString("Message", v.optString("message", ""));
                if (msg.isEmpty()) msg = "HTTP " + r.status;
                continue;
            }
            a.token = token;
            a.refresh = v.optString("RefreshToken", v.optString("refreshToken", a.refresh));
            a.expires = when(v.has("TokenExpireAt") ? v.opt("TokenExpireAt") : JSONObject.NULL, a.expires);
            any = true;
        }
        if (!any) throw new Api(0, 0, "Trae 设备绑定换新失败");
    }

    private Map<String, String> ugHeaders(Auth a) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Authorization", "Cloud-IDE-JWT " + a.token);
        h.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) TRAE-SOLO-CN/" + CLIENT_VERSION + " Chrome/120.0.6099.291 Electron/28.2.10 Safari/537.36");
        h.put("x-device-id", ugDeviceId(a.deviceId, a.uid));
        h.put("x-device-brand", BRAND);
        h.put("x-device-type", "Windows");
        h.put("x-os-version", "10.0.22631");
        h.put("x-app-version", CLIENT_VERSION);
        h.put("Accept", "application/json");
        return h;
    }
    private Map<String, String> shapeHeaders(Auth a) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Cloud-IDE-JWT " + a.token);
        h.put("Accept", "*/*");
        h.put("Accept-Language", "zh-CN");
        h.put("Content-Type", "application/json");
        h.put("User-Agent", "VSCode 1.107.1 (TRAE SOLO CN)");
        h.put("x-market-client-id", "VSCode 1.107.1");
        h.put("x-market-user-id", shapeMarketUser(a.uid));
        h.put("x-user-region", "CN");
        h.put("x-device-id", shapeDevice(a.uid));
        h.put("x-lgw-req-sdk-type", "3");
        h.put("package-type", "stable_cn");
        h.put("x-lscbd-aid", "787976");
        h.put("x-lscbd-platform", "windows");
        h.put("app-version", "0.1.45");
        h.put("x-tt-trace-id", "00-" + hex(8) + "-01");
        h.put("vscode-sessionid", sessionId(a.uid));
        h.put("x-request-id", UUID.randomUUID().toString());
        h.put("sec-fetch-dest", "empty");
        h.put("sec-fetch-mode", "no-cors");
        h.put("sec-fetch-site", "none");
        return h;
    }

    public JSONObject balanceStatus(Auth auth) throws IOException {
        JSONObject response=postStatus(auth);
        int status=response.optInt("status");
        if (status < 200 || status >= 300 || response.optInt("code") != 0)
            throw new IOException("Trae 余额查询失败");
        return response.optJSONObject("body");
    }

    private JSONObject postStatus(Auth a) throws IOException {
        Resp r = request(authHost, "POST", STATUS_PATH, ugHeaders(a), "{}");
        JSONObject env = parse(r.text);
        JSONObject out = new JSONObject();
        put(out,"status",r.status); put(out,"code",env.optInt("code", 0));
        put(out,"body",env);
        return out;
    }
    /** Read-only credential check used immediately after login; it never sends a claim. */
    public SiteRegistry.Verify verify(Auth a) {
        try {
            JSONObject st = postStatus(a);
            int code = st.optInt("code"), status = st.optInt("status");
            JSONObject body = st.optJSONObject("body");
            String message = body == null ? "" : body.optString("message", "");
            if (status == 401 || code == 1001 || code == 20101)
                return SiteRegistry.Verify.invalid("凭据无效，请重新登录：" + message);
            if (code != 0) return SiteRegistry.Verify.network("网络异常，无法验证：" + message);
            return SiteRegistry.Verify.pass();
        } catch (Exception e) {
            return SiteRegistry.Verify.network("网络异常，无法验证：" + e.getMessage());
        }
    }

    private JSONObject postClaim(Auth a, Map<String, String> headers, String body) throws IOException {
        Resp r = request(authHost, "POST", CLAIM_PATH, headers, body);
        JSONObject env = parse(r.text);
        JSONObject out = new JSONObject();
        put(out,"status",r.status); put(out,"code",env.optInt("code", 0));
        put(out,"body",env);
        return out;
    }

    public R checkIn(Auth a) {
        R r = new R();
        try {
            JSONObject st = postStatus(a);
            int stCode = st.optInt("code"), stStatus = st.optInt("status");
            if (stStatus == 401 || stCode == 1001 || stCode == 20101) { r.outcome = "failed"; r.needsAuth = true; r.message = "Trae 登录已失效，请到设置页重新授权"; return r; }
            if (stCode != 0) { r.outcome = "failed"; r.message = st.optJSONObject("body").optString("message", "状态查询失败"); return r; }
            JSONObject body = st.optJSONObject("body");
            if (body.optBoolean("checked_in")) {
                r.outcome = "done"; r.credit = num(body.opt("credits"));
                r.balance = stStatus >= 200 && stStatus < 300 ? Net.confirmedBalance(body,"credits") : "";
                return r;
            }
            if (!body.optBoolean("enable", true)) { r.outcome = "inactive"; r.message = "Trae 活动未开启"; return r; }

            JSONObject claim = postClaim(a, ugHeaders(a), "{\"req_source\":1}");
            int cCode = claim.optInt("code"), cStatus = claim.optInt("status");
            JSONObject cBody = claim.optJSONObject("body");
            if (cStatus == 401 || cCode == 1001 || cCode == 20101) { r.outcome = "failed"; r.needsAuth = true; r.message = "Trae 登录已失效，请到设置页重新授权"; return r; }
            if (cCode == 9095) { r.outcome = "ineligible"; r.message = "该设备今天已签到"; return r; }
            if (cCode == 9074 && a.uid != null && !a.uid.isEmpty()) {
                try { postClaim(a, shapeHeaders(a), "{}"); }
                catch (IOException e) { /* still confirm with read-only status; never replay */ }
            }
            // Claim already fired: never fire it again. Recheck the read-only status to confirm.
            JSONObject back;
            try { back = postStatus(a); }
            catch (IOException e) { r.outcome = "ineligible"; r.message = "签到请求已发出但无法确认，当天不再重试"; return r; }
            int bCode = back.optInt("code"), bStatus = back.optInt("status");
            if (bStatus == 401 || bCode == 1001 || bCode == 20101) { r.outcome = "failed"; r.needsAuth = true; r.message = "Trae 登录已失效，请到设置页重新授权"; return r; }
            if (bCode != 0) { r.outcome = "ineligible"; r.message = back.optJSONObject("body").optString("message", "签到后确认失败"); return r; }
            JSONObject bb = back.optJSONObject("body");
            if (!bb.optBoolean("checked_in")) { r.outcome = "ineligible"; r.message = "签到请求返回成功但当天未入账，当天不再重试"; return r; }
            int credit = num(cBody.opt("credits"));
            if (credit == 0) credit = num(cBody.opt("extra_credits"));
            if (credit == 0) credit = num(bb.opt("credits"));
            r.outcome = "claimed"; r.credit = credit;
            r.balance = bStatus >= 200 && bStatus < 300 ? Net.confirmedBalance(bb,"credits") : "";
        } catch (IOException e) {
            r.outcome = "failed"; r.message = e.getMessage();
        }
        return r;
    }
}
